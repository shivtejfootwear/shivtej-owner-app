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
4. Open `supabase/owner_update_3_supplier_bill_file.sql`, paste, Run. (Update 3: supplier bill photo / PDF, storage bucket `supplier-bills`.)
   It only ADDS a column, a bucket and policies, and replaces `owner_create_purchase` with the same signature. The last result line says `OK: update 3 is installed ...`.
5. Open `supabase/owner_update_4_media_ledger_tasks.sql`, paste, Run. (Update 4: customer payments (udhaar), customer and supplier photos,
   product videos, storage buckets `profile-photos` and `product-videos`.) It only ADDS columns, a table, buckets, policies and functions.
   Run it AFTER update 2 and update 3. The last result line says `OK: update 4 is installed ...`.
   Until it is run, payments, photos and videos stay on the phone and are sent when the SQL is installed (nothing is lost).
6. Optional, after testing the website and the app once: the commented STEP B at the end of `website_sync.sql` (row-level security).

## 2. Website
Upload `website/index-V4.4-OWNER-SYNC.html` in place of the current website file (your original V4.3 is kept in `website/`).
It reads products, colours, sizes and live stock from Supabase, places orders through `site_place_order` (stock checked and deducted
on the server), and its Billing / Add Product screens write to the shared database after you type the owner code once per session.

## 3. Build the APK on GitHub (no coding)
1. Create a GitHub repository and upload the CONTENTS of this folder (`app`, `supabase`, `website`, `build.gradle.kts`, ... and the
   hidden folder `.github`) so that they sit directly in the repository root.
   The build instructions are the file `.github/workflows/build-apk.yml` - GitHub only sees it at exactly that path.
2. Open the repository > Actions > "Build Shivtej Owner APK" (it also starts automatically on upload; or press "Run workflow").
3. The run first runs the unit tests (a failing test stops the build), then lint (report only), then builds the APKs.
4. When it turns green, open the run > Artifacts > download **Shivtej-Owner-App** (zip) > extract `Shivtej-Owner-App.apk`.
   Also available: **Shivtej-Owner-App-debug** (debug APK) and **unit-test-results** (the test report).
5. Copy the APK to the phone and install it (allow "install unknown apps").
If the build is red, open the failed step, copy the red error lines and send them back - they are fixed from the log.

**Release signing (one time, so updates install over the old app).** The release APK must be signed with the SAME key every time.
Without the key below, every run signs with a new debug key and an installed app cannot be updated over ("package conflicts").
1. Use the key the installed app was signed with. If that key is not available, it cannot be recovered: the old app must be uninstalled
   once (back up first in the app: Settings > Backup) and a new key created.
2. Encode the keystore file as one line of base64 (on a computer: `base64 -w0 owner-release.jks > owner-release.jks.b64`).
3. Repository > Settings > Secrets and variables > Actions > add these four secrets:
   `SHIVTEJ_KEYSTORE_BASE64` (the content of the .b64 file), `SHIVTEJ_KEYSTORE_PASSWORD`, `SHIVTEJ_KEY_ALIAS`, `SHIVTEJ_KEY_PASSWORD`.
   The keystore is never stored in the repository. The workflow writes it to a temporary file during the run only.
4. Run the workflow again. The log then says "Release keystore loaded"; without the secrets it shows a warning instead.

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

## Update 4 (final update) - what changed
- **Theme and wallpaper** (Settings > Theme, colours and wallpaper): Light, Dark, Red & White or System; accent colour; 6 built-in
  wallpapers or your own picture from the gallery; preview before applying; readability cover (0-90%); dashboard only or all main screens;
  remove wallpaper; restore default. Saved on the phone. Code: `ui/Theme.kt`, `data/Prefs.kt`, `MainActivity.kt`.
- **Dashboard readability**: cards use the theme surface colour; coloured figures are lightened on dark backgrounds (`readableTone`).
- **Assistant**: the offline engine `util/Assistant.kt` answers from this phone's data (today's sales, what sold today, last 7 days vs
  the 7 days before, stock of an article such as `6100 size 8`, low and out of stock, reorder, best sellers, customer dues by name or
  mobile, supplier dues, pending orders, open tasks). Works without internet.
  Optional online AI (`util/AiClient.kt`, Anthropic Messages API, default model `claude-haiku-5-5`): Settings > AI assistant. It is used only
  when it is switched on, the phone is online and a key is saved. If it fails, the offline answer is shown with the reason.
  The API key is kept only in encrypted storage (`util/SecretStore.kt`, AndroidX Security). It is not in the source code, the ZIP or the sync queue.
  Only totals and the top few names are sent with a question (no phone numbers).
- **Product videos**: on the product screen, Add videos (gallery, up to 25 MB each), Preview (opens the phone's video player), Replace,
  Remove. The video is kept on the phone and uploaded to the `product-videos` bucket by the sync. Code: `ui/ProductEdit.kt`, `util/Videos.kt`,
  `sync/SyncManager.kt` (`syncVideos`).
- **Customer udhaar**: each customer row shows the amount due; a total-outstanding card at the top; filter "Owes money" / "No dues"; search
  by name, mobile or address. Customer detail: outstanding box, Receive payment (full or partial, against one bill or on account; refused
  when it is more than what is due), account ledger (bills, payments, running balance, tap a shop bill to open its receipt).
  Code: `util/Ledger.kt`, `ui/Customers.kt`, `ui/CustomerLedger.kt`, `data/Repo.kt` (`recordCustomerPayment`).
- **Photos**: customer and supplier photo (add, replace, remove), shown in the lists and the detail, synced to the `profile-photos` bucket.
- **Supplier bill photo / PDF** (update 3) is unchanged in this version.
- **Tasks and reminders** (drawer "Tasks & Reminders"): create, edit, complete, delete; due date and time; example tasks. Reminders use
  WorkManager (`util/Reminders.kt`) and need the notification permission (Android 13 and newer ask for it). Tasks stay on this phone and are not synced.
- **Returns, refunds and website orders in the customer ledger** (final update, `util/Ledger.kt`, `data/LedgerInput.kt`):
  - A website order that is not cancelled is a charge on the customer's account. The website saves orders as "COD - pending" or
    "Pending payment", so they stay owed until the owner records the money (Receive payment > On account). An order counts as paid
    only when its payment status is "Paid".
  - A return takes back the value of the goods (it reduces what is owed). An exchange adds the replacement goods to what is owed.
  - A refund is cash handed back to the customer, so it is recorded as cash. The return screen allows cash only up to what the
    customer has paid extra with the shop over all their sales and payments ("Cash refund allowed"); the rest of the value reduces the
    udhaar. The same limit is checked when saving. A customer who owes on another sale gets no cash refund.
  - An exchange where the customer pays the difference is recorded as money received.
  - Rejected returns and payments are not counted. Changing an order status to "Returned" does not change the balance: record the return.
  - The customer's outstanding, purchase history and account ledger use the same rules; the last ledger balance equals the outstanding amount.
  - No database change: the rules read the existing bills, orders, payments and returns. No data is rewritten.
- **Database version 6**: `MIGRATION_5_6` only adds columns and tables. Backup and the database check accept versions 2 to 6.
  Nothing is dropped, reset or rewritten.

## Update 5 (theme, Quick actions, customers and dues)
- Dashboard: pencil icon edits the Quick actions (add, remove, reorder, restore default). "Theme" button opens the theme settings.
- Theme: Light, Dark, Custom (own background colour), System, Red & White; wallpaper from the gallery with "Fill screen" or "Whole picture".
- Customers: opening balance (dues from before the app) per customer, sorting by Most spent / Highest due / Oldest due, "Customer dues" card on the dashboard.
- Billing: saved customers are listed while you type a name or number; choosing one shows what they owe and their recent bills.
  A new mobile number is offered for saving on the receipt (it is not saved silently). Bills never remove a saved customer's photo or opening balance.
- Contacts: "Pick from phone contacts" in Add customer (permission asked only when tapped).
- Database version 7 (additive: one new column `openingBalance` on `pos_customers`). The opening balance is kept on this phone only.

## Known limits (update 4)
- GitHub Actions run 37918973021 (repository shivtejfootwear/shivtej-owner-app, commit 069c8a5) succeeded: unit tests, debug APK,
  release APK, APK collection and the signature check all passed. The release APK was signed with the DEBUG key, because the keystore
  secrets are not set. The APK itself was not downloaded or installed, so it is not verified on a phone.
  Earlier runs failed on compile errors that were then fixed (SecretStore.kt, Theme.kt); see CHANGES_AND_TEST_REPORT.md section 2.
  Local tests: 21 standalone logic checks and 25 JVM tests (`LedgerTest` 6, `LedgerReturnsTest` 19) passed in the working environment.
- Not tested on a phone or against the live server: camera and gallery pickers, videos, notifications, the Bluetooth printer, online AI
  (no API key was available), photo / video / payment sync with Supabase.
- Reminders run with WorkManager, so they can be a few minutes late and battery saving can delay them. They are not exact alarms.
- Product videos are not shown on the website yet.
- Returns saved BEFORE this update kept the refund the screen proposed (the full value, even for udhaar sales). The ledger now records
  those refunds as cash given, so such a customer's balance can be higher than before. Nothing was changed in the saved records:
  check those customers in Customers > account ledger and correct the return if needed.
- Website payments made online outside the app are not known to the app. They show as owed until recorded (Receive payment > On account).
- Release signing: the original signing key is NOT available here. Without the four secrets (see section 3) the release APK is signed
  with the debug key, and it cannot install over an app signed with another key ("package conflicts"). Back up the data in the app,
  add the original key as the secrets (preferred), or uninstall the old app once and then install. Do not uninstall without a backup.

## Known limits (update 2)
- The Android code has not been compiled or run in the environment where it was written. The first GitHub build and a test on a real phone and the
  ATPOS printer are the real check. Please send the build log if it is red.
- Bluetooth, camera, photo upload and the live Supabase run were not tested in that environment.

## Configuration
Supabase URL and the publishable key are in `app/build.gradle.kts` (`buildConfigField`). No service-role key is used anywhere.

## Local build (optional)
Android Studio (JDK 17): open the folder, Build > Build APK(s).
Command line with Gradle 8.9: `gradle :app:assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`
