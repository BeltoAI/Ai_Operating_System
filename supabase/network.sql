-- The SlyOS Network
--
-- Routing lives here. Deciding does not.
--
-- The server holds only what is needed to narrow ten thousand people to two hundred plausible ones:
-- a name, two public sentences, and a handful of coarse tags. Every actual decision — does my owner
-- care about this — happens on the phone, against a brain that is never uploaded.
--
-- So the worst case for a breach of this database is that somebody reads a sentence you wrote on
-- purpose. That property is the product, and the row-level security below is what enforces it
-- rather than a promise in a privacy policy.

-- ─────────────────────────────────────────────────────────────────────────────
-- profiles — the entire public surface of a person
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists profiles (
  user_id       uuid primary key references auth.users(id) on delete cascade,
  handle        text unique,
  name          text,
  photo_url     text,

  -- The two lines. Written by the owner's brain, edited by them, and the only prose here.
  looking_for   text default '',
  open_to       text default '',

  -- Coarse routing only: "payments", "founder", "bay-area". Never anything a stranger could not
  -- read off a business card. Fine-grained matching is the phone's job.
  tags          text[] default '{}',

  -- Nobody is forced to be reachable, and this is the setting that makes joining safe.
  reachability  text default 'vouched' check (reachability in ('open','vouched','closed')),

  -- Earned by making introductions that get accepted; decays. See asks below.
  vouch_weight  real default 1.0,
  vouches_made  int  default 0,
  vouches_kept  int  default 0,

  asks_left     int  default 3,        -- scarcity is the spam defence, not a pricing tier
  tier          text default 'free',
  updated_at    timestamptz default now()
);

alter table profiles enable row level security;

-- Anyone signed in can READ a profile — that is what makes the network navigable.
create policy "profiles are public to members"
  on profiles for select using (auth.role() = 'authenticated');

-- Only you can write yours.
create policy "own profile write"
  on profiles for insert with check (auth.uid() = user_id);
create policy "own profile update"
  on profiles for update using (auth.uid() = user_id);

create index if not exists idx_profiles_tags on profiles using gin (tags);

-- ─────────────────────────────────────────────────────────────────────────────
-- asks — one row per request
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists asks (
  id            uuid primary key default gen_random_uuid(),
  from_user     uuid not null references auth.users(id) on delete cascade,
  kind          text not null check (kind in ('reach','match')),

  -- reach: a named person. match: no target, described by criteria + tags.
  target_user   uuid references auth.users(id) on delete set null,

  criteria      text not null,        -- public-safe. Never the reason behind it.
  tags          text[] default '{}',

  state         text default 'open' check (state in ('open','matched','closed','expired')),

  -- REQUIRED, never null. An ask with no deadline is an ask that runs forever, and two agents with
  -- no clock will still be politely qualifying each other next week. The server closes it.
  expires_at    timestamptz not null default (now() + interval '72 hours'),
  created_at    timestamptz default now()
);

alter table asks enable row level security;

create policy "own asks"
  on asks for all using (auth.uid() = from_user);

-- A candidate may read the ask they were actually sent, and nothing else.
create policy "asks visible to their candidates"
  on asks for select using (
    exists (select 1 from ask_candidates c
            where c.ask_id = asks.id and c.candidate_user = auth.uid())
  );

-- ─────────────────────────────────────────────────────────────────────────────
-- ask_candidates — the fan-out, and the privacy boundary
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists ask_candidates (
  ask_id          uuid not null references asks(id) on delete cascade,
  candidate_user  uuid not null references auth.users(id) on delete cascade,

  -- 'ignored' is written by the phone and seen by nobody. Of two hundred candidates, a hundred and
  -- ninety-eight normally ignore a request and never learn it existed — which is precisely why
  -- receiving costs nothing and the network does not become an inbox to defend.
  state           text default 'sent' check (state in
                    ('sent','ignored','interested','declined','surfaced','accepted')),

  -- Clarification turns used. Enforced HERE rather than in a prompt, because a model asked to stop
  -- talking will eventually not stop.
  turns           int default 0,
  verdict         text check (verdict in ('qualified','not_qualified','need_human')),

  vouched_by      uuid references auth.users(id),
  updated_at      timestamptz default now(),
  primary key (ask_id, candidate_user)
);

alter table ask_candidates enable row level security;

-- THE IMPORTANT ONE. You can only ever see your own candidacy — so nobody, including the asker,
-- can enumerate who was approached. The asker sees counts through the view below, never names.
create policy "only my own candidacy"
  on ask_candidates for all using (auth.uid() = candidate_user);

-- The asker sees rows that reached a human, and only those.
create policy "asker sees engaged candidates"
  on ask_candidates for select using (
    exists (select 1 from asks a where a.id = ask_id and a.from_user = auth.uid())
    and state in ('interested','surfaced','accepted','declined')
  );

-- ─────────────────────────────────────────────────────────────────────────────
-- ask_messages — the agent-to-agent thread
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists ask_messages (
  id          bigserial primary key,
  ask_id      uuid not null references asks(id) on delete cascade,
  from_user   uuid not null references auth.users(id) on delete cascade,
  to_user     uuid not null references auth.users(id) on delete cascade,
  body        text not null,
  turn        int  not null default 0,
  is_agent    boolean default true,
  created_at  timestamptz default now()
);

alter table ask_messages enable row level security;

create policy "messages i sent or received"
  on ask_messages for all using (auth.uid() = from_user or auth.uid() = to_user);

-- ─────────────────────────────────────────────────────────────────────────────
-- The exit criteria the database itself enforces
--
-- Three of the six live here, because the three that matter cannot be trusted to a prompt: the turn
-- cap, the clock, and one-thread-per-pair. The other three — token budget, no-new-information, and
-- a required terminal verdict — are enforced on the phone, where the model runs.
-- ─────────────────────────────────────────────────────────────────────────────

create or replace function enforce_turn_cap() returns trigger as $$
declare used int;
begin
  select coalesce(turns, 0) into used
    from ask_candidates
    where ask_id = new.ask_id and candidate_user in (new.from_user, new.to_user)
    limit 1;

  -- Six total: at most three each. Past that the exchange is not converging, it is looping.
  if used >= 6 then
    raise exception 'turn cap reached for ask %', new.ask_id;
  end if;

  update ask_candidates
     set turns = coalesce(turns, 0) + 1, updated_at = now()
   where ask_id = new.ask_id and candidate_user in (new.from_user, new.to_user);

  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists trg_turn_cap on ask_messages;
create trigger trg_turn_cap before insert on ask_messages
  for each row execute function enforce_turn_cap();

-- Nothing may be added to an expired ask. The clock is absolute and belongs to the server, because
-- a deadline either side can ignore is not a deadline.
create or replace function reject_expired() returns trigger as $$
begin
  if exists (select 1 from asks a
             where a.id = new.ask_id
               and (a.expires_at < now() or a.state in ('closed','expired'))) then
    raise exception 'ask % is closed', new.ask_id;
  end if;
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists trg_not_expired on ask_messages;
create trigger trg_not_expired before insert on ask_messages
  for each row execute function reject_expired();

-- Swept by cron. An ask nobody answered simply ends rather than hanging open forever.
create or replace function expire_stale_asks() returns void as $$
  update asks set state = 'expired'
   where state = 'open' and expires_at < now();
$$ language sql security definer;

-- ─────────────────────────────────────────────────────────────────────────────
-- Scarcity. Three a week on free, and it is the anti-spam mechanism rather than a pricing lever:
-- somebody with three asks writes a good one.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function spend_ask() returns trigger as $$
declare left_now int;
begin
  select asks_left into left_now from profiles where user_id = new.from_user for update;
  if left_now is null or left_now <= 0 then
    raise exception 'no asks left this week';
  end if;
  update profiles set asks_left = asks_left - 1 where user_id = new.from_user;
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists trg_spend_ask on asks;
create trigger trg_spend_ask before insert on asks
  for each row execute function spend_ask();

create or replace function reset_weekly_asks() returns void as $$
  update profiles set asks_left =
    case tier when 'business' then 100 when 'plus' then 20 else 3 end;
$$ language sql security definer;
