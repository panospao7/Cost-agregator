# Cell P-08 Audit — Privacy, Retention, and Cloud AI

- Campaign: CA-2026-09-21
- Cell: P-08
- Date: 2026-09-21
- Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965 (`git rev-parse --short HEAD` = 37601232); `git diff --stat 37601232..HEAD -- app config scripts` was empty
- Auditor: direct astra session
- Mode: AUDIT, static only

## Scope and provenance

Coverage row: `docs/architecture/COVERAGE_MATRIX.md` P-08.
Legal-path anchors and segment entries will be extracted and checked below.
Known issue IDs (excluded unless clearly regressed): FRESH-P8-001..008.

## Coverage checklist

- [x] PrivacyGate and privacy decision types
- [x] CloudPayloadPolicy and redaction implementation
- [x] HybridRouter and cloud/on-device routing
- [x] DailyBriefingWorker and briefing providers
- [x] DataRetentionWorker and retention registry/targets
- [x] Cloud provider call sites and payload preparation
- [x] Worker guard/barrier/diagnostics paths
- [x] Tests and static guards relevant to privacy/cloud/retention
- [x] Legal-path and segment intent compared to pinned code
- [x] All 15 defect classes considered

## Extracted intent checked

- `LEGAL_PATHS.md` Privacy / Cloud AI requires `EffectiveCloudAiPolicy` -> `CloudPayloadPolicy.prepareText` / `prepareReceiptAssist` / `prepareBankStatementValidation` -> `PreparedCloudPayload` for every cloud provider, with `CompositePrivacyGate` final decision; FailClosed must stop execution.
- Raw storage intent requires `RawStorageMode`/`RawContentSanitizer`, ephemeral processing, and retention cleanup runnable independently of raw-retention capabilities.
- `CODEBASE_SEGMENTS.md` Segment 20 states `HybridRouter` consolidates six hybrid services; Segment 28 owns the cloud payload policy and typed privacy decisions; Segment 12/29 own guarded workers and retention.

## Files and paths covered

- `domain/privacy/PrivacyGate.kt`, `PrivacyDecision.kt`, `PrivacyBlocked.kt`, `CompositePrivacyGate.kt`, `CloudAiPrivacyGate.kt`, `EffectiveCloudAiPolicy.kt`, `CloudPayloadPolicy.kt`
- `data/privacy/DefaultCloudPayloadPolicy.kt`, `DefaultCloudPayloadRedactor.kt`, `DataRetentionWorker.kt`, `RetentionCheckpointStore.kt`
- `di/RetentionModule.kt` (target registration and purge paths), `di/PrivacyModule.kt`, `di/AiModule.kt`
- `domain/ai/HybridRouter.kt`; hybrid provider wrappers including `HybridQueryInterpretationService.kt`
- Cloud providers: `CloudQueryInterpretationService.kt`, `CloudDashboardBriefingService.kt`, `CloudDedupeJudgeService.kt`, `CloudReceiptAssistService.kt`, `CloudReviewExplanationService.kt`, plus policy-adopting provider call-site scans
- `data/ai/worker/DailyBriefingWorker.kt`; `WorkerExecutionGuard.kt` cancellation/permission semantics
- Tests/guards: `CloudProviderPreparedPayloadTest.kt`, `CloudQueryInterpretationServiceTest.kt`, `DataRetentionWorkerTest.kt`, `scripts/verify_privacy_boundaries.py`, `scripts/verify_cloud_payload_boundaries.py`, `scripts/verify_pii_logging_boundaries.py`, relevant allowlist
- Caller traces: `HybridQueryInterpretationService` -> `AiModule` binding -> `InterpretFinancialQueryUseCase`/assistant; `DailyBriefingWorker` via `WorkerRegistry`; cloud provider AI use cases and artifact persistence

## Defect-class coverage notes

Classes 1–6, 9–10, 12–15 were traced in the privacy gate, cloud provider, hybrid routing, and retention paths above. Classes 7–8 and 11 were checked for these files and produced no new P-08-specific issue. Known FRESH-P8-001..008 were excluded per cell instructions; retention targets now present in the pinned source were treated as baseline remediation, not re-reported.

## Findings

### CA-P08-001 | Composite privacy gate converts cancellation into FailClosed | defect class 5 (cancellation safety) | severity P1

**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt`, `CompositePrivacyGate.check`, lines 26–39: the `gate.check(...)` suspend call is wrapped in `catch (e: Exception)` and every exception is logged and converted to `PrivacyDecision.FailClosed(PRIVACY_GATE_FAILURE)`. `CancellationException` is an `Exception` and is not rethrown before this conversion.

**Impact path:** coroutine cancellation during a privacy-settings/gate read -> `CompositePrivacyGate` returns FailClosed instead of propagating cancellation -> cloud providers, `WorkerExecutionGuard`, UI gate callers, or retention-adjacent callers treat it as an ordinary privacy denial/failure and may write terminal diagnostics or skip work after cancellation. This violates the coroutine cancellation contract and can turn shutdown/timeout cancellation into durable work outcomes.

**Caller trace:** Hilt `PrivacyModule.providePrivacyGate` (lines 77–95) injects this composite into `Cloud*Service`, `WorkerExecutionGuard`, and other privacy callers; `WorkerExecutionGuard` invokes `privacyGate.check` from its guarded worker path.

**Existing tests/guards:** `PrivacyGuardTest`/`CloudAuditProviderProvenanceTest` cover gate composition and fail-closed behavior but no production `CompositePrivacyGate` cancellation test was found. `WorkerExecutionGuardTest` verifies cancellation at the worker guard, not cancellation thrown by a gate. Static privacy guards do not reject this catch pattern.

**Cross-cell impact:** P-03 receipt/OCR and P-10/P-11 cloud paths; P-09 worker terminal-state and cancellation accounting; I-03 DI wiring.

**Old-ID cross-refs:** NONE-FOUND (not FRESH-P8-001..008).

### CA-P08-002 | Query cloud provider bypasses the mandatory CloudPayloadPolicy path | defect class 1 (legal-path violation) | severity P1

**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`, constructor lines 39–46 injects `CloudPayloadRedactor` (defaulting to `DefaultCloudPayloadRedactor`) and no `CloudPayloadPolicy`. In `interpret`, lines 85–90 checks `PrivacyGate`, then lines 92–115 build a prompt, call `redactor.redactText(...)`, manually construct `PreparedCloudPayload` only for audit, and send `redacted.text` at lines 115–122. The service never calls `CloudPayloadPolicy.prepareText(...)` or `EffectiveCloudAiPolicyResolver`.

**Impact path:** assistant query input/alias context -> `CloudQueryInterpretationService.interpret` -> legacy redactor/manual payload -> Gemini HTTP request. The legal path requires effective policy resolution and the policy-owned prepared payload for every cloud provider; this provider can diverge from image/raw-text policy decisions and bypasses the single redaction authority.

**Caller trace:** `HybridQueryInterpretationService.interpret` lines 22–39 selects this cloud service; `di/AiModule.kt` lines 153–157 binds the hybrid implementation as `QueryInterpretationService`; `InterpretFinancialQueryUseCase` lines 41–70 is the assistant entry path.

**Existing tests/guards:** `CloudQueryInterpretationServiceTest` exercises prompt behavior with the legacy constructor but has no `CloudPayloadPolicy` seam. `scripts/verify_privacy_boundaries.py` and `verify_cloud_payload_boundaries.py` accept `redactor.redactText`/`PreparedCloudPayload` markers, so the manual wrapper passes static markers despite bypassing policy.

**Cross-cell impact:** P-03 receipt/AI routing, P-10 bank/AI routing, P-11 email/assistant routing, I-03/I-05 provider wiring.

**Old-ID cross-refs:** NONE-FOUND (not FRESH-P8-001..008).

### CA-P08-003 | Cloud AI failures expose raw exception messages and throwable stack traces | defect class 9 (privacy) | severity P1

**Evidence:** Cloud providers pass raw throwable messages into result objects and log throwable objects: `CloudDashboardBriefingService.kt` lines 256–261 (`ParseError(e.message)`, `Unknown(e.message)`, `Timber.w(e, ...)`); `CloudDedupeJudgeService.kt` lines 197–202; `CloudReceiptAssistService.kt` lines 239–245 and 392–395; `CloudReviewExplanationService.kt` lines 209–214; `CloudQueryInterpretationService.kt` lines 154–168 (`Timber.w(e, ...)`, `unsupported(...${e.message})`).

**Impact path:** provider network/JSON/SDK exception (which may include URLs, request details, file paths, or payload fragments) -> `AiServiceError`/`Unsupported` carrying `e.message` and logcat throwable stack trace -> AI use cases persist the message in `ai_artifacts.errorMessage` (for example `GenerateDashboardBriefingUseCase.kt` lines 108–115 and `failureMessage` lines 151–155; analogous dedupe/review/receipt paths) and/or surface it through assistant result models. This violates the bounded reason-code rule for both logs and persisted diagnostics.

**Caller trace:** `Hybrid*Service`/use-case cloud routes invoke these providers; `GenerateDashboardBriefingUseCase`, `JudgePendingReviewDuplicateUseCase`, `ExplainPendingReviewUseCase`, and receipt-assist use cases consume the error objects and persist/readable messages.

**Existing tests/guards:** `scripts/verify_pii_logging_boundaries.py` has no allowlist entries, but its `e.message` rule only matches message text in log/exception-constructor lines and does not detect `Timber.w(e, ...)` throwable overloads or `e.message` returned inside result objects. Provider tests assert only failure type/unsupported status; no test asserts controlled reason codes or absence of exception text.

**Cross-cell impact:** P-03/P-10/P-11 cloud pipelines, P-05 dashboard artifacts, P-09 diagnostics, and any backup/export carrying `ai_artifacts`.

**Old-ID cross-refs:** NONE-FOUND (not FRESH-P8-001..008).

### CA-P08-004 | HybridRouter is documented as the shared routing seam but has no production caller | defect class 13 (wiring / dead code) | severity P2

**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt`, lines 49–71, defines the generic router and `execute`. A production search at the pinned tree finds no import, construction, or invocation; references are limited to the class's own documentation and migration comments. `HybridQueryInterpretationService.kt` lines 14–20 (and the analogous hybrid wrappers) show the intended `HybridRouter` migration only as commented sample code, while lines 22–39 retain duplicated routing logic.

**Impact path:** changes/fixes to the documented shared router never affect the six live hybrid services; each service remains a separate routing implementation, so privacy/fallback behavior can drift and the architecture map gives a false single-seam guarantee. A future caller can also wire the generic helper without the provider-specific privacy behavior that current wrappers supply.

**Caller trace:** NONE-FOUND for `HybridRouter` in production source; live callers route through `Hybrid*Service` classes directly.

**Existing tests/guards:** `HybridServiceDelegationTest` covers wrapper-level route delegation, but no `HybridRouterTest` or production wiring test exists. `CODEBASE_SEGMENTS.md` and `ARCHITECTURE.md` state that the router consolidates the six services, which diverges from the pinned code.

**Cross-cell impact:** P-03/P-10/P-11 and I-03/I-05; all AI capability routing and privacy-policy review relying on the shared seam.

**Old-ID cross-refs:** NONE-FOUND (not FRESH-P8-001..008).

### CA-P08-005 | Provider payload acceptance test and static guard can pass a policy bypass | defect class 14 (test correctness) | severity P2

**Evidence:** `app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt` claims at lines 17–22 to verify that cloud providers use `CloudPayloadPolicy`, but every behavior test at lines 25–113 instantiates `DefaultCloudPayloadPolicy` directly and never loads or invokes a provider; lines 115–127 only assert that a guard script file exists. `scripts/verify_privacy_boundaries.py` lines 214–229 treats generic markers such as `PreparedCloudPayload`, `prepared`, `prepareText`, or `redactor.redactText` as sufficient, so `CloudQueryInterpretationService`'s hand-built payload is accepted.

**Impact path:** provider bypass introduced/retained -> acceptance test still passes because it tests only the policy implementation -> static marker scan also passes -> raw/legacy provider path reaches production without a provider-level policy contract check. This is the test/guard gap that permits CA-P08-002 to remain landed.

**Caller trace:** CI privacy/cloud guard suite and `CloudProviderPreparedPayloadTest`; no runtime caller because the defect is in verification coverage.

**Existing tests/guards:** The named test and the two scripts are the existing checks; none asserts that each production `Cloud*Service` injects `CloudPayloadPolicy`, calls a `prepare*` method, and sends the returned payload.

**Cross-cell impact:** P-03/P-10/P-11 cloud providers; I-03 DI and static-guard cells.

**Old-ID cross-refs:** NONE-FOUND (not FRESH-P8-001..008).


## Validation constraints

No builds, tests, or validation commands were run, per campaign instructions (static audit only).



