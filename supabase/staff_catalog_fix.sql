-- Shivtej Managers: catalog fix only (run this if the full update 15 was already run once).
-- Small enough to paste on a phone. Safe to run again.

-- number from any column type ('' / text / numeric) - never an error
create or replace function public._staff_num(t text) returns numeric
language sql immutable as $$
  select case when trim(coalesce(t, '')) ~ '^-?\d+(\.\d+)?$' then trim(t)::numeric end
$$;

-- Website products for the staff catalog: MRP only (no selling price), sizes only as in stock / not in stock.
-- Every column is read through to_jsonb(row), so a column with an unexpected type can not break the catalog.
create or replace function public.staff_catalog(p_token text, p_device text) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _staff_session(p_token, p_device);
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', pj.j->>'id', 'article', coalesce(pj.j->>'article', ''), 'name', coalesce(pj.j->>'name', ''),
      'brand', coalesce(pj.j->>'brand', ''), 'category', coalesce(pj.j->>'category', ''),
      'type', coalesce(pj.j->>'product_type', ''),
      'mrp', coalesce(nullif(_staff_num(pj.j->>'mrp'), 0), _staff_num(pj.j->>'price'), 0),
      'new', lower(coalesce(pj.j->>'new_item', 'false')) in ('true', 't', '1')
             and case when coalesce(pj.j->>'created_at', '') ~ '^\d{4}-\d{2}-\d{2}'
                      then (pj.j->>'created_at')::timestamptz > now() - interval '120 days' else true end,
      'description', coalesce(pj.j->>'description', ''),
      'images', case when jsonb_typeof(pj.j->'images') = 'array' then pj.j->'images' else '[]'::jsonb end,
      'colours', v.colours))
    from products p
    cross join lateral (select to_jsonb(p) as j) pj
    cross join lateral (
      select coalesce(jsonb_agg(jsonb_build_object(
          'colour', trim(coalesce(c->>'name', c->>'colour', '')),
          'images', case when jsonb_typeof(c->'images') = 'array' then c->'images' else '[]'::jsonb end,
          'sizes', (select coalesce(jsonb_agg(jsonb_build_object(
                        'size', case when jsonb_typeof(s) = 'object' then coalesce(s->>'size', '') else s #>> '{}' end,
                        'in', case when jsonb_typeof(s) = 'object' then coalesce(_staff_num(s->>'qty'), 0) > 0 else false end)), '[]'::jsonb)
                      from jsonb_array_elements(case when jsonb_typeof(c->'sizes') = 'array' then c->'sizes' else '[]'::jsonb end) s)
        )), '[]'::jsonb) as colours
        from jsonb_array_elements(case when jsonb_typeof(pj.j->'colours') = 'array' then pj.j->'colours'
                                       when jsonb_typeof(pj.j->'colours') = 'string' and left(pj.j->>'colours', 1) = '['
                                         then (pj.j->>'colours')::jsonb
                                       else '[]'::jsonb end) c
       where jsonb_typeof(c) = 'object' and trim(coalesce(c->>'name', c->>'colour', '')) <> ''
    ) v
    where lower(coalesce(pj.j->>'active', 'true')) not in ('false', 'f', '0')
      and exists (select 1 from jsonb_array_elements(v.colours) vc, jsonb_array_elements(vc->'sizes') vs
                   where (vs->>'in')::boolean)
  ), '[]'::jsonb);
end $$;

revoke all on function public._staff_num(text) from public, anon, authenticated;
revoke all on function public.staff_catalog(text, text) from public;
grant execute on function public.staff_catalog(text, text) to anon, authenticated;

select 'catalog fix ready' as result;
