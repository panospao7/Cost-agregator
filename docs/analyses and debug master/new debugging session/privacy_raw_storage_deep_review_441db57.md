# Privacy / Raw-Storage / Redaction Deep Review

Commit reviewed: `441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e`  
Previous reviewed privacy commit: `4b4b5f7a223fe44df0e39be72cca8490b0e45297`

## Executive verdict

`441db57` is mainly a **test-fix / contract-fix commit**, not a broad production hardening commit.

Good:
- `SafePrivacyMetadata.put()` hash-key bypass appears fixed.
- `putHash()` is stricter.
- corruption sentinel logic exists in production repository.
- `RawPersistencePolicyResolver` now uses exhaustive `when` style for AI/debug modes.
- privacy tests were adjusted to include new export capabilities and `NotApplicable` behavior.

But major remaining problems still exist:

1. Core cloud providers still build raw HTTP requests directly and do **not** use `CloudPayloadPolicy` / `PreparedCloudPayload`.
2. The static privacy guard is **not run in CI**.
3. `CompositePrivacyGate` fail-closed handling is not wired in DI because `gateHandledCapabilities` is passed as empty.
4. Notification capture still extracts extras before the full privacy gate and before package-block checks.
5. Email raw-storage policy is not end-to-end: the payload model exists, but the real coordinator path does not use it.
6. Email metadata-only / do-not-store mode appears to lose message-ID hash dedupe.
7. Email parsed items can still persist raw item descriptions under restricted modes.
8. Retention coverage is still hardcoded and incomplete.
9. Raw-storage audit tests remain mostly model-level, not real persistence tests.

---

# 1. What is now resolved or mostly resolved

## 1.1 `SafePrivacyMetadata.put()` hash-key bypass

Status: **mostly resolved**

Previous issue:

```kotlin
put("messageIdHash", "plaintext")
```

could pass because only `putHash()` enforced hash value rules.

Now:
- `put()` calls `sanitizeByKey(...)`
- approved hash keys require hex-like values
- unknown hash-like keys are redacted
- `merge()` re-sanitizes
- `toJson()` final-pass sanitizes

This is a real improvement.

Remaining caveat:
- `Map` and `Collection` values are redacted wholesale, not recursively sanitized. This is privacy-safe but lossy. Acceptable for now unless audit metadata needs structured nested context.

Files:
- `SafePrivacyMetadata.kt`
- `SafePrivacyMetadataValueSafetyTest.kt`

---

## 1.2 Corruption sentinel is present in production

Status: **mostly resolved**

Production `PrivacySettingsRepositoryImpl` now uses:

```kotlin
ReplaceFileCorruptionHandler {
    mutablePreferencesOf(LOAD_STATE_KEY to LOAD_STATE_CORRUPTED)
}
```

and `toLoadState()` maps the corrupted marker to fail-closed settings.

Good.

Remaining:
- tests are still fake-repository/model tests, not a real corrupted DataStore integration test.

File:
- `PrivacySettingsRepositoryImpl.kt`

---

## 1.3 Export capabilities included in capability policy test

Status: **resolved as a test-map fix**

`PrivacyCapabilityHandlingPolicyTest` now includes:

```text
EXPENSE_EXPORT
EXPENSE_EXPORT_RAW
EXPENSE_EXPORT_REDACTED
EXPENSE_EXPORT_ENCRYPTED
DEBUG_RAW_EXPORT
RAW_DATABASE_EXPORT
```

Good, but this test map is not wired into production composite gate. See issue `PRIV-441-03`.

---

# 2. High-priority remaining issues

---

## PRIV-441-01 — Cloud providers still bypass `PreparedCloudPayload`

Severity: **High**  
Type: **actual architecture/privacy gap**  
Pipelines: P8, P3, P10, P12

The plan required:

```text
No cloud provider may build an HTTP request from raw strings directly.
Every cloud request must use PreparedCloudPayload produced by CloudPayloadPolicy.
```

But the providers still directly build prompts/request bodies.

Examples:

### `CloudReceiptAssistService`
- Injects:
  - `CloudPayloadRedactor`
  - `EffectiveCloudAiPolicyResolver`
- Does **not** inject/use `CloudPayloadPolicy`
- Builds request body directly through `buildRequestPayload(...)`
- Uses:
  ```kotlin
  val shouldRedact = policyResolver.resolve().redactBeforeCloud
  val requestPayload = buildRequestPayload(input, allowImage, shouldRedact)
  ```
- `suggestFromText(prompt)` manually redacts and posts JSON directly.

### `CloudDashboardBriefingService`
- Does **not** use `CloudPayloadPolicy`
- Builds request body through `buildRequestBody(input, shouldRedact)`
- Directly posts the JSON.

### `CloudReceiptItemCategorizationService`
- Does **not** use `CloudPayloadPolicy`
- Directly builds prompt/body and posts it.

Impact:
- The central `PreparedCloudPayload` contract exists, but is not enforced.
- Raw payload provenance/audit fields are not guaranteed.
- Providers can drift independently.
- Bank statement validation still routes through a generic receipt-assist text path in at least one service.

Fix:
1. Inject `CloudPayloadPolicy` into every cloud provider.
2. Replace provider-local redaction decisions with:
   ```kotlin
   val prepared = cloudPayloadPolicy.prepareText(...)
   ```
   or source-specific methods:
   ```kotlin
   prepareReceiptAssist(...)
   prepareDashboardBriefing(...)
   prepareItemCategorization(...)
   prepareBankStatementValidation(...)
   ```
3. Build HTTP JSON only from `prepared.text`.
4. For images, only use `prepared.imageBytes` when `prepared.rawImageIncluded == true`.
5. Add audit event using `prepared.auditMetadata`.

Tests:
```text
cloud_receipt_assist_uses_prepared_payload
cloud_dashboard_briefing_uses_prepared_payload
cloud_item_categorization_uses_prepared_payload
cloud_suggest_from_text_uses_bank_statement_validation_purpose
privacy_redact_true_ai_redact_false_redacts_all_provider_payloads
receipt_image_upload_suppressed_by_prepared_payload
```

---

## PRIV-441-02 — Static privacy guard is not run in CI

Severity: **High regression risk**  
Type: **process bug**

`.github/workflows/ci.yml` runs:

```text
verify_event_writers.py
```

but does **not** run:

```text
scripts/verify_privacy_boundaries.py
```

Impact:
- The new privacy guard can pass locally but not protect CI/main branch.
- The current providers likely violate the intended “PreparedCloudPayload only” boundary.
- If the script were added today, its current G2 rule may also flag legitimate `policyResolver.resolve().redactBeforeCloud` references, so the guard needs refinement before CI enforcement.

Fix:
1. Add CI step:
   ```yaml
   - name: Verify privacy boundaries
     run: python3 scripts/verify_privacy_boundaries.py --root .
   ```
2. Refine G2:
   - forbid `input.redactBeforeCloud`
   - forbid `AiSettings.redactBeforeCloud` in providers
   - allow `CloudPayloadPolicy` internals to inspect effective policy
   - ideally forbid direct provider request posts unless request body derives from `PreparedCloudPayload`
3. Add a Gradle task or CI-only script test.

Tests:
```text
ci_runs_verify_privacy_boundaries
privacy_guard_flags_cloud_provider_direct_raw_post
privacy_guard_allows_cloud_payload_policy_redaction_logic
```

---

## PRIV-441-03 — `CompositePrivacyGate` fail-closed set is not wired

Severity: **High**  
Type: **fail-open privacy architecture bug**

`CompositePrivacyGate` supports:

```kotlin
gateHandledCapabilities: Set<PrivacyCapability>
```

and will fail closed if no gate handles a gate-handled capability.

But `PrivacyModule.providePrivacyGate(...)` constructs it as:

```kotlin
CompositePrivacyGate(
    listOf(notificationGate, locationGate, cloudAiGate, backupGate, exportGate),
    auditLogger
)
```

No `gateHandledCapabilities` is passed, so the default empty set is used.

Impact:
- If a new sensitive capability is added and no gate handles it, production composite can still default to Allowed.
- `PrivacyCapabilityHandlingPolicyTest` is only a test-local map; it does not protect production behavior.

Fix:
1. Add a production policy object:
   ```kotlin
   object PrivacyCapabilityHandlingPolicy {
       val gateHandledCapabilities = setOf(...)
       val localOnlyCapabilities = setOf(...)
   }
   ```
2. Inject/pass:
   ```kotlin
   CompositePrivacyGate(
       gates = ...,
       auditLogger = auditLogger,
       gateHandledCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
   )
   ```
3. Make the test assert the production object covers all enum entries.

Tests:
```text
composite_gate_fails_closed_for_unhandled_gate_handled_capability
production_privacy_capability_policy_covers_all_enum_values
privacy_module_passes_gate_handled_capabilities_to_composite
```

---

## PRIV-441-04 — Cloud provider secondary constructors use allow-all privacy gates

Severity: **Medium/High**

Several cloud providers have secondary constructors with:

```kotlin
object : PrivacyGate { ... Allowed }
```

The test guard was loosened to allow this in secondary constructors.

Risk:
- These are in `main` source, not test source.
- If production code accidentally uses a secondary constructor, the provider can bypass privacy gate checks.
- `CloudPayloadPolicy` is not used, so the fallback is still risky.

Fix:
1. Replace allow-all secondary constructors with fail-closed gates:
   ```kotlin
   PrivacyDecision.FailClosed("Test constructor privacy gate not configured")
   ```
2. For tests, use explicit fake/test provider factories in test source.
3. If secondary constructors must remain, annotate `@VisibleForTesting` and make them `internal`.

Tests:
```text
cloud_provider_secondary_constructor_is_fail_closed
privacy_guard_rejects_allow_all_gate_in_main_even_in_secondary_constructor
```

---

# 3. Notification privacy issues

---

## PRIV-441-05 — Full privacy gate still happens after extras extraction

Severity: **High**  
Type: **actual privacy boundary gap**  
Pipeline: P1 Notification

The service now has a fast cache:

```kotlin
capturePrivacyDenied
```

and checks it before extraction. Good.

But the full suspend `PrivacyGate.check(NOTIFICATION_CAPTURE)` still happens after:

```text
sbn.notification.extras
NotificationTextParts.extract(extras)
NotificationFilter.shouldCapture(...)
```

If the fast cache is stale, incomplete, or does not reflect a gate other than the simple setting, raw extras can still be read before the full gate denies.

Original invariant wanted:

```text
full PrivacyGate decision before extras/text extraction
```

Fix:
1. Add `NotificationCaptureGate` with a cheap pre-extraction decision:
   - restore mode
   - privacy settings load state
   - notification capture setting
   - blocked package cache
   - shutdown
2. Maintain an in-memory blocked-package cache if DB lookup is too expensive.
3. Only extract extras after that gate says allowed.
4. Keep full gate inside coroutine as defense in depth, but it should not be the first authoritative gate.

Tests:
```text
privacy_fail_closed_notification_does_not_read_extras
privacy_setting_disabled_does_not_read_extras
blocked_package_does_not_read_extras
stale_fast_cache_cannot_read_extras_when_load_state_corrupted
```

---

## PRIV-441-06 — Blocked package check happens after extras extraction

Severity: **Medium/High**  
Pipeline: P1 Notification

`repository.isPackageBlocked(packageName)` is inside `processNotification(...)`, after extras extraction and filtering.

Impact:
- blocked packages can still have their notification text extracted and filtered before the package policy drop.

Fix:
- maintain a package-block cache updated from `BlockedPackageDao`
- check it before:
  ```text
  sbn.notification.extras
  NotificationTextParts.extract(...)
  ```

Tests:
```text
blocked_package_drop_does_not_read_notification_extras
blocked_package_drop_writes_terminal_diagnostic
```

---

## PRIV-441-07 — Privacy-denied notifications still poison dedupe cache

Severity: **Medium**  
Pipeline: P1 Notification

Flow:

```text
processedNotifications[coarseDedupeKey] = now
if (isPrivacyDeniedFast()) return
```

The cache entry is not removed because the `finally` cleanup only runs inside the later work-tracked coroutine.

Impact:
- if privacy was temporarily denied or the cached state changes, the same notification can be incorrectly treated as duplicate for the dedupe window.
- violates earlier requirement:
  ```text
  privacy denied does not poison dedupe cache
  ```

Fix:
- move dedupe-cache insertion after the fast privacy gate
- or remove the entry before every early return after insertion

Tests:
```text
privacy_denied_does_not_poison_dedupe_cache
privacy_denied_then_enabled_same_notification_not_dropped_as_duplicate
```

---

# 4. Email / receipt privacy issues

---

## PRIV-441-08 — `EmailReceiptPersistencePayload` exists but is not used in real ingestion

Severity: **High**  
Pipeline: P11 Email, P3 Receipt

`EmailReceiptPersistencePayload` has the desired policy contract, but `EmailReceiptIngestionService` and `ReceiptLifecycleCoordinator.processEmailReceipt(...)` do not use it.

The real path still passes raw data:

```kotlin
EmailReceiptData(
    messageId = messageIdHash,
    from = sender,
    subject = subject,
    body = emailBody,
    ...
)

receiptLifecycleCoordinator.processEmailReceipt(
    rawEmailBody = emailBody,
    sender = sender,
    subject = subject,
    messageId = messageId,
    provider = provider
)
```

The coordinator partially sanitizes:
- raw OCR/body via `sanitizeRawOcr`
- sender/subject via `sanitizeEmailSender` / `sanitizeEmailSubject`

But not consistently for all targets.

Fix:
1. Build `EmailReceiptPersistencePayload` in the ingestion service.
2. Pass only the payload to the coordinator for persistence.
3. Keep raw body only as ephemeral parser input.
4. Remove raw `sender`, `subject`, `rawEmailBody`, `messageId` persistence parameters or clearly separate ephemeral vs persisted values.

Tests:
```text
email_ingestion_uses_email_persistence_payload
email_do_not_store_no_subject_sender_body_message_id_in_real_tables
email_metadata_only_no_plain_subject_sender_body_message_id_in_real_tables
```

---

## PRIV-441-09 — Email message-ID hash dedupe is broken in restricted modes

Severity: **High**  
Type: **actual functional/privacy bug**

In `EmailReceiptIngestionService`:

```kotlin
val messageIdHash = hashingService.hmacSha256Prefix(messageId, "emailMessageId")
val coordinatorEmailData = EmailReceiptData(messageId = messageIdHash, ...)
```

But `ReceiptLifecycleCoordinator.processEmailReceipt(...)` ignores that for source fingerprint/message ID lookup and uses the raw `messageId` parameter:

```kotlin
RawContentSanitizer.sanitizeEmailMessageId(messageId, emailStorageMode)
```

For `STORE_METADATA_ONLY` and `DO_NOT_STORE`, `sanitizeEmailMessageId(...)` returns `null`.

Then:
```kotlin
sourceFingerprint = ... ?: ""
emailMessageId = null
```

Impact:
- metadata-only/do-not-store mode does **not** keep message-ID hash for dedupe, contrary to the payload tests.
- source fingerprint can become empty, increasing duplicate/constraint weirdness.
- the model-level test passes, but the real path does not.

Fix:
1. Use:
   ```kotlin
   sanitizeEmailMessageIdWithHash(messageId, messageIdHash, emailStorageMode)
   ```
2. Pass `messageIdHash` explicitly to coordinator.
3. Store hash in:
   - `EmailReceiptSource.emailMessageIdHash` column if added
   - or `emailMessageId` temporarily only if clearly documented as hashed value
4. Never use raw message ID in restricted modes.

Tests:
```text
email_metadata_only_stores_message_id_hash_in_source
email_do_not_store_keeps_message_id_hash_for_dedupe_if_policy_allows
email_duplicate_by_message_id_hash_works_under_metadata_only
email_source_fingerprint_not_empty_when_message_id_hash_available
```

---

## PRIV-441-10 — Email parsed items can persist raw item descriptions under restricted modes

Severity: **High**  
Pipeline: P11 / P3

In coordinator:

```kotlin
parsedItems = emailData.items
```

This is unconditional.

If `emailData.items` contains raw item descriptions parsed from the email body, those descriptions can persist even when:

```text
emailReceiptStorageMode = STORE_METADATA_ONLY
emailReceiptStorageMode = DO_NOT_STORE
```

The payload model says `parsedItemsJson` should be null unless raw/redacted mode allows it, but the real path does not use that model.

Fix:
```kotlin
val safeParsedItems = when (emailStorageMode) {
    STORE_RAW -> emailData.items
    STORE_REDACTED -> redactLineItemDescriptions(emailData.items)
    STORE_METADATA_ONLY, DO_NOT_STORE -> null
}
```

Tests:
```text
email_metadata_only_does_not_persist_parsed_item_descriptions
email_do_not_store_does_not_persist_parsed_items
email_redacted_mode_redacts_parsed_item_descriptions
```

---

## PRIV-441-11 — Email side-effect and transaction correlation is still lost

Severity: **Medium**

In `EmailReceiptIngestionService`, after coordinator success:

```kotlin
coordinator.dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT)
```

No correlation ID is passed.

Inside `ReceiptLifecycleCoordinator.processEmailReceipt(...)`, create expense request also appears to omit the email correlation.

Impact:
- privacy/diagnostic trace from email intake to expense/side effects is broken.
- not raw-storage leakage, but affects auditability.

Fix:
- pass email `correlationId` into coordinator
- set `CreateExpenseRequest.correlationId`
- pass to post-creation side effects

Tests:
```text
email_expense_created_uses_email_correlation
email_side_effect_uses_email_correlation
```

---

# 5. Retention and raw-storage audit issues

---

## PRIV-441-12 — Retention registry is still inline and incomplete

Severity: **Medium/High**

`DataRetentionWorker` creates inline `RetentionTarget` objects:

```text
raw_notifications
scanned_receipts.rawOcrText
ai_artifacts
email_receipt_sources
```

The file itself comments remaining gaps:

```text
chat messages
debug diagnostics
```

The test `retention_registry_covers_all_sensitive_targets()` is ineffective because it asserts a hardcoded set contains itself.

Impact:
- sensitive artifacts can be missed.
- retention cannot be centrally audited or extended safely.

Fix:
1. Create injectable `RetentionRegistry`.
2. Register all targets through DI.
3. Include at least:
   - raw notifications
   - scanned receipt raw OCR
   - scanned receipt parsed items if raw-bearing
   - email source subject/sender/body/message IDs
   - AI prompts/responses/chat messages
   - debug exports
   - diagnostics metadata redaction/purge if policy requires
   - bank statement debug/import artifacts
4. Make tests inspect the real registry.

Tests:
```text
retention_registry_contains_all_sensitive_targets
retention_worker_purges_email_subject_sender_body
retention_worker_purges_ai_chat_messages
retention_worker_purges_debug_exports
retention_worker_purges_diagnostics_metadata_if_configured
```

---

## PRIV-441-13 — Raw-storage audit remains model-level, not end-to-end

Severity: **High regression risk**

`RawStoragePolicyAuditTest` mostly validates payload builders and a synthetic policy matrix.

It does **not** inspect actual persisted rows from:
- notification repository/pipeline
- receipt lifecycle coordinator
- email ingestion/coordinator
- bank sync/lifecycle
- export/debug/backup paths

Impact:
- tests can pass while real persistence still stores raw values.
- this is exactly happening in email message-ID / parsed-items paths.

Fix:
- add integration-style tests with fake/in-memory DAOs where possible.
- insert sentinel raw values and assert they do not appear in any persisted row under restricted modes.

Tests:
```text
notification_do_not_store_no_raw_text_in_real_raw_notification_or_pending_review
email_metadata_only_no_raw_values_in_email_source_scanned_receipt_receipt_event
ocr_do_not_store_no_raw_ocr_or_items_in_scanned_receipt
bank_metadata_only_no_raw_description_reference_in_transaction_events
debug_export_redacted_output_has_no_sentinel_values
```

---

# 6. Cloud/static-guard issues

---

## PRIV-441-14 — Static guard does not enforce PreparedCloudPayload

Severity: **High**

The script says:

```text
G3 No Request.Builder().post(...) in cloud package using a raw String prompt directly.
```

But implementation does not actually include a G3 rule scanning direct request body construction in cloud provider files.

Also cloud providers currently use direct:

```kotlin
Request.Builder().post(requestBody.toRequestBody(...))
```

Fix:
1. Add actual G3:
   - flag `Request.Builder` / `.post(` in `data/ai/provider`
   - allow only if nearby body variable is derived from `PreparedCloudPayload`
2. Add allowlist only for lower-level HTTP transport if you introduce one.
3. Run script in CI.

Tests:
```text
privacy_guard_flags_cloud_provider_request_without_prepared_payload
privacy_guard_allows_cloud_transport_using_prepared_payload
```

---

## PRIV-441-15 — `PrivacyGate` contract docs are stale/inconsistent

Severity: **Low/Medium**

`PrivacyGate.kt` still says unrecognized capabilities must return `Allowed`, while current behavior and tests expect `NotApplicable`.

Also docs say each gate must audit every check, but implementation comments/tests say individual gates do not audit; `CompositePrivacyGate` audits.

Fix docs:
```text
Unrecognized -> NotApplicable
Individual gates do not audit
Composite gate performs final audit
Denied / FailClosed block
Allowed only means this gate positively handles the capability
```

Tests:
```text
privacy_gate_contract_docs_match_not_applicable_behavior
```

---

# 7. Corruption integration issue

---

## PRIV-441-16 — No real DataStore corruption integration test

Severity: **Medium**

Production code likely works now, but tests are fake-model tests.

Fix:
- create a real Preferences DataStore test with a corrupted file or injected corruption handler.
- assert actual production repository emits `CorruptedFailClosed`.

Tests:
```text
real_datastore_corruption_writes_corrupted_sentinel
real_datastore_corruption_returns_fail_closed_defaults
clean_empty_datastore_is_first_run_not_corruption
saving_settings_after_corruption_marks_normal
```

---

# 8. Acceptance matrix after `441db57`

| Criterion | Status | Notes |
|---|---:|---|
| Corruption sentinel production path | Mostly | Good, but no real DataStore test |
| `SafePrivacyMetadata.put()` hash-key safe | Mostly | Good |
| `SafePrivacyMetadata` value-safety | Mostly | Maps/lists redacted wholesale |
| Cloud providers use PreparedCloudPayload | Not done | Providers still build requests directly |
| Static privacy guard CI-enforced | Not done | CI does not run script |
| Composite gate fail-closed for unhandled sensitive capability | Not done | DI passes empty gateHandledCapabilities |
| Notification full gate before extras extraction | Partial | Fast setting cache only; full gate after extraction |
| Blocked package before extras extraction | Not done | DB check after extraction |
| Privacy denied does not poison dedupe cache | Not done | cache entry set before fast denial return |
| Email message ID hash under restricted modes | Broken | real path loses hash/dedupe |
| Email parsed items policy | Broken | raw items persisted unconditionally |
| Retention registry real coverage | Partial | inline and incomplete |
| Raw-storage end-to-end tests | Not done | model-level only |
| Export/debug/backup end-to-end policy proof | Unknown/partial | requires deeper persistence/export tests |

---

# 9. Recommended next PR order

## PR 1 — Cloud payload enforcement

Fix:
```text
PRIV-441-01
PRIV-441-14
```

Goal:
```text
All cloud providers use CloudPayloadPolicy / PreparedCloudPayload.
Static guard and CI enforce it.
```

## PR 2 — Production privacy gate fail-closed wiring

Fix:
```text
PRIV-441-03
PRIV-441-15
```

Goal:
```text
Gate-handled capabilities fail closed if no gate handles them.
Docs/tests align with NotApplicable.
```

## PR 3 — Notification pre-extraction privacy hardening

Fix:
```text
PRIV-441-05
PRIV-441-06
PRIV-441-07
```

Goal:
```text
No extras/text extraction before fast authoritative gate and blocked-package check.
Privacy-denied paths do not poison dedupe cache.
```

## PR 4 — Email real-path raw storage fix

Fix:
```text
PRIV-441-08
PRIV-441-09
PRIV-441-10
PRIV-441-11
```

Goal:
```text
EmailReceiptPersistencePayload is used in real ingestion/coordinator path.
Message ID hash dedupe works under restricted modes.
Parsed items obey policy.
Correlation propagates.
```

## PR 5 — Retention registry and real raw-storage tests

Fix:
```text
PRIV-441-12
PRIV-441-13
```

Goal:
```text
Real retention registry, real persistence tests with sentinel raw values.
```

## PR 6 — Real DataStore corruption test

Fix:
```text
PRIV-441-16
```

Goal:
```text
Production corruption handler is proven by real DataStore test.
```

---

# 10. Highest-priority bug list

Fix these first:

```text
1. Cloud providers still bypass PreparedCloudPayload.
2. CompositePrivacyGate fail-closed set is not wired in production DI.
3. Email restricted modes lose message ID hash dedupe.
4. Email parsed item descriptions persist under metadata-only/do-not-store.
5. Notification blocked package/full gate still happen after extras extraction.
6. Privacy-denied notification path poisons dedupe cache.
7. Static privacy guard is not run in CI.
```

---

# Sources checked

- Commit:
  https://github.com/panospao7/Cost-agregator/commit/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e

- `SafePrivacyMetadata.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadata.kt

- `PrivacySettingsRepositoryImpl.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `NotificationCaptureService.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `EmailReceiptIngestionService.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `ReceiptLifecycleCoordinator.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `CloudReceiptAssistService.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `CloudDashboardBriefingService.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt

- `CloudReceiptItemCategorizationService.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt

- `CloudPayloadPolicy.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadPolicy.kt

- `PrivacyModule.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt

- `CompositePrivacyGate.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt

- `DataRetentionWorker.kt`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `verify_privacy_boundaries.py`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/scripts/verify_privacy_boundaries.py

- `.github/workflows/ci.yml`:
  https://raw.githubusercontent.com/panospao7/Cost-agregator/441db57c0596cf0ec1f6b7de7c7a8336b74f2b0e/.github/workflows/ci.yml