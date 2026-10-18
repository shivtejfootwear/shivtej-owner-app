-- Shivtej Footwear - update 15: the "Shivtej Staff" app (catalog for staff + staff location in shop hours).
-- Run ONCE in Supabase > SQL Editor (safe to run again).
--
-- How access works
--   * The owner (owner app > Staff > Staff app) makes a 6-digit code for one staff member. The code works once,
--     for 24 hours, on one phone. 5 wrong tries and the code stops working (make a new one).
--   * The staff logs in with their mobile number + that code. Their phone gets a secret login key (only its hash is saved).
--   * Block = the login key is deleted at once; the staff app logs out and wipes the catalog the next time it checks
--     (on opening and every few minutes). Making a new code also logs the old phone out.
-- Location
--   * Saved only inside shop hours (set by the owner, India time). Outside shop hours nothing is saved,
--     even if a phone sends it. Points older than 60 days are deleted.
--   * How often: chosen per staff in the owner app - Live (every 15 seconds), 1/5/10/30 minutes, 1 or 2 hours.
--     Default 10 minutes. A change reaches the staff phone within about 2 minutes.
-- (This file replaces the first version of update 15 - running it again keeps all data.)
-- Catalog
--   * Same products as the website (switched on, some stock). Only the MRP is sent - never the selling price.

-- 1) Tables (no public access: only the functions below can read or write them)
create table if not exists public.staff_app_users (
  staff_id      text primary key,          -- the id from owner_staff (owner app Staff list)
  name          text not null,
  mobile        text not null,
  active        boolean not null default true,
  code_hash     text,
  code_expires  timestamptz,
  code_tries    int not null default 0,
  device_id     text,
  device_name   text,
  last_seen     timestamptz,
  last_lat      double precision,
  last_lng      double precision,
  last_acc      real,
  last_battery  int,
  last_status   text,
  last_at       timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
alter table public.staff_app_users enable row level security;

create table if not exists public.staff_app_sessions (
  token_hash  text primary key,
  staff_id    text not null,
  device_id   text not null,
  created_at  timestamptz not null default now()
);
alter table public.staff_app_sessions enable row level security;
create index if not exists staff_app_sessions_staff on public.staff_app_sessions (staff_id);

create table if not exists public.staff_locations (
  id        bigserial primary key,
  staff_id  text not null,
  lat       double precision,
  lng       double precision,
  acc       real,
  battery   int,
  status    text not null default 'ok',     -- ok | gps_off | no_permission
  at        timestamptz not null default now()
);
alter table public.staff_locations enable row level security;
create index if not exists staff_locations_staff_at on public.staff_locations (staff_id, at desc);

create table if not exists public.staff_app_settings (
  id            int primary key default 1 check (id = 1),
  start_min     int not null default 540,    -- 09:00 (minutes after midnight, India time)
  end_min       int not null default 1320,   -- 22:00
  interval_min  int not null default 10,
  updated_at    timestamptz not null default now()
);
alter table public.staff_app_settings enable row level security;
insert into public.staff_app_settings (id) values (1) on conflict (id) do nothing;

-- how often the location is sent, in seconds (per staff; empty = the default in staff_app_settings)
alter table public.staff_app_users add column if not exists interval_sec int;
alter table public.staff_app_settings add column if not exists interval_sec int not null default 600;

revoke all on public.staff_app_users, public.staff_app_sessions, public.staff_locations, public.staff_app_settings
  from anon, authenticated;

-- 2) Helpers
create or replace function public._staff_hash(t text) returns text
language sql immutable as $$ select encode(sha256(convert_to(t, 'UTF8')), 'hex') $$;

create or replace function public._staff_hours() returns jsonb
language sql stable security definer set search_path = public as $$
  select jsonb_build_object('start_min', start_min, 'end_min', end_min, 'interval_min', interval_min,
                            'interval_sec', interval_sec)
    from staff_app_settings where id = 1
$$;

-- shop hours + this staff member's own interval (sent to the staff app with every answer)
create or replace function public._staff_conf(p_staff_id text) returns jsonb
language sql stable security definer set search_path = public as $$
  select jsonb_build_object('start_min', s.start_min, 'end_min', s.end_min,
           'interval_sec', coalesce(u.interval_sec, s.interval_sec),
           'interval_min', greatest(1, coalesce(u.interval_sec, s.interval_sec) / 60))
    from staff_app_settings s left join staff_app_users u on u.staff_id = p_staff_id
   where s.id = 1
$$;

-- true when India time now is inside shop hours
create or replace function public._staff_in_hours() returns boolean
language sql stable security definer set search_path = public as $$
  select case when s.start_min <= s.end_min then m between s.start_min and s.end_min
              else m >= s.start_min or m <= s.end_min end
    from staff_app_settings s,
         lateral (select (extract(hour from now() at time zone 'Asia/Kolkata') * 60
                        + extract(minute from now() at time zone 'Asia/Kolkata'))::int as m) x
   where s.id = 1
$$;

-- The staff row for a login key on this phone, or NOT_ALLOWED (blocked, logged out, other phone).
create or replace function public._staff_session(p_token text, p_device text) returns public.staff_app_users
language plpgsql security definer set search_path = public as $$
declare s staff_app_sessions%rowtype; u staff_app_users%rowtype;
begin
  if coalesce(p_token, '') !~ '^[a-f0-9]{64}$' then raise exception 'NOT_ALLOWED'; end if;
  select * into s from staff_app_sessions where token_hash = _staff_hash(p_token);
  if not found or s.device_id is distinct from p_device then raise exception 'NOT_ALLOWED'; end if;
  select * into u from staff_app_users where staff_id = s.staff_id;
  if not found or not u.active or u.device_id is distinct from p_device then raise exception 'NOT_ALLOWED'; end if;
  update staff_app_users set last_seen = now() where staff_id = u.staff_id;
  return u;
end $$;

-- 3) Owner functions (owner app, with the owner code)
create or replace function public.owner_staff_app_list(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  return jsonb_build_object(
    'hours', _staff_hours(),
    'staff', coalesce((select jsonb_agg(jsonb_build_object(
        'staff_id', staff_id, 'name', name, 'mobile', mobile, 'active', active,
        'device_name', device_name, 'has_device', device_id is not null and active,
        'code_pending', code_hash is not null and code_expires > now(), 'code_expires', code_expires,
        'last_seen', last_seen, 'lat', last_lat, 'lng', last_lng, 'acc', last_acc,
        'battery', last_battery, 'status', last_status, 'at', last_at,
        'interval_sec', coalesce(interval_sec, (select interval_sec from staff_app_settings where id = 1))) order by name)
      from staff_app_users), '[]'::jsonb));
end $$;

-- New 6-digit code for one staff member (also logs out the old phone). Returns the code ONCE.
create or replace function public.owner_staff_app_code(p_code text, p_staff_id text, p_name text, p_mobile text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_mobile text := right(regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g'), 10); v_code text;
begin
  perform _owner_ok(p_code);
  if coalesce(trim(p_staff_id), '') = '' or coalesce(trim(p_name), '') = '' then raise exception 'BAD_STAFF'; end if;
  if v_mobile !~ '^[6-9]\d{9}$' then raise exception 'BAD_MOBILE'; end if;
  v_code := lpad((('x' || lpad(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12), 16, '0'))::bit(64)::bigint % 1000000)::text, 6, '0');
  insert into staff_app_users (staff_id, name, mobile, active, code_hash, code_expires, code_tries, device_id, device_name, updated_at)
  values (p_staff_id, left(trim(p_name), 80), v_mobile, true, _staff_hash(v_code || ':' || p_staff_id),
          now() + interval '24 hours', 0, null, null, now())
  on conflict (staff_id) do update set name = excluded.name, mobile = excluded.mobile, active = true,
    code_hash = excluded.code_hash, code_expires = excluded.code_expires, code_tries = 0,
    device_id = null, device_name = null, updated_at = now();
  delete from staff_app_sessions where staff_id = p_staff_id;
  return jsonb_build_object('code', v_code, 'expires', now() + interval '24 hours');
end $$;

-- Block: logs the phone out at once and cancels any code. A new code gives access again.
create or replace function public.owner_staff_app_block(p_code text, p_staff_id text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  update staff_app_users set active = false, code_hash = null, code_expires = null, device_id = null,
         device_name = null, updated_at = now() where staff_id = p_staff_id;
  delete from staff_app_sessions where staff_id = p_staff_id;
end $$;

-- How often one staff member's location is sent: 15 (Live), 60, 300, 600, 1800, 3600 or 7200 seconds.
create or replace function public.owner_staff_app_interval(p_code text, p_staff_id text, p_sec int) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if p_sec is null or p_sec not in (15, 60, 300, 600, 1800, 3600, 7200) then raise exception 'BAD_INTERVAL'; end if;
  update staff_app_users set interval_sec = p_sec, updated_at = now() where staff_id = p_staff_id;
  if not found then raise exception 'NO_ACCESS'; end if;
end $$;

-- Location points of one staff member on one day (India date), oldest first.
create or replace function public.owner_staff_app_trail(p_code text, p_staff_id text, p_day date) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  return coalesce((select jsonb_agg(jsonb_build_object('lat', lat, 'lng', lng, 'acc', acc, 'battery', battery,
                                                       'status', status, 'at', at) order by at)
    from staff_locations
   where staff_id = p_staff_id
     and (at at time zone 'Asia/Kolkata')::date = p_day), '[]'::jsonb);
end $$;

create or replace function public.owner_staff_app_hours(p_code text, p_start_min int, p_end_min int) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if p_start_min is null or p_end_min is null or p_start_min not between 0 and 1439
     or p_end_min not between 0 and 1439 or p_start_min = p_end_min then raise exception 'BAD_HOURS'; end if;
  update staff_app_settings set start_min = p_start_min, end_min = p_end_min, updated_at = now() where id = 1;
end $$;

-- 4) Staff app functions
create or replace function public.staff_login(p_mobile text, p_code text, p_device text, p_device_name text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_mobile text := right(regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g'), 10);
        u staff_app_users%rowtype; v_token text; v_any boolean := false;
begin
  if coalesce(p_device, '') !~ '^[A-Za-z0-9-]{8,64}$' then raise exception 'BAD_DEVICE'; end if;
  -- wrong / missing code is RETURNED (not raised), so the wrong-try count is kept (an error would undo it)
  if coalesce(p_code, '') !~ '^\d{6}$' then return jsonb_build_object('error', 'WRONG_CODE'); end if;
  for u in select * from staff_app_users
            where mobile = v_mobile and active and code_hash is not null and code_expires > now() for update loop
    v_any := true;
    if u.code_hash = _staff_hash(p_code || ':' || u.staff_id) then
      v_token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
      delete from staff_app_sessions where staff_id = u.staff_id;
      insert into staff_app_sessions (token_hash, staff_id, device_id) values (_staff_hash(v_token), u.staff_id, p_device);
      update staff_app_users set code_hash = null, code_expires = null, code_tries = 0, device_id = p_device,
             device_name = left(coalesce(p_device_name, ''), 80), last_seen = now(), updated_at = now()
       where staff_id = u.staff_id;
      return jsonb_build_object('token', v_token, 'name', u.name, 'hours', _staff_conf(u.staff_id));
    end if;
    update staff_app_users set code_tries = code_tries + 1,
           code_hash = case when code_tries + 1 >= 5 then null else code_hash end
     where staff_id = u.staff_id;
  end loop;
  if not v_any then return jsonb_build_object('error', 'NO_CODE'); end if;
  return jsonb_build_object('error', 'WRONG_CODE');
end $$;

create or replace function public.staff_check(p_token text, p_device text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype;
begin
  u := _staff_session(p_token, p_device);
  return jsonb_build_object('ok', true, 'name', u.name, 'hours', _staff_conf(u.staff_id));
end $$;

create or replace function public.staff_location(p_token text, p_device text, p_lat double precision,
  p_lng double precision, p_acc real, p_battery int, p_status text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u staff_app_users%rowtype; v_status text := coalesce(nullif(p_status, ''), 'ok');
begin
  u := _staff_session(p_token, p_device);
  if v_status not in ('ok', 'gps_off', 'no_permission') then v_status := 'ok'; end if;
  if not _staff_in_hours() then
    return jsonb_build_object('ok', true, 'saved', false, 'hours', _staff_conf(u.staff_id));
  end if;
  if v_status = 'ok' and (p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180) then
    raise exception 'BAD_LOCATION';
  end if;
  -- not more often than half the chosen interval (Live = every 15 seconds -> at most one point per 7.5 seconds)
  if exists (select 1 from staff_locations where staff_id = u.staff_id
               and at > now() - make_interval(secs => greatest(
                     coalesce(u.interval_sec, (select interval_sec from staff_app_settings where id = 1)) * 0.5, 5))) then
    return jsonb_build_object('ok', true, 'saved', false, 'hours', _staff_conf(u.staff_id));
  end if;
  insert into staff_locations (staff_id, lat, lng, acc, battery, status)
  values (u.staff_id, case when v_status = 'ok' then p_lat end, case when v_status = 'ok' then p_lng end,
          p_acc, least(greatest(p_battery, 0), 100), v_status);
  update staff_app_users set last_status = v_status, last_at = now(), last_battery = least(greatest(p_battery, 0), 100),
         last_lat = case when v_status = 'ok' then p_lat else last_lat end,
         last_lng = case when v_status = 'ok' then p_lng else last_lng end,
         last_acc = case when v_status = 'ok' then p_acc else last_acc end
   where staff_id = u.staff_id;
  -- keep 60 days
  delete from staff_locations where at < now() - interval '60 days';
  return jsonb_build_object('ok', true, 'saved', true, 'hours', _staff_conf(u.staff_id));
end $$;

-- number from any column type ('' / text / numeric) - never an error
create or replace function public._staff_num(t text) returns numeric
language sql immutable as $$
  select case when trim(coalesce(t, '')) ~ '^-?\d+(\.\d+)?$' then trim(t)::numeric end
$$;

-- Website products for the staff catalog: MRP only (no selling price), sizes only as in stock / not in stock.
-- Every column is read through to_jsonb(row), so a column with an unexpected type can not break the catalog.
create or replace function public.staff_catalog(p_token text, p_device text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _staff_session(p_token, p_device);
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', pj.j->>'id', 'article', coalesce(pj.j->>'article', ''), 'name', coalesce(pj.j->>'name', ''),
      'brand', coalesce(pj.j->>'brand', ''), 'category', coalesce(pj.j->>'category', ''),
      'type', coalesce(pj.j->>'product_type', ''),
      'mrp', coalesce(nullif(_staff_num(pj.j->>'mrp'), 0), _staff_num(pj.j->>'price'), 0),
      'new', lower(coalesce(pj.j->>'new_item', 'false')) in ('true', 't', '1')
             and case when coalesce(pj.j->>'created_at', '') ~ '^\d{4}-\d{2}-\d{2}'
                      then (pj.j->>'created_at')::timestamptz > now() - interval '120 days' else true end,
      'description', coalesce(pj.j->>'description', ''),
      'images', case when jsonb_typeof(pj.j->'images') = 'array' then pj.j->'images' else '[]'::jsonb end,
      'colours', v.colours))
    from products p
    cross join lateral (select to_jsonb(p) as j) pj
    cross join lateral (
      select coalesce(jsonb_agg(jsonb_build_object(
          'colour', trim(coalesce(c->>'name', c->>'colour', '')),
          'images', case when jsonb_typeof(c->'images') = 'array' then c->'images' else '[]'::jsonb end,
          'sizes', (select coalesce(jsonb_agg(jsonb_build_object(
                        'size', case when jsonb_typeof(s) = 'object' then coalesce(s->>'size', '') else s #>> '{}' end,
                        'in', case when jsonb_typeof(s) = 'object' then coalesce(_staff_num(s->>'qty'), 0) > 0 else false end)), '[]'::jsonb)
                      from jsonb_array_elements(case when jsonb_typeof(c->'sizes') = 'array' then c->'sizes' else '[]'::jsonb end) s)
        )), '[]'::jsonb) as colours
        from jsonb_array_elements(case when jsonb_typeof(pj.j->'colours') = 'array' then pj.j->'colours'
                                       when jsonb_typeof(pj.j->'colours') = 'string' and left(pj.j->>'colours', 1) = '['
                                         then (pj.j->>'colours')::jsonb
                                       else '[]'::jsonb end) c
       where jsonb_typeof(c) = 'object' and trim(coalesce(c->>'name', c->>'colour', '')) <> ''
    ) v
    where lower(coalesce(pj.j->>'active', 'true')) not in ('false', 'f', '0')
      and exists (select 1 from jsonb_array_elements(v.colours) vc, jsonb_array_elements(vc->'sizes') vs
                   where (vs->>'in')::boolean)
  ), '[]'::jsonb);
end $$;

-- 5) Permissions: helpers are private; owner_* need the owner code; staff_* need a staff login key.
revoke all on function public._staff_hash(text) from public, anon, authenticated;
revoke all on function public._staff_hours() from public, anon, authenticated;
revoke all on function public._staff_in_hours() from public, anon, authenticated;
revoke all on function public._staff_session(text, text) from public, anon, authenticated;
revoke all on function public._staff_conf(text) from public, anon, authenticated;
revoke all on function public._staff_num(text) from public, anon, authenticated;
revoke all on function public.owner_staff_app_interval(text, text, int) from public;
grant execute on function public.owner_staff_app_interval(text, text, int) to anon, authenticated;
revoke all on function public.owner_staff_app_list(text) from public;
revoke all on function public.owner_staff_app_code(text, text, text, text) from public;
revoke all on function public.owner_staff_app_block(text, text) from public;
revoke all on function public.owner_staff_app_trail(text, text, date) from public;
revoke all on function public.owner_staff_app_hours(text, int, int) from public;
revoke all on function public.staff_login(text, text, text, text) from public;
revoke all on function public.staff_check(text, text) from public;
revoke all on function public.staff_location(text, text, double precision, double precision, real, int, text) from public;
revoke all on function public.staff_catalog(text, text) from public;
grant execute on function public.owner_staff_app_list(text) to anon, authenticated;
grant execute on function public.owner_staff_app_code(text, text, text, text) to anon, authenticated;
grant execute on function public.owner_staff_app_block(text, text) to anon, authenticated;
grant execute on function public.owner_staff_app_trail(text, text, date) to anon, authenticated;
grant execute on function public.owner_staff_app_hours(text, int, int) to anon, authenticated;
grant execute on function public.staff_login(text, text, text, text) to anon, authenticated;
grant execute on function public.staff_check(text, text) to anon, authenticated;
grant execute on function public.staff_location(text, text, double precision, double precision, real, int, text) to anon, authenticated;
grant execute on function public.staff_catalog(text, text) to anon, authenticated;

select 'staff app ready' as result;
