-- =====================================================================
-- OWNER APP UPDATE 22: the website charges the price of the size the customer chose
--  A size of an "Extra size" group has its own selling price (colours > sizes > price).
--  A size without its own price (0 or missing) sells at the product price, as before.
-- Run ONCE in Supabase > SQL Editor (safe to run again).
-- Only site_prepare_checkout changes (copy of owner_update_14_packing.sql, with the size price).
-- The last result line says "size price checkout ready".
-- =====================================================================

create or replace function public.site_prepare_checkout(p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_mobile text; v_name text; v_addr text; v_pay text;
  v_country text; v_pin text; v_city text; v_state text;
  v_fee numeric; v_coupon text; v_cj jsonb; v_disc numeric := 0; v_ccode text;
  v_recent int; it jsonb; v_qty int; v_have int; v_sprice numeric; v_unit numeric; prod products%rowtype;
  v_items jsonb := '[]'::jsonb; v_sub numeric := 0; v_id uuid; v_payload jsonb;
  v_courier jsonb := null; v_pack numeric := 0;
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

  -- packing charge set by the owner (Admin Portal "Packing charge"; 0 = none). It is added to every order.
  v_pack := greatest(coalesce(public.site_shipping_fee(), 0), 0);
  -- courier chosen by the customer (price checked with Shiprocket by create-payment) + packing; else only packing
  if coalesce(p->>'ship_fee', '') ~ '^\d{1,5}(\.\d{1,2})?$' and jsonb_typeof(p->'courier') = 'object' then
    v_fee := least((p->>'ship_fee')::numeric, 5000);
    v_courier := jsonb_build_object(
      'id', left(coalesce(p->'courier'->>'id', ''), 20),
      'name', left(coalesce(p->'courier'->>'name', ''), 80),
      'rate', v_fee,
      'days', p->'courier'->'days',
      'etd', left(coalesce(p->'courier'->>'etd', ''), 40));
    v_fee := v_fee + v_pack;
  else
    v_fee := v_pack;
  end if;

  for it in select value from jsonb_array_elements(p->'items') loop
    v_qty := (it->>'qty')::int;
    if v_qty is null or v_qty < 1 or v_qty > 10 then raise exception 'INVALID_ITEMS'; end if;

    select * into prod from products
     where id = it->>'product_id' and coalesce(active, true);
    if not found then raise exception 'STOCK: product not available'; end if;

    -- the stock and the size's own selling price (0 or missing = not set) of the colour and size the customer chose
    v_have := null; v_sprice := null;
    select coalesce((s->>'qty')::int, 0), nullif((s->>'price')::numeric, 0)
      into v_have, v_sprice
      from jsonb_array_elements(coalesce(prod.colours, '[]'::jsonb)) c,
           jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) s
     where lower(coalesce(c->>'name', c->>'colour')) = lower(it->>'colour')
       and s->>'size' = it->>'size'
     limit 1;
    if v_have is null or v_have < v_qty then
      raise exception 'STOCK: only % left of % (% / %)', coalesce(v_have, 0), prod.name, it->>'colour', it->>'size';
    end if;

    -- a size with its own price sells at that price; any other size sells at the product price
    v_unit := coalesce(v_sprice, prod.price, 0);
    v_sub := v_sub + v_unit * v_qty;
    v_items := v_items || jsonb_build_array(jsonb_build_object(
        'productId', prod.id, 'id', prod.id, 'name', prod.name, 'article', prod.article,
        'colour', it->>'colour', 'size', it->>'size', 'qty', v_qty, 'price', v_unit));
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
    'items', v_items, 'subtotal', v_sub, 'discount', v_disc, 'coupon', v_ccode, 'shipping', v_fee,
    'courier', v_courier, 'packing', v_pack);

  insert into site_checkouts (payload, mobile, total)
  values (v_payload, v_mobile, v_sub - v_disc + v_fee)
  returning id into v_id;

  return jsonb_build_object('ok', true, 'checkout_id', v_id, 'subtotal', v_sub, 'discount', v_disc,
                            'coupon', v_ccode, 'shipping', v_fee, 'packing', v_pack, 'total', v_sub - v_disc + v_fee);
end $$;

revoke all on function public.site_prepare_checkout(jsonb) from public, anon, authenticated;
grant execute on function public.site_prepare_checkout(jsonb) to service_role;

select 'size price checkout ready' as result;
