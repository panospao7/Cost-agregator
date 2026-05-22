# Dedicated implementation plan — P1-NEW-13, P1-NEW-14, P1-NEW-15

Target issues:

| ID | Severity | Theme |
|---|---:|---|
| P1-NEW-13 | P2 | `insertOrIgnore` duplicate path needs typed outcome |
| P1-NEW-14 | P2/P3 | `RawNotification.isProcessed` is dead |
| P1-NEW-15 | P2 | Extras JSON materialized even when raw storage disabled |

Context commit: `e781c226862234ed412914884e98d22165a41a95`

Recommended split:

1. **PR 1 — Typed raw-notification insert result**
2. **PR 2 — Make `RawNotification.isProcessed` meaningful or formally deprecate it**
3. **PR 3 — Extras JSON persistence policy**

---

# PR 1 — P1-NEW-13: Typed raw-notification insert result

## Current problem

Current raw notification insert logic likely uses a sentinel value:

```kotlin
val rawId = dao.insertOrIgnore(...)
if (rawId == -1L) {
    // duplicate
}
```

Problems:

- `-1L` is ambiguous and easy to mishandle.
- Duplicate insert conflict can be confused with failure.
- Callers must remember Room’s sentinel behavior.
- Pipeline outcome mapping becomes fragile.
- Diagnostics cannot reliably say whether duplicate was:
  - pre-check duplicate;
  - unique-index insert conflict;
  - legacy raw-field duplicate.

## Goal

Replace sentinel handling with a typed result:

```kotlin
sealed interface RawNotificationInsertResult
```

Every insert call should return a semantic result:

```text
Inserted(rawId)
Duplicate(...)
```

No caller should directly compare `rawId == -1L`.

---

## Files to modify

Primary:

- `NotificationProcessingPipeline.kt`
- `RawNotificationDao.kt`
- `NotificationRepository.kt` if it has direct insert paths
- `RawNotification.kt`

New file recommended:

```text
domain/notification/RawNotificationInsertResult.kt
```

Tests:

```text
RawNotificationInsertResultTest.kt
NotificationProcessingPipelineInsertDuplicateTest.kt
RawNotificationDaoDuplicateTest.kt
```

---

## Step 1.1 — Add typed insert result

Create:

```kotlin
sealed interface RawNotificationInsertResult {
    val dedupeFingerprint: String?

    data class Inserted(
        val rawId: Long,
        override val dedupeFingerprint: String?
    ) : RawNotificationInsertResult

    data class Duplicate(
        val existingRawId: Long?,
        override val dedupeFingerprint: String?,
        val basis: DuplicateBasis
    ) : RawNotificationInsertResult
}

enum class DuplicateBasis {
    DEDUPE_FINGERPRINT_PRECHECK,
    DEDUPE_FINGERPRINT_INSERT_CONFLICT,
    LEGACY_RAW_FIELD_PRECHECK
}
```

Do **not** add `Error` unless current insert code catches exceptions. Prefer exceptions for real DB failures and typed result only for expected duplicate outcomes.

---

## Step 1.2 — Add DAO lookup by fingerprint

If not already added from P1-NEW-12, add:

```kotlin
@Query("""
    SELECT id FROM raw_notifications
    WHERE dedupeFingerprint = :fingerprint
    LIMIT 1
""")
suspend fun findIdByDedupeFingerprint(fingerprint: String): Long?
```

Also add if missing:

```kotlin
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM raw_notifications
        WHERE dedupeFingerprint = :fingerprint
    )
""")
suspend fun existsByDedupeFingerprint(fingerprint: String): Boolean
```

No migration needed if `dedupeFingerprint` column/index already exists.

---

## Step 1.3 — Replace insert helper return type

Current helper probably resembles:

```kotlin
private suspend fun insertRawNotificationIfNotDuplicate(...): Long
```

Change to:

```kotlin
private suspend fun insertRawNotificationIfNotDuplicate(
    processingNotification: RawNotification,
    storageNotification: RawNotification = processingNotification
): RawNotificationInsertResult
```

Implementation shape:

```kotlin
val fingerprint = processingNotification.resolvedDedupeFingerprint()

val existingId = dao.findIdByDedupeFingerprint(fingerprint)
if (existingId != null) {
    return RawNotificationInsertResult.Duplicate(
        existingRawId = existingId,
        dedupeFingerprint = fingerprint,
        basis = DuplicateBasis.DEDUPE_FINGERPRINT_PRECHECK
    )
}

val insertId = dao.insertOrIgnore(
    storageNotification.copy(dedupeFingerprint = fingerprint)
)

return if (insertId == -1L) {
    RawNotificationInsertResult.Duplicate(
        existingRawId = dao.findIdByDedupeFingerprint(fingerprint),
        dedupeFingerprint = fingerprint,
        basis = DuplicateBasis.DEDUPE_FINGERPRINT_INSERT_CONFLICT
    )
} else {
    RawNotificationInsertResult.Inserted(
        rawId = insertId,
        dedupeFingerprint = fingerprint
    )
}
```

Important:

- Compute fingerprint from the **processing/raw** notification.
- Store same fingerprint into the sanitized storage notification.
- Do not compute fingerprint from redacted/null storage fields.

---

## Step 1.4 — Update all pipeline call sites

Every place currently doing:

```kotlin
val rawId = insertRawNotificationIfNotDuplicate(...)
if (rawId == -1L) return Duplicate(...)
```

should become:

```kotlin
when (val insert = insertRawNotificationIfNotDuplicate(...)) {
    is RawNotificationInsertResult.Inserted -> {
        val rawId = insert.rawId
        // continue
    }

    is RawNotificationInsertResult.Duplicate -> {
        writeDuplicateDiagnostic(insert)
        return NotificationPipelineOutcome.Duplicate(
            packageName = notification.packageName,
            correlationId = correlationId,
            reason = insert.basis.name
        )
    }
}
```

Likely paths:

- auto-accept;
- needs-review;
- parser-failed review;
- transaction-signal review;
- oversized-amount review;
- auto-reject with raw row;
- batch path.

---

## Step 1.5 — Add duplicate diagnostic helper

Create:

```kotlin
private suspend fun writeRawInsertDuplicateDiagnostic(
    notification: RawNotification,
    duplicate: RawNotificationInsertResult.Duplicate,
    correlationId: String?
)
```

Safe metadata:

```text
duplicateBasis
hasExistingRawId
dedupeFingerprintHash? optional
```

Do not store raw title/text/body.

---

## Step 1.6 — Remove sentinel checks

Search:

```bash
grep -R "== -1L" app/src/main/java
grep -R "rawId == -1" app/src/main/java
grep -R "insertOrIgnore" app/src/main/java
```

Expected:

- direct `-1L` checks only inside the insert-result adapter/helper;
- pipeline branches use `RawNotificationInsertResult`.

---

## PR 1 tests

1. New insert returns `Inserted(rawId)`.
2. Existing fingerprint pre-check returns `Duplicate(...PRECHECK)`.
3. Race condition insert conflict returns `Duplicate(...INSERT_CONFLICT)`.
4. Duplicate outcome does not create expense.
5. Duplicate outcome does not create pending review.
6. Duplicate diagnostic includes duplicate basis.
7. No raw text appears in duplicate diagnostic metadata.
8. Cancellation exceptions still propagate.

## PR 1 acceptance criteria

- No pipeline caller handles `-1L` directly.
- Duplicate insert conflict maps to `NotificationPipelineOutcome.Duplicate`.
- Duplicate basis is visible in diagnostics.
- Tests cover pre-check duplicate and insert-conflict duplicate.

---

# PR 2 — P1-NEW-14: `RawNotification.isProcessed` is dead

## Current problem

`RawNotification.isProcessed` exists but is not maintained.

Risks:

- Misleading data model.
- Debug UI or future recovery code may trust a false field.
- Durable processing/recovery cannot rely on it.
- It overlaps conceptually with future `notification_intake.status`.

## Goal

Choose and implement one explicit policy.

Recommended short-term policy:

```text
RawNotification.isProcessed means:
“The raw notification row reached a terminal pipeline outcome.”
```

It does **not** mean:
- expense was created;
- notification was valid;
- durable intake is complete;
- retry state is tracked.

Long-term, once P1-P1-07 durable intake exists:

```text
notification_intake.status becomes the source of truth.
RawNotification.isProcessed can be deprecated/removed later.
```

---

## Files to modify

Primary:

- `RawNotification.kt`
- `RawNotificationDao.kt`
- `NotificationProcessingPipeline.kt`

Optional:

- debug UI/query files if they display raw notification status

Tests:

```text
RawNotificationProcessedFlagTest.kt
NotificationProcessingPipelineProcessedFlagTest.kt
```

---

## Step 2.1 — Clarify KDoc

In `RawNotification.kt`, update `isProcessed` KDoc:

```kotlin
/**
 * True after this raw notification row reached a terminal pipeline outcome.
 *
 * This is a coarse legacy marker for raw row consumption only.
 * It is not a retry/status machine and must not be used for durable
 * notification recovery. Durable intake/retry state belongs in
 * NotificationIntakeEntity.status once P1-P1-07 lands.
 */
val isProcessed: Boolean = false
```

---

## Step 2.2 — Add DAO update method

In `RawNotificationDao.kt`:

```kotlin
@Query("""
    UPDATE raw_notifications
    SET isProcessed = 1
    WHERE id = :rawId
""")
suspend fun markProcessed(rawId: Long): Int
```

Optional if timestamps exist:

```kotlin
@Query("""
    UPDATE raw_notifications
    SET isProcessed = 1,
        updatedAt = :nowMs
    WHERE id = :rawId
""")
suspend fun markProcessed(rawId: Long, nowMs: Long): Int
```

Do not add `processedAt` unless you want a schema migration.

---

## Step 2.3 — Mark processed after terminal outcomes

In `NotificationProcessingPipeline`, after a raw row is inserted and the pipeline reaches a terminal result, call:

```kotlin
markRawProcessedBestEffort(rawId)
```

Create helper:

```kotlin
private suspend fun markRawProcessedBestEffort(rawId: Long) {
    runCatching {
        rawNotificationDao.markProcessed(rawId)
    }.onFailure { error ->
        writePipelineDiagnosticEvent(
            stage = "raw_notification_status",
            outcome = EventOutcome.FAILED,
            reasonCode = DiagnosticReasonCode.SIDE_EFFECT_FAILED,
            metadata = safeMetadata("rawId" to rawId)
        )
    }
}
```

But do not mark processed before all required DB writes complete.

Suggested mapping:

| Pipeline result | Mark raw processed? |
|---|---:|
| `AutoAccepted` | yes |
| `NeedsReview` | yes |
| `ParserFailed` with raw row | yes |
| `AutoRejected` with raw row | yes |
| `Dropped` before raw insert | no raw row |
| `Duplicate` existing row | no new row; optional no-op |
| `Error` before terminal write | no |
| `Error` after raw insert but before expense/review | no unless explicitly terminal/final |

Conservative rule:

```text
Only mark processed after the returned outcome is terminal and expected.
Do not mark processed for retryable errors.
```

---

## Step 2.4 — Avoid using `isProcessed` for recovery

Search:

```bash
grep -R "isProcessed" app/src/main/java
```

Rules:

- It may be displayed/debugged.
- It may be used in raw history filters.
- It must not be used to resume processing.
- It must not replace durable intake status.

If any recovery code uses it, replace that logic with explicit TODO or intake status.

---

## Step 2.5 — Optional alternative if durable intake lands first

If P1-P1-07 lands before this PR, prefer:

1. Mark `RawNotification.isProcessed` as deprecated.
2. Stop displaying it as processing truth.
3. Use `NotificationIntakeStatus` instead.
4. Plan a later migration to remove the column.

KDoc:

```kotlin
@Deprecated("Use NotificationIntakeEntity.status for processing state.")
```

But do not remove the column in the same PR unless you are ready for a Room migration and all query updates.

---

## PR 2 tests

1. Auto-accepted raw row becomes `isProcessed = true`.
2. Needs-review raw row becomes `isProcessed = true`.
3. Parser-failed final raw row becomes `isProcessed = true`.
4. Duplicate does not create/modify a new row.
5. Retryable error does not mark processed.
6. Mark-processed failure emits diagnostic but does not roll back user-facing terminal result.
7. No recovery code uses `isProcessed` as source of truth.

## PR 2 acceptance criteria

- `isProcessed` is either maintained or formally deprecated.
- If maintained, terminal raw rows are marked processed.
- Retryable/unfinished rows are not marked processed.
- KDoc explains exact semantics.
- Tests prove behavior.

---

# PR 3 — P1-NEW-15: Extras JSON materialized when raw storage disabled

## Current problem

Current listener path builds extras JSON before applying raw-storage mode:

```kotlin
val extrasJson = buildExtrasJson(extras)
```

Then storage mode may later null/redact persisted extras.

Problem:

Even if not persisted, the app still materializes potentially sensitive extras into a JSON string in memory.

This violates the stronger privacy expectation:

```text
If raw storage is disabled, do not serialize raw extras at all.
```

## Goal

Create a storage-mode-aware extras persistence policy:

```text
STORE_RAW:
    may serialize extras

STORE_REDACTED:
    do not serialize raw extras; store redacted marker or safe metadata only

STORE_METADATA_ONLY:
    do not serialize extras; null or safe marker

DO_NOT_STORE:
    do not serialize extras; null
```

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `RawContentSanitizer.kt`
- notification persistence mapper if already extracted
- `RawNotification.kt` usage sites

New file recommended:

```text
domain/notification/NotificationExtrasPersistencePolicy.kt
```

Tests:

```text
NotificationExtrasPersistencePolicyTest.kt
NotificationCaptureServiceExtrasPrivacyTest.kt
```

---

## Step 3.1 — Add extras persistence policy

Create:

```kotlin
class NotificationExtrasPersistencePolicy @Inject constructor(
    private val extrasJsonBuilder: NotificationExtrasJsonBuilder
) {
    fun buildExtrasForStorage(
        extras: Bundle,
        rawStorageMode: RawStorageMode
    ): String? {
        return when (rawStorageMode) {
            RawStorageMode.STORE_RAW -> extrasJsonBuilder.buildRaw(extras)

            RawStorageMode.STORE_REDACTED -> {
                // Important: do not enumerate raw values.
                """{"redacted":true}"""
            }

            RawStorageMode.STORE_METADATA_ONLY -> null

            RawStorageMode.DO_NOT_STORE -> null
        }
    }
}
```

If enum names differ, adapt.

Important:

- `STORE_REDACTED` should not call the raw builder and then redact.
- It should avoid iterating through raw values unless you have a key-only allowlist.

---

## Step 3.2 — Split raw extras builder from policy

If `buildExtrasJson()` currently lives in `NotificationCaptureService`, move it to:

```text
NotificationExtrasJsonBuilder.kt
```

API:

```kotlin
class NotificationExtrasJsonBuilder @Inject constructor() {
    fun buildRaw(extras: Bundle): String?
}
```

This method is allowed to inspect extras, but only called under `STORE_RAW`.

Add KDoc:

```text
Do not call buildRaw unless RawStorageMode.STORE_RAW permits raw extras persistence.
```

---

## Step 3.3 — Fetch storage mode before extras serialization

In service flow, current order likely:

```text
extract extras
build extrasJson
read privacy settings / storage mode
create storage notification
```

Change to:

```text
1. privacy/capture gate allowed
2. read privacy settings / raw storage mode
3. extract text needed for processing
4. call extras policy:
   - raw mode => serialize extras
   - non-raw modes => do not serialize
5. build processing/storage notifications
```

Pseudo:

```kotlin
val settings = privacySettingsRepository.getSettings()
val rawStorageMode = settings.rawNotificationStorageMode

val extrasJson = extrasPersistencePolicy.buildExtrasForStorage(
    extras = extras,
    rawStorageMode = rawStorageMode
)
```

For non-raw modes, this must not call `buildRaw`.

---

## Step 3.4 — Do not put raw extras into processing object unless needed

Question:

```text
Does parser/pipeline actually need extrasJson?
```

If no, then:

- `processingNotification.extrasJson` should also be null unless raw mode allows.
- Parser should use title/text/body fields, not extras JSON.

Recommended:

```kotlin
val processingNotification = RawNotification(
    ...
    extrasJson = null // or extrasJson only if parser truly needs it
)
```

Storage notification:

```kotlin
val storageNotification = processingNotification.copy(
    title = sanitizedTitle,
    text = sanitizedText,
    bigText = sanitizedBody,
    extrasJson = extrasJson
)
```

If some downstream parser needs extras JSON, that dependency should be explicitly reviewed and privacy-gated.

---

## Step 3.5 — Redacted mode policy

Avoid this anti-pattern:

```kotlin
val rawJson = buildExtrasJson(extras)
val redactedJson = sanitizer.redact(rawJson)
```

Correct:

```kotlin
RawStorageMode.STORE_REDACTED -> """{"redacted":true}"""
```

Optional safe metadata:

```json
{
  "redacted": true,
  "hasExtras": true
}
```

Do not include keys unless you are sure keys cannot leak sensitive content. Many notification extra keys are standard, but OEM/app-specific keys can be revealing.

---

## Step 3.6 — Batch/public repository paths

Audit non-service paths:

```bash
grep -R "extrasJson" app/src/main/java
grep -R "buildExtrasJson" app/src/main/java
```

Rules:

- any path that constructs raw notification from Android extras must use `NotificationExtrasPersistencePolicy`;
- repository-only paths that receive already-built `RawNotification` should not serialize extras themselves;
- batch path should not assume raw extras are safe.

---

## Step 3.7 — Diagnostics

Add safe diagnostic metadata when extras are suppressed:

```text
extrasStorage = RAW / REDACTED_MARKER / SUPPRESSED_METADATA_ONLY / SUPPRESSED_DO_NOT_STORE
```

Do not include extras JSON or raw keys.

---

## PR 3 tests

### Policy tests

Use fake builder that records calls.

1. `STORE_RAW`:
   - builder called once;
   - raw JSON returned.

2. `STORE_REDACTED`:
   - builder not called;
   - returns redacted marker or null.

3. `STORE_METADATA_ONLY`:
   - builder not called;
   - returns null.

4. `DO_NOT_STORE`:
   - builder not called;
   - returns null.

### Service tests

1. `DO_NOT_STORE`:
   - no extras JSON materialized;
   - persisted raw notification has `extrasJson = null`.

2. `STORE_METADATA_ONLY`:
   - no extras JSON materialized;
   - persisted extras null.

3. `STORE_REDACTED`:
   - raw builder not called;
   - persisted marker contains no raw values.

4. `STORE_RAW`:
   - raw builder called;
   - persisted extras allowed.

5. Parser still receives required title/text/body for processing.

6. Pending review/diagnostics do not receive raw extras.

---

## PR 3 acceptance criteria

- Extras JSON builder is never called unless raw storage mode allows it.
- `STORE_REDACTED` does not build raw JSON and then redact.
- `STORE_METADATA_ONLY` and `DO_NOT_STORE` persist no extras JSON.
- Processing still works from extracted title/text/body.
- Tests prove builder call count per mode.

---

# Recommended implementation order

1. **PR 3 first if privacy risk is priority**
   - It is the most privacy-sensitive of these three.

2. **PR 1 second**
   - Makes duplicate handling safer and helps later dedupe/intake work.

3. **PR 2 third**
   - Lowest urgency unless UI/debug/recovery currently reads `isProcessed`.

Alternative order if you are already working in pipeline insert code:

1. PR 1 — typed insert result
2. PR 2 — processed flag
3. PR 3 — extras policy

---

# Tracker update after PRs

After PR 1:

| ID | New status |
|---|---:|
| P1-NEW-13 | Fixed |

After PR 2:

| ID | New status |
|---|---:|
| P1-NEW-14 | Fixed or Deprecated by design |

After PR 3:

| ID | New status |
|---|---:|
| P1-NEW-15 | Fixed |

For P1-NEW-14, use:

```text
Fixed
```

if `isProcessed` is now maintained.

Use:

```text
Deprecated / superseded by intake status
```

if durable intake has landed and `NotificationIntakeStatus` is now the source of truth.

---

# Final validation commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Search checks:

```bash
grep -R "== -1L" app/src/main/java
grep -R "insertOrIgnore" app/src/main/java
grep -R "isProcessed" app/src/main/java
grep -R "buildExtrasJson" app/src/main/java
grep -R "extrasJson" app/src/main/java/com/yourname/expensetracker/service
```

Expected after all PRs:

- only insert-result helper knows about `insertOrIgnore == -1L`;
- pipeline uses `RawNotificationInsertResult`;
- `isProcessed` has clear semantics and tests, or is deprecated;
- extras JSON is not built for redacted/metadata-only/do-not-store modes;
- no raw extras appear in diagnostics or pending reviews.