-- =====================================================================
-- OWNER APP UPDATE 8
--  A) Supplier payments: change, delete (kept, but not counted), Return / Claim with the goods sent back (stock)
--  B) Shop and bill details saved on the server (restored on a new phone, sent once a day and after each app update)
-- Run this ONCE in Supabase > SQL Editor.
-- It includes update 7 (cheque number), so update 7 does not need to be run separately.
-- It only ADDS columns and one table and REPLACES functions. Nothing is deleted.
-- The last result line says "OK: update 8 is installed ...".
-- =====================================================================

-- STEP 1: supplier payments - cheque number (update 7), deleted flag, goods sent back
alter table public.owner_supplier_payments add column if not exists cheque_no text;
alter table public.owner_supplier_payments add column if not exists deleted    boolean not null default false;
alter table public.owner_supplier_payments add column if not exists items      jsonb   not null default '[]'::jsonb;

-- STEP 2: save a supplier payment (update 7 version, kept for payments that were queued on the phone before this update)
create or replace function public.owner_add_supplier_payment(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if coalesce(trim(p->>'id'),'') = '' or coalesce(trim(p->>'supplier_id'),'') = '' or coalesce((p->>'amount')::numeric, 0) <= 0 then
    raise exception 'INVALID_PAYMENT';
  end if;
  insert into owner_supplier_payments (id, supplier_id, bill_id, amount, method, note, cheque_no, paid_at, created_at)
  values (p->>'id', p->>'supplier_id', nullif(p->>'bill_id',''), (p->>'amount')::numeric,
          nullif(p->>'method',''), nullif(p->>'note',''), nullif(p->>'cheque_no',''),
          coalesce((p->>'paid_at')::timestamptz, now()), now())
  on conflict (id) do nothing;
  if not found then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;
  return jsonb_build_object('ok', true);
end $$;

-- STEP 3: add, change, delete, or record a Return / Claim (with stock) in one function.
-- The payment row is saved as the phone sends it (same id = same row, so it can never be saved twice).
-- Stock changes come as "stock": [{product_id, colour, size, delta, op_key, reason}]. Each op_key is applied once only.
create or replace function public.owner_save_supplier_payment(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare rec record; warns jsonb := '[]'::jsonb; pid text := p->>'id';
begin
  perform _owner_ok(p_code);
  if coalesce(trim(pid),'') = '' or coalesce(trim(p->>'supplier_id'),'') = '' or coalesce((p->>'amount')::numeric, 0) <= 0 then
    raise exception 'INVALID_PAYMENT';
  end if;
  insert into owner_supplier_payments (id, supplier_id, bill_id, amount, method, note, cheque_no, paid_at, deleted, items, created_at)
  values (pid, p->>'supplier_id', null, (p->>'amount')::numeric, nullif(p->>'method',''), nullif(p->>'note',''),
          nullif(p->>'cheque_no',''), coalesce((p->>'paid_at')::timestamptz, now()),
          coalesce((p->>'deleted')::boolean, false), coalesce(p->'items','[]'::jsonb), now())
  on conflict (id) do update set
    amount   = excluded.amount,
    method   = excluded.method,
    note     = excluded.note,
    cheque_no = excluded.cheque_no,
    paid_at  = excluded.paid_at,
    deleted  = excluded.deleted,
    items    = excluded.items;

  for rec in select value as it, ordinality as idx
               from jsonb_array_elements(coalesce(p->'stock','[]'::jsonb)) with ordinality loop
    begin
      perform _apply_delta(rec.it->>'product_id', rec.it->>'colour', rec.it->>'size',
                           (rec.it->>'delta')::int, rec.it->>'op_key',
                           coalesce(rec.it->>'reason', 'SUPPLIER_RETURN'), pid);
    exception when others then
      warns := warns || jsonb_build_array(sqlerrm);
    end;
  end loop;
  return jsonb_build_object('ok', true, 'warnings', warns);
end $$;

-- STEP 4: shop and bill details (one row, id 'main'). Only the owner can read or write it.
create table if not exists public.owner_shop_settings (
  id          text primary key default 'main',
  data        jsonb not null default '{}'::jsonb,
  updated_at  timestamptz not null default now()
);
alter table public.owner_shop_settings enable row level security;

create or replace function public.owner_get_shop_settings(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare r public.owner_shop_settings%rowtype;
begin
  perform _owner_ok(p_code);
  select * into r from public.owner_shop_settings where id = 'main';
  if not found then
    return jsonb_build_object('exists', false);
  end if;
  return jsonb_build_object('exists', true, 'data', r.data,
                            'updated_at_ms', floor(extract(epoch from r.updated_at) * 1000)::bigint);
end $$;

create or replace function public.owner_save_shop_settings(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if p is null or jsonb_typeof(p) <> 'object' then
    raise exception 'INVALID_SHOP';
  end if;
  insert into public.owner_shop_settings (id, data, updated_at) values ('main', p, now())
  on conflict (id) do update set data = excluded.data, updated_at = excluded.updated_at;
  return jsonb_build_object('ok', true);
end $$;

-- STEP 5: only the owner functions are callable from the app
revoke all on function public.owner_add_supplier_payment(text,jsonb)  from public;
revoke all on function public.owner_save_supplier_payment(text,jsonb) from public;
revoke all on function public.owner_get_shop_settings(text)           from public;
revoke all on function public.owner_save_shop_settings(text,jsonb)    from public;
grant execute on function public.owner_add_supplier_payment(text,jsonb)  to anon, authenticated;
grant execute on function public.owner_save_supplier_payment(text,jsonb) to anon, authenticated;
grant execute on function public.owner_get_shop_settings(text)           to anon, authenticated;
grant execute on function public.owner_save_shop_settings(text,jsonb)    to anon, authenticated;

-- STEP 6: check (the last line says whether everything is in place)
select case
         when exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_supplier_payments' and column_name = 'cheque_no')
          and exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_supplier_payments' and column_name = 'deleted')
          and exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_supplier_payments' and column_name = 'items')
          and to_regclass('public.owner_shop_settings') is not null
          and to_regprocedure('public.owner_save_supplier_payment(text,jsonb)') is not null
          and to_regprocedure('public.owner_save_shop_settings(text,jsonb)') is not null
          and to_regprocedure('public.owner_get_shop_settings(text)') is not null
         then 'OK: update 8 is installed (supplier payment changes and returns, shop details on the server).'
         else 'PROBLEM: update 8 is incomplete - send a screenshot of the error above.'
       end as status;
