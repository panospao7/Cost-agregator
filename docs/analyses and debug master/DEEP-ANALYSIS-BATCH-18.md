# Deep Analysis — Batch 18: UI Screens (@reviewer)

## Scope
- `ReviewScreen.kt`
- `ReviewViewModel.kt`
- `SharedExpenseGroupsScreen.kt`
- `SharedExpenseGroupsViewModel.kt`
- `SavingsGoalsScreen.kt`
- `SavingsGoalsViewModel.kt`
- `WarrantyTrackerScreen.kt`
- `WarrantyTrackerViewModel.kt`
- `CashFlowCalendarScreen.kt`
- `CashFlowCalendarViewModel.kt`
- `SpendingMapScreen.kt`
- `SpendingMapViewModel.kt`
- `CategoryScreen.kt`
- `CategoryViewModel.kt`
- `CurrencyManagementScreen.kt`
- `CurrencyManagementViewModel.kt`
- `SubscriptionManagementScreen.kt`
- `SubscriptionManagementViewModel.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `ReviewScreen.kt:442-455`; `ReviewViewModel.kt:272-296` | MEDIUM | Cross-component logic | The edit sheet captures an `osmId`/place id but drops it before the ViewModel call, so review-approved expenses keep lat/lon without place metadata and later map/correction flows cannot reuse the resolved place. | Add a `finalPlaceId`/`osmId` parameter to `approveReviewWithEdits`, thread it through the repository, and persist it on the created `Expense`. |
| 2 | `ReviewViewModel.kt:286-347` | CRITICAL | Data integrity | `approveReviewWithEdits()` continues into `applyToAll` / `approveAllPending` even when the primary approve returned `Duplicate` or `Error`. That can bulk-rewrite merchants/categories or approve other pending rows even though the original approval failed. | Return early unless the first `approveReview(...)` result is `Result.Success`. |
| 3 | `ReviewViewModel.kt:324-328` | HIGH | Logic | `approveAllPending` searches by `finalMerchant` when the user renamed the merchant. Remaining pending reviews still hold the original merchant, so the “approve all identical” path silently misses them. | Search by the original merchant key/value, then apply `finalMerchant` during each approval. |
| 4 | `ReviewScreen.kt:433-460` | MEDIUM | Compose state | `consumePrefilledReceiptSuggestion()` and `consumePrefilledCategorySuggestion()` are invoked from composition, which mutates ViewModel state during recomposition and can consume one-shot data multiple times while the sheet is open. | Move consumption into `LaunchedEffect(review.id)` or capture the suggestions before setting `editingReview`. |
| 5 | `ReviewScreen.kt:860-866` | CRITICAL | Crash/null safety | `TransferDirection.valueOf(review.suggestedDirection)` assumes persisted enum text is always valid. Any unexpected or migrated value will throw and crash the review list. | Parse with `runCatching` / enum lookup and fall back to `null` or `UNKNOWN`. |
| 6 | `ReviewScreen.kt:82,305-324` | MEDIUM | Interaction state | `processingIds` only grows. If approve/reject fails, the review stays in the queue but future swipe gestures are permanently blocked for that row until the screen is recreated. | Track per-review in-flight state from the ViewModel and clear it on success/failure. |
| 7 | `SharedExpenseGroupsViewModel.kt:74-111` | HIGH | State/race | `loadGroups()` replaces the entire state with a fresh `GroupsUiState`, wiping `selectedGroup` and dialog flags. Because mutations call `loadGroups()` in a nested launch, refreshes race with later `copy()` calls and can kick the user out of the detail screen unexpectedly. | Preserve/remap `selectedGroup` and dialog flags when new data arrives, and serialize/cancel overlapping loads. |
| 8 | `SavingsGoalsViewModel.kt:157-167,213-221` | CRITICAL | Race/data loss | `acceptSweepRecommendation()` and `contributeToGoal()` do read-modify-write updates against snapshot values plus `updateGoalAmount(goalId, amount)`. Concurrent contributions can overwrite each other and lose saved money. | Add an atomic increment/deposit DAO method or wrap updates in a transaction that increments the current amount. |
| 9 | `WarrantyTrackerScreen.kt:494-503` | HIGH | Logic/UI | Expired warranties never receive the expired badge because the badge block only runs when `isExpiringSoon` is true (`0..30` days). Once `daysRemaining < 0`, the expired label path is unreachable. | Render the badge when `isExpired || isExpiringSoon`, with expired styling in the first branch. |
| 10 | `WarrantyTrackerViewModel.kt:39-42,66-79` | MEDIUM | Stale data | `warranties` are reactive, but `activeCount` / `expiringSoonCount` / `totalProtectedValue` are snapshot-loaded separately and only refreshed after local write operations. Background auto-detection or expiry changes can leave the summary cards inconsistent with the list. | Derive stats from the collected warranties flow or collect dedicated flows together. |
| 11 | `CashFlowCalendarScreen.kt:111-117`; `CashFlowCalendarViewModel.kt:76-80` | MEDIUM | UX/performance | The starting-balance field writes directly to `Double` state and recalculates the calendar on every valid keystroke. This reformats input mid-typing (`1` → `1.0`) and does unnecessary month recomputations. | Keep editable text locally, validate separately, and commit/debounce recalculation. |
| 12 | `CashFlowCalendarViewModel.kt:76-80` | HIGH | Logic | `setStartingBalance()` always calls `loadCurrentMonth()`, so editing the balance while viewing another month jumps the screen back to the current month instead of recomputing the visible month. | Reload the month represented by `state.currentMonth` instead of always using `now()`. |
| 13 | `SpendingMapViewModel.kt:109-118,418-428` | MEDIUM | Stale data | `LocationStatsBar` counters are only refreshed at init and after local map actions. External expense inserts/location resolutions update markers reactively but leave `totalLocatedExpenses` / `totalUnlocatedExpenses` stale. | Collect count flows reactively or recompute counts inside the existing expense/unlocated collectors. |
| 14 | `SubscriptionManagementViewModel.kt:77-79,111-123`; `SubscriptionManagementScreen.kt:198-247` | HIGH | Logic | The ViewModel loads only active subscriptions, but the screen renders active and inactive sections and offers inactive-item flows. Once a subscription is toggled off it disappears from the dataset entirely, so `inactiveCount` stays `0` and the user cannot reactivate or delete it from this screen. | Load all subscriptions (or separate active/inactive queries) and derive both sections from the full set. |

## Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `SavingsGoalsScreen`, `CashFlowCalendarScreen`, `ReviewScreen`, `CurrencyManagement*` | MEDIUM | Currency infrastructure exists, but several screens still hardcode `€` strings/labels or local formatting instead of using the configured home currency. That creates inconsistent money presentation after the user changes currency settings. | Centralize amount formatting and currency labels behind a shared formatter/settings-backed abstraction and use it across all screens. |
| 2 | `SharedExpenseGroupsViewModel`, `WarrantyTrackerViewModel`, `SpendingMapViewModel`, `SubscriptionManagementViewModel` | HIGH | Multiple screens mix reactive flows with ad-hoc snapshot reloads and whole-state replacement. The recurring result is stale counters, lost selections, and race-prone refresh behavior after mutations. | Prefer derived reactive state (`combine`/`map`/`stateIn`) and incremental updates over nested `loadX()` launches that rebuild entire UI state objects. |
| 3 | `SavingsGoalsScreen`, `WarrantyTrackerScreen`, `CurrencyManagementScreen`, `SpendingMapScreen` | LOW | Several lazy lists/grids omit stable keys, which increases item reuse mistakes and recomposition churn when lists are filtered or reordered. | Add `key = ...` using stable ids/unique fields for goals, warranties, exchange rates/currency rows, unlocated expenses, and insight rows. |
| 4 | `ReviewScreen`, `CashFlowCalendarScreen` | MEDIUM | Transient user input is pushed straight into ViewModel/domain state instead of being staged locally, causing recomposition-driven side effects and awkward editing behavior. | Keep ephemeral form/input state in the composable and only dispatch validated, intentional updates to the ViewModel. |

## Summary
- Total issues: 14
- Critical: 3, High: 6, Medium: 5, Low: 0
- Files with issues: 11/18

## Key Patterns
- Several ViewModels still rely on snapshot reload methods that overwrite full UI state instead of deriving state reactively from flows.
- One-shot metadata is not propagated consistently across layers (review prefills, place ids), which breaks downstream features.
- Monetary presentation is not centralized, so currency-aware screens and non-currency-aware screens drift apart.
- Some Compose interactions are wired directly to persistent/domain state, which makes recomposition and user input harder to reason about.
