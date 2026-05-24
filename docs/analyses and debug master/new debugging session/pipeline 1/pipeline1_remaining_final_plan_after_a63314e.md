# Pipeline 1 remaining implementation plan after `a63314e`

Baseline: `a63314e16e59e0743c846e4ecd5407e568722551`

Current status:

```text
Pipeline 1 is close, but not clean yet.
Most privacy/durability foundations exist, but a few final correctness gaps remain.
```

Remaining issue groups:

1. P2-10 — money detector exists but hardcoded fallback branches remain.
2. P2-11 — historical plaintext transient repair ordering/batching needs hardening.
3. P2-11/P1-P1-07 — worker decrypt/load failures are outside the main failure-handling block.
4. P1-P1-07 — durable intake is still inserted late, unless you explicitly accept a narrower durability guarantee.
5. P2-11 — DO_NOT_STORE synchronous path should pass explicit persistence context.
6. P2-09 — finance filter is mostly fixed but still heuristic.
7. P3-13 — service decomposition remains partial.
8. Final regression tests/docs/tracker.

Recommended order:

1. **PR 1 — Currency detector fully replaces fallback**
2. **PR 2 — Historical transient repair ordering + loop**
3. **PR 3 — Worker crypto/load/filter failure handling**
4. **PR 4 — DO_NOT_STORE sync persistence context**
5. **PR 5 — Intake durability/app-scope decision**
6. **PR 6 — Finance filter finalization**
7. **PR 7 — Cleanup/service decomposition**
8. **PR 8 — Final regression suite + tracker**

---

# PR 1 — P2-10: currency detector fully replaces fallback

## Problem

`NotificationMoneySignalDetector` now exists, and `NotificationProcessingPipeline` injects it.

But active fallback logic still has hardcoded currency branches like:

```kotlin
fullText.contains("$") -> "USD"
else -> defaultCurrency
```

This means the detector does not fully control fallback currency resolution.

Bad examples:

| Notification | Expected | Current risk |
|---|---:|---|
| `Paid $12.00` with home CAD | CAD | USD |
| `Paid $12.00` with home AUD | AUD | USD |
| `Paid 99 kr` with home SEK | SEK | not resolved by detector |
| no explicit currency | ambiguous/home/default basis | silent default |

## Goal

Fallback review creation must use `MoneySignal`, not local regex branches.

No active code should silently map:

```text
$ -> USD
else -> EUR/default
```

without explicit `CurrencyResolution`.

## Implementation

### Step 1.1 — Change fallback candidate APIs

Replace APIs like:

```kotlin
detectTransactionSignalCandidate(
    title,
    text,
    bigText,
    defaultCurrency: String
)
```

with:

```kotlin
detectTransactionSignalCandidate(
    title: String?,
    text: String?,
    bigText: String?,
    homeCurrency: String?
): TransactionSignalCandidate?
```

Inside:

```kotlin
val fullText = ...
val signal = moneySignalDetector.bestTransactionAmount(
    text = fullText,
    homeCurrency = homeCurrency,
    allowedCurrencies = CurrencyLexicon.DEFAULT_SUPPORTED
) ?: return null
```

Build candidate from `signal`:

```kotlin
return TransactionSignalCandidate(
    amount = signal.amount,
    currency = signal.currencyCode ?: return handleAmbiguous(signal),
    merchantHint = extractMerchantHint(fullText),
    currencyResolution = signal.resolution,
    currencyConfidence = signal.confidence
)
```

Do the same for:

```kotlin
detectOversizedAmountCandidate(...)
```

### Step 1.2 — Add ambiguity policy

If `signal.currencyCode == null` and `signal.ambiguous == true`:

Option A, safer:

```text
Return null, no fallback review.
```

Option B, more user-friendly:

```text
Create low-confidence PendingReview with home/default currency only if basis is explicit:
- AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME
- USER_HOME_CURRENCY
- APP_DEFAULT_CURRENCY
```

Do **not** create a normal-confidence review pretending currency was explicit.

### Step 1.3 — Add diagnostic metadata

For fallback-created reviews:

```text
currencyResolution
currencyConfidence
currencyCandidates
currencyAmbiguous
currencySource = EXPLICIT / HOME / DEFAULT / AMBIGUOUS
```

Do not store raw notification text.

### Step 1.4 — Delete/disable old branches

Search and remove:

```bash
grep -R "contains(\"\\$\")" app/src/main/java/com/yourname/expensetracker/data/repository
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "defaultCurrency" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
```

Expected:
- no hardcoded `$ -> USD`;
- no hardcoded `else -> EUR`;
- no currency fallback bypassing `MoneySignal`.

## Tests

Add table tests:

| Text | Home currency | Expected |
|---|---:|---|
| `Paid PLN 42.00 at Zabka` | EUR | PLN |
| `Zapłacono 42,00 zł` | EUR | PLN |
| `Plata 120,50 lei` | EUR | RON |
| `Paid ₺75.90` | EUR | TRY |
| `Paid A$12.00` | EUR | AUD |
| `Paid C$12.00` | EUR | CAD |
| `Paid $12.00` | CAD | CAD, inferred |
| `Paid $12.00` | AUD | AUD, inferred |
| `Paid $12.00` | null | ambiguous/no silent USD |
| `Paid 99 kr` | SEK | SEK, inferred |
| `Paid 99 kr` | null | ambiguous/no silent DKK/SEK/NOK |
| `EUR/USD 1.08` | EUR | reject |
| `Card *1234` | EUR | reject |

## Acceptance

- P2-10 fixed.
- Pipeline fallback no longer guesses USD/EUR silently.
- Currency basis is visible in diagnostics/review metadata.

---

# PR 2 — P2-11: historical transient repair ordering + batching

## Problem

`NotificationIntakePayloadRepairer` exists and can:

- purge terminal legacy plaintext transient rows;
- encrypt non-terminal plaintext transient rows.

But service currently risks running recovery before repair. If recovery starts workers first, old plaintext rows without ciphertext can be marked `PAYLOAD_UNAVAILABLE_PRIVACY` before repair encrypts them.

Also, repair currently fetches only one batch of 100 rows.

## Goal

Repair must happen before any pending intake recovery/worker enqueue, and it must loop until no legacy rows remain.

## Implementation

### Step 2.1 — Change startup/listener order

Wherever this currently happens:

```kotlin
intakeRecoveryScheduler.recoverPending()
intakePayloadRepairer.repairLegacyPlaintextTransientRows()
```

change to:

```kotlin
intakePayloadRepairer.repairLegacyPlaintextTransientRowsUntilComplete()
intakeRecoveryScheduler.recoverPending()
```

Apply this order on:

- app start;
- notification listener connected;
- restore completed;
- boot receiver if present;
- any manual recovery/debug action.

### Step 2.2 — Make repair loop

Current method:

```kotlin
val rows = intakeDao.getLegacyPlaintextTransientRows(100)
...
```

Add:

```kotlin
suspend fun repairLegacyPlaintextTransientRowsUntilComplete(
    batchSize: Int = 100,
    maxBatches: Int = 100
): RepairSummary {
    var totalRepaired = 0
    var batches = 0

    while (batches < maxBatches) {
        val repaired = repairLegacyPlaintextTransientRowsBatch(batchSize)
        if (repaired == 0) break
        totalRepaired += repaired
        batches++
    }

    return RepairSummary(totalRepaired, batches)
}
```

Keep a max batch guard to avoid infinite loops.

### Step 2.3 — Return typed repair result

```kotlin
data class RepairSummary(
    val repairedRows: Int,
    val purgedTerminalRows: Int,
    val encryptedPendingRows: Int,
    val failedRows: Int,
    val batches: Int
)
```

Emit safe diagnostics:

```text
stage = "intake_payload_repair"
repairedRows
purgedTerminalRows
encryptedPendingRows
failedRows
```

No raw text.

### Step 2.4 — Explicit policy for corrupt old rows

If a non-terminal legacy row has visible fields all null and no ciphertext:

```text
mark PAYLOAD_UNAVAILABLE_PRIVACY and purge
```

or leave for worker to handle.

Recommended: mark final with diagnostic in repairer so the row does not loop forever.

## Tests

1. Terminal legacy transient row with visible plaintext:
   - visible fields purged;
   - terminal status remains terminal.

2. Non-terminal legacy transient row with visible plaintext:
   - encrypted payload created;
   - visible fields cleared;
   - worker later processes it.

3. More than 100 rows:
   - multiple batches run;
   - all rows repaired.

4. Repair before recovery:
   - recovery sees encrypted rows, not plaintext-only rows.

5. Repair failure:
   - one bad row does not stop the whole repair pass;
   - failure is diagnosed safely.

## Acceptance

- No legacy plaintext transient rows remain after startup repair.
- Recovery never races ahead of repair.

---

# PR 3 — Worker crypto/load/filter failure handling

## Problem

The worker now decrypts before filtering, which is correct.

But decrypt/load/filter/build happens before the main `try/catch` that handles repository failures. If crypto decrypt throws, the worker can exit without marking the row retry/final, leaving it stuck in `PROCESSING` until stale recovery.

## Goal

All post-claim worker logic must be inside the worker’s controlled failure-handling block.

## Implementation

### Step 3.1 — Wrap full post-claim flow

Current rough shape:

```kotlin
val current = intakeDao.getById(...)
val payload = crypto.decrypt(...)
if (!NotificationFilter.shouldCapture(...)) ...
val processingNotification = ...
return try {
    val outcome = repository.processAndSave(...)
    ...
} catch ...
```

Change to:

```kotlin
return try {
    val payload = loadProcessingPayloadOrThrow(current, rawMode)

    if (payload == null) {
        markTerminalPayloadUnavailableAndPurge(...)
        return Result.success()
    }

    val filterDecision = NotificationFilter.decide(...payload...)
    if (!filterDecision.capture) {
        markFilterRejectedAndPurge(...)
        return Result.success()
    }

    val processingNotification = buildProcessingNotification(current, payload)
    val storageNotification = buildStorageNotification(processingNotification, current.rawStorageMode)
    val context = buildPersistenceContext(current, rawMode)

    val outcome = repository.processAndSave(..., persistenceContext = context)
    markTerminalAndCleanup(...)
    Result.success()

} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    handleWorkerException(e, current)
}
```

### Step 3.2 — Classify crypto failures

Create:

```kotlin
private fun classifyWorkerFailure(e: Throwable): WorkerFailureClass
```

```kotlin
sealed interface WorkerFailureClass {
    data object Retryable : WorkerFailureClass
    data object FinalPurgePayload : WorkerFailureClass
}
```

Recommended classification:

| Exception | Classification |
|---|---|
| `AEADBadTagException` | final + purge |
| bad base64 / corrupt ciphertext | final + purge |
| unknown payload version | final + purge |
| `KeyPermanentlyInvalidatedException` | final + purge + diagnostic |
| transient keystore unavailable | retryable |
| database locked | retryable |
| `IOException` | retryable |
| unknown exception before repository | retryable until max attempts, then final + purge |

### Step 3.3 — Preserve retry payloads

For retryable errors:

```text
do not purge encrypted payload.
```

For final errors:

```text
mark final and purge encrypted/visible payload.
```

### Step 3.4 — Avoid terminal regression

Keep existing `terminalMarked` guard for post-terminal cleanup failures.

## Tests

1. Bad ciphertext:
   - worker marks `FAILED_FINAL`;
   - payload purged;
   - no stuck `PROCESSING`.

2. Temporary DB lock during repository:
   - worker marks `FAILED_RETRYABLE`;
   - encrypted payload remains.

3. Keystore transient failure:
   - retryable if classifier says so.

4. Cancellation:
   - rethrown;
   - stale recovery later releases row.

5. Filter rejected after decrypt:
   - terminal `FILTER_REJECTED`;
   - payload purged.

6. Cleanup failure after terminal:
   - terminal status remains terminal.

## Acceptance

- No exception after claim leaves row uncontrolled.
- Payload purge behavior matches retry/final semantics.

---

# PR 4 — DO_NOT_STORE synchronous persistence context

## Problem

Worker now passes captured `NotificationPersistenceContext`.

But the synchronous `DO_NOT_STORE` path in the service still likely calls repository without explicit context, so pipeline may fall back to current privacy settings.

For DO_NOT_STORE, processing is immediate, so risk is lower, but consistency matters.

## Goal

All notification processing paths pass a persistence context.

## Implementation

### Step 4.1 — Update `processNotification(...)` service method

Add parameter:

```kotlin
source: CaptureSource
```

or use already-available source.

Build:

```kotlin
val persistenceContext = NotificationPersistenceContext(
    rawStorageMode = settings.rawNotificationStorageMode,
    payloadMode = if (settings.rawNotificationStorageMode == RawStorageMode.DO_NOT_STORE) {
        "EPHEMERAL"
    } else {
        "SERVICE_DIRECT"
    },
    source = source.name.lowercase()
)
```

Call:

```kotlin
repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = correlationId,
    persistenceContext = persistenceContext
)
```

### Step 4.2 — Make repository context explicit

Prefer no nullable context for notification path:

```kotlin
processAndSave(
    processingNotification,
    storageNotification,
    correlationId,
    persistenceContext
)
```

Keep nullable only for legacy tests if necessary.

### Step 4.3 — Pipeline fail-safe

In pending-review creation, avoid fallback to current settings. If context is missing:

Option A:

```text
fail closed to DO_NOT_STORE behavior for review text
```

Option B:

```text
use storageNotification fields only
```

Recommended:

```kotlin
val mode = persistenceContext?.rawStorageMode ?: RawStorageMode.DO_NOT_STORE
```

## Tests

1. `DO_NOT_STORE` synchronous path:
   - raw processing works;
   - raw storage row has null body;
   - pending review has null/sanitized text;
   - diagnostics have no raw body.

2. Missing context fallback:
   - does not store raw review text.

3. `STORE_RAW` direct context:
   - raw allowed.

## Acceptance

- No notification path depends on current settings after payload capture.
- DO_NOT_STORE behavior is consistent and tested.

---

# PR 5 — P2-10 money detector integration completion

This PR is separated from PR 1 if you want smaller changes. If PR 1 fully completed P2-10, skip this PR.

## Additional tasks

### Step 5.1 — Use detector in `NotificationFilter`

`NotificationFilterDecision` should include:

```kotlin
moneySignals: List<MoneySignal>
```

or at least:

```kotlin
primaryMoneySignal: MoneySignal?
```

instead of only:

```kotlin
hasMoneySignal: Boolean
```

### Step 5.2 — Add review explanation

For fallback pending reviews:

```text
currencyResolution = EXPLICIT_ISO_CODE
currencyResolution = AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME
currencyResolution = AMBIGUOUS_UNRESOLVED
```

Add safe metadata to PendingReview or diagnostic.

### Step 5.3 — Remove old regex constants if unused

Delete local pipeline constants:

```kotlin
CURRENCY_HINT_REGEX
CURRENCY_SUFFIX_REGEX
AMOUNT_TOKEN_REGEX
```

only after detector fully replaces them.

## Acceptance

- Detector is single source of truth for fallback money/currency.

---

# PR 6 — Intake durability/app-scope decision

## Problem

Full P1-P1-07 durability is still partial because service does significant work before intake insert, inside service scope.

## Decision point

Choose and document one of these:

### Option A — Full durability guarantee

```text
After capture gate allows extraction, notification is durably spooled before filter/pipeline.
```

Requires architecture change.

### Option B — Qualified durability guarantee

```text
Notifications that reach NotificationIntakeCoordinator are durable.
DO_NOT_STORE is synchronous and not process-death recoverable.
Pre-intake service cancellation remains best-effort.
```

If choosing B, update tracker/docs honestly.

Recommended if aiming “clean”: Option A.

## Option A implementation

### Step 6.1 — App-scope capture coordinator

Create:

```kotlin
@Singleton
class NotificationCaptureCoordinator @Inject constructor(
    private val captureGate: NotificationCaptureGate,
    private val extractor: NotificationExtractor,
    private val intakeCoordinator: NotificationIntakeCoordinator,
    private val diagnostics: NotificationDiagnosticEmitter,
    private val deduper: NotificationCaptureDeduper,
    ...
)
```

Service:

```kotlin
private fun captureNotification(sbn: StatusBarNotification, source: CaptureSource) {
    applicationScope.launch {
        captureCoordinator.handle(sbn, source)
    }
}
```

### Step 6.2 — Insert intake before filter

Coordinator flow:

```text
1. emit RECEIVED
2. gate
3. extract
4. insert intake row
5. enqueue worker
```

Worker flow:

```text
1. load/decrypt payload
2. filter
3. terminal FILTER_REJECTED or process
```

### Step 6.3 — Keep DO_NOT_STORE explicit

For DO_NOT_STORE:

```text
synchronous ephemeral path, not durable
```

or add explicit encrypted opt-in.

## Tests

1. service destroyed after callback but before old filter point:
   - intake row still exists.

2. filter-rejected notification:
   - intake row exists;
   - status `FILTER_REJECTED`;
   - payload purged.

3. WorkManager enqueue failure:
   - intake row remains `RECEIVED`;
   - recovery enqueues later.

4. refresh/listener source preserved.

## Acceptance

- P1-P1-07 can be marked fixed or fixed-with-explicit-DO_NOT_STORE-caveat.

---

# PR 7 — Finance filter final hardening

## Problem

Filter is improved but still heuristic.

## Goal

Make filter decisions explainable enough for Pipeline 1 ready status.

## Tasks

1. Ensure every rejection uses precise reason:
   - `BALANCE_ONLY`
   - `INCOMING_ONLY`
   - `PROMOTION`
   - `CURRENCY_ONLY`
   - `PAYMENT_FAILED_OR_DECLINED`
   - `SECURITY_OR_AUTH`
   - `NO_AMOUNT`
   - `NO_TRANSACTION_SIGNAL`

2. Ensure every allowed path uses:
   - `ALLOW_STRONG_EXPENSE`
   - `ALLOW_OUTGOING_TRANSFER`
   - `ALLOW_REVIEWABLE_FINANCIAL_SIGNAL`

3. Populate direction:
   - `DEBIT`
   - `CREDIT`
   - `TRANSFER_OUT`
   - `TRANSFER_IN`
   - `UNKNOWN`

4. Use `MoneySignal` for amount/currency.

5. Add safe diagnostic metadata:
   - reason;
   - direction;
   - confidence;
   - currencyResolution;
   - hasMoneySignal.

## Tests

Reject:
- available balance with amount;
- account statement;
- FX rate;
- OTP/security;
- promo;
- incoming transfer;
- salary/deposit;
- card declined;
- unknown package with currency only.

Allow:
- card purchase;
- POS/contactless;
- online payment;
- Greek bank charge;
- SMS/Gmail bank purchase;
- outgoing transfer.

## Acceptance

- P2-09 fixed enough for readiness.

---

# PR 8 — Final cleanup and regression suite

## Cleanup

1. Make `parseWithAiFallback()` hard-deprecated:

```kotlin
@Deprecated("Use parseWithProvenance", level = DeprecationLevel.ERROR)
```

2. Remove dead GPS/location dependency if any.

3. Remove stale weak hash helper:

```kotlin
computeNotificationContentHash()
```

if no longer used.

4. Verify no static crypto key:

```bash
grep -R "STATIC_KEY" app/src/main/java
```

5. Verify no hardcoded currency fallback:

```bash
grep -R "contains(\"\\$\") -> \"USD\"" app/src/main/java
grep -R "else -> \"EUR\"" app/src/main/java
```

6. Verify batch path safe:

```bash
grep -R "processBatch(notifications" app/src/main/java
```

7. Verify notification key hash no longer uses `hashCode()`.

## Golden tests

Run for all storage modes:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

Scenarios:

1. valid notification creates expense/review.
2. storage row respects mode.
3. pending review respects captured mode.
4. diagnostics contain no raw text.
5. intake payload purged on terminal.
6. encrypted payload remains only for retryable rows.
7. repair legacy rows before recovery.
8. parser provenance correct.
9. currency detector correct.
10. finance balance-only rejected.
11. refresh source correct.
12. no GPS access.

## Commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If possible:

```bash
./gradlew connectedDebugAndroidTest
```

## Tracker finalization

Only mark Pipeline 1 clean when:

```text
1. P2-10 hardcoded fallback is gone.
2. P2-11 privacy is safe for intake/raw/review/batch.
3. Worker handles crypto failures safely.
4. Historical repair is ordered before recovery and loops.
5. P1-P1-07 guarantee is either truly fixed or explicitly documented with caveat.
6. Tests are green.
```

---

# Minimum ready bar

Pipeline 1 can be called ready if all are true:

```text
- Encrypted transient rows process successfully.
- Crypto uses Android Keystore, not static key.
- Legacy plaintext rows are repaired before recovery.
- Decrypt failures do not leave rows stuck.
- Captured privacy mode controls all persistence.
- Batch/direct paths sanitize storage.
- Currency fallback no longer guesses USD/EUR silently.
- Finance filter rejects balance/currency-only noise.
- Diagnostics are source-aware and raw-safe.
- Test suite is green.
```

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/a63314e16e59e0743c846e4ecd5407e568722551
- `NotificationIntakeWorker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
- `NotificationIntakePayloadRepairer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakePayloadRepairer.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt