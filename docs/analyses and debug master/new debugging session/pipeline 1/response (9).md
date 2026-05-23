Here is the careful final implementation plan for the remaining Pipeline 1 issues.

<pipeline1_final_clear_implementation_plan_after_16c8833.md>
# Pipeline 1 final implementation plan after `16c8833`

Baseline: `16c8833fb44cf05f9f21858cb710ca0904e62342`

Current state:

```text
Pipeline 1 is close, but not clean yet.
The main remaining blockers are:
1. Worker filters encrypted transient rows before decrypting.
2. Transient crypto uses a static hardcoded key.
3. Worker/service do not consistently pass captured persistence context.
4. Historical plaintext transient rows need cleanup/repair.
5. Currency fallback still guesses USD/EUR.
6. Durable intake is still not as early/app-scope as the strongest guarantee requires.
```

Recommended order:

1. **PR 1 — Worker transient payload correctness hotfix**
2. **PR 2 — Real Keystore-backed transient payload crypto**
3. **PR 3 — Historical transient payload repair**
4. **PR 4 — Captured persistence context everywhere**
5. **PR 5 — Shared money/currency detector**
6. **PR 6 — Intake durability/app-scope handoff**
7. **PR 7 — Final finance filter/dedupe/diagnostic cleanup**
8. **PR 8 — Final regression suite + tracker update**

---

# PR 1 — Worker transient payload correctness hotfix

## Fixes

- `STORE_REDACTED` / `STORE_METADATA_ONLY` worker rows being filter-rejected before decrypt.
- P2-11 functional regression from encrypted transient payload change.

## Current bug

Coordinator now stores visible fields as null for non-raw modes:

```kotlin
title = null
text = null
bigText = null
subText = null
```

and stores the real text in encrypted transient payload.

But worker currently runs filter before decrypting:

```kotlin
NotificationFilter.shouldCapture(
    current.packageName,
    current.title,
    current.text,
    current.bigText
)
```

For non-raw rows, this filters empty text and returns false.

## Target worker order

Worker must do:

```text
1. Load intake row.
2. Check write barrier.
3. Claim row.
4. Load processing payload:
   - raw visible fields for STORE_RAW
   - decrypted transient payload for STORE_REDACTED / STORE_METADATA_ONLY
   - no payload for DO_NOT_STORE durable path
5. Run NotificationFilter on processing payload.
6. If rejected, mark FILTER_REJECTED and purge transient payload.
7. Build processing RawNotification.
8. Build sanitized storage RawNotification.
9. Call repository/pipeline.
10. Mark terminal and purge.
```

## Implementation

Create helper:

```kotlin
private data class IntakeProcessingPayload(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?
)
```

Add:

```kotlin
private suspend fun loadProcessingPayload(
    row: NotificationIntakeEntity,
    rawStorageMode: RawStorageMode
): IntakeProcessingPayload?
```

Implementation:

```kotlin
private suspend fun loadProcessingPayload(
    row: NotificationIntakeEntity,
    rawStorageMode: RawStorageMode
): IntakeProcessingPayload? {
    return when {
        rawStorageMode == RawStorageMode.STORE_RAW -> {
            IntakeProcessingPayload(
                title = row.title,
                text = row.text,
                bigText = row.bigText,
                subText = row.subText,
                extrasJson = row.extrasJson
            )
        }

        row.transientPayloadCiphertext != null &&
        row.transientPayloadNonce != null &&
        row.transientPayloadVersion != null -> {
            val decrypted = transientPayloadCrypto.decrypt(
                ciphertext = row.transientPayloadCiphertext,
                nonce = row.transientPayloadNonce,
                version = row.transientPayloadVersion
            )

            IntakeProcessingPayload(
                title = decrypted.title,
                text = decrypted.text,
                bigText = decrypted.bigText,
                subText = decrypted.subText,
                extrasJson = decrypted.extrasJson
            )
        }

        else -> null
    }
}
```

Then worker flow:

```kotlin
val rawStorageMode = parseRawStorageMode(current.rawStorageMode)

val payload = loadProcessingPayload(current, rawStorageMode)

if (payload == null) {
    intakeDao.markTerminal(
        id = current.id,
        status = NotificationIntakeStatus.PAYLOAD_UNAVAILABLE_PRIVACY.name,
        ...
    )
    purgePayloadBestEffort(current, now)
    return Result.success()
}

val filterDecision = NotificationFilter.decide(
    packageName = current.packageName,
    title = payload.title,
    text = payload.text,
    bigText = payload.bigText
)

if (!filterDecision.capture) {
    intakeDao.markTerminal(
        id = current.id,
        status = NotificationIntakeStatus.FILTER_REJECTED.name,
        ...
    )
    purgePayloadBestEffort(current, now)
    return Result.success()
}
```

Then build `processingNotification` from `payload`, not from `current.title`.

## Tests

Add tests for both:

```text
STORE_REDACTED
STORE_METADATA_ONLY
```

Scenario:

```text
Given visible intake title/text/body are null
And encrypted transient payload contains "Paid €12.30 at Lidl"
When worker runs
Then filter sees decrypted payload
And worker does not mark FILTER_REJECTED
And repository/pipeline is called
And terminal outcome is PROCESSED or NEEDS_REVIEW
And transient payload is purged
And raw_notifications does not contain raw text
```

Also test:

```text
missing ciphertext -> PAYLOAD_UNAVAILABLE_PRIVACY + purge
bad ciphertext -> FAILED_RETRYABLE or FAILED_FINAL based on classifier
```

## Acceptance

- Non-raw durable intake rows process correctly.
- Filter always uses decrypted/raw processing payload.
- This PR is the first required hotfix.

---

# PR 2 — Real Keystore-backed transient payload crypto

## Fixes

- hardcoded static AES key in source.
- transient encryption not production-safe.

## Current problem

`NotificationTransientPayloadCrypto` uses a static byte array key.

That is not real encryption. Anyone with APK/source can recover payloads.

## Target

Use Android Keystore-backed AES-GCM key.

## Implementation

Create:

```kotlin
interface NotificationTransientPayloadKeyProvider {
    fun getOrCreateSecretKey(version: Int = CURRENT_VERSION): SecretKey
}
```

Implementation:

```kotlin
@Singleton
class AndroidKeystoreNotificationTransientPayloadKeyProvider @Inject constructor()
    : NotificationTransientPayloadKeyProvider {

    override fun getOrCreateSecretKey(version: Int): SecretKey {
        val alias = "notification_transient_payload_v$version"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
```

Crypto:

```kotlin
class NotificationTransientPayloadCrypto @Inject constructor(
    private val keyProvider: NotificationTransientPayloadKeyProvider,
    private val json: Json
) {
    fun encrypt(payload: NotificationTransientPayload): EncryptedNotificationPayload {
        val key = keyProvider.getOrCreateSecretKey(CURRENT_VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val nonce = cipher.iv
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)

        return EncryptedNotificationPayload(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            version = CURRENT_VERSION
        )
    }

    fun decrypt(ciphertext: String, nonce: String, version: Int): NotificationTransientPayload {
        val key = keyProvider.getOrCreateSecretKey(version)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val spec = GCMParameterSpec(128, Base64.decode(nonce, Base64.NO_WRAP))
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return json.decodeFromString(String(plaintext, Charsets.UTF_8))
    }
}
```

## Testing strategy

Unit tests can use fake key provider:

```kotlin
class FakeNotificationTransientPayloadKeyProvider : NotificationTransientPayloadKeyProvider {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    override fun getOrCreateSecretKey(version: Int): SecretKey = key
}
```

Instrumentation/Robolectric if available for Android Keystore.

## Tests

1. Same plaintext encrypted twice gives different ciphertext.
2. Decrypt roundtrip returns original payload.
3. Wrong nonce fails.
4. Wrong key fails.
5. Static key is removed from source.
6. Version maps to key alias.

## Acceptance

- No hardcoded static encryption key.
- AES-GCM uses randomized nonce.
- Transient payload encryption is production-safe.

---

# PR 3 — Historical transient payload repair

## Fixes

- users upgrading from schema 133/previous transient implementation may still have plaintext visible transient rows.

## Problem

Migration 133→134 only adds encrypted fields. It does not clean old rows:

```text
payloadMode = TRANSIENT
title/text/bigText/extrasJson contain plaintext
transientPayloadCiphertext = null
```

## Target

Repair old rows safely.

## Add repair worker/service

```kotlin
class NotificationIntakePayloadRepairer @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val crypto: NotificationTransientPayloadCrypto,
    private val timeProvider: TimeProvider
) {
    suspend fun repairLegacyPlaintextTransientRows(limit: Int = 100)
}
```

DAO queries:

```kotlin
@Query("""
    SELECT * FROM notification_intake
    WHERE payloadMode = 'TRANSIENT'
      AND transientPayloadCiphertext IS NULL
      AND rawPayloadPurgedAt IS NULL
    LIMIT :limit
""")
suspend fun getLegacyPlaintextTransientRows(limit: Int): List<NotificationIntakeEntity>
```

Repair logic:

```text
If row is terminal:
    purge visible raw fields.
If row is non-terminal and has visible payload:
    encrypt visible payload into transientPayloadCiphertext/nonce/version.
    null visible fields.
If row is non-terminal but has no visible payload:
    mark PAYLOAD_UNAVAILABLE_PRIVACY or leave for worker to handle.
```

DAO updates:

```kotlin
@Query("""
UPDATE notification_intake
SET transientPayloadCiphertext = :ciphertext,
    transientPayloadNonce = :nonce,
    transientPayloadVersion = :version,
    title = NULL,
    text = NULL,
    bigText = NULL,
    subText = NULL,
    extrasJson = NULL,
    updatedAt = :nowMs
WHERE id = :id
""")
suspend fun replaceVisiblePayloadWithEncrypted(...)
```

```kotlin
@Query("""
UPDATE notification_intake
SET title = NULL,
    text = NULL,
    bigText = NULL,
    subText = NULL,
    extrasJson = NULL,
    rawPayloadPurgedAt = :nowMs,
    transientPayloadPurgedAt = COALESCE(transientPayloadPurgedAt, :nowMs),
    updatedAt = :nowMs
WHERE id = :id
""")
suspend fun purgeVisiblePayload(...)
```

Run repair on:

- app start;
- after DB open/migration;
- listener connected;
- before recovery scheduler enqueues pending rows.

## Tests

1. Terminal legacy transient row with plaintext -> visible fields purged.
2. Non-terminal legacy transient row with plaintext -> encrypted payload created, visible fields null.
3. Worker can process repaired non-terminal row.
4. Repair is idempotent.
5. Repair does not purge retryable rows without first encrypting.

## Acceptance

- No old plaintext transient rows remain unhandled.
- Upgrade path is privacy-safe.

---

# PR 4 — Captured persistence context everywhere

## Fixes

- PendingReview sanitization can still use current settings if context is not passed.
- Worker currently does not pass context to repository/pipeline.
- Synchronous DO_NOT_STORE path needs context too.

## Current issue

Pipeline supports context, but worker call still looks like:

```kotlin
repository.processAndSave(processingNotification, storageNotification, correlationId)
```

So pipeline may fall back to current settings.

## Implementation

Ensure repository overload exists:

```kotlin
suspend fun processAndSave(
    processingNotification: RawNotification,
    storageNotification: RawNotification,
    correlationId: String? = null,
    persistenceContext: NotificationPersistenceContext
): NotificationPipelineOutcome
```

Worker:

```kotlin
val rawMode = RawStorageMode.valueOf(current.rawStorageMode)

val context = NotificationPersistenceContext(
    rawStorageMode = rawMode,
    payloadMode = current.payloadMode,
    source = current.source
)

val outcome = repository.processAndSave(
    processingNotification = processingNotification,
    storageNotification = storageNotification,
    correlationId = current.correlationId,
    persistenceContext = context
)
```

Service synchronous DO_NOT_STORE path:

```kotlin
val context = NotificationPersistenceContext(
    rawStorageMode = settings.rawNotificationStorageMode,
    payloadMode = "EPHEMERAL",
    source = source.name.lowercase()
)

repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId,
    persistenceContext = context
)
```

Pipeline:

- remove fallback to current settings where possible;
- `sanitizePendingReviewText()` must require a mode;
- if context missing, fail closed or use `storageNotification` fields.

Recommended:

```kotlin
private fun sanitizePendingReviewText(
    text: String?,
    rawStorageMode: RawStorageMode
): String?
```

Do not keep:

```kotlin
privacySettingsRepository.getSettings().rawNotificationStorageMode
```

inside pipeline pending-review construction.

## Tests

Critical test:

```text
1. Create intake row with rawStorageMode = STORE_METADATA_ONLY.
2. Current settings now return STORE_RAW.
3. Worker creates PendingReview.
4. PendingReview contains no raw title/text/body.
```

Also:

```text
DO_NOT_STORE synchronous path creates sanitized review/raw row.
STORE_RAW captured row can store raw.
STORE_REDACTED captured row stores redacted.
```

## Acceptance

- Captured mode controls all persistence for that notification.
- Current settings changes cannot retroactively loosen privacy.

---

# PR 5 — P2-10 shared money/currency detector

## Fixes

- `$ -> USD`
- fallback/default currency guessing
- no ambiguity model
- filter/pipeline amount-detection mismatch

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

Resolution:

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

## Detector behavior

Support:

```text
€12.30
12,30 EUR
PLN 42.00
42,00 zł
120,50 lei
₺75.90
A$12.00
C$12.00
99 kr
JPY 1200
```

Ambiguous:

```text
$ => USD/CAD/AUD
kr => SEK/NOK/DKK
```

If home currency resolves ambiguity:

```text
$ + CAD home => CAD
kr + SEK home => SEK
```

If unresolved:

```text
return ambiguous MoneySignal; do not silently choose USD/EUR.
```

Reject/penalize:

```text
OTP
card tails
dates/times
percentages
FX rates
order IDs
```

## Pipeline integration

Replace local fallback in:

```kotlin
detectOversizedAmountCandidate(...)
detectTransactionSignalCandidate(...)
```

Use:

```kotlin
val homeCurrency = userCurrencyProvider.getHomeCurrency()
val signal = moneySignalDetector.bestTransactionAmount(fullText, homeCurrency, ...)
```

If ambiguous unresolved:

- create low-confidence review with explicit `CurrencyResolution.AMBIGUOUS_UNRESOLVED`; or
- return null.

Do not silently assign `EUR` or `USD`.

## Filter integration

Use detector for:

```kotlin
hasMoneySignal
moneySignals
filterDecision.moneySignals
```

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
| `$12.00` | null | ambiguous unresolved |
| `99 kr` | SEK | SEK inferred |
| `EUR/USD 1.08` | EUR | reject |
| `Card *1234` | EUR | reject |

## Acceptance

- No hardcoded `$ -> USD`.
- No silent `else -> EUR`.
- P2-10 fixed.

---

# PR 6 — Intake durability/app-scope handoff

## Fixes

- P1-P1-07 still partial.
- Intake inserted late after service-side filter.
- Service-scope cancellation before intake insert.

## Current state

Service does before intake:

```text
gate
extract
dedupe
filter
privacy second check
settings/appName/extras
```

inside `serviceScope`.

## Target

Move durable handoff earlier:

```text
NotificationCaptureService
  -> appScope.launch
      -> captureCoordinator.handle(sbn, source)
```

Coordinator:

```text
1. emit RECEIVED
2. gate
3. extract text
4. build encrypted/raw intake payload
5. insert intake row
6. enqueue worker
```

Worker:

```text
1. load/decrypt payload
2. filter
3. FILTER_REJECTED terminal if needed
4. pipeline
```

## Implementation

1. Add/inject:

```kotlin
@ApplicationScope CoroutineScope
```

2. Create or complete:

```kotlin
NotificationCaptureCoordinator
```

3. Service method:

```kotlin
private fun captureNotification(
    sbn: StatusBarNotification,
    source: CaptureSource
) {
    applicationScope.launch {
        captureCoordinator.handle(sbn, source)
    }
}
```

4. Move filter out of service and into worker only.

5. Keep in-memory dedupe as optional callback spam suppressor, but do not let it be the source of durability.

6. Ensure filter-rejected rows are intake terminal rows:

```kotlin
NotificationIntakeStatus.FILTER_REJECTED
```

7. Include source in diagnostics:

```kotlin
metadata["captureSource"] = source.name
stage = if (source == REFRESH) "refresh" else "listener"
```

## Tests

1. service destroyed after callback still inserts intake row.
2. filter-rejected notification has durable intake row.
3. WorkManager enqueue failure leaves RECEIVED row for recovery.
4. recovery scheduler enqueues pending rows.
5. refresh diagnostics source = REFRESH.

## Acceptance

- Durable intake guarantee is clear and test-backed.
- P1-P1-07 can be marked fixed with documented DO_NOT_STORE synchronous caveat.

---

# PR 7 — Finance filter completion

## Fixes

- P2-09 remaining shallow reasons/direction.
- Finance filter still heuristic.

## Tasks

1. Use `NotificationMoneySignalDetector`.

2. Populate `NotificationFilterDecision` fully:

```kotlin
capture
reason
confidence
direction
moneySignals
```

3. Use precise reasons:

```text
BALANCE_ONLY
INCOMING_ONLY
PROMOTION
CURRENCY_ONLY
PAYMENT_FAILED_OR_DECLINED
ALLOW_STRONG_EXPENSE
ALLOW_OUTGOING_TRANSFER
```

4. Direction:

```text
DEBIT
CREDIT
TRANSFER_OUT
TRANSFER_IN
UNKNOWN
```

5. Logic order:

```text
ignored package
security/auth
promo
balance/account
FX/rate
incoming-only
failed/declined
no amount
strong expense
outgoing transfer
ambiguous reviewable signal
reject
```

## Tests

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

Allow:
- card purchase
- POS/contactless
- online payment
- Greek bank charge
- SMS/Gmail bank purchase
- outgoing transfer

## Acceptance

- Finance filter is explainable.
- Balance/currency-only noise rejected.
- P2-09 fixed.

---

# PR 8 — Final cleanup and regression suite

## Cleanup tasks

### 1. Deprecate old parser API harder

After pipeline migration:

```kotlin
@Deprecated("Use parseWithProvenance", level = DeprecationLevel.ERROR)
```

or make private/internal.

### 2. Intake hash cleanup

Replace:

```kotlin
notificationKey.hashCode().toString(36)
```

with:

```kotlin
sha256(notificationKey).take(32)
```

### 3. Remove dead location dependency

Remove unused `ForegroundLocationProvider` from notification pipeline constructor/tests.

### 4. Remove stale weak hash helper

Remove deprecated:

```kotlin
computeNotificationContentHash()
```

if no longer needed.

### 5. Service decomposition

At least extract:

```kotlin
NotificationCaptureCoordinator
NotificationCaptureDeduper
NotificationEnvelopeFactory
NotificationForegroundController
NotificationRefreshCoordinator
```

## Golden regression tests

1. `STORE_RAW` worker path processes and stores raw allowed fields.
2. `STORE_REDACTED` worker path decrypts before filter, processes, stores redacted only.
3. `STORE_METADATA_ONLY` worker path decrypts before filter, processes, stores null body.
4. `DO_NOT_STORE` synchronous path processes ephemerally and stores no raw body.
5. Retryable worker failure keeps encrypted payload.
6. Terminal worker paths purge encrypted payload.
7. Captured mode beats current settings.
8. Batch/direct paths sanitize storage.
9. Parser provenance is correct for specific/generic/AI/skipped/failure.
10. Currency detector handles all target currencies.
11. Finance balance-only rejected.
12. Refresh source diagnostics correct.
13. No GPS/location read.
14. Historical plaintext repair works.

## Final commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If possible:

```bash
./gradlew connectedDebugAndroidTest
```

## Final grep checks

```bash
grep -R "STATIC_KEY" app/src/main/java
grep -R "parseWithAiFallback(" app/src/main/java
grep -R "contains(\"\\$\") -> \"USD\"" app/src/main/java
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "hashCode().toString" app/src/main/java/com/yourname/expensetracker/service
grep -R "getLastKnownLocation" app/src/main/java
grep -R "processBatch(notifications" app/src/main/java
```

Expected:

```text
no static crypto key
no pipeline use of parseWithAiFallback
no hardcoded USD/EUR currency fallback
no weak hash for intake keys
no GPS read
no unsafe batch path
```

---

# Minimum ready bar

Pipeline 1 can be marked clean only when:

```text
1. Worker decrypts transient payload before filter.
2. Transient payload encryption uses Android Keystore, not static key.
3. Old plaintext transient rows are repaired.
4. Captured rawStorageMode controls pending reviews and all persistence.
5. Batch/direct paths sanitize storage.
6. Currency fallback has no silent USD/EUR guesses.
7. Parser provenance is truthful.
8. Durable intake guarantee is documented and tested.
9. Tests are green.
```
</pipeline1_final_clear_plan_after_16c8833.md>