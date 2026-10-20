-- =====================================================================
-- Owner update 20: brand films (4 video or photo lines) inside the website product list.
-- Run ONCE in the Supabase SQL editor. It only replaces the saving function; no data is changed.
--
-- The saving function keeps a list of the keys it may save. The list below is the full list
-- (all keys from the earlier updates) plus the new one: "films" (the 4 brand film lines).
-- Until this is run, "Save website" does not store the films.
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
                      'heroImage','heroArtImage','bandImage','filmVideo','filmArticle','films','slides','offer',
                      'season',
                      'maintenanceOn','maintenanceText','announceOn','announceText','freeDeliveryAbove',
                      'trustOn','trust1','trust2','trust3','promoOn','promoText','promoCode','promoTerms',
                      'storeMapLink','storeTiming','featuredArticles','lowStockOn','lowStockLimit',
                      'sliderAuto','sliderSeconds','heroBtnText','heroBtnLink','revealOn']) as k
   where p_value ? k;
  insert into site_settings (key, value, updated_at)
  values ('site', coalesce(v_old, '{}'::jsonb) || coalesce(v_pick, '{}'::jsonb), now())
  on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at;
end $$;

-- Check after running and pressing "Save website" in the app:
-- select jsonb_array_length(value->'films') as film_lines from site_settings where key = 'site';
