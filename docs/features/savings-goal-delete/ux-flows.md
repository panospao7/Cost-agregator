# UX flows: savings-goal-delete (UF-05)

Source: integration worktree `Cost-agregator-int` @ e6967db1. Path key `M` = `app/src/main/java/com/yourname/expensetracker/`.
Designed for domain-spec O-1 option (c): delete only EMPTY goals; funded/completed goals get an explanation. No Kotlin here. No new permissions, no network.

## 1. Entry point and navigation

- Screen: existing `SavingsGoalsScreen` (`M ui/screens/savings/SavingsGoalsScreen.kt`). No new screen, route, or deep link. There is no goal detail screen: `selectedGoal` is written on card tap (`:201`) and never read.
- Pattern chosen: **per-card overflow menu (MoreVert IconButton + DropdownMenu with one "Delete" item), then a confirm `AlertDialog`.**
  - Overflow icon copies `Icons.Rounded.MoreVert` use in `ui/screens/transactions/TransactionsScreen.kt:1694` and `ui/screens/review/ReviewScreen.kt:204`.
  - Confirm dialog copies `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt:265-288` (AlertDialog, error-coloured `Button` "Delete", `TextButton` "Cancel").
  - Why not the others: swipe (ReviewScreen `:340`) is an undiscoverable gesture and needs custom TalkBack actions; long-press exists only on the dashboard card; an inline red Delete text button (recurring `:537-548`) would crowd `GoalCard`'s header, which already holds the "+10" quick-add or "Done" badge.
- The menu item is shown on EVERY goal card, including funded/completed and unknown-currency cards (an unknown-currency goal with zero balance is deletable and otherwise unremovable). Eligibility is not guessed in Compose (INV-16): tapping Delete asks the VM, which asks the same `SavingsGoalDeletionPolicy` via a read-only call.
- Back: system Back dismisses the menu or either dialog (not while Deleting). No new back-stack entries. Top-bar Back/Refresh unchanged.

## 2. Screens and wireframes

GoalCard header (both card variants), overflow at the trailing edge, 48dp target:

```
+--------------------------------------------------+
| Holiday fund                  [ +10 ]   [ : ]    |   [ : ] = More actions for "Holiday fund"
| Target: Dec 2026                     |  Delete | |   menu -> single item "Delete"
| ======----------------                           |
| EUR 40.00                     of EUR 200.00      |
+--------------------------------------------------+
```

D1 Confirm (empty goal; name only, no amounts):
```
+----------------------------------+
| Delete goal?                     |
| Delete "Holiday fund"? It has no |
| savings or history. This can't   |
| be undone.                       |
|            [Cancel] [ Delete ]   |   Delete = error colour
+----------------------------------+
```
D2 Deleting (same dialog, buttons disabled, Delete label replaced by a small progress indicator, outside-tap and Back ignored):
```
|            [Cancel] [ (spin) ]  |   all disabled
```
D3 Not allowed (funded or completed; neutral, no destructive action):
```
+----------------------------------+
| Can't delete this goal           |
| Goals with savings or            |
| contribution history can't be    |
| deleted, so your savings record  |
| stays complete.                  |
|                      [ Got it ] |
+----------------------------------+
```
Snackbars (existing `SnackbarHost`, `withDismissAction = true`): success, concurrent-change, history-unavailable, generic failure, write blocked (section 3).

## 3. UI state model (delete overlay, owned by the VM so it survives rotation)

The screen's existing states (Loading / Empty / Data; Error falls back to Empty at `:190-192`) are unchanged. Not applicable: offline, consent-denied, permission-denied (all local, no permission).

`DeleteFlowState` (single sealed state in `SavingsGoalsState`, at most one active):

| State | Cause | UI |
|---|---|---|
| Idle | default | nothing |
| Checking(goalId) | menu "Delete" tapped; eligibility read in flight | menu closed; that card's overflow and quick-add disabled; no spinner unless >300 ms |
| Confirm(goalId, name) | policy says Deletable | D1 |
| NotAllowed(goalId) | policy `HAS_BALANCE` or `HAS_HISTORY` (spec S5) | D3 |
| Deleting(goalId) | Confirm tapped (spec S2) | D2 |

Transitions out of Deleting (one-shot `SavingsGoalsEvent`s, bounded enum payload only, never exception text, ids, names or amounts; INV-14):

| Result | Domain cause | Event | User text |
|---|---|---|---|
| Deleted / AlreadyAbsent (S3, S4) | row removed or already gone | `GoalDeleted` | snackbar "Goal deleted" (no Undo, see section 5) |
| write barrier (S7) | `DatabaseAccessBlockedException`, INV-3 | existing `WriteBlocked(UiText)` | existing barrier message, no new string |
| Rejected `HAS_BALANCE`/`HAS_HISTORY` after confirm | goal became non-empty concurrently (INV-8 ii, INV-9) | `DeleteRejected(NOT_EMPTY)` | snackbar "This goal just received savings, so it wasn't deleted." |
| Rejected `HISTORY_UNAVAILABLE` (S6; also from pre-check) | DataStore unreadable, INV-10; never treated as "no history" | `DeleteRejected(HISTORY_UNAVAILABLE)` | snackbar "Contribution history couldn't be checked, so the goal wasn't deleted. Try again later." |
| Rejected `UNAVAILABLE` (S8; also pre-check failure) | any other failure, INV-13 | `DeleteRejected(GENERIC)` | snackbar `error_delete_failed` "Failed to delete" (existing) |
| cancellation | scope cancelled (INV-12) | none | flow state reset; no message |

After every terminal result the flow returns to Idle. If the pre-check returns NotAllowed, no event is emitted (D3 is the response). Spec coverage: S1=Confirm, S2=Deleting, S3/S4=GoalDeleted, S5=NotAllowed, S6/S7/S8=snackbars above, S9=existing `EmptyGoalsState` (`:631`). No spec state lacks UI; no UI state lacks a spec cause. "Checking" has no spec state: it is the eligibility read the spec requires in section 3 (INV-16) and is flagged as a UI-only transient.

Open detail / selected goal (INV-11): there is no detail view to close. On Deleted or AlreadyAbsent the VM clears `selectedGoal` if it has that id and drops smart/sweep recommendations naming it (the Room flow re-emission also clears the sweep card). Gap to implement: `recommendationToConfirm` (`SavingsGoalsScreen.kt:45`) is screen-local; the screen must clear it when its goal id leaves `state.goals`, and D1/D3 must dismiss silently if their goal id disappears from `state.goals` (treated as AlreadyAbsent, no snackbar from the dialog itself). Deleting the last goal shows `EmptyGoalsState`. The gamification header recomputes from the flow; its level/total text for zero goals must be pinned by test (header currently prints hard-coded "Unavailable" for null level/total, `:288,312`, which would misread as an error after the last goal is deleted; implementer to confirm real values in that case).

## 4. Money display

D1 and D3 deliberately show no amounts (name only), so no currency, rounding, mixed-currency, or unavailable-conversion rendering is introduced; deletion performs no conversion. Remaining cards keep `CurrencyFormatter.formatMoney(amount, goal.currency)`. A goal with unknown currency keeps its `savings_goal_currency_unavailable` card and still offers Delete. The header total is unchanged by deleting an empty goal (INV-15).

## 5. Destructive and irreversible actions

- Only empty goals can be deleted, so the only loss is the goal's name and target settings; confirmation D1 (spec O-3) states it can't be undone and names the goal.
- No Undo: re-creating would yield a new id and a different record; a fake Undo is misleading. Success is a plain snackbar.
- No money history is ever destroyed (structurally prevented by the rule, not by a warning).
- Funded/completed goals: D3 explains, offers no destructive action. Until archive exists these goals cannot be removed (accepted cost of O-1(c)).
- Double-tap on Delete: second call returns AlreadyAbsent; button is disabled during Deleting anyway.

## 6. Accessibility

- Overflow button: 48x48dp, `contentDescription` = "More actions for <goal name>" (name makes each of N identical icons distinguishable). Menu item text "Delete" is a text label, not colour alone.
- Cards stay clickable; overflow is a separate focus stop after the quick-add button (TalkBack order: name, target date, quick-add, more actions, progress, amounts).
- Dialogs: title read first, then message, then buttons (Cancel before Delete; initial focus on title, not on Delete). Delete uses error container/colour with the existing theme pair (contrast per theme; text label always present).
- Deleting: progress indicator has a content description "Deleting goal"; buttons disabled state is announced as disabled.
- Snackbars are live regions; success text is announced without moving focus. After a dialog closes, focus returns to that card's overflow button; if the card is gone, focus falls back to the list heading "Your Goals".
- Font scale up to 200%: dialog text scrolls; card header Row must tolerate wrapped goal names (name column keeps `weight(1f)`; overflow does not shrink below 48dp).

## 7. Copy (new string keys; EN drafts; EL/ES drafted by the implementer, owner signs off; none of these may contain raw user text except the goal name as a format argument)

| Key | EN draft |
|---|---|
| `savings_goal_more_actions_cd_format` | More actions for %1$s |
| `savings_goal_delete_confirm_title` | Delete goal? |
| `savings_goal_delete_confirm_message_format` | Delete "%1$s"? It has no savings or history. This can\'t be undone. |
| `savings_goal_delete_blocked_title` | Can\'t delete this goal |
| `savings_goal_delete_blocked_message` | Goals with savings or contribution history can\'t be deleted, so your savings record stays complete. |
| `savings_goal_deleted_message` | Goal deleted |
| `savings_goal_delete_not_empty_message` | This goal just received savings, so it wasn\'t deleted. |
| `savings_goal_delete_history_unavailable_message` | Contribution history couldn\'t be checked, so the goal wasn\'t deleted. Try again later. |
| `savings_goal_deleting_cd` | Deleting goal |

Reused as-is: `action_delete` (menu item and confirm button), `action_cancel`, `action_got_it` (D3), `error_delete_failed` (generic failure), and the existing `WriteBlocked` UiText. The goal name appears only in D1 and the overflow content description, held in UI state, never logged. Errors, logs and diagnostics carry only the bounded codes of INV-14.

## 8. Open UX questions (with recommendations)

**CLOSED (2026-10-26):** the owner accepted all recommendations (UX-1..6 in `decision-sheet.md`). The Deleting state is **not** cancellable: both buttons are disabled while it runs, and leaving the screen cancels the scope.


1. Explanation wording promises nothing about archive. Recommend: keep silent on future features until `savings-goal-archive` is approved.
2. Spec S2 says Deleting is "cancellable". Recommend: not cancellable in the dialog (single sub-second transaction; a Cancel that may not stop the write is ambiguous); leaving the screen cancels the scope per INV-12 and the list reflects the truth.
3. Hide vs show Delete on funded goals. Recommend show (with D3) so users learn why; hiding leaves no explanation.
4. Success snackbar on AlreadyAbsent shows "Goal deleted". Recommend yes (user intent satisfied).
5. Replace the hard-coded "Unavailable" header text for zero goals. Recommend implementer verifies and fixes only if it shows for an empty list (spec section 5 empty states); otherwise out of scope.
6. Is the generic `error_delete_failed` ("Failed to delete") specific enough? Recommend accept to reuse the existing string; add a goal-specific key only if the owner prefers.

## Appendix: if the owner chooses O-1(a) hard delete with warning

- Same entry point (overflow Delete). Remove D3 and the NotAllowed state; every goal reaches a confirm dialog.
- D1 becomes a destructive warning: title "Delete goal and history?"; body must state that contribution history and accepted sweep records for this goal are permanently erased and cannot be recovered, and that manual contribution entries may still count toward streak and monthly totals (orphans). Show goal balance with currency (needs money display rules: `CurrencyFormatter.formatMoney`, unknown currency shown as unavailable, never as zero).
- Typed confirmation (spec O-1 note): user types the goal name or "DELETE"; Delete stays disabled until it matches; confirm carries `expectedBalance` and `expectedLedgerCount`, re-checked in the transaction. New failure `STALE_CONFIRMATION`: snackbar "This goal changed while you were confirming. Review it and try again." and the dialog reopens with fresh values.
- New states: Confirm gains balance/history counts; `NotAllowed` replaced by `StaleConfirmation`. New strings for the warning, the typed-confirmation label and the stale message; `savings_goal_delete_blocked_*`, `savings_goal_delete_not_empty_message` removed. Still no Undo. Requires explicit owner approval of destructive behaviour (spec O-1, stop-gates).
