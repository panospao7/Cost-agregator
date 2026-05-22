# Dedicated implementation plan — P1-NEW-11 and P1-NEW-12

Reviewed against commit: `e781c226862234ed412914884e98d22165a41a95`

Target issues:

| ID | Severity | Theme |
|---|---:|---|
| P1-NEW-11 | P1/P2 | Privacy cache fail-closed startup drops valid notifications |
| P1-NEW-12 | P1/P2 | Non-raw storage modes break raw-field duplicate pre-check |

Recommended split:

1. **PR 1 — Privacy capture policy warm-up + no fake startup privacy drops**
2. **PR 2 — Fingerprint-first duplicate pre-check for all raw-storage modes**
3. **PR 3 — Regression tests + tracker/docs update**

---

# Current evidence

## P1-NEW-11

Current `NotificationCaptureService` has:

```kotlin
@Volatile private var capturePrivacyDenied = true
```

The service updates this flag asynchronously from:

```kotlin
privacySettingsRepository.observeSettings()
```

Then `onNotificationPosted()` checks:

```kotlin
if (isPrivacyDeniedFast()) {
    // emit PRIVACY_DENIED and return
}
```

Problem:

```text
Before the first settings-flow emission, capturePrivacyDenied is true.
```

So a valid notification posted immediately after service start can be dropped as `PRIVACY_DENIED`, even if settings actually allow notification capture.

This is privacy-safe but capture-unreliable and diagnostically misleading.

Important distinction:

- Real privacy denial should drop notification.
- Settings-loading state should not be mislabeled as real privacy denial.
- Corrupted/fail-closed settings should drop, but with a distinct fail-closed reason.

---

## P1-NEW-12

`RawNotification` has a unique index on `dedupeFingerprint`.

But `RawNotificationDao` currently exposes a raw-field duplicate check:

```kotlin
exists(packageName, timestamp, title, text, bigText)
```

`NotificationProcessingPipeline` uses this before parse/AI work.

Problem:

The listener path creates:

- `processingNotification` with real raw title/text/body in memory;
- `storageNotification` sanitized according to `RawStorageMode`.

In modes like:

- `STORE_REDACTED`
- `STORE_METADATA_ONLY`
- `DO_NOT_STORE`

the stored DB row can have redacted/null title/text/body.

So a later duplicate pre-check using raw in-memory title/text/body does **not** match the stored sanitized row.

The DB unique fingerprint may still reject `insertOrIgnore`, but that happens too late — after parser/AI work.

Correct rule:

```text
Fast duplicate detection must use the canonical dedupe fingerprint, not raw persisted text fields.
```

---

# PR 1 — Privacy capture policy warm-up + no fake startup privacy drops

## Goal

Replace:

```text
capturePrivacyDenied = true until observeSettings emits
```

with:

```text
privacy state is explicitly Loaded / Loading / Failed.
Only real denial or real fail-closed state is emitted as privacy denial.
Cache-not-ready is either resolved by one-shot load or reported as SETTINGS_NOT_READY.
```

No notification extras/text extraction should happen until the privacy decision is known.

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `PrivacySettingsRepository.kt` usage sites only

New files recommended:

```text
domain/notification/capture/NotificationPrivacyCapturePolicy.kt
domain/notification/capture/NotificationPrivacyCaptureDecision.kt
```

Tests:

```text
NotificationPrivacyCapturePolicyTest.kt
NotificationCaptureServicePrivacyStartupTest.kt
```

Optional if P1-P1-05 capture gate already exists:

- integrate this into `NotificationCaptureGate` instead of creating a separate policy.

---

## Step 1.1 — Add explicit privacy decision model

Create:

```kotlin
sealed interface NotificationPrivacyCaptureDecision {
    data object Allowed : NotificationPrivacyCaptureDecision

    data class Denied(
        val reason: PrivacyCaptureBlockReason,
        val diagnosticReason: DiagnosticReasonCode,
        val message: String? = null
    ) : NotificationPrivacyCaptureDecision

    data class TemporarilyUnavailable(
        val reason: PrivacyCaptureBlockReason,
        val retryable: Boolean,
        val message: String? = null
    ) : NotificationPrivacyCaptureDecision
}
```

Reasons:

```kotlin
enum class PrivacyCaptureBlockReason {
    SETTINGS_LOADING,
    SETTINGS_LOAD_FAILED,
    SETTINGS_CORRUPTED_FAIL_CLOSED,
    NOTIFICATION_CAPTURE_DISABLED,
    PRIVACY_GATE_DENIED,
    PRIVACY_GATE_FAIL_CLOSED,
    PRIVACY_GATE_NOT_APPLICABLE
}
```

Policy:

| State | Decision |
|---|---|
| loaded settings + notification capture enabled + privacy gate allowed | `Allowed` |
| loaded settings + `notificationCaptureEnabled=false` | `Denied(NOTIFICATION_CAPTURE_DISABLED)` |
| corrupted/fail-closed settings | `Denied(SETTINGS_CORRUPTED_FAIL_CLOSED)` |
| settings still loading and one-shot load times out | `TemporarilyUnavailable(SETTINGS_LOADING)` |
| repository throws during one-shot load | `TemporarilyUnavailable` or `Denied`, depending on load state |
| full privacy gate denied | `Denied(PRIVACY_GATE_DENIED)` |
| full privacy gate fail-closed | `Denied(PRIVACY_GATE_FAIL_CLOSED)` |
| privacy gate returns `NotApplicable` | fail closed or explicit policy decision; do not accidentally proceed |

Recommended:

```text
Treat NotApplicable as fail-closed at the service boundary.
```

Reason: `PrivacyGate` docs say `NotApplicable` is an intermediate composite result; the capture boundary needs a final decision.

---

## Step 1.2 — Implement `NotificationPrivacyCapturePolicy`

Suggested constructor:

```kotlin
@Singleton
class NotificationPrivacyCapturePolicy @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val privacyGate: PrivacyGate,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
)
```

Internal state:

```kotlin
private val state = MutableStateFlow<PrivacyCaptureCacheState>(
    PrivacyCaptureCacheState.NotLoaded
)
```

State model:

```kotlin
sealed interface PrivacyCaptureCacheState {
    data object NotLoaded : PrivacyCaptureCacheState
    data object Loading : PrivacyCaptureCacheState
    data class Ready(
        val settings: PrivacySettings,
        val loadState: PrivacySettingsLoadState
    ) : PrivacyCaptureCacheState
    data class LoadFailed(
        val errorClass: String,
        val messageHash: String?
    ) : PrivacyCaptureCacheState
}
```

Public API:

```kotlin
suspend fun warmUp()

fun startObservers(scope: CoroutineScope)

suspend fun decide(): NotificationPrivacyCaptureDecision
```

---

## Step 1.3 — Warm up privacy state synchronously/one-shot

`warmUp()` should call the repository’s one-shot APIs:

```kotlin
val loadState = privacySettingsRepository.getLoadState()
val settings = when (loadState) {
    is PrivacySettingsLoadState.Loaded -> loadState.settings
    is PrivacySettingsLoadState.FirstRunDefault -> loadState.settings
    is PrivacySettingsLoadState.CorruptedFailClosed -> loadState.settings
}
```

Then store:

```kotlin
state.value = Ready(settings, loadState)
```

If `getLoadState()` fails, fallback to `getSettings()` only if that is safe and known to return fail-closed defaults on corruption.

Important:

```text
Do not start with “denied”.
Start with “not loaded”.
```

---

## Step 1.4 — Observe updates after warm-up

After one-shot load:

```kotlin
fun startObservers(scope: CoroutineScope) {
    scope.launch(ioDispatcher) {
        warmUp()
        privacySettingsRepository.observeLoadState().collect { loadState ->
            state.value = Ready(loadState.settings, loadState)
        }
    }
}
```

If `observeLoadState()` is awkward to use, observe settings but preserve load-state from the latest one-shot load.

On observer error:

- keep last known good `Ready` state if present;
- otherwise set `LoadFailed`;
- do **not** convert to fake privacy denial.

---

## Step 1.5 — `decide()` must resolve NotLoaded with one-shot load

Pseudo:

```kotlin
suspend fun decide(): NotificationPrivacyCaptureDecision {
    val current = state.value

    val readyState = when (current) {
        is Ready -> current
        NotLoaded, Loading, is LoadFailed -> {
            val warmed = withTimeoutOrNull(PRIVACY_WARMUP_TIMEOUT_MS) {
                runCatching {
                    warmUp()
                    state.value
                }.getOrNull()
            }

            warmed as? Ready ?: return TemporarilyUnavailable(
                reason = SETTINGS_LOADING,
                retryable = true
            )
        }
    }

    if (readyState.loadState is PrivacySettingsLoadState.CorruptedFailClosed) {
        return Denied(
            reason = SETTINGS_CORRUPTED_FAIL_CLOSED,
            diagnosticReason = DiagnosticReasonCode.PRIVACY_DENIED
        )
    }

    if (!readyState.settings.notificationCaptureEnabled) {
        return Denied(
            reason = NOTIFICATION_CAPTURE_DISABLED,
            diagnosticReason = DiagnosticReasonCode.PRIVACY_DENIED
        )
    }

    return when (val decision = privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) {
        PrivacyDecision.Allowed -> Allowed
        is PrivacyDecision.Denied -> Denied(PRIVACY_GATE_DENIED, DiagnosticReasonCode.PRIVACY_DENIED, decision.reason)
        is PrivacyDecision.FailClosed -> Denied(PRIVACY_GATE_FAIL_CLOSED, DiagnosticReasonCode.PRIVACY_DENIED, decision.reason)
        PrivacyDecision.NotApplicable -> Denied(PRIVACY_GATE_NOT_APPLICABLE, DiagnosticReasonCode.PRIVACY_DENIED)
    }
}
```

Suggested timeout:

```kotlin
private const val PRIVACY_WARMUP_TIMEOUT_MS = 300L
```

If P1-P1-07 durable intake already exists, use retryable intake status instead of final drop for `SETTINGS_LOADING`.

---

## Step 1.6 — Update service flow

Replace:

```kotlin
@Volatile private var capturePrivacyDenied = true
private fun isPrivacyDeniedFast(): Boolean = capturePrivacyDenied
```

with injected policy:

```kotlin
@Inject lateinit var privacyCapturePolicy: NotificationPrivacyCapturePolicy
```

In `onCreate()`:

```kotlin
privacyCapturePolicy.startObservers(serviceScope)
```

In notification path:

```kotlin
val decision = privacyCapturePolicy.decide()

when (decision) {
    Allowed -> proceedToPackageGateAndExtraction()
    is Denied -> emitTerminalPrivacyDrop(decision)
    is TemporarilyUnavailable -> emitTerminalGateUnavailable(decision)
}
```

Important:

```text
This decision must happen before:
- sbn.notification.extras
- NotificationTextParts.extract(...)
- content hash
- filter
- parser/pipeline
```

Current `onNotificationPosted()` reads extras after fast privacy/package checks, so keep it that way, but replace the boolean with the explicit decision.

---

## Step 1.7 — Diagnostics

Do not label startup-loading drops as `PRIVACY_DENIED`.

Use distinct metadata:

```text
stage = "privacy_policy"
outcome = BLOCKED or DROPPED
reasonCode = PRIVACY_DENIED or UNKNOWN_ERROR/VALIDATION_FAILED depending enum availability
metadata:
  privacyReason = SETTINGS_LOADING / SETTINGS_CORRUPTED_FAIL_CLOSED / NOTIFICATION_CAPTURE_DISABLED
  retryable = true/false
```

If your enum supports it, add:

```kotlin
DiagnosticReasonCode.PRIVACY_SETTINGS_NOT_READY
DiagnosticReasonCode.PRIVACY_SETTINGS_CORRUPTED
```

If not, use existing `PRIVACY_DENIED` only for real disabled/denied/fail-closed states, and put exact reason in safe metadata.

No raw notification text in metadata.

---

## PR 1 tests

### Policy tests

1. Initial state + `getLoadState()` returns `Loaded(enabled=true)`:
   - decision = `Allowed`.

2. Initial state + first-run defaults enabled:
   - decision = `Allowed`.

3. Initial state + loaded settings disabled:
   - decision = `Denied(NOTIFICATION_CAPTURE_DISABLED)`.

4. Initial state + corrupted fail-closed:
   - decision = `Denied(SETTINGS_CORRUPTED_FAIL_CLOSED)`.

5. Initial state + repository timeout:
   - decision = `TemporarilyUnavailable(SETTINGS_LOADING)`;
   - not `Denied(PRIVACY_DENIED)`.

6. Observer error after previous good state:
   - keep previous good state.

7. Observer error before any good state:
   - `TemporarilyUnavailable` or fail-closed with explicit load-failed reason.

8. `PrivacyGate.Denied`:
   - decision = `Denied(PRIVACY_GATE_DENIED)`.

9. `PrivacyGate.FailClosed`:
   - decision = `Denied(PRIVACY_GATE_FAIL_CLOSED)`.

10. `PrivacyGate.NotApplicable`:
   - does not accidentally proceed.

### Service tests

1. Privacy settings loading:
   - extractor not called;
   - diagnostic reason is settings-not-ready, not generic privacy denied.

2. Privacy enabled:
   - extractor called.

3. Privacy disabled:
   - extractor not called.

4. Corrupted fail-closed:
   - extractor not called;
   - diagnostic has fail-closed reason.

5. Notification posted immediately after `onCreate()`:
   - one-shot `getLoadState()` is used;
   - valid notification is not dropped simply because flow has not emitted.

---

## PR 1 acceptance criteria

- `capturePrivacyDenied = true` startup fail-closed boolean is removed.
- No valid notification is dropped solely because `observeSettings()` has not emitted yet.
- Startup-loading is represented separately from real privacy denial.
- Corrupted/fail-closed settings still block capture.
- Full `PrivacyGate.check(NOTIFICATION_CAPTURE)` is part of the decision.
- Extras/text extraction never happens unless decision is `Allowed`.
- Tests cover enabled, disabled, loading, corrupted, observer error, and gate denial.

---

# PR 2 — Fingerprint-first duplicate pre-check for all raw-storage modes

## Goal

Use `dedupeFingerprint` for fast duplicate detection before parser/AI work.

Current problematic behavior:

```text
dao.exists(packageName, timestamp, rawTitle, rawText, rawBigText)
```

New behavior:

```text
dao.existsByDedupeFingerprint(rawContentFingerprint)
```

This fixes duplicate detection under:

- raw mode,
- redacted mode,
- metadata-only mode,
- do-not-store mode.

---

## Files to modify

Primary:

- `RawNotificationDao.kt`
- `NotificationProcessingPipeline.kt`
- `RawNotificationFingerprint.kt` usage sites
- tests for pipeline/DAO

Possible:

- `NotificationRepository.kt` if batch/public paths construct missing fingerprints
- `NotificationCaptureService.kt` if service sometimes fails to set fingerprint

---

## Step 2.1 — Add DAO query

In `RawNotificationDao.kt`:

```kotlin
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM raw_notifications
        WHERE dedupeFingerprint = :fingerprint
    )
""")
suspend fun existsByDedupeFingerprint(fingerprint: String): Boolean
```

Optional but useful for debugging:

```kotlin
@Query("""
    SELECT id FROM raw_notifications
    WHERE dedupeFingerprint = :fingerprint
    LIMIT 1
""")
suspend fun findIdByDedupeFingerprint(fingerprint: String): Long?
```

No migration needed because `RawNotification` already has a unique index on `dedupeFingerprint`.

---

## Step 2.2 — Add fingerprint resolver helper

Create helper in pipeline or shared domain:

```kotlin
private fun RawNotification.resolvedDedupeFingerprint(): String {
    return dedupeFingerprint ?: RawNotificationFingerprint.compute(
        packageName = packageName,
        title = title,
        text = text,
        bigText = bigText,
        timestamp = timestamp
    )
}
```

Important:

```text
Compute from the processing/raw in-memory notification, not the sanitized storage notification.
```

Reason:

- sanitized storage fields may be null/redacted;
- canonical duplicate identity should be based on the captured notification content that parser sees.

---

## Step 2.3 — Replace pre-parse raw-field duplicate check

In `NotificationProcessingPipeline.processInternal(...)`, replace:

```kotlin
if (dao.exists(
    packageName = notification.packageName,
    timestamp = notification.timestamp,
    title = notification.title,
    text = notification.text,
    bigText = notification.bigText
)) {
    return NotificationPipelineOutcome.Duplicate(...)
}
```

with:

```kotlin
val dedupeFingerprint = notification.resolvedDedupeFingerprint()

if (dao.existsByDedupeFingerprint(dedupeFingerprint)) {
    Timber.d("Duplicate notification detected by fingerprint before parse: ${notification.packageName}")
    writePipelineDiagnosticEvent(
        notification = notification,
        stage = "dedupe",
        outcome = EventOutcome.DUPLICATE,
        reasonCode = DiagnosticReasonCode.DUPLICATE,
        correlationId = correlationId,
        metadata = SafeEventMetadata.builder()
            .put("dedupeBasis", "dedupeFingerprint")
            .build(),
        isTerminal = true
    )
    return NotificationPipelineOutcome.Duplicate(
        packageName = notification.packageName,
        correlationId = correlationId,
        reason = "dedupeFingerprint"
    )
}
```

If your current `NotificationPipelineOutcome.Duplicate` does not yet include `packageName/correlationId`, adapt to the current type, but include the basis in diagnostics.

---

## Step 2.4 — Keep legacy raw-field check only for legacy rows

Because older rows may have `dedupeFingerprint = null`, use raw-field fallback only if needed.

Suggested policy:

```kotlin
if (dao.existsByDedupeFingerprint(dedupeFingerprint)) {
    return Duplicate(...)
}

if (LEGACY_RAW_FIELD_DEDUP_ENABLED &&
    dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text, notification.bigText)
) {
    return Duplicate(reason = "legacyRawField")
}
```

But be careful:

- Under non-raw modes, legacy raw-field check can still miss duplicates.
- It is only a best-effort compatibility fallback for old rows with null fingerprint.

Recommended:

```text
Use fingerprint as the canonical check.
Keep raw-field exists only as legacy fallback, never call it the primary check.
```

---

## Step 2.5 — Strengthen insert path

Current insert path does:

```kotlin
val alreadyExists = dao.exists(raw fields)
if (alreadyExists) return -1L

return dao.insertOrIgnore(storageNotification.copy(
    dedupeFingerprint = notification.dedupeFingerprint
))
```

Change to:

```kotlin
private suspend fun insertRawNotificationIfNotDuplicate(
    notification: RawNotification,
    storageNotification: RawNotification = notification
): Long {
    val dedupeFingerprint = notification.resolvedDedupeFingerprint()

    if (dao.existsByDedupeFingerprint(dedupeFingerprint)) {
        return -1L
    }

    return dao.insertOrIgnore(
        storageNotification.copy(dedupeFingerprint = dedupeFingerprint)
    )
}
```

This ensures:

```text
DB row stores the raw-content fingerprint even if storage row text is redacted/null.
```

If you already implemented typed insert result from another PR, use:

```kotlin
RawNotificationInsertResult.Duplicate
RawNotificationInsertResult.Inserted(rawId)
```

instead of `-1L`.

---

## Step 2.6 — Ensure all entrypoints set fingerprint

Audit call sites:

```bash
grep -R "RawNotification(" app/src/main/java
grep -R "dedupeFingerprint" app/src/main/java
```

Rules:

1. Listener path:
   - compute fingerprint from processing text/body.
   - storage row copies that fingerprint.

2. Manual refresh path:
   - same as listener path.

3. Batch path:
   - if input raw notification has null fingerprint, pipeline computes it before duplicate check and insert.

4. Tests/fakes:
   - update to provide fingerprint or rely on resolver.

Do not compute fingerprint from sanitized title/text/body.

---

## Step 2.7 — Update KDoc/comments

Current pipeline KDoc says:

```text
Fast fingerprint dedup — checks RawNotificationDao.exists
```

Update to:

```text
Fast fingerprint dedup — checks RawNotificationDao.existsByDedupeFingerprint before parse/AI.
Raw-field exists is legacy fallback only.
```

Update `RawNotification` KDoc if needed to clarify:

```text
dedupeFingerprint is based on processing/raw notification content, not necessarily persisted title/text/body, because privacy modes may sanitize stored fields.
```

---

## Step 2.8 — Diagnostics

Duplicate diagnostic metadata should include:

```text
dedupeBasis = "dedupeFingerprint"
rawStorageSafe = true
```

Do not include the fingerprint itself unless it is considered safe. If you need correlation, store a hash of the fingerprint:

```kotlin
.putHashed("dedupeFingerprint", dedupeFingerprint)
```

But usually just `dedupeBasis` is enough.

---

## PR 2 tests

### DAO tests

1. Insert row with `dedupeFingerprint = X`.
2. `existsByDedupeFingerprint(X)` returns true.
3. `existsByDedupeFingerprint(Y)` returns false.
4. Unique index prevents second insert with same fingerprint.

### Pipeline duplicate tests

Use fake parser that records call count.

1. Raw mode duplicate:
   - existing row has same raw fields and same fingerprint;
   - pipeline returns `Duplicate`;
   - parser call count = 0.

2. `STORE_METADATA_ONLY` duplicate:
   - existing stored row has `title=null`, `text=null`, `bigText=null`, `dedupeFingerprint=X`;
   - incoming processing notification has raw title/text/body and fingerprint X;
   - pipeline returns `Duplicate`;
   - parser call count = 0.

3. `STORE_REDACTED` duplicate:
   - existing row has redacted title/text/body and fingerprint X;
   - incoming raw notification has original body and fingerprint X;
   - duplicate detected before parser.

4. `DO_NOT_STORE` duplicate:
   - existing row has no raw body and fingerprint X;
   - incoming raw notification has body in memory and fingerprint X;
   - duplicate detected before parser.

5. Null incoming fingerprint:
   - pipeline computes fingerprint;
   - duplicate check still works.

6. Null legacy stored fingerprint:
   - raw-field fallback can catch if stored row has matching raw fields;
   - otherwise not guaranteed, documented as legacy limitation.

7. Insert conflict:
   - if fingerprint unique index rejects insert, outcome maps to duplicate, not generic failure.

### Regression test

```text
Non-raw storage duplicate must not invoke parseWithAiFallback().
```

This is the core acceptance test for P1-NEW-12.

---

## PR 2 acceptance criteria

- `RawNotificationDao.existsByDedupeFingerprint()` exists.
- Pipeline pre-parse duplicate check uses fingerprint first.
- Insert duplicate check uses fingerprint first.
- Stored sanitized row preserves raw-content `dedupeFingerprint`.
- Duplicate detection works under `STORE_RAW`, `STORE_REDACTED`, `STORE_METADATA_ONLY`, and `DO_NOT_STORE`.
- Parser/AI is not called for fingerprint duplicates.
- Raw-field `exists(...)` is no longer described as “fingerprint dedup.”
- Tests cover all storage modes.

---

# PR 3 — Regression tests + tracker/docs update

## Goal

Lock both fixes into the debugging workflow and update tracker status.

---

## Step 3.1 — Add combined startup + duplicate tests

Useful scenario:

```text
1. Service starts.
2. Privacy settings flow has not emitted yet.
3. One-shot getLoadState returns notificationCaptureEnabled=true.
4. Notification is accepted and processed.
5. Stored row is metadata-only with fingerprint.
6. Same notification arrives again.
7. Duplicate is detected by fingerprint before parser.
```

Expected:

- no fake `PRIVACY_DENIED`;
- parser called only once;
- second callback returns duplicate.

---

## Step 3.2 — Add debug checklist entries

Update `debugging-slicing-and-checklist.md` or Pipeline 1 debugging doc with:

```text
Privacy startup:
- notification immediately after service start should not drop as PRIVACY_DENIED unless settings actually deny.
- SETTINGS_LOADING/LOAD_FAILED must be distinguished from user-disabled capture.

Dedup:
- duplicate pre-check must use dedupeFingerprint, not raw title/text/body.
- metadata-only and do-not-store modes must still skip parser/AI for duplicates.
```

---

## Step 3.3 — Update master tracker

After PR 1:

| ID | New status |
|---|---:|
| P1-NEW-11 | Fixed |

After PR 2:

| ID | New status |
|---|---:|
| P1-NEW-12 | Fixed |

If full P1-P1-05 capture gate is not done yet, add caveat:

```text
P1-NEW-11 fixed only for startup cache false drops. Full pre-extraction gate consolidation remains P1-P1-05.
```

If typed insert result is not done yet, add caveat:

```text
P1-NEW-12 fixed for fingerprint pre-check. Typed insert-result cleanup remains P1-NEW-13.
```

---

# Recommended implementation order

1. **PR 1 — Privacy capture policy**
   - prevents valid startup notifications from being falsely dropped.
   - improves diagnostics.

2. **PR 2 — Fingerprint duplicate check**
   - avoids duplicate parser/AI work under privacy-safe storage modes.

3. **PR 3 — Combined regression/docs**
   - updates tracker and locks scenarios into tests.

These can be implemented independently, but PR 1 is safer first because it affects the earliest capture boundary.

---

# Do not mix into these PRs

Keep out of scope:

- durable intake queue / process-death recovery;
- full service decomposition;
- MessagingStyle extraction;
- in-memory dedupe TTL/content-aware rewrite;
- AI parser provenance;
- currency fallback expansion;
- location/foreground-service fix.

Allowed small overlap:

- If `NotificationCaptureGate` already exists from P1-P1-05, implement P1-NEW-11 inside it instead of creating `NotificationPrivacyCapturePolicy`.

---

# Final validation commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Useful searches:

```bash
grep -R "capturePrivacyDenied" app/src/main/java
grep -R "isPrivacyDeniedFast" app/src/main/java
grep -R "existsByDedupeFingerprint" app/src/main/java
grep -R "dao.exists(" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "dedupeFingerprint = notification.dedupeFingerprint" app/src/main/java
```

Expected after PRs:

- no startup `capturePrivacyDenied = true` boolean gate;
- no fake privacy-denied drop due only to flow not emitted;
- `existsByDedupeFingerprint()` is used before parser/AI;
- pipeline raw-field `dao.exists(...)` is gone or legacy-only;
- storage notification always receives canonical raw-content fingerprint.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationProcessingPipeline.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `RawNotificationDao.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt
- `RawNotification.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt
- `RawNotificationFingerprint.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/notification/RawNotificationFingerprint.kt
- `PrivacySettingsRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettingsRepository.kt
- `PrivacySettings.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt
- `PrivacyGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyGate.kt