-- =====================================================================
-- OWNER APP UPDATE 21: custom size groups and extra size groups saved on the server
--  "+ Custom" groups (e.g. Ladies 5-9) and "+ Extra size" groups (Small size / Big size, each with its MRP)
--  are kept on the phone and sent here, so a new phone gets them back.
-- Run this ONCE in Supabase > SQL Editor.
-- It only ADDS one table and two functions. Nothing existing is changed.
-- The per-size MRP itself needs no SQL: it travels inside the colours JSON of owner_upsert_product.
-- The last result line says "OK: update 21 is installed ...".
-- =====================================================================

-- STEP 1: one row (id 'main') holds the whole list of size groups. Only the owner functions can read or write it.
create table if not exists public.owner_size_presets (
  id          text primary key default 'main',
  data        jsonb not null default '{}'::jsonb,
  updated_at  timestamptz not null default now()
);
alter table public.owner_size_presets enable row level security;

-- STEP 2: read the list (with the time it was saved, so a phone only takes it when the server copy is newer)
create or replace function public.owner_get_size_presets(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare r public.owner_size_presets%rowtype;
begin
  perform _owner_ok(p_code);
  select * into r from public.owner_size_presets where id = 'main';
  if not found then
    return jsonb_build_object('exists', false);
  end if;
  return jsonb_build_object('exists', true, 'data', r.data,
                            'updated_at_ms', floor(extract(epoch from r.updated_at) * 1000)::bigint);
end $$;

-- STEP 3: save the whole list (every save sends the full list, so the last one wins)
create or replace function public.owner_save_size_presets(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if p is null or jsonb_typeof(p) <> 'object' then
    raise exception 'INVALID_SIZE_PRESETS';
  end if;
  insert into public.owner_size_presets (id, data, updated_at) values ('main', p, now())
  on conflict (id) do update set data = excluded.data, updated_at = excluded.updated_at;
  return jsonb_build_object('ok', true);
end $$;

-- STEP 4: only the owner functions are callable from the app
revoke all on function public.owner_get_size_presets(text)        from public;
revoke all on function public.owner_save_size_presets(text,jsonb) from public;
grant execute on function public.owner_get_size_presets(text)        to anon, authenticated;
grant execute on function public.owner_save_size_presets(text,jsonb) to anon, authenticated;

-- STEP 5: check (the last line says whether everything is in place)
select case
         when to_regclass('public.owner_size_presets') is not null
          and to_regprocedure('public.owner_get_size_presets(text)') is not null
          and to_regprocedure('public.owner_save_size_presets(text,jsonb)') is not null
         then 'OK: update 21 is installed (custom and extra size groups on the server).'
         else 'PROBLEM: update 21 is incomplete - send a screenshot of the error above.'
       end as status;
