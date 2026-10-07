-- =====================================================================
-- SHIVTEJ FOOTWEAR - OWNER APP SETUP  (run once in Supabase > SQL Editor)
-- Additive only: nothing is dropped, no existing rows are changed.
-- Existing tables used: products, orders, customers.
--   (the existing "stock" and "payments" tables are left untouched)
-- =====================================================================

-- STEP 0 ------------------------------------------------------------
-- >>> CHANGE 'CHANGE-ME-1234' TO YOUR OWN SECRET OWNER CODE (8+ chars) <<<
-- You will type this same code once inside the app. Keep it private.
-- ------------------------------------------------------------------
create table if not exists public.owner_secret (
  id        int primary key default 1,
  code_hash text not null,
  constraint owner_secret_single check (id = 1)
);
alter table public.owner_secret enable row level security;   -- no policies = not readable by the public key

insert into public.owner_secret (id, code_hash)
values (1, encode(sha256(convert_to('CHANGE-ME-1234', 'utf8')), 'hex'))
on conflict (id) do nothing;      -- re-running this file never resets a code you already set

-- To CHANGE the owner code later, run this single statement (with your new code) in the SQL Editor:
--   update public.owner_secret set code_hash = encode(sha256(convert_to('MY-NEW-CODE', 'utf8')), 'hex') where id = 1;

-- STEP 1: extra columns on existing tables (nullable / defaulted) ----
alter table public.products add column if not exists brand    text;
alter table public.products add column if not exists category text;
alter table public.products add column if not exists new_item boolean default false;

alter table public.orders add column if not exists tracking_no     text;
alter table public.orders add column if not exists courier         text;
alter table public.orders add column if not exists stock_deducted  boolean default false;
alter table public.orders add column if not exists updated_at      timestamptz default now();

-- STEP 2: new tables --------------------------------------------------
create table if not exists public.stock_log (
  id          bigserial primary key,
  op_id       text unique not null,
  product_id  text,
  article     text,
  colour      text,
  size        text,
  delta       int  not null,
  qty_after   int,
  reason      text,
  ref         text,
  created_at  timestamptz default now()
);
alter table public.stock_log enable row level security;

create table if not exists public.pos_bills (
  id              text primary key,
  bill_no         text,
  total           numeric,
  payment_method  text,
  items           jsonb,
  note            text,
  created_at      timestamptz default now()
);
alter table public.pos_bills enable row level security;

-- STEP 3: owner check -------------------------------------------------
create or replace function public._owner_ok(p_code text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from owner_secret
                 where code_hash = encode(sha256(convert_to(coalesce(p_code,''), 'utf8')), 'hex')) then
    raise exception 'INVALID_OWNER_CODE' using errcode = '28000';
  end if;
end $$;

create or replace function public.owner_ping(p_code text) returns text
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code); return 'ok'; end $$;

-- STEP 4: atomic, idempotent stock change (one colour + one size) -----
-- products.colours = [ {"name":"Black","images":[..],"sizes":[{"size":"8","qty":5,"barcode":"SW123"}]} ]
create or replace function public._apply_delta(
  p_product text, p_colour text, p_size text, p_delta int,
  p_op text, p_reason text, p_ref text) returns int
language plpgsql security definer set search_path = public as $$
declare
  prod products%rowtype;
  c jsonb; s jsonb;
  newcols jsonb := '[]'::jsonb;
  newsizes jsonb;
  newq int := null;
begin
  if exists (select 1 from stock_log where op_id = p_op) then
    return (select qty_after from stock_log where op_id = p_op);
  end if;

  select * into prod from products where id = p_product for update;
  if not found then raise exception 'PRODUCT_NOT_FOUND %', p_product; end if;

  for c in select value from jsonb_array_elements(coalesce(prod.colours, '[]'::jsonb)) loop
    if lower(coalesce(c->>'name', c->>'colour')) = lower(p_colour) then
      newsizes := '[]'::jsonb;
      for s in select value from jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) loop
        if (s->>'size') = p_size then
          newq := greatest(0, coalesce((s->>'qty')::int, 0) + p_delta);
          s := jsonb_set(s, '{qty}', to_jsonb(newq));
        end if;
        newsizes := newsizes || jsonb_build_array(s);
      end loop;
      c := jsonb_set(c, '{sizes}', newsizes);
    end if;
    newcols := newcols || jsonb_build_array(c);
  end loop;

  if newq is null then
    raise exception 'VARIANT_NOT_FOUND % / % / %', p_product, p_colour, p_size;
  end if;

  update products set colours = newcols, updated_at = now() where id = p_product;
  insert into stock_log (op_id, product_id, article, colour, size, delta, qty_after, reason, ref)
  values (p_op, p_product, prod.article, p_colour, p_size, p_delta, newq, p_reason, p_ref);
  return newq;
end $$;

create or replace function public.owner_apply_stock(
  p_code text, p_product text, p_colour text, p_size text, p_delta int,
  p_op text, p_reason text) returns int
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  return _apply_delta(p_product, p_colour, p_size, p_delta, p_op, coalesce(p_reason,'MANUAL'), null);
end $$;

-- STEP 5: products ----------------------------------------------------
create or replace function public.owner_list_products(p_code text) returns setof public.products
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code); return query select * from products; end $$;

-- Upsert structure. For sizes that already exist on the server the SERVER qty is kept
-- (qty only changes through owner_apply_stock), so concurrent sales are never overwritten.
create or replace function public.owner_upsert_product(p_code text, p jsonb) returns text
language plpgsql security definer set search_path = public as $$
declare
  ex jsonb; outc jsonb := '[]'::jsonb; c jsonb; s jsonb; ns jsonb;
  exq text; allsizes jsonb; bc text; pid text := p->>'id';
begin
  perform _owner_ok(p_code);
  select colours into ex from products where id = pid for update;

  for c in select value from jsonb_array_elements(coalesce(p->'colours', '[]'::jsonb)) loop
    ns := '[]'::jsonb;
    for s in select value from jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) loop
      bc := nullif(s->>'barcode', '');
      if bc is not null and exists (
           select 1 from products pr,
                  jsonb_array_elements(coalesce(pr.colours,'[]'::jsonb)) pc,
                  jsonb_array_elements(coalesce(pc->'sizes','[]'::jsonb)) ps
           where pr.id <> pid and ps->>'barcode' = bc) then
        raise exception 'BARCODE_DUPLICATE %', bc;
      end if;
      if ex is not null then
        exq := null;
        select e2->>'qty' into exq
          from jsonb_array_elements(ex) e1,
               jsonb_array_elements(coalesce(e1->'sizes','[]'::jsonb)) e2
         where lower(coalesce(e1->>'name', e1->>'colour')) = lower(c->>'name')
           and e2->>'size' = s->>'size'
         limit 1;
        if exq is not null then s := jsonb_set(s, '{qty}', to_jsonb(exq::int)); end if;
      end if;
      ns := ns || jsonb_build_array(s);
    end loop;
    outc := outc || jsonb_build_array(jsonb_set(c, '{sizes}', ns));
  end loop;

  select coalesce(jsonb_agg(distinct sz), '[]'::jsonb) into allsizes
    from (select e2->>'size' as sz
            from jsonb_array_elements(outc) e1,
                 jsonb_array_elements(coalesce(e1->'sizes','[]'::jsonb)) e2) t;

  update products set
      article = p->>'article', name = p->>'name', description = p->>'description',
      brand = p->>'brand', category = p->>'category',
      price = (p->>'price')::numeric, mrp = (p->>'mrp')::numeric,
      active = coalesce((p->>'active')::boolean, true),
      new_item = coalesce((p->>'new_item')::boolean, false),
      images = coalesce(p->'images', '[]'::jsonb),
      colours = outc, sizes = allsizes, updated_at = now()
    where id = pid;
  if not found then
    insert into products (id, article, name, description, brand, category, price, mrp,
                          active, new_item, images, colours, sizes, updated_at)
    values (pid, p->>'article', p->>'name', p->>'description', p->>'brand', p->>'category',
            (p->>'price')::numeric, (p->>'mrp')::numeric,
            coalesce((p->>'active')::boolean, true), coalesce((p->>'new_item')::boolean, false),
            coalesce(p->'images', '[]'::jsonb), outc, allsizes, now());
  end if;
  return pid;
end $$;

create or replace function public.owner_delete_product(p_code text, p_id text) returns void
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code); delete from products where id = p_id; end $$;

-- STEP 6: POS bills (idempotent by bill id) ---------------------------
create or replace function public.owner_create_bill(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare rec record; warns jsonb := '[]'::jsonb; bid text := p->>'id';
begin
  perform _owner_ok(p_code);
  if exists (select 1 from pos_bills where id = bid) then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;
  insert into pos_bills (id, bill_no, total, payment_method, items, created_at)
  values (bid, p->>'bill_no', (p->>'total')::numeric, p->>'payment_method',
          p->'items', coalesce((p->>'created_at')::timestamptz, now()));
  for rec in select value as it, ordinality as idx
               from jsonb_array_elements(coalesce(p->'items','[]'::jsonb)) with ordinality loop
    begin
      perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size',
                           -((rec.it->>'qty')::int), bid || '#' || rec.idx, 'POS_SALE', p->>'bill_no');
    exception when others then
      warns := warns || jsonb_build_array(sqlerrm);
    end;
  end loop;
  return jsonb_build_object('ok', true, 'warnings', warns);
end $$;

create or replace function public.owner_list_bills(p_code text) returns setof public.pos_bills
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code);
  return query select * from pos_bills order by created_at desc limit 2000; end $$;

-- STEP 7: orders, customers, stock log --------------------------------
create or replace function public.owner_list_orders(p_code text) returns setof public.orders
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code);
  return query select * from orders order by created_at desc limit 2000; end $$;

-- Confirming an online order deducts stock once; cancelling a confirmed order restores it.
create or replace function public.owner_update_order(
  p_code text, p_id text, p_status text, p_tracking text, p_courier text, p_shipping numeric)
returns void
language plpgsql security definer set search_path = public as $$
declare o orders%rowtype; rec record; stamp text := replace(gen_random_uuid()::text, '-', '');
begin
  perform _owner_ok(p_code);
  select * into o from orders where id = p_id for update;
  if not found then raise exception 'ORDER_NOT_FOUND %', p_id; end if;

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
  elsif p_status = 'Cancelled' and coalesce(o.stock_deducted,false) then
    if exists (select 1 from returns_log where ref_type = 'ORDER' and ref_id = p_id) then
      raise exception 'ORDER_HAS_RETURN';      -- already (partly) returned: cancelling would put the stock back twice
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

  update orders set order_status = p_status, tracking_no = p_tracking, courier = p_courier,
         shipping = coalesce(p_shipping, shipping), stock_deducted = o.stock_deducted, updated_at = now()
   where id = p_id;
end $$;

create or replace function public.owner_list_customers(p_code text) returns setof public.customers
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code); return query select * from customers limit 5000; end $$;

create or replace function public.owner_stock_log(p_code text, p_after bigint) returns setof public.stock_log
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code);
  return query select * from stock_log where id > p_after order by id limit 2000; end $$;

-- STEP 8: allow the public key to call ONLY the owner functions -------
revoke all on function public.owner_ping(text)                                   from public;
revoke all on function public.owner_apply_stock(text,text,text,text,int,text,text) from public;
revoke all on function public.owner_list_products(text)                           from public;
revoke all on function public.owner_upsert_product(text,jsonb)                    from public;
revoke all on function public.owner_delete_product(text,text)                     from public;
revoke all on function public.owner_create_bill(text,jsonb)                       from public;
revoke all on function public.owner_list_bills(text)                              from public;
revoke all on function public.owner_list_orders(text)                             from public;
revoke all on function public.owner_update_order(text,text,text,text,text,numeric) from public;
revoke all on function public.owner_list_customers(text)                          from public;
revoke all on function public.owner_stock_log(text,bigint)                        from public;
revoke all on function public._owner_ok(text)                                     from public;
revoke all on function public._apply_delta(text,text,text,int,text,text,text)     from public;

grant execute on function public.owner_ping(text)                                   to anon, authenticated;
grant execute on function public.owner_apply_stock(text,text,text,text,int,text,text) to anon, authenticated;
grant execute on function public.owner_list_products(text)                           to anon, authenticated;
grant execute on function public.owner_upsert_product(text,jsonb)                    to anon, authenticated;
grant execute on function public.owner_delete_product(text,text)                     to anon, authenticated;
grant execute on function public.owner_create_bill(text,jsonb)                       to anon, authenticated;
grant execute on function public.owner_list_bills(text)                              to anon, authenticated;
grant execute on function public.owner_list_orders(text)                             to anon, authenticated;
grant execute on function public.owner_update_order(text,text,text,text,text,numeric) to anon, authenticated;
grant execute on function public.owner_list_customers(text)                          to anon, authenticated;
grant execute on function public.owner_stock_log(text,bigint)                        to anon, authenticated;

-- STEP 9: product photo storage bucket --------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('product-images', 'product-images', true, 2097152, array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

drop policy if exists "owner app upload product images" on storage.objects;
create policy "owner app upload product images" on storage.objects
  for insert to anon, authenticated with check (bucket_id = 'product-images');

drop policy if exists "public read product images" on storage.objects;
create policy "public read product images" on storage.objects
  for select to anon, authenticated using (bucket_id = 'product-images');

-- STEP 10: returns & exchanges (idempotent by return id; stock changes through _apply_delta) ---------
create table if not exists public.returns_log (
  id          text primary key,                 -- generated by the app, so a retry can never be applied twice
  kind        text not null,                    -- 'RETURN' | 'EXCHANGE'
  ref_type    text not null,                    -- 'ORDER' (website order) | 'BILL' (POS bill)
  ref_id      text not null,
  reason      text,
  refund      numeric default 0,                -- money given back (negative = customer paid the difference)
  restock     boolean default true,             -- false = damaged item, not put back on the shelf
  items_in    jsonb,                            -- [{productId,colour,size,qty,price,name,article}] coming back
  items_out   jsonb,                            -- exchange only: replacement items handed over
  note        text,
  created_at  timestamptz default now()
);
alter table public.returns_log enable row level security;

create or replace function public._variant_qty(p_product text, p_colour text, p_size text) returns int
language sql security definer set search_path = public stable as $$
  select (s->>'qty')::int
    from products p,
         jsonb_array_elements(coalesce(p.colours, '[]'::jsonb)) c,
         jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) s
   where p.id = p_product
     and lower(coalesce(c->>'name', c->>'colour')) = lower(p_colour)
     and s->>'size' = p_size
   limit 1
$$;

create or replace function public.owner_process_return(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  rid text := p->>'id';
  k text := upper(coalesce(p->>'kind', 'RETURN'));
  rtype text := upper(coalesce(p->>'ref_type', 'ORDER'));
  refid text := p->>'ref_id';
  v_restock boolean := coalesce((p->>'restock')::boolean, true);
  o orders%rowtype; b pos_bills%rowtype;
  sold_items jsonb;
  rec record; q int; sold int; done int; have int;
  total_sold int; total_ret int;
  warns jsonb := '[]'::jsonb;
begin
  perform _owner_ok(p_code);
  if rid is null or rid = '' or refid is null or k not in ('RETURN', 'EXCHANGE') or rtype not in ('ORDER', 'BILL') then
    raise exception 'INVALID_RETURN';
  end if;
  if jsonb_typeof(p->'items_in') is distinct from 'array' or jsonb_array_length(p->'items_in') = 0 then
    raise exception 'INVALID_RETURN: no items selected';
  end if;
  if k = 'EXCHANGE' and (jsonb_typeof(p->'items_out') is distinct from 'array' or jsonb_array_length(p->'items_out') = 0) then
    raise exception 'INVALID_RETURN: an exchange needs replacement items';
  end if;

  -- lock the sale first: concurrent / repeated requests for the same sale are processed one after another
  if rtype = 'ORDER' then
    select * into o from orders where id = refid for update;
    if not found then raise exception 'ORDER_NOT_FOUND %', refid; end if;
    sold_items := coalesce(o.items, '[]'::jsonb);
  else
    select * into b from pos_bills where id = refid for update;
    if not found then raise exception 'BILL_NOT_FOUND %', refid; end if;
    sold_items := coalesce(b.items, '[]'::jsonb);
  end if;

  if exists (select 1 from returns_log where id = rid) then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;
  if rtype = 'ORDER' and (not coalesce(o.stock_deducted, false) or o.order_status = 'Cancelled') then
    raise exception 'ORDER_NOT_RETURNABLE';
  end if;

  -- 1) items coming back: never more than was sold and not yet returned
  for rec in select value as it, ordinality as idx from jsonb_array_elements(p->'items_in') with ordinality loop
    q := (rec.it->>'qty')::int;
    if q is null or q < 1 then raise exception 'INVALID_RETURN: quantity'; end if;
    select coalesce(sum(coalesce((x->>'qty')::int, (x->>'q')::int, 1)), 0) into sold
      from jsonb_array_elements(sold_items) x
     where coalesce(x->>'productId', x->>'id') = rec.it->>'productId'
       and lower(coalesce(x->>'colour', '')) = lower(coalesce(rec.it->>'colour', ''))
       and coalesce(x->>'size', '') = coalesce(rec.it->>'size', '');
    select coalesce(sum((y->>'qty')::int), 0) into done
      from returns_log r, jsonb_array_elements(coalesce(r.items_in, '[]'::jsonb)) y
     where r.ref_type = rtype and r.ref_id = refid
       and y->>'productId' = rec.it->>'productId'
       and lower(coalesce(y->>'colour', '')) = lower(coalesce(rec.it->>'colour', ''))
       and coalesce(y->>'size', '') = coalesce(rec.it->>'size', '');
    if q > sold - done then
      raise exception 'RETURN_TOO_MANY: only % can still be returned for % (% / %)',
        greatest(sold - done, 0), coalesce(rec.it->>'name', ''), rec.it->>'colour', rec.it->>'size';
    end if;
    if v_restock then
      begin
        perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size', q,
                             'RET:' || rid || ':in:' || rec.idx,
                             case when k = 'EXCHANGE' then 'EXCHANGE_IN' else 'RETURN' end, refid);
      exception when others then
        warns := warns || jsonb_build_array(sqlerrm);
      end;
    end if;
  end loop;

  -- 2) exchange: replacement items must really be in stock, otherwise the whole thing is rolled back
  if k = 'EXCHANGE' then
    for rec in select value as it, ordinality as idx from jsonb_array_elements(p->'items_out') with ordinality loop
      q := (rec.it->>'qty')::int;
      if q is null or q < 1 then raise exception 'INVALID_RETURN: quantity'; end if;
      perform 1 from products where id = rec.it->>'productId' for update;
      have := _variant_qty(rec.it->>'productId', rec.it->>'colour', rec.it->>'size');
      if have is null or have < q then
        raise exception 'STOCK: only % left of % (% / %)', coalesce(have, 0), coalesce(rec.it->>'name', ''), rec.it->>'colour', rec.it->>'size';
      end if;
      perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size', -q,
                           'RET:' || rid || ':out:' || rec.idx, 'EXCHANGE_OUT', refid);
    end loop;
  end if;

  insert into returns_log (id, kind, ref_type, ref_id, reason, refund, restock, items_in, items_out, note, created_at)
  values (rid, k, rtype, refid, left(coalesce(p->>'reason', ''), 300),
          coalesce((p->>'refund')::numeric, 0), v_restock, p->'items_in', p->'items_out',
          left(coalesce(p->>'note', ''), 300), coalesce((p->>'created_at')::timestamptz, now()));

  if rtype = 'ORDER' then
    select coalesce(sum(coalesce((x->>'qty')::int, (x->>'q')::int, 1)), 0) into total_sold
      from jsonb_array_elements(sold_items) x;
    select coalesce(sum((y->>'qty')::int), 0) into total_ret
      from returns_log r, jsonb_array_elements(coalesce(r.items_in, '[]'::jsonb)) y
     where r.ref_type = 'ORDER' and r.ref_id = refid and r.kind = 'RETURN';
    update orders set updated_at = now(),
           order_status = case when k = 'EXCHANGE' then 'Exchanged'
                               when total_ret >= total_sold then 'Returned'
                               else order_status end
     where id = refid;
  end if;

  return jsonb_build_object('ok', true, 'warnings', warns);
end $$;

create or replace function public.owner_list_returns(p_code text) returns setof public.returns_log
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code);
  return query select * from returns_log order by created_at desc limit 2000; end $$;

revoke all on function public._variant_qty(text,text,text)            from public;
revoke all on function public.owner_process_return(text,jsonb)        from public;
revoke all on function public.owner_list_returns(text)                from public;
grant execute on function public.owner_process_return(text,jsonb)     to anon, authenticated;
grant execute on function public.owner_list_returns(text)             to anon, authenticated;

-- =====================================================================
-- OPTIONAL (recommended, run separately AFTER the app works):
-- Without row-level security anyone holding the website's public key could edit
-- products/orders directly. This locks direct table access; the Owner App keeps
-- working because it only uses the owner_* functions above.
-- NOTE: it also blocks the *current* website's direct table access except what
-- the two policies below allow, so only run it once the website is updated.
--
-- alter table public.products  enable row level security;
-- alter table public.orders    enable row level security;
-- alter table public.customers enable row level security;
-- alter table public.payments  enable row level security;
-- create policy "public read active products" on public.products
--   for select to anon, authenticated using (active is true);
-- create policy "public create orders" on public.orders
--   for insert to anon, authenticated with check (true);
-- =====================================================================

-- Final check (shows one line in the result panel) ---------------------
select case when (select code_hash from public.owner_secret where id = 1)
                 = encode(sha256(convert_to('CHANGE-ME-1234', 'utf8')), 'hex')
            then 'WARNING: the owner code is still the default CHANGE-ME-1234 - change it now (see STEP 0)'
            else 'OK: owner code is set and the Owner App functions are installed' end as status;
