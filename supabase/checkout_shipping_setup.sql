-- Shivtej Footwear - checkout shipping charge, full address, live shipment status.
-- Run once in Supabase > SQL Editor AFTER admin_orders_setup.sql, shiprocket_setup.sql and
-- site_place_order_address.sql. Safe to re-run.

-- 1) Columns and the shipping setting ------------------------------------
alter table public.orders add column if not exists shipment_status text;

-- Shipping charge for website orders (admin can change it later). Default 80.
insert into public.site_settings (key, value, updated_at)
values ('shipping', '{"fee": 80}', now())
on conflict (key) do nothing;

-- Public: the fee shown at checkout.
create or replace function public.site_shipping_fee() returns numeric
language sql stable security definer set search_path = public as $$
  select coalesce((select (value->>'fee')::numeric from site_settings where key = 'shipping'), 80)
$$;

-- Admin only: change the checkout shipping fee.
create or replace function public.admin_set_shipping_fee(p_fee numeric) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not _is_admin() then raise exception 'NOT_ADMIN'; end if;
  if p_fee is null or p_fee < 0 or p_fee > 5000 then raise exception 'BAD_SHIPPING'; end if;
  insert into site_settings (key, value, updated_at)
  values ('shipping', jsonb_build_object('fee', p_fee), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

-- 2) Website order: fee comes from the server, never from the browser ---
create or replace function public.site_place_order(p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_mobile text; v_name text; v_addr text; v_pay text;
  v_country text; v_pin text; v_city text; v_state text;
  v_fee numeric;
  v_oid text; v_recent int;
  it jsonb; v_idx int := 0; v_qty int; v_have int;
  prod products%rowtype;
  v_items jsonb := '[]'::jsonb; v_sub numeric := 0;
begin
  v_mobile  := regexp_replace(coalesce(p->>'mobile', ''), '\D', '', 'g');
  v_name    := left(coalesce(p->>'name', ''), 120);
  v_addr    := left(coalesce(p->>'address', ''), 500);
  v_pay     := left(coalesce(p->>'payment', ''), 60);
  v_country := left(coalesce(nullif(p->>'country', ''), 'India'), 60);
  v_pin     := left(regexp_replace(coalesce(p->>'pincode', ''), '\D', '', 'g'), 6);
  v_city    := left(coalesce(p->>'city', ''), 60);
  v_state   := left(coalesce(p->>'state', ''), 60);

  if length(v_mobile) < 10 or v_name = '' or v_addr = '' then
    raise exception 'INVALID_DETAILS';
  end if;
  if v_country = 'India' and (length(v_pin) <> 6 or v_city = '' or v_state = '') then
    raise exception 'INVALID_DETAILS';
  end if;
  if jsonb_typeof(p->'items') is distinct from 'array'
     or jsonb_array_length(p->'items') not between 1 and 20 then
    raise exception 'INVALID_ITEMS';
  end if;

  select count(*) into v_recent from orders
   where created_at > now() - interval '1 hour' and customer->>'mobile' = v_mobile;
  if v_recent >= 5 then raise exception 'RATE_LIMIT'; end if;

  v_fee := public.site_shipping_fee();
  v_oid := 'SW' || floor(extract(epoch from clock_timestamp()) * 1000)::bigint::text
                || upper(substr(md5(random()::text), 1, 4));

  for it in select value from jsonb_array_elements(p->'items') loop
    v_idx := v_idx + 1;
    v_qty := (it->>'qty')::int;
    if v_qty is null or v_qty < 1 or v_qty > 10 then raise exception 'INVALID_ITEMS'; end if;

    select * into prod from products
     where id = it->>'product_id' and coalesce(active, true) for update;       -- lock the product row
    if not found then raise exception 'STOCK: product not available'; end if;

    v_have := null;
    select coalesce((s->>'qty')::int, 0) into v_have
      from jsonb_array_elements(coalesce(prod.colours, '[]'::jsonb)) c,
           jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) s
     where lower(coalesce(c->>'name', c->>'colour')) = lower(it->>'colour')
       and s->>'size' = it->>'size'
     limit 1;
    if v_have is null or v_have < v_qty then
      raise exception 'STOCK: only % left of % (% / %)', coalesce(v_have, 0), prod.name, it->>'colour', it->>'size';
    end if;

    perform _apply_delta(prod.id, it->>'colour', it->>'size', -v_qty,
                         'WEB:' || v_oid || ':' || v_idx, 'ONLINE_ORDER', v_oid);

    v_sub := v_sub + coalesce(prod.price, 0) * v_qty;
    v_items := v_items || jsonb_build_array(jsonb_build_object(
        'productId', prod.id, 'id', prod.id, 'name', prod.name, 'article', prod.article,
        'colour', it->>'colour', 'size', it->>'size', 'qty', v_qty, 'price', prod.price));
  end loop;

  insert into orders (id, customer, items, subtotal, shipping, total, payment_status, order_status,
                      created_at, stock_deducted, updated_at)
  values (v_oid,
          jsonb_build_object('name', v_name, 'mobile', v_mobile, 'address', v_addr,
                             'country', v_country, 'pincode', v_pin, 'city', v_city, 'state', v_state),
          v_items, v_sub, v_fee, v_sub + v_fee,
          case when v_pay ilike '%cash%' then 'COD - pending' else 'Pending payment' end,
          'New', now(), true, now());

  update customers set name = coalesce(nullif(name, ''), v_name), updated_at = now() where mobile = v_mobile;
  if not found then
    insert into customers (mobile, name, created_at, updated_at) values (v_mobile, v_name, now(), now());
  end if;

  return jsonb_build_object('ok', true, 'id', v_oid, 'subtotal', v_sub, 'shipping', v_fee, 'total', v_sub + v_fee);
end $$;

-- 3) Admin edits the customer's address / details before shipping ------
create or replace function public.admin_update_order_details(
  p_id text, p_name text, p_mobile text, p_country text, p_state text, p_city text, p_pincode text, p_address text)
returns void
language plpgsql security definer set search_path = public as $$
declare v_mobile text := regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g');
        v_pin text := regexp_replace(coalesce(p_pincode, ''), '\D', '', 'g');
begin
  if not _is_admin() then raise exception 'NOT_ADMIN'; end if;
  if length(v_mobile) < 10 or trim(coalesce(p_name, '')) = '' or trim(coalesce(p_address, '')) = '' then
    raise exception 'INVALID_DETAILS';
  end if;
  if coalesce(p_country, 'India') = 'India' and (length(v_pin) <> 6 or trim(coalesce(p_city,'')) = '' or trim(coalesce(p_state,'')) = '') then
    raise exception 'INVALID_DETAILS';
  end if;
  update orders set
     customer = coalesce(customer, '{}'::jsonb) || jsonb_build_object(
        'name', left(trim(p_name), 120), 'mobile', v_mobile, 'address', left(trim(p_address), 500),
        'country', left(coalesce(nullif(p_country, ''), 'India'), 60),
        'state', left(coalesce(p_state, ''), 60), 'city', left(coalesce(p_city, ''), 60), 'pincode', v_pin),
     updated_at = now()
   where id = p_id;
  if not found then raise exception 'ORDER_NOT_FOUND'; end if;
end $$;

-- 4) Customer tracking now also shows the live Shiprocket status ---------
drop function if exists public.site_track_order(text, text);
create or replace function public.site_track_order(p_id text, p_mobile text)
returns table (id text, order_status text, tracking_no text, courier text,
               shipping numeric, total numeric, created_at timestamptz, shipment_status text)
language sql security definer set search_path = public stable as $$
  select o.id, o.order_status, o.tracking_no, o.courier, o.shipping, o.total, o.created_at, o.shipment_status
    from orders o
   where o.id = upper(trim(coalesce(p_id, '')))
     and o.customer->>'mobile' = regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g')
$$;

-- 5) Permissions ------------------------------------------------------------
revoke all on function public.site_shipping_fee()                                       from public;
revoke all on function public.admin_set_shipping_fee(numeric)                           from public, anon;
revoke all on function public.site_place_order(jsonb)                                   from public;
revoke all on function public.admin_update_order_details(text, text, text, text, text, text, text, text) from public, anon;
revoke all on function public.site_track_order(text, text)                              from public;
grant execute on function public.site_shipping_fee()                                    to anon, authenticated;
grant execute on function public.admin_set_shipping_fee(numeric)                        to authenticated;
grant execute on function public.site_place_order(jsonb)                                to anon, authenticated;
grant execute on function public.admin_update_order_details(text, text, text, text, text, text, text, text) to authenticated;
grant execute on function public.site_track_order(text, text)                           to anon, authenticated;
