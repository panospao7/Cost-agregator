# Pipeline 1 Debug Report — Notification Capture → Expense → Dashboard

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static code/debug rerun, not local device/Gradle execution.

## Verdict

Pipeline 1 is **much better than the older baseline**, but I would not call it fully clean/stable yet.

Current state:

- Listener exists and has restore-mode guard.
- Privacy gate exists before persistence.
- Text extraction is unified for several fields.
- Raw duplicate pre-check now happens before expensive parser/AI fallback.
- Auto-accepted expenses go through `TransactionLifecycleCoordinator` with deferred side effects.
- Needs-review and parser-failed fallback paths are stronger than before.

Main remaining instability is **observability, extraction completeness, privacy/raw-text policy, and shutdown/durable-processing safety**.

---

# Severity scale

- **P0 / Critical:** can lose user money data, create duplicates, or violate privacy.
- **P1 / High:** major pipeline instability or impossible-to-debug behavior.
- **P2 / Medium:** correctness gap for common edge cases.
- **P3 / Low:** cleanup, polish, maintainability.

---

# Issue P1-01 — Processing outcomes are flattened to `Success`

## Severity

P1 / High

## Evidence

`NotificationProcessingPipeline.process()` returns `ProcessingResult.Success` whenever `processInternal()` returns normally, even when the internal result was:

- duplicate
- parser failed
- auto rejected
- needs review
- auto accepted

There is already a TODO in `NotificationProcessingPipeline.kt`:

```text
TODO (P2-1): Return sealed ProcessingOutcome from processInternal() instead of flattening everything to Success/Error.
```

`NotificationRepository.processAndSave()` then treats most outcomes as success and only logs rejected/error.

## Impact

The caller cannot know what happened. The debug UI cannot reliably show:

- last drop reason
- duplicate reason
- parser failure
- review created
- expense created
- auto rejected
- DB write attempted/succeeded

This directly violates the pipeline-debug checklist.

## Fixing strategy

Create a real outcome contract.

## Implementation plan

1. Add sealed outcome:

```kotlin
sealed interface NotificationPipelineOutcome {
    data class AutoAccepted(val rawId: Long, val expenseId: Long) : NotificationPipelineOutcome
    data class NeedsReview(val rawId: Long, val reviewId: Long) : NotificationPipelineOutcome
    data class Duplicate(val packageName: String, val reason: String) : NotificationPipelineOutcome
    data class ParserFailed(val rawId: Long?, val reason: String) : NotificationPipelineOutcome
    data class AutoRejected(val rawId: Long?, val reason: String) : NotificationPipelineOutcome
    data class Dropped(val packageName: String, val reason: DropReason) : NotificationPipelineOutcome
    data class Error(val packageName: String, val throwable: Throwable) : NotificationPipelineOutcome
}
```

2. Make `processInternal()` return this instead of `Unit`.

3. Update `NotificationRepository.processAndSave()` and `processAndSaveAll()` to propagate/log the real outcome.

4. Add tests:

```text
duplicate_returns_Duplicate
parser_failure_returns_ParserFailed
auto_reject_returns_AutoRejected
needs_review_returns_NeedsReview_with_reviewId
auto_accept_returns_AutoAccepted_with_expenseId
```

---

# Issue P1-02 — No durable notification diagnostic/drop-reason ledger

## Severity

P1 / High

## Evidence

`NotificationCaptureService` records service start/disconnect/killed via `ServiceDiagnostics`, but most pipeline decisions are only `Timber` logs:

- restore drop
- filter drop
- privacy denial
- blocked package
- duplicate
- parser failure
- review created
- expense created
- DB error

The checklist requires a standard enum:

```text
RECEIVED
DROPPED_FILTER
DROPPED_PRIVACY
DROPPED_RESTORE_MODE
DROPPED_DUPLICATE
PARSER_FAILED
REVIEW_CREATED
EXPENSE_CREATED
DB_ERROR
PIPELINE_ERROR
```

## Impact

If the user says “my Revolut notification did not appear,” the app cannot answer where it died.

## Fixing strategy

Add a DB-backed or DataStore-backed pipeline diagnostics ledger.

## Implementation plan

1. Add entity:

```kotlin
@Entity(tableName = "notification_pipeline_events")
data class NotificationPipelineEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String?,
    val stage: String,
    val outcome: String,
    val dropReason: String?,
    val rawNotificationId: Long?,
    val reviewId: Long?,
    val expenseId: Long?,
    val timestamp: Long,
    val message: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?
)
```

2. Add DAO:

```kotlin
@Dao
interface NotificationPipelineEventDao {
    @Insert suspend fun insert(event: NotificationPipelineEvent)
    @Query("SELECT * FROM notification_pipeline_events ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NotificationPipelineEvent>>
}
```

3. Write events at every exit point:
   - service received
   - restore blocked
   - filter blocked
   - privacy denied
   - blocked package
   - duplicate
   - parser failed
   - review created
   - expense created
   - DB error

4. Add a compact debug summary:

```kotlin
data class NotificationPipelineHealth(
    val lastReceivedAt: Long?,
    val lastSuccessAt: Long?,
    val lastDropReason: String?,
    val lastException: String?,
    val listenerConnected: Boolean,
    val restoreWritesAllowed: Boolean
)
```

5. Tests:
   - each drop path inserts exactly one event
   - success path inserts received + final success
   - exception path inserts `PIPELINE_ERROR`

---

# Issue P1-03 — Extraction misses `textLines` and notification `messages`

## Severity

P1 / High

## Evidence

`NotificationTextParts.extract()` extracts:

- title
- text
- bigText
- subText
- infoText
- summaryText

But the checklist also requires:

- `textLines`
- `messages`

Many bank/SMS/email notifications place transaction details in `Notification.EXTRA_TEXT_LINES` or messaging-style extras.

## Impact

The filter/parser can drop valid transaction notifications because the amount/merchant is not in `title`, `text`, or `effectiveBigText`.

## Fixing strategy

Replace “single effective bigText” with a full normalized text payload.

## Implementation plan

1. Extend model:

```kotlin
internal data class NotificationTextParts(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val summaryText: String?,
    val textLines: List<String>,
    val messages: List<String>,
    val combinedBody: String
)
```

2. Extract:

```kotlin
Notification.EXTRA_TEXT_LINES
Notification.EXTRA_MESSAGES
Notification.EXTRA_TITLE_BIG
```

3. Build `combinedBody` from all unique non-blank values.

4. Pass `combinedBody` to:
   - `NotificationFilter.shouldCapture`
   - `computeNotificationContentHash`
   - `RawNotification.bigText` or new `combinedText` field
   - parser registry

5. Add tests:
   - amount only in `EXTRA_TEXT_LINES` is captured
   - amount only in `EXTRA_MESSAGES` is captured
   - duplicate hash changes when textLines change
   - no duplicate text inflation

---

# Issue P1-04 — `effectiveBigText` is lossy

## Severity

P2 / Medium

## Evidence

`effectiveBigText` chooses:

```kotlin
bigText ?: infoText ?: summaryText
```

If `bigText` exists but the amount is in `summaryText`, the summary is ignored.

## Impact

False negatives in filter/parser and incorrect fingerprinting.

## Fixing strategy

Use `combinedBody`, not a single fallback field.

## Implementation plan

1. Keep `bigText` as raw original.
2. Add `combinedBody`.
3. Use combined text for capture/filter/parser.
4. Keep raw columns unchanged if migration cost is high.
5. Add regression test:

```text
bigText_present_but_amount_in_summaryText_is_captured
```

---

# Issue P1-05 — Privacy gate runs after text extraction/filter

## Severity

P1 / High for privacy correctness

## Evidence

`onNotificationPosted()` does:

1. restore guard
2. extract notification text
3. filter
4. dedupe
5. launch coroutine
6. check `PrivacyGate`

The KDoc says the gate protects persistence, which is true, but the debug checklist expects privacy gate decision as a first-class gate.

## Impact

If notification capture is disabled, the app still reads notification contents in memory and applies heuristics. That may be acceptable technically, but it is not the strictest privacy contract.

## Fixing strategy

Check privacy before reading extras whenever possible.

## Implementation plan

1. In `onNotificationPosted()`:
   - get only `packageName`
   - check restore
   - launch coroutine or use fast cached privacy state
   - if denied, record diagnostic and return
   - only then extract text

2. Avoid expensive suspend call on main listener callback by maintaining a cached `StateFlow<PrivacyDecision>` in a lightweight `NotificationCaptureGate`.

3. Add tests:
   - privacy denied does not call extractor
   - privacy denied records `DROPPED_PRIVACY`
   - privacy allowed proceeds

---

# Issue P1-06 — Restore/write guard exists in service but not in repository pipeline

## Severity

P1 / High

## Evidence

`NotificationCaptureService` checks `restoreMaintenanceMode.isWritesAllowed()` in:

- `onNotificationPosted`
- `processNotificationBypassDedupe`

But `NotificationProcessingPipeline.process()` itself does not visibly own a restore/write guard.

## Impact

Any non-service caller of `NotificationRepository.processAndSave()` or `processAndSaveAll()` can still write during restore unless another global DB write barrier catches it.

## Fixing strategy

Guard writes at the lowest shared write boundary, not only in the listener.

## Implementation plan

1. Inject `RestoreMaintenanceMode` or `WriteBarrier` into `NotificationProcessingPipeline`.
2. Check before any DB transaction.
3. Return `NotificationPipelineOutcome.Dropped(DROPPED_RESTORE_MODE)`.
4. Add tests:
   - direct repository call during restore creates no raw notification
   - batch processing during restore creates no rows
   - diagnostic event is written only if diagnostics are allowed during restore

---

# Issue P1-07 — Service shutdown can silently lose accepted notifications

## Severity

P1 / High

## Evidence

`onNotificationPosted()` puts the dedupe key in memory before async processing.  
`onDestroy()` then sets `isShuttingDown = true` and cancels `serviceJob`.

So this sequence can happen:

1. notification received
2. dedupe key stored
3. coroutine launched
4. service destroyed
5. job cancelled before DB write
6. notification not persisted
7. short-term duplicate cache may suppress retry

## Impact

A real bank notification can be lost.

## Fixing strategy

Make the first durable write happen before the pipeline can be cancelled, or remove dedupe entry when processing is cancelled.

## Implementation plan

Preferred:

1. Split pipeline into:
   - `captureRawNotification()` durable insert
   - async processing of raw rows
2. On notification:
   - check gates
   - insert raw row quickly
   - enqueue processing work by raw ID
3. If service dies after raw insert, a worker can resume unprocessed raw notifications.

Alternative:

1. Wrap coroutine with `try/finally`.
2. If cancelled before `repository.processAndSave()` completes, remove dedupe key.
3. Record `PIPELINE_CANCELLED`.

Tests:
- cancelling service job before DB insert does not suppress retry
- raw notification survives process death
- worker resumes unprocessed raw notification

---

# Issue P2-08 — Manual refresh bypasses in-memory dedupe

## Severity

P2 / Medium

## Evidence

`refreshActiveNotifications()` calls `processNotificationBypassDedupe()` for each active notification.

DB-level duplicate checks probably catch exact duplicates, but this still performs work and can hit parser/AI/review paths unnecessarily.

## Impact

Manual refresh can produce extra load and noisy logs. If timestamps/content differ slightly, it may create extra pending reviews.

## Fixing strategy

Do not bypass all dedupe. Bypass only the short 5-second callback suppression, not durable DB duplicate detection.

## Implementation plan

1. Rename method to `processActiveNotificationRefresh`.
2. Use a stable durable fingerprint check before parser.
3. Record outcome as `DROPPED_DUPLICATE` when already known.
4. Add test:
   - refresh same active notification twice creates one raw row and one final outcome.

---

# Issue P2-09 — Finance packages bypass all filter/deny heuristics

## Severity

P2 / Medium

## Evidence

`NotificationFilter.shouldCapture()` returns true immediately for packages in `FINANCE_PACKAGES`.

KDoc says bank 2FA/promo filtering is handled downstream.

## Impact

Bank 2FA, promo, security, and balance-only alerts may be persisted as raw notifications. This increases privacy exposure and debug noise.

## Fixing strategy

Keep finance packages high-trust, but add a small “hard deny unless transaction-like” layer for clearly non-transactional content.

## Implementation plan

1. Add finance-safe deny categories:
   - password reset
   - login attempt
   - verification code
   - security code
   - promo-only
2. For finance packages:
   - if content has hard-deny and no transaction amount/keyword, drop
   - if content has amount + transaction keyword, keep
3. Add tests:
   - bank card purchase captured
   - bank 2FA dropped
   - bank promo without transaction amount dropped
   - bank transfer captured

---

# Issue P2-10 — Currency/amount filter is too narrow for supported currencies

## Severity

P2 / Medium

## Evidence

`NotificationFilter` currency regex supports symbols and only:

```text
EUR, USD, GBP, CHF
```

The README says the app supports many currencies.

## Impact

Non-finance-package transaction alerts in other currencies may be dropped.

## Fixing strategy

Use the app’s `CurrencyCode`/supported-currency list or a shared money parser instead of local regex.

## Implementation plan

1. Create shared `NotificationAmountSignalDetector`.
2. Support all configured currency codes.
3. Include common symbols and ISO codes.
4. Add tests for:
   - PLN
   - RON
   - TRY
   - CAD
   - AUD
   - no-currency decimal amount with financial keyword

---

# Issue P2-11 — Raw text storage policy is not granular

## Severity

P1/P2 depending on privacy requirements

## Evidence

If privacy gate allows notification capture, the app stores:

- title
- text
- bigText/effectiveBigText
- subText
- extrasJson

`buildExtrasJson()` redacts some sensitive keys, but generic Android keys can still contain raw body text.

## Impact

A user may want notification-derived expenses but not raw notification body retention.

## Fixing strategy

Separate “capture/process” permission from “store raw text” permission.

## Implementation plan

1. Add policy:

```kotlin
enum class RawNotificationStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    STORE_METADATA_ONLY
}
```

2. Apply sanitizer before building `RawNotification`.
3. If metadata-only:
   - keep package name, timestamp, fingerprint, parser outcome
   - do not store body text
4. Add tests:
   - raw disabled stores no title/text/bigText/extras body
   - parser still receives text in memory when capture allowed
   - debug UI shows redacted state

---

# Issue P2-12 — AI/provider fallback may occur inside notification pipeline without explicit per-call audit

## Severity

P2 / Medium

## Evidence

Pipeline calls:

```kotlin
parserRegistry.parseWithAiFallback(...)
```

Auto-accept audit exists, but parser AI fallback itself should be auditable if cloud/local AI is used.

## Impact

Hard to debug whether a parse came from deterministic parser or AI fallback. Privacy review is harder.

## Fixing strategy

Return parser provenance.

## Implementation plan

1. Change parser result to include:

```kotlin
data class ParseOutcome(
    val parsed: ParsedTransaction?,
    val parserName: String?,
    val usedAiFallback: Boolean,
    val aiProvider: String?,
    val confidence: Float,
    val failureReason: String?
)
```

2. Store provenance in pending review / transaction event metadata.
3. Add diagnostics:
   - `PARSER_SELECTED`
   - `AI_FALLBACK_USED`
   - `PARSER_FAILED`

---

# Issue P3-13 — `NotificationCaptureService` contains too many responsibilities

## Severity

P3 / Low/Medium

## Evidence

The service currently handles:

- Android listener lifecycle
- foreground service behavior
- restart alarm
- extraction
- filtering
- dedupe
- privacy gate
- restore gate
- raw entity creation
- extras JSON redaction

## Impact

Hard to test and easy to create mixed behavior.

## Fixing strategy

Extract pure components.

## Implementation plan

Create:

```text
NotificationExtractor
NotificationCaptureGate
NotificationCaptureDeduper
NotificationRawMapper
NotificationExtrasSanitizer
NotificationCaptureDiagnostics
```

Keep `NotificationCaptureService` as a thin Android adapter.

---

# Positive findings

These should be preserved:

1. `NotificationTextParts` creates a single extraction object used consistently.
2. Restore mode is checked before service-level processing.
3. Privacy gate exists before persistence.
4. `RawNotificationFingerprint` is computed consistently.
5. Pipeline performs DB duplicate pre-check before expensive parser/AI fallback.
6. Parser-failed transaction-signal fallback can create pending review instead of silently dropping.
7. Auto-accept uses `TransactionLifecycleCoordinator.createExpense(..., SideEffectMode.DEFER)`.
8. Auto-accept writes `AI_AUTO_ACCEPT` transaction event.
9. Post-commit side effects are separated from DB transaction.

---

# Recommended fixing order

## PR 1 — Diagnostics/outcome contract

Files:
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- new `NotificationPipelineEvent.kt`
- new `NotificationPipelineEventDao.kt`
- `AppDatabase.kt`

Goal:
- no more flattened success
- every exit path has a durable outcome

Tests:
- all major outcome paths

---

## PR 2 — Full extraction model

Files:
- `NotificationCaptureService.kt`
- new `NotificationExtractor.kt`
- `NotificationFilter.kt`
- parser tests

Goal:
- support `textLines`, `messages`, `combinedBody`

Tests:
- amount in textLines/messages captured
- amount in summary captured even when bigText exists

---

## PR 3 — Capture gate hardening

Files:
- `NotificationCaptureService.kt`
- new `NotificationCaptureGate.kt`
- `PrivacyGate`
- `RestoreMaintenanceMode`

Goal:
- privacy/restore checked before text extraction and again before DB writes

Tests:
- denied privacy does not extract
- restore blocks repository/direct batch writes

---

## PR 4 — Durable raw queue / shutdown safety

Files:
- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- maybe new worker: `RawNotificationProcessingWorker`

Goal:
- notification cannot be lost after listener callback accepts it

Tests:
- cancellation does not lose raw row
- unprocessed raw notification resumes

---

## PR 5 — Raw text storage policy

Files:
- privacy settings
- `RawNotification`
- `NotificationRawMapper`
- `buildExtrasJson` replacement

Goal:
- user can capture notifications without retaining full raw text

Tests:
- `STORE_REDACTED`
- `STORE_METADATA_ONLY`
- debug UI displays policy

---

# AI implementation checklist

Before coding:

```text
./gradlew test
grep -R "processAndSave(" app/src/main/java
grep -R "RawNotification(" app/src/main/java
grep -R "NotificationFilter.shouldCapture" app/src
grep -R "parseWithAiFallback" app/src/main/java
```

Required golden scenarios:

```text
1. Revolut card purchase → AUTO_ACCEPT or NEEDS_REVIEW → dashboard changes
2. Greek bank notification → parser/review path works
3. Gmail receipt/bank alert → heuristic capture works
4. Notification amount only in textLines → captured
5. Notification amount only in messages → captured
6. Privacy denied → no extraction, no raw row, diagnostic recorded
7. Restore mode active → no raw/review/expense writes
8. Duplicate notification → duplicate outcome, no parser/AI call
9. Parser failed but transaction signal exists → PendingReview
10. Service cancellation after receive → no silent loss
```

Definition of done:

```text
- Every pipeline exit returns NotificationPipelineOutcome.
- Every drop path writes diagnostic event.
- No notification text is extracted when capture privacy is denied.
- Repository/pipeline write path has restore/write barrier.
- textLines/messages are included in filter/parser/fingerprint.
- Auto-accepted notification expenses still go through TransactionLifecycleCoordinator.
- Post-commit side effects remain outside final DB transaction.
```

---

# Source files inspected

- `NotificationCaptureService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `NotificationFilter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt

- `NotificationProcessingPipeline.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `NotificationRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

- `ReviewQueueRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt