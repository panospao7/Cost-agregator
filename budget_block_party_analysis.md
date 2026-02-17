# Budget Block Party: Deep Analysis & Smart Implementation

## Executive Summary

The "Budget Block Party" feature is a visual calendar grid showing daily spending performance against a calculated daily budget. This document analyzes how to make it intelligent by leveraging the existing infrastructure: `SynthesisEngine`, `RecurringExpenseEngine`, `BudgetRepository`, and `FinancialWeatherRepository`.

---

## Part 1: The Core Question - What Should "Daily Budget" Be?

### 1.1 Naive Approach (Not Recommended)

```
Daily Budget = Overall Monthly Budget / Days In Month
```

**Why this fails:**
- Ignores recurring expenses (rent on 1st = instant red day)
- Ignores planned expenses
- Ignores variable spending patterns
- Doesn't account for budget rollover

### 1.2 Smart Approach (Recommended)

The daily budget should be **dynamic and context-aware**, calculated as:

```
Daily Discretionary Budget = (Budget Limit - Committed Expenses - Goal Reserves) / Remaining Days
```

This is **exactly what `SynthesisEngine` already computes** as `discretionaryBudget / daysRemaining`.

---

## Part 2: Existing Infrastructure We Can Leverage

### 2.1 SynthesisEngine (`domain/logic/SynthesisEngine.kt`)

**Already Computes:**

| Metric | How It's Calculated | Value for Block Party |
|--------|---------------------|----------------------|
| `totalCommitted` | `committedUpcomingBills + committedPlanned` | Subtract from daily budget awareness |
| `typicalDailyDiscretionary` | `(avgMonthlyTotal - monthlyRecurringTotal) / daysInMonth` | **Perfect baseline for daily budget** |
| `discretionaryBudget` | `budgetLimit - (spentSoFar + projectedObligations + goalReserves)` | **Remaining safe-to-spend pool** |

**Key Code Extract:**
```kotlin
val typicalDailyDiscretionary = spendingPace.averageMonthlyTotal?.let { 
    (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth 
} ?: (spendingPace.previousMonthTotal?.let { 
    (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth 
}) ?: 0.0
```

**Insight:** Your engine already knows to subtract `monthlyRecurringTotal` before dividing - this is crucial for Block Party to avoid penalizing users for predictable bills.

### 2.2 RecurringExpenseEngine (`domain/logic/RecurringExpenseEngine.kt`)

**Already Provides:**

| Data | Type | Use for Block Party |
|------|------|---------------------|
| `monthlyRecurringTotal` | Calculated sum | Pre-allocate these amounts to specific days |
| `RecurringPattern.nextExpectedDate` | `Long` timestamp | Know exactly which day rent/Netflix hits |
| `RecurringPattern.frequency` | WEEKLY/MONTHLY/etc. | Handle different billing cycles |
| `RecurringPattern.confidence` | 0.0-1.0 Float | Only pre-allocate high-confidence patterns |

**Monthly Recurring Calculation (from SynthesisEngine):**
```kotlin
val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
    when (pattern.frequency) {
        RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (daysInMonth.toDouble() / 7.0)
        RecurrenceFrequency.BIWEEKLY -> pattern.averageAmount * (daysInMonth.toDouble() / 14.0)
        RecurrenceFrequency.MONTHLY -> pattern.averageAmount
        RecurrenceFrequency.QUARTERLY -> pattern.averageAmount / 3.0
        RecurrenceFrequency.SEMI_ANNUALLY -> pattern.averageAmount / 6.0
        RecurrenceFrequency.ANNUALLY -> pattern.averageAmount / 12.0
        else -> 0.0
    }
}
```

### 2.3 BudgetRepository (`data/repository/BudgetRepository.kt`)

**Already Handles:**

| Feature | Implementation | Relevance |
|---------|---------------|-----------|
| **Budget Periods** | DAILY, WEEKLY, MONTHLY, YEARLY | Must normalize to daily |
| **Rollover** | Compounding surplus carry-forward | Adjust daily budget accordingly |
| **Category Budgets** | Budget per category + overall budget | Which budget to show? |
| **Period Windows** | `calculatePeriodWindow()` | Know budget cycle boundaries |

**Critical Code for Rollover:**
```kotlin
if (budget.rollover) {
    var effectiveLimit = budget.amount
    while (movingWindow.second <= window.first) {
        val spentInPeriod = getSpentInRange(movingWindow.first, movingWindow.second)
        val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
        effectiveLimit = budget.amount + surplus
        // Move to next period
    }
    limit = effectiveLimit
}
```

**Insight:** If user has rollover enabled, their effective budget might be HIGHER than the nominal amount. Block Party should use `effectiveLimit`, not `budget.amount`.

### 2.4 ExpenseDao (`data/database/dao/ExpenseDao.kt`)

**Already Has:**
```kotlin
@Query("""
    SELECT (date / 86400000) as dayEpoch, SUM(amount) as total, COUNT(*) as txCount
    FROM expenses 
    WHERE transactionType = 'PURCHASE' 
    AND date >= :startMs AND date < :endMs
    GROUP BY dayEpoch
    ORDER BY dayEpoch ASC
""")
suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>
```

**Perfect for Block Party** - one query gets all daily totals for the month.

---

## Part 3: Proposed Daily Budget Formula

### 3.1 The Smart Daily Budget

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        DAILY BUDGET CALCULATION                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   effectiveBudget = budget.amount                                        │
│                     + (rollover ? accumulatedSurplus : 0)                │
│                                                                          │
│   allocatedToRecurring = sum of recurring expenses for this month        │
│                          (weighted by confidence ≥ 0.85)                 │
│                                                                          │
│   allocatedToPlanned = sum of MUST/LIKELY planned expenses this month    │
│                                                                          │
│   goalReserves = sum of STRICT savings goal monthly contributions        │
│                                                                          │
│   discretionaryPool = effectiveBudget                                    │
│                       - allocatedToRecurring                              │
│                       - allocatedToPlanned                                │
│                       - goalReserves                                      │
│                                                                          │
│   dailyBudget = discretionaryPool / daysInMonth                          │
│                                                                          │
│   TODAY's dailyBudget = discretionaryPoolRemaining / daysRemaining       │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Why Two Daily Budgets?

| Metric | Formula | Use Case |
|--------|---------|----------|
| **Static Daily Budget** | `discretionaryPool / daysInMonth` | Display at start of month, consistent messaging |
| **Dynamic Daily Budget** | `discretionaryPoolRemaining / daysRemaining` | Realistic "catch-up" target if overspent |

**Recommendation:** Show the **Static Daily Budget** as the baseline, but use **Dynamic** for "Today's Target" in a secondary label.

---

## Part 4: The "Red Day" Problem - How to Handle Recurring Expenses

### 4.1 The Problem

If a user pays rent on the 1st (€800) and their daily budget is €30, day 1 will show RED even though rent is expected.

### 4.2 Solution Options

#### Option A: "Burn It Early" (Recommended)
Pre-allocate recurring expenses to their expected day, reducing that day's discretionary budget.

```
Day 1:  Daily Budget = €30 - €800 (rent) = €-770 → Special "Bill Day" indicator
Day 2:  Daily Budget = €30
Day 15: Daily Budget = €30 - €15 (Netflix) = €15
```

**Visual Treatment:**
```
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ 🔵  │ 🟢  │ 🟢  │ 🟢  │ 🟢  │ 🟢  │ 🟢  │
│ 💸  │     │     │     │     │     │     │
│-770 │ 30  │ 30  │ 30  │ 30  │ 30  │ 30  │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┘
```

The 🔵 blue day with 💸 money bag indicates "expected bill day - not counted against you."

#### Option B: "Smooth It Out"
Deduct monthly recurring total from the budget pool BEFORE calculating daily budget.

```
dailyBudget = (budget - monthlyRecurringTotal) / daysInMonth
```

**Pros:** No red days for expected bills  
**Cons:** User loses visibility into which days money "leaves"

#### Option C: "Hybrid" (Recommended for Best UX)

1. Show recurring expenses as **pre-allocated markers** on their expected days
2. Don't count them as "over budget" - they're **expected**
3. Use a **different visual treatment** (outlined block vs filled block)

```
Day 1:  [Rent €800] → White block with outline + rent icon
Day 15: [Netflix €15] → White block with outline
Other days: Green/Red based on discretionary spending
```

---

## Part 5: Handling Budget Period Complexity

### 5.1 The User's Budget May Not Be Monthly

Your `BudgetPeriod` enum supports:
- `DAILY`
- `WEEKLY`
- `MONTHLY`
- `YEARLY`

### 5.2 Normalization Strategy

| Budget Period | Normalization to Block Party (Monthly Grid) |
|--------------|---------------------------------------------|
| `DAILY` | Multiply by `daysInMonth` → treat as monthly |
| `WEEKLY` | Multiply by `weeksInMonth` (~4.33) → treat as monthly |
| `MONTHLY` | Direct use |
| `YEARLY` | Divide by 12 → treat as monthly |

**Code:**
```kotlin
fun getMonthlyBudgetEquivalent(budget: Budget, daysInMonth: Int): Double {
    return when (budget.period) {
        BudgetPeriod.DAILY -> budget.amount * daysInMonth
        BudgetPeriod.WEEKLY -> budget.amount * (daysInMonth / 7.0)
        BudgetPeriod.MONTHLY -> budget.amount
        BudgetPeriod.YEARLY -> budget.amount / 12.0
    }
}
```

### 5.3 Rollover Makes It Interesting

If user has `rollover = true`, their effective budget already includes surplus from previous periods:

```kotlin
// BudgetRepository already computes this:
var effectiveLimit = budget.amount
while (movingWindow.second <= window.first) {
    val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
    effectiveLimit = budget.amount + surplus
}
```

**Block Party should use `effectiveLimit`**, not the raw `budget.amount`.

---

## Part 6: Which Budget to Display?

### 6.1 The Multi-Budget Problem

Users can have:
- **Overall Budget** (categoryId = null)
- **Category Budgets** (categoryId = specific)

### 6.2 Resolution Hierarchy

```
1. If Overall Budget exists → Use it for Block Party
2. Else, sum all Category Budgets → Use as effective overall budget
3. Else, no budget → Show "Set a budget to unlock this feature" state
```

**Already Implemented in SynthesisEngine:**
```kotlin
val overallBudget = budgetStatuses.find { it.budget.categoryId == null }?.budget?.amount ?: 0.0
val categoryBudgetsSum = budgetStatuses.filter { it.budget.categoryId != null }.sumOf { it.budget.amount }
val budgetLimit = if (overallBudget > 0) overallBudget else categoryBudgetsSum
```

---

## Part 7: Data Model for Block Party

### 7.1 Proposed Data Classes

```kotlin
data class BudgetBlockPartyData(
    val daysInMonth: Int,
    val currentDay: Int,
    val dailyBudget: Double,                    // Static daily target
    val dynamicDailyBudget: Double,             // Remaining / days left
    val blocks: List<BlockDay>,
    val recurringMarkers: List<RecurringMarker>,
    val summary: BlockPartySummary,
    val effectiveBudget: Double,                // Including rollover
    val monthlyRecurringTotal: Double,
    val goalReserves: Double
)

data class BlockDay(
    val dayOfMonth: Int,
    val spent: Double,
    val dailyBudget: Double,                    // This day's specific budget
    val state: BlockState,
    val isToday: Boolean,
    val isFuture: Boolean,
    val transactions: Int                       // Transaction count for drill-down
)

data class RecurringMarker(
    val dayOfMonth: Int,
    val merchantName: String,
    val amount: Double,
    val confidence: Float
)

data class BlockPartySummary(
    val greenDays: Int,
    val redDays: Int,
    val billDays: Int,                          // Days with expected recurring
    val totalSpent: Double,
    val totalBudget: Double,
    val onTrackPercentage: Float                // greenDays / passedDays
)

enum class BlockState {
    GOOD,           // Spent ≤ daily budget
    OVER,           // Spent > daily budget  
    BILL_DAY,       // Expected recurring expense day
    FUTURE,         // Not yet happened
    TODAY           // Current day
}
```

### 7.2 Repository Method

```kotlin
@Singleton
class BudgetBlockPartyRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val budgetMonitor: BudgetMonitor
) {
    fun getBlockPartyData(): Flow<BudgetBlockPartyData> = combine(
        budgetRepository.getBudgetStatuses(),
        expenseDao.getDailyTotalsForPeriod(monthStart, monthEnd),
        recurringExpenseEngine.getPatterns(),
        plannedExpenseDao.getAllPlannedExpenses(),
        savingsGoalDao.getAllGoals()
    ) { budgetStatuses, dailyTotals, recurringPatterns, plannedExpenses, goals ->
        // Calculate all the metrics using existing engine logic
        calculateBlockPartyData(
            budgetStatuses, 
            dailyTotals, 
            recurringPatterns, 
            plannedExpenses, 
            goals
        )
    }
}
```

---

## Part 8: Smart Features Deep Dive

### 8.1 Feature: Pre-Allocate Recurring Expenses

**Using: `RecurringExpenseEngine` + `SynthesisEngine`**

```kotlin
fun allocateRecurringToDays(
    patterns: List<RecurringPattern>,
    daysInMonth: Int,
    monthStart: Long,
    monthEnd: Long
): Map<Int, Double> {
    val allocation = mutableMapOf<Int, Double>()
    val calendar = Calendar.getInstance()
    
    for (pattern in patterns.filter { it.confidence >= 0.85f }) {
        val nextDate = pattern.nextExpectedDate
        if (nextDate in monthStart..monthEnd) {
            calendar.timeInMillis = nextDate
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            allocation[dayOfMonth] = (allocation[dayOfMonth] ?: 0.0) + pattern.averageAmount
        }
        
        // For WEEKLY/BIWEEKLY, calculate all occurrences in month
        if (pattern.frequency == RecurrenceFrequency.WEEKLY || 
            pattern.frequency == RecurrenceFrequency.BIWEEKLY) {
            // Add all occurrences within the month
            // ... (implementation details)
        }
    }
    
    return allocation
}
```

### 8.2 Feature: Account for Planned Expenses

**Using: `PlannedExpenseDao` + `PlannedExpensePriority`**

```kotlin
fun allocatePlannedToDays(
    planned: List<PlannedExpense>,
    monthStart: Long,
    monthEnd: Long
): Map<Int, Double> {
    val allocation = mutableMapOf<Int, Double>()
    val calendar = Calendar.getInstance()
    
    for (expense in planned.filter { 
        it.priority == PlannedExpensePriority.MUST || 
        it.priority == PlannedExpensePriority.LIKELY 
    }) {
        if (expense.date in monthStart..monthEnd) {
            calendar.timeInMillis = expense.date
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            allocation[dayOfMonth] = (allocation[dayOfMonth] ?: 0.0) + expense.amount
        }
    }
    
    return allocation
}
```

### 8.3 Feature: Goal Reserves Impact

**Using: `SavingsGoalDao` + `GoalProtectionLevel`**

```kotlin
fun calculateMonthlyGoalReserve(goals: List<SavingsGoal>): Double {
    return goals
        .filter { it.protectionLevel == GoalProtectionLevel.STRICT }
        .sumOf { goal ->
            val remaining = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
            // Pro-rate by months remaining
            val monthsRemaining = goal.targetDate?.let { 
                maxOf(1, ((it - System.currentTimeMillis()) / (30L * 24 * 60 * 60 * 1000)).toInt())
            } ?: 12
            remaining / monthsRemaining
        }
}
```

### 8.4 Feature: Rollover Budget Adjustment

**Using: `BudgetRepository.rollover` logic**

```kotlin
fun getEffectiveBudgetWithRollover(
    budget: Budget,
    allPurchases: List<Expense>
): Double {
    if (!budget.rollover) return budget.amount
    
    var effectiveLimit = budget.amount
    var window = budgetMonitor.calculatePeriodWindow(budget.period, budget.startDate)
    val currentWindow = budgetMonitor.calculatePeriodWindow(budget.period, System.currentTimeMillis())
    
    while (window.second <= currentWindow.first) {
        val spentInPeriod = allPurchases
            .filter { it.date in window.first until window.second }
            .sumOf { it.amount }
        val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
        effectiveLimit = budget.amount + surplus
        window = budgetMonitor.calculatePeriodWindow(budget.period, window.second)
    }
    
    return effectiveLimit
}
```

---

## Part 9: Visual States & Edge Cases

### 9.1 Block Visual States

| State | Color | Condition | Icon/Indicator |
|-------|-------|-----------|----------------|
| `GOOD` | 🟢 Green | Spent ≤ daily budget | None |
| `OVER` | 🔴 Red | Spent > daily budget | None |
| `BILL_DAY` | ⚪ White with outline | Expected recurring expense | 💸 Money bag |
| `PLANNED` | 🟡 Yellow outline | Planned expense | 🎯 Target |
| `TODAY` | 🔵 Blue | Current day | Border highlight |
| `FUTURE` | ⚪ Gray/Empty | Not happened | None |
| `NO_BUDGET` | ⚫ Dark gray | No budget set | "Set Budget" prompt |

### 9.2 Edge Cases

| Scenario | Solution |
|----------|----------|
| **No budget set** | Show "Set a budget" empty state with CTA |
| **No transactions yet** | Show all green/future blocks with "Great start!" |
| **Overspent early in month** | Dynamic daily budget goes negative → Show warning |
| **Multiple budgets (overall + categories)** | Use overall budget; show "Overall" label |
| **Only category budgets** | Sum them up; show "Combined Budget" label |
| **Budget period mismatch (weekly budget)** | Normalize to monthly equivalent |
| **Leap year February** | Use `Calendar.getActualMaximum(DAY_OF_MONTH)` |
| **Big one-time purchase** | Show as red but explain in drill-down |

---

## Part 10: Implementation Phases

### Phase 1: MVP (Minimum Viable Block Party)

```
Simple Daily Budget = Budget / DaysInMonth
Simple Color Logic = Spent > Daily Budget → Red, else Green
```

**Data needed:**
- Overall budget amount
- Daily totals from `ExpenseDao.getDailyTotalsForPeriod()`

### Phase 2: Smart Recurring Integration

```
Pre-allocate recurring expenses
Don't count recurring days as "over budget"
Show recurring markers
```

**Data needed:**
- `RecurringExpenseEngine.getPatterns()`
- `nextExpectedDate` for each pattern

### Phase 3: Full Synthesis Integration

```
Account for planned expenses
Account for savings goals
Handle rollover budgets
Dynamic daily budget adjustment
```

**Data needed:**
- `PlannedExpenseDao.getAllPlannedExpenses()`
- `SavingsGoalDao.getAllGoals()`
- Budget `rollover` flag

---

## Part 11: Example Scenarios

### Scenario 1: New User, No Budget

```
┌─────────────────────────────────────────────────┐
│              BUDGET BLOCK PARTY                 │
│                                                 │
│  ┌─────────────────────────────────────────────┐│
│  │                                             ││
│  │     📊 Set a budget to unlock this view    ││
│  │                                             ││
│  │     [Set Monthly Budget]                    ││
│  │                                             ││
│  └─────────────────────────────────────────────┘│
└─────────────────────────────────────────────────┘
```

### Scenario 2: User with €1000 Monthly Budget, Rent €800 on 1st

**Without Smart Recurring:**
```
Day 1:  Spent €800, Budget €33 → RED (scary!)
```

**With Smart Recurring (Recommended):**
```
Day 1:  Expected €800 rent, Budget €33 → BILL_DAY (white with 💸)
        Actual spent on other things: €15 → Good
        
Day 2:  Spent €20, Budget €33 → GREEN
Day 3:  Spent €50, Budget €33 → RED
...
```

### Scenario 3: User Overspent Early

**Static Daily Budget:** €33  
**Remaining Budget:** €200  
**Days Remaining:** 10  
**Dynamic Daily Budget:** €20

```
┌─────────────────────────────────────────────────┐
│              BUDGET BLOCK PARTY                 │
│                                                 │
│  Daily: €33  |  Catch-up: €20/day              │
│                                                 │
│  Week 1: 🔴 🔴 🟢 🔴 🔴 🟢 🟢                   │
│  Week 2: 🟢 🔴 🟢 🔵 ⚪ ⚪ ⚪                   │
│  Week 3: ⚪ ⚪ ⚪ ⚪ ⚪ ⚪ ⚪                   │
│                                                 │
│  ⚠️ 5 over budget · 📅 7 days left             │
│  💡 Spend €20/day to stay on track             │
└─────────────────────────────────────────────────┘
```

### Scenario 4: Rollover Budget with Surplus

**Previous Month:** Spent €800 of €1000 budget  
**Surplus:** €200  
**Effective Budget:** €1200

```
Daily Budget = (€1200 - Recurring) / 30 = €40
(vs €33 without rollover)
```

---

## Part 12: Key Implementation Code

### 12.1 ViewModel Integration

```kotlin
@HiltViewModel
class BudgetBlockPartyViewModel @Inject constructor(
    private val blockPartyRepository: BudgetBlockPartyRepository
) : ViewModel() {
    
    private val _blockData = MutableStateFlow<BudgetBlockPartyData?>(null)
    val blockData: StateFlow<BudgetBlockPartyData?> = _blockData.asStateFlow()
    
    init {
        viewModelScope.launch {
            blockPartyRepository.getBlockPartyData()
                .catch { e -> 
                    Log.e("BlockParty", "Error loading data", e)
                    _blockData.value = null 
                }
                .collect { data -> 
                    _blockData.value = data 
                }
        }
    }
}
```

### 12.2 Composable Component

```kotlin
@Composable
fun BudgetBlockPartyCard(
    data: BudgetBlockPartyData,
    modifier: Modifier = Modifier,
    onDayClick: (BlockDay) -> Unit = {}
) {
    BentoCard(modifier = modifier) {
        Column {
            // Header with dual budgets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "BUDGET BLOCK PARTY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = "€${data.dailyBudget.format(2)}/day",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Show catch-up target if different
                if (data.dynamicDailyBudget != data.dailyBudget) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Catch-up",
                            style = MaterialTheme.typography.labelSmall,
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = "€${data.dynamicDailyBudget.format(2)}/day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (data.dynamicDailyBudget < data.dailyBudget * 0.5)
                                SemanticColors.WarningOrange
                            else
                                SemanticColors.TextPrimary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Day labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        modifier = Modifier.width(20.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Calendar grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Leading empty cells for month start alignment
                items(firstDayOffset) { 
                    Box(modifier = Modifier.size(20.dp)) 
                }
                
                // Day blocks
                items(data.blocks.size) { index ->
                    val block = data.blocks[index]
                    BudgetDayBlock(
                        block = block,
                        onClick = { onDayClick(block) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BlockLegend(color = SemanticColors.SuccessGreen, label = "${data.summary.greenDays} good")
                    Spacer(modifier = Modifier.width(8.dp))
                    BlockLegend(color = SemanticColors.DangerRed, label = "${data.summary.redDays} over")
                    if (data.summary.billDays > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BlockLegend(color = Color.White, label = "${data.summary.billDays} bills", outlined = true)
                    }
                }
                
                Text(
                    text = "${(data.summary.onTrackPercentage * 100).toInt()}% on track",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        data.summary.onTrackPercentage >= 0.8f -> SemanticColors.SuccessGreen
                        data.summary.onTrackPercentage >= 0.5f -> SemanticColors.WarningOrange
                        else -> SemanticColors.DangerRed
                    }
                )
            }
        }
    }
}

@Composable
fun BudgetDayBlock(
    block: BlockDay,
    onClick: () -> Unit
) {
    val (backgroundColor, borderColor) = when {
        block.isToday -> Pair(SemanticColors.PrimaryIndigo, Color.White.copy(alpha = 0.5f))
        block.isFuture -> Pair(SemanticColors.GlassSurface, Color.Transparent)
        block.state == BlockState.BILL_DAY -> Pair(Color.Transparent, SemanticColors.TextSecondary)
        block.state == BlockState.GOOD -> Pair(SemanticColors.SuccessGreen, Color.Transparent)
        block.state == BlockState.OVER -> Pair(SemanticColors.DangerRed, Color.Transparent)
        else -> Pair(SemanticColors.GlassSurface, Color.Transparent)
    }
    
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(4.dp))
                } else Modifier
            )
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(enabled = !block.isFuture, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (block.state == BlockState.BILL_DAY) {
            Text(
                text = "💸",
                fontSize = 8.sp,
                modifier = Modifier.alpha(0.7f)
            )
        }
    }
}
```

---

## Part 13: Summary & Recommendations

### What Daily Budget Should Be

**Recommended Formula:**
```
dailyBudget = (effectiveBudget - monthlyRecurringTotal - goalReserves) / daysInMonth
```

**Why:**
1. Uses `effectiveBudget` which accounts for rollover
2. Pre-subtracts recurring expenses (SynthesisEngine already does this)
3. Pre-subtracts savings goal contributions
4. Leaves truly discretionary pool

### What Existing Engines Provide

| Need | Source | Ready to Use? |
|------|--------|---------------|
| Monthly budget normalization | `BudgetRepository` | ✅ Yes |
| Rollover calculation | `BudgetRepository` | ✅ Yes |
| Recurring patterns | `RecurringExpenseEngine` | ✅ Yes |
| Monthly recurring total | `SynthesisEngine` | ✅ Yes |
| Daily discretionary baseline | `SynthesisEngine.typicalDailyDiscretionary` | ✅ Yes |
| Goal reserves | `SynthesisEngine` | ✅ Yes |
| Daily spending totals | `ExpenseDao.getDailyTotalsForPeriod()` | ✅ Yes |
| Planned expenses | `PlannedExpenseDao` | ✅ Yes |

### Implementation Priority

1. **Phase 1 (MVP):** Simple budget/days calculation with basic green/red
2. **Phase 2:** Integrate recurring expense markers (BILL_DAY treatment)
3. **Phase 3:** Full synthesis integration (goals, planned, rollover)

### Key Insight

Your `SynthesisEngine` already has the most sophisticated calculation. The Block Party feature should **reuse** its logic rather than implement new calculations. The visual is the innovation - the math is already done.
