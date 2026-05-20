# PR Checklist — remaining work after `fb58e690e2d89d691df2b548ef54f972eddcda61`

## Notes
- PR6–PR8 are **partially addressed** by this commit.
- The checklist below focuses on what is still missing or still too weak.

---

## PR1 — Email pipeline correctness + schema cleanup
### Goal
Make email ingestion single-owner, hash-safe, retention-safe, and fully correlated.

### Tasks
- [ ] Remove plaintext fallback from `messageIdHash`.
  - If HMAC fails, fail closed; do not use raw `messageId`.
- [ ] Add explicit hash columns:
  - [ ] `emailMessageIdHash`
  - [ ] `contentFingerprintHash`
  - [ ] `providerOrderIdHash`
- [ ] Stop storing hashes in raw-named fields.
- [ ] Ensure `ReceiptLifecycleCoordinator` is the only owner of post-create email side effects.
- [ ] Keep `correlationId` on every email diagnostic and side effect.
- [ ] Make retention-safe email fields nullable or use consistent `[REDACTED]` placeholders.
- [ ] Remove raw sender/subject/body fragments from logs and exceptions.

### Acceptance tests
- [ ] `email_ingestion_does_not_dispatch_transaction_side_effects_twice`
- [ ] `email_side_effect_dispatch_has_email_correlation`
- [ ] `email_metadata_only_stores_message_id_hash_column_not_raw_message_id`
- [ ] `retention_email_redacts_without_sql_constraint_failure`
- [ ] `email_insert_failure_does_not_include_raw_sender`

---

## PR2 — PrivacySettings fail-closed update safety
### Goal
Corruption must not resurrect unsafe defaults during updates.

### Tasks
- [ ] Make `updateSettings()` transform from load-state settings, not normal defaults.
- [ ] On corruption, base updates on `FAIL_CLOSED_DEFAULTS`.
- [ ] Prevent silent recovery into `STORE_RAW`/capture-enabled values.
- [ ] Add UI warning for corrupted/fail-closed state.
- [ ] Verify persisted update = runtime-applied update.

### Acceptance tests
- [ ] `privacy_update_from_corrupted_state_uses_fail_closed_base`
- [ ] `privacy_update_after_corruption_does_not_reenable_notification_capture`
- [ ] `privacy_update_after_corruption_does_not_restore_store_raw_modes`
- [ ] `first_run_defaults_are_distinct_from_corruption_defaults`

---

## PR3 — Cloud categorization cleanup + receipt-image hardening
### Goal
Remove empty-prompt probing and make receipt-image policy-owned.

### Tasks
- [ ] Remove `prepareText(..., "")` empty-prompt probes.
- [ ] Build one real prompt, then prepare it once.
- [ ] Make `PreparedCloudPayload` own the final text/image decision.
- [ ] Validate image MIME type.
- [ ] Validate app-owned image path/source.
- [ ] Add explicit suppression reason to audit metadata.
- [ ] Suppress image upload if redaction is required or validation fails.

### Acceptance tests
- [ ] `cloud_categorization_assist_does_not_prepare_empty_prompt`
- [ ] `receipt_assist_rejects_unsupported_image_mime`
- [ ] `receipt_assist_rejects_non_app_owned_image_path`
- [ ] `receipt_assist_audit_records_image_suppression_reason`
- [ ] `receipt_assist_payload_hash_changes_when_image_changes`

---

## PR4 — Notification blocked-package startup fail-closed
### Goal
No notification text is read before blocked-package cache readiness.

### Tasks
- [ ] Add `blockedPackageCacheLoaded`.
- [ ] Fail closed until the first blocked-package emission arrives.
- [ ] Keep normal and refresh paths aligned before extras extraction.
- [ ] Do not read extras/text before cache readiness.
- [ ] Keep observer failure in fail-closed state.

### Acceptance tests
- [ ] `notification_before_blocked_cache_load_does_not_read_extras`
- [ ] `refresh_before_blocked_cache_load_does_not_read_extras`
- [ ] `blocked_cache_observer_failure_keeps_fail_closed_state`

---

## PR5 — Retention registry and accurate counts
### Goal
Retention must be complete, target-owned, and count-accurate.

### Tasks
- [ ] Move cutoff logic into each `RetentionTarget`.
- [ ] Return actual affected row counts from DAOs.
- [ ] Stop using worker-level string special cases.
- [ ] Add missing targets:
  - [ ] parsed receipt items
  - [ ] debug exports
  - [ ] bank debug/import artifacts
  - [ ] pipeline diagnostic metadata
  - [ ] operation-run metadata
  - [ ] privacy audit context
  - [ ] cloud call audit artifacts, if present
- [ ] Preserve hashes/links while purging raw fields.
- [ ] Keep `ai_chat_messages` on the 30-day cutoff.

### Acceptance tests
- [ ] `retention_registry_contains_all_sensitive_targets`
- [ ] `retention_ai_artifacts_reports_actual_deleted_count`
- [ ] `retention_ai_chat_messages_reports_actual_deleted_count`
- [ ] `retention_parsed_items_purged_or_redacted`
- [ ] `retention_debug_exports_purged`

---

## PR6 — Static privacy guard hardening
### Goal
Make CI catch real bypasses, not just suspicious strings.

### Tasks
- [ ] Strengthen G3 so provider `Request.Builder().post(...)` must derive from approved prepared transport.
- [ ] Strengthen G4 to catch allow-all `PrivacyGate` in `main`.
- [ ] Keep G12, but make it resilient to multiline/helper indirection.
- [ ] Keep G13, but require `correlationId` even with named args/wrappers.
- [ ] Add rules for:
  - [ ] hash fallback in email identifiers
  - [ ] empty-prompt policy probes in helpers
  - [ ] direct provider HTTP construction outside transport abstraction

### Acceptance tests
- [ ] `privacy_guard_flags_cloud_provider_request_without_prepared_payload`
- [ ] `privacy_guard_flags_allow_all_privacy_gate_in_main`
- [ ] `privacy_guard_flags_empty_prompt_policy_probe`
- [ ] `privacy_guard_flags_missing_email_correlation`
- [ ] `privacy_guard_flags_hashcode_in_cloud_provider`

---

## PR7 — Behavioral regression tests overhaul
### Goal
Replace contract-only tests with real service/DAO/worker behavior tests.

### Tasks
- [ ] Replace “documentation-style” assertions with live-path tests.
- [ ] Test real `EmailReceiptIngestionService` flow.
- [ ] Test real `NotificationCaptureService` flow.
- [ ] Test real `DataRetentionWorker`/DAO behavior.
- [ ] Test provider request construction, not just policy helpers.
- [ ] Use sentinel raw values and assert they never reach persistence or requests.

### Must cover
- [ ] email side effects dispatch once
- [ ] correlation survives end-to-end
- [ ] corrupted privacy state does not re-enable unsafe defaults
- [ ] notification startup is fail-closed
- [ ] retention redacts/preserves as expected
- [ ] receipt image suppression is enforced

---

## PR8 — Docs / cleanup / drift removal
### Goal
Align docs and code; remove leftover dead patterns.

### Tasks
- [ ] Remove unused `shouldRedact`/probe branches if still present.
- [ ] Normalize naming:
  - `raw ephemeral input`
  - `prepared payload`
  - `persisted hash`
- [ ] Update architecture docs to match final behavior.
- [ ] Add one clear “privacy boundaries” doc for agents.
- [ ] Remove any outdated test comments that imply mock-only coverage.

---

## Suggested execution order
1. PR1
2. PR2
3. PR3
4. PR4
5. PR5
6. PR6
7. PR7
8. PR8

## Done when
- Email is single-dispatch, hashed, and correlated.
- Corruption cannot weaken privacy settings.
- Cloud receipt assist owns image policy safely.
- Notification startup cannot leak extras early.
- Retention is complete and truthful.
- Static guard catches real bypasses.
- Behavioral tests hit real code paths.