-- =====================================================================
-- Owner update 19: website switches (maintenance page and top announcement bar).
-- Run ONCE in the Supabase SQL editor. It only replaces the saving function; no data is changed.
--
-- The saving function keeps a list of the keys it may save. The list below is the full list
-- (all keys from the earlier updates, including "season" and "offer") plus the new ones:
--   maintenanceOn, maintenanceText, announceOn, announceText, freeDeliveryAbove.
-- =====================================================================
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
                      'season',
                      'maintenanceOn','maintenanceText','announceOn','announceText','freeDeliveryAbove']) as k
   where p_value ? k;
  insert into site_settings (key, value, updated_at)
  values ('site', coalesce(v_old, '{}'::jsonb) || coalesce(v_pick, '{}'::jsonb), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

-- Check after running: the saved site settings must contain the switch keys once you save them in the app.
-- select value->'maintenanceOn', value->'announceOn' from site_settings where key = 'site';
