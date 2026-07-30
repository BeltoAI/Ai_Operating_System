-- TEST DATA — three fake people so the network has somebody in it before it has anybody in it.
--
-- A social product with one user is untestable: there is no second galaxy, nothing to fan an ask
-- out to, and no way to see a bridge appear. These three exist to be asked, and to answer.
--
-- Everything here is namespaced on `@test.slyos`, and `drop_test_network()` at the bottom removes
-- all of it in one statement. Nothing in this file should ever run against a database with real
-- users in it that you are not willing to have three fake ones beside.
--
-- Run AFTER network.sql.

-- ─────────────────────────────────────────────────────────────────────────────
-- The users.
--
-- Real rows in auth.users, with a real password, so you can genuinely sign in as one on a second
-- device and watch both sides of an introduction. Password for all three: `testpass123`.
-- ─────────────────────────────────────────────────────────────────────────────
create extension if not exists pgcrypto;

do $$
declare
  u record;
begin
  for u in
    select * from (values
      ('ada@test.slyos',   'Ada Bishop',
       'Warm intros to seed-stage fintech investors, and a decade of payments infrastructure.',
       'Two backend engineers who have shipped payment rails.',
       'Anything about payments, hiring, or fundraising. Not agency pitches.',
       array['payments','fintech','founder','engineering','london'], 4200),
      ('rune@test.slyos',  'Rune Halvorsen',
       'I run a 900-person operator community and can put a product in front of it.',
       'Early testers for a developer tool, and a design partner in logistics.',
       'Product feedback, beta tests, community. Not recruiters.',
       array['community','devtools','logistics','operator','oslo'], 11800),
      ('mira@test.slyos',  'Mira Okonkwo',
       'Introductions across African health systems and regulatory contacts in three markets.',
       'A clinical advisor, and distribution partners in West Africa.',
       'Health, regulation, distribution. Open to cold asks.',
       array['health','regulatory','africa','distribution','founder'], 2650)
    ) as t(email, name, offer, looking_for, open_to, tags, network_size)
  loop
    insert into auth.users (
      instance_id, id, aud, role, email, encrypted_password,
      email_confirmed_at, created_at, updated_at,
      raw_app_meta_data, raw_user_meta_data
    ) values (
      '00000000-0000-0000-0000-000000000000', gen_random_uuid(),
      'authenticated', 'authenticated', u.email, crypt('testpass123', gen_salt('bf')),
      now(), now(), now(),
      '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb
    )
    on conflict (email) do nothing;

    insert into public.profiles (id, email, display_name, offer, looking_for, open_to,
                                 tags, reachability, network_size, asks_left)
    select au.id, u.email, u.name, u.offer, u.looking_for, u.open_to,
           u.tags, 'open', u.network_size, 3
      from auth.users au where au.email = u.email
    on conflict (id) do update set
      display_name = excluded.display_name,
      offer        = excluded.offer,
      looking_for  = excluded.looking_for,
      open_to      = excluded.open_to,
      tags         = excluded.tags,
      reachability = excluded.reachability,
      network_size = excluded.network_size;
  end loop;
end $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- simulate_answer — a test user replying, without a second phone.
--
-- Scaffolding, and deliberately shaped so it cannot be turned on real people: it refuses any
-- candidate whose email is not `@test.slyos`. It writes exactly what a real phone would write —
-- the candidacy state and a bridge row — so the path being tested is the real one, not a mock of it.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.simulate_answer(
  p_ask uuid, p_email text, p_person text, p_strength real default 0.7, p_note text default ''
) returns text language plpgsql security definer set search_path = public as $$
declare v_uid uuid; v_asker uuid;
begin
  select id into v_uid from auth.users where email = p_email;
  if v_uid is null then return 'no such test user'; end if;
  if p_email not like '%@test.slyos' then
    raise exception 'simulate_answer only works on @test.slyos accounts';
  end if;

  select from_user into v_asker from asks where id = p_ask;
  if v_asker is null then return 'no such ask'; end if;

  insert into ask_candidates (ask_id, candidate_user, state, verdict)
       values (p_ask, v_uid, 'accepted', 'qualified')
  on conflict (ask_id, candidate_user)
  do update set state = 'accepted', verdict = 'qualified', updated_at = now();

  insert into bridges (ask_id, asker, holder, person, note, strength)
       values (p_ask, v_asker, v_uid, p_person, p_note, p_strength)
  on conflict (ask_id, holder) do update set person = excluded.person, strength = excluded.strength;

  return 'ok';
end $$;

grant execute on function public.simulate_answer(uuid, text, text, real, text) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Teardown. One statement, and the database is exactly as it was.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.drop_test_network() returns void
language sql security definer set search_path = public as $$
  delete from auth.users where email like '%@test.slyos';
$$;
