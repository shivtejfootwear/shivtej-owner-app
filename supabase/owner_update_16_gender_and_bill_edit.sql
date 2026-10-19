-- Shivtej Footwear - update 16: customer gender (male / female) + editing a POS bill in place.
-- Run ONCE in Supabase > SQL Editor, AFTER update 9. Safe to run again. Nothing is deleted.
--
-- 1) owner_customers gets a "gender" column ('M' / 'F' / NULL). owner_upsert_customer carries it.
--    (owner_list_pos_customers is "select *", so the app receives gender automatically.)
-- 2) owner_update_bill edits a saved bill KEEPING its number: old items go back into stock, the bill row is
--    rewritten, and the new items leave stock. Idempotent by a per-edit "cycle" token, so a re-sync is safe.

-- ---------- 1) gender on the customer ----------
alter table public.owner_customers add column if not exists gender text;

create or replace function public.owner_upsert_customer(p_code text, p jsonb) returns text
language plpgsql security definer set search_path = public as $$
declare m text := regexp_replace(coalesce(p->>'mobile',''), '\D', '', 'g');
        g text := upper(nullif(trim(coalesce(p->>'gender','')), ''));
begin
  perform _owner_ok(p_code);
  if length(m) > 10 then m := right(m, 10); end if;
  if length(m) <> 10 then raise exception 'INVALID_MOBILE'; end if;
  if g is not null and g not in ('M','F') then g := null; end if;
  insert into owner_customers (mobile, name, address, online_contact, gender, created_at, updated_at)
  values (m, nullif(trim(coalesce(p->>'name','')),''), nullif(trim(coalesce(p->>'address','')),''),
          nullif(right(regexp_replace(coalesce(p->>'online_contact',''), '\D', '', 'g'), 10), ''),
          g, coalesce((p->>'created_at')::timestamptz, now()), now())
  on conflict (mobile) do update set
     name    = case when coalesce((p->>'force')::boolean, false) then excluded.name
                    else coalesce(excluded.name, owner_customers.name) end,
     address = case when coalesce((p->>'force')::boolean, false) then excluded.address
                    else coalesce(excluded.address, owner_customers.address) end,
     online_contact = case when p ? 'online_contact' then excluded.online_contact
                           else owner_customers.online_contact end,
     -- gender is set when the app sends it; an already-set gender is kept unless 'force' is true
     gender = case when not (p ? 'gender') then owner_customers.gender
                   when coalesce((p->>'force')::boolean, false) then excluded.gender
                   else coalesce(owner_customers.gender, excluded.gender) end,
     updated_at = now();
  return m;
end $$;

-- ---------- 2) edit a bill in place ----------
create or replace function public.owner_update_bill(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare rec record; warns jsonb := '[]'::jsonb; bid text := p->>'id'; cyc text := coalesce(p->>'cycle','0');
        existing pos_bills%rowtype;
begin
  perform _owner_ok(p_code);
  -- this exact edit (cycle) already ran? do nothing (a re-sync must not move stock again)
  if exists (select 1 from stock_log where op_id like bid || '#ev' || cyc || '#%') then
    return jsonb_build_object('ok', true, 'duplicate', true);
  end if;
  select * into existing from pos_bills where id = bid for update;

  if found then
    -- put the OLD items back into stock (unique op id per edit, so a re-sync does nothing)
    for rec in select value as it, ordinality as idx
                 from jsonb_array_elements(coalesce(existing.items,'[]'::jsonb)) with ordinality loop
      begin
        perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size',
                             ((rec.it->>'qty')::int), bid || '#eu' || cyc || '#' || rec.idx, 'BILL_EDIT_UNDO', existing.bill_no);
      exception when others then warns := warns || jsonb_build_array('undo: ' || sqlerrm); end;
    end loop;
    update pos_bills set items = p->'items', total = (p->>'total')::numeric,
                         payment_method = p->>'payment_method'
     where id = bid;
  else
    -- the create never reached the server: insert the bill now
    insert into pos_bills (id, bill_no, total, payment_method, items, created_at)
    values (bid, p->>'bill_no', (p->>'total')::numeric, p->>'payment_method',
            p->'items', coalesce((p->>'created_at')::timestamptz, now()));
  end if;

  -- take the NEW items out of stock
  for rec in select value as it, ordinality as idx
               from jsonb_array_elements(coalesce(p->'items','[]'::jsonb)) with ordinality loop
    begin
      perform _apply_delta(rec.it->>'productId', rec.it->>'colour', rec.it->>'size',
                           -((rec.it->>'qty')::int), bid || '#ev' || cyc || '#' || rec.idx, 'POS_SALE', p->>'bill_no');
    exception when others then warns := warns || jsonb_build_array('apply: ' || sqlerrm); end;
  end loop;
  return jsonb_build_object('ok', true, 'warnings', warns);
end $$;

revoke all on function public.owner_upsert_customer(text,jsonb) from public;
revoke all on function public.owner_update_bill(text,jsonb)    from public;
grant execute on function public.owner_upsert_customer(text,jsonb) to anon, authenticated;
grant execute on function public.owner_update_bill(text,jsonb)     to anon, authenticated;

select case
         when exists (select 1 from information_schema.columns
                       where table_schema='public' and table_name='owner_customers' and column_name='gender')
          and to_regprocedure('public.owner_update_bill(text,jsonb)') is not null
         then 'OK: update 16 is installed (customer gender + edit bill).'
         else 'PROBLEM: update 16 is incomplete - send a screenshot of the error above.'
       end as status;
