# Batch 03 — androidtest

Scope: instrumented Room migration tests + DAO/constraint/parity androidTests + one worker androidTest (Segments 2, 3, 4, 6, 7, 9, 16, 19, 20, 24, 34, 35, 12) · Files: 28 · LOC: 12,328 · 373 @Test, 0 @Ignore

Reviewer notes:
- CI reality: `connectedDebugAndroidTest` is NOT run on PRs. Two jobs in `.github/workflows/ci.yml` run it only on `main`/`master` pushes or `workflow_dispatch`, both with `continue-on-error: true` (non-blocking, "flaky on emulators" comment, ci.yml:241). `instrumented-tests` (ci.yml:236) runs all 28 files; `migration-proof` (ci.yml:300) runs only `--tests "*DatabaseMigration*"` → DatabaseMigrationTest + DatabaseMigrationMatrixTest only. **MigrationContractTest and all 24 dao/ files are effectively manual-only in CI.**
- Migration policy consistency (checked against `docs/architecture/LEGAL_PATHS.md` "Financial Rescue Path" + `app/src/main/java/com/yourname/expensetracker/data/database/DatabaseSchemaPolicy.kt:17` + `DatabaseMigrations.kt:74`): baseline is v145; `DatabaseMigrations.ALL` registers ONLY 145→146→147→148; versions <145 are intentionally NOT migrated (destructive fallback + rescue path). Schema snapshots in `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/` span 33.json…148.json (103 files, documented gaps 1–32/54-55/58/61-63/66/97-99 per app/build.gradle.kts:295+). androidTest assets wired via `app/build.gradle.kts:92-94`. The active-chain tests (Matrix + DatabaseMigrationTest 145–148 tests) match policy exactly. ~50 legacy single-step tests in DatabaseMigrationTest + all 18 in MigrationContractTest exercise `AppDatabase.MIGRATION_*` objects below v145 that production NO LONGER REGISTERS (135 dead companion vals remain in AppDatabase.kt, e.g. AppDatabase.kt:220 MIGRATION_6_7 … 8570 MIGRATION_142_143) — they still compile and execute those SQL scripts directly, so they are historical regression coverage of dead-registered code, not the production upgrade path.
- Overlap baseline: only 3 files have JVM Room in-memory twins under `app/src/test/data/database/` (ExchangeRateDaoTest, RecommendationDaoTest) plus a Robolectric twin for MerchantKeyBackfillWorkerTest in `app/src/test/data/location/`. All other DAO/parity/migration androidTests are unique device-side coverage (JVM database tests cover different DAOs: TransactionEventDao, ReceiptEventDao, ScannedReceiptClaim, etc.).
- Prior 2026-05 audit (`docs/testing/generated/test-batches/batch-005.md:275-277`) blanket-KEEPed all androidTest files ("Must remain"). **Overturned for 4 files** (ComplexQueryTest, ExchangeRateDaoTest, RecommendationDaoTest, MerchantKeyBackfillWorkerTest) — evidence below.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 21 | 2 | 4 | 0 | 0 | 1 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | androidTest/…/data/database/DatabaseMigrationMatrixTest.kt | 249 | 6 | 0 | ROOM | DatabaseMigrations, AppDatabase schema | KEEP | P0 | complementary to DatabaseMigrationTest | Active chain 145→148 matches baseline policy; vacuous count assert L215-219 |
| 2 | androidTest/…/data/database/DatabaseMigrationTest.kt | 3998 | 72 | 0 | ROOM | AppDatabase.MIGRATION_* | STRENGTHEN | P0 | DUP w/ MigrationContractTest (13 steps) | ~8 tests silently skip (hasSchema(1)=false); legacy chains pass no migrations; pre-145 body = dead-registered path |
| 3 | androidTest/…/data/database/MigrationContractTest.kt | 1344 | 18 | 0 | ROOM | AppDatabase.MIGRATION_* | KEEP | P1 | DUP w/ DatabaseMigrationTest | Snapshot-independent; Keystore-failure fallback contract is unique; hand-built v69 schema FRAGILE |
| 4 | androidTest/…/data/database/dao/AiArtifactDaoTest.kt | 230 | 14 | 0 | ROOM | AiArtifactDao | KEEP | P2 | none | Upsert dedupe on unique key, expiry cleanup — solid |
| 5 | androidTest/…/data/database/dao/AiChatMessageDaoTest.kt | 119 | 5 | 0 | ROOM | AiChatMessageDao | KEEP | P3 | none | Ordering, cascade delete verified |
| 6 | androidTest/…/data/database/dao/AiChatSessionDaoTest.kt | 85 | 5 | 0 | ROOM | AiChatSessionDao | KEEP | P3 | none | Round-trip + ordering, fine |
| 7 | androidTest/…/data/database/dao/BudgetDaoTest.kt | 413 | 18 | 0 | ROOM | BudgetDao | KEEP | P1 | none | Single-active-budget enforcement, notification-field preservation |
| 8 | androidTest/…/data/database/dao/CategoryDaoTest.kt | 117 | 4 | 0 | ROOM | CategoryDao | KEEP | P3 | none | CRUD + defaults-first ordering |
| 9 | androidTest/…/data/database/dao/ComplexQueryTest.kt | 344 | 18 | 0 | ROOM | ExpenseDao, MerchantCategoryDao | MERGE | P3 | DUP ExpenseDaoTest | Most tests aggregate in Kotlin stdlib over getAll(), not SQL; keep 3-4 SQL-backed tests |
| 10 | androidTest/…/data/database/dao/DaoStressTest.kt | 404 | 20 | 0 | ROOM/STRESS | ExpenseDao | NIGHTLY | P2 | complementary ExpenseDaoTest | Real concurrency value; wall-clock timing asserts flaky on emulator; `maxRead >= 0` tautology L168 |
| 11 | androidTest/…/data/database/dao/DedupeKeyUniquenessRegressionTest.kt | 205 | 3 | 0 | ROOM | ExpenseDao.insertAtomic, DuplicateDetectionPolicy | KEEP | P0 | complementary DedupeKeyTest (entity, JVM) | Proves real unique-index dedupe: type/currency-distinct keys, same-type race blocked |
| 12 | androidTest/…/data/database/dao/ExchangeRateDaoTest.kt | 105 | 4 | 0 | ROOM | ExchangeRateDao | MERGE | P2 | DUP JVM twin (broader, 9 tests) | JVM twin covers validDate/history/latest; device copy is a subset |
| 13 | androidTest/…/data/database/dao/ExpenseDaoTest.kt | 971 | 40 | 0 | ROOM | ExpenseDao | KEEP | P0 | complementary ExpenseDaoBoundaryConsistencyTest (JVM, static) | Gold: effectiveAmount/shared/isNotMine money semantics, PURCHASE-only filters, receipt anti-join |
| 14 | androidTest/…/data/database/dao/ExpenseGroupDaoTest.kt | 112 | 4 | 0 | ROOM | ExpenseGroupDao | KEEP | P3 | none | Archive/restore, member cascade |
| 15 | androidTest/…/data/database/dao/FreshInstallBatch8ParityTest.kt | 609 | 23 | 0 | ROOM | FRESH_INSTALL_CALLBACK constraints | KEEP | P1 | complementary migrate_75_76 tests (in-batch) | Proves CHECK constraints + splitTemplateId FK ON DELETE SET NULL on fresh install; despite name, does NOT diff vs migrated schema |
| 16 | androidTest/…/data/database/dao/FreshInstallIndexParityTest.kt | 370 | 5 | 0 | ROOM | FRESH_INSTALL_CALLBACK indexes | STRENGTHEN | P1 | DUP GroupMemberDaoTest index tests | Index presence only; KDoc admits full PRAGMA parity is "planned"; table-level parity lives in MatrixTest |
| 17 | androidTest/…/data/database/dao/GroupMemberDaoTest.kt | 436 | 16 | 0 | ROOM | GroupMemberDao, GroupExpenseDao | KEEP | P1 | none | Single-current-user invariant, FK RESTRICT, index shape |
| 18 | androidTest/…/data/database/dao/MerchantCategoryDaoTest.kt | 129 | 4 | 0 | ROOM | MerchantCategoryDao | KEEP | P2 | none | Deterministic confidence/timesUsed/lexical tie-break cascade |
| 19 | androidTest/…/data/database/dao/MerchantLocationDaoTest.kt | 273 | 14 | 0 | ROOM | MerchantLocationDao | KEEP | P2 | none | Unique (name, areaKey), hitCount, global correction coherence |
| 20 | androidTest/…/data/database/dao/MerchantNormalizationDaoTest.kt | 292 | 15 | 0 | ROOM | MerchantNormalizationDao | KEEP | P2 | none | CREATED/UPDATED/CONFLICT/CANONICAL_MISSING result codes verified |
| 21 | androidTest/…/data/database/dao/PendingReviewDaoTest.kt | 180 | 8 | 0 | ROOM | PendingReviewDao | KEEP | P1 | none | Conditional transitionStatus (claim semantics), upsert dedupe by rawNotificationId |
| 22 | androidTest/…/data/database/dao/RecommendationDaoTest.kt | 123 | 4 | 0 | ROOM | RecommendationDao | MERGE | P3 | DUP JVM twin (broader, 10+ tests) | JVM twin adds expireOld, max-5 cap, overflow archive, ordering |
| 23 | androidTest/…/data/database/dao/RecurringExpenseDaoTest.kt | 237 | 7 | 0 | ROOM | RecurringExpenseDao (deprecated), ManualRecurringExpenseDao | KEEP | P2 | none | Pins ABORT (non-replace) insert semantics on both DAOs |
| 24 | androidTest/…/data/database/dao/SavingsGoalDaoTest.kt | 282 | 16 | 0 | ROOM | SavingsGoalDao | KEEP | P1 | complementary SavingsSweepPlanDaoTest (JVM) | Atomic increment incl. 50-way concurrency no-lost-update, float-cents boundary |
| 25 | androidTest/…/data/database/dao/ScannedReceiptDaoTest.kt | 163 | 7 | 0 | ROOM | ScannedReceiptDao | KEEP | P2 | complementary ScannedReceiptClaimTest (JVM) | linkToExpense status transition; claimForAutoMatch only on JVM |
| 26 | androidTest/…/data/database/dao/UserCorrectionDaoTest.kt | 276 | 16 | 0 | ROOM | UserCorrectionDao | KEEP | P2 | none | Learning-tie-break determinism (count→recency→lexicographic) |
| 27 | androidTest/…/data/database/dao/WarrantyDaoTest.kt | 145 | 4 | 0 | ROOM | WarrantyDao | KEEP | P2 | complementary WarrantyReminderDeliveryDaoTest (JVM) | Active/expired/claimed query windows |
| 28 | androidTest/…/data/location/MerchantKeyBackfillWorkerTest.kt | 117 | 3 | 0 | MOCKED | MerchantKeyBackfillWorker | MERGE | P2 | DUP JVM Robolectric twin (broader, 5 tests; ledger F-20) | Mock-verify-only; no real DB; JVM twin covers retry/cancellation/guard precedence |

## Findings (noteworthy files only)

### androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt
- 72 tests. The v145–148 tail is excellent and policy-aligned: `migration_147_148_adds_background_job_run_columns` (L3838), `all_migrations_to_latest_pass` running `DatabaseMigrations.ALL` (L3906), `schema_version_matches_latest_schema_file` asserting APP_DATABASE_SCHEMA_VERSION == max exported JSON (L3976) — a real drift guard.
- Silent skips: `migrate_all_versions_from_1_to_51` (L39), `migrate_1_to_34_full_chain` (L490), `migrate_6_to_7` (L141), `migrate_7_to_8` (L164), `migrate_8_to_9` (L189), `migrate_9_to_10` (L204), `migration_preserves_expense_data` (L227), `migration_preserves_category_data` (L258), `foreign_key_constraints_maintained_after_migration` (L286) are guarded by `assumeTrue(hasSchema(1..10))` — no schema JSON exists below 33 (build.gradle.kts knownGapVersions), so they never execute and report as skipped, inflating apparent coverage.
- Legacy full-chain tests (`migrate_all_versions_from_33_to_114` L56, `migrate_92_to_114/115` L113/131, `migrate_33/38/49_to_51` L912-983, `migrate_40/46_to_48` L591-631) call `runMigrationsAndValidate(name, target, true)` with NO migration varargs while production registers only 145+ (DatabaseMigrations.kt:74). Under the documented baseline policy (DatabaseSchemaPolicy.kt:17, LEGAL_PATHS rescue section) these no longer test the registered upgrade path — either they fail at runtime with "migration not found" or they validate nothing registered. Needs a device run to disambiguate (could not verify statically; not run per constraints).
- ~50 single-step tests (69→70, 70→71, 71→72, 72→73, 73→74, 74→75, 75→76, 81→82, 84→86, 85→91, 113→114, 139→143) pass explicit `AppDatabase.MIGRATION_*` objects that are still compiled but unregistered — historically valuable (they caught dedup/retention semantics), but they are coverage of dead-registered code. Recommended action: keep 145+ tail; port the strongest pre-145 contract tests into MigrationContractTest (snapshot-independent) or mark/annotate the rest as legacy; delete the always-skip v<33 tests.
- FRAGILE: any schema-policy change touches ~70 tests.

### androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationMatrixTest.kt
- Correctly mirrors production policy: active chain 145→148 (L57), individual 145→146/146→147/147→148 with data seeding and index/column assertions (L72-160), fresh-vs-migrated table-set parity at v148 (L171), pre-baseline v33 destructive path (L203).
- Minor: `pre_baseline_v33_destructive_migration_to_v148` fetches a COUNT cursor then closes it without asserting (L215-219, "either is acceptable" comment) — a vacuous assertion; tighten or delete.
- Runs in CI migration-proof job only (main pushes/manual, non-blocking).

### androidTest/java/com/yourname/expensetracker/data/database/MigrationContractTest.kt
- 18 snapshot-independent tests. Unique value: `migration_69_to_70_completes_with_data_preserved_when_keystore_unavailable` (L212-460) pins the fail-open data-preservation fallback (tokens kept plaintext, version stays 0) via the `tokenEncryptionOverrideForTest` seam, with proper seam cleanup in `finally` — a security-adjacent contract not covered elsewhere on-device.
- Strong data-integrity assertions: 72→73 dedup/unique-index/NOT-NULL (L475-630), 141→142 forecast preservation + FK cascade (L1001-1124), 142→143 warranty deliveries shape/unique/FK (L1170-1315).
- Partial DUP with DatabaseMigrationTest for 13 of the same migration steps (6_7, 7_8, 8_9, 9_10, 69_70, 77_78, 78_79, 79_80, 91_92, 139_140, 140_141, 141_142, 142_143) — complementary implementations (real exported schemas vs hand-built minimal schemas), not identical; keep both.
- FRAGILE: hand-written v69/v72 schema fixtures must be kept in sync with historical migration SQL.
- Effectively manual-only in CI (not matched by `--tests "*DatabaseMigration*"`).

### androidTest/java/com/yourname/expensetracker/data/database/dao/ComplexQueryTest.kt
- 14 of 18 tests do their "complex query" in Kotlin after `expenseDao.getAll()` (e.g. sort/paginate/search/filter/sum/dedupe-tolerance L45-328) — this tests Kotlin stdlib, not SQL; contradicts MASTER_TESTING_STRATEGY.md "What NOT to Test". Real SQL coverage already exists and is better in ExpenseDaoTest (PURCHASE filters, effective amounts) and ExpenseDaoTest.getExpensesBetween.
- Overturns prior 2026-5 blanket KEEP. Action: move `query_merchant_category_mappings` (L67) and the two date-range tests (L124, L138) into ExpenseDaoTest, delete the stdlib-aggregation remainder.

### androidTest/java/com/yourname/expensetracker/data/database/dao/DedupeKeyUniquenessRegressionTest.kt
- The only on-device proof that the persisted `dedupeKey` unique index protects against duplicate approvals: same-type second insert returns -1 (L134-159), type/currency-distinct keys coexist (L88-119, L169-204) via `insertAtomic`. P0 dedup protection; keep.

### androidTest/java/com/yourname/expensetracker/data/database/dao/FreshInstallIndexParityTest.kt / FreshInstallBatch8ParityTest.kt
- What they actually guarantee: Batch8 (609 LOC) proves fresh-install CHECK constraints (budget/savings-goal/mileage/pending-review money>0 rules) and the `expenses.splitTemplateId` FK ON DELETE SET NULL via raw SQL on a real in-memory DB — genuine constraint enforcement, not schema diffing. Index variant proves 20 expected indexes exist and legacy non-Room indexes are absent, plus behavioral dup allowance (subscription candidates, budget forecasts).
- Neither performs fresh-vs-migrated PRAGMA-level parity; the class KDoc (FreshInstallIndexParityTest L33-56) admits full parity is only "planned". Table-name-level parity exists in DatabaseMigrationMatrixTest.fresh_vs_migrated_schema_parity_at_v148 (L171). Gap remains: column/default/FK-shape parity between the FRESH_INSTALL_CALLBACK path and migrated databases is untested.
- Name is misleading ("Parity") — rename or implement the planned PRAGMA diff.

### androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt
- Mock-only: stubs `WorkerExecutionGuard.runGuardedWithContext` to invoke the block and `coVerify`s `updateMerchantKey(1L, "sklavenitis")` (L94) — mock-verification-only per MASTER_TESTING_STRATEGY; never touches a real DB.
- The Robolectric JVM twin (`app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt`, 5 tests) covers the same happy path plus retry/cancellation/guard-precedence and runs in PR CI (ledger family F-20 tracks its normalization drift). Action: keep JVM twin as survivor; delete or fold androidTest into a real-DB variant if device-side worker behavior is ever needed.

## Area gaps (what is NOT tested in this area)

- Full fresh-vs-migrated schema parity (columns, defaults, NOT NULL, FK shapes via PRAGMA table_info/index_info) is documented as planned in FreshInstallIndexParityTest but never implemented; only table-name parity (MatrixTest) and index-name parity exist.
- No on-device test of `ScannedReceiptDao.claimForAutoMatch` atomic claim (the load-bearing auto-match overlap guard per LEGAL_PATHS) — covered only by JVM `ScannedReceiptClaimTest` on a JVM in-memory DB; the worker-facing guarantee is never exercised against a device SQLite.
- No device-side coverage for RawNotificationDao dedup indexes' insert behavior, TransactionEventDao/ReceiptEventDao/BackgroundJobRunDao (JVM-only twins), or the notification-intake capture path against real device SQLite/WAL semantics.
- Exchange-rate androidTest lacks the historical `validDate`/as-of-date behavior the JVM twin covers; no device test for `getLatestRate`.
- MerchantKeyBackfillWorker has no real-DB (unmocked repository) test anywhere proving actual backfill SQL against seeded data.
- MigrationContractTest + DAO suite are manual-only in CI: nothing runs `connectedDebugAndroidTest` on PRs, and the two emulator jobs are `continue-on-error` — a red instrumented suite cannot block a merge. MigrationContractTest is additionally missed by the migration-proof filter (`--tests "*DatabaseMigration*"` does not match `MigrationContractTest`).
- No androidTest asserts the DatabaseWriteBarrier/restore-mode blocking at the DAO layer on device (JVM-only families F-14).

## Rollup

- Verdicts: KEEP 21 · STRENGTHEN 2 · MERGE 4 · REWRITE 0 · DELETE 0 · NIGHTLY 1 · UNKNOWN 0 (28 files, 373 tests, 0 @Ignore, ~12.3k LOC)
- P0 count: 4 (DatabaseMigrationTest, DatabaseMigrationMatrixTest, ExpenseDaoTest, DedupeKeyUniquenessRegressionTest)
- DUP pairs found: 4 — androidTest↔JVM ExchangeRateDaoTest (JVM survivor); androidTest↔JVM RecommendationDaoTest (JVM survivor); androidTest↔JVM MerchantKeyBackfillWorkerTest (JVM survivor, F-20); ComplexQueryTest↔ExpenseDaoTest (in-batch). Partial: MigrationContractTest↔DatabaseMigrationTest share 13 migration steps (complementary, both kept).
- P4 (negative-value) files: none — no file is net-negative; ComplexQueryTest's stdlib-only tests are the closest (handled via MERGE).
- FRAGILE count: 2 (DatabaseMigrationTest — schema-policy churn touches ~70 tests; MigrationContractTest — hand-built historical schema fixtures).
- Unverifiable statically: whether DatabaseMigrationTest's no-varargs legacy chain tests (33→51, 92→114/115) throw at runtime under Room 2.7.2 now that production registers only 145+ — needs one manual `connectedDebugAndroidTest --tests "*DatabaseMigration*"` run to resolve; flagged as the first follow-up.
- Prior 2026-05 audit overturns: 4 files moved from KEEP to MERGE (ComplexQueryTest, ExchangeRateDaoTest, RecommendationDaoTest, MerchantKeyBackfillWorkerTest) with evidence above.
