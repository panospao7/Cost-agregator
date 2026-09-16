# RP-15 — Privacy hardening and denied UX

> **Mode:** strict (privacy). **Status:** CONDITIONAL — execute only in the batches below.

## Scope and invariants

RP-15 owns raw-persistence resolution, cloud-capability policy, merchant redaction, and denied UI. RP-14 owns retention targets; RP-16 owns retention terminal/checkpoint visibility. Fail closed on corrupt/unavailable settings. Never render, persist, or diagnose exception messages or raw privacy-decision reasons.

## 15-A — Full-policy resolver consolidation

`RawPersistencePolicyResolver.forSource(...)` and `forSourceSync(...)` are the only callable APIs; `modeFor(...)` is private. Replace inline selectors in NotificationProcessingPipeline, BankStatementLifecycleProcessor, and ReceiptLifecycleCoordinator's email path with the complete `RawPersistencePolicy`, consuming every relevant field—not only `mode`.

Normalize unavailable/corrupt/invalid settings to `PrivacySettings.FAIL_CLOSED_DEFAULTS` before policy construction. Preserve email dedupe/hash behavior and source-specific parsed-field/debug permissions.

Required before code: a truth table for all seven `RawSourceType` values × every storage mode, including complete policy fields, corrupt settings, old caller result, and proposed result. Tests cover that matrix, fail-closed corruption, each write-site equivalence, and absence of owned inline selection.

## 15-B — Capability policy and safe reason codes

Make `requireAllowed(...)` exhaustive only for explicitly registered cloud capabilities: receipt assist, receipt OCR/image upload, item categorization, warranty extraction, daily briefing, bank statement, and general AI. The matrix must state global, image, bank, and dedicated-setting inputs. Where no dedicated setting exists, global-only behavior is an explicit product decision. Non-cloud, unknown, and future capabilities fail closed.

Replace gate exception-derived reasons with bounded controlled codes such as `PRIVACY_GATE_FAILURE`; diagnostics may use exception class only. Centralize blocked mapping; new `PrivacyBlocked.Custom` instances must not carry untrusted text.

Tests: each current capability; global/image/bank denial; non-cloud/future denial; exact-one enum coverage; controlled reasons only.

## 15-C — Merchant identity and phone redaction decision

RP-00 must choose: (1) remediation with one injected Keystore-backed installation-secret HMAC service, or (2) accepted debt. A public salt and/or version prefix is not remediation.

If remediating, both `DefaultCloudPayloadRedactor.redactText()` and `redactMerchant()`/`CloudPiiSanitizer` use the same service. Define generation, restart stability, Keystore invalidation fail-closed behavior, backup/restore and cross-install unlinkability, rotation, versioning, and legacy-artifact treatment. No public SHA fallback.

The seven-digit phone floor is permitted only with corpus tests proving currency/amount strings do not falsely redact.

Tests: both paths parity, restart, distinct installs, invalidation, rotation/legacy behavior, seven-digit locals, and amount/currency corpus.

## 15-D — Typed denied UI

Keep domain `PrivacyBlocked` compatible: it is a sealed hierarchy, not a generic `(capability, resourceId)` constructor. Add a UI adapter such as `PrivacyBlockedUiState(capability, @StringRes messageResId, reasonCode)`. `PrivacyBlockedCard` renders resource-backed text, never arbitrary reason strings.

BackupRestoreViewModel, ExportOptionsViewModel, SpendingMapViewModel, and ReviewViewModel converge denial, fail-closed, and `SecurityException` paths on this state. SpendingMap permission races set it, not only a snackbar. Remove message matching such as `contains("denied")`.

Tests: all three denial classes per ViewModel; card rendering; permitted paths unchanged; static check against raw reason/exception rendering.

## Gates and completion

Order: 15-A → 15-B → RP-00 hash decision → 15-C → 15-D. Run targeted privacy/redaction/UI tests when approved. Strict privacy review follows 15-A, 15-C, and 15-D. Stop for architecture review on new storage, setting, backup, or schema work.
