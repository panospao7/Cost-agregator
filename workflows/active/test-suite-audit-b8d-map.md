# G-B8D map + decisions (scout 2026-10-19; orchestrator decisions under the approved plan + standing approval; user: "Use the emulator")

AT = app/src/androidTest/java/com/yourname/expensetracker. Emulator: wave5-api35, serial emulator-5554, API 35. No release overlap on androidTest.

## B8-2 DatabaseMigrationTest (76 → 67 @Test)
- Delete these 9 tests. Each is always skipped, because no schema JSON exists for v1 or v6-10 (the lowest exported is 33.json):
  - :211 migrate_all_versions_from_1_to_51
  - :504 migrate_6_to_7_adds_payment_columns
  - :527 migrate_7_to_8_creates_budgets_table
  - :552 migrate_8_to_9_creates_scanned_receipts_table
  - :567 migrate_9_to_10_recreates_pending_reviews_with_status
  - :590 migration_preserves_expense_data
  - :621 migration_preserves_category_data
  - :649 foreign_key_constraints_maintained_after_migration
  - :852 migrate_1_to_34_full_chain
- No helpers are used only by these tests (testDb, helper, archivedMigrations and hasSchema are shared). Remove an import only if it becomes unused.
- KDoc fixes:
  - :219-224 says "v33 … to v114 (current)"; current is APP_DATABASE_SCHEMA_VERSION 151, so describe the test as the v33→v114 chain without "(current)".
  - :21-25 says "all 33 migrations", which is stale.
- Guards:
  - release_block_denylist :14: the class stays, with no @Ignore.
  - ignored_test_budget counts @Ignore/@Disabled only (hard-coded baseline 3), so there is no change.
  - migration_matrix reads production roots only, so there is no impact.
  - **No baseline change.**

## B8-3 DaoStressTest (20 @Test)
Replace the wall-clock and memory thresholds with exact correctness checks, and delete the timer and Runtime lines.
- :68 insert_1000: sum == 500500.0 and 1000 unique ids.
- :92 insert_10000: assertEquals 10000 and sum 495000.0.
- :206 query_5000_filtering: exact count computed from the generated dates (half-open), plus DESC order.
- :224 aggregate: total == 245000.0.
- :247 date_range: exact 7/30/90 counts (half-open), DESC order, and drop the "inclusive" comment.
- :388 memory: unique ids and max date.

Rename the speed-promising names: insert_1000_expenses_quickly, query_5000_expenses_with_filtering, date_range_queries_with_indices.

Hardening:
- :168 `maxRead >= 0` is vacuous. Replace it with non-decreasing reads, each ≤ 50.
- Make the concurrent tests (concurrent_write_operations, handles_concurrent_duplicate_checks, concurrent_read_and_write, concurrent_read_operations) really concurrent: runBlocking + async(Dispatchers.IO) + a CompletableDeferred start gate + withTimeout (pattern T CategoryDaoTest :133-155). Keep the same assertions or exact stronger ones; for the duplicates, exactly one id > 0 and the rest -1.
- Keep the expected values exact. Never loosen to bounds.

## B8-4 device part: NEW AT/data/database/dao/DaoDeviceSmokeTest.kt (5 cases)
Setup: copy FreshInstallIndexParityTest :59-74 — AndroidJUnit4, inMemoryBuilder, runBlocking, @OptIn(RestrictedExpenseDaoMutation::class), @Suppress("DEPRECATION_ERROR") (needed for getWeeklyTotalsForPeriod).

Cases:
1. UNIQUE dedupeKey: insertAtomic gives >0, then -1, and count 1 (mirrors DedupeKeyUniquenessRegressionTest :136-162).
2. FK RESTRICT: GroupMember delete with a GroupExpense paidById throws SQLiteConstraintException, and the member survives (mirrors GroupMemberDaoTest :121-150).
3. FK CASCADE: deleting a group removes its members (mirrors ExpenseGroupDaoTest :99-112).
4. NOCASE: getOrInsertByNameNoCase for "Travel" then "travel" returns the same row; count 1; getByName("TRAVEL") (mirrors CategoryDaoTest :117-130). The non-ASCII case is skipped.
5. localtime weekly: the cross-year week gives 1 bucket "2025-12-29", total 100.0, count 4 (mirrors ExpenseDaoTest :208-225; copy the localDateAtHourMs helper).

## Writers (sequential): W1 B8-2; W2 B8-3; W3 B8-4.
## Validation (serial)
1. connected-test-class DatabaseMigrationTest (67, 0 failures).
2. connected-test-class DaoStressTest (20).
3. connected-test-class DaoDeviceSmokeTest (5).
4. migration-tests.
5. registered-guard migration_matrix.
6. registered-guard ignored_test_budget.
Review: reviewer-strict plus room-migration-guardian (B8-2 is T3).
