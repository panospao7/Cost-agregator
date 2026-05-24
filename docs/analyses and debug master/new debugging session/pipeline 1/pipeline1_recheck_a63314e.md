# Pipeline 1 deep evaluation — commit `a63314e16e59e0743c846e4ecd5407e568722551`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/a63314e16e59e0743c846e4ecd5407e568722551

Key files checked:
- `NotificationIntakeWorker.kt`
- `NotificationTransientPayloadCrypto.kt`
- `NotificationTransientKeyProvider.kt`
- `NotificationIntakeCoordinator.kt`
- `NotificationIntakePayloadRepairer.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- `AppParserRegistry.kt`
- `NotificationCaptureService.kt`
- `NotificationMoneySignalDetector.kt`

---

# Executive verdict

`a63314e` is a **real improvement** and fixes several blockers from `16c8833`:

- Worker now decrypts transient payload **before** filtering.
- Static AES key is gone; crypto now uses Android Keystore-backed keys.
- Worker now passes captured `NotificationPersistenceContext` to repository/pipeline.
- Historical plaintext transient-row repair exists.
- Batch repository path now sanitizes through `processAndSave(it)`.
- Parser provenance is now mostly real.
- Service refresh source is now explicit.
- Intake `notificationKeyHash` now uses SHA-256.

However, Pipeline 1 is **still not fully clean/ready**.

Remaining blockers:

1. **Money detector is not fully wired.** Pipeline still has hardcoded `$ -> USD` branches inside fallback candidate functions.
2. **Historical repair runs after recovery**, which can cause legacy rows to be processed/failed before repair encrypts them.
3. **Decrypt/load payload happens outside the worker try/catch**, so decryption failure can leave rows stuck in `PROCESSING`.
4. **Durable intake is still inserted late inside service-scope flow**, not app-scope/early after extraction.
5. **DO_NOT_STORE synchronous path still does not pass persistence context** into repository/pipeline.
6. Service is still large/multi-responsibility.

So the right status is:

```text
Pipeline 1 is close, but still PARTIAL / NOT READY.
```

---

# What is fixed now

## 1. Worker decrypt-before-filter is fixed

Previously, `STORE_REDACTED` and `STORE_METADATA_ONLY` rows were filtered using null visible fields.

Current worker now does:

```text
if STORE_RAW:
    use visible fields
else if ciphertext/nonce/version exist:
    decrypt transient payload
else:
    PAYLOAD_UNAVAILABLE_PRIVACY
```

Then it runs:

```kotlin
NotificationFilter.shouldCapture(
    current.packageName,
    processingTitle,
    processingText,
    processingBody
)
```

This fixes the encrypted transient row regression.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt

Status:

```text
P2-11 worker transient functional regression: fixed.
```

---

## 2. Static crypto key is removed

`NotificationTransientPayloadCrypto` now depends on:

```kotlin
NotificationTransientKeyProvider
```

and `AndroidKeystoreNotificationTransientKeyProvider` creates AES-GCM keys in `AndroidKeyStore`.

Source:
- Crypto: https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientPayloadCrypto.kt
- Key provider: https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientKeyProvider.kt

Good:

```text
No obvious STATIC_KEY remains.
AES/GCM/NoPadding is used.
Nonce comes from cipher.iv.
```

Caveats:

- Verify Hilt binding for `NotificationTransientKeyProvider` exists. An `@Singleton` implementation alone does not bind the interface unless there is an `@Binds`/`@Provides` module or direct concrete injection.
- Payload serialization uses `"\u0000"` delimiters instead of JSON. This is probably okay for notification text, but JSON would be safer and clearer.
- No AAD is used. Optional improvement: bind ciphertext to `intakeId` or `dedupeFingerprint`.

Status:

```text
Static-key blocker: mostly fixed, pending DI/runtime verification.
```

---

## 3. Captured persistence context is passed from worker

Worker now builds:

```kotlin
NotificationPersistenceContext(
    rawStorageMode = rawMode,
    payloadMode = current.payloadMode,
    source = current.source
)
```

and passes it to:

```kotlin
repository.processAndSave(..., persistenceContext = persistenceContext)
```

This addresses the setting-change-after-capture risk for the worker path.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt

Status:

```text
Captured-mode worker path: fixed.
```

---

## 4. Batch repository path is improved

`NotificationRepository.processAndSaveAll()` now maps:

```kotlin
notifications.map { processAndSave(it) }
```

The single-item path loads settings and builds sanitized storage payload.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

Status:

```text
Public/batch raw-storage bypass: mostly fixed.
```

Caveat:

- This uses **current settings**, which is fine for direct immediate processing.
- Captured delayed intake rows must keep using `NotificationPersistenceContext`, which worker now does.

---

## 5. Parser provenance is mostly fixed

`AppParserRegistry.parseWithProvenance()` now tracks:

- specific parser attempt,
- generic parser attempt,
- AI fallback attempt,
- AI success,
- AI no-result,
- AI exception,
- AI skipped by policy.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt

Status:

```text
P2-12: mostly fixed.
```

Remaining small caveat:

- `parseWithAiFallback()` still exists. It is deprecated, but future code can still call it. Once all internal callers are migrated, consider `DeprecationLevel.ERROR` or making it internal/private.

---

## 6. Historical repair exists

New `NotificationIntakePayloadRepairer`:

- purges terminal legacy plaintext transient rows;
- encrypts non-terminal legacy plaintext rows and clears visible fields.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakePayloadRepairer.kt

Status:

```text
Historical repair exists, but ordering is wrong. See blocker below.
```

---

# Remaining blockers

---

## BLOCKER 1 — Money detector is only partially wired

Severity: **P2 correctness**

A `NotificationMoneySignalDetector` now exists.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/notification/money/NotificationMoneySignalDetector.kt

Pipeline also has:

```kotlin
resolveCurrency(fullText, homeCurrency)
```

which calls:

```kotlin
moneySignalDetector.bestTransactionAmount(fullText, homeCurrency)
```

Good.

But the fallback candidate functions still contain hardcoded currency resolution:

```kotlin
fullText.contains("$") -> "USD"
...
else -> defaultCurrency
```

This appears in both:

- `detectOversizedAmountCandidate(...)`
- `detectTransactionSignalCandidate(...)`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

Why this matters:

```text
resolveCurrency() is only passed as defaultCurrency.
Then candidate functions override it with hardcoded "$" -> USD.
```

So:

| Text | Home currency | Current risk |
|---|---:|---|
| `Paid $12.00` | CAD | still USD |
| `Paid $12.00` | AUD | still USD |
| `Paid 99 kr` | SEK | may not resolve via detector |
| no explicit signal | home/EUR fallback still possible |

### Required fix

Do not pass a string `defaultCurrency` into old functions.

Instead pass the actual `MoneySignal`:

```kotlin
val signal = moneySignalDetector.bestTransactionAmount(fullText, homeCurrency)
```

Then build candidates from that signal:

```kotlin
TransactionSignalCandidate(
    amount = signal.amount,
    currency = signal.currencyCode ?: return null // or ambiguous low-confidence review
)
```

If ambiguous unresolved:

```text
do not silently choose USD/EUR.
Either reject fallback or create low-confidence review with ambiguity metadata.
```

Acceptance:

```text
No `fullText.contains("$") -> "USD"` remains in active pipeline code.
```

Status:

```text
P2-10: still open/partial.
```

---

## BLOCKER 2 — Historical repair runs after recovery

Severity: **P1/P2 migration correctness**

Service currently does on listener connected:

```kotlin
intakeRecoveryScheduler.recoverPending()
intakePayloadRepairer.repairLegacyPlaintextTransientRows()
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

This order is backwards.

Bad sequence:

1. App has legacy non-terminal `TRANSIENT` row with plaintext visible payload and no ciphertext.
2. `recoverPending()` enqueues worker.
3. Worker sees no ciphertext and non-raw mode.
4. Worker marks `PAYLOAD_UNAVAILABLE_PRIVACY` and purges payload.
5. Repair runs after, but row is already terminal/purged.

This can lose recoverable notifications.

### Required fix

Run repair before recovery:

```kotlin
serviceScope.launch {
    intakePayloadRepairer.repairLegacyPlaintextTransientRows()
    intakeRecoveryScheduler.recoverPending()
}
```

Also run repair on app start before any intake recovery worker, not only listener connected.

### Additional repair caveat

Repairer processes only:

```kotlin
getLegacyPlaintextTransientRows(100)
```

once. If there are >100 rows, the rest remain.

Fix:

```kotlin
while (true) {
    val repaired = repairBatch(100)
    if (repaired == 0) break
}
```

Status:

```text
Historical repair: partial until ordering and looping are fixed.
```

---

## BLOCKER 3 — Decryption happens outside worker try/catch

Severity: **P1/P2 reliability**

In `NotificationIntakeWorker`, decrypt/load/filter/build happens before:

```kotlin
return try {
    val outcome = repository.processAndSave(...)
    ...
} catch (...)
```

So if this throws:

```kotlin
crypto.decrypt(...)
```

the exception escapes `doWork()` without:

- marking retryable/final failure,
- purging payload,
- releasing terminal state,
- emitting structured diagnostic.

The row may remain `PROCESSING` until stale recovery.

### Required fix

Wrap the full post-claim processing flow in the worker try/catch:

```kotlin
return try {
    val payload = loadProcessingPayload(...)
    if (payload == null) { mark terminal; purge; return success }

    val filterDecision = NotificationFilter.decide(...)
    if (!filterDecision.capture) { mark terminal; purge; return success }

    val outcome = repository.processAndSave(...)
    ...
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    classify retry/final
}
```

Or add a dedicated catch around decrypt:

```kotlin
catch (e: AEADBadTagException) {
    markFinalFailure("TRANSIENT_PAYLOAD_DECRYPT_FAILED")
    purgePayloadBestEffort(...)
    return Result.failure()
}
```

### Recommended classification

| Failure | Classification |
|---|---|
| Keystore temporarily unavailable | retryable |
| AEAD tag mismatch / corrupt payload | final + purge |
| unknown crypto version | final + purge |
| JSON/parse payload format error | final + purge |

Status:

```text
Worker reliability: partial.
```

---

## BLOCKER 4 — Durable intake still inserted late

Severity: **P1 reliability, depending on guarantee**

Service still performs before durable intake:

- restore/write check,
- capture gate,
- extras/text extraction,
- dedupe,
- filter,
- second privacy check,
- settings lookup,
- app name lookup,
- extras policy.

Then:

```kotlin
withContext(NonCancellable) {
    intakeCoordinator.capture(...)
}
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

If service is destroyed before reaching the `NonCancellable` block, no intake row exists.

If the accepted guarantee is:

```text
durable after service-side filter passes and intakeCoordinator.capture begins
```

then document that.

If the intended guarantee is:

```text
once listener callback is accepted by gate, it becomes durable
```

then still not fixed.

### Required architectural fix

Move orchestration to application scope:

```text
service callback -> appScope capture coordinator
```

And insert intake earlier:

```text
gate -> extract -> intake insert -> worker filter -> pipeline
```

Status:

```text
P1-P1-07: partial unless documented with narrower guarantee.
```

---

# Important non-blocking issues

---

## 1. DO_NOT_STORE synchronous path still lacks explicit persistence context

For `DO_NOT_STORE`, service calls:

```kotlin
processNotification(...)
```

Inside it, repository call appears as:

```kotlin
repository.processAndSave(processingNotification, storageNotification, correlationId)
```

without `persistenceContext`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

Because processing is immediate, this is lower risk than worker delayed mode, but still not ideal.

Fix:

```kotlin
NotificationPersistenceContext(
    rawStorageMode = RawStorageMode.DO_NOT_STORE,
    payloadMode = "EPHEMERAL",
    source = source.name.lowercase()
)
```

Pass to repository/pipeline.

---

## 2. Crypto payload serialization uses NUL delimiter

Current crypto serializes fields with:

```kotlin
title + "\u0000" + text + "\u0000" + ...
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/a63314e16e59e0743c846e4ecd5407e568722551/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientPayloadCrypto.kt

This is fragile if notification text contains NUL.

Better:

```kotlin
Json.encodeToString(NotificationTransientPayload.serializer(), payload)
```

Not a readiness blocker, but a cleanup.

---

## 3. Money detector itself is simplistic

Detector exists, but:

- no scoring near transaction keywords;
- no OTP/card-tail/date rejection inside detector;
- no returned ambiguity metadata used by pipeline diagnostics;
- ISO/symbol regex handling may behave oddly for some symbols.

This can be improved after removing the old hardcoded pipeline branches.

---

## 4. Finance filter is still heuristic

`NotificationFilter` is much better than before, but still keyword-based. If tests cover the desired examples, it may be acceptable for Pipeline 1.

---

## 5. Service decomposition still partial

`NotificationCaptureService` still owns much of the pipeline orchestration.

This is not a blocker for privacy/correctness if tests are good, but P3-13 remains partial.

---

# Regression status after `a63314e`

## Fixed regressions

| Regression | Status |
|---|---:|
| Worker filters encrypted rows before decrypt | ✅ Fixed |
| Static AES key | ✅ Mostly fixed |
| Worker missing persistence context | ✅ Fixed |
| Batch raw-storage bypass | ✅ Mostly fixed |
| Refresh source race | ✅ Fixed |
| Intake notificationKeyHash weak hash | ✅ Fixed |

## Still open / new concerns

| Concern | Status |
|---|---:|
| Money detector not truly replacing hardcoded fallback | ❌ Open |
| Historical repair order wrong | ❌ Open |
| Decrypt exceptions outside worker failure handling | ❌ Open |
| Durable intake inserted late | ⚠ Partial |
| DO_NOT_STORE sync path missing context | ⚠ Minor |
| Service decomposition | ⚠ Partial |

---

# Issue status table

| Issue | Status at `a63314e` | Notes |
|---|---:|---|
| P1-P1-01 | ✅ Fixed | Outcome propagation OK. |
| P1-P1-02 | ✅ Mostly fixed | Safe diagnostics mostly OK. |
| P1-P1-03 | ✅ Mostly fixed | Messaging extraction OK. |
| P1-P1-05 | ✅ Mostly fixed | Gate OK. |
| P1-P1-06 | ✅ Fixed | Known barriers OK. |
| P1-P1-07 | ⚠ Partial | Intake runtime exists; still late unless guarantee narrowed. |
| P2-08 | ✅ Mostly fixed | Refresh source explicit now. |
| P2-09 | ✅/⚠ Mostly fixed | Filter improved; still heuristic. |
| P2-10 | ❌ Open/partial | Hardcoded `$ -> USD` still active in fallback. |
| P2-11 | ⚠ Mostly fixed | Encryption/context/batch improved; repair order + decrypt catch remain. |
| P2-12 | ✅ Mostly fixed | Provenance mostly truthful. |
| P3-13 | ⚠ Partial | Service still large. |
| P1-NEW-14 | ✅ Mostly fixed | Direct/batch processed marking improved. |
| P1-NEW-16 | ✅ Mostly fixed | GPS read gone. |
| P1-NEW-18 | ✅ Mostly fixed | Typed source-link result exists. |

---

# Is Pipeline 1 clean and ready?

Not yet.

It is **very close**, but I would not mark it clean until these are fixed:

## Must fix before ready

1. Remove/replace hardcoded fallback currency branches inside:
   - `detectOversizedAmountCandidate`
   - `detectTransactionSignalCandidate`

2. Run historical transient repair before recovery and loop until complete.

3. Catch/classify decrypt/load-processing-payload failures inside worker.

## Strongly recommended before ready

4. Pass persistence context through DO_NOT_STORE synchronous path.

5. Document the exact durability guarantee:
   - full listener callback durability, or
   - durability only after intake insertion.

6. Add tests proving all raw-storage modes work.

---

# Required tests before final sign-off

## Transient worker tests

1. `STORE_REDACTED`: visible fields null, ciphertext exists, worker decrypts, filter passes, review/expense created, storage redacted.
2. `STORE_METADATA_ONLY`: visible fields null, ciphertext exists, worker decrypts, storage null.
3. Bad ciphertext: worker marks final failure or retryable according to classifier, does not leave row stuck.
4. Retryable DB error: ciphertext remains.
5. Terminal outcome: ciphertext and visible payload purged.

## Historical repair tests

1. Terminal legacy transient plaintext row is purged.
2. Non-terminal legacy transient plaintext row is encrypted and visible fields cleared.
3. Repair runs before recovery.
4. More than 100 rows are repaired by repeated batches.

## Currency tests

1. `$12.00`, home CAD -> CAD, not USD.
2. `$12.00`, home AUD -> AUD, not USD.
3. `$12.00`, no home -> ambiguous or low-confidence, not silent USD.
4. `99 kr`, home SEK -> SEK.
5. PLN/RON/TRY/CAD/AUD/JPY/CHF/SEK/NOK/DKK/HUF/CZK all parse.
6. `EUR/USD 1.08` rejected as FX rate.
7. `Card *1234` rejected.

## Captured context tests

1. Worker row captured under `STORE_METADATA_ONLY`, settings later changed to `STORE_RAW`, pending review still sanitized.
2. DO_NOT_STORE synchronous path stores no raw text in raw row/review.

---

# Final recommendation

Do one small final hardening PR before calling Pipeline 1 ready:

```text
PR FINAL-1
- Fix currency fallback to use MoneySignal directly.
- Move repair before recovery and loop repair batches.
- Wrap decrypt/load/filter/build inside worker try/catch.
- Pass context in DO_NOT_STORE synchronous path.
- Add regression tests.
```

After that, if tests are green, Pipeline 1 can likely be marked:

```text
Clean enough / ready, with P3-13 service decomposition as non-blocking architecture cleanup.
```