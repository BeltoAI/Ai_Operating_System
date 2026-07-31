-- Remove every trace of the test network, and the scaffolding that created it.
--
-- Run this before letting real people in. The six @test.slyos accounts existed so the network had
-- somebody in it before it had anybody in it; a beta tester who opens Orbit and finds Ada Bishop
-- and Priya Raman has no way to know which parts are real, and will reasonably assume none of it is.
--
-- Safe to run more than once. Safe to run with real users present — every statement is scoped to
-- the test namespace or to rows that reference it.

-- 1) The accounts. Everything they own — profiles, asks, candidacies, bridges — is wired with
--    `on delete cascade`, so this one statement takes their side of the network with them.
delete from auth.users where email like '%@test.slyos';

-- 2) Bridges naming people who only ever existed in the seed. The holder is gone by now and the
--    row cascaded with them; this catches any that were written against a real holder by hand.
delete from public.bridges
 where person in ('Priya Raman', 'Tobi Adeyemi');

-- 3) Asks written while testing. Deliberately narrow: it matches the exact criteria strings used
--    during the test run rather than anything that looks like a test, because a real beta user is
--    perfectly likely to write "who can introduce me to a payments investor" and mean it.
delete from public.asks
 where criteria in (
   'who can introduce me to a payments investor',
   'who can introduce me to a fraud lead at a payments company',
   'who knows a fraud lead at a European payments company'
 );

-- 4) The scaffolding itself. `simulate_answer` refuses non-test accounts, so it is harmless once
--    the accounts are gone — but a function that writes bridges on somebody's behalf has no
--    business existing in a database real people are using.
drop function if exists public.simulate_round(uuid);
drop function if exists public.simulate_answer(uuid, text, text, real, text);
drop function if exists public.drop_test_network();

-- 5) Give everyone their weekly asks back, since testing spent them.
select public.reset_weekly_asks();

-- What should be left: real accounts only, and whatever they genuinely did.
select
  (select count(*) from public.profiles)                                as profiles,
  (select count(*) from auth.users where email like '%@test.slyos')     as test_accounts_remaining,
  (select count(*) from public.asks)                                    as asks,
  (select count(*) from public.bridges)                                 as introductions;
