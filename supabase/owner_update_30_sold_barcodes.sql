-- Owner update 30: one barcode = one piece, also on the server (run once in the Supabase SQL Editor).
-- A piece whose barcode is already on a (not deleted) shop bill can not be sent again by a manager.
-- A piece that came back (return with stock) can be sold again.
-- Safe to run more than once. Nothing is deleted or changed in the data.

-- STEP 1: which bill sold this barcode (null = not sold, or returned to stock after the sale)
create or replace function public._barcode_sold_bill(p_code text) returns text
language sql stable security definer set search_path = public as $$
  select b.bill_no::text
    from pos_bills b,
         jsonb_array_elements(case when jsonb_typeof(b.items) = 'array' then b.items else '[]'::jsonb end) it
   where trim(coalesce(p_code, '')) <> ''
     and not coalesce(b.deleted, false)
     and trim(p_code) = any (string_to_array(replace(coalesce(it->>'barcode', ''), ' ', ''), ','))
     and not exists (
       select 1
         from returns_log r,
              jsonb_array_elements(case when jsonb_typeof(r.items_in) = 'array' then r.items_in else '[]'::jsonb end) y
        where coalesce(r.restock, false)
          and r.created_at >= b.created_at
          and ( y->>'barcode' = trim(p_code)
             -- older returns have no barcode: the same item of the same bill came back
             or ( coalesce(y->>'barcode', '') = '' and r.ref_id = b.id::text
                  and y->>'productId' = it->>'productId' and y->>'colour' = it->>'colour' and y->>'size' = it->>'size') ))
   order by b.created_at desc
   limit 1
$$;

-- STEP 2: a manager's bill (send / update) is refused when one of its pieces is already sold
create or replace function public._staff_check_stock(p_items jsonb) returns void
language plpgsql security definer set search_path = public as $$
declare it jsonb; v_have int; c text; v_bill text;
begin
  for it in select value from jsonb_array_elements(p_items) loop
    v_have := _staff_stock(it->>'productId', it->>'colour', it->>'size');
    if (it->>'qty')::int > v_have then
      raise exception 'OVER_STOCK: % % / % - स्टॉक में सिर्फ % है',
        coalesce(nullif(it->>'article', ''), it->>'name'), it->>'colour', it->>'size', v_have;
    end if;
    for c in select trim(x) from unnest(string_to_array(coalesce(it->>'barcode', ''), ',')) x where trim(x) <> '' loop
      v_bill := _barcode_sold_bill(c);
      if v_bill is not null then
        raise exception 'ALREADY_SOLD: % % / % - यह पीस (बारकोड %) बिल % में पहले ही बिक चुका है',
          coalesce(nullif(it->>'article', ''), it->>'name'), it->>'colour', it->>'size', c, v_bill;
      end if;
    end loop;
  end loop;
end $$;

-- STEP 3: a manager's scan tells whether the piece is already sold (sold = true, bill = bill number)
create or replace function public.staff_lookup(p_token text, p_device text, p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare code text := trim(coalesce(p_code,'')); r record; v_bill text;
begin
  perform _staff_session(p_token, p_device);
  if code = '' then return jsonb_build_object('found', false); end if;
  select p.id, p.article, p.name, p.mrp, p.price, c->>'name' as colour, s->>'size' as size
    into r
    from products p,
         lateral jsonb_array_elements(case when jsonb_typeof(to_jsonb(p.colours))='array' then to_jsonb(p.colours) else '[]'::jsonb end) c,
         lateral jsonb_array_elements(case when jsonb_typeof(c->'sizes')='array' then c->'sizes' else '[]'::jsonb end) s
   where coalesce(p.active, true)
     and ( (s->>'barcode') = code
        or (jsonb_typeof(s->'barcodes')='array' and s->'barcodes' ? code) )
   limit 1;
  if not found then return jsonb_build_object('found', false); end if;
  v_bill := _barcode_sold_bill(code);
  return jsonb_build_object('found', true, 'productId', r.id, 'article', coalesce(r.article,''),
    'name', coalesce(r.name,''), 'colour', coalesce(r.colour,''), 'size', coalesce(r.size,''),
    'mrp', coalesce(nullif(_staff_num((r.mrp)::text),0), _staff_num((r.price)::text), 0), 'barcode', code,
    'stock', _staff_stock(r.id::text, r.colour, r.size),
    'sold', v_bill is not null, 'bill', coalesce(v_bill, ''));
end $$;

-- STEP 4 (check): the last line must say OK
select case when exists (select 1 from pg_proc where proname = '_barcode_sold_bill') then 'OK' else 'MISSING' end as result;
