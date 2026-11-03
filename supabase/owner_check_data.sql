-- Data check (read only, changes nothing). Run after owner_update_30_sold_barcodes.sql.

-- CHECK 1: one barcode on two or more shop bills (the same piece billed twice). Empty = OK.
select x.code as barcode, string_agg(x.bill_no::text, ', ' order by x.created_at) as bills, count(*) as times
  from (
    select b.bill_no, b.created_at, trim(c) as code
      from pos_bills b,
           jsonb_array_elements(case when jsonb_typeof(b.items) = 'array' then b.items else '[]'::jsonb end) it,
           unnest(string_to_array(coalesce(it->>'barcode', ''), ',')) c
     where not coalesce(b.deleted, false) and trim(c) <> ''
  ) x
 group by x.code
having count(*) > 1
 order by max(x.created_at) desc;

-- CHECK 2: managers' bills still waiting that hold a piece already sold. Empty = OK.
select sb.id, sb.created_at, it->>'article' as article, it->>'colour' as colour, it->>'size' as size,
       trim(c) as barcode, _barcode_sold_bill(trim(c)) as sold_on_bill
  from staff_pending_bills sb,
       jsonb_array_elements(case when jsonb_typeof(sb.items) = 'array' then sb.items else '[]'::jsonb end) it,
       unnest(string_to_array(coalesce(it->>'barcode', ''), ',')) c
 where sb.status = 'pending' and trim(c) <> '' and _barcode_sold_bill(trim(c)) is not null;
