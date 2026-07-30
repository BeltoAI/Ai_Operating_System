-- TEST DATA — six fake people so the network has somebody in it before it has anybody in it.
--
-- A social product with one user is untestable: no second galaxy, nothing to fan an ask out to, no
-- way to watch a bridge appear. These six exist to be asked, and to answer.
--
-- They are deliberately not identical. Different sizes of orbit, different routing words, and all
-- three reachability settings — because the interesting cases are the ones where two of them know
-- the SAME person and where one of them is not reachable at all.
--
-- Everything is namespaced on `@test.slyos`, and `drop_test_network()` removes all of it in one
-- statement. Run AFTER network.sql. Safe to re-run.

create extension if not exists pgcrypto;

-- ─────────────────────────────────────────────────────────────────────────────
-- The users. Real rows in auth.users with a real password (`testpass123`), so you can genuinely
-- sign in as one on a second device and watch both ends of an introduction.
-- ─────────────────────────────────────────────────────────────────────────────
do $$
declare u record;
begin
  for u in
    select * from (values
      ('ada@test.slyos',   'Ada Bishop',
       'Warm intros to seed-stage fintech investors, and a decade of payments infrastructure.',
       'Two backend engineers who have shipped payment rails.',
       'Payments, hiring, fundraising. Not agency pitches.',
       array['payments','fintech','founder','engineering','investor'], 4200, 'open'),

      ('rune@test.slyos',  'Rune Halvorsen',
       'I run a 900-person operator community and can put a product in front of it.',
       'Early testers for a developer tool, and a design partner in logistics.',
       'Product feedback, beta tests, community. Not recruiters.',
       array['community','devtools','logistics','operator','testers'], 11800, 'open'),

      ('mira@test.slyos',  'Mira Okonkwo',
       'Introductions across African health systems and regulatory contacts in three markets.',
       'A clinical advisor, and distribution partners in West Africa.',
       'Health, regulation, distribution. Open to cold asks.',
       array['health','regulatory','distribution','founder'], 2650, 'open'),

      -- Reachable only through somebody she already shares a bridge with. Her agent still answers
      -- asks — being asked is anonymous and costs nothing — but nobody gets introduced TO her cold.
      ('sena@test.slyos',  'Sena Yilmaz',
       'I invest at seed in payments and fraud, and I answer every warm intro.',
       'Founders working on card-present fraud.',
       'Warm intros only.',
       array['payments','investor','fraud','fintech'], 980, 'vouched'),

      -- Off. Should never appear in a fan-out at all — the control case.
      ('kai@test.slyos',   'Kai Lindqvist',
       'Nothing right now.',
       'Nothing right now.',
       'Not taking requests.',
       array['payments','design'], 320, 'closed'),

      ('omar@test.slyos',  'Omar Haddad',
       'Fifteen years in card processing; I know most of the fraud teams in Europe.',
       'A compliance lead, and someone who has run PSD2 migrations.',
       'Payments, fraud, compliance.',
       array['payments','fraud','compliance','engineering'], 7400, 'open')
    ) as t(email, name, offer, looking_for, open_to, tags, network_size, reach)
  loop
    -- `where not exists`, not `on conflict (email)`. GoTrue's uniqueness on auth.users is scoped, so
    -- ON CONFLICT has nothing to match and Postgres refuses with 42P10.
    insert into auth.users (
      instance_id, id, aud, role, email, encrypted_password,
      email_confirmed_at, created_at, updated_at, raw_app_meta_data, raw_user_meta_data
    )
    select '00000000-0000-0000-0000-000000000000', gen_random_uuid(),
           'authenticated', 'authenticated', u.email, crypt('testpass123', gen_salt('bf')),
           now(), now(), now(),
           '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb
    where not exists (select 1 from auth.users a where a.email = u.email);

    insert into public.profiles (id, email, display_name, offer, looking_for, open_to,
                                 tags, reachability, network_size, asks_left)
    select au.id, u.email, u.name, u.offer, u.looking_for, u.open_to,
           u.tags, u.reach, u.network_size, 3
      from auth.users au where au.email = u.email
    on conflict (id) do update set
      display_name = excluded.display_name, offer = excluded.offer,
      looking_for  = excluded.looking_for,  open_to = excluded.open_to,
      tags = excluded.tags, reachability = excluded.reachability,
      network_size = excluded.network_size;
  end loop;
end $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- simulate_answer — one test user replying, without a second phone.
--
-- Scaffolding, shaped so it cannot be turned on real people: it refuses any candidate whose email
-- is not `@test.slyos`. It writes exactly what a real phone writes — the candidacy state, the
-- strength, and the bridge — so the path under test is the real one, not a mock of it.
--
-- `p_person` NULL means "I know nobody" — the common case, and the one worth being able to
-- reproduce, because it is what four out of five candidates do.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.simulate_answer(
  p_ask uuid, p_email text, p_person text default null,
  p_strength real default 0.7, p_note text default ''
) returns text language plpgsql security definer set search_path = public as $$
declare v_uid uuid; v_asker uuid;
begin
  if p_email not like '%@test.slyos' then
    raise exception 'simulate_answer only works on @test.slyos accounts';
  end if;
  select id into v_uid from auth.users where email = p_email;
  if v_uid is null then return 'no such test user'; end if;
  select from_user into v_asker from asks where id = p_ask;
  if v_asker is null then return 'no such ask'; end if;

  if p_person is null then
    update ask_candidates set state = 'ignored', verdict = 'not_qualified', updated_at = now()
     where ask_id = p_ask and candidate_user = v_uid;
    return 'ignored';
  end if;

  insert into ask_candidates (ask_id, candidate_user, state, verdict, strength)
       values (p_ask, v_uid, 'accepted', 'qualified', p_strength)
  on conflict (ask_id, candidate_user)
  do update set state = 'accepted', verdict = 'qualified',
                strength = excluded.strength, updated_at = now();

  insert into bridges (ask_id, asker, holder, person, note, strength)
       values (p_ask, v_asker, v_uid, p_person, p_note, p_strength)
  on conflict (ask_id, holder)
  do update set person = excluded.person, note = excluded.note, strength = excluded.strength;

  return 'introduced';
end $$;

grant execute on function public.simulate_answer(uuid, text, text, real, text) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- simulate_round — a whole realistic answer cycle for the newest ask.
--
-- The shape matters more than the numbers. Most candidates find nothing and say nothing. Two
-- separately know the SAME person — which is the case the map has to get right, because there is
-- one person and several ways to reach them, not several people. One knows somebody else. One
-- says no outright.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.simulate_round(p_ask uuid default null) returns table(who text, outcome text)
language plpgsql security definer set search_path = public as $$
declare v_ask uuid;
begin
  v_ask := coalesce(p_ask, (select id from asks order by created_at desc limit 1));
  if v_ask is null then raise exception 'no asks yet — send one from the phone first'; end if;

  -- Two people, the same person, different closeness. Omar worked with her; Ada met her once.
  return query select 'Omar Haddad'::text,
    simulate_answer(v_ask, 'omar@test.slyos', 'Priya Raman', 0.88, 'ran fraud with her for 3 years');
  return query select 'Ada Bishop'::text,
    simulate_answer(v_ask, 'ada@test.slyos',  'Priya Raman', 0.41, 'met at a conference');

  -- Somebody else entirely.
  return query select 'Mira Okonkwo'::text,
    simulate_answer(v_ask, 'mira@test.slyos', 'Tobi Adeyemi', 0.66, 'former colleague');

  -- The majority: found nothing, said nothing, owner never told.
  return query select 'Rune Halvorsen'::text,
    simulate_answer(v_ask, 'rune@test.slyos', null);
  return query select 'Sena Yilmaz'::text,
    simulate_answer(v_ask, 'sena@test.slyos', null);
end $$;

grant execute on function public.simulate_round(uuid) to authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Teardown. One statement, and the database is exactly as it was.
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.drop_test_network() returns void
language sql security definer set search_path = public as $$
  delete from auth.users where email like '%@test.slyos';
$$;
