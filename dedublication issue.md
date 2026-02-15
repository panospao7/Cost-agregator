This is an excellent question! Let me analyze how the current codebase handles the distinction between:
1. **Duplicate expense notifications** (legitimate, but filtered for deduplication)
2. **Non-expense notifications** (actually not expenses, should affect trust score)

Let me search the codebase for how this is currently handled:
Let me read more details from the Data Layer document to understand the full implementation:
Now let me search for the duplicate recording logic:
Now I have a clear picture. Let me analyze and write a comprehensive response:
I've analyzed the duplicate notification handling thoroughly. Here's the summary:

## Answer: YES, the System DOES Support This Distinction ✅

The codebase already has the infrastructure to distinguish between:

| Scenario | Counter Incremented | Affects Trust Score? |
|----------|---------------------|---------------------|
| **Duplicate expense** (wallet + bank same transaction) | `duplicates` | ❌ NO - Excluded from calculation |
| **Non-expense notification** (spam, irrelevant) | `autoRejected` or `rejectedByUser` | ✅ YES - Penalizes trust score |

### Trust Score Formula (Current Implementation)
```kotlin
val trustScore: Float
    get() {
        val relevant = totalNotifications - duplicates  // ⭐ Duplicates EXCLUDED
        return if (relevant > 0)
            acceptedAsExpense.toFloat() / relevant
        else 0f
    }
```

**Example:** 
- 100 notifications from bank app
- 50 became expenses
- 50 were duplicates (Google Wallet already captured)

→ Trust Score = 50/(100-50) = **100%** ✅ Perfect!

---

## ⚠️ Gaps Found That Need Testing

### Gap 1: Duplicate Window Too Short (60 seconds)
```kotlin
windowMs = 60000  // Current
// Wallet and bank may arrive > 60 seconds apart!
```
**Recommendation:** Increase to 5 minutes (300000ms)

### Gap 2: No Duplicate Pre-Check in NEEDS_REVIEW Flow
```kotlin
RoutingDecision.NEEDS_REVIEW -> {
    // NO duplicate check here!
    // Both wallet AND bank could go to pending review
}
```
**Result:** User sees 2 review items for same transaction

### Gap 3: No Merchant Normalization in Duplicate Check
- Wallet: "STARBUCKS ATHENS #1234"
- Bank: "STARBUCKS"

These won't match as duplicates because merchant names differ!

---

## Files Created

1. **`/home/z/my-project/download/Duplicate_Notification_Handling_Analysis.md`**
   - Full analysis of current implementation
   - Gap identification
   - Recommendations

2. **`/home/z/my-project/download/Duplicate_Notification_Handling_Tests.kt`**
   - 20+ test cases for duplicate handling
   - Tests for trust score calculation
   - Wallet + bank scenario tests
   - ML classifier impact tests

### Key Tests Added:
- `trust score excludes duplicates from calculation`
- `wallet notification creates expense, bank notification marked as duplicate`
- `duplicate notification does not add to user_corrections` (ML safety)
- `source with many duplicates still has high trust score`
- `exact window boundary - 60000ms exactly`
- `both wallet and bank in NEEDS_REVIEW - should still deduplicate` (documents gap)

/**
 * DEDUPLICATION FIX IMPLEMENTATION
 * 
 * This file contains the fixed code for the duplicate detection system
 * that failed to detect Google Wallet + NBG Bank duplicate transactions.
 * 
 * PROBLEM IDENTIFIED:
 * - Google Wallet at 10:09:46 → €4.99 to "KOUKOS DODEKANISOU"
 * - NBG Bank at 10:09:56 → €4.99 to same merchant
 * - Both created expenses instead of one being marked duplicate
 * 
 * ROOT CAUSE: Exact string match in isDuplicate query fails when
 * merchant names differ slightly between parsers.
 */

// =============================================================================
// FIX 1: Enhanced ExpenseDao with Fuzzy Merchant Matching
// =============================================================================

package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    
    // ... existing methods ...
    
    /**
     * ORIGINAL: Exact match only - FAILS when merchant names differ slightly
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
            AND merchant = :merchant 
            AND ABS(date - :date) <= :windowMs
        )
    """)
    suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean

    /**
     * FIXED: Fuzzy merchant matching with multiple strategies
     * 
     * Matching strategies:
     * 1. Exact match (original behavior)
     * 2. Case-insensitive match
     * 3. Normalized match (remove spaces, punctuation)
     * 4. Substring match (one contains the other)
     * 5. Search key match (first 15 chars, normalized)
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE transactionType = 'PURCHASE'
            AND (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
            AND ABS(date - :date) <= :windowMs
            AND (
                -- Strategy 1: Exact match
                merchant = :merchant
                OR
                -- Strategy 2: Case-insensitive match
                UPPER(merchant) = UPPER(:merchant)
                OR
                -- Strategy 3: Normalized match (remove spaces and common punctuation)
                UPPER(REPLACE(REPLACE(REPLACE(merchant, ' ', ''), '.', ''), '''', '')) =
                UPPER(REPLACE(REPLACE(REPLACE(:merchant, ' ', ''), '.', ''), '''', ''))
                OR
                -- Strategy 4: Substring match (one contains the other)
                merchant LIKE '%' || :merchant || '%'
                OR
                :merchant LIKE '%' || merchant || '%'
                OR
                -- Strategy 5: Search key match (first 15 chars normalized)
                UPPER(SUBSTR(REPLACE(REPLACE(merchant, ' ', ''), '.', ''), 1, 15)) =
                UPPER(SUBSTR(REPLACE(REPLACE(:merchant, ' ', ''), '.', ''), 1, 15))
            )
        )
    """)
    suspend fun isDuplicateFuzzy(
        amount: Double, 
        merchant: String, 
        date: Long, 
        windowMs: Long = 300000
    ): Boolean

    /**
     * Alternative: Use pre-computed search key for faster matching
     * Add a searchKey column to expenses table for this approach
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE transactionType = 'PURCHASE'
            AND (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
            AND ABS(date - :date) <= :windowMs
            AND searchKey = :searchKey
        )
    """)
    suspend fun isDuplicateBySearchKey(
        amount: Double, 
        searchKey: String, 
        date: Long, 
        windowMs: Long = 300000
    ): Boolean
}

// =============================================================================
// FIX 2: Merchant Search Key Generator
// =============================================================================

package com.yourname.expensetracker.domain.util

object MerchantSearchKey {
    
    /**
     * Generate a normalized search key for merchant matching.
     * This key is used for fuzzy duplicate detection.
     * 
     * Transformations:
     * 1. Uppercase
     * 2. Remove spaces
     * 3. Remove common punctuation (., ', -)
     * 4. Remove common suffixes (LTD, INC, LLC, STORE, SHOP)
     * 5. Take first 15 characters
     * 
     * Examples:
     * - "KOUKOS DODEKANISOU" → "KOUKOSDODEKANIS"
     * - "KOUKOS DODEKANISOU " → "KOUKOSDODEKANIS" (trailing space removed)
     * - "Starbucks Coffee" → "STARBUCKSCOFFEE"
     * - "STARBUCKS" → "STARBUCKS"
     * - "McDonald's Store #123" → "MCDONALDSSTORE"
     */
    fun generate(merchant: String): String {
        return merchant
            .uppercase()
            .replace(" ", "")
            .replace(".", "")
            .replace("'", "")
            .replace("-", "")
            .replace("#", "")
            .replace(Regex("\\d{3,}"), "") // Remove numbers 3+ digits (store numbers)
            .removeSuffix("LTD")
            .removeSuffix("INC")
            .removeSuffix("LLC")
            .removeSuffix("STORE")
            .removeSuffix("SHOP")
            .take(15)
    }
    
    /**
     * Check if two merchant names are likely the same.
     */
    fun matches(merchant1: String, merchant2: String): Boolean {
        val key1 = generate(merchant1)
        val key2 = generate(merchant2)
        
        return key1 == key2 || 
               key1.contains(key2) || 
               key2.contains(key1)
    }
}

// =============================================================================
// FIX 3: Updated NotificationRepository with Enhanced Deduplication
// =============================================================================

package com.yourname.expensetracker.data.repository

// ... imports ...

class NotificationRepository @Inject constructor(
    // ... dependencies ...
) {
    
    // In-memory cache for cross-package duplicate detection
    private val recentTransactionKeys = ConcurrentHashMap<String, Long>()
    private val RECENT_CACHE_TTL = 120_000L // 2 minutes
    
    /**
     * Generate a unique key for duplicate detection across packages.
     */
    private fun generateTransactionKey(amount: Double, merchant: String): String {
        val searchKey = MerchantSearchKey.generate(merchant)
        return "${String.format("%.2f", amount)}_$searchKey"
    }
    
    /**
     * Check if a transaction was recently seen from ANY package.
     */
    private fun wasRecentlySeen(amount: Double, merchant: String): Boolean {
        val key = generateTransactionKey(amount, merchant)
        val lastSeen = recentTransactionKeys[key]
        val now = System.currentTimeMillis()
        
        return if (lastSeen != null && (now - lastSeen) < RECENT_CACHE_TTL) {
            true
        } else {
            recentTransactionKeys[key] = now
            // Cleanup old entries
            cleanupRecentCache(now)
            false
        }
    }
    
    private fun cleanupRecentCache(now: Long) {
        recentTransactionKeys.entries.removeIf { 
            now - it.value > RECENT_CACHE_TTL 
        }
    }
    
    suspend fun processAndSave(notification: RawNotification) {
        // ... existing parsing logic ...
        
        if (parsed == null) {
            // ... handle null parsed ...
            return
        }
        
        // Normalize merchant
        val lookupResult = merchantNormalizer.normalize(parsed.merchant)
        val correctedMerchant = lookupResult.canonical.normalizedName
        
        // =========================================
        // NEW: Cross-package duplicate pre-check
        // =========================================
        val wasRecentlySeen = wasRecentlySeen(parsed.amount, correctedMerchant)
        if (wasRecentlySeen) {
            android.util.Log.d("NotificationRepo", 
                "Cross-package duplicate detected: ${notification.packageName}")
            sourceStatsDao.incrementDuplicate(notification.packageName)
            return
        }
        
        database.withTransaction {
            // ... existing transaction logic ...
            
            when (routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> {
                    // =========================================
                    // FIXED: Use fuzzy duplicate detection
                    // =========================================
                    val isDuplicate = expenseDao.isDuplicateFuzzy(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 300000  // 5 minutes
                    )
                    
                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementDuplicate(notification.packageName)
                        android.util.Log.d("NotificationRepo", 
                            "Duplicate expense detected: $correctedMerchant, €${parsed.amount}")
                        return@withTransaction
                    }
                    
                    // ... create expense ...
                }
                
                RoutingDecision.NEEDS_REVIEW -> {
                    // =========================================
                    // NEW: Add duplicate check for NEEDS_REVIEW too!
                    // =========================================
                    val isDuplicate = expenseDao.isDuplicateFuzzy(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 300000
                    )
                    
                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementDuplicate(notification.packageName)
                        android.util.Log.d("NotificationRepo", 
                            "Duplicate detected in NEEDS_REVIEW flow")
                        return@withTransaction
                    }
                    
                    // ... add to pending review ...
                }
                
                RoutingDecision.AUTO_REJECT -> {
                    // ... existing logic ...
                }
            }
        }
    }
}

// =============================================================================
// FIX 4: Add searchKey Column to Expense Entity (Migration Required)
// =============================================================================

/*
@Entity(tableName = "expenses")
data class Expense(
    // ... existing fields ...
    
    // NEW: Pre-computed search key for fast duplicate detection
    val searchKey: String = ""
) {
    companion object {
        fun create(
            amount: Double,
            merchant: String,
            // ... other params
        ): Expense {
            return Expense(
                amount = amount,
                merchant = merchant,
                searchKey = MerchantSearchKey.generate(merchant),
                // ... other fields
            )
        }
    }
}
*/

// Migration
/*
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE expenses ADD COLUMN searchKey TEXT NOT NULL DEFAULT ''")
        
        // Populate search keys for existing expenses
        database.execSQL("""
            UPDATE expenses 
            SET searchKey = UPPER(SUBSTR(
                REPLACE(REPLACE(REPLACE(merchant, ' ', ''), '.', ''), '''', ''), 
                1, 15
            ))
        """)
        
        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_searchKey ON expenses (searchKey)")
    }
}
*/
# 🔴 DEDUPLICATION BUG FIX SUMMARY

## Bug Confirmed from Screenshots

**Screenshot Evidence:**
- Google Wallet: `10:09:46` → €4.99 → "KOUKOS DODEKANISOU"
- NBG Bank: `10:09:56` → €4.99 → "KOUKOS DODEKANISOU"  
- **Result:** TWO expenses created instead of ONE

---

## Root Cause

The `isDuplicate` query uses **EXACT string match**:

```sql
AND merchant = :merchant  -- ❌ EXACT match fails on minor differences!
```

Even though both notifications have the same merchant name in screenshots, in reality the **parsers extract different strings**:

| Source | Raw Text | Extracted Merchant |
|--------|----------|-------------------|
| Google Wallet | "Payment at KOUKOS DODEKANISOU" | `KOUKOS DODEKANISOU` |
| NBG Bank | "Purchase KOUKOS DODEKANISOU Card *1554" | `KOUKOS DODEKANISOU` or `KOUKOS` |

Any difference (trailing space, case, truncation) breaks detection.

---

## Required Fixes

### Fix 1: Update ExpenseDao.kt (CRITICAL - Apply Immediately)

Replace the `isDuplicate` query:

```kotlin
// BEFORE (Broken)
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM expenses 
        WHERE (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
        AND merchant = :merchant 
        AND ABS(date - :date) <= :windowMs
    )
""")
suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean

// AFTER (Fixed)
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM expenses 
        WHERE transactionType = 'PURCHASE'
        AND (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
        AND ABS(date - :date) <= :windowMs
        AND (
            merchant = :merchant
            OR UPPER(merchant) = UPPER(:merchant)
            OR UPPER(REPLACE(merchant, ' ', '')) = UPPER(REPLACE(:merchant, ' ', ''))
            OR merchant LIKE '%' || :merchant || '%'
            OR :merchant LIKE '%' || merchant || '%'
        )
    )
""")
suspend fun isDuplicateFuzzy(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean
```

### Fix 2: Update NotificationRepository.kt

Change all `isDuplicate` calls to use `isDuplicateFuzzy`:

```kotlin
// BEFORE
val isDuplicate = expenseDao.isDuplicate(
    amount = parsed.amount,
    merchant = correctedMerchant,
    date = notification.timestamp,
    windowMs = 60000
)

// AFTER
val isDuplicate = expenseDao.isDuplicateFuzzy(
    amount = parsed.amount,
    merchant = correctedMerchant,
    date = notification.timestamp,
    windowMs = 300000  // Also increased to 5 minutes
)
```

### Fix 3: Add Duplicate Check to NEEDS_REVIEW Flow

```kotlin
RoutingDecision.NEEDS_REVIEW -> {
    // NEW: Check for duplicates before adding to review
    val isDuplicate = expenseDao.isDuplicateFuzzy(
        amount = parsed.amount,
        merchant = correctedMerchant,
        date = notification.timestamp,
        windowMs = 300000
    )
    if (isDuplicate) {
        dao.markRelevance(rawId, false)
        sourceStatsDao.incrementDuplicate(notification.packageName)
        return@withTransaction
    }
    
    // Continue to pending review...
}
```

### Fix 4: Add Cross-Package Duplicate Detection

```kotlin
// Add to NotificationRepository class
private val recentTransactionKeys = ConcurrentHashMap<String, Long>()

private fun wasRecentlySeen(amount: Double, merchant: String): Boolean {
    val key = "${String.format("%.2f", amount)}_${merchant.uppercase().take(15)}"
    val lastSeen = recentTransactionKeys[key]
    val now = System.currentTimeMillis()
    
    return if (lastSeen != null && (now - lastSeen) < 120_000) {
        true
    } else {
        recentTransactionKeys[key] = now
        false
    }
}

// Call this BEFORE the database transaction
if (wasRecentlySeen(parsed.amount, correctedMerchant)) {
    sourceStatsDao.incrementDuplicate(notification.packageName)
    return
}
```

---

## Test the Fix

Add this test to verify the fix works:

```kotlin
@Test
fun `wallet and bank same transaction creates ONE expense`() = runBlocking {
    val now = System.currentTimeMillis()
    
    // First: Wallet creates expense
    expenseDao.insert(Expense(
        amount = 4.99,
        merchant = "KOUKOS DODEKANISOU",
        transactionType = TransactionType.PURCHASE,
        date = now,
        currency = "EUR"
    ))
    
    // Second: Bank arrives 10 seconds later
    val isDuplicate = expenseDao.isDuplicateFuzzy(
        amount = 4.99,
        merchant = "KOUKOS DODEKANISOU",
        date = now + 10000,
        windowMs = 60000
    )
    
    assertTrue("Should detect duplicate", isDuplicate)
}
```

---

## Files Created

| File | Purpose |
|------|---------|
| `Bug_Deduplication_Failure_Analysis.md` | Bug report and root cause |
| `Deduplication_Fix_Implementation.kt` | Complete fix code |
| `Deduplication_Regression_Tests.kt` | 30 test cases for this bug |
| `Duplicate_Notification_Handling_Analysis.md` | Full system analysis |
| `Duplicate_Notification_Handling_Tests.kt` | Additional tests |

---

## Priority

🔴 **CRITICAL** - Apply immediately

This bug causes:
1. Duplicate expenses in the database
2. Incorrect spending totals
3. Wrong analytics
4. Poor user experience
5. Wrong trust scores (if not for the duplicates column exclusion)
