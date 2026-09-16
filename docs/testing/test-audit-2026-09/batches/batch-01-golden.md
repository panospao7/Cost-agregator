# Batch 01 — golden

Scope: `test/java/com/yourname/expensetracker/golden/` — the project's "golden scenario" anchor set (Segments 1, 2, 7, 8, 9, 10, 14, 16, 18, 24/25, 28, 30, 38 cross-pipeline) · Files: 24 · LOC: 3220 (42 @Test, 0 @Ignore)

Reviewer notes:
- Fixture-driven claim is HALF-true. 16 files use `GoldenScenarioVerifier` + committed JSONs in `app/src/test/resources/golden/` (real expected values, tolerance 0.01, update-mode forbidden in CI per `GoldenScenarioVerifier.kt:32-38` — good). 8 files use plain asserts/DB only. The `domain/analytics/fixtures/` and `metrics/GoldenAnalyticsDataset.kt` fixture families exist but are consumed by OTHER packages (verification/, metrics/), not by this batch.
- CI wiring: goldens run only transitively inside the full `:app:testDebugUnitTest` in `.github/workflows/ci.yml:123` (unit-tests job). There is NO dedicated golden gate job as `docs/testing/GOLDEN_TESTS_CI_GATE.md` prescribes ("Add to your CI pipeline" is still unapplied). `-PupdateGoldens`/strict-golden-mode is documented but not wired as a separate release-blocking step.
- **Two goldens are statically predicted FAILING:** (a) `StaleRateCurrencyConversionGoldenTest` — the committed golden predates the 24h RATE_STALE behavior (see findings); (b) `Pipeline4LifecycleGoldenTest` — asserts coordinator behavior while calling raw DAOs (see findings). This contradicts the "completed anchor set" claim. `TEST_FAILURE_LEDGER.md` confirms the `golden` package was never measured ("ui/scenarios/golden/... NOT yet run").
- Systemic FRAGILITY: 9 files hand-wire `ExchangeRateStoreAdapter`+`CurrencyConverter`+`MultiCurrencyRepository` (11-arg constructor, `applicationScope` etc.). Any constructor/signature change breaks ~10 golden files at once — the user's "refactors break ~100 tests" pain, concentrated here. A shared factory in `GoldenTestBase` would fix it.
- Legal paths: several goldens deliberately simulate coordinator writes by hand via DAOs (documented in file KVDs as "without wiring N dependencies"). Per `docs/architecture/LEGAL_PATHS.md` the coordinators are the legal writers; these tests therefore verify the DB *contracts* (unique indexes, status SQL) but NOT that coordinators actually produce them.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 9 | 6 | 4 | 5 | 0 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/golden/AnalyticsDashboardBudgetParityGoldenTest.kt | 177 | 1 | 0 | ROOM | MultiCurrencyRepository, BudgetVsActualEngine | KEEP | P0 | #10 HomeDashboardFinancialInvariant (DUP), metrics/DashboardWidgetConsistencyTest | Golden-locked 220=220=220 parity |
| 2 | test/…/golden/BackupRestoreRoundtripGoldenTest.kt | 141 | 1 | 0 | ROOM | DatabaseWriteBarrier, MultiCurrencyRepository | STRENGTHEN | P0 | #20/#24, data/backup/DatabaseBarrierTest | No actual backup/restore; 7-mode loop = same stub |
| 3 | test/…/golden/BankSyncFailureRecoveryGoldenTest.kt | 114 | 1 | 0 | ROOM | BankConnectionDao, MultiCurrencyRepository | REWRITE | P2 | scenarios/BankSyncScenarioTest | Self-fulfilling: writes status via DAO, reads it back |
| 4 | test/…/golden/ConcurrentOccurrenceClaimTest.kt | 101 | 3 | 0 | ROOM | RecurringOccurrenceDao.claimForExpense | MERGE | P1 | #18 RecurringBillPaymentMatch (DUP) | "Concurrent" is sequential; fold into #18 |
| 5 | test/…/golden/CsvExportImportRoundtripGoldenTest.kt | 113 | 1 | 0 | PURE | CsvCellSanitizer | STRENGTHEN | P2 | domain/export/CsvCellSanitizerNegativeAmountTest | No real CSV export/import roundtrip (misnomer) |
| 6 | test/…/golden/ForecastSynthesisGoldenTest.kt | 97 | 1 | 0 | MOCKED | MonteCarloSpendingSimulator, DataQualityAssessor | KEEP | P1 | domain/forecasting/MonteCarlo*GoldenTest/Test | Deterministic seed-42 golden; good |
| 7 | test/…/golden/GoldenTestBase.kt | 108 | 0 | 0 | FIXTURE | (Room in-memory + fixed clock + barrier) | KEEP | P1 | — | Solid base; add shared repo factory to kill FRAGILE wiring |
| 8 | test/…/golden/GroupSettlementBudgetOffsetGoldenTest.kt | 158 | 1 | 0 | ROOM | EFFECTIVE_AMOUNT_SQL, BudgetVsActualEngine | KEEP | P0 | metrics/EffectiveAmountConsistencyTest | 80-vs-140 share math golden-locked; some hardcoded-true fields |
| 9 | test/…/golden/HiltGraphSmokeTest.kt | 97 | 3 | 0 | ROOM | misc singletons, AppDatabase DAOs, NavigationDestination | REWRITE | P3 | — | assertNotNull-only; NavigationRouteSmokeTest tautology; not Hilt |
| 10 | test/…/golden/HomeDashboardFinancialInvariantTest.kt | 127 | 1 | 0 | ROOM | MultiCurrencyRepository, BudgetVsActualEngine | MERGE | P1 | #1 (DUP) | Same parity invariant, smaller seed; "ViewModel-level" claim false |
| 11 | test/…/golden/MerchantCategorizationDedupeGoldenTest.kt | 136 | 1 | 0 | ROOM | MerchantKeyGenerator, ExpenseDao merchant totals | KEEP | P1 | util/MerchantKeyGeneratorStressTest | Greek→Latin key dedup golden-locked; real dedup-window query |
| 12 | test/…/golden/MulticurrencyAnalyticsDashboardBudgetGoldenTest.kt | 170 | 1 | 0 | ROOM | CurrencyConverter, MultiCurrencyRepository | KEEP | P0 | scenarios/MulticurrencyPartialRateScenarioTest | Best in batch: partial rates + failure reasons golden-locked |
| 13 | test/…/golden/NotificationReviewDashboardBudgetGoldenTest.kt | 153 | 1 | 0 | ROOM | ExpenseDao dedupeKey index, TransactionEventDao | STRENGTHEN | P1 | e2e/NotificationExpenseDashboardE2ETest, scenarios/NotificationPipelineScenarioTest | Pipeline simulated by hand; 3 hardcoded-constant fields |
| 14 | test/…/golden/Pipeline4LifecycleGoldenTest.kt | 104 | 3 | 0 | ROOM | RecurringRuleLifecycleCoordinator (via raw DAOs!) | REWRITE | P1 | #21, contracts/RecurringDeactivateContractTest | LIKELY FAILING: raw DAO insert can't generate occurrences |
| 15 | test/…/golden/PrivacyDoNotStoreTest.kt | 121 | 3 | 0 | ROOM | RawNotification data class, RawNotificationDao | REWRITE | P1 | contracts/PrivacyStorageContractTest | Test 1 = data-class copy tautology; sanitizer untested |
| 16 | test/…/golden/PrivacyGateEnforcementGoldenTest.kt | 133 | 1 | 0 | MOCKED | CompositePrivacyGate, LocationPrivacyGate, PrivacyAuditLoggerImpl | KEEP | P0 | scenarios/PrivacyGateContractTest | Real gates + real Room audit; single-gate composite (no short-circuit proof) |
| 17 | test/…/golden/ReceiptMatchingNoDoubleCountGoldenTest.kt | 166 | 1 | 0 | ROOM | ReceiptExpenseLinkDao unique index, MultiCurrencyRepository | STRENGTHEN | P1 | scenarios/ReceiptLifecycleDbContractTest | Link simulated by hand; unique-index rejection real |
| 18 | test/…/golden/RecurringBillPaymentMatchTest.kt | 153 | 4 | 0 | ROOM | claimForExpense, fulfillByOccurrenceKey, suppressByOccurrenceId | KEEP | P1 | #4 (DUP), #19 | DAO claim/fulfill/suppress contract; absorb #4 |
| 19 | test/…/golden/RecurringPlannedActualNoDoubleCountGoldenTest.kt | 185 | 1 | 0 | ROOM | claim+fulfill+MultiCurrencyRepository | KEEP | P0 | scenarios/RecurringNoDoubleCountScenarioTest | 12.99 count-once golden-locked; claim simulated, not coordinator |
| 20 | test/…/golden/RestoreBlocksAllWritesTest.kt | 114 | 6 | 0 | MOCKED | DatabaseWriteBarrier + RestoreMaintenanceMode (real, mocked prefs) | MERGE | P1 | data/backup/DatabaseBarrierTest (DUP), #2/#24 | Barrier-only; F-14 typed-exception risk; not "all engines" |
| 21 | test/…/golden/RuleDeactivationCleanupTest.kt | 169 | 4 | 0 | ROOM | RecurringOccurrence/Reminder/Planned DAO mutators | STRENGTHEN | P1 | domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest | Performs cleanup steps itself; coordinator.deactivateRule never called |
| 22 | test/…/golden/StaleRateCurrencyConversionGoldenTest.kt | 126 | 1 | 0 | ROOM | CurrencyConverter 24h staleness | REWRITE | P1 | scenarios/CurrencyRateStalenessScenarioTest | Golden JSON provably stale → verifier fails today |
| 23 | test/…/golden/TransactionLifecycleFullContractGoldenTest.kt | 151 | 1 | 0 | ROOM | ExpenseDao dedupeKey index, TransactionEventDao | STRENGTHEN | P1 | domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest (F-02) | Events hand-written; "full contract" overstates |
| 24 | test/…/golden/WorkerRestoreBarrierIdempotencyGoldenTest.kt | 106 | 1 | 0 | MOCKED | DatabaseWriteBarrier (mock mode) | MERGE | P2 | #20, data/backup/DatabaseBarrierTest | Mock-mode; "idempotency" = same pure check twice; tautological fields |

## Findings (noteworthy files only)

### test/…/golden/StaleRateCurrencyConversionGoldenTest.kt (+ stale_rate_currency_conversion.json)
- Test emits 7 keys including `gbpFailureReason` (StaleRateCurrencyConversionGoldenTest.kt:118); committed golden has only 6 — `gbpFailureReason` absent — so `GoldenScenarioVerifier.compareJson` produces "unexpected key" diff and `assertPassed()` throws. Statically FAILING today.
- Worse: golden records `conversionFailures: []` and `displayTotal: 191` (= 100 + 45 + 46, i.e. GBP converted at 1.15). It was captured BEFORE the 24h staleness threshold existed (`CurrencyConverter.kt:87 MAX_RATE_AGE_MS`, per CODEBASE_SEGMENTS Segment 16 note dated 2026-05-06). The exact regression this golden exists to catch is the one it currently cannot run.
- Action: regenerate with `-PupdateGoldens=true`, review diff (GBP must appear as `RATE_STALE`, displayTotal must drop to 145), commit. Keep the test — high P1 money-correctness value.

### test/…/golden/Pipeline4LifecycleGoldenTest.kt
- KDoc says "through repositories (which delegate to coordinators)" but the code calls raw DAOs: `database.recurringExpenseDao().insert(...)` (:102) is a plain `@Insert` (RecurringExpenseDao.kt:63-64); the delegating path is `RecurringExpenseRepository.insert → RecurringRuleLifecycleCoordinator.createRule` (RecurringExpenseRepository.kt:116-118). Occurrence/reminder/planned-row generation lives only in the coordinator.
- Therefore test 1 ("generates occurrences reminders and planned rows", :22) cannot pass, test 2's "deactivation must delete open PLANNED occurrences" (:50) cannot pass (raw `setActiveStatus` is a bare UPDATE, ManualRecurringExpenseDao.kt:74), and test 3's "occurrences must be cleaned" (:86) cannot pass — `RecurringOccurrence` has no FK cascade to the rule (RecurringOccurrence.kt:12-13). Test 3 also has dead code (:87-90, unused `planned`, comment "Just verify no explosion").
- This is a golden that asserts legal-path outcomes while exercising a DAO bypass. Action: rewrite against `RecurringExpenseRepository`/`RecurringRuleLifecycleCoordinator` (real legal path), then run. Until then it is a predicted-red member of the "anchor" set.

### test/…/golden/PrivacyDoNotStoreTest.kt
- Test 1 (:21-59) is a Kotlin data-class `copy(title=null, …)` then asserts the copy is null — tautology, tests stdlib, zero production code. Tests 2-3 insert hand-constructed null/redacted rows and read them back — self-fulfilling; the enforcement point (`RawContentSanitizer`, `RawStorageMode` application in the capture pipeline) is never executed.
- Master-strategy golden #8 ("processes but persists no raw text") is effectively NOT implemented. Privacy-critical. Action: rewrite driving a real notification through the DO_NOT_STORE path; assert parser got real text AND persisted row is null AND fingerprint computed (delete test 1 outright).

### test/…/golden/BackupRestoreRoundtripGoldenTest.kt + WorkerRestoreBarrierIdempotencyGoldenTest.kt
- Both replace `RestoreMaintenanceMode` with a MockK whose `isWritesAllowed()` is stubbed (BackupRestore:88-104; Worker:47-66). The 7-mode loop and the 7-worker list thus execute the identical stub 7× — `allNonNormalModesBlocked`/`allWorkersBlocked` are near-tautologies; per-mode/per-worker behavior of the real mode manager is untested. `dbMutationsDuringRestore: 0` (Worker:98) and `sharedExpenseUsesMyShare: true`-style constants elsewhere are hardcoded pass values inside otherwise-real JSONs.
- "Roundtrip" name overstates: no `createCostBackup`/`restoreCostBackup` is performed; totals before/after are real but no restore occurs.
- Redeeming value: barrier throws ISE with operation name in message (Worker:54) and NORMAL-allowed check; totals preservation. Merge barrier-mode coverage into one place; if kept, drive the REAL `RestoreMaintenanceMode` (mocked SharedPreferences like #20 does) instead of mocking the mode itself.

### test/…/golden/AnalyticsDashboardBudgetParityGoldenTest.kt vs HomeDashboardFinancialInvariantTest.kt
- Same invariant (dashboard == categorySum == budgetActual) over near-identical seeds; both golden-locked. #10's KDoc claims "ViewModel-level" but instantiates no ViewModel — same repository/engine as #1. Keep #1 (richer golden: budgetItems, deposit exclusion), delete #10 or repurpose it to actually test HomeViewModel state.
- Weakness in both: budget-path inputs are hand-normalized with the rate hardcoded (`exp.amount * 0.90`, #1:86; literal 45.0, #10:94) rather than through `AnalyticsCurrencyNormalizer` — the parity is only as independent as the hand math.

### CI / docs drift
- `GOLDEN_TESTS_CI_GATE.md` documents 13 goldens; the resources dir now holds 21 scenario JSONs (16 used by this package; 5 `e2e_*.json` used elsewhere). No dedicated CI job exists; goldens only run inside the monolithic `testDebugUnitTest` that the ledger reports can hang (JVM instrumentation crash, TEST_FAILURE_LEDGER operational note). A fast `--tests "com.yourname.expensetracker.golden.*"` release gate (as the doc prescribes) would both honor the doc and isolate these predicted failures.

## Area gaps (what is NOT tested in this area)

Mapping of the 10 Phase-3 golden scenarios (MASTER_TESTING_STRATEGY.md:52-151) to reality:

| # | Master scenario | Status today |
|---|---|---|
| 1 | notification → expense → dashboard | PARTIAL — #13 tests the DB contract (dedupeKey unique index, dashboard total) but hand-writes what `NotificationProcessingPipeline` would write; no real pipeline in any golden |
| 2 | receipt scan → expense → link → analytics | PARTIAL — #17 inserts the link by hand ("simulating ReceiptLinkService"); `ReceiptLifecycleCoordinator` never runs |
| 3 | recurring bill payment marks occurrence PAID, suppresses reminder | PARTIAL — #18/#19 verify the DAO claim/fulfill/suppress SQL and count-once; the actual cross-pipeline hook (TransactionLifecycleCoordinator side-effect → `linkExpenseToOccurrence`) is never exercised |
| 4 | email receipt duplicate → LinkedExisting, no new expense | MISSING — no golden; only out-of-package scenarios/EmailReceiptPipelineScenarioTest (plan itself calls it DAO-only) |
| 5 | backup/restore preserves dashboard totals | PARTIAL — #2 does mode-string transitions only; no real backup/restore roundtrip in this package (lives in scenarios/e2e) |
| 6 | deactivate rule stops all future activity | MISSING at coordinator level — #21/#14 test DAO primitives (and #14 is predicted-failing); `RecurringRuleLifecycleCoordinator.deactivateRule` itself only covered by static contract test + RecurringLifecycleCoordinatorTest (other batch) |
| 7 | multi-currency dashboard shows converted totals | COVERED — #12, #22, #1 (strongest area; fix #22's stale golden) |
| 8 | privacy DO_NOT_STORE processes but persists no raw text | MISSING as behavior — #15 is a tautology + self-fulfilling DAO roundtrip |
| 9 | restore mode blocks all writes across ALL engines | PARTIAL — #20/#2/#24 call the barrier directly; no golden proves expense-create/receipt-save/recurring-generate/budget/group/investment actually throw through their real entry points |
| 10 | concurrent expense creation does not double-link occurrence | PARTIAL — #4 is sequential; no true-concurrency (multi-dispatcher/transaction) test of the atomic claim |

Other gaps:
- No golden uses `NotificationProcessingPipeline`, `TransactionLifecycleCoordinator`, `ReceiptLifecycleCoordinator`, `RecurringRuleLifecycleCoordinator`, or `ReceiptLinkService` — i.e., the anchor set verifies DB contracts, not the legal paths that AGENTS.md/LEGAL_PATHS.md mandate. GOLDEN_SCENARIO_IMPLEMENTATION_PLAN.md explicitly required "REAL coordinators (not mocks)"; that intent was not realized for scenarios 1, 2, 3, 5, 6, 8, 9, 10.
- No true concurrency anywhere in the package (all `runTest` sequential).
- 9× duplicated `MultiCurrencyRepository` manual wiring (FRAGILE) — no shared factory in `GoldenTestBase`.
- No golden for budget monitor alerts, forecast-vs-budget integration with real `SynthesisEngine` (only mocked-distribution Monte Carlo in #6).

## Rollup

- Verdicts: KEEP 9 · STRENGTHEN 6 · MERGE 4 · REWRITE 5 · DELETE 0 · NIGHTLY 0 · UNKNOWN 0 (24 files, 42 tests, 0 @Ignore)
- P0 count: 6 (#1, #2, #8, #12, #16, #19)
- DUP pairs: HomeDashboardFinancialInvariant↔AnalyticsDashboardBudgetParity · ConcurrentOccurrenceClaim↔RecurringBillPaymentMatch · RestoreBlocksAllWrites↔data/backup/DatabaseBarrierTest · WorkerRestoreBarrierIdempotency↔RestoreBlocksAllWrites
- FRAGILE: 9 files share hand-rolled 11-arg repository wiring (systemic); + #14 (bypass premise), #2/#24 (mocked mode)
- Predicted-red goldens (static): StaleRateCurrencyConversion (stale JSON artifact), Pipeline4Lifecycle (DAO-bypass premise); RestoreBlocksAllWrites may break if F-14's typed-exception change landed
- No P4 (negative-value) files; closest are PrivacyDoNotStoreTest test 1 and NavigationRouteSmokeTest (inside #9)
