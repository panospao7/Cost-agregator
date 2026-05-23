# Pipeline 1 deep evaluation — commit `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0

Key sources:
- Commit diff: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationIntakeCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt
- `NotificationIntakeWorker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `NotificationRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
- `AppParserRegistry.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt
- `NotificationFilter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt
- `MainApplication.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/MainApplication.kt

---

# Executive verdict

`ad91767` fixes several real problems from `c09c1f5`:

- non-raw intake rows now have payload, so the worker can parse them;
- worker no longer mutates intake row when write barrier blocks writes;
- worker now enforces `maxAttempts`;
- `CancellationException` is rethrown;
- intake insert/enqueue is wrapped in `NonCancellable`;
- Hilt WorkManager configuration exists in `MainApplication`;
- location read is no longer performed, although the unused dependency remains.

But Pipeline 1 is **not clean**.

The biggest blocker is a **privacy regression**:

```text
For STORE_REDACTED / STORE_METADATA_ONLY / DO_NOT_STORE,
NotificationIntakeCoordinator now stores raw title/text/body in the intake row,
and NotificationIntakeWorker passes that same raw object as both processing and storage.
Therefore raw notification text can be persisted into raw_notifications.
```

That means the previous “non-raw modes do not process” bug was replaced with a worse bug:

```text
non-raw modes process, but raw text can leak into persistent storage.
```

So do **not** mark Pipeline 1 ready yet.

---

# What improved

## 1. Non-raw modes now reach the worker

Before `ad91767`, non-raw modes stored null title/text/body, and the worker marked them `PAYLOAD_UNAVAILABLE_PRIVACY`.

Now `NotificationIntakeCoordinator` always stores:

```kotlin
title = title
text = text
bigText = combinedBody
subText = subText
extrasJson = extrasJson
```

and sets:

```kotlin
RawStorageMode.STORE_RAW -> "RAW"
else -> "TRANSIENT"
```

The worker no longer has the `PAYLOAD_UNAVAILABLE_PRIVACY` early return.

So the immediate processing breakage is fixed.

## 2. Worker write-barrier behavior improved

The worker now does:

```kotlin
if (!writeBarrier.writesAllowed()) {
    return Result.retry()
}
```

instead of writing `markRetryableFailure()` during blocked mode.

That fixes the earlier write-barrier violation.

## 3. Retry/cancellation behavior improved

The worker now:

- checks `attempts >= maxAttempts`;
- uses exponential-ish backoff;
- rethrows `CancellationException`.

This is much better than converting cancellation to final failure.

## 4. Hilt WorkManager configuration exists

`MainApplication` implements `Configuration.Provider` and injects `HiltWorkerFactory`, so `@HiltWorker` should be creatable by WorkManager.

Good.

## 5. GPS/location read is effectively removed

`NotificationProcessingPipeline` no longer calls `getLastKnownLocation()`. It sets:

```kotlin
val deviceGps: Pair<Double, Double>? = null
```

So the hidden GPS read issue is functionally fixed.

Remaining cleanup: the pipeline still injects `ForegroundLocationProvider`, but it appears unused.

---

# Critical regressions / blockers

---

## BLOCKER 1 — Non-raw storage modes now leak raw notification text

Severity: **P0/P1 privacy**

This is the most important finding.

### Current flow

Service extracts raw notification text:

```text
parts.title
parts.text
parts.combinedBody
parts.subText
```

Then calls `intakeCoordinator.capture(...)`.

The coordinator now always stores these raw fields in `notification_intake`, regardless of raw-storage mode.

Then the worker reconstructs:

```kotlin
val processingNotification = RawNotification(
    title = current.title,
    text = current.text,
    bigText = current.bigText,
    subText = current.subText,
    extrasJson = current.extrasJson,
    ...
)

val storageNotification = processingNotification
```

Then:

```kotlin
repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = current.correlationId
)
```

So for `STORE_REDACTED`, `STORE_METADATA_ONLY`, and `DO_NOT_STORE`, the storage notification is **raw**.

The pipeline then persists the raw storage notification into `raw_notifications`.

### Why this is a regression

Before intake, the service had two objects:

```text
processingNotification = raw ephemeral text
storageNotification = sanitized/null/redacted text
```

After this commit, the worker uses:

```text
processingNotification = raw
storageNotification = raw
```

So raw body/title can persist under privacy modes that should not persist raw content.

### Affected tables

Likely affected:

- `notification_intake` until purge;
- `raw_notifications` permanently or according to raw row retention;
- possibly pending reviews / diagnostics depending downstream sanitizer behavior.

Even though worker purges intake payload after terminal outcome when `payloadMode == "TRANSIENT"`, that does **not** undo raw text already persisted into `raw_notifications`.

### Correct fix

Worker must reconstruct **two** payloads:

```kotlin
val processingNotification = RawNotification(
    title = current.title,
    text = current.text,
    bigText = current.bigText,
    ...
)

val storageNotification = when (current.rawStorageMode) {
    "STORE_RAW" -> processingNotification

    "STORE_REDACTED" -> processingNotification.copy(
        title = "[REDACTED]",
        text = "[REDACTED]",
        bigText = "[REDACTED]",
        subText = "[REDACTED]",
        extrasJson = """{"redacted":true}"""
    )

    "STORE_METADATA_ONLY" -> processingNotification.copy(
        title = null,
        text = null,
        bigText = null,
        subText = null,
        extrasJson = null
    )

    "DO_NOT_STORE" -> processingNotification.copy(
        title = null,
        text = null,
        bigText = null,
        subText = null,
        extrasJson = null
    )

    else -> fail closed
}
```

Also add tests:

1. `STORE_REDACTED` creates expense/review but raw row contains redacted fields.
2. `STORE_METADATA_ONLY` creates expense/review but raw row has null body fields.
3. `DO_NOT_STORE` creates expense/review but raw row has null body fields.
4. Intake payload is purged after terminal result.
5. No raw title/text/body appears in `raw_notifications`, `pending_reviews`, diagnostics, transaction events.

### Tracker status

```text
P2-11: still open/regressed.
P1-P1-07: partial because durable runtime exists, but privacy-safe runtime is not correct.
```

---

## BLOCKER 2 — “TRANSIENT” payload is not encrypted and is stored as normal DB columns

Severity: **P1 privacy**

Commit message says:

```text
payloadMode = TRANSIENT for non-raw modes
```

But `NotificationIntakeEntity` has only normal columns:

```kotlin
title
text
bigText
subText
extrasJson
```

There is no:

```text
ciphertext
nonce
payloadVersion
encrypted transient store
```

So “TRANSIENT” currently means:

```text
raw text stored durably in normal Room columns until worker reaches terminal and purges it
```

If the app crashes, worker retries, restore blocks writes, or WorkManager is delayed, raw text remains in DB.

This may be acceptable only if product privacy copy explicitly says:

```text
Non-raw modes allow temporary unencrypted local processing queue.
```

But that contradicts the previous privacy direction.

### Correct fix options

Preferred:

```text
Use encrypted transient payload for non-raw modes, purge after terminal.
```

Minimum safer fix:

```text
Document and expose user setting:
Allow temporary local processing queue.
```

For `DO_NOT_STORE`, safest policy is either:

1. no durable async recovery, process ephemeral in service; or
2. encrypted transient opt-in only.

Do not silently store raw `DO_NOT_STORE` text in Room.

---

## BLOCKER 3 — terminal intake row can be regressed to retry/failure if post-terminal cleanup fails

Severity: **P1 correctness / duplicate risk**

In `NotificationIntakeWorker`, the worker does:

1. `repository.processAndSave(...)`
2. `intakeDao.markTerminal(...)`
3. `rawDao.markProcessed(rawId)`
4. `intakeDao.purgeRawPayload(...)`
5. `Result.success()`

All of this is inside the same `try`.

If `markTerminal(...)` succeeds, but then:

- `rawDao.markProcessed(rawId)` throws; or
- `purgeRawPayload(...)` throws,

the `catch (Exception)` block runs and calls:

```kotlin
intakeDao.markRetryableFailure(...)
```

or:

```kotlin
intakeDao.markFinalFailure(...)
```

That can overwrite a terminal `PROCESSED` row and make WorkManager retry processing for a transaction that was already created.

This can cause:

- duplicate pipeline work;
- duplicate diagnostics;
- source-link noise;
- possible duplicate pending review/expense attempts.

### Correct fix

After `markTerminal()` succeeds, failures in cleanup must not change terminal status.

Recommended structure:

```kotlin
val terminalMarked = false

try {
   val outcome = repository.processAndSave(...)
   intakeDao.markTerminal(...)
   terminalMarked = true

   markRawProcessedBestEffort(rawId)
   purgeTransientPayloadBestEffort(current)

   return Result.success()
} catch (e: Exception) {
   if (terminalMarked) {
      // emit diagnostic, but do NOT regress intake status
      return Result.success()
   }
   ...
}
```

Or split into helpers:

```kotlin
markRawProcessedBestEffort(...)
purgeTransientPayloadBestEffort(...)
```

These helpers should emit diagnostics, not retry the whole intake.

Also update DAO methods so `markRetryableFailure` / `markFinalFailure` do not overwrite terminal statuses:

```sql
WHERE id = :id
AND status NOT IN ('PROCESSED', 'DROPPED_DUPLICATE', 'DROPPED_POLICY', 'FILTER_REJECTED', 'FAILED_FINAL')
```

---

## BLOCKER 4 — intake insert is NonCancellable only after filter/extraction

Severity: **P1 reliability**

The `intakeCoordinator.capture(...)` call is wrapped in:

```kotlin
withContext(NonCancellable) { ... }
```

Good improvement.

But the service coroutine still does these before that:

1. emit `RECEIVED`;
2. gate decision;
3. text extraction;
4. in-memory dedupe;
5. filter;
6. second privacy check;
7. settings lookup;
8. app name lookup;
9. extras policy.

If `serviceJob.cancel()` happens before reaching the `NonCancellable` block, no intake row exists.

So the P1-P1-07 loss window is smaller, but not eliminated.

### Correct fix

Move accepted capture handoff earlier and/or into application scope.

Best architecture:

```text
onNotificationPosted
  -> appScope launch
  -> gate
  -> extraction
  -> intake insert
  -> worker does filter + pipeline
```

Even better:

```text
after gate + extraction, immediately insert intake row
filter happens in worker
```

Current filter-before-intake means filter-accepted notifications still have a loss window.

If you define durability only after filter, document that clearly. But then P1-P1-07 is not “all accepted notifications are durable”; it is “notifications that survive service pre-processing and reach intake are durable.”

---

# Important open/partial issues

---

## 1. Parser provenance is still not implemented

Status: **P2-12 partial**

This commit only updates comments in `NotificationProcessingPipeline`.

`AppParserRegistry` still has:

```kotlin
parseWithAiFallback(...): ParsedTransaction?
```

There is no:

```kotlin
parseWithProvenance(...)
ParseOutcome
winningParserId
aiAttempted
aiStatus
aiProvider
aiModel
failureReason
attempts
```

Pipeline still cannot know whether the result came from:

- specific deterministic parser;
- generic parser;
- AI fallback;
- skipped AI;
- failed AI.

So P2-12 remains partial.

### Required fix

Implement actual `ParseOutcome` and wire pipeline to use it.

---

## 2. Currency fallback is still not fixed

Status: **P2-10 partial**

Commit only added TODO comments.

Pipeline still defaults:

```kotlin
"$" -> "USD"
else -> "EUR"
```

There is still no:

- `NotificationMoneySignalDetector`;
- `UserCurrencyProvider`;
- ambiguity model for `$`, `kr`;
- `CurrencyResolution`;
- confidence/basis metadata.

So P2-10 is still open/partial.

---

## 3. Finance filter is still broad

Status: **P2-09 open/partial**

`NotificationFilter` still allows finance packages if:

```kotlin
combined contains transaction/payment/purchase/transfer
OR COMBINED_CURRENCY_REGEX matches
```

Then:

```kotlin
return true
```

So balance-only notifications can still pass:

```text
Available balance €1,240.00
```

There is still no structured decision model:

```kotlin
NotificationFilterDecision
BALANCE_ONLY
CURRENCY_ONLY
INCOMING_ONLY
SECURITY_OR_AUTH
ALLOW_STRONG_EXPENSE
```

P2-09 remains open.

---

## 4. Public repository privacy sanitizer still not fixed

Status: **P2-11 partial**

`NotificationRepository.processAndSave(notification)` now has a stronger warning in KDoc, but behavior is unchanged:

```kotlin
return processAndSave(notification, notification)
```

That means any caller using the single-argument method can still persist raw notification content.

Docs are not a fix.

### Required fix

Either:

1. make the single-argument method internal/test-only; or
2. make it load privacy settings and create sanitized `storageNotification`; or
3. require callers to pass `NotificationPersistencePayload`.

Same issue for batch:

```kotlin
processAndSaveAll(notifications)
```

---

## 5. `RawNotification.isProcessed` is only fixed for worker path

Status: **P1-NEW-14 partial**

The worker now calls:

```kotlin
rawDao.markProcessed(rawId)
```

Good.

But direct repository/pipeline paths still exist:

```kotlin
processAndSave(notification)
processAndSaveAll(notifications)
```

Those can create terminal raw rows without the worker marking them processed.

Either:

- pipeline/repository should mark processed for every terminal path; or
- direct paths should be removed/deprecated; or
- KDoc should say `isProcessed` is only meaningful for intake-originated rows.

---

## 6. Source-link typed result still missing

Status: **P1-NEW-18 partial**

`SOURCE_LINK_FAILED` diagnostic exists.

But there is still no:

```kotlin
SourceLinkWriteResult.Created
SourceLinkWriteResult.AlreadyExists
SourceLinkWriteResult.Failed
```

`writeNotificationDedupeSourceLink(...)` still returns `Unit`.

So callers still cannot reason about source-link failure except via diagnostics.

---

## 7. Refresh diagnostics still mislabeled

Status: **open cleanup**

Manual refresh still calls:

```kotlin
onNotificationPosted(sbn)
```

and intake coordinator is called with:

```kotlin
source = "listener"
```

So refresh diagnostics look like listener events.

Need:

```kotlin
enqueueNotificationCapture(sbn, CaptureSource.REFRESH)
```

---

## 8. Dedupe cleanup still open

Status: **partial**

`computeDedupeKey()` still uses:

```kotlin
packageName.hashCode()
notificationKey.hashCode()
contentFingerprint.take(16)
postTime
```

Issues:

- JVM `hashCode()` is weak and not privacy-grade;
- 64-bit truncated content hash is probably OK for in-memory, but not ideal;
- including `postTime` can miss duplicate callbacks with changed post time;
- old `computeNotificationContentHash` import still exists in service but seems unused/stale.

This is not a blocker, but not clean.

---

## 9. Location issue is functionally fixed, but cleanup remains

Status: **mostly fixed**

No `getLastKnownLocation()` call remains in the notification pipeline.

But `NotificationProcessingPipeline` still injects:

```kotlin
ForegroundLocationProvider
```

Remove it from constructor/DI/tests.

---

## 10. Service decomposition still partial

Status: **P3-13 partial**

`NotificationCaptureService` still owns:

- lifecycle;
- foreground service;
- restart alarm;
- gate orchestration;
- extraction orchestration;
- dedupe;
- filter;
- settings/extras policy;
- intake call;
- diagnostics;
- legacy direct `processNotification(...)` method.

Pipeline 1 can work without full decomposition, but it is not architecturally clean.

---

# New regressions introduced by `ad91767`

## REG-AD-01 — Raw storage privacy bypass through worker storage payload

This is the major new regression.

Cause:

```kotlin
val storageNotification = processingNotification
```

in worker.

Impact:

```text
STORE_REDACTED / STORE_METADATA_ONLY / DO_NOT_STORE can persist raw title/text/body into raw_notifications.
```

Severity:

```text
P0/P1 privacy
```

---

## REG-AD-02 — Unencrypted transient raw payload stored in normal intake columns

Cause:

```kotlin
title = title
text = text
bigText = combinedBody
payloadMode = "TRANSIENT"
```

but no encryption.

Impact:

```text
Raw text persists in notification_intake until terminal purge.
If app crashes/retries, raw remains.
```

Severity:

```text
P1 privacy
```

---

## REG-AD-03 — Terminal intake status can be overwritten after cleanup failure

Cause:

```text
markTerminal succeeds
markProcessed or purge fails
catch marks retryable/final
```

Impact:

```text
terminal row can be reprocessed or misreported
```

Severity:

```text
P1 correctness
```

---

# Issue status after `ad91767`

| Issue | Status | Comment |
|---|---:|---|
| P1-P1-01 | ✅ Fixed | Outcome propagation OK. |
| P1-P1-02 | ✅ Mostly fixed | Safe diagnostics OK, shutdown caveats remain. |
| P1-P1-03 | ✅ Mostly fixed | Messaging extraction OK; small fallback cleanup remains. |
| P1-P1-05 | ✅ Mostly fixed | Gate mostly OK. |
| P1-P1-06 | ✅ Fixed | Write barriers mostly OK. |
| P1-P1-07 | ⚠ Partial | Runtime intake exists, but durability/privacy correctness not complete. |
| P2-08 | ✅ Mostly fixed | Refresh shares listener path, but source mislabeled. |
| P2-09 | ❌ Open/partial | Finance filter still broad. |
| P2-10 | ❌ Partial | TODO comments only; hardcoded fallback remains. |
| P2-11 | ❌ Regressed | Worker can persist raw text under non-raw modes. |
| P2-12 | ❌ Partial | Provenance model/comments only, not integrated. |
| P3-13 | ⚠ Partial | Service still large. |
| P1-NEW-14 | ⚠ Partial | Worker path only. |
| P1-NEW-16 | ✅ Mostly fixed | GPS read removed; cleanup dependency. |
| P1-NEW-18 | ⚠ Partial | Diagnostic yes, typed result no. |
| Dedupe cleanup | ⚠ Partial | hashCode/postTime/source cleanup remains. |

---

# Is Pipeline 1 clean and ready?

No.

It is **closer**, but not ready because of the privacy regression.

Minimum blocking fixes before “ready”:

## Must-fix blocker PR

### 1. Worker must build sanitized `storageNotification`

Fix:

```kotlin
val processingNotification = RawNotification(... raw fields ...)

val storageNotification = sanitizeForStorage(
    processingNotification,
    rawStorageMode = current.rawStorageMode
)

repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = current.correlationId
)
```

### 2. Add tests for all raw storage modes

For each mode:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

Assert:

- worker processes notification;
- raw notification storage respects mode;
- pending review storage respects mode;
- diagnostics contain no raw body;
- intake payload is purged after terminal if transient.

### 3. Make post-terminal cleanup best-effort

After `markTerminal`, do not allow `markProcessed` or `purgeRawPayload` failure to change status to retry/failure.

### 4. Decide transient-payload policy

Either:

- encrypt transient payload; or
- explicitly document temporary raw queue behavior; or
- make `DO_NOT_STORE` process synchronously / no durable recovery.

Without this, `DO_NOT_STORE` is misleading.

---

# Recommended next PR order

## PR A — Privacy regression hotfix

Fix:

- worker storage sanitizer;
- transient payload policy;
- terminal status overwrite bug.

This is urgent.

## PR B — Tests

Add regression tests for:

- all `RawStorageMode` values through intake worker;
- cleanup failure after terminal status;
- worker write barrier;
- cancellation;
- max attempts.

## PR C — Parser provenance

Actually implement `parseWithProvenance()`.

## PR D — Currency detector

Replace hardcoded USD/EUR fallback.

## PR E — Finance filter v2

Reject balance/currency-only finance notifications.

## PR F — Repository public-path sanitizer

Do not rely on KDoc warning.

## PR G — cleanup

- refresh source;
- remove location provider dependency;
- dedupe hash cleanup;
- typed source-link result;
- service decomposition.

---

# Final conclusion

`ad91767` fixes some important runtime hardening issues, but it also introduces a serious privacy regression.

The current Pipeline 1 state is:

```text
Functionally closer to durable intake,
but privacy-unsafe for non-raw storage modes.
```

I would not mark Pipeline 1 clean or ready until the worker storage sanitizer and transient-payload policy are fixed and tested.