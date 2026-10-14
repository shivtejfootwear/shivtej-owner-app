-- =====================================================================
-- OWNER APP UPDATE 9: a separate ONLINE (website) contact number for each customer
--  - offline (shop) number = the customer's mobile, as before
--  - online number = the new column online_contact (may be the same number, or empty)
-- Run this ONCE in Supabase > SQL Editor, AFTER update 8.
-- It only ADDS a column and REPLACES owner_upsert_customer. Nothing is deleted, and existing customers keep their data.
-- The last result line says "OK: update 9 is installed ...".
-- =====================================================================

-- STEP 1: the online contact column (empty for customers saved before this update)
alter table public.owner_customers add column if not exists online_contact text;
-- (address and created_at are already there from update 2; these lines only make sure they exist)
alter table public.owner_customers add column if not exists address text;
alter table public.owner_customers add column if not exists created_at timestamptz default now();

-- STEP 2: save a customer. name / address work exactly as before.
-- online_contact is changed only when the app sends the key, so older app versions never erase it.
create or replace function public.owner_upsert_customer(p_code text, p jsonb) returns text
language plpgsql security definer set search_path = public as $$
declare m text := regexp_replace(coalesce(p->>'mobile',''), '\D', '', 'g');
begin
  perform _owner_ok(p_code);
  if length(m) > 10 then m := right(m, 10); end if;
  if length(m) <> 10 then raise exception 'INVALID_MOBILE'; end if;
  insert into owner_customers (mobile, name, address, online_contact, created_at, updated_at)
  values (m, nullif(trim(coalesce(p->>'name','')),''), nullif(trim(coalesce(p->>'address','')),''),
          nullif(right(regexp_replace(coalesce(p->>'online_contact',''), '\D', '', 'g'), 10), ''),
          coalesce((p->>'created_at')::timestamptz, now()), now())
  on conflict (mobile) do update set
     name    = case when coalesce((p->>'force')::boolean, false) then excluded.name
                    else coalesce(excluded.name, owner_customers.name) end,
     address = case when coalesce((p->>'force')::boolean, false) then excluded.address
                    else coalesce(excluded.address, owner_customers.address) end,
     online_contact = case when p ? 'online_contact' then excluded.online_contact
                           else owner_customers.online_contact end,
     updated_at = now();
  return m;
end $$;

-- STEP 3: check (the last line says whether everything is in place)
select case
         when exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'owner_customers' and column_name = 'online_contact')
          and to_regprocedure('public.owner_upsert_customer(text,jsonb)') is not null
         then 'OK: update 9 is installed (online contact number for each customer).'
         else 'PROBLEM: update 9 is incomplete - send a screenshot of the error above.'
       end as status;
