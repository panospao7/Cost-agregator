# Wave 5 Batch 8 persistence continuation - 2026-10-12

Status: PARTIAL - implementation and automated gates passed at the frozen
2026-10-13 candidate; final independent acceptance remains NOT RUN. Wave 5
is NOT finalized. The final evidence section below supersedes older pending
validation notes, which remain historical. Resume from this packet and
money-w5-remaining-implementation-plan-20261009.md, not prior chat.

## Authority and scope

- Branch bug-fixes; revision 4137afdfb487b43e7d7f76a511b1618efa89dfb8.
- Continue locally without subagents under the imported-plan amendment.
  Local strict review is not independent reviewer/guardian acceptance.
- Preserve unrelated .claude/ and CLAUDE.md. Nothing staged or committed here.
- Batch 8 scope is Room-authoritative sweep acceptance, not manual/smart
  contributions. Earlier category/archive migration repairs remain in the diff.

## Implementation and review corrections

- Schema 150, additive 149-to-150 migration, ledger DAO/entity/schema snapshot,
  nullable unique plan acceptanceKey and conditional goal/plan updates.
- SavingsSweepAcceptanceCoordinator owns one Room transaction for multi-goal
  balance/plan/ledger commit. It validates provenance and snapshots, rejects
  malformed recommendations, rolls back on failures/cancellation, and uses
  durable ledger identity for retries including reopen and optional plan cleanup.
- ViewModel delegates authoritative sweep persistence, exposes controlled
  rejection, and rechecks home currency/rate after asynchronous recomputation.
  No authoritative sweep DataStore dual write.
- Legacy history reads preserve bytes; validation/retention apply to the returned
  view. History merges Room ledger events and validates their semantics before
  exposing them. Existing manual history writes are unchanged.
- Backup ledger is TIER_1_EXACT. Pre-150 completeness accepts its absence;
  post-migration verification requires exact zero. Initial staged verification
  uses original manifest counts, while migrated/live verification uses normalized
  counts. Tests cover real v149 migration and current ledger database copies.
  Added encrypted-bundle restore tests use the production repository constructor,
  real Room staging/live verification and file/journal operations, with peripheral
  privacy/maintenance/diagnostic collaborators controlled. Both restore tests pass.
- Four exact authorized writer grants were added to
  config/guards/db_ownership_policy.yml for accept(SavingsSweepRecommendation):
  contributionLedgerDao.insert, savingsGoalDao.addToGoalAmountIfSnapshot,
  savingsSweepPlanDao.insert, savingsSweepPlanDao.acceptPendingPlan. User
  continuation followed the exact approval request. No broader exception or
  baseline growth. Reference FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/06/07/23.
- Existing authorized cancellation ratchet removes five resolved keys only;
  historical static result reports baseline/current 112, NEW 0, RESOLVED 0.
- Local review checked transaction/barrier, retries, schema/DI, backup verification
  call sites, source privacy, tests and policy changes. Corrected settings-recheck
  regression and ledger semantic validation. Independent acceptance NOT RUN.

## Historical validation (not final-tree acceptance)

All execution uses scripts/validation-runner.ps1, one live process at a time.
Evidence is build/validation-runs/<run ID>/{result.json,stdout.log,stderr.log,complete.marker}.

| Run ID | Profile/filter | Result |
| --- | --- | --- |
| vr-20261012-212500-e732e6ba | migration-tests | PASS/0 |
| vr-20261012-215159-4ba4a833 | compile | PASS/0 |
| vr-20261012-215444-f9a07db7 | acceptance coordinator, original 3 tests | PASS/0 |
| vr-20261012-215917-a773d185 | manifest, original 8 tests | PASS/0 |
| vr-20261012-220022-ac3aba2e | static-guards | FAIL/1; four unregistered writes |
| vr-20261013-050221-84b53a08 | compile | PASS/0 |
| vr-20261013-051027-2c15eb13 | expanded coordinator, 11 tests | FAIL/1; 10 passed |
| vr-20261013-052853-ffc0d521 | corrected coordinator, 11 tests | PASS/0; rollback and identity preserved |

The last failure was strict cancellation identity after Room context switching,
not swallowed cancellation. The runtime stack-trace recovery implementation
can copy exceptions. A non-copyable injected cancellation fixture now retains
the strict identity assertion plus all rollback assertions; the corrected rerun passed.
Start/end fingerprint of that failed run matched
b9c20dc9a942c9f20e089de484e41ef9ba2f24015c870c46b96f670d3c51b350.
Subsequent code/tests/docs changes make all preceding evidence historical.

## 2026-10-13 validation continuation

- At fingerprint bbbc10d4400a286dc0bb36e9b5f9b57c99f79e0ec8cc5bcb6364a671aa32ee29:
  coordinator check PASS/0 (Gradle up-to-date; actual 11-test execution in the
  preceding run), history PASS/0, BackupVerifier PASS/0, ViewModel PASS/0,
  migration PASS/0, compile PASS/0 (up-to-date), known_good_state PASS/0 with
  freshness row executed PASS. Exact runs are in the generated focused ledger.
- Full static suite vr-20261013-054110-df18973c FAIL/1: 24/25 guards pass,
  4744 Python tests pass, 28 skip and 2 fail. Both failures pin the old policy
  count 424 rather than the approved 428. Updated the two exact-count assertions
  and pinned all four coordinator contract identities. No guard/policy weakening.
- New connected 149-to-150 test assertions preserve seeded savings/accepted plan
  and verify no fabricated legacy ledger rows. Current connected execution pending.
- Full encrypted-bundle test iterations failed before any live swap with
  SQLiteCantOpenDatabaseException in the Windows Robolectric fixture. A short
  temporary ContextWrapper database/files/cache root fixes the path limitation
  while retaining all real Room and verification operations. No production
  restore workaround or relaxed assertion was introduced. All four ledger backup
  tests pass in vr-20261013-070201-def372bb, exit 0, with matching fingerprint
  c48d392b4f0bb35f961988bf0df9b7787bae7913066271e3d2904a5bb47bf9ce.
- Unrelated .codex/config.toml changed externally during continuation; preserve it.
  All preceding validation is historical after these test/documentation edits.
- First final queue at d2699cde60e9b4aae9321bbba183404cb76fe626a8861385af0ac080190f513f:
  compile PASS (up-to-date), migration PASS, data shard PASS (actual execution).
  Architecture-contracts FAIL: 414/416 pass. The Kotlin policy fixture also
  pinned 424; updated it to approved 428. SmartSavingsEngine had executable
  cancellation catches outside the guard's 200-character sibling window.
  Moved the existing cancellation rethrow into each broad catch, preserving
  identity/behavior and leaving the guard unchanged. Focused savings and
  architecture tests must pass before restarting the frozen broad queue.
- SmartSavingsEngine focused run vr-20261013-071813-b5ba6912 completed BUILD
  SUCCESSFUL, but runner verdict is STALE_RESULT/E_WORKTREE_CHANGED, not PASS.
  Unrelated docs/prompts/release-1.0-assurance appeared during execution.
  Preserve that external work and .codex/config.toml; restart final validation
  against the new snapshot. No final acceptance may combine stale snapshots.

## Frozen candidate validation and remaining gates

- Focused coordinator/history/BackupVerifier/ViewModel tests and migration profile
  must pass before proceeding. No test deletions or relaxed financial assertions.
- Generated queue ledger: build/validation-runs/wave5-20261013-focused-ledger.jsonl.
  It records terminal results; this packet does not predeclare their outcome.
- Test freshness metadata is written after test runs; known_good_state through
  the runner checks freshness before XML evidence is consumed.
- Required final checks: compile, full static guards, named serial unit shards
  including integration-golden, app-check, current connected upgrade/PDF gates.
  An emulator is available; earlier connected results are historical.
- Final broad runner ledger: build/validation-runs/wave5-20261013-final-ledger.jsonl.
  Required checks remain pending until its terminal records establish results.
- Independent final reviewer/guardian acceptance remains outstanding. Local
  self-review is not independent acceptance.
- Do not mark Wave 5 finalized or release-ready while required gates remain.
  Match terminal PASS/0, physical marker, logs and start/end/current fingerprints;
  stale, unknown, skipped-required and infrastructure results are never PASS.

## 2026-10-13 app-check correction and renewed freeze

- At fingerprint 1f5b8e78bb600f9286df152bc4f175ec1b12da7af2302aa0bfd4887af9de6213,
  compile, migrations and all six named unit shards passed with exit 0 and
  physical markers. The generated final ledger preserves their exact commands
  and logs. Architecture-contracts also passed in vr-20261013-073509-73eb1fbb.
- App-check vr-20261013-074529-ea2508f7 FAIL/1: 32 lint errors (one local SDK
  property escape and 31 untranslated resources), plus incompatible configuration
  caching of the five existing verification tasks. Python-backed time/raw-money
  guards executed and passed; Windows python3 alias warnings were not the failure.
- Corrected ignored local.properties SDK syntax, supplied Greek/Spanish resource
  translations, and marked the five project-dependent verification tasks as
  configuration-cache incompatible. Their actions, inputs, failure behavior and
  check dependencies remain unchanged. No lint baseline or guard exception growth.
- Local strict review of these corrections: PASS, no concrete issues. Independent
  acceptance remains NOT RUN. The changed files require renewed frozen validation;
  prior shard runs are historical and cannot be combined as final-tree acceptance.
- Renewed queue must run app-check, compile, migration, all six named unit shards,
  connected tests and full static guards, one at a time. Terminal evidence appends
  to build/validation-runs/wave5-20261013-final-ledger.jsonl; outcomes remain pending
  until the runner establishes PASS/0 with marker and matching fingerprints.

## Local review correction: financial allocation bounds

- The live conversation temporarily authorized a same-model review subagent.
  It returned FAIL for missing aggregate safe-sweep and per-goal target bounds,
  with scoped Room/migration and privacy source review PASS (validation pending).
  The user then explicitly instructed: do not use subagents in this session.
  The review agent is closed; all remaining work and review are local.
- Stopped the queue and the exact runner child for app-check
  vr-20261013-080046-a1f7a3b9 after this finding. Its terminal result is FAIL/1,
  E_COMMAND_FAILED, with matching d8870b863699ce687e943a47829e7717db35b632f87e08b9fca86204d25ba1e1
  fingerprints. Lint had completed and the full unit task was still running.
  This intentional interruption is not a passing gate or a unit-test assertion failure.
- Coordinator now sums validated same-currency allocations with BigDecimal,
  rejects non-finite/over-safe totals, and caps the resulting goal balance at its
  target inside the transaction. Residual sweep funds remain legal. The only
  aggregate tolerance is one Double ULP at the safe amount per allocation;
  there is no currency-unit or percentage allowance and no persistence rounding.
- Added five owner tests for aggregate excess, overflow, multi-goal target-cap
  rollback, valid residual/exact completion and floating-point tolerance bounds.
  No previous assertion was removed or weakened.
- Local strict re-review of the correction: PASS, no concrete issues. Independent
  re-review of the correction remains NOT RUN under the latest user instruction.
  Required focused and final frozen validation remains pending.

## App-check release-variant test repair

- At fingerprint 355b19c7ff70437986889b625900c0dfd8965dd313a664da01bfcbc1b5428372,
  focused coordinator (all 16 tests), compile, migrations and all six named shards
  passed with matching fingerprints/markers. App-check vr-20261013-083926-97907dc2
  FAIL/1 after debug tests passed: release console reports 7899 tests, 42 failed,
  277 skipped. No XML consumed or new skips introduced. Exact logs are in the ledger.
- Release failures: 12 migration/ledger-backup cases lacked schema assets; 29
  assertions assumed debug-only raw DB import/export, expense snapshots and demo
  bank APIs were enabled; one parser diagnostic fixture expected debug logs in
  release. Production release gates are correct and remain unchanged.
- Added schema JSON to test resources only and a test-only instrumentation asset
  adapter. Real Room MigrationTestHelper still parses schemas and validates actual
  SQLite migrations; no schemas added to release APK assets.
- Variant-aware test branches now assert release denial/no side effects rather
  than debug-only success. All debug assertions remain; encrypted restore/reset
  fault matrices remain active in release, and raw import denial is checked before
  each matrix. No ignored tests, skipped required checks, assertion relaxation,
  production guard change or lint-baseline growth.
- Task-specific configuration-cache incompatibility reports remain informational;
  the last build failed from testReleaseUnitTest, not those cache reports.
- Local strict review: PASS for this test-only repair, validation pending. Latest
  no-subagents instruction remains in force; independent re-review NOT RUN.
  Renewed focused and frozen gates must pass before any Wave 5 finalization claim.

- Schema loader/real encrypted restore focused debug run
  vr-20261013-093300-a7c5b90b PASS/0, all four cases, matching
  c5872701db3b0f7b9d2758c9ede8261ddd9f4c1cb843893948bacd969b7cfb00
  fingerprints and physical marker. This becomes historical after runner docs edits.
- Added targeted-release-unit-test to the durable runner's allowlisted profiles
  for focused diagnosis of release-only failures. It uses the same strict filter,
  worker/fork bounds, lock, timeout, logs, marker and fingerprint behavior as the
  debug profile. Duplicate-detection coverage now pins variant command separation.
  Updated the run-validation skill profile list. No generic command/override added.
- Local strict review of runner extension: PASS; protocol/static validation pending.

## Canonical ignored-test gate correction

- At fingerprint 17a08ae97af44a7433257704056709523d335b85fdc9972a5cd2c03b3785db5c,
  all six focused release filters, compile, migration profile and six serial unit
  shards passed. App-check vr-20261013-095857-e1030d8a FAIL/1, not a test assertion
  failure: both full debug/release unit tasks completed, then the inline ignored
  counter reported 32 against 29. It incorrectly counts six KDoc annotation mentions.
- Replaced only that legacy inline counter with the existing canonical registered
  ignored_test_budget Gradle bridge, preserving check wiring/fail-closed behavior.
  Registry budget 29, reasons and release denylist remain unchanged. No added skips,
  suppression, baseline growth or human waiver. Contract tests pin CI-mode,
  interpreter/preflight/exit behavior, no local override and the exact registry argv;
  a fixture distinguishes KDoc mentions from a real Ignore annotation.
- Added the narrow correction to docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md.
  Local strict self-protection review: PASS; reference FG-03/06/07/23. Validation
  pending; independent re-review remains NOT RUN with no subagents in this session.
- Next freeze: registered ignored_test_budget, compile, migrations, six shards,
  app-check, connected upgrade/PDF tests and full static guards. No result may be
  inferred from prior snapshots. The generated final ledger remains evidence truth.

## Final automated evidence audit - 2026-10-13

Validated snapshot: `9b3c78c8856b8e278d9819ca7c2e589753cef61ed1392c8300b4148156adcf4d`.
Branch `bug-fixes`, HEAD `4137afdfb487b43e7d7f76a511b1618efa89dfb8`.
All twelve results were reread with physical completion markers, nonempty logs,
PASS/0, and matching start/end/current fingerprints before this documentation
append. No active validation lock remained. All commands below were executed
through `scripts/validation-runner.ps1`, never directly.

| Run ID | Runner profile / scope | Result / execution |
| --- | --- | --- |
| vr-20261013-102452-6a1bf581 | registered-guard / ignored_test_budget | PASS/0; actual guard execution |
| vr-20261013-102605-6b2a6f38 | static-guards | PASS/0; all 25 guards executed |
| vr-20261013-104157-d01ca439 | app-check | PASS/0; lint and bridges executed; unit tasks up-to-date |
| vr-20261013-104818-b28377bf | compile | PASS/0; up-to-date |
| vr-20261013-104829-855605f1 | migration-tests | PASS/0; from cache |
| vr-20261013-104850-a73311e7 | unit-test-shard / architecture-contracts | PASS/0; from cache |
| vr-20261013-104911-491f2760 | unit-test-shard / data | PASS/0; from cache |
| vr-20261013-104931-ca7f4e97 | unit-test-shard / domain-a-m | PASS/0; from cache |
| vr-20261013-104952-ae5f22cd | unit-test-shard / domain-n-z | PASS/0; from cache |
| vr-20261013-105013-1aecb61c | unit-test-shard / runtime-ui | PASS/0; from cache |
| vr-20261013-105034-5a2eeee2 | unit-test-shard / integration-golden | PASS/0; from cache |
| vr-20261013-105055-c7852189 | connected-tests | PASS/0; actual API 35 device execution |

Exact commands, exit codes and log paths are preserved in each run's
`build/validation-runs/<run-id>/result.json`. The final ledger is
`build/validation-runs/wave5-20261013-final-ledger.jsonl`; filter by the validated
snapshot rather than combining earlier failed/stale runs. Machine-readable audit:
`build/validation-runs/wave5-20261013-closure-evidence.json` (generated, ignored).
It captures terminal records and SHA-256 hashes for 48 implementation/test/guard
files, plus the required connected HTML reports.

Static suite: 25/25 guards PASS, zero blocking violations, warnings or infra
errors; guard regression tests: 4750 passed, 28 existing skips. Canonical ignored
budget: 26 Ignore, zero Disabled, fixed budget 29, reasons/denylist PASS.
The known_good_state freshness row executed and passed in the static run.
App-check's full debug/release unit tasks were up-to-date from prior actual
execution; they were not freshly rerun at this final fingerprint. Migration and
named-shard results are cached, not newly executed test cases. Existing lint
baseline debt and configuration-cache incompatibility reports remain; neither
was suppressed by expanding a baseline or disabling verification.

Connected suite: zero failures, nine existing skips. Console totals are
inconsistent, so no exact total is asserted here. Required passing cases were
checked in HTML reports generated at 11:09:29Z, inside this run's interval:
- DatabaseMigrationMatrixTest: baseline_chain_to_current_version_passes;
  fresh_vs_migrated_table_parity_at_current_version;
  migrate_149_to_150_adds_savings_acceptance_ledger.
- AccountingExportPdfRepositoryInstrumentedTest:
  exportExpensesAccountantReportPdfWritesPdfOutput;
  mixedCurrencyReportSerializesAndRendersMultiplePagesWithoutFx.
None of these required cases was skipped. No test-result XML was consumed in
this audit; the timestamped HTML reports and runner records are the evidence.

## Final local review and closure limitation

Local strict review: PASS. Issues: None after the earlier financial-bound fixes.
Checked the uncommitted continuation, plan Batch 8, legal owner/transaction path,
write barrier, retry/rollback/cancellation, finite/aggregate/target bounds, schema
and DI, pre/current-schema exact backup verification, read-only legacy history,
ViewModel success/rejection behavior, release test gates and canonical guards.
Architecture/privacy/Room checks are local self-review, not independent guardian
acceptance. Reference `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/FG-06/FG-07/FG-23.
The four exact writer grants and five cancellation-baseline removals retain
their previously recorded human authority; no new exceptions or baseline growth.

Updated candidate selection to include the actual Batch 8 and validation-repair
paths, and corrected the restore-flow document to use post-migration counts.
These documentation-only edits change the overall fingerprint AFTER the audited
snapshot. The audit must not be relabeled as validation of the later whole tree.
Implementation-file hashes must remain unchanged; any source/test/guard change
requires fresh relevant validation. No staging, commit or push in this continuation.
Preserve unrelated `.codex/config.toml`, `.claude/`, `CLAUDE.md`, release-assurance
documents and their separate handoff.

Remaining acceptance: independent reviewer/guardian re-review of the final
candidate, including corrections to the earlier FAIL verdict. NOT RUN under the
latest no-subagents instruction. A human or separate independent review can
provide that gate; local self-review is not a substitute. Do not mark Wave 5
finalized or release-ready before it passes. Keep the reviewed final candidate
and validation fingerprint aligned if acceptance entails further edits.

## User finalization prerequisite audit - 2026-10-13

Overall: PARTIAL; the four requested prerequisites are NOT all satisfied.
The user authorized commit/push, then requested this prerequisite assessment.
No staging, new commit or push has occurred; commit/push is paused for the audit.

1. Final candidate with recorded source commit: NOT MET. HEAD remains
   4137afdfb487b43e7d7f76a511b1618efa89dfb8, a conversation-export/base revision,
   not a commit containing the current savings/schema/validation repairs.
   A validated worktree fingerprint is not a final source commit.
2. Up-to-date handoff: MET as a truthful PARTIAL handoff, not a completion
   declaration. This packet records implementation, validation and open gates.
3. Required review/validation with no unresolved mandatory gates: NOT MET.
   Twelve automated runs passed at the recorded snapshot; required migration
   and PDF device cases passed. Local strict review passed, but independent
   reviewer/guardian acceptance, including rereview of the earlier financial
   FAIL corrections, remains NOT RUN. No subagents may be used in this session.
4. No mixed unfinished production/test changes silently included: PARTIAL.
   Forty-eight audited implementation/test/guard files still match their
   validated hashes; 56 continuation paths are selected, not staged. Unrelated
   configuration and release-assurance work remains excluded. The working tree
   is mixed; no final committed candidate/index boundary has yet been verified.

Documentation changed after the broad validated snapshot. Evidence must retain
that distinction; do not relabel prior results as validation of a new entire
worktree. No failed automated gate is currently known, but passing automation
does not close the mandatory independent acceptance gate.

Next: obtain independent acceptance without violating the no-subagents request;
then resolve any findings, verify exact selected hunks/index and evidence linkage,
and create/record the authorized candidate commit before claiming finalization.
Do not call a WIP checkpoint a finalized Wave 5 or release-ready candidate.
## Renewed user-authorized checkpoint commit - 2026-10-13

The user explicitly requested "commit and push first" after the prerequisite
assessment. This supersedes the temporary commit/push pause above and authorizes
only the selected Wave 5 checkpoint, not final acceptance or a release claim.
The 48 audited implementation/test/guard files were rechecked unchanged before
staging; eight scoped documentation paths complete the 56-path selection.
Unrelated local agent configuration, Claude files and release-assurance work
remain excluded. Source/checkpoint commit identity will be recorded in the
subsequent handoff update after Git creates it. Independent reviewer/guardian
acceptance remains NOT RUN; Wave 5 remains PARTIAL. No subagents are used.
No new build/test run is claimed by this Git-only checkpoint operation.

Commit preparation found trailing whitespace/extra EOF blank lines in the new ledger DAO and entity. Removed only whitespace; non-whitespace characters were checked identical. These two byte hashes now differ from the broad validated snapshot; prior runs remain historical to that snapshot. No behavior change or new final acceptance is claimed.


Commit-preparation compile vr-20261013-121642-ff0b5a65: PASS/0, physical marker, actual Kotlin/KSP execution (two tasks executed), matching start/end fingerprint 97c729976e4093aa6c4bc244d4baf31f32e45619ac4e4fff55d1d877feaa3666. Command and logs: build/validation-runs/vr-20261013-121642-ff0b5a65/result.json and stdout.log. This evidence append is documentation-only after that run; it does not assert a rerun of the full suite or independent acceptance.


## Recorded source checkpoint - 2026-10-13

Source candidate commit: `
d5d4d97e014078d651a841b8309c748ed93d9944
`.
Branch: `bug-fixes`. Commit: `wip: checkpoint Wave 5 savings persistence and validation repairs`.
The commit contains exactly the 56 selected continuation paths; committed path
membership was checked against the selection manifest. No unrelated local agent
configuration, Claude files, release-assurance work or generated logs were included.
The Git index was empty after the source commit; the remaining worktree changes
are the explicitly excluded unrelated files. This updates the earlier prerequisite
audit: a source checkpoint is now committed and recorded, but it is NOT a
final independently accepted candidate. Wave 5 remains PARTIAL.

The broad automated evidence remains attached to its original snapshot; the
post-whitespace compile passed as recorded above. No full-suite rerun on the
new commit and no independent reviewer/guardian acceptance is claimed.
This documentation-only follow-up records the source SHA without modifying code.
Authorized push target: `origin/bug-fixes`; push success must be separately verified.
