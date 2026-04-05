# Crash-Test Scenarios & E2E Validation Plan

> **Date:** April 5, 2026  
> **Purpose:** Break every calculation engine, expose edge cases, verify cross-component consistency  
> **Approach:** Predetermined inputs → expected outputs. No test passes unless the exact value matches.  
> **For AI agents:** This file contains the golden dataset and all expected values. When creating tests, import `createExpense` and `assertApproxEquals` from `TestUtils.kt` and reference exact values from the tables below. Read `TESTING-AGENT-PLAYBOOK.md` first for conventions, base classes, and templates.  
> **Start here:** `TESTING-WORKFLOW.md` — master orchestration with phased batches  
> **Companion files:** `TESTING-AGENT-PLAYBOOK.md` (conventions & templates), `COMPONENT-TEST-MATRIX.md` (file inventory)

---

## Table of Contents

1. [Golden Dataset Definition](#1-golden-dataset-definition)
2. [Budget Calculator Crash Tests](#2-budget-calculator-crash-tests)
3. [Spending Pace Calculator Crash Tests](#3-spending-pace-calculator-crash-tests)
4. [Split & Settlement Calculator Crash Tests](#4-split--settlement-calculator-crash-tests)
5. [Currency Converter Crash Tests](#5-currency-converter-crash-tests)
6. [Financial Health Score Crash Tests](#6-financial-health-score-crash-tests)
7. [Analytics Pipeline E2E Tests](#7-analytics-pipeline-e2e-tests)
8. [Forecasting Engine Crash Tests](#8-forecasting-engine-crash-tests)
9. [Savings Engine Crash Tests](#9-savings-engine-crash-tests)
10. [Synthesis Engine Crash Tests](#10-synthesis-engine-crash-tests)
11. [Cross-Component Consistency Tests](#11-cross-component-consistency-tests)
12. [Numeric Precision & Overflow Tests](#12-numeric-precision--overflow-tests)
13. [Temporal Boundary Crash Tests](#13-temporal-boundary-crash-tests)
14. [Concurrency & State Race Tests](#14-concurrency--state-race-tests)
15. [Implementation Priority](#15-implementation-priority)

---

## 1. Golden Dataset Definition

All scenarios reference a shared golden dataset. Every test must produce **exactly** the expected output — no tolerance unless explicitly stated.

### Base Expenses (Golden Month: March 2026, 31 days)

| ID | Date | Merchant | Amount | EffectiveAmt | Category | Type | isNotMine |
|----|------|----------|--------|-------------|----------|------|-----------|
| 1 | Mar 1 | Rent Co | 800.00 | 800.00 | rent | PURCHASE | false |
| 2 | Mar 2 | Lidl | 45.30 | 45.30 | groceries | PURCHASE | false |
| 3 | Mar 5 | Shell Gas | 62.50 | 62.50 | transport | PURCHASE | false |
| 4 | Mar 7 | Netflix | 15.99 | 15.99 | entertainment | PURCHASE | false |
| 5 | Mar 10 | Lidl | 38.70 | 38.70 | groceries | PURCHASE | false |
| 6 | Mar 12 | Restaurant A | 24.50 | 24.50 | dining | PURCHASE | false |
| 7 | Mar 15 | Salary | 2500.00 | 2500.00 | income | DEPOSIT | false |
| 8 | Mar 15 | Coffee Shop | 4.80 | 4.80 | dining | PURCHASE | false |
| 9 | Mar 18 | Lidl | 52.10 | 52.10 | groceries | PURCHASE | false |
| 10 | Mar 20 | Zara | 89.90 | 89.90 | clothing | PURCHASE | false |
| 11 | Mar 22 | Pharmacy | 12.30 | 12.30 | healthcare | PURCHASE | false |
| 12 | Mar 25 | Friend Lunch | 35.00 | 17.50 | dining | PURCHASE | false |
| 13 | Mar 28 | Utilities | 120.00 | 120.00 | utilities | PURCHASE | false |
| 14 | Mar 30 | Bonus | 500.00 | 500.00 | income | DEPOSIT | false |

### Derived Golden Constants

```
Total PURCHASE (non-isNotMine) effectiveAmounts:
  800.00 + 45.30 + 62.50 + 15.99 + 38.70 + 24.50 + 4.80 + 52.10 + 89.90 + 12.30 + 17.50 + 120.00
  = 1,283.59

Total DEPOSIT effectiveAmounts:
  2,500.00 + 500.00 = 3,000.00

Savings amount: max(3000.00 - 1283.59, 0) = 1,716.41
Savings rate: 1716.41 / 3000.00 = 0.572137 (57.21%)

Days in March: 31
Grocery total: 45.30 + 38.70 + 52.10 = 136.10
Dining total: 24.50 + 4.80 + 17.50 = 46.80
```

### Previous Month (February 2026, 28 days)

| ID | Date | Merchant | Amount | EffectiveAmt | Category | Type |
|----|------|----------|--------|-------------|----------|------|
| 101 | Feb 1 | Rent Co | 800.00 | 800.00 | rent | PURCHASE |
| 102 | Feb 5 | Lidl | 55.00 | 55.00 | groceries | PURCHASE |
| 103 | Feb 10 | Shell Gas | 58.00 | 58.00 | transport | PURCHASE |
| 104 | Feb 15 | Salary | 2500.00 | 2500.00 | income | DEPOSIT |
| 105 | Feb 18 | Restaurant B | 30.00 | 30.00 | dining | PURCHASE |
| 106 | Feb 25 | Utilities | 115.00 | 115.00 | utilities | PURCHASE |

```
Feb PURCHASE total: 800.00 + 55.00 + 58.00 + 30.00 + 115.00 = 1,058.00
Feb DEPOSIT total: 2,500.00
Feb days: 28
```

---

## 2. Budget Calculator Crash Tests

### Scenario 2.1 — MONTHLY Calendar Mode

**Input:** `budget = { periodMode: "MONTHLY", period: MONTHLY }`, `now = March 15, 2026 14:00 UTC`  
**Expected:** `start = March 1 00:00:00`, `end = March 31 23:59:59.999` (or April 1 00:00:00 exclusive)

### Scenario 2.2 — ROLLING MONTHLY (30-day fixed window, NOT calendar)

**Input:** `budget = { periodMode: "ROLLING", period: MONTHLY, startDate: Feb 10 }`, `now = March 5`  
**Expected:** `start = Feb 10`, `end = Feb 10 + 30 days = March 12`  
**Why it breaks:** A user who set a rolling budget on Jan 31 always gets a 30-day window, never a full calendar month. The window doesn't advance automatically.

### Scenario 2.3 — Anchor Day Coercion (31st → Feb 28)

**Input:** `period = MONTHLY`, `anchorDate = Jan 31 2026`, `evaluationTime = Feb 15 2026`  
**Expected:** `start = Jan 31` (coerced from 31 → 28 for Feb → **start = Feb 28 of previous cycle? Or Jan 28?**)  
**Critical check:** Verify the anchor day 31 is coerced to `calendar.getActualMaximum(DAY_OF_MONTH)` for February (28).

### Scenario 2.4 — Leap Year (Feb 29)

**Input:** `period = MONTHLY`, `anchorDate = Mar 29 2024 (leap year)`, `evaluationTime = Feb 15 2025 (non-leap)`  
**Expected:** Anchor 29 coerced to 28 for Feb 2025. `start = Feb 28`, `end = Mar 29` (29 days back to normal).

### Scenario 2.5 — YEARLY Anniversary Edge

**Input:** `period = YEARLY`, `anchorDate = Mar 15 2025`, `evaluationTime = Mar 15 2026`  
**Expected:** Since the code uses `>` for month and `>=` for day: month==month AND day>=day → anniversary has passed. `start = Mar 15 2026`, `end = Mar 15 2027`.  
**What if `evaluationTime = Mar 14 2026`?** Anniversary not passed → `start = Mar 15 2025`, `end = Mar 15 2026`.

### Scenario 2.6 — WEEKLY Anchor Day Match

**Input:** `period = WEEKLY`, `anchorDate = Monday Jan 6 2026`, `evaluationTime = Wednesday Mar 11 2026`  
**Expected:** Walk back from Wed to find Monday → `start = Mon Mar 9`. `end = Mon Mar 16`.

### Scenario 2.7 — DST Transition (March clock change)

**Input:** `period = DAILY`, `evaluationTime = March 29 2026 03:00 (Europe/Athens, DST spring forward)`  
**Expected:** `startOfDay = March 29 00:00 EET` (which is 22:00 UTC March 28). Duration = 23 hours, not 24.  
**Crash potential:** If the code adds `24 * 60 * 60 * 1000` millis for "end of day", it overshoots to March 30 01:00 local time.

### Scenario 2.8 — Empty/Null Period Mode

**Input:** `budget = { periodMode: "", period: MONTHLY }`  
**Expected:** Falls to `else` branch (calendar mode). Should not crash. Verify it returns a valid range.

---

## 3. Spending Pace Calculator Crash Tests

### Scenario 3.1 — Standard Golden Month (March 15, midway)

**Input:** Golden dataset, `now = March 15 2026 end-of-day`  
**Pre-computed:**
```
Purchases on/before Mar 15: 800.00 + 45.30 + 62.50 + 15.99 + 38.70 + 24.50 + 4.80 = 991.79
currentDay = 15
daysInMonth = 31

currentDailyRate = 991.79 / 15 = 66.1193...
previousMonthDays = 28
previousDailyRate = 1058.00 / 28 = 37.7857...

pacePercentage = (66.1193 / 37.7857) * 100 = 174.98%
paceStatus = OVER_PACE (>110%)

Projection (day 15):
  weight = clamp(15 / 7.0, 0.0, 1.0) = 1.0  (capped at 1.0)
  linearProjection = 991.79 * 31 / 15 = 2,049.03
  conservativeEstimate = 991.79 * 3.0 = 2,975.37
  projectedTotal = 1.0 * 2049.03 + 0.0 * 2975.37 = 2,049.03
```

**Expected:**
- `currentMonthSpent = 991.79`
- `daysElapsed = 15`
- `projectedTotal ≈ 2049.03`
- `pacePercentage ≈ 175.0f` (Float truncation)
- `paceStatus = OVER_PACE`

### Scenario 3.2 — Day 1 Projection (Conservative Bias)

**Input:** Single expense: €800 rent on March 1. `now = March 1 end-of-day`.
```
currentDay = 1
weight = clamp(1 / 7.0, 0, 1) = 0.1429
linearProjection = 800 * 31 / 1 = 24,800
conservativeEstimate = 800 * 3 = 2,400
projectedTotal = 0.1429 * 24800 + 0.8571 * 2400 = 3,543 + 2,057 = 5,600
```

**Expected:** `projectedTotal ≈ 5,600.00`. This is wildly inaccurate (a single rent payment inflates the projection to 5.6k). Validates the bias is understood.

### Scenario 3.3 — Zero Previous Month (NO_BASELINE)

**Input:** Golden March data, but `previousMonthExpenses = []`  
**Expected:** `baselineDailyRate = 0.0`, `pacePercentage = 0.0f`, `paceStatus = NO_BASELINE`.

### Scenario 3.4 — Boundary: Exactly 90% and 110%

**Input A (exactly 90%):** Craft expenses such that `currentDailyRate / baselineDailyRate = 0.900`.  
**Expected:** `pacePercentage = 90.0f`, `paceStatus = ON_PACE` (not UNDER_PACE — threshold is strict `<`).

**Input B (exactly 110%):** Craft expenses such that ratio = 1.100.  
**Expected:** `pacePercentage = 110.0f`, `paceStatus = ON_PACE` (not OVER_PACE — threshold is strict `>`).

### Scenario 3.5 — Last Day of Month (Full Data)

**Input:** All golden March expenses, `now = March 31 23:59:59`.
```
currentDay = 31
monthSpent = 1,283.59
projectedTotal = 1,283.59 * 31 / 31 = 1,283.59 (no projection needed, weight = 1.0)
```

**Expected:** `projectedTotal = 1283.59` (equals actual — no projection error at month end).

### Scenario 3.6 — Float-to-Float Precision on Boundary

**Input:** Previous month: 1 expense of €100.00 in 28 days. Current month: daily rate = €3.214285... in 7 days.  
`ratio = 3.214285714... / 3.571428571... = 0.9000000...`  
**Expected:** After `toFloat()` conversion, verify `pacePercentage` does not flip across the 90% boundary due to Float precision.

---

## 4. Split & Settlement Calculator Crash Tests

### Scenario 4.1 — Equal Split: 3 Members, €100.00

**Input:** `totalAmount = 100.00`, `members = [A, B, C]`
```
totalCents = 10000
baseCents = 10000 / 3 = 3333
remainder = 10000 % 3 = 1
```

**Expected:**
- A: `fromCents(3334) = 33.34`
- B: `fromCents(3333) = 33.33`
- C: `fromCents(3333) = 33.33`
- **Sum: 99.99 + 0.01 (A absorbs) = 100.00** ← MUST validate sum exactly equals input

### Scenario 4.2 — Equal Split: 7 Members, €100.00 (Large Remainder)

```
totalCents = 10000
baseCents = 10000 / 7 = 1428
remainder = 10000 % 7 = 4
```

**Expected:**
- Members 0-3: `fromCents(1429) = 14.29` each
- Members 4-6: `fromCents(1428) = 14.28` each
- **Sum: 4 × 14.29 + 3 × 14.28 = 57.16 + 42.84 = 100.00** ✓

### Scenario 4.3 — Percentage Split: 33.33% / 33.33% / 33.34%

**Input:** `totalAmount = 100.00`, percentages = `{A: 33.33, B: 33.33, C: 33.34}`
```
totalCents = 10000
A: rawCents = 10000 * 0.3333 = 3333.0, base=3333, frac=0.0
B: rawCents = 10000 * 0.3333 = 3333.0, base=3333, frac=0.0
C: rawCents = 10000 * 0.3334 = 3334.0, base=3334, frac=0.0

remainder = 10000 - (3333+3333+3334) = 0
```

**Expected:** A=33.33, B=33.33, C=33.34. Sum=100.00 ✓

### Scenario 4.4 — Percentage Split: 33.33% / 33.33% / 33.33% (Doesn't Sum to 100%)

**Input:** Percentages sum to 99.99%.
```
totalCents = 10000
A: rawCents = 3333.0, base=3333, frac=0.0
B: rawCents = 3333.0, base=3333, frac=0.0
C: rawCents = 3333.0, base=3333, frac=0.0
remainder = 10000 - 9999 = 1
```

**Expected:** Sort by fractional part (all 0.0, tiebreak by index) → A gets +1 cent. A=33.34, B=33.33, C=33.33. Sum=100.00.

### Scenario 4.5 — Int Overflow: Amount > $21,474,836.47

**Input:** `totalAmount = 25,000,000.00` (25 million)
```
BigDecimal.valueOf(25000000.00).setScale(2).movePointRight(2).toInt()
= 2,500,000,000 > Int.MAX_VALUE (2,147,483,647)
```

**Expected:** Silent overflow → corrupted cents value → **incorrect splits**. This test documents the breakage.

### Scenario 4.6 — Settlement: 3-Member Triangle Debt

**Input balances:**
- A: +€50.00 (creditor)
- B: -€30.00 (debtor)
- C: -€20.00 (debtor)

**Greedy (SplitCalculator.simplifyBalances):**
- B→A: €30.00
- C→A: €20.00
- **2 transactions**

**DFS (SettlementCalculator.calculateSettlements):**
- Same result (optimal is also 2 transactions)

**Expected:** Both algorithms produce 2 transactions summing to €50.

### Scenario 4.7 — Settlement: 4-Member Where Greedy ≠ Optimal

**Input balances:**
- A: +€10
- B: +€20
- C: -€10
- D: -€20

**Greedy (sorted desc):**
1. D(-20) → B(+20): €20 → both settled. **1 tx**
2. C(-10) → A(+10): €10 → both settled. **1 tx**
- **Total: 2 transactions** ✓

**DFS:** Also finds 2 transactions (this case is optimal for both).

**Better test — 4 members where greedy is suboptimal:**

**Input:**
- A: +€6, B: +€4, C: -€7, D: -€3

**Greedy:**
1. C(-7) → A(+6): €6 → A settled, C has -1 remaining
2. C(-1) → B(+4): €1 → C settled, B has +3 remaining
3. D(-3) → B(+3): €3 → both settled
- **3 transactions**

**DFS optimal:**
1. D(-3) → B(+4): €3 → D settled, B has +1
2. C(-7) → A(+6): €6 → A settled, C has -1
3. C(-1) → B(+1): €1 → both settled
- **Still 3 transactions** (same count, different pairings)

**Test with this input instead:**
- A: +5, B: +5, C: -5, D: -5

**Greedy:** D→A: 5, C→B: 5 → **2 tx**  
**DFS:** Same → **2 tx**  
Both optimal. To find where they diverge, use:

- A: +3, B: +6, C: -4, D: -5

**Greedy (sorted creditors desc):**
1. D(-5) → B(+6): €5, B remaining=+1
2. C(-4) → B(+1): €1, B settled, C remaining=-3
3. C(-3) → A(+3): €3, both settled
- **3 tx**

**DFS:** Explores all orderings, also finds minimum 3. But the specific pairings may differ.

### Scenario 4.8 — DFS Exponential Blowup: 15 Members

**Input:** 15 members, each alternating +€1/-€1.  
**Expected:** DFS explores 7!×8! or similar permutations. Must complete within a reasonable time (< 5 seconds). If it doesn't, this proves the need for a depth/time guard.

### Scenario 4.9 — Balance Calculation: Dual-Path Parity

**Input:** Same 5 expenses with 3 members, various split types.  
**Test:** `SplitCalculator.calculateBalances()` and `SharedExpenseManager.calculateBalances()` must produce **identical** net balances (within FP tolerance of ±0.01).

### Scenario 4.10 — Zero-Amount Expense

**Input:** `totalAmount = 0.00`, 3 members, EQUAL split.
```
totalCents = 0
baseCents = 0 / 3 = 0
remainder = 0
```

**Expected:** All members get €0.00. No crash, no division by zero.

### Scenario 4.11 — Single Member Equal Split

**Input:** `totalAmount = 100.00`, `members = [A]`
```
totalCents = 10000
baseCents = 10000 / 1 = 10000
remainder = 0
```

**Expected:** A = €100.00.

### Scenario 4.12 — Negative Amount

**Input:** `totalAmount = -50.00`, 2 members.  
**Expected:** `toCents(-50.00) = -5000`. `baseCents = -5000 / 2 = -2500`. Each member gets -€25.00. Verify this doesn't crash and the sign is preserved.

---

## 5. Currency Converter Crash Tests

### Scenario 5.1 — Same Currency

**Input:** `convert(100.00, "EUR", "EUR")`  
**Expected:** `ConversionResult(100.00, 1.0)`

### Scenario 5.2 — Case Insensitivity

**Input:** `convert(100.00, "eur", "EUR")`  
**Expected:** Same as above — rate 1.0, no conversion.

### Scenario 5.3 — Direct Rate

**Input:** Store rate EUR→USD = 1.08500. `convert(100.00, "EUR", "USD")`.  
**Expected:** `convertedAmount = 108.50`

### Scenario 5.4 — Cross-Rate via EUR

**Input:** Store rates GBP→EUR = 1.17, EUR→JPY = 162.50.  
`convert(100.00, "GBP", "JPY")`
```
combinedRate = 1.17 * 162.50 = 190.125
convertedAmount = 100.00 * 190.125 = 19012.50
```

**Expected:** `convertedAmount = 19012.50`, `rate = 190.125`

### Scenario 5.5 — Missing Rate (No Path)

**Input:** No rates stored. `convert(100.00, "BTC", "CHF")`  
**Expected:** `null` (no direct rate, no cross-rate via EUR)

### Scenario 5.6 — formatAmount Rounding Edge

**Input:** `formatAmount(10.005, "EUR")`  
**Expected:** `"€10.01"` or `"€10.00"` — depends on `String.format("%.2f")` rounding behavior (Banker's rounding vs. HALF_UP). Document which is correct.

### Scenario 5.7 — convertMultiple with Partial Failures

**Input:** `amounts = [(100.0, "EUR"), (50.0, "BTC"), (200.0, "USD")]`. Only EUR and USD rates exist.  
**Expected:** `total = 100.0 + converted(200 USD→EUR)`, `failures = [("BTC", 50.0)]`, `hasFailures = true`.

### Scenario 5.8 — Very Small Amount

**Input:** `convert(0.001, "EUR", "USD")` with rate 1.085.  
**Expected:** `convertedAmount = 0.001085`. Verify `formatAmount` shows `"$0.00"` (rounds to 0).

### Scenario 5.9 — Very Large Amount (Precision)

**Input:** `convert(999999999.99, "EUR", "USD")` with rate 1.085.  
**Expected:** `convertedAmount = 1,084,999,999.48915`. Verify Double precision is sufficient (Double can represent up to ~15 significant digits, this has 13).

---

## 6. Financial Health Score Crash Tests

### Scenario 6.1 — Golden Month Score

**Input:** Golden dataset March 2026. No savings goals. No budgets.
```
savingsRateScore:
  totalIncome = 3000.00
  totalExpenses = 1283.59
  savingsRate = (3000 - 1283.59) / 3000 = 0.57214
  score = floor(0.57214 / 0.20 * 100) = floor(286.07) → coerced to 100

runwayScore:
  savingsGoals = [] → totalSavings = 0
  monthlyExpenses = ... (computed from blend)
  runwayMonths = 0 / monthlyExpenses = 0
  score = floor(0 / 6.0 * 100) = 0

budgetAdherenceScore:
  no budgets → 50 (neutral)

billReliabilityScore:
  no patterns → 75 (default)

overall = floor(0.30*100 + 0.25*0 + 0.25*50 + 0.20*75)
        = floor(30 + 0 + 12.5 + 15) = floor(57.5) = 57
```

**Expected:** `overallScore = 57`, trend = STABLE (no prior history).

### Scenario 6.2 — No Income (Neutral Savings)

**Input:** Only purchase expenses, zero deposits.  
```
savingsRateScore = 50 (totalIncome <= 0)
```

**Expected:** `savingsRateScore = 50`. Does NOT penalize for missing income data.

### Scenario 6.3 — Expenses > Income (Floor at 0)

**Input:** Income = €1000, Expenses = €2000.  
```
savingsAmount = max(1000 - 2000, 0) = 0
savingsRate = 0 / 1000 = 0
score = floor(0 / 0.20 * 100) = 0
```

**Expected:** `savingsRateScore = 0`.

### Scenario 6.4 — Perfect Score

**Input:** Income = €5000, Expenses = €500, Savings goals = €30000, 6 budgets all under-spent.
```
savingsRate = (5000-500)/5000 = 0.90 → score = floor(0.90/0.20*100) = floor(450) → 100
runway = 30000 / ~500 = 60 months → score = floor(60/6*100) → 100
budgetAdherence = all under → adherence = 1.0 → 100
billReliability = all patterns > 0.95 → 100

overall = floor(0.30*100 + 0.25*100 + 0.25*100 + 0.20*100) = 100
```

**Expected:** `overallScore = 100`.

### Scenario 6.5 — New User (No Data Asymmetry)

**Input:** Zero expenses, zero deposits, no budgets, no savings, no patterns.  
```
savingsRate: totalIncome <= 0 → 50
runway: insufficient data → 50
budget: empty → 50
bills: no patterns → 75  ← HIGHER than other defaults

overall = floor(0.30*50 + 0.25*50 + 0.25*50 + 0.20*75) = floor(15+12.5+12.5+15) = floor(55) = 55
```

**Expected:** `overallScore = 55`. Document this asymmetry (bills default 75 vs everything else 50).

### Scenario 6.6 — Truncation vs Rounding

**Input:** Craft scores so `0.30*s + 0.25*r + 0.25*b + 0.20*bi = 74.999999`.  
**Expected:** `floor(74.999999) = 74`, NOT 75. Verify `.toInt()` truncates, not rounds.

### Scenario 6.7 — Trend: Exactly 5 Points Change

**Input:** Previous score = 50, current score = 55.  
**Expected:** `diff = 5`, `5 >= 5` → `IMPROVING`.

**Input:** Previous score = 50, current = 45.  
**Expected:** `diff = -5`, `-5 <= -5` → `DECLINING`.

**Input:** Previous = 50, current = 54.  
**Expected:** `diff = 4` → `STABLE`.

---

## 7. Analytics Pipeline E2E Tests

### Scenario 7.1 — InsightsEngine: Monthly Comparison

**Input:** Golden March + February data.
```
currentTotal = 1283.59 (March purchases)
previousTotal = 1058.00 (Feb purchases)
changePercent = ((1283.59 - 1058.00) / 1058.00) * 100 = 21.32%
```

**Expected:** `monthlyComparison.changePercentage ≈ 21.32f`. Since >20%, legacy insights should generate a "Spending Increase" insight.

### Scenario 7.2 — Category Percentage Normalization

**Input:** 3 categories: A=40.00, B=35.50, C=24.50. Total=100.00.
```
A: 40.0 / 100.0 * 100 = 40.00%
B: 35.5 / 100.0 * 100 = 35.50%
C: 24.5 / 100.0 * 100 = 24.50%
Sum = 100.00%
```

**Expected:** No normalization adjustment needed. Sum == 100%.

**Edge case:** Percentages from Float arithmetic: A=33.33%, B=33.33%, C=33.33% → sum=99.99%. Last category should be adjusted to 33.34% to make sum=100.00%.

### Scenario 7.3 — Anomaly Detection: Merchant-Level

**Input:** Merchant "Lidl" has 5 historical transactions averaging €40. Current month max = €200.  
```
multiplier = 3.0 (count >= 10? If count is 5: multiplier = 5.0 → threshold = 200)
threshold = 40 * 5.0 = 200
maxAmount = 200 → 200 > 200? NO (strict >)
```

**Expected:** `200 > 200` is false → Lidl is NOT flagged as anomaly.  
**If max = €200.01:** Flagged. Verify the strict `>` boundary.

### Scenario 7.4 — Day-of-Week Distribution Correctness

**Input:** Golden March data.
```
Mar 1 (Sun), Mar 2 (Mon), Mar 5 (Thu), Mar 7 (Sat), Mar 10 (Tue),
Mar 12 (Thu), Mar 15 (Sun), Mar 18 (Wed), Mar 20 (Fri), Mar 22 (Sun),
Mar 25 (Wed), Mar 28 (Sat), Mar 30 (Mon)

Day-of-week mapping (Calendar.SUNDAY=1 → index 6, MONDAY=2 → index 0):
  Mon (index 0): Mar 2 (45.30), Mar 30 (500.00 deposit — excluded: PURCHASE only) → PURCHASE: 45.30
  Tue (index 1): Mar 10 (38.70)
  Wed (index 2): Mar 18 (52.10), Mar 25 (120.00)
  Thu (index 3): Mar 5 (62.50), Mar 12 (24.50)
  Fri (index 4): Mar 20 (89.90)
  Sat (index 5): Mar 7 (15.99), Mar 28 (—wait, Mar 28 is a Sat)
  Sun (index 6): Mar 1 (800.00), Mar 15 (4.80—PURCHASE), Mar 22 (12.30)
```

**Expected:** Verify each bucket total against manually computed values. Cross-check `InsightsEngine` and `AdvancedAnalyticsEngine` produce the same day-of-week distribution.

### Scenario 7.5 — effectiveAmount vs amount Inconsistency

**Input:** Expense ID 12 has `amount = 35.00`, `effectiveAmount = 17.50` (split bill).  
**Expected:** Analytics totals use `effectiveAmount` (17.50). Legacy "Largest Transaction" uses `amount` (35.00). If ID 12 happens to be the largest, the displayed amount should be 35.00 raw, but the analytics total should use 17.50.  
**Verify:** `getLegacyInsights` shows `largest.amount` while `generateInsights` uses `effectiveAmount` everywhere else.

### Scenario 7.6 — TotalsAggregationEngine: Weekly Boundary Double-Count

**Input:** March 2026. Week containing March 30-April 5 straddles month boundary.
**Expected:** When viewing March monthly totals, the partial week (Mar 30-31) includes Mar 30 expense. When viewing April, the same week (Apr 1-5) should NOT re-count Mar 30-31 expenses.
**Test:** Sum of all monthly views for a year should equal the total yearly spend. If weeks are double-counted, the sum will be higher.

### Scenario 7.7 — StdDev Inconsistency: Sample vs Population

**Input:** 4 transactions: €10, €20, €30, €40. Mean = €25.
```
Sample variance (N-1): [(10-25)² + (20-25)² + (30-25)² + (40-25)²] / 3
  = [225 + 25 + 25 + 225] / 3 = 500 / 3 = 166.67
Sample StdDev = √166.67 ≈ 12.91

Population variance (N): 500 / 4 = 125
Population StdDev = √125 ≈ 11.18
```

**Test:** Feed same 4 transactions to `getStatisticalInsights()` (should use sample=12.91) and to `calculateLoyaltyScore()` (will use population=11.18). **Document the inconsistency.** The coefficient of variation differs: `12.91/25 = 0.516` (sample) vs `11.18/25 = 0.447` (population).

---

## 8. Forecasting Engine Crash Tests

### Scenario 8.1 — MonteCarloSimulator: End of Month (0 Days Remaining)

**Input:** `spentToDate = 1200.00`, `knownUpcoming = 0`, `budgetAmount = 1500`, `daysRemaining = 0`.  
**Expected:** All percentiles = 1200.00. `probUnderBudget = 1.0` (1200 <= 1500). No stochastic component.

### Scenario 8.2 — MonteCarloSimulator: Determinism

**Input:** Same inputs twice.  
**Expected:** Identical outputs (seed=42L guarantees reproducibility).

### Scenario 8.3 — FinancialStressForecast: No Expenses

**Input:** Brand new user, no expenses.  
```
currentBalance = 0 - 0 = 0
estimatedIncome: no deposits → fallback: totalBudget * (days/30)
recurringOutflows: no patterns → 0
MC simulation: no data → fallback: daysAhead * 20 + gaussian * 5
```

**Expected:** Risk level should be MODERATE (fallback), not crash. Verify `projectedBalance` and `crunchProbability` are finite numbers.

### Scenario 8.4 — Stress Forecast: Income >> Expenses

**Input:** Monthly income €10,000, expenses €500, no recurring.  
**Expected:** All 3 horizons should be LOW risk. `projectedBalance >> 0` at all horizons. `crunchProbability ≈ 0.0`.

### Scenario 8.5 — Stress Forecast: Recurring > Income (80% Threshold)

**Input:** Monthly income €2000, recurring subscriptions totaling €1700 (85% of income).  
**Expected:** Recommendation should include subscription/recurring warning (threshold is `> 0.80 * avgIncome`).

---

## 9. Savings Engine Crash Tests

### Scenario 9.1 — Round-Up Rule: Exact Multiple

**Input:** Expense €15.00, `roundUpTo = 5.00`.  
```
remainder = 15.00 % 5.00 = 0.0
```

**Expected:** `remainder == 0` → no savings generated. Rule should NOT trigger.

### Scenario 9.2 — Round-Up Rule: Standard

**Input:** Expense €17.30, `roundUpTo = 5.00`.  
```
remainder = 17.30 % 5.00 = 2.30
roundUpAmount = 5.00 - 2.30 = 2.70
```

**Expected:** `savingsAmount = 2.70`.

### Scenario 9.3 — Spare Change: Boundary

**Input A:** Expense €0.99 → below €1.00 range → rule does NOT trigger.  
**Input B:** Expense €1.00 → in range [1.0, 10.0] → `savingsAmount = 1.00`.  
**Input C:** Expense €10.00 → in range → `savingsAmount = 10.00`.  
**Input D:** Expense €10.01 → above range → rule does NOT trigger.

### Scenario 9.4 — Monthly Cap Exhaustion

**Input:** Rule with `maximumPerMonth = 50.00`. Already used €48.00 this month. New execution would save €5.00.  
**Expected:** `remainingAllowance = 50.00 - 48.00 = 2.00`. `allowedAmount = min(5.00, 2.00) = 2.00`.

### Scenario 9.5 — SmartSavingsEngine: No Budget (Fallback)

**Input:** No active budgets. Some spending data exists.  
```
Source 1 (budget surplus): 0.0 (no budgets)
Source 2 (pace): computed from spending
Source 3 (MC): computed or fallback
```

**Expected:** Confidence should be lower (1 source instead of 3). Verify cap is still enforced.

### Scenario 9.6 — SmartSavingsEngine: Horizon Caps

**Input:** Large surplus + pace + MC all suggest €1000.  
**Expected by horizon:**
- WEEK: capped at €75
- MONTH: capped at €200
- QUARTER: capped at €500

### Scenario 9.7 — Weekly No-Spend: Essential Categories Excluded

**Input:** 7-day period with only a €200 rent payment (category: "rent").  
```
discretionary = filter out "rent" (essential) → discretionary = 0
0 < 5.00 → trigger weekly no-spend reward
```

**Expected:** `rewardAmount = 10.00`. Verify "rent" is correctly excluded as essential.

---

## 10. Synthesis Engine Crash Tests

### Scenario 10.1 — Committed vs Likely Confidence Bands

**Input:** 3 recurring patterns:
- Pattern A: confidence=0.95 (COMMITTED, ≥0.90)
- Pattern B: confidence=0.85 (LIKELY, [0.70, 0.90))
- Pattern C: confidence=0.65 (NEITHER — below 0.70)

**Expected:**
- A included in `committedUpcomingBills` at 100%
- B included in `likelyUpcomingBills` at 100% (but planned expenses would be weighted at 70%)
- C excluded from both committed and likely

### Scenario 10.2 — Biweekly Recurring Detection Tolerance

**Input:** Pattern anchored on March 1 (Sunday). Check March 15 (Sunday = day 14).
```
daysDiff = 14
mod = floorMod(14, 14) = 0
distanceToCycle = min(0, 14-0) = 0
0 <= 2 → MATCH
```

**Input:** Check March 17 (Tuesday = day 16).
```
daysDiff = 16
mod = floorMod(16, 14) = 2
distanceToCycle = min(2, 14-2=12) = 2
2 <= 2 → MATCH
```

**Input:** Check March 18 (Wednesday = day 17).
```
daysDiff = 17
mod = floorMod(17, 14) = 3
distanceToCycle = min(3, 14-3=11) = 3
3 <= 2 → NO MATCH
```

**Expected:** March 15 and 17 are matches, March 18 is not.

### Scenario 10.3 — Discretionary Budget Calculation

**Input:**
- `budgetLimit = 2000`
- `totalMonthlyRecurring = 800` (rent)
- `totalMonthlyPlanned = 200` (must-have planned)
- `goalReserves = 100`
- `daysInMonth = 30`

```
discretionaryTotal = (2000 - 800 - 200 - 100) = 900
baseDiscretionaryRate = 900 / 30 = 30.00/day
```

**Expected:** `dailyTarget = 30.00 + recurringOnDay + plannedOnDay`.

### Scenario 10.4 — Dynamic Confidence Penalties

**Input A:** Has budget, has average, has patterns → `0.85 + 0 = 0.85`  
**Input B:** No budget → `0.85 - 0.15 = 0.70`  
**Input C:** No budget, no average → `0.85 - 0.15 - 0.10 = 0.60`  
**Input D:** No budget, no average, no patterns → `0.85 - 0.15 - 0.10 - 0.05 = 0.55`  
**Input E:** All penalties → `0.55`, still above minimum 0.10 ✓

### Scenario 10.5 — Risk Level Classification

**Input:** `paceStatus = OVER_PACE`, budget status includes one CRITICAL.  
**Expected:** `riskLevel = CRITICAL` (any CRITICAL budget → CRITICAL).

**Input:** `paceStatus = OVER_PACE`, no critical budgets, `bufferRatio = 0.04`.  
**Expected:** `riskLevel = CRITICAL` (overPace AND buffer ≤ 0.05).

---

## 11. Cross-Component Consistency Tests

These tests verify that values flowing between components are consistent end-to-end.

### Scenario 11.1 — Expense → Analytics → Dashboard: Amount Consistency

**Pipeline:** `ExpenseRepository.getExpenses()` → `InsightsEngine.generateInsights()` → `HomeViewModel` state.  
**Test:** The `currentMonthSpent` displayed on the dashboard MUST equal `Σ effectiveAmount` of PURCHASE expenses from the repository. No intermediate transformation should alter the total.  
**Golden value:** March = €1,283.59.

### Scenario 11.2 — BudgetCalculator → HealthScore: Period Alignment

**Pipeline:** `BudgetCalculator.calculatePeriodRange()` → filter expenses → `FinancialHealthScoreV2.calculateBudgetAdherenceScore()`  
**Test:** The period range used to fetch expenses for budget status MUST match the period range used in health score calculation. Misalignment → health score sees different expenses than budget.

### Scenario 11.3 — CurrencyConverter → EffectiveAmount → SpendingPace

**Pipeline:** `CurrencyConverter.convert()` → `expense.effectiveAmount` → `SpendingPaceCalculator.calculate()`  
**Test:** If a €100 expense is stored with `effectiveAmount = 100.00` in EUR, then converted to USD at rate 1.085, the `effectiveAmount` should be 108.50 (not 100.00). If the pace calculator uses the unconverted amount, it will miscalculate.

### Scenario 11.4 — Split Calculator → Balance → Settlement: Cent-Level Parity

**Pipeline:** `SplitCalculator.calculateSplitAmounts()` → `calculateBalances()` → `SettlementCalculator.calculateSettlements()`  
**Test:** For any set of group expenses, `Σ(settlement.amount)` must equal `Σ(|balance|) / 2`. And after all settlements are applied, all balances must be zero (within ±0.01).

**Golden scenario:** 3 members, 3 expenses:
- Expense 1: €90, paid by A, equal split
- Expense 2: €60, paid by B, equal split
- Expense 3: €30, paid by C, equal split

```
Per expense splits: each member owes 30/20/10 per expense
A paid: 90, owes: 30+20+10 = 60 → net: +30
B paid: 60, owes: 30+20+10 = 60 → net: 0
C paid: 30, owes: 30+20+10 = 60 → net: -30

Settlements: C→A: €30 (1 transaction)
Total volume: 30. |balances|/2 = (30+0+30)/2 = 30 ✓
Post-settlement: A=0, B=0, C=0 ✓
```

### Scenario 11.5 — RecurringExpenseEngine → SynthesisEngine → StressForecast: Pattern Consistency

**Pipeline:** `RecurringExpenseEngine.getPatterns()` → `SynthesisEngine.synthesize()` AND `FinancialStressForecastEngine.computeStressForecast()`  
**Test:** Both consumers receive the same patterns list. The recurring outflow total in SynthesisEngine (committed + likely) must be ≤ the recurring outflow in StressForecast (which uses confidence ≥ 0.50). Since SynthesisEngine uses ≥0.90 for committed and ≥0.70 for likely, and StressForecast uses ≥0.50, the stress forecast's outflows will be higher.  
**Verify:** `stressForecast.recurringOutflows >= synthesisEngine.committedUpcomingBills + synthesisEngine.likelyUpcomingBills`

### Scenario 11.6 — Dual Essential Category Sets

**Test:** `AutomatedSavingsRuleEngine.ESSENTIAL_CATEGORIES` and `SmartSavingsEngine.ESSENTIAL_CATEGORIES` must be identical sets. If they diverge, the "weekly no-spend" rule and the "discretionary spending" calculation will use different definitions of essential spending.

### Scenario 11.7 — Hardcoded EUR: Multi-Currency User

**Test for each hardcoded EUR assumption:**

| Component | Hardcoded Value | Expected Behavior for USD User |
|-----------|----------------|-------------------------------|
| `SplitCalculator.formatBalance` | `"$"` | Should use group's currency symbol |
| `SettlementCalculator.getSettlementSummary` | `"€"` | Should use group's currency symbol |
| `ReceiptScanViewModel.saveExpenseInternal` | `"EUR"` | Should use user's default currency |
| `FinancialStressForecastEngine` | €500 buffer, €20/day fallback | Should scale to user's currency |
| `SmartSavingsEngine` | €75/200/500 caps | Should scale to user's currency |
| `SharedExpenseManager.createGroup` | `"EUR"` default | OK as default, but should be configurable |

---

## 12. Numeric Precision & Overflow Tests

### Scenario 12.1 — Floating-Point Accumulation Drift

**Input:** 1000 expenses, each €33.33.
```
Exact total: 1000 × 33.33 = 33,330.00
FP accumulation: 33.33 + 33.33 + ... (1000 times)
```

**Test:** Verify the accumulated total equals €33,330.00 exactly (or document the drift magnitude).

### Scenario 12.2 — toCents Int Overflow

**Input:** `toCents(25_000_000.00)`
```
BigDecimal(25000000.00).setScale(2).movePointRight(2) = 2,500,000,000
.toInt() → wraps to -1,794,967,296 (signed overflow)
```

**Expected:** Incorrect result. Document the overflow boundary: **max safe amount = €21,474,836.47**.

### Scenario 12.3 — Cross-Rate Compound Precision

**Input:** GBP→EUR rate = 1.17000000001, EUR→JPY rate = 162.4999999999.  
`convert(99999.99, "GBP", "JPY")`
```
combinedRate = 1.17000000001 × 162.4999999999 = 190.124999...
convertedAmount = 99999.99 × 190.125 = 19,012,498.10
```

**Test:** Verify the result is correct to at least 2 decimal places. Document any FP error in the 3rd+ decimal.

### Scenario 12.4 — Health Score Weighted Sum Precision

**Input:** scores = [99, 99, 99, 99].
```
0.30*99 + 0.25*99 + 0.25*99 + 0.20*99 = 99.00
```

**Input:** scores = [100, 0, 100, 0].
```
0.30*100 + 0.25*0 + 0.25*100 + 0.20*0 = 55.00
```

**Test:** Verify `floor()` produces exact integer (no FP drift making 55.0 become 54.999...).

### Scenario 12.5 — Percentage Split with Many Decimals

**Input:** `totalAmount = 1000.00`, percentages = `{A: 14.285714, B: 14.285714, C: 14.285714, D: 14.285714, E: 14.285714, F: 14.285714, G: 14.285716}` (sums to ~100%).
```
Per member: 1000 * 0.14285714 = 142.857140
baseCents each = 14285
7 members * 14285 = 99995
remainder = 100000 - 99995 = 5
```

**Expected:** 5 members get 14286 cents (€142.86), 2 get 14285 (€142.85). Sum = 5×142.86 + 2×142.85 = 714.30 + 285.70 = 1000.00 ✓

---

## 13. Temporal Boundary Crash Tests

### Scenario 13.1 — Midnight Expense (Belongs to Which Day?)

**Input:** Expense at `2026-03-15T00:00:00.000` (midnight exactly).  
**Test:** Does `getExpensesBetween(startOfDay(Mar15), startOfDay(Mar16))` include this expense? With half-open `[start, end)`, midnight March 15 should be included in March 15's bucket.

### Scenario 13.2 — Month Boundary: Last Millisecond

**Input:** Expense at `2026-03-31T23:59:59.999`.  
**Test:** Included in March (yes) and NOT in April (correct).

### Scenario 13.3 — Leap Second (Theoretical)

**Input:** `2026-06-30T23:59:60.000` (if system supports leap seconds).  
**Expected:** No crash. Treat as next day if unsupported.

### Scenario 13.4 — Timezone: User in UTC-12 vs UTC+14

**Input:** Same epoch millis, different timezone. Budget period "March 2026".  
**Test:** The budget period boundaries differ by up to 26 hours between UTC-12 and UTC+14. Expenses near month boundaries may appear in different months. Verify consistent behavior.

### Scenario 13.5 — Year Boundary: Dec 31 → Jan 1

**Input:** Budget with YEARLY period, `anchorDate = Jan 15 2026`, `evaluationTime = Dec 31 2026 23:59`.  
**Expected:** Anniversary hasn't passed yet for Jan 15 2027. Period = Jan 15 2026 → Jan 15 2027.

### Scenario 13.6 — February Edge Cases in BudgetCalculator

**Input A:** Anchor day = 29, eval = Feb 2025 (non-leap). Coerced to 28.  
**Input B:** Anchor day = 30, eval = Feb 2024 (leap). Coerced to 29.  
**Input C:** Anchor day = 31, eval = Feb 2025. Coerced to 28.  
**Input D:** Anchor day = 31, eval = Apr 2026 (30-day month). Coerced to 30.

### Scenario 13.7 — Historical Baseline: Straddles DST Change

**Input:** Health score computes 3-month lookback from April 1 2026. The lookback covers January 1 - March 31, which includes DST change (March 29 in Europe).  
**Test:** `daysBetween()` should count calendar days, not `(endMs - startMs) / DAY_IN_MILLIS` which would give 89.958... days instead of 90.

---

## 14. Concurrency & State Race Tests

### Scenario 14.1 — ViewModel State Race: `_uiState.value = ...`

**Test:** Call two state-modifying methods concurrently from separate coroutines. With `_uiState.value = currentState.copy(...)`, the second write can overwrite the first.

```kotlin
// Simulate:
launch { viewModel.loadExpenses() }   // sets loading=true
launch { viewModel.updateFilter() }   // reads state, sets filter

// Race: updateFilter() reads state BEFORE loadExpenses() completes
// → loadExpenses() overwrites filter with old value
```

**Expected:** If using `.value = copy(...)`, race is present. If using `.update { }`, race is prevented.

### Scenario 14.2 — ReceiptScanViewModel: Rapid Re-scan

**Test:** Start item analysis (async), then immediately start a new scan before the first completes.  
**Expected without guard:** Both analyses run concurrently. First one completes and updates state. Second one completes and also updates state → stale data from first analysis leaks into final state.  
**Expected with fix:** First analysis is cancelled when second scan starts.

### Scenario 14.3 — SharedExpenseGroupsViewModel: Non-Atomic Creation

**Test:** `addExpense` creates a system expense, then a group expense. Simulate failure after system expense creation.  
**Expected:** Orphaned system expense exists in the database without a corresponding group expense. Verify this leaves the database in an inconsistent state.

### Scenario 14.4 — Savings Rule Monthly Cap Across Process Death

**Test:**
1. Trigger rule execution → saves €40 of €50 monthly cap
2. Kill process
3. Restart → in-memory `monthToDateRuleTotals` map is empty
4. Trigger rule again → allows full €50 (total = €90, exceeds cap)

**Expected:** Cap is violated. Document that monthly cap needs persistence.

---

## 15. Implementation Priority

### Tier 1 — Must Have (blocks confidence in core calculations)

| # | Scenario Set | Est. Tests | Effort |
|---|---|---|---|
| 1 | Split & Settlement (4.1-4.12) | ~15 | 1 day |
| 2 | Budget Calculator (2.1-2.8) | ~10 | 0.5 day |
| 3 | Cross-Component Consistency (11.1-11.7) | ~10 | 1 day |
| 4 | Numeric Precision (12.1-12.5) | ~8 | 0.5 day |
| 5 | Spending Pace (3.1-3.6) | ~8 | 0.5 day |

### Tier 2 — High Priority (validates E2E financial correctness)

| # | Scenario Set | Est. Tests | Effort |
|---|---|---|---|
| 6 | Financial Health Score (6.1-6.7) | ~10 | 0.5 day |
| 7 | Analytics Pipeline E2E (7.1-7.7) | ~12 | 1 day |
| 8 | Savings Engine (9.1-9.7) | ~10 | 0.5 day |
| 9 | Temporal Boundaries (13.1-13.7) | ~10 | 0.5 day |

### Tier 3 — Important (validates secondary engines and edge cases)

| # | Scenario Set | Est. Tests | Effort |
|---|---|---|---|
| 10 | Currency Converter (5.1-5.9) | ~10 | 0.5 day |
| 11 | Forecasting Engine (8.1-8.5) | ~8 | 0.5 day |
| 12 | Synthesis Engine (10.1-10.5) | ~8 | 0.5 day |
| 13 | Concurrency & State Races (14.1-14.4) | ~6 | 0.5 day |

### Total Estimated Effort

| Tier | Tests | Days |
|------|-------|------|
| Tier 1 | ~51 | 3.5 days |
| Tier 2 | ~42 | 2.5 days |
| Tier 3 | ~32 | 2.0 days |
| **Total** | **~125** | **~8 days** |

---

## Appendix A: Constants Reference (for test writers)

All hardcoded values that tests must verify against:

| Engine | Constant | Value |
|--------|----------|-------|
| SpendingPace | UNDER threshold | `< 90%` |
| SpendingPace | OVER threshold | `> 110%` |
| SpendingPace | Conservative multiplier | `3.0` |
| SpendingPace | Full-linear day | `7` |
| HealthScore | Savings weight | `0.30` |
| HealthScore | Runway weight | `0.25` |
| HealthScore | Budget weight | `0.25` |
| HealthScore | Bills weight | `0.20` |
| HealthScore | Savings target | `20%` |
| HealthScore | Runway target | `6 months` |
| HealthScore | Trend threshold | `±5 points` |
| HealthScore | Bill default | `75` |
| HealthScore | Neutral default | `50` |
| SplitCalculator | Settled threshold | `0.01` |
| toCents | Max safe amount | `€21,474,836.47` |
| toCents | Rounding | `HALF_UP` |
| MonteCarlo | Iterations | `1000` |
| MonteCarlo | Seed | `42L` |
| StressForecast | Buffer threshold | `€500` |
| StressForecast | Fallback daily | `€20/day` |
| StressForecast | Risk: LOW | `P < 0.10` |
| StressForecast | Risk: MODERATE | `0.10-0.25` |
| StressForecast | Risk: ELEVATED | `0.25-0.50` |
| StressForecast | Risk: HIGH | `0.50-0.75` |
| StressForecast | Risk: CRITICAL | `≥ 0.75` |
| SmartSavings | WEEK cap | `€75` |
| SmartSavings | MONTH cap | `€200` |
| SmartSavings | QUARTER cap | `€500` |
| SmartSavings | Budget factor | `0.50` |
| SmartSavings | Pace factor | `0.30` |
| SmartSavings | MC factor | `0.20` |
| Synthesis | Likely weight | `0.70` |
| Synthesis | Committed conf | `≥ 0.90` |
| Synthesis | Likely conf | `[0.70, 0.90)` |
| Synthesis | Base confidence | `0.85` |
| Synthesis | Biweekly tolerance | `±2 days` |
| Anomaly | Merchant multipliers | `5.0 / 4.0 / 3.0` |
| Anomaly | Min tx count | `3` |
| Anomaly | Result cap | `10` |
| AdvancedAnalytics | Trend thresholds | `±5% / ±20%` |
| AdvancedAnalytics | Budget: WARNING | `75%` |
| AdvancedAnalytics | Budget: CRITICAL | `90%` |
| AdvancedAnalytics | Budget: EXCEEDED | `100%` |
| AdvancedAnalytics | Impulse buyer CV | `> 1.0` |
| AdvancedAnalytics | Weekend warrior | `> 50%` |
| InsightsEngine | Spending increase | `> 20%` |
| InsightsEngine | Spending decrease | `< -15%` |
| InsightsEngine | Category trend | `> 40%` AND `> €50` |
| CurrencyConverter | Cross-rate base | `EUR` |

---

## Appendix B: Known Bugs to Verify via Tests

These are confirmed or suspected bugs from prior code reviews. Each crash-test scenario above is designed to expose them.

| Bug ID | Description | Scenario |
|--------|-------------|----------|
| B-01 | `toCents()` uses `.toInt()` — silent overflow above €21.47M | 12.2, 4.5 |
| B-02 | `SplitCalculator` and `SharedExpenseManager` have duplicated logic that could diverge | 4.9, 11.4 |
| B-03 | `SettlementCalculator` DFS has no depth/time guard — exponential blowup | 4.8 |
| B-04 | `FinancialHealthScoreV2` uses `.toInt()` (truncation, not rounding) | 6.6 |
| B-05 | Bill reliability defaults to 75 while everything else defaults to 50 (asymmetry) | 6.5 |
| B-06 | `AdvancedAnalyticsEngine` uses population StdDev in some places, sample in others | 7.7 |
| B-07 | `InsightsEngine.getLegacyInsights` uses `amount` for largest tx while analytics uses `effectiveAmount` | 7.5 |
| B-08 | `TotalsAggregationEngine` weekly boundary can double-count expenses across months | 7.6 |
| B-09 | Hardcoded EUR amounts in stress forecast, savings, settlement | 11.7 |
| B-10 | Savings rule monthly cap is in-memory only — reset on process death | 14.4 |
| B-11 | `_uiState.value = copy(...)` pattern allows state races | 14.1 |
| B-12 | `SharedExpenseGroupsViewModel.addExpense` is non-atomic (orphan risk) | 14.3 |
| B-13 | `ReceiptScanViewModel` has no cancellation guard for in-flight item analysis | 14.2 |
| B-14 | `SpendingPaceCalculator.pacePercentage` uses `toFloat()` — precision loss near boundaries | 3.6 |
| B-15 | `BudgetCalculator` ROLLING MONTHLY uses 30 fixed days, not calendar month | 2.2 |
| B-16 | Dual essential category sets in savings engines could diverge | 11.6 |
| B-17 | `InsightsEngine` day-of-week mapping uses arithmetic that assumes Calendar constants | 7.4 |
| B-18 | `Quarter calculation` in AdvancedAnalytics: `getMonth()/3+1` may be wrong for 1-based months | 13.* |
