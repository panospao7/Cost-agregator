# CL-19 — RR3 re-review completion, including UNCOMMITTED work

**VERDICT: FAIL — NOT MERGEABLE.** Reviewed September 25, 2026, Europe/Bucharest (+03:00), astra xhigh review lane. This closes the review assignment, not the implementation.

**0/11 DONE-CORRECT: 0/5 original WIs and 0/6 requested continuation items. R-1 through R-5 are all OPEN. Six source-evidenced regressions remain: five carried forward plus one newly identified in dirty code (RR3-R6).** Two are CRITICAL and four MAJOR, following the governing document's actual severity labels. No subagents, builds, tests, guards, or validation-runner actions were invoked. Source was read only, including all twelve pre-existing dirty/untracked paths.

## 1. Authority, environment, and immutable review boundary

- Worktree verified: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-22`.
- Branch verified: `rp-22-wip`; reviewed source HEAD: `2e8bf205b4f227cbebb8d13e3d903e58124ce5ed`.
- `git merge-base --is-ancestor ead80016 HEAD` returned exit 0. The rp-25 compilation-repair merge is present; the old five out-of-fence compiler failures are **not a current source prerequisite to re-integrate**.
- Governing document read in full: `CL-19-review-completion.md` in this directory, especially R-1–R-5 and WI-6–WI-10/WI-4a. Original `CL-19-restore-backup-fail-closed-state-machine.md` read as background and for the state machine, named tests, and fence. Architecture routing inspected in `CODEBASE_SEGMENTS.md` (Segment 18 and worker ownership), `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md:406-442`, and `ENGINE_INTERACTION_MAP.md` (backup/worker impacts). Source takes precedence over map descriptions.
- Scope: committed `ad412aec..HEAD`, plus `git diff HEAD` and the untracked `PendingWorkerTestFactory.kt`. Initial index was empty. Dirty source hashes were rechecked against the initial read before artifact creation; all twelve matched.
- Evidence tags: **C = committed** at the reviewed HEAD; **D = uncommitted tracked changes**; **U = untracked, also uncommitted**. Line numbers refer to the reviewed working-tree bytes, not old anchors in the first review. A D file may retain a C defect; this is stated explicitly.
- Severity clarification: the request's introductory parenthesis calls R-1/R-2 critical, but the governing artifact actually labels **R-1 MAJOR, R-2 CRITICAL, R-3 CRITICAL, R-4/R-5 MAJOR** at lines 65/69/73/77/81. This report retains the governing IDs and labels rather than silently renumbering defects.
- This artifact's docs-only commit does **not** deliver the dirty fixes or helper. Its parent identifies reviewed committed source; the hashes below identify the additional uncommitted evidence.

### Committed history and inventory

`git log --oneline ad412aec..HEAD` returned:

~~~text
2e8bf205 docs: add CL-19 completion review and implementation evidence
f268dbf2 fix: enforce fail-closed restore recovery state machine
2b6d79aa fix: gate bank terminal status writes during restore
ead80016 merge: rp-25 test-recovery — unit-test compilation repairs + behavioral triage
00ca7e1e test: record trusted-tests + Phase B baseline triage (CANCEL-01 production violations, OOM-bounded baseline 5230P/214F)
9e8353bf test: repair unit-test compilation at HEAD (5 files, assertions unchanged) + triage behavioral failures
~~~

`git diff --stat ad412aec..HEAD`: **20 files, +843/-112**. All committed production and test hunks were inspected, including the five rp-25 repairs. The following path key also resolves every basename citation in this report. Main/test paths are under `app/src/main/java/com/yourname/expensetracker/` and `app/src/test/java/com/yourname/expensetracker/`, respectively.

| Kind | Path suffix | Committed delta | Dirty state |
|---|---|---|---|
| main | data/backup/RestoreJournal.kt | C | unchanged from HEAD |
| main | data/backup/RestoreMaintenanceMode.kt | C | D |
| main | data/repository/DatabaseBackupRepositoryImpl.kt | C | unchanged from HEAD |
| main | domain/bank/BankConnectionLifecycleCoordinator.kt | C | unchanged from HEAD |
| main | startup/AppStartupCoordinator.kt | C | D |
| main | domain/workers/WorkerRegistry.kt | none | D |
| test | data/backup/DatabaseBarrierTest.kt | C | D |
| test | data/backup/P7BugFixesTest.kt | C | D |
| test | data/backup/RestoreJournalDurabilityTest.kt | C | unchanged from HEAD |
| test | data/privacy/KeystoreInstallationSecretHashingTest.kt | C, rp-25 | unchanged from HEAD |
| test | domain/bank/BankConnectionLifecycleCoordinatorOutcomeTest.kt | C | unchanged from HEAD |
| test | domain/consistency/LegacyDataConsistencyCheckerTest.kt | C, rp-25 | unchanged from HEAD |
| test | domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt | C, rp-25 | unchanged from HEAD |
| test | domain/workers/WorkerRestoreRegressionTest.kt | C | D |
| test | scenarios/BackupRestoreContractTest.kt | C | D |
| test | startup/AppStartupCoordinatorRecoveryTest.kt | C | D |
| test | ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt | C, rp-25 | unchanged from HEAD |
| test | ui/screens/export/ExportOptionsViewModelPrivacyDenialTest.kt | C, rp-25 | unchanged from HEAD |
| test | data/privacy/PrivacySettingsRepositoryImplWorkerGatingTest.kt | none | D |
| test | domain/notification/capture/NotificationIntakeRestoreBarrierTest.kt | none | D |
| test | golden/RestoreBlocksAllWritesTest.kt | none | D |
| test | domain/workers/PendingWorkerTestFactory.kt | none | U |

The other three committed paths are `docs/analyses and debug master/CL-19-wave1-implementation-report-2026-09-23.md`, the governing first review, and `docs/testing/generated/TRIAGE-2026-09-test-recovery.md`. They were read, not edited. The specifically required `test/data/repository/DatabaseBackupRepositoryImplTest.kt` is present and **unchanged in both reviewed deltas**, not missing from the repository.

Dirty inventory: **11 modified tracked paths (+2059/-235), plus one untracked 31-line helper**. Exact SHA-256 snapshot:

| Uncommitted path (resolved above) | SHA-256 |
|---|---|
| RestoreMaintenanceMode.kt | `E6A257692A0B306CBED1B8CE1F87A88DDA572520CB5EBC44B0836623A4216188` |
| WorkerRegistry.kt | `11D85809680CFE0F111D33AADBCFD1E0929015043F48FD996B2D10D1844B8BEB` |
| AppStartupCoordinator.kt | `73F3A59F92092D6A77FA1B273A243E5F113F5367E2A9EECAA1AB1DD1F5C45E72` |
| DatabaseBarrierTest.kt | `51EA25AB599E03DEB92526327E0A40F97FDB9584BEFB20657348F779A163BF2D` |
| P7BugFixesTest.kt | `9BAA04133897CB61585AA480FBAF2C529AEB879FE75B7B335480F63168C2584D` |
| PrivacySettingsRepositoryImplWorkerGatingTest.kt | `0D375C9247F582C350F4F7C9D4DFEF8338B3E9B7863AE2B3AC8FB30B18732D9F` |
| NotificationIntakeRestoreBarrierTest.kt | `D8FC284B00E427970B5CD66C76B3D1C2B117102F1203C0CD03781DBBB71E3614` |
| WorkerRestoreRegressionTest.kt | `E10DE9B3036B291D22C2A8F22092B100073DC974438CEF7839C4C654BE35B7BB` |
| RestoreBlocksAllWritesTest.kt | `5EF429BB010416B4D28627B58C51B85D6161F87BEA85AE834B7C40A6E682C5E4` |
| BackupRestoreContractTest.kt | `20E02A15276DE2B7E6CA872849E2AA1232CBD069565350230ACC85DE6EFF8B92` |
| AppStartupCoordinatorRecoveryTest.kt | `F7268D98AE86C76817F691CADD321C17AEEF0DACE9C9A7CBBC858CAA43DC4691` |
| PendingWorkerTestFactory.kt | `F83C9C41F61E47473FD6215158BD9B1610BEF9F731D15F7E75C4CC7C84356A0D` |

## 2. Per-WI verdicts at current committed + dirty state

DONE-CORRECT requires the specified behavior, named-test coverage, actual execution on the final relevant tree, and review acceptance. PARTIAL credits source progress without granting execution evidence; DONE-DEFECTIVE identifies a delivered path with a concrete counterexample. No row below has earned DONE-CORRECT.

| Item | Current verdict | Where satisfying progress lives | Evidence and remaining blocker |
|---|---|---|---|
| WI-1 — durable mode/barrier | **PARTIAL** | **C + D**, chiefly D | D `RestoreMaintenanceMode.kt:73-123,133-156,480-548,574-605` adds preference-acquisition containment, coherent strict decoding, runtime latching, and a pending-resume key. D P7 cases at `368-555,558-1037` cover many previously missing boundaries. RR3-R6, the unapproved persistence design checkpoint, incomplete fresh/concurrent resumption proof, and no named execution prevent completion. |
| WI-2 — corrupt journal | **PARTIAL** | **C only**; no journal continuation fix | `RestoreJournal.kt:504-530,733-737` separates absence/corruption and routes critical; D startup preserves that branch at `216-220`. Coercive identity/time/path decoding remains at `110-168`; file inspection lies outside the catch at `512`; terminal cleanup at `741-761` remains unchecked. Full corruption/second-startup matrix is absent and unexecuted. |
| WI-3 — journal durability | **DONE-DEFECTIVE** | **C only** for journal/repository; D mode does not repair callers | `RestoreJournal.kt:536-576,636-681` now throws and reset ordering at `DatabaseBackupRepositoryImpl.kt:2785-2794` is improved. R-1/R-2/R-4/R-5 remain; required caller/finalization/rollback tests were not added to `DatabaseBackupRepositoryImplTest`. |
| WI-4 — bank terminal barrier | **PARTIAL — execution pending** | **C only** | `BankConnectionLifecycleCoordinator.kt:155-190` checks immediately before either terminal DAO write, skips both on typed denial, preserves outcome, and rethrows cancellation. Tests at `BankConnectionLifecycleCoordinatorOutcomeTest.kt:142-252` cover allowed/denied/cancelled branches. Static implementation is sound; no current-tree execution evidence. |
| WI-5 — rollback scheduling | **DONE-DEFECTIVE** | **C + D + U** | D `AppStartupCoordinator.kt:434-442` uses checked cancellable exit; D recovery test `598-627` now asserts one active schedule per default and repeats recovery, using U `PendingWorkerTestFactory.kt:10-28`. But failed rollback still loses critical containment (R-3), successful rollback deletes rather than durably finalizes the journal, and real guarded-worker resumption is not proven. |
| WI-6 — decoding/checked resume | **PARTIAL — design/fence decision required** | **D + U** | D `RestoreMaintenanceMode.kt:257-359,386-474` checks aggregate results, active WorkInfo, cancellation and publication ordering; `WorkerRegistry.kt:59-74,131-201` exposes results. However WI-6 expressly required a caller-local approach and escalation before persistence redesign; pending metadata plus a sentinel and out-of-fence registry edits have no approval in the governing authority. RR3-R6 and remaining tests must be resolved. |
| WI-7 — strict journal/terminal semantics | **PARTIAL — continuation changes absent** | Existing **C** foundation only | `RestoreJournal.kt`, `RestoreJournalDurabilityTest.kt` unchanged since first review's substantive source. `fromJson:110-168`, terminal cleanup `741-761`, cancellation translation `559-560,680-681`, and incomplete fault seam `572-576` still fail the continuation. No qualifying tests executed. |
| WI-8 — repository containment/cancellation | **PARTIAL — continuation changes absent** | Existing **C** catches only; no satisfying completion fix | Journal begin remains outside handlers at `DatabaseBackupRepositoryImpl.kt:851-869,1773-1786,2737-2752`; outer import `2016-2023` still exits normally. R-1/R-2/R-5 OPEN. Repository tests remain unchanged. |
| WI-9 — destructive edges/evidence | **PARTIAL — continuation changes absent** | Existing **C** reset ordering only; no satisfying completion fix | `DatabaseBackupRepositoryImpl.kt:1093-1112,1211-1214,1277-1279,2755-2836` still ignores failed swap rollback when choosing restart-safe, deletes extraction evidence on durability failure, and does not implement required reset/stage/ROLLING_BACK finalization. R-2/R-4 OPEN. |
| WI-10 — startup finalization/real schedules | **PARTIAL** | **C + D + U** | D `AppStartupCoordinator.kt:44-88,416-425` contains asynchronous mode/worker-resume failures, and D tests improve active-schedule evidence. It does **not** catch JournalDurabilityException around failed asset rollback `444-448`, honor absorbing critical before recovery `151,186,233,408`, or provide the corruption/terminal-failure matrix. R-3 OPEN. |
| WI-4a — earn bank execution evidence | **PARTIAL — NOT EXECUTED** | Correct **C** bank change/tests; no D addition | Preserve the surgical fix. Add explicit integration → barrier → chosen DAO order assertions as specified; existing tests verify outcome/call counts, not that sequence. Then actually execute the named class; compile and static review cannot satisfy this item. |

## 3. R-1–R-5: explicit re-rulings, with current source evidence

All are source/control-flow counterexamples, **not runtime reproductions**. Do not restore swallowed journal errors to make these disappear.

| Regression | Ruling | Source provenance | Decisive evidence at current state |
|---|---|---|---|
| R-1 — beginJournal failure escapes Result API | **OPEN — MAJOR** | **C**, untouched by D | `DatabaseBackupRepositoryImpl.kt:851-855,1773-1777,2737-2741` invokes `var journalEntry = restoreJournal.beginJournal(` before each operation's handler (`869,1786,2752`). `RestoreJournal.kt:604,559-560` throws. A begin temp-write/sync failure escapes instead of returning a contained failure, with no critical latch and initial NORMAL still admissible. D mode cannot catch code that never calls it. |
| R-2 — legacy post-swap failure can reopen writes | **OPEN — CRITICAL** | **C** defect; D exit still permits its unsafe caller | `DatabaseBackupRepositoryImpl.kt:1980`: `restoreJournal.failJournal(journalEntry, "IMPORT_FAILED_AFTER_SWAP")` precedes critical persistence at `1984-1986`. Its archive exception escapes the catch body to `2016-2023`, including `restoreMaintenanceMode.exit(forceRestartRequired = false)` at `2022`. If worker confirmation succeeds, D mode publishes NORMAL even though DB rollback failed. Confirmed scheduling is not DB verification. Active FAILED can then be cleaned at `RestoreJournal.kt:747-753`. |
| R-3 — failed startup asset rollback loses critical lock | **OPEN — CRITICAL** | **C defect retained inside D startup file** | Current `AppStartupCoordinator.kt:444-448` still calls `restoreJournal.failJournal(` before `restoreMaintenanceMode.enterCriticalRecoveryRequired("startup crash recovery failed")`. D wrapper `416-425` catches only cancellation and mode/worker exceptions, not JournalDurabilityException. If FAILED is written and archive preservation throws, no critical sentinel/mode is set. Next startup consumes FAILED (journal `747-753`) and D checked reset (startup `233-243,64-75`) can reopen an unhealthy DB once schedules confirm. |
| R-4 — destructive failure deletes extraction evidence | **OPEN — MAJOR** | **C**, untouched by D | New committed durability branches `DatabaseBackupRepositoryImpl.kt:1211-1214,1277-1280` call `cleanupRestoreStaging(stagedDbPath, tempDir)`. Helper `1327-1329` executes `runCatching { tempDir?.deleteRecursively() }`. A post-swap/asset-ledger durability error therefore keeps a recovery journal but deletes its recorded extraction source, unlike the neighboring post-swap ordinary/cancellation contract at `1259-1267,1289-1296`. |
| R-5 — cleanup durability failure replaces cancellation | **OPEN — MAJOR** | **C**; D scheduling cancellation tests do not cover it | In `DatabaseBackupRepositoryImpl.kt:1259-1274`, `restoreJournal.failJournal(journalEntry, "RESTORE_CANCELLED")` and checked maintenance exit precede `throw e`. Secondary failure can replace the original CancellationException. Journal generic catches at `RestoreJournal.kt:559-560,680-681` also translate cancellation into JournalDurabilityException. D P7 cancellation cases exercise worker resumption, not these repository/journal paths. |

## 4. New-delta/dirty regression hunt

### RR3-R6 — MAJOR: the new sentinel can falsely certify critical-state durability [D]

Evidence: `RestoreMaintenanceMode.kt:180-183,623-637,648-657,666-679`. The new fallback includes these exact branches:

~~~kotlin
        if (!sentinel.exists()) {
            FileOutputStream(sentinel, false).use { output ->
                output.write(CRITICAL_SENTINEL_CONTENT)
                output.fd.sync()
            }
        }
        sentinel.exists()
~~~

and then `return sentinelPersisted || preferencesPersisted` at line 637. `latchCritical` assigns that aggregate to `persistenceHealthy`; the public critical transition throws only if the aggregate is false.

Source-derived counterexample: a write/sync failure **after file creation** leaves a visible sentinel but returns false; critical preference commit also fails. A subsequent critical-persistence attempt sees the leftover file and skips both writing and sync, treating existence as durable success. If preferences fail again, the method nevertheless reports success/healthy. This is an introduced durability-certification defect: no successful sync of that file was established. It is not proof of a particular device losing bytes, but the required durable-or-fail guarantee has been replaced by an unproven assumption. Independently, a failed critical preference commit is no longer surfaced as the typed health failure required by governing WI-6/spec line 35 whenever the sentinel path returns true. An alternate persistence contract needs explicit approval, not implicit acceptance.

Required closure: first settle the WI-6 design checkpoint; if a sentinel is approved, establish checked durability on retries as well as first creation, never promote a leftover failed-write file to healthy merely because it exists, and define how the typed persistence-failure contract is retained. Add named P7/startup tests for sentinel open/write/sync failure after creation, repeated attempts, both stores failing, and fresh reconstruction. Existing D P7 tests `494-527` prove only the successful-sentinel/failed-preference case; they do not exercise this failure boundary. **NOT RUN.**

### Additional risks / acceptance gaps (not additional counted regressions)

1. **Fresh-instance cancellation can invalidate an earlier schedule observation.** D mode construction with pending state calls `pauseAllWorkers()` (`114-123`), which cancels unique work (`362-379`). Resumer A can finish active-work queries (`386-437`), then a fresh instance B can observe pending and cancel that work before A finalizes (`322-358`). No cancellation generation is shared with A; the mode/pending pair can still pass finalization. This is a concrete interleaving to test for the explicitly required fresh-instance boundary. Production reachability under the Hilt singleton lifecycle was not established here, so it is **not inflated into another counted production regression**. Either prove/enforce single-owner lifecycle assumptions or close the race within an approved design. Current tests construct fresh instances during cancellation but do not exercise this post-confirmation window.
2. **Absorbing critical is checked too late in startup.** `AppStartupCoordinator.kt:151` invokes journal recovery before `233` checks the mode; swap recovery can alter live files first, and assets call `enter(ASSETS_RESTORING)` at `408` synchronously. With an existing critical lock that throws before the asynchronous catches, startup can fail before recovery UI. This was already an unfinished WI-10 contract; dirty asynchronous catches do not finish it.
3. **Terminal preservation and required states remain unfinished.** Journal COMPLETE/FAILED cleanup blindly deletes the active record (`RestoreJournal.kt:741-753`); deletion itself ignores failure (`691-695`). Successful startup asset rollback deletes instead of checked failure finalization (`AppStartupCoordinator.kt:438-440`). Costbackup pre-swap aborts still exit NORMAL before finalization (e.g. repository `889-890`); reset's backup abort leaves PREPARING active (`2763-2775`). Reset skips the specified preparatory/staged transitions and ignores delete Booleans (`2755,2785-2803`). The swap-failure branch uses rollback success only for cleanup but always reports restart-safe (`1093-1112`). Do not recount these first-review residuals as newly discovered regressions.
4. **Testing improvement is real but narrower than execution proof.** U `PendingWorkerTestFactory.kt:16-24` substitutes a never-completing worker for every requested class. This is useful to inspect active enqueue state deterministically, but cannot prove production workers cross WorkerExecutionGuard with NORMAL and stop flags reset. Guard still blocks before normal worker execution (`main/domain/workers/WorkerExecutionGuard.kt:280-327`). Dirty worker tests `104-172` test synthetic registry entries; they do not execute the recovery/real-guard interaction. Repeated recovery asserts active counts, not retained identities, at startup test `615-627`.
5. **Fence/design gate is OPEN.** Governing `CL-19-review-completion.md:150,156-158,196` explicitly calls for caller-local confirmation and no WorkerRegistry/WorkerSpecScheduler/WorkerExecutionGuard production edits, with escalation before persistence redesign. D `WorkerRegistry.kt` changes its public entry/result contract and scheduling implementation; D mode adds both `worker_resume_pending` and `restore_maintenance_critical`. The three additional dirty privacy/notification/golden test classes and U helper are outside the specifically named original tests. Their adaptations preserve assertions on inspection, but this review grants no scope expansion. Get a narrow documented human decision or have the owner supply an in-fence solution; **do not discard or rewrite the author's dirty work during review**.
6. **Privacy/guard boundaries were not broadened by this reviewer.** No schema, migration, guard, baseline, or allowlist change is in the reviewed deltas. Bank provider/DAO and ordinary barrier contracts remain unchanged. Registry's dirty exception-class logging replaces throwable logging, but `WorkerRegistry.kt:156` constructs a dynamic error code rather than a controlled constant; keep exception class in a separate bounded field if this new result surface is approved. Existing raw journal/repository diagnostics remain sibling CL-17 ownership, not permission for a sweep. No runtime or static-guard PASS is inferred.

## 5. Coverage and persisted execution evidence

### What the dirty work adds, and what remains

- **WI-1/6:** D P7 supplies first-install absence, orphan metadata, present-null/read faults, false/thrown NORMAL commits, successful/failed critical preference fallback, fresh pending state, pending-clear failure, active-work confirmation failure, cancellation identity, actual coroutine cancellation, and stop-flag/publication order (`368-1037`). D real-mode barrier test `DatabaseBarrierTest.kt:88-114` covers pending state on two instances. Still required: RR3-R6's alternate-store fault matrix; absent preferences/acquisition failure and wrong-type mode cases through the actual owner; approved fresh-instance/finalization ordering; genuine worker-guard interaction rather than perpetual placeholder execution. Existing mocked every-enum coverage is not itself a persistence test.
- **WI-2/7:** C `RestoreJournalDurabilityTest.kt:212-254` covers blank/unknown/malformed-task and several valid states. Missing complete truncated/invalid/unreadable/missing/null/wrong-type required identity/state/task variants, exact corrupt-byte retention and second-startup lock matrix. Permissible legacy missing startedAt and reset's intentionally empty source/staged fields must remain valid; do not blanket-require optional paths.
- **WI-3/7/8/9:** C journal tests `168-208` cover sync failure, active rename-false fallback and preservation-temp sync failure. They do not cover initial open/write failures, independent fallback write failures, success/failure archive rename plus failed fallback, deletion failure, interrupted terminal preservation, or original cancellation across cleanup faults. Repository tests `263-325,479-554,637-674,742-803` are existing ordering/rollback/pre-swap tests, not the required new durability matrix. No repository test changed.
- **WI-5/10:** D startup tests add active schedules and repeat counts (`598-627`), worker-resume failure UI containment (`630-682,957-1002`), and reconstruction after failed preference commit (`841-907`). Missing corrupt/terminal journal failures on two startups, failed rollback plus failed archive preservation, checked successful rollback finalization, no scheduling on every failure/asset-completion branch, and the actual worker-release interaction.
- **WI-4/4a:** C bank tests `142-252` exercise allowed success/partial/failure, denied blocked/success, and cancellation. Add the requested explicit ordering assertions and earn named execution. No provider bypass found in inspected `BankApiIntegration.kt:223-237` or the bank coordinator delta.

### Read-only validation audit — not a validation run

There is no `app/build/test-results/testDebugUnitTest` directory in this worktree. All ten existing `build/validation-runs/` result files and marker presence were inspected. Historical successful entries are **compile only**; no result establishes execution of the eight named classes on the reviewed HEAD plus dirty hashes.

| Persisted run(s) | Recorded evidence | Re-review use |
|---|---|---|
| 20260923: 131324, 131650, 132114, 140217, 141036 | targeted-unit-test FAIL, exit 1; first review documents wrapper/then-local/old out-of-fence compilation failures | Historical only, not current acceptance. |
| 20260923: 133947, 140601, 141932 | compile PASS, exit 0 | No named-test execution; cannot earn any DONE-CORRECT row. |
| `vr-20260924-173411-8d30ea88` | P7 targeted FAIL, exit 1; `stderr.log:6` SDK location not found; complete.marker present | Failure before tests, revision `c57fe83c`, not current reviewed source. |
| `vr-20260924-173500-0b44a5a3` | P7 targeted FAIL, exit 1; `stderr.log:1-10` old five-file unit-test compilation failures; complete.marker present | Also revision `c57fe83c`; no tests executed. Both September 24 runs record fingerprint `31d5d436260d17fdef0dcbec7ec0ba4a396fab4e87e6d4024de4bf3d6aeb91ce`, not evidence for this review snapshot. |

The merged rp-25 triage document reports compilation repairs and other-lane execution, including a journal probe. Those are useful historical provenance, **not final CL-19 named-test evidence**. Source inspection of the merged five repairs confirms the obsolete nullable/fake-DAO/constructor mismatches were repaired; do not repeat the first review's obsolete instruction to integrate them. This session has no ANDROID_HOME and no local.properties; the future validation owner must resolve SDK configuration in its approved environment rather than treating those historical infrastructure failures as source failures. This reviewer did not configure the SDK, start the runner, or execute Gradle.

## 6. Exact remaining steps to mergeable, in order

1. **Keep merge blocked; resolve the narrow WI-6 fence/design checkpoint.** Decide whether the registry result API, pending preference, fallback sentinel, three additional test files and helper are authorized. The governing plan did not approve them. Preserve all existing dirty files while the owner obtains that decision; no re-plan of the entire feature is needed.
2. **Finish WI-6 and RR3-R6 in the approved design.** Keep strict decoding and useful dirty tests. Require checked critical durability on every attempt; preserve typed failure/cancellation. Prove fresh-instance, stop-flag, confirmation and final publication behavior, including the post-confirmation cancellation interleaving. Use P7/barrier/startup/worker named tests; do not silently broaden worker admission or clear critical mode.
3. **Finish WI-7 before relying on terminal records.** Strictly validate present required JSON types; contain inspection errors; retain corrupt bytes; retry/check terminal archive preservation and deletion; rethrow cancellation before durability translation. Extend the narrow journal I/O seam and complete independent active/success/failure write/sync/rename/fallback/deletion tests, including crash windows and legacy compatibility.
4. **Finish WI-8 (R-1/R-2/R-5).** Put beginJournal/maintenance entry inside bounded Result/cancellation handling for all three operations. Handle exceptions raised inside catch/finalization/cleanup bodies, retain the destructive-point fact, and establish critical state before return. Preserve the original CancellationException even when cleanup and critical persistence also fail. Add the required `DatabaseBackupRepositoryImplTest` no-close/no-swap/byte-retention matrix; do not substitute another class.
5. **Finish WI-9 (R-2/R-4 and destructive residuals).** Publish the approved stage modes, durably record/attempt ROLLING_BACK, consume existing verification results correctly, check reset deletion outcomes, finalize safe pre-destructive aborts before NORMAL, and retain extraction/safety/pre-restore evidence after destructive uncertainty. Failed rollback is critical, never merely restart-safe. No verifier-query/schema changes.
6. **Finish WI-10 and original WI-5 (R-3).** Honor absorbing critical before automatic recovery. Protect failed asset rollback even if failJournal throws; map journal/mode finalization errors to recovery UI without uncaught startup failure. Verified rollback must finalize the journal before checked resumption; successful asset completion stays restart-required. Add two-fresh-startup corrupt/failure matrices, zero-schedule failure assertions, active unique identities/counts and real guarded-worker ordering.
7. **Finish WI-4a without rewriting working bank logic.** Add explicit integration/barrier/DAO order assertions while retaining original outcome/timestamp and no-both-DAO assertions. Execute the existing named class in the gate below.
8. **Obtain a passing strict review of the complete final committed + uncommitted diff**, including any explicitly authorized design expansion and required architecture/privacy review. A source review FAIL blocks validation/completion. This RR3 review is FAIL, not that future gate. Do not weaken guards, baseline assertions or test inclusion.
9. **Have only the designated validation owner run the serial commands below**, after SDK/runner preflight and on a stable final tree. Poll RUNNING, retain logs/result/marker/fingerprint and actual named test cases. Then compile. A compile PASS, zero-match filter, missing/stale/unknown result, or other-lane historical PASS cannot replace execution. Ask separately before expensive broader profiles; this review authorizes none.
10. **Publish only the owner's separately approved source completion**, including the currently untracked helper if retained; this artifact commit intentionally does not stage it. Human integration must preserve rp-20 → rp-21 → rp-22 contracts, resolve overlaps surgically, and repeat affected named validation on the integrated tree. Only then update completion status through an authorized documentation pass. Leave JOURNAL.md to the human; the return includes a proposed line, not an edit.

### Exact validation-runner commands and per-WI mapping — NOT EXECUTED HERE

Run from the verified worktree. The interface was read at `scripts/validation-runner.ps1:1-42`; it was never invoked. Only the designated validation owner executes these. First inspect the runner's existing state using the normal owner workflow; do not launch over a live run. For each Start below, retain its returned run ID and serially call `& .\scripts\validation-runner.ps1 -Action Wait -RunId '<returned-run-id>'` until terminal. A Wait may return RUNNING; poll it rather than rerun Start.

| ID | Exact Start command |
|---|---|
| T1 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*P7BugFixesTest'` |
| T2 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*DatabaseBarrierTest'` |
| T3 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*RestoreJournalDurabilityTest'` |
| T4 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*DatabaseBackupRepositoryImplTest'` |
| T5 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*BackupRestoreContractTest'` |
| T6 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*AppStartupCoordinatorRecoveryTest'` |
| T7 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*WorkerRestoreRegressionTest'` |
| T8 | `& .\scripts\validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*BankConnectionLifecycleCoordinatorOutcomeTest'` |
| C1, last | `& .\scripts\validation-runner.ps1 -Action Start -Profile compile` |

| WI | Required exact commands above, after its final changes | Acceptance-specific execution |
|---|---|---|
| WI-1 | T1, T2, T6 | real mode decode/commit/critical publication and fresh startup |
| WI-2 | T3, T6 | complete corrupt-input/retention/second-startup matrix |
| WI-3 | T3, T4, T5 | independent durability faults and operation-level destructive ordering |
| WI-4 | T8 | all allowed/denied/cancelled terminal status branches |
| WI-5 | T6, T7 | verified rollback, active unique schedules, no premature release |
| WI-6 | T1, T2, T6, T7 | approved persistence protocol, RR3-R6 and fresh-instance/stop-flag ordering |
| WI-7 | T3, T5, T6 | decoding, terminal preservation, interrupted-finalization recovery |
| WI-8 | T3, T4, T5 | all three Result APIs, failJournal-in-catch and original cancellation identity |
| WI-9 | T3, T4, T5, T6 | rollback success/failure cross terminal durability, reset outcomes, extraction retention |
| WI-10 | T1, T6, T7 | two startups, recovery UI containment, real guarded resumption |
| WI-4a | T8 | explicit ordering and actual case execution, not just compilation |

A single final stable-tree execution of T1–T8 may satisfy multiple rows; no need to rerun identical classes per row without intervening changes. Then C1. If the additional dirty test scope is approved and retained, also execute serially:

~~~powershell
& ./scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*PrivacySettingsRepositoryImplWorkerGatingTest'
& ./scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*NotificationIntakeRestoreBarrierTest'
& ./scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*RestoreBlocksAllWritesTest'
~~~

Wait/poll after **each** of those starts as well, before final compile. The helper is exercised through P7/startup/contract tests; it is not a separate runnable test class. Persist exact command, exit code, result.json/stdout/stderr/complete.marker paths, matching source fingerprint and nonzero matching test-case counts. Execution status for every command in this section: **NOT RUN by this reviewer**.

## 7. Cross-lane interaction flags — merge order only

Read-only sibling refs at review: rp-20-wip `577c9e56d344b65527def875d149aca53f34c036`; rp-21-wip `18d2551a7ee322a9be90040007496cb173e43aa9`. Both changed-file inventories still include `main/data/repository/DatabaseBackupRepositoryImpl.kt`. No sibling source was imported and no sibling correctness verdict is given.

Keep the governing **rp-20 → rp-21 → rp-22** integration order. In the shared repository, preserve rp-20's privacy-before-side-effects contract, rp-21's bounded/cancellation-safe diagnostics, and CL-19's checked journal/rollback/critical containment together. Whole-file conflict resolution can drop one contract. WI-8/9 will also need the repository test class that has sibling fixture/constructor interactions. Recheck sibling refs and surrounding hunks at integration; these flags are not a merge simulation or integration PASS.

## 8. Deliverable and safety record

- Files authored by this reviewer: **this new artifact only**. Governing first-review SHA-256 before writing: `861B481237B3CBCC9121AFD8BC6D787CAEFB7A017F9CB5E93435ACF9EE67CDC1`.
- The source hash inventory above matched the initial dirty snapshot at September 25, 2026 09:46:39 +03:00. The docs-only commit must contain only this artifact; retain all twelve author-owned dirty paths, including the untracked helper. No staging/stashing/discarding of them is permitted.
- Validation: **NOT RUN — explicitly prohibited for this role.** Historical results are separated from current acceptance above. No tests, source edits, JOURNAL edits, guard/baseline changes, or unrelated campaign edits were performed.
- Merge gate remains **FAIL**: R-1–R-5 OPEN, RR3-R6 OPEN, design/fence checkpoint unresolved, required continuation tests incomplete and no final named execution evidence. Closing this review is not a claim of implementation completion.

