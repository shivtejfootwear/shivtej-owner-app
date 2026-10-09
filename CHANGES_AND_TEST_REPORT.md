# Shivtej Owner App - Final update: change summary and test report

## 1. What to do, in order
1. Supabase SQL, in the SQL editor, once each, in this order: `supabase/owner_update_2_migration.sql`,
   `supabase/owner_update_3_supplier_bill_file.sql`, `supabase/owner_update_4_media_ledger_tasks.sql`.
   The last update made no change to the SQL files. Do not run `owner_app_setup.sql` again (it is only for a new project).
2. Build with GitHub Actions (`.github/workflows/build-apk.yml`). The run tests first, then builds the release and debug APKs.
   For a release key that lets updates install, add the four secrets described in `README.md` section 3.
3. Before installing over the old app: Settings > Backup, then sync. If the old app was signed with a different key, it must be
   uninstalled once (see section 5). Then install the new APK, sign in with the owner code, and wait for the sync to finish.

## 2. Verification status (what was really run)
| Check | Result |
|---|---|
| Standalone logic checks (`tools/logic-check`): real `BillMath.kt`, `Ledger.kt`, `Assistant.kt` with stub data, Kotlin 2.0.21 | **21 of 21 passed** |
| Total JVM tests (`LedgerTest` + `LedgerReturnsTest`) | **25 of 25 passed** |
| JVM unit tests `LedgerTest` (6 tests, original rules) | **6 of 6 passed** |
| JVM unit tests `LedgerReturnsTest` (19 tests: returns, refunds, exchanges, website orders, "Returned" status, paid orders, on-account payments, rejected records, cash cap over the whole account, no double counting, ledger balance = customer net) | **19 of 19 passed** |
| Android compile (Kotlin, Compose, Room with KSP) | **NOT RUN** - no Android SDK in the working environment |
| Gradle unit tests (`./gradlew :app:testDebugUnitTest`) | **NOT RUN** - same reason; the GitHub workflow runs them as its first step |
| APK build | **NOT BUILT - NOT VERIFIED** |
| GitHub push | **NOT DONE** - no GitHub account is linked to this session (the GitHub token is invalid) and the repository name (owner/repo) was not given |
| GitHub Actions run | **NOT RUN** - it can only run after the push |
| Android build endpoints (Google Maven, dl.google.com) | **Blocked by the proxy policy** (403 on CONNECT). Not circumvented |
| Phone tests (camera, gallery, video, notifications, printer), live Supabase sync, online AI with a real key | **NOT RUN** |

The JVM tests were compiled with the Kotlin compiler bundled in the local Gradle installation and run with JUnit 4.13.2. They use
stub data classes with the same field names as the Room entities, so they test the rules, not the Room mapping.

## 3. Feature status
Status meanings: **Code done** = written and read through; **Logic checked** = the pure rules were run in the tests above;
**Not run** = not compiled or run on a phone or against the live server.

| # | Feature | Status | Where |
|---|---------|--------|-------|
| 1 | Dashboard colours and readability | Code done. Dashboard cards use the theme surface; coloured figures are lightened on dark backgrounds. Reports and orders screens still use some fixed colours. | `ui/Dashboard.kt`, `ui/Theme.kt` |
| 2 | Theme: Light / Dark / Red & White / System, accent, 6 built-in wallpapers, gallery wallpaper, preview, overlay, remove, restore default | Code done. Not run. | `ui/Theme.kt`, `data/Prefs.kt`, `MainActivity.kt` |
| 3a | Offline assistant answers from local data | **Logic checked** (21 standalone checks). UI code done. | `util/Assistant.kt`, `ui/AssistantSettings.kt` |
| 3b | Optional online AI, key in encrypted storage, never in source | Code done. Not run (no key). | `util/AiClient.kt`, `util/SecretStore.kt` |
| 4 | Product videos (max 25 MB): add, preview, replace, remove, sync | Code done. Not run. Not shown on the website yet. | `ui/ProductEdit.kt`, `util/Videos.kt`, `sync/SyncManager.kt` |
| 5 | Customer udhaar: outstanding, payments (full / partial / on account), ledger, returns and refunds, website orders | **Logic checked** (18 JVM tests). UI code done, not compiled. | `util/Ledger.kt`, `data/LedgerInput.kt`, `data/Repo.kt`, `ui/Customers.kt`, `ui/CustomerLedger.kt`, `ui/Returns.kt` |
| 6 | Bills from the customer detail (purchase history and ledger rows open the receipt) | Code done. Not run. | `ui/Customers.kt`, `ui/CustomerLedger.kt` |
| 7 | Supplier bill photo / PDF | Carried over from update 3. Not re-tested in this update. | `ui/Suppliers.kt`, `owner_update_3` |
| 8 | Customer and supplier photos: select, preview, replace, remove, sync | Code done. Not run. | `ui/CustomerLedger.kt`, `ui/Customers.kt`, `ui/Suppliers.kt` |
| 9 | Tasks and reminders with Android notifications | Code done. Not run. Tasks are on this phone only. | `ui/Tasks.kt`, `util/Reminders.kt` |
| 10 | Database and sync safety: additive migration 5 to 6, no reset | Code done. The migration and Room defaults were checked by reading. Not run on a phone. | `data/Db.kt`, `data/DbGuard.kt`, `util/BackupRestore.kt` |
| 11 | Release signing that can stay the same across updates (optional secrets, debug-key fallback) | Code done. Not run. Needs the owner's key (section 5). | `app/build.gradle.kts`, `.github/workflows/build-apk.yml` |

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
- `app/src/test/java/com/shivtej/owner/util/LedgerReturnsTest.kt` (new): 12 JVM tests.
- `app/build.gradle.kts`: optional release signing from environment variables; debug-key fallback unchanged.
- `.github/workflows/build-apk.yml`: unit tests first, lint (report only), debug and release APKs, keystore from secrets, test report artifact.
  A copy is in `github-workflow-copy/`. The YAML was parsed and is valid. The workflow has not been run.
- `README.md`, `CHANGES_AND_TEST_REPORT.md`: updated.

## 7. Known problems and limits
0. **Blockers for the final step:** (a) the original release key (or permission to create a new one with an uninstall of the old app);
   (b) the GitHub account connection and the repository owner/repo name, needed for the push and the Actions run.
1. **Not compiled on Android.** The Android screens and `Repo.kt` were checked by reading only. The first real compile is the GitHub build.
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
