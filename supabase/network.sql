-- The SlyOS Network
--
-- Routing lives here. Deciding does not.
--
-- The server holds only what is needed to narrow ten thousand people to two hundred plausible ones:
-- a name, three public sentences, a handful of coarse tags, and one number. Every actual decision —
-- does my owner care about this — happens on the phone, against a brain that is never uploaded.
--
-- So the worst case for a breach of this database is that somebody reads a sentence you wrote on
-- purpose. That property is the product, and the row-level security below is what enforces it
-- rather than a promise in a privacy policy.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- THIS IS A MIGRATION, NOT A FRESH SCHEMA.
--
-- `public.profiles` already exists — it is the account row from ACCOUNT_AND_SYNC.md, keyed on
-- `id`, and it has been there since the first user signed up. An earlier version of this file said
-- `create table if not exists profiles (user_id uuid primary key ...)`, which against a live
-- database does exactly nothing: the table exists, so the statement is skipped, and not one of the
-- columns below is added. Everything downstream then fails with "column profiles.offer does not
-- exist" — which is precisely what happened.
--
-- Safe to run more than once.
-- ─────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────────────────
-- profiles — the entire public surface of a person
-- ─────────────────────────────────────────────────────────────────────────────
alter table public.profiles add column if not exists handle       text;
alter table public.profiles add column if not exists photo_url    text;

-- The three lines. Written by the owner's brain, edited by them, and the only prose here.
alter table public.profiles add column if not exists offer        text default '';
alter table public.profiles add column if not exists looking_for  text default '';
alter table public.profiles add column if not exists open_to      text default '';

-- Coarse routing only: "payments", "founder", "bay-area". Never anything a stranger could not read
-- off a business card. Fine-grained matching is the phone's job.
alter table public.profiles add column if not exists tags         text[] default '{}';

-- Nobody is forced to be reachable, and this is the setting that makes joining safe.
alter table public.profiles add column if not exists reachability text default 'vouched';

-- How many people are in their field. ONE INTEGER, and deliberately nothing more: it is what makes
-- somebody else's galaxy the right size on your screen without a single one of their contacts ever
-- leaving their phone. Whose names those are is not knowable from here, and that is the point.
alter table public.profiles add column if not exists network_size int default 0;

-- Earned by making introductions that get accepted; decays.
alter table public.profiles add column if not exists vouch_weight real default 1.0;
alter table public.profiles add column if not exists vouches_made int  default 0;
alter table public.profiles add column if not exists vouches_kept int  default 0;

alter table public.profiles add column if not exists asks_left    int  default 3;
alter table public.profiles add column if not exists tier         text default 'free';

do $$ begin
  alter table public.profiles add constraint profiles_reachability_ck
    check (reachability in ('open','vouched','closed'));
exception when duplicate_object then null; end $$;

create unique index if not exists idx_profiles_handle on public.profiles (handle)
  where handle is not null;
create index if not exists idx_profiles_tags on public.profiles using gin (tags);

alter table public.profiles enable row level security;

-- Anyone signed in can READ a profile — that is what makes the network navigable, and it is the
-- one thing that has to be true before a second user is visible to a first.
drop policy if exists "profiles are public to members" on public.profiles;
create policy "profiles are public to members"
  on public.profiles for select using (auth.role() = 'authenticated');

-- Only you can write yours.
drop policy if exists "own profile write"  on public.profiles;
drop policy if exists "own profile update" on public.profiles;
create policy "own profile write"
  on public.profiles for insert with check (auth.uid() = id);
create policy "own profile update"
  on public.profiles for update using (auth.uid() = id);

-- ─────────────────────────────────────────────────────────────────────────────
-- asks — one row per request
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists public.asks (
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

alter table public.asks enable row level security;

drop policy if exists "own asks" on public.asks;
create policy "own asks" on public.asks for all using (auth.uid() = from_user);

-- ─────────────────────────────────────────────────────────────────────────────
-- ask_candidates — the fan-out, and the privacy boundary
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists public.ask_candidates (
  ask_id          uuid not null references public.asks(id) on delete cascade,
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

alter table public.ask_candidates enable row level security;

-- THE IMPORTANT ONE. You can only ever see your own candidacy — so nobody, including the asker,
-- can enumerate who was approached.
drop policy if exists "only my own candidacy" on public.ask_candidates;
create policy "only my own candidacy"
  on public.ask_candidates for all using (auth.uid() = candidate_user);

-- The asker sees rows that reached a human, and only those.
drop policy if exists "asker sees engaged candidates" on public.ask_candidates;
create policy "asker sees engaged candidates"
  on public.ask_candidates for select using (
    exists (select 1 from public.asks a where a.id = ask_id and a.from_user = auth.uid())
    and state in ('interested','surfaced','accepted','declined')
  );

-- A candidate may read the ask they were actually sent, and nothing else. Declared here because it
-- references ask_candidates, which does not exist until now.
drop policy if exists "asks visible to their candidates" on public.asks;
create policy "asks visible to their candidates"
  on public.asks for select using (
    exists (select 1 from public.ask_candidates c
            where c.ask_id = asks.id and c.candidate_user = auth.uid())
  );

-- ─────────────────────────────────────────────────────────────────────────────
-- ask_messages — the agent-to-agent thread
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists public.ask_messages (
  id          bigserial primary key,
  ask_id      uuid not null references public.asks(id) on delete cascade,
  from_user   uuid not null references auth.users(id) on delete cascade,
  to_user     uuid not null references auth.users(id) on delete cascade,
  body        text not null,
  turn        int  not null default 0,
  is_agent    boolean default true,
  created_at  timestamptz default now()
);

alter table public.ask_messages enable row level security;

drop policy if exists "messages i sent or received" on public.ask_messages;
create policy "messages i sent or received"
  on public.ask_messages for all using (auth.uid() = from_user or auth.uid() = to_user);

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
    from public.ask_candidates
    where ask_id = new.ask_id and candidate_user in (new.from_user, new.to_user)
    limit 1;

  -- Six total: at most three each. Past that the exchange is not converging, it is looping.
  if used >= 6 then
    raise exception 'turn cap reached for ask %', new.ask_id;
  end if;

  update public.ask_candidates
     set turns = coalesce(turns, 0) + 1, updated_at = now()
   where ask_id = new.ask_id and candidate_user in (new.from_user, new.to_user);

  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists trg_turn_cap on public.ask_messages;
create trigger trg_turn_cap before insert on public.ask_messages
  for each row execute function enforce_turn_cap();

-- Nothing may be added to an expired ask. The clock is absolute and belongs to the server, because
-- a deadline either side can ignore is not a deadline.
create or replace function reject_expired() returns trigger as $$
begin
  if exists (select 1 from public.asks a
             where a.id = new.ask_id
               and (a.expires_at < now() or a.state in ('closed','expired'))) then
    raise exception 'ask % is closed', new.ask_id;
  end if;
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists trg_not_expired on public.ask_messages;
create trigger trg_not_expired before insert on public.ask_messages
  for each row execute function reject_expired();

-- Swept by cron. An ask nobody answered simply ends rather than hanging open forever.
create or replace function expire_stale_asks() returns void as $$
  update public.asks set state = 'expired'
   where state = 'open' and expires_at < now();
$$ language sql security definer;

-- ─────────────────────────────────────────────────────────────────────────────
-- Scarcity. Three a week on free, and it is the anti-spam mechanism rather than a pricing lever:
-- somebody with three asks writes a good one.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function spend_ask() returns trigger as $$
declare left_now int;
begin
  select asks_left into left_now from public.profiles where id = new.from_user for update;
  if left_now is null or left_now <= 0 then
    raise exception 'no asks left this week';
  end if;
  update public.profiles set asks_left = asks_left - 1 where id = new.from_user;
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists trg_spend_ask on public.asks;
create trigger trg_spend_ask before insert on public.asks
  for each row execute function spend_ask();

create or replace function reset_weekly_asks() returns void as $$
  update public.profiles set asks_left =
    case tier when 'business' then 100 when 'plus' then 20 else 3 end;
$$ language sql security definer;
