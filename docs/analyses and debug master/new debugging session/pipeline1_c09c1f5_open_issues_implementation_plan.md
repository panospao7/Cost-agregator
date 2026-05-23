# Pipeline 1 remaining open issues — implementation plan after `c09c1f5`

Baseline: `c09c1f551ae0733e2b6d4803d2bb9d68c719abc5`

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/c09c1f551ae0733e2b6d4803d2bb9d68c719abc5
- `NotificationIntakeCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c09c1f551ae0733e2b6d4803d2bb9d68c719abc5/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt
- `NotificationIntakeWorker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c09c1f551ae0733e2b6d4803d2bb9d68c719abc5/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
- `NotificationFilter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c09c1f551ae0733e2b6d4803d2bb9d68c719abc5/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt
- `AppParserRegistry.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c09c1f551ae0733e2b6d4803d2bb9d68c719abc5/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt

---

# Remaining open / partial issues

| Issue | Status | Core problem |
|---|---:|---|
| TEST-GAP | Open | Tests still not fully green. |
| P1-P1-07 | Partial | Intake runtime exists, but durability/write-barrier/retry/privacy-mode gaps remain. |
| P2-11 | Partial/regressed | Non-raw storage modes lose processing payload; public/batch paths still bypass sanitizer. |
| P2-09 | Open/partial | Finance filter still accepts balance/currency-only noise. |
| P2-10 | Partial | Currency fallback still hardcodes USD/EUR and lacks ambiguity/home-currency model. |
| P2-12 | Partial | Provenance models exist, but parser registry/pipeline still use `ParsedTransaction?`. |
| P3-13 | Partial | Service still owns orchestration/business logic. |
| P1-NEW-14 | Partial | `isProcessed` marked only from intake worker, not all direct paths. |
| P1-NEW-16 | Mostly fixed + cleanup | GPS read removed, but dead dependency/import cleanup remains. |
| P1-NEW-18 | Partial | Source-link failures diagnosed but no typed result. |
| GAP-REFRESH-SOURCE | Open | Manual refresh diagnostics still look like listener events. |
| GAP-PACKAGE-POLICY | Open | Package-policy unavailable can be mislabeled as `BLOCKED_PACKAGE`. |
| GAP-DEDUPE-CLEANUP | Open | Weak package/key hashes, postTime tradeoff, stale hash helper. |

Recommended order:

1. **PR 0 — Green tests**
2. **PR 1 — Fix intake privacy-mode payload regression**
3. **PR 2 — Harden intake durability, worker retry, write barrier, cancellation**
4. **PR 3 — Public/batch repository privacy sanitizer**
5. **PR 4 — Parser provenance integration**
6. **PR 5 — Currency detector**
7. **PR 6 — Finance filter v2**
8. **PR 7 — Source-link typed result**
9. **PR 8 — `RawNotification.isProcessed` global policy**
10. **PR 9 — Small cleanup: location, refresh source, package-policy reason, dedupe**
11. **PR 10 — Service decomposition**

---

# PR 0 — Test stabilization

## Goal

Get the current baseline green before adding more behavior.

## Tasks

1. Fix the remaining 2 MockK failures.
2. Update stale expectations around:
   - fingerprint DAO methods;
   - typed raw insert result;
   - intake worker path;
   - direct pipeline path vs intake path.
3. Add missing regression tests for the known current regressions:
   - non-raw storage modes become `PAYLOAD_UNAVAILABLE_PRIVACY`;
   - write barrier active should not mutate intake row;
   - cancellation should not mark final failure.

## Acceptance

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

passes.

---

# PR 1 — Fix intake privacy-mode payload regression

## Problem

`NotificationIntakeCoordinator` currently stores title/text/body only for `STORE_RAW`.

Then `NotificationIntakeWorker` refuses to process rows with null title/text/body.

Result:

| Mode | Current behavior |
|---|---|
| `STORE_RAW` | processed |
| `STORE_REDACTED` | `PAYLOAD_UNAVAILABLE_PRIVACY` |
| `STORE_METADATA_ONLY` | `PAYLOAD_UNAVAILABLE_PRIVACY` |
| `DO_NOT_STORE` | `PAYLOAD_UNAVAILABLE_PRIVACY` |

This breaks the previous design where raw text was used ephemerally for parsing while sanitized data was persisted.

## Goal

Notification processing must still work under privacy-safe storage modes without silently violating the user’s raw-storage promise.

## Required product policy

Add an explicit distinction:

```text
RawStorageMode = long-term retention policy.
TransientProcessingQueuePolicy = whether encrypted temporary payload may be stored for worker processing.
```

Recommended new setting:

```kotlin
enum class TransientNotificationPayloadPolicy {
    ENCRYPTED_UNTIL_PROCESSED,
    DISABLED
}
```

Default recommendation:
- `STORE_RAW`: no extra setting needed.
- `STORE_REDACTED` / `STORE_METADATA_ONLY`: allow encrypted transient queue by default only if privacy copy clearly states this.
- `DO_NOT_STORE`: require explicit opt-in, otherwise no durable async processing guarantee.

## Schema/model changes

Add fields to `NotificationIntakeEntity`:

```kotlin
val transientPayloadCiphertext: String?
val transientPayloadNonce: String?
val transientPayloadVersion: Int?
val transientPayloadPurgedAt: Long?
val payloadMode: String
```

Keep existing sanitized fields:

```kotlin
title
text
bigText
subText
extrasJson
```

Use them only according to `RawStorageMode`.

## New classes

```text
NotificationTransientPayload
NotificationTransientPayloadStore
NotificationTransientPayloadCrypto
NotificationIntakePayloadPolicy
```

Example payload:

```kotlin
data class NotificationTransientPayload(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?
)
```

## Coordinator behavior

For each mode:

| Mode | Intake persisted visible fields | Transient payload |
|---|---|---|
| `STORE_RAW` | raw fields allowed | optional/not needed |
| `STORE_REDACTED` | redacted marker/text | encrypted raw processing payload |
| `STORE_METADATA_ONLY` | null metadata only | encrypted raw processing payload |
| `DO_NOT_STORE` + transient opt-in | null metadata only | encrypted raw processing payload |
| `DO_NOT_STORE` + no opt-in | null metadata only | none; either direct synchronous processing or documented no recovery |

## Worker behavior

Worker should reconstruct processing text in this order:

1. If `STORE_RAW`, use intake raw fields.
2. Else if encrypted transient payload exists, decrypt and use it.
3. Else mark `PAYLOAD_UNAVAILABLE_PRIVACY`.

After terminal result:

```kotlin
intakeDao.purgeRawPayload(...)
intakeDao.purgeTransientPayload(...)
```

## Important: do not silently store raw text

If encryption/transient policy is not implemented, do not store raw fields in non-raw modes just to make tests pass.

## Tests

1. `STORE_RAW` processes from raw fields.
2. `STORE_REDACTED` processes from encrypted transient payload; persisted visible fields are redacted.
3. `STORE_METADATA_ONLY` processes from encrypted transient payload; visible fields null.
4. `DO_NOT_STORE` without opt-in does not persist raw and returns `PAYLOAD_UNAVAILABLE_PRIVACY`.
5. `DO_NOT_STORE` with opt-in processes and purges transient payload.
6. Transient payload is purged after:
   - auto accepted;
   - needs review;
   - parser failed final;
   - duplicate;
   - failed final.
7. Diagnostics never include raw payload.

## Acceptance

- Non-raw modes no longer silently stop processing unless policy explicitly disables transient queue.
- Raw-storage promise remains true.
- P2-11 privacy regression fixed for intake path.

---

# PR 2 — Harden durable intake runtime

## Problems

Current intake runtime has these gaps:

1. Intake insert still happens in `serviceScope`; `onDestroy()` can cancel before insert.
2. Write barrier active path writes `markRetryableFailure()`, violating write-blocked semantics.
3. `maxAttempts` is not enforced.
4. `CancellationException` is caught by `catch (Exception)` and marked final failure.
5. WorkManager/Hilt configuration needs verification.
6. Recovery scheduler is not guaranteed on app start/boot/restore completion.

## Goal

Make intake truly durable and safe.

## Step 2.1 — Move insert/enqueue critical section out of cancellable service scope

Best design:

```kotlin
@ApplicationScope
CoroutineScope
```

Service should do:

```kotlin
applicationScope.launch {
    captureCoordinator.handle(...)
}
```

or at least:

```kotlin
withContext(NonCancellable) {
    intakeCoordinator.capture(...)
}
```

Critical section:

```text
insert intake row + enqueue worker
```

must not be cancelled by `serviceJob.cancel()`.

## Step 2.2 — Do not mutate DB when write barrier denies writes

Current bad flow:

```kotlin
if (!writeBarrier.writesAllowed()) {
    intakeDao.markRetryableFailure(...)
    return Result.retry()
}
```

Fix:

```kotlin
if (!writeBarrier.writesAllowed()) {
    diagnostics.emit(...)
    return Result.retry()
}
```

Only update intake status when writes are allowed.

If you want intake status writes allowed during restore, formalize this in `DatabaseWriteBarrier` as a named exception. Do not bypass implicitly.

## Step 2.3 — Enforce `maxAttempts`

Before claim or immediately after reload:

```kotlin
if (current.attempts >= current.maxAttempts) {
    intakeDao.markFinalFailure(
        failureCode = "MAX_ATTEMPTS_EXCEEDED"
    )
    return Result.failure()
}
```

For retryable errors:

```kotlin
if (current.attempts + 1 >= current.maxAttempts) {
    markFinalFailure(...)
} else {
    markRetryableFailure(...)
}
```

## Step 2.4 — Correct cancellation handling

Add:

```kotlin
catch (e: CancellationException) {
    Timber.d("IntakeWorker cancelled intakeId=$intakeId")
    throw e
}
```

Do not mark cancellation as final failure.

If row is left `PROCESSING`, recovery scheduler should release stale rows later.

## Step 2.5 — Retry classification

Create:

```kotlin
NotificationIntakeFailureClassifier
```

Retryable:
- DB locked;
- IO/network-ish;
- timeout;
- WorkManager stop;
- write barrier active;
- transient `SQLiteException`.

Final:
- validation/privacy policy;
- payload unavailable;
- duplicate;
- parser final no-signal.

## Step 2.6 — Verify Hilt WorkManager setup

Check for:

```kotlin
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

If absent, add it. Otherwise `@HiltWorker` can fail at runtime.

## Step 2.7 — Recovery scheduling

Run:

```kotlin
notificationIntakeRecoveryScheduler.recoverPending()
```

from:
- app start;
- listener connected;
- boot receiver;
- restore completed event;
- optional periodic maintenance work.

## Tests

1. Service destroy after callback does not cancel intake insert/enqueue.
2. Write barrier active returns retry without intake DB mutation.
3. CancellationException is rethrown.
4. Retryable exception becomes `FAILED_RETRYABLE` until max attempts.
5. Max attempts becomes `FAILED_FINAL`.
6. Stale `PROCESSING` rows are released.
7. HiltWorkerFactory config exists.
8. Insert succeeds but WorkManager enqueue fails -> recovery later enqueues row.

## Acceptance

- P1-P1-07 can be marked fixed, or fixed-with-caveat for `DO_NOT_STORE` without transient opt-in.
- No write-barrier violation in worker.
- Worker retry lifecycle is finite and recoverable.

---

# PR 3 — Public/batch repository privacy sanitizer

## Problem

Listener/intake path has privacy mapping, but repository still exposes direct raw paths:

```kotlin
processAndSave(notification)
processAndSaveAll(notifications)
```

These can persist raw notification title/text/body/extras as storage payload.

## Goal

All entrypoints use the same storage privacy policy.

## Tasks

1. Create:

```text
NotificationPersistencePayload
NotificationPersistenceMapper
NotificationExtrasPersistencePolicy
```

2. Change repository public API:

Preferred:

```kotlin
suspend fun processAndSave(payload: NotificationPersistencePayload): NotificationPipelineOutcome
```

Compatibility wrapper:

```kotlin
suspend fun processAndSave(notification: RawNotification): NotificationPipelineOutcome {
    val payload = persistenceMapper.fromRaw(notification)
    return processAndSave(payload)
}
```

3. Batch path:

```kotlin
processAndSaveAll(notifications)
```

must map each item to processing/storage pair.

4. Storage notification must:
   - keep raw only for `STORE_RAW`;
   - redact for `STORE_REDACTED`;
   - metadata-only/null for `STORE_METADATA_ONLY`;
   - no body/extras for `DO_NOT_STORE`.

5. Extras JSON builder must not run for non-raw modes.

## Tests

For every `RawStorageMode`, through:
- listener/intake path;
- direct repository path;
- batch path.

Verify no raw text appears in:
- raw notifications;
- pending reviews;
- transaction events;
- diagnostics;
- source-link metadata.

## Acceptance

- P2-11 fixed globally.
- No public API can bypass storage sanitizer.

---

# PR 4 — Parser provenance integration

## Problem

`ParseProvenance.kt` exists, but the registry still returns only:

```kotlin
ParsedTransaction?
```

Pipeline still calls:

```kotlin
parseWithAiFallback(...)
```

## Goal

Parser registry returns typed parse outcome with provenance.

## New model

Add:

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

## Tasks

1. Add `parserId` to `AppNotificationParser`:

```kotlin
val parserId: String get() = this::class.simpleName ?: "UnknownParser"
```

2. Add:

```kotlin
suspend fun parseWithProvenance(...): ParseOutcome
```

to `AppParserRegistry`.

3. Deterministic path records:
   - specific parser attempted/succeeded;
   - generic parser attempted/succeeded;
   - parser exceptions as attempts.

4. AI path records:
   - attempted/skipped;
   - provider/model if available;
   - confidence;
   - failure reason.

5. Deprecate:

```kotlin
parseWithAiFallback(...)
```

6. Update `NotificationProcessingPipeline` to use `ParseOutcome`.

7. Diagnostics include safe provenance:
   - parser source;
   - parser ID;
   - AI status;
   - provider/model;
   - confidence;
   - failure reason.

No raw prompt/response/text.

## Tests

1. Specific parser success.
2. Generic parser success.
3. Deterministic fail + AI success.
4. AI skipped by package policy.
5. AI exception.
6. No duplicate deterministic parse.
7. Pipeline diagnostics include provenance.

## Acceptance

- P2-12 fixed.
- Every parsed/review/failed outcome has provenance.

---

# PR 5 — Shared money/currency detector

## Problem

Fallback still does:

```kotlin
"$" -> USD
else -> EUR
```

No home-currency or ambiguity model.

## Goal

One detector shared by filter and pipeline.

## Components

```text
NotificationMoneySignalDetector
CurrencyLexicon
MoneySignal
CurrencyResolution
UserCurrencyProvider
```

## Data model

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

## Required currencies

```text
EUR, USD, GBP, CHF, PLN, RON, TRY, CAD, AUD, JPY,
SEK, NOK, DKK, HUF, CZK
```

Handle:
- `€12.30`
- `12,30 EUR`
- `PLN 42.00`
- `42,00 zł`
- `120,50 lei`
- `₺75.90`
- `A$12.00`
- `C$12.00`
- `99 kr`
- `JPY 1200`

## Tasks

1. Replace pipeline regex fallback with detector.
2. Replace filter amount detection with detector.
3. Add `UserCurrencyProvider`.
4. For ambiguous `$`, use home currency if USD/CAD/AUD.
5. For ambiguous `kr`, use home currency if SEK/NOK/DKK.
6. If unresolved, create low-confidence review or reject; do not silently EUR.
7. Diagnostics include resolution basis.

## Tests

| Text | Home | Expected |
|---|---:|---|
| `Paid PLN 42.00` | EUR | PLN |
| `42,00 zł` | EUR | PLN |
| `120,50 lei` | EUR | RON |
| `₺75.90` | EUR | TRY |
| `A$12.00` | EUR | AUD |
| `C$12.00` | EUR | CAD |
| `$12.00` | CAD | CAD inferred |
| `$12.00` | null | ambiguous/default warning |
| `99 kr` | SEK | SEK inferred |
| `EUR/USD 1.08` | EUR | reject |
| `Card *1234` | EUR | reject |

## Acceptance

- P2-10 fixed.
- No hardcoded silent EUR/USD fallback remains.

---

# PR 6 — Finance filter v2

## Problem

Current finance-app filter still allows any currency-looking notification from finance packages.

Balance-only example can pass:

```text
Available balance €1,240.00
```

## Goal

Finance packages require transaction-like/debit/reviewable signal, not just amount.

## New model

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
- `ALLOW_STRONG_EXPENSE`
- `ALLOW_OUTGOING_TRANSFER`
- `BALANCE_ONLY`
- `ACCOUNT_INFO_ONLY`
- `CURRENCY_ONLY`
- `SECURITY_OR_AUTH`
- `PROMOTION`
- `INCOMING_ONLY`
- `PAYMENT_FAILED_OR_DECLINED`
- `NO_TRANSACTION_SIGNAL`

## Tasks

1. Convert `NotificationFilter` to injectable class or add object facade over detector.
2. Add deny groups:
   - OTP/security/login;
   - promo/cashback offer;
   - balance/account/statement;
   - FX/currency rate;
   - failed/declined payment if not desired.
3. Add allow groups:
   - paid/spent/purchase/charged/card/POS/contactless;
   - Greek/Greeklish equivalents;
   - outgoing transfer.
4. Add incoming rejection:
   - received/credited/deposit/salary/refund.
5. Diagnostics store reason/confidence/direction only.

## Tests

Allow:
- Revolut purchase;
- Google Wallet payment;
- Greek bank card charge;
- SMS/Gmail bank purchase;
- outgoing transfer.

Reject:
- balance-only amount;
- account statement;
- FX rate;
- OTP;
- login;
- promo;
- incoming transfer;
- salary/deposit;
- declined payment;
- unknown package with currency only.

## Acceptance

- P2-09 fixed or mostly fixed with explicit product-policy caveats.

---

# PR 7 — Typed source-link result

## Problem

Source-link failures are diagnosed but the helper still returns `Unit`.

## Goal

Make source-link writes observable to callers.

## Model

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

## Tasks

1. Create `NotificationSourceLinkWriter`.
2. Replace source-link helper in pipeline.
3. Return typed result.
4. Emit `SOURCE_LINK_FAILED` diagnostic on failure.
5. Decide repair policy:
   - do not roll back expense/review by default;
   - optional repair queue for retryable failures.

## Tests

1. Created result.
2. AlreadyExists result.
3. Retryable failure.
4. Non-retryable failure.
5. Auto-accept still succeeds if source-link fails.
6. Diagnostics contain no raw notification text.

## Acceptance

- P1-NEW-18 fixed.

---

# PR 8 — `RawNotification.isProcessed` global policy

## Problem

Worker marks raw rows processed, but direct repository/pipeline paths can still create terminal raw rows without marking them.

## Options

### Option A — pipeline owns mark processed

Pipeline marks raw row processed after terminal outcome for every path.

Pros:
- direct/batch paths covered.

Cons:
- status side effect inside pipeline.

### Option B — intake status is canonical; direct paths deprecated

Pros:
- cleaner long-term.

Cons:
- direct paths remain inconsistent.

## Recommended

1. Pipeline exposes terminal raw ID in outcome.
2. Repository or worker marks processed after terminal outcome.
3. Direct/batch repository wrappers also mark processed.
4. KDoc says:

```text
isProcessed is a legacy coarse terminal marker.
NotificationIntakeEntity.status is authoritative where available.
```

## Tests

1. Intake auto-accept -> processed.
2. Direct auto-accept -> processed.
3. Batch needs-review -> processed.
4. Retryable error -> not processed.
5. Duplicate -> does not mutate unrelated row.

## Acceptance

- P1-NEW-14 fully fixed.

---

# PR 9 — Small cleanup PR

## 9.1 Refresh diagnostics source

Replace:

```kotlin
refreshActiveNotifications() -> onNotificationPosted(sbn)
```

with:

```kotlin
enqueueNotificationCapture(sbn, CaptureSource.REFRESH)
```

Listener uses:

```kotlin
CaptureSource.LISTENER
```

Acceptance:
- diagnostics distinguish refresh vs listener.

## 9.2 Package-policy unavailable reason

If blocked-package DAO fails/timeouts, emit:

```text
PACKAGE_POLICY_UNAVAILABLE
```

not `BLOCKED_PACKAGE`.

Acceptance:
- actual blocked package and policy unavailable are distinguishable.

## 9.3 Dedupe cleanup

Replace:
- `String.hashCode()` package/key hashes;
- truncated weak hashes;
- stale `computeNotificationContentHash()`.

Use SHA-256/HMAC.

Reconsider postTime:
- in-flight key should probably be `packageHash + keyHash + contentFingerprint`;
- durable raw fingerprint can include postTime bucket if needed.

Acceptance:
- no JVM `hashCode()` helper remains in capture dedupe.

## 9.4 Messaging fallback cleanup

In `NotificationTextParts`, remove unknown parcelable:

```kotlin
item.toString()
```

Use `null`.

Acceptance:
- unknown bundles/parcelables do not pollute parser body.

## 9.5 Location dead dependency cleanup

Remove unused `ForegroundLocationProvider` from `NotificationProcessingPipeline` constructor/import/tests.

Acceptance:
- no location provider dependency remains in notification pipeline.

---

# PR 10 — Service decomposition

## Problem

Even after intake runtime, `NotificationCaptureService` still owns too much orchestration.

## Goal

Service is Android adapter only.

## Components

```text
NotificationCaptureCoordinator
NotificationCaptureDeduper
NotificationEnvelopeFactory
NotificationPersistenceMapper
NotificationRefreshCoordinator
NotificationForegroundController
NotificationRestartScheduler
```

## Target architecture

```text
NotificationCaptureService
  -> NotificationCaptureCoordinator
      -> CaptureGate
      -> Extractor
      -> Filter
      -> Deduper
      -> IntakeCoordinator
      -> Diagnostics
```

## Tasks

1. Move capture flow into coordinator.
2. Service only forwards:
   - listener callback;
   - refresh request;
   - lifecycle events.
3. Move foreground notification/channel to controller.
4. Move restart alarm to scheduler.
5. Remove direct service dependencies:
   - repository;
   - raw notification construction;
   - extras JSON builder;
   - filter;
   - privacyGate direct second check if gate owns policy.

## Tests

1. Gate denied -> extractor not called.
2. Allowed -> intake coordinator called.
3. Refresh/listener share same coordinator but different source.
4. Service has no business logic branches except lifecycle.

## Acceptance

- P3-13 fixed.
- Future Pipeline 1 changes are testable outside Android service.

---

# Final tracker recommendation after all PRs

| Issue | Target status |
|---|---:|
| P1-P1-07 | Fixed / fixed with explicit DO_NOT_STORE caveat |
| P2-09 | Fixed |
| P2-10 | Fixed |
| P2-11 | Fixed |
| P2-12 | Fixed |
| P3-13 | Fixed |
| P1-NEW-14 | Fixed |
| P1-NEW-16 | Fixed |
| P1-NEW-18 | Fixed |
| GAP-REFRESH-SOURCE | Fixed |
| GAP-PACKAGE-POLICY | Fixed |
| GAP-DEDUPE-CLEANUP | Fixed |
| TEST-GAP | Fixed |

---

# Final validation search checks

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Searches:

```bash
grep -R "parseWithAiFallback(" app/src/main/java
grep -R "getLastKnownLocation" app/src/main/java
grep -R "String.hashCode" app/src/main/java/com/yourname/expensetracker
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "contains(\"$\") -> \"USD\"" app/src/main/java
grep -R "processAndSave(notification" app/src/main/java
grep -R "PAYLOAD_UNAVAILABLE_PRIVACY" app/src/test
grep -R "ForegroundLocationProvider" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
```

Expected final state:
- tests green;
- non-raw modes either process through encrypted transient payload or explicitly opt out;
- intake worker respects write barrier;
- parser provenance is real;
- no hidden GPS;
- no silent EUR/USD fallback;
- finance balance-only alerts rejected;
- direct/batch paths sanitized;
- service decomposed.