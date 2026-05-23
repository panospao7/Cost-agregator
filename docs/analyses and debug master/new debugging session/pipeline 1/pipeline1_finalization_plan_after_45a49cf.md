# Pipeline 1 finalization implementation plan

Baseline: `45a49cf0a847f010303ff207583d9e2fb777f896`

Goal:

```text
Make Pipeline 1 clean/ready without remaining privacy, durability, parser, currency, filter, or service-architecture gaps.
```

Current blockers to close before “ready”:

1. Transient intake payload is still plaintext in Room.
2. Some terminal worker paths can retain transient raw payload.
3. PendingReview sanitization can use current settings instead of captured intake mode.
4. Intake is still inserted late after service-side filtering.
5. Parser provenance is still not wired.
6. Currency fallback still silently defaults to USD/EUR.
7. Finance filter is improved but still not fully structured.
8. Public/batch repository paths still can bypass storage sanitization.
9. Batch `isProcessed`, refresh-source diagnostics, dedupe-hash cleanup, location dead dependency, and service decomposition remain.

Recommended order:

1. PR 1 — Intake terminal purge + captured privacy mode propagation
2. PR 2 — Transient payload encryption / explicit policy
3. PR 3 — Move intake earlier and app-scope handoff
4. PR 4 — Public/batch repository privacy sanitizer
5. PR 5 — Parser provenance integration
6. PR 6 — Shared money/currency detector
7. PR 7 — Finance filter v2
8. PR 8 — Remaining lifecycle/provenance cleanup
9. PR 9 — Service decomposition
10. PR 10 — Final validation/docs/tracker

---

# PR 1 — Intake terminal purge + captured privacy mode propagation

## Fixes

- transient raw payload retained on some terminal paths
- PendingReview privacy using current settings instead of captured intake mode
- P2-11 remaining privacy gaps
- P1-P1-07 privacy correctness

## Problem A — terminal paths can retain transient payload

Current worker purges after normal terminal success, but these paths still risk retaining raw payload:

```text
FILTER_REJECTED
MAX_ATTEMPTS_EXCEEDED
non-retryable pre-terminal exception
unknown rawStorageMode final failure
payload unavailable final failure
```

## Implementation

Create one terminal helper:

```kotlin
private suspend fun markTerminalAndPurgeBestEffort(
    row: NotificationIntakeEntity,
    status: NotificationIntakeStatus,
    rawId: Long? = null,
    expenseId: Long? = null,
    reviewId: Long? = null,
    finalOutcome: String?,
    failureCode: String? = null,
    nowMs: Long
): Result
```

Behavior:

1. Mark terminal.
2. Set `terminalMarked = true`.
3. Mark raw processed best-effort if applicable.
4. Purge transient payload best-effort if `row.payloadMode == "TRANSIENT"`.
5. Return success/failure according to terminal status.
6. Never regress terminal status if purge/markProcessed fails.

Use this helper for:

```text
PROCESSED
DROPPED_DUPLICATE
DROPPED_POLICY
FILTER_REJECTED
FAILED_FINAL
PAYLOAD_UNAVAILABLE_PRIVACY
MAX_ATTEMPTS_EXCEEDED
UNKNOWN_RAW_STORAGE_MODE
```

Retryable failures should **not** purge payload, because retry needs processing text.

## Problem B — PendingReview sanitization uses current settings

Current pipeline can call:

```kotlin
privacySettingsRepository.getSettings().rawNotificationStorageMode
```

That means worker-time settings can differ from capture-time settings.

Bad scenario:

```text
1. Notification captured under DO_NOT_STORE.
2. User changes settings to STORE_RAW.
3. Worker later creates PendingReview.
4. Pipeline may store raw review text.
```

## Implementation

Add:

```kotlin
data class NotificationPersistenceContext(
    val rawStorageMode: RawStorageMode,
    val payloadMode: String?,
    val source: String
)
```

Change repository/pipeline signatures:

```kotlin
suspend fun processAndSave(
    processingNotification: RawNotification,
    storageNotification: RawNotification,
    correlationId: String? = null,
    persistenceContext: NotificationPersistenceContext
): NotificationPipelineOutcome
```

Pipeline must use:

```kotlin
persistenceContext.rawStorageMode
```

for:

- PendingReview title/text sanitization
- transaction-event notification metadata
- source-link metadata policy
- diagnostic metadata policy if relevant

Do **not** use current settings for already-captured notification persistence.

Minimum alternative:

```text
Build PendingReview from storageNotification fields, not processingNotification fields.
```

But explicit `NotificationPersistenceContext` is cleaner.

## Tests

For each mode:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

Test terminal paths:

1. normal `PROCESSED` purges transient payload.
2. `FILTER_REJECTED` purges transient payload.
3. max attempts final failure purges transient payload.
4. final exception purges transient payload.
5. retryable failure does not purge payload.
6. raw row respects storage mode.
7. pending review respects captured storage mode.

Critical captured-mode test:

```text
Capture row with DO_NOT_STORE.
Change settings to STORE_RAW before worker runs.
Worker creates PendingReview.
Assert PendingReview has no raw title/text/body.
```

## Acceptance

- No terminal intake row keeps plaintext transient payload except retryable rows.
- PendingReview/privacy uses captured mode.
- Terminal cleanup cannot regress status.

---

# PR 2 — Transient payload encryption / explicit policy

## Fixes

- plaintext raw notification text in `notification_intake`
- misleading `payloadMode = TRANSIENT`
- DO_NOT_STORE semantics

## Current problem

For non-raw modes, coordinator stores raw payload in normal DB columns:

```kotlin
title
text
bigText
subText
extrasJson
payloadMode = "TRANSIENT"
```

This is not encrypted and can remain during delay/crash/retry.

## Product policy

Add explicit setting:

```kotlin
enum class NotificationTransientPayloadPolicy {
    ENCRYPTED_UNTIL_PROCESSED,
    DISABLED
}
```

Recommended defaults:

| RawStorageMode | Default transient policy |
|---|---|
| STORE_RAW | not needed |
| STORE_REDACTED | ENCRYPTED_UNTIL_PROCESSED |
| STORE_METADATA_ONLY | ENCRYPTED_UNTIL_PROCESSED |
| DO_NOT_STORE | DISABLED unless explicit opt-in |

## Schema

Add migration for `notification_intake`:

```kotlin
transientPayloadCiphertext: String?
transientPayloadNonce: String?
transientPayloadVersion: Int?
transientPayloadPurgedAt: Long?
```

Optional:

```kotlin
transientPayloadPolicy: String
```

## New model

```kotlin
data class NotificationTransientPayload(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?
)

data class EncryptedNotificationPayload(
    val ciphertext: String,
    val nonce: String,
    val version: Int
)
```

## New service

```kotlin
interface NotificationTransientPayloadCrypto {
    fun encrypt(payload: NotificationTransientPayload): EncryptedNotificationPayload
    fun decrypt(payload: EncryptedNotificationPayload): NotificationTransientPayload
}
```

Use existing app crypto/keystore if available. If not, use Android Keystore + AES-GCM.

## Coordinator rules

| Mode | Visible intake fields | Encrypted transient payload |
|---|---|---|
| STORE_RAW | raw fields allowed | optional |
| STORE_REDACTED | redacted/null visible fields | encrypted raw payload |
| STORE_METADATA_ONLY | null visible fields | encrypted raw payload |
| DO_NOT_STORE + opt-in | null visible fields | encrypted raw payload |
| DO_NOT_STORE + disabled | no durable raw payload |

For `DO_NOT_STORE + disabled`, choose one explicit behavior:

### Option A — no durable recovery

Process synchronously/ephemerally and persist sanitized storage only.

### Option B — reject with explicit diagnostic

```text
PAYLOAD_UNAVAILABLE_PRIVACY / TRANSIENT_PAYLOAD_DISABLED
```

Do **not** silently store plaintext raw text.

## Worker rules

Load processing payload:

```kotlin
when {
    row.rawStorageMode == STORE_RAW.name -> visible raw fields
    row.transientPayloadCiphertext != null -> decrypt transient payload
    else -> markTerminalAndPurgeBestEffort(PAYLOAD_UNAVAILABLE_PRIVACY)
}
```

After terminal:

```kotlin
purgeRawPayload(row.id)
purgeTransientPayload(row.id)
```

## Tests

1. `STORE_REDACTED` visible columns contain no raw text; encrypted payload decrypts and processes.
2. `STORE_METADATA_ONLY` visible columns null; encrypted payload decrypts and processes.
3. `DO_NOT_STORE + disabled` stores no raw/encrypted payload.
4. `DO_NOT_STORE + opt-in` stores encrypted only and purges after terminal.
5. App crash/retry leaves ciphertext, not plaintext.
6. Purge clears ciphertext/nonce/version and visible fields.

## Acceptance

- No plaintext raw payload stored for non-raw modes.
- `DO_NOT_STORE` semantics are explicit.
- P2-11 privacy queue issue closed.

---

# PR 3 — Move intake earlier and app-scope handoff

## Fixes

- P1-P1-07 remaining durability gap
- service-scope cancellation before intake insert
- filter-before-intake loss window
- refresh source setup can be included here

## Current flow problem

Service still does a lot before durable insert:

```text
gate
extract
dedupe
filter
second privacy check
settings/app name/extras policy
then intake insert
```

All before insert happens in `serviceScope`.

## Target flow

```text
onNotificationPosted
  -> build safe envelope only
  -> applicationScope.launch
      -> captureCoordinator.handle(envelope, source)
```

Inside coordinator:

```text
1. emit RECEIVED
2. gate
3. extract text
4. build payload/fingerprint
5. insert intake row
6. enqueue worker
```

Move filter to worker:

```text
worker:
  -> decrypt/load payload
  -> filter
  -> FILTER_REJECTED terminal if rejected
  -> process pipeline if accepted
```

## Implementation

1. Add/inject:

```kotlin
@ApplicationScope CoroutineScope
```

2. Introduce:

```kotlin
enum class CaptureSource { LISTENER, REFRESH }
```

3. Replace:

```kotlin
refreshActiveNotifications() -> onNotificationPosted(sbn)
```

with:

```kotlin
enqueueNotificationCapture(sbn, CaptureSource.REFRESH)
```

4. Listener uses:

```kotlin
enqueueNotificationCapture(sbn, CaptureSource.LISTENER)
```

5. Coordinator writes source into intake row and diagnostics.

6. Remove service-side filter before intake.

7. Keep in-memory dedupe as callback suppressor only; durable duplicate remains intake fingerprint.

## Tests

1. Service destroyed after callback still inserts intake row.
2. Filter-rejected notification has intake row with `FILTER_REJECTED`.
3. Refresh diagnostics have source `REFRESH`.
4. Listener diagnostics have source `LISTENER`.
5. WorkManager enqueue failure leaves `RECEIVED` row for recovery.

## Acceptance

- Durable intake starts immediately after gate + extraction.
- P1-P1-07 can be marked fixed except explicit DO_NOT_STORE/transient-disabled caveat.
- Refresh diagnostics are no longer mislabeled.

---

# PR 4 — Public/batch repository privacy sanitizer

## Fixes

- direct repository privacy bypass
- batch path privacy bypass
- P2-11 global completion

## Current problem

Repository still allows:

```kotlin
processAndSave(notification) = processAndSave(notification, notification)
processAndSaveAll(notifications)
```

KDoc warning is not enough.

## Implementation

Create:

```kotlin
data class NotificationPersistencePayload(
    val processingNotification: RawNotification,
    val storageNotification: RawNotification,
    val persistenceContext: NotificationPersistenceContext
)
```

Create mapper:

```kotlin
class NotificationPersistenceMapper @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val storageSanitizer: NotificationStoragePayloadSanitizer
) {
    suspend fun fromRawProcessingNotification(raw: RawNotification): NotificationPersistencePayload
}
```

Change repository:

```kotlin
suspend fun processAndSave(payload: NotificationPersistencePayload): NotificationPipelineOutcome
```

Compatibility wrapper must sanitize:

```kotlin
suspend fun processAndSave(notification: RawNotification): NotificationPipelineOutcome {
    val payload = persistenceMapper.fromRawProcessingNotification(notification)
    return processAndSave(payload)
}
```

Batch:

```kotlin
suspend fun processAndSaveAll(notifications: List<RawNotification>): List<NotificationPipelineOutcome> {
    return notifications.map { processAndSave(it) }
}
```

or add pipeline batch that accepts payloads:

```kotlin
processBatch(payloads: List<NotificationPersistencePayload>)
```

## Tests

For all modes and all entrypoints:

```text
listener/intake
repository single
repository batch
```

Verify:
- parser gets raw processing payload;
- storage row respects mode;
- pending review respects mode;
- diagnostics/events/source links no raw text when disallowed.

## Acceptance

- No public path can persist raw notification content accidentally.

---

# PR 5 — Parser provenance integration

## Fixes

- P2-12

## Current state

`ParseProvenance` models exist, but pipeline still calls:

```kotlin
parseWithAiFallback(...): ParsedTransaction?
```

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

Add parser ID:

```kotlin
interface AppNotificationParser {
    val parserId: String get() = this::class.simpleName ?: "UnknownParser"
}
```

Implement:

```kotlin
suspend fun AppParserRegistry.parseWithProvenance(...): ParseOutcome
```

It records:

```text
specific parser attempted/succeeded
generic parser attempted/succeeded
AI attempted/skipped/succeeded/failed
provider/model/confidence
failure reason
attempt list
```

Pipeline:

```kotlin
val parseOutcome = parserRegistry.parseWithProvenance(...)
val parsed = parseOutcome.parsed
val provenance = parseOutcome.provenance
```

Diagnostics:
- `parserSource`
- `winningParserId`
- `aiStatus`
- `aiProvider`
- `aiModel`
- `confidence`
- `failureReason`

Never persist:
- raw prompt
- raw AI response
- raw notification body

Deprecate wrapper:

```kotlin
@Deprecated("Use parseWithProvenance")
suspend fun parseWithAiFallback(...): ParsedTransaction?
```

## Tests

1. specific parser success.
2. generic parser success.
3. deterministic fail + AI success.
4. AI skipped by policy.
5. AI exception.
6. no duplicate deterministic parse.
7. pipeline diagnostics include safe provenance.

## Acceptance

- P2-12 fixed.

---

# PR 6 — Shared money/currency detector

## Fixes

- P2-10

## Current problem

Pipeline still has:

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

Model:

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

Supported currencies:

```text
EUR, USD, GBP, CHF, PLN, RON, TRY, CAD, AUD, JPY,
SEK, NOK, DKK, HUF, CZK
```

Rules:
- `$` → ambiguous USD/CAD/AUD unless home currency resolves.
- `kr` → ambiguous SEK/NOK/DKK unless home currency resolves.
- no silent EUR fallback.
- bare amounts allowed only with explicit policy and low confidence.
- reject OTP/card tails/dates/exchange rates.

Replace:
- pipeline fallback regex/currency resolution;
- filter money detection.

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
| `$12.00` | null | ambiguous |
| `99 kr` | SEK | SEK inferred |
| `EUR/USD 1.08` | EUR | reject |
| `Card *1234` | EUR | reject |

## Acceptance

- No hardcoded silent USD/EUR fallback remains.
- P2-10 fixed.

---

# PR 7 — Finance filter v2

## Fixes

- P2-09

## Current state

Filter improved, but still boolean-only and lacks robust direction/reason model.

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

Enums:

```kotlin
enum class NotificationFilterReason {
    ALLOW_STRONG_EXPENSE,
    ALLOW_OUTGOING_TRANSFER,
    ALLOW_REVIEWABLE_FINANCIAL_SIGNAL,
    BALANCE_ONLY,
    ACCOUNT_INFO_ONLY,
    CURRENCY_ONLY,
    SECURITY_OR_AUTH,
    PROMOTION,
    INCOMING_ONLY,
    PAYMENT_FAILED_OR_DECLINED,
    NO_AMOUNT,
    NO_TRANSACTION_SIGNAL,
    IGNORED_PACKAGE
}

enum class TransactionDirection {
    DEBIT,
    CREDIT,
    TRANSFER_OUT,
    TRANSFER_IN,
    UNKNOWN
}
```

Keep:

```kotlin
fun shouldCapture(...): Boolean = decide(...).capture
```

## Logic

For finance packages:

1. hard deny:
   - OTP/security/login
   - promo/offer
   - balance/account/statement
   - FX/currency rate
   - declined/failed if product does not want it

2. require:
   - money signal
   - debit/expense/outgoing action

3. reject:
   - incoming transfer
   - salary/deposit
   - refund-only
   - balance-only
   - currency-only

4. allow:
   - card purchase/payment
   - POS/contactless
   - online payment
   - outgoing transfer if policy says reviewable

Diagnostics metadata:
- reason
- confidence
- direction
- hasMoney
- package hash only

## Tests

Allow:
- Revolut purchase
- Google Wallet payment
- Greek bank charge
- SMS/Gmail bank purchase
- outgoing transfer

Reject:
- available balance with amount
- account statement
- FX rate
- OTP/security
- promo
- incoming transfer
- salary/deposit
- card declined
- unknown package with currency only

## Acceptance

- Finance balance/currency-only notifications no longer pass.
- P2-09 fixed.

---

# PR 8 — Remaining lifecycle/provenance cleanup

## 8.1 `RawNotification.isProcessed` batch path

Repository single path now marks processed, but batch still needs it.

Implement:

```kotlin
processAndSaveAll(...).also { outcomes ->
    outcomes.forEach { markProcessedIfNeeded(it) }
}
```

Test:
- batch auto-accepted/review terminal rows set `isProcessed=true`.
- retryable errors do not.

## 8.2 Source-link typed result completeness

Dedupe source-link result exists. Verify all source-link paths:
- auto-accept
- review
- parser-failed review
- pending-review source-link service

If any path still returns opaque `Unit`, convert to:

```kotlin
SourceLinkWriteResult
```

## 8.3 Refresh source

If not done in PR 3, implement:

```kotlin
CaptureSource.REFRESH
CaptureSource.LISTENER
```

## 8.4 Package-policy unavailable

Add:

```kotlin
DiagnosticReasonCode.PACKAGE_POLICY_UNAVAILABLE
```

Do not emit `BLOCKED_PACKAGE` when DAO/cache fails.

## 8.5 Dedupe hash cleanup

Replace:

```kotlin
packageName.hashCode()
notificationKey.hashCode()
```

with SHA-256/HMAC helper.

Remove/deprecate stale:

```kotlin
computeNotificationContentHash()
```

if it returns JVM `hashCode()`.

Review `postTime` in dedupe key:
- in-flight key should likely not require exact postTime;
- completed TTL key may include time bucket if needed.

## 8.6 Messaging fallback cleanup

Remove unknown parcelable:

```kotlin
item.toString()
```

from message extraction fallback.

## 8.7 Location dependency cleanup

Remove unused `ForegroundLocationProvider` from `NotificationProcessingPipeline` constructor/import/tests.

## Acceptance

- Cleanup gaps closed.
- No weak/stale hash helper.
- Refresh diagnostics accurate.
- No dead GPS dependency.

---

# PR 9 — Service decomposition

## Fixes

- P3-13

## Target architecture

```text
NotificationCaptureService
  -> NotificationCaptureCoordinator
      -> CaptureGate
      -> Extractor
      -> Deduper
      -> IntakeCoordinator
      -> Diagnostics
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

Service should only own:
- Android lifecycle callbacks
- foreground start/stop delegation
- listener callback forwarding
- refresh command forwarding

Remove from service:
- raw notification construction
- extras policy
- filter logic
- repository calls
- direct second privacy check
- dedupe map internals
- restart alarm implementation

## Tests

1. service forwards listener callback to coordinator.
2. service forwards refresh to coordinator with `REFRESH`.
3. gate denied -> extractor not called.
4. allowed -> intake coordinator called.
5. foreground/restart controllers tested independently.

## Acceptance

- P3-13 fixed.
- Future Pipeline 1 behavior can be tested outside Android service.

---

# PR 10 — Final validation/docs/tracker

## Required test suite

Run:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If emulator available:

```bash
./gradlew connectedDebugAndroidTest
```

## Golden scenarios

1. Valid bank notification under `STORE_RAW` creates expense/review.
2. Valid bank notification under `STORE_REDACTED` creates expense/review; no raw storage.
3. Valid bank notification under `STORE_METADATA_ONLY` creates expense/review; no raw storage.
4. `DO_NOT_STORE` behavior matches explicit transient policy.
5. Filter rejected terminal purges payload.
6. Final failure purges payload.
7. Retryable failure keeps encrypted payload.
8. Parser provenance is recorded.
9. Non-EUR currency fallback works.
10. Balance-only finance alert rejected.
11. Manual refresh source is `REFRESH`.
12. Service destroyed after capture still leaves intake row/worker recovery.
13. PendingReview uses captured storage mode, not changed current setting.
14. Batch/direct paths respect storage mode.
15. No GPS/location dependency in notification pipeline.

## Final grep checks

```bash
grep -R "parseWithAiFallback(" app/src/main/java
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "contains(\"\\$\") -> \"USD\"" app/src/main/java
grep -R "processAndSave(notification" app/src/main/java
grep -R "ForegroundLocationProvider" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "hashCode().toString" app/src/main/java/com/yourname/expensetracker
grep -R "item?.toString()" app/src/main/java/com/yourname/expensetracker/domain/notification
```

Expected:
- no active pipeline usage of `parseWithAiFallback`;
- no silent EUR/USD fallback;
- no unsafe raw repository overload;
- no notification pipeline GPS dependency;
- no JVM `hashCode()` dedupe keys;
- no unsafe parcelable `toString()` extraction.

## Tracker final statuses

After all PRs:

| Issue | Target |
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
| Refresh source gap | Fixed |
| Package-policy gap | Fixed |
| Dedupe cleanup | Fixed |

---

# Minimum “ready” bar

Pipeline 1 should only be marked clean when all are true:

```text
1. No raw notification title/text/body/extras persist under non-raw modes.
2. Transient processing payload is encrypted or explicitly disabled by policy.
3. Every terminal intake path purges transient payload.
4. PendingReview uses captured rawStorageMode.
5. Intake insert is app-scope/early enough to satisfy durability guarantee.
6. Parser provenance is typed and visible.
7. Currency fallback has no silent EUR/USD default.
8. Finance filter rejects balance/currency-only noise.
9. Public/batch paths cannot bypass sanitizer.
10. Tests are green.
```

---

# Sources used

- Commit `45a49cf`: https://github.com/panospao7/Cost-agregator/commit/45a49cf0a847f010303ff207583d9e2fb777f896
- `NotificationIntakeWorker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45a49cf0a847f010303ff207583d9e2fb777f896/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
- `NotificationIntakeCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45a49cf0a847f010303ff207583d9e2fb777f896/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45a49cf0a847f010303ff207583d9e2fb777f896/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt