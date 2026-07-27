-- Account deletion, required by App Store Review Guideline 5.1.1(v).
--
-- No client key may delete an auth user, so the app calls this instead. SECURITY DEFINER lets it
-- run with the privileges of its owner while `auth.uid()` still identifies the caller — so a user
-- can only ever delete themselves, never anyone else.
--
-- Run once in the Supabase SQL editor.

create or replace function public.delete_account()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated';
  end if;

  -- Belt and braces: the client deletes these too, but a partially failed client call must not
  -- leave someone's data behind after their account is gone.
  delete from public.brain_items where user_id = uid;
  delete from public.vault_items where user_id = uid;
  delete from public.vault_meta  where user_id = uid;
  delete from public.profiles    where id = uid;

  delete from auth.users where id = uid;
end;
$$;

revoke all on function public.delete_account() from public, anon;
grant execute on function public.delete_account() to authenticated;
