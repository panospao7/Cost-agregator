# Remaining Privacy / Raw-Storage / Redaction Implementation Plan

Target commit: `441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e`

Scope: finish remaining Universal Issue #3 privacy/raw-storage/redaction gaps.

---

## 0. Remaining issue map

```text
PRIV-441-01 Cloud providers bypass PreparedCloudPayload
PRIV-441-02 Static privacy guard not run in CI
PRIV-441-03 CompositePrivacyGate fail-closed handled-capability set not wired
PRIV-441-04 Cloud provider secondary constructors use allow-all PrivacyGate
PRIV-441-05 Full notification privacy gate still after extras extraction
PRIV-441-06 Blocked package check still after extras extraction
PRIV-441-07 Privacy-denied notification can poison dedupe cache
PRIV-441-08 EmailReceiptPersistencePayload exists but real path does not use it
PRIV-441-09 Email restricted modes lose messageId hash dedupe
PRIV-441-10 Email parsed items can persist raw descriptions
PRIV-441-11 Email correlation not fully propagated
PRIV-441-12 Retention registry inline/incomplete
PRIV-441-13 Raw-storage audit model-level, not persistence-level
PRIV-441-14 Static guard does not enforce PreparedCloudPayload
PRIV-441-15 PrivacyGate docs stale
PRIV-441-16 Need real DataStore corruption integration test
```

---

# PR 1 — Enforce PreparedCloudPayload everywhere

## Fixes

```text
PRIV-441-01
PRIV-441-14
PRIV-441-02
```

## Goal

No cloud provider may construct/send a cloud request from raw prompt/text/image input. All cloud requests must use `CloudPayloadPolicy` and `PreparedCloudPayload`.

## Files

```text
domain/privacy/CloudPayloadPolicy.kt
data/privacy/DefaultCloudPayloadPolicy.kt
data/ai/provider/CloudReceiptAssistService.kt
data/ai/provider/CloudDashboardBriefingService.kt
data/ai/provider/CloudReceiptItemCategorizationService.kt
other data/ai/provider/* cloud classes
scripts/verify_privacy_boundaries.py
.github/workflows/ci.yml
```

## Implementation steps

### 1. Inject `CloudPayloadPolicy` into providers

Replace provider-local redaction decisions:

```kotlin
val shouldRedact = policyResolver.resolve().redactBeforeCloud
val requestPayload = buildRequestPayload(input, allowImage, shouldRedact)
```

with:

```kotlin
val prepared = cloudPayloadPolicy.prepareReceiptAssist(input)
val requestPayload = buildRequestPayloadFromPrepared(prepared)
```

Use appropriate methods:

```text
prepareReceiptAssist
prepareDashboardBriefing
prepareItemCategorization
prepareBankStatementValidation
prepareText
```

### 2. Build HTTP bodies only from prepared payloads

Rules:

```text
JSON prompt/text field = prepared.text
image bytes = prepared.imageBytes only when prepared.rawImageIncluded == true
do not use raw input text/body/prompt in provider request code
```

### 3. Add bank-statement purpose where needed

If a provider is validating/classifying bank statements, call:

```kotlin
prepareBankStatementValidation(...)
```

not generic receipt assist.

### 4. Emit audit/provenance from prepared payload

Every provider call should write or pass along:

```text
purpose
provider
model
payloadHash
redactionApplied
rawTextIncluded
rawImageIncluded
correlationId
```

### 5. Strengthen static guard

In `verify_privacy_boundaries.py` add rules:

```text
No Request.Builder().post(...) in data/ai/provider unless body derives from PreparedCloudPayload.
No provider-local use of input.redactBeforeCloud.
No provider-local use of AiSettings.redactBeforeCloud.
No raw prompt/body variable passed into request JSON.
```

Allowlist only:
- `DefaultCloudPayloadPolicy`
- low-level transport helper that accepts `PreparedCloudPayload`

### 6. Run privacy guard in CI

Add to `.github/workflows/ci.yml`:

```yaml
- name: Verify privacy boundaries
  run: python3 scripts/verify_privacy_boundaries.py --root .
```

## Tests

```text
cloud_receipt_assist_uses_prepared_payload
cloud_dashboard_briefing_uses_prepared_payload
cloud_item_categorization_uses_prepared_payload
bank_statement_cloud_call_uses_bank_statement_validation_purpose
privacy_redact_true_ai_redact_false_redacts_all_provider_payloads
receipt_image_upload_suppressed_when_prepared_payload_suppresses_image
privacy_guard_flags_cloud_provider_request_without_prepared_payload
ci_runs_verify_privacy_boundaries
```

## Acceptance criteria

```text
All cloud providers depend on PreparedCloudPayload.
Static guard and CI prevent direct raw cloud requests from returning.
```

---

# PR 2 — Production PrivacyGate fail-closed wiring

## Fixes

```text
PRIV-441-03
PRIV-441-04
PRIV-441-15
```

## Goal

Sensitive capabilities fail closed when no production gate handles them. Main-source cloud provider constructors must not include allow-all privacy gates.

## Files

```text
domain/privacy/PrivacyCapability.kt
domain/privacy/PrivacyCapabilityHandlingPolicy.kt
domain/privacy/CompositePrivacyGate.kt
domain/privacy/PrivacyGate.kt
di/PrivacyModule.kt
data/ai/provider/*Cloud*Service.kt
tests/privacy/*
```

## Implementation steps

### 1. Add production handling policy

Create:

```kotlin
object PrivacyCapabilityHandlingPolicy {
    val gateHandledCapabilities: Set<PrivacyCapability> = setOf(
        NOTIFICATION_CAPTURE,
        CLOUD_AI,
        RECEIPT_IMAGE_CLOUD,
        BANK_STATEMENT_AI,
        EXTERNAL_GEOCODING,
        BACKGROUND_LOCATION_BACKFILL,
        DEVICE_GPS_LOCATION,
        BACKUP_EXPORT,
        RESTORE_IMPORT,
        EXPENSE_EXPORT,
        EXPENSE_EXPORT_RAW,
        EXPENSE_EXPORT_REDACTED,
        EXPENSE_EXPORT_ENCRYPTED,
        DEBUG_RAW_EXPORT,
        RAW_DATABASE_EXPORT
    )

    val localOnlyCapabilities: Set<PrivacyCapability> = setOf(...)
}
```

Adjust names to actual enum values.

### 2. Wire into DI

In `PrivacyModule`:

```kotlin
CompositePrivacyGate(
    gates = listOf(notificationGate, locationGate, cloudAiGate, backupGate, exportGate),
    auditLogger = auditLogger,
    gateHandledCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
)
```

### 3. Make enum coverage test use production policy

Test:

```text
every PrivacyCapability is either gateHandled or localOnly
sets do not overlap
```

### 4. Remove allow-all gates from main source

Replace secondary constructor `object : PrivacyGate { Allowed }` with either:

```kotlin
object : PrivacyGate {
    override suspend fun check(...) =
        PrivacyDecision.FailClosed("PrivacyGate not configured")
}
```

or remove secondary constructors and create test-only factories in `src/test`.

If unavoidable:
- mark `@VisibleForTesting`
- make `internal`
- fail closed by default

### 5. Fix PrivacyGate docs

Update contract:

```text
Unrelated capability -> NotApplicable
Individual gates do not audit final decisions
CompositePrivacyGate audits final decision
Unhandled gate-handled capability -> FailClosed
```

## Tests

```text
composite_gate_fails_closed_for_unhandled_gate_handled_capability
production_privacy_capability_policy_covers_all_enum_values
privacy_module_passes_gate_handled_capabilities_to_composite
cloud_provider_secondary_constructor_is_fail_closed
privacy_guard_rejects_allow_all_gate_in_main
privacy_gate_contract_docs_match_not_applicable_behavior
```

## Acceptance criteria

```text
No sensitive capability can silently default to Allowed.
No main-source cloud provider has allow-all PrivacyGate fallback.
```

---

# PR 3 — Notification pre-extraction privacy hardening

## Fixes

```text
PRIV-441-05
PRIV-441-06
PRIV-441-07
```

## Goal

Notification extras/title/text must not be read before an authoritative pre-extraction decision allows capture.

## Files

```text
service/NotificationCaptureService.kt
domain/privacy/NotificationCaptureGate.kt
data/repository/NotificationRepository.kt
data/database/dao/BlockedPackageDao.kt
diagnostics tests
```

## Implementation steps

### 1. Add pre-extraction gate

Create/extend `NotificationCaptureGate`:

```kotlin
data class NotificationPreExtractionDecision(
    val allowed: Boolean,
    val reason: DiagnosticReasonCode?,
    val stage: String
)
```

It must check without reading extras:

```text
restore/maintenance state
service shutdown
PrivacySettingsLoadState
notificationCaptureEnabled
fail-closed corruption
blocked package cache
```

### 2. Maintain blocked-package cache

Use an in-memory `StateFlow<Set<String>>` or repository cache populated from `BlockedPackageDao`.

Check package before:

```kotlin
sbn.notification.extras
NotificationTextParts.extract(...)
NotificationFilter.shouldCapture(...)
```

### 3. Reorder `onNotificationPosted`

New order:

```text
sbn null check
create correlationId
emit RECEIVED
pre-extraction gate
  if denied -> terminal diagnostic, return
dedupe check/insert
extract extras/text
filter
full async PrivacyGate check as defense-in-depth
process
```

### 4. Fix dedupe cache poisoning

Do not insert into `processedNotifications` until after pre-extraction privacy/package gate allows.

If insertion must happen earlier, remove entry on every early return.

### 5. Keep full PrivacyGate check

Keep the existing full suspend privacy check inside coroutine, but it should be second-line defense, not the first authoritative gate before raw extraction.

## Tests

```text
privacy_fail_closed_notification_does_not_read_extras
notification_disabled_does_not_read_extras
blocked_package_does_not_read_extras
blocked_package_drop_writes_terminal_diagnostic
privacy_denied_does_not_poison_dedupe_cache
privacy_denied_then_enabled_same_notification_not_dropped_as_duplicate
stale_fast_cache_cannot_read_extras_when_load_state_corrupted
```

## Acceptance criteria

```text
Denied/blocked notification paths do not read extras/text and do not poison dedupe.
```

---

# PR 4 — Email real-path raw-storage enforcement

## Fixes

```text
PRIV-441-08
PRIV-441-09
PRIV-441-10
PRIV-441-11
```

## Goal

The real email ingestion/coordinator path must use `EmailReceiptPersistencePayload`, preserve hashed dedupe IDs in restricted modes, and never persist raw subject/sender/body/items where policy forbids it.

## Files

```text
data/email/EmailReceiptIngestionService.kt
domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
domain/privacy/EmailReceiptPersistencePayload.kt
data/database/entity/EmailReceiptSource.kt
data/database/dao/EmailReceiptSourceDao.kt
domain/transaction/CreateExpenseRequest.kt
```

## Implementation steps

### 1. Build payload in ingestion service

After parse, build:

```kotlin
val payload = emailPayloadBuilder.build(
    subject = subject,
    sender = sender,
    body = emailBody,
    messageId = messageId,
    providerOrderId = providerOrderId,
    parsedItems = items,
    mode = policy.mode
)
```

Raw body remains parser input only.

### 2. Change coordinator API

Replace raw persistence params:

```kotlin
rawEmailBody: String,
sender: String,
subject: String,
messageId: String
```

with:

```kotlin
payload: EmailReceiptPersistencePayload,
correlationId: String
```

If raw fields are still needed for parsing, keep them outside persistence/coordinator.

### 3. Persist messageId hash in restricted modes

Add/use fields:

```text
emailMessageIdHash
contentFingerprintHash
providerOrderIdHash
```

For `STORE_METADATA_ONLY` / `DO_NOT_STORE`:

```text
emailMessageId plaintext = null
emailMessageIdHash = HMAC(messageId)
sourceFingerprint = emailMessageIdHash ?: contentFingerprintHash
```

Never let `sourceFingerprint = ""` if a hash exists.

### 4. Sanitize parsed items

```kotlin
val safeItems = when (mode) {
    STORE_RAW -> rawItems
    STORE_REDACTED -> redactItemDescriptions(rawItems)
    STORE_METADATA_ONLY, DO_NOT_STORE -> null
}
```

### 5. Propagate correlation

When creating expense:

```kotlin
CreateExpenseRequest(..., correlationId = emailCorrelationId)
```

When dispatching side effects:

```kotlin
dispatchPostCreationSideEffects(..., correlationId = emailCorrelationId)
```

## Tests

```text
email_ingestion_uses_email_persistence_payload
email_do_not_store_no_subject_sender_body_message_id_in_real_tables
email_metadata_only_stores_message_id_hash_in_source
email_do_not_store_keeps_message_id_hash_for_dedupe_if_policy_allows
email_duplicate_by_message_id_hash_works_under_metadata_only
email_source_fingerprint_not_empty_when_message_id_hash_available
email_metadata_only_does_not_persist_parsed_item_descriptions
email_redacted_mode_redacts_parsed_item_descriptions
email_expense_created_uses_email_correlation
email_side_effect_uses_email_correlation
```

## Acceptance criteria

```text
Restricted email modes keep dedupe by hash but persist no raw subject/sender/body/messageId/items.
```

---

# PR 5 — Retention registry and real raw-storage persistence tests

## Fixes

```text
PRIV-441-12
PRIV-441-13
```

## Goal

Retention targets must be centrally registered and tested against real persistence surfaces. Raw-storage tests must inspect actual rows, not only payload models.

## Files

```text
data/privacy/DataRetentionWorker.kt
domain/privacy/RetentionTarget.kt
domain/privacy/RetentionRegistry.kt
di/PrivacyModule.kt
all retention targets
privacy integration tests
```

## Implementation steps

### 1. Create injectable registry

```kotlin
class RetentionRegistry @Inject constructor(
    private val targets: Set<@JvmSuppressWildcards RetentionTarget>
) {
    fun allTargets(): Set<RetentionTarget> = targets
}
```

`DataRetentionWorker` uses registry, not inline list.

### 2. Register targets

Minimum:

```text
raw_notifications
scanned_receipts.rawOcrText
scanned_receipts.parsedItems
email_receipt_sources subject/sender/body/message hashes if policy requires
ai_prompts
ai_responses
ai_chat_messages
cloud_payload_artifacts
debug_exports
bank_statement_debug/import artifacts
pipeline/operation diagnostic metadata if retention policy requires
```

### 3. Add real purge tests

Insert old rows with sentinel values. Run retention. Assert values are purged/redacted.

### 4. Add persistence sentinel tests

Use sentinel raw strings for each source and inspect actual persisted rows under `METADATA_ONLY` / `DO_NOT_STORE`.

Sources:

```text
notification
OCR receipt
email
bank
debug/export
```

## Tests

```text
retention_registry_contains_all_sensitive_targets
retention_worker_purges_raw_notifications
retention_worker_purges_raw_ocr
retention_worker_purges_email_subject_sender_body
retention_worker_purges_ai_chat_messages
retention_worker_purges_debug_exports
notification_do_not_store_no_raw_text_in_real_rows
ocr_do_not_store_no_raw_ocr_or_items_in_real_rows
email_metadata_only_no_raw_values_in_real_rows
bank_metadata_only_no_raw_description_reference_in_events
debug_export_redacted_output_has_no_sentinel_values
```

## Acceptance criteria

```text
Real persisted rows prove raw sentinels are absent under restricted modes.
Retention registry is inspectable and complete.
```

---

# PR 6 — Real DataStore corruption integration test

## Fixes

```text
PRIV-441-16
```

## Goal

Production corruption handling must be verified with real DataStore behavior, not only fake repository tests.

## Files

```text
data/privacy/PrivacySettingsRepositoryImpl.kt
test/data/privacy/PrivacySettingsRepositoryImplCorruptionTest.kt
```

## Implementation steps

### 1. Create temp Preferences DataStore

Use temp folder/file and production corruption handler.

### 2. Force corruption

Options:
- write invalid bytes to preferences file
- inject a test corruption handler path if Preferences DataStore corruption is hard to trigger

### 3. Assert state

```kotlin
val state = repository.getLoadState()
assertTrue(state is PrivacySettingsLoadState.CorruptedFailClosed)
```

### 4. Assert fail-closed fields

Check:

```text
notificationCaptureEnabled = false
cloudAiEnabled = false
redactBeforeCloud = true
rawNotificationStorageMode = DO_NOT_STORE
rawOcrStorageMode = DO_NOT_STORE
emailReceiptStorageMode = DO_NOT_STORE
debugDataPersistenceEnabled = false
```

### 5. Assert clean first run differs

Clean empty DataStore:

```text
FirstRunDefault
not CorruptedFailClosed
```

### 6. Assert explicit save recovers

After user saves settings:

```text
LOAD_STATE = NORMAL
getLoadState() = Loaded(...)
```

## Tests

```text
real_datastore_corruption_writes_corrupted_sentinel
real_datastore_corruption_returns_fail_closed_defaults
clean_empty_datastore_is_first_run_not_corruption
saving_settings_after_corruption_marks_normal
```

## Acceptance criteria

```text
Actual production DataStore corruption cannot be misclassified as first run.
```

---

# Final recommended order

```text
PR 1  Cloud payload enforcement + privacy guard CI
PR 2  Production PrivacyGate fail-closed wiring
PR 3  Notification pre-extraction hardening
PR 4  Email real-path raw-storage enforcement
PR 5  Retention registry + real persistence sentinel tests
PR 6  Real DataStore corruption integration test
```

---

# Definition of done

Universal Issue #3 is complete when:

```text
1. Every cloud provider sends only PreparedCloudPayload-derived request bodies.
2. CI runs verify_privacy_boundaries.py.
3. CompositePrivacyGate fails closed for every gate-handled capability without a handler.
4. Notification denied/blocked paths do not read extras/text and do not poison dedupe.
5. Email restricted modes keep dedupe hashes but persist no raw subject/sender/body/messageId/items.
6. Retention targets are centrally registered and complete.
7. Real persistence tests prove no raw sentinel values are stored under restricted modes.
8. Production DataStore corruption path is integration-tested and fail-closed.
```