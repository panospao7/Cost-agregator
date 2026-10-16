# Impact map: cloud-alias-hashing

Author: impact-mapper (read-only). Persisted by the orchestrator. Date 2026-10-16. `M/` = `app/src/main/java/com/yourname/expensetracker/`, `T/` = `app/src/test/java/com/yourname/expensetracker/`.
Budget note (mapper): exceeded 45 calls; did NOT read `ENGINE_INTERACTION_MAP.md` rows or `CODEBASE_SEGMENTS.md` (consumers below from source). FG-IDs confirmed by heading only for FG-07/10/16/19/20/23/26.

## 0. Findings the domain spec must absorb
1. **RI already has a name-free id fallback:** `CloudReceiptItemCategorizationService.kt:542-552` `cloudCategoryOptionsForPrompt` builds `cat_<id>` from `userCategories` when `redactBeforeCloud && cloudCategoryOptions.isEmpty()`; reverse `:576-588` (`cat_(\d+)`); one-to-one. → RI on null hasher can send empty `cloudCategoryOptions` + `merchant_redacted` (single field) → no UNAVAILABLE state, no routing change (**OD-H8**). Confirms OD-H5 must stay.
2. **HybridRouter has 5 production users** (Dashboard, Query, ReceiptItem, CategorizationAssist, ReviewExplanation, `M/data/ai/provider/Hybrid*Service.kt`); only Query (`HybridQueryInterpretationService.kt:22`), ReceiptItem (`:21`), CategorizationAssist (`:22`) need `aliasStateOf`; `HybridRouterTest` uses `String` inputs → optional param with default compiles. **U3 resolved:** `CategorizeReceiptItemsUseCase.kt:41-42, 169-171` injects two unqualified `ReceiptItemCategorizationService` → both = `HybridReceiptItemCategorizationService` (`M/di/AiModule.kt:159-163`) → Hybrid-level check covers it. `createFallbackResult` (`:246-279`) reachable only on `DETERMINISTIC_FALLBACK`; Hybrid `fallbackFn = { null }` → `failCategorization` `:179-181`.
3. **U2 resolved — no NoOp/fallback result persisted READY:** Dedupe NoOp → `Failure(Disabled)` (`NoOpDedupeJudgeService.kt:11-12`) → FAILED (`JudgePendingReviewDuplicateUseCase.kt:114-122`); CA NoOp → `null` (`NoOpCategorizationAssistService.kt:9`) → FAILED (`SuggestCategoryFallbackUseCase.kt:186-196`); RI `fallbackFn` null → `failCategorization`. Caveat: RUNNING/FAILED artifact records `routeDecision` (CLOUD/ON_DEVICE) though NoOp ran → `mode`/provider misattributed (cosmetic; **OD-H11**).
4. **U1 resolved** (`M/data/privacy/KeystoreInstallationSecretHasher.kt:77-102`): UTF-8 (`:88`); null never cached (re-queries `keyProvider.getOrCreateSecretKey` every call `:85`); `currentVersion` = const `CURRENT_VERSION = 1`. **Perf:** each alias call does `KeyStore.getInstance + load` (`:44`); FQI aliases ALL `getRecentMerchantNames()` then `.distinct().take(100)` (`FinancialQueryInterpretationInputBuilder.kt:67-81`) → possibly hundreds of Keystore round-trips → dedupe+cap before alias or perf note/test (**OD-H10**).
5. **Guard scope collision (G1/G2 would fail day 1):** non-alias public-hash uses in `data/privacy/**`, `domain/privacy/**`: `DefaultCloudPayloadPolicy.kt:3` (import), `:50`, `:82` (`sha256Prefix(32)` audit `payloadHash` of raw/prepared prompt text); `DefaultSensitiveHashingService.kt:25`, `:37` (`MessageDigest`), `:34` (`override fun sha256Prefix`); `domain/privacy/SensitiveHashingService.kt:26` (interface, KDoc `:10`); `DefaultCloudPayloadRedactor.kt:97` (`hashPayload`, only one seeded by spec). → allowlist with justification or narrow scope; `DefaultCloudPayloadPolicy.kt:50/:82` (unsalted SHA of raw prompt text in audit record) explicitly out of scope or owner decides (**OD-H9**).
6. **Existing registered guard `privacy`** (`scripts/ci/guard_registry.py:568`, `scripts/verify_privacy_boundaries.py`, ratchet baseline `config/baselines/privacy.json`, tests `scripts/test_verify_privacy_boundaries.py`) already has G11 (`.hashCode()` ban for prompt pseudonyms in `data/ai/provider`). Spec G1..G4 collide with script G1..G14 → rename G15..G18 (**OD-H12**). `cloud_payload` (`G-CLOUD-01`) and `pii_logging` are the wrong homes.
7. **Ctor wiring:** `DefaultCloudPayloadRedactor()` (`:35-38`) and `DashboardBriefingPromptFormatter:59` use `CloudPiiSanitizer(FailClosedInstallationSecretHasher)`; `CloudWarrantyExtractionService` has 5-arg `@Provides` (`AiModule.kt:227-234`) + internal test ctor (`:53-62`) — copy its `auditLogger` default pattern. Keep `CloudPiiSanitizer(InstallationSecretHasher)` and build `CloudAliasFactory` internally → no churn in `KeystoreInstallationSecretHashingTest.kt:52,55`.
8. **U-resolutions:** U5 `AiCapability.WARRANTY_EXTRACTION` exists (`domain/ai/model/AiModels.kt:14`). U9 Dedupe subject uses `sanitizeLabel` like candidates (`DedupeJudgeInputBuilder.kt:89, 94`); expense candidate `sourceLabel` hashes constants `"notification"`/`"expense"` (`:113-117`). U11 `CloudPiiSanitizer.sanitizeText` blank fallback (`:92-93`) only for blank prompt (≈constant; Q-G low value, harmless). U14 Warranty has no artifact/source hash; called from `data/repository/WarrantyTrackerRepository.kt:465`.
9. Inline `cat_${it.id}` (`CloudReceiptItemCategorizationService.kt:549`) is not a hash; G17 doesn't flag it; a future G15 regex must not.
10. CA computes `shouldRedact` without `canUseCloudFor` (`CategorizationAssistInputBuilder.kt:40`, `:83`) → null hasher on on-device-only settings still routes CA to fallback (spec §11 covers).

## 1. Segments (by path)
Privacy/cloud domain `M/domain/privacy/**`, `M/data/privacy/**`; AI domain `M/domain/ai/**`; AI providers `M/data/ai/provider/**`; DI `M/di/AiModule.kt`, `M/di/PrivacyModule.kt`; Assistant/Financial Query (ai-fixes "Segment 20", FQI builder owner); scripts/CI guards `scripts/`.

## 2. Production files
**New (domain/privacy):** `CloudAliasScope.kt`, `CloudAliasKind.kt`, `CloudAliasSession.kt`, `CloudAliasState.kt`, `CloudAliasUnavailableReason`, `CloudAliasFactory.kt` (`@Inject`/`@Singleton`, wraps `InstallationSecretHasher`); `CloudAliasRouting.kt` (`mustUseDeterministicFallback(state)`); `localLogHash` in `M/domain/common/` ("MUST NOT in cloud payload"). `Hashing.kt` stays (out-of-scope callers: `BankApiIntegration.kt:431,453,473`, `SafeEventMetadata.kt:58`, `SourceLinkEventMetadataBuilder.kt:92`, `DefaultCloudPayloadPolicy`, `RedactionSanitizer`, 5 builders).
**Edited:**
- `domain/privacy/InstallationSecretHasher.kt` KDoc only (`:7-10`, `:25-26`, `:31-35`); `versionedPseudonym` (`:62-65`) stays for Q-G callers via factory or becomes internal.
- `domain/privacy/RedactionSanitizer.kt`: `DefaultRedactionSanitizer` takes factory/session (OD-H7); drop `sha256Prefix` import (`:6, 17`); bound `AiModule.kt:183-187`; only caller CA (`:44, 91, 204`).
- `domain/ai/usecase/ReviewExplanationInputBuilder.kt:44, 56` SINGLE scope `review_explanation`; drop import `:8`; ctor +factory.
- `DedupeJudgeInputBuilder.kt:164-169, 186-189` MULTI; delete private SHA helper + `MessageDigest` import `:18`.
- `ReceiptItemCategorizationInputBuilder.kt:90-93, 95-99, 115, 118-121` (`cat_`, merchant, `item_unknown`); delete helper + import `:14`.
- `CategorizationAssistInputBuilder.kt:200-215`, `:44, 91, 131-132, 196, 204` MULTI, one session per `build` overload.
- `FinancialQueryInterpretationInputBuilder.kt:120-132` + UNAVAILABLE content (spec §4) — **ai-fixes C1 rewrites this file** (ctor → resolver, `priorUserQueries`, history) → must come AFTER C1.
- Input models (`cloudAliasState`, default `NOT_APPLICABLE`): `DedupeJudgeInput`, `ReceiptItemCategorizationInput` (only if OD-H8 rejected), `CategorizationAssistInput`, `FinancialQueryInterpretationInput`, `ReviewExplanationInput` (if needed) in `M/domain/ai/model/{AiModels,CaptureAssistModels,FinancialQueryModels,ReceiptItemCategorizationModels}.kt` (which-in-which unconfirmed; `FinancialQueryModels.kt:118` ai-fixes C1..C4 hot file).
- `domain/ai/HybridRouter.kt:47-70`: optional `aliasStateOf: (TInput) -> CloudAliasState`; UNAVAILABLE → `fallbackFn` once + one bounded log line.
- `HybridQueryInterpretationService.kt:22-29`, `HybridCategorizationAssistService.kt:22-29`, (only if OD-H8 rejected) `HybridReceiptItemCategorizationService.kt:21-28`: wiring.
- `HybridDedupeJudgeService.kt:46-72`: pre-dispatch UNAVAILABLE → `noOpDedupeJudgeService` (removes both cross-route failovers for that input); bounded Timber line without `e.message`; `errorMessage` helper (`:94-106`) untouched.
- `CloudWarrantyExtractionService.kt:300-304`, `:392-396` (delete helper, import `:34`), `:255, :293` → `localLogHash`; ctor +factory (default-param); `AiModule.kt:227-234` provider +factory. ai-fixes keeps this class unchanged (deletion deferred, A6) → no conflict.
- `CloudReceiptItemCategorizationService.kt`: delete `sanitizeCloudText` (`:523-540`) + helper (`:581-584`) + import `:40`; `:328, :436` → `localLogHash`; `EMAIL/IBAN/CARD/PHONE/LONG_NUMBER_REGEX` (companion `:589+`) remove if unused (verify).
- `data/ai/provider/internal/CloudPiiSanitizer.kt:93, 102` + `data/privacy/DefaultCloudPayloadRedactor.kt:67`: Q-G scope `pii_redactor`; drop `versionedPseudonym` imports (`CloudPiiSanitizer.kt:4`, `DefaultCloudPayloadRedactor.kt:9`); `hashPayload` `MessageDigest` stays allowlisted.
- `DashboardBriefingPromptFormatter.kt:82, 98, 118` call `sanitizeMerchant` (category/description as "merchant"): no code change; tokens change under Q-G.
**Not touched:** `AiArtifactSourceHash.kt` (raw `MessageDigest` `:255` out of guard scope), `DefaultSensitiveHashingService`, `DefaultCloudPayloadPolicy` (allowlist only), `KeystoreInstallationSecretHasher`.

## 3. Legal paths
- Cloud alias generation: builder/service → `CloudAliasFactory.session(scope).alias(kind, value)` → `InstallationSecretHasher.hmacPrefix` — **new**, `LEGAL_PATHS.md` Privacy/Cloud AI (`:375`; block shared with ai-fixes C1/B1a/B2a/B2d → serial edits).
- UNAVAILABLE multi-alias dispatch → `HybridRouter` / `HybridDedupeJudgeService` → fallback (NoOp/null) — **new**; changes dedupe failover contract (`HybridDedupeJudgeService.kt:54-70`); FORBIDDEN lines per spec §9.
- Local log correlation hash (`localLogHash` → Timber only) — **new**.
- No write-path change (no Room/DAO/expense/receipt writes); artifact writes via `AiArtifactRepository.upsert` unchanged (`JudgePendingReviewDuplicateUseCase.kt:95, 101, 116`; `SuggestCategoryFallbackUseCase.kt:181`).

## 4. Downstream consumers (from source)
FQI builder → `InterpretFinancialQueryUseCase` → `HybridQueryInterpretationService` → `Cloud`/`OnDeviceQueryInterpretationService`, fallback `NoOpQueryInterpretationService`; alias→raw maps consumed there and in `ExecuteFinancialQueryUseCase` (empty-map consequence re-verify S0). CA → `SuggestCategoryFallbackUseCase` (cache compare `:146-158`). Dedupe → `JudgePendingReviewDuplicateUseCase` (`forDedupeJudge` `:59`, compared `:66`). RE → `ExplainPendingReviewUseCase` (spec `:70`, not re-read). RI → `CategorizeReceiptItemsUseCase` (`forReceiptItemCategorization` written `:160`, no compare). Warranty → `WarrantyTrackerRepository:465` (no artifact).

## 5. DAOs, schema, workers, permissions
Schema **NONE**, no version bump/migration/snapshot. Persisted source hashes change content only → cache miss at `forDedupeJudge`, `forReviewExplanation`, `forReviewCategorizationFallback`; audit `payloadHash` never compared. Workers: none touched (builders run from use cases, some worker-invoked); new suspend paths must not swallow CE (FG-10); `HybridDedupeJudgeService.safeExecute` already rethrows. Permissions: none. Privacy surface: alias token content, Timber/diagnostics (`PRIVACY_FAIL_CLOSED` + `CloudAliasUnavailableReason.name` only), audit `payloadHash` value. Keystore keys not exported/backed up.

## 6. Tests
**Stop-and-ask (assertion changes):**
- `T/domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt`: `build creates reversible alias maps when redaction is enabled` `:112-162` (literal `sha256Prefix` aliases `:136-139`, `:160-161`); second privacy test `:164-200` (`:194-195`). **Collides with ai-fixes C1** (retargets `:54-97`, `:164-190`, ctor) → second lander re-baselines.
- `T/domain/ai/usecase/ReviewExplanationInputBuilderTest.kt:34-41`, `:65-77` (4 literal assertions).
- `T/scenarios/PrivacyGateContractTest.kt:120-149` (`redaction sanitizer removes sensitive data`): `:135-136` exact sha equality → fake-hasher expectation; `:139-148` stay; ctor `DefaultRedactionSanitizer()` `:123` changes.
- Likely Q-G (not read): `T/data/privacy/KeystoreInstallationSecretHashingTest.kt` comparisons to `hasher.versionedPseudonym(...)` (candidates `:65-73, :113-141, :152-172, :209, :223, :232`); grep `merchant_v`/`merchant_redacted` in `PrivacyBehavioralRegressionTest`, `P8PrivacyFixesTest`, `CloudProviderPreparedPayloadTest`, `CloudPayloadPolicyTest` at S0. Keep `CloudPiiSanitizer(InstallationSecretHasher)` to minimise churn.
**Ctor-only (prefix assertions still pass):** `DedupeJudgeInputBuilderTest` (ctor `:42, :296`; startsWith `merchant_` `:327-330`), `CategorizationAssistInputBuilderTest` (ctor `:48-53, :228-233`; `:113-114, :253`), `ReceiptItemCategorizationInputBuilderTest` (ctor `:40`; `cat_` `:87-89`), `InputBuilderRedactionPolicyTest` (ctor `:121`; `cat_` `:59-61`). The 7 critique files (U10, inferred) = FQI, RE, PrivacyGateContract, DedupeJudge, CA, RI, InputBuilderRedactionPolicy tests.
**Provider tests (ctor only if signatures change; use trailing default):** `CloudWarrantyExtractionServiceTest.kt:38, 85, 139, 186`, `CloudProviderTransportPayloadTest.kt:161`. `CloudReceiptItemCategorizationServiceTest.kt:177, 226-239` unaffected.
**Extend:** `T/domain/ai/HybridRouterTest.kt` (default + UNAVAILABLE dispatch), `T/data/ai/provider/HybridRouterIntegrationTest.kt`, `HybridServiceDelegationTest.kt`; `HybridDedupeJudgeService` test (locate at S0; AE-H8 both-way failover); `Cloud*PreparedPayloadTest`, `JudgePendingReviewDuplicateUseCase` tests (AE-H7 cache miss once), `SuggestCategoryFallbackUseCase` tests.
**New:** `CloudAliasFactoryTest`, `CloudAliasSessionTest` (collision AE-H9, memoisation, state machine), `CloudAliasRoutingTest`, `LocalLogHashTest`, test fakes `TableHasher`/`HmacTestHasher` (shared test-support), null-hasher test per MULTI builder, planted-violation tests in `scripts/test_verify_privacy_boundaries.py`.

## 7. Guard design
- **Home: registered guard `privacy`** (`scripts/verify_privacy_boundaries.py`, ratchet, baseline `config/baselines/privacy.json`); new rules **G15..G18**, strict-zero, explicit in-script allowlist (registry has `allowlist: None` for `privacy` → separate YAML needs `guard_registry.py` + `allowlist_compliance` changes; choose constant + justification strings, or new allowlist file registered in `allowlist_compliance`). Planted violations in `scripts/test_verify_privacy_boundaries.py` (registered `testManifest`). Rejected alternative: Kotlin `SourceScanningArchitectureGuardTest` (shared with ai-fixes C1/B1a/B1b/B2d/B3a).
- Rules (comments exempt): **G15** no `sha256Prefix` identifier in `domain/ai/usecase/*InputBuilder.kt`, `domain/privacy/**`, `data/privacy/**`, `data/ai/provider/**`; **G16** no `MessageDigest` in same scope except allowlist; **G17** `hmacPrefix(`/`versionedPseudonym(` only in `domain/privacy/CloudAlias*.kt`, `InstallationSecretHasher.kt`, `data/privacy/KeystoreInstallationSecretHasher.kt`; **G18** no `localLogHash(` in `*InputBuilder.kt`, `domain/privacy/**`, `data/privacy/**`.
- Allowlist (verified current uses): `DefaultCloudPayloadRedactor.kt` (`hashPayload` `:94-100`, audit content hash); `DefaultSensitiveHashingService.kt` (`:25, :34, :37`); `SensitiveHashingService.kt` (`:10, :26`); `DefaultCloudPayloadPolicy.kt` (`:3, :50, :82`, audit payload hash — owner confirm, OD-H9). Test code out of scope.
- `MessageDigest` uses outside scope (not allowlisted/touched): `Hashing.kt:15`, `AiArtifactSourceHash.kt:255`, `SuggestReceiptExtractionUseCase.kt:202`, `SavingsSweepAcceptanceCoordinator.kt:326`, `ReceiptDuplicateDetector.kt:224, 261`, `ReceiptAssetStore.kt:108, 135, 262`, `RawNotificationFingerprint.kt:26`, `PriceProtectionTracker.kt:544`, `LogSanitizer.kt:15`, `CostbackupBundle.kt:687`, `ReceiptAssetRecovery.kt:300, 381`.
- `cloud_payload`, `pii_logging`: no allowlist change; regression for new Timber lines.
- FG (`FINAL_CI_GUARD_ACCEPTANCE_GATE.md`): FG-16, FG-23 (registry + test manifest), FG-07 (no `privacy.json` growth), FG-10, FG-19, FG-20, FG-26; `allowlist_compliance` if an allowlist file is added.

## 8. Risk tier and slice cut
Overall **T3** (privacy, cloud payload, guards, cross-segment contract, cached-artifact behaviour); no schema.
| Slice | Content | Tier | Notes |
|---|---|---|---|
| S0 | Re-verify (no app edits) | — | §12 |
| S1 | Foundation: scope/kind/session/state/factory/routing predicate/`localLogHash`/hasher KDoc + fakes + unit tests | T3 | no consumers wired |
| S2 | SINGLE sites: RE builder, Warranty + `AiModule` provider, Q-G (`CloudPiiSanitizer`/`DefaultCloudPayloadRedactor`), `localLogHash` in Warranty + RI logs, delete `sanitizeCloudText` + helpers | T3 | |
| S3 | Dedupe: builder + `DedupeJudgeInput` state + `HybridDedupeJudgeService` + AE-H7/H8 | T3 | routing change |
| S4 | RI + CA + `RedactionSanitizer` + `HybridRouter` `aliasStateOf` + CA/RI state + wiring | T3 | |
| S5 | FQI builder + `FinancialQueryInterpretationInput` state + `HybridQueryInterpretationService` wiring | T3 | after ai-fixes C1 (+ assistant-follow-ups F3) |
| S6 | Guards (`privacy` G15..G18 + tests), `LEGAL_PATHS.md`, docs, final `static-guards` | T3 | last (zero residual `sha256Prefix` in scope) |

## 9. Validation (validation-runner, serial)
- S1: `compile`; targeted `"*CloudAlias*"`, `"*LocalLogHash*"`, `"*KeystoreInstallationSecretHashingTest*"`; `registered-guard -GuardId privacy`.
- S2: targeted `"*ReviewExplanationInputBuilderTest*"`, `"*CloudWarrantyExtractionServiceTest*"`, `"*CloudProviderTransportPayloadTest*"`, `"*KeystoreInstallationSecretHashingTest*"`, `"*PrivacyBehavioralRegressionTest*"`, `"*P8PrivacyFixesTest*"`, `"*CloudReceiptItemCategorizationServiceTest*"`, `"*CloudAuditProviderProvenanceTest*"`, `"*CloudPayloadPolicyTest*"`; guards `privacy`, `cloud_payload`, `pii_logging`.
- S3: `"*DedupeJudgeInputBuilderTest*"`, `"*HybridDedupeJudge*"`, `"*JudgePendingReviewDuplicate*"`, `"*HybridServiceDelegationTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`; guards `cancellation`, `pii_logging`.
- S4: `"*ReceiptItemCategorizationInputBuilderTest*"`, `"*InputBuilderRedactionPolicyTest*"`, `"*CategorizationAssistInputBuilderTest*"`, `"*PrivacyGateContractTest*"`, `"*HybridRouterTest*"`, `"*HybridRouterIntegrationTest*"`, `"*SuggestCategoryFallbackUseCase*"`, `"*CategorizeReceiptItemsUseCase*"`, `"*CloudCategorizationAssistServiceTest*"`.
- S5: `"*FinancialQueryInterpretationInputBuilderTest*"`, `"*HybridServiceDelegationTest*"`, `"*CloudQueryInterpretationServiceTest*"`, `"*OnDeviceQueryInterpretationServiceTest*"`, `"*InterpretFinancialQueryUseCaseTest*"`; guards `cloud_payload`, `pii_logging`.
- S6/exit: `registered-guard` `privacy`, `allowlist_compliance`, `guard_docs_truth`, `cloud_payload`, `pii_logging`, `cancellation`; `static-guards`; `unit-test-shard` `domain-a-m`, `data`, `architecture-contracts`; `lint`. No migration tests.
- Gates: PSG + AG (if routing predicate in `domain/ai`) + reviewer-strict on S1, S3..S6.

## 10. Sequencing and conflicts
- ai-fixes: S5 after C1 (FQI builder ctor/history rewrite; C1 retargets of FQI builder test `:54-97`, `:164-190`); `FinancialQueryModels.kt` C1–C4 hot file; `LEGAL_PATHS.md` Privacy/Cloud AI block (`:375-416`) shared with C1, B1a, B2a, B2d. Part B: B2d/B3a touch `SourceScanningArchitectureGuardTest`/`CancellationSafety` (avoided here); `HybridRouter` documented "briefing-only" in `ENGINE_INTERACTION_MAP.md` per ai-fixes plan `:129` → possible Part B edit (verify S0).
- assistant-follow-ups F3 (`workflows/active/assistant-follow-ups-plan.md`) extends FQI builder + same test → order C1 → F3 → S5, or rebase S5.
- money-correctness, nl-quick-add, manual-entry-currency: no overlap found; money-m1b adds 3 tests to `ReceiptItemCategorizationInputBuilderTest` (`workflows/active/money-m1b-20261003-handoff.md:28`) → S4 re-reads.
- Shared docs `LEGAL_PATHS.md`, `CODEBASE_SEGMENTS.md`, `DEPENDENCY_MAP.md` → serial. Internal order S1 → S6.

## 11. Open items for Gate 2
- **OD-H1** UNAVAILABLE → deterministic fallback: supported (`HybridRouter` single dispatch `:62-69`; dedupe both-way failover `HybridDedupeJudgeService.kt:54-70`; no fallback READY persisted). Deviates from Gate 1 Q-C wording ("on-device path") for MULTI flows → owner confirm.
- **OD-H2** reuse `PRIVACY_FAIL_CLOSED` (`domain/diagnostics/DiagnosticReasonCode.kt:7`; `WORKER_PRIVACY_FAIL_CLOSED` `:43` exists; `.name` used by `OperationRunRecorder`, `WorkerExecutionGuard`) — agree.
- **OD-H3** one `pii_redactor` scope (`sanitizeMerchant(raw, shouldRedact)` has no purpose param, `CloudPiiSanitizer.kt:99`) — agree.
- **OD-H4** placeholders `merchant_unknown`/`item_unknown`/`app_unknown` (today RI null merchant collides with a real "Unknown Merchant", `ReceiptItemCategorizationInputBuilder.kt:80, 96`) — agree.
- **OD-H5** keep `cat_(\d+)` (backs the `cat_<id>` fallback) — agree strongly.
- **OD-H6** frozen scope/kind ids — agree.
- **OD-H7** CA one session per `build` overload; `DefaultRedactionSanitizer` takes session/scope (tests `CategorizationAssistInputBuilderTest.kt:53, 233`, `PrivacyGateContractTest.kt:123-149`; binding `AiModule.kt:183-187` stays).
- **OD-H8 (new)** RI null hasher → existing `cat_<id>` fallback (empty `cloudCategoryOptions`, `merchant_redacted`) instead of UNAVAILABLE: no routing change, no new state, on-device keeps working; leaks numeric category ids (not names) → owner accepts.
- **OD-H9 (new)** allowlist seed for `DefaultCloudPayloadPolicy`/`DefaultSensitiveHashingService`/`SensitiveHashingService`, or narrow scope.
- **OD-H10 (new)** FQI cap-before-alias vs alias-all-then-cap (Keystore cost).
- **OD-H11 (new)** artifact `mode`/provider diagnostics when fallback ran.
- **OD-H12 (new)** guard home = `privacy` registered guard; rules G15..G18.

## 12. Re-verify at S0
FQI builder/model lines after ai-fixes A/C1; `getRecentMerchantNames()` size/order and FQI downstream empty-map use; locations of `DedupeJudgeInput`/`CategorizationAssistInput`/`ReviewExplanationInput`/`FinancialQueryInterpretationInput` + test ctors; `ExplainPendingReviewUseCase.kt:70` compare; `KeystoreInstallationSecretHashingTest` exact pseudonym assertions, grep `merchant_v`/`merchant_redacted`; whether `verify_privacy_boundaries.py` rules take an allowlist without registry change; `HybridDedupeJudgeService` test name; `ENGINE_INTERACTION_MAP.md` AI rows; `CODEBASE_SEGMENTS.md` segment numbers.

## 13. Unverified
U6 stored chat history echoing aliases; U7 aliases excluded from backup/export; U12 category name uniqueness; U13 BYOK linkability; U10 file list inferred from `sha256Prefix` uses; `ExplainPendingReviewUseCase.kt:70` (spec claim, not confirmed; `SuggestCategoryFallbackUseCase.kt:152` confirmed `:146-158`); `DefaultCloudPayloadRedactor` tokens feeding no compared artifact not re-grepped.
