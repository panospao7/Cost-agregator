# FC-PAID-UNLINKED: write-time payment linking, domain spec

Status: DESIGN ONLY. Needs a new owner approval item before any slice starts (items 140 and 141 covered the read-time fix, which is parked).
Source read: mainline e4376a77 (`Cost-agregator-c34`). Paths below are under `app/src/main/java/com/yourname/expensetracker/`.
History: `workflows/active/fix-1.0.4-paid-unlinked-plan.md` (three strict rejections of the read-time matcher, branch `feature/fix104-ap-paid-unlinked` 2503e27c, parked under item 141).

## 0. Problem and design principle

A recurring bill is paid, but the expense is never linked to its occurrence. The occurrence stays PLANNED, so the forecasts count the bill twice: once as spent and once as a committed obligation. Rent example: limit 1,000, spent 700 including rent 400 paid on Nov 25, rent due Nov 27. The forecasts see 1,100. This affects Home weather, Monte Carlo, Runway, stress, the calendar and Radar.

Why the read-time attempts failed: each forecast consumer guessed which bill a payment paid, using only the rows it could see. Three reviews each found a case where the guess removed a real bill (two bills from one merchant, SKIPPED rows freeing a payment, one payment advancing two patterns).

**Principle of this design.** The forecasts never guess. A bill leaves the committed obligations only when its persisted occurrence status is not PLANNED. That status is written by exactly three things:

1. the existing DR-11 automatic link (unchanged window, plus a stricter ambiguity gate, D-3);
2. a new explicit user confirmation of one suggested (occurrence, expense) pair;
3. the existing mark-paid, "already recorded", skip and cancel paths.

The new suggestion logic only asks the user a question. It is never an input to any forecast. A wrong suggestion therefore cannot remove a bill: the worst case is a question the user answers "Not this bill".

**Rejected alternative: persisted candidate rows.** These were rejected for three reasons:

- They need a new table, which means a schema change.
- They need invalidation on six paths: expense edit, expense delete, occurrence status change, rule update, rule pause or delete, and restore. Each path is a place for a stale-candidate bug.
- They are written by a post-commit action that process death can drop, so a sweeper would be needed as well.

Instead, suggestions are computed on demand from persisted state. The only new persisted fact is the user's decline.

## 1. Entities and value objects

No new table, no new column, no new occurrence status.

### 1.1 Existing, unchanged

- `RecurringOccurrence` (`data/database/entity/RecurringOccurrence.kt`): `status` (PLANNED, PAID, SKIPPED, MISSED, CANCELLED, IGNORED), `linkedExpenseId: Long?`, `expectedAmount: Double` + `expectedCurrency: String`, `paidAmount: Double?` + `paidCurrency: String?`, `dueDate: Long` (start of day, device zone), `sourceType`, `sourceId` (rule id), `merchant: String?`.
- `Expense`: `amount: Double` (major units of `currency`), `currency: String` (ISO code), `date: Long`, `merchant`, `merchantKey?`, `transactionType`, `isNotMine`.
- `RecurringLifecycleEvent` (`recurring_lifecycle_events`): `occurrenceId: Long?`, `eventType: String`, `metadata: String?` (JSON). It is append-only, and main source has no DELETE query on this table.

### 1.2 Money

- Amounts are compared as stored `Double` major units, in the row's own currency. The matcher does no rounding and no FX conversion.
- An amount matches when `expected` is finite and non-zero, `actual` is finite, and `|actual - expected| <= |expected * 0.10|`. The bound is inclusive. This is the existing `amountMatches` in `RecurringLifecycleCoordinator`.
- Currency matches when the two ISO codes are equal, ignoring case.
- A negative amount or a refund never matches a positive expected amount.

### 1.3 New value objects (in memory only, never persisted)

- `PaymentLinkWindow(earlyDays: Int, lateDays: Int)`. This is the existing `AutoLinkWindow` type, reused.
- `PaymentLinkSuggestion` has these fields, all non-null:
  - `occurrenceId: Long`, `ruleId: Long`, `occurrenceKey: String`
  - `dueDate: Long`
  - `expected: amount + currency`
  - `expenseId: Long`, `expenseDate: Long`
  - `paid: amount + currency`
  - `dayDelta: Int` (positive when the expense is after the due day)
  - `alternatives: List<Long>`: other occurrence ids that are open and pair-eligible for this expense, ordered by rank. Used by the "Different bill" picker.

  Display names (merchant, rule name) are read by the UI from the rule and the expense. They are not copied into the suggestion.
- `PaymentLinkSuggestions` = `Available(items: List<PaymentLinkSuggestion>)` or `Unavailable(reason: READ_BLOCKED | READ_FAILED)`. "Unavailable" is never shown as "no suggestions".
- `PaymentLinkConfirmResult` = `Linked`, `AlreadyLinkedToThis`, `NoLongerValid`, `ExpenseLinkedElsewhere`, or `Failed(code)`. Codes are bounded: `WRITE_BLOCKED`, `INTERNAL`.
- `PaymentLinkDeclineResult` = `Declined`, `AlreadyDeclined`, `NoLongerValid`, or `Failed(code)`.

### 1.4 New persisted fact (no schema change)

The decline is one `recurring_lifecycle_events` row:

- `occurrenceId = O.id`
- `eventType = "LINK_SUGGESTION_DECLINED"`
- `oldStatus = null`, `newStatus = null`
- `metadata = {"expenseId": <Long>, "source": "user_declined_match"}`, written with `JSONObject`. Ids and a bounded code only.

It is read through the existing `RecurringLifecycleEventDao.getEventsForOccurrence(id)`, so no new DAO query is needed. If the metadata is malformed or `expenseId` is missing, the row is not a decline. A suggestion can then come back, which is the safe direction.

### 1.5 Definitions

- **P(O), the period in days of O's rule.** Resolved exactly as `resolveAutoLinkWindow` does today: `fixedIntervalDays`, or `calendarMonths * 28`, or 0. Implementation note: extract `resolvePeriodDays(occ)` and use it for both windows.
- **A(P), the auto window.** The existing `autoLinkWindow(P)`: weekly 1/2, biweekly and monthly 1/3, daily and irregular 0/0.
- **S(P), the suggestion window.** New. `s = min(7, floor((P - 1) / 2))` for P >= 1, otherwise 0, and `S = (s early, s late)`:

  | Rule | S(P) |
  |---|---|
  | Weekly | 3/3 |
  | Biweekly | 6/6 |
  | Monthly and longer | 7/7 |
  | Daily or irregular | 0/0 |

  Properties: A(P) is inside S(P) for every P. Two neighbouring occurrences of one rule never have overlapping S windows, because `2s < P`.
- **d(E, O)** = `TimePeriodUtils.daysBetween(O.dueDate, E.date)`. These are calendar days in the device zone, so a DST change does not shift them.
- **Open** = `status == PLANNED && linkedExpenseId == null`.
- **E is link-eligible** when all hold:
  - `RecurringLinkTypeEligibility.mayCreateLink(type, isNotMine)` passes (PURCHASE or WITHDRAWAL, not "not mine");
  - `RecurringInstallmentEligibility.mayLinkToRecurringOccurrence(E)` passes (INV-5 of loan-installments);
  - no occurrence has `linkedExpenseId == E.id`.
- **Pair (E, O) matches** when all hold:
  - O.sourceType is RECURRING_RULE;
  - the two merchant keys are equal and not blank;
  - the amount matches (1.2);
  - the currencies are equal;
  - |d| is within S(P(O)) (early side for d < 0, late side for d > 0);
  - the pair is not declined.
- **M(E)** = every occurrence row, of any status and any rule, where (E, O) matches.
- **N(E), the nearest owner.** The minimum of M(E) by `(|d|, |E.amount - O.expectedAmount|, O.dueDate, O.id)`.

## 2. Invariants

Each invariant has one owner. In this section, "the coordinator" means `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`, and "the matcher" means the new internal pure object `RecurringPaymentLinkMatcher`, in the same package and with no DAO access. The coordinator owns all I/O. The matcher owns the pair predicate, the windows and the ranking.

- **INV-1 Forecast isolation.** No forecast, budget, savings or Radar consumer reads suggestions or declines. These consumers stay unchanged: `ForecastInputAssembler`, `CashFlowCalculator`, `FinancialStressForecastEngine`, `ComputeDashboardWidgetsUseCase`, `FinancialWeatherRepository`, `MonthlySavingsSweepUseCase` and Radar. A bill leaves the committed obligations only through a persisted status that is not PLANNED, or through `rule.nextDate` advancing (the Dashboard pattern path, `ForecastInputAssembler.kt:244`). Owner: the coordinator, as sole writer of the link state. Proof: an architecture test that only `ui/` ViewModels and `MarkRecurringBillPaidUseCase` reference the suggestion API.
- **INV-2 One-to-one.** An expense links to at most one occurrence, and an occurrence holds at most one expense. Owner: `claimLinkSuppressInWrite` (existing conditional claim plus the `getByLinkedExpenseId` check). No new writer of `linkedExpenseId`.
- **INV-3 Single write path.** Every new link (auto, confirm, mark-paid) goes through `claimLinkSuppressInWrite`, under the write barrier and in one transaction. Owner: the coordinator.
- **INV-4 Single pair predicate.** The rule of section 1.5 is defined once, in the matcher. The coordinator's private `amountMatches` and its merchant check move into the matcher. `OccurrenceConflictResolver.amountMatches` is today an exact duplicate (`OccurrenceConflictResolver.kt:153`); D-8 proposes delegating it to the matcher. Owner: the matcher.
- **INV-5 Windows.** A(P) stays as merged (DR-11). S(P) is as defined in section 1.5. A must be inside S, and neighbouring S windows must not overlap; both are unit-tested over P = 0..400. Owner: the matcher, with `autoLinkWindow` kept in the coordinator companion and called from there.
- **INV-6 Nearest-owner gate (D-3).** The auto tier and the suggestion tier may target only N(E). If N(E) is not open, E gets no auto link and no suggestion. If N(E)'s rule is inactive, E gets no suggestion, but the auto tier is not blocked (the merged R21 pin `autoLinkDoesNotAdvanceAnInactiveRule` stands: link, PAID, no advance). Because M(E) includes SKIPPED, PAID and MISSED rows, a payment next to a bill already handled by the user is never moved onto a different bill. Owner: the matcher.
- **INV-7 Auto tier.** On create, and on edit when not linked, E is claimed to N(E) when N(E) is open, `d` is inside A(P), E is link-eligible, and the pair is not declined. The rule's active flag is not a condition here (R21 pin). First come wins, with no displacement (LEGAL_PATHS DR-11 text). Owner: `linkExpenseToOccurrence`. Its read range widens from [-3 days, +1 day] to [-7 days, +7 days] so that M(E) is complete. Non-rule sources (subscription and installment occurrences) keep same-day auto-link matching as before; only `RECURRING_RULE` occurrences get windows and suggestions.
- **INV-8 Suggestion tier.** E is suggested for O = N(E) when all hold: O is open, O's rule is active, E is link-eligible, `E.date <= endOfDay(now)`, and O is materialized. A suggestion also covers pairs inside A(P) that were never claimed (a lost post-commit action, an expense from before DR-11, or a row that was materialized later). Each expense is suggested at most once. Each occurrence shows at most one expense: among all E with N(E) = O, the minimum by `(|d|, |amount diff|, E.date, E.id)`. The order is deterministic: dueDate, then occurrence id. Owner: the matcher for the ranking, and `findPaymentLinkSuggestions` for the reads.
- **INV-9 Read scope.**
  - Targets are open RECURRING_RULE rows with `dueDate` in [today - 31 days, today + 7 days].
  - Expenses are read for [today - 38 days, endOfDay(today)].
  - Blocker rows (any status) are read for [today - 45 days, today + 8 days).
  - Reads per call: one occurrence range read, one expense range read, one installment batch read, and one `getEventsForOccurrence` per occurrence that has at least one pair before the decline filter. The method never reads per rule and never runs inside the forecast compute. Owner: `findPaymentLinkSuggestions`.
- **INV-10 Suggestions are read-only.** No write, no materialization, no horizon top-up. Owner: `findPaymentLinkSuggestions`.
- **INV-11 Fail closed.**
  - While a restore is running, or when the read barrier blocks, the result is `Unavailable(READ_BLOCKED)`.
  - Any other read error gives `Unavailable(READ_FAILED)`.
  - The result is never `Available(empty)` because of an error.
  - `CancellationException` is always rethrown.

  Owner: `findPaymentLinkSuggestions`. It uses `DatabaseReadBarrier.checkReadAllowed`. Adding the barrier to the coordinator constructor changes test fixtures, so those classes must be in the targeted filter.
- **INV-12 Confirm.** A link from a suggestion needs one explicit user action that names `(ruleId, occurrenceId, expenseId)`. Inside the claim transaction (the `selectTarget` lambda), the pair is checked again:
  - O exists, is open, has `O.sourceId == ruleId`, and its rule is active;
  - the pair matches (section 1.5, S window);
  - the pair is not declined;
  - E is link-eligible.

  If any check fails, the result is `NoLongerValid` and nothing is written. The provenance source is `user_confirmed_match`. A confirm may target any open, pair-eligible occurrence (the "Different bill" picker), not only N(E). Owner: `RecurringLifecycleCoordinator.confirmPaymentLink`. Entry point: `MarkRecurringBillPaidUseCase.linkExistingPayment`, under the existing per-rule `Mutex`, so it cannot race mark-paid.
- **INV-13 Advance after confirm.** After a `CLAIMED` confirm, the rule advances by the DR-03 rule: only when `day(O.dueDate) == day(rule.nextDate)`, through `advanceRuleAfterAutoLink`, which calls `RecurringRuleLifecycleCoordinator.advanceNextDate` (forward only). It runs in `NonCancellable` after the commit, as best effort. The advance is load-bearing: the Dashboard path (Monte Carlo, Runway) reads `rule.nextDate`, not occurrence status. Owner: the coordinator.
- **INV-14 Decline.** "Not this bill" writes one `LINK_SUGGESTION_DECLINED` event per pair, in one write-barrier transaction. A pair that already has a decline is a no-op (`AlreadyDeclined`). A declined pair is never suggested again, and it is never auto-linked: the INV-7 auto tier on edit-time relink also excludes it. A decline is removed from M(E), so the next-nearest bill can be suggested. The decline changes no occurrence status. Owner: `RecurringLifecycleCoordinator.declinePaymentLink`. Entry point: `MarkRecurringBillPaidUseCase.declinePayment` (per-rule lock).
- **INV-15 Keep check on edit (D-4).** An existing link is kept when all hold:
  - `mayKeepLink` passes;
  - installment eligibility passes;
  - the merchant keys are equal;
  - the amount matches (±10%);
  - the currencies are equal;
  - `d` is inside S(P). Today the check uses A(P).

  An existing link is never re-ranked or displaced. Without the S window, any category edit or bulk correction (`reconcileAllLinkedExpensesAfterBulkUpdate`) would silently unlink a confirmed link paid two days early, and the bill would be counted twice again. Owner: `reconcileExpenseLinkAfterUpdate` / `expenseMatchesOccurrence`.
- **INV-16 Writes blocked during restore.** Confirm and decline go through `writeBarrier.runWrite`. If writes are blocked, nothing is written and the use case returns `Failed(WRITE_BLOCKED)`. Owner: the coordinator.
- **INV-17 No backfill writes (D-5).** Existing unlinked pairs are covered by INV-8 and INV-9: any pair whose bill is due within the last 31 days appears as a suggestion. No migration, no sweep, and no automatic link of historical data. Owner: none, since this is a decision not to write.
- **INV-18 Privacy.** Events, logs and diagnostics carry only ids, bounded codes and counts. Owner: the coordinator, together with `RecurringLifecycleEventWriter`.

## 3. State machines

### 3.1 Occurrence status (unchanged; no new transition)

| From | To | Trigger | Reason |
|---|---|---|---|
| PLANNED (open) | PAID | auto claim (INV-7), confirm (INV-12), mark-paid | ACTUAL_EXPENSE_LINKED |
| PAID | PLANNED | expense deleted; edit no longer matches (INV-15) | ACTUAL_EXPENSE_UNLINKED |
| PLANNED | SKIPPED / CANCELLED / MISSED | existing user and system paths | unchanged |

Illegal transitions:

- A confirm on a non-open row returns `NoLongerValid` (claim NOT_OPEN).
- A confirm on an expense already linked elsewhere returns `ExpenseLinkedElsewhere`.
- A decline never changes the status.

### 3.2 Pair (E, O)

| State | Persisted | Meaning |
|---|---|---|
| NONE | no | Fails the predicate, the gate or the uniqueness rule |
| SUGGESTED | no (derived on each read) | INV-8 holds |
| LINKED | yes (`O.linkedExpenseId = E`) | Claimed |
| DECLINED | yes (event) | User said "Not this bill". Terminal in v1 (D-9) |

Transitions:

- NONE -> SUGGESTED, and back to NONE, happen implicitly whenever the persisted state changes. Causes include an edit or delete, a status change, a rule pause, or a link to another expense.
- SUGGESTED -> LINKED happens on confirm.
- SUGGESTED -> DECLINED happens on decline.
- NONE -> LINKED happens through the auto tier.
- LINKED -> NONE happens on unlink after a delete or a non-matching edit.
- DECLINED stays DECLINED even when the expense is edited.

### 3.3 Retry, cancellation and process death

- **Suggestion read.** No state is kept, so a retry recomputes it. Cancellation propagates.
- **Confirm.**
  - Process death before the commit leaves nothing written; the user taps again.
  - A retry after the commit returns `AlreadyLinkedToThis`, a success, with no second event.
  - Process death between the commit and the advance (INV-13) leaves `rule.nextDate` on a PAID occurrence. The existing mark-paid path self-heals this: an occurrence on that day that is not open gives `AdvancedOnly`. Until then the Dashboard path still counts the bill (known limit L-7).
  - Cancellation before the claim aborts. After the claim, the advance runs `NonCancellable`.
- **Decline.** The check and the insert happen in one transaction, so a retry returns `AlreadyDeclined`.
- **Auto tier.** Unchanged: a best-effort post-commit action. If process death drops it, the pair resurfaces as a suggestion (INV-8).

## 4. Edge cases

The figures in this table use EUR, and today is Nov 26, 2026, unless stated otherwise.

| Case | Behaviour |
|---|---|
| Two bills, same merchant, one payment | Only N(E) is suggested or auto-linked. The other bill stays PLANNED and counted (AE-3, AE-6). |
| SKIPPED neighbour ("already recorded") | It sits in M(E). If it is nearest, nothing is offered and the live bill stays counted (AE-4). If it is farther, the live bill is suggested, but never linked without a tap (AE-5). |
| Occurrence already PAID | Never a target. If it is nearest, it blocks: a duplicate entry next to a paid bill never pays a second bill (AE-13). |
| Partial payment (200 + 200 for 400) | Neither amount matches, so nothing happens. The bill is still counted: a false warning, the safe direction (L-2). |
| Amount drift | Matches within ±10% inclusive; beyond that, nothing (AE-7). |
| Other currency | Never matches, and there is no FX (L-3, D-7). |
| Missing or stale FX | Not used anywhere in matching. |
| Expense edited | Keep check per INV-15. If unlinked, the auto relink respects declines. No re-ranking (AE-11, AE-22, AE-23). |
| Expense deleted | Existing unlink returns the occurrence to PLANNED, so it is counted (AE-12). |
| Manual "Not this bill" | Decline (INV-14). For an already linked occurrence there is no new unlink path in v1 (D-6). |
| Installments | INV-5 eligibility refuses installment payments and plan purchases on both tiers (AE-10). |
| Inactive or paused rule | Never a suggestion or confirm target (the occurrence stays PLANNED and counted). The auto tier may still link a payment to its occurrence (INV-6/INV-7 amended): the occurrence becomes PAID and the rule does not advance. Its PAID and SKIPPED rows can still block. |
| Unmaterialized occurrence (projection only) | Never a target (L-5). |
| Null or blank merchant | Never matches (AE-25). |
| Future-dated expense | Never suggested. The auto tier is unchanged. |
| Weekly | S = 3/3, so each payment day is in at most one window of the rule (AE-15). |
| Month end | The anchor day is kept by the existing expander. A monthly S window of 7/7 never reaches the neighbouring month's occurrence (AE-16). |
| DST | `daysBetween` uses calendar days, so the delta is stable across the change (AE-17). |
| Time zone change after generation | The day delta can be off by 1 (existing limit (a), L-8). |
| Duplicates | A second identical expense finds N(E) PAID, so nothing happens (AE-13). Confirm and decline are idempotent (AE-19). |
| Stale UI | The expense is deleted, or the bill is paid by mark-paid, before the confirm. The check inside the transaction returns `NoLongerValid` (AE-20). |
| Mark-paid at the same time | Both run under the same per-rule `Mutex`. Whichever runs second sees the row is not open (AE-28). |
| Restore | Reads return `Unavailable(READ_BLOCKED)` and writes return `Failed(WRITE_BLOCKED)` (AE-18). |
| Empty states | No rules, no expenses or no pairs give `Available([])`. The UI shows nothing (AE-24). |
| Rule update recreates occurrence rows | Declines tied to the old ids are lost, so the suggestion can come back (L-6, safe). |
| Aggregates and rounding | Nothing is split or summed. One expense pays one bill, and the snapshot stores the exact expense amount. |

## 5. Persistence impact

- **Schema: none.** No new table, column or index, and no version bump, migration, snapshot or migration test.
- **New stored values:** event type `LINK_SUGGESTION_DECLINED`, and provenance sources `user_confirmed_match` and `user_declined_match`. Both sources pass `sanitizeProvenanceSource`. They are stored in the existing `PLANNED_FULFILLED` and `REMINDER_SUPPRESSED_*` metadata.
- **DAO:** no new query. The code reuses `occurrenceDao.getByDateRange`, `getById`, `getByLinkedExpenseId`, `expenseDao.getExpensesBetween`, `lifecycleEventDao.getEventsForOccurrence`, and `eventWriter.writeCritical`. If a batch event query is later needed for cost, it requires a `db_access` classification row, which needs owner approval.
- **Guard note from review 2:** DAO calls must be guard-readable, as direct calls on injected DAO properties. Dot-chained calls caused `DB_DAO_SCOPE_UNRESOLVED`.
- **Backfill:** none (INV-17). Nothing is destructive.
- **Backup and restore:** the decline events travel with the occurrence table. If a restore renumbers occurrence ids, the declines are orphaned and suggestions come back. This is the safe direction.

## 6. Privacy rules

- **Stored:** only the decline event. It holds the occurrence id, the expense id and a bounded source code. It holds no merchant, amount, note or date text.
- **Displayed:** the user's own merchant, amount and date, on the user's own screen, read live. Nothing is copied into the suggestion.
- **Exported:** nothing new. If lifecycle events are exported (not verified), the new row adds only ids and a code.
- **Logged:** bounded codes and counts only. The codes are `PAYMENT_LINK_CONFIRMED`, `PAYMENT_LINK_DECLINED`, `PAYMENT_LINK_NO_LONGER_VALID`, `PAYMENT_LINK_ADVANCE_FAILED`, `PAYMENT_LINK_SUGGESTIONS_UNAVAILABLE` and `suggestionCount=<n>`. Nothing else is logged: no merchant, amount, path, SQL message or stack trace.
- The feature is behind no new permission.

## 7. Acceptance examples

The rent fixture applies wherever "rent" is mentioned: rule "Rent", merchant "Acme Properties", 400 EUR, monthly, due Nov 27, 2026. The monthly limit is 1,000 EUR. Spent is 700 EUR, including expense E1, a 400 EUR PURCHASE at "Acme Properties" on Nov 25. Today is Nov 26. The occurrence is materialized.

- **AE-1 (INV-8, INV-12, INV-13, INV-1).**
  - Given the rent fixture.
  - Then: d = -2 is outside A (early 1), so there is no auto link. Suggestions are `Available([ (Nov 27 occurrence, E1, dayDelta -2) ])`. Before any confirm: Monte Carlo base 1,100, Runway 0 CRITICAL, weather discretionary -100, and stress and the calendar show 400 on Nov 27.
  - When the user confirms.
  - Then: the occurrence is PAID with linkedExpenseId E1, the planned row is FULFILLED, reminders are suppressed, and `rule.nextDate` becomes Dec 27. Monte Carlo base 700, Runway 300, weather 300, and stress and the calendar show no item on Nov 27.
- **AE-2 (INV-14, INV-1).** Given AE-1 before the confirm. When the user taps "Not this bill". Then one `LINK_SUGGESTION_DECLINED` event exists with `{expenseId: E1}`. The occurrence stays PLANNED, the forecasts stay at 1,100, 0, -100, and suggestions are `Available([])`.
- **AE-3 (INV-6, INV-8; review 1 blocker).**
  - Given rule A "Rent", 400, due Nov 20, and rule B "Parking", 400, due Nov 25. Both rules use merchant "Acme Properties". E is 400 on Nov 18, and today is Nov 21.
  - Then M(E) = {A (|d| 2), B (|d| 7)}. N(E) = A, so only (A, E) is suggested; B shows no suggestion.
  - When the user confirms, then A is PAID. B stays PLANNED, and stress and the upcoming bills from today show 400 (B), both before and after the confirm.
- **AE-4 (INV-6; review 2 blocker 1).** Given AE-3, except the user tapped "Already recorded" on A, so A is SKIPPED and unlinked. Then N(E) = A, which is not open. There is no auto link and no suggestion, and B 400 is counted everywhere.
- **AE-5 (INV-6, INV-12; review 3 blocker 1).**
  - Given B due Nov 20 and A due Nov 25, both 400 at the same merchant. E is 400 on Nov 18, and A is SKIPPED by "Already recorded".
  - Then N(E) = B, so (B, E) is suggested. B stays PLANNED and counted (400) until the user taps.
  - Second variant: A is due Nov 15, E is on Nov 16, A is SKIPPED, and B is due Nov 20. N(E) = A (|d| 1), so nothing is offered and B 400 is counted.
- **AE-6 (INV-6, INV-7; review 2 blocker 2 and review 3 blocker 2).**
  - Given A 400 and B 420, same merchant, both due Nov 25, and E 400 on Nov 25.
  - Then the ranking is (0, 0) for A against (0, 20) for B, so E is auto-linked to A. A's rule advances to Dec 25. B stays PLANNED and its rule's nextDate stays Nov 25, so Dashboard, weather, stress and the calendar all count 420.
  - Second variant: mark-paid on A creates E', and the post-commit auto match sees E' already linked and does nothing. B still counts 420.
- **AE-7 (INV-4).** Given the rent rule at 400 and an expense 2 days early, on Nov 25:
  - 360, 430 or 440 is suggested;
  - 359.99, 441 or 450 gets nothing;
  - a coffee of 4 gets nothing;
  - a refund of -400 gets nothing.
- **AE-8 (INV-4).** Given rent at 400 EUR, and E is 345 GBP on Nov 25. Then no suggestion and no link, and 400 EUR stays committed.
- **AE-9 (INV-4).** Given rent at 400, paid as 200 on Nov 24 and 200 on Nov 25. Then no suggestion. Spent is 700 and the committed rent is 400, a false warning (L-2).
- **AE-10 (INV-4, installment exclusion).** Given E 400 at "Acme Properties" on Nov 27, and E pays an installment entry. Then no auto link and no suggestion, and the rent stays PLANNED.
- **AE-11 (INV-15).** Given AE-1 after the confirm.
  - Edit the category: the link is kept and the snapshot is updated.
  - Edit the amount to 430: kept, and paidAmount becomes 430.
  - Edit the amount to 450: unlinked. The occurrence is PLANNED and counted (400), with no relink and no suggestion.
  - Edit the date to Nov 15 (d = -12): unlinked, and no suggestion.
- **AE-12.** Given AE-1 after the confirm. When E1 is deleted, the occurrence is PLANNED and counted again. `rule.nextDate` stays Dec 27, which is the existing limit (L-9).
- **AE-13 (INV-6, INV-2).** Given rent auto-linked to E1 on Nov 27 (d = 0), and a duplicate E2 of 400 on Nov 27. Then N(E2) is the PAID rent occurrence, so E2 gets no suggestion and no link, and no other bill is touched.
- **AE-14 (INV-12).** Given the rent occurrence is already PAID by E1. When a confirm for (rent, E3) arrives from a stale screen, the result is `NoLongerValid` and nothing is written.
- **AE-15 (INV-5, INV-8).** Given rule "Gym", 100, weekly on Mondays, with Nov 9 and Nov 16 PLANNED.
  - E is 100 on Thursday Nov 12: d = +3 to Nov 9 is within S 3 but outside A late 2, so (Nov 9, E) is suggested.
  - E is 100 on Friday Nov 13: d = +4 to Nov 9 is out, and d = -3 to Nov 16 is in, so (Nov 16, E) is suggested.
  - A plan parity check, with no new code: on June 8, 2026 (a Monday), limit 1,000, spent 700, weekly 100 paid today. DR-11 auto-links it on the same day, so committed is 300 (June 15, 22 and 29).
- **AE-16.** Given a monthly rule anchored on Jan 31 (Feb 2027 occurrence due Feb 28, Mar 2027 due Mar 31).
  - E on Feb 26 is suggested for Feb 28.
  - E on Mar 3 is auto-linked to Feb 28 (d = +3), not to Mar 31.
- **AE-17.** With the device zone Europe/Athens (DST ends Oct 25, 2026): rule due Oct 27, E on Oct 24 at 23:30 local. Then d = -3, so E is suggested and not auto-linked.
- **AE-18 (INV-11, INV-16).** While a restore is running, suggestions return `Unavailable(READ_BLOCKED)`, and both confirm and decline return `Failed(WRITE_BLOCKED)`. Nothing is written, and the UI hides the section rather than showing "none".
- **AE-19.** Confirm the same pair twice: the result is `Linked`, then `AlreadyLinkedToThis`, with one `OCCURRENCE_PAID` event. Decline twice: `Declined`, then `AlreadyDeclined`, with one event.
- **AE-20.** Given the suggestion from AE-1 is shown, and E1 is deleted. When the user confirms, the result is `NoLongerValid`.
- **AE-21 (INV-1).** Given AE-1 with the suggestion present and no confirm. Then every forecast number equals the value it has when the suggestion feature is absent: 1,100, 0 and -100. A test asserts that the forecast classes have no dependency on the suggestion API.
- **AE-22 (INV-15).** Given AE-1 after the confirm (d = -2). When a bulk category correction runs `reconcileAllLinkedExpensesAfterBulkUpdate`, the link is kept (`updatedSnapshots = 1`, `unlinked = 0`).
- **AE-23 (INV-14, INV-7).** Given E is 400 on Nov 27 and was never auto-linked (post-commit lost), and the user declined (rent, E). When E's note is edited, the reconcile auto relink skips the declined pair and the rent stays PLANNED.
- **AE-24.** With no rules, or no expenses in range, the result is `Available([])` and the reads are bounded (INV-9).
- **AE-25.** Given the rent rule has a blank merchant. Then nothing is suggested and nothing is linked.
- **AE-26.** Given E is 400 dated Nov 28, after today. Then nothing is suggested.
- **AE-27.** Given the rent rule is paused. Then nothing is suggested, and a confirm returns `NoLongerValid`.
- **AE-28.** Given mark-paid and a confirm run at the same time on the rent rule. They are serialized by the per-rule `Mutex`. Exactly one PAID link results, and there is no orphan RECURRING_GENERATED expense from this race.

## 8. Proposed LEGAL_PATHS additions (not edited; for the architecture owner)

```
SUGGEST payment links (FC-PAID-UNLINKED):
  → ViewModel → RecurringLifecycleCoordinator.findPaymentLinkSuggestions()
    (read-only, read barrier, Available | Unavailable(READ_BLOCKED|READ_FAILED); never read by forecast/budget/savings/Radar)
CONFIRM a suggested link:
  → MarkRecurringBillPaidUseCase.linkExistingPayment(ruleId, occurrenceId, expenseId) (per-rule Mutex)
  → RecurringLifecycleCoordinator.confirmPaymentLink() → claimLinkSuppressInWrite(source=user_confirmed_match, pair re-validated in-transaction)
  → advanceRuleAfterAutoLink() (DR-03 rule, NonCancellable, best effort)
DECLINE a suggested link ("Not this bill"):
  → MarkRecurringBillPaidUseCase.declinePayment(...) → RecurringLifecycleCoordinator.declinePaymentLink()
    (one LINK_SUGGESTION_DECLINED event per pair; excluded from suggestions and from auto-link)
LINK expense to occurrence (amend DR-11 text): target = nearest matching occurrence of ANY status within S(P); claimed only if open, within A(P), not declined.
RECONCILE after update (amend): keep window is S(P).
```

## 9. Open decisions (owner)

| ID | Decision | Recommended default |
|---|---|---|
| D-1 | UX surface and copy. Proposal: on the bill row of the Recurring screen, next to Mark paid and Already recorded, the line "Paid already? {amount} on {date}" with buttons [Link] [Not this bill], plus "Different bill" when alternatives exist. On Home, one hint line: "1 bill may already be paid. Review." en/es/el strings; es/el need owner sign-off. | Both surfaces. The Home hint links to the Recurring screen. No forecast numbers change before the tap. |
| D-2 | Widen the auto window A(P), for example monthly to 3 days early. | No. Owner concern 6 (a 38 grocery run settling a 40 bill), and the review history. Suggestions cover it. |
| D-3 | Nearest-owner gate on the auto tier. This changes merged DR-11 behaviour, but only by refusing some auto links: those whose nearest match is SKIPPED, PAID or MISSED, which become suggestions or nothing. It also adds amount closeness as the second tie-break. | Adopt. Every change is in the safe direction. |
| D-4 | Keep window on edit is S(P) for all links (auto, confirmed, mark-paid). Without a schema change, a confirmed link cannot be told apart from an auto link. | Adopt. It is required, or edits silently undo confirms. |
| D-5 | Backfill. Option: a one-time DR-11 sweep over the last 31 days. | No writes. Suggestions surface the existing pairs (INV-17). |
| D-6 | "Not this bill" on an already linked occurrence: unlink plus decline. This needs a bounded rewind of `rule.nextDate`, which is a new path and breaks "nextDate never decreases". Without the rewind, the Dashboard path would undercount, the unsafe direction. | Not in v1. Users edit or delete the expense, as today. Revisit with a rewind design. |
| D-7 | Cross-currency confirm. | No. Same currency only, and no FX in linking. |
| D-8 | `OccurrenceConflictResolver.amountMatches` delegates to the matcher, removing the duplicate rule. | Yes, in slice S1. The logic is identical, so behaviour does not change. |
| D-9 | Undo a decline. | Not in v1. |
| D-10 | Radar redo (`feature/fix104-ao-home-verdicts` 549884f9) resuming after this lands. | Resume after S2 merges. The false warning remains only for pairs nobody has confirmed yet (L-1). |
| D-11 | Window sizes: S capped at 7 days, lookback L = 31 days. | 7 and 31. |

Proposed slices:

- **S1, domain.** Matcher, S window, gate (D-3), keep window (D-4), decline exclusion in the auto tier, resolver delegation (D-8).
- **S2.** Coordinator read, confirm and decline, plus the use case methods.
- **S3.** UI and strings (D-1).

S1 and S2 are money and lifecycle work: a Sonnet writer plus a separate test author, and reviewer-strict before the merge.

## 10. Known limits (remaining after this design)

- **L-1.** Until the user confirms, a bill paid outside A(P) is still counted twice (false warning, safe direction). The Home hint (D-1) mitigates it.
- **L-2.** Partial or split payments, and amount drift beyond ±10%, never match.
- **L-3.** A payment in another currency never matches.
- **L-4.** A late payment beyond the S window (for example, the May bill paid June 3 against a due day of the 8th) cannot be linked to its own occurrence. The way out is "Already recorded".
- **L-5.** Only materialized occurrences are targets. The projection-only rows keep the existing same-day resolver.
- **L-6.** Declines are lost when a rule update recreates occurrence rows, or when a restore renumbers them. The suggestion comes back.
- **L-7.** If the process dies between the confirm commit and the advance, the Dashboard path counts the bill until mark-paid's `AdvancedOnly` self-heal runs.
- **L-8.** A time zone change after generation can shift the day delta by 1.
- **L-9.** After an expense is deleted or unlinked, `rule.nextDate` stays advanced. This is pre-existing, and D-6 is related.
- **L-10.** Two real payments in one week for two bills from the same merchant: the second payment may get no suggestion (gate). The way out is "Already recorded".
- **L-11.** A wrong DR-11 auto link can be undone only by editing or deleting the expense (pre-existing; D-3 makes it rarer).
- **L-12.** Detected patterns that are not rules (FC-HIGH-CONF-UNDERCOUNT) and the savings sweep are out of scope. They pick up the change only through persisted status.
- **L-13.** Matching uses the gross `amount`. A shared bill whose gross amount differs from the user's share does not match.
