# Cell I-05 Audit — UI/ViewModel Mutation and Insight Pipelines

## Provenance

- Campaign: CA-2026-09-21
- Cell: I-05
- Mode: AUDIT (static only)
- Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965
- Pin check: `git rev-parse --short HEAD` = `37601232`; `git diff --stat 37601232..HEAD -- app config scripts` empty.
- Session: direct astra session
- Date: 2026-09-22
- Agents invoked: direct session; scout `i05_scope` for read-only scope reconciliation.
- No builds, tests, lint, or guards run.

## Scope and coverage

The I-05 row in `docs/architecture/COVERAGE_MATRIX.md` was read first. Legal-path sections read from `docs/architecture/LEGAL_PATHS.md`: Expense Mutations, Receipt Mutations, Analytics, Privacy / Cloud AI, and Money / Currency. Segment contracts read: CODEBASE_SEGMENTS sections 8 (Analytics & Insights), 9 (Core Expense Management), 10 (Dashboard Totals & Widgets), 20 (AI Platform), 30 (Dependency Injection), and 31 (Use Cases).

Inventory reconciliation:
- `rg --files` found 40 physical `*ViewModel.kt` files.
- The inline `RecurringExpensesViewModel` in `ui/screens/recurring/RecurringExpensesScreen.kt` makes 41 Hilt ViewModel classes, matching `route-viewmodel-map.md` and `VIEWMODEL_INJECTION_MAP.md`.
- `domain/usecase/` contains 17 Kotlin files.
- T3-light inventory found 84 screen files, 60 component files, 7 `ui/util` files, 3 mapper files, and 5 navigation files. Broad searches found no composable that directly injects a Repository or DAO.
- The matrix's unreconciled Configuration/Performance/Accessibility segment was enumerated: `domain/config/AppConfig.kt`, `domain/performance/ImageCache.kt`, `ui/components/CategoryDonutChart.kt`, `SpendingPaceGauge.kt`, `ForecastTimeline.kt`, `BudgetBlockPartyCard.kt`, `RetroBudgetBlockPartyCard.kt`, and `data/currency/AppConfigCurrencyProvider.kt`. No additional orphan file was found in that scoped segment search.
- Primary mutation/read paths inspected: `TransactionsViewModel`, `SpendingMapViewModel`, `AddExpenseViewModel`, `ReviewViewModel`, `ReceiptScanViewModel`, `ReceiptMatchingViewModel`, `ManualRecurringExpenseViewModel`, inline `RecurringExpensesViewModel`, `SharedExpenseGroupsViewModel`, `SubscriptionManagementViewModel`, `HomeViewModel`, `AnalyticsViewModel`, `ExpenseRepository`, `ManualExpenseRepository`, `ManualRecurringExpenseRepository`, `RecurringExpenseRepository`, `NavigationDestination`, `NavigationController`, `DestinationPersistencePolicy`, `DeepLinkParser`, the 17 use-case files by mutation/IO pattern, and relevant coordinator call sites.
- Existing findings in the registry and owner-cell reports were excluded; known transaction-lifecycle, receipt-dispatch, recurring-subscription, dashboard-window, and cancellation debt was not restated.

## Findings

### ID: CA-I-05-001
Title: User-facing location clear bypasses the transaction lifecycle
Defect class: 1 — Legal-path violation
Severity: P1
Evidence: `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt`, `clearLocation`, lines 710-718 calls `expenseRepository.clearExpenseLocation(expense.id)`; `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`, `clearExpenseLocation`, lines 981-988 directly calls `expenseDao.clearLocation` after only `DatabaseWriteBarrier` checking. The repository's own allowlist labels this operation “maintenance/backfill” and explicitly says “no lifecycle event by design,” but the production caller is the user-facing `TransactionsScreen.kt` clear action at lines 941-942.
Impact path: Transactions screen → TransactionsViewModel.clearLocation → ExpenseRepository.clearExpenseLocation → ExpenseDao.clearLocation; the location mutation commits without `TransactionLifecycleCoordinator.updateLocation`, `UPDATED` TransactionEvent, or post-commit side-effect planning/dispatch. UI reports “Location cleared” and refreshes, so the missing audit/event path is silent.
Caller trace: `TransactionsScreen` EditLocationDialog `onClear` → `TransactionsViewModel.clearLocation`; separate backfill callers use `conditionallySetLocation` and are not this path.
Existing tests/guards: `config/guards/db_ownership_policy.yml` allowlists `clearExpenseLocation` as GR-08l1 maintenance; `DbGuardPolicyFixtureTest` covers the allowlist. `scripts/guards/check_lifecycle_bypasses.kts` maps `expenseDao.clearLocation` to the coordinator but does not distinguish a user UI caller. No test found asserting a clear action emits an UPDATED event.
Cross-cell impact: P-02 transaction lifecycle and P-09 location enrichment; any event-driven analytics, provenance, or side-effect consumer misses a user location clear.
Old-ID cross-refs: none.

### ID: CA-I-05-002
Title: ViewModels expose raw exception messages to UI state and logs
Defect class: 9 — Privacy
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt`, `deleteExpense`, lines 473-477 and update methods lines 499-522/560-587/705-717 interpolate `e.message` into `UiText.DynamicString`; `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`, scan failure lines 474-495 appends `Processing Error: ${e.message}` to `parsingLogs` and renders `Total failure: ${e.message}`; save failure lines 1268-1284 also put `e.message` into `SaveReceiptResult.Error`. The same pattern is present in recurring, budget, tax, cash-flow, groups, subscription, warranty, currency, investment, and other ViewModels found by the scoped `rg`.
Impact path: DAO/Room, filesystem, parser/OCR, provider, or network exception → ViewModel catch → Compose error state/debug data → user-visible UI and (for receipt scan) retained in in-memory debug data. Exception messages can carry SQL text, paths, provider details, OCR/receipt content, or other unbounded payloads, contrary to the privacy contract requiring controlled reason codes and exception class only.
Caller trace: screen event → affected ViewModel coroutine (for example TransactionsScreen delete/edit or ReceiptScanScreen scan/save) → repository/coordinator/provider exception → raw message state.
Existing tests/guards: `scripts/test_verify_pii_logging_boundaries.py` and PII guard infrastructure target logging/wrapping, while Assistant and Review privacy tests assert sanitized messages; no guard or test covers these UI state assignments. No registry entry was found for this UI-wide pattern.
Cross-cell impact: P-03 receipt/OCR privacy, P-08 AI/provider privacy, P-12 export/diagnostics, and every screen displaying the affected error state.
Old-ID cross-refs: none.

### ID: CA-I-05-003
Title: Visual Split navigation persistence serializes financial amount and currency
Defect class: 9 — Privacy
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationDestination.kt`, `VisualSplitEditor`, lines 61-90 carries `expenseAmount`, `expenseCurrency`, and an optional full `Expense` payload; `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationController.kt`, `NavigationDestination.toSaveToken`, lines 147-154 writes `expenseAmount` and `expenseCurrency` into the URI-encoded save token. `rememberSaveable` persists `PersistedNavigationState` tokens in Android saved state. `DestinationPersistencePolicy.kt` lines 18-31 classifies this route as DEGRADED but does not redact the financial fields.
Impact path: TransactionsScreen → `NavigationDestination.VisualSplitEditor.forExpense(expense)` → save-token generation → persisted navigation state/Bundle; a transaction's amount and currency remain in navigation state and may be exposed to state inspection/back-stack tooling. The route can recover by `expenseId` and reload the record, so these fields are unnecessary in the persisted token.
Caller trace: `MainActivity.kt` transaction click around lines 573-577 → `VisualSplitEditor.forExpense` → `NavigationController.toSaveToken`.
Existing tests/guards: `NavigationRouteContractTest` covers round-trip serialization (including amount/currency), which codifies the behavior; no privacy guard checks saved navigation tokens for financial payloads. `DeepLinkParser` requires confirmation for individual expense IDs but does not cover saved-state serialization.
Cross-cell impact: Segment 21 split UI, navigation/state-restoration privacy, and any backup/debug tooling that captures saved navigation state.
Old-ID cross-refs: none.

### ID: CA-I-05-004
Title: Injected expense use cases are dead and one preserves an unsafe error contract
Defect class: 13 — Wiring / dead code
Severity: P3
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/usecase/expense/ExpenseUseCases.kt`, `GetExpensesBetweenDatesUseCase` lines 25-38, `GetExpenseStatisticsUseCase` lines 44-83, and `ReviewExpenseUseCase` lines 89-104. Repository-wide production caller search found no caller, Hilt binding, or ViewModel injection for any of the three classes. The file labels itself an “Example UseCase” (lines 11-23), while `ReviewExpenseUseCase` returns raw `e.message` at lines 97-103 and `GetExpenseStatisticsUseCase` falls back to raw `effectiveAmount` when normalization fails at lines 63-79.
Impact path: NONE-FOUND in production; if later wired, the dead API would introduce a parallel category mutation facade and unsafe error/currency fallbacks.
Caller trace: NONE-FOUND (definition-only search for all three class names).
Existing tests/guards: no production wiring or caller test found; no dead-use-case guard found.
Cross-cell impact: Segment 31 use-case layer, P-02 transaction lifecycle, E-01/E-02 money and analytics contracts.
Old-ID cross-refs: none.

## Defect-class coverage

1 Legal path — clear-location UI bypass found (CA-I-05-001).
2 Barrier — write barrier present on the bypass; no separate missing-barrier finding.
3 Atomicity/TOCTOU — coordinator-backed UI mutations and repository transaction boundaries inspected; no new finding.
4 Idempotency/duplicates — mutation wrappers and receipt-match UI paths inspected; no new finding.
5 Cancellation — UI catches rethrow cancellation where inspected; known shared cancellation debt excluded.
6 Side-effect timing — CA-I-05-001 also omits lifecycle side effects; no separate duplicate.
7 Money/currency — analytics/dashboard consumers inspected; known owner-cell mixed-currency debt excluded; no new I-05 finding.
8 Time — TimeProvider/TimePeriodUtils use in inspected ViewModels and use cases checked; no new finding.
9 Privacy — CA-I-05-002 and CA-I-05-003.
10 Worker hygiene — no worker implementation owned by this cell; worker references were consumer-only.
11 Data integrity — no new orphan/FK defect established in inspected UI paths.
12 Error handling — raw error exposure is captured by CA-I-05-002; no separate duplicate.
13 Wiring/dead code — CA-I-05-004.
14 Test correctness — route round-trip tests and privacy tests were inspected; no new tautology finding.
15 Fix regression — no new defect attributable to the pinned wave diff was established.

## Limitations

This was a static audit. No builds, tests, lint, or guards were executed. The T3-light UI sweep was pattern-based and did not deeply review layout/visual behavior. The 40 ViewModel files and 17 use-case files were inventory-reconciled; primary mutation and insight paths were read in detail, while pure display-only ViewModels/components were checked by targeted mutation/IO/privacy searches rather than full-file reads. Findings require the independent Phase 2 verifier.

## Provenance block

Direct astra session; pinned source 37601232; scout `i05_scope`; report written 2026-09-22.
