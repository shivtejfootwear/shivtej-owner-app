-- =====================================================================
-- OWNER APP UPDATE 6b: restore a deleted POS bill ("रिस्टोर") + safe repeated delete / restore
-- Run this ONCE in Supabase > SQL Editor, AFTER owner_update_6_bill_delete.sql (update 6 is already installed).
-- It only REPLACES owner_delete_bill and ADDS owner_restore_bill. It drops and deletes nothing.
-- Nothing is ever erased: a deleted bill keeps its row, with deleted = true.
-- The last result line says "OK: update 6b is installed ...".
-- =====================================================================

-- STEP 1: delete a bill (replaces the version from update 6). Idempotent.
-- Every cycle (delete -> restore -> delete ...) has its own stock-log id, so each one really changes stock.
create or replace function public.owner_delete_bill(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  bid   text := p->>'id';
  cyc   text := coalesce(p->>'cycle', '0');
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
                           bid || '#del' || rec.idx || ':' || cyc, 'BILL_DELETE', b.bill_no);
    exception when others then
      warns := warns || jsonb_build_array(sqlerrm);
    end;
  end loop;

  update pos_bills set deleted = true, deleted_at = now() where id = bid;
  update owner_customer_payments set deleted = true where bill_id = bid and not deleted;
  get diagnostics v_pay = row_count;

  return jsonb_build_object('ok', true, 'warnings', warns, 'payments_reversed', v_pay);
end $$;

-- STEP 2: restore a deleted bill. All or nothing: if any line does not fit in stock, nothing changes.
create or replace function public.owner_restore_bill(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  bid   text := p->>'id';
  cyc   text := coalesce(p->>'cycle', '0');
  b     pos_bills%rowtype;
  rec   record;
  need  int;
  have  int;
  v_pay int := 0;
begin
  perform _owner_ok(p_code);
  if coalesce(trim(bid), '') = '' then raise exception 'INVALID_BILL'; end if;

  select * into b from pos_bills where id = bid for update;
  if not found then
    return jsonb_build_object('ok', true, 'missing', true);
  end if;
  if not b.deleted then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;

  -- 1) check every line first
  for rec in select value as it from jsonb_array_elements(coalesce(b.items, '[]'::jsonb)) loop
    need := coalesce((rec.it->>'qty')::int, 0);
    select coalesce((s->>'qty')::int, 0) into have
      from products pr,
           jsonb_array_elements(coalesce(pr.colours, '[]'::jsonb)) c,
           jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) s
     where pr.id = rec.it->>'productId'
       and lower(coalesce(c->>'name', c->>'colour')) = lower(rec.it->>'colour')
       and s->>'size' = rec.it->>'size'
     limit 1;
    if have is null then
      raise exception 'STOCK_LINE_MISSING: %', coalesce(rec.it->>'article', rec.it->>'productId');
    end if;
    if have < need then
      raise exception 'STOCK_SHORT: % has % left, % needed', coalesce(rec.it->>'article', rec.it->>'productId'), have, need;
    end if;
  end loop;

  -- 2) take the items out of stock again (the barcode stays on the size, as before)
  for rec in select value as it, ordinality as idx
               from jsonb_array_elements(coalesce(b.items, '[]'::jsonb)) with ordinality loop
    perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size',
                         -coalesce((rec.it->>'qty')::int, 0),
                         bid || '#res' || rec.idx || ':' || cyc, 'BILL_RESTORE', b.bill_no);
  end loop;

  -- 3) the bill and its payments count again
  update pos_bills set deleted = false, deleted_at = null where id = bid;
  update owner_customer_payments set deleted = false where bill_id = bid and deleted;
  get diagnostics v_pay = row_count;

  return jsonb_build_object('ok', true, 'payments_restored', v_pay);
end $$;

revoke all on function public.owner_delete_bill(text, jsonb)  from public;
revoke all on function public.owner_restore_bill(text, jsonb) from public;
grant execute on function public.owner_delete_bill(text, jsonb)  to anon, authenticated;
grant execute on function public.owner_restore_bill(text, jsonb) to anon, authenticated;

-- STEP 3: check (the last line says whether everything is in place)
select case
         when to_regprocedure('public.owner_restore_bill(text,jsonb)') is not null
          and to_regprocedure('public.owner_delete_bill(text,jsonb)') is not null
         then 'OK: update 6b is installed (bill restore, repeated delete and restore).'
         else 'PROBLEM: update 6b is incomplete - send a screenshot of the error above.'
       end as status;
