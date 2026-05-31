# Pipeline 4 — Recurring/Bill Reminders: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 10 FIXED, 1 DEFERRED, 10 NEW open issues  
> **Total open items:** 11 (1 DEFERRED + 10 NEW)

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P4-P0-01 | P0 | Actual payment does not fulfill planned expense | ✅ FIXED | ✅ **FIXED** | `linkExpenseToOccurrence` works |
| P4-P0-02 | P0 | Paid occurrence does not suppress reminders | ✅ FIXED | ✅ **FIXED** | `suppressOpenDeliveries` works |
| P4-P1-01 | P1 | Reminder dispatch not exactly-once safe | ✅ FIXED | ✅ **FIXED** | Atomic `claimDelivery()` |
| P4-P1-02 | P1 | Recurring rule CRUD bypasses lifecycle/events | 📝 TODO ONLY | ✅ **FIXED** | `RecurringRuleLifecycleCoordinator` owns all CRUD |
| P4-P1-03 | P1 | Bill reminder worker disabled by default | ✅ FIXED | ✅ **FIXED** | Worker enabled in spec |
| P4-P1-04 | P1 | Reminder deliveries only when caller passes windows | 📝 TODO ONLY | ✅ **FIXED** | `DEFAULT_REMINDER_WINDOWS` applied |
| P4-P1-05 | P1 | `occurrenceKey` can collide across source types | ⏭ DEFERRED | ⏭ **DEFERRED** | Needs design/migration |
| P4-P1-06 | P1 | Expense→occurrence linking not globally guaranteed | 📝 TODO ONLY | ✅ **FIXED** | Side-effect planner dispatches recurring link |
| P4-P1-07 | P1 | Existing PAID occurrences downgraded by regeneration | 📝 TODO ONLY | ✅ **FIXED** | Materializer checks `terminalDbValues` |
| P4-P1-08 | P1 | Materializer updates status without lifecycle event | 📝 TODO ONLY | ✅ **FIXED** | Writes `OCCURRENCE_STATUS_CHANGED` |
| P4-P1-09 | P1 | Shared recurring write methods miss restore guard | ✅ FIXED | ✅ **FIXED** | Write barrier present |
| P4-P1-10 | P1 | Legacy `BillReminderManager.markBillPaid()` mixed behavior | 📝 TODO ONLY | ✅ **FIXED** | Legacy deprecated, coordinator owns |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P4-001 | P1 | CancellationException swallowed in bulk reconcile | RecurringRuleLifecycleCoordinator.kt | ✅ FIXED (U-PR1) |
| NEW-P4-002 | P2 | Variable shadowing — `scheduledAt` computed twice | OccurrenceMaterializer.kt | 🔴 OPEN |
| NEW-P4-003 | P2 | Race in `linkExpenseToOccurrence` — lookup outside transaction | RecurringRuleLifecycleCoordinator.kt | 🔴 OPEN |
| NEW-P4-004 | P2 | `BillReminderWorker` uses `System.currentTimeMillis` | BillReminderWorker.kt | ✅ FIXED (U-PR7) |
| NEW-P4-005 | P2 | Notification ID collision risk | BillReminderWorker.kt | 🔴 OPEN |
| NEW-P4-006 | P2 | PendingIntent request code collision | BillReminderWorker.kt | 🔴 OPEN |
| NEW-P4-007 | P2 | CancellationException swallowed in `regenerateReminderDeliveries` | RecurringRuleLifecycleCoordinator.kt | ✅ FIXED (U-PR1) |
| NEW-P4-008 | P2 | `reconcilePlannedVsActual` has write side-effects in query-like method | RecurringRuleLifecycleCoordinator.kt | 🔴 OPEN |
| NEW-P4-009 | P3 | JSON injection in lifecycle event metadata | RecurringRuleLifecycleCoordinator.kt | 🔴 OPEN |
| NEW-P4-010 | P3 | `linkExpenseToOccurrenceDetailed` returns Skipped for impossible state | RecurringRuleLifecycleCoordinator.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 10 |
| ⏭ DEFERRED (old issues) | 1 |
| 🔴 OPEN (new issues) | 10 |
| **Total open work** | **11** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P4-001** — CancellationException swallowed in bulk reconcile

### P2 (should fix)
2. **NEW-P4-003** — Race in linkExpenseToOccurrence — lookup outside transaction
3. **NEW-P4-007** — CancellationException swallowed in regenerateReminderDeliveries
4. **NEW-P4-002** — Variable shadowing — scheduledAt computed twice
5. **NEW-P4-004** — BillReminderWorker uses System.currentTimeMillis
6. **NEW-P4-005** — Notification ID collision risk
7. **NEW-P4-006** — PendingIntent request code collision
8. **NEW-P4-008** — reconcilePlannedVsActual write side-effects

### P3 (cleanup)
9. **NEW-P4-009** — JSON injection in lifecycle event metadata
10. **NEW-P4-010** — linkExpenseToOccurrenceDetailed returns Skipped for impossible state

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P4-001/007 (CancellationException) | U-PR1 — shared detekt rule + helper |
| P4-P1-05 (occurrenceKey collision) | Deferred — needs schema migration design |
