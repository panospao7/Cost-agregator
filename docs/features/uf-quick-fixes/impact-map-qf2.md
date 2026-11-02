# Impact map: QF-2 "dead buttons" (UF-01, UF-03, UF-04, UF-06, UF-08), 2026-10-22

Source: impact-mapper (Sonnet, read-only, no shell). Persisted by the orchestrator. The orchestrator ran git itself
and found none of the QF-2 files dirty in the main worktree or in `Cost-agregator-mc`.

**Batch tier: T1.** No schema, money, worker, permission or guard change. **No new strings.** No existing test pins
the buggy behaviour. One cheap review is enough. There is no Compose test infrastructure for these screens.

## Write sets
| Slice | Item | Files (lines) | Notes |
|---|---|---|---|
| QF-2a | UF-01 | `ui/components/CategoryBreakdownSheet.kt` (40-41, 48, 95) | Local `expanded` state. Hide the button once expanded, so no "Show less" string is needed. Add `verticalScroll` because the column at L48 has no scroll. The only caller is `HomeScreen.kt:1061`, unchanged. |
| QF-2b | UF-04 | `ui/screens/subscription/SubscriptionManagementScreen.kt` (50-51, 141-148); `ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt` (80-87) | Drop the `onOpenNotificationSettings` param and call `NotificationListenerSettingsLauncher.open(LocalContext.current)` in the L147 branch, so `MainActivity` is not touched. Delete the dead `onOpenBankConnections`. Remove the `scan_bank` action. Leave the `empty_action_scan_bank_*` strings in place, because they are lint-baselined and editing them would cause baseline churn. Extend `EmptyStateRegistryCompletenessTest` to check that no action targets `BankConnections`. |
| QF-2c | UF-08 | `ui/screens/home/HomeScreen.kt` (909-914); optional `ui/components/dashboard/MoneyRadarWidget.kt:272` | Remove the tier `if`. Destination is per D-MR: `onNavigateToFeature(NavigationDestination.Budget)` (the "plan" deep link already uses Budget, `MainActivity.kt:253`) or Analytics. **`ComputeMoneyRadarUseCase.kt:573-584` emits the AdjustBudget CTA for MEDIUM too, which currently does nothing.** Optionally hide the chip at LOW risk. Do not touch the hard-coded chip labels (briefing S3 replaces them). |
| QF-2d | UF-03 + UF-06 | `ui/screens/warranty/WarrantyTrackerScreen.kt:293`; `ui/MainActivity.kt` (773-776, 788-796); `ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt` (51, 139, 194, 264) | UF-03: `AddWarranty` sets `showManualAddDialog = true` (the existing comment is wrong; the empty state has no primary button). Pass `onNavigateToScanReceipt`; the same call already exists at :609-611. UF-06: set `activeTransactionFilter = TransactionFilter(dateRange = TimePeriodUtils.getDayRange(date))` and `navigateTo(Transactions(initialExpenseId = id))`, mirroring `HomeRoute` :632-635. Carry `expenseDateMillis` through `onViewTransaction`, so the UI needs no DAO read (FG-12). **Do not copy the deep-link "activity" handler**: it goes through `MainNavigationRequest.Transactions` → `navigateToTab(1)` and loses the highlight (pending-ledger "deep-link highlight inverted"). Fixing that handler belongs to app-lock S10 or the deep-link slice. Tint only, no scroll-to; a real detail view belongs to `transaction-detail` (C). |

Segments: 10 (Home), 26 (NL search), 34 (Warranty/Subscription). `MainActivity`, `CategoryBreakdownSheet`,
`MoneyRadarWidget` and `emptystate/` have no owning segment. No LEGAL_PATHS or engine change. No doc edits (avoids the
doc lock).

## Guards
- FG-12: no DAO in UI.
- FG-17: use `TimePeriodUtils.getDayRange`.
- FG-16 (UF-04): the launcher opens only system Settings intents. No manifest or permission change, no logging.
- FG-20 and FG-07: no new strings, so the lint baseline does not move.
- No `config/guards` entry covers these files.

## Conflicts
- **`MainActivity.kt`:** QF-2d hunks sit in the screen-routing `when` and are disjoint from `handleIntent` (:197-278),
  which belongs to the deep-link micro-slice and app-lock S10. One writer at a time. Merge before AL S10 (W3).
  QF-1 now proposes no `MainActivity` hunk, so QF-2d is the only QF writer on this file.
- **`HomeScreen.kt` / `MoneyRadarWidget.kt`:** briefing-grounded-signals S3 (Lane A, W5) rewrites the Radar taps and
  chips. Its plan text says "MEDIUM risk → Analytics month" (stale lines :833-836; the code is now at :896-918). UF-08
  lands first and S3 rebases. **The D-MR decision must be recorded in the BGS S3 plan/S0 so it is not undone.**
- Lane B W1 is a safe slot (its own work is docs plus pure domain math; loan's `MainActivity` slices come at W6).
- UF-02 `warranty-edit` must start after QF-2d.

## Validation (one batch, run by validation-runner)
1. `compile`. This is the main check; the `NaturalLanguageSearchScreen` signature change is compile-sensitive.
2. `targeted-unit-test` with `*EmptyStateRegistryCompletenessTest* *ContextualActionRegistryTest*
   *NotificationListenerSettingsLauncherTest*`, plus `*ComputeMoneyRadarUseCaseTest*` if the widget is gated.
3. `lint` once at the end, to confirm the baseline is unchanged.
4. `static-guards` once at merge. Cheap, and it covers `MainActivity.kt` (FG-12/17).

No `unit-test-shard`, `app-check` or migration run.

## Order (single Lane B W1 writer)
QF-1 → QF-2a → QF-2b → QF-2c (after D-MR) → QF-2d → QF-3. QF-2a/2b/2c are file-disjoint. Optionally, the UF-03
screen-only half can ship early without `MainActivity`.

## Owner questions
1. D-SB: remove `scan_bank` (recommended) and leave its strings in place (recommended)?
2. D-MR: Budget (recommended) or Analytics? Hide the chip at LOW? Record the choice in BGS S3?
3. UF-06: day filter plus highlighted row is acceptable, real detail view deferred? Carry the date through the
   callback (recommended)?
4. UF-04: if Settings fails to open, do nothing (recommended), or reuse the `notificationAccessOpenFailed` message
   pattern?
5. UF-03: ship together in QF-2d (recommended), or ship the screen-only half early?
