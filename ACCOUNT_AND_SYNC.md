# SlyOS Accounts, Sync & Encryption — Cross-Client Contract

This document is the **single source of truth** for the SlyOS account/database layer. Any client
(Android, future iOS/desktop/web, or another AI coding session) can build against this exact structure to
create accounts, authenticate, and sync a user's brain across devices.

Backend: **Supabase** (hosted Postgres + Auth + Row-Level Security + REST/PostgREST).

---

## 0. Decisions (locked)

| Area | Decision |
|------|----------|
| Auth | Email + password (Supabase Auth / GoTrue) |
| Transport | HTTPS to Supabase REST + Auth endpoints |
| Normal brain data | Stored server-side, protected by **Row-Level Security** + Supabase at-rest encryption. Recoverable. |
| Sensitive data (bank vault) | **End-to-end encrypted** client-side (AES-256-GCM). Server only ever stores ciphertext. |
| Vault key root | Derived from the **account password** (Argon2id/PBKDF2) → portable across devices. |
| Vault unlock (per device) | **Biometric / device PIN**. After the first password unlock on a device, the vault key is cached in the platform keystore, released by biometric. |
| Conflict strategy | Per-row **last-write-wins** using `updated_at` (UTC ms). |

> **Why the vault key is rooted in the password, not biometric:** biometric keys live in a device's secure
> hardware and never leave it. If the vault were encrypted with a biometric-only key, a second device could
> never decrypt it. So the portable root is `KDF(password, salt)`; biometric is only a local convenience gate.

---

## 1. Supabase project setup

1. Create a project at supabase.com. Note the **Project URL** (`https://<ref>.supabase.co`) and the
   **anon public key** (safe to ship in clients) and the **service_role key** (server-only, NEVER ship).
2. Run the SQL in section 2 in the Supabase SQL editor.
3. In Auth settings, enable **Email** provider. For a frictionless MVP you may turn **"Confirm email"** off;
   for production keep it on.
4. Clients configure two values: `SUPABASE_URL` and `SUPABASE_ANON_KEY`.

---

## 2. Database schema (run this SQL)

```sql
-- ── profiles: one row per user, keyed to auth.users ──────────────────────────
create table public.profiles (
  id           uuid primary key references auth.users(id) on delete cascade,
  email        text,
  display_name text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

-- ── brain_items: the synced brain (memories, chats, papers, settings, docs index) ──
-- One generic, append/merge-friendly table. `kind` namespaces the record type; `client_id`
-- is a stable id the client generates so the same logical row updates in place (last-write-wins).
create table public.brain_items (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  kind        text not null,              -- 'memory' | 'chat' | 'paper' | 'setting' | 'doc' | ...
  client_id   text not null,              -- stable per-logical-row id from the client
  title       text,
  body        text,                       -- plaintext for normal data
  data        jsonb,                       -- structured payload (optional)
  updated_at  bigint not null,            -- UTC epoch MILLIS; drives last-write-wins
  deleted     boolean not null default false,
  created_at  timestamptz not null default now(),
  unique (user_id, kind, client_id)
);
create index brain_items_user_kind_idx on public.brain_items (user_id, kind, updated_at desc);

-- ── vault_items: END-TO-END ENCRYPTED sensitive data (e.g. bank info) ──────────
-- The server sees ONLY ciphertext. `ciphertext` = base64(AES-256-GCM output); `iv` = base64 nonce.
-- `wrapped_key` stores the vault Data-Encryption-Key wrapped by KDF(password) so any device can recover it.
create table public.vault_items (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  client_id   text not null,             -- e.g. 'bank:primary'
  label       text,                       -- non-secret label ('Chase checking') — plaintext, for listing
  ciphertext  text not null,             -- base64 AES-256-GCM ciphertext of the secret JSON
  iv          text not null,             -- base64 12-byte GCM nonce
  updated_at  bigint not null,
  deleted     boolean not null default false,
  created_at  timestamptz not null default now(),
  unique (user_id, client_id)
);
create index vault_items_user_idx on public.vault_items (user_id, updated_at desc);

-- ── vault_meta: the wrapped vault key + KDF params (one row per user) ──────────
create table public.vault_meta (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  kdf         text not null default 'PBKDF2WithHmacSHA256',
  kdf_salt    text not null,             -- base64 random salt
  kdf_iters   int  not null default 210000,
  wrapped_dek text not null,             -- base64: the vault DEK, AES-GCM-wrapped by KDF(password, salt)
  wrap_iv     text not null,             -- base64 nonce for the wrap
  updated_at  bigint not null
);

-- ── Row-Level Security: a user can only see/modify their own rows ─────────────
alter table public.profiles     enable row level security;
alter table public.brain_items  enable row level security;
alter table public.vault_items  enable row level security;
alter table public.vault_meta   enable row level security;

create policy "own profile"      on public.profiles    for all using (auth.uid() = id)      with check (auth.uid() = id);
create policy "own brain"        on public.brain_items for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own vault"        on public.vault_items for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own vault_meta"   on public.vault_meta  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ── Auto-create a profile row on signup ───────────────────────────────────────
create or replace function public.handle_new_user() returns trigger as $$
begin
  insert into public.profiles (id, email) values (new.id, new.email)
  on conflict (id) do nothing;
  return new;
end; $$ language plpgsql security definer;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute procedure public.handle_new_user();
```

---

## 3. Auth API (GoTrue REST) — used by every client

Base: `${SUPABASE_URL}/auth/v1`. Header on every call: `apikey: ${SUPABASE_ANON_KEY}`.

| Action | Request |
|--------|---------|
| Sign up | `POST /signup` body `{"email","password"}` → returns `access_token`, `refresh_token`, `user` |
| Sign in | `POST /token?grant_type=password` body `{"email","password"}` → same |
| Refresh | `POST /token?grant_type=refresh_token` body `{"refresh_token"}` |
| Sign out | `POST /logout` header `Authorization: Bearer <access_token>` |
| Current user | `GET /user` header `Authorization: Bearer <access_token>` |

Store `access_token` (short-lived), `refresh_token`, `user.id`, `email` on the client. Refresh when a REST
call returns 401.

---

## 4. Data API (PostgREST) — read/write rows

Base: `${SUPABASE_URL}/rest/v1`. Headers: `apikey: <anon>`, `Authorization: Bearer <access_token>`.

- **Pull changed brain rows since a cursor:**
  `GET /brain_items?user_id=eq.<uid>&updated_at=gt.<cursorMs>&order=updated_at.asc`
- **Push (upsert) rows** (last-write-wins on the unique key):
  `POST /brain_items` with header `Prefer: resolution=merge-duplicates` and a JSON array of rows.
- **Soft delete:** upsert the same row with `deleted=true` and a newer `updated_at`.
- Vault rows identical, against `/vault_items` and `/vault_meta`.

### Sync loop (client)
1. On login: `GET` all `brain_items` (and `vault_*`) for the user → merge into local store by
   `(kind, client_id)`, keeping the row with the greater `updated_at`.
2. On local change: mark row dirty; push dirty rows via upsert; advance the local `lastSyncMs` cursor.
3. Periodically / on app foreground: pull `updated_at > lastSyncMs`, merge, push dirty.

---

## 5. Encryption details

### Normal brain (`brain_items`)
Plaintext in Postgres, protected by RLS (only the owner's JWT can read) + Supabase at-rest encryption.
Transport is TLS. Good recoverability + full feature support (search, etc.).

### Vault (`vault_items` + `vault_meta`) — end-to-end
1. On first vault use, the client generates a random 256-bit **DEK** (data-encryption key).
2. Each secret (bank record) is `AES-256-GCM(DEK, iv, plaintextJSON)` → stored as `ciphertext`+`iv`.
   The non-secret `label` stays plaintext for listing.
3. The DEK itself is wrapped: `wrapped_dek = AES-GCM( KDF(password, kdf_salt, kdf_iters), wrap_iv, DEK )`
   and stored in `vault_meta`. So **any device** that knows the password can recover the DEK, decrypt the
   vault, and re-encrypt. The server never sees the DEK or the password.
4. **Per-device convenience:** after the first successful password unlock on a device, the client caches the
   DEK inside the platform secure keystore (Android Keystore / iOS Keychain / WebCrypto+IndexedDB), gated by
   biometric. Subsequent views require only a fingerprint; the password is needed again only on a new device
   or after the cache is cleared.
5. Changing the password re-wraps the DEK (decrypt with old KDF key, encrypt with new) and updates
   `vault_meta`. Losing the password = vault is unrecoverable by design (that is the privacy guarantee).

---

## 6. What the SlyOS Android client stores locally

- Session: `access_token`, `refresh_token`, `user_id`, `email` in app prefs.
- `SUPABASE_URL` / `SUPABASE_ANON_KEY`: from `apikey.properties` → BuildConfig (never committed).
- Vault DEK: Android Keystore, `setUserAuthenticationRequired(true)` (biometric/PIN release).
- The bank vault also mirrors a **non-secret pointer** into the local brain ("Bank info saved — locked")
  so the brain knows it exists, but the values only decrypt after biometric unlock.

---

## 7. Minimal client checklist to connect (any language)

1. Configure `SUPABASE_URL`, `SUPABASE_ANON_KEY`.
2. Sign up / sign in via section 3 → hold tokens.
3. Pull + merge `brain_items` (section 4).
4. For vault: fetch `vault_meta`; derive KDF key from password; unwrap DEK; decrypt `vault_items`.
5. Push local changes as upserts with a fresh `updated_at` (UTC ms).

That is the entire contract. Same tables, same keys, same last-write-wins rule everywhere.
