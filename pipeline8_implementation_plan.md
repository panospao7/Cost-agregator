# Pipeline 8 implementation plan — Privacy / AI / Redaction
Basis: HEAD `c424274` on **May 11, 2026**

## Goal
Move Pipeline 8 from **“substantially improved but partial”** to **“single-policy, fail-closed, retention-complete, and contract-proven.”**

## Highest-risk remaining gaps

1. **Fail-closed is modeled, but not uniformly enforced**
   - `CompositePrivacyGate` can return `FailClosed`
   - multiple callers only block on `Denied`
   - sampled cloud services and `NotificationCaptureService.processNotificationBypassDedupe()` still proceed on `FailClosed` / `else`

2. **PrivacySettings and AiSettings still drift**
   - `CloudAiPrivacyGate` still has TODO to use an effective resolver
   - hybrids still route from `AiSettingsRepository + AiCapabilityRouter`
   - some cloud services also read `allowCloudAi`/`redactBeforeCloud` directly

3. **Denied-state/result contract is inconsistent**
   - dashboard briefing returns `AiServiceResult.Failure(Disabled(...))`
   - categorization returns `null`
   - query interpretation returns `unsupported(...)`

4. **Raw-storage policy is incomplete**
   - `PrivacySettings.emailReceiptStorageMode` exists
   - `PrivacySettingsRepositoryImpl` does not persist/load it
   - `EmailReceiptIngestionService` still uses `rawOcrStorageMode`
   - raw `emailMessageId` still persists

5. **Retention scope is still incomplete**
   - `DataRetentionWorker` still has TODO for AI artifacts, chat, diagnostics, email sources

6. **Notification privacy ordering is still partial**
   - main posted path pre-gates correctly
   - refresh/bypass path still extracts text before privacy decision
   - settings observer failure still fails open

7. **Plaintext backup export is still reachable**
   - `BackupPrivacyGate` allows `RAWBACKUP_EXPORT` when encrypted backups are disabled

8. **No formal purpose-aware payload contract**
   - payload prep/redaction still lives inside provider-specific request builders

---

## Foundations to preserve
These are real improvements; harden them instead of redoing them:
- runtime worker cancellation in `PrivacySettingsRepositoryImpl.applyPrivacyChange()`
- `CompositePrivacyGate` final-audit ownership
- sanitized audit context in `PrivacyAuditLoggerImpl`
- provider self-gating is much better than before
- notification fast-path cached privacy denial exists
- raw storage modes + `RawContentSanitizer` exist

---

## Recommended PR order

## PR0 — Freeze the privacy contract + test skeleton
**Priority:** Critical

### Work
Create a short doc under `docs/` that defines:
- one source of truth for effective privacy policy
- required behavior for `Allowed / Denied / FailClosed / NotApplicable`
- which capabilities are:
  - cloud AI
  - local AI
  - notification capture
  - email raw storage
  - OCR raw storage
  - backup/export
  - geocoding
- retention obligations by artifact type

### Add empty tests
- `CompositePrivacyGateFailClosedTest`
- `EffectiveCloudAiPolicyResolverTest`
- `AiProviderDeniedStateContractTest`
- `NotificationCapturePrivacyOrderTest`
- `EmailPrivacyStorageModeTest`
- `DataRetentionWorkerScopeTest`
- `BackupPrivacyGatePolicyTest`
- `CloudPayloadPreparationContractTest`

### Done when
There is one explicit privacy contract before more refactor.

---

## PR1 — Hotfix fail-closed propagation everywhere
**Priority:** Critical  
**Files:**
- `CompositePrivacyGate.kt`
- `CloudDashboardBriefingService.kt`
- `CloudCategorizationAssistService.kt`
- `CloudReviewExplanationService.kt`
- `CloudDedupeJudgeService.kt`
- `CloudQueryInterpretationService.kt`
- `NotificationCaptureService.kt`
- any other direct `privacyGate.check(...)` callers

### Problem
Current callers often do:
- `if (decision is PrivacyDecision.Denied) ...`
- and treat everything else as proceed

That is wrong now that `FailClosed` exists.

### Changes
Add a tiny shared helper, e.g.:
- `PrivacyDecision.blocksExecution(): Boolean`
- or `PrivacyDecision.requireAllowed()`

Policy:
- `Allowed` -> proceed
- `Denied` -> block
- `FailClosed` -> block
- `NotApplicable` -> only acceptable internally in composite evaluation; caller-level behavior should not silently proceed unless explicitly intended

### Concrete fixes
- `NotificationCaptureService.processNotificationBypassDedupe()` must not proceed on `FailClosed`
- sampled cloud providers must block on `FailClosed`
- consider removing direct provider logging of denial if it duplicates final audit noise

### Done when
No privacy-gated execution path can continue after a gate exception/fail-closed result.

---

## PR2 — Introduce one effective privacy/AI policy resolver
**Priority:** Critical  
**Files:**
- `CloudAiPrivacyGate.kt`
- `PrivacySettings.kt`
- `PrivacySettingsRepositoryImpl.kt`
- `AiSettingsRepositoryImpl.kt`
- AI model/settings files
- `HybridCategorizationAssistService.kt`
- `HybridDashboardBriefingService.kt`
- `AiCapabilityRouter` and related route code

### Problem
Two settings systems still influence the same behavior:
- `PrivacySettings`
- `AiSettings`

### Changes
Create:
- `EffectiveCloudAiPolicyResolver`
or broader:
- `EffectivePrivacyPolicyResolver`

Output should include:
- `cloudAiAllowed`
- `onDeviceAiAllowed`
- `redactBeforeCloud`
- `receiptImageUploadAllowed`
- `bankStatementCloudAllowed`
- per-capability allow/deny
- denial reason / source
- preferred route policy

### Design recommendation
Keep underlying stores if needed, but make them implementation detail.  
All enforcement and routing should consume the **resolved policy**, not raw stores.

### Migrate
- `CloudAiPrivacyGate` to resolver output
- hybrids/router to resolver output
- providers stop reading `allowCloudAi` / `redactBeforeCloud` ad hoc

### Done when
If PrivacySettings and AiSettings disagree, behavior is still deterministic and uniform.

---

## PR3 — Standardize denied-state surfaces and cloud attempt semantics
**Priority:** High  
**Files:**
- cloud provider services
- shared AI result/error models
- hybrids/router layer
- diagnostics/audit helpers if needed

### Problem
Different services expose privacy denial differently:
- `Failure(Disabled)`
- `null`
- `unsupported(...)`

### Changes
Define one explicit denial surface:
- `AiServiceError.PrivacyDenied(...)`
or a shared outcome wrapper

For nullable interfaces like categorization:
- either widen interface to a richer result
- or keep nullable API but emit structured diagnostics + internal typed outcome before flattening

### Also add
A shared cloud-attempt event schema:
- capability
- provider
- model
- route
- decision
- reason
- redacted payload hash
- outcome
- correlationId

### Done when
Privacy denial means the same thing across all cloud AI features.

---

## PR4 — Finish raw-storage closure for email and retention
**Priority:** Critical  
**Files:**
- `PrivacySettings.kt`
- `PrivacySettingsRepositoryImpl.kt`
- `RawContentSanitizer.kt`
- `EmailReceiptIngestionService.kt`
- email entity/DAO files
- `DataRetentionWorker.kt`

### Concrete gaps to close
1. `emailReceiptStorageMode` exists in `PrivacySettings`, but repo does not persist/load it
2. email ingestion still uses `rawOcrStorageMode`
3. raw `emailMessageId` still persists
4. retention worker still ignores email/AI/debug artifacts

### Changes
1. Add missing DataStore key:
   - `EMAIL_RECEIPT_STORAGE_MODE`
   - and optionally `RAW_EMAIL_RETENTION_DAYS`
2. Make email ingestion use `emailReceiptStorageMode`, not OCR mode
3. Stop storing raw `emailMessageId`
   - replace with deterministic hash
   - dedupe by hash/fingerprint
4. Expand `RawContentSanitizer`
   - sender
   - subject
   - message body snippet
   - message-id hashing helper
5. Expand `DataRetentionWorker` scope to purge:
   - email receipt raw fields
   - AI artifacts
   - AI chat messages
   - debug/service diagnostics
6. Add `executionGuard.checkpoint(...)` inside retention loops if missing

### Done when
Email is a first-class participant in the privacy storage/retention contract.

---

## PR5 — Finish notification privacy ordering
**Priority:** Critical  
**Files:**
- `NotificationCaptureService.kt`
- maybe small extraction/gating helper

### Problem
Refresh/bypass path still extracts text before privacy approval, and observer failure defaults to allow.

### Changes
1. Create one metadata-only pre-extraction gate used by:
   - `onNotificationPosted()`
   - `processNotificationBypassDedupe()`
   - any refresh/rescan path
2. On settings observation failure:
   - fail closed for text extraction
   - do not set `capturePrivacyDenied = false`
3. In bypass path:
   - block on `Denied` and `FailClosed`
   - do not extract `NotificationTextParts` until privacy permits capture
4. Ensure logs before gate contain no raw user text

### Done when
No notification path reads user text before privacy approval.

---

## PR6 — Close backup/export and payload-preparation gaps
**Priority:** High  
**Files:**
- `BackupPrivacyGate.kt`
- backup/export entrypoints
- cloud provider request-building code
- query interpretation / bank statement cloud paths
- location/geocoding outbound wrappers

### Part A — plaintext backup export
Recommendation:
- disable `RAWBACKUP_EXPORT` in production entirely
- if legacy/plaintext export must exist, make it debug-only + explicit danger UX

### Part B — purpose-aware payload contract
Create a shared type like:
- `PreparedCloudPayload`
or
- `CloudPayloadPreparationResult`

It should contain:
- capability/purpose
- redaction mode used
- payload text/body
- payload hash
- attachment/image allowance
- audit metadata

Then migrate provider-specific builders to use it.

### Part C — audit coverage pass
Re-audit:
- bank-statement cloud parsing text path
- geocoding/network location paths
to ensure they all flow through the shared privacy preparation + gate model.

### Done when
No cloud payload is assembled ad hoc without a formal privacy-preparation contract.

---

## PR7 — Harden implemented fixes with tests + docs sync
**Priority:** Required for closure

### Tests to add
- `CompositePrivacyGateFailClosedTest`
- `CloudProviderFailClosedBlockingTest`
- `EffectiveCloudAiPolicyResolverTest`
- `AiProviderDeniedStateContractTest`
- `NotificationCapturePrivacyOrderTest`
- `NotificationPrivacyObserverFailureTest`
- `EmailStorageModePersistenceTest`
- `EmailMessageIdHashingTest`
- `DataRetentionWorkerScopeTest`
- `BackupPrivacyGatePolicyTest`
- `CloudPayloadPreparationContractTest`
- `LocationPrivacyCoverageTest`

### Minimum scenarios
1. gate throws -> composite returns `FailClosed` -> caller blocks
2. PrivacySettings vs AiSettings disagree -> resolved policy stays deterministic
3. categorization/dashboard/query/review/dedupe expose privacy denial consistently
4. email storage mode roundtrips through DataStore
5. raw `emailMessageId` is never persisted
6. retention purges AI/email/debug artifacts
7. refresh notification path does not extract text before gate
8. plaintext backup export is blocked in production
9. payload prep emits redacted/hash-only metadata consistently

### Docs cleanup
After tests pass:
- update Pipeline 8 tracker statuses
- note that `emailReceiptStorageMode` exists but required repository wiring was added in this pass
- document `FailClosed` caller contract explicitly

---

## Recommended execution order
1. PR0 contract + test skeleton
2. PR1 fail-closed hotfix
3. PR2 effective policy resolver
4. PR3 denied-state/result normalization
5. PR4 email/raw-storage/retention closure
6. PR5 notification privacy ordering
7. PR6 backup/export + payload contract + coverage audit
8. PR7 final tests + docs sync

---

## Closure criteria
I would only call Pipeline 8 clean/stable when all of these are true:

- no caller proceeds on `FailClosed`
- cloud AI routing/enforcement uses one resolved policy
- denied states are surfaced consistently
- email storage policy is persisted and enforced
- raw `emailMessageId` is gone
- retention covers AI/email/debug artifacts
- no notification path extracts text before gate approval
- plaintext backup export is unreachable in production
- payload preparation is centralized and purpose-aware
- tests prove the contract

## Sources
- Tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `PrivacySettings.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt
- `PrivacySettingsRepositoryImpl.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
- `AiSettingsRepositoryImpl.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt
- `CloudAiPrivacyGate.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt
- `CompositePrivacyGate.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt
- `PrivacyAuditLoggerImpl.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt
- `NotificationCaptureService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `DataRetentionWorker.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt
- `BackupPrivacyGate.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt
- `EmailReceiptIngestionService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `RawContentSanitizer.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawContentSanitizer.kt
- Sample cloud services/hybrids:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt

## Scope note
Static repo/doc review only; no Gradle or runtime execution.