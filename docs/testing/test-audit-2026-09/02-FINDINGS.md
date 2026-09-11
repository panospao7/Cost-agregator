# Test Suite Audit 2026-09 — Findings

**Scope:** all 675 `.kt` files under `app/src/test` + `app/src/androidTest` (~180k LOC). Guardrail tests (`architecture/`) inventoried light-touch only, per scope decision.
**Evidence:** per-file verdicts with citations live in [`batches/`](batches/) (26 files). This document is the cross-batch synthesis. Verdict census: KEEP 436 · STRENGTHEN 102 · MERGE 61 · REWRITE 35 · DELETE 25 · NIGHTLY 15 · UNKNOWN 1 — see [01-INVENTORY.md](01-INVENTORY.md).
**Method note:** fully static review (no Gradle runs). "Predicted failing" means statically deduced; every such claim needs one targeted measured run to confirm (see §H).

---

## A. What the suite gets right (protect these anchors)

The suite is not uniformly weak — it contains genuinely excellent clusters that any consolidation must preserve untouched:

- **Time/timezone math (Segment 32)** — `TimePeriodUtils` is the best-tested class in the repo: legacy + Validation + five T4C migration-lock suites (~127 tests) with dual independent oracles (Calendar + java.time), the 1582 Gregorian-seam boundary, DST 23h/25h days, and anti-`DAY_IN_MILLIS` guards (`domain/util/` batch 23).
- **Money primitives (Segment 16/17)** — `MoneyTest` (BigDecimal exactness, split-sum invariants), `ConversionSemanticsHardeningTest` (staleness policy, weakest-leg provenance, validDate boundary), `domain/core/money` fail-closed conversion tests, `metrics/EffectiveAmountConsistencyTest` (cross-engine share-precedence invariant), `verification/GoldenMasterVerificationTest` (15+ exact-constant money checks), CSV formula-injection tests (`CsvCellSanitizer` DDE vectors).
- **Worker-guard core (Segment 12)** — `WorkerExecutionGuardTest` (48 tests), `WorkerRunLoggerTest` (58), `FileWorkerTerminalDiagnosticSinkTest` (22), `worker/NotificationIntakeWorkerTimeoutTest` (real guard, timeout→retry vs failure, privacy deny→no decrypt), `ReceiptMatchingWorkerTest` (sanitized reason codes, atomic claim), `WarrantyExpirationWorkerTest` (faithful guard mirror + real Room race tests).
- **DB lifecycle contracts** — `scenarios/TransactionLifecycleCoordinatorDbContractTest` (dedupe events, merchantKey regeneration), `GroupLifecycleScenarioTest` (33 tests, real coordinator), `GroupTransactionCoordinatorTest` (real atomicity/fail-closed), `ScannedReceiptClaimTest` (CAS double-link guard across 5 match states), `data/backup/DatabaseBarrierTest` (11-mode write matrix + 3 read policies, exactly matches LEGAL_PATHS).
- **Privacy data layer** — `BackupEncryptionServiceTest` (GCM tamper/bad-tag), `ExportAnonymizerTest` (real SQLite redaction, dedup hashes preserved), `RetentionTargetPurgeTest` (real Room + production targets), `PrivacySettingsRepositoryImplCorruptionTest` (garbage-byte DataStore → fail-closed DO_NOT_STORE), `InputBuilderRedactionPolicyTest` + `DefaultAiCapabilityRouterTest` + `AiPolicyTest` (P8 privacy-routing contracts).
- **androidTest gold** — `ExpenseDaoTest` (40 money/dedup invariants), `DedupeKeyUniquenessRegressionTest`, migration tail v145→148 matches the LEGAL_PATHS baseline.

**P0 census:** 129 files carry P0 priority — these are the invariant-protecting core the consolidation plan must not damage.

---

## B. Predicted-failing / stale tests (static evidence)

These will fail (or silently prove nothing) on their next run. Each needs a targeted measured run to confirm (§H):

| File | Evidence | Likely root cause / action |
|---|---|---|
| `golden/StaleRateCurrencyConversionGoldenTest` | committed JSON predates 24h RATE_STALE behavior; lacks `gbpFailureReason` key the verifier now emits (`CurrencyConverter.kt:87`) | Regenerate fixture + review diff; the exact regression it exists to catch is currently uncatchable |
| `golden/Pipeline4LifecycleGoldenTest` | asserts occurrence/reminder/planned generation while calling raw DAOs; generation lives only in `RecurringRuleLifecycleCoordinator`; no FK cascade | Statically cannot pass; also a legal-path bypass. Rewrite against the coordinator |
| `architecture/DirectEventDaoInsertGuardTest` | 8 `APPROVED_ENTRIES` expired **2026-08-15** (already past); expiry test compares `LocalDate.now()` | Allowlist renewal needed, not deletion — **will fail next CI run** |
| `data/ai/CloudDashboardBriefingServiceTest` (ledger F-11/F-19) | 2-arg `@VisibleForTesting` ctor installs `failClosedGate()` (`CloudDashboardBriefingService.kt:85-98`) → gate denies before HTTP → retry counters stay 0 | Stale test constructor; construct with allowed gate + real `DefaultCloudPayloadPolicy` (pattern exists in `CloudCategorizationAssistServiceTest`) |
| `domain/budget/BudgetMonitorTest` + `BudgetMonitorStressTest` (F-03) | prod gates `updateXNotification` on `sendBudgetAlert(...) == DELIVERED` (`BudgetMonitor.kt:433`); tests never stub the enum → `delivered=false` | Stub `DeliveryResult.DELIVERED` in both files (ledger's "cached statuses" theory was only half right) |
| `domain/intelligence/ml/ExpenseCategoryClassifierTest` (F-04/F-17) | three tests hold two live classifier instances over the same filesDir → "multiple DataStores active"; relaxed `atRestEncryptionService` returns `""` → JSONException on reload | Isolation fix; content is high value — do not delete |
| `data/repository/AutomatedSavingsRuleStateRepositoryTest` (F-17) | recreates DataStore on same temp file immediately after `scope.cancel()` without awaiting (lines 42-45) | Await cancellation or use unique file |
| `domain/reminder/BillReminderManagerTest` (F-06) | production `markBillPaid` now hard-errors (`BillReminderManager.kt:155`) | 3 tests can never pass; re-home 2 real tests to the linkExpenseToOccurrence contract |
| `data/repository/ReviewQueueRepositoryTest` (F-08/F-13) | prod casts `mutation.value as CreateExpenseResult.Created` (`ReviewQueueRepository.kt:627`); un-stubbed generic mock path returns bare `Object` | Strict-mock stub gap; make coordinator mocks strict so this class of bug surfaces at compile/stub time |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest` + `TransactionLifecycleCoordinatorTest` (F-02) | `UncompletedCoroutinesError` after 1m — root cause **undecidable statically**; shared coroutine harness is the suspect | Fix the shared harness before touching individual tests |
| `domain/tax/TaxEstimatorTest` (F-05) | expected values computed by mirroring the estimator's own period-fraction formula | Drift-prone by construction; recompute expectations as independent constants |
| `data/location/MerchantKeyBackfillWorkerTest` (F-20) | `still_broken` vs `stillbroken` normalization drift | Deliberate decision required: pin new normalization or fix production |
| `domain/health/HealthScoreGoldenTest` | name says "57 and stable", asserts 55/IMPROVING (`:68-79`) | F-07-style drift marker; re-baseline deliberately |
| `domain/budget/BudgetTrendBoundaryTest` | stubs the dead `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween` path; engine now reads snapshots (`BudgetForecastingEngine.kt:389`) | Rewrite against current read path (unique ±10% boundary value) |
| `ui/screens/bank/BankConnectionsViewModelTest` | VM uses `viewModelScope`; test lacks `Dispatchers.setMain` | Likely red at runtime; add main-dispatcher rule |

**Ledger families statically resolved in-tree (confirm with one run, then close):** F-01 (`flowOf("EUR")` already at `BankStatementParserTest:33`), F-09/F-14 (typed `DatabaseAccessBlockedException` asserted; subclasses `IllegalStateException`), F-15 (missing schema JSONs 119/120/141/142 now exist under `app/schemas/`, wired into the test sourceSet at `app/build.gradle.kts:94`).

---

## C. Negative-value files (DELETE — 25 files, ~2.6k LOC)

All 25 carry no unique regression power; P4-flagged ones are marked ★. Full reasons per file in the batch reports.

**Tautologies / self-assertions (test asserts its own simulation or data-class mechanics):**
`integration/Curr587BehavioralTest` ★ · `scenarios/MapMarkerConversionCurrencyTest` ★ (tests an in-file helper; zero production marker code exists) · `data/database/dao/ExpenseDaoBoundaryConsistencyTest` ★ (15 TODO tautologies, zero DAO usage) · `data/database/entity/MileageTrackingValidationTest` ★ · `data/location/AndroidForegroundLocationProviderTest` ★ · `domain/privacy/NotificationPrivacyHardeningTest` ★ (simulates the privacy flow locally; false privacy confidence) · `domain/usecase/dashboard/DashboardProjectionSafetyTest` ★ (re-implements guarded formulas, asserts the copies) · `service/NotificationCaptureServiceFallbackTest` ★ (re-implements the `takeIf` chain).

**Tests of stdlib/platform, not production:** `domain/receipt/BitmapConcurrencyTest` ★ (kotlinx Mutex).

**Empty husks of removed APIs (class-level `@Ignore`, removed APIs):** `domain/receipt/lifecycle/ReceiptLifecycleBugFixesTest` ★ · `ReceiptLifecycleHardeningTest` ★ · `domain/recurring/RecurringLifecycleFixesTest` ★.

**Stale `@Ignore`d stress tests that would fail if un-ignored (production changed under them):** `data/repository/ExpenseRepositoryStressTest` ★ (verifies direct `expenseDao.delete`; prod routes through the coordinator) · `data/repository/NotificationProcessingPipelineStressTest` ★ (re-implements the pipeline; `result != null || result == null` tautology) · `data/repository/CategoryRepositoryStressTest` (zero assertions, mocks the `dagger.Lazy` wrapper itself).

**Dead infrastructure:** `e2e/FlowPipelineTestHarness` (superseded by golden/GoldenTestBase) · `domain/currency/MultiCurrencyTestFixture` (zero consumers; prior-audit claim of wide usage overturned) · `util/HiltTestUtils` (zero subclassers) · `guards/GuardSeededViolationTest` ★ (matches its own seeded text).

**One-off cleanup guards / dead sinks:** `domain/analytics/IncludeDepositsForBehaviorCleanupTest` · `data/backup/MaintenanceSafeDiagnosticSinkTest` (all no-crash/mock-echo; the persisted sink is properly covered elsewhere).

**Dead production code exposed (flag for prod pruning, not test pruning):** `domain/usecase/receipt/ProcessReceiptUseCaseHomeCurrencyTest` — the test is fine, but `ProcessReceiptUseCase` has **zero production references** (grep across `app/src/main`).

---

## D. Duplicates and crossover clusters (MERGE — 61 files)

The user's "crossover tests covering the same concepts with different means" is confirmed and concentrated in these clusters. **Rule for every merge: port unique tests to the survivor first, then delete.** Survivor named in each line; details in batch reports.

1. **Restore/write-barrier** (worst cluster): `golden/RestoreBlocksAllWritesTest` + `golden/WorkerRestoreBarrierIdempotencyGoldenTest` mock `RestoreMaintenanceMode` and near-duplicate `data/backup/DatabaseBarrierTest`, which is the **survivor** (real barrier, 11-mode matrix, F-14-aligned). `ExportReadBarrierTest` folds into `DatabaseBarrierTest` (port Flow-helper tests). The goldens mock the mode — they prove nothing the barrier test doesn't.
2. **JVM contract tests superseded by guards**: `contracts/CancellationPropagationContractTest`, `LifecycleBarrierContractTest`, `MoneyContractTest` → superseded by `architecture/CancellationSafetyArchitectureGuardTest`, `WriteBarrierArchitectureGuardTest`, money scripts. The 2026-05 "keep as CI guards" decision now applies to only 3 of 6 contract files (`PrivacyStorageContractTest`, `SideEffectContractTest`, `RecurringDeactivateContractTest` remain unique).
3. **JVM guard ↔ script guard duplication** (Segment 39): 6 true pairs — CancellationSafety↔`verify_cancellation_boundaries.py` (two parallel allowlists!), WorkerGuard+SourceScanning↔`verify_worker_boundaries.py`, DirectEventDaoInsertGuard↔`verify_event_writers.py`, WriteBarrierGuard↔`verify_db_access_boundaries.py`, ExpenseDaoMutationAccess+RawDao↔db-ownership policy, Engine5 E5-002↔`verify_time_boundaries.py`. Plus a **third orphan copy**: `scripts/guards/*.kts` + `dao-access-check.kts` are wired nowhere.
4. **e2e/integration ↔ golden cross-coverage** (batch 07): dashboard-parity ← 6 files; notification-dashboard ← 4 (incl. the 636-LOC FRAGILE `NotificationExpenseDashboardPipelineTest`); recurring-match ← 3; receipt-match ← 3; currency semantics ← 4; settlement ← 2. Notably, 5 `GoldenTestBase`-derived e2e tests are **real** Room+pipeline tests mis-packaged as e2e — fold into `golden/` (content is P0; e.g. `RecurringPaymentMatchE2ETest` real coordinator generate→link→paid→dashboard-once).
5. **Scenarios internals**: `GroupLifecycleContractTest` ⊂ `GroupLifecycleScenarioTest` (port removeMember-open-balance test); `PrivacyCloudLocationDeniedScenarioTest#3` ≡ `PrivacyGateContractTest#3` verbatim; decorative-seeding money trio (`MoneyAggregateConversionScenarioTest`, `MixedCurrencyCoreFinancialScenarioTest`, `MulticurrencyPartialRateScenarioTest`) seed data never read into asserted aggregates — but carry the **only coverage of the `MoneyAmount.plus` cross-currency throw** into `domain/core/money/` first.
6. **Same-class stress/main pairs**: `MerchantNormalizerStressTest` (misnamed; 12 fast unit tests) → `MerchantNormalizerTest`; `MerchantKeyGeneratorStressTest` → `MerchantKeyGeneratorTest` (port emoji/ZWJ/10k-char cases); `SplitCalculatorGoldenTest` verbatim subset of `SplitCalculatorTest`; `ReviewQueueRepositoryStressTest` ⊂ `ReviewQueueRepositoryTest` ★; `AppParserRegistryTest` ↔ `AppParserRegistryRoutingTest`.
7. **Deprecated-path pairs** (batch 19): `AreaSpendingEngineStressTest` + `TravelDetectionEngineStressTest` test `compute()`, which is `@Deprecated` with zero production callers — carry TravelDetection's unique trip-gap/determinism tests into the Normalized file, then delete.
8. **Privacy-file fragmentation**: `RawStoragePolicyAuditTest` → `RawStorageEndToEndTest` (~70% dup; contains a set-contains-itself "coverage" test); `CloudProviderPreparedPayloadTest` ↔ `CloudPayloadPolicyTest`; `RetentionRegistryTest` (~85% tautology) → fold any real bits into `RawStorageEndToEndTest`; `UPR5CompletionTest` ↔ `PR5PrivacyContractTest`.
9. **Worker-internal dups**: `P9RemainingWorkerFixesTest` idempotency/spec sections duplicate `WorkerRunLoggerTest`/`WorkerIdempotencyTest` (keep its unique battery-constraint + unexpected-RuntimeException fail-safe tests); `WorkerRestoreRegressionTest` ↔ `WorkerLeaseRegistryTest`; `WorkerBarrierIntegrationTest` ↔ `WorkerExecutionGuardTest` (partial).

---

## E. Wrong-object tests (REWRITE — 35 files)

The most insidious quality problem: tests that look legitimate but run a **test-local mirror** of production logic, so a production regression cannot fail them.

- `scenarios/RecurringNoDoubleCountScenarioTest` — the headline "no double count" scenario is tautological (separate tables make 25.98 impossible); the golden is the real one.
- `domain/split/SplitCalculationPrecisionTest` — all 22 tests run a test-local mirror of `EnhancedSplitManager`.
- `domain/tax/TaxCalculationTest` — VAT/bracket math mirrored; its `extractVat` helper is itself wrong (integer divide by 1), hidden behind the file's lone `@Ignore`.
- `data/backup/AppOperationalStateTest` — each test stubs the flow with its own `modeToState` reimplementation and asserts it back; `RestoreMaintenanceMode.toOperationalState` has zero real coverage.
- `metrics/GoldenAnalyticsDatasetTest` — compares dataset constants against helpers re-implemented inside the test.
- `diagnostics/DDL512RegressionTest` — journal-ordering tests prove java.io/org.json semantics; block tests assert its own `TrackingHandle` double; only the 6 `EventMetadataSanitizer` tests are real.
- `data/ai/CloudReceiptItemCategorizationServiceTest#1` — named "redaction does not include raw names" but asserts raw names DO appear (redaction moved to `CloudPayloadPolicy`).
- `verification/DedupeKeyProducerConsistencyTest` — "all producers agree" compares one function to itself 6×; the producer call-site wiring (ISSUE-8) is enforced nowhere.
- `ui/HomeViewModelRecommendationTest` (501 LOC) — still never instantiates HomeViewModel.
- `domain/analytics/BankStatementItemAuditTest`, `P6BudgetCleanupTest` (7/9 reflection/self-math), `P5AnalyticsFixesTest` (4/6 tautologies), `PrivacyBehavioralRegressionTest` (`1 == 1` "verified by code inspection"), `TotalAggregation` monthAvg computed-never-asserted (`TotalsAggregationEngineDeepTest:164-177`), `P7BugFixesTest` (2 tests reimplement SQL-quoting instead of invoking the guarded method), `P9RemainingWorkerFixesTest` (2 stdlib `maxOf` tests), `AccountingExportPolicyTest` (first test has no assertion), `WidgetStyleRepositoryTest` (fakes re-declare the method under test).

---

## F. FRAGILE hotspots (the "refactor breaks ~100 tests" driver)

47 files flagged FRAGILE. Systemic, fixable with shared fixtures (plan Phase 6):

| Pattern | Scale | Where |
|---|---|---|
| Hand-wired `MultiCurrencyRepository` 11-arg graph | ~9 files — one ctor change breaks ~10 goldens | `golden/` |
| `TransactionLifecycleCoordinator` 14-arg relaxed MockK | 6 files; relaxed `DatabaseWriteBarrier` means the barrier is never exercised on these paths | `scenarios/` |
| `NotificationProcessingPipeline` 29-arg ctor | triplicated | `data/repository/` |
| ViewModel ctors 12–20 args | Analytics 18 args ×3 files, Home 20, Debug 16, SpendingMap 12 | `ui/` |
| Worker-guard mirrored in stubs instead of the real guard | 3 fidelity tiers (faithful / partial / blanket-mock) — guard semantics can drift undetected; no shared fixture exists; `FakeNotificationPermissionChecker` defined twice | `service/`, `data/location/` |
| Reflection into private fields | AssistantVM, SharedExpenseGroupsVM, ReviewVM stress, ReceiptScanVM stress, `RecommendationLifecycleManager`, `P7BugFixes`, `ForecastInputAssembler` | various |
| `TimeZone.setDefault` / `Locale` without the lock | `TimePeriodUtilsStressTest` + `TimePeriodUtilsTest` not under `GlobalTimeZoneTestLock` (11 other files are); `AmountUtilsStressTest` leaks `Locale.GERMANY` on failure | `domain/util/` |
| SRCTEXT residue | 3 known offenders deleted; 1 remains in `DataRetentionWorkerTest` (CWD-relative source read) | `data/location/` |

---

## G. Coverage gaps (systemic)

**Coordinator/legal-path gaps (P0):**
- `HybridRouter` (the AID-4 consolidated routing seam) has **zero** direct tests repo-wide; execution wrapper only indirectly covered.
- Master golden scenarios missing: #4 email-receipt `LinkedExisting`, #6 coordinator-level rule deactivation, #8 DO_NOT_STORE end-to-end behavior (existing `golden/PrivacyDoNotStoreTest` is a data-class copy tautology + self-fulfilling DAO roundtrips).
- No test in e2e/scenarios/golden routes expense creation through the real `TransactionLifecycleCoordinator` end-to-end; `NotificationProcessingPipeline`, `ReceiptLifecycleCoordinator.processReceiptInput`, `ReceiptLinkService`, `RecurringRuleLifecycleCoordinator` never run as real pipelines in the anchor sets (goldens hand-write what coordinators would write).
- `RecurringOccurrenceExpander` / `OccurrenceConflictResolver` have no dedicated unit tests anywhere; `deactivateRule` / `linkExpenseToOccurrence` / occurrence-claim atomicity lack unit-level coverage (only DB-contract/golden fragments).
- `PrivacyBlocked` / `toPrivacyBlocked` mapping: zero tests repo-wide; `PrivacyDecision.blocksExecution()/reason()` has no exhaustive variant test despite 39+ callers.

**Silently unowned behavior (coverage that exists only in `@Ignore`d files or not at all):**
- `NotificationRepository` and `ReceiptRepository` have **no live repository-level tests** — unique behaviors (source-stats decrement, OCR-failure fallback, parse-failure raw-text preservation, deleteReceipt asset removal) are stranded inside ignored stress tests.
- `SecureKeyStorage`: entire class `@Ignore`d (17 tests) — zero active coverage of API-key storage (P0 hole).
- `BudgetViewModel`, `HomeViewModel`, `MainViewModel`, `ReviewViewModel`, `ReceiptScanViewModel`, `TransactionsViewModel` have **zero CI-active tests** (only class-ignored stress files).
- `ReviewPriorityScorer`, `DetectSemanticDuplicateUseCase`, `StrictAiJsonParsing`, `DashboardBriefingPromptFormatter`, `WidgetStyleRepositoryImpl`, `WorkerDrainController`, `WorkerReasonCodes` — no dedicated tests.
- `ExpenseDao` itself has no executable JVM boundary test (documented `<=` vs `<` inconsistency never executed); `RawNotificationDao` purge untested; `AccountingExportPolicy.validateGlobalDataset()` untested; `CostbackupBundle` lacks wrong-password/tamper fail-closed tests; `BackupVerifier` Tier 2/3 semantics untested.
- `computeNormalized` overloads (heatmap/insights) untested anywhere; `DataQualityReport` zero references; no mixed-currency analytics unit fixture exists (all EUR-only — the exact blind spot behind F-07 drift).
- CSV v2 **writer** side untested (import only); anonymized-export restorability untested; `CategorizeReceiptItemsUseCase` covered only on its failure path.

**CI/infra reality (affects what "covered" means):**
- `connectedDebugAndroidTest` runs only on main/master pushes or manual dispatch, both `continue-on-error` — the 28 androidTest files (incl. 24 DAO tests) are effectively manual-only; JVM twins are the CI-effective side.
- The dedicated golden CI gate prescribed by `docs/testing/GOLDEN_TESTS_CI_GATE.md` was **never added**; goldens run only inside the monolithic unit-test task, and the doc drifts (13 goldens documented, 16 exist + 5 e2e JSONs).
- The ledger never measured `golden/`, `scenarios/`, `e2e/`, `ui/` packages — "passing" is unproven for roughly a third of the suite.
- `DatabaseMigrationTest` still contains ~50 single-step tests over 135 unregistered dead `MIGRATION_*` vals, and ~9 tests silently skip (`assumeTrue`) because schemas <33 don't exist.
- Docs drift found: CODEBASE_SEGMENTS says 8-state journal/8 modes vs production 9/11 (LEGAL_PATHS is right); `ValidateBankStatementTransactionsUseCaseTest` KDoc claims six untested gaps that are covered.

---

## H. Ledger cross-reference (F-01…F-21 status after static review)

| Family | Status after audit |
|---|---|
| F-01 BankStatementParser | Likely resolved in-tree (`flowOf` at test:33) — confirm by run |
| F-02 lifecycle UncompletedCoroutinesError | OPEN; shared harness suspect; fix harness first (§B) |
| F-03 BudgetMonitor | Root-caused: `DeliveryResult.DELIVERED` not stubbed |
| F-04/F-17 DataStore collisions | Root-caused: same-file reuse in 2 files |
| F-05 TaxEstimator | Mirror-formula drift; rewrite expectations |
| F-06 BillReminderManager | Stale (legacy `markBillPaid` removed) — re-home 2 real tests |
| F-07 analytics/forecast drift | EUR-only fixtures + mirror expectations; mixed-currency fixture + independent constants needed |
| F-08/F-13 ReviewQueue/Recurring ClassCastException | Root-caused: relaxed mock on sealed return + `as` cast (`ReviewQueueRepository.kt:627`) |
| F-09/F-14 barrier exceptions | Statically resolved (typed exception asserted) — confirm by run |
| F-10/F-15 MigrationRegistration | Statically resolved (schema JSONs present + sourceSet wired) — confirm by run |
| F-11/F-19 CloudDashboardBriefing | Root-caused: 2-arg ctor installs fail-closed gate |
| F-12 misc | Per-file verdicts in batches |
| F-16 ExportReadBarrier coroutine error | Root-caused: nested `runTest` in `assertThrows` (test lines 119-121) |
| F-18 MaintenanceOperationRunner | Root-caused: stale `PROCEED_WITH_WARNING` expectation vs `FAIL_OPERATION` default |
| F-20 MerchantKeyBackfill | Decision needed: pin new normalization or fix prod |
| F-21 misc singles | EmailReceiptParser UTC pinning (batch 12); ExchangeRateStoreAdapter validDate (batch 10); others per-file |

---

## I. Prior-audit (2026-05) reconciliation

The stale audit remains useful but required heavy correction — evidence that a standing re-verification process (plan Phase 7) is needed:

- **~20 verdicts overturned** (examples: `CategoryRepositoryTest` DELETE→KEEP; `BankConnectionsViewModelTest` DELETE→STRENGTHEN; `MerchantNormalizerStressTest`/parser "stress" NIGHTLY→fast functional KEEP; `CompositeGeocodingServiceStressTest` NIGHTLY→un-ignore (it's fast behavioral, and it's the *only* coverage of geocoding coordination); `GoldenDataSets` "wide usage"→1 consumer; `MultiCurrencyTestFixture` KEEP→orphan).
- **Already actioned since 202-05**: `ConcurrencyStateRaceTest`, `TransactionRollbackTest`, `PrivacyGateEnforcementScenarioTest`, `TravelDetectionEngineTest`, `FlowTestUtils`, `DebugScreenTextTest`, SRCTEXT UI trio, `NotificationCaptureServiceStressTest`, `BudgetForecastingEngineStubTest` — all confirmed gone.
- **Skeleton claims now false**: `BackgroundJobRunDaoTest`, `InvestmentDaoTest`, `PrivacyAuditDaoTest`, `RawNotificationDaoTest` are real Room tests today.
- Suite grew 489 → 675 files without a matching re-audit; the failure ledger never covered golden/scenarios/e2e/ui.
