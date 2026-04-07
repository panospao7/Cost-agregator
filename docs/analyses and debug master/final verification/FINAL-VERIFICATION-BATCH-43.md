# Final Verification — Batch 43: Logic, Negotiation & Parsers

## Scope
- `com/yourname/expensetracker/domain/logic/CustomSplitParser.kt`
- `com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt`
- `com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt`
- `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
- `com/yourname/expensetracker/domain/logic/SplitCalculator.kt`
- `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
- `com/yourname/expensetracker/domain/parser/AppParserRegistry.kt`
- `com/yourname/expensetracker/domain/parser/GenericTransactionParser.kt`
- `com/yourname/expensetracker/domain/parser/ParsedTransactionEnums.kt`
- `com/yourname/expensetracker/domain/parser/TransferDirectionDetector.kt`
- `com/yourname/expensetracker/domain/parser/parsers/GreekBankParser.kt`
- `com/yourname/expensetracker/domain/parser/parsers/GoogleWalletParser.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- `com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`

Requested but not present in repository:
- `domain/negotiation/NegotiationTracker.kt`
- `domain/negotiation/NegotiationModels.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/logic/CustomSplitParser.kt:122-137` | Medium | Precision | Split validation compares raw `Double` sums to inclusive tolerances, so payloads that are only exactly at the allowed boundary can be rejected by floating-point drift. | B | DOWNGRADED | Validate amounts in cents and percentages in integer basis points / `BigDecimal`, then compare integer deltas. |
| 2 | `com/yourname/expensetracker/domain/logic/CustomSplitParser.kt:76-110` | Medium | Data validation | `CUSTOM_AMOUNT` / `UNEQUAL` splits accept arbitrary decimal precision and can store sub-cent liabilities that other money paths round inconsistently. | B | DOWNGRADED | Reject values with more than two decimal places, or normalize to cents before accepting. |
| 3 | `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt:39-49` | Medium | Identity consistency | Recurring detection groups merchants with `lowercase().trim()` instead of the app-wide canonical merchant key, so punctuation/script variants fragment into separate patterns and can miss manual overrides. | B | DOWNGRADED | Group by `expense.merchantKey` when present, otherwise use `MerchantKeyGenerator`. |
| 4 | `com/yourname/expensetracker/domain/logic/SplitCalculator.kt:48-55,172-176,181-185,229-239` | High | Arithmetic overflow | Split calculations convert money to cents with `Int`; amounts above ~€21.47M overflow and can produce corrupted negative splits instead of failing safely. | B | CONFIRMED | Use `Long` or `BigDecimal` cents end-to-end and enforce an explicit upper bound. |
| 5 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:172-216` | Medium | Numeric hygiene | `pastSumDaily.lastOrNull()` is used without any `isFinite()` guard, so a single `NaN`/`Infinity` baseline poisons every projected point and bypasses the exception fallback. | B | DOWNGRADED | Reject or sanitize non-finite inputs/intermediates before building forecast points. |
| 6 | `com/yourname/expensetracker/domain/parser/GenericTransactionParser.kt:43-52,84-107` | High | Classification | The generic parser treats transfer wording such as `transfer received` as a deposit and emits `DEPOSIT` instead of `TRANSFER`, corrupting transfer analytics for unknown apps. | B | CONFIRMED | Separate incoming-transfer detection from true income/deposit detection and emit `TRANSFER` with direction. |
| 7 | `com/yourname/expensetracker/domain/parser/GenericTransactionParser.kt:204-239` | Medium | Date parsing | Date extraction uses lenient `Calendar` normalization, so impossible dates like `31/13/2024` can be accepted as different real timestamps instead of being rejected. | B | DOWNGRADED | Replace manual `Calendar` construction with strict `java.time` parsing or explicit month/day validation with leniency disabled. |
| 8 | `com/yourname/expensetracker/domain/parser/parsers/GreekBankParser.kt:67-76,239-285,298-317` | Medium | Direction detection | Transfer parsing accepts Latin one-letter codes (`X`, `D`, `P`), but direction detection only recognizes Greek/full-word codes, so supported transfer formats can lose direction metadata. | B | DOWNGRADED | Extend debit/credit direction detection to the same Latin code set already accepted by transfer parsing. |
| 9 | `com/yourname/expensetracker/domain/parser/parsers/GoogleWalletParser.kt:40-44,62-76,97-129` | High | Classification | Google Wallet / Google Pay parsing has no transfer path, so P2P sends are emitted as `PURCHASE` and incoming P2P money as `DEPOSIT` rather than `TRANSFER`. | B | CONFIRMED | Add explicit incoming/outgoing transfer detection and emit `ParsedTransactionType.TRANSFER` with direction/counterparty. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/reminder/BillReminderManager.kt:125-131,145-150` | High | Logic / enum handling | `SEMI_ANNUALLY` is not handled at all in reminder scheduling or monthly-cost conversion, so semiannual bills fall through to monthly logic. | Switch on `RecurrenceFrequency` directly (or at minimum add `SEMI_ANNUALLY`) for both next-date calculation and monthly-equivalent conversion. |
| 2 | `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:287-312,387-413,653-658` | High | Contract mismatch | Dashboard widget assembly keeps only `overallBudget` as the resolved limit. When a user has category budgets but no overall budget, `SafeToSpend` falls back to `monthSpent` and `FinancialRunway.totalBudget` remains `0`, even though synthesis already computed a valid discretionary budget from category totals. | Resolve `budgetLimit` once as `overall-or-category-sum` and reuse that value for Safe-to-Spend, Runway, and Block Party inputs. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| - | - | - | None identified. Every reported issue mapped to a real defect, though several severities/examples were overstated. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `SynthesisEngine -> ComputeDashboardWidgetsUseCase -> Block Party` | High | Contract mismatch | Forecast synthesis resolves `budgetLimit` as `overall budget or category-budget sum`, but Block Party receives only `overallBudget?.budgetAmount`, so category-budget-only months get a zero discretionary base. | `domain/logic/SynthesisEngine.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Compute the resolved budget limit once and pass the same value through forecast and Block Party generation. |
| 2 | `CustomSplitParser / SplitCalculator -> SharedExpenseBudgetOffsetEngine` | High | Duplicated logic | Settlement code uses strict custom-split validation plus equal-split fallback, while budget-offset code reparses the raw string with a weaker parser and can return partial/zero shares for the same malformed expense. | `domain/logic/CustomSplitParser.kt`, `domain/logic/SplitCalculator.kt`, `domain/groups/SharedExpenseBudgetOffsetEngine.kt` | Remove the ad-hoc parser and reuse validated split data from the shared split pipeline. |
| 3 | `RecurrenceCalculator -> RecurringExpenseRepository -> BillReminderManager` | High | Semantic drift | Recurrence semantics are not centralized: `IRREGULAR` advances by one month in `RecurrenceCalculator`, stays unchanged in `RecurringExpenseRepository`, and reminder code uses string-based mappings that mis-handle `ANNUALLY` and omit `SEMI_ANNUALLY`. | `domain/logic/RecurrenceCalculator.kt`, `data/repository/RecurringExpenseRepository.kt`, `domain/reminder/BillReminderManager.kt` | Route all recurrence advancement and monthly-equivalent calculations through one shared recurrence service using the enum directly. |

## Summary
- Total verified issues: 12
- Confirmed: 12 (Critical: 0, High: 6, Medium: 6, Low: 0)
- False positives: 0
- Missed issues found: 2
- Files affected: 12/16

## Key Patterns
- Shared business rules are still duplicated instead of centralized (`budgetLimit`, recurrence advancement, custom split parsing).
- Transfer semantics remain inconsistent across parsers; supported transfer notifications are still collapsed into purchase/deposit buckets.
- Money handling mixes raw `Double` validation with cent-based arithmetic and lacks consistent scale/bounds enforcement.
- Several fallback or legacy paths are weaker than the primary path, causing cross-screen inconsistencies rather than outright crashes.
