-- SlyOS analytics v2 — run once in Supabase SQL editor.
-- Adds per-account + "what for" columns to the existing usage_events table, plus ready-made views
-- so you can answer "how many users, what do they use SlyOS for, what wins, what fails."

-- 1) New columns (safe to re-run)
alter table usage_events add column if not exists account   text;
alter table usage_events add column if not exists category   text;
alter table usage_events add column if not exists created_at timestamptz not null default now();

-- 2) Indexes for fast rollups
create index if not exists idx_ue_category on usage_events (category);
create index if not exists idx_ue_event    on usage_events (event);
create index if not exists idx_ue_account  on usage_events (account);
create index if not exists idx_ue_created  on usage_events (created_at);

-- 3) WHAT FOR — the headline: what people actually use SlyOS for
create or replace view slyos_what_for as
select coalesce(nullif(category,''),'other') as what_for,
       count(*)                       as events,
       count(distinct device)         as devices,
       count(distinct nullif(account,'')) as accounts
from usage_events
group by 1
order by events desc;

-- 4) FEATURE USE — which features get used
create or replace view slyos_feature_use as
select event, count(*) as uses, count(distinct device) as devices
from usage_events
group by 1
order by uses desc;

-- 5) WINS vs LOSSES — is it working for people?
create or replace view slyos_wins_losses as
select case
         when event in ('action_failed','action_gated','llm_error') then 'loss'
         when event in ('action','site_published','agent_hired')     then 'win'
         else 'other'
       end as outcome,
       count(*) as events
from usage_events
group by 1;

-- 6) USERS — reach over time
create or replace view slyos_users as
select date_trunc('day', created_at) as day,
       count(distinct device)  as active_devices,
       count(distinct nullif(account,'')) as active_accounts,
       count(*)                as events
from usage_events
group by 1
order by day desc;

-- Quick reads once data flows in:
--   select * from slyos_what_for;
--   select * from slyos_feature_use;
--   select * from slyos_wins_losses;
--   select * from slyos_users limit 30;
