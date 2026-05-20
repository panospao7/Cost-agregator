# Remaining Implementation Plan — post-`850c0e8258141a22021eb4a52a299853e1cb60d9`

## Baseline

Latest reviewed commit:

```text
850c0e8258141a22021eb4a52a299853e1cb60d9
fix(privacy): PRIV-FB58-01 — remove plaintext messageId fallback; fail closed on hash failure
```

This commit correctly fixes the specific plaintext `messageId` fallback:

```kotlin
val messageIdHash = hashingService.hmacSha256Prefix(messageId, "emailMessageId")
    ?: return@withLock EmailReceiptResult.ParseError("Failed to hash messageId — cannot proceed safely")
```

That is good and should not be reverted.

---

# 0. Current remaining issue register

## Fixed or mostly fixed; do not rework unless tests prove otherwise

```text
DONE: plaintext messageId fallback removed
DONE: messageId hash passed to coordinator instead of raw messageId
DONE: duplicate email side-effect dispatch appears removed
DONE: PrivacySettings update now uses load-state settings
DONE: notification blocked-package cache now fails closed before first emission
DONE: CloudCategorizationAssistService empty-prompt probe appears removed
DONE/PARTIAL: emailSender/emailSubject nullable for retention
DONE/PARTIAL: emailMessageIdHash + contentFingerprintHash columns exist
DONE/PARTIAL: AI artifact/chat retention counts now return DAO counts
```

## Still open

```text
REM-850-01 Email content fingerprint still falls back to String.hashCode()
REM-850-02 Email validation ParseError leaks raw merchant/amount
REM-850-03 Email exception diagnostic uses plain SHA for messageId
REM-850-04 Email live path is still not payload-first
REM-850-05 providerOrderIdHash column is still missing
REM-850-06 email raw fields still flow through coordinator API
REM-850-07 Cloud receipt payloadHash only covers text, not final text+image payload
REM-850-08 Cloud receipt image audit lacks suppression reason, size, mime, image hash
REM-850-09 Cloud receipt image path/source is not app-owned validated
REM-850-10 Main-source receipt test helper still reads image file and uses input.redactBeforeCloud
REM-850-11 Retention registry is still incomplete
REM-850-12 Retention cutoff logic is still worker/name-special-cased
REM-850-13 Static privacy guard is still regex/heuristic and misses current bugs
REM-850-14 Behavioral tests are mostly contract/documentation tests, not live-path tests
REM-850-15 Cloud transport still allows providers to construct Request.Builder directly
REM-850-16 Docs and code contracts are drifting
```

---

# PR 1 — Email residual privacy hardening

## Goal

Finish the email privacy hardening so no raw or weak fallback identifier survives the email path.

## Fixes

```text
REM-850-01
REM-850-02
REM-850-03
```

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
app/src/main/java/com/yourname/expensetracker/domain/privacy/SensitiveHashingService.kt
app/src/test/java/.../EmailReceiptIngestionServicePrivacyTest.kt
```

---

## 1.1 Remove `hashCode()` fallback from `createFingerprint`

### Current problem

`EmailReceiptIngestionService.createFingerprint(...)` still does:

```kotlin
return hashingService.sha256Prefix(raw, 32) ?: raw.hashCode().toString(16)
```

This violates the current privacy direction:
- no `String.hashCode()` fallback for sensitive or quasi-sensitive identifiers
- no silent degradation to a weak hash

### Implementation

Change `createFingerprint` to fail closed.

Recommended:

```kotlin
private fun createFingerprint(
    merchant: String,
    amount: Double,
    date: Long
): String? {
    val roundedAmount = String.format(Locale.US, "%.2f", amount)
    val dateBucket = date / 300_000L
    val raw = "${merchant.lowercase()}_${roundedAmount}_${dateBucket}"

    return hashingService.hmacSha256Prefix(
        value = raw,
        purpose = "emailContentFingerprint",
        length = 32
    )
}
```

Then in `processEmailReceipt(...)`:

```kotlin
val fingerprint = createFingerprint(
    normalizedMerchant,
    parsedReceipt.amount,
    parsedReceipt.date
) ?: return@withLock EmailReceiptResult.ParseError(
    "Failed to create safe email fingerprint"
)
```

### Notes

Use HMAC instead of plain SHA because this fingerprint is linkable and derived from merchant/amount/date.

Remove the unused `messageId` parameter from `createFingerprint(...)`.

---

## 1.2 Sanitize validation ParseError

### Current problem

Validation failure returns raw parsed values:

```kotlin
return EmailReceiptResult.ParseError(
    "Invalid receipt data: amount=${parsedReceipt.amount}, merchant=${parsedReceipt.merchant}"
)
```

This can leak merchant and amount through UI, logs, diagnostics, or test output.

### Implementation

Replace with:

```kotlin
return EmailReceiptResult.ParseError("Invalid receipt data")
```

If metadata is needed, emit safe booleans only:

```kotlin
metadata = SafeEventMetadata.builder()
    .put("hasMerchant", parsedReceipt.merchant.isNotBlank())
    .put("amountPositive", parsedReceipt.amount > 0)
    .put("hasDate", parsedReceipt.date > 0)
    .build()
```

Do not include merchant, amount, subject, sender, body, or raw parser text.

---

## 1.3 Use keyed hashing for exception source ID

### Current problem

The catch block uses:

```kotlin
sourceIdHash = messageId.let { it.sha256Prefix(16) }
```

For external identifiers such as email message IDs, use HMAC.

### Implementation

Compute message hash near the beginning if possible, or compute safely in the catch block:

```kotlin
val safeSourceIdHash = hashingService.hmacSha256Prefix(
    messageId,
    purpose = "emailMessageId",
    length = 16
)
```

Then:

```kotlin
sourceIdHash = safeSourceIdHash
```

If HMAC fails during exception handling:
- set `sourceIdHash = null`
- do not fall back to plaintext
- do not fall back to `hashCode()`

---

## 1.4 Remove raw-sensitive values from any ParseError

Search:

```bash
rg "ParseError\\(" app/src/main/java/com/yourname/expensetracker/data/email
rg "merchant=|amount=|sender=|subject=|body=" app/src/main/java/com/yourname/expensetracker/data/email
```

Replace user/debug-facing error messages with safe generic messages.

---

## Tests

Add real tests, not only contract assertions.

```text
email_content_fingerprint_hash_failure_fails_closed
email_content_fingerprint_never_uses_string_hashCode
email_validation_error_does_not_expose_merchant_or_amount
email_exception_source_id_uses_hmac_not_plain_sha
email_hmac_failure_never_uses_plaintext_message_id
```

## Acceptance criteria

```text
1. No `.hashCode()` remains in EmailReceiptIngestionService.
2. No ParseError includes raw merchant, amount, sender, subject, body, or messageId.
3. External email identifiers use HMAC, not plain SHA.
4. Hash failure fails closed.
```

---

# PR 2 — Email payload-first persistence contract

## Goal

Make the live email path actually use `EmailReceiptPersistencePayload`, not raw argument sprawl.

## Fixes

```text
REM-850-04
REM-850-05
REM-850-06
```

## Files

```text
EmailReceiptIngestionService.kt
ReceiptLifecycleCoordinator.kt
EmailReceiptPersistencePayload.kt
EmailReceiptSource.kt
EmailReceiptDao.kt
AppDatabase.kt
migrations
```

---

## 2.1 Add missing `providerOrderIdHash`

### Current state

`EmailReceiptSource` has:

```kotlin
emailMessageIdHash: String?
contentFingerprintHash: String?
```

But it does not have:

```kotlin
providerOrderIdHash: String?
```

### Implementation

Add to `EmailReceiptSource`:

```kotlin
@ColumnInfo(defaultValue = "NULL")
val providerOrderIdHash: String? = null
```

Add index:

```kotlin
Index(
    name = "index_email_receipt_sources_providerOrderIdHash",
    value = ["providerOrderIdHash"]
)
```

Migration:

```sql
ALTER TABLE email_receipt_sources ADD COLUMN providerOrderIdHash TEXT;

CREATE INDEX IF NOT EXISTS index_email_receipt_sources_providerOrderIdHash
ON email_receipt_sources(providerOrderIdHash);
```

If parser does not currently expose provider order ID, still add the nullable column now and wire later.

---

## 2.2 Stop treating raw `fingerprint` as primary dedupe identity

### Current risk

`EmailReceiptSource.fingerprint` is a raw-named field. It now appears to contain a hash/fingerprint, but the semantics are unclear.

### Implementation

Short term:

```text
fingerprint = contentFingerprintHash ?: ""
contentFingerprintHash = HMAC fingerprint
```

Long term:

```text
deprecate fingerprint
use contentFingerprintHash explicitly
```

DAO additions:

```kotlin
@Query("""
SELECT * FROM email_receipt_sources
WHERE emailMessageIdHash = :hash
LIMIT 1
""")
suspend fun getByMessageIdHash(hash: String): EmailReceiptSource?

@Query("""
SELECT * FROM email_receipt_sources
WHERE contentFingerprintHash = :hash
LIMIT 1
""")
suspend fun getByContentFingerprintHash(hash: String): EmailReceiptSource?
```

---

## 2.3 Build `EmailReceiptPersistencePayload` in ingestion

### Current problem

`EmailReceiptIngestionService` still builds:

```kotlin
EmailReceiptData(
    messageId = messageIdHash,
    from = sender,
    subject = subject,
    body = emailBody,
    ...
)
```

and calls coordinator with:

```kotlin
rawEmailBody = emailBody,
sender = sender,
subject = subject,
messageId = messageIdHash
```

Even if coordinator sanitizes today, the API remains dangerous.

### Target

In ingestion:

```kotlin
val settings = privacySettingsRepository.getSettings()

val payload = EmailReceiptPersistencePayload.build(
    mode = settings.emailReceiptStorageMode,
    subject = subject,
    sender = sender,
    bodyText = emailBody,
    messageId = messageId,
    messageIdHash = messageIdHash,
    contentFingerprintHash = fingerprint,
    providerOrderIdHash = providerOrderIdHash,
    parsedItemsJson = parsedItemsJson
)
```

Then call:

```kotlin
receiptLifecycleCoordinator.processEmailReceipt(
    emailData = coordinatorEmailDataEphemeral,
    persistencePayload = payload,
    provider = provider,
    correlationId = correlationId
)
```

---

## 2.4 Change coordinator API

Replace or deprecate:

```kotlin
processEmailReceipt(
    emailData: EmailReceiptData,
    fingerprint: String,
    rawEmailBody: String,
    sender: String,
    subject: String,
    messageId: String,
    ...
)
```

With:

```kotlin
processEmailReceipt(
    emailData: EmailReceiptData,
    persistencePayload: EmailReceiptPersistencePayload,
    provider: String,
    correlationId: String
)
```

Rules:
- raw `emailData.body` may be used only for parsing/classification in-memory
- DB writes must use only `persistencePayload`
- duplicate detector must use `messageIdHash` / `contentFingerprintHash`, not raw values

---

## 2.5 Persist hash columns correctly

When creating `EmailReceiptSource`:

```kotlin
EmailReceiptSource(
    receiptId = receiptId,
    emailSender = payload.sender,
    emailSubject = payload.subject,
    emailMessageId = payload.messageIdStored, // raw only in STORE_RAW
    emailMessageIdHash = payload.messageIdHash,
    contentFingerprintHash = payload.contentFingerprintHash,
    providerOrderIdHash = payload.providerOrderIdHash,
    fingerprint = payload.contentFingerprintHash ?: "",
    parsedAt = now,
    provider = provider,
    confidence = confidence
)
```

Retention must preserve:

```text
emailMessageIdHash
contentFingerprintHash
providerOrderIdHash
receiptId
provider
parsedAt
```

---

## Tests

```text
email_ingestion_builds_persistence_payload_before_coordinator
email_coordinator_persists_only_payload_fields
email_metadata_only_stores_hash_columns_not_raw_fields
email_do_not_store_keeps_dedupe_hashes_but_no_raw_subject_sender_body
email_provider_order_id_hash_column_exists
email_duplicate_by_message_id_hash_works_after_retention
email_duplicate_by_content_fingerprint_hash_works_after_retention
```

## Acceptance criteria

```text
1. The coordinator persistence path cannot write raw email fields except through EmailReceiptPersistencePayload.
2. Hashes live in explicit hash columns.
3. providerOrderIdHash exists and is preserved by retention.
4. Restricted modes persist no raw subject/sender/body/messageId/items.
```

---

# PR 3 — Cloud receipt payload provenance and image hardening

## Goal

Make `PreparedCloudPayload` describe the actual final payload sent to the cloud, including images.

## Fixes

```text
REM-850-07
REM-850-08
REM-850-09
REM-850-10
REM-850-15
```

## Files

```text
DefaultCloudPayloadPolicy.kt
CloudPayloadPolicy.kt
PreparedCloudPayload.kt
CloudReceiptAssistService.kt
ReceiptAssetStore.kt or new ReceiptImagePolicy.kt
cloud provider tests
```

---

## 3.1 Make `payloadHash` cover text + image

### Current problem

`prepareReceiptAssist(...)` computes:

```kotlin
val hash = preparedText.sha256Prefix(32)
```

If image bytes are included, the hash does not represent the final cloud payload.

### Implementation

Add helper:

```kotlin
private fun computePreparedPayloadHash(
    purpose: CloudPayloadPurpose,
    text: String,
    imageBytes: ByteArray?,
    imageMimeType: String?
): String {
    val textHash = text.sha256Prefix(64) ?: "text_hash_failed"
    val imageHash = imageBytes?.sha256Prefix(64)
    val raw = buildString {
        append("purpose=").append(purpose.name)
        append("|text=").append(textHash)
        append("|image=").append(imageHash ?: "none")
        append("|mime=").append(imageMimeType ?: "none")
    }
    return raw.sha256Prefix(32) ?: textHash.take(32)
}
```

If your hashing helpers do not support `ByteArray`, add one.

Required behavior:
- changing image bytes changes `payloadHash`
- changing MIME type changes `payloadHash`
- text-only payload remains deterministic

---

## 3.2 Add image audit metadata

### Current metadata

Only:

```text
purpose
redacted
imageIncluded
```

### Add

```text
imageSuppressedReason
imageMimeType
imageSizeBytes
imageHash
payloadHash
rawImageIncluded
rawTextIncluded
```

Example:

```kotlin
val audit = SafePrivacyMetadata.builder()
    .put("purpose", CloudPayloadPurpose.RECEIPT_ASSIST.name)
    .put("redacted", redactRequired)
    .put("imageIncluded", imageBytes != null)
    .put("imageSuppressedReason", imageSuppressedReason)
    .put("imageMimeType", resolvedMimeType)
    .put("imageSizeBytes", imageBytes?.size)
    .put("imageHash", imageHash)
    .put("payloadHash", payloadHash)
    .build()
```

Use `putHash` / approved hash key support if available.

---

## 3.3 Preserve caller context

`prepareText(...)` and `prepareReceiptAssist(...)` accept:

```kotlin
context: SafePrivacyMetadata
```

but currently the returned audit metadata does not merge it.

Add safe merge support:

```kotlin
SafePrivacyMetadata.builder()
    .putAll(context)
    .put("purpose", ...)
    ...
```

If `putAll` does not exist, add it while preserving blocked-key sanitation.

---

## 3.4 Validate image path/source

### Current risk

Policy reads:

```kotlin
val file = java.io.File(imagePath)
```

This trusts caller-provided paths.

### Target

Do not read arbitrary filesystem paths.

Options:

#### Preferred

Inject a receipt asset abstraction:

```kotlin
interface ReceiptImagePolicy {
    fun isAppOwnedReceiptImage(path: String): Boolean
    fun resolveMime(path: String, claimedMime: String?): String?
    suspend fun readForCloud(path: String, maxBytes: Long): ByteArray?
}
```

Then in `DefaultCloudPayloadPolicy`:

```kotlin
if (!receiptImagePolicy.isAppOwnedReceiptImage(imagePath)) {
    imageSuppressedReason = "non_app_owned_path"
    imageBytes = null
}
```

#### Acceptable short-term

Validate canonical path is under an app-owned receipt directory:

```kotlin
val canonical = file.canonicalFile
val root = receiptRootDir.canonicalFile

if (!canonical.path.startsWith(root.path + File.separator)) {
    suppress("non_app_owned_path")
}
```

---

## 3.5 Move `buildRequestBodyForTest` out of main source

### Current problem

`CloudReceiptAssistService.buildRequestBodyForTest(...)` is in main source and:
- reads image files directly
- uses `input.redactBeforeCloud`
- builds a fake `PreparedCloudPayload`

This bypasses the architecture, even if intended for tests.

### Implementation

Preferred:
- move test helper to `src/test`
- or replace with a method that accepts only `PreparedCloudPayload`

```kotlin
internal fun buildRequestBodyForPreparedPayloadTest(
    prepared: PreparedCloudPayload
): String = buildRequestPayloadFromPrepared(prepared).jsonBody
```

Do not read files or inspect raw input in provider test helpers.

---

## 3.6 Introduce cloud transport boundary

Long-term fix for static guard weakness:

```kotlin
interface CloudAiTransport {
    suspend fun postPrepared(
        endpoint: String,
        prepared: PreparedCloudPayload,
        modelConfig: CloudModelConfig
    ): CloudAiTransportResult
}
```

Then:
- providers build/receive `PreparedCloudPayload`
- transport builds HTTP body
- static guard forbids `Request.Builder` in providers

Short-term:
- keep providers but add guard/tests
- long-term:
  ```text
  no Request.Builder in data/ai/provider
  ```

---

## Tests

```text
receipt_assist_payload_hash_changes_when_image_bytes_change
receipt_assist_payload_hash_changes_when_mime_changes
receipt_assist_audit_records_image_suppression_reason
receipt_assist_audit_records_image_size_mime_and_hash
receipt_assist_rejects_non_app_owned_image_path
receipt_assist_preserves_context_metadata
cloud_receipt_provider_builds_request_only_from_prepared_payload
main_source_receipt_test_helper_does_not_read_files
```

## Acceptance criteria

```text
1. payloadHash represents final cloud text+image payload.
2. Image suppression is explainable in audit metadata.
3. Non-app-owned image paths are rejected.
4. Provider never reads image files directly, including test helpers in main source.
5. A transport boundary exists or direct provider Request.Builder is statically forbidden.
```

---

# PR 4 — Retention registry completion and target-owned policy

## Goal

Make retention complete, target-owned, and free from worker-level string special cases.

## Fixes

```text
REM-850-11
REM-850-12
```

## Files

```text
DataRetentionWorker.kt
RetentionTarget.kt
RetentionRegistry.kt
RetentionModule.kt
DAO files for sensitive targets
```

---

## 4.1 Change `RetentionTarget` contract

### Current problem

`DataRetentionWorker` still does name-based logic:

```kotlin
when (target.name) {
    "email_receipt_sources" -> emailCutoff
    "ai_chat_messages" -> aiChatCutoff
    else -> now
}
```

### Target

Each target owns its cutoff.

```kotlin
interface RetentionTarget {
    val name: String

    suspend fun cutoff(
        nowMs: Long,
        settings: PrivacySettings
    ): Long

    suspend fun purge(
        cutoffMs: Long,
        nowMs: Long
    ): RetentionPurgeResult
}
```

Worker becomes:

```kotlin
for (target in retentionRegistry.allTargets()) {
    executionGuard.checkpoint("data_retention_${target.name}")
    val cutoff = target.cutoff(now, settings)
    results += target.purge(cutoff, now)
}
```

No worker-level string special casing.

---

## 4.2 Expand registry

Current targets appear to include:

```text
raw_notifications
scanned_receipts.rawOcrText
ai_artifacts
ai_chat_messages
email_receipt_sources
```

Add missing sensitive targets where tables/artifacts exist:

```text
scanned_receipts.parsedItems
debug_exports
parser_debug_artifacts
bank_statement_debug/import artifacts
pipeline_diagnostic_events.metadataJson
operation_run_events.metadataJson
privacy_audit_events.context
cloud_ai_call_events / cloud payload artifacts
email_receipt_sources parsed item fields if separate
backup/export temp artifacts
```

If a target table does not exist, add a documented “not applicable” entry in `docs/privacy/retention-targets.md`.

---

## 4.3 Preserve provenance; redact sensitive fields

For each target decide:

```text
delete row
redact fields
truncate metadata
hash identifiers
```

Examples:

### Email

Keep:

```text
receiptId
provider
emailMessageIdHash
contentFingerprintHash
providerOrderIdHash
parsedAt
```

Redact:

```text
emailSender
emailSubject
emailMessageId
rawBody if present
```

### Scanned receipts parsed items

If `parsedItems` can contain item descriptions:

```sql
UPDATE scanned_receipts
SET parsedItems = NULL
WHERE createdAt < :cutoffMs
```

or store redacted structured items if needed.

### Diagnostics metadata

Do not delete the event row by default.
Redact/replace metadata:

```json
{"retained": true, "sensitiveMetadataPurged": true}
```

---

## 4.4 Make all DAO methods return counts

Ensure every purge/update DAO method returns `Int`.

Examples:

```kotlin
@Query("DELETE FROM ai_chat_messages WHERE createdAt < :cutoffMs")
suspend fun deleteOlderThan(cutoffMs: Long): Int
```

```kotlin
@Query("""
UPDATE pipeline_diagnostic_events
SET metadataJson = '{"sensitiveMetadataPurged":true}'
WHERE occurredAt < :cutoffMs AND metadataJson IS NOT NULL
""")
suspend fun purgeMetadataOlderThan(cutoffMs: Long): Int
```

---

## Tests

```text
retention_worker_has_no_target_name_cutoff_switch
retention_registry_contains_all_sensitive_targets
retention_email_preserves_all_hash_columns
retention_scanned_receipts_parsed_items_redacted
retention_debug_exports_purged
retention_pipeline_diagnostic_metadata_redacted
retention_privacy_audit_context_redacted_or_preserved_by_policy
retention_counts_match_actual_rows_changed
```

## Acceptance criteria

```text
1. Worker no longer decides cutoff by target.name.
2. All sensitive artifact types are registered or explicitly documented as N/A.
3. Retention preserves provenance hashes/links.
4. Counts are accurate.
```

---

# PR 5 — Static privacy guard hardening

## Goal

Make CI catch the current remaining privacy bugs and reduce heuristic bypasses.

## Fixes

```text
REM-850-13
REM-850-15
```

## Files

```text
scripts/verify_privacy_boundaries.py
test fixtures for guard script
CI workflow
```

---

## 5.1 Add guard for email `hashCode()`

Current G5 misses:

```kotlin
raw.hashCode().toString(16)
```

because it only matches certain ID names.

Add:

```text
G14: No .hashCode() in data/email or receipt/email privacy-sensitive paths.
```

Allowlist only if clearly non-sensitive and documented.

---

## 5.2 Add guard for plain SHA on external IDs

Add:

```text
G15: No sha256Prefix(messageId/accountId/providerTransactionId/orderId/transactionId)
     for external identifiers. Use SensitiveHashingService.hmacSha256Prefix.
```

Should flag:

```kotlin
messageId.let { it.sha256Prefix(16) }
```

---

## 5.3 Add guard for raw-sensitive ParseError strings

Add:

```text
G16: No ParseError/Error message interpolation containing merchant, amount,
     sender, subject, body, messageId, emailBody.
```

Flag patterns:

```text
ParseError(".*$merchant")
ParseError(".*merchant=${")
ParseError(".*amount=${")
IllegalArgumentException(".*sender=${")
```

---

## 5.4 Strengthen G12 empty-prompt probe

Current G12 line regex can miss multiline calls.

Scan joined file content:

```python
content = "\n".join(lines)
pattern = re.compile(
    r'prepareText\s*\([^)]*,\s*(""|String\.EMPTY|emptyPrompt)\s*[,)]',
    re.DOTALL
)
```

Also flag helpers that pass empty string into `prepareText`.

---

## 5.5 Strengthen G13 email side-effect correlation

Current G13 matches only exact two-argument positional calls.

Use a multi-line call extractor:
- find `dispatchPostCreationSideEffects(`
- parse until matching `)`
- if call contains `ExpenseSource.EMAIL_RECEIPT`
- require a `correlationId` argument or named `correlationId =`

Flag:

```kotlin
dispatchPostCreationSideEffects(
    expenseId = id,
    source = ExpenseSource.EMAIL_RECEIPT
)
```

---

## 5.6 Strengthen G3 provider HTTP boundary

Best target:

```text
No Request.Builder in data/ai/provider.
Only CloudAiTransport may build HTTP requests.
```

If transport is not implemented yet:
- temporarily allow only if the same method has a local variable named `prepared: PreparedCloudPayload`
- and body builder uses only `prepared.text` / `prepared.imageBytes`

But long-term, use transport and make the rule simple.

---

## 5.7 Add guard for main-source test helpers reading raw files

Flag in cloud providers:

```text
buildRequestBodyForTest
java.io.File(...).readBytes()
input.redactBeforeCloud
```

unless in `src/test`.

---

## 5.8 Add schema guard

Add:

```text
G17: EmailReceiptSource must define:
     emailMessageIdHash
     contentFingerprintHash
     providerOrderIdHash
```

This prevents future schema regression.

---

## Guard tests

Create `PrivacyGuardScriptTest` using temp fixture files.

```text
privacy_guard_flags_email_hashcode_fallback
privacy_guard_flags_plain_sha_message_id
privacy_guard_flags_raw_parse_error_interpolation
privacy_guard_flags_multiline_empty_prompt_probe
privacy_guard_flags_email_dispatch_without_correlation_named_args
privacy_guard_flags_provider_request_builder
privacy_guard_flags_main_source_file_read_test_helper
privacy_guard_requires_email_hash_columns
```

## Acceptance criteria

```text
1. Guard catches all currently known remaining code patterns.
2. Guard fixtures fail before fixes and pass after fixes.
3. CI runs the guard.
```

---

# PR 6 — Real behavioral regression tests

## Goal

Replace documentation-style tests with live-path tests.

## Fixes

```text
REM-850-14
```

## Files

```text
PrivacyBehavioralRegressionTest.kt
EmailReceiptIngestionServicePrivacyTest.kt
EmailReceiptPersistenceIntegrationTest.kt
CloudReceiptAssistProviderTest.kt
CloudReceiptPayloadPolicyTest.kt
DataRetentionWorkerIntegrationTest.kt
NotificationCaptureServicePrivacyTest.kt
PrivacyGuardScriptTest.kt
```

---

## 6.1 Email service tests

### Test hash failure through real service

Use a fake `SensitiveHashingService`:

```kotlin
class FailingHashingService : SensitiveHashingService {
    override fun hmacSha256Prefix(...) = null
    override fun sha256Prefix(...) = null
}
```

Test:

```text
email_message_id_hmac_failure_returns_parse_error_and_does_not_call_coordinator
email_content_fingerprint_failure_returns_parse_error_and_does_not_call_coordinator
```

Verify:
- coordinator fake call count = 0
- result is `ParseError`
- error does not include raw message ID, merchant, amount, sender, subject, body

---

## 6.2 Email persistence integration tests

Use in-memory Room DB or fake DAO.

Sentinels:

```text
SECRET_EMAIL_BODY
SECRET_EMAIL_SUBJECT
SECRET_EMAIL_SENDER
<secret-message-id@example.com>
SECRET_ITEM_DESCRIPTION
```

Tests:

```text
email_metadata_only_persists_hashes_not_raw_values
email_do_not_store_persists_hashes_not_raw_values
email_store_raw_only_mode_allows_raw_message_id
email_retention_redacts_raw_fields_but_preserves_hashes
```

Assertions:
- no sentinel in `email_receipt_sources`
- no sentinel in `scanned_receipts.parsedItems` if policy forbids
- hash columns populated
- raw columns null under restricted modes

---

## 6.3 Cloud provider request tests

Use `MockWebServer` or fake `OkHttpClient`.

Fake policy:

```kotlin
class SentinelCloudPayloadPolicy : CloudPayloadPolicy {
    override suspend fun prepareReceiptAssist(...) =
        PreparedCloudPayload(
            text = "PREPARED_SENTINEL",
            imageBytes = "SAFE_IMAGE".toByteArray(),
            rawImageIncluded = true,
            ...
        )
}
```

Tests:

```text
cloud_receipt_provider_request_contains_prepared_text_only
cloud_receipt_provider_request_uses_prepared_image_bytes_only
cloud_categorization_provider_request_contains_prepared_text_only
cloud_bank_statement_request_uses_prepareBankStatementValidation
```

Assert request body:
- contains `PREPARED_SENTINEL`
- does not contain raw OCR sentinel
- does not contain raw line item sentinel
- includes image only if `prepared.rawImageIncluded`

---

## 6.4 Cloud payload policy tests

Use temp files under approved app-owned root and outside it.

```text
receipt_assist_rejects_non_app_owned_image_path
receipt_assist_rejects_unsupported_mime
receipt_assist_suppresses_image_when_redaction_required
receipt_assist_payload_hash_changes_when_image_changes
receipt_assist_audit_records_suppression_reason
```

---

## 6.5 Retention integration tests

Use actual DAOs.

Insert:
- old email row with raw sender/subject/messageId and hashes
- old scanned receipt with rawOcrText and parsedItems
- old AI chat messages
- old debug artifact if table exists
- old diagnostics metadata if table exists

Run retention target or worker.

Assert:
- raw fields redacted/purged
- hash/provenance fields preserved
- counts match changed rows

Tests:

```text
retention_worker_redacts_live_email_rows
retention_worker_preserves_email_hash_columns
retention_worker_purges_parsed_items
retention_worker_reports_actual_counts
retention_worker_has_all_targets_registered
```

---

## 6.6 Notification tests

If Android/Robolectric test support is available:
- use fake `StatusBarNotification`
- use trap `Bundle` wrapper or test seam around `NotificationTextParts.extract`

Tests:

```text
notification_before_blocked_cache_load_does_not_access_extras
refresh_before_blocked_cache_load_does_not_access_extras
privacy_denied_before_extraction_does_not_access_extras
```

If direct trap extras are too hard, extract a `NotificationPreExtractionGate` and unit-test the gate.

---

## Acceptance criteria

```text
1. Tests execute real service/provider/DAO/worker paths.
2. Sentinel raw values prove no leak to DB or HTTP.
3. Existing documentation-style tests are replaced or demoted.
```

---

# PR 7 — Cloud AI audit/provenance completion

## Goal

Every cloud call has durable, safe provenance for payload policy decisions.

## Fixes

```text
REM-850-07
REM-850-08
REM-850-15
```

## Files

```text
PrivacyAuditEvent.kt
PrivacyAuditLogger.kt
CloudPayloadPolicy.kt
DefaultCloudPayloadPolicy.kt
cloud providers
optional CloudAiCallEvent.kt
```

---

## 7.1 Add or complete typed cloud audit

Recommended short-term:
- extend privacy audit context safely

Recommended long-term:
- add `cloud_ai_call_events`

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
    val imageHash: String?,
    val status: String,
    val errorClass: String?,
    val occurredAt: Long
)
```

---

## 7.2 Thread provenance from `PreparedCloudPayload`

Every provider should log/pass:

```text
provider
model
purpose
payloadHash
redactionApplied
rawTextIncluded
rawImageIncluded
correlationId
status
errorClass
```

No raw prompt, raw image path, raw body, raw OCR, or raw merchant.

---

## Tests

```text
cloud_call_audit_has_provider_model_purpose
cloud_call_audit_has_payload_hash
cloud_call_audit_records_image_inclusion
cloud_call_audit_does_not_include_raw_prompt
cloud_call_audit_does_not_include_raw_image_path
```

## Acceptance criteria

```text
1. Every cloud call is auditable.
2. Audit payload hash matches final sent payload.
3. Audit metadata is safe.
```

---

# PR 8 — Documentation and cleanup

## Goal

Remove drift so future agents can reason safely.

## Files

```text
docs/privacy/raw-storage-policy.md
docs/privacy/cloud-payload-boundary.md
docs/privacy/retention-targets.md
docs/debugging/... trackers
```

## Tasks

```text
1. Document current raw-storage mode semantics.
2. Document email payload-first contract.
3. Document cloud prepared-payload and image policy.
4. Document all retention targets and N/A targets.
5. Document static guard rules and why each exists.
6. Remove outdated comments saying ingestion dispatches side effects.
7. Rename variables:
   - rawMessageIdEphemeral
   - messageIdHash
   - contentFingerprintHash
   - preparedPayload
8. Remove unused imports:
   - sha256Prefix in EmailReceiptIngestionService if no longer used.
9. Remove unused `messageId` param from createFingerprint.
10. Update debugging master tracker with fixed/open status.
```

## Acceptance criteria

```text
1. Docs match code behavior.
2. Future agents do not need to infer privacy boundaries from scattered comments.
3. Static guard and docs describe the same rules.
```

---

# Suggested execution order

```text
PR 1  Email residual privacy hardening
PR 2  Email payload-first persistence contract
PR 3  Cloud receipt payload provenance and image hardening
PR 4  Retention registry completion and target-owned policy
PR 5  Static privacy guard hardening
PR 6  Real behavioral regression tests
PR 7  Cloud AI audit/provenance completion
PR 8  Documentation and cleanup
```

Fastest risk-reduction order:

```text
1. Remove email hashCode fallback + sanitize validation ParseError
2. Add real email hash-failure tests
3. Fix cloud receipt payloadHash/audit/path validation
4. Add static guard rules for the bugs found above
5. Expand retention registry
6. Replace documentation tests with live-path tests
```

---

# Final definition of done

Universal privacy issue #3 can be called done only when:

```text
1. No email path falls back to plaintext, hashCode, or plain SHA for external IDs.
2. Email validation/errors/logs contain no raw merchant, amount, sender, subject, body, or messageId.
3. Email persistence is payload-first and uses explicit hash columns.
4. providerOrderIdHash exists or is documented N/A if no parser exposes order IDs.
5. Restricted email modes persist no raw subject/sender/body/messageId/items.
6. Receipt-assist PreparedCloudPayload hash covers final text+image payload.
7. Receipt image uploads are MIME/path/size validated and audit-explainable.
8. Cloud providers cannot bypass prepared payload construction.
9. Retention targets cover every sensitive artifact or document N/A.
10. Retention cutoffs are target-owned, not worker-name-special-cased.
11. Static guard catches all known regression patterns.
12. Behavioral tests exercise live services, DAOs, providers, and worker paths.
13. Docs match the final implementation.
```

---

# Sources reviewed

- Commit `850c0e8258141a22021eb4a52a299853e1cb60d9`:  
  https://github.com/panospao7/Cost-agregator/commit/850c0e8258141a22021eb4a52a299853e1cb60d9

- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `EmailReceiptSource.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt

- `EmailReceiptDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt

- `PrivacySettingsRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `NotificationCaptureService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `DefaultCloudPayloadPolicy.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadPolicy.kt

- `CloudReceiptAssistService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `CloudCategorizationAssistService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt

- `DataRetentionWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `RetentionModule.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt

- `verify_privacy_boundaries.py`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/scripts/verify_privacy_boundaries.py

- `PrivacyBehavioralRegressionTest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyBehavioralRegressionTest.kt