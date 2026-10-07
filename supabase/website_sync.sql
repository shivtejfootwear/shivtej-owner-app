-- =====================================================================
-- SHIVTEJ FOOTWEAR - WEBSITE <-> OWNER APP SYNC   (run AFTER owner_app_setup.sql)
-- The website uses only these 3 functions (with the public key). Nothing is dropped or changed.
--   site_list_products()      -> active products with colours / sizes / live stock
--   site_place_order(p)       -> checks stock, deducts it atomically, saves the order
--   site_order_status(p_ids)  -> status / tracking of the customer's own orders (by order id)
-- =====================================================================

create or replace function public.site_list_products() returns setof public.products
language sql security definer set search_path = public stable as $$
  select * from products where coalesce(active, true) is true
$$;

create or replace function public.site_order_status(p_ids text[])
returns table (id text, order_status text, tracking_no text, courier text)
language sql security definer set search_path = public stable as $$
  select o.id, o.order_status, o.tracking_no, o.courier
    from orders o where o.id = any (p_ids)
$$;

-- p = { "name":..., "mobile":..., "address":..., "payment":..., "items":[{"product_id","colour","size","qty"}] }
-- Prices come from the products table (never from the browser). Any stock problem raises an error
-- that starts with "STOCK" and rolls the whole order back, so stock can never go wrong or be deducted twice.
create or replace function public.site_place_order(p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_mobile text; v_name text; v_addr text; v_pay text;
  v_oid text; v_recent int;
  it jsonb; v_idx int := 0; v_qty int; v_have int;
  prod products%rowtype;
  v_items jsonb := '[]'::jsonb; v_sub numeric := 0;
begin
  v_mobile := regexp_replace(coalesce(p->>'mobile', ''), '\D', '', 'g');
  v_name   := left(coalesce(p->>'name', ''), 120);
  v_addr   := left(coalesce(p->>'address', ''), 500);
  v_pay    := left(coalesce(p->>'payment', ''), 60);

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
          jsonb_build_object('name', v_name, 'mobile', v_mobile, 'address', v_addr),
          v_items, v_sub, 0, v_sub,
          case when v_pay ilike '%cash%' then 'COD - pending' else 'Pending payment' end,
          'New', now(), true, now());

  update customers set name = coalesce(nullif(name, ''), v_name), updated_at = now() where mobile = v_mobile;
  if not found then
    insert into customers (mobile, name, created_at, updated_at) values (v_mobile, v_name, now(), now());
  end if;

  return jsonb_build_object('ok', true, 'id', v_oid, 'total', v_sub);
end $$;

revoke all on function public.site_list_products()            from public;
revoke all on function public.site_order_status(text[])       from public;
revoke all on function public.site_place_order(jsonb)         from public;
grant execute on function public.site_list_products()         to anon, authenticated;
grant execute on function public.site_order_status(text[])    to anon, authenticated;
grant execute on function public.site_place_order(jsonb)      to anon, authenticated;

-- The website's existing Admin Portal looks for an "admin_users" table (id = Supabase Auth user, role = 'admin').
-- It did not exist, so the portal always said "not an active admin". Created empty; each admin can read only their own row.
-- To make someone an admin:  insert into public.admin_users (id) values ('<their Supabase Auth user id>');
create table if not exists public.admin_users (
  id         uuid primary key,
  role       text not null default 'admin',
  active     boolean not null default true,
  created_at timestamptz default now()
);
alter table public.admin_users enable row level security;
drop policy if exists "admin reads own row" on public.admin_users;
create policy "admin reads own row" on public.admin_users
  for select to authenticated using (id = auth.uid());
grant select on public.admin_users to authenticated;

-- =====================================================================
-- STEP B - RECOMMENDED, run separately AFTER you have tested the website + app once.
-- Locks direct table access for the public key. The website and the Owner App keep working because
-- they only use the functions above (functions run with the owner's rights and bypass RLS).
--
-- alter table public.products   enable row level security;
-- alter table public.orders     enable row level security;
-- alter table public.customers  enable row level security;
-- alter table public.payments   enable row level security;
-- alter table public.stock      enable row level security;
-- =====================================================================
