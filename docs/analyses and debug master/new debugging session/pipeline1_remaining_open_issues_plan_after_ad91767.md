# Pipeline 1 remaining open issues — implementation plan after `ad91767`

Baseline: `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

## Current blocker summary

Pipeline 1 is **not ready** mainly because:

1. **P2-11 privacy regression:** worker passes raw intake payload as both processing and storage notification.
2. **Transient payload is unencrypted:** `payloadMode = TRANSIENT` still stores raw title/text/body in normal Room columns.
3. **Terminal intake rows can be overwritten after cleanup failure.**
4. **P1-P1-07 durability is still partial:** intake insert happens only after service-side gate/extract/filter work.
5. **P2-12, P2-10, P2-09 remain functional TODOs:** parser provenance, currency detector, finance filter.
6. **Public/batch repository paths still bypass sanitization.**
7. Several cleanup issues remain: source-link typed result, refresh source, dedupe hashes, location dead dependency, service decomposition.

Recommended order:

1. **PR A — Emergency privacy hotfix: worker storage sanitizer**
2. **PR B — Transient payload policy/encryption**
3. **PR C — Intake worker terminal/retry hardening**
4. **PR D — Move durable intake earlier / app-scope handoff**
5. **PR E — Repository public/batch privacy sanitizer**
6. **PR F — Parser provenance integration**
7. **PR G — Shared money/currency detector**
8. **PR H — Finance filter v2**
9. **PR I — Source-link typed result**
10. **PR J — `RawNotification.isProcessed` global policy**
11. **PR K — Cleanup PR**
12. **PR L — Service decomposition**

---

# PR A — Emergency privacy hotfix: worker storage sanitizer

## Fixes

- P2-11 regression
- P1-P1-07 privacy correctness
- raw text leaking into `raw_notifications` from worker path

## Current bug

`NotificationIntakeWorker` reconstructs:

```kotlin
val processingNotification = RawNotification(... raw intake fields ...)
val storageNotification = processingNotification
repository.processAndSave(processingNotification, storageNotification, ...)
```

So for `STORE_REDACTED`, `STORE_METADATA_ONLY`, and `DO_NOT_STORE`, raw title/text/body can be persisted.

## Goal

Worker must always build **two different objects**:

```text
processingNotification = raw/transient payload used only for parsing
storageNotification = sanitized according to RawStorageMode
```

## Implementation

Create:

```kotlin
class NotificationStoragePayloadSanitizer @Inject constructor(
    private val rawContentSanitizer: RawContentSanitizer
) {
    fun sanitizeForStorage(
        processing: RawNotification,
        rawStorageMode: RawStorageMode
    ): RawNotification
}
```

Rules:

```kotlin
when (rawStorageMode) {
    STORE_RAW -> processing

    STORE_REDACTED -> processing.copy(
        title = processing.title?.let { rawContentSanitizer.redact(it) } ?: "[REDACTED]",
        text = processing.text?.let { rawContentSanitizer.redact(it) } ?: "[REDACTED]",
        bigText = processing.bigText?.let { rawContentSanitizer.redact(it) } ?: "[REDACTED]",
        subText = processing.subText?.let { rawContentSanitizer.redact(it) },
        extrasJson = """{"redacted":true}"""
    )

    STORE_METADATA_ONLY,
    DO_NOT_STORE -> processing.copy(
        title = null,
        text = null,
        bigText = null,
        subText = null,
        extrasJson = null
    )
}
```

In `NotificationIntakeWorker`:

```kotlin
val mode = RawStorageMode.valueOf(current.rawStorageMode)

val processingNotification = current.toProcessingRawNotification()

val storageNotification = storagePayloadSanitizer.sanitizeForStorage(
    processing = processingNotification,
    rawStorageMode = mode
)

val outcome = repository.processAndSave(
    processingNotification = processingNotification,
    storageNotification = storageNotification,
    correlationId = current.correlationId
)
```

Fail closed on unknown mode:

```kotlin
catch (e: IllegalArgumentException) {
    markFinalFailure("UNKNOWN_RAW_STORAGE_MODE")
}
```

## Tests

For each `RawStorageMode`:

1. Worker processes notification successfully.
2. `raw_notifications` row respects mode:
   - `STORE_RAW`: raw body allowed.
   - `STORE_REDACTED`: no raw string; redacted marker/string.
   - `STORE_METADATA_ONLY`: title/text/body/extras null.
   - `DO_NOT_STORE`: title/text/body/extras null.
3. Pending review text respects mode.
4. Transaction events/audit metadata contain no raw notification body.
5. Diagnostics contain no raw body/title/extras.

## Acceptance

- Worker never passes raw notification as storage payload under non-raw modes.
- P2-11 privacy regression fixed immediately.

---

# PR B — Transient payload policy/encryption

## Fixes

- unencrypted transient raw payload in `notification_intake`
- privacy ambiguity around `payloadMode = TRANSIENT`
- `DO_NOT_STORE` semantic mismatch

## Current problem

Coordinator stores raw title/text/body in normal DB columns for non-raw modes:

```kotlin
title = title
text = text
bigText = combinedBody
payloadMode = "TRANSIENT"
```

This is not actually transient until the worker purges it, and it is not encrypted.

## Goal

Make processing payload policy explicit.

## Add policy model

```kotlin
enum class NotificationTransientPayloadPolicy {
    ENCRYPTED_UNTIL_PROCESSED,
    DISABLED
}
```

Add to privacy settings or notification privacy settings:

```kotlin
val transientNotificationPayloadPolicy: NotificationTransientPayloadPolicy
```

Recommended defaults:

| RawStorageMode | Default transient policy |
|---|---|
| STORE_RAW | not needed |
| STORE_REDACTED | ENCRYPTED_UNTIL_PROCESSED |
| STORE_METADATA_ONLY | ENCRYPTED_UNTIL_PROCESSED |
| DO_NOT_STORE | DISABLED unless explicit opt-in |

## Schema migration

Add to `notification_intake`:

```kotlin
transientPayloadCiphertext: String?
transientPayloadNonce: String?
transientPayloadVersion: Int?
transientPayloadPurgedAt: Long?
```

Optionally keep visible sanitized fields:

```kotlin
title
text
bigText
subText
extrasJson
```

but non-raw modes must not store raw values in those columns.

## New classes

```kotlin
data class NotificationTransientPayload(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?
)

interface NotificationTransientPayloadCrypto {
    fun encrypt(payload: NotificationTransientPayload): EncryptedPayload
    fun decrypt(ciphertext: String, nonce: String, version: Int): NotificationTransientPayload
}
```

Use existing app crypto infrastructure if present; otherwise use AES-GCM with Android Keystore.

## Coordinator behavior

```text
STORE_RAW:
    store visible raw payload; no encrypted transient needed.

STORE_REDACTED:
    store redacted visible fields + encrypted transient raw payload.

STORE_METADATA_ONLY:
    store null visible fields + encrypted transient raw payload.

DO_NOT_STORE + transient opt-in:
    store null visible fields + encrypted transient raw payload.

DO_NOT_STORE + transient disabled:
    do not create durable intake row with raw payload.
    Either:
      A. process synchronously in-memory with sanitized storage, with documented no process-death guarantee; or
      B. mark dropped/unsupported with explicit diagnostic.
```

Preferred for `DO_NOT_STORE`:

```text
No durable raw/transient storage unless user explicitly enables encrypted temporary queue.
```

## Worker behavior

Reconstruct processing payload:

```kotlin
val payload = when {
    current.rawStorageMode == STORE_RAW.name -> payloadFromVisibleFields(current)

    current.transientPayloadCiphertext != null -> crypto.decrypt(...)

    else -> return markTerminal(PAYLOAD_UNAVAILABLE_PRIVACY)
}
```

After terminal outcome:

```kotlin
purgeVisibleRawPayloadIfNeeded()
purgeEncryptedTransientPayload()
```

## Tests

1. Non-raw modes do not store raw title/text/body in visible columns.
2. Encrypted payload decrypts and processes.
3. Encrypted payload is purged after terminal outcome.
4. `DO_NOT_STORE + disabled` does not persist raw/transient payload.
5. `DO_NOT_STORE + opt-in` processes and purges encrypted payload.
6. App crash before worker leaves ciphertext, not plaintext.

## Acceptance

- “TRANSIENT” no longer means plaintext raw payload in Room.
- Privacy policy is explicit and test-covered.

---

# PR C — Intake worker terminal/retry hardening

## Fixes

- terminal row overwritten after cleanup failure
- retry/final misclassification
- cleanup side effects causing duplicate processing

## Current bug

Worker does:

```text
repository.processAndSave()
markTerminal()
markProcessed()
purgeRawPayload()
```

inside one `try`.

If `markTerminal()` succeeds but `markProcessed()` or purge fails, catch block can mark retry/final and reprocess a terminal item.

## Implementation

Use terminal guard:

```kotlin
var terminalMarked = false

try {
    val outcome = repository.processAndSave(...)

    intakeDao.markTerminal(...)
    terminalMarked = true

    markRawProcessedBestEffort(rawId)
    purgePayloadBestEffort(current)

    return Result.success()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (terminalMarked) {
        diagnostics.emit(nonTerminalCleanupFailure(e))
        return Result.success()
    }

    return handlePreTerminalFailure(e, current)
}
```

Best-effort helpers:

```kotlin
private suspend fun markRawProcessedBestEffort(rawId: Long?) {
    if (rawId == null) return
    runCatching { rawDao.markProcessed(rawId) }
        .onFailure { emitDiagnostic("RAW_MARK_PROCESSED_FAILED") }
}

private suspend fun purgePayloadBestEffort(row: NotificationIntakeEntity) {
    runCatching { intakeDao.purgeRawPayload(row.id, timeProvider.now()) }
        .onFailure { emitDiagnostic("INTAKE_PAYLOAD_PURGE_FAILED") }
}
```

Strengthen DAO updates:

```sql
UPDATE notification_intake
SET status = 'FAILED_RETRYABLE' ...
WHERE id = :id
AND status NOT IN (
  'PROCESSED',
  'DROPPED_DUPLICATE',
  'DROPPED_POLICY',
  'FILTER_REJECTED',
  'FAILED_FINAL'
)
```

Same for `markFinalFailure`.

## Retry classification

Create:

```kotlin
class NotificationIntakeFailureClassifier {
    fun classify(t: Throwable): FailureClass
}
```

```kotlin
sealed interface FailureClass {
    data object Retryable : FailureClass
    data object Final : FailureClass
}
```

Retryable:
- SQLite locked/busy
- IOException
- timeout
- WorkManager stop
- write barrier active

Final:
- malformed intake row
- unknown raw storage mode
- payload unavailable due to privacy
- parser final failure

## Tests

1. `markTerminal()` succeeds, `purgeRawPayload()` throws -> worker returns success; status remains terminal.
2. `markTerminal()` succeeds, `rawDao.markProcessed()` throws -> status remains terminal.
3. retryable pre-terminal failure -> `FAILED_RETRYABLE`.
4. max attempts -> `FAILED_FINAL`.
5. cancellation -> rethrown.
6. failure updates do not overwrite terminal statuses.

## Acceptance

- No terminal intake row can be regressed to retry/failure by cleanup failure.

---

# PR D — Move durable intake earlier / app-scope handoff

## Fixes

- P1-P1-07 remaining loss window
- service-scope cancellation before intake insert
- filter-before-intake durability gap

## Current issue

`withContext(NonCancellable)` protects only `intakeCoordinator.capture(...)`, but many steps happen first inside `serviceScope`.

## Target flow

```text
onNotificationPosted
  -> build safe envelope only
  -> applicationScope.launch
       -> emit RECEIVED
       -> gate
       -> extract text
       -> compute dedupe
       -> insert intake row
       -> enqueue worker
```

Move filter to worker only:

```text
worker:
  -> decrypt/load payload
  -> filter
  -> FILTER_REJECTED terminal if needed
  -> pipeline
```

## Implementation

1. Inject app scope:

```kotlin
@Inject
@ApplicationScope
lateinit var applicationScope: CoroutineScope
```

2. Replace listener launch:

```kotlin
workTracker.launch(serviceScope) { ... }
```

with:

```kotlin
applicationScope.launch {
    captureCoordinator.handle(sbn, CaptureSource.LISTENER)
}
```

3. Create `NotificationCaptureCoordinator` if not ready yet, or move logic into `NotificationIntakeCoordinator`.

4. Remove service-side filter before intake. Worker already filters.

5. Keep service-side in-memory dedupe only as a lightweight callback suppressor. Durable duplicate still based on intake fingerprint.

## Tests

1. service destroyed after listener callback still inserts intake row.
2. filter-rejected notification creates intake row and terminal `FILTER_REJECTED`.
3. WorkManager enqueue failure leaves `RECEIVED` row for recovery.
4. recovery scheduler enqueues stuck `RECEIVED` rows.

## Acceptance

- P1-P1-07 can be marked fixed, except explicit privacy caveat for `DO_NOT_STORE + transient disabled`.

---

# PR E — Repository public/batch privacy sanitizer

## Fixes

- P2-11 public/batch privacy bypass
- `processAndSave(notification)` KDoc-only warning

## Current issue

Repository still does:

```kotlin
processAndSave(notification) = processAndSave(notification, notification)
```

Docs are not enough.

## Implementation options

### Preferred

Make unsafe overload internal/test-only:

```kotlin
internal suspend fun processAndSaveUnsafeRawStorage(...)
```

Public API becomes:

```kotlin
suspend fun processAndSave(payload: NotificationPersistencePayload)
```

### Compatible

Keep overload but sanitize internally:

```kotlin
suspend fun processAndSave(notification: RawNotification): NotificationPipelineOutcome {
    val payload = persistenceMapper.fromRaw(notification)
    return processAndSave(payload.processing, payload.storage)
}
```

Create:

```kotlin
data class NotificationPersistencePayload(
    val processingNotification: RawNotification,
    val storageNotification: RawNotification
)
```

`NotificationPersistenceMapper` must apply the same storage sanitizer as worker.

Batch:

```kotlin
processAndSaveAll(notifications)
```

must map each notification separately before processing.

## Tests

For direct and batch paths under all modes:

- no raw text in persisted raw rows when non-raw;
- no raw extras;
- parser still gets processing payload;
- reviews/events/diagnostics safe.

## Acceptance

- No repository entrypoint can bypass storage privacy policy.

---

# PR F — Parser provenance integration

## Fixes

- P2-12

## Current issue

`ParseProvenance` exists, but parser still returns `ParsedTransaction?`.

## Add

```kotlin
sealed interface ParseOutcome {
    val parsed: ParsedTransaction?
    val provenance: ParseProvenance

    data class Parsed(
        override val parsed: ParsedTransaction,
        override val provenance: ParseProvenance
    ) : ParseOutcome

    data class NoParse(
        override val provenance: ParseProvenance
    ) : ParseOutcome {
        override val parsed: ParsedTransaction? = null
    }
}
```

In `AppParserRegistry`:

```kotlin
suspend fun parseWithProvenance(...): ParseOutcome
```

Keep deprecated wrapper:

```kotlin
@Deprecated("Use parseWithProvenance")
suspend fun parseWithAiFallback(...): ParsedTransaction? =
    parseWithProvenance(...).parsed
```

Pipeline:

```kotlin
val parseOutcome = parserRegistry.parseWithProvenance(...)
val parsed = parseOutcome.parsed
val provenance = parseOutcome.provenance
```

Emit diagnostics:

```text
parserSource
winningParserId
aiAttempted
aiStatus
aiProvider
aiModel
confidence
failureReason
```

No raw prompt/response.

## Tests

1. specific deterministic success.
2. generic deterministic success.
3. deterministic fail + AI success.
4. AI skipped.
5. AI exception.
6. no duplicate deterministic parse.
7. pipeline diagnostics include provenance.

## Acceptance

- P2-12 fixed.

---

# PR G — Shared money/currency detector

## Fixes

- P2-10

## Current issue

Pipeline still uses:

```kotlin
"$" -> "USD"
else -> "EUR"
```

## New components

```kotlin
NotificationMoneySignalDetector
CurrencyLexicon
MoneySignal
CurrencyResolution
UserCurrencyProvider
```

```kotlin
data class MoneySignal(
    val raw: String,
    val amount: Double,
    val currencyCode: String?,
    val currencyCandidates: Set<String>,
    val resolution: CurrencyResolution,
    val confidence: Float,
    val ambiguous: Boolean
)
```

```kotlin
enum class CurrencyResolution {
    EXPLICIT_ISO_CODE,
    EXPLICIT_UNAMBIGUOUS_SYMBOL,
    AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME,
    AMBIGUOUS_UNRESOLVED,
    USER_HOME_CURRENCY,
    APP_DEFAULT_CURRENCY,
    UNKNOWN
}
```

Supported:

```text
EUR, USD, GBP, CHF, PLN, RON, TRY, CAD, AUD, JPY, SEK, NOK, DKK, HUF, CZK
```

Replace:
- fallback regex currency resolution;
- filter amount detection;
- `else -> "EUR"`.

## Tests

- `PLN 42.00` -> PLN
- `42,00 zł` -> PLN
- `120,50 lei` -> RON
- `₺75.90` -> TRY
- `A$12.00` -> AUD
- `C$12.00` -> CAD
- `$12.00` + home CAD -> CAD inferred
- `$12.00` + no home -> ambiguous
- `99 kr` + home SEK -> SEK inferred
- `EUR/USD 1.08` -> reject
- `Card *1234` -> reject

## Acceptance

- No silent EUR/USD default remains.
- P2-10 fixed.

---

# PR H — Finance filter v2

## Fixes

- P2-09

## Current issue

Finance apps still pass with any currency-looking amount.

## Add model

```kotlin
data class NotificationFilterDecision(
    val capture: Boolean,
    val reason: NotificationFilterReason,
    val confidence: Float,
    val direction: TransactionDirection,
    val moneySignals: List<MoneySignal>
)
```

Reasons:

```text
ALLOW_STRONG_EXPENSE
ALLOW_OUTGOING_TRANSFER
BALANCE_ONLY
ACCOUNT_INFO_ONLY
CURRENCY_ONLY
SECURITY_OR_AUTH
PROMOTION
INCOMING_ONLY
PAYMENT_FAILED_OR_DECLINED
NO_TRANSACTION_SIGNAL
```

## Logic

For finance packages:

```text
hard deny first:
  security/auth, promo, balance/account, FX/rate, failed/declined

require:
  money signal + debit/expense/outgoing transfer signal

reject:
  incoming transfer, salary, deposit, refund, balance-only
```

## Tests

Allow:
- Revolut card purchase.
- Google Wallet payment.
- Greek bank charge.
- SMS/Gmail purchase.
- outgoing transfer.

Reject:
- available balance with amount.
- account statement.
- FX rate.
- OTP/security.
- promo.
- incoming transfer.
- salary/deposit.
- card declined.
- unknown package with currency only.

## Acceptance

- Balance/currency-only finance notifications no longer pass.
- P2-09 fixed.

---

# PR I — Source-link typed result

## Fixes

- P1-NEW-18

## Add

```kotlin
sealed interface SourceLinkWriteResult {
    data class Created(val sourceLinkId: Long) : SourceLinkWriteResult
    data class AlreadyExists(val sourceLinkId: Long?) : SourceLinkWriteResult
    data class Failed(
        val errorClass: String,
        val errorMessageHash: String?,
        val retryable: Boolean
    ) : SourceLinkWriteResult
}
```

Create:

```kotlin
NotificationSourceLinkWriter
```

Pipeline should handle result explicitly.

Failures:
- emit `SOURCE_LINK_FAILED`;
- do not roll back expense/review by default;
- optionally enqueue repair.

## Tests

1. created.
2. already exists.
3. retryable failure.
4. non-retryable failure.
5. auto-accept still succeeds.
6. diagnostic safe.

## Acceptance

- Source-link failures are typed and observable.

---

# PR J — `RawNotification.isProcessed` global policy

## Fixes

- P1-NEW-14 remaining partial

## Problem

Worker marks processed, but direct/batch paths do not.

## Implement

Best option:

```text
Repository marks raw processed after terminal pipeline outcome for all paths.
```

Add outcome helper:

```kotlin
fun NotificationPipelineOutcome.rawIdOrNull(): Long?
fun NotificationPipelineOutcome.isTerminalProcessed(): Boolean
```

Repository wrapper:

```kotlin
val outcome = pipeline.process(...)
markProcessedIfNeeded(outcome)
return outcome
```

Worker can either:
- stop marking processed directly and let repository do it; or
- keep best-effort mark, idempotent.

## Tests

1. intake auto-accept -> processed.
2. direct auto-accept -> processed.
3. batch review -> processed.
4. retryable error -> not processed.
5. duplicate -> does not mutate unrelated raw row.

## Acceptance

- `isProcessed` semantics are global and tested.

---

# PR K — Cleanup PR

## Items

### 1. Refresh source

Replace:

```kotlin
refreshActiveNotifications() -> onNotificationPosted(sbn)
```

with:

```kotlin
enqueueNotificationCapture(sbn, CaptureSource.REFRESH)
```

### 2. Package-policy unavailable

Do not emit `BLOCKED_PACKAGE` for DAO/cache failure.

Add:

```kotlin
DiagnosticReasonCode.PACKAGE_POLICY_UNAVAILABLE
```

### 3. Dedupe hashes

Replace:

```kotlin
packageName.hashCode()
notificationKey.hashCode()
contentFingerprint.take(16)
```

with SHA-256/HMAC and at least 128-bit digest.

Review whether `postTime` belongs in in-flight key.

### 4. Remove stale hash helper

Remove `computeNotificationContentHash()` if it returns JVM `hashCode()`.

### 5. Messaging fallback cleanup

Do not use `item.toString()` for unknown parcelables.

### 6. Location dependency cleanup

Remove unused `ForegroundLocationProvider` from `NotificationProcessingPipeline`.

## Acceptance

- No stale weak hash helpers.
- Refresh diagnostics distinguish source.
- No dead location dependency.

---

# PR L — Service decomposition

## Fixes

- P3-13

## Target

```text
NotificationCaptureService = Android lifecycle adapter only.
```

Extract:

```kotlin
NotificationCaptureCoordinator
NotificationCaptureDeduper
NotificationEnvelopeFactory
NotificationRefreshCoordinator
NotificationForegroundController
NotificationRestartScheduler
NotificationPersistenceMapper
```

Final flow:

```text
NotificationCaptureService
  -> NotificationCaptureCoordinator
      -> CaptureGate
      -> Extractor
      -> Deduper
      -> IntakeCoordinator
      -> Diagnostics
```

## Acceptance

Service no longer directly owns:
- raw notification construction;
- extras storage policy;
- filter;
- repository;
- direct privacy second-check;
- dedupe map internals.

---

# Final validation

Run after each PR:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Final search checks:

```bash
grep -R "storageNotification = processingNotification" app/src/main/java
grep -R "parseWithAiFallback(" app/src/main/java
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "contains(\"\\$\") -> \"USD\"" app/src/main/java
grep -R "ForegroundLocationProvider" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "hashCode().toString" app/src/main/java/com/yourname/expensetracker
grep -R "processAndSave(notification" app/src/main/java
```

Expected final state:

- non-raw modes process without raw persistence;
- transient payload is encrypted or explicitly disabled;
- worker terminal status cannot regress;
- intake is app-scope / durable early;
- parser provenance integrated;
- currency fallback has no silent EUR/USD default;
- finance balance-only alerts rejected;
- repository direct/batch paths sanitized;
- source-link failures typed;
- service decomposed.

# Sources used

- Commit page and diff summary: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `NotificationIntakeWorker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
- `NotificationIntakeCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `AppParserRegistry.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt