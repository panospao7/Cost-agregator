# Batch 28 — misc UI (part 2) + misc util + misc worker/workers (tail)

Scope: Segments 3 (review UI), 4 (receipt scan VM), 7 (manual recurring), 9 (transactions VM, CSV/JSON import), 12 (worker contract, notification intake worker), 21 (split), 32 (ui/util + test utils), 34 (price/subscription/warranty), 35 (savings goals), 36 (bill reminders), 38 (receipt matching VM) · Files: 22 · LOC: 6,325
Reviewer notes:
- Prior-audit DELETE items already actioned and verified gone from `app/src/`: `util/FlowTestUtils.kt` (prior DELETE P4 — confirmed no longer exists) and `ui/screens/debug/DebugScreenTextTest.kt` (prior SRCTEXT offender — confirmed gone). No file in this batch reads production source as text (grep for `src/main`/`readText`/`readLines` = clean).
- None of these files appear in measured failure families F-01…F-21 of `TEST_FAILURE_LEDGER.md`.
- Three `*StressTest.kt` files are class-level `@Ignore`d but are the ONLY behavioral ViewModel tests for `ReviewViewModel`, `ReceiptScanViewModel`, and `TransactionsViewModel` (siblings only appear in source-scanning architecture guards). Deleting them would zero out that coverage; they need nightly reactivation, not pruning.
- `util/HiltTestUtils.kt` prior KEEP verdict is OVERTURNED: zero subclasses exist anywhere (test + androidTest); only its own KDoc mentions one.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 13 | 5 | 0 | 0 | 1 | 3 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/ui/screens/price/PriceProtectionViewModelTest.kt | 251 | 9 | 0 | VIEWMODEL | PriceProtectionViewModel (S34) | STRENGTHEN | P3 | arch-guards only | 2 verify-only tests; "sorted" test never asserts order |
| 2 | test/…/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt | 218 | 4 | 0 | VIEWMODEL | ReceiptMatchingViewModel (S38) | KEEP | P1 | arch-guards only | Real state transitions + repo link/reject verify |
| 3 | test/…/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt | 853 | 19 | 1 (class) | ROBOLECTRIC | ReceiptScanViewModel (S4) | NIGHTLY | P1 | arch-guards only | FRAGILE; reflection into `_state`; only VM coverage; not actually stress |
| 4 | test/…/ui/screens/recurringmanual/ManualRecurringExpenseViewModelTest.kt | 234 | 4 | 0 | VIEWMODEL | ManualRecurringExpenseViewModel (S7) | KEEP | P2 | arch-guards only | Sort/activeCount/totalMonthly asserted |
| 5 | test/…/ui/screens/reminder/BillRemindersViewModelTest.kt | 130 | 4 | 0 | VIEWMODEL | BillRemindersViewModel (S36) | KEEP | P2 | arch-guards only | Load/refresh/error-empty covered |
| 6 | test/…/ui/screens/review/ReviewScreenTransactionTypeParserTest.kt | 25 | 3 | 0 | PURE | parseTransactionTypeOrNull (S3) | KEEP | P3 | #7 complementary | Tests real `internal` helper in ReviewScreen.kt:1335 |
| 7 | test/…/ui/screens/review/ReviewScreenTransferDirectionParserTest.kt | 25 | 3 | 0 | PURE | parseTransferDirectionOrNull (S3) | KEEP | P3 | #6 complementary | ReviewScreen.kt:1329 |
| 8 | test/…/ui/screens/review/ReviewViewModelStressTest.kt | 1353 | 37 | 1 (class) | MOCKED | ReviewViewModel (S3) | NIGHTLY | P1 | arch-guards only | FRAGILE; reflection into `_reviewCaptureAssistStates`; ~5 assertNotNull-only tests |
| 9 | test/…/ui/screens/savings/SavingsGoalsViewModelTest.kt | 303 | 7 | 0 | VIEWMODEL | SavingsGoalsViewModel (S35) | KEEP | P1 | none | Stateful fake repo; atomic-add stacking; legacy per-goal API retired (exactly=0) |
| 10 | test/…/ui/screens/split/VisualSplitEditorScreenStateTest.kt | 112 | 11 | 0 | PURE | SplitTextFieldState (S21) | KEEP | P1 | none | Hidden gem: NaN/Infinity/locale comma/trailing-dot |
| 11 | test/…/ui/screens/split/VisualSplitViewModelTest.kt | 227 | 5 | 0 | VIEWMODEL | VisualSplitViewModel + buildCompletedSplitShares (S21) | STRENGTHEN | P2 | #10 complementary | Tests 1–4 assert mock pass-through; test 5 (dup names by index) real |
| 12 | test/…/ui/screens/subscription/SubscriptionManagementViewModelTest.kt | 184 | 4 | 0 | VIEWMODEL | SubscriptionManagementViewModel (S34) | KEEP | P2 | arch-guards only | Real monthly/annual cost math (20/240) |
| 13 | test/…/ui/screens/transactions/TransactionsViewModelStressTest.kt | 343 | 16 | 1 (class) | MOCKED | TransactionsViewModel (S9) | NIGHTLY | P2 | arch-guards only | @Ignore; pagination stop + filter param threading good; 2 no-crash-only tests |
| 14 | test/…/ui/screens/warranty/WarrantyTrackerViewModelTest.kt | 275 | 6 | 0 | VIEWMODEL | WarrantyTrackerViewModel (S34) | STRENGTHEN | P2 | arch-guards only | KDoc self-lists gaps: sort, receipt link, status transitions |
| 15 | test/…/ui/util/ClipboardAmountParserTest.kt | 47 | 3 | 0 | MOCKED | ClipboardAmountParser (S32) | KEEP | P3 | none | Grouped-amount regex behavior; real ClipData on JVM (minor hazard) |
| 16 | test/…/util/CsvExpenseImporterTest.kt | 187 | 12 | 0 | MOCKED | CsvExpenseImporter (S9) | KEEP | P1 | #17 complementary | Goes through legal TransactionLifecycleCoordinator path; 1 trivial instantiation test |
| 17 | test/…/util/ExportImportRoundtripTest.kt | 169 | 4 | 0 | MOCKED | ExportTransaction + CsvExpenseImporter (S9/18) | STRENGTHEN | P1 | #16 complementary | Test 2 misnamed assertNotNull-only tautology; v2 roundtrip tests valuable |
| 18 | test/…/util/HiltTestUtils.kt | 34 | 0 | 0 | FIXTURE | (Hilt base class) | DELETE | P3 | none | Zero subclassers in test+androidTest; prior KEEP overturned |
| 19 | test/…/util/JsonExpenseImporterTest.kt | 277 | 9 | 0 | MOCKED | JsonExpenseImporter (S9) | KEEP | P1 | none | Date/timestamp/provider fallback contract via counting TimeProvider |
| 20 | test/…/util/ViewModelTestUtils.kt | 48 | 0 | 0 | FIXTURE | (Main-dispatcher base) | KEEP | P1 | 35 subclassers | Core ViewModel-test foundation |
| 21 | test/…/worker/NotificationIntakeWorkerTimeoutTest.kt | 941 | 15 | 0 | MOCKED | NotificationIntakeWorker + real WorkerExecutionGuard (S3/12) | KEEP | P0 | arch-guards only | Real guard; privacy fail-closed + no raw-payload-load proven; 3 dup type-check tests; dead `intakeEntity` helper (line 738) |
| 22 | test/…/workers/WorkerContractTest.kt | 89 | 5 | 0 | ROBOLECTRIC | WorkerSpec.DEFAULTS + WorkerRegistry (S12) | STRENGTHEN | P1 | none | Registry-parity test valuable; `enabled \|\| !enabled` tautology; dup size check |

## Findings (noteworthy files only)

### test/…/util/HiltTestUtils.kt
- Abstract Hilt+Robolectric base class (34 LOC) exposing `HiltAndroidRule`.
- grep across `app/src/test` and `app/src/androidTest` finds zero subclasses; the only mention of a subclass is inside its own KDoc example (line 16).
- Prior audit (batch-005 #54) said KEEP P1 — that was wrong or the consumers were deleted since; re-verified against current source.
- DELETE P3: dead fixture; if Hilt tests are reintroduced, recreate from the KDoc pattern.

### test/…/worker/NotificationIntakeWorkerTimeoutTest.kt — P0, KEEP
- Only behavioral test of `NotificationIntakeWorker`; builds a REAL `WorkerExecutionGuard` (mocked deps) so guard semantics (lease, barrier, retry-vs-failure) are actually exercised, not mocked away (buildGuard at lines 78–95).
- Proves worker invariants per AGENTS.md: `TimeoutCancellationException` → retry, max-attempts → failure with `markFinalFailure(failureCode="TIMEOUT")` (lines 261–325); privacy Denied/FailClosed → success with zero `crypto.decrypt` (lines 331–431, fail-closed honored); mid-run privacy recheck aborts before `getPayloadForProcessing` (lines 671–732) — i.e. raw notification payload is never materialized; checkpoint blocked → retry (lines 543–606).
- Warts: 3 "pure type-check" tests (lines 101–127) assert the Kotlin type hierarchy (`TimeoutCancellationException is CancellationException`) — platform tautologies, two of them duplicates of each other; private helper `intakeEntity` (lines 738–762) is never used.
- Trim ~60 LOC of type-checks + dead helper; the rest is high-value P0 privacy/worker coverage.

### test/…/ui/screens/review/ReviewViewModelStressTest.kt — NIGHTLY, P1
- 1,353 LOC, 37 tests, class-level `@Ignore("may hang in CI")` — currently zero CI value, yet it is the ONLY behavioral test of `ReviewViewModel` (approve/reject/bulk/duplicate-error, AI explanation/category/dedupe assist, quick-approve gating incl. toggle-off stops `approveReview` with `exactly=0`).
- Not a stress test — mislabeled; prior audit's MOVE_TO_NIGHTLY still correct.
- FRAGILE: reflection into private `_reviewCaptureAssistStates` (lines 928, 990, 1070, 1176, 1230, 1320); 17 constructor deps; ViewModel re-instantiated ~12 times inline.
- ~5 tests are assertNotNull-only ("flow is available", "initial pending reviews is empty") — near-zero value.
- Action: un-ignore in a nightly suite; delete the 5 assertNotNull-only tests; introduce a test seam instead of reflection.

### test/…/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt — NIGHTLY, P1
- Same pattern: class-level `@Ignore`, 19 tests, only behavioral coverage of `ReceiptScanViewModel` — AI receipt/category assist diagnostics strings, quick-save gating (`confirmQuickSave` blocked when toggle off, verify `createExpenseFromReceipt` never called), OCR-fallback state reset (lines 152–247, strong 20-field assertion).
- FRAGILE: reflection into `_state` in 10+ tests; 19 constructor deps.
- Action: nightly; replace reflection with a test-visible state seed.

### test/…/ui/screens/transactions/TransactionsViewModelStressTest.kt — NIGHTLY, P2
- @Ignore'd; good assertions for paged ALL-tab behavior: `loadMore` stops after empty page with `coVerify(exactly=2)` (lines 180–200), filter params threaded to `getExpensesPagedDynamic` (lines 262–299), merchant update refresh.
- 2 "does not crash" tests (`refresh does not crash`, assertNotNull(viewModel)) are throwaway.

### test/…/workers/WorkerContractTest.kt — STRENGTHEN, P1
- Test 5 (lines 75–88) is the real asset: enforces `WorkerRegistry.entries.specName` parity with `WorkerSpec.DEFAULTS.keys` (pause side vs schedule side — P9-CURRENT-021). Test 1 (exact 7-name set) catches silent worker add/remove.
- Test 2 asserts `spec.enabled || !spec.enabled` — always-true tautology (line 51). Test 4 re-asserts size 7, duplicating test 1.
- Drop test 2, fold test 4 into test 1.

### test/…/util/ExportImportRoundtripTest.kt — STRENGTHEN, P1
- Tests 1/3/4 valuable: Expense→ExportTransaction field mapping; full CSV v2 export-column roundtrip through `CsvExpenseImporter` into a captured `CreateExpenseRequest` (lines 81–143); metadata-comment skip (lines 146–168).
- Test 2 `exportTransaction_csvColumns_matchExpectedOrder` (lines 66–78) asserts only `assertNotNull(tx.id)`/`assertNotNull(tx.merchant)` — name promises column-order verification, body verifies nothing; delete or implement.
- Minor: builds date strings with `ZoneId.systemDefault()` — symmetric with importer parsing, but pin a zone for hermeticity.

### test/…/ui/screens/split/VisualSplitEditorScreenStateTest.kt — KEEP, P1 (hidden gem confirmed)
- 11 pure tests on `SplitTextFieldState` (internal production class in `ui/screens/screens/split/VisualSplitEditorScreen.kt:51`): trailing-dot preservation vs external sync, NaN/Infinity/−Infinity rejection, locale-comma non-commit, empty input no-commit, clear-and-retype sequence.
- Exactly the kind of money-input edge-case behavior the strategy asks for; prior "underrated gem" verdict re-confirmed.

### test/…/ui/screens/warranty/WarrantyTrackerViewModelTest.kt — STRENGTHEN, P2
- Stateful fake repo via `coAnswers`; good filter mutual-exclusion tests (auto-detected vs status vs needs-review, lines 210–251) and expiring-soon count.
- KDoc (lines 24–33) itself lists untested behaviors: expiration sorting, manual-creation receipt-placeholder linking, price-protection detection, ACTIVE→EXPIRING_SOON→EXPIRED transitions. Close those gaps.

## Area gaps (what is NOT tested in this area)

- `ReviewViewModel`, `ReceiptScanViewModel`, `TransactionsViewModel` have NO CI-active behavioral tests — all coverage is behind class-level `@Ignore`. A PR can break approve-review or quick-save with a green suite.
- PriceProtectionViewModel: eligibility sort order never asserted despite the test name; also nothing tests notification posting/permission interplay of price-drop alerts.
- WarrantyTracker: sorting, receipt-placeholder linking, and time-based status transitions untested (self-documented).
- CSV export side: no test writes the v2 CSV (only import-side roundtrip built by hand in the test); a writer/parser drift would not be caught.
- WorkerContractTest covers registry parity but nothing asserts WorkerSpec schedule/backoff values; NotificationIntakeWorker notification-permission gating of posting is not covered here (only capture privacy).
- No test covers `ClipboardAmountParser` non-`en_US` grouping beyond the three cases (e.g. EU-style "1.234,56").

## Rollup

- Verdicts: KEEP 13 · STRENGTHEN 5 · MERGE 0 · REWRITE 0 · DELETE 1 · NIGHTLY 3 · UNKNOWN 0
- P0 count: 1 (NotificationIntakeWorkerTimeoutTest)
- DUP pairs: none across files. In-file duplication: 3 duplicate type-check tests in NotificationIntakeWorkerTimeoutTest; WorkerContractTest tests 2/4 redundant with test 1. ReviewScreen parser tests #6/#7 are complementary (different functions), not DUP.
- FRAGILE count: 2 (ReceiptScanViewModelStressTest, ReviewViewModelStressTest — reflection into private StateFlows + 17–19 mock deps each)
- P4 (negative-value) files: none. Closest is HiltTestUtils (DELETE P3, dead fixture).
- Prior-verdict changes: HiltTestUtils KEEP→DELETE (overturned, evidence above); FlowTestUtils DELETE confirmed already removed; DebugScreenTextTest (SRCTEXT) confirmed already removed; ReceiptScan/Review/Transactions stress tests re-confirm MOVE_TO_NIGHTLY.
