# SlyOS Agent Store — schema & publishing contract

The Agent Store is a global catalogue of **SlyOS agents**. An agent is a **single self-contained HTML/JS
file** that runs in SlyOS's sandboxed WebView with the `SlyOS` bridge (the same runtime the Architect builds
into). No native code, no build tools, no signing — if you can write an HTML page, you can ship an agent.

Backed by **Supabase (free tier is plenty)** — agents are small text blobs; the free 500 MB Postgres holds
thousands. Reuses the same project as accounts/sync (`ACCOUNT_AND_SYNC.md`).

## 1. Table (run this SQL in Supabase)

```sql
create table public.agents (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid references auth.users(id) on delete set null,   -- publisher
  name        text not null,
  author      text,
  description text,
  category    text default 'Other',
  icon        text default '🤖',        -- an emoji or short glyph
  code        text not null,            -- the full self-contained HTML of the agent
  version     int  default 1,
  installs    int  default 0,
  approved    boolean default false,    -- flips true after review; only approved agents are listed
  created_at  timestamptz default now()
);
create index agents_browse_idx on public.agents (approved, installs desc, created_at desc);

alter table public.agents enable row level security;
-- Anyone (even signed-out) can READ approved agents:
create policy "read approved agents" on public.agents for select using (approved = true);
-- Signed-in users can PUBLISH (insert) their own rows:
create policy "publish own agents" on public.agents for insert with check (auth.uid() = user_id);
-- Publishers can edit/delete their own submissions:
create policy "edit own agents" on public.agents for update using (auth.uid() = user_id);
create policy "delete own agents" on public.agents for delete using (auth.uid() = user_id);

-- Atomic install counter (RPC, callable by anyone):
create or replace function public.bump_installs(agent_id uuid) returns void as $$
  update public.agents set installs = installs + 1 where id = agent_id and approved = true;
$$ language sql security definer;
grant execute on function public.bump_installs(uuid) to anon, authenticated;
```

## 1b. Ratings, reviews & versioned releases (run this too)

Turns the catalogue into a real marketplace: star ratings with a trigger-maintained average, one review
per user per agent, and a full release history with an atomic "publish a new version" function.

```sql
-- Aggregate columns kept in sync by a trigger (never write these directly):
alter table public.agents add column if not exists rating numeric(2,1) default 0;
alter table public.agents add column if not exists ratings_count int default 0;

-- One review per user per agent:
create table public.reviews (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid references public.agents(id) on delete cascade,
  user_id  uuid references auth.users(id) on delete cascade,
  author   text,
  stars    int not null check (stars between 1 and 5),
  body     text,
  created_at timestamptz default now(),
  unique (agent_id, user_id)
);
create index reviews_agent_idx on public.reviews (agent_id, created_at desc);
alter table public.reviews enable row level security;
create policy "read reviews"    on public.reviews for select using (true);
create policy "write own review" on public.reviews for insert with check (auth.uid() = user_id);
create policy "update own review" on public.reviews for update using (auth.uid() = user_id);
create policy "delete own review" on public.reviews for delete using (auth.uid() = user_id);

-- Trigger: recompute agents.rating + ratings_count on any review change:
create or replace function public.recalc_rating() returns trigger as $$
declare aid uuid;
begin
  aid := coalesce(new.agent_id, old.agent_id);
  update public.agents a set
    ratings_count = (select count(*) from public.reviews r where r.agent_id = aid),
    rating = coalesce((select round(avg(stars)::numeric, 1) from public.reviews r where r.agent_id = aid), 0)
  where a.id = aid;
  return null;
end; $$ language plpgsql security definer;
create trigger reviews_recalc after insert or update or delete on public.reviews
  for each row execute function public.recalc_rating();

-- Full release history:
create table public.agent_versions (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid references public.agents(id) on delete cascade,
  version  int not null,
  notes    text,
  created_at timestamptz default now()
);
create index versions_agent_idx on public.agent_versions (agent_id, version desc);
alter table public.agent_versions enable row level security;
create policy "read versions" on public.agent_versions for select using (true);
create policy "owner insert version" on public.agent_versions for insert
  with check (auth.uid() = (select user_id from public.agents where id = agent_id));

-- Atomic release: bump code + version and log the changelog. Owner only.
create or replace function public.publish_release(p_agent uuid, p_code text, p_notes text)
returns int as $$
declare v int;
begin
  if auth.uid() <> (select user_id from public.agents where id = p_agent) then
    raise exception 'not the owner';
  end if;
  update public.agents set code = p_code, version = version + 1 where id = p_agent returning version into v;
  insert into public.agent_versions(agent_id, version, notes) values (p_agent, v, p_notes);
  return v;
end; $$ language plpgsql security definer;
grant execute on function public.publish_release(uuid, text, text) to authenticated;
```

## 2. REST contract (any client/dev)

Base: `${SUPABASE_URL}/rest/v1`. Header on every call: `apikey: ${SUPABASE_ANON_KEY}`.

- **Browse** (public): `GET /agents?approved=eq.true&select=id,name,author,description,category,icon,installs&order=installs.desc,created_at.desc`
  - filter category: `&category=eq.Games`
  - search: `&or=(name.ilike.*chess*,description.ilike.*chess*)`
- **Get an agent's code** (to install/run): `GET /agents?id=eq.<uuid>&select=code`
- **Publish** (needs a user JWT — sign in via GoTrue, see ACCOUNT_AND_SYNC.md):
  `POST /agents` with `Authorization: Bearer <access_token>` and body
  `{ "user_id":"<uid>", "name":"…", "author":"…", "description":"…", "category":"Games", "icon":"♟", "code":"<html>", "version":1, "approved":false }`
- **Count an install**: `POST /rpc/bump_installs` body `{ "agent_id":"<uuid>" }`

## 3. What an agent (the HTML) can do

Agents run in SlyOS's sandboxed WebView with a `SlyOS` bridge injected. Typical capabilities (see `AppBridge`):
persist data (`SlyOS.save/load`), call the model, and render a full UI. Keep everything in ONE HTML file —
inline CSS + JS. No external network beyond what the bridge exposes. Example skeleton:

```html
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui;background:#0f0d0c;color:#eee;margin:0;padding:16px}</style></head>
<body>
  <h2>My Agent</h2>
  <button onclick="SlyOS.save('count', (Number(SlyOS.load('count')||0)+1)+'')">tap</button>
  <script>/* your agent logic */</script>
</body></html>
```

## 4. Moderation
New submissions default to `approved = false` and aren't listed until reviewed (a maintainer flips `approved`
to true). Because agents are sandboxed HTML, the blast radius is small, but review keeps the catalogue clean
and prevents abuse of the bridge. A future "community / unverified" shelf can surface unreviewed agents behind
a clear warning.

## 5. Why Supabase free tier is enough (and when to move)
Agents are a few KB–MB of text; thousands fit in the free 500 MB DB, storage isn't needed, auth is already
wired, and reads are just RLS-protected selects. You'd only outgrow it at large scale (millions of installs /
heavy media) — at which point moving `code` to Supabase Storage or a CDN is a one-column change, no rewrite.
