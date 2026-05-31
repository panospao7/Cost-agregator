# Pipeline 1 — Notification Capture: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 5 FIXED, 1 PARTIAL, 17 NEW open issues  
> **Total open items:** 18
> **Update 2026-05-31:** NEW-P1-002 and NEW-P1-015 FIXED by P1-PR2 (deferred diagnostic emission, no IllegalStateException in transaction)

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P1-P1-01 | P1 | Processing outcomes flattened to `Success` | ✅ FIXED | ✅ **FIXED** | Sealed `NotificationPipelineOutcome` confirmed |
| P1-P1-02 | P1 | No durable diagnostic/drop-reason ledger | ⚠ PARTIAL | ✅ **FIXED** | All drop paths emit via `NotificationDiagnosticEmitter`; service-level + pipeline-level covered |
| P1-P1-03 | P1 | Extraction misses `textLines` and `messages` | ✅ FIXED | ✅ **FIXED** | MessagingStyle extraction confirmed |
| P1-P1-05 | P1 | Privacy gate runs after text extraction | 📝 TODO | ✅ **FIXED** | `captureGate` with cached privacy decision; fail-closed until settings emit |
| P1-P1-06 | P1 | Restore guard in service but not pipeline | ✅ FIXED | ✅ **FIXED** | `writeBarrier.checkWritesAllowed()` in pipeline + repository |
| P1-P1-07 | P1 | Service shutdown loses accepted notifications | 📝 TODO | ⚠ **PARTIAL** | `NotificationIntakeCoordinator` + encrypted transient payload + worker exist. DO_NOT_STORE → synchronous. REDACTED/METADATA → encrypted transient. **Remaining gap:** service-scope cancellation window before intake insert. |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P1-001 | P1 | CancellationException swallowed in `captureNotification` outer catch | NotificationCaptureService.kt ~line 578 | ✅ FIXED (U-PR1) |
| NEW-P1-002 | P1 | `writeNotificationDedupeSourceLink` inside transaction performs I/O side effect | NotificationProcessingPipeline.kt | ✅ FIXED (P1-PR2) |
| NEW-P1-003 | P2 | `workTracker.acceptingNewWork` never set to false — dead code | NotificationCaptureService.kt | 🔴 OPEN |
| NEW-P1-004 | P2 | `emitOrderedNotificationEvents` silently drops events when launch returns null | NotificationCaptureService.kt | 🔴 OPEN |
| NEW-P1-005 | P2 | Filter blocks ALL "deposit" notifications unconditionally | NotificationFilter.kt | ✅ FIXED (P1-PR3) |
| NEW-P1-006 | P2 | "failed" keyword deny is overly broad (matches merchant names) | NotificationFilter.kt | ✅ FIXED (P1-PR3) |
| NEW-P1-007 | P2 | Race between `captureGate.warmUp()` (async) and first notification | NotificationCaptureService.kt | 🔴 OPEN |
| NEW-P1-008 | P2 | `processMutex` serializes ALL processing — bottleneck | NotificationProcessingPipeline.kt | 🔴 OPEN |
| NEW-P1-009 | P2 | Double privacy settings fetch — TOCTOU race | NotificationCaptureService.kt | 🔴 OPEN |
| NEW-P1-010 | P2 | `processAndSave` marks processed OUTSIDE pipeline transaction | NotificationRepository.kt | 🔴 OPEN |
| NEW-P1-011 | P3 | Redundant SHA-256 implementations | NotificationCaptureService.kt | 🔴 OPEN |
| NEW-P1-012 | P3 | Unused `postTime` parameter in computeDedupeKey | NotificationCaptureService.kt | ✅ FIXED (P1-PR6) |
| NEW-P1-013 | P2 | Filter receives combinedBody as bigText — over-inclusive matching | NotificationCaptureService.kt | ✅ FIXED (P1-PR3) |
| NEW-P1-014 | P3 | Deduper `cleanupExpired` never called | NotificationCaptureDeduper.kt | ✅ FIXED (P1-PR6) |
| NEW-P1-015 | P1 | `IllegalStateException` in transaction creates orphaned diagnostic | NotificationProcessingPipeline.kt | ✅ FIXED (P1-PR2) |
| NEW-P1-016 | P3 | Sensitive key filtering uses exact match (misses camelCase) | NotificationCaptureService.kt | 🔴 OPEN |
| NEW-P1-017 | P2 | Settings observer dies permanently on exception — privacy regression risk | NotificationCaptureGate.kt | ✅ FIXED (P1-PR4) |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 5 |
| ⚠ PARTIAL (old issues) | 1 |
| 🔴 OPEN (new issues) | 17 |
| **Total open work** | **18** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P1-001** — CancellationException swallowed (universal fix U-PR1)
2. **P1-P1-07 remainder** — Wrap intake insert in `NonCancellable` or `@ApplicationScope`
3. **NEW-P1-002** — Source-link I/O inside transaction (potential deadlock)
4. **NEW-P1-015** — Orphaned diagnostic for rolled-back transactions

### P2 (should fix)
5. **NEW-P1-013** — combinedBody passed as bigText to filter (over-inclusive)
6. **NEW-P1-005** — Filter blocks all deposits unconditionally
7. **NEW-P1-006** — "failed" keyword too broad
8. **NEW-P1-007** — Warm-up race on cold start
9. **NEW-P1-008** — processMutex bottleneck
10. **NEW-P1-009** — TOCTOU privacy settings race
11. **NEW-P1-010** — markProcessed outside transaction
12. **NEW-P1-017** — Settings observer dies permanently
13. **NEW-P1-003** — Dead workTracker code
14. **NEW-P1-004** — Silently dropped events

### P3 (cleanup)
15. **NEW-P1-011** — Redundant SHA-256
16. **NEW-P1-012** — Unused postTime parameter
17. **NEW-P1-014** — Deduper cleanup never called
18. **NEW-P1-016** — Sensitive key exact match

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P1-001 (CancellationException) | U-PR1 — shared detekt rule + helper |
| NEW-P1-009 (privacy TOCTOU) | U-PR5 — RawStorageMode contract |
