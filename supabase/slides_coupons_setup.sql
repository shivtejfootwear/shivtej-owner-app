-- Shivtej Footwear - home slider (swap screens), coupons, discount on website orders.
-- Run once in Supabase > SQL Editor, in Database mode, AFTER checkout_shipping_setup.sql and
-- film_video_setup.sql. Safe to re-run. Nothing existing is deleted.
--
-- 1) Orders get a discount and a coupon code.
-- 2) New private table "coupons" (RLS on, no public policies). Only the functions below use it.
-- 3) Website: check a coupon (public), and the order function applies it on the server.
-- 4) Owner (Admin Portal): list / save coupons.
-- 5) Site settings: the key "slides" (home slider) is now allowed in owner_save_site_settings.

-- 1) Orders -----------------------------------------------------------------
alter table public.orders add column if not exists discount    numeric not null default 0;
alter table public.orders add column if not exists coupon_code text;

-- 2) Coupons ------------------------------------------------------------------
-- kind: 'flat' = rupees off, 'percent' = % off. category: null = all categories.
-- Coupons are switched off with active = false (never deleted, so old orders keep their code).
create table if not exists public.coupons (
  code        text primary key,
  kind        text not null check (kind in ('flat', 'percent')),
  value       numeric not null check (value > 0),
  category    text,
  active      boolean not null default true,
  used_count  int not null default 0,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
alter table public.coupons enable row level security;

-- 3) Discount for a cart. Prices and categories come from the products table, never from the browser.
create or replace function public._coupon_calc(p_code text, p_items jsonb)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  c coupons%rowtype;
  it jsonb;
  pr products%rowtype;
  v_elig numeric := 0;
  v_disc numeric := 0;
begin
  select * into c from coupons
   where code = upper(trim(coalesce(p_code, ''))) and active;
  if not found then raise exception 'COUPON_INVALID'; end if;

  for it in select value from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
    select * into pr from products where id = it->>'product_id';
    -- a product counts when the coupon category is one of its categories ("Men, Senior Men" has "Men" and "Senior Men")
    if found and (c.category is null or exists (
         select 1 from unnest(string_to_array(coalesce(pr.category, ''), ',')) as x
          where lower(trim(x)) = lower(trim(c.category)))) then
      v_elig := v_elig + coalesce(pr.price, 0)
                       * greatest(1, least(10, coalesce((it->>'qty')::int, 1)));
    end if;
  end loop;
  if v_elig <= 0 then raise exception 'COUPON_NOT_FOR_CART'; end if;

  v_disc := case when c.kind = 'percent' then round(v_elig * c.value / 100)
                 else least(c.value, v_elig) end;

  return jsonb_build_object('code', c.code, 'kind', c.kind, 'value', c.value,
                            'category', c.category, 'eligible', v_elig, 'discount', v_disc);
end $$;

-- Public: the website asks before placing the order, to show the discount.
create or replace function public.site_check_coupon(p_code text, p_items jsonb)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  return jsonb_build_object('ok', true) || public._coupon_calc(p_code, p_items);
exception when others then
  return jsonb_build_object('ok', false, 'error', sqlerrm);
end $$;

-- 3b) Website order: same as before, plus the coupon (checked again here). -------
create or replace function public.site_place_order(p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_mobile text; v_name text; v_addr text; v_pay text;
  v_country text; v_pin text; v_city text; v_state text;
  v_fee numeric;
  v_coupon text; v_cj jsonb; v_disc numeric := 0; v_ccode text;
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

  -- Coupon (optional). Raises COUPON_INVALID / COUPON_NOT_FOR_CART when it can not be used.
  v_coupon := nullif(upper(trim(coalesce(p->>'coupon', ''))), '');
  if v_coupon is not null then
    v_cj    := public._coupon_calc(v_coupon, p->'items');
    v_disc  := least((v_cj->>'discount')::numeric, v_sub);
    v_ccode := v_cj->>'code';
  end if;

  insert into orders (id, customer, items, subtotal, shipping, discount, coupon_code, total,
                      payment_status, order_status, created_at, stock_deducted, updated_at)
  values (v_oid,
          jsonb_build_object('name', v_name, 'mobile', v_mobile, 'address', v_addr,
                             'country', v_country, 'pincode', v_pin, 'city', v_city, 'state', v_state),
          v_items, v_sub, v_fee, v_disc, v_ccode, v_sub - v_disc + v_fee,
          case when v_pay ilike '%cash%' then 'COD - pending' else 'Pending payment' end,
          'New', now(), true, now());

  if v_ccode is not null then
    update coupons set used_count = used_count + 1, updated_at = now() where code = v_ccode;
  end if;

  update customers set name = coalesce(nullif(name, ''), v_name), updated_at = now() where mobile = v_mobile;
  if not found then
    insert into customers (mobile, name, created_at, updated_at) values (v_mobile, v_name, now(), now());
  end if;

  return jsonb_build_object('ok', true, 'id', v_oid, 'subtotal', v_sub, 'discount', v_disc,
                            'coupon', v_ccode, 'shipping', v_fee, 'total', v_sub - v_disc + v_fee);
end $$;

-- 4) Owner (Admin Portal): list and save coupons ---------------------------------
create or replace function public.owner_list_coupons(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  return coalesce((
    select jsonb_agg(jsonb_build_object('code', code, 'kind', kind, 'value', value,
                                        'category', category, 'active', active, 'used', used_count)
                     order by created_at desc)
      from coupons), '[]'::jsonb);
end $$;

-- Adds a coupon, or changes it when the code already exists (use active=false to switch it off).
create or replace function public.owner_save_coupon(p_code text, p_coupon jsonb) returns void
language plpgsql security definer set search_path = public as $$
declare
  v_code text; v_kind text; v_val numeric; v_cat text;
begin
  perform _owner_ok(p_code);
  v_code := upper(regexp_replace(coalesce(p_coupon->>'code', ''), '\s', '', 'g'));
  v_kind := coalesce(p_coupon->>'kind', '');
  v_val  := (p_coupon->>'value')::numeric;
  v_cat  := nullif(trim(coalesce(p_coupon->>'category', '')), '');
  if length(v_code) < 3 or length(v_code) > 20 then raise exception 'BAD_COUPON_CODE'; end if;
  if v_kind not in ('flat', 'percent') then raise exception 'BAD_COUPON_KIND'; end if;
  if v_val is null or v_val <= 0 or (v_kind = 'percent' and v_val > 100) then
    raise exception 'BAD_COUPON_VALUE';
  end if;

  insert into coupons (code, kind, value, category, active, updated_at)
  values (v_code, v_kind, v_val, v_cat, coalesce((p_coupon->>'active')::boolean, true), now())
  on conflict (code) do update
     set kind = excluded.kind, value = excluded.value, category = excluded.category,
         active = excluded.active, updated_at = now();
end $$;

-- 5) Site settings: owner_save_site_settings now also saves "slides" and "offer" (today's offer popup) ----------------
create or replace function public.owner_save_site_settings(p_code text, p_value jsonb)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v_old jsonb;
  v_pick jsonb;
begin
  perform _owner_ok(p_code);
  select value into v_old from site_settings where key = 'site';
  select jsonb_object_agg(k, p_value -> k) into v_pick
    from unnest(array['heroTitle','heroText','heroAnim','heroArticle',
                      'bandTitle','bandText','instagram','facebook','whatsapp',
                      'heroImage','heroArtImage','bandImage','filmVideo','filmArticle','slides','offer']) as k
   where p_value ? k;
  insert into site_settings (key, value, updated_at)
  values ('site', coalesce(v_old, '{}'::jsonb) || coalesce(v_pick, '{}'::jsonb), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

-- Permissions -----------------------------------------------------------------
revoke all on function public._coupon_calc(text, jsonb)              from public;
revoke all on function public.site_check_coupon(text, jsonb)         from public;
revoke all on function public.site_place_order(jsonb)                from public;
revoke all on function public.owner_list_coupons(text)               from public;
revoke all on function public.owner_save_coupon(text, jsonb)         from public;
revoke all on function public.owner_save_site_settings(text, jsonb)  from public;

grant execute on function public.site_check_coupon(text, jsonb)        to anon, authenticated;
grant execute on function public.site_place_order(jsonb)               to anon, authenticated;
grant execute on function public.owner_list_coupons(text)              to anon, authenticated;
grant execute on function public.owner_save_coupon(text, jsonb)        to anon, authenticated;
grant execute on function public.owner_save_site_settings(text, jsonb) to anon, authenticated;
