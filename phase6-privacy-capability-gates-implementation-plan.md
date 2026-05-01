# Phase 6 — Privacy Capability Gates Implementation Plan

## 0. Phase 6 Mission

Phase 6 introduces explicit privacy gates for every feature that captures, stores, exports, uploads, geocodes, or retains sensitive user data.

The audit found several strong existing privacy foundations:

- Cloud AI is mostly off by default.
- Receipt image upload is opt-in.
- Redaction before cloud is on by default.
- Location logs are anonymized.
- API keys are stored in encrypted preferences.
- Runtime location permission is checked before device GPS access.
- There are no third-party analytics/crash-reporting SDKs.

But major privacy gaps remain:

- Notification capture has no master toggle.
- Location/geocoding has no master toggle.
- Background location backfill has no opt-out.
- Raw notifications are retained indefinitely.
- Raw OCR text is retained indefinitely.
- Debug import data is retained indefinitely.
- Backup exports are unencrypted raw SQLite copies.
- Raw notification/OCR data are included in backup by default.
- Some cloud AI services may not consistently pass through the same gate.
- Notification package management is hidden in Debug screen.
- Export has no anonymization/redaction option.

The target of Phase 6 is a privacy capability gate system that makes sensitive behavior explicit, testable, auditable, and user-controllable.

---

# 1. Phase 6 Principles

## 1.1 Default-deny for sensitive capture/external calls

New privacy-sensitive capabilities should default to disabled unless the user explicitly enables them.

Default-off capabilities:

- notification capture
- cloud AI
- cloud image upload
- geocoding
- background location backfill
- GPS bias for merchant resolution
- Overpass nearby POI lookup
- raw data inclusion in backups
- raw OCR/notification indefinite retention

## 1.2 Centralized gates, not scattered conditionals

Every sensitive feature should call a gate service before doing work.

Examples:

- Notification capture calls `NotificationPrivacyGate`.
- Cloud AI calls `CloudAiGate`.
- Location/geocoding calls `LocationPrivacyGate`.
- Backup/export calls `BackupPrivacyGate`.
- Retention cleanup calls `RetentionPolicyRepository`.

Do not rely on each service remembering to check individual DataStore keys.

## 1.3 Denied means no side effects

If a privacy gate denies an operation, the feature must not:

- make HTTP calls
- access GPS
- insert raw notification rows
- upload prompt/image/text
- write raw debug files
- create raw unencrypted backup files
- schedule background workers that perform gated work

## 1.4 Audit without storing PII

When useful, write privacy audit events, but do not store raw payloads.

Audit events should record:

- capability
- allow/deny
- reason
- destination category
- timestamp
- payload class, not payload content
- redaction status
- network type if relevant
- source feature

They should not record:

- merchant names
- notification text
- OCR text
- email bodies
- coordinates
- API keys
- full URLs with query parameters

## 1.5 Retention must be explicit

Raw sensitive data should have a TTL and a purge/scrub mechanism.

The app should distinguish:

- structured transaction data needed for app function
- raw notification text
- raw OCR text
- email bodies/snippets
- debug import artifacts
- cloud request audit metadata

---

# 2. Dependencies / Preconditions

Before Phase 6 starts, ensure the Phase 1–5 baseline compiles and migrations are stable.

Minimum before starting:

1. Database version/migration issues from Phase 5 are resolved.
2. Room schema export matches migrations.
3. Hilt graph compiles.
4. `./gradlew.bat :app:compileDebugKotlin` passes.
5. `./gradlew.bat :app:kaptDebugKotlin` passes.
6. Existing privacy-related defaults in `AiSettings` are preserved.

Phase 6 will add more DataStore settings and likely at least one Room migration for retention/audit metadata.

---

# 3. Target Architecture

## 3.1 New privacy domain package

Suggested package:

`domain/privacy`

Core components:

- `PrivacyCapability`
- `PrivacyDecision`
- `PrivacyDecisionReason`
- `PrivacyGate`
- `PrivacySettingsRepository`
- `PrivacyAuditLogger`
- `RetentionPolicyRepository`
- `BackupPrivacyPolicy`
- `LocationPrivacyPolicy`
- `NotificationPrivacyPolicy`
- `CloudAiPrivacyPolicy`

## 3.2 New data package

Suggested package:

`data/privacy`

Components:

- `PrivacySettingsRepositoryImpl`
- `PrivacyAuditDao`
- `PrivacyAuditEvent`
- `DataRetentionWorker`
- `PrivacyDataCleanupRepository`
- `BackupEncryptionService`
- `SanitizedBackupExporter`

## 3.3 Capability groups

Define privacy capabilities at a level that maps to user understanding and tests.

Suggested capability groups:

### Notification

- `NOTIFICATION_CAPTURE`
- `NOTIFICATION_CAPTURE_PACKAGE`
- `RAW_NOTIFICATION_STORAGE`
- `RAW_NOTIFICATION_EXTRAS_STORAGE`

### Cloud AI

- `CLOUD_AI`
- `CLOUD_AI_RECEIPT_EXTRACTION`
- `CLOUD_AI_RECEIPT_IMAGE_UPLOAD`
- `CLOUD_AI_DEDUPE_JUDGE`
- `CLOUD_AI_DASHBOARD_BRIEFING`
- `CLOUD_AI_REVIEW_EXPLANATION`
- `CLOUD_AI_CATEGORIZATION`
- `CLOUD_AI_RECEIPT_ITEM_CATEGORIZATION`
- `CLOUD_AI_WARRANTY_EXTRACTION`
- `CLOUD_AI_QUERY_INTERPRETATION`

### Location

- `LOCATION_GEOCODING`
- `LOCATION_BACKGROUND_BACKFILL`
- `LOCATION_GPS_BIAS`
- `LOCATION_OVERPASS_LOOKUP`
- `LOCATION_EXTERNAL_PROVIDER`

### Backup/export

- `BACKUP_CREATE`
- `BACKUP_UNENCRYPTED_LEGACY`
- `BACKUP_INCLUDE_RAW_NOTIFICATIONS`
- `BACKUP_INCLUDE_RAW_OCR`
- `BACKUP_INCLUDE_RECEIPT_IMAGES`
- `ACCOUNTING_EXPORT`
- `ACCOUNTING_EXPORT_ANONYMIZED`

### Retention/debug

- `RAW_DATA_RETENTION`
- `DEBUG_DATA_STORAGE`
- `DEBUG_DATA_EXPORT`

---

# 4. Privacy Settings Design

## 4.1 New top-level PrivacySettings

Add a DataStore-backed settings model.

Recommended defaults:

### Notification privacy

- notification capture enabled: false
- capture finance apps: false until user enables
- capture communication apps: false until user enables
- raw extras storage: false
- notification retention days: 30
- auto purge notifications: true
- package allowlist mode: preferred
- package blocklist support: retained

### Location privacy

- geocoding enabled: false
- background backfill enabled: false
- GPS bias enabled: false
- Overpass lookup enabled: false
- external geocoding providers enabled: false
- Wi-Fi only for background geocoding: true
- location cache retention days: 30

### Backup/export privacy

- encrypt backups: true
- password-protected portable backups: recommended
- include raw notifications: false
- include raw OCR text: false
- include debug data: false
- include receipt images: explicit opt-in
- anonymize accounting exports: false
- include notes in exports: true or user-controlled
- include location fields in exports: false by default

### Raw data retention

- raw OCR retention days: 90
- raw notification retention days: 30
- debug data retention days: 7
- cloud audit event retention days: 30
- auto purge enabled: true

## 4.2 Relationship to existing AiSettings

Do not duplicate existing AI settings unnecessarily.

Keep existing `AiSettings` as the source for:

- `aiEnabled`
- `allowCloudAi`
- `allowOnDeviceAi`
- capability-specific AI feature toggles
- `receiptImageCloudEnabled`
- `redactBeforeCloud`
- `wifiOnlyForCloud`
- `storeConversationHistory`
- preferred mode

Add a central `CloudAiGate` that reads existing `AiSettings`.

If you later add per-capability cloud toggles, store them either:

1. in `AiSettings`, if they are AI-product settings, or
2. in `PrivacySettings`, if they are privacy consent settings.

Do not let individual cloud services read random settings directly.

---

# 5. PR Implementation Plan

## PR 0 — Baseline, privacy map, and guardrail doc

### Goal

Create a stable baseline and a checklist before behavior changes.

### Actions

1. Run compile and tests.
2. Record current failing tests.
3. Create `docs/development/PRIVACY_CAPABILITY_GATES.md`.
4. Create a table of all external call sites from the audit.
5. Create an approved list of direct OkHttp users.
6. Create an approved list of raw-data stores:
   - `raw_notifications`
   - `scanned_receipts.rawOcrText`
   - `last_debug_data.json`
   - backup export files
7. Document default privacy posture.

### Done when

- Baseline is known.
- Every sensitive capability has an owner.
- No behavior change yet.

---

## PR 1 — Privacy settings repository and gate foundation

### Goal

Add the central privacy settings and gate infrastructure.

### Add

- `PrivacySettingsRepository`
- `PrivacySettingsRepositoryImpl`
- `PrivacyCapability`
- `PrivacyDecision`
- `PrivacyDecisionReason`
- `PrivacyGate`
- `PrivacyAuditLogger`
- `PrivacyAuditEvent`, if using DB audit events now

### Storage

Add a new DataStore:

`privacy_settings`

Initial keys:

- notification capture enabled
- raw notification retention days
- raw notification auto purge enabled
- store raw notification extras
- geocoding enabled
- background backfill enabled
- GPS bias enabled
- Overpass enabled
- backup encryption enabled
- include raw notification backup
- include raw OCR backup
- include receipt images backup
- anonymize accounting exports
- raw OCR retention days
- debug data retention days

### Defaults

Use privacy-preserving defaults.

Important:

- New installs should default to sensitive features off.
- Existing installs should show a privacy review prompt before continuing capture/geocoding behavior.

### Tests

- defaults are privacy-preserving
- settings persist and reload
- gate denies disabled capability
- gate returns structured denial reason
- audit logger does not store PII

### Done when

- A central privacy gate exists.
- No service migration yet.

---

## PR 2 — Notification capture master gate

### Goal

Add a real user-facing master switch for notification capture.

### Files

- `NotificationCaptureService.kt`
- `NotificationRepository.kt`
- `NotificationFilter.kt`
- `RawNotificationDao.kt`
- settings UI/view model
- debug screen package management

### Actions

1. Add `notificationCaptureEnabled` setting.
2. In `NotificationCaptureService.onNotificationPosted`, check the gate before:
   - reading extras deeply
   - dedup processing
   - repository insert
   - pipeline processing
3. If disabled, return immediately.
4. Add package allowlist/blocklist management to normal settings.
5. Keep the existing Room blocked packages table, but expose it outside Debug.
6. Add optional monitored package allowlist:
   - safer than hardcoded finance packages only
   - user can enable specific apps
7. Add raw extras storage toggle:
   - if disabled, do not store full `extrasJson`
   - store only minimal metadata needed for processing/debug
8. Add privacy audit event for denied capture, without notification content.
9. Keep `NotificationFilter` as a second-level filter, not the primary privacy gate.

### Product decision

Choose one:

### Strict privacy mode

- default capture disabled for all users
- user must enable capture explicitly

### Compatibility mode

- existing users with notification listener permission get a one-time privacy review prompt
- capture pauses until prompt is answered

Recommended: strict privacy mode for new installs and review prompt for existing installs.

### Tests

- disabled capture creates no `RawNotification`
- disabled capture does not call processing pipeline
- blocked package still blocks when capture enabled
- allowlisted package captures when enabled
- ignored package remains ignored
- raw extras disabled stores no full extras JSON
- package settings survive app restart

### Done when

- notification capture has a real master gate.
- package management is user-facing.

---

## PR 3 — Raw notification retention

### Goal

Prevent indefinite raw notification retention.

### Files

- `RawNotificationDao.kt`
- `NotificationRepository.kt`
- new `DataRetentionWorker`
- `AppStartupCoordinator`
- privacy settings UI

### Actions

1. Add DAO methods for:
   - deleting old unreferenced raw notifications
   - scrubbing old referenced raw notifications
   - counting purge candidates
2. Determine FK safety:
   - if expenses/pending reviews reference raw notifications, do not blindly delete referenced rows
   - scrub sensitive text fields instead
3. Retention strategy:
   - delete unreferenced raw notifications older than TTL
   - for referenced rows older than TTL, set sensitive fields to purge marker:
     - title
     - text
     - bigText
     - subText
     - extrasJson
     - parseResult if it contains raw text
   - keep package name, timestamps, relevance flags if needed for stats
4. Add `rawContentPurgedAt` column if useful.
5. Schedule daily `DataRetentionWorker`.
6. Add “Purge now” action in Privacy settings.
7. Add purge summary UI:
   - raw notifications deleted
   - raw notifications scrubbed
   - debug files deleted
   - OCR rows scrubbed

### Tests

- old unreferenced raw notifications are deleted
- old referenced raw notifications are scrubbed, not FK-broken
- recent notifications remain
- retention disabled leaves rows unchanged
- purge now runs immediately
- worker uses `TimeProvider`
- no raw notification text remains after scrub

### Done when

- raw notifications no longer persist indefinitely by default.

---

## PR 4 — Location/geocoding privacy gates

### Goal

Stop all geocoding/location calls unless enabled.

### Files

- `LocationResolver.kt`
- `LocationBackfillWorker.kt`
- `AndroidForegroundLocationProvider.kt`
- `NotificationProcessingPipeline.kt`
- `CompositeGeocodingService.kt`
- `OverpassNearbyService.kt`
- `AppStartupCoordinator.kt`
- settings UI/view model

### Actions

1. Add `LocationPrivacyGate`.
2. Gate `LocationResolver.resolve()`:
   - if geocoding disabled, return unresolved result without external calls
   - existing cached/manual corrections may still be used if product allows
3. Gate external provider calls:
   - Nominatim
   - Photon
   - Geoapify
   - Google Places
4. Gate Overpass separately.
5. Gate GPS bias:
   - do not call `ForegroundLocationProvider.getLastKnownLocation()` unless enabled
6. Gate notification-time GPS capture:
   - `NotificationProcessingPipeline` must not access GPS unless GPS bias/location capture is enabled
7. Gate `LocationBackfillWorker`:
   - check before scheduling
   - check again at runtime
   - cancel existing scheduled backfill when disabled
8. Add UI toggles:
   - Enable geocoding
   - Enable background backfill
   - Allow GPS bias
   - Allow nearby POI lookup
9. Keep log anonymization.

### Tests

- geocoding disabled causes zero HTTP calls
- GPS bias disabled causes zero location provider calls
- Overpass disabled causes zero Overpass calls
- backfill disabled exits early
- disabling setting cancels/suppresses scheduled work
- manual merchant location corrections still work
- cached locations can display without network, if allowed

### Done when

- no location/geocoding network call can occur without user opt-in.

---

## PR 5 — Central Cloud AI gate

### Goal

Ensure all cloud AI services use one gate.

### Files

Cloud services:

- `CloudReceiptAssistService`
- `CloudDedupeJudgeService`
- `CloudDashboardBriefingService`
- `CloudReviewExplanationService`
- `CloudCategorizationAssistService`
- `CloudReceiptItemCategorizationService`
- `CloudWarrantyExtractionService`
- `CloudQueryInterpretationService`

Other files:

- `SmartReceiptAssistService`
- `AiCapabilityRouter`
- `AiSettingsRepository`
- `CloudPiiSanitizer`
- network module

### Add

`CloudAiGate`

Gate inputs:

- capability
- needs image upload?
- text payload class
- redaction required?
- network requirement
- preferred AI mode
- API key presence

Gate checks:

1. AI enabled.
2. Capability enabled.
3. Cloud AI allowed.
4. API key present.
5. If Wi-Fi only, current network is Wi-Fi/unmetered.
6. If image upload requested:
   - receipt image cloud enabled
   - redact-before-cloud behavior understood
   - no image upload when redaction is required and image cannot be redacted
7. If preferred mode is ON_DEVICE, no cloud fallback.
8. Redaction policy applied before network call.

### Actions

1. Remove service-local inconsistent gate checks.
2. Every cloud service calls `CloudAiGate` before building/executing HTTP request.
3. Every denied call returns a structured local fallback/disabled result.
4. Add privacy audit event for cloud attempt:
   - capability
   - allowed/denied
   - redaction status
   - image included yes/no
   - destination class: Gemini
   - no prompt text
5. Add per-capability cloud setting if product wants more control than existing capability toggles.

### Tests

- allowCloudAi false prevents every cloud HTTP call
- API key missing prevents every cloud HTTP call
- redaction true suppresses image upload
- receiptImageCloudEnabled false suppresses image upload
- ON_DEVICE mode never falls back to cloud
- wifiOnly blocks cloud on metered network
- all 8 cloud services tested with fake OkHttp call counter
- audit events contain no raw prompt text

### Done when

- cloud privacy behavior is centralized and testable.

---

## PR 6 — External HTTP guardrails

### Goal

Prevent future external services from bypassing privacy gates.

### Actions

1. Add documentation rule:
   - every new external HTTP service must declare its privacy capability
   - every external call must be behind a gate or explicitly whitelisted
2. Add a lightweight audit script or Gradle task that scans for:
   - `OkHttpClient`
   - `.newCall`
   - `HttpURLConnection`
   - external base URLs
3. Allowlist:
   - ECB exchange rates, if considered non-sensitive
   - explicitly gated cloud AI services
   - explicitly gated geocoding services
4. Add `ExternalRequestDescriptor` for audit metadata:
   - service
   - endpoint category
   - data class sent
   - privacy capability
5. Review Geoapify API key in query parameter:
   - if API supports header auth, move key to header
   - if not, ensure logs never include full URL

### Tests

- scan fails for new ungated HTTP call
- existing services pass allowlist
- Geoapify logs do not expose API key

### Done when

- new network features cannot silently bypass privacy review.

---

## PR 7 — Raw OCR/debug data retention

### Goal

Stop indefinite OCR/debug retention.

### Files

- `ScannedReceipt.kt`
- `ScannedReceiptDao.kt`
- `ReceiptRepository.kt`
- `ReceiptLifecycleCoordinator.kt`
- `DebugDataStorage.kt`
- `DataRetentionWorker`

### Schema decision

`scanned_receipts.rawOcrText` is currently non-null. Options:

### Option A — keep non-null

Scrub text to sentinel:

- `[PURGED_BY_RETENTION]`

Add:

- `rawOcrTextPurgedAt`
- `rawOcrRetentionPolicy`

### Option B — migrate to nullable

Make `rawOcrText` nullable and update all callers.

Option A is safer short-term.

### Actions

1. Add retention columns if needed.
2. Add DAO method to scrub old OCR text.
3. Do not scrub OCR text for receipts that are:
   - still processing
   - pending review
   - needed for active warranty extraction
   - explicitly pinned by user
4. After purge, features should degrade gracefully:
   - AI receipt assist says OCR no longer available
   - warranty extraction does not run
   - duplicate detection uses stored fingerprints
   - receipt image may remain if user kept images
5. Add debug data cleanup:
   - delete `last_debug_data.json` after TTL
   - expose “Clear debug data now”
6. Add privacy settings:
   - raw OCR retention days
   - debug retention days
   - purge now

### Tests

- old OCR text is scrubbed
- recent OCR text remains
- pending review OCR text remains until safe
- scrubbed receipt does not crash AI/warranty/debug views
- debug JSON file is removed after TTL
- purge now works

### Done when

- OCR/debug raw data no longer persists indefinitely.

---

## PR 8 — Backup privacy foundation

### Goal

Make backups safe by default.

### Files

- `DatabaseBackupRepository.kt`
- `DatabaseBackupRepositoryImpl.kt`
- `BackupRepositoryModule.kt`
- settings UI
- backup/import tests

### Current problem

Backup is a raw unencrypted SQLite copy that includes:

- expenses
- raw notification text
- raw OCR text
- pending reviews
- user corrections
- notification extras JSON

### Target backup format

Move from raw DB copy to a backup archive format.

Archive contents:

- sanitized database copy
- optional receipt images
- manifest JSON
- schema version
- app version
- backup created timestamp
- privacy options used
- encryption metadata

### Encryption modes

Support at least one safe mode.

Recommended:

### Portable password-protected backup

- user supplies password
- random salt
- key derivation
- AES-GCM encryption
- manifest stored either encrypted or with only non-sensitive metadata

Optional later:

### Device-protected backup

- key from Android Keystore
- easier UX
- not portable across devices

### Raw legacy backup

If retained for debug:

- hidden behind developer/debug mode
- explicit warning
- never default
- writes privacy audit event

### Sanitization options

Default backup should exclude or scrub:

- `raw_notifications.title/text/bigText/subText/extrasJson/parseResult`
- `scanned_receipts.rawOcrText`
- debug data
- AI conversation history unless enabled
- potentially precise location fields if user excludes location

Do not mutate the live database. Create a temporary sanitized copy.

### Actions

1. Add `BackupPrivacyPolicy`.
2. Add `BackupEncryptionService`.
3. Add sanitized DB copy pipeline.
4. Add archive manifest.
5. Add encrypted export.
6. Add import path for encrypted archive.
7. Preserve support for legacy raw DB import if needed.
8. Add settings:
   - encrypt backups
   - include raw notifications
   - include raw OCR
   - include receipt images
   - include precise locations
9. Add scary confirmation if user includes raw data.

### Tests

- default backup is encrypted
- default backup excludes raw notifications
- default backup excludes raw OCR text
- include raw data option works only when explicitly enabled
- encrypted backup imports successfully
- wrong password fails safely
- legacy backup import still works if supported
- live DB is unchanged after sanitized export
- temporary sanitized DB is deleted after export

### Done when

- default backups no longer expose raw financial/notification/OCR data.

---

## PR 9 — Accounting export anonymization

### Goal

Give users control over PII in exports.

### Files

- `AccountingExportRepository.kt`
- `AccountingExporters.kt`
- `AccountantReportPdfExporter.kt`
- `ExportOptionsScreen.kt`
- `AccountingExportPolicy.kt`

### Add export privacy options

- anonymize merchant names
- exclude notes
- exclude location/address
- round dates to month
- exclude large transaction highlights
- include category only
- include receipt metadata no/yes
- include raw OCR never by default

### Actions

1. Add export options model.
2. Use `RedactionSanitizer` or export-specific anonymizer.
3. Add preview summary:
   - number of transactions
   - fields included
   - raw data included yes/no
   - anonymization enabled yes/no
4. Ensure PDFs and CSV/IIF respect the same policy.
5. Add privacy audit event for export.

### Tests

- anonymized export does not contain merchant names
- notes excluded when disabled
- location excluded when disabled
- raw OCR never included by default
- PDF obeys same options as CSV
- export summary matches actual fields

### Done when

- user-facing exports can be privacy-preserving.

---

## PR 10 — Privacy settings UI and onboarding

### Goal

Expose privacy gates clearly to users.

### Add top-level Privacy screen

Suggested sections:

### Notification Capture

- master toggle
- monitored apps
- blocked apps
- store raw extras toggle
- retention period
- purge now

### Location

- enable geocoding
- enable background backfill
- allow GPS bias
- allow nearby POI lookup
- clear location cache

### AI Privacy

Link to existing AI settings, or show key privacy toggles:

- cloud AI enabled
- redact before cloud
- receipt image cloud upload
- Wi-Fi only
- conversation history

### Raw Data Retention

- notification retention days
- OCR retention days
- debug data retention days
- purge now

### Backup / Export

- encrypt backups
- include raw data in backups
- include receipt images
- anonymize exports

### Privacy Review Prompt

Show when:

- first launch after Phase 6 migration
- notification listener is enabled but app capture toggle is unset
- location permissions are granted but geocoding toggle is unset
- cloud AI key exists but cloud setting is off
- user tries to use a gated feature

### Tests

- toggles update DataStore
- disabled capabilities show explanatory UI
- privacy review prompt appears once
- purge now displays summary
- settings survive restart

### Done when

- privacy controls are user-facing, not hidden in Debug/AI screens.

---

## PR 11 — Data retention worker

### Goal

Run privacy cleanup automatically.

### Worker

`DataRetentionWorker`

Schedule:

- daily
- constraints: battery not low if desired
- no network required

Responsibilities:

1. Purge/scrub raw notifications.
2. Purge/scrub raw OCR text.
3. Delete old debug data files.
4. Delete old privacy audit events.
5. Optionally clear old AI runtime artifacts/history if setting disabled.
6. Emit summary diagnostics.

### Startup behavior

`AppStartupCoordinator` should schedule it.

It should also run after settings changes where TTL is shortened.

### Tests

- worker respects settings
- worker is idempotent
- worker does not break FK references
- worker handles missing files
- worker summary is accurate

### Done when

- retention policy is enforced automatically.

---

## PR 12 — Final guardrails and closeout

### Goal

Prevent regressions.

### Add scan checks

Flag these patterns unless allowlisted:

- `OkHttpClient` injection without declared privacy capability
- `.newCall(` in ungated services
- `HttpURLConnection` usage
- `ForegroundLocationProvider.getLastKnownLocation()` without gate
- `LocationResolver.resolve()` call path without gate
- `rawNotificationDao.insert` without notification gate
- direct raw backup DB copy
- cloud AI service not using `CloudAiGate`
- `scanned_receipts.rawOcrText` use without retention awareness
- writing `last_debug_data.json` without TTL awareness
- `Timber.d` / `Timber.i` logging merchant names, coordinates, OCR, notification text

### Documentation

Update:

- privacy capability matrix
- user-facing privacy behavior
- external service list
- data retention policy
- backup/export privacy behavior
- cloud AI redaction behavior
- location/geocoding behavior

### Done when

- guardrail scan passes
- docs match implementation
- all high-severity audit gaps are closed or explicitly deferred

---

# 6. File-by-File Migration Targets

## Notification

| File | Action |
|---|---|
| `NotificationCaptureService.kt` | Add master gate before processing |
| `NotificationFilter.kt` | Keep as content filter, not privacy gate |
| `NotificationRepository.kt` | Add retention/block/allow settings integration |
| `RawNotificationDao.kt` | Add purge/scrub DAO methods |
| `RawNotification.kt` | Add purge metadata if needed |
| `DebugScreen.kt` | Move package blocking to real settings |
| `NotificationProcessingPipeline.kt` | Gate GPS access and raw parse storage |

## Cloud AI

| File | Action |
|---|---|
| `CloudReceiptAssistService.kt` | Use central gate |
| `CloudDedupeJudgeService.kt` | Use central gate |
| `CloudDashboardBriefingService.kt` | Use central gate |
| `CloudReviewExplanationService.kt` | Use central gate |
| `CloudCategorizationAssistService.kt` | Use central gate |
| `CloudReceiptItemCategorizationService.kt` | Use central gate |
| `CloudWarrantyExtractionService.kt` | Use central gate |
| `CloudQueryInterpretationService.kt` | Use central gate |
| `SmartReceiptAssistService.kt` | Preserve no-cloud-fallback in ON_DEVICE mode |
| `CloudPiiSanitizer.kt` | Keep as central sanitizer |
| `AiSettingsRepositoryImpl.kt` | Reuse existing settings |

## Location

| File | Action |
|---|---|
| `LocationResolver.kt` | Gate external/geocoding/GPS pipeline |
| `LocationBackfillWorker.kt` | Gate runtime and scheduling |
| `AndroidForegroundLocationProvider.kt` | Keep permission check, add upstream gate |
| `CompositeGeocodingService.kt` | Enforce provider gates |
| `NominatimGeocodingService.kt` | Ensure no call without gate |
| `PhotonGeocodingService.kt` | Ensure no call without gate |
| `GeoapifyGeocodingService.kt` | Avoid API key in logs; consider header auth |
| `GooglePlacesGeocodingService.kt` | Ensure no call without gate |
| `OverpassNearbyService.kt` | Separate Overpass toggle |
| `AppStartupCoordinator.kt` | Respect backfill toggle |

## Backup/export

| File | Action |
|---|---|
| `DatabaseBackupRepositoryImpl.kt` | Replace raw default backup with encrypted/sanitized archive |
| `DatabaseBackupRepository.kt` | Add privacy options |
| `AccountingExportRepository.kt` | Add anonymization options |
| `AccountingExporters.kt` | Respect privacy fields |
| `AccountantReportPdfExporter.kt` | Respect anonymization/exclusion |
| `ExportOptionsScreen.kt` | Add privacy options |

## Retention/debug

| File | Action |
|---|---|
| `ScannedReceipt.kt` | Add OCR purge metadata if needed |
| `ScannedReceiptDao.kt` | Add raw OCR scrub methods |
| `DebugDataStorage.kt` | Add TTL cleanup |
| `DebugScreen.kt` | Add clear debug data action |
| `AppStartupCoordinator.kt` | Schedule retention worker |

---

# 7. Testing Strategy

## 7.1 Gate unit tests

Test each gate independently:

- notification capture disabled
- geocoding disabled
- background backfill disabled
- GPS bias disabled
- Overpass disabled
- cloud AI disabled
- cloud image upload disabled
- backup raw data excluded
- retention enabled/disabled

## 7.2 No-network tests

Use fake HTTP clients or call counters.

Required tests:

- all 8 cloud services make zero HTTP calls when cloud disabled
- all geocoding services make zero HTTP calls when geocoding disabled
- Overpass makes zero HTTP calls when disabled
- Wi-Fi-only cloud blocks on metered network
- ON_DEVICE mode never falls back to cloud

## 7.3 Notification tests

- capture disabled inserts no raw notification
- package blocked inserts no raw notification
- package allowlisted captures only when master enabled
- raw extras disabled stores no extras JSON
- retention scrubs/deletes old notifications safely

## 7.4 Location tests

- `LocationResolver` returns unresolved without network when disabled
- GPS provider not called when GPS bias disabled
- worker exits early when backfill disabled
- scheduling respects toggle

## 7.5 Backup/export tests

- encrypted backup created by default
- raw notifications excluded by default
- raw OCR excluded by default
- receipt images included only if opted in
- import encrypted backup succeeds
- wrong password fails
- anonymized CSV/PDF contains no merchant names

## 7.6 Retention tests

- raw notification purge
- raw OCR purge
- debug data purge
- purge now
- no FK breakage
- no crash when OCR text has been purged

## 7.7 Regression tests from audit

1. Notification capture has master toggle.
2. Location/geocoding has master toggle.
3. Background location backfill has opt-out.
4. Raw notifications are not retained indefinitely.
5. Raw OCR text is not retained indefinitely.
6. Backup is encrypted by default.
7. Raw data is excluded from backup by default.
8. Cloud AI calls all use central gate.
9. Receipt image upload remains opt-in.
10. Debug data has cleanup.
11. Package blocking is available outside Debug.
12. External HTTP guard catches ungated call sites.

---

# 8. Acceptance Criteria

Phase 6 is complete when:

1. Notification capture is disabled by default and has a user-facing master toggle.
2. Notification package allow/block management is user-facing.
3. Raw notification retention is enforced.
4. Geocoding is disabled by default and gated.
5. Background geocoding/backfill is separately gated.
6. GPS bias is separately gated.
7. Overpass lookup is separately gated.
8. All cloud AI services use a central `CloudAiGate`.
9. Cloud image upload remains explicit opt-in.
10. Redaction-before-cloud remains default-on.
11. Raw OCR retention is enforced.
12. Debug data retention is enforced.
13. Backup is encrypted by default.
14. Backup excludes raw notification/OCR data by default.
15. Accounting exports support anonymization.
16. Privacy settings are accessible in normal UI.
17. Privacy audit events do not store PII.
18. Guardrail scan catches new ungated external calls.
19. Tests prove denied gates cause zero network/raw-storage side effects.
20. Documentation matches implementation.

---

# 9. Recommended Implementation Order

Recommended PR order:

1. Baseline and privacy matrix.
2. Privacy settings repository and gate foundation.
3. Notification capture master gate.
4. Raw notification retention.
5. Location/geocoding gates.
6. Central Cloud AI gate.
7. External HTTP guardrails.
8. Raw OCR/debug retention.
9. Backup encryption and sanitized backup.
10. Accounting export anonymization.
11. Privacy settings UI and onboarding.
12. Data retention worker.
13. Final guardrails and docs closeout.

This order fixes the highest-risk capture/storage issues first, then external-call gates, then backup/export safety, then UI polish and guardrails.

---

# 10. Phase 6 Closeout Checklist

Before closing Phase 6, run:

- compile
- unit tests
- Room migration tests if schema changed
- Hilt graph check
- privacy gate tests
- no-network tests
- backup encryption/import tests
- retention worker tests
- guardrail scan

Manual verification:

- fresh install defaults are privacy-preserving
- existing install gets privacy review prompt
- notification capture does nothing until enabled
- geocoding does nothing until enabled
- cloud AI does nothing until enabled
- backup file is encrypted by default
- raw data is excluded by default
- purge now actually removes/scrubs raw data
- no sensitive values appear in logs