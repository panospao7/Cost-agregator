# fix-1.0.4 Batch H: date headers, forecast baseline, cloud prompt truncation

Planner: feature-planner (Sonnet high), anchored at ad61b573. No schema, guard, baseline, allowlist or policy change.

## Triage
- P-03-3: wrong as written. Cancel resets ANALYZING in NonCancellable; nothing reads the status.
- J-07-2: wrong as written. The DAO returns only due snoozed rows.
- E-02-4: wrong as written for AdvancedAnalyticsDashboard; the rest deferred, overlap with `fix3-wt`.
- P-06-4: deferred, overlap with `fix2-wt`. E-03-3 is DR-04, excluded.
- J-18-1 errors: mostly wrong as written; a real small leak of `e.message` into UI state at `SharedExpenseGroupsViewModel.kt:199,228,347`. Next batch.

## Owner decisions, not started
J-11-1 backup redaction toggle; J-11-2 cross-device restore notice; J-12-2 import UI; category delete/rename UI; J-18-1 settle-up UI; J-09-1 briefing trigger.

## Lane A, `Cost-agregator-we`, `feature/fix104-h-headers`, N-08-3 (money, separate test-author)
`ui/screens/transactions/TransactionsScreen.kt:667-672`: the mixed-currency header sums unsigned values. Extract `internal fun dateHeaderTotal`; sign -1 PURCHASE/WITHDRAWAL, +1 DEPOSIT, 0 TRANSFER/UNKNOWN; keep the null rules. Do not change `ComparableHomeValue` (`fix3-wt`).
Filter: `*DateHeaderTotalTest*,*TransactionsScreen*`.

## Lane B, `Cost-agregator-sgd`, `feature/fix104-h-forecast`, J-08-2 code part (money, separate test-author)
`ui/screens/budget/BudgetForecastingScreen.kt:287,245-315`: remaining-period spending is compared with the full limit. Add `projectedPeriodTotal = spentToDate + predictedSpending`; use it for colour and bounds. `spentToDate` is `@Ignore`, so it reads 0 from the DB. ViewModel untouched (`fix2-wt`). Deferred, overlap: a "Spent so far" row.
Filter: `*BudgetForecastProjectionTest*,*BudgetForecasting*`.

## Lane C, `Cost-agregator-rsv`, `feature/fix104-h-prompt`, N-05-3 (privacy, separate test-author)
Orchestrator change to the plan: the planner proposed raising the ITEM_CATEGORIZATION cap from 2000 to 12000. That sends more receipt text to the cloud, so it is rejected. Instead: the prompt puts the output schema first, items are budgeted by whole lines to fit the existing cap, and items not sent stay uncategorised. Cap unchanged.
Filter: `*CloudPayload*,*CloudPiiSanitizer*,*CloudReceiptItemCategorization*`.

## Notes after writers
- J-08-2: "Predicted Spending" stays remaining-period beside shifted Low/Base/High, so base no longer equals the displayed figure. The label needs a string change; `strings.xml` overlaps with `fix2-wt`, so it goes with the "Spent so far" row. Bound shift and colour sit in a private composable with no test seam.
- N-08-3: in a mixed group, a not-mine or neutralised row with `baseAmount` 0 makes the header total null, because `baseAmount > 0` is checked per row first. Existing rule kept.

## Guardian: PASS on all lanes
- Fixed: forecast bound shift now has a pure function and tests; blank description fallback in the prompt.
- Recorded, cloud prompt: with a large category list no item fits and a redacted run returns null, fail-closed but silent; when a receipt is cut, retries re-send the same prefix and later items never progress; about 6 to 8 items fit today because the fixed text takes about 1600 of 2000 chars, so compacting the instructions is the follow-up; `PROMPT_VERSION_RECEIPT_ITEMS` not bumped; the `categorizeItems` entries in `CancellationSafetyArchitectureGuardTest` and `scripts/allowlists/cancellation_allowlist.yml` are now removable; where `needsReview` leads in the UI and what `saveCategorizationResult` does to earlier corrections are unconfirmed.
- Not verified: agreement of the date header sign with the row display.

## Found while testing
Pre-existing: the redactor rewrites "0.7-0.89" in the item prompt's confidence guidance to `[REDACTED_PHONE]`, so the model never sees those ranges when redaction is on. Recorded for the prompt compaction follow-up.

## reviewer-strict on 9318c249: REJECT for Lane C, Lanes A and B acceptable
- MAJOR, budget starves real prompts: with the 20 seeded categories the fixed text is 1817 raw chars, leaving 133 of 1950. 2 to 4 items fit, not 6 to 8. A 10-item receipt sent all 10 before, with "amount" still in the schema. 4 custom categories mean no item fits and every receipt returns null. The orchestrator's refusal to raise the cap caused this.
- MAJOR, partial result is permanent: rows are saved, status goes READY, later runs return `AlreadyAnalyzed`, no caller passes `force`. Before, a failure stayed PENDING. The recorded "retries re-send the prefix" was wrong.
- MINOR: header average counts 0% placeholders; info button on blank rationale; prompt tests use 2 categories.
- MINOR, Lane B: colour wiring unpinned; Base beside "Predicted Spending" is confusing but reconciles.

Decision: Lane C held out of this merge and parked on `feature/fix104-h-prompt`. N-05-3 stays open, owner decision needed on the cap. Lanes A and B ship.

## Owner decision on N-05-3 (item 127): option A
Shorten the instructions, ITEM_CATEGORIZATION cap about 4,000 chars, partial results retry. Own strict review, tests with the real seeded category list. Fix the redactor rewriting "0.7-0.89" in the guidance by rewording it.

## N-05-3 second attempt, plan (feature-planner, Sonnet high), step 1
Lane: `Cost-agregator-rsv`, new branch `feature/fix104-h3-prompt` from 84d25027. The rejected attempt stays parked on `feature/fix104-h-prompt`.

Root cause found: `AppConfig.kt:207` `CLOUD_RECEIPT_ITEM_MAX_TOKENS = 300` fits only 4 to 5 item objects, so the JSON is cut and `parseResponse` returns null. The input cap was never the real limit.

Decisions under item 116, recorded for overrule:
- Reply tokens: 256 + 80 per sent item, with at most 25 items per call. More cost per call, no more data sent.
- Partial result status stays PENDING; `itemCategorizationStatus` has no reader in main.
- Unsent items show the existing "Select category" chip.
- Stale cancellation allowlist entries stay unless static-guards flags them.
- A receipt with about 60 or more long custom categories that fits no item stays deferred.

Step 1 write set (under app/src/main/java/com/yourname/expensetracker/):
1. `data/privacy/DefaultCloudPayloadRedactor.kt`: ITEM_CATEGORIZATION cap 4000 as a shared `internal const`.
2. `data/ai/provider/CloudReceiptItemCategorizationService.kt`: shortened fixed text, schema before items; one prepareText; budget in whole lines with a 100-char margin and at most 25 items; verify-and-shrink loop bounded by item count; parse against sentCount; placeholders for unsent; no tax figure or taxDistribution in the prompt (tax is local).
3. `domain/config/AppConfig.kt`: token formula; `PROMPT_VERSION_RECEIPT_ITEMS` "v2".
4. `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`: READY only when no placeholder remains, else PENDING.
5. `ui/components/ai/ReceiptItemBreakdownCard.kt`: average over non-placeholders, hide badge when none; info button only for a non-blank rationale, as pure internal functions.
6. A new placeholder predicate under `domain/ai/model/`.

Step 2, held for owner: partial retry sending only unsent items needs a new repository write, so new exact rows in `db_ownership_policy.yml` and moved pinned counts.
