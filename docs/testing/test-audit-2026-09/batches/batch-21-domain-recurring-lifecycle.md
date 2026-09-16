# Batch 21 — domain receipt + lifecycle, receiptmatching, recurring, reminder, savings, sideeffect, split, subscription, tax, transaction lifecycle

Scope: Segments 4 (Receipt OCR & Lifecycle), 38 (Receipt Matching), 7 (Recurring), 36 (Bill Reminders), 23/35 (Savings), 21 (Split), 34 (Subscription), 17 (Tax), transaction lifecycle (Segments 3/7 boundary) · Files: 35 · LOC: 8,392 · Tests: 393 · @Ignore: 4

Reviewer notes: LIFECYCLE = STRICT MODE P0 per AGENTS.md. This batch holds the real lifecycle unit tests for the LEGAL_PATHS.md coordinators (ReceiptLifecycleCoordinator, RecurringLifecycleCoordinator.generateOccurrences/projectOccurrences, TransactionLifecycleCoordinator). Batch 01 found `golden/Pipeline4LifecycleGoldenTest` bypasses legal paths and statically cannot pass; the files here are therefore the load-bearing lifecycle coverage, complementary to (not duplicative of) the DB-backed scenario contract tests (`scenarios/ReceiptLifecycleDbContractTest`, `scenarios/TransactionLifecycleCoordinatorDbContractTest` — batch 08, the good ones) and goldens (`golden/ConcurrentOccurrenceClaimTest`, `golden/ReceiptMatchingNoDoubleCountGoldenTest`). Ledger families in-batch: F-01, F-02, F-05, F-06; F-17 root cause owned by batch 13. All lifecycle-verdict files are currently RED per the ledger; verdicts assume the failures are fixed, per family notes below.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 18 | 10 | 0 | 3 | 4 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/receipt/BankStatementParserTest.kt | 537 | 19 | 0 | PURE | BankStatementParser | KEEP | P0 | ReceiptRepositoryStatementDuplicateTest, ReceiptRepositoryStressTest | F-01; flowOf fix present, see findings |
| 2 | test/…/domain/receipt/BitmapConcurrencyTest.kt | 356 | 13 | 0 | PURE (stdlib) | none (kotlinx Mutex) | DELETE | P4 | — | Tests kotlinx library, zero prod code |
| 3 | test/…/domain/receipt/EnhancedMerchantExtractorTest.kt | 101 | 4 | 0 | MOCKED | EnhancedMerchantExtractor | KEEP | P2 | — | Real extractor, asserts source+confidence |
| 4 | test/…/domain/receipt/GreekNormalizationTest.kt | 65 | 5 | 0 | PURE | ReceiptParser.normalizeGreekOcr | STRENGTHEN | P1 | #7, #8 | Reflection into private method (FRAGILE) |
| 5 | test/…/domain/receipt/OcrLanguageProcessorTest.kt | 136 | 13 | 0 | PURE | OcrLanguageProcessor | KEEP | P1 | — | Exhaustive locale amount extraction |
| 6 | test/…/domain/receipt/ReceiptOcrTempNameTest.kt | 75 | 4 | 0 | PURE | uniqueTempFileName (ReceiptOcrService) | KEEP | P3 | ReceiptRepositoryStressTest | Trivial but guards UUID-uniqueness contract |
| 7 | test/…/domain/receipt/ReceiptParserOcrPatternsTest.kt | 761 | 59 | 0 | PURE | ReceiptParser | KEEP | P0 | #4, #8 | 59 Greek/OCR total-extraction cases, fixed clock |
| 8 | test/…/domain/receipt/ReceiptParserTest.kt | 272 | 20 | 0 | PURE | ReceiptParser | KEEP | P0 | #4, #7 | Complementary: hallucination map, dates, decimals |
| 9 | test/…/domain/receipt/WarrantyTextExtractorTest.kt | 251 | 11 | 0 | PURE | WarrantyTextExtractor | STRENGTHEN | P2 | — | Uses LocalDate.now() clock (midnight flake risk) |
| 10 | test/…/domain/receipt/lifecycle/ReceiptLifecycleBugFixesTest.kt | 9 | 0 | 1 | FIXTURE (husk) | — | DELETE | P4 | — | Empty class-level @Ignore, APIs removed |
| 11 | test/…/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt | 679 | 14 | 0 | MOCKED | ReceiptLifecycleCoordinator | STRENGTHEN | P0 | batch-08 DB-contract, EmailReceiptIngestionServiceTest, PrivacyBehavioralRegressionTest | F-02 RED; FRAGILE 28-dep ctor; see findings |
| 12 | test/…/domain/receipt/lifecycle/ReceiptLifecycleHardeningTest.kt | 9 | 0 | 1 | FIXTURE (husk) | — | DELETE | P4 | — | Empty class-level @Ignore, APIs removed |
| 13 | test/…/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt | 231 | 9 | 0 | ROOM | ReceiptMatchLifecycleService | KEEP | P0 | ReceiptMatchingWorkerTest, ScannedReceiptClaimTest | Real Room; reason-code sanitization asserted |
| 14 | test/…/domain/receiptmatching/ReceiptTransactionMatcherTest.kt | 103 | 2 | 0 | MOCKED | ReceiptTransactionMatcher | STRENGTHEN | P1 | ReceiptMatchingNoDoubleCountGoldenTest, ReceiptMatchingE2ETest, ReceiptMatchingWorkerTest | Only 2 cases; no date-window/amount-tolerance |
| 15 | test/…/domain/recurring/RecurringLifecycleFixesTest.kt | 9 | 0 | 1 | FIXTURE (husk) | — | DELETE | P4 | — | Empty class-level @Ignore, APIs removed |
| 16 | test/…/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt | 302 | 5 | 0 | MOCKED | RecurringLifecycleCoordinator | STRENGTHEN | P0 | RecurringPaymentMatchE2ETest, ConcurrentOccurrenceClaimTest, batch-08 contract tests | FakeDomainTransactionRunner good; see findings |
| 17 | test/…/domain/reminder/BillReminderManagerTest.kt | 143 | 5 | 0 | MOCKED | BillReminderManager | REWRITE | P1 | BillRemindersViewModelTest, RecurringArchitectureGuardTest | F-06; 3 tests call removed markBillPaid |
| 18 | test/…/domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt | 84 | 1 | 0 | MOCKED | AutomatedSavingsRuleEngine | KEEP | P0 | #19 | Golden 17.30→2.70 math; clean DataStore isolation |
| 19 | test/…/domain/savings/AutomatedSavingsRuleEngineTest.kt | 289 | 8 | 0 | MOCKED | AutomatedSavingsRuleEngine | KEEP | P0 | #18 | State persists across engine recreation; caps/idempotency |
| 20 | test/…/domain/savings/SavingsGamificationEngineTest.kt | 242 | 7 | 0 | MOCKED | SavingsGamificationEngine | KEEP | P2 | SavingsGoalsViewModelTest | Real history repo; honest-zero legacy contract |
| 21 | test/…/domain/savings/SmartSavingsEngineTest.kt | 315 | 7 | 0 | MOCKED | SmartSavingsEngine | KEEP | P1 | SavingsGoalsViewModelTest, GoldenMasterVerificationTest | Weighted formula asserted with documented math |
| 22 | test/…/domain/sideeffect/DiagnosticSideEffectEventWriterTest.kt | 202 | 15 | 0 | MOCKED | DiagnosticSideEffectEventWriter | KEEP | P2 | DirectEventDaoInsertGuardTest | Note: asserts raw e.message carried (privacy) |
| 23 | test/…/domain/sideeffect/PostCommitActionBatchTest.kt | 93 | 7 | 0 | PURE | PostCommitActionBatch | KEEP | P3 | — | Idempotency-key dedup semantics |
| 24 | test/…/domain/sideeffect/PostCommitActionRunnerExtensionsTest.kt | 101 | 4 | 0 | MOCKED | runBestEffortAfterCommit ext | STRENGTHEN | P2 | #25 | Mock-verify heavy; cancellation contract is the value |
| 25 | test/…/domain/sideeffect/PostCommitActionRunnerTest.kt | 281 | 9 | 0 | MOCKED | PostCommitActionRunnerImpl | KEEP | P1 | CancellationPropagationContractTest | Real impl + fake writer; CancellationException rethrow |
| 26 | test/…/domain/sideeffect/SideEffectMetadataFactoryTest.kt | 145 | 11 | 0 | PURE | SideEffectMetadataFactory | KEEP | P2 | — | Asserts hashed idempotency key + no raw payloads |
| 27 | test/…/domain/sideeffect/TransactionSideEffectFailureEventWriterTest.kt | 118 | 4 | 0 | MOCKED | TransactionSideEffectFailureEventWriter | KEEP | P1 | TransactionContextProvenanceGuardTest | Deterministic clock; cancellation not swallowed |
| 28 | test/…/domain/split/SplitCalculationPrecisionTest.kt | 342 | 22 | 0 | PURE | (Money) — mirror of EnhancedSplitManager | REWRITE | P1 | VisualSplitViewModelTest | Test-local split mirror = tautology; see findings |
| 29 | test/…/domain/subscription/SubscriptionManagerEngineTest.kt | 451 | 21 | 0 | MOCKED | SubscriptionManagerEngine | STRENGTHEN | P1 | SubscriptionManagementViewModelTest | runBlocking-in-runTest hazard; some overlap in recordPriceChange tests |
| 30 | test/…/domain/tax/TaxCalculationTest.kt | 433 | 35 | 1 | PURE | GreeceTaxConfiguration/UsTaxConfiguration/TaxConfigurationFactory | REWRITE | P2 | TaxGoldenScenarioTest | Tax math is test-local mirror; @Ignore hides broken helper |
| 31 | test/…/domain/tax/TaxEstimatorTest.kt | 425 | 18 | 0 | MOCKED | TaxEstimator | STRENGTHEN | P1 | TaxGoldenScenarioTest (scenarios) | F-05 drift; expected computed by mirrored formula |
| 32 | test/…/domain/transaction/category/CategoryAssignmentServiceBarrierTest.kt | 157 | 3 | 0 | MOCKED | DefaultExpenseCategoryAssignmentService | STRENGTHEN | P1 | CancellationSafetyArchitectureGuardTest | Test 1 real fail-closed; tests 2–3 tautological |
| 33 | test/…/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt | 267 | 8 | 0 | MOCKED | TransactionLifecycleCoordinator | STRENGTHEN | P0 | batch-08 TransactionLifecycleCoordinatorDbContractTest, CancellationPropagationContractTest, Pipeline4LifecycleGoldenTest | F-02 RED; FRAGILE 15-dep ctor; see findings |
| 34 | test/…/domain/transaction/lifecycle/TransactionLifecycleCoordinatorUpdateTest.kt | 120 | 1 | 0 | MOCKED | TransactionLifecycleCoordinator | KEEP | P0 | batch-08 contract tests | F-02 family; stale baseAmount cleared on conv failure |
| 35 | test/…/domain/transaction/lifecycle/TransactionSideEffectPlannerTest.kt | 288 | 29 | 0 | PURE | TransactionSideEffectPlanner, SourceLearningPolicy | KEEP | P1 | DirectEventDaoInsertGuardTest | Trigger/idempotency/source-trust invariants, no mocks of logic |

## Findings (noteworthy files only)

### test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt — F-01
- 19 spatial-block statement parsing tests, all real assertions on amounts/merchants/types (NBG Χ/Π markers, Revolut locale amounts, transaction-amount-vs-balance, header date order).
- F-01 measured 19 failures with `NoSuchElementException` in `@Before` line 34 at ledger HEAD `43ca2228` (ledger verdict: `mockk<Flow>()`+`coEvery{first()}` antipattern). Current source line 33 already stubs `homeCurrency() → flowOf("EUR")` — exactly the ledger's recommended fix; production only reads `homeCurrency().first()` (BankStatementParser.kt:114). The current `@Before` line 34 is a harmless `resolveHomeCurrency()` stub.
- Static conclusion: F-01 root cause appears already fixed in current source; the ledger likely measured the pre-fix state. Needs one targeted `--tests "*BankStatementParserTest*"` run to confirm green (not run here — static review).
- Action: KEEP P0; confirm F-01 closure, do not re-fix.

### test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt — F-02
- 14 tests on the legal receipt path (LEGAL_PATHS.md:67-91): validation fail-closed, NeedsReview for low-confidence/incomplete email parses (reason asserted), messageId-conflict → Duplicate, write-barrier → Error (fail-closed), post-commit CancellationException rethrow vs best-effort failure isolation.
- In F-02: measured RED (UncompletedCoroutinesError after 1m). Static review cannot reproduce the hang; both this and #33 use `runTest` over all-mocked infra, so the shared-coroutine-harness leak is the likely culprit (ledger: NEEDS INVESTIGATION, HIGH RISK).
- FRAGILE: constructor takes ~28 dependencies (lines 119-149), almost all relaxed mocks — every coordinator DI change breaks this file (the "refactors break ~100 tests" pain).
- Some tests are verify-only weak (`email_uses_email_storage_mode_not_ocr_mode` asserts only `getSettings()` was called); strengthen to assert raw email body is NOT persisted under DO_NOT_STORE.
- Action: keep; fix F-02 harness first, then tighten the two verify-only tests. Not a rewrite.

### test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt + TransactionLifecycleCoordinatorUpdateTest.kt — F-02
- Coordinator tests assert real sealed results (Created id=42, ValidationFailed for negative/blank/bad-currency with zero DAO writes) and the post-commit contract triad: CancellationException rethrows (updateType/updateExpense), non-cancellation runner failure does NOT roll back the committed update, deleteExpense surfaces CancellationException as result failure. UpdateTest is a focused P2-PR1 regression: conversion failure clears stale baseAmount/baseCurrency/exchangeRateUsed (captured slot, real values asserted).
- Both RED per F-02 (Transaction half of the 16). Same harness caveat as #11.
- 2026-05 stale verdict ("UPDATE mock expectations") appears already addressed — tests now assert result types, not outdated event expectations. Overturned.
- Complementary to batch 08's DB-backed `TransactionLifecycleCoordinatorDbContractTest` (mocked unit vs real-Room contract — keep both). `Pipeline4LifecycleGoldenTest` (batch 01, broken/bypassing) must not be treated as covering these behaviors.

### test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt
- Legal path coverage (LEGAL_PATHS.md:152): expand→resolve→materialize and the read-only `projectOccurrences` (asserts zero materializer/DAO/event/barrier writes — excellent no-side-effect invariant for a projection). Uses `FakeDomainTransactionRunner` instead of mocking the transaction runner — good pattern worth copying.
- Weakness: `generateOccurrences` result counts are stubbed echoes (materializer mocked returns created=1, test asserts created==1) — mock-verification-only for the count; and no unit coverage at all here for deactivateRule/updateRule/linkExpenseToOccurrence/occurrence claim atomicity (claim atomicity lives in golden/ConcurrentOccurrenceClaimTest, out of batch).
- Action: STRENGTHEN — assert materializer received the resolved candidates rather than echoing counts; add occurrence-claim unit case or formally reference the golden.

### test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt — F-06
- 3 of 5 tests call `markBillPaid`, which production now hard-removes: BillReminderManager.kt:155-156 `error("Legacy markBillPaid removed. Create an actual expense and use linkExpenseToOccurrence(expenseId).")` — these can never pass (IllegalStateException). The `@Suppress("DEPRECATION_ERROR")` header confirms known-stale status.
- The other 2 tests (getMonthlyBillsTotal frequency normalization via RecurrenceCalculator; getUpcomingReminders urgency mapping CRITICAL/URGENT/WARNING/INFO) are real and passing — keep.
- Action: REWRITE — replace markBillPaid tests with the new contract (expense creation → linkExpenseToOccurrence advances schedule); ledger flags this as intentional prod change (P4-stale), do not "fix" production back.

### test/java/com/yourname/expensetracker/domain/split/SplitCalculationPrecisionTest.kt
- All 22 tests run against private helpers that REIMPLEMENT the split algorithm in the test file (`calculateEqualSplit` "Mirrors the logic in EnhancedSplitManager", line 307-325). A regression in the real EnhancedSplitManager cannot fail this test — tautological for splits. Only the underlying `Money.divide/sum/percentage/plus/isZero` arithmetic is real production code.
- 2026-05 verdict KEEP P1 ("uses real Money class") overturned: the split contract itself is untested here.
- Action: REWRITE — point the same boundary cases (100/3, 0.01/2, 999999.99/7, negative, 100 participants) at EnhancedSplitManager (or whatever production split calculator owns rounding); the money-precision intent is P1 and worth preserving.

### test/java/com/yourname/expensetracker/domain/tax/TaxCalculationTest.kt and TaxEstimatorTest.kt — F-05
- TaxCalculationTest: config/factory/rate/bracket-shape tests are real (Greece 24%/9-22-32 brackets, US 0%/10-12, factory defaults). But `calculateVat`/`calculateProgressiveTax` are test-local reimplementations — the headline "tax accuracy" tests prove nothing about production math. The `extractVat` helper is itself wrong (integer `divide(divisor.toDouble().toInt())` → divide by 1), which is what the lone `@Ignore` hides.
- TaxEstimatorTest: excellent business-only VAT scope, filing-currency warnings, Uncategorized-merge regression — but `progressiveTax`/`periodYearFraction` helpers mirror the estimator's own B.8 algorithm, so F-05's 6 assertion-drift failures are the predictable cost: expected values must be hand-updated whenever prod normalization changes. Recompute expected values as independent constants (not the same formula) to stop the drift.

### test/java/com/yourname/expensetracker/domain/transaction/category/CategoryAssignmentServiceBarrierTest.kt
- Test 1 is genuinely valuable: write barrier blocked → CategoryAssignmentOutcome.Failed, zero DAO/event writes (fail-closed, LEGAL_PATHS-consistent).
- Test 2 asserts only that the `BULK_DELETED` enum exists and a TransactionEvent data class can be constructed — no production code invoked. Test 3 reflects over the DAO method name and then `coVerify(exactly=0)` on a mock nothing ever called — tautology. Action: delete or replace tests 2-3 with a real bulk-delete/bulk-merchant-update path test.

### Deleted-husk files (P4)
- `ReceiptLifecycleBugFixesTest.kt`, `ReceiptLifecycleHardeningTest.kt`, `RecurringLifecycleFixesTest.kt`: 9 LOC each, class-level `@Ignore`, zero tests, referencing removed APIs. Inflating @Ignore counts (a CI guardrail target per MASTER_TESTING_STRATEGY Phase 5). DELETE; the intended coverage (receipt hardening, recurring fixes) is partially re-homed in #11/#16 — rewrite there when domain stabilizes.

## Area gaps (what is NOT tested in this area)

- RecurringOccurrenceExpander and OccurrenceConflictResolver have NO dedicated unit tests anywhere (the batch plan's "expander, conflict resolver" files do not exist; only mocked usage in #16 and e2e). The batch-plan husk RecurringLifecycleFixesTest was likely their intended home. Expander date math and conflict dedup rules are money-adjacent P0 logic tested only incidentally.
- RecurringLifecycleCoordinator legal operations updateRule/deactivateRule/linkExpenseToOccurrenceDetailed/updateOccurrenceStatus (LEGAL_PATHS.md:134-176) have no unit coverage in this batch — only goldens/scenarios.
- ReceiptTransactionMatcher: no unit tests for amount tolerance, date-window edges, already-matched receipts, or currency mismatch — only DEPOSIT-exclusion and Greek normalization.
- ReceiptLifecycleCoordinator camera path: no duplicate-detected outcome test (dedupe always stubbed non-duplicate) and no OCR raw-text storage-mode behavioral test in this file (privacy path covered by PrivacyBehavioralRegressionTest — acceptable but scattered).
- BillReminderManager: no unit test of the post-legacy reminder contract (claim-based dispatch, linkExpenseToOccurrence) after F-06 removal.
- Split: no test anywhere exercises EnhancedSplitManager production logic at unit level (VisualSplitViewModelTest is ViewModel-level).
- Side effects: no in-batch runtime test that side effects are deferred until after `withTransaction` commits (the P11 comment in #11 line 359-364 admits ordering is "covered by static review, not by this unit test"); relies on static SideEffectContract guards.

## Rollup

- Verdict counts: KEEP 18 · STRENGTHEN 10 · MERGE 0 · REWRITE 3 · DELETE 4 · NIGHTLY 0 · UNKNOWN 0 (35 files).
- P0 files: 9 (#1, #7, #8, #11, #13, #16, #18, #19, #33, #34 — of which #33/#34 and #11 are RED via F-02).
- DUP pairs: 0 — all same-class overlaps (#7/#8, #18/#19, batch-08 DB-contract tests, goldens) are complementary (unit vs DB-backed vs golden vs scenario).
- P4 (negative/dead) files: BitmapConcurrencyTest, ReceiptLifecycleBugFixesTest, ReceiptLifecycleHardeningTest, RecurringLifecycleFixesTest.
- FRAGILE count: 5 (#4 reflection, #11 28-dep ctor, #32 reflection, #33 15-dep ctor, #34 15-dep ctor).
- Ledger families cited: F-01 (fix appears applied in source; confirm by run), F-02 (RED, shared-coroutine-harness root cause undecidable statically — the one thing this batch could not settle), F-05 (assertion drift via mirrored formula), F-06 (stale removed-API tests → REWRITE), F-17 (root cause owned by batch 13; in-batch savings files already use unique temp DataStores + scope cancel and look clean).
- Stale 2026-05 verdicts overturned: 2 (BitmapConcurrencyTest KEEP→DELETE; SplitCalculationPrecisionTest KEEP→REWRITE); TransactionLifecycleCoordinatorTest "UPDATE mock expectations" issue verified already addressed.
