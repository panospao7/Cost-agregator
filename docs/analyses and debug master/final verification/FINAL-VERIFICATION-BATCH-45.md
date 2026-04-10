# Final Verification — Batch 45: Receipt, Savings & Tax

> **[RESOLVED BY A.1]** The `effectiveAmount` vs `amount` inconsistency has been standardized across the codebase. All related issues in this batch are now resolved.

## Scope
- `com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`
- `com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
- `com/yourname/expensetracker/domain/receipt/ReceiptSource.kt`
- `com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt`
- `com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt`
- `com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt`
- `com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt`
- `com/yourname/expensetracker/domain/savings/SavingsGoalRepository.kt`
- `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`
- `com/yourname/expensetracker/domain/tax/TaxConfiguration.kt`
- `com/yourname/expensetracker/domain/tax/TaxEstimator.kt`
- `com/yourname/expensetracker/domain/service/NotificationService.kt`
- `com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`
- Supporting integration files read during verification:
  - `com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
  - `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
  - `com/yourname/expensetracker/data/repository/BudgetRepository.kt`
  - `com/yourname/expensetracker/data/repository/BusinessExpenseRepository.kt`
  - `com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt`
  - `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - `com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`
  - `com/yourname/expensetracker/data/database/entity/Expense.kt`
  - `com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt`
  - `com/yourname/expensetracker/domain/budget/BudgetModels.kt`
  - `com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`
  - `com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt`
  - `com/yourname/expensetracker/domain/model/SavingsGoal.kt`
  - `com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`
  - `com/yourname/expensetracker/domain/util/StringDistanceUtils.kt`
  - `com/yourname/expensetracker/domain/util/TimeProvider.kt`
  - `com/yourname/expensetracker/domain/util/SystemTimeProvider.kt`
  - `com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
  - `com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt:455-458,626-630` | Medium | Race condition | `recognizeText()` serializes recognizer access with `recognizerMutex`, but `close()` uses a different lock. The shared ML recognizer can therefore be closed/reset while an OCR call is still active. | R | CONFIRMED | Make `close()` coordinate with `recognizerMutex` or use active-operation reference counting before closing the recognizer. |
| 2 | `com/yourname/expensetracker/domain/receipt/ReceiptParser.kt:84-105,618-653` | Medium | Parsing logic | Line-item extraction runs overlapping patterns over the entire OCR blob, so quantity-formatted lines can be added twice; patterns 3 and 4 are declared but never executed. | B | DOWNGRADED | Parse line-by-line with exclusive precedence, dedupe matched lines, and execute all declared item formats. |
| 3 | `com/yourname/expensetracker/domain/receipt/ReceiptParser.kt:150-153` | Medium | Incorrect calculation | When subtotal is missing, the parser blindly computes `total - tax`, which can produce impossible negative subtotals when OCR tax extraction is wrong. | R | CONFIRMED | Accept computed subtotal only when it falls in a sane range such as `0.0..total`. |
| 4 | `com/yourname/expensetracker/domain/receipt/ReceiptParser.kt:745-757` | Low | Error handling | `lineItemsFromJson()` swallows deserialization failures and returns `emptyList()`, hiding corrupted payloads and making recovery/debugging difficult. | R | CONFIRMED | Log the parse failure and preserve the raw payload for diagnostics or fallback handling. |
| 5 | `com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt:127-138` | Medium | Date parsing | Shared `SimpleDateFormat` instances are left lenient, so impossible OCR dates can be normalized into different “valid” dates instead of being rejected. | R | CONFIRMED | Set every formatter to `isLenient = false` or switch to `java.time` parsing. |
| 6 | `com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt:144-149` | High | Business logic | `isReasonablePurchaseDate()` rejects any receipt older than one year, blocking legitimate 2–5 year warranty receipts from auto-creation or review-draft creation. | B | CONFIRMED | Reject only future/absurdly old dates, or align the allowed age window with supported warranty durations. |
| 7 | `com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt:30-38` | High | Thread safety | `WarrantyTextExtractor` stores shared `SimpleDateFormat` instances, and the singleton warranty pipeline reuses one extractor across parallel batch imports even though `SimpleDateFormat` is not thread-safe. | B | CONFIRMED | Replace the shared formatters with immutable `java.time` formatters or create formatter instances per parse call. **[RESOLVED BY A.8]** |
| 8 | `com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt:57-59,107-110` | High | Matching logic | Candidate filtering and type scoring treat any positive-amount transaction as receipt-compatible, so deposits/transfers can be suggested or auto-matched to receipts. | B | CONFIRMED | Restrict candidate eligibility and type scoring to `PURCHASE` or an explicit receipt-compatible allowlist. |
| 9 | `com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt:44,96-99,120-124` | High | Matching logic | Merchant normalization strips everything outside `[a-z0-9]`, collapsing Greek merchant names to empty strings. Two unrelated Greek merchants can then score as perfect matches, and the injected `MerchantNormalizer` is ignored entirely. | B | CONFIRMED | Normalize through `MerchantNormalizer`/`MerchantKeyGenerator` and compare canonical multilingual keys instead of ASCII-only stripping. |
| 10 | `com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt:55-76,214-247` | High | Rule execution | `WEEKLY_NO_SPEND` ignores the triggering expense and has no per-week idempotency. Re-running `evaluateRules()` during the same qualifying week can grant the same €10 reward repeatedly. | B | CONFIRMED | Persist week-level execution state and evaluate the rule once per completed week, not once per event. |
| 11 | `com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt:52-53,250-305` | High | State persistence | Monthly cap tracking lives only in the in-memory `monthToDateRuleTotals` map, so app restart/process death resets used-cap state and allows over-saving beyond `maximumPerMonth`. | B | CONFIRMED | Persist rule executions/cap usage or derive month-to-date consumption from durable savings-transfer history. |
| 12 | `com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt:94-107` | Medium | Validation | Percentage rules accept negative, `NaN`, or infinite percentages and can emit invalid transfer amounts and malformed reasons. | R | CONFIRMED | Validate `percentage` as finite and within a sensible configured range before using it. |
| 13 | `com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt:45-69` | High | Business logic | Streaks are fabricated from `goal.createdAt` and a hard-coded `5`-day placeholder instead of real contribution history, so returned streaks, last-savings dates, and monthly contribution counts are not authoritative. | B | CONFIRMED | Back streaks with persisted savings-contribution events and compute them from real contribution timestamps. |
| 14 | `com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt:91-143` | Medium | Data consistency | `unlockedAt` is recomputed as `timeProvider.now()` on each call, so already-unlocked achievements look newly unlocked forever. | R | CONFIRMED | Persist unlock timestamps/progress and return the stored unlock time instead of recomputing it on read. |
| 15 | `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt:77-85` | High | Aggregation | `calculateBudgetSurplus()` sums every positive remaining budget. If an overall budget and category budgets coexist, the same available headroom is counted multiple times. | B | CONFIRMED | Apply a single headroom policy, e.g. prefer an overall budget or otherwise sum only non-overlapping category budgets. |
| 16 | `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt:44-74,237-260` | High | Allocation | `calculateSafeToSaveAmount(goal)` computes one portfolio-wide safe amount per call; `goal` affects only the message, and the result is not capped by the goal’s remaining balance. Multiple goals can therefore each be told the full available amount. | B | CONFIRMED | Separate total safe-to-save capacity from per-goal allocation/capping, and clamp by remaining target and priority. |
| 17 | `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt:158-174` | Medium | Incorrect formula | `monthlyDiscretionary` divides by a fixed `3.0` whenever any history exists, even for new users or partial windows, which distorts the Monte Carlo contribution. | R | CONFIRMED | Divide by the actual covered months/days of data instead of a hard-coded three months. |
| 18 | `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt:186-197` | High | Model misuse | Week and quarter recommendations scale a month-end Monte Carlo forecast by `0.25`/`3.0`, even though the simulator models the current month only. | R | CONFIRMED | Use the simulator only for monthly recommendations or add horizon-specific forecasting inputs/models. |
| 19 | `com/yourname/expensetracker/domain/tax/TaxEstimator.kt:42-44,62-64,82-92` | High | Incorrect calculation | The estimator selects a single bracket rate and applies it to all taxable income even though `TaxConfiguration` exposes progressive brackets. | B | CONFIRMED | Compute marginal tax across all brackets instead of applying one flat rate to all taxable income. |
| 20 | `com/yourname/expensetracker/domain/tax/TaxEstimator.kt:59-75` | High | Period math | `estimateTaxes()` collapses any non-zero period to one month of income (`estimatedAnnualIncome / 12`) while subtracting expenses for the full requested period. Multi-month and partial-month estimates are internally inconsistent. | B | CONFIRMED | Prorate income to the actual period length and keep all returned totals on the same period basis. |
| 21 | `com/yourname/expensetracker/domain/tax/TaxEstimator.kt:100-128` | High | Incorrect annual summary | `getTaxYearSummary()` hardcodes annual income to `30000.0` and annualizes values that already came from a full-year query, producing fabricated year summaries. | B | CONFIRMED | Feed real annual income into the summary path and stop multiplying already annual values by 12. |
| 22 | `com/yourname/expensetracker/domain/tax/TaxEstimator.kt:48-57` | High | Scope / truncation | VAT paid is computed from all purchases, not business-only purchases, and `expenseDao.getExpensesBetween(...)` silently uses the DAO’s default `LIMIT 2000`, truncating large periods. | R | CONFIRMED | Use a business-only aggregate query and page/aggregate all rows instead of relying on the default-limited list API. **[RESOLVED BY A.9]** |
| 23 | `com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt:27-38,75-77` | Medium | Error handling | Any exception returns `Result.retry()`, including permanent data/code failures, so WorkManager can keep retrying the same broken batch indefinitely. | R | CONFIRMED | Retry only transient failures; return `failure`/`success` for permanent issues after logging enough context. |
| 24 | `com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt:31-38,90-99` | Medium | Performance | Every run rescans all still-unmatched receipts against the expense set again, with no last-attempt marker, cutoff, or hopeless-record backoff. | R | CONFIRMED | Persist attempt metadata and only re-match new/recent/materially changed receipts. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/tax/TaxEstimator.kt:37-39,51-55` | High | Ownership semantics | Tax estimation sums raw `amount` values for deductible expenses and VAT instead of using user-owned/effective amounts. Shared or partially-owned expenses can therefore overstate deductions and VAT. | Sum `effectiveAmount` (or an equivalent DAO aggregate) and keep tax calculations aligned with the ownership semantics documented on `Expense`. |
| 2 | `com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt:103-104` | Medium | Parsing | The “date at the start of a line” regex is not compiled with `MULTILINE`, so it only matches the start of the entire OCR text, not later lines as the comment claims. | Add `Pattern.MULTILINE` or split the OCR text into lines and match per line. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R #1 / D #9` | `com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt:641-650` | `runWithRetry()` does catch `CancellationException`, but on real coroutine cancellation the following `delay()` immediately rethrows from the cancelled context, so the OCR block is not retried. The implementation should still avoid logging cancellation as an OCR failure, but the reported “retries cancelled work” behavior is not reproducible here. **[Explicit CancellationException rethrow added by A.7]** |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Receipt batch import → warranty auto-create | High | Thread safety | `ReceiptRepository.processBatch()` parallelizes receipt processing, while the singleton warranty path reuses one `WarrantyTextExtractor` instance backed by shared `SimpleDateFormat` state. The thread-safety bug is primarily exposed in the real batch pipeline. | `com/yourname/expensetracker/data/repository/ReceiptRepository.kt`, `com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`, `com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt` | Make warranty extraction stateless/thread-safe and add concurrent batch tests that exercise the real import pipeline. |
| 2 | Receipt matching → background worker → notifications | High | Functional / API mismatch | The matcher can auto-link receipts to non-purchase transactions, and the worker immediately persists that linkage and emits the result through the budget-alert notification channel. A matching error therefore becomes both a data-integrity issue and a user-facing notification mismatch. | `com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt`, `com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`, `com/yourname/expensetracker/domain/service/NotificationService.kt`, `com/yourname/expensetracker/data/service/AndroidNotificationService.kt` | Tighten receipt-compatible matching rules before auto-linking and introduce a dedicated receipt-match notification API/channel. |
| 3 | Savings recommendation ↔ automation ↔ gamification | Medium | Inconsistent domain model | Smart savings, automated savings, and gamification all infer “savings” from different proxies (budget headroom, current triggering event, goal creation/current amount) instead of a shared contribution ledger, so user-facing savings states can contradict each other. | `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`, `com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt`, `com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt` | Introduce a shared savings-contribution history/policy service and make all three engines consume the same source of truth. |
| 4 | Tax configuration contract → estimator | High | Contract mismatch | `TaxConfiguration` exposes progressive brackets, but `TaxEstimator` implements flat-rate taxation by taking only the matching bracket’s rate. The abstraction and the consumer disagree on the tax model. | `com/yourname/expensetracker/domain/tax/TaxConfiguration.kt`, `com/yourname/expensetracker/domain/tax/TaxEstimator.kt` | Either implement true progressive taxation in the estimator or simplify the configuration contract so it matches the real behavior. |

## Summary
- Total verified issues: 24
- Confirmed: 24 (Critical: 0, High: 14, Medium: 9, Low: 1)
- False positives: 1
- Missed issues found: 2
- Files affected: 9/13

## Key Patterns
- Receipt-processing bugs are concentrated in real pipeline behavior: parallel batch import, background reprocessing, and multilingual merchant matching are less robust than isolated happy-path flows.
- Savings modules still treat placeholder/proxy values as real business facts, especially for streaks, weekly rewards, and per-goal save recommendations.
- Tax logic is internally inconsistent across bracket handling, period math, VAT scope, and ownership semantics, despite the surrounding domain model already exposing the needed abstractions.
- Several components already have stronger shared primitives (`MerchantNormalizer`, `effectiveAmount`, progressive tax brackets), but local implementations bypass them and reintroduce incorrect bespoke logic.
