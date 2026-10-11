-- Shivtej Footwear - admin orders for the website Admin page (Google login).
-- Run once in Supabase > SQL Editor AFTER website_sync.sql and owner_app_setup.sql. Safe to re-run.
--
-- Only an active user in admin_users can use these functions (checked with auth.uid()).
-- The owner code is NOT used here.
-- Stock rules are the same as owner_update_order: web orders already deducted stock when placed
-- (stock_deducted = true); cancelling puts it back once; cancelling an order that has a return is refused.

alter table public.orders add column if not exists tracking_no    text;
alter table public.orders add column if not exists courier        text;
alter table public.orders add column if not exists stock_deducted boolean default false;
alter table public.orders add column if not exists updated_at     timestamptz default now();

-- Is the logged-in user an active admin?
create or replace function public._is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from admin_users where id = auth.uid() and active)
$$;

-- All orders, newest first (max 2000).
create or replace function public.admin_list_orders() returns setof public.orders
language plpgsql security definer set search_path = public as $$
begin
  if not _is_admin() then raise exception 'NOT_ADMIN'; end if;
  return query select * from orders order by created_at desc limit 2000;
end $$;

-- Change status, courier, tracking number and shipping charge of one order.
-- Total = (items total, unchanged) + shipping charge.
create or replace function public.admin_update_order(
  p_id text, p_status text, p_tracking text, p_courier text, p_shipping numeric)
returns void
language plpgsql security definer set search_path = public as $$
declare o orders%rowtype; rec record; stamp text := replace(gen_random_uuid()::text, '-', '');
        v_ship numeric; v_items_total numeric;
begin
  if not _is_admin() then raise exception 'NOT_ADMIN'; end if;
  if p_status not in ('New','Confirmed','Packed','Shipped','Delivered','Cancelled') then
    raise exception 'BAD_STATUS';
  end if;
  if p_shipping is not null and (p_shipping < 0 or p_shipping > 5000) then
    raise exception 'BAD_SHIPPING';
  end if;

  select * into o from orders where id = p_id for update;
  if not found then raise exception 'ORDER_NOT_FOUND'; end if;

  -- Stock: deduct once if the order never took stock (same as owner_update_order).
  if p_status in ('Confirmed','Packed','Shipped','Delivered') and not coalesce(o.stock_deducted,false) then
    for rec in select value as it, ordinality as idx
                 from jsonb_array_elements(coalesce(o.items,'[]'::jsonb)) with ordinality loop
      begin
        perform _apply_delta(coalesce(rec.it->>'productId', rec.it->>'id'), rec.it->>'colour', rec.it->>'size',
               -coalesce((rec.it->>'qty')::int, (rec.it->>'q')::int, 1),
               'ORD:' || p_id || ':' || rec.idx || ':d' || stamp, 'ONLINE_ORDER', p_id);
      exception when others then null; end;
    end loop;
    o.stock_deducted := true;
  -- Cancel: put stock back once, unless the order already has a return.
  elsif p_status = 'Cancelled' and coalesce(o.stock_deducted,false) then
    if exists (select 1 from returns_log where ref_type = 'ORDER' and ref_id = p_id) then
      raise exception 'ORDER_HAS_RETURN';
    end if;
    for rec in select value as it, ordinality as idx
                 from jsonb_array_elements(coalesce(o.items,'[]'::jsonb)) with ordinality loop
      begin
        perform _apply_delta(coalesce(rec.it->>'productId', rec.it->>'id'), rec.it->>'colour', rec.it->>'size',
               coalesce((rec.it->>'qty')::int, (rec.it->>'q')::int, 1),
               'ORD:' || p_id || ':' || rec.idx || ':r' || stamp, 'ORDER_CANCELLED', p_id);
      exception when others then null; end;
    end loop;
    o.stock_deducted := false;
  end if;

  v_ship        := coalesce(p_shipping, o.shipping, 0);
  v_items_total := coalesce(o.total, 0) - coalesce(o.shipping, 0);

  update orders set
         order_status   = p_status,
         tracking_no    = nullif(trim(coalesce(p_tracking,'')), ''),
         courier        = nullif(trim(coalesce(p_courier,'')), ''),
         shipping       = v_ship,
         total          = v_items_total + v_ship,
         stock_deducted = o.stock_deducted,
         updated_at     = now()
   where id = p_id;
end $$;

revoke all on function public._is_admin()                                              from public, anon;
revoke all on function public.admin_list_orders()                                      from public, anon;
revoke all on function public.admin_update_order(text, text, text, text, numeric)      from public, anon;
grant execute on function public.admin_list_orders()                                   to authenticated;
grant execute on function public.admin_update_order(text, text, text, text, numeric)   to authenticated;
