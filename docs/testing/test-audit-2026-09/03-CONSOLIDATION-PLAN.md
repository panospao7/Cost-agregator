# Test Suite Audit 2026-09 — Consolidation & Reduction Plan

**Goal:** reduce test volume and ambiguity, cut the "refactor breaks ~100 tests" failure surface, and make remaining tests trustworthy — **without losing real coverage**.
**Inputs:** [02-FINDINGS.md](02-FINDINGS.md) · per-file verdicts in [01-INVENTORY.md](01-INVENTORY.md) · batch evidence in [`batches/`](batches/).
**Rules honored (AGENTS.md):** no destructive shortcuts; strict areas (workers, privacy/security/AI/export/backup, Room, money, transaction/receipt/recurring lifecycle) get targeted tests + strict review; one Gradle owner at a time; docs never marked done before gates pass. **No code has been changed yet — every phase below is a proposal awaiting execution.**

## Guiding principles

1. **Nothing merges or dies before its unique content is ported** — every MERGE/DELETE verdict names what must survive.
2. **One concern, one owner:** each invariant lives in exactly one layer (unit / DB-contract / golden / guard-script). The current suite has up to three copies (§D.3 of findings).
3. **Fix mirrors, not assertions:** wrong-object tests (test-local reimplementations) get rewritten against production, never "fixed" by loosening.
4. **Baseline before touching:** every phase starts from a measured green/red census so improvement is provable.

---

## Phase 0 — Baseline & ledger refresh (prerequisite, no test edits)

- **Owner:** compile-owner agent only (`tester-runtime`/`ci-build-debugger`), per Gradle coordination rules.
- Run `:app:testDebugUnitTest` per package batch (the ledger's fork-instability mitigation: small filters, log to file). Record a green/red/ignored census per package into `TEST_FAILURE_LEDGER.md`, closing families F-01/F-09/F-14/F-15 if they pass (static review says they should).
- Verify the predicted-failing list (findings §B) — each confirmation/correction updates its verdict in this audit.
- **Exit criteria:** measured census exists for all packages incl. `golden/`, `scenarios/`, `e2e/`, `ui/` (never measured before).
- ⚠ Ask before running expensive Gradle commands (AGENTS.md).

## Phase 1 — Zero-risk deletion PR (~25 files, ~2.6k LOC)

Delete the finding-§C list. All are tautologies, stdlib tests, removed-API husks, dead fixtures, or stale ignored stress files that would fail if un-ignored. Every file has a named reason; none removes unique live coverage.
- Also remove dead fixture members found in batch 02 (`ScenarioSeeder.feedInputs`, `ScenarioSeed.NotificationInput`, `TestFixtures.asReadableDate`, `STANDARD_CATEGORIES`) and the in-file dead weight noted in batch reports (e.g. `NotificationIntakeWorkerTimeoutTest` dead helper ~60 LOC).
- Flag `ProcessReceiptUseCase` (zero production references) to a production-cleanup PR — separate decision, not part of this PR.
- **Validation:** `./gradlew :app:compileDebugUnitTestKotlin` + full `testDebugUnitTest` census unchanged except removed classes. Architecture guards must stay green (several scan test sources).
- **Risk:** low. **Gate:** fast review.

## Phase 2 — Merge consolidation (61 MERGE files, cluster by cluster)

Work the findings-§D clusters in this order; each cluster is its own PR with "port first, delete second" diffs:

1. Verbatim/subset same-class pairs (lowest risk): `SplitCalculatorGoldenTest`, `GroupLifecycleContractTest`, `ReviewQueueRepositoryStressTest`, `AppParserRegistryTest`, `MerchantNormalizerStressTest`, `MerchantKeyGeneratorStressTest`, `ExportReadBarrierTest`→`DatabaseBarrierTest`.
2. Barrier-golden dedup: fold `golden/RestoreBlocksAllWritesTest` + `WorkerRestoreBarrierIdempotencyGoldenTest` into `DatabaseBarrierTest` coverage (or rewrite as real-mode integration goldens — see Phase 5).
3. e2e/integration → golden repackaging: move the 5 real `GoldenTestBase` e2e tests into `golden/`; delete mock-only pipelines; carry `RecurringPaymentMatchE2ETest`'s coordinator-level flow into the recurring golden; salvage the classifier `RULE_MATCH` case from `NotificationExpenseDashboardPipelineTest` before deleting.
4. Money scenarios: port the unique `MoneyAmount.plus` cross-currency throw test to `domain/core/money/`, then delete/rewrite the decorative-seeding trio. Optionally move `scenarios/MoneyAggregateBuilderTest` → `domain/core/money/` (2026-05 recommendation, still valid; note package moves may touch guard allowlists — check `config/guards/*.yml`).
5. Deprecated-path removals: carry TravelDetection trip-gap/determinism tests into `TravelDetectionEngineNormalizedTest`, then delete both deprecated-path stress files.
6. Privacy-file consolidation: `RawStoragePolicyAuditTest`→`RawStorageEndToEndTest` (port resolver matrix first); fold `RetentionRegistryTest` real bits; drop `UPR5CompletionTest` dups.
7. Contract tests superseded by guards: delete `CancellationPropagation`/`LifecycleBarrier`/`Money` contract tests **only after** Phase 7 confirms the script/guard owners are wired in CI.
- **Validation per PR:** targeted `--tests` run of both survivor and merged classes. **Gate:** standard review; strict review for anything touching backup/privacy files.

## Phase 3 — Stale-test repair (F-families + 35 REWRITE verdicts)

Batch by mechanism, one targeted run per batch:

1. **Mock-contract fixes** (mechanical): stub `DeliveryResult.DELIVERED` (F-03); allowed-gate ctor for `CloudDashboardBriefingServiceTest` (F-11/F-19); strict coordinator mocks for `ReviewQueueRepositoryTest` (F-08/F-13); DataStore isolation in `ExpenseCategoryClassifierTest` (F-04) and `AutomatedSavingsRuleStateRepositoryTest` (F-17).
2. **Removed-API re-homes:** `BillReminderManagerTest` (F-06) → linkExpenseToOccurrence contract.
3. **Fixture regeneration:** `StaleRateCurrencyConversionGoldenTest` — regenerate JSON, human-review the diff (it's a behavior baseline change); decide F-20 merchantKey pinning deliberately.
4. **Shared-coroutine-harness fix** (F-02) — highest-risk item, strict review, do not patch individual tests first.
5. **Mirror rewrites (wrong-object tests):** `SplitCalculationPrecisionTest` → real `EnhancedSplitManager`; `TaxCalculationTest` VAT math → real configuration; `TaxEstimatorTest` expectations → independent constants (F-05); `AppOperationalStateTest` → real `toOperationalState`; `GoldenAnalyticsDatasetTest` → feed real engines or delete; strip `1==1`/reflection/stdlib assertions from `PrivacyBehavioralRegressionTest`, `P6BudgetCleanupTest`, `P5AnalyticsFixesTest`, `P7BugFixesTest`, `P9RemainingWorkerFixesTest`, etc.
6. **Guard hygiene:** renew `DirectEventDaoInsertGuardTest` allowlist (**expired 2026-08-15 — fails next run**); fix `RecurringArchitectureGuardTest` self-skipping golden scan path; add expiry enforcement for the two uncovered allowlists.
7. **Small repairs:** `HealthScoreGoldenTest` re-baseline (55 vs 57), `BudgetTrendBoundaryTest` to snapshot read path, `BankConnectionsViewModelTest` add `Dispatchers.setMain`, `EmailReceiptParserTest`/`UberReceiptParserTest` TZ strategy (F-21), `TimePeriodUtilsStressTest`/`AmountUtilsStressTest` tz/locale locks.

## Phase 4 — NIGHTLY lane (15 files) + un-ignoring with intent

- Create a `nightly` test task + scheduled workflow (TEST_IGNORE_CLASSIFICATION.md already sketched this). Move: `DaoStressTest`, `CategorizationEngineStressTest`, `SynthesisEngineStressTest`, `AnalyticsStressTest`, `SpendingHeatmapEngineStressTest` (after prune), `ExpenseEntityStressTest`, `DaoStress`-family, `ReviewViewModel/ReceiptScanViewModel/TransactionsViewModel` stress files (after fragile-harness fixes).
- **Counter-move:** `CompositeGeocodingServiceStressTest` is fast behavioral coverage, class-ignored — un-ignore INTO PR CI (it's the only geocoding-coordination coverage).
- Decide deliberately on ignored files with unique stranded coverage: `NotificationRepositoryStressTest`/`ReceiptRepositoryStressTest` (batch 14: REWRITE into live repository tests), `SecureKeyStorageTest` (P0 hole — live fake-prefs rewrite).
- **Exit criteria:** no `@Ignore` file without a classification (nightly / rewrite / delete) recorded in this audit's inventory.

## Phase 5 — Coverage-gap closing (P0 first)

Ordered by risk (findings §G):

1. **Golden scenario completion:** #4 email-receipt `LinkedExisting`, #6 coordinator-level rule deactivation, #8 DO_NOT_STORE behavior (replace the tautological golden). Rewrite `Pipeline4LifecycleGoldenTest` against the real coordinator.
2. **Coordinator-anchored goldens:** convert hand-written-side-effect goldens to run `NotificationProcessingPipeline` / `ReceiptLifecycleCoordinator` / `TransactionLifecycleCoordinator` for at least the 6 currently partial scenarios.
3. **Privacy seam tests:** `HybridRouter` direct execution-wrapper tests; `PrivacyBlocked`/`toPrivacyBlocked` exhaustive mapping; `PrivacyDecision` 4-variant exhaustiveness.
4. **Money/analytics:** one mixed-currency analytics unit fixture (F-07 blind spot); `MerchantNormalizer` key-derivation pinning (8 pipelines depend on it).
5. **Silently-unowned behavior:** revive `NotificationRepository`/`ReceiptRepository` live tests; `SecureKeyStorage`; `validateGlobalDataset()`; `CostbackupBundle` tamper fail-closed; CSV v2 writer; `ExpenseDao` JVM boundary test.
6. Keep scope honest: do NOT add ViewModel tests for every screen — follow MASTER_TESTING_STRATEGY "what not to test".

## Phase 6 — Anti-fragility fixtures (fixes the "refactor breaks 100 tests" pain)

Shared test infrastructure (test-only changes):

- `CoordinatorTestFactory` — single constructor point for `TransactionLifecycleCoordinator` (14-arg) and `NotificationProcessingPipeline` (29-arg) with **strict** mocks and a real `DatabaseWriteBarrier` by default.
- `GoldenGraphFactory` — single `MultiCurrencyRepository`+converter wiring for the ~9 hand-wired goldens.
- Shared guarded-worker harness wrapping the **real** `WorkerExecutionGuard` (kills the 3-fidelity-tier mirror drift; dedupes the twice-defined `FakeNotificationPermissionChecker`).
- ViewModel factory helpers + `Dispatchers.setMain` rule; adopt `GlobalTimeZoneTestLock` in the 2 unlocked tz files.
- **Exit criteria:** constructor signature change touches ≤2 fixture files instead of ~47.

## Phase 7 — Guard-layer single-ownership + process

- Per invariant, pick one owner (JVM guard test vs Python script); retire the orphaned third copy (`scripts/guards/*.kts` + `dao-access-check.kts` are wired nowhere — confirm with CI owner before removal; guard changes need explicit approval).
- Wire the golden CI gate the docs already promise; fix `GOLDEN_TESTS_CI_GATE.md` drift.
- Re-run this audit (scout + batched review) after Phases 1–3 complete; add a ratchet: no net growth of tautology/FRAGILE counts.
- Documentation corrections (journal 9-state, 11 modes, golden counts, stale KDocs) — small PR, batched.

---

## Decision points for the user

| # | Decision | Why it needs a human |
|---|---|---|
| 1 | Approve the 25-file deletion list (Phase 1) | destructive action policy |
| 2 | Regenerate the stale golden fixture (`StaleRateCurrencyConversionGoldenTest`) — changes a committed behavior baseline | baseline change |
| 3 | F-20: pin new merchantKey normalization, or change production? | product decision |
| 4 | Create the nightly Gradle lane + workflow (Phase 4) | new CI infrastructure |
| 5 | Health-score re-baseline (55 vs 57) — which is correct? | product decision |
| 6 | Guard-layer consolidation choices (Phase 7) incl. retiring orphan scripts | architecture-guard policy |

## Expected end state (after Phases 1–4)

| Metric | Now | Target |
|---|---|---|
| Test files | 675 | ~590 (−25 delete, −61 merge, +repairs/rewrites roughly balancing) |
| Negative-value files | 25 | 0 |
| Wrong-object (mirror) tests | 35 | 0 |
| Predicted-failing / stale | 15+ | 0 (each confirmed fixed or re-baselined) |
| Un-owned `@Ignore` files | 29 | 0 unclassified |
| FRAGILE files | 47 | ≤15 (via shared fixtures) |
| Duplication clusters | 9 | 0 (one owner each) |

Phases 5–7 then grow coverage where it is genuinely missing instead of where it is currently triplicated.
