-- Shivtej Footwear - tracking also returns the payment status.
-- The website uses it to show the bill (PDF) only for online paid orders.
-- Run once in the Supabase SQL editor. Safe to run again.
-- (The "destructive operations" warning comes from the DROP line below. It only replaces this one function.)

drop function if exists public.site_track_order(text, text);
create or replace function public.site_track_order(p_id text, p_mobile text)
returns table (id text, order_status text, tracking_no text, courier text,
               shipping numeric, total numeric, created_at timestamptz, shipment_status text,
               payment_status text)
language sql security definer set search_path = public stable as $$
  select o.id, o.order_status, o.tracking_no, o.courier, o.shipping, o.total, o.created_at, o.shipment_status,
         o.payment_status
    from orders o
   where o.id = upper(trim(coalesce(p_id, '')))
     and o.customer->>'mobile' = regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g')
$$;

revoke all on function public.site_track_order(text, text) from public;
grant execute on function public.site_track_order(text, text) to anon, authenticated;
