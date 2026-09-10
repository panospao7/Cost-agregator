# Privacy / Security Fail-Closed Implementation Plan

Last updated: 2026-06-16  
Scope: MIT-020, MIT-021, MIT-022, MIT-023, MIT-024, MIT-025, MIT-026, MIT-027, MIT-028, MIT-045, MIT-069  
Goal: raw data cannot be read, persisted, logged, replayed, exported, audited, or uploaded incorrectly.

---

## 1. Objective

Build a fail-closed privacy/security layer so that privacy does **not** depend on caller discipline.

After this plan:

- notification text/extras are not read before privacy gate approval,
- queued encrypted notification payloads are not decrypted/replayed after privacy is revoked,
- cloud payloads cannot be prepared or sent when cloud is disabled,
- redaction removes semantic merchant/item/category/amount data, not only regex patterns,
- bank/email/import/receipt data is sanitized before persistence,
- logs, diagnostics, exceptions, and UI errors cannot expose raw data,
- receipt cloud upload cannot exfiltrate arbitrary local files,
- sensitive hashes are install-specific and Keystore-backed,
- privacy audit metadata is typed and value-safe,
- release builds fail if debug/demo/secrets/body logging/raw cloud paths are present.

---

## 2. Covered Master Issues

| MIT | Issue |
|---|---|
| MIT-020 | Prevent notification text/extras read before capture gate allows |
| MIT-021 | Re-check privacy before decrypting/replaying queued notification payloads |
| MIT-022 | Make `CloudPayloadPolicy` fail closed |
| MIT-023 | Add semantic redaction for merchant/item/category/amount data |
| MIT-024 | Replace raw privacy audit maps with typed safe metadata |
| MIT-025 | Move sensitive hashing to install-specific Keystore secret |
| MIT-026 | Sanitize logs, exceptions, paths, tokens, and UI-visible errors |
| MIT-027 | Restrict receipt cloud upload to safe asset IDs/URIs |
| MIT-028 | Enable release security hardening |
| MIT-045 | Sanitize bank/email/import raw persistence before DB writes |
| MIT-069 | Privacy edge-case hardening |

Related but not owned here:

- MIT-003: static guards.
- MIT-034: broad cancellation guard.
- MIT-047/MIT-048: import lifecycle/parser safety.
- MIT-060/MIT-062: UI action paths and privacy-blocked UX.
- MIT-079: DI release binding matrix.

---

## 3. Affected Pipelines

| Pipeline | Impact |
|---|---|
| P1 | Notification capture/replay privacy |
| P3 | Receipt/OCR/PendingReview privacy |
| P8 | Privacy/AI/redaction core |
| P10 | Bank raw merchant/description/token safety |
| P11 | Email receipt body/item privacy |
| P14 | UI error/privacy-blocked safety |
| P16 | Security/network/secrets |
| P18 | Import raw content/error persistence |

---

## 4. Current Problem Summary

Known risk classes:

- Notification text can be extracted before capture gate reaches `Allowed`.
- Notification replay worker can decrypt queued data without rechecking privacy.
- Bank statement/API paths can persist raw merchant/description into review/import rows.
- Email receipt persistence path may bypass privacy payload contract.
- Import errors/rows may include raw file content.
- Cloud payload preparation may not itself enforce cloud-disabled state.
- Regex redaction is insufficient for merchant/item/category/amount semantics.
- Raw audit `Map` metadata can carry unsafe values.
- Audit metadata may be silently dropped instead of safely preserved.
- Sensitive hashing is deterministic and linkable across installs.
- Receipt cloud assist can read direct `imagePath`.
- Logs/UI errors can surface raw exception messages, paths, tokens, merchant names, amounts, URLs, emails, API keys, tax IDs.
- Redacted receipt/email modes may still persist parsed item names.
- AI settings corruption fallback may fail open.
- Release minify/security/network checks are incomplete.

---

## 5. Architecture Decision

### Decision

Create a central **Privacy Boundary Layer** with typed capabilities and typed payloads.

Raw data may pass through the app only in controlled stages:

```text
External Input
  -> Capability Gate
  -> Raw Access Guard
  -> Purpose-Specific Parser
  -> Persistence Payload Policy
  -> Semantic Redaction / Sanitization
  -> Safe DB Entity / Safe Diagnostic / PreparedCloudPayload
```

### Core rule

> Raw data cannot be read, decrypted, persisted, logged, audited, or uploaded unless the current capability and purpose explicitly allow it.

### Rejected approach

Do not rely on individual call sites remembering to sanitize. That is how the current gaps recur.

---

# 6. Non-Negotiable Invariants

After this plan:

- [ ] No notification text/extras/body is read before gate allows.
- [ ] Queued payload replay rechecks privacy before decrypt.
- [ ] Cloud payload policy enforces disabled state internally.
- [ ] Cloud providers accept only `PreparedCloudPayload`, not raw strings/files.
- [ ] Receipt cloud upload accepts only verified asset IDs/allowlisted URIs.
- [ ] Bank/email/import/receipt review rows contain privacy-safe text only.
- [ ] Redacted modes do not persist raw item names.
- [ ] Audit metadata is typed and value-safe.
- [ ] Sensitive hashes use install-specific Keystore secret with versioning.
- [ ] Logs, diagnostics, exceptions, snackbar/UI messages are sanitized.
- [ ] AI settings corruption fails closed.
- [ ] Release build fails on secrets, BODY logging, unsafe raw cloud paths, debug/demo bindings, cleartext endpoints.
- [ ] Static guards catch known privacy-boundary violations.

---

# 7. Target Components

## 7.1 `PrivacyCapabilityManager`

Central capability evaluator.

Capabilities:

- notification capture,
- notification replay,
- local receipt OCR storage,
- email receipt ingestion,
- bank import/review,
- import file parsing,
- cloud AI,
- receipt cloud assist,
- location,
- analytics,
- diagnostics/audit.

Returns typed result:

```text
Allowed
TemporarilyUnavailable
DeniedByUser
DeniedByPolicy
DeniedByCorruptSettings
DeniedByMaintenance
DeniedByReleaseBuild
```

---

## 7.2 `RawDataAccessGuard`

Controls when code may access raw source data.

Examples:

- notification extras/text,
- email body,
- bank statement description,
- receipt OCR text,
- import CSV/JSON row,
- file path/URI,
- exception message.

Rule:

> If capability is not `Allowed`, raw access method must return blocked without touching raw value.

---

## 7.3 `PersistencePayloadPolicy`

Purpose-specific payload builders.

Payload types:

- `NotificationPersistencePayload`
- `BankReviewPersistencePayload`
- `EmailReceiptPersistencePayload`
- `ImportRowPersistencePayload`
- `ReceiptOcrPersistencePayload`
- `PendingReviewPersistencePayload`

Each builder returns only safe fields for DB.

---

## 7.4 `SemanticRedactor`

Redacts meaning, not just patterns.

Must handle:

- merchant names,
- receipt item names,
- category names,
- business/project names,
- amounts,
- dates when sensitive,
- emails,
- URLs,
- phone numbers,
- addresses,
- tax IDs,
- Greek AFM,
- IBAN/card-like numbers,
- API keys/tokens,
- file paths,
- raw CSV/JSON snippets.

Modes:

```text
DROP
GENERALIZE
HASH_LOCAL
ENCRYPT_LOCAL_ONLY
ALLOW_LOCAL_ONLY
ALLOW_CLOUD_SAFE
```

---

## 7.5 `CloudPayloadPolicy`

Only producer of `PreparedCloudPayload`.

Responsibilities:

- check cloud capability itself,
- apply semantic redaction,
- enforce size/type limits,
- block raw paths,
- include safe metadata only,
- fail closed on uncertainty.

Cloud providers must accept:

```kotlin
PreparedCloudPayload
```

not raw `String`, `File`, `Path`, `Uri`, `RequestBody`, or raw OCR/body text.

---

## 7.6 `ReceiptAssetResolver`

Safe receipt cloud upload resolver.

Responsibilities:

- accept receipt asset ID, not raw `imagePath`,
- verify asset belongs to app-managed receipt store,
- allowlist URI authority/path,
- MIME sniff,
- size limit,
- reject symlinks/path traversal,
- return safe stream descriptor.

---

## 7.7 `PrivacyAuditMetadata`

Typed audit metadata.

No raw `Map<String, Any>`.

Examples:

```text
PrivacyAuditMetadata.NotificationBlocked(packageHash, reason)
PrivacyAuditMetadata.CloudPayloadPrepared(provider, purpose, redactionMode)
PrivacyAuditMetadata.ImportRowSanitized(fileHash, rowNumber, droppedFieldCount)
```

All fields must be value-safe by construction.

---

## 7.8 `SafeDiagnostics`

Single logger/error sanitizer.

Outputs:

- sanitized log message,
- UI-safe message,
- diagnostic reason code,
- optional hashed safe identifier.

Never output raw `Throwable.message` directly.

---

## 7.9 `SensitiveHasher`

Install-specific hashing.

Requirements:

- Keystore-backed or install-secret-backed HMAC,
- versioned hash format,
- purpose separation,
- migration compatibility,
- no cross-install linkability unless explicitly intended.

---

# 8. Implementation Phases

---

## Phase 0 — Privacy Data-Flow Inventory

### Goal

Know every raw-data source, sink, and transformation.

### Tasks

- [ ] Inventory raw sources:
  - notification extras/text,
  - email body,
  - bank API/statement merchant/description,
  - receipt OCR/raw image,
  - import CSV/JSON rows,
  - location,
  - cloud assistant prompts,
  - exceptions/log messages.
- [ ] Inventory sinks:
  - DB tables,
  - PendingReview,
  - operation ledgers,
  - logs,
  - UI/snackbars,
  - exports/backups,
  - cloud requests,
  - audit events.
- [ ] Inventory current sanitizer/redactor APIs.
- [ ] Inventory cloud provider request paths.
- [ ] Inventory direct `Log`/`Timber`/`e.message` usage.
- [ ] Inventory hashing helpers.
- [ ] Create `docs/privacy/PRIVACY_DATA_FLOW_INVENTORY.md`.

### Acceptance Criteria

- [ ] Every raw source and sink is listed.
- [ ] Every cloud path is listed.
- [ ] Every unsafe sink has linked MIT issue.

---

## Phase 1 — Define Privacy Capability Model

### Tasks

- [ ] Create `PrivacyCapabilityManager`.
- [ ] Define capability enum/sealed class.
- [ ] Define blocked reasons.
- [ ] Make AI/cloud settings corruption return `DeniedByCorruptSettings`.
- [ ] Ensure capability state is fail-closed if settings cannot load.
- [ ] Add tests for allowed/denied/corrupt/unavailable states.

### Acceptance Criteria

- [ ] Corrupt settings never enable cloud/privacy-sensitive features.
- [ ] Callers receive typed blocked state, not nullable boolean.

---

## Phase 2 — Notification Capture and Replay Fail-Closed

### Tasks

- [ ] Move privacy gate before any notification extras/text/body access.
- [ ] On `TemporarilyUnavailable`, do not read extras/text.
- [ ] Worker replay rechecks package/privacy/blocked-state before decrypt.
- [ ] If denied, do not decrypt payload.
- [ ] Emit sanitized durable diagnostic.
- [ ] Preserve legally captured fields after gate: `combinedBody`, `textLines`, messages.
- [ ] Use shared hashing helper for notification fingerprints.
- [ ] Add tests:
  - denied before extraction,
  - temporarily unavailable,
  - privacy revoked after queueing,
  - blocked package after queueing,
  - same key/different content.

### Acceptance Criteria

- [ ] Disallowed notifications are never inspected or decrypted.
- [ ] Replay cannot bypass privacy revocation.

---

## Phase 3 — Persistence Payload Policies

### Tasks

- [ ] Implement `BankReviewPersistencePayload`.
- [ ] Implement `EmailReceiptPersistencePayload`.
- [ ] Implement `ImportRowPersistencePayload`.
- [ ] Implement `ReceiptOcrPersistencePayload`.
- [ ] Ensure PendingReview rows use safe payloads.
- [ ] Apply bank payload before any DB write.
- [ ] Apply email payload in real coordinator write path.
- [ ] Apply import sanitizer before row/error persistence.
- [ ] Preserve null-vs-empty-vs-dropped OCR semantics.
- [ ] Redacted modes must remove raw parsed item names.
- [ ] Add tests for low-confidence bank/email/import rows.

### Acceptance Criteria

- [ ] DB review/import rows contain privacy-safe text only.
- [ ] Raw merchant/description/body/file-content cannot enter PendingReview accidentally.

---

## Phase 4 — Semantic Redaction

### Tasks

- [ ] Build purpose-specific semantic redactors:
  - cloud assistant,
  - receipt AI,
  - bank review,
  - email receipt,
  - import diagnostics,
  - audit/logging.
- [ ] Add entity-aware replacements:
  - merchant → `[merchant]`,
  - item → `[item]`,
  - category → `[category]`,
  - amount → `[amount]`,
  - address → `[address]`.
- [ ] Add golden tests for:
  - merchants,
  - item names,
  - categories,
  - amounts,
  - emails,
  - URLs,
  - Greek AFM,
  - API keys,
  - addresses,
  - IBAN/card-like numbers,
  - file paths.
- [ ] Fail closed if redaction confidence is insufficient.

### Acceptance Criteria

- [ ] Redaction removes sensitive semantics, not only obvious patterns.
- [ ] Cloud payload golden tests pass.

---

## Phase 5 — Cloud Payload Fail-Closed

### Tasks

- [ ] Make `CloudPayloadPolicy` require capability check internally.
- [ ] Return hard blocked result when cloud disabled/corrupt/denied.
- [ ] Ensure providers accept only `PreparedCloudPayload`.
- [ ] Ban direct raw `RequestBody` construction in cloud paths.
- [ ] Add provider tests for every cloud path.
- [ ] Add static guard:
  - raw `RequestBody` in cloud package fails,
  - raw prompt/body/path sent to provider fails,
  - provider method accepting raw string/file fails unless local-only allowlisted.

### Acceptance Criteria

- [ ] Caller mistakes cannot prepare/send cloud payload when cloud is disabled.
- [ ] Release CI fails on cloud payload bypass.

---

## Phase 6 — Receipt Asset Upload Safety

### Tasks

- [ ] Replace direct `imagePath` upload with receipt asset ID or allowlisted URI.
- [ ] Verify asset exists in app-managed store.
- [ ] Reject arbitrary filesystem paths.
- [ ] Reject path traversal/symlink escape.
- [ ] MIME sniff and enforce allowed image/PDF types.
- [ ] Enforce size limit.
- [ ] Add tests for:
  - valid asset,
  - arbitrary app-readable file,
  - path traversal,
  - wrong MIME,
  - oversized file.

### Acceptance Criteria

- [ ] Compromised caller cannot upload arbitrary local file.

---

## Phase 7 — Typed Privacy Audit Metadata

### Tasks

- [ ] Replace raw audit `Map` contexts with sealed metadata types.
- [ ] Preserve safe typed metadata during serialization.
- [ ] Value-scan all allowlisted string fields.
- [ ] Drop/generalize unsafe values with diagnostic count.
- [ ] Add compile/static guard against raw audit maps.
- [ ] Add tests for metadata preservation and unsafe value rejection.

### Acceptance Criteria

- [ ] Raw arbitrary key/value audit contexts cannot be submitted.
- [ ] Safe metadata is not silently dropped.

---

## Phase 8 — Sensitive Hashing Migration

### Tasks

- [ ] Create Keystore/install-secret-backed HMAC helper.
- [ ] Version hash format:
  - `v1:legacy-purpose-derived`,
  - `v2:install-secret-hmac`.
- [ ] Add purpose separation.
- [ ] Define migration behavior:
  - new writes use v2,
  - old v1 can still be matched if required,
  - background backfill if safe.
- [ ] Document backup/restore implications.
- [ ] Add tests:
  - same install stable,
  - different install different,
  - purpose separation,
  - legacy compatibility.

### Acceptance Criteria

- [ ] Sensitive hashes are not linkable across installs unless explicitly intended.

---

## Phase 9 — Logging, Exceptions, UI Error Sanitization

### Tasks

- [ ] Implement central `SafeDiagnostics`.
- [ ] Replace direct Android `Log` in sensitive providers.
- [ ] Replace unsafe `Timber` calls.
- [ ] Ban raw `e.message` in UI/snackbars.
- [ ] Redact:
  - URLs,
  - emails,
  - API keys,
  - tax IDs,
  - file paths,
  - merchant names,
  - amounts,
  - bank tokens,
  - CSV/JSON row content.
- [ ] Override/redact `BankConnection.toString()`.
- [ ] Redact backup asset filenames/paths.
- [ ] Add static guard and tests.

### Acceptance Criteria

- [ ] No sensitive value appears in release logs, diagnostics, or UI errors.

---

## Phase 10 — Release Security Hardening

### Tasks

- [ ] Enable R8/minify for release or document explicit tested waiver.
- [ ] Add release CI checks:
  - no API keys/secrets,
  - no BODY HTTP logging,
  - no cleartext endpoints unless allowlisted,
  - no debug/demo/stub/no-op bindings,
  - no raw cloud `RequestBody`,
  - no direct cloud payload bypass.
- [ ] Verify backup KDF params encoded in encrypted header/manifest.
- [ ] Verify restored bank-token blobs on another device trigger reauth.
- [ ] Add OkHttp/RequestBody inventory.
- [ ] Add release APK/AAB scan.

### Acceptance Criteria

- [ ] Release build fails on known security/privacy regressions.

---

# 9. Static Guards Required

Implement or extend guards for:

- [ ] notification raw access before gate,
- [ ] cloud payload bypass,
- [ ] raw `RequestBody` in cloud providers,
- [ ] raw audit map submission,
- [ ] direct `Log`/unsafe `Timber`,
- [ ] raw `Throwable.message` to UI,
- [ ] raw `imagePath` cloud upload,
- [ ] bank/email/import raw persistence bypass,
- [ ] hardcoded unsafe sanitizer bypass,
- [ ] sensitive hash helper bypass,
- [ ] release debug/demo/stub binding,
- [ ] BODY logging/cleartext/secrets.

All guards need:

- positive fixture,
- negative fixture,
- allowlisted fixture,
- expired allowlist fixture.

---

# 10. Testing Strategy

## Unit tests

- [ ] capability allowed/denied/corrupt,
- [ ] notification extraction blocked before raw read,
- [ ] replay privacy revoked before decrypt,
- [ ] persistence payload redaction,
- [ ] semantic redaction golden tests,
- [ ] audit metadata serialization,
- [ ] sensitive hashing install separation,
- [ ] exception/log sanitizer,
- [ ] receipt asset resolver.

## Integration tests

- [ ] bank low-confidence row contains safe text,
- [ ] email low-confidence row contains safe text,
- [ ] import error does not store raw row,
- [ ] cloud disabled blocks payload preparation,
- [ ] receipt cloud upload rejects arbitrary path,
- [ ] UI snackbar does not expose raw exception.

## Release tests

- [ ] release secret scan,
- [ ] no BODY logging,
- [ ] no cleartext endpoint,
- [ ] no debug/demo binding,
- [ ] no raw cloud request path.

---

# 11. Rollout PR Plan

## PR 1 — Privacy Inventory and Capability Model

- data-flow inventory,
- capability manager,
- corrupt settings fail-closed.

## PR 2 — Notification Gate and Replay Recheck

- no raw read before gate,
- replay privacy recheck,
- diagnostics.

## PR 3 — Persistence Payload Policies

- bank/email/import/receipt safe payloads,
- PendingReview safety,
- null/empty/dropped semantics.

## PR 4 — Semantic Redaction

- purpose redactors,
- golden tests,
- fail-closed uncertainty.

## PR 5 — Cloud Fail-Closed

- `PreparedCloudPayload`,
- cloud provider API restriction,
- raw `RequestBody` guard.

## PR 6 — Receipt Asset Upload Safety

- asset ID resolver,
- MIME/path/size checks,
- negative tests.

## PR 7 — Audit Metadata and Sensitive Hashing

- typed metadata,
- Keystore/install HMAC,
- legacy compatibility.

## PR 8 — Logging/UI Error Sanitization

- central diagnostics,
- `BankConnection.toString()` redaction,
- no raw `e.message`.

## PR 9 — Release Security CI

- R8/minify decision,
- secret/log/network/cloud scans,
- release binding checks.

## PR 10 — Final Privacy Regression Suite

- cross-pipeline tests,
- guards blocking,
- tracker updates.

---

# 12. Edge Cases

## Privacy revoked after queueing notification

Expected:

- worker checks before decrypt,
- payload remains encrypted or is discarded per policy,
- diagnostic reason code only.

## AI settings corrupted

Expected:

- cloud disabled,
- UI gets typed blocked state,
- no fallback to enabled defaults.

## Redaction uncertain

Expected:

- fail closed,
- do not send to cloud,
- optionally store generalized local diagnostic.

## Import row invalid

Expected:

- no raw row in error,
- file hash/row number only,
- sanitized reason.

## Bank token restored on different device

Expected:

- token decrypt/auth fails safely,
- force reauth,
- no token blob logging.

---

# 13. Metrics

| Metric | Target |
|---|---|
| Raw cloud payload bypasses | 0 |
| Raw audit map call sites | 0 |
| Direct sensitive `Log` calls | 0 |
| Raw `e.message` UI paths | 0 |
| Direct receipt image path cloud uploads | 0 |
| Bank/email/import raw persistence violations | 0 |
| Redaction golden failures | 0 |
| Release BODY logging | 0 |
| Release embedded secrets | 0 |
| Expired privacy allowlists | 0 |

---

# 14. Definition of Done by MIT

## MIT-020

- [ ] Notification text/extras are never read before allowed gate.
- [ ] Tests prove denied/unavailable states do not inspect payload.

## MIT-021

- [ ] Replay rechecks privacy before decrypt.
- [ ] Revoked privacy blocks queued payload processing.

## MIT-022

- [ ] Cloud payload policy fails closed internally.
- [ ] Providers accept only prepared payloads.

## MIT-023

- [ ] Semantic redaction golden tests cover merchant/item/category/amount and identifiers.

## MIT-024

- [ ] Raw audit maps removed.
- [ ] Typed metadata is safe and preserved.

## MIT-025

- [ ] Sensitive hashes use install-specific Keystore/install secret with versioning.

## MIT-026

- [ ] Logs/exceptions/UI errors are sanitized.
- [ ] Release scans pass.

## MIT-027

- [ ] Receipt cloud upload uses safe asset IDs/URIs only.

## MIT-028

- [ ] Release security checks are blocking.

## MIT-045

- [ ] Bank/email/import raw persistence is sanitized before DB write.

## MIT-069

- [ ] AI settings corruption fails closed.
- [ ] OCR null/empty/dropped semantics are explicit.
- [ ] Redacted modes do not persist raw item names.

---

# 15. Final Completion Checklist

This plan is complete when:

- [ ] Privacy data-flow inventory exists.
- [ ] Capability model is fail-closed.
- [ ] Notification gate/replay are safe.
- [ ] Persistence payload policies are used by real write paths.
- [ ] Semantic redaction golden tests pass.
- [ ] Cloud payload policy is the only cloud payload producer.
- [ ] Receipt asset upload is path-safe.
- [ ] Privacy audit metadata is typed.
- [ ] Sensitive hashing is install-specific.
- [ ] Logs/UI errors/diagnostics are sanitized.
- [ ] Release security CI is blocking.
- [ ] Static guards prevent regressions.
- [ ] Master tracker is updated with closing SHAs.

---

# 16. Recommended First Action

Start with:

```text
PR 1 — Privacy Data-Flow Inventory and Capability Model
```

Then immediately:

```text
PR 2 — Notification Gate and Replay Recheck
PR 3 — Persistence Payload Policies
PR 4 — Cloud Fail-Closed
```

Do not begin cloud/AI feature work until fail-closed payload policy and semantic redaction are enforced.