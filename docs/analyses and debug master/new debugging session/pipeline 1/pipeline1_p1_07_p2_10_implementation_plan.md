# Dedicated implementation plan — P1-P1-07 and P2-10

Reviewed against commit: `e781c226862234ed412914884e98d22165a41a95`

Target issues:

| ID | Status | Theme |
|---|---:|---|
| P1-P1-07 | TODO ONLY | Service shutdown/process death can silently lose accepted notifications |
| P2-10 | TODO ONLY | Currency fallback is still EUR/USD/GBP-heavy and defaults to EUR |

Recommended split:

1. **PR 1 — Durable notification intake schema**
2. **PR 2 — Intake coordinator + service spool-before-process**
3. **PR 3 — Intake worker + recovery/retry/stale-processing handling**
4. **PR 4 — Shared notification money/currency detector**
5. **PR 5 — Replace pipeline/filter fallback currency logic**
6. **PR 6 — Currency fallback diagnostics + regression suite**

---

# Context from current code

## P1-P1-07 evidence

Current `NotificationCaptureService` still processes notifications asynchronously from the listener path. The listener inserts an in-memory dedupe key, launches work, then removes the key in `finally`.

Current risks:

- `onDestroy()` sets shutdown state and cancels service-owned work.
- There is no durable intake table before expensive pipeline work.
- If the listener accepts a notification and the service/process dies before DB write, Android may not repost it.
- Manual refresh can recover only notifications still visible in the shade.
- `RawNotification.isProcessed` exists but is documented as never set to true.

So the real missing contract is:

```text
After privacy/package/restore gate allows a notification, a durable intake row must exist before parser/AI/pipeline work starts.
```

## P2-10 evidence

Current `NotificationFilter` supports more currency codes than before, but the parser-failed fallback logic in `NotificationProcessingPipeline` remains narrow:

- `CURRENCY_HINT_REGEX` only handles `€`, `$`, `£`, `EUR`, `USD`, `GBP`.
- `CURRENCY_SUFFIX_REGEX` only handles the same currencies.
- `AMOUNT_TOKEN_REGEX` only handles those currencies.
- fallback currency resolves as:
  - USD if text contains `USD` or `$`;
  - GBP if text contains `GBP` or `£`;
  - otherwise EUR.

This means non-EUR users can get wrong fallback reviews, especially for currencies like `PLN`, `RON`, `TRY`, `CAD`, `AUD`, `JPY`, `CHF`, `SEK`, `NOK`, `DKK`, `HUF`, `CZK`.

---

# Dependencies / recommended order

These two issues should not be implemented before the earlier foundation PRs if possible.

Recommended prerequisites:

1. **P1-P1-01 outcome propagation**
   - repository returns `NotificationPipelineOutcome`;
   - worker can map result to intake status.

2. **P1-P1-02 diagnostic emitter**
   - durable/fallback-safe diagnostics;
   - intake worker can emit reliable events.

3. **P1-P1-05 capture gate**
   - full privacy/package/restore gate before extraction;
   - intake coordinator can reuse one gate.

4. **P2-08 fingerprint duplicate check**
   - durable fingerprint duplicate pre-check;
   - intake should not enqueue duplicate work unnecessarily.

If these are not landed yet, the plan below still works, but you will create temporary glue that later has to be deleted.

---

# PR 1 — P1-P1-07: Durable notification intake schema

## Goal

Add a durable intake table that represents a notification accepted by the capture gate but not necessarily processed yet.

This table is separate from `raw_notifications`.

Reason:

- `raw_notifications` is currently both audit/source data and pipeline input.
- It has `isProcessed`, but that field is not a real status machine.
- Intake needs retry/lock/status/recovery fields.
- Intake should be able to exist before a raw notification row is inserted.

## New entity

Create:

```text
app/src/main/java/com/yourname/expensetracker/data/database/entity/NotificationIntakeEntity.kt
```

Suggested entity:

```kotlin
@Entity(
    tableName = "notification_intake",
    indices = [
        Index(value = ["dedupeFingerprint"], unique = true),
        Index(value = ["status", "nextAttemptAt"]),
        Index(value = ["status", "updatedAt"]),
        Index(value = ["correlationId"]),
        Index(value = ["packageName", "postTime"])
    ]
)
data class NotificationIntakeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Source metadata
    val packageName: String,
    val appName: String?,
    val notificationKeyHash: String?,
    val postTime: Long,
    val capturedAt: Long,
    val source: String, // LISTENER or REFRESH
    val correlationId: String,

    // Dedup
    val dedupeFingerprint: String,
    val contentHash: String?,

    // Processing payload
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?,

    // Privacy/storage policy captured at intake time
    val rawStorageMode: String,
    val payloadMode: String,
    val rawPayloadPurgedAt: Long? = null,

    // State machine
    val status: String,
    val attempts: Int = 0,
    val maxAttempts: Int = 5,
    val nextAttemptAt: Long? = null,
    val lockedAt: Long? = null,
    val lockedBy: String? = null,
    val lastAttemptAt: Long? = null,
    val terminalAt: Long? = null,

    // Result references
    val rawNotificationId: Long? = null,
    val expenseId: Long? = null,
    val pendingReviewId: Long? = null,

    // Failure/debug metadata — no raw text
    val lastFailureCode: String? = null,
    val lastFailureMessageHash: String? = null,
    val finalOutcome: String? = null,

    val createdAt: Long,
    val updatedAt: Long
)
```

## Intake status enum

Create:

```kotlin
enum class NotificationIntakeStatus {
    RECEIVED,
    ENQUEUED,
    PROCESSING,
    PROCESSED,
    DROPPED_DUPLICATE,
    DROPPED_POLICY,
    FILTER_REJECTED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    CANCELLED_STALE,
    PAYLOAD_UNAVAILABLE_PRIVACY
}
```

Use a string enum converter, matching the existing Room converter style.

## Privacy policy decision

This is the key design choice.

To truly fix P1-P1-07, the app needs enough durable payload to resume processing after process death.

But if `RawStorageMode.DO_NOT_STORE` means “never persist notification title/body anywhere,” then full process-death recovery is impossible for those notifications.

Recommended product policy:

```text
RawStorageMode controls long-term raw retention.
NotificationIntake may store encrypted/transient processing payload only until terminal processing, then it must purge raw fields.
```

If you do not want transient raw storage, then add this explicit caveat:

```text
Full shutdown/process-death recovery is guaranteed only for modes that allow resumable intake payload.
For DO_NOT_STORE, the app records metadata and a diagnostic PAYLOAD_UNAVAILABLE_PRIVACY if processing cannot complete before death.
```

Best practical option:

| RawStorageMode | Intake payload |
|---|---|
| `STORE_RAW` | store full payload until terminal, then optionally keep according to retention policy |
| `STORE_REDACTED` | store redacted payload for audit plus encrypted transient raw payload if user permits transient processing |
| `STORE_METADATA_ONLY` | store metadata plus encrypted transient raw payload if user permits transient processing |
| `DO_NOT_STORE` | either no resumable payload, or require explicit transient-processing opt-in |

Do **not** silently persist raw text in intake if the rest of the app promises not to store it.

## DAO

Create:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/NotificationIntakeDao.kt
```

Suggested methods:

```kotlin
@Dao
interface NotificationIntakeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: NotificationIntakeEntity): Long

    @Query("SELECT * FROM notification_intake WHERE id = :id")
    suspend fun getById(id: Long): NotificationIntakeEntity?

    @Query("""
        SELECT * FROM notification_intake
        WHERE status IN ('RECEIVED', 'ENQUEUED', 'FAILED_RETRYABLE')
          AND (:nowMs >= COALESCE(nextAttemptAt, 0))
        ORDER BY capturedAt ASC
        LIMIT :limit
    """)
    suspend fun getReadyForProcessing(nowMs: Long, limit: Int): List<NotificationIntakeEntity>

    @Query("""
        UPDATE notification_intake
        SET status = 'PROCESSING',
            lockedAt = :nowMs,
            lockedBy = :workerId,
            lastAttemptAt = :nowMs,
            attempts = attempts + 1,
            updatedAt = :nowMs
        WHERE id = :id
          AND status IN ('RECEIVED', 'ENQUEUED', 'FAILED_RETRYABLE')
    """)
    suspend fun claimForProcessing(id: Long, nowMs: Long, workerId: String): Int

    @Query("""
        UPDATE notification_intake
        SET status = :status,
            rawNotificationId = :rawId,
            expenseId = :expenseId,
            pendingReviewId = :reviewId,
            finalOutcome = :finalOutcome,
            terminalAt = :nowMs,
            updatedAt = :nowMs,
            lockedAt = NULL,
            lockedBy = NULL
        WHERE id = :id
    """)
    suspend fun markTerminal(
        id: Long,
        status: String,
        rawId: Long?,
        expenseId: Long?,
        reviewId: Long?,
        finalOutcome: String?,
        nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET status = 'FAILED_RETRYABLE',
            nextAttemptAt = :nextAttemptAt,
            lastFailureCode = :failureCode,
            lastFailureMessageHash = :failureHash,
            updatedAt = :nowMs,
            lockedAt = NULL,
            lockedBy = NULL
        WHERE id = :id
    """)
    suspend fun markRetryableFailure(
        id: Long,
        nextAttemptAt: Long,
        failureCode: String,
        failureHash: String?,
        nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET status = 'FAILED_FINAL',
            lastFailureCode = :failureCode,
            lastFailureMessageHash = :failureHash,
            terminalAt = :nowMs,
            updatedAt = :nowMs,
            lockedAt = NULL,
            lockedBy = NULL
        WHERE id = :id
    """)
    suspend fun markFinalFailure(
        id: Long,
        failureCode: String,
        failureHash: String?,
        nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET status = 'FAILED_RETRYABLE',
            lockedAt = NULL,
            lockedBy = NULL,
            nextAttemptAt = :nowMs,
            updatedAt = :nowMs
        WHERE status = 'PROCESSING'
          AND lockedAt < :staleBeforeMs
    """)
    suspend fun releaseStaleProcessing(staleBeforeMs: Long, nowMs: Long): Int

    @Query("""
        UPDATE notification_intake
        SET title = NULL,
            text = NULL,
            bigText = NULL,
            subText = NULL,
            extrasJson = NULL,
            rawPayloadPurgedAt = :nowMs,
            updatedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun purgeRawPayload(id: Long, nowMs: Long): Int
}
```

## Database update

Update:

```text
AppDatabase.kt
```

Tasks:

1. Add `NotificationIntakeEntity::class` to `@Database(entities = [...])`.
2. Add:

```kotlin
abstract fun notificationIntakeDao(): NotificationIntakeDao
```

3. Bump schema:

```kotlin
APP_DATABASE_SCHEMA_VERSION = 133
```

4. Add `MIGRATION_132_133`.

Use explicit SQL.

Example:

```sql
CREATE TABLE IF NOT EXISTS notification_intake (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    packageName TEXT NOT NULL,
    appName TEXT,
    notificationKeyHash TEXT,
    postTime INTEGER NOT NULL,
    capturedAt INTEGER NOT NULL,
    source TEXT NOT NULL,
    correlationId TEXT NOT NULL,
    dedupeFingerprint TEXT NOT NULL,
    contentHash TEXT,
    title TEXT,
    text TEXT,
    bigText TEXT,
    subText TEXT,
    extrasJson TEXT,
    rawStorageMode TEXT NOT NULL,
    payloadMode TEXT NOT NULL,
    rawPayloadPurgedAt INTEGER,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    maxAttempts INTEGER NOT NULL DEFAULT 5,
    nextAttemptAt INTEGER,
    lockedAt INTEGER,
    lockedBy TEXT,
    lastAttemptAt INTEGER,
    terminalAt INTEGER,
    rawNotificationId INTEGER,
    expenseId INTEGER,
    pendingReviewId INTEGER,
    lastFailureCode TEXT,
    lastFailureMessageHash TEXT,
    finalOutcome TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
```

Indexes:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS index_notification_intake_dedupeFingerprint
ON notification_intake(dedupeFingerprint);

CREATE INDEX IF NOT EXISTS index_notification_intake_status_nextAttemptAt
ON notification_intake(status, nextAttemptAt);

CREATE INDEX IF NOT EXISTS index_notification_intake_status_updatedAt
ON notification_intake(status, updatedAt);

CREATE INDEX IF NOT EXISTS index_notification_intake_correlationId
ON notification_intake(correlationId);

CREATE INDEX IF NOT EXISTS index_notification_intake_packageName_postTime
ON notification_intake(packageName, postTime);
```

## PR 1 tests

1. Migration creates table and indexes.
2. `insertOrIgnore` prevents duplicate `dedupeFingerprint`.
3. `claimForProcessing` only claims eligible statuses.
4. stale `PROCESSING` rows are released.
5. terminal mark stores result references.
6. raw payload purge clears text/body/extras.

## PR 1 acceptance criteria

- Intake table exists.
- DAO supports insert, claim, retry, terminal, stale recovery, purge.
- Migration test passes.
- No service behavior changes yet.

---

# PR 2 — P1-P1-07: Intake coordinator + service spool-before-process

## Goal

Change the listener flow from:

```text
listener -> async service coroutine -> parser/pipeline -> DB
```

to:

```text
listener -> capture gate -> extract/copy immutable payload -> durable intake insert -> WorkManager
```

The service should no longer call `repository.processAndSave(...)` directly for normal listener/refresh processing.

## New component

Create:

```text
domain/notification/NotificationIntakeCoordinator.kt
```

Responsibilities:

1. Accept a `StatusBarNotification` envelope.
2. Run capture gate.
3. Extract immutable text payload.
4. Compute dedupe fingerprint.
5. Build privacy-compliant intake row.
6. Insert intake row.
7. Enqueue worker.
8. Emit diagnostics.

Suggested API:

```kotlin
class NotificationIntakeCoordinator @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val workManager: WorkManager,
    private val captureGate: NotificationCaptureGate,
    private val extractor: NotificationExtractor,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val timeProvider: TimeProvider,
    private val diagnostics: NotificationDiagnosticEmitter
) {
    suspend fun capture(
        sbn: StatusBarNotification,
        source: NotificationCaptureSource
    ): NotificationIntakeCaptureResult
}
```

Result:

```kotlin
sealed interface NotificationIntakeCaptureResult {
    data class Enqueued(val intakeId: Long, val correlationId: String) : NotificationIntakeCaptureResult
    data class Duplicate(val correlationId: String, val dedupeFingerprint: String) : NotificationIntakeCaptureResult
    data class Dropped(val correlationId: String, val reason: String) : NotificationIntakeCaptureResult
    data class Error(val correlationId: String, val throwable: Throwable) : NotificationIntakeCaptureResult
}
```

## Service changes

Update:

```text
NotificationCaptureService.kt
```

Current service-owned coroutine scope is tied to `serviceJob`, which is cancelled on service destroy.

For intake durability, do **not** run the intake insert on `serviceJob`.

Inject application scope:

```kotlin
@Inject
@ApplicationScope
lateinit var applicationScope: CoroutineScope
```

Listener path:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return

    applicationScope.launch {
        notificationIntakeCoordinator.capture(
            sbn = sbn,
            source = NotificationCaptureSource.LISTENER
        )
    }
}
```

Why application scope:

- service destruction should not cancel an already-started intake insert;
- once inserted, WorkManager owns processing.

Caveat:

- process death before the coroutine inserts the row is still theoretically possible.
- This plan minimizes that window by making intake insert the first durable operation after gate/extraction.
- No Android API can guarantee work after the whole process is killed before your code persists state.

## Move filter out of service

To avoid losing notifications between service filter and durable insert, move filter to worker.

New service/coordinator order:

```text
1. RECEIVED diagnostic
2. capture gate
3. extraction
4. privacy-compliant intake payload build
5. insert intake row
6. enqueue worker
```

Worker order:

```text
1. claim intake
2. apply filter
3. if rejected -> FILTER_REJECTED terminal
4. else process pipeline
```

Why:

- if filtering happens before durable insert, a service death during filter/drop handling can still make behavior invisible;
- durable row should explain both processed and filter-rejected notifications.

## Intake payload builder

Create:

```text
NotificationIntakePayloadMapper.kt
```

It should reuse existing storage-mode logic but with explicit intake semantics.

Suggested modes:

```kotlin
enum class NotificationIntakePayloadMode {
    RAW,
    REDACTED,
    METADATA_ONLY,
    NONE,
    TRANSIENT_ENCRYPTED
}
```

If you do not implement encryption now, do not name it encrypted.

Rules:

| RawStorageMode | Intake fields |
|---|---|
| `STORE_RAW` | title/text/body/subText/extras allowed |
| `STORE_REDACTED` | redacted fields, or transient raw only if explicitly allowed |
| `STORE_METADATA_ONLY` | metadata only, or transient raw only if explicitly allowed |
| `DO_NOT_STORE` | no raw fields; worker may mark `PAYLOAD_UNAVAILABLE_PRIVACY` if it cannot process |

Recommended short-term implementation:

- For `STORE_RAW`: durable/resumable.
- For `STORE_REDACTED`, `STORE_METADATA_ONLY`, `DO_NOT_STORE`: do not persist raw text; mark as non-resumable if process dies before direct in-memory handoff.

Better long-term implementation:

- Add a separate user-visible setting:
  - “Allow encrypted temporary notification processing queue.”
- If enabled:
  - store encrypted raw payload;
  - purge immediately after terminal state;
  - enforce TTL cleanup.
- If disabled:
  - no full recovery guarantee for non-raw modes.

## Worker enqueue

Use WorkManager unique work:

```kotlin
val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
    .setInputData(workDataOf("intakeId" to intakeId))
    .addTag("notification-intake")
    .addTag("notification-intake-$intakeId")
    .build()

workManager.enqueueUniqueWork(
    "notification-intake-$intakeId",
    ExistingWorkPolicy.KEEP,
    request
)
```

Use `KEEP` because one intake ID should have one active worker.

## Duplicate handling

If `insertOrIgnore` returns `-1L`:

- emit `DUPLICATE` diagnostic;
- do not enqueue worker;
- return `Duplicate`.

This catches duplicate listener callbacks before pipeline work.

## PR 2 tests

1. service calls coordinator, not repository.
2. service uses application scope or non-service-cancelled scope.
3. gate denied -> no intake row.
4. gate allowed -> intake row inserted.
5. duplicate fingerprint -> no worker enqueued.
6. refresh and listener both use coordinator.
7. filter is not run in service anymore.
8. `onDestroy()` does not cancel intake coordinator work already launched in app scope.

## PR 2 acceptance criteria

- Normal listener path no longer directly calls `repository.processAndSave`.
- Refresh path no longer directly calls `processNotificationBypassDedupe`.
- Intake row is inserted before parser/AI/pipeline work.
- Duplicate intake fingerprint is terminal duplicate.
- WorkManager is enqueued exactly once per intake row.

---

# PR 3 — P1-P1-07: Intake worker + recovery/retry/stale-processing

## Goal

Process durable intake rows reliably after service shutdown/process death.

## New worker

Create:

```text
app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
```

Use Hilt worker if project already supports Hilt WorkManager:

```kotlin
@HiltWorker
class NotificationIntakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val intakeProcessor: NotificationIntakeProcessor
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val intakeId = inputData.getLong("intakeId", -1L)
        if (intakeId <= 0L) return Result.failure()
        return intakeProcessor.process(intakeId)
    }
}
```

If Hilt workers are not configured, add Hilt WorkManager setup in the application class.

## New processor

Create:

```text
domain/notification/NotificationIntakeProcessor.kt
```

Responsibilities:

1. Load intake row.
2. Check restore/write barrier.
3. Claim row.
4. Reconstruct `RawNotification`.
5. Run filter.
6. Call repository/pipeline.
7. Map outcome to intake terminal status.
8. Purge intake payload according to policy.
9. Emit diagnostics.

Suggested flow:

```kotlin
suspend fun process(intakeId: Long): Result {
    val intake = intakeDao.getById(intakeId) ?: return Result.failure()

    if (!writeBarrier.writesAllowed()) {
        intakeDao.markRetryableFailure(...)
        return Result.retry()
    }

    val claimed = intakeDao.claimForProcessing(
        id = intakeId,
        nowMs = now,
        workerId = workerId
    )

    if (claimed == 0) return Result.success()

    val current = intakeDao.getById(intakeId) ?: return Result.failure()

    if (!hasProcessablePayload(current)) {
        intakeDao.markTerminal(
            status = PAYLOAD_UNAVAILABLE_PRIVACY,
            ...
        )
        return Result.success()
    }

    if (!filter.shouldCapture(...)) {
        intakeDao.markTerminal(status = FILTER_REJECTED, ...)
        return Result.success()
    }

    val outcome = repository.processAndSave(
        processingNotification = current.toProcessingRawNotification(),
        storageNotification = current.toStorageRawNotification(),
        correlationId = current.correlationId
    )

    intakeDao.markTerminal(status = outcome.toIntakeStatus(), ...)
    purgePayloadIfNeeded(current)
    return Result.success()
}
```

## Outcome mapping

Requires P1-P1-01 outcome-return PR.

Map:

| `NotificationPipelineOutcome` | Intake status |
|---|---|
| `AutoAccepted` | `PROCESSED` with `expenseId` |
| `NeedsReview` | `PROCESSED` with `pendingReviewId` |
| `Duplicate` | `DROPPED_DUPLICATE` |
| `ParserFailed` | `FAILED_FINAL` or `FILTER_REJECTED` depending product semantics |
| `AutoRejected` | `DROPPED_POLICY` |
| `Dropped` | `DROPPED_POLICY` |
| `Error` retryable | `FAILED_RETRYABLE` + WorkManager retry |
| `Error` non-retryable | `FAILED_FINAL` |

Recommended retryable classification:

```text
retryable:
- SQLiteDatabaseLockedException
- IOException
- timeout
- write barrier / restore active
- transient WorkManager stop

non-retryable:
- validation/privacy policy denied
- payload unavailable by privacy
- parser failed with no signal
- duplicate
```

## Backoff

Worker-level backoff:

```kotlin
.setBackoffCriteria(
    BackoffPolicy.EXPONENTIAL,
    30.seconds.toJavaDuration()
)
```

DAO-level next attempt:

```text
attempt 1: now + 30s
attempt 2: now + 2m
attempt 3: now + 10m
attempt 4: now + 30m
attempt 5: final failure
```

Keep both WorkManager and DAO attempt info so debug UI can explain retries.

## Recovery scheduler

Create:

```text
NotificationIntakeRecoveryScheduler.kt
```

Run on:

- app start;
- notification listener connected;
- boot receiver;
- after restore completes;
- manual debug action.

Responsibilities:

1. `releaseStaleProcessing(staleBeforeMs = now - 10 minutes)`.
2. Query ready rows.
3. Enqueue unique work for each row.
4. Emit recovery diagnostics.

Pseudo:

```kotlin
suspend fun recoverPending(limit: Int = 100) {
    intakeDao.releaseStaleProcessing(now - STALE_PROCESSING_MS, now)
    intakeDao.getReadyForProcessing(now, limit).forEach { row ->
        enqueue(row.id)
    }
}
```

## Stop/cancellation handling

In worker:

- if `isStopped`, mark retryable if processing is not terminal yet;
- do not leave row stuck in `PROCESSING`;
- use `try/finally` to release lock or mark retryable.

In service:

- `onDestroy()` should stop accepting new listener work;
- it should not cancel WorkManager jobs;
- optional: call recovery scheduler on next connection.

## Replace `RawNotification.isProcessed`

Do not use `RawNotification.isProcessed` for durable intake.

Options:

1. Leave as deprecated field for now.
2. Add TODO to remove in later migration.
3. If cheap, set it true after terminal success, but do not rely on it.

Recommended:

```text
Keep `RawNotification.isProcessed` unchanged in this PR.
Use `notification_intake.status` as source of truth.
```

## PR 3 tests

### Processor tests

1. eligible row is claimed and processed.
2. filter rejected row becomes `FILTER_REJECTED`.
3. auto-accepted outcome stores `expenseId`.
4. needs-review outcome stores `pendingReviewId`.
5. duplicate outcome becomes `DROPPED_DUPLICATE`.
6. retryable exception becomes `FAILED_RETRYABLE`.
7. max attempts exceeded becomes `FAILED_FINAL`.
8. payload unavailable becomes `PAYLOAD_UNAVAILABLE_PRIVACY`.
9. raw payload is purged after terminal when policy requires it.
10. restore/write barrier active results in retry, not DB mutation.

### Recovery tests

1. stale `PROCESSING` row is released.
2. `RECEIVED` row is enqueued.
3. `FAILED_RETRYABLE` row with future `nextAttemptAt` is not enqueued.
4. terminal rows are ignored.
5. unique work prevents duplicate worker scheduling.

### End-to-end tests

1. listener captures row, service destroyed before pipeline, worker processes later.
2. process restart simulation: row already exists, recovery scheduler enqueues it.
3. duplicate notification creates one intake row and one terminal duplicate.
4. privacy metadata-only mode does not store raw payload unless transient processing is explicitly allowed.

## PR 3 acceptance criteria

- Accepted notifications are durably represented before pipeline work.
- Service destruction does not lose already-spooled notifications.
- Stale `PROCESSING` rows recover.
- Retryable failures retry with backoff.
- Terminal outcomes are queryable from intake table.
- Raw intake payload is purged according to policy.
- `P1-P1-07` can be marked fixed with a documented privacy-mode caveat if DO_NOT_STORE cannot be resumable.

---

# PR 4 — P2-10: Shared notification money/currency detector

## Goal

Replace local EUR/USD/GBP regex fallback with a reusable money detector used by:

- `NotificationFilter`;
- `NotificationProcessingPipeline.detectOversizedAmountCandidate`;
- `NotificationProcessingPipeline.detectTransactionSignalCandidate`;
- future parsers/review fallback.

## New files

Create:

```text
domain/notification/money/NotificationMoneySignalDetector.kt
domain/notification/money/MoneySignal.kt
domain/notification/money/CurrencyLexicon.kt
domain/notification/money/CurrencyResolution.kt
domain/notification/money/NotificationTextNormalizer.kt
```

## Supported currency set

Start with the set already partially present in `NotificationFilter`:

```text
EUR, USD, GBP, CHF, PLN, RON, TRY, CAD, AUD, JPY, SEK, NOK, DKK, HUF, CZK
```

Add symbols/aliases:

| Currency | Symbols / aliases |
|---|---|
| EUR | `€`, `EUR`, `EURO` |
| USD | `$`, `US$`, `USD` |
| GBP | `£`, `GBP` |
| CHF | `CHF`, `Fr`, `SFr` |
| PLN | `PLN`, `zł`, `zl` |
| RON | `RON`, `lei`, `leu` |
| TRY | `TRY`, `₺`, `TL` |
| CAD | `CAD`, `C$`, `CA$` |
| AUD | `AUD`, `A$`, `AU$` |
| JPY | `JPY`, `¥` |
| SEK | `SEK`, `kr` |
| NOK | `NOK`, `kr` |
| DKK | `DKK`, `kr` |
| HUF | `HUF`, `Ft` |
| CZK | `CZK`, `Kč`, `Kc` |

Important ambiguity:

- `$` can be USD/CAD/AUD.
- `kr` can be SEK/NOK/DKK.
- `Fr` can be CHF but may appear in other contexts.
- `TL` may appear as text abbreviation.

So the detector must model ambiguity instead of silently picking EUR.

## Data model

```kotlin
data class MoneySignal(
    val raw: String,
    val amount: Double,
    val currencyCode: String?,
    val currencyCandidates: Set<String>,
    val currencyResolution: CurrencyResolution,
    val amountRange: IntRange,
    val confidence: Float,
    val hasExplicitCurrency: Boolean,
    val isAmbiguousCurrency: Boolean
)
```

```kotlin
enum class CurrencyResolution {
    EXPLICIT_ISO_CODE,
    EXPLICIT_UNAMBIGUOUS_SYMBOL,
    AMBIGUOUS_SYMBOL,
    USER_HOME_CURRENCY,
    APP_DEFAULT_CURRENCY,
    UNKNOWN
}
```

## Detector API

```kotlin
interface NotificationMoneySignalDetector {
    fun detectSignals(text: String, options: Options = Options()): List<MoneySignal>

    fun bestTransactionAmount(
        text: String,
        homeCurrency: String?,
        allowedCurrencies: Set<String>
    ): MoneySignal?

    data class Options(
        val homeCurrency: String? = null,
        val allowedCurrencies: Set<String> = DEFAULT_SUPPORTED_CURRENCIES,
        val allowBareAmounts: Boolean = false,
        val maxAmount: Double = AppConfig.MAX_TRANSACTION_AMOUNT
    )
}
```

## Parsing rules

Support:

```text
€12.30
12.30 €
12,30 EUR
EUR 12,30
PLN 42.00
42,00 zł
RON 120,50
120,50 lei
₺75.90
75.90 TL
A$12.00
C$12.00
JPY 1200
1200 ¥
1,234.56 USD
1.234,56 EUR
1 234,56 PLN
```

Reject/penalize:

```text
card *1234
order 123456
OTP 123456
phone numbers
dates
times
percentages
exchange rates like EUR/USD 1.082 unless transaction keywords also exist
```

## Candidate scoring

Score each signal:

| Condition | Score |
|---|---:|
| explicit ISO code | +5 |
| unambiguous symbol | +4 |
| ambiguous symbol but home currency matches candidate | +2 |
| decimal separator present | +2 |
| near transaction keyword | +2 |
| near balance/account keyword | -3 |
| near card tail/order id | -4 |
| no currency and bare amount allowed | -2 |
| amount > max | special oversized path |
| amount <= 0.01 | reject |

## Home currency source

Inject a small provider:

```kotlin
interface UserCurrencyProvider {
    suspend fun getHomeCurrency(): String?
}
```

Implementation can read from the existing settings/config source.

If no user setting exists yet:

- use `AppConfig.DEFAULT_CURRENCY`;
- but mark `CurrencyResolution.APP_DEFAULT_CURRENCY`;
- never pretend it was explicit.

## PR 4 tests

1. each supported ISO code parses.
2. each supported symbol parses.
3. decimal comma parses.
4. thousands separators parse.
5. ambiguous `$` returns candidates, not forced USD unless context/home currency resolves it.
6. ambiguous `kr` returns SEK/NOK/DKK candidates unless ISO/home resolves.
7. `PLN`, `RON`, `TRY`, `CAD`, `AUD`, `JPY`, `CHF` all work.
8. card tail `*1234` is not parsed as amount.
9. OTP/security code is not parsed as amount.
10. exchange-rate text is not treated as expense amount without transaction signal.

## PR 4 acceptance criteria

- One shared detector exists.
- Detector supports all target currencies.
- Detector returns currency basis/confidence.
- No silent EUR fallback exists inside detector.
- Tests cover all supported currencies and ambiguity.

---

# PR 5 — P2-10: Replace pipeline/filter fallback currency logic

## Goal

Delete the duplicated narrow regexes from `NotificationProcessingPipeline` and use the shared detector.

## Files to modify

- `NotificationProcessingPipeline.kt`
- `NotificationFilter.kt`
- possibly `NotificationTransactionSignalDetector.kt` if created for P2-09
- tests

## Step 5.1 — Replace pipeline regex constants

Remove or stop using:

```kotlin
CURRENCY_HINT_REGEX
CURRENCY_SUFFIX_REGEX
AMOUNT_TOKEN_REGEX
```

Replace with injected detector:

```kotlin
private val moneySignalDetector: NotificationMoneySignalDetector
private val userCurrencyProvider: UserCurrencyProvider
```

Constructor:

```kotlin
class NotificationProcessingPipeline @Inject constructor(
    ...
    private val moneySignalDetector: NotificationMoneySignalDetector,
    private val userCurrencyProvider: UserCurrencyProvider,
    ...
)
```

## Step 5.2 — Update oversized candidate detection

Current behavior:

```text
requires EUR/USD/GBP hint
extracts amount
currency = USD/GBP/EUR fallback
```

New behavior:

```kotlin
internal suspend fun detectOversizedAmountCandidate(
    title: String?,
    text: String?,
    bigText: String?
): OversizedAmountCandidate? {
    val fullText = listOfNotNull(title, text, bigText).joinToString(" ").trim()
    if (fullText.isBlank()) return null
    if (!transactionSignalDetector.hasTransactionHint(fullText)) return null

    val homeCurrency = userCurrencyProvider.getHomeCurrency()
    val signals = moneySignalDetector.detectSignals(
        fullText,
        Options(
            homeCurrency = homeCurrency,
            allowBareAmounts = false,
            maxAmount = Double.MAX_VALUE
        )
    )

    val oversized = signals
        .filter { it.amount > AppConfig.MAX_TRANSACTION_AMOUNT }
        .maxByOrNull { it.confidence }
        ?: return null

    return OversizedAmountCandidate(
        amount = oversized.amount,
        currency = oversized.resolveCurrencyForReview(homeCurrency),
        merchantHint = extractMerchantHint(fullText),
        currencyResolution = oversized.currencyResolution,
        currencyConfidence = oversized.confidence
    )
}
```

If adding fields to `OversizedAmountCandidate` is too invasive, keep the external data class simple but include resolution metadata in diagnostics/review explanation.

## Step 5.3 — Update transaction signal candidate detection

Current behavior defaults to EUR unless USD/GBP found.

New behavior:

```kotlin
internal suspend fun detectTransactionSignalCandidate(
    title: String?,
    text: String?,
    bigText: String?
): TransactionSignalCandidate? {
    val fullText = listOfNotNull(title, text, bigText).joinToString(" ").trim()
    if (fullText.isBlank()) return null
    if (!transactionSignalDetector.hasTransactionHint(fullText)) return null

    val homeCurrency = userCurrencyProvider.getHomeCurrency()

    val signal = moneySignalDetector.bestTransactionAmount(
        text = fullText,
        homeCurrency = homeCurrency,
        allowedCurrencies = CurrencyLexicon.DEFAULT_SUPPORTED_CURRENCIES
    ) ?: return null

    return TransactionSignalCandidate(
        amount = signal.amount,
        currency = signal.resolveCurrencyForReview(homeCurrency),
        merchantHint = extractMerchantHint(fullText),
        currencyResolution = signal.currencyResolution,
        currencyConfidence = signal.confidence
    )
}
```

## Step 5.4 — Currency resolution policy for reviews

`PendingReview.suggestedCurrency` is probably non-null, so the fallback path needs a value.

Policy:

| Detector result | `suggestedCurrency` | Review explanation |
|---|---|---|
| explicit ISO/symbol unambiguous | explicit currency | normal |
| ambiguous symbol resolved by home currency | home currency | “currency inferred from home currency” |
| ambiguous and not resolvable | home/default currency | low-confidence explanation |
| no currency but bare amount allowed | home currency | low-confidence explanation |
| no currency and bare amount not allowed | no candidate |

Do not default to EUR unless the user/app home currency is EUR.

## Step 5.5 — Update duplicate checks

Current duplicate checks use candidate currency.

After this PR:

- duplicate check must use the resolved review currency;
- if currency is ambiguous/low-confidence, consider a relaxed duplicate check across likely candidate currencies.

Recommended:

```text
If currencyResolution is AMBIGUOUS_SYMBOL or APP_DEFAULT_CURRENCY:
    check duplicate by amount/merchant/date across candidate currencies and home currency.
Else:
    use exact currency-aware duplicate check.
```

Do not create duplicate false negatives just because the fallback picked a default.

## Step 5.6 — Update `NotificationFilter`

`NotificationFilter` currently has a broader currency regex than the pipeline.

Replace its amount/currency detection with the same money detector.

Because `NotificationFilter` is currently an `object`, you have two options:

### Better

Convert it to injectable class:

```kotlin
@Singleton
class NotificationFilter @Inject constructor(
    private val moneySignalDetector: NotificationMoneySignalDetector,
    private val transactionSignalDetector: NotificationTransactionSignalDetector
)
```

### Smaller PR

Keep object API and delegate to a singleton-like pure detector:

```kotlin
object NotificationFilter {
    fun shouldCapture(...): Boolean {
        return DefaultNotificationMoneySignalDetector.detectSignals(...).isNotEmpty()
    }
}
```

Recommended: injectable class, but it touches more call sites.

## Step 5.7 — Update parser-failed review text

When creating `PendingReview` from fallback signal, include explanation:

```text
"Transaction signal detected but parser failed — amount/currency inferred from notification text. Currency basis: EXPLICIT_ISO_CODE."
```

For low-confidence currency:

```text
"Currency was inferred from home currency because notification used ambiguous symbol."
```

Do not store raw notification text in explanation.

## PR 5 tests

1. parser failed + `PLN 42.00` creates review with `PLN`.
2. parser failed + `42,00 zł` creates review with `PLN`.
3. parser failed + `RON 120,50` creates review with `RON`.
4. parser failed + `120,50 lei` creates review with `RON`.
5. parser failed + `₺75.90` creates review with `TRY`.
6. parser failed + `A$12.00` creates review with `AUD`.
7. parser failed + `C$12.00` creates review with `CAD`.
8. parser failed + `JPY 1200` creates review with `JPY`.
9. parser failed + `CHF 10.50` creates review with `CHF`.
10. parser failed + `$12.00`, home currency CAD -> review `CAD`, low/medium confidence.
11. parser failed + `$12.00`, no home currency -> low-confidence default, not hardcoded EUR unless default is EUR.
12. no amount/currency -> no fallback review.
13. card tail/order id is not selected as amount.
14. oversized fallback works for non-EUR currencies.

## PR 5 acceptance criteria

- Pipeline no longer has EUR/USD/GBP-only regex fallback.
- Fallback does not silently default to EUR.
- Filter and pipeline agree on amount/currency detection.
- Non-EUR fallback reviews use correct currency.
- Ambiguous symbols are represented as ambiguous, not guessed silently.

---

# PR 6 — P2-10: Currency diagnostics + regression suite

## Goal

Make currency fallback observable and safe.

## Diagnostics

Add diagnostic metadata for fallback candidates:

```text
moneySignalDetected=true
moneySignalCurrency=PLN
currencyResolution=EXPLICIT_ISO_CODE
currencyConfidence=0.95
ambiguousCurrency=false
amountSignalCount=1
```

For ambiguous:

```text
currencyCandidates=["USD","CAD","AUD"]
currencyResolution=AMBIGUOUS_SYMBOL
resolvedCurrency=CAD
resolutionBasis=USER_HOME_CURRENCY
currencyConfidence=0.65
```

Do not include raw notification text.

## Debug/review visibility

For pending reviews created from parser-failed fallback, expose:

- suggested amount;
- suggested currency;
- currency basis;
- low-confidence warning.

If no UI field exists, store in safe metadata/explanation.

## Golden test matrix

Add table-driven tests:

| Text | Home currency | Expected |
|---|---:|---|
| `Paid PLN 42.00 at Żabka` | EUR | PLN |
| `Zapłacono 42,00 zł` | EUR | PLN |
| `Plata 120,50 lei` | EUR | RON |
| `Paid ₺75.90` | EUR | TRY |
| `Paid A$12.00` | EUR | AUD |
| `Paid C$12.00` | EUR | CAD |
| `Paid $12.00` | CAD | CAD, inferred |
| `Paid $12.00` | AUD | AUD, inferred |
| `Paid $12.00` | null | default/ambiguous warning |
| `Paid 1200 ¥` | EUR | JPY |
| `Paid CHF 10.50` | EUR | CHF |
| `Paid 99 kr` | SEK | SEK, inferred |
| `Balance PLN 1200` | EUR | reject/filter or no expense fallback |
| `EUR/USD 1.08` | EUR | reject |
| `Card *1234` | EUR | reject |

## Regression against old behavior

Add explicit test:

```text
Non-EUR fallback does not return EUR unless EUR was explicit or user home currency.
```

## PR 6 acceptance criteria

- Currency fallback emits safe diagnostics.
- Regression tests cover all supported currencies.
- Ambiguous symbols are visible to debug tooling.
- Tracker can mark P2-10 fixed.

---

# Combined implementation sequence

## Commit group A — P1-P1-07

1. Add `NotificationIntakeEntity`, DAO, migration.
2. Add intake coordinator and payload mapper.
3. Change service listener/refresh to coordinator.
4. Add WorkManager enqueue.
5. Add worker + processor.
6. Add recovery scheduler.
7. Add stale-processing release.
8. Add payload purge.
9. Add diagnostics.
10. Add tests.

## Commit group B — P2-10

1. Add `CurrencyLexicon`.
2. Add `MoneySignal` and detector.
3. Add home-currency provider.
4. Replace pipeline fallback detectors.
5. Replace filter amount regex usage.
6. Add review explanations/diagnostics.
7. Add currency test matrix.
8. Remove stale EUR/USD/GBP fallback constants.

---

# Validation commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If emulator available:

```bash
./gradlew connectedDebugAndroidTest
```

Search checks:

```bash
grep -R "processAndSave(" app/src/main/java/com/yourname/expensetracker/service
grep -R "processNotificationBypassDedupe" app/src/main/java
grep -R "CURRENCY_HINT_REGEX" app/src/main/java
grep -R "AMOUNT_TOKEN_REGEX" app/src/main/java
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "isProcessed" app/src/main/java
grep -R "notification_intake" app/src/main/java
```

Expected after all PRs:

- service does not call `processAndSave()` directly for listener/refresh;
- no `processNotificationBypassDedupe`;
- no pipeline-local EUR/USD/GBP-only currency regex fallback;
- no hardcoded fallback `else -> "EUR"` unless explicitly tied to user/app home currency;
- intake table and worker exist;
- stale intake recovery exists.

---

# Tracker update after implementation

After PRs 1–3:

| ID | New status |
|---|---:|
| P1-P1-07 | Fixed, or Fixed with documented privacy-mode caveat |

Use “Fixed with caveat” only if `DO_NOT_STORE` cannot persist a resumable transient payload.

After PRs 4–6:

| ID | New status |
|---|---:|
| P2-10 | Fixed |

---

# Out of scope for these PRs

Do not mix in:

- AI parser provenance contract;
- full MoneyAggregate exchange-rate basis;
- dashboard currency normalization;
- location/foreground-service privacy fix;
- full service decomposition beyond what intake requires;
- durable receipt/email pipeline work.

P2-10 fixes notification fallback currency detection only. It does not solve all app-wide currency conversion/aggregation issues.

---

# Sources checked

Code/docs:

- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `NotificationFilter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt
- `RawNotification.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt
- `RawNotificationDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt
- `AppDatabase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

Android primary docs:

- WorkManager: https://developer.android.com/reference/androidx/work/WorkManager
- HiltWorker: https://developer.android.com/reference/androidx/hilt/work/HiltWorker
- NotificationListenerService: https://developer.android.com/reference/android/service/notification/NotificationListenerService