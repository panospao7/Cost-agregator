Here is the detailed implementation plan for the remaining privacy/raw-storage issues.

<remaining_privacy_plan_after_4b4b5f7.md>
# Remaining Privacy / Raw-Storage / Redaction Implementation Plan

Target commit: `4b4b5f7a223fe44df0e39be72cca8490b0e45297`

Scope: finish the remaining issues from the Universal Issue #3 privacy/raw-storage/redaction pass.

---

## 0. Remaining issue summary

### High priority

```text
PRIV-4B-01 SafePrivacyMetadata.put() can still bypass hash-key validation.
```

### Medium/high priority

```text
PRIV-4B-02 Raw-storage audit is still model/synthetic, not end-to-end.
PRIV-4B-03 Corruption fail-closed path needs real DataStore integration coverage.
PRIV-4B-04 Static privacy guard is heuristic and needs behavior-test backing.
```

---

# PR 1 — Make SafePrivacyMetadata hash-key safe through every API

## Issue fixed

```text
PRIV-4B-01
```

## Problem

`putHash()` is now safer, but normal `put()` can still be misused:

```kotlin
SafePrivacyMetadata.builder()
    .put("messageIdHash", "plaintext-message-id")
    .put("providerTransactionIdHash", "raw-provider-id")
```

If the plaintext value does not match token/path/account patterns, it can pass.

This means `SafePrivacyMetadata` is not fully safe by construction.

## Files

```text
app/src/main/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadata.kt
app/src/main/java/com/yourname/expensetracker/domain/privacy/EventMetadataSanitizer.kt // if shared
app/src/test/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadataValueSafetyTest.kt
```

## Implementation steps

### 1. Add key classification helpers

In `SafePrivacyMetadata` or a shared privacy sanitizer:

```kotlin
fun canonicalizeKey(key: String): String =
    key.lowercase().replace(Regex("[^a-z0-9]"), "")

fun isApprovedHashKey(key: String): Boolean =
    canonicalizeKey(key) in APPROVED_HASH_KEYS

fun isHashLikeKey(key: String): Boolean {
    val canonical = canonicalizeKey(key)
    return canonical.endsWith("hash") || canonical.endsWith("idhash")
}

fun isHashLikeValue(value: Any?): Boolean =
    value is String && Regex("^[a-fA-F0-9]{8,128}$").matches(value)
```

Approved hash keys should include only intentionally safe keys:

```text
sourceIdHash
notificationKeyHash
packageHash
messageIdHash
providerTransactionIdHash
accountIdHash
counterpartyHash
contentFingerprintHash
providerOrderIdHash
payloadHash
externalHash
backupHash
assetRelativePathHash
```

### 2. Make `put()` hash-key aware

In builder `put(key, value)`:

```kotlin
val canonical = canonicalizeKey(key)

when {
    isApprovedHashKey(key) -> {
        values[key] = if (isHashLikeValue(value)) value else REDACTED
    }

    isHashLikeKey(key) -> {
        values[key] = REDACTED
    }

    isDangerousKey(key) -> {
        values[key] = REDACTED
    }

    else -> {
        values[key] = sanitizeValue(value)
    }
}
```

Rules:

```text
Approved hash key + valid hash value -> allowed.
Approved hash key + plaintext value -> redacted.
Unknown hash-like key -> redacted.
Dangerous key -> redacted.
Benign key -> value-level sanitized.
```

### 3. Make `merge()` and `fromMap()` revalidate hash keys

Any path that combines metadata must run through the same `put()` logic.

Do not directly copy internal maps.

```kotlin
fun merge(other: SafePrivacyMetadata): SafePrivacyMetadata {
    val builder = builder()
    this.values.forEach { builder.put(it.key, it.value) }
    other.values.forEach { builder.put(it.key, it.value) }
    return builder.build()
}
```

### 4. Make `toJson()` final-pass safe

Before serialization, run final validation:

```kotlin
values.mapValues { (key, value) -> sanitizeByKey(key, value) }
```

This prevents unsafe values introduced by future constructors.

## Tests

```text
safe_metadata_put_message_id_hash_plaintext_is_redacted
safe_metadata_put_provider_transaction_id_hash_plaintext_is_redacted
safe_metadata_put_account_id_hash_plaintext_is_redacted
safe_metadata_put_approved_hash_key_with_hex_value_allowed
safe_metadata_put_unknown_hash_key_redacted
safe_metadata_put_raw_text_hash_redacted
safe_metadata_merge_revalidates_hash_keys
safe_metadata_to_json_revalidates_hash_keys
safe_metadata_put_benign_key_with_sensitive_value_redacted
```

## Acceptance criteria

```text
1. No public SafePrivacyMetadata API can store plaintext under a hash-key name.
2. Unknown *Hash keys are redacted unless explicitly approved.
3. merge/toJson cannot reintroduce unsafe hash-key values.
```

---

# PR 2 — Real DataStore corruption fail-closed integration test

## Issue fixed

```text
PRIV-4B-03
```

## Problem

The corruption logic is improved with a sentinel, but current tests mostly use fake or model-level repositories. Need proof that production DataStore corruption actually results in:

```text
PrivacySettingsLoadState.CorruptedFailClosed
PrivacySettings.FAIL_CLOSED_DEFAULTS
```

## Files

```text
app/src/test/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImplCorruptionTest.kt
app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
```

## Implementation steps

### 1. Create real temporary DataStore test

Use a temp file/folder and production serializer/preferences setup.

For Preferences DataStore, create a corrupt preferences file or inject a test `ReplaceFileCorruptionHandler`.

Goal: trigger the real corruption handler:

```kotlin
ReplaceFileCorruptionHandler {
    mutablePreferencesOf(LOAD_STATE_KEY to CORRUPTED)
}
```

### 2. Assert load-state

Test should verify:

```kotlin
val state = repository.getLoadState()
assertTrue(state is PrivacySettingsLoadState.CorruptedFailClosed)
```

### 3. Assert settings are fail-closed

Check concrete fields:

```kotlin
notificationCaptureEnabled == false
cloudAiEnabled == false
redactBeforeCloud == true
receiptImageCloudEnabled == false
bankStatementAiEnabled == false
externalGeocodingEnabled == false
debugDataPersistenceEnabled == false
rawNotificationStorageMode == DO_NOT_STORE
rawOcrStorageMode == DO_NOT_STORE
emailReceiptStorageMode == DO_NOT_STORE
```

### 4. Assert first-run is distinct

Separate test:

```text
empty clean DataStore -> FirstRunDefault
corrupt DataStore -> CorruptedFailClosed
```

### 5. Assert update clears corruption state only after explicit user action

If user saves settings after corruption:

```text
LOAD_STATE_KEY becomes NORMAL
settings reflect saved value
```

But until then, fail-closed remains.

## Tests

```text
real_datastore_corruption_sets_corrupted_sentinel
real_datastore_corruption_returns_corrupted_fail_closed
real_datastore_corruption_disables_notification_capture
real_datastore_corruption_disables_cloud_ai
real_datastore_corruption_sets_all_raw_modes_do_not_store
clean_empty_datastore_is_first_run_not_corruption
saving_settings_after_corruption_marks_load_state_normal
```

## Acceptance criteria

```text
1. Production DataStore corruption path is tested, not only fake repository logic.
2. Corruption cannot be misclassified as first run.
3. Fail-closed settings persist until explicit user save/recovery.
```

---

# PR 3 — End-to-end raw-storage persistence tests

## Issue fixed

```text
PRIV-4B-02
```

## Problem

`RawStoragePolicyAuditTest` validates builders/policy objects, but does not prove real persistence paths obey policy.

Need tests that insert/process realistic data and inspect actual stored rows.

## Files / areas

```text
NotificationRepository.kt
NotificationProcessingPipeline.kt
NotificationCaptureService.kt
ReceiptLifecycleCoordinator.kt
EmailReceiptIngestionService.kt
BankApiIntegration.kt
BankStatementLifecycleProcessor.kt
ExportCoordinator.kt
DataRetentionWorker.kt
RawStoragePolicyAuditTest.kt
new privacy integration tests
```

## Implementation strategy

Create test fixtures for each source:

```text
notification with title/body/extras
OCR result with raw text and item descriptions
email receipt with subject/sender/body/messageId/orderId
bank transaction with description/reference/account/provider id
debug/export request
```

Run under modes:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

Then inspect persisted rows and emitted diagnostics.

---

## Part A — Notification end-to-end tests

### Test setup

Use fake DAOs/repository or in-memory Room if available.

Input contains unique sentinel raw text:

```text
"SECRET_NOTIFICATION_BODY_123"
"SECRET_CARD_4111111111111111"
```

### Tests

```text
notification_do_not_store_no_raw_text_in_raw_notifications
notification_do_not_store_no_raw_text_in_pending_reviews
notification_do_not_store_no_raw_text_in_diagnostics
notification_metadata_only_stores_hashes_not_body
notification_redacted_mode_stores_redacted_placeholder
notification_store_raw_preserves_raw_only_in_approved_raw_row
```

### Assert no raw sentinel appears in:

```text
raw_notifications
pending_reviews
transaction_events.metadataJson
pipeline_diagnostic_events.metadataJson
background_job_runs.metadataJson
debug trace output
```

---

## Part B — Receipt/OCR tests

Raw sentinel:

```text
"SECRET_OCR_ITEM_DESCRIPTION_123"
```

Tests:

```text
ocr_do_not_store_no_raw_text_in_scanned_receipts
ocr_do_not_store_no_raw_text_in_pending_reviews
ocr_do_not_store_no_raw_text_in_receipt_events
ocr_metadata_only_omits_item_descriptions
ocr_redacted_mode_redacts_item_descriptions
```

Inspect:

```text
scanned_receipts.rawOcrText
scanned_receipts.parsedItems
pending_reviews
receipt_events.metadataJson
pipeline_diagnostic_events
```

---

## Part C — Email tests

Raw sentinels:

```text
subject = "SECRET_EMAIL_SUBJECT_123"
sender = "private@example.com"
body = "SECRET_EMAIL_BODY_123"
messageId = "<secret-message-id@example.com>"
```

Tests:

```text
email_do_not_store_no_plain_subject_sender_body_message_id
email_metadata_only_keeps_message_id_hash_only
email_content_fingerprint_not_plaintext
email_diagnostics_do_not_include_subject_sender_body
email_parsed_items_redacted_by_policy
```

Inspect:

```text
email_receipt_sources
scanned_receipts
pending_reviews
receipt_events
pipeline_diagnostic_events
operation_run_events
```

---

## Part D — Bank tests

Raw sentinels:

```text
description = "SECRET_BANK_DESCRIPTION_123"
reference = "SECRET_BANK_REFERENCE_123"
accountId = "secret-account-id"
providerTransactionId = "secret-provider-transaction-id"
counterparty = "Secret Counterparty"
```

Tests:

```text
bank_do_not_store_no_raw_description_reference_counterparty
bank_provider_transaction_id_stored_as_hash_only
bank_account_id_stored_as_hash_only
bank_sync_errors_do_not_include_raw_description
bank_transaction_events_do_not_include_raw_bank_fields
bank_pending_review_uses_redacted_description
```

Inspect:

```text
operation_run_events
transaction_events
pending_reviews
pipeline_diagnostic_events
sync result errors
```

---

## Part E — Export/debug/backup tests

Tests:

```text
debug_export_denied_without_debug_raw_export_capability
redacted_export_removes_notification_email_ocr_bank_ai_location_fields
encrypted_disabled_does_not_allow_raw_export
backup_manifest_declares_privacy_mode
support_debug_trace_does_not_include_raw_sentinels
```

---

## Acceptance criteria

```text
1. Each source has behavior tests against real persistence paths or close realistic fakes.
2. Raw sentinel strings do not appear anywhere under METADATA_ONLY / DO_NOT_STORE.
3. Redacted mode stores redacted placeholders only.
4. STORE_RAW stores raw values only in explicitly approved columns.
```

---

# PR 4 — Retention registry real coverage audit

## Issue fixed

```text
PRIV-4B-02 partial
```

## Problem

Current test checks a hardcoded retention target list, not the actual production registry.

## Files

```text
DataRetentionWorker.kt
RetentionTarget.kt
RetentionRegistry.kt // create if missing
RawStoragePolicyAuditTest.kt
```

## Implementation steps

### 1. Create explicit `RetentionRegistry`

```kotlin
class RetentionRegistry @Inject constructor(
    targets: Set<@JvmSuppressWildcards RetentionTarget>
) {
    fun allTargets(): Set<RetentionTarget> = targets
}
```

Each target must have:

```kotlin
interface RetentionTarget {
    val name: String
    val sensitiveClasses: Set<SensitiveDataClass>
    suspend fun purge(cutoffMs: Long): RetentionPurgeResult
}
```

### 2. Register real targets

Minimum targets:

```text
raw_notifications
scanned_receipts.rawOcrText
scanned_receipts.parsedItems
email_receipt_sources.subject
email_receipt_sources.sender
email_receipt_sources.body
email_receipt_sources.messageIdPlaintext if still exists
ai_prompts
ai_responses
cloud_payload_artifacts
debug_exports
pipeline_diagnostic_events.metadataJson optional purge/redact
operation_run_events.metadataJson optional purge/redact
bank_import_debug_data
```

### 3. Test registry, not literals

```kotlin
val targetNames = retentionRegistry.allTargets().map { it.name }.toSet()
assertContains(targetNames, "email_receipt_sources")
...
```

### 4. Add purge behavior tests

Insert rows with old timestamps and raw sentinel values. Run retention. Assert values are removed/redacted.

## Tests

```text
retention_registry_contains_all_sensitive_targets
retention_worker_purges_raw_notifications
retention_worker_purges_raw_ocr
retention_worker_purges_email_subject_sender_body
retention_worker_purges_ai_prompts_and_responses
retention_worker_purges_debug_exports
retention_worker_records_per_target

:warning: The provider stream ended early, so this response may be incomplete.