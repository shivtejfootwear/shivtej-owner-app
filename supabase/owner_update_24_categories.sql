-- =====================================================================
-- OWNER APP UPDATE 24: category under the product type + CAPITAL letters
--  * products.sub_category: the category of a product (e.g. चप्पल -> V BELT, SPORTS SHOES -> LACE)
--  * owner_category_tree: the list of product types with their categories (one row, like the size groups)
--  * owner_upsert_product: the same function as before, plus sub_category
--  * existing English names (article, name, brand, product type, category, colour names) are changed to CAPITAL letters
--    (the Men / Ladies "category" field is NOT changed, the website filter uses it)
-- Run this ONCE in Supabase > SQL Editor. Safe to run again.
-- The last result line says "OK: update 24 is installed ...".
-- =====================================================================

-- STEP 1: the category column on products
alter table public.products add column if not exists sub_category text;

-- STEP 2: the list of product types and their categories (one row)
create table if not exists public.owner_category_tree (
  id          text primary key default 'main',
  data        jsonb not null default '{}'::jsonb,
  updated_at  timestamptz not null default now()
);
alter table public.owner_category_tree enable row level security;

create or replace function public.owner_get_category_tree(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare r public.owner_category_tree%rowtype;
begin
  perform _owner_ok(p_code);
  select * into r from public.owner_category_tree where id = 'main';
  if not found then
    return jsonb_build_object('exists', false);
  end if;
  return jsonb_build_object('exists', true, 'data', r.data,
                            'updated_at_ms', floor(extract(epoch from r.updated_at) * 1000)::bigint);
end $$;

create or replace function public.owner_save_category_tree(p_code text, p jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  perform _owner_ok(p_code);
  if p is null or jsonb_typeof(p) <> 'object' then
    raise exception 'INVALID_CATEGORY_TREE';
  end if;
  insert into public.owner_category_tree (id, data, updated_at) values ('main', p, now())
  on conflict (id) do update set data = excluded.data, updated_at = excluded.updated_at;
  return jsonb_build_object('ok', true);
end $$;

revoke all on function public.owner_get_category_tree(text)        from public;
revoke all on function public.owner_save_category_tree(text,jsonb) from public;
grant execute on function public.owner_get_category_tree(text)        to anon, authenticated;
grant execute on function public.owner_save_category_tree(text,jsonb) to anon, authenticated;

-- STEP 3: owner_upsert_product, the same as before, plus sub_category
--  (an old phone that does not send sub_category keeps the value already saved)
create or replace function public.owner_upsert_product(p_code text, p jsonb) returns text
language plpgsql security definer set search_path = public as $$
declare
  ex jsonb; outc jsonb := '[]'::jsonb; c jsonb; s jsonb; ns jsonb;
  exq text; allsizes jsonb; bc text; pid text := p->>'id';
begin
  perform _owner_ok(p_code);
  select colours into ex from products where id = pid for update;

  for c in select value from jsonb_array_elements(coalesce(p->'colours', '[]'::jsonb)) loop
    ns := '[]'::jsonb;
    for s in select value from jsonb_array_elements(coalesce(c->'sizes', '[]'::jsonb)) loop
      bc := nullif(s->>'barcode', '');
      if bc is not null and exists (
           select 1 from products pr,
                  jsonb_array_elements(coalesce(pr.colours,'[]'::jsonb)) pc,
                  jsonb_array_elements(coalesce(pc->'sizes','[]'::jsonb)) ps
           where pr.id <> pid and ps->>'barcode' = bc) then
        raise exception 'BARCODE_DUPLICATE %', bc;
      end if;
      if ex is not null then
        exq := null;
        select e2->>'qty' into exq
          from jsonb_array_elements(ex) e1,
               jsonb_array_elements(coalesce(e1->'sizes','[]'::jsonb)) e2
         where lower(coalesce(e1->>'name', e1->>'colour')) = lower(c->>'name')
           and e2->>'size' = s->>'size'
         limit 1;
        if exq is not null then s := jsonb_set(s, '{qty}', to_jsonb(exq::int)); end if;
      end if;
      ns := ns || jsonb_build_array(s);
    end loop;
    outc := outc || jsonb_build_array(jsonb_set(c, '{sizes}', ns));
  end loop;

  select coalesce(jsonb_agg(distinct sz), '[]'::jsonb) into allsizes
    from (select e2->>'size' as sz
            from jsonb_array_elements(outc) e1,
                 jsonb_array_elements(coalesce(e1->'sizes','[]'::jsonb)) e2) t;

  update products set
      article = p->>'article', name = p->>'name', description = p->>'description',
      brand = p->>'brand', category = p->>'category',
      product_type = coalesce(p->>'product_type', product_type),
      sub_category = coalesce(p->>'sub_category', sub_category),
      price = (p->>'price')::numeric, mrp = (p->>'mrp')::numeric,
      active = coalesce((p->>'active')::boolean, true),
      new_item = coalesce((p->>'new_item')::boolean, false),
      images = coalesce(p->'images', '[]'::jsonb),
      colours = outc, sizes = allsizes, updated_at = now()
    where id = pid;
  if not found then
    insert into products (id, article, name, description, brand, category, product_type, sub_category, price, mrp,
                          active, new_item, images, colours, sizes, updated_at)
    values (pid, p->>'article', p->>'name', p->>'description', p->>'brand', p->>'category', p->>'product_type',
            p->>'sub_category',
            (p->>'price')::numeric, (p->>'mrp')::numeric,
            coalesce((p->>'active')::boolean, true), coalesce((p->>'new_item')::boolean, false),
            coalesce(p->'images', '[]'::jsonb), outc, allsizes, now());
  end if;
  return pid;
end $$;

-- STEP 4: CAPITAL letters for the existing products (article, name, brand, product type, category, colour names)
update public.products set
    article      = upper(article),
    name         = upper(name),
    brand        = upper(brand),
    product_type = upper(product_type),
    sub_category = upper(sub_category)
where true;

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

-- STEP 5: check (the last line says whether everything is in place)
select case
         when to_regclass('public.owner_category_tree') is not null
          and to_regprocedure('public.owner_get_category_tree(text)') is not null
          and to_regprocedure('public.owner_save_category_tree(text,jsonb)') is not null
          and exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'products' and column_name = 'sub_category')
         then 'OK: update 24 is installed (categories under product types, capital letters).'
         else 'PROBLEM: update 24 is incomplete - send a screenshot of the error above.'
       end as status;
