-- =====================================================================
-- OWNER APP UPDATE 7: supplier payments - cheque number, on-account payments with a date
-- Run this ONCE in Supabase > SQL Editor, AFTER owner_update_2_migration.sql (update 2 is already installed).
-- It only ADDS a column and REPLACES owner_add_supplier_payment. It drops and deletes nothing.
-- Existing supplier payments are untouched (their cheque number stays empty).
-- The last result line says "OK: update 7 is installed ...".
-- =====================================================================

-- STEP 1: cheque number column (empty for cash / online / old payments)
alter table public.owner_supplier_payments add column if not exists cheque_no text;

-- STEP 2: save a supplier payment. Idempotent by id (a retry can never be saved twice).
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

revoke all on function public.owner_add_supplier_payment(text,jsonb) from public;
grant execute on function public.owner_add_supplier_payment(text,jsonb) to anon, authenticated;

-- STEP 3: check (the last line says whether everything is in place)
select case
         when exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_supplier_payments' and column_name = 'cheque_no')
          and to_regprocedure('public.owner_add_supplier_payment(text,jsonb)') is not null
         then 'OK: update 7 is installed (supplier payments with cheque number).'
         else 'PROBLEM: update 7 is incomplete - send a screenshot of the error above.'
       end as status;
