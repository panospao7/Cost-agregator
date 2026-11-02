# fix-1.0.4 closure: verified findings on merge 7b1bb8b1

The owner asked for this on 2026-11-02 (item 112): merge fix-1.0.4-review-findings, but first check that the original fixes are correct and that they introduce no regressions. The checks below were run on 7b1bb8b1 (694bdf15 + 03d519a8; fix commit 8a895772). All three are independent Opus read-only reviews:
- the finding-verifier;
- regression audit A (data/privacy);
- regression audit B (logic/navigation).

Paths are relative to `app/src/main/java/com/yourname/expensetracker/`. Line numbers are approximate; re-anchor before editing.

The original evidence base is `C:\Users\panos\Desktop\Cost-agregator\build\review-wt\build\full-review-2026-11\` (`12-findings-index.csv`, `13a`, `09d`, `05n`, `05e-v`). The lane record is `git show 03d519a8:workflows/active/fix-1.0.4-review-findings-handoff.md`.

## A. Regressions introduced by 8a895772 (must fix before FF)

| ID | Sev | Where | Problem | Proposed fix |
|---|---|---|---|---|
| R-SMS-1 (B-H1) | HIGH | `domain/parser/parsers/SmsParser.kt` `extractBestAmount` `pool.last()` fallback (~L218-224) | If no candidate is next to a purchase-preference keyword, the parser takes the LAST amount; the old code took the first. Example: "Card charged EUR 12.50 at SHOP. Bal EUR 500.00" gives 500.00. The keyword lists miss charged / debit / συναλλαγ (preference) and bal / avl / υπόλ. / υπολ (balance). | Fall back to `nonBalance.first()`. Add the missing EN/EL/ES keywords. |
| R-SMS-2 (B-H2) | HIGH | `SmsParser.kt` `TRANSACTION_KEYWORDS` (~L64-69) and the type rule (~L126-130) | "paid"/"spent" were added as substring matches. Any purchase keyword blocks DEPOSIT, so "Salary credited EUR 1,500.00, paid by ACME" and "Prepaid card credited EUR 50.00" become PURCHASE at 0.85 and AUTO_ACCEPT. | Keep "paid"/"spent" only in `PURCHASE_PREFERENCE_KEYWORDS`, and use word boundaries. |
| R-SMS-3 (B-M1) | MED | SmsParser ambiguity confidence 0.70 + `ConfidenceRouter` ML blend | A recognised balance clause marks the result ambiguous at 0.70. The blend 0.70·0.6 + 0.1·0.4 = 0.46 is below the 0.50 review threshold, so it AUTO_REJECTs; before the fix it went to review. | Keep base confidence at 0.85 for ambiguous results and rely on the router cap (`ConfidenceRouter.kt:~228`, ambiguous → at most NEEDS_REVIEW). Ensure the cap also floors ambiguous results at NEEDS_REVIEW, never AUTO_REJECT. |
| R-SMS-4 (B-M2 + verifier) | MED | SmsParser `bareAmountPattern` fallback (~L47-50) | Currency-less amounts are now accepted, assumed to be EUR, and given 0.85, so they can AUTO_ACCEPT. A dotted date can become an amount: "NBG: card payment due 15.11" gives 15.11. Before the fix: null, then the generic parser at 0.60, then review. | Remove the bare fallback, restoring the pre-fix behaviour. Alternatively mark it ambiguous, which caps it at NEEDS_REVIEW, and reject date-like tokens. Prefer removal. |
| R-SMS-5 (found by closure tests, missed by all audits) | HIGH | `SmsParser.kt` `amountPattern` lookahead | `(?![\d,]|\.\d)`, added by 8a895772, rejects an amount directly followed by a comma, so "EUR 12.50, at SHOP" is dropped. | Fixed in bacd1278: `(?!\d|[.,]\d)`. |
| R-LEARN-1 (A-R-A1) | HIGH | `domain/.../TransactionClassifier.kt` `retrainFromCorrectionsInternal` :193-230, `buildTrainingText` :371-379; `UserCorrectionDao.redactNotificationTextOlderThan` | Legacy `user_corrections.createdAt = 0`, so the first retention run blanks every row. Retraining then sees only `originalMerchant`, which is "Manual" for manual-relevance rows, so positive and negative samples collapse together. The sample count keeps the ML weight at 0.5-0.6, and the biased output drives AUTO_REJECT. | Exclude corrections whose text fields are fully redacted from the retrain set, so the ML weight falls back as samples drop. Add a test: seed, redact, retrain, check predictions and weight. |
| R-EXPORT-1 (A-R-A2) | MED | `data/database/dao/ExpenseDao.kt` `getBusinessExpensesBetween` (~2527), `getBusinessExpensesMissingReceipts` (~2636), feeding `BusinessExpenseReportGenerator` :67, :105, :271 | The new spending predicate removes INSTALLMENT_PURCHASE/REVERSED marker rows. That breaks the loan-installments OD-11/S4d contract: rows must be listed with 0 and a NOT_COUNTED/REFUNDED marker. The AE-1 row count drops from 13 to 12, and missing-receipts loses business installment purchases. `ExpenseExportInstallmentMarkingTest:275-298` hides this because it mocks the repository. | Remove the predicate from these two LIST queries and mark them `// spending-exempt: <REASON>`, using the guard's own exemption mechanism. Totals stay predicate-based. Add a DAO-level (Room) test for the marker rows. |
| R-SWEEP-1 (B-M3) | MED | `domain/.../MonthlySavingsSweepUseCase.kt` :403-408 | A degraded simulator result (`metadata.iterations == 0`, p75 == p50) gives a zero uncertainty buffer, and the conservative fallback is skipped. New users and fits with sigma > 2.0 now land here. | Treat a degraded result (`iterations == 0`, or the result's own degraded flag) like a missing result. Add a test. |
| R-CLIP-1 (B-L1) | LOW | `ui/util/ClipboardAmountParser.kt` :19-22 | The new regex requires a decimal separator, so "€50" no longer pre-fills Quick Add. | Allow plain integers, keeping the multi-amount and balance rejection. Add a test. |
| R-RCPT-1 (A-R-A3) | LOW | `ReceiptItemCategorizationDao` scrub + `ExportAnonymizer.sanitizeReceiptItemCategorizations` | `userCorrectedCategoryName` is nulled. That is over-redaction (RP-12 in `CategorizeReceiptItemsUseCase:304-309` treats category names as safe), and the UI shows "Select category" (`ReceiptItemBreakdownCard.kt:105-123`). | Keep `userCorrectedCategoryName` in both retention and export. Keep scrubbing `itemDescription`, raw text and `alternativeCategoriesJson`. Adjust tests. |

## B. Incomplete closures of the original findings

| ID | Where | Gap | Fix |
|---|---|---|---|
| BL-01 | `domain/forecasting/MonteCarloSpendingSimulator.kt` :121-131 | One quiet-week Bernoulli covers the whole remaining horizon. With more than 50% quiet weeks, P50 shows no further spend (close to the original symptom). | Draw quiet vs active per remaining week. Add a golden test with a high quiet share asserting P10/P50/P90 sanity. |
| FL-02 tests | `app/src/test/.../SmsParserTest`, ConfidenceRouter tests | Nothing references `isAmountAmbiguous`. The router mocks have no boosts. The "multi-amount" test is wrapped in `if (result != null)`, so it can pass vacuously. | Add a ConfidenceRouter test: ambiguous at 0.70 + previous-approval boost + source trust gives NEEDS_REVIEW. Make the vacuous test assert non-null. Add two-amount fixtures (purchase+balance, EN/EL/ES), the R-SMS-1/2/4 repros, and the deposit-with-"paid" repro. |
| DR-05 copy | `res/values{,-el,-es}/strings.xml` `transactions_refund_likelihood_notice` | The advice is "edit or delete the original purchase". If the user does only that, the deposit stays as income and net cash flow is overstated. | Reword it to also delete or convert this deposit (en/el/es; el/es are DRAFT). |
| N-02 subscription query | `ExpenseDao.getRecentExpensesWithCategoryForMerchant` (SUGGESTION exemption) → `NotificationProcessingPipeline.kt:1806` | Reversed and installment-plan rows feed subscription detection. | Apply the spending predicate and drop that SUGGESTION exemption. `getRecentExpensesForMerchant` has no callers: leave it. |

## C. Deferred (recorded; not in this closure)
- P-02-5 second half: `BULK_UPDATED` events write raw merchant/oldMerchant/newMerchant to `transaction_events` metadata, and the export does not scrub them. Needs consumer analysis.
- N-02-1 allowlisted row readers `getExpensesBetweenUncapped` / `getExpensesByTypeBetweenUncapped`, and N-02-4 `searchMerchants` averaging neutralised rows. Need consumer analysis.
- L2: the refund O(D×N) check runs on the main thread per emission (`TransactionsScreen.kt:159-164`).
- R-A4: the receipt retention count omits item rows (diagnostics only).
- `findBetterDeals` is still computed and discarded.
- The SmartSavingsEngine cap multipliers do not scale with the horizon.
- I-01-1 barrier dominance (already deferred by the lane).

## Clean (checked)
- ExpenseRepository `timeProvider` wiring.
- Restore-semantic-verify: no false mismatch from the scrubs.
- PII bounded reasons: no code values changed.
- BL-11 gating: no dead ends or loops.
- No NaN/Infinity from forecasting.
- The stress card does not use the simulator.
- `isAmountAmbiguous` default and producers.
- Strings merge.
