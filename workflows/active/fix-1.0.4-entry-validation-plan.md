# fix-1.0.4 Batch F: entry validation and lifecycle correctness

Planner: feature-planner (Sonnet high), anchored at e730c9a0. All findings re-anchored and still hold. No Room schema change. No guard, baseline or allowlist change. Paths are under `app/src/main/java/com/yourname/expensetracker/`.

Lanes are based on f3aac5e9, which the follow-up candidate does not conflict with.

## Decisions (owner item 116: recommended defaults taken; recorded for overrule)
1. B2: manual savings contributions move to the existing `savings_contribution_ledger`, a legal-path change. Approved by default. Stop if it needs a guard, baseline or allowlist change. `LEGAL_PATHS.md` updates in the same slice. Guardian required.
2. A4: a home-currency change discards a foreign draft only in the collision case (the picked foreign currency equals the new home).

## Lane A, `Cost-agregator-we`, `feature/fix104-f-entry` (money, separate test-author, T2)
Write set: `ui/screens/receiptscan/ReceiptScanViewModel.kt`, `ui/screens/addexpense/AddExpenseViewModel.kt`. Tests: `ReceiptScanViewModelTest`, `ReceiptScanViewModelMecC1Test`, `AddExpenseViewModelTest`, `AddExpenseViewModelHomeChangeTest`.
- A1 N-01-1, `ReceiptScanViewModel:2175-2183`: tax-inclusive save substitutes raw `parsedReceipt.total.amount` for the validated request amount. Remove the substitution.
- A2 N-01-2, `AddExpenseViewModel:758-770`: `myShareAmount` skips `CurrencyAmountPolicy`. Check after parse; failure blocks save.
- A3 N-01-3, `AddExpenseViewModel:372`, `ReceiptScanViewModel:390`: raw `homeCurrency()` collection with no catch or timeout. Use `observeReadHomeCurrency()`, 5s timeout, Unavailable keeps save blocked. Fixture risk: receipt tests stub only `homeCurrency()`.
- A4 J-02-2, `AddExpenseViewModel:377-378`: also at risk when `currencyOverride?.code == hc`.
Filter: `*ReceiptScanViewModel*,*AddExpenseViewModel*`.

## Lane B, `Cost-agregator-sgd`, `feature/fix104-f-savings`
Write set: `ui/screens/savings/SavingsGoalsViewModel.kt`, `ui/screens/savings/SavingsGoalsScreen.kt`, `ui/screens/subscription/SubscriptionManagementScreen.kt`, `domain/naturallanguage/NaturalLanguageSearchEngine.kt`, `data/repository/SavingsGoalRepository.kt`, `domain/savings/SavingsGoalRepository.kt`, `docs/architecture/LEGAL_PATHS.md`.
- B1 E-02-1b (money, T2), `SavingsGoalsScreen:319`, `SavingsGoalsViewModel:405`: quick add always adds 10.0. New `quickAdd(goalId)` adds min(10.0, remaining), no-op at <= 0.
- B2 E-02-1 (money, T2/T3), `SavingsGoalsViewModel:439-451`: Room increment and DataStore history are not atomic. New `recordManualContribution(goalId, delta, expectedSnapshot, idempotencyKey)`: one `withTransaction`, `addToGoalAmountIfSnapshot` plus ledger insert with source "manual", same write barrier as the sweep coordinator. Legacy DataStore events stay read-only.
- B3 J-19-1 (T1), `SubscriptionManagementScreen:133`: empty state wins over detected candidates. `subscriptions.isEmpty() && detectedCandidates.isEmpty()` via pure `showEmptyState`.
- B4 FL-03 (T1), `NaturalLanguageSearchEngine:85-89,487-492`: date digits read as amounts. Mask dd/mm/yyyy and ISO date spans before `extractAmounts`.
Filter: `*SavingsGoalsViewModel*,*SavingsGoalRepository*,*SubscriptionScreenState*,*NaturalLanguageSearchEngine*`. Guard: `db_access`.

## Lane C, `Cost-agregator-rsv`, `feature/fix104-f-normaliser`
Write set: `domain/intelligence/ml/MerchantNormalizer.kt`, `domain/ai/usecase/CategorizationAssistInputBuilder.kt`, `domain/usecase/expense/CategorizeExpenseUseCase.kt`, `data/repository/NotificationProcessingPipeline.kt`, `domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`, new `domain/recurring/lifecycle/InvalidRecurringAmountException.kt`.
- C1 E-03-1 (T2), `MerchantNormalizer:98-100`: fuzzy link runs with `autoCreate = false`. Gate on `autoCreate`.
- C2 E-03-2 (privacy-adjacent, T2), `CategorizationAssistInputBuilder:48,96`, `CategorizeExpenseUseCase:13`, `NotificationProcessingPipeline:1139`: normalise writes before routing. First three pass `autoCreate=false`; pipeline passes `autoCreate = shouldPrepareAcceptedOrReviewData`.
- C3 recurring-amount-validation (money, T2/T3), `RecurringRuleLifecycleCoordinator:164,516`: no amount check. `CurrencyAmountPolicy.check` before writes; `InvalidRecurringAmountException` with a bounded code; `updateRule` validates only when amount or currency changes, so legacy rows keep working.
Filter: `*MerchantNormalizerTest,*CategorizeExpenseUseCaseTest,*CategorizationAssistInputBuilderTest,*NotificationProcessingPipeline*,*RecurringRuleLifecycleCoordinatorTest`. Guard: `db_access`.

## Deferred
- Overlap: the `RecurringExpensesScreen.kt` confirmPattern message (owned by `fix2-wt`). The coordinator guard still covers that call.
- Share amount above total; other bare-number NL contexts such as "last 3 months"; `ManualRecurringExpenseScreen.kt` UI pre-check.
- Left out of this batch: E-04-1, P-07-1, P-07-2.

## Gates
Targeted per lane, combined guardian per lane, `db_access` once, then one candidate: static-guards, reviewer-strict, app-check.

## Changes after the writers reported (orchestrator)
- **B2 E-02-1 stopped, not implemented.** `config/guards/db_ownership_policy.yml` is an exact-policy table, so a new `SavingsGoalRepository.recordManualContribution` needs new rows, a guard policy change. Waiting on the owner. Reader analysis was favourable: `sweepPlanId` is nullable, `hasSweepBetween` filters `source='sweep'`, `getAllContributions` merges both sources, no schema change. Pattern to copy: `SavingsSweepAcceptanceCoordinator.accept`.
- **B4 FL-03 dropped: the finding is wrong as written.** `amountPattern` is a multi-line raw string with no COMMENTS flag, so it needs literal newlines around the digits and never matches a one-line query. Checked with the same regex on "spent on 15/03/2026", "coffee 15", "over EUR 50" and "EUR 50": no match. The date-search symptom does not exist. The code change was backed out; two tests pin that date searches carry no amount.
- **New finding FL-03R (open):** typed amount filters such as "over 50" appear never to apply, because no amount is ever extracted. Fixing it makes filter code at `NaturalLanguageSearchEngine.kt:375-420` live for the first time, so it needs its own slice: single-line regex, mask dates, require a currency marker or comparison keyword.
- **C3:** `PrecisionExceeded` is accepted because detected amounts are unrounded averages. `RecurringExpensesScreen.confirmPattern` added to Lane C's write set, inside that one function, because the new exception would crash there. The other session's hunks in that file are at ~:393-457.
- **A3/A4:** a failed home read must not count as a home change. Draft discard requires a known new home.
- **Recorded residuals:** quick save passes `preview.amount` unchecked by the currency policy; `isAmountEditedByUser` is now unused in main code.

## Guardians
- Lanes B and C: PASS. Fixed from it: three docs and `LEGAL_PATHS.md` categorisation text on `normalize()`, the alias-conflict log (raw merchant name removed), the double-tap test now suspends the write so it pins the lock.
- Lane A: PASS. Fixed from it: stale comment on the home-change field.
- Recorded residuals:
  - A quick add can still interleave with a dialog contribution; only quick add is serialised.
  - After a home READ_ERROR the flow ends, so home stays unknown until the view model is recreated. Fail-closed; the draft is kept.
  - `isAmountEditedByUser` is written but no longer read in main code; `readHomeCurrencyCode` is duplicated in two view models.
  - `createPlaceholder` returns the cleaned name while a created canonical uses `formatDisplayName`, so casing can differ between rejected and accepted notifications (unproven).
  - Debug legacy migration now counts zero or oversized rules as failed rows (`service/debug/LegacyDataMigrationService.kt`).

## B2 after owner approval (item 124)
- Implemented on `feature/fix104-f-savings`. `recordManualContribution` writes balance and ledger row in one transaction. Two exact policy rows. `db_access` vr-20261103-155341-d7d5b150 PASS on ff323d79.
- Guardian PASS. Fixed from it: SnapshotMismatch and Rejected now show `savings_contribution_not_added` (el/es DRAFT); four tests renamed.
- Recorded:
  - Manual ledger rows are not pruned, unlike DataStore events (730 days or 5000), so a zero-balance goal with manual history stays undeletable and the table grows slowly.
  - `contributionHistoryWriteFailure`, `incrementSavingsGoalAmount` and the view model's history repository parameter are now dead. Left in place to keep the diff small.

## Candidate
`feature/fix104-f-candidate` 3858e62a from 28d80002. Targeted: A vr-20261103-150221-61ecac8f FAIL then vr-20261103-152046-febd2282 PASS on corrected classes; C vr-20261103-145330-81eb91bb FAIL then vr-20261103-152318-b87e49b3 PASS on the corrected class; B vr-20261103-144812-0544812d PASS before B2.

## reviewer-strict REJECT on 3858e62a (review 1 of 2)
- MAJOR, receipt scan: a null home dropped the pending OCR proposal, so USD 50 could save as EUR 50 after a slow read. Fix on Lane A.
- MAJOR, pipeline: a salvaged bank notification normalised with `autoCreate=false`, so review and the approved expense kept raw casing and `merchant_stats` skipped. Fixed on Lane C.
- MINOR: a read error blocked Add Expense for the life of the Activity (Lane A); quick add label and floating-point dust (Lane B); total card "conversion unavailable" with only candidates (Lane B).
- Pre-existing gap recorded: a salvaged notification still gets null category, direction and account, so review shows no category suggestion.

## reviewer-strict ACCEPT-WITH-MINORS on 6b60352a (review 2 of 2)
All five issues from review 1 closed. Minors go to follow-up lanes, not this merge:
1. A goal with dust below target gets no Done badge and no button (0.7999999999999999 of 0.80).
2. Quick add label uses `toPlainString()`, ignoring locale ("+2.50" beside "97,50 €").
3. Receipt scan: READ_ERROR during REVIEW with USD pending makes Save say "Please wait" indefinitely; only reset restarts the read, and reset wipes edits.
4. Receipt scan: no home-unknown branch for the quick-save reason text, so it points at AI fields.
Targeted after fixes: rsv vr-20261103-162100-38527f9a, sgd vr-20261103-162718-cb42342a, we vr-20261103-163322-48a77046 (638 of 640 passed, two test corrections) and vr-20261103-164040-9586ea36.
