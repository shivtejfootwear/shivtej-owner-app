-- Shivtej Footwear - website orders now save pincode, city and state (needed for Shiprocket).
-- Run once in Supabase > SQL Editor AFTER website_sync.sql. Safe to re-run.
-- Only the customer details change. Prices, stock and everything else work exactly as before.

create or replace function public.site_place_order(p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_mobile text; v_name text; v_addr text; v_pay text;
  v_pin text; v_city text; v_state text;
  v_oid text; v_recent int;
  it jsonb; v_idx int := 0; v_qty int; v_have int;
  prod products%rowtype;
  v_items jsonb := '[]'::jsonb; v_sub numeric := 0;
begin
  v_mobile := regexp_replace(coalesce(p->>'mobile', ''), '\D', '', 'g');
  v_name   := left(coalesce(p->>'name', ''), 120);
  v_addr   := left(coalesce(p->>'address', ''), 500);
  v_pay    := left(coalesce(p->>'payment', ''), 60);
  v_pin    := left(regexp_replace(coalesce(p->>'pincode', ''), '\D', '', 'g'), 6);
  v_city   := left(coalesce(p->>'city', ''), 60);
  v_state  := left(coalesce(p->>'state', ''), 60);

  if length(v_mobile) < 10 or v_name = '' or v_addr = '' then
    raise exception 'INVALID_DETAILS';
  end if;
  if jsonb_typeof(p->'items') is distinct from 'array'
     or jsonb_array_length(p->'items') not between 1 and 20 then
    raise exception 'INVALID_ITEMS';
  end if;

  select count(*) into v_recent from orders
   where created_at > now() - interval '1 hour' and customer->>'mobile' = v_mobile;
  if v_recent >= 5 then raise exception 'RATE_LIMIT'; end if;

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
                             'pincode', v_pin, 'city', v_city, 'state', v_state),
          v_items, v_sub, 0, v_sub,
          case when v_pay ilike '%cash%' then 'COD - pending' else 'Pending payment' end,
          'New', now(), true, now());

  update customers set name = coalesce(nullif(name, ''), v_name), updated_at = now() where mobile = v_mobile;
  if not found then
    insert into customers (mobile, name, created_at, updated_at) values (v_mobile, v_name, now(), now());
  end if;

  return jsonb_build_object('ok', true, 'id', v_oid, 'total', v_sub);
end $$;

revoke all on function public.site_place_order(jsonb) from public;
grant execute on function public.site_place_order(jsonb) to anon, authenticated;
