# Deep Evaluation / Debugging Report — commit `6825e6947346251a25289b2ea8f39e6c1b9feca5`

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/6825e6947346251a25289b2ea8f39e6c1b9feca5

## Executive verdict

This commit fixes several important items, but the remaining privacy/refactor work is **not fully closed**.

### Confirmed improvements
- Receipt-assist image upload is now mostly policy-owned through `CloudPayloadPolicy.prepareReceiptAssist(...)`.
- `CloudReceiptAssistService` no longer reads the receipt image file directly in the normal provider path.
- `ReceiptLifecycleCoordinator` now passes `correlationId` into its own email transaction side-effect dispatch.
- `CloudReceiptItemCategorizationService` cleanup looks done.
- `DataRetentionWorker` now gives `ai_chat_messages` a 30-day cutoff instead of passing `now`.
- `PrivacySettingsLoadState` / `FAIL_CLOSED_DEFAULTS` exist.

### Still not done
The largest remaining problems are:

1. **Email side effects are still duplicated and one path still drops correlation.**
2. **Email retention likely fails or corrupts reads because it sets non-null entity columns to `NULL`.**
3. **Email hash columns are still missing; hash values are still stored in raw-named fields.**
4. **Settings update after corruption can accidentally persist unsafe normal defaults.**
5. **`CloudCategorizationAssistService` still does an empty-prompt policy probe.**
6. **Retention registry remains incomplete and AI purge counts remain inaccurate.**
7. **Notification blocked-package cache is initially fail-open.**
8. **Static guard is still heuristic and misses important privacy regressions.**

---

# 1. Recent fix status matrix

| Area | Status | Notes |
|---|---:|---|
| Email coordinator side-effect correlation | Partial | Coordinator path fixed, ingestion path still dispatches again without correlation |
| Receipt image policy ownership | Mostly fixed | Provider uses prepared image bytes, but policy still lacks path/mime validation and image hash provenance |
| AI chat retention cutoff | Fixed locally | `ai_chat_messages` now gets 30-day cutoff |
| Email retention deletes rows | Improved | It now redacts instead of deletes, but redaction query likely fails because columns are non-null |
| PrivacySettings fail-closed load state | Mostly present | But `updateSettings()` has a corruption-state bug |
| Cloud item categorization dead branch | Fixed | Receipt item categorization cleanup appears done |
| Cloud categorization assist probe | Not fixed | Still calls `prepareText(..., "")` as a policy probe |
| Static guard | Partial | Still weak and bypassable |
| Behavioral tests | Not evidenced | Commit changed 6 files, no tests visible in commit diff |

---

# 2. High-priority issues found

## PRIV-6825-01 — Email side effects are duplicated and one path still lacks correlation

Severity: **High**  
Type: **actual functional + traceability bug**  
User impact: possible duplicate side effects, duplicate notifications/jobs, bad audit traceability.

### Evidence

In `ReceiptLifecycleCoordinator.processEmailReceipt(...)`, post-commit transaction side effects are now called with correlation:

```kotlin
dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT, correlationId ?: "")
```

That part is good.

But `EmailReceiptIngestionService.processEmailReceipt(...)` still loops over `coordinatorResult.expenseIds` and calls:

```kotlin
coordinator.dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT)
```

without correlation.

This means the flow can do:

1. Coordinator creates expense with `SideEffectMode.DEFER`.
2. Coordinator dispatches post-creation side effects after transaction.
3. Ingestion service receives success and dispatches post-creation side effects again.
4. The second dispatch lacks `correlationId`.

### Why this matters

This is worse than just missing correlation. It can cause duplicate downstream effects.

Possible duplicates:
- budget recalculation
- category learning
- notification scheduling
- warranty/receipt follow-up jobs
- analytics/audit events

### Fix strategy

Use only one owner for post-create transaction side effects.

Recommended:
- `ReceiptLifecycleCoordinator` should own the side-effect dispatch.
- `EmailReceiptIngestionService` should remove the success-loop dispatch entirely.

### Implementation

In `EmailReceiptIngestionService`, remove:

```kotlin
for (expenseId in coordinatorResult.expenseIds) {
    coordinator.dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT)
}
```

If you intentionally want ingestion to dispatch instead, then remove the coordinator dispatch and pass `correlationId`. But do **not** keep both.

### Tests

Add:

```text
email_ingestion_does_not_dispatch_transaction_side_effects_twice
email_side_effect_dispatch_has_email_correlation
email_coordinator_is_single_owner_of_email_side_effects
```

---

## PRIV-6825-02 — Email retention redaction likely fails because entity columns are non-null

Severity: **High**  
Type: **actual runtime bug / retention bug**

### Evidence

`EmailReceiptSource` defines:

```kotlin
val emailSender: String
val emailSubject: String
```

These are non-null Kotlin fields. Room normally maps these to `NOT NULL` columns.

But `EmailReceiptDao.redactSensitiveFieldsOlderThan(...)` runs an update that sets:

```sql
emailSender = NULL,
emailSubject = NULL,
emailMessageId = NULL
```

### Why this matters

Two bad outcomes are possible:

1. If the DB schema has `NOT NULL`, the update fails with a constraint error.
2. If the DB schema allows nulls despite the Kotlin entity, Room may crash or misread rows later because it expects non-null `String`.

Either way, email retention is not safe.

### Fix strategy

Choose one of two designs.

#### Option A — nullable fields

Make these nullable:

```kotlin
val emailSender: String?
val emailSubject: String?
val emailMessageId: String?
```

Then add a migration that makes the DB columns nullable if needed.

#### Option B — redacted placeholders

Keep non-null columns, but update to:

```sql
emailSender = '[REDACTED]',
emailSubject = '[REDACTED]',
emailMessageId = NULL
```

Option A is better for privacy and matches the plan.

### Tests

```text
retention_email_redacts_without_sql_constraint_failure
retention_email_rows_can_be_read_after_redaction
retention_email_preserves_receipt_link_after_redaction
```

---

## PRIV-6825-03 — Email hash columns are still missing; hash is still stored in raw-named `emailMessageId`

Severity: **High**  
Type: **privacy architecture gap with user-impact risk**

### Evidence

`EmailReceiptSource` still has only:

```kotlin
emailMessageId: String?
fingerprint: String
```

There are no explicit columns for:

```text
emailMessageIdHash
contentFingerprintHash
providerOrderIdHash
```

In `ReceiptLifecycleCoordinator`, restricted modes store `messageIdHash` into `emailMessageId`.

### Why this matters

This keeps the old semantic problem:
- future code may assume `emailMessageId` is plaintext,
- retention nulls `emailMessageId`, thereby deleting the dedupe hash,
- export/debug code cannot reliably distinguish raw ID from hash,
- the architecture docs say hashes must live in explicit hash fields.

### Fix strategy

Add explicit hash columns:

```kotlin
val emailMessageIdHash: String?
val contentFingerprintHash: String?
val providerOrderIdHash: String?
```

Then use:

```text
emailMessageId = raw only in STORE_RAW
emailMessageIdHash = HMAC hash in all modes where dedupe is allowed
contentFingerprintHash = hash of content fingerprint
providerOrderIdHash = hash of provider order id
```

Do **not** store hash values in `emailMessageId`.

### Migration

Add columns:

```sql
ALTER TABLE email_receipt_sources ADD COLUMN emailMessageIdHash TEXT;
ALTER TABLE email_receipt_sources ADD COLUMN contentFingerprintHash TEXT;
ALTER TABLE email_receipt_sources ADD COLUMN providerOrderIdHash TEXT;

CREATE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageIdHash
ON email_receipt_sources(emailMessageIdHash);
```

Migration backfill:
- If `emailMessageId` looks like existing hash format and privacy mode was restricted, move it to `emailMessageIdHash` and set `emailMessageId = NULL`.
- If impossible to distinguish, leave old rows but enforce new rows going forward.

### Tests

```text
email_metadata_only_stores_message_id_hash_column_not_raw_message_id
email_do_not_store_keeps_hash_but_nulls_raw_message_id
email_retention_preserves_message_id_hash
email_retention_nulls_raw_message_id_only
email_duplicate_by_message_id_hash_after_retention
```

---

## PRIV-6825-04 — `PrivacySettingsRepositoryImpl.updateSettings()` can resurrect unsafe defaults after corruption

Severity: **High**  
Type: **actual privacy bug**

### Evidence

Fail-closed load state exists. However, in `updateSettings()`:

```kotlin
val current = prefs.toPrivacySettings()
val updated = transform(current)
```

If the DataStore contains only the corruption sentinel, `toPrivacySettings()` returns normal default values:

```text
notificationCaptureEnabled = true
rawNotificationStorageMode = STORE_RAW
rawOcrStorageMode = STORE_RAW
emailReceiptStorageMode = STORE_REDACTED
```

So after corruption, the UI can transform from unsafe normal defaults instead of from `FAIL_CLOSED_DEFAULTS`.

### Example failure

1. DataStore corrupts.
2. Repository emits fail-closed settings.
3. User changes one privacy setting in UI.
4. `updateSettings()` uses `prefs.toPrivacySettings()`, not `prefs.toLoadState().settings`.
5. The persisted result can silently restore:
   - notification capture enabled,
   - raw notification storage,
   - raw OCR storage.

### Fix strategy

Use load-state settings as the update base:

```kotlin
val current = prefs.toLoadState().settings()
val updated = transform(current)
```

Also consider blocking normal updates from `CorruptedFailClosed` until the user explicitly confirms reset/recreate settings.

### Tests

```text
privacy_update_from_corrupted_state_uses_fail_closed_base
privacy_update_after_corruption_does_not_reenable_notification_capture
privacy_update_after_corruption_does_not_restore_store_raw_modes
privacy_update_after_corruption_requires_explicit_reset
```

---

## PRIV-6825-05 — `CloudCategorizationAssistService` still has an empty-prompt policy probe

Severity: **Medium**  
Type: **cloud audit/provenance bug / architecture drift**

### Evidence

`CloudCategorizationAssistService` still does:

```kotlin
cloudPayloadPolicy.prepareText(CloudPayloadPurpose.ITEM_CATEGORIZATION, "")
```

then separately prepares the real prompt.

### Why this matters

Even if not used to build the final request, this is still bad:
- creates fake/empty prepared payload decisions,
- can create misleading audit/provenance,
- reintroduces the old “policy as boolean probe” pattern,
- static guard does not catch it.

### Fix

Remove the empty-prompt call entirely.

Use only:

```kotlin
val rawPrompt = buildRawPrompt(input)
val prepared = cloudPayloadPolicy.prepareText(CloudPayloadPurpose.ITEM_CATEGORIZATION, rawPrompt)
```

### Tests

```text
cloud_categorization_assist_does_not_prepare_empty_prompt
cloud_categorization_assist_prepares_exactly_one_payload
```

---

## PRIV-6825-06 — Receipt image policy is improved but still needs boundary hardening

Severity: **Medium**  
Type: **privacy/security hardening**

### What is fixed

Good:
- provider calls `prepareReceiptAssist(...)`,
- provider uses `prepared.imageBytes`,
- provider no longer reads image file directly,
- image is suppressed when redaction is required.

### Remaining risks

`DefaultCloudPayloadPolicy.prepareReceiptAssist(...)` reads from `java.io.File(imagePath)` if:
- image upload is allowed,
- redaction is not required,
- file exists,
- file size <= 2 MB.

Missing checks:
- MIME allowlist,
- file/source ownership validation,
- safe path validation,
- combined text+image payload hash,
- image hash in audit metadata,
- suppression reason in audit metadata.

### Why this matters

Since `CloudPayloadPolicy` is now the trusted boundary, it must validate the image path and MIME type, not just trust caller input.

### Fix strategy

Add:

```kotlin
private val allowedReceiptImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
```

Add either:
- a `ReceiptImagePayloadSource` abstraction from `ReceiptAssetStore`, or
- validation that `imagePath` is inside the app-owned receipt asset directory.

Compute:

```text
textHash
imageHash
combinedPayloadHash
```

Audit:

```text
imageIncluded
imageSuppressedReason
imageMimeType
imageSizeBytes
imageHash
```

### Tests

```text
receipt_assist_rejects_unsupported_image_mime
receipt_assist_rejects_non_app_owned_image_path
receipt_assist_payload_hash_changes_when_image_changes
receipt_assist_audit_records_image_suppression_reason
```

---

## PRIV-6825-07 — Notification blocked-package cache is initially fail-open

Severity: **Medium/High**  
Type: **privacy bug edge case**

### Evidence

`blockedPackagesCache` starts as:

```kotlin
emptySet()
```

The observer fills it asynchronously.

Normal and refresh paths check `isPackageBlockedFast(packageName)` before extras extraction. That is good only **after** the cache has loaded.

### Failure mode

If a notification arrives before the blocked-package flow emits:
- cache is empty,
- blocked package check passes,
- extras/text can be read.

### Fix strategy

Track cache load state:

```kotlin
@Volatile private var blockedPackageCacheLoaded = false
```

On observer emission:

```kotlin
blockedPackagesCache = packages.toSet()
blockedPackageCacheLoaded = true
```

Before extraction:

```kotlin
if (!blockedPackageCacheLoaded) {
    drop or delay notification fail-closed
}
```

Alternative:
- synchronously load blocked packages before accepting capture,
- or run a suspend pre-extraction gate inside the work tracker before reading extras.

### Tests

```text
notification_before_blocked_cache_load_does_not_read_extras
refresh_before_blocked_cache_load_does_not_read_extras
blocked_cache_observer_failure_keeps_fail_closed_state
```

---

## PRIV-6825-08 — Retention registry remains incomplete and counts remain inaccurate

Severity: **Medium/High**  
Type: **privacy retention gap**

### Evidence

Current registered targets include:
- raw notifications,
- scanned receipt raw OCR text,
- AI artifacts,
- AI chat messages,
- email receipt sources.

Still missing or not evidenced:
- `scanned_receipts.parsedItems`,
- debug exports,
- parser debug artifacts,
- bank statement debug/import artifacts,
- pipeline diagnostic metadata retention/redaction,
- operation run metadata retention/redaction,
- privacy audit context retention/redaction,
- cloud call audit/payload artifacts.

Also, AI targets still return `0` counts because DAO delete methods do not return affected row count.

### Fix strategy

Move from string-name cutoff switching to target-owned policy:

```kotlin
interface RetentionTarget {
    val name: String
    suspend fun cutoff(now: Long, settings: PrivacySettings): Long
    suspend fun purge(cutoffMs: Long): RetentionPurgeResult
}
```

Then each target owns its retention semantics.

Update DAO delete methods to return `Int`.

### Tests

```text
retention_registry_contains_all_sensitive_targets
retention_ai_artifacts_reports_actual_deleted_count
retention_ai_chat_messages_reports_actual_deleted_count
retention_parsed_items_purged_or_redacted
retention_debug_exports_purged
retention_bank_statement_debug_artifacts_purged
```

---

## PRIV-6825-09 — Static guard still misses important patterns

Severity: **Medium**  
Type: **regression risk**

### Remaining weaknesses

The guard still:
- allows provider `Request.Builder()` if nearby context contains “prepared”,
- only checks allow-all gates with a small local text window,
- does not catch `hashCode()` fallback in email fingerprint code,
- does not catch duplicate side-effect dispatch,
- does not catch empty-prompt `prepareText(..., "")`,
- does not enforce explicit email hash columns.

### Fix strategy

Add new rules:

```text
G12: No cloudPayloadPolicy.prepareText(..., "") in provider main source.
G13: No dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT) without correlationId.
G14: No .hashCode() fallback in email/receipt fingerprint code.
G15: EmailReceiptSource must define explicit hash columns.
G16: Email retention must not set non-null entity fields to NULL.
```

Best long-term:
- introduce `CloudAiTransport.postPrepared(...)`,
- forbid `Request.Builder` directly in providers.

---

## PRIV-6825-10 — Raw sensitive values still appear in logs/exceptions

Severity: **Medium**  
Type: **diagnostics privacy bug**

Examples seen:
- email ingestion logs provider detection with raw sender,
- coordinator insert failure message includes raw sender.

### Fix strategy

Use hashes or safe metadata only:

```kotlin
.putHashed("sender", sender)
```

Avoid:

```text
sender=$sender
```

in exception/log messages.

### Tests

```text
email_insert_failure_does_not_include_raw_sender
email_provider_detection_log_uses_hashed_sender
diagnostics_do_not_include_raw_email_sender_subject_body
```

---

# 3. What is actually fixed vs still open

## Fixed or mostly fixed

### Receipt image provider ownership
Status: **mostly fixed**

The provider now uses `PreparedCloudPayload.imageBytes`. This resolves the main previous leak where the provider read the image file directly after text preparation.

Remaining hardening is needed in the policy itself.

### Notification refresh order
Status: **fixed for normal loaded-state case**

Refresh now checks restore/shutdown/privacy/package before extras extraction.

Remaining edge case: blocked-package cache initial load.

### AI chat retention cutoff
Status: **fixed for `ai_chat_messages`**

The worker now uses a 30-day cutoff for that target.

Remaining issue: target-owned retention policy should replace string-name switching.

### Fail-closed load-state existence
Status: **mostly present**

`PrivacySettingsLoadState` and `FAIL_CLOSED_DEFAULTS` exist.

Remaining issue: `updateSettings()` should not transform from normal defaults after corruption.

---

# 4. Bug vs architecture classification

## Actual user-impacting bugs

1. Duplicate email side-effect dispatch.
2. Email side-effect dispatch still missing correlation in ingestion.
3. Email retention likely fails due nulling non-null fields.
4. Email retention deletes dedupe hash because hash is stored in `emailMessageId`.
5. Settings update after corruption can persist unsafe defaults.
6. Blocked-package notification can leak during startup before cache load.

## Privacy architecture gaps

1. Email path is still not payload-first.
2. Explicit email hash columns are missing.
3. Cloud image policy lacks path/mime/hash hardening.
4. Retention target contract is still string-name based.
5. Static guard is not authoritative.
6. Cloud audit/provenance is incomplete.

## Cleanup / technical debt

1. Remove empty-prompt cloud policy probe.
2. Remove raw sensitive values from logs/exceptions.
3. Remove `hashCode()` fallback in email fingerprinting.
4. Add behavior tests for actual services, not only models.

---

# 5. Recommended next PR sequence

## PR 1 — Email correctness and hash schema

Fixes:
- `PRIV-6825-01`
- `PRIV-6825-02`
- `PRIV-6825-03`
- `PRIV-6825-10`

Tasks:
1. Remove duplicate side-effect dispatch from `EmailReceiptIngestionService`.
2. Ensure coordinator is the only owner of email transaction side effects.
3. Add explicit hash columns to `EmailReceiptSource`.
4. Make sender/subject nullable or use redacted placeholders consistently.
5. Change retention query to preserve hash columns.
6. Remove raw sender from logs/exceptions.
7. Remove raw fallback from `messageIdHash`.

Acceptance:
```text
Email side effects dispatch exactly once.
All email side effects carry correlationId.
Restricted modes store hash columns, not raw message ID.
Retention can redact email rows without crashing.
Retention preserves dedupe hashes.
```

## PR 2 — Privacy settings update-from-corruption safety

Fixes:
- `PRIV-6825-04`

Tasks:
1. Change update base to `prefs.toLoadState().settings()`.
2. Add explicit reset-from-corruption UX or API.
3. Add corruption update regression tests.

Acceptance:
```text
Updating one setting after corruption never restores STORE_RAW or notification capture.
```

## PR 3 — Notification pre-extraction fail-closed cache

Fixes:
- `PRIV-6825-07`

Tasks:
1. Add `blockedPackageCacheLoaded`.
2. Fail closed before extraction until loaded.
3. Add trap-extras tests for startup race.

Acceptance:
```text
Blocked package extras are not read before cache load.
```

## PR 4 — Retention target contract and registry expansion

Fixes:
- `PRIV-6825-08`

Tasks:
1. Move cutoff logic into each target.
2. Return actual DAO affected counts.
3. Add missing sensitive targets.
4. Remove string-name cutoff switch.

Acceptance:
```text
Every sensitive artifact class is registered.
Every target uses correct cutoff.
Counts are accurate.
```

## PR 5 — Cloud cleanup and guard hardening

Fixes:
- `PRIV-6825-05`
- `PRIV-6825-06`
- `PRIV-6825-09`

Tasks:
1. Remove empty-prompt `prepareText`.
2. Validate image MIME/path in policy.
3. Add combined text+image payload hash.
4. Add guard rules G12-G16.
5. Prefer `CloudAiTransport.postPrepared(...)`.

Acceptance:
```text
No empty prompt policy probes.
No provider direct post can bypass prepared payload.
Receipt image uploads are path/mime/hash audited.
```

---

# 6. Test plan

Add or upgrade these tests:

```text
EmailReceiptIngestionServiceCorrelationTest
EmailReceiptRetentionIntegrationTest
PrivacySettingsCorruptionUpdateTest
NotificationCaptureStartupPrivacyTest
RetentionRegistryIntegrationTest
CloudReceiptAssistImagePolicyTest
PrivacyGuardScriptTest
```

Required sentinel tests:

```text
SECRET_EMAIL_SENDER
SECRET_EMAIL_SUBJECT
SECRET_EMAIL_BODY
<secret-message-id@example.com>
SECRET_ITEM_DESCRIPTION
SECRET_NOTIFICATION_TEXT
SECRET_RECEIPT_IMAGE_BYTES
```

Assertions:
- no sentinel in restricted persisted rows,
- no duplicate side-effect dispatch,
- correlation preserved,
- retention does not crash,
- hashes survive retention,
- image suppressed when required,
- no cloud request includes raw text outside prepared payload.

---

# 7. Updated acceptance matrix

| Criterion | Status after `6825e69` |
|---|---:|
| Email side effects correlated | Partial |
| Email side effects single-dispatch | Not done |
| Email persistence payload-first | Not done |
| Explicit email hash columns | Not done |
| Email retention redacts not deletes | Partial, likely broken by null/non-null mismatch |
| Email dedupe hash survives retention | Not done |
| Privacy fail-closed load state exists | Mostly |
| Privacy update from corrupted state safe | Not done |
| Receipt image provider no direct file read | Mostly done |
| Receipt image policy validates path/mime/hash | Not done |
| Notification refresh pre-extraction order | Mostly done |
| Notification blocked cache startup fail-closed | Not done |
| AI chat cutoff | Done |
| Retention registry complete | Not done |
| AI retention counts accurate | Not done |
| Static guard strong | Partial |
| Behavioral tests | Not evidenced |

---

# 8. Highest-priority action list

Do these first:

1. Remove duplicate email side-effect dispatch from `EmailReceiptIngestionService`.
2. Add explicit email hash columns and stop storing hash in `emailMessageId`.
3. Fix email retention nullability or use redacted placeholders.
4. Fix `updateSettings()` to transform from load-state settings.
5. Add blocked-package cache loaded fail-closed state.
6. Remove `prepareText(..., "")` from `CloudCategorizationAssistService`.
7. Add tests that would have caught these exact bugs.

---

# Sources reviewed

- Commit diff:  
  https://github.com/panospao7/Cost-agregator/commit/6825e6947346251a25289b2ea8f39e6c1b9feca5

- `CloudReceiptAssistService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `DefaultCloudPayloadPolicy.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadPolicy.kt

- `CloudPayloadPolicy.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadPolicy.kt

- `DataRetentionWorker.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `RetentionModule.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt

- `PrivacySettings.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt

- `PrivacySettingsRepositoryImpl.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `ReceiptLifecycleCoordinator.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `EmailReceiptIngestionService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `EmailReceiptSource.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt

- `EmailReceiptDao.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt

- `NotificationCaptureService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `CloudCategorizationAssistService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt

- `verify_privacy_boundaries.py`:  
  https://github.com/panospao7/Cost-agregator/blob/6825e6947346251a25289b2ea8f39e6c1b9feca5/scripts/verify_privacy_boundaries.py