# Test Coverage Analysis & Recommendations

## Executive Summary

**Current State:** The test suite covers ~20 test files with good coverage for **parsers** and **basic DAO operations**, but has **significant gaps** in stress testing the logic-heavy components.

| Component | Test Coverage | Edge Cases | Stress Tests |
|-----------|---------------|------------|--------------|
| InsightsEngine | ⚠️ Partial | ❌ Missing | ❌ None |
| SynthesisEngine | ⚠️ Partial | ❌ Missing | ❌ None |
| RecurringExpenseEngine | ⚠️ Partial | ❌ Missing | ❌ None |
| ConfidenceRouter | ✅ Good | ⚠️ Some | ❌ None |
| Receipt Parser (OCR) | ✅ Good | ⚠️ Some | ❌ None |
| Parsers (Revolut, Greek, etc.) | ✅ Good | ✅ Good | ❌ None |
| ML Classifiers | ⚠️ Basic | ❌ Missing | ❌ None |
| Budget Monitor | ❌ None | ❌ Missing | ❌ None |

---

## 1. Existing Test Analysis

### ✅ Well-Tested Components

#### Parsers (Revolut, GreekBank, GoogleWallet, SMS, Generic)
- **Good coverage:** Multiple parsing patterns tested
- **Edge cases covered:** Null inputs, empty strings, amount bounds, rejection of OTP/promotional content
- **Missing:** Large-scale stress tests with 1000+ random inputs

#### ConfidenceRouter
- **Tests exist:** 9 tests covering thresholds, penalties, boosts
- **Good:** Tests for spam source, merchant rejection rate, previous approvals
- **Missing:** Boundary tests at exact threshold values (0.8499 vs 0.85)

#### OCR Parser (ReceiptParser)
- **Tests exist:** 15 tests for decimal parsing, Greek normalization
- **Good:** Tests for OCR artifacts (EYNONO, ZYNOAO, 2YNONO, IYNOAO)
- **Missing:** Complex multi-line receipts, corrupted OCR, very long receipts

---

### ⚠️ Partially Tested Components

#### InsightsEngine (7 tests)
```kotlin
// Current tests:
- detects monthly recurring payments
- detects weekly recurring payments  
- does not detect irregular payments
- ignores single-occurrence merchants
- buildDailyTotals includes all requested days
- buildDailyTotals sums same-day purchases
- buildDailyTotals ignores non-purchase types
```

**Missing Edge Cases:**
- ❌ Empty expense list
- ❌ Single expense (division/edge cases)
- ❌ All same-day expenses (projection calculation)
- ❌ Leap year February handling
- ❌ Month boundary (31st → 1st)
- ❌ Year boundary (Dec 31 → Jan 1)
- ❌ Duplicate expenses (same merchant, amount, time)
- ❌ Very large amounts (1,000,000+)
- ❌ Very small amounts (0.01)
- ❌ Mixed currencies

#### SynthesisEngine (1 test!)
```kotlin
// Only tests determineRiskLevel() logic - not the full synthesize() method
- test risk level logic (6 scenarios)
```

**Critical Missing Tests:**
- ❌ **CONFIDENCE RANGE GAP BUG** - No test for patterns with confidence 0.89-0.90
- ❌ Full synthesize() method test
- ❌ Empty lists for any input
- ❌ Goal reserve calculations
- ❌ Discretionary budget calculation
- ❌ Month boundary projections
- ❌ No-budget scenario (budgetLimit = 0)
- ❌ Multiple Calendar instances consistency

#### RecurringExpenseEngine (5 tests)
```kotlin
// Current tests:
- should detect perfect monthly subscription
- should detect bi-weekly salary
- should ignore random coffee purchases
- should ignore variable bills (high amount variance)
- manual override should take precedence
```

**Missing Edge Cases:**
- ❌ **Boundary tests** - 23 days (biweekly vs monthly boundary)
- ❌ Weekly pattern (7 days exactly)
- ❌ Annual pattern (365 days)
- ❌ Quarterly pattern (90 days)
- ❌ Leap year handling
- ❌ DST transition periods
- ❌ Variable amounts within 35% threshold
- ❌ Merchant name variations (Netflix vs NETFLIX vs netflix.com)
- ❌ Exactly 3 occurrences (minimum threshold)
- ❌ Confidence at exactly 0.50 (threshold boundary)

---

### ❌ Untested Components

#### BudgetMonitor
**No tests exist!** Critical component for:
- Budget spent amount calculation
- Period calculations
- Notification triggers
- Rollover logic

#### TransactionClassifier (Naive Bayes)
**No tests exist!** Missing:
- Training behavior
- Prediction with empty vocabulary
- Probability calculations
- Log probability overflow handling

#### ExpenseCategoryClassifier
**No tests exist!** Missing:
- Classification accuracy
- Category scoring
- Softmax normalization

---

## 2. Critical Missing Tests for Confirmed Bugs

### Bug #1: Confidence Range Gap (CRITICAL)
```kotlin
// MUST ADD THIS TEST
@Test
fun `patterns with confidence 0_89 to 0_90 are not excluded`() = runTest {
    // Create a pattern that would have confidence 0.894
    val expenses = createExpensesWithConfidence("TestMerchant", confidence = 0.894f)
    coEvery { expenseDao.getExpensesSince(any()) } returns expenses
    
    val patterns = engine.getPatterns()
    
    // This test WILL FAIL with current code
    assertTrue(patterns.isNotEmpty())
    assertTrue(patterns.any { it.confidence >= 0.70f && it.confidence < 0.90f })
}
```

### Bug #2: projectedCategorySpending Always Empty
```kotlin
@Test
fun `synthesize returns non-empty projectedCategorySpending`() = runTest {
    val forecast = engine.synthesize(
        pastSumDaily = listOf(10.0, 20.0, 30.0),
        recurringPatterns = createRecurringPatterns(),
        plannedExpenses = createPlannedExpenses(),
        savingsGoals = emptyList(),
        budgetStatuses = createBudgetStatuses(),
        spendingPace = createSpendingPace()
    )
    
    // This test WILL FAIL - projectedCategorySpending is always empty
    assertTrue(forecast.components.projectedCategorySpending.isNotEmpty())
}
```

---

## 3. Recommended Stress Tests

### 3.1 InsightsEngine Stress Tests

```kotlin
class InsightsEngineStressTest {
    
    @Test
    fun `handle 10000 expenses without timeout`() = runTest {
        val expenses = List(10000) { i ->
            makeExpense("Merchant$i", (i % 100 + 1).toDouble(), i)
        }
        
        val start = System.currentTimeMillis()
        val recurring = engine.detectRecurring(expenses)
        val duration = System.currentTimeMillis() - start
        
        assertTrue("Should complete in under 5 seconds", duration < 5000)
    }
    
    @Test
    fun `handle extreme amount values`() = runTest {
        val expenses = listOf(
            makeExpense("Tiny", 0.01, 0),
            makeExpense("Huge", 999999.99, 0),
            makeExpense("Negative", -100.0, 0), // Should be handled
        )
        
        // Should not crash or produce NaN/Infinity
        val totals = engine.buildDailyTotals(expenses, 1)
        assertFalse(totals.values.any { it.isNaN() || it.isInfinite() })
    }
    
    @Test
    fun `handle leap year February correctly`() = runTest {
        // Test February 2024 (leap year - 29 days)
        // Test February 2023 (non-leap year - 28 days)
        // Verify day count calculations
    }
    
    @Test
    fun `month boundary spending pace calculation`() = runTest {
        // Test on Jan 31 - daysRemaining should handle correctly
        // Test on Feb 1 - new month calculations
        // Test on Dec 31 - year boundary
    }
}
```

### 3.2 SynthesisEngine Stress Tests

```kotlin
class SynthesisEngineStressTest {
    
    @Test
    fun `empty inputs return valid forecast`() = runTest {
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = SpendingPace(0.0, 1, 30, 0.0, null, null, 0f, PaceStatus.NO_BASELINE)
        )
        
        assertNotNull(forecast)
        assertEquals(RiskLevel.LOW, forecast.components.riskLevel)
    }
    
    @Test
    fun `confidence boundary 0_89999 and 0_90 included correctly`() = runTest {
        val patterns = listOf(
            createPattern("A", confidence = 0.70f),   // Should be likely
            createPattern("B", confidence = 0.89f),   // Should be likely (inclusive)
            createPattern("C", confidence = 0.899f),  // BUG: Currently excluded!
            createPattern("D", confidence = 0.90f),   // Should be committed
            createPattern("E", confidence = 0.95f),   // Should be committed
        )
        
        val forecast = engine.synthesize(
            recurringPatterns = patterns,
            // ... other params
        )
        
        // Verify all patterns are counted
        val totalFromPatterns = patterns.sumOf { it.averageAmount }
        val countedTotal = forecast.components.totalCommitted + forecast.components.totalLikely
        
        // This test WILL FAIL due to the gap bug
        assertEquals(totalFromPatterns, countedTotal, 0.01)
    }
    
    @Test
    fun `no budget scenario returns appropriate risk level`() = runTest {
        val forecast = engine.synthesize(
            budgetStatuses = emptyList(), // No budget
            // ... other params
        )
        
        // With no budget, bufferRatio = 0.0, currently returns HIGH
        // Should probably have special NO_BUDGET handling
        assertNotNull(forecast.components.riskLevel)
    }
    
    @Test
    fun `midnight boundary calendar consistency`() = runTest {
        // Run synthesis at 23:59:59 and 00:00:01
        // Verify consistent results within same day
    }
}
```

### 3.3 RecurringExpenseEngine Stress Tests

```kotlin
class RecurringExpenseEngineStressTest {
    
    @Test
    fun `exactly 3 occurrences minimum threshold`() = runTest {
        val expenses = List(3) { i ->
            createExpense("Test", 10.0, "2026-0${i+1}-01")
        }
        
        val patterns = engine.getPatterns()
        // Should detect - 3 is minimum
        assertTrue(patterns.isNotEmpty())
    }
    
    @Test
    fun `exactly 2 occurrences should not detect`() = runTest {
        val expenses = List(2) { i ->
            createExpense("Test", 10.0, "2026-0${i+1}-01")
        }
        
        val patterns = engine.getPatterns()
        // Should NOT detect - 2 < 3 minimum
        assertTrue(patterns.isEmpty())
    }
    
    @Test
    fun `frequency boundary 23 days classification`() = runTest {
        // Create expenses with exactly 23-day intervals
        // Could be classified as BIWEEKLY or MONTHLY
        // Document expected behavior
    }
    
    @Test
    fun `confidence exactly 0_50 threshold`() = runTest {
        // Create expenses that result in exactly 50% confidence
        // Currently accepted - verify this is intended
    }
    
    @Test
    fun `DST spring forward handling`() = runTest {
        // Create expenses around DST transition (March)
        // Verify day calculation is correct
    }
    
    @Test
    fun `DST fall back handling`() = runTest {
        // Create expenses around DST transition (October/November)
        // Verify day calculation is correct
    }
    
    @Test
    fun `merchant case variations grouped together`() = runTest {
        val expenses = listOf(
            createExpense("Netflix", 10.0, "2026-01-01"),
            createExpense("NETFLIX", 10.0, "2026-02-01"),
            createExpense("netflix", 10.0, "2026-03-01"),
        )
        
        val patterns = engine.getPatterns()
        // BUG: Currently creates 3 separate patterns instead of 1
        // Merchant normalization not applied!
    }
}
```

### 3.4 BudgetMonitor Tests (None Exist!)

```kotlin
class BudgetMonitorTest {
    
    @Test
    fun `calculate budget status for category budget`() = runTest {
        // Test category-specific budget calculation
    }
    
    @Test
    fun `calculate budget status for overall budget`() = runTest {
        // Test overall budget calculation
    }
    
    @Test
    fun `period calculation monthly`() = runTest {
        // Test month start/end calculation
    }
    
    @Test
    fun `period calculation weekly`() = runTest {
        // Test week start/end calculation
    }
    
    @Test
    fun `notification triggered at warning threshold`() = runTest {
        // Test 75% threshold triggers warning
    }
    
    @Test
    fun `notification triggered at critical threshold`() = runTest {
        // Test 90% threshold triggers critical
    }
    
    @Test
    fun `notification not duplicated within cooldown period`() = runTest {
        // Test deduplication logic
    }
    
    @Test
    fun `rollover adds unspent to next period`() = runTest {
        // Test rollover calculation
    }
    
    @Test
    fun `last day of month daysRemaining calculation`() = runTest {
        // Test Jan 31 - daysRemaining should be 1 (coerced from 0)
    }
}
```

---

## 4. Recommended Test Implementation Priority

### P0 - Critical (Immediate)
1. **Confidence range gap test** - Validates Bug #1
2. **SynthesisEngine empty inputs test** - Validates crash prevention
3. **BudgetMonitor basic tests** - No tests exist for critical component

### P1 - High (This Sprint)
1. RecurringExpenseEngine boundary tests (23 days, 0.50 confidence)
2. InsightsEngine month boundary tests
3. Merchant case variation grouping test

### P2 - Medium (Next Sprint)
1. Stress tests for 10,000+ expenses
2. DST transition tests
3. Large amount handling tests

---

## 5. Test Utilities to Create

```kotlin
// Test data builders for cleaner tests
object TestDataBuilder {
    
    fun expense(
        merchant: String = "TestMerchant",
        amount: Double = 10.0,
        date: String = "2026-01-15", // YYYY-MM-DD format
        type: TransactionType = TransactionType.PURCHASE
    ): Expense { ... }
    
    fun recurringPattern(
        merchant: String = "Netflix",
        amount: Double = 15.0,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        confidence: Float = 0.90f
    ): RecurringPattern { ... }
    
    fun budgetStatus(
        amount: Double = 500.0,
        spent: Double = 250.0,
        healthStatus: BudgetHealthStatus = BudgetHealthStatus.ON_TRACK
    ): BudgetStatus { ... }
    
    fun spendingPace(
        currentSpent: Double = 200.0,
        dayOfMonth: Int = 15,
        daysInMonth: Int = 30,
        paceStatus: PaceStatus = PaceStatus.ON_PACE
    ): SpendingPace { ... }
}
```

---

## 6. Summary

The current test suite provides good coverage for **parsing** but is **insufficient** for the logic-heavy components:

| Action Required | Count |
|-----------------|-------|
| New test files needed | 3 (BudgetMonitor, TransactionClassifier, ExpenseCategoryClassifier) |
| Critical bug tests needed | 2 |
| Edge case tests needed | ~30 |
| Stress tests needed | ~10 |
| Total new tests estimated | ~45 |

**Recommendation:** Create a dedicated test sprint to implement P0 and P1 tests before any major releases.
