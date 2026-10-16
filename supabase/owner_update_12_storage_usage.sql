-- =====================================================================
-- OWNER APP UPDATE 12: storage usage for the dashboard (database size and Supabase file storage)
-- Run this ONCE in Supabase > SQL Editor.
-- It only ADDS one function. Nothing is changed, moved or deleted.
-- The function needs the owner code, the same check every owner action uses.
-- The last result line says "Success. No rows returned" when it worked.
-- =====================================================================

create or replace function public.owner_storage_usage(p_code text)
returns json
language plpgsql
security definer
set search_path = public, storage
as $$
begin
  perform public.owner_ping(p_code);   -- raises INVALID_OWNER_CODE for a wrong code
  return json_build_object(
    'db_bytes', pg_database_size(current_database()),
    'storage_bytes', (select coalesce(sum((metadata->>'size')::bigint), 0) from storage.objects)
  );
end;
$$;

revoke all on function public.owner_storage_usage(text) from public;
grant execute on function public.owner_storage_usage(text) to anon, authenticated;
