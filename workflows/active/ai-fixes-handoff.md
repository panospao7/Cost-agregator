# ai-fixes — feature handoff

Feature id: `ai-fixes` (roadmap item 1 of the `ai-evolution` program; see `workflows/active/ai-evolution-handoff.md`)
Branch at start: release-1.0-remediation
Status: PLAN APPROVED — PARKED until Release 1.0 accepted + release-1.0-wt merged; next slice S0. DOCS-ONLY (Release 1.0 frozen).

## Origin
Owner GO 2026-10-16 ("ok start") after approving roadmap order ai-fixes → nl-quick-add → assistant-follow-ups →
briefing-grounded-signals. Scope source: `docs/features/ai-evolution/critique.md` §4 items 1-6.

## Owner decisions carried in
- Bug fixes belong inside the AI program (not a separate track); post-1.0, no reopening of the freeze.
- Item 4 warranty: option (a) — AI-found warranties always shown as suggestions confirmed by a tap; no auto-persist at ≥0.75.
- Item 5 (insight purpose): transaction insight gets its own setting/purpose; on-device Nano by default; cloud only if
  the user enabled cloud for it (default recorded by orchestrator; owner may revisit).
- Owner's phone has Gemini Nano; Nano = free on-device model for simple tasks.

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/ai-fixes/brief.md | v2 DONE (reshape pass used; further reshape → owner); v1 archived at brief-v1.md. v2: Part A T1; B1 warranty, B2 insight on-device-only, B3 notif decoupling cloud-route-only; 1 owner Q; 12 domain Qs. v1 notes: DONE (feature-strategist) — 6 items; recommends PURSUE, item 3 reshaped (no auto re-show, only regen on request); AICore foreground-only (BACKGROUND_USE_BLOCKED) reshapes item 2; expected T3 items 1,2,4,5 / T1 item 6; no schema change expected; 6 owner Qs, 7 domain Qs |
| 2 Critique | docs/features/ai-fixes/critique.md | DONE (feature-critic) — RESHAPE: split A (T1 docs/strings) / B (T3 items 4,5,1) / C (on-device briefing = separate feature); item 3 defer; insight on-device-only (no RP-15 amendment); item 4 retargeted to upsertReturnWindowForReceipt cloud path |
| 3 Domain rules | docs/features/ai-fixes/domain-spec.md | DONE (domain-modeler) — 29 INV (INV-29 conditional on OD-2=A), 51 AE (AE-51 conditional), 2 OD; schema verdict NONE (no Room change; 1 new DataStore key + one-time migration); stop-and-ask test list §8 |
| 4 UX flows | docs/features/ai-fixes/ux-flows.md | DONE (ux-flow-designer) — no new screens; insight row under briefing; hide warranty-extraction toggle (static caption); proactive relabel; expired-unconfirmed card; bounded error keys + snackbar (mutationError never shown today); 5 UX-Qs |
| 5 Impact & tier | docs/features/ai-fixes/impact-map.md | DONE (impact-mapper) — overall T3 (A T1; B1/B2/B3 T3); schema NONE; guards: SourceScanning:408 allowlist, 3 new source-scan guards, GR-08 policy rows; conflicts with release-1.0-wt R24/R21/R23; loan shared hot files |
| Gate 2 sheet | docs/features/ai-fixes/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 6 Slice plan | workflows/active/ai-fixes-plan.md | DONE (feature-planner attempt 2; attempt 1 hit 80-turn limit) — 13 slices S0, A, B1a-c, B2a-e, B3a-b, Z; T3; schema NONE; N1-N6 new stop-and-asks; APPROVED 2026-10-16 |

## Human decisions
- 2026-10-16 Gate 1 = RESHAPE. Owner answer (verbatim): "recommended" — accepts all orchestrator recommendations:
  1. Insight: on-device (Nano) only, no cloud route, no RP-15 amendment; ON by default for new installs (on-device);
     existing users inherit their `dashboardBriefingEnabled` value. Cloud opt-in = possible later separate amendment.
     (Supersedes the earlier recorded default "cloud only if user enabled cloud for insights".)
  2. Cloud-derived return windows: tap-to-confirm before saving; no cloud call when a warranty already exists;
     delete the unused ≥0.75 auto-accept code path. `warrantyExtractionEnabled` default → off.
  3. Pending (unconfirmed) warranties: auto-expire after their end date.
  4. Local warranty review drafts dropped when cloud gate denied (`AutoCreateWarrantyFromReceiptUseCase.kt:128-131`): fix IN this bundle.
  5. On-device briefing: SPLIT OUT as new roadmap item `on-device-briefing` after this bundle; ai-fixes leaves on-device users' briefing behaviour unchanged.
  6. Dismissed suggestions: DEFERRED to A3 (learn-from-corrections).
  Shape accepted: Part A (T1 docs/strings/raw e.message incl. WarrantyTrackerViewModel.kt:195) + Part B (T3: warranty → insight → notification decoupling, cloud route only).
  Single allowed strategist reshape pass now runs with these answers.
- 2026-10-16 Brief v2 confirmation. Owner (verbatim): "recommended and approved"
  - Brief v2 CONFIRMED (Gate 1 closed → GO on v2).
  - Q1 = (a): retire the cloud call from the return-window path for now; return windows use local merchant defaults only;
    `warrantyExtractionEnabled` default off; FEATURES.md states warranty AI not active. No return-window UI, no schema change.
  - Stop-and-ask APPROVED IN PRINCIPLE: delete tests that only exercise the dead ≥0.75 path
    (WarrantyTrackerRepositoryTest :641 + ~:665-791 band tests); retarget :127, :171, :217, :347 to the production path.
    Exact list returns to owner at plan approval (FG-19).
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "do the recommended" → all G2-1..G2-16 = recommended
  (docs/features/ai-fixes/gate2-decisions.md): OD-1=(a) Policy A guard; OD-2=(A) decouple WarrantyExpirationWorker in bundle;
  APPROVED stop-and-asks: SourceScanning:408 allowlist (DailyBriefingWorker + WarrantyExpirationWorker), 3 new source-scan
  guards, GR-08 policy row edits + signature regeneration, deletion of WarrantyTrackerRepositoryTest :641/:673/:705/:735/:767
  with listed retargets; Greek+Spanish for all new/changed keys (no lint-baseline refresh); UX-Q-1/2/3 yes; FEATURES.md :911 in;
  recommendation-job timeout 5 s / insight 3 s / GenAI errors → PROVIDER_ERROR; ship after Release 1.0 + release-1.0-wt merge
  (R24 precedence on WarrantyTrackerViewModel); fold AutoCreate…:110-115/:151 log leak into B1; §15 adjacent items → follow-ups;
  tier T3 accepted.
- 2026-10-16 Gate 3 PLAN APPROVED. Owner (verbatim): "reccomended,approved". Recorded:
  1. 13 slices + order approved (S0 → A → B1a → B1b → B1c → B2a → B2b/B2c → B2d → B2e → B3a → B3b → Z; OD-2 in B3a).
  2. Spec §8.1 confirmed: cloud discard diagnostic (:705) unreachable after Q1(a); deletion loses no production coverage.
  3. R7 Pattern E signature regeneration in B1a: R7 precedent — run LAST in B1a on a tree where only B1a is uncommitted,
     after architecture-guardian supplies the sanctioned procedure; `--verify` via registered-guard db_artifact_sync;
     any drift beyond WarrantyTrackerRepository rows (incl. seed-row pin changes beyond those rows) → stop (N2).
  4. Expired WarrantyExpirationWorker broad-catch allowlist entry: remove after fix, never extend (N1 if impossible).
  5. Non-blocking: owner may measure Nano warm-up vs 3 s timeout later.
  New stop-and-asks N1-N6 stand.

## Open risks
- Worker gating changes (DailyBriefingWorker, PrivacyRuntimeWorkerPolicy, WorkerGuardVerifier, FG worker guards) → likely T3.
- AICore/ML Kit GenAI may be foreground-only → on-device briefing may need "generate on Home open" instead of midnight worker.
- Shipped-behaviour changes (notification decoupling, warranty tap-to-confirm) — owner-approved in principle.
- ~~Item 5 needs an explicit RP-15 amendment~~ VOID (Gate 1: insight on-device-only; RP-15 unchanged).
- Stop-and-ask: B1 dead-path deletion would delete/retarget WarrantyTrackerRepositoryTest :641 and :665-791 band tests; retarget :127,:171,:217,:347 (FG-19).
- Background notification-captured insights cannot use Nano (BACKGROUND_USE_BLOCKED) → skip/defer/template decision needed.

## Next action
When Release 1.0 is accepted and release-1.0-wt is merged into bug-fixes: run S0 pre-flight (branch feature/ai-fixes from bug-fixes; D-1..D-10; baseline validations via validation-runner). No app/ edits before that.

## New facts from critique (affect scope)
- Production cloud warranty extraction only feeds return windows (`WarrantyTrackerRepository.upsertReturnWindowForReceipt` :392-429), re-uploads OCR even when a warranty exists, saves return windows without a tap. ≥0.75 ACTIVE band has no production caller.
- Pending warranties never remind or expire (`WarrantyDao` filters status='ACTIVE').
- Adjacent bug: local warranty review drafts dropped when cloud gate denied (`AutoCreateWarrantyFromReceiptUseCase.kt:128-131`).
- Raw `e.message` also in `WarrantyTrackerViewModel.kt:195`.

## Domain-spec findings needing owner (Gate 2)
- OD-1: no PrivacyCapability for local receipt access; recommended (a) treat ReceiptSideEffectPlanner in-memory-OCR rule as the guard, remove cloud check + PrivacyGate dep from warranty use case, rewrite LEGAL_PATHS :992/:998.
- OD-2: WarrantyExpirationWorker requires notification permission → pending never EXPIRED in DB for notification-denied users; recommended (A) same decoupling fix in this bundle.
- STOP-AND-ASK guard change: SourceScanningArchitectureGuardTest:408 forces notification-permission flag on DailyBriefing workers → B3 needs DailyBriefingWorker.kt allowlisted (permission checked in-worker before posting, like ReceiptMatchingWorker).
- STOP-AND-ASK tests (WarrantyTrackerRepositoryTest): delete :641, :673, :705, :735, :767 (3 of these are review/discard bands — confirm by name); retarget :127, :171, :217, :347, :316; also retarget GenerateTransactionInsightUseCaseTest :71, :114 and SyncProactiveBriefingWorkUseCaseTest:43.
- Risks: insight 3s timeout == outer recommendation job 3s → INV-18; CancellationException swallowed in insight use case + DefaultAiEnvironmentMonitor.kt:84 (FG-10, INV-4); new-install vs upgrade detection via package install/update times; WARRANTY_EXTRACTION left unreachable (not deleted) + proposed source-scan guard (new guard = stop-and-ask).

## UX findings needing owner (Gate 2)
- UX-Q-1: Expired filter includes expired-unconfirmed + optional "N expired without confirmation" line (rec: yes).
- UX-Q-2: confirmation dialog before Reject (irreversible delete of warranty + return window; changes review-card behaviour too) (rec: yes).
- UX-Q-3: AI settings VM reads notification-enabled state for a caption only (rec: yes; no new permission/deep link).
- UX-Q-4: hidden warranty capability may still feed highestPriorityMessage / "Needs attention" card (UNVERIFIED) → planner to confirm excluded.
- UX-Q-5: Greek/Spanish: translate only new/changed keys now; backfill rest later (rec). values-el has no ai_*/warranty_* keys today.
- Adjacent (out of scope, user-visible): deterministic fallback recommendation text hard-codes "€" (spec §15.3).

## Slice statuses
| Slice | Status |
|---|---|
| S0, A, B1a, B1b, B1c, B2a, B2b, B2c, B2d, B2e, B3a, B3b, Z | planned |

## Approved global post-1.0 build order (manual-entry-currency Gate 2 G2-1, 2026-10-16)
Release 1.0 (incl. R16, R23 part 1) → ai-fixes → money-correctness-1.0.x (if specced; else after MEC a) → MEC core (a, setter-less)
→ loan S1..S10 (S3/S6a serialised vs TLC work) → nl-quick-add inc 1 → MEC (b) add-expense UI → MEC (c) receipt review
→ loan S11 on the general currency control (plan edits at loan S0: S11 write set, D-8, S6a AUTO_RECORD_DEFERRED_FX) → nl-quick-add inc 1b → MEC inc 1b.
Source: docs/features/manual-entry-currency/impact-map.md §9.

## Part C — assistant Track 0 (ADDED 2026-10-16 by owner decision at assistant-follow-ups Gate 1: "Recommended")
Scope (from docs/features/assistant-follow-ups/critique.md §3; owner-approved scope growth of this bundle):
- C-1 F-D (P2 privacy): drop ASSISTANT-role turns from the cloud query-interpretation prompt (keep sanitized USER turns only).
- C-2 F-I (P2): raw exception text → bounded reason codes + localized message (InterpretFinancialQueryUseCase.kt:48-51; not shown/persisted/fed back).
- C-3 F-F (P2 wrong answers): "last month/week/year" shadowing (InterpretFinancialQueryUseCase.kt:300-304) + calendar-aware previous period (ExecuteFinancialQueryUseCase.kt:496-499); release-note line.
- C-4 F-G (P2 wrong answer): clarification option completes the pending intent (filters kept) (AssistantViewModel.kt:384-387).
- C-5 MIN: remove from prompt schema or add executor. C-6 F-E: assistant redaction via EffectiveCloudAiPolicy (AssistantViewModel.kt:242).
Status: spec addendum DONE; impact addendum DONE (docs/features/ai-fixes/impact-map-part-c.md: slices C1..C5, T3 overall, schema NONE); decision sheet DECIDED; plan ADDENDUM appended to workflows/active/ai-fixes-plan.md (C1..C5 + CX; order S0 → A → C1..C5 → CX → B1a.. → Z) — PLAN APPROVED 2026-10-16 (owner "Recommended approved"; Gate 3 Part C: C1..C5 + CX; order S0 → A → C1..C5 → CX → B1a.. → Z; (Cc) behavioural test, (Ca)/(Cb) source scans; C3 LEGAL_PATHS one-liner kept; shared MAX/MIN helper; owner reviews Greek before Z). Lands before assistant-follow-ups inc 1.
Not in Part C: unsalted SHA-256 alias hashing (cross-cutting) → separate roadmap item `cloud-alias-hashing`.

### Part C spec findings needing owner (Part C gate)
- F-I severity corrected: raw exception text is NOT shown/persisted/sent today (provider "unsupported" replaced by local fallback which can't return unsupported); live issue = Timber.w(e,…) full throwable logs (:48; OnDeviceQueryInterpretationService.kt:58,63,125) → latent P2 + live P3; C-2 still needed because C-5 lets fallback return unsupported.
- OD-C1 drop assistant turns from on-device prompts too (rec yes). OD-C2 MIN: implement executor (rec) vs keep with localized "not supported"; don't remove from schema.
- OD-C3 options on old/restored clarifications shown disabled (rec). OD-C4 "this month" mid-month compare: full Oct vs full Sep (rec) vs Oct 1–16 vs Sep 1–16.
- OD-C5 add LAST_WEEK/LAST_QUARTER/LAST_YEAR to model period keywords (rec yes). OD-C6 4 prior user questions (rec).
- New field `periodGranularity` on query filters (C-3). Prompt input type → priorUserQueries: List<String> (C-1); fixes current question appearing twice.
- STOP-AND-ASK tests: FinancialQueryInterpretationInputBuilderTest.kt:55-97 and :165-190 retarget; AssistantViewModelTest.kt:485-544 delete or retarget (VM redaction branch removed).
- Fold validateAmountBound amount logging into C-2 (rec). Routed: "0.00 N/A" → assistant-follow-ups inc 1 (owner decision); unsalted aliases → cloud-alias-hashing; hard-coded English → l10n; lexicon gaps → assistant 1b/nl-quick-add; future-dated rows in "this month" → money-correctness (unverified); redaction timing gap + F-I correction → PSG.

### Part C decisions (2026-10-16, owner verbatim "Recommended") — docs/features/ai-fixes/gate2-decisions-part-c.md
PC-1 USER-only prior questions on cloud + on-device; PC-2 K=4; PC-3 implement MIN executor; PC-4 stale clarification options disabled;
PC-5 full-unit vs full-unit comparison; PC-6 LAST_WEEK/QUARTER/YEAR keywords; PC-7 APPROVED test changes: retarget
FinancialQueryInterpretationInputBuilderTest :54-97 + :164-190, DELETE AssistantViewModelTest :484-544 (coverage moved to builder), mechanical
reason-type retargets; PC-8 no Engine5 allowlist (resolver returns plain values); PC-9 APPROVED 3 new SourceScanning guard tests (a)(b)(c);
PC-10 OR-redaction FORBIDDEN line scoped to assistant + follow-up for 6 other builders; PC-11 validateAmountBound logs fixed in both places (C2);
PC-12 Part C placed RIGHT AFTER Part A; PC-13 strings EN/el/es, owner reviews Greek; PC-14 LEGAL_PATHS assistant section + money-correctness
impact-map stale line + release note; PC-15 T3 overall.
- Reconciliation (orchestrator, 2026-10-16): Part C spec routed finding RF-C1 ("0.00 N/A" → money-correctness) is SUPERSEDED — owner assigned the typed
  empty result to assistant-follow-ups inc 1. Part C's NO_MATCHING_TRANSACTIONS Unsupported for largest/smallest may later be replaced by the typed Empty
  (assistant-follow-ups OD-F1); Part C tests AE-C9/AE-C27/AE-C12 are expected to be retargeted then (assistant-follow-ups stop-and-ask, not Part C's).

## Slice statuses (Part C added)
| Slice | Status |
|---|---|
| C1, C2, C3, C4, C5, CX | planned (approved 2026-10-16) |

- 2026-10-16 (cloud-alias-hashing Gate 1 Q-E): follow-up owned by ai-fixes — migrate 5 builders' redaction decision (ReviewExplanation, ReceiptItemCategorization, ReceiptAssist, DedupeJudge, CategorizationAssist ×2) to EffectiveCloudAiPolicyResolver; must keep canUseCloudFor conjunct (INV-C18) and match caller-snapshot semantics (critique: resolver reads settings().first(), builders read caller settings). Not in Part C scope (PC-10).

## S0 results (2026-10-23, orchestrator; worktree C:\Users\panos\Desktop\Cost-agregator-af, branch feature/ai-fixes from 681d49a3)
Base: Release 1.0.2 merged (044be2c8) + money-correctness 681d49a3 (touches no ai-fixes production file; shifts LEGAL_PATHS / ENGINE_INTERACTION_MAP anchors). Drift vs C* 53824bcd confirmed by git: WarrantyExpirationWorker.kt (+16), DailyBriefingWorker.kt (6), WarrantyTrackerViewModel.kt (111, R24/R29/R18b landed), db_ownership_policy.yml (126). AiSettingsViewModel, router, AiPolicyImpl unchanged vs C* (router/policy line offsets are plan off-by-N).
Discovery (scout, Sonnet) — plan corrections to apply when executing each slice:
- WarrantyDaoTest is JVM Robolectric at T/data/database/dao/WarrantyDaoTest.kt (not androidTest): B1a/final gate use targeted-unit-test "*WarrantyDaoTest*", no connected-test-class/AVD.
- Missing classes named by plan: ReceiptSideEffectPlannerWarrantyTest (use/extend T/domain/receipt/lifecycle/ReceiptSideEffectPlannerTest.kt), WarrantyExtractionModelsTest (new), *EffectiveCloudAiPolicy* (matches nothing; B2a filter).
- B1a unlisted test breakage: WarrantyTrackerRepositoryTest :221, :911 (+ helper ~:890-908), :859 repositoryWith ctor; WarrantyTrackerRepositoryTransactionTest :109-120 ctor. Plan delete/retarget cites are ~4 lines above the fun lines (consistent offset).
- B1a policy row toWarrantyEntityOrNull: db_ownership_policy.yml :3389 (plan ~:3433), also in signatures.candidate.yml :3372, signatures.accounting.json, legacy snapshots; NOT in GR-08-seeds.yml / legacy.yml by grep. Extra pins: scripts/test_migrate_db_policy_seed_rows.py :2786/:2894/:2929/:2933/:3089; docs/ci/db-mediation/GR-14_OWNER_ACCEPTANCE_REGISTRY.yml:24; docs/ci/db-structural/GR-11_CORPUS.yml:1051 -> B1a is needs-sonnet-confirm; R7 Pattern E sequence per wave2-db-guard-repair-20260927-handoff.md:80-92 (generate with --seed-rows, then --verify; never --force-repromote; run LAST on a tree with only that change uncommitted).
- D-1 KNOWN_VIOLATIONS (CancellationSafetyArchitectureGuardTest): :79 DefaultAiEnvironmentMonitor, :92 ManualExpenseRepository, :126 WarrantyExpirationWorker (FALSE_POSITIVE entry; worker already rethrows CE :150), :151 AssistantViewModel, :170 WarrantyTrackerViewModel (VM now rethrows CE -> likely removable in A), :150 AiSettingsViewModel (A), :113 AutoCreateWarrantyFromReceiptUseCase (B1a).
- D-3 confirmed: AssistantViewModel.kt:535 passes only QUERY_INTERPRETATION -> no B1c addition.
- D-4: only updateWarranty use is confirmWarranty (:221) -> guard (c) full form.
- D-7 anchors: ARCHITECTURE.md :1268/:1270/:1826/:1977 (-1); LEGAL_PATHS Privacy/Cloud AI :400, Workers :544, Warranty Auto-Create :1112; ENGINE_INTERACTION_MAP PrivacyGate :188-198, HybridRouter :167-173.
- D-8: JVM Room harness exists (Robolectric + inMemoryDatabaseBuilder).
- D-10: CODEBASE_INVENTORY is hand-maintained (dated Drift Sync sections).
- Slice A scope shrinks: WarrantyTrackerViewModel/Screen already have CE rethrow, bounded error and SnackbarHost (R24) -> A's warranty part = AE-3 interim test + KNOWN_VIOLATIONS :170 removal if guard agrees. AiSettingsViewModel closed literal set must also cover :108, :109, :111, :246, :264, :265, :267; broad catch :127 (Timber.e(e) :129) and :281 Timber.e(e) also in INV-3 scope.
- Expired 2026-09-30 broad-catch allowlist entries (SourceScanningArchitectureGuardTest ~:545-588) appear unenforced (expiry check applies to the first allowlist only) — confirmed by baseline below.
Baselines:
- targeted "*SourceScanningArchitectureGuardTest*,*CancellationSafetyArchitectureGuardTest*,*AiSettingsViewModelTest*,*WarrantyTrackerViewModelTest*,*DataRetentionWorkerTest*" vr-20261022-211416-b7cd6e9e PASS (expired entry not enforced).
- static-guards vr-20261022-211619-cd7ab4cc STALE_RESULT (orchestrator edited this handoff mid-run); rerun after ff to bcd8e921: vr-20261022-213819-94e2ad10 PASS.
- registered-guard db_access vr-20261022-213139-9fa5158b PASS; db_artifact_sync vr-20261022-213412-82199f14 PASS; lint vr-20261022-213428-05af4ce9 PASS (on 681d49a3).
- Branch fast-forwarded to feature/money-aggregates bcd8e921 (2026-10-23) so the lane sits on all committed money work.
S0 DONE. Next: A.

## Slice A (2026-10-23) DONE
Implemented (feature-implementer): FEATURES.md AI relabel + warranty section (local regex detection; cloud service "retained but not active"); DataRetentionWorker header; AiSettingsViewModel/Screen all user-visible literals -> UiText.StringResource (27 keys en/es/el), CE-first catches, class-name-only logs (INV-3 incl. :127/:281); CancellationSafetyArchitectureGuardTest KNOWN_VIOLATIONS removed for AiSettingsViewModel and WarrantyTrackerViewModel (no suspend fun in either -> not scanned violations). WarrantyTracker VM/Screen unchanged (R24 already landed; AE-3 interim tests already exist :285/:297).
Validation: targeted "*AiSettingsViewModelTest*,*WarrantyTrackerViewModelTest*,*DataRetentionWorkerTest*,*CancellationSafetyArchitectureGuardTest*,*SourceScanningArchitectureGuardTest*" vr-20261022-222651-0476f3ea PASS; static-guards vr-20261022-223911-7a32dec9 PASS; lint vr-20261022-225429-60d77bc6 PASS.
Review (cheap, Sonnet): ACCEPT-WITH-MINORS, no required fixes. Follow-ups: FEATURES.md :20 "AI Forecasting" / :225 "AI suggestions" (optional wording); AiSettingsViewModel :130 fully-qualified CE (cosmetic); glyph prefixes in Screen; OkHttpCloudProviderConnectionTester.kt:50 Timber.w(e) -> later INV-3 sweep; testGemini messages English-only; WarrantyTrackerViewModel.kt:93 raw runCatching in launch swallows CE -> fix in B1b.
Next: B1a (guard regeneration approved; needs-sonnet-confirm per S0 notes; must run LAST on a tree with only that change uncommitted; never interleaved with loan regeneration).
