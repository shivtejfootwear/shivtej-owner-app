-- =====================================================================
-- OWNER APP UPDATE 6: delete a POS bill ("डिलीट") with full reversal
-- Run this ONCE in Supabase > SQL Editor, AFTER owner_app_setup.sql and owner_update_4_media_ledger_tasks.sql.
-- It only ADDS columns and a function. It drops and deletes nothing.
-- Existing bills, stock, payments and customers are untouched.
-- The last result line says "OK: update 6 is installed ...".
-- =====================================================================

-- STEP 1: new columns (default = not deleted, so every existing row stays as it is)
alter table public.pos_bills                add column if not exists deleted    boolean not null default false;
alter table public.pos_bills                add column if not exists deleted_at timestamptz;
alter table public.owner_customer_payments  add column if not exists deleted    boolean not null default false;

-- STEP 2: delete a bill. Idempotent (a second call changes nothing).
-- 1) every item goes back into stock (same product, colour and size; the barcode stays on that size, so it comes back too)
-- 2) the customer payments made against this bill are reversed (marked deleted, not erased)
-- 3) the bill is marked deleted (not erased), so the history stays traceable
-- Stock lines that can not be restored (the product was removed) are returned in "warnings"; nothing else is skipped.
create or replace function public.owner_delete_bill(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  bid   text := p->>'id';
  b     pos_bills%rowtype;
  rec   record;
  warns jsonb := '[]'::jsonb;
  v_pay int := 0;
begin
  perform _owner_ok(p_code);
  if coalesce(trim(bid), '') = '' then raise exception 'INVALID_BILL'; end if;

  select * into b from pos_bills where id = bid for update;
  if not found then
    -- the bill never reached the server (its upload was refused): there is no stock change to reverse here
    return jsonb_build_object('ok', true, 'missing', true);
  end if;
  if b.deleted then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;

  for rec in select value as it, ordinality as idx
               from jsonb_array_elements(coalesce(b.items, '[]'::jsonb)) with ordinality loop
    begin
      perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size',
                           coalesce((rec.it->>'qty')::int, 0),
                           bid || '#del' || rec.idx, 'BILL_DELETE', b.bill_no);
    exception when others then
      warns := warns || jsonb_build_array(sqlerrm);
    end;
  end loop;

  update pos_bills set deleted = true, deleted_at = now() where id = bid;
  update owner_customer_payments set deleted = true where bill_id = bid and not deleted;
  get diagnostics v_pay = row_count;

  return jsonb_build_object('ok', true, 'warnings', warns, 'payments_reversed', v_pay);
end $$;

-- The lists already return whole rows (owner_list_bills and owner_list_customer_payments), so the new
-- "deleted" flag reaches every phone on the next sync. Nothing else needs to change.

revoke all on function public.owner_delete_bill(text, jsonb) from public;
grant execute on function public.owner_delete_bill(text, jsonb) to anon, authenticated;

-- STEP 3: check (the last line says whether everything is in place)
select case
         when exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'pos_bills' and column_name = 'deleted')
          and exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_customer_payments' and column_name = 'deleted')
          and to_regprocedure('public.owner_delete_bill(text,jsonb)') is not null
         then 'OK: update 6 is installed (bill delete with stock and payment reversal).'
         else 'PROBLEM: update 6 is incomplete - send a screenshot of the error above.'
       end as status;
