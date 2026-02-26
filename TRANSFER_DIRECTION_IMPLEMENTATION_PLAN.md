# AUTOMATIC TRANSFER DIRECTION DETECTION
## Implementation Plan for INCOMING/OUTGOING Auto-Detection

**Status:** Design Document  
**Priority:** HIGH  
**Estimated Effort:** 16-20 hours  
**Impact:** Eliminates manual editing for 90%+ of transfers

---

## CURRENT STATE

### What's Working:
- ✅ `TransferDirection` enum exists (INCOMING, OUTGOING)
- ✅ `transferDirection` field in Expense entity
- ✅ UI allows manual editing in AddExpenseSheet.kt
- ✅ DEPOSIT and TRANSFER types auto-detected from notifications

### What's Missing:
- ❌ Direction detection from notification text
- ❌ Automatic INCOMING/OUTGOING classification
- ❌ Users must manually edit each transfer

---

## IMPLEMENTATION PLAN

### Phase 1: Enhanced ParsedTransaction (2 hours)

**Modify:** `domain/parser/AppParserRegistry.kt`

```kotlin
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float,
    val date: Long? = null,
    // NEW FIELDS:
    val transferDirection: TransferDirection? = null,  // Auto-detected direction
    val transferAccountName: String? = null,          // "From: Checking" or "To: Savings"
    val isIncoming: Boolean? = null                   // Helper for quick checks
) {
    init {
        // Validation for transfer direction
        if (type == TransactionType.TRANSFER || type == TransactionType.DEPOSIT) {
            require(transferDirection != null) {
                "Transfer/Deposit transactions must have a direction"
            }
        }
        
        // Auto-set isIncoming based on direction
        if (transferDirection != null) {
            isIncoming = (transferDirection == TransferDirection.INCOMING)
        }
    }
}
```

---

### Phase 2: Direction Detection Patterns (6 hours)

**Create:** `domain/parser/TransferDirectionDetector.kt`

```kotlin
@Singleton
class TransferDirectionDetector @Inject constructor() {
    
    // INCOMING patterns (money received)
    private val INCOMING_PATTERNS = listOf(
        // English
        Pattern.compile("""received\s+(?:from|via)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""credited\s+(?:to|with)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""deposit(?:ed)?""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""money\s+received""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""transfer\s+(?:in|received)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""salary\s+(?:credited|received)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""refund\s+(?:received|credited)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""from\s+(.+?)\s+(?:to|→|->)""", Pattern.CASE_INSENSITIVE),  // "From John to You"
        Pattern.compile("""\+\s*[€$£]?\s*\d"""),  // +€50.00
        
        // Greek
        Pattern.compile("""προς\s+λήψη""", Pattern.CASE_INSENSITIVE),  // received
        Pattern.compile("""πιστώθηκε""", Pattern.CASE_INSENSITIVE),  // credited
        Pattern.compile("""κατάθεση""", Pattern.CASE_INSENSITIVE),  // deposit
        
        // Revolut-specific
        Pattern.compile("""revolut\s+to\s+revolut""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""added\s+money""", Pattern.CASE_INSENSITIVE),
        
        // Bank-specific
        Pattern.compile("""credit\s+transfer""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""incoming\s+transfer""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""wire\s+received""", Pattern.CASE_INSENSITIVE),
        Pattern.compile(""" ACH\s+(?:credit|deposit)""", Pattern.CASE_INSENSITIVE)
    )
    
    // OUTGOING patterns (money sent)
    private val OUTGOING_PATTERNS = listOf(
        // English
        Pattern.compile("""sent\s+(?:to|via)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""transferred\s+(?:to|out)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""transfer\s+(?:out|sent)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""withdrawal\s+(?:to|from)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""paid\s+(?:to|via)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""to\s+(.+?)\s+(?:from|→|->)""", Pattern.CASE_INSENSITIVE),  // "To John from You"
        Pattern.compile("""-\s*[€$£]?\s*\d"""),  // -€50.00
        Pattern.compile("""outgoing\s+transfer""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""debit\s+transfer""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""sent\s+via""", Pattern.CASE_INSENSITIVE),
        
        // Greek
        Pattern.compile("""αποστολή""", Pattern.CASE_INSENSITIVE),  // sent
        Pattern.compile("""μεταφορά\s+σε""", Pattern.CASE_INSENSITIVE),  // transfer to
        Pattern.compile("""ανάληψη""", Pattern.CASE_INSENSITIVE),  // withdrawal
        
        // Revolut-specific
        Pattern.compile("""you\s+paid""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""you\s+sent""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""payment\s+to""", Pattern.CASE_INSENSITIVE),
        
        // Bank-specific
        Pattern.compile("""ACH\s+debit""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""bill\s+payment""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""wire\s+sent""", Pattern.CASE_INSENSITIVE)
    )
    
    // Account name extraction patterns
    private val ACCOUNT_PATTERNS = listOf(
        Pattern.compile("""from\s+(.+?)(?:\s+(?:to|→|->)|\s*$)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""to\s+(.+?)(?:\s+(?:from|→|->)|\s*$)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:account|acc|a/c)[:\s]+(.+?)(?:\s|$)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:from|to)[:\s]+(.+?)(?:\s|$)""", Pattern.CASE_INSENSITIVE)
    )
    
    /**
     * Detects transfer direction from notification text
     * Returns null if cannot determine
     */
    fun detectDirection(
        title: String?,
        text: String?,
        bigText: String?,
        transactionType: TransactionType
    ): TransferDirection? {
        // Only detect for TRANSFER and DEPOSIT types
        if (transactionType != TransactionType.TRANSFER && 
            transactionType != TransactionType.DEPOSIT) {
            return null
        }
        
        val allText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerText = allText.lowercase()
        
        // Check incoming patterns
        val incomingScore = INCOMING_PATTERNS.count { it.matcher(allText).find() }
        val outgoingScore = OUTGOING_PATTERNS.count { it.matcher(allText).find() }
        
        return when {
            incomingScore > outgoingScore -> TransferDirection.INCOMING
            outgoingScore > incomingScore -> TransferDirection.OUTGOING
            incomingScore > 0 && outgoingScore > 0 -> {
                // Ambiguous - check specific keywords
                resolveAmbiguousCase(allText)
            }
            else -> null  // Cannot determine
        }
    }
    
    /**
     * Extracts account name from notification text
     */
    fun extractAccountName(
        title: String?,
        text: String?,
        bigText: String?
    ): String? {
        val allText = listOfNotNull(title, text, bigText).joinToString(" ")
        
        for (pattern in ACCOUNT_PATTERNS) {
            val matcher = pattern.matcher(allText)
            if (matcher.find()) {
                return matcher.group(1)?.trim()?.take(50) // Limit length
            }
        }
        
        return null
    }
    
    private fun resolveAmbiguousCase(text: String): TransferDirection? {
        // Specific disambiguation rules
        return when {
            // You received = incoming
            text.contains("you received", ignoreCase = true) -> TransferDirection.INCOMING
            // You sent/paid = outgoing
            text.contains("you sent", ignoreCase = true) ||
            text.contains("you paid", ignoreCase = true) -> TransferDirection.OUTGOING
            // Default to null if still ambiguous
            else -> null
        }
    }
}
```

---

### Phase 3: Update All Parsers (4 hours)

**Modify each parser to detect direction:**

#### 1. RevolutParser.kt
```kotlin
override fun parse(...): ParsedTransaction? {
    // ... existing logic ...
    
    if (receivedMatcher.find()) {
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.DEPOSIT,
            confidence = 0.90f,
            transferDirection = TransferDirection.INCOMING,  // "Received from..."
            transferAccountName = merchantCleaner.clean(receivedMatcher.group(3))
        )
    } else if (paidMatcher.find()) {
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,  // Or TRANSFER if person-to-person
            confidence = 0.95f,
            transferDirection = TransferDirection.OUTGOING,  // "Paid to..."
            transferAccountName = merchantCleaner.clean(paidMatcher.group(3))
        )
    }
}
```

#### 2. GreekBankParser.kt
```kotlin
// Add direction detection based on transaction codes:
// "Χ" = Χρέωση (Debit/Outgoing)
// "Π" = Πίστωση (Credit/Incoming)

when (transactionCode) {
    "Χ", "ΧΡ" -> {
        type = TransactionType.TRANSFER
        direction = TransferDirection.OUTGOING
    }
    "Π", "ΠΙ" -> {
        type = TransactionType.DEPOSIT  // or TRANSFER
        direction = TransferDirection.INCOMING
    }
}
```

#### 3. SmsParser.kt
```kotlin
// Add direction detection based on SMS patterns
// "Sent" vs "Received"
// "Αποστολή" vs "Λήψη"
```

#### 4. GenericTransactionParser.kt
```kotlin
// Use TransferDirectionDetector for fallback detection
val direction = directionDetector.detectDirection(title, text, bigText, type)
```

---

### Phase 4: Update NotificationRepository (2 hours)

**Modify:** `data/repository/NotificationRepository.kt`

```kotlin
class NotificationRepository @Inject constructor(
    // ... existing dependencies ...
    private val directionDetector: TransferDirectionDetector  // NEW
) {
    
    private suspend fun processAndSaveInternal(notification: RawNotification) {
        // ... existing parsing logic ...
        
        val parsed = parserRegistry.parse(...)
        
        // Auto-detect direction if not set by parser
        val direction = parsed.transferDirection 
            ?: directionDetector.detectDirection(
                notification.title,
                notification.text,
                notification.bigText,
                parsed.type
            )
        
        // Extract account name
        val accountName = parsed.transferAccountName
            ?: directionDetector.extractAccountName(
                notification.title,
                notification.text,
                notification.bigText
            )
        
        // ... create expense with direction and account name ...
        val expense = Expense(
            // ... other fields ...
            transferDirection = direction,
            transferAccountName = accountName
        )
    }
}
```

---

### Phase 5: Update UI to Show Auto-Detected Direction (2 hours)

**Modify:** `ui/screens/review/ReviewScreen.kt`

```kotlin
// Show detected direction with visual indicator
@Composable
fun TransferDirectionBadge(
    direction: TransferDirection?,
    accountName: String?
) {
    when (direction) {
        TransferDirection.INCOMING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(SemanticColors.SuccessGreen.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Incoming",
                    tint = SemanticColors.SuccessGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Incoming${accountName?.let { " from $it" } ?: ""}",
                    color = SemanticColors.SuccessGreen,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        TransferDirection.OUTGOING -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Outgoing",
                    tint = SemanticColors.PrimaryIndigo,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Outgoing${accountName?.let { " to $it" } ?: ""}",
                    color = SemanticColors.PrimaryIndigo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        null -> {
            // Show manual edit prompt
            TextButton(onClick = { showEditDialog = true }) {
                Text("Set Direction", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

---

### Phase 6: Analytics & Reporting (2 hours)

**Add insights for transfers:**

```kotlin
// In InsightsEngine.kt
suspend fun buildTransferInsights(): TransferInsights {
    val transfers = expenseRepository.getExpensesByType(TransactionType.TRANSFER)
    
    val incoming = transfers.filter { it.transferDirection == TransferDirection.INCOMING }
    val outgoing = transfers.filter { it.transferDirection == TransferDirection.OUTGOING }
    val unknown = transfers.filter { it.transferDirection == null }
    
    return TransferInsights(
        totalIncoming = incoming.sumOf { it.amount },
        totalOutgoing = outgoing.sumOf { it.amount },
        netTransferFlow = incoming.sumOf { it.amount } - outgoing.sumOf { it.amount },
        autoDetectedCount = transfers.count { it.transferDirection != null },
        manualEditCount = unknown.size,
        autoDetectionRate = if (transfers.isNotEmpty()) {
            (transfers.count { it.transferDirection != null }.toFloat() / transfers.size * 100)
        } else 0f
    )
}
```

---

### Phase 7: Testing (2 hours)

**Create:** `domain/parser/TransferDirectionDetectorTest.kt`

```kotlin
class TransferDirectionDetectorTest {
    
    private val detector = TransferDirectionDetector()
    
    @Test
    fun `detects incoming - received from`() {
        val direction = detector.detectDirection(
            title = "Received €100.00",
            text = "Received €100.00 from John",
            bigText = null,
            transactionType = TransactionType.DEPOSIT
        )
        assertEquals(TransferDirection.INCOMING, direction)
    }
    
    @Test
    fun `detects outgoing - sent to`() {
        val direction = detector.detectDirection(
            title = "You sent €50.00",
            text = "You sent €50.00 to Mary",
            bigText = null,
            transactionType = TransactionType.TRANSFER
        )
        assertEquals(TransferDirection.OUTGOING, direction)
    }
    
    @Test
    fun `extracts account name - from`() {
        val name = detector.extractAccountName(
            title = "Transfer",
            text = "Transfer from Checking Account to Savings",
            bigText = null
        )
        assertEquals("Checking Account", name)
    }
    
    @Test
    fun `handles ambiguous - you received`() {
        val direction = detector.detectDirection(
            title = "Transaction",
            text = "You received a transfer",
            bigText = null,
            transactionType = TransactionType.TRANSFER
        )
        assertEquals(TransferDirection.INCOMING, direction)
    }
    
    @Test
    fun `returns null for purchase`() {
        val direction = detector.detectDirection(
            title = "Starbucks",
            text = "Paid €5.00 at Starbucks",
            bigText = null,
            transactionType = TransactionType.PURCHASE
        )
        assertNull(direction)
    }
}
```

---

## SUCCESS METRICS

### Target Metrics:
- **Auto-detection rate:** >90% of transfers should have direction automatically set
- **Accuracy:** >95% of auto-detected directions should be correct
- **Coverage:** Support for all major banks (NBG, Piraeus, Eurobank, Alpha, Revolut, etc.)
- **Languages:** English, Greek, with extensibility for others

### Tracking:
```kotlin
// Track in analytics
TransferInsights(
    autoDetectedCount = 850,
    manualEditCount = 50,
    autoDetectionRate = 94.4f,  // Goal: >90%
    accuracyRate = 97.2f        // Goal: >95%
)
```

---

## ROLLBACK PLAN

If issues arise:
1. Direction detection is additive (null = manual edit)
2. Can disable detector via feature flag
3. Users can always override auto-detected values
4. No breaking changes to existing data

---

## FUTURE ENHANCEMENTS

1. **Machine Learning:** Train model on user corrections to improve detection
2. **Smart Defaults:** Learn user's typical transfer patterns
3. **Multi-Language:** Add support for French, German, Spanish
4. **Bank-Specific:** Custom patterns for each bank's notification format
5. **Visual Indicators:** Animated arrows in UI for transfers

---

## TIMELINE

| Phase | Effort | Status |
|-------|--------|--------|
| 1. Enhanced ParsedTransaction | 2h | Ready |
| 2. Direction Detector | 6h | Ready |
| 3. Update Parsers | 4h | Ready |
| 4. Update Repository | 2h | Ready |
| 5. UI Updates | 2h | Ready |
| 6. Analytics | 2h | Ready |
| 7. Testing | 2h | Ready |
| **TOTAL** | **20h** | |

---

## IMPLEMENTATION CHECKLIST

- [ ] Create TransferDirectionDetector class
- [ ] Add direction field to ParsedTransaction
- [ ] Update RevolutParser with direction detection
- [ ] Update GreekBankParser with direction detection
- [ ] Update SmsParser with direction detection
- [ ] Update GenericTransactionParser with fallback detection
- [ ] Integrate detector into NotificationRepository
- [ ] Update ReviewScreen UI to show direction badges
- [ ] Add transfer insights to analytics
- [ ] Write comprehensive unit tests
- [ ] Test with real notifications from all banks
- [ ] Monitor auto-detection rate and accuracy
- [ ] Document patterns for future bank additions

---

This implementation will eliminate the need for manual direction editing in 90%+ of cases, providing a seamless user experience while maintaining high accuracy.
