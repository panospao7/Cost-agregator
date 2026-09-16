# Batch 13 — data/repository (part 1)

Scope: data/repository test cluster — Budget Mgmt (S2), Notification Capture/Pipeline (S3), Core Expense Mgmt + transaction lifecycle observability (S9), Merchant Categorization (S6), Currency aggregation backbone (S16), Export & Backup (S18), Shared Groups (S24), AI repos (S20), Financial Weather (S1). Files: 32 · LOC: 11,373 (~290 @Test, 45 @Ign via 3 class-level @Ignore files)

Reviewer notes:
- F-08 (`ReviewQueueRepositoryTest`) and F-13 (`ReviewQueueRepositoryTest` + `RecurringExpenseRepositoryTest`) ARE in this package but are NOT in this batch's file list (data/repository part 2). Verified statically: `RecurringExpenseRepositoryTest.kt:24` uses strict `mockk<RecurringRuleLifecycleCoordinator>()` and ReviewQueueRepositoryTest uses strict coordinator mocks — consistent with the F-13 ClassCastException-on-sealed-return signature. No file in THIS batch is in F-08/F-13.
- Analogous F-13-style stale-mock pattern found here: `CategoryRepositoryStressTest.kt:44` mocks the `dagger.Lazy<HybridExpenseClassifier>` wrapper itself (`mockk<Lazy<...>>(relaxed = true)`); the correct pattern (real `object : Lazy`) is in `CategoryRepositoryTest.kt:32-34`. File is @Ignore'd so latent.
- Relaxed `DatabaseWriteBarrier`/`RestoreMaintenanceMode` mocks dominate: in this batch the write barrier is actively exercised (typed `DatabaseAccessBlockedException`) only in `BudgetRepositoryHistoricalStatusTest` (restoreDebugSnapshot) and `NotificationRepositoryDeleteAllNotificationsClockTest`; the scenarios-batch finding (barrier installed but never fired) applies to the Expense/Budget/Groups/Pipeline suites here.
- Prior 2026-05 audit re-verified; two stale verdicts overturned (CategoryRepositoryTest, BudgetRepositoryStressTest @Ignore status); two confirmed (ExpenseRepositoryStressTest, NotificationProcessingPipelineStressTest).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 23 | 4 | 1 | 1 | 3 | 0 | 0 |

## Per-file table

Path prefix: `test/java/com/yourname/expensetracker/data/repository/`

| # | File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | AccountingExportRepositoryTest.kt | 802 | 15 | 0 | MOCKED | AccountingExportRepository, DeterministicExpenseExportPager | KEEP | P1 | #17, DeterministicExpenseExportPagerTest, AccountingExportPolicyTest | Real file I/O, content asserts, privacy fail-closed |
| 2 | AiArtifactRepositoryImplTest.kt | 201 | 9 | 0 | MOCKED | AiArtifactRepositoryImpl | REWRITE | P3 | AiArtifactDao tests | 7/9 verify-only delegation (self-flagged TODO) |
| 3 | AiChatRepositoryImplTest.kt | 173 | 8 | 0 | MOCKED | AiChatRepositoryImpl | KEEP | P2 | — | History-disabled = no persist (privacy); 2 thin delegation |
| 4 | AnalyticsRepositoryAggregateTest.kt | 564 | 9 | 0 | MOCKED | AnalyticsRepository | KEEP | P1 | CurrencyConsistency tests, AdvancedAnalyticsEngine* | Real invariants: aggregate==totalSpent, failures grouped |
| 5 | AutomatedSavingsRuleStateRepositoryTest.kt | 196 | 6 | 0 | PURE | AutomatedSavingsRuleStateRepository | STRENGTHEN | P1 | domain savings tests | F-17: recreate-on-same-file after cancel() (DataStore collision) |
| 6 | BudgetRepositoryDiagnosticsTest.kt | 192 | 5 | 0 | MOCKED | BudgetRepository, DiagnosticEventWriter | KEEP | P1 | BudgetMonitor tests | Best-effort diagnostics contract; structured fields asserted |
| 7 | BudgetRepositoryHistoricalStatusTest.kt | 522 | 19 | 0 | MOCKED | BudgetRepository | KEEP | P0 | #8, #10, #11, BudgetMonitorTest | Period-end as-of rates, threshold bounds, real barrier test |
| 8 | BudgetRepositoryStressTest.kt | 550 | 15 | 0 | MOCKED | BudgetRepository | MERGE | P1 | #7, #10, #11 | §1-4 dup of #7; keep §5 rollover regressions (→ #11) |
| 9 | BudgetRepositorySuggestionsBatchTest.kt | 137 | 1 | 0 | MOCKED | BudgetRepository | KEEP | P2 | #10 | Single grouped-query batching contract |
| 10 | BudgetRepositoryTruncationTest.kt | 500 | 9 | 0 | MOCKED | BudgetRepository | KEEP | P1 | #8, #11 | Aggregate-not-capped regression; health thresholds real |
| 11 | BudgetRolloverTest.kt | 517 | 12 | 0 | MOCKED | BudgetRepository, BudgetCalculator | KEEP | P1 | #8, #10 | Canonical rollover math, real calculator, ISSUE-3 regression |
| 12 | BusinessExpenseRepositoryTest.kt | 69 | 2 | 0 | MOCKED | BusinessExpenseRepository | KEEP | P2 | — | NaN rejected pre-DAO; small but real |
| 13 | CategoryRepositoryStressTest.kt | 90 | 4 | 4 | MOCKED | CategoryRepository | DELETE | P3 | #14 | Class @Ignore holds; zero assertions; Lazy-wrapper mock |
| 14 | CategoryRepositoryTest.kt | 155 | 9 | 0 | MOCKED | CategoryRepository | STRENGTHEN | P2 | #13 | Overturns 2026-05 DELETE; real case-dedupe/scope contracts |
| 15 | DashboardContractsAdapterTest.kt | 109 | 2 | 0 | MOCKED | DashboardContractsAdapter | KEEP | P2 | FinancialWeatherRepositoryTest | Confirmed-only feed + isPartial propagation |
| 16 | DatabaseBackupRepositoryImplTest.kt | 1237 | 24 | 0 | ROBOLECTRIC | DatabaseBackupRepositoryImpl | KEEP | P0 | P7BugFixesTest, BackupRestoreIntegrityE2ETest | Real SQLite staged import/rollback; 1 SRCTEXT guard; gaps documented |
| 17 | DeterministicExpenseExportPagerTest.kt | 57 | 2 | 0 | MOCKED | DeterministicExpenseExportPager | KEEP | P2 | #1 | Focused pager unit; complementary to #1 e2e |
| 18 | ExpenseRepositoryStressTest.kt | 256 | 13 | 13 | MOCKED | ExpenseRepository | DELETE | P4 | #19, #20 | Class @Ignore; STALE: asserts direct expenseDao.delete (prod routes coordinator, ExpenseRepository.kt:417) |
| 19 | ExpenseRepositoryTest.kt | 330 | 9 | 0 | MOCKED | ExpenseRepository | KEEP | P1 | #18, #20, architecture guards | Legal-path positive (coordinator delegation); SQL-string asserts FRAGILE |
| 20 | ExpenseRepositoryTruncationTest.kt | 215 | 9 | 0 | MOCKED | ExpenseRepository | KEEP | P1 | #19 | Uncapped-read regression (data-loss guard) |
| 21 | ExpenseWriteStoreObservabilityTest.kt | 298 | 6 | 0 | MOCKED | TransactionLifecycleCoordinator, TransactionSideEffectPlanner | STRENGTHEN | P0 | domain lifecycle tests (F-02 area), golden | Real coordinator events/keys; last test = stdlib withTimeoutOrNull (drop) |
| 22 | FinancialWeatherRepositoryTest.kt | 637 | 11 | 0 | MOCKED | FinancialWeatherRepository, ForecastInputAssembler | KEEP | P1 | DashboardContractsAdapterTest, forecast engine tests | Real assembler; effectiveAmount + fail-safe UNKNOWN currency |
| 23 | GroupsRepositoryImplTest.kt | 299 | 6 | 0 | MOCKED | GroupsRepositoryImpl | KEEP | P2 | GroupLifecycleCoordinator tests | joinedAt delete-guard boundaries; coordinator delegation |
| 24 | MerchantNormalizationRepositoryTest.kt | 127 | 10 | 0 | MOCKED | MerchantNormalizationRepository | KEEP | P2 | MerchantCanonicalizer tests | Result-code mapping + fallback chain real |
| 25 | MerchantRulesRepositoryTest.kt | 34 | 4 | 0 | PURE | MerchantRulesRepository | KEEP | P2 | — | Pure sanitization; exemplary |
| 26 | MultiCurrencyRepositoryTest.kt | 862 | 27 | 0 | MOCKED | MultiCurrencyRepository | KEEP | P1 | currency/ CanonicalMultiCurrencyFixture, Budget* | Aggregate-path + type-agnostic + null-bucket regressions |
| 27 | NotificationProcessingPipelineAtomicityTest.kt | 396 | 8 | 0 | MOCKED | NotificationProcessingPipeline | KEEP | P0 | #29, #30, DedupeKeyProducerConsistencyTest | markProcessed atomicity/rollback; 29-arg ctor FRAGILE |
| 28 | NotificationProcessingPipelineOversizedAmountTest.kt | 114 | 8 | 0 | PURE | NotificationProcessingPipeline (static detectors) | KEEP | P1 | #29 (DUP: 2 tests) | Pure detector; PAN/keyword scoring real |
| 29 | NotificationProcessingPipelineReliabilityTest.kt | 847 | 17 | 0 | MOCKED | NotificationProcessingPipeline, DuplicateDetectionPolicy | STRENGTHEN | P0 | #27, #28 (DUP), #30 | Salvage/boundary/concurrency; 2 tests dup of #28; FRAGILE ctor |
| 30 | NotificationProcessingPipelineSourceLinkTest.kt | 183 | 2 | 0 | MOCKED | NotificationProcessingPipeline | KEEP | P1 | #29 | Source-link failure ≠ lost review; post-commit diagnostics |
| 31 | NotificationProcessingPipelineStressTest.kt | 587 | 28 | 28 | MOCKED | (none — test-local simulation) | DELETE | P4 | #27, #29 | Class @Ignore; reimplements pipeline in helpers; `result != null || result == null` |
| 32 | NotificationRepositoryDeleteAllNotificationsClockTest.kt | 117 | 2 | 0 | MOCKED | NotificationRepository | KEEP | P1 | NotificationRepositoryStressTest (other batch) | Barrier verified + injected-clock audit event |

## Findings (noteworthy files only)

### test/java/.../data/repository/ExpenseRepositoryStressTest.kt
- Class-level `@Ignore("Stress test: may hang in CI, run manually")` (line 25) — runs nowhere; prior P4 verdict confirmed.
- STALE beyond shallowness: `stress - deleteExpense with valid expense` (line 198) does `coVerify { expenseDao.delete(expense) }`, but production `ExpenseRepository.deleteExpense` now routes through `transactionLifecycleCoordinator.deleteExpense(expense)` (main ExpenseRepository.kt:417-419). Un-ignoring would fail — the test pins the pre-coordinator implementation.
- Bulk loops (lines 129-147) have zero assertions. Real contract already in ExpenseRepositoryTest (#19) + TruncationTest (#20).
- Action: DELETE. No nightly value (no real contention, all relaxed mocks).

### test/java/.../data/repository/NotificationProcessingPipelineStressTest.kt
- Class-level `@Ignore` holds (line 9); 28 tests, all calling test-private `runPipeline`/`makeRoutingDecision`/`classifyMerchant` helpers (lines 508-586) that reimplement pipeline logic — production NotificationProcessingPipeline is never touched (no import).
- Tautologies: line 53 `assertTrue(result != null || result == null)`; line 421 asserts both timings > 0.
- Action: DELETE. Everything non-tautological is covered for real by #27/#28/#29.

### test/java/.../data/repository/AutomatedSavingsRuleStateRepositoryTest.kt
- F-17 (1 failure "multiple DataStores active for the same file") — root cause visible statically: `weekly reservation is idempotent...` (lines 33-48) cancels the first scope (line 42) then immediately creates a new PreferenceDataStore on the SAME file (lines 45, 178-181). `scope.cancel()` is not awaited, so the previous DataStore's IO actor can still hold the file.
- Excellent content otherwise: real DataStore persistence, atomic monthly-cap under concurrency (awaitAll, lines 51-77), pruning, atomic reserve+cap.
- Action: STRENGTHEN — await the cancelled scope (or join) before recreation; keep in CI (reward caps = money-adjacent).

### test/java/.../data/repository/BudgetRepositoryStressTest.kt
- Header (lines 40-44) documents the A.9 removal of @Ignore — prior "NIGHTLY / class-@Ignore" status is OVERTURNED; it runs in PR CI today.
- Sections 1-4 (lines 121-318): validation edge cases duplicate BudgetRepositoryHistoricalStatusTest (#7 NaN/infinite/threshold tests, lines 216-294) plus two no-assertion loops (lines 256-278).
- Section 5 (lines 335-540) is genuinely valuable: aggregate-vs-capped regression and 12-period compounding rollover with derived expectation (4400) — but near-duplicates BudgetRepositoryTruncationTest rollover test (lines 231-276) and BudgetRolloverTest.
- Action: MERGE — fold Section 5 tests into BudgetRolloverTest (#11, canonical rollover file); delete Sections 1-4 as covered by #7.

### test/java/.../data/repository/CategoryRepositoryStressTest.kt vs CategoryRepositoryTest.kt
- StressTest: class @Ignore holds (line 18); 3 of 4 tests assert nothing; mocks `dagger.Lazy<HybridExpenseClassifier>` wrapper (line 44) — the F-13-shaped antipattern (mock the wrapper, not the delegate). DELETE.
- CategoryRepositoryTest: prior 2026-05 verdict (#98, "TODO tautological, DELETE, P4") is OVERTURNED — file now has 9 tests with real contracts: case-preserving insert, case-insensitive dedup, CategoryCorrectionScope sealed results, engine cache invalidation. Remaining gap: 3 verify-only invalidation tests + learnMerchant delegation carry self-flagged TODOs.

### test/java/.../data/repository/DatabaseBackupRepositoryImplTest.kt
- Strongest file in batch: real SQLite files (not mocked DAOs), staged import with verifier seams, safety-backup rollback on reopen failure (lines 630-666), journal-before-RESTORE_PREPARING ordering (260-286), schema-86 budget repair/reject matrix, T2B clock-injection regressions (1000-1153).
- Flag 1: `no direct wall clock reads remain...` (1156-1174) is SRCTEXT-style (reads prod source, asserts strings) — acceptable as a CI guard, has a self-check for the resolver; keep but label as guard.
- Flag 2: `createCostBackup proceeds when write barrier protects snapshot` (200-229) has a conditional assertion (only checks if isFailure) — weak; and `checks barrier doubleCheck` (144-168) wraps in runCatching. Barrier breach/protect pair is decent but brittle.
- KDoc (53-67) honestly lists gaps: privacy-gate DENIAL during export/import not covered (unlike #1 which does cover denial) — recommended follow-up.

### test/java/.../data/repository/ExpenseWriteStoreObservabilityTest.kt
- Tests the REAL TransactionLifecycleCoordinator (legal path) — captures TransactionEvent correlationId for updateLocation/updateMerchant/updateType, merchantKey regeneration ("newname"), idempotency-key uniqueness with FakeTimeProvider. Real expected-output assertions.
- `currency flow timeout returns fallback` (287-297) tests only kotlinx `withTimeoutOrNull` — platform tautology, no app code. Drop it.
- Triple coverage note: overlaps domain lifecycle coordinator tests (scenarios/golden) and F-02's harness area; keep as the fast unit layer.

### test/java/.../data/repository/NotificationProcessingPipelineReliabilityTest.kt
- High value: fingerprint dedup, `DuplicateDetectionPolicy.windowEndExclusive` boundary convention (291-337), financial-package salvage rules (645-767), subscription-candidate concurrency with CompletableDeferred barriers (414-546).
- DUP: last two tests (770-791) are copy-paste of OversizedAmountTest #28 tests (45-66) — remove from this file, keep in #28.
- FRAGILE: 29-parameter pipeline ctor (110-142) must track every production ctor change (user pain: refactors break ~100 tests). Same ctor triplicated in #27/#30.

### F-08 / F-13 / F-03 disposition (batch guidance)
- F-08 and F-13 files (`ReviewQueueRepositoryTest.kt`, `RecurringExpenseRepositoryTest.kt`, `ReviewQueueRepositoryStressTest.kt`) live in this package but are NOT in this batch list — they belong to the data/repository part 2 batch. Static corroboration for F-13: strict (non-relaxed) `mockk<RecurringRuleLifecycleCoordinator>()` at RecurringExpenseRepositoryTest.kt:24; any newly-added coordinator method returning a sealed type would return an unstubbed/relaxed-Any and ClassCastException at the cast site — matches ledger signature. No analogous LIVE instance found in this batch's files (only the @Ignore'd CategoryRepositoryStressTest Lazy-wrapper mock).
- F-03 (BudgetMonitor updateXNotification): no BudgetMonitor tests in this batch; adjacent BudgetRepository tests here do assert the monitor-facing `adjustedSpendBreakdown` propagation (BudgetRepositoryHistoricalStatusTest lines 168-214, P6-CURRENT-002) — complementary, not duplicative.

## Area gaps (what is NOT tested in this area)

- Write barrier is mocked relaxed in nearly every Expense/Budget/Groups/Pipeline test; only 2 files in the batch actually fire `DatabaseAccessBlockedException`. A repository-layer "restore mode blocks writes" sweep (per MASTER_TESTING_STRATEGY golden #9) is missing for ExpenseRepository/GroupsRepositoryImpl/NotificationProcessingPipeline.
- Privacy-gate denial path tested for accounting export (#1) but NOT for DatabaseBackupRepositoryImpl export/import (self-documented gap) nor AiChat/AiArtifact.
- No ROOM-backed (real in-memory DB) repository tests in this batch — all DAO interactions are mocks; SQL correctness relies on DAO/migration batches. The BudgetRepository forecast-CASCADE tests even self-flag this (HistoricalStatusTest lines 428-434).
- deleteExpense/deleteAllExpenses audit-event content (TransactionEvent DELETED fields) is not asserted anywhere in this batch (only coordinator delegation verify in #19 area).
- MultiCurrencyRepository: RateBasis/stale-policy selection is only covered indirectly (in #7/#22); no direct test that monthly/category/merchant aggregates pick the documented rate basis.
- NotificationProcessingPipeline DO_NOT_STORE / STORE_REDACTED raw-text persistence behavior (strategy golden #8) is not covered by any file in this batch (privacySettingsRepository is a relaxed mock everywhere).
- F-09 (ExpenseStoreTest, data/store) correctly belongs to another batch; nothing here duplicates it.

## Rollup

- Verdict counts: KEEP 23 · STRENGTHEN 4 · MERGE 1 · REWRITE 1 · DELETE 3 · NIGHTLY 0 · UNKNOWN 0 (32 files)
- P0 count: 5 (files 7, 16, 21, 27, 29)
- DUP pairs found: ReliabilityTest↔OversizedAmountTest (2 detector tests, exact); ExpenseRepositoryStressTest↔ExpenseRepositoryTest (contract subset, stale); BudgetRepositoryStressTest §5↔BudgetRolloverTest↔BudgetRepositoryTruncationTest (rollover-aggregate regression triple — keep #11 as survivor, fold §5); DeterministicExpenseExportPagerTest↔AccountingExportRepositoryTest (complementary, not DUP)
- P4 (negative-value) files: ExpenseRepositoryStressTest.kt, NotificationProcessingPipelineStressTest.kt
- FRAGILE count: 9 — NotificationProcessingPipeline 29-arg ctor (files 27, 29, 30), BudgetRepository 13-14-arg ctor re-instantiated in 5 files (6, 7, 8, 9, 10, 11), ExpenseRepository 10-positional-arg ctor (18, 19, 20), ExpenseRepositoryTest SQL-string assertions (lines 195-232)
- Stale prior-audit verdicts overturned: CategoryRepositoryTest (DELETE→STRENGTHEN), BudgetRepositoryStressTest (NIGHTLY/@Ignore→runs in CI, MERGE). Confirmed: ExpenseRepositoryStressTest, NotificationProcessingPipelineStressTest (P4), CategoryRepositoryStressTest @Ignore, DatabaseBackupRepositoryImplTest KEEP.
- Ledger families touched in batch: F-17 (file 5, root cause identified statically). F-03/F-08/F-13/F-09 files are NOT in this batch list (other batches); F-13 analog pattern flagged in file 13.
