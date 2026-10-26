-- Shivtej Footwear - update 23: Make Bill (Managers app) can not add more than the stock.
-- Run ONCE in Supabase > SQL Editor, AFTER update 17. Safe to run again. Nothing is deleted.
--
--   * The stock of a colour + size is read from the product (the same number the owner sees).
--   * Scan / lookup returns the stock, so the + button stops at the stock in the Managers app.
--   * The server checks again when the bill is sent or updated: if any item is more than the stock,
--     nothing is saved and the manager sees "स्टॉक में सिर्फ N है".

-- stock (number of pieces) of one colour + size of one product; 0 if not found
create or replace function public._staff_stock(p_pid text, p_colour text, p_size text) returns int
language sql stable security definer set search_path = public as $$
  select coalesce(max(_staff_num(s->>'qty')), 0)::int
  from products p,
       lateral jsonb_array_elements(case when jsonb_typeof(to_jsonb(p.colours))='array' then to_jsonb(p.colours) else '[]'::jsonb end) c,
       lateral jsonb_array_elements(case when jsonb_typeof(c->'sizes')='array' then c->'sizes' else '[]'::jsonb end) s
  where p.id::text = p_pid
    and lower(trim(coalesce(c->>'name', c->>'colour', ''))) = lower(trim(coalesce(p_colour, '')))
    and s->>'size' = p_size;
$$;

-- raises an error if any item has more pieces than the stock
create or replace function public._staff_check_stock(p_items jsonb) returns void
language plpgsql security definer set search_path = public as $$
declare it jsonb; v_have int;
begin
  for it in select value from jsonb_array_elements(p_items) loop
    v_have := _staff_stock(it->>'productId', it->>'colour', it->>'size');
    if (it->>'qty')::int > v_have then
      raise exception 'OVER_STOCK: % % / % - स्टॉक में सिर्फ % है',
        coalesce(nullif(it->>'article', ''), it->>'name'), it->>'colour', it->>'size', v_have;
    end if;
  end loop;
end $$;

revoke all on function public._staff_stock(text, text, text) from public, anon, authenticated;
revoke all on function public._staff_check_stock(jsonb) from public, anon, authenticated;

-- scan / barcode lookup: now also returns the stock of that colour + size
create or replace function public.staff_lookup(p_token text, p_device text, p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare code text := trim(coalesce(p_code,'')); r record;
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
  return jsonb_build_object('found', true, 'productId', r.id, 'article', coalesce(r.article,''),
    'name', coalesce(r.name,''), 'colour', coalesce(r.colour,''), 'size', coalesce(r.size,''),
    'mrp', coalesce(nullif(_staff_num((r.mrp)::text),0), _staff_num((r.price)::text), 0), 'barcode', code,
    'stock', _staff_stock(r.id::text, r.colour, r.size));
end $$;

-- manager sends a new pending bill (stock checked)
create or replace function public.staff_bill_send(p_token text, p_device text, p_items jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype; v_items jsonb; v_id uuid; v_cnt int;
begin
  u := _staff_session(p_token, p_device);
  v_items := _staff_clean_items(p_items);
  if jsonb_array_length(v_items) < 1 then raise exception 'EMPTY_BILL'; end if;
  perform _staff_check_stock(v_items);
  select count(*) into v_cnt from staff_pending_bills where staff_id = u.staff_id and status = 'pending';
  if v_cnt >= 50 then raise exception 'TOO_MANY_PENDING'; end if;
  insert into staff_pending_bills (staff_id, items) values (u.staff_id, v_items) returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end $$;

-- manager edits one of their own pending bills (stock checked)
create or replace function public.staff_bill_update(p_token text, p_device text, p_id uuid, p_items jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype; v_items jsonb; n int;
begin
  u := _staff_session(p_token, p_device);
  v_items := _staff_clean_items(p_items);
  if jsonb_array_length(v_items) < 1 then raise exception 'EMPTY_BILL'; end if;
  perform _staff_check_stock(v_items);
  update staff_pending_bills set items = v_items, updated_at = now()
   where id = p_id and staff_id = u.staff_id and status = 'pending';
  get diagnostics n = row_count;
  if n = 0 then raise exception 'NOT_PENDING'; end if;   -- not theirs, or already completed by the owner
  return jsonb_build_object('ok', true);
end $$;

select 'staff stock check ready' as result;
