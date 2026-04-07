# Deep Analysis — Batch 19: UI Screens (@reviewer)

## Scope
- `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantSheet.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/split/VisualSplitEditorScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/split/VisualSplitViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesViewModel.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `AssistantViewModel.kt:178-182` | HIGH | Logic / AI context | Clarification replies intentionally drop conversation history (`!isClarificationResponse`), so answers like “Breakdown” or “Groceries” are interpreted without the prior question/context. That breaks the assistant’s follow-up flow exactly when clarification is needed. | Always include the prior session history for clarification turns, or explicitly append the previous unresolved query/context before calling `interpretFinancialQueryUseCase`. |
| 2 | `AiSettingsViewModel.kt:173-180` | HIGH | Security / state management | `testConnection()` persists the typed API key before the connectivity check succeeds. A failed test or disabled-cloud configuration can overwrite a previously working stored key with an unverified one. | Do not write the typed key to secure storage during test setup. Test with the in-memory value first, and only persist after an explicit save or a confirmed successful test. |
| 3 | `AiSettingsViewModel.kt:59-66`, `AiSettingsViewModel.kt:70-95` | MEDIUM | Performance / concurrency | Every settings emission launches a fresh `refreshRuntimeStatus()` job, and those jobs are not cancelled/debounced. Rapid toggle changes can spam expensive runtime checks and briefly publish out-of-order summaries. | Keep a single refresh job (`Job` + cancel), or drive runtime refresh from a debounced/`collectLatest` pipeline instead of launching a new detached job per emission. |
| 4 | `ReceiptScanViewModel.kt:204-222` | HIGH | Race condition | Receipt processing runs in an untracked `viewModelScope.launch`. If the user picks/captures another image before the first OCR pass finishes, the older coroutine can still publish `REVIEW`/`ERROR` state and overwrite the newer receipt. | Track the processing job and cancel/replace it on new input, or attach a request token and ignore stale completions before updating state. |
| 5 | `VisualSplitEditorScreen.kt:101-104` | HIGH | Logic / currency handling | The screen accepts `currencyCode`, but the formatter never applies it. All totals/remaining amounts are rendered in the device locale’s default currency instead of the expense currency. | Set `numberFormat.currency = Currency.getInstance(currencyCode)` and pass the correct formatter/currency down to participant rows. |
| 6 | `VisualSplitEditorScreen.kt:178-183` | HIGH | Logic / data loss | `onSplitComplete` returns the raw editable `participants` list, not the calculated split. For equal splits and partially edited percentage/custom splits, the callback can receive null/obsolete `amount` and `percentage` values. | Build the callback payload from `currentSplit.segments` (or a normalized computed share list) instead of the raw local draft objects. |
| 7 | `VisualSplitEditorScreen.kt:303-312` | HIGH | Logic | Assigned amounts/percentages are matched with `find { it.participantName == participant.participantName }`. Duplicate participant names make multiple rows resolve to the same segment, so displayed amounts become wrong. | Match by a stable participant identifier/index, not by editable display name. |
| 8 | `VisualSplitEditorScreen.kt:303-345` | MEDIUM | Compose / list state | `LazyColumn.items(participants)` has no stable key. Adding/removing participants can cause text-field state and focus to jump between rows during recomposition. | Provide a stable row key (preferably a UI-only immutable participant ID). |
| 9 | `LifestyleInflationViewModel.kt:24-36` | HIGH | Race condition | `analyze()` launches a new detached job every time the selected period changes. Because the screen’s `LaunchedEffect` only triggers the method and does not own the real work, older requests can finish later and replace the report for a newer period. | Keep/cancel the active analysis job, or model the selected period as a `StateFlow` and use `flatMapLatest`. |
| 10 | `CarbonFootprintViewModel.kt:27-42` | HIGH | Race condition | `loadReport()` has the same detached-job pattern as lifestyle analysis. Fast 7/30/90/365-day changes can leave the UI showing a stale report for the wrong period. | Cancel previous load jobs or switch to a `flatMapLatest`-style state pipeline keyed by selected period. |
| 11 | `CarbonFootprintScreen.kt:459-463` | LOW | Logic / presentation | When `parisAgreementGap` is negative, the code passes the negative number directly into the “below target” string, producing text like `-5% below target`. | Use `abs(gap)` for the below-target branch before formatting the string. |
| 12 | `SpendingChallengesViewModel.kt:23-28`, `SpendingChallengesViewModel.kt:30-38` | HIGH | Functional gap | `activeChallenges` is exposed to the screen but never populated. The screen therefore always falls back to the empty state and can never show real active challenges. | Load active challenges from `SpendingChallengeManager`/repository in `init` (or expose them as a flow) and update `_activeChallenges`. |
| 13 | `SpendingChallengesScreen.kt:40-45` | MEDIUM | Compose / stale state | `emptyStateActions` is wrapped in `remember { derivedStateOf { ... } }` without any key that changes when actions are dismissed. The collected `completedActionKeys` value is never used, so dismissed actions can remain visible until a full recomposition/recreation. | Either compute `actionRegistry.getActions(...)` directly on recomposition, or key `remember` with `completedActionKeys`. |

## Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `ReceiptScanScreen`, `VisualSplitEditorScreen`, `LifestyleInflationScreen`, `CarbonFootprintScreen`, `SpendingChallengesScreen` | MEDIUM | These screens collect long-lived flows with `collectAsState()` instead of `collectAsStateWithLifecycle()`. They can continue collecting while the UI is not at least STARTED, causing unnecessary work and stale lifecycle behavior. | Use `collectAsStateWithLifecycle()` for screen-level state flows and action registries. |
| 2 | `AiSettingsViewModel`, `LifestyleInflationViewModel`, `CarbonFootprintViewModel`, `ReceiptScanViewModel` | HIGH | Multiple components launch detached work in `viewModelScope` in response to rapidly changing UI inputs/settings without cancellation or request versioning. This creates a repeated stale-result/race pattern across AI status refresh, period analysis, report loading, and receipt processing. | Standardize on a cancellable request pattern: keep a `Job`, or move input state into flows and use `collectLatest`/`flatMapLatest`. |
| 3 | `AssistantViewModel`, `AiSettingsViewModel`, `ReceiptScanViewModel` | MEDIUM | AI-related flows use different gating/persistence behaviors for similar user actions (assistant clarification history, test-connection API key persistence, receipt assist retries). The result is inconsistent AI UX and harder-to-reason-about state transitions across screens. | Introduce shared AI interaction/state policies for context retention, temporary credential usage, retry semantics, and artifact/application status. |

## Summary
- Total issues: 13
- Critical: 0, High: 8, Medium: 4, Low: 1
- Files with issues: 9/14

## Key Patterns
- Several screens/ViewModels still use non-lifecycle-aware or detached async state patterns, which is the main source of stale UI and background-work risk in this batch.
- The split editor has multiple correctness issues around computed-vs-draft data, identity, and currency handling; those problems stack and can produce wrong UI plus wrong persisted split payloads.
- AI surfaces are feature-rich but inconsistent: assistant context retention, settings credential flow, and receipt assist behavior are implemented independently instead of through a shared interaction contract.
- The challenges feature is only partially wired: the screen is present, but the ViewModel never loads challenge data, so the primary screen path is effectively non-functional.
