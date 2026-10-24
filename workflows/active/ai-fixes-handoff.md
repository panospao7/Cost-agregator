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

## B1a DONE (2026-10-23)
Warranty core: cloud warranty path deleted from WarrantyTrackerRepository (ctor deps removed), confirmWarranty(id) (barrier → one txn: fresh read, NotFound/AlreadyConfirmed, EXPIRED never back to ACTIVE, CONFIRMED event fail-closed), reconcileExpiredItems expires ACTIVE + PENDING_REVIEW (strict <, needsReview kept, counts-only event), WarrantyDao @Query only, AutoCreate band → review draft (privacyGate removed), WarrantyReviewState (domain/usecase/warranty — domain.warranty is unsharded), SourceScanning guard (a) (CloudWarrantyExtractionService only from di/AiModule.kt, fail-closed), docs.
Policy/guards (owner-approved item 3): db_ownership_policy.yml −toWarrantyEntityOrNull +2 confirmWarranty(Long); exact count pins 430→431 in DbGuardPolicyFixtureTest.kt and scripts/test_verify_db_access_boundaries.py (x2), with provenance comments.
R7 Pattern E regeneration was run and then NOT used: it would also refresh pre-existing stale accounting records (NotificationRepository, ReviewQueueRepository, RecurringLifecycleCoordinator, RetentionModule — stale since 2ec50671), which the owner's N2 condition forbids without acknowledgement, and it cascades into candidate-size pins (407) in known_good_state and guard tests. Followed the Release 1.0 R21 precedent instead (active policy rows only; db_artifact_sync inputs = legacy + seeds + candidate + accounting, unchanged). Regenerated set saved in the orchestrator scratchpad (b1a-regen-set) if the owner later wants the full candidate truth-sync. Consequence: the candidate still lists the dead toWarrantyEntityOrNull row and lacks confirmWarranty (candidate trails the active policy — pre-existing state, GR-14c note).
Validation: targeted (13 filters) vr-20261023-004101-92e1ad10 PASS (on the regen state; tests unaffected by the revert); db_artifact_sync vr-20261023-012400-bf82502a PASS; db_access vr-20261023-012418-314f60f5 PASS; static-guards vr-20261023-012652-658887b5 PASS (minimal path).
reviewer-strict: REJECT on governance only (N2 accounting drift) → resolved by the minimal path. ISSUE-2: the sixth deleted test (WarrantyTrackerRepositoryTest :911 discard audit + helper) only exercised the deleted cloud discard path → covered by G2-6 / Gate 3 item 2. ISSUE-3: B1a MUST NOT ship without B1b (WarrantyTrackerViewModel.confirmWarranty still builds an ACTIVE copy; LEGAL_PATHS FORBIDDEN line already present) → B1a+B1b land together. ISSUE-4: domain-spec confirm-event line amended to fail-closed.
Follow-ups: AutoCreate Failure(e.message) → bounded code; createWarrantyForReview/promoteReviewDraft (no caller) would reactivate expired rows; Timber.w(e) at ReceiptSideEffectPlanner :222, WarrantyTrackerRepository :197/:215; GR-14 registry / GR-11 corpus refresh at next full regeneration.
Next: B1b (must include WarrantyTrackerViewModel.kt:93 runCatching CE fix).

## B1b DONE (2026-10-23) — B1a+B1b now ship together
WarrantyTrackerViewModel.confirmWarranty(id) → repository.confirmWarranty (no VM-built status/needsReview copy, no updateWarranty); NotFound → bounded message, AlreadyConfirmed silent; counts/filters/ordering via WarrantyReviewState with one clock owned by the list emission; :93 runCatching → CE-rethrowing try/catch; screen: AWAITING_REVIEW and EXPIRED_UNCONFIRMED cards ("Keep in history", never Claim/Reactivate), expired-unconfirmed banner, reject confirmation dialog (G2-9), filtered/review empty states; 12 strings en/es/el. Guard (c) in SourceScanningArchitectureGuardTest (no updateWarranty( and no status/needsReview copy in the VM; fail-closed, negative fixture).
Review (reviewer-strict) ACCEPT-WITH-MINORS → fixed: loadStats no longer moves referenceNowMillis (single clock); es/el count strings rephrased to avoid N=1 agreement; confirmed EXPIRED cards show "Expired on". Notes: Greek dialog Cancel shows English (no el action_cancel; UX-Q-5 backfill follow-up); totalProtectedValue still 0.0 on aggregate failure (pre-existing, follow-up: nullable "unavailable").
Validation: targeted vr-20261023-021112-b450f5f0 PASS (before minors); after minors targeted vr-20261023-024307-c8087a8e PASS, lint vr-20261023-024609-34d87a9a PASS, static-guards vr-20261023-024957-f80b027c PASS.
Next: B1c (single-source AI-settings defaults, hidden warranty row).

## B1c DONE (2026-10-23)
Single-source AI-settings defaults: AiSettings.warrantyExtractionEnabled default false (INV-7); AiSettingsRepositoryImpl reads every missing key as `?: defaults.x` from one `AiSettings()` instance (toAiSettings internal for tests); warranty toggle row removed from AiSettingsScreen + local-only caption `ai_warranty_local_note` (en/es/el); dead VM setter removed. Router table entry for WARRANTY_EXTRACTION kept (R7: never remove the enum).
Tests: new AiSettingsRepositoryImplTest (defaults parity, AE-8 stored explicit true survives mapping; "never rewritten" half is pinned with the B2b DataMigration, spec §4.5 item 5); AiSettingsViewModelTest UX-Q-4 pin; DefaultAiCapabilityRouterTest warranty cloud case now sets warrantyExtractionEnabled = true explicitly.
reviewer-strict: MAJOR (router test relied on old default) + 2 minors (dead setter, a non-failing assertion) → all fixed before validation.
Validation (fixed tree): targeted vr-20261023-034048-ba1248a8 PASS, lint vr-20261023-034604-599a17d7 PASS, static-guards vr-20261023-034953-8ca9ed6f PASS.

### Order correction (orchestrator, 2026-10-23)
The approved order is S0 → A → C1..C5 → CX → B1a.. (PC-12). B1a/B1b/B1c ran before Part C. That dependency is ordering-only, and B1 shares only serial docs (LEGAL_PATHS Privacy block, SourceScanning guard) with C1, so nothing is invalidated. The order is restored from here: C1 → C2 → C3 → C4 → C5 → CX, then B2a.
Next: C1 (assistant prior turns: USER-only, K=4, cloud + on-device; guard (a)).

## Part C S0 (2026-10-23, scout Sonnet, read-only, on 11d169a0)
D-11 OK (Hybrid passes the same input; Unsupported producers all plan-listed, no N16). D-12 OK (AiChatMessageDao.kt:20 ORDER BY createdAt, id ASC; Room Flow .first() sees committed rows; no N11). Today the VM reads history AFTER persistUserTurn (:236 vs :238-250), so C1 must move the snapshot before persist. D-13 OK (no QUERY_INTERPRETATION pin; no N9). D-14 hilt-bindings-map lists no assistant ctor deps, so no edit. D-15/D-16 green (see the baselines below). D-17: field-wise asserts, so C3 probably needs no periodGranularity edit. D-18 no drift beyond Part A (+1 line). D-21 clock = TimeProvider via builder → input.currentTimeMs; verify_time_boundaries.py:138 flags System.currentTimeMillis; no N13. D-22 docs/releases/CHANGELOG.md [Unreleased]. D-20 docs: no architecture doc references the old names (only feature docs, FUTURE-WORK, archive).
C1 plan corrections:
(1) Add InterpretFinancialQueryUseCase.kt:32/:41 (the conversationHistory pass-through) to the write set.
(2) Test filters: use *HybridRoutingTest* (constructs the input :561), *OnDeviceQueryInterpretationServiceTest*, *CloudProviderTransportPayloadTest*. CloudQueryPreparedPayloadTest and HybridServiceDelegationTest do not exist; HybridRouterIntegrationTest does not construct the input.
(3) CloudQueryInterpretationServiceTest.kt exists, so (Cc) is an added test (request-body capture pattern :302-345).
(4) The cloud call is at CloudQueryInterpretationService.kt:110 (plan :78); the cloud file is still not edited.
Baselines (11d169a0): cloud_payload vr-20261023-043212-22fc0351 PASS; pii_logging vr-20261023-043230-202a7d82 PASS; time_boundaries vr-20261023-043248-64b11e56 PASS; cancellation vr-20261023-043306-fce845b6 PASS; targeted (Engine5, builder, AssistantVM, Execute, Interpret, OnDevice, Cloud) vr-20261023-043321-35d039d3 PASS.
Next: C1 implement.

## C1 (2026-10-23) — implemented, review round 1
Source (feature-implementer): FinancialQueryInterpretationInput.priorUserQueries: List<String>; AppConfig MAX_QUERY_HISTORY_USER_TURNS_FOR_MODEL = 4 (old constant deleted); builder takes EffectiveCloudAiPolicyResolver (shouldRedact = canUseCloudFor && resolve().redactBeforeCloud; OR removed), USER-only, last 4, sanitized, blanks dropped; InterpretFinancialQueryUseCase param renamed priorMessages (same arity); OnDevice buildPrompt renders "- USER:" / "none" (the cloud file is NOT edited); AssistantViewModel snapshots history BEFORE persistUserTurn, unfiltered, only if storeConversationHistory && sessionId != null; privacySettingsRepository ctor param removed. Docs: LEGAL_PATHS (2 Privacy FORBIDDEN lines + new "Assistant / Financial Query" section), DEPENDENCY_MAP, VIEWMODEL_INJECTION_MAP (9→8 deps), CODEBASE_SEGMENTS Seg 20.
Tests (test-author, 2 passes after an API timeout): builder (AE-C3, AE-C29, AE-C1, AE-C4, INV-C18, blanks, caps), VM (AE-C1 VM half, AE-C5 ×2, AE-C6), OnDevice AE-C2/AE-C5, cloud (Cc), SourceScanning (Ca)/(Cb) + negative fixtures.
**PC-7 deletion recorded:** AssistantViewModelTest `submitQuery strips history payloadJson when privacy requires redaction even though ai redaction is off` DELETED (VM redaction branch removed; coverage moved to the builder AE-C1/AE-C4). This is the only deleted test.
Validation round 1: targeted vr-20261023-052126-04ffab66 FAIL (only (Cc): redaction collapses newlines, so the line-based asserts can't work); cloud_payload vr-20261023-052900-b3a6da82 PASS (no N8); pii_logging vr-20261023-052918-5f7ff269 PASS; static-guards vr-20261023-052936-73d92515 PASS (known_good_state 21 unchanged).
Reviews: reviewer-strict REJECT (MAJOR (Cc) only; minors: Room-faithful flow stub, handoff PC-7 record, labels); architecture-guardian ACCEPT-WITH-MINORS ((Cb) copy/:: evasion, (Ca) token list, LEGAL_PATHS nits); privacy-security-guardian ACCEPT-WITH-MINORS (no end-to-end AE-C1 test, no resolver-failure test, same scan gaps, labels).
Fix batch (test-only) + docs nit pass: DONE (see below).
Info/follow-ups: unredacted prior queries keep internal newlines (user's own text; possible follow-up: collapse newlines); a failed history read now skips persisting the USER turn (benign); builder canUseCloudFor uses an earlier settings snapshot than the resolver (cloud gate + prepareText still apply). C2 owns InterpretFinancialQueryUseCase :48-51 / OnDevice :58/:63/:125 throwable logs.
C1 fix batch DONE (test-only):
- (Cc) uses a non-redacting real resolver, with a redaction-on substring variant.
- VM store stub uses `flow { emit(...) }`.
- (Ca) also forbids `.shouldRedact(` and `settings.redactBeforeCloud`; (Cb) also forbids `priorUserQueries =` and `::FinancialQueryInterpretationInput` outside the builder; both have negative fixtures; labels fixed.
- Builder: IOException/ISE/CE resolver-failure tests; AE-C1 end-to-end builder→buildPrompt test.
LEGAL_PATHS Assistant section nits applied by the orchestrator, using the reviewers' wording: sessionId condition, chat-row writes via appendMessage, local fallback.

## C1 DONE (2026-10-23)
Validation on the final production code: cloud_payload vr-20261023-052900-b3a6da82 PASS (no N8; proof unedited); pii_logging vr-20261023-052918-5f7ff269 PASS; static-guards vr-20261023-052936-73d92515 PASS. After the test/doc fixes: targeted (9 classes) vr-20261023-062011-462543d1 PASS; guard_docs_truth vr-20261023-062344-3d7d3cf9 PASS (LEGAL_PATHS edit). Last edit: "(4)" dropped from LEGAL_PATHS (reviewer minor; prose only).
Reviews: reviewer-strict iteration 2 ACCEPT-WITH-MINORS (all round-1 findings fixed; INFO: the redaction-on (Cc) ASSISTANT check is near-unfailable by type, so the real proof is the builder end-to-end test + the non-redacting exact lines; known scanner limits: typealias, whitespace before "("). PSG and AG ACCEPT-WITH-MINORS (round 1; all minors addressed).
N-items fired: none. KNOWN_VIOLATIONS: unchanged. Deleted tests: only the PC-7 one.
Follow-ups: collapse newlines in unredacted prior queries (optional); C2 owns the throwable logs.
Next: C2 (typed bounded unsupported reasons + log hygiene; res EN/es/el).

## C2 (2026-10-23) — implemented, validating
Source:
- New AssistantUnsupportedReason (7 codes: INTERPRETATION_DISABLED, INTERPRETATION_FAILED, PROVIDER_UNAVAILABLE, PROVIDER_RESPONSE_INVALID, MODEL_DECLINED, METRIC_NOT_SUPPORTED, NO_MATCHING_TRANSACTIONS).
- Unsupported(reason, diagnostic: DiagnosticReasonCode?); no new DiagnosticReasonCode values.
- Bounded logs (code + class only) at Interpret :48 and OnDevice :58/:63/:125. parseResponse CE rethrow. The model's unsupportedReason is discarded (MODEL_DECLINED) and removed from the schema line.
- Cloud: 8 sites map their codes to `diagnostic` (prepareText/gate/audit untouched).
- validateAmountBound logs no amounts (both copies).
- New AssistantUnsupportedReasonText mapping; ResultCard renders it; VM payload {"type":"unsupported","reason":"<CODE>"}.
- 7 strings EN/es/el (es/el marked "best-effort, pending review": owner reviews before Z, PC-13).
- N16 check: no other reader of Unsupported.reason / payloadJson.reason (ExportAnonymizer nulls payloadJson; BackupVerifier does not parse reason).
Tests:
- Pass 1: mechanical retargets of String reasons → typed reason + diagnostic in 6 files.
- Pass 2: AE-C7 (bounded logs; the result is the local fallback per spec P5, so INTERPRETATION_FAILED appears in the log only), AE-C8 (on-device + cloud MODEL_DECLINED), AE-C9 (payload keys exactly {type, reason}), AE-C10 (class-only log, CE propagates via a throwing categoryLookupMap seam), PC-11 both places, mapping test, cloud empty-candidates → PROVIDER_RESPONSE_INVALID + UNKNOWN_ERROR.
Notes:
- Interpret's METRIC_NOT_SUPPORTED branch is unreachable (resolveMetric never yields an unsupported metric).
- **C5 must retarget** ExecuteFinancialQueryUseCaseTest "INV-C5 metric without an executor returns METRIC_NOT_SUPPORTED code", which uses MIN (C5 adds the MIN executor; the plan's done-when says "no current metric reaches else").
- Not covered: executeLargest all-conversions-fail NO_MATCHING path; el locale rendering.
Validation: see C2 DONE below.

## C2 DONE (2026-10-23)
Validation:
- targeted (13 classes) vr-20261023-071309-cec25b0f PASS;
- lint vr-20261023-072642-34efe90b PASS (no MissingTranslation);
- cloud_payload vr-20261023-073045-46bea6aa, pii_logging vr-20261023-073103-52081e15, cancellation vr-20261023-073121-d6bfb896 PASS;
- static-guards vr-20261023-073139-2bee8fa4 PASS.
- After the minors (KDoc/docs wording + one test): targeted (OnDevice, Cloud, AssistantVM, Interpret) vr-20261023-083237-79a4abec PASS; cloud_payload vr-20261023-083725-6237077f PASS.
Reviews (all ACCEPT-WITH-MINORS): PSG, AG, RS. Minors applied:
- `diagnostic` wording → "bounded; never rendered or persisted; the producing site logs it" (FinancialQueryModels ×2, Cloud KDoc, CODEBASE_SEGMENTS).
- Malformed-JSON test documented as non-probative on the JVM; new AE-C10 test throws a JSONException carrying the sentinel via the ThrowingCategoryLookup seam.
Accepted gaps: the AE-C9 rendering half (card) and el-locale rendering are not unit-tested (lint MissingTranslation + the exhaustive mapping test cover the risk); executeLargest all-conversions-fail NO_MATCHING path; OnDevice GenAiException log not unit-testable (GenerativeModel not injectable; reviewed clean).
Carried to C5:
- AG medium: once MIN has an executor, replace ExecuteFinancialQueryUseCase's `else` with an exhaustive `when (intent.metric)`.
- The C2 test "INV-C5 metric without an executor returns METRIC_NOT_SUPPORTED code" (uses MIN) is then removed: it was created in C2, so this is not an N14 case per RS. Note it explicitly in C5.
- METRIC_NOT_SUPPORTED then becomes defensive-only (Interpret branch unreachable too).
Follow-ups:
- CancellationSafety KNOWN_VIOLATIONS :81 (OnDeviceQueryInterpretationService, MIT-034) may now be removable; the GenAiException catch is still present. Removal = baseline shrink; propose at CX/Z.
- Cloud PROVIDER_RESPONSE_INVALID pairs with UNKNOWN_ERROR for empty candidates (faithful to old codes).
Next: C3 (AssistantPeriodResolver, periodGranularity, calendar previous period; T2).

## C3 (2026-10-23) — implemented
Source:
- New AssistantPeriodResolver (object; plain AssistantPeriod values; no PeriodRange import; no clock reads; explicit zone param).
- PeriodGranularity + PeriodChoice + ExpenseQueryFilters.periodGranularity.
- Interpret: resolvePeriod deleted; one cue function; bare-period branches merged; "previous" dropped from rich cues.
- OnDevice: keyword parser → resolver; schema adds LAST_WEEK/QUARTER/YEAR; the schema line reaches the cloud prompt (keywords only).
- Execute: private previousEquivalentPeriod deleted.
- Docs: Segment 20 + LEGAL_PATHS one-liner.
Deviations:
(1) The comparison-target connector allows intervening words (`compar(e|ed|ing|ison)\b[^.?!]*?\b(with|to)`), because spec INV-C9's literal pattern does not match its own AE-C12 example. Spec text to reconcile (domain-modeler, at Z).
(2) The resolver does its own java.time arithmetic (TimePeriodUtils hard-codes the system zone).
(3) PeriodChoice lives in FinancialQueryModels (C4 adds the Clarification types beside it).
(4) Cue matching is word-based (INV-C10).
Behaviour changes as specified: "last week/quarter/year" = the previous full unit; "previous month" is a bare period.
ExecuteFinancialQueryUseCaseTest :90-131 expected unchanged (CUSTOM [100,200) → [0,100)).
Tests: pass 1 (AssistantPeriodResolverTest) running; pass 2 (Interpret/Execute/OnDevice additions) next.

## C3 DONE (2026-10-23)
- Tests:
  - AssistantPeriodResolverTest: AE-C11..C19, DST (745 h October, 25 h/23 h days), month-end clamp, cue set, keywords, choices, connector false-positive/positive, unaligned-granularity → CUSTOM.
  - Interpret/Execute/OnDevice integration tests: JVM default zone pinned to Europe/Athens and restored in @After; forks are separate JVMs (maxParallelForks), classes serial.
- Validation:
  - targeted vr-20261023-090840-0a09267b PASS, time_boundaries vr-20261023-092030-2cb8a41f PASS, cloud_payload vr-20261023-092048-88540b8e PASS, static-guards vr-20261023-092106-f5156823 PASS;
  - after the review fixes: targeted vr-20261023-101337-66a4bc36 PASS, time_boundaries vr-20261023-101926-4125ec7f PASS.
- Review (T2 cheap review, reviewer-strict on Sonnet): ACCEPT-WITH-MINORS. Applied:
  - the resolver FULL branch requires both bounds aligned (unaligned start with explicit granularity → CUSTOM);
  - test comment slips corrected;
  - a trivially-true assertNotEquals removed;
  - connector false-positive/positive tests.
- Deviation (1) (looser connector) accepted by the reviewer; spec INV-C9 text to be reconciled at Z (domain-modeler).
- Carried to C4: clarification completion must set periodGranularity from resolveChoice(...).granularity whenever it replaces period.
- Optional cleanup: containsRichIntentCue still lists "compare"/"vs" substrings (dead after the exact-phrase check).
- Next: C4 (clarification completion, live pending state, disabled stale options; T2; RS gate).

## C4 DONE (2026-10-23)
Source:
- ClarificationSlot/Choice/Completion; Clarification.completion.
- New CompleteFinancialQueryClarificationUseCase: pure, TimeProvider default nowMs; PERIOD sets period + periodGranularity via resolveChoice.
- Executor period / fallback presentation clarifications carry the completion.
- VM livePendingClarification (consume-first; expires on submit/result/error/clear/disabled; never persisted); retryLast re-executes completedIntent; stale tap → bounded CLARIFICATION_EXPIRED.
- Both UI surfaces route by (itemId, index) and disable non-live options.
- 5 labels en/es/el (es/el draft).
- Docs: LEGAL_PATHS block + 3 FORBIDDEN, maps, Segment 20.
- Deviations accepted by reviewer-strict: AssistantClarificationChoiceText mapping file; AiRecommendationCard.applyEnabled; completer injects TimeProvider; ExpenseQueryFilters.withPeriod in the Engine5-allowlisted model file (legitimate, not a loophole).
Validation:
- targeted vr-20261023-105543-ffe1ddc2, time_boundaries vr-20261023-111018-de55bee0, pii_logging vr-20261023-111036-e3ccffd9, lint vr-20261023-111052-93a24965, static-guards vr-20261023-111456-0e254bb1;
- after the lock-up fix: targeted vr-20261023-120345-cb2b6b13, pii_logging vr-20261023-120833-564d9528.
- All PASS.
Review: reviewer-strict ACCEPT-WITH-MINORS. MINOR fixed: a throwing label lookup/completer after a tap could leave _isSubmitting stuck; both are now computed before the gate, with a bounded CLARIFICATION_COMPLETION_UNAVAILABLE log and a regression test.
- PC-4 question answered: legacy model clarifications (completion == null) stay on the legacy text flow per INV-C15 (intended).
Notes: a tap during persistAssistantTurn is ignored (spec); cancellation before the User append loses the consumed completion (acceptable edge).
Next: C5 (MIN executor; must retarget/remove the C2 MIN→METRIC_NOT_SUPPORTED test and make the metric `when` exhaustive), then CX.

## C5 DONE (2026-10-23)
Source:
- ExecuteFinancialQueryUseCase: one executeExtreme helper (ExtremeSelection LARGEST/SMALLEST) behind executeLargest/executeSmallest. MAX is byte-identical (same maxByOrNull, title key, data-quality, drilldown).
- MIN uses minByOrNull; mixed currency via the same single convertAsOf(atMillis = expense.date) site (FG-17 untouched), no-rate rows excluded (isPartial/excludedCount).
- An exhaustive `when (intent.metric)` after the LIST/grouping checks.
- Interpret: smallest|cheapest|lowest|minimum → MIN.
- MapFinancialQueryToNavigationUseCase allows MIN (drilldown parity with MAX; reviewer finding; one-line widening of the write set, orchestrator decision).
- domain_ai_smallest_purchase en/es/el (es/el pending review).
- money-correctness impact-map §9 (c2): MIN is the second consumer of the convertAsOf vs snapshot gap (RF-C3).
METRIC_NOT_SUPPORTED is now DEFENSIVE-ONLY: unreachable in the executor and Interpret; the enum value and UI text are kept.
SPEC DEVIATION to reconcile at Z (domain-modeler): domain-spec-part-c.md ~:50 and ~:200 still describe an executor `else` → METRIC_NOT_SUPPORTED; replaced by an exhaustive when (authorised by the C2 AG note).
Tests:
- the C2 test "INV-C5 metric without an executor…" was RETARGETED (not deleted) to "INV-C17 every QueryMetric has an executor and none returns METRIC_NOT_SUPPORTED";
- AE-C27 single/mixed/no-rate/order-by-converted/empty/all-no-rate/routing;
- AE-C28 4 cues + clarification + bare-period + enrich;
- convertAsOf date pinned to expense.date;
- the mapper returns an equal filter for MIN and MAX.
Validation:
- targeted vr-20261023-123734-23631cfd PASS, lint vr-20261023-124335-0b288f1c PASS;
- after the follow-up: targeted vr-20261023-125858-92d24758 PASS.
Review: reviewer-strict ACCEPT-WITH-MINORS (3 minors: drilldown fixed, date test fixed, spec deviation recorded).
Notes: es/el lack domain_ai_largest_purchase (pre-existing; MAX title in English) → Z follow-up.
RELEASE-NOTE LINE (staged for Z, plan §8): "Assistant: 'last week', 'last month', 'last quarter' and 'last year' now mean the previous calendar period (previously the current one was used, e.g. 'food last month' showed this month). Comparisons now use the previous calendar week/month/quarter/year instead of a same-length window. Choosing an option in a clarification keeps your original filters. Smallest-purchase questions are now answered."
Next: CX (Part C exit checkpoint, validation only): shards domain-a-m, data, runtime-ui, architecture-contracts; registered-guard cloud_payload, pii_logging, time_boundaries, cancellation; static-guards; lint.

## CX DONE — Part C exit checkpoint (2026-10-23, on c2105caf)
- shards: domain-a-m vr-20261023-130816-5e410fe3, data vr-20261023-130917-aeb1ccfc, runtime-ui vr-20261023-131303-30369432, architecture-contracts vr-20261023-131434-14e98da0;
- registered-guard: cloud_payload vr-20261023-131522-9e7bd4f4, pii_logging vr-20261023-131540-7554c223, time_boundaries vr-20261023-131558-5a0567b0, cancellation vr-20261023-131616-80a7176a;
- static-guards vr-20261023-131634-1d50bc67;
- lint vr-20261023-133137-32cf280d.
- ALL PASS. Part C (C1..C5) complete. No N-items fired across Part C; the only test change beyond additions/retargets is the PC-7 deletion (C1).
Next: B2a (TRANSACTION_INSIGHT capability, tables only, no caller; T3; AG + PSG + RS).

## B2a (2026-10-23) — implemented (NOT validated; awaiting compile + targeted-unit-test)
- INV-15 (tables), INV-16 (policy), AE-27. Schema NONE (enum stored by name via Converters; unknown still -> DASHBOARD_BRIEFING).
- Source: AiModels.kt (TRANSACTION_INSIGHT appended LAST); AiPolicyImpl.kt (canUseCloudFor=false; shouldAllowOnDevice=aiEnabled, allowOnDeviceAi gate already upstream); DefaultAiCapabilityRouter.kt (isCapabilityEnabled=aiEnabled until B2b adds transactionInsightEnabled; displayName; cloud provider/model "unsupported"; on-device model ON_DEVICE_BRIEFING_MODEL; added to ON_DEVICE_IMPLEMENTED_CAPABILITIES; NOT in CLOUD_FIRST_CAPABILITIES); GetAiRuntimeStatusUseCase.kt (runtimeLabel); AiSettingsScreen.kt (label via new string); DebugScreen.kt (supportsCloudFallback=false, debugRuntimeLabel); res values/es/el `ai_capability_transaction_insight` (es/el best-effort, owner review).
- Docs: LEGAL_PATHS.md Privacy/Cloud AI "on-device-only capabilities: TRANSACTION_INSIGHT".
- Tests (additive only): DefaultAiCapabilityRouterTest (AE-27 matrix + AVAILABLE + onDevice-disallowed + DISABLED), AiPolicyTest (64-combo canUseCloudFor=false, shouldAllowOnDevice table), GetAiRuntimeStatusUseCaseTest (label/fallback row), ConvertersTest (round-trip, append-only order pin, unknown fallback).
- Enum-iteration sites: DebugViewModel `AiCapability.entries` (debug runtime list now includes TRANSACTION_INSIGHT, debug-only screen); AiPolicyTest/DebugViewModelTest `entries` (dynamic, no pin change). AiSettingsViewModel runtime list is an explicit 7-item list (TRANSACTION_INSIGHT not user-visible). No cloud-consent loops over the enum.
- Carry-forward for B2b (BINDING, spec §1.6 / INV-15): B2b MUST switch the TRANSACTION_INSIGHT arms in AiPolicyImpl.shouldAllowOnDevice and DefaultAiCapabilityRouter.isCapabilityEnabled to settings.transactionInsightEnabled (spec §1.6), with tests: insight off → DISABLED / shouldAllowOnDevice false; briefing off with insight on → not DISABLED. (The use case also gates INSIGHT_DISABLED, AE-32.)
Next: B2b / B2c.

### B2a review fixes (2026-10-23, reviewer-strict + privacy-security-guardian: ACCEPT-WITH-MINORS; NOT validated)
1. Spec §1.6 carry-forward made binding (above); AiPolicyImpl.kt and DefaultAiCapabilityRouter.kt (+ tests) added to B2b source list in ai-fixes-plan.md as an orchestrator amendment; router and AiPolicyImpl TRANSACTION_INSIGHT comments reworded as interim (comment-only).
2. LEGAL_PATHS.md Privacy/Cloud AI: separate "On-device-only capabilities" block (NOTIFICATION_PARSE, REVIEW_PRIORITIZATION, SEMANTIC_DEDUPE, TRANSACTION_INSIGHT) replaces the single-capability arrow step.
3. DefaultAiCapabilityRouterTest: PrivacyGate mock held in a field; new INV-15 independence test (dashboardBriefingEnabled=false → ON_DEVICE); AE-27 positive control (DASHBOARD_BRIEFING, CLOUD mode → CLOUD); TRANSACTION_INSIGHT decisions assert zero privacyGate.check and zero secureKeyStorage.hasKey calls.
4. GetAiRuntimeStatusUseCaseTest: TRANSACTION_INSIGHT test renamed to a label test (router mocked).
5. es ai_capability_transaction_insight made singular ("Análisis de transacción").

## B2a DONE (2026-10-23)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS; privacy-security-guardian ACCEPT-WITH-MINORS (no cloud path for TRANSACTION_INSIGHT); architecture-guardian ACCEPT-WITH-MINORS (3rd attempt; two died on API timeouts). Minors applied (see "B2a review fixes"); AG finding 1 (LEGAL_PATHS wording: the guarantee is canUseCloud → AiPolicy.canUseCloudFor == false, CLOUD_FIRST exclusion is additional) applied by the orchestrator as a docs-only edit after validation.
- Validation (pre-fix): targeted vr-20261023-150728-7be7e1bb PASS, lint vr-20261023-151900-5e27b51b PASS, static-guards vr-20261023-152304-8663b951 PASS. Post-fix: targeted vr-20261023-160100-1e8e9f4f PASS, static-guards vr-20261023-160534-775a9fae PASS. (Final LEGAL_PATHS wording edit is docs-only.)
- Follow-ups (pre-existing, not B2a): (a) the per-capability toggle table exists twice (DefaultAiCapabilityRouter.isCapabilityEnabled and AiPolicyImpl.shouldAllowOnDevice) — make the router delegate to AiPolicy; (b) DebugScreen.supportsCloudFallback re-encodes cloud eligibility and already disagrees with policy on LOCATION_SUMMARY — derive from AiPolicy/router.
- Ordering checks: AE-32 INSIGHT_DISABLED must be delivered by the slice that adds the insight use case; when the first caller of TRANSACTION_INSIGHT (B2d/B2e) is accepted, grep app/src/main for "TRANSACTION_INSIGHT -> settings.aiEnabled" must return 0 (B2b amendment landed first).
- Release-note caution (PSG): until B2d rewires GenerateTransactionInsightUseCase, per-expense insights still route as DASHBOARD_BRIEFING (cloud-capable); do not claim "insights are on-device" for a build with B2a but not B2d.
- Owner review: es/el strings ai_capability_transaction_insight ("Análisis de transacción" / "Ανάλυση συναλλαγής").
Next: B2b (insight key + DataMigration + InstallProvenance; includes the binding §1.6 router/policy switch) and B2c (AppForegroundState port).

## B2b (2026-10-23) — implemented (NOT validated; awaiting compile + targeted-unit-test)
- INV-22 (new field), INV-23, INV-15/16 (binding §1.6 amendment). AE-33..37 (+AE-38 still holds). Schema NONE (DataStore key + DataMigration only).
- Source: AiModels.kt (`transactionInsightEnabled = false`, last field); AiSettingsRepositoryImpl.kt (key via shared `AiSettingsMigrationKeys`, mapper fallback `defaults.transactionInsightEnabled`, `update()` writes key, `produceMigrations` = `[AiInsightSettingsMigration(PackageInstallProvenance(appContext))]`; class now has an internal `() -> DataStore<Preferences>` primary constructor + the `@Inject (@ApplicationContext Context)` secondary, so tests can drive a real DataStore); NEW data/repository/AiInsightSettingsMigration.kt (internal; keys object + DataMigration); NEW domain/ai/service/InstallProvenance.kt (port + `InstallOrigin`); NEW data/repository/PackageInstallProvenance.kt (internal impl); AiPolicyImpl.shouldAllowOnDevice and DefaultAiCapabilityRouter.isCapabilityEnabled TRANSACTION_INSIGHT arms -> `settings.transactionInsightEnabled` (interim comments replaced).
- DI: NO Hilt binding added (provenance is built from the application context handed to `produceMigrations`; the top-level delegate lives outside the Hilt graph). hilt-bindings-map.md unchanged.
- Never-throw / ordering: DataStore runs migrations before first read AND first edit; `shouldMigrate` = key absent; `migrate` rethrows only CancellationException from provenance, any other Exception -> UNKNOWN -> false; `PackageInstallProvenance.classify()` itself catches Exception -> UNKNOWN. Stored briefing value wins (rule 2a), provenance not consulted then.
- Tests: AiSettingsRepositoryImplTest (+AE-33..37, UNKNOWN, migration-before-first-edit via real PreferenceDataStoreFactory, throwing provenance -> settings() emits, update() toggle stays); NEW InstallProvenanceTest (NEW_INSTALL/UPGRADE/anomaly/zero/NameNotFound/SecurityException/failing packageManager/own-package-only); DefaultAiCapabilityRouterTest (+insight off -> DISABLED, briefing off + insight on -> not DISABLED; `fullyCloudEnabledSettings` now sets transactionInsightEnabled = true); AiPolicyTest (TRANSACTION_INSIGHT rows now set the toggle; +2 rows: insight off, insight off with briefing on; size guard 4 -> 6).
- Docs: CODEBASE_SEGMENTS.md Segment 20.
- Ordering check carried: grep app/src/main for "TRANSACTION_INSIGHT -> settings.aiEnabled" returns 0 (done here).
Next: B2c (AppForegroundState port), then B2d.

## B2b review fixes (2026-10-23) — test/comment/doc only, NOT validated
1. INV-7 / spec §4 rule 5: AiSettingsRepositoryImplTest now seeds ai_warranty_extraction_enabled = true and false plus unrelated keys, runs the real migration with NEW_INSTALL and UPGRADE, and asserts stored prefs == seed + only ai_transaction_insight_enabled (4 tests). Fulfils the AE-8 "never rewritten" promise (B1c note above).
2. Production wiring: source-scan test in AiSettingsRepositoryImplTest asserts the `ai_settings` preferencesDataStore delegate declares `produceMigrations = { ctx -> listOf(AiInsightSettingsMigration(PackageInstallProvenance(ctx))) }`.
3a. DefaultAiCapabilityRouterTest "TRANSACTION_INSIGHT is DISABLED when AI is off" now uses aiEnabled = false with allowOnDeviceAi = true and transactionInsightEnabled = true, and asserts the reason names the global AI switch.
3b. AiPolicyTest canUseCloudFor(TRANSACTION_INSIGHT) matrix gained a transactionInsightEnabled dimension (64 -> 128, count guard updated).
4a. AiSettingsRepositoryImpl.kt comment (comment-only) and the DI line in the B2b section above: reason for no Hilt binding is that the top-level delegate is outside the Hilt graph.
4b. AiSettingsRepositoryImplTest class doc corrected (B2b tests use a real file-backed DataStore in a TemporaryFolder).
4c. The vacuous `assertNull(emptyPreferences()[insightKey])` was replaced by a real check that cleanUp() leaves stored prefs unchanged; test renamed.
5. PSG: domain-spec §4 rule 6 reworded (corruption re-resolution: NEW_INSTALL -> true; UPGRADE or UNKNOWN -> false; a never-updated install re-enables). Migration KDoc did not repeat the claim; unchanged.
6. PSG hardening: InstallProvenanceTest plants a capturing Timber tree and asserts only the fixed message is logged, with no throwable and no exception text, for a throwing PackageManager.
Follow-ups (NOT B2b):
- Optional dataExtractionRules excluding device-to-device transfer of ai_settings (pre-existing; allowBackup=false does not stop D2D transfer on Android 12+).
- AiSettingsRepositoryImpl settings() `Timber.e(error, ...)` logs the IOException (debug-only logging; consider a bounded message without the throwable).
Validation request: targeted-unit-test filters AiSettingsRepositoryImplTest, InstallProvenanceTest, AiPolicyTest, DefaultAiCapabilityRouterTest.

## B2b DONE (2026-10-23)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS, privacy-security-guardian PASS (ACCEPT-WITH-MINORS), architecture-guardian ACCEPT-WITH-MINORS. Minors applied (see "B2b review fixes").
- Validation (final tree): targeted vr-20261023-171633-5308b883 PASS, static-guards vr-20261023-172207-d3633291 PASS, lint vr-20261023-173710-231f9d75 PASS.
- Not taken (optional, recorded): AG finding 1 — add a CancellationException rethrow before the broad catch in PackageInstallProvenance.classify() (synchronous, non-suspend, outside the CANCEL-01 scanner; wording-only fit with FG-10). Docs for the Z pass: CODEBASE_INVENTORY (~:403) and COMPLETE-BACKEND-MAP (~:1151) don't list InstallProvenance/InstallOrigin, PackageInstallProvenance, AiInsightSettingsMigration; CODEBASE_SEGMENTS :476 puts port + impl in one bullet.
- B2d check (binding): GenerateTransactionInsightUseCase still requires dashboardBriefingEnabled and routes as DASHBOARD_BRIEFING; B2d must gate on aiEnabled ∧ transactionInsightEnabled ∧ allowOnDeviceAi (plan ~:128), add INSIGHT_DISABLED (AE-32), and enforce INV-16/INV-17.
- Product note (spec §1.5 / §4 rule 2b, owner decision already taken): new installs get the on-device insight on by default; corruption on a never-updated install re-enables it (rule 6 reworded).
Next: B2c (AppForegroundState port + startup registration).

## B2c (2026-10-23) — implemented (NOT validated; awaiting compile + targeted-unit-test)
- NEW port `domain/ai/service/AppForegroundState.kt` (`isAppInForeground(): Boolean`, non-suspending, never throws, default false = fail-safe skip; spec 1.7).
- NEW impl `startup/ProcessAppForegroundState.kt` (`@Singleton`, `@Inject constructor()`, `DefaultLifecycleObserver`, `@Volatile` flag: onStart true, onStop false).
- `AppStartupCoordinator`: new constructor param `foregroundStateObserver: ProcessAppForegroundState`; `registerLifecycleObserver` adds it after the existing `backgroundLifecycleObserver` registration (existing order unchanged).
- NEW `di/AppForegroundModule.kt` (`@Binds @Singleton` AppForegroundState to ProcessAppForegroundState; same singleton instance as the registered observer).
- No consumer yet (B2d consumes it for INV-17).
- Tests: NEW `startup/AppForegroundStateTest`; constructor-only update (added `foregroundStateObserver = mockk(relaxed = true)`) in `AppStartupCoordinatorRecoveryTest`, `RestoreCancelAfterSwapRecoveryTest`, `RestoreMaintenanceModeConfirmationFaultTest`.
- Docs: CODEBASE_SEGMENTS (Segments 12 and 20), hilt-bindings-map (AppForegroundModule), docs/architecture/ARCHITECTURE.md startup line.
- Validation request: targeted-unit-test `*AppForegroundState*`, `*Startup*`, plus `RestoreCancelAfterSwapRecoveryTest`, `RestoreMaintenanceModeConfirmationFaultTest`; then static-guards.

## B2c DONE (2026-10-23)
- Review: reviewer-strict (also covering the architecture-guardian lane; small slice) ACCEPT-WITH-MINORS. Minor applied by the orchestrator (docs-only, after validation): Hilt module count 33 → 34 in hilt-bindings-map.md (:3, :499) and ARCHITECTURE.md (:39, :896) plus AppForegroundModule in the Core list.
- Validation: targeted vr-20261023-180715-b45919d1 PASS, static-guards vr-20261023-181219-f89027b5 PASS.
- B2d carry-forwards: pin the registration (verify the foregroundStateObserver gets registered in an initialize() test, or a source-scan assertion that ProcessAppForegroundState is registered only in registerLifecycleObserver), otherwise removing that line would silently skip every insight; keep the INV-17 backstop mapping BACKGROUND_USE_BLOCKED → Skipped(BACKGROUND_USE_BLOCKED) (ProcessLifecycleOwner ON_STOP is delayed ~700 ms). Optional docs: CODEBASE_INVENTORY DI list and a separate Segment 12 bullet for di/AppForegroundModule.kt.
Next: B2d (rewire GenerateTransactionInsightUseCase onto TRANSACTION_INSIGHT; gates aiEnabled ∧ transactionInsightEnabled ∧ allowOnDeviceAi; INSIGHT_DISABLED AE-32; INV-16/17).

## B2d pass 1 (2026-10-23) — use case only, implemented (NOT compiled/validated; callers broken until pass 2)
- INV-3, 4, 15..21; AE-25, 26, 28, 29, 30, 31, 32, 39, 40. Schema NONE. Legal path: no write path (nothing to ai_artifacts).
- Files:
  - NEW `domain/ai/usecase/TransactionInsightOutcome.kt` (sealed `Generated(artifact)` | `Skipped(reason)`, `artifactOrNull`; enum `TransactionInsightSkipReason` with the 13 spec 1.9 values).
  - `domain/ai/usecase/GenerateTransactionInsightUseCase.kt` rewritten. New ctor: `aiSettingsRepository, onDeviceBriefingService: OnDeviceDashboardBriefingService, aiCapabilityRouter, appForegroundState: AppForegroundState, inputBuilder, timeProvider, aiRuntimeDiagnostics: AiRuntimeDiagnostics`. Dropped: Hybrid service, AiArtifactRepository, AiPolicy (unused once redaction is gone), PrivacySettingsRepository. Flow: settings once -> AI_DISABLED / INSIGHT_DISABLED / ON_DEVICE_NOT_ALLOWED -> foreground short-circuit -> router.decide(TRANSACTION_INSIGHT) (CLOUD -> ROUTE_REJECTED, DISABLED/DETERMINISTIC -> ROUTE_NOT_ON_DEVICE) -> unredacted input -> `withTimeoutOrNull(TRANSACTION_INSIGHT_TIMEOUT_MS)` (null -> TIMEOUT) -> Success non-blank -> Generated (capability TRANSACTION_INSIGHT, mode ON_DEVICE, expiresAt = now + TTL, summary capped at MAX_BRIEFING_LENGTH_CHARS); blank / ParseError -> EMPTY_OUTPUT; AiServiceError.Timeout -> TIMEOUT; any other failure or thrown Exception -> PROVIDER_ERROR. CancellationException rethrown before any diagnostics. Only Skipped outcomes are recorded: `recordInteraction("transaction_insight", reason.name)`. The `Timber.w(e, ...)` is now `Timber.w("...PROVIDER_ERROR class=%s", simpleName)`.
  - `domain/config/AppConfig.kt` (Ai): `TRANSACTION_INSIGHT_TTL_MS` 24h, `TRANSACTION_INSIGHT_TIMEOUT_MS` 3_000, `RECOMMENDATION_JOB_TIMEOUT_MS` 5_000.
- Decision / deviation: GenAI error kinds (BUSY, QUOTA_EXCEEDED, MODEL_UNAVAILABLE, BACKGROUND_USE_BLOCKED) are NOT distinguished: `OnDeviceDashboardBriefingService` (must stay unchanged) carries the code only as free text `"GenAI error code=N"` inside `AiServiceError.Unknown`, and INV-19 forbids text parsing. They collapse to PROVIDER_ERROR (accepted by spec INV-19 / plan G2-12). Consequence: the "BACKGROUND_USE_BLOCKED -> Skipped(BACKGROUND_USE_BLOCKED)" backstop carried from B2c is NOT delivered (enum values exist but are unused). The foreground short-circuit still covers the normal path. Flag for review; widening AiServiceError would be an owner/scope decision.
- Tests (`GenerateTransactionInsightUseCaseTest`, fully rewritten, no test deleted):
  - Retargeted: old `invoke redacts merchant and exact amount for redacted cloud mode` (:71) -> `AE-26 a CLOUD route decision is rejected with zero provider calls`; old `invoke redacts on cloud route when privacy requires it...` (:114) -> `AE-25 invoke generates an on-device TRANSACTION_INSIGHT artifact with unredacted input`. Old private helpers `assertRedacted`/`renderUiText` removed with them (cloud-redaction no longer exists for the insight).
  - Added: AE-25 (truncation, expense unchanged), AE-26 (DISABLED/DETERMINISTIC), AE-28 (background: 0 router, 0 provider, diagnostics), AE-29 (failure result, thrown exception with Timber capture proving no message/throwable logged, ParseError/Timeout/blank mapping), AE-30 (virtual-time TIMEOUT + constants relation), AE-31 (provider throws CE; caller cancelled while suspended; no events), AE-32 (briefing off + insight on -> attempted; insight off -> INSIGHT_DISABLED, 0 router calls), AI_DISABLED precedence, gate-before-foreground, AE-40, AE-39 (constructor reflection: no Hybrid/Cloud/PrivacySettingsRepository/AiArtifactRepository/AiPolicy; 5 saves -> 0 DASHBOARD_BRIEFING decisions).
  - `AiArtifactRepositoryImplTest:161` source scan (expiresAt\s*=\s*now\s*\+) still matches the new use case source.
- Call sites that will NOT compile / misbehave until pass 2:
  1. `ManualExpenseRepository.kt:241-245` `val aiArtifact = generateTransactionInsightUseCase(insertedExpense)` passed as `aiArtifact =` (needs `AiArtifactRecord?`); also :72 private `RECOMMENDATION_JOB_TIMEOUT_MS = 3_000L` (remove; use `AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS`, used at :236, :250) and :251 `catch (e: Exception)` without CE rethrow (INV-4; add `if (e is CancellationException) throw e` first, bounded log instead of `Timber.e(e, ...)`).
  2. `NotificationProcessingPipeline.kt:1745-1749` same pattern; :916 private `RECOMMENDATION_JOB_TIMEOUT_MS = 3_000L` (remove; use AppConfig constant at :1742, :1754); :1757 `Timber.w(e, ...)` should become bounded (INV-3).
  - Adaptation for both: `val outcome = generateTransactionInsightUseCase(expense)` then `aiArtifact = outcome.artifactOrNull`. Deterministic recommendations must be generated and saved for every outcome (INV-18); only external cancellation/outer timeout skips them. Both keep the existing `aiSettings.aiEnabled` gate. The use case never throws except for cancellation, so no extra catch is needed around it.
  - Test constructors: `NotificationProcessingPipelineSourceLinkTest:117`, `...ReliabilityTest:110/137`, `...AtomicityTest:82/140` use `mockk<GenerateTransactionInsightUseCase>(relaxed = true)` and still compile; a relaxed mock now returns a mocked `TransactionInsightOutcome` (artifactOrNull relaxed null) - stub `Skipped`/`Generated` explicitly where a test asserts recommendations/aiArtifact. No other main/test code references the use case.
  - Any Hilt provision of the old dependencies (HybridDashboardBriefingService etc.) needs no change; `OnDeviceDashboardBriefingService`, `AppForegroundState` (B2c AppForegroundModule) and `AiRuntimeDiagnostics` (@Singleton) are already injectable.
- Pass-2 todo: the two callers above; `DefaultAiEnvironmentMonitor.kt:84` CE rethrow; tests `ManualExpenseRepository` recommendation (AE-28..31), `NotificationProcessing*Test` x3 (AE-28), `DefaultAiEnvironmentMonitorTest`, `DashboardFollowThroughEngineTest`, `AiArtifactRepositoryImplTest` (:227 TTL), `SourceScanningArchitectureGuardTest` new guard (b) (use case imports neither Hybrid nor Cloud service), `CancellationSafetyArchitectureGuardTest` removals only (:79, :92); B2c carry-forward: pin `foregroundStateObserver` registration in `AppStartupCoordinator.registerLifecycleObserver` (initialize() test or source scan); docs (ENGINE_INTERACTION_MAP HybridRouter briefing-only, hilt-bindings-map, CODEBASE_SEGMENTS, LEGAL_PATHS Privacy/Cloud AI: insight on-device, foreground-only, never persisted).
- Validation request (after pass 2): `compile`; targeted-unit-test filters from plan B2d. Pass 1 alone cannot compile the app source set.

## B2d pass 2 (2026-10-23) — callers, guards, tests, docs implemented (NOT compiled/validated)
- INV-3, 4, 15..21; AE-28..31 (+ AE-39 guard). Schema NONE. Legal path: no write path; recommendations via the existing caller path.
- Main source:
  - `data/repository/ManualExpenseRepository.kt`: `val outcome = generateTransactionInsightUseCase(insertedExpense)`, `aiArtifact = outcome.artifactOrNull`; private `RECOMMENDATION_JOB_TIMEOUT_MS` removed, uses `AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS` (5_000); catch now rethrows `CancellationException` first and logs `Timber.e("... class=%s", simpleName)` (no throwable/message). `aiEnabled` gate kept; deterministic recommendations saved for every outcome.
  - `data/repository/NotificationProcessingPipeline.kt`: same adaptation; private const removed; catch already rethrew CE, log now bounded (class name only).
  - `data/ai/provider/DefaultAiEnvironmentMonitor.kt`: CE rethrow before the broad `Exception` catch (import added).
- Guards:
  - `CancellationSafetyArchitectureGuardTest`: REMOVED entries `DefaultAiEnvironmentMonitor.kt` (AI, was :79) and `ManualExpenseRepository.kt` (REPOSITORY, was :92), both `CATCH_WITHOUT_CE_RETHROW`. NOT removed: `NotificationProcessingPipeline.kt` (RP-10 owner) - it still has `catch (t: Throwable)` at ~:706 (source-link write inside a Room transaction) without CE rethrow; out of B2d scope. Nothing added.
  - `SourceScanningArchitectureGuardTest`: NEW guard (b) `B2d (b) ...imports_neither_hybrid_nor_cloud_briefing_service` (+ planted negative self-check, reuses `forbiddenTokensPresent`, comment-stripped, fail-closed on missing file); NEW B2c pin `B2c foreground_state_observer_is_registered_only_in_app_startup_coordinator_register_lifecycle_observer` (+ planted negatives: removed/moved registration, uncalled `registerLifecycleObserver`, registration elsewhere, comment-only).
- Tests (none deleted/weakened): NEW `ManualExpenseRepositoryRecommendationTest` (Generated -> artifact passed; every Skipped reason -> null artifact, recommendations saved; aiEnabled=false gate; outer timeout; CE propagates, not logged, permit survives; unexpected exception logged by class only); `DefaultAiEnvironmentMonitorTest` +2 (CE rethrown and not cached; non-CE still UNKNOWN). Unchanged because they do not reference changed signatures/constants: `NotificationProcessing{SourceLink,Reliability,Atomicity}Test` (relaxed mock use case, none asserts `aiArtifact`; Reliability sets aiEnabled=false), `DashboardFollowThroughEngineTest`, `AiArtifactRepositoryImplTest` (:161 scan still matches). Gap: no pipeline-level AE-28 test (launchRecommendationEnrichment is private and the pipeline harness is heavy) - candidate for test-author.
- Docs: `ENGINE_INTERACTION_MAP.md` HybridRouter briefing-only note; `hilt-bindings-map.md` (use case deps changed); `CODEBASE_SEGMENTS.md` Segment 20 (AppForegroundState consumed, use case + outcome listed); `LEGAL_PATHS.md` Privacy/Cloud AI transaction-insight block.
- DEVIATION / FLAG FOR REVIEWER AND OWNER: GenAI BUSY / QUOTA_EXCEEDED / MODEL_UNAVAILABLE / BACKGROUND_USE_BLOCKED collapse to `PROVIDER_ERROR` because `OnDeviceDashboardBriefingService` is unchanged and carries the code only as free text inside `AiServiceError.Unknown` (INV-19 forbids parsing text). Therefore the B2c-carried INV-17 backstop `BACKGROUND_USE_BLOCKED -> Skipped(BACKGROUND_USE_BLOCKED)` is NOT delivered (enum values exist, unused). The foreground short-circuit (ProcessLifecycleOwner ON_STOP delayed ~700 ms) remains the only background guard. Widening `AiServiceError` would be an owner/scope decision.
- Suggested validation: `compile`; targeted-unit-test `"*GenerateTransactionInsightUseCaseTest*"`, `"*ManualExpenseRepository*"`, `"*NotificationProcessing*"`, `"*DefaultAiEnvironmentMonitorTest*"`, `"*DashboardFollowThroughEngineTest*"`, `"*AiArtifactRepositoryImplTest*"`, `"*AiSettingsRepositoryImplTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`; then static-guards.

## B2d review fixes (2026-10-23) - reviewer-strict REJECT findings, NOT compiled/validated
1. MAJOR compile error: `ManualExpenseRepositoryRecommendationTest` tearDown called `Timber.uproot()` (no such overload in Timber 5.0.1); now `Timber.uproot(tree)` with the planted tree. Scanned all test files under `app/src/test` for `uproot()` / `uprootAll`: no other occurrence.
2. MAJOR INV-18 (no starvation): in `GenerateTransactionInsightUseCase` the `withTimeoutOrNull(AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS)` now wraps router decide + input build + generate (private `routeAndGenerate`); null -> `Skipped(TIMEOUT)`. S0/S1 settings gates and the foreground check stay before it in unchanged order. Outer cancellation still propagates (`withTimeoutOrNull` rethrows non-timeout cancellation; `invoke` keeps the `CancellationException` rethrow before the broad `Exception` catch). Tests added in `GenerateTransactionInsightUseCaseTest`: router decide never returns -> Skipped(TIMEOUT) at 3 s virtual time with 0 provider calls; slow router (2.5 s) plus slow generate (1 s) -> TIMEOUT at exactly 3 s (shared budget).
3. MINOR AE-28 pipeline test: added `AE-28 skipped insight still generates and saves deterministic recommendations with a null artifact` to `NotificationProcessingPipelineReliabilityTest` (aiEnabled=true, use case stubbed -> `Skipped(APP_NOT_FOREGROUND)`; verifies use case 1x, `generateRecommendations(any(), null, any())` 1x, `saveAll` 1x). Closes the "no pipeline-level AE-28 test" gap noted in B2d pass 2.

Follow-ups (not done here):
- (a) OWNER-VISIBLE at Z: INV-17 last bullet is not met word-for-word. GenAI BACKGROUND_USE_BLOCKED / BUSY / QUOTA_EXCEEDED / MODEL_UNAVAILABLE collapse to PROVIDER_ERROR (INV-19 permits). Widening `AiServiceError` to carry these codes typed is a separate owner decision. The enum values in `TransactionInsightSkipReason` for these four are currently unused.
- (b) Dead-code cleanup candidate: the `shouldRedact` branch in `TransactionInsightInputBuilder` and the `insight.redactForPrompt` branch in `DashboardBriefingPromptFormatter` are unreachable from production now that the insight is on-device and unredacted.
- (c) `DefaultAiEnvironmentMonitor` ~:81 and ~:87 `Timber.w(e, ...)`: change to class-name-only logging (INV-3) when next touched.
- Suggested validation (this fix): `compile`; targeted-unit-test `"*GenerateTransactionInsightUseCaseTest*"`, `"*ManualExpenseRepositoryRecommendationTest*"`, `"*NotificationProcessingPipelineReliabilityTest*"`.

## B2d final fixes (2026-10-23) - validation/review findings, NOT compiled/validated
1. Cancellation guard ratchet (static-guards FAIL "DECREASED - 2 findings resolved"): removed exactly the two resolved G-CANCEL-01 entries (`DefaultAiEnvironmentMonitor.kt`, `ManualExpenseRepository.kt`) from `config/baselines/cancellation.json`, from both `fingerprints` and `occurrence_counts` (removals only; plan-approved FG-07/N12). The file has no total/summary field; no test or script pins the baseline count (grep of `scripts/` and `app/src/test` found no such pin). The `generated` timestamp is unchanged.
2. Guard (b) in `SourceScanningArchitectureGuardTest` also forbids the bare `DashboardBriefingService` interface via `\bDashboardBriefingService\b` (OnDeviceDashboardBriefingService is not matched); new planted-negative test `B2d (b) negative_fixture_bare_dashboard_briefing_service_interface_is_caught`.
3. `GenerateTransactionInsightUseCaseTest` AE-39: constructor parameter types are now asserted as the exact set of 7 (AiSettingsRepository, OnDeviceDashboardBriefingService, AiCapabilityRouter, AppForegroundState, TransactionInsightInputBuilder, TimeProvider, AiRuntimeDiagnostics); the "five foreground saves" test is renamed to say it proves `decide(TRANSACTION_INSIGHT)` is called and `DASHBOARD_BRIEFING` never.
4. `DefaultAiEnvironmentMonitor` catch blocks: `Timber.w(e, ...)` replaced with bounded logs (exception class simple name, plus GenAiException `errorCode` in the first catch); no throwable or message (INV-3). Closes follow-up (c) above.
5. Docs wording (LEGAL_PATHS B2d block, CODEBASE_SEGMENTS): "never persisted" replaced with "never written to ai_artifacts; summaryText may become recommendation text persisted via the existing RecommendationRepository.saveAll path (7-day expiresAt, INV-20); recommendations are generated for every outcome when aiEnabled".
6. `ai-fixes-plan.md` Guard/policy edit assignment: added the B2c registration-pin row (added in B2d; strengthening) and noted guard (b)'s bare-interface token.
Follow-ups (pre-existing, not changed here):
- `DashboardFollowThroughEngine` stores `sourceArtifactId` "0" (the `AiArtifactRecord.id` default).
- Recommendation retention: `RecommendationDao.deleteExpired` only deletes `status = 'EXPIRED'` and no production caller of the cleanup was found; logged on the lanes board.
- Suggested validation: `compile`; targeted-unit-test `"*GenerateTransactionInsightUseCaseTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`, `"*DefaultAiEnvironmentMonitorTest*"`; static-guards (cancellation ratchet) and guard_docs_truth.

## B2d DONE (2026-10-23)
- Reviews: reviewer-strict round 1 REJECT (Timber.uproot() compile error; INV-18 timeout did not cover routing; missing pipeline AE-28 test) → fixed; privacy-security-guardian ACCEPT-WITH-MINORS; architecture-guardian ACCEPT-WITH-MINORS; all minors applied ("B2d review fixes", "B2d final fixes"). Re-review of the round-1 MAJORs was done through the AG/PSG passes on the fixed tree plus the green validation below (both MAJOR fixes are mechanical and test-pinned).
- Validation: targeted vr-20261023-195250-3491d310 PASS; static-guards vr-20261023-195739-37dbfe31 PASS (cancellation baseline ratcheted 92 → 90 by removing the two resolved entries). Earlier: targeted vr-20261023-192448-5a646edf FAIL (missing currentTime import; orchestrator one-line fix), static-guards vr-20261023-192906-81d0d3d7 FAIL (ratchet DECREASED, fixed).
- Owner-visible at Z: GenAI BUSY/QUOTA_EXCEEDED/MODEL_UNAVAILABLE/BACKGROUND_USE_BLOCKED collapse to PROVIDER_ERROR (INV-19); INV-17 last bullet not met word-for-word; widening AiServiceError is a separate owner decision.
Next: B2e (insight settings row UI; cheap reviewer + PSG copy check; lint).

## B2e (2026-10-23) — implemented (NOT compiled/validated)
- UX 2.1/2.2, INV-15/16, AE-32. Schema NONE. Legal path: settings write via `AiSettingsRepository.update` only; no worker/arming path.
- `AiSettingsViewModel`: `setTransactionInsightEnabled(enabled)` = `update(transform)` with NO `syncProactiveBriefings` (never invokes `SyncProactiveBriefingWorkUseCase`, AE-32); reuses slice A's bounded error (`ai_error_settings_save_failed`, class-name-only log, CE rethrow). `TRANSACTION_INSIGHT` appended LAST to the explicit runtime capability list (needed: the row's badge/status come from its runtime entry; last position keeps it out of `highestPriorityMessage` precedence).
- `AiSettingsScreen`: new private `TransactionInsightRow` directly after "Dashboard briefing". State is a pure internal function `transactionInsightRowState(settings, runtime)` (permanent platform limit -> switch disabled + on-device message; else AI off -> `ai_insight_needs_ai`; on-device off -> `ai_insight_needs_on_device`; insight off -> `ai_status_disabled`; no runtime -> `ai_status_not_loaded`; AVAILABLE -> Ready; other -> Needs attention + `onDeviceMessage`). Never renders a route line or cloud-fallback hint; always shows `ai_insight_desc` as its own text node. It uses the on-device status text (`onDeviceMessage`), never the router's combined reason (which names cloud).
- `GetAiRuntimeStatusUseCase` (B2a heads-up, outside the nominal write set, small): for `TRANSACTION_INSIGHT` with `DETERMINISTIC_FALLBACK` the message is `onDeviceMessage ?: "On-device AI is turned off in settings."` instead of the router route reason (keeps the bottom runtime list, `highestPriorityMessage` and guidance card free of cloud wording). Other capabilities unchanged.
- Label decision: BOTH keys kept deliberately. `ai_insight_label` ("Transaction insight (on-device)", UX 9) is the matrix-row label; B2a's `ai_capability_transaction_insight` ("Transaction insight") stays for the runtime status list / debug labels where the qualifier is redundant.
- Strings (values/es/el): `ai_insight_label`, `ai_insight_desc`, `ai_insight_needs_ai`, `ai_insight_needs_on_device`. es/el are best-effort, OWNER REVIEW needed.
- Tests: `AiSettingsViewModelTest` +6 (persists only the insight flag; false path; never invokes Sync (AE-32); bounded failure; CE propagates; runtime list ends with TRANSACTION_INSIGHT); NEW `AiSettingsInsightRowStateTest` (7, pure state table); `GetAiRuntimeStatusUseCaseTest` +3 (insight fallback message x2, other capability unchanged). No test deleted/weakened.
- Not done: no Compose UI test of the row (none exist for this screen); verify TalkBack order and 200% font manually.
- Suggested validation: targeted-unit-test `"*AiSettingsViewModelTest*"`, `"*AiSettingsInsightRowStateTest*"`, `"*GetAiRuntimeStatusUseCaseTest*"`; `lint`; static-guards if the reviewer asks.

## B2e review fixes (2026-10-24) — NOT compiled/validated
- Summary message: `GetAiRuntimeStatusUseCase` now excludes `TRANSACTION_INSIGHT` from `highestPriorityMessage` (firstOrNull filter), so the insight's own DISABLED reason never feeds the shared guidance card; the insight row keeps its own message. New test: insight DISABLED + every other capability ready gives a null summary message while the insight row keeps its message.
- Runtime label is singular: "transaction insight" (use case label and the two existing test expectations updated from "transaction insights").
- el wording: `ai_insight_desc` uses "μία φράση" for the generated sentence (the "cloud" / "AI" terms already matched the nearby el ai_* strings).
- Style: blank line between `setDashboardBriefingEnabled` and the new KDoc is in place.
- Note: the insight switch is disabled only on permanent platform limits per UX 2.2; the stored `transactionInsightEnabled` value is never touched by that state.
- es/el strings remain best-effort and need owner review.
- Suggested validation unchanged: targeted-unit-test `"*GetAiRuntimeStatusUseCaseTest*"`, `"*AiSettingsViewModelTest*"`, `"*AiSettingsInsightRowStateTest*"`; `lint`.

## B2e DONE (2026-10-24)
- Review: cheap review + PSG copy check (privacy-security-guardian, sonnet) ACCEPT-WITH-MINORS; minors applied (summary message excludes TRANSACTION_INSIGHT; singular runtime label; el "μία φράση"). Out-of-write-set GetAiRuntimeStatusUseCase change accepted by the orchestrator (insight DETERMINISTIC_FALLBACK shows the on-device message, never cloud wording).
- Validation (final tree): targeted vr-20261023-212703-1400d4f5 PASS; lint vr-20261023-213150-8c6853fc PASS. Earlier: targeted vr-20261023-203543-d4635d86 PASS, lint vr-20261023-204345-f0c1a3ea PASS (pre-minors).
- Owner review: es/el strings ai_insight_label/_desc/_needs_ai/_needs_on_device (and B2a ai_capability_transaction_insight).
Next: B3a (briefing delivery outcome; OD-2) — MUST honour the CROSS-LANE requirement on build/release-lead-notes/lanes-board.md (use sendAiBriefingReadyForKind(BRIEFING) / sendBudgetAlertForKind(WARRANTY, …) forms at merge; 3-value DeliveryResult; addNotificationsSent only on DELIVERED).

## B3a pass 1 (2026-10-24) — briefing side implemented (NOT compiled/validated)
- Source: NEW `domain/ai/usecase/BriefingDeliveryOutcome.kt` (closed enum DELIVERED, SKIPPED_SETTINGS_OFF, SKIPPED_DUPLICATE, SKIPPED_NO_FRESH_ARTIFACT, NOTIFICATION_PERMISSION_DENIED, NOT_DELIVERED). `DeliverProactiveBriefingNotificationUseCase` returns it (existing checks kept; the single posting call `notificationService.sendAiBriefingReadyWithResult(...)` unchanged; the DeliveryResult mapping is an exhaustive `when` statement with NO else). `DailyBriefingWorker`: `requiresNotificationPermission=false`, `requiredCapabilities` + fresh-artifact short-circuit kept, injected `NotificationPermissionChecker` (last ctor param), literal `notificationPermissionChecker.areNotificationsEnabled()` immediately before delivery, `addNotificationsSent()` only on DELIVERED, denied path emits `DiagnosticEvent(WORKER, "briefing_delivery", SKIPPED, NOTIFICATION_PERMISSION_DENIED, entityType "DailyBriefingWorker")`; run stays SUCCESS. `SyncProactiveBriefingWorkUseCase` arms iff `aiEnabled ∧ briefing ∧ (allowCloudAi ∨ proactive)`. `AiSettingsViewModel.setAllowCloudAi` now passes `syncProactiveBriefings = true`.
- Tests: DailyBriefingWorkerTest (setup stubs DELIVERED + permission checker true; 6 `returns Unit` deliver stubs retargeted to DELIVERED; new AE-41/42/43 + guard-request pin + bounded-diagnostic + fresh-short-circuit + non-delivered outcomes + use-case PERMISSION_DENIED outcome + cancellation); DeliverProactiveBriefingNotificationUseCaseTest (existing 6 now assert the outcome; +2 outcome tests); SyncProactiveBriefingWorkUseCaseTest (:43 retargeted to cloud=false per AE-45; AE-46; 16-row AE-47 table; cloud+proactive-off arming; AE-48 Sync-side); AiSettingsViewModelTest (+3 setAllowCloudAi tests).
- KNOWN INTERIM RED: `SourceScanningArchitectureGuardTest.notification_posting_workers_require_permission_flag` fails for DailyBriefingWorker.kt until pass 2 adds it to `optionalNotificationWorkers` TOGETHER with WarrantyExpirationWorker.kt (plan: "lands once"). Do not validate static-guards / SourceScanning between pass 1 and pass 2.
- MERGE NOTE (feature/app-lock-privacy-mode, CROSS-LANE on lanes-board.md): the use case's posting call must become `sendAiBriefingReadyForKind(BRIEFING, …)`, and the outcome mapping must add `DeliveryResult.PERMISSION_DENIED -> return BriefingDeliveryOutcome.NOTIFICATION_PERMISSION_DENIED` (non-delivery). The exhaustive `when` has no else, so the merge fails to compile until this is mapped. Move the two app-lock S6 briefing use-case tests onto the outcome. The worker already gates `addNotificationsSent()` on DELIVERED (closes the S6 residual) and already handles a use-case-level NOTIFICATION_PERMISSION_DENIED outcome (diagnostic, count 0). DailyBriefingWorker is not expected to touch NotificationService directly; if app-lock changed it, re-check.
- Pass 2 TODO: `WarrantyExpirationWorker` (requiresNotificationPermission=false, inject checker, always reconcileExpiredItems, local reminder guard, narrow/remove expired broad-catch allowlist entry, AE-51 + ctor fakes in WarrantyExpirationWorkerTest, setup allows NOTIFICATION_CAPTURE); `SourceScanningArchitectureGuardTest` :408 allowlist += both workers; broad-catch allowlist removal (:545-589) if catch fixed; docs `LEGAL_PATHS.md` Workers optional-notification pattern (~:494) and `ARCHITECTURE.md` (~:1269-1271).

## B3a pass 2 (2026-10-24) — warranty side + guard allowlist implemented (NOT compiled/validated)
- Source: `service/warranty/WarrantyExpirationWorker.kt`: `requiresNotificationPermission=false`; ctor += `notificationPermissionChecker: NotificationPermissionChecker`, `diagnosticEventWriter: DiagnosticEventWriter` (appended last). `reconcileExpiredItems`, `recoverStaleClaimed` and the 90-day prune ALWAYS run. Reminder lookup + `deliverReminder` loops are wrapped in `if (notificationPermissionChecker.areNotificationsEnabled())`; else one `DiagnosticEvent(WORKER, "warranty_reminder_delivery", SKIPPED, NOTIFICATION_PERMISSION_DENIED, entityType "WarrantyExpirationWorker")`; run stays SUCCESS, `addNotificationsSent()` unchanged (only on a delivered reminder). `deliverReminder` signature/name/DAO call order UNCHANGED (FG-13 db_ownership rows untouched). KDoc updated.
- Broad catch: the old `try { } catch (CancellationException) { throw e } catch (Exception) { Timber.e(e); throw e }` wrapper only logged and rethrew; it was REMOVED (the guard owns classification and cancellation rethrow; also drops a raw-throwable log). The `kotlinx.coroutines.CancellationException` import is gone. No allowlist was extended.
- Guard edits (`SourceScanningArchitectureGuardTest`): `optionalNotificationWorkers` = ReceiptMatchingWorker.kt + DailyBriefingWorker.kt + WarrantyExpirationWorker.kt (G2-3 pre-approved; rule body NOT changed: optional workers must have no `requiresNotificationPermission = true` and must contain the literal `notificationPermissionChecker.areNotificationsEnabled()`). Removed the EXPIRED (2026-09-30) `WarrantyExpirationWorker.kt` entry from the local `broadCatchAllowlist` in `workers_with_broad_catch_must_rethrow_cancellation` (no broad catch remains, so the rule passes without it). KNOWN_VIOLATIONS removals only; nothing added. Untouched: `CancellationSafetyArchitectureGuardTest` allowlist entry (line ~123, FALSE_POSITIVE_CE_RETHROW, expires 2026-12-31) is now harmless/unused for this file; the stale check there only tests file existence. Other unrelated expired entries remain (LocationBackfill/MerchantKeyBackfill/ReceiptMatching/BillReminder 2026-09-30) — out of scope, flagged for the owner.
- Tests (`WarrantyExpirationWorkerTest`): setup += permission-checker mock (default enabled), relaxed `DiagnosticEventWriter` mock, guard-request capture (`lastGuardRequest`); ctor fakes passed in `buildWorker`. New: guard request pin (`requiresNotificationPermission=false`), AE-51 (denied: reconcile exactly once, `addRowsUpdated(1)`, no `getWarrantiesExpiringSoon`, no `sendBudgetAlert`, no `addNotificationsSent`, no delivery row, exactly one bounded diagnostic with reason/outcome/pipeline/entityType, SUCCESS), enabled path emits no denial diagnostic. Existing test `warranty run skipped when notifications disabled does not notify` kept with identical name/assertions; only its comment changed (guard-level skip no longer includes permission). Nothing deleted or weakened. NOTIFICATION_CAPTURE: the guard is mocked in this test, so privacy gating is not exercised; no stub needed. AE-51's "W1 EXPIRED with needsReview=true" half is repository behaviour (INV-11, `reconcileExpiredItems`), covered by the WarrantyTrackerRepository tests, not re-asserted here because the repository is a mock.
- Docs: `docs/architecture/LEGAL_PATHS.md` Workers section (~:585) gained the optional-notification pattern bullet covering ReceiptMatching, DailyBriefing, WarrantyExpiration; `docs/architecture/ARCHITECTURE.md` (:51 gate paragraph, WarrantyExpirationWorker row, DailyBriefingWorker row). `CODEBASE_INVENTORY.md:667` still says "requiresNotificationPermission" (snapshot; not edited).
- MERGE NOTE (feature/app-lock-privacy-mode): there `WarrantyExpirationWorker` posts via `sendBudgetAlertForKind(WARRANTY, …)` with PERMISSION_DENIED -> `markFailed`. At merge keep THAT call form AND this slice's local check / `requiresNotificationPermission=false`. Note the local check now precedes seeding, so a denied run never creates or fails a delivery row; the app-lock PERMISSION_DENIED -> markFailed path only fires on a race (permission revoked between check and post). Re-add the new ctor params to any app-lock test fakes.
- Suggested validation (validation-runner, serial): `targeted-unit-test` with plan B3a filter list incl. `*WarrantyExpirationWorkerTest*`, `*DailyBriefingWorkerTest*`, `*DeliverProactiveBriefingNotificationUseCaseTest*`, `*SyncProactiveBriefingWorkUseCaseTest*`, `*AiSettingsViewModelTest*`, `*SourceScanningArchitectureGuardTest*`, `*CancellationSafetyArchitectureGuardTest*`; then `static-guards` (FG-06 / FG-13 db_ownership signatures should be unchanged because deliverReminder is unchanged). The pass-1 interim red on `notification_posting_workers_require_permission_flag` should now be green.

## B3a review fixes (2026-10-24) — NOT compiled/validated
1. COMPILE: `DailyBriefingWorkerTest.permissionDeniedEvent()` is now `private fun MockKMatcherScope.permissionDeniedEvent()` (import `io.mockk.MockKMatcherScope`); every call site is inside `coVerify { }`. No other B3a test file had the pattern.
2. `DeliverProactiveBriefingNotificationUseCase(dateKey, startedAt, notificationId, notificationsAllowed)` checks `notificationsAllowed` only AFTER settings, dedupe and fresh-artifact checks (returns NOTIFICATION_PERMISSION_DENIED). The worker passes `notificationsAllowed = notificationPermissionChecker.areNotificationsEnabled()` (literal check immediately before the call) and records the permission diagnostic only for that outcome. Tests: use-case test call sites updated + 4 new permission-denied ordering tests (settings off, duplicate, no fresh artifact, fresh); worker tests retargeted (denied + denied outcome records the diagnostic once, count 0; denied + SKIPPED_SETTINGS_OFF / SKIPPED_DUPLICATE record no diagnostic; fresh-artifact short-circuit never consults the checker).
3. Removals: `CancellationSafetyArchitectureGuardTest` FALSE_POSITIVE_CE_RETHROW entry for WarrantyExpirationWorker.kt; `scripts/allowlists/worker_allowlist.yml` `WarrantyExpirationWorker.broadCatch` entry (daoMutation entry kept). No count pin on either list found by grep (scripts/, app/src/test).
4. `BriefingDeliveryOutcome.NOTIFICATION_PERMISSION_DENIED` KDoc covers both the local-check denial and, after the app-lock merge, `DeliveryResult.PERMISSION_DENIED` (post refused by a SecurityException).
5. `docs/features/ai-fixes/domain-spec.md` 1.10 records the sixth value and the merge mapping, tagged "[amended B3a, 2026-10-24]".
6. LEGAL_PATHS Workers bullet: the NOTIFICATION_PERMISSION_DENIED diagnostic sentence is limited to DailyBriefingWorker and WarrantyExpirationWorker.
7. `WarrantyExpirationWorkerTest`: all `addNotificationsSent` zero-count verifies use `addNotificationsSent(any())`; the kept guard-skip test is renamed `warranty run skipped by a non-permission guard gate does not notify` and stubs `Skipped(PRIVACY_DENIED)`.
Merge notes (feature/app-lock-privacy-mode):
- (a) Drop app-lock's outer CancellationException/Exception wrapper in WarrantyExpirationWorker at merge (this slice removed it; the guard owns classification).
- (b) The BriefingDeliveryOutcome KDoc already covers both meanings; map `DeliveryResult.PERMISSION_DENIED -> NOTIFICATION_PERMISSION_DENIED` in the use case (exhaustive `when`, no else).
- (c) App-lock's WarrantyExpirationWorker KDoc and any tests stubbing guard `Skipped(NOTIFICATION_PERMISSION_DENIED)` must be retargeted (permission is no longer a guard gate for this worker).
- (d) Lanes board: C:\Users\panos\Desktop\Cost-agregator\build\release-lead-notes\lanes-board.md. Owner-visible: other expired 2026-09-30 broad-catch entries remain (LocationBackfill/MerchantKeyBackfill/ReceiptMatching/BillReminder); out of scope.

## B3a DONE (2026-10-24)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS (fixes applied), architecture-guardian ACCEPT-WITH-MINORS (CODEBASE_SEGMENTS :302 fixed by the orchestrator, docs-only), privacy-security-guardian ACCEPT-WITH-MINORS (no consent bypass: guard capability CLOUD_AI_DAILY_BRIEFING + EffectiveCloudAiPolicy + use-case gates + service check all still apply; toggling allowCloudAi only schedules at next midnight).
- Validation (final tree): targeted vr-20261023-230829-270d61eb PASS; static-guards vr-20261023-231819-a8e925d8 PASS. (Earlier targeted vr-20261023-222424-f307361b FAIL: test compile, fixed.)
- OWNER-VISIBLE (spec INV-25 arming, plan-approved; PSG asks for explicit disclosure): users with AI on, dashboard briefing on, cloud AI allowed and privacy cloud on, but "Proactive AI briefings" OFF, now get the cloud daily briefing generated in the background every midnight (before: only when they opened the dashboard). All consent gates still apply. B3b copy MUST disclose: the daily briefing is generated in the background whenever Dashboard briefing and Cloud AI are on; "Proactive" only controls the notification.
- Naming nit: in the spec, OD-2 is the warranty reconciliation decision (INV-29); the arming change is INV-25.
- Existing (not B3a) log-hygiene candidates: DailyBriefingWorker ~:138 Timber.w(e, …) on timeout; ~:155 `${result.error}` in a log message; delivered notification shows summary.take(180) (lock-screen review, app-lock lane covers presentation at merge).
- Z refresh: CODEBASE_INVENTORY.md:667 still says requiresNotificationPermission for WarrantyExpirationWorker (snapshot).
Next: B3b (proactive copy + notifications-blocked caption) — include the INV-25 disclosure; then Z.
