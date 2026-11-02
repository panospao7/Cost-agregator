# Plan: fix-1.0.4 closure (findings sections A + B)

- **Scope:** `workflows/active/fix104-closure-findings.md`. **Base:** merge `7b1bb8b1`.
- **Authority:** owner item 112. Plan decisions made by the orchestrator under item 101, flagged to the owner:
  - The classifier skips fully redacted corrections.
  - Business list queries are spending-exempt per OD-11.
  - `getBusinessExpensesBetweenFlow` gets the same treatment as its sibling list queries.
  - The `ROW_COUNT` reason is used for these exemptions.
- **Planner:** feature-planner (Sonnet high), 2026-11-02.
- **Risk tier:** T2. There is no schema change and no guard or baseline edit.
- **Paths:** source paths are relative to `app/src/main/java/com/yourname/expensetracker/`; test paths are relative to `app/src/test/java/com/yourname/expensetracker/`.

## Re-anchor (at 7b1bb8b1)
- **SmsParser.kt:**
  - `pool.last()` is at L225. `TRANSACTION_KEYWORDS` is at L64. `bareAmountPattern` is at L47. The ambiguity constant is at L265.
  - There are no ES keywords or senders.
- **`domain/intelligence/ConfidenceRouter.kt`:**
  - The ambiguity cap is at L228. It fires only when `adjustedConfidence >= REVIEW_THRESHOLD`; it is not a floor. That is the R-SMS-3 cause.
- **`domain/usecase/savings/MonthlySavingsSweepUseCase.kt`:**
  - The buffer is at L402-408. There is no `iterations == 0` check.
  - `MonteCarloResult` has no degraded flag, only `metadata.iterations`.
- **ExpenseDao:**
  - `getBusinessExpensesBetween` is at L2533. `getBusinessExpensesBetweenFlow` is at L2543. `getBusinessExpensesMissingReceipts` is at L2642.
- **Receipt and correction redaction:**
  - `ReceiptItemCategorizationDao` L113-126 and `ExportAnonymizer` L408-425 null out `userCorrectedCategoryName`.
  - `UserCorrectionDao.redactNotificationTextOlderThanInternal` is at L111.
- **Spending-exempt marker** (from `scripts/verify_spending_predicate_usage.py`):
  - It is written `// spending-exempt: <REASON>`, on its own line directly above `@Query`. Only annotations or line comments may sit in between.
  - Valid REASON values: DEDUPE_BLOCKING, DEDUPE_RESOLVER, NON_SPENDING_INCOME, ROW_COUNT, NON_MONEY, SUGGESTION, ASSET_VALUE.
  - A marker plus a `SpendingInclusionSql` reference is a violation (MARKER_WITH_REFERENCE).
  - A new REASON or an allowlist change is a guard edit, which is a STOP.

## Lane P: `Cost-agregator-qf`, branch `feature/fix-1.0.4-closure`
### P1. SMS parser (R-SMS-1..4, FL-02 tests). Writer + test-author.
**Superseded for FL-02:** the SmsParser design was replaced by owner item 113. See `workflows/active/fix104-closure-findings.md`, "Outcome for FL-02".
- **Source:** `domain/parser/parsers/SmsParser.kt`.
- **Tests:**
  - `domain/parser/SmsParserTest.kt`
  - `domain/intelligence/ConfidenceRouterTest.kt`
  - NEW `domain/parser/SmsParserAmbiguityTest.kt`
- **Change:**
  - Amount fallback:
    - The fallback becomes `pool.first()`.
    - Remove `bareAmountPattern` and its fallback block.
  - Keyword lists:
    - `BALANCE_KEYWORDS` gains `bal`, `avl`, `υπόλ`, `υπολ`, `saldo`. Do not add a trailing dot.
    - `PURCHASE_PREFERENCE_KEYWORDS` gains `charged`, `debit`, `συναλλαγή`, `συναλλαγη`, `synallagi`, `compra`, `pago`, `cargo`.
    - Reuse the balance list in the trailing-keyword regex (L249).
    - Remove `paid` and `spent` from `TRANSACTION_KEYWORDS`. They stay in the token-bounded preference list.
  - Ambiguity confidence:
    - The ambiguity constant becomes `0.85f`. The router cap demotes (0.85·0.6 + 0.1·0.4 = 0.55 ≥ REVIEW_THRESHOLD).
    - Add no floor; a floor would override the anti-spam rejection signals.
  - No ES words in `TRANSACTION_KEYWORDS`; that would be a new accepted-input path.
- **Acceptance:**
  - "Card charged EUR 12.50 at SHOP. Bal EUR 500.00" parses as 12.50 and is not AUTO_ACCEPT. The EL equivalent ("Χρέωση 12,50 EUR … Υπόλ. 500,00 EUR") parses as 12.50.
  - "Salary credited EUR 1,500.00, paid by ACME" is a DEPOSIT. So is "Prepaid card credited EUR 50.00".
  - "NBG: card payment due 15.11" returns null.
  - Purchase + balance in either order gives the purchase amount, ambiguous, routed to NEEDS_REVIEW.
  - Ambiguous at 0.85 with ML ready at 0.1 routes to NEEDS_REVIEW, not AUTO_REJECT.
  - Ambiguous with a previous-approval boost and source trust (adjusted ≥ 0.85) routes to NEEDS_REVIEW.
- **Test corrections:**
  - The vacuous "multi-amount SMS" test (L372-384) must assert non-null.
  - List every currency-less fixture from 8a895772 that pinned bare-amount acceptance, and correct it.
  - Check that `ConfidenceRouterTest:54` is unaffected.
  - Check the e2e `NotificationParserDashboardTotalSeam` / `NotificationExpenseDashboardE2E` and `AppParserRegistry*Test` pins.
- **Filter:** `*SmsParser*,*ConfidenceRouter*,*AppParserRegistry*,*NotificationParserDashboardTotalSeam*,*NotificationExpenseDashboardE2E*,*NotificationProcessingPipeline*`

### P2. Export and subscription list queries (R-EXPORT-1, N-02). Writer + test-author.
**Superseded:** the business listings use `(SPENDING_PREDICATE OR installment markers)`, not `ROW_COUNT` exemptions. See the findings file.
- **Source:** `data/database/dao/ExpenseDao.kt`.
- **Tests:**
  - `data/database/dao/ExpenseDaoTest.kt` (Robolectric, real Room).
  - `NotificationProcessingPipelineReliabilityTest.kt`, only if a mock signature needs it.
  - `ExpenseExportInstallmentMarkingTest` is NOT touched.
- **Change:**
  - Business list queries:
    - Remove the spending predicate from `getBusinessExpensesBetween`, `getBusinessExpensesBetweenFlow` and `getBusinessExpensesMissingReceipts`.
    - Add `// spending-exempt: ROW_COUNT` above each. Totals stay predicate-based.
  - Merchant queries:
    - `getRecentExpensesWithCategoryForMerchant` gains `${SpendingInclusionSql.SPENDING_PREDICATE}` and loses its SUGGESTION marker.
    - `getRecentExpensesForMerchant` is unchanged.
- **Acceptance:**
  - Business INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows are listed by `getBusinessExpensesBetween` and by missing-receipts, with `countsAsSpending = false`.
  - The business totals query still excludes them.
  - Merchant history for subscription detection excludes them.
- **Filter:** `*ExpenseDaoTest*,*BusinessExpenseReportGenerator*,*ExpenseExportInstallmentMarking*,*NotificationProcessingPipelineReliability*,*DbGuard*`

**Order and validation:** P1, then P2. Run the combined filter, then `unit-test-shard data`.

## Lane D: `Cost-agregator-we`, branch `feature/fix-1.0.4-closure-d` (merged into P at the end)
### D1. Monte Carlo per-week quiet draw (BL-01). Writer + test-author.
- **Source:** `domain/forecasting/MonteCarloSpendingSimulator.kt`.
- **Tests:** `MonteCarloSpendingSimulatorTest.kt`, `MonteCarloSpendingSimulatorGoldenTest.kt`.
- **Change:**
  - Keep the single `nextGaussian()` stream.
  - For each remaining week, draw quiet or active from `zeroProbRandom`. Weight the last week by `min(1, remainingDays/7)`.
  - Set `activeFraction = Σ weight·(1−zero)`, which replaces `fractionOfWeek`.
  - With `zeroWeekProbability == 0` the path must be bit-identical to today.
- **Golden expectations:**
  - Both golden fits use the default `zeroWeekProbability` 0.0, so they stay unchanged.
  - The `bl01 regression` test (25% quiet) keeps P50 ∈ [100,260] and P90 ∈ [130,450].
  - If any golden value does change, derive it analytically (P50 ≈ k·100, with k from Binomial(weeks, p)), comment the derivation, and keep tolerance ≤ 1.0.
- **Acceptance:** 60% quiet with 14 days left gives P10 = 0, P50 > 0 (≈100), P90 ≈ 200, and P10 ≤ P50 ≤ P90.
- **Filter:** `*MonteCarloSpendingSimulator*,*GetMonteCarloBudgetImpact*`

### D2. Savings sweep with a degraded result (R-SWEEP-1). Writer + test-author.
- **Source:** `domain/usecase/savings/MonthlySavingsSweepUseCase.kt`.
- **Tests:** `MonthlySavingsSweepUseCaseTest.kt`.
- **Change:** `calculateRiskBuffer` treats `monteCarloResult == null || metadata.iterations == 0` as the conservative fallback.
- **Acceptance:** a result with `iterations == 0` and p75 == p50 produces the non-zero fallback buffer.
- **Filter:** `*MonthlySavingsSweep*`

### D3. Retraining on redacted corrections (R-LEARN-1). Writer + test-author.
- **Source:** `domain/intelligence/TransactionClassifier.kt`.
- **Tests:** `TransactionClassifierTest.kt`, plus `UserCorrectionDaoTest.kt` if a DAO round-trip is needed.
- **Change:**
  - In `retrainFromCorrectionsInternal`, keep only corrections whose `notificationTitle` or `notificationText` is non-null.
  - Apply that filter before the `MIN_TRAINING_SAMPLES` check and before `lastTrainingCount`.
  - If too few rows are left, the method returns early and the previous model is kept.
- **Acceptance:**
  - 20 or more corrections, all redacted, then retrain: weights and predictions are unchanged, and no "Manual" merchant samples are added.
  - With a mixed set, only the unredacted rows train.
- **Filter:** `*TransactionClassifier*,*UserCorrectionDao*,*ConfidenceRouter*`

### D4. Receipt item category names (R-RCPT-1). Writer + test-author.
- **Source:**
  - `data/database/dao/ReceiptItemCategorizationDao.kt` (L113-126): drop the `userCorrectedCategoryName = NULL` assignment and its OR clause.
  - `data/privacy/ExportAnonymizer.kt` (L408-425): the same change.
- **Tests:** `ExportAnonymizerTest.kt`, `RetentionTargetPurgeTest.kt` (L404), `RetentionTargetCoverageGapTest.kt`.
- **Test corrections:**
  - Assertions that expected `userCorrectedCategoryName` to be null must now expect it preserved. List each one.
  - `itemDescription`, the raw text and `alternativeCategoriesJson` must still be scrubbed.
- **Filter:** `*ExportAnonymizer*,*RetentionTarget*,*ReceiptItemCategorization*`

### D5. Clipboard integers and refund copy (R-CLIP-1, DR-05). One writer.
- **Source:**
  - `ui/util/ClipboardAmountParser.kt`.
  - `res/values{,-el,-es}/strings.xml`. Lane D owns these; lane P never touches them.
- **Tests:** `ui/util/ClipboardAmountParserTest.kt`.
- **Change:**
  - Clipboard parsing:
    - Accept an integer only when a currency symbol or code is adjacent (`€50`, `50 EUR`). A bare integer is rejected (phone numbers, dates).
    - Keep the multi-amount and balance rejection.
  - Refund copy: reword `transactions_refund_likelihood_notice` to also say "delete or convert this deposit". The el/es versions are DRAFT.
- **Filter:** `*ClipboardAmountParser*`

**Order and validation:** D1 → D5. They are independent of each other. Run the combined filter, then shards `domain-a-m` and `runtime-ui`.

## Final candidate (merged closure tip)
1. `static-guards`, then `app-check`.
2. Re-run the targeted filter `*SmsParser*,*ExpenseDaoTest*,*MonteCarloSpendingSimulator*,*TransactionClassifier*,*ExportAnonymizer*`.
3. Re-audit with a fresh regression auditor on the closure diff.
4. reviewer-strict (Opus xhigh) gives final acceptance.
5. Fast-forward feature/ai-evolution.

## Stop-and-ask
- A new spending-exempt REASON, or any allowlist or baseline edit.
- ES words in `TRANSACTION_KEYWORDS`.
- A router floor that overrides the anti-spam signals.
- Golden changes beyond the analytically derived values, or a tolerance above 1.0.
- Anything in findings section C.
- The el/es refund copy is DRAFT and needs release-gate sign-off (item 103).
