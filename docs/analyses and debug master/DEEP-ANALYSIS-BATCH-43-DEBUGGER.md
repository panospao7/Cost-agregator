# Deep Analysis — Batch 43: Logic, Negotiation & Parsers (@debugger)

## Scope
- domain/logic/CustomSplitParser.kt
- domain/logic/NarrativeGenerator.kt
- domain/logic/RecurrenceCalculator.kt
- domain/logic/RecurringExpenseEngine.kt
- domain/logic/SplitCalculator.kt
- domain/logic/SynthesisEngine.kt
- domain/negotiation/NegotiationTracker.kt (NOT FOUND)
- domain/negotiation/NegotiationModels.kt (NOT FOUND)
- domain/parser/AppParserRegistry.kt
- domain/parser/GenericTransactionParser.kt
- domain/parser/ParsedTransactionEnums.kt
- domain/parser/TransferDirectionDetector.kt
- domain/parser/parsers/GreekBankParser.kt
- domain/parser/parsers/GoogleWalletParser.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | GoogleWalletParser.kt:41-44,63,71-76,97-129 | **HIGH** | Logic Error | Google Wallet parser misclassifies P2P money movement as PURCHASE/DEPOSIT instead of TRANSFER, so supported Wallet transfer notifications bypass the transfer pipeline entirely. | 1. Receive Google Wallet notification for P2P transfer. 2. Parser classifies as PURCHASE. 3. Transfer analytics corrupted. | Add explicit incoming/outgoing transfer detection and emit `ParsedTransactionType.TRANSFER` with direction/counterparty. |
| 2 | GenericTransactionParser.kt:43-52,103-107 | **HIGH** | Logic Error | Generic parser classifies "transfer received" style notifications as DEPOSIT, corrupting transfer analytics and account-flow reporting for unknown apps. | 1. Unknown app sends "transfer received" notification. 2. Parser classifies as DEPOSIT. 3. Transfer analytics corrupted. | Separate true income/deposit signals from incoming-transfer signals and map transfer wording to `TRANSFER`. |
| 3 | SplitCalculator.kt:48-55,181-185,229-233 | **HIGH** | Integer Overflow | Split calculation converts money to cents with `Int`, which overflows above ~€21.47M and produces negative corrupted splits instead of failing safely. | 1. Split expense of €22,000,000. 2. `Int` overflow produces negative cents. 3. Corrupted splits. | Use `Long`/`BigDecimal` cents and enforce an explicit upper bound. |
| 4 | SynthesisEngine.kt:219-236,273-317 | **HIGH** | Logic Error | Forecasting and Block Party use different effective budget-limit rules when only category budgets exist, so safe-to-spend/weather can show available funds while Block Party shows zero discretionary base for the same month. | 1. User has only category budgets. 2. Safe-to-spend shows €500. 3. Block Party shows €0. | Resolve budget limit once and pass the same value through both paths. |
| 5 | SharedExpenseBudgetOffsetEngine.kt:127-165 | **MAJOR** | Logic Error | Shared expense budget math re-parses custom splits with a weaker ad-hoc parser than settlement logic, so malformed legacy split payloads can yield different liabilities in budgeting vs settlement views. | 1. Group expense with custom split. 2. Budget view shows €33.33. 3. Settlement view shows €33.34. | Reuse `CustomSplitParser`/`SplitCalculator` instead of manual parsing. |
| 6 | CustomSplitParser.kt:122-137 | **MAJOR** | Logic Error | Custom split validation uses raw `Double` sums against tolerance thresholds, so boundary-valid payloads can be rejected by floating-point drift. | 1. Split: 33.33 + 33.33 + 33.34 = 100.0. 2. Floating-point sum = 99.99999999999999. 3. Validation rejects valid split. | Validate amounts in cents and percentages in integer basis units / `BigDecimal`. |
| 7 | CustomSplitParser.kt:76-110 | **MAJOR** | Logic Error | Custom amount splits accept arbitrary decimal precision, allowing sub-cent liabilities that the rest of the money pipeline cannot represent consistently. | 1. Split amount: €33.333333. 2. Sub-cent liability stored. 3. Rest of pipeline can't represent it. | Reject or normalize values to two decimal places before accepting. |
| 8 | SynthesisEngine.kt:172-216 | **MAJOR** | Logic Error | Forecast projection accepts non-finite past cumulative values and propagates `NaN`/`Infinity` into projected points and UI weather data without triggering fallback. | 1. Past cumulative value is `NaN`. 2. Forecast propagates `NaN`. 3. UI weather data shows `NaN`. | Sanitize all numeric inputs/intermediates with `isFinite()` checks before building the forecast. |
| 9 | RecurringExpenseEngine.kt:39-49 | **MAJOR** | Logic Error | Recurring detection groups merchants by `lowercase().trim()` instead of the app's canonical merchant identity key, fragmenting variants of the same merchant and breaking alignment with manual overrides. | 1. Merchant "STARBUCKS" and "starbucks" detected as separate recurring patterns. 2. Manual override for "starbucks" doesn't apply to "STARBUCKS". | Group by shared merchant key generation logic. |
| 10 | GenericTransactionParser.kt:217-239 | **MAJOR** | Logic Error | Generic parser date extraction is lenient and can normalize impossible dates like `31/13/2024` into a different real date instead of rejecting them. | 1. Notification contains "31/13/2024". 2. Parser normalizes to a valid but wrong date. 3. Expense stored with wrong date. | Switch to strict `java.time` parsing or enforce explicit month/day validation. |
| 11 | GreekBankParser.kt:73-76,271-279,298-317 | **MAJOR** | Logic Error | Greek bank parser accepts Latin one-letter bank codes in transfer parsing but direction detection does not recognize them, dropping direction metadata for formats the parser claims to support. | 1. Greek bank notification with Latin code "α". 2. Parser recognizes code. 3. Direction detection fails. 4. Direction metadata lost. | Extend direction detection to the same Latin code set. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | GoogleWalletParser ↔ Transfer Pipeline | **HIGH** | Misclassification | Google Wallet P2P transfers classified as PURCHASE/DEPOSIT, bypassing transfer pipeline entirely. | Add explicit transfer detection in Google Wallet parser. |
| C2 | GenericTransactionParser ↔ Transfer Analytics | **HIGH** | Misclassification | "Transfer received" notifications classified as DEPOSIT, corrupting transfer analytics. | Separate transfer signals from deposit signals. |
| C3 | SynthesisEngine ↔ BlockPartyCard | **HIGH** | Budget Limit Divergence | Different budget-limit rules when only category budgets exist, causing safe-to-spend and Block Party to disagree. | Resolve budget limit once and share across both paths. |
| C4 | CustomSplitParser ↔ Settlement Logic | **MAJOR** | Split Parsing Divergence | Budget offset engine uses weaker ad-hoc parser than settlement logic, causing different liabilities for the same expense. | Reuse `CustomSplitParser`/`SplitCalculator` in budget offset engine. |
| C5 | RecurringExpenseEngine ↔ Merchant Canonicalizer | **MAJOR** | Merchant Key Divergence | Recurring detection uses `lowercase().trim()` instead of canonical merchant key, fragmenting variants and breaking manual overrides. | Use shared merchant key generation logic. |

## Summary
- **Total issues: 16** (11 file-level + 5 cross-component)
- **Critical: 0**, **High: 3**, **Major: 8**, **Low: 0**
- **Files with issues: 7/12** (Negotiation files not found)

## Key Patterns

### 1. Transfer Misclassification
Both Google Wallet parser and Generic parser misclassify transfers as purchases/deposits, corrupting transfer analytics and account-flow reporting.

### 2. Budget Limit Divergence
SynthesisEngine and Block Party use different budget-limit rules when only category budgets exist, causing conflicting safe-to-spend values.

### 3. Split Parsing Inconsistency
Budget offset engine uses weaker ad-hoc parser than settlement logic, causing different liabilities for the same expense.

### 4. Merchant Key Fragmentation
Recurring detection uses `lowercase().trim()` instead of canonical merchant key, fragmenting variants and breaking manual overrides.

### 5. Lenient Date Parsing
Generic parser accepts impossible dates and normalizes them to valid but wrong dates instead of rejecting them.
