I reviewed commit `c09c1f5` statically. The recent fixes are substantial, especially intake runtime and location removal, but several issues are still partial and there is one major regression around non-raw storage modes.

<pipeline1_recheck_c09c1f5.md>
# Pipeline 1 deep evaluation — commit `c09c1f551ae0733e2b6d4803d2bb9d68c719abc5`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/c09c1f551ae0733e2b6d4803d2bb9d68c719abc5

Main files checked:
- `NotificationCaptureService.kt`
- `NotificationIntakeCoordinator.kt`
- `NotificationIntakeWorker.kt`
- `NotificationIntakeRecoveryScheduler.kt`
- `NotificationIntakeDao.kt`
- `NotificationIntakeEntity.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- `NotificationFilter.kt`
- `AppParserRegistry.kt`
- `ParseProvenance.kt`
- `RawNotificationDao.kt`
- `RawNotification.kt`
- `WorkerModule.kt`
- `DaoModule.kt`

---

# Executive verdict

Commit `c09c1f5` is another **real improvement**, especially for:

- adding actual intake runtime classes;
- service no longer directly calling repository in the main listener path;
- adding WorkManager enqueue for intake rows;
- adding recovery scheduler;
- removing the direct GPS read from the notification pipeline;
- adding parse provenance model classes;
- marking raw rows processed from the worker;
- keeping the build compiling.

But Pipeline 1 is still **not clean**.

The biggest remaining problems:

1. **Major regression: non-raw storage modes no longer process notifications.**  
   `NotificationIntakeCoordinator` stores title/text/body only in `STORE_RAW`; `NotificationIntakeWorker` refuses to process if all text fields are null. So `STORE_REDACTED`, `STORE_METADATA_ONLY`, and `DO_NOT_STORE` mostly become `PAYLOAD_UNAVAILABLE_PRIVACY` instead of creating expense/review.

2. **P1-P1-07 durable intake is now partially implemented, but not fully fixed.**  
   There is real runtime intake now, but insertion still happens inside `serviceScope`, which is cancelled on service destruction. There is still a loss window before intake insert.

3. **Worker violates/weakens write-barrier semantics.**  
   If writes are blocked, worker calls `intakeDao.markRetryableFailure(...)`, which itself is a DB write during write-blocked mode.

4. **Retry/status model is incomplete.**  
   `attempts` and `maxAttempts` exist, but `maxAttempts` is not enforced. `FAILED_RETRYABLE` can loop forever.

5. **Worker cancellation is mishandled.**  
   `catch (e: Exception)` catches `CancellationException`, marks final failure, and returns failure instead of rethrowing/cancelling cleanly.

6. **Parser provenance is model-only.**  
   `ParseProvenance.kt` exists, but `AppParserRegistry` still exposes/uses `parseWithAiFallback(): ParsedTransaction?`; pipeline still does not consume typed provenance.

7. **Currency fallback remains partial.**  
   More currencies are recognized, but fallback still uses hardcoded `USD` / `EUR`, with no home-currency or ambiguity model.

8. **Finance filtering remains broad.**  
   Finance app notifications still pass on any currency-looking amount; balance-only notifications can still pass.

9. **Public/batch repository privacy bypass remains.**  
   `processAndSave(notification)` and `processAndSaveAll()` still use raw notification as storage payload.

10. **Tests are still not fully green.**  
   Commit message says `15/17` pipeline tests pass, with 2 remaining MockK failures.

---

# Status table

| Issue | Status at `c09c1f5` | Notes |
|---|---:|---|
| P1-P1-01 | ✅ Fixed | Outcome propagation still good. |
| P1-P1-02 | ✅ Mostly fixed | Safe diagnostics remain; shutdown caveats remain. |
| P1-P1-03 | ✅ Mostly fixed | Messaging extraction improved; stale hash helper remains. |
| P1-P1-05 | ✅ Mostly fixed | Gate mostly repaired in prior commit. |
| P1-P1-06 | ✅ Fixed | Main known write barriers fixed. |
| P1-P1-07 | ⚠ Partial | Intake runtime exists, but loss window + privacy-mode processing regression remain. |
| P2-08 | ✅ Mostly fixed | Refresh uses same listener path, but source is still mislabeled as listener. |
| P2-09 | ⚠ Partial/open | Finance filter still broad. |
| P2-10 | ⚠ Partial | Regex expanded, still hardcoded USD/EUR fallback. |
| P2-11 | ⚠ Partial | Public/batch paths still bypass sanitizer; intake non-raw modes now fail to process. |
| P2-12 | ⚠ Partial | Models added only; no `parseWithProvenance()` integration. |
| P3-13 | ⚠ Partial | Service still owns many responsibilities and dead direct-processing method remains. |
| P1-NEW-14 | ⚠ Mostly fixed for intake path | Worker marks processed; direct/batch paths do not. |
| P1-NEW-16 | ✅ Mostly fixed | Direct GPS read removed; dead injection/import still exists. |
| P1-NEW-18 | ⚠ Partial | Source-link failure diagnostic exists, no typed result. |

---

# Detailed evaluation

---

## 1. Durable intake runtime — P1-P1-07

## What improved

This commit adds real runtime pieces:

- `NotificationIntakeCoordinator`
- `NotificationIntakeWorker`
- `NotificationIntakeRecoveryScheduler`
- `NotificationIntakeDao` provider
- `WorkManager` provider
- service uses `intakeCoordinator.capture(...)` instead of `repository.processAndSave(...)` in the main listener path
- worker calls `repository.processAndSave(...)`
- worker marks intake rows terminal
- worker marks raw rows processed

This is a major step forward compared to `64e78ef`, where intake was schema-only.

## Current flow

Service:

```text
onNotificationPosted
 -> emit RECEIVED
 -> captureGate.decide()
 -> extract text
 -> in-memory dedupe
 -> NotificationFilter.shouldCapture()
 -> second privacy check
 -> intakeCoordinator.capture(...)
 -> WorkManager enqueue
```

Coordinator:

```text
compute dedupeFingerprint
if intakeDao.existsByFingerprint -> duplicate
insert intake row
enqueue NotificationIntakeWorker
```

Worker:

```text
load intake row
check writeBarrier.writesAllowed()
claim row
if no payload -> PAYLOAD_UNAVAILABLE_PRIVACY
filter
build RawNotification
repository.processAndSave(...)
mark terminal
mark raw processed
purge raw payload if non-raw
```

## Remaining problems

### Problem 1 — service destruction can still cancel before intake insert

The intake insert is launched inside:

```kotlin
workTracker.launch(serviceScope) { ... intakeCoordinator.capture(...) }
```

`onDestroy()` does:

```kotlin
serviceJob.cancel()
```

So if service is destroyed after gate/extraction/filter but before `intakeDao.insertOrIgnore(entity)`, the work can still be cancelled and no intake row exists.

This means the original loss sequence is reduced but not eliminated:

```text
listener callback accepted
service coroutine starts
text extracted and filter passed
service destroyed / process killed
intake row not inserted
notification not reposted
transaction lost
```

Fix:

- move the minimal intake insert to an `@ApplicationScope` coroutine, or
- wrap the insert+enqueue section in `NonCancellable`, or
- make `NotificationCaptureService` hand off to an app-scoped `NotificationCaptureCoordinator`.

Recommended:

```kotlin
applicationScope.launch {
    intakeCoordinator.capture(...)
}
```

or:

```kotlin
withContext(NonCancellable) {
    intakeCoordinator.capture(...)
}
```

for the insert + enqueue critical section.

### Problem 2 — intake is inserted after filter

Filtering happens before intake row creation.

That is okay if you define “accepted notification” as “passed filter,” but it means filter-rejected callbacks do not get an intake row. They only get diagnostics.

If the goal is a complete durable callback ledger, intake should be inserted before filter and worker should mark `FILTER_REJECTED`.

Current approach is acceptable if documented:

```text
Intake durability starts only after gate + extraction + filter.
```

But then P1-P1-07 should be worded as:

```text
Durability for filter-accepted notifications, not every listener callback.
```

### Problem 3 — WorkManager enqueue failure leaves row but depends on recovery

If `insertOrIgnore` succeeds but `workManager.enqueueUniqueWork(...)` throws, service catch emits failure and removes dedupe key. The intake row remains `RECEIVED`.

That is recoverable only if recovery scheduler later runs.

Currently recovery scheduler runs in:

```kotlin
onListenerConnected()
```

Need also wire it to:
- app start;
- boot receiver;
- restore completion;
- maybe periodic WorkManager sweep.

### Problem 4 — Hilt WorkManager runtime needs verification

Worker is annotated:

```kotlin
@HiltWorker
class NotificationIntakeWorker @AssistedInject constructor(...)
```

For Hilt workers, the app must configure WorkManager with `HiltWorkerFactory` in the Application class. I did not verify that in this commit. If not already configured elsewhere, WorkManager will fail to instantiate the worker at runtime.

Verify:

```bash
grep -R "HiltWorkerFactory" app/src/main/java
grep -R "Configuration.Provider" app/src/main/java
```

If missing, add the Hilt WorkManager configuration.

Source: Android HiltWorker docs:  
https://developer.android.com/reference/kotlin/androidx/hilt/work/HiltWorker

## Verdict

```text
P1-P1-07: partial, not fully fixed.
```

It is now real runtime intake, but not fully durable because:
- service-scope cancellation window remains;
- non-raw privacy modes do not process;
- recovery not wired everywhere;
- write-barrier retry writes are problematic.

---

# 2. Major regression — non-raw storage modes no longer process notifications

This is the biggest issue in the commit.

## Evidence

`NotificationIntakeCoordinator` stores payload fields only when:

```kotlin
rawStorageMode == RawStorageMode.STORE_RAW
```

Specifically:

```kotlin
title = if (rawStorageMode == RawStorageMode.STORE_RAW) title else null
text = if (rawStorageMode == RawStorageMode.STORE_RAW) text else null
bigText = if (rawStorageMode == RawStorageMode.STORE_RAW) combinedBody else null
subText = if (rawStorageMode == RawStorageMode.STORE_RAW) subText else null
extrasJson = if (rawStorageMode == RawStorageMode.STORE_RAW) extrasJson else null
```

Then `NotificationIntakeWorker` does:

```kotlin
if (current.bigText == null && current.title == null && current.text == null) {
    intakeDao.markTerminal(
        status = PAYLOAD_UNAVAILABLE_PRIVACY
    )
    return Result.success()
}
```

Therefore:

| RawStorageMode | Intake payload | Worker result |
|---|---|---|
| `STORE_RAW` | raw title/text/body | processed |
| `STORE_REDACTED` | null title/text/body | `PAYLOAD_UNAVAILABLE_PRIVACY` |
| `STORE_METADATA_ONLY` | null title/text/body | `PAYLOAD_UNAVAILABLE_PRIVACY` |
| `DO_NOT_STORE` | null title/text/body | `PAYLOAD_UNAVAILABLE_PRIVACY` |

This means users who disabled raw storage will likely stop getting notification-created expenses/reviews.

## Why this is worse than before

Before intake, service used:
- raw text ephemerally for processing;
- sanitized text for storage.

That preserved privacy while still processing.

Now processing moved to an async worker, but the worker has no ephemeral raw payload unless it was stored durably.

## Correct design options

### Option A — explicit encrypted transient queue

Best long-term:

```text
RawStorageMode controls long-term retention.
NotificationIntake may store encrypted transient processing payload,
with immediate purge after terminal outcome.
```

Add user-facing setting if necessary:

```text
Allow encrypted temporary notification processing queue
```

Then:

| Mode | Intake payload |
|---|---|
| `STORE_RAW` | raw, retained according to raw policy |
| `STORE_REDACTED` | encrypted transient raw + redacted audit |
| `STORE_METADATA_ONLY` | encrypted transient raw + metadata audit |
| `DO_NOT_STORE` | either encrypted transient if explicit opt-in, or no async recovery |

### Option B — synchronous direct processing for non-raw modes

If user disallows transient raw storage:

```text
For DO_NOT_STORE / METADATA_ONLY:
    process synchronously in service using ephemeral raw text,
    persist only sanitized storage rows.
```

But then P1-P1-07 durability is not guaranteed for those modes. Document:

```text
Full process-death recovery requires transient encrypted queue or STORE_RAW.
```

### Option C — keep raw in intake for all modes, then purge

Simplest technically, but risky:

```text
Store raw text temporarily for all modes and purge after processing.
```

Do not do this silently if user expects “do not store.”

## Required immediate fix

At minimum:

1. Add explicit policy tests:
   - `STORE_RAW` processes.
   - `STORE_REDACTED` either processes via transient payload or clearly opts out.
   - `STORE_METADATA_ONLY` same.
   - `DO_NOT_STORE` same.

2. Update UI/docs/tracker:
   - if non-raw modes cannot be recovered, say so explicitly.

3. Do not mark P1-P1-07 or P2-11 fully fixed until this is resolved.

## Severity

```text
P1 correctness/privacy-mode regression.
```

---

# 3. Worker write-barrier problem

## Evidence

Worker checks:

```kotlin
if (!writeBarrier.writesAllowed()) {
    intakeDao.markRetryableFailure(...)
    return Result.retry()
}
```

But `markRetryableFailure(...)` is a DB write.

If writes are blocked because restore/maintenance mode is active, this code writes to the same DB state it is supposed to avoid mutating.

## Correct behavior

During write-blocked mode:

- do not mutate Room tables;
- return `Result.retry()`;
- optionally emit maintenance-safe diagnostic through `MaintenanceSafeDiagnosticSink`.

Possible fix:

```kotlin
if (!writeBarrier.writesAllowed()) {
    diagnosticEmitter.emit(...)
    return Result.retry()
}
```

Do not call:

```kotlin
intakeDao.markRetryableFailure(...)
```

until writes are allowed.

Alternative:

- if intake status updates are explicitly allowed during restore, that must be codified in `DatabaseWriteBarrier` as a special allowed operation. Right now it is not.

## Verdict

```text
Write barrier for worker is partial/buggy.
```

---

# 4. Worker retry/status model

## Current state

`NotificationIntakeEntity` has:

```kotlin
attempts
maxAttempts
nextAttemptAt
```

`claimForProcessing` increments attempts.

But worker does not enforce:

```kotlin
attempts >= maxAttempts
```

`getReadyForProcessing()` selects all `FAILED_RETRYABLE` rows where time has arrived.

So a permanently failing row can retry forever.

## Also problematic

Worker catches all exceptions:

```kotlin
catch (e: Exception) {
    intakeDao.markFinalFailure(...)
    Result.failure()
}
```

This means:
- transient DB exceptions outside `repository.processAndSave()` become final failure;
- `CancellationException` is caught and converted to final failure;
- WorkManager stop/cancel is not handled correctly.

## Required fix

Add classification:

```kotlin
catch (e: CancellationException) {
    throw e
}

catch (e: Exception) {
    if (isRetryable(e) && current.attempts < current.maxAttempts) {
        markRetryableFailure(...)
        Result.retry()
    } else {
        markFinalFailure(...)
        Result.failure()
    }
}
```

Before processing:

```kotlin
if (current.attempts >= current.maxAttempts) {
    markFinalFailure(MAX_ATTEMPTS_EXCEEDED)
    return Result.failure()
}
```

Retryable:
- DB locked;
- IOException;
- write barrier;
- WorkManager stop/cancellation;
- timeout.

Final:
- validation;
- privacy payload unavailable;
- duplicate;
- parser final failure.

## Verdict

```text
Intake retry model is partial.
```

---

# 5. RawNotification.isProcessed — P1-NEW-14

## What improved

`RawNotificationDao` now has:

```kotlin
markProcessed(rawId)
```

Worker calls:

```kotlin
if (rawId != null) {
    rawDao.markProcessed(rawId)
}
```

KDoc/TODO in `RawNotification.kt` was updated to say:

```text
isProcessed is set by NotificationIntakeWorker after terminal pipeline outcome.
```

## Remaining caveat

This is fixed only for intake-worker path.

Direct paths still exist:

```kotlin
NotificationRepository.processAndSave(notification)
NotificationRepository.processAndSaveAll(notifications)
```

Those call pipeline directly. If those paths create raw rows, `isProcessed` is not marked by the worker.

Also service still contains a private legacy `processNotification(...)` method that directly calls repository, although main listener path no longer uses it.

## Verdict

```text
P1-NEW-14: mostly fixed for intake path, partial globally.
```

To fully close:
- either mark processed inside pipeline after terminal outcome, or
- remove/deprecate direct repository processing paths, or
- document that `isProcessed` only applies to intake-originated rows.

---

# 6. Location/GPS — P1-NEW-16

## What improved

Direct location read was removed.

`NotificationProcessingPipeline` no longer calls:

```kotlin
locationProvider.getLastKnownLocation()
```

Now:

```kotlin
val deviceGps: Pair<Double, Double>? = null
```

This fixes the actual hidden GPS read.

## Remaining cleanup

The pipeline still imports/injects:

```kotlin
ForegroundLocationProvider
```

even though it no longer uses it.

That is now dead dependency/architecture debt, not a privacy bug.

## Verdict

```text
P1-NEW-16: fixed with cleanup.
```

Follow-up:
- remove `ForegroundLocationProvider` constructor dependency from `NotificationProcessingPipeline`;
- update DI/tests accordingly.

---

# 7. Parser provenance — P2-12

## What improved

New domain model exists:

```kotlin
ParserSource
AiFallbackStatus
ParseFailureReason
ParserAttempt
ParseProvenance
```

That is useful groundwork.

## Still not fixed

`AppParserRegistry` still exposes:

```kotlin
parseWithAiFallback(...): ParsedTransaction?
```

Pipeline still calls:

```kotlin
val parsed = parserRegistry.parseWithAiFallback(...)
```

Pipeline still emits only:

```kotlin
parserSource = "PARSE_SUCCEEDED"
```

No actual typed provenance is produced or consumed.

The pipeline comment still says:

```text
A full ParseOutcome contract would carry provenance metadata directly
```

## Missing

Still no:

```kotlin
ParseOutcome
parseWithProvenance()
deterministic parser ID
AI attempted/skipped/failure status
AI provider/model
failure reason
attempt list
```

## Verdict

```text
P2-12: partial/model-only.
```

Next PR:
- implement `parseWithProvenance()` in `AppParserRegistry`;
- update pipeline to consume it;
- deprecate `parseWithAiFallback()`.

---

# 8. Currency fallback — P2-10

## Current state

Still partial.

The pipeline now recognizes more currencies, but fallback still contains:

```kotlin
fullText.contains("$") -> "USD"
else -> "EUR"
```

This appears in both:
- `detectOversizedAmountCandidate`
- `detectTransactionSignalCandidate`

The comment even says:

```text
Ambiguous symbols: try home-currency context or default to EUR as last resort
```

but there is no home-currency provider or ambiguity model.

## Remaining problems

- `$` can be USD/CAD/AUD.
- `kr` can be SEK/NOK/DKK.
- bare/default fallback still becomes EUR.
- no `CurrencyResolution`.
- no `MoneySignal`.
- no confidence/basis metadata.
- filter and pipeline still use separate logic.

## Verdict

```text
P2-10: partial.
```

Still need the shared money detector.

---

# 9. Finance filtering — P2-09

## Current state

`NotificationFilter` remains broad.

For finance packages:

```kotlin
val hasTransactionSignal =
    combined.contains("transaction") ||
    combined.contains("payment") ||
    combined.contains("purchase") ||
    combined.contains("transfer") ||
    COMBINED_CURRENCY_REGEX.containsMatchIn(combined)
```

Then if there is any currency-looking amount and no deny keyword:

```kotlin
return true
```

This means balance-only notifications can still pass:

```text
"Available balance €1,240.00"
```

There is no:
- structured `NotificationFilterDecision`;
- balance-only rejection;
- direction detection;
- incoming-only rejection;
- currency-only rejection;
- finance-specific reviewable-vs-allow classification.

## Verdict

```text
P2-09: open/partial.

:warning: The provider stream ended early, so this response may be incomplete.