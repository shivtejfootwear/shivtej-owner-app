-- Owner update: supplier brands and margin (run once in the Supabase SQL Editor)
-- Adds a brands list (brand + margin %) to each supplier and saves it from the owner app.

alter table public.owner_suppliers add column if not exists brands jsonb not null default '[]'::jsonb;

create or replace function public.owner_upsert_supplier(p_code text, p jsonb) returns text
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if coalesce(trim(p->>'id'),'') = '' or coalesce(trim(p->>'name'),'') = '' then
    raise exception 'INVALID_SUPPLIER';
  end if;
  insert into owner_suppliers (id, name, mobile, address, notes, active, created_at, updated_at, brands)
  values (p->>'id', trim(p->>'name'), nullif(trim(coalesce(p->>'mobile','')),''),
          nullif(trim(coalesce(p->>'address','')),''), nullif(trim(coalesce(p->>'notes','')),''),
          coalesce((p->>'active')::boolean, true), coalesce((p->>'created_at')::timestamptz, now()), now(),
          coalesce(p->'brands', '[]'::jsonb))
  on conflict (id) do update
     set name = excluded.name, mobile = excluded.mobile, address = excluded.address,
         notes = excluded.notes, active = excluded.active, brands = excluded.brands, updated_at = now();
  return p->>'id';
end $$;

-- Check: the last line must say OK.
select case when exists (select 1 from information_schema.columns
         where table_schema = 'public' and table_name = 'owner_suppliers' and column_name = 'brands')
       then 'OK' else 'MISSING brands column' end as result;
