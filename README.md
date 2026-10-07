# Shivtej Footwear - Owner App (native Android, Kotlin + Jetpack Compose)

Private owner/admin POS app. Offline-first (Room database) with a sync queue to the same Supabase project the website uses.
Website, Owner App and POS all share ONE stock: every stock change is an atomic, idempotent change on the server.

## 1. Supabase (once) - run the SQL in this order
1. Open `supabase/owner_app_setup.sql`. Change `CHANGE-ME-1234` (STEP 0) to your own secret owner code, paste the whole file into
   Supabase > SQL Editor > Run. The last result line must say `OK: owner code is set ...`.
   (Re-running the file never resets the code. To change it later use the one-line statement shown in STEP 0.)
2. Open `supabase/website_sync.sql`, paste, Run. (Website functions + `admin_users` table.)
3. Optional, after testing the website and the app once: the commented STEP B at the end of `website_sync.sql` (row-level security).

## 2. Website
Upload `website/index-V4.4-OWNER-SYNC.html` in place of the current website file (your original V4.3 is kept in `website/`).
It reads products, colours, sizes and live stock from Supabase, places orders through `site_place_order` (stock checked and deducted
on the server), and its Billing / Add Product screens write to the shared database after you type the owner code once per session.

## 3. Build the APK on GitHub (no coding)
1. Create a GitHub repository and upload this whole folder (including the hidden `.github` folder).
2. Open the repository > Actions > "Build Shivtej Owner APK" (it also starts automatically on upload).
3. When it turns green, open the run > Artifacts > download **Shivtej-Owner-App** (zip) > extract `Shivtej-Owner-App.apk`.
4. Copy the APK to the phone and install it (allow "install unknown apps").
If the build is red, open the failed step, copy the red error lines and send them back - they are fixed from the log.

## 4. First start
Enter the same owner code you put in the SQL. The app downloads data and then keeps syncing by itself (every 15 s while open,
immediately after each change, and in the background about every 15 min).

## What is inside
Dashboard, Products (add / edit / delete, colours with their own sizes, stock, barcode and photos, active = visible on website),
Inventory + stock history, POS billing with camera barcode / QR scan, receipts (view / share / PDF / print), online orders with
status + courier + tracking, **returns & exchanges** (from any order or bill, with refund and optional restock), customers,
shipping, reports (with refunds / net revenue), offline AI assistant, settings (store info, owner code, PIN, sync, backup / restore, clear cache).

## Configuration
Supabase URL and the publishable key are in `app/build.gradle.kts` (`buildConfigField`). No service-role key is used anywhere.

## Local build (optional)
Android Studio (JDK 17): open the folder, Build > Build APK(s).
Command line with Gradle 8.9: `gradle :app:assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`
