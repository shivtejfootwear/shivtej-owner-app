-- =====================================================================
-- OWNER APP UPDATE 11: product type (चप्पल, सैंडल, शूज़, लोफर ...) for the website filter
-- Run this ONCE in Supabase > SQL Editor, AFTER owner_app_setup.sql (and the earlier updates).
-- It only ADDS a column and replaces owner_upsert_product with the same function plus product_type.
-- Old phones that do not send product_type keep the value already saved (coalesce).
-- The website's site_list_products already returns every column, so it gets product_type too.
-- =====================================================================

alter table public.products add column if not exists product_type text;

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
      price = (p->>'price')::numeric, mrp = (p->>'mrp')::numeric,
      active = coalesce((p->>'active')::boolean, true),
      new_item = coalesce((p->>'new_item')::boolean, false),
      images = coalesce(p->'images', '[]'::jsonb),
      colours = outc, sizes = allsizes, updated_at = now()
    where id = pid;
  if not found then
    insert into products (id, article, name, description, brand, category, product_type, price, mrp,
                          active, new_item, images, colours, sizes, updated_at)
    values (pid, p->>'article', p->>'name', p->>'description', p->>'brand', p->>'category', p->>'product_type',
            (p->>'price')::numeric, (p->>'mrp')::numeric,
            coalesce((p->>'active')::boolean, true), coalesce((p->>'new_item')::boolean, false),
            coalesce(p->'images', '[]'::jsonb), outc, allsizes, now());
  end if;
  return pid;
end $$;
