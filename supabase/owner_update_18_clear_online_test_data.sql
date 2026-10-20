-- =====================================================================
-- Clear the ONLINE (website) TEST data only.
--
-- Deleted:   website customers (public.customers), website orders (public.orders),
--            pay-first checkout records (public.site_checkouts),
--            return records made on website orders (public.returns_log where ref_type = 'ORDER').
-- NOT touched: shop (POS) bills, shop customers (pos_customers), owner customer payments,
--            supplier data, staff data, products, variants, sizes, stock, stock_log, settings.
-- Stock: NOTHING is added back and NOTHING is taken out. Stock stays exactly as it is now.
--
-- Run STEP 1 first, check the numbers. Then STEP 2 (backup). Then STEP 3 (delete).
-- =====================================================================

-- STEP 1: counts (write these numbers down before deleting)
select 'orders (website)'            as what, count(*) as rows from public.orders
union all select 'customers (website)',       count(*) from public.customers
union all select 'site_checkouts',            count(*) from public.site_checkouts
union all select 'returns_log (ORDER)',       count(*) from public.returns_log where ref_type = 'ORDER';

-- STEP 2: backup copy (kept in a separate schema; nothing is lost if a mistake happens)
create schema if not exists online_test_backup;
create table if not exists online_test_backup.orders          as select * from public.orders;
create table if not exists online_test_backup.customers       as select * from public.customers;
create table if not exists online_test_backup.site_checkouts  as select * from public.site_checkouts;
create table if not exists online_test_backup.returns_log_orders as select * from public.returns_log where ref_type = 'ORDER';

-- STEP 3: delete (one transaction: either everything is deleted or nothing is)
-- The counts shown in STEP 1 must match what you expect. To cancel, replace the final COMMIT with ROLLBACK.
begin;
delete from public.returns_log where ref_type = 'ORDER';
delete from public.site_checkouts;
delete from public.orders;
delete from public.customers;
commit;

-- Check after: all four must be 0
select (select count(*) from public.orders)    as orders_left,
       (select count(*) from public.customers) as customers_left,
       (select count(*) from public.site_checkouts) as checkouts_left,
       (select count(*) from public.returns_log where ref_type = 'ORDER') as order_returns_left;
