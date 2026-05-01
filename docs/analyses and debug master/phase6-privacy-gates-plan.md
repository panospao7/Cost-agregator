# Phase 6 — Privacy Capability Gates: Final Implementation Plan

**Date**: 2026-05-01
**Decision**: **Option A** — Endorse the template plan with structural refinements.
**Template**: `phase6-privacy-capability-gates-implementation-plan.md` (1,336 lines)
**Audit**: `docs/analyses and debug master/privacy-gates-audit.md` (803 lines)
**DB at start**: v102 → **target**: v104

---

## 0. Evaluation Summary

The template plan is fundamentally sound. All 8 high-severity audit gaps are covered. Refinements needed:

| Issue in Template | Refinement |
|---|---|
| 13 PRs → merge conflict risk | Consolidated to **8 batches** |
| No database migration plan | Explicit v102→v103→v104 migration spec |
| Unresolved product decisions | All resolved (see §2) |
| No encryption key management | AES-256-GCM + PBKDF2 design |
| Retention worker separated from retention PRs | Integrated into respective batches |
| "External HTTP guardrails PR" is documentation-only | Folded into closeout batch; added Gradle guardrail task |
| Geoapify API key in query param flagged but not fixed | Header-auth migration in Batch 1 |
| No rollback strategy | Per-batch rollback instructions |
| No existing-user migration plan | Privacy review prompt (one-time modal) |

---

## 1. Principles (Preserved from Template)

1. **Default-deny** for sensitive capture/external-call features (OFF by default)
2. **Centralized gates** — not scattered conditionals per service
3. **Denied = zero side effects** — no HTTP, GPS, DB insert, file write
4. **Audit without PII** — capability, allow/deny, reason, timestamp, destination class only
5. **Retention must be explicit** — TTLs and purge/scrub mechanisms for all raw data

---

## 2. Product Decisions Resolved

| Decision | Resolution |
|---|---|
| New install default posture | Strict privacy: capture OFF, geocoding OFF, backup encrypted, raw data excluded |
| Existing user transition | One-time Privacy Review prompt on first post-migration launch |
| Notification: allowlist vs blocklist | Both: default blocklist (system/social), user-configurable allowlist |
| Per-capability cloud AI toggles | Deferred. Use existing `AiSettings` per-capability toggles with central gate |
| Backup encryption mode | Password-protected AES-256-GCM |
| OCR: nullable column vs sentinel | Sentinel `[PURGED_BY_RETENTION]` — no schema migration needed |
| Legacy unencrypted backup | Import-only behind scary warning. No new unencrypted exports. |
| Geoapify API key in query param | Fix: move to header or redact from URL logs |

---

## 3. Database Migrations

### v102 → v103 (Batch 1)
New table: `privacy_audit_events`
```sql
CREATE TABLE privacy_audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    capability TEXT NOT NULL,
    allowed INTEGER NOT NULL,
    denialReason TEXT,
    destinationCategory TEXT,
    timestampMs INTEGER NOT NULL,
    redactionApplied INTEGER NOT NULL DEFAULT 0,
    networkType TEXT,
    sourceFeature TEXT
);
CREATE INDEX idx_privacy_audit_ts ON privacy_audit_events(timestampMs);
```

### v103 → v104 (Batch 3)
Add retention metadata columns:
```sql
ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER DEFAULT NULL;
ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER DEFAULT NULL;
```

---

## 4. New Package Structure

### `domain/privacy/` (12 files)
`PrivacyCapability.kt` — enum of all ~30 gated capabilities
`PrivacyDecision.kt` — `sealed interface { Allowed, Denied(reason) }`
`PrivacyDecisionReason.kt` — enum of denial reasons
`PrivacyGate.kt` — `interface { suspend fun check(capability, context): PrivacyDecision }`
`PrivacySettings.kt` — data class with all defaults (all OFF except encryption)
`PrivacySettingsRepository.kt` — domain interface for DataStore
`PrivacyAuditLogger.kt` — domain interface
`RetentionPolicyRepository.kt` — domain interface
`NotificationPrivacyGate.kt` — capture + per-package gate
`CloudAiPrivacyGate.kt` — centralized cloud AI gate
`LocationPrivacyGate.kt` — geocoding/GPS/backfill/Overpass gate
`BackupPrivacyPolicy.kt` — domain model for backup options
`ExportPrivacyOptions.kt` — domain model for export anonymization

### `data/privacy/` (8 files)
`PrivacySettingsRepositoryImpl.kt` — DataStore (`privacy_settings` file)
`PrivacyAuditEvent.kt` — Room entity
`PrivacyAuditDao.kt` — Room DAO
`PrivacyAuditLoggerImpl.kt` — Room-backed audit logger
`RetentionPolicyRepositoryImpl.kt`
`DataRetentionWorker.kt` — daily CoroutineWorker
`BackupEncryptionService.kt` — AES-256-GCM encrypt/decrypt
`SanitizedBackupExporter.kt` — temp sanitized DB copy for export
`ExportAnonymizer.kt`

### `di/` (1 file)
`PrivacyModule.kt` — Hilt module for all new bindings

### `ui/screens/privacysettings/` (3 files)
`PrivacySettingsScreen.kt`, `PrivacySettingsViewModel.kt`, `PrivacyReviewPrompt.kt`

---

## 5. Implementation Batches

### Batch 0 — Baseline & Verification (Zero code changes)
- Run full compile + test suite
- Document current test state
- Create `docs/development/PRIVACY_CAPABILITY_GATES.md`
- Map all 15 external endpoints + raw data stores
- **Done when**: baseline known; no behavior changes

### Batch 1 — Privacy Settings Foundation + Geoapify Fix
**DB**: v102 → v103 (privacy_audit_events table)

**Actions**:
1. Create all `domain/privacy/` interfaces and models
2. Create `PrivacySettingsRepositoryImpl` with DataStore (`privacy_settings` file)
3. ~15 boolean/int preference keys (all defaults privacy-preserving)
4. Create `PrivacyAuditLoggerImpl` with Room DAO
5. Create `PrivacyModule.kt` Hilt module
6. **Geoapify fix**: Move API key from query parameter to `X-Api-Key` HTTP header in `GeoapifyGeocodingService.kt`

**Key defaults**: notification capture OFF, geocoding OFF, backfill OFF, GPS bias OFF, Overpass OFF, backup encryption ON, raw data in backup OFF, notification retention 30 days, OCR retention 90 days

**Tests**: defaults preserve privacy; settings persist; gate returns structured denial; audit logger stores no PII; Geoapify key not in logs

**Done when**: foundation compiles; no behavior change to existing services

---

### Batch 2 — Notification Capture Master Gate
**DB**: none

**Actions**:
1. Create `NotificationPrivacyGateImpl`
2. Wire gate into `NotificationCaptureService.onNotificationPosted()` — BEFORE any content extraction
3. Wire gate into `processNotification()` — per-package allowlist check
4. Raw extras toggle: set `extrasJson = null` when disabled
5. Wire `PrivacyAuditLogger` for denied captures
6. Expose per-package allowlist from `privacy_settings` DataStore
7. Keep existing `NotificationFilter` as content filter (not privacy gate)

**Gate logic**:
1. `notificationCaptureEnabled == false` → DENIED (no notification captured)
2. `notificationCaptureEnabled == true` → check per-package:
   - Package in blocklist → DENIED
   - Package in user allowlist → ALLOWED
   - Otherwise → DENIED (strict mode)

**Tests**: disabled → zero inserts/processing; blocked → skipped; allowed → captured; extras disabled → null extrasJson

**Done when**: notification capture has real master toggle; package management user-facing

---

### Batch 3 — Raw Data Retention (Notifications + OCR + Debug)
**DB**: v103 → v104 (add `rawContentPurgedAt`, `rawOcrTextPurgedAt` columns)

**Actions**:
1. Add columns to `RawNotification` and `ScannedReceipt` entities
2. Add DAO methods:
   - `deleteUnreferencedOlderThan(cutoffMs)` — delete raw_notifications with no FK references
   - `scrubReferencedOlderThan(cutoffMs, nowMs)` — NULL-out sensitive fields on referenced rows
   - `scrubOcrOlderThan(cutoffMs, nowMs, sentinel)` — scrub OCR text to `[PURGED_BY_RETENTION]`
3. Create `DataRetentionWorker` (daily CoroutineWorker)
4. Schedule from `AppStartupCoordinator`
5. Add `DebugDataStorage.deleteDebugData()` for `last_debug_data.json` cleanup
6. Add `PurgeSummary` return type with counts

**Retention strategy**:
- Unreferenced notifications → DELETE
- Referenced notifications → SCRUB (NULL sensitive fields, keep FK intact)
- Pending-review notifications → SKIP
- Old OCR text → SCRUB to sentinel (skip processing/pending-review/warranty receipts)
- Debug data → DELETE file
- Audit events → DELETE (30-day TTL)

**Tests**: unreferenced deleted; referenced scrubbed (FK intact); recent untouched; pending-review skipped; worker idempotent; purge now works

**Done when**: raw data no longer persists indefinitely

---

### Batch 4 — Location/Geocoding Privacy Gates
**DB**: none

**Actions**:
1. Create `LocationPrivacyGateImpl`
2. Gate `LocationResolver.resolve()`:
   - If `geocodingEnabled == false` → return `Unresolved` without external calls
   - If `gpsBiasEnabled == false` → skip `getLastKnownLocation()`
   - If `overpassEnabled == false` → skip Overpass step
3. Gate `LocationBackfillWorker.doWork()` — exit early when disabled
4. Gate scheduling in `AppStartupCoordinator` — only schedule when enabled; cancel existing when disabled
5. Gate `NotificationProcessingPipeline` GPS capture at notification time
6. Keep log anonymization (`LogSanitizer`) as-is

**Gate design**: When geocoding disabled, `LocationResolver` still returns cached/user-correction locations. Only external HTTP/GPS calls are suppressed.

**Tests**: disabled → zero external HTTP; GPS bias disabled → no location provider call; Overpass disabled → zero Overpass; backfill disabled → early exit; scheduling respects toggle

**Done when**: no location/geocoding network call without user opt-in

---

### Batch 5 — Central Cloud AI Gate
**DB**: none (uses v103 audit table from Batch 1)

**Actions**:
1. Create `CloudAiPrivacyGateImpl`
2. For each of 8 cloud AI services, replace per-service checks with central gate call:
   - `CloudReceiptAssistService`, `CloudDedupeJudgeService`, `CloudDashboardBriefingService`
   - `CloudReviewExplanationService`, `CloudCategorizationAssistService`
   - `CloudReceiptItemCategorizationService`, `CloudWarrantyExtractionService`
   - `CloudQueryInterpretationService`
3. Gate checks (in order): AI enabled → cloud allowed → capability enabled → API key present → Wi-Fi only check → image upload gate → ON_DEVICE mode
4. Write privacy audit event for every cloud attempt (allow/deny, redaction status, image included, no PII)
5. Preserve existing `redactBeforeCloud` and `receiptImageCloudEnabled` behaviors

**Consistency rule**: No cloud service reads `allowCloudAi` or API key directly — all route through `CloudAiPrivacyGate.check()`.

**Tests**: `allowCloudAi=false` → zero HTTP from all 8 services; missing key → zero; image + redaction → image suppressed; ON_DEVICE → no fallback; audit events contain no PII

**Done when**: cloud privacy behavior centralized and testable

---

### Batch 6 — Backup Encryption + Export Anonymization
**DB**: none

**Actions**:

**Backup encryption**:
1. Create `BackupEncryptionService` — AES-256-GCM with PBKDF2 (100K iterations, 256-bit key)
2. Create `SanitizedBackupExporter` — creates temp DB copy, scrubs raw data, packs as `.etbak` archive
3. Modify `DatabaseBackupRepositoryImpl.exportDatabase()` to use sanitized+encrypted export by default
4. Modify `DatabaseBackupRepositoryImpl.importDatabase()` to detect encrypted vs legacy format
5. Archive format: `[ETBK magic][manifest JSON][encrypted ZIP(database.db + receipts/)]`
6. Legacy unencrypted import preserved behind warning

**Export anonymization**:
1. Add `ExportPrivacyOptions` to `AccountingExportRepository`
2. Implement merchant anonymization via `RedactionSanitizer` (reuse `sha256Prefix`)
3. Wire anonymization into `AccountantReportPdfExporter` and CSV/IIF exporters
4. Add privacy checkboxes to `ExportOptionsScreen`

**Tests**: encrypted by default; raw data excluded by default; opt-in includes raw data; correct password imports; wrong password fails; legacy import works; anonymized export has no raw merchant names

**Done when**: default backups encrypted and free of raw financial/notification/OCR data

---

### Batch 7 — UI, Onboarding, Worker Scheduling & Guardrails
**DB**: none

**Actions**:
1. Create `PrivacySettingsScreen` with sections: Notification Capture, Location, AI Privacy, Raw Data Retention, Backup & Export
2. Create `PrivacyReviewPrompt` — one-time dialog for existing users explaining new privacy controls
3. Implement "Purge Now" button with confirmation dialog and summary
4. Schedule `DataRetentionWorker` from `AppStartupCoordinator`
5. Add Gradle guardrail task scanning for ungated HTTP/GPS/DB-insert patterns
6. Timber PII audit: scan all `Timber.d/i/w` calls for merchant names, notification text, OCR, coordinates
7. Clean up old per-service gate checks (removed in Batch 5)

**UI layout**: All toggles linked to DataStore. Disabled capabilities show explanatory text. Scary confirmation for sensitive enables (raw data in backup).

**Done when**: privacy controls user-facing; prompt appears once; purge works; guardrail scan passes

---

### Batch 8 — Integration Tests & Closeout
**DB**: none

**Actions**:
1. Integration test: all gates + real DataStore + real Room — fresh install defaults, enable/disable all, full pipeline
2. Backup encryption integration: export encrypted → verify unreadable as SQLite → import with password → data matches
3. Notification gate integration: service starts disabled → no inserts → enable → inserts work
4. Manual verification checklist (20 items from audit)
5. Finalize `PRIVACY_CAPABILITY_GATES.md` documentation
6. Annotate audit gaps as RESOLVED/DEFERRED

**Exit criteria**: All 20 acceptance criteria from audit met.

---

## 6. Execution Order & Dependencies

```
Batch 0 (baseline)
  └── Batch 1 (foundation — all others depend on this)
        ├── Batch 2 (notification gate — independent)
        ├── Batch 4 (location gates — independent)
        ├── Batch 5 (cloud AI gate — independent)
        ├── Batch 3 (retention — depends on Batch 1 migration)
        └── Batch 6 (backup/export — depends on Batch 1)
              └── Batch 7 (UI — depends on B2, B3, B4, B5, B6)
                    └── Batch 8 (integration — depends on all)
```

Recommended merge order: 0 → 1 → (2, 3, 4, 5, 6 in parallel) → 7 → 8

---

## 7. Testing Strategy

### Unit tests per gate
- `NotificationPrivacyGate`: disabled → denied; blocked → denied; allowed → allowed
- `LocationPrivacyGate`: each sub-toggle independently tested
- `CloudAiPrivacyGate`: cloud disabled, no key, WiFi-only, image upload, ON_DEVICE mode
- `PrivacySettingsRepository`: defaults, persist, restart

### No-network tests (fake OkHttpClient with call counter)
- `allowCloudAi = false` → all 8 services zero calls
- `geocodingEnabled = false` → all 5 providers + Overpass zero calls
- `wifiOnlyForCloud = true` on metered → zero cloud calls

### Retention tests
- Unreferenced deleted, referenced scrubbed, recent untouched, pending-review skipped
- Worker idempotent, no FK violations, sentinel handled gracefully

### Backup tests
- Encrypted by default, raw data excluded by default, opt-in works, wrong password fails, legacy import works

### Migration tests
- `MigrationTestHelper` for v102→v103 (new table)
- `MigrationTestHelper` for v103→v104 (new columns)
- Existing data preserved

---

## 8. Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Notification capture regression | HIGH | Gate returns Allowed when enabled; code paths unchanged |
| FK violations on purge | HIGH | Only DELETE unreferenced rows; SCRUB referenced rows |
| Backup encryption key loss | HIGH | Warn prominently; device-Keystore mode in future |
| Cloud AI fallback regression | MEDIUM | Gate doesn't override `AiCapabilityRouter.ON_DEVICE` enforcement |
| Existing user friction | MEDIUM | One-time dismissible prompt; no forced data purge |
| Geoapify API key in logs | LOW | Fixed in Batch 1 |
| Migration collision with Phase 5 | LOW | Phase 5 ends at v102; Phase 6 starts at v103 |

---

## 9. Rollback Strategy

| Batch | Rollback |
|---|---|
| 1 | Drop `privacy_audit_events` table, revert DB to v102, remove `PrivacyModule` |
| 2 | Revert `NotificationCaptureService` to pre-gate code |
| 3 | Revert migration v104 (remove columns). Remove worker scheduling. |
| 4 | Revert `LocationResolver`/`LocationBackfillWorker` to pre-gate code |
| 5 | Revert all 8 cloud services to inline checks |
| 6 | Revert to raw SQLite backup. Keep encrypted files (unimportable but live DB intact) |
| 7 | Revert UI. DataStore keys are harmless. |
| 8 | No rollback needed |

Critical: If Batch 6 export fails, temp files deleted, live DB untouched. Safety backups (last 3) always retained.

---

## 10. Files Summary

**New**: ~25 files (12 domain, 8 data, 1 DI, 3 UI, 1 docs)
**Modified**: ~30 files (6 domain, 17 data, 1 service, 1 startup, 3 UI)
**Total touch**: ~55 files

Key modified files:
- `service/NotificationCaptureService.kt`
- `domain/location/LocationResolver.kt`
- `data/location/LocationBackfillWorker.kt`
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `data/repository/AccountingExportRepository.kt`
- All 8 `data/ai/provider/Cloud*Service.kt` files
- `data/database/AppDatabase.kt` (v102→103→104)
- `startup/AppStartupCoordinator.kt`

---

## 11. Deferred Audit Items

| Finding | Disposition | Rationale |
|---|---|---|
| Per-request cloud AI consent prompt | Deferred | Low severity; cloud AI already off-by-default + redacted |
| Per-merchant location opt-out | Won't fix | Master geocoding toggle suffices |
| Certificate pinning | Won't fix | HTTPS + default trust store adequate for personal finance app |
| Privacy policy URL/consent flow | Deferred | Legal/UX concern, not Phase 6 engineering scope |
| ECB rates over HTTP | Acceptable | Public non-sensitive data |

---

## 12. Acceptance Criteria (Final)

1. Notification capture disabled by default + user-facing master toggle
2. Notification package allow/block management user-facing
3. Raw notification retention enforced (30-day TTL)
4. Geocoding disabled by default, gated
5. Background geocoding/backfill separately gated
6. GPS bias separately gated
7. Overpass lookup separately gated
8. All 8 cloud AI services use central `CloudAiPrivacyGate`
9. Cloud image upload remains explicit opt-in
10. Redaction-before-cloud remains default-on
11. Raw OCR retention enforced (90-day TTL)
12. Debug data retention enforced (7-day TTL)
13. Backup encrypted by default
14. Backup excludes raw notification/OCR data by default
15. Accounting exports support anonymization
16. Privacy settings accessible in normal UI
17. Privacy audit events contain no PII
18. Guardrail scan catches new ungated external calls
19. Tests prove denied gates cause zero network/raw-storage side effects
20. Phase 1-5 no regressions

---

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1.
