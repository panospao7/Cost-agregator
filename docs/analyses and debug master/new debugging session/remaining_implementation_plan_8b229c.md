# Remaining Implementation Plan — post-`8b229c7710dda0e384a2a7052bc3ed99ced52010`

## Goal
Close the remaining privacy / traceability / retention gaps without reopening fixed ones.

## Priority order
1. Email correlation completion
2. Fail-closed settings/load-state recheck
3. Receipt-assist image/payload ownership
4. Static privacy guard hardening
5. Retention semantics audit
6. Behavioral regression tests
7. Cleanup / dead-code removal

---

## 1) Email traceability and payload-first contract
### Problem
One or more email side-effect paths still drop `correlationId`, and the live ingestion path still needs proof that `EmailReceiptPersistencePayload` is the actual persistence contract.

### Target state
- Every email diagnostic, expense creation, and post-create side effect shares the same `correlationId`.
- Persistence uses a payload-first API, not raw parameter sprawl.
- Restricted modes keep hashes, not plaintext identifiers.

### Files
- `EmailReceiptIngestionService.kt`
- `ReceiptLifecycleCoordinator.kt`
- `EmailReceiptSource.kt`
- `EmailReceiptSourceDao.kt`
- migration files
- `CreateExpenseRequest.kt`

### Steps
1. Patch every email side-effect dispatch to accept/pass `correlationId`.
2. Add or fix coordinator overload:
   - `dispatchPostCreationSideEffects(expenseId, source, correlationId)`
3. Ensure all diagnostics emitted inside email lifecycle code accept `correlationId`.
4. Make ingestion build `EmailReceiptPersistencePayload` before coordinator handoff.
5. Remove raw-persistence arguments from coordinator API where possible.
6. Add explicit hash columns if not already present:
   - `emailMessageIdHash`
   - `contentFingerprintHash`
   - `providerOrderIdHash`
7. Keep raw `emailMessageId` null in restricted modes.

### Tests
- `email_side_effect_uses_email_correlation`
- `email_coordinator_dedupe_diagnostic_uses_email_correlation`
- `email_coordinator_error_diagnostic_uses_email_correlation`
- `email_metadata_only_stores_message_id_hash_column_not_raw_message_id`
- `email_duplicate_detector_receives_hash_not_raw_message_id`

### Done when
No email-side path emits or persists raw identifiers where a hash/payload field is required, and all lifecycle events can be traced by one correlation ID.

---

## 2) Fail-closed settings/load-state recheck
### Problem
The plan requires fail-closed behavior when privacy settings are missing/corrupted, but this still needs verification or completion.

### Target state
- DataStore corruption does not silently enable capture, cloud AI, raw storage, or debug persistence.
- The app distinguishes:
  - first run defaults
  - loaded settings
  - corrupted fail-closed fallback

### Files
- `PrivacySettings.kt`
- `PrivacySettingsRepository.kt`
- `PrivacySettingsRepositoryImpl.kt`
- privacy settings UI
- tests

### Steps
1. Add or verify `PrivacySettingsLoadState`.
2. Make repository emit explicit load state, not only settings.
3. On corruption/read failure, return fail-closed settings:
   - notification capture disabled
   - cloud AI disabled
   - redact before cloud enabled
   - raw storage modes = `DO_NOT_STORE`
   - debug persistence disabled
4. Ensure `observeSettings()` also reflects fail-closed state on corruption.
5. Surface a visible UI warning if settings are in fail-closed fallback.
6. Confirm any runtime apply/update path uses the persisted updated settings, not stale `transform(old)` output.

### Tests
- `datastore_corruption_disables_notification_capture`
- `datastore_corruption_sets_raw_notification_do_not_store`
- `datastore_corruption_sets_raw_ocr_do_not_store`
- `datastore_corruption_sets_email_do_not_store`
- `datastore_corruption_disables_cloud_ai`
- `first_run_defaults_are_distinct_from_corruption_defaults`
- `privacy_update_applies_actual_persisted_updated_settings`

### Done when
Corruption cannot weaken privacy defaults, and the UI/tests prove the distinction between first run and corrupted fallback.

---

## 3) Receipt-assist image / prompt ownership
### Problem
Text payloads are largely policy-driven now, but image inclusion is still partially provider-owned.

### Target state
- The policy layer owns the final prepared receipt-assist payload.
- The provider only sends what `PreparedCloudPayload` allows.
- If image redaction/suppression is required, no raw image is uploaded.

### Files
- `CloudReceiptAssistService.kt`
- `CloudPayloadPolicy.kt`
- `DefaultCloudPayloadPolicy.kt`
- `PreparedCloudPayload` model
- tests

### Steps
1. Move receipt-assist image decision into policy, not provider logic.
2. Add/extend `PreparedCloudPayload` fields for:
   - `rawImageIncluded`
   - `imageBytes`
   - `imageMimeType`
3. Ensure provider never reads image bytes unless the prepared payload explicitly allows it.
4. Make the prepared payload govern the full final request body.
5. Remove provider-local prompt/image assembly that bypasses policy ownership.

### Tests
- `receipt_assist_prepared_payload_includes_full_prompt_redaction`
- `receipt_assist_line_items_redacted_when_policy_requires`
- `receipt_assist_merchant_redacted_when_policy_requires`
- `receipt_assist_image_upload_uses_prepared_rawImageIncluded`

### Done when
The provider cannot leak raw image data or raw prompt fragments outside `PreparedCloudPayload`.

---

## 4) Static privacy guard hardening
### Problem
The guard exists, but it is still heuristic and can be bypassed.

### Target state
- CI catches raw provider request paths reliably.
- Main-source allow-all privacy gates are impossible.
- `hashCode()` pseudonym misuse is blocked in cloud/provider code.

### Files
- `scripts/verify_privacy_boundaries.py`
- `data/ai/provider/*`
- `PrivacyGate.kt`
- `CompositePrivacyGate.kt`

### Steps
1. Strengthen the provider rule:
   - direct `Request.Builder().post(...)` should be forbidden unless routed through an approved transport/payload abstraction.
2. Flag any anonymous `PrivacyGate` in `main` that returns `Allowed` unconditionally.
3. Keep the `hashCode()` ban in provider code.
4. Add explicit allowlist exceptions only for test code.
5. Prefer a dedicated transport API over loose provider-side HTTP construction.

### Tests
- `privacy_guard_flags_cloud_provider_request_without_prepared_payload`
- `privacy_guard_flags_allow_all_privacy_gate_in_main`
- `privacy_guard_flags_hashcode_in_cloud_provider`
- `privacy_guard_allows_cloud_transport_using_prepared_payload`

### Done when
The guard fails for real bypasses, not just suspicious text patterns.

---

## 5) Retention semantics audit
### Problem
Retention improved, but every target must be audited for correct cutoff semantics and accurate counts.

### Target state
- Each retention target has an explicit cutoff contract.
- Sensitive fields are redacted/purged without accidental mass deletion.
- Purge counts are real.

### Files
- `DataRetentionWorker.kt`
- `RetentionTarget.kt`
- `RetentionRegistry.kt`
- DAO methods for email/AI/debug/bank/diagnostics

### Steps
1. Review every registered target and confirm whether it should delete rows or redact fields.
2. Make email source retention redact sensitive columns rather than deleting provenance rows by default.
3. Ensure `now` is not being passed where a true cutoff is required.
4. Make DAO purge methods return actual affected row counts.
5. Expand registry coverage to include any missing sensitive targets:
   - parsed receipt items
   - debug exports
   - bank debug/import artifacts
   - diagnostic metadata where applicable

### Tests
- `retention_email_uses_email_cutoff_not_now`
- `retention_email_redacts_sensitive_columns_not_delete_rows`
- `retention_email_preserves_hashes_and_receipt_links`
- `retention_ai_artifacts_reports_actual_deleted_count`
- `retention_registry_contains_all_sensitive_targets`

### Done when
Retention never deletes the wrong rows and every target reports truthful purge counts.

---

## 6) Behavioral regression tests
### Problem
Some existing tests validate contracts/models but not the real live path.

### Target state
- Tests exercise actual services/coordinators/workers/providers.
- Sentinel raw values cannot leak into request bodies or persistence under restricted modes.

### Add/upgrade tests
- `CloudProviderPreparedPayloadIntegrationTest.kt`
- `EmailReceiptPersistenceIntegrationTest.kt`
- `NotificationCaptureServicePrivacyTest.kt`
- `DataRetentionWorkerIntegrationTest.kt`
- `RawStorageEndToEndTest.kt`
- `PrivacyGuardScriptTest.kt`

### Required scenarios
- Cloud providers send prepared text only.
- Receipt-assist request body does not contain raw sentinel fragments after preparation.
- Email restricted modes store hashes, not plaintext.
- Retention redacts instead of deleting provenance rows.
- Guard script fails on raw provider posting and allow-all main gates.

### Done when
The bugs found in review would fail before the fix and pass after it.

---

## 7) Cleanup queue
### Low-priority but recommended
- Remove dead `shouldRedact` branches in item categorization if no longer needed.
- Ensure `messageIdHash` is not stored in a raw-named field anywhere.
- Normalize naming around “ephemeral raw input” vs “persisted payload”.
- Update architecture docs to match the final contract.

---

## Suggested PR sequence
1. Email correlation + payload-first cleanup
2. Fail-closed settings/load-state
3. Receipt-assist image ownership
4. Static guard hardening
5. Retention audit
6. Behavioral tests
7. Cleanup/docs sync

## Final definition of done
- Email lifecycle is fully correlated.
- Fail-closed privacy settings are proven.
- Receipt-assist image/text payloads are policy-owned.
- Static guard blocks real bypasses.
- Retention is safe, scoped, and counted correctly.
- Behavioral tests cover the real code paths.