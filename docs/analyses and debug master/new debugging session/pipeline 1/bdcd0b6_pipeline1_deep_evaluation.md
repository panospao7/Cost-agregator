# Pipeline 1 deep evaluation — commit `bdcd0b6b29aa1a12d2ebba90c7dd2958f72d89e1`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/bdcd0b6b29aa1a12d2ebba90c7dd2958f72d89e1

Key files reviewed:
- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- `NotificationCaptureGate.kt`
- `NotificationTextParts.kt`
- `NotificationDiagnosticEmitter.kt`
- `AppParserRegistry.kt`
- `RawNotificationDao.kt`
- `NotificationIntakeDao.kt`
- `AppDatabase.kt`

---

# Executive verdict

This commit is a **large and real improvement**, especially for:

- repository outcome propagation,
- safe diagnostic emitter,
- pre-extraction capture gate,
- MessagingStyle extraction,
- fingerprint duplicate pre-check,
- redacted extras handling,
- expanded currency regex coverage,
- service success-key retention,
- source-link failure diagnostics,
- intake schema creation.

However, it does **not fully close all issues**.

The biggest remaining problems are:

1. **P1-P1-07 is not actually fixed** — intake schema exists, but the service still processes directly and no intake worker/coordinator is wired.
2. **P2-08 is still open/partial** — manual refresh still explicitly bypasses service dedupe.
3. **P2-12 is only partial** — second deterministic parse appears removed, but there is still no `ParseOutcome` / `parseWithProvenance()`.
4. **P1-NEW-13 is not fixed** — `insertOrIgnore` still returns `Long` and `-1L` sentinel checks remain.
5. **CaptureGate has correctness bugs** — partial warm-up failure can allow capture without settings or blocked-package data.
6. **Startup false-drop risk remains** — warm-up is launched asynchronously, not completed before callbacks.
7. **P2-10 is partial** — regex expanded, but fallback still defaults to USD/EUR and has no ambiguity model.
8. **P3-13 is only minimally improved** — `NotificationTextParts` moved out, but service remains large and owns many responsibilities.
9. **In-memory dedupe is better but still racy and lossy in edge cases**.
10. **Batch/public repository paths still bypass listener privacy-sanitization rules.**

Bottom line:

```text
This commit moves Pipeline 1 from “many open P1 issues” to “materially hardened but still not clean.”
```

---

# Status table — old issues

| ID | Claimed by commit | My status | Notes |
|---|---:|---:|---|
| P1-P1-01 | fixed | **Mostly fixed** | Repository now returns `NotificationPipelineOutcome`; service logs truthfully. |
| P1-P1-02 | fixed | **Mostly fixed / partial** | Safe emitter exists and is used, but service-scope async drops can still lose diagnostics during shutdown. |
| P1-P1-03 | fixed | **Mostly fixed with caveats** | `MessagingStyle.Message.getMessagesFromBundleArray()` is used. Fallback still has unsafe `item.toString()`. |
| P1-P1-05 | fixed | **Partial** | Gate exists before extraction, but warm-up/partial-load logic is flawed. |
| P1-P1-06 | fixed | **Mostly fixed with old caveats** | Main processing guarded; `blockPackage`, `unblockPackage`, and `restoreSourceStatsSnapshot` still lack visible barrier. |
| P1-P1-07 | schema | **Open** | Only schema/DAO exists. Service still processes directly; no worker/recovery path. |
| P2-08 | fixed | **Partial/open** | Refresh still calls `processNotificationBypassDedupe()`. |
| P2-09 | fixed | **Unverified / likely partial** | No clear evidence of structured finance detector v2; `NotificationFilter.kt` was not obviously part of the sweep. |
| P2-10 | fixed | **Partial** | Regex expanded, but fallback still has hardcoded USD/EUR defaults. |
| P2-11 | fixed | **Partial** | Listener path improved; public/batch paths still raw/sanitization-bypass risk. |
| P2-12 | fixed | **Partial** | Duplicate deterministic parse removed from pipeline, but no typed provenance contract. |
| P3-13 | fixed | **Partial** | `NotificationTextParts` extracted, but service remains 800+ LOC and multi-responsibility. |

---

# Status table — new issues from previous review

| ID | My status at `bdcd0b6` | Notes |
|---|---:|---|
| P1-NEW-09 | **Mostly fixed** | `NotificationDiagnosticEmitter` added and used. |
| P1-NEW-10 | **Partial / still risky** | Cache fail-closed behavior changed, but partial cache load can allow blocked packages. |
| P1-NEW-11 | **Partial / still risky** | Async warm-up can still drop valid startup notifications as `PRIVACY_DENIED`/gate unavailable. |
| P1-NEW-12 | **Mostly fixed for listener path** | Fingerprint pre-check exists, but null-fingerprint public/batch paths still weak. |
| P1-NEW-13 | **Not fixed** | `-1L` sentinel still used. |
| P1-NEW-14 | **Not clearly fixed** | No evidence `RawNotification.isProcessed` is maintained/deprecated. |
| P1-NEW-15 | **Mostly fixed for listener path** | Extras JSON only built for `STORE_RAW`; batch/public paths still need audit. |
| P1-NEW-16 | **Partial / cleanup needed** | No direct `getLastKnownLocation()` found, but pipeline still injects `ForegroundLocationProvider` and has `deviceGps`. |
| P1-NEW-17 | **Needs verification** | KDoc still says audit contains sanitized notification title. Need grep actual event payloads. |
| P1-NEW-18 | **Partial** | Failure diagnostic added, but no typed `SourceLinkWriteResult`. |
| P1-NEW-19 | **Mostly fixed** | Pipeline uses safe diagnostic emitter. |
| P1-NEW-20 | **Partial** | Key is content-aware, but uses raw package/key string, non-atomic map ops, 32-bit hash, no postTime. |
| P1-NEW-21 | **Partial** | Success keys retained briefly, but refresh bypasses, races remain, retry/final classification is crude. |

---

# Detailed evaluation

---

## 1. P1-P1-01 — Outcome propagation

## Current state

This is one of the strongest fixes.

`NotificationRepository.processAndSave(...)` now returns `NotificationPipelineOutcome`.

Evidence:
- `processAndSave(notification): NotificationPipelineOutcome`
- `processAndSave(processingNotification, storageNotification, correlationId): NotificationPipelineOutcome`
- `processAndSaveAll(...): List<NotificationPipelineOutcome>`

Service also now records:

```kotlin
pipelineOutcome = processNotification(...)
```

and logs differently for:
- `AutoAccepted`
- `NeedsReview`
- `Duplicate`
- `ParserFailed`
- `AutoRejected`
- `Dropped`
- `Error`

## Remaining caveats

### Caveat 1 — batch path still raw-storage unsafe

`processAndSaveAll(notifications)` delegates to:

```kotlin
pipeline.processBatch(notifications)
```

and `processBatch()` calls:

```kotlin
processInternal(notification, initializeClassifier = false)
```

with default:

```kotlin
storageNotification = notification
```

So batch/public paths can still bypass the listener’s `processingNotification` vs `storageNotification` split.

### Caveat 2 — service outcome handling is still tied to legacy dedupe map

Outcome is now returned, but dedupe handling is still manually implemented in the service, not centralized.

## Verdict

```text
P1-P1-01: mostly fixed.
```

Tracker can mark it fixed, with a privacy-entrypoint caveat elsewhere.

---

## 2. P1-P1-02 / P1-NEW-09 / P1-NEW-19 — Diagnostic safety

## Current state

This is materially improved.

New:

```kotlin
NotificationDiagnosticEmitter
```

It:
- writes through `DiagnosticEventWriter` in normal mode,
- routes to `MaintenanceSafeDiagnosticSink` in non-normal restore/maintenance mode,
- falls back to sink if writer throws,
- catches sink failure and logs warning.

Pipeline now injects:

```kotlin
private val diagnosticEmitter: NotificationDiagnosticEmitter
```

and uses it for:
- parse diagnostics,
- source-link failure diagnostics,
- pipeline outcome diagnostics.

Service also injects:

```kotlin
notificationDiagnosticEmitter
```

and uses it in normal/early paths.

## Remaining gaps

### Gap 1 — service-scope diagnostic emission can still be cancelled

`emitOrderedNotificationEvents(...)` does:

```kotlin
workTracker.launch(serviceScope) {
    notificationDiagnosticEmitter.emitOrdered(received, terminal)
}
```

If the service is shutting down or `serviceJob.cancel()` happens immediately after, these diagnostics can still be lost.

The emitter has:

```kotlin
emitOrderedNonCancellable(...)
```

but the service does not use it for shutdown/early terminal events.

### Gap 2 — `workTracker.launch(...)` null result ignored

If `workTracker.launch(serviceScope)` returns `null`, the event is silently not emitted.

This matters for:
- restore drop,
- shutdown drop,
- privacy drop,
- gate unavailable,
- duplicate,
- filter reject.

### Gap 3 — terminal reason for temporary gate unavailable is wrong

For `NotificationCaptureDecision.TemporarilyUnavailable`, service emits:

```kotlin
reasonCode = PRIVACY_DENIED
```

That is diagnostically misleading. It should be `GATE_NOT_READY`, `PRIVACY_SETTINGS_NOT_READY`, or `FAILED_RETRYABLE`.

### Gap 4 — pipeline diagnostics are safe, but not result-returning

`NotificationDiagnosticEmitter.emit()` does not return a `DiagnosticEmitResult`.

That is acceptable if you do not care about tests asserting fallback behavior, but it is weaker than the plan. Still much better than swallowed `runCatching`.

## Verdict

```text
P1-P1-02: mostly fixed, not perfect.
P1-NEW-09: mostly fixed.
P1-NEW-19: mostly fixed.
```

Recommended small follow-up:

```kotlin
private fun emitOrderedNotificationEvents(...) {
    val job = workTracker.launch(serviceScope) {
        notificationDiagnosticEmitter.emitOrdered(received, terminal)
    }
    if (job == null) {
        applicationScope.launch {
            notificationDiagnosticEmitter.emitOrderedNonCancellable(received, terminal)
        }
    }
}
```

---

## 3. P1-P1-03 — MessagingStyle extraction

## Current state

This is mostly fixed.

`NotificationTextParts.extract()` now uses:

```kotlin
Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcelables)
```

and extracts:

```kotlin
msg.text?.toString()
```

It also still extracts:
- title,
- text,
- bigText,
- subText,
- infoText,
- summaryText,
- `EXTRA_TEXT_LINES`,
- `EXTRA_MESSAGES`,
- `combinedBody`.

## Remaining gaps

### Gap 1 — fallback uses `item.toString()`

Fallback branch:

```kotlin
else -> item?.toString()?.takeIf { it.isNotBlank() }
```

This can pull object dumps into `combinedBody`.

Even if not persisted under non-raw modes, it can:
- pollute parser input,
- affect dedupe hash,
- include unexpected OEM/app objects.

Recommended:

```kotlin
else -> null
```

### Gap 2 — no whitespace normalization before dedupe

`combinedBody` dedupes exact strings only. Minor whitespace differences can create different content hashes.

### Gap 3 — no `EXTRA_HISTORIC_MESSAGES`

Not required for the original issue, but worth noting for SMS/messaging apps.

## Verdict

```text
P1-P1-03: mostly fixed with safe-fallback caveat.
```

---

## 4. P1-P1-05 / P1-NEW-10 / P1-NEW-11 — Capture gate

## Current state

A new `NotificationCaptureGate` exists and is called before:

```kotlin
val extras = sbn.notification.extras
val parts = NotificationTextParts.extract(extras)
```

That is a major improvement.

Gate checks:
1. restore mode,
2. shutdown,
3. readiness,
4. `notificationCaptureEnabled`,
5. full `PrivacyGate.check(NOTIFICATION_CAPTURE)`,
6. blocked package.

## Serious remaining bugs

### Bug 1 — warm-up is not actually synchronous

Service does:

```kotlin
serviceScope.launch {
    captureGate.warmUp()
}
captureGate.startObservers(serviceScope)
```

This does not guarantee warm-up completes before `onNotificationPosted()`.

So if a notification arrives immediately after service start:

```text
settingsLoaded=false
blockedPackagesLoaded=false
```

gate returns:

```kotlin
TemporarilyUnavailable(GATE_NOT_READY)
```

and service drops it.

This means the startup false-drop issue is **not fully fixed**.

### Bug 2 — partial warm-up failure can allow unsafe capture

Gate readiness condition:

```kotlin
if (!settingsLoaded && !blockedPackagesLoaded) TemporarilyUnavailable
```

This means if **either** settings or blocked packages loaded, gate proceeds.

Bad case A:

```text
settingsLoaded = true
blockedPackagesLoaded = false
```

Then blocked-package check is skipped:

```kotlin
if (blockedPackagesLoaded && packageName in blockedPackages)
```

A blocked package can be allowed.

Bad case B:

```text
settingsLoaded = false
blockedPackagesLoaded = true
```

Then privacy setting disabled check is skipped:

```kotlin
if (settingsLoaded && !notificationCaptureEnabled)
```

The full `PrivacyGate` might allow based on defaults/stale state, so capture can proceed without confirmed notification-capture setting.

Correct rule should be:

```text
settings must be loaded before allow.
blocked-package policy must be loaded or queried one-shot before allow.
```

### Bug 3 — no fallback one-shot package check on cache miss

If `blockedPackagesLoaded=false`, gate should query:

```kotlin
blockedPackageDao.isBlocked(packageName)
```

with timeout.

Current behavior skips blocked check.

### Bug 4 — temporary unavailable mapped to privacy denied

Service maps `TemporarilyUnavailable` to:

```kotlin
DiagnosticReasonCode.PRIVACY_DENIED
```

This is inaccurate and hides startup/cache bugs.

### Bug 5 — `runBlocking` inside listener callback

Service uses:

```kotlin
val gateDecision = runBlocking { captureGate.decide(...) }
```

in `onNotificationPosted()` and refresh path.

This can block the NotificationListenerService callback thread. If `PrivacyGate.check()` or DB-backed gate logic is slow, this risks listener latency/ANR-like behavior.

Better:

```text
Build safe envelope, then launch coroutine, then call suspend gate before extraction.
```

## Verdict

```text
P1-P1-05: partial.
P1-NEW-10: partial/still risky.
P1-NEW-11: partial/still risky.
```

Required follow-up:

```text
Gate must require confirmed settings.
Gate must require blocked-package policy loaded or one-shot checked.
Service must await warm-up or call decide in coroutine with short retry/buffer.
Do not label GATE_NOT_READY as PRIVACY_DENIED.
Remove runBlocking from listener callbacks.
```

---

## 5. P2-08 — Manual refresh dedupe

## Current state

Still open/partial.

`refreshActiveNotifications()` still says:

```kotlin
// Bypass deduplication cache for manual refresh
processNotificationBypassDedupe(sbn)
```

That function duplicates large parts of normal capture logic and does not use the content-aware in-memory dedupe.

Durable fingerprint pre-check in pipeline reduces parser/AI duplicate work **after a DB row exists**, but refresh can still race with an in-flight listener path:

```text
listener processing same notification in-flight
manual refresh runs
refresh bypasses service dedupe
both enter pipeline before raw row exists
```

`processMutex` serializes pipeline, and fingerprint pre-check may catch after the first commits, but this is still not the clean design and still duplicates service code.

## Verdict

```text
P2-08: partial/open.
```

Fix:

```text
Delete processNotificationBypassDedupe.
Make refresh call same capture handler/deduper as listener.
Only source/stage should differ.
```

---

## 6. P2-09 — finance filtering

I did not see enough evidence that the planned structured finance filter v2 landed.

The changed file list from the commit page did not obviously include `NotificationFilter.kt`, while the issue required substantial changes there.

So unless `NotificationFilter.kt` was changed elsewhere in the large diff and not visible in the reviewed slices, assume the old concerns remain:

- finance package may still pass currency-only/balance-only notifications,
- no structured filter decision model,
- no direction-aware incoming/outgoing filtering,
- no robust deny groups.

## Verdict

```text
P2-09: unverified, likely partial.
```

You should specifically inspect:

```bash
git show bdcd0b6 -- app/src/main/java/.../service/NotificationFilter.kt
grep -R "NotificationFilterDecision" app/src/main/java
grep -R "BALANCE_ONLY" app/src/main/java
```

---

## 7. P2-10 — currency fallback

## Current state

Currency regexes were expanded.

Visible regex now includes:
- EUR, USD, GBP,
- CHF, PLN, RON, TRY,
- CAD, AUD, JPY,
- SEK, NOK, DKK,
- HUF, CZK,
- TL, kr, Ft, Kč/Kc.

That is an improvement.

## Still not fixed

Currency resolution still contains fallback logic like:

```kotlin
fullText.contains("$") -> "USD"
else -> "EUR"
```

This means:

- `$` silently becomes USD even for CAD/AUD users,
- ambiguous `kr` is not modeled as SEK/NOK/DKK,
- no home-currency provider,
- no `CurrencyResolution`,
- no confidence/basis metadata,
- no shared detector used by filter + pipeline,
- fallback still eventually defaults to EUR.

## Verdict

```text
P2-10: partial.
```

This is an incremental regex expansion, not the canonical money detector from the plan.

---

## 8. P1-P1-07 — durable intake

## Current state

Schema and DAO were added:

- `NotificationIntakeEntity`
- `NotificationIntakeStatus`
- `NotificationIntakeDao`
- DB version 133
- migration 132→133

This is good foundation work.

## But the actual issue is still open

Service still directly processes notifications:

```kotlin
pipelineOutcome = processNotification(...)
repository.processAndSave(...)
```

There is no visible:
- `NotificationIntakeCoordinator`,
- `NotificationIntakeWorker`,
- WorkManager enqueue,
- recovery scheduler,
- stale `PROCESSING` recovery wired into app start,
- service spool-before-process.

Even `onDestroy()` still comments:

```text
For full durability, a NotificationIntake table is planned
```

So the original failure remains:

```text
listener receives notification
async service work starts
service/process dies before raw DB write
notification may never be reposted
transaction lost
```

## Verdict

```text
P1-P1-07: open.
```

Current status should be:

```text
Schema added only. Runtime durability not implemented.
```

---

## 9. P2-12 — AI provenance

## Current state

The pipeline no longer appears to call deterministic `parse()` a second time after `parseWithAiFallback()`.

That is an improvement.

## Still not fixed

`AppParserRegistry` still exposes:

```kotlin
parseWithAiFallback(...): ParsedTransaction?
```

There is no:

```kotlin
parseWithProvenance(...)
ParseOutcome
ParseProvenance
AiFallbackStatus
ParserAttempt
```

The pipeline comment explicitly says:

```text
A full ParseOutcome contract would carry provenance metadata directly...
```

So the issue is still acknowledged as incomplete.

Diagnostics now say parse completed, but they do not know:

- deterministic vs AI,
- parser ID,
- AI attempted/skipped,
- provider/model,
- confidence basis,
- failure reason.

## Verdict

```text
P2-12: partial.
```

Mark fixed only for:

```text
duplicate deterministic parse removed
```

not for provenance.

---

## 10. P3-13 — service decomposition

## Current state

`NotificationTextParts` was extracted.

That is good.

## Still not decomposed

`NotificationCaptureService.kt` remains about 872 lines and still owns:

- lifecycle,
- foreground service,
- restart alarm,
- refresh logic,
- capture gate call,
- diagnostics,
- extraction orchestration,
- filter,
- in-memory dedupe,
- raw/storage mapping,
- extras JSON building,
- repository invocation,
- duplicated refresh path.

## Verdict

```text
P3-13: partial.
```

This is phase 1 only, not full service decomposition.

---

# New/current bugs introduced or still present

---

## BUG-A — capture gate can allow blocked packages if blocked cache failed

Current gate:

```kotlin
if (blockedPackagesLoaded && packageName in blockedPackages) blocked
return Allowed
```

If blocked packages failed to load:

```text
blockedPackagesLoaded=false
```

then every package is treated as not blocked.

Severity: **P1/P2 privacy/control bypass**

Fix:

```text
If blocked package cache is not loaded:
    query isBlocked(packageName) one-shot.
If that fails:
    return TemporarilyUnavailable(PACKAGE_POLICY_UNAVAILABLE)
```

---

## BUG-B — capture gate can allow capture without loaded privacy settings

Current readiness:

```kotlin
if (!settingsLoaded && !blockedPackagesLoaded) unavailable
```

If blocked packages loaded but settings did not:

```text
settingsLoaded=false
blockedPackagesLoaded=true
```

gate can still proceed to full `PrivacyGate`.

This may bypass explicit `notificationCaptureEnabled=false` if settings load failed.

Severity: **P1 privacy**

Fix:

```text
settingsLoaded must be true before allow.
```

---

## BUG-C — warm-up is asynchronous, so startup false drops remain

`onCreate()` launches warm-up but does not wait.

Immediate notification can hit:

```kotlin
TemporarilyUnavailable(GATE_NOT_READY)
```

and be dropped.

Severity: **P1/P2 reliability**

Fix options:
1. perform blocking/synchronous warm-up before accepting notifications;
2. use application-scope queue/buffer until warm-up completes;
3. call `warmUp()` inside `decide()` with short timeout before returning unavailable.

---

## BUG-D — `runBlocking` in notification listener callback

Both normal and refresh path use:

```kotlin
runBlocking { captureGate.decide(...) }
```

This is risky in a framework callback.

Severity: **P2 reliability/perf**

Fix:

```text
Do not block callback thread.
Build safe envelope, launch coroutine, run suspend gate there before extraction.
```

---

## BUG-E — in-memory dedupe check+put is non-atomic

Current pattern:

```kotlin
val lastProcessed = processedNotifications[dedupeKey]
if (lastProcessed != null && now - lastProcessed < window) return
processedNotifications[dedupeKey] = now
```

The map is synchronized internally, but the check+put sequence is not atomic.

Two rapid callbacks can both pass and both launch.

Severity: **P1/P2 dedupe race**

Fix:

```kotlin
val duplicate = synchronized(processedNotifications) {
    val last = processedNotifications[dedupeKey]
    if (last != null && now - last < DEDUP_WINDOW_MS) true
    else {
        processedNotifications[dedupeKey] = now
        false
    }
}
```

Better: move into `NotificationCaptureDeduper`.

---

## BUG-F — service dedupe key can still drop legitimate identical transactions

Current dedupe key:

```kotlin
"$packageName|$notificationKey|$contentHash"
```

`contentHash` is only:

```kotlin
parts.combinedBody.hashCode()
```

It does **not** include `postTime`.

So two legitimate identical notifications from same bank with same notification key and same body inside 5 seconds can be suppressed.

Severity: **P1 edge-case data loss**

Fix:

```text
Use content fingerprint + postTime bucket or durable intake ID logic.
For in-flight dedupe, same content may suppress only while the first is actively in-flight, not after success unless durable duplicate confirms.
```

---

## BUG-G — content hash is Kotlin/JVM `hashCode()`

`computeNotificationContentHash()` returns:

```kotlin
parts.combinedBody.hashCode()
```

This is a 32-bit hash and collision-prone compared to SHA-256/HMAC.

Severity: **P2**

Fix:

```text
Use stable SHA-256/HMAC digest, not Int hashCode.
```

---

## BUG-H — raw package/key stored in dedupe key

Dedupe key stores:

```kotlin
"${packageName}|${notificationKey}|${contentHash}"
```

This is in memory only, so not a persistence leak, but it contradicts the planned “hashed key only” design.

Severity: **P3/P2 privacy hardening**

Fix:

```text
Use packageHash + notificationKeyHash + contentFingerprint.
```

---

## BUG-I — `insertOrIgnore` typed result not implemented

Despite the issue plan, pipeline still uses:

```kotlin
private suspend fun insertRawNotificationIfNotDuplicate(...): Long
```

and checks:

```kotlin
if (rawId == -1L)
```

This appears in multiple places.

Severity: **P2**

Fix:

```kotlin
sealed interface RawNotificationInsertResult {
    data class Inserted(val rawId: Long) : RawNotificationInsertResult
    data class Duplicate(...) : RawNotificationInsertResult
}
```

---

## BUG-J — null fingerprint path is still broken for insert

`processInternal()` computes fallback:

```kotlin
val dedupeFingerprint = notification.dedupeFingerprint ?: compute(...)
```

but `insertRawNotificationIfNotDuplicate()` does:

```kotlin
val fingerprint = notification.dedupeFingerprint
...
dao.insertOrIgnore(storageNotification.copy(
    dedupeFingerprint = notification.dedupeFingerprint
))
```

So if incoming `notification.dedupeFingerprint == null`:

- pre-check computes local fingerprint,
- insert helper sees null,
- raw-field legacy path may run,
- inserted storage row keeps null fingerprint.

Listener path sets fingerprint, so normal capture is mostly safe. But public/batch paths with null fingerprint remain weak.

Severity: **P1/P2 for batch/public path**

Fix:

```kotlin
val fingerprint = notification.resolvedDedupeFingerprint()
dao.insertOrIgnore(storageNotification.copy(dedupeFingerprint = fingerprint))
```

---

## BUG-K — batch/public paths still bypass storage sanitization

`processAndSave(notification)` uses same raw notification for processing and storage.

`processAndSaveAll()` uses raw input list.

So non-listener entrypoints can still persist raw title/text/body/extras.

Severity: **P1 privacy if these paths are reachable from user/debug/import flows**

Fix:

```text
Make repository load RawStorageMode and sanitize for every public entrypoint,
or restrict raw overloads to internal/test only.
```

---

## BUG-L — refresh path still bypasses service dedupe

Already covered under P2-08, but this is important enough to repeat.

Severity: **P1/P2 duplicate work/race**

Fix:

```text
Refresh must call same capture handler/deduper as listener.
```

---

## BUG-M — no worker/recovery uses the intake table

Schema-only intake can create false confidence.

Severity: **P1 if tracker marks P1-P1-07 fixed**

Fix:

```text
Add NotificationIntakeCoordinator + Worker + RecoveryScheduler or mark P1-P1-07 open.
```

---

## BUG-N — location issue is not cleanly fixed

I did not find a direct `getLastKnownLocation()` call in visible slices, which is good.

But `NotificationProcessingPipeline` still injects:

```kotlin
ForegroundLocationProvider
```

and `PreDbContext` still contains:

```kotlin
deviceGps: Pair<Double, Double>?
```

So the dependency/model still suggests location enrichment.

Severity: **P2 cleanup / P1 if hidden tail code uses it**

Fix:

```text
Remove ForegroundLocationProvider injection from notification pipeline.
Remove deviceGps from PreDbContext or set via explicit no-op NotificationLocationContextProvider.
```

---

## BUG-O — source-link failure diagnostics are added, but no typed result

`writeNotificationDedupeSourceLink()` now catches and emits `SOURCE_LINK_FAILED`.

That is better than silent swallowing.

But it still returns `Unit`; no caller can know source-link failed.

Severity: **P2**

Fix:

```kotlin
sealed interface SourceLinkWriteResult
```

---

## BUG-P — audit raw-title issue needs verification

Visible KDoc still says auto-accept `TransactionEvent` contains a sanitized notification payload including `title`.

That may be stale, but it is a warning sign.

Need grep:

```bash
grep -R "notification.title" app/src/main/java/com/yourname/expensetracker/data/repository
grep -R "notificationTitle" app/src/main/java
grep -R "auditReason" app/src/main/java
```

If any audit/event JSON still includes raw/sanitized title, ensure it respects `RawStorageMode`.

Severity: **P1/P2 depending actual code**

---

## BUG-Q — `blockPackage` / `unblockPackage` still lack write barrier

Repository still has:

```kotlin
suspend fun blockPackage(packageName: String) =
    blockedPackageDao.block(BlockedPackage(packageName))

suspend fun unblockPackage(packageName: String) =
    blockedPackageDao.unblock(packageName)
```

No `writeBarrier.checkWritesAllowed(...)`.

Severity: **P2 restore/write-safety**

Fix:

```kotlin
writeBarrier.checkWritesAllowed("NotificationRepository.blockPackage")
```

Same for unblock.

---

## BUG-R — `restoreSourceStatsSnapshot` still lacks write barrier

Visible code:

```kotlin
suspend fun restoreSourceStatsSnapshot(stats: List<SourceStats>) {
    database.withTransaction {
        sourceStatsDao.deleteAll()
        sourceStatsDao.insertAll(stats)
    }
}
```

No barrier.

Severity: **P2**

Fix:

```kotlin
writeBarrier.checkWritesAllowed("NotificationRepository.restoreSourceStatsSnapshot")
```

---

# Issue-by-issue final classification

## Safe to mark fixed

These can probably be marked fixed after tests pass:

| ID | Status |
|---|---|
| P1-P1-01 | Fixed |
| P1-NEW-09 | Fixed with cancellation caveat |
| P1-NEW-19 | Fixed |
| P1-P1-03 | Mostly fixed; fix unsafe fallback first if strict |

---

## Mark partial, not fixed

| ID | Why |
|---|---|
| P1-P1-02 | diagnostics are safer, but service cancellation/null launch gaps remain |
| P1-P1-05 | gate exists, but readiness/partial-load bugs remain |
| P2-08 | refresh still bypasses dedupe |
| P2-09 | no evidence of structured finance detector |
| P2-10 | expanded regex but no canonical detector/ambiguity policy |
| P2-11 | listener fixed, public/batch paths not |
| P2-12 | no typed provenance |
| P3-13 | only text extraction moved |
| P1-NEW-10 | blocked cache failure can allow blocked package |
| P1-NEW-11 | async warm-up can still false-drop |
| P1-NEW-12 | listener mostly fixed, null-fingerprint paths weak |
| P1-NEW-15 | listener fixed, public/batch paths need audit |
| P1-NEW-16 | likely no direct read, but dependency/model remain |
| P1-NEW-18 | diagnostic added, typed result missing |
| P1-NEW-20 | content-aware but racy/weak hash |
| P1-NEW-21 | immediate repeat improved, but lifecycle still crude |

---

## Mark open / not fixed

| ID | Why |
|---|---|
| P1-P1-07 | schema only; no worker/coordinator/runtime durable flow |
| P1-NEW-13 | sentinel `-1L` still used |
| P1-NEW-14 | no clear mark/deprecate behavior |
| P1-NEW-17 | needs grep; KDoc still concerning |

---

# Recommended next patch set

Do **not** start a new big sweep. Do a tight repair PR.

## Repair PR A — capture gate correctness

Fixes:
- P1-P1-05 remaining,
- P1-NEW-10,
- P1-NEW-11.

Tasks:

1. Remove `runBlocking` from listener callbacks.
2. Make `decide()` require `settingsLoaded == true`.
3. If blocked packages not loaded, perform one-shot `isBlocked(packageName)` with timeout.
4. If settings cannot load, return `TemporarilyUnavailable(PRIVACY_SETTINGS_NOT_READY)` or fail-closed explicitly.
5. Do not map temporary unavailable to `PRIVACY_DENIED`.
6. On `onCreate`, either await warm-up safely or buffer notifications until ready.

Acceptance:

```text
No extras extraction unless settings loaded and package policy checked.
Blocked-package DB load failure cannot allow capture.
Startup-not-ready is distinct from privacy denied.
```

---

## Repair PR B — refresh path unification

Fixes:
- P2-08,
- part of P1-NEW-20/21.

Tasks:

1. Delete `processNotificationBypassDedupe`.
2. Extract shared `handleNotificationCapture(sbn, source)`.
3. Listener and refresh both use same deduper.
4. Source/stage differs only in diagnostics.
5. Use atomic dedupe check+put.

Acceptance:

```text
Manual refresh cannot bypass in-memory dedupe.
Listener in-flight + refresh same notification => one processing run.
```

---

## Repair PR C — typed insert + resolved fingerprint

Fixes:
- P1-NEW-12 caveat,
- P1-NEW-13.

Tasks:

1. Add `RawNotificationInsertResult`.
2. Replace all `rawId == -1L`.
3. Always resolve fingerprint from processing notification.
4. Always store resolved fingerprint in storage notification.
5. Map insert conflict to typed duplicate.

Acceptance:

```text
No pipeline code checks -1L.
Null incoming fingerprint still stores computed fingerprint.
Duplicate insert conflict is typed Duplicate.
```

---

## Repair PR D — durable intake runtime

Fixes:
- P1-P1-07.

Tasks:

1. Add `NotificationIntakeCoordinator`.
2. Listener writes intake row before parser/pipeline.
3. Add `NotificationIntakeWorker`.
4. Add recovery scheduler for stale `RECEIVED/PROCESSING`.
5. Update `onDestroy()` comment.

Acceptance:

```text
Service death after accepted notification does not lose it.
Schema is actually used.
```

---

## Repair PR E — parser provenance

Fixes:
- P2-12.

Tasks:

1. Add `ParseOutcome`.
2. Add `parseWithProvenance()`.
3. Replace `parseWithAiFallback()` in pipeline.
4. Emit provider/status/confidence/failure reason.
5. Deprecate old API.

Acceptance:

```text
Every outcome can say deterministic vs AI vs skipped/failed.
No duplicate parse.
```

---

## Repair PR F — currency detector

Fixes:
- P2-10.

Tasks:

1. Replace regex-only fallback with shared money detector.
2. Add home-currency provider.
3. Model ambiguous `$` / `kr`.
4. Remove `else -> "EUR"` unless default/home currency is explicitly EUR.
5. Add tests for PLN/RON/TRY/CAD/AUD/JPY/CHF/SEK/NOK/DKK/HUF/CZK.

Acceptance:

```text
Non-EUR fallback never silently becomes EUR.
Ambiguous symbols produce explicit resolution basis.
```

---

# Test cases to add immediately

## Gate tests

1. settings loaded, blocked package loaded, allowed => extraction called.
2. settings not loaded, blocked loaded => extraction not called.
3. settings loaded, blocked not loaded, one-shot says blocked => extraction not called.
4. settings loaded, blocked not loaded, one-shot fails => extraction not called, reason policy unavailable.
5. immediate notification after service start => not fake `PRIVACY_DENIED`.

## Dedupe tests

1. same key + different body => both processed.
2. same key + same body simultaneous => one processed.
3. same key + same body after success immediate => suppressed.
4. same key + same body after retryable error => retry allowed.
5. manual refresh while listener in-flight => suppressed.
6. hash collision simulation => does not drop distinct content if using strong digest.

## Fingerprint tests

1. metadata-only stored row + same raw processing notification => duplicate before parser.
2. null incoming fingerprint => computed and stored.
3. `insertOrIgnore` conflict => typed duplicate.
4. no `-1L` checks outside helper.

## Intake tests

1. listener inserts intake row before pipeline.
2. service destroyed after intake insert => worker later processes.
3. stale processing row released.
4. duplicate fingerprint intake insert => terminal duplicate.
5. `DO_NOT_STORE` behavior documented and tested.

## Provenance tests

1. deterministic parser success => source deterministic.
2. generic parser success => source generic.
3. deterministic fail + AI success => source AI.
4. AI skipped => status skipped.
5. AI exception => failure reason.

---

# Suggested tracker update now

Do **not** mark the sweep as fully done.

Recommended tracker statuses after `bdcd0b6`:

| ID | Status |
|---|---:|
| P1-P1-01 | ✅ Fixed |
| P1-P1-02 | ⚠ Mostly fixed |
| P1-P1-03 | ✅ Mostly fixed |
| P1-P1-05 | ⚠ Partial |
| P1-P1-06 | ✅ Mostly fixed |
| P1-P1-07 | ❌ Open — schema only |
| P2-08 | ⚠ Partial/open |
| P2-09 | ⚠ Unverified/partial |
| P2-10 | ⚠ Partial |
| P2-11 | ⚠ Partial |
| P2-12 | ⚠ Partial |
| P3-13 | ⚠ Partial |
| P1-NEW-09 | ✅ Mostly fixed |
| P1-NEW-10 | ⚠ Partial |
| P1-NEW-11 | ⚠ Partial |
| P1-NEW-12 | ⚠ Mostly fixed |
| P1-NEW-13 | ❌ Open |
| P1-NEW-14 | ❌ Open / unverified |
| P1-NEW-15 | ✅ Mostly fixed |
| P1-NEW-16 | ⚠ Partial cleanup |
| P1-NEW-17 | ⚠ Needs verification |
| P1-NEW-18 | ⚠ Partial |
| P1-NEW-19 | ✅ Mostly fixed |
| P1-NEW-20 | ⚠ Partial |
| P1-NEW-21 | ⚠ Partial |

---

# Final conclusion

The commit is a **strong hardening sweep**, but it overclaims completion.

The most important correction is:

```text
P1-P1-07 is not fixed.
```

You added the intake schema, but the runtime capture path still does:

```text
NotificationListenerService -> service coroutine -> repository.processAndSave()
```

not:

```text
NotificationListenerService -> durable intake row -> WorkManager/recovery -> pipeline
```

The second most important correction is:

```text
CaptureGate is conceptually right but has dangerous readiness semantics.
```

It should not allow capture when only one of privacy settings or blocked-package policy loaded.

The third most important correction is:

```text
Refresh still bypasses in-memory dedupe.
```

That keeps P2-08 alive and weakens the new dedupe improvements.

If you do only one next PR, do:

```text
Repair PR A + B:
- fix CaptureGate readiness,
- remove runBlocking,
- unify listener/refresh path,
- atomic content-aware dedupe.
```

Then do typed insert/fingerprint cleanup, then durable intake runtime.