-- =====================================================================
-- OWNER APP UPDATE 3: supplier bill photo / PDF
-- Run this ONCE in Supabase > SQL Editor, after owner_update_2_migration.sql.
-- It only ADDS a column, a storage bucket and policies, and replaces
-- owner_create_purchase with the same signature (bill_file_url is stored too).
-- Nothing is dropped or deleted. Existing bills, stock and payments are untouched.
-- The last result line says "OK: update 3 is installed ...".
-- =====================================================================

-- STEP 1: the link to the supplier's bill photo / PDF on each purchase bill
alter table public.owner_purchase_bills add column if not exists bill_file_url text;

-- STEP 2: storage bucket for supplier bills (images and PDF, max 10 MB)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('supplier-bills', 'supplier-bills', true, 10485760,
        array['image/jpeg','image/png','image/webp','application/pdf'])
on conflict (id) do nothing;

drop policy if exists "owner app upload supplier bills" on storage.objects;
create policy "owner app upload supplier bills" on storage.objects
  for insert to anon, authenticated with check (bucket_id = 'supplier-bills');

drop policy if exists "public read supplier bills" on storage.objects;
create policy "public read supplier bills" on storage.objects
  for select to anon, authenticated using (bucket_id = 'supplier-bills');

-- STEP 3: purchase bill creation also stores the bill file URL (same signature, same checks)
create or replace function public.owner_create_purchase(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare rec record; warns jsonb := '[]'::jsonb; pid text := p->>'id'; addst boolean;
begin
  perform _owner_ok(p_code);
  if coalesce(trim(pid),'') = '' or coalesce(trim(p->>'supplier_id'),'') = '' then
    raise exception 'INVALID_PURCHASE';
  end if;
  addst := coalesce((p->>'add_stock')::boolean, true);
  insert into owner_purchase_bills (id, supplier_id, bill_no, bill_date, amount, items, add_stock, note, bill_file_url, created_at)
  values (pid, p->>'supplier_id', p->>'bill_no', coalesce((p->>'bill_date')::timestamptz, now()),
          coalesce((p->>'amount')::numeric, 0), coalesce(p->'items','[]'::jsonb), addst,
          nullif(p->>'note',''), nullif(p->>'bill_file_url',''), now())
  on conflict (id) do nothing;
  if not found then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;
  if addst then
    for rec in select value as it, ordinality as idx
                 from jsonb_array_elements(coalesce(p->'items','[]'::jsonb)) with ordinality loop
      begin
        perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size',
                             greatest((rec.it->>'qty')::int, 0), 'PUR:' || pid || ':' || rec.idx,
                             'PURCHASE', coalesce(p->>'bill_no', pid));
      exception when others then
        warns := warns || jsonb_build_array(sqlerrm);
      end;
    end loop;
  end if;
  return jsonb_build_object('ok', true, 'warnings', warns);
end $$;

-- STEP 4: check (the last line says whether everything is in place)
select case
         when exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_purchase_bills' and column_name = 'bill_file_url')
          and exists (select 1 from storage.buckets where id = 'supplier-bills')
         then 'OK: update 3 is installed (supplier bill photo / PDF).'
         else 'PROBLEM: update 3 is incomplete - send a screenshot of the error above.'
       end as status;
