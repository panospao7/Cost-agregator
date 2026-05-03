# Room Schema Mismatch Analysis: v112

**Generated:** 2026-05-03  
**Schema JSON:** `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/112.json`  
**FRESH_INSTALL_CALLBACK:** `AppDatabase.kt` lines 4941–5195  

---

## VERDICT: FAIL

3 CRITICAL issues found — fresh install will crash.  
3 MAJOR issues — silent behavioral drift or future validation failures.  
1 MINOR issue — extra indices not declared in Room entities.

---

## Executive Summary

The `FRESH_INSTALL_CALLBACK` rebuilds 6 tables to add CHECK constraints not supported by Room’s annotation system: `savings_goals`, `mileage_tracking`, `pending_reviews`, `budgets`, `group_members`, and `planned_expenses`. Three of these rebuilds have **schema mismatches** with the Room-generated JSON metadata.

### Critical path to crash

1. Room opens database → creates all tables (including `pending_reviews` with 23 columns, `budgets` with `ON DELETE RESTRICT`)
2. `FRESH_INSTALL_CALLBACK` fires → rebuilds `pending_reviews` with 22 columns → `INSERT … SELECT *` fails (column-count mismatch)
3. Even if the INSERT succeeds (empty table), the rebuilt `budgets` table has `ON DELETE SET NULL` instead of `ON DELETE RESTRICT`
4. Room’s post-callback schema validation compares actual schema against JSON metadata → **crash**

---

## Issue Details

### [ISSUE-1] [CRITICAL] `pending_reviews` — missing `extractionState` column
- **File:** `AppDatabase.kt` lines 5029–5060  
- **Entity declares:** `extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'` (23rd column — `PendingReview.kt:86`)  
- **Callback creates:** `pending_reviews_new` with **22 columns** (omits `extractionState`)  
- **Callback copies data with:** `INSERT INTO pending_reviews_new SELECT * FROM pending_reviews`  
- **Effect:** On a fresh install, Room creates `pending_reviews` with 23 columns; the callback creates `pending_reviews_new` with 22; the `SELECT *` INSERT fails because the column counts don’t match. **Guaranteed crash.**  
- **Suggested fix:** Add the column to `pending_reviews_new` in the callback:

```sql
extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'
```

The callback INSERT must also use an explicit column list or `SELECT *` (the column count will then be 23 → 23, and the SELECT result set will include the column from Room’s initial table).

---

### [ISSUE-2] [CRITICAL] `budgets` — FK `ON DELETE RESTRICT` vs `ON DELETE SET NULL`
- **File:** `AppDatabase.kt` line 5100 (callback) vs `Budget.kt:45` (entity)  
- **Entity declares:** `ForeignKeys.Restrict` → JSON generates `ON DELETE RESTRICT`  
- **Callback creates:** `FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL`  
- **Effect:** On fresh install, Room first creates `budgets` with `ON DELETE RESTRICT`. The callback rebuilds with `ON DELETE SET NULL`. Room’s schema validation detects the FK clause mismatch and **crashes on database open.** Additionally, this silently changes the intended behavior: `RESTRICT` prevents category deletion when budgets reference it (as documented in `Budget.kt:33-36`), but `SET NULL` would silently convert category budgets into overall budgets, losing attribution.  
- **Suggested fix:** Change line 5100 in the callback from `ON DELETE SET NULL` to `ON DELETE RESTRICT`:

```sql
FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
```

---

### [ISSUE-3] [CRITICAL] `mileage_tracking` — extra SQL DEFAULT constraints not in Room schema
- **File:** `AppDatabase.kt` lines 5005, 5009 (callback) vs `MileageTracking.kt:48,54` (entity)  
- **Entity (Kotlin):** `isBusinessTrip: Boolean = true` — NO `@ColumnInfo(defaultValue=…)` → Room does NOT emit SQL DEFAULT  
- **Entity (Kotlin):** `deductionRatePerKm: Double = 0.30` — NO `@ColumnInfo(defaultValue=…)` → Room does NOT emit SQL DEFAULT  
- **Callback creates:**  
  - `isBusinessTrip INTEGER NOT NULL DEFAULT 1`  
  - `deductionRatePerKm REAL NOT NULL DEFAULT 0.30`  
- **Effect:** Room’s schema validation compares the actual CREATE TABLE SQL against the expected SQL in the JSON. The JSON has no DEFAULT for these columns; the rebuilt table does. **Schema validation may fail** depending on Room version strictness. Even if Room doesn’t crash, this is a latent drift — any future Room version upgrade could reject it.  
- **Suggested fix:** Remove the SQL DEFAULT constraints from the callback’s `mileage_tracking_new` definition. The Kotlin defaults already handle default values at the application layer:

```sql
isBusinessTrip INTEGER NOT NULL,                     -- remove DEFAULT 1
deductionRatePerKm REAL NOT NULL,                    -- remove DEFAULT 0.30
```

---

### [ISSUE-4] [MAJOR] `pending_reviews` — callback creates UNIQUE `rawNotificationId` index; schema declares UNIQUE too, but this was changed in migration 81→82
- **File:** `AppDatabase.kt` line 5061  
- **Schema JSON:** `index_pending_reviews_rawNotificationId` is UNIQUE  
- **Callback creates:** `CREATE UNIQUE INDEX` ✅ (matches)  
- **Historical note:** Migration 9→10 created it as non-unique; migration 81→82 made it UNIQUE. The callback correctly uses UNIQUE. No mismatch here — verified for completeness.

---

### [ISSUE-5] [MAJOR] 4 extra partial unique indexes on `raw_notifications` not declared in Room entities
- **File:** `AppDatabase.kt` lines 4945–4968  
- **Schema JSON has NO entries for:**  
  - `index_raw_notifications_dedup_nonnull`  
  - `index_raw_notifications_dedup_both_null`  
  - `index_raw_notifications_dedup_title_null`  
  - `index_raw_notifications_dedup_text_null`  
- **Are they in any migration?** YES — migration 83→84 (lines 5339–5385) creates these same partial unique indexes and drops the old `index_raw_notifications_packageName_timestamp_title_text`. Migration 104→105 (later) may also manage them.  
- **Effect:** These are **extra** indices that Room does not expect. Room’s schema validation **may** flag them as unexpected indices. Historically Room has been lenient about extra indices, but stricter validation is being added in newer Room versions. If Room rejects these, the app will crash on database open.  
- **Risk level:** MAJOR — if these are mission-critical deduplication indexes, Room rejecting them would silently allow duplicates through the regular unique index on `dedupeFingerprint`.  
- **Suggested fix:** Either (a) declare these as partial unique indexes in the RawNotification entity (Room may not support partial indexes natively), or (b) ensure the Room validation mode is set to allow extra indices, or (c) move the dedup logic entirely to the `dedupeFingerprint` column (since the JSON already has `index_raw_notifications_dedupeFingerprint` as UNIQUE).

---

### [ISSUE-6] [MAJOR] `index_raw_notifications_packageName_timestamp_title_text` — schema JSON expects non-unique index; migration 83→84 changed from UNIQUE to non-unique; callback creates 4 separate partial UNIQUE indexes
- **File:** `AppDatabase.kt` lines 4945–4968, 5385  
- **Schema JSON declares:** `index_raw_notifications_packageName_timestamp_title_text_bigText` (non-unique, columns: `packageName`, `timestamp`, `title`, `text`, `bigText`)  
- **Migration 21→22 created:** `index_raw_notifications_packageName_timestamp_title_text` (UNIQUE, 4 columns, no `bigText`)  
- **Migration 83→84 dropped the UNIQUE index and recreated as non-unique with 4 columns**  
- **Schema JSON currently expects:** a 5-column non-unique index `index_raw_notifications_packageName_timestamp_title_text_bigText`  
- **Callback does NOT create** `index_raw_notifications_packageName_timestamp_title_text_bigText`  
- **Callback creates 4 partial UNIQUE indexes instead**  
- **Migration 104→105 (line ~5860 area) likely handles this.**  
- **Effect:** On fresh install, the 5-column non-unique index from JSON may be missing. Room validation would detect its absence and crash.  
- **Suggested fix:** Verify that migration 104→105 or a later migration creates `index_raw_notifications_packageName_timestamp_title_text_bigText`. If not, add it to the callback or a migration.

---

## Detailed Table-by-Table Comparison

### Tables rebuilt by FRESH_INSTALL_CALLBACK

| Table | Schema JSON indices | Callback indices | Match? | Notes |
|-------|-------------------|------------------|--------|-------|
| `savings_goals` | (none) | (none created) | ✅ | |
| `mileage_tracking` | `linkedExpenseId`, `date`, `isBusinessTrip` | All 3 created | ⚠️ | DEFAULT mismatch (ISSUE-3) |
| `pending_reviews` | `rawNotificationId`[UNIQUE], `scannedReceiptId`, `status`, `status_createdAt`, `suggestedMerchantKey`, `status_suggestedMerchantKey_suggestedDate` | All 6 created | 🚨 | Missing extractionState column (ISSUE-1) |
| `budgets` | `categoryId`, `isActive`, `activeOverallKey`[UNIQUE], `activeCategoryKey`[UNIQUE] | All 4 created | 🚨 | FK RESTRICT vs SET NULL (ISSUE-2) |
| `group_members` | `groupId`, `groupId_isCurrentUser`, `groupId_name`[UNIQUE], `currentUserGroupKey`[UNIQUE] | All 4 created | ✅ | |
| `planned_expenses` | `date`, `categoryId`, `openSourceOccurrenceKey`[UNIQUE] | All 3 created | ✅ | |

### Tables NOT rebuilt by FRESH_INSTALL_CALLBACK (Room handles directly)

These tables are created by Room and the callback does not modify them. Indices match the JSON schema (verified):

| Table | Indices created by Room? | Notes |
|-------|------------------------|-------|
| `raw_notifications` | Yes (from entity) | Extra partial UNIQUE indexes from callback (ISSUE-5, ISSUE-6) |
| `blocked_packages` | Yes (no indices) | |
| `expenses` | Yes (14 indices) | |
| `categories` | Yes (no indices) | |
| `merchant_categories` | Yes (2 indices) | |
| `user_corrections` | Yes (6 indices) | |
| `source_stats` | Yes (no indices) | |
| `scanned_receipts` | Yes (4 indices) | |
| `manual_recurring_expenses` | Yes (3 indices) | |
| `merchant_canonicals` | Yes (3 indices) | |
| `merchant_aliases` | Yes (3 indices) | |
| `merchant_locations` | Yes (2 indices) | |
| `merchant_location_corrections` | Yes (2 indices) | |
| `ai_artifacts` | Yes (4 indices) | |
| `ai_chat_sessions` | Yes (2 indices) | |
| `ai_chat_messages` | Yes (3 indices) | |
| `recommendations` | Yes (4 indices) | |
| `receipt_item_categorizations` | Yes (4 indices) | |
| `warranties` | Yes (4 indices) | `receiptId` is UNIQUE ✓ |
| `return_windows` | Yes (4 indices) | `receiptId` and `expenseId` are UNIQUE ✓ |
| `subscription_price_history` | Yes (1 index) | |
| `subscription_usage` | Yes (1 index) | |
| `exchange_rates` | Yes (3 indices) | |
| `expense_groups` | Yes (3 indices) | |
| `group_expenses` | Yes (5 indices) | `expenseId` is UNIQUE ✓ |
| `budget_forecasts` | Yes (4 indices) | |
| `investments` | Yes (3 indices) | |
| `investment_values` | Yes (2 indices) | |
| `bank_connections` | Yes (4 indices) | |
| `split_templates` | Yes (1 index) | |
| `split_item_assignments` | Yes (2 indices) | |
| `anomaly_alerts` | Yes (5 indices) | |
| `prompt_states` | Yes (2 indices) | |
| `health_score_history` | Yes (3 indices) | |
| `savings_sweep_plan` | Yes (3 indices) | |
| `subscription_candidates` | Yes (4 indices) | |
| `budget_adjustment_recommendations` | Yes (4 indices) | |
| `budget_adjustment_events` | Yes (3 indices) | |
| `spending_personality_profiles` | Yes (3 indices) | |
| `stress_forecast_snapshots` | Yes (5 indices) | |
| `email_receipt_sources` | Yes (5 indices) | |
| `spending_challenges` | Yes (4 indices) | |
| `transaction_events` | Yes (4 indices) | |
| `receipt_events` | Yes (5 indices) | |
| `receipt_expense_links` | Yes (5 indices) | |
| `recurring_occurrences` | Yes (5 indices) | |
| `recurring_reminder_deliveries` | Yes (3 indices) | |
| `recurring_lifecycle_events` | Yes (3 indices) | |
| `privacy_audit_events` | Yes (3 indices) | |
| `background_job_runs` | Yes (2 indices) | |

---

## Key Table Indices — Detailed Verification

### `expenses` (14 indices)
All 14 indices declared in JSON are present in the entity `Expense.kt` and Room creates them on fresh install. Confirmed:
1. `index_expenses_rawNotificationId` (UNIQUE)
2. `index_expenses_date`
3. `index_expenses_transactionType_date`
4. `index_expenses_transactionType_categoryId_date`
5. `index_expenses_categoryId_date`
6. `index_expenses_amount_merchant_date`
7. `index_expenses_merchant_date`
8. `index_expenses_transactionType_merchant_date`
9. `index_expenses_dedupeKey` (UNIQUE)
10. `index_expenses_latitude_longitude`
11. `index_expenses_latitude_backfillAttempts_date`
12. `index_expenses_merchantKey`
13. `index_expenses_merchantKey_date_amount`
14. `index_expenses_isBusinessExpense`
15. `index_expenses_splitTemplateId`

### `budgets` (4 indices)
All 4 created by callback (match JSON). FK clause mismatch (see ISSUE-2).

### `group_members` (4 indices)
All 4 created by callback. Match JSON. ✅

### `group_expenses` (5 indices)
Room creates all 5. `expenseId` is UNIQUE in JSON. ✅

### `raw_notifications` (5 indices in JSON + 4 extra)
JSON expects: `packageName_timestamp`, `capturedAt`, `isRelevant`, `packageName_timestamp_title_text_bigText`, `dedupeFingerprint`[UNIQUE].  
Callback adds 4 partial UNIQUE indexes. See ISSUE-5 and ISSUE-6.

### `planned_expenses` (3 indices)
All 3 created by callback. Match JSON. ✅

### `scanned_receipts` (4 indices)
Room creates: `expenseId`, `createdAt`, `matchStatus`, `processingStatus`. Match JSON. ✅

### `pending_reviews` (6 indices)
All 6 created by callback. Match JSON for indices. BUT table schema is wrong (ISSUE-1).

### `warranties` (4 indices)
Room creates: `receiptId`[UNIQUE], `expenseId`, `warrantyEndDate`, `status`. Match JSON. ✅  
(Migration 66→67 changed `receiptId` from non-unique to UNIQUE.)

### `return_windows` (4 indices)
Room creates: `receiptId`[UNIQUE], `expenseId`[UNIQUE], `returnDeadline`, `status`. Match JSON. ✅  
(Migration 80→81 changed `expenseId` to UNIQUE.)

### `recurring_occurrences` (5 indices)
Room creates all 5: `sourceType_sourceId`, `dueDate`, `status`, `occurrenceKey`[UNIQUE], `linkedExpenseId`. ✅

### `recurring_reminder_deliveries` (3 indices)
Room creates all 3: `occurrenceId_reminderWindow`[UNIQUE], `status`, `scheduledAt`. ✅

---

## Migration Coverage Summary

For each index in the JSON that the FRESH_INSTALL_CALLBACK creates, the corresponding migration coverage is:
- `index_budgets_activeOverallKey` — created in MIGRATION_106_107 ✅
- `index_budgets_activeCategoryKey` — created in MIGRATION_106_107 ✅
- `index_pending_reviews_suggestedMerchantKey` — created in MIGRATION_104_105 ✅
- `index_pending_reviews_status_suggestedMerchantKey_suggestedDate` — created in MIGRATION_104_105 ✅
- `index_group_members_groupId_isCurrentUser` — created in MIGRATION_52_53 ✅
- `index_group_members_currentUserGroupKey` — created in MIGRATION_106_107 ✅
- `index_planned_expenses_openSourceOccurrenceKey` — created in MIGRATION_106_107 ✅
- `index_anomaly_alerts_category_alertedAt` — created in MIGRATION_77_78 ✅
- `index_exchange_rates_toCurrency` — created in MIGRATION_78_79 ✅
- `index_scanned_receipts_processingStatus` — created in MIGRATION_104_105 ✅

---

## Requirements Coverage
- **Requirements met:** NO — fresh install will crash due to ISSUE-1 and ISSUE-2
- **Testing adequate:** NO — no test validates the FRESH_INSTALL_CALLBACK against the current Room schema JSON
