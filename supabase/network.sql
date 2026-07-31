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

-- ── Breaking the RLS cycle ────────────────────────────────────────────────────
--
-- `asks` needs to be readable by its candidates, and `ask_candidates` needs to be readable by the
-- owner of the ask. Written as plain policies those two are mutually recursive, and Postgres says
-- so at query time: "42P17 infinite recursion detected in policy for relation asks". Both tables
-- become unreadable — which is exactly what happened on the first run.
--
-- The fix is two SECURITY DEFINER functions. They run as the owner, so the lookup inside them does
-- not re-enter RLS, and the cycle is cut. They leak nothing: each answers one boolean about the
-- caller's OWN relationship to one ask.
create or replace function public.is_ask_candidate(a uuid) returns boolean
  language sql security definer stable set search_path = public as $$
    select exists (select 1 from public.ask_candidates c
                   where c.ask_id = a and c.candidate_user = auth.uid());
  $$;

create or replace function public.owns_ask(a uuid) returns boolean
  language sql security definer stable set search_path = public as $$
    select exists (select 1 from public.asks x where x.id = a and x.from_user = auth.uid());
  $$;

grant execute on function public.is_ask_candidate(uuid) to authenticated;
grant execute on function public.owns_ask(uuid) to authenticated;

-- THE IMPORTANT ONE. You can only ever see your own candidacy — so nobody, including the asker,
-- can enumerate who was approached.
drop policy if exists "only my own candidacy" on public.ask_candidates;
create policy "only my own candidacy"
  on public.ask_candidates for all using (auth.uid() = candidate_user);

-- The asker sees rows that reached a human, and only those.
drop policy if exists "asker sees engaged candidates" on public.ask_candidates;
create policy "asker sees engaged candidates"
  on public.ask_candidates for select using (
    public.owns_ask(ask_id) and state in ('interested','surfaced','accepted','declined')
  );

-- A candidate may read the ask they were actually sent, and nothing else.
drop policy if exists "asks visible to their candidates" on public.asks;
create policy "asks visible to their candidates"
  on public.asks for select using (public.is_ask_candidate(id));

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
  -- Somebody who has never opened Where you stand has no profile row, and without this they would
  -- hit "no asks left this week" on their first ever ask — a quota error for a row that does not
  -- exist. Give them the row and their three asks.
  insert into public.profiles (id) values (new.from_user)
    on conflict (id) do nothing;

  select asks_left into left_now from public.profiles where id = new.from_user for update;
  if left_now is null then
    update public.profiles set asks_left = 3 where id = new.from_user;
    left_now := 3;
  end if;
  if left_now <= 0 then
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

-- ─────────────────────────────────────────────────────────────────────────────
-- fan_out — the only matching the server does
--
-- Narrowing by tag overlap is a set operation on words anybody would print on a business card, and
-- it is the whole of the server's contribution. Every actual decision happens afterwards, on two
-- hundred separate phones, against brains this database has never seen.
--
-- It has to be a function rather than a plain insert, because `ask_candidates` is locked to
-- `auth.uid() = candidate_user` — the asker cannot and must not write rows naming other people.
-- SECURITY DEFINER does the write; the guard on the first line is what keeps it honest.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.fan_out(p_ask uuid, p_limit int default 200)
returns int language plpgsql security definer set search_path = public as $$
declare n int; m int; t text[]; floor_n int := 25;
begin
  if not exists (select 1 from asks a where a.id = p_ask and a.from_user = auth.uid()) then
    raise exception 'not your ask';
  end if;
  select tags into t from asks where id = p_ask;

  -- Pass one: people whose routing words overlap. An untagged ask reaches everyone rather than
  -- nobody, because an ask that silently matches zero people is indistinguishable from a broken
  -- feature.
  insert into ask_candidates (ask_id, candidate_user)
  select p_ask, p.id
    from profiles p
   where p.id <> auth.uid()
     and coalesce(p.reachability, 'vouched') <> 'closed'
     and (t is null or cardinality(t) = 0 or p.tags && t)
   order by p.network_size desc nulls last
   limit p_limit
  on conflict do nothing;
  get diagnostics n = row_count;

  -- Pass two: people who have published NO routing words at all.
  --
  -- Tag overlap can only exclude somebody on evidence, and a profile with an empty tag array is
  -- evidence of nothing — it is somebody who has not filled the form in yet. Excluding them made
  -- an ask reach one person out of four in a network where three had simply never published, which
  -- reads as a broken feature and is really an empty column. Being a candidate is anonymous and
  -- costs them nothing, so the safe direction is to include, not to drop.
  --
  -- Only used to top a thin pass one up to a floor, so a well-tagged network never pays for it.
  if n < floor_n then
    insert into ask_candidates (ask_id, candidate_user)
    select p_ask, p.id
      from profiles p
     where p.id <> auth.uid()
       and coalesce(p.reachability, 'vouched') <> 'closed'
       and (p.tags is null or cardinality(p.tags) = 0)
     order by p.network_size desc nulls last
     limit greatest(0, floor_n - n)
    on conflict do nothing;
    get diagnostics m = row_count;
    n := n + coalesce(m, 0);
  end if;

  return n;
end $$;

grant execute on function public.fan_out(uuid, int) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- bridges — the moment somebody says "I know them, and yes"
--
-- This is the only place a real person's name crosses between two users, and it is written by the
-- one who holds the relationship, at the moment they decide to. Nothing infers it, nothing
-- precomputes it, and no overlap between two address books is visible to anyone until this row
-- exists. It is the shared node on the map.
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists public.bridges (
  id          uuid primary key default gen_random_uuid(),
  ask_id      uuid references public.asks(id) on delete cascade,
  asker       uuid not null references auth.users(id) on delete cascade,
  holder      uuid not null references auth.users(id) on delete cascade,
  person      text not null,              -- revealed by the holder, on acceptance, deliberately
  note        text default '',
  -- How close the HOLDER actually is to them, computed on the holder's phone from their own
  -- message history. When ten people know the same person this is what decides who routes it.
  strength    real default 0,
  created_at  timestamptz default now(),
  unique (ask_id, holder)
);

alter table public.bridges enable row level security;

drop policy if exists "bridge parties" on public.bridges;
create policy "bridge parties"
  on public.bridges for select using (auth.uid() in (asker, holder));

drop policy if exists "holder writes bridge" on public.bridges;
create policy "holder writes bridge"
  on public.bridges for insert with check (auth.uid() = holder);

create index if not exists idx_bridges_asker on public.bridges (asker, created_at desc);

-- ─────────────────────────────────────────────────────────────────────────────
-- strength — how close the answering phone actually is, sent BEFORE any name
--
-- The claim "ten people know them, and it routes to the closest" is only true if the asker can
-- rank offers before anybody has revealed who they know. So the number crosses early and the name
-- crosses late. A number between 0 and 1 says nothing about who the person is.
-- ─────────────────────────────────────────────────────────────────────────────
alter table public.ask_candidates add column if not exists strength real default 0;

-- ─────────────────────────────────────────────────────────────────────────────
-- Reachability, said plainly.
--
-- It governs whether somebody can be REACHED — introduced to, written to — and deliberately NOT
-- whether their agent is asked "do you know X". Being in a candidate pool is anonymous and costs
-- nothing: the phone answers privately and, in the overwhelming majority of cases, tells its owner
-- nothing at all. Gating the pool on reachability would mean a brand-new network where nobody has
-- a bridge yet reaches nobody, and it would buy no privacy that the pool does not already have.
--
--   open     — anyone whose ask matches may be introduced to them
--   vouched  — only through somebody they already share a bridge with
--   closed   — not routed at all, in either direction
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.can_reach(p_target uuid) returns boolean
  language sql security definer stable set search_path = public as $$
    select case (select coalesce(reachability,'vouched') from profiles where id = p_target)
      when 'open'   then true
      when 'closed' then false
      else exists (select 1 from bridges b
                   where (b.asker = auth.uid() and b.holder = p_target)
                      or (b.holder = auth.uid() and b.asker = p_target))
    end;
  $$;

grant execute on function public.can_reach(uuid) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Does it work? — the numbers that say so, and the ones that say it does not
--
-- A network like this fails quietly. Asks go out, nothing comes back, and every individual screen
-- still looks fine. These views are what make that visible, and the second one is the important
-- one: the share of asks that died with nobody answering.
-- ─────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────
-- Measurement, and the reason it is a function rather than a view.
--
-- A plain view runs under the caller's row-level security, and RLS deliberately hides `ignored`
-- candidates from the asker — nobody, including the person who asked, may enumerate who was
-- approached. So a view reports "reached 3" when the ask actually reached five, and the one number
-- that matters most (how many found nothing) is structurally always zero.
--
-- COUNTS identify nobody. So they come back through a SECURITY DEFINER function that can see the
-- whole picture, guarded so you only ever get the funnel for an ask you own. You learn how many
-- people looked and said nothing; you never learn which.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.ask_funnel(p_ask uuid)
returns table(
  criteria text, reached bigint, found_nothing bigint, knew_someone bigint,
  still_thinking bigint, declined bigint, introductions bigint, distinct_people bigint,
  best_strength real, time_to_first_answer interval
) language sql security definer stable set search_path = public as $$
  select
    a.criteria,
    (select count(*) from ask_candidates x where x.ask_id = a.id),
    (select count(*) from ask_candidates x where x.ask_id = a.id and x.state = 'ignored'),
    (select count(*) from ask_candidates x where x.ask_id = a.id and x.state in ('interested','accepted')),
    (select count(*) from ask_candidates x where x.ask_id = a.id and x.state = 'sent'),
    (select count(*) from ask_candidates x where x.ask_id = a.id and x.state = 'declined'),
    (select count(*) from bridges y where y.ask_id = a.id),
    (select count(distinct lower(btrim(y.person))) from bridges y where y.ask_id = a.id),
    (select max(x.strength) from ask_candidates x where x.ask_id = a.id),
    (select min(x.updated_at) from ask_candidates x where x.ask_id = a.id) - a.created_at
  from asks a
  where a.id = p_ask and a.from_user = auth.uid();
$$;

grant execute on function public.ask_funnel(uuid) to authenticated;

create or replace function public.network_health()
returns table(
  published_profiles bigint, asks_total bigint, asks_open bigint, introductions bigint,
  pct_asks_introduced numeric, pct_candidates_answered numeric, pct_who_knew_someone numeric,
  avg_reach_per_ask numeric
) language sql security definer stable set search_path = public as $$
  select
    (select count(*) from profiles where coalesce(offer,'') <> '' or coalesce(looking_for,'') <> ''),
    (select count(*) from asks),
    (select count(*) from asks where state = 'open'),
    (select count(*) from bridges),
    -- THE headline, and deliberately the failure one: asks that reached people and got nothing.
    round(100.0 * (select count(distinct ask_id) from bridges)
                / nullif((select count(*) from asks), 0), 1),
    round(100.0 * (select count(*) from ask_candidates where state <> 'sent')
                / nullif((select count(*) from ask_candidates), 0), 1),
    round(100.0 * (select count(*) from ask_candidates where state in ('interested','accepted'))
                / nullif((select count(*) from ask_candidates), 0), 1),
    round((select count(*) from ask_candidates)::numeric
        / nullif((select count(*) from asks), 0), 1);
$$;

grant execute on function public.network_health() to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- The handoff — how a name turns into an actual conversation
--
-- Three tiers, and they really are asymmetric keys:
--
--   PUBLIC     your three lines, your tags, how many people you know.
--              Readable by anyone signed in. You wrote it on purpose.
--   SCOPED     closeness numbers. They exist only inside one ask, they cross before any name
--              does, and they are what lets the asker rank ten offers without knowing who anyone
--              is. A number between 0 and 1 identifies nobody.
--   PRIVATE    names and contact details. They cross ONE HOP AT A TIME, and only when a human
--              taps accept.
--
-- The one-hop rule is the important one and it is what makes this a warm intro rather than a
-- scrape. When Omar says he knows Priya, the asker does NOT get Priya's details — Omar has no
-- right to hand those over and Priya has not been asked yet. The asker gets OMAR: his chosen
-- contact, his note, and a drafted message. Priya's details reach the asker only if Priya says
-- yes, on her own device, in her own time. Double opt-in, enforced by what the database will
-- release rather than by good manners.
-- ─────────────────────────────────────────────────────────────────────────────
alter table public.profiles add column if not exists contact_email  text;
alter table public.profiles add column if not exists contact_phone  text;
alter table public.profiles add column if not exists calendly       text;

-- What you are willing to hand over the moment you agree to make an introduction. Default is the
-- least: an email address, which is the one channel a stranger cannot use to interrupt you.
alter table public.profiles add column if not exists share_on_intro text default 'email';
do $$ begin
  alter table public.profiles add constraint profiles_share_ck
    check (share_on_intro in ('email','calendly','both','none'));
exception when duplicate_object then null; end $$;

-- What the holder's agent actually wrote. Not a transcript of two bots being polite at each other
-- — the useful artefact is the paragraph a human can send, and the reason it thinks this is a fit.
alter table public.bridges add column if not exists intro_draft text default '';
alter table public.bridges add column if not exists why         text default '';

-- ─────────────────────────────────────────────────────────────────────────────
-- reveal_contact — the release gate
--
-- Returns the HOLDER's contact details, and only to the person they agreed to introduce. There is
-- no query that returns anybody else's: a bridge between you and them is the entire condition, and
-- it exists only because they tapped accept.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.reveal_contact(p_ask uuid, p_holder uuid)
returns table(name text, email text, phone text, calendly text, policy text)
language sql security definer stable set search_path = public as $$
  select p.display_name,
         case when p.share_on_intro in ('email','both')    then p.contact_email end,
         case when p.share_on_intro = 'both'               then p.contact_phone end,
         case when p.share_on_intro in ('calendly','both') then p.calendly end,
         coalesce(p.share_on_intro, 'email')
    from profiles p
   where p.id = p_holder
     and exists (select 1 from bridges b
                 where b.ask_id = p_ask and b.holder = p_holder and b.asker = auth.uid());
$$;

grant execute on function public.reveal_contact(uuid, uuid) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Did it work? — per introduction, not per ask
--
-- An ask that produced three names and no conversations is a failure that every screen above this
-- line reports as a success. So the outcome lives on the bridge, it is set by the person who asked,
-- and `no_reply` is a first-class value rather than an absence — a network cannot be improved on
-- the evidence of the introductions that happened to work.
-- ─────────────────────────────────────────────────────────────────────────────
alter table public.bridges add column if not exists outcome text default 'pending';
do $$ begin
  alter table public.bridges add constraint bridges_outcome_ck
    check (outcome in ('pending','reaching_out','connected','no_reply','not_useful'));
exception when duplicate_object then null; end $$;

drop policy if exists "asker records the outcome" on public.bridges;
create policy "asker records the outcome"
  on public.bridges for update using (auth.uid() = asker) with check (auth.uid() = asker);

create or replace function public.intro_outcomes()
returns table(outcome text, n bigint, avg_strength numeric)
language sql security definer stable set search_path = public as $$
  select coalesce(outcome,'pending'), count(*), round(avg(strength)::numeric, 2)
    from bridges where asker = auth.uid() group by 1 order by 2 desc;
$$;

grant execute on function public.intro_outcomes() to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Column privileges — because row-level security is not column-level security
--
-- "profiles are public to members" grants SELECT on the ROW, and `profiles` is the account table,
-- so it carries an `email` column. The result: any signed-in user could read every other user's
-- email address, and their contact details, with one request. That is not a small leak — it is the
-- precise opposite of the claim this whole design rests on, which is that what the network sees
-- about you is three sentences you wrote on purpose.
--
-- RLS cannot express "this row, but not that column". GRANT can. SELECT is revoked on the private
-- columns while INSERT and UPDATE stay, so you can still write your own; `reveal_contact` runs as
-- the owner and is therefore still able to hand your details to somebody you agreed to introduce.
-- That function remains the only way they ever cross.
-- ─────────────────────────────────────────────────────────────────────────────
-- THE TABLE-LEVEL GRANT MUST GO FIRST.
--
-- Postgres column privileges are additive to table privileges, not a mask over them. Supabase
-- grants SELECT on the whole table to `authenticated` by default, so `revoke select (email)`
-- removes a column privilege that was never what was authorising the read — and the email column
-- stays perfectly readable. Verified: it did exactly nothing the first time.
--
-- Revoke the table-wide privilege, then grant back the columns that are genuinely public.
revoke select on public.profiles from authenticated, anon;

-- Everything genuinely public stays readable.
grant select (id, handle, display_name, photo_url, offer, looking_for, open_to, tags,
              reachability, network_size, vouch_weight, vouches_made, vouches_kept,
              asks_left, tier, share_on_intro, updated_at, created_at)
  on public.profiles to authenticated;

grant insert, update on public.profiles to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- A name, from the moment somebody signs up
--
-- `display_name` was written only by publish(), so anybody who had not yet opened Where you stand
-- appeared on everyone else's map as "Someone" — which, in a beta, is nearly everybody. A trigger
-- fills it from the address they signed up with so the field is never a crowd of ghosts, and the
-- moment they write a real name it wins.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.name_from_email(addr text) returns text
language sql immutable as $$
  select case
    -- A role address is not a person. "info@solruo.com" becoming "Info" is worse than useless;
    -- the company name is at least true and recognisable.
    when split_part(addr, '@', 1) in
         ('info','hello','contact','admin','team','support','sales','hi','mail','office','noreply','no-reply')
      then initcap(split_part(split_part(addr, '@', 2), '.', 1))
    else initcap(replace(replace(split_part(addr, '@', 1), '.', ' '), '_', ' '))
  end;
$$;

create or replace function public.default_display_name() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if coalesce(new.display_name, '') = '' and coalesce(new.email, '') <> '' then
    new.display_name := public.name_from_email(new.email);
  end if;
  return new;
end $$;

drop trigger if exists trg_default_display_name on public.profiles;
create trigger trg_default_display_name before insert or update on public.profiles
  for each row execute function public.default_display_name();

-- Backfill anybody already in that state.
update public.profiles
   set display_name = public.name_from_email(email)
 where coalesce(display_name, '') = '' and coalesce(email, '') <> '';

-- ─────────────────────────────────────────────────────────────────────────────
-- An ask is a TASK, with a scope and three ways to end
--
-- "Working on it" with no boundary is the sentence every abandoned assistant feature was built on.
-- An ask now declares up front how far it will go and what will stop it, and all three conditions
-- are visible while it runs:
--
--   1. it has found enough — `target_intros` introductions, and it closes itself
--   2. it has asked enough people — `target_reach` distinct users, then it stops widening and
--      waits out the clock for their answers
--   3. the clock — 72 hours, absolute, enforced by the server
--
-- Reaching people and getting answers are different things and the second one is slow, which is
-- exactly why the screen has to distinguish them rather than showing one spinner for both.
-- ─────────────────────────────────────────────────────────────────────────────
alter table public.asks add column if not exists target_reach  int default 50;
alter table public.asks add column if not exists target_intros int default 3;

-- Enough is enough. The moment an ask has what it went looking for it closes itself, which is the
-- difference between a task that finishes and one that merely expires.
create or replace function public.close_when_satisfied() returns trigger
language plpgsql security definer set search_path = public as $$
declare n int; want int;
begin
  select count(distinct lower(btrim(person))) into n from bridges where ask_id = new.ask_id;
  select coalesce(target_intros, 3) into want from asks where id = new.ask_id;
  if n >= want then
    update asks set state = 'matched' where id = new.ask_id and state = 'open';
  end if;
  return new;
end $$;

drop trigger if exists trg_close_when_satisfied on public.bridges;
create trigger trg_close_when_satisfied after insert on public.bridges
  for each row execute function public.close_when_satisfied();

-- ─────────────────────────────────────────────────────────────────────────────
-- Why anybody comes back: you earn asks by answering them
--
-- Not points, not streaks, not a badge. The one thing this network can genuinely run out of is
-- people willing to answer — an ask that reaches two hundred phones and gets nothing back is the
-- failure mode — so the scarce resource is bought with the scarce contribution. Make an
-- introduction, get an ask. It is the only reward that makes the thing work better rather than
-- merely making somebody open the app.
--
-- The cap matters as much as the reward: earning is bounded, so nobody farms introductions.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.reward_introduction() returns trigger
language plpgsql security definer set search_path = public as $$
declare cap int;
begin
  select case tier when 'business' then 100 when 'plus' then 20 else 8 end
    into cap from profiles where id = new.holder;

  update profiles
     set vouches_made = coalesce(vouches_made, 0) + 1,
         asks_left    = least(coalesce(asks_left, 0) + 1, coalesce(cap, 8))
   where id = new.holder;
  return new;
end $$;

drop trigger if exists trg_reward_introduction on public.bridges;
create trigger trg_reward_introduction after insert on public.bridges
  for each row execute function public.reward_introduction();

-- An introduction that actually led somewhere is worth more than one that was merely offered, and
-- only the asker can say which it was. This is the number that should decide whose answer gets
-- read first when the network is large.
create or replace function public.reward_kept() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.outcome = 'connected' and coalesce(old.outcome,'') <> 'connected' then
    update profiles
       set vouches_kept = coalesce(vouches_kept, 0) + 1,
           vouch_weight = least(5.0, 1.0 + 0.25 * (coalesce(vouches_kept, 0) + 1))
     where id = new.holder;
  end if;
  return new;
end $$;

drop trigger if exists trg_reward_kept on public.bridges;
create trigger trg_reward_kept after update on public.bridges
  for each row execute function public.reward_kept();

/** Your standing: what you have given the network, and what it gave back. */
create or replace function public.my_standing()
returns table(asks_left int, intros_made int, intros_kept int, weight real, rank_pct numeric)
language sql security definer stable set search_path = public as $$
  select p.asks_left, coalesce(p.vouches_made,0), coalesce(p.vouches_kept,0), p.vouch_weight,
         round(100.0 * (select count(*) from profiles q
                         where coalesce(q.vouches_made,0) <= coalesce(p.vouches_made,0))
             / nullif((select count(*) from profiles), 0), 0)
    from profiles p where p.id = auth.uid();
$$;

grant execute on function public.my_standing() to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- How many people COULD this reach — the honest denominator
--
-- A bar reading "asked 1 of 50" when the network contains one other person does not describe an
-- early network, it describes a broken one. Fifty was a hardcoded ambition; what a progress bar
-- has to show is progress against something real.
--
-- So the ask carries a target the owner chose, and the bar is drawn against
-- `min(target, eligible)` — the smaller of what they asked for and what exists. Early on that
-- reads "asked 1 of 1", which is a true statement about a young network rather than a false one
-- about a failing feature. As people join, the same ask silently starts meaning more.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.eligible_reach(p_tags text[] default '{}')
returns int language sql security definer stable set search_path = public as $$
  select count(*)::int
    from profiles p
   where p.id <> auth.uid()
     and coalesce(p.reachability, 'vouched') <> 'closed'
     and (p_tags is null or cardinality(p_tags) = 0 or p.tags && p_tags
          or p.tags is null or cardinality(p.tags) = 0);
$$;

grant execute on function public.eligible_reach(text[]) to authenticated;

-- fan_out now honours the target the owner set, instead of its own default.
create or replace function public.fan_out(p_ask uuid, p_limit int default null)
returns int language plpgsql security definer set search_path = public as $$
declare n int; m int; t text[]; want int; floor_n int := 25;
begin
  if not exists (select 1 from asks a where a.id = p_ask and a.from_user = auth.uid()) then
    raise exception 'not your ask';
  end if;
  select tags, coalesce(target_reach, 50) into t, want from asks where id = p_ask;
  want := coalesce(p_limit, want);

  insert into ask_candidates (ask_id, candidate_user)
  select p_ask, p.id from profiles p
   where p.id <> auth.uid()
     and coalesce(p.reachability, 'vouched') <> 'closed'
     and (t is null or cardinality(t) = 0 or p.tags && t)
   order by p.vouch_weight desc nulls last, p.network_size desc nulls last
   limit want
  on conflict do nothing;
  get diagnostics n = row_count;

  -- Untagged profiles are evidence of nothing, so a thin match tops up from them rather than
  -- letting an ask reach one person in four because three never filled the form in.
  if n < least(floor_n, want) then
    insert into ask_candidates (ask_id, candidate_user)
    select p_ask, p.id from profiles p
     where p.id <> auth.uid()
       and coalesce(p.reachability, 'vouched') <> 'closed'
       and (p.tags is null or cardinality(p.tags) = 0)
     order by p.network_size desc nulls last
     limit greatest(0, least(floor_n, want) - n)
    on conflict do nothing;
    get diagnostics m = row_count;
    n := n + coalesce(m, 0);
  end if;

  return n;
end $$;

grant execute on function public.fan_out(uuid, int) to authenticated;
