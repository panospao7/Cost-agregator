# Final Verification — Batch 18: UI Screens

## Scope
- `com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
- `com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
- `com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt`
- `com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`
- `com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt`
- `com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`
- `com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerScreen.kt`
- `com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt`
- `com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarScreen.kt`
- `com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarViewModel.kt`
- `com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt`
- `com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt`
- `com/yourname/expensetracker/ui/screens/categories/CategoryScreen.kt`
- `com/yourname/expensetracker/ui/screens/categories/CategoryViewModel.kt`
- `com/yourname/expensetracker/ui/screens/currency/CurrencyManagementScreen.kt`
- `com/yourname/expensetracker/ui/screens/currency/CurrencyManagementViewModel.kt`
- `com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementScreen.kt`
- `com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `ui/screens/review/ReviewScreen.kt:432-460` | Medium | Compose side effect | `consumePrefilledReceiptSuggestion()` and `consumePrefilledCategorySuggestion()` are called during composition, so recomposition mutates ViewModel state and can consume one-shot data at the wrong time. | B | CONFIRMED | Read/consume these values from a `LaunchedEffect(review.id)` or cache them before opening the sheet. |
| 2 | `ui/screens/review/ReviewScreen.kt:82,304-325,360-364,1003-1031` | Medium | Interaction state | `processingIds` only guards swipe actions, is never cleared, and the approve/reject buttons bypass it entirely. That allows duplicate actions before removal and leaves failed rows permanently blocked. | B | CONFIRMED | Move in-flight state to the ViewModel, disable both swipe and buttons from the same source of truth, and clear it on success and failure. |
| 3 | `ui/screens/review/ReviewViewModel.kt:128,167-180` | Medium | Concurrency | The AI explanation “in-flight” guard is populated only after `settings().first()`, so rapid taps can launch duplicate explanation requests before the set is updated. | D | DOWNGRADED | Mark the review as in-flight before the async settings read, or use a mutex/atomic map around request creation. |
| 4 | `ui/screens/review/ReviewViewModel.kt:272-348` | Critical | Data integrity | `approveReviewWithEdits()` still runs `applyToAll` and `approveAllPending` after the primary approve returns `Duplicate` or `Error`, so bulk mutations can run even though the edited approval failed. | R | CONFIRMED | Return early unless the initial `approveReview(...)` result is `Result.Success`. |
| 5 | `ui/screens/review/ReviewViewModel.kt:324-328` | Medium | Logic | When the merchant name is edited, `approveAllPending` searches by `finalMerchant` instead of the original merchant still stored on pending rows, so “approve all identical” silently misses matches. | R | CONFIRMED | Search by the original merchant identity and only apply the renamed merchant during each approval. |
| 6 | `ui/screens/review/ReviewScreen.kt:860-866` | High | Crash safety | `TransferDirection.valueOf(review.suggestedDirection)` assumes persisted enum text is always valid. Unexpected or migrated values will crash composition. | R | DOWNGRADED | Parse with `runCatching` or a safe enum lookup and fall back to `null`. |
| 7 | `ui/screens/review/ReviewScreen.kt:442-455` / `ui/screens/review/ReviewViewModel.kt:272-283` | Medium | Data pipeline | The edit sheet captures `osmId`, but `approveReviewWithEdits()` does not accept or persist it, so approved edits keep coordinates without the selected place id. | R | CONFIRMED | Add a `finalPlaceId` parameter through the ViewModel/repository path and persist it on the created `Expense`. |
| 8 | `ui/screens/review/ReviewViewModel.kt:748-753` | Low | State consistency | `clearDebugData()` clears `_debugData` and shows success before the persisted storage delete finishes; if `debugDataStorage.clear()` fails, UI and storage diverge. | D | CONFIRMED | Only clear UI state after the storage delete succeeds, and surface errors if it fails. |
| 9 | `ui/screens/groups/SharedExpenseGroupsViewModel.kt:74-111` | High | State reset | `loadGroups()` rebuilds `GroupsUiState` from scratch, wiping `selectedGroup` and dialog flags. Refreshes after mutations can kick the user out of the detail screen and race later `copy()` calls. | B | CONFIRMED | Preserve/remap `selectedGroup` and transient dialog flags when new data arrives, and avoid nested fire-and-forget reloads. |
| 10 | `ui/screens/groups/SharedExpenseGroupsScreen.kt:498-499` | Low | Numeric logic | `balance == 0.0` uses exact floating-point equality, so near-zero balances can render as “owes” or “gets back” instead of settled. | D | CONFIRMED | Compare with a small tolerance, e.g. `abs(balance) < 0.005`. |
| 11 | `ui/screens/savings/SavingsGoalsViewModel.kt:157-167,213-221` | High | Lost update | Savings goal contributions use read-modify-write snapshots plus `updateGoalAmount(goalId, amount)`, so concurrent contributions can overwrite each other and lose saved money. | R | CONFIRMED | Add an atomic increment/deposit DAO operation or wrap updates in a transaction. |
| 12 | `ui/screens/warranty/WarrantyTrackerScreen.kt:494-509` | Low | UI logic | Expired warranties never show the expired badge because the badge block only runs when `isExpiringSoon` is true, which excludes negative `daysRemaining`. | B | DOWNGRADED | Render the badge when `isExpired || isExpiringSoon`, with expired styling first. |
| 13 | `ui/screens/warranty/WarrantyTrackerViewModel.kt:39-42,66-79` | Medium | Stale state | Warranty list data is reactive, but summary cards are loaded from one-shot queries and only refreshed after local writes, so external updates and time-based expiry drift leave cards inconsistent with the list. | B | DOWNGRADED | Derive summary stats from the reactive warranties flow or collect dedicated flows together. |
| 14 | `ui/screens/cashflow/CashFlowCalendarScreen.kt:111-119` / `ui/screens/cashflow/CashFlowCalendarViewModel.kt:48-80` | Medium | Input / performance | The starting-balance field writes parsed doubles to ViewModel state on every keystroke and triggers uncancelled recalculations. This causes cursor-jumping, extra DB work, and last-writer-wins races. | B | CONFIRMED | Keep editable text locally, validate separately, debounce recalculation, and cancel stale load jobs. |
| 15 | `ui/screens/cashflow/CashFlowCalendarViewModel.kt:76-80` | Medium | Navigation logic | `setStartingBalance()` always calls `loadCurrentMonth()`, so editing the balance while viewing another month jumps the screen back to the current month. | R | CONFIRMED | Recompute the month represented by `state.currentMonth` instead of always using `now()`. |
| 16 | `ui/screens/map/SpendingMapViewModel.kt:109-118,418-428` | Medium | Stale state | Located/unlocated counters are only refreshed at init and after local map actions. External expense inserts or location updates refresh markers but leave the stats bar stale. | R | CONFIRMED | Update counts from reactive flows or recompute them inside the existing collectors. |
| 17 | `ui/screens/map/SpendingMapScreen.kt:205-245` | Medium | Filter state | Date-range chips are built from `remember { System.currentTimeMillis() }` and compared by exact timestamp equality, so long-lived screens use stale windows and active chips stop matching after recreation/time drift. | D | CONFIRMED | Store a semantic filter enum in state and derive timestamps only when applying the filter. |
| 18 | `ui/screens/map/SpendingMapViewModel.kt:104-108,309-379,381-410` | Medium | Race condition | Manual `recomputeMapData(...)` calls race with the reactive located-expenses collector and both read `_state.value` independently, so stale filters can overwrite newer selections. | D | CONFIRMED | Replace manual recomputation with a single `combine(filters, locatedExpenses)` pipeline. |
| 19 | `ui/screens/map/SpendingMapScreen.kt:472-479` | Low | UX | The map auto-centres whenever device coordinates change, so normal GPS drift can yank the map away from the user’s current viewport. | D | CONFIRMED | Auto-centre only on first fix or explicit user request. |
| 20 | `ui/screens/warranty/WarrantyTrackerViewModel.kt:93-101` | Low | UX logic | The auto-detected filter chip can be turned on but not toggled off by tapping it again; it only resets through another filter. | D | CONFIRMED | Make the chip toggle its own boolean instead of always forcing `true`. |
| 21 | `ui/screens/currency/CurrencyManagementViewModel.kt:65-77,119-139` | Medium | Concurrency | `homeCurrency().collect { ... loadCurrencyData() }` launches a new load coroutine for every emission without cancelling the previous one, so loading/error state becomes non-deterministic under rapid emissions. | D | DOWNGRADED | Use `collectLatest`, `flatMapLatest`, or an explicit load job that cancels stale work. |
| 22 | `ui/screens/currency/CurrencyManagementScreen.kt:748-752` | Low | Validation UX | The conversion dialog leaves Convert enabled for invalid amounts and then silently no-ops when `toDoubleOrNull()` fails. | D | CONFIRMED | Disable Convert for invalid input or show an inline validation error. |
| 23 | `ui/screens/subscription/SubscriptionManagementViewModel.kt:70-125` / `ui/screens/subscription/SubscriptionManagementScreen.kt:198-247` | High | Dataset mismatch | The screen renders active and inactive sections, but the ViewModel only loads active subscriptions. Toggled-off subscriptions vanish completely, `inactiveCount` stays wrong, and reactivation/delete flows cannot work from this screen. | B | CONFIRMED | Load all subscriptions (or separate active and inactive queries) and derive both sections from the full dataset. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/repository/ReviewQueueRepository.kt:95-118` | High | Data loss | Approved transfer/deposit reviews never copy `suggestedDirection` or `suggestedAccountName` into `Expense.transferDirection` / `transferAccountName`, so transfer metadata is lost during approval. | Populate transfer fields from the pending review when creating the `Expense`, and add edited overrides if that path is later supported. |
| 2 | `ui/screens/warranty/WarrantyTrackerScreen.kt:205-206` / `ui/screens/warranty/WarrantyTrackerViewModel.kt:184-191` | Medium | Logic | The “Expired” filter uses `status == EXPIRED`, but nothing in this batch auto-transitions elapsed warranties from `ACTIVE` to `EXPIRED`. Expired items therefore never appear under the expired chip. | Derive expiry from `warrantyEndDate` and current time when filtering, or add a reliable status-transition job. |
| 3 | `ui/screens/review/ReviewScreen.kt:739,944-953` | Low | Dead UI state | `showTrustSignal` is remembered and used by `AnimatedVisibility`, but nothing ever toggles it, so the raw evidence text can never be revealed. | Add explicit expand/collapse behavior or remove the unreachable hidden content. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `Debugger-A#4` | `ReviewViewModel.kt:296-348` | `ReviewQueueRepository.approveReview()` changes review status; it does not delete the row. `getReviewById(reviewId)` still returns the review after approval, so the reported “always null/dead code” diagnosis is incorrect. |
| 2 | `Debugger-A#6` | `SharedExpenseGroupsScreen.kt:53-55` and similar | The `!!` usages are guarded by the same Compose snapshot read (`if (uiState.selectedGroup != null)`), and no concrete null-race crash path is present here. |
| 3 | `Debugger-A#8` | `SavingsGoalsScreen.kt:119-128` | The inner list is intentionally capped to three items; this is a layout choice and speculative UX concern, not a demonstrated bug. |
| 4 | `Debugger-A#9` | `SavingsGoalsScreen.kt:152-163` | The outer container is a regular `Column`, not another vertical scroller, so this is not the reported same-direction nested-scroll crash scenario. |
| 5 | `Debugger-A#11` | `SavingsGoalsScreen.kt:191-195` and related | These are localization/i18n gaps, not functional defects in the current implementation. |
| 6 | `Debugger-A#12` | `SavingsGoalsScreen.kt:475` | The UI and goal-generation paths in this batch enforce `targetAmount > 0`; the report is defensive hardening for corrupt data, not a demonstrated current bug. |
| 7 | `Debugger-A#13` | `SavingsGoalsViewModel.kt:136` | `MonthlySavingsSweepUseCase.shouldShowSweepPrompt()` is a synchronous calendar check, not I/O, so it does not block on external work. |
| 8 | `Debugger-A#14` | `SavingsGoalsViewModel.kt:180-183` | Launching a coroutine just to update state is unnecessary overhead, but it is not a correctness bug. |
| 9 | `Debugger-A#16` | `ReviewScreen.kt:843-846` | The report assumes a crash/wrong-color path without evidence from the actual Compose `Color(...)` usage here; this is speculative hardening, not a verified defect. |
| 10 | `Debugger-A#17` | `SharedExpenseGroupsScreen.kt:563` | Recreating `SimpleDateFormat` in this composable is a minor allocation concern only; no functional bug is shown. |
| 11 | `Debugger-A#19` | `SavingsGoalsScreen.kt:471` | The remembered formatter is only used from Compose on the main thread in this code path; no concurrent access bug is present. |
| 12 | `Debugger-B#2` | `WarrantyTrackerScreen.kt:428` | Computing `daysRemaining` from current time inside composition is slightly noisy but not a concrete correctness or crash bug. |
| 13 | `Debugger-B#3` | `WarrantyTrackerScreen.kt:305` | This is a tiny allocation observation inside dialog recomposition, not a user-visible bug. |
| 14 | `Debugger-B#6` | `WarrantyTrackerScreen.kt:48` | The remembered formatter is used from the main thread only; the claimed thread-safety defect is not substantiated by actual call paths. |
| 15 | `Debugger-B#7` | `CashFlowCalendarScreen.kt:203-212` | Material3 `ModalBottomSheet` is designed to be conditionally composed inside screen trees; the reported layout-crash claim is not reproduced by this code. |
| 16 | `Debugger-B#9` | `CashFlowCalendarScreen.kt:159-183` | Recomputing the calendar model on recomposition is a micro-optimization opportunity, not a verified functional bug. |
| 17 | `Debugger-B#10` | `CashFlowCalendarScreen.kt:301-309` | The normalization helper allocates calendars frequently, but this is performance tuning rather than a correctness defect. |
| 18 | `Debugger-B#12` | `CashFlowCalendarViewModel.kt:82-90` | `currentMonth` is always written from `loadCashFlow(startDate, ...)`, which uses month-range starts in the shown code paths, so the reported drift bug is speculative. |
| 19 | `Debugger-B#14` | `CashFlowCalendarScreen.kt:330-406` | Hardcoded English strings are localization debt, not a runtime or logic bug. |
| 20 | `Debugger-B#18` | `SpendingMapViewModel.kt:356-358` | `toLongOrNull()` falling back to a generic “Category X” label is already safe behavior; there is no crash or broken logic here. |
| 21 | `Debugger-B#19` | `SpendingMapScreen.kt:431-464,537-545` | `AndroidView` keeps a stable node instance for this composable, and `DisposableEffect` cleans up on disposal. The claimed overwritten-MapView leak is not supported by the actual lifecycle here. |
| 22 | `Debugger-B#21` | `WarrantyTrackerScreen.kt:52-56` | The `derivedStateOf` usage is unnecessary, but in the current app flow actions are effectively static and updates are already driven by `completedActionKeys`; this is not a verified bug. |
| 23 | `Debugger-C#1` | `SubscriptionManagementScreen.kt:580` | The debugger report itself resolves this to “no real bug”; the reject lambda uses the candidate from the current row as intended. |
| 24 | `Debugger-C#3` | `CurrencyManagementViewModel.kt:218-219` | The non-atomic `copy()` concern is speculative in this file; the real issue is the uncancelled concurrent load jobs, already captured separately. |
| 25 | `Debugger-C#4` | `SubscriptionManagementViewModel.kt:209` and related | The blanket “all `.value = .copy()` calls are thread-safety bugs” claim is too broad here; no concrete lost-update defect was demonstrated beyond the separate dataset/query issues. |
| 26 | `Debugger-C#5` | `CurrencyManagementScreen.kt:46-49` | Showing both a snackbar and an inline error card is redundant UX, but not a functional correctness bug. |
| 27 | `Debugger-C#6` | `SubscriptionManagementScreen.kt:515` | This is a localization issue only. |
| 28 | `Debugger-C#7` | `CategoryScreen.kt:140` | The shadowed `defaultLabel` is confusing style, not a behavioral bug. |
| 29 | `Debugger-C#8` | `CurrencyManagementScreen.kt:262` | Recreating `SimpleDateFormat` for the timestamp label is a minor allocation issue, not a correctness defect. |
| 30 | `Debugger-C#9` | `CurrencyManagementScreen.kt:429-430` | Recreating `NumberFormat` in a card composable is performance polish, not a verified bug. |
| 31 | `Debugger-C#10` | `SubscriptionManagementScreen.kt:368,434,582` | These formatter allocations are micro-optimizations only. |
| 32 | `Debugger-C#11` | `SubscriptionManagementViewModel.kt:81` | `id ?: 0L` is dead-code smell because `id` is non-nullable, but it does not create an active bug in the current repository path. |
| 33 | `Debugger-C#12` | `SubscriptionManagementViewModel.kt:70-99` | Failing the whole load when one nested query throws is a resilience trade-off, not a verified defect against current requirements. |
| 34 | `Debugger-C#13` | `SubscriptionManagementViewModel.kt:86` | Per-subscription `.first()` queries may be inefficient at scale, but this is not a demonstrated functional bug. |
| 35 | `Debugger-C#15` | `CurrencyManagementScreen.kt:648-649` | Resetting defaults when the supported-currency list changes is possible but not demonstrated by the current screen flow; this is speculative UX hardening. |
| 36 | `Debugger-C#17` | `CategoryScreen.kt:175` | The extra regex compilation is a micro-optimization concern only. |
| 37 | `Debugger-C#18` | `CategoryScreen.kt:77-85` | Dialog placement inside the `Scaffold` content lambda is unconventional but harmless here. |
| 38 | `Debugger-C#19` | `SubscriptionManagementViewModel.kt:113` | This assumes an error in `RecurrenceCalculator.toMonthlyAmount(...)` that is outside the reviewed implementation and not evidenced here. |
| 39 | `Debugger-C#20` | `SubscriptionManagementScreen.kt:500` | Locale-sensitive number formatting is acceptable for user-facing display; this is not a bug. |
| 40 | `Debugger-C#21` | `SubscriptionManagementScreen.kt:819` | Hardcoded placeholder text is localization debt, not a functional defect. |
| 41 | `Debugger-C#23` | `CurrencyManagementViewModel.kt:151-153` | The extra `isLoading = true` write is redundant, but it is not itself a bug. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Review approval pipeline | High | Data pipeline | Optional review metadata is still inconsistently threaded through approval. The chosen place id is dropped on edited approvals, and transfer metadata is never copied into approved expenses. | `ui/screens/review/ReviewScreen.kt`, `ui/screens/review/ReviewViewModel.kt`, `data/repository/ReviewQueueRepository.kt`, `data/database/entity/Expense.kt` | Replace the loose parameter list with one typed approval command that carries all persisted metadata end-to-end. |
| 2 | Snapshot reload state management | High | State architecture | Several ViewModels mix reactive flows with ad-hoc snapshot reloads or whole-state replacement, which is the common root of lost selections and stale counters. | `ui/screens/groups/SharedExpenseGroupsViewModel.kt`, `ui/screens/warranty/WarrantyTrackerViewModel.kt`, `ui/screens/map/SpendingMapViewModel.kt` | Prefer one reactive pipeline per screen and preserve transient UI state instead of rebuilding entire state objects. |
| 3 | Currency presentation | Medium | Presentation consistency | Currency formatting is not centralized: multiple screens still hardcode `€` or manual `String.format(...)` output instead of using a settings-backed formatter. | `ui/screens/savings/SavingsGoalsScreen.kt`, `ui/screens/cashflow/CashFlowCalendarScreen.kt`, `ui/screens/currency/CurrencyManagementScreen.kt` | Route all money rendering through one shared currency formatter tied to the home-currency settings. |
| 4 | Compose list identity | Low | Performance / UI stability | Several lists still omit stable item keys, increasing unnecessary recomposition and item-identity churn when data changes. | `ui/screens/savings/SavingsGoalsScreen.kt`, `ui/screens/warranty/WarrantyTrackerScreen.kt`, `ui/screens/map/SpendingMapScreen.kt`, `ui/screens/currency/CurrencyManagementScreen.kt` | Add stable `key = ...` lambdas for goals, warranties, unlocated expenses, filter chips, exchange-rate rows, and currency rows. |
| 5 | Ephemeral input handling | Medium | UI state design | Transient form/input state is still pushed directly into ViewModel recalculation paths in a few screens, making recomposition and editing behavior harder to reason about. | `ui/screens/review/ReviewScreen.kt`, `ui/screens/cashflow/CashFlowCalendarScreen.kt` | Keep user-edit text locally in the composable and commit validated values intentionally. |

## Summary
- Total verified issues: 23
- Confirmed: 23 (Critical: 1, High: 4, Medium: 12, Low: 6)
- False positives: 41
- Missed issues found: 3
- Files affected: 16/18

## Key Patterns
- The batch plan and the actual reports are misaligned: `DEEP-ANALYSIS-BATCH-PLAN.md` defines B18 as repository/security files, but both Batch 18 reports actually cover UI screens and their ViewModels.
- The review pipeline still loses optional metadata across layers. One-shot prefills are consumed unsafely, place ids are dropped, and transfer metadata is not persisted at approval time.
- The most repeated architectural problem is mixed reactive and snapshot state: screens update lists reactively but recompute counters, selections, or filters via ad-hoc reloads, which causes stale UI and race-prone refresh behavior.
- Currency and list-identity concerns are systemic: several screens still hardcode euro formatting, and multiple lazy lists rely on positional identity instead of stable keys.
