# Batch 09 — data-ai

Scope: Segment 20 (AI Platform, provider routing, briefing) + Segment 5 (AI receipt item categorization) + guarded AI worker (`data/ai/worker`). Cloud/on-device/hybrid provider tests, strict JSON parsing internals, cloud retry policy, DailyBriefingWorker.
Files: 25 · LOC: 5,509 · @Test: 170 · @Ignore: 0
Reviewer notes: Privacy-critical area (LEGAL_PATHS "Privacy / Cloud AI"): cloud calls must pass `CloudPayloadPolicy`/`PreparedCloudPayload` redaction BEFORE HTTP. Verified in-source: `CloudDashboardBriefingService` test ctors install a `failClosedGate()` — this is the root cause of ledger families F-11/F-19 (see Findings). Routing was consolidated into `domain/ai/HybridRouter` (AID-4); tests exercise routing at the hybrid-service seam via mocked `AiCapabilityRouter` — `HybridRouter` itself has ZERO direct tests (gap). `StrictAiJsonParsing` and `DashboardBriefingPromptFormatter` have no dedicated test files (indirect coverage only). `CloudPiiSanitizer` is redaction-covered in `domain/privacy/CloudPayloadPolicyTest` + `CloudProviderPreparedPayloadTest` (other batch) — several provider tests now document "raw prompt built first; policy redacts", which SUPERSEDES stale 2026-05 audit claims that provider tests verify redaction. No test asserts a raw payload leaving the app — good (the only "raw in body" assertions are on a `buildRequestBodyForTest` helper that bypasses policy and never hits HTTP).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 19 | 5 | 0 | 1 | 0 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/data/ai/provider/CloudCategorizationAssistServiceTest.kt | 509 | 11 | 0 | MOCKED | CloudCategorizationAssistService | KEEP | P0 | CloudReceiptItemCategorizationServiceTest, CloudPayloadPolicyTest (domain/privacy) | Real interceptor HTTP; asserts alias-only prompt, strict parse, fail-closed ctor; overturn 2026-05 REWRITE |
| 2 | test/…/data/ai/provider/CloudDashboardBriefingServiceTest.kt | 264 | 5 | 0 | MOCKED | CloudDashboardBriefingService | REWRITE | P1 | DashboardBriefingResponseParserTest (complementary) | F-11/F-19: 2-arg test ctor installs fail-closed gate → interceptor never hit; 4 tests fail |
| 3 | test/…/data/ai/provider/CloudDedupeJudgeServiceTest.kt | 258 | 5 | 0 | MOCKED | CloudDedupeJudgeService | KEEP | P1 | OnDeviceDedupeJudgeServiceTest (complementary layer) | Real JSON verdicts, offline/disabled/parse-error; relaxed PrivacyGate mock |
| 4 | test/…/data/ai/provider/CloudQueryInterpretationServiceTest.kt | 349 | 8 | 0 | MOCKED | CloudQueryInterpretationService | KEEP | P0 | OnDeviceQueryInterpretationServiceTest (complementary) | Alias-only prompt assert; gate-denied → verify 0 HTTP; CancellationException rethrow |
| 5 | test/…/data/ai/provider/CloudReceiptAssistServiceAuditTest.kt | 159 | 1 | 0 | MOCKED | CloudReceiptAssistService + PrivacyAuditLogger | KEEP | P0 | CloudAuditProviderProvenanceTest (domain/privacy) | PreparedCloudPayload provenance (hash, redactionApplied, rawTextIncluded=false) via hand-written recorder |
| 6 | test/…/data/ai/provider/CloudReceiptAssistServiceTest.kt | 269 | 6 | 0 | MOCKED | CloudReceiptAssistService | STRENGTHEN | P1 | CloudReceiptAssistServiceAuditTest (complementary) | Image suppressed when redactBeforeCloud; misleading name on raw-prompt helper test |
| 7 | test/…/data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt | 267 | 3 | 0 | MOCKED | CloudReceiptItemCategorizationService | STRENGTHEN | P1 | OnDeviceReceiptItemCategorizationServiceTest, CloudPayloadPolicyTest | Test 1 name contradicts assertion (tautology); alias-mapping tests real; overturn 2026-05 KEEP-P0 |
| 8 | test/…/data/ai/provider/CloudReviewExplanationServiceTest.kt | 82 | 2 | 0 | MOCKED | CloudReviewExplanationService | KEEP | P1 | OnDeviceReviewExplanationServiceTest (complementary) | Typed PrivacyDenied + capability; overturn 2026-05 DELETE |
| 9 | test/…/data/ai/provider/CloudWarrantyExtractionServiceTest.kt | 205 | 4 | 0 | MOCKED | CloudWarrantyExtractionService | KEEP | P2 | WarrantyTrackerRepositoryTest (complementary) | Full field parse incl. return-policy-only; relaxed PrivacyGate mock |
| 10 | test/…/data/ai/provider/DashboardBriefingResponseParserTest.kt | 37 | 3 | 0 | PURE | DashboardBriefingResponseParser | KEEP | P1 | CloudDashboardBriefingServiceTest | NaN/out-of-range confidence rejected |
| 11 | test/…/data/ai/provider/DefaultAiEnvironmentMonitorTest.kt | 116 | 4 | 0 | ROBOLECTRIC | DefaultAiEnvironmentMonitor | KEEP | P2 | — | Fake clock TTL boundary checks; mockkObject(Generation) static |
| 12 | test/…/data/ai/provider/HybridReceiptItemCategorizationServiceTest.kt | 90 | 1 | 0 | MOCKED | HybridReceiptItemCategorizationService | STRENGTHEN | P2 | HybridServiceDelegationTest (complementary) | Only ON_DEVICE route; no cloud/fallback/disabled coverage |
| 13 | test/…/data/ai/provider/HybridServiceDelegationTest.kt | 486 | 7 | 0 | MOCKED | Hybrid{Receipt,Categorization,Query}Service | KEEP | P1 | SmartReceiptAssistServiceTest (complementary), DefaultAiCapabilityRouterTest | Exact-count delegation × 4 routes; stale TODO labels; FRAGILE harness |
| 14 | test/…/data/ai/provider/OnDeviceCategorizationAssistServiceTest.kt | 217 | 21 | 0 | PURE | OnDeviceCategorizationAssistService | KEEP | P1 | CloudCategorizationAssistServiceTest (complementary) | Excellent strict-parse matrix (fences, NaN, zero id, alt ids) |
| 15 | test/…/data/ai/provider/OnDeviceDashboardBriefingServiceTest.kt | 111 | 5 | 0 | PURE | OnDeviceDashboardBriefingService | KEEP | P0 | CloudDashboardBriefingServiceTest (complementary) | Asserts redacted insight prompt: alias merchant, amount bucket, no raw values |
| 16 | test/…/data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt | 120 | 8 | 0 | PURE | OnDeviceDedupeJudgeService | KEEP | P1 | CloudDedupeJudgeServiceTest (complementary) | Verdict/targetId zero-drop, NaN confidence, unknown enum |
| 17 | test/…/data/ai/provider/OnDeviceNotificationParserTest.kt | 60 | 3 | 0 | MOCKED | OnDeviceNotificationParser | KEEP | P2 | domain parser tests | Transfer metadata kept/dropped correctly; stale TODO labels; relaxed mocks |
| 18 | test/…/data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt | 137 | 7 | 0 | PURE | OnDeviceQueryInterpretationService | KEEP | P1 | CloudQueryInterpretationServiceTest (complementary) | Alias-only lookup keys; alias→real resolution; explicit period |
| 19 | test/…/data/ai/provider/OnDeviceReceiptAssistServiceTest.kt | 141 | 8 | 0 | PURE | OnDeviceReceiptAssistService | KEEP | P1 | CloudReceiptAssistServiceTest, SmartReceiptAssistServiceTest | Image attach/omit; merchant/total/date parse; missing→null |
| 20 | test/…/data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt | 48 | 1 | 0 | PURE | OnDeviceReceiptItemCategorizationService | KEEP | P3 | CloudReceiptItemCategorizationServiceTest | Single keyword-fallback test; thin but real |
| 21 | test/…/data/ai/provider/OnDeviceReviewExplanationServiceTest.kt | 86 | 6 | 0 | PURE | OnDeviceReviewExplanationService | KEEP | P2 | CloudReviewExplanationServiceTest (complementary) | Prompt facts; blank headline/body → null |
| 22 | test/…/data/ai/provider/SmartReceiptAssistServiceTest.kt | 474 | 8 | 0 | MOCKED | SmartReceiptAssistService | KEEP | P1 | HybridServiceDelegationTest (complementary) | Real fall-through + attemptDetails; no cloud route when router denies; FRAGILE 8-mock ctor |
| 23 | test/…/data/ai/provider/internal/CloudJsonParserTest.kt | 156 | 12 | 0 | PURE | CloudJsonParser | KEEP | P1 | used by all cloud/on-device parsers | Nested braces, escapes, fences, strict double/long |
| 24 | test/…/data/ai/provider/internal/CloudRetryPolicyTest.kt | 71 | 7 | 0 | PURE | CloudRetryPolicy | KEEP | P1 | CloudDashboardBriefingServiceTest (consumer) | Retryable codes/IO causes, bounded backoff+ jitter |
| 25 | test/…/data/ai/worker/DailyBriefingWorkerTest.kt | 797 | 24 | 0 | ROBOLECTRIC | DailyBriefingWorker | STRENGTHEN | P0 | WorkerExecutionGuardTest, SyncProactiveBriefingWorkUseCaseTest | Worker lifecycle + idempotent notification IDs + reschedule matrix; guard mocked (mirrored, not real); 1 tautological test; FRAGILE |

## Findings (noteworthy files only)

### test/…/data/ai/provider/CloudDashboardBriefingServiceTest.kt — F-11 / F-19 root cause identified
- 4 of 5 tests (lines 62, 119, 158, 197) construct `CloudDashboardBriefingService(mockKeyStorage, client)` — the 2-arg `@VisibleForTesting` ctor, which since P8 installs `failClosedGate()` + `EffectiveCloudAiPolicyResolver.failClosedNoAi()` (production `CloudDashboardBriefingService.kt:85-98`).
- The fail-closed gate denies before HTTP, so the interceptor counters stay 0 → `expected 2 but was 0` / `expected 3 but was 0`. This answers the ledger's "retry refactor?" hypothesis: NOT a retry change — a stale test constructor vs the new privacy-gate contract.
- Fix: build with an allowed gate + real `DefaultCloudPayloadPolicy` exactly as `CloudCategorizationAssistServiceTest.serviceWithAllowedGate` (test lines 404-416) does. Also delete the stale "TODO: Tautological" comments (tests do assert retry counts and parsed domain output).
- Also note: the key-absent test (line 27) only exercises the disabled path; no test currently proves a successful briefing parse end-to-end (parser itself is covered by #10).
- Recommended action: REWRITE the 4 failing tests with allowed-gate construction; keep assertions as-is. Priority P1 (family F-11 count 4, F-19 count 4 — same 4 tests).

### test/…/data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt
- Test 1 (lines 29-126) is named "redaction on does not include raw category names in payload" but asserts `capturedPrompt.contains("Private Category Alpha") || capturedPrompt.contains("cat_a1b2c3")` (line 125) — an always-true-style disjunction asserting the OPPOSITE of the name. The inline comment (lines 122-124) explains redaction moved to `CloudPayloadPolicy` (PRIV-43B-03); redaction lives in `domain/privacy/CloudPayloadPolicyTest` (other batch).
- This overturns the 2026-05 audit verdict ("KEEP P0 — verifies redaction strips raw category names"): that property is no longer tested here.
- Tests 2-3 (lines 129-215) are real: fallback alias→real category mapping and unknown-alias passthrough with confidence/alternatives assertions.
- Recommended action: rename test 1 to what it verifies (raw prompt composition pre-policy) or replace it with a policy-integrated redaction assertion; keep tests 2-3. STRENGTHEN P1.

### test/…/data/ai/provider/CloudReceiptAssistServiceAuditTest.kt
- Best-practice privacy test in the batch: hand-written `RecordingAuditLogger` (no mockk fragility, line 41) asserts cloud-call provenance — capability `CLOUD_AI_RECEIPT_ASSIST`, purpose, `payloadHash`, `redactionApplied=true`, `rawTextIncluded=false`, `rawImageIncluded=false` (lines 147-157) — i.e., the `PreparedCloudPayload` contract that guarantees redaction happened BEFORE the HTTP call (interceptor returns the response; request body never contains raw OCR).
- Exactly the "redaction before cloud calls" evidence the audit guidance asks for. KEEP P0.

### test/…/data/ai/provider/CloudCategorizationAssistServiceTest.kt (overturn stale REWRITE)
- 2026-05 audit said REWRITE ("4+ TODOs, tautological"). Current source: redaction test asserts captured cloud body contains `category_alias_groceries`/`merchant_123` and NOT raw `Lidl` (lines 271-274); strict parsing rejects NaN/Infinity/1.1/-0.1 confidence, zero/non-numeric categoryId (lines 280-333); 2-arg fail-closed ctor test proves no HTTP is reached (lines 385-395). Retry tests assert exact attempt counts + parsed domain result. Only the 4 stale TODO comments remain. KEEP P0.

### test/…/data/ai/provider/CloudReviewExplanationServiceTest.kt (overturn stale DELETE)
- 2026-05 audit said DELETE P4 ("1 TODO tautological test"). Now 2 tests: key-absent → `AiServiceError.Disabled`, and fail-closed gate → typed `PrivacyDenied` with `CLOUD_AI_GENERAL` capability (lines 52-81). Real fail-closed privacy coverage; STRENGTHEN candidate only for a success-path parse test. KEEP P1.

### test/…/data/ai/worker/DailyBriefingWorkerTest.kt
- Strong worker coverage (strict area): guard-mirrored classification (Retry on transient/timeout, rethrow CancellationException — lines 109-126, 232-243), reschedule matrix (Success/Skip reschedule; DISABLED/Retry/Failed do not — lines 302-439) with an anti-tautology literal drift guard (lines 398-401), deterministic notification-ID idempotency incl. post-delivery timeout (lines 514-754), metrics (`addNotificationsSent`) counted only on real delivery.
- Gaps: guard semantics are re-implemented inside the MockK `coAnswers` rather than using the real `WorkerExecutionGuard` (drift risk; mitigated by `WorkerExecutionGuardTest` and the literal drift guard). One tautological test (`worker returns success`, lines 192-199) and a misleading `no data empty briefing stored` name (provides full data, asserts only the use case call). FRAGILE: 11-mock ctor + `mockkStatic(WorkManager)`.

### Routing seam / HybridRouter (AID-4) — batch-level finding
- Production routing is consolidated in `domain/ai/HybridRouter.kt`, used by all 6 hybrid services (grep verified). NO test file references `HybridRouter` directly — coverage is indirect via `HybridServiceDelegationTest`, `SmartReceiptAssistServiceTest`, `HybridReceiptItemCategorizationServiceTest`, all mocking `AiCapabilityRouter`. The delegation tests are the correct seam (no duplicated per-provider routing logic asserted); but a dedicated `HybridRouterTest` (settings read, disabled short-circuit, fallback fn semantics, one-exact-call) is missing. `HybridDashboardBriefingService`, `HybridDedupeJudgeService`, `HybridReviewExplanationService` have no delegation tests at all (only the 3 in #13).

## Area gaps (what is NOT tested in this area)

- `HybridRouter` (AID-4 shared routing base): zero direct tests; only indirect service-level coverage.
- `HybridDashboardBriefingService`, `HybridDedupeJudgeService`, `HybridReviewExplanationService`: no routing/delegation tests.
- `DashboardBriefingPromptFormatter`: no dedicated test (only indirectly via the currently-failing CloudDashboardBriefingServiceTest).
- `StrictAiJsonParsing`: no dedicated test file; covered indirectly (NaN/out-of-range/strict-type cases in #1, #10, #14, #16, #23) — acceptable but undocumented.
- Privacy-denied → no HTTP is proven only for query interpretation (#4) and review explanation (#8); dedupe judge, warranty, categorization-assist, receipt-assist, dashboard-briefing rely on relaxed PrivacyGate mocks and never test the denial path.
- Relaxed `mockk<PrivacyGate>()` in #3/#7/#9 depends on MockK relaxed behavior for a sealed-typed return — brittle; prefer the explicit `allowedGate()` object pattern used in #1/#5.
- No test covers retry interplay with privacy policy for receipt-assist redaction mode (image suppressed + retry combined).

## Rollup

- Verdict counts: KEEP 19 · STRENGTHEN 5 · REWRITE 1 · MERGE 0 · DELETE 0 · NIGHTLY 0 · UNKNOWN 0
- P0 count: 5 (#1, #4, #5, #15, #25)
- DUP pairs: 0 (no same-class-same-behavior duplicates). Near-dup/complementary pairs noted: HybridServiceDelegationTest ↔ SmartReceiptAssistServiceTest (different classes, complementary); retry-interceptor scaffolding duplicated as boilerplate across 5 cloud provider tests (pattern copy, not behavior dup); cloud vs on-device parser tests are complementary layers, not DUPs.
- FRAGILE count: 4 (#13, #22, #25, #11 — heavy constructor/static-mock coupling)
- Ledger families hit: F-11 + F-19 → `CloudDashboardBriefingServiceTest` (root cause: fail-closed test ctor; stale test, not a prod retry regression — but confirm against privacy intent before fixing).
- Stale 2026-05 verdicts overturned: #1 REWRITE→KEEP, #7 KEEP-P0→STRENGTHEN-P1, #8 DELETE→KEEP.
