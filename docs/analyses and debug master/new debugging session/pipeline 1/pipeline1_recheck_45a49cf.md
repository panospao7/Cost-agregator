# Pipeline 1 deep evaluation — commit `45a49cf0a847f010303ff207583d9e2fb777f896`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/45a49cf0a847f010303ff207583d9e2fb777f896

Key files checked:
- `NotificationIntakeWorker.kt`
- `NotificationIntakeCoordinator.kt`
- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- `NotificationFilter.kt`
- `NotificationTextParts.kt`
- `AppParserRegistry.kt`
- `NotificationIntakeDao.kt`
- `NotificationIntakeEntity.kt`

---

# Executive verdict

`45a49cf` correctly fixes two major blockers from `ad91767`:

1. **Worker no longer passes raw processing notification as storage notification.**
   - `STORE_RAW` preserves raw storage.
   - `STORE_REDACTED` stores redacted markers.
   - `STORE_METADATA_ONLY` / `DO_NOT_STORE` store null title/text/body/extras.
   - Unknown modes fail closed.

2. **Post-terminal cleanup failures no longer regress terminal intake status.**
   - `markProcessed()` and `purgeRawPayload()` are now best-effort.
   - If they fail after terminal status is set, worker returns success instead of retrying/reprocessing.

These are important fixes.

However, Pipeline 1 is **still not clean and not ready**.

Main remaining blockers:

1. **Transient payload is still plaintext in Room.**
2. **Several terminal/non-success paths still do not purge transient raw payload.**
3. **PendingReview sanitization uses current settings, not the intake row’s captured `rawStorageMode`.**
4. **Durable intake is still inserted late, after service-side gate/extract/filter work.**
5. **Parser provenance is still not implemented.**
6. **Currency fallback still silently defaults to USD/EUR.**
7. **Finance filter is improved but still not a structured, fully reliable decision model.**
8. **Public/batch repository paths can still bypass storage sanitization.**
9. **Service decomposition is still partial.**

Bottom line:

```text
The emergency privacy leak into raw_notifications is fixed.
But Pipeline 1 still has enough open privacy/lifecycle/correctness gaps that it should remain PARTIAL, not READY.
```

---

# What this commit fixed

## 1. Worker storage sanitizer — major improvement

Previous bad code:

```kotlin
val storageNotification = processingNotification
```

Current worker now uses:

```kotlin
val storageNotification = buildStorageNotification(
    processing = processingNotification,
    rawStorageMode = current.rawStorageMode
)
```

Current behavior:

| `rawStorageMode` | Worker storage behavior |
|---|---|
| `STORE_RAW` | stores raw notification |
| `STORE_REDACTED` | stores `[REDACTED]` fields + redacted extras marker |
| `STORE_METADATA_ONLY` | null title/text/body/subText/extras |
| `DO_NOT_STORE` | null title/text/body/subText/extras |
| unknown | fail-closed to null fields |

This fixes the **direct raw leak into `raw_notifications`** that existed in `ad91767`.

Status:

```text
P2-11 worker raw_notifications leak: fixed for this path.
```

---

## 2. Terminal cleanup guard — major improvement

Previous risk:

```text
repository.processAndSave()
markTerminal()
markProcessed() throws
catch block marks retry/failure
row may be reprocessed
```

Current worker sets:

```kotlin
terminalMarked = true
```

after `markTerminal()`, then cleanup is best-effort:

```kotlin
markRawProcessedBestEffort(rawId)
purgePayloadBestEffort(current, now)
```

If cleanup throws after terminal:

```kotlin
if (terminalMarked) {
    Timber.w(...)
    return Result.success()
}
```

This fixes the previous “terminal row can regress to retry/failure after cleanup failure” bug.

Status:

```text
Terminal overwrite bug: mostly fixed.
```

---

# Remaining blockers / regressions

---

## BLOCKER 1 — transient intake payload is still plaintext in Room

Severity: **P1 privacy**

`NotificationIntakeCoordinator` still stores raw processing text directly in normal Room columns:

```kotlin
title = title
text = text
bigText = combinedBody
subText = subText
extrasJson = extrasJson
payloadMode = "TRANSIENT"
```

For non-raw modes, the worker now sanitizes `raw_notifications`, which is good.

But until the worker terminally processes and purges the intake row, raw notification text is still stored in:

```text
notification_intake.title
notification_intake.text
notification_intake.bigText
notification_intake.subText
notification_intake.extrasJson
```

This is not encrypted. It is only “transient” by convention.

Risk cases where raw text remains longer than expected:

- WorkManager is delayed.
- App crashes before worker runs.
- Restore/write barrier blocks processing.
- Worker retries.
- Filter-rejected path returns before purge.
- Max-attempts final failure returns before purge.
- Non-retryable exception final failure returns before purge.

If product privacy promise says `DO_NOT_STORE` means “do not store raw notification content at all,” this is still a privacy violation.

### Required fix

Add an explicit transient payload policy:

```kotlin
enum class NotificationTransientPayloadPolicy {
    ENCRYPTED_UNTIL_PROCESSED,
    DISABLED
}
```

For non-raw modes:

| Mode | Correct behavior |
|---|---|
| `STORE_REDACTED` | redacted visible fields + encrypted transient payload |
| `STORE_METADATA_ONLY` | null visible fields + encrypted transient payload |
| `DO_NOT_STORE` | no transient payload unless user explicitly opts in |
| `STORE_RAW` | raw payload allowed |

Add columns:

```kotlin
transientPayloadCiphertext: String?
transientPayloadNonce: String?
transientPayloadVersion: Int?
transientPayloadPurgedAt: Long?
```

Until then, mark this as:

```text
P2-11 / privacy transient payload: partial.
```

---

## BLOCKER 2 — filter-rejected intake rows do not purge transient raw payload

Severity: **P1 privacy**

Worker flow:

```kotlin
if (!NotificationFilter.shouldCapture(...)) {
    intakeDao.markTerminal(
        status = FILTER_REJECTED,
        ...
    )
    return Result.success()
}
```

There is no call to:

```kotlin
purgePayloadBestEffort(...)
```

So if a non-raw-mode intake row reaches the worker and is filter-rejected, raw title/text/body can remain in `notification_intake`.

This matters because the service currently filters before intake, but worker still filters again. Rows can be filter-rejected if:

- filter rules changed between capture and worker;
- recovery processes older rows;
- tests/debug/manual inserted rows exist;
- service-side filter was bypassed in some future path.

### Required fix

After `FILTER_REJECTED` terminal:

```kotlin
intakeDao.markTerminal(...)
purgePayloadBestEffort(current, now)
return Result.success()
```

Make purge best-effort and do not regress terminal status.

---

## BLOCKER 3 — max-attempts final failure does not purge transient raw payload

Severity: **P1 privacy**

Current early path:

```kotlin
if (intake.attempts >= intake.maxAttempts) {
    intakeDao.markFinalFailure(...)
    return Result.failure()
}
```

No purge happens.

If this row has `payloadMode = TRANSIENT`, raw payload remains after final failure.

### Required fix

```kotlin
if (intake.attempts >= intake.maxAttempts) {
    intakeDao.markFinalFailure(...)
    purgePayloadBestEffort(intake, now)
    return Result.failure()
}
```

---

## BLOCKER 4 — non-retryable pre-terminal exception does not purge transient payload

Severity: **P1 privacy**

Catch block:

```kotlin
if (isRetryable(e) && current.attempts + 1 < current.maxAttempts) {
    markRetryableFailure(...)
    Result.retry()
} else {
    markFinalFailure(...)
    Result.failure()
}
```

For final failure, there is no purge.

### Required fix

```kotlin
else {
    intakeDao.markFinalFailure(...)
    purgePayloadBestEffort(current, now)
    Result.failure()
}
```

Retryable failures may keep payload for retry, but final failures must purge.

---

## BLOCKER 5 — PendingReview sanitization uses current settings, not captured intake mode

Severity: **P1/P2 privacy edge case**

Worker correctly builds storage notification based on:

```kotlin
current.rawStorageMode
```

That is the mode captured at intake time.

But inside `NotificationProcessingPipeline`, pending review fields are sanitized by:

```kotlin
privacySettingsRepository.getSettings().rawNotificationStorageMode
```

That means PendingReview privacy behavior uses **current settings at worker time**, not the storage mode captured with the intake row.

Bad scenario:

1. User has `DO_NOT_STORE`.
2. Notification is captured with rawStorageMode=`DO_NOT_STORE`.
3. Raw transient payload is queued.
4. User later changes setting to `STORE_RAW`.
5. Worker processes old intake row.
6. `sanitizePendingReviewText()` sees `STORE_RAW`.
7. PendingReview may store raw notification title/text.

This violates the storage mode active when the notification was captured.

### Required fix

Pipeline needs an explicit persistence context:

```kotlin
data class NotificationPersistenceContext(
    val rawStorageMode: RawStorageMode
)
```

Change process signature:

```kotlin
pipeline.process(
    processingNotification,
    storageNotification,
    correlationId,
    persistenceContext
)
```

Then:

```kotlin
sanitizePendingReviewText(text, persistenceContext.rawStorageMode)
```

Do not read current privacy settings inside the pipeline for already-captured notification payloads.

At minimum, use the already-sanitized `storageNotification` fields when constructing `PendingReview`.

---

# Durable intake status

## Improved, but still not fully durable

Current service wraps only the final `intakeCoordinator.capture(...)` call in:

```kotlin
withContext(NonCancellable)
```

But many steps still happen before that inside `serviceScope`:

1. emit `RECEIVED`
2. gate decision
3. extract extras/text
4. dedupe
5. filter
6. second privacy check
7. settings lookup
8. app name lookup
9. extras policy
10. then intake insert/enqueue

If `onDestroy()` cancels `serviceJob` before step 10, no intake row exists.

So P1-P1-07 is better, but still not fully solved.

### Required fix

Move capture orchestration to app scope:

```kotlin
@ApplicationScope
CoroutineScope
```

Listener should do minimal safe handoff:

```kotlin
applicationScope.launch {
    captureCoordinator.handle(sbn, CaptureSource.LISTENER)
}
```

Also move filter to worker, so intake is inserted earlier:

```text
service:
  gate -> extraction -> intake insert

worker:
  filter -> pipeline
```

Then filter-rejected rows become terminal `FILTER_REJECTED` rows and can purge payload safely.

Status:

```text
P1-P1-07: partial, not fully fixed.
```

---

# Parser provenance — still open

`ParseProvenance.kt` exists, but `AppParserRegistry` still exposes only:

```kotlin
parseWithAiFallback(...): ParsedTransaction?
```

There is still no:

```kotlin
parseWithProvenance(...)
ParseOutcome
parserId
winningParserId
aiAttempted
aiStatus
aiProvider
aiModel
failureReason
attempts
```

Pipeline still calls:

```kotlin
parserRegistry.parseWithAiFallback(...)
```

and emits only:

```kotlin
parserSource = "PARSE_SUCCEEDED"
```

Status:

```text
P2-12: open/partial.
```

---

# Currency fallback — still open

Pipeline still contains hardcoded fallback logic:

```kotlin
fullText.contains("$") -> "USD"
else -> "EUR"
```

This remains in both:

- oversized amount fallback,
- transaction signal fallback.

There is still no:

- `NotificationMoneySignalDetector`,
- `CurrencyResolution`,
- home-currency provider,
- ambiguity model for `$` / `kr`,
- confidence/basis diagnostics.

Status:

```text
P2-10: open/partial.
```

---

# Finance filter — improved but still partial

`NotificationFilter` has improved finance deny lists and now requires amount + expense/transaction signal.

Good improvements:
- balance/account/FX/security/promo keywords exist.
- finance packages no longer pass solely on currency amount.

Remaining concerns:

1. It is still boolean-only:
   ```kotlin
   shouldCapture(...): Boolean
   ```
   No structured reason/confidence/direction.

2. Deny keywords are broad and can false-reject valid expenses:
   - `"credit"` appears in `FINANCIAL_KEYWORDS` generally.
   - `"received"`, `"deposit"`, `"refund"` hard-deny all finance notifications, even if text has both refund and purchase context.
   - `"available"` can reject phrases not strictly balance-related.

3. `hasTransactionSignal` includes:
   ```kotlin
   transfer
   payment
   sent
   purchase
   ```
   without direction semantics.

4. Incoming vs outgoing transfer logic is not robust.

Status:

```text
P2-09: improved / mostly partial, not fully clean.
```

To fully close, add:

```kotlin
NotificationFilterDecision(
    capture,
    reason,
    confidence,
    direction,
    moneySignals
)
```

---

# Public/batch repository privacy bypass still open

`NotificationRepository.processAndSave(notification)` still does:

```kotlin
return processAndSave(notification, notification)
```

The KDoc warns callers, but behavior remains unsafe.

`processAndSaveAll(notifications)` still calls:

```kotlin
pipeline.processBatch(notifications)
```

which uses raw notification as storage notification.

This means direct/batch callers can still persist raw text under non-raw modes.

Status:

```text
P2-11: still partial outside worker/listener path.
```

Required fix:

- make unsafe overload internal/test-only, or
- load privacy settings and sanitize internally, or
- require `NotificationPersistencePayload`.

---

# `RawNotification.isProcessed` status

Improved:

- Repository now marks processed after terminal outcomes.
- Worker also marks processed best-effort.

Remaining caveat:

`processAndSaveAll()` logs outcomes but does not mark processed for batch outcomes.

`pipeline.processBatch()` returns outcomes, but repository does not call `dao.markProcessed(...)` for batch outcomes.

Status:

```text
P1-NEW-14: mostly fixed for single/worker paths, partial for batch.
```

Fix:

```kotlin
processAndSaveAll(...).also { outcomes ->
    outcomes.forEach { markProcessedIfNeeded(it) }
}
```

---

# Source-link typed result

Improved:

`writeNotificationDedupeSourceLink(...)` now returns `SourceLinkWriteResult`.

That closes the previous “Unit only” gap for this helper.

Remaining caveat:

- This is only for dedupe source links.
- Pending-review source-link service still has its own `linkResult.hasFatalFailure` behavior.
- Auto-accept/source-link coverage should be verified separately.

Status:

```text
P1-NEW-18: mostly fixed for dedupe source-link path.
```

---

# Location issue

Functional GPS read is gone:

```text
getLastKnownLocation: no match
```

But `NotificationProcessingPipeline` still injects:

```kotlin
ForegroundLocationProvider
```

and comments say it is kept for test constructors.

Status:

```text
P1-NEW-16: fixed functionally, cleanup remains.
```

---

# Dedupe cleanup still open

Service dedupe uses SHA-256 for content, but package/key hashing still uses JVM `hashCode()`:

```kotlin
val pkgHash = packageName.hashCode().toString(36)
val keyHash = notificationKey.hashCode().toString(36)
```

Also the key includes:

```kotlin
postTime
```

which avoids dropping identical legitimate transactions, but can miss duplicate callbacks with changed postTime.

`computeNotificationContentHash()` still exists as a deprecated `hashCode()` helper.

Status:

```text
Dedupe: acceptable but not clean.
```

---

# Refresh diagnostics still mislabeled

Manual refresh still does:

```kotlin
activeNotifications.forEach { sbn -> onNotificationPosted(sbn) }
```

and intake source is still:

```kotlin
source = "listener"
```

So refresh events look like listener events.

Status:

```text
GAP-REFRESH-SOURCE: open.
```

Fix:

```kotlin
enqueueNotificationCapture(sbn, CaptureSource.REFRESH)
```

---

# Service decomposition still partial

`NotificationCaptureService` still owns:

- lifecycle,
- foreground service,
- restart alarm,
- capture orchestration,
- gate call,
- extraction,
- dedupe key building,
- filtering,
- privacy second-check,
- settings/extras policy,
- intake call,
- diagnostics,
- legacy direct `processNotification(...)` method.

Status:

```text
P3-13: partial.
```

---

# New regressions found at `45a49cf`

## REG-45-01 — final/filter-rejected intake rows may retain transient payload

This is the most important new/remaining issue after the hotfix.

Affected paths:

- `FILTER_REJECTED`
- `MAX_ATTEMPTS_EXCEEDED`
- non-retryable pre-terminal exception

All can leave raw transient payload in `notification_intake`.

Severity:

```text
P1 privacy.
```

## REG-45-02 — pending review privacy can depend on settings changed after capture

Pipeline sanitizes pending review text using current privacy settings, not captured intake mode.

Severity:

```text
P1/P2 privacy edge case.
```

## REG-45-03 — transient payload remains plaintext

Not new in this exact commit, but still unresolved.

Severity:

```text
P1 privacy.
```

---

# Issue status table after `45a49cf`

| Issue | Status | Comment |
|---|---:|---|
| P1-P1-01 | ✅ Fixed | Outcome propagation OK. |
| P1-P1-02 | ✅ Mostly fixed | Safe diagnostics OK. |
| P1-P1-03 | ✅ Mostly fixed | Messaging extraction OK; stale helper remains. |
| P1-P1-05 | ✅ Mostly fixed | Gate mostly OK. |
| P1-P1-06 | ✅ Fixed | Known write barriers OK. |
| P1-P1-07 | ⚠ Partial | Runtime intake exists, but not early/app-scope enough. |
| P2-08 | ✅ Mostly fixed | Same path, but refresh source mislabeled. |
| P2-09 | ⚠ Improved/partial | Better deny lists, still no structured decision/direction. |
| P2-10 | ❌ Open/partial | Hardcoded USD/EUR fallback remains. |
| P2-11 | ⚠ Partial | Worker raw row leak fixed; transient/plaintext + public/batch gaps remain. |
| P2-12 | ❌ Open/partial | Provenance model not wired. |
| P3-13 | ⚠ Partial | Service still large. |
| P1-NEW-14 | ⚠ Mostly fixed | Batch path still not marking processed. |
| P1-NEW-16 | ✅ Mostly fixed | No GPS read; dead dependency remains. |
| P1-NEW-18 | ✅ Mostly fixed | Dedupe source-link typed result exists. |
| Dedupe cleanup | ⚠ Partial | JVM hashCode + postTime tradeoff. |
| Refresh source | ❌ Open | Refresh still source=`listener`. |

---

# Is Pipeline 1 clean and ready?

No.

It is **not ready** yet.

It is much safer than `ad91767` because the worker storage sanitizer fixed the most direct raw persistence leak into `raw_notifications`.

But to call Pipeline 1 clean, you still need at least these blockers fixed:

## Required before ready

1. **Purge transient payload on every terminal path**
   - filter rejected,
   - max attempts,
   - final exception,
   - normal terminal outcomes.

2. **Either encrypt transient payload or explicitly change product privacy policy**
   - especially for `DO_NOT_STORE`.

3. **Use captured rawStorageMode for all downstream persistence**
   - pending reviews must not depend on settings changed after capture.

4. **Fix parser provenance**
   - actual `parseWithProvenance()` integration.

5. **Fix currency fallback**
   - no silent USD/EUR default.

6. **Fix public/batch privacy sanitizer**
   - KDoc warning is not enough.

## Strongly recommended before ready

7. Move intake insertion earlier / app-scope.
8. Add structured finance filter decision.
9. Fix refresh source labeling.
10. Remove dead location dependency.
11. Clean service decomposition.

---

# Recommended next PR order

## PR 1 — Intake privacy completion

Fix:
- purge payload on all terminal paths;
- use captured rawStorageMode in PendingReview sanitizer;
- add tests for all raw storage modes and terminal statuses.

## PR 2 — Transient payload policy

Fix:
- encrypted transient payload, or explicit opt-in/disabled policy.

## PR 3 — Repository public/batch sanitizer

Fix:
- no direct raw storage through public/batch paths.

## PR 4 — Parser provenance

Fix:
- `ParseOutcome`;
- `parseWithProvenance`;
- pipeline diagnostics.

## PR 5 — Currency detector

Fix:
- shared money detector;
- no silent USD/EUR fallback.

## PR 6 — Finance filter structured decision

Fix:
- `NotificationFilterDecision`;
- direction;
- reasons;
- tests.

## PR 7 — Cleanup

Fix:
- refresh source;
- dedupe hash;
- remove location dependency;
- batch `isProcessed`;
- service decomposition.

---

# Specific tests to add now

## Intake privacy terminal tests

For each mode:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

Test:

1. normal processed outcome purges transient payload if needed.
2. filter rejected purges transient payload.
3. max attempts purges transient payload.
4. final exception purges transient payload.
5. retryable exception does not purge because retry needs payload.
6. raw_notifications respects mode.
7. pending_reviews respects captured mode.

## Captured-mode test

```text
capture row with DO_NOT_STORE
change PrivacySettings to STORE_RAW
worker creates pending review
assert pending review has no raw title/text
```

## Batch path test

```text
processAndSaveAll under DO_NOT_STORE
assert raw row does not contain raw body
assert isProcessed set when terminal
```

## Currency tests

```text
$ with CAD home currency -> CAD
$ with no home -> ambiguous, not silently USD
bare no-currency -> home/default with low-confidence or reject
```

## Parser provenance tests

```text
specific parser success
generic parser success
AI success
AI skipped
AI failed
```

---

# Final conclusion

`45a49cf` fixed the immediate worker raw-storage leak and the terminal-status regression. Good repair.

But Pipeline 1 is still **partial**, mainly because:

```text
raw transient intake payload is still plaintext,
not purged on all terminal paths,
and downstream pending-review sanitization can use the wrong storage mode.
```

So: **not clean / not ready yet**.