-- Owner app update 13: season animation on the website.
-- Run this ONCE in Supabase > SQL Editor.
-- It lets the owner app save the "season" setting (which season animation plays when the website opens).
-- It is the same function as before (slides_coupons_setup.sql) with one more allowed key: season.
-- Nothing is deleted. The other settings stay as they are.

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
                      'heroImage','heroArtImage','bandImage','filmVideo','filmArticle','slides','offer',
                      'season']) as k
   where p_value ? k;
  insert into site_settings (key, value, updated_at)
  values ('site', coalesce(v_old, '{}'::jsonb) || coalesce(v_pick, '{}'::jsonb), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

revoke all on function public.owner_save_site_settings(text, jsonb) from public;
grant execute on function public.owner_save_site_settings(text, jsonb) to anon, authenticated;

-- Check: should show one row with "season" in the list of keys the function accepts.
select 'owner_save_site_settings updated' as result;
