-- Shivtej Footwear - live shipment timeline on Track order + video upload for the top photo.
-- Run in Supabase > SQL Editor, in Database mode (not Logs mode), ONE STEP AT A TIME.
-- Nothing is deleted. Existing orders, stock and settings are not changed.

-- STEP 1: keep the Shiprocket scan history on each order (empty for old orders).
alter table public.orders add column if not exists shipment_scans jsonb;

-- STEP 2: Track order also returns the scan history (same columns as before, plus shipment_scans).
drop function if exists public.site_track_order(text, text);
create or replace function public.site_track_order(p_id text, p_mobile text)
returns table (id text, order_status text, tracking_no text, courier text,
               shipping numeric, total numeric, created_at timestamptz,
               shipment_status text, shipment_scans jsonb)
language sql security definer set search_path = public stable as $$
  select o.id, o.order_status, o.tracking_no, o.courier, o.shipping, o.total, o.created_at,
         o.shipment_status, o.shipment_scans
    from orders o
   where o.id = upper(trim(coalesce(p_id, '')))
     and o.customer->>'mobile' = regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g')
$$;
revoke all on function public.site_track_order(text, text) from public;
grant execute on function public.site_track_order(text, text) to anon, authenticated;

-- STEP 3: allow MP4 / WebM video in the site-media bucket (top photo or video), up to 50 MB.
-- Photos stay allowed. This only changes the bucket's allow-list, which caused "415 invalid_mime_type".
update storage.buckets
   set allowed_mime_types = array['image/jpeg','image/png','image/webp','video/mp4','video/webm'],
       file_size_limit = 52428800
 where id = 'site-media';
