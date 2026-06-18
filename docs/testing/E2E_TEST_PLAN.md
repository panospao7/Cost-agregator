# End-to-End Test Plan — Critical Operations

## Philosophy

These tests wire REAL production classes (not mocks) through the full pipeline path.
Only external network providers (Gemini AI, exchange rate APIs, geocoding) are faked.
The goal: prove that the app's most important user flows work from input to final DB state.

---

## E2E Test 1: Notification → Expense → Dashboard

**User story:** Greek bank sends push notification → app auto-creates expense → dashboard updates

**Pipeline:**
```
RawNotification
→ NotificationProcessingPipeline.process()
→ AppParserRegistry (Greek bank parser)
→ ConfidenceRouter (auto-accept)
→ TransactionLifecycleCoordinator.createExpense()
→ ExpenseDao.insertAtomic()
→ TransactionEvent written
→ MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() reflects new expense
```

**What to wire (real):**
- NotificationProcessingPipeline (all 24 deps except AI/network)
- AppParserRegistry with real Greek bank parser
- TransactionLifecycleCoordinator
- Real Room DB
- Real MerchantNormalizer (with empty merchant DB — first-time behavior)
- Real ConfidenceRouter
- Real DuplicateDetectionPolicy

**What to fake:**
- AiSettingsRepository → cloud AI disabled
- GenerateTransactionInsightUseCase → no-op
- ForegroundLocationProvider → null location
- DashboardFollowThroughEngine → no-op
- RecommendationRepository → no-op

**Assertions:**
- Expense created with correct amount, merchant, currency, date
- merchantKey generated correctly
- TransactionEvent CREATED written
- Dashboard total includes the new expense
- Duplicate notification → no second expense (dedup works end-to-end)
- Low-confidence notification → PendingReview created (not auto-accepted)

**Priority:** P0 — this is the #1 user flow

---

## E2E Test 2: Receipt Capture → OCR → Match → Link

**User story:** User photographs receipt → OCR extracts data → matches existing expense → links

**Pipeline:**
```
ScannedReceipt (OCR complete)
→ ReceiptTransactionMatcher.findBestMatch()
→ ReceiptLinkService.linkReceiptToExpense()
→ ReceiptExpenseLink created
→ ReceiptEvent written
→ Warranty/ReturnWindow side effects
→ Analytics still counts expense once
```

**What to wire (real):**
- ReceiptTransactionMatcher
- ReceiptLinkService
- MerchantNormalizer
- StringDistanceUtils
- Real Room DB with pre-seeded expense

**What to fake:**
- OCR provider (provide pre-parsed receipt data)

**Assertions:**
- Match score ≥ 0.95 → auto-match
- ReceiptExpenseLink persisted
- Receipt matchStatus = AUTO_MATCHED
- ReceiptEvent RECEIPT_LINKED_TO_EXPENSE written
- Analytics total unchanged (no double-count)
- Second link attempt rejected

**Priority:** P0 — receipt matching is a core differentiator

---

## E2E Test 3: Recurring Rule → Occurrence Generation → Payment Match

**User story:** Monthly Netflix rule → system generates occurrence → real payment arrives → auto-links

**Pipeline:**
```
ManualRecurringExpense (rule)
→ RecurringLifecycleCoordinator.generateOccurrences()
→ RecurringOccurrence PLANNED created
→ PlannedExpense created
→ Real expense arrives (via notification or manual)
→ RecurringLifecycleCoordinator.matchExpenseToOccurrence()
→ Occurrence claimed (PAID)
→ PlannedExpense fulfilled
→ Reminders suppressed
→ Dashboard counts once
```

**What to wire (real):**
- RecurringLifecycleCoordinator
- RecurringOccurrenceDao
- PlannedExpenseDao
- RecurringReminderDeliveryDao
- TransactionLifecycleCoordinator (for expense creation)
- Real Room DB

**What to fake:**
- TimeProvider (advance time to trigger generation)

**Assertions:**
- Occurrences generated for correct future dates
- PlannedExpenses created with correct amounts
- Expense matched to occurrence (status = PAID)
- PlannedExpense status = FULFILLED
- Reminders cancelled
- Dashboard total = actual payment amount (not doubled)
- Rule deactivation cancels all future occurrences

**Priority:** P0 — recurring bills are daily user interaction

---

## E2E Test 4: Budget Creation → Expense → Threshold Alert

**User story:** User sets 200€ grocery budget → spends 180€ → gets WARNING → spends 20€ more → EXCEEDED

**Pipeline:**
```
Budget created (200€, Food category, MONTHLY)
→ Expense created (180€ groceries)
→ BudgetMonitor.checkBudgets()
→ BudgetStatus computed (90% = WARNING)
→ Notification dispatched
→ Another expense (20€)
→ BudgetMonitor.checkBudgets()
→ BudgetStatus (100% = EXCEEDED)
```

**What to wire (real):**
- BudgetRepository
- BudgetCalculator
- BudgetMonitor
- MultiCurrencyRepository
- TransactionLifecycleCoordinator
- Real Room DB

**What to fake:**
- NotificationService (capture dispatched notifications)
- SharedExpenseBudgetOffsetEngine (no groups for simplicity)

**Assertions:**
- Budget persisted with correct period boundaries
- After 180€: status = WARNING, percentUsed ≈ 0.90
- After 200€: status = EXCEEDED, percentUsed ≈ 1.0
- Notification dispatched at WARNING threshold
- Notification dispatched at EXCEEDED threshold
- Cooldown prevents duplicate notifications

**Priority:** P1 — budget alerts are a key engagement feature

---

## E2E Test 5: Group Expense → Settlement → Budget Offset

**User story:** Alice pays 90€ dinner for 3 → system calculates shares → Bob settles → budget uses Alice's share

**Pipeline:**
```
GroupLifecycleCoordinator.createGroup()
→ GroupLifecycleCoordinator.addExpense(90€, paidBy=Alice, EQUAL split)
→ GroupBalanceCalculator computes balances
→ SettlementCalculator suggests transfers
→ GroupLifecycleCoordinator.recordSettlement(Bob→Alice, 30€)
→ SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend()
→ Budget uses Alice's 30€ share (not 90€ gross)
```

**What to wire (real):**
- GroupLifecycleCoordinator
- GroupBalanceCalculator
- SettlementCalculator
- SharedExpenseBudgetOffsetEngine
- Real Room DB

**What to fake:**
- BudgetMonitor (lazy, no-op)
- TransactionSideEffectDispatcher (no-op)
- CurrencyConverter (single currency, no conversion needed)

**Assertions:**
- Group created with 3 members
- Expense split equally (30€ each)
- Balances: Alice +60, Bob -30, Carol -30
- Settlement suggestion: Bob→Alice 30€, Carol→Alice 30€
- After Bob settles: Alice +30, Bob 0, Carol -30
- Budget effective spend = 30€ (Alice's share)
- Foreign currency expense rejected (single-currency policy)

**Priority:** P1 — group expenses are complex and error-prone

---

## E2E Test 6: Backup → Restore → Verify Integrity

**User story:** User backs up full DB → restores on fresh install → all data intact

**Pipeline:**
```
Seed full DB state (expenses, receipts, links, groups, budgets, rates)
→ DatabaseBackupRepository.createCostBackup()
→ Verify backup file created
→ Clear DB (simulate fresh install)
→ DatabaseBackupRepository.restoreCostBackup()
→ RestoreMaintenanceMode transitions
→ RestoreJournal tracks states
→ Verify all data matches original
```

**What to wire (real):**
- DatabaseBackupRepository (full implementation)
- RestoreMaintenanceMode
- RestoreJournal
- DatabaseWriteBarrier
- Real Room DB + real filesystem

**What to fake:**
- WorkManager (not available in unit tests)

**Assertions:**
- Backup file created (non-zero size)
- Restore journal: PREPARING → STAGED → SWAPPING → VERIFYING → COMPLETE
- After restore: expense count matches
- After restore: receipt links preserved
- After restore: exchange rates preserved
- After restore: group balances unchanged
- After restore: dashboard total identical to pre-backup
- Workers blocked during restore (write barrier active)

**Priority:** P0 — data loss is unacceptable

---

## E2E Test 7: Multi-Currency Expense → Rate Fetch → Dashboard

**User story:** User travels, spends in USD → app fetches rate → dashboard shows EUR total

**Pipeline:**
```
Expense created (50 USD)
→ CurrencyConverter.convert(50, USD, EUR)
→ ExchangeRateStore lookup
→ MoneyAggregateBuilder.fromBuckets()
→ Dashboard shows converted total
→ Rate becomes stale (24h+)
→ Dashboard shows partial warning
```

**What to wire (real):**
- MultiCurrencyRepository
- CurrencyConverter
- ExchangeRateStoreAdapter
- MoneyAggregateBuilder
- Real Room DB

**What to fake:**
- TimeProvider (advance past 24h to trigger staleness)
- CurrencySettingsRepository (fixed home currency)

**Assertions:**
- Fresh rate: displayTotal = 50 × rate, isPartial = false
- After 24h: isPartial = true, reason = RATE_STALE
- Missing rate: isPartial = true, reason = MISSING_RATE
- Source buckets always show original amounts
- Category totals sum to display total (for non-partial)

**Priority:** P1 — already partially covered by golden tests, but E2E adds time-advancement

---

## Implementation Strategy

### Phase 1 (highest value):
1. Notification → Expense → Dashboard (E2E #1)
2. Recurring Rule → Payment Match (E2E #3)
3. Backup → Restore → Verify (E2E #6)

### Phase 2:
4. Receipt → Match → Link (E2E #2)
5. Budget → Threshold Alert (E2E #4)

### Phase 3:
6. Group Expense → Settlement (E2E #5)
7. Multi-Currency staleness (E2E #7)

### Test Base Class

```kotlin
abstract class EndToEndTestBase : GoldenTestBase() {
    // Extends GoldenTestBase for Room DB + TimeProvider
    // Adds real production class wiring
    // Provides helper to advance time
    // Provides helper to capture "dispatched" notifications
}
```

### Key Difference from Golden Tests

| Aspect | Golden Tests | E2E Tests |
|--------|-------------|-----------|
| Dependencies | Mostly mocked | Mostly real |
| Scope | Single engine/contract | Full pipeline |
| Speed | Fast (~1s each) | Slower (~5-10s each) |
| What they catch | Numeric/logic regressions | Wiring/integration bugs |
| Maintenance | Low (stable contracts) | Higher (more deps to keep aligned) |

---

## Estimated Effort

- E2E #1 (Notification): ~2-3 hours (wiring NotificationProcessingPipeline is complex)
- E2E #2 (Receipt): ~1 hour
- E2E #3 (Recurring): ~1-2 hours
- E2E #4 (Budget): ~1 hour
- E2E #5 (Groups): ~1-2 hours
- E2E #6 (Backup): ~2-3 hours (filesystem operations)
- E2E #7 (Currency): ~30 min (mostly done in golden tests)

Total: ~10-12 hours for all 7
