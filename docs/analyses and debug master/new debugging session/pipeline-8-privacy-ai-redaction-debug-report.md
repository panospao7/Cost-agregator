# Pipeline 8 Debug Report — Privacy Gates / AI / Redaction

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 8 is **substantially improved but not fully clean/stable yet**.

Good foundations exist:

- `PrivacySettings`
- `PrivacySettingsRepository`
- `PrivacyGate`
- `CompositePrivacyGate`
- capability-specific gates:
  - `CloudAiPrivacyGate`
  - `NotificationPrivacyGate`
  - `LocationPrivacyGate`
  - `BackupPrivacyGate`
- `PrivacyAuditLogger`
- `CloudPayloadRedactor`
- `DefaultCloudPayloadRedactor`
- `CloudPiiSanitizer`
- `DataRetentionWorker`
- cloud AI services now mostly use `SecureKeyStorage`, `PrivacyGate`, and redaction.

But the pipeline is still **yellow/orange**, not production-clean, because several privacy contracts are incomplete or inconsistent:

1. privacy settings changes do not immediately stop workers/services;
2. privacy settings and AI settings can disagree;
3. audit logging is noisy and incomplete;
4. raw notification/OCR/email/AI artifacts are still stored first and purged later;
5. retention does not cover all sensitive artifacts;
6. bank-statement cloud text path can send a prebuilt raw prompt;
7. redaction is regex/field-based, not a formal purpose-aware payload contract;
8. geocoding/location privacy enforcement is not statically guaranteed across all providers;
9. raw backup/export remains reachable;
10. denied states are not consistently visible to users.

Overall: **good privacy infrastructure, incomplete enforcement coverage**.

---

# Severity scale

- **P0 / Critical:** direct privacy leak despite disabled setting or cloud/raw export policy.
- **P1 / High:** common path can bypass or delay a privacy setting; audit/retention insufficient for compliance/debug.
- **P2 / Medium:** weak observability, inconsistent UX, regression risk.
- **P3 / Low:** cleanup/maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Privacy settings load from DataStore | Mostly yes. DataStore repository exists. |
| Settings persist across restart | Yes. Preferences DataStore persists toggles. |
| Cloud AI disabled blocks cloud provider | Mostly yes, but split `PrivacySettings` vs `AiSettings` can disagree. |
| Notification capture disabled blocks capture | Partial. Gate exists, but setting changes do not stop active service immediately. |
| Geocoding disabled blocks external calls | Gate exists, but full provider coverage needs guard tests. |
| Backup/export disabled blocks raw export | Partial. `BackupPrivacyGate` blocks raw export only when encrypted backup is enabled; no master export-disable setting. |
| Audit event written for allow/deny | Partial. Gates log decisions, but logs are noisy, incomplete, and lack provider/model/redaction metadata. |
| Redaction before cloud calls | Partial. Most cloud text prompts redacted, but not all paths are guaranteed and bank-statement `suggestFromText(prompt)` is risky. |
| Raw notification/OCR text not stored when forbidden | Not fully. Current model is retention-after-storage, not store-time policy. |
| AI provider fallback deterministic | Mostly yes through hybrid/no-op services. Provenance remains weak. |
| Denied state visible to user | Partial. Many cloud services return null/disabled failure without durable/user-visible reason. |

---

# Positive findings to preserve

## PF-01 — Capability-based privacy gate architecture exists

The app now has a real `PrivacyGate` abstraction and capability enum. This is the right base for policy enforcement.

Important capabilities include:

```text
NOTIFICATION_CAPTURE
CLOUD_AI_GENERAL
CLOUD_AI_BANK_STATEMENT
RECEIPT_IMAGE_CLOUD_UPLOAD
EXTERNAL_GEOCODING
BACKGROUND_LOCATION_BACKFILL
DEVICE_GPS_LOCATION
RAWBACKUP_EXPORT
ENCRYPTED_BACKUP
RAW_NOTIFICATION_RETENTION
RAW_OCR_RETENTION
DEBUG_DATA_PERSISTENCE
OVERPASS_API
TIMBER_PII_LOGGING
```

## PF-02 — Cloud AI defaults are conservative

`PrivacySettings.cloudAiEnabled` defaults to `false`.

`redactBeforeCloud` defaults to `true`.

`receiptImageCloudEnabled` defaults to `false`.

`bankStatementAiEnabled` defaults to `false`.

This is good.

## PF-03 — Cloud image upload has a strong privacy rule

`CloudAiPrivacyGate` denies `RECEIPT_IMAGE_CLOUD_UPLOAD` when redaction is required, because images cannot be meaningfully redacted.

`CloudReceiptAssistService` also suppresses inline image upload when `shouldRedact = true`.

Preserve this rule.

## PF-04 — Cloud services mostly self-defend

Several cloud services now check privacy directly before making HTTP calls, including:

- cloud receipt assist,
- cloud categorization assist,
- cloud dashboard briefing,
- cloud dedupe judge,
- cloud review explanation,
- cloud query interpretation,
- receipt item categorization.

This is good defense-in-depth.

## PF-05 — API keys moved out of `BuildConfig`

Cloud providers use `SecureKeyStorage.getGeminiKey()` instead of compiled API keys.

This closes an important extraction risk.

## PF-06 — Redaction infrastructure exists

`CloudPayloadRedactor` and `DefaultCloudPayloadRedactor` exist.

`CloudPiiSanitizer` redacts common patterns:

```text
email
IBAN
card-like numbers
phone-like numbers
long numbers
```

Merchant names can be hashed through `redactMerchant()`.

## PF-07 — Data retention worker exists

`DataRetentionWorker` purges raw notification fields and raw OCR text after configured retention periods and writes audit events for purges.

## PF-08 — Redacted backups sanitize raw notification/OCR text

`ExportAnonymizer` strips raw OCR and raw notification content from a temporary DB copy.

`.costbackup` also skips receipt image assets when redacted.

---

# Issue P1-01 — Privacy setting changes do not immediately stop active workers/services

## Severity

P1 / High

## Evidence

`PrivacySettingsRepositoryImpl.updateSettings()` has an explicit TODO saying privacy setting changes should immediately cancel active workers and stop capture services instead of waiting for app restart.

This affects at least:

```text
notification capture
location backfill
cloud AI scheduled work
data retention/debug workers
daily briefing worker
```

## Impact

A user can disable a privacy-sensitive feature, but already-running services/workers may continue until their next gate check, worker cycle, or restart.

Example:

```text
User disables notification capture
→ active NotificationListenerService may still receive/extract notifications
```

or:

```text
User disables background location backfill
→ already scheduled worker may run until it checks gate or is rescheduled
```

## Fixing strategy

Privacy settings must be an active runtime policy source, not just a persisted config.

## Implementation plan

1. Add:

```kotlin
interface PrivacyRuntimePolicyApplier {
    suspend fun apply(old: PrivacySettings, new: PrivacySettings)
}
```

2. On update, compare old/new settings and apply side effects:

```text
notificationCaptureEnabled false
→ stop/disable notification capture pipeline
→ cancel related work
→ clear in-memory capture queues if needed

cloudAiEnabled false
→ cancel cloud AI work
→ force hybrid routers into local/no-op mode
→ invalidate cloud request queues

externalGeocodingEnabled false
→ cancel location/geocoding workers

backgroundLocationBackfillEnabled false
→ cancel LocationBackfillWorker

debugDataPersistenceEnabled false
→ purge/disable debug persistence
```

3. Add one central scheduler/canceller using `WorkerSpecScheduler`.

4. Tests:

```text
disable_notification_capture_cancels_capture_work_and_blocks_service
disable_cloud_ai_cancels_daily_briefing_worker
disable_geocoding_cancels_location_backfill_worker
settings_update_writes_privacy_policy_applied_audit_event
```

---

# Issue P1-02 — `PrivacySettings` and `AiSettings` can disagree

## Severity

P1 / High

## Evidence

Cloud privacy is split across two systems:

```text
PrivacySettings.cloudAiEnabled
PrivacySettings.redactBeforeCloud
PrivacySettings.bankStatementAiEnabled
PrivacySettings.receiptImageCloudEnabled
```

and AI-specific settings such as:

```text
AiSettings.allowCloudAi
AiSettings.redactBeforeCloud
AiSettings.receiptImageCloudEnabled
```

Several providers check both, but not uniformly:

- hybrid services route based on `AiSettings`;
- cloud services often check `PrivacyGate`;
- some cloud services also check `AiSettings`;
- `CloudQueryInterpretationService` directly checks the privacy gate, while hybrid routing checks AI settings.

## Impact

The app can enter contradictory states:

```text
Privacy cloud AI disabled, AI settings enabled
AI settings cloud disabled, privacy cloud enabled
Privacy redaction enabled, AI redaction disabled
Receipt image cloud enabled in one settings system only
```

This makes behavior hard to reason about and can lead to bypasses if a cloud service is called directly.

## Fixing strategy

Make `PrivacySettings` the authoritative privacy contract. AI settings may select models/routes, but must not weaken privacy.

## Implementation plan

1. Add a unified resolver:

```kotlin
data class EffectiveCloudAiPolicy(
    val cloudAllowed: Boolean,
    val reason: String?,
    val redactBeforeCloud: Boolean,
    val receiptImageUploadAllowed: Boolean,
    val bankStatementCloudAllowed: Boolean
)
```

2. Source it from privacy settings first:

```text
privacy denies → deny always
AI route disabled → fallback/no-op
AI route cloud allowed only if privacy allows
```

3. Replace direct `settings.allowCloudAi` checks in cloud services with:

```kotlin
cloudAiPolicy.requireAllowed(capability)
```

4. Add consistency tests:

```text
privacy_false_ai_true_denies_cloud
privacy_true_ai_false_uses_noop_or_on_device
privacy_redact_true_ai_redact_false_redacts
receipt_image_requires_both_privacy_and_ai_opt_in
bank_statement_requires_global_cloud_and_bank_statement_toggle
```

---

# Issue P1-03 — Audit logging is noisy and not semantically precise

## Severity

P1 / High

## Evidence

Each concrete gate logs a decision, including `Allowed` for capabilities it does not handle.

With `CompositePrivacyGate`, a single cloud check can produce multiple audit rows like:

```text
Notification gate allowed unrelated cloud capability
Location gate allowed unrelated cloud capability
Backup gate allowed unrelated cloud capability
Cloud AI gate allowed or denied actual capability
```

Also, if `CompositePrivacyGate` catches an exception and fails closed, it returns `Denied`, but there is no guaranteed durable audit event for that fail-closed denial.

## Impact

The audit table can be misleading:

```text
Many ALLOWED rows for gates that did not actually authorize the capability
No clear final effective decision
No clear provider/model/redaction/payload metadata
```

This weakens compliance/debuggability.

## Fixing strategy

Only the composite gate should write the final effective audit decision, or audit records must distinguish `NOT_APPLICABLE` from `ALLOWED`.

## Implementation plan

1. Change gate contract:

```kotlin
sealed interface GateDecision {
    data object NotApplicable
    data object Allowed
    data class Denied(val reason: String)
}
```

2. Concrete gates return `NotApplicable` for unrelated capabilities and do not audit.

3. `CompositePrivacyGate` writes exactly one final audit row:

```text
ALLOWED
DENIED
DENIED_FAIL_CLOSED
```

4. Add metadata:

```text
capability
effectiveDecision
denyingGate
reason
caller
provider
modelId
route
redactionApplied
payloadHash
rawTextIncluded
rawImageUploaded
timestamp
```

5. Tests:

```text
cloud_ai_check_writes_one_audit_event
unsupported_gate_does_not_write_allowed_noise
gate_exception_writes_denied_fail_closed_audit
audit_event_contains_denying_gate_and_reason
```

---

# Issue P1-04 — Audit context can store caller-provided sensitive data

## Severity

P1 / High

## Evidence

`PrivacyAuditLoggerImpl` serializes the arbitrary `context: Map<String, String>` directly to JSON.

Today many contexts are safe, such as `receiptId` or `operation`, but the API allows future callers to pass:

```text
raw notification text
merchant name
email subject
OCR text
file path
prompt snippet
```

## Impact

The privacy audit table itself can become a PII sink.

## Fixing strategy

Make audit context structured and sanitized.

## Implementation plan

1. Replace raw context map with:

```kotlin
data class PrivacyAuditContext(
    val operation: String?,
    val caller: String?,
    val entityType: String?,
    val entityId: String?,
    val provider: String?,
    val modelId: String?,
    val payloadHash: String?,
    val redactionApplied: Boolean?,
    val rawTextIncluded: Boolean?,
    val rawImageUploaded: Boolean?
)
```

2. If map remains, sanitize with allowlist:

```text
allowed keys only
max length
hash unknown values
reject keys containing text/body/raw/prompt/path
```

3. Tests:

```text
audit_context_drops_raw_text_key
audit_context_hashes_unknown_value
audit_context_preserves_safe_receipt_id
```

---

# Issue P1-05 — Raw notification/OCR/email data is stored first and purged later

## Severity

P1 / High

## Evidence

`PrivacySettings` only has retention-day settings:

```text
rawNotificationRetentionDays
rawOcrRetentionDays
```

`DataRetentionWorker` purges old raw fields later.

There is no write-time storage mode like:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

Previous pipeline reports also found raw notification and receipt OCR text are persisted during normal processing.

## Impact

A user may want notification/receipt processing without long-term raw content retention. Current behavior still writes raw text to DB until the retention worker runs.

This is risky for:

```text
bank notifications
email receipts
OCR text
debug exports
backup snapshots before purge
```

## Fixing strategy

Separate “process in memory” from “persist raw text”.

## Implementation plan

1. Extend settings:

```kotlin
enum class RawNotificationStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    STORE_METADATA_ONLY,
    DO_NOT_STORE
}

enum class RawOcrStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    STORE_METADATA_ONLY,
    DO_NOT_STORE
}
```

2. Apply at write time:

```text
parser receives raw text in memory
DB stores according to storage mode
```

3. For metadata-only, keep:

```text
source type
timestamp
fingerprint/hash
parser result
drop reason
entity id
```

not raw body.

4. Tests:

```text
notification_metadata_only_stores_no_title_text_bigText_extras
receipt_ocr_do_not_store_persists_no_rawOcrText
email_receipt_metadata_only_does_not_store_email_body
parser_still_works_with_in_memory_text
```

---

# Issue P1-06 — Retention worker scope is incomplete

## Severity

P1 / High

## Evidence

`DataRetentionWorker` has a TODO saying retention should expand to:

```text
AI artifacts
chat messages
service diagnostics
email receipt sources
```

Current purging covers:

```text
raw notification content
scanned_receipts.rawOcrText
```

## Impact

Sensitive data can remain indefinitely in:

```text
AI prompt/response artifacts
AI chat history
debug diagnostics
email receipt bodies
cloud provider logs/metadata if persisted
service diagnostic messages
```

## Fixing strategy

Retention should cover every sensitive persistence table.

## Implementation plan

1. Create a retention registry:

```kotlin
interface RetentionTarget {
    val name: String
    suspend fun purge(cutoff: Long, now: Long): RetentionPurgeResult
}
```

2. Add targets:

```text
RawNotificationRetentionTarget
ScannedReceiptOcrRetentionTarget
EmailReceiptSourceRetentionTarget
AiArtifactRetentionTarget
AiChatMessageRetentionTarget
ServiceDiagnosticsRetentionTarget
DebugDataRetentionTarget
```

3. Add separate settings:

```text
aiArtifactRetentionDays
emailReceiptRawBodyRetentionDays
debugDiagnosticsRetentionDays
```

4. Tests:

```text
retention_purges_ai_artifact_prompts
retention_purges_ai_chat_messages
retention_purges_email_receipt_raw_body
retention_purges_debug_diagnostics_when_disabled
```

---

# Issue P1-07 — Bank-statement cloud text path can send a prebuilt raw prompt

## Severity

P1 / High

## Evidence

`CloudReceiptAssistService.suggestFromText(prompt)` checks `CLOUD_AI_BANK_STATEMENT`, but sends the provided `prompt` directly as a text part.

The caller is expected to prepare the prompt safely. The method itself does not apply `CloudPayloadRedactor` to the text.

## Impact

Bank statements can contain highly sensitive data:

```text
merchant names
descriptions
account details
counterparty names
transaction references
IBAN/card-like values
```

A direct cloud fallback can leak raw prompt text if the caller forgot to redact.

## Fixing strategy

Cloud services must be self-redacting, not caller-dependent.

## Implementation plan

1. Change API:

```kotlin
suspend fun suggestFromText(
    prompt: String,
    purpose: CloudPayloadPurpose = BANK_STATEMENT_VALIDATION
)
```

2. Add new purpose:

```kotlin
CloudPayloadPurpose.BANK_STATEMENT_VALIDATION
```

3. Resolve effective policy:

```text
if redactBeforeCloud → redact prompt before request
else send raw only if explicit user opt-in allows raw cloud text
```

4. Audit:

```text
payloadHash
redactionApplied
rawTextIncluded
provider
model
```

5. Tests:

```text
bank_statement_suggestFromText_redacts_when_policy_requires
bank_statement_suggestFromText_denied_when_bank_ai_disabled
bank_statement_cloud_audit_records_redaction_applied
```

---

# Issue P1-08 — Redaction is not a formal purpose-aware payload contract yet

## Severity

P1 / High

## Evidence

`DefaultCloudPayloadRedactor.redactText()` uses broad regex redaction and truncation.

Some providers build prompts themselves and decide which fields to redact. Examples:

- receipt assist redacts merchant/OCR/line-items conditionally;
- dashboard briefing redacts some category/upcoming labels;
- item categorization redacts item descriptions with text regex;
- dedupe judge appears to redact merchant/preview unconditionally;
- query interpretation redacts full prompt.

There is still a TODO in `RedactionSanitizer.kt` saying remaining providers need migration.

## Impact

Redaction behavior differs by provider and field. Some sensitive fields remain by design or accident:

```text
exact amount
date
currency
package name
category names
item descriptions that are not regex-detected PII
merchant-like text if sanitized through redactText instead of redactMerchant
```

This is not necessarily always wrong, but it is not a clear enforceable contract.

## Fixing strategy

Define typed cloud payloads per purpose and build prompts only from redacted DTOs.

## Implementation plan

1. Add typed payloads:

```kotlin
data class CloudReceiptPayload(...)
data class CloudDedupePayload(...)
data class CloudDashboardBriefingPayload(...)
data class CloudBankStatementPayload(...)
```

2. Add:

```kotlin
interface CloudPayloadPolicy {
    fun prepare(payload, purpose, policy): PreparedCloudPayload
}
```

3. Prepared payload includes:

```text
promptText
redactionApplied
rawTextIncluded
rawImageIncluded
payloadHash
fieldsRedacted
```

4. Make providers accept only `PreparedCloudPayload`.

5. Add static guard:

```text
no cloud provider may call Request.Builder().post(...) unless it has a PreparedCloudPayload
```

6. Tests:

```text
all_cloud_providers_use_cloud_payload_policy
redactBeforeCloud_true_never_sends_raw_merchant
redactBeforeCloud_true_never_sends_raw_notification_text
receipt_image_upload_false_or_redact_true_never_sends_inlineData
```

---

# Issue P1-09 — Notification privacy gate is too late and runtime state is not cached

## Severity

P1 / High

## Evidence

From Pipeline 1: notification capture currently extracts notification text before the privacy gate in some paths, and setting changes do not stop the service immediately.

`NotificationPrivacyGate` itself only checks the master toggle.

## Impact

When notification capture is disabled, the app may still read notification contents in memory before denying persistence.

This may be unacceptable under a strict privacy model.

## Fixing strategy

Add a fast runtime `NotificationCaptureGate` that is checked before extras extraction.

## Implementation plan

1. Maintain cached privacy state:

```kotlin
class NotificationCaptureGate {
    val captureAllowed: StateFlow<Boolean>
}
```

2. In listener callback:

```text
read packageName only
check restore mode
check captureAllowed
if denied → record diagnostic and return
only then extract text/extras
```

3. On setting update:

```text
captureAllowed=false
cancel capture workers
optionally request listener rebind/stop foreground helper
```

4. Tests:

```text
notification_disabled_does_not_call_extractor
notification_disabled_writes_denied_audit_or_diagnostic
settings_change_to_disabled_blocks_next_callback_without_restart
```

---

# Issue P1-10 — Geocoding/location gate coverage is not statically guaranteed

## Severity

P1 / High

## Evidence

`LocationPrivacyGate` handles:

```text
EXTERNAL_GEOCODING
BACKGROUND_LOCATION_BACKFILL
DEVICE_GPS_LOCATION
OVERPASS_API
```

The codebase contains multiple external/location providers and workers, including:

```text
GeoapifyGeocodingService
GooglePlacesGeocodingService
NominatimGeocodingService
PhotonGeocodingService
OverpassNearbyService
LocationBackfillWorker
AndroidForegroundLocationProvider
```

In this pass, gate existence was verified, but every provider call site was not proven to be protected by a static guard.

## Impact

A future or existing provider can make an external geocoding/network call without checking privacy first.

## Fixing strategy

Make external location/network providers self-defending and add CI guardrails.

## Implementation plan

1. Wrap external geocoding:

```kotlin
class PrivacyAwareGeocodingService(
    private val gate: PrivacyGate,
    private val delegate: GeocodingService
)
```

2. Each provider should check the exact capability:

```text
Geoapify/Google/Nominatim/Photon → EXTERNAL_GEOCODING
Overpass → OVERPASS_API
LocationBackfillWorker → BACKGROUND_LOCATION_BACKFILL
Foreground GPS → DEVICE_GPS_LOCATION
```

3. Add static guard:

```text
all classes ending GeocodingService or NearbyService must inject/use PrivacyGate
```

4. Tests:

```text
external_geocoding_disabled_blocks_geoapify_google_nominatim_photon
overpass_disabled_when_external_geocoding_disabled
background_location_backfill_disabled_skips_worker
device_gps_disabled_blocks_foreground_location_provider
```

---

# Issue P1-11 — Raw backup/export remains reachable

## Severity

P1 / High if reachable in release UI, otherwise P2

## Evidence

`DatabaseBackupRepositoryImpl.exportDatabase()` is deprecated as debug-only, but still exists in production repository interface.

`BackupPrivacyGate` allows raw plaintext export when `encryptedBackupEnabled = false`.

There is no separate setting:

```text
backupExportEnabled
rawPlaintextExportAllowed
```

## Impact

A user or UI path can disable encrypted backup and thereby make raw export allowed.

That is not the same as an explicit plaintext export consent.

## Fixing strategy

Raw export should be impossible in release unless explicitly debug-gated or protected by a strong user confirmation.

## Implementation plan

1. Add release guard:

```kotlin
if (!BuildConfig.DEBUG) {
    return Result.failure(UnsupportedOperationException("Raw DB export disabled in release"))
}
```

2. Replace boolean with explicit policy:

```kotlin
enum class BackupExportPolicy {
    ENCRYPTED_ONLY,
    ENCRYPTED_REDACTED,
    DISABLED,
    DEBUG_RAW_ONLY
}
```

3. Require a one-time user confirmation for raw plaintext export if ever allowed.

4. Tests:

```text
raw_export_rejected_in_release
encrypted_backup_allowed_when_policy_encrypted_only
backup_disabled_blocks_all_export
raw_export_requires_debug_build_and_explicit_policy
```

---

# Issue P1-12 — Denied privacy states are not consistently visible to users

## Severity

P1 / High

## Evidence

Many cloud providers return:

```text
null
AiServiceResult.Failure(AiServiceError.Disabled(...))
Unsupported(...)
```

when privacy denies a call.

This is good for blocking, but user visibility depends on each caller/UI.

There is no unified user-facing privacy-denied event model.

## Impact

User symptoms can look like generic feature failure:

```text
AI unavailable
no receipt suggestion
no dashboard briefing
no geocoding result
no notification capture
```

instead of:

```text
Blocked by privacy setting: Cloud AI disabled
```

## Fixing strategy

Return explicit privacy-denied outcomes from privacy-sensitive pipelines.

## Implementation plan

1. Add shared error:

```kotlin
sealed interface PrivacyBlocked {
    val capability: PrivacyCapability
    val reason: String
}
```

2. Map provider failures to UI states:

```text
Cloud AI disabled
Receipt image upload disabled
External geocoding disabled
Notification capture disabled
Raw export disabled
```

3. Add debug/health screens:

```text
last privacy denied capability
last privacy denied reason
last audit timestamp
```

4. Tests:

```text
cloud_ai_denied_shows_privacy_disabled_state
geocoding_denied_shows_privacy_disabled_state
notification_capture_denied_visible_in_diagnostics
backup_export_denied_shows_specific_reason
```

---

# Issue P2-13 — DataStore fail-closed is only partially fail-closed

## Severity

P2 / Medium, P1 for strict privacy mode

## Evidence

The DataStore corruption handler returns empty preferences.

Defaults then produce:

```text
notificationCaptureEnabled = true
cloudAiEnabled = false
redactBeforeCloud = true
externalGeocodingEnabled = false
encryptedBackupEnabled = true
```

So cloud/location fail closed, but notification capture defaults to enabled.

## Impact

If privacy settings are corrupt, notification capture remains enabled by default.

That may be acceptable if capture is considered core app functionality, but it is not a strict privacy fail-closed posture.

## Fixing strategy

Distinguish first install defaults from corrupted settings defaults.

## Implementation plan

1. Add:

```kotlin
PrivacySettingsLoadState {
    Loaded(settings)
    DefaultFirstRun(settings)
    Corrupted(failClosedSettings)
}
```

2. On corruption, use strict fail-closed:

```text
notificationCaptureEnabled = false
cloudAiEnabled = false
externalGeocodingEnabled = false
debugDataPersistenceEnabled = false
```

3. Show user warning:

```text
Privacy settings were reset for safety.
```

4. Tests:

```text
datastore_corruption_disables_notification_capture
datastore_corruption_disables_cloud_ai
first_run_defaults_can_still_enable_notification_capture
corruption_warning_visible_to_user
```

---

# Issue P2-14 — Cloud audit lacks provider/model/payload provenance

## Severity

P2 / Medium

## Evidence

`PrivacyAuditLoggerImpl` has TODO for:

```text
provider name
model ID
routing decision
redactionApplied
payloadHash
rawImageUploaded
rawTextIncluded
```

Cloud providers generate correlation IDs for HTTP errors, but privacy audit does not consistently store them.

## Impact

You cannot answer:

```text
Which model/provider was called?
Was text redacted?
Was an image uploaded?
Was this routed cloud/on-device/no-op?
Which payload hash was sent?
```

## Fixing strategy

Make cloud request provenance mandatory.

## Implementation plan

1. Add `CloudAiCallAudit` or extend `PrivacyAuditEvent`.

2. Providers must emit:

```text
capability
route
provider
modelId
redactionApplied
fieldsRedacted
payloadHash
rawTextIncluded
rawImageUploaded
result
errorClass
correlationId
```

3. Tests:

```text
cloud_receipt_text_call_audit_has_model_and_payload_hash
cloud_receipt_image_suppressed_audit_rawImageUploaded_false
cloud_query_interpretation_audit_redactionApplied_true
cloud_http_failure_audit_has_correlation_id
```

---

# Issue P2-15 — Test constructors can bypass privacy gates

## Severity

P2 / Medium

## Evidence

Several cloud providers include secondary constructors for tests that use a no-op allow-all `PrivacyGate`.

That is useful for unit testing, but dangerous if those constructors become reachable outside tests.

## Impact

Future code may accidentally instantiate a cloud provider with allow-all gate.

## Fixing strategy

Restrict test-only constructors or replace with explicit test fakes.

## Implementation plan

1. Mark constructors:

```kotlin
@VisibleForTesting
internal constructor(...)
```

2. Move test helpers to test source set.

3. Static guard:

```text
production source must not call constructors that create no-op PrivacyGate
```

4. Tests/CI:

```text
no_noop_privacy_gate_in_main_source_except_test_visibility
```

---

# Issue P2-16 — `TIMBER_PII_LOGGING` capability exists but logging policy is not enforced

## Severity

P2 / Medium

## Evidence

`PrivacyCapability.TIMBER_PII_LOGGING` exists.

Cloud/location/backup services log many messages. Most avoid raw bodies, but there is no visible central logging sanitizer or guard.

## Impact

Future debug logs may leak:

```text
merchant names
notification snippets
file paths
email subjects
prompt bodies
```

## Fixing strategy

Make PII logging opt-in and sanitize log helpers.

## Implementation plan

1. Add:

```kotlin
PrivacySafeLogger
```

2. Ban direct `Timber.d/w/e` with raw domain objects in sensitive packages.

3. Static guard:

```text
cloud/location/notification/receipt packages cannot log raw prompt/body/OCR/text
```

4. Tests:

```text
pii_logging_disabled_redacts_merchant_and_text
debug_data_persistence_disabled_blocks_sensitive_logs
```

---

# Recommended fixing order

## PR 1 — Runtime privacy policy applier

Files:

```text
PrivacySettingsRepositoryImpl.kt
new PrivacyRuntimePolicyApplier.kt
WorkerSpecScheduler.kt
NotificationCaptureService.kt
LocationBackfillWorker.kt
DailyBriefingWorker.kt
```

Fix:

```text
- settings changes immediately cancel/stop affected services/workers
- no restart required for privacy-off changes
```

## PR 2 — Unified cloud AI policy resolver

Files:

```text
PrivacySettings.kt
AiSettingsRepository.kt
CloudAiPrivacyGate.kt
all Cloud*Service.kt
Hybrid*Service.kt
```

Fix:

```text
- privacy settings are authoritative
- AI settings cannot weaken privacy
- one effective cloud policy object
```

## PR 3 — Audit contract cleanup

Files:

```text
PrivacyGate.kt
CompositePrivacyGate.kt
PrivacyAuditLoggerImpl.kt
PrivacyAuditEvent.kt
PrivacyAuditDao.kt
```

Fix:

```text
- NotApplicable vs Allowed
- one final audit event per check
- fail-closed exception audit
- sanitized structured context
```

## PR 4 — Write-time raw data storage policy

Files:

```text
PrivacySettings.kt
NotificationCaptureService.kt
NotificationProcessingPipeline.kt
ReceiptLifecycleCoordinator.kt
ReceiptRepository.kt
EmailReceipt ingestion
```

Fix:

```text
- raw notification/OCR/email body storage modes
- metadata-only and do-not-store modes
```

## PR 5 — Expand retention coverage

Files:

```text
DataRetentionWorker.kt
AiArtifactDao
AiChatMessageDao
EmailReceiptDao
ServiceDiagnosticsDao
Debug diagnostics tables
```

Fix:

```text
- retention registry
- AI/email/debug artifact purge
```

## PR 6 — Cloud payload policy

Files:

```text
CloudPayloadRedactor.kt
DefaultCloudPayloadRedactor.kt
Cloud*Service.kt
DashboardBriefingPromptFormatter.kt
ValidateBankStatementTransactionsUseCase.kt
```

Fix:

```text
- no raw prompt sent without PreparedCloudPayload
- bank statement suggestFromText redacts internally
- audit payload hashes/redaction metadata
```

## PR 7 — Location privacy guardrails

Files:

```text
GeoapifyGeocodingService.kt
GooglePlacesGeocodingService.kt
NominatimGeocodingService.kt
PhotonGeocodingService.kt
OverpassNearbyService.kt
LocationBackfillWorker.kt
AndroidForegroundLocationProvider.kt
```

Fix:

```text
- all external calls self-check privacy
- static guard for providers
```

## PR 8 — Raw export hard disable

Files:

```text
BackupPrivacyGate.kt
DatabaseBackupRepositoryImpl.kt
BackupRestoreViewModel.kt
```

Fix:

```text
- raw export debug-only/release-blocked
- explicit backup export policy
```

---

# Golden tests to add

```text
privacy_settings_persist_across_restart
privacy_settings_corruption_fails_closed_for_cloud_location_and_notification
disable_cloud_ai_blocks_all_cloud_services
disable_cloud_ai_cancels_daily_briefing_worker
disable_notification_capture_blocks_listener_before_extraction
disable_external_geocoding_blocks_all_external_geocoders
disable_background_location_blocks_location_backfill_worker
disable_device_gps_blocks_foreground_location_provider
receipt_image_upload_denied_when_redactBeforeCloud_true
bank_statement_ai_disabled_blocks_suggestFromText
bank_statement_suggestFromText_redacts_prompt_when_required
cloud_receipt_text_prompt_redacted_before_http
cloud_dashboard_prompt_redacted_before_http
cloud_dedupe_prompt_redacted_before_http
cloud_review_prompt_redacted_before_http
cloud_query_prompt_redacted_before_http
raw_notification_metadata_only_stores_no_raw_text
raw_ocr_metadata_only_stores_no_raw_ocr
email_receipt_body_purged_by_retention
ai_artifact_prompt_purged_by_retention
privacy_audit_writes_one_final_decision_per_check
privacy_audit_fail_closed_exception_is_recorded
privacy_audit_context_does_not_store_raw_text
raw_backup_export_rejected_in_release
privacy_denied_state_visible_in_ui_or_diagnostics
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "PrivacyCapability" app/src/main/java
grep -R "privacyGate.check" app/src/main/java
grep -R "allowCloudAi" app/src/main/java
grep -R "redactBeforeCloud" app/src/main/java
grep -R "Request.Builder()" app/src/main/java/com/yourname/expensetracker/data/ai/provider
grep -R "suggestFromText" app/src/main/java
grep -R "rawOcrText" app/src/main/java
grep -R "raw_notifications" app/src/main/java
grep -R "EmailReceipt" app/src/main/java
grep -R "Timber." app/src/main/java/com/yourname/expensetracker/data/ai app/src/main/java/com/yourname/expensetracker/data/location
grep -R "object : PrivacyGate" app/src/main/java
```

Allowed direct cloud HTTP calls should be restricted to:

```text
Cloud*Service implementations only
each must use effective cloud policy + prepared redacted payload + audit
```

Allowed raw sensitive storage should be explicit:

```text
only when user policy says STORE_RAW
otherwise redacted/metadata-only
```

---

# Definition of done

```text
- Disabling a privacy setting takes effect immediately without app restart.
- PrivacySettings is the authoritative privacy contract over AiSettings.
- Every cloud provider self-checks privacy and uses prepared redacted payloads.
- Bank statement cloud text path redacts internally.
- Receipt image upload is impossible when redaction is required.
- CompositePrivacyGate writes exactly one final audit event per check.
- Audit context is structured/sanitized and cannot store raw prompt/body/OCR text.
- Raw notification/OCR/email body storage is controlled at write time.
- Retention covers notification, OCR, email, AI artifacts, AI chats, and debug diagnostics.
- External geocoding/Overpass/GPS/background location calls are all privacy-gated.
- Raw plaintext DB export is release-disabled or explicit debug-only.
- Privacy-denied outcomes are visible to user/debug UI.
- CI/static guards prevent new cloud/privacy bypasses.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `PrivacySettings.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt

- `PrivacyCapability.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyCapability.kt

- `CloudAiPrivacyGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt

- `CompositePrivacyGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt

- `NotificationPrivacyGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt

- `LocationPrivacyGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt

- `BackupPrivacyGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt

- `PrivacySettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `PrivacyAuditLoggerImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt

- `DefaultCloudPayloadRedactor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadRedactor.kt

- `CloudPiiSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt

- `CloudPayloadRedactor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadRedactor.kt

- `RedactionSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/privacy/RedactionSanitizer.kt

- `DataRetentionWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `ExportAnonymizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt

- `CloudQueryInterpretationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt

- `CloudReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `CloudCategorizationAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt

- `CloudDashboardBriefingService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt

- `CloudDedupeJudgeService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt

- `CloudReceiptItemCategorizationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt

- `CloudReviewExplanationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt

- `DashboardBriefingPromptFormatter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/provider/DashboardBriefingPromptFormatter.kt

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt