# Pipeline 1 recheck — commit `e781c226862234ed412914884e98d22165a41a95`

Mode: static GitHub review only; I did **not** run Gradle/tests.

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- Architecture doc: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/docs/architecture/ARCHITECTURE.md
- Master tracker: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationRepository.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

## High-level verdict

Pipeline 1 is **better than the previous `b6abe0a` report**, especially around:
- service-level diagnostic events,
- pre-extraction blocked-package check,
- pending-review raw-text sanitization,
- restore/write barrier coverage,
- source-link/provenance work.

But it is **not clean yet**.

Most important remaining bugs:

1. **Shutdown/process death can still lose notifications.**
2. **Repository still flattens pipeline outcomes to `Unit`.**
3. **Messaging-style extraction is still probably broken.**
4. **In-memory dedupe still removes keys after success and is still `sbn.key`-coarse.**
5. **Full `PrivacyGate` still runs after extraction.**
6. **Currency fallback remains EUR/USD/GBP-heavy.**
7. **Batch/public repository paths can still bypass new storage sanitization.**

Also: commit `e781c22` itself mostly changes side-effect cancellation wrappers, not Pipeline 1 directly.

---

# Tracker reconciliation

The master tracker is now stale for Pipeline 1.

| ID | Tracker says | My current status | Reason |
|---|---:|---:|---|
| P1-P1-01 | fixed | **Partial** | Pipeline returns `NotificationPipelineOutcome`, but `NotificationRepository.processAndSave()` still returns `Unit`; service cannot act on outcome. |
| P1-P1-02 | partial | **Mostly fixed / partial** | Service now writes `RECEIVED` and terminal events for many early exits; pipeline writes terminal outcomes. Still async/cancellable; restore-safe diagnostic sink use is not obvious. |
| P1-P1-03 | fixed | **Partial** | `textLines` fixed. `EXTRA_MESSAGES` extraction still casts to `CharSequence`; real MessagingStyle messages are parcelables/bundles, so likely missed. |
| P1-P1-05 | TODO | **Partial** | Fast settings gate and blocked-package cache now run before extraction, but full `PrivacyGate.check()` still runs after extraction/filter. |
| P1-P1-06 | fixed | **Fixed for writes** | Service, repository, and pipeline have restore/write checks. Diagnostics during restore still need verification. |
| P1-P1-07 | TODO | **Open** | `onDestroy()` still cancels `serviceJob`; comments say durable intake table is still planned. |

---

# Original issues

## P1-P1-01 — Outcome flattening

**Status: Partial, not fixed.**

Good:
- `NotificationProcessingPipeline.process()` returns sealed `NotificationPipelineOutcome`.

Still bad:
- `NotificationRepository.processAndSave(...)` returns `Unit`.
- It logs outcome internally but does not expose it.
- `NotificationCaptureService.processNotification()` calls repository and then logs success even if the pipeline returned an `Error` outcome instead of throwing.
- Stale TODO still exists in `NotificationProcessingPipeline`.

Fix:
```kotlin
suspend fun processAndSave(...): NotificationPipelineOutcome
suspend fun processAndSaveAll(...): List<NotificationPipelineOutcome>
```

Then service can:
- keep/remove dedupe keys correctly,
- avoid false “processed” logging,
- emit final service-level outcome based on truth.

---

## P1-P1-02 — Durable diagnostic/drop ledger

**Status: Mostly fixed / partial.**

Good:
- `onNotificationPosted()` builds a `RECEIVED` event.
- Restore drop, shutdown drop, privacy drop, blocked-package drop, duplicate, filter reject, privacy-gate reject, cancellation, and repository failures now emit diagnostic events.
- Pipeline emits terminal diagnostic events.
- Correlation IDs are propagated into pipeline events.

Remaining caveats:
- Diagnostic emission is launched asynchronously on `serviceScope`; if the service is destroyed immediately, events can still be lost.
- `MaintenanceSafeDiagnosticSink` is injected into the service but not visibly used in the shown service code.
- Restore-mode diagnostic durability needs verification: if the diagnostic writer itself uses normal DB writes, restore-blocked events may not persist.
- This is still not a durable notification intake ledger.

Verdict:
- Much better observability.
- Not equivalent to a guaranteed durable receive/drop ledger.

---

## P1-P1-03 — `textLines` and `messages`

**Status: Partial.**

Good:
- `EXTRA_TEXT_LINES` is now extracted.
- `combinedBody` includes top-level fields + textLines + messages.

Still bad:
- `EXTRA_MESSAGES` extraction is still probably wrong.
- API 33 path asks for `ParcelableArrayList(..., CharSequence::class.java)`.
- Legacy path casts items to `CharSequence`.
- Real Android `Notification.MessagingStyle.Message` payloads are not plain `CharSequence`.

Fix:
Use Android MessagingStyle APIs / bundle conversion:
```kotlin
Notification.MessagingStyle.Message.getMessagesFromBundleArray(...)
```

Tests needed:
- amount only in `EXTRA_TEXT_LINES`,
- amount only in `EXTRA_MESSAGES`,
- message bundle payload,
- duplicate message text deduped.

---

## P1-P1-05 — Privacy gate before extraction

**Status: Partial.**

Good:
- `capturePrivacyDenied` checked before extras extraction.
- blocked-package cache checked before extras extraction.
- diagnostics emitted for fast privacy drops.
- cache fails closed.

Still bad:
- fast gate only tracks `settings.notificationCaptureEnabled`.
- full `PrivacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)` still happens after:
  - extras access,
  - text extraction,
  - filter,
  - dedupe insertion.
- startup fail-closed cache can drop valid notifications before first settings emission.
- service proceeds on “else” privacy decisions as inconclusive/allowed.

Fix:
Create real `NotificationCaptureGate` backed by a hot cached full `PrivacyDecision`, initialized synchronously on service start.

---

## P1-P1-06 — Restore/write barrier

**Status: Fixed for write safety.**

Evidence:
- service checks `restoreMaintenanceMode.isWritesAllowed()`.
- pipeline calls `writeBarrier.checkWritesAllowed(...)`.
- repository save/delete/reset paths mostly call `writeBarrier`.

Remaining caveats:
- `blockPackage()` / `unblockPackage()` in `NotificationRepository` write directly without visible barrier.
- `restoreSourceStatsSnapshot()` writes without visible barrier.
- restore-drop diagnostics need verification.

---

## P1-P1-07 — Shutdown/process death loss

**Status: Open.**

`onDestroy()` now explicitly cancels `serviceJob`; comments say durable intake is still planned.

So the core failure remains:

1. Listener accepts notification.
2. Work is async.
3. Service/process dies.
4. No durable intake row guaranteed.
5. Android may not repost the notification.

Fix still needed:
- durable `NotificationIntake` table,
- status machine,
- worker resume,
- stale `PROCESSING` recovery.

---

# Previously found new issues

## P1-NEW-01 — Raw-storage bypass via `PendingReview`

**Status: Mostly fixed / partial.**

Good:
- Pipeline now uses `sanitizePendingReviewText(...)`.
- Parser-failed oversized/signal pending reviews use sanitized title/text.
- `storageNotification` is sanitized before raw notification persistence.

Remaining gaps:
- Public `processAndSave(notification)` still passes raw as both processing and storage.
- `processBatch(notifications)` uses raw notifications with no visible raw-storage policy.
- Auto-accept transaction-event notification payload sanitization was not fully verified from the visible code.
- `buildExtrasJson()` is still called before checking storage mode, although non-raw modes later null/redact persisted extras.

Verdict:
- The specific pending-review leak is mostly addressed.
- The privacy contract is not fully enforced across every entrypoint.

---

## P1-NEW-02 — In-memory dedupe removed after successful processing

**Status: Open.**

Code still removes `processedNotifications[coarseDedupeKey]` in `finally`.

Effect:
- dedupe only protects in-flight work.
- immediate repeated callbacks after success can re-enter processing.

Needs repository outcome return before fixing cleanly.

---

## P1-NEW-03 — Coarse `sbn.key` dedupe can drop distinct transactions

**Status: Open.**

Still:
```kotlin
val coarseDedupeKey = notificationKey
```

This is before content extraction. If a bank reuses the same status-bar key for two different transactions inside 5 seconds, second one can be dropped.

Fix:
- move dedupe after allowed extraction,
- use package + key + postTime + content fingerprint,
- keep DB-level canonical dedupe too.

---

## P1-NEW-04 — Full `PrivacyGate` denial after extraction

**Status: Still open / partial.**

Fast settings gate helps, but full `PrivacyGate` is still checked after extraction.

---

## P1-NEW-05 — Blocked packages checked too late

**Status: Fixed for privacy.**

Now blocked-package cache is checked before extras extraction.

Caveat:
- fail-closed until cache first emission can drop valid notifications after service start.

---

## P1-NEW-06 — Location privacy issue

**Status: Needs verification / partial.**

The pipeline still injects `ForegroundLocationProvider`. The architecture doc suggests location providers now fail closed, but the notification pipeline itself still appears to depend on location context.

Needed:
- static/unit test proving notification pipeline cannot bypass location privacy,
- diagnostic when location is denied/unavailable,
- or explicit notification-pipeline gate before location fetch.

---

## P1-NEW-07 — AI fallback provenance incomplete/inefficient

**Status: Open.**

Still present:
- `parseWithAiFallback(...)`,
- then second deterministic `parse(...)` only to infer parser source.

The TODO remains.

Fix:
```kotlin
data class ParseOutcome(
  val parsed: ParsedTransaction?,
  val parserSource: ParserSource,
  val aiProvider: String?,
  val confidence: Float?,
  val failureReason: String?
)
```

---

## P1-NEW-08 — Currency fallback narrow

**Status: Open.**

Fallback regexes still focus on:
- EUR,
- USD,
- GBP.

Default remains EUR for ambiguous cases.

Fix:
Use one shared app-wide money detector, not notification-local regexes.

---

# Extra current gaps noticed

## 1. Block/unblock package writes lack barrier

`NotificationRepository.blockPackage()` and `unblockPackage()` directly call DAO methods without visible `DatabaseWriteBarrier`.

Status: **new small restore-safety gap**.

## 2. Batch processing can bypass storage sanitization

`processBatch(notifications)` calls `processInternal(notification, initializeClassifier = false)` with raw notification as storage notification.

Status: **privacy partial**.

## 3. Startup fail-closed caches can cause false drops

Both privacy and blocked-package caches fail closed before first emission. This is privacy-safe but can lose valid notifications after service start.

Status: **capture reliability issue**.

---

# Recommended next order

1. Make `NotificationRepository.processAndSave()` return `NotificationPipelineOutcome`.
2. Use outcome in service for truthful final diagnostics and dedupe retention.
3. Fix `EXTRA_MESSAGES` extraction with real MessagingStyle handling.
4. Change in-memory dedupe to content-aware and keep success keys until TTL.
5. Implement durable notification intake queue.
6. Replace fast setting boolean with full cached `NotificationCaptureGate`.
7. Close privacy entrypoint gaps: batch path, public raw overload, transaction-event payload tests.
8. Replace fallback EUR/USD/GBP regexes with shared money parser.
9. Add write barriers to block/unblock and snapshot restore paths.
10. Verify/gate location provider use from notification processing.

## Bottom line

You have fixed a lot of the global/privacy/diagnostic foundation, but Pipeline 1 should **not** be marked clean yet.

The master tracker should be updated:
- P1-P1-01: **Partial**
- P1-P1-02: **Mostly fixed / partial**
- P1-P1-03: **Partial**
- P1-P1-05: **Partial**
- P1-P1-06: **Fixed with caveats**
- P1-P1-07: **Open**