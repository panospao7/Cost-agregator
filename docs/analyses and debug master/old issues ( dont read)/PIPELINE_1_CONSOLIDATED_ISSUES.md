# Pipeline 1 — Notification Capture: Consolidated Issue Registry

> **Last validated:** 2026-06-01 against HEAD (commits ca5972bf, 6b31d468, 31696516)  
> **Status:** 5 FIXED (original), 1 PARTIAL (original), 10 FIXED (NEW), 7 OPEN (NEW), 1 BLOCKED (NEW)  
> **Total open items:** 8 (7 OPEN + 1 PARTIAL from original P1-P1-07)
> **Update 2026-06-01:** Post-tracker commits reconciled. P1-PR2 (ca5972bf): NEW-P1-002/015 FIXED. P1-PR3 (6b31d468): NEW-P1-005/006/013 FIXED. P1-PR4 (6b31d468): NEW-P1-017 FIXED. P1-PR5 (31696516): NEW-P1-008 FIXED. P1-PR6 (6b31d468): NEW-P1-012/014 FIXED. Remaining: NEW-P1-003/004/007/010/011/016 OPEN, NEW-P1-009 BLOCKED by U-PR5.

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
| NEW-P1-008 | P2 | `processMutex` serializes ALL processing — bottleneck | NotificationProcessingPipeline.kt | ✅ FIXED (P1-PR5) |
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
| ✅ FIXED (original issues) | 5 |
| ✅ FIXED (NEW issues) | 10 |
| ⚠ PARTIAL (original issues) | 1 |
| 🔴 OPEN (NEW issues) | 6 |
| ⏳ BLOCKED by U-PR5 | 1 |
| **Total open work** | **8** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **P1-P1-07 remainder** — Wrap intake insert in `NonCancellable` or `@ApplicationScope` (P1-PR1)

### P2 (should fix)
2. **NEW-P1-007** — Warm-up race on cold start (P1-PR4 remainder)
3. **NEW-P1-010** — markProcessed outside transaction (P1-PR5 remainder)
4. **NEW-P1-009** — TOCTOU privacy settings race ⏳ **BLOCKED by U-PR5**
5. **NEW-P1-003** — Dead workTracker code (P1-PR6)
6. **NEW-P1-004** — Silently dropped events (P1-PR6)

### P3 (cleanup)
7. **NEW-P1-011** — Redundant SHA-256 (P1-PR6)
8. **NEW-P1-016** — Sensitive key exact match (P1-PR6)

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P1-001 (CancellationException) | U-PR1 — shared detekt rule + helper |
| NEW-P1-009 (privacy TOCTOU) | U-PR5 — RawStorageMode contract |
