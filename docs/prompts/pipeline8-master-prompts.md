# Pipeline 8 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P8 — Privacy / AI / Redaction**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P8 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_8_CONSOLIDATED_ISSUES.md
- P8 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_8_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Universal tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md
- Privacy UI architecture: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/PRIVACY_UI_ARCHITECTURE.md
- Sensitive diagnostics policy: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md
- Hilt bindings map: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/hilt-bindings-map.md
- Architecture folder: https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture

Important context:
- P8 is **Privacy / AI / Redaction**.
- Core architecture segments involved:
  - Segment 20 — AI Platform, Assistant & Follow-Through
  - Segment 28 — Security & API Key Management
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
  - Segment 33 — Configuration / Performance / Accessibility
- Cross-pipeline segments involved:
  - Segment 3 — Notification Capture
  - Segment 4 — Receipt Scanning / OCR
  - Segment 5 — AI Receipt Item Categorization
  - Segment 14 — Bank Integration
  - Segment 18 — Export & Backup
  - Segment 19 — Location Enrichment
  - Segment 26 — Natural Language Search
- P8 registry says many issues are TODO/open, but current code at this commit already contains newer privacy primitives such as `PreparedCloudPayload`, `RawStorageMode`, `PrivacyAuditContext`, `SafePrivacyMetadata`, `EffectiveCloudAiPolicy`, `RetentionRegistry`, and typed `PrivacyBlocked`.
- Therefore: **do not trust tracker status. Validate every P8 issue against code at the target SHA.**
- Code is source of truth. Docs are context and architecture expectations.

---

## Prompt A — P8 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin privacy, security, AI-policy, data-retention, WorkManager, diagnostics-redaction, and architecture-review agent.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P8 — Privacy / AI / Redaction

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not exactly at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 8 end-to-end:

### Privacy settings scope
- `PrivacySettings`,
- `PrivacySettingsRepository`,
- `PrivacySettingsRepositoryImpl`,
- `AiSettings` / `AiSettingsRepository`,
- effective cloud AI policy,
- privacy setting load-state / corruption behavior,
- fail-closed defaults,
- settings update atomicity,
- runtime worker cancellation/rescheduling when privacy toggles change,
- consistency between privacy settings and AI settings,
- first-run defaults vs corrupted defaults.

### Privacy gate scope
- `PrivacyGate`,
- `CompositePrivacyGate`,
- all concrete gates:
  - `NotificationPrivacyGate`,
  - `LocationPrivacyGate`,
  - `CloudAiPrivacyGate`,
  - `BackupPrivacyGate`,
  - `ExportPrivacyGate`,
- `PrivacyCapability`,
- `PrivacyCapabilityHandlingPolicy`,
- `PrivacyDecision`,
- `PrivacyBlocked`,
- `NotificationCaptureGate`,
- typed denied-state mapping,
- fail-closed behavior,
- no unhandled capability should fail open,
- gates should log one final decision, not noisy partial decisions.

### Cloud AI / payload redaction scope
- `CloudPayloadPolicy`,
- `DefaultCloudPayloadPolicy`,
- `CloudPayloadRedactor`,
- `DefaultCloudPayloadRedactor`,
- `PreparedCloudPayload`,
- `EffectiveCloudAiPolicy`,
- redaction before every cloud call,
- capability-specific AI policy,
- AI provider calls,
- receipt image cloud upload,
- bank statement AI parsing,
- AI assistant/chat,
- natural-language search,
- dashboard briefing,
- review explanation,
- warranty extraction,
- duplicate detection if cloud-capable,
- any prompt builder or raw text sender.

### Raw storage / persistence scope
- `RawStorageMode`,
- `RawPersistencePolicy`,
- `RawPersistencePolicyResolver`,
- `RawContentSanitizer`,
- source-specific persistence payloads:
  - `NotificationPersistencePayload`,
  - `ReceiptPersistencePayload`,
  - `EmailReceiptPersistencePayload`,
  - `BankTransactionPersistencePayload`,
- raw notification text,
- OCR text,
- email receipt bodies,
- bank statement raw text,
- AI artifacts/chats,
- debug persistence,
- “do not store” and “store redacted only” modes,
- write-time sanitization rather than store-first-purge-later.

### Data retention scope
- `DataRetentionWorker`,
- `RetentionRegistry`,
- `RetentionTarget`,
- retention target coverage,
- per-target checkpoints,
- per-target failure behavior,
- worker guard/run logging,
- cancellation propagation,
- audit events for purges,
- retention of:
  - raw notifications,
  - OCR text,
  - email receipt sources,
  - AI artifacts,
  - AI chat messages,
  - notification intake text,
  - pipeline diagnostics,
  - pending review notification text,
  - background job error messages,
  - bank statement import items.

### Audit / diagnostics scope
- `PrivacyAuditLogger`,
- `PrivacyAuditLoggerImpl`,
- `PrivacyAuditContext`,
- `SafePrivacyMetadata`,
- `SensitiveHashingService`,
- `DefaultSensitiveHashingService`,
- `EventMetadataSanitizer`,
- durable diagnostics,
- operation-run errors,
- background-job errors,
- service diagnostics,
- raw logs / Timber,
- no raw merchant names, addresses, OCR, notification text, prompts, tokens, or account numbers in release logs/durable diagnostics.

### Export / backup / raw database scope
- raw database export,
- encrypted backup gate,
- plain export gate,
- redacted export,
- debug raw export,
- `ExportAnonymizer`,
- P7 backup privacy mode,
- P12 import/export overlap,
- no raw backup reachable in production unless explicitly gated and debug-only.

### Location/geocoding scope
- external geocoding,
- background location backfill,
- device GPS,
- Overpass API,
- location provider implementations,
- static guarantee that every external location provider checks privacy.

### UI / UX scope
- privacy settings UI,
- typed `PrivacyBlocked` rendering,
- `PrivacyBlockedCard`,
- assistant privacy-denied state,
- spending map GPS/location denied state,
- export/backup denied state,
- AI settings denied state,
- no silent privacy-denied failure.

### Cross-pipeline dependencies
- P1/P3 notification capture must gate before text extraction/persistence.
- P3/P4 receipt OCR must respect raw OCR storage policy.
- P5/P6 diagnostics must not leak financial/merchant data.
- P7 backup/export must honor raw export privacy gates.
- P10 bank integration must redact/gate cloud statement text.
- P11 email receipt ingestion must sanitize or drop raw email content at write time.
- P12 export/import must preserve privacy audit and redaction contracts.
- Location enrichment must gate external calls.

Read first:
- `docs/analyses and debug master/PIPELINE_8_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_8_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs, especially privacy/raw-storage contracts.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that method:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read:
- UI/privacy:
  - `PRIVACY_UI_ARCHITECTURE.md`
  - `COMPREHENSIVE_UI_MAP.md`
  - `VIEWMODEL_INJECTION_MAP.md`
  - `route-viewmodel-map.md`
- Privacy/diagnostics:
  - `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

For P8 specifically, pay special attention to:
- Segment 20 — AI Platform.
- Segment 28 — Security & API Key Management.
- Segment 29 — Debug & Diagnostics.
- Segment 30 — Hilt bindings.
- Sensitive diagnostics/logging rules.
- Privacy UI denied-state architecture.
- Export/backup privacy gate architecture.
- Worker guard and runtime-worker policy docs.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, and tests to build the real inventory.

### Domain privacy core
Review:
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/BankTransactionPersistencePayload.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadPolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadRedactor.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/EffectiveCloudAiPolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/EmailReceiptPersistencePayload.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/ExportPrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationCaptureGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPersistencePayload.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PreparedCloudPayload.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyAuditContext.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyAuditLogger.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyBlocked.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyCapability.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyCapabilityHandlingPolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettingsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RawContentSanitizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RawPersistencePolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RawPersistencePolicyResolver.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RawSourceType.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RawStorageMode.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/ReceiptPersistencePayload.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RedactionSanitizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RetentionRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/RetentionTarget.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadata.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/SensitiveHashingService.kt`

### Data privacy implementations
Review:
- `app/src/main/java/com/yourname/expensetracker/data/privacy/AtRestEncryptionService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadPolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadRedactor.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultSensitiveHashingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt`

### Security / keys / encryption
Review:
- `app/src/main/java/com/yourname/expensetracker/data/security/SecureKeyStorage.kt`
- any bank token / credential cipher classes:
  - search `rg -n "Cipher|KeyStore|AES|GCM|token|secret|apiKey|Authorization|Bearer"`

### AI platform
Inventory every AI path:
- `app/src/main/java/com/yourname/expensetracker/domain/ai/**`
- `app/src/main/java/com/yourname/expensetracker/data/ai/**`
- `app/src/main/java/com/yourname/expensetracker/di/AiModule.kt`
- `AiSettingsRepository`
- `AiSettingsRepositoryImpl`
- `AiPolicy`
- `AiPolicyImpl`
- `AiCapabilityRouter`
- `DefaultAiCapabilityRouter`
- `HybridRouter`
- `CloudProviderConnectionTester`
- `OkHttpCloudProviderConnectionTester`
- `CloudReceiptItemCategorizationService`
- `CloudWarrantyExtractionService`
- `HybridDashboardBriefingService`
- `HybridReviewExplanationService`
- `SmartReceiptAssistService`
- `HybridCategorizationAssistService`
- `HybridDedupeJudgeService`
- `HybridQueryInterpretationService`
- `HybridReceiptItemCategorizationService`
- any Gemini/OpenAI/HTTP prompt sender.

Search:
- `rg -n "prompt|rawText|rawPrompt|cloud|Gemini|OpenAI|OkHttp|RequestBody|PreparedCloudPayload|CloudPayloadRedactor|CloudPayloadPolicy|PrivacyGate" app/src/main/java/com/yourname/expensetracker`

### Notification capture / raw notification
Include P1/P3 cross-pipeline files:
- notification listener service,
- notification parser registry,
- raw notification repository/DAO/entity,
- pending review entity/DAO,
- notification intake worker,
- `NotificationCaptureGate`,
- `NotificationPersistencePayload`,
- `RawContentSanitizer`.

Search:
- `rg -n "NotificationCaptureGate|RawNotification|rawContent|notificationText|extrasJson|PendingReview|NotificationIntake"`

### Receipt / OCR
Include:
- receipt lifecycle coordinator,
- OCR capture/parsing classes,
- scanned receipt entity/DAO,
- receipt repository,
- raw OCR persistence points,
- `ReceiptPersistencePayload`,
- `RawContentSanitizer`,
- receipt cloud upload/categorization paths.

Search:
- `rg -n "rawOcr|OCR|Ocr|ScannedReceipt|ReceiptPersistencePayload|ReceiptImage|receipt.*cloud"`

### Email ingestion
Include:
- email receipt entity/DAO,
- email source/body persistence,
- email parsers,
- email ingestion workers/services,
- `EmailReceiptPersistencePayload`.

Search:
- `rg -n "EmailReceipt|email.*body|rawEmail|EmailReceiptPersistencePayload|imap|gmail|mail"`

### Bank integration
Include:
- bank statement import,
- bank transaction import,
- bank raw text / merchant persistence,
- bank AI suggestion path,
- `BankTransactionPersistencePayload`,
- any `suggestFromText(prompt)` or similar raw text path,
- token storage/cipher.

Search:
- `rg -n "Bank|Statement|bank.*prompt|suggestFromText|BankTransactionPersistencePayload|rawStatement|merchant"`

### Location/geocoding
Include:
- geocoding services,
- location backfill worker,
- GPS providers,
- Overpass provider,
- map ViewModel/screen,
- location privacy gates.

Search:
- `rg -n "Geocoding|Geocoder|Location|GPS|Overpass|Nearby|latitude|longitude|EXTERNAL_GEOCODING|DEVICE_GPS|BACKGROUND_LOCATION"`

### Export / backup
Include:
- export repositories,
- accounting export,
- database backup repository,
- raw backup/export path,
- export privacy gate,
- anonymizer/redactor,
- CSV sanitizer.

Search:
- `rg -n "RAWBACKUP|RAW_DATABASE|exportDatabase|BackupPrivacyGate|ExportPrivacyGate|ExportAnonymizer|CsvCellSanitizer|debugDataPersistence"`

### Retention / workers
Include:
- `DataRetentionWorker`,
- `PrivacyRuntimeWorkerPolicy`,
- `WorkerRegistry`,
- `WorkerExecutionGuard`,
- `WorkerRunLogger`,
- `WorkerRunContext`,
- `WorkerSpec`,
- all workers cancelled/rescheduled by privacy changes:
  - AI daily briefing,
  - location backfill,
  - data retention,
  - notification capture/intake if applicable.

Search:
- `rg -n "PrivacyRuntimeWorkerPolicy|data_retention|ai_daily_briefing|location_backfill|WorkerExecutionGuard|runGuarded|cancelUniqueWork|reschedule"`

### DAOs / Room entities
Inventory all relevant DAOs/entities:
- `PrivacyAuditDao.kt`
- `PrivacyAuditEvent.kt`
- `RawNotificationDao.kt`
- `RawNotification.kt`
- `ScannedReceiptDao.kt`
- `ScannedReceipt.kt`
- `EmailReceiptDao.kt`
- email receipt entities,
- `AiArtifactDao.kt`
- `AiArtifact.kt`
- `AiChatSessionDao.kt`
- `AiChatMessageDao.kt`
- `NotificationIntakeDao.kt`
- `PendingReviewDao.kt`
- `PipelineDiagnosticEventDao.kt`
- `BackgroundJobRunDao.kt`
- `BankStatementImportItemDao.kt`
- `BankStatementImportRunDao.kt`
- any schema/migrations touching raw/privacy/AI/diagnostics tables,
- `AppDatabase.kt`.

### Hilt modules
Review modules that provide/bind:
- `PrivacyModule.kt`
- `SecurityModule.kt`
- `AiModule.kt`
- `WorkerModule.kt`
- `RetentionModule.kt`
- `DiagnosticsModule.kt`
- `ServiceModule.kt`
- `NetworkModule.kt`
- `BackupRepositoryModule.kt`
- `ExportModule.kt`
- `DaoModule.kt`
- `DatabaseModule.kt`
- `DispatchersModule.kt`
- `TimeModule.kt`
- email/location/bank modules if present.

Verify actual runtime bindings, not just class existence.

### UI / ViewModels
If privacy or AI reaches UI, include:
- `PrivacySettingsScreen`
- `PrivacySettingsViewModel`
- `PrivacyBlockedCard`
- `AiSettingsViewModel`
- `AssistantViewModel`
- `AssistantSheet`
- `SpendingMapViewModel`
- `SpendingMapScreen`
- `ExportOptionsViewModel`
- `ExportOptionsScreen`
- `BackupRestoreViewModel`
- debug screens exposing raw data,
- route/navigation entries.

Search:
- `rg -n "PrivacyBlocked|PrivacySettings|AiSettings|Assistant|SpendingMap|ExportOptions|BackupRestore|RawExport|Debug" app/src/main/java/com/yourname/expensetracker/ui`

### Tests
Search the whole repo:
- `rg -n "Privacy|Retention|Sanitizer|Redaction|CloudPayload|RawStorage|PreparedCloudPayload|Audit|SafePrivacy|Sensitive|AiSettings|PrivacyGate|PrivacyBlocked|DataRetention|ExportPrivacy|LocationPrivacy" app/src/test app/src/androidTest`

Known likely tests to look for:
- privacy gate golden tests,
- privacy behavioral regression tests,
- do-not-store tests,
- privacy contract tests,
- capability handling policy tests,
- retention worker tests,
- sanitizer tests,
- redaction detector tests,
- cloud payload policy tests,
- debug/release export gating tests,
- UI ViewModel denied-state tests.

Do not stop at known names. Search the entire repo.

## 5. Code-reading rules

Mandatory:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Check whether public methods bypass privacy gates or redactors.
- Check whether tests assert real privacy invariants, not only happy-path construction.
- If a tracker says fixed/open/TODO, validate against code at this SHA.
- Treat privacy failures as high severity even when they are edge cases.
- Treat “redacted eventually by retention” as insufficient if raw data is written first and policy says write-time redaction/drop.
- Treat any fail-open behavior as a bug unless explicitly documented and tested.
- Do not assume a class named “Policy” is actually used by cloud/network callers; trace calls into HTTP request creation.
- Do not assume debug-only code is safe; verify `BuildConfig.DEBUG` or equivalent release exclusion.

Use searches like:
- `rg -n "PrivacyGate|PrivacyCapability|PrivacyDecision|PrivacyBlocked|FailClosed|NotApplicable|Allowed|Denied"`
- `rg -n "PreparedCloudPayload|CloudPayloadRedactor|CloudPayloadPolicy|EffectiveCloudAiPolicy|redactBeforeCloud"`
- `rg -n "prompt|rawText|rawPrompt|ocr|OCR|notification text|email body|statement text"`
- `rg -n "RequestBody|OkHttp|Gemini|OpenAI|cloud|upload|image"`
- `rg -n "RawStorageMode|RawPersistencePolicy|RawContentSanitizer|STORE_RAW|STORE_REDACTED|DO_NOT_STORE"`
- `rg -n "PrivacyAuditContext|SafePrivacyMetadata|context: Map|Map<String|JSONObject|metadata"`
- `rg -n "DataRetentionWorker|RetentionRegistry|RetentionTarget|purge|checkpoint|CancellationException"`
- `rg -n "debugDataPersistence|RAWBACKUP_EXPORT|exportDatabase|raw export|BuildConfig.DEBUG"`
- `rg -n "ExternalGeocoding|Overpass|LocationPrivacyGate|DEVICE_GPS|BACKGROUND_LOCATION"`
- `rg -n "catch \\(e: Exception\\)|catch \\(t: Throwable\\)|CancellationException"`
- `rg -n "Timber\\.|Log\\.|println|OperationRun|BackgroundJobRun|PipelineDiagnosticEvent"`
- `rg -n "insert\\(|update\\(|delete\\(" app/src/main/java/com/yourname/expensetracker/data`

## 6. Universal contracts to verify

Audit these for P8:

1. Restore/write barrier:
   - DB writes by privacy/retention/audit paths respect write barriers where applicable,
   - retention worker does not write during restore/backup if not allowed,
   - privacy settings changes during restore do not create inconsistent worker state,
   - raw export/backup privacy gates align with P7 maintenance.

2. Worker guard and worker run logging:
   - `DataRetentionWorker` uses `WorkerExecutionGuard`,
   - AI/location/retention workers are cancelled/rescheduled on privacy setting changes,
   - worker runs are logged with success/skip/retry/failure,
   - cancellation is propagated.

3. Privacy/redaction/raw-storage policy:
   - central contract for P8,
   - no raw PII stored when policy says redacted/drop,
   - no raw PII sent to cloud,
   - no raw PII logged to release logs or durable diagnostics,
   - fail closed on policy uncertainty.

4. Money/currency normalization:
   - P8 does not own money math, but AI prompts/exports/diagnostics may contain amounts.
   - financial totals/amounts should not appear in release diagnostics or audit context unless explicitly allowed/sanitized.

5. Transaction lifecycle ownership:
   - notification/receipt/email/bank-created expenses still use legal transaction lifecycle,
   - privacy sanitization must happen before raw intake writes, without bypassing transaction creation rules.

6. Receipt lifecycle/link ownership:
   - receipt OCR and images respect raw storage and cloud-upload policies,
   - receipt-created expenses do not leak raw OCR in diagnostics.

7. Recurring planned/actual reconciliation:
   - mostly not applicable,
   - but AI/diagnostics/export should not leak recurring bill names or raw reminder text.

8. Diagnostics/drop reasons/events:
   - privacy denials produce user-visible and/or diagnostic-safe reasons,
   - durable diagnostics redact exception messages and metadata,
   - audit events are semantic and not noisy,
   - audit context is typed/sanitized.

9. Import/export schema/roundtrip:
   - privacy audit records and settings are preserved or intentionally excluded,
   - redacted exports do not include raw PII,
   - raw backup/export is gated and debug-safe,
   - export/import cannot reintroduce raw content against settings.

10. DAO conflict handling and timestamps:
   - privacy audit timestamps valid,
   - purge timestamps valid,
   - retention updates are idempotent,
   - per-target purge failures do not corrupt partial state,
   - checkpoint state does not permanently skip failed targets.

## 7. P8-specific invariants to audit

### Privacy settings and AI settings
Check:
- `PrivacySettings` and `AiSettings` cannot contradict each other for cloud usage.
- `EffectiveCloudAiPolicy` is the authoritative source if present.
- All cloud AI calls use the effective policy.
- Settings update is atomic under concurrent updates.
- DataStore corruption fails closed.
- First-run defaults are not confused with corruption defaults.
- Disabling a privacy feature cancels active/scheduled workers.
- Enabling a feature reschedules only allowed worker specs.
- `data_retention` remains allowed to run if it is required for cleanup.

### Gate semantics
Check:
- every `PrivacyCapability` has an explicit handling policy,
- unsupported capability returns `NotApplicable`, not `Allowed`,
- composite gate fail-closes if no gate handles a gated capability,
- final decision is logged once,
- `FailClosed` blocks execution,
- `requireAllowed` or equivalents check the specific capability, not just a global toggle,
- gate context is typed/sanitized.

### Cloud payload safety
Check:
- every outbound cloud call receives `PreparedCloudPayload`,
- raw prompt strings are not sent directly,
- bank statement text path is redacted,
- receipt image upload is gated separately,
- cloud image/text payloads include purpose/capability,
- redaction cannot be bypassed by a convenience overload,
- exception paths do not log prompts/responses.

### Raw storage modes
Check:
- `DO_NOT_STORE` drops raw fields at write time,
- `STORE_REDACTED` writes redacted fields at write time,
- `STORE_RAW` is only allowed when setting permits,
- null and empty raw values are distinguishable where semantics matter,
- raw notification/OCR/email/bank fields are not stored first and purged later,
- write APIs accept structured sanitized payloads, not raw strings.

### Retention worker
Check:
- all intended retention targets are registered,
- target list is deterministic,
- checkpoint/resume is correct after crash,
- failed target is retried later and not permanently skipped,
- per-target failure is reported,
- worker run result semantics match project retry policy,
- `CancellationException` is rethrown,
- guard checkpoints are used inside long loops,
- purge queries are paginated and bounded,
- audit events are sanitized.

### Audit and durable diagnostics
Check:
- no raw `Map<String, String>` audit context with caller-provided values,
- `SafePrivacyMetadata` allowlist is enforced,
- prompt/rawText/token/path/account/card/IBAN values cannot be inserted,
- diagnostics sanitize exception messages,
- Timber/Log calls do not leak release-sensitive data,
- debug screens are release-inaccessible.

### Sanitizers/redaction
Check:
- Cloud PII sanitizer catches common PII:
  - emails,
  - phone numbers,
  - IBANs,
  - card numbers,
  - Greek AFM/tax IDs if relevant,
  - tokens/API keys,
  - addresses/locations where possible,
- merchant-line regex does not over-redact normal text,
- truncation markers count as redaction when relevant,
- redaction detector cannot miss shortened fields,
- null vs empty OCR/raw text behavior is preserved.

### Export/backup raw data
Check:
- raw DB export is debug-only or fully gated,
- `RAWBACKUP_EXPORT` is denied unless explicitly allowed by debug/privacy gate,
- normal expense export does not request raw backup capability,
- redacted export removes sensitive fields and formula-injection risks,
- encrypted backup checks privacy gate,
- backup privacy mode aligns with P7.

### Location privacy
Check:
- every external geocoding provider checks privacy before network call,
- Overpass calls are gated,
- background backfill worker is cancelled when disabled,
- device GPS is gated,
- UI shows denied reason.

### Privacy denied UX
Check:
- ViewModels expose typed `PrivacyBlocked` or equivalent,
- UI does not silently no-op on denied state,
- error strings do not leak raw data,
- user receives actionable explanation and path to settings where appropriate.

## 8. Known P8 issue set to validate

Read P8 consolidated issue doc and implementation plan, then validate each against code.

Old issues to validate:
- `P8-P1-01`: privacy setting changes should stop/cancel active workers.
- `P8-P1-02`: `PrivacySettings` and `AiSettings` can disagree.
- `P8-P1-03`: audit logging noisy / semantically imprecise.
- `P8-P1-04`: audit context stores caller-provided sensitive data.
- `P8-P1-05`: raw notification/OCR/email data stored before later purge.
- `P8-P1-06`: retention worker scope incomplete.
- `P8-P1-07`: bank-statement cloud text path sends raw prompt.
- `P8-P1-08`: redaction not formal purpose-aware payload contract.
- `P8-P1-09`: notification privacy gate too late / runtime state not cached.
- `P8-P1-10`: geocoding/location gate coverage not statically guaranteed.
- `P8-P1-11`: raw backup/export remains reachable.
- `P8-P1-12`: denied privacy states not consistently visible.

New issues to validate:
- `NEW-P8-001`: settings update TOCTOU race.
- `NEW-P8-002`: retention worker lacks per-target checkpoint.
- `NEW-P8-003`: merchant-line regex over-matches.
- `NEW-P8-004`: cloud PII sanitizer missing patterns.
- `NEW-P8-005`: `requireAllowed()` ignores specific capability.
- `NEW-P8-006`: retention worker swallows purge failures.
- `NEW-P8-007`: `sanitizeRawOcr` conflates null with empty.
- `NEW-P8-008`: redaction detector misses truncation.

Important:
- Current code may already contain fixes that the tracker does not reflect.
- If code is fixed but tracker says open, report tracker drift.
- If code contains a class but callers do not use it, issue remains open/partial.
- If a design contract exists but has no tests, classify risk appropriately.
- If code and architecture disagree, report doc/code drift.

## 9. Review dimensions

Check:
- correctness,
- privacy fail-closed behavior,
- AI cloud redaction,
- raw PII storage,
- raw PII logging,
- audit semantics,
- settings consistency,
- data-retention completeness,
- worker guard/run logging,
- cancellation handling,
- coroutine races,
- WorkManager retry/idempotency,
- restore/export safety,
- direct DAO writes,
- lifecycle bypasses,
- schema/migration compatibility,
- Hilt binding correctness,
- UI denied-state consistency,
- diagnostics coverage,
- test coverage,
- performance risks,
- security risks,
- accessibility of privacy-denied UI.

## 10. Required output format

Produce this exact structure:

# Pipeline 8 Review — Privacy / AI / Redaction

## 1. Pipeline summary
- What P8 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |

Include:
- entry points,
- privacy gates,
- settings repositories,
- AI services,
- redactors/sanitizers,
- raw persistence policies,
- repositories,
- DAOs,
- Room entities,
- workers,
- export/backup paths,
- location providers,
- Hilt modules,
- ViewModels/UI,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow privacy/security/AI architecture docs?
- Does code follow `SENSITIVE_DIAGNOSTICS_POLICY.md`?
- Does UI follow `PRIVACY_UI_ARCHITECTURE.md`?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- privacy settings update,
- cloud AI call,
- notification capture write,
- OCR receipt write,
- email receipt write,
- bank statement AI path,
- retention worker,
- privacy audit logging,
- export/backup gate,
- location/geocoding call,
- UI privacy denial.

## 5. Issue table
Use columns:
| ID | Severity P0/P1/P2/P3 | Status bug/partial/TODO/fixed/design | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |

Every finding must have concrete evidence:
- file path,
- method name,
- relevant condition,
- why it violates contract.

## 6. Universal contract audit
Subsections:
- restore barrier,
- privacy/redaction/raw storage,
- lifecycle ownership,
- worker guard/run logging,
- money/financial-data redaction,
- diagnostics/events,
- import/export/backup,
- DAO conflict/timestamps.

For each, verdict:
- PASS,
- FAIL,
- PARTIAL,
- NOT APPLICABLE,
with evidence.

## 7. P8 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |

Include all old and new P8 issues from `PIPELINE_8_CONSOLIDATED_ISSUES.md`.

## 8. Test coverage review
- Existing tests found.
- What each test proves.
- Missing tests.
- Weak tests that do not assert the important invariant.

## 9. Test plan
Include:
- unit tests,
- integration tests,
- regression tests,
- instrumentation/UI tests if needed,
- manual validation scenarios.

## 10. Optional deliverables
Include at least one:
- Mermaid/text data-flow diagram,
- call graph,
- dependency map,
- legal privacy-gate table,
- raw-storage policy table,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P8 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

For P8:
- Any raw PII sent to cloud without gate/redaction is at least P0/P1 depending exposure.
- Any raw PII stored against explicit do-not-store policy is P0/P1.
- Any fail-open privacy gate is at least P1.
- Any durable diagnostic with raw OCR/notification/prompt/token is at least P1 and often P0.

## 12. Completion criteria

The review is not complete until:
- P8 issue doc was read,
- master/universal trackers were read,
- architecture/privacy docs were checked,
- all relevant source files were inventoried,
- key callers/callees were traced,
- every cloud/network path was checked for gate/redaction,
- every raw storage path was checked for policy enforcement,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P8 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent specializing in privacy, AI payload redaction, secure diagnostics, WorkManager, DataStore concurrency, and test-driven security fixes.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P8 — Privacy / AI / Redaction

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P8 issues.
Do not perform broad refactors.
Preserve architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_8_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_8_IMPLEMENTATION_PLAN.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`
- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md`
- `docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/export docs if touching backup/export/import.

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually wrong.
If only docs are stale, report docs drift instead of changing code.

## 3. Implementation constraints

Follow P8 legal paths:
- all privacy decisions through `PrivacyGate` / `CompositePrivacyGate`,
- all cloud AI payloads through `CloudPayloadPolicy` + `CloudPayloadRedactor` / `PreparedCloudPayload`,
- all raw persistence through `RawPersistencePolicyResolver` / source-specific persistence payloads,
- all audit logging through `PrivacyAuditLogger` with `PrivacyAuditContext` / `SafePrivacyMetadata`,
- all retention work through `DataRetentionWorker` + `RetentionRegistry` + `WorkerExecutionGuard`,
- raw database/export through `ExportPrivacyGate` / `BackupPrivacyGate`,
- UI privacy denial through typed `PrivacyBlocked` where applicable.

General rules:
- Keep changes minimal and targeted.
- Add or update tests for every fixed issue.
- Do not introduce schema migration unless explicitly required and approved.
- Do not mask `CancellationException`.
- Do not send raw prompts/text/images to cloud.
- Do not write raw sensitive fields before sanitization if policy says redact/drop.
- Do not log sensitive values in release logs or durable diagnostics.
- Do not add ad-hoc stringly typed audit context if typed context exists.
- Do not make privacy gates fail open.
- Do not use `BuildConfig.DEBUG` as the only control for user privacy if an explicit privacy gate exists.

## 4. Candidate P8 fix areas

Validate first, then fix only if still broken.

### P8-PR1 — Critical settings, gate, and retention bugs
Candidate issues:
- `NEW-P8-001`: settings update race.
- `NEW-P8-002`: retention worker checkpoint gap.
- `NEW-P8-005`: capability-specific gate check missing.
- `NEW-P8-006`: retention purge failure swallowed.
- cancellation propagation in worker loops.

Implementation intent:
1. Make `updateSettings()` atomic:
   - serialize read-modify-write via `Mutex` or DataStore transactional edit,
   - compare persisted old/new settings for worker policy,
   - test concurrent updates.
2. Retention worker:
   - use deterministic target order,
   - checkpoint per target,
   - resume failed/incomplete target safely,
   - do not permanently skip failed targets,
   - report partial failures,
   - rethrow `CancellationException`,
   - use `WorkerExecutionGuard` checkpoints.
3. Gate check:
   - `requireAllowed()` or equivalent must check the requested `PrivacyCapability`,
   - fail closed for unhandled gated capabilities.

Required tests:
- `concurrent_settings_updates_preserve_all_changes`
- `privacy_settings_corruption_uses_fail_closed_defaults`
- `disabled_privacy_toggle_cancels_mapped_workers`
- `enabled_privacy_toggle_reschedules_via_worker_registry`
- `retention_worker_resumes_from_checkpoint`
- `retention_failed_target_retried_next_run`
- `retention_worker_rethrows_cancellation`
- `gate_checks_specific_capability`
- `unhandled_gated_capability_fails_closed`

### P8-PR2 — Audit and denied UX
Candidate issues:
- `P8-P1-03`: audit decisions noisy/imprecise.
- `P8-P1-04`: audit context can contain sensitive values.
- `P8-P1-12`: privacy-denied states not visible.

Implementation intent:
1. Audit:
   - log only final decision per privacy request,
   - include structured reason and capability,
   - avoid intermediate gate noise unless debug-only.
2. Audit context:
   - replace raw caller `Map` with `PrivacyAuditContext` / `SafePrivacyMetadata`,
   - allowlist keys,
   - hash where needed,
   - reject or redact prompt/rawText/token/path/account/card/IBAN values.
3. UI:
   - expose typed `PrivacyBlocked`,
   - render `PrivacyBlockedCard` or equivalent,
   - ensure denied actions do not silently no-op.

Required tests:
- `audit_logs_one_final_decision`
- `audit_context_rejects_raw_prompt`
- `audit_context_redacts_token_path_iban`
- `privacy_denied_maps_to_typed_blocked_state`
- `assistant_denied_shows_privacy_blocked`
- `export_denied_shows_reason`
- `location_denied_shows_reason`

### P8-PR3 — Cloud payload and raw-storage contract
Candidate issues:
- `P8-P1-02`: settings disagreement.
- `P8-P1-05`: raw data write-before-purge gaps.
- `P8-P1-07`: bank cloud path sends raw prompt.
- `P8-P1-08`: no formal payload contract.
- `P8-P1-09`: notification gate too late.
- `P8-P1-10`: geocoding gate coverage.
- `P8-P1-11`: raw export reachable.

Implementation intent:
1. Cloud AI:
   - enforce `EffectiveCloudAiPolicy`,
   - require `PreparedCloudPayload`,
   - remove raw-string cloud overloads or mark internal/test-only,
   - gate per capability and purpose.
2. Raw storage:
   - route notification/OCR/email/bank persistence through structured payloads,
   - apply `RawStorageMode` at write time,
   - distinguish null vs empty,
   - preserve parsed non-sensitive data.
3. Notification capture:
   - gate before text extraction/persistence where possible,
   - stop active services or intake workers when disabled.
4. Bank statement AI:
   - redact statement text before cloud,
   - add capability-specific audit.
5. Geocoding:
   - every external provider checks privacy before network call.
6. Export/backup:
   - raw DB export debug-only and privacy-gated,
   - normal export uses normal export capability, not raw backup capability.

Required tests:
- `cloud_call_requires_prepared_payload`
- `bank_statement_cloud_prompt_is_redacted`
- `raw_prompt_cloud_overload_unreachable_or_test_only`
- `do_not_store_notification_raw_fields`
- `store_redacted_ocr_writes_redacted_not_raw`
- `email_body_not_stored_when_policy_denies`
- `notification_capture_disabled_drops_before_persist`
- `external_geocoding_denied_prevents_network_call`
- `raw_export_denied_in_release_or_without_debug_permission`
- `normal_export_does_not_use_rawbackup_capability`

### P8-PR4 — Sanitizer and redaction quality
Candidate issues:
- `NEW-P8-003`: merchant-line regex over-matches.
- `NEW-P8-004`: missing PII patterns.
- `NEW-P8-007`: null vs empty raw OCR behavior.
- `NEW-P8-008`: truncation detection gap.

Implementation intent:
1. Narrow merchant-line regex:
   - avoid matching normal prose,
   - require merchant-context evidence.
2. Add/verify PII patterns:
   - email,
   - phone,
   - IBAN,
   - card-like numbers,
   - Greek AFM/tax IDs if relevant,
   - tokens/API keys,
   - file paths/URLs where policy requires.
3. Null vs empty:
   - preserve distinction between not captured, intentionally empty, and purged/redacted.
4. Redaction detector:
   - detect truncation markers and length-capped fields.

Required tests:
- `merchant_regex_does_not_match_normal_text`
- `sanitizer_redacts_email_phone_iban_card_afm`
- `sanitizer_redacts_tokens_and_paths`
- `raw_ocr_null_distinct_from_empty`
- `redaction_detector_flags_truncated_fields`
- `redaction_detector_accepts_expected_markers`

### P8-PR5 — Cross-pipeline privacy hardening
Candidate areas:
- P1/P3 notification capture,
- receipt OCR,
- P10 bank import,
- P11 email receipts,
- P7 backup/export,
- P12 export/import,
- diagnostics.

Implementation intent:
1. Add privacy-gate tests at integration boundaries.
2. Add golden tests for no raw PII in durable diagnostics.
3. Add release/debug tests for debug screens/raw export.
4. Ensure import/export preserves privacy audit/settings intentionally.

Required tests:
- `durable_diagnostics_do_not_store_raw_ocr_notification_prompt`
- `operation_run_error_message_is_sanitized`
- `background_job_error_message_is_sanitized`
- `debug_screen_not_registered_in_release`
- `backup_redacted_mode_excludes_raw_content`
- `export_redacted_mode_excludes_sensitive_fields`
- `privacy_audit_survives_backup_restore_or_is_documented`

## 5. Universal checks before/after every fix

Verify:
- privacy gates fail closed,
- raw storage policy enforced before write,
- cloud calls are redacted and gated,
- retention worker uses guard and propagates cancellation,
- no raw PII in logs/diagnostics,
- no unsafe audit context,
- privacy-denied UI is visible,
- raw export is gated,
- Hilt bindings point to intended implementations,
- tests cover actual call path, not only helpers.

## 6. Required validation commands

Run at minimum:
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Retention*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Sanitizer*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Redaction*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CloudPayload*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CloudPii*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Ai*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Notification*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Ocr*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Export*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Backup*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Location*" --stacktrace
./gradlew :app:check --stacktrace
```

If a command cannot run, report:
- exact command,
- failure reason,
- whether failure is related to P8,
- what still needs manual validation.

## 7. Required output

Produce:

## Summary
- Issues fixed.
- Issues confirmed already fixed.
- Issues deferred/design-only.
- Issues not touched and why.

## Changed files
| File | Change | Issue IDs | Tests |

## Issue reconciliation
| ID | Before | After | Evidence | Tests |

## Test results
- Commands run.
- Pass/fail.
- Relevant logs.

## Remaining risks
- Highest risk.
- Cross-pipeline impacts.
- Any migration/design follow-up.

## Commit plan
Split into safe PRs:
1. settings/gate/retention correctness,
2. audit and denied UX,
3. cloud payload and raw-storage contract,
4. sanitizer/redaction quality,
5. cross-pipeline privacy hardening,
6. docs/tracker sync.
```

---

## Prompt C — P8 Final Validation / Fixed-Claims Audit Prompt

Use this after fixes land.

```text
You are a senior validation/debugger agent specializing in privacy/security and AI payload safety.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Target:
Use the current working branch/commit provided by the user.
Baseline context commit:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P8 — Privacy / AI / Redaction

Mode:
Validation of already-fixed claims.
Do not implement new fixes.
Verify whether P8 can be marked GREEN/YELLOW/RED.

## 2. Required reading

Read:
- P8 consolidated issue doc,
- P8 implementation plan,
- master tracker,
- universal tracker,
- all architecture docs listed in Prompt A,
- privacy UI architecture,
- sensitive diagnostics policy,
- changed source files,
- changed tests,
- migration/schema files if touched,
- Hilt modules,
- changed UI files,
- changed workers,
- changed AI/network callers.

Do not trust PR descriptions or comments.
Validate against code and tests.

## 3. Claims to validate

Validate:
- all P8 old issues marked fixed,
- all P8 new issues marked fixed,
- all universal privacy/raw-storage fixes that affect P8,
- all newly added tests,
- no new bypasses introduced,
- no new raw PII storage/logging/cloud paths introduced.

Specific P8 claims:
- settings update is atomic,
- settings corruption fails closed,
- disabling toggles cancels affected workers,
- enabling toggles reschedules only via `WorkerRegistry`/specs,
- `PrivacySettings` and `AiSettings` cannot contradict cloud policy,
- `EffectiveCloudAiPolicy` is used by cloud callers,
- composite privacy gate fails closed for unhandled capabilities,
- every capability has explicit handling policy,
- final gate decisions are audited once,
- audit context is typed/sanitized,
- no raw prompt/rawText/token/path/card/IBAN in audit events,
- every cloud call uses `PreparedCloudPayload`,
- bank statement cloud text is redacted,
- receipt image upload is separately gated,
- raw notification/OCR/email/bank content is dropped/redacted at write time according to policy,
- null vs empty raw content is preserved where required,
- retention worker has deterministic per-target checkpointing,
- failed retention targets are not silently swallowed,
- retention worker uses worker guard/run logging,
- retention worker rethrows `CancellationException`,
- sanitizer catches common PII patterns,
- merchant regex does not over-redact normal text,
- truncation detection works,
- raw export is debug-only/gated,
- normal export does not request raw backup capability,
- geocoding/location providers are all gated,
- denied privacy states are visible in UI,
- durable diagnostics sanitize sensitive data,
- debug screens/raw viewers are release-inaccessible.

## 4. Required validation steps

1. Build exact file inventory.
2. Trace runtime flows.
3. Compare code to `LEGAL_PATHS.md`.
4. Compare code to privacy UI architecture.
5. Compare code to sensitive diagnostics policy.
6. Run targeted tests.
7. Review test assertions for real coverage.
8. Check direct DAO writes.
9. Check worker guard/run logging.
10. Check raw storage policy at write sites.
11. Check every cloud/network path.
12. Check diagnostics/logging.
13. Check Hilt bindings.
14. Check backup/export/import privacy overlap.
15. Check cross-pipeline impacts with notifications, receipts, bank, email, export, location.

## 5. Required output

Produce:

# P8 Fixed-Claims Validation

## 1. Verdict
GREEN / YELLOW / RED

## 2. Claims table
| Claim | Source doc/PR | Validated? | Evidence | Remaining risk |

## 3. Regression search
| Area | Search/check performed | Result |

Include at least:
- raw prompt cloud paths,
- raw OCR/notification/email/bank storage,
- audit raw context,
- `context: Map`,
- `RequestBody`,
- `OkHttp`,
- `Log`/`Timber`,
- `CancellationException`,
- raw export,
- geocoding network calls,
- unhandled privacy capabilities,
- Hilt bindings.

## 4. Test validation
| Test | What it proves | Weakness/gap |

## 5. Contract audit
- restore/write barrier,
- worker guard/run logging,
- privacy gate fail-closed,
- cloud payload redaction,
- raw storage policy,
- diagnostics/privacy,
- import/export/backup,
- UI denied state,
- DAO timestamps/idempotency.

## 6. Remaining issues
| ID | Severity | Status | Evidence | Required next action |

## 7. Production safety
- Is P8 production-safe?
- Highest-risk issue.
- Required fix before GREEN.
```