-- =====================================================================
-- OWNER APP UPDATE 25: CAPITAL letters for the category list and the colours
--  * the product types and categories saved on the server (owner_category_tree) -> CAPITAL
--  * the colour names of all products -> CAPITAL (same as update 24, safe to run again)
-- Run this ONCE in Supabase > SQL Editor. Safe to run again.
-- The last result line says "OK: update 25 is installed".
-- =====================================================================

-- STEP 1: the category list (product types and their categories) -> CAPITAL
update public.owner_category_tree set data = jsonb_build_object('types', (
  select coalesce(jsonb_agg(jsonb_build_object(
           'name', upper(t->>'name'),
           'cats', (select coalesce(jsonb_agg(upper(x.v)), '[]'::jsonb)
                    from jsonb_array_elements_text(coalesce(t->'cats', '[]'::jsonb)) as x(v))
         )), '[]'::jsonb)
  from jsonb_array_elements(coalesce(data->'types', '[]'::jsonb)) t
))
where id = 'main' and jsonb_typeof(data->'types') = 'array';

-- STEP 2: colour names of all products -> CAPITAL
update public.products p set colours = (
    select coalesce(jsonb_agg(
             case
               when jsonb_typeof(c) <> 'object' then c
               when c ? 'name'   then jsonb_set(c, '{name}',   to_jsonb(upper(c->>'name')))
               when c ? 'colour' then jsonb_set(c, '{colour}', to_jsonb(upper(c->>'colour')))
               else c
             end), '[]'::jsonb)
    from jsonb_array_elements(p.colours) c)
where jsonb_typeof(p.colours) = 'array';

-- STEP 3: check
select 'OK: update 25 is installed (category list and colours in CAPITAL letters).' as status;
