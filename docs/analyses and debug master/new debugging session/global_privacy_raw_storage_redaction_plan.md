# Global Privacy / Raw-Storage / Redaction Implementation Plan

Baseline commit: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`

Universal rule:

```text
PrivacySettings is authoritative.
RawStorageMode applies to every persisted target, not only primary raw rows.
Cloud payloads must use EffectiveCloudAiPolicy + prepared/redacted payloads.
Debug/export/backup/diagnostics must obey the same privacy policy.
No raw sensitive value is persisted or sent unless an explicit policy allows it.
```

Affected pipelines:

```text
P1 Notification capture
P3 Receipt/OCR/bank statement
P7 Backup/restore
P8 Privacy/AI/redaction
P10 Bank integration/imports
P11 Email receipt ingestion
P12 Import/export/accounting
```

---

## 0. Current state summary

Current code already has useful building blocks:

```text
PrivacySettings
RawStorageMode
RawContentSanitizer
EffectiveCloudAiPolicyResolver
CloudPayloadRedactor
DefaultCloudPayloadRedactor
PrivacyGate / CompositePrivacyGate
PrivacyAuditEvent
BackupPrivacyGate
NotificationCaptureService fast notification setting cache
```

But enforcement is incomplete.

Key current issues:

1. `PrivacySettings` defaults still allow:
   ```text
   notificationCaptureEnabled = true
   rawNotificationStorageMode = STORE_RAW
   rawOcrStorageMode = STORE_RAW
   ```
   even when DataStore fallback is `emptyPreferences()`.

2. `EffectiveCloudAiPolicyResolver` computes:
   ```text
   redactBeforeCloud = privacy.redactBeforeCloud || ai.redactBeforeCloud
   ```
   but cloud providers still often use `AiSettings.redactBeforeCloud` or caller flags directly.

3. `RawContentSanitizer` sanitizes some primary fields, but raw data can still leak through:
   ```text
   PendingReview
   TransactionEvent metadata
   ReceiptEvent metadata
   PipelineDiagnosticEvent
   EmailReceiptSource fingerprint/message ID
   ScannedReceipt.parsedItems
   bank notes / transfer account name
   debug exports
   normal CSV/JSON export
   backup/export paths
   ```

4. Notification capture checks a fast settings flag before extraction, but the full `PrivacyGate` decision can still happen after extras/text extraction.

5. `CloudPayloadPurpose` lacks bank-statement-specific purpose.

6. Raw export policy is confused: backup/privacy settings are reused for normal export and raw export.

---

# 1. Target invariants

## 1.1 Privacy-settings authority

```text
PrivacySettings must be the strongest privacy source.
AiSettings or caller input may further restrict behavior but must never weaken PrivacySettings.
```

Example:

```text
privacy.redactBeforeCloud = true
ai.redactBeforeCloud = false
=> effective redaction MUST be true.
```

---

## 1.2 Raw-storage invariant

For every source type:

```text
STORE_RAW
  raw body may be persisted in approved tables.

STORE_REDACTED
  no raw body; redacted/safe placeholder or structured redacted value only.

STORE_METADATA_ONLY
  no body text; only safe metadata, app-internal IDs, hashes, parsed amount/date/currency if policy allows.

DO_NOT_STORE
  no body text, no subject/sender/body/items/raw prompt/fingerprint plaintext.
  Dedupe may use keyed hashes only.
```

This applies to **all** persisted targets, including:

```text
raw_notifications
pending_reviews
scanned_receipts
email_receipt_sources
transaction_events
receipt_events
pipeline_diagnostic_events
background_job_runs metadata
bank import rows
export/import run rows
debug/export files
```

---

## 1.3 Cloud payload invariant

```text
No cloud provider may build an HTTP request from raw strings directly.
Every cloud request must use PreparedCloudPayload produced by CloudPayloadPolicy.
```

---

## 1.4 Diagnostics invariant

```text
Diagnostics/audit metadata must never contain raw sensitive content.
External identifiers must be hashed.
Exception messages must be sanitized.
```

---

## 1.5 Export/backup invariant

```text
Normal export, debug export, redacted export, and backup are separate privacy capabilities.
Disabling encrypted backup must never imply permission for plaintext raw export.
```

---

# 2. Sensitive data taxonomy

Create:

```kotlin
enum class SensitiveDataClass {
    NOTIFICATION_TITLE,
    NOTIFICATION_BODY,
    NOTIFICATION_EXTRAS,

    OCR_RAW_TEXT,
    OCR_PARSED_ITEMS,
    RECEIPT_IMAGE_PATH,

    EMAIL_SUBJECT,
    EMAIL_SENDER,
    EMAIL_BODY,
    EMAIL_MESSAGE_ID,
    EMAIL_ORDER_ID,

    BANK_DESCRIPTION,
    BANK_REFERENCE,
    BANK_COUNTERPARTY,
    BANK_ACCOUNT_ID,
    BANK_PROVIDER_TRANSACTION_ID,
    BANK_TOKEN,

    AI_PROMPT,
    AI_RESPONSE,
    CLOUD_PAYLOAD,

    LOCATION_COORDINATES,
    LOCATION_ADDRESS,
    PLACE_ID,

    FILE_PATH,
    DEBUG_EXPORT_BODY
}
```

Also define allowed transformations:

```kotlin
enum class SensitiveTransform {
    RAW,
    REDACTED,
    HASHED,
    HMAC_HASHED,
    METADATA_ONLY,
    OMITTED
}
```

---

# 3. Core architecture

## 3.1 PrivacySettings load state

Current fallback cannot distinguish first run from corrupted settings.

Add:

```kotlin
sealed interface PrivacySettingsLoadState {
    data class Loaded(val settings: PrivacySettings) : PrivacySettingsLoadState
    data class FirstRunDefault(val settings: PrivacySettings) : PrivacySettingsLoadState
    data class CorruptedFailClosed(
        val settings: PrivacySettings,
        val reason: String
    ) : PrivacySettingsLoadState
}
```

Fail-closed corrupted defaults:

```kotlin
PrivacySettings(
    notificationCaptureEnabled = false,
    cloudAiEnabled = false,
    redactBeforeCloud = true,
    receiptImageCloudEnabled = false,
    bankStatementAiEnabled = false,
    externalGeocodingEnabled = false,
    backgroundLocationBackfillEnabled = false,
    deviceGpsLocationEnabled = false,
    encryptedBackupEnabled = true,
    rawNotificationStorageMode = RawStorageMode.DO_NOT_STORE,
    rawOcrStorageMode = RawStorageMode.DO_NOT_STORE,
    emailReceiptStorageMode = RawStorageMode.DO_NOT_STORE,
    debugDataPersistenceEnabled = false
)
```

Add repository APIs:

```kotlin
interface PrivacySettingsRepository {
    fun observeSettings(): Flow<PrivacySettings>
    fun observeLoadState(): Flow<PrivacySettingsLoadState>
    suspend fun getSettings(): PrivacySettings
    suspend fun getLoadState(): PrivacySettingsLoadState
}
```

---

## 3.2 Keyed hash service

Do not use `hashCode()` for privacy-sensitive identifiers.

Add:

```kotlin
interface SensitiveHashingService {
    fun hmacSha256Prefix(value: String?, purpose: String, length: Int = 24): String?
    fun sha256Prefix(value: String?, length: Int = 24): String?
}
```

Use HMAC for:

```text
emailMessageId
providerTransactionId
bank account ID
notification key
external fingerprint
receipt order ID
```

Use plain SHA only for already non-sensitive or non-linkable content.

---

## 3.3 Raw persistence policy

Add:

```kotlin
data class RawPersistencePolicy(
    val mode: RawStorageMode,
    val sourceType: RawSourceType,
    val allowParsedAmountDateCurrency: Boolean,
    val allowParsedMerchant: Boolean,
    val allowParsedItems: Boolean,
    val allowExternalIdHash: Boolean,
    val allowDebugBody: Boolean
)

enum class RawSourceType {
    NOTIFICATION,
    RECEIPT_OCR,
    EMAIL_RECEIPT,
    BANK_STATEMENT,
    BANK_API,
    AI_ARTIFACT,
    EXPORT_DEBUG
}
```

Resolver:

```kotlin
class RawPersistencePolicyResolver @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository
) {
    suspend fun forSource(sourceType: RawSourceType): RawPersistencePolicy
}
```

---

## 3.4 Sanitized persistence payload

Create source-specific payload models.

### Notification

```kotlin
data class NotificationPersistencePayload(
    val rawNotificationTitle: String?,
    val rawNotificationText: String?,
    val rawNotificationBigText: String?,
    val rawNotificationSubText: String?,
    val rawNotificationExtrasJson: String?,

    val pendingReviewTitle: String?,
    val pendingReviewText: String?,

    val eventMetadata: SafePrivacyMetadata,
    val diagnosticMetadata: SafePrivacyMetadata,

    val dedupeFingerprint: String,
    val contentHash: String?
)
```

### Receipt/OCR

```kotlin
data class ReceiptPersistencePayload(
    val rawOcrText: String,
    val reviewSnippet: String?,
    val parsedItemsJson: String?,
    val eventMetadata: SafePrivacyMetadata,
    val diagnosticMetadata: SafePrivacyMetadata
)
```

### Email

```kotlin
data class EmailReceiptPersistencePayload(
    val subject: String?,
    val sender: String?,
    val bodyText: String?,
    val messageIdStored: String?,
    val messageIdHash: String?,
    val contentFingerprintHash: String?,
    val providerOrderIdHash: String?,
    val parsedItemsJson: String?,
    val eventMetadata: SafePrivacyMetadata
)
```

### Bank

```kotlin
data class BankTransactionPersistencePayload(
    val redactedDescription: String?,
    val redactedReference: String?,
    val counterpartyHash: String?,
    val providerTransactionIdHash: String?,
    val accountIdHash: String?,
    val notes: String?,
    val eventMetadata: SafePrivacyMetadata
)
```

---

## 3.5 Safe metadata type

Add or reuse from diagnostics plan:

```kotlin
class SafePrivacyMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String
}
```

Blocked keys:

```text
rawText
rawBody
body
subjectRaw
senderRaw
prompt
token
accessToken
refreshToken
authorization
password
iban
accountNumber
cardNumber
fullPath
ocrText
emailBody
bankDescription
```

Tests must prove blocked keys are dropped or redacted.

---

# 4. Cloud payload architecture

## 4.1 Prepared payload contract

Add:

```kotlin
data class PreparedCloudPayload(
    val purpose: CloudPayloadPurpose,
    val text: String,
    val redactionApplied: Boolean,
    val fieldsRedacted: Set<String>,
    val payloadHash: String,
    val rawTextIncluded: Boolean,
    val rawImageIncluded: Boolean,
    val imageBytes: ByteArray? = null,
    val imageMimeType: String? = null,
    val auditMetadata: SafePrivacyMetadata
)
```

Rules:

```text
rawTextIncluded can be true only if effective policy allows no redaction.
rawImageIncluded can be true only if receiptImageUploadAllowed and redaction is not required.
If redaction is required, image upload is suppressed unless a real image-redaction pipeline exists.
```

---

## 4.2 Add cloud purposes

Current purposes include receipt/item/query/dashboard/etc.

Add:

```kotlin
BANK_STATEMENT_VALIDATION
BANK_TRANSACTION_CLASSIFICATION
EXPORT_SUMMARY
```

Bank statement purpose should be stricter than receipt assist:

```text
hash/redact counterparties
redact account fragments
redact IBAN/card/reference-like tokens
preserve amount/date/currency when needed
```

---

## 4.3 CloudPayloadPolicy

Add:

```kotlin
interface CloudPayloadPolicy {
    suspend fun prepareText(
        purpose: CloudPayloadPurpose,
        rawText: String,
        context: SafePrivacyMetadata = SafePrivacyMetadata.empty()
    ): PreparedCloudPayload

    suspend fun prepareReceiptAssist(
        input: ReceiptAssistInput
    ): PreparedCloudPayload

    suspend fun prepareDashboardBriefing(
        input: DashboardBriefingInput
    ): PreparedCloudPayload

    suspend fun prepareItemCategorization(
        input: ReceiptItemCategorizationInput
    ): PreparedCloudPayload

    suspend fun prepareBankStatementValidation(
        rawPromptOrPayload: BankStatementCloudInput
    ): PreparedCloudPayload
}
```

Implementation uses:

```text
EffectiveCloudAiPolicyResolver
CloudPayloadRedactor
PrivacyGate
SensitiveHashingService
PrivacyAuditLogger
```

---

## 4.4 Provider rule

Cloud providers must not call:

```text
aiSettingsRepository.settings().first().redactBeforeCloud
input.redactBeforeCloud
```

for privacy weakening.

They may read AI settings for:

```text
provider/model preference
temperature/config
feature enablement that is stricter than privacy
```

But redaction/permission must come from:

```text
EffectiveCloudAiPolicy
CloudPayloadPolicy
PrivacyGate
```

---

# 5. Audit/provenance architecture

## 5.1 Typed audit context

Replace raw `Map<String, String>` with:

```kotlin
data class PrivacyAuditContext(
    val operation: String,
    val caller: String?,
    val entityType: String?,
    val entityId: Long?,
    val provider: String?,
    val modelId: String?,
    val purpose: CloudPayloadPurpose?,
    val payloadHash: String?,
    val redactionApplied: Boolean?,
    val rawTextIncluded: Boolean?,
    val rawImageIncluded: Boolean?,
    val correlationId: String?,
    val metadata: SafePrivacyMetadata = SafePrivacyMetadata.empty()
)
```

---

## 5.2 Cloud AI call audit

Either add a new table:

```kotlin
@Entity(tableName = "cloud_ai_call_events")
data class CloudAiCallEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val provider: String,
    val modelId: String,
    val purpose: String,
    val decision: String,
    val redactionApplied: Boolean,
    val rawTextIncluded: Boolean,
    val rawImageIncluded: Boolean,
    val payloadHash: String?,
    val status: String,
    val errorClass: String?,
    val occurredAt: Long
)
```

or extend `PrivacyAuditEvent`.

Recommended short-term:

```text
extend PrivacyAuditEvent context safely
```

Recommended long-term:

```text
CloudAiCallEvent table for cloud-call provenance
```

---

# 6. PR implementation plan

## PR 1 — Strict privacy load state and fail-closed corruption

### Goal

DataStore corruption/read failure cannot silently enable capture/raw storage/cloud/location.

### Files

```text
PrivacySettings.kt
PrivacySettingsRepository.kt
PrivacySettingsRepositoryImpl.kt
Privacy settings UI
tests
```

### Tasks

1. Add `PrivacySettingsLoadState`.
2. Distinguish first-run default from corrupted fallback.
3. Replace corruption fallback with explicit fail-closed settings.
4. Make `observeSettings()` emit fail-closed settings on corruption.
5. Surface warning in privacy settings UI.
6. Fix `updateSettings()` to pass the actual persisted updated value to runtime applier, not `transform(old)`.

### Acceptance tests

```text
datastore_corruption_disables_notification_capture
datastore_corruption_sets_raw_notification_do_not_store
datastore_corruption_sets_raw_ocr_do_not_store
datastore_corruption_sets_email_do_not_store
datastore_corruption_disables_cloud_ai
first_run_defaults_are_distinct_from_corruption_defaults
privacy_update_applies_actual_persisted_updated_settings
```

---

## PR 2 — Central raw persistence policy and hashing

### Goal

Every source uses the same mode semantics and safe hashing.

### Files

```text
RawStorageMode.kt
RawContentSanitizer.kt
new RawPersistencePolicy.kt
new RawPersistencePolicyResolver.kt
new SensitiveHashingService.kt
new SafePrivacyMetadata.kt
```

### Tasks

1. Add `RawSourceType`.
2. Add `RawPersistencePolicyResolver`.
3. Add HMAC hashing service.
4. Replace `hashCode()` message ID storage with HMAC hash.
5. Define explicit mode matrix.
6. Add tests for every mode/source.

### Acceptance tests

```text
store_raw_preserves_allowed_raw_fields
store_redacted_replaces_body_fields
metadata_only_omits_body_but_keeps_hashes
do_not_store_omits_body_and_plain_identifiers
email_message_id_hash_stable_across_modes_except_policy_denied
hash_service_does_not_use_String_hashCode
```

---

## PR 3 — Notification privacy and persistence hardening

### Goal

Notification raw mode applies to raw row, pending review, transaction events, diagnostics.

### Files

```text
NotificationCaptureService.kt
NotificationRepository.kt
NotificationProcessingPipeline.kt
RawNotification.kt
PendingReview creation paths
PipelineDiagnosticEvent writers
```

### Tasks

1. Create `NotificationCaptureGate`:
   ```text
   restore mode
   full PrivacyGate decision
   blocked package cache
   shutdown state
   ```
2. Check gate before:
   ```text
   dedupe insert
   extras extraction
   filter
   content hash
   ```
3. Build `NotificationPersistencePayload`.
4. Ensure `PendingReview` uses sanitized title/text.
5. Ensure transaction/diagnostic metadata uses safe metadata.
6. Do not build/persist extras JSON for metadata-only/do-not-store.
7. Keep raw text only in ephemeral parse input.

### Acceptance tests

```text
notification_disabled_does_not_read_extras
privacy_fail_closed_notification_does_not_read_extras
blocked_package_does_not_read_extras
do_not_store_notification_no_raw_text_in_raw_notifications
do_not_store_notification_no_raw_text_in_pending_reviews
metadata_only_notification_no_raw_extras_in_diagnostics
redacted_notification_pending_review_has_redacted_text
privacy_denied_does_not_poison_dedupe_cache
```

---

## PR 4 — Receipt/OCR/email storage hardening

### Goal

OCR/email raw modes cover all receipt, review, event, source, and debug fields.

### Files

```text
ReceiptLifecycleCoordinator.kt
ReceiptRepository.kt
EmailReceiptIngestionService.kt
EmailReceiptSource.kt
EmailReceiptDao.kt
ScannedReceipt.kt
PendingReview paths
ReceiptEvent metadata
parser debug export paths
```

### Tasks

1. Build `ReceiptPersistencePayload`.
2. Build `EmailReceiptPersistencePayload`.
3. Add hashed columns:
   ```text
   emailMessageIdHash
   providerOrderIdHash
   contentFingerprintHash
   ```
4. Stop storing plaintext email content fingerprint.
5. Sanitize `parsedItems` based on policy.
6. Sanitize pending-review snippets.
7. Sanitize receipt events and diagnostics.
8. Ensure parser debug exports respect storage/export policy.
9. Add migration for new hashed columns.

### Acceptance tests

```text
raw_ocr_do_not_store_no_raw_text_in_scanned_receipts
raw_ocr_do_not_store_no_raw_text_in_pending_reviews
raw_ocr_metadata_only_no_raw_text_in_receipt_events
email_do_not_store_no_subject_sender_body_message_id_plaintext
email_metadata_only_keeps_message_id_hash_for_dedupe
email_fingerprint_not_plaintext_merchant_amount_date
parsed_items_redacted_when_policy_requires
debug_export_blocked_or_redacted_by_policy
```

---

## PR 5 — Bank and bank-statement privacy hardening

### Goal

Bank descriptions, references, account IDs, provider IDs, and debug data are safe.

### Files

```text
BankApiIntegration.kt
BankStatementLifecycleProcessor.kt
BankConnection.kt
BankTokenCipher.kt
CreateExpenseRequest bank metadata
TransactionEvent metadata
PendingReview bank paths
DebugData models
```

### Tasks

1. Build `BankTransactionPersistencePayload`.
2. Hash:
   ```text
   providerTransactionId
   accountId
   counterparty/account references
   ```
3. Redact notes and transfer account name.
4. Do not use raw bank description as `transferAccountName`.
5. Sanitize bank sync errors.
6. Sanitize statement debug data based on raw OCR policy.
7. Strictly reject plaintext bank tokens.
8. Define restored-token behavior:
   ```text
   undecryptable token -> REAUTH_REQUIRED
   ```
9. Add bank raw/export policy.

### Acceptance tests

```text
bank_notes_redacted_when_policy_requires
transfer_account_name_not_raw_description
provider_transaction_id_stored_as_hash_in_events
sync_error_does_not_include_raw_bank_description
statement_debug_data_respects_raw_ocr_policy
plaintext_bank_token_marked_invalid_and_wiped
restored_undecryptable_token_marks_reauth_required
```

---

## PR 6 — Prepared cloud payload contract

### Goal

No cloud provider can send raw payload accidentally.

### Files

```text
CloudPayloadRedactor.kt
DefaultCloudPayloadRedactor.kt
EffectiveCloudAiPolicy.kt
new CloudPayloadPolicy.kt
all Cloud*Service.kt providers
Hybrid services if applicable
```

### Tasks

1. Add `PreparedCloudPayload`.
2. Add missing purposes:
   ```text
   BANK_STATEMENT_VALIDATION
   BANK_TRANSACTION_CLASSIFICATION
   EXPORT_SUMMARY
   ```
3. Implement `CloudPayloadPolicy`.
4. Migrate providers:
   ```text
   CloudReceiptAssistService
   CloudDashboardBriefingService
   CloudReceiptItemCategorizationService
   CloudQueryInterpretationService
   any warranty/dedupe/review providers
   ```
5. Remove direct use of:
   ```text
   AiSettings.redactBeforeCloud
   input.redactBeforeCloud
   ```
   for privacy decisions.
6. Suppress image upload when redaction is required.
7. Add audit metadata for every prepared payload.

### Acceptance tests

```text
privacy_redact_true_ai_redact_false_redacts_receipt_assist
privacy_redact_true_ai_redact_false_redacts_dashboard
privacy_redact_true_input_redact_false_redacts_item_categorization
bank_statement_uses_BANK_STATEMENT_VALIDATION_purpose
receipt_image_upload_suppressed_when_redaction_required
all_cloud_providers_send_PreparedCloudPayload
```

---

## PR 7 — Cloud audit and privacy provenance

### Goal

Every privacy-sensitive cloud decision is provable.

### Files

```text
PrivacyAuditEvent.kt
PrivacyAuditLogger.kt
CompositePrivacyGate.kt
CloudPayloadPolicy.kt
cloud providers
optional CloudAiCallEvent.kt
```

### Tasks

1. Add typed `PrivacyAuditContext`.
2. Add cloud provenance:
   ```text
   provider
   model
   purpose
   redactionApplied
   payloadHash
   rawTextIncluded
   rawImageIncluded
   correlationId
   ```
3. Sanitize all context.
4. Update `PrivacyGate` docs:
   ```text
   unrelated capability -> NotApplicable
   composite does final audit
   ```
5. Add complete sensitive-capability handler set.

### Acceptance tests

```text
cloud_call_audit_has_provider_model_purpose
cloud_call_audit_has_payload_hash
cloud_call_audit_records_redactionApplied
audit_context_rejects_raw_prompt
privacy_gate_unrelated_returns_not_applicable
missing_sensitive_handler_fail_closed
```

---

## PR 8 — Export, debug, and backup privacy policy

### Goal

Plain export/debug/raw backup are separate, explicit policies.

### Files

```text
BackupPrivacyGate.kt
PrivacyCapability.kt
ExportOptionsViewModel.kt
ExportCoordinator.kt
ExportAnonymizer.kt
DatabaseBackupRepositoryImpl.kt
debug export functions
```

### Tasks

1. Add dedicated capabilities:
   ```text
   EXPENSE_EXPORT
   EXPENSE_EXPORT_RAW
   EXPENSE_EXPORT_REDACTED
   EXPENSE_EXPORT_ENCRYPTED
   DEBUG_RAW_EXPORT
   RAW_DATABASE_EXPORT
   ```
2. Replace backup setting implication:
   ```text
   encryptedBackupEnabled=false must NOT allow raw export
   ```
3. Add:
   ```kotlin
   enum class ExportPrivacyPolicy {
       DISABLED,
       ENCRYPTED_ONLY,
       REDACTED_ALLOWED,
       RAW_DEBUG_ONLY
   }
   ```
4. Gate parser/debug exports.
5. Make redacted export use registry-based redaction targets.
6. Remove hardcoded export encryption password.
7. Include privacy mode in export manifest.

### Acceptance tests

```text
encrypted_disabled_does_not_allow_raw_export
raw_export_rejected_in_release
debug_raw_export_requires_debug_and_privacy_consent
plaintext_export_rejected_when_policy_encrypted_only
redacted_export_removes_email_ai_location_bank_sensitive_fields
export_manifest_records_privacy_mode
```

---

## PR 9 — Retention registry

### Goal

Retention applies to all sensitive artifacts.

### Files

```text
DataRetentionWorker.kt
new RetentionTarget.kt
RawNotification DAO
ScannedReceipt DAO
EmailReceiptSource DAO
AI artifact/chat DAOs
diagnostic/debug export DAOs
```

### Tasks

1. Add:
   ```kotlin
   interface RetentionTarget {
       val name: String
       suspend fun purge(cutoffMs: Long): RetentionPurgeResult
   }
   ```
2. Register targets:
   ```text
   raw_notifications
   scanned_receipts.rawOcrText
   email_receipt_sources subject/sender/body
   ai_artifacts prompts/responses
   ai_chat_messages
   pipeline diagnostics containing optional metadata
   debug exports
   cloud call audit payload hashes if policy requires
   ```
3. Log per-target counts.
4. Do not cancel data retention when notification capture is disabled.
5. Add worker checkpoints before each purge target.

### Acceptance tests

```text
data_retention_purges_raw_notifications
data_retention_purges_raw_ocr
data_retention_purges_email_subject_sender_body
data_retention_purges_ai_prompts
data_retention_records_per_target_counts
disable_notification_capture_does_not_cancel_data_retention
```

---

## PR 10 — Static privacy guards

### Goal

Prevent regressions.

### Add scripts

```text
scripts/verify_privacy_boundaries.py
```

Guard rules:

```text
No cloud provider may use AiSettings.redactBeforeCloud for redaction decisions.
No cloud provider may use input.redactBeforeCloud for final policy.
No Request.Builder().post(...) in cloud package may use raw String prompt directly.
No object : PrivacyGate { Allowed } in main source except approved test-only constructors.
No String.hashCode() for message IDs/provider transaction IDs.
No PendingReview raw notification/email/OCR body persistence without sanitizer.
No debug export of rawOcrText/rawNotification/email body without PrivacyGate(DEBUG_RAW_EXPORT).
```

Example grep targets:

```bash
rg "redactBeforeCloud" app/src/main/java/com/yourname/expensetracker/data/ai
rg "Request.Builder\\(\\).*post" app/src/main/java/com/yourname/expensetracker/data/ai
rg "object : PrivacyGate" app/src/main/java
rg "hashCode\\(\\).*messageId|messageId.*hashCode" app/src/main/java
rg "PendingReview\\(" app/src/main/java
rg "rawOcrText" app/src/main/java
```

### Acceptance tests

```text
privacy_guard_fails_on_ai_redact_policy_usage
privacy_guard_fails_on_raw_cloud_prompt_post
privacy_guard_fails_on_messageId_hashCode
privacy_guard_fails_on_allow_all_privacy_gate_in_main
```

---

# 7. Pipeline-specific application checklist

## P1 — Notification

Must fix:

```text
full capture gate before extras extraction
blocked package before extras extraction
content-aware dedupe after privacy gate
sanitized pending reviews
sanitized diagnostics/events
safe dedupe hashes
```

Definition of done:

```text
DO_NOT_STORE leaves no raw notification text in any table.
Privacy denied reads no extras.
```

---

## P3 — Receipt/OCR/bank statement

Must fix:

```text
raw OCR mode covers ScannedReceipt, PendingReview, ReceiptEvent, diagnostics
parsedItems sanitized
debug parser export gated/redacted
bank statement debug data sanitized
asset paths treated as sensitive file paths
```

Definition of done:

```text
DO_NOT_STORE raw OCR mode stores no raw OCR text or item descriptions anywhere.
```

---

## P7 — Backup/restore

Must fix:

```text
privacy audit backup policy explicit
redacted backup target registry
raw DB export release-disabled and privacy-gated
bank tokens handled explicitly
restore does not resurrect invalid undecryptable tokens silently
```

Definition of done:

```text
Backup manifest declares privacy policy.
Redacted backup excludes all registered sensitive fields.
```

---

## P8 — Privacy/AI

Must fix:

```text
EffectiveCloudAiPolicy consumed by every provider
PreparedCloudPayload required
bank-statement purpose added
typed audit context
fail-closed DataStore state
no allow-all privacy gates in main source
```

Definition of done:

```text
PrivacySettings cannot be weakened by AiSettings or caller input.
```

---

## P10 — Bank

Must fix:

```text
description/reference/counterparty redaction
provider transaction ID hash
account ID hash
token strict encryption/decryption
bank source metadata safe
sync errors safe
```

Definition of done:

```text
No raw bank description/reference/account/token appears in events, notes, diagnostics, or exports unless explicit raw policy allows.
```

---

## P11 — Email

Must fix:

```text
messageIdHash
contentFingerprintHash
no plaintext fingerprint
sender/subject/body/storage policy
parsedItems policy
parse failure safe diagnostics
```

Definition of done:

```text
Metadata-only email mode can still dedupe by hash but stores no raw subject/sender/body/message ID/fingerprint plaintext.
```

---

## P12 — Export/import/accounting

Must fix:

```text
dedicated export privacy capabilities
encrypted export with real passphrase
redacted export registry
manifest privacy mode
debug export gate
receipt/bank/email/AI/location field redaction
```

Definition of done:

```text
User can choose encrypted/redacted export safely.
Plain raw export is explicit, gated, and release-safe.
```

---

# 8. Golden test matrix

Add global tests:

```text
datastore_corruption_disables_sensitive_features
privacy_settings_authoritative_over_ai_settings
prepared_cloud_payload_required_for_all_cloud_calls
bank_statement_payload_uses_bank_statement_purpose
receipt_image_suppressed_when_redaction_required
metadata_sanitizer_rejects_raw_sensitive_keys

notification_do_not_store_no_raw_text_anywhere
notification_privacy_denied_reads_no_extras
ocr_do_not_store_no_raw_text_anywhere
email_metadata_only_no_plain_subject_sender_body_message_id
email_dedupe_works_with_message_id_hash
bank_notes_redacted_by_policy
bank_provider_transaction_id_hashed
debug_export_requires_privacy_consent
redacted_export_removes_registered_sensitive_fields
encrypted_disabled_does_not_allow_raw_export
data_retention_purges_all_sensitive_targets
```

---

# 9. Recommended PR order

Do this order:

```text
PR 1  PrivacySettings load-state + fail-closed corruption
PR 2  RawPersistencePolicy + SensitiveHashingService + SafePrivacyMetadata
PR 3  Notification privacy/persistence hardening
PR 4  Receipt/OCR/email persistence hardening
PR 5  Bank/bank-statement privacy hardening
PR 6  PreparedCloudPayload + CloudPayloadPolicy
PR 7  Cloud audit/provenance + PrivacyGate docs cleanup
PR 8  Export/debug/backup privacy policy
PR 9  Retention target registry
PR 10 Static privacy guards
```

Fastest risk reduction order:

```text
1. Effective cloud redaction enforcement
2. DataStore fail-closed settings
3. Raw storage downstream sanitization for notifications/email/OCR
4. Export/raw backup policy
5. Static guards
```

---

# 10. Implementation checklist for agents

Before coding, run:

```bash
rg "redactBeforeCloud" app/src/main/java
rg "allowCloudAi" app/src/main/java
rg "EffectiveCloudAiPolicy" app/src/main/java
rg "CloudPayloadPurpose" app/src/main/java
rg "Request.Builder" app/src/main/java/com/yourname/expensetracker/data/ai
rg "object : PrivacyGate" app/src/main/java
rg "RawStorageMode" app/src/main/java
rg "RawContentSanitizer" app/src/main/java
rg "PendingReview\\(" app/src/main/java
rg "rawOcrText" app/src/main/java
rg "EmailReceiptSource" app/src/main/java
rg "messageId" app/src/main/java/com/yourname/expensetracker
rg "fingerprint" app/src/main/java/com/yourname/expensetracker/data/email app/src/main/java/com/yourname/expensetracker/domain/receipt
rg "transferAccountName" app/src/main/java
rg "notes = transaction.description" app/src/main/java
rg "rawText = ocrResult.fullText" app/src/main/java
rg "RAWBACKUP_EXPORT" app/src/main/java
rg "encryptedBackupEnabled" app/src/main/java
rg "exportParserDebugData" app/src/main/java
rg "hashCode\\(" app/src/main/java/com/yourname/expensetracker
```

Allowed raw persistence must be documented in one place:

```text
docs/privacy/raw-storage-policy.md
```

---

# 11. Definition of done

```text
1. DataStore corruption fails closed for cloud, notification, raw storage, location, and debug persistence.

2. PrivacySettings is authoritative over AiSettings and caller flags.

3. Every cloud provider uses PreparedCloudPayload.

4. Bank statement AI uses a dedicated strict redaction purpose.

5. Notification capture denied state reads no extras/text.

6. RawStorageMode applies to raw rows, pending reviews, events, diagnostics, debug exports, normal exports, and source tables.

7. Message IDs, provider transaction IDs, order IDs, notification keys, and account IDs are stored as keyed hashes when raw storage is disabled.

8. No plaintext email fingerprint stores merchant/amount/date details.

9. Bank notes/descriptions/references are redacted or hashed according to policy.

10. Raw export policy is explicit and release-safe.

11. Retention worker purges every registered sensitive target.

12. Static privacy guards block regressions.

13. Golden tests prove no raw sensitive data is persisted under DO_NOT_STORE / METADATA_ONLY modes.
```

---

# 12. Sources used

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- `PrivacySettings.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt

- `PrivacySettingsRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `RawContentSanitizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawContentSanitizer.kt

- `EffectiveCloudAiPolicy.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/EffectiveCloudAiPolicy.kt

- `CloudPayloadRedactor.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadRedactor.kt

- `DefaultCloudPayloadRedactor.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadRedactor.kt

- `CloudReceiptAssistService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `NotificationCaptureService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt