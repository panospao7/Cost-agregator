# Pipeline 1–5: NEW Issues Deep Audit Report

> **Generated:** 2026-05-31  
> **Method:** Critical source code audit — reading actual implementation, NOT re-verifying known issues  
> **Scope:** Issues NOT previously documented in any pipeline debug report  
> **Total NEW issues found:** 59 (1 P0, 13 P1, 25 P2, 20 P3)

---

## Executive Summary

| Pipeline | P0 | P1 | P2 | P3 | Total | Top Risk |
|----------|:--:|:--:|:--:|:--:|:-----:|----------|
| 1 — Notification | 0 | 1 | 10 | 6 | 17 | CancellationException swallowed; filter over-inclusive |
| 2 — Transaction | 0 | 3 | 7 | 6 | 16 | TOCTOU race in ALL update methods |
| 3 — Receipt/OCR | 0 | 3 | 3 | 2 | 8 | CancellationException swallowed (3 locations) |
| 4 — Recurring | 0 | 2 | 5 | 3 | 10 | Notification/PendingIntent ID collisions |
| 5 — Currency/Dashboard | 1 | 4 | 5 | 4 | 14 | Dead features (previousMonth, runway always 0) |
| **TOTAL** | **1** | **13** | **25** | **20** | **59** | |

### Cross-cutting patterns:
1. **CancellationException swallowing** — 7 locations across pipelines 1, 3, 4
2. **TOCTOU races** — beforeSnapshot captured outside transaction in ALL Pipeline 2 update methods
3. **Dead/non-functional features** — previousMonthAggregate always null, FinancialRunway always 0
4. **Mixed-currency arithmetic** — SynthesisEngine sums planned expenses without conversion

---

## P0 — Critical / Data Corruption

### NEW-P5-001: `previousMonthAggregate` is ALWAYS null — dead feature
- **File:** `ComputeDashboardWidgetsUseCase.kt`, `produceDashboardNormalizedInput()`
- **Evidence:** Filters `purchases` for previous month dates, but `purchases` only contains current-month expenses (from `DashboardDataProvider` query). Previous month data is never in the input list.
- **Impact:** Month-over-month comparison widgets are dead. SpendingPace.previousMonthTotal always null. Forecast confidence reduced unnecessarily.
- **Fix:** Expand data provider query to include previous month, or fetch separately.

---

## P1 — High Severity

### NEW-P1-001: CancellationException swallowed in `captureNotification` outer catch
- **File:** `NotificationCaptureService.kt`, ~line 578
- **Evidence:** `catch (e: Exception)` in `workTracker.launch` lambda catches CancellationException without rethrowing.
- **Impact:** `serviceJob.cancel()` won't reliably terminate in-flight work. Coroutines continue on dead scope.
- **Fix:** Add `if (e is CancellationException) throw e` before Timber.e.

### NEW-P2-001: TOCTOU race in `updateExpense()` — beforeSnapshot outside transaction
- **File:** `TransactionLifecycleCoordinator.kt`, ~line 885
- **Evidence:** `val existing = expenseDao.getById(expense.id)` runs BEFORE `database.withTransaction`. Concurrent update can modify row between read and write.
- **Impact:** Audit trail corruption (wrong beforeSnapshot), lost concurrent updates.
- **Fix:** Move getById + snapshot inside `database.withTransaction`.

### NEW-P2-002: Same TOCTOU race in ALL other update methods
- **File:** `TransactionLifecycleCoordinator.kt` — `updateMerchant()`, `updateType()`, `updateTransferDetails()`, `updateTypeAndTransferDetails()`, `updateBusinessExpensePatch()`, `updateOwnershipDbOnlyV2()`
- **Evidence:** All follow same pattern: read outside transaction → snapshot → withTransaction { update }.
- **Impact:** Same as NEW-P2-001 across all update paths.
- **Fix:** Consolidate read-modify-write into a helper that always reads inside transaction.

### NEW-P2-003: `deleteExpense(Expense)` uses stale caller-provided entity for snapshot
- **File:** `TransactionLifecycleCoordinator.kt`, ~line 1440
- **Evidence:** `val snapshot = expenseToSnapshot(expense)` uses caller's potentially stale object instead of re-reading from DB inside transaction.
- **Impact:** Incorrect audit trail for deletions via this overload.
- **Fix:** Re-read from DB inside transaction (like the ID-based overload does).

### NEW-P3-001: CancellationException swallowed in ReceiptSideEffectDispatcher
- **File:** `ReceiptSideEffectDispatcher.kt`, line 52
- **Evidence:** `catch (e: Exception) { Timber.e(...) }` — no CancellationException rethrow.
- **Impact:** Coroutine cancellation broken for callers. WorkManager workers won't respect `isStopped`.
- **Fix:** Add `if (e is CancellationException) throw e`.

### NEW-P3-002: CancellationException swallowed in BankStatementLifecycleProcessor per-item loop
- **File:** `BankStatementLifecycleProcessor.kt`, line 517
- **Evidence:** Per-transaction `catch (e: Exception)` continues loop without checking CancellationException.
- **Impact:** Cancelled batch continues processing on dead scope, potentially writing to closed DB.
- **Fix:** Add `if (e is CancellationException) throw e` as first line in catch.

### NEW-P3-003: CancellationException swallowed in ReceiptLinkService.unlinkReceiptFromExpense
- **File:** `ReceiptLinkService.kt`, line 442
- **Evidence:** `catch (e: Exception) { Result.failure(e) }` wraps entire method including transaction.
- **Impact:** Cancellation during unlink wrapped as failure instead of propagating.
- **Fix:** Add `if (e is CancellationException) throw e` before Result.failure.

### NEW-P4-001: CancellationException swallowed in bulk reconcile
- **File:** `RecurringLifecycleCoordinator.kt`, line 469
- **Evidence:** `catch (_: Exception) { failed++ }` catches ALL exceptions including CancellationException.
- **Impact:** Bulk reconciliation cannot be cancelled. Continues on dead scope.
- **Fix:** Add CancellationException check before `failed++`.

### NEW-P5-002: Division by zero risk in `projectedTotal` calculation
- **File:** `ComputeDashboardWidgetsUseCase.kt`, ~line 564
- **Evidence:** `projectedTotal = amount / daysElapsed * daysInMonth`. If `daysElapsed` is 0 (timezone edge), produces Infinity → crash in MoneyAggregate `require(isFinite())`.
- **Impact:** Potential crash on day 1 of month in edge-case timezones.
- **Fix:** Guard with `daysElapsed.coerceAtLeast(1)`.

### NEW-P5-003: Deposit filter includes "not mine" items
- **File:** `ComputeDashboardWidgetsUseCase.kt`, `produceDashboardNormalizedInput()`
- **Evidence:** `deposits` list does NOT filter `!it.isNotMine`, so shared-account deposits marked "not mine" inflate monthlyIncome.
- **Impact:** Inflated income figure → incorrect runway calculations.
- **Fix:** Add `&& !it.isNotMine` to deposits filter.

### NEW-P5-004: `TotalsAggregationEngine.getAverageForPeriodType(DAY)` uses wrong denominator
- **File:** `TotalsAggregationEngine.kt`, ~line 280
- **Evidence:** Divides total by calendar days, but daily totals only exist for days WITH expenses. Makes every spending day appear OVER_AVERAGE.
- **Impact:** Status indicators systematically biased — useless for users.
- **Fix:** Compute average over spending days only, or zero-fill all calendar days.

### NEW-P5-005: SynthesisEngine sums planned expenses across currencies without conversion
- **File:** `SynthesisEngine.kt`, ~lines 170-210
- **Evidence:** `committedPlannedByCurrency.values.sum()` — groups by currency then sums raw doubles. 100 EUR + 50 USD = 150.
- **Impact:** Incorrect forecast for multi-currency users. Wrong discretionary budget, risk levels, projected spending.
- **Fix:** Convert each currency group to home currency before summing.

### NEW-P5-011: FinancialRunway always shows 0 days remaining
- **File:** `ComputeDashboardWidgetsUseCase.kt`, ~line 585
- **Evidence:** `val totalRemaining = 0.0` is hardcoded. Runway calculation always returns 0.
- **Impact:** FinancialRunway widget is non-functional — always shows CRITICAL/NO_INCOME.
- **Fix:** Compute from budget limit - spent - committed, or hide widget.

---

## P2 — Medium Severity

### Pipeline 1

| ID | Title | File | Impact |
|----|-------|------|--------|
| NEW-P1-002 | `writeNotificationDedupeSourceLink` inside transaction performs I/O side effect | NotificationProcessingPipeline.kt | Potential transaction deadlock |
| NEW-P1-003 | `workTracker.acceptingNewWork` never set to false — dead code | NotificationCaptureService.kt | False sense of graceful shutdown |
| NEW-P1-004 | `emitOrderedNotificationEvents` silently drops events when launch returns null | NotificationCaptureService.kt | Lost diagnostic events |
| NEW-P1-005 | Filter blocks ALL "deposit" notifications unconditionally | NotificationFilter.kt | Users tracking deposits miss transactions |
| NEW-P1-006 | "failed" keyword deny is overly broad (matches merchant names) | NotificationFilter.kt | False negatives for legitimate expenses |
| NEW-P1-007 | Race between `captureGate.warmUp()` (async) and first notification | NotificationCaptureService.kt | First notification after cold boot may be lost |
| NEW-P1-008 | `processMutex` serializes ALL processing — single-threaded bottleneck | NotificationProcessingPipeline.kt | Latency under burst notifications |
| NEW-P1-009 | Double privacy settings fetch — TOCTOU race | NotificationCaptureService.kt | Privacy mode inconsistency |
| NEW-P1-010 | `processAndSave` marks processed OUTSIDE pipeline transaction | NotificationRepository.kt | Crash recovery can cause duplicate processing |
| NEW-P1-013 | Filter receives combinedBody as bigText — over-inclusive matching | NotificationCaptureService.kt | Deny keywords match too broadly |

### Pipeline 2

| ID | Title | File | Impact |
|----|-------|------|--------|
| NEW-P2-004 | Non-atomic duplicate check in updateExpense | TransactionLifecycleCoordinator.kt | Race can create duplicate dedupeKeys |
| NEW-P2-005 | DefaultExpenseCategoryAssignmentService bypasses lifecycle | DefaultExpenseCategoryAssignmentService.kt | No budget recheck, non-standard event type |
| NEW-P2-006 | NotificationRepository.deleteAll() bypasses audit trail | NotificationRepository.kt | All expenses deleted without events |
| NEW-P2-007 | Currency conversion failure leaves stale baseAmount | TransactionLifecycleCoordinator.kt | Reports show incorrect home-currency values |
| NEW-P2-008 | DAO exposes updateMerchantForMerchant that nulls dedupeKey | ExpenseDao.kt | Footgun for future callers |
| NEW-P2-009 | Planner hardcodes EXPENSE_CREATED trigger for update paths | TransactionSideEffectPlanner.kt | Idempotency key collision, wrong telemetry |
| NEW-P2-010 | Inconsistent event-write guard between bulkUpdateCategory overloads | TransactionLifecycleCoordinator.kt | Zero-effect events pollute audit log |

### Pipeline 3

| ID | Title | File | Impact |
|----|-------|------|--------|
| NEW-P3-004 | Double `attachReceipt` call for same run | BankStatementLifecycleProcessor.kt | Redundant DB write, potential constraint violation |
| NEW-P3-005 | Race in post-OCR duplicate path — receipt read after delete | ReceiptLifecycleCoordinator.kt | Force-unwrap on null existingReceiptId |
| NEW-P3-006 | Privacy leak — merchant/category logged in production | ReceiptLinkService.kt | Spending patterns reconstructable from logs |

### Pipeline 4

| ID | Title | File | Impact |
|----|-------|------|--------|
| NEW-P4-003 | Race in linkExpenseToOccurrence — lookup outside transaction | RecurringLifecycleCoordinator.kt | Concurrent expenses both select same occurrence |
| NEW-P4-004 | BillReminderWorker uses System.currentTimeMillis() not TimeProvider | BillReminderWorker.kt | Untestable quiet-hours, inconsistent time source |
| NEW-P4-005 | Notification ID collision — `delivery.id % Int.MAX_VALUE` | BillReminderWorker.kt | New notification replaces old undismissed one |
| NEW-P4-006 | PendingIntent request code collision between Snooze/Dismiss | BillReminderWorker.kt | Wrong action triggered on tap |
| NEW-P4-007 | CancellationException swallowed in regenerateReminderDeliveries (3 locations) | RecurringLifecycleCoordinator.kt | Transaction may commit with partial state |

### Pipeline 5

| ID | Title | File | Impact |
|----|-------|------|--------|
| NEW-P5-006 | `homeCurrency().first()` cold Flow subscription on every call | AnalyticsInputAssembler.kt | Performance degradation |
| NEW-P5-007 | NormalizedAnalyticsInput.homeCurrency defaults to "EUR" | NormalizedAnalyticsInput.kt | Silent incorrect currency |
| NEW-P5-008 | Category aggregates use ALL_TYPES but input is PURCHASE-only | ComputeDashboardWidgetsUseCase.kt | Percentages may not sum to 100% |
| NEW-P5-009 | MoneyAggregateBuilder silently drops counts when list sizes mismatch | MoneyAggregateBuilder.kt | Misleading warning messages |
| NEW-P5-010 | computeFromNormalized uses per-expense average not per-day | TotalsAggregationEngine.kt | Status indicators biased toward OVER_AVERAGE |

---

## P3 — Low Severity

### Pipeline 1

| ID | Title |
|----|-------|
| NEW-P1-011 | Redundant SHA-256 implementations |
| NEW-P1-012 | Unused `postTime` parameter in computeDedupeKey |
| NEW-P1-014 | Deduper `cleanupExpired` never called |
| NEW-P1-015 | Orphaned diagnostic events for rolled-back transactions |
| NEW-P1-016 | Sensitive key filtering uses exact match (misses camelCase variants) |
| NEW-P1-017 | Settings observer dies permanently on exception — privacy regression risk |

### Pipeline 2

| ID | Title |
|----|-------|
| NEW-P2-011 | updateLocation missing correlationId propagation |
| NEW-P2-012 | updateMerchant missing correlationId in event |
| NEW-P2-013 | updateType missing correlationId in event |
| NEW-P2-014 | ExpenseWriteStore.updateMerchant doesn't update merchantKey/dedupeKey |
| NEW-P2-015 | Bulk idempotency keys non-unique across time |
| NEW-P2-016 | Flow.first() could hang indefinitely for currency settings |

### Pipeline 3

| ID | Title |
|----|-------|
| NEW-P3-007 | deleteReceipt writes ASSET_DELETE_FAILED event for non-existent receipt |
| NEW-P3-008 | homeCurrency() inside withContext(ioDispatcher) may cause thread starvation |

### Pipeline 4

| ID | Title |
|----|-------|
| NEW-P4-002 | Variable shadowing — scheduledAt computed twice |
| NEW-P4-008 | reconcilePlannedVsActual has write side-effects in query-like method |
| NEW-P4-009 | JSON injection risk in lifecycle event metadata |
| NEW-P4-010 | linkExpenseToOccurrenceDetailed returns Skipped for impossible state |

### Pipeline 5

| ID | Title |
|----|-------|
| NEW-P5-012 | Stale-rate detection uses fixed 7-day threshold regardless of expense age |
| NEW-P5-013 | aggregateCurrencyTotalsToMoneyAggregate returns empty on unknown type |
| NEW-P5-014 | buildTrendFromNormalizedInput timezone edge case in day bucketing |

---

## Recommended Fix Priority

### Immediate (P0 + critical P1)
1. **NEW-P5-001** — previousMonthAggregate always null (dead feature, easy fix)
2. **NEW-P5-011** — FinancialRunway always 0 (dead feature, easy fix)
3. **NEW-P5-005** — SynthesisEngine mixed-currency sum (financial correctness)
4. **NEW-P2-001/002** — TOCTOU race in all update methods (data integrity)

### Next sprint (remaining P1)
5. **NEW-P1-001** — CancellationException in captureNotification
6. **NEW-P3-001/002/003** — CancellationException in receipt pipeline (3 locations)
7. **NEW-P4-001** — CancellationException in bulk reconcile
8. **NEW-P5-002** — Division by zero risk
9. **NEW-P5-003** — Deposit filter includes not-mine
10. **NEW-P5-004** — Wrong average denominator
11. **NEW-P2-003** — deleteExpense(Expense) stale snapshot

### Hardening (P2)
12. **NEW-P4-005/006** — Notification/PendingIntent ID collisions
13. **NEW-P2-005** — DefaultExpenseCategoryAssignmentService bypass
14. **NEW-P2-007** — Currency conversion failure leaves stale baseAmount
15. **NEW-P1-013** — Filter over-inclusive matching
16. **NEW-P1-010** — processAndSave marks processed outside transaction

---

## Cross-cutting Fix Strategies

### Strategy 1: CancellationException audit (7 locations)
Add a lint rule or detekt custom rule that flags `catch (e: Exception)` or `catch (_: Exception)` in suspend functions without a CancellationException rethrow check.

**Files to fix:**
- `NotificationCaptureService.kt` (1)
- `ReceiptSideEffectDispatcher.kt` (1)
- `BankStatementLifecycleProcessor.kt` (1)
- `ReceiptLinkService.kt` (1)
- `RecurringLifecycleCoordinator.kt` (3)

### Strategy 2: TOCTOU race elimination (8 methods)
Create a helper:
```kotlin
private suspend inline fun <T> readModifyWrite(
    expenseId: Long,
    crossinline modify: (Expense) -> T
): T = database.withTransaction {
    val existing = expenseDao.getById(expenseId) ?: throw ...
    modify(existing)
}
```
Apply to all update/delete methods in `TransactionLifecycleCoordinator`.

### Strategy 3: Dead feature activation (2 widgets)
- `previousMonthAggregate`: Expand `DashboardDataProvider` query range or add separate previous-month fetch
- `FinancialRunway`: Wire to actual budget remaining calculation

### Strategy 4: Mixed-currency arithmetic guard
Add a CI guardrail that flags `.values.sum()` on maps keyed by currency without prior normalization.
