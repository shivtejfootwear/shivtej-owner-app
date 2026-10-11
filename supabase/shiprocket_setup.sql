-- Shivtej Footwear - Shiprocket columns on orders.
-- Run once in Supabase > SQL Editor. Safe to re-run.

alter table public.orders add column if not exists shiprocket_order_id text;
alter table public.orders add column if not exists shipment_id        text;
