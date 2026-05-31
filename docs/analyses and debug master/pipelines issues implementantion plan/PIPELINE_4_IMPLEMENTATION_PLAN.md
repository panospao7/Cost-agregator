# Pipeline 4 — Recurring / Bill Reminders: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 4 — Recurring / Bill Reminders  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 4 — Recurring / Bill Reminders
Verdict: YELLOW
Summary:
- 10 old issues fully FIXED, 1 DEFERRED (occurrenceKey collision — needs migration)
- 3 issues FIXED by universal (NEW-P4-001/007 via U-PR1, NEW-P4-004 via U-PR7)
- 7 pipeline-local issues remain OPEN (5 P2, 2 P3)
- Core lifecycle coordinator is solid; remaining work is edge-case hardening
- Key gaps: occurrence lookup race, notification/PendingIntent ID collisions, variable shadowing
- No P0/P1 issues remain open
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_4_CONSOLIDATED_ISSUES.md`, `new debugging session/pipeline4_static_debug_report_b6abe0a (1).md`

**Source files:** `RecurringRuleLifecycleCoordinator.kt`, `OccurrenceMaterializer.kt`, `BillReminderWorker.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 4 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | Fixes NEW-P4-001, NEW-P4-007 | No | ✅ Fixed |
| U-PR2 (TOCTOU) | No direct impact | No | N/A |
| U-PR3 (Money/Currency) | No direct impact | No | N/A |
| U-PR4 (Barrier) | Pipeline 4 already uses write barrier | No | ✅ Compatible |
| U-PR5 (Privacy) | No direct impact | No | N/A |
| U-PR6 (Worker Guard) | BillReminderWorker already uses guard | No | ✅ Compatible |
| U-PR7 (TimeProvider) | Fixes NEW-P4-004 — System.currentTimeMillis replaced | No | ✅ Fixed |
| U-PR8 (Side Effects) | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P4-P0-01 | ✅ FIXED | None | None |
| P4-P0-02 | ✅ FIXED | None | None |
| P4-P1-01 through P4-P1-04 | ✅ FIXED | None | None |
| P4-P1-05 | ⏭ DEFERRED | None | Needs schema migration design |
| P4-P1-06 through P4-P1-10 | ✅ FIXED | None | None |
| NEW-P4-001 | ✅ FIXED | U-PR1 | None |
| NEW-P4-002 | ✅ FIXED | None | Redundant scheduledAt removed (P4-PR1 landed) |
| NEW-P4-003 | ✅ Already mitigated | None | Atomic claimForExpense inside transaction |
| NEW-P4-004 | ✅ FIXED | U-PR7 | None |
| NEW-P4-005 | 🔴 OPEN | None | Use stable unique notification IDs |
| NEW-P4-006 | 🔴 OPEN | None | Use stable unique PendingIntent codes |
| NEW-P4-007 | ✅ FIXED | U-PR1 | None |
| NEW-P4-008 | 🔴 OPEN | None | Separate query from write side-effects |
| NEW-P4-009 | 🔴 OPEN | None | Sanitize JSON metadata |
| NEW-P4-010 | 🔴 OPEN | None | Return proper error for impossible state |

---

## 5. New Issues / Regressions

No regressions from universal fixes. All universal changes are compatible with Pipeline 4.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P4-003 | P2 | Race in linkExpenseToOccurrence — lookup outside transaction | Atomicity | P4-PR1 |
| NEW-P4-002 | P2 | Variable shadowing — scheduledAt computed twice | Correctness | P4-PR1 |
| NEW-P4-008 | P2 | reconcilePlannedVsActual has write side-effects in query method | Design | P4-PR1 |
| NEW-P4-005 | P2 | Notification ID collision risk (hashCode) | Android | P4-PR2 |
| NEW-P4-006 | P2 | PendingIntent request code collision | Android | P4-PR2 |
| NEW-P4-009 | P3 | JSON injection in lifecycle event metadata | Security | P4-PR2 |
| NEW-P4-010 | P3 | linkExpenseToOccurrenceDetailed returns Skipped for impossible state | Correctness | P4-PR2 |

---

## 7. PR Organization

### P4-PR1 — Core Correctness (Atomicity & Logic)

```
PR name: fix(p4): atomic occurrence lookup, fix variable shadowing, separate query/write
Goal: Fix race condition and logic errors in recurring lifecycle
Issues fixed: NEW-P4-003, NEW-P4-002, NEW-P4-008
Universal dependencies: None
Files likely touched:
  - RecurringRuleLifecycleCoordinator.kt
  - OccurrenceMaterializer.kt
Implementation steps:
  1. NEW-P4-003: Move occurrence lookup in linkExpenseToOccurrence inside database.withTransaction block; read occurrence + write link atomically
  2. NEW-P4-002: Remove duplicate scheduledAt computation in OccurrenceMaterializer; use single computed value
  3. NEW-P4-008: Split reconcilePlannedVsActual into: (a) pure query method returning reconciliation report, (b) separate apply method that performs writes; callers choose whether to apply
Tests:
  - concurrent_link_does_not_create_duplicate_occurrence_link
  - materializer_scheduledAt_computed_once
  - reconcile_query_has_no_write_side_effects
Risks: Low — targeted fixes within existing patterns
Acceptance criteria:
  - Occurrence lookup and link write are atomic
  - No variable shadowing in materializer
  - reconcilePlannedVsActual query path performs zero DB writes
```

### P4-PR2 — Notification Safety & Cleanup

```
PR name: fix(p4): stable notification/PendingIntent IDs, JSON sanitization, impossible state
Goal: Fix Android notification collisions and minor issues
Issues fixed: NEW-P4-005, NEW-P4-006, NEW-P4-009, NEW-P4-010
Universal dependencies: None
Files likely touched:
  - BillReminderWorker.kt
  - RecurringRuleLifecycleCoordinator.kt
Implementation steps:
  1. NEW-P4-005: Replace hashCode()-based notification ID with stable ID derived from deliveryId or occurrenceId (e.g. (deliveryId % Int.MAX_VALUE).toInt())
  2. NEW-P4-006: Use same stable ID strategy for PendingIntent request codes; ensure uniqueness per delivery
  3. NEW-P4-009: Sanitize user-provided strings in lifecycle event metadata JSON; use JSONObject.put() (auto-escapes) instead of string interpolation
  4. NEW-P4-010: Replace Skipped return for impossible state with explicit error/throw; log as bug indicator
Tests:
  - notification_ids_unique_across_deliveries
  - pending_intent_codes_unique_across_deliveries
  - metadata_json_escapes_special_characters
  - impossible_state_throws_not_skips
Risks: Low — notification ID change may dismiss existing notifications on upgrade (acceptable)
Acceptance criteria:
  - No notification ID collisions for concurrent reminders
  - PendingIntent codes deterministic and unique per delivery
  - No JSON injection possible via merchant/rule names
  - Impossible states produce clear error signals
```

---

## 8. Detailed Implementation Plan

### P4-PR1 Step-by-Step

1. **Open** `RecurringRuleLifecycleCoordinator.kt`
   - Find `linkExpenseToOccurrence()` — locate the occurrence lookup (likely `occurrenceDao.findByKey()`)
   - Wrap the lookup + link write in `database.withTransaction { }`
   - Ensure the link insert uses the occurrence loaded inside the transaction

2. **Open** `OccurrenceMaterializer.kt`
   - Find `scheduledAt` — identify where it's computed twice (variable shadowing)
   - Remove the redundant computation; keep the correct one

3. **In** `RecurringRuleLifecycleCoordinator.kt`
   - Find `reconcilePlannedVsActual()`
   - Extract query logic into `getReconciliationReport(): ReconciliationReport`
   - Keep write logic in `applyReconciliation(report: ReconciliationReport)`
   - Existing callers call both; new query-only callers call just the report method

### P4-PR2 Step-by-Step

1. **Open** `BillReminderWorker.kt`
   - Find notification ID generation (likely `something.hashCode()`)
   - Replace with: `val notificationId = (delivery.id % Int.MAX_VALUE).toInt()`
   - Same for PendingIntent request code

2. **Open** `RecurringRuleLifecycleCoordinator.kt`
   - Find lifecycle event metadata construction
   - Replace string interpolation with `JSONObject().apply { put("key", value) }.toString()`

3. **Find** `linkExpenseToOccurrenceDetailed` impossible state return
   - Replace `Skipped` with `throw IllegalStateException("...")` or a dedicated error result

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 4 Adapter/Follow-up |
|---|---|
| None required | All universal fixes already compatible with Pipeline 4 |

**Deferred item:** P4-P1-05 (occurrenceKey collision across source types) requires schema migration design. Not blocked by any universal PR — blocked by design decision on key format.

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 4 targeted tests
./gradlew :app:testDebugUnitTest --tests "*RecurringRule*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*OccurrenceMaterializer*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BillReminder*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*RecurringLifecycle*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P4-PR1: Occurrence lookup atomic; no variable shadowing; reconcile query is side-effect-free
- [ ] P4-PR2: Notification/PendingIntent IDs stable and unique; JSON sanitized; impossible states throw
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] P4-P1-05 (occurrenceKey collision) tracked as future migration — not blocking GREEN
- [ ] Pipeline 4 status upgraded to GREEN in master tracker
