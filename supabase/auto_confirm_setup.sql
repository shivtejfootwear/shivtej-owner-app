-- Shivtej Footwear - automatic order confirmation.
-- The order becomes "Confirmed" by itself as soon as the payment is received,
-- so the shop does not have to press Confirmed. The customer's bill (PDF) is
-- available from Track order from that moment on.
-- Online payment: payment_status says "Paid" (not "pending" / "unpaid").
-- COD (testing only, to be removed later): confirmed at placement.
-- Run once in the Supabase SQL editor. Safe to run again.

create or replace function public.orders_auto_confirm()
returns trigger
language plpgsql
security definer set search_path = public as $$
declare
  v_pay text := lower(coalesce(new.payment_status, ''));
begin
  if new.order_status = 'New'
     and (
       (v_pay like '%paid%' and v_pay not like '%unpaid%' and v_pay not like '%pending%')
       or v_pay like '%cod%'
     )
  then
    new.order_status := 'Confirmed';
    new.updated_at := now();
  end if;
  return new;
end $$;

drop trigger if exists orders_auto_confirm on public.orders;
create trigger orders_auto_confirm
  before insert or update of payment_status, order_status on public.orders
  for each row execute function public.orders_auto_confirm();
