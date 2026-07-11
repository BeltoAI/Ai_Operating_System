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
