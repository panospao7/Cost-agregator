# Dedicated implementation plan — P1-NEW-19, P1-NEW-20, P1-NEW-21

Target issues:

| ID | Severity | Theme |
|---|---:|---|
| P1-NEW-19 | P2 | Pipeline diagnostic failures are swallowed |
| P1-NEW-20 | P1 | Service in-memory dedupe remains `sbn.key`-coarse |
| P1-NEW-21 | P1/P2 | Successful dedupe key is removed immediately |

Context: Pipeline 1 notification capture/processing around commit `e781c226862234ed412914884e98d22165a41a95`.

Recommended split:

1. **PR 1 — Safe pipeline diagnostic emission**
2. **PR 2 — Content-aware notification capture deduper**
3. **PR 3 — Outcome-aware dedupe lifecycle / retain success keys**
4. **PR 4 — Regression tests + tracker/docs update**

---

# Dependency notes

These fixes are easiest after:

| Dependency | Why |
|---|---|
| P1-P1-01 outcome return | Service needs real `NotificationPipelineOutcome` to decide whether to retain or release dedupe keys. |
| P1-P1-02 / P1-NEW-09 safe diagnostic emitter | P1-NEW-19 should reuse the same safe diagnostic path. |
| P1-P1-05 capture gate | Content-aware dedupe must happen after privacy/package gate, not before extraction. |
| P2-08 fingerprint duplicate check | After in-memory TTL expires, durable fingerprint dedupe should prevent parser/AI repeat work. |

If these are not implemented yet, this plan can still be built, but it should introduce temporary adapters that can be deleted later.

---

# PR 1 — P1-NEW-19: Safe pipeline diagnostic emission

## Current problem

`NotificationProcessingPipeline.writePipelineDiagnosticEvent(...)` wraps writes in something like:

```kotlin
runCatching {
    diagnosticEventWriter.emit(event)
}
```

or otherwise catches and ignores diagnostic failures.

Risk:

- pipeline result exists, but no diagnostic explains it;
- Room failures/restore mode can make diagnostics disappear;
- debugging says “we emit outcome events,” but the event may be silently lost;
- failures in provenance/source-link/parse diagnostics can hide real bugs.

## Goal

Pipeline diagnostic writes should be:

```text
best-effort but observable:
- normal mode: write through DiagnosticEventWriter
- writer failure: fallback to MaintenanceSafeDiagnosticSink
- restore/maintenance mode: use MaintenanceSafeDiagnosticSink directly
- sink failure: log safe warning, do not crash pipeline
```

No pipeline diagnostic failure should be silently swallowed.

---

## Files to modify

Primary:

- `NotificationProcessingPipeline.kt`
- existing diagnostic writer/emitter classes
- `MaintenanceSafeDiagnosticSink.kt` usage sites

New file if not already present:

```text
domain/diagnostics/SafeDiagnosticEmitter.kt
```

or notification-specific:

```text
domain/diagnostics/NotificationDiagnosticEmitter.kt
```

Tests:

```text
SafeDiagnosticEmitterTest.kt
NotificationProcessingPipelineDiagnosticFailureTest.kt
```

---

## Step 1.1 — Create generic safe diagnostic result

Create:

```kotlin
sealed interface DiagnosticEmitResult {
    data object Written : DiagnosticEmitResult

    data class WrittenToMaintenanceSink(
        val mode: RestoreMaintenanceMode.Mode,
        val originalWriterFailed: Boolean
    ) : DiagnosticEmitResult

    data class DroppedAfterSinkFailure(
        val mode: RestoreMaintenanceMode.Mode,
        val writerFailureClass: String?,
        val sinkFailureClass: String
    ) : DiagnosticEmitResult
}
```

Purpose:

- tests can assert what happened;
- callers do not need to catch writer exceptions;
- no silent `runCatching`.

---

## Step 1.2 — Implement `SafeDiagnosticEmitter`

```kotlin
@Singleton
class SafeDiagnosticEmitter @Inject constructor(
    private val writer: DiagnosticEventWriter,
    private val maintenanceSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun emit(event: DiagnosticEvent): DiagnosticEmitResult =
        withContext(ioDispatcher) {
            val mode = restoreMaintenanceMode.currentMode()

            if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
                return@withContext emitToSink(
                    event = event,
                    mode = mode,
                    writeFailure = null
                )
            }

            try {
                writer.emit(event)
                DiagnosticEmitResult.Written
            } catch (writerFailure: Throwable) {
                emitToSink(
                    event = event,
                    mode = mode,
                    writeFailure = writerFailure
                )
            }
        }

    suspend fun emitNonCancellable(event: DiagnosticEvent): DiagnosticEmitResult =
        withContext(NonCancellable + ioDispatcher) {
            emit(event)
        }

    private suspend fun emitToSink(
        event: DiagnosticEvent,
        mode: RestoreMaintenanceMode.Mode,
        writeFailure: Throwable?
    ): DiagnosticEmitResult {
        return try {
            maintenanceSink.recordDiagnosticEvent(
                event = event,
                mode = mode,
                writeFailure = writeFailure
            )
            DiagnosticEmitResult.WrittenToMaintenanceSink(
                mode = mode,
                originalWriterFailed = writeFailure != null
            )
        } catch (sinkFailure: Throwable) {
            Timber.w(
                sinkFailure,
                "Diagnostic fallback sink failed: pipeline=%s stage=%s outcome=%s",
                event.pipeline,
                event.stage,
                event.outcome
            )

            DiagnosticEmitResult.DroppedAfterSinkFailure(
                mode = mode,
                writerFailureClass = writeFailure?.javaClass?.simpleName,
                sinkFailureClass = sinkFailure.javaClass.simpleName ?: "Unknown"
            )
        }
    }
}
```

If `NotificationDiagnosticEmitter` already exists from P1-NEW-09, reuse it instead of adding a new class. But pipeline-level code should depend on a safe emitter, not raw `DiagnosticEventWriter`.

---

## Step 1.3 — Replace pipeline direct writer dependency

In `NotificationProcessingPipeline`, replace:

```kotlin
private val diagnosticEventWriter: DiagnosticEventWriter
```

with:

```kotlin
private val diagnosticEmitter: SafeDiagnosticEmitter
```

or:

```kotlin
private val notificationDiagnosticEmitter: NotificationDiagnosticEmitter
```

Then change:

```kotlin
private suspend fun writePipelineDiagnosticEvent(...) {
    runCatching {
        diagnosticEventWriter.emit(event)
    }
}
```

to:

```kotlin
private suspend fun writePipelineDiagnosticEvent(...) {
    val event = buildPipelineDiagnosticEvent(...)

    val result = diagnosticEmitter.emit(event)

    if (result is DiagnosticEmitResult.DroppedAfterSinkFailure) {
        Timber.w(
            "Pipeline diagnostic dropped after sink failure: stage=%s outcome=%s writerFailure=%s sinkFailure=%s",
            event.stage,
            event.outcome,
            result.writerFailureClass,
            result.sinkFailureClass
        )
    }
}
```

Important:

- do not throw diagnostic failure into user pipeline result;
- but do not silently ignore it.

---

## Step 1.4 — Avoid recursive diagnostic failure events

Do **not** emit a new `DIAGNOSTIC_WRITE_FAILED` diagnostic through the same emitter after emitter failure, because that can recurse.

Allowed:

- safe `Timber.w`;
- fallback sink record;
- optional in-memory counter/metric.

If you have a separate crash-safe local logger, you may write there.

---

## Step 1.5 — Replace all pipeline `runCatching` diagnostic blocks

Search:

```bash
grep -R "runCatching" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "diagnosticEventWriter" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "writePipelineDiagnosticEvent" app/src/main/java
```

Rules:

- `runCatching` may remain for genuinely best-effort side effects, but not for silent diagnostic writes.
- diagnostic writer failures should route through `SafeDiagnosticEmitter`.

---

## Step 1.6 — Extend to parser/source-link diagnostics where local

If parser provenance and source-link failure diagnostics are built in the pipeline, route them through the safe emitter too.

Do not keep mixed behavior where outcome diagnostics are safe but parse diagnostics are silently swallowed.

---

## PR 1 tests

### Safe emitter tests

1. Normal mode + writer succeeds:
   - result = `Written`;
   - sink not called.

2. Normal mode + writer throws:
   - result = `WrittenToMaintenanceSink(originalWriterFailed=true)`;
   - sink receives original event and failure.

3. Restore mode:
   - writer not called;
   - sink receives event;
   - result = `WrittenToMaintenanceSink(originalWriterFailed=false)`.

4. Sink throws:
   - no exception escapes;
   - result = `DroppedAfterSinkFailure`;
   - warning log is safe.

5. Cancellation path:
   - `emitNonCancellable` still attempts write.

### Pipeline tests

1. Pipeline emits terminal event successfully.
2. Writer failure does not change pipeline outcome.
3. Writer failure records event in maintenance sink.
4. Restore mode does not call Room writer.
5. Sink failure does not crash pipeline.
6. No raw notification title/text/body appears in fallback event metadata.

## PR 1 acceptance criteria

- `NotificationProcessingPipeline` no longer swallows diagnostic writer failures.
- Pipeline diagnostics use a safe emitter.
- Writer failures fallback to `MaintenanceSafeDiagnosticSink`.
- Restore/maintenance mode avoids normal Room diagnostic writes.
- Diagnostic failure does not crash user processing.
- Tests cover writer success, writer failure, restore mode, sink failure.

---

# PR 2 — P1-NEW-20: Content-aware notification capture deduper

## Current problem

Current service dedupe uses something like:

```kotlin
val coarseDedupeKey = sbn.key
processedNotifications[coarseDedupeKey] = now
```

This is too coarse.

Android/bank apps can reuse the same status-bar key/tag/ID for different transactions. If two different notification bodies arrive within the dedupe window, the second can be dropped before content is inspected.

Bad sequence:

```text
1. Bank posts notification key=bank:42, body="Paid €10 at Shop A"
2. Service marks key bank:42 as in-flight
3. Bank updates/reuses same key, body="Paid €25 at Shop B"
4. Service sees same key bank:42
5. Second distinct transaction is dropped as duplicate
```

## Goal

Use content-aware dedupe after privacy/package gate and text extraction.

New rule:

```text
Do not dedupe solely by sbn.key.
Dedupe key must include package + notification key hash + canonical content fingerprint.
```

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`

New files:

```text
domain/notification/capture/NotificationCaptureDeduper.kt
domain/notification/capture/NotificationDedupeKey.kt
domain/notification/capture/NotificationContentFingerprint.kt
```

Tests:

```text
NotificationCaptureDeduperTest.kt
NotificationCaptureServiceDedupeTest.kt
```

---

## Step 2.1 — Define content fingerprint

Create:

```kotlin
object NotificationContentFingerprint {
    fun compute(
        packageName: String,
        title: String?,
        text: String?,
        combinedBody: String?,
        postTime: Long?
    ): String
}
```

Recommended canonical input:

```text
packageName
normalized(title)
normalized(text)
normalized(combinedBody)
```

Do **not** include raw notification key inside the content hash; key belongs in the dedupe key separately.

Recommended normalization:

```kotlin
private fun normalize(value: String?): String =
    value.orEmpty()
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()
```

Do not store raw content in memory maps. Store only the hash.

Hash:

- use SHA-256 or existing app safe hash utility;
- if available, prefer app HMAC/safe hash helper.

---

## Step 2.2 — Define service dedupe key

```kotlin
data class NotificationDedupeKey(
    val packageNameHash: String,
    val notificationKeyHash: String?,
    val contentFingerprint: String
)
```

Why no raw package/key?

- package name may be acceptable in logs, but use hash to be consistent;
- notification key can contain package/user/id/tag details;
- content fingerprint avoids raw body retention.

Should `postTime` be included?

Recommended: **not in the primary key**.

Reason:

- duplicate callbacks may have slightly different post times;
- including postTime weakens duplicate suppression;
- distinct reused-key transactions are already separated by content fingerprint.

If you need extra safety against suppressing two identical-content legitimate transactions, include a short time bucket only in the **completed-success TTL key**, not the in-flight key. But start simple:

```text
package + notificationKey + content fingerprint
```

---

## Step 2.3 — Create `NotificationCaptureDeduper`

```kotlin
@Singleton
class NotificationCaptureDeduper @Inject constructor(
    private val timeProvider: TimeProvider
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<NotificationDedupeKey, DedupeEntry>()

    suspend fun tryStart(key: NotificationDedupeKey): DedupeStartResult

    suspend fun finish(
        key: NotificationDedupeKey,
        outcome: NotificationPipelineOutcome?,
        phase: DedupeFinishPhase
    )

    suspend fun cleanupExpired(nowMs: Long = timeProvider.nowMillis())
}
```

Models:

```kotlin
sealed interface DedupeStartResult {
    data object Started : DedupeStartResult

    data class DuplicateInFlight(
        val firstSeenAt: Long
    ) : DedupeStartResult

    data class DuplicateRecentlyCompleted(
        val completedAt: Long,
        val expiresAt: Long,
        val outcomeSummary: String?
    ) : DedupeStartResult
}
```

```kotlin
private sealed interface DedupeEntry {
    val createdAt: Long

    data class InFlight(
        override val createdAt: Long
    ) : DedupeEntry

    data class Completed(
        override val createdAt: Long,
        val completedAt: Long,
        val expiresAt: Long,
        val outcomeSummary: String?
    ) : DedupeEntry
}
```

For PR 2, you can implement only `InFlight`. PR 3 adds `Completed`. But adding both now is fine if P1-P1-01 outcome return is ready.

---

## Step 2.4 — Move dedupe after privacy/package gate and extraction

Current flow roughly:

```text
restore/privacy/package fast checks
coarse sbn.key dedupe
extract text
filter
process
```

New flow:

```text
1. restore/shutdown/capture gate
2. package/privacy allowed
3. extract text
4. build content-aware dedupe key
5. tryStart(key)
6. if duplicate -> emit duplicate diagnostic and return
7. filter
8. repository/pipeline
9. finish(key, outcome)
```

Important privacy rule:

```text
Do not compute content hash before privacy/package gate allows extras/text extraction.
```

If P1-P1-05 full capture gate is not done yet, at least preserve the existing fast gate order and do not move extraction earlier.

---

## Step 2.5 — Replace `processedNotifications` map

Remove or stop using:

```kotlin
processedNotifications: MutableMap<String, Long>
coarseDedupeKey = notificationKey
processedNotifications.remove(coarseDedupeKey)
```

Replace with:

```kotlin
val dedupeKey = notificationDedupeKeyFactory.from(
    envelope = envelope,
    textParts = textParts
)

when (val start = deduper.tryStart(dedupeKey)) {
    Started -> continue
    DuplicateInFlight -> emit duplicate terminal and return
    DuplicateRecentlyCompleted -> emit duplicate terminal and return
}
```

---

## Step 2.6 — Add dedupe key factory

Create:

```kotlin
class NotificationDedupeKeyFactory @Inject constructor(
    private val safeHash: SafeHashProvider
) {
    fun create(
        packageName: String,
        notificationKey: String?,
        title: String?,
        text: String?,
        combinedBody: String?
    ): NotificationDedupeKey {
        return NotificationDedupeKey(
            packageNameHash = safeHash.hash(packageName),
            notificationKeyHash = notificationKey?.let(safeHash::hash),
            contentFingerprint = NotificationContentFingerprint.compute(
                packageName = packageName,
                title = title,
                text = text,
                combinedBody = combinedBody,
                postTime = null
            )
        )
    }
}
```

If no `SafeHashProvider` exists, wrap existing hash helper. Do not use Kotlin `hashCode()` because it is not stable.

---

## Step 2.7 — Diagnostics

When deduper rejects:

```text
stage = "capture_dedupe"
outcome = DUPLICATE
reasonCode = DUPLICATE
terminal = true
metadata:
  dedupeBasis = IN_FLIGHT_CONTENT_KEY or RECENT_COMPLETION_CONTENT_KEY
  source = LISTENER / REFRESH
  hasNotificationKey = true/false
```

Do not include:

- raw package name unless allowed;
- raw notification key;
- raw content hash if considered sensitive;
- title/text/body.

---

## PR 2 tests

### Deduper unit tests

1. same key first call => `Started`.
2. same key second call before finish => `DuplicateInFlight`.
3. same `sbn.key` but different content fingerprint => both `Started`.
4. different `sbn.key` but same content => both started or configurable; recommended both started because notification key differs.
5. expired in-flight entry is cleaned up.
6. max-size cleanup evicts oldest entries.

### Service tests

1. same `sbn.key`, different title/body within 5 seconds:
   - both are processed;
   - no duplicate drop for second.

2. same `sbn.key`, same body while first in-flight:
   - second is duplicate;
   - repository called once.

3. dedupe happens after privacy gate:
   - privacy denied => extractor not called;
   - deduper not called.

4. dedupe key contains no raw body/key in stored map.

## PR 2 acceptance criteria

- Service no longer dedupes by `sbn.key` alone.
- Same notification key with different content is not dropped.
- Same notification key with same content while in-flight is suppressed.
- Deduper stores hashes/fingerprints, not raw notification text.
- Diagnostics explain content-aware duplicate basis.

---

# PR 3 — P1-NEW-21: Retain successful dedupe keys until TTL

## Current problem

Current code removes the dedupe key in `finally`:

```kotlin
finally {
    processedNotifications.remove(coarseDedupeKey)
}
```

This means in-memory dedupe only protects while work is in-flight.

Bad sequence:

```text
1. Notification callback A processed successfully.
2. finally removes dedupe key.
3. Android immediately sends callback B for same active notification.
4. Service processes again.
5. DB duplicate may catch later, but parser/AI/work can repeat.
```

## Goal

After terminal successful or final outcomes, retain the content-aware dedupe key until TTL expires.

Rule:

```text
Remove key immediately only when retry should be allowed.
Retain key when duplicate callbacks should be suppressed.
```

---

## Files to modify

Primary:

- `NotificationCaptureDeduper.kt`
- `NotificationCaptureService.kt`

Depends on:

- repository returns `NotificationPipelineOutcome`;
- PR 2 content-aware dedupe key exists.

---

## Step 3.1 — Define TTL constants

Recommended:

```kotlin
private const val IN_FLIGHT_STALE_TTL_MS = 2 * 60 * 1000L
private const val COMPLETED_SUCCESS_TTL_MS = 30 * 1000L
private const val COMPLETED_FINAL_DROP_TTL_MS = 10 * 1000L
private const val MAX_DEDUPE_ENTRIES = 1_000
```

Rationale:

- in-flight stale TTL prevents permanent blocks if coroutine dies oddly;
- success TTL suppresses immediate duplicate callbacks;
- final-drop TTL suppresses spammy repeated notifications briefly;
- bounded map prevents memory growth.

Make TTLs injectable/testable if possible.

---

## Step 3.2 — Classify finish behavior by outcome

Add:

```kotlin
enum class DedupeFinishAction {
    RETAIN_COMPLETED,
    RELEASE_FOR_RETRY,
    RELEASE_CANCELLED_BEFORE_PROCESSING
}
```

Classifier:

```kotlin
object NotificationDedupeOutcomeClassifier {
    fun classify(
        outcome: NotificationPipelineOutcome?,
        failure: Throwable?,
        repositoryStarted: Boolean
    ): DedupeFinishAction
}
```

Suggested mapping:

| Situation | Action |
|---|---|
| `AutoAccepted` | retain completed |
| `NeedsReview` | retain completed |
| `Duplicate` | retain completed, shorter TTL ok |
| `ParserFailed` final | retain completed |
| `AutoRejected` final | retain completed |
| `Dropped` final | retain completed |
| `Error` retryable/transient | release for retry |
| exception before repository starts | release for retry |
| `CancellationException` before durable insert | release for retry |
| cancellation after durable intake/raw insert | retain or rely on durable intake; depends on outcome |
| unknown outcome and unknown failure | release for retry |

If `NotificationPipelineOutcome.Error` does not classify retryable/non-retryable yet, add helper:

```kotlin
fun NotificationPipelineOutcome.Error.isRetryable(): Boolean
```

Conservative default:

```text
Error => release for retry
```

---

## Step 3.3 — Implement `finish`

In deduper:

```kotlin
suspend fun finish(
    key: NotificationDedupeKey,
    outcome: NotificationPipelineOutcome?,
    failure: Throwable?,
    repositoryStarted: Boolean
) {
    mutex.withLock {
        val now = timeProvider.nowMillis()

        when (NotificationDedupeOutcomeClassifier.classify(outcome, failure, repositoryStarted)) {
            RETAIN_COMPLETED -> {
                entries[key] = DedupeEntry.Completed(
                    createdAt = entries[key]?.createdAt ?: now,
                    completedAt = now,
                    expiresAt = now + ttlFor(outcome),
                    outcomeSummary = outcome?.javaClass?.simpleName
                )
            }

            RELEASE_FOR_RETRY,
            RELEASE_CANCELLED_BEFORE_PROCESSING -> {
                entries.remove(key)
            }
        }

        cleanupExpiredLocked(now)
        enforceMaxSizeLocked()
    }
}
```

`tryStart` should treat:

- `InFlight` and not stale => duplicate in-flight;
- stale `InFlight` => replace with new in-flight;
- `Completed` and not expired => duplicate recently completed;
- expired `Completed` => replace with new in-flight.

---

## Step 3.4 — Update service coroutine lifecycle

Current structure likely:

```kotlin
processedNotifications[key] = now

serviceScope.launch {
    try {
        processNotification(...)
    } finally {
        processedNotifications.remove(key)
    }
}
```

New structure:

```kotlin
val start = deduper.tryStart(dedupeKey)
if (start !is Started) {
    emitDuplicateDiagnostic(start)
    return
}

var outcome: NotificationPipelineOutcome? = null
var failure: Throwable? = null
var repositoryStarted = false

try {
    repositoryStarted = true
    outcome = repository.processAndSave(...)
} catch (t: Throwable) {
    failure = t
    throw t
} finally {
    deduper.finish(
        key = dedupeKey,
        outcome = outcome,
        failure = failure,
        repositoryStarted = repositoryStarted
    )
}
```

Important:

- Do not remove key blindly.
- Do not swallow `CancellationException`.
- Use returned outcome from repository.
- If repository still returns `Unit`, this PR should wait for P1-P1-01 or use temporary `RepositoryProcessResult`.

---

## Step 3.5 — Duplicate completed diagnostic

When `tryStart` returns `DuplicateRecentlyCompleted`:

```text
stage = "capture_dedupe"
outcome = DUPLICATE
reasonCode = DUPLICATE
terminal = true
metadata:
  duplicateBasis = RECENT_SUCCESS_TTL
  completedOutcome = AutoAccepted/NeedsReview/etc
  ttlRemainingMsBucket = "<5s" / "5-30s"
```

Do not store exact raw content/fingerprint.

---

## Step 3.6 — Periodic cleanup

Deduper should clean on every `tryStart`.

Optional service cleanup:

```kotlin
serviceScope.launch {
    while (isActive) {
        delay(60_000)
        deduper.cleanupExpired()
    }
}
```

Not strictly necessary if cleanup happens during `tryStart`, but useful for long-running listener service.

---

## Step 3.7 — Manual refresh behavior

Refresh should use same deduper.

Expected behavior:

```text
listener processes notification successfully
manual refresh immediately sees same active notification
refresh is suppressed as DuplicateRecentlyCompleted
```

After TTL expires:

```text
refresh may pass service deduper, but durable fingerprint duplicate should catch before parser/AI
```

This is acceptable and avoids permanent memory dedupe.

---

## PR 3 tests

### Deduper unit tests

1. `AutoAccepted` finish creates completed entry.
2. same key before TTL => `DuplicateRecentlyCompleted`.
3. same key after TTL => `Started`.
4. retryable error finish removes key.
5. cancellation before repository removes key.
6. parser-final outcome retains key.
7. stale in-flight entry is replaced.
8. max entries enforced.

### Service tests

1. same notification immediate repeat after success:
   - second suppressed by in-memory completed TTL;
   - repository called once.

2. same notification immediate repeat after `NeedsReview`:
   - second suppressed.

3. same notification after retryable error:
   - retry allowed.

4. same notification after cancellation before repository:
   - retry allowed.

5. manual refresh immediately after listener success:
   - refresh suppressed.

6. after TTL:
   - service deduper allows;
   - durable fingerprint duplicate handles downstream.

## PR 3 acceptance criteria

- Successful/final dedupe keys are retained until TTL.
- Keys are not blindly removed in `finally`.
- Retryable failures/cancellations release keys.
- Manual refresh and listener share dedupe state.
- Tests cover success, duplicate, retryable error, cancellation, TTL expiry.

---

# PR 4 — Regression tests + docs/tracker update

## Step 4.1 — Combined scenario tests

Add end-to-end-ish tests:

### Scenario A — diagnostic writer failure

```text
Given pipeline creates NeedsReview
And diagnostic writer throws
Then pipeline still returns NeedsReview
And maintenance sink receives diagnostic
And no raw notification text appears in fallback event
```

### Scenario B — same key, different content

```text
Given two StatusBarNotifications with same sbn.key
And bodies "Paid €10 at A" and "Paid €25 at B"
When posted within dedupe window
Then both are processed
```

### Scenario C — same key, same content after success

```text
Given notification processed AutoAccepted
When same notification posts immediately again
Then second is DuplicateRecentlyCompleted
And repository/parser is not called again
```

### Scenario D — retryable error

```text
Given first processing fails retryably
When same notification posts again
Then service allows retry
```

---

## Step 4.2 — Update debugging checklist

Add to Pipeline 1 checklist:

```text
Diagnostics:
- Pipeline diagnostic writer failures must fallback to MaintenanceSafeDiagnosticSink.
- Pipeline outcome must not change due to diagnostic failure.
- No diagnostic write is silently swallowed.

In-memory dedupe:
- Never dedupe solely by sbn.key.
- Dedupe after privacy/package gate and extraction.
- Key = package hash + notification key hash + content fingerprint.
- Same key + different body must process separately.
- Same key + same body in-flight must dedupe.
- Successful/final outcome is retained until TTL.
- Retryable failure/cancellation releases key.
```

---

## Step 4.3 — Update master tracker

After PR 1:

| ID | New status |
|---|---:|
| P1-NEW-19 | Fixed |

After PR 2:

| ID | New status |
|---|---:|
| P1-NEW-20 | Fixed |

After PR 3:

| ID | New status |
|---|---:|
| P1-NEW-21 | Fixed |

Caveat if durable intake is not implemented:

```text
In-memory dedupe now handles listener/refresh duplicate callbacks correctly.
Process-death-safe dedupe/resume remains P1-P1-07.
```

---

# Recommended implementation order

1. **PR 1 — Safe pipeline diagnostics**
   - independent and low risk;
   - improves observability for following PRs.

2. **PR 2 — Content-aware deduper**
   - fixes missed distinct transactions.

3. **PR 3 — Retain completed dedupe keys**
   - depends on content-aware key and ideally repository outcome return.

4. **PR 4 — Tests/docs**
   - locks behavior into tracker/checklist.

---

# Do not mix into these PRs

Keep out of scope:

- durable notification intake queue;
- parser provenance refactor;
- currency fallback detector;
- full finance filter rewrite;
- location policy;
- audit/source-link privacy work;
- full service decomposition.

Allowed overlap:

- If P1-P1-01 outcome return is not landed, add only the minimal outcome propagation needed for PR 3, or defer PR 3 until it lands.
- If P1-NEW-09 safe diagnostic emitter is already implemented, reuse it for PR 1.

---

# Final validation commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Useful searches:

```bash
grep -R "runCatching" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "diagnosticEventWriter.emit" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "processedNotifications" app/src/main/java
grep -R "coarseDedupeKey" app/src/main/java
grep -R "sbn.key" app/src/main/java/com/yourname/expensetracker/service
grep -R "finally.*remove" app/src/main/java/com/yourname/expensetracker/service
```

Expected after all PRs:

- pipeline does not silently swallow diagnostic writer failures;
- service does not dedupe by `sbn.key` alone;
- dedupe key includes content fingerprint;
- successful/final outcomes are retained until TTL;
- retryable failures/cancellations release dedupe key;
- no raw notification content is stored in dedupe maps or diagnostics.