# Shivtej Footwear - Owner App (native Android, Kotlin + Jetpack Compose)

Private owner/admin POS app. Offline-first (Room database) with a sync queue to the same Supabase project the website uses.
Website, Owner App and POS all share ONE stock: every stock change is an atomic, idempotent change on the server.

## 1. Supabase (once) - run the SQL in this order
1. Open `supabase/owner_app_setup.sql`. Change `CHANGE-ME-1234` (STEP 0) to your own secret owner code, paste the whole file into
   Supabase > SQL Editor > Run. The last result line must say `OK: owner code is set ...`.
   (Re-running the file never resets the code. To change it later use the one-line statement shown in STEP 0.)
2. Open `supabase/website_sync.sql`, paste, Run. (Website functions + `admin_users` table.)
3. Open `supabase/owner_update_2_migration.sql`, paste, Run. This is the update-2 migration: it only ADDS columns, tables and
   functions (staff, customers, suppliers, purchase bills, supplier payments, bill snapshot fields). It drops and deletes nothing, and
   existing products, stock, bills, orders, returns and customers are untouched. The last result line says `OK: update 2 is installed ...`.
   Until this file is run, the app keeps the new records on the phone and waits for the server (nothing is lost).
4. Optional, after testing the website and the app once: the commented STEP B at the end of `website_sync.sql` (row-level security).

## 2. Website
Upload `website/index-V4.4-OWNER-SYNC.html` in place of the current website file (your original V4.3 is kept in `website/`).
It reads products, colours, sizes and live stock from Supabase, places orders through `site_place_order` (stock checked and deducted
on the server), and its Billing / Add Product screens write to the shared database after you type the owner code once per session.

## 3. Build the APK on GitHub (no coding)
1. Create a GitHub repository and upload the CONTENTS of this folder (`app`, `supabase`, `website`, `build.gradle.kts`, ... and the
   hidden folder `.github`) so that they sit directly in the repository root.
   The build instructions are the file `.github/workflows/build-apk.yml` - GitHub only sees it at exactly that path.
2. Open the repository > Actions > "Build Shivtej Owner APK" (it also starts automatically on upload; or press "Run workflow").
3. When it turns green, open the run > Artifacts > download **Shivtej-Owner-App** (zip) > extract `Shivtej-Owner-App.apk`.
4. Copy the APK to the phone and install it (allow "install unknown apps").
If the build is red, open the failed step, copy the red error lines and send them back - they are fixed from the log.

**If the `.github` folder did not upload** (hidden folders are skipped by some file managers and browsers):
repository > Add file > Create new file > in the name box type `.github/workflows/build-apk.yml` (typing the `/` makes the
folders) > paste the whole content of `github-workflow-copy/build-apk.yml` > Commit changes. The build then starts by itself.

## 4. First start
Enter the same owner code you put in the SQL. The app downloads data and then keeps syncing by itself (every 15 s while open,
immediately after each change, and in the background about every 15 min).

## What is inside
Dashboard (light, real numbers from the database: today's and period sales, bills, discounts, stock and low-stock alerts, staff and top sellers),
Products (add / edit / delete, colours with their own sizes, stock, barcode and photos, active = visible on website, size filter),
Inventory + stock history, POS billing with camera barcode / QR scan (customer name / mobile / city, bill no / date / time, sales person,
Article / Colour / Size / Qty / MRP / Selling rate with discount = MRP - rate, GST %, payment mode, amount paid, balance, notes),
bill design in the app (presets, backgrounds, custom picture, strength, header / footer toggles, save as default; old bills keep their own design),
Shop & Bill Settings (store details, logo, website and Instagram QR codes generated automatically, GST %, footer and thanks text; old bills keep
the shop details they were made with), receipts (view / share / PDF / WhatsApp / print), Bluetooth printer (ATPOS AT-301 80 mm, text or graphic mode),
Staff (sales people, active / inactive, staff sales report by day / week / month / custom range, tap a person for the detail history),
Customers (search by mobile, purchase history from the shop and the website), Suppliers & Purchases (supplier list, purchase bills that add stock,
payments against a bill or on account, outstanding per supplier), online orders with status + courier + tracking, **returns & exchanges**
(from any order or bill, with refund and optional restock), shipping, reports (with refunds / net revenue), offline AI assistant,
settings (store info, owner code, PIN, sync, backup / restore (the file is checked first; the restore is applied at the next start, before the data is opened, and the current data is kept as a copy), temporary cache clean-up).

## Data safety rules in this version
- Pulling from the server never deletes a product. Products are removed only by the owner's Delete action (logged).
- A colour or size that still has stock cannot be removed from a product: set its stock to 0 first.
- Deleting a product writes its stock movement to the stock history first. Refused returns and purchases are kept as REJECTED (see below).
- A return, purchase bill or supplier payment that the server clearly refuses (400 / 409 / 422) is KEPT on this phone with status REJECTED and the server's reason. It is shown in the lists for history but is not counted in any total, balance or refund. Its stock movement is taken back through logged reversal entries. Timeouts and rate limits keep the record for a retry.
- Clear temporary cache deletes only temporary camera photos. Restore checks the file first and keeps the previous data as a copy.
- A database that this version can not open safely (for example version 1, or newer than this app) is never opened or changed; the app shows a message instead. Nothing is migrated destructively.
- Sent sync records are kept; the app does not delete its sync history.
- Backup uses a consistent snapshot on Android 11 and newer. On older Android take the backup while the sync is idle.

## Bluetooth permissions (printer) and why
- `BLUETOOTH_CONNECT` (Android 12+): to see paired printers and connect to the printer. Required by Android for any Bluetooth connection.
- `BLUETOOTH_SCAN` (Android 12+, `neverForLocation`): to look for nearby printers. It is not used to find your location.
- `BLUETOOTH` and `BLUETOOTH_ADMIN` (Android 11 and older, `maxSdk="30"`): the same job on old phones.
- `ACCESS_FINE_LOCATION` (`maxSdk="30"`): Android 11 and older only require it to scan for Bluetooth devices. Newer Android versions do not.
- `uses-feature android.hardware.bluetooth` is optional, so the app still installs on a phone without Bluetooth (printing is then unavailable).
The app asks for these only when you open the Printer screen or print. Printing uses Bluetooth Classic (SPP) to the ATPOS AT-301, and PDF / share keep working without a printer.

## Update 2 (new screens) - what changed in the code
- Data: `data/Db.kt` (Room database version 3, additive `MIGRATION_2_3`, new tables for staff, customers, suppliers, purchases and supplier payments),
  `data/Repo.kt` (create bill with the new fields, staff, customers, suppliers, purchase bills, payments), `data/CartStore.kt`, `data/BillMeta.kt`, `data/Sales.kt`.
- Sync: `sync/SyncManager.kt` pushes the new changes after the existing ones and pulls the new lists. If the update-2 SQL is not installed
  yet, those changes stay on the phone and retry later.
- Pure logic (tested on the JVM): `util/BillMath.kt`, `util/SupplierMath.kt`, `util/Reports.kt`, `util/Links.kt`, `util/EscPos.kt`, `util/ReceiptModel.kt`.
- Screens: `ui/Billing.kt` (POS and receipt), `ui/Dashboard.kt`, `ui/Staff.kt`, `ui/Customers.kt`, `ui/Suppliers.kt`, `ui/ShopSettings.kt`,
  `ui/BillDesign.kt`, `ui/PrinterScreen.kt`. Receipts are drawn by `util/Invoice.kt` (colour and 80 mm black-and-white) with QR codes from `util/Qr.kt`.
- Printer: `util/PrinterManager.kt`.
- New dependency: `com.google.zxing:core:3.5.3` (QR codes).

## Known limits (not yet tested on a phone)
- The Android code has not been compiled or run in the environment where it was written. The first GitHub build and a test on a real phone and the
  ATPOS printer are the real check. Please send the build log if it is red.
- Bluetooth, camera, photo upload and the live Supabase run were not tested in that environment.

## Configuration
Supabase URL and the publishable key are in `app/build.gradle.kts` (`buildConfigField`). No service-role key is used anywhere.

## Local build (optional)
Android Studio (JDK 17): open the folder, Build > Build APK(s).
Command line with Gradle 8.9: `gradle :app:assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`
