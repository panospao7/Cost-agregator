# Batch 15 — domain-ai

Scope: Segment 20 (AI Platform, Assistant & Follow-Through) + Segment 5 (AI Receipt Item Categorization): domain/ai model, policy (AiPolicy, DefaultAiCapabilityRouter), usecase (categorization, dedupe, briefing, financial query, receipt extraction), util (AiArtifactSourceHash). · Files: 31 · LOC: 5,745 (179 @Test, 0 @Ignore)

Reviewer notes: This area is in markedly better shape than the 2026-05 audit suggested — the domain-ai seam is interface-based (router/services/builders are interfaces), so mock coupling is moderate, not the fragile-constructor pain seen elsewhere. A P8-NEW-01 privacy contract (`InputBuilderRedactionPolicyTest`, new since prior audit) plus per-builder "privacy authority" tests now pin `PrivacySettings.redactBeforeCloud` as authoritative over `AiSettings` across 6 builders/use-cases — a real regression guard for a past leak class. Fail-closed is verified at the settings layer: `AiPolicyTest` proves default `AiSettings` → `canUseCloud=false`, `shouldRedact=true`, and contradictory combos resolve deny. **Scope note for the coordinator:** the P0 `EffectiveCloudAiPolicy` resolution tests named in the batch guidance do NOT live here — `EffectiveCloudAiPolicy`/`Resolver` is in `domain/privacy/EffectiveCloudAiPolicy.kt` and is tested by `domain/privacy/*` (CloudPayloadPolicyTest, P8PrivacyFixesTest, PR5PrivacyContractTest, scenarios/PrivacyGateContractTest) and data/ai provider tests (batch 09). This batch covers the `AiSettings`-layer policy half (AiPolicyTest). **HybridRouter:** confirmed zero test-tree references to `domain/ai/HybridRouter` — batch 09's finding persists globally; domain-ai tests cover route *decision* (DefaultAiCapabilityRouterTest) but nothing tests the HybridRouter execution wrapper (settings read → decide → provider dispatch → fallback) directly. No file in this batch is in ledger families F-01…F-21 (F-11/F-19 are data/ai, batch 09). Prior-audit verdicts re-verified: mostly upheld; one MERGE issued (new overlapping contract test supersedes a single-test builder file); one stale-verdict upgrade (ValidateBankStatement... prior gaps now actually covered).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 26 | 4 | 1 | 0 | 0 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/ai/model/AiArtifactPresentationTest.kt | 68 | 3 | 0 | PURE | AiArtifactRecord.toDiagnosticsOrNull | KEEP | P3 | AiRuntimeStatusModelsTest (complementary) | Pins diagnostic display strings; low value but cheap |
| 2 | test/…/domain/ai/model/AiRuntimeStatusModelsTest.kt | 35 | 2 | 0 | PURE | AiCapabilityRuntimeStatus.routeDisplayText | KEEP | P3 | #1 (complementary) | Null-route handling; string pin |
| 3 | test/…/domain/ai/model/CategorizationAssistInputTest.kt | 27 | 1 | 0 | PURE | CategorizationAssistInput init | KEEP | P3 | InputBuilderRedactionPolicyTest (complementary) | Real init invariant: rejects NaN amount |
| 4 | test/…/domain/ai/model/NotificationParsingModelsTest.kt | 38 | 2 | 0 | PURE | NotificationParseResult init | KEEP | P3 | — | Rejects zero amount, confidence>1; real invariants |
| 5 | test/…/domain/ai/model/OnDeviceRuntimePresentationTest.kt | 37 | 4 | 0 | PURE | OnDeviceModelStatus.toRuntimeStatusMessage | KEEP | P3 | GetAiRuntimeStatusUseCaseTest (complementary) | Pins user-facing strings; brittle to copy edits |
| 6 | test/…/domain/ai/model/WarrantyExtractionModelsTest.kt | 39 | 2 | 0 | PURE | WarrantyExtractionResult init | KEEP | P3 | — | Rejects NaN confidence, non-positive fields |
| 7 | test/…/domain/ai/policy/AiPolicyTest.kt | 149 | 15 | 0 | PURE | AiPolicyImpl | KEEP | P0 | domain/privacy EffectiveCloudAiPolicy tests (complementary layer) | Fail-closed defaults; all toggle combos; redact authority |
| 8 | test/…/domain/ai/policy/DefaultAiCapabilityRouterTest.kt | 384 | 18 | 0 | MOCKED | DefaultAiCapabilityRouter | KEEP | P0 | HybridServiceDelegationTest b09 (complementary) | No-cloud-leak in ON_DEVICE mode; API-key fail-closed; mild FRAGILE 5-dep ctor |
| 9 | test/…/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt | 289 | 7 | 0 | MOCKED | CategorizationAssistInputBuilder | KEEP | P1 | InputBuilderRedactionPolicyTest (complementary) | Redaction, privacy authority, cancel propagation |
| 10 | test/…/domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt | 132 | 1 | 0 | MOCKED | CategorizeReceiptItemsUseCase | STRENGTHEN | P2 | ReceiptScanViewModelStressTest (complementary) | Only failure path (ANALYZING→PENDING restore); no success/cloud route |
| 11 | test/…/domain/ai/usecase/DedupeJudgeInputBuilderTest.kt | 350 | 9 | 0 | MOCKED | DedupeJudgeInputBuilder | KEEP | P1 | JudgePendingReviewDuplicateUseCaseTest (complementary) | A.4 regressions; type/currency filtering; privacy authority; one constant-pin test |
| 12 | test/…/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt | 207 | 6 | 0 | MOCKED | DeliverProactiveBriefingNotificationUseCase | KEEP | P2 | DailyBriefingWorkerTest (complementary) | Dedup delivery gates; metrics only after real delivery |
| 13 | test/…/domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt | 395 | 9 | 0 | MOCKED | ExecuteFinancialQueryUseCase | KEEP | P1 | AssistantViewModelTest (complementary) | Money text correctness; mixed-currency no raw sum — money rule |
| 14 | test/…/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt | 385 | 12 | 0 | MOCKED | ExplainPendingReviewUseCase | KEEP | P1 | ReviewViewModelStressTest (complementary) | Full artifact lifecycle; cancel writes no FAILED; FRAGILE 6-dep ctor |
| 15 | test/…/domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt | 201 | 4 | 0 | MOCKED | FinancialQueryInterpretationInputBuilder | KEEP | P1 | InterpretFinancialQueryUseCaseTest (complementary) | Card redaction in query text; reversible alias maps; truncation caps |
| 16 | test/…/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt | 319 | 10 | 0 | MOCKED | GenerateDashboardBriefingUseCase | KEEP | P2 | DailyBriefingWorkerTest (complementary) | Cache/hash staleness, TTL, truncation, cancel |
| 17 | test/…/domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt | 195 | 2 | 0 | MOCKED | GenerateTransactionInsightUseCase | STRENGTHEN | P1 | NotificationProcessingPipeline tests (complementary) | Strong redaction pair; missing disabled/failure/on-device paths |
| 18 | test/…/domain/ai/usecase/GetAiRuntimeStatusUseCaseTest.kt | 136 | 4 | 0 | MOCKED | GetAiRuntimeStatusUseCase | KEEP | P3 | AiSettingsViewModelTest, AssistantViewModelTest (complementary) | Status aggregation + priority message; long-string pins duplicated |
| 19 | test/…/domain/ai/usecase/InputBuilderRedactionPolicyTest.kt | 154 | 4 | 0 | MOCKED | ReceiptItemCategorizationInputBuilder, ReceiptAssistInputBuilder | KEEP | P0 | #25 (DUP overlap — survivor) | P8-NEW-01 contract: PrivacySettings redact authority; both polarities |
| 20 | test/…/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt | 216 | 7 | 0 | MOCKED | InterpretFinancialQueryUseCase | KEEP | P2 | Engine5PrimitiveGuardTest (complementary) | Disabled gate, local fallback, cancel propagation |
| 21 | test/…/domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt | 275 | 7 | 0 | MOCKED | JudgePendingReviewDuplicateUseCase | KEEP | P1 | ReviewViewModelStressTest (complementary) | Clears matched target outside candidate set — wrong-merge guard |
| 22 | test/…/domain/ai/usecase/MapFinancialQueryToNavigationUseCaseTest.kt | 72 | 2 | 0 | PURE | MapFinancialQueryToNavigationUseCase | KEEP | P2 | AssistantViewModelTest (complementary) | Filter→navigation mapping incl. ownership |
| 23 | test/…/domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt | 113 | 4 | 0 | MOCKED | PrioritizeReviewItemsUseCase | STRENGTHEN | P3 | — | Sort/tie-break real; `score calculation correct` is tautological delegation |
| 24 | test/…/domain/ai/usecase/ReceiptAssistInputBuilderTest.kt | 102 | 3 | 0 | MOCKED | ReceiptAssistInputBuilder | KEEP | P1 | InputBuilderRedactionPolicyTest (complementary) | CARD/IBAN/phone redaction in OCR text — PII leak guard |
| 25 | test/…/domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt | 88 | 1 | 0 | MOCKED | ReceiptItemCategorizationInputBuilder | MERGE | P3 | #19 (DUP — merge into InputBuilderRedactionPolicyTest) | Same redaction behavior + same fixtures as #19, narrower |
| 26 | test/…/domain/ai/usecase/ReviewExplanationInputBuilderTest.kt | 102 | 3 | 0 | MOCKED | ReviewExplanationInputBuilder | KEEP | P1 | ExplainPendingReviewUseCaseTest (complementary) | Pseudonymize merchant/app; drop notification text; privacy authority |
| 27 | test/…/domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt | 420 | 10 | 0 | MOCKED | SuggestCategoryFallbackUseCase | KEEP | P1 | CancellationSafetyArchitectureGuardTest (complementary) | Lifecycle, malformed-cache bypass, Uncategorized-allowed, cancel |
| 28 | test/…/domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt | 361 | 9 | 0 | MOCKED | SuggestReceiptExtractionUseCase | KEEP | P1 | ReceiptScanViewModelStressTest (complementary) | Stable hash ignores time; image-aware marker; FRAGILE 7-dep ctor |
| 29 | test/…/domain/ai/usecase/SyncProactiveBriefingWorkUseCaseTest.kt | 69 | 3 | 0 | MOCKED | SyncProactiveBriefingWorkUseCase | KEEP | P3 | AiSettingsViewModelTest (complementary) | Schedule/cancel contract; thin pure verify |
| 30 | test/…/domain/ai/usecase/ValidateBankStatementTransactionsUseCaseTest.kt | 234 | 9 | 0 | MOCKED | ValidateBankStatementTransactionsUseCase | STRENGTHEN | P1 | CancellationSafetyArchitectureGuardTest (complementary) | Cloud only after PrivacyGate check; stale KDoc claims gaps now covered |
| 31 | test/…/domain/ai/util/AiArtifactSourceHashTest.kt | 153 | 6 | 0 | PURE | AiArtifactSourceHash | KEEP | P2 | Used by 4 usecase tests (complementary) | Hash stability + sensitivity — cache invalidation correctness |

## Findings (noteworthy files only)

### test/…/domain/ai/policy/AiPolicyTest.kt
- Real `AiPolicyImpl`, zero mocks; 15 tests enumerate `canUseCloud` truth table, per-capability cloud gates, on-device gate, `shouldRedact` (all `AiCapability.entries` both polarities, independence from master switches).
- Fail-closed verified: default `AiSettings()` → `canUseCloud=false` + `shouldRedact=true` (AiPolicyTest.kt:143-148); contradictory `aiEnabled=true, allowCloudAi=false` → deny (:33-36).
- This is the settings-layer half of the P0 EffectiveCloudAiPolicy story; resolution/fail-closed of the *resolver* itself is in `domain/privacy/*` tests (other batch) — cross-referenced, not duplicated.
- Action: KEEP P0. This is the cheapest, highest-leverage privacy gate in the suite.

### test/…/domain/ai/usecase/InputBuilderRedactionPolicyTest.kt
- New since the 2026-05 audit; documents contract P8-NEW-01 (InputBuilderRedactionPolicyTest.kt:21-29): builders must redact when EITHER AiSettings or PrivacySettings `redactBeforeCloud` is true — previously PrivacySettings=true with AiSettings=false leaked raw merchant/category labels.
- Asserts hashed `merchant_`/`cat_` prefixes and absence of raw labels, both polarities, two builders. Textbook behavioral privacy test.
- Supersedes `ReceiptItemCategorizationInputBuilderTest` (#25) which tests the same builder, same redaction behavior, same category-name fixtures with a single test → MERGE #25 into this file.

### HybridRouter (area finding — batch-09 gap persists)
- `app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt` has ZERO references anywhere in `app/src/test` (grep verified). All routing coverage in this batch is at the decision layer (DefaultAiCapabilityRouterTest: 18 solid tests incl. the on-device-preferred "no cloud leak" privacy fix at :344-363 and missing-API-key fail-closed at :366-383).
- Execution semantics (settings read → decide → dispatch to on-device/cloud fn → fallback lambda, one-exact-call) are only indirectly exercised by data/ai delegation tests (batch 09). A dedicated `HybridRouterTest` is still missing — recommended P1 add.

### test/…/domain/ai/usecase/ValidateBankStatementTransactionsUseCaseTest.kt
- Privacy path correct: cloud fallback happens only after `privacyGate.check(CLOUD_AI_BANK_STATEMENT)` (test :89-109), denial → PARSER_ONLY (:112-128) — matches AGENTS.md fail-closed rule; legal path respected.
- KDoc (:19-34) claims six coverage gaps "not yet tested" — stale: tests below cover on-device success, cloud fallback, gate denial, parser-only, envelope/markdown/empty JSON parsing, AI_CORRECTED attribution. KDoc should be deleted or rewritten.
- Gap left: no `coVerify(exactly=0)` that the gate is NOT consulted when on-device succeeds (comment at :75 promises it, never asserted); no cancellation test here (static guard `CancellationSafetyArchitectureGuardTest` references the class).

### test/…/domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt
- Only 2 tests, both redaction (AiSettings-redact and PrivacySettings-authority variants); assertions on amount bucketing (RANGE_100_249) and stripped weather/budget context are strong.
- Unlike its 4 sibling artifact-lifecycle use-cases, no disabled-gate, failure-artifact, ON_DEVICE-metadata, or cancellation test. STRENGTHEN: add the standard lifecycle quartet.

### Artifact-lifecycle use-case family (#14, #16, #21, #27, #28)
- Five files share a high-quality common pattern: disabled gate → cache hit/stale-hash → RUNNING tombstone → READY/FAILED with route metadata → CancellationException propagates and writes NO FAILED artifact (e.g. ExplainPendingReviewUseCaseTest.kt:345-365). This directly encodes AGENTS.md worker/cancellation rules at the domain seam.
- Minor privacy observation: tests pin that provider exception messages land verbatim in `AiArtifactRecord.errorMessage` (e.g. "Network timeout", ExplainPendingReviewUseCaseTest.kt:338). AGENTS.md diagnostics discipline prefers exception class name/controlled codes over raw messages — consider asserting class-name-based diagnostics in production instead of message passthrough.

## Area gaps (what is NOT tested in this area)

- `HybridRouter` (domain/ai/HybridRouter.kt): zero direct tests; gap persists globally (batch-09 finding stands). No delegation tests at all for HybridDashboardBriefingService, HybridDedupeJudgeService, HybridReviewExplanationService (batch-09).
- `EffectiveCloudAiPolicy`/`EffectiveCloudAiPolicyResolver`: not here by design (domain/privacy owns it) — coordinator should confirm the privacy batch covers fail-closed on unknown/contradictory settings.
- `CategorizeReceiptItemsUseCase` (Segment 5 core): only the service-returns-null failure path; no success path, no cloud-route, no per-item tax-split/precision assertions at this layer.
- Assistant models: `AiChatMessage`, `AssistantHistoryMode`, `AssistantMessageKind/Role` — no direct model tests (exercised only incidentally via input-builder tests).
- `DetectSemanticDuplicateUseCase`, `ExtractedAmountFilter`, `AiOutputValidators` (validation/AiOutputValidators.kt), `ReviewPriorityScorer` (only ever mocked): no direct tests in the tree; AiOutputValidators only indirectly via data/ai strict-JSON tests.
- `PrioritizeReviewItemsUseCaseTest` never tests the real `ReviewPriorityScorer` scoring math — the scorer itself appears untested anywhere (0 direct test files).

## Rollup

- Verdicts: KEEP 26 · STRENGTHEN 4 · MERGE 1 · REWRITE 0 · DELETE 0 · NIGHTLY 0 · UNKNOWN 0
- P0: 3 (AiPolicyTest, DefaultAiCapabilityRouterTest, InputBuilderRedactionPolicyTest) · P1: 12 · P2: 9 · P3: 7
- DUP pairs: 1 — ReceiptItemCategorizationInputBuilderTest → InputBuilderRedactionPolicyTest (merge; survivor keeps both builders + polarities)
- FRAGILE count: 3 (CategorizeReceiptItemsUseCaseTest 9-dep ctor; SuggestReceiptExtractionUseCaseTest 7-dep; ExplainPendingReviewUseCaseTest 6-dep) — all interface seams, moderate not severe
- P4 (negative value) files: none (one tautological delegation test inside PrioritizeReviewItemsUseCaseTest flagged for removal, file itself STRENGTHEN)
- Prior-audit overturns: ValidateBankStatementTransactionsUseCaseTest KDoc "gaps" now covered (upgrade KEEP→STRENGTHEN with doc cleanup); ReceiptItemCategorizationInputBuilderTest KEEP→MERGE (superseded by new P8-NEW-01 contract test)
