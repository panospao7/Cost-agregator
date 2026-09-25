# CL-19 — Wave-1 review and direct-fix completion plan

**VERDICT: FAIL — NOT READY FOR MERGE.**

Review performed **2026-09-24**, Europe/Bucharest (+03:00), by completion reviewer (astra, xhigh). This is a source/diff review, not a validation run. The request's September 23 journal-date template predates this review; use September 24 for the actual review entry.

**Summary: 0/5 DONE-CORRECT; 5 source-evidenced regressions (2 CRITICAL, 3 MAJOR); all five out-of-fence compilation failures are PRE-EXISTING at ad412aec.** No subagents, builds, tests, guards, or validation runner actions were invoked. No production/test source was edited.

## 1. Environment, authority, and evidence baseline

- Verified worktree: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-22`.
- Lane: `rp-22-wip`; reviewed source HEAD: `d3e8fa25689b76f67abcfa1ad55c3e5c517e3e4e`.
- `git merge-base ad412aec HEAD`: `ad412aecb1347daebee7fef62aca745cf205f0aa` (the requested base).
- Initial tracked modifications/staged paths: none. Sole untracked path: `docs/analyses and debug master/CL-19-wave1-implementation-report-2026-09-23.md`. Read in full and retained unchanged; original Git blob digest `cd9b9c08fb6b7e70de8b384b349acaf753ee6337`. Commit that report with this review, as expressly requested.
- Spec read in full: `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-19-restore-backup-fail-closed-state-machine.md` (180 lines). Its post-spec gate-lift at line 6 supersedes its older unverified labels. Authority is the spec's state machine, WI-1–5, tests at 151–155, validation at 161–162, and fence at 166–167.
- Architecture routing inspected: `CODEBASE_SEGMENTS.md` Segment 18, `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md:406-442`, and `ENGINE_INTERACTION_MAP.md` worker/backup impact chains. Source wins over stale map descriptions.
- History from `git log --oneline ad412aec..HEAD`: `4c1de21d fix: gate bank terminal status writes during restore`; `d3e8fa25 fix: enforce fail-closed restore recovery state machine`.
- Full diff read file by file, including all test hunks: **12 files, +495/-107**. The implementation report correctly says PARTIAL and no strict gate passed; its compile PASS is not test evidence.

### Changed-file inventory and evidence path key

Below, basename-and-line references resolve to these exact paths at reviewed HEAD. All twelve implementation paths are inside the original ALLOWED fence.

| File | Exact path | Diff + / - |
|---|---|---:|
| RestoreJournal.kt | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt` | 106 / 64 |
| RestoreMaintenanceMode.kt | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt` | 110 / 24 |
| DatabaseBackupRepositoryImpl.kt | `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | 35 / 0 |
| BankConnectionLifecycleCoordinator.kt | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt` | 4 / 0 |
| AppStartupCoordinator.kt | `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt` | 29 / 13 |
| DatabaseBarrierTest.kt | `app/src/test/java/com/yourname/expensetracker/data/backup/DatabaseBarrierTest.kt` | 10 / 0 |
| P7BugFixesTest.kt | `app/src/test/java/com/yourname/expensetracker/data/backup/P7BugFixesTest.kt` | 36 / 0 |
| RestoreJournalDurabilityTest.kt | `app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalDurabilityTest.kt` | 89 / 0 |
| BankConnectionLifecycleCoordinatorOutcomeTest.kt | `app/src/test/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinatorOutcomeTest.kt` | 55 / 0 |
| WorkerRestoreRegressionTest.kt | `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt` | 8 / 0 |
| BackupRestoreContractTest.kt | `app/src/test/java/com/yourname/expensetracker/scenarios/BackupRestoreContractTest.kt` | 4 / 6 |
| AppStartupCoordinatorRecoveryTest.kt | `app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt` | 9 / 0 |

The specifically required `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` is **unchanged**, not absent. Existing import/rollback tests do not supply the missing durability-failure matrix.

## 2. Per-work-item verdicts

DONE-CORRECT requires the delivered contract and enumerated test coverage, with actual execution evidence; source correctness alone is not a completion gate. DONE-DEFECTIVE denotes a delivered implementation path with a concrete counterexample, not a claim that the coder finished its tests. PARTIAL denotes unfinished coverage/behavior or an unexecuted otherwise-correct implementation.

| Spec WI | Verdict | Diff/source evidence and completion assessment |
|---|---|---|
| WI-1 — maintenance mode | **DONE-DEFECTIVE** | Checked commits and an in-memory failure lock were added (`RestoreMaintenanceMode.kt:134-158,268-297`). However, present-but-null still maps to NORMAL (`254-257`); a runtime read failure is returned but not durably latched/published (`99-108,254-265`). `exit(false)` persists NORMAL before unconfirmed, exception-swallowing scheduling (`175-197,247-249`). False/thrown commit and scheduling boundary tests are missing. |
| WI-2 — corrupt journal | **PARTIAL** | Explicit Absent/Valid/Corrupt classification and critical routing are real (`RestoreJournal.kt:504-530,733-737`); unknown state and malformed asset-list handling were tightened (`110-169`). Required-field type validation is incomplete; only live-path nonblankness is checked after coercive path reads. Startup's critical branch exists (`AppStartupCoordinator.kt:181-185`), but corruption/second-startup and finalization-failure matrices are not supplied. |
| WI-3 — journal durability | **DONE-DEFECTIVE** | fsync/write/preservation errors now throw (`RestoreJournal.kt:536-576,636-681`), and reset adds safety-path/SWAPPING ordering (`DatabaseBackupRepositoryImpl.kt:2785-2794`). Callers do not consistently contain the new exceptions: journal begin is outside all three operation handlers; legacy import can exit NORMAL after post-swap failure-journal errors; failed startup asset rollback can miss critical persistence. Post-destructive evidence cleanup and cancellation also regress (R-1–R-5). |
| WI-4 — bank terminal barrier | **PARTIAL** | The production change is correct on static inspection: barrier immediately precedes either terminal DAO write, typed denial skips both, cancellation rethrows, and the original outcome is returned (`BankConnectionLifecycleCoordinator.kt:155-190`). New denial/cancellation tests plus existing success/partial/failure tests cover the requested branches (`BankConnectionLifecycleCoordinatorOutcomeTest.kt:142-252`). **No named test executed**, so this is implementation-complete / validation-pending, not DONE-CORRECT. |
| WI-5 — rollback scheduling | **DONE-DEFECTIVE** | Verified asset rollback now calls `exit(false)` rather than `reset()` (`AppStartupCoordinator.kt:388-395`). The registry call is real, but it cannot prove scheduling succeeded; its per-entry failures are swallowed. The new WorkManager assertion accepts any historical row, including cancelled work (`AppStartupCoordinatorRecoveryTest.kt:521-525`), and the registry-set assertion (`WorkerRestoreRegressionTest.kt:95-101`) does not exercise recovery or repeated scheduling. Failed rollback plus journal-preservation failure also regresses (R-3). |

### Enumerated boundary coverage audit — all listed tests remain NOT RUN

- **WI-1:** `DatabaseBarrierTest.kt:32-86` covers NORMAL admission and every non-NORMAL denial, using a mocked mode (`14-26`), not real persistence. `P7BugFixesTest.kt:68-144` covers atomic critical keys, unknown/blank decoding, and a fresh critical instance. Missing: mode-read exception; present-null/invalid value distinction; false and thrown `commit()` (including critical fallback commit); no scheduling/stop-flag reset after failure; runtime critical flow publication; fresh coordinator after those failures. `AppStartupCoordinatorRecoveryTest.kt` added no such cases.
- **WI-2:** `RestoreJournalDurabilityTest.kt:212-254` adds absent/blank, unknown state, one malformed task, and PREPARING/STAGED/SWAPPING/VERIFYING/ROLLING_BACK classification. Existing startup tests at `636-685` cover failed swap and critical persistence, **not corrupt input**. Missing: truncated and invalid JSON; unreadable file; missing/invalid/null/wrong-type required state/identity fields; malformed list/task variants; exact corrupt-byte retention; fresh mode plus second startup for each corruption family.
- **WI-3:** Existing `RestoreJournalDurabilityTest.kt:45-80` supplies round-trip, safety-path reread, and event preservation. New `168-208` supplies injected fsync failure, active rename-false fallback, fallback fsync failure, and success-preservation temp fsync failure. That last case is **not** success-journal rename failure. Missing: actual temp-open/write exception; fallback copy/write failure distinct from fsync; success/failure archive rename plus failed fallback; failure-archive preservation failure; cancellation; operation-level no-close/no-swap proofs; import/reset ordering/failure handling. The seam at `RestoreJournal.kt:572-576` runs after actual write/flush, so it does not itself inject an initial temp-write failure. `DatabaseBackupRepositoryImplTest.kt:263-288,292-325,637-674` tests older import ordering/rollback but was not updated. `BackupRestoreContractTest.kt:102-125` only corrects backup-mode admission; no new durability matrix.
- **WI-4:** Allowed success (`142-154`), partial (`156-168`), and failure families (`170-200`) already existed. New blocked outcome (`203-215`), denial after success (`218-229`), and cancellation at terminal barrier (`232-249`) preserve the original typed result and prove no DAO call on denial. `BankApiIntegration.kt:223-237` still owns its provider barrier, with no lane modification/direct status-write bypass. Add explicit ordering verification only if needed; do not rewrite working production logic.
- **WI-5:** Existing `AppStartupCoordinatorRecoveryTest.kt:153-237,451-490,494-560,602-685` exercises completed/pending assets, best-effort missing assets, verified safety/pre-restore recovery, and unsafe recovery. New unique-work lookup is useful but weak: assert **active** schedules, not merely nonempty WorkInfo history. Missing: repeated recovery without duplicate active unique work; failed rollback schedules none; successful asset completion schedules none before restart; mode/worker scheduling failure remains blocked; fresh-instance scheduling window; workers first released with NORMAL and the stop flag reset.

## 3. Regression hunt: five introduced failure paths

These are source-derived control-flow counterexamples, not claims of runtime reproduction. R-1/R-2/R-3/R-5 arise because formerly swallowed journal I/O now throws without all callers being adapted. The fix is to complete caller handling, **not restore swallowed errors**.

### R-1 [MAJOR / P1] Journal creation failures escape the Result API before any critical lock

New throwing implementation: `RestoreJournal.kt:536-560,586-605`. All three callers invoke `beginJournal()` before their enclosing try/maintenance boundary: `DatabaseBackupRepositoryImpl.kt:849-869` (costbackup), `1772-1786` (legacy import), and `2736-2752` (reset). A temp-write/fsync exception therefore escapes a `Result<...>` API; none of the new critical catches runs and the initial NORMAL mode remains admitted. Existing corrupt bytes can also reach this path through `writeJournal`'s Corrupt guard. Original DB mutation stops, which is good, but required durable failure visibility and API containment are missing. At the base, `RestoreJournal.kt:518-520` swallowed the same write error. **Fix: WI-8.**

### R-2 [CRITICAL / P0] Legacy post-swap failure-journal error can reopen writes on an unverified DB

After an import swap/reopen failure and failed safety rollback, `DatabaseBackupRepositoryImpl.kt:1980` calls the now-throwing `failJournal()` before persisting critical mode at `1984-1986`. If writing FAILED succeeds but preservation fails, the exception escapes that catch into `2016-2023`, which unconditionally calls `exit(false)`. The new typed catch at `1948-1950` does **not** catch exceptions thrown inside its own catch body. The process becomes writable; startup also treats active FAILED as clean (`RestoreJournal.kt:747-753`). Base preservation caught its own error (`ad412aec:RestoreJournal.kt:635-647`), so the caller reached its critical branch. **Fix: WI-8/9.**

### R-3 [CRITICAL / P0] Failed startup asset rollback can lose its durable critical lock

`AppStartupCoordinator.kt:398-402` still calls `failJournal()` before `enterCriticalRecoveryRequired`, unlike the newly protected swap-recovery branch at `159-168`. When both recovery sources are unusable and failure-archive preservation throws after the active FAILED write, the async task exits without persisting critical mode. On the next startup, `RestoreJournal.kt:747-753` deletes that FAILED record and `AppStartupCoordinator.kt:198-203` resets the transient mode to NORMAL despite the unhealthy DB. This new exception route did not exist while journal preservation swallowed errors at the base. **Fix: WI-10; terminal-record sequencing in WI-7/9.**

### R-4 [MAJOR / P1] New durability catches delete the extraction workspace after destructive restore

New returns at `DatabaseBackupRepositoryImpl.kt:1211-1214,1277-1280` call `cleanupRestoreStaging`, whose `1327-1329` deletes the extraction directory as well as staged DB files. These catches are reachable after live swap, including a ledger write/fsync failure during ASSETS_RESTORING. They retain an active recovery journal but delete its recorded asset-resume source. The existing post-swap generic/cancellation contract explicitly retained that workspace (`1259-1267,1289-1296`). Critical mode alone does not justify destroying recovery evidence before rollback is verified. **Fix: WI-9.**

### R-5 [MAJOR / P1] New durability exceptions can replace the caller's cancellation

`DatabaseBackupRepositoryImpl.kt:1259-1274` intends to rethrow the caught CancellationException, but its pre-swap cleanup calls newly throwing `failJournal()` at `1269` (and checked maintenance exit at `1271`) before reaching `throw e`. A cleanup fsync/preservation/mode failure escapes instead, masks the original cancellation, and can skip critical containment. Separately, new generic wrappers in `RestoreJournal.kt:559-560,680-681` convert a CancellationException from a seam/dependency into JournalDurabilityException. Base journal cleanup suppressed its I/O error and the explicit caller rethrow remained reachable. **Fix: WI-7/8; assert original cancellation identity and safe persisted mode.**

## 4. Other unfinished contracts / coder-flagged risks

These are residual defects or unproven contracts, **not additional counted regressions**.

1. **[MAJOR] Mode decoder/health is incomplete.** `RestoreMaintenanceMode.kt:254-257` treats present-null as NORMAL, with no `contains`/type distinction. `currentMode():99-108` can return critical on a later read fault without updating either flow, setting the health latch, or persisting a critical marker. A subsequent successful NORMAL read admits writes again. New-instance unknown/blank tests do not exercise this. Also inspect exceptions acquiring preferences at construction, not only `getString`.
2. **[MAJOR] Scheduling failure is not observable at the new catch.** `RestoreMaintenanceMode.kt:188-197,247-249` assumes `WorkerRegistry.scheduleAll` throws. Its unchanged implementation at `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt:103-117` catches each entry and returns Unit; `WorkerSpecScheduler.kt:166-186` can return `scheduled=false`, discarded by Unit registry lambdas (`WorkerRegistry.kt:48-58,75-84`). No confirmed seven-schedule success reaches the mode owner. Do not edit these out-of-fence worker files to hide the problem.
3. **[MAJOR] Two scheduling windows need a bounded solution, not an assumed PASS.** NORMAL is durably written at `RestoreMaintenanceMode.kt:180` before scheduling and publication at `197`; the same instance is blocked by `currentMode`, but a fresh instance reads NORMAL immediately. Cancellation at `193` can leave that persisted value behind. Conversely, work enqueued at `189` can start while `currentMode` is still non-NORMAL and stop flags remain set; `WorkerExecutionGuard.kt:280-327` blocks it. Most defaults retry; DailyBriefing uses SKIP_SUCCESS (`data/ai/worker/DailyBriefingWorker.kt:77`) and is midnight-delayed, so do **not** claim every immediate worker is permanently lost. Prove ordering/repeated recovery deterministically.
4. **[MAJOR] Post-destructive ordinary failures remain unsafe.** The costbackup swap catch observes `rollbackOk` only for residue cleanup (`DatabaseBackupRepositoryImpl.kt:1093-1108`), then always selects restart-required and finalizes failure, even if rollback failed. Reset generic failure similarly finalizes FAILED and selects restart-required (`2830-2836`) without proving a successful reset or verified recovery; delete Booleans at `2801-2803` are unchecked. These behaviors predate this lane but violate WI-3's required completion contract. Do not repair semantic-verifier queries; consume existing verification results correctly.
5. **[MAJOR] Required stage/finalization edges are not complete.** Costbackup does not publish STAGING/SWAPPING/VERIFYING maintenance modes alongside its journal stages (`910,1056-1070,1124`), and rollback paths do not durably transition the journal to ROLLING_BACK. Reset enters RESETTING_DATABASE directly (`2755`) and skips STAGED before SAFETY_BACKUP_CREATED (`2785`). Several pre-swap aborts exit NORMAL before `failJournal` (`889-890,921-922,943-944,963-964,995-996,1015-1016,1034-1035`); reset's safety-backup abort leaves PREPARING active (`2763-2775`). Mirror the approved existing states; do not invent a replacement machine.
6. **[MAJOR] Corruption/terminal preservation is incomplete.** `RestoreJournal.kt:129-138,165-168` uses tolerant field reads and only nonblank live-path checks rather than validating required JSON identity types; a malformed non-string path can be coerced into a nonblank string. `exists()` at `512` is outside read error handling. `deleteJournal():691-695` ignores failure. Startup COMPLETE/FAILED cleanup (`741-753`) can discard the only terminal record after a failed archive preservation/crash window. Corrupt active bytes must never be rewritten as a normal failure; terminal success/failure preservation must be retried or mapped to critical, not assumed complete.
7. **[MAJOR] Test evidence is incomplete by design and execution.** `DatabaseBackupRepositoryImplTest` was not updated; registry membership is not runtime scheduling; success-preservation fsync is not rename failure. No named test executed (section 5).

### Fence, privacy, cancellation, and weakening checks

- No implementation file is outside the ALLOWED fence; no schema/migration/guard/baseline/allowlist was changed. `DatabaseWriteBarrier` and `BankApiIntegration` remain unchanged; the bank fix is correctly caller-local.
- Added production lines introduce controlled failure strings and internal recovery paths, not new raw `e.message`, stack-trace logging, or throwable-to-log pass-throughs. Existing raw logging remains, e.g. `RestoreJournal.kt:522` and repository reset error text at `2764,2831,2847`; these are pre-existing CL-17 ownership, not a license for a CL-19 sweep. Preserve sibling sanitization when merging.
- Some incidental journal throwable logs disappeared when replacing swallowed errors with typed exceptions. Do not expand those incidental edits into CL-17's forbidden logging work.
- `BackupRestoreContractTest.kt:102-125` changes BACKUP_EXPORTING to deny writes, matching the existing write barrier and spec; this is **not assertion weakening**. No tests were removed. New scheduling tests are insufficient, not evidence of an intentionally weakened suite.
- WorkerExecutionGuard itself was not modified; the interaction risk is scheduling while its existing barrier still blocks. R-5 documents the concrete cancellation regression. The separate R2/CL-17 16-file mechanical sweep is not this R3/CL-19 lane's deliverable and is not claimed as passed.

## 5. Persisted validation evidence — read only

All paths below are relative to this worktree under `build/validation-runs/`. Result JSON, stderr/stdout and completion-marker presence were inspected. There is no `app/build/test-results/testDebugUnitTest` directory. **No test case from any of the eight named classes executed.**

| Run directory | Requested profile/filter | Recorded result | Evidence / limitation |
|---|---|---|---|
| vr-20260923-131324-837e9c83 | targeted-unit-test / `*RestoreJournalDurabilityTest` | FAIL, exit 1 | `stderr.log:1`: Gradle wrapper cache lock access denied; no test execution. |
| vr-20260923-131650-7489d8bf | targeted-unit-test / same | FAIL, exit 1 | `stderr.log:1`: then-local `RestoreMaintenanceMode.kt:280:40` PersistenceException constructor error. Repaired in reviewed source. |
| vr-20260923-132114-7f6ef47e | targeted-unit-test / same | FAIL, exit 1 | Five out-of-fence test compile failures; start/end fingerprints differ, so not stable-tree evidence. |
| vr-20260923-133947-aafafdd4 | compile | PASS, exit 0 | Production compilation only; no test execution. |
| vr-20260923-140217-f5a8a50d | targeted-unit-test / `*Restore*Test` | FAIL, exit 1 | `stderr.log:1-10,15`: same five files; `:app:compileDebugUnitTestKotlin` failed. |
| vr-20260923-140601-984e1911 | compile | PASS, exit 0 | Production task UP-TO-DATE; no test execution. |
| vr-20260923-141036-941fb222 | targeted-unit-test / `*RestoreJournalDurabilityTest` | FAIL, exit 1 | `stderr.log:1-10,15`; `stdout.log:1086-1088`: unit-test compilation failed before execution. |
| vr-20260923-141932-f82c2c80 | compile | PASS, exit 0 | `stdout.log:23,25`: compileDebugKotlin UP-TO-DATE, BUILD SUCCESSFUL. |

Each directory contains `result.json`, `stdout.log`, `stderr.log`, and `complete.marker`. The final targeted and compile records both name revision `4c1de21d23c9939bfed336a68d30e03b78ff6a86` and fingerprint `18cd47eb5ec45bcb548f8511d2764915d1b4b644456cb6e21b412d843679a767`; they ran over then-uncommitted implementation. This review does not certify that opaque fingerprint as the later committed HEAD. Rerun after completion changes. `*Restore*Test` is not a substitute for all eight explicitly named classes (notably the bank and barrier classes).

### R3 decisive audit: five out-of-fence failures

For **each** file, `git show ad412aec:<path>` was read around the error, its blob was compared to HEAD, and the referenced declaration was inspected at the base and compared to HEAD. All five test blobs and all relevant declarations are unchanged. This is source-level proof of pre-existing API/fixture mismatch, **not a claim that the base was compiled here**. No build configuration changed in this lane.

Common log: `build/validation-runs/vr-20260923-141036-941fb222/stderr.log` (same errors in the 132114 and 140217 runs). Paths below are under `app/src/test/java/com/yourname/expensetracker/` unless marked main.

| Test file | Verdict | Exact compile-error evidence | Base comparison / unchanged declaration |
|---|---|---|---|
| `data/privacy/KeystoreInstallationSecretHashingTest.kt` | **PRE-EXISTING** | stderr lines 1–4: `196:77 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'.`; `199:36 Argument type mismatch: actual type is 'String?', but 'CharSequence' was expected.`; same nullable-receiver error at `208:32,209:31`. | Base `195-209` dereferences the same nullable values. Base/main `domain/privacy/CloudPayloadRedactor.kt:49-51` defines `RedactedField.value: String?`; unchanged. Test blob `086f114215ebd075e7ff3cdd750d82e97d410e47` at both refs. |
| `domain/consistency/LegacyDataConsistencyCheckerTest.kt` | **PRE-EXISTING** | stderr lines 5–6: `74:13 Class 'LegacyDataConsistencyCheckerTest.FakeReceiptEventDao' is not abstract and does not implement abstract member: suspend fun deleteOlderThan(beforeMs: Long): Int`. | Base fake at `74-83` implements only getEventsForReceipt/insert; base/main `data/database/dao/ReceiptEventDao.kt:23` already requires deleteOlderThan. Test blob `effa20519d67c5b6b904183e70ffcceaf4e4f7a6` at both refs. |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt` | **PRE-EXISTING** | stderr line 7: `202:13 No parameter with name 'privacySettingsRepository' found.` | Base test passes that named argument at `202`; base/main `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:99-138` has rawPersistencePolicyResolver, not privacySettingsRepository. Neither file changed in this lane. |
| `ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | **PRE-EXISTING** | stderr lines 8–9: `189:19 Argument type mismatch: actual type is 'Result<Unit>', but 'Result<DatabaseImportResult>' was expected.`; `189:55 Classifier 'data class Success : DatabaseImportResult' does not have a companion object, so it cannot be used as an expression.` | Base `189` already uses `Result.success(DatabaseImportResult.Success)` without construction. Base/main `domain/backup/DatabaseOperationResults.kt:14` already defines `data class Success(val summary: DatabaseImportSummary)`. Test blob `db3db8eed4371e4c20b6a429981f6f5e36d73e73` at both refs. |
| `ui/screens/export/ExportOptionsViewModelPrivacyDenialTest.kt` | **PRE-EXISTING** | stderr line 10: `75:13 No value passed for parameter 'exportJobSerializer'.` | Base test constructor at `66-76` omits it; base/main `ui/screens/export/ExportOptionsViewModel.kt:85` already requires it. Test blob `e4900edf64368a47092900ba708dfacfe54fc7d1` at both refs. |

**Conclusion:** zero of these five compile failures references a symbol/signature changed by CL-19. They block test execution but are not this lane's regressions. Do not fix, delete, exclude, or relax these tests in CL-19. Human-owned prerequisite integration is required before the named validation gate can be earned.

## 6. Cross-lane flags — human merge order only

`git diff --name-only ad412aec..rp-2X-wip` was inspected for all four siblings. Snapshot refs: rp-20 `a9a95ea513e2a7156247a87bd95d8f5a9571b7bf`; rp-21 `ad6c55e88871888cab83301f89db76b73d25741a`; rp-23 `0b955ec1e2f06f371a98cd0ba4162dd0c3eb8595`; rp-24 `cbb0a54eff6a2d5bae1327e7c82501b8d1ba25d9`. Recheck refs before integration; no merge/cherry-pick/rebase was performed.

- **rp-20 → rp-21 → rp-22 is the required order.** All three touch `DatabaseBackupRepositoryImpl.kt`. rp-20 moves the encrypted-backup privacy preflight outside maintenance cleanup, handles Denied/FailClosed, and adds a test-constructor recorder parameter. rp-21 sanitizes overlapping restore/import/reset catch bodies; rp-22 adds typed durability exits in those same bodies. Preserve privacy-before-side-effects, cancellation propagation, bounded diagnostics, and checked durability together; resolving by taking a whole file from any lane would lose another contract.
- **rp-21/rp-22 actual overlaps:** `RestoreJournal.kt`, `DatabaseBackupRepositoryImpl.kt`, `AppStartupCoordinator.kt`, `RestoreJournalDurabilityTest.kt`, and `AppStartupCoordinatorRecoveryTest.kt`. The highest-risk conflict is rp-21's sanitized catches versus rp-22's throw-on-durability and explicit Corrupt result: do not restore swallow/null-on-corruption behavior, raw Throwable logging, or delete-on-failed-preservation. Preserve both lanes' tests.
- **Future overlap:** WI-8/9 must finally update `DatabaseBackupRepositoryImplTest.kt`; rp-20 already changes that class and its constructor seam. Base comparisons here are against rp-22, not against a post-merge tree.
- rp-20 also changes the five pre-existing failing tests. Flag that as a possible human integration prerequisite, not proof of an approved fix or permission to import those changes into this lane.
- rp-23 (notification) and rp-24 (groups) have no direct changed-file overlap with this lane in the inspected name lists. No semantic integration PASS is inferred from that.

## 7. DIRECT-FIX completion work items

Locations are at **current reviewed HEAD d3e8fa25**, not the original spec pin. Execute one bounded batch at a time. The source fence remains exactly spec lines 166–167; no new permission is granted by this review. Specifically: do not edit BackupVerifier/semantic queries, DatabaseReadBarrier/getDatabaseStats admission, CompositePrivacyGate/export privacy behavior, RestoreJournalImporter/raw diagnostic handling, backup image contracts, unrelated workers/receipts/notifications/recurring/currency/groups, schemas, migrations, guards, baselines, or the five out-of-fence tests. Do not redesign the eleven-mode/nine-state machine. Reviewer failure or a required out-of-fence change is a STOP/escalation, not scope expansion.

### WI-6 — Complete maintenance decoding and checked resume (continues WI-1/5)

- **Location:** `RestoreMaintenanceMode.kt:74-108,134-158,168-220,247-306`; named P7/barrier/startup/worker tests.
- **Defect:** Null/corrupt reads and resumption health are not durably represented; worker-scheduling invocation is confused with success.
- **Change:** Distinguish a genuinely absent first-install preference from a present null/invalid/unreadable value; publish/latch critical state for decoding/persistence failures and attempt the checked critical commit. Preserve the ordinary barrier as a consumer, not a second store. Complete a caller-local confirmation of the registry's seven default schedules; do not depend on exceptions escaping the unchanged best-effort registry. Keep the stop flag and writable flow blocked until successful scheduling is confirmed. Preserve the spec's NORMAL-commit-before-schedule rule and no-auto-clear-critical rule.
- **Acceptance:** Every known non-NORMAL blocks; only proven NORMAL admits. False/thrown transition/critical commits never schedule workers or reset stop flags. Unknown/blank/present-null/read errors expose critical flow and remain blocked across fresh instances/startups. Scheduling failure/cancellation leaves durable restart/critical recovery and no writable unscheduled state. Same-instance and fresh-instance behavior during the persisted-NORMAL window must be explicitly proven.
- **Constraint checkpoint:** The spec requires a successful NORMAL commit before scheduling, yet no writable unconfirmed resume. Do not silently add a mode, broaden worker admission, or redesign persistence to bridge this window. If the fresh-instance guarantee cannot be achieved inside the approved mode-owner contract/fence, stop and request a narrowly scoped design decision. Do not declare the current split disk/flow state a solution.
- **Tests:** Complete every WI-1 case from section 2 in `P7BugFixesTest`, `DatabaseBarrierTest`, `AppStartupCoordinatorRecoveryTest`; use `WorkerRestoreRegressionTest` for deterministic paused-scheduler/stop-flag observations, no sleeps. Use the actual mode/barrier for persistence tests, not only mocked enum returns.
- **Validation profile:** `targeted-unit-test` for those four exact classes, serialized by validation-runner after review; final `compile` in WI-11.

### WI-7 — Finish strict journal decoding and durable terminal semantics (continues WI-2/3)

- **Location:** `RestoreJournal.kt:110-169,504-576,636-695,733-778`; `RestoreJournalDurabilityTest.kt:45-254`; journal cases in `BackupRestoreContractTest.kt:223-344`.
- **Defect:** Required-field types/corrupt reads, archive failure/crash windows, cancellation, and fault-injection coverage are incomplete.
- **Change:** Validate required state/identity JSON types before coercion; preserve legitimate legacy missing startedAt handling but reject malformed present required values. Preserve valid reset's intentionally empty source/staged identities rather than blanket-requiring every optional path. Put file inspection/read failures under explicit fail-closed classification. Keep corruption distinct at recovery callers. Make terminal cleanup conditional on checked preservation; do not blindly delete active COMPLETE/FAILED when preservation is uncertain. Rethrow CancellationException before generic durability translation. Extend only the permitted internal RestoreJournal I/O seam to distinguish temp open/write, sync, rename, fallback write/sync, and deletion/preservation failure.
- **Acceptance:** Only proven absence yields NoAction. All specified corrupt families retain original bytes and map to critical startup. Valid PREPARING/STAGED and destructive recovery classifications remain intact. No transition failure returns success; no failed archive preservation deletes the sole active record. Interrupted terminal preservation is retried safely or remains critical. Original cancellation is observable.
- **Tests:** In `RestoreJournalDurabilityTest`, cover all WI-2/3 cases enumerated in section 2, independently for active/success/failure destinations, including rename-false with successful fallback and rename-plus-failed-fallback. In `BackupRestoreContractTest`, assert valid round-trip and terminal behavior without weakening assertions. Coordinate second-startup corruption tests with WI-10.
- **Validation profile:** `targeted-unit-test` for those two classes and `AppStartupCoordinatorRecoveryTest`; serialized, no custom test exclusions.

### WI-8 — Contain every repository durability failure and preserve cancellation (R-1/R-2/R-5)

- **Location:** `DatabaseBackupRepositoryImpl.kt:836-890,1259-1307,1750-1805,1946-2023,2732-2757,2824-2849`; `DatabaseBackupRepositoryImplTest.kt:263-288,637-674,742-849`.
- **Defect:** New throw paths bypass critical state/Result handling, escape catch bodies into NORMAL cleanup, and replace cancellation.
- **Change:** Bring journal creation and maintenance entry under bounded operation handling without moving destructive work ahead of durable journal writes. Handle JournalDurabilityException at the outer import boundary and inside failure-finalization/cancellation cleanup, not only the inner work try. Preserve the destructive-point fact across handlers. Persist/publish critical state before returning a durability failure; if that persistence also fails, keep the in-memory lock and surface the typed health failure. Never let a secondary cleanup error replace the original CancellationException; finish minimal fail-closed cleanup in the existing cancellation-safe pattern and rethrow it.
- **Acceptance:** Inject begin/transition/failJournal errors into costbackup/import/reset: no live close/copy/delete follows failed preconditions; no API unexpectedly escapes a normal failure instead of its Result (cancellation still throws); no post-destructive catch chooses `exit(false)`; original cancellation identity survives cleanup faults; critical fallback failure remains blocked. Retain evidence rather than attempting unsafe failure-record rewrites over corrupt bytes.
- **Tests:** **Update `DatabaseBackupRepositoryImplTest`**, not an unnamed test class. Add no-close/no-swap call-order checks and filesystem-byte assertions for all three operations, including failure inside the post-swap catch and failure inside cancellation cleanup. Extend `BackupRestoreContractTest` for the externally visible mode contract.
- **Validation profile:** `targeted-unit-test` for repository/contract plus journal tests; serialized.

### WI-9 — Complete destructive rollback/import/reset edges and evidence retention (R-2/R-4; WI-3 gaps)

- **Location:** `DatabaseBackupRepositoryImpl.kt:910,1054-1184,1208-1254,1277-1296,1866-2008,2586-2639,2753-2848`.
- **Defect:** Stage modes/ROLLING_BACK records are incomplete; failed recovery can be finalized as restart-safe; new durability exits discard asset-resume material.
- **Change:** Mirror approved maintenance/journal states at the actual transition points, using durable safety path then SWAPPING before closing/replacing/deleting live files. Track whether any destructive step occurred, independently of a possibly failed in-memory journal assignment. Before rollback, durably record/attempt ROLLING_BACK; use existing health/verification results to choose verified recovery + terminal failure + restart, or critical if recovery/record durability is uncertain. Correct the swap catch's ignored false rollback result and reset's unchecked destructive/failure outcomes. Finalize pre-destructive failure journals before NORMAL. After destructive uncertainty, retain the active record, safety/pre-restore files, and extraction workspace until verified recovery/finalization makes them disposable.
- **Acceptance:** No NORMAL after a destructive failure; no restart-safe claim after failed rollback; no deletion of the recorded extraction source on post-swap durability failure. Verified rollback still requires the appropriate restart/rescheduling contract. All successful restore/import/reset flows retain their intended ordering and data preservation; no semantic-verifier/query or schema changes.
- **Tests:** `DatabaseBackupRepositoryImplTest`: swap/verification failure with rollback success versus failure, each crossed with terminal-journal failure; all reset destructive outcomes; transition order in costbackup/import/reset; extraction bytes retained after ASSETS_RESTORING journal failure. `BackupRestoreContractTest`: modes/final journal states and fresh-start behavior. Reuse existing verification seams; do not relax their results.
- **Validation profile:** serialized `targeted-unit-test` for repository/contract/journal classes, followed by startup cases in WI-10.

### WI-10 — Make startup recovery fail-closed through finalization and verify real schedules (R-3; WI-2/5)

- **Location:** `AppStartupCoordinator.kt:114-207,363-440,611-633`; `AppStartupCoordinatorRecoveryTest.kt:153-237,494-709`; `WorkerRestoreRegressionTest.kt:73-124`; allowed mode-owner changes only as needed by WI-6.
- **Defect:** Failed asset rollback's critical marker can be skipped; corrupt/terminal journal failures can bypass intended UI handling; WorkInfo history is mistaken for resumed work.
- **Change:** Protect failed asset rollback at least as strongly as the swap branch at `159-168`: critical locking must occur even when failure-journal preservation throws, and the failure must not fall into clean-start reset on the next launch. Honor an existing absorbing critical state before automatic recovery transitions. Map corrupt/finalization errors into the existing recovery-required operational UI without an uncaught startup failure preventing that UI. Verified rollback uses the checked normal-exit/rescheduling path, only after checked journal finalization; successful asset completion stays restart-required with no ordinary scheduling.
- **Acceptance:** Failed rollback plus failed archive preservation remains critical on two fresh startups with bytes retained and zero normal schedules. Verified rollback produces the seven expected **active** unique schedules and NORMAL only after checked resumption; repeated recovery creates no duplicate active unique work. Asset-completion/critical branches schedule none. Read/commit/scheduling failures never bypass the blocked state.
- **Tests:** `AppStartupCoordinatorRecoveryTest`: full malformed-input/second-startup matrix, failed rollback + failJournal exception, successful and failed mode commits, archive-finalization failure, scheduling failures, and successful pending-asset completion. Change `getWorkInfosForUniqueWork(...).isNotEmpty()` to assertions that distinguish active work from CANCELLED/finished history; compare identities/counts after a second recovery. `WorkerRestoreRegressionTest`: execute the recovery/resumption interaction, not only registry-set equality.
- **Validation profile:** serialized `targeted-unit-test` for startup/worker/P7 classes; no production WorkerExecutionGuard/WorkerRegistry/WorkerSpecScheduler edits.

### WI-4a — Earn execution evidence for the correct bank change

- **Location:** `BankConnectionLifecycleCoordinator.kt:170-190`; `BankConnectionLifecycleCoordinatorOutcomeTest.kt:142-252`.
- **Change:** Preserve the current surgical production fix. Add explicit integration → barrier → chosen DAO order assertions if absent; retain original-outcome and zero-both-DAO assertions on denial. Do not add a DAO barrier or provider write path.
- **Acceptance/tests:** The named class actually executes allowed success/partial/failure families, denied blocked/success outcomes, and cancellation; original outcomes/timestamps remain correct. Static inspection of unchanged BankApiIntegration remains part of review, not a fabricated provider-integration test result.
- **Validation profile:** `targeted-unit-test -TestFilter '*BankConnectionLifecycleCoordinatorOutcomeTest'`, through validation-runner.

### WI-11 — External compile prerequisite, strict review, then serial named validation

- **Location:** The eight named test classes and existing `scripts/validation-runner.ps1` interface; no runner/source-fence expansion.
- **Change:** Human integrates/reviews an independently authorized fix for the five PRE-EXISTING out-of-fence test compilation blockers. CL-19 coder must not edit those files, remove them from compilation, weaken assertions, or merge/cherry-pick sibling changes without authorization. Until that prerequisite is satisfied, this gate remains BLOCKED, even if all in-fence edits are ready.
- **Acceptance:** Strict review of the full updated CL-19 diff passes before validation. The designated validation-runner is the only execution owner; confirm no live run, start one profile at a time, poll any RUNNING result rather than relaunch, and retain command/exit/result/log/marker/fingerprint evidence. All eight classes execute and pass after the actual final changes; then `compile` passes. No old compile PASS substitutes for these tests. Missing/stale/unknown results fail closed. Request human approval before any expensive broader Gradle profile.
- **Tests/profiles:** For each filter below, serially use `scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '<filter>'`; wait/poll via `-Action Wait -RunId '<returned id>'` before starting the next. These are instructions for the completion validation owner, **not commands executed by this reviewer**.

  1. `*P7BugFixesTest`
  2. `*DatabaseBarrierTest`
  3. `*RestoreJournalDurabilityTest`
  4. `*DatabaseBackupRepositoryImplTest`
  5. `*BackupRestoreContractTest`
  6. `*AppStartupCoordinatorRecoveryTest`
  7. `*WorkerRestoreRegressionTest`
  8. `*BankConnectionLifecycleCoordinatorOutcomeTest`

  Finally: `scripts/validation-runner.ps1 -Action Start -Profile compile`. Inspect actual named test-case results as well as runner result.json. Compile-only or a filter matching no executed test is not acceptance.

## 8. Deliverable and remaining risk

- Newly authored file: this review-completion artifact only. The coder's pre-existing untracked implementation report is included unchanged in the docs-only lane commit. No JOURNAL.md or other campaign file is edited.
- Validation by this reviewer: **NOT RUN — prohibited by role**. Historical validation is reported separately and accurately above.
- Completion/merge gates remain **FAIL / BLOCKED**: two critical newly exposed fail-open paths, three additional regressions, unfinished spec boundaries, no executed named tests, and human-owned out-of-fence compilation prerequisites.
- Human journal summary: `0/5 DONE-CORRECT, 5 regressions, out-of-fence verdicts all 5 PRE-EXISTING | rp-22-wip`. Use the actual September 24 review timestamp from the commit/return, not the request's September 23 template.

