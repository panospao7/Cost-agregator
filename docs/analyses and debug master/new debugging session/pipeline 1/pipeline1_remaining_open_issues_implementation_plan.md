# Pipeline 1 remaining open issues — detailed implementation plan

Baseline reviewed: `64e78ef81433756bc457cfa97ef61a63139127e2`

This plan covers the issues that are still open or materially partial after the latest repair PRs.

## Remaining issue list

| Issue | Current status | Theme |
|---|---:|---|
| P1-P1-07 | Open | Durable notification intake runtime not wired |
| P2-09 | Partial / needs verification | Finance filter still broad |
| P2-10 | Partial | Currency fallback still regex/default-based |
| P2-11 | Partial | Public/batch repository paths bypass storage sanitizer |
| P2-12 | Partial | Parser/AI provenance contract missing |
| P3-13 | Partial | Service still owns too much business logic |
| P1-NEW-14 | Open | `RawNotification.isProcessed` still dead |
| P1-NEW-16 | Open | Notification pipeline still reads location/GPS |
| P1-NEW-18 | Partial | Source-link failure diagnosed but no typed result |
| GAP-64-01 | Open | Refresh diagnostics mislabeled as listener events |
| GAP-64-02 | Open | Package-policy unavailable mislabeled as blocked package |
| GAP-64-03/04/05 | Open | Dedupe/hash cleanup and stale hash helper |
| TEST-GAP | Open | Pipeline tests not fully green |

Recommended implementation order:

1. **PR 0 — Stabilize failing tests and tracker**
2. **PR 1 — Durable notification intake runtime**
3. **PR 2 — Repository/public-path privacy sanitizer**
4. **PR 3 — Location policy: remove hidden GPS**
5. **PR 4 — Parser provenance contract**
6. **PR 5 — Money/currency detector**
7. **PR 6 — Finance filter v2**
8. **PR 7 — Source-link typed result**
9. **PR 8 — `RawNotification.isProcessed` policy**
10. **PR 9 — Small diagnostic/dedupe cleanup**
11. **PR 10 — Service decomposition**

---

# PR 0 — Test stabilization and tracker reset

## Goal

Before adding more functionality, make the current repair state testable and honest.

The latest commit message says compile passes, but some pipeline tests still need updates because DAO expectations changed from raw-field dedupe to fingerprint/typed insert behavior.

## Tasks

1. Update stale tests that expect:

```kotlin
rawDao.exists(...)
```

to expect:

```kotlin
rawDao.findIdByDedupeFingerprint(...)
rawDao.existsByDedupeFingerprint(...)
insertOrIgnore(storage.copy(dedupeFingerprint = resolvedFingerprint))
```

2. Add tests for typed insert result:
   - pre-check duplicate;
   - insert conflict duplicate;
   - new insert;
   - null incoming fingerprint gets computed and stored.

3. Update service tests:
   - refresh path calls shared handler/path;
   - same listener/refresh dedupe behavior;
   - diagnostics still emitted.

4. Update tracker statuses:
   - mark schema-only intake as **open**, not fixed;
   - mark parser provenance as **partial**;
   - mark currency fallback as **partial**;
   - mark location as **open**.

## Acceptance criteria

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

must pass before landing the next functional PR.

---

# PR 1 — P1-P1-07: Durable notification intake runtime

## Current problem

`NotificationIntakeEntity` and `NotificationIntakeDao` exist, but the runtime flow still goes:

```text
NotificationCaptureService -> repository.processAndSave() -> pipeline
```

The intended durable flow is not wired:

```text
NotificationCaptureService -> NotificationIntake row -> WorkManager worker -> pipeline
```

So process/service death can still lose accepted notifications.

## Goal

After capture gate allows a notification and text is extracted, create a durable intake row before parser/AI/pipeline work.

## New components

Create:

```text
NotificationIntakeCoordinator
NotificationIntakePayloadMapper
NotificationIntakeWorkEnqueuer
NotificationIntakeWorker
NotificationIntakeProcessor
NotificationIntakeRecoveryScheduler
```

## Runtime flow

### Listener/refresh service path

```text
1. Build safe envelope: package, key, postTime, correlationId, source.
2. Emit RECEIVED.
3. CaptureGate.decide().
4. If denied, emit terminal drop.
5. Extract NotificationTextParts.
6. Read RawStorageMode.
7. Build privacy-compliant intake payload.
8. Insert NotificationIntakeEntity with status RECEIVED.
9. Enqueue unique WorkManager job by intake ID.
10. Return from service.
```

Important: do **not** call `repository.processAndSave()` directly from the service after this PR.

### Worker path

```text
1. Load intake row.
2. Check write barrier / restore mode.
3. Claim row: RECEIVED/FAILED_RETRYABLE -> PROCESSING.
4. Reconstruct processing/storage RawNotification.
5. Run NotificationFilter.
6. If rejected: mark FILTER_REJECTED terminal.
7. Else call repository.processAndSave(...).
8. Map NotificationPipelineOutcome to intake terminal status.
9. Purge raw payload according to privacy policy.
10. Emit diagnostics.
```

## Intake status mapping

| Pipeline result | Intake status |
|---|---|
| `AutoAccepted` | `PROCESSED` |
| `NeedsReview` | `PROCESSED` |
| `Duplicate` | `DROPPED_DUPLICATE` |
| `ParserFailed` final | `FAILED_FINAL` |
| `AutoRejected` | `DROPPED_POLICY` |
| `Dropped` | `DROPPED_POLICY` |
| retryable exception | `FAILED_RETRYABLE` |
| non-retryable exception | `FAILED_FINAL` |
| privacy payload unavailable | `PAYLOAD_UNAVAILABLE_PRIVACY` |

## Privacy payload policy

This is the main design decision.

Recommended:

| RawStorageMode | Intake payload |
|---|---|
| `STORE_RAW` | durable raw payload allowed until terminal, then purge if retention policy says so |
| `STORE_REDACTED` | store redacted payload; optionally transient encrypted raw payload if user allows |
| `STORE_METADATA_ONLY` | metadata + optional encrypted transient raw payload |
| `DO_NOT_STORE` | no raw body persisted; if service dies before processing, mark `PAYLOAD_UNAVAILABLE_PRIVACY` |

If you want full recovery even under `DO_NOT_STORE`, add a separate explicit user setting:

```text
Allow encrypted temporary processing queue
```

Do not silently persist raw notification text under `DO_NOT_STORE`.

## WorkManager enqueue

Use unique work per intake row:

```kotlin
enqueueUniqueWork(
    "notification-intake-$intakeId",
    ExistingWorkPolicy.KEEP,
    request
)
```

## Recovery scheduler

Run recovery on:

- app start;
- listener connected;
- restore completion;
- boot receiver;
- manual debug action.

Recovery tasks:

```text
1. releaseStaleProcessing(now - STALE_PROCESSING_MS)
2. getReadyForProcessing(now)
3. enqueue unique work per row
```

## Tests

1. Listener creates intake row before parser is called.
2. Service destroyed after intake insert still results in worker processing later.
3. Duplicate fingerprint intake insert does not enqueue a second worker.
4. Stale PROCESSING row becomes FAILED_RETRYABLE and is re-enqueued.
5. Restore mode causes retry, not mutation.
6. `DO_NOT_STORE` behavior is explicitly tested.
7. Raw payload is purged after terminal result.

## Acceptance criteria

- Service no longer calls `repository.processAndSave()` directly for listener/refresh capture.
- Intake row exists before parser/AI/pipeline work.
- Worker can resume pending/stale rows.
- P1-P1-07 can be marked fixed, or fixed-with-caveat for `DO_NOT_STORE`.

---

# PR 2 — P2-11: Storage sanitizer for public/batch repository paths

## Current problem

Listener path builds:

```text
processingNotification = raw in-memory text
storageNotification = sanitized according to RawStorageMode
```

But repository still exposes:

```kotlin
processAndSave(notification)
processAndSaveAll(notifications)
```

Those use the same raw object for processing and storage.

## Goal

Every pipeline entrypoint must apply the same raw-storage policy.

## Tasks

1. Create:

```text
NotificationPersistenceMapper
NotificationPersistencePayload
NotificationExtrasPersistencePolicy
```

2. Replace public raw overload behavior.

Option A — safest:

```kotlin
internal suspend fun processAndSaveUnsafeForTestsOnly(...)
```

and make public methods require:

```kotlin
NotificationPersistencePayload(
    processingNotification,
    storageNotification
)
```

Option B — compatible:

```kotlin
processAndSave(notification)
```

loads current privacy settings and creates sanitized storage notification internally.

3. Fix batch path:

```kotlin
processAndSaveAll(notifications)
```

must map every item to processing/storage pair before calling pipeline.

4. Ensure extras JSON builder is never called for:
   - `STORE_REDACTED`,
   - `STORE_METADATA_ONLY`,
   - `DO_NOT_STORE`.

5. Add regression tests for all storage modes across:
   - listener path;
   - public repository path;
   - batch path.

## Acceptance criteria

- `DO_NOT_STORE` persists no raw title/text/body/extras in raw rows, reviews, events, diagnostics.
- Batch path behaves like listener path.
- No public method can accidentally persist raw text by passing one `RawNotification`.

---

# PR 3 — P1-NEW-16: Remove hidden GPS/location read

## Current problem

`NotificationCaptureService` says notification capture does not read location and uses only `DATA_SYNC` foreground service type, but `NotificationProcessingPipeline` still calls:

```kotlin
locationProvider.getLastKnownLocation()
```

## Recommended policy

Notification pipeline should not read location by default.

## Tasks

1. Create:

```kotlin
interface NotificationLocationContextProvider {
    suspend fun getLocationForNotificationPipeline(correlationId: String?): NotificationLocationContext
}
```

2. Default implementation:

```kotlin
NoOpNotificationLocationContextProvider
```

returns:

```text
location = null
decision = DISABLED_FOR_NOTIFICATION_PIPELINE
```

3. Replace direct `ForegroundLocationProvider` injection in `NotificationProcessingPipeline`.

4. If product wants notification-location enrichment later, add a gated implementation that checks:
   - explicit feature setting;
   - `PrivacyGate.DEVICE_GPS_LOCATION`;
   - runtime permission;
   - OS foreground/background location constraints.

5. Update KDoc and docs.

## Tests

1. Notification pipeline does not call `ForegroundLocationProvider` by default.
2. Auto-accept works with null location.
3. Needs-review works with null location.
4. If gated provider is used:
   - privacy denied -> provider not called;
   - permission denied -> provider not called;
   - allowed -> provider called.

## Acceptance criteria

- Notification pipeline no longer directly reads GPS.
- Service documentation matches behavior.
- P1-NEW-16 fixed.

---

# PR 4 — P2-12: Parser provenance contract

## Current problem

Pipeline still calls:

```kotlin
parserRegistry.parseWithAiFallback(...)
```

and only knows whether `ParsedTransaction?` is null.

No typed metadata exists for:
- deterministic parser used;
- generic parser used;
- AI attempted/skipped;
- AI provider/model;
- AI failure reason;
- confidence source.

## Goal

Replace null-only parsing with typed outcome.

## New model

Create:

```kotlin
sealed interface ParseOutcome {
    val parsed: ParsedTransaction?
    val provenance: ParseProvenance
}

data class ParseProvenance(
    val source: ParserSource,
    val winningParserId: String?,
    val deterministicAttempted: Boolean,
    val deterministicSucceeded: Boolean,
    val aiAttempted: Boolean,
    val aiStatus: AiFallbackStatus,
    val aiProvider: String?,
    val aiModel: String?,
    val confidence: Float?,
    val failureReason: ParseFailureReason?,
    val attempts: List<ParserAttempt>
)
```

Enums:

```kotlin
ParserSource:
- SPECIFIC_DETERMINISTIC
- GENERIC_DETERMINISTIC
- AI_FALLBACK
- NONE

AiFallbackStatus:
- NOT_NEEDED
- SKIPPED_POLICY
- SKIPPED_PRIVACY
- UNAVAILABLE
- ATTEMPTED_NO_RESULT
- FAILED_EXCEPTION
- SUCCEEDED
```

## Tasks

1. Add `parserId` to `AppNotificationParser` with default:

```kotlin
val parserId: String get() = this::class.simpleName ?: "UnknownParser"
```

2. Add:

```kotlin
parseWithProvenance(...)
```

to `AppParserRegistry`.

3. Keep old:

```kotlin
parseWithAiFallback(...)
```

as deprecated wrapper temporarily.

4. Update `NotificationProcessingPipeline` to use `parseWithProvenance()`.

5. Store safe provenance in:
   - parse diagnostic events;
   - pending review explanation/metadata;
   - transaction event/source-link metadata if available.

6. Do not store raw AI prompt/response.

## Tests

1. Specific parser success -> source `SPECIFIC_DETERMINISTIC`.
2. Generic parser success -> source `GENERIC_DETERMINISTIC`.
3. Deterministic fail + AI success -> source `AI_FALLBACK`.
4. AI skipped -> `SKIPPED_POLICY`.
5. AI exception -> `FAILED_EXCEPTION`.
6. No duplicate deterministic parse.
7. Diagnostics contain no raw text.

## Acceptance criteria

- Pipeline no longer depends on ambiguous `ParsedTransaction?`.
- Every parse outcome has provenance.
- P2-12 fixed.

---

# PR 5 — P2-10: Shared money/currency detector

## Current problem

Pipeline fallback regex now supports more currencies, but still defaults:

```text
$ -> USD
else -> EUR
```

There is no home-currency provider or ambiguity model.

## Goal

Create one detector used by filter and fallback paths.

## New components

```text
NotificationMoneySignalDetector
CurrencyLexicon
MoneySignal
CurrencyResolution
UserCurrencyProvider
```

## Supported currencies

Start with:

```text
EUR, USD, GBP, CHF, PLN, RON, TRY, CAD, AUD, JPY,
SEK, NOK, DKK, HUF, CZK
```

Support aliases/symbols:

```text
€, $, US$, C$, A$, £, CHF, zł/zl, lei/leu, ₺/TL,
¥, kr, Ft, Kč/Kc
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

## Tasks

1. Parse:
   - prefix currency,
   - suffix currency,
   - decimal comma,
   - thousands separators,
   - symbols and ISO codes.

2. Penalize/reject:
   - OTP codes;
   - card tails;
   - dates;
   - exchange rates;
   - percentages;
   - balance-only contexts.

3. Inject `UserCurrencyProvider`.

4. Replace:
   - `CURRENCY_HINT_REGEX`,
   - `AMOUNT_TOKEN_REGEX`,
   - hardcoded currency `when` blocks,
   - `else -> "EUR"` fallback.

5. Add diagnostic metadata:
   - `currencyResolution`;
   - `currencyConfidence`;
   - `currencyCandidates`;
   - `resolvedCurrency`.

## Tests

Matrix:

| Text | Home currency | Expected |
|---|---:|---|
| `Paid PLN 42.00` | EUR | PLN |
| `42,00 zł` | EUR | PLN |
| `120,50 lei` | EUR | RON |
| `₺75.90` | EUR | TRY |
| `A$12.00` | EUR | AUD |
| `C$12.00` | EUR | CAD |
| `$12.00` | CAD | CAD, inferred |
| `$12.00` | null | ambiguous/default with warning |
| `99 kr` | SEK | SEK, inferred |
| `EUR/USD 1.08` | EUR | reject |
| `Card *1234` | EUR | reject |

## Acceptance criteria

- No silent EUR fallback.
- Ambiguous symbols are explicit.
- Pipeline and filter use same detector.
- P2-10 fixed.

---

# PR 6 — P2-09: Finance filter v2

## Current problem

`NotificationFilter` still allows finance apps when there is a transaction keyword or currency-looking amount. Balance/account/currency-only alerts can still pass.

## Goal

Finance apps should require expense-like or review-worthy transaction signals, not just any amount.

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

```text
ALLOW_STRONG_EXPENSE
ALLOW_OUTGOING_TRANSFER
ALLOW_REVIEWABLE_FINANCIAL_SIGNAL
BALANCE_ONLY
ACCOUNT_INFO_ONLY
CURRENCY_ONLY
SECURITY_OR_AUTH
PROMOTION
INCOMING_ONLY
PAYMENT_FAILED_OR_DECLINED
NO_AMOUNT
NO_TRANSACTION_SIGNAL
IGNORED_PACKAGE
```

## Tasks

1. Convert `NotificationFilter` from object to injectable class, or keep object facade and delegate to detector.

2. Add `NotificationTransactionSignalDetector`.

3. Hard-deny groups:
   - OTP/security/login/password;
   - promo/offers;
   - balance/account summaries;
   - FX rates;
   - failed/declined payments if not wanted.

4. Strong allow groups:
   - paid/spent/purchase/charged/card payment/POS/contactless;
   - Greek/Greeklish equivalents;
   - outgoing transfer if product treats as expense/reviewable.

5. Reject incoming-only:
   - received/credited/deposit/salary/refund.

6. Diagnostics should store only:
   - reason;
   - confidence;
   - hasAmount;
   - direction;
   - package hash.

## Tests

Allow:
- Revolut purchase;
- Google Wallet payment;
- Greek bank charge;
- SMS/Gmail bank purchase;
- outgoing transfer.

Reject:
- balance-only with amount;
- monthly statement;
- FX rate;
- OTP/security code;
- login alert;
- promo/cashback offer;
- incoming transfer;
- salary/deposit;
- payment declined;
- unknown package with currency only.

## Acceptance criteria

- Finance packages no longer pass solely because of currency amount.
- Valid purchases still pass.
- P2-09 fixed or mostly fixed with documented policy caveats.

---

# PR 7 — P1-NEW-18: Typed source-link result

## Current problem

Source-link failures now emit diagnostics, but the helper returns `Unit`. Callers cannot react to failures.

## Goal

All source-link writes return typed results.

## New model

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

2. Replace local `writeNotificationDedupeSourceLink(...)` with typed writer.

3. Emit `SOURCE_LINK_FAILED` diagnostic on failure.

4. Decide policy:
   - user-facing expense/review should usually still commit;
   - source-link failure is non-terminal;
   - optional repair queue for retryable failures.

5. Make fatal review source-link failures explicit:
   - either keep fatal behavior and document it;
   - or switch to non-terminal diagnostic + repair.

## Tests

1. Source link created.
2. Existing link returns `AlreadyExists`.
3. DAO retryable failure returns `Failed(retryable=true)`.
4. Auto-accept still succeeds if source link fails.
5. Failure diagnostic contains no raw notification text.

## Acceptance criteria

- No silent or unknowable source-link failure.
- Caller receives typed result.
- P1-NEW-18 fixed.

---

# PR 8 — P1-NEW-14: `RawNotification.isProcessed` policy

## Current problem

`RawNotification.kt` still says `isProcessed` is never set.

## Recommended policy

After durable intake lands:

```text
NotificationIntake.status is the source of truth.
RawNotification.isProcessed is a legacy coarse flag.
```

## Tasks

Option A — maintain it:

1. Add DAO method:

```kotlin
markProcessed(rawId)
```

2. In `NotificationIntakeProcessor`, after terminal pipeline outcome, mark raw row processed.

3. Do not mark processed for retryable errors.

Option B — deprecate it:

1. Add `@Deprecated` KDoc.
2. Stop exposing it in debug UI as processing truth.
3. Plan migration to remove field later.

Recommended: do both short-term:
- mark it true for terminal raw rows;
- document that intake status is canonical.

## Tests

1. Auto-accepted row becomes processed.
2. Needs-review row becomes processed.
3. Parser-final row becomes processed.
4. Retryable error does not mark processed.
5. Intake status remains source of truth.

## Acceptance criteria

- TODO removed.
- Field is maintained or formally deprecated.
- P1-NEW-14 fixed.

---

# PR 9 — Small diagnostic/dedupe/extraction cleanup

## Goal

Clean up remaining non-blocking gaps from `64e78ef`.

## Tasks

### 1. Refresh diagnostics source

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

Diagnostics should distinguish refresh from listener callback.

### 2. Package-policy unavailable reason

If blocked-package DAO/list is unavailable, emit:

```text
PACKAGE_POLICY_UNAVAILABLE
```

not `BLOCKED_PACKAGE`.

### 3. Dedupe hashes

Replace package/key `String.hashCode()` with SHA-256/HMAC.

Keep at least 128-bit content digest for in-memory key.

### 4. Revisit postTime in dedupe key

Current key includes `postTime`, which prevents dropping identical legitimate transactions but weakens duplicate callback suppression.

Recommended:

```text
in-flight key = packageHash + notificationKeyHash + contentFingerprint
completed TTL key = packageHash + notificationKeyHash + contentFingerprint + optional time bucket
durable duplicate = raw/expense fingerprint
```

### 5. Remove stale helper

Remove or deprecate:

```kotlin
computeNotificationContentHash()
```

if it still returns JVM `hashCode()`.

### 6. MessagingStyle fallback cleanup

In `NotificationTextParts.extract()`, do not use:

```kotlin
item.toString()
```

for unknown parcelables/bundles.

Use `null` instead.

## Tests

1. Refresh event has source `REFRESH`.
2. DAO failure emits `PACKAGE_POLICY_UNAVAILABLE`.
3. Dedupe key does not use JVM `hashCode()`.
4. Unknown message parcelable does not pollute `combinedBody`.
5. Same duplicate callback with changed postTime still handled by intended policy.

## Acceptance criteria

- Minor gaps closed.
- Diagnostics become clearer.
- No stale weak hash helper remains.

---

# PR 10 — P3-13: Service decomposition

## Current problem

`NotificationCaptureService` still owns lifecycle plus business logic.

## Goal

Service becomes Android adapter only.

## Extract components

```text
NotificationCaptureCoordinator
NotificationCaptureDeduper
NotificationPersistenceMapper
NotificationExtrasPersistencePolicy
NotificationEnvelopeFactory
NotificationRefreshCoordinator
NotificationForegroundController
NotificationRestartScheduler
```

## Final architecture

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

After PR 1, coordinator should enqueue intake, not call repository directly.

## Tasks

1. Move capture flow into coordinator:
   - receive `StatusBarNotification`;
   - build envelope;
   - gate;
   - extract;
   - dedupe;
   - intake enqueue;
   - diagnostics.

2. Move foreground notification/channel code to `NotificationForegroundController`.

3. Move restart alarm to `NotificationRestartScheduler`.

4. Move refresh to `NotificationRefreshCoordinator`.

5. Remove direct dependencies from service:
   - repository;
   - privacyGate;
   - blockedPackageDao;
   - NotificationFilter;
   - RawNotification construction;
   - extras JSON builder.

## Tests

1. Gate denied -> extractor not called.
2. Filter rejected -> intake/worker not called if filter remains pre-intake, or intake terminal if filter moved to worker.
3. Allowed -> intake row enqueued.
4. Refresh and listener use same coordinator.
5. Service has only Android lifecycle behavior.

## Acceptance criteria

- Service no longer owns core business logic.
- P3-13 fixed.

---

# Recommended final sequence

Best order:

```text
0. Green tests.
1. Durable intake runtime.
2. Repository/public-path privacy sanitizer.
3. Location policy.
4. Parser provenance.
5. Money detector.
6. Finance filter v2.
7. Source-link typed result.
8. RawNotification.isProcessed policy.
9. Minor cleanup.
10. Service decomposition.
```

Reasoning:

- Durable intake fixes the highest remaining user-loss risk.
- Privacy sanitizer closes remaining raw-storage bypasses.
- Location fix closes the clearest privacy/OS mismatch.
- Parser/currency/filter fixes improve correctness and debugging.
- Service decomposition should happen after behavior is stable.

---

# Validation commands

Run after each PR:

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
grep -R "parseWithAiFallback(" app/src/main/java
grep -R "getLastKnownLocation" app/src/main/java
grep -R "isProcessed is never set" app/src/main/java
grep -R "else -> \"EUR\"" app/src/main/java
grep -R "fullText.contains(\"\\$\") -> \"USD\"" app/src/main/java
grep -R "processNotificationBypassDedupe" app/src/main/java
grep -R "hashCode().toString" app/src/main/java/com/yourname/expensetracker/service
```

Expected final state:

- service does not call repository directly for capture;
- intake worker owns processing;
- parser uses provenance outcome;
- currency fallback has no silent EUR/USD default;
- location is not read by default;
- public/batch paths sanitize storage;
- `isProcessed` TODO gone;
- tests are green.

---

# Sources checked

- Commit `64e78ef`: https://github.com/panospao7/Cost-agregator/commit/64e78ef81433756bc457cfa97ef61a63139127e2
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `NotificationCaptureGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureGate.kt
- `RawNotification.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt
- `AppParserRegistry.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt
- `NotificationFilter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt
- `NotificationIntakeEntity.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/data/database/entity/NotificationIntakeEntity.kt
- `NotificationIntakeDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/64e78ef81433756bc457cfa97ef61a63139127e2/app/src/main/java/com/yourname/expensetracker/data/database/dao/NotificationIntakeDao.kt