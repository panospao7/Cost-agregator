# Pipeline 1 Static Debug Report — Notification Capture → Expense → Dashboard

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 1 is **materially improved** from the old debugging report, but it is **not fully clean**.

Main conclusion:

- Several tracker items marked fixed are **only partially fixed** in code.
- The highest remaining user-impact risks are:
  1. **Silent notification loss on service shutdown/process death**.
  2. **Raw notification text leaking into `PendingReview` despite raw-storage privacy modes**.
  3. **Messaging-style notification extraction still likely broken**.
  4. **Pre-pipeline drops are still mostly invisible in diagnostics**.
  5. **Dedupe logic can both lose distinct notifications and reprocess duplicates**.

## Sources checked

- Latest commit page: https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba
- Pipeline master tracker: https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Pipeline 1 new debugging-session report: https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-1-notification-debug-report.md
- Current Pipeline 1 code:
  - `NotificationCaptureService.kt`
  - `NotificationProcessingPipeline.kt`
  - `NotificationRepository.kt`
  - `NotificationFilter.kt`

---

# 1. Tracker reconciliation

The master tracker currently says Pipeline 1 has:

| ID | Tracker status |
|---|---|
| P1-P1-01 | fixed |
| P1-P1-02 | partial |
| P1-P1-03 | fixed |
| P1-P1-05 | TODO |
| P1-P1-06 | fixed |
| P1-P1-07 | TODO |

After reviewing current code, I would update this to:

| ID | My status | Reason |
|---|---:|---|
| P1-P1-01 | **Partial** | `NotificationProcessingPipeline` returns sealed outcomes, but `NotificationRepository.processAndSave()` still returns `Unit`, so the service/callers cannot act on the real result. |
| P1-P1-02 | **Partial** | Pipeline outcomes write `PipelineDiagnosticEvent`, but service-level early exits do not. |
| P1-P1-03 | **Partial** | `textLines` are handled, but `EXTRA_MESSAGES` extraction is probably wrong for Android `MessagingStyle.Message` / bundle payloads. |
| P1-P1-04 | **Mostly fixed** | `combinedBody` is used, but stale `effectiveBigText` docs/fields remain. |
| P1-P1-05 | **Partial** | Fast settings cache blocks some denied cases before extraction, but full `PrivacyGate` still runs after extraction. |
| P1-P1-06 | **Fixed for writes** | `DatabaseWriteBarrier` exists in pipeline/repository. Missing diagnostics remain separate. |
| P1-P1-07 | **Unresolved / high risk** | No durable intake queue. `onDestroy()` cancels in-flight work. Retry suppression partly mitigated but data-loss root remains. |
| P2-08 | **Open/partial** | Manual refresh still calls `processNotificationBypassDedupe()`. |
| P2-09 | **Partial** | Finance-package hard deny exists, but balance-only/currency-only bank alerts can still pass. |
| P2-10 | **Partial** | Filter supports more currencies, but pipeline fallback regex still mostly EUR/USD/GBP. |
| P2-11 | **Partial with privacy bug** | RawNotification storage is sanitized, but pending-review rows can still store raw title/text. |
| P2-12 | **Partial** | AI fallback provenance event exists, but provider/confidence contract is incomplete and deterministic parse is repeated. |
| P3-13 | **Open architectural work** | Service remains large and multi-responsibility. |

---

# 2. Original issues — detailed evaluation

## P1-P1-01 — Outcome flattening

### Current state

`NotificationProcessingPipeline` now has `NotificationPipelineOutcome`:

- `AutoAccepted`
- `NeedsReview`
- `Duplicate`
- `ParserFailed`
- `AutoRejected`
- `Dropped`
- `Error`

That part is good.

However, `NotificationRepository.processAndSave()` logs the outcome and returns `Unit`. This means `NotificationCaptureService` still cannot know whether processing created an expense, created a review, failed, duplicated, or dropped.

There is also dead/stale code:

- `ProcessingResult` still exists.
- A TODO still says to return sealed processing outcome, even though the pipeline partially does.

### Classification

- **Actual bug:** debug correctness / service behavior.
- **Architectural cleanup:** remove dead result class and stale TODO.

### Fix strategy

Change repository methods to return outcomes:

```kotlin
suspend fun processAndSave(...): NotificationPipelineOutcome
suspend fun processAndSaveAll(...): List<NotificationPipelineOutcome>
```

Then the service can:

- keep or remove dedupe keys based on actual outcome,
- write service-level diagnostics,
- log truthfully,
- avoid saying “Processed notification” after an error/drop.

---

## P1-P1-02 — Durable diagnostic/drop ledger

### Current state

Good:

- `PipelineDiagnosticEventDao` is used.
- Pipeline outcomes write diagnostic events.
- Batch processing writes events per result.
- Parser provenance writes parse-stage events.

Still missing:

- `RECEIVED` event at listener entry.
- `DROPPED_RESTORE_MODE` from service-level restore guard.
- `DROPPED_PRIVACY` from fast pre-extraction gate.
- `DROPPED_FILTER` from `NotificationFilter.shouldCapture()`.
- `DROPPED_BLOCKED_PACKAGE`.
- `PIPELINE_CANCELLED`.
- `SERVICE_SHUTDOWN_IN_FLIGHT`.

### User impact

If a user says “my bank notification didn’t appear,” the app still cannot always tell whether it died at:

- restore gate,
- privacy gate,
- filter,
- blocked package,
- service shutdown,
- repository/pipeline.

### Fix strategy

Add `NotificationCaptureDiagnostics` as the only capture-stage recorder.

It should expose:

```kotlin
recordReceived(...)
recordDroppedPrivacy(...)
recordDroppedRestore(...)
recordDroppedFilter(...)
recordDroppedBlockedPackage(...)
recordPipelineOutcome(...)
recordCancelled(...)
```

Use `PipelineDiagnosticEvent` if diagnostic writes are allowed. If restore mode blocks DB writes, use a small DataStore/ring-buffer fallback or explicitly allow diagnostics under a safe policy.

---

## P1-P1-03 — `textLines` and `messages`

### Current state

Good:

- `NotificationTextParts` now includes `textLines`, `messages`, `combinedBody`.
- `combinedBody` is passed to filter/parser/hash/raw notification as `bigText`.
- `EXTRA_TEXT_LINES` is handled.

Problem:

The `EXTRA_MESSAGES` extraction uses CharSequence-style APIs/casts. Android messaging notifications typically store message objects/bundles, not plain `CharSequence`. Therefore message extraction is likely empty for real MessagingStyle notifications.

### User impact

Valid SMS / messaging-style bank alerts can still be missed if the transaction data lives only inside `EXTRA_MESSAGES`.

### Fix strategy

Use Android messaging extraction APIs:

- Read `Notification.EXTRA_MESSAGES` as parcelables/bundles.
- Convert with `Notification.MessagingStyle.Message.getMessagesFromBundleArray(...)` where available.
- Extract each message text, sender, timestamp if useful.
- Add API-version tests.

Required tests:

- amount only in `EXTRA_TEXT_LINES` captured,
- amount only in `EXTRA_MESSAGES` captured,
- message bundle payload captured,
- duplicate text is not inflated.

---

## P1-P1-05 — Privacy gate before extraction

### Current state

Good:

- Service has a fast cached `capturePrivacyDenied` flag.
- It checks this before reading extras.
- It then checks full `PrivacyGate` inside the coroutine.

Remaining issues:

1. The pre-extraction check is based on `PrivacySettingsRepository.observeSettings()`, not necessarily the full `PrivacyGate` decision.
2. If `PrivacyGate` denies for another reason, extraction already happened.
3. `capturePrivacyDenied` starts as `true`; notifications may be silently dropped before the first settings emission.
4. Blocked-package check happens after extraction.
5. Service-level privacy drops do not write diagnostics.

### Classification

- **Actual privacy bug / possible false-negative capture bug.**
- Also architectural: needs a formal capture-gate component.

### Fix strategy

Create `NotificationCaptureGate`:

```kotlin
data class NotificationCaptureDecision(
    val allowed: Boolean,
    val reason: String?,
    val source: String
)
```

It should combine:

- notification privacy setting,
- full `PrivacyGate`,
- restore mode,
- blocked-package cache,
- service shutdown state.

No extras/text extraction should happen before `allowed == true`.

Also initialize synchronously on service start using current settings to avoid fail-closed silent drops during flow startup.

---

## P1-P1-06 — Restore/write barrier

### Current state

Mostly fixed.

Evidence:

- `NotificationProcessingPipeline.process()` calls `writeBarrier.checkWritesAllowed(...)`.
- `processBatch()` does too.
- `NotificationRepository.save/delete` also check write barrier.
- Service also checks restore mode before processing.

Remaining gap:

- Restore drops at service level are not durable diagnostics.

### Classification

- **User write-safety bug fixed.**
- **Observability still partial.**

---

## P1-P1-07 — Service shutdown can lose notifications

### Current state

Still unresolved.

Good partial mitigation:

- The in-memory dedupe key is removed in `finally`, so cancellation does not permanently suppress retry in the same process.

But root problem remains:

- The listener accepts notification.
- Processing is async.
- `onDestroy()` cancels `serviceJob`.
- No durable raw/intake row is guaranteed before cancellation.
- If process/service dies, Android may not repost the same notification.
- Code comments explicitly say durable intake is still planned.

### User impact

A real bank notification can be lost. This is a direct user-impact bug.

### Fix strategy

Implement durable intake queue.

Preferred design:

1. After pre-extraction privacy/restore/filter gates, insert a durable intake row quickly:
   - package,
   - notification key hash,
   - post time,
   - content fingerprint,
   - sanitized/raw body according to storage mode,
   - status = `RECEIVED`.
2. Enqueue `RawNotificationProcessingWorker` / pipeline worker by intake ID.
3. Worker transitions:
   - `RECEIVED -> PROCESSING -> PROCESSED`
   - `FAILED_RETRYABLE`
   - `FAILED_FINAL`
   - `DROPPED_DUPLICATE`
4. On app start, resume stale `RECEIVED/PROCESSING` rows.

Alternative short-term patch:

- Do not remove dedupe key on success.
- Remove only on cancellation before durable DB insert.
- Return repository outcome to service so it can decide.

But this does not solve process-death loss.

---

# 3. New/reopened issues found in current code

## P1-NEW-01 — Raw-storage privacy mode bypass via `PendingReview`

### Severity

P0/P1 privacy depending on product promise.

### Evidence

`NotificationCaptureService` creates:

- `processingNotification` with raw title/text/combinedBody,
- `storageNotification` sanitized according to `RawStorageMode`.

But parser-failed and transaction-signal review paths create `PendingReview` using raw `notification.title` and `notification.text ?: notification.bigText`.

Therefore, even if `RawStorageMode.METADATA_ONLY`, `STORE_REDACTED`, or `DO_NOT_STORE` is selected, raw notification text can still be persisted in `pending_reviews`.

### User impact

User thinks raw notification content is not retained, but it may be stored in review rows.

### Fix strategy

Introduce a sanitizer for all persistence targets, not only `RawNotification`.

Use one policy object:

```kotlin
data class NotificationPersistencePayload(
    val rawNotification: RawNotification,
    val reviewTitle: String?,
    val reviewText: String?,
    val diagnosticMessage: String?,
    val transactionEventReason: String?
)
```

Rules:

- `STORE_RAW`: allow raw review title/text.
- `STORE_REDACTED`: store redacted title/text.
- `STORE_METADATA_ONLY`: store package/app/timestamp/fingerprint plus parsed merchant/amount only.
- `DO_NOT_STORE`: store no body text anywhere.

Add tests that inspect:

- `raw_notifications`,
- `pending_reviews`,
- `transaction_events`,
- `pipeline_diagnostic_events`.

---

## P1-NEW-02 — In-memory dedupe is removed after successful processing

### Severity

P1/P2.

### Evidence

`onNotificationPosted()` stores `processedNotifications[coarseDedupeKey] = now`, then the coroutine `finally` always removes the key.

This means the in-memory dedupe window only protects while work is in-flight. After success, immediate repeated callbacks can re-enter the pipeline.

DB duplicate checks may catch exact duplicates, but not all active notification updates/timestamp changes.

### User impact

Extra parser/AI work, noisy diagnostics, duplicate pending reviews in edge cases.

### Fix strategy

Keep dedupe key on success until TTL expires. Remove only if:

- coroutine cancelled before durable intake insert,
- pipeline returned `Error` before durable insert,
- no DB row was created and retry is desired.

Requires repository to return outcome.

---

## P1-NEW-03 — Coarse `sbn.key` dedupe can drop distinct transactions

### Severity

P1.

### Evidence

The code comment already notes that `sbn.key` is coarse and can represent different transactions if a bank reuses notification ID/tag.

Because dedupe happens before text extraction, a second distinct transaction within the 5-second window can be dropped without content comparison.

### User impact

Silent missed transaction.

### Fix strategy

Use a content-aware in-flight key:

```text
packageName + sbn.key + postTime + hash(title/text/combinedBody)
```

But privacy rule matters: compute content hash only after privacy allows extraction. Before that, do not mark as processed.

Durable DB uniqueness should be based on `dedupeFingerprint`, not just `package/timestamp/title/text`.

---

## P1-NEW-04 — Full `PrivacyGate` denial still happens after extraction

### Severity

P1 privacy.

### Evidence

Fast pre-extraction check uses settings cache. The full `PrivacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)` happens after extraction/filter.

### User impact

If denial comes from a higher-level policy/fail-closed state rather than the raw setting, text is still read in memory.

### Fix strategy

The pre-extraction gate must call or cache the same decision source as `PrivacyGate`.

If `PrivacyGate` is suspend-only, maintain a hot cached `StateFlow<PrivacyDecision>` in `NotificationCaptureGate`.

---

## P1-NEW-05 — Blocked packages are checked too late

### Severity

P1/P2 privacy.

### Evidence

`repository.isPackageBlocked(packageName)` is called inside `processNotification()`, after extras were extracted and after `extrasJson` can be built.

### User impact

A user-blocked package still has notification text read into memory before being dropped.

### Fix strategy

Maintain a cached blocked-package set in the service/gate. Check package block before extraction.

---

## P1-NEW-06 — Possible location privacy issue in notification pipeline

### Severity

Needs verification; likely P1 if not gated inside provider.

### Evidence

`NotificationProcessingPipeline.buildPreDbContext()` calls `locationProvider.getLastKnownLocation()` for accepted/review paths.

I did not see a local `PrivacyGate` check around this call in the reviewed code.

### User impact

Notification processing may attach GPS/location context unexpectedly.

### Fix strategy

Verify whether `ForegroundLocationProvider` internally gates `DEVICE_GPS_LOCATION`.

If yes:

- add static/unit test proving notification pipeline cannot bypass it,
- add diagnostic event when location is denied.

If no:

- inject `PrivacyGate`,
- check location capability before calling provider,
- default to `null` location when denied.

---

## P1-NEW-07 — AI fallback provenance is incomplete and inefficient

### Severity

P2.

### Evidence

Pipeline calls `parseWithAiFallback()`, then calls deterministic `parse()` again only to infer whether AI fallback was used.

### User impact

Mostly performance/observability. If parser ever has side effects, this can become correctness risk.

### Fix strategy

Change parser contract:

```kotlin
data class ParseOutcome(
    val parsed: ParsedTransaction?,
    val parserName: String?,
    val usedAiFallback: Boolean,
    val aiProvider: String?,
    val confidence: Float?,
    val failureReason: String?
)
```

Store this in diagnostics/review metadata.

---

## P1-NEW-08 — Currency fallback remains EUR/USD/GBP-heavy

### Severity

P2, can become P1 for non-EUR users.

### Evidence

`NotificationFilter` supports more ISO currencies, but `NotificationProcessingPipeline` fallback regexes still focus on EUR/USD/GBP, and fallback currency defaults to EUR.

### User impact

When deterministic parsing fails, non-EUR transaction-signal fallback can create wrong-currency reviews or fail to create review.

### Fix strategy

Use one shared `NotificationAmountSignalDetector` / money parser for:

- filter,
- parser fallback,
- oversized detection,
- transaction-signal detection.

It should support the same currency set as the app.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize these:

1. **Service shutdown/process death can lose notifications.**
2. **Raw text can persist in pending reviews despite raw-storage privacy mode.**
3. **Messaging-style notifications may still not extract transaction text.**
4. **Coarse in-flight dedupe can drop different transactions using the same status-bar key.**
5. **Initial privacy cache may silently drop notifications before settings emission.**
6. **Blocked packages are checked after text extraction.**
7. **Non-EUR fallback currency handling can produce wrong pending reviews.**
8. **Possible location capture without explicit notification-pipeline privacy gate.**

## Architectural / cleanup work

These are valuable but should not block emergency bug fixes:

1. Split `NotificationCaptureService` into smaller components.
2. Remove dead `ProcessingResult`.
3. Make repository outcome-returning.
4. Standardize parser provenance.
5. Standardize diagnostic event enums.
6. Add service-level diagnostic ring buffer.
7. Consolidate currency detection.

---

# 5. Recommended implementation plan

## PR 1 — Return outcomes and fix diagnostics

### Goal

Make every stage observable and let service react to real outcomes.

### Files

- `NotificationRepository.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationCaptureService.kt`
- new `NotificationCaptureDiagnostics.kt`
- `PipelineDiagnosticEvent.kt` / DAO if enum fields are needed

### Tasks

1. Change repository methods to return outcome(s).
2. Remove/deprecate `ProcessingResult`.
3. Add service-level diagnostic events:
   - `RECEIVED`
   - `DROPPED_RESTORE_MODE`
   - `DROPPED_PRIVACY`
   - `DROPPED_FILTER`
   - `DROPPED_BLOCKED_PACKAGE`
   - `PIPELINE_CANCELLED`
4. Stop logging success after pipeline error/drop.
5. Add tests for each early-exit path.

### Acceptance

For every notification callback, there is either:

- a durable `RECEIVED` + final event, or
- a durable drop event explaining why no raw/review/expense row exists.

---

## PR 2 — Privacy persistence hardening

### Goal

Raw-storage mode applies to **all persisted tables**, not only `raw_notifications`.

### Files

- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- `PendingReview` creation paths
- `TransactionEvent` creation paths
- new `NotificationPersistenceSanitizer.kt`

### Tasks

1. Build one sanitized persistence payload.
2. Use sanitized title/text in:
   - `RawNotification`,
   - `PendingReview.notificationTitle`,
   - `PendingReview.notificationText`,
   - diagnostics,
   - transaction-event reason/metadata.
3. Do not call `buildExtrasJson()` when storage mode is metadata-only or do-not-store.
4. Add tests for each `RawStorageMode`.

### Acceptance

With `DO_NOT_STORE`, no raw notification body appears in:

- raw notifications,
- pending reviews,
- transaction events,
- diagnostics.

---

## PR 3 — Correct extraction for messaging notifications

### Goal

Real `Notification.EXTRA_MESSAGES` payloads are parsed.

### Files

- `NotificationTextParts`
- possibly new `NotificationExtractor.kt`
- tests

### Tasks

1. Extract `MessagingStyle.Message` text properly.
2. Support parcelable bundle arrays.
3. Add fixtures for:
   - `EXTRA_TEXT_LINES`,
   - `EXTRA_MESSAGES`,
   - mixed bigText + summaryText,
   - duplicate text.

### Acceptance

A bank/SMS transaction where amount exists only in `EXTRA_MESSAGES` is captured and fingerprinted.

---

## PR 4 — Durable notification intake queue

### Goal

No accepted notification can be lost after listener callback.

### Files

- new `NotificationIntakeEntity.kt`
- new `NotificationIntakeDao.kt`
- `AppDatabase.kt`
- `NotificationCaptureService.kt`
- new `RawNotificationProcessingWorker.kt`
- `NotificationProcessingPipeline.kt`

### Tasks

1. Add durable intake table with status:
   - `RECEIVED`
   - `PROCESSING`
   - `PROCESSED`
   - `FAILED_RETRYABLE`
   - `FAILED_FINAL`
   - `DROPPED_DUPLICATE`
2. Listener inserts intake row before expensive parser/AI work.
3. Worker resumes stale rows on startup.
4. Add retry/backoff.
5. Add content-aware unique fingerprint.

### Acceptance

If service is destroyed after callback but before processing, the notification is resumed later.

---

## PR 5 — Dedupe correctness

### Goal

No duplicate noise, no silent loss of distinct transactions.

### Files

- `NotificationCaptureService.kt`
- `RawNotificationFingerprint`
- DAO unique indexes/tests

### Tasks

1. Replace coarse `sbn.key`-only dedupe with content-aware in-flight key.
2. Keep successful dedupe entry until TTL.
3. Remove key only on cancellation before durable insert.
4. Manual refresh should use durable duplicate check before parser.

### Acceptance

- Same notification twice => one final outcome.
- Same `sbn.key` but different amount/body => two valid outcomes.
- Refresh same active notification twice => no duplicate review.

---

## PR 6 — Location and AI provenance hardening

### Goal

No hidden privacy side effects; parser source is explicit.

### Tasks

1. Verify/gate `locationProvider.getLastKnownLocation()`.
2. Add diagnostics for location denied/unavailable.
3. Replace `parseWithAiFallback()` with `ParseOutcome`.
4. Remove second deterministic parse.

---

# 6. Test plan for the agent

## Golden scenarios

1. Revolut card purchase -> auto-accept or review -> dashboard changes.
2. Greek bank purchase -> parser/review path works.
3. Gmail/SMS bank alert -> heuristic capture works.
4. Amount only in `EXTRA_TEXT_LINES` -> captured.
5. Amount only in `EXTRA_MESSAGES` -> captured.
6. Privacy denied -> no extraction, no raw/review/event text.
7. Restore active -> no raw/review/expense writes, diagnostic exists.
8. Duplicate notification -> duplicate outcome, no parser/AI call.
9. Parser failed but transaction signal exists -> pending review.
10. Service destroyed after receive -> intake row survives and worker resumes.
11. Same `sbn.key`, different body within 5 seconds -> both processed.
12. `RawStorageMode.DO_NOT_STORE` -> no raw body in any table.
13. Location privacy denied -> `locationProvider` not called or returns null with diagnostic.
14. AI fallback used -> diagnostic includes parser source/provider.

## Suggested command set

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # if emulator available
./gradlew lintDebug
grep -R "PendingReview(" app/src/main/java
grep -R "notificationTitle" app/src/main/java
grep -R "notificationText" app/src/main/java
grep -R "getLastKnownLocation" app/src/main/java
grep -R "processAndSave(" app/src/main/java
grep -R "RawStorageMode" app/src/main/java
```

---

# 7. Suggested tracker updates

Update Pipeline 1 in `PIPELINE_ISSUES_MASTER_TRACKER.md`:

| ID | Suggested status |
|---|---|
| P1-P1-01 | Partial |
| P1-P1-02 | Partial |
| P1-P1-03 | Partial |
| P1-P1-05 | Partial, not TODO |
| P1-P1-06 | Fixed with diagnostic caveat |
| P1-P1-07 | TODO / unresolved |
| P2-08 | TODO |
| P2-09 | Partial |
| P2-10 | Partial |
| P2-11 | Partial / privacy bug |
| P2-12 | Partial |
| P3-13 | TODO architectural |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P1-NEW-01 | P0/P1 | Raw-storage mode bypass via pending reviews |
| P1-NEW-02 | P1/P2 | Dedupe key removed after successful processing |
| P1-NEW-03 | P1 | Coarse `sbn.key` dedupe can drop distinct transactions |
| P1-NEW-04 | P1 | Full `PrivacyGate` denial still after extraction |
| P1-NEW-05 | P1/P2 | Blocked-package check after extraction |
| P1-NEW-06 | Verify/P1 | Location provider call needs explicit privacy proof |
| P1-NEW-07 | P2 | AI provenance contract incomplete |
| P1-NEW-08 | P2 | Currency fallback still too narrow |

---

# 8. Agent-ready priority order

Do this order:

1. **Privacy persistence fix** — prevent raw text leaking into pending reviews.
2. **Repository outcome return + diagnostics** — needed by later dedupe/shutdown fixes.
3. **Dedupe correctness short-term patch** — keep success keys, content-aware key.
4. **Messaging extraction fix** — real capture correctness.
5. **Durable intake queue** — bigger but necessary for true shutdown safety.
6. **Full capture gate** — unify privacy/restore/blocked-package checks before extraction.
7. **Currency fallback consolidation**.
8. **Location gate verification/fix**.
9. **Service decomposition cleanup**.