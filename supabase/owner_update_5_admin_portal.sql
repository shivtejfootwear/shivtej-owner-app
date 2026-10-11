-- ============================================================
-- OWNER APP UPDATE 5: Admin Portal (website text and shipping charge)
-- Run this ONCE in the Supabase SQL editor.
-- It only ADDS three functions and one storage bucket (site-media). Nothing is deleted,
-- and no table is changed.
-- ============================================================

-- 1) Read the website text settings and the shipping charge (owner code needed)
create or replace function public.owner_get_site_settings(p_code text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_site jsonb;
  v_fee  numeric;
begin
  perform _owner_ok(p_code);
  select value into v_site from site_settings where key = 'site';
  select (value->>'fee')::numeric into v_fee from site_settings where key = 'shipping';
  return jsonb_build_object(
    'site', coalesce(v_site, '{}'::jsonb),
    'shippingFee', coalesce(v_fee, 80)
  );
end $$;

-- 2) Save only the website keys listed below (text, links and the three photo URLs).
--    Keys not listed here are never touched.
create or replace function public.owner_save_site_settings(p_code text, p_value jsonb)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v_old jsonb;
  v_pick jsonb;
begin
  perform _owner_ok(p_code);
  select value into v_old from site_settings where key = 'site';
  select jsonb_object_agg(k, p_value -> k) into v_pick
    from unnest(array['heroTitle','heroText','heroAnim','heroArticle',
                      'bandTitle','bandText','instagram','facebook','whatsapp',
                      'heroImage','heroArtImage','bandImage']) as k
   where p_value ? k;
  insert into site_settings (key, value, updated_at)
  values ('site', coalesce(v_old, '{}'::jsonb) || coalesce(v_pick, '{}'::jsonb), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

-- 3) Save the shipping charge for website orders (0 to 5000)
create or replace function public.owner_set_shipping_fee(p_code text, p_fee numeric)
returns void
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if p_fee is null or p_fee < 0 or p_fee > 5000 then raise exception 'BAD_SHIPPING'; end if;
  insert into site_settings (key, value, updated_at)
  values ('shipping', jsonb_build_object('fee', p_fee), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

-- 4) Website photo bucket: public read (the shop shows it). Uploads go through the
--    site-media-upload Edge Function with the owner code, so there is NO public upload policy.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('site-media', 'site-media', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public = true, file_size_limit = 5242880,
  allowed_mime_types = array['image/jpeg','image/png','image/webp'];

drop policy if exists "public read site media" on storage.objects;
create policy "public read site media" on storage.objects
  for select to anon, authenticated using (bucket_id = 'site-media');

-- 5) Permissions: only the owner app (anon key + owner code) can call these
revoke all on function public.owner_get_site_settings(text)    from public;
revoke all on function public.owner_save_site_settings(text, jsonb) from public;
revoke all on function public.owner_set_shipping_fee(text, numeric) from public;

grant execute on function public.owner_get_site_settings(text)    to anon, authenticated;
grant execute on function public.owner_save_site_settings(text, jsonb) to anon, authenticated;
grant execute on function public.owner_set_shipping_fee(text, numeric) to anon, authenticated;

-- 6) Check
select case when
  exists (select 1 from pg_proc where proname = 'owner_get_site_settings')
  and exists (select 1 from pg_proc where proname = 'owner_save_site_settings')
  and exists (select 1 from pg_proc where proname = 'owner_set_shipping_fee')
then 'OK: owner update 5 (admin portal) is installed.'
else 'PROBLEM: owner update 5 is incomplete - send a screenshot of the error above.'
end as status;
