# Pipeline 1 open issues + PR plan

Commit reviewed: `e781c226862234ed412914884e98d22165a41a95`  
Mode: static GitHub/code review only; no Gradle/device run.

Sources used:
- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Old Pipeline 1 report: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/docs/analyses%20and%20debug%20master/debugging/pipeline-1-notification-debug-report.md
- New-session Pipeline 1 report: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline1_static_debug_report_b6abe0a.md
- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- `NotificationFilter.kt`
- `RawNotification.kt`
- `RawNotificationFingerprint.kt`
- `RawContentSanitizer.kt`
- `DiagnosticEventWriter.kt`
- `MaintenanceSafeDiagnosticSink.kt`

---

# Executive verdict

Pipeline 1 is **not clean yet**.

Many old issues are partly fixed, but the current implementation introduced or exposed several new gaps around diagnostics durability, sanitized-storage dedupe, fail-closed caches, and location/foreground-service consistency.

The most important remaining risks are:

1. **Accepted notifications can still be lost on service/process death.**
2. **Repository still hides the real pipeline outcome from the service.**
3. **Messaging-style notification extraction is still wrong/incomplete.**
4. **Full `PrivacyGate` still runs after extras/text extraction.**
5. **In-memory dedupe is still coarse and removed after success.**
6. **Non-raw storage modes break the fast raw-field duplicate pre-check.**
7. **Batch/public repository entrypoints still bypass storage sanitization.**
8. **Diagnostics use the normal Room writer even where the maintenance-safe sink exists.**
9. **Notification pipeline still reads GPS/location context, contradicting service comments and needing explicit privacy/OS verification.**

---

# Tracker reconciliation

Current master tracker is still too optimistic for Pipeline 1.

| ID | Tracker status | Correct status at `e781c22` |
|---|---:|---:|
| P1-P1-01 | fixed | **Partial** |
| P1-P1-02 | partial | **Partial / mostly improved** |
| P1-P1-03 | fixed | **Partial** |
| P1-P1-05 | TODO | **Partial** |
| P1-P1-06 | fixed | **Mostly fixed with caveats** |
| P1-P1-07 | TODO | **Open** |

---

# Complete open issue list

## OLD-01 / P1-P1-01 — Repository still flattens outcomes

Status: **Partial**

`NotificationProcessingPipeline.process()` returns `NotificationPipelineOutcome`, but `NotificationRepository.processAndSave(...)` still returns `Unit`.

Current effect:
- service cannot know whether result was `AutoAccepted`, `NeedsReview`, `Duplicate`, `ParserFailed`, `Dropped`, or `Error`;
- service logs “Processed notification” after repository call even if the pipeline outcome was an error-like outcome returned rather than thrown;
- dedupe logic cannot decide correctly whether to retain/remove key.

Required:
```kotlin
suspend fun processAndSave(...): NotificationPipelineOutcome
suspend fun processAndSaveAll(...): List<NotificationPipelineOutcome>
```

---

## OLD-02 / P1-P1-02 — Diagnostic ledger improved but not durable/safe enough

Status: **Partial / mostly improved**

Fixed/improved:
- service emits `RECEIVED`;
- service emits early drop diagnostics for restore, privacy, blocked package, filter, duplicate, cancellation;
- pipeline writes outcome diagnostics;
- correlation ID is propagated.

Remaining gaps:
- early diagnostic writes are launched on `serviceScope`; service destruction can still cancel them;
- `MaintenanceSafeDiagnosticSink` is injected but normal paths use `diagnosticEventWriter.emit(...)`;
- `RoomDiagnosticEventWriter` writes directly to Room, so restore-mode diagnostic behavior is ambiguous;
- no durable intake ledger exists.

New related issue: **NEW-01** below.

---

## OLD-03 / P1-P1-03 — `textLines` fixed, `EXTRA_MESSAGES` still likely broken

Status: **Partial**

Fixed:
- `EXTRA_TEXT_LINES` is extracted.
- `combinedBody` exists.

Still broken:
- `EXTRA_MESSAGES` is read as `CharSequence`.
- Real Android `Notification.MessagingStyle.Message` payloads are usually parcelables/bundles, not plain `CharSequence`.

Required:
- use `Notification.MessagingStyle.Message.getMessagesFromBundleArray(...)`;
- support parcelable bundle arrays;
- add API-version tests.

---

## OLD-04 / P1-P1-04 — `effectiveBigText` lossy issue mostly fixed

Status: **Mostly fixed**

`combinedBody` is now used in key service paths.

Remaining cleanup:
- stale docs still say `effectiveBigText` is the canonical downstream value;
- `effectiveBigText` still exists and can confuse future code;
- tests should enforce `combinedBody` usage.

---

## OLD-05 / P1-P1-05 — Privacy gate before extraction only partially fixed

Status: **Partial**

Fixed:
- fast cached `notificationCaptureEnabled` check happens before extras extraction;
- blocked-package cache check happens before extras extraction.

Still open:
- full `PrivacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)` still happens after:
  - extras access,
  - `NotificationTextParts.extract(...)`,
  - filter,
  - in-memory dedupe insert;
- fast gate only represents one setting, not the full policy;
- `PrivacyDecision.NotApplicable` / inconclusive branches proceed with capture;
- startup cache fail-closed can drop valid notifications.

Required:
- real `NotificationCaptureGate`;
- cached full `PrivacyDecision`;
- synchronous first-load or safe initialization;
- no extras read until gate says allowed.

---

## OLD-06 / P1-P1-06 — Restore/write barrier mostly fixed but has caveats

Status: **Mostly fixed**

Fixed:
- service checks `restoreMaintenanceMode.isWritesAllowed()`;
- pipeline checks `DatabaseWriteBarrier`;
- repository save/delete/reset mostly guarded.

Remaining caveats:
- `NotificationRepository.blockPackage()` / `unblockPackage()` directly mutate DAO without visible write barrier;
- `restoreSourceStatsSnapshot()` likely still needs barrier verification;
- diagnostic writes during restore are not clearly routed through `MaintenanceSafeDiagnosticSink`.

---

## OLD-07 / P1-P1-07 — Shutdown/process death can still lose notifications

Status: **Open**

Current code comments explicitly say:
- active notifications are recovered through refresh;
- full durability needs a future `NotificationIntake` table;
- `onDestroy()` cancels `serviceJob`.

Failure sequence still possible:
1. listener receives bank notification;
2. gates pass;
3. work is launched async;
4. service/process dies;
5. no durable row is guaranteed;
6. Android may not repost notification.

Required:
- durable intake table;
- worker resume;
- stale processing recovery.

---

## OLD-08 / P2-08 — Manual refresh still bypasses service dedupe

Status: **Partial / open**

`refreshActiveNotifications()` still calls `processNotificationBypassDedupe()`.

DB duplicate checks may catch exact duplicates, but:
- parser/AI can still run unnecessarily;
- with sanitized storage modes, the raw-field `exists(...)` pre-check can miss duplicates;
- duplicate diagnostics can be noisy.

---

## OLD-09 / P2-09 — Finance package filtering still partial

Status: **Partial**

Improved:
- finance packages no longer blindly pass everything;
- hard-deny keywords exist.

Still partial:
- finance package content with any currency amount can pass, even if it is balance-only/account-info noise;
- hard deny is keyword-based and narrow;
- no shared transaction-signal detector.

---

## OLD-10 / P2-10 / P1-NEW-08 — Currency fallback still EUR/USD/GBP-heavy

Status: **Open**

`NotificationFilter` supports more currencies now, but `NotificationProcessingPipeline` fallback regexes still focus on:
- EUR,
- USD,
- GBP.

Fallback defaults to EUR.

Impact:
- non-EUR users can get wrong-currency pending reviews;
- non-EUR transaction-signal fallback can fail.

Required:
- shared `NotificationAmountSignalDetector`;
- use app-wide supported currency list;
- no silent EUR default unless home-currency policy says so.

---

## OLD-11 / P2-11 / P1-NEW-01 — Raw-storage privacy bypass mostly fixed, but not complete

Status: **Partial**

Fixed:
- pending-review text paths now appear to use `sanitizePendingReviewText(...)`;
- service creates separate `processingNotification` and `storageNotification`.

Still open:
- public `processAndSave(notification)` passes raw object as both processing and storage;
- `processAndSaveAll(...)` / batch path uses raw notifications;
- `processBatch(...)` calls `processInternal(notification, initializeClassifier = false)` with default `storageNotification = notification`;
- extras JSON is still built before mode-specific storage decision;
- transaction-event/audit payloads need verification for raw-title leakage.

Required:
- one sanitizer/persistence payload contract across all entrypoints;
- tests inspecting `raw_notifications`, `pending_reviews`, `transaction_events`, diagnostics.

---

## OLD-12 / P2-12 / P1-NEW-07 — AI fallback provenance incomplete

Status: **Open**

Still present:
- `parseWithAiFallback(...)`;
- second deterministic parse to infer provenance.

Required:
```kotlin
data class ParseOutcome(
    val parsed: ParsedTransaction?,
    val deterministicParserName: String?,
    val usedAiFallback: Boolean,
    val aiProvider: String?,
    val confidence: Float?,
    val failureReason: String?
)
```

---

## OLD-13 / P3-13 — `NotificationCaptureService` still too large

Status: **Open architectural cleanup**

Still owns:
- Android lifecycle;
- foreground service;
- restart alarm;
- extraction;
- filter;
- dedupe;
- privacy;
- restore;
- raw mapping;
- extras JSON.

Should be split into:
- `NotificationExtractor`;
- `NotificationCaptureGate`;
- `NotificationCaptureDeduper`;
- `NotificationPersistenceMapper`;
- `NotificationCaptureDiagnostics`;
- `NotificationIntakeCoordinator`.

---

# New / newly confirmed gaps at `e781c22`

## NEW-01 — Maintenance-safe diagnostic sink is injected but not used

Severity: **P1**

Evidence:
- `NotificationCaptureService` injects `MaintenanceSafeDiagnosticSink`.
- Early events call `diagnosticEventWriter.emit(...)`.
- `RoomDiagnosticEventWriter` writes directly to Room.
- restore-blocked path still emits diagnostics through normal writer.

Risk:
- restore-mode diagnostics may either:
  - write to DB during restore, violating the maintenance contract, or
  - fail/disappear if DB is unavailable/blocked.

Fix:
- introduce `SafeNotificationDiagnosticEmitter`;
- when mode is non-normal, write to `MaintenanceSafeDiagnosticSink`;
- if Room write fails, fallback to sink.

---

## NEW-02 — Fail-closed blocked-package cache can drop valid notifications on startup

Severity: **P1/P2 reliability**

Current behavior:
```kotlin
isPackageBlockedFast(packageName) =
    !blockedPackageCacheLoaded || packageName in blockedPackagesCache
```

Until first DB flow emission, every package is treated as blocked.

Impact:
- valid bank notifications posted immediately after service start can be silently dropped with blocked-package diagnostic.

Fix:
- synchronously load blocked packages once in `onCreate()` before listener processing;
- or introduce `GateState.Loading` and buffer/queue briefly;
- do not permanently drop due only to cache not loaded.

---

## NEW-03 — Fail-closed privacy cache can drop valid notifications on startup

Severity: **P1/P2 reliability**

`capturePrivacyDenied = true` until first settings emission.

This is privacy-safe, but can lose valid notifications after service start.

Fix:
- synchronous `privacySettingsRepository.getSettings()` initialization in `onCreate()`;
- then observe flow for updates;
- diagnostic reason should distinguish `PRIVACY_DENIED` from `PRIVACY_CACHE_NOT_READY`.

---

## NEW-04 — Non-raw storage modes break fast duplicate pre-check

Severity: **P1/P2**

Pipeline pre-parse duplicate check uses:
```kotlin
dao.exists(packageName, timestamp, rawTitle, rawText, rawBigText)
```

But in metadata-only / do-not-store / redacted modes, stored row has:
- null title/text/body, or
- `[REDACTED]`.

Therefore future duplicate pre-check using raw in-memory text will not match stored sanitized fields.

The DB unique `dedupeFingerprint` can still reject `insertOrIgnore`, but only **after parser/AI work**.

Impact:
- duplicate notifications in privacy-safe modes still hit parser/AI;
- diagnostics may be noisy;
- manual refresh gets worse;
- defeats TRN-8 “fast pre-parse dedup”.

Fix:
- add DAO exists-by-fingerprint:
```kotlin
@Query("SELECT EXISTS(SELECT 1 FROM raw_notifications WHERE dedupeFingerprint = :fingerprint)")
suspend fun existsByDedupeFingerprint(fingerprint: String): Boolean
```
- use fingerprint before parser.

---

## NEW-05 — `dedupeFingerprint` is unique but `insertOrIgnore` result needs stronger outcome handling

Severity: **P2**

`insertRawNotificationIfNotDuplicate(...)` uses `insertOrIgnore(...)`.

If unique fingerprint rejects insert, outcome must be explicitly `Duplicate`, not ambiguous raw failure.

Need verify every caller maps `-1L` to duplicate safely.

Fix:
- wrap insert as:
```kotlin
sealed interface RawInsertResult {
  data class Inserted(val rawId: Long)
  data object Duplicate
}
```

---

## NEW-06 — `RawNotification.isProcessed` is dead

Severity: **P2/P3**

`RawNotification` has TODO:
- `isProcessed` is never set true.

Impact:
- cannot use raw row status to resume;
- misleading data model;
- durable intake work needs a real status machine instead.

Fix:
- either remove/migrate field;
- or replace with intake/processing status.

---

## NEW-07 — `buildExtrasJson()` still materializes extras even when raw storage is disabled

Severity: **P2 privacy hardening**

`processNotification(...)` builds `extrasJson` before checking `rawNotificationStorageMode`.

Even though persisted extras are null/redacted later, the app still stringifies all extras in memory.

Fix:
- get settings before building extras;
- only build extras for `STORE_RAW`;
- for redacted mode, do not inspect all extras; store fixed marker.

---

## NEW-08 — Service KDoc/foreground type says no location, but pipeline reads GPS

Severity: **P1 verification / possible privacy + OS compliance**

Service comment says notification capture does not read location and uses only `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.

But `NotificationProcessingPipeline.buildPreDbContext()` calls:
```kotlin
locationProvider.getLastKnownLocation()
```

Impact:
- privacy mismatch;
- possible Android foreground-service type mismatch if location is accessed while service-driven processing occurs;
- user may not expect GPS enrichment from notification capture.

Fix:
- either remove location enrichment from notification pipeline;
- or explicitly gate by location privacy + permission + foreground requirements;
- add diagnostic event for location denied/unavailable;
- update service docs/FGS type if location remains.

---

## NEW-09 — Auto-accept audit still risks raw notification title usage

Severity: **P1/P2 privacy verification**

Auto-accept path builds an `auditReason` JSON with `notification.title`.

Even if the current writer uses safe metadata and ignores `auditReason`, the code still constructs raw audit payload.

Fix:
- remove unused raw `auditReason`;
- enforce no raw title/text in transaction events when mode is not `STORE_RAW`;
- add regression test.

---

## NEW-10 — Source-link/dedupe source links use `runCatching` and swallow failures

Severity: **P2**

`writeNotificationDedupeSourceLink(...)` uses `runCatching` and ignores failure.

Impact:
- provenance gaps can happen invisibly;
- this conflicts with source-link/provenance contract.

Fix:
- emit diagnostic `SOURCE_LINK_FAILED`;
- do not fail user transaction, but make failure durable.

---

## NEW-11 — Pipeline diagnostic writer swallows diagnostic failures

Severity: **P2**

`writePipelineDiagnosticEvent(...)` wraps all diagnostic writes in `runCatching`.

Impact:
- observability can silently disappear.

Fix:
- fallback to `MaintenanceSafeDiagnosticSink`;
- at minimum log non-sensitive warning;
- add test with failing Room writer.

---

## NEW-12 — Service-level duplicate key is still only `sbn.key`

Severity: **P1**

Still:
```kotlin
val coarseDedupeKey = notificationKey
```

A bank can reuse status-bar key/tag for different transaction updates.

Impact:
- second transaction inside 5 seconds can be dropped before content is compared.

Fix:
- move dedupe after allowed extraction;
- use:
```text
packageName + notificationKey + postTime + contentFingerprint
```
- durable DB uniqueness remains fingerprint-based.

---

## NEW-13 — Successful in-memory dedupe key is still removed immediately

Severity: **P1/P2**

`finally` always removes:
```kotlin
processedNotifications.remove(coarseDedupeKey)
```

Impact:
- dedupe only protects in-flight work;
- immediate repeated callback after success can re-enter parser/pipeline.

Fix:
- retain success key until TTL;
- remove only on cancellation before durable insert or retryable pre-insert failure;
- requires repository to return outcome.

---

# PR organization

## PR 1 — Outcome contract + truthful service handling

Goal:
- repository returns real outcome;
- service acts on outcome;
- no false success logging.

Fixes:
- OLD-01 / P1-P1-01
- NEW-05
- groundwork for NEW-12/NEW-13

Files:
- `NotificationRepository.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationCaptureService.kt`
- tests

Tasks:
1. Change:
```kotlin
processAndSave(...): NotificationPipelineOutcome
processAndSaveAll(...): List<NotificationPipelineOutcome>
```
2. Remove/deprecate dead `ProcessingResult`.
3. Replace `Timber.d("Processed notification")` with outcome-aware log.
4. Make `insertOrIgnore == -1L` a typed duplicate result.
5. Add tests for all sealed outcomes.

Acceptance:
- service can distinguish accepted/review/duplicate/parser-failed/dropped/error.
- no success log after `Error`, `Dropped`, or `ParserFailed`.

---

## PR 2 — Diagnostics safety and maintenance-safe emission

Goal:
- diagnostics are durable/safe even during restore/shutdown.
- no silent diagnostic failure.

Fixes:
- OLD-02 / P1-P1-02
- NEW-01
- NEW-10
- NEW-11

Files:
- new `SafeNotificationDiagnosticEmitter.kt`
- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- `DiagnosticEventWriter.kt` usage sites
- `MaintenanceSafeDiagnosticSink`

Tasks:
1. Wrap all notification diagnostic writes.
2. If maintenance mode is non-normal, use `MaintenanceSafeDiagnosticSink`.
3. If Room diagnostic write fails, fallback to sink.
4. Emit durable `SOURCE_LINK_FAILED` for swallowed provenance errors.
5. Add tests for restore-drop diagnostics and failing writer.

Acceptance:
- every listener callback gets either `RECEIVED + terminal` or durable maintenance-safe fallback.
- no diagnostic failure is silently swallowed.

---

## PR 3 — Full capture gate before extraction

Goal:
- no extras/text extraction before full allow decision.

Fixes:
- OLD-05 / P1-P1-05
- NEW-02
- NEW-03
- part of OLD-13

Files:
- new `NotificationCaptureGate.kt`
- `NotificationCaptureService.kt`
- `PrivacySettingsRepository`
- `BlockedPackageDao`

Tasks:
1. Initialize privacy and blocked-package state synchronously on service start.
2. Represent gate states:
```kotlin
Allowed
Denied(reason)
Loading(reason)
RestoreBlocked
Shutdown
```
3. Do not drop valid notifications just because cache is not ready; buffer briefly or emit distinct loading drop.
4. Replace `isPrivacyDeniedFast()` and `isPackageBlockedFast()` with one gate.
5. Full cached `PrivacyGate` decision must be available before extraction.

Acceptance:
- privacy denied => extractor not called.
- blocked package => extractor not called.
- cache loading has explicit reason, not fake blocked/privacy denial.
- inconclusive privacy does not silently proceed unless policy explicitly says so.

---

## PR 4 — Privacy persistence hardening across all entrypoints

Goal:
- raw-storage mode applies to every persisted table and every repository path.

Fixes:
- OLD-11 / P2-11 / P1-NEW-01
- NEW-07
- NEW-09
- batch/public bypasses

Files:
- `NotificationRepository.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationCaptureService.kt`
- new `NotificationPersistencePayload.kt`
- `RawContentSanitizer.kt`

Tasks:
1. Remove or restrict public raw overload:
```kotlin
processAndSave(notification)
```
or make it load storage mode and sanitize.
2. Fix `processBatch(...)` to create sanitized storage payloads.
3. Build extras JSON only for `STORE_RAW`.
4. Remove raw title/text from transaction-event/audit payloads unless mode allows.
5. Add tests for:
   - raw notifications,
   - pending reviews,
   - transaction events,
   - diagnostics,
   - source links.

Acceptance:
- `DO_NOT_STORE` stores no raw body/title/extras anywhere.
- `STORE_METADATA_ONLY` keeps only safe metadata/fingerprint.
- batch path behaves same as listener path.

---

## PR 5 — Fingerprint-first duplicate detection

Goal:
- duplicate detection remains fast even when raw text is not stored.

Fixes:
- OLD-08 / P2-08
- NEW-04
- part of NEW-05

Files:
- `RawNotificationDao.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- migration if needed only for index/query support

Tasks:
1. Add `existsByDedupeFingerprint(...)`.
2. Use fingerprint pre-check before parse/AI.
3. Manual refresh checks durable fingerprint before parser.
4. Add diagnostic `DUPLICATE` with reason `FINGERPRINT_DUPLICATE`.
5. Add tests under each `RawStorageMode`.

Acceptance:
- duplicate notification under `METADATA_ONLY` does not call parser/AI.
- refresh same notification twice creates one raw row and one final duplicate outcome.

---

## PR 6 — Service in-memory dedupe correctness

Goal:
- no duplicate reprocessing, no dropped distinct transaction.

Fixes:
- NEW-12
- NEW-13
- old dedupe issues from b6 report

Files:
- `NotificationCaptureService.kt`
- new `NotificationCaptureDeduper.kt`

Tasks:
1. Replace `sbn.key`-only key with content-aware key:
```text
packageName + sbn.key + postTime + contentHash
```
2. Insert in-memory key after privacy/package gate.
3. Keep success key until TTL.
4. Remove key only on cancellation before durable insert or retryable pre-insert failure.
5. Requires PR 1 outcome return.

Acceptance:
- same callback twice => one processing run.
- same `sbn.key`, different body/amount => both processed.
- successful processing suppresses immediate repeat callback.

---

## PR 7 — Correct MessagingStyle extraction

Goal:
- real SMS/messaging bank alerts are captured.

Fixes:
- OLD-03 / P1-P1-03

Files:
- new `NotificationExtractor.kt`
- `NotificationCaptureService.kt`
- tests

Tasks:
1. Move extraction out of service.
2. Use:
```kotlin
Notification.MessagingStyle.Message.getMessagesFromBundleArray(...)
```
3. Support API 23–current paths.
4. Deduplicate repeated text pieces.
5. Include `EXTRA_TITLE_BIG` if relevant.

Acceptance:
- amount only in `EXTRA_MESSAGES` is captured.
- amount only in `EXTRA_TEXT_LINES` is captured.
- no duplicate text inflation.

---

## PR 8 — Durable notification intake queue

Goal:
- accepted notification cannot be lost after listener callback.

Fixes:
- OLD-07 / P1-P1-07
- NEW-06
- part of OLD-02

Files:
- new `NotificationIntakeEntity.kt`
- new `NotificationIntakeDao.kt`
- `AppDatabase.kt`
- `NotificationCaptureService.kt`
- new `NotificationIntakeWorker.kt`
- `NotificationProcessingPipeline.kt`

Tasks:
1. Add intake status:
```text
RECEIVED
PROCESSING
PROCESSED
FAILED_RETRYABLE
FAILED_FINAL
DROPPED_DUPLICATE
DROPPED_POLICY
```
2. Listener writes intake row after gate/filter and before expensive parse.
3. Worker processes by intake ID.
4. On app start, resume stale `RECEIVED/PROCESSING`.
5. Replace or repurpose `RawNotification.isProcessed`.

Acceptance:
- killing service after receive does not lose notification.
- stale intake rows resume.
- retryable failures retry with backoff.

---

## PR 9 — Currency/amount signal consolidation

Goal:
- no EUR/USD/GBP-only fallback behavior.

Fixes:
- OLD-10 / P2-10 / P1-NEW-08
- part of OLD-09 / P2-09

Files:
- new `NotificationAmountSignalDetector.kt`
- `NotificationFilter.kt`
- `NotificationProcessingPipeline.kt`
- currency config/domain module

Tasks:
1. Shared detector for:
   - filter,
   - oversized fallback,
   - transaction-signal fallback.
2. Use app-wide supported currencies.
3. No silent EUR default unless policy is explicit.
4. Add tests for PLN/RON/TRY/CAD/AUD/JPY/CHF etc.

Acceptance:
- non-EUR transaction signal creates correct-currency review.
- balance-only finance alert is not treated as purchase.
- filter and fallback agree.

---

## PR 10 — Location/privacy/foreground-service consistency

Goal:
- notification capture does not secretly attach GPS or violate service contract.

Fixes:
- NEW-08
- old P1-NEW-06

Files:
- `NotificationProcessingPipeline.kt`
- `ForegroundLocationProvider`
- `NotificationCaptureService.kt`
- privacy diagnostics/tests

Tasks:
1. Decide policy:
   - remove location enrichment from notification pipeline, or
   - explicitly gate it.
2. If keeping:
   - check location privacy before provider call;
   - verify permission;
   - emit `LOCATION_DENIED` / `LOCATION_UNAVAILABLE`;
   - update KDoc and foreground-service type if required.
3. Add unit tests proving provider is not called when denied.

Acceptance:
- notification pipeline cannot bypass location privacy.
- service docs match actual behavior.
- no location read occurs under DATA_SYNC-only assumption unless explicitly allowed/legal.

---

## PR 11 — AI parser provenance contract

Goal:
- parser source is explicit and no duplicate parse call.

Fixes:
- OLD-12 / P2-12 / P1-NEW-07

Files:
- `AppParserRegistry`
- `NotificationProcessingPipeline.kt`
- pending-review metadata if available
- diagnostics

Tasks:
1. Replace parse result with `ParseOutcome`.
2. Store:
   - deterministic parser name;
   - AI fallback used;
   - provider;
   - confidence;
   - failure reason.
3. Remove second deterministic parse.
4. Add diagnostics:
   - `PARSER_SELECTED`;
   - `AI_FALLBACK_USED`;
   - `PARSER_FAILED`.

Acceptance:
- every review/expense can say where parse came from.
- no repeated deterministic parse just for provenance.

---

## PR 12 — Service decomposition cleanup

Goal:
- reduce future regressions.

Fixes:
- OLD-13 / P3-13

Files/components:
- `NotificationExtractor`
- `NotificationCaptureGate`
- `NotificationCaptureDeduper`
- `NotificationPersistenceMapper`
- `NotificationCaptureDiagnostics`
- `NotificationIntakeCoordinator`

Tasks:
1. Keep `NotificationCaptureService` as Android adapter only.
2. Move pure logic into testable classes.
3. Add static tests for capture flow order.

Acceptance:
- service no longer owns parsing/filtering/dedupe/privacy/storage mapping directly.
- new tests can run without Android listener instance.

---

# Recommended implementation order

Do not start with durable intake first; it depends on outcome/dedupe/privacy pieces.

Recommended order:

1. **PR 1 — Outcome contract**
2. **PR 2 — Diagnostic safety**
3. **PR 3 — Full capture gate**
4. **PR 4 — Privacy persistence hardening**
5. **PR 5 — Fingerprint-first duplicate detection**
6. **PR 6 — In-memory dedupe correctness**
7. **PR 7 — MessagingStyle extraction**
8. **PR 8 — Durable intake queue**
9. **PR 9 — Currency/amount detector**
10. **PR 10 — Location/privacy/FGS consistency**
11. **PR 11 — AI provenance**
12. **PR 12 — service decomposition**

If you want fewer PRs, merge like this:

- PR A: Outcome + diagnostics.
- PR B: Capture gate + privacy persistence.
- PR C: Dedup + fingerprint + messaging extraction.
- PR D: Durable intake.
- PR E: currency/location/AI provenance cleanup.

But the 12-PR split is safer for your current debugging workflow.

---

# Tracker update recommendation

Update Pipeline 1 tracker to:

| ID | New status |
|---|---:|
| P1-P1-01 | ⚠ PARTIAL |
| P1-P1-02 | ⚠ PARTIAL |
| P1-P1-03 | ⚠ PARTIAL |
| P1-P1-04 | ✅ MOSTLY FIXED / cleanup only |
| P1-P1-05 | ⚠ PARTIAL |
| P1-P1-06 | ✅ FIXED WITH CAVEATS |
| P1-P1-07 | TODO ONLY |
| P2-08 | ⚠ PARTIAL |
| P2-09 | ⚠ PARTIAL |
| P2-10 | TODO ONLY |
| P2-11 | ⚠ PARTIAL |
| P2-12 | TODO ONLY |
| P3-13 | TODO ONLY |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P1-NEW-09 | P1 | Maintenance-safe diagnostic sink injected but not used |
| P1-NEW-10 | P1/P2 | Blocked-package cache fail-closed startup drops valid notifications |
| P1-NEW-11 | P1/P2 | Privacy cache fail-closed startup drops valid notifications |
| P1-NEW-12 | P1/P2 | Non-raw storage modes break raw-field duplicate pre-check |
| P1-NEW-13 | P2 | `insertOrIgnore` duplicate path needs typed outcome |
| P1-NEW-14 | P2/P3 | `RawNotification.isProcessed` is dead |
| P1-NEW-15 | P2 | Extras JSON materialized even when raw storage disabled |
| P1-NEW-16 | P1 | Notification service says no location but pipeline reads GPS |
| P1-NEW-17 | P1/P2 | Auto-accept audit path still constructs raw title payload |
| P1-NEW-18 | P2 | Source-link failures swallowed |
| P1-NEW-19 | P2 | Pipeline diagnostic failures swallowed |
| P1-NEW-20 | P1 | Service in-memory dedupe remains `sbn.key`-coarse |
| P1-NEW-21 | P1/P2 | Successful dedupe key removed immediately |

---

# Bottom line

You fixed a lot, but Pipeline 1 still has **13 old open/partial issues** plus **13 new/newly confirmed gaps**.

The next best PR is **Outcome contract + diagnostic safety**, because almost every later fix needs the service to know the real pipeline result and needs diagnostics that do not disappear during cancellation/restore.