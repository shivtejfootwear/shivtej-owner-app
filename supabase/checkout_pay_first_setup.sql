-- Shivtej Footwear - PAY FIRST, then the order.
-- 1) Placing "Pay now" does NOT create an order and does NOT cut stock. It only keeps the
--    checkout details in site_checkouts (and checks stock).
-- 2) The order (SW number) is created, and stock is cut, only when Razorpay reports the payment
--    (payment-webhook calls site_finalize_checkout).
-- 3) The mobile number is always the logged-in customer's number (checked by create-payment).
-- Run once in the Supabase SQL editor. Safe to run again.

-- ---------- 1) Checkouts waiting for payment (nobody can read this table from the website) ----------
create table if not exists public.site_checkouts (
  id uuid primary key default gen_random_uuid(),
  payload jsonb not null,
  mobile text not null,
  total numeric not null,
  razorpay_order_id text unique,
  status text not null default 'open',          -- open | done | failed_after_payment
  order_id text,
  razorpay_payment_id text,
  created_at timestamptz not null default now()
);
alter table public.site_checkouts enable row level security;

-- ---------- 2) Check the details and stock, keep the checkout (no order, no stock cut) ----------
create or replace function public.site_prepare_checkout(p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_mobile text; v_name text; v_addr text; v_pay text;
  v_country text; v_pin text; v_city text; v_state text;
  v_fee numeric; v_coupon text; v_cj jsonb; v_disc numeric := 0; v_ccode text;
  v_recent int; it jsonb; v_qty int; v_have int; prod products%rowtype;
  v_items jsonb := '[]'::jsonb; v_sub numeric := 0; v_id uuid; v_payload jsonb;
begin
  v_mobile  := regexp_replace(coalesce(p->>'mobile', ''), '\D', '', 'g');
  v_name    := left(coalesce(p->>'name', ''), 120);
  v_addr    := left(coalesce(p->>'address', ''), 500);
  v_pay     := left(coalesce(p->>'payment', ''), 60);
  v_country := left(coalesce(nullif(p->>'country', ''), 'India'), 60);
  v_pin     := left(regexp_replace(coalesce(p->>'pincode', ''), '\D', '', 'g'), 6);
  v_city    := left(coalesce(p->>'city', ''), 60);
  v_state   := left(coalesce(p->>'state', ''), 60);

  if length(v_mobile) < 10 or v_name = '' or v_addr = '' then raise exception 'INVALID_DETAILS'; end if;
  if v_country = 'India' and (length(v_pin) <> 6 or v_city = '' or v_state = '') then raise exception 'INVALID_DETAILS'; end if;
  if v_pay ilike '%cash%' then raise exception 'NOT_ONLINE'; end if;
  if jsonb_typeof(p->'items') is distinct from 'array'
     or jsonb_array_length(p->'items') not between 1 and 20 then
    raise exception 'INVALID_ITEMS';
  end if;

  select count(*) into v_recent from site_checkouts
   where created_at > now() - interval '1 hour' and mobile = v_mobile;
  if v_recent >= 20 then raise exception 'RATE_LIMIT'; end if;

  v_fee := public.site_shipping_fee();

  for it in select value from jsonb_array_elements(p->'items') loop
    v_qty := (it->>'qty')::int;
    if v_qty is null or v_qty < 1 or v_qty > 10 then raise exception 'INVALID_ITEMS'; end if;

    select * into prod from products
     where id = it->>'product_id' and coalesce(active, true);
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

    v_sub := v_sub + coalesce(prod.price, 0) * v_qty;
    v_items := v_items || jsonb_build_array(jsonb_build_object(
        'productId', prod.id, 'id', prod.id, 'name', prod.name, 'article', prod.article,
        'colour', it->>'colour', 'size', it->>'size', 'qty', v_qty, 'price', prod.price));
  end loop;

  v_coupon := nullif(upper(trim(coalesce(p->>'coupon', ''))), '');
  if v_coupon is not null then
    v_cj    := public._coupon_calc(v_coupon, p->'items');
    v_disc  := least((v_cj->>'discount')::numeric, v_sub);
    v_ccode := v_cj->>'code';
  end if;

  v_payload := jsonb_build_object(
    'name', v_name, 'mobile', v_mobile, 'address', v_addr, 'country', v_country,
    'pincode', v_pin, 'city', v_city, 'state', v_state, 'payment', v_pay,
    'items', v_items, 'subtotal', v_sub, 'discount', v_disc, 'coupon', v_ccode, 'shipping', v_fee);

  insert into site_checkouts (payload, mobile, total)
  values (v_payload, v_mobile, v_sub - v_disc + v_fee)
  returning id into v_id;

  return jsonb_build_object('ok', true, 'checkout_id', v_id, 'subtotal', v_sub, 'discount', v_disc,
                            'coupon', v_ccode, 'shipping', v_fee, 'total', v_sub - v_disc + v_fee);
end $$;

-- ---------- 3) After the payment: create the order, cut stock, mark it Paid (webhook only) ----------
create or replace function public.site_finalize_checkout(p_rz_order text, p_rz_payment text, p_paid_paise bigint)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  c site_checkouts%rowtype;
  v_p jsonb; it jsonb; v_idx int := 0; v_qty int; v_have int; prod products%rowtype;
  v_oid text; v_disc numeric; v_sub numeric; v_fee numeric; v_ccode text;
begin
  select * into c from site_checkouts where razorpay_order_id = p_rz_order for update;
  if not found then raise exception 'NO_CHECKOUT'; end if;
  if c.status = 'done' then
    return jsonb_build_object('ok', true, 'id', c.order_id, 'already', true);
  end if;
  if round(c.total * 100) <> p_paid_paise then raise exception 'AMOUNT_MISMATCH'; end if;

  v_p := c.payload;
  v_oid := 'SW' || floor(extract(epoch from clock_timestamp()) * 1000)::bigint::text
                || upper(substr(md5(random()::text), 1, 4));

  for it in select value from jsonb_array_elements(v_p->'items') loop
    v_idx := v_idx + 1;
    v_qty := (it->>'qty')::int;
    select * into prod from products where id = it->>'productId' for update;
    if not found then raise exception 'STOCK: product not available'; end if;

    v_have := null;
    select coalesce((s->>'qty')::int, 0) into v_have
      from jsonb_array_elements(coalesce(prod.colours, '[]'::jsonb)) col,
           jsonb_array_elements(coalesce(col->'sizes', '[]'::jsonb)) s
     where lower(coalesce(col->>'name', col->>'colour')) = lower(it->>'colour')
       and s->>'size' = it->>'size'
     limit 1;
    if v_have is null or v_have < v_qty then raise exception 'STOCK: not enough left'; end if;

    perform _apply_delta(prod.id, it->>'colour', it->>'size', -v_qty,
                         'WEB:' || v_oid || ':' || v_idx, 'ONLINE_ORDER', v_oid);
  end loop;

  v_disc  := coalesce((v_p->>'discount')::numeric, 0);
  v_sub   := coalesce((v_p->>'subtotal')::numeric, 0);
  v_fee   := coalesce((v_p->>'shipping')::numeric, 0);
  v_ccode := nullif(v_p->>'coupon', '');

  insert into orders (id, customer, items, subtotal, shipping, discount, coupon_code, total,
                      payment_status, order_status, created_at, stock_deducted, updated_at,
                      razorpay_order_id, razorpay_payment_id)
  values (v_oid,
          jsonb_build_object('name', v_p->>'name', 'mobile', v_p->>'mobile', 'address', v_p->>'address',
                             'country', v_p->>'country', 'pincode', v_p->>'pincode',
                             'city', v_p->>'city', 'state', v_p->>'state'),
          v_p->'items', v_sub, v_fee, v_disc, v_ccode, c.total,
          'Paid', 'New', now(), true, now(), p_rz_order, p_rz_payment);

  if v_ccode is not null then
    update coupons set used_count = used_count + 1, updated_at = now() where code = v_ccode;
  end if;

  update customers set name = coalesce(nullif(name, ''), v_p->>'name'), updated_at = now()
   where mobile = v_p->>'mobile';
  if not found then
    insert into customers (mobile, name, created_at, updated_at)
    values (v_p->>'mobile', v_p->>'name', now(), now());
  end if;

  update site_checkouts set status = 'done', order_id = v_oid, razorpay_payment_id = p_rz_payment
   where id = c.id;

  return jsonb_build_object('ok', true, 'id', v_oid, 'already', false);
end $$;

-- ---------- 4) The website asks: has the order of this payment been created? ----------
create or replace function public.site_checkout_order(p_rz text) returns text
language sql stable security definer set search_path = public as $$
  select order_id from public.site_checkouts
   where razorpay_order_id = p_rz and status = 'done' limit 1
$$;

-- ---------- 5) Permissions ----------
revoke all on function public.site_prepare_checkout(jsonb) from public, anon, authenticated;
revoke all on function public.site_finalize_checkout(text, text, bigint) from public, anon, authenticated;
grant execute on function public.site_prepare_checkout(jsonb) to service_role;
grant execute on function public.site_finalize_checkout(text, text, bigint) to service_role;
grant execute on function public.site_checkout_order(text) to anon, authenticated;

-- The old order function (order first, pay later) can no longer be called from the website.
revoke execute on function public.site_place_order(jsonb) from anon, authenticated;
