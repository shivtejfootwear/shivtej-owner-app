-- Shivtej Footwear - update 17: "Make Bill" in the Managers app -> "Staff Bills" in the Owner app.
-- Run ONCE in Supabase > SQL Editor, AFTER update 15 (staff app). Safe to run again. Nothing is deleted.
--
-- Flow:
--   * A manager adds items in the Managers app and taps "Send bill to owner". It is saved here as a PENDING bill.
--   * The manager sees only their own PENDING bills (numbered 1,2,3 by age) and can edit (add / change qty) or cancel
--     a pending bill - until the owner completes it.
--   * The owner opens "Staff Bills", picks one, sets the customer and rate, and completes it. It then turns 'done'
--     and disappears from both lists; the manager's remaining pending bills move up (2 becomes 1, ...).
-- Everything lives on the server, so no data is lost if either app is reinstalled.

create extension if not exists pgcrypto;

-- a number from any text/numeric value, or null (also defined in update 15; repeated so update 17 stands alone)
create or replace function public._staff_num(t text) returns numeric
language sql immutable as $$
  select case when trim(coalesce(t, '')) ~ '^-?\d+(\.\d+)?$' then trim(t)::numeric end
$$;
revoke all on function public._staff_num(text) from public, anon, authenticated;

create table if not exists public.staff_pending_bills (
  id          uuid primary key default gen_random_uuid(),
  staff_id    text not null,
  items       jsonb not null default '[]'::jsonb,
  status      text not null default 'pending',   -- pending | done | cancelled
  pos_bill_id text,                              -- the POS bill the owner made from it
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
alter table public.staff_pending_bills enable row level security;
create index if not exists staff_pending_bills_pending on public.staff_pending_bills (status, staff_id, created_at);
revoke all on public.staff_pending_bills from anon, authenticated;

-- a clean, size-checked copy of the items the app sent (never trust the client blindly)
create or replace function public._staff_clean_items(p_items jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare it jsonb; out jsonb := '[]'::jsonb; q int;
begin
  if jsonb_typeof(p_items) is distinct from 'array' then return '[]'::jsonb; end if;
  for it in select value from jsonb_array_elements(p_items) loop
    q := greatest(1, least(99, coalesce((it->>'qty')::int, 1)));
    if coalesce(it->>'productId','') <> '' and coalesce(it->>'size','') <> '' then
      out := out || jsonb_build_array(jsonb_build_object(
        'productId', it->>'productId', 'article', left(coalesce(it->>'article',''),60),
        'name', left(coalesce(it->>'name',''),120), 'colour', left(coalesce(it->>'colour',''),60),
        'size', left(it->>'size',20), 'barcode', left(coalesce(it->>'barcode',''),400),
        'qty', q, 'mrp', coalesce((it->>'mrp')::numeric, 0)));
    end if;
  end loop;
  return out;
end $$;

-- resolve one scanned barcode to a product / colour / size (Managers app "Make Bill" scanning)
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
    'mrp', coalesce(nullif(_staff_num((r.mrp)::text),0), _staff_num((r.price)::text), 0), 'barcode', code);
end $$;

-- manager sends a new pending bill
create or replace function public.staff_bill_send(p_token text, p_device text, p_items jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype; v_items jsonb; v_id uuid; v_cnt int;
begin
  u := _staff_session(p_token, p_device);
  v_items := _staff_clean_items(p_items);
  if jsonb_array_length(v_items) < 1 then raise exception 'EMPTY_BILL'; end if;
  select count(*) into v_cnt from staff_pending_bills where staff_id = u.staff_id and status = 'pending';
  if v_cnt >= 50 then raise exception 'TOO_MANY_PENDING'; end if;
  insert into staff_pending_bills (staff_id, items) values (u.staff_id, v_items) returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end $$;

-- manager edits one of their own pending bills
create or replace function public.staff_bill_update(p_token text, p_device text, p_id uuid, p_items jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype; v_items jsonb; n int;
begin
  u := _staff_session(p_token, p_device);
  v_items := _staff_clean_items(p_items);
  if jsonb_array_length(v_items) < 1 then raise exception 'EMPTY_BILL'; end if;
  update staff_pending_bills set items = v_items, updated_at = now()
   where id = p_id and staff_id = u.staff_id and status = 'pending';
  get diagnostics n = row_count;
  if n = 0 then raise exception 'NOT_PENDING'; end if;   -- not theirs, or already completed by the owner
  return jsonb_build_object('ok', true);
end $$;

-- manager cancels one of their own pending bills
create or replace function public.staff_bill_delete(p_token text, p_device text, p_id uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype;
begin
  u := _staff_session(p_token, p_device);
  update staff_pending_bills set status = 'cancelled', updated_at = now()
   where id = p_id and staff_id = u.staff_id and status = 'pending';
  return jsonb_build_object('ok', true);
end $$;

-- manager's own pending bills, oldest first (the app numbers them 1,2,3...)
create or replace function public.staff_bills_list(p_token text, p_device text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype;
begin
  u := _staff_session(p_token, p_device);
  return coalesce((select jsonb_agg(jsonb_build_object('id', id, 'items', items, 'at', created_at) order by created_at)
    from staff_pending_bills where staff_id = u.staff_id and status = 'pending'), '[]'::jsonb);
end $$;

-- owner: all pending bills, with the manager's name, oldest first
create or replace function public.owner_staff_bills_list(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  return coalesce((select jsonb_agg(jsonb_build_object(
      'id', b.id, 'staff_id', b.staff_id, 'staff_name', coalesce(u.name, 'Manager'),
      'items', b.items, 'at', b.created_at) order by u.name, b.created_at)
    from staff_pending_bills b left join staff_app_users u on u.staff_id = b.staff_id
    where b.status = 'pending'), '[]'::jsonb);
end $$;

-- owner: mark a pending bill done once the owner has made the real POS bill from it
create or replace function public.owner_staff_bill_done(p_code text, p_id uuid, p_pos_bill_id text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  update staff_pending_bills set status = 'done', pos_bill_id = left(coalesce(p_pos_bill_id,''),80), updated_at = now()
   where id = p_id and status = 'pending';
  return jsonb_build_object('ok', true);
end $$;

-- permissions: staff_* need a manager login key; owner_* need the owner code
revoke all on function public._staff_clean_items(jsonb) from public, anon, authenticated;
revoke all on function public.staff_lookup(text,text,text) from public;
revoke all on function public.staff_bill_send(text,text,jsonb) from public;
revoke all on function public.staff_bill_update(text,text,uuid,jsonb) from public;
revoke all on function public.staff_bill_delete(text,text,uuid) from public;
revoke all on function public.staff_bills_list(text,text) from public;
revoke all on function public.owner_staff_bills_list(text) from public;
revoke all on function public.owner_staff_bill_done(text,uuid,text) from public;
grant execute on function public.staff_lookup(text,text,text) to anon, authenticated;
grant execute on function public.staff_bill_send(text,text,jsonb) to anon, authenticated;
grant execute on function public.staff_bill_update(text,text,uuid,jsonb) to anon, authenticated;
grant execute on function public.staff_bill_delete(text,text,uuid) to anon, authenticated;
grant execute on function public.staff_bills_list(text,text) to anon, authenticated;
grant execute on function public.owner_staff_bills_list(text) to anon, authenticated;
grant execute on function public.owner_staff_bill_done(text,uuid,text) to anon, authenticated;

select 'OK: update 17 is installed (Make Bill / Staff Bills).' as status;
