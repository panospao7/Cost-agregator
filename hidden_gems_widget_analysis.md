# Hidden Gems: Widget Concepts That Unlock Existing Engine Potential

## Executive Summary

After a deep analysis of your codebase, I discovered that your engines compute **22+ unique metrics** that are either:
1. **Completely hidden** (never shown to user)
2. **Buried in deep analytics screens** (user must navigate 3+ levels to find)
3. **Computed but discarded** (used internally but never exposed)

This document presents creative widget concepts that surface these hidden insights on the home screen.

---

## Part 1: The Hidden Metrics Inventory

### 1.1 Computed But Never Visualized

| Metric | Engine | Current Status | Potential Value |
|--------|--------|----------------|-----------------|
| `loyaltyScore` | AdvancedAnalyticsEngine | Shown only in Advanced Analytics detail | Gamification potential |
| `consecutiveMonthsVisited` | AdvancedAnalyticsEngine | Hidden in merchant detail | "Streak" motivation |
| `priceTrend` | AdvancedAnalyticsEngine | Shown as text in detail | Inflation awareness |
| `predictedNextVisitDate` | AdvancedAnalyticsEngine | Hidden | Future awareness |
| `consistencyRating` | AdvancedAnalyticsEngine | Text label only | Merchant categorization |
| `velocity` (category) | AdvancedAnalyticsEngine | Not shown at all | Acceleration detection |
| `detectedPatterns` (behavioral) | AdvancedAnalyticsEngine | Shown in Advanced Analytics | Spending personality |
| `volatilityIndex` | AdvancedAnalyticsEngine | Shown in Advanced Analytics | Risk awareness |
| `coefficientOfVariation` | AdvancedAnalyticsEngine | Hidden | Consistency measure |
| `daysWithoutSpending` | AdvancedAnalyticsEngine | Hidden | Positive reinforcement |
| `weekendToWeekdayRatio` | AdvancedAnalyticsEngine | Used in pattern detection only | Balance insight |
| `timeOfDayDistribution` | AdvancedAnalyticsEngine | Hidden | Habit awareness |
| `mostActiveDayIndex` | AdvancedAnalyticsEngine | Used internally only | Pattern awareness |
| `changeFromAverage` | InsightsEngine | Not visualized | Deviation alert |
| `typicalDailyDiscretionary` | SynthesisEngine | Used internally | Baseline comparison |

### 1.2 Computed But Buried (Requires Deep Navigation)

| Metric | Location | Clicks to Reach |
|--------|----------|-----------------|
| `discretionaryBudget` | Financial Weather Card → Breakdown | 2 clicks |
| `goalReserves` | Financial Weather Card → Breakdown | 2 clicks |
| `totalCommitted` | Financial Weather Card → Breakdown | 2 clicks |
| `predictedDiscretionary` | Financial Weather Card | 1 click (expand) |
| Anomaly list | Insights section | Scroll + expand |
| Recurring patterns | Recurring screen | 2 clicks |

---

## Part 2: Widget Concepts

### Widget 1: "Spending Personality" Card 🔮

**What it unlocks:** `detectedPatterns` from `SpendingPatternAnalysis`

**Current status:** Computed but only shown in Advanced Analytics screen (3+ clicks deep)

**The insight you're hiding:**
```kotlin
enum class SpendingPatternType {
    WEEKEND_WARRIOR,      // 50%+ spending on weekends
    LUNCH_BROWSER,        // 40%+ spending during daytime
    COMMUTER,             // Transport patterns
    SUBSCRIPTION_HEAVY,   // High recurring ratio
    IMPULSE_BUYER,        // High transaction variance (cv > 1.0)
    PLANNER,              // Low variance, predictable
    OCCASIONAL_SPLURGER   // Irregular large purchases
}
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 🎭 SPENDING PERSONALITY                         │
│                                                 │
│    ┌───────────────────────────────────────┐   │
│    │                                       │   │
│    │         🏃 WEEKEND WARRIOR            │   │
│    │                                       │   │
│    │   "You do 62% of your spending        │   │
│    │    on weekends - Saturday is          │   │
│    │    your power spending day!"          │   │
│    │                                       │   │
│    │   Confidence: ████████░░ 85%          │   │
│    └───────────────────────────────────────┘   │
│                                                 │
│   Also detected: 🍔 Lunch Browser (45%)        │
│                                                 │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Gives user a "personality" they can identify with
- Makes analytics feel personal, not clinical
- Gamifies the experience without artificial badges
- Self-awareness drives behavior change

**Data needed:**
```kotlin
data class SpendingPersonalityWidget(
    val primaryPattern: SpendingPatternType,
    val primaryConfidence: Float,
    val primaryDescription: String,
    val secondaryPatterns: List<SpendingPatternType>,
    val affectedMerchants: List<String>  // For drill-down
)
```

---

### Widget 2: "Price Watchdog" Card 📈

**What it unlocks:** `priceTrend`, `priceChangePercent` from `EnhancedMerchantAnalytics`

**Current status:** Computed, shown as small text in merchant detail

**The insight you're hiding:**
```kotlin
enum class MerchantPriceTrend {
    INCREASING_FAST,   // >10% increase
    INCREASING,        // 3-10% increase
    STABLE,            // -3% to +3%
    DECREASING,        // 3-10% decrease
    DECREASING_FAST,   // >10% decrease
    INSUFFICIENT_DATA
}
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 📊 PRICE WATCHDOG                               │
│   Tracking price changes at your favorites      │
│                                                 │
│   ⚠️ INFLATION ALERT                            │
│   ┌─────────────────────────────────────────┐   │
│   │ ☕ Starbucks      +12% ⬆️               │   │
│   │    €4.50 → €5.04 (3 months)             │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   📉 GOOD NEWS                                   │
│   ┌─────────────────────────────────────────┐   │
│   │ 🛒 Lidl           -5% ⬇️                │   │
│   │    Your avg basket is cheaper           │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   [See all tracked merchants →]                 │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Users don't notice gradual price increases (€4.50 → €4.65 → €4.80)
- Real financial awareness
- Could influence merchant switching decisions
- "Inflation is personal" - shows THEIR inflation, not national average

**Implementation note:**
```kotlin
// Already computed in AdvancedAnalyticsEngine:
val priceTrendData = analyzePriceTrend(historicalForMerchant)
// Returns: trend, firstPurchaseAmount, latestPurchaseAmount, priceChangePercent
```

---

### Widget 3: "Loyalty Passport" Card 🎫

**What it unlocks:** `loyaltyScore`, `consecutiveMonthsVisited`, `consistencyRating` from `EnhancedMerchantAnalytics`

**Current status:** Computed but only visible in Advanced Analytics merchant detail

**The insight you're hiding:**
```kotlin
val loyaltyScore: Float,              // 0-100 score
val consistencyRating: MerchantConsistencyRating,  // HIGHLY_CONSISTENT to IRREGULAR
val consecutiveMonthsVisited: Int     // Streak count
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 🎫 YOUR LOYALTY PASSPORT                        │
│                                                 │
│   🏆 TOP LOYALTY SCORES                         │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ 1. ☕ Gregory's     ██████████ 92       │   │
│   │    🔥 8 month streak                    │   │
│   │    ⭐ Highly Consistent                 │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ 2. 🏪 Sklavenitis  ███████░░░ 78        │   │
│   │    🔥 6 month streak                    │   │
│   │    ⭐ Consistent                         │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   Your loyalty habits save ~€15/month!         │
│   (Predictable merchants = better budgeting)   │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Gamification without being annoying
- Positive reinforcement (celebrates loyalty vs criticizing overspending)
- "Streak" psychology is powerful
- Could be used for budget predictions

**Hidden formula already computed:**
```kotlin
// From AdvancedAnalyticsEngine.calculateLoyaltyScore():
// consistencyScore = (1 - cv.coerceIn(0.0, 1.0)) * 0.4
// longevityScore = (historicalCount / 24.0).coerceIn(0.0, 1.0) * 0.3
// frequencyScore = (amounts.size / 12.0).coerceIn(0.0, 1.0) * 0.3
// return (consistencyScore + longevityScore + frequencyScore) * 100
```

---

### Widget 4: "Next Visit Predictor" Card 🔮

**What it unlocks:** `predictedNextVisitDate`, `averageDaysBetweenVisits` from `EnhancedMerchantAnalytics`

**Current status:** Computed but hidden

**The insight you're hiding:**
```kotlin
val predictedNextVisitDate: Long?,        // Epoch timestamp
val averageDaysBetweenVisits: Double?     // Average interval
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 🔮 COMING UP                                     │
│   Predicted visits based on your patterns       │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ TOMORROW                                │   │
│   │ ☕ You'll likely visit Gregory's        │   │
│   │    Typical: €4.20 · Every 2 days        │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ THURSDAY                                │   │
│   │ 🛒 Weekly grocery run at Lidl           │   │
│   │    Typical: €45.00 · Every 7 days       │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ NEXT WEEK                               │   │
│   │ ⛽ Refuel likely (12 days since last)   │   │
│   │    Typical: €55.00                      │   │
│   └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Proactive vs reactive financial awareness
- "Thursday you'll probably spend €50" → plan ahead
- Connects recurring patterns to calendar
- Could integrate with Cash Flow view

**Already computed:**
```kotlin
private fun predictNextVisit(dates: List<Long>, avgDaysBetween: Double?): Long? {
    if (dates.isEmpty() || avgDaysBetween == null || avgDaysBetween <= 0) return null
    val lastVisit = dates.max()
    return lastVisit + (avgDaysBetween * MILLIS_PER_DAY).toLong()
}
```

---

### Widget 5: "Category Velocity" Card ⚡

**What it unlocks:** `velocity` from `EnhancedCategoryAnalytics`

**Current status:** Computed but **NEVER SHOWN TO USER**

**The insight you're hiding:**
```kotlin
// From EnhancedCategoryAnalytics:
val velocity: Double  // Positive = accelerating, Negative = decelerating
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ ⚡ SPENDING VELOCITY                            │
│   What's accelerating, what's cooling down      │
│                                                 │
│   🔥 ACCELERATING                               │
│   ┌─────────────────────────────────────────┐   │
│   │ 🍔 Food           +€42 velocity          │   │
│   │    2nd half of month is €42 more         │   │
│   │    than 1st half → eating out more?     │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   ❄️ COOLING DOWN                               │
│   ┌─────────────────────────────────────────┐   │
│   │ 🛍️ Shopping       -€28 velocity         │   │
│   │    Spending slowed in 2nd half          │   │
│   │    Great job reining it in!             │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   → Tap to see breakdown by merchant           │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- EARLY WARNING SYSTEM: Velocity catches problems before budget is exceeded
- "I'm spending more on food this week" vs "I've exceeded my food budget"
- Shows trajectory, not just current state
- Could predict budget exhaustion

**Already computed:**
```kotlin
private fun calculateVelocity(expenses: List<Expense>): Double {
    if (expenses.size < 2) return 0.0
    val sorted = expenses.sortedBy { it.date }
    val midPoint = sorted.size / 2
    val firstHalfTotal = sorted.take(midPoint).sumOf { it.amount }
    val secondHalfTotal = sorted.takeLast(midPoint).sumOf { it.amount }
    return secondHalfTotal - firstHalfTotal
}
```

---

### Widget 6: "Volatility Index" Card 📊

**What it unlocks:** `volatilityIndex`, `coefficientOfVariation`, `daysWithoutSpending` from `StatisticalInsights`

**Current status:** Computed but only shown in Advanced Analytics

**The insight you're hiding:**
```kotlin
val volatilityIndex: Float,           // 0-100 (how erratic spending is)
val coefficientOfVariation: Float,    // Statistical measure
val daysWithoutSpending: Int          // "Zero spend days"
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 🌊 SPENDING CONSISTENCY                         │
│                                                 │
│   Your volatility score: 32/100                 │
│   ████████░░░░░░░░░░░░ STABLE                   │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │ Good news: Your spending is predictable!│   │
│   │                                         │   │
│   │ 📊 Most transactions: €8-25             │   │
│   │ 📅 8 days with no spending this month   │   │
│   │ 🎯 Your P90 is €45 (90% under this)     │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   💡 Predictable spending = easier budgeting   │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Users don't know if their spending is "normal" or erratic
- "My spending is all over the place" = high volatility
- Could suggest "Your spending is volatile - consider a buffer budget"
- Days without spending = positive metric to celebrate

---

### Widget 7: "Weekend/Weekday Balance" Card ⚖️

**What it unlocks:** `weekendToWeekdayRatio`, `weekendVsWeekday` from `SpendingPatternAnalysis`

**Current status:** Computed for pattern detection, shown as tiny text

**The insight you're hiding:**
```kotlin
val weekendToWeekdayRatio: Float        // e.g., 0.8 = 80% of weekday spend
val weekdayTotal: Double
val weekendTotal: Double
val weekdayAveragePerTransaction: Double
val weekendAveragePerTransaction: Double
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ ⚖️ WEEKEND/WEEKDAY BALANCE                     │
│                                                 │
│   Weekday    ████████████████░░░░  €420 (68%)   │
│   Weekend    ████████░░░░░░░░░░░░  €200 (32%)   │
│                                                 │
│   Ratio: 0.47x                                 │
│   "You spend about half on weekends            │
│    compared to weekdays"                        │
│                                                 │
│   📊 Average per transaction:                   │
│   Weekday: €18    Weekend: €32                 │
│                                                 │
│   💡 Weekend purchases are 1.8x larger          │
│      but less frequent                         │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Reveals spending rhythm user may not be aware of
- "Oh, I really am a weekend warrior"
- Could inform budget allocation strategies
- Connects to detected patterns

---

### Widget 8: "Time-of-Day Heatmap" Card 🕐

**What it unlocks:** `timeOfDayDistribution` from `SpendingPatternAnalysis`

**Current status:** Computed but **NEVER SHOWN TO USER**

**The insight you're hiding:**
```kotlin
enum class TimeSlot {
    EARLY_MORNING,   // 6-9
    MORNING,         // 9-12
    AFTERNOON,       // 12-17
    EVENING,         // 17-21
    NIGHT,           // 21-24
    LATE_NIGHT       // 0-6
}
val timeOfDayDistribution: Map<TimeSlot, Double>
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 🕐 YOUR SPENDING CLOCK                         │
│                                                 │
│   ┌───┬───┬───┬───┬───┬───┐                    │
│   │ 6 │ 9 │12 │15 │18 │21 │  ← Hour of day     │
│   ├───┼───┼───┼───┼───┼───┤                    │
│   │░░░│▓▓▓│███│███│▓▓▓│░░░│                    │
│   └───┴───┴───┴───┴───┴───┘                    │
│     Dawn  ☀️  Peak   🌙                          │
│                                                 │
│   🏆 Peak spending: AFTERNOON (12-17)          │
│   42% of your spending happens here            │
│                                                 │
│   🌙 Night spending: Only 3%                   │
│   Great - late night impulse buys are rare!   │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- "When do I spend?" is rarely asked but revealing
- Lunch Browser pattern becomes visual
- Could enable time-based budget alerts
- Connects habit to time

---

### Widget 9: "Deviation from Average" Card 📏

**What it unlocks:** `changeFromAverage` from `CategoryInsight`

**Current status:** Computed but not visualized

**The insight you're hiding:**
```kotlin
val averageOverMonths: Double?,       // Historical average
val monthsOfData: Int,                 // How many months of data
val changeFromAverage: Float?          // % deviation from historical
```

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 📏 COMPARED TO YOUR AVERAGE                    │
│                                                 │
│   Based on your last 4 months of data           │
│                                                 │
│   🚨 UNUSUAL THIS MONTH                         │
│   ┌─────────────────────────────────────────┐   │
│   │ 🛒 Groceries      +35% vs your avg      │   │
│   │    €320 this month vs €237 typical      │   │
│   │    Stocking up? Price increase?         │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   ✅ IMPROVEMENT                                │
│   ┌─────────────────────────────────────────┐   │
│   │ 🍔 Food           -22% vs your avg      │   │
│   │    €180 this month vs €230 typical      │   │
│   │    Cooking more? Nice work!             │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   💡 "Your average" = personalized baseline    │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- "Is this normal for ME?" is more relevant than "Is this normal?"
- Self-comparison is more actionable than peer comparison
- "Your average" accounts for your lifestyle (expensive city, big family, etc.)
- Could replace generic budget suggestions

---

### Widget 10: "Category Health Matrix" Card 🎯

**What it unlocks:** Multiple metrics combined - `velocity`, `changePercent`, `budgetStatus`, `sparklineData`

**Current status:** All computed but scattered across different screens

**Widget concept:**
```
┌─────────────────────────────────────────────────┐
│ 🎯 CATEGORY HEALTH MATRIX                      │
│                                                 │
│   Category    Trend   Velocity   Budget   Score │
│   ─────────────────────────────────────────────│
│   🍔 Food     ↗️ +12%   +€28     🟢 68%   85   │
│   🛒 Groc.    → +3%    -€5      🟢 45%   92   │
│   🚗 Trans.   ↘️ -8%   -€15     🟡 78%   88   │
│   🎬 Entert.  ↗️ +25%   +€42    🔴 95%   42   │
│                                                 │
│   Health Score = (Trend + Velocity + Budget)   │
│                 weighted composite             │
│                                                 │
│   ⚠️ Entertainment needs attention             │
│      High velocity + over budget               │
└─────────────────────────────────────────────────┘
```

**Why it matters:**
- Single view of all category health
- Composite score simplifies decision-making
- "Which category should I focus on?" becomes answerable
- Connects velocity + budget + trend

---

## Part 3: The "Meta-Insight" Widgets

These combine multiple existing metrics in novel ways.

### Widget 11: "Financial Momentum" Card 📈

**Combines:** `velocity` + `spendingPace` + `discretionaryBudget` trend

**Concept:**
```
┌─────────────────────────────────────────────────┐
│ 📈 FINANCIAL MOMENTUM                          │
│                                                 │
│   Last 7 days:  ────📈────                      │
│   Momentum: +€127 (trending up)                 │
│                                                 │
│   🔴 SLOWING DOWN                               │
│   "Your discretionary budget is shrinking      │
│    faster than your pace suggests.             │
│    Next week might be tight."                   │
│                                                 │
│   Suggestion: 3 non-essential days could       │
│   restore €60 buffer                            │
└─────────────────────────────────────────────────┘
```

### Widget 12: "Prediction Accuracy" Card 🎯

**Combines:** Historical `predictedTotal` vs actual `currentTotal`

**Concept:**
```
┌─────────────────────────────────────────────────┐
│ 🎯 PREDICTION ACCURACY                         │
│                                                 │
│   How well did we predict your month?          │
│                                                 │
│   Month   Predicted  Actual  Accuracy          │
│   ─────────────────────────────────────────────│
│   Oct     €1,200    €1,180   98% ✓             │
│   Sep     €950      €1,020   93% ✓             │
│   Aug     €1,100    €1,350   82% ⚠️            │
│                                                 │
│   Your avg accuracy: 91%                       │
│   "We know your patterns pretty well!"         │
│                                                 │
│   💡 When accuracy drops, something changed    │
│      (new job? vacation? lifestyle shift?)     │
└─────────────────────────────────────────────────┘
```

### Widget 13: "Safe-to-Spend Today" Card 💰

**Combines:** `discretionaryBudget` + `daysRemaining` + `typicalDailyDiscretionary`

**Concept:**
```
┌─────────────────────────────────────────────────┐
│ 💰 SAFE TO SPEND TODAY                         │
│                                                 │
│   ┌─────────────────────────────────────────┐   │
│   │                                         │   │
│   │            € 42.50                      │   │
│   │         ───────────                     │   │
│   │       Your daily allowance              │   │
│   │                                         │   │
│   └─────────────────────────────────────────┘   │
│                                                 │
│   Based on:                                     │
│   • €850 remaining budget                       │
│   • 20 days left in month                       │
│   • €200 committed expenses pending            │
│                                                 │
│   ⚠️ If you spend over €60 today,              │
│      tomorrow's allowance drops to €38          │
└─────────────────────────────────────────────────┘
```

---

## Part 4: Widget Complexity Matrix

| Widget | Data Already Computed | UI Effort | Business Value | Priority |
|--------|----------------------|-----------|----------------|----------|
| Spending Personality | ✅ Yes | Low | High | 🔴 P1 |
| Price Watchdog | ✅ Yes | Medium | High | 🔴 P1 |
| Loyalty Passport | ✅ Yes | Low | Medium | 🟡 P2 |
| Next Visit Predictor | ✅ Yes | Medium | Medium | 🟡 P2 |
| Category Velocity | ✅ Yes | Low | **Very High** | 🔴 P1 |
| Volatility Index | ✅ Yes | Low | Medium | 🟡 P2 |
| Weekend/Weekday Balance | ✅ Yes | Low | Medium | 🟢 P3 |
| Time-of-Day Heatmap | ✅ Yes | Medium | Low | 🟢 P3 |
| Deviation from Average | ✅ Yes | Low | High | 🔴 P1 |
| Category Health Matrix | ✅ Yes (scattered) | Medium | **Very High** | 🔴 P1 |
| Financial Momentum | Partial | Medium | High | 🟡 P2 |
| Prediction Accuracy | Partial | Medium | Medium | 🟢 P3 |
| Safe-to-Spend Today | ✅ Yes | Low | **Very High** | 🔴 P1 |

---

## Part 5: Implementation Roadmap

### Phase 1: Quick Wins (1-2 days each)
1. **Category Velocity** - Single number, already computed, huge predictive value
2. **Safe-to-Spend Today** - Uses existing SynthesisEngine calculation
3. **Deviation from Average** - Simple comparison already computed

### Phase 2: Engagement Widgets (2-3 days each)
4. **Spending Personality** - Novel concept, high shareability
5. **Loyalty Passport** - Gamification without badges
6. **Price Watchdog** - Real financial value

### Phase 3: Deep Insights (3-5 days each)
7. **Category Health Matrix** - Comprehensive view
8. **Next Visit Predictor** - Calendar integration potential
9. **Financial Momentum** - Trend analysis

---

## Part 6: Key Insight

**Your biggest hidden asset: `velocity`**

The `velocity` metric from `EnhancedCategoryAnalytics` is your **single most underutilized** piece of computed data:

```kotlin
// Spending velocity (positive = accelerating)
val velocity: Double
```

This tells you if spending in a category is **speeding up or slowing down**. This is:
- An early warning system (catch problems before budget exceeded)
- A success indicator (deceleration = good progress)
- A pattern detector (accelerating + over budget = crisis)

**It's computed but never shown.**

---

## Part 7: Summary

| Category | Widgets | Key Unlocked Metric |
|----------|---------|---------------------|
| **Behavioral** | Spending Personality, Weekend Balance | `detectedPatterns`, `weekendToWeekdayRatio` |
| **Predictive** | Next Visit, Safe-to-Spend | `predictedNextVisitDate`, `discretionaryBudget` |
| **Velocity** | Category Velocity, Financial Momentum | `velocity` |
| **Loyalty** | Loyalty Passport | `loyaltyScore`, `consecutiveMonthsVisited` |
| **Market** | Price Watchdog | `priceTrend`, `priceChangePercent` |
| **Health** | Volatility, Deviation, Matrix | `volatilityIndex`, `changeFromAverage` |
| **Temporal** | Time-of-Day Heatmap | `timeOfDayDistribution` |

**Your engines are goldmines. The widgets just need to surface the gems.**
