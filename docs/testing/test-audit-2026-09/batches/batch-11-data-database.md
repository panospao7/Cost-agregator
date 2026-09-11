# Batch 11 — data-database

Scope: JVM-side persistence layer (`data/database/**`) — migration registration/proof, transaction runner, group transaction coordinator, 21 DAO suites, entity + Room-model tests. Shared persistence layer underpinning Segments 3/4/7/9/14/15/16/20/21/24/34 · Files: 32 · LOC: 7,412

Reviewer notes: All 21 DAO suites are real Robolectric + in-memory Room (`AppDatabase.inMemoryBuilder`) — the four DAO files the 2026-05 audit called TODO skeletons (BackgroundJobRun, Investment, PrivacyAudit, RawNotification) are now fully implemented; those stale verdicts are OVERTURNED. `TransactionRollbackTest.kt` (prior P4 DELETE) no longer exists — deletion was executed. F-15 (`MigrationRegistrationTest` FileNotFoundException on `AppDatabase/{119,120,141,142}.json`) appears resolved: all four JSONs exist under `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/` (gaps only at 118/128/139, all pre-baseline) and `app/build.gradle.kts:94` wires `$projectDir/schemas` into the `test` sourceSet assets; per instructions no test run was performed, so confirmation requires one measured `:app:testDebugUnitTest --tests "*MigrationRegistration*"`. CI (.github/workflows/ci.yml:122-126) runs `:app:testDebugUnitTest` + `verifyRoomSchemaSnapshots` and NO connected task, so every JVM file here is CI-effective while same-class androidTest DAO tests (batch 03) are not. DB is v148 (`DatabaseSchemaPolicy.MIGRATION_BASELINE=145`, `CURRENT_VERSION=APP_DATABASE_SCHEMA_VERSION`); no file wrongly asserts an outdated current version — only intentional historical-step pins (119/120/121/141/142/143) whose schema JSONs exist.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 27 | 1 | 0 | 0 | 2 | 1 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/data/database/DatabaseMigrationProofTest.kt | 208 | 3 | 0 | ROOM | DatabaseMigrations, DatabaseSchemaPolicy | KEEP | P1 | MigrationRegistrationTest | chain 145→148 seed survival; gapless-chain + fresh/migrated parity; name hardcodes 148 |
| 2 | test/…/data/database/DomainTransactionRunnerTest.kt | 255 | 13 | 0 | MOCKED | DomainTransactionRunner, TransactionContext, CancellationSafe | STRENGTHEN | P2 | GroupTransactionCoordinatorTest | fake-runner tests test the double; one assertNotNull-only |
| 3 | test/…/data/database/GroupTransactionCoordinatorTest.kt | 1241 | 33 | 0 | ROOM | GroupTransactionCoordinator, TransactionLifecycleCoordinator | KEEP | P0 | GroupLifecycleScenarioTest; GroupsRepositoryImplTest | real atomicity/share math; 2 weak conditional tests; FRAGILE ctor |
| 4 | test/…/data/database/MigrationRegistrationTest.kt | 177 | 7 | 0 | ROOM | AppDatabase migrations | KEEP | P1 | DatabaseMigrationProofTest | F-15 root cause (missing 119/120/141/142.json) now fixed; see notes |
| 5 | test/…/data/database/converter/ConvertersTest.kt | 34 | 4 | 0 | PURE | Converters | KEEP | P2 | — | roundtrip + UNKNOWN fallback |
| 6 | test/…/data/database/dao/BackgroundJobRunDaoTest.kt | 208 | 8 | 0 | ROOM | BackgroundJobRunDao | KEEP | P2 | WorkerRunLogger tests | prior "skeleton" verdict overturned — real coverage |
| 7 | test/…/data/database/dao/BankConnectionDaoTest.kt | 274 | 12 | 0 | ROOM | BankConnectionDao | KEEP | P1 | — | credential wipe on disconnect (tokens→NULL), scoped, idempotent |
| 8 | test/…/data/database/dao/BudgetAdjustmentDaoTest.kt | 139 | 3 | 0 | ROOM | BudgetAdjustmentDao | KEEP | P2 | — | caller-timestamp semantics, strict expiry boundary |
| 9 | test/…/data/database/dao/EmailReceiptDaoTest.kt | 266 | 15 | 0 | ROOM | EmailReceiptDao | KEEP | P2 | EmailReceiptParser tests | message-ID dedupe (-1), fingerprint, deleteOlderThan |
| 10 | test/…/data/database/dao/ExchangeRateDaoTest.kt | 190 | 10 | 0 | ROOM | ExchangeRateDao | KEEP | P2 | androidTest ExchangeRateDaoTest (DUP-lite) | historical as-of rate, upsert, cleanup; JVM side is CI-effective |
| 11 | test/…/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt | 461 | 15 | 0 | PURE | (none — no DAO used) | DELETE | P4 | TimePeriodAnalyticsAlignmentTest | tautologies (`5000 <= 5000`); salvage last TimePeriodUtils test |
| 12 | test/…/data/database/dao/InvestmentDaoTest.kt | 254 | 12 | 0 | ROOM | InvestmentDao | KEEP | P2 | — | prior "skeleton" overturned; real aggregate math (3000/250/2500) |
| 13 | test/…/data/database/dao/PrivacyAuditDaoTest.kt | 129 | 6 | 0 | ROOM | PrivacyAuditDao | KEEP | P2 | — | prior "skeleton" overturned; insert/limit/DESC/empty |
| 14 | test/…/data/database/dao/RawNotificationDaoTest.kt | 246 | 9 | 0 | ROOM | RawNotificationDao | KEEP | P2 | NotificationRepository tests | prior "mock-only" overturned; dedupe UNIQUE (-1); purge column untested |
| 15 | test/…/data/database/dao/ReceiptEventDaoTest.kt | 144 | 6 | 0 | ROOM | ReceiptEventDao | KEEP | P2 | ReceiptLifecycleCoordinatorTest | ordering, no cross-receipt mixing |
| 16 | test/…/data/database/dao/ReceiptExpenseLinkDaoTest.kt | 214 | 9 | 0 | ROOM | ReceiptExpenseLinkDao | KEEP | P2 | ReceiptMatching tests | FK-seeded links, pair-scoped unlink, IGNORE -1 |
| 17 | test/…/data/database/dao/RecommendationDaoTest.kt | 525 | 15 | 0 | ROOM | RecommendationDao | KEEP | P1 | androidTest RecommendationDaoTest (DUP-lite) | cap-5, overflow archive, expire idempotence; JVM is CI-effective |
| 18 | test/…/data/database/dao/RecurringOccurrenceDaoTest.kt | 239 | 14 | 0 | ROOM | RecurringOccurrenceDao | KEEP | P2 | Recurring lifecycle tests | targeted updateStatus, date range, ordering |
| 19 | test/…/data/database/dao/SavingsSweepPlanDaoTest.kt | 137 | 4 | 0 | ROOM | SavingsSweepPlanDao | KEEP | P2 | — | caller-timestamp + strict monthEnd boundary |
| 20 | test/…/data/database/dao/ScannedReceiptClaimTest.kt | 167 | 5 | 0 | ROOM | ScannedReceiptDao.claimForAutoMatch | KEEP | P0 | ReceiptMatchLifecycleServiceTest | atomic CAS claim vs all 5 start states; double-link guard |
| 21 | test/…/data/database/dao/SpendingPersonalityProfileDaoTest.kt | 92 | 2 | 0 | ROOM | SpendingPersonalityProfileDao | KEEP | P3 | — | targeted markAsViewed; thin |
| 22 | test/…/data/database/dao/SplitItemAssignmentDaoTest.kt | 125 | 3 | 0 | ROOM | SplitItemAssignmentDao | KEEP | P2 | — | exact paidAt incl. older-than-creation proof |
| 23 | test/…/data/database/dao/SplitTemplateDaoTest.kt | 123 | 4 | 0 | ROOM | SplitTemplateDao | KEEP | P3 | — | incrementUseCount semantics |
| 24 | test/…/data/database/dao/SubscriptionCandidateDaoTest.kt | 144 | 4 | 0 | ROOM | SubscriptionCandidateDao | KEEP | P3 | — | convert/reject field semantics |
| 25 | test/…/data/database/dao/TransactionEventDaoTest.kt | 162 | 7 | 0 | ROOM | TransactionEventDao | KEEP | P2 | lifecycle coordinator tests | ordering, nullable expenseId |
| 26 | test/…/data/database/dao/WarrantyReminderDeliveryDaoTest.kt | 258 | 10 | 0 | ROOM | WarrantyReminderDeliveryDao | KEEP | P1 | WarrantyReminderWorker tests | claim-before-notify state machine, stale recovery, FK cascade |
| 27 | test/…/data/database/entity/CategoryTest.kt | 52 | 7 | 0 | PURE | Category | KEEP | P3 | — | init validation (name/color/icon) |
| 28 | test/…/data/database/entity/DedupeKeyTest.kt | 104 | 9 | 0 | PURE | Expense.generateDedupeKey | KEEP | P0 | ExpenseEntityStressTest; DedupeKeyProducerConsistencyTest | currency-required ISSUE-6 regression; bucket boundaries |
| 29 | test/…/data/database/entity/ExpenseEntityStressTest.kt | 600 | 42 | 1 | PURE | Expense (dedupe key, effectiveAmount) | NIGHTLY | P3 | DedupeKeyTest (DUP) | class-level @Ignore; 2 empty-body tests; fuzz unique |
| 30 | test/…/data/database/entity/MileageTrackingValidationTest.kt | 24 | 1 | 0 | PURE | MileageTracking | DELETE | P4 | — | constructor passthrough tautology; entity has no validation |
| 31 | test/…/data/database/model/ExpenseWithCategoryFormattedAmountTest.kt | 141 | 11 | 0 | PURE | ExpenseWithCategory.formattedAmount | KEEP | P1 | — | polarity/currency placement/effectiveAmount basis |
| 32 | test/…/data/database/model/ExpenseWithCategoryFormattedTimeTest.kt | 79 | 5 | 0 | PURE | ExpenseWithCategory.formattedDate/Time | KEEP | P3 | — | shadowing regression; regex assumes English month locale |

## Findings (noteworthy files only)

### app/src/test/java/com/yourname/expensetracker/data/database/MigrationRegistrationTest.kt
- Ledger family F-10/F-15: measured `FileNotFoundException: AppDatabase/{119,120,141,142}.json` at ledger HEAD 43ca2228.
- Re-verified statically: all four JSONs now exist in `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/` (103 snapshots, 100–148 with gaps only at 118/128/139, all below the v145 baseline), and `app/build.gradle.kts:94` adds `$projectDir/schemas` to the `test` sourceSet assets. Root cause of F-15 is fixed in the tree.
- Tests are well-built: seed + migrate 120→121, 119→121, 141→142 (budget_forecasts quality columns + CASCADE FK with legacy-row preservation), 142→143 (warranty_reminder_deliveries + FK insert), plus registration checks in `ALL_MIGRATIONS`.
- Minor: reads `AppDatabase.ALL_MIGRATIONS` directly while `DatabaseSchemaPolicy` (main, line: "Production code AND tests must reference this") says to use the policy object — file #1 does. Normalize on `DatabaseSchemaPolicy.ALL_MIGRATIONS`.
- Action: KEEP P1; confirm F-15 GREEN with one measured run (not run here per constraints).

### app/src/test/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt
- Confirms prior P4 DELETE verdict; still 15 TODO-marked tautologies asserting literal Kotlin comparisons (`assertTrue(5000L <= 5000L)`, `assertFalse(5000L < 5000L)`) with zero Room/DAO usage despite the name — see lines 124-128, 275-291.
- Real concern it only documents: `ExpenseDao` date-boundary inconsistency (some queries `<= end`, others `< end`) — documented inline at lines 37-101 with query-name lists, but never executed against a DB.
- Sole real test (`canonical week range from week key`, lines 451-460) tests `TimePeriodUtils`, duplicated by consistency/TimePeriodAnalyticsAlignmentTest + TemporalConsistencyTest — salvage into a TimePeriodUtils test if no duplicate, then DELETE.
- Action: DELETE P4; replace with a real Room-backed boundary test (see Area gaps).

### app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt
- Highest-value file in batch: real in-memory Room + real `TransactionLifecycleCoordinator`; asserts DB state, not mocks — share math (`myShareAmount == 37.5`, `effectiveAmount == 37.5` lines 570-573), fail-closed inactive-group/non-member-payer/currency-mismatch with `getAllUncapped()` empty (lines 602-604, 630-634, 1151-1153), idempotency-key dedup (1160-1185), soft-delete re-add (1208-1240), CancellationException rethrow from post-commit runner (921-1027) per AGENTS worker rules.
- Weak spots: `partial failure rolls back everything` (335-357) and `transaction integrity` (470-493) are success-path-only with conditional `if (groupId > 0)` assertions; `concurrent transactions should not interfere` (360-383) is sequential; the member-insert rollback test (201-239) mocks the DAOs, so it proves exception propagation but not real Room rollback (nothing was written to roll back).
- FRAGILE: 14-arg `TransactionLifecycleCoordinator` construction (120-132) and 13-arg coordinator ctor (133-139) with relaxed mocks — refactor will break this file.
- Action: KEEP P0; convert the two conditional tests to real failure-path rollback (e.g., FK-violating expense) and rename "concurrent" test.

### app/src/test/java/com/yourname/expensetracker/data/database/DomainTransactionRunnerTest.kt
- Real value: `TransactionContext` rejects zero/negative occurredAt (61-69); `CancellationSafe.rethrowIfCancellation` CE vs non-CE (73-93).
- Six "fake runner" tests (138-247) exercise the file-local `FakeDomainTransactionRunner`, not production code — tautological; `runner constructs TransactionContext…` (97-110) is `assertNotNull` only. KDoc admits Room `withTransaction` delegation/rollback is only covered indirectly by DB-backed tests.
- Action: STRENGTHEN P2 — drop/merge the fake-runner section, keep context + CE tests; cover `RoomDomainTransactionRunner` with a real in-memory Room transaction test.

### app/src/test/java/com/yourname/expensetracker/data/database/entity/ExpenseEntityStressTest.kt
- Entire class `@Ignore("Stress test: may hang in CI, run manually")` (line 17) — 42 tests never run anywhere, not even nightly (no wired nightly job found).
- Real unique content: A.4 locale-stability regressions for dedupe keys (German/Greek locale, lines 39-111), effectiveAmount matrix incl. isNotMine-overrides-shared and 33% math (118-218), fuzz (442-476), extreme timestamps.
- Two tests have EMPTY bodies (no assertion at all): `dedupe key normalizes Greek characters` (418-426) and `removes special characters` (428-435) — negative value as-is.
- Action: NIGHTLY P3 — move to a nightly source set/job; delete or implement the two empty tests; dedupe format/casing/boundary cases against DedupeKeyTest.

### app/src/test/java/com/yourname/expensetracker/data/database/dao/BankConnectionDaoTest.kt
- Security-relevant: verifies `disconnect()` nulls accessToken/refreshToken/tokenExpiry, resets encryptionVersion, flips flags, preserves identity fields, is row-scoped and idempotent (87-253). Fail-closed credential cleanup — exactly the privacy posture AGENTS.md requires.
- Action: KEEP P1.

### app/src/test/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptClaimTest.kt
- Exercises the load-bearing conditional UPDATE `claimForAutoMatch` against real SQL for all five MatchStatus start states, asserting row counts and untouched invariants (93-166). This is the concurrency guard preventing two matching runs from double-linking one receipt — DB-level proof the mocked services cannot give.
- Action: KEEP P0.

## Area gaps (what is NOT tested in this area)

- **ExpenseDao has no executable JVM test at all**: the boundary-consistency file tests nothing, and `ExpenseDaoTest` exists only in androidTest, which CI does not run. The documented `<=` vs `<` date-boundary inconsistency (analytics total vs list) is unverified by any automated test that actually executes SQL.
- Real Room rollback for `GroupTransactionCoordinator` on mid-transaction failure (FK/constraint failure) — only mock-proven today; no test inserts a constraint-violating row inside the real transaction and asserts zero persisted rows.
- `RoomDomainTransactionRunner` real `withTransaction` delegation/rollback has no direct test.
- `RawNotificationDao` purge path (`rawContentPurgedAt` / DO_NOT_STORE cleanup) untested at DAO level; only insert/dedupe/read covered.
- Currency conversion inside group expense creation is mocked out (`CurrencyConverter` relaxed), so cross-currency group math is untested here.
- No JVM suite covers `ExpenseGroupDao`/`GroupMemberDao` query semantics in isolation (only via coordinator); androidTest versions are not CI-run.
- Schema snapshots missing for versions 118, 128, 139 (currently harmless — below v145 baseline and no test pins them, but any new createDatabase at those versions would regress to F-15).

## Rollup

- Verdicts: KEEP 27, STRENGTHEN 1, MERGE 0, REWRITE 0, DELETE 2, NIGHTLY 1, UNKNOWN 0 (32 files, 7,412 LOC, 318 @Test, 1 class-level @Ignore).
- P0 count: 3 (GroupTransactionCoordinatorTest, ScannedReceiptClaimTest, DedupeKeyTest).
- DUP pairs: JVM↔androidTest `ExchangeRateDaoTest` and `RecommendationDaoTest` (complementary, JVM side CI-effective); `DedupeKeyTest` ↔ `ExpenseEntityStressTest` (stress duplicates format/casing/boundary cases; @Ignore). No two CI-active files in this batch substantially duplicate each other.
- P4 (negative-value) files: `dao/ExpenseDaoBoundaryConsistencyTest.kt`, `entity/MileageTrackingValidationTest.kt`.
- FRAGILE count: 2 (GroupTransactionCoordinatorTest, DomainTransactionRunnerTest).
- Prior-audit overturns: 4 skeleton verdicts reversed to KEEP (BackgroundJobRunDaoTest, InvestmentDaoTest, PrivacyAuditDaoTest, RawNotificationDaoTest); TransactionRollbackTest deletion confirmed executed; F-15 root cause verified fixed in tree pending one measured run.
