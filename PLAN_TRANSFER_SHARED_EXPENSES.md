# Transfer & Shared Expense Tracking - Implementation Plan

> **Status**: ✅ IMPLEMENTED (February 2026)

---

## Overview

This document details the implementation plan for adding two new features to ExpenseTracker:

1. **Transfer Tracking** - Track money transfers (between own accounts AND money lent/borrowed)
2. **Not Mine / Shared Expenses** - Mark expenses as belonging to others or split with others

---

## Table of Contents

1. [Requirements Summary](#requirements-summary)
2. [Database Schema Changes](#database-schema-changes)
3. [UI Changes](#ui-changes)
4. [Business Logic Updates](#business-logic-updates)
5. [Implementation Order](#implementation-order)
6. [File Modification List](#file-modification-list)
7. [Testing Considerations](#testing-considerations)

---

## Requirements Summary

### Transfer Tracking

| Use Case | Description | UI Behavior |
|----------|-------------|-------------|
| **Account Transfer** | Moving money between my own accounts | Show as TRANSFER with direction indicator |
| **Money Lent** | I lent money to someone | Show as TRANSFER (OUTGOING) with person name |
| **Money Borrowed** | Someone owes me money | Show as TRANSFER (INCOMING) with person name |

**Key Requirement**: Both use cases should be supported simultaneously.

### Not Mine Expenses

| Feature | Description |
|---------|-------------|
| **Not Mine** | Mark expense as someone else's |
| **Owner Name** | Track who the expense belongs to (e.g., "Partner", "Roommate", "Friend") |

**Key Requirement**: These expenses should be **excluded** from:
- All analytics calculations
- Budget tracking
- Financial forecasting
- Spending totals

### Shared Expenses

| Feature | Description |
|---------|-------------|
| **Shared Expense** | Mark expense as split with someone |
| **Shared With** | Name of person shared with |
| **My Share** | Percentage or fixed amount I owe |

**Key Requirement**: Options to track both who owes what AND visibility control.

---

## Database Schema Changes

### New Fields in `Expense` Entity

```kotlin
@Entity(tableName = "expenses", ...)
data class Expense(
    // ... existing fields ...
    
    // === NEW: Transfer Tracking ===
    val transferDirection: TransferDirection? = null,  // INCOMING / OUTGOING (only for TRANSFER type)
    val transferAccountName: String? = null,           // Account name or person name for transfers
    
    // === NEW: Not Mine Tracking ===
    val isNotMine: Boolean = false,                     // Expense belongs to someone else
    val ownerName: String? = null,                      // Who owns this expense
    
    // === NEW: Shared Expense ===
    val isSharedExpense: Boolean = false,               // Split/shared with someone
    val sharedWithName: String? = null,                 // Person shared with
    val mySharePercentage: Int? = null,                 // e.g., 50 = 50%
    val myShareAmount: Double? = null                   // Alternative: fixed amount instead of percentage
)
```

### New Enum: `TransferDirection`

```kotlin
enum class TransferDirection {
    INCOMING,  // Money coming TO me (received transfer, borrowed money returned)
    OUTGOING   // Money going FROM me (sent transfer, lent money)
}
```

### Database Migration Required

- **Current DB Version**: Check `AppDatabase.kt`
- **New Version**: Increment by 1
- **Migration Strategy**: Add new columns with NULL (optional fields)

---

## UI Changes

### 1. AddExpenseSheet.kt

Add new UI sections for transfer and not-mine/shared tracking:

#### Transaction Type Selection
- Expand existing `TransactionType` selector to show transfer subtypes:
  - "Purchase" → PURCHASE
  - "Withdrawal" → WITHDRAWAL  
  - "Transfer (Between Accounts)" → TRANSFER (internal)
  - "Money Lent" → TRANSFER (OUTGOING with person)
  - "Money Borrowed" → TRANSFER (INCOMING with person)

#### New Section: Transfer Details (show when TransactionType == TRANSFER)
```
┌─────────────────────────────────────────┐
│ Transfer Details                        │
├─────────────────────────────────────────┤
│ Direction: ○ Incoming  ● Outgoing       │
│ Account/Person Name: [________________] │
└─────────────────────────────────────────┘
```

#### New Section: Not Mine (toggle)
```
┌─────────────────────────────────────────┐
│ ○ This expense is not mine             │
│ Owner Name: [________________]         │
└─────────────────────────────────────────┘
```

#### New Section: Shared Expense (toggle)
```
┌─────────────────────────────────────────┐
│ ○ Split with someone                    │
│ Shared with: [________________]         │
│ My share: [___] %  or  € [______]       │
└─────────────────────────────────────────┘
```

### 2. TransactionsScreen.kt

Add filter chips/tabs:
- "All" (default)
- "Mine only" (exclude isNotMine)
- "Not mine"
- "Shared"
- "Transfers"

Add indicator icons on transaction list items:
- 🔄 Transfer icon for transfers
- 👤 "Not mine" indicator  
- 🤝 Shared indicator

### 3. Transaction Detail/Edit

Allow editing of these new fields after creation.

### 4. HomeScreen.kt

Option to toggle "Include not mine" in totals (default: OFF/excluded).

---

## Business Logic Updates

### 1. SynthesisEngine.kt (Financial Forecasting)

**Change**: Filter out `isNotMine` expenses from all calculations.

```kotlin
// Current (pseudo-code):
val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }

// New:
val purchases = expenses.filter { 
    it.transactionType == TransactionType.PURCHASE && 
    !it.isNotMine  // Exclude not-mine expenses
}
```

**Affected Methods**:
- `generateTrajectory()`
- `calculateSpendingPace()`
- All forecast calculations

### 2. InsightsEngine.kt (Analytics)

**Change**: Add filter parameter to include/exclude not-mine.

```kotlin
fun getSpendingInsights(
    expenses: List<Expense>,
    includeNotMine: Boolean = false  // NEW PARAMETER
): List<Insight> {
    val filtered = if (includeNotMine) {
        expenses
    } else {
        expenses.filter { !it.isNotMine }
    }
    // ... rest of calculation
}
```

**Update All Analytics Methods**:
- `getSpendingBreakdown()`
- `getTopMerchants()`
- `getSpendingTrends()`
- All insight generators

### 3. BudgetCalculator.kt / BudgetMonitor.kt

**Change**: Option to include/exclude shared/not-mine from budgets.

```kotlin
data class BudgetCalculationParams(
    // ... existing
    val includeNotMine: Boolean = false,
    val includeShared: Boolean = false
)
```

### 4. ReviewQueueRepository.kt

When approving expenses from review, preserve/copy the new fields.

### 5. Notification Parsers

Update parsers to potentially detect:
- Transfer notifications from banks
- "Money sent to X" patterns

---

## Implementation Order

### Phase 1: Database & Core (Priority: HIGH)

1. **Add fields to `Expense.kt`** - Add all new columns and enum
2. **Update `Converters.kt`** - Handle new types if needed
3. **Create database migration** - Increment version, add migration
4. **Update `ManualExpenseRepository.kt`** - Handle new fields in insert

### Phase 2: Add Expense UI (Priority: HIGH)

1. **Update `AddExpenseState`** - Add new state fields
2. **Update `AddExpenseViewModel`** - Handle new field changes
3. **Update `AddExpenseSheet.kt`** - Add UI controls for:
   - Transfer direction (when TRANSFER selected)
   - Transfer account/person name
   - "Not mine" toggle + owner name
   - "Shared" toggle + shared with name + share amount/percentage
4. **Test manual expense creation**

### Phase 3: Transaction List UI (Priority: HIGH)

1. **Update `ExpenseWithCategory`** - Add new fields if needed
2. **Update `TransactionsViewModel`** - Add filter options
3. **Update `TransactionsScreen.kt`** - Add filter chips, indicators
4. **Test filtering**

### Phase 4: Business Logic (Priority: MEDIUM)

1. **Update `SynthesisEngine.kt`** - Filter out isNotMine
2. **Update `InsightsEngine.kt`** - Add includeNotMine parameter
3. **Update `BudgetCalculator.kt`** - Add filter options
4. **Test calculations**

### Phase 5: Parsers (Priority: LOW)

1. **Update `GreekBankParser.kt`** - Detect transfer patterns
2. **Update `RevolutParser.kt`** - Detect transfer patterns
3. **Update other parsers** as needed

---

## File Modification List

### Database Layer

| File | Change |
|------|--------|
| `data/database/entity/Expense.kt` | Add new fields + TransferDirection enum |
| `data/database/converter/Converters.kt` | Add converter for TransferDirection |
| `data/database/AppDatabase.kt` | Increment version, add migration |

### Repository Layer

| File | Change |
|------|--------|
| `data/repository/ManualExpenseRepository.kt` | Handle new fields in insert |
| `data/repository/ExpenseRepository.kt` | Add filter methods if needed |

### Domain Layer

| File | Change |
|------|--------|
| `domain/logic/SynthesisEngine.kt` | Filter isNotMine from calculations |
| `domain/analytics/InsightsEngine.kt` | Add includeNotMine parameter |
| `domain/budget/BudgetCalculator.kt` | Add filter options |
| `domain/budget/BudgetMonitor.kt` | Filter isNotMine from budget checks |

### UI Layer

| File | Change |
|------|--------|
| `ui/screens/addexpense/AddExpenseState.kt` | Add new state fields |
| `ui/screens/addexpense/AddExpenseViewModel.kt` | Handle new fields |
| `ui/screens/addexpense/AddExpenseSheet.kt` | Add UI controls |
| `ui/screens/transactions/TransactionsViewModel.kt` | Add filter state |
| `ui/screens/transactions/TransactionsScreen.kt` | Add filter UI, indicators |
| `ui/screens/home/HomeViewModel.kt` | Add includeNotMine option |

### DI Layer (if needed)

| File | Change |
|------|--------|
| `di/AppModule.kt` | Update database version binding |

---

## Testing Considerations

### Manual Testing Checklist

- [ ] Create expense with transfer (incoming)
- [ ] Create expense with transfer (outgoing)  
- [ ] Create expense with transfer + person name
- [ ] Create "not mine" expense
- [ ] Create "not mine" expense with owner name
- [ ] Create shared expense with percentage
- [ ] Create shared expense with fixed amount
- [ ] Create combined: transfer + not mine (edge case - should be rare)
- [ ] Edit existing expense to add these fields
- [ ] Filter transactions by new types
- [ ] Verify "not mine" excluded from home screen totals
- [ ] Verify "not mine" excluded from analytics
- [ ] Verify "not mine" excluded from forecast

### Unit Tests to Update/Add

- `SynthesisEngine` tests - ensure isNotMine filtered
- `InsightsEngine` tests - test includeNotMine parameter
- `BudgetCalculator` tests - test filter behavior

---

## Edge Cases & Decisions

### Q1: Can a transfer be "not mine"?

**Decision**: Yes, theoretically possible (someone transferred on behalf of someone else). Implement as independent flags.

### Q2: Can a shared expense also be "not mine"?

**Decision**: Yes - if I'm splitting an expense that my partner owes me. Both flags can coexist.

### Q3: What happens to existing expenses?

**Decision**: Default values apply (isNotMine=false, isSharedExpense=false, transferDirection=null). No migration of old data needed.

### Q4: How to display in list?

**Decision**: Use icons:
- 🔄 = Transfer
- 👤 = Not mine  
- 🤝 = Shared
- Combined: 🔄👤🤝

### Q5: Transfer with amount - income or expense?

**Decision**: 
- Transfers don't affect "total spent" calculations
- But TRANSFER (OUTGOING) could show as expense in some views
- Use separate "transfers" category in analytics

---

## Migration Strategy

```kotlin
// Example migration pseudocode
val MIGRATION_XX = object : Migration(dbVersion, dbVersion + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE expenses ADD COLUMN transferDirection TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN transferAccountName TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN isNotMine INTEGER DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN ownerName TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN isSharedExpense INTEGER DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN sharedWithName TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN mySharePercentage INTEGER")
        db.execSQL("ALTER TABLE expenses ADD COLUMN myShareAmount REAL")
    }
}
```

---

## Success Criteria

1. ✅ Users can mark expenses as "not mine" with owner name
2. ✅ Users can mark expenses as "shared" with share details
3. ✅ Users can track transfers (internal accounts + lent/borrowed)
4. ✅ "Not mine" expenses are excluded from all financial calculations
5. ✅ Shared expenses can be tracked but optionally included in totals
6. ✅ UI provides clear indication of these expense types
7. ✅ Filtering works correctly in transaction list

---

## Future Enhancements (Out of Scope)

- [ ] Settlement tracking - "Partner owes you €50"
- [ ] Recurring shared expenses
- [ ] Export shared expense reports
- [ ] Multi-currency for transfers
- [ ] Transfer reconciliation (mark as settled)
