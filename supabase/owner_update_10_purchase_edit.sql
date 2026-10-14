-- =====================================================================
-- OWNER APP UPDATE 10: supplier purchase bills can be CHANGED and DELETED
--  - Change: amount, items, bill date and note. The bill number and the id stay the same.
--            Stock moves only by the difference (a bill that added items to stock).
--  - Delete: the bill is kept on the server with deleted = true. It is not counted or shown.
-- Run this ONCE in Supabase > SQL Editor, AFTER update 8.
-- It only ADDS a column and REPLACES functions. Nothing is deleted.
-- The last result line says "OK: update 10 is installed ...".
-- =====================================================================

-- STEP 1: deleted flag for a purchase bill (existing bills stay not deleted)
alter table public.owner_purchase_bills add column if not exists deleted boolean not null default false;

-- STEP 2: change or delete a purchase bill, with its stock changes, in one function.
-- Stock changes come as "stock": [{product_id, colour, size, delta, op_key, reason}]. Each op_key is applied once only.
create or replace function public.owner_save_purchase(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare rec record; warns jsonb := '[]'::jsonb; pid text := p->>'id';
begin
  perform _owner_ok(p_code);
  if coalesce(trim(pid),'') = '' or coalesce(trim(p->>'supplier_id'),'') = '' then
    raise exception 'INVALID_PURCHASE';
  end if;
  insert into owner_purchase_bills (id, supplier_id, bill_no, bill_date, amount, items, add_stock, note, deleted, created_at)
  values (pid, p->>'supplier_id', p->>'bill_no', coalesce((p->>'bill_date')::timestamptz, now()),
          coalesce((p->>'amount')::numeric, 0), coalesce(p->'items','[]'::jsonb),
          coalesce((p->>'add_stock')::boolean, false), nullif(p->>'note',''),
          coalesce((p->>'deleted')::boolean, false), now())
  on conflict (id) do update set
    bill_no   = excluded.bill_no,
    bill_date = excluded.bill_date,
    amount    = excluded.amount,
    items     = excluded.items,
    note      = excluded.note,
    deleted   = excluded.deleted;

  for rec in select value as it, ordinality as idx
               from jsonb_array_elements(coalesce(p->'stock','[]'::jsonb)) with ordinality loop
    begin
      perform _apply_delta(rec.it->>'product_id', rec.it->>'colour', rec.it->>'size',
                           (rec.it->>'delta')::int, rec.it->>'op_key',
                           coalesce(rec.it->>'reason', 'PURCHASE_EDIT'), pid);
    exception when others then
      warns := warns || jsonb_build_array(sqlerrm);
    end;
  end loop;
  return jsonb_build_object('ok', true, 'warnings', warns);
end $$;

-- STEP 3: the purchase bill list now also sends the deleted flag (the phone hides deleted bills)
create or replace function public.owner_list_purchases(p_code text) returns setof public.owner_purchase_bills
language plpgsql security definer set search_path = public as $$
begin perform _owner_ok(p_code); return query select * from owner_purchase_bills order by bill_date desc limit 5000; end $$;

-- STEP 4: only the owner functions are callable from the app
revoke all on function public.owner_save_purchase(text,jsonb)  from public;
revoke all on function public.owner_list_purchases(text)       from public;
grant execute on function public.owner_save_purchase(text,jsonb)  to anon, authenticated;
grant execute on function public.owner_list_purchases(text)       to anon, authenticated;

-- STEP 5: check (the last line says whether everything is in place)
select case
         when exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_purchase_bills' and column_name = 'deleted')
          and to_regprocedure('public.owner_save_purchase(text,jsonb)') is not null
         then 'OK: update 10 is installed (purchase bill change and delete).'
         else 'PROBLEM: update 10 is incomplete - send a screenshot of the error above.'
       end as status;
