# Shivtej Owner App - Update 5: theme, Quick actions, customers and dues

## U5.1 What changed in this update
| # | Request | What was done | Where |
|---|---------|---------------|-------|
| 1 | Dashboard theme settings | The theme screen already existed (Settings > Theme & wallpaper); it was not removed. A "Theme" button is now on the Dashboard. | `ui/Dashboard.kt`, `ui/Theme.kt` |
| 2 | Light / Dark / Custom | Added **Custom**: the owner picks a background colour (8 swatches). Text, cards and buttons switch to dark or light so they stay readable. Existing System / Light / Dark / Red & White kept. | `ui/Theme.kt`, `data/Prefs.kt` |
| 3 | Wallpaper from gallery, fit/crop, readability | Existing gallery wallpaper kept. Added "Fill screen" or "Whole picture" and the existing cover slider. Saved on the phone. | `ui/Theme.kt` |
| 4 | Quick actions | Pencil icon on the Dashboard opens an editor: add, remove, reorder (arrows), restore default. 16 shortcuts available, including the 8 original ones. Saved on the phone. | `ui/QuickActions.kt`, `ui/Dashboard.kt`, `data/Prefs.kt` |
| 5 | Customer outstanding | Dashboard "Customer dues" card, same figures as the customer ledgers. The Customers screen sorts by Most spent, Highest due or Oldest due, and shows "since <date>". Opening: `Dues` screen from the dashboard. | `util/Ledger.kt`, `ui/Customers.kt`, `ui/Dashboard.kt`, `MainActivity.kt` |
| 6 | Opening balance | A per-customer opening balance (dues before the app), entered in Add/Edit customer. Shown as the first ledger row. Never changed by sales; kept when the server copy is synced. New DB column (version 6 to 7, additive). | `data/Db.kt` (MIGRATION_6_7), `util/Ledger.kt`, `data/Repo.kt` |
| 7 | Contacts | "Pick from phone contacts" in Add customer (asks permission only when tapped). Pasted "+91 98765 43210" is reduced to 10 digits. | `ui/Customers.kt`, `ui/Billing.kt`, `AndroidManifest.xml` |
| 8 | Billing lookup | While typing a name (2+ letters) or mobile digits, matching saved customers are listed under the fields. Tap one to load name, city, outstanding ("Owes now, before this bill") and the last 3 bills. | `ui/Billing.kt` |
| 9 | Keep existing data | Fixed an existing bug: saving a bill rebuilt the customer row without the profile photo, so the photo was lost. The photo and opening balance are now kept. | `data/Repo.kt` |

## U5.2 Verification (this update)
| Check | Result |
|---|---|
| JVM ledger tests (`LedgerTest` 6, `LedgerReturnsTest` 19, `LedgerOpeningTest` 8 new) | **33 of 33 passed** (local, JUnit 4.13.2, Kotlin 2.0.21) |
| Android compile (Kotlin, Compose, Room) and the APK build | **Not run yet.** There is no Android SDK here. The GitHub Actions run decides this. See the run result in the final message. |
| Phone tests: theme on each screen, wallpaper, Quick actions after restart, contacts, outstanding after a restart | **NOT RUN.** Needs the installed APK on a phone. |
| Live Supabase sync of opening balances | **NOT RUN.** The opening balance is on this phone only (see U5.4). |

## U5.3 Test steps for the owner (to tick on the phone)
1. Dashboard: tap the pencil, remove one shortcut, add "Customer Dues", move it up, tap Done. Close and reopen the app: the order stays.
2. Settings > Theme & wallpaper (or the Dashboard "Theme" button): choose Custom and a background. Close and reopen: it stays.
3. Add customer: enter a name and mobile, and an opening balance of 500. Open the customer: "Outstanding 500", first ledger row "Opening balance".
4. New Bill: type the first 3 digits of that mobile. Its name is listed below. Tap it: name, city, "Owes now" and recent bills appear.
5. Record a payment of 200 on account: outstanding becomes 300. Dashboard "Customer dues" shows the same total as the sum of the customer ledgers.
6. Enter the same mobile again in Add customer: the app offers "Open customer" and does not create a second one.

## U5.4 Limits and decisions
- **Opening balance is on this phone only.** The server (website and Supabase) does not know it yet, so website reports do not include it.
  The SQL for the server column has not been written; say if you want it.
- **Oldest due** is the oldest sale that still has an amount due (not a strict first-in-first-out match of payments to bills).
- **Saving a new customer from a bill** is now an offer, not automatic. After a bill for a mobile number that is not saved yet, the receipt asks "Save this customer?" with the name and city filled in (change them first if needed). A bill for an unsaved number still counts in the dues and in the Customers list. An already saved customer keeps the name and city on the phone; the bill only fills blanks.
- **Wallpaper on every screen** needs "All main screens" in the wallpaper section; the default is the Dashboard only (as before).
- **Contrast:** the colour rules are coded for the Custom theme and the cards, but the visual result was not seen on a phone, so please check it on the phone.
- **Signing:** unchanged. The release APK is still signed with the debug key until the original keystore secrets are added (see section 5).

---

# Shivtej Owner App - Final update: change summary and test report

## 1. What to do, in order
1. Supabase SQL, in the SQL editor, once each, in this order: `supabase/owner_update_2_migration.sql`,
   `supabase/owner_update_3_supplier_bill_file.sql`, `supabase/owner_update_4_media_ledger_tasks.sql`.
   The last update made no change to the SQL files. Do not run `owner_app_setup.sql` again (it is only for a new project).
2. Build with GitHub Actions (`.github/workflows/build-apk.yml`, repository `shivtejfootwear/shivtej-owner-app`). The run tests first,
   then builds the release and debug APKs. Last run: 37918973021, success (see section 2).
   For a release key that lets updates install, add the four secrets described in `README.md` section 3. They are not set yet.
3. Before installing over the old app: Settings > Backup, then sync. If the old app was signed with a different key, it must be
   uninstalled once (see section 5). Then install the new APK, sign in with the owner code, and wait for the sync to finish.

## 2. Verification status (what was really run)
| Check | Result |
|---|---|
| Standalone logic checks (`tools/logic-check`): real `BillMath.kt`, `Ledger.kt`, `Assistant.kt` with stub data, Kotlin 2.0.21 | **21 of 21 passed** (local) |
| JVM unit tests `LedgerTest` (6, original rules) and `LedgerReturnsTest` (19, returns, refunds, exchanges, website orders, cash cap, no double counting) | **25 of 25 passed** (local, JUnit 4.13.2) |
| GitHub Actions run 37918973021 on commit 069c8a5: step "Unit tests" (`./gradlew :app:testDebugUnitTest`) | **Passed** (step result only; the per-test log is not readable from here) |
| GitHub Actions run 37918973021: "Build the debug APK" and "Build the release APK" (Gradle, Kotlin compile, KSP, Room) | **Passed** |
| GitHub Actions run 37918973021: "Collect the APKs", "Check the APK signatures", artifact uploads | **Passed**. Artifacts `Shivtej-Owner-App` (release, about 23 MiB) and `Shivtej-Owner-App-debug` (about 29 MiB) were uploaded. |
| Signing of the release APK | **Debug key.** The run's warning says: "No release keystore secret (SHIVTEJ_KEYSTORE_BASE64). The release APK will be signed with the debug key." The original key is not available. |
| APK installed and run on a phone | **NOT DONE.** The artifacts could not be downloaded from this environment (GitHub returned Forbidden), so the APK was not inspected or installed. |
| GitHub push | **Done.** Commits on `main` of `shivtej-owner-app`, latest 069c8a5. The website repository `shivtej-footwear` was not changed. |
| Phone tests (camera, gallery, video, notifications, printer), live Supabase sync, online AI with a real key | **NOT RUN** |

**Earlier CI runs** (the reason for each change):
- Set up Android SDK with `android-actions/setup-android@v3` failed in about 20 seconds. Replaced by the preinstalled SDK with a verify step.
- Kotlin compile failed on `SecretStore.kt` (expression-body return) and on `Theme.kt` (missing `horizontalScroll` and `luminance` imports).
  Fixed in commit 219865d; the unit tests then passed.
- Run 37918107176 (commit 219865d): all build steps passed, but "Show compile and test errors" failed because its `grep` returned
  no match and `pipefail` made the step fail. Fixed in commit 069c8a5. Run 37918973021 passed everywhere.

The JVM tests use stub data classes with the same field names as the Room entities. They test the rules, not the Room mapping.

## 3. Feature status
Status meanings: **Code done** = written and read through; **Logic checked** = the pure rules were run in the tests above;
**Not run** = not compiled or run on a phone or against the live server.

| # | Feature | Status | Where |
|---|---------|--------|-------|
| 1 | Dashboard colours and readability | Code done. Dashboard cards use the theme surface; coloured figures are lightened on dark backgrounds. Reports and orders screens still use some fixed colours. | `ui/Dashboard.kt`, `ui/Theme.kt` |
| 2 | Theme: Light / Dark / Red & White / System, accent, 6 built-in wallpapers, gallery wallpaper, preview, overlay, remove, restore default | Code done. Not run. | `ui/Theme.kt`, `data/Prefs.kt`, `MainActivity.kt` |
| 3a | Offline assistant answers from local data | **Logic checked** (21 standalone checks). UI code done. | `util/Assistant.kt`, `ui/AssistantSettings.kt` |
| 3b | Optional online AI, key in encrypted storage, never in source | Code compiled in CI. Not run (no key). | `util/AiClient.kt`, `util/SecretStore.kt` |
| 4 | Product videos (max 25 MB): add, preview, replace, remove, sync | Code done. Not run. Not shown on the website yet. | `ui/ProductEdit.kt`, `util/Videos.kt`, `sync/SyncManager.kt` |
| 5 | Customer udhaar: outstanding, payments (full / partial / on account), ledger, returns and refunds, website orders | **Logic checked** (25 JVM tests). UI code compiled in CI; not run on a phone. | `util/Ledger.kt`, `data/LedgerInput.kt`, `data/Repo.kt`, `ui/Customers.kt`, `ui/CustomerLedger.kt`, `ui/Returns.kt` |
| 6 | Bills from the customer detail (purchase history and ledger rows open the receipt) | Code done. Not run. | `ui/Customers.kt`, `ui/CustomerLedger.kt` |
| 7 | Supplier bill photo / PDF | Carried over from update 3. Not re-tested in this update. | `ui/Suppliers.kt`, `owner_update_3` |
| 8 | Customer and supplier photos: select, preview, replace, remove, sync | Code done. Not run. | `ui/CustomerLedger.kt`, `ui/Customers.kt`, `ui/Suppliers.kt` |
| 9 | Tasks and reminders with Android notifications | Code done. Not run. Tasks are on this phone only. | `ui/Tasks.kt`, `util/Reminders.kt` |
| 10 | Database and sync safety: additive migration 5 to 6, no reset | Code done. The migration and Room defaults were checked by reading. Not run on a phone. | `data/Db.kt`, `data/DbGuard.kt`, `util/BackupRestore.kt` |
| 11 | Release signing that can stay the same across updates (optional secrets, debug-key fallback) | Code compiled in CI and the build passed. Signed with the debug key until the owner's key is added (section 5). | `app/build.gradle.kts`, `.github/workflows/build-apk.yml` |

## 4. Returns, refunds and website orders (final rules)
These rules are in `util/Ledger.kt` and are the same in the customer outstanding, the purchase history and the account ledger.
- **Shop bill:** charge = bill total. Received at sale = the amount paid on the bill (old bills with no payment record count as paid).
- **Website order (not cancelled):** charge = order total. Received at sale = the whole total only when the payment status is "Paid".
  The website writes "COD - pending" or "Pending payment", so such an order stays owed until the owner records the money
  (Receive payment > On account). Online payments that the app does not know about must be recorded the same way.
- **Return:** the goods handed back reduce what the customer owes (their value at the sale price).
- **Exchange:** the replacement goods are added to what the customer owes.
- **Refund (cash given back):** raises what the customer owes again. The cash limit is the customer's overpayment over their whole account
  (all sales and payments on account), so a udhaar return reduces the balance instead of paying cash out, and a customer who owes on
  another sale gets no cash refund. The same limit is checked when the return is saved
  (`Repo.processReturn`). An exchange where the customer pays the difference is recorded as money received.
- **Payments:** a payment tied to a bill or order, or on account, reduces the total owed. Each payment is counted once.
- **Rejected records** (refused by the server) are kept for history and never counted, as before.
- **Order status "Returned" alone does not change the balance.** Record the return on the order so that the goods and the money are counted once.
- **Duplicates:** a return cannot take back more than was sold on that sale (the return screen counts earlier returns). Each payment,
  return and refund is one saved record and is counted once.
- **No database change.** The rules read the existing bills, orders, payments and returns. No saved record is rewritten.

## 5. Signing and the install conflict
- **Original signing key: NOT AVAILABLE.** Searched this environment for keystores (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`): none for this app.
  The GitHub secrets cannot be read from here, and the key of the installed phone app cannot be extracted. No key was changed, invented or substituted.
  The signing-dependent step is therefore NOT done. The release build keeps the debug-key fallback until the key is supplied.
- The release build now uses a release key when the secrets `SHIVTEJ_KEYSTORE_BASE64`, `SHIVTEJ_KEYSTORE_PASSWORD`, `SHIVTEJ_KEY_ALIAS` and
  `SHIVTEJ_KEY_PASSWORD` are set in GitHub. The keystore is written to a temporary file during the run only and is never in the repository or the ZIP.
  Without the secrets the release APK is signed with the debug key, as before (the run shows a warning).
- **Decision needed from the owner:**
  1. If the owner still has the key the installed app was signed with, add it as the secrets. Updates then install over the old app.
  2. If the key is lost, a new key must be created. The old app must be uninstalled once. Back up first (Settings > Backup).
     Uninstalling removes the data stored on the phone; the Supabase data is kept on the server.

## 6. Files changed in this final pass
- `util/Ledger.kt`: rewritten with the rules in section 4. Existing function names kept; new optional inputs have defaults.
- `data/LedgerInput.kt` (new): turns saved orders and returns into ledger inputs.
- `data/Db.kt`: two read queries added (`allOrders`, `allReturns`). No table or column change.
- `data/Repo.kt`: payment check uses the full ledger; return save checks the cash cap; `maxCashRefund` added.
- `ui/Returns.kt`: refund default limited to the cash cap, with a note on the screen.
- `ui/Customers.kt`, `ui/CustomerLedger.kt`, `util/Assistant.kt`, `ui/AssistantSettings.kt`: pass orders and returns to the ledger.
- `app/src/test/java/com/shivtej/owner/util/LedgerReturnsTest.kt` (new): 19 JVM tests.
- `app/build.gradle.kts`: optional release signing from environment variables; debug-key fallback unchanged.
- `.github/workflows/build-apk.yml`: unit tests first, lint (report only), debug and release APKs, keystore from secrets, test report artifact.
  A copy is in `github-workflow-copy/`. The workflow ran successfully in run 37918973021.
- `util/SecretStore.kt`, `ui/Theme.kt`: compile fixes found by CI (see section 2).
- `README.md`, `CHANGES_AND_TEST_REPORT.md`: updated.
- Repository: `shivtejfootwear/shivtej-owner-app`, the private Owner App repository (not the website). Commits from this pass: 4ed6c84, bb77832, d76c3f8, 219865d, 069c8a5.

## 7. Known problems and limits
0. **Open decision for the owner:** (a) the original release key, added as the GitHub secrets, so that updates install over the installed app;
   or (b) if the key is lost, a new key, with the installed app uninstalled once after a backup. Nothing was uninstalled or changed.
1. **Compiled and built in CI, not run on a phone.** The APK was built and signed with the debug key; it was not downloaded or installed here.
2. **Old returns:** returns saved before this update kept the refund the screen proposed (the full value, even on udhaar). The ledger now reads
   those as cash given back, so such a customer's balance can be higher. Nothing was changed in the saved records. Check them in
   Customers > account ledger.
3. **Website payments made online** are not known to the app. They show as owed until recorded.
4. **Release signing** needs the owner's decision (section 5).
5. **Not tested on a phone:** camera and gallery, video preview, notifications, the Bluetooth printer, online AI with a real key,
   photo / video / payment sync with the live Supabase project.
6. **Reminders** use WorkManager, so they can be a few minutes late.
7. **Tasks** are on this phone only.
8. **Videos** are not shown on the website yet.
9. **Supplier bill photo / PDF** was not re-tested in this update.
10. **Stray file:** `build-apk.yml` in the repository root is not used by the build. It was left unchanged; it can be deleted once you confirm.
11. **Website repository access:** the GitHub App still lists `shivtej-footwear`. Remove it from the app's repository access on GitHub.
