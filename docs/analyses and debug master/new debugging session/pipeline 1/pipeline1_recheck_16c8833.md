# Pipeline 1 deep evaluation — commit `16c8833fb44cf05f9f21858cb710ca0904e62342`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/16c8833fb44cf05f9f21858cb710ca0904e62342

Key files checked:
- `NotificationIntakeCoordinator.kt`
- `NotificationIntakeWorker.kt`
- `NotificationTransientPayloadCrypto.kt`
- `NotificationIntakeEntity.kt`
- `NotificationIntakeDao.kt`
- `AppDatabase.kt`
- `NotificationRepository.kt`
- `NotificationProcessingPipeline.kt`
- `AppParserRegistry.kt`
- `NotificationFilter.kt`
- `NotificationCaptureService.kt`

---

# Executive verdict

`16c8833` is a meaningful step, but Pipeline 1 is **still not clean / not ready**.

It fixes part of the transient payload problem:

```text
STORE_REDACTED / STORE_METADATA_ONLY no longer store raw text in visible intake columns.
They now store null visible fields + encrypted transient payload.
```

However, there are two major blockers:

1. **New regression:** the worker runs `NotificationFilter` **before decrypting** the transient payload.  
   Since visible fields are now null for non-raw modes, `STORE_REDACTED` and `STORE_METADATA_ONLY` intake rows will be filter-rejected as `NO_AMOUNT` before they are decrypted.

2. **Encryption is not production-safe:** `NotificationTransientPayloadCrypto` uses a hardcoded static AES key in source code.  
   That is not real privacy protection. It is reversible by anyone with the APK/source.

Other important remaining issues:

3. Worker still does not pass captured `rawStorageMode` / `NotificationPersistenceContext` to the pipeline.
4. Currency fallback still hardcodes `$ -> USD` and default/home fallback rather than using a real money detector.
5. Durable intake is still inserted late after service-side gate/extract/filter work.
6. Historical plaintext `TRANSIENT` rows from schema 133 are not purged/encrypted by migration.
7. Finance filter is improved but still heuristic.
8. Service decomposition remains partial.

So the current status is:

```text
Pipeline 1 = improved, but still PARTIAL / NOT READY.
```

---

# What improved

## 1. Encrypted transient columns added

`NotificationIntakeEntity` now includes:

```kotlin
transientPayloadCiphertext: String?
transientPayloadNonce: String?
transientPayloadVersion: Int?
transientPayloadPurgedAt: Long?
```

Migration `133 -> 134` adds these columns.

Source:
- Entity: https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/data/database/entity/NotificationIntakeEntity.kt
- Migration: https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

## 2. Coordinator no longer stores visible raw fields for non-raw durable modes

`NotificationIntakeCoordinator` now does:

```kotlin
val isRaw = rawStorageMode == RawStorageMode.STORE_RAW
payloadMode = if (isRaw) "RAW" else "TRANSIENT"

title = if (isRaw) title else null
text = if (isRaw) text else null
bigText = if (isRaw) combinedBody else null
subText = if (isRaw) subText else null
extrasJson = if (isRaw) extrasJson else null
```

For non-raw modes, it encrypts a `NotificationTransientPayload`.

That fixes the previous visible plaintext intake columns for **new** redacted/metadata rows.

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt

## 3. Worker can decrypt transient payload

Worker has logic:

```kotlin
if (isRaw) {
    processingTitle = current.title
    ...
} else if (current.transientPayloadCiphertext != null ...) {
    val payload = crypto.decrypt(...)
    processingTitle = payload.title
    ...
}
```

Good direction.

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt

## 4. DO_NOT_STORE remains synchronous / ephemeral

Coordinator still returns:

```kotlin
RequiresSynchronousProcessing
```

for `DO_NOT_STORE`, and the service handles it by calling synchronous `processNotification(...)`.

That avoids durable raw/transient intake for `DO_NOT_STORE`.

---

# Blocking regressions / still-open issues

---

## BLOCKER 1 — Worker filters before decrypting transient payload

Severity: **P0/P1 functional regression**

Current worker order:

```kotlin
// Run filter
if (!NotificationFilter.shouldCapture(
    current.packageName,
    current.title,
    current.text,
    current.bigText
)) {
    mark FILTER_REJECTED
    purgePayloadBestEffort(...)
    return Result.success()
}

// Then later decrypt/build processing payload
```

For `STORE_REDACTED` / `STORE_METADATA_ONLY`, coordinator now stores:

```kotlin
title = null
text = null
bigText = null
subText = null
extrasJson = null
```

So the worker filters an empty notification:

```text
packageName = bank app
title/text/body = null/null/null
```

`NotificationFilter` sees no amount and returns false.

Result:

```text
STORE_REDACTED and STORE_METADATA_ONLY notifications are likely FILTER_REJECTED before decryption.
```

This means the encrypted transient payload exists but is never used for filtering/processing.

### Required fix

Move decrypt/build-processing-payload **before** worker filter.

Correct worker flow:

```kotlin
val processingPayload = loadProcessingPayload(current)
// raw visible fields for STORE_RAW
// decrypted transient payload for STORE_REDACTED / STORE_METADATA_ONLY

if (processingPayload == null) {
    mark PAYLOAD_UNAVAILABLE_PRIVACY
    purge
    return success
}

if (!NotificationFilter.shouldCapture(
    current.packageName,
    processingPayload.title,
    processingPayload.text,
    processingPayload.bigText
)) {
    mark FILTER_REJECTED
    purge
    return success
}

val processingNotification = RawNotification(... processingPayload ...)
val storageNotification = buildStorageNotification(...)
repository.processAndSave(...)
```

### Tests to add immediately

For both modes:

```text
STORE_REDACTED
STORE_METADATA_ONLY
```

Use a valid bank notification with amount only in body.

Assert:

1. intake visible fields are null/redacted;
2. encrypted payload exists;
3. worker decrypts before filter;
4. worker does **not** mark `FILTER_REJECTED`;
5. pipeline is called;
6. expense/review is created;
7. raw storage respects mode.

Until this is fixed, Pipeline 1 cannot be ready.

---

## BLOCKER 2 — Encryption uses hardcoded static key

Severity: **P1 privacy/security**

`NotificationTransientPayloadCrypto` contains:

```kotlin
private val STATIC_KEY = byteArrayOf(...)
```

and comments:

```kotlin
// NOTE: In production, derive this key from Android Keystore...
// This static key is a placeholder...
```

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientPayloadCrypto.kt

This is not production-safe encryption.

Anyone with the APK/source can decrypt all transient payloads. It protects against casual DB browsing, but not against reverse engineering or backup extraction with app code available.

### Required fix

Use Android Keystore.

Recommended design:

```kotlin
class NotificationTransientPayloadCrypto @Inject constructor(
    private val keyStoreProvider: NotificationTransientKeyProvider
)
```

Key provider:

```kotlin
private const val KEY_ALIAS = "notification_transient_payload_v1"

fun getOrCreateKey(): SecretKey {
    // AndroidKeyStore
    // AES/GCM/NoPadding
    // PURPOSE_ENCRYPT | PURPOSE_DECRYPT
    // randomized encryption required
}
```

Use:

```kotlin
KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
```

with:

```kotlin
KeyGenParameterSpec.Builder(
    KEY_ALIAS,
    PURPOSE_ENCRYPT or PURPOSE_DECRYPT
)
.setBlockModes(BLOCK_MODE_GCM)
.setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
.setRandomizedEncryptionRequired(true)
```

### Versioning

Keep:

```kotlin
transientPayloadVersion
```

but make it map to key alias/version.

### Tests

- crypto roundtrip;
- random nonce produces different ciphertext for same plaintext;
- decrypt wrong nonce fails;
- static key is gone from source;
- key alias created through provider.

Do not mark transient privacy clean with a hardcoded key.

---

## BLOCKER 3 — Worker still does not pass captured persistence context into pipeline

Severity: **P1/P2 privacy edge case**

Pipeline now supports:

```kotlin
NotificationPersistenceContext?
```

and pending reviews call:

```kotlin
sanitizePendingReviewText(..., persistenceContext?.rawStorageMode)
```

Good.

But worker still calls repository like:

```kotlin
repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = current.correlationId
)
```

No `persistenceContext`.

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt

Therefore, in worker path, pipeline falls back to:

```kotlin
privacySettingsRepository.getSettings().rawNotificationStorageMode
```

inside `sanitizePendingReviewText(...)`.

This reintroduces the setting-change-after-capture bug.

### Bad scenario

1. Notification captured under `STORE_METADATA_ONLY`.
2. Intake row stores `rawStorageMode = STORE_METADATA_ONLY`.
3. User changes setting to `STORE_RAW`.
4. Worker processes row.
5. Pending review sanitization falls back to current `STORE_RAW`.
6. Raw title/text may be persisted in `pending_reviews`.

### Required fix

Worker must pass:

```kotlin
val rawMode = RawStorageMode.valueOf(current.rawStorageMode)

val context = NotificationPersistenceContext(
    rawStorageMode = rawMode,
    payloadMode = current.payloadMode,
    source = current.source
)

repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = current.correlationId,
    persistenceContext = context
)
```

Service synchronous `DO_NOT_STORE` path should also pass a context.

### Tests

1. Capture row under `STORE_METADATA_ONLY`.
2. Change current settings to `STORE_RAW`.
3. Worker creates review.
4. Review notification title/text remain null/sanitized.

This is still a readiness blocker.

---

## BLOCKER 4 — Migration does not clean historical plaintext transient rows

Severity: **P1 privacy for users who installed earlier 133 transient build**

Migration `133 -> 134` only adds columns:

```sql
ALTER TABLE notification_intake ADD COLUMN transientPayloadCiphertext ...
...
```

It does not purge/encrypt existing terminal transient plaintext rows from the previous implementation.

If a user ran the prior version where `payloadMode='TRANSIENT'` stored raw text in visible columns, upgrade to 134 leaves:

```text
notification_intake.title/text/bigText/subText/extrasJson
```

as plaintext.

### Required fix

Add post-migration cleanup for terminal transient rows:

```sql
UPDATE notification_intake
SET title = NULL,
    text = NULL,
    bigText = NULL,
    subText = NULL,
    extrasJson = NULL,
    rawPayloadPurgedAt = COALESCE(rawPayloadPurgedAt, <now-ish>)
WHERE payloadMode = 'TRANSIENT'
  AND terminalAt IS NOT NULL
```

Migration cannot easily use app time, but can use `0` or a documented sentinel. Better: add a startup cleanup worker that uses real `timeProvider.now()`.

For non-terminal old transient rows:

Options:
1. process them using visible payload then purge; or
2. mark `PAYLOAD_UNAVAILABLE_PRIVACY` and purge; or
3. encrypt them in a repair job before worker processing.

Need explicit policy.

### Recommended repair job

On app start after v134:

```kotlin
NotificationIntakePayloadMigrationRepair.run()
```

- terminal transient rows → purge visible payload;
- non-terminal transient rows with visible payload but no ciphertext → encrypt visible payload, null visible fields.

---

## BLOCKER 5 — Currency fallback still hardcodes USD/EUR

Severity: **P2 correctness**

Pipeline still contains:

```kotlin
fullText.contains("$") -> "USD"
...
else -> defaultCurrency
```

and callers pass:

```kotlin
userCurrencyProvider.getHomeCurrency() ?: "EUR"
```

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

This is improved from raw `else -> EUR`, but still not the requested detector:

- `$` defaults to USD unless explicit CAD/AUD marker exists;
- ambiguous `kr` is not resolved with home currency;
- no `MoneySignal`;
- no `CurrencyResolution`;
- no confidence/basis metadata;
- filter and pipeline do not share a detector.

### Required fix

Implement and use:

```kotlin
NotificationMoneySignalDetector
UserCurrencyProvider
MoneySignal
CurrencyResolution
```

Replace both:

```kotlin
detectOversizedAmountCandidate(...)
detectTransactionSignalCandidate(...)
```

No silent `$ -> USD`.

---

# Important non-blocking / partial issues

---

## 1. Durable intake is still late

Service still does before durable insert:

- gate;
- extraction;
- dedupe;
- filter;
- second privacy check;
- settings/app name/extras work.

Only `intakeCoordinator.capture(...)` is inside `NonCancellable`.

The service comment still admits:

```text
P1-P1-07 full durability requires app-scope handoff.
```

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

So P1-P1-07 remains partial if the guarantee is “accepted listener callback cannot be lost.”

If your finalized contract is narrower:

```text
Durability begins after service-side gate/extract/filter reaches intake insertion.
DO_NOT_STORE is synchronous and non-durable.
```

then document that explicitly.

---

## 2. Finance filter is better but still heuristic

`NotificationFilter.decide()` now returns structured reasons and direction is partially populated. Good.

But:
- it still uses regex/keyword logic, not the shared money detector;
- direction can be wrong for mixed transfer text;
- incoming/credit deny is broad;
- finance false positives/negatives still likely.

This is probably acceptable as “mostly fixed” if backed by tests, but not perfect.

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt

---

## 3. Parser provenance is mostly fixed

`parseWithProvenance()` now:
- tracks specific parser;
- tracks generic parser;
- tracks AI attempt/success/no-result/exception;
- marks AI skipped by policy;
- deprecates `parseWithAiFallback`.

This is good.

Minor remaining caveat:
- `parseWithAiFallback()` still exists and can be called by future code.
- Consider `DeprecationLevel.ERROR` after migration.

Source:
https://raw.githubusercontent.com/panospao7/Cost-agregator/16c8833fb44cf05f9f21858cb710ca0904e62342/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt

---

## 4. Batch path appears improved

`NotificationRepository.processAndSaveAll(...)` now maps:

```kotlin
notifications.map { processAndSave(it) }
```

and the single-item path sanitizes storage based on current settings.

This closes the old raw batch bypass for ordinary current-settings use.

Caveat:
- direct/batch path uses current settings because there is no captured context, which is acceptable for direct immediate processing.
- intake worker must still pass captured context separately.

---

## 5. Source-link typed result mostly fixed

`SourceLinkWriteResult` is now used for dedupe source links. Good.

Still worth auditing pending-review source-link service behavior separately, but not a Pipeline 1 blocker unless it swallows failures elsewhere.

---

# New regressions introduced by `16c8833`

## REG-16-01 — encrypted transient rows are filtered before decrypt

This is the critical new regression.

Impact:

```text
STORE_REDACTED / STORE_METADATA_ONLY durable intake rows likely become FILTER_REJECTED.
```

Fix:

```text
decrypt/build processing payload before worker filter.
```

## REG-16-02 — encryption key is hardcoded in source

Impact:

```text
transient payload encryption is not production-grade.
```

Fix:

```text
Android Keystore key provider.
```

---

# Updated issue status

| Issue | Status at `16c8833` | Notes |
|---|---:|---|
| P1-P1-01 | ✅ Fixed | Outcome propagation OK. |
| P1-P1-02 | ✅ Mostly fixed | Diagnostics OK. |
| P1-P1-03 | ✅ Mostly fixed | Messaging extraction OK. |
| P1-P1-05 | ✅ Mostly fixed | Gate OK. |
| P1-P1-06 | ✅ Fixed | Write barriers mostly OK. |
| P1-P1-07 | ⚠ Partial | Intake runtime exists; still late/service-scope. |
| P2-08 | ✅ Mostly fixed | Refresh source mostly fixed. |
| P2-09 | ✅/⚠ Mostly fixed | Structured filter exists; still heuristic. |
| P2-10 | ❌ Open | No real money detector; `$ -> USD` remains. |
| P2-11 | ❌ Partial/regressed | Encryption added, but worker filters before decrypt; hardcoded key; context not passed. |
| P2-12 | ✅ Mostly fixed | Provenance mostly real now. |
| P3-13 | ⚠ Partial | Service still owns orchestration. |
| P1-NEW-14 | ✅ Mostly fixed | Batch marking improved. |
| P1-NEW-16 | ✅ Mostly fixed | GPS read gone. |
| P1-NEW-18 | ✅ Mostly fixed | Typed dedupe source-link result exists. |

---

# Is Pipeline 1 clean and ready?

No.

It is closer, but not ready.

## Must-fix before ready

1. **Move worker filter after decrypt/build processing payload.**
2. **Replace static AES key with Android Keystore.**
3. **Pass `NotificationPersistenceContext` from worker and synchronous service path into repository/pipeline.**
4. **Handle historical plaintext `TRANSIENT` rows from v133.**
5. **Replace currency fallback with real detector or explicitly downgrade P2-10 to open.**

## Strongly recommended

6. Move capture orchestration to app scope / insert intake earlier.
7. Make `parseWithAiFallback()` `DeprecationLevel.ERROR` or internal.
8. Add full RawStorageMode regression tests.
9. Add real tests for encrypted transient payload under `STORE_REDACTED` and `STORE_METADATA_ONLY`.

---

# Specific tests that must pass

## Transient payload worker tests

For both `STORE_REDACTED` and `STORE_METADATA_ONLY`:

```text
given valid bank notification
and visible intake title/text/bigText are null
and encrypted transient payload contains body with amount
when worker runs
then worker decrypts before filtering
and creates expense/review
and purges transient payload
and raw_notifications/pending_reviews do not contain raw body
```

## Crypto tests

```text
same plaintext encrypted twice -> different ciphertext
wrong nonce -> decrypt fails
static key not present
keystore key provider used
```

## Captured mode test

```text
capture under STORE_METADATA_ONLY
change current settings to STORE_RAW
worker creates pending review
review has no raw notification text
```

## Migration repair tests

```text
v133 terminal TRANSIENT row with plaintext visible fields
upgrade/repair
visible fields cleared
```

## Currency tests

```text
$12 with CAD home -> CAD, not USD
$12 unresolved -> ambiguous, not silent USD
99 kr with SEK home -> SEK
PLN/RON/TRY/AUD/CAD/JPY/CHF all detected
```

---

# Recommended next PR

Do a small hotfix PR first:

```text
PR: Worker transient payload correctness
1. Load/decrypt processing payload before filter.
2. Pass NotificationPersistenceContext to repository.
3. Add tests for STORE_REDACTED/METADATA_ONLY worker processing.
```

Then:

```text
PR: Keystore crypto + migration repair
```

Then:

```text
PR: Money detector
```

---

# Final conclusion

`16c8833` solves the visible plaintext intake-column issue for new non-raw rows, but introduces a new functional regression because worker filtering still reads the now-null visible fields.

So Pipeline 1 is:

```text
Improved privacy model,
but currently broken for encrypted transient worker processing.
```

Do **not** mark Pipeline 1 ready until the worker decrypt-before-filter bug, static-key crypto, captured persistence context, and currency fallback are fixed.