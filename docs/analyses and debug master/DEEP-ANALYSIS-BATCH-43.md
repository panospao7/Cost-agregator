# Deep Analysis — Batch 43: Logic, Negotiation & Parsers (@reviewer)

## Scope
- `domain/logic/CustomSplitParser.kt`
- `domain/logic/NarrativeGenerator.kt`
- `domain/logic/RecurrenceCalculator.kt`
- `domain/logic/RecurringExpenseEngine.kt`
- `domain/logic/SplitCalculator.kt`
- `domain/logic/SynthesisEngine.kt`
- `domain/negotiation/NegotiationTracker.kt` **(requested in batch, not present in repository)**
- `domain/negotiation/NegotiationModels.kt` **(requested in batch, not present in repository)**
- `domain/parser/AppParserRegistry.kt`
- `domain/parser/GenericTransactionParser.kt`
- `domain/parser/ParsedTransactionEnums.kt`
- `domain/parser/TransferDirectionDetector.kt`
- `domain/parser/parsers/GreekBankParser.kt`
- `domain/parser/parsers/GoogleWalletParser.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/logic/CustomSplitParser.kt:122-137` | MEDIUM | Precision | Amount/percent validation compares raw `Double` sums against exact tolerances. Boundary-valid payloads (for example totals off by exactly `0.01` or `0.1`) can be rejected because floating-point addition produces `0.010000...` drift instead of the intended threshold. | Validate amount splits in cents and percentage splits in basis points / `BigDecimal`, then compare integer deltas instead of raw doubles. |
| 2 | `domain/logic/CustomSplitParser.kt:76-110` | MEDIUM | Data validation | Amount-based custom splits accept arbitrary decimal precision. `CUSTOM_AMOUNT` / `UNEQUAL` entries like `33.333` are treated as valid and later propagated verbatim, creating sub-cent liabilities that cannot be represented or settled consistently elsewhere in the money pipeline. | Reject amount-based values with more than two decimal places, or normalize them to cents before accepting the payload. |
| 3 | `domain/logic/RecurringExpenseEngine.kt:39-49` | MEDIUM | Logic / Consistency | Recurring detection groups merchants with `lowercase().trim()` only. That diverges from the app’s canonical merchant identity strategy (`merchantKey` / `MerchantKeyGenerator`), so punctuation/script variants such as `NETFLIX`, `Netflix.com`, or Greek/Latin renderings fragment into separate recurring patterns and manual overrides can miss the same merchant. | Group and merge by canonical merchant key (`expense.merchantKey` when present, otherwise generate one with the shared merchant-key utility). |
| 4 | `domain/logic/SplitCalculator.kt:48-55,181-185,229-233` | HIGH | Arithmetic overflow | Money is converted to cents with `Int`. Totals above ~€21.47M overflow during `movePointRight(2).toInt()`, which makes equal/percentage splits go negative and corrupts balances instead of failing safely. Existing stress tests already document the broken output. | Store cents in `Long` (or `BigDecimal`) end-to-end and reject amounts above the supported bound explicitly before splitting. |
| 5 | `domain/logic/SynthesisEngine.kt:172-216` | MEDIUM | Error handling / Data hygiene | `pastSumDaily.lastOrNull()` is used directly as the projection baseline and copied into the forecast without any finite-value guard. A single `NaN`/`Infinity` input contaminates all projected points and downstream weather/chart widgets, but no exception is thrown so the fallback path never activates. | Sanitize `pastSumDaily`, planned totals, and projection intermediates with `isFinite()` checks; if inputs are non-finite, drop them or fall back to a zeroed forecast. |
| 6 | `domain/parser/GenericTransactionParser.kt:43-52,103-107` | HIGH | Classification | `depositSignals` includes transfer phrases such as `transfer received`, but the parser maps any deposit signal straight to `ParsedTransactionType.DEPOSIT`. Unknown-app incoming transfers are therefore stored as income deposits, not transfers, which distorts transfer analytics and account-flow reporting. | Split incoming-transfer detection from true deposit/income detection, and emit `TRANSFER + INCOMING` when counterparty/transfer wording is present. |
| 7 | `domain/parser/GenericTransactionParser.kt:217-239` | MEDIUM | Parsing / Data correctness | Date extraction uses lenient `Calendar` math and never validates the month range before setting it. Impossible dates like `31/13/2024` can be normalized into a different real date instead of being rejected, silently shifting transaction timestamps. | Replace manual `Calendar` construction with strict `java.time` parsing (`ResolverStyle.STRICT`) or explicitly validate `month in 1..12` and disable leniency. |
| 8 | `domain/parser/parsers/GreekBankParser.kt:73-76,271-279,298-317` | MEDIUM | Logic | `TRANSFER_PATTERNS` explicitly accepts Latin one-letter bank codes (`X`, `D`, `P`), but `detectGreekDirection()` only recognizes Greek credit/debit codes and full words. Matching notifications parse as transfers with `null` direction, so downstream direction/account metadata is dropped for a format the parser claims to support. | Extend `DEBIT_CODES` / `CREDIT_CODES` (or the direction regex) to cover the Latin codes already accepted by the transfer parser. |
| 9 | `domain/parser/parsers/GoogleWalletParser.kt:41-44,63,71-76,97-129` | HIGH | Classification | Google Wallet parsing has no transfer path. P2P notifications such as `You paid ... to John` are emitted as `PURCHASE`, while `John paid you ...` / `sent to you` become `DEPOSIT`. That collapses person-to-person transfers into the wrong transaction classes for a supported package, preventing the generic fallback from correcting them. | Add explicit incoming/outgoing transfer detection for Wallet/UPI text and emit `ParsedTransactionType.TRANSFER` with the correct direction/counterparty. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `SynthesisEngine -> ComputeDashboardWidgetsUseCase -> Block Party` | HIGH | Contract mismatch | `SynthesisEngine.synthesize()` falls back to the sum of category budgets when no overall budget exists, but `ComputeDashboardWidgetsUseCase` passes only `overallBudget?.budgetAmount` into `calculateBlockPartyData()`. Result: safe-to-spend/weather use the category-budget fallback while Block Party shows a zero discretionary base for the same month. | Resolve budget limit once (overall-or-category-sum) and pass the same value to both forecast synthesis and Block Party generation, or store the resolved limit inside the forecast. |
| 2 | `CustomSplitParser/SplitCalculator -> SharedExpenseBudgetOffsetEngine` | HIGH | Duplicated logic | Group settlement code validates custom splits with `CustomSplitParser` and falls back to equal shares for invalid legacy payloads, but `SharedExpenseBudgetOffsetEngine` re-parses the raw string manually and returns partial/zero values. The same malformed group expense can therefore produce different liabilities in budget math vs settlement math. | Delete the ad-hoc parser in `SharedExpenseBudgetOffsetEngine` and reuse the validated split map from `CustomSplitParser` / `SplitCalculator`. |
| 3 | `RecurrenceCalculator -> RecurringExpenseRepository / BillReminderManager` | MEDIUM | Semantic drift | Recurrence semantics are not centralized. `RecurrenceCalculator` moves `IRREGULAR` forward by one month, `RecurringExpenseRepository` keeps `IRREGULAR` on the same date, and reminder logic uses string literals like `YEARLY` instead of the shared `ANNUALLY` enum name. This makes next-date behavior depend on which component touched the record last. | Make all recurrence consumers call one shared recurrence service and remove string-based frequency handling. |

## Summary
- Total issues: 9
- Critical: 0, High: 4, Medium: 5, Low: 0
- Files with issues: 7/12 existing files (`2` requested files were missing from the repository)

## Key Patterns
- **Money precision is not centralized.** Split validation still relies on raw `Double` math in some paths and `Int` cents in others, causing both tolerance-edge rejection and overflow at higher amounts.
- **Transfer vs deposit semantics are inconsistent across parsers.** Generic and Google Wallet parsing both collapse incoming/outgoing transfers into income/purchase buckets, which leaks directly into downstream analytics.
- **Shared business rules are duplicated instead of reused.** Merchant normalization, recurrence advancement, and custom-split parsing all have multiple implementations with different behavior, creating cross-screen inconsistencies.
- **Error handling often protects only exceptions, not invalid numeric state.** Several engines avoid crashes but still allow poisoned `NaN`/`Infinity` values or malformed business data to propagate into UI-facing models.
