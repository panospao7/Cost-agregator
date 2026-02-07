

# Strategic Expansion Roadmap

## Current Architecture Assessment

You have a solid foundation: notification capture → parsing → expense creation → categorization → display. Let me map out what transforms this from a "working prototype" into a genuinely compelling app.

---

## Phase 1: Intelligent Core (Do This Next)

### 1A. Confidence-Based Review System

Instead of silently accepting or rejecting parsed notifications, introduce a **pending review queue** for medium-confidence parses:

```
Confidence ≥ 0.85 → Auto-accept as expense
Confidence 0.50-0.84 → Add to "Review Queue" (user confirms/rejects)
Confidence < 0.50 → Silently reject
```

This is critical because every user correction becomes training data.

**New entity: `PendingReview.kt`**
```kotlin
@Entity(tableName = "pending_reviews")
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedType: String,
    val suggestedCategoryId: Long?,
    val confidence: Float,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING" // PENDING, APPROVED, REJECTED, MODIFIED
)
```

**Why this matters**: Users train the system by approving/rejecting. You log what they change, which feeds back into parsing accuracy.

### 1B. User Correction Learning Engine

Every time a user corrects something, you should learn from it:

```kotlin
data class UserCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val originalMerchant: String,
    val correctedMerchant: String?,
    val originalAmount: Double,
    val correctedAmount: Double?,
    val originalCategoryId: Long?,
    val correctedCategoryId: Long?,
    val wasRejected: Boolean = false, // User said "this isn't a transaction"
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis()
)
```

This table becomes your on-device ML training set. Over time you can:
- Auto-reject notification patterns that users always reject
- Auto-correct merchant names users always rename
- Pre-select categories based on past corrections

### 1C. Source Trustworthiness Scoring

Track which notification sources actually produce real transactions:

```kotlin
data class SourceStats(
    @PrimaryKey val packageName: String,
    val totalNotifications: Int = 0,
    val acceptedAsExpense: Int = 0,
    val rejectedByUser: Int = 0,
    val autoRejected: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() = if (totalNotifications > 0) 
            acceptedAsExpense.toFloat() / totalNotifications 
        else 0f
}
```

After enough data, apps with trustScore < 0.05 can be auto-blocked or at minimum, their confidence scores halved.

-
--




# 

# 
## Phase 2: On-Device ML (Lightweight, No Server Needed)

### 2A. TF-Lite Transaction Classifier

Train a small text classification model that takes notification text and outputs: `IS_TRANSACTION` vs `NOT_TRANSACTION`. You can train this offline using the corrections your users provide.

But **before** going full ML, a simpler approach works remarkably well:

### 2B. Naive Bayes On-Device Classifier

```kotlin
/**
 * Lightweight on-device text classifier using word frequency counting.
 * No TensorFlow needed. Learns from user corrections.
 */
class TransactionClassifier {
    
    // Word → how many times it appeared in REAL transactions
    private val positiveWordCounts = mutableMapOf<String, Int>()
    // Word → how many times it appeared in NON-transactions  
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    
    fun train(text: String, isTransaction: Boolean) {
        val words = tokenize(text)
        if (isTransaction) {
            totalPositive++
            words.forEach { positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1 }
        } else {
            totalNegative++
            words.forEach { negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1 }
        }
    }
    
    fun predict(text: String): Float {
        if (totalPositive + totalNegative < 20) return 0.5f // Not enough data
        
        val words = tokenize(text)
        var logProbPos = Math.log(totalPositive.toDouble() / (totalPositive + totalNegative))
        var logProbNeg = Math.log(totalNegative.toDouble() / (totalPositive + totalNegative))
        
        for (word in words) {
            val posCount = (positiveWordCounts[word] ?: 0) + 1 // Laplace smoothing
            val negCount = (negativeWordCounts[word] ?: 0) + 1
            logProbPos += Math.log(posCount.toDouble() / (totalPositive + positiveWordCounts.size))
            logProbNeg += Math.log(negCount.toDouble() / (totalNegative + negativeWordCounts.size))
        }
        
        // Sigmoid-like normalization
        val diff = logProbPos - logProbNeg
        return (1.0 / (1.0 + Math.exp(-diff))).toFloat()
    }
    
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-zα-ω0-9€$£ ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
    }
    
    // Serialize to/from JSON for persistence
    fun toJson(): String { /* ... */ }
    fun fromJson(json: String) { /* ... */ }
}
```

This classifier gets trained automatically from user corrections. After ~50 corrections it becomes remarkably accurate for that specific user's notification patterns.

### 2C. Merchant Name Normalization with Fuzzy Matching

```kotlin
/**
 * Smart merchant normalization that handles variations:
 * "SKLAVENITIS ATH001" → "SKLAVENITIS"
 * "SHELL STATION 2345" → "SHELL"  
 * "STARBUCKS #1234 ATHENS" → "STARBUCKS"
 */
class MerchantNormalizer {
    
    // Suffixes to strip
    private val NOISE_PATTERNS = listOf(
        Regex("""\s*#?\d{3,}.*$"""),           // Store numbers: "#1234", "ATH001"
        Regex("""\s*\*+\d+.*$"""),              // Card suffixes: "**1234"
        Regex("""\s+(?:GR|ATH|THES|ATHENS|THESSALONIKI).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(?:BRANCH|STORE|SHOP|KATAST)\s*\d*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+\d{1,2}/\d{1,2}/?\d{0,4}$"""),  // Dates at end
        Regex("""\s+(?:SA|AE|ΑΕ|EPE|ΕΠΕ|IKE|ΙΚΕ|LTD|GMBH|SRL)\s*$""", RegexOption.IGNORE_CASE)
    )
    
    fun normalize(merchant: String): String {
        var result = merchant.uppercase().trim()
        for (pattern in NOISE_PATTERNS) {
            result = result.replace(pattern, "")
        }
        return result
            .replace(Regex("[^A-ZΑ-Ω0-9 &]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    /**
     * Levenshtein-based similarity for matching "STARBUCKS" to "STARBUCKS COFFEE"
     */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        if (na.contains(nb) || nb.contains(na)) return 0.9f
        
        // Word overlap
        val wordsA = na.split(" ").toSet()
        val wordsB = nb.split(" ").toSet()
        val intersection = wordsA.intersect(wordsB)
        val union = wordsA.union(wordsB)
        return if (union.isNotEmpty()) intersection.size.toFloat() / union.size else 0f
    }
}
```

---

## Phase 3: Analytics & Visualization

### 3A. Analytics Tab Structure

Create a dedicated **Analytics/Insights** tab (replace or augment Home). Here's what to show:

```
┌─────────────────────────────────────┐
│         THIS MONTH: €1,234.56       │
│    ▼12% vs last month (€1,402.30)   │
├─────────────────────────────────────┤
│  [Today] [Week] [Month] [Year] [All]│
├─────────────────────────────────────┤
│                                     │
│    ╭──────── Donut Chart ─────────╮ │
│    │  🛒 Groceries    €456  37%   │ │
│    │  🍽 Food         €234  19%   │ │
│    │  🚗 Transport    €189  15%   │ │
│    │  📱 Subscriptions €89   7%   │ │
│    │  ❓ Other        €266  22%   │ │
│    ╰──────────────────────────────╯ │
│                                     │
├─────────────────────────────────────┤
│  Daily Spending Bar Chart           │
│  ▓▓▓░░▓▓▓▓░▓▓░░▓▓▓▓▓░░▓▓▓▓░░░▓▓  │
│  Mon    Wed    Fri    Sun           │
├─────────────────────────────────────┤
│  INSIGHTS                           │
│  ⚠️ You spent 40% more on Food     │
│     this week vs your average       │
│  📈 Groceries trending up: €380    │
│     → €420 → €456 (last 3 months)  │
│  💡 Your biggest expense today:     │
│     €45.00 at Shell                 │
│  🔄 Recurring: Netflix €13.99      │
│     expected in 3 days              │
├─────────────────────────────────────┤
│  TOP MERCHANTS                      │
│  1. Sklavenitis    12x    €234.50  │
│  2. Shell           8x    €189.00  │
│  3. Starbucks       15x   €67.50  │
│  4. efood            6x   €95.20  │
└─────────────────────────────────────┘
```

### 3B. Chart Library

Add **Vico** (modern Compose charting library) to `build.gradle`:
```groovy
implementation "com.patrykandpatrick.vico:compose-m3:1.13.1"
```

### 3C. Analytics Data Models

```kotlin
data class SpendingPeriod(
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val byCategory: Map<Category, Double>,
    val byMerchant: Map<String, MerchantStats>,
    val dailyTotals: Map<String, Double>, // "2024-01-15" → 45.60
    val transactionCount: Int
)

data class MerchantStats(
    val name: String,
    val totalSpent: Double,
    val transactionCount: Int,
    val averageTransaction: Double,
    val categoryId: Long?
)

data class SpendingInsight(
    val type: InsightType,
    val icon: String,
    val title: String,
    val description: String,
    val severity: Float // 0-1, how important
)

enum class InsightType {
    SPENDING_INCREASE,
    SPENDING_DECREASE,
    UNUSUAL_TRANSACTION,
    RECURRING_DETECTED,
    CATEGORY_TREND,
    BUDGET_WARNING,
    MERCHANT_FREQUENCY,
    DAILY_AVERAGE
}
```

### 3D. Insights Engine

```kotlin
class InsightsEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    suspend fun generateInsights(expenses: List<Expense>, categories: List<Category>): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        val now = System.currentTimeMillis()
        val dayMs = 86400000L
        
        val thisWeek = expenses.filter { now - it.date < 7 * dayMs }
        val lastWeek = expenses.filter { it.date in (now - 14 * dayMs)..(now - 7 * dayMs) }
        
        val thisMonth = expenses.filter { now - it.date < 30 * dayMs }
        val lastMonth = expenses.filter { it.date in (now - 60 * dayMs)..(now - 30 * dayMs) }
        
        // 1. Week-over-week comparison
        val thisWeekTotal = thisWeek.sumOf { it.amount }
        val lastWeekTotal = lastWeek.sumOf { it.amount }
        if (lastWeekTotal > 0) {
            val change = ((thisWeekTotal - lastWeekTotal) / lastWeekTotal * 100)
            if (change > 20) {
                insights.add(SpendingInsight(
                    InsightType.SPENDING_INCREASE, "📈",
                    "Spending up ${change.toInt()}%",
                    "You've spent €${String.format("%.2f", thisWeekTotal)} this week vs €${String.format("%.2f", lastWeekTotal)} last week",
                    (change / 100).coerceAtMost(1.0).toFloat()
                ))
            } else if (change < -15) {
                insights.add(SpendingInsight(
                    InsightType.SPENDING_DECREASE, "📉",
                    "Great job! Spending down ${(-change).toInt()}%",
                    "You saved €${String.format("%.2f", lastWeekTotal - thisWeekTotal)} compared to last week",
                    0.3f
                ))
            }
        }
        
        // 2. Category trends
        val categoryMap = categories.associateBy { it.id }
        val thisMonthByCategory = thisMonth.groupBy { it.categoryId }
        val lastMonthByCategory = lastMonth.groupBy { it.categoryId }
        
        for ((catId, exps) in thisMonthByCategory) {
            if (catId == null) continue
            val cat = categoryMap[catId] ?: continue
            val thisTotal = exps.sumOf { it.amount }
            val lastTotal = lastMonthByCategory[catId]?.sumOf { it.amount } ?: 0.0
            
            if (lastTotal > 0 && thisTotal > lastTotal * 1.5 && thisTotal > 20) {
                insights.add(SpendingInsight(
                    InsightType.CATEGORY_TREND, cat.icon,
                    "${cat.name} spending up",
                    "€${String.format("%.0f", thisTotal)} this month vs €${String.format("%.0f", lastTotal)} last month",
                    0.7f
                ))
            }
        }
        
        // 3. Recurring payment detection
        val recurringCandidates = detectRecurring(thisMonth + lastMonth)
        for (recurring in recurringCandidates) {
            insights.add(SpendingInsight(
                InsightType.RECURRING_DETECTED, "🔄",
                "Recurring: ${recurring.merchant}",
                "€${String.format("%.2f", recurring.amount)} approximately every ${recurring.intervalDays} days",
                0.4f
            ))
        }
        
        // 4. Biggest transaction today
        val today = expenses.filter { now - it.date < dayMs }
        val biggest = today.maxByOrNull { it.amount }
        if (biggest != null && biggest.amount > 10) {
            insights.add(SpendingInsight(
                InsightType.UNUSUAL_TRANSACTION, "💰",
                "Biggest today: ${biggest.merchant}",
                "€${String.format("%.2f", biggest.amount)}",
                0.2f
            ))
        }
        
        // 5. Daily average
        if (thisMonth.isNotEmpty()) {
            val days = ((now - thisMonth.minOf { it.date }) / dayMs).coerceAtLeast(1)
            val dailyAvg = thisMonth.sumOf { it.amount } / days
            insights.add(SpendingInsight(
                InsightType.DAILY_AVERAGE, "📊",
                "Daily average: €${String.format("%.2f", dailyAvg)}",
                "Based on last $days days",
                0.3f
            ))
        }
        
        // Sort by severity
        return insights.sortedByDescending { it.severity }
    }
    
    data class RecurringCandidate(val merchant: String, val amount: Double, val intervalDays: Int)
    
    private fun detectRecurring(expenses: List<Expense>): List<RecurringCandidate> {
        val results = mutableListOf<RecurringCandidate>()
        val byMerchant = expenses.groupBy { it.merchant.uppercase() }
        
        for ((merchant, exps) in byMerchant) {
            if (exps.size < 2) continue
            val sorted = exps.sortedBy { it.date }
            
            // Check if amounts are similar (within 10%)
            val amounts = sorted.map { it.amount }
            val avgAmount = amounts.average()
            val allSimilar = amounts.all { Math.abs(it - avgAmount) / avgAmount < 0.1 }
            
            if (allSimilar && sorted.size >= 2) {
                // Calculate average interval
                val intervals = sorted.zipWithNext().map { (a, b) -> 
                    ((b.date - a.date) / 86400000L).toInt() 
                }
                val avgInterval = intervals.average().toInt()
                
                // Monthly (25-35 days) or yearly (350-380 days)
                if (avgInterval in 25..35 || avgInterval in 350..380 || avgInterval in 6..8) {
                    results.add(RecurringCandidate(merchant, avgAmount, avgInterval))
                }
            }
        }
        return results
    }
}
```

---

## Phase 4: Budget System

### 4A. Budget Entity

```kotlin
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?, // null = overall budget
    val amount: Double,
    val period: BudgetPeriod,
    val startDate: Long,
    val isActive: Boolean = true,
    val notifyAt: Float = 0.8f // Notify at 80% spent
)

enum class BudgetPeriod {
    DAILY, WEEKLY, MONTHLY, YEARLY
}
```

### 4B. Budget Monitoring

When a new expense is created, check if any budget threshold is crossed and send a local notification warning the user.

---

## Phase 5: Advanced Features (Longer Term)

### 5A. Export & Reports

```kotlin
// CSV export
fun exportToCSV(expenses: List<Expense>, categories: Map<Long, Category>): String {
    val header = "Date,Merchant,Amount,Currency,Category,Type\n"
    val rows = expenses.joinToString("\n") { exp ->
        val cat = exp.categoryId?.let { categories[it]?.name } ?: "Uncategorized"
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(exp.date))
        "$date,\"${exp.merchant}\",${exp.amount},${exp.currency},$cat,${exp.transactionType}"
    }
    return header + rows
}
```

### 5B. Recurring Transaction Manager

Dedicated screen showing detected subscriptions/recurring payments with:
- Next expected date
- Monthly/yearly cost
- Option to set reminders
- "Cancel this?" suggestions for unused subscriptions

### 5C. Multi-Currency Support

```kotlin
data class ExchangeRate(
    @PrimaryKey val currencyPair: String, // "USD_EUR"
    val rate: Double,
    val updatedAt: Long
)
```

Convert all amounts to user's base currency for totals, but show original currency in details.

### 5D. Shared Expenses / Splitting

For users who share expenses with partners/roommates:
- Tag expenses as "shared"
- Split 50/50 or custom ratios
- Track who owes whom

### 5E. Receipt Photo Attachment

Allow users to photograph receipts and attach them to expenses:
- Camera intent
- Store image path in Expense entity
- Optional OCR later (ML Kit)

### 5F. Widgets

Android home screen widgets showing:
- Today's spending
- Monthly total
- Quick "add expense" button

---

## Phase 6: Visual Design Improvements

### 6A. Navigation Redesign

Replace current 4-tab layout with 5 focused tabs:

```
[Home/Dashboard] [Transactions] [Analytics] [Budgets] [Settings]
```

Move Debug into Settings as a hidden section.

### 6B. Transaction List Improvements

Group transactions by date with sticky headers:
```
── Today ──────────────────
  🛒 Sklavenitis        -€23.45
  ☕ Starbucks           -€4.50

── Yesterday ──────────────
  ⛽ Shell               -€45.00
  🍕 efood               -€12.80

── Monday, Jun 12 ─────────
  📱 Netflix             -€13.99
```

### 6C. Color-Coded Amount Display

```kotlin
@Composable
fun AmountText(amount: Double, type: TransactionType) {
    val color = when (type) {
        TransactionType.PURCHASE -> MaterialTheme.colorScheme.error
        TransactionType.DEPOSIT -> Color(0xFF4CAF50)
        TransactionType.WITHDRAWAL -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = when (type) {
        TransactionType.DEPOSIT -> "+"
        else -> "-"
    }
    Text(
        text = "$prefix€${String.format("%.2f", amount)}",
        color = color,
        fontWeight = FontWeight.Bold
    )
}
```

---

## Recommended Implementation Order

```
Week 1-2: Phase 1 (Confidence + Review Queue + Corrections)
          ↓
          This directly improves parsing quality
          
Week 3:   Phase 3A-3C (Analytics data models + Insights engine)
          ↓
          Users see value immediately
          
Week 4:   Phase 3D + Charts (Vico integration, donut chart, bar chart)
          ↓
          Visual wow factor
          
Week 5:   Phase 2B (Naive Bayes classifier, trained from corrections)
          ↓
          App gets smarter over time
          
Week 6:   Phase 4 (Budgets)
          ↓
          Core financial feature
          
Week 7:   Phase 5A + 5B (Export + Recurring detection)
          ↓
          Power user features
          
Week 8+:  Phase 5C-5F + Phase 6 (Polish, widgets, extras)
```

The key insight: **every user interaction is training data**. The review queue, category corrections, merchant renames, and rejection patterns all feed back to make the system smarter for that specific user. This is your competitive advantage over apps that rely on generic server-side rules. Your app learns the user's specific bank, notification format, and spending patterns entirely on-device with zero privacy concerns.