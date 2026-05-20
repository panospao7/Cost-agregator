# Remaining Privacy / Raw-Storage / Redaction Implementation Plan

Target commit: `43b5cae0d43228a3d5b2f27ae34d9173e358e220`

Scope: remaining Universal Issue #3 privacy/raw-storage/redaction gaps.

---

## 0. Remaining issue map

```text
PRIV-43B-01 Several cloud providers bypass PreparedCloudPayload
PRIV-43B-02 CloudReceiptAssistService prepares only OCR text, then appends raw fields
PRIV-43B-03 CloudReceiptItemCategorizationService uses policy as boolean flag
PRIV-43B-04 Static privacy guard too weak
PRIV-43B-05 Notification refresh reads extras before blocked-package/shutdown checks
PRIV-43B-06 Full notification gate still defense-in-depth, not true pre-extraction authority
PRIV-43B-07 EmailReceiptPersistencePayload not used in real path
PRIV-43B-08 Email coordinator diagnostics lose correlation
PRIV-43B-09 Email side effects lose correlation
PRIV-43B-10 Raw messageId still flows into duplicate detector
PRIV-43B-11 Email source stores hash in raw-named field
PRIV-43B-12 Retention may delete almost all email source rows
PRIV-43B-13 Retention purge counts inaccurate
PRIV-43B-14 Retention registry incomplete
PRIV-43B-15 Main-source cloud constructors still allow-all
PRIV-43B-16 Test constructors with empty CompositePrivacyGate can fail open
PRIV-43B-17 Tests remain mostly model/contract-level, not behavioral
```

---

# Recommended PR order

```text
PR 1  Finish cloud provider PreparedCloudPayload migration
PR 2  Strengthen static privacy guard and remove allow-all gates
PR 3  Notification refresh/pre-extraction parity
PR 4  Email persistence payload + hash/correlation cleanup
PR 5  Retention safety and registry completion
PR 6  Real behavioral privacy tests
```

---

# PR 1 — Finish cloud provider PreparedCloudPayload migration

## Fixes

```text
PRIV-43B-01
PRIV-43B-02
PRIV-43B-03
```

## Goal

Every cloud provider must build request bodies only from `PreparedCloudPayload`.

No provider should directly decide redaction using:

```kotlin
policyResolver.resolve().redactBeforeCloud
input.redactBeforeCloud
AiSettings.redactBeforeCloud
```

No provider should append raw prompt fragments after payload preparation.

## Files

```text
data/ai/provider/CloudReceiptAssistService.kt
data/ai/provider/CloudReceiptItemCategorizationService.kt
data/ai/provider/CloudWarrantyExtractionService.kt
data/ai/provider/CloudCategorizationAssistService.kt
data/ai/provider/CloudDedupeJudgeService.kt
data/ai/provider/CloudReviewExplanationService.kt
domain/privacy/CloudPayloadPolicy.kt
data/privacy/DefaultCloudPayloadPolicy.kt
```

---

## Step 1.1 — Expand CloudPayloadPolicy API

Add purpose-specific methods if missing:

```kotlin
suspend fun prepareReceiptAssist(
    input: ReceiptAssistInput,
    correlationId: String? = null
): PreparedCloudPayload

suspend fun prepareItemCategorization(
    input: ReceiptItemCategorizationInput,
    correlationId: String? = null
): PreparedCloudPayload

suspend fun prepareWarrantyExtraction(
    input: WarrantyExtractionInput,
    correlationId: String? = null
): PreparedCloudPayload

suspend fun prepareCategorizationAssist(
    input: CategorizationAssistInput,
    correlationId: String? = null
): PreparedCloudPayload

suspend fun prepareDedupeJudge(
    input: DedupeJudgeInput,
    correlationId: String? = null
): PreparedCloudPayload

suspend fun prepareReviewExplanation(
    input: ReviewExplanationInput,
    correlationId: String? = null
): PreparedCloudPayload
```

If adding all purpose-specific models is too much, acceptable intermediate pattern:

```kotlin
val rawPrompt = buildFullRawPrompt(input)
val prepared = cloudPayloadPolicy.prepareText(
    purpose = CloudPayloadPurpose.X,
    rawText = rawPrompt,
    context = ...
)
```

But the full raw prompt must be built before policy and the provider must send only `prepared.text`.

---

## Step 1.2 — Fix CloudReceiptAssistService full prompt leak

Current problem:

```text
Only rawOcrText is prepared.
parsedMerchant / parsedTotal / parsedDate / parsedTaxAmount / lineItemsJson are appended after preparation.
```

Required:

```kotlin
val prepared = cloudPayloadPolicy.prepareReceiptAssist(input, correlationId)

val requestBody = buildRequestPayloadFromPrepared(prepared)
```

Provider must not independently add:

```text
raw lineItemsJson
raw merchant
raw OCR
raw date text
raw image bytes
```

Image rule:

```kotlin
if (prepared.rawImageIncluded) {
    use prepared.imageBytes
} else {
    omit image
}
```

Do not read image file directly in provider after policy preparation.

---

## Step 1.3 — Fix CloudReceiptItemCategorizationService policy misuse

Current anti-pattern:

```kotlin
val shouldRedact = cloudPayloadPolicy.prepareText(ITEM_CATEGORIZATION, "").redactionApplied
val prompt = buildPrompt(input, shouldRedact)
```

Replace with:

```kotlin
val prepared = cloudPayloadPolicy.prepareItemCategorization(input, correlationId)
val requestBody = buildRequestBody(prepared.text)
```

Also remove:

```kotlin
merchant.hashCode()
item.description.hashCode()
```

Use:

```kotlin
sensitiveHashingService.hmacSha256Prefix(value, purpose = "itemCategorization")
```

or let `CloudPayloadPolicy` create pseudonyms.

---

## Step 1.4 — Migrate remaining unmigrated providers

For each:

```text
CloudWarrantyExtractionService
CloudCategorizationAssistService
CloudDedupeJudgeService
CloudReviewExplanationService
```

Replace direct flow:

```kotlin
val shouldRedact = policyResolver.resolve().redactBeforeCloud
val prompt = buildPrompt(input, shouldRedact)
Request.Builder().post(promptBody)
```

with:

```kotlin
val prepared = cloudPayloadPolicy.prepareX(input, correlationId)
val body = buildRequestBody(prepared.text)
Request.Builder().post(body)
```

The only payload sent to HTTP must be derived from `prepared.text` and optionally `prepared.imageBytes`.

---

## Step 1.5 — Audit/provenance

Each provider call should record or pass forward:

```text
provider
model
purpose
payloadHash
redactionApplied
rawTextIncluded
rawImageIncluded
correlationId
```

If this is already emitted inside `CloudPayloadPolicy`, provider tests should verify that the call path triggers it.

---

## Tests

```text
cloud_receipt_assist_uses_prepared_full_prompt
cloud_receipt_assist_does_not_append_raw_line_items_after_preparation
cloud_receipt_assist_image_uses_prepared_rawImageIncluded
cloud_item_categorization_request_uses_prepared_text
cloud_item_categorization_does_not_call_prepareText_on_empty_string
cloud_item_categorization_does_not_use_string_hashCode
cloud_warranty_uses_prepared_payload
cloud_categorization_assist_uses_prepared_payload
cloud_dedupe_judge_uses_prepared_payload
cloud_review_explanation_uses_prepared_payload
privacy_redact_true_ai_redact_false_redacts_all_cloud_provider_payloads
```

## Acceptance criteria

```text
1. All cloud providers inject CloudPayloadPolicy.
2. No provider uses EffectiveCloudAiPolicyResolver directly for redaction decisions.
3. No provider posts prompt/body not derived from PreparedCloudPayload.
4. Receipt assist prepares the entire final prompt, not just OCR text.
5. Item categorization uses prepared real prompt, not empty-string policy probe.
```

---

# PR 2 — Strengthen static privacy guard and remove allow-all gates

## Fixes

```text
PRIV-43B-04
PRIV-43B-15
PRIV-43B-16
```

## Goal

CI should fail if a provider bypasses `PreparedCloudPayload` or if main source contains allow-all privacy gates.

## Files

```text
scripts/verify_privacy_boundaries.py
.github/workflows/ci.yml
data/ai/provider/*Cloud*.kt
domain/privacy/PrivacyGate.kt
domain/privacy/CompositePrivacyGate.kt
```

---

## Step 2.1 — Remove allow-all constructors in main source

Find patterns:

```kotlin
object : PrivacyGate {
    override suspend fun check(...) = PrivacyDecision.Allowed
}
```

Replace with fail-closed:

```kotlin
object : PrivacyGate {
    override suspend fun check(...) =
        PrivacyDecision.FailClosed("PrivacyGate not configured")
}
```

For test convenience:
- move allow-all fakes into `src/test`
- or use explicit fake gate in test constructors
- do not keep allow-all gates in `main`

For constructors using:

```kotlin
CompositePrivacyGate(emptyList(), PrivacyAuditLogger.NO_OP)
```

replace with:

```kotlin
CompositePrivacyGate(
    gates = emptyList(),
    auditLogger = PrivacyAuditLogger.NO_OP,
    gateHandledCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
)
```

or a fail-closed fake.

---

## Step 2.2 — Strengthen G3 provider request rule

Current guard is too easy to bypass.

Add rule:

```text
In app/src/main/java/.../data/ai/provider:
  Any Request.Builder().post(...) is forbidden unless the posted request body is created from PreparedCloudPayload.
```

Recommended simple implementation:
- flag `.post(` in provider files
- allow only if file uses a helper named `buildRequestBodyFromPrepared` or `CloudAiTransport.send(prepared, ...)`
- better: introduce `CloudAiTransport` and forbid direct `Request.Builder` in providers entirely

Preferred architecture:

```kotlin
interface CloudAiTransport {
    suspend fun postPrepared(
        endpoint: String,
        prepared: PreparedCloudPayload,
        providerConfig: ProviderConfig
    ): String
}
```

Then static guard rule:

```text
No Request.Builder in data/ai/provider except CloudAiTransport implementation.
```

---

## Step 2.3 — Strengthen G4 allow-all gate rule

Flag any `PrivacyDecision.Allowed` inside an anonymous `object : PrivacyGate` in `main`.

Do not allow it just because `FailClosed` appears nearby.

---

## Step 2.4 — Add hashCode guard in cloud providers

Add:

```text
No .hashCode() in data/ai/provider
```

unless allowlisted for non-sensitive debug code.

---

## Step 2.5 — Ensure CI runs guard

Already added, but keep/verify:

```yaml
- name: Verify privacy boundaries
  run: python3 scripts/verify_privacy_boundaries.py --root .
```

Add script self-tests if available.

## Tests

```text
privacy_guard_flags_cloud_provider_request_without_prepared_payload
privacy_guard_flags_policy_resolver_redact_before_cloud_in_provider
privacy_guard_flags_allow_all_privacy_gate_in_main
privacy_guard_flags_hashcode_in_cloud_provider
privacy_guard_allows_cloud_transport_using_prepared_payload
ci_runs_verify_privacy_boundaries
```

## Acceptance criteria

```text
1. No main-source allow-all PrivacyGate remains.
2. Providers cannot directly post raw request bodies.
3. CI catches PreparedCloudPayload bypasses.
```

---

# PR 3 — Notification refresh and pre-extraction parity

## Fixes

```text
PRIV-43B-05
PRIV-43B-06
```

## Goal

Normal and refresh notification paths must have the same pre-extraction privacy/block/shutdown order.

## Files

```text
service/NotificationCaptureService.kt
domain/privacy/NotificationCaptureGate.kt
data/repository/NotificationRepository.kt
```

---

## Step 3.1 — Mirror normal path in refresh

In `processNotificationBypassDedupe`, enforce this order before reading extras:

```text
1. sbn/package key extraction only
2. create correlationId
3. emit RECEIVED
4. restore/maintenance check
5. shutdown check
6. fast privacy/load-state check
7. blocked package cache check
8. then read sbn.notification.extras
9. extract NotificationTextParts
10. filter
11. full async PrivacyGate check as defense-in-depth
12. process notification
```

No call to:

```kotlin
sbn.notification.extras
NotificationTextParts.extract(...)
NotificationFilter.shouldCapture(...)
```

before steps 4-7.

---

## Step 3.2 — Make fast gate authoritative enough

Ensure cached pre-extraction gate covers:

```text
PrivacySettingsLoadState.CorruptedFailClosed
notificationCaptureEnabled=false
blocked package cache
shutdown
restore/maintenance
```

If full `PrivacyGate` can deny for other reasons, maintain cached state or move a safe pre-extraction gate earlier.

---

## Step 3.3 — Tests with trap extras

Create fake notification/extras object that throws or records access if extras are read.

Tests:

```text
refresh_blocked_package_does_not_read_extras
refresh_shutdown_does_not_read_extras
refresh_privacy_denied_does_not_read_extras
refresh_restore_blocked_does_not_read_extras
refresh_pre_extraction_order_matches_normal_path
```

## Acceptance criteria

```text
Refresh path cannot read notification extras/text when blocked, shutdown, restore-blocked, or privacy-denied.
```

---

# PR 4 — Email real-path persistence payload and correlation cleanup

## Fixes

```text
PRIV-43B-07
PRIV-43B-08
PRIV-43B-09
PRIV-43B-10
PRIV-43B-11
```

## Goal

Real email ingestion/coordinator path must use `EmailReceiptPersistencePayload`; restricted modes keep hash dedupe but persist no raw subject/sender/body/messageId/items.

## Files

```text
data/email/EmailReceiptIngestionService.kt
domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
domain/privacy/EmailReceiptPersistencePayload.kt
data/database/entity/EmailReceiptSource.kt
data/database/dao/EmailReceiptSourceDao.kt
AppDatabase.kt
migration
domain/transaction/CreateExpenseRequest.kt
```

---

## Step 4.1 — Add explicit hash columns

If not already present, add to `EmailReceiptSource`:

```kotlin
val emailMessageIdHash: String?
val contentFingerprintHash: String?
val providerOrderIdHash: String?
```

Migration:

```sql
ALTER TABLE email_receipt_sources ADD COLUMN emailMessageIdHash TEXT;
ALTER TABLE email_receipt_sources ADD COLUMN contentFingerprintHash TEXT;
ALTER TABLE email_receipt_sources ADD COLUMN providerOrderIdHash TEXT;

CREATE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageIdHash
ON email_receipt_sources(emailMessageIdHash);
```

Keep:

```text
emailMessageId = raw message ID only when STORE_RAW allows it.
emailMessageIdHash = HMAC in restricted modes.
```

---

## Step 4.2 — Build payload in ingestion service

In `EmailReceiptIngestionService`, after parsing:

```kotlin
val payload = emailReceiptPayloadBuilder.build(
    subject = subject,
    sender = sender,
    body = emailBody,
    messageId = messageId,
    providerOrderId = parsedOrderId,
    parsedItems = parsedItems,
    correlationId = correlationId
)
```

Raw body remains parser input only.

---

## Step 4.3 — Change coordinator API

Replace:

```kotlin
processEmailReceipt(
    rawEmailBody: String,
    sender: String,
    subject: String,
    messageId: String,
    ...
)
```

with:

```kotlin
processEmailReceipt(
    payload: EmailReceiptPersistencePayload,
    parsedData: EmailReceiptData,
    correlationId: String,
    ...
)
```

or minimally add payload and mark raw params ephemeral-only.

Coordinator persistence must use only payload fields.

---

## Step 4.4 — Use hash for duplicate detector

Replace:

```kotlin
externalSourceId = messageId.ifBlank { null }
```

with:

```kotlin
externalSourceId = payload.messageIdHash ?: payload.contentFingerprintHash
```

Never pass raw message ID to duplicate detector in restricted modes.

---

## Step 4.5 — Parsed items policy

In real coordinator path:

```kotlin
val safeItems = payload.parsedItemsJson
```

or:

```kotlin
when (policy.mode) {
    STORE_RAW -> rawItems
    STORE_REDACTED -> redactedItems
    STORE_METADATA_ONLY, DO_NOT_STORE -> null
}
```

Do not persist raw item descriptions under restricted modes.

---

## Step 4.6 — Correlation propagation

Add correlation to coordinator diagnostics:

```kotlin
private suspend fun emitEmailReceiptDiagnostic(
    ...,
    correlationId: String
)
```

Pass correlation to every diagnostic.

Expense creation:

```kotlin
CreateExpenseRequest(..., correlationId = correlationId)
```

Side effects:

```kotlin
dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT, correlationId)
```

## Tests

```text
email_ingestion_uses_email_persistence_payload
email_metadata_only_stores_message_id_hash_column_not_raw_message_id
email_do_not_store_keeps_message_id_hash_for_dedupe
email_duplicate_by_message_id_hash_works_under_metadata_only
email_source_fingerprint_not_empty_when_message_id_hash_available
email_metadata_only_does_not_persist_parsed_item_descriptions
email_do_not_store_does_not_persist_parsed_items
email_coordinator_dedupe_diagnostic_uses_email_correlation
email_coordinator_error_diagnostic_uses_email_correlation
email_side_effect_uses_email_correlation
email_duplicate_detector_receives_hash_not_raw_message_id
```

## Acceptance criteria

```text
1. Real email path persists only payload-approved fields.
2. Restricted modes retain dedupe hash but not raw message ID.
3. Email diagnostics, expense, and side effects share correlation.
```

---

# PR 5 — Retention safety and registry completion

## Fixes

```text
PRIV-43B-12
PRIV-43B-13
PRIV-43B-14
```

## Goal

Retention must not delete active source rows accidentally. It should redact/purge sensitive fields with correct per-target cutoffs and accurate counts.

## Files

```text
data/privacy/DataRetentionWorker.kt
domain/privacy/RetentionTarget.kt
domain/privacy/RetentionRegistry.kt
di/RetentionModule.kt
DAOs for email/AI/debug/bank/diagnostics
```

---

## Step 5.1 — Per-target retention policy

Each `RetentionTarget` should own its cutoff:

```kotlin
interface RetentionTarget {
    val name: String
    suspend fun cutoff(now: Long, settings: PrivacySettings): Long
    suspend fun purge(cutoffMs: Long): RetentionPurgeResult
}
```

or registry stores:

```kotlin
data class RegisteredRetentionTarget(
    val target: RetentionTarget,
    val retentionMs: Long
)
```

Do not pass `now` to every non-OCR target.

---

## Step 5.2 — Fix email retention behavior

Current risk:

```kotlin
emailReceiptDao().deleteOlderThan(now)
```

Instead:
- use `emailCutoff = now - emailRetentionMs`
- redact sensitive columns, do not delete source rows by default

DAO method:

```kotlin
@Query("""
UPDATE email_receipt_sources
SET emailSender = NULL,
    emailSubject = NULL,
    emailMessageId = NULL,
    rawBody = NULL
WHERE createdAt < :cutoffMs
""")
suspend fun redactSensitiveFieldsOlderThan(cutoffMs: Long): Int
```

Preserve:
```text
receiptId
provider
emailMessageIdHash
contentFingerprintHash
providerOrderIdHash
sourceFingerprint
createdAt
```

---

## Step 5.3 — Accurate purge counts

DAO delete/update methods should return affected row count.

`RetentionPurgeResult.purgedCount` must reflect actual count.

Fix targets currently returning `0`.

---

## Step 5.4 — Complete registry

Minimum targets:

```text
raw_notifications
scanned_receipts.rawOcrText
scanned_receipts.parsedItems
email_receipt_sources sensitive fields
ai_artifacts prompts/responses
ai_chat_messages
cloud_payload_artifacts if any
debug_exports
bank_statement_debug/import artifacts
pipeline_diagnostic_events.metadataJson if retention policy requires
operation_run_events.metadataJson if retention policy requires
privacy_audit_events.context if retention policy requires
```

---

## Tests

```text
retention_email_uses_email_cutoff_not_now
retention_email_redacts_sensitive_columns_not_delete_rows
retention_email_preserves_hashes_and_receipt_links
retention_ai_artifacts_reports_actual_deleted_count
retention_ai_chat_messages_reports_actual_deleted_count
retention_registry_contains_all_sensitive_targets
retention_worker_purges_debug_exports
retention_worker_purges_bank_statement_debug_artifacts
```

## Acceptance criteria

```text
1. Email retention does not delete all source rows.
2. Retention redacts sensitive fields while preserving provenance hashes/links.
3. Counts are accurate.
4. Registry covers all sensitive artifact classes.
```

---

# PR 6 — Real behavioral privacy tests

## Fixes

```text
PRIV-43B-17
```

## Goal

Tests should exercise actual providers/services/coordinators/workers, not only payload models and fake contract helpers.

## Test files to add/upgrade

```text
CloudProviderPreparedPayloadIntegrationTest.kt
NotificationCaptureServicePrivacyTest.kt
EmailReceiptPersistenceIntegrationTest.kt
DataRetentionWorkerIntegrationTest.kt
RawStorageEndToEndTest.kt
PrivacyGuardScriptTest.kt
```

---

## Required behavioral tests

### Cloud providers

Use fake `CloudPayloadPolicy` returning:

```text
prepared.text = "PREPARED_SENTINEL"
```

Assert request body contains `PREPARED_SENTINEL` and not raw input.

```text
cloud_receipt_assist_request_contains_prepared_text_only
cloud_warranty_request_contains_prepared_text_only
cloud_categorization_request_contains_prepared_text_only
cloud_dedupe_request_contains_prepared_text_only
cloud_review_explanation_request_contains_prepared_text_only
```

### Notification

Use trap extras that record access.

```text
normal_privacy_denied_does_not_access_extras
refresh_blocked_package_does_not_access_extras
refresh_shutdown_does_not_access_extras
```

### Email

Use in-memory/fake DAOs and sentinel values:

```text
SECRET_EMAIL_BODY
SECRET_EMAIL_SUBJECT
SECRET_ITEM_DESCRIPTION
<secret-message-id@example.com>
```

Assert no sentinel under restricted modes.

### Retention

Insert actual old rows, run worker/target, assert:
- rows not accidentally deleted
- sensitive fields redacted
- counts correct

### Guard

Run script against temp fixture files:
- raw cloud provider post should fail
- prepared transport should pass
- allow-all main PrivacyGate should fail
- hashCode in provider should fail

## Acceptance criteria

```text
Known bugs from this review would fail tests before fix and pass after fix.
```

---

# Final recommended execution order

```text
PR 1  Finish cloud provider PreparedCloudPayload migration
PR 2  Strengthen static guard + remove allow-all gates
PR 3  Notification refresh/pre-extraction parity
PR 4  Email real-path payload/hash/correlation cleanup
PR 5  Retention safety and registry completion
PR 6  Real behavioral tests
```

---

# Definition of done

Universal Issue #3 is complete when:

```text
1. All cloud providers send only PreparedCloudPayload-derived request bodies.
2. Static guard in CI catches raw provider requests, allow-all gates, and hashCode pseudonyms.
3. Notification normal and refresh paths do not read extras before privacy/package/shutdown approval.
4. Real email path persists no raw subject/sender/body/messageId/items under restricted modes but keeps dedupe hashes.
5. Email diagnostics, expense creation, and side effects share correlation.
6. Retention redacts/purges sensitive fields with correct cutoffs and accurate counts.
7. Real behavioral tests prove no raw sentinel values reach persisted rows or cloud requests.
```