# ExpenseTracker Bug Fixes - Manual Testing Checklist

**Date:** March 31, 2026  
**Version:** Post-bug-fixes verification  
**Tester:** [Your Name]

---

## Test Environment Setup

- [ ] Fresh app install (clear data or use new device)
- [ ] Or update existing install with new build
- [ ] Enable developer options / logging
- [ ] Test on Android API 28+ (Android 9+)

---

## Phase 1: Critical Stability Fixes (Priority: HIGH)

### Test 1: Widget Style Toggle
**Bug Fixed:** Force unwrap crash in WidgetStyleRepository
**File:** `domain/widget/service/WidgetStyleRepository.kt`

#### Test Steps:
1. [ ] Open app and add a home screen widget
2. [ ] Long press widget → tap to open configuration
3. [ ] Toggle widget style between "Modern" and "Retro" 5 times rapidly
4. [ ] Background the app while toggling
5. [ ] Kill app (swipe away) during toggle
6. [ ] Restart app and check widget displays correctly

#### Expected Results:
- [ ] No crash occurs during rapid toggling
- [ ] Widget displays correct style after each toggle
- [ ] Widget falls back to "Modern" if toggle fails silently
- [ ] App handles DataStore failures gracefully

#### Edge Cases:
- [ ] Toggle with no network (offline)
- [ ] Toggle on low memory device
- [ ] Toggle immediately after app launch

---

### Test 2: Recurring Expense with Empty Data
**Bug Fixed:** Crash when dates list is empty
**File:** `domain/logic/RecurringExpenseEngine.kt`

#### Test Steps:
1. [ ] Fresh install with no expenses
2. [ ] Navigate to "Recurring" tab
3. [ ] Add one expense, then delete it
4. [ ] Navigate away and back to Recurring tab
5. [ ] Add 2 expenses, verify recurring detection works
6. [ ] Delete one expense, check app doesn't crash

#### Expected Results:
- [ ] Empty state shows "No recurring patterns detected"
- [ ] No crash when no expenses exist
- [ ] No crash when expenses are deleted
- [ ] Recurring patterns appear when 3+ similar expenses exist

#### Edge Cases:
- [ ] Add expenses, delete all, add new ones immediately
- [ ] Expenses with same merchant but different amounts (>40% variance)
- [ ] Expenses older than 6 months

---

### Test 3: Notification Service Resilience
**Bug Fixed:** Crash on alarm scheduling failure
**File:** `service/NotificationCaptureService.kt`

#### Test Steps:
1. [ ] Enable notification monitoring in settings
2. [ ] Grant notification access permission
3. [ ] Kill app and restart
4. [ ] Toggle notification service on/off rapidly
5. [ ] Background the app for 5 minutes
6. [ ] Simulate notification from banking app
7. [ ] Revoke notification permission while service running
8. [ ] Grant permission back, restart service

#### Expected Results:
- [ ] Service starts foreground without crash
- [ ] Service handles permission changes gracefully
- [ ] Alarm scheduling failures are caught and logged
- [ ] Notifications continue to be captured after restart

#### Edge Cases:
- [ ] Start service on Android 14+ with restricted background
- [ ] Start service in Doze mode
- [ ] Rapid permission grant/revoke cycles

---

### Test 4: Review Approval Atomicity
**Bug Fixed:** Non-atomic transaction could leave orphaned data
**File:** `data/repository/ReviewQueueRepository.kt`

#### Test Steps:
1. [ ] Wait for notification to appear in "Pending Review"
2. [ ] Open the review item
3. [ ] Approve the transaction
4. [ ] Immediately approve the same transaction again (double-tap)
5. [ ] Check Expense list - transaction should appear once
6. [ ] Check Source Stats - should show correct counts
7. [ ] Verify receipt is linked if applicable

#### Expected Results:
- [ ] Transaction appears exactly once in expense list
- [ ] No duplicate expenses created
- [ ] Source stats reflect single approval
- [ ] Receipt properly linked
- [ ] Review marked as "APPROVED" not "PROCESSING"

#### Edge Cases:
- [ ] Approve during network issues
- [ ] Approve with very large amount (should trigger validation)
- [ ] Approve with null category
- [ ] Approve receipt-based review

---

## Phase 2: Concurrent Operations (Priority: HIGH)

### Test 5: Dashboard Analytics Resilience
**Bug Fixed:** One failed analytics component crashes entire dashboard
**File:** `domain/analytics/InsightsEngine.kt`

#### Test Steps:
1. [ ] Fresh install with no data
2. [ ] Open dashboard
3. [ ] Add one expense, refresh dashboard
4. [ ] Add 10+ expenses across different categories
5. [ ] Pull-to-refresh rapidly (5 times in 2 seconds)
6. [ ] Background app, add expense from notification, foreground app
7. [ ] Test with very old expenses (1+ year old)

#### Expected Results:
- [ ] Dashboard loads with partial data if some analytics fail
- [ ] Monthly comparison shows even with minimal data
- [ ] No crash on rapid refreshes
- [ ] All widgets populate correctly
- [ ] AI briefing loads independently of other widgets

#### Edge Cases:
- [ ] Dashboard with 1000+ expenses
- [ ] Dashboard with expenses spanning multiple years
- [ ] Dashboard after database restore

---

### Test 6: Transaction List Pagination Race Conditions
**Bug Fixed:** AtomicBoolean race condition in pagination
**File:** `ui/screens/transactions/TransactionsViewModel.kt`

#### Test Steps:
1. [ ] Navigate to "All" transactions tab (requires pagination)
2. [ ] Scroll down rapidly to trigger multiple "load more"
3. [ ] While loading, switch tabs rapidly (Today → Week → Month → All)
4. [ ] Pull-to-refresh while loading more
5. [ ] Search while paginating
6. [ ] Apply filters while loading
7. [ ] Rotate device while loading

#### Expected Results:
- [ ] No duplicate transactions appear
- [ ] Loading indicator shows correctly
- [ ] No crash on rapid tab switching
- [ ] Pagination stops when reaching end
- [ ] Search filters work correctly with pagination

#### Edge Cases:
- [ ] 10,000+ transactions
- [ ] Load more with empty search results
- [ ] Load more with active filters returning few results

---

## Phase 3: Data Integrity & Null Safety (Priority: MEDIUM)

### Test 7: Analytics with Uncategorized Expenses
**Bug Fixed:** Force unwrap on null category crashes Analytics
**File:** `ui/screens/analytics/AnalyticsViewModel.kt`

#### Test Steps:
1. [ ] Add expense without selecting category
2. [ ] Navigate to Analytics screen
3. [ ] Check "Budget vs Actual" section
4. [ ] Verify uncategorized expenses appear as "Uncategorized"
5. [ ] Add more uncategorized expenses
6. [ ] Change expense category to null via database (edge case)

#### Expected Results:
- [ ] Analytics screen loads without crash
- [ ] Uncategorized expenses shown as "Uncategorized"
- [ ] Charts display correctly with mixed categories
- [ ] No force unwrap crashes in logs

---

### Test 8: Database Backup Import/Export
**Bug Fixed:** Force unwrap on null validation/export results
**Files:** `DatabaseBackupRepositoryImpl.kt`, `DebugViewModel.kt`

#### Test Steps:
1. [ ] Navigate to Debug screen
2. [ ] Click "Export Database"
3. [ ] Verify success message with file path
4. [ ] Export with no transactions (fresh install)
5. [ ] Attempt to import corrupted file (create invalid JSON file)
6. [ ] Import valid backup file
7. [ ] Import empty backup file

#### Expected Results:
- [ ] Export succeeds with proper path displayed
- [ ] Import shows error for corrupted file (not crash)
- [ ] Import shows error for empty file
- [ ] Import shows error for non-existent file
- [ ] Success message for valid import

#### Edge Cases:
- [ ] Export while database is locked
- [ ] Import very large backup file
- [ ] Import file with schema version mismatch

---

### Test 9: Date Parsing Edge Cases
**Bug Fixed:** Silent date parsing failures
**File:** `domain/parser/GenericTransactionParser.kt`

#### Test Steps:
1. [ ] Add expenses with various date formats:
   - [ ] Standard format: "2024-03-15"
   - [ ] European format: "15/03/2024"
   - [ ] US format: "03/15/2024"
   - [ ] Text format: "March 15, 2024"
   - [ ] Invalid format: "not a date"
2. [ ] Check logs for "Failed to parse date" warnings
3. [ ] Verify expenses are created even with date parse failures

#### Expected Results:
- [ ] Valid dates parsed correctly
- [ ] Invalid dates logged with Timber.w()
- [ ] Expense created with fallback date (current time) on parse failure
- [ ] No silent failures

---

## Phase 4: Integration Testing (Priority: MEDIUM)

### Test 10: End-to-End Transaction Flow
**Tests:** Multiple components working together

#### Test Steps:
1. [ ] Receive notification from bank app
2. [ ] Verify notification captured in "Pending Review"
3. [ ] Approve the transaction
4. [ ] Verify appears in expense list with correct category
5. [ ] Check appears in analytics
6. [ ] Verify widget updates
7. [ ] Export database and verify transaction present

#### Expected Results:
- [ ] Full flow completes without crash
- [ ] Data consistent across all screens
- [ ] Analytics reflect new transaction
- [ ] Budget warnings trigger if applicable

---

### Test 11: Concurrent Operations Under Load
**Tests:** Stability under stress

#### Test Steps:
1. [ ] Rapidly add 50 expenses via notifications (use test data)
2. [ ] While adding, navigate between all tabs rapidly
3. [ ] Pull-to-refresh on all screens during load
4. [ ] Search while transactions being added
5. [ ] Filter and sort while background sync active

#### Expected Results:
- [ ] No ANR (Application Not Responding)
- [ ] No crashes
- [ ] UI remains responsive
- [ ] Data eventually consistent

---

## Regression Testing (Priority: HIGH)

### Test 12: Existing Features Still Work

#### Critical Paths:
- [ ] Add manual expense
- [ ] Edit expense
- [ ] Delete expense
- [ ] Add budget
- [ ] Edit budget
- [ ] View spending by category
- [ ] View spending by merchant
- [ ] Generate reports
- [ ] Export data
- [ ] Import data

#### UI/UX:
- [ ] Dark mode works
- [ ] RTL layout (if supported)
- [ ] Tablet layout
- [ ] Small screen devices

---

## Performance Testing (Priority: LOW)

### Test 13: Performance Baselines

#### Metrics to Check:
- [ ] App cold start time < 3 seconds
- [ ] Dashboard load time < 2 seconds
- [ ] Transaction list load (100 items) < 1 second
- [ ] Analytics calculation (1000 expenses) < 2 seconds
- [ ] Memory usage < 150MB during normal use

---

## Sign-off

### Tester Verification:
- [ ] All Phase 1 tests passed
- [ ] All Phase 2 tests passed
- [ ] All Phase 3 tests passed
- [ ] All Phase 4 tests passed
- [ ] Regression tests passed
- [ ] No crashes observed during testing
- [ ] Performance acceptable

### Known Issues:
- [ ] Document any issues found
- [ ] Mark severity (Critical/High/Medium/Low)
- [ ] Note if issue existed before fixes

### Approval:
**Tester Name:** _________________  
**Date:** _________________  
**Signature:** _________________

---

## Quick Reference: Bug Fixes Summary

| Bug | File | Test # | Status |
|-----|------|--------|--------|
| Widget toggle crash | WidgetStyleRepository.kt | Test 1 | [ ] |
| Recurring empty list | RecurringExpenseEngine.kt | Test 2 | [ ] |
| Service alarm crash | NotificationCaptureService.kt | Test 3 | [ ] |
| Non-atomic transaction | ReviewQueueRepository.kt | Test 4 | [ ] |
| Dashboard async crash | InsightsEngine.kt | Test 5 | [ ] |
| Pagination race | TransactionsViewModel.kt | Test 6 | [ ] |
| Category null crash | AnalyticsViewModel.kt | Test 7 | [ ] |
| Backup null crash | DatabaseBackupRepository.kt | Test 8 | [ ] |
| Silent date failure | GenericTransactionParser.kt | Test 9 | [ ] |
| Import/export null | DebugViewModel.kt | Test 8 | [ ] |

---

**Notes:**
- Mark [x] when test passes
- Add comments for any failures
- Re-test after any code changes
- Keep this document updated with new test cases
