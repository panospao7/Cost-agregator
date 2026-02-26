# Test Plan: Deposit Detection & Type Editing

**Date:** February 19, 2026  
**Purpose:** Test DEPOSIT parsing and transaction type editing features

---

## Part 1: Parser Testing (DEPOSIT Detection)

### Test Strategy
Send test notifications through each parser and verify the detected type.

### 1.1 SMS Parser Tests

**Test Notifications to Send:**

| # | Notification Text | Expected Type | Keywords |
|---|-----------------|--------------|----------|
| 1 | "NBG: Κατάθεση €500.00 from EMPLOYER" | DEPOSIT | κατάθεση |
| 2 | "NBG: Πίστωση €250.00 salary" | DEPOSIT | πίστωση |
| 3 | "NBG: Αγορά €12.50 at CAFE" | PURCHASE | αγορά |
| 4 | "Revolut: deposit €100.00 from JOHN" | DEPOSIT | deposit |
| 5 | "Revolut: received €500 salary" | DEPOSIT | received, salary |
| 6 | "NBG: Μισθοδοσία €1500.00" | DEPOSIT | μισθοδοσία |
| 7 | "NBG: Επιστροφή €25.00" | DEPOSIT | επιστροφή |
| 8 | "NBG: Payment €15.00 at SHOP" | PURCHASE | Payment |

**How to Test:**
1. Use Debug Screen → "Test Parser" or manually trigger notifications
2. Check Review Queue for the detected type

### 1.2 Greek Bank Parser Tests

**Test Notifications:**

| # | Notification Text | Expected Type |
|---|-----------------|--------------|
| 1 | "Αγορά 12,50 EUR στο CAFE" | PURCHASE |
| 2 | "Πληρωμή €15.00 σε SUPERMARKET" | PURCHASE |
| 3 | "Κατάθεση €500 από EMPLOYER" | DEPOSIT |
| 4 | "Πίστωση €1000 μισθός" | DEPOSIT |

### 1.3 Google Wallet Parser Tests

| # | Notification Text | Expected Type |
|---|-----------------|--------------|
| 1 | "You spent €12.50 at CAFE" | PURCHASE |
| 2 | "You received €50.00 from JOHN" | DEPOSIT |
| 3 | "Payment of €20.00 completed" | PURCHASE |
| 4 | "€100 credited to your account" | DEPOSIT |

### 1.4 Generic Parser Tests

| # | Notification Text | Expected Type |
|---|-----------------|--------------|
| 1 | "You paid €15.00 at SHOP" | PURCHASE |
| 2 | "Payment of €25.00 to MERCHANT" | PURCHASE |
| 3 | "deposit €500 received" | DEPOSIT |
| 4 | "credited €100 salary" | DEPOSIT |

### Edge Cases

| # | Scenario | Expected Behavior |
|---|----------|------------------|
| E1 | Both "payment" AND "deposit" in same notification | DEPOSIT (deposit checked first) |
| E2 | No keywords (ambiguous) | No match / NULL |
| E3 | Amount only, no keywords | No match / NULL |
| E4 | "payment" but negative amount pattern | PURCHASE |
| E5 | Greeklish mix: "katathesi €100" | DEPOSIT (lowercase works) |
| E6 | Large amount €50000+ | Filtered out (too large) |
| E7 | Very small amount €0.01 | Filtered out (too small) |

---

## Part 2: Review Screen - Type Editing

### Test Flow

1. **Create a test notification** that goes to Review Queue
2. **Open Review Screen**
3. **Tap Edit (pencil icon)** on a pending item
4. **Verify:**
   - Transaction type selector is visible with 4 options
   - Current type is pre-selected
   - Can change to different type
5. **Tap Confirm**
6. **Verify in Transactions:**
   - Transaction appears with new type
   - Amount shows + or - based on type

### Test Cases

| # | Scenario | Expected Result |
|---|----------|----------------|
| R1 | Change PURCHASE → DEPOSIT | Amount shows as + (green), icon 💰 |
| R2 | Change DEPOSIT → PURCHASE | Amount shows as - (red), icon 💸 |
| R3 | No change (keep same type) | Normal save |
| R4 | Change type + amount + category | All changes applied |

### Verification Steps
1. Go to Review Screen
2. Find pending notification
3. Tap Edit button (✏️)
4. In dialog, verify type selector shows: 💸 PURCHASE, 💰 DEPOSIT, 🏧 WITHDRAWAL, 🔄 TRANSFER
5. Tap different type
6. Confirm
7. Go to Transactions - verify type changed

---

## Part 3: Transactions Screen - Type Editing

### Test Flow

1. **Open Transactions Screen**
2. **Find any transaction**
3. **Tap the type icon** (💸/💰/🏧/🔄) in the action buttons row
4. **Verify:**
   - Dialog opens with all type options
   - Current type is pre-selected
   - Descriptions show for each type
5. **Select different type**
6. **Tap Update Type**
7. **Verify:**
   - Transaction updates immediately
   - Icon changes in the list
   - Success message shows

### Test Cases

| # | Scenario | Expected Result |
|---|----------|----------------|
| T1 | Tap 💸 on a PURCHASE, change to DEPOSIT | Icon changes to 💰, amount color changes |
| T2 | Tap 💰 on a DEPOSIT, change to WITHDRAWAL | Icon changes to 🏧 |
| T3 | Tap current type (no change), tap Update | Should be disabled or show same |
| T4 | Change type on transaction with category | Category preserved |
| T5 | Change type on recurring transaction | Recurring preserved |

### Visual Checkpoints

**Before:**
```
💸 CAFE          Category    €12.50    [↻] [🗑]
```

**After changing to DEPOSIT:**
```
💰 CAFE          Category   +€12.50    [↻] [🗑]
```

---

## Part 4: Edge Cases & Error Handling

### Parser Edge Cases

| # | Edge Case | Expected Behavior |
|---|-----------|-------------------|
| EC1 | Duplicate notification | Should detect as duplicate, not create new |
| EC2 | Very long merchant name | Should truncate gracefully |
| EC3 | Unicode in merchant (Greek) | Should handle correctly |
| EC4 | Amount with commas (€12,50) | Should parse correctly |
| EC5 | Amount with dots (€12.50) | Should parse correctly |
| EC6 | Currency symbol before amount (€12) | Should parse correctly |
| EC7 | Currency symbol after amount (12€) | Should parse correctly |
| EC8 | No space between amount and currency | Should parse correctly |

### UI Edge Cases

| # | Edge Case | Expected Behavior |
|---|-----------|-------------------|
| EU1 | Empty merchant name | Should show "Unknown" |
| EU2 | Very long merchant name | Should truncate with ellipsis |
| EU3 | Rapid tap on type button | Should not open multiple dialogs |
| EU4 | Change type, immediately delete | Both should work correctly |
| EU5 | Network offline | Should work (local DB) |

---

## Part 5: Database Verification

### Check UserCorrection Table

After editing a type, verify the database:

```sql
SELECT * FROM user_corrections 
WHERE correctedType IS NOT NULL 
ORDER BY createdAt DESC;
```

**Should show:**
- `originalType`: "PURCHASE" (or whatever was suggested)
- `correctedType`: "DEPOSIT" (or whatever user changed to)

### Check Expense Table

```sql
SELECT id, merchant, amount, transactionType, date 
FROM expenses 
ORDER BY date DESC 
LIMIT 10;
```

**Should reflect the type change.**

---

## Part 6: Test Execution Checklist

### Pre-Test Setup
- [ ] Clear app cache (optional)
- [ ] Ensure debug mode enabled
- [ ] Note current transaction count

### Parser Tests
- [ ] Test 8+ notification scenarios
- [ ] Verify edge cases
- [ ] Check Review Queue for results

### Review Screen Tests
- [ ] Verify type selector visible
- [ ] Change type in Review
- [ ] Verify in Transactions list

### Transactions Screen Tests
- [ ] Find existing transaction
- [ ] Change type via icon
- [ ] Verify change persisted
- [ ] Check success message

### Post-Test
- [ ] Verify database records
- [ ] Check for any crashes
- [ ] Note any unexpected behavior

---

## Expected Outcomes

### Parser
- DEPOSIT detection should work for Greek keywords (κατάθεση, πίστωση, etc.)
- DEPOSIT detection should work for English keywords (deposit, received, salary, etc.)
- Fallback to PURCHASE when ambiguous

### Type Editing
- Both screens allow changing transaction type
- Changes persist to database
- Visual feedback (icons, colors) updates immediately
- UserCorrection tracks changes for ML training

---

*End of Test Plan*
