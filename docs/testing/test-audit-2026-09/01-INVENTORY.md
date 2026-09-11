# Test Suite Audit 2026-09 — Master Inventory

**Generated** from the 26 batch review files in [`batches/`](batches/) — those files are authoritative; this is the consolidated per-file index (675 files).
Verdict legend: KEEP · STRENGTHEN (keep, fix gaps) · MERGE (fold into named survivor) · REWRITE · DELETE · NIGHTLY (keep out of PR CI) · UNKNOWN.
Pri: P0 protects money/lifecycle/privacy/backup/worker invariants · P4 negative value.

## Suite-level verdict summary

| Verdict | Files |
|---|---|
| KEEP | 436 |
| STRENGTHEN | 102 |
| MERGE | 61 |
| REWRITE | 35 |
| DELETE | 25 |
| NIGHTLY | 15 |
| UNKNOWN | 1 |
| **Total** | **675** |

| Priority | Files |
|---|---|
| P0 | 129 |
| P1 | 261 |
| P2 | 181 |
| P3 | 74 |
| P4 | 16 |

## Batch 01 — golden

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/golden/AnalyticsDashboardBudgetParityGoldenTest.kt | 177 | 1 | 0 | ROOM | MultiCurrencyRepository, BudgetVsActualEngine | KEEP | P0 | #10 HomeDashboardFinancialInvariant (DUP), metrics/DashboardWidgetConsistencyTest | Golden-locked 220=220=220 parity |
| test/…/golden/BackupRestoreRoundtripGoldenTest.kt | 141 | 1 | 0 | ROOM | DatabaseWriteBarrier, MultiCurrencyRepository | STRENGTHEN | P0 | #20/#24, data/backup/DatabaseBarrierTest | No actual backup/restore; 7-mode loop = same stub |
| test/…/golden/BankSyncFailureRecoveryGoldenTest.kt | 114 | 1 | 0 | ROOM | BankConnectionDao, MultiCurrencyRepository | REWRITE | P2 | scenarios/BankSyncScenarioTest | Self-fulfilling: writes status via DAO, reads it back |
| test/…/golden/ConcurrentOccurrenceClaimTest.kt | 101 | 3 | 0 | ROOM | RecurringOccurrenceDao.claimForExpense | MERGE | P1 | #18 RecurringBillPaymentMatch (DUP) | "Concurrent" is sequential; fold into #18 |
| test/…/golden/CsvExportImportRoundtripGoldenTest.kt | 113 | 1 | 0 | PURE | CsvCellSanitizer | STRENGTHEN | P2 | domain/export/CsvCellSanitizerNegativeAmountTest | No real CSV export/import roundtrip (misnomer) |
| test/…/golden/ForecastSynthesisGoldenTest.kt | 97 | 1 | 0 | MOCKED | MonteCarloSpendingSimulator, DataQualityAssessor | KEEP | P1 | domain/forecasting/MonteCarlo*GoldenTest/Test | Deterministic seed-42 golden; good |
| test/…/golden/GoldenTestBase.kt | 108 | 0 | 0 | FIXTURE | (Room in-memory + fixed clock + barrier) | KEEP | P1 | — | Solid base; add shared repo factory to kill FRAGILE wiring |
| test/…/golden/GroupSettlementBudgetOffsetGoldenTest.kt | 158 | 1 | 0 | ROOM | EFFECTIVE_AMOUNT_SQL, BudgetVsActualEngine | KEEP | P0 | metrics/EffectiveAmountConsistencyTest | 80-vs-140 share math golden-locked; some hardcoded-true fields |
| test/…/golden/HiltGraphSmokeTest.kt | 97 | 3 | 0 | ROOM | misc singletons, AppDatabase DAOs, NavigationDestination | REWRITE | P3 | — | assertNotNull-only; NavigationRouteSmokeTest tautology; not Hilt |
| test/…/golden/HomeDashboardFinancialInvariantTest.kt | 127 | 1 | 0 | ROOM | MultiCurrencyRepository, BudgetVsActualEngine | MERGE | P1 | #1 (DUP) | Same parity invariant, smaller seed; "ViewModel-level" claim false |
| test/…/golden/MerchantCategorizationDedupeGoldenTest.kt | 136 | 1 | 0 | ROOM | MerchantKeyGenerator, ExpenseDao merchant totals | KEEP | P1 | util/MerchantKeyGeneratorStressTest | Greek→Latin key dedup golden-locked; real dedup-window query |
| test/…/golden/MulticurrencyAnalyticsDashboardBudgetGoldenTest.kt | 170 | 1 | 0 | ROOM | CurrencyConverter, MultiCurrencyRepository | KEEP | P0 | scenarios/MulticurrencyPartialRateScenarioTest | Best in batch: partial rates + failure reasons golden-locked |
| test/…/golden/NotificationReviewDashboardBudgetGoldenTest.kt | 153 | 1 | 0 | ROOM | ExpenseDao dedupeKey index, TransactionEventDao | STRENGTHEN | P1 | e2e/NotificationExpenseDashboardE2ETest, scenarios/NotificationPipelineScenarioTest | Pipeline simulated by hand; 3 hardcoded-constant fields |
| test/…/golden/Pipeline4LifecycleGoldenTest.kt | 104 | 3 | 0 | ROOM | RecurringRuleLifecycleCoordinator (via raw DAOs!) | REWRITE | P1 | #21, contracts/RecurringDeactivateContractTest | LIKELY FAILING: raw DAO insert can't generate occurrences |
| test/…/golden/PrivacyDoNotStoreTest.kt | 121 | 3 | 0 | ROOM | RawNotification data class, RawNotificationDao | REWRITE | P1 | contracts/PrivacyStorageContractTest | Test 1 = data-class copy tautology; sanitizer untested |
| test/…/golden/PrivacyGateEnforcementGoldenTest.kt | 133 | 1 | 0 | MOCKED | CompositePrivacyGate, LocationPrivacyGate, PrivacyAuditLoggerImpl | KEEP | P0 | scenarios/PrivacyGateContractTest | Real gates + real Room audit; single-gate composite (no short-circuit proof) |
| test/…/golden/ReceiptMatchingNoDoubleCountGoldenTest.kt | 166 | 1 | 0 | ROOM | ReceiptExpenseLinkDao unique index, MultiCurrencyRepository | STRENGTHEN | P1 | scenarios/ReceiptLifecycleDbContractTest | Link simulated by hand; unique-index rejection real |
| test/…/golden/RecurringBillPaymentMatchTest.kt | 153 | 4 | 0 | ROOM | claimForExpense, fulfillByOccurrenceKey, suppressByOccurrenceId | KEEP | P1 | #4 (DUP), #19 | DAO claim/fulfill/suppress contract; absorb #4 |
| test/…/golden/RecurringPlannedActualNoDoubleCountGoldenTest.kt | 185 | 1 | 0 | ROOM | claim+fulfill+MultiCurrencyRepository | KEEP | P0 | scenarios/RecurringNoDoubleCountScenarioTest | 12.99 count-once golden-locked; claim simulated, not coordinator |
| test/…/golden/RestoreBlocksAllWritesTest.kt | 114 | 6 | 0 | MOCKED | DatabaseWriteBarrier + RestoreMaintenanceMode (real, mocked prefs) | MERGE | P1 | data/backup/DatabaseBarrierTest (DUP), #2/#24 | Barrier-only; F-14 typed-exception risk; not "all engines" |
| test/…/golden/RuleDeactivationCleanupTest.kt | 169 | 4 | 0 | ROOM | RecurringOccurrence/Reminder/Planned DAO mutators | STRENGTHEN | P1 | domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest | Performs cleanup steps itself; coordinator.deactivateRule never called |
| test/…/golden/StaleRateCurrencyConversionGoldenTest.kt | 126 | 1 | 0 | ROOM | CurrencyConverter 24h staleness | REWRITE | P1 | scenarios/CurrencyRateStalenessScenarioTest | Golden JSON provably stale → verifier fails today |
| test/…/golden/TransactionLifecycleFullContractGoldenTest.kt | 151 | 1 | 0 | ROOM | ExpenseDao dedupeKey index, TransactionEventDao | STRENGTHEN | P1 | domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest (F-02) | Events hand-written; "full contract" overstates |
| test/…/golden/WorkerRestoreBarrierIdempotencyGoldenTest.kt | 106 | 1 | 0 | MOCKED | DatabaseWriteBarrier (mock mode) | MERGE | P2 | #20, data/backup/DatabaseBarrierTest | Mock-mode; "idempotency" = same pure check twice; tautological fields |

## Batch 02 — fixtures

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/java/…/domain/util/FakeMonotonicTimeProvider.kt | 28 | 0 | 0 | FIXTURE | MonotonicTimeProvider | KEEP | P2 | none | 2 consumers (SystemMonotonicTimeProviderTest, AssistantViewModelTest); only monotonic fake |
| test/java/…/domain/util/FakeTimeProvider.kt | 67 | 0 | 0 | FIXTURE | TimeProvider | KEEP | P1 | none | 67 consumer files; canonical wall-clock fake; forDate is default-TZ |
| test/java/…/metrics/DashboardWidgetConsistencyTest.kt | 333 | 4 | 0 | MOCKED | ComputeDashboardWidgetsUseCase, SynthesisEngine | STRENGTHEN | P1 | complementary: DashboardProjectionSafetyTest, ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest | FRAGILE; 14 relaxed mocks; runway test asserts only `>=0` |
| test/java/…/metrics/EffectiveAmountConsistencyTest.kt | 242 | 10 | 0 | PURE | SpendingPaceCalculator, MonthlyComparisonCalculator, DayOfWeekAnalyzer, Expense.effectiveAmount | KEEP | P0 | complementary: integration/EffectiveAmountPipelineIntegrationTest, SpendingPaceCalculatorDeepTest | Real money math: isNotMine/share precedence across 3 engines |
| test/java/…/metrics/GoldenAnalyticsDataset.kt | 244 | 0 | 0 | FIXTURE | (fixture: GoldenScenario, expectations) | MERGE | P3 | DUP: domain/analytics/fixtures/GoldenDataSets.kt | Dead fixture — only consumer is its own test; fold into fixtures/GoldenDataSets |
| test/java/…/metrics/GoldenAnalyticsDatasetTest.kt | 175 | 8 | 0 | PURE | (none — test-local reimplementations) | REWRITE | P2 | DUP: verification/GoldenMasterVerificationTest | Tautology: metrics re-implemented in test, no production engine exercised |
| test/java/…/metrics/TimePeriodAlignmentTest.kt | 313 | 21 | 0 | PURE | TimePeriodUtils, AdvancedAnalyticsEngine, BudgetCalculator | KEEP | P1 | complementary: consistency/TimePeriodAnalyticsAlignmentTest (weaker) | Strong half-open/contiguity/leap/DST contract matrix |
| test/java/…/testfixtures/TestFixtures.kt | 104 | 0 | 0 | FIXTURE | (extensions: dateMs, eur/usd/gbp, money) | STRENGTHEN | P2 | none | dateMs 9+ importers; asReadableDate & STANDARD_CATEGORIES 0 consumers (dead) |
| test/java/…/testfixtures/database/AppDatabaseTestFactory.kt | 18 | 0 | 0 | FIXTURE | AppDatabase (in-memory) | KEEP | P1 | none | 23 consumer scenario files; applies migrations + FRESH_INSTALL_CALLBACK |
| test/java/…/testfixtures/golden/GoldenScenarioVerifier.kt | 217 | 0 | 0 | FIXTURE | (golden-file comparer) | KEEP | P1 | none | 21 consumer tests; 21 goldens checked in; CI-safe update-mode guard |
| test/java/…/testfixtures/scenario/ScenarioAssertions.kt | 92 | 0 | 0 | FIXTURE | (AppDatabase assertions) | KEEP | P2 | none | 4 consumer files; assertDashboardTotal sums raw amount not effectiveAmount |
| test/java/…/testfixtures/scenario/ScenarioSeed.kt | 108 | 0 | 0 | FIXTURE | (seed models) | KEEP | P2 | none | Used with seeder in ~10 scenario tests; NotificationInput dead stub |
| test/java/…/testfixtures/scenario/ScenarioSeeder.kt | 167 | 0 | 0 | FIXTURE | (seeder for AppDatabase) | STRENGTHEN | P1 | none | 9-10 consumer files; `feedInputs()` dead stub (0 external callers) |

## Batch 03 — androidTest

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| androidTest/…/data/database/DatabaseMigrationMatrixTest.kt | 249 | 6 | 0 | ROOM | DatabaseMigrations, AppDatabase schema | KEEP | P0 | complementary to DatabaseMigrationTest | Active chain 145→148 matches baseline policy; vacuous count assert L215-219 |
| androidTest/…/data/database/DatabaseMigrationTest.kt | 3998 | 72 | 0 | ROOM | AppDatabase.MIGRATION_* | STRENGTHEN | P0 | DUP w/ MigrationContractTest (13 steps) | ~8 tests silently skip (hasSchema(1)=false); legacy chains pass no migrations; pre-145 body = dead-registered path |
| androidTest/…/data/database/MigrationContractTest.kt | 1344 | 18 | 0 | ROOM | AppDatabase.MIGRATION_* | KEEP | P1 | DUP w/ DatabaseMigrationTest | Snapshot-independent; Keystore-failure fallback contract is unique; hand-built v69 schema FRAGILE |
| androidTest/…/data/database/dao/AiArtifactDaoTest.kt | 230 | 14 | 0 | ROOM | AiArtifactDao | KEEP | P2 | none | Upsert dedupe on unique key, expiry cleanup — solid |
| androidTest/…/data/database/dao/AiChatMessageDaoTest.kt | 119 | 5 | 0 | ROOM | AiChatMessageDao | KEEP | P3 | none | Ordering, cascade delete verified |
| androidTest/…/data/database/dao/AiChatSessionDaoTest.kt | 85 | 5 | 0 | ROOM | AiChatSessionDao | KEEP | P3 | none | Round-trip + ordering, fine |
| androidTest/…/data/database/dao/BudgetDaoTest.kt | 413 | 18 | 0 | ROOM | BudgetDao | KEEP | P1 | none | Single-active-budget enforcement, notification-field preservation |
| androidTest/…/data/database/dao/CategoryDaoTest.kt | 117 | 4 | 0 | ROOM | CategoryDao | KEEP | P3 | none | CRUD + defaults-first ordering |
| androidTest/…/data/database/dao/ComplexQueryTest.kt | 344 | 18 | 0 | ROOM | ExpenseDao, MerchantCategoryDao | MERGE | P3 | DUP ExpenseDaoTest | Most tests aggregate in Kotlin stdlib over getAll(), not SQL; keep 3-4 SQL-backed tests |
| androidTest/…/data/database/dao/DaoStressTest.kt | 404 | 20 | 0 | ROOM/STRESS | ExpenseDao | NIGHTLY | P2 | complementary ExpenseDaoTest | Real concurrency value; wall-clock timing asserts flaky on emulator; `maxRead >= 0` tautology L168 |
| androidTest/…/data/database/dao/DedupeKeyUniquenessRegressionTest.kt | 205 | 3 | 0 | ROOM | ExpenseDao.insertAtomic, DuplicateDetectionPolicy | KEEP | P0 | complementary DedupeKeyTest (entity, JVM) | Proves real unique-index dedupe: type/currency-distinct keys, same-type race blocked |
| androidTest/…/data/database/dao/ExchangeRateDaoTest.kt | 105 | 4 | 0 | ROOM | ExchangeRateDao | MERGE | P2 | DUP JVM twin (broader, 9 tests) | JVM twin covers validDate/history/latest; device copy is a subset |
| androidTest/…/data/database/dao/ExpenseDaoTest.kt | 971 | 40 | 0 | ROOM | ExpenseDao | KEEP | P0 | complementary ExpenseDaoBoundaryConsistencyTest (JVM, static) | Gold: effectiveAmount/shared/isNotMine money semantics, PURCHASE-only filters, receipt anti-join |
| androidTest/…/data/database/dao/ExpenseGroupDaoTest.kt | 112 | 4 | 0 | ROOM | ExpenseGroupDao | KEEP | P3 | none | Archive/restore, member cascade |
| androidTest/…/data/database/dao/FreshInstallBatch8ParityTest.kt | 609 | 23 | 0 | ROOM | FRESH_INSTALL_CALLBACK constraints | KEEP | P1 | complementary migrate_75_76 tests (in-batch) | Proves CHECK constraints + splitTemplateId FK ON DELETE SET NULL on fresh install; despite name, does NOT diff vs migrated schema |
| androidTest/…/data/database/dao/FreshInstallIndexParityTest.kt | 370 | 5 | 0 | ROOM | FRESH_INSTALL_CALLBACK indexes | STRENGTHEN | P1 | DUP GroupMemberDaoTest index tests | Index presence only; KDoc admits full PRAGMA parity is "planned"; table-level parity lives in MatrixTest |
| androidTest/…/data/database/dao/GroupMemberDaoTest.kt | 436 | 16 | 0 | ROOM | GroupMemberDao, GroupExpenseDao | KEEP | P1 | none | Single-current-user invariant, FK RESTRICT, index shape |
| androidTest/…/data/database/dao/MerchantCategoryDaoTest.kt | 129 | 4 | 0 | ROOM | MerchantCategoryDao | KEEP | P2 | none | Deterministic confidence/timesUsed/lexical tie-break cascade |
| androidTest/…/data/database/dao/MerchantLocationDaoTest.kt | 273 | 14 | 0 | ROOM | MerchantLocationDao | KEEP | P2 | none | Unique (name, areaKey), hitCount, global correction coherence |
| androidTest/…/data/database/dao/MerchantNormalizationDaoTest.kt | 292 | 15 | 0 | ROOM | MerchantNormalizationDao | KEEP | P2 | none | CREATED/UPDATED/CONFLICT/CANONICAL_MISSING result codes verified |
| androidTest/…/data/database/dao/PendingReviewDaoTest.kt | 180 | 8 | 0 | ROOM | PendingReviewDao | KEEP | P1 | none | Conditional transitionStatus (claim semantics), upsert dedupe by rawNotificationId |
| androidTest/…/data/database/dao/RecommendationDaoTest.kt | 123 | 4 | 0 | ROOM | RecommendationDao | MERGE | P3 | DUP JVM twin (broader, 10+ tests) | JVM twin adds expireOld, max-5 cap, overflow archive, ordering |
| androidTest/…/data/database/dao/RecurringExpenseDaoTest.kt | 237 | 7 | 0 | ROOM | RecurringExpenseDao (deprecated), ManualRecurringExpenseDao | KEEP | P2 | none | Pins ABORT (non-replace) insert semantics on both DAOs |
| androidTest/…/data/database/dao/SavingsGoalDaoTest.kt | 282 | 16 | 0 | ROOM | SavingsGoalDao | KEEP | P1 | complementary SavingsSweepPlanDaoTest (JVM) | Atomic increment incl. 50-way concurrency no-lost-update, float-cents boundary |
| androidTest/…/data/database/dao/ScannedReceiptDaoTest.kt | 163 | 7 | 0 | ROOM | ScannedReceiptDao | KEEP | P2 | complementary ScannedReceiptClaimTest (JVM) | linkToExpense status transition; claimForAutoMatch only on JVM |
| androidTest/…/data/database/dao/UserCorrectionDaoTest.kt | 276 | 16 | 0 | ROOM | UserCorrectionDao | KEEP | P2 | none | Learning-tie-break determinism (count→recency→lexicographic) |
| androidTest/…/data/database/dao/WarrantyDaoTest.kt | 145 | 4 | 0 | ROOM | WarrantyDao | KEEP | P2 | complementary WarrantyReminderDeliveryDaoTest (JVM) | Active/expired/claimed query windows |
| androidTest/…/data/location/MerchantKeyBackfillWorkerTest.kt | 117 | 3 | 0 | MOCKED | MerchantKeyBackfillWorker | MERGE | P2 | DUP JVM Robolectric twin (broader, 5 tests; ledger F-20) | Mock-verify-only; no real DB; JVM twin covers retry/cancellation/guard precedence |

## Batch 05 — architecture (guards — light-touch, out of pruning scope)

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| BackupRestoreArchitectureGuardTest.kt | 131 | 3 | P7-020/021: `resetDatabase()` enters RESETTING_DATABASE maintenance + RestoreJournal; raw `exportDatabase` is debug-UI-only | gradle-only (implicit) | KEEP | KEEP |
| BankPrivacyModeArchitectureGuardTest.kt | 62 | 2 | BANK-PRIVACY-01: BankApiIntegration uses `rawBankStatementStorageMode`, never `rawOcrStorageMode` | gradle-only (implicit) | KEEP | KEEP |
| CancellationSafetyArchitectureGuardTest.kt | 708 | 16 | CANCEL-01: broad catch in suspend fns must handle CE; no raw `runCatching` in suspend paths; structured allowlist w/ expiry + burn-down | gradle-only (implicit) | KEEP | KEEP |
| DeprecatedApiArchitectureGuardTest.kt | 330 | 13 | W01/W06/W29/W30/W31 + PR8: deprecated raw-Double/self-fetching analytics APIs have no new production call sites | gradle-only (implicit) | KEEP | KEEP |
| DirectEventDaoInsertGuardTest.kt | 410 | 6 | EVENT-GUARD-01: critical event-DAO `.insert()` only from approved files (structured allowlist, owner/expiry) | gradle-only (implicit) | KEEP (flag: expired allowlist, see Observations) | KEEP |
| Engine5PrimitiveGuardTest.kt | 149 | 3 | E5-001/002/003: no new deprecated `domain.model.PeriodRange` imports; no `System.currentTimeMillis` in domain/core\ | budget\ | analytics; no raw `CurrencyCode(` ctor in domain/core | UNKNOWN | KEEP | **DUP pair (E5-002)**: verify_time_boundaries.py (`time_boundaries`) + orphan scripts/guards/check_direct_time_calls.kts; **partial (E5-003)**: verify_money_boundaries.py |
| ExpenseDaoMutationAccessTest.kt | 129 | 5 | `RestrictedExpenseDaoMutation` @OptIn discipline: no file/class-level opt-in outside coordinators; mutating ExpenseDao methods annotated | gradle-only (implicit) | KEEP | KEEP |
| RawDaoArchitectureGuardTest.kt | 94 | 1 | DAO-01 (Engine 3 scope): merchant/category normalization DAO mutators only from repository layer | gradle-only (implicit) | KEEP | KEEP |
| RecurringArchitectureGuardTest.kt | 324 | 19 | Recurring legal paths: recurring DAO mutation only via coordinators; receivers use WorkManager (no runBlocking/DAO); no legacy `markBillPaid`; eventWriter for critical events; MIGRATION_139_140 content; rule create/activate/deactivate semantics | gradle-only (implicit) | KEEP (flag: 1 silently-skipped test, see Observations) | KEEP |
| SourceScanningArchitectureGuardTest.kt | 686 | 21 | Worker-layer rules: CoroutineWorker guard calls, no DAO/@Inject in workers (allowlist), notification-permission flag + local check (PR12K-3), privacy capabilities, `blockedPolicy=RETRY`, schema version == latest JSON, worker broad-catch CE rule, DataRetention raw-capability ban; comment-stripping + negative fixtures | gradle-only (implicit) | KEEP | KEEP |
| TransactionContextProvenanceGuardTest.kt | 192 | 5 | PR21-1: `TransactionContext(` constructed only in RoomDomainTransactionRunner / allowlisted files | gradle-only (implicit) | KEEP | KEEP |
| WorkerGuardArchitectureGuardTest.kt | 138 | 3 | Every CoroutineWorker calls `runGuarded(/WithContext)` (WorkerExecutionGuard) or documented exemption (allowlist currently empty, anti-creep tests) | gradle-only (implicit) | KEEP | KEEP |
| WorkerGuardStaticVerificationTest.kt | 163 | 4 | WorkerGuardVerifier registry completeness: all 10 known workers registered; KNOWN_WORKER_FQNS ↔ findWorkerClasses sync (reflection); WorkerSpec.DEFAULTS ⊆ listAllWorkerNames; count == 10 | gradle-only (implicit) | KEEP | KEEP |
| WriteBarrierArchitectureGuardTest.kt | 385 | 6 | All DAO write-method callers inject `DatabaseWriteBarrier` or are exempt (parses @Insert/@Update/@Delete + @Query UPDATE/DELETE/INSERT + @Transaction promotion); non-vacuousness checks | gradle-only (implicit) | KEEP | KEEP |

## Batch 06 — crosslayer: consistency/verification/contracts/diagnostics

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/consistency/ConstantsConsistencyTest.kt | 120 | 2 | 0 | PURE | SpendingPaceCalculator, SettlementCalculator, BudgetForecastingEngine | KEEP | P2 | InsightsEngineDeepTest | Reflection on constant names; mildly FRAGILE |
| test/…/consistency/CrossParserConsistencyTest.kt | 154 | 8 | 0 | MOCKED | Revolut/GreekBank/Generic parsers, MerchantKeyGenerator | KEEP | P1 | MerchantKeyCrossConsumer | Real parse→key assertions |
| test/…/consistency/CurrencyNormalizerConsistencyTest.kt | 160 | 10 | 0 | MOCKED | CurrencyNormalizer, parsers | KEEP | P1 | CrossParserConsistency | Symbol/code normalization verified |
| test/…/consistency/DedupeKeyProducerConsistencyTest.kt | 203 | 12 | 0 | PURE | DuplicateDetectionPolicy | MERGE | P2 | DuplicateDetectionPolicyDedupeKeyTest | "All producers" tautology — same fn 6×; see findings |
| test/…/consistency/DuplicateLogicConsistencyIntegrationTest.kt | 475 | 21 | 0 | MOCKED | CrossSourceDeduplication | KEEP | P0 | DetectDuplicateExpenseUseCaseTest | ISSUE-5 regression, real outputs; 2 stress tests padding |
| test/…/consistency/EmptyZeroNullResilienceTest.kt | 306 | 2 | 0 | MOCKED | Budget/Pace/Health/Converter/Settlement/Split | STRENGTHEN | P1 | CrossGroupIntegration #9 | Test 2 (StateFlow take 1) tautological; FRAGILE ctor |
| test/…/consistency/FinancialArithmeticPrecisionTest.kt | 136 | 4 | 0 | PURE | SettlementCalculator, SharedExpenseManager cents math | KEEP | P1 | SettlementCalculatorTest | Money boundary cases; reflection FRAGILE |
| test/…/consistency/HaversineConsistencyTest.kt | 109 | 5 | 0 | PURE | GeoUtils | KEEP | P2 | — | Formula parity + null-safe variant |
| test/…/consistency/MerchantKeyConsistencyTest.kt | 72 | 3 | 0 | PURE | MerchantKeyGenerator, MerchantCleaner, MerchantRulesRepository | KEEP | P2 | MerchantKeyCrossConsumer | Parser-vs-rules key parity |
| test/…/consistency/MerchantKeyCrossConsumerConsistencyTest.kt | 145 | 10 | 0 | PURE | MerchantKeyGenerator, Expense.generateDedupeKey | MERGE | P3 | DedupeKeyTest, SharedUtility, CrossParser | Greek/Latin+apostrophe+dedupe-key dups; keep dedupeKey-containment in DedupeKeyTest |
| test/…/consistency/SharedUtilityConsistencyTest.kt | 233 | 15 | 0 | PURE | AmountUtils, AmountExtractionUtils, CommonPatterns | STRENGTHEN | P2 | MerchantKeyCrossConsumer | Amount agreement unique; merchant-key section dup |
| test/…/consistency/TemporalConsistencyTest.kt | 190 | 4 | 0 | PURE | BudgetCalculator, SpendingPaceCalculator, TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest family | DST + leap-year, FakeTimeProvider, TZ pinned/restored |
| test/…/consistency/TimePeriodAnalyticsAlignmentTest.kt | 72 | 6 | 0 | PURE | TimePeriodUtils | DELETE | P3 | TimePeriodUtilsTest family | Self-comparison tautology; deprecated getLastNDaysRange |
| test/…/contracts/CancellationPropagationContractTest.kt | 138 | 2 | 0 | SRCTEXT | 12 catch sites (budget/receipt/recurring/forecast) | MERGE | P2 | CancellationSafetyArchitectureGuardTest | Superseded; test 2 tautological; see findings |
| test/…/contracts/LifecycleBarrierContractTest.kt | 69 | 2 | 0 | SRCTEXT | DatabaseWriteBarrier usage | MERGE | P2 | WriteBarrierArchitectureGuardTest | Substring-anywhere check weaker than guard; see findings |
| test/…/contracts/MoneyContractTest.kt | 58 | 1 | 0 | SRCTEXT | effectiveAmount summation sites | MERGE | P2 | check_raw_money_aggregates.kts | `.currency`-anywhere escape hatch too lax; see findings |
| test/…/contracts/PrivacyStorageContractTest.kt | 67 | 3 | 0 | SRCTEXT | RawStorageMode, RawContentSanitizer | STRENGTHEN | P1 | — | NOT covered by any script; keep as CI guard; test 1 weak |
| test/…/contracts/RecurringDeactivateContractTest.kt | 90 | 5 | 0 | SRCTEXT | RecurringRuleLifecycleCoordinator.deactivateRule | KEEP | P1 | RecurringArchitectureGuardTest | Token checks only; no script covers this invariant |
| test/…/contracts/SideEffectContractTest.kt | 75 | 1 | 0 | SRCTEXT | TransactionSideEffectDispatcher call sites | KEEP | P1 | — | Real brace-matching; unique, no script equivalent |
| test/…/diagnostics/DDL512RegressionTest.kt | 512 | 24 | 0 | PURE | EventMetadataSanitizer; (test doubles) | REWRITE | P2 | DurableDiagnostics* family | Journal/file tests test the test; TrackingHandle self-tests; see findings |
| test/…/diagnostics/DurableDiagnosticsA8RegressionTest.kt | 242 | 15 | 0 | PURE | RestoreJournal, EventMetadataSanitizer | STRENGTHEN | P1 | Acceptance/Regression/Golden | Journal path-stripping test is gold; tautology blocks |
| test/…/diagnostics/DurableDiagnosticsAcceptanceTest.kt | 285 | 18 | 0 | PURE | EventMetadataSanitizer, SideEffectDiagnosticRecorder | STRENGTHEN | P1 | Regression (verbatim dups) | Sanitizer/IBAN/path tests real; 3 data-class round-trips |
| test/…/diagnostics/DurableDiagnosticsRegressionTest.kt | 223 | 15 | 0 | PURE | EventMetadataSanitizer, DiagnosticEvent | STRENGTHEN | P2 | Acceptance | Contains literal `assertTrue(true)` tests; dups #22 |
| test/…/diagnostics/GlobalDurableDiagnosticsGoldenTest.kt | 222 | 19 | 0 | PURE | EventMetadataSanitizer, CorrelationIds, taxonomies | KEEP | P2 | Acceptance/Regression | Least tautology of the four; enum taxonomy pins vocab |
| test/…/verification/CarbonFootprintTest.kt | 139 | 7 | 0 | MOCKED | CarbonFootprintCalculator | KEEP | P2 | CrossGroup #3 | A.9 uncapped-query regression; effectiveAmount check |
| test/…/verification/CrossGroupIntegrationTest.kt | 845 | 9 | 0 | MOCKED | Insights/Advanced/Totals/Carbon/Lifestyle/Shared | KEEP | P1 | CrossSourceVerification | Overturn prior NIGHTLY: deterministic mocks; FRAGILE ctor |
| test/…/verification/CrossSourceVerificationTest.kt | 414 | 6 | 0 | MOCKED | Insights/Advanced/Dashboard/Totals/Pace | KEEP | P0 | CrossGroupIntegration | Canonical 280% pace formula; cross-engine parity |
| test/…/verification/GoldenMasterVerificationTest.kt | 1023 | 22 | 0 | MOCKED | All analytics engines + SmartSavings | STRENGTHEN | P0 | CrossSourceVerification | ~6 tautological/dup tests inside; golden constants gold; FRAGILE |
| test/…/verification/LifestyleAnalysisTest.kt | 169 | 7 | 0 | MOCKED | LifestyleInflationDetector | KEEP | P2 | CrossGroup #4 | Elasticity/bucket math asserted numerically |
| test/…/verification/SharedExpenseTest.kt | 344 | 15 | 0 | MOCKED | SharedExpenseManager, SettlementCalculator | KEEP | P1 | SharedExpenseManagerTest, SettlementCalculatorTest | Conservation + min-transfer invariants; NaN rejection |

## Batch 07 — e2e + integration

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/java/…/e2e/AnalyticsPipelineTest.kt | 301 | 5 | 0 | MOCKED | InsightsEngine (S8) | STRENGTHEN | P1 | AnalyticsEngineTestBase users; domain/analytics | Golden-march fixture, real numbers; ERROR-deprecated DAO stubs |
| test/java/…/e2e/BackupRestoreIntegrityE2ETest.kt | 184 | 1 | 0 | ROOM | MultiCurrencyRepository, DatabaseWriteBarrier (S18/S16) | MERGE | P2 | golden/BackupRestoreRoundtripGoldenTest; scenarios/BackupRestore* | "restore" is mock-flip; before==after tautology |
| test/java/…/e2e/BudgetAlertPipelineTest.kt | 162 | 4 | 0 | MOCKED | BudgetMonitor (S2/S11) | MERGE | P2 | domain/budget/BudgetMonitorTest(+Stress) | verify-only dispatch; F-03 seam adjacency |
| test/java/…/e2e/BudgetThresholdAlertE2ETest.kt | 151 | 1 | 0 | ROOM | BudgetCalculator, MultiCurrencyRepository (S2/S16) | MERGE | P2 | golden/AnalyticsDashboardBudgetParityGoldenTest | Real DB+pipeline; severity re-implemented in test |
| test/java/…/e2e/CategoryBreakdownFlowTest.kt | 65 | 1 | 0 | MOCKED | ExpenseRepository→AnalyticsViewModel (S8/S10) | MERGE | P2 | golden/HomeDashboardFinancialInvariantTest; metrics/DashboardWidgetConsistencyTest | % sum=100 invariant good; mock DAO |
| test/java/…/e2e/DailyAverageFlowTest.kt | 54 | 1 | 0 | MOCKED | AdvancedAnalyticsEngine (S8) | MERGE | P3 | golden analytics parity family | periodDays semantics; fold into golden |
| test/java/…/e2e/DateBoundaryFlowTest.kt | 49 | 1 | 0 | MOCKED | ExpenseRepository, TimePeriodUtils (S32/S10) | MERGE | P2 | TimePeriodUtils tests; golden parity | Half-open interval; Kotlin-simulated SQL |
| test/java/…/e2e/EmptyDataFlowTest.kt | 46 | 1 | 0 | MOCKED | AnalyticsRepository, AnalyticsViewModel (S8/S10) | MERGE | P3 | golden parity family | Empty-state defaults; fold into golden |
| test/java/…/e2e/FlowPipelineTestHarness.kt | 258 | 0 | 0 | FIXTURE | builds mock DAO pipeline (S8/S10) | DELETE | P3 | golden/GoldenTestBase (superseder) | ERROR-deprecated stubs; reimplements SQL in Kotlin |
| test/java/…/e2e/GroupSettlementPipelineTest.kt | 198 | 4 | 0 | MOCKED | SplitCalculator, SettlementCalculator, SharedExpenseManager (S24) | MERGE | P2 | domain/groups/SettlementCalculatorTest(+Stress); golden/GroupSettlementBudgetOffset | Unit tests mislabeled pipeline; good zero-sum asserts |
| test/java/…/e2e/MonthlyTotalFlowTest.kt | 53 | 1 | 0 | MOCKED | ExpenseRepository→ViewModel (S10) | MERGE | P2 | golden/HomeDashboardFinancialInvariantTest | Same totals-parity invariant, DB-backed there |
| test/java/…/e2e/NotificationExpenseDashboardE2ETest.kt | 200 | 1 | 0 | ROOM | AppParserRegistry, MultiCurrencyRepository (S3/S10/S16) | STRENGTHEN | P1 | golden/NotificationReviewDashboardBudgetGoldenTest | Real parsers+DB; insertAtomic bypasses coordinator |
| test/java/…/e2e/NotificationExpenseDashboardPipelineTest.kt | 636 | 3 | 0 | MOCKED | ComputeDashboardWidgetsUseCase, HybridExpenseClassifier (S10/S6/S3) | MERGE | P2 | file #12; golden/NotificationReviewDashboard; scenarios/NotificationPipelineScenarioTest | FRAGILE 330-line manual graph; unique classifier case |
| test/java/…/e2e/ReceiptMatchingE2ETest.kt | 193 | 1 | 0 | ROOM | ReceiptTransactionMatcher, ReceiptLinkService (S38/S4) | MERGE | P1 | golden/ReceiptMatchingNoDoubleCountGoldenTest | Real matcher/link/DB; same flow as golden |
| test/java/…/e2e/ReceiptProcessingPipelineTest.kt | 180 | 4 | 0 | MOCKED | ReceiptParser, CategorizationEngine (S4/S6) | REWRITE | P1 | domain ReceiptParser/CategorizationEngine tests | No ReceiptLifecycleCoordinator; OCR mocked; parse asserts real |
| test/java/…/e2e/RecurringPaymentMatchE2ETest.kt | 184 | 1 | 0 | ROOM | RecurringLifecycleCoordinator (S7) | MERGE | P0 | golden/RecurringBillPaymentMatchTest; golden/RecurringPlannedActualNoDoubleCountGoldenTest | Real coordinator+DB; coverage must survive merge |
| test/java/…/e2e/SharedExpenseFlowTest.kt | 77 | 1 | 0 | MOCKED | ExpenseRepository/InsightsEngine shared semantics (S9/S24) | MERGE | P2 | scenarios/SharedExpenseGroupScenarioTest | effectiveAmount+notMine across layers |
| test/java/…/integration/BudgetCashflowCurrencyBehavioralTest.kt | 146 | 6 | 0 | PURE | CurrencyConverter, MoneyNormalizationEngine (S16) | MERGE | P1 | domain/currency/ConversionSemanticsHardeningTest | 2 tautologies (enum checks); FORECAST_DATE case unique |
| test/java/…/integration/CategorizationPipelineIntegrationTest.kt | 282 | 19 | 0 | PURE | MerchantCleaner, MerchantKeyGenerator, AmountUtils (S6/S32) | DELETE | P3 | domain/util unit+stress tests (all of them) | isNotEmpty asserts; nanoTime flake; mislabeled |
| test/java/…/integration/Curr587BehavioralTest.kt | 173 | 14 | 0 | PURE | result data classes, StaleRatePolicy (S16) | DELETE | P4 | domain/currency family | Tautology-heavy; salvage 3 forBasis mapping asserts |
| test/java/…/integration/CurrencyConversionIntegrationTest.kt | 210 | 10 | 0 | PURE | CurrencyConverter, MoneyNormalizationEngine (S16) | MERGE | P1 | domain/currency/ConversionSemanticsHardeningTest | Strong sentinel rates; keep aggregate-provenance cases |
| test/java/…/integration/CurrencyNormalizationPost9a6Test.kt | 116 | 5 | 0 | PURE | MoneyNormalizationEngine, StaleRatePolicy (S16) | MERGE | P1 | ConversionSemanticsHardeningTest stalePolicy tests | 3 real stale-policy tests; 2 tautologies |
| test/java/…/integration/DashboardCurrencyIntegrationTest.kt | 319 | 7 | 0 | MOCKED | ComputeDashboardWidgetsUseCase (S10/S16) | REWRITE | P2 | metrics/DashboardWidgetConsistencyTest | Monte-carlo test has EMPTY assert block; keep widget-unavailable |
| test/java/…/integration/EffectiveAmountPipelineIntegrationTest.kt | 183 | 1 | 0 | MOCKED | TotalsAggregationEngine, AdvancedAnalyticsEngine (S10/S8) | MERGE | P2 | golden parity family; file #11 | Stubbed ERROR-deprecated DAO = tests own stub |
| test/java/…/integration/ExpenseCreationPipelineIntegrationTest.kt | 228 | 16 | 0 | PURE | MerchantCleaner, AmountUtils (S32) | DELETE | P3 | AmountUtilsTest, MerchantCleanerStressTest | No coordinator despite name; prior audit confirmed |
| test/java/…/integration/ForecastRunwayIntegrationTest.kt | 193 | 4 | 0 | MOCKED | ForecastInputAssembler(mocked!), SynthesisEngine (S1) | REWRITE | P2 | domain/forecasting/ForecastInputAssemblerTest | Tests 1&4 stub+verify their own mock; use real assembler |
| test/java/…/integration/MultiCurrencyAnalyticsTest.kt | 184 | 4 | 0 | MOCKED | MultiCurrencyRepository (S16) | STRENGTHEN | P1 | currency/CanonicalMultiCurrencyFixture users; scenarios mixed-currency | Real repo semantics, >2000 regression, no-uncapped-scan guard |
| test/java/…/integration/MultiCurrencyRepositoryBehavioralTest.kt | 134 | 4 | 0 | PURE | MoneyNormalizationEngine (S16) — never the repository | MERGE | P1 | domain/currency/CurrencyNormalizationBehavioralTest | Name lies; engine-level duplicates |

## Batch 08 — scenarios

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/scenarios/BackupRestoreContractTest.kt | 557 | 23 | 0 | ROBOLECTRIC | RestoreMaintenanceMode, RestoreJournal, CostbackupBundle, BackupVerifier | KEEP | P0 | data/backup/P7BugFixesTest, CostbackupBundleLimitsTest, BackupVerifierManifestTest, golden #20/#24 | Real mode machine + typed exceptions; unique vs mocked goldens |
| test/…/scenarios/BackupRestoreMoneyIntegrityScenarioTest.kt | 175 | 3 | 0 | ROOM | AppDatabase.ALL_MIGRATIONS, DAOs, ScenarioSeeder | STRENGTHEN | P2 | data/database/MigrationRegistrationTest (F-15) | No backup/restore despite name; test 2 assertNotNull-only |
| test/…/scenarios/BankSyncScenarioTest.kt | 190 | 3 | 0 | ROOM | BankConnectionDao, ExpenseDao.isDuplicate | STRENGTHEN | P2 | golden/BankSyncFailureRecoveryGoldenTest | DAO-only; no sync pipeline; isDuplicate window real |
| test/…/scenarios/CsvExportImportRoundtripTest.kt | 210 | 3 | 0 | ROOM | ExpenseDao date-range queries | STRENGTHEN | P2 | golden/CsvExportImportRoundtripGoldenTest | No CSV anywhere (misnomer); range boundaries real |
| test/…/scenarios/CurrencyRateStalenessScenarioTest.kt | 145 | 4 | 0 | MOCKED | CurrencyConverter 24h staleness | STRENGTHEN | P1 | golden/StaleRateCurrencyConversionGoldenTest, domain/currency tests | Tests 1/3 near-tautology; tests 2/4 real staleness+fallback |
| test/…/scenarios/DatabaseIntegrityTest.kt | 187 | 3 | 0 | ROOM | DatabaseIntegrityScanner | KEEP | P1 | — (only scanner test) | Seeds real violation; dedupeKey unique-index IGNORE proven |
| test/…/scenarios/EmailReceiptPipelineScenarioTest.kt | 212 | 3 | 0 | ROOM | EmailReceiptSourceDao dedupe | STRENGTHEN | P2 | data/email/EmailReceiptParserTest | insertOrIgnore dedupe real; no pipeline exercised |
| test/…/scenarios/ExpenseDaoAggregateFilterTest.kt | 188 | 5 | 0 | ROOM | ExpenseDao aggregate SQL | KEEP | P1 | domain/tax BusinessExpenseRepository tests | Real filter semantics: business-only, not-mine, null-key |
| test/…/scenarios/GroupLifecycleContractTest.kt | 272 | 6 | 0 | ROOM+MOCKED | GroupLifecycleCoordinator | MERGE | P1 | #10 (DUP 4/6 tests) | Survivor #10; carry removeMember-open-balance test |
| test/…/scenarios/GroupLifecycleScenarioTest.kt | 749 | 33 | 0 | ROOM+MOCKED | GroupLifecycleCoordinator (all 7 methods) | KEEP | P0 | #9 (DUP), GroupTransactionCoordinatorTest | Real legal path; NaN/Inf settlement guards; balance mocked |
| test/…/scenarios/GroupSettlementLifecycleScenarioTest.kt | 218 | 3 | 0 | ROOM | group/settlement DAOs | MERGE | P2 | #10, #27, golden GroupSettlementBudgetOffset | Pure insert-readback; coordinator-level dup |
| test/…/scenarios/HeatmapNormalizesCurrencyTest.kt | 234 | 3 | 0 | ROOM | AnalyticsCurrencyNormalizer + CurrencyConverter | KEEP | P1 | contracts/MoneyContractTest, consistency tests | Fake store good; relaxed timeProvider "fresh rates" by luck |
| test/…/scenarios/InvestmentGoldenScenarioTest.kt | 233 | 3 | 0 | ROOM+MOCKED | InvestmentTracker | KEEP | P1 | domain/investment/InvestmentTrackerTest, #14 | addHolding atomic; exact bucket math; not golden-locked |
| test/…/scenarios/InvestmentPortfolioScenarioTest.kt | 218 | 3 | 0 | ROOM | Investment/InvestmentValue DAOs | MERGE | P2 | #13 | Totals assertNotNull-only; range-exclusivity worth keeping |
| test/…/scenarios/LocationMapScenarioTest.kt | 177 | 3 | 0 | ROOM | MerchantLocationDao | KEEP | P2 | — (only MerchantLocationDao test) | upsert hitCount increment + area scoping contracts |
| test/…/scenarios/MapMarkerConversionCurrencyTest.kt | 185 | 3 | 0 | PURE | (test-local helper only) | DELETE | P4 | — | Tests its own if/else; zero production marker code exists |
| test/…/scenarios/MixedCurrencyCoreFinancialScenarioTest.kt | 245 | 3 | 0 | ROOM | MoneyAggregate factories | MERGE | P2 | #18, #5 (test 2 DUP), domain/core/money | Seeding decorative, never read into aggregate |
| test/…/scenarios/MoneyAggregateBuilderTest.kt | 291 | 8 | 0 | MOCKED | MoneyAggregateBuilder | KEEP | P0 | MoneyAggregateBuilderRestrictionTest (sibling pkg) | Solid behavior tests; MOVE to domain/core/money/ |
| test/…/scenarios/MoneyAggregateConversionScenarioTest.kt | 535 | 6 | 0 | ROOM | MoneyAggregate, MoneyAmount | REWRITE | P2 | #20 (throw test DUP), #18 | Self-built aggregates asserted; only test 5 is real |
| test/…/scenarios/MulticurrencyPartialRateScenarioTest.kt | 299 | 6 | 0 | PURE/ROOM | MoneyAmount helpers, ScenarioSeeder | MERGE | P2 | #19 (DUP throw test), domain/core/money | No conversion attempted; tests 3/4 assert the seeder |
| test/…/scenarios/NotificationPipelineScenarioTest.kt | 304 | 5 | 0 | MOCKED+ROOM | GreekBankParser + ScenarioSeeder | STRENGTHEN | P1 | CrossParserConsistencyTest, GreekBankParserStressTest, golden #13 | Real parser on real Greek text; "pipeline" never runs |
| test/…/scenarios/PrivacyCloudLocationDeniedScenarioTest.kt | 114 | 3 | 0 | MOCKED | CloudAi/Location/CompositePrivacyGate | MERGE | P1 | #23 (DUP test 3 verbatim), golden PrivacyGateEnforcement | Survivor #23; carry unique GPS-denied test |
| test/…/scenarios/PrivacyGateContractTest.kt | 294 | 10 | 0 | MOCKED/PURE | privacy gates, DefaultRedactionSanitizer, PrivacySettings | KEEP | P0 | #22, contracts/PrivacyStorageContractTest | Fail-closed defaults + sanitizer determinism locked |
| test/…/scenarios/ReceiptLifecycleDbContractTest.kt | 306 | 4 | 0 | ROOM | receipt tables DAO contract | STRENGTHEN | P1 | golden ReceiptMatchingNoDoubleCount, #25 | Coordinator bypass is documented; no unique-index test |
| test/…/scenarios/ReceiptPreOcrDedupeScenarioTest.kt | 132 | 2 | 0 | ROOM | ScannedReceiptDao.getByImageHash | MERGE | P2 | #24 | Thin lookup checks; fold into #24 |
| test/…/scenarios/RecurringNoDoubleCountScenarioTest.kt | 345 | 5 | 0 | ROOM+seeder | RecurringOccurrence/Reminder DAOs | REWRITE | P1 | golden #18/#19 (batch 01) | Headline test tautological (separate tables can't collide) |
| test/…/scenarios/SharedExpenseGroupScenarioTest.kt | 371 | 4 | 0 | ROOM | group DAOs, Expense.effectiveAmount | MERGE | P2 | #10, #11, e2e/SharedExpenseFlowTest | Share math computed in test (tautology); keep effAmount check |
| test/…/scenarios/TaxGoldenScenarioTest.kt | 194 | 2 | 0 | MOCKED+ROOM | TaxEstimator, BusinessExpenseRepository | STRENGTHEN | P1 | domain/tax/TaxEstimatorTest (F-05) | Test 1 weak non-empty asserts; test 2 real DB mileage math |
| test/…/scenarios/TransactionLifecycleCoordinatorDbContractTest.kt | 287 | 4 | 0 | ROOM | TransactionLifecycleCoordinator | KEEP | P0 | domain/transaction lifecycle tests (F-02), golden #23 | Real legal path: create/dedupe/update/delete + events |
| test/…/scenarios/TransactionLifecycleDbContractTest.kt | 268 | 4 | 0 | ROOM+seeder | ScenarioSeeder, ScenarioAssertions | REWRITE | P2 | fixture users package-wide | Name lies: tests the seeder, not the lifecycle |
| test/…/scenarios/TransactionTargetedUpdateSideEffectsTest.kt | 301 | 4 | 0 | ROOM+coVerify | TransactionLifecycleCoordinator targeted updates | STRENGTHEN | P0 | #29 | Key regeneration + recurring unlink/link verified; test 4 dups 1 |

## Batch 09 — data/ai

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/data/ai/provider/CloudCategorizationAssistServiceTest.kt | 509 | 11 | 0 | MOCKED | CloudCategorizationAssistService | KEEP | P0 | CloudReceiptItemCategorizationServiceTest, CloudPayloadPolicyTest (domain/privacy) | Real interceptor HTTP; asserts alias-only prompt, strict parse, fail-closed ctor; overturn 2026-05 REWRITE |
| test/…/data/ai/provider/CloudDashboardBriefingServiceTest.kt | 264 | 5 | 0 | MOCKED | CloudDashboardBriefingService | REWRITE | P1 | DashboardBriefingResponseParserTest (complementary) | F-11/F-19: 2-arg test ctor installs fail-closed gate → interceptor never hit; 4 tests fail |
| test/…/data/ai/provider/CloudDedupeJudgeServiceTest.kt | 258 | 5 | 0 | MOCKED | CloudDedupeJudgeService | KEEP | P1 | OnDeviceDedupeJudgeServiceTest (complementary layer) | Real JSON verdicts, offline/disabled/parse-error; relaxed PrivacyGate mock |
| test/…/data/ai/provider/CloudQueryInterpretationServiceTest.kt | 349 | 8 | 0 | MOCKED | CloudQueryInterpretationService | KEEP | P0 | OnDeviceQueryInterpretationServiceTest (complementary) | Alias-only prompt assert; gate-denied → verify 0 HTTP; CancellationException rethrow |
| test/…/data/ai/provider/CloudReceiptAssistServiceAuditTest.kt | 159 | 1 | 0 | MOCKED | CloudReceiptAssistService + PrivacyAuditLogger | KEEP | P0 | CloudAuditProviderProvenanceTest (domain/privacy) | PreparedCloudPayload provenance (hash, redactionApplied, rawTextIncluded=false) via hand-written recorder |
| test/…/data/ai/provider/CloudReceiptAssistServiceTest.kt | 269 | 6 | 0 | MOCKED | CloudReceiptAssistService | STRENGTHEN | P1 | CloudReceiptAssistServiceAuditTest (complementary) | Image suppressed when redactBeforeCloud; misleading name on raw-prompt helper test |
| test/…/data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt | 267 | 3 | 0 | MOCKED | CloudReceiptItemCategorizationService | STRENGTHEN | P1 | OnDeviceReceiptItemCategorizationServiceTest, CloudPayloadPolicyTest | Test 1 name contradicts assertion (tautology); alias-mapping tests real; overturn 2026-05 KEEP-P0 |
| test/…/data/ai/provider/CloudReviewExplanationServiceTest.kt | 82 | 2 | 0 | MOCKED | CloudReviewExplanationService | KEEP | P1 | OnDeviceReviewExplanationServiceTest (complementary) | Typed PrivacyDenied + capability; overturn 2026-05 DELETE |
| test/…/data/ai/provider/CloudWarrantyExtractionServiceTest.kt | 205 | 4 | 0 | MOCKED | CloudWarrantyExtractionService | KEEP | P2 | WarrantyTrackerRepositoryTest (complementary) | Full field parse incl. return-policy-only; relaxed PrivacyGate mock |
| test/…/data/ai/provider/DashboardBriefingResponseParserTest.kt | 37 | 3 | 0 | PURE | DashboardBriefingResponseParser | KEEP | P1 | CloudDashboardBriefingServiceTest | NaN/out-of-range confidence rejected |
| test/…/data/ai/provider/DefaultAiEnvironmentMonitorTest.kt | 116 | 4 | 0 | ROBOLECTRIC | DefaultAiEnvironmentMonitor | KEEP | P2 | — | Fake clock TTL boundary checks; mockkObject(Generation) static |
| test/…/data/ai/provider/HybridReceiptItemCategorizationServiceTest.kt | 90 | 1 | 0 | MOCKED | HybridReceiptItemCategorizationService | STRENGTHEN | P2 | HybridServiceDelegationTest (complementary) | Only ON_DEVICE route; no cloud/fallback/disabled coverage |
| test/…/data/ai/provider/HybridServiceDelegationTest.kt | 486 | 7 | 0 | MOCKED | Hybrid{Receipt,Categorization,Query}Service | KEEP | P1 | SmartReceiptAssistServiceTest (complementary), DefaultAiCapabilityRouterTest | Exact-count delegation × 4 routes; stale TODO labels; FRAGILE harness |
| test/…/data/ai/provider/OnDeviceCategorizationAssistServiceTest.kt | 217 | 21 | 0 | PURE | OnDeviceCategorizationAssistService | KEEP | P1 | CloudCategorizationAssistServiceTest (complementary) | Excellent strict-parse matrix (fences, NaN, zero id, alt ids) |
| test/…/data/ai/provider/OnDeviceDashboardBriefingServiceTest.kt | 111 | 5 | 0 | PURE | OnDeviceDashboardBriefingService | KEEP | P0 | CloudDashboardBriefingServiceTest (complementary) | Asserts redacted insight prompt: alias merchant, amount bucket, no raw values |
| test/…/data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt | 120 | 8 | 0 | PURE | OnDeviceDedupeJudgeService | KEEP | P1 | CloudDedupeJudgeServiceTest (complementary) | Verdict/targetId zero-drop, NaN confidence, unknown enum |
| test/…/data/ai/provider/OnDeviceNotificationParserTest.kt | 60 | 3 | 0 | MOCKED | OnDeviceNotificationParser | KEEP | P2 | domain parser tests | Transfer metadata kept/dropped correctly; stale TODO labels; relaxed mocks |
| test/…/data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt | 137 | 7 | 0 | PURE | OnDeviceQueryInterpretationService | KEEP | P1 | CloudQueryInterpretationServiceTest (complementary) | Alias-only lookup keys; alias→real resolution; explicit period |
| test/…/data/ai/provider/OnDeviceReceiptAssistServiceTest.kt | 141 | 8 | 0 | PURE | OnDeviceReceiptAssistService | KEEP | P1 | CloudReceiptAssistServiceTest, SmartReceiptAssistServiceTest | Image attach/omit; merchant/total/date parse; missing→null |
| test/…/data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt | 48 | 1 | 0 | PURE | OnDeviceReceiptItemCategorizationService | KEEP | P3 | CloudReceiptItemCategorizationServiceTest | Single keyword-fallback test; thin but real |
| test/…/data/ai/provider/OnDeviceReviewExplanationServiceTest.kt | 86 | 6 | 0 | PURE | OnDeviceReviewExplanationService | KEEP | P2 | CloudReviewExplanationServiceTest (complementary) | Prompt facts; blank headline/body → null |
| test/…/data/ai/provider/SmartReceiptAssistServiceTest.kt | 474 | 8 | 0 | MOCKED | SmartReceiptAssistService | KEEP | P1 | HybridServiceDelegationTest (complementary) | Real fall-through + attemptDetails; no cloud route when router denies; FRAGILE 8-mock ctor |
| test/…/data/ai/provider/internal/CloudJsonParserTest.kt | 156 | 12 | 0 | PURE | CloudJsonParser | KEEP | P1 | used by all cloud/on-device parsers | Nested braces, escapes, fences, strict double/long |
| test/…/data/ai/provider/internal/CloudRetryPolicyTest.kt | 71 | 7 | 0 | PURE | CloudRetryPolicy | KEEP | P1 | CloudDashboardBriefingServiceTest (consumer) | Retryable codes/IO causes, bounded backoff+ jitter |
| test/…/data/ai/worker/DailyBriefingWorkerTest.kt | 797 | 24 | 0 | ROBOLECTRIC | DailyBriefingWorker | STRENGTHEN | P0 | WorkerExecutionGuardTest, SyncProactiveBriefingWorkUseCaseTest | Worker lifecycle + idempotent notification IDs + reschedule matrix; guard mocked (mirrored, not real); 1 tautological test; FRAGILE |

## Batch 10 — data/backup + currency fixture

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/data/backup/AppOperationalStateTest.kt | 95 | 7 | 0 | MOCKED | AppOperationalState, RestoreMaintenanceMode (mock) | REWRITE | P1 | (none — only test of AppOperationalState) | Tautology: seeds own flow copy, asserts it back |
| test/…/data/backup/AssetRestoreAtomicityTest.kt | 232 | 7 | 0 | ROBOLECTRIC | RestoreJournal | KEEP | P0 | #11/#13, golden roundtrip (complementary) | Real journal+fixed clock; privacy strip check |
| test/…/data/backup/BackupVerifierManifestTest.kt | 155 | 7 | 0 | ROBOLECTRIC | BackupVerifier | KEEP | P0 | DatabaseBackupRepositoryImplTest, BackupRestoreContractTest | Typed exceptions, Tier-1 manifest, aggregates |
| test/…/data/backup/CostbackupBundleLimitsTest.kt | 255 | 9 | 0 | PURE | CostbackupBundle | KEEP | P0 | P7BugFixesTest (complementary) | Zip-bomb limits, legacy createdAt fallback |
| test/…/data/backup/DataStoreMaintenanceSafeDiagnosticSinkTimeProviderTest.kt | 215 | 4 | 0 | MOCKED | DataStoreMaintenanceSafeDiagnosticSink | KEEP | P0 | #9 (different impl) | Hostile-payload redaction; DataStore singleton hazard |
| test/…/data/backup/DatabaseBarrierTest.kt | 195 | 19 | 0 | MOCKED | DatabaseWriteBarrier, DatabaseReadBarrier | KEEP | P0 | golden/#20/#24 (they MERGE here), crosslayer/arch guards | Canonical barrier matrix; F-14 aligned in source |
| test/…/data/backup/ExportReadBarrierTest.kt | 143 | 9 | 0 | MOCKED | DatabaseReadBarrier, DatabaseReadBarrierFlowExt | MERGE | P1 | #6 (DUP), survivor: DatabaseBarrierTest | Move Flow-helper tests; F-16 nested-runTest hazard |
| test/…/data/backup/MaintenanceOperationRunnerTest.kt | 167 | 10 | 0 | MOCKED | MaintenanceOperationRunner, WorkerDrainController | STRENGTHEN | P0 | architecture/WriteBarrierArchitectureGuardTest | F-18 root cause: stale default-policy timeout test |
| test/…/data/backup/MaintenanceSafeDiagnosticSinkTest.kt | 91 | 5 | 0 | MOCKED | TimberMaintenanceSafeDiagnosticSink | DELETE | P3 | #5, #6 | No-crash + mock-echo only; Timber never observed |
| test/…/data/backup/P7BugFixesTest.kt | 429 | 5 | 0 | ROBOLECTRIC | RestoreMaintenanceMode, RestoreJournal, CostbackupBundle, DatabaseBackupRepositoryImpl | STRENGTHEN | P0 | #4/#11/#13 | 3 solid; 2 tests reimplement prod logic; FRAGILE reflection |
| test/…/data/backup/RestoreJournalDurabilityTest.kt | 80 | 2 | 0 | ROBOLECTRIC | RestoreJournal | KEEP | P0 | #2/#13 (complementary) | fsync-path roundtrip guard |
| test/…/data/backup/RestoreJournalImporterFailureTest.kt | 127 | 3 | 0 | ROBOLECTRIC | RestoreJournalImporter | KEEP | P0 | architecture/DirectEventDaoInsertGuardTest | Slot-captured ledger rows; idempotent import |
| test/…/data/backup/RestoreJournalTimeProviderTest.kt | 145 | 8 | 0 | ROBOLECTRIC | RestoreJournal, TimeProvider | KEEP | P0 | #2/#11 (complementary) | No-wall-clock contract; legacy JSON fallback |
| test/…/data/currency/ExchangeRateStoreAdapterTest.kt | 195 | 8 | 0 | MOCKED | ExchangeRateStoreAdapter | STRENGTHEN | P1 | e2e/golden use adapter (complementary) | F-21: validDate mapping never asserted |

## Batch 11 — data/database

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/data/database/DatabaseMigrationProofTest.kt | 208 | 3 | 0 | ROOM | DatabaseMigrations, DatabaseSchemaPolicy | KEEP | P1 | MigrationRegistrationTest | chain 145→148 seed survival; gapless-chain + fresh/migrated parity; name hardcodes 148 |
| test/…/data/database/DomainTransactionRunnerTest.kt | 255 | 13 | 0 | MOCKED | DomainTransactionRunner, TransactionContext, CancellationSafe | STRENGTHEN | P2 | GroupTransactionCoordinatorTest | fake-runner tests test the double; one assertNotNull-only |
| test/…/data/database/GroupTransactionCoordinatorTest.kt | 1241 | 33 | 0 | ROOM | GroupTransactionCoordinator, TransactionLifecycleCoordinator | KEEP | P0 | GroupLifecycleScenarioTest; GroupsRepositoryImplTest | real atomicity/share math; 2 weak conditional tests; FRAGILE ctor |
| test/…/data/database/MigrationRegistrationTest.kt | 177 | 7 | 0 | ROOM | AppDatabase migrations | KEEP | P1 | DatabaseMigrationProofTest | F-15 root cause (missing 119/120/141/142.json) now fixed; see notes |
| test/…/data/database/converter/ConvertersTest.kt | 34 | 4 | 0 | PURE | Converters | KEEP | P2 | — | roundtrip + UNKNOWN fallback |
| test/…/data/database/dao/BackgroundJobRunDaoTest.kt | 208 | 8 | 0 | ROOM | BackgroundJobRunDao | KEEP | P2 | WorkerRunLogger tests | prior "skeleton" verdict overturned — real coverage |
| test/…/data/database/dao/BankConnectionDaoTest.kt | 274 | 12 | 0 | ROOM | BankConnectionDao | KEEP | P1 | — | credential wipe on disconnect (tokens→NULL), scoped, idempotent |
| test/…/data/database/dao/BudgetAdjustmentDaoTest.kt | 139 | 3 | 0 | ROOM | BudgetAdjustmentDao | KEEP | P2 | — | caller-timestamp semantics, strict expiry boundary |
| test/…/data/database/dao/EmailReceiptDaoTest.kt | 266 | 15 | 0 | ROOM | EmailReceiptDao | KEEP | P2 | EmailReceiptParser tests | message-ID dedupe (-1), fingerprint, deleteOlderThan |
| test/…/data/database/dao/ExchangeRateDaoTest.kt | 190 | 10 | 0 | ROOM | ExchangeRateDao | KEEP | P2 | androidTest ExchangeRateDaoTest (DUP-lite) | historical as-of rate, upsert, cleanup; JVM side is CI-effective |
| test/…/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt | 461 | 15 | 0 | PURE | (none — no DAO used) | DELETE | P4 | TimePeriodAnalyticsAlignmentTest | tautologies (`5000 <= 5000`); salvage last TimePeriodUtils test |
| test/…/data/database/dao/InvestmentDaoTest.kt | 254 | 12 | 0 | ROOM | InvestmentDao | KEEP | P2 | — | prior "skeleton" overturned; real aggregate math (3000/250/2500) |
| test/…/data/database/dao/PrivacyAuditDaoTest.kt | 129 | 6 | 0 | ROOM | PrivacyAuditDao | KEEP | P2 | — | prior "skeleton" overturned; insert/limit/DESC/empty |
| test/…/data/database/dao/RawNotificationDaoTest.kt | 246 | 9 | 0 | ROOM | RawNotificationDao | KEEP | P2 | NotificationRepository tests | prior "mock-only" overturned; dedupe UNIQUE (-1); purge column untested |
| test/…/data/database/dao/ReceiptEventDaoTest.kt | 144 | 6 | 0 | ROOM | ReceiptEventDao | KEEP | P2 | ReceiptLifecycleCoordinatorTest | ordering, no cross-receipt mixing |
| test/…/data/database/dao/ReceiptExpenseLinkDaoTest.kt | 214 | 9 | 0 | ROOM | ReceiptExpenseLinkDao | KEEP | P2 | ReceiptMatching tests | FK-seeded links, pair-scoped unlink, IGNORE -1 |
| test/…/data/database/dao/RecommendationDaoTest.kt | 525 | 15 | 0 | ROOM | RecommendationDao | KEEP | P1 | androidTest RecommendationDaoTest (DUP-lite) | cap-5, overflow archive, expire idempotence; JVM is CI-effective |
| test/…/data/database/dao/RecurringOccurrenceDaoTest.kt | 239 | 14 | 0 | ROOM | RecurringOccurrenceDao | KEEP | P2 | Recurring lifecycle tests | targeted updateStatus, date range, ordering |
| test/…/data/database/dao/SavingsSweepPlanDaoTest.kt | 137 | 4 | 0 | ROOM | SavingsSweepPlanDao | KEEP | P2 | — | caller-timestamp + strict monthEnd boundary |
| test/…/data/database/dao/ScannedReceiptClaimTest.kt | 167 | 5 | 0 | ROOM | ScannedReceiptDao.claimForAutoMatch | KEEP | P0 | ReceiptMatchLifecycleServiceTest | atomic CAS claim vs all 5 start states; double-link guard |
| test/…/data/database/dao/SpendingPersonalityProfileDaoTest.kt | 92 | 2 | 0 | ROOM | SpendingPersonalityProfileDao | KEEP | P3 | — | targeted markAsViewed; thin |
| test/…/data/database/dao/SplitItemAssignmentDaoTest.kt | 125 | 3 | 0 | ROOM | SplitItemAssignmentDao | KEEP | P2 | — | exact paidAt incl. older-than-creation proof |
| test/…/data/database/dao/SplitTemplateDaoTest.kt | 123 | 4 | 0 | ROOM | SplitTemplateDao | KEEP | P3 | — | incrementUseCount semantics |
| test/…/data/database/dao/SubscriptionCandidateDaoTest.kt | 144 | 4 | 0 | ROOM | SubscriptionCandidateDao | KEEP | P3 | — | convert/reject field semantics |
| test/…/data/database/dao/TransactionEventDaoTest.kt | 162 | 7 | 0 | ROOM | TransactionEventDao | KEEP | P2 | lifecycle coordinator tests | ordering, nullable expenseId |
| test/…/data/database/dao/WarrantyReminderDeliveryDaoTest.kt | 258 | 10 | 0 | ROOM | WarrantyReminderDeliveryDao | KEEP | P1 | WarrantyReminderWorker tests | claim-before-notify state machine, stale recovery, FK cascade |
| test/…/data/database/entity/CategoryTest.kt | 52 | 7 | 0 | PURE | Category | KEEP | P3 | — | init validation (name/color/icon) |
| test/…/data/database/entity/DedupeKeyTest.kt | 104 | 9 | 0 | PURE | Expense.generateDedupeKey | KEEP | P0 | ExpenseEntityStressTest; DedupeKeyProducerConsistencyTest | currency-required ISSUE-6 regression; bucket boundaries |
| test/…/data/database/entity/ExpenseEntityStressTest.kt | 600 | 42 | 1 | PURE | Expense (dedupe key, effectiveAmount) | NIGHTLY | P3 | DedupeKeyTest (DUP) | class-level @Ignore; 2 empty-body tests; fuzz unique |
| test/…/data/database/entity/MileageTrackingValidationTest.kt | 24 | 1 | 0 | PURE | MileageTracking | DELETE | P4 | — | constructor passthrough tautology; entity has no validation |
| test/…/data/database/model/ExpenseWithCategoryFormattedAmountTest.kt | 141 | 11 | 0 | PURE | ExpenseWithCategory.formattedAmount | KEEP | P1 | — | polarity/currency placement/effectiveAmount basis |
| test/…/data/database/model/ExpenseWithCategoryFormattedTimeTest.kt | 79 | 5 | 0 | PURE | ExpenseWithCategory.formattedDate/Time | KEEP | P3 | — | shadowing regression; regex assumes English month locale |

## Batch 12 — data email/location/privacy

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/data/email/EmailReceiptIngestionServiceTest.kt | 960 | 24 | 0 | MOCKED | EmailReceiptIngestionService | STRENGTHEN | P1 | #2; DedupeKeyProducerConsistencyTest; PrivacyBehavioralRegressionTest | FRAGILE; fingerprint-discrimination tests excellent; several mock-echo mapping tests |
| test/…/data/email/EmailReceiptIngestionServiceTransactionTest.kt | 137 | 1 | 0 | MOCKED | EmailReceiptIngestionService | KEEP | P1 | #1 | Legal-path pin (delegate-all-to-coordinator); stale prior verdict |
| test/…/data/email/provider/AmazonReceiptParserTest.kt | 41 | 1 | 0 | PURE | AmazonReceiptParser | STRENGTHEN | P1 | #1,#2 (mocked) | Real localized €12,34 + FR date; only 1 case |
| test/…/data/email/provider/AppleReceiptParserTest.kt | 205 | 11 | 0 | PURE | AppleReceiptParser | KEEP | P1 | #1,#2 (mocked) | Double-escape regression + sender gating; exemplary |
| test/…/data/email/provider/EmailReceiptParserTest.kt | 61 | 3 | 0 | PURE | BaseEmailParser | REWRITE | P1 | — | F-21: UTC-zone expected vs systemDefault production parse |
| test/…/data/email/provider/UberReceiptParserTest.kt | 127 | 6 | 0 | PURE | UberReceiptParser | REWRITE | P1 | — | F-21 tz family; mojibake bodies; assertions contradict names |
| test/…/data/location/AndroidForegroundLocationProviderTest.kt | 16 | 1 | 0 | PURE | AndroidForegroundLocationProvider | DELETE | P4 | — | Tautology re-confirmed (asserts pair equals itself) |
| test/…/data/location/CompositeGeocodingServiceStressTest.kt | 174 | 8 | 1 | MOCKED | CompositeGeocodingService | STRENGTHEN | P1 | #9 | @Ignore confirmed (line 26); not stress — dead unique coverage |
| test/…/data/location/CompositeGeocodingServiceTest.kt | 52 | 1 | 0 | MOCKED | CompositeGeocodingService | KEEP | P2 | #8 | Fallback-cascade; complementary to #8 |
| test/…/data/location/GeocodingCancellationTest.kt | 151 | 2 | 0 | MOCKED | executeCancellable, PhotonGeocodingService | KEEP | P1 | CancellationSafetyArchitectureGuardTest | Real OkHttp cancellation; latch flake risk minor |
| test/…/data/location/GeocodingRetryHttpSemanticsTest.kt | 127 | 5 | 0 | MOCKED | Photon/Nominatim/Geoapify/GooglePlaces | KEEP | P1 | — | Real interceptor 429/503 retry; typed GeocodingError |
| test/…/data/location/LocationBackfillWorkerTest.kt | 220 | 6 | 0 | ROBOLECTRIC | LocationBackfillWorker | STRENGTHEN | P1 | WorkerGuardStaticVerificationTest; SourceScanningArchitectureGuardTest | Guard stub duplicates real guard semantics; FRAGILE |
| test/…/data/location/MerchantKeyBackfillWorkerTest.kt | 169 | 5 | 0 | ROBOLECTRIC | MerchantKeyBackfillWorker | STRENGTHEN | P1 | WorkerSpecSchedulerTest; WorkerIdempotencyTest | F-20 (`still_broken` vs `stillbroken`); guard-stub duplication |
| test/…/data/location/NominatimGeocodingServiceLocaleTest.kt | 59 | 1 | 0 | MOCKED | NominatimGeocodingService | KEEP | P1 | — | Greek-locale dot-decimal URL guard; locale restored in finally |
| test/…/data/location/OverpassNearbyServiceTest.kt | 115 | 2 | 0 | MOCKED | OverpassNearbyService | KEEP | P2 | HaversineConsistencyTest | 429 retry + Greek name ranking, real JSON |
| test/…/data/location/internal/LogSanitizerTest.kt | 39 | 3 | 0 | PURE | anonymizeForLog (LogSanitizer) | KEEP | P1 | — | No raw leak, stable, distinct outputs |
| test/…/data/privacy/BackupEncryptionServiceTest.kt | 78 | 5 | 0 | PURE | BackupEncryptionService | KEEP | P0 | CostbackupBundleLimitsTest; DatabaseBackupRepositoryImplTest | AES-256-GCM fail-closed: bad tag, tamper, random salt/IV |
| test/…/data/privacy/DataRetentionWorkerTest.kt | 395 | 16 | 0 | ROBOLECTRIC | DataRetentionWorker | STRENGTHEN | P0 | RetentionTargetPurgeTest; RawStoragePolicyAuditTest; DbGuardPolicyFixtureTest | Strong sanitized-diagnostics; 1 SRCTEXT test; guard stub |
| test/…/data/privacy/ExportAnonymizerTest.kt | 161 | 4 | 0 | ROBOLECTRIC | ExportAnonymizer | KEEP | P0 | DatabaseBackupRepositoryImplTest | Real SQLite redaction across all PII tables; hashes preserved |
| test/…/data/privacy/PrivacySettingsRepositoryImplCorruptionTest.kt | 188 | 9 | 0 | ROBOLECTRIC | PrivacySettingsRepositoryImpl, PrivacySettings | STRENGTHEN | P1 | P8PrivacyFixesTest; PrivacySettingsLoadStateTest | Fail-closed defaults asserted; several constant-only tests |
| test/…/data/privacy/PrivacySettingsRepositoryImplWorkerGatingTest.kt | 153 | 6 | 0 | ROBOLECTRIC | PrivacySettingsRepositoryImpl | KEEP | P1 | WorkerSpecSchedulerTest | Over-cancel + no-reschedule regression guards; data_retention never gated |
| test/…/data/privacy/RetentionTargetPurgeTest.kt | 135 | 2 | 0 | ROOM | RetentionModule targets | KEEP | P0 | DataRetentionWorkerTest | Real Room + real production targets; nulls raw payload, keeps recent |

## Batch 13 — data/repository (1/2)

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| AccountingExportRepositoryTest.kt | 802 | 15 | 0 | MOCKED | AccountingExportRepository, DeterministicExpenseExportPager | KEEP | P1 | #17, DeterministicExpenseExportPagerTest, AccountingExportPolicyTest | Real file I/O, content asserts, privacy fail-closed |
| AiArtifactRepositoryImplTest.kt | 201 | 9 | 0 | MOCKED | AiArtifactRepositoryImpl | REWRITE | P3 | AiArtifactDao tests | 7/9 verify-only delegation (self-flagged TODO) |
| AiChatRepositoryImplTest.kt | 173 | 8 | 0 | MOCKED | AiChatRepositoryImpl | KEEP | P2 | — | History-disabled = no persist (privacy); 2 thin delegation |
| AnalyticsRepositoryAggregateTest.kt | 564 | 9 | 0 | MOCKED | AnalyticsRepository | KEEP | P1 | CurrencyConsistency tests, AdvancedAnalyticsEngine* | Real invariants: aggregate==totalSpent, failures grouped |
| AutomatedSavingsRuleStateRepositoryTest.kt | 196 | 6 | 0 | PURE | AutomatedSavingsRuleStateRepository | STRENGTHEN | P1 | domain savings tests | F-17: recreate-on-same-file after cancel() (DataStore collision) |
| BudgetRepositoryDiagnosticsTest.kt | 192 | 5 | 0 | MOCKED | BudgetRepository, DiagnosticEventWriter | KEEP | P1 | BudgetMonitor tests | Best-effort diagnostics contract; structured fields asserted |
| BudgetRepositoryHistoricalStatusTest.kt | 522 | 19 | 0 | MOCKED | BudgetRepository | KEEP | P0 | #8, #10, #11, BudgetMonitorTest | Period-end as-of rates, threshold bounds, real barrier test |
| BudgetRepositoryStressTest.kt | 550 | 15 | 0 | MOCKED | BudgetRepository | MERGE | P1 | #7, #10, #11 | §1-4 dup of #7; keep §5 rollover regressions (→ #11) |
| BudgetRepositorySuggestionsBatchTest.kt | 137 | 1 | 0 | MOCKED | BudgetRepository | KEEP | P2 | #10 | Single grouped-query batching contract |
| BudgetRepositoryTruncationTest.kt | 500 | 9 | 0 | MOCKED | BudgetRepository | KEEP | P1 | #8, #11 | Aggregate-not-capped regression; health thresholds real |
| BudgetRolloverTest.kt | 517 | 12 | 0 | MOCKED | BudgetRepository, BudgetCalculator | KEEP | P1 | #8, #10 | Canonical rollover math, real calculator, ISSUE-3 regression |
| BusinessExpenseRepositoryTest.kt | 69 | 2 | 0 | MOCKED | BusinessExpenseRepository | KEEP | P2 | — | NaN rejected pre-DAO; small but real |
| CategoryRepositoryStressTest.kt | 90 | 4 | 4 | MOCKED | CategoryRepository | DELETE | P3 | #14 | Class @Ignore holds; zero assertions; Lazy-wrapper mock |
| CategoryRepositoryTest.kt | 155 | 9 | 0 | MOCKED | CategoryRepository | STRENGTHEN | P2 | #13 | Overturns 2026-05 DELETE; real case-dedupe/scope contracts |
| DashboardContractsAdapterTest.kt | 109 | 2 | 0 | MOCKED | DashboardContractsAdapter | KEEP | P2 | FinancialWeatherRepositoryTest | Confirmed-only feed + isPartial propagation |
| DatabaseBackupRepositoryImplTest.kt | 1237 | 24 | 0 | ROBOLECTRIC | DatabaseBackupRepositoryImpl | KEEP | P0 | P7BugFixesTest, BackupRestoreIntegrityE2ETest | Real SQLite staged import/rollback; 1 SRCTEXT guard; gaps documented |
| DeterministicExpenseExportPagerTest.kt | 57 | 2 | 0 | MOCKED | DeterministicExpenseExportPager | KEEP | P2 | #1 | Focused pager unit; complementary to #1 e2e |
| ExpenseRepositoryStressTest.kt | 256 | 13 | 13 | MOCKED | ExpenseRepository | DELETE | P4 | #19, #20 | Class @Ignore; STALE: asserts direct expenseDao.delete (prod routes coordinator, ExpenseRepository.kt:417) |
| ExpenseRepositoryTest.kt | 330 | 9 | 0 | MOCKED | ExpenseRepository | KEEP | P1 | #18, #20, architecture guards | Legal-path positive (coordinator delegation); SQL-string asserts FRAGILE |
| ExpenseRepositoryTruncationTest.kt | 215 | 9 | 0 | MOCKED | ExpenseRepository | KEEP | P1 | #19 | Uncapped-read regression (data-loss guard) |
| ExpenseWriteStoreObservabilityTest.kt | 298 | 6 | 0 | MOCKED | TransactionLifecycleCoordinator, TransactionSideEffectPlanner | STRENGTHEN | P0 | domain lifecycle tests (F-02 area), golden | Real coordinator events/keys; last test = stdlib withTimeoutOrNull (drop) |
| FinancialWeatherRepositoryTest.kt | 637 | 11 | 0 | MOCKED | FinancialWeatherRepository, ForecastInputAssembler | KEEP | P1 | DashboardContractsAdapterTest, forecast engine tests | Real assembler; effectiveAmount + fail-safe UNKNOWN currency |
| GroupsRepositoryImplTest.kt | 299 | 6 | 0 | MOCKED | GroupsRepositoryImpl | KEEP | P2 | GroupLifecycleCoordinator tests | joinedAt delete-guard boundaries; coordinator delegation |
| MerchantNormalizationRepositoryTest.kt | 127 | 10 | 0 | MOCKED | MerchantNormalizationRepository | KEEP | P2 | MerchantCanonicalizer tests | Result-code mapping + fallback chain real |
| MerchantRulesRepositoryTest.kt | 34 | 4 | 0 | PURE | MerchantRulesRepository | KEEP | P2 | — | Pure sanitization; exemplary |
| MultiCurrencyRepositoryTest.kt | 862 | 27 | 0 | MOCKED | MultiCurrencyRepository | KEEP | P1 | currency/ CanonicalMultiCurrencyFixture, Budget* | Aggregate-path + type-agnostic + null-bucket regressions |
| NotificationProcessingPipelineAtomicityTest.kt | 396 | 8 | 0 | MOCKED | NotificationProcessingPipeline | KEEP | P0 | #29, #30, DedupeKeyProducerConsistencyTest | markProcessed atomicity/rollback; 29-arg ctor FRAGILE |
| NotificationProcessingPipelineOversizedAmountTest.kt | 114 | 8 | 0 | PURE | NotificationProcessingPipeline (static detectors) | KEEP | P1 | #29 (DUP: 2 tests) | Pure detector; PAN/keyword scoring real |
| NotificationProcessingPipelineReliabilityTest.kt | 847 | 17 | 0 | MOCKED | NotificationProcessingPipeline, DuplicateDetectionPolicy | STRENGTHEN | P0 | #27, #28 (DUP), #30 | Salvage/boundary/concurrency; 2 tests dup of #28; FRAGILE ctor |
| NotificationProcessingPipelineSourceLinkTest.kt | 183 | 2 | 0 | MOCKED | NotificationProcessingPipeline | KEEP | P1 | #29 | Source-link failure ≠ lost review; post-commit diagnostics |
| NotificationProcessingPipelineStressTest.kt | 587 | 28 | 28 | MOCKED | (none — test-local simulation) | DELETE | P4 | #27, #29 | Class @Ignore; reimplements pipeline in helpers; `result != null |
| NotificationRepositoryDeleteAllNotificationsClockTest.kt | 117 | 2 | 0 | MOCKED | NotificationRepository | KEEP | P1 | NotificationRepositoryStressTest (other batch) | Barrier verified + injected-clock audit event |

## Batch 14 — data/repository (2/2) + security/service/speech/store

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/data/repository/NotificationRepositoryStressTest.kt | 306 | 21 | 21 (class) | MOCKED | NotificationRepository | REWRITE | P3 | NotificationRepositoryDeleteAllNotificationsClockTest; (no live NotificationRepositoryTest exists) | @Ignore class; flow tests tautological; delete/stats-decrement unique but dead |
| test/…/data/repository/PlannedExpenseRepositoryDiagnosticsTest.kt | 134 | 5 | 0 | MOCKED | PlannedExpenseRepository | KEEP | P1 | DashboardContractsAdapterTest, FinancialWeatherRepositoryTest | Best-effort diagnostics contract; real event assertions |
| test/…/data/repository/ReceiptRepositoryStatementDuplicateTest.kt | 216 | 1 | 0 | MOCKED | ReceiptRepository | KEEP | P1 | ReceiptRepositoryStressTest, DedupeKeyProducerConsistencyTest | Currency-aware dup lookup; FRAGILE (22 ctor deps); ledger F-21 |
| test/…/data/repository/ReceiptRepositoryStressTest.kt | 408 | 12 | 12 (class) | MOCKED | ReceiptRepository | REWRITE | P3 | ReceiptLifecycleCoordinatorTest; (no live repo-level test) | @Ignore class; OCR-fallback/text-preserve behavior stranded; FRAGILE |
| test/…/data/repository/RecommendationRepositoryTest.kt | 426 | 17 | 0 | MOCKED | RecommendationRepository | KEEP | P2 | RecommendationLifecycleManagerTest, RecommendationStateManagerTest | saveAll cap/priority/merge tests are real; setMain never reset |
| test/…/data/repository/RecurringExpenseRepositoryTest.kt | 87 | 3 | 0 | MOCKED | RecurringExpenseRepository | KEEP | P1 | CashFlowCalculatorTest (indirect) | Exercises legal path (coordinator.createRule); F-13 fix pattern in place |
| test/…/data/repository/ReviewQueueRepositoryStressTest.kt | 171 | 8 | 8 (class) | MOCKED | ReviewQueueRepository | MERGE | P4 | ReviewQueueRepositoryTest | @Ignore dead; port 2 bulk-op verifies, then delete |
| test/…/data/repository/ReviewQueueRepositoryTest.kt | 604 | 11 | 0 | MOCKED | ReviewQueueRepository | KEEP | P0 | ReviewQueueRepositoryStressTest, DedupeKeyProducerConsistencyTest | Approve/dedup/status contracts; ledger F-08+F-13 (8 failed); FRAGILE |
| test/…/data/repository/SavingsContributionHistoryRepositoryTest.kt | 112 | 3 | 0 | MOCKED | SavingsContributionHistoryRepository | KEEP | P1 | SavingsGamificationEngineTest (indirect) | Real temp-file DataStore persistence; gold standard |
| test/…/data/repository/WarrantyTrackerRepositoryTest.kt | 804 | 25 | 0 | MOCKED | WarrantyTrackerRepository | KEEP | P1 | AutoCreateWarrantyFromReceiptUseCaseTest, WarrantyExpirationWorkerTest | Confidence bands w/ exact boundaries; calendar-math + privacy assertions; some verify-only TODOs |
| test/…/data/security/SecureKeyStorageTest.kt | 341 | 17 | 17 (class) | MOCKED | SecureKeyStorage | REWRITE | P0 | (AI provider tests mock SecureKeyStorage, not test it) | P0 area with ZERO active coverage; @Ignore + tautological prefs mocks |
| test/…/data/service/AndroidNotificationServiceTest.kt | 74 | 2 | 0 | ROBOLECTRIC | AndroidNotificationService | KEEP | P1 | — | Permission gate → NOT_DELIVERED, notify not called; metric gate contract |
| test/…/data/speech/AndroidSpeechInputGatewayTest.kt | 89 | 3 | 0 | ROBOLECTRIC | AndroidSpeechInputGateway | KEEP | P2 | — | Injected checkers/factory; real error-surface contracts |
| test/…/data/store/ExpenseStoreTest.kt | 138 | 11 | 0 | MOCKED | ExpenseWriteStore, ExpenseReadStore, DatabaseWriteBarrier | KEEP | P0 | ExpenseWriteStoreObservabilityTest, DatabaseBarrierTest | F-09/F-14 RESOLVED statically (see findings) |

## Batch 15 — domain/ai

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/ai/model/AiArtifactPresentationTest.kt | 68 | 3 | 0 | PURE | AiArtifactRecord.toDiagnosticsOrNull | KEEP | P3 | AiRuntimeStatusModelsTest (complementary) | Pins diagnostic display strings; low value but cheap |
| test/…/domain/ai/model/AiRuntimeStatusModelsTest.kt | 35 | 2 | 0 | PURE | AiCapabilityRuntimeStatus.routeDisplayText | KEEP | P3 | #1 (complementary) | Null-route handling; string pin |
| test/…/domain/ai/model/CategorizationAssistInputTest.kt | 27 | 1 | 0 | PURE | CategorizationAssistInput init | KEEP | P3 | InputBuilderRedactionPolicyTest (complementary) | Real init invariant: rejects NaN amount |
| test/…/domain/ai/model/NotificationParsingModelsTest.kt | 38 | 2 | 0 | PURE | NotificationParseResult init | KEEP | P3 | — | Rejects zero amount, confidence>1; real invariants |
| test/…/domain/ai/model/OnDeviceRuntimePresentationTest.kt | 37 | 4 | 0 | PURE | OnDeviceModelStatus.toRuntimeStatusMessage | KEEP | P3 | GetAiRuntimeStatusUseCaseTest (complementary) | Pins user-facing strings; brittle to copy edits |
| test/…/domain/ai/model/WarrantyExtractionModelsTest.kt | 39 | 2 | 0 | PURE | WarrantyExtractionResult init | KEEP | P3 | — | Rejects NaN confidence, non-positive fields |
| test/…/domain/ai/policy/AiPolicyTest.kt | 149 | 15 | 0 | PURE | AiPolicyImpl | KEEP | P0 | domain/privacy EffectiveCloudAiPolicy tests (complementary layer) | Fail-closed defaults; all toggle combos; redact authority |
| test/…/domain/ai/policy/DefaultAiCapabilityRouterTest.kt | 384 | 18 | 0 | MOCKED | DefaultAiCapabilityRouter | KEEP | P0 | HybridServiceDelegationTest b09 (complementary) | No-cloud-leak in ON_DEVICE mode; API-key fail-closed; mild FRAGILE 5-dep ctor |
| test/…/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt | 289 | 7 | 0 | MOCKED | CategorizationAssistInputBuilder | KEEP | P1 | InputBuilderRedactionPolicyTest (complementary) | Redaction, privacy authority, cancel propagation |
| test/…/domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt | 132 | 1 | 0 | MOCKED | CategorizeReceiptItemsUseCase | STRENGTHEN | P2 | ReceiptScanViewModelStressTest (complementary) | Only failure path (ANALYZING→PENDING restore); no success/cloud route |
| test/…/domain/ai/usecase/DedupeJudgeInputBuilderTest.kt | 350 | 9 | 0 | MOCKED | DedupeJudgeInputBuilder | KEEP | P1 | JudgePendingReviewDuplicateUseCaseTest (complementary) | A.4 regressions; type/currency filtering; privacy authority; one constant-pin test |
| test/…/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt | 207 | 6 | 0 | MOCKED | DeliverProactiveBriefingNotificationUseCase | KEEP | P2 | DailyBriefingWorkerTest (complementary) | Dedup delivery gates; metrics only after real delivery |
| test/…/domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt | 395 | 9 | 0 | MOCKED | ExecuteFinancialQueryUseCase | KEEP | P1 | AssistantViewModelTest (complementary) | Money text correctness; mixed-currency no raw sum — money rule |
| test/…/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt | 385 | 12 | 0 | MOCKED | ExplainPendingReviewUseCase | KEEP | P1 | ReviewViewModelStressTest (complementary) | Full artifact lifecycle; cancel writes no FAILED; FRAGILE 6-dep ctor |
| test/…/domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt | 201 | 4 | 0 | MOCKED | FinancialQueryInterpretationInputBuilder | KEEP | P1 | InterpretFinancialQueryUseCaseTest (complementary) | Card redaction in query text; reversible alias maps; truncation caps |
| test/…/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt | 319 | 10 | 0 | MOCKED | GenerateDashboardBriefingUseCase | KEEP | P2 | DailyBriefingWorkerTest (complementary) | Cache/hash staleness, TTL, truncation, cancel |
| test/…/domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt | 195 | 2 | 0 | MOCKED | GenerateTransactionInsightUseCase | STRENGTHEN | P1 | NotificationProcessingPipeline tests (complementary) | Strong redaction pair; missing disabled/failure/on-device paths |
| test/…/domain/ai/usecase/GetAiRuntimeStatusUseCaseTest.kt | 136 | 4 | 0 | MOCKED | GetAiRuntimeStatusUseCase | KEEP | P3 | AiSettingsViewModelTest, AssistantViewModelTest (complementary) | Status aggregation + priority message; long-string pins duplicated |
| test/…/domain/ai/usecase/InputBuilderRedactionPolicyTest.kt | 154 | 4 | 0 | MOCKED | ReceiptItemCategorizationInputBuilder, ReceiptAssistInputBuilder | KEEP | P0 | #25 (DUP overlap — survivor) | P8-NEW-01 contract: PrivacySettings redact authority; both polarities |
| test/…/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt | 216 | 7 | 0 | MOCKED | InterpretFinancialQueryUseCase | KEEP | P2 | Engine5PrimitiveGuardTest (complementary) | Disabled gate, local fallback, cancel propagation |
| test/…/domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt | 275 | 7 | 0 | MOCKED | JudgePendingReviewDuplicateUseCase | KEEP | P1 | ReviewViewModelStressTest (complementary) | Clears matched target outside candidate set — wrong-merge guard |
| test/…/domain/ai/usecase/MapFinancialQueryToNavigationUseCaseTest.kt | 72 | 2 | 0 | PURE | MapFinancialQueryToNavigationUseCase | KEEP | P2 | AssistantViewModelTest (complementary) | Filter→navigation mapping incl. ownership |
| test/…/domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt | 113 | 4 | 0 | MOCKED | PrioritizeReviewItemsUseCase | STRENGTHEN | P3 | — | Sort/tie-break real; `score calculation correct` is tautological delegation |
| test/…/domain/ai/usecase/ReceiptAssistInputBuilderTest.kt | 102 | 3 | 0 | MOCKED | ReceiptAssistInputBuilder | KEEP | P1 | InputBuilderRedactionPolicyTest (complementary) | CARD/IBAN/phone redaction in OCR text — PII leak guard |
| test/…/domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt | 88 | 1 | 0 | MOCKED | ReceiptItemCategorizationInputBuilder | MERGE | P3 | #19 (DUP — merge into InputBuilderRedactionPolicyTest) | Same redaction behavior + same fixtures as #19, narrower |
| test/…/domain/ai/usecase/ReviewExplanationInputBuilderTest.kt | 102 | 3 | 0 | MOCKED | ReviewExplanationInputBuilder | KEEP | P1 | ExplainPendingReviewUseCaseTest (complementary) | Pseudonymize merchant/app; drop notification text; privacy authority |
| test/…/domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt | 420 | 10 | 0 | MOCKED | SuggestCategoryFallbackUseCase | KEEP | P1 | CancellationSafetyArchitectureGuardTest (complementary) | Lifecycle, malformed-cache bypass, Uncategorized-allowed, cancel |
| test/…/domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt | 361 | 9 | 0 | MOCKED | SuggestReceiptExtractionUseCase | KEEP | P1 | ReceiptScanViewModelStressTest (complementary) | Stable hash ignores time; image-aware marker; FRAGILE 7-dep ctor |
| test/…/domain/ai/usecase/SyncProactiveBriefingWorkUseCaseTest.kt | 69 | 3 | 0 | MOCKED | SyncProactiveBriefingWorkUseCase | KEEP | P3 | AiSettingsViewModelTest (complementary) | Schedule/cancel contract; thin pure verify |
| test/…/domain/ai/usecase/ValidateBankStatementTransactionsUseCaseTest.kt | 234 | 9 | 0 | MOCKED | ValidateBankStatementTransactionsUseCase | STRENGTHEN | P1 | CancellationSafetyArchitectureGuardTest (complementary) | Cloud only after PrivacyGate check; stale KDoc claims gaps now covered |
| test/…/domain/ai/util/AiArtifactSourceHashTest.kt | 153 | 6 | 0 | PURE | AiArtifactSourceHash | KEEP | P2 | Used by 4 usecase tests (complementary) | Hash stability + sensitivity — cache invalidation correctness |

## Batch 16 — domain alerts/analytics/bank

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/alerts/AnomalyAlertOrchestratorTest.kt | 494 | 12 | 0 | MOCKED | AnomalyAlertOrchestrator | KEEP | P1 | TransactionSideEffectPlannerTest; e2e/BudgetAlertPipelineTest | cooldown/dedup/single-flight/cancellation asserted |
| test/…/domain/analytics/AdvancedAnalyticsDashboardTest.kt | 504 | 14 | 0 | MOCKED | AdvancedAnalyticsDashboard | KEEP | P0 | golden/AnalyticsDashboardBudgetParityGoldenTest | half-open bounds, DST, leap; best in batch |
| test/…/domain/analytics/AdvancedAnalyticsEngineDeepTest.kt | 345 | 10 | 0 | MOCKED | AdvancedAnalyticsEngine | STRENGTHEN | P1 | #4 #5, verification/CrossSourceVerificationTest | calls deprecated self-fetching overloads (incl. ERROR merchant) |
| test/…/domain/analytics/AdvancedAnalyticsEngineNormalizedTest.kt | 183 | 3 | 0 | MOCKED | AdvancedAnalyticsEngine | KEEP | P1 | #3 #5 | legal NormalizedAnalyticsInput path; only 3 tests |
| test/…/domain/analytics/AdvancedAnalyticsEngineTest.kt | 133 | 3 | 0 | MOCKED | AdvancedAnalyticsEngine | MERGE | P2 | #3 (survivor), #4 | deprecated self-fetch; weaker dup of DeepTest |
| test/…/domain/analytics/AnalyticsCurrencyNormalizerTest.kt | 195 | 7 | 0 | PURE | AnalyticsCurrencyNormalizer | KEEP | P0 | integration/MultiCurrencyAnalyticsTest, batch-10 currency | stale-by-validDate (P5) semantics locked |
| test/…/domain/analytics/AnalyticsInputAssemblerProvenanceTest.kt | 281 | 5 | 0 | MOCKED | AnalyticsInputAssembler | KEEP | P0 | #6 | legal assembler path; exclusion + rate provenance |
| test/…/domain/analytics/AnalyticsStressTest.kt | 119 | 1 | 0 | STRESS | AdvancedAnalyticsEngine | NIGHTLY | P2 | #3 | 10k tx; wall-clock `<10s` flaky in PR CI |
| test/…/domain/analytics/AnalyticsWindowingSupportTest.kt | 88 | 5 | 0 | PURE | AnalyticsWindowingSupport ext.fns | KEEP | P2 | metrics/TimePeriodAlignmentTest | merchant-key normalization, unicode |
| test/…/domain/analytics/AnomalyDetectorTest.kt | 208 | 4 | 0 | PURE | AnomalyDetector | KEEP | P1 | #1 (complementary: detector vs orchestrator) | effective-amount guard, FP/FN guards |
| test/…/domain/analytics/CategoryInsightEngineTest.kt | 474 | 11 | 0 | PURE | CategoryInsightEngine | KEEP | P1 | InsightsEngineValidationTest | golden totals; 4th inline copy of March dataset |
| test/…/domain/analytics/DayOfWeekAnalyzerTest.kt | 238 | 6 | 0 | PURE | DayOfWeekAnalyzer | KEEP | P2 | InsightsEngineValidationTest (DoW section) | DST + monday-zero indexing (bug b17) |
| test/…/domain/analytics/IncludeDepositsForBehaviorCleanupTest.kt | 25 | 1 | 0 | SRCTEXT | (none — source string scan) | DELETE | P3 | architecture/* guard suite | one-off cleanup guard; string-scan anti-pattern |
| test/…/domain/analytics/InsightsEngineDeepTest.kt | 211 | 7 | 0 | MOCKED | InsightsEngine | KEEP | P1 | #16 #17 | real sub-engines; effective-amount math |
| test/…/domain/analytics/InsightsEngineEdgeCaseTest.kt | 178 | 6 | 0 | MOCKED | InsightsEngine | STRENGTHEN | P2 | #14 #16 #17 | 2 "does not crash" only; runBlocking + real clock |
| test/…/domain/analytics/InsightsEngineTest.kt | 120 | 4 | 0 | MOCKED | InsightsEngine | KEEP | P2 | #14 #15 #17 | buildDailyTotals real sums; real clock |
| test/…/domain/analytics/InsightsEngineValidationTest.kt | 524 | 13 | 0 | MOCKED | InsightsEngine | KEEP | P1 | #14 (complementary) | real calculators; MoM/median/DoW math |
| test/…/domain/analytics/MerchantInsightEngineTest.kt | 147 | 4 | 0 | PURE | MerchantInsightEngine | KEEP | P2 | #3 merchant tests | alias canonicalization, recurring variance |
| test/…/domain/analytics/MonthlyComparisonCalculatorTest.kt | 181 | 3 | 0 | PURE | MonthlyComparisonCalculator | KEEP | P1 | InsightsEngineValidationTest | F-07 candidate (1283.59 ± d) |
| test/…/domain/analytics/SpendingPaceBoundaryTest.kt | 181 | 4 | 0 | PURE | SpendingPaceCalculator | KEEP | P1 | #21 #22 | exact 90/110 threshold boundaries |
| test/…/domain/analytics/SpendingPaceCalculatorDeepTest.kt | 188 | 6 | 0 | PURE | SpendingPaceCalculator | KEEP | P1 | #20 #22 (survivor for merge) | blended smoothing formulas asserted |
| test/…/domain/analytics/SpendingPaceCalculatorValidationTest.kt | 559 | 14 | 0 | PURE | SpendingPaceCalculator | MERGE | P2 | #20 #21 (survivors) | day-4/Feb cases duplicate DeepTest |
| test/…/domain/analytics/SpendingPaceGoldenTest.kt | 109 | 2 | 0 | PURE | SpendingPaceCalculator | KEEP | P1 | #19 #21 | F-07 candidate; oracle 991.79/2049.70/175% |
| test/…/domain/analytics/SpendingPersonalityClassifierTest.kt | 564 | 17 | 0 | MOCKED | SpendingPersonalityClassifier | STRENGTHEN | P2 | verification/LifestyleAnalysisTest | reflection into privates; some range-only asserts |
| test/…/domain/analytics/SpendingThresholdCalculatorTest.kt | 213 | 11 | 0 | MOCKED | SpendingThresholdCalculator | STRENGTHEN | P2 | — | P90 math good; cache "tests" are weak tolerances |
| test/…/domain/analytics/TotalsAggregationEngineDeepTest.kt | 205 | 5 | 0 | MOCKED | TotalsAggregationEngine | STRENGTHEN | P1 | #27 #28 | monthAvg computed but NEVER asserted |
| test/…/domain/analytics/TotalsAggregationEngineTest.kt | 961 | 48 | 0 | MOCKED | TotalsAggregationEngine | KEEP | P0 | #26 #28, metrics/DashboardWidgetConsistencyTest | legal MCR path; isPartial propagation; real clock at :49 |
| test/…/domain/analytics/TotalsAggregationEngineValidationTest.kt | 561 | 18 | 0 | MOCKED | TotalsAggregationEngine | KEEP | P1 | #26 #27 | status trio triplicated across #26/#27/#28 |
| test/…/domain/analytics/TransferDirectionAnalyticsTest.kt | 234 | 5 | 0 | PURE | TransferDirectionAnalytics | KEEP | P2 | — | prune/cap via private-field reflection (fragile) |
| test/…/domain/analytics/fixtures/ExpectedResults.kt | 226 | 0 | 0 | FIXTURE | (oracle constants) | KEEP | P2 | metrics/GoldenAnalyticsDataset (batch-02: fold) | sole consumer is verification/GoldenMasterVerificationTest |
| test/…/domain/analytics/fixtures/GoldenDataSets.kt | 321 | 0 | 0 | FIXTURE | (synthetic datasets) | KEEP | P3 | #30, metrics/GoldenAnalyticsDataset (batch-02) | UTC dates vs base's systemDefault TZ mismatch |
| test/…/domain/bank/BankApiIntegrationTest.kt | 382 | 12 | 0 | MOCKED | BankApiIntegration | STRENGTHEN | P1 | golden/BankSyncFailureRecoveryGoldenTest, scenarios/BankSyncScenarioTest | P10 contracts strong; 2 tautologies; refresh unverified |
| test/…/domain/bank/BankStatementItemAuditTest.kt | 147 | 7 | 0 | PURE | (none — constructs entity directly) | REWRITE | P3 | — | asserts its own hand-written strings (tautology) |

## Batch 17 — domain budget→core/money

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/budget/BudgetAutopilotEngineTest.kt | 444 | 13 | 0 | MOCKED | BudgetAutopilotEngine (+real MultiCurrencyRepository, BudgetForecastingEngine) | KEEP | P1 | BudgetForecastingEngineTest, ui BudgetViewModelStressTest | Real trend/cap/volatility math; parity test w/ forecast; FRAGILE-lite ctor |
| test/…/domain/budget/BudgetCalculatorBoundaryTest.kt | 380 | 16 | 0 | PURE | BudgetCalculator | KEEP | P1 | BudgetCalculatorTest, TimeBoundaryTest, GoldenTest | Leap/DST/anchor coercion; TZ restored in finally; 2 cases near-DUP w/ #4 |
| test/…/domain/budget/BudgetCalculatorGoldenTest.kt | 83 | 3 | 0 | PURE | BudgetCalculator | KEEP | P1 | BudgetCalculatorBoundaryTest | Golden calendar/rolling/anniversary; different anchors than #2 |
| test/…/domain/budget/BudgetCalculatorTest.kt | 442 | 16 | 0 | PURE | BudgetCalculator | KEEP | P1 | BoundaryTest (2 dup cases), TimeBoundaryTest | Exact windows; drop duplicated calendar-yearly cases |
| test/…/domain/budget/BudgetCalculatorTimeBoundaryTest.kt | 291 | 11 | 0 | PURE | BudgetCalculator | KEEP | P1 | #2/#4 | java.time migration lock-in; invalid-mode throws; half-open contains; GlobalTimeZoneTestLock |
| test/…/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt | 195 | 4 | 0 | MOCKED | BudgetForecastingEngine diagnostics | KEEP | P2 | BudgetForecastingEngineTest | FORECAST_GENERATED/UNAVAILABLE events; writer failure tolerated |
| test/…/domain/budget/BudgetForecastingEngineTest.kt | 807 | 26 | 0 | MOCKED | BudgetForecastingEngine | KEEP | P1 | #1, #6, BudgetTrendBoundaryTest | Trend/zero-fill/confidence; ABORT-vs-FK conflict mapping; FRAGILE 10-dep ctor |
| test/…/domain/budget/BudgetHistorySeriesBuilderTest.kt | 59 | 2 | 0 | PURE | BudgetHistorySeriesBuilder | KEEP | P2 | #1 (uses builder) | Half-open window + zero-fill counts |
| test/…/domain/budget/BudgetMonitorStressTest.kt | 366 | 11 | 0 | MOCKED | BudgetMonitor | REWRITE | P2 | BudgetMonitorTest | F-03 (5 fail); stub DeliveryResult; concurrency cases worth keeping |
| test/…/domain/budget/BudgetMonitorTest.kt | 266 | 6 | 0 | MOCKED | BudgetMonitor | REWRITE | P1 | BudgetMonitorStressTest | F-03 (3 fail); stale DeliveryResult stubs; diagnostics tests good |
| test/…/domain/budget/BudgetRecommendationEngineTest.kt | 121 | 5 | 0 | PURE | BudgetRecommendationEngine | KEEP | P2 | ui BudgetForecastingViewModelTest | Ordered recommendations, savings clamp, health summary format |
| test/…/domain/budget/BudgetTrendBoundaryTest.kt | 120 | 1 | 0 | MOCKED | BudgetForecastingEngine | REWRITE | P2 | BudgetForecastingEngineTest | Stale: drives dead DAO path; engine reads snapshots (BudgetForecastingEngine.kt:389) |
| test/…/domain/budget/P6BudgetCleanupTest.kt | 501 | 9 | 0 | MIXED/REFLECTION | BudgetRepository, SpendingPaceCalculator, CashFlowCalculator, constants | REWRITE | P3 | CashFlowCalculatorTest | 2 real tests; 7 reflection/self-arithmetic tautologies |
| test/…/domain/budget/SharedBudgetManagerTest.kt | 435 | 14 | 0 | MOCKED | SharedBudgetManager (+real BudgetCalculator) | KEEP | P2 | data/repository BudgetRepositoryStressTest | Progress math, rolling vs calendar window, purchase-only semantics |
| test/…/domain/business/BusinessExpenseReportGeneratorTest.kt | 803 | 40 | 0 | MOCKED | BusinessExpenseReportGenerator, BusinessExpenseRepository | KEEP | P1 | (none) | Purchase-only defense-in-depth; CSV injection; mixed-currency partial flag |
| test/…/domain/carbon/CarbonFootprintCalculatorTest.kt | 347 | 23 | 0 | MOCKED | CarbonFootprintCalculator | KEEP | P2 | ui CarbonFootprintViewModelTest, verification CarbonFootprintTest | Numeric factor math; uncapped DAO regression; ~5 isNotNull-only tests; mixed real/fixed clock |
| test/…/domain/cashflow/CashFlowCalculatorTest.kt | 1024 | 29 | 0 | MOCKED | CashFlowCalculator | KEEP | P1 | P6BudgetCleanupTest, ui CashFlowCalendarViewModelTest | Movement-aware classification; read-path purity; barrier partial flags; DST; fail-closed currency precondition |
| test/…/domain/categorization/CategorizationComponentsTest.kt | 340 | 41 | 0 | PURE | MerchantCanonicalizer, GreeklishNormalizer, SemanticKeywordMatcher, ContextualInferenceEngine | KEEP | P1 | #24, #25 (dup sources) | Merge survivor for canonicalizer/semantic stress files |
| test/…/domain/categorization/CategorizationEngineDebugTest.kt | 221 | 7 | 0 | MOCKED | CategorizationEngine | KEEP | P2 | #20, #21 | Overturns prior P3: asserts cascade layer order + legal learn path + hashed-key privacy |
| test/…/domain/categorization/CategorizationEngineStressTest.kt | 651 | 33 | 0 | MOCKED/STRESS | CategorizationEngine | NIGHTLY | P2 | #19, #21 | NOT @Ignored; 10k-thread test; prune 4 no-assertion tests |
| test/…/domain/categorization/CategorizationEngineTest.kt | 120 | 6 | 0 | MOCKED | CategorizationEngine | KEEP | P1 | #19, #20 | Exact/canonical/unknown + cache; layers 3-6 mocked out |
| test/…/domain/categorization/CategoryKeywordsTest.kt | 69 | 7 | 0 | PURE | CategoryKeywords | KEEP | P3 | #18 | Dictionary integrity + deterministic dedup guard |
| test/…/domain/categorization/ContextualInferenceEngineStressTest.kt | 660 | 27 | 0 | PURE/STRESS | ContextualInferenceEngine | NIGHTLY | P3 | #18 (Contextual class) | Brackets/time/day/source asserted; wall-clock perf tests are only CI risk |
| test/…/domain/categorization/MerchantCanonicalizerStressTest.kt | 65 | 8 | 0 | PURE | MerchantCanonicalizer | MERGE | P3 | #18 (survivor) | Mostly dup of #18; carry Greek-script/iterative cases over |
| test/…/domain/categorization/SemanticKeywordMatcherStressTest.kt | 50 | 6 | 0 | PURE | SemanticKeywordMatcher | MERGE | P3 | #18 (survivor) | Dup of #18 semantics class |
| test/…/domain/challenge/SpendingChallengeManagerTest.kt | 195 | 5 | 0 | MOCKED | SpendingChallengeManager | KEEP | P2 | ui SpendingChallengesViewModelTest | Streak w/ grouped query (no day-by-day reads); baseline; DST range |
| test/…/domain/common/HashingTest.kt | 36 | 4 | 0 | PURE | Hashing.kt sha256/sha256Fingerprint | KEEP | P3 | (none) | Known SHA-256 vector pins real behavior |
| test/…/domain/consistency/LegacyDataConsistencyCheckerTest.kt | 377 | 5 | 0 | MOCKED (fakes) | LegacyDataConsistencyChecker | KEEP | P2 | none in consistency/ (batch 06) | Event-log completeness invariants; weak `>= 1` assertions |
| test/…/domain/core/money/CurrencyNormalizationBehavioralTest.kt | 249 | 12 | 0 | PURE (fakes) | CurrencyConverter, MoneyNormalizationEngine | KEEP | P0 | #30, #31 | validDate selection, fail-closed conversion, invalid-currency exclusion |
| test/…/domain/core/money/InvalidCurrencyBehavioralTest.kt | 273 | 13 | 0 | PURE (fakes) | MoneyNormalizationEngine, MoneyAggregateBuilder, MoneyMappers | KEEP | P0 | #29 | Invalid currency never crashes, degrades to partial/UNAVAILABLE |
| test/…/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt | 112 | 5 | 0 | PURE | MoneyAggregateBuilder | KEEP | P1 | scenarios MoneyAggregateBuilderTest; 1 case dup of #29 | API restriction contract (basis rejection, RequireBucketDate) |
| test/…/domain/core/money/NormalizationProvenanceTest.kt | 178 | 9 | 0 | PURE | MoneyNormalizationEngine | KEEP | P1 | #29 | Rate provenance + quality enum COMPLETE/PARTIAL/UNAVAILABLE/ESTIMATED |
| test/…/domain/core/time/PeriodKindContractTest.kt | 70 | 4 | 0 | PURE | PeriodKind | MERGE | P3 | #34 (survivor) | Keep TimePeriodUtils-parity tests; rest dup of #34 |
| test/…/domain/core/time/PeriodRangeTest.kt | 200 | 9 | 0 | PURE | PeriodRange, PeriodKind | KEEP | P1 | #33 | Half-open contract, DST 23-25h, leap Feb, custom bounds |

## Batch 18 — domain currency→health

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/currency/ConversionSemanticsHardeningTest.kt | 332 | 16 | 0 | PURE | CurrencyConverter, ExchangeRateStoreAdapter | KEEP | P0 | ConversionTest, NormalizationBehavioral, batch-17 same-name | RateBasis/stale-policy/composite provenance + storage boundary; excellent |
| test/…/domain/currency/CurrencyConversionTest.kt | 404 | 28 | 0 | MOCKED | CurrencyConverter, SupportedCurrency | STRENGTHEN | P1 | #1, #3, #4 | Solid core; 1 tautology (local SupportedCurrency list), mock-stubs use System.currentTimeMillis |
| test/…/domain/currency/CurrencyConverterEdgeCaseTest.kt | 148 | 7 | 0 | MOCKED | CurrencyConverter | KEEP | P1 | #1, #2 | Drift bound, NaN/Inf rate rejection, sign preservation |
| test/…/domain/currency/CurrencyConverterGoldenTest.kt | 62 | 2 | 0 | GOLDEN | CurrencyConverter | KEEP | P0 | #2 | GBP→JPY 19012.50 cross-rate golden |
| test/…/domain/currency/CurrencyConverterStressTest.kt | 70 | 2 | 0 | STRESS | CurrencyConverter | KEEP | P2 | #3 | 2×500 iters, sub-second; fine for PR CI (prior NIGHTLY suggestion unnecessary) |
| test/…/domain/currency/CurrencyNormalizationBehavioralTest.kt | 268 | 10 | 0 | PURE | MoneyNormalizationEngine, MoneyAggregate | KEEP | P0 | batch-17 same-name class, #1 | isPartial/excluded/missingRate counters + rateBasis propagation |
| test/…/domain/currency/HomeCurrencyResolutionTest.kt | 90 | 6 | 0 | PURE | HomeCurrencyResolution, CurrencySettingsRepository | KEEP | P1 | ForecastInputAssemblerTest fail-closed test | Fail-closed resolution incl. exception→Failed |
| test/…/domain/currency/MultiCurrencyTestFixture.kt | 151 | 0 | 0 | FIXTURE | (fixture) | DELETE | P2 | — | Orphaned: zero references outside itself (prior 2026-05 "used by dashboard/budget/analytics" no longer true) |
| test/…/domain/dashboard/P5AnalyticsFixesTest.kt | 284 | 6 | 0 | MOCKED | MultiCurrencyRepository, MoneyAggregateBuilder, ComputeDashboardWidgetsUseCase | REWRITE | P1 | MultiCurrencyRepositoryTest, scenarios MoneyAggregateBuilderTest | 2 real tests; 4 reflection/tautology (asserts own inline filter, java.time stdlib) |
| test/…/domain/debug/ServiceDiagnosticsTest.kt | 281 | 11 | 0 | ROBOLECTRIC | ServiceDiagnostics | KEEP | P2 | DebugViewModelStressTest | Counter behavior + genuine concurrency snapshot invariants; SharedPreferences cleared in setup |
| test/…/domain/dto/DtoContractTest.kt | 178 | 4 | 0 | PURE | AiArtifactRecord, ReceiptItemCategorizationSnapshot | KEEP | P3 | — | copy()/defaults contract; mostly compiler-checked but cheap; keep |
| test/…/domain/engine/DashboardFollowThroughEngineTest.kt | 449 | 20 | 0 | MOCKED | DashboardFollowThroughEngine | KEEP | P1 | NavigationTargetResolverTest, pipeline tests | Real outputs (priority/nav/JSON filters/7-day expiry); setMain without resetMain |
| test/…/domain/export/AccountingExportPolicyTest.kt | 72 | 3 | 0 | PURE | AccountingExportPolicy | STRENGTHEN | P1 | AccountingExportRepositoryTest, ExportOptionsViewModelTest | Test 1 has NO assertion (silent pass); tests 2-3 assert error messages |
| test/…/domain/export/CsvCellSanitizerNegativeAmountTest.kt | 100 | 13 | 0 | PURE | CsvCellSanitizer | KEEP | P0 | CsvEscapingTest | Formula-injection incl. "-2+3+cmd" DDE vectors, negative-amount non-corruption |
| test/…/domain/export/CsvEscapingTest.kt | 475 | 28 | 0 | PURE | XeroCSVExporter, QuickBooksIIFExporter, FreshBooksExporter | KEEP | P0 | #14, golden CsvExportImportRoundtrip | Escaping + delimiter-injection field-count asserts + IIF formula neutralization |
| test/…/domain/export/ExpenseExportMapperTest.kt | 68 | 2 | 0 | PURE | Expense.toExportTransaction | KEEP | P2 | AccountingExportRepositoryTest | effectiveAmount (shared 25%) + payment-method account labels |
| test/…/domain/forecasting/FinancialStressForecastEngineTest.kt | 721 | 21 | 0 | MOCKED | FinancialStressForecastEngine | KEEP | P0 | DashboardWidgetConsistency, ForecastSynthesisGolden, e2e | Legal-path assert: projectOccurrences not generateOccurrences; DBG-03/DBG-06; FRAGILE (14 mocks, reflection into private classifyRiskLevel) |
| test/…/domain/forecasting/ForecastInputAssemblerTest.kt | 1023 | 22 | 0 | MOCKED | ForecastInputAssembler | KEEP | P0 | #20, FinancialWeatherRepositoryTest | Fail-closed home currency; planned-expense conversion + excludedPlannedCount/isPartial; DBG-06 partial flag |
| test/…/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt | 320 | 8 | 0 | MOCKED | HistoricalSpendingDistribution | KEEP | P1 | ForecastSynthesisGolden, e2e | DST-safe week/day math; fixed-total asserts (400.0) are F-07 drift candidates |
| test/…/domain/forecasting/MergedRecurringPatternsProviderTest.kt | 193 | 5 | 0 | MOCKED | MergedRecurringPatternsProvider | KEEP | P1 | #18 merge tests, CalculateFinancialForecastUseCaseTest | Near-dup of #18 merge semantics but via provider entry point — complementary |
| test/…/domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt | 82 | 1 | 0 | GOLDEN | MonteCarloSpendingSimulator | KEEP | P0 | ComputeMoneyRadar, ForecastSynthesisGolden | Seed-42 p10-p90 golden; prime F-07 drift sensor — keep ±1.0 tolerance |
| test/…/domain/forecasting/MonteCarloSpendingSimulatorTest.kt | 162 | 5 | 0 | MOCKED | MonteCarloSpendingSimulator | KEEP | P1 | #21 | Degraded no-history path + leap-year daysRemaining |
| test/…/domain/groups/GroupBalanceCalculatorTest.kt | 144 | 5 | 0 | MOCKED | GroupBalanceCalculator | KEEP | P0 | GroupLifecycleScenarioTest | joinedAt participation, cancelled/foreign-currency settlement exclusion |
| test/…/domain/groups/SettlementCalculatorStressTest.kt | 88 | 3 | 0 | STRESS | SettlementCalculator | KEEP | P1 | #25, FinancialArithmeticPrecisionTest, e2e GroupSettlementPipeline | 15-member budget, volume invariant; fast |
| test/…/domain/groups/SettlementCalculatorTest.kt | 149 | 6 | 0 | PURE | SettlementCalculator | KEEP | P1 | #24, consistency/precision tests | Triangle-debt solver, min-amount parity, greedy-fallback marker |
| test/…/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt | 362 | 8 | 0 | MOCKED | SharedExpenseBudgetOffsetEngine | KEEP | P1 | Budget repo suite, e2e | Linked-expense exclusion, malformed-split fallback, N+1 check, category filter |
| test/…/domain/groups/SharedExpenseManagerTest.kt | 467 | 14 | 0 | MOCKED | SharedExpenseManager, SplitCalculator | KEEP | P0 | verification/SharedExpenseTest, e2e | All split types + parity with SplitCalculator (B.02), joinedAt, NaN/Inf rejection |
| test/…/domain/groups/usecase/GroupUseCasesTest.kt | 372 | 12 | 0 | MOCKED | AddGroupExpenseUseCase, DeleteGroup(Member)UseCase | KEEP | P2 | SharedExpenseManagerTest | Delegation/verify-heavy but pins B.4 coordinator-migration contract + atomic path |
| test/…/domain/health/FinancialHealthCalculatorBoundaryTest.kt | 388 | 11 | 0 | PURE | FinancialHealthCalculator, TimePeriodUtils | STRENGTHEN | P1 | #30, #31 | Half of tests assert TimePeriodUtils directly or only "score in 0..100"; strong: empty-budget bonus=8 |
| test/…/domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt | 199 | 3 | 0 | PURE | FinancialHealthCalculator | KEEP | P1 | #29 | Daily/weekly/monthly target normalization incl. overlap de-dup; formula-derived expectations |
| test/…/domain/health/FinancialHealthCalculatorTransactionTypeTest.kt | 382 | 9 | 0 | PURE | FinancialHealthCalculator | KEEP | P1 | RecurringIncomeTrackerTest (semantics) | Differential with/without non-spend design; canonical isSpending protected |
| test/…/domain/health/FinancialHealthScoreV2Test.kt | 501 | 11 | 0 | MOCKED | FinancialHealthScoreV2 | KEEP | P0 | #33, #34, metrics/DashboardWidgetConsistency | Weighted-formula golden, history upsert, trend threshold, fake-clock timing diagnostic |
| test/…/domain/health/HealthScoreEdgeCaseTest.kt | 318 | 5 | 0 | MOCKED | FinancialHealthScoreV2 | KEEP | P0 | #32 | Zero-income/overspend floors, toInt truncation (B.04), deposit-only |
| test/…/domain/health/HealthScoreGoldenTest.kt | 133 | 2 | 0 | GOLDEN | FinancialHealthScoreV2 | STRENGTHEN | P1 | #32, #33 | Test name says "57 and stable" but asserts 55/IMPROVING — stale golden naming (drift symptom) |
| test/…/domain/income/RecurringIncomeTrackerTest.kt | 158 | 4 | 0 | MOCKED | RecurringIncomeTracker | KEEP | P1 | TransactionTypeTest | A.10 canonical isSpending: only PURCHASE counts; deposit-only recurring detection |

## Batch 19 — domain intelligence→negotiation

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/intelligence/ConfidenceRouterEdgeCaseTest.kt | 90 | 7 | 0 | MOCKED | ConfidenceRouter | KEEP | P1 | #2 | Exact 0.85/0.50/0.499 boundaries; NaN/blank rejected |
| test/…/domain/intelligence/ConfidenceRouterTest.kt | 173 | 11 | 0 | MOCKED | ConfidenceRouter | KEEP | P1 | #1 | Unknown-merchant floor, spam anti-trust, clamp |
| test/…/domain/intelligence/DuplicateDetectionPolicyDedupeKeyTest.kt | 90 | 6 | 0 | PURE | DuplicateDetectionPolicy | KEEP | P1 | DedupeKeyProducerConsistencyTest (b06) | ISSUE-6 contract; complementary to producer test |
| test/…/domain/intelligence/TransactionClassifierTest.kt | 180 | 10 | 0 | MOCKED | TransactionClassifier | STRENGTHEN | P2 | NotificationProcessingPipeline* (mock) | 3 tests no-assert/no-crash; temp dir never deleted |
| test/…/domain/intelligence/ml/ExpenseCategoryClassifierTest.kt | 272 | 11 | 0 | MOCKED | ExpenseCategoryClassifier | STRENGTHEN | P1 | e2e pipeline tests | F-04/F-17: 7 failures; isolation fix needed |
| test/…/domain/intelligence/ml/HybridExpenseClassifierTest.kt | 357 | 15 | 0 | MOCKED | HybridExpenseClassifier | KEEP | P1 | data/repository pipeline tests | Dict→ML→fallback order; CancellationException rethrow |
| test/…/domain/intelligence/ml/MerchantNormalizerStressTest.kt | 186 | 12 | 0 | MOCKED | MerchantNormalizer | MERGE | P2 | #8 | Not stress; DUP w/ #8; rename survivor; prior Nightly overturned |
| test/…/domain/intelligence/ml/MerchantNormalizerTest.kt | 104 | 6 | 0 | MOCKED | MerchantNormalizer | KEEP | P1 | #7 | Survivor; linkAlias tests are stub-echo; no key-derivation pin |
| test/…/domain/investment/InvestmentTrackerTest.kt | 374 | 19 | 0 | MOCKED | InvestmentTracker | KEEP | P1 | InvestmentGoldenScenarioTest | Epoch-0 all-time regression; fee math; FRAGILE ctor |
| test/…/domain/location/AreaSpendingEngineNormalizedTest.kt | 166 | 7 | 0 | PURE | AreaSpendingEngine (normalized) | KEEP | P1 | #11 | Live API; conversion filtering + grid grouping |
| test/…/domain/location/AreaSpendingEngineStressTest.kt | 75 | 4 | 0 | PURE | AreaSpendingEngine.compute | MERGE | P3 | #10 | Deprecated compute(), no prod callers; not stress |
| test/…/domain/location/LocationInsightsEngineStressTest.kt | 101 | 6 | 0 | PURE | LocationInsightsEngine | KEEP | P2 | ui analytics tests | Misnamed; normalized overload untested |
| test/…/domain/location/LocationResolverStressTest.kt | 283 | 12 | 0 | MOCKED | LocationResolver | KEEP | P1 | #14, data/location workers | Cascade priority, GPS bias, area-key contract |
| test/…/domain/location/LocationResolverTest.kt | 201 | 2 | 0 | MOCKED | LocationResolver | KEEP | P1 | #13 | Pins merchantKey-vs-derived cacheKey selection |
| test/…/domain/location/SpendingHeatmapEngineStressTest.kt | 562 | 32 | 0 | STRESS | SpendingHeatmapEngine | REWRITE | P3 | SpendingMapViewModelStressTest | 32 tests; wall-clock perf asserts; raw path only |
| test/…/domain/location/TravelDetectionEngineNormalizedTest.kt | 153 | 5 | 0 | PURE | TravelDetectionEngine (normalized) | KEEP | P1 | #17 | Live API; add gap-separation + determinism from #17 |
| test/…/domain/location/TravelDetectionEngineStressTest.kt | 110 | 7 | 0 | PURE | TravelDetectionEngine.compute | MERGE | P3 | #16 | Deprecated compute(); unique gap/determinism tests |
| test/…/domain/logic/CustomSplitParserTest.kt | 185 | 12 | 0 | PURE | CustomSplitParser | KEEP | P1 | — | Tolerance boundaries; line 63 name contradicts assertion |
| test/…/domain/logic/RecurrenceCalculatorTest.kt | 61 | 5 | 0 | PURE | RecurrenceCalculator | KEEP | P1 | RecurringExpenseRepositoryTest | Frequency monthly-normalization semantics |
| test/…/domain/logic/RecurringExpenseEngineEmptyListTest.kt | 114 | 4 | 0 | MOCKED | RecurringExpenseEngine | KEEP | P2 | domain/analytics Insights* | Regression: empty/single/stale/merchantKey grouping |
| test/…/domain/logic/SplitCalculatorGoldenTest.kt | 102 | 4 | 0 | PURE | SplitCalculator | MERGE | P2 | #22 | Verbatim 4-scenario subset of #22; prior KEEP overturned |
| test/…/domain/logic/SplitCalculatorTest.kt | 286 | 12 | 0 | PURE | SplitCalculator | KEEP | P0 | #21, groups tests | Cent-preserving splits, backdated validation, settlements |
| test/…/domain/logic/SynthesisEngineBlockPartyPaidExclusionTest.kt | 231 | 3 | 0 | MOCKED | SynthesisEngine + occurrence statuses | KEEP | P0 | #24, #26 | PAID/SKIPPED/CANCELLED double-count prevention |
| test/…/domain/logic/SynthesisEngineGoldenTest.kt | 210 | 3 | 0 | PURE | SynthesisEngine | KEEP | P1 | #26 | Bands, biweekly ±2 tolerance, discretionary rate |
| test/…/domain/logic/SynthesisEngineStressTest.kt | 1710 | 56 | 0 | STRESS | SynthesisEngine | NIGHTLY | P2 | #24, #26 | Prior Nightly confirmed; many assertNotNull-only |
| test/…/domain/logic/SynthesisEngineTest.kt | 526 | 10 | 0 | PURE | SynthesisEngine | KEEP | P1 | #24, #25 | Committed/likely, goals, risk, ForecastInput quality |
| test/…/domain/model/RecurringPatternModelTest.kt | 27 | 2 | 0 | PURE | RecurrenceFrequency | KEEP | P3 | — | Frequency semantics contract, not data-class tautology |
| test/…/domain/model/dashboard/DashboardExpenseMapperTest.kt | 125 | 4 | 0 | PURE | DashboardExpense.toTransactionSummary | STRENGTHEN | P2 | — | Half the mapping re-implemented as test fixture |
| test/…/domain/naturallanguage/NaturalLanguageSearchEngineDefaultWindowBoundaryTest.kt | 135 | 3 | 0 | MOCKED | NaturalLanguageSearchEngine | KEEP | P1 | — | Independent java.time oracle; single keyset call |
| test/…/domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt | 193 | 5 | 0 | PURE | NaturalLanguageSearchEngine | KEEP | P3 | — | Fakes not mocks; voice callbacks; location flag |
| test/…/domain/negotiation/NegotiationEngineTest.kt | 1346 | 46 | 0 | MOCKED | SmartBillNegotiationEngine | KEEP | P1 | CancellationSafetyArchitectureGuardTest | Grown 3→46 tests; barrier/rollback/conversion coverage |

## Batch 20 — domain parser/price/privacy/provenance

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/parser/AppParserRegistryRoutingTest.kt | 110 | 6 | 0 | MOCKED | AppParserRegistry, RevolutParser, GreekBankParser, SmsParser, GoogleWalletParser, GenericTransactionParser | KEEP | P1 | #2; e2e NotificationExpenseDashboard* | routing + fallback + no-match; real parsers |
| test/…/domain/parser/AppParserRegistryTest.kt | 150 | 8 | 0 | MOCKED | AppParserRegistry (+ all parsers) | MERGE | P1 | #1 (DUP) | merge OTP + confidence-provenance tests into #1 |
| test/…/domain/parser/GenericTransactionParserStressTest.kt | 105 | 11 | 0 | PURE | GenericTransactionParser | KEEP | P1 | #4 complementary | not stress; real normalizer/cleaner; overturn NIGHTLY |
| test/…/domain/parser/GenericTransactionParserTest.kt | 296 | 21 | 0 | MOCKED | GenericTransactionParser, TransferDirectionDetector | KEEP | P1 | #3 complementary | amounts, bounds, spam rejection |
| test/…/domain/parser/GoogleWalletParserTest.kt | 274 | 18 | 0 | MOCKED | GoogleWalletParser | KEEP | P1 | e2e pipeline tests | INR + corrupted €→E regression; P2P vs purchase |
| test/…/domain/parser/GreekBankParserStressTest.kt | 107 | 10 | 0 | PURE | GreekBankParser | KEEP | P1 | #7 complementary | not stress; decimal comma; overturn NIGHTLY |
| test/…/domain/parser/GreekBankParserTest.kt | 139 | 10 | 0 | MOCKED | GreekBankParser | KEEP | P1 | #6 complementary | Greek comma, single decimal, package list |
| test/…/domain/parser/NBGReproTest.kt | 54 | 1 | 0 | PURE | GreekBankParser, MerchantCleaner | KEEP | P2 | #7 | live-bug regression guard; has println |
| test/…/domain/parser/RevolutParserTest.kt | 352 | 25 | 0 | MOCKED | RevolutParser | KEEP | P0 | e2e pipeline tests | money P0: grouped US/EU amounts, ATM, currencies |
| test/…/domain/parser/SmsParserTest.kt | 235 | 17 | 0 | MOCKED | SmsParser | KEEP | P1 | #1, #2 | grouped amounts, ambiguous direction nulls |
| test/…/domain/parser/TransferDirectionDetectorTest.kt | 446 | 52 | 0 | PURE | TransferDirectionDetector | KEEP | P1 | consistency/CrossParserConsistencyTest | exhaustive EN/GR; 90%-bucket test can mask 1 miss |
| test/…/domain/price/PriceProtectionTrackerTest.kt | 335 | 19 | 0 | MOCKED | PriceProtectionTracker | STRENGTHEN | P2 | PriceProtectionViewModelTest | 3 vacuous if-empty asserts; real clock; 1 verify-only |
| test/…/domain/privacy/BackupPrivacyGateOwnershipTest.kt | 51 | 3 | 0 | MOCKED | BackupPrivacyGate | KEEP | P0 | ExportPrivacyPolicyTest | gate-ownership: no conflicting verdicts |
| test/…/domain/privacy/BankPrivacyHardeningTest.kt | 200 | 12 | 0 | PURE | BankTransactionPersistencePayload, BankTokenCipher, RawPersistencePolicyResolver | KEEP | P0 | #30, RawStorageEndToEndTest | fail-closed bank payloads + token invariants |
| test/…/domain/privacy/CloudAuditProviderProvenanceTest.kt | 196 | 10 | 0 | MOCKED | PrivacyAuditContext, SafePrivacyMetadata, CompositePrivacyGate | KEEP | P1 | #16 | audit provenance; composite missing-handler fails closed |
| test/…/domain/privacy/CloudPayloadPolicyTest.kt | 154 | 11 | 0 | MOCKED | DefaultCloudPayloadPolicy, EffectiveCloudAiPolicyResolver | KEEP | P0 | #17 (DUP) | survivor of pair; redaction matrix |
| test/…/domain/privacy/CloudProviderPreparedPayloadTest.kt | 129 | 8 | 0 | MOCKED | DefaultCloudPayloadPolicy | MERGE | P1 | #16 | dup + CWD-dependent script-existence test |
| test/…/domain/privacy/EmailRawStorageEnforcementTest.kt | 191 | 10 | 0 | PURE | EmailReceiptPersistencePayload | STRENGTHEN | P0 | #29, #19, #31 | 3 tautologies (ifBlank/correlation locals); rest real |
| test/…/domain/privacy/ExportPrivacyPolicyTest.kt | 141 | 12 | 0 | MOCKED | ExportPrivacyGate, ExportPrivacyPolicy | KEEP | P0 | golden PrivacyGateEnforcementGoldenTest | fail-closed export matrix; debug-gated raw |
| test/…/domain/privacy/NotificationPrivacyHardeningTest.kt | 187 | 10 | 0 | PURE | (none — test-owned simulations) | DELETE | P4 | scenarios/PrivacyGateContractTest | all tests assert test's own if/when replicas |
| test/…/domain/privacy/P8PrivacyFixesTest.kt | 409 | 8 | 0 | PURE | RawContentSanitizer, EffectiveCloudAiPolicy, DefaultCloudPayloadRedactor | STRENGTHEN | P0 | #22, #26 | P8-001/006 test own fake/loop; rest real |
| test/…/domain/privacy/PR5PrivacyContractTest.kt | 324 | 15 | 0 | MOCKED | RawContentSanitizer, BankApiIntegration, RawPersistencePolicyResolver, EffectiveCloudAiPolicyResolver | KEEP | P0 | #34 (DUP), #21 | BankApiIntegration ctor mock-heavy FRAGILE |
| test/…/domain/privacy/PrivacyBehavioralRegressionTest.kt | 269 | 16 | 0 | PURE | PrivacySettings, DefaultCloudPayloadPolicy | REWRITE | P1 | #27, #16 | half tautologies ("1==1"), half real value |
| test/…/domain/privacy/PrivacyCapabilityHandlingPolicyProductionTest.kt | 139 | 9 | 0 | MOCKED | PrivacyCapabilityHandlingPolicy, CompositePrivacyGate | STRENGTHEN | P1 | #25 (DUP) | exhaustiveness guard; 3 self-referential tests |
| test/…/domain/privacy/PrivacyCapabilityHandlingPolicyTest.kt | 84 | 3 | 0 | PURE | (test-owned shadow policyMap) | MERGE | P2 | #24 | shadows production policy in test map |
| test/…/domain/privacy/PrivacyGuardTest.kt | 383 | 12 | 0 | SRCTEXT | RawContentSanitizer (source), CompositePrivacyGate (source), PrivacySettings | STRENGTHEN | P1 | scripts/verify_privacy_boundaries.py | deliberate static guard; G5/G8 silently skip if file missing |
| test/…/domain/privacy/PrivacySettingsLoadStateTest.kt | 231 | 21 | 0 | PURE | PrivacySettings.FAIL_CLOSED_DEFAULTS, PrivacySettingsLoadState | STRENGTHEN | P0 | #23; data PrivacySettingsRepositoryImplCorruptionTest | fake's corruption mapping tested, not prod impl |
| test/…/domain/privacy/RawPersistencePolicyTest.kt | 248 | 20 | 0 | PURE | RawPersistencePolicyResolver, DefaultSensitiveHashingService, RawContentSanitizer, SafePrivacyMetadata | KEEP | P0 | #34 | mode×source matrix; HMAC determinism |
| test/…/domain/privacy/RawStorageEndToEndTest.kt | 311 | 18 | 0 | PURE | Notification/Receipt/Email/Bank PersistencePayload | KEEP | P0 | #30, #31, #18 | sentinel-based; survivor of raw-storage merge |
| test/…/domain/privacy/RawStoragePolicyAuditTest.kt | 257 | 13 | 0 | PURE | same payload builders + RawPersistencePolicyResolver | MERGE | P1 | #29 (DUP) | keep resolver-matrix test; retention test tautological |
| test/…/domain/privacy/ReceiptOcrEmailStorageHardeningTest.kt | 244 | 12 | 0 | PURE | ReceiptPersistencePayload, EmailReceiptPersistencePayload | STRENGTHEN | P0 | #29, #18 | unique reviewSnippet semantics; overlap real |
| test/…/domain/privacy/RetentionRegistryTest.kt | 203 | 10 | 0 | PURE | RetentionRegistry, RetentionTarget | MERGE | P2 | #29 | ~85% tautology; registry built from checked list |
| test/…/domain/privacy/SafePrivacyMetadataValueSafetyTest.kt | 210 | 21 | 0 | PURE | SafePrivacyMetadata | KEEP | P0 | #28, #26 | value-level leak prevention (JWT/IBAN/path) |
| test/…/domain/privacy/UPR5CompletionTest.kt | 274 | 7 | 0 | PURE | EffectiveCloudAiPolicyResolver, RawPersistencePolicyResolver, PrivacySettings | MERGE | P1 | #22 (DUP), #21 | keep allowParsedMerchant + cross-source asserts |
| test/…/domain/provenance/SourceLinkBackfillWorkerTest.kt | 186 | 3 | 0 | MOCKED | SourceLinkBackfillWorker, DatabaseWriteBarrier | KEEP | P0 | (none) | barrier per expense, CE rethrow, error counting |

## Batch 21 — domain receipt→transaction

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/receipt/BankStatementParserTest.kt | 537 | 19 | 0 | PURE | BankStatementParser | KEEP | P0 | ReceiptRepositoryStatementDuplicateTest, ReceiptRepositoryStressTest | F-01; flowOf fix present, see findings |
| test/…/domain/receipt/BitmapConcurrencyTest.kt | 356 | 13 | 0 | PURE (stdlib) | none (kotlinx Mutex) | DELETE | P4 | — | Tests kotlinx library, zero prod code |
| test/…/domain/receipt/EnhancedMerchantExtractorTest.kt | 101 | 4 | 0 | MOCKED | EnhancedMerchantExtractor | KEEP | P2 | — | Real extractor, asserts source+confidence |
| test/…/domain/receipt/GreekNormalizationTest.kt | 65 | 5 | 0 | PURE | ReceiptParser.normalizeGreekOcr | STRENGTHEN | P1 | #7, #8 | Reflection into private method (FRAGILE) |
| test/…/domain/receipt/OcrLanguageProcessorTest.kt | 136 | 13 | 0 | PURE | OcrLanguageProcessor | KEEP | P1 | — | Exhaustive locale amount extraction |
| test/…/domain/receipt/ReceiptOcrTempNameTest.kt | 75 | 4 | 0 | PURE | uniqueTempFileName (ReceiptOcrService) | KEEP | P3 | ReceiptRepositoryStressTest | Trivial but guards UUID-uniqueness contract |
| test/…/domain/receipt/ReceiptParserOcrPatternsTest.kt | 761 | 59 | 0 | PURE | ReceiptParser | KEEP | P0 | #4, #8 | 59 Greek/OCR total-extraction cases, fixed clock |
| test/…/domain/receipt/ReceiptParserTest.kt | 272 | 20 | 0 | PURE | ReceiptParser | KEEP | P0 | #4, #7 | Complementary: hallucination map, dates, decimals |
| test/…/domain/receipt/WarrantyTextExtractorTest.kt | 251 | 11 | 0 | PURE | WarrantyTextExtractor | STRENGTHEN | P2 | — | Uses LocalDate.now() clock (midnight flake risk) |
| test/…/domain/receipt/lifecycle/ReceiptLifecycleBugFixesTest.kt | 9 | 0 | 1 | FIXTURE (husk) | — | DELETE | P4 | — | Empty class-level @Ignore, APIs removed |
| test/…/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt | 679 | 14 | 0 | MOCKED | ReceiptLifecycleCoordinator | STRENGTHEN | P0 | batch-08 DB-contract, EmailReceiptIngestionServiceTest, PrivacyBehavioralRegressionTest | F-02 RED; FRAGILE 28-dep ctor; see findings |
| test/…/domain/receipt/lifecycle/ReceiptLifecycleHardeningTest.kt | 9 | 0 | 1 | FIXTURE (husk) | — | DELETE | P4 | — | Empty class-level @Ignore, APIs removed |
| test/…/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt | 231 | 9 | 0 | ROOM | ReceiptMatchLifecycleService | KEEP | P0 | ReceiptMatchingWorkerTest, ScannedReceiptClaimTest | Real Room; reason-code sanitization asserted |
| test/…/domain/receiptmatching/ReceiptTransactionMatcherTest.kt | 103 | 2 | 0 | MOCKED | ReceiptTransactionMatcher | STRENGTHEN | P1 | ReceiptMatchingNoDoubleCountGoldenTest, ReceiptMatchingE2ETest, ReceiptMatchingWorkerTest | Only 2 cases; no date-window/amount-tolerance |
| test/…/domain/recurring/RecurringLifecycleFixesTest.kt | 9 | 0 | 1 | FIXTURE (husk) | — | DELETE | P4 | — | Empty class-level @Ignore, APIs removed |
| test/…/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt | 302 | 5 | 0 | MOCKED | RecurringLifecycleCoordinator | STRENGTHEN | P0 | RecurringPaymentMatchE2ETest, ConcurrentOccurrenceClaimTest, batch-08 contract tests | FakeDomainTransactionRunner good; see findings |
| test/…/domain/reminder/BillReminderManagerTest.kt | 143 | 5 | 0 | MOCKED | BillReminderManager | REWRITE | P1 | BillRemindersViewModelTest, RecurringArchitectureGuardTest | F-06; 3 tests call removed markBillPaid |
| test/…/domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt | 84 | 1 | 0 | MOCKED | AutomatedSavingsRuleEngine | KEEP | P0 | #19 | Golden 17.30→2.70 math; clean DataStore isolation |
| test/…/domain/savings/AutomatedSavingsRuleEngineTest.kt | 289 | 8 | 0 | MOCKED | AutomatedSavingsRuleEngine | KEEP | P0 | #18 | State persists across engine recreation; caps/idempotency |
| test/…/domain/savings/SavingsGamificationEngineTest.kt | 242 | 7 | 0 | MOCKED | SavingsGamificationEngine | KEEP | P2 | SavingsGoalsViewModelTest | Real history repo; honest-zero legacy contract |
| test/…/domain/savings/SmartSavingsEngineTest.kt | 315 | 7 | 0 | MOCKED | SmartSavingsEngine | KEEP | P1 | SavingsGoalsViewModelTest, GoldenMasterVerificationTest | Weighted formula asserted with documented math |
| test/…/domain/sideeffect/DiagnosticSideEffectEventWriterTest.kt | 202 | 15 | 0 | MOCKED | DiagnosticSideEffectEventWriter | KEEP | P2 | DirectEventDaoInsertGuardTest | Note: asserts raw e.message carried (privacy) |
| test/…/domain/sideeffect/PostCommitActionBatchTest.kt | 93 | 7 | 0 | PURE | PostCommitActionBatch | KEEP | P3 | — | Idempotency-key dedup semantics |
| test/…/domain/sideeffect/PostCommitActionRunnerExtensionsTest.kt | 101 | 4 | 0 | MOCKED | runBestEffortAfterCommit ext | STRENGTHEN | P2 | #25 | Mock-verify heavy; cancellation contract is the value |
| test/…/domain/sideeffect/PostCommitActionRunnerTest.kt | 281 | 9 | 0 | MOCKED | PostCommitActionRunnerImpl | KEEP | P1 | CancellationPropagationContractTest | Real impl + fake writer; CancellationException rethrow |
| test/…/domain/sideeffect/SideEffectMetadataFactoryTest.kt | 145 | 11 | 0 | PURE | SideEffectMetadataFactory | KEEP | P2 | — | Asserts hashed idempotency key + no raw payloads |
| test/…/domain/sideeffect/TransactionSideEffectFailureEventWriterTest.kt | 118 | 4 | 0 | MOCKED | TransactionSideEffectFailureEventWriter | KEEP | P1 | TransactionContextProvenanceGuardTest | Deterministic clock; cancellation not swallowed |
| test/…/domain/split/SplitCalculationPrecisionTest.kt | 342 | 22 | 0 | PURE | (Money) — mirror of EnhancedSplitManager | REWRITE | P1 | VisualSplitViewModelTest | Test-local split mirror = tautology; see findings |
| test/…/domain/subscription/SubscriptionManagerEngineTest.kt | 451 | 21 | 0 | MOCKED | SubscriptionManagerEngine | STRENGTHEN | P1 | SubscriptionManagementViewModelTest | runBlocking-in-runTest hazard; some overlap in recordPriceChange tests |
| test/…/domain/tax/TaxCalculationTest.kt | 433 | 35 | 1 | PURE | GreeceTaxConfiguration/UsTaxConfiguration/TaxConfigurationFactory | REWRITE | P2 | TaxGoldenScenarioTest | Tax math is test-local mirror; @Ignore hides broken helper |
| test/…/domain/tax/TaxEstimatorTest.kt | 425 | 18 | 0 | MOCKED | TaxEstimator | STRENGTHEN | P1 | TaxGoldenScenarioTest (scenarios) | F-05 drift; expected computed by mirrored formula |
| test/…/domain/transaction/category/CategoryAssignmentServiceBarrierTest.kt | 157 | 3 | 0 | MOCKED | DefaultExpenseCategoryAssignmentService | STRENGTHEN | P1 | CancellationSafetyArchitectureGuardTest | Test 1 real fail-closed; tests 2–3 tautological |
| test/…/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt | 267 | 8 | 0 | MOCKED | TransactionLifecycleCoordinator | STRENGTHEN | P0 | batch-08 TransactionLifecycleCoordinatorDbContractTest, CancellationPropagationContractTest, Pipeline4LifecycleGoldenTest | F-02 RED; FRAGILE 15-dep ctor; see findings |
| test/…/domain/transaction/lifecycle/TransactionLifecycleCoordinatorUpdateTest.kt | 120 | 1 | 0 | MOCKED | TransactionLifecycleCoordinator | KEEP | P0 | batch-08 contract tests | F-02 family; stale baseAmount cleared on conv failure |
| test/…/domain/transaction/lifecycle/TransactionSideEffectPlannerTest.kt | 288 | 29 | 0 | PURE | TransactionSideEffectPlanner, SourceLearningPolicy | KEEP | P1 | DirectEventDaoInsertGuardTest | Trigger/idempotency/source-trust invariants, no mocks of logic |

## Batch 22 — domain/usecase

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/usecase/budget/CalculateBudgetStatusUseCaseTest.kt | 129 | 4 | 0 | MOCKED | CalculateBudgetStatusUseCase | STRENGTHEN | P2 | — | Passthrough asserted; getBudgetHealth/BUD-5 untested |
| test/…/domain/usecase/budget/GetMonteCarloBudgetImpactUseCaseTest.kt | 223 | 12 | 0 | PURE | GetMonteCarloBudgetImpactUseCase | KEEP | P0 | ComputeMoneyRadarUseCaseTest (complementary) | Exact threshold boundaries; exemplary |
| test/…/domain/usecase/dashboard/ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest.kt | 198 | 3 | 0 | MOCKED | ComputeDashboardWidgetsUseCase | KEEP | P1 | P5AnalyticsFixesTest, DashboardWidgetConsistencyTest (complementary) | FRAGILE: 14 mocked ctor deps |
| test/…/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt | 577 | 13 | 0 | ROBOLECTRIC | ComputeMoneyRadarUseCase | KEEP | P1 | GetMonteCarlo test, e2e pipeline (complementary) | Exact weighted scores; prior AssertionError unconfirmed |
| test/…/domain/usecase/dashboard/DashboardProjectionSafetyTest.kt | 105 | 6 | 0 | PURE | (none — local formula copies) | DELETE | P4 | MoneyBoundaryGuardTest (source guard) | Tautology: re-implements prod formulas locally |
| test/…/domain/usecase/expense/CategorizeExpenseUseCaseTest.kt | 112 | 4 | 0 | MOCKED | CategorizeExpenseUseCase | KEEP | P3 | CategorizationEngineTest (complementary) | Thin wiring; normalize-then-categorize order checked |
| test/…/domain/usecase/expense/DetectDuplicateExpenseUseCaseTest.kt | 230 | 5 | 0 | MOCKED | DetectDuplicateExpenseUseCase | STRENGTHEN | P1 | DuplicateDetectionPolicy tests (complementary) | KDoc lists own gaps; currency-aware dedup untested |
| test/…/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt | 439 | 6 | 0 | MOCKED | CalculateFinancialForecastUseCase, ForecastInputAssembler | KEEP | P1 | ForecastInputAssembler/SynthesisEngine tests (complementary) | Real assembler asserts; F-07-adjacent drift risk |
| test/…/domain/usecase/receipt/ProcessReceiptUseCaseHomeCurrencyTest.kt | 46 | 1 | 0 | MOCKED | ProcessReceiptUseCase | DELETE | P3 | — | Class has zero production references (dead) |
| test/…/domain/usecase/savings/LifestyleSavingsPromptUseCaseTest.kt | 292 | 9 | 0 | MOCKED | LifestyleSavingsPromptUseCase | KEEP | P2 | — | Overturns stale stub flag; real gating+uplift math |
| test/…/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt | 418 | 9 | 0 | MOCKED | MonthlySavingsSweepUseCase | KEEP | P0 | — | Money math: no double-count, NaN guard, caps |
| test/…/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCaseTest.kt | 236 | 8 | 0 | MOCKED | AutoCreateWarrantyFromReceiptUseCase, WarrantyTextExtractor | STRENGTHEN | P0 | ReceiptRepository tests, CancellationSafetyGuard (complementary) | PrivacyGate fail-closed + half-open end date untested |

## Batch 23 — domain/util

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/domain/util/AmountUtilsStressTest.kt | 595 | 65 | 0 | PURE | AmountUtils | STRENGTHEN | P1 | AmountUtilsTest, SharedUtilityConsistencyTest, CategorizationPipelineIntegrationTest | Real locale/negative/boundary parsing; 1 test leaks Locale on failure (no try/finally); ~3 no-assertion "document behavior" tests |
| test/…/domain/util/AmountUtilsTest.kt | 58 | 6 | 0 | PURE | AmountUtils | KEEP | P1 | AmountUtilsStressTest (subset) | Core parse/validate cases; duplicated verbatim in #1 regression section |
| test/…/domain/util/BKTreeTest.kt | 27 | 1 | 0 | PURE | StringBKTree | REWRITE | P3 | (none — search untested anywhere) | Still exactly 1 trivial insert/size/clear test; searchByDistance never directly tested |
| test/…/domain/util/CancellationSafeTest.kt | 119 | 9 | 0 | PURE | CancellationSafe | KEEP | P1 | CancellationSafetyArchitectureGuardTest | Pins CE-propagation contract behind AGENTS.md worker rule |
| test/…/domain/util/CurrencyFormatterExportSafetyTest.kt | 209 | 20 | 0 | PURE | CurrencyFormatter | KEEP | P1 | MoneyContractTest | Fail-closed on NaN/Inf, raw-code fallback for invalid currency |
| test/…/domain/util/GlobalTimeZoneTestLock.kt | 72 | 0 | 0 | FIXTURE | (shared TZ lock) | KEEP | P1 | Used by 11 test files | Process-wide TZ serialization harness; adoption incomplete (see gaps) |
| test/…/domain/util/MerchantCleanerStressTest.kt | 290 | 35 | 0 | PURE | MerchantCleaner | KEEP | P1 | GenericTransactionParserTest, MerchantKeyConsistencyTest, LocationResolverTest | "stress" name is misleading — real cleaning rules incl. documented-bug regression tests |
| test/…/domain/util/MerchantKeyGeneratorStressTest.kt | 183 | 29 | 0 | PURE | MerchantKeyGenerator | MERGE | P3 | MerchantKeyGeneratorTest | ~Half duplicate w/ weaker asserts (isNotEmpty); port emoji/null-char/perf cases then delete |
| test/…/domain/util/MerchantKeyGeneratorTest.kt | 157 | 16 | 0 | PURE | MerchantKeyGenerator | KEEP | P0 | CrossParserConsistencyTest, MerchantKeyConsistencyTest | Canonical merchant identity: transliteration, diphthongs, idempotency |
| test/…/domain/util/MoneyTest.kt | 418 | 31 | 0 | PURE | Money | KEEP | P0 | TaxCalculationTest, MoneyContractTest | BigDecimal exactness, split-sum invariants, HALF_UP rounding — money core |
| test/…/domain/util/NotificationIdGeneratorTest.kt | 390 | 36 | 1 | PURE | NotificationIdGenerator | KEEP | P2 | DailyBriefingWorkerTest | Range non-overlap + overflow; 1 reasoned @Ignore (negative IDs) |
| test/…/domain/util/StatisticsUtilsStressTest.kt | 416 | 35 | 0 | PURE | StatisticsUtils | STRENGTHEN | P2 | (none) | Known-value/sample-vs-population tests solid; 3 tautological "handle gracefully" tests |
| test/…/domain/util/StringDistanceUtilsStressTest.kt | 198 | 28 | 0 | PURE | StringDistanceUtils | KEEP | P2 | ReceiptTransactionMatcherTest, ReceiptMatchingE2ETest | Exact distances + fuzzy-match semantics; fast |
| test/…/domain/util/SystemMonotonicTimeProviderTest.kt | 61 | 3 | 0 | PURE | SystemMonotonicTimeProvider, FakeMonotonicTimeProvider | KEEP | P3 | (none) | Correctly asserts only monotonic contract, not platform timing |
| test/…/domain/util/TimePeriodUtilsStressTest.kt | 824 | 40 | 0 | PURE | TimePeriodUtils | STRENGTHEN | P1 | T4CBatch2A/2B, TimePeriodUtilsTest, ValidationTest | 14x TimeZone.setDefault WITHOUT GlobalTimeZoneTestLock; DST contiguity unique but unlocked |
| test/…/domain/util/TimePeriodUtilsT4CBatch1Test.kt | 480 | 24 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest | Field accessors + month keys vs java.time oracle across 3 zones under TZ lock |
| test/…/domain/util/TimePeriodUtilsT4CBatch2ATest.kt | 622 | 20 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsStressTest, ValidationTest | Day boundaries: hardcoded instants, DST gap/overlap, Long extremes, cutover seam |
| test/…/domain/util/TimePeriodUtilsT4CBatch2BTest.kt | 680 | 19 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest, TimePeriodUtilsStressTest | Week helpers: 167h/169h DST weeks, year rollover, dual oracles |
| test/…/domain/util/TimePeriodUtilsT4CBatch2CTest.kt | 1081 | 28 | 0 | PURE | TimePeriodUtils | KEEP | P0 | ValidationTest | Month helpers + parseMonthKeyToRange; exact cutover-boundary matrix, Int extremes |
| test/…/domain/util/TimePeriodUtilsT4CBatch2DTest.kt | 1306 | 36 | 0 | PURE | TimePeriodUtils | KEEP | P0 | ValidationTest | Year/quarter helpers; southern-hemisphere DST year, Int-extreme year overload |
| test/…/domain/util/TimePeriodUtilsTest.kt | 873 | 57 | 0 | PURE | TimePeriodUtils | KEEP | P0 | ValidationTest, T4C batches, TimePeriodAnalyticsAlignmentTest | Half-open contract, Monday weeks, week-key rollover, ISO vs app calendar |
| test/…/domain/util/TimePeriodUtilsValidationTest.kt | 1010 | 77 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest (partial DUP) | Full expected-value validation of every helper; substantial overlap with #21 |

## Batch 24 — domain workers + misc guards/receivers

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/java/…/domain/widget/WidgetStyleRepositoryTest.kt | 73 | 2 | 0 | MOCKED | WidgetStyleRepository (default toggleWidgetStyle) | REWRITE | P2 | HomeViewModelStressTest (touching) | TAUTOLOGY: fakes re-declare prod logic |
| test/java/…/domain/workers/FileWorkerTerminalDiagnosticSinkTest.kt | 652 | 22 | 0 | MOCKED | FileWorkerTerminalDiagnosticSink, WorkerTerminalDiagnosticReader | KEEP | P0 | — | real file IO; privacy-negative asserts |
| test/java/…/domain/workers/P9RemainingWorkerFixesTest.kt | 272 | 12 | 0 | MOCKED | WorkerSpec, WorkerRunLoggerImpl, WorkerExecutionGuard | STRENGTHEN | P1 | 12, 8, 13 | 2 stdlib `maxOf` tautologies; dup spec asserts |
| test/java/…/domain/workers/PrivacyRuntimeWorkerPolicyTest.kt | 144 | 9 | 0 | ROBOLECTRIC | PrivacyRuntimeWorkerPolicy | KEEP | P0 | — | pins data_retention cancel-exemption |
| test/java/…/domain/workers/WorkerBarrierIntegrationTest.kt | 473 | 23 | 0 | MOCKED | WorkerExecutionGuard + barriers | KEEP | P0 | 6 (partial DUP) | 5 restore modes; fixture dup of #6 |
| test/java/…/domain/workers/WorkerExecutionGuardTest.kt | 1140 | 48 | 0 | MOCKED | WorkerExecutionGuard (REAL) | KEEP | P0 | 5, 3 | anchor test; FRAGILE 11-mock ctor |
| test/java/…/domain/workers/WorkerGuardVerifierTest.kt | 101 | 3 | 0 | PURE | WorkerGuardVerifier, WorkerSpecScheduler | KEEP | P2 | 10, 23 | pins exact 10 worker names |
| test/java/…/domain/workers/WorkerIdempotencyTest.kt | 116 | 5 | 0 | PURE | WorkerSpec.DEFAULTS | KEEP | P2 | 3 | misleading name (spec config, not runtime) |
| test/java/…/domain/workers/WorkerLeaseRegistryTest.kt | 325 | 18 | 0 | MOCKED | WorkerLeaseRegistryImpl (real) | KEEP | P0 | 10 | real-clock drain waits (minor flake) |
| test/java/…/domain/workers/WorkerRestoreRegressionTest.kt | 404 | 15 | 0 | MOCKED | WorkerLeaseRegistryImpl, WorkerRunLoggerImpl, WorkerRegistry | STRENGTHEN | P1 | 9, 12 | lease+CAS sections dup 9/12 |
| test/java/…/domain/workers/WorkerRunContextThreadSafetyTest.kt | 43 | 2 | 0 | STRESS | WorkerRunContext | KEEP | P2 | — | 1000 concurrent increments |
| test/java/…/domain/workers/WorkerRunLoggerTest.kt | 980 | 58 | 0 | MOCKED | WorkerRunLoggerImpl, WorkerReasonCodes | KEEP | P0 | 3, 10 | terminal CAS + reason-code sanitization |
| test/java/…/domain/workers/WorkerSpecSchedulerTest.kt | 411 | 9 | 0 | ROBOLECTRIC | WorkerSpecScheduler | KEEP | P1 | 8 | FRAGILE: mockkObject + static WorkManager |
| test/java/…/AnalyticsEngineTestBase.kt | 404 | 0 | 0 | FIXTURE | (42 analytics/budget/e2e subclasses) | KEEP | P1 | 15 | mock layer re-implements SQL math |
| test/java/…/AnalyticsTestCompat.kt | 293 | 0 | 0 | FIXTURE | analytics engine compat shims (20+ users) | KEEP | P1 | 18 | duplicate currency fakes vs #18 |
| test/java/…/TestUtils.kt | 302 | 0 | 0 | FIXTURE | createExpense/assertApproxEquals (128 users) | KEEP | P1 | — | systemDefault TZ; deprecated helper kept |
| test/kotlin/…/domain/logic/RecurringExpenseEngineTest.kt | 316 | 12 | 0 | MOCKED | RecurringExpenseEngine | KEEP | P1 | RecurringExpenseEngineEmptyListTest | Calendar default-TZ (noon mitigates) |
| test/java/…/currency/CanonicalMultiCurrencyFixture.kt | 592 | 7 | 0 | FIXTURE | MultiCurrencyRepository, CurrencyConverter | KEEP | P0 | 15 | 142-EUR golden; fakes unused elsewhere |
| test/java/…/guard/DbGuardPolicyFixtureTest.kt | 2298 | 72 | 0 | SRCTEXT | db_ownership_policy.yml + 4 prod sources | KEEP | P2 | batch-05 arch guards | FRAGILE pinned counts 99/62/58/4 |
| test/java/…/guard/MoneyBoundaryGuardTest.kt | 214 | 10 | 0 | STRESS | scripts/verify_money_boundaries.py | KEEP | P2 | batch-05, scripts/ | requires Python on PATH |
| test/java/…/guards/GuardSeededViolationTest.kt | 102 | 4 | 0 | SRCTEXT | scripts/guards/*.kts (existence only) | DELETE | P4 | #20, scripts/ CI | tautology: never runs the scripts |
| test/java/…/receiver/BootReceiverStressTest.kt | 61 | 4 | 0 | ROBOLECTRIC | BootReceiver | KEEP | P2 | — | prior "environment-dependent" OVERTURNED |
| test/java/…/receiver/ServiceRestartReceiverStressTest.kt | 51 | 3 | 0 | ROBOLECTRIC | ServiceRestartReceiver | KEEP | P2 | — | misnamed "stress"; deterministic |

## Batch 25 — service + startup

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/service/NavigationTargetResolverTest.kt | 424 | 32 | 0 | MOCKED | NavigationTargetResolverImpl | KEEP | P2 | HomeViewModelRecommendationTest, HomeViewModelStressTest | Real mapping/period logic asserted |
| test/…/service/NotificationCaptureServiceCleanupTest.kt | 185 | 7 | 0 | MOCKED | NotificationCaptureService, NotificationServiceWorkTracker | STRENGTHEN | P1 | NotificationPrivacyHardeningTest, PrivacyStorageContractTest | 2 reflection signature tests FRAGILE |
| test/…/service/NotificationCaptureServiceFallbackTest.kt | 69 | 5 | 0 | PURE | (none — tautology) | DELETE | P4 | — | Re-implements fallback in test; prior KEEP overturned |
| test/…/service/NotificationFilterTest.kt | 345 | 25 | 0 | PURE | NotificationFilter | KEEP | P1 | — | Pins NEW-P1-2026-001 "pos" regression |
| test/…/service/RecommendationCacheServiceTest.kt | 383 | 18 | 0 | MOCKED | RecommendationCacheService | STRENGTHEN | P2 | RecommendationLifecycleManagerTest | No resetMain; TTL test is a no-op |
| test/…/service/RecommendationDeduplicatorTest.kt | 203 | 11 | 0 | PURE | RecommendationDeduplicator | KEEP | P2 | RecommendationRepositoryTest | Real dedup semantics, real serializer |
| test/…/service/RecommendationDismissalHandlerTest.kt | 387 | 23 | 0 | MOCKED | RecommendationDismissalHandler | STRENGTHEN | P2 | HomeViewModelRecommendationTest | Verify-heavy; low-value permutations |
| test/…/service/RecommendationLifecycleManagerTest.kt | 473 | 32 | 0 | MOCKED | RecommendationLifecycleManager | STRENGTHEN | P2 | RecommendationCacheServiceTest | FRAGILE unguarded private-field reflection |
| test/…/service/RecommendationStateManagerTest.kt | 830 | 30 | 0 | MOCKED | RecommendationStateManager | KEEP | P1 | RecommendationDismissalHandlerTest | Deterministic generation-guard overlap tests |
| test/…/service/TransactionFilterSerializerTest.kt | 236 | 16 | 0 | PURE | TransactionFilterSerializer | KEEP | P1 | DashboardFollowThroughEngineTest, RecommendationRepositoryTest | Round-trip + malformed-input robustness |
| test/…/service/receiptmatching/ReceiptMatchingWorkerTest.kt | 686 | 24 | 0 | ROBOLECTRIC | ReceiptMatchingWorker | KEEP | P0 | WorkerGuardStaticVerificationTest | Partial guard mirror; superb diagnostics/privacy pins |
| test/…/service/reminder/BillReminderWorkerTest.kt | 255 | 4 | 0 | ROBOLECTRIC | BillReminderWorker | KEEP | P1 | WorkerIdempotencyTest, BillReminderWorkerTimeProviderTest | Guard blanket-mocked (systemic tier c) |
| test/…/service/reminder/BillReminderWorkerTimeProviderTest.kt | 133 | 3 | 0 | ROBOLECTRIC | BillReminderWorker | KEEP | P2 | BillReminderWorkerTest | Quiet hours via FakeTimeProvider |
| test/…/service/reminder/DismissReminderActionWorkerTest.kt | 71 | 2 | 0 | MOCKED | DismissReminderActionWorker | KEEP | P2 | WorkerGuardStaticVerificationTest | No guard Skipped/Retry mapping tests |
| test/…/service/reminder/SnoozeReminderActionWorkerTest.kt | 71 | 2 | 0 | MOCKED | SnoozeReminderActionWorker | KEEP | P2 | WorkerGuardStaticVerificationTest | Mirror of #14 for snooze |
| test/…/service/warranty/WarrantyExpirationWorkerTest.kt | 342 | 12 | 0 | ROBOLECTRIC | WarrantyExpirationWorker | KEEP | P0 | WorkerGuardStaticVerificationTest, DbGuardPolicyFixtureTest | Real Room claim/dedup; faithful guard mirror |
| test/…/startup/AppStartupCoordinatorRecoveryTest.kt | 204 | 4 | 0 | ROBOLECTRIC | AppStartupCoordinator, RestoreJournal, RestoreMaintenanceMode | KEEP | P0 | WriteBarrierArchitectureGuardTest, WorkerContractTest | Real journal files + persisted mode |

## Batch 27 — ui (1/2)

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/ui/MainViewModelStressTest.kt | 79 | 5 | 5 | VIEWMODEL | MainViewModel | NIGHTLY | P3 | — | class @Ignore; 1 tautology test |
| test/…/ui/components/emptystate/ContextualActionRegistryTest.kt | 137 | 7 | 0 | PURE | ContextualActionRegistry | KEEP | P2 | EmptyStateRegistryCompletenessTest | real state/Flow assertions |
| test/…/ui/components/emptystate/EmptyStateRegistryCompletenessTest.kt | 70 | 2 | 0 | PURE | DefaultEmptyStateRegistryInitializer | KEEP | P2 | ContextualActionRegistryTest | completeness contract |
| test/…/ui/navigation/DeepLinkParserTest.kt | 96 | 12 | 0 | ROBOLECTRIC | parseDeepLink/DeepLinkDecision | KEEP | P1 | — | real parser; confirmation gating |
| test/…/ui/navigation/DestinationPersistencePolicyTest.kt | 91 | 4 | 0 | PURE | NavigationDestination.persistencePolicy | KEEP | P2 | NavigationRouteContractTest | samples only some destinations |
| test/…/ui/navigation/FeatureConfigNavigationContractTest.kt | 94 | 7 | 0 | PURE | FeatureConfig | KEEP | P2 | NavigationRouteContractTest | uniqueness + roundtrip |
| test/…/ui/navigation/NavigationControllerBehaviorTest.kt | 161 | 12 | 0 | PURE | NavigationController | KEEP | P1 | — | back-stack semantics, no Android |
| test/…/ui/navigation/NavigationRouteContractTest.kt | 420 | 23 | 0 | PURE | NavigationDestination toSaveToken | KEEP | P0 | DestinationPersistencePolicyTest | full roundtrip + legacy format |
| test/…/ui/screens/addexpense/AddExpenseViewModelStressTest.kt | 146 | 8 | 8 | VIEWMODEL | AddExpenseViewModel | NIGHTLY | P3 | AddExpenseViewModelTest (partial DUP) | class @Ignore; weaker duplicate |
| test/…/ui/screens/addexpense/AddExpenseViewModelTest.kt | 129 | 4 | 0 | VIEWMODEL | AddExpenseViewModel | KEEP | P1 | #9 | debounce-cancel, prefill-once |
| test/…/ui/screens/aisettings/AiSettingsViewModelTest.kt | 197 | 7 | 0 | VIEWMODEL | AiSettingsViewModel | KEEP | P0 | — | API key stored only after test OK |
| test/…/ui/screens/analytics/AdvancedAnalyticsViewModelTest.kt | 79 | 2 | 0 | VIEWMODEL | AdvancedAnalyticsViewModel | KEEP | P1 | #14/#15/#16 | currency-change reload |
| test/…/ui/screens/analytics/AnalyticsStateMoneySafetyTest.kt | 60 | 5 | 0 | PURE | AnalyticsState money fallback | KEEP | P1 | — | null/invalid currency fail-safe |
| test/…/ui/screens/analytics/AnalyticsViewModelInsightsTest.kt | 632 | 6 | 0 | VIEWMODEL | AnalyticsViewModel, InsightsEngine | KEEP | P1 | #12,#15,#16; TimePeriodAnalyticsAlignmentTest | FRAGILE 18 ctor args |
| test/…/ui/screens/analytics/AnalyticsViewModelStressTest.kt | 282 | 8 | 8 | VIEWMODEL | AnalyticsViewModel | NIGHTLY | P3 | #12,#14,#16 | class @Ignore; mostly no-crash |
| test/…/ui/screens/analytics/BudgetVsActualFxBasisTest.kt | 199 | 1 | 0 | VIEWMODEL | AnalyticsViewModel, CurrencyConverter | KEEP | P0 | #14 | FRAGILE 18 args; convertAsOf contract |
| test/…/ui/screens/assistant/AssistantViewModelTest.kt | 806 | 26 | 0 | VIEWMODEL | AssistantViewModel | KEEP | P0 | — | FRAGILE: reflection into privates |
| test/…/ui/screens/backup/BackupRestoreViewModelTest.kt | 315 | 12 | 0 | VIEWMODEL | BackupRestoreViewModel | KEEP | P0 | — | preflight, maintenance-mode exit |
| test/…/ui/screens/bank/BankConnectionsViewModelTest.kt | 69 | 4 | 0 | MOCKED | BankConnectionsViewModel | STRENGTHEN | P3 | — | likely red: no Main dispatcher |
| test/…/ui/screens/budget/BudgetForecastingViewModelTest.kt | 289 | 6 | 0 | VIEWMODEL | BudgetForecastingViewModel | KEEP | P1 | — | Turbine state machine |
| test/…/ui/screens/budget/BudgetViewModelStressTest.kt | 303 | 15 | 15 | VIEWMODEL | BudgetViewModel | NIGHTLY | P3 | — | class @Ignore; many no-assert tests |
| test/…/ui/screens/carbon/CarbonFootprintScreenTest.kt | 40 | 3 | 0 | PURE | resolveCarbonFootprintContentState | KEEP | P2 | CarbonFootprintViewModelTest | content-state resolver |
| test/…/ui/screens/carbon/CarbonFootprintViewModelTest.kt | 253 | 8 | 0 | VIEWMODEL | CarbonFootprintViewModel | KEEP | P1 | #22 | stale-result-wins race |
| test/…/ui/screens/cashflow/CashFlowCalendarViewModelTest.kt | 419 | 10 | 0 | VIEWMODEL | CashFlowCalendarViewModel | KEEP | P0 | — | DBG-01 currency race; partial flag |
| test/…/ui/screens/challenge/SpendingChallengesViewModelTest.kt | 128 | 3 | 0 | VIEWMODEL | SpendingChallengesViewModel | KEEP | P2 | — | canonical-source availability |
| test/…/ui/screens/currency/CurrencyManagementScreenValidationTest.kt | 44 | 4 | 0 | PURE | isAmountParseableAndPositive etc | KEEP | P2 | — | input validation helpers |
| test/…/ui/screens/currency/CurrencyManagementViewModelTest.kt | 185 | 4 | 0 | VIEWMODEL | CurrencyManagementViewModel | KEEP | P1 | — | conversion + refresh states |
| test/…/ui/screens/debug/DebugViewModelStressTest.kt | 254 | 7 | 7 | VIEWMODEL | DebugViewModel | NIGHTLY | P3 | — | class @Ignore; FRAGILE 16 deps |
| test/…/ui/screens/export/ExportOptionsViewModelTest.kt | 465 | 15 | 0 | VIEWMODEL | ExportOptionsViewModel | KEEP | P0 | ExportReadBarrierTest (complementary) | real privacy gate; fail-closed enc |
| test/…/ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt | 26 | 2 | 0 | PURE | isSettledBalance/rounding helpers | KEEP | P2 | — | money rounding semantics |
| test/…/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt | 463 | 7 | 0 | VIEWMODEL | SharedExpenseGroupsViewModel | KEEP | P1 | — | asserts atomic use case path |
| test/…/ui/screens/home/DashboardWidgetMetaContractTest.kt | 41 | 2 | 0 | PURE | HomeViewModel.getWidgetId | STRENGTHEN | P3 | #35; metrics/DashboardWidgetConsistencyTest | hardcoded ID list, 4 widgets only |
| test/…/ui/screens/home/DashboardWidgetRenderCoverageTest.kt | 79 | 3 | 0 | PURE | DashboardWidget sealed subclasses | KEEP | P2 | metrics/DashboardWidgetConsistencyTest | reflection coverage, not SRCTEXT |
| test/…/ui/screens/home/HomeViewModelRecommendationTest.kt | 501 | 24 | 0 | MOCKED | (none — mocks only) | REWRITE | P3 | #35 | still never instantiates HomeViewModel |
| test/…/ui/screens/home/HomeViewModelStressTest.kt | 522 | 19 | 19 | VIEWMODEL | HomeViewModel | NIGHTLY | P3 | #32,#34 | class @Ignore; FRAGILE 20 args |
| test/…/ui/screens/lifestyle/LifestyleInflationScreenTest.kt | 107 | 6 | 0 | PURE | trend weights/content state | KEEP | P2 | #37 | bar-weight math incl. clamping |
| test/…/ui/screens/lifestyle/LifestyleInflationViewModelTest.kt | 231 | 7 | 0 | VIEWMODEL | LifestyleInflationViewModel | KEEP | P2 | #36 | stale-result-wins race |
| test/…/ui/screens/map/SpendingMapViewModelStressTest.kt | 471 | 10 | 3 | VIEWMODEL | SpendingMapViewModel | MERGE | P2 | — | intra-file DUP; unbounded awaitUntil |

## Batch 28 — ui/util/worker tail (2/2)

| File | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Note |
|---|---|---|---|---|---|---|---|---|---|
| test/…/ui/screens/price/PriceProtectionViewModelTest.kt | 251 | 9 | 0 | VIEWMODEL | PriceProtectionViewModel (S34) | STRENGTHEN | P3 | arch-guards only | 2 verify-only tests; "sorted" test never asserts order |
| test/…/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt | 218 | 4 | 0 | VIEWMODEL | ReceiptMatchingViewModel (S38) | KEEP | P1 | arch-guards only | Real state transitions + repo link/reject verify |
| test/…/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt | 853 | 19 | 1 (class) | ROBOLECTRIC | ReceiptScanViewModel (S4) | NIGHTLY | P1 | arch-guards only | FRAGILE; reflection into `_state`; only VM coverage; not actually stress |
| test/…/ui/screens/recurringmanual/ManualRecurringExpenseViewModelTest.kt | 234 | 4 | 0 | VIEWMODEL | ManualRecurringExpenseViewModel (S7) | KEEP | P2 | arch-guards only | Sort/activeCount/totalMonthly asserted |
| test/…/ui/screens/reminder/BillRemindersViewModelTest.kt | 130 | 4 | 0 | VIEWMODEL | BillRemindersViewModel (S36) | KEEP | P2 | arch-guards only | Load/refresh/error-empty covered |
| test/…/ui/screens/review/ReviewScreenTransactionTypeParserTest.kt | 25 | 3 | 0 | PURE | parseTransactionTypeOrNull (S3) | KEEP | P3 | #7 complementary | Tests real `internal` helper in ReviewScreen.kt:1335 |
| test/…/ui/screens/review/ReviewScreenTransferDirectionParserTest.kt | 25 | 3 | 0 | PURE | parseTransferDirectionOrNull (S3) | KEEP | P3 | #6 complementary | ReviewScreen.kt:1329 |
| test/…/ui/screens/review/ReviewViewModelStressTest.kt | 1353 | 37 | 1 (class) | MOCKED | ReviewViewModel (S3) | NIGHTLY | P1 | arch-guards only | FRAGILE; reflection into `_reviewCaptureAssistStates`; ~5 assertNotNull-only tests |
| test/…/ui/screens/savings/SavingsGoalsViewModelTest.kt | 303 | 7 | 0 | VIEWMODEL | SavingsGoalsViewModel (S35) | KEEP | P1 | none | Stateful fake repo; atomic-add stacking; legacy per-goal API retired (exactly=0) |
| test/…/ui/screens/split/VisualSplitEditorScreenStateTest.kt | 112 | 11 | 0 | PURE | SplitTextFieldState (S21) | KEEP | P1 | none | Hidden gem: NaN/Infinity/locale comma/trailing-dot |
| test/…/ui/screens/split/VisualSplitViewModelTest.kt | 227 | 5 | 0 | VIEWMODEL | VisualSplitViewModel + buildCompletedSplitShares (S21) | STRENGTHEN | P2 | #10 complementary | Tests 1–4 assert mock pass-through; test 5 (dup names by index) real |
| test/…/ui/screens/subscription/SubscriptionManagementViewModelTest.kt | 184 | 4 | 0 | VIEWMODEL | SubscriptionManagementViewModel (S34) | KEEP | P2 | arch-guards only | Real monthly/annual cost math (20/240) |
| test/…/ui/screens/transactions/TransactionsViewModelStressTest.kt | 343 | 16 | 1 (class) | MOCKED | TransactionsViewModel (S9) | NIGHTLY | P2 | arch-guards only | @Ignore; pagination stop + filter param threading good; 2 no-crash-only tests |
| test/…/ui/screens/warranty/WarrantyTrackerViewModelTest.kt | 275 | 6 | 0 | VIEWMODEL | WarrantyTrackerViewModel (S34) | STRENGTHEN | P2 | arch-guards only | KDoc self-lists gaps: sort, receipt link, status transitions |
| test/…/ui/util/ClipboardAmountParserTest.kt | 47 | 3 | 0 | MOCKED | ClipboardAmountParser (S32) | KEEP | P3 | none | Grouped-amount regex behavior; real ClipData on JVM (minor hazard) |
| test/…/util/CsvExpenseImporterTest.kt | 187 | 12 | 0 | MOCKED | CsvExpenseImporter (S9) | KEEP | P1 | #17 complementary | Goes through legal TransactionLifecycleCoordinator path; 1 trivial instantiation test |
| test/…/util/ExportImportRoundtripTest.kt | 169 | 4 | 0 | MOCKED | ExportTransaction + CsvExpenseImporter (S9/18) | STRENGTHEN | P1 | #16 complementary | Test 2 misnamed assertNotNull-only tautology; v2 roundtrip tests valuable |
| test/…/util/HiltTestUtils.kt | 34 | 0 | 0 | FIXTURE | (Hilt base class) | DELETE | P3 | none | Zero subclassers in test+androidTest; prior KEEP overturned |
| test/…/util/JsonExpenseImporterTest.kt | 277 | 9 | 0 | MOCKED | JsonExpenseImporter (S9) | KEEP | P1 | none | Date/timestamp/provider fallback contract via counting TimeProvider |
| test/…/util/ViewModelTestUtils.kt | 48 | 0 | 0 | FIXTURE | (Main-dispatcher base) | KEEP | P1 | 35 subclassers | Core ViewModel-test foundation |
| test/…/worker/NotificationIntakeWorkerTimeoutTest.kt | 941 | 15 | 0 | MOCKED | NotificationIntakeWorker + real WorkerExecutionGuard (S3/12) | KEEP | P0 | arch-guards only | Real guard; privacy fail-closed + no raw-payload-load proven; 3 dup type-check tests; dead `intakeEntity` helper (line 738) |
| test/…/workers/WorkerContractTest.kt | 89 | 5 | 0 | ROBOLECTRIC | WorkerSpec.DEFAULTS + WorkerRegistry (S12) | STRENGTHEN | P1 | none | Registry-parity test valuable; `enabled \ |
