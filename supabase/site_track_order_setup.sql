-- Shivtej Footwear - customer order tracking on the website.
-- Run once in Supabase > SQL Editor. Safe to re-run.
--
-- The customer must give BOTH the order number and the mobile used for the order.
-- Anyone else gets no row back.

create or replace function public.site_track_order(p_id text, p_mobile text)
returns table (id text, order_status text, tracking_no text, courier text,
               shipping numeric, total numeric, created_at timestamptz)
language sql security definer set search_path = public stable as $$
  select o.id, o.order_status, o.tracking_no, o.courier, o.shipping, o.total, o.created_at
    from orders o
   where o.id = upper(trim(coalesce(p_id, '')))
     and o.customer->>'mobile' = regexp_replace(coalesce(p_mobile, ''), '\D', '', 'g')
$$;

revoke all on function public.site_track_order(text, text) from public;
grant execute on function public.site_track_order(text, text) to anon, authenticated;
