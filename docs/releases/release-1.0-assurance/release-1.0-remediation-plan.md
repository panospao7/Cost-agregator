# Release 1.0 remediation plan (PLAN ONLY — nothing approved for execution)

```text
Workflow / phase: release-1.0-assurance / remediation planning (approved 2026-10-15: planning only)
Source candidate: HEAD 279b7c92; app/ == ea446316 (pin verified by lead before and after)
Author: release-planner, requested Opus 5.5/xhigh; self-reported Opus 5.5 (effort not observable); read-only.
Persisted by: release-orchestrator (lead). The plan is a planner draft: it is NOT independently reviewed and NOT an
  approval. Every R0 test authoring, every batch, every runner queue, every guard/allowlist/policy edit and every
  owner decision (D-xx) needs its own explicit human approval.
Evidence basis: release-1.0-verification-ledger.md (static, independently verified findings), regression ledger,
  handoff. No runtime evidence exists for any finding yet.
Status: PENDING human review
```

Lead notes:
- Fail-first R0 tests are planned to stay uncommitted until their fix batch lands. While present in the worktree they
  change app/ (re-pin required) and would make broad suites (app-check/unit-tests) fail; run them only through
  targeted-unit-test until their batch lands.
- Robolectric fidelity for R0-1 (nested SQLite transaction semantics) is uncertain; the device twin (R0-1b) is the
  stronger evidence and needs D-22.
- The "Findings" severities used here are those after independent static verification (2 P1, 12 P2).

## 1. Principles and constraints

- One legal path per operation (LEGAL_PATHS): expense writes only via TransactionLifecycleCoordinator -> insertAtomic();
  receipt mutations via ReceiptLinkService / ReceiptLifecycleCoordinator; group expenses via GroupTransactionCoordinator.
  No new direct-DAO writes from VM/UI; no duplicated business rule (share predicates, never copy).
- Room: no schema/version change planned in any batch. If any fix needs one: stop -> version bump + migration +
  snapshot + migration tests (FG-21) + explicit approval; no destructive migration; no backfill migration without approval.
- Guards: no weakening, no allowlist/baseline growth (FG-06/FG-07). Every edit to config/guards/db_ownership_policy.yml
  or scripts/allowlists/*.yml requires human approval (shrinks and rationale corrections included). Gates by ID only:
  FG-10 cancellation, FG-11 worker, FG-12 UI/DAO ownership, FG-13 write barrier, FG-14 transaction ownership,
  FG-15 receipt links, FG-16 privacy, FG-17 money, FG-19 ignored tests, FG-20 build/test, FG-26 docs.
- Privacy/cancellation: fail closed; bounded reason codes only (no e.message/paths/SQL/model output in persisted or
  displayed diagnostics); always rethrow CancellationException; metrics only after real success.
- Tests: real Room (Robolectric in-memory or androidTest) for DB/lifecycle/money; hand-derived oracles; no MockK
  suspend-extension mocking on lifecycle paths; never @Ignore/delete/relax (FG-19). Changing a test that pins the
  defective behavior is a spec change needing explicit approval (RG01A-1, RG05A-1).
- Process: one approved batch at a time; 1 writer, <=2 read-only workers, 1 validation owner. Implementer Sonnet/high;
  reviewer-strict (Opus/xhigh) for every batch (all T1). Max 2 review + 2 debug iterations then PARTIAL/BLOCKED.
  Orchestrator is the sole ledger writer.
- Validation only via validation-runner, serially:
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <p> [-TestFilter <one pattern>]`.
  -TestFilter takes one pattern per run (regex `^[A-Za-z0-9_.$*?\-]+$`): multiple filters = multiple serial runs or a named
  unit-test-shard (shard names from test-shards.json, not yet read). connected-tests runs the WHOLE androidTest suite
  (no class filter, 2 h timeout, device required) -> needs human approval or an approved narrow runner addition with
  independent review (D-22). Missing/stale/RUNNING/errored results are never PASS.

## 2. Phase R0 — deciding runtime tests (authoring: release-test-author Sonnet/high; execution: validation-runner)

R0 tests assert the intended behavior (fail-first: fail now, pass after the fix), except R0-1 which permanently
characterizes framework semantics. Record runner run ID + deciding observation (counts/result types only). Adding tests
changes app/ -> lead records a new pin (Phase 0 delta). reviewer-strict for R0-1/R0-2; cheap review for the rest.

| # | Finding | Test file (new) | Fixture | Deciding observation -> severity | Profile |
| --- | --- | --- | --- | --- | --- |
| R0-1 | RG11-H2 Room semantics | (a) app/src/test/java/com/yourname/expensetracker/data/database/NestedTransactionSemanticsTest.kt (Robolectric); (b) twin app/src/androidTest/.../data/database/NestedTransactionSemanticsInstrumentedTest.kt | In-memory AppDatabase with the production builder config/callbacks; record PRAGMA foreign_keys. T1 `withTransaction { insert A; runCatching { withTransaction { error("x") } } }`; T2 control (exception caught inside nested block); T3 nested FK-violating `UPDATE expenses SET categoryId=<missing>` swallowed outside the nested block | A absent + outer returns normally -> mechanism CONFIRMED (silent rollback). A present -> REFUTED (RG11-H2 closes; swallow becomes P3 hygiene). Outer throws -> loud, downgrade P2/P3. T2 present -> control valid | (a) targeted-unit-test `*NestedTransactionSemanticsTest`; (b) connected-tests (D-22 + device) before final acceptance |
| R0-2 | RG11-H2 end-to-end + V11-NEW-1 trigger | app/src/test/.../data/repository/ReviewQueueRepositoryNestedRollbackTest.kt | Real Room; real ReviewQueueRepository + ReceiptLinkService + DefaultExpenseCategoryAssignmentService + DAOs; fakes only for non-DB ports. Categories C, D; receipt R with majority userCorrectedCategoryId=C; CategoryDao.mergeCategories(C->D) (variant: delete C); pending review scannedReceiptId=R, no category. Variants: createExpenseAndLinkReceipt; standalone linkReceiptToExpense | Success + review PENDING + 0 expenses + 0 links + post-commit ran -> P1 CONFIRMED and V11-NEW-1 confirmed as trigger. Failure result, rows absent -> contained, P2 (approval permanently blocked; still fix). Success with rows present -> trigger refuted, RG11-H2 -> P2 (I/O-class triggers only) | targeted-unit-test `*ReviewQueueRepositoryNestedRollbackTest` |
| R0-3 | RG12-01 | app/src/test/.../domain/receipt/lifecycle/ReceiptDuplicateFingerprintCollisionTest.kt | Coordinator-level real Room, real detector + DAO; "ACME FUEL / UNLEADED / TOTAL 12.50 EUR" vs 13.75, parsedDate null, different image bytes; control with date + currency; VM variants (first receipt linked / unlinked) | Second inserted=false with first id, 1 row, POST_OCR_DUPLICATE_DRAFT cleanup, image deleted -> P1 kept; control 2 rows -> valid; VM variants decide branch A (Save hidden) vs B (old receiptId prefill; V12-NEW-B) | targeted-unit-test (one run per filter) |
| R0-4 | RG01A-1 frequency/fault | app/src/test/.../data/backup/RestoreMaintenanceModeConfirmationFaultTest.kt | Robolectric + WorkManagerTestInitHelper, real mode persistence in temp filesDir. (a) 200 cold-start exit(false)/resetCancellable() runs recording confirmation latency/failures; (b) injected failed getWorkInfosForUniqueWork future + clock > 10 s, then rebuilt coordinator | (b) CRITICAL persists, writes denied, startup BLOCKED -> permanence proven; (a) zero failures & p99 << 10 s -> frequency P3-leaning; failures -> P2; frequent -> P1. Robolectric timing != device (device frequency is a runtime RT item). Recommend fixing regardless (CL-19 spec requires a recovery path) | targeted-unit-test |
| R0-5 | RG01-N1 | app/src/test/.../data/backup/ReceiptAssetRepeatRestoreTest.kt | Real Room + real filesDir: restore receipt 5 -> restored_5.jpg; reset row to absent original path; restore again with a new journal | FAILED(TARGET_COLLISION) + missing pointer + unreferenced restored_5.jpg -> CONFIRMED (optional: next image export fails RECEIPT_ASSET_COLLECTION_FAILED); completes pointing at restored_5.jpg -> REFUTED | targeted-unit-test |
| R0-6 | RG06A-1 | app/src/test/.../domain/usecase/dashboard/DashboardBudgetProvenanceChainTest.kt | In-memory Room, home USD; overall budget USD 1000 created exactly as BudgetScreen :949 builds it (or via BudgetViewModel.addBudget), not hand-stamped; one USD 100 purchase on day 1; now = day 15 of 30; ComputeDashboardWidgetsUseCase | Runway "unavailable", no pace/Block Party -> CONFIRMED (becomes R3 fail-first regression). Intended: remaining 900, burn 6.667/day, runwayDays 135 HEALTHY, simulate(100.0, 0.0, 1000.0, "USD", 0.0) | targeted-unit-test |
| R0-7 (optional) | RG02A-1 sanitizer probe | JVM test per ledger (path with spaces/amount; SQLite "while compiling"; require() decimal) | — | user text survives -> raise to P2 and add a batch | targeted-unit-test |

Other ledger tests (RG04A-1, RG05A-1, RG07A-1, RG02B-1 device) do not change severity; they are authored inside their
fix batches as regression tests.

## 3. Remediation batches (root-cause groups; write sets disjoint unless sequenced)

Shared-file sequencing: app/src/main/res/values/strings.xml is touched by R2 (optional), R3 (optional), R5, R12 -> strict
plan order, distinct appended keys only. config/guards/db_ownership_policy.yml by R1b and R7 only if a grant is needed;
R1b before R7.

| Batch | Findings | Fix shape (smallest) | Write set | Re-check (no edit expected) | Implications / approvals | Review | Effort / risk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | RG11-H2 (P1; gated on R0-1/R0-2) | (1) Inside the assignment transaction read the target category; if missing return a non-throwing outcome (e.g. SkippedCategoryMissing), removing the deterministic FK trigger. (2) ReceiptLinkService: best-effort only for the read-only part (categorization read/majority); an assignment Failed or exception aborts the link transaction with a typed bounded-code exception -> Result.failure at the linkReceiptToExpense boundary (cancellation rethrown; ReceiptAlreadyClaimedException kept). (3) Correct KDoc ExpenseRepository :112-114 | domain/transaction/DefaultExpenseCategoryAssignmentService.kt; domain/transaction/ExpenseCategoryAssignmentPort.kt; domain/receipt/lifecycle/ReceiptLinkService.kt; data/repository/ExpenseRepository.kt (comment); tests R0-1, R0-2 + new ReceiptLinkServiceNestedFailureTest.kt (real Room) | ReviewQueueRepository :309-323; ReceiptLifecycleCoordinator :1193/:1210/:1607; ReceiptMatchingViewModel :158/:202/:272/:335; ReceiptMatchingWorker :86 (terminal vs retry classification, no endless retry, FG-11); ReceiptSideEffectPlanner :396; exhaustive `when` over CategoryAssignmentOutcome; Review UI error path | no schema; DI adds CategoryDao (confirm no FG-12 grant needed); FG-14/15/10; D-02 | reviewer-strict + architecture-guardian | M / medium (silent rollbacks become visible failures — intended). Follow-up: nested-transaction swallow sweep (HZ-02/HZ-04); a new detector guard needs approval (FG-02/FG-08) |
| R1b | V11-NEW-1 stale category refs (conditional on R0-2 or a refutation pass) | CategoryDao.mergeCategories (:187; existing UPDATE :150 covers only suggestedCategoryId) also reassigns userCorrectedCategoryId; delete (:59) per D-03; existing stale rows not repaired (would be a migration) — R1 makes them harmless | CategoryDao.kt; CategoryDaoTest (androidTest) or Robolectric DAO test; db_ownership_policy.yml only if a grant is needed (human) | — | FG-12/FG-13; D-03 | reviewer-strict | S |
| R2 | RG12-01 (P1) | D-04 option A: text hit is an automatic duplicate only when both semantic fingerprints are present and equal; otherwise isDuplicate=false (insert + keep image) with advisory matchType (e.g. TEXT_FINGERPRINT_UNCONFIRMED). Option B (digit-preserving fingerprint v2) changes persisted semantics/backup comparability — not for 1.0. Optional with approval: duplicate notice instead of silent prefill when inserted=false (V12-NEW-B); hide/wire no-op buttons (V12-NEW-A) | ReceiptDuplicateDetector.kt; ReceiptDuplicateDetectorTest.kt; R0-3 promoted; optional ReceiptScanViewModel.kt + strings.xml | coordinator duplicate branch (image deletion only when isDuplicate); S1-REV-01 duplicateOutcome; email path (externalSourceId decisive); bank-statement detector use | FG-15; D-04. Oracles: 12.50 vs 13.75 null date -> 2 rows + both images; equal semantics -> 1 row; one side null -> 2 rows | reviewer-strict | S (M with VM) / low-medium (true rescan with unparsed fields creates a second deletable receipt) |
| R3 | RG06A-1 + RG06A-4 (P2; release-blocker candidate) | D-05 option a: dialog create/edit stores the displayed currency with USER_CONFIRMED (or EXPLICIT_HOME_CURRENCY); suggestion create stores currency=home with EXPLICIT_HOME_CURRENCY; stamp in one place (BudgetViewModel addBudget :185 / updateBudget :206 with explicit currency from the screen). Legacy rows: no migration; the "conversion unavailable" state links to the edit dialog; saving re-stamps. Gate not relaxed | ui/screens/budget/BudgetScreen.kt; ui/screens/budget/BudgetViewModel.kt; R0-6 promoted; BudgetViewModel provenance test; optional runway-card affordance file (not yet identified) + strings.xml | BudgetRepository :648/:689/:740 (autopilot preserves provenance via copy); LegacyDataMigrationService :358 (debug); ComputeDashboardWidgetsUseCase :654/:1302; MonthlySavingsSweepUseCase :264; FinancialHealthCalculator :466; budget backup/restore keeps the column; do NOT gate Money Radar (RG06A-3) until R3 lands | FG-17; D-05 | reviewer-strict | S-M / existing users stay gated until they confirm (D-05) |
| R4 | RG01A-1 (+RG01A-2 tests) | D-01 option a: on WorkerRescheduleException keep the durable NORMAL+resumePending write-blocked state (mirror cancellation branch :287-290) and retry next launch via reset(); CRITICAL stays for PersistenceException; optional one bounded in-process retry; optional reviewed one-time downgrade of existing WORKER_RESCHEDULE_FAILURE sentinels only if H-2 requires | data/backup/RestoreMaintenanceMode.kt; AppStartupCoordinator only if its BLOCKED mapping must change (:284-427 unread); AppStartupCoordinatorRecoveryTest.kt (pinned test initialize_contains_checked_resume_failure_and_keeps_recovery_UI_available changes expectation, D-06); R0-4 promoted; new initialize()/resetCancellable() positive-path tests; CL-19 spec doc | triggers DBRI :495/:796 (exit(false) in finally), MaintenanceOperationRunner, :985 wrong password, :471 asset rollback; MainActivity :437-449 dialog text | FG-11/13/10; D-01, D-06 | reviewer-strict + architecture-guardian | M / medium-high (state machine; barrier must stay closed while resume pending) |
| R5 | RG01-N1 | ReceiptAssetRecovery :92-97: a PENDING task whose existing restored_<id> matches the verified bundle proof (checksum, not name) adopts via the normal pointer CAS (like TEMP_WRITTEN adopt :225-234); non-matching stays TARGET_COLLISION. BackupRestoreViewModel :238-239 surfaces the receiptAssetWarnings count (no names/paths) | data/backup/ReceiptAssetRecovery.kt; ui/screens/backup/BackupRestoreViewModel.kt; strings.xml (sequenced); R0-5 promoted | DBRI :1265, :1446-1457, :1568-1598; generateBackupManifest :228-233 (stays fail-closed); 6-code reason allowlist; RG01B-1 untouched | FG-13/16 | reviewer-strict + privacy-security-guardian | M |
| R6 | RG02B-1 | CloudDedupeJudgeService :197-202 and CloudReviewExplanationService :209-214 return ParseError/Unknown with fixed codes (mirror CloudDashboardBriefingService); check HybridDedupeJudgeService and OnDeviceReviewExplanationService (unread). Optional coupling: RG02B-4 cancellation rethrow in HybridDedupe.safeExecute | those provider files; JVM sentinel tests + androidTest MockWebServer (Android org.json differs, V02B-N4; needs connected-tests/device) | — | FG-16; existing rows per D-07 (bounded SQL scrub needs a new DAO write + grant + approval, else accept retention + ExportAnonymizer) | privacy-security-guardian + reviewer-strict | S |
| R7 | RG07A-1 (after R1) | ReceiptSideEffectPlanner Suggested branch (:444-446): replace the full-row update with the existing conditional updateMatchSuggestion; require 1 row; 0 rows -> no write/no event | ReceiptSideEffectPlanner.kt; new real-Room interleave test (claimForAutoMatch lands between snapshot read and write -> stays linked). Guard files with approval: scripts/allowlists/receipt_link_allowlist.yml (planner G-RCPT-LINK-01 rationale false: correct or remove; must not grow); db_ownership_policy.yml only if a grant is needed | — | FG-15/12 | reviewer-strict | S |
| R8 | RG05A-1 | Producer (MonthlySavingsSweepUseCase :88-109) declines a consumed month via ONE shared read-only predicate equal to the coordinator check (SavingsSweepAcceptanceCoordinator :96: marker overlap OR accepted plan OR 'sweep' ledger row); SavingsGoalsViewModel (:223, :292) treats MONTH_ALREADY_ACCEPTED as terminal and clears the card | those three files (coordinator only to expose the predicate); SavingsGoalsViewModelTest (pinned "card stays" changes, D-06); MonthlySavingsSweepUseCaseTest; ledger Room test + v150-migrated variant | — | FG-17/14; D-06, D-08 (upgrade month shows no card); RG05A-2 not included unless approved | reviewer-strict | S-M |
| R9 | RG16A-2 | SynthesisEngine :365-382 applies SavingsGoalCurrencyPolicy (read-only) and converts the remaining amount using the engine's rate input; policy-unknown/unconvertible goal -> AVAILABLE partial/unavailable (D-09), never silently excluded | SynthesisEngine.kt + new test (EUR 1000 goal, home JPY at rate r; legacy goal -> unavailable) | if no rate input/partial flag exists, the result model and dashboard consumers join the write set (re-plan) | FG-17; D-09 | reviewer-strict | M |
| R10 | RG12-03 (+ optional RG12-02) | ExportOptionsViewModel.setDateRange (:169) invalidates the completed result (generation bump + clear, as on format switch) and snapshots the range at job start; privacy block :262-279 untouched | ExportOptionsViewModel.kt; ExportOptionsViewModelTest | — | FG-16 | privacy-security-guardian + cheap review | S |
| R11 | RG04A-1 | GroupsRepositoryImpl.validateActiveMembersAndCustomSplit uses a date-aware member set (joinedAt <= expense date, not left) with the same date passed to the coordinator; fail closed with a bounded error. No coordinator/SplitCalculator change unless D-10 approves WI-6 rejection (separate batch) | GroupsRepositoryImpl.kt; ledger Room test (EUR 90, A10/B80, Bob joinedAt > date -> Error, 0 rows); GroupsRepositoryImplTest boundary | — | FG-17; D-10 | reviewer-strict | S |
| R12 | RG04B-1 (product, D-11) | Option a (recommended): relabel to read-only "suggested transfers" in SharedExpenseGroupsScreen (SettlementPlanSection) + strings.xml; correct CHANGELOG :74 and ARCHITECTURE :1709; optionally RG04A-2 LEGAL_PATHS :664-670 drift. Option b (legal settlement writer via GroupTransactionCoordinator) is planner-first, outside 1.0 | ui/screens/groups/SharedExpenseGroupsScreen.kt; strings.xml; docs | — | FG-26 (edit docs before the fingerprint freeze); D-11 | cheap review + doc check | S |
| R13 | RG17B-1 (test-only) | New app/src/test/.../domain/notification/capture/NotificationIntakeEnqueueFailureRoomTest.kt: Robolectric real Room + throwing fake enqueuer; covers markEnqueueFailed and the service EnqueueFailed branch (status, attempts, terminalAt per V17B-N1, payload purge). Do not un-ignore NotificationIntakeEnqueueFailureTest (RP-21 harness); new defects found go to a verifier, not a silent fix | test file only | — | FG-19/11 | reviewer-strict (light) | S |
| G1 | FG-06 governance (D-19 first) | RG17A2-1 cloud_payload matcher (exact path, enforced rule + symbol) or explicit waiver; RG17A2-3 remove 53 orphan entries (shrink, approval); human DECISION line for the 24 agent-approved additions; plan the 2027-01-01 expiry of all 77 entries (extension is an FG-06/FG-07 decision) | matcher script (file not yet located) + its tests + cancellation/cloud_payload allowlists (no overlap with R7's receipt_link_allowlist.yml) | — | registered-guard / static-guards validation | reviewer-strict + guard owner | — |

## 4. Owner decisions

| ID | Decision | Options | Planner recommendation | Blocks |
| --- | --- | --- | --- | --- |
| D-01 | RG01A-1 policy | a) NORMAL+resumePending for reschedule failures; b) explicit reviewed recovery action; c) keep CRITICAL | a (+ sentinel downgrade only if H-2 requires) | R4 |
| D-02 | RG11-H2 failure semantics | a) link/approval fails visibly; b) move category propagation post-commit (changes atomicity) | a | R1 |
| D-03 | V11-NEW-1 category merge/delete refs | reassign on merge; on delete null vs leave | reassign on merge; leave on delete (R1 neutralizes) | R1b |
| D-04 | RG12-01 shape | A rule change / B fingerprint v2; branch-B notice; V12-NEW-A buttons | A + notice; hide no-op buttons | R2 |
| D-05 | RG06A-1 provenance | a) stamp on create/edit + legacy confirm affordance; b) accept LEGACY when currency==home; c) migration backfill | a (b conflicts with B4 savings policy and RG06A-4; c needs a schema version bump) | R3 |
| D-06 | Change tests pinning defective behavior | approve / reject | approve, named tests only | R4, R8 |
| D-07 | Scrub existing ai_artifacts.errorMessage | SQL scrub (grant) / accept retention | accept unless a device test shows real user text, then scrub | R6 closure |
| D-08 | No sweep card in the upgrade month | accept / special-case | accept (F2 design) | R8 |
| D-09 | RG16A-2 fail direction/severity | unavailable/partial vs exclude; P2 vs P3 | unavailable; keep P2 | R9 |
| D-10 | CL-29 WI-6 | reject vs equal fallback in coordinator | reject (separate batch after R11) | R11 extension |
| D-11 | RG04B-1 1.0 scope | relabel / build writer | relabel | R12 |
| D-12 | RG02B-2 capture-toggle worker mapping | toggle cancels reminders/warranty vs capture-only | capture-only workers | future batch |
| D-13 | RG02B-3 briefing vs proactive flag | gate generation / delivery only | gate generation | future batch |
| D-14 | RG03-H1 raw text before filter under STORE_RAW | breach (P2) / accepted | filter or encrypted-transient-only before persist; rule P2 | future privacy batch |
| D-15 | OBS-REVOKE purge on consent revoke | purge / retention | purge via existing cleanup path (cleanup not gated on the capability) | future privacy batch |
| D-16 | RG16A-1 goal-delete history asymmetry | accept / align | accept for 1.0, document | none |
| D-17 | RG15C-2 redacted backup diagnostic tables | extend sanitizer / accept + runtime check of legacy rows | runtime check first | future |
| D-18 | M0 legacy contribution attribution | record the fail-closed default | record | FG-26 traceability |
| D-19 | FG-06 items (matcher, 53 orphans, 24 agent-approved additions, 2027-01-01 expiry) | fix/waive, remove, confirm, expiry plan | fix matcher; remove orphans; confirm 24; plan expiry | G1, FG-06 sign-off |
| D-20 | H-3 public repo .db + receipt photos + transcripts | owner assessment / purge history | owner decides before release | release |
| D-21 | P3 couplings (RG01A-2, RG12-02, RG02B-4, V12-NEW-A, RG04A-2 doc) | include / defer | include in their batches | R2, R4, R6, R10, R12 |
| D-22 | Device + connected-tests, or narrow class-filter runner addition | approve / defer | narrow runner addition with independent review | R0-1b, R6 device test |

## 5. Ordering rationale and first batch

Order: B-1 -> R0-1 -> R0-2 (decide the only provisional P1; confirm V11-NEW-1) -> R0-3..R0-6 (fail-first regressions) ->
R1 (+R1b) -> R2 (the P1s) -> R3 (deterministic broad user loss; release-blocker candidate) -> R4 (unrecoverable state) ->
R5 (same restore area, after R4) -> R6 (privacy) -> R7 (after R1) -> R8 -> R9 -> R10 -> R11 -> R12 -> R13 ->
G1 (after D-19, before FG-06 sign-off, well before 2027-01-01). strings.xml edits in batch order; R1b before R7 for
db_ownership_policy.yml; RG06A-3 only after R3.

Recommended first batch after R0: R1 (RG11-H2), if R0-1/R0-2 confirm. If R0-1 refutes the mechanism, start with R2
(RG12-01). If the owner prefers the fastest broad user-visible restoration, R3 is the smallest alternative but leaves a
P1 open.

## 5a. G1 expiry plan

- scripts/verify_allowlist_compliance flags allowlist entries with expiry < today. The 2027-01-01 entries become violations
  from 2027-01-02 and then block static-guards (FG-06), so they must be resolved before that date.
- Owner review on 2026-12-01 of the remaining exact-symbol entries in scripts/allowlists/cancellation_allowlist.yml (24
  confirmed by D-19; 19 after the 2026-10-15 stale removals) and of the Kotlin KNOWN_VIOLATIONS list
  (CancellationSafetyArchitectureGuardTest, entries expire 2026-12-31): for each, either fix the site or record an
  explicit FG-06/FG-07 extension decision. No silent date bumps.
- Deferred (approved, D-19 item 6): Kotlin guard expiry/basename/stale-entry gaps (the Kotlin guard matches by basename
  and lacks the Python guard's exact path/symbol, expiry and stale-entry enforcement).

Note (G1 review, 2026-10-15): the 12 RAW_RUN_CATCHING_ALLOWLIST entries in CancellationSafetyArchitectureGuardTest
(~:198-209) passed their 2026-10-01 expiry; the Kotlin guard checks expiry against a fixed GUARD_TODAY_VALID date, so this
is not detected. Include them explicitly in the 2026-12-01 owner review (deferred Kotlin guard expiry enforcement).

## 6. Blockers

- B-1: the performance runner commit (14a2e6ce: gradle.properties, scripts/validation-runner.ps1, test-shards.json) must be
  settled/reviewed and a runner self-check run at the pin before ANY R0 or batch validation.
- Approvals: each R0 authoring, batch, runner queue and guard/allowlist/grant edit requires its own approval; none granted.
- connected-tests needs a device and has no class filter (D-22); blocks only R0-1b and the R6 device proof.
- B-2/B-3 (release artifact profile, signing) block final acceptance, not remediation.
- D-01..D-11 block their named batches.

## 7. Out of plan and evidence invalidation

Out of plan: Phase 1 indexing (IX-01/02); Phase 3 horizontal (HZ-01..08 incl. the nested-transaction sweep); Phase 4
journeys; runtime/device RT gates (RG03A-1 cold-start drop rate, device WorkManager latency, org.json device behavior,
upgrade/restore matrix H-2); all P3s and unverified candidates (V16-NEW-1, V03B-NEW-A, V07B-N1, ...); re-verification of
discovery-only closures (RG-08b, RG-09 consumers, RG-13, RG-14, RG-15a/c).

Invalidation: any app/ change ends content-equivalence with ea446316 -> Wave 5 runs become historical; Phase 0 re-pin;
final unit-tests, app-check, static-guards (FG-05), lint and connected-tests must run on the final candidate
(migration-tests only if a schema change is ever approved). Static re-verification per batch:
R1 -> CA-P-02-002 sink deferral, CL-07/08, review/receipt-link journeys, FG-15 guard; R2 -> CA-P-03-004, S1-REV-01;
R3 -> F4, CA-E-02-001, CA-P-06-001, SafeToSpend; R4 -> CL-19 P07-003/004/005, S2 R1-R6; R5 -> S2 asset recovery,
CA-P-07-007, RG01B-1; R6 -> CA-P08-003/002/004; R7 -> CA-P-03-001; R8 -> F2/F3, RG05A-2; R9 -> B4, RG14-1 refutation
basis, CA-E-02-004; R10 -> CA-P-12-002, CA-P-07-001 export gate; R11 -> CA-E-04-002, CL-29 WI-1..4; R12 -> A7, E-04;
R13 -> CL-01/02/03 worker evidence.

## 8. Planner uncertainty (fix shape not fully determined)

R0-1/R1: Robolectric vs device fidelity; production PRAGMA foreign_keys; ReceiptMatchingWorker failure classification
(unread); whether a domain CategoryDao read needs a grant. R2: ~1150 ReceiptScanViewModel lines unread. R3: dashboard
affordance file not identified; whether the dialog has a currency picker. R4: AppStartupCoordinator :284-427 unread.
R6: hybrid/on-device providers unread. R9: SynthesisEngine rate input/partial flag unconfirmed (may widen write set).
R11: exact function lines unread. G1: matcher file not located. Validation: shard names unread.

## 9. Batch R1 detailed plan (release-planner, read-only, 2026-10-15) and lead scope decision

Basis: R0-1/R0-2 runtime results + independent test review (verification ledger, "Phase R0" section).
Lead scope decision: R1b DEFERRED (approval made it conditional on the merge trigger; the review found
CategoryRepository.mergeCategories has no production caller -> V11-NEW-1 P3; a DAO change for dead code is not a
minimal diff). R1 alone neutralizes any stale category id (merge, cloud ids, MIGRATION_112_113).

Production edits (paths under app/src/main/java/com/yourname/expensetracker/):
- domain/transaction/ExpenseCategoryAssignmentPort.kt: add outcome `SkippedCategoryMissing`; KDoc: a Failed outcome
  inside an enclosing transaction means it is already rollback-only -> caller must abort by throwing.
- domain/transaction/DefaultExpenseCategoryAssignmentService.kt: inject CategoryDao (read only); inside the existing
  withTransaction, before updateCategory (:37), return SkippedCategoryMissing when the category does not exist. Keep the
  CE-first catch at :48-52 unchanged; no new catches.
- domain/receipt/lifecycle/ReceiptLinkService.kt: bounded `ReceiptLinkFailureCode.CATEGORY_ASSIGNMENT_FAILED` +
  `ReceiptLinkAbortedException(code)` (message = code name, no cause/SQL text). Restrict the try/catch at :303-340 to the
  read-only categorization read/majority + log; call the port outside it with an exhaustive `when` (no else):
  Assigned -> record; Skipped* -> nothing; Failed -> throw ReceiptLinkAbortedException inside the transaction.
  At the boundary (:387-392) add `catch (e: ReceiptLinkAbortedException) -> Result.failure(e)`; CancellationException
  never caught; other exceptions unchanged. KDoc :145-148 updated.
- data/repository/ExpenseRepository.kt :112-114: comment-only correction.
- docs/architecture/LEGAL_PATHS.md :1195-1199: add SkippedCategoryMissing + existence check line.
Callers re-check only (no edits expected): ReviewQueueRepository :309-323 (throws -> rollback -> Result.Error),
ReceiptLifecycleCoordinator :1193/:1210/:1607/:1635, ReceiptMatchingViewModel, ReceiptMatchingWorker :86/:253 (bounded
code + class name; no WorkManager retry), ReceiptSideEffectPlanner :396.
Grants: none expected (CategoryDao read; db_access.json :16 already keys this class). Any new guard key / baseline /
policy row -> BLOCKED-ON-HUMAN.
Tests:
- R0-2: replace @Suppress with @OptIn(RestrictedExpenseDaoMutation::class); neutral failure token "SILENT_ROLLBACK";
  assert the assignment outcome via a delegating recorder; add a non-merge stale-id seed (nonexistent category id) and
  run the three path tests on both seeds; on failure assert the post-commit runner did not run. After R1 the three
  path tests must PASS with Success + rows present + expense uncategorized (SkippedCategoryMissing). The merge-based
  V11-NEW-1 test stays as-is (documents the latent P3).
- R0-1: @OptIn fix only; characterization unchanged.
- New ReceiptLinkServiceNestedFailureTest (Robolectric, real Room; fake port doing a real failing nested write and
  returning Failed): standalone link and createExpenseAndLinkReceipt -> Result failure with CATEGORY_ASSIGNMENT_FAILED,
  0 links / 0 new expenses, message == code; port throwing CancellationException propagates.
- CategoryAssignmentServiceBarrierTest: mechanical constructor update (categoryDao fake/mock with explicit getById stub);
  add a SkippedCategoryMissing test (no update, no event).
Validation (serial, validation-runner, B-1 conditions): targeted-unit-test per class (CategoryAssignmentServiceBarrierTest,
ReceiptLinkServiceNestedFailureTest, ReviewQueueRepositoryNestedRollbackTest, ReceiptLinkServiceColumnScopeTest,
NestedTransactionSemanticsTest) -> unit-tests -> static-guards. app-check deferred to the final candidate.
Stop if: any guard/baseline/policy/schema change; a caller needs an edit; the approve path still loses rows; a test
would need deletion/@Ignore; >2 review or >2 debug iterations.
Follow-ups (not in R1): HZ-02 sweep incl. email path ReceiptLifecycleCoordinator :1238-1245 and
promotePendingReviewLinksToExpense; validate cloud categorization ids (CloudReceiptItemCategorizationService.kt:351);
R1b/V11-NEW-1 P3; R0-1b device twin (D-22).

## 10. Batch R3 detailed plan (release-planner, read-only, 2026-10-15) and lead adjustments

Facts: the budget dialog has no currency picker and shows no currency; create uses home currency (BudgetScreen.kt :941/:957);
edit keeps the stored currency (:943-948); suggestion create keeps entity defaults "EUR"/LEGACY_DEFAULT (:804-809,
Budget.kt :67-68). EXPLICIT_HOME_CURRENCY is an accepted string (SavingsGoalsViewModel :331 precedent;
BudgetStatusSnapshot :28; SavingsGoalCurrencyPolicy :14), not an enum value — do not edit the enum.
Edits (one writer; paths under app/src/main/java/com/yourname/expensetracker/):
- ui/screens/budget/BudgetViewModel.kt (sole stamping point): addBudget(budget, confirmedCurrency) / updateBudget(budget,
  confirmedCurrency); private suspend helper parses the currency (CurrencyCode.parse, supported set), reads home via
  currencySettingsRepository.homeCurrency().first(); edit requires budget.currency == confirmed (no silent relabel);
  create/suggestion -> currency = home + EXPLICIT_HOME_CURRENCY; edit with currency == home -> EXPLICIT_HOME_CURRENCY;
  edit of a non-legacy row with currency != home -> USER_CONFIRMED. Helper null -> bounded
  ERROR_BUDGET_CURRENCY_UNCONFIRMED, no write. Autopilot paths (:339/:399) unchanged (copy keeps the stamp).
- LEAD ADJUSTMENT (fail closed, avoids D-05b): editing a LEGACY row whose currency != home keeps its existing assumption
  (no confirmation stamp, no relabel); the "Confirm currency" affordance is shown only for budgets whose currency == home.
  D-05b (relabel/picker for legacy non-home rows) is recorded as a new owner decision, not decided here.
- ui/screens/budget/BudgetScreen.kt: dialog onConfirm(Budget, String) with a read-only currency Text (displayed currency =
  initialBudget?.currency ?: homeCurrency); wire :293-294/:308; SuggestionsBanner onAdd passes home currency, Create
  disabled while home is null; budget card "Confirm currency" button (policy knownCurrencyOrNull == null AND currency ==
  home) calling the existing onEdit.
- ui/components/FinancialRunwayCard.kt: optional onReviewBudget callback + button in the unavailable branch;
  ui/screens/home/HomeScreen.kt :764-776 passes onNavigateToBudgetDetail("OVERALL") (note: the reason code is shared with
  FX failures, so the link is generic "review budget").
- strings.xml: append budget_currency_display_format, budget_confirm_currency_action, financial_runway_review_budget AFTER
  R2's keys.
Consumers (re-check only): BudgetRepository copies keep the stamp (:653-655, :694, :743); DashboardContractsAdapter :97
hasCompleteAmountIn -> runway (:654), SafeToSpend (:1302), Monte Carlo (:971), Block Party (:855), pace; MonthlySavingsSweep
:162-163/:264 and FinancialHealthCalculator :462-467 unaffected; backup copies the column; ForecastInputAssembler :217; do
not gate Money Radar. Legal write path VM -> BudgetRepository (barrier :650/:691, validate :761). No DAO/schema/guard change.
Tests: R0-6 DashboardBudgetProvenanceChainTest (fail-first; oracles remaining 900, burn 100/15, runwayDays 135 HEALTHY,
simulate(100.0, 0.0, 1000.0, "USD", 0.0), totalCommitted 0); new BudgetViewModelCurrencyProvenanceTest (create, suggestion,
edit EUR-confirmed row, edit legacy home row, legacy non-home row keeps assumption, mismatch/unsupported -> no write +
error, autopilot keeps stamp); BudgetViewModelStressTest mechanical signature/stub update; PaceWiringTest LEGACY-gate test
unchanged and must pass. Targeted filters: DashboardBudgetProvenanceChainTest, BudgetViewModelCurrencyProvenanceTest,
BudgetViewModelStressTest, ComputeDashboardWidgetsUseCasePaceWiringTest, NotificationExpenseDashboardPipelineTest,
BudgetViewModelAutopilotQualityTest; shared unit-tests + static-guards after R6.
Stop if: BudgetStatusSnapshot/enum/DAO/schema/guards/use case would change; a picker/relabel is needed; >2 review/debug
iterations. Compose tests using AddEditBudgetDialog were not checked by the planner (writer must grep androidTest).

## 11. Batch R2 detailed plan (release-planner, read-only, 2026-10-15) and lead notes

NEW-R2-A (static, planner; lead confirmed the SQL): MIGRATION_125_126 (AppDatabase.kt ~:7968-7973, idx_fresh_*_u) and
MIGRATION_137_138 (:8325-8344, idx_scanned_receipts_*_u) create UNIQUE partial indexes on scanned_receipts
imageHash/sourceFingerprint/textFingerprint/semanticFingerprint; the ScannedReceipt entity does not declare them, so
entity-built (fresh/Robolectric) DBs lack them while DBs upgraded through those migrations have them. With the index,
ScannedReceiptDao.insert (IGNORE) silently skips a second row with the same textFingerprint -> ReceiptInsertResolver
:52-54 reports Duplicate -> INSERT_ROLLBACK_DUPLICATE cleanup deletes the image (ReceiptLifecycleCoordinator :713-728).
So detector rule A alone does not fix upgraded installs. Fix inside R2: never store a textFingerprint already claimed by
another row (same approach as MIGRATION_137_138 nulling collisions); no index/schema change.
Separate candidate (NOT in R2; to verify): NEW-R2-B — whether Room schema validation on DBs carrying these undeclared
indexes behaves as intended for supported upgrade paths (H-2). Routed to room-migration-guardian (read-only).
Edits (paths under app/src/main/java/com/yourname/expensetracker/):
- ReceiptDuplicateDetector.kt: DuplicateResult gains textFingerprintClaimed (default false). Text hit: both semantic
  fingerprints present (non-blank) and equal -> TEXT_FINGERPRINT duplicate (0.95, unchanged); otherwise remember
  textHitId and continue to semantic/external-id steps; if nothing else hits -> isDuplicate=false, confidence 0,
  existingReceiptId=textHitId, textFingerprintClaimed=true, matchType TEXT_FINGERPRINT_UNCONFIRMED (one/both semantics
  null) or NONE (both present and different). semanticFingerprintsConflict -> semanticFingerprintsEqual; KDoc.
- ReceiptLifecycleCoordinator.kt: track textFingerprintClaimed from the post-OCR duplicate result (both branches ~:513 /
  ~:557); store textFingerprint.takeUnless { claimed } (~:595); email path ~:1062 same (lead: in scope, same defect
  class). Residual (not fixed): legacy id>0 update path ~:523.
- V12-NEW-B: ReceiptScanViewModel.kt reopenedExistingReceipt flag (set = !outcome.inserted in both REVIEW updates; reset
  at ~:279); ReceiptScanScreen.kt notice card (~:1170); strings.xml receipt_reopened_existing_notice (R2 first in the
  strings.xml order).
- V12-NEW-A: remove the two no-op Button blocks (ReceiptScanScreen.kt ~:280-288); keep "Scan New"; keep string keys.
Lead approval reading: D-04 covers changing the two detector tests that pin the defect ("text hit is kept when
incoming/existing semantic fingerprint is unknown"), the coordinator/email lines and the new string key.
Tests: R0-3 ReceiptDuplicateFingerprintCollisionTest (fail-first; harness from ReceiptPartialOcrPipelineTest :84-195;
real detector; each case plain + with the exact migration index SQL): T1 12.50 vs 13.75 date null -> 2 rows, second
inserted, no duplicate cleanup, second textFingerprint null; T2 identical parsed fields + date, different image -> 1 row,
POST_OCR_DUPLICATE_DRAFT; T3 one side date null -> 2 rows; T4 identical image -> EXACT_HASH, 1 row; T5 different totals
with date -> 2 rows; precondition asserts. ReceiptDuplicateDetectorTest: two pinned tests changed, others kept, three
added. ReceiptScanViewModelTest: flag tests.
Targeted filters: ReceiptDuplicateFingerprintCollisionTest, ReceiptDuplicateDetectorTest, ReceiptLifecycleCoordinatorTest,
ReceiptPartialOcrPipelineTest, ReceiptScanViewModelTest, ReceiptScanViewModelStressTest, BankStatementSourceBoundaryTest.
Stop if: index/schema change needed; T1 precondition fails; tests beyond the two named need semantic change; >2 iterations.
Section 11 amendment (2026-10-15): NEW-R2-A premise refuted by NEW-R2-B verification -> R2 write set no longer includes
the ReceiptLifecycleCoordinator textFingerprint/email lines; DuplicateResult.textFingerprintClaimed is not added. R0-3
keeps plain-schema cases only.

## 12. Batch R4 plan summary (release-planner, 2026-10-15)
RestoreMaintenanceMode.kt only: split catch (CE first; PersistenceException -> CRITICAL unchanged; other ->
retainDurableResumePending() then throw WorkerRescheduleException); retainDurableResumePending under stateLock re-reads
persisted state, keeps NORMAL+resumePending (publish RESTORE_COMPLETE_RESTART_REQUIRED), never pauses workers/resets the
stop flag; barrier stays closed; next launch retries via checkRestoreJournalState -> resetCancellable (one per launch, 10 s
bounded); no AppStartupCoordinator/MainActivity/DBRI edit; CL-19 WI-5 doc line. Residual -> D-23/R4b: DBRI
isRestoreDurabilityFailure (:876-879) still routes WRE to lockCriticalForFailure (:881) on pre-destructive aborts.
R0-4 RestoreMaintenanceModeConfirmationFaultTest (b1 sync exit with jumping clock; b2 exitCancellable with failed
confirmation future via mockkStatic(WorkManager) — plain Java methods; c next-launch recovery; d bounded). Pinned tests
changed (approved): ASCRT initialize_contains_checked_resume_failure..., initialize_contains_asset_rollback_worker_resume_
failure..., P7BugFixesTest :724-727, :759, :793. Unchanged and must pass: :799 PersistenceException, :851/:894/:938
cancellation. Filters: RestoreMaintenanceModeConfirmationFaultTest, P7BugFixesTest, AppStartupCoordinatorRecoveryTest,
WorkerRestoreRegressionTest, BackupRestoreContractTest.

## 13. Batch R5 plan summary (release-planner, 2026-10-15)
ReceiptAssetRecovery.kt: PENDING task with an existing final file adopts it only when hasProof && matchesProof (journal
expectedSha256 + expectedSize, recorded before any file work); re-check in stage() PENDING branch, persist FINAL_DURABLE,
NeedsPointer(adoptedExisting=true) without copy/move; normal CAS (updateImagePathIfUnchanged + read-back in
restoreInternalWriteScope); afterCas NOT_APPLIED never deletes an adopted file; mismatch/unreadable/no-proof stays
TARGET_COLLISION. BackupRestoreViewModel receiptAssetWarningCount (count only) + BackupRestoreScreen text +
backup_restore_receipt_images_skipped_format (en/es/el). No journal/schema/reason-code change. Residual: adopted flag not
journaled (process death after FINAL_DURABLE then NOT_APPLIED could delete an adopted file; NOT_APPLIED practically
unreachable under the barrier) — journal field would be a format change (owner decision, not in R5); already-broken rows
not repaired. R0-5 ReceiptAssetRepeatRestoreTest (a adopt, b non-matching control, c engine NOT_APPLIED keeps file,
d no-proof collision) + VM count test. Pinned tests expected unchanged: DatabaseBackupRepositoryImplTest :1303, :1357,
:1617, :1631; ASCRT :1018; BackupRestoreViewModelTest :157. Filters: ReceiptAssetRepeatRestoreTest,
DatabaseBackupRepositoryImplTest, AppStartupCoordinatorRecoveryTest, BackupRestoreViewModelTest. Order: after R4.

## 14. Batch R6 plan summary (release-planner, 2026-10-15) — BLOCKED on owner HD-1 (HD-2/HD-3 optional)
Reuse bounded codes PARSER_FAILED / UNKNOWN_ERROR (DiagnosticReasonCode.kt :15/:28; CloudDashboardBriefingService
precedent). W1 CloudDedupeJudgeService :197-202 ParseError/Unknown fixed codes + CE rethrow before catch(Exception) +
class-name-only logs (:172/:178/:182/:188/:194); W2 CloudReviewExplanationService :209-214 same + logs :184-206; W3
HybridDedupeJudgeService.safeExecute :83-88 CE rethrow + Unknown("UNKNOWN_ERROR") (RG02B-4); W4
OnDeviceReviewExplanationService :43-47, :98; W5 (HD-3) OnDeviceDedupeJudgeService :44-49, :116; W6
JudgePendingReviewDuplicateUseCase CE rethrow before :126; HD-2 bound e.message at JudgePendingReviewDuplicateUseCase
:130/:135 and ExplainPendingReviewUseCase :130. Persisted at AiArtifactEntity.errorMessage (:59) via
AiArtifactRepositoryImpl :85/:107; shown ReviewViewModel :369/:731; ExportAnonymizer nulls it. Guard: FG-10 cancellation
ratchet baseline entry for HybridDedupeJudgeService (config/baselines/cancellation.json :6/:57) resolves and the ratchet
fails on resolved entries -> HD-1 owner approval to delete that baseline entry (guard-config edit). Tests with sentinel
R6SENTINEL_9f2c per provider + CE tests + new HybridDedupeJudgeServiceTest. Residuals: V02B-N4 device org.json proof;
D-07 existing rows; successful rationale stored by design.

## 15. Batch R6b plan summary (release-planner, 2026-10-15) — privacy sweep, owner-approved scope
Dropped after verification: InterpretFinancialQueryUseCase :50 (error text discarded, local fallback), CloudRetryPolicy :23,
GetAiRuntimeStatusUseCase :50. Edits: E1 GenerateDashboardBriefingUseCase :121/:125 (UNKNOWN_ERROR_CODE; shown on Home
HomeViewModel :317); E2 SuggestReceiptExtractionUseCase :161/:165/:170 (fixed "AI receipt assist failed."); E3
SuggestCategoryFallbackUseCase :214/:218/:223 (fixed "AI category assist failed."); E4 CategorizeReceiptItemsUseCase :216/
:225-226 (persist class simple name in both modes; CE part = HD-R6b-1 owner decision: catch CE, reset ANALYZING->PENDING in
NonCancellable, rethrow); E5 OnDeviceDashboardBriefingService :46-50 (CE rethrow; UNKNOWN_ERROR); E6 OnDeviceReceiptAssistService
logs :51/:55/:91/:95/:154/:185 + :56/:96 UNKNOWN_ERROR; E7 SmartReceiptAssistService :373/:374 fixed codes (payloadJson
:276/:309); E8 ReviewViewModel AI-assist catches :375/:602/:684/:734 CE rethrow + bounded logs; optional (lead: include)
CategorizationAssistInputBuilder :138 merchant + throwable log. Pinned leak assertion to change (owner approval covers):
GenerateDashboardBriefingUseCaseTest :261-276. No cancellation baseline entry resolved; non-failing stale allowlist entries
(cancellation_allowlist.yml :291-297, :484-491; KNOWN_VIOLATIONS :83) -> cleanup proposal. Follow-ups (owner approval):
F1 pii_logging guard extension; F2 cancellation scanner misses `suspend operator fun invoke` (baseline would grow);
F3 ~15 raw-throwable log sites in other AI files; F4 ReviewViewModel non-AI catches. HD-R6b-2: Home shows "UNKNOWN_ERROR
Route: ..." (accept; localized follow-up).

## 16. Batch R7 plan summary (2026-10-15) — owner-approved incl. policy edit
ReceiptSideEffectPlanner Suggested branch :442-473: scannedReceiptDao.updateMatchSuggestion (existing, ScannedReceiptDao :128-143)
instead of full-row update; MATCH_SUGGESTED event only when 1 row; else Skipped(ALREADY_PROCESSED) (mirror :418). Guard edits
(approved): db_ownership_policy.yml :5305 operation update -> updateMatchSuggestion (+reason :5307-5310); accounting JSON 2 rows;
receipt_link_allowlist.yml :100 rationale corrected (guard reads path only). event_writers counts unchanged. Test:
ReceiptSideEffectPlannerSuggestionRaceTest (interleave, control, identical retry).

## 17. Batch R8 plan summary (2026-10-15) — owner-approved incl. D-06 for the coordinator test
NEW data/repository/SavingsSweepMonthConsumption (read-only; existing DAO queries; coordinator check :60-67 moved verbatim);
coordinator builds it from its own DAOs (stays in transaction) + public const SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED;
MonthlySavingsSweepUseCase injects it and returns NoRecommendation for a consumed month before budget reads; SavingsGoalsViewModel
:278-281 clears card + reason on MONTH_ALREADY_ACCEPTED. Tests: coordinator real-Room cases (marker / ACCEPTED plan / sweep ledger
row / negative controls / accept-then-new-producer), v150-migrated variant in SavingsSweepMonthMigrationTest, use-case test,
VM terminal test; changed: SavingsSweepAcceptanceCoordinatorTest :471/:479 (approved). No DAO/schema/guard change.

## 18. Batch R9 plan (option B, owner H-R9-1 = B; 2026-10-15)
No change to ComputeDashboardWidgetsUseCase :964 (Monte Carlo reads forecast.isPartial/excludedCount, which stay
recurring + planned + input quality; MC never uses goalReserves). Edits: FinancialForecast.ForecastComponents
goalReserveExclusions: Int = 0 (+require >= 0, derived goalReservesPartial); SynthesisEngine :362-382 STRICT unfinished goals,
SavingsGoalCurrencyPolicy.knownCurrencyOrNull -> unknown => exclusion++; pro-rate in goal currency; convertAmount to display
currency, failure => exclusion++; count-only log; components :512-524. FinancialWeather.goalReservesPartial (default false);
FinancialWeatherRepository :115 mapping; FinancialWeatherCard availablePartial -> DataQualityWarningChip (existing string);
HomeScreen :696-713. Lead decision on H-R9-2: chip for goal-only partial (other partial causes unchanged; follow-up).
Tests: new SynthesisEngineGoalReserveCurrencyTest T1-T5, T8; FinancialWeatherRepositoryTest T6; DashboardBudgetProvenanceChainTest
T7 (legacy goal keeps Monte Carlo available); fixtures SynthesisEngineTest :600-608 and SynthesisEngineGoldenTest :118-126 ->
currencyAssumption "EXPLICIT". Money guards: money.json fingerprints must stay []; no G-MONEY-ALLOW; avoid sumOf { it.amount }.
Residuals: AI briefing input and BlockParty/Narrative show no partial marker.

## 19. Phase 6 runtime/release evidence plan (release-planner, read-only, 2026-10-15) — PENDING owner decisions
Facts: runner fingerprint hashes only dirty/untracked bytes (evidence identity = git_revision + fingerprint); no existing run is
final evidence for the frozen candidate. HD-P6-1: final candidate C* = 2ec50671 + one prep commit (runner D-22/B-2 additions +
protocol tests + evidence script + R0-1b and V02B-N4 device tests + handoff), with proof that shipped inputs are unchanged
(git diff --quiet 2ec50671 C* -- app/src/main app/build.gradle.kts app/proguard-rules.pro app/schemas); alternative: keep
2ec50671 and disclose R0-1b/V02B-N4 as gaps.
Gates on C*: runner-smoke + runner-protocol-tests; unit-tests; app-check (= :app:check incl. debug+release unit tests, lint debug
with baseline, Gradle-wired guards); static-guards; migration-tests not separately needed (no schema change; *Migration* tests in
unit-tests, connected migration tests in connected suite); connected-tests whole suite per AVD; assemble-release +
release-artifact-evidence.
D-22: profile connected-test-class (-TestFilter one FQCN, strict regex) + optional -DeviceSerial (ANDROID_SERIAL; recorded inert
-PvalidationDeviceSerial) on connected profiles; protocol tests; reviewer-strict; lead checks device XML (device/API in name,
tests > 0, class present). R0-1b NestedTransactionSemanticsInstrumentedTest (new); V02B-N4 device org.json sentinel test (new;
OkHttp interceptor fake preferred; MockWebServer would add androidTestImplementation = build-input change needing approval).
B-2: profiles assemble-release (:app:assembleRelease) + release-artifact-evidence (new read-only script
scripts/ci/release_artifact_evidence.py wrapping unchanged verify_release_artifact.py --fail-on-violation; evidence.json with APK
sha256/size, variant/version/appId, aapt2 sdk/debuggable/permissions, apksigner cert digest + debug_certificate flag, mapping.txt
sha256; no keystore access). B-3: owner states production signing/distribution (Play App Signing AAB vs APK).
WARNING: connected tests reinstall and clear app data — never on a device with real data.
Device matrix: D1 AVD API 26 google_apis (create); D2 AVD API 33 (recommended, create); D3 wave5-api35 (exists); P1 spare/wiped
physical API 34/35 (smoke + beta). Minimum D1 + D3 + P1.
Runtime candidates (unverified, verifier dispatched): RT-C1 NotificationCaptureService dataSync FGS without onTimeout (Android 15
6 h limit); RT-C2 BootReceiver/ServiceRestartReceiver startForegroundService with swallowed startForeground failure; RT-C3 Gson
reflection without keep rules under R8 (AutomatedSavingsRuleState, SavingsContributionHistory, split-template TypeToken, worker
diagnostics); RT-C4 Android 15 edge-to-edge insets.
Manual scenarios per remediated batch on the optimized APK; synthetic datasets only (never the tracked .db or receits/);
performance bounds set before testing; 3-7 day beta on the same APK. Serial queue and stop rules as in the plan report.

## 20. Batches R14 (RT-C1) and R15 (RT-C3) — owner-approved release blockers (plan by release-planner, 2026-10-15)
R14: new service/DataSyncFgsTimeoutGuard.kt (SharedPreferences "notification_capture_fgs_guard", key data_sync_timeout_elapsed_ms;
isPromotionBlocked = stored && 0 <= now-stored < 24 h (reboot unblocks); recordTimeout apply(); no catch blocks).
NotificationCaptureService: startForegroundWithNotification (:304) returns early when blocked (bounded Timber.i
FGS_PROMOTION_SKIPPED); override onTimeout(startId, fgsType): stopForeground(STOP_FOREGROUND_REMOVE) + recordTimeout + bounded
log; no super call, no stopSelf, listener stays bound. ServiceRestartReceiver :13 / BootReceiver :30: skip startForegroundService
when blocked; DebugViewModel :324-325 startService when blocked. Tests: DataSyncFgsTimeoutGuardTest; NotificationCaptureService
FgsTimeoutTest (Robolectric sdk 34, onTimeout -> stopped, no re-promotion, re-promotes after 24 h); receiver stress tests blocked
cases. Device check on API 35 (device_config data_sync_fgs_timeout_duration 60000; logcat -b crash empty over 2 alarm cycles;
listener still captures). Stop if stopForeground alone does not stop the crash (stopSelf = owner decision).
R15: app/proguard-rules.pro after :21 — TypeToken keep (allowobfuscation, allowshrinking) + package-scoped
`com.yourname.expensetracker.** extends TypeToken` (lead decision: this option); exact -keepclassmembers {<fields>} for
SavingsContributionEvent, SavingsContributionHistoryRepository$PersistedState, AutomatedSavingsRuleStateRepository$PersistedState
(+<init>()), $WeeklyRewardReservation, $MonthlyCapUsage, SplitShare, ui.PersistedVisualSplit, SplitTemplate$SplitType (enum fields).
No broad/dontwarn rules (FINAL gate §34). Optional GsonR8KeepRulesContractTest (fail closed on new Gson consumers). Proof:
assemble-release -> mapping.txt shows identity field mapping + kept <init>() + TypeToken subclasses with Signature; device
smoke on the release APK (contributions persist; CUSTOM_AMOUNT expense; split template; visual split; automated rules twice).
No pre-1.0 minified data exists (first release) -> no migration concern.
Device-test review notes folded in: CloudProviderAndroidOrgJsonSentinelInstrumentedTest log list synchronized (N2); runner doc
codes E_DEBUGGABLE_APK/E_VARIANT_NOT_RELEASE (N4). API 26 device scope: mockk-android inline mocking needs API 28+, so on
release-api26 run only mock-free classes (R0-1b, migration/fresh-install) + manual smoke; full suite on wave5-api35.

(Planner output for remediation plan section 21 — R16 S1/S2 + R16c, R17. Saved by lead while runner-protocol-tests runs; to be
appended to docs/releases/release-1.0-assurance/release-1.0-remediation-plan.md after the run completes.)
Lead decisions on planner owner questions:
- R16c (remaining toDoubleOrNull amount fields: SpendingChallenges :431, BillNegotiation :663, VisualSplitEditor :65,
  TaxConfiguration :133, Home :1423, CashFlowCalendar :122, CurrencyManagement :38/:777, SubscriptionManagement :838/:844) is
  INSIDE the owner-approved R16 scope ("locale-aware parsing for all amount inputs"); runs after R17 (HomeScreen overlap).
  Tax/Challenges fields are skipped if R19 hides those screens first.
- Non-dismissable Home "Capture inactive" card while access is off: accepted (planner default; hiding = turn consent off).
- Single-writer rule kept: R16-S1 -> R16-S2 -> R17 -> R16c sequential (planner allowed parallel; lead overrides).

## 21. R16 + R17 plan (release-planner, read-only @ 0a9cab1c, 2026-10-16)
No Room/schema/guard/baseline/allowlist change; stop if one appears. Runner targeted-unit-test = ONE --tests pattern per run.
M/ = app/src/main/java/com/yourname/expensetracker/, T/ = app/src/test/java/com/yourname/expensetracker/.

### R16 (V-A(A) P1 + V-I-1 generic path)
Policy owner M/domain/util/AmountUtils.kt: parseAmount UNCHANGED (~20 machine parsers). Add
parseUserAmount(text, decimalSep, groupingSep, maxFractionDigits = 2) -> Valid(Double) | Empty | Ambiguous | Invalid, BigDecimal
scale check before toDouble, reuse hasStrictThousandsGrouping; add parseStatementAmount(token).
User rules: (1) both . and , -> last is decimal, earlier must be strict 3-digit groups of the other char else Invalid; (2) one
separator kind repeated -> valid strict groups = integer else Invalid; (3) one separator + 1-2 digits = decimal in every locale;
(4) one separator + exactly 3 digits = grouping if locale grouping sep, else Ambiguous (rejected, no guess); (5) >2 fraction or
>10 integer digits Invalid; (6) Ambiguous uses each site's existing invalid-amount error (no new strings).
Statement rule: path only accepts EUR/USD/GBP (2-decimal) -> exactly 3 trailing digits = grouping; otherwise equals parseAmount.
Sanitizer M/ui/util/AmountInputSanitizer.kt: keep digits . , ; leading sep -> "0,"/"0."; trim leading zeros; cap fraction 2 after
locale decimal sep, 3 after the other; isValid delegates to parseUserAmount; separators from DecimalFormatSymbols(Locale.getDefault()) at call time.
S1 prod: AmountUtils.kt, AmountInputSanitizer.kt, M/domain/receipt/BankStatementParser.kt (:904-918 -> abs(parseStatementAmount(rawToken)); abs + keyword typing byte-identical).
S2 prod: M/ui/util/OwnershipValidator.kt :52; M/ui/screens/addexpense/AddExpenseViewModel.kt :157/:266/:291/:358/:480;
M/ui/screens/receiptscan/ReceiptScanViewModel.kt :549/:606/:746/:1056/:1108; M/ui/screens/budget/BudgetScreen.kt :929/:933/:935/:990;
M/ui/screens/savings/SavingsGoalsScreen.kt :386; M/ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt :555/:559;
M/ui/screens/groups/SharedExpenseGroupsScreen.kt :797/:805/:941/:1032; M/ui/screens/review/ReviewScreen.kt :1961;
M/ui/screens/transactions/TransactionsViewModel.kt :663.
Tests new: T/ui/util/AmountInputSanitizerTest.kt, T/domain/util/AmountUtilsUserAmountTest.kt, T/ui/util/OwnershipValidatorTest.kt;
extend: T/domain/receipt/BankStatementParserTest.kt, AddExpenseViewModelTest, ReceiptScanViewModelTest, TransactionsViewModelTest
(Locale.setDefault + restore in @After).
Worked inputs: el_GR 12,50->12.50; 1.234,56->1234.56; 1.234->1234; 12.5->12.5; 1,234->Ambiguous. en_US 1,234.56->1234.56;
1,234->1234; 12,50->12.50; 1.234->Ambiguous. any: 1.234.567,89->1234567.89; 1,23.4->Invalid; ,5->0.5. statement: EUR1,234->1234;
EUR1,234,567->1234567; "1.234 EUR"->1234; "1.234,56 EUR"->1234.56; existing sign/type fixtures unchanged.
Filters (serial): *AmountInputSanitizerTest, *AmountUtils*, *SharedUtilityConsistencyTest, *ClipboardAmountParserTest,
*BankStatementParser*, *OwnershipValidatorTest, *AddExpenseViewModel*, *ReceiptScanViewModel*, *TransactionsViewModel*.
Reviewer: reviewer-strict (money). Out of R16: R22b (V-I-2 sign, V-I-3 dates, N1, N2; after R16, same function);
R16c (toDoubleOrNull fields listed in lead decisions; after R17).
Stop if: any existing parseAmount/AmountUtils/bank-statement assertion must change; sign/type/ParsedTransaction change;
currency-specific fraction digits needed; schema/guard/allowlist change; file outside write set.
Acceptance: worked inputs pass; *AmountUtils*, *SharedUtilityConsistencyTest, *ClipboardAmountParserTest, *BankStatementParser*
pass with zero assertion edits; no path deletes a comma; validation and save cannot disagree. Device: el_GR Gboard keypad smoke.

### R17 (V-A(B) P1)
Check NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(packageName); RuntimeException -> false (fail closed);
only a boolean leaves the checker; log code NOTIFICATION_LISTENER_ACCESS_CHECK_FAILED + exception class only.
States: ACTIVE (consent on + access), ACCESS_OFF (consent on, no access), CONSENT_OFF (notificationCaptureEnabled false or
CorruptedFailClosed; consent wins). VM read-only, never writes privacy settings, never bypasses NotificationCaptureGate/PrivacyGate.
Home card only in ACCESS_OFF (S4-022 pattern HomeScreen :278-294); Privacy screen always shows OS access + "Manage access";
re-check on resume (DisposableEffect + LifecycleEventObserver as MainActivity :947). Intent chain: API 30+
ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS with component extra -> ACTION_NOTIFICATION_LISTENER_SETTINGS ->
ACTION_APPLICATION_DETAILS_SETTINGS (as ReceiptScanScreen :211); each catches ActivityNotFoundException; final fallback string.
Hint string for sideloaded Android 13+ restricted settings.
Prod: new M/domain/notification/capture/NotificationListenerAccessChecker.kt, NotificationCaptureStatus.kt;
new M/data/service/AndroidNotificationListenerAccessChecker.kt; M/di/PrivacyModule.kt (one @Binds); new
M/ui/screens/privacysettings/NotificationCaptureStatusViewModel.kt; new M/ui/components/NotificationCaptureStatusCard.kt; new
M/ui/util/NotificationListenerSettingsLauncher.kt; M/ui/screens/home/HomeScreen.kt; M/ui/screens/privacysettings/PrivacySettingsScreen.kt;
res values/, values-el/, values-es/ strings.xml (all three; lint MissingTranslation).
Tests new: T/domain/notification/capture/NotificationCaptureStatusTest.kt; T/ui/screens/privacysettings/NotificationCaptureStatusViewModelTest.kt;
T/data/service/AndroidNotificationListenerAccessCheckerTest.kt (Robolectric Settings.Secure enabled_notification_listeners);
T/ui/util/NotificationListenerSettingsLauncherTest.kt (@Config sdk 26,29,30,35). No Compose UI test infra; none added.
Filters: *NotificationCaptureStatus*, *AndroidNotificationListenerAccessChecker*, *NotificationListenerSettingsLauncher*,
*PrivacySettings*, + targeted-release-unit-test *NotificationCaptureStatus*. Reviewer: privacy-security-guardian (fresh).
Stop if: NotificationCaptureService/BootReceiver/manifest change; new permission; PrivacySettings/DataStore change; launch-time
prompt/onboarding; G-DI-01/PII/privacy guards flag. Acceptance: matrix; grant/revoke updates on return; no crash API 26-35; no new
log/stored fields; strings in 3 languages. Device: API 35 release grant/revoke/resume + sideload restricted settings.

### Gates
Targeted runs -> app-check once -> static-guards once -> unit-tests; documentor updates VM count in inventory. Max 2 review + 2 debug per slice.


## 22. R18 + R19 plan (release-planner, read-only @ 2ca932a9, 2026-10-16)
### R18 (P2; before R19)
R18a V-G-2 BLOCK (no single group-expense delete path exists; adding one = new LEGAL_PATHS entry = stop):
TransactionLifecycleCoordinator.deleteExpense (:2467-2494) after re-reading fresh: groupExpenseDao().getGroupExpenseForExpense(fresh.id)
-> outcome LinkedToGroup, no event, no delete, Result.failure(GroupLinkedExpenseException) code EXPENSE_DELETE_BLOCKED_GROUP_LINKED
(covers ManualRecurringExpenseViewModel :146, ExpenseRepository :438). TransactionsViewModel :476-490 shows bounded message.
Edit path BLOCK (no sync path): same check in updateOwnership :2044, updateType :1681, updateTypeAndTransferDetails :1899,
updateExpense :981 only when amount/currency/type/isNotMine/isSharedExpense/myShare change; NOT updateOwnershipDbOnlyV2 (addExpenseWithLink).
R18b V-F-1: new ui/util/UiWriteGuard.kt guardedWrite(onBlocked, block) catches only DatabaseAccessBlockedException ->
"Changes paused during backup, try again (WRITE_BLOCKED)" UiText.DynamicString; CE propagates; no global handler.
Sweep (first-statement scan; implementer completes via repository->VM callers, final table into handoff): SavingsGoalsViewModel
:314/:321/:328-342/:352-396/:433-435; HomeViewModel :557 (+:482 if widgetStyleRepository barriered); RecurringExpensesScreen.kt :191;
CategoryViewModel :25/:31; WarrantyTrackerViewModel :204/:212/:219; AssistantViewModel :398/:424; ReviewViewModel :1147;
SpendingMapViewModel :288/:527; VisualSplitViewModel :65/:76/:82. Savings snackbar (SavingsGoalsScreen :213-216) -> VM goalCreated event after success.
R18c V-I-6: BootReceiver internal shouldStartOnBoot(action, sdkInt) false only for BOOT_COMPLETED && sdk >= 35; log
BOOT_FGS_START_FAILED class=<simpleName>; BOOT_FGS_START_SKIPPED_API35. Robolectric 4.11.1 cannot run sdk 35 -> pure table test
+ @Config(sdk=[34]) receiver test (one start).
Write set prod: TransactionLifecycleCoordinator.kt, TransactionsViewModel.kt, new UiWriteGuard.kt, sweep VMs, SavingsGoalsScreen.kt,
BootReceiver.kt, MainActivity.kt :255-303 only if split-editor caller does not catch. Tests: new
TransactionLifecycleCoordinatorGroupLinkedGuardTest, UiWriteGuardTest, <VM>WriteBlockedTest per VM; BootReceiverStressTest; TransactionsViewModelTest.
Reviewers reviewer-strict + architecture-guardian. Filters: *GroupLinkedGuardTest, *TransactionsViewModelTest, *UiWriteGuardTest,
*WriteBlockedTest, *SavingsGoalsViewModelTest, *BootReceiver*, *GroupTransactionCoordinator*, then static-guards.
Stop: FK/schema change; new group legal path; verify_db_access_boundaries/db_ownership_policy flags coordinator read of
groupExpenseDao (no allowlist growth); guard/baseline change; Robolectric bump; file outside write set.
Acceptance: linked delete leaves expense + group_expenses + event count unchanged with bounded code; unlinked unchanged; swept VMs
survive DatabaseAccessBlockedException with message, CE propagates; snackbar after success; boot sdk35 0 starts, sdk34 1, replaced 1.
Device: delete group-linked row; writes during backup export; API 35 real reboot granted/revoked (logcat FGS exceptions, dumpsys),
adb install -r, API 34 reboot.
Known limitation (owner note): 1.0 cannot delete a single group expense (only whole group).
### R19 (H-1 hide)
New ui/navigation/DeferredFeatureGate.kt DEFERRED_FEATURE_IDS {tax, investment, challenges}, available = BuildConfig.DEBUG;
FeatureConfig.availableFeatures(bankSyncAvailable, deferredAvailable) (only caller HomeScreen :1551); destinationFromSaveToken(...,
deferredAvailable) null for investment_portfolio/spending_challenges/tax_configuration (callers :482/:487); MainActivity :729/:762/:863
redirect LaunchedEffect navigateHome (as :751-754). No deep-link hosts/search/notifications/shortcuts/home cards for them; no
workers; keep tables + BackupVerifier entries. Privacy tile: feature_privacy_settings(+_desc) in values/-el/-es at FeatureConfig
:237/:241. TaxEstimator :28-34 comment -> hidden in release by DeferredFeatureGate. Voice search: orphan string nlp_search_voice_cd
(strings :1761) no UI -> delete in 3 locales; other "Coming soon" only code comments.
Write set: FeatureConfig.kt, new DeferredFeatureGate.kt, NavigationController.kt, MainActivity.kt, HomeScreen.kt, TaxEstimator.kt,
res values{,-el,-es}/strings.xml; tests FeatureConfigNavigationContractTest, NavigationRouteContractTest, DeepLinkParserTest, new DeferredFeatureGateTest.
Review: cheap (Sonnet), Opus if persisted restore changes beyond the parameter. Filters: com.yourname.expensetracker.ui.navigation.*,
*BankFeatureAvailabilityTest. Stop: weakening restore tests, guard change, data deletion, file outside write set.
Device: release tile grid en/el/es; process-death restore from Tax lands Home.

## 23. R21 + R23 + R25 parts 1-2 plan (release-planner, read-only @ 2ca932a9, 37/40 calls, COMPLETE)
Order: commit+validate R16 first (ManualRecurringExpenseScreen overlap); then R21 -> R23 -> R25 serially (shared strings, manifest).
### R21 (reviewer-strict)
V-C1: new MarkRecurringBillPaidUseCase: earliest open PLANNED occurrence -> expense via TLC -> insertAtomic (rule merchant/amount/
currency/category, date now, source RECURRING_GENERATED) -> new RecurringLifecycleCoordinator.linkExpenseToSpecificOccurrence
(reuse claim/fulfil/suppress :337-414 as private helper; no-op if TransactionSideEffectPlanner :332 auto-linked) -> advanceNextDate
to next open occurrence due date (fixed anchor; no RecurrenceCalculator 31->28 drift). Dialog second choice "Already recorded" ->
skipOccurrence. Retry on PAID only advances.
V-C2: RecurringRuleLifecycleCoordinator.topUpHorizon(ruleId, now) (activateRule sequence, anchorDayOfMonth from generateOccurrences
:129-140, one runWrite+tx per rule, event only when inserted) from new RecurringHorizonWorker (daily periodic KEEP, WorkerExecutionGuard
requiresNotificationPermission=false, allowDuringBackupExport=false, RETRY). Catch-up <= 12 months back as overdue PLANNED,
allowPastDueReminderDeliveries=false.
V-C3: BillReminderWorker collapseStaleDueDeliveries(now): keep latest due window per occurrence + newest overdue occurrence per rule;
others CANCELLED reason STALE_WINDOW_SUPERSEDED / STALE_WINDOW_EXPIRED (no new status literal).
V-C4: homeCurrency.first(); unresolved -> refuse save with string error.
HZ-05 F1: WorkerSpec :94 KEEP; scheduleAtMidnight policyOverride; DailyBriefingWorker :129 self-reschedule APPEND_OR_REPLACE.
Write set: ManualRecurringExpenseViewModel/Screen, new MarkRecurringBillPaidUseCase, RecurringLifecycleCoordinator,
RecurringRuleLifecycleCoordinator, BillReminderWorker, new service/reminder/RecurringHorizonWorker, WorkerSpec, WorkerSpecScheduler
(listAllWorkerNames), WorkerRegistry, WorkerGuardVerifier, DailyBriefingWorker, strings x3, LEGAL_PATHS.md; tests existing + 2 new.
Filters: *RecurringRuleLifecycleCoordinatorTest, *.RecurringLifecycleCoordinatorTest, *BillReminderWorker*, *ManualRecurringExpenseViewModelTest,
*MarkRecurringBillPaidUseCaseTest, *RecurringHorizonWorkerTest, *WorkerSpecSchedulerTest, *DailyBriefingWorkerTest, *RecurringArchitectureGuardTest.
No schema. Acceptance: one expense via insertAtomic, occurrence PAID+linkedExpenseId, planned FULFILLED, deliveries cancelled,
Jan31->Feb28->Mar31; double tap one expense; second top-up inserts nothing; 3 missed months + grant -> <= 1 notification per rule;
non-EUR saved; briefing work id stable across cold starts and chain survives. Stop: Room, TLC signature, guard edit beyond worker
inventory, > 2 iterations. OWNER (blocking): approve one new guarded worker (RecurringHorizonWorker).
### R23 (privacy-security-guardian)
PrivacyCapability.EXCHANGE_RATE_DOWNLOAD + PrivacySettings.exchangeRateDownloadEnabled default false, FAIL_CLOSED false, not copied
from cloudAiEnabled. Toggle in Privacy settings "Network" group; currency screen names cause + "Open privacy settings". Repo :48 new
capability; refresh typed outcome Updated/DisabledByPrivacy/NetworkUnavailable/ProviderError -> string resources (replaces raw :261).
Copy :1299-1301 fixed. Backup screen notice "receipt images not included" (3 locales); domain-quick-reference.md :48 corrected.
DataStore: new Preferences key exchange_rate_download_enabled (missing = false), additive, no migration; no Room.
Write set: domain/privacy PrivacyCapability, PrivacySettings, PrivacyGate, PrivacyGateReasonCodes, PrivacyCapabilityHandlingPolicy,
PrivacyBlocked; PrivacyBlockedUiState/Card (exhaustive when); PrivacyAuditLoggerImpl; PrivacySettingsRepositoryImpl; PrivacySettingsViewModel/
Screen; CurrencyRatesRepository/Impl + refresh callers; CurrencyManagementViewModel/Screen (:63/:148); BackupRestoreScreen; strings;
quick reference; LEGAL_PATHS.md. Filters: *CurrencyRatesRepositoryImplTest, *CurrencyManagementViewModelTest, *PrivacySettingsRepositoryImpl*,
*PrivacyGate*. Stop: EffectiveCloudAiPolicy/other defaults change, new worker. OWNER (blocking): default OFF (recommended) vs ON
(ECB request sends no user data, only device IP).
### R25 parts 1-2 (reviewer-strict + room-migration-guardian; privacy-security-guardian for export)
Room opens the file lazily on first query (AppDatabase :8913-8919, no destructive fallback) -> Hilt graph build safe. Entry points:
MainApplication -> AppStartupDelegate/Coordinator; Configuration.Provider -> HiltWorkerFactory -> workers (WorkerExecutionGuard
run-logger writes DB); NotificationCaptureService; Boot/ServiceRestart/SnoozeReminder/DismissReminder receivers; MainActivity VMs.
D: DatabaseOpenGate plain object, once per process; no file = fresh; read-only open PRAGMA user_version (<145 too old, >151 too new,
exception open failed); never writes. Safety net DatabaseModule :36 .openHelperFactory(GatedOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))
throws bounded exception when blocked. MainApplication skips AppStartupDelegate (RescueConfig precedent :48-51). Separate non-exported
non-Hilt UnsupportedDatabaseActivity; MainActivity redirects before setContent. WorkerExecutionGuard checks gate before run-logger, skip
no retry; service/receivers return early.
F2: :152 MIN_SUPPORTED_SCHEMA_VERSION = MIGRATION_BASELINE; :2193 BACKUP_SCHEMA_UNSUPPORTED_LEGACY / _NEWER, drop free-text Timber.e;
.costbackup manifest.databaseVersion check after parse (abort pattern :1049-1057).
Write set new: data/database/DatabaseOpenGate.kt, GatedOpenHelperFactory.kt, ui/unsupported/UnsupportedDatabaseActivity.kt; changed:
DatabaseModule, MainApplication, MainActivity, WorkerExecutionGuard, NotificationCaptureService, 4 receivers, AndroidManifest.xml,
DatabaseBackupRepositoryImpl; KDoc DatabaseSchemaPolicy :19, AppDatabase :15-42; strings; LEGAL_PATHS.md.
Filters: *DatabaseOpenGateTest (v144, v145, v151, v152, garbage, missing, WAL; bytes unchanged), *GatedOpenHelperFactoryTest,
*UnsupportedDatabaseActivityTest, *WorkerExecutionGuardTest, *DatabaseBackupRepositoryImplTest, *MigrationRegistrationTest. No schema.
Device: push v144/v152 fixtures (debug run-as), cold start, force job, post notification, hash unchanged; export byte-identical;
healthy DB with WAL not blocked. Stop: destructive option, A-narrow/B work, guard weakening.
OWNER (blocking): untouched-file export = plaintext raw DB export while RAW_DATABASE_EXPORT is disabled in release -> approve exception
(user-started, SAF picker only) or require encryption. To confirm: MainActivity injected fields querying at injection; WorkManagerInitializer
removal in manifest; privacy settings in backups.

## 24. R26 plan (release-planner, read-only @ release-1.0-wt, 39/40 calls) — summary; full text in agent report, key points:
- Verified: :678 only locking_mode setter; snapshot consistency from barrier + enterAndDrain(failOnTimeout) :652 + TRUNCATE checkpoint
  :3013 + recheck :683 + VACUUM INTO single read tx -> delete :678, fix comments :670-677.
- VACUUM INTO needs API 30+ (26-29 fallback copy). OPEN_READONLY unverified on framework SQLite -> deciding test A2; if fallback,
  keep OPEN_READWRITE (SHARED-only, not root cause).
- :613 start -> CancellationSafe.runCatchingCancellable { start } .getOrElse { NoOpOperationRunHandle }.
- BackupRestoreViewModel createBackup (:106-151) no catch; restoreBackup queryUriSize/createTempFile (:184/:193) outside catch;
  runCatching (:194) and catch(Throwable) (:231) swallow CE -> fix.
- CompositePrivacyGate :69 -> wrap audit call, CE rethrow, class-name log, return existing decision if already blocking else
  PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE) (exists :15). No test asserts propagation.
- Audit dedup CONFLICTS with docs/reference/backend-domain-map.md :610 "Records every gate check" -> core R26 = batching only; dedup
  = R26-D after owner Q1.
- Batched purges: PrivacyAuditDao :36, PipelineDiagnosticEventDao :40 add limit (DELETE ... WHERE id IN (SELECT id ... ORDER BY id
  LIMIT :limit)); RetentionModule :302-330/:616-640 loop barrier -> delete 5000 -> ensureActive until < 5000; barrier closed mid-run
  -> transient WRITE_BARRIER_BLOCKED, checkpoint PENDING. No schema.
- Write set prod: DatabaseBackupRepositoryImpl, SqliteSnapshotCreator, BackupRestoreViewModel, CompositePrivacyGate, PrivacyAuditDao,
  PipelineDiagnosticEventDao, RetentionModule. Tests: new androidTest data/backup/BackupWalLockInstrumentedTest (A1/A2); edit
  DatabaseBackupRepositoryImplTest (B1), BackupRestoreViewModelTest (B2), CompositePrivacyGateTest (C), PrivacyAuditDaoTest (D1);
  new di/RetentionModuleBatchedPurgeTest (D). Regression *DataRetentionWorkerTest, *PrivacyGateContractTest, static-guards, app-check.
- Reviewers: reviewer-strict, room-migration-guardian, privacy-security-guardian (item 5). Stop: schema/index; guard/allowlist growth
  or non-R7 regeneration; test expecting audit propagation; PRIVACY_AUDIT_RETENTION_DAYS touch; outside write set; > 2 iterations;
  R16 not committed. Device: emulator seed 400k+ rows each over 200 d + 1.2k expenses; backup OK, immediate expense add OK, restore
  round trip, forced retention < 10 min, second backup OK.
## E0 evidence (lead, count-only on the safety copy, owner consent)
- pipeline_diagnostic_events: 388,175 rows, 2026-09-15..2026-10-16 -> 30-day retention IS running (old build); Sep 71k, Oct 1-16 317k
  (early-October spike); last 7 days 28.5k (~4k/day): stage listener 14,077 / filter 7,866 / dedupe 6,188 / SIDE_EFFECT 340.
- privacy_audit_events: 363,161 rows, 2026-06-02..2026-10-16 (all within 180 d); monthly 39k/44k/55k/66k/159k(16 d). Last 7 days:
  NOTIFICATION_CAPTURE ALLOWED 14,100 (caller privacy-gate) of 14,121 -> ~2k/day = one per notification.
- background_job_runs: data_retention SUCCESS 135 runs, last 2026-10-15.
- Conclusion: volume is real and notification-driven; batching alone keeps purges short but steady state stays ~120k diag + ~360k
  audit rows (more with spikes). Proposed R26-V (diagnostics: coalesce per-notification listener/filter/dedupe pass events) and
  R26-D (audit: coalesce ALLOWED local NOTIFICATION_CAPTURE decisions) -> owner Q1.

## 25. R26-V / R26-D plan (release-planner, read-only, 32/35) — summary
- Q-V1 finding: only listener/refresh RECEIVED (non-terminal, no reasonCode; NotificationCaptureService :391-403/:440) is a routine
  pass (14,077/7 d). filter (:595-607 DROPPED+FILTER_REJECTED terminal, 7,866) and dedupe (:577-588 DUPLICATE terminal, 6,188) are
  terminal drops with reason -> written unless owner reclassifies INFO FILTER_REJECTED/DUPLICATE as routine. No release feature
  needs per-notification rows (only DiagnosticsRepository, DI-only).
- R26-V: coalesce in NotificationDiagnosticEmitter (TimeProvider param): first RECEIVED per stage per hour written, later counted;
  next-window summary row metadata count=N via existing writer.emit; LRU(64) by correlationId writes suppressed RECEIVED before any
  problem event of the same notification; emitOrdered* bypass; Mutex; CE rethrow. Process death loses < 1 h count (lower bound).
  Write set: NotificationDiagnosticEmitter.kt; new NotificationDiagnosticEmitterCoalescingTest; constructor edits in
  NotificationIntakeRestoreBarrierTest :63, NotificationIntakeEnqueueFailureTest :110. Tests V1-V7 (+V8 if Q-V1).
- R26-D: PrivacyAuditLoggerImpl.logDecision (:26-61) COALESCIBLE_ALLOWED = {NOTIFICATION_CAPTURE}; only Allowed coalesced; key
  capability+caller+canonical sanitized context; fixed 60-min window; coalescedCount injected after sanitizeContext (callers cannot
  spoof); non-coalescible decision flushes pending then writes; <= 32 keys LRU flush; insert failure opens no window. Docs
  "every gate check": backend-domain-map.md :610, DEPENDENCY_MAP.md :700, backend-data-map.md :189/:350, ARCHITECTURE.md
  :514/:1181/:1564. Tests D1-D9. New test PrivacyAuditLoggerImplCoalescingTest.
- Filters: *NotificationDiagnosticEmitterCoalescingTest, com.yourname.expensetracker.diagnostics.*, *NotificationIntake*Test,
  *NotificationCaptureService*, *PrivacyAuditLoggerImplCoalescingTest, *CompositePrivacyGateTest, *PrivacyGateEnforcementGoldenTest,
  *PrivacyGateContractTest, *WriteBarrierArchitectureGuardTest, *CancellationSafetyArchitectureGuardTest; static-guards; app-check.
  Reviewers privacy-security-guardian then reviewer-strict. Order: R26 core -> R26-D -> R26-V.
- Expected: diagnostics 122k -> 63k (listener only) or ~6k (with Q-V1); audit 360k -> ~5k.
- RISKS: WriteBarrier U-004 allowlist entries for DiagnosticEventWriter and PrivacyAuditLoggerImpl EXPIRE 2026-10-31 -> static-guards
  turns red after that date regardless (owner/guard-hardening decision needed before release). NotificationDiagnosticEmitter.emit
  catch(Throwable) :46 swallows CE (follow-up).
- Lead reading of owner approval: Q-D1 = {NOTIFICATION_CAPTURE} only (as described to owner). Q-V1 and process-death loss -> asked.


## 26. G3 + G2b plan (release-planner, read-only, 2026-10-16; base 88382c4c + G2 diff)
Decision: no new reason code; CompositePrivacyGate :72-85 maps any audit-write exception (incl. DatabaseAccessBlockedException) to
FailClosed(PRIVACY_AUDIT_WRITE_FAILED); WorkerExecutionGuard :169/:356 retries; NotificationCaptureGate :163 blocks before :233 in
maintenance. PRIVACY_AUDIT_WRITE_BLOCKED dropped.
S1 (before 10-31) barrier: ReceiptInsertResolver :18-19/:32, DebugExpenseAuditWriter :20-23/:29/:58, ReceiptLifecycleEventWriter
  :42/:49, TransactionLifecycleEventWriter :41/:48; post-commit path no change (CompositeSideEffectEventWriter :57-63 swallows non-CE).
  Tests: new LifecycleWritersWriteBarrierTest; TransactionSideEffectFailureEventWriterTest case; constructor fixes
  ReceiptDuplicateFingerprintCollisionTest, ReceiptPartialOcrPipelineTest. Risk M: callers in non-NORMAL mode (startup recovery) —
  coder greps first.
S2 (before 10-31) PrivacyAuditLoggerImpl barrier :14-17/:51; DBRI exportDatabase: settings read :408-409 + privacy checks :413-433
  moved before enterAndDrain :391. G2b: restore pre-swap abort finalizeRunFailed after exitAfterPreDestructiveAbort() at :1023, :1054,
  :1077, :1102, :1134, :1154, :1174, :1187; reset :2878, :2977; legacy import swap failedFinal/exit(false) at :1874, 1880, 1886, 1898,
  1906, 1920, 1957, 1968, :2120 (:2093 only if destinationFilesMutated provably false). Post-swap: keep journal + safe sink, never write
  old run id; no non-bank stale sweep for 1.0 (R27/post-1.0). Tests: CompositePrivacyGateTest, PrivacyGateEnforcementGoldenTest
  constructor, DatabaseBackupRepositoryImplTest (privacy before maintenance; finalize-after-exit restore/import/reset).
S3 (before 10-31) COVERED_BY_CALLER category in WriteBarrierArchitectureGuardTest: remove 8 U-004 entries + table; map with mechanism
  (WORKER_CHECKPOINT / SOLE_CALLER_GUARDED), justification, evidence; hygiene (cap 3, no overlap, non-blank, evidence exists);
  fixtures; KDoc :36-38. WarrantyExpirationWorker ctx.checkpoint before :138. NotificationIntakeWorker only if gap found.
  Test WarrantyExpirationWorkerTest. Reviewer architecture-guardian.
S4 (before 11-20, HIGH) NotificationRepository :201 withTransaction -> DomainTransactionRunner; ReviewQueueRepository.approveReview
  :281 event write in nested runner call inside the raw :224 transaction. Tests: NotificationRepositoryDeleteAllNotificationsClockTest,
  NotificationRepositoryStressTest, ReviewQueueRepositoryTest, ReviewQueueRepositoryNestedRollbackTest, ReviewQueueRepositoryStressTest.
  Guard effects: event_writers.json stale keys; db_ownership_policy.signatures.accounting.json TransactionEventDao|insert keys ->
  regenerate. Reviewer reviewer-strict.
S5 (before 11-20, after S4) DirectEventDaoInsertGuardTest: remove ReceiptRepository, RecurringExpenseRepository,
  ManualRecurringExpenseRepository, ReviewQueueRepository, NotificationRepository; reclassify WarrantyTrackerRepository (warranty owner,
  LEGAL_PATHS :564) and BankApiIntegration (DEBUG_ONLY; requireStubMode :874-875; barrier :369 before :377); stale-entry test (legacy +
  debug-only only). Warranty :432 cannot be private (6 test callers) -> barrier inside try :481 before :482 + test.
S6 ratchet sync (shrink-only) at batch end.
Validation: targeted per slice + WorkerExecutionGuardTest, PrivacyGateContractTest, WorkerRunLoggerTest,
  NotificationIntakeWorkerTimeoutTest, TransactionContextProvenanceGuardTest, WriteBarrierArchitectureGuardTest,
  DirectEventDaoInsertGuardTest; after S4 registered-guard db_access + known_good_state; batch end static-guards, app-check, legacy-tests.
Owner questions: (Q-G3-1) pre-approve shrink-only guard/baseline edits (event_writers keys, db_ownership_policy regeneration for the
  2 removed writes + count pins, cancellation.json decreases); (Q-G3-2) expiry for the 2 reclassified DirectEventDao entries.

## 27. Batch R28+HL-1 (owner approved 2026-10-16, confirmed "I approve")

Discovery: verifier (release-finding-verifier) R28 CONFIRMED - no production path removes old intake ciphertext once the worker's
best-effort purge is skipped (no DELETE on notification_intake; only worker callers of purgeAllPayload). Scout: active policy row
db_ownership_policy.yml :3601-3618 (RetentionModule.provideRetentionTargets, notificationIntakeDao, purgeRawPayload, helper).
Candidate/accounting/seeds are generated historical inputs (db_artifact_sync regenerates from legacy.yml + GR-08 seeds) -> untouched,
as in G3 part 1.

Decisions (lead):
- Selection stays status-agnostic (current code already nulls plaintext on non-terminal rows; a status filter would keep ciphertext
  on stuck rows forever; a PROCESSING row holds its payload in memory; a TRANSIENT row without ciphertext ends as bounded
  PAYLOAD_UNAVAILABLE_PRIVACY).
- Predicate (in place, single caller): capturedAt < :cutoffMs AND (rawPayloadPurgedAt IS NULL OR transientPayloadCiphertext IS NOT NULL).
- Active policy row: operation purgeRawPayload -> purgeAllPayload, reason reworded. Same site, accessor, DAO, barrierMode and row
  count; it authorizes exactly the owner-approved R28 call. Treated as covered by the R28 approval (not a new writer site).
- purgeRawPayload DAO method kept (seed tests reference it).

Write set (release-remediation-coder):
1. NotificationIntakeDao.kt :302-310 predicate + comment.
2. RetentionModule.kt :258-261 comment, :287 purgeAllPayload.
3. config/guards/db_ownership_policy.yml :3612 operation + :3614-3616 reason.
4. RetentionTargetPurgeTest: transientPayloadPurgedAt assertion on the existing case + 6 cases (old TRANSIENT ciphertext purged;
   rawPayloadPurgedAt set + ciphertext present purged; recent row untouched; fully purged row not reselected / idempotent; 205 rows
   across batch boundary; old RECEIVED row purged = status-agnostic pin).
5. NotificationIntakeWorkerTimeoutTest: TRANSIENT row with null ciphertext -> markTerminal PAYLOAD_UNAVAILABLE_PRIVACY + purgeAllPayload.
6. config/validation/known-hanging-tests.json HANG-001..003: owner @panospao7, evidence += clean legacy-tests run
   vr-20261016-125302-b26c6ac6 (no hang), review_by 2026-12-01, status suspected.
Validation: targeted RetentionTargetPurgeTest, NotificationIntakeWorkerTimeoutTest, RetentionModuleBatchedPurgeTest -> review
(privacy-security-guardian) -> legacy-tests -> static-guards -> app-check. Stop on any guard failure (debug cap 2).
Out of scope (new finding R28-X, triage later): ExportAnonymizer.sanitizeNotificationIntake (:267-293) keeps ciphertext in the
export copy.


## 28. G3 part 2 refresh (base 8671b844; lead, from verifier S4 + scout S5 discovery)
Corrections to section 26: generated policy files (signatures.candidate.yml/.accounting.json, GR-08-seeds.yml) are NOT regenerated
(pinned at 407 candidate entries / seed counts); only the active db_ownership_policy.yml and config/baselines/event_writers.json shrink.
DirectEventDaoInsertGuardTest LEGACY_REPOSITORY entries expire 2026-11-20 (hard deadline); 75-day cap test :390 -> the two 2027-01-01
entries need their own categories (WARRANTY_OWNER, DEBUG_ONLY) implementing owner decision Q-G3-2.
S4 (HIGH, reviewer-strict): NotificationRepository.deleteAllNotifications (:200-203, no prod caller; kept) raw withTransaction ->
  transactionRunner.runInTransaction + transactionLifecycleEventWriter.write(ctx.copy(occurredAt = now), BULK_DELETED); ReviewQueueRepository
  approve :281-302 SOURCE_LINKED -> nested runner + writer (no try/catch; joins outer :224 tx; raw tx :224/:457/:634 unchanged).
  Constructors: + DomainTransactionRunner, + TransactionLifecycleEventWriter, - TransactionEventDao (keep writeBarrier/database).
  Tests: 5 constructor sites + new SOURCE_LINKED inserted>0 test + writer-failure rollback test. Guard shrink (Q-G3-1): event_writers.json
  DAO/ENTITY lines for both files; db_ownership_policy.yml delete the two transactionEventDao|insert rows (:1956-1972, :2637-2665).
  Accepted audit-content change: actor null -> "system", correlationId -> UUID, + tx* metadata keys (reviewer checks consumers).
S5 (architecture-guardian): DirectEventDaoInsertGuardTest remove Receipt/RecurringExpense/ManualRecurring/ReviewQueue/Notification
  entries; Warranty -> WARRANTY_OWNER, BankApiIntegration -> DEBUG_ONLY, expiry 2027-01-01; stale-entry test (LEGACY_REPOSITORY +
  DEBUG_ONLY + WARRANTY_OWNER must still contain a guarded insert); category-aware expiry cap (legacy 75 d; the two new categories
  max 2027-01-01). WarrantyTrackerRepository: barrier check inside try :481 before discard insert :482; catches that sit in the same
  transaction as the warranty insert rethrow DatabaseAccessBlockedException (S1-N1 :201; also :135/:164 if same pattern) so no id of an
  unsaved warranty is returned; :490 discard catch may stay best-effort. Tests in WarrantyTrackerRepositoryTest.
S6: ratchet/baseline sync (shrink-only) at batch end, driven by guard results.
Order: S4 coder -> S5 coder -> targeted queue (+ reviews in parallel, read-only) -> registered-guard db_access + known_good_state ->
  static-guards -> app-check -> legacy-tests.


## 29. R27 plan (release-planner, read-only @ 6f706c29) - summary
S1 barrier counter + drains: DatabaseWriteBarrier.runWrite counts in-flight writes (MutableStateFlow<Int>; increment, check, withContext
  (InFlightWriteElement) block, decrement in finally; nested = check only); awaitNoInFlightWrites(timeoutMs) (withTimeoutOrNull, suspend);
  reentrant drain -> MAINTENANCE_DRAIN_REENTRANT; order enter -> worker drain -> write drain; enterAndDrain timeout -> exit(false) +
  WriteDrainTimeoutException (MAINTENANCE_WRITE_DRAIN_TIMEOUT); enterRestoreAndDrain -> RESTORE_WRITE_DRAIN_TIMEOUT keeps barrier closed.
  Files DatabaseWriteBarrier.kt, MaintenanceOperationRunner.kt :27-41, DatabaseBackupRepositoryImpl.kt :970-977 + tests (9 cases).
S2 caller migration (check-only -> runWrite wrapping the existing tx in the same method; no renames): S2a TransactionLifecycleCoordinator
  :1296/:1350/:1458/:1552+/:1595/:1912/:2215/:2467; S2b ReceiptLifecycleCoordinator :425/:503/:611/:823/:868/:1443/:1513/:1599;
  S2c NotificationRepository 9 sites + leftovers in intake/pipeline; S2d OperationRunRecorder :122/:192/:243/:280 + DiagnosticEventWriter
  :46; S2e PrivacyAuditLoggerImpl insert loop after B merges. Risk: relaxed barrier mocks no longer run blocks -> fix per slice.
S3 restart wiring: BackupRestoreViewModel one-shot restart event on in-process SuccessNeedsRestart (never from persisted mode -> no loop);
  BackupRestoreScreen -> onRestartRequired; MainActivity :874-876 passes restartProcess() (:433); delete dismissRestartRequired + test;
  mode persisted with commit() before exit.
S4 bank finalize: BankStatementLifecycleProcessor :873/:917/:974-990/:1006/:1049 in runWrite; blocked finalize skipped by existing
  catches; BankSyncStartupRecovery.recoverStaleRuns :72-81 -> STALE_FAILED (confirm threshold + PARSED receipt recovery).
Guards: no WriteBarrierArchitectureGuardTest/db_ownership_policy/cancellation count changes expected. Reviewers reviewer-strict
  (S1+S2+S4) + room-migration-guardian (S1+S3). Emulator probes C3a/C3b/C2/C1/P5 after the batch.
Owner questions: Q-R27-1 central RoomDomainTransactionRunner wrap (rec: no, R27b later); Q-R27-2 write-drain timeout + fail closed;
  Q-R27-3 restart = exit(0) only or relaunch + exit; Q-R27-4 ~60 other check-only classes accepted for 1.0 (rely on R25) or follow-up;
  Q-R27-5 optional new tightening guard pinning runWrite coverage for R27 classes.


## 30. R21 plan (release-planner, read-only @ 6f706c29) - summary
Repositories (ManualRecurringExpenseRepository :27-45, RecurringExpenseRepository :116-130) already delegate to the rule coordinator
(no bypass). Order: R27 -> R29 commit -> S1 -> {S2, S3b, S4}; S3a independent.
S1 RecurringLifecycleCoordinator: private claimLinkSuppressInWrite(expenseId, selectTarget) (runWrite + tx; already-linked guard);
  linkExpenseToOccurrence keeps matching; new linkExpenseToSpecificOccurrence (Linked/AlreadyLinkedToThis/AlreadyLinkedElsewhere/NotOpen);
  findEarliestOpenOccurrence; collapseStaleDueDeliveries(now) (STALE_WINDOW_SUPERSEDED / STALE_WINDOW_EXPIRED; CLAIMED untouched; new DAO
  cancelOpenDueDeliveriesByIds chunked 900); RecurringRuleLifecycleCoordinator.topUpHorizon(ruleId, now) (free slots only, catch-up
  >= now-12 months, allowPastDueReminderDeliveries=false, RULE_HORIZON_TOPPED_UP only when inserted); resolveAnchorDay = max
  day-of-month of latest 12 occurrences.
S2 MarkRecurringBillPaidUseCase (@Singleton, Mutex per rule, seenNextDate double-tap guard, TLC createExpenseDbOnlyV2 STRICT_EXTERNAL_ID
  recurring_paid:<occurrenceKey>, link specific, dispatchPostCreationSideEffects, advanceNextDate); VM currency via resolveHomeCurrency()
  (Failed -> refuse), bounded string errors; screen dialog "Already recorded" -> skipOccurrence; strings x3.
S3a HZ-05: WorkerSpec oneShotPolicy KEEP; scheduleAtMidnight policyOverride; DailyBriefingWorker APPEND_OR_REPLACE; WorkerIdempotencyTest
  REPLACE -> KEEP (approved behaviour change).
S3b RecurringHorizonWorker (guarded, coordinators only, ctx.checkpoint per rule) + registrations; counts 7->8 / 10->11 in contract tests;
  worker docs + LEGAL_PATHS Recurring.
S4 BillReminderWorker :71 collapseStaleDueDeliveries before recoverAndGetDueReminders.
Policy: +1 row collapseStaleDueDeliveries/reminderDeliveryDao/cancelOpenDueDeliveriesByIds/helper; 3 rows re-keyed to
  claimLinkSuppressInWrite (net 0); generated files untouched (precedent) unless a guard proves otherwise; stop on new GR-13 UNPROVEN.
LEAD-DECISION (standing approval) on planner questions: (1) anchor drift after payment accepted for 1.0, documented known limitation;
  (2) horizon worker not privacy-gated (local data only); (3) RULE_HORIZON_TOPPED_UP event + HORIZON_TOP_UP generation source approved;
  (4) 3-row policy re-key (net 0) + 1 added row approved as part of the owner-approved R21 batch (no loosening).


## 31. R23 plan (release-planner @ f631d1be) - summary
NetworkPrivacyGate (new; EXCHANGE_RATE_DOWNLOAD; corrupted -> FailClosed, disabled -> Denied, enabled -> Allowed, else NotApplicable)
registered in PrivacyModule; S1 capability/settings (default false, FAIL_CLOSED false)/repo key/policy/reason codes/PrivacyBlocked/UI
state/card/audit description + all strings (contiguous block); S2 CurrencyRatesRepository typed outcome, VM/screen bounded error mapping,
offline banner suppressed when disabled; S3 privacy settings Network section; S4 backup notice (true: in-app backups redacted, no images),
KDoc, quick reference, LEGAL_PATHS. Reviewers privacy-security-guardian + reviewer-strict.


## 32. R25 parts 1-2 + R27 safeguard plan (release-planner @ f631d1be) - summary
A core: DatabaseOpenGate (read-only probe, no-op DatabaseErrorHandler, v0 + no tables = fresh, <145 TOO_OLD, >151 TOO_NEW, exception
OPEN_FAILED; once per process); GatedOpenHelperFactory (fast path when open; refuses when gate blocked or RESTORE_SWAPPING via in-process
latch or persisted peek; unreadable mode + no latch -> allow); RestoreMaintenanceMode pure decodePersistedState + swap latch;
RestoreMaintenanceModePeek (no Hilt); DatabaseModule :36 only; exact db_access structural-exception tuples. B1 entry points
(MainApplication early return, WorkerExecutionGuard Skipped first, NotificationCaptureService, 4 receivers). B2 UnsupportedDatabaseActivity
(plain, non-exported, SAF export, bounded logs) + MainActivity redirect + manifest + strings. C backup floor = MIGRATION_BASELINE with
LEGACY/NEWER codes + manifest version check (v86 branch left unreachable). D docs. Reviewers reviewer-strict + room-migration-guardian +
privacy-security-guardian (B2).
LEAD-DECISIONS (standing approval): H1 the ~3 exact db_access structural-exception tuples are a direct consequence of the owner-approved
R25 design (read-only PRAGMA probe) -> approved as exact additions, recorded; H2 export = zip of db + -wal (never -shm); H3 accept refusal
during the cold-start SWAPPING window; H4 v0 + no tables = fresh install; H5 keep the unreachable v86 branch for 1.0; H6 no extra message.

## 33. R20 plan (release-planner, read-only @ df769791, 2026-10-17)

Canonical path: MultiCurrencyRepository.aggregateDisplayAmounts (:466) -> MoneyAggregateBuilder.fromBuckets (:352), convertOutcome + StaleRatePolicy.forBasis; unconvertible rows excluded + isPartial, all-fail -> Unavailable (precedent BillReminderManager.getMonthlyBillsTotal :173-191). No new convertMultiple callers (G-MONEY-17). R23 (rates default OFF) makes HZ01-A/B more frequent.
- HZ01-A P2 include: SubscriptionManagementViewModel :141-147 raw fallback/raw sum labelled home currency; cost-per-use :546, candidate annual :672. Fix via aggregateDisplayAmounts; partial -> dashboard_partial_currency_warning; unavailable -> currency_error_conversion_unavailable; per-row own currency; CE rethrow :164.
- HZ01-B P2 include: FinancialStressForecastEngine :362-371/:427-429 drop failed conversions (runCatching swallows CE), :250/:172 partial only on barrier; card no partial notice. Fix convertOutcome(LATEST_AVAILABLE, forBasis), RecurringOutflowResult.excludedConversionCount, partial flag + card warning. Staleness 24h -> 7d (canonical).
- HZ01b1-F1 P2 include: MonthlySavingsSweepUseCase :216 underspend - riskBuffer ignores upcoming bills; test :335-336 pins 30/270. Fix: bills + uncertainty into riskBuffer; tests 110/190 + bills>=underspend -> no sweep; KDoc :44-54.
- V-E-12 P2 include: AnalyticsViewModel prior window = same-length block (:430-432, :497-499); YoY YTD vs full year (:940-951). Fix like-for-like previous calendar period cut at same elapsed point; ALL no %.
- V-G-1 P2 include: ExportOptionsScreen :333-337 UTC-midnight millis; VM :176 rejects same day; DAO exclusive end. Fix VM/screen only: setPickedDateRange UTC LocalDate -> local start-of-day, end = next local day start; chips/metadata end-1ms.
- V-D F-2 P3 include: CalculateFinancialForecastUseCase :29-48 add homeCurrency/lastRateUpdate triggers (pattern AnalyticsViewModel :310-317).
- V-D F-1/CURR-6 P2 DEFER 1.0.1 + Known limitation (sorting/filtering by amount after home-currency change).
- NEW candidate outside R20: other date pickers interpret UTC picker millis in system zone (AddExpenseSheet :847-849, HomeScreen :1527, ManualRecurringExpenseScreen :636, SavingsGoalsScreen :503, SubscriptionManagementScreen :865) -> triage separately (R20c?).
Slices: R20a (S1 subscriptions, S2 stress forecast, S3 savings sweep; reviewer-strict) and R20b (S4 analytics, S5 export dates, S6 forecast reactivity; Sonnet reviewer); disjoint write sets; no strings/DAO/schema/worker/guard edits. S7 lead docs at commit. Gate: targeted filters, static-guards (G-MONEY-15/17/19), app-check, legacy.
Owner decisions (lead adopted planner recommendations under standing approval): Q-R20-1 bills inside existing Buffer figure; Q-R20-2 like-for-like period-to-date %; Q-R20-3 CURR-6 deferred to 1.0.1 with note.
Pre-existing P3: dashboard_partial_currency_warning lacks el/es translations.

## 34b. R24 + R20c plan (release-planner, read-only @ df769791, 2026-10-17)
R24: (a) ReviewViewModel :421-425 replaces every Result.Error with a prefix; ReviewQueueRepository :386 returns raw e.message (joined validation errors :374) -> bounded constant; VM shows message only if a known public constant. P2 include (reviewer-strict). (b) POST_NOTIFICATIONS: MainActivity :503-510 dialog every launch, deny callback :497-500 reopens immediately (loop after permanent denial) -> pure shouldAutoPrompt(granted, sdk, alreadyAsked) + prefs flag; no reopen on deny; when !shouldShowRequestPermissionRationale the button opens ACTION_APP_NOTIFICATION_SETTINGS. P2 include. (c) ReceiptScanViewModel :460 blank OCR currency -> home currency silently; add currencyUnverified state (cleared by updateCurrency :560), banner near ReceiptOcrCoverageWarning (ReceiptScanScreen :480), quick-save (:1100) off while unverified. P2 money include (reviewer-strict). Warranty: fixed by R29 + R18b; leftover P3 loadStats :91 runCatching CE swallow -> small fix after R18. V-I-7 onboarding: defer 1.0.x.
R20c: AddExpenseSheet DateSelector :801-802/:847-849 (shared by AddExpense :274, ReviewScreen :1765, ReceiptScanScreen :891) P2; HomeScreen planned-expense :1489-1490/:1527-1528; ManualRecurringExpenseScreen :630/:636; SavingsGoalsScreen :495-503; SubscriptionManagementScreen :881-889; FormComponents :158/:165 (unused). Shared helpers in ui/util/UiTimeUtils.kt (pickerMillisToLocalDate [UTC read], localDateToPickerMillis, instantToPickerMillis, pickerMillisToLocalStartOfDay, pickerMillisKeepingTimeOf) with injectable zone; R20b reuses pickerMillisToLocalDate.
Slices/sequence: R20c-1 (UiTimeUtils+test, AddExpenseSheet, ManualRecurringExpenseScreen, FormComponents; reviewer-strict) after R20a commit -> R20b (uses helper) -> R20c-2 (HomeScreen, SavingsGoalsScreen after R18; SubscriptionManagementScreen after R20a; cheap review) -> R24a (after R18) -> R24b (MainActivity, NotificationPermissionDialog, prefs store, strings x3; after R18) -> R24c (ReceiptScan VM/screen, strings x3; reviewer-strict). No schema/guard/baseline change. Device: TZ=America/New_York add expense/recurring/savings goal; notification deny path API 33+.
Owner Qs (lead adopted recommendations under standing approval): Q-R24-1 ask once per install + Settings link, no auto re-prompts; Q-R24-2 blank OCR currency -> warn + quick-save off (manual save still allowed after review); Q-R24-3 onboarding deferred to 1.0.x.

## 34a. R22b + R16c plan (release-planner, read-only @ df769791, 2026-10-17)
R22b (all in domain/receipt/BankStatementParser.kt, all P2, include): V-I-2 abs at :912 + type rules :941-946 ("+1.500,00 TRANSFER" -> expense; "VISA CREDIT CARD 45,00" -> DEPOSIT); V-I-3 dd/MM only :996-1035 (US dates null -> BankStatementLifecycleProcessor :512/:537/:562 falls back to now; "03/04/2025" -> 3 April); N1 2-digit-year pattern :999 matches inside 4-digit dates (second-column header :957 -> 2020 dates; /1999 -> 2019); N2 generic branch :210-214 missing break -> duplicate pending reviews/expenses. Fix: N2 break; V-I-2 "+" -> DEPOSIT, deposit keywords matched after removing "CREDIT CARD", else PURCHASE, "-" with deposit keyword unchanged (refunds); N1 positional regex (?<!\d)(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2}|\d{2})(?!\d); V-I-3 statement-wide pre-pass (any first part >12 -> dd/MM; else any second >12 -> MM/dd; else dd/MM). R16 parseStatementAmount unchanged. Tests new BankStatementParserSignDateTest (no edits to BankStatementParserTest). Filter *BankStatement*; reviewer-strict. Stop if an existing assertion must change.
R16c (AmountInputSanitizer.sanitize on change + parseOrNull for validation and save; Locale.US "%.2f" prefill): HomeScreen AddPlannedExpenseDialog :1362/:1462 P2; BillNegotiationScreen :639/:663 P2 (SUCCESS saved with null savings); SubscriptionManagementScreen :772/:862/:868 P2 (after R20a); VisualSplitEditorScreen :65 P2 (VisualSplitEditorScreenStateTest :51-56 pins the defect -> flip); CurrencyManagementScreen :38/:782 P3; CashFlowCalendarScreen :122 P3 (local text state); SpendingChallenges/TaxConfiguration debug-only since R19 -> defer. Tests: extend CurrencyManagementScreenValidationTest, VisualSplitEditorScreenStateTest (el_GR/en_US); filters *CurrencyManagementScreenValidationTest, *VisualSplitEditorScreenStateTest, *AmountInputSanitizerTest; reviewer-strict; el_GR device smoke.
Order: after R20a commit: R22b -> R16c-1 (Home, BillNegotiation, Currency, VisualSplit, CashFlow; Home/VisualSplit after R18 commit) -> R16c-2 (Subscription).
Owner Qs (lead adopted recommendations under standing approval): Q-R22-1 statement-wide date-order detection, dd/MM default; Q-R16c-2 flip the VisualSplit test that pins the comma defect (precedent: R20a sweep test 30/270 re-pinned); Q-R16c-3 fix CashFlow field in 1.0.

## 35. Device verification plan (release-planner x2, read-only @ 1914ee0d, 2026-10-17)
35a tooling: assemble-debug / assemble-release (debug-signed, minified, non-debuggable; release-artifact-evidence profile); connected-tests need -DeviceSerial (no emulator-only guard; without serial AGP targets all devices) -> procedure guard: phone unplugged, `adb devices` only emulator-5554, ro.kernel.qemu=1, -s on every command, ANDROID_SERIAL=emulator-5554, connected tests before any owner data. Package com.yourname.expensetracker (debug/release same id + signature; install -r swaps keeping data). DB databases/expense_tracker_db (WAL); push via run-as on debug build. Timber only in debug builds -> reason codes read on debug; release uses crash buffer + android.util.Log tags DatabaseOpenGate/UnsupportedDatabase + dumpsys.
35b probes (blocking unless noted): P1 owner DB copy v145 opens + migrates to v151 (OWNER APPROVAL Q-DEV-1 pending; counts-only host check, no data printed; pm clear after); P2 v144/v152 synthetic fixtures (sqlite user_version + one table) -> unsupported screen, SAF zip (db+wal, no shm, byte-identical), hash unchanged after job/notification; P3 backup/restore with 40 posted notifications + manual add in flight, self-relaunch, second backup OK; P4 group-linked delete/edit blocked; P5 save during backup -> paused message; P6 API35 reboot granted/revoked + install -r: no FGS exceptions, listener rebinds, capture works; P7 R17 access card; P8 R24b prompt once/deny/settings (pm revoke + clear-permission-flags); P9 TZ America/New_York date pickers (7 screens, incl. ~20:30 local); P10 el_GR "12,50" in 7 places; P11 bank statement import with synthetic PDF (debug-only feature, see below); P12 R23 ECB download off by default; P13 R19 hidden features + deep links + install -r from debug; P14 R24c synthetic receipt without currency; P15 API26 smoke on release-api26 (exists).
Finding: "Import Bank Statement" is behind debugActionsEnabled (ReviewScreen.kt :104/:186) -> not reachable in release builds; lead decision: keep as-is for 1.0 (no product change), correct the user note (R22 item -> applies to the debug/test import only) in the next records commit.
