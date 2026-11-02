-- Shivtej Footwear - update 29: customer RETURN sent from the Managers app.
-- Run ONCE in Supabase > SQL Editor, AFTER update 28. Safe to run again. Nothing is deleted.
--
--   * A manager scans the returned piece in Make Bill > "Customer return" and sends it to the owner.
--   * The return waits in the same Staff Bills list of the owner app, marked as a return.
--   * The owner makes the return bill from it (stock goes back +1), then taps "हो गया".
--   * The manager's own list does not show returns (they are not bills).

-- 1. a kind column: 'bill' (old rows) or 'return'
alter table public.staff_pending_bills add column if not exists kind text not null default 'bill';

-- 2. manager sends a return (no stock check: the piece is coming back to the shop)
create or replace function public.staff_return_send(p_token text, p_device text, p_items jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype; v_items jsonb; v_id uuid; v_cnt int;
begin
  u := _staff_session(p_token, p_device);
  v_items := _staff_clean_items(p_items);
  if jsonb_array_length(v_items) < 1 then raise exception 'EMPTY_BILL'; end if;
  select count(*) into v_cnt from staff_pending_bills where staff_id = u.staff_id and status = 'pending';
  if v_cnt >= 50 then raise exception 'TOO_MANY_PENDING'; end if;
  insert into staff_pending_bills (staff_id, items, kind) values (u.staff_id, v_items, 'return') returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end $$;

-- 3. manager's own list: bills only (returns are not shown there)
create or replace function public.staff_bills_list(p_token text, p_device text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype;
begin
  u := _staff_session(p_token, p_device);
  return coalesce((select jsonb_agg(jsonb_build_object('id', id, 'items', items, 'at', created_at) order by created_at)
    from staff_pending_bills where staff_id = u.staff_id and status = 'pending' and kind = 'bill'), '[]'::jsonb);
end $$;

-- 4. owner's list: bills and returns, with the kind
create or replace function public.owner_staff_bills_list(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  return coalesce((select jsonb_agg(jsonb_build_object(
      'id', b.id, 'staff_id', b.staff_id, 'staff_name', coalesce(u.name, 'Manager'),
      'items', b.items, 'at', b.created_at, 'kind', b.kind) order by u.name, b.created_at)
    from staff_pending_bills b left join staff_app_users u on u.staff_id = b.staff_id
    where b.status = 'pending'), '[]'::jsonb);
end $$;

grant execute on function public.staff_return_send(text,text,jsonb) to anon, authenticated;
grant execute on function public.staff_bills_list(text,text) to anon, authenticated;
grant execute on function public.owner_staff_bills_list(text) to anon, authenticated;
revoke all on function public.staff_return_send(text,text,jsonb) from public;

-- 5. check (should show OK)
select case when exists (select 1 from information_schema.columns
  where table_schema = 'public' and table_name = 'staff_pending_bills' and column_name = 'kind')
  then 'OK - update 29 done' else 'NOT DONE' end as result;
