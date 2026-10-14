# Wave 5 independent-review remediation - 2026-10-13

Status: CLOSED under owner acceptance. The original independent
review was FAIL; separate remediation re-review was not run and the user has
accepted/waived it. Source base:
3e8377e42083c92990f32f4e46fdcb481c29cc3a.
Final source commit: ea44631647e69a0dabacdc179dd094b292378b7e, pushed and
verified on origin/bug-fixes on 2026-10-14.
Review: money-w5-independent-review-handoff.md. No subagents.
Current status: CLOSED. The moved workspace is recovered. Candidate-only
compile, migration tests, app-check, 25/25 static guards and device migrations
all PASS. G1 approval, F9 retention and source publication are confirmed below.
The earlier F2 deletion bypass was reproduced
and corrected by the approved marker batch below. The separate independent
re-review requirement is owner-waived, not an independently executed PASS.

## Approved intent and bounded sequence

After the review findings and G1/F9 decisions were explained, the user instructed:
"proceed with them all". Implement the reviewed corrections rather than accepting
F4-F6 as limitations. This continuation does not authorize history rewriting or
force-pushing. Preserve the reviewer report and unrelated local work.

1. Savings: F1 producer arithmetic, F2 monthly durable rejection, F3 visible stale
   outcome, F7 actual coordinator assertion, F10 defensive safe-pool validation.
2. Dashboard F4: verify budget denomination/provenance before money decisions.
3. Email F5: unknown/unsupported source remains unresolved and routes to review.
4. Smart savings F6: normalized complete percentile input; no mixed raw currency cap.
5. F8: require explicit ledger currency in Kotlin; document retained SQL default
   compatibility if no schema change is needed. F9: ignore future Codex transcripts;
   preserve existing history pending an explicit history-retention decision.
6. Record G1 authority context and exact existing contracts; no new exceptions,
   baseline growth or guard weakening. Fresh compile, targeted filters, affected
   shards, app-check and full static guards through validation-runner only.

Each bounded code batch receives local strict review before serialized focused
validation. Local review is not independent acceptance. No finalization claim.

## G1 authorization evidence

The user instruction authorizes remediation, but does not verbatim confirm the
five cancellation removals and four exact writer grants listed below. G1 remains
OPEN pending the human-owner confirmation requested by the independent reviewer.
The prior handoff asserts approval; that assertion is not a substitute for the
missing specific human confirmation. No new policy exceptions or baseline growth
have been introduced by this remediation.

Cancellation removals (each count 1):
- G-CANCEL-01 domain/health/FinancialHealthScoreV2.kt
- G-CANCEL-01 domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- G-CANCEL-01 domain/usecase/savings/MonthlySavingsSweepUseCase.kt
- G-CANCEL-02 domain/usecase/savings/MonthlySavingsSweepUseCase.kt
- G-CANCEL-02 domain/forecasting/HistoricalSpendingDistribution.kt

Paths above are beneath app/src/main/java/com/yourname/expensetracker/.
Existing MIT-DB-W5-R16 grants are restricted to
SavingsSweepAcceptanceCoordinator.accept(SavingsSweepRecommendation):
- contributionLedgerDao.insert
- savingsGoalDao.addToGoalAmountIfSnapshot
- savingsSweepPlanDao.insert
- savingsSweepPlanDao.acceptPendingPlan

Reference FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23.

## Validation / review

Validation results are snapshot-bound and listed below. Independent re-review of
this remediation is NOT RUN. The original independent verdict remains FAIL.

## Savings batch evidence (before dashboard edits)

Local strict review: PASS for F1/F2/F3/F7/F10 implementation. Independent
re-review remains NOT RUN. No schema or policy changes in this batch.
F1 producer steps allocation down until exact persistence cap holds; bounded
roundoff is checked separately from exact no-overshoot and committed balance.
F2 checks accepted month plans plus indexed ledger timestamps in the same Room
transaction; same-month recomputation after plan cleanup is rejected, next month
is accepted, and identical ledger-authorized retry remains idempotent.
F3 invalidates stale cards with a controlled reason, including collector races.
F7 asserts the actual coordinator is not called. F10 rejects unsafe input pools.

At fingerprint 24b025d6fc1c2c49ac4d2397e24003692ad38301f38e4d031eec3f36b0c09631:
- vr-20261013-144731-ce9ee0c8: SavingsGoalsViewModelTest PASS/0 (33 tests).
- vr-20261013-145248-433b9631: MonthlySavingsSweepUseCaseTest PASS/0.
- vr-20261013-145315-0cd84191: SavingsContributionHistoryRepositoryTest PASS/0.
- vr-20261013-145343-656301f3: SavingsSweepAcceptanceCoordinatorTest PASS/0 (19 tests).
Physical markers and matching start/end fingerprints inspected. Tasks actually
executed. Exact commands/logs remain under build/validation-runs/<run-id>/.
Earlier failed runs are retained: missing fixture effectiveLimit; decimal test
incorrectly required exact completion; collector clearing visible stale reason.
The corrected runs do not erase those failures. These become historical after
subsequent code/documentation changes. Final broad validation still pending.

## F6/F8/F9 remediation evidence

F6 is implemented: SmartSavingsEngine now normalizes the 90-day purchase history
through AnalyticsCurrencyNormalizer, requires complete home-currency quality, and
calculates the adaptive P90 only from normalized amounts. Sparse normalized input
uses the existing conservative minimum; raw currency-mixed DAO values are never
used for the cap. SpendingThresholdCalculator has a pure normalized-input helper.

F8 is implemented without a schema change: SavingsContributionLedgerEntry requires
an explicit Kotlin currency argument. The v149->150 SQL default remains only for
existing-schema compatibility, and the sole production writer already supplies
recommendation.currency.

F9 hygiene is partially addressed: future codex-session files are ignored. The
already tracked transcript remains in history pending an explicit retention or
history-rewrite decision; no force-push or history rewrite was performed.

Focused evidence at fingerprint 4804d395c52d88c5dc1d71457f9c670e7a66f329d6ff6ac871db08b208085dcc:
- vr-20261013-171307-3fe4b976: SpendingThresholdCalculatorTest PASS/0.
- vr-20261013-171800-1fe4b6a4: SmartSavingsEngineTest PASS/0.

The first validation attempts exposed and resolved environment/tooling and compile
issues: Android Studio JBR was OpenJDK 25.0.3 and failed this project; JDK 21 at
C:\Users\panos\.jdks\jdk-21.0.8+9 is now used. Earlier failed runs remain
historical and are not counted as passing evidence.

## Broad validation and strengthened F6 coverage

At fingerprint 9efb9075d3fe611032783dc495f8cdade26baab6be5160dc579c8af1873b2c37:
- vr-20261013-172213-d85ce098: compile PASS/0, Kotlin compilation executed.
- vr-20261013-172457-7dcef7d6: SavingsSweepAcceptanceCoordinatorTest PASS/0.
- vr-20261013-172733-eaf7b32f: SavingsGoalsViewModelTest PASS/0.
- vr-20261013-172825-a988e82c: MonthlySavingsSweepUseCaseTest PASS/0.
- vr-20261013-172909-5cc304e7: ReceiptLifecycleCoordinatorTest PASS/0.
- vr-20261013-173000-ce482191: ComputeDashboardWidgetsUseCasePaceWiringTest PASS/0.
- vr-20261013-173104-96c24ed0: SavingsContributionHistoryRepositoryTest PASS/0.
- vr-20261013-175038-7aeba8a5: app-check PASS/0; debug and release unit tasks
  actually executed; lint executed. Configuration-cache warnings and Python alias
  probes did not fail the build; 25 tasks executed / 62 up-to-date.
- vr-20261013-182016-6f04e21a: static-guards PASS/0, 25/25 guards, no blocking
  violations or infrastructure errors; guard_tests 4750 passed / 28 pre-existing
  skips. Physical completion marker inspected.

vr-20261013-173237-06bd31ca remains FAIL/1 (24/25 guards passed): only the
test-result freshness stamp had SHA drift. After the completed app-check, the
documented stamp writer recorded current HEAD/tree; the full suite rerun above
passed. No guard, baseline, allowlist or exception was relaxed. The stamp is
not proof of an uncommitted candidate: durable runner fingerprints remain the
source of truth for these worktree results.

The broad runs predate the following test-only strengthening and G1 wording
correction; production code is unchanged. vr-20261013-184510-b8b416a8:
SmartSavingsEngineTest PASS/0, 22 tests, at fingerprint
d6d790b48a0ba34619aa1afa3f6b289caa87f3b2973e019e96f955100518c5a2. The two
new tests use real AnalyticsCurrencyNormalizer and SpendingThresholdCalculator,
not a mocked cap: EUR/USD/GBP home currencies with JPY inputs, sparse minimum,
normalized P90, missing rate and stale rate isolated to the cap history. They
assert controlled unavailable outcomes, quality metadata, and zero raw DAO
percentile calls.

The affected serial shards all PASS/0 at fingerprint
fe2556b05a0d2e8e33ae609bd8402a197f35b3037ce4388e06ae42fef43c2acb:
- vr-20261013-185002-9e7d302b: data.
- vr-20261013-185512-e59c6d1f: domain-a-m.
- vr-20261013-185604-39c9f400: domain-n-z.
- vr-20261013-185725-ddd27a36: runtime-ui.
All four actually executed their test task (1 executed / 34 up-to-date), with
physical PASS markers and matching start/end fingerprints inspected.

## Historical v150 candidate boundary and acceptance limits

HEAD/base is 3e8377e42083c92990f32f4e46fdcb481c29cc3a, branch bug-fixes.
This remediation is UNCOMMITTED: there is no final remediation source commit
yet. Its explicit path/hash manifest and final automated results are recorded
in build/validation-runs/wave5-20261013-remediation-final-evidence.json.
The evidence packet is local/ignored, not a substitute for a recorded source
commit. Commit/push has not been performed in this remediation continuation.

Candidate paths are only the Wave 5 app source/tests, .gitignore, this handoff
and the independent-review packet. Unrelated .codex/config.toml, .claude/,
CLAUDE.md, docs/prompts/release-1.0-assurance/ and the release-assurance handoff
are untouched and excluded, not silently part of the candidate. The manifest
enumerates every included/excluded dirty path and hashes included file bytes.
No schema, migration, index, policy, baseline or allowlist changed. F8 retains
the reviewed v150 SQL default; it is a documented compatibility limitation,
not a claim that all implicit EUR defaults were removed. Existing device
migration evidence remains historical; a new connected run is not required
by this review when schema/indexes are unchanged.

Final compile/app-check/static-guards runs are pending at this document write.
Their durable results, commands, markers and fingerprints will be copied to
the evidence packet only after each actually finishes; never infer a PASS
from this planned sequence. No source/test edits are planned after freezing.

Earlier local architecture/privacy/Room/strict review passed the normal/retry
paths, but the final source trace reopens F2 and supersedes the local PASS with
FAIL. This is implementer review, NOT an independent acceptance verdict.
Original independent review remains FAIL;
remediation re-review by a different agent/session is NOT RUN. G1 explicit
owner confirmation remains OPEN (the exact five removals/four writer grants
above); F9 history-retention decision remains OPEN and no history rewrite was
authorized. Wave 5 is NOT finalized. Finalization additionally needs an
authorized source commit, candidate-bound validation trace, independent
re-review, and written G1 confirmation.

## Final automation result and reopened F2 blocker

At frozen fingerprint 65a190b6e9a2c04c0aa08c4085fd94f54e4efcc0c047651fe441c8fa6e5aa62f:
- vr-20261013-190438-6b4592b2: compile PASS/0.
- vr-20261013-190718-60b4f736: app-check PASS/0; debug/release units executed,
  BUILD SUCCESSFUL in 26m48s.
- vr-20261013-193413-babdaf7b: static-guards PASS/0, 25/25 guards; guard_tests
  4750 passed / 28 pre-existing skips. Run-specific guard summary, known-good-state
  log and guard-test log are copied beneath that run's directory.
All physical completion markers and matching fingerprints were inspected.
The 28 included path hashes matched; the 33 unrelated paths were excluded.
This final documentation-only finding update occurs AFTER those runs, and is
explicitly recorded as a post-validation documentation delta in the evidence
JSON. No production/test/schema/policy bytes were changed after the freeze.

VERDICT: FAIL (source trace; the additional deletion regression is NOT RUN).

F2-GOAL-DELETE - HIGH - Monthly sweep acceptance evidence is goal-owned and can
be erased by a legal goal deletion:
- SavingsGoalRepository.kt:35-37 calls SavingsGoalDao.deleteGoal (Room @Delete).
- SavingsSweepPlan.kt:30 and SavingsContributionLedgerEntry.kt:28 both declare
  goalId foreign keys with ON DELETE CASCADE.
- SavingsSweepAcceptanceCoordinator.kt:61-63 only consults accepted plans and
  the contribution ledger; line 92 permits new acceptance when both are absent.
- SavingsGoalsViewModel.deleteGoal exposes the repository deletion normally.
All paths above are beneath app/src/main/java/com/yourname/expensetracker/.

Trace: accept a single-goal sweep -> delete that goal -> both plan and ledger
rows cascade away -> create a new explicit home-currency goal -> recompute in
the same month. Both monthly checks are now false and a fresh identity reaches
the normal acceptance writes. The budget-derived safe pool is unchanged.
The existing regression deletes optional plans but keeps the goal/ledger; it
does not cover this path. Therefore F2 is only PARTIALLY remediated, even though
all executed tests and guards pass.

The no-schema plan is insufficient for this path. Recommended delta: add a
goal-independent durable month acceptance marker, written atomically by the
same coordinator, with an additive versioned migration, exact backup/restore
classification and validation, and an explicitly approved exact DAO writer
grant. Add accept -> delete all accepted goals -> create -> recompute/reaccept
regressions, plus next-month, retry and migration preservation cases. Decide
migration behavior when v150 historical acceptance rows have already been
deleted; do not silently assume absence proves the month was never accepted.

STOP: the imported plan forbids unexpected schema/policy broadening without
owner review. No schema, writer-policy or deletion-behavior change was made to
hide this defect. Human approval of the delta is required before implementing.
Do not commit/finalize this as an accepted Wave 5 candidate. Independent
re-review, G1 confirmation and a final source commit also remain outstanding.

## Approved F2 goal-independent marker batch

Human approval on 2026-10-13: assistant asked permission for a goal-independent
monthly acceptance marker, schema/migration, backup tests and a narrowly scoped
writer grant; the user replied verbatim: "yes i approve". This approves this
delta only, not history rewriting, committing/pushing, or blanket writer access.
G1's earlier cancellation removals/four grants still need their separate explicit
confirmation. No subagents will be used.

Bounded strict plan:
1. Demonstrate accept -> delete accepted goal -> recreate -> reaccept with a
   failing coordinator regression against the v150 candidate.
2. Add v151 savings_sweep_month_acceptance with no goal FK and no money/currency
   defaults; the coordinator inserts one month marker in its existing atomic
   Room transaction. Add one exact marker-DAO insert grant to accept only.
3. Upgrade/old-backup policy: consume the migration month conservatively because
   deleted v150 history cannot be reconstructed. Use TimeProvider for the clock,
   preserve all existing rows, and seed exactly one controlled migration marker.
   Fresh installs start with no consumed month; the next month remains available.
4. Require exact-tier backup counts for v151, and exact one-marker expectations
   after restoring older supported versions. Add migration, restore, deletion,
   idempotency, next-month and rollback/cancellation tests.
5. Perform local strict/architecture/privacy/Room review, then serialized runner
   compile, focused tests, affected shards, app-check, full guards and connected
   migration validation. Independent re-review remains a separate pending gate.

Prior automated evidence is historical once this batch changes source. Marker
implementation is present, but post-fix validation is pending. The pre-fix
regression vr-20261013-200033-b6c2429d FAILed as expected: 19 passed, one
deletion/recreation regression failed. Post-fix compile vr-20261013-201911-1fa51919
was STALE_RESULT (Room generated 151.json during compilation), not PASS. The
export adds one table and changes no existing table. Fresh stable validation
is required. No finalization claim is made here.

### Post-fix marker review and focused evidence

F2-GOAL-DELETE is implemented and the previously failing regression now passes.
Local strict/architecture/privacy/Room review: PASS for this approved marker
batch; independent re-review remains NOT RUN. No new blanket permissions,
baseline growth, guard relaxation or deletion restrictions were introduced.
The only active-policy delta is the approved accept -> monthAcceptanceDao.insert
contract, pinned with the other four exact contracts (428 -> 429 entries).
Reference FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23.

The marker has no goal FK or cleanup writer and contains no financial amount or
currency. Its half-open month bounds are checked in the same Room transaction
as all goal CAS, ledger and plan writes. Failed marker insertion or cancellation
rolls back the complete operation. Identical ledger-authorized retries do not
create a second marker; a new identity in a consumed month is rejected.

At fingerprint 40974a1e32dc4d927f4a92fb11c45a35643c79c99f5fc56bc51522ef70181c86:
- vr-20261013-203637-5da1982a: SavingsSweepAcceptanceCoordinatorTest PASS/0.
- vr-20261013-204245-c943882b: SavingsSweepMonthMigrationTest PASS/0.
- vr-20261013-204320-32a7cd46: BackupVerifierManifestTest PASS/0.
- vr-20261013-204352-57c2c2af: BackupVerifierSavingsLedgerTest PASS/0.
All test tasks executed and physical completion markers/matching fingerprints
were checked. The first test includes goal deletion/recreation, next month,
marker conflict rollback and cancellation after marker insertion. Restore tests
include pre-v150 and v150 upgrades, v151 roundtrip and a marker surviving deletion
of every accepted goal. Migration tests preserve goals/plans/ledger and consume
exactly one upgrade month even with absent legacy history. Fresh schema 151.json
adds exactly one table and changes no existing tables.

Policy artifact regeneration used the unchanged reviewed seed inputs: candidate
permission bytes are identical; accounting evidence was regenerated for the new
source-file inventory. Generator exit 1 reports the existing 93/55/38 legacy
fold debt, not a validation PASS. The registered artifact verifier must pass.

Broader v151 validation is PENDING at this freeze. Final results/commands/logs
and the explicit included/excluded path hashes are to be recorded in
build/validation-runs/wave5-20261013-month-marker-evidence.json. This ignored
local packet is not a source commit. Independent acceptance, G1 confirmation,
and a final authorized source commit remain required; Wave 5 is NOT finalized.

Stable compile vr-20261013-204759-70b5af19: PASS/0, fingerprint
71c0fab976d074e080cbefa213a28ee7bac657e342f09e9a48ddfee3e667c1ec;
task was UP-TO-DATE after the earlier successful-but-stale schema export.
This documentation update follows that run. No production/test bytes changed.
The final frozen validation results and candidate hashes will be recorded in
the evidence packet above, avoiding assumed results or silent snapshot drift.

## Continuation - 2026-10-14: count pins and validation isolation

These results supersede the pending broad-validation statements above, but do
not supersede the original independent FAIL or the open acceptance gates.
At fingerprint 915496dd5db2bafff3be81ea486553070c22f0cae79e0e890e39446695ea3e69:
- vr-20261013-205005-e4acc4f5: migration-tests PASS/0.
- vr-20261013-205129-e460210f: connected-tests PASS/0, API 35; 360 tests,
  zero failures, nine skips. This covers the actual v150-to-v151 device migration.
- vr-20261013-211551-6509483e: compile PASS/0.
- vr-20261013-211607-64b504a3: data shard PASS/0.
- vr-20261013-212006-dedcc874: domain-a-m shard PASS/0.
- vr-20261013-212108-1ec1c041: domain-n-z shard PASS/0.
- vr-20261013-212241-18f2851a: runtime-ui shard PASS/0.
- vr-20261013-212349-b5505ba9: app-check FAIL/1: debug units reported
  7,918 tests, two failures, 276 skips. Release units were not reached.
  DbGuardPolicyFixtureTest expected 428 ownership entries but the approved exact
  marker-insert grant makes 429; BackupRestoreContractTest expected 58 backup
  tables but the new exact-tier marker makes 59. Both fixed-count assertions,
  test names and associated comments now match the approved source delta.
  No assertion was removed, derived from the value under test, or relaxed.

Focused retries after these test-only corrections did not execute tests:
- vr-20261014-042459-13fdfc2a: FAIL/1, generated class was not a regular file.
- vr-20261014-043954-ef74c7d3: FAIL/1, unit-test output cleanup failed.
- vr-20261014-045513-29fbe951: FAIL/1, unit-test output cleanup failed.
OneDrive cloud reparse attributes were verified on the generated output tree.
These are failed runs, not passing validation evidence. Original build outputs
and unrelated user work will not be deleted or moved to work around the issue.

Next bounded step: clone the recorded base into an isolated directory outside
OneDrive, overlay only the explicitly enumerated Wave 5 candidate files, verify
their byte hashes against this workspace, and run serialized validation through
that checkout's unchanged validation-runner. Do not copy generated app outputs.
Record clone provenance, included/excluded paths, hashes, runner results and
durable logs in build/validation-runs/wave5-20261014-isolated-evidence.json.
Isolated fingerprints differ from the mixed original workspace; do not claim
they validate unrelated excluded work or silently overwrite earlier evidence.

Current candidate remains UNCOMMITTED at base 3e8377e42083c92990f32f4e46fdcb481c29cc3a.
There are no production/schema/policy changes since the passing device run;
only the two test-count fixes and this documented handoff delta follow it.
Independent re-review, G1 owner confirmation, F9 history disposition, a final
authorized source commit and remaining broad gates are still outstanding.
Wave 5 is NOT finalized. No subagents, commit, push or history rewrite authorized
in this continuation.

## Current workspace and isolated results - 2026-10-14

Active workspace is now C:/Users/panos/Desktop/Cost-agregator. HEAD/base remains
3e8377e42083c92990f32f4e46fdcb481c29cc3a. Do not resume from the old OneDrive
directory as though it were the complete working tree. No subagents.

The isolated candidate at
C:/Users/panos/codex-validation/Cost-agregator-wave5-v151-20261014-085459
was cloned from that exact base without hardlinks and overlaid with 51
byte-verified Wave 5 paths, excluding the 33 unrelated paths then present.
At isolated fingerprint
17cd60e40c842e8782dd7009035621c6eb98ea527fffe16a5ae0b35c9408cbe2:
- vr-20261014-055528-640b125c: policy fixture tests PASS/0, 73 tests.
- vr-20261014-060239-d8c4c978: backup/restore filter PASS/0, 23 tests.
- vr-20261014-060321-4fd46210: compile PASS/0.
- vr-20261014-060343-17eb555a: migration profile PASS/0, 14 tests.
- vr-20261014-060425-6edb562d: app-check FAIL/1 after 29m06s.
  Debug units: 7,918 tests, zero failures/errors, 276 skips.
  Release units: 7,918 tests, zero failures/errors, 277 skips.
  Failure: verifyDbAccessBoundaries reported one new structural key with two
  execSQL occurrences in SavingsSweepMonthMigration.migrate. Neither the
  passing unit suites nor the completed lint output makes app-check PASS.
Completion markers and matching start/end fingerprints were verified; results,
logs and JUnit XML snapshots were archived under
build/validation-runs/isolated-20261014/<run-id>/ and recorded in
build/validation-runs/wave5-20261014-isolated-evidence.json.
Static guards were NOT RUN after this failure. No freshness stamp was written
to represent a passing app-check.

Migration correction under consideration: keep SQL in the existing canonical
DatabaseMigrations.kt MIGRATION_N_N anonymous-object path authorized by
config/guards/db_structural_exceptions.yml, preserving the injected clock seam
and exactly the same migration semantics. Do not add an unapproved structural
exception, baseline entry or blanket grant. Reference
FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23.
This correction has NOT been implemented or validated.

### Directory-move audit and separate performance work

Read-only inspection of the moved checkout found 744 tracked paths missing.
All 744 still exist and are readable in the old OneDrive directory. Of the 51
Wave 5 candidate paths, 37 exist here and match the frozen byte hashes; 14 are
missing, including two untracked marker files. All 14 missing candidate files
also match the recorded hashes at the old location. The isolated checkout
retains all 51 matching candidate files. Nothing has been restored, deleted,
reset or overwritten as part of this audit. Whether other missing paths were
intentional deletions has not been assumed.

Existing drawable ic_dismiss.xml still has a cloud reparse tag after the move.
The performance continuation's default compile therefore failed before Kotlin
compilation. Moving the directory did not by itself establish that all files
are materialized as regular local files. Do not strip reparse metadata, reset
the tree or restore all paths as an automatic shortcut.
Audit: build/validation-runs/wave5-20261014-directory-move-audit.json, with
candidate comparisons, missing-path inventory and readable old-file hashes.
This handoff update is a post-validation documentation delta, not an alteration
of the historic candidate manifest or a new passing fingerprint.

User-reported performance work is separate from Wave 5:
- gradle.properties
- scripts/validation-runner.ps1
- scripts/ci/test_validation_runner_protocol.py
- docs/development/VALIDATION_RUNNER.md
- docs/development/BUILD_GUIDE.md
- config/validation/test-shards.json
- workflows/active/local-build-performance-20261014-handoff.md
Its saved protocol and smoke PASS results were verified against completion
markers and unchanged fingerprints: vr-20261014-080714-f0733237 (194 protocol
tests) and vr-20261014-081214-b314e7b2.
vr-20261014-081520-c25e602e remains compile FAIL/1 at mapDebugSourceSetPaths.
No app-level speedup or tuned-test validation is claimed. Preserve this work;
do not silently include it or the missing-path delta in the Wave 5 candidate.

Next steps: obtain confirmation before restoring missing paths; preserve any
intentional deletions and all uncommitted bytes. Resolve file materialization
without destructive cleanup. Then implement and strictly review the bounded
canonical migration-path correction, regenerate source-inventory accounting
if required without changing permission scope, and run focused migration/DB
guard checks before app-check and full static guards through the runner.
Independent re-review, G1 confirmation, F9 disposition and an authorized final
source commit remain pending. Wave 5 is NOT finalized.

## Recovery, canonical migration and current-tree gates - 2026-10-14

After the user authorized necessary recovery, all 744 missing tracked files and
two missing untracked marker files were restored as regular local files from
the old copy, each byte-verified against the previously verified isolated
checkout. None of the existing edited files was overwritten. An additional
1,702 tracked app, config, Gradle and guard inputs with cloud reparse metadata
were rematerialized byte-for-byte; their originals are preserved under
build/validation-runs/file-materialization-20261014-125648. SHA-256 inventories
and 78 pre-existing dirty-file hashes are under
build/validation-runs/wave5-20261014-file-recovery.json. No tracked deletions
remain. Four intentional Wave 5 edits were made after that snapshot; all 74
other pre-existing dirty files retain their hashes. The performance patch and
the remaining unrelated files are not part of the Wave 5 candidate.

The standalone v151 migration's two execSQL calls originally failed the DB
boundary guard. An intermediate nested-object approach failed closed with
DB_STRUCTURAL_SCOPE_UNSUPPORTED (vr-20261014-100336-30b51f4a FAIL/2 and
known-good diagnostic run vr-20261014-101255-c055c4e9 FAIL/2). Final code
delegates from SavingsSweepMonthMigration to the version-bounded
DatabaseMigrations.MIGRATION_150_151(database, timeProvider) function. The SQL,
clock read at migrate-time, exact one-month legacy marker and data preservation
are unchanged; an explicit unavailable-clock regression was added. No guard
policy, baseline or allowlist was widened. BackupVerifier's stale 58-table
comment was aligned with the enforced 59-table count.

At current *mixed* worktree fingerprint
6e6b57f5e18d64ac9c88d4a1cc152d22b41224aa203a67304e93a43954f66cc0:
- vr-20261014-102027-6244bcda: registered db_access PASS/0, zero findings.
- vr-20261014-102859-8597dce5: compile PASS/0.
- vr-20261014-103203-26d97ad6: SavingsSweepMonthMigrationTest PASS/0.
- vr-20261014-103709-5f663b7d: migration-tests PASS/0.
- vr-20261014-103949-5baf47d6: app-check PASS/0; debug and release each
  executed 7,920 tests, zero failures/errors (276/277 skips respectively).
- vr-20261014-110804-241bbe92: full static guards FAIL/1, 24/25 passed;
  only known_good_state failed: XML files were newer than the old test-result
  freshness stamp. The failed summary was archived under that run directory.
- After the real app-check PASS, the documented stamp writer recorded its
  completion; the old and new stamps and both app-check XML suites are archived
  under their run directories. No test result was invented or guard weakened.
- vr-20261014-112957-42996321: full static guards PASS/0, 25/25, zero blocking,
  warning or infrastructure errors; guard_tests 4,931 PASS, 28 skips.
- vr-20261014-115605-a6546ac8: connectedDebugAndroidTest PASS/0 on API 35
  wave5-api35; all 369 reported tests finished with nine skips and zero
  failures; the v150-to-v151 migration case was included.
For each PASS above, result.json has exit 0, a physical PASS completion marker,
and equal start/end fingerprints. A prior failure remains historical, not PASS.
Logs and markers are under build/validation-runs/<run-id>/.

The mixed runner fingerprint also covers the user's separate performance patch
and other unrelated local files. Thus these passing runs are valuable current
implementation evidence but are NOT proof of a *candidate-only* snapshot.
Next: create a clean clone of base 3e8377e42083c92990f32f4e46fdcb481c29cc3a,
overlay and hash-verify only the 51 Wave 5 paths, record the excluded paths,
then run the required serialized gates on that isolated candidate. Preserve
the original independent FAIL document unchanged. Independent re-review of
these fixes is NOT RUN, G1 specific confirmation remains OPEN, F9 committed
transcript disposition remains OPEN, and no final source commit is authorized
by this continuation. Wave 5 is NOT finalized.

## Candidate-only automation and acceptance boundary - 2026-10-14

The candidate-only checkout at
C:/Users/panos/codex-validation/Cost-agregator-wave5-candidate-20261014-20261014-151834
is a no-hardlinks clone of base 3e8377e42083c92990f32f4e46fdcb481c29cc3a
with exactly 51 Wave 5 paths overlaid. Every overlaid file was SHA-256 matched
to the active workspace before and after validation. Exactly 41 other dirty
paths, including all seven performance-tuning paths, were excluded. No
generated build files were copied. Manifest, exclusions and result records:
build/validation-runs/wave5-20261014-candidate-only-evidence.json.

All six serialized runs below completed PASS/0 with a physical completion
marker and equal start/end fingerprint
648e34bdbe6ebbc2ab71f7f38bcd29e3d2a6c635c14a9ad15ee3572d9d029165:
- vr-20261014-122341-505e3cda: registered db_access guard PASS, zero findings.
- vr-20261014-122625-ff4f70e1: compile PASS.
- vr-20261014-122727-e694b2f3: migration-tests PASS.
- vr-20261014-122950-8e76f251: app-check PASS; both debug and release unit
  suites ran 7,920 tests with zero failures/errors (276 and 277 skips).
- vr-20261014-125555-ffee054e: static-guards PASS, 25/25; guard_tests
  4,750 passed and 28 skipped. The freshness stamp was written only after
  the real passing candidate-only app-check.
- vr-20261014-131128-f6ab038e: connected-tests PASS on API 35; connected
  XML records 360 tests, zero failures/errors and nine skips. The v150-to-v151
  goal-independent marker migration case is present and passed. Gradle's
  progress text says 369 finished; use the XML as the test count.

Each run's result/marker/log/request files were byte-verified into a distinct
build/validation-runs/candidate-only-20261014/<run-id>/ directory. Both complete
unit JUnit XML suites, the full guard report/logs and the connected results XML
are archived with the relevant run. The optional raw Android test output copy
is partial due to Windows path length and must not be counted as a complete
raw-device-output archive; its authoritative XML, result and stdout are intact.
The original independent review file's bytes remain unchanged.

Local strict/architecture/privacy/Room review: PASS on this candidate as
implementer assessment. The v151 table is additive with no goal FK, preserves
existing rows, consumes the unknown upgrade month exactly once, and has
fail-closed clock tests. The coordinator writes its marker after plan/ledger/
balance writes within the existing Room transaction; conflicts and cancellation
roll back, and matching retries are idempotent. Tier-1 backup/restore and
pre-v151 exact count expectations are covered. The only newly approved DAO
identity is accept -> monthAcceptanceDao.insert (428 -> 429); no structural
exception, baseline or allowlist changed. The guard source-evidence, schema
matrix, privacy and money checks passed in the full guard suite. This is NOT
an independent re-review or human-owner sign-off.

Acceptance remains PARTIAL: this remediation has no final source commit,
independent re-review is NOT RUN (the preserved original verdict is FAIL),
specific G1 confirmation of five cancellation removals and four earlier writer
grants is OPEN, and F9's committed-transcript history disposition is OPEN.
No commit, push or history rewrite was performed. Wave 5 is NOT finalized.

## Owner review disposition - 2026-10-14

The user stated: "the I dependent review is fine consider it passing".
Record this as owner acceptance/waiver of a separate remediation re-review,
not as an independently executed PASS. No independent reviewer has re-reviewed
the remediation; the original independent FAIL report remains unchanged.
The local strict review and candidate-only validation above remain the actual
review/test evidence. This owner disposition does not confirm G1's five prior
cancellation removals/four existing writer grants, decide F9's committed
transcript retention, or authorize a new source commit/push. These remain open.
Only this documentation changed after the frozen candidate automation.

## Owner approval and authorized publication - 2026-10-14

The user stated: "I approve both and I want the wave to be committed pushed and
considered closed". This closes the two decisions requested in the preceding
summary:
- G1: owner confirms the five specific cancellation removals and the four
  earlier exact SavingsSweepAcceptanceCoordinator writer grants enumerated in
  the G1 authorization-evidence section. No additional grants are authorized.
- F9: retain the already committed transcript in Git history. Future transcripts
  remain ignored. No history rewrite, force-push or transcript removal is
  authorized or required for this disposition.
The separate remediation re-review remains NOT RUN, with the explicit owner
waiver above satisfying the acceptance decision. The original FAIL report
remains preserved as historical evidence, not rewritten to PASS.

Publication is authorized for the 51-file Wave 5 candidate only. All 41 dirty
excluded paths, including the performance patch, must remain outside the
commits. Validate committed blobs against the candidate manifest (Git's normal
line-ending normalization applies), record the source commit, then verify a
normal push to origin/bug-fixes. Record closure only after publication is
verified. Earlier OPEN/PARTIAL statements in this chronological record are
superseded by this owner decision and the subsequent closure entry. No source,
test, schema, guard or policy bytes changed after the candidate-only PASS runs;
only handoff/approval documentation follows those runs.

## Final closure and published source - 2026-10-14

Wave 5 is CLOSED under explicit owner acceptance and the documented independent
re-review waiver. All required implementation/automation gates are satisfied;
no mandatory Wave 5 gate remains open. The original independent FAIL is
preserved, not misrepresented as an independently executed PASS. G1's exact
five cancellation removals/four earlier writer grants are owner-confirmed; F9
is resolved by retaining the already committed transcript, with future
transcripts ignored. No force-push or history rewrite occurred.

Published implementation source:
ea44631647e69a0dabacdc179dd094b292378b7e
(`fix(money): complete Wave 5 review remediation and v151 sweep safety`).
Parent/base: 3e8377e42083c92990f32f4e46fdcb481c29cc3a.
Normal push to origin/bug-fixes returned exit 0; git ls-remote independently
confirmed this exact SHA. Exactly 51 Wave 5 paths were committed, each committed
blob matching the staged preflight manifest. Implementation, test, schema and
policy blobs are matched to the clean validated candidate under Git's normal
line-ending normalization. Only handoff/approval documentation changed after
the six candidate-only PASS runs; tests were not rerun on the commit ID itself.

The completion documentation is published separately from the source commit.
Its purpose is to record the source SHA, verified push, acceptance decisions and
closed status; it changes no app/test/schema/policy files. All 41 excluded local
paths, including the performance patch, remain outside the source and closure
commits and retain their recorded precommit hashes. Unrelated dirty work is
deliberately preserved, so the overall workspace is not claimed clean.

Evidence: build/validation-runs/wave5-20261014-candidate-only-evidence.json,
with the frozen 51-path hashes, six PASS records, per-run archived logs/markers,
committed-blob crosswalk, excluded-file hashes and publication verification.
This packet and build logs are local/ignored; the tracked handoff records the
source SHA and run IDs for cross-session use. Closure does not claim a measured
performance speedup, a new independent review, or post-push remote CI success.
Earlier pending/OPEN statements are chronological history superseded here.
