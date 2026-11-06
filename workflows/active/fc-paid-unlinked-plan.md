# fc-paid-unlinked: slice plan (feature-planner, 2026-11-06, base e4376a77)

Spec: `docs/features/fc-paid-unlinked/` (`domain-spec.md`, `decisions.md`, `ux-flows.md`, `impact-map.md`). Tier T3. The slices run serially: S1, then S2, then S3. Constraints: no schema change, no permission or cloud change, no new policy rows. If any slice would need one, stop and ask.

## S1: domain (matcher, S window, D-3, D-4, decline exclusion, D-8)
- **New `domain/recurring/lifecycle/RecurringPaymentLinkMatcher.kt`**: a pure object, holding `amountMatches`, `currencyMatches`, `s(P)`, `pairMatches`, `nearestOwner`, `isDeclined` and `LINK_SUGGESTION_DECLINED`. `resolvePeriodDays` is extracted into it.
- **`RecurringLifecycleCoordinator`** (constructor and `claimLinkSuppressInWrite` at :616 unchanged):
  - `linkExpenseToOccurrence` (397-453): widen the read to ±7 days with any status, and select the target with `nearestOwner`. Claim only when N(E) is open, the rule is active, d is inside A(P), and the expense is not declined.
  - `expenseMatchesOccurrence` (975-990): keep the link within S(P).
  - Delete the private `amountMatches` (996-1003).
  - `reconcileExpenseLinkAfterUpdate` (780-855): use S(P) and respect declines.
- **`OccurrenceConflictResolver:153`**: `amountMatches` delegates to the matcher.
- **Tests**:
  - New `RecurringPaymentLinkMatcherTest`, covering INV-5 properties, amount bounds and ranking.
  - D-3 and decline cases in `RecurringAutoLinkWindowLinkTest` or a new `RecurringNearestOwnerGateTest`.
  - Two flips: `:460` weekly moves to +4, and `:477` monthly moves to +8.
  - AE-22 (bulk reconcile, d = -2).
- **Filter**: `*RecurringPaymentLinkMatcherTest *RecurringAutoLinkWindowLinkTest *RecurringNearestOwnerGateTest *OccurrenceConflictResolver* *RecurringLinkTypeEligibilityParityTest *RecurringLifecycleCoordinatorTest *RecurringLifecycleCoordinatorInstallmentLinkPolicyTest *RecurringHorizonAndLinkR21Test *RecurringRuleLifecycleCoordinatorTest *RecurringPlannedActualNoDoubleCountGoldenTest *RecurringPaymentMatchE2ETest *RecurringArchitectureGuardTest`, plus `db_access`.

## S2: coordinator read, confirm, decline, use case
- **New `PaymentLinkSuggestion.kt`.**
- **Coordinator**:
  - Add a `DatabaseReadBarrier` to the constructor (12 fixture files change).
  - `findPaymentLinkSuggestions(now)` returns `Unavailable` with `READ_BLOCKED` or `READ_FAILED`, rethrowing cancellation.
  - `confirmPaymentLink` uses `claimLinkSuppressInWrite` with source `user_confirmed_match`. It revalidates in `selectTarget`, which also returns an already-linked occurrence so the call is idempotent (AE-19), then advances in NonCancellable.
  - `declinePaymentLink` runs `runWrite` plus `withTransaction`, dedupes, then calls `eventWriter.writeCritical` with LINK_SUGGESTION_DECLINED.
- **`MarkRecurringBillPaidUseCase`**: `linkExistingPayment`, `declinePayment` and `findSuggestions`, all under `lockFor(ruleId)`.
- **LEGAL_PATHS**: add the spec section 8 text.
- **Tests**: `RecurringPaymentLinkSuggestionsTest`, `RecurringPaymentLinkConfirmDeclineTest`, `MarkRecurringBillPaidUseCaseLinkTest`, an INV-1 architecture test, and AE-1 and AE-21 forecast parity.
- **Guards**: `db_access` and `static-guards`. If the decline write needs a policy row, stop.
- **After S2**: the Radar redo (549884f9) rebases and re-verifies AE-1: 1,100 before confirm, 700 after.

## S3: UI, strings and ViewModels
- **Code**:
  - `ManualRecurringExpenseViewModel` and `ManualRecurringExpenseScreen`: the suggestion block in `RecurringExpenseCard` (:426).
  - `HomeViewModel` and `HomeScreen`: the hint.
  - `MainActivity:664`: a new callback to `ManualRecurringExpense`.
- **Strings**: 13 keys in en. el and es come from ux-flows §7.2 and are marked DRAFT.
- **Filter**: `*ManualRecurringExpenseViewModel* *HomeViewModel* *ManualRecurringExpenseScreen* *PaymentLinkStrings* *RecurringArchitectureGuardTest`, then `app-check` at the candidate.

## Deferred
- D-6, D-9 and the alternatives picker.
- UX Q4: the Mark as Paid dialog does not warn about a pending suggestion.
