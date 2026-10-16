# Feature plan: cloud-alias-hashing (increment 1)

Author: feature-planner (read-only). Persisted by the orchestrator. Date 2026-10-16. Status: **APPROVED 2026-10-16** (owner: "Recommended and approved" — PD-1..PD-6 accepted; spec amendment set incl. AE-H17..H20 + G15..G18; G2-9 in-script allowlist; S5 gate C1 → F3 → S5; shared-doc serialisation). PARKED until after Release 1.0.
Basis: `docs/features/cloud-alias-hashing/impact-map.md` (trusted; lines not re-checked), `gate2-decisions.md` (G2-1..G2-16 DECIDED = Recommended), `domain-spec.md` (INV-H1..H17, AE-H1..H16, OD-H1..H7), `brief.md` v2 (Q-A..Q-J).
`M` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`. `VR <profile> …` = `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <profile> …` (validation-runner only, serial, poll; missing/stale ≠ PASS). `-TestFilter` takes **one** pattern per run (runner checks `^[A-Za-z0-9_.$*?\-]+$`, no commas) → each listed filter = its own serial run. PSG = privacy-security-guardian, RS = reviewer-strict, AG = architecture-guardian. Max 2 review + 2 debug iterations per slice → PARTIAL/BLOCKED.

- Risk tier **T3 every slice** (G2-16). Schema **NONE** (no version bump/migration/snapshot/`migration-tests`/DAO/SQL; artifact writes stay on `AiArtifactRepository.upsert`).
- Dependencies: Release 1.0 shipped; **S5 waits for ai-fixes C1 green, then assistant-follow-ups F3** (C1 → F3 → S5). Other slices independent of ai-fixes except shared docs (`LEGAL_PATHS.md` Privacy/Cloud AI `:375-416`, `CODEBASE_SEGMENTS.md`, `DEPENDENCY_MAP.md`, `ENGINE_INTERACTION_MAP.md`) → serialised via orchestrator doc lock.
- Guard rules **G15..G18** in the existing `privacy` guard (spec G1..G4 renamed, G2-12).

## Planner deviations (owner confirms)
- **PD-1** Split S4 → **S4a** RI builder only (no `ReceiptItemCategorizationModels.kt`, `HybridReceiptItemCategorizationService.kt`, `CategorizeReceiptItemsUseCase.kt`) + **S4b** CA + `RedactionSanitizer` + `HybridRouter.aliasStateOf` + CA wiring (no shared files). Alt: one S4.
- **PD-2** G2-11 attribution: `JudgePendingReviewDuplicateUseCase` (S3) and `SuggestCategoryFallbackUseCase` (S4b) choose saved mode/provider by **calling** `CloudAliasRouting.mustUseDeterministicFallback(input.cloudAliasState)` (no re-implemented rule), reusing an existing fallback mode/provider value. FQI keeps no artifact (S0 confirms). No fallback value in the type → stop.
- **PD-3** `HybridRouter.aliasStateOf` lands in S4b (CA first user); Query wired S5; RI hybrid never wired (G2-2); Dashboard/ReviewExplanation keep default `NOT_APPLICABLE`.
- **PD-4** RI on key missing/collision: builder reads session state; UNAVAILABLE (either reason) → empty `cloudCategoryOptions` + `merchant = merchant_redacted` → existing `cat_<id>` fallback (`CloudReceiptItemCategorizationService.kt:542-552`/`:576-588`) unchanged; null merchant → `merchant_unknown` (no hasher call). No input state, no routing change, no new log line.
- **PD-5** G2-10 FQI order: normalise N(v), drop blanks, `distinct` by N(v), `take(100)`, **then** alias.
- **PD-6** Optional S6 split if S5 still blocked when S4b closes: S6a G16..G18 + allowlist + planted tests + docs for S1–S4b; S6b G15 + FQI docs after S5. Default one S6, last.

## Spec amendment set (S0, domain-modeler, docs only)
- G2-1: §0 D3 + Q-C → MULTI flows (assistant, dedupe, CA) go to deterministic fallback, not on-device.
- G2-2: §2 RI rows + §4 "RI: `cloudCategoryOptions`" → RI UNAVAILABLE = id-only fallback; INV-H9/H10 exclude RI; §4 routing list + §12 name only 2 `HybridRouter` users (Query, CA).
- G2-9: G16 allowlist 4 files with written reasons (`DefaultCloudPayloadRedactor.kt` `hashPayload`, `DefaultSensitiveHashingService.kt`, `SensitiveHashingService.kt`, `DefaultCloudPayloadPolicy.kt`); raw-prompt audit hash → PSG backlog.
- G2-10: FQI dedupe+cap before alias (PD-5). G2-11: new INV — fallback ran → saved artifact shows fallback mode/provider. G2-12: rename G1..G4 → G15..G18 in §7, INV-H1/H6/H15/H17, AE-H14.
- New AEs: **AE-H17** RI with Null → options `[]`, merchant `merchant_redacted`, no state; route CLOUD → 1 cloud call, prompt lists only `cat_<id>` (no names); route ON_DEVICE → 1 on-device call. **AE-H18** FQI 250 recent merchants (dups) → merchant hasher calls == min(100, distinct N(v)). **AE-H19** dedupe or CA with Null → RUNNING/FAILED artifact records fallback mode/provider. **AE-H20** RI forced collision `7:Food` vs `9:Food` → options `[]`.

## Slice table
| ID | Title | Prod | Tests | Depends | Reviewers | FG |
|---|---|---|---|---|---|---|
| S0 | Pre-flight, baselines, spec amendment (no `app/` edits) | 0 | 0 | Release 1.0 | — | — |
| S1 | Foundation (types, factory, session, routing predicate, `localLogHash`, KDoc) | 8 (7 new) | 5 new + fakes | S0 | PSG + RS + AG | 07, 16, 19, 26 |
| S2 | SINGLE sites (RE, Warranty), Q-G, dead code, local log hash | 6 | 2 retarget + 3–4 ext/new | S1 | PSG + RS + AG (DI) | 07, 16, 19, 20, 26 |
| S3 | Dedupe: builder, input state, hybrid pre-dispatch, attribution | 4 | 1 ctor + 3 ext | S2 | PSG + RS + AG (routing) | 07, 10, 16, 19, 20, 26 |
| S4a | RI builder (G2-2 id-only fallback) | 1 | 2 ctor + ext | S3 (order) | PSG + RS | 07, 16, 19, 26 |
| S4b | CA, `RedactionSanitizer`, `HybridRouter.aliasStateOf`, CA wiring, attribution | 5–6 | 1 retarget + 5 ext | S4a | PSG + RS + AG (routing, DI) | 07, 10, 16, 19, 20, 26 |
| S5 | FQI builder, input state, Query wiring | 3 | 1 retarget + 3 ext | S4b + ai-fixes C1 + AF F3 | PSG + RS + AG | 07, 10, 16, 19, 20, 26 |
| S6 | `privacy` guard G15..G18, planted tests, docs closeout | 1 script | 1 py ext | S5 (or PD-6) | PSG + RS | 07, 16, 23, 26 |
| Z | Bundle gate + handoff | docs | — | S6 | fresh-session RS (final) + PSG | all |
Strict order S0 → S1 → S2 → S3 → S4a → S4b → S5 → S6 → Z (S2/S3/S4a share no files but share docs + the single runner). Cross-slice files: `M/di/AiModule.kt` (S2; S4b only if needed), `HybridServiceDelegationTest` (S3, S4b, S5), `LEGAL_PATHS.md` (S2, S3, S4b, S6), `DEPENDENCY_MAP.md` (S2..S5).

## Slices
### S0 — Pre-flight (no `app/` edits)
- Re-verify (impact-map §12 +): locations/test ctors of `DedupeJudgeInput`, `CategorizationAssistInput`, `FinancialQueryInterpretationInput`; `HybridDedupeJudgeService` test name; `ExplainPendingReviewUseCase.kt:70` compare; artifact mode/provider field type, existing fallback value, readers (G2-11); on-device RI with empty `cloudCategoryOptions` (G2-2 premise); FQI query/history replacement + alias→raw maps vs names beyond the 100 cap (PD-5); companion regexes in `CloudReceiptItemCategorizationService` used?; permitted logger in `domain/ai/HybridRouter`; `CloudAliasFactory` constructor-injectable without `PrivacyModule` edit; shared test-support package for fakes; exact `merchant_v`/`merchant_redacted`/pseudonym assertions in `KeystoreInstallationSecretHashingTest`, `PrivacyBehavioralRegressionTest`, `P8PrivacyFixesTest`, `CloudProviderPreparedPayloadTest`, `CloudPayloadPolicyTest`, DashboardBriefing tests (classify approved retarget vs stop-and-ask); residual `sha256Prefix`/`MessageDigest`/`hmacPrefix(`/`versionedPseudonym(` in G15..G17 scope mapped to slice or G2-9 allowlist; `verify_privacy_boundaries.py` in-script allowlist without `guard_registry.py`/`allowlist_compliance` change; money-m1b status (RI builder test); ai-fixes C1 + AF F3 status; `ENGINE_INTERACTION_MAP.md` AI rows + `CODEBASE_SEGMENTS.md` numbers.
- Baselines: record `config/baselines/privacy.json` counts + any in-scope `KNOWN_VIOLATIONS`; `VR registered-guard -GuardId privacy`, `cloud_payload`, `pii_logging`; `VR unit-test-shard -Shard architecture-contracts`; record run IDs; red pre-state → stop (not this feature's job).
- Spec amendment set (domain-modeler). Done when: re-verify notes in handoff, baselines recorded, amendments merged, owner plan gate passed.

### S1 — Foundation
- INV-H2..H8, H13, H14 (bounded `CloudAliasUnavailableReason`), H15 (`localLogHash` KDoc), §4 session state machine (sticky UNAVAILABLE, MARKED only in SINGLE, no `MARKED→KEYED`); G2-3, G2-5, G2-7. No consumers wired → releasable.
- Source: NEW `M/domain/privacy/` `CloudAliasScope.kt`, `CloudAliasKind.kt`, `CloudAliasState.kt` (+ `CloudAliasUnavailableReason`), `CloudAliasSession.kt`, `CloudAliasFactory.kt` (`@Inject`/`@Singleton`), `CloudAliasRouting.kt`; NEW `M/domain/common/LocalLogHash.kt`; `InstallationSecretHasher.kt` KDoc only (§8 wording `:7-10`, `:25-26`, `:31-35`).
- Tests: NEW `T/domain/privacy/CloudAliasFactoryTest.kt` (encoding `cah1|scope|kind|value`, `id:name` for CATEGORY_OPTION, INV-H3 regex, never `matchEntire` `cat_(\d+)`, blank → placeholder 0 hasher calls, Null never falls back to hash); `CloudAliasSessionTest.kt` (memoisation, collision → UNAVAILABLE(ALIAS_COLLISION) AE-H9, intermittent AE-H15 session level); `CloudAliasRoutingTest.kt`; `CloudAliasScopeKindTest.kt` (frozen ids); `T/domain/common/LocalLogHashTest.kt`; fakes `TableHasher`, `HmacTestHasher`, `NullInstallationSecretHasher` (package per S0); AE-H2 factory level K1/K2.
- Docs: `CODEBASE_SEGMENTS.md` privacy (`CloudAlias*`) + `domain/common` (`localLogHash`).
- Validation: `VR compile`; `VR targeted-unit-test -TestFilter "*CloudAlias*"`; `"*LocalLogHashTest*"`; `"*KeystoreInstallationSecretHashingTest*"`; `VR registered-guard -GuardId privacy`.
- Done when: green; PSG/RS/AG approve API shape; no consumer diff.

### S2 — SINGLE sites, Q-G, dead code, local log hash
- INV-H6, H7, H11, H15, H16 (RE one-time miss), H17 (`sanitizeCloudText` + Warranty/RI-service helpers removed); AE-H5, H6, H13, H15 (RE part: 2 cloud calls); G2-4, G2-5.
- Source: `M/domain/ai/usecase/ReviewExplanationInputBuilder.kt` (scope `review_explanation`, ctor +factory, drop import `:8`); `M/data/ai/provider/CloudWarrantyExtractionService.kt` (default-param factory, delete helper + import `:34`, `:255`/`:293` → `localLogHash`); `M/di/AiModule.kt` Warranty `@Provides` `:227-234` only; `M/data/ai/provider/internal/CloudPiiSanitizer.kt` (scope `pii_redactor`, factory internal, keep `InstallationSecretHasher` ctor); `M/data/privacy/DefaultCloudPayloadRedactor.kt` (`:67` → `pii_redactor`; `hashPayload` stays); `M/data/ai/provider/CloudReceiptItemCategorizationService.kt` (delete `:523-540`, `:581-584`, import `:40`; `:328`/`:436` → `localLogHash`; regexes only if S0 found unused).
- Tests: **approved retarget (G2-13)** `ReviewExplanationInputBuilderTest.kt` `:34-41`, `:65-77` (+ INV-H3 regex, ≠ public SHA); `KeystoreInstallationSecretHashingTest.kt` exact-pseudonym checks only. Extend `CloudWarrantyExtractionServiceTest` (AE-H5), `ExplainPendingReviewUseCase` test (AE-H15 RE). NEW `T/data/ai/provider/internal/CloudPiiSanitizerAliasScopeTest.kt` (AE-H13). Ctor-only if signature breaks: `CloudProviderTransportPayloadTest.kt:161`.
- Legal path: builder/service → `CloudAliasFactory.session(scope).alias(kind, value)` → `hmacPrefix`; null key → SINGLE marker, call proceeds.
- Docs: `LEGAL_PATHS.md` "Cloud alias" + "Local log correlation hash" (serialised); `DEPENDENCY_MAP.md` edges.
- Validation (one run per filter): `*ReviewExplanationInputBuilderTest*`, `*ExplainPendingReview*`, `*CloudWarrantyExtractionServiceTest*`, `*CloudPiiSanitizer*`, `*CloudProviderTransportPayloadTest*`, `*KeystoreInstallationSecretHashingTest*`, `*PrivacyBehavioralRegressionTest*`, `*P8PrivacyFixesTest*`, `*CloudReceiptItemCategorizationServiceTest*`, `*CloudAuditProviderProvenanceTest*`, `*CloudPayloadPolicyTest*`, `*CloudProviderPreparedPayloadTest*`, `*DashboardBriefing*`; `VR registered-guard` `privacy`, `cloud_payload`, `pii_logging`.
- Done when: green; only approved retargets; no log line carries a raw name.

### S3 — Dedupe
- INV-H9, H10 (pre-dispatch removes both failovers when UNAVAILABLE), H13, H14, H16, H17 (Dedupe helper); AE-H1 (dedupe half), H7, H8, H19; G2-1, G2-11.
- Source: `DedupeJudgeInputBuilder.kt` (MULTI scope `dedupe_judge`; delete helper `:186-189` + `MessageDigest` import `:18`); `DedupeJudgeInput` model (S0 location) `cloudAliasState` default `NOT_APPLICABLE`; `HybridDedupeJudgeService.kt` (shared predicate → `noOpDedupeJudgeService` once; one Timber line `PRIVACY_FAIL_CLOSED` + `reason.name`; no `e.message`; `errorMessage` untouched); `JudgePendingReviewDuplicateUseCase.kt` attribution only (PD-2).
- Tests: `DedupeJudgeInputBuilderTest` (ctor + null-hasher + collision); `HybridDedupeJudgeService` test (AE-H8 both routes; KEYED/NOT_APPLICABLE failover regression; bounded log); `JudgePendingReviewDuplicateUseCase` test (AE-H7 one-time miss then hit; AE-H19); `HybridServiceDelegationTest` regression.
- Legal path: `upsert` unchanged; new "UNAVAILABLE MULTI → deterministic fallback"; CE rethrown (FG-10).
- Docs: `LEGAL_PATHS.md` "Hasher unavailable (MULTI)" for dedupe + FORBIDDEN cloud/on-device/failover; `ENGINE_INTERACTION_MAP.md` dedupe failover row.
- Validation: `*DedupeJudgeInputBuilderTest*`, `*HybridDedupeJudge*`, `*JudgePendingReviewDuplicate*`, `*HybridServiceDelegationTest*`, `*CancellationSafetyArchitectureGuardTest*`; `VR registered-guard` `cancellation`, `pii_logging`, `privacy`.
- Done when: green; AG signs off routing contract.

### S4a — Receipt items (G2-2, content only)
- INV-H3, H6, H7, H13 (collision → id-only fallback), H17 (RI builder helper); AE-H10, H12, H17, H20; G2-2, G2-5, G2-6.
- Source: `ReceiptItemCategorizationInputBuilder.kt` only (scope `receipt_item_categorization`; placeholders `merchant_unknown`/`item_unknown`; delete helper `:118-121` + import `:14`).
- Tests: `ReceiptItemCategorizationInputBuilderTest` (ctor + AE-H10/H12/H17/H20; re-read for money-m1b first); `InputBuilderRedactionPolicyTest` ctor only; regression `CloudReceiptItemCategorizationServiceTest` (`cat_<id>` path), `CategorizeReceiptItems*`.
- RI routing/hybrid untouched; `cat_(\d+)` kept (G2-6).
- Validation: `*ReceiptItemCategorizationInputBuilderTest*`, `*InputBuilderRedactionPolicyTest*`, `*CloudReceiptItemCategorizationServiceTest*`, `*CategorizeReceiptItemsUseCase*`; `VR registered-guard -GuardId privacy`.
- Done when: green; no category name reaches cloud on the fallback path.

### S4b — Category suggestions, `RedactionSanitizer`, `HybridRouter`
- INV-H9, H10 (`HybridRouter`), H13, H14, H16 (CA one-time miss); AE-H11, H19 + CA null-hasher; G2-1, G2-8, G2-11.
- Source: `M/domain/privacy/RedactionSanitizer.kt` (thin delegate taking session; drop `sha256Prefix` import); `CategorizationAssistInputBuilder.kt` (one session per `build` overload; UNAVAILABLE → empty candidates/history); `CategorizationAssistInput` model (S0 location) + state; `M/domain/ai/HybridRouter.kt` (optional `aliasStateOf`, default `NOT_APPLICABLE`; UNAVAILABLE → `fallbackFn` once + one bounded line); `HybridCategorizationAssistService.kt` wiring; `SuggestCategoryFallbackUseCase.kt` attribution only; `AiModule.kt:183-187` only if binding must change (not expected).
- Tests: **approved retarget** `T/scenarios/PrivacyGateContractTest.kt:120-149` (`:135-136` → fake-hasher expectation; `:139-148` stay; ctor `:123`); extend `CategorizationAssistInputBuilderTest` (ctor + AE-H11 + Null), `HybridRouterTest` (default unchanged; UNAVAILABLE on CLOUD and ON_DEVICE → cloud 0, on-device 0, fallback 1), `HybridRouterIntegrationTest` + `HybridServiceDelegationTest` (CA + Dashboard/RE default regression), `SuggestCategoryFallbackUseCase` test (one-time miss, AE-H19).
- Docs: `LEGAL_PATHS.md` "Hasher unavailable" → `HybridRouter`; `ENGINE_INTERACTION_MAP.md` `HybridRouter` row.
- Validation: `*CategorizationAssistInputBuilderTest*`, `*PrivacyGateContractTest*`, `*HybridRouterTest*`, `*HybridRouterIntegrationTest*`, `*HybridServiceDelegationTest*`, `*SuggestCategoryFallbackUseCase*`, `*CloudCategorizationAssistServiceTest*`; `VR registered-guard` `privacy`, `pii_logging`, `cancellation`.
- Done when: green; AG approves routing + DI.

### S5 — Assistant query (FQI), after ai-fixes C1 + AF F3
- INV-H9, H10, H12, H13, H14; AE-H1 (FQI half), H2, H3, H4, H9, H15 (FQI), H16, H18; G2-10 (PD-5).
- Source: `FinancialQueryInterpretationInputBuilder.kt`; `FinancialQueryModels.kt` (`FinancialQueryInterpretationInput.cloudAliasState` only); `HybridQueryInterpretationService.kt` wiring.
- Tests: **approved retarget** `FinancialQueryInterpretationInputBuilderTest` (G2-13; lines re-baselined after C1/F3) + new Null/collision/cap cases; extend `InterpretFinancialQueryUseCaseTest` (AE-H3 end-to-end, NoOp = 1), `HybridServiceDelegationTest`; regression `CloudQueryInterpretationServiceTest`, `OnDeviceQueryInterpretationServiceTest`, `ExecuteFinancialQueryUseCase` empty-map case.
- Pre-step: re-run S0 FQI re-verify on post-C1/F3 tree.
- Validation: `*FinancialQueryInterpretationInputBuilderTest*`, `*InterpretFinancialQueryUseCaseTest*`, `*HybridServiceDelegationTest*`, `*CloudQueryInterpretationServiceTest*`, `*OnDeviceQueryInterpretationServiceTest*`, `*ExecuteFinancialQuery*`; `VR registered-guard` `cloud_payload`, `pii_logging`, `privacy`.
- Done when: green; zero `sha256Prefix` in G15 scope.

### S6 — Guard rules + docs closeout
- Enforce INV-H1, H6, H15, H17; AE-H14; G2-9, G2-12.
- `scripts/verify_privacy_boundaries.py`: G15..G18 per impact-map §7, strict zero, comments exempt, in-script allowlist constant (G2-9 4 files + justification strings); inline `cat_${it.id}` not flagged.
- `scripts/test_verify_privacy_boundaries.py` planted: must-fail `"merchant_${n.sha256Prefix()}"` in `data/ai/provider`, `MessageDigest` in non-allowlisted file, `hasher.hmacPrefix(` in an InputBuilder, `localLogHash(` in an InputBuilder; must-pass Warranty-style Timber `localLogHash(content)`, `cat_${it.id}`, allowlisted file, comment mention.
- Docs: `LEGAL_PATHS.md` FORBIDDEN lines citing G15..G18 (serialised); privacy guard rule docs (`guard_docs_truth`); final `CODEBASE_SEGMENTS.md`, `DEPENDENCY_MAP.md`, `ENGINE_INTERACTION_MAP.md` reconciliation; PSG backlog entries (Q-J, raw-prompt audit hash).
- Validation: `VR registered-guard` `privacy`, `allowlist_compliance`, `guard_docs_truth`, `cloud_payload`, `pii_logging`, `cancellation`; `VR static-guards`.
- Done when: green; `privacy.json` not grown; registry/manifest unchanged (FG-23).

### Z — Bundle gate
`VR static-guards`; `VR unit-test-shard -Shard domain-a-m`, `data`, `architecture-contracts`; `VR lint`; S6 `registered-guard` set; full `unit-tests` only if the release gate requires; no migration tests. Fresh-session RS (not a slice reviewer) final acceptance + PSG privacy sign-off. Update handoff; release-note line on the one-time AI re-check needs owner approval.

## Owner plan-gate checklist
- [ ] PD-1 (split S4), PD-2 (attribution via predicate), PD-3, PD-4 (no RI log line), PD-5 (cap order), PD-6 (S6 split option).
- [ ] Spec amendment set incl. AE-H17..H20 + G15..G18 rename.
- [ ] G2-9 allowlist (4 files) as in-script constant (no registry change).
- [ ] S5 gate: ai-fixes C1 → AF F3 → S5 (rebase instead needs explicit owner decision).
- [ ] Shared-doc serialisation with ai-fixes C1/B1a/B2a/B2d.

## New stop-and-ask items
1. Any growth in `config/baselines/privacy.json` (or other ratchet baseline) or `KNOWN_VIOLATIONS`.
2. Guard needs `guard_registry.py`, `allowlist_compliance` or new allowlist-file change, or scope narrowing.
3. Any test assertion change outside G2-13 (incl. `PrivacyBehavioralRegressionTest`, `P8PrivacyFixesTest`, `CloudProviderPreparedPayloadTest`, `CloudPayloadPolicyTest`, DashboardBriefing tests, money-m1b RI tests); ctor-only allowed; any deletion needs approval.
4. Keystore perf: >100 merchant hasher calls per FQI request after G2-10, main-thread/StrictMode findings, measured latency regressions; caching null/keys/aliases across requests needs approval.
5. G2-11 needs a new persisted enum value, TypeConverter or schema change.
6. Residual `sha256Prefix`/`MessageDigest`/`hmacPrefix` in guard scope mapping to no slice or allowlist entry.
7. Cap-before-alias changes which names are replaced in query/history text or resolvable via maps.
8. C1/F3 change FQI alias sites/maps away from spec §2/§4 (re-spec before S5).
9. On-device RI can't handle empty `cloudCategoryOptions` (G2-2 premise fails).
10. `HybridRouter` needs a breaking signature change or a logger not allowed in `domain/ai`.
11. Any log/diagnostic beyond `PRIVACY_FAIL_CLOSED` + `reason.name`, or a new `DiagnosticReasonCode` constant.
12. DI needs a new module/qualifier beyond the Warranty provider parameter.
13. A swallowed `CancellationException` in a touched suspend path.

## Risk register
| Risk | Mitigation |
|---|---|
| Raw name or public hash reaches a payload | AE tests per site, S6 guards, PSG per slice |
| Dedupe unjudged while key unavailable | Accepted (G2-1); AE-H8 |
| One-time cloud re-ask cost | Accepted (G2-14); AE-H7; only where cloud already allowed |
| Keystore round-trips | G2-10, AE-H18; stop item 4 |
| Collision with ai-fixes hot files (FQI builder, `FinancialQueryModels.kt`, `LEGAL_PATHS.md`) | S5 ordering; doc lock |
| Guard false positives (`cat_${id}`, comments) | Planted pass cases |
| Readers of artifact mode affected by G2-11 | S0 reader check |
| money-m1b edits to RI test | Re-read in S4a |
| Q-G token change breaks DashboardBriefing tests | S2 regression; stop item 3 |

## Deferred
Per-request key or numbered aliases (Q-A/Q-C) + on-device local tokens (OD-H1 alternative); PSG backlog local-only unsalted hashes outside guard scope (Q-J: `Hashing.kt` callers, `AiArtifactSourceHash`, …); raw-prompt audit hash `DefaultCloudPayloadPolicy.kt:50/:82` (G2-9); ai-fixes 5-builder redaction-source move (Q-E; also resolves CA/RE redacting without `canUseCloudFor`); removing the `cat_(\d+)` hallucination fallback (OD-H5, ai-fixes item); dashboard-briefing category-as-merchant quirk; RI fallback diagnostics line (PD-4); unverified U6/U7/U12/U13.
