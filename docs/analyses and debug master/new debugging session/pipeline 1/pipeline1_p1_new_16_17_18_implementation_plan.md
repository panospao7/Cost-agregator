# Dedicated implementation plan — P1-NEW-16, P1-NEW-17, P1-NEW-18

Target issues:

| ID | Severity | Theme |
|---|---:|---|
| P1-NEW-16 | P1 | Notification service says no location, but pipeline reads GPS/location |
| P1-NEW-17 | P1/P2 | Auto-accept audit path still constructs raw notification title payload |
| P1-NEW-18 | P2 | Source-link failures are swallowed |

Context commit: `e781c226862234ed412914884e98d22165a41a95`

Recommended split:

1. **PR 1 — Notification location policy / remove hidden GPS read**
2. **PR 2 — Safe notification audit payload contract**
3. **PR 3 — Typed source-link write result + diagnostics**
4. **PR 4 — Cross-regression tests + tracker/docs update**

---

# PR 1 — P1-NEW-16: Notification service says no location but pipeline reads GPS

## Current problem

`NotificationCaptureService` documentation/foreground-service assumptions say notification capture does not read location.

But `NotificationProcessingPipeline.buildPreDbContext()` calls something like:

```kotlin
locationProvider.getLastKnownLocation()
```

Risk:

- privacy mismatch;
- possible Android foreground-service type mismatch;
- user does not expect notification parsing to attach GPS context;
- location provider may be privacy-gated internally, but the notification pipeline should not rely on hidden behavior.

## Recommended product decision

Prefer this policy:

```text
Notification pipeline must not read device GPS/location by default.
```

If location enrichment is needed later, it should be explicit:

```text
Only attach location when:
1. user enabled expense-location enrichment;
2. privacy gate allows DEVICE_GPS_LOCATION;
3. runtime permission is granted;
4. Android foreground/background location constraints are satisfied;
5. service/worker context is legally allowed to access location.
```

For this PR, safest fix:

```text
Remove location read from notification pipeline and set location context to null.
```

---

## Files to modify

Primary:

- `NotificationProcessingPipeline.kt`
- `ForegroundLocationProvider.kt` usage sites
- `NotificationCaptureService.kt` KDoc/comments
- possibly DI module if location provider injection becomes unused

New files optional:

```text
domain/notification/NotificationLocationPolicy.kt
domain/notification/NotificationLocationContextProvider.kt
```

Tests:

```text
NotificationProcessingPipelineLocationTest.kt
NotificationLocationPolicyTest.kt
```

---

## Step 1.1 — Introduce explicit policy object

Create:

```kotlin
enum class NotificationLocationPolicyDecision {
    DISABLED_FOR_NOTIFICATION_PIPELINE,
    ALLOWED,
    DENIED_PRIVACY,
    DENIED_PERMISSION,
    DENIED_RUNTIME_CONTEXT
}
```

Create:

```kotlin
interface NotificationLocationContextProvider {
    suspend fun getLocationForNotificationPipeline(
        correlationId: String?
    ): NotificationLocationContext
}
```

```kotlin
data class NotificationLocationContext(
    val latitude: Double?,
    val longitude: Double?,
    val decision: NotificationLocationPolicyDecision
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    companion object {
        val Disabled = NotificationLocationContext(
            latitude = null,
            longitude = null,
            decision = NotificationLocationPolicyDecision.DISABLED_FOR_NOTIFICATION_PIPELINE
        )
    }
}
```

Short-term implementation:

```kotlin
@Singleton
class NoOpNotificationLocationContextProvider @Inject constructor()
    : NotificationLocationContextProvider {
    override suspend fun getLocationForNotificationPipeline(
        correlationId: String?
    ): NotificationLocationContext = NotificationLocationContext.Disabled
}
```

This makes the policy explicit and testable.

---

## Step 1.2 — Remove direct `ForegroundLocationProvider` call from pipeline

In `NotificationProcessingPipeline`, replace direct injection:

```kotlin
private val locationProvider: ForegroundLocationProvider
```

with:

```kotlin
private val notificationLocationContextProvider: NotificationLocationContextProvider
```

In `buildPreDbContext()` replace:

```kotlin
val location = locationProvider.getLastKnownLocation()
```

with:

```kotlin
val locationContext =
    notificationLocationContextProvider.getLocationForNotificationPipeline(correlationId)

val location = if (locationContext.hasLocation) {
    LocationSnapshot(
        latitude = locationContext.latitude!!,
        longitude = locationContext.longitude!!
    )
} else {
    null
}
```

If the current context uses raw `Pair<Double, Double>` or another type, adapt accordingly.

---

## Step 1.3 — Do not emit noisy diagnostics by default

Because the default policy is intentionally disabled, do not emit a warning for every notification.

Emit diagnostic only if:

- location was previously expected;
- location provider failed unexpectedly;
- a non-default “location allowed” implementation denies due to privacy/permission.

Suggested diagnostic metadata:

```text
stage = "location_context"
outcome = SKIPPED
reason = DISABLED_FOR_NOTIFICATION_PIPELINE
```

But keep it non-terminal and possibly debug-only.

---

## Step 1.4 — If keeping location enrichment, implement full gate

If you decide not to remove location, then implement:

```kotlin
class GatedNotificationLocationContextProvider @Inject constructor(
    private val privacyGate: PrivacyGate,
    private val permissionChecker: LocationPermissionChecker,
    private val foregroundLocationProvider: ForegroundLocationProvider,
    private val diagnostics: NotificationDiagnosticEmitter
) : NotificationLocationContextProvider
```

Flow:

```text
1. Check feature setting: expenseLocationEnrichmentEnabled.
2. Check PrivacyGate.DEVICE_GPS_LOCATION.
3. Check ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION.
4. Check Android runtime context allows location access.
5. Only then call foregroundLocationProvider.getLastKnownLocation().
6. If denied/unavailable, return null and emit safe diagnostic.
```

If this path is chosen, also update:

- service KDoc;
- Android manifest foreground service type if required;
- user-facing privacy copy.

But again: recommended fix is no-op provider for notification pipeline.

---

## PR 1 tests

1. Default notification pipeline does not call `ForegroundLocationProvider`.
2. `PreDbContext` location fields are null.
3. Auto-accepted expense creation still works with null location.
4. Pending-review creation still works with null location.
5. If gated implementation is used:
   - privacy denied => provider not called;
   - permission denied => provider not called;
   - allowed => provider called once;
   - unavailable => null location, safe diagnostic.

## PR 1 acceptance criteria

- `NotificationProcessingPipeline` no longer directly depends on `ForegroundLocationProvider`.
- Notification capture no longer secretly reads GPS/location.
- Service documentation matches behavior.
- Tests prove provider is not called by default.
- If location remains enabled, it is explicitly gated and documented.

---

# PR 2 — P1-NEW-17: Safe auto-accept audit payload

## Current problem

Auto-accept path constructs audit JSON containing raw notification title, e.g.:

```kotlin
auditReason = json {
    "notificationTitle": notification.title
}
```

Even if the writer later ignores it, this is still risky:

- raw notification title is materialized into an audit payload;
- future code may persist it;
- violates raw-storage modes like `DO_NOT_STORE` / `METADATA_ONLY`;
- contradicts the “all persistence targets respect RawStorageMode” rule.

## Goal

No auto-accept audit/event/source metadata should contain raw notification title/text/body/extras unless explicitly allowed by the same persistence policy.

Recommended stricter rule:

```text
Transaction audit payloads should never store raw notification title/text/body.
Use source IDs, hashes, parser provenance, amount/currency/merchant, and rawId instead.
```

---

## Files to modify

Primary:

- `NotificationProcessingPipeline.kt`
- transaction event/audit writer call sites
- `PendingReview`/expense audit helper if shared
- `RawContentSanitizer.kt` or new audit sanitizer

New files recommended:

```text
domain/notification/audit/NotificationAuditPayload.kt
domain/notification/audit/NotificationAuditPayloadFactory.kt
```

Tests:

```text
NotificationAuditPayloadFactoryTest.kt
NotificationProcessingPipelineAuditPrivacyTest.kt
```

---

## Step 2.1 — Define safe audit payload model

Create:

```kotlin
data class NotificationAuditPayload(
    val sourceType: String = "NOTIFICATION",
    val rawNotificationId: Long?,
    val sourceLinkId: Long?,
    val packageNameHash: String?,
    val notificationKeyHash: String?,
    val dedupeFingerprintHash: String?,
    val parserSource: String?,
    val parserConfidence: Float?,
    val autoAcceptReason: String?,
    val privacyMode: String
)
```

Allowed fields:

- IDs;
- hashes;
- parser provenance;
- confidence;
- high-level reason codes;
- raw-storage mode;
- source type.

Forbidden fields:

- raw notification title;
- raw text;
- raw bigText/combinedBody;
- raw extras JSON;
- notification key in plain text;
- AI prompt/response.

---

## Step 2.2 — Create audit payload factory

```kotlin
class NotificationAuditPayloadFactory @Inject constructor(
    private val safeHash: SafeHashProvider
) {
    fun forAutoAccept(
        notification: RawNotification,
        rawId: Long?,
        sourceLinkId: Long?,
        parserSource: String?,
        parserConfidence: Float?,
        autoAcceptReason: String?,
        rawStorageMode: RawStorageMode
    ): NotificationAuditPayload {
        return NotificationAuditPayload(
            rawNotificationId = rawId,
            sourceLinkId = sourceLinkId,
            packageNameHash = safeHash.hash(notification.packageName),
            notificationKeyHash = notification.notificationKey?.let(safeHash::hash),
            dedupeFingerprintHash = notification.dedupeFingerprint?.let(safeHash::hash),
            parserSource = parserSource,
            parserConfidence = parserConfidence,
            autoAcceptReason = autoAcceptReason,
            privacyMode = rawStorageMode.name
        )
    }
}
```

If no `SafeHashProvider` exists, use existing app hash utility. Do not introduce unstable/random hashes if diagnostics need correlation.

---

## Step 2.3 — Replace raw audit JSON construction

In auto-accept path, replace:

```kotlin
val auditReason = buildJsonObject {
    put("notificationTitle", notification.title)
    ...
}
```

with:

```kotlin
val auditPayload = notificationAuditPayloadFactory.forAutoAccept(
    notification = notification,
    rawId = rawId,
    sourceLinkId = sourceLinkId,
    parserSource = provenance.source.name,
    parserConfidence = parsed.confidence,
    autoAcceptReason = "AUTO_ACCEPT_CONFIDENCE_THRESHOLD",
    rawStorageMode = rawStorageMode
)
```

Serialize only the safe payload:

```kotlin
val auditReasonJson = auditPayload.toJson()
```

Or better, if transaction events accept structured metadata, store fields there.

---

## Step 2.4 — Apply policy to all transaction events from notification pipeline

Search:

```bash
grep -R "notification.title" app/src/main/java/com/yourname/expensetracker/data/repository
grep -R "auditReason" app/src/main/java
grep -R "TransactionEvent" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
```

Replace any raw notification title/text usage in:

- expense created event;
- auto-accept event;
- review created event;
- duplicate event;
- source-link metadata;
- parser diagnostics.

---

## Step 2.5 — Add negative privacy tests

For every `RawStorageMode`:

- `STORE_RAW`
- `STORE_REDACTED`
- `STORE_METADATA_ONLY`
- `DO_NOT_STORE`

Verify transaction events/audit payload do not contain:

```text
notification.title
notification.text
notification.bigText
combinedBody
extrasJson
```

Even in `STORE_RAW`, prefer no raw audit payload. Raw audit storage should not be necessary because raw notification row/source link already provides provenance under allowed mode.

---

## PR 2 tests

1. Auto-accepted notification with title `"Secret Bank Title"`:
   - transaction event/audit does not contain it.

2. Auto-accepted notification with text/body:
   - audit does not contain text/body.

3. `DO_NOT_STORE`:
   - no title/text/body/extras in audit, diagnostics, source metadata.

4. `STORE_RAW`:
   - raw notification row may store raw if policy allows;
   - audit payload still uses safe IDs/hashes only.

5. Parser provenance survives:
   - audit contains parser source/confidence.

6. Source link ID/raw ID survives:
   - audit can still trace without raw text.

## PR 2 acceptance criteria

- Auto-accept path no longer constructs raw title payload.
- Transaction event/audit metadata is privacy-safe.
- Raw-storage modes are respected.
- Tests prove raw notification title/body cannot appear in audit payload.

---

# PR 3 — P1-NEW-18: Source-link failures swallowed

## Current problem

`writeNotificationDedupeSourceLink(...)` uses `runCatching` and ignores failure.

Risk:

- expense/review may be created with no provenance link;
- debugging loses traceability;
- source-link contract says provenance should be durable/explainable;
- failure is invisible to diagnostics.

## Goal

Source-link writes should return a typed result and emit diagnostics.

Rule:

```text
Source-link failure should not usually roll back user expense creation,
but it must be visible and repairable.
```

---

## Files to modify

Primary:

- `NotificationProcessingPipeline.kt`
- source-link DAO/repository/coordinator
- diagnostic emitter/factory

New files recommended:

```text
domain/provenance/SourceLinkWriteResult.kt
domain/provenance/NotificationSourceLinkWriter.kt
```

Optional worker:

```text
worker/SourceLinkRepairWorker.kt
```

Tests:

```text
NotificationSourceLinkWriterTest.kt
NotificationProcessingPipelineSourceLinkTest.kt
```

---

## Step 3.1 — Define typed source-link result

```kotlin
sealed interface SourceLinkWriteResult {
    data class Created(
        val sourceLinkId: Long
    ) : SourceLinkWriteResult

    data class AlreadyExists(
        val sourceLinkId: Long?
    ) : SourceLinkWriteResult

    data class Failed(
        val errorClass: String,
        val errorMessageHash: String?,
        val retryable: Boolean
    ) : SourceLinkWriteResult
}
```

Do not expose raw exception messages in diagnostics.

---

## Step 3.2 — Create `NotificationSourceLinkWriter`

```kotlin
class NotificationSourceLinkWriter @Inject constructor(
    private val sourceLinkDao: SourceLinkDao,
    private val diagnostics: NotificationDiagnosticEmitter,
    private val safeHash: SafeHashProvider
) {
    suspend fun write(
        rawNotificationId: Long?,
        expenseId: Long?,
        pendingReviewId: Long?,
        notification: RawNotification,
        correlationId: String?
    ): SourceLinkWriteResult
}
```

Responsibilities:

1. Build safe source-link metadata.
2. Insert source link.
3. Return typed result.
4. On failure, emit diagnostic.

Safe metadata only:

```text
sourceType = NOTIFICATION
rawNotificationId
expenseId / pendingReviewId
packageNameHash
notificationKeyHash
dedupeFingerprintHash
providerId if available
externalFingerprintHash if available
```

Forbidden:

- raw title;
- raw text;
- raw body;
- raw extras.

---

## Step 3.3 — Replace swallowed `runCatching`

Replace:

```kotlin
runCatching {
    writeNotificationDedupeSourceLink(...)
}
```

with:

```kotlin
when (val result = notificationSourceLinkWriter.write(...)) {
    is SourceLinkWriteResult.Created -> {
        // optional non-terminal diagnostic SOURCE_LINK_CREATED
    }

    is SourceLinkWriteResult.AlreadyExists -> {
        // optional debug diagnostic
    }

    is SourceLinkWriteResult.Failed -> {
        // already diagnosed by writer
        scheduleRepairIfRetryable(...)
    }
}
```

No empty `runCatching`.

---

## Step 3.4 — Decide atomicity policy

Recommended policy:

```text
Do not fail/rollback expense creation solely because source-link insert fails.
Emit SOURCE_LINK_FAILED and schedule repair.
```

Reason:

- user-visible expense should not disappear because provenance insert failed;
- source-link can be reconstructed from rawId/expenseId if enough safe metadata exists.

But for duplicate/provenance-critical paths, add repair.

---

## Step 3.5 — Add repair queue or pending repair marker

Minimum fix:

- emit durable diagnostic with:
  - rawNotificationId;
  - expenseId/reviewId;
  - retryable flag.

Better fix:

Create table:

```text
source_link_repair_queue
```

or reuse existing worker/diagnostic repair infrastructure if present.

Suggested entity:

```kotlin
data class SourceLinkRepairEntity(
    val id: Long = 0,
    val sourceType: String,
    val rawNotificationId: Long?,
    val expenseId: Long?,
    val pendingReviewId: Long?,
    val dedupeFingerprintHash: String?,
    val attempts: Int,
    val nextAttemptAt: Long?,
    val status: String
)
```

If this is too much for PR 3, add TODO but still emit diagnostic. Since severity is P2, typed result + diagnostic may be enough.

---

## Step 3.6 — Diagnostic events

Add reason/event:

```text
stage = "source_link"
outcome = SIDE_EFFECT_FAILED or FAILED_RETRYABLE
reasonCode = SOURCE_LINK_FAILED
terminal = false
```

If enum does not exist, add:

```kotlin
DiagnosticReasonCode.SOURCE_LINK_FAILED
```

Safe metadata:

```text
entityType = Expense/PendingReview
hasRawNotificationId = true/false
hasExpenseId = true/false
hasReviewId = true/false
retryable = true/false
errorClass
errorMessageHash
```

Do not include raw notification body.

---

## Step 3.7 — Update pipeline paths

Source links likely need to be written for:

- auto-accepted expense;
- pending-review creation;
- parser-failed transaction-signal review;
- oversized review;
- maybe duplicate raw link if design requires.

Ensure every path handles `SourceLinkWriteResult`.

Suggested helper:

```kotlin
private suspend fun writeSourceLinkBestEffort(
    rawId: Long?,
    expenseId: Long?,
    reviewId: Long?,
    notification: RawNotification,
    correlationId: String?
): SourceLinkWriteResult
```

But “best effort” must mean:

```text
does not throw to caller, but emits diagnostics and returns result.
```

Not “silent”.

---

## PR 3 tests

1. Source-link insert succeeds:
   - returns `Created`;
   - source link exists.

2. Source-link unique conflict:
   - returns `AlreadyExists`;
   - no failure diagnostic.

3. DAO throws retryable DB exception:
   - returns `Failed(retryable=true)`;
   - emits `SOURCE_LINK_FAILED`;
   - expense/review outcome still returned.

4. DAO throws non-retryable validation exception:
   - returns `Failed(retryable=false)`;
   - emits diagnostic.

5. Auto-accept path with source-link failure:
   - expense created;
   - pipeline outcome `AutoAccepted`;
   - non-terminal source-link failure diagnostic exists.

6. Pending-review path with source-link failure:
   - review created;
   - failure diagnostic exists.

7. Diagnostic contains no raw title/text/body/extras.

## PR 3 acceptance criteria

- No silent `runCatching` around source-link writes.
- Source-link write returns typed result.
- Source-link failure produces diagnostic.
- User-visible expense/review is not rolled back unless explicitly configured.
- Tests cover success, duplicate, retryable failure, non-retryable failure.
- No raw notification content appears in source-link metadata/diagnostics.

---

# PR 4 — Cross-regression tests + docs/tracker

## Step 4.1 — End-to-end regression tests

Add combined scenarios:

### Scenario A — auto-accept, no location, safe audit, source link success

Expected:

- location provider not called;
- expense created;
- audit payload has no raw title/text/body;
- source link created.

### Scenario B — auto-accept, source-link failure

Expected:

- expense created;
- `SOURCE_LINK_FAILED` diagnostic exists;
- no raw notification content in diagnostic;
- no location call.

### Scenario C — privacy mode `DO_NOT_STORE`

Expected:

- no raw audit payload;
- no raw source-link metadata;
- no raw diagnostics;
- location provider not called.

---

## Step 4.2 — Update docs

Update Pipeline 1 docs:

```text
Location:
- Notification pipeline does not read GPS by default.
- Location enrichment requires explicit policy/gate.

Audit:
- Transaction audit from notifications stores safe IDs/hashes/provenance only.
- Raw notification title/text/body never enters audit payload.

Source links:
- Source-link failures are non-terminal but diagnosed.
- Failures are no longer swallowed.
```

---

## Step 4.3 — Update tracker

After PR 1:

| ID | New status |
|---|---:|
| P1-NEW-16 | Fixed |

After PR 2:

| ID | New status |
|---|---:|
| P1-NEW-17 | Fixed |

After PR 3:

| ID | New status |
|---|---:|
| P1-NEW-18 | Fixed |

If repair worker is deferred, write:

```text
P1-NEW-18 fixed for observability/typed failure handling.
Optional repair queue remains follow-up.
```

---

# Recommended implementation order

1. **PR 1 — Location policy**
   - highest privacy/OS compliance risk.
2. **PR 2 — Safe audit payload**
   - prevents raw title persistence/propagation.
3. **PR 3 — Source-link typed result**
   - improves provenance observability.
4. **PR 4 — Combined tests/docs**

---

# Do not mix into these PRs

Keep out of scope:

- durable notification intake queue;
- full service decomposition;
- AI parser provenance refactor;
- currency fallback detector;
- complete source-link repair worker unless small;
- UI changes for source-link diagnostics.

Allowed overlap:

- If safe diagnostic emitter already exists, use it for source-link failure events.
- If parser provenance already exists, include safe parser source/confidence in audit payload.

---

# Final validation commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Useful searches:

```bash
grep -R "getLastKnownLocation" app/src/main/java
grep -R "ForegroundLocationProvider" app/src/main/java/com/yourname/expensetracker/data/repository
grep -R "notification.title" app/src/main/java/com/yourname/expensetracker/data/repository
grep -R "auditReason" app/src/main/java
grep -R "writeNotificationDedupeSourceLink" app/src/main/java
grep -R "runCatching" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
```

Expected after PRs:

- notification pipeline does not directly call location provider;
- no auto-accept audit payload contains raw notification title/text/body;
- source-link writes do not silently swallow failures;
- source-link failures emit safe diagnostics.