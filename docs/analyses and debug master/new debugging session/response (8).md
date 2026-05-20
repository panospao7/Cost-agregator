I created a PR-by-PR implementation plan for the remaining issues, focused on what is still open after `fb58e69`.

<remaining_implementation_plan_post_fb58e69.md>
# Remaining Implementation Plan — post-`fb58e690e2d89d691df2b548ef54f972eddcda61`

## Already fixed; do not rework
- PrivacySettings fail-closed load/update behavior
- blocked-package startup fail-closed
- email hash columns / nullable email fields
- duplicate email side-effect dispatch
- raw sender removed from the two log lines

## Remaining issues
1. Email hashing still has unsafe fallbacks
2. Email diagnostics still leak raw sensitive content
3. Cloud receipt payload provenance is incomplete
4. Static privacy guard is still heuristic/bypassable
5. “Behavioral” tests are mostly contract/spec tests
6. Retention registry is still incomplete
7. Cloud audit metadata is not fully propagated

---

## PR1 — Email privacy hardening
### Goal
No raw or weak fallback identifier survives the email path.

### Files
- `EmailReceiptIngestionService.kt`
- `ReceiptLifecycleCoordinator.kt`
- `SensitiveHashingService.kt` usage sites
- email diagnostic builders/tests

### Tasks
- [ ] Remove plaintext fallback in `messageIdHash`
  - current bad path: `hmacSha256Prefix(...) ?: messageId`
  - fail closed instead
- [ ] Remove `hashCode()` fallback in `createFingerprint(...)`
  - current bad path: `sha256Prefix(...) ?: raw.hashCode().toString(16)`
  - use HMAC/SHA only, never `hashCode()`
- [ ] Replace `sourceIdHash = messageId.sha256Prefix(...)` with keyed hashing
- [ ] Stop returning raw parsed merchant/amount in `ParseError`
- [ ] Replace `require(...){ "sender=$sender" }` with sanitized message
- [ ] Review `Timber.w(... result.errors)` for raw-sensitive payloads
- [ ] Keep raw `messageId` only ephemeral; never persist/fallback raw

### Acceptance tests
- [ ] `email_hash_fallback_never_uses_plaintext_message_id`
- [ ] `email_fingerprint_never_uses_string_hashCode`
- [ ] `email_validation_error_does_not_expose_merchant_or_amount`
- [ ] `email_insert_failure_does_not_include_raw_sender`

---

## PR2 — Cloud receipt provenance / image hardening
### Goal
The prepared cloud payload must describe the actual payload sent.

### Files
- `DefaultCloudPayloadPolicy.kt`
- `CloudReceiptAssistService.kt`
- `PreparedCloudPayload.kt`
- cloud audit/provenance code

### Tasks
- [ ] Make `payloadHash` reflect final payload, not only text
  - include image bytes when `rawImageIncluded=true`
- [ ] Add image suppression reason to `auditMetadata`
- [ ] Add image MIME and image size to audit metadata
- [ ] Thread `SafePrivacyMetadata context` through and preserve it
- [ ] If possible, validate image source/path is app-owned before reading
- [ ] Keep provider code from touching raw image bytes except via prepared payload
- [ ] Do not silently ignore provenance fields

### Acceptance tests
- [ ] `receipt_assist_payload_hash_changes_when_image_changes`
- [ ] `receipt_assist_audit_records_image_suppression_reason`
- [ ] `receipt_assist_rejects_non_app_owned_image_path`
- [ ] `receipt_assist_preserves_context_metadata`

---

## PR3 — Static privacy guard hardening
### Goal
CI should catch real bypasses, not just suspicious text.

### Files
- `scripts/verify_privacy_boundaries.py`
- cloud provider files
- email ingestion file
- CI workflow if needed

### Tasks
- [ ] Strengthen G3
  - forbid direct provider `Request.Builder().post(...)` unless it is clearly routed through prepared transport
- [ ] Strengthen G4
  - catch allow-all anonymous `PrivacyGate` in `main`
- [ ] Strengthen G12
  - detect empty-prompt probes even if wrapped across lines/helpers
- [ ] Strengthen G13
  - detect missing correlation even with named args/wrappers
- [ ] Add new rules for:
  - [ ] `messageIdHash` plaintext fallback
  - [ ] `hashCode()` fallback in email fingerprinting
  - [ ] raw sender/merchant/amount in exception strings
- [ ] Prefer a transport abstraction over regex-only enforcement

### Acceptance tests
- [ ] `privacy_guard_flags_raw_provider_post_without_prepared_payload`
- [ ] `privacy_guard_flags_allow_all_privacy_gate_in_main`
- [ ] `privacy_guard_flags_empty_prompt_probe`
- [ ] `privacy_guard_flags_plaintext_hash_fallback`
- [ ] `privacy_guard_flags_raw_sensitive_exception_text`

---

## PR4 — Real behavioral regression tests
### Goal
Replace model/contract tests with live-path tests.

### Files
- `PrivacyBehavioralRegressionTest.kt`
- new integration tests for email/notification/retention/cloud

### Tasks
- [ ] Test actual `EmailReceiptIngestionService` path
- [ ] Test actual `ReceiptLifecycleCoordinator`
- [ ] Test actual `NotificationCaptureService`
- [ ] Test actual `DataRetentionWorker`
- [ ] Test actual cloud provider request construction
- [ ] Use sentinel raw inputs and assert they never reach DB/request bodies
- [ ] Remove tests that only assert constants or policy objects

### Must prove
- [ ] single email side-effect dispatch
- [ ] email correlation survives end-to-end
- [ ] email restricted modes store hashes only
- [ ] cloud prepared payload is the only request source
- [ ] retention actually redacts/purges live rows
- [ ] notification capture does not read extras too early

### Acceptance tests
- [ ] `email_ingestion_live_flow_single_dispatch`
- [ ] `email_restricted_mode_persists_hashes_not_raw_values`
- [ ] `cloud_receipt_assist_request_contains_prepared_text_only`
- [ ] `retention_worker_redacts_live_email_rows`
- [ ] `notification_service_fail_closed_before_extras_access`

---

## PR5 — Retention registry expansion
### Goal
Every sensitive artifact class must be registered and auditable.

### Files
- `RetentionModule.kt`
- `RetentionRegistry.kt`
- DAO classes for additional sensitive targets

### Tasks
- [ ] Audit all sensitive tables/artifacts against the privacy taxonomy
- [ ] Add missing targets if they exist:
  - [ ] parsed receipt items
  - [ ] debug exports
  - [ ] bank/import debug artifacts
  - [ ] pipeline diagnostic metadata
  - [ ] operation-run metadata
  - [ ] privacy audit context
  - [ ] cloud call audit artifacts
- [ ] Keep retention target-owned, not worker-special-cased
- [ ] Preserve hashes/links while redacting raw fields

### Acceptance tests
- [ ] `retention_registry_contains_all_sensitive_targets`
- [ ] `retention_debug_exports_purged`
- [ ] `retention_parsed_items_purged_or_redacted`
- [ ] `retention_diagnostic_metadata_purged_or_redacted`

---

## PR6 — Cleanup / docs sync
### Goal
Remove drift so the next agent can reason safely.

### Tasks
- [ ] Remove unused params/branches like `messageId` in fingerprint helpers if truly unused
- [ ] Normalize naming:
  - raw ephemeral input
  - prepared payload
  - persisted hash
- [ ] Update privacy docs to match final code paths
- [ ] Add one “privacy boundaries” reference doc for future agents

---

## Suggested execution order
1. PR1 Email privacy hardening
2. PR2 Cloud receipt provenance / image hardening
3. PR3 Static privacy guard hardening
4. PR4 Real behavioral regression tests
5. PR5 Retention registry expansion
6. PR6 Cleanup / docs sync

## Sources reviewed
- Commit: https://github.com/panospao7/Cost-agregator/commit/fb58e690e2d89d691df2b548ef54f972eddcda61
- `EmailReceiptIngestionService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `ReceiptLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `DefaultCloudPayloadPolicy.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadPolicy.kt
- `CloudReceiptAssistService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt
- `RetentionModule.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt
- `verify_privacy_boundaries.py`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/scripts/verify_privacy_boundaries.py
</remaining_implementation_plan_post_fb58e69>

If you want, I can turn this into a **copy-paste agent prompt** next.