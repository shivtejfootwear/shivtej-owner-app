-- Shivtej Footwear - update 28: the owner can DELETE a manager's pending bill from "Staff Bills".
-- Run ONCE in Supabase > SQL Editor, AFTER update 17. Safe to run again. Nothing is deleted from products or sales.
-- The bill is only marked 'cancelled' (same as when a manager cancels it), so it disappears from the owner
-- list AND from the manager's own pending list on the Managers app.

create or replace function public.owner_staff_bill_delete(p_code text, p_id uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  perform _owner_ok(p_code);
  update staff_pending_bills set status = 'cancelled', updated_at = now()
   where id = p_id and status = 'pending';
  get diagnostics n = row_count;
  return jsonb_build_object('ok', true, 'deleted', n);
end $$;

revoke all on function public.owner_staff_bill_delete(text,uuid) from public;
grant execute on function public.owner_staff_bill_delete(text,uuid) to anon, authenticated;

select 'OK: update 28 is installed (owner can delete a staff bill).' as status;
