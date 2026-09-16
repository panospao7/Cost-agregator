# Batch 22 — domain/usecase

Scope: application-layer orchestration use cases — budget status (Seg 2), Monte Carlo budget impact (Seg 2), dashboard widgets/money radar (Seg 10), expense categorization & dedup (Seg 9), financial forecast (Seg 1), receipt processing (Seg 4), savings prompt/sweep (Seg 23/35), warranty auto-create (Seg 34) · Files: 12 · LOC: 3005
Reviewer notes: Prior audit (2026-05) flagged "stub tests" under domain/usecase/savings — OVERTURNED: both production use cases are real (240/544 LOC) and both tests assert concrete computed money values. Stale TEST_FAILURE_REPORT listed AssertionErrors in 4 of these files; current pass/fail could not be re-measured (static review, no Gradle) — see notes. These are thin orchestration layers, so several tests verify input assembly into mocked engines rather than end-to-end outputs; that is the right unit boundary here and does not duplicate coordinator (batch 08) or golden scenarios.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 7 | 3 | 0 | 0 | 2 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/usecase/budget/CalculateBudgetStatusUseCaseTest.kt | 129 | 4 | 0 | MOCKED | CalculateBudgetStatusUseCase | STRENGTHEN | P2 | — | Passthrough asserted; getBudgetHealth/BUD-5 untested |
| 2 | test/…/domain/usecase/budget/GetMonteCarloBudgetImpactUseCaseTest.kt | 223 | 12 | 0 | PURE | GetMonteCarloBudgetImpactUseCase | KEEP | P0 | ComputeMoneyRadarUseCaseTest (complementary) | Exact threshold boundaries; exemplary |
| 3 | test/…/domain/usecase/dashboard/ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest.kt | 198 | 3 | 0 | MOCKED | ComputeDashboardWidgetsUseCase | KEEP | P1 | P5AnalyticsFixesTest, DashboardWidgetConsistencyTest (complementary) | FRAGILE: 14 mocked ctor deps |
| 4 | test/…/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt | 577 | 13 | 0 | ROBOLECTRIC | ComputeMoneyRadarUseCase | KEEP | P1 | GetMonteCarlo test, e2e pipeline (complementary) | Exact weighted scores; prior AssertionError unconfirmed |
| 5 | test/…/domain/usecase/dashboard/DashboardProjectionSafetyTest.kt | 105 | 6 | 0 | PURE | (none — local formula copies) | DELETE | P4 | MoneyBoundaryGuardTest (source guard) | Tautology: re-implements prod formulas locally |
| 6 | test/…/domain/usecase/expense/CategorizeExpenseUseCaseTest.kt | 112 | 4 | 0 | MOCKED | CategorizeExpenseUseCase | KEEP | P3 | CategorizationEngineTest (complementary) | Thin wiring; normalize-then-categorize order checked |
| 7 | test/…/domain/usecase/expense/DetectDuplicateExpenseUseCaseTest.kt | 230 | 5 | 0 | MOCKED | DetectDuplicateExpenseUseCase | STRENGTHEN | P1 | DuplicateDetectionPolicy tests (complementary) | KDoc lists own gaps; currency-aware dedup untested |
| 8 | test/…/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt | 439 | 6 | 0 | MOCKED | CalculateFinancialForecastUseCase, ForecastInputAssembler | KEEP | P1 | ForecastInputAssembler/SynthesisEngine tests (complementary) | Real assembler asserts; F-07-adjacent drift risk |
| 9 | test/…/domain/usecase/receipt/ProcessReceiptUseCaseHomeCurrencyTest.kt | 46 | 1 | 0 | MOCKED | ProcessReceiptUseCase | DELETE | P3 | — | Class has zero production references (dead) |
| 10 | test/…/domain/usecase/savings/LifestyleSavingsPromptUseCaseTest.kt | 292 | 9 | 0 | MOCKED | LifestyleSavingsPromptUseCase | KEEP | P2 | — | Overturns stale stub flag; real gating+uplift math |
| 11 | test/…/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt | 418 | 9 | 0 | MOCKED | MonthlySavingsSweepUseCase | KEEP | P0 | — | Money math: no double-count, NaN guard, caps |
| 12 | test/…/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCaseTest.kt | 236 | 8 | 0 | MOCKED | AutoCreateWarrantyFromReceiptUseCase, WarrantyTextExtractor | STRENGTHEN | P0 | ReceiptRepository tests, CancellationSafetyGuard (complementary) | PrivacyGate fail-closed + half-open end date untested |

## Findings (noteworthy files only)

### test/…/domain/usecase/dashboard/DashboardProjectionSafetyTest.kt
- Claims (line 8-10) to verify NEW-P5-002/NEW-P5-011 fixes in ComputeDashboardWidgetsUseCase, but never touches that class.
- Every test re-implements the guarded formula locally (lines 25, 36, 50-56, 67-73, 83-87, 97-101; comment at line 24 admits "this is the guarded formula from ComputeDashboardWidgetsUseCase") and asserts on the copy.
- Zero regression power: production formula can change/delete while this stays green — negative value (false confidence).
- Action: DELETE; replace with a real-input test driving `ComputeDashboardWidgetsUseCase.compute()` at daysElapsed=0 (the DaysRemaining boundary test already shows the harness pattern to copy).

### test/…/domain/usecase/receipt/ProcessReceiptUseCaseHomeCurrencyTest.kt
- Single verify-only test (`verify { receiptParser.parse(any(), homeCurrency = "USD") }`, line 44) for the P3-P1-07 wiring fix — reasonable in isolation.
- However `ProcessReceiptUseCase` has zero references in app/src/main outside its own file (grep verified): never injected, never called — dead code, receipt work goes through ReceiptLifecycleCoordinator per LEGAL_PATHS.
- Action: DELETE the test (and flag the production class as a pruning candidate). If the class is revived, keep a wiring test of this shape.

### test/…/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCaseTest.kt
- Strong on LEGAL_PATHS items: 70/40% bands (asserts ≥0.70 auto-create, [0.40,0.70) → PENDING_REVIEW draft), dedup pre-check + unique-conflict race (returnsMany + `-1L` insert → AlreadyExists), missing-date/malformed/empty failures, draft-promotion-instead-of-duplicate.
- GAP vs LEGAL_PATHS FORBIDDEN "Accessing receipt data without PrivacyGate check": `privacyGate = mockk(relaxed = true)` (line 38); the fail-closed branch (prod `AutoCreateWarrantyFromReceiptUseCase.kt:122-126` — CLOUD_AI_WARRANTY_EXTRACTION denied → Failure) is never exercised.
- GAP vs FORBIDDEN "inclusive end-date semantics": captured `Warranty.warrantyEndDate`/display notes never asserted; prod stores exclusive (half-open) end date + `getDisplayEndDate` (prod lines 198-220, W20). No test pins this.
- Minor: document-type gating branch (BANK_STATEMENT/OCR_FAILED skip, prod lines 78-92) untested; `recentDate()` uses `System.currentTimeMillis()` while timeProvider is `FakeTimeProvider(FIXED_NOW)` — mixed clocks.
- Also note (production, out of audit scope): `execute()` returns `Failure(e.message)` (prod line 144) — raw exception message risks violating the no-`e.message` privacy rule.
- Prior 2026-05 run showed 4 AssertionErrors here; re-run needed to confirm current status.

### test/…/domain/usecase/budget/CalculateBudgetStatusUseCaseTest.kt
- Only tests `invoke()`, which is `getBudgetStatuses().first()` wrapped in Result (prod `CalculateBudgetStatusUseCase.kt:15-21`) — asserting mocked data comes back is near-tautological; `Result.Error` path untested.
- The real logic — `getBudgetHealth()` with the BUD-5 rule "CRITICAL must not be counted as healthy" and overallStatus cascade (prod lines 23-46) — has no coverage here (grep: only BudgetRecommendationEngineTest mentions BudgetHealth, different scope).
- Action: add getBudgetHealth tests (BUD-5, cascade, empty list) and an error-path test; keep existing rows as smoke.

### test/…/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt
- Good orchestration coverage via real `ForecastInputAssembler` + slot capture on `SynthesisEngine.synthesize`: month-to-date PURCHASE-only/isNotMine-excluding total (80.0), planned priority preservation, early-month shared projection path (1200.0 not legacy ×3=600), cumulative history + goal-protection mapping, manual-over-detected recurring merge with confidence gate (0.85 kept, 0.60 dropped).
- Input-capture style means a broken SynthesisEngine would not be caught here — acceptable (engine has own tests) but note as boundary limitation.
- Assertions pin exact money values; stale ledger shows prior AssertionError and F-07 forecasting drift family nearby — needs a fresh run to confirm.

### test/…/domain/usecase/dashboard/ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest.kt
- Drives the real compute path with injected fixed TimeProvider; pins leap-year/month-end `daysRemaining` (Feb 28→1, Feb 29→0, Mar 31→0) — exactly the kind of calendar boundary that silently regresses.
- FRAGILE: constructor takes 14 dependencies, ~12 relaxed mocks (lines 112-127); any ctor change breaks this file (matches the "refactors break ~100 tests" pain).

## Area gaps (what is NOT tested in this area)

- `CalculateBudgetStatusUseCase.getBudgetHealth()` — BUD-5 CRITICAL-vs-healthy rule and overallStatus cascade have no unit test anywhere.
- Warranty: PrivacyGate fail-closed (cloud AI denied), half-open end-date storage/display semantics, document-type gating, and `createWarrantyForReview` for the no-existing-warranty insert branch.
- Duplicate detection: currency-aware dedup (€50 vs $50 must not match), exact ±window boundary, ReceiptDuplicateDetector integration (gaps self-documented in the test's KDoc).
- `ComputeDashboardWidgetsUseCase` day-1 projectedTotal guard is only "tested" by the tautology file (#5) — real-input coverage disappears if that file is deleted without replacement.
- `ProcessReceiptUseCase` is dead production code; if any receipt path still needs home-currency forwarding, that behavior should live in the legal-path (ReceiptLifecycleCoordinator) tests instead.
- Stale-failure confirmation: 4 files in this batch previously failed with AssertionError (warranty 4, savings-sweep/radar/forecast 1 each, 2026-05 report); static review cannot confirm they pass now — schedule a targeted `:app:testDebugUnitTest --tests "*domain.usecase.*"` run.

## Rollup

- Verdicts: KEEP 7, STRENGTHEN 3, MERGE 0, REWRITE 0, DELETE 2, NIGHTLY 0, UNKNOWN 0.
- P0 count: 3 (files #2, #11, #12).
- DUP pairs: none strict; #5 partially shadows the P5-002 guard (resolved by DELETE + gap note); #4 vs #2 complementary (orchestration vs threshold unit).
- P4 (negative value): #5 DashboardProjectionSafetyTest.
- FRAGILE count: 1 heavy (#3, 14 ctor deps); #8 moderate.
- Stale verdicts overturned: savings "stub tests" flag (TEST_PRUNING_CANDIDATES.md item 3) — not stubs; prior batch-004 KEEP verdicts confirmed for #2, #6, #7, #10, #11; #12 downgraded to STRENGTHEN; #5 and #9 are new files not in the prior audit.
