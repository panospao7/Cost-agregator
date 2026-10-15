# Release 1.0 verification ledger

```text
Workflow / phase: release-1.0-assurance / Phase 2 / slice RG-01 (CL-19)
Source candidate: HEAD 279b7c92 (docs-only on top of 84d819be); app/ byte-identical
  to ea44631647e69a0dabacdc179dd094b292378b7e. Every agent pin check passed at start and end
  (PIN_OK, 0 untracked app files). Verifiers reported a clean `git status` throughout.
Comparison baselines: campaign 37601232; Wave 2 0b44cd4d; remediation commits
  2b6d79aa, f268dbf2, 61a509c0, b950fc77, 29c3fdf7, e896233e
Scope and write set: lead (release-orchestrator) writes this ledger; agents NONE
Evidence type: independent STATIC verification only. No runtime, no validation run.
Status: RG-01 PARTIAL (see "Limits")
```

## Agents (actual identity as observable)

| Step | Role | Requested | Actual (self-reported) | Independent of discovery? | Outcome |
| --- | --- | --- | --- | --- | --- |
| RG-01 attempt 1 | release-regression-auditor | Opus 5.5/xhigh | unknown | n/a | hit 45-turn limit, NO report; nothing used |
| RG-01a discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | PARTIAL report |
| RG-01b discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | PARTIAL report |
| V-01a | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5; effort not observable | yes (separate invocation, no shared context) | verified RG-01a |
| V-01b | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5; effort not observable | yes | verified RG-01b; raised N1 |
| V-01c | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5; effort not observable | yes (did not raise N1) | N1 CONFIRMED P2 (narrowed) |

## Original-ID closure (static)

| Old ID | Sev | Discovery | Independent verification | Fixing commits | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-07-003 | P0 | FIXED | VERIFIED-FIXED: writeMode/commit-before-publish (RestoreMaintenanceMode.kt:579-602); all writers check commit; invalid/unknown/read-failure state -> CRITICAL (531-575, 155-160); prefs name used only at :754 | f268dbf2, 61a509c0, 0b44cd4d | sentinel `File.exists()` I/O false-negative (needs both stores failing) |
| CA-P-07-004 | P0 | FIXED | VERIFIED-FIXED for the recovery journal: readJournalResult Absent/Valid/Corrupt (RestoreJournal.kt:583-601); strict fromJson; Corrupt -> CriticalRecoveryRequired (:845) -> BLOCKED (AppStartupCoordinator.kt:249-254); the remaining `readJournal()` null use (:504) cannot overwrite a corrupt file (writeJournal throws, :617-620) | f268dbf2, b950fc77, 0b44cd4d | success/failure archive readers return null (diagnostics loss only); RestoreJournalImporter internals NOT-VERIFIED |
| CA-P-07-005 | P0 | FIXED | VERIFIED-FIXED: writeJournal maps every failure to JournalDurabilityException (612-641); the journal transition precedes the destructive flag and DB close for restore 1164/1172/1173, import 1939/1941/1942 and reset 2863/2865/2866; durability/post-destructive failures go to lockCriticalForFailure | f268dbf2, 29c3fdf7, 0b44cd4d | no parent-dir fsync (pre-existing); finishCancelledRestore with throwing failJournal NOT-VERIFIED |
| CA-I-01-001 | P2 | FIXED (changed contract) | VERIFIED-FIXED: rollback -> exitCancellable(false) -> scheduleAll + WorkManager confirmation -> finalizeWorkerResume | 61a509c0, b950fc77, e896233e | contract change creates RG01A-1; no test asserts the worker schedules after a SUCCESSFUL rollback (WI-5); successful rollback skips scheduleStartupIfAllowed until next launch (pre-existing) |
| CA-P-10-001 | P1 | FIXED | VERIFIED-FIXED: barrier at persistOutcome:173, blocked -> no write, cancellation rethrown; every BankConnectionDao writer is barrier-gated; sync/connect are unreachable in release (BankFeatureAvailability from BuildConfig.DEBUG + requireStubMode); release-reachable disconnect is barrier-gated | 2b6d79aa | generic check-then-write TOCTOU (U-BARRIER-02, pre-existing) |
| RP-03 S2 R1 checksum/size replay | — | FIXED | VERIFIED-FIXED (proofs from checksums.json; size-bounded SHA copy; matchesProof on replay) | 0b44cd4d | checksums.json authentication NOT-VERIFIED (handoff RG-05) |
| S2 R2 crash-after-publish | — | FIXED | VERIFIED-FIXED (static) | 0b44cd4d | no dir fsync / check-to-move window: fails closed; NEEDS_RUNTIME (power loss) |
| S2 R3 fsync ignored (asset loops) | — | FIXED | VERIFIED-FIXED | 0b44cd4d | `runCatching { fd.sync() }` remains in copyFileSynced :2300 and safety-backup copy :2785. Present at 37601232; outside S2 assets; unassigned -> RG-05/HZ-03 |
| S2 R4a missing-source rebuild | — | FIXED | VERIFIED-FIXED | 0b44cd4d | — |
| S2 R4b journal-write discard | — | FIXED | VERIFIED-FIXED | 0b44cd4d | — |
| S2 R5 journal publication fallback | — | CHANGED/likely fixed (search-only) | NOT-VERIFIED | — | kill during replacement NEEDS_RUNTIME |
| S2 R6 filename exposure | — | FIXED | VERIFIED-FIXED for cited logs/diagnostics | 0b44cd4d | RestoreDiagnosticsSink exception-message sanitizer NOT-VERIFIED (handoff RG-02) |
| Barrier contract | — | unchanged, consulted | VERIFIED | — | — |

Prior CL-19 review work items R-1..R-5, RR3-R6, "critical checked too late" and
MAJOR-4: discovery reports them satisfied at the candidate (static). The verifier
did not separately re-check each WI. WI-6 design approval record: UNKNOWN.
Execution evidence for these tests at the candidate: none observed in this lane.

## Candidate findings

| ID | Claimed | Verifier | Final severity | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG01A-1 | P2 | CONFIRMED P2. User impact is P1-class when it fires; trigger rate is unproven | P2 (NEEDS_RUNTIME for frequency) | NEW-REGRESSION (changed contract, introduced in 61a509c0: before it the critical edge was practically unreachable) | CONFIRMED |
| RG01A-2 | P3 | CONFIRMED P3 | P3 | TEST-DEBT | CONFIRMED |
| RG01B-1 | P3 | RECALIBRATED. The cited admit branches are effectively unreachable with a published final. The real orphan paths are the generic catch after a published final (SQLite failure on the CAS UPDATE), startup markUnfinishedAssetTasksFailed, and startup safety rollback. Missing cleanup is partly defensible (ambiguous commit) | P3 | CHANGED residual; regression vs pre-existing UNKNOWN | RECALIBRATED |
| RG01-N1 | P2 suggested (raised by V-01b) | V-01c CONFIRMED P2, scope narrowed (cross-device pointers only); rowid "wrong image" side claim REFUTED (AUTOINCREMENT), with a theoretical P3 residual not registered | P2 | PRE-EXISTING (same deterministic name + collision at 37601232: RestoreJournal.kt:793, DBRI:1531,1547-1557) | CONFIRMED |

### RG01A-1 - permanent CRITICAL lock after worker-schedule confirmation failure

- Mechanism: single-pass WorkManager confirmation, no retry (RestoreMaintenanceMode.kt
  405-461; 10 s timeout at :766). The first failure or `scheduleFromSpec` exception
  leads to `enterCriticalRecoveryRequired(WORKER_RESCHEDULE_FAILURE)` (291-296, 319-324).
  The sentinel short-circuits to CRITICAL (507-514) and is never deleted in main
  source. `ensureCriticalIsNotCleared` (641-655) blocks later restores/exits. Startup
  returns BLOCKED. MainActivity.kt:437-449 shows "restart" with a no-op OK, and a restart
  does not help. data/rescue has no critical handling. allowBackup=false, so
  clearing app data (losing the healthy local DB) is the only exit.
- Triggers (verifier): legacy export `finally { runCatching { exit(false) } }` (DBRI :495, :796)
  where the export reports success and then the app locks; MaintenanceOperationRunner exits;
  pre-destructive aborts incl. wrong backup password (:985); startup resume after
  a restart-required restore (initialize :51-57); asset rollback (:471).
- Intent: CL-19 spec line ~31 requires "an explicit, separately reviewed recovery action"
  to clear CRITICAL, and none exists. WI-5 (~141) allowed a durable restart state, which
  the cancellation branch already uses (NORMAL+resumePending, :287-290). Test
  `initialize_contains_checked_resume_failure_and_keeps_recovery_UI_available`
  pins the CRITICAL behavior.
- Deciding runtime evidence (frequency only; mechanism statically confirmed):
  real-WorkManager instrumented/Robolectric run of exit(false) and resetCancellable()
  at cold start. Measure confirmation latency across repeated runs, plus one
  fault-injection run (failed getWorkInfosForUniqueWork future, or clock >10 s), then a
  rebuilt coordinator asserting CRITICAL persists and writes are denied. p99 far
  below 10 s with no exceptions supports P3; observed failures keep P2, or P1 if frequent.
- Smallest safe next step (for a future approved remediation batch, NOT authorized now):
  owner decision on the policy. Discovery proposal: for WorkerRescheduleException only, stay in
  the durable NORMAL+resumePending (write-blocked, retried on next launch) state
  instead of CRITICAL, and keep CRITICAL for persistence failures. Alternatively,
  specify the reviewed recovery action the spec requires.

### RG01A-2 - startup recovery tests mostly bypass the production entry

34 `checkRestoreJournal()` vs 3 `initialize()` calls in AppStartupCoordinatorRecoveryTest.
The test entry (AppStartupCoordinator 143-156) uses synchronous reset(), adds its own
critical marking and skips scheduleStartupIfAllowed. There is no positive-path initialize ->
NORMAL + schedules test, and resetCancellable() has no direct test caller. If the
coroutine success path regressed, every post-restore restart would become permanently
CRITICAL while the existing tests still passed.

### RG01B-1 - orphaned published restored_<id> file on failure paths (P3)

See table. Deciding test (if wanted): real Room + real filesDir variant of
DatabaseBackupRepositoryImplTest; `updateImagePathIfUnchanged` throws SQLiteFullException
once after admit -> task FAILED, file present and matching proof, pointer == expected;
then a second restore of the same bundle -> FAILED(TARGET_COLLISION).

### RG01-N1 - repeat restore of a foreign-device bundle silently loses receipt images (P2)

- Path: BackupRestoreViewModel.kt:227 -> restoreCostBackup -> restoreReceiptAssets
  (DBRI :1265). The bundle stores images as `files/receipts/{id}_{basename}` (:1446); the
  restored row keeps the source device's absolute UUID imagePath (originals are named by
  ReceiptOcrService.kt:882 / ReceiptAssetStore.kt:77). Each task records the row pointer as
  expectedImagePath, PENDING (:1568-1598). ReceiptAssetRecovery.begin builds deterministic
  `restored_<id>.<ext>` (:84) and fails TARGET_COLLISION for a PENDING task whenever that file
  exists (:96-97), before any proof/adopt check (adopt exists only for TEMP_WRITTEN, :225-234).
- Effect on a second restore of a bundle whose pointers do not resolve on this device:
  the row keeps pointing at a nonexistent path, so the image is missing while identical bytes sit
  unreferenced in restored_N. The warning goes to DatabaseImportSummary.receiptAssetWarnings,
  which no UI reads. BackupRestoreViewModel.kt:238-239 shows "Restore completed successfully!".
  Rows, amounts and links stay intact (image loss, not financial data loss).
- Knock-on: ReceiptAssetStore.generateBackupManifest (:228-233) and
  collectReceiptAssetsForBackup (DBRI :1448, :1455-1457) fail closed on a missing image, so
  later backups that include images return failure. Whether a no-image export remains
  available is NOT-VERIFIED.
- Refuted scope: a same-device bundle (including a backup made after a first restore) still
  resolves its pointer, so the image displays (silent TARGET_COLLISION only).
- Nothing deletes receipts on restore/reset, no orphan sweep (listReceiptFiles has no callers),
  no repeat-restore guard, allowBackup=false.
- Tests: collision tests use only non-matching foreign bytes and a mock DAO. The repeat-restore
  case is untested.
- Deciding runtime test (optional; static case deterministic): targeted-unit-test with real
  Room (Robolectric) + real filesDir. Restore receipt 5 from an extract with valid
  checksums -> pointer restored_5.jpg; reset row 5 to the absent original path; restore again
  with a new journal. CONFIRMS if FAILED(TARGET_COLLISION) with a missing pointer and an
  unreferenced restored_5.jpg (optionally: a later export with images fails with
  RECEIPT_ASSET_COLLECTION_FAILED). REFUTES if the task completes pointing at restored_5.jpg.
- Smallest safe next step (future approved batch only): for a PENDING task whose existing
  restored_N matches the bundle proof, adopt it via the normal CAS instead of collision; surface
  receiptAssetWarnings to the user.

## Retained refutations

- RG01-N1 side claim "SQLite rowid reuse shows another receipt's image": refuted as stated
  (scanned_receipts id AUTOINCREMENT, schema 151 ~:1388; ScannedReceipt.kt:50; PENDING never
  adopts an existing restored_N). Theoretical P3 residual via sqlite_sequence rollback across
  restore histories is not registered unless shown at runtime.

- RG01B-1 "row missing / pointer differs after publish" branches: refuted as
  practically unreachable (tasks created from the same fresh DB; barrier blocks gated
  writers; the only pointer mutation is the task's own CAS; duplicate receiptIds fail
  closed). An exhaustive app-wide ungated-writer audit is UNKNOWN.
- RG01A-1 "steady-state false negative" (would always fire): refuted. Periodic
  workers stay unfinished; merchant_key_backfill retries under a non-NORMAL mode and
  stays ENQUEUED; ai_daily_briefing is delayed to midnight (narrow midnight edge not verified).

## Limits / pending scope for RG-01

- Not read/verified: DatabaseBackupRepositoryImpl 976-1149, 1225-1339 (verify/asset
  section beyond scoped ranges), 1825-1991 (import staging); RestoreJournal 236-575
  event readers; RestoreJournalImporter internals; AppStartupCoordinator 284-427.
- S2-R5 and power-loss/kill behavior: NEEDS_RUNTIME. androidTest has no
  connected coverage for any RG-01 item.
- Test execution at the candidate: NOT RUN in this lane. The S2 test runs on record
  (2026-09-28) predate 0b44cd4d.
- Handoffs: RG-02 (RestoreDiagnosticsSink sanitizer; toDiagnosticsJson callers;
  scheduleAll re-enqueues location/receipt/briefing workers regardless of privacy
  toggles, so confirm runtime self-gating); RG-05/HZ-03 (checksums.json authentication;
  `runCatching { fd.sync() }` in DB/safety copies at :2300/:2785).

---

# Slice RG-02 (CL-18 + CL-17 P0/P1 + A2 + A6 + RG-01 handoffs)

```text
Approval: user "i approve the next slice" (2026-10-14), interpreted as RG-02 only (RG-04 not approved)
Source candidate: HEAD 279b7c92; app/ == ea446316. All four agents: PIN_OK start+end, 0 untracked app files
Evidence type: independent STATIC verification only. No runtime, no validation run.
Status: RG-02 PARTIAL (static; runtime probes pending; CA-P08-003 still open)
```

| Step | Role | Requested | Actual (self-reported) | Independent? | Outcome |
| --- | --- | --- | --- | --- | --- |
| RG-02a discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | PARTIAL |
| RG-02b discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | FAIL (P08-003 partial) |
| V-02a | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-02a |
| V-02b | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-02b. The harness briefly reported "background work still running", then completed (one extra tool call). No writes; PIN_OK |

## Original-ID closure (static)

| Old ID | Sev | Discovery | Independent verification | Fixing commits | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-07-001 | P1 | FIXED | VERIFIED-FIXED: runCostBackupExport blocks on blocksExecution() (Denied+FailClosed) before enterAndDrain (DBRI :620-652); cancellation rethrown; debug exportDatabase throws in release (:375); ExportOptionsViewModel :262-279 also blocks; no release export path is gated only on Denied | 83918e0f, 577c9e56 (introduced R-1), 984596ed (fixed R-1) | AccountingExportRepository unwired; auditLogger.logDecision outside the gate try (fails closed for backup) |
| CA-P08-001 | P1 | FIXED | VERIFIED-FIXED: CompositePrivacyGate :29-30 rethrows cancellation; no other domain/privacy gate swallows | b10af5f6 | anonymous provider stub gates not read |
| CA-P-07-006 | P0 | FIXED (contract changed) | VERIFIED-FIXED: reset persists reason-code name; failJournal/toJson/fromJson/importer normalize to the 6-code allowlist; asset-task errors are constants and not imported; event metadataJson not persisted to Room | 71d4bb97, 18d2551a, 61eaf224, 29c3fdf7, 5903c891 | event excMsg -> RG02A-1 |
| A2 denial contract | — | CHANGED, consistent | VERIFIED: bounded PrivacyDeniedException.reasonCode; Denied->ENCRYPTED_BACKUP_DISABLED, FailClosed->PRIVACY_GATE_FAILURE | 0b44cd4d or a1c66173 | A2 record's privacy-guardian approval still PENDING; unverified P3 V02A-N2 |
| CA-E-04-001 | P0 | FIXED | VERIFIED-FIXED: engine adds the constant "MISSING_RATE" only, with no Log/Timber; CurrencyConverter :318 logs a constant. Release logging: Timber planted only in debug (AppStartupCoordinator :681-684), no assumenosideeffects, so android.util.Log still emits in release | 8409e098, 18d2551a | unused `import android.util.Log` (:3); home-currency read failure silently defaults to EUR (money handoff) |
| A6 conversionWarnings | — | FIXED, unreachable | VERIFIED (grep): budget consumers ignore warnings | — | callers drop isPartial (money handoff) |
| CA-P08-003 | P1 | STILL-OPEN (partial) | PARTIAL: CloudDashboardBriefing, CloudReceiptAssist and CloudQueryInterpretation fixed; CloudDedupeJudgeService :197-202 and CloudReviewExplanationService :209-214 still return ParseError(e.message)/Unknown(e.message) | 1b084470, 18d2551a, b3c2404f | -> RG02B-1 |
| RG-01 handoff: diagnostics sanitizer | — | UNKNOWN | -> RG02A-1 | — | — |
| RG-01 handoff: toDiagnosticsJson | — | REFUTED as export path | VERIFIED tests-only -> RG02A-2 | — | — |
| RG-01 handoff: worker self-gating | — | location gated; receipt_matching not | VERIFIED, wider than claimed (receipt_matching, warranty_expiration_check, bill_reminder_periodic) | — | -> RG02B-2, RG02B-3 |

## Candidate findings (RG-02)

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG02B-1 | P1 | RECALIBRATED to P2. Real path: Android org.json messages echo the parsed model output, which reaches ai_artifacts.errorMessage and ReviewViewModel :368-370/:725-731. It is opt-in (cloud AI and features default off), unlikely to trigger, and the leaked text is model output already stored on success. ExportAnonymizer nulls errorMessage | P2 | STILL-OPEN residual of CA-P08-003 | CONFIRMED; NEEDS_RUNTIME on device (JVM org.json differs) |
| RG02A-1 | P2 | RECALIBRATED to P3. The sanitizer is a blocklist without SQL/amount patterns and breaks the AGENTS.md rule as written, but realistic messages are schema-level and nothing displays them. They do carry into encrypted .costbackup (diagnostic tables not scrubbed in redacted mode) | P3 (P2 if probe shows user text) | PRE-EXISTING (identical at 37601232) | RECALIBRATED; NEEDS_RUNTIME probe |
| RG02A-2 | P3 | CONFIRMED | P3 | TEST-DEBT (toDiagnosticsJson tests-only) | CONFIRMED |
| RG02B-2 | P2 | RECALIBRATED to P3. The NOTIFICATION_CAPTURE toggle cancels receipt_matching, warranty_expiration_check and bill_reminder_periodic, but scheduleAll re-arms them on every start/restore and the guard only enforces requiredCapabilities. No privacy egress is shown. Product decision on the mapping is needed (turning the toggle off cancels reminders until restart) | P3 | PRE-EXISTING | RECALIBRATED |
| RG02B-3 | P3 | CONFIRMED: briefing generation ignores proactiveBriefingsEnabled (only delivery checks it) | P3 | likely PRE-EXISTING | CONFIRMED; product decision |
| RG02B-4 | P2 | RECALIBRATED to P3, partly refuted: HybridDedupe.safeExecute and OnDevice* providers swallow cancellation; JudgePendingReviewDuplicateUseCase lacks a rethrow; cloud try blocks have no suspension points; Room suspend upsert re-raises | P3 | PRE-EXISTING | RECALIBRATED; NEEDS_RUNTIME unit test |
| RG02B-5 | P3 | CONFIRMED: LocationBackfillWorker throwables via android.util.Log (:71/:80/:111) reach release logcat; token anonymized; feature off by default | P3 | PRE-EXISTING | CONFIRMED |

Deciding runtime tests (authoring and runner execution need separate approval):
- RG02B-1: androidTest with a MockWebServer Gemini reply containing brace-balanced invalid JSON with a sentinel merchant;
  real JudgePendingReviewDuplicateUseCase + Room; assert the sentinel is absent from ai_artifacts.errorMessage and the returned Error.
- RG02A-1: JVM test of sanitizeExceptionMessage with a path containing spaces/amount ("Coffee Shop 12.50 EUR.jpg: open
  failed"), a real SQLite "while compiling" message, and a require() message with a decimal. If user text survives,
  raise to P2 and check whether stored asset names can contain user text.
- RG02B-4: cancel during the cloud dedupe call; expect CancellationException, no on-device fallback, no FAILED artifact,
  no cloud audit row.

## Unverified possible items raised by verifiers (need their own refutation; NOT registered)

- V02A-N1 (P3): the 6-code allowlist collapses every restore failJournal constant to UNKNOWN_ERROR (diagnostic fidelity).
- V02A-N2 (P3): FailClosed ENCRYPTED_BACKUP displays "Encrypted backup is disabled." (strings.xml:827).
- V02B-N2 (P3): OnDevice* AI providers swallow cancellation (overlaps RG02B-4).
- V02B-N4 (test debt): JVM tests use JSON-java while Android ships its own org.json, so tests claiming "no raw text"
  on parse paths can pass while the app leaks.
- V02B-N3/N5: unused Log import; capture toggle cancels reminders (folded into RG02B-2).
- Out-of-scope lead: ReviewScreen :238 -> getDebugExportData -> ReceiptDebugExporter release reachability (I-05/RG-15).

## Limits / pending scope for RG-02

- NOT-READ: DBRI export body 657-786, legacy import 1743-2080 and parts of restore 930-1500; provider parseResponse
  bodies; OnDeviceReviewExplanationService; AiArtifactDao/entity; library consumer ProGuard rules.
- No execution at the candidate of the relevant suites (DatabaseBackupRepositoryImplTest, CompositePrivacyGateTest,
  BackupRestoreViewModelPrivacyDenialTest, RestoreJournalImporterFailureTest, SharedExpenseBudgetOffsetEngineTest,
  Cloud*ServiceTest, CloudDedupeJudgeServiceTest).
- Handoffs: money slice (EUR default on home-currency read failure; dropped isPartial); cancellation slice
  (BackupRestoreViewModel.restoreBackup :228 catch Throwable, pre-existing; BudgetViewModel :173); RG-10 (pre-retry
  payload-preparation exceptions); RG-15 (journal files vs bundles; CL-17 B-1/R-2); P-12 (ExportOptionsViewModel
  audit-failure escape :262); P-07 (export cleanup always calls exit(false) even if enterAndDrain failed, which is
  another RG01A-1 lock trigger).

---

# Slice RG-04 (CL-29 group split/settlement + A7)

```text
Approval: user approved "RG-04: CL-29 group split and settlement (P0 E04-002) plus A7" (2026-10-14).
  RG-03 NOT approved (only quoted). CA-E-04-004 (tax currency) reassigned to RG-09.
Source candidate: HEAD 279b7c92; app/ == ea446316. All four agents: PIN_OK start+end, 0 untracked app files
Evidence type: independent STATIC verification only. No runtime, no validation run.
Status: RG-04 PARTIAL (static; runtime probes pending)
```

| Step | Role | Requested | Actual (self-reported) | Independent? | Outcome |
| --- | --- | --- | --- | --- | --- |
| RG-04a discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | PARTIAL |
| RG-04b discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5 | n/a | PASS |
| V-04a | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-04a |
| V-04b | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-04b |

Commit-ID correction: the CL-29 docs cite lane SHAs that do not exist in this repo (64cd2302,
37be9d9c, da0173f0; `git cat-file` invalid). The landed commits are 9dc2d1ca (WI-2 signs) and
5624b715 / 8f86b85f / 52724c0c (merge cdf501d4) for WI-1/WI-3; A7 landed in 0b44cd4d.

## Original-ID closure (static)

| Old ID | Sev | Discovery | Independent verification | Fixing commits | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-E-04-002 | P0 | FIXED on new-entry path | VERIFIED-FIXED on the only live writer chain: Screen (active members, payer gating) -> VM (fresh active read, payer + split checks, invokeAtomic with date=now) -> GroupsRepositoryImpl.validateActiveMembersAndCustomSplit (exact member-ID match, canonical JSON, exact Long-cents sum) -> coordinator createSystemExpenseAndLinkToGroup in one withTransaction via TransactionLifecycleCoordinator.createExpenseDbOnlyV2 (legal path), rollback, cancellation rethrow. Other writers dormant (SharedExpenseManager never injected; AddGroupExpenseUseCase.invoke uncalled); FinancialRescueCoordinator copies rows on recovery without validation | 5624b715, 8f86b85f, 52724c0c | RG04A-1; the coordinator does not re-check exact member set/sum inside the transaction (unverified V04A-N1); pre-fix rows never repaired |
| CA-E-04-003 | P3 | FIXED, zero-caller | VERIFIED-FIXED (hand check Alice/Bob 100 -> settle 50 -> 0/0); GroupBalanceCalculator has no production caller | 9dc2d1ca | none at release |
| A7 settlements in balances | — | FIXED in source | VERIFIED-FIXED on the shipped VM path (GroupSettlementBalancePolicy, BigDecimal, same-currency + RECORDED/COMPLETED). No visible effect in shipped app, because nothing writes group_settlements (only writer SettlementCalculator.recordSettlement is never injected; never called in history). Full-DB backup would carry rows if any existed | 0b44cd4d | one-sided application for a member missing from the map (corrupt data only; V04B-N1); one bad row hides all groups (fail-closed, unreachable) |
| CL-29 retained O-1 (P1 in review) | P1 | STILL-OPEN in source, unreachable normally | CONFIRMED not reachable under normal clocks (date=now; leftAt=now); clock-rollback residual folded into RG04A-1 | — | — |
| CL-29 WI-1/WI-2/WI-4 | — | FIXED (source) | consistent with the above | — | named-test execution UNKNOWN (only aggregate app-check vr-20261014-122950-8e76f251, skip list not checked) |
| CL-29 WI-3 | — | mostly addressed | real-Room tests GroupTransactionCoordinatorTest :959-1032, GroupsRepositoryImplTest :375/:481 | 52724c0c | full Repo boundary matrix not read |
| CL-29 WI-6 | — | STILL-OPEN (scope decision) | no decision record found | — | owner decision on whether the coordinator should reject instead of equal-fallback |

## Candidate findings (RG-04)

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG04A-1 | P1 | RECALIBRATED to P2. Chain confirmed: the Repo preflight uses all active members, while the coordinator/SplitCalculator keep only members with joinedAt <= date; the parser then returns Invalid and the split silently falls back to equal; myShareAmount is stored as the equal share; balances erase the requested unequal amounts (EUR 90, A10/B80 -> A share 90; with B as payer, A owes 90 not 10). No date picker, so the trigger needs a wall clock that moved backwards (manual change, corrected set-ahead clock, DB restored from a device with a clock ahead) | P2 | PRE-EXISTING (split/parser/coordinator unchanged since 37601232) | CONFIRMED (P2); NEEDS_RUNTIME deciding test defined |
| RG04A-2 | P3 | CONFIRMED: LEGAL_PATHS.md :664-670 names the dormant SharedExpenseManager.addExpense (validates against departed members, skips the preflight); the live path is undocumented | P3 | DOC-DRIFT / latent | CONFIRMED |
| RG04A-3 | P3 | RECALIBRATED P3: raw coordinator Error text (internal IDs, dedupeKey with amount/merchant/currency, exception.message) shown in the groups UI error state; on-device only, not logged or persisted | P3 | PRE-EXISTING | RECALIBRATED |
| RG04A-4 | P2 | REFUTED: a double tap is possible, but TLC's in-transaction currency-aware duplicate check (±5 min window) and the unique dedupeKey index block the second insert, so no group row is written. Residual UX (full-screen duplicate error) folds into RG04A-3 | — | — | REFUTED |
| RG04B-1 | P2 | CONFIRMED P2: "Settle" button (SettlementPlanSection) only updates `remember` state, with no callback or persistence. It shows "settled up" while the member cards still show the debts; ticks are lost on balance change, navigation, rotation, process death. Advertised as "one-tap settle" (CHANGELOG :74, ARCHITECTURE :1709). The app has no way to record a settlement at all | P2 | PRE-EXISTING (fe53c813, before pin) | CONFIRMED |

Deciding runtime test (authoring + runner execution need separate approval):
- RG04A-1: Robolectric + in-memory Room (model on GroupTransactionCoordinatorTest :989). Alice current user joinedAt
  TEST_DATE-10_000; Bob active joinedAt TEST_DATE+5_000; createSystemExpenseAndLinkToGroup(UNEQUAL {A:10,B:80}, 90.0,
  paidBy=A, date=TEST_DATE). CONFIRMED if Success with myShareAmount == 90.0 and calculateSplitAmounts == {A:90.0};
  REFUTED if Error or myShareAmount == 10.0.
- RG04B-1 (optional, UX): Compose UI test showing the settlement plan reappears after a balance change, with zero
  group_settlements rows.

Product decision required for RG04B-1 (1.0 scope): relabel/remove the UI-only "Settle", or build a legal settlement
writer through GroupTransactionCoordinator (planner-first, out of current scope). Docs (CHANGELOG/ARCHITECTURE) overstate it.

## Unverified possible items (need their own refutation; NOT registered)

- V04A-N1 (P3): member-set/sum preflight happens before the coordinator transaction; a departure committing in
  between could store JSON naming a departed member (equal fallback). Likely impractical from the modal dialog.
- V04B-N1 (P3 hardening): settlement naming a member outside the balance map is applied one-sided; negative/zero
  amounts accepted by the policy. Unreachable today (no writer).
- FinancialRescueCoordinator.importGroupExpenses copies currency/rows without validation (rescue path; RG-15/P-07).

## Limits / pending scope for RG-04

- NOT-READ: coordinator addExpenseToGroup/addExpenseWithLink bodies (dormant callers only), SettlementCalculator
  solver 200-448 (no production caller), SplitCalculator 127-300 custom splits beyond scoped ranges, several test
  bodies (GroupsRepositoryImplTest matrix, VMTest :520/:543/:651, SplitCalculatorGoldenTest, SettlementCalculatorTest).
- Display rounding for currencies without 2 decimal places (screen hard-codes 2 fraction digits): handoff to E-01/I-05.
- Restore/rescue of group rows: handoff to P-07/RG-15.

---

# Slice RG-03 (CL-01 notification capture privacy + CL-02 + CL-03 + A1)

```text
Approval: user approved RG-03 by name (2026-10-14).
Source candidate: HEAD 279b7c92; app/ == ea446316. All four agents: PIN_OK start+end, 0 untracked app files
Evidence type: independent STATIC verification only. No runtime, no validation run.
Status: RG-03 PARTIAL (static; named test execution and runtime probes pending)
Milestone: all 7 original campaign P0s are now independently verified FIXED (static only).
```

| Step | Role | Requested | Actual (self-reported) | Independent? | Outcome |
| --- | --- | --- | --- | --- | --- |
| RG-03a discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | PARTIAL |
| RG-03b discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5 | n/a | PARTIAL |
| V-03a | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-03a |
| V-03b | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-03b |

Commit-ID correction: CL-01 docs cite lane SHAs not in this repo (55d245ca, cbf2a011). Landed: 7d021502
(production fix), 8abf4db8, 0bbbdb48, a890a8cf (tests), merge 1647cb7e. CL-02/CL-03: a1c66173. A1: 3b7cabfe.

## Original-ID closure (static)

| Old ID | Sev | Discovery | Independent verification | Fixing commits | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-01-001 | P0 | FIXED | VERIFIED-FIXED: deferred branch (NotificationCaptureService :456-541) runs privacy gate, fresh settings/toggle and fresh blocked-package checks, each 300 ms-bounded and fail-closed, BEFORE extraction and the captureForRetry handoff; cancellation rethrown. Coordinator DO_NOT_STORE persists nothing; non-raw modes keep content only encrypted. Worker rechecks privacy before payload load and purges on denial. No consent-bypass path found (live path double-gated; refreshActiveNotifications only from the debug VM; repairer adds no content; processAndSave only after gates) | 7d021502 | RG03A-1, H-1, H-2; consent revoke does not proactively purge stored rows (OBS-REVOKE, unverified) |
| CA-P-01-004 | P2 | FIXED | VERIFIED-FIXED wiring (service :571 passes combinedBody). Behavior concern RECALIBRATED: the worker already filtered on combinedBody at 37601232, so only DO_NOT_STORE users see a change; deny-before-allow filter precision (account-info words in extended fields) is pre-existing, frequency UNKNOWN | 7d021502 | NEEDS_RUNTIME corpus test of NotificationFilter.decide with sanitized bank notifications |
| CL-01 review WI-1..WI-4, R-1 | — | FIXED in tests | VERIFIED real oracles (no-extraction/no-handoff verify(exactly=0), coVerifyOrder, real parent cancellation; FallbackTest slot capture) | 0bbbdb48, a890a8cf | captureGate self-heal mocked; no test for captureForRetry throwing; timeout tests pin misleading codes; WI-5 named execution UNKNOWN (DeferredPolicyTest has MockK+suspend hang history) |
| CA-P-01-002 | P2 | FIXED | VERIFIED-FIXED: per-row "notification-intake-${id}" + KEEP in captureForRetry and recoverPending; the worker processes only its intakeId | a1c66173 | live path name mismatch -> RG03B-1 |
| CA-P-01-003 | P2 | FIXED | VERIFIED-FIXED: zero-row claim on not-yet-due FAILED_RETRYABLE -> RetryableWorkerException -> Result.retry(); projection carries nextAttemptAt; claim SQL filters status/nextAttemptAt | a1c66173 | other zero-row statuses still return success -> RG03B-2; tests use a mock DAO |
| CA-P-01-005 | P2 | FIXED | VERIFIED-FIXED: try covers reload/privacy/payload load/decrypt; cancellation and checkpoint-blocked rethrown; final failures purge and throw a cause-free code | a1c66173 | handler checkpoints/writes can still leave PROCESSING -> RG03B-2 |
| A1 CANCEL-01 x3 | — | FIXED | VERIFIED (spot-check): DataRetentionWorker :438-446, TLC :2423-2430, AppStartupCoordinator :490-500 rethrow cancellation; no cancellation baseline entries for these files | 3b7cabfe | the sites already had typed CE catches at ad412aec (static-pattern finding) |
| Wave 4 intake worker delta | — | no regression | agreed: harmless behavior notes (CLAIMED_METADATA_UNAVAILABLE now FAILED; preprocess/processing retry-count mismatch) | a1c66173 | Wave 4 independent strict review still PENDING per wave4-checkpoint handoff :52 (not replaced by this audit) |

## Candidate findings (RG-03)

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG03A-1 | P3 | CONFIRMED: deferred branch is reached only after the gate's 300 ms self-heal failed; up to three more 300 ms reads; any timeout -> terminal drop (no row, no retry, no later re-capture); timeouts recorded as PRIVACY_DENIED/BLOCKED_PACKAGE instead of DEFERRED_STORAGE_POLICY_UNAVAILABLE | P3 (P2 if runtime shows non-trivial drop rate) | CHANGED by 7d021502 (deliberate fail-closed) | CONFIRMED; NEEDS_RUNTIME cold-boot latency/drop count |
| RG03-H1 (was H-1) | historical | RECALIBRATED P3: the deferred branch skips the filter/deduper/ignored packages, so under STORE_RAW (product default) it persists raw title/text/combinedBody (incl. MessagingStyle text despite the sensitive-extras strip) of ANY non-blocked app, including chats, until the worker filters and purges (best-effort) | P3 (P2 if the policy owner rules it breaks the contract) | PRE-EXISTING | RECALIBRATED; owner policy decision |
| RG03-H2 (was H-2) | historical | CONFIRMED mechanism: the captureForRetry handoff is not in a try; serviceScope has no CoroutineExceptionHandler; SQLite/WorkManager exceptions escaping captureForRetry (insert only catches DatabaseAccessBlockedException; enqueue-failure write unguarded) would crash the app process | P3 (P2 if a realistic trigger is shown) | PRE-EXISTING | CONFIRMED; NEEDS_RUNTIME |
| RG03B-1 | P3 | CONFIRMED: live work name "$intakeId" vs recovery "notification-intake-${id}" lets recovery start a duplicate chain; the pre-claim max-attempts check + status-less markFinalFailure can overwrite PROCESSED with FAILED_FINAL (expense kept, audit wrong); needs attempts == maxAttempts (narrow) | P3 | PRE-EXISTING | CONFIRMED |
| RG03B-2 | P2 | RECALIBRATED to P3 (borderline P2): a row left PROCESSING after claim (process death, WorkManager stop, checkpoint block, failing handler write) makes the next run return success; recovery only at process start/listener connect after the 10-min stale threshold, which can take hours/days in the long-lived START_STICKY listener. Capture delayed, not lost (unless NEW-A) | P3 | PRE-EXISTING (same logic at 37601232) | RECALIBRATED; optional Room+TestDriver runtime test |

## Unverified possible items (need their own refutation; NOT registered)

- V03B-NEW-A (could raise RG03B-2 to P2): if the stuck claim was the 5th attempt, release keeps attempts=5 and the next run
  marks MAX_ATTEMPTS_EXCEEDED and purges, so the capture is LOST although the attempt never finished.
- V03B-NEW-B (low confidence): retention purgeRawPayload has no status filter; a pending STORE_RAW row older than the cutoff
  would be nulled before processing.
- V03B-NEW-C: concurrent RG03B-1 variant can purge the payload under an in-flight chain (PAYLOAD_UNAVAILABLE_PRIVACY). Very narrow.
- V03A-NEW-RG03-A (low confidence): NotificationTextParts.extract getCharSequence calls are outside any try; a
  BadParcelableException from third-party extras could take the same crash route as RG03-H2.
- V03A-OBS-REVOKE: turning notification capture off does not purge stored intake rows/payloads proactively; completed
  STORE_RAW rows remain until retention (30 days default). Policy question.

## Limits / pending scope for RG-03

- No execution at the candidate of NotificationCaptureServiceDeferredPolicyTest/FallbackTest (hang history; request
  serially), NotificationIntakeWorkerTimeoutTest, WorkerClaimRepairDaoTest. No real-Room worker test for the claim paths.
- NOT-READ: NotificationTransientPayloadCrypto.decrypt exception types; NotificationTextParts body; filter beyond :240;
  review-queue persistence of raw text after parsing (P-02/P-08 horizontal).

---

# Slice RG-05 (Wave 5 remediation delta 3e8377e4..ea446316: first independent review)

```text
Approval: user "i approve the next slices" (2026-10-14) -> RG-05 + RG-17. F4/F5 remain in RG-06 (NOT approved).
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents: PIN_OK start+end, 0 untracked app files
Evidence type: independent STATIC verification only (verifier used pure-Python IEEE double arithmetic). No runtime.
Status: RG-05 PARTIAL (static; F3/F6/F7 not re-verified by the verifier; runtime pending)
```

| Step | Role | Requested | Actual (self-reported) | Independent? | Outcome |
| --- | --- | --- | --- | --- | --- |
| RG-05a discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5; effort not observable | n/a | FAIL (RG05A-1 candidate) |
| RG-05b discovery | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5 | n/a | PARTIAL (no P0-P2) |
| V-05 | release-finding-verifier (fresh, both halves) | Opus 5.5/xhigh | Opus 5.5 | yes | verified |

Lead correction: RG-05b reported "G1 human written confirmation still OPEN" from handoff lines 42-46 only. The same
handoff's closure section (owner approval and authorized publication, 2026-10-14) records explicit owner confirmation
of the five removals and four grants. G1 = HUMAN-DECIDED (confirmed after the fact), not open.

## Closure (static)

| Item | Discovery | Independent verification | Notes |
| --- | --- | --- | --- |
| F1 exact per-goal cap | FIXED | VERIFIED-FIXED: Math.nextDown step-down (use case :457-465) + identical IEEE expression in coordinator check (:264) and SQL CAS; terminates; 200k random pairs -> at most 1 step, 0 false rejections, 0 overshoot | every F1 case now ends 1 ULP below target -> RG05A-2 |
| F2 + F2-GOAL-DELETE month bound | FIXED | VERIFIED-FIXED: month consumed if marker overlaps OR accepted plan OR 'sweep' ledger row; new identity rejected before any write; marker PK monthStart in the same transaction (rollback on conflict); no goal FK; no marker deletion; Room serializes writers; only other goal increment is the manual path (source "manual") | static; Room serialization not runtime-tested |
| F3 visible stale outcome | FIXED | NOT-VERIFIED by V-05 (budget) | discovery: VM early exits call rejectStaleSweep |
| F6 normalized P90 | FIXED | NOT-VERIFIED by V-05 | discovery: complete-quality normalized input; hand check P90 = 910 |
| F7 coordinator assertion | FIXED | NOT-VERIFIED by V-05 | discovery: coVerify(exactly=0) |
| F8 explicit ledger currency | FIXED | VERIFIED-FIXED (ledger/plan constructors only in coordinator, pass recommendation.currency) | other entities keep Kotlin "EUR" defaults (outside F8 scope; money slice) |
| F10 safe-pool recheck | FIXED | VERIFIED-FIXED (producer and coordinator use the same expression; coerce only when negative -> below MIN_SWEEP) | — |
| v151 migration SQL vs schema | VERIFIED | VERIFIED: MIGRATION_150_151 CREATE == 151.json:2066 createSql | additive; 71 -> 72 entities |
| Migration chain 145 -> 151 | VERIFIED (RG-05b) | not re-checked | no gaps; no destructive fallback |
| Seeding semantics | VERIFIED (RG-05b) | consistent | clock check before DDL; same TimeProvider binding as coordinator; system time zone half-open month |
| Backup tiers/restore | VERIFIED | VERIFIED: TIER_1_EXACT; required for v>=151; v<151 expects 1 marker | — |
| Writer grant 429 + four G1 grants | VERIFIED (RG-05b) | not re-checked | exact, method-scoped; single production writer |
| G1 cancellation removals x5 | VERIFIED real fixes (RG-05b) | not re-checked | removals landed in d5d4d97e; baseline 118 -> 117 -> 112; remaining runCatching{withTimeout} in ComputeDashboardWidgetsUseCase :1162 is pre-existing G-CANCEL-02 baseline debt |

## Candidate findings (RG-05)

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG05A-1 | P2 | CONFIRMED P2. The producer never checks markers/plans/ledger, so an accepted month is re-offered on every new VM; the seeded Monte Carlo makes the equality check pass; coordinator rejects MONTH_ALREADY_ACCEPTED; VM keeps the card (pinned by test); screen shows "unavailable" plus an active Accept card. ALSO: the v151 migration / pre-151 restore seeds a LEGACY marker for the current month, so users who NEVER accepted a sweep get a dead Accept card in that month's sweep window (a functional regression vs 3e8377e4, where acceptance succeeded). Bounded to the 5-day window; Dismiss works; no money effect | P2 | NEW-REGRESSION symptom of ea446316 (incomplete F2) on pre-existing producer behavior | CONFIRMED; optional Room+real-coordinator runtime test defined |
| RG05A-2 | P3 | CONFIRMED P3: a goal 1 ULP short stays unfinished; the next month allocates 2.27e-13, which is accepted and consumes the month ("+EUR 0.00" row); no per-allocation minimum | P3 | mixed (1-ULP state partly pre-existing; month consumption new) | CONFIRMED |
| RG05B-1 | P3 | CONFIRMED: restore test asserts marker count/reasonCode, not month bounds (mitigated by SavingsSweepMonthMigrationTest exact bounds) | P3 | TEST-DEBT (new in ea446316 tests) | CONFIRMED |
| RG05B-2 | P3 | RECALIBRATED: stale "58-table"/"Tier 1 (39 total)" comments; actual 59 entries / 40 TIER_1_EXACT; went stale in ea446316 | P3 | DOC-DRIFT | RECALIBRATED |

Deciding runtime test (RG05A-1): Robolectric in-memory Room + real coordinator, fixed clock in the last 5 days of month M,
underspent budget, goal 200/1000: accept -> ledger 1, marker 1; new VM 1 min later -> computeSweepRecommendation non-null;
accept -> reason SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED, card stays, ledger 1, goal unchanged. Variant: DB migrated from
v150 inside month M -> same rejection with 0 ledger rows.

## Unverified possible items (need their own refutation; NOT registered)

- V05-N1: a goal 1 ULP short displays as complete ("1975.95/1975.95") but stays unfinished (completion/achievement UI).
- V05-N2 (low confidence): init race; goals emission after the sweep result clears a fresh card and shows STALE on screen entry.
- RG05A init-order race noted by discovery (same as V05-N2).

## Limits / pending scope for RG-05

- NOT-READ: SavingsSweepPlanDao SQL; card/formatter rendering of 1e-13 amounts; coordinator test bodies 108-452;
  use case 262-398; androidTest DatabaseMigrationMatrixTest marker-bound assertions.
- No candidate-only execution of SavingsSweepAcceptanceCoordinatorTest, SavingsSweepMonthMigrationTest,
  BackupVerifierSavingsLedgerTest, migration-tests, connected migration in this lane (Wave 5 candidate-only runs exist
  at the pre-14a2e6ce runner; they are baseline evidence only).

---

# Slice RG-17 (guard closure: CL-22, raw-money path, cancellation ratchet, skipped tests)

```text
Approval: user "i approve the next slices" (2026-10-14) -> RG-05 + RG-17.
Source candidate: HEAD 279b7c92; app/ == ea446316; scripts/config/.github clean at HEAD (incl. 14a2e6ce runner changes).
Evidence type: independent STATIC verification only. No guard/pytest/Gradle executed. Generated evidence read for counts only.
Status: RG-17 PARTIAL (static; FG-06 nonconformances open; skipped-case runtime evidence missing)
```

| Step | Role | Requested | Actual (self-reported) | Independent? | Outcome |
| --- | --- | --- | --- | --- | --- |
| RG-17a attempt 1 | release-regression-auditor | Opus 5.5/xhigh | unknown | n/a | hit 45-turn limit, NO report; nothing used |
| RG-17a1 (I04-001..004) | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5 | n/a | PASS |
| RG-17a2 (I04-005..007, P08-005) | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5 | n/a | PARTIAL |
| RG-17b (raw money, ratchet, skips) | release-regression-auditor | Opus 5.5/xhigh | Opus 5.5 | n/a | PARTIAL |
| V-17a | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-17a1/a2 |
| V-17b | release-finding-verifier (fresh) | Opus 5.5/xhigh | Opus 5.5 | yes | verified RG-17b |

## Closure (static)

| Item | Discovery | Independent verification | Fix | Residual |
| --- | --- | --- | --- | --- |
| CA-I-04-001 evidence-gate --base-ref | FIXED | VERIFIED-FIXED (ci.yml :178/:181 pass a validated base ref; script fails closed when it is missing/invalid) | 0b44cd4d | workflow contract test does not assert --base-ref (RG17A1-1, P3) |
| CA-I-04-002 comment markers / no linkage | FIXED | VERIFIED-FIXED (executable source masking; per-POST provenance proof) | 0b44cd4d | `.post(`-only detection: `.method("POST")`/`.put`/`.patch` would escape R2 and privacy G3; no current production use (N-RG17-A, P3 latent) |
| CA-I-04-003 ratchet occurrence collapse | FIXED | VERIFIED-FIXED (per-occurrence fingerprints; Counter diff; validated occurrence_counts) | 0b44cd4d | — |
| CA-I-04-004 CI test globs | FIXED | VERIFIED-FIXED (`pytest scripts` recursive, blocking guard_tests leg) | 0b44cd4d | no required-module presence check in the suite (RG17A1-2, P3); registry testManifest drift (RG17A1-3, RG17A2-4, P3) |
| CA-I-04-005 worker comment markers / qualified supertypes | FIXED | VERIFIED-FIXED for current inventory (10 workers, 10 @HiltWorker, all match) | 0b44cd4d | only the CoroutineWorker spelling is discovered (RG17A2-2, P3 latent) |
| CA-I-04-006 cancellation allowlist widening | FIXED | VERIFIED-FIXED (exact repo-relative path + rule + "(...)" symbol) | 0b44cd4d | 53 of 77 entries are inert class-only symbols, i.e. FG-06 orphans (RG17A2-3, P3) |
| CA-I-04-007 allowlist compliance fail-open | FIXED | VERIFIED-FIXED (ALLOWLIST_INPUT_* -> exit 2) | 0b44cd4d | compliance check treats only literal "*" as a wildcard |
| CA-P08-005 provider payload test | FIXED | VERIFIED (per-provider transport test; per-POST proof) | 0b44cd4d | cloud_payload allowlist matcher (RG17A2-1) |
| raw_money_aggregates 83 -> PASS | — | GENUINE SOURCE FIX (discovery; not separately re-verified): guard, allowlist and scope unchanged since a1c66173 and there is no baseline mechanism; `sumOf { it.amount... }` 68 -> 0, MoneyAggregate refs 353 -> 715; allowlisted raw hits steady 20 -> 20; provenance 73c4e4c4 (Wave 5 money hardening); run log PASS | 73c4e4c4 | semantic correctness of the conversions belongs to E-01; shapes the guard does not scan (e.g. `forEach { total += }`) not scanned |
| Cancellation ratchet 125 -> 112 | — | CLEAN SHRINK (discovery): 59 -> 49 fingerprints, zero additions/increases at every step; allowlist not used to absorb removals | 0b44cd4d..d5d4d97e | remaining T1 debt: DatabaseBackupRepositoryImpl (-02 x13), PostCommitActionRunnerImpl (-01 x7), MultiCurrencyRepository (-02 x6), FinancialStressForecastEngine (-02 x5), receipt/review/import/notification sites |
| Skipped tests | — | ACCOUNTED: unit 254 stress @Ignore + 22 others (+1 release Assume for debug-only API); 3 empty @Ignore placeholder lifecycle classes (historical); 28 guard-test skips are platform/privilege-conditional (Windows host); ubuntu CI would run them, but CI does not trigger on bug-fixes | — | RG17B-1..4 |

## Candidate findings (RG-17)

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG17A2-1 | P2 | RECALIBRATED P3, but a definite FG-06 nonconformance. The cloud_payload allowlist matcher (suffix path, empty rule, symbol never enforced) exempts every R1/R2 hit in any `*CloudPayloadPolicy.kt`; its sole entry is an orphan that suppresses nothing today. R2 is backstopped by privacy G3; R1 (`RequestBody.create`) has no other layer. Fixture A (R1 in a new `data/sync/LegacyCloudPayloadPolicy.kt`) passes the blocking guard | P3 (FG-06 blocker) | PRE-EXISTING (identical at 37601232) | RECALIBRATED; fix or explicit waiver needed for FG-06 sign-off |
| RG17A2-3 | P3 | CONFIRMED: 53 inert cancellation allowlist entries (orphans under FG-06), intentionally retained per JOURNAL :125 | P3 (FG-06) | CHANGED (0b44cd4d) | CONFIRMED |
| RG17A2-2 | P3 | CONFIRMED latent: worker discovery ignores ListenableWorker/Worker/abstract bases; no @HiltWorker cross-check | P3 | latent | CONFIRMED |
| RG17A1-1..4, RG17A2-4 | P3 | accepted as-is (no contrary evidence) | P3 | hygiene | ACCEPTED |
| RG17B-1 | P2 | CONFIRMED P2 coverage gap: the only tests of the notification-intake enqueue-failure transition (markEnqueueFailed; service EnqueueFailed branch) are in the class-@Ignore'd NotificationIntakeEnqueueFailureTest; the coordinator tests' CompletedOperation always succeeds. Production reads correct statically | P2 (T1 coverage gap; no product defect shown) | TEST-DEBT (UNKNOWN origin class) | CONFIRMED |
| RG17B-2 | P2 | RECALIBRATED P3: HomeViewModelCurrencyReloadTest ignored; production reload wiring (dashboardReloadTrigger on home-currency change) present but unexercised | P3 (P2 if dashboard money display is treated as T1) | TEST-DEBT | RECALIBRATED |
| RG17B-3 | P2/P3 | RECALIBRATED P3: no real-Keystore round-trip coverage for SecureKeyStorage (EncryptedSharedPreferences wrapper) or BankTokenCipher (own AES/GCM; fails closed) | P3 | TEST-DEBT | RECALIBRATED; instrumented round-trip test requested |
| RG17B-4 | P3 | RECALIBRATED P3: 28 conditional skips hide fail-closed negative cases on the Windows evidence host; no ubuntu run exists for this tree (CI does not trigger on bug-fixes) | P3 | EVIDENCE GAP | RECALIBRATED; needs ubuntu static-guards run at the pin |
| RG17B-5 | P3 | REFUTED as money defect: the ignored test's own helper is wrong (1.24.toInt() = 1); production TaxEstimator VAT = total*r/(1+r) (124 -> 24.00) is correct. Running VAT/bracket tests in TaxCalculationTest also exercise local helpers, so they are vacuous for production math | — (P3 test hygiene) | TEST-DEBT | REFUTED (defect) |

## FG-06 governance lead (human decision)

- 0b44cd4d added 24 cancellation allowlist entries (53 -> 77, none removed; 9 data/ai/provider services, 4
  RestoreDiagnosticsSink rule/symbol pairs incl. two annotated "no CE rethrow at this site - tracked debt", 2
  RestoreJournalImporter, 1 DefaultCloudPayloadPolicy, 2 AnalyticsRepository, 2 input builders, 3
  FileWorkerTerminalDiagnosticSink, 1 WorkerSpecScheduler). All carry owner @panospao7, linked_issue MIT-035.
- Approval appears ONLY in agent-written records (wave2-closure-review-20260929.md :35; JOURNAL :125). No human-authored
  DECISION line for the allowlist was found. Disposition: APPROVED-ON-RECORD via agent records only, so human confirmation
  is needed.
- ALL 77 entries expire 2027-01-01 (about 11 weeks after 2026-10-14). The guard behavior on expiry and the release timing
  must be planned; extending expiry would be an FG-06/FG-07 decision requiring explicit approval.

## Unverified possible items (need their own refutation; NOT registered)

- V17B-N1 (P3?): markEnqueueFailed to FAILED_FINAL does not set terminalAt (markFinalFailure does); the payload repairer
  uses terminalAt != null; also the DAO KDoc "cannot double-count" is not enforced for FAILED_RETRYABLE rows.
- V17B-N2 (P3?): known cancellation-swallowing sites in RestoreDiagnosticsSink are exempted as "tracked debt".
- V17B-N3 (NEEDS_RUNTIME): verify_allowlist_compliance behavior after its 2026-10-01 grace period end.
- N-RG17-A (P3 latent): non-`.post(` HTTP verbs escape payload proof (see CA-I-04-002 residual).

## Limits / pending scope for RG-17

- No guard_tests / static-guards / targeted-test execution at this pin (B-1 still open). Windows evidence-host skips.
- NOT-READ: cloud_payload_proof internals (222-698), CloudProviderTransportPayloadTest body, check_yaml_allowlist beyond
  :300, guard_execution_plan manifest use, liveness of the 24 new cancellation entries, expiry handling in the guard.

---

# Slice RG-06 (Wave 5 F4/F5 + B1/B2 dashboard/forecast + M2a-d analytics)

```text
Approval: "lets do all the regression slicces then in order" (2026-10-14) -> RG-06..RG-16.
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents PIN_OK start+end. Static only.
Agents: RG-06a, RG-06b auditors (Opus 5.5); V-06 fresh verifier (Opus 5.5). Effort not observable.
Status: RG-06 FAIL (confirmed new P2 regression RG06A-1); PARTIAL coverage
```

| Item | Discovery | Verification | Fix | Residual |
| --- | --- | --- | --- | --- |
| F4 dashboard budget provenance | CHANGED | VERIFIED-FIXED for runway/Monte Carlo/SafeToSpend (raw EUR cannot reach them as USD) but OVER-CLOSED -> RG06A-1. Other overall-budget consumers ungated: MonthlySavingsSweepUseCase :264, FinancialHealthCalculator :466, Money Radar (RG06A-3) | ea446316 | RG06A-1 |
| B1 Monte Carlo partial-input boundary | FIXED (present before ea446316) | not re-verified | a1c66173..3e8377e4 | runway card unavailable text may show a raw reason code (unverified) |
| B2 explicit forecast currencies | FIXED (no "EUR" literal in domain/forecasting; required currency param validated) | not re-verified | 73c4e4c4 | B2 handoff :35 stale (RG06A-2) |
| F5 email source currency | FIXED | VERIFIED-FIXED: missing/unsupported email currency -> "XXX" -> review (currency_unknown), never home currency; expense only in final branch. Email ingestion unwired in release (no caller of EmailReceiptIngestionService) | ea446316 | review approval forcing currency choice not verified |
| M2a normalized aggregates | FIXED (worked example 10 EUR + 10 USD@0.9 + 10 JPY no rate = 19.00 EUR PARTIAL, 1 excluded) | VERIFIED-FIXED (spot-check) | 73c4e4c4 | legacy scalar consumers open (declared) |
| M2c1 / M2c2 / M2d | FIXED / FIXED with declared residual / PARTIAL coverage | not re-verified | 73c4e4c4 | VM day-of-week/hour patterns lack per-slice quality (pre-existing); classifier internals unread |

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG06A-1 | P2 | CONFIRMED P2 (verifier: release-blocker candidate). The F4 gate BudgetStatusSnapshot.hasCompleteAmountIn requires currencyAssumption in {EXPLICIT, EXPLICIT_HOME_CURRENCY, USER_CONFIRMED, PARSED_FROM_SOURCE}, but NO production budget writer ever sets it: entity default LEGACY_DEFAULT; the only constructors are BudgetScreen dialog/suggestion and a debug migration; edits copy() keep LEGACY_DEFAULT; no migration backfill. So every user with an overall budget permanently loses dashboard Runway (shown as "conversion unavailable"), Monte Carlo, Block Party, the pace widget/insight and SafeToSpend totalBudget; users without an overall budget are unaffected. Tests hide it with hand-stamped "EXPLICIT"/"USER_CONFIRMED" fixtures; no real repository -> dashboard chain test | P2 (fails closed; broad core-feature loss) | NEW-REGRESSION (ea446316; BudgetRepository unchanged 3e8377e4..ea446316) | CONFIRMED; owner policy: stamp USER_CONFIRMED on create/edit (no schema change) + legacy confirm affordance, or align gate with the sweep gate |
| RG06A-4 | P2 | CONFIRMED P2: the BudgetScreen suggestion "create" (:804-809) omits currency, so the entity default "EUR" labels a home-currency suggested amount (getSuggestions uses home-currency totals) -> FX-converted category limit for non-EUR users (e.g. JPY 30,000 -> EUR 30,000) -> wrong alerts/health | P2 | PRE-EXISTING (identical at 3e8377e4; file last changed a1c66173) | CONFIRMED; fix must set currency + provenance together |
| RG06A-3 | P2 | RECALIBRATED P3: Money Radar uses the converted base limit without partial/provenance checks; only legacy "EUR"-labeled rows of non-EUR users are FX-scaled; advisory output; uses baseLimit not effectiveLimit | P3 | PRE-EXISTING | RECALIBRATED; do NOT apply the F4 gate before fixing RG06A-1 |
| RG06A-2 | P3 | accepted | P3 | DOC-DRIFT (stale money-allow comments; B2 handoff) | ACCEPTED |
| RG06B-1 | P3 | accepted | P3 (P1 if email ever wired) | latent: bare "$" -> USD in the unwired email parser | ACCEPTED |
| RG06B-2 | P3 | accepted | P3 | analytics changePercent compares PARTIAL vs COMPLETE totals | ACCEPTED |

Deciding runtime test (RG06A-1): in-memory Room, home USD; BudgetRepository.addBudget(overall USD 1000); one USD
purchase 100 on day 1; now = day 15 of 30 -> current code: FinancialRunway unavailable, no pace widget, no Block Party.
Intended: remaining 900, burn 6.667/day, runwayDays 135 (HEALTHY), simulate(100.0, 0.0, 1000.0, "USD", 0.0).

Unverified lead: BudgetRepository.getActiveBudgetSnapshots latest-rate fallback returns the raw source amount with the
source currency label and drops isPartial in BudgetSnapshot; harmful only if consumers ignore the label.

Limits: SpendingPersonalityClassifier, InsightsEngine, AnalyticsScreen headline partial marker, forecast consumers of
budget snapshots (CalculateFinancialForecastUseCase :53, SynthesisEngine :149, stress forecast) NOT-READ.

---

# Slice RG-07 (CL-09 conditional stale-write protection)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents PIN_OK start+end. Static only.
Agents: RG-07a, RG-07b auditors; V-07a, V-07b fresh verifiers (all Opus 5.5; effort not observable).
Status: RG-07 PARTIAL (CA-P-03-001 residual confirmed P2)
```

| Old ID | Sev | Discovery | Verification | Fix | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-03-001 | P1 | PARTIAL | PARTIAL (confirmed): DAO updateMatchSuggestion predicate (expenseId IS NULL AND status IN UNMATCHED/SUGGESTED), service requires 1 row before event, worker counts only on success; real-Room service tests | 0b44cd4d | planner Suggested branch full-row write (RG07A-1); unconditional updateMatchRejected (RG07A-2) |
| CA-P-09-001 | P2 | FIXED | VERIFIED-FIXED: updateMerchantKey `WHERE id AND merchantKey IS NULL AND merchant IS :expected`, repository re-read + 1-row requirement, worker under guard with cancellation rethrown; real-Room interleave test exists | 0b44cd4d | reverse NULL write-back (RG07A-3) |
| CA-P-04-005 | P1 | FIXED | VERIFIED-FIXED: activateRule reads the rule inside the transaction and aborts on missing/already-active/!=1 row; deleteRule fully transactional; no activation bypass found (all paths route through the coordinator; updateRule callers hold outer transactions) | 0b44cd4d | concurrency not runtime-proven; RecurringExpenseDao write methods with no callers found (non-exhaustive grep) |
| CA-E-05-008 | P2 | FIXED | VERIFIED-FIXED for claim/eligibility (EXISTS parent ACTIVE on claim + pre-post check, counters after DELIVERED, cancellation rethrown) | 0b44cd4d | notification post lacks SecurityException handling (V07B-N1) |

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG07A-1 | P2 | CONFIRMED P2 (race window only): ReceiptSideEffectPlanner Suggested branch does a full-row @Update from a snapshot read before the matcher suspends; if the worker's claimForAutoMatch or a user link lands in the window, the row reverts to expenseId=NULL/SUGGESTED while the receipt_expense_links row remains -> approve/manual match fail; worker records AUTO_MATCH_LINK_FAILED every 2h. Reachable only for freshly inserted RETAIL receipts (no replay path). The G-RCPT-LINK-01 allowlist reason for the planner ("NOT expenseId") is false for a full-entity @Update | P2 | PRE-EXISTING (identical at 37601232; gap in CL-09 fix scope) | CONFIRMED; fix via conditional updateMatchSuggestion; allowlist correction needs approval |
| RG07A-2 | P2 | RECALIBRATED P3: updateMatchRejected `WHERE id` only, does not clear expenseId; a stale matching-screen Reject/Skip after an auto-match leaves REJECTED + linked + MATCH_REJECTED event; receipt silently leaves the matching UI; no amount change | P3 (P2 if overriding an explicit user decision counts) | PRE-EXISTING | RECALIBRATED |
| RG07A-3 | P3 | CONFIRMED P3: updateExpense keeps the caller's merchantKey when the merchant is unchanged; can write NULL over a backfilled key | P3 | UNKNOWN | CONFIRMED |
| RG07B-1 | P3 | CONFIRMED: deactivateRule/deleteRule write lifecycle events without checking row change (event-only orphans/inaccurate oldStatus) | P3 | not introduced by 0b44cd4d | CONFIRMED |
| RG07B-2 | P3 | CONFIRMED (low likelihood): warranty eligibility ignores an edited warrantyEndDate -> stale "expires in 7 days" post within one run | P3 | UNKNOWN | CONFIRMED |
| RG07B-3 | P3 | NEEDS_RUNTIME: INSERT OR IGNORE of a delivery for a just-deleted warranty can raise an FK violation (CASCADE FK declared) and abort the worker batch | P3 if confirmed | UNKNOWN | NEEDS_RUNTIME |

Unverified possible items: V07B-N1 (P3): AndroidNotificationService.sendBudgetAlert has no SecurityException handling and
returns DELIVERED after any non-throwing notify (blocked channel -> marked SENT; a throw aborts the batch), contrary to
AGENTS.md notification rules; V07B-N2 latent updateRule pre-transaction read via unused updateSubscription; V07B-N3
activate/deactivate lack writeBarrier.runWrite scope (pre-existing); V07B-N4 subscription snapshot lost-update;
V07A-N1 incorrect G-RCPT-LINK-01 planner allowlist rationale (fold into RG07A-1 fix).

Limits: tests not run; restore path, warranty hard-delete existence, guard exception mapping, PostCommitActionRunner
details beyond replay check NOT-VERIFIED.

---

# Slice RG-08 (CL-15 failure-to-unavailable semantics + S1 reconciliation)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents PIN_OK start+end. Static only.
Agents: RG-08a, RG-08b auditors (both PASS); V-08 fresh verifier for the three P1 closures (Opus 5.5 all).
Status: RG-08 PASS (static) — no P0-P2 candidates; P3 residuals only
```

| Old ID | Sev | Discovery | Verification | Fix | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-07-002 | P1 | FIXED | VERIFIED-FIXED: getDatabaseStats behind DatabaseReadBarrier; blocked/failed -> DatabaseStatsUnavailableException; both callers (BackupRestoreViewModel, DebugViewModel) show unavailable, never 0 | 0b44cd4d | stats not read in one transaction (debug only, P3) |
| CA-P-07-009 | P1 | FIXED (incl. J66 residual) | VERIFIED-FIXED: the 3 semantic checks fail closed on query error/empty/NULL/negative; errors gate `passed`; backup refused / restore fails on !passed. sourceType-unscoped orphan query refuted for current writers (all producers use RECURRING_RULE); lead-checked: all six referenced tables exist in 151.json | 0b44cd4d | latent: a future DETECTED_PATTERN/SUBSCRIPTION occurrence producer would refuse every backup; TIER_2 tables pass when absent/count fails (V08-NC1, P3 unverified) |
| CA-P-12-003 | P2 | FIXED | not re-verified (debug-only reachability: DebugScreen behind BuildConfig.DEBUG) | 0b44cd4d | CSV importer returns raw e.message (debug only) |
| CA-P-03-003 | P1 | FIXED | VERIFIED-FIXED for bank statements (partial PDF can never be COMPLETED: COMPLETED_WITH_SKIPS + PDF_PARTIAL event + partial message); receipt path PARSED with hasPartialOcr (UI render NOT-VERIFIED) | 0b44cd4d | one bad page render aborts the whole PDF (fail-closed, loses good pages); no failed-page count shown (RG08B-1) |
| CA-P-05-003 | P2 | FIXED | not re-verified | 0b44cd4d | — |
| CA-P-05-004 | P2 | FIXED (Wave 5 strengthened to explicit Unavailable widget) | not re-verified | 0b44cd4d (+73c4e4c4/ea446316) | timeout reported as DISPLAY_CONVERSION_UNAVAILABLE (RG08B-4) |
| CA-P-06-005 | P2 | FIXED | not re-verified | 0b44cd4d | occurrence read failure not marked partial (RG08B-3) |
| CA-E-01-004 | P2 | FIXED | not re-verified | 0b44cd4d | UNAVAILABLE aggregates still carry displayAmount 0.0 for unchecked consumers |
| S1 reconciliation | — | records are sequential (independent PASS -> strict FAIL on S1-REV-01/02 -> later passes); none touches RG-08a files | — | — | S1-REV-01 (partial OCR duplicate retry) and S1-REV-02 (stress fixtures) closure not checked (receipt lifecycle / test debt) |

P3 candidates recorded (discovery only, not separately verified): RG08B-1 (no failed-page count for bank partial
imports), RG08B-2 (computeLifestyleWidget runCatching swallows parent cancellation; likely the remaining G-CANCEL-02
baseline entry), RG08B-3 (stress forecast drops failed occurrence read without partial flag), RG08B-4 (sweep timeout
mislabeled as conversion failure), V08-NC1 (BackupVerifier TIER_2 tables pass when absent/count query throws), and the
backup-side notes: savings_sweep_plan is TIER_3_OPTIONAL; verifySemanticAggregates unused; lastBackupDate never set.

---

# Slice RG-09 (CL-05 + CL-27 money contract, consumers, A5/A6/A8, E04-004)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents PIN_OK start+end. Static only.
Agents: RG-09a (FAIL on RG09A-1 pending refutation), RG-09b (PARTIAL), V-09 fresh verifier (Opus 5.5 all).
Status: RG-09 PARTIAL (static) — no P0-P2 confirmed after verification
```

| Old ID | Sev | Discovery | Verification | Fix | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-E-01-001 rate publication date | P2 | FIXED | not re-verified | cd4777a0/eb8c8bb9 (0ea0316a) | default convertOutcome stalePolicy 24h measured from validDate would misreport staleness for future callers (no current caller); conversion uses Double without rounding (existing design) |
| CA-E-01-002 single catalog | P2 | FIXED | not re-verified | 7241e7c1 (03f197d1) | catalog lists RUB/BGN (provider availability unverified; refresh throws HOME_CURRENCY_QUOTE_UNAVAILABLE if chosen as home) |
| CA-E-01-003 locale month keys | P2 | FIXED (contract change) | consistent | 9ece8bdf | -> RG09A-1 |
| CA-E-01-005 failed buckets | P2 | FIXED | VERIFIED-FIXED (typed and legacy fromBuckets keep all buckets, count failures) | ac9e5748/ae20fdcd | fromNormalizedRows exception (RG09A-3) |
| A8 money-batch landmine | — | not a landmine (test builds aggregates directly; values hand-derivable) | not re-verified | — | that test does not exercise the builder |
| CA-P-01-006 ambiguous "kr" | P2 | FIXED (home among SEK/NOK/DKK, else unresolved; approval requires currency) | not re-verified | 6fb265d5/21303188 | ISK home unresolved; prefix "kr 120"; "1.234,50 kr" dropped (parsing gaps) |
| CL-05 review regression R1 ("from" read as CHF) | MAJOR | RESOLVED (alias lookarounds) | not re-verified | 3b7cabfe | alias pass precedence by list order ("Mo-Fr 9" case) |
| CA-P-04-006 bill reminders | P2 | FIXED (per-currency labels; converted total; partial/unavailable) | not re-verified | 3b7cabfe | raw reason-code names shown (RG09B-1) |
| CA-P-06-001 autopilot | P2 | FIXED (convert to home and back with captured quote; refuses on currency change) | NOT-VERIFIED (budget) | 3b7cabfe | consistent with RG06A-4 wrong label (does not fix it); apply-all may pick another same-category budget (pre-existing) |
| CA-E-02-002 empty factories | P2 | FIXED (homeCurrency threaded; no Locale.getDefault) | not re-verified | 3b7cabfe/73c4e4c4 | — |
| CA-E-02-005 manual-rule relabel | P2 | FIXED (source label preserved) | not re-verified | 3b7cabfe/73c4e4c4 | SynthesisEngine :557 recurring sumOf lead (unverified) |
| CA-E-04-004 tax buckets | P3 | FIXED (hand-check 165 EUR); not release-reachable (no caller of getTaxYearSummary) | not re-verified | 3b7cabfe | estimateTaxes raw fallback lead; whole-summary throw (RG09B-2) |
| A5 amount over-redaction | — | FIXED | VERIFIED-FIXED for 2-decimal amounts | 3b7cabfe | 0/1/3-decimal and grouped integer amounts still redacted (fail closed; V09-N1) |

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG09A-1 | P2 | RECALIBRATED P3 latent: the code regression from 9ece8bdf is CONFIRMED (AdvancedAnalyticsDashboard.buildMonthKey uses default locale while lookup uses Locale.ROOT, so all months 0 under non-ASCII-digit locales), but AnalyticsDashboardData.monthlyTrend has NO production reader (AdvancedAnalyticsScreen renders other fields) | P3 (P2 if a trend UI is wired) | NEW-REGRESSION (latent) | RECALIBRATED |
| RG09A-2 | P2 | RECALIBRATED P3: TotalsAggregationEngine currentMonthKey default-locale vs ASCII repository keys -> partial current month included in the "completed months" average under non-ASCII-digit locales; affects Home drill-down UNDER/OVER_AVERAGE badges only | P3 | PRE-EXISTING | RECALIBRATED |
| RG09A-3 | P3 | RECALIBRATED P3 latent: fromNormalizedRows PARTIAL omits failures from getters; no consumer reads them (counts/isPartial-guarded) | P3 | latent contract debt | RECALIBRATED |
| RG09B-1 | P3 | discovery only: bill reminders card shows raw MoneyDisplayUnavailableReasonCode names | P3 | campaign-introduced (3b7cabfe) | CANDIDATE |
| RG09B-2 | P3 | discovery only: getTaxYearSummary throws on one unconvertible category (no production caller) | P3 | latent | CANDIDATE |

Deciding runtime tests (optional): RG09A-1 Locale ar-EG-u-nu-arab + one 25.0 purchase in 2026-04 -> monthlyTrend 25.0
(predicted 0.0); RG09A-2 months 100/300/20 with now in month 3 under ar-EG-u-nu-arab -> average 200.0 (predicted 140.0).

---

# Slice RG-10 (CL-21 cloud provider policy/hybrid routing + CL-10 OCR consent)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. PIN_OK start+end. Static only.
Agents: RG-10 auditor (PASS). Independent verification of the two P1 closures: PENDING (to be folded into the next
verifier dispatch).
Status: RG-10 PASS (discovery); P1 closure verification PENDING
```

| Old ID | Sev | Discovery | Fix | Residual |
| --- | --- | --- | --- | --- |
| CA-P08-002 | P1 | FIXED: CloudQueryInterpretationService requires CloudPayloadPolicy; gate before prepareText; body only from prepared text; bounded logs. All 8 Cloud*Service providers gate then prepare* (no bypass); transport test covers 9 entries | 0b44cd4d | redaction now follows policy.redactBeforeCloud (intended) |
| CA-P08-004 | P2 | FIXED: 5 bound hybrid wrappers use HybridRouter (no failover, cancellation propagates); dedupe failover and SmartReceiptAssist specialised and documented | 0b44cd4d | HybridDedupe on-device -> cloud failover overrides ON_DEVICE preference (consent still enforced; documented, pre-existing) |
| CA-P-03-002 | P1 | FIXED: local ML Kit OCR ungated; image upload requires four independent checks (Smart shouldAttemptCloudVision, CloudReceiptAssist capability, policy allowImage, CloudAiPrivacyGate receiptImageUploadAllowed). Hand-traces: cloud off -> local OCR only; cloud on/image off -> text-only prepared payload | a25f843a | CL-21 privacy-guardian review still PENDING (process gap) |

| ID | Claimed | Final | Class | Status |
| --- | --- | --- | --- | --- |
| RG10-1 | P3 | P3 | DOC-DRIFT: CLOUD_AI_RECEIPT_OCR has no enforcement site since CL-10 (KDoc implies a guarantee) | CANDIDATE |

RG-10 P1 closure verification (V-10/11, fresh Opus 5.5 verifier, static): CA-P08-002 VERIFIED-FIXED (gate before
prepareText, body from prepared text only, all other AI providers gate + prepare; location services outside AI policy
not checked); CA-P-03-002 VERIFIED-FIXED (spot-check: no gate before local OCR; image upload gated in 3 places).
RG-10 status: PASS (static).

---

# Slice RG-11 (CL-07 review side-effect timing, CL-08 single-writer identity, CL-04)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents PIN_OK start+end. Static only.
Agents: RG-11a (PARTIAL), RG-11b (PASS) auditors; V-10/11 fresh verifier (Opus 5.5). The verifier extracted the Room
2.7.2 runtime AAR from the local Gradle cache into /tmp and disassembled it with javap to read the transaction code
(read-only with respect to the repo; recorded for transparency).
Status: RG-11 FAIL — provisional P1 RG11-H2 (static CONFIRMED; trigger reachability NEEDS_RUNTIME)
```

| Old ID | Sev | Discovery | Verification | Fix | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-02-002 | P1 | FIXED (sink defers category dispatch until after the outer approval/email/create transaction) | VERIFIED-FIXED (lexical: every in-transaction caller passes the sink; sink-less callers are not in a transaction) | a25f843a | sink defaults to null (fails open, RG11A-3); no test at the ReviewQueueRepository call site (RG11A-2); post-commit dispatch can still fire for rows silently rolled back by RG11-H2 |
| CA-E-05-005 | P2 | CHANGED: Failure -> FailedRetryable, but "retryable" is only a diagnostic label (no replay; ephemeral OCR means replay impossible) | not re-verified | a25f843a | RG11A-1 |
| CA-P-02-001 (CL-04) | P3 | STILL-OPEN by design; unwired in release (no scheduler/route/composable) | not re-verified | — | dormant cross-product code ships |
| CA-P-02-003 | P2 | FIXED: dedupeKeyForUpdate keeps `idem:` keys on all update paths (only bank STRICT_EXTERNAL_ID uses them); hand-trace bank edit + replay -> DuplicateSkipped | not re-verified | a25f843a | updateExpense no-key-change branch saves the caller's key (RG11B-1, latent); no real-Room replay-after-edit test (RG11B-2) |
| CA-I-05-001 | P1 ledger / P2 verifier | FIXED: TransactionsViewModel.clearLocation -> coordinator.clearLocation (barrier + transaction + UPDATED event); repository method deprecated and delegating; clearLocation DAO grant coordinator-only; all non-coordinator expense writes carry exact grants | NOT independently re-verified (discovery PASS only) | a25f843a | — |

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG11-H2 (was HIST-2) | potential P1 | CONFIRMED (static) for the transaction defect: Room 2.7.2 withTransaction always begins a REAL nested SQLite transaction (bytecode: beginTransaction/setTransactionSuccessful/endTransaction around each nested block; WAL beginTransactionNonExclusive). DefaultExpenseCategoryAssignmentService.assignCategoryIfUnset opens a nested withTransaction and swallows its failure (and ReceiptLinkService also swallows), so per the SQLiteSession contract the parent is marked child-failed and the outermost endTransaction silently ROLLBACKs. Chain: ReviewQueueRepository.approveReview (outer) -> ReceiptLinkService.linkReceiptToExpense (level 2) -> assignCategoryIfUnset (level 3). Result: approval reports success and runs post-commit actions while expense/link/review rows were rolled back; same for email receipts and createExpenseAndLinkReceipt (user-entered expense lost with success shown); standalone link callers return success for a rolled-back link. Plausible deterministic trigger: Expense.categoryId FK + receipt_item_categorizations.userCorrectedCategoryId (no FK, preferred by the majority key, not reassigned by category merge) pointing at a merged/deleted category -> SQLiteConstraintException on UPDATE -> silent rollback on every retry | P1 provisional (P2 if only I/O-class triggers are reachable) | UNKNOWN (origin not examined) | CONFIRMED (static); NEEDS_RUNTIME for trigger |
| RG11A-1 | P3 | discovery: normal "no warranty" outcomes (ineligible, privacy-denied default, low confidence, missing fields) are now FAILED_RETRYABLE diagnostics, burying real persistence failures | P3 (P2 if a release health surface consumes the counts) | REGRESSED-CONTRACT (a25f843a) | CANDIDATE |
| RG11A-2 | P3 | discovery: no regression guard at the ReviewQueueRepository sink call site | P3 | TEST-GAP | CANDIDATE |
| RG11A-3 | P3 | discovery: deferredCategorySideEffectSink defaults to null (fails open for future nested callers) | P3 | residual | CANDIDATE |
| RG11B-1, RG11B-2 | P3 | discovery: updateExpense caller-key branch (latent); no real-Room strict replay-after-edit test | P3 | latent / TEST-GAP | CANDIDATE |
| V11-NEW-1 | P2 candidate | raised by verifier: category merge reassigns only suggestedCategoryId, not userCorrectedCategoryId; neither column has an FK -> stale category references (feeds RG11-H2) | needs refutation | UNKNOWN | CANDIDATE |

Deciding runtime tests for RG11-H2 (androidTest, real Room on framework SQLite; separate approved authoring; runner only):
1. Room semantics: `db.withTransaction { insert A; runCatching { db.withTransaction { error("x") } } }` -> A absent
   after return = CONFIRMED; present = REFUTED.
2. End-to-end: category C; receipt R with item categorization userCorrectedCategoryId = C; merge/delete C; pending review
   with scannedReceiptId = R and no category; approveReview -> reported success but review still PENDING, no expense, no
   link row = P1 CONFIRMED. Record counts/result types only.

Historical notes (not registered): approveAllReview catches Exception (no production caller); PostCommitActionRunnerImpl
eventWriter catches may swallow cancellation (likely baselined). Suggested sweep: every try/catch around a nested
withTransaction/runInTransaction reachable inside an outer transaction (fold into HZ-02/HZ-04).

---

# Slice RG-13 (CL-12 reminder delivery, CL-13 recurring materialization, CL-23 worker diagnostics/drain)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. PIN_OK start+end. Static only.
Agent: RG-13 auditor (Opus 5.5). Independent closure verification: PENDING (old IDs are all P2; to be spot-checked with
a later verifier dispatch). No Wave 5 commit touched these files.
Status: RG-13 PARTIAL (discovery: all 6 FIXED; P3 candidates only)
```

| Old ID | Sev | Discovery | Fix | Residual |
| --- | --- | --- | --- | --- |
| CA-P-04-001 SENT reminders snooze/dismiss | P2 | FIXED: SENT no longer terminal; snooze/dismiss transactional with barrier; action workers cancel the posted alert with the same NotificationId.forBill | a1c66173 | duplicate REMINDER_SNOOZED event on guard retry (P3) |
| CA-P-04-002 FAILED_TRANSIENT never reselected | P2 | FIXED (reselected by due/claim queries; no tight loop) | a1c66173 | no backoff/attempt cap/final state (RG13-1) |
| CA-P-04-003 inactive rule regenerated on update | P2 | FIXED (inactive branch only updates rule + RULE_UPDATED_RECONCILED with reconciliationSkipped) | a1c66173 | pre-fix regenerated rows not cleaned; updateRule pre-transaction read (RG13-3, pre-existing) |
| CA-P-04-004 projection anchor clamp | P2 | FIXED (anchorDayOfMonth derived once and passed to projection and materialization; hand-trace Jan 31 anchor -> Feb28/Mar31 keys identical); D7 accepted drift distinguished | a1c66173 | no end-to-end key-parity test with the real expander |
| CA-P-09-002 terminal diagnostic errorClass | P2 | FIXED (NotDurable carries the stored DAO exception's simpleName; bounded) | 0b44cd4d | terminal() may absorb an outer timeout as NotDurableTimeout (unverified, likely pre-existing) |
| CA-P-09-003 wall-clock drain deadline | P2 | FIXED (monotonic nanos; production binding SystemMonotonicTimeProvider) | 0b44cd4d | — |

Worker rules: BillReminderWorker and action workers run under the guard, rethrow cancellation, catch SecurityException on
notify (delivery CANCELLED), count only after a real post. Bill reminders do NOT use AndroidNotificationService (RG-07 V07B-N1
does not apply here).

---

# Slice RG-12 (CL-11 receipt duplicate fingerprint, CL-25 import provenance, CL-26 export invalidation, S1-REV-01)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. All agents PIN_OK start+end. Static only.
Agents: RG-12 auditor (FAIL); V-12 fresh verifier (also verified CA-I-05-001), Opus 5.5 both.
Status: RG-12 FAIL — RG12-01 CONFIRMED P1 (static)
```

| Old ID | Sev | Discovery | Verification | Fix | Residual |
| --- | --- | --- | --- | --- | --- |
| CA-P-03-004 | P1 | CHANGED (partial) | PARTIAL: semantic tie-breaker only applies when both semantic fingerprints exist -> RG12-01 | a25f843a | RG12-01 |
| S1-REV-01 partial OCR duplicate retry | — | FIXED (duplicateOutcome rebuilds coverage / flags unavailable; all duplicate exits use it) | not re-verified | 0b44cd4d | — |
| CA-P-12-001 import provenance | P1 ledger / P2 verifier | FIXED for CSV (batch UUID + row numbers); JSON v1/v2 provenance; debug-only reachability | not re-verified | a25f843a | JSON v2 keeps per-row source (RG12-04, reachability disputed) |
| CA-P-12-002 export result on format switch | P2 | FIXED (generation + format guards; clear on switch) | not re-verified | a1c66173 | RG12-02, RG12-03 |
| CA-I-05-001 (from RG-11) | P1 ledger | — | VERIFIED-FIXED (only coordinator calls expenseDao.clearLocation; UPDATED event; coordinator-only grant) | a25f843a | guard enforcement runtime-unverified |

| ID | Claimed | Verifier | Final | Class | Status |
| --- | --- | --- | --- | --- | --- |
| RG12-01 | P1 | CONFIRMED P1: ReceiptDuplicateDetector text fingerprint regex `[€$£¥]?\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?` strips EVERY digit (totals, dates, times, receipt numbers); a text hit is a duplicate unless both semantic fingerprints exist and differ; the semantic fingerprint is null whenever merchant/total/date/currency is unparsed (parsedDate can be null; PARSE_FAILED drafts stored with text but no semantic fingerprint). Draft path deletes the new image (POST_OCR_DUPLICATE_DRAFT). UI (ReceiptScanViewModel ignores outcome.inserted): if the old receipt is linked -> "already linked" screen, Save hidden, "View transaction"/"Match existing" are no-op lambdas -> the purchase cannot be captured by scanning; if unlinked -> REVIEW prefilled with the OLD receipt's money/date/image/receiptId with no duplicate notice (NEEDS_RUNTIME) | P1 | PRE-EXISTING residual of CA-P-03-004 (a25f843a narrowed it; HEAD also requires currency, widening the null-semantic gap) | CONFIRMED (static); runtime test defined |
| RG12-03 | P3 | RECALIBRATED to P2: setDateRange does not invalidate a completed export; Save/Share then deliver the PREVIOUS range's file while the UI shows the new range (over-disclosure if the old range was wider); also a race where the job reads dates after async checks | P2 | PRE-EXISTING (identical at 37601232) | RECALIBRATED |
| RG12-02 | P3 | CONFIRMED: re-export + format switch leaves isLoading stuck true (no data impact) | P3 | UNKNOWN | CONFIRMED |
| RG12-04 | P3 | CONFIRMED behaviour; "unreachable" disputed (ImportCoordinator injects the JSON importer; UI reachability not traced) | P3 | UNKNOWN | CONFIRMED |
| V12-NEW-A | P3 | raised by verifier: DUPLICATE-step "View transaction"/"Match existing" have empty onClick lambdas | P3 | needs refutation | CANDIDATE |
| V12-NEW-B | P1/P2 | raised by verifier: REVIEW prefilled with an unlinked matched receipt's data and receiptId with no notice (fold into RG12-01 branch B) | — | needs refutation | CANDIDATE |

Deciding runtime test (RG12-01): coordinator-level real-Room test, real detector/DAO, two synthetic template receipts
"ACME FUEL / UNLEADED / TOTAL 12.50 EUR" vs 13.75, parsedDate null, different image bytes -> CONFIRMED if the second
returns inserted=false with the first id, 1 scanned_receipts row, cleanup POST_OCR_DUPLICATE_DRAFT; control with date +
currency -> 2 rows; VM variants decide branch B.

---

---

# Slice RG-14 (CL-14 dashboard windows/lifestyle, CL-16 autopilot clamp, CL-28 analytics inputs)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. PIN_OK start+end. Static only.
Agent: RG-14 auditor, requested Sonnet 5.5/high; the agent self-reported "claude-sonnet-5" (MODEL-IDENTITY DISCREPANCY
recorded; cannot confirm 5.5). Read budget exhausted: UI consumers, tests, fixing-commit SHAs and the Wave-4->candidate
diff were NOT-READ. Independent verification: PENDING (folded into the final combined verifier).
Status: RG-14 PARTIAL
```

| Old ID | Sev | Discovery | Residual |
| --- | --- | --- | --- |
| CA-P-05-001 week aggregate across months | P2 | FIXED (code; hand-trace Sep 30 + Oct 2 -> 50) | HomeScreen consumer and caller window not read |
| CA-P-05-002 lifestyle trend order | P2 | FIXED (toSortedMap by YearMonth; 100/120/150 -> +0.5) | LifestyleSavingsPromptUseCase not read |
| CA-P-06-002 autopilot clamp after scaling | P2 | FIXED (re-clamp to ±15%; INFEASIBLE_CONSTRAINTS branch; 1000 -> >=850) | Wave-4->candidate diff not run; BudgetViewModel not read |
| CA-E-02-001 overall budget actuals | P2 | FIXED (overall budget sums all categories) | BudgetRepository/AnalyticsViewModel not read |
| CA-E-02-003 missing-rate count | P2 | FIXED (sum of affectedTransactionCount; grep-level) | double counting across warnings not checked |
| CA-E-02-004 forecast trajectory obligations | P2 | CHANGED/PARTIAL: recurring now accumulated into trajectory; committed planned expenses may still be absent -> RG14-1 | — |

| ID | Claimed | Final | Class | Status |
| --- | --- | --- | --- | --- |
| RG14-1 | P2 tentative (low-medium confidence) | pending verification | CA-E-02-004 residual: trajectory may omit committedPlanned that totalCommitted includes (SynthesisEngine :301 vs :435-457) | CANDIDATE; two reads decide (SynthesisEngine ~250-300 vs ~380-397) |

---

---

# Slice RG-16a (Wave 5 B4 legacy savings provenance, B5 history corruption, M0 legacy attribution)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. PIN_OK start+end. Static only.
Agent: RG-16a auditor (Opus 5.5). Independent verification PENDING (final combined verifier).
Status: RG-16a PARTIAL — no P1; two P2 candidates
```

| Item | Discovery | Residual |
| --- | --- | --- |
| B4 legacy savings provenance | IMPLEMENTED: SavingsGoalCurrencyPolicy accepts only EXPLICIT/EXPLICIT_HOME_CURRENCY/USER_CONFIRMED/PARSED_FROM_SOURCE + supported code; legacy LEGACY_DEFAULT "EUR" goals are unknown (even for EUR home); all consumers checked except SynthesisEngine (RG16A-2); UI shows currency-unavailable | no confirm/repair flow (by design) |
| B5 history corruption | IMPLEMENTED: strict parsing; corrupt history -> SAVINGS_HISTORY_CORRUPT, bytes preserved, gamification Unavailable. B8 merged ledger rows (no dedupe needed: manual only in DataStore, sweep only in ledger). Hand-trace: an in-month currency-less legacy event makes the monthly total Unavailable(INVALID_SOURCE_CURRENCY) (stricter than "50 + flagged"); out-of-month -> 50 EUR | manual add updates balance and history non-atomically (documented); ledger rows not pruned by retention |
| M0 legacy contribution-currency attribution | fail-closed default IMPLEMENTED and documented (B4/B5; matches proposed M0 contract) | owner decision record NOT found -> STILL-OPEN (human) |

| ID | Claimed | Final | Class | Status |
| --- | --- | --- | --- | --- |
| RG16A-1 | P2 | pending verification | CHANGED (Wave 5 B8): goal delete cascades ledger (sweep) rows but keeps DataStore manual events -> gamification totals/streaks drop only the sweep share silently; conflicts with M0 "preserve stored history" | CANDIDATE (owner decision) |
| RG16A-2 | P2 | pending verification | PRE-EXISTING: SynthesisEngine :365-381 sums STRICT goal reserves raw without currency policy/conversion (legacy "EUR" or foreign goals treated as home) -> available-to-spend misstated | CANDIDATE |
| RG16A-3 | P3 | pending verification | CHANGED: manual DataStore history not in backups (snapshotJson unused) while ledger is TIER_1 -> after restore, history shows sweeps only as complete | CANDIDATE (NEEDS_RUNTIME; backup rules unread) |

---

RG-15a (CL-17 remainder; requested Sonnet 5.5/high, agent self-reported "claude-sonnet-5" — MODEL-IDENTITY DISCREPANCY;
read budget exhausted; discovery only, not verified):
| Item | Discovery | Residual |
| --- | --- | --- |
| CA-P-04-007 BillReminderWorker Log | FIXED (bounded code + class simpleName) | grep-level |
| CA-P-06-003 forecasting UI error | FIXED (static "Forecast unavailable"; cancellation rethrown) | — |
| CA-P-06-004 SynthesisEngine Timber | FIXED | — |
| CA-P-07-008 restore/backup/startup logging | FIXED (bounded patterns) | DBRI :2359 `$message` provenance unconfirmed (RG15A-1, P3) |
| CA-E-01-006 convertMultiple logging | FIXED (count + code only) | FailedConversion.reason = outcome.message retained in returned aggregate; downstream not traced |
| CA-E-02-006 engine Throwable logs | FIXED for inspected sites | TotalsAggregationEngine :769/:793 args not individually read |
| CA-I-05-002 VM raw e.message | MOSTLY FIXED | TransactionsViewModel :646 validationResult.message provenance (RG15A-2, P3); ~1150 ReceiptScanViewModel lines unread |
| CL-17 re-review R-2 / B-1 | FIXED/ADDRESSED (typed DuplicateTransactionException + `is` check) | WI-10 generic codes partially remain (pre-existing) |
| CA-P-07-007, CA-I-05-003, A3, A4, CA-I-05-004 | NOT-READ -> dispatched as RG-15c | — |

---

# Slice RG-16b (Wave 5 B6 source boundaries, M1b allocation, B7 PDF)

```text
Source candidate: HEAD 279b7c92; app/ == ea446316. PIN_OK start+end. Static only.
Agent: RG-16b auditor (Opus 5.5). Verification of RG16B-1: V-16b (see below).
```

| Item | Discovery | Residual |
| --- | --- | --- |
| B6 receipt boundary (M1a) | FIXED (73c4e4c4): home currency never used as evidence; unresolved/conflicting currency -> "" -> money review with XXX, no auto expense | bare "$" -> USD; "ΕΥΡ" substring adds EUR (historical, narrowed) |
| B6 bank boundary | PARTIAL: parser-fallback path fixed (unresolved home -> XXX -> CURRENCY_UNKNOWN skip; non-PARSED_FROM_SOURCE currency dropped); AI-validated path -> RG16B-1 | NBG statements produce zero reviews when AI unavailable (fail-closed usability) |
| M1b tax allocation | IMPLEMENTED: minor-unit largest-remainder allocation with conservation checks; hand-trace 1.00 over 3.33/3.33/3.34 -> 0.33/0.33/0.34; over 1/1/1 -> 0.34/0.33/0.33 (tie-break lowest index; conserved) | callers' source-currency argument NOT-READ |
| B7 PDF money | CHANGED (safer): per-currency sections, no FX, no cross-currency grand total ("A combined total across currencies is not shown"); unsupported currency fails export closed; hand-trace EUR 15.50 + USD 20.00 sections | deductible aggregate unreachable (RG16B-2) |
| B7 PDF privacy / policy | privacy IMPLEMENTED (no OCR/notification text); policy application to PDF UNKNOWN | validation only first N rows (historical) |

| ID | Claimed | Final | Class | Status |
| --- | --- | --- | --- | --- |
| RG16B-1 | P1 (NEEDS_RUNTIME for AI behavior) | V-16b RECALIBRATED to P3: code defect CONFIRMED statically (seeded assumed currency echoed by AI is accepted as AI_VALIDATED; parserPolicyCurrency not applied to accepted AI rows; review pre-filled), but the ONLY entry point (ReviewScreen "Import bank statement") is inside `if (BuildConfig.DEBUG)`; release cannot reach it. Re-rate to P2+ (P1 if approval does not force a currency check) if statement import ever ships | P3 | PRE-EXISTING (identical at a1c66173); B6 Gate B closure claim overstated for accepted AI rows | RECALIBRATED |
| RG16B-2 | P3 | V-16b CONFIRMED: generateDeductibleAggregate has only test callers | P3 | test-evidence scope gap | CONFIRMED |

V-16b also VERIFIED-FIXED (static) the ReceiptParser unresolved-currency routing (-> money review with XXX; email path
currency_unknown). Open question (weak, unregistered): whether approving an "XXX" review is blocked (approval guard not read).

---

Combined verifier V-13..16 (fresh Opus 5.5, static) results:
| Item | Verdict | Final |
| --- | --- | --- |
| RG14-1 (trajectory omits committedPlanned) | REFUTED: committedPlanned and mustCumulative come from the same filtered MUST set with the same conversion; hand-trace 100 recurring + 50 MUST -> trajectory end L+150 = totalCommitted | — (CA-E-02-004 PARTIAL reason unsupported; see V16-NEW-3) |
| RG16A-2 (SynthesisEngine STRICT goal reserves raw) | CONFIRMED / RECALIBRATED (narrowed): no conversion/policy; goals reach the engine raw; visible in dashboard "AVAILABLE", Block Party rate, narrative; reachable via home-currency change or legacy goals; e.g. EUR goal 1000 after switching home to JPY counted as 1000 JPY | P2 (P3 defensible if owner accepts narrow precondition); PRE-EXISTING (bc8746d2; policy added in 73c4e4c4 but not applied here) |
| RG16A-1 (goal delete cascade asymmetry) | RECALIBRATED P2 -> P3: real (B8 moved sweeps from DataStore to an FK-cascaded ledger) but display/gamification only; no acceptance record for the gamification effect | P3, CHANGED (Wave 5 B8); owner decision |
| CA-P-07-007 | VERIFIED-FIXED (one resolved flag gates collection, snapshot, manifest, ZIP) | closed-static |
| CA-I-05-003 | VERIFIED-FIXED (token carries only templateId/expenseId) | closed-static |
| CA-P-04-004 | VERIFIED-FIXED (same anchorDayOfMonth in generate/project; expander uses it) | closed-static |

New unverified items from V-13..16 (need their own refutation; NOT registered): V16-NEW-1 (P2/P3) detected patterns with
confidence >= 0.90 drop out of committed/likely/trajectory when confirmed occurrences exist, yet are still subtracted
from the discretionary baseline; V16-NEW-2 (P3) planned-expense conversion failures double-counted in excludedCount;
V16-NEW-3 (P3) likely recurring bills included in totalLikely but not in projected trajectory points (example: 300 bill
missing from the chart); low-confidence: RecurringOccurrenceExpander :113 Timber.d includes merchant name (debug tree only?).

RG-15c (RG-15a leftovers; Opus 5.5, discovery; closures spot-checked by the final combined verifier):
| Item | Discovery | Residual |
| --- | --- | --- |
| CA-P-07-007 backup privacy mode | FIXED (a1c66173): receipt inclusion, manifest and ZIP entries gated by one resolved flag; REDACT_RAW_TEXT label matches contents | only bundle-level test (repository flag resolution untested); release UI likely passes privacyMode=null (legacy path, consistent) |
| CA-I-05-003 nav saved-state money | FIXED: VisualSplitEditor token carries only templateId/expenseId; restore reloads by id; no other destination carries amount/currency | SpendingMap.initialLocationQuery persists a location string (P3 note); RG15C-1 |
| A3 SpendingMap privacy block | FIXED (production correct; test harness repaired c8f4c383) | — |
| A4 Review denial | FIXED (fixture); production fail-closed | typed debugExportBlocked has no UI consumer (RG15C-3) |
| CA-I-05-004 dead use cases | FIXED (ExpenseUseCases.kt deleted, 94f18716) | — |
| RG15A-1 / RG15A-2 leads | DISMISSED (controlled strings) | — |

| ID | Claimed | Class | Status |
| --- | --- | --- | --- |
| RG15C-1 | P3 | CHANGED: after process-death restore, VisualSplitEditor renders 0.0/"EUR" fallback until the expense loads | CANDIDATE (NEEDS_RUNTIME) |
| RG15C-2 | P3 (P0 if legacy rows hold raw text) | confirms RG-02 lead: redacted-backup sanitizer skips operation_run_events, pipeline_diagnostic_events, receipt_events, background_job_runs, entity_source_links, group_lifecycle_events, recommendations, budget_forecasts free-text/JSON columns; current writers sanitize, legacy rows unverified | CANDIDATE (owner policy + runtime check) |
| RG15C-3 | P3 | CHANGED: Review debug-export denial shows a "copied" toast (blocked state unused by UI) | CANDIDATE |

RG-15b (CL-06 doc drift + CL-24 email parser; scout Haiku 5.5/medium per profile, T3 facts only, not verified):
| Item | Status | Evidence |
| --- | --- | --- |
| CA-P-01-007 RP-10 framing doc | FIXED (wording slightly imprecise) | RP-10 doc :185-186 vs NotificationTransientPayloadCrypto :32-35/:93-97 |
| CA-P-03-005 LEGAL_PATHS receipt creation | FIXED | LEGAL_PATHS :144-145 names createExpenseAndLinkReceipt |
| CA-E-05-001 / CA-E-05-002 | FIXED (policy-registration claim not read) | LEGAL_PATHS :736-767 / :747-749 |
| CA-I-02-001 dead FRESH_INSTALL_CALLBACK | STILL-OPEN | AppDatabase.kt :5195 still defines it (unregistered); stale androidTest comment BudgetDaoTest :134 |
| CA-I-03-001 WorkerRunLogger module | PARTIAL | fixed in SEGMENTS/INVENTORY/hilt map; ARCHITECTURE.md :324 still says WorkerModule |
| CA-I-03-002 retention target count | PARTIAL | fixed in INVENTORY/SEGMENTS (15); COMPLETE-BACKEND-MAP :1420 (10), DEPENDENCY_MAP :772 (10), ARCHITECTURE.md :539 (5) stale |
| LEGAL_PATHS group paths (RG04A-2 / RG-04b) | STILL-OPEN | LEGAL_PATHS :665 SharedExpenseManager.addExpense; :696 GroupBalanceCalculator |
| AGENTS.md "DB v148, 68 DAOs" | STILL-OPEN | AGENTS.md :28 |
| "one-tap settle" claims | STILL-OPEN | CHANGELOG :74, ARCHITECTURE.md :1709 |
| CA-P-11-001 conflicting ISO currencies | FIXED | EmailReceiptParser.detectCurrencyCode :336-350 returns null on conflicts |
| CA-P-11-002 summary-row exclusion | PARTIAL/UNKNOWN | keyword fallback only; labeled-line path :236-248 unguarded (email unwired in release) |
All P3 (documentation / unwired code).

RG-13 (continued from the RG-13 section above) P3 candidates (discovery only): RG13-1 FAILED_TRANSIENT retries every ~6h forever with growing events (no cap/backoff);
RG13-2 unconditional cancel of a hashed bill-notification slot may cancel another notification sharing the slot (low
confidence); RG13-3 updateRule reads `old` outside its transaction (pre-existing).

## Remediation lane — B-1 settlement (2026-10-15)

- Approval: user "i approve them" (see handoff approval log).
- Review: reviewer-strict (read-only) of 14a2e6ce -> ACCEPT-WITH-CONDITIONS. No false-PASS path introduced
  (PASS only on exit 0, scripts/validation-runner.ps1:903-905; E_COMMAND_MISMATCH fail-closed :794-806); new options
  default off and default command unchanged (:493-505, pinned by test_validation_runner_protocol.py:420-435);
  gradle.properties removed kapt.incremental.apt=true (inert, KSP only); test-shards.json description-only change;
  zero-match -TestFilter fails (Gradle default failOnNoMatchingTests).
- Findings: P2 (pre-existing, worsened by tuning options) build cache (org.gradle.caching=true, no --rerun) can let a
  test task be UP-TO-DATE/FROM-CACHE and the run PASS without executing tests; P3 multi-filter shard can shrink
  silently; P3 timed-out daemon not detected; P3 benchmark profiles produce PASS records.
- Runner self-check: runner-smoke vr-20261015-064122-be35ad7f PASS; runner-protocol-tests
  vr-20261015-064132-e5a8df74 PASS (200 passed, 0 failed, 0 skipped); fingerprint start == end; HEAD 279b7c92; PIN_OK.
  Lead re-read result.json for both (status PASS, exit 0, git_revision 279b7c92).
- Conditions binding for this lane: no -Parallel/-MaxWorkers/-TestForks/-ForkEvery/-GradleDaemon/-KotlinCompilerStrategy;
  no benchmark/tuned runs; cite standard profiles only; every cited test PASS must show the test task executed with
  >0 tests in stdout.log (else NOT RUN), default gradle_settings, matching fingerprints, complete.marker.

## Remediation lane — Phase R0 deciding tests R0-1 / R0-2 (2026-10-15)

- Authoring: release-test-author (two NEW untracked test files; fail-first; kept uncommitted until the fix batch lands):
  app/src/test/java/com/yourname/expensetracker/data/database/NestedTransactionSemanticsTest.kt (3 tests);
  app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryNestedRollbackTest.kt (8 tests).
  Tracked app/ still == ea446316.
- R0-1 targeted-unit-test vr-20261015-065622-64214338: PASS, exit 0, default gradle_settings, testDebugUnitTest
  EXECUTED, 3/3 passed (t1 swallowed nested failure -> outer returns normally AND outer insert rolled back; t2 control
  -> committed; t3 swallowed FK violation -> outer rolled back). Robolectric SQLite; device twin R0-1b pending (D-22).
- R0-2 targeted-unit-test vr-20261015-070245-8a85f172: FAIL as designed (fail-first), exit 1, EXECUTED, 8 run / 5
  passed / 3 failed. Failures (bounded markers):
  approveReview -> "P1 CONFIRMED: success reported, review PENDING, expenses=0, links=0 [result=Success,
  foreign_keys=1, postCommitRuns=1]"; linkReceiptToExpense -> "P1 CONFIRMED: ... expenses=1, links=0 [Success]";
  createExpenseAndLinkReceipt -> "P1 CONFIRMED: ... expenses=0, links=0 [Success]". Passed: V11-NEW-1 stale
  userCorrectedCategoryId survives mergeCategories; FK violation fires for the merged-away category; three no-merge
  controls commit expense + link + approval.
- Lead verified result.json (status/exit/default settings) and stdout test lines for both runs; run-1 XML was replaced
  by run 2 (stdout is the evidence).
- Provisional reading (pending independent test review): RG11-H2 P1 RUNTIME-CONFIRMED (Robolectric) on all three
  paths with the deterministic V11-NEW-1 trigger; V11-NEW-1 CONFIRMED (P2, trigger of a P1). Compiler warning: the
  tests call restricted ExpenseDao mutations directly (fixtures/T3) — test-only, to be checked by the reviewer.
- Independent test review (reviewer-strict, read-only, 2026-10-15): both files VALID-WITH-NOTES. Harness faithful
  (production configureBuilder, foreign_keys=1, real services/coordinator/runner/DAOs; failing tests and controls share
  identical mocks and differ only by mergeCategories). Mechanism CONFIRMED: FK SQLiteConstraintException at
  DefaultExpenseCategoryAssignmentService.kt:37 inside nested withTransaction (:31), swallowed at :49-51 (Failed) and
  ignored at ReceiptLinkService.kt:313-320; :362 returns success; outermost endTransaction rolls back
  (RoomDomainTransactionRunner.kt:43 / ReviewQueueRepository.kt:224 / ReceiptLifecycleCoordinator ~:1599);
  ReviewQueueRepository.kt:409 runs post-commit actions for a rolled-back expense, :443 returns Success. Latent third
  swallow ReceiptLinkService.kt:336-340. Robolectric 4.11.1 runs real SQLiteSession code (recalled, not source-read).
  RECALIBRATION: CategoryRepository.mergeCategories has NO caller in app/src/main and CategoryViewModel.deleteCategory is
  not called from CategoryScreen -> the tested trigger is NOT UI-reachable at HEAD. V11-NEW-1 -> P3 (latent).
  RG11-H2: mechanism CONFIRMED (runtime, Robolectric); live-trigger reachability INCONCLUSIVE; plausible unverified
  triggers: cloud categorization ids not validated against categories (CloudReceiptItemCategorizationService.kt:351),
  MIGRATION_112_113 (AppDatabase.kt:7591) deletes duplicate categories without remapping categorization rows.
  Reviewer recommendation: keep P1 (fix-before-release; silent loss of a user-saved expense with success shown); P2 if
  the owner requires a proven reachable trigger. Required test changes before they become permanent regression tests:
  @OptIn(RestrictedExpenseDaoMutation::class) instead of @Suppress; assert outcome Failed via a delegating recorder; add
  a non-merge stale-id fixture (nonexistent category id); assert post-commit did not run on failure; neutral token
  "SILENT_ROLLBACK" instead of "P1 CONFIRMED". Guard risk low (test sources out of guard scope).

## Remediation lane — batch R1 (RG11-H2) implementation and review (2026-10-15)

- Scope decision (lead): R1b deferred (mergeCategories has no production caller; V11-NEW-1 P3). Plan: remediation plan section 9.
- Implementation: release-remediation-coder. Production: ExpenseCategoryAssignmentPort.kt (SkippedCategoryMissing + KDoc),
  DefaultExpenseCategoryAssignmentService.kt (CategoryDao read, existence check inside the existing transaction),
  ReceiptLinkService.kt (ReceiptLinkFailureCode/ReceiptLinkAbortedException; port call moved out of the swallowing catch;
  exhaustive when; boundary catch -> Result.failure), ExpenseRepository.kt (comment), LEGAL_PATHS.md (outcome list +
  existence guard line; lead restored one dropped blank line). Tests: R0-1 @OptIn; R0-2 post-fix assertions + NONEXISTENT_ID
  seed + recorder; new ReceiptLinkServiceNestedFailureTest (3); CategoryAssignmentServiceBarrierTest constructor + 1 test.
  No DAO/guard/allowlist/policy/schema edits.
- architecture-guardian: ACCEPT-WITH-NOTES; predicted no change for FG-10/12/13/14/15/16/26; no line-anchored allowlist
  risk; Hilt provides CategoryDao. Notes N1-N5 (doc precision; db_ownership_policy free-text not to be edited (pinned
  SHA); FG-16 follow-up: DefaultExpenseCategoryAssignmentService logs raw exception and puts e.message in Failed.reason).
- reviewer-strict (first attempt died on an API error, no output; retry): ACCEPT-WITH-NOTES. All transactional callers
  throw on failure (ReviewQueueRepository :318-323; ReceiptLifecycleCoordinator :1201/:1218/:1617-1623); CE never caught;
  no leak. Findings: (1) MINOR coverage gap: no approveReview test with a Failed outcome; (2) KDoc "already rollback-only"
  imprecise for barrier-blocked Failed; (3) worker folds CATEGORY_ASSIGNMENT_FAILED into RECEIPT_LINK_INVALID_STATE
  (follow-up); (4) residual: email path ReceiptLifecycleCoordinator :1238-1245 Error inside outer tx without throw (HZ-02);
  (5) cosmetic import order.
- Review iteration 1: lead routes (1) + (2) to the writer before validation.
- Review iteration 1 (writer): added approveReview_failedCategoryAssignmentOutcome_returnsErrorAndRollsBackEverything
  (real failing nested write via FailingNestedWritePort; asserts ReviewResult.Error, PENDING, 0 expenses/links/post-commit,
  port called once and returned Failed); KDoc wording of Failed corrected (comment only).
- Validation queue 1: targeted CategoryAssignmentServiceBarrierTest vr-20261015-075833-aae647c8 PASS (9/9, executed);
  targeted ReceiptLinkServiceNestedFailureTest vr-20261015-080914-3780f4d9 FAIL (3 run, 2 passed): createExpenseAndLink
  test "cause must be ReceiptLinkAbortedException (was IllegalStateException)"; message SOURCE_LINK_FAILED matched.
  Queue stopped by rule.
- Debug iteration 1 (lead diagnosis): test artifact, not a production defect — kotlinx.coroutines stack-trace recovery
  re-wraps the IllegalStateException("SOURCE_LINK_FAILED", cause) thrown across the suspend runInTransaction boundary in a
  copy whose cause is the original; the direct cause is therefore IllegalStateException and the abort exception is one
  level deeper. Fix (lead, test-only, one assertion): search the cause chain (bounded 8) for ReceiptLinkAbortedException
  and keep the code/message assertions. Not a weakening: the test still fails if no abort exception is in the chain.
  Item-1 PASS is pre-edit evidence; the full unit-tests run re-covers it.
- Validation queue 2 (after the debug fix; same uncommitted tree, fingerprints start == end on every run, HEAD 279b7c92,
  default gradle_settings, test task EXECUTED on every test run — lead re-checked result.json and stdout):
  ReceiptLinkServiceNestedFailureTest vr-20261015-081348-994d6ec6 PASS 3/3;
  ReviewQueueRepositoryNestedRollbackTest vr-20261015-081727-daaf3c1f PASS 12/12;
  ReceiptLinkServiceColumnScopeTest vr-20261015-081923-fc5a0bb6 PASS 5/5;
  NestedTransactionSemanticsTest vr-20261015-082131-a28577f3 PASS 3/3;
  unit-tests vr-20261015-082326-b668c16f PASS (7939 total, 7663 passed, 0 failed, 276 skipped);
  static-guards vr-20261015-083506-319b847d FAIL: 24/25 guards PASS (all FG guard families incl. cancellation, receipt_link,
  db_access, event_writers, privacy, pii_logging, ui_dao, worker, money, guard_docs_truth, ignored_test_budget,
  allowlist_compliance); known_good_state FAIL on row 7/7 test_result_freshness only (verdict=sha_drift,
  commit_match=false). Rows 1-6 PASS (active_db_gate findings=0; structural_manifest 65 = 60 + 5 fixtures as expected).
  (The first runner session hit its turn limit while polling; a second session adopted the active unit-tests run — no
  duplicate run.)
- Lead diagnosis of the FAIL (static, not a guard execution): app/build/test-results/.freshness-stamp.json was written by
  the Wave 5 lane at commit 3e8377e4 (suite app-check, 2026-10-14T11:28:47Z). The row compares the stamp commit with HEAD
  only, so it fails at HEAD 279b7c92 independently of R1 (in Wave 5 evidence the row was SKIP: stamp missing). Not
  attributable to R1; still a FAILED gate result -> R1 is NOT PASS.
- R1 STATUS: PARTIAL — implementation reviewed (architecture-guardian + reviewer-strict ACCEPT-WITH-NOTES), targeted tests
  and unit-tests PASS; static-guards FAIL on the stale freshness stamp. BLOCKED-ON-HUMAN for the resolution: (a) approve
  deleting the stale stamp (row becomes SKIP, as in Wave 5) and re-running static-guards; or (b) approve writing a stamp
  per docs/ci/GR00-GR04_validation_checklist.md section 2 (`test_result_freshness.py --write`) right after a unit-test run
  — only meaningful on a committed tree, since the stamp records the commit SHA, so (b) needs a commit decision; or
  (c) waive the optional row for this uncommitted batch. Lead recommends (a).
- RG11-H2: fixed in the worktree (uncommitted) and runtime-proven in Robolectric; final closure needs the static-guards
  gate, final-candidate re-validation and, for device fidelity, R0-1b (D-22).
- Owner chose option (a) (2026-10-15). Lead deleted the git-ignored stale app/build/test-results/.freshness-stamp.json
  (commitSha 3e8377e4). static-guards vr-20261015-091626-65ab1540 PASS, exit 0, 25/25 blocking guards PASS;
  known_good_state rows 1-6 PASS, row 7 test_result_freshness SKIP (stamp=missing; not counted as a pass, same as Wave 5).
  Fingerprints start == end; HEAD 279b7c92.
- Evidence coherence: every changed/new app file was last modified at or before 08:13:19Z (the debug fix); queue 2 started
  08:13:48Z. All queue-2 runs (4 targeted, unit-tests, static-guards re-run) therefore cover identical app content; only
  release docs/handoff and the ignored stamp changed between unit-tests and the static-guards re-run.
- R1 BATCH STATUS: PASS (batch gate: two independent reviews ACCEPT-WITH-NOTES; targeted tests, unit-tests, static-guards
  PASS) on an UNCOMMITTED worktree at HEAD 279b7c92. Not final acceptance: RG11-H2 closure still needs the final-candidate
  re-validation (app-check, lint, connected-tests incl. R0-1b) and independent final review. Follow-ups recorded above.

## Remediation lane — R2..R6 sequence (approved 2026-10-15; shared unit-tests + static-guards after R6)

- R3 R0-6 fail-first run vr-20261015-100018-cdb7edc5: FAIL, but NOT a decision: 3/3 tests (incl. 2 controls) hit
  UncompletedCoroutinesError (test body did not complete in 1 min); compile OK; executed. Harness hang -> debugger.
- R2 plan surfaced NEW-R2-A (in-scope fix) and NEW-R2-B (candidate, routed to room-migration-guardian).
- NEW-R2-B (room-migration-guardian, read-only): REFUTED / Info. Room 2.7.2 (app/build.gradle.kts:271) validation compares
  index sets (bytecode of TableInfoKt.equalsCommon / SchemaInfoUtilKt.readIndices; in-repo corroboration AppDatabase.kt
  :481, :8872), so any upgrade through MIGRATION_125_126 / 137_138 leaving the undeclared unique indexes would fail
  validation and roll back; no later code drops/creates them; supported chain is v145->v151; fresh installs build from
  entities. => NEW-R2-A premise (upgraded installs carry the unique indexes) is unreachable; the coordinator
  "never store a claimed textFingerprint" change is DROPPED from R2 (lead decision, smaller diff). Low-severity cleanup
  noted: misleading comment AppDatabase.kt :7968-7969; emailMessageIdHash partial indexes (:7979, :8317) unchecked.
  Optional deciding device test (migration matrix + extra indexes -> validation failure) recorded, not scheduled.
- R3 R0-6 harness fix (debugger root cause ~80%: runTest virtual time advanced the day-boundary ticker inside
  BudgetRepository.getBudgetStatuses() so flatMapLatest kept restarting real Room queries; lead applied the test-only
  fix: collect the first budget status and run compute() on Dispatchers.Default with a 10 s timeout; await specifically
  BudgetSaved). Re-run vr-20261015-101513-65de09c8: executed, 3 run / 2 passed / 1 failed —
  ui_created_overall_budget... FAIL "RG06A-1: runway unavailable for UI-created budget (assumption=LEGACY_DEFAULT)";
  both controls PASS. => RG06A-1 RUNTIME-CONFIRMED (Robolectric, real repository/VM/adapter/use case).
- R2 R0-3 first run vr-20261015-101908-3a2304d1: harness NPE in 11/14 (MockK spy coAnswers reified non-null cast of an
  argument). Lead fix (test-only, debug iteration 1): tolerant argument capture; removed the 7 upgraded-index variants
  (unreachable schema per NEW-R2-B). Re-run vr-20261015-102239-24b9910a: executed, 7 run / 4 passed / 3 failed —
  T1 (12.50 vs 13.75, date null), T3, T3b FAIL "RG12-01 COLLISION ...: rows=1 inserted=false"; controls T2 (duplicate
  with POST_OCR_DUPLICATE_DRAFT), T4, T4b (EXACT_HASH) and T5 (dated different totals) PASS.
  => RG12-01 P1 RUNTIME-CONFIRMED (Robolectric, real coordinator + detector + Room).

### Batch R2 (RG12-01) — implementation, review, targeted validation (2026-10-15)
- Writer (release-remediation-coder): detector rule A (ReceiptDuplicateDetector: text hit is a duplicate only with both
  semantic fingerprints present and equal; otherwise falls through; final non-duplicate NONE / TEXT_FINGERPRINT_UNCONFIRMED);
  ReceiptScanViewModel reopenedExistingReceipt flag + ReceiptScanScreen notice; two no-op buttons removed; string
  receipt_reopened_existing_notice (lead added es/el translations to avoid a new MissingTranslation lint error); tests:
  two D-04 pinned detector tests flipped, 3 added, NONE test strengthened; 4 VM tests. Coordinator untouched.
- reviewer-strict: ACCEPT-WITH-NOTES. All DuplicateResult consumers gate on isDuplicate (coordinator :409/:514/:558/:999,
  BankStatementLifecycleProcessor :190); getByTextFingerprint LIMIT 1 harmless; F1 R0-3 dead code (fixed by lead,
  test-only: index-variant helper/parameter/comments removed); F2 accepted D-04 trade-off (rescans with unparsed fields
  create a second row — fail-safe, never deletes an image); F3 follow-up: ScannedReceipt.kt :43-46 TODO suggests unique
  textFingerprint index, which would reintroduce RG12-01 — reword later; F4 two now-unused strings (lint warning only).
- Targeted (all EXECUTED, default settings, same fingerprint 4baeaad9…): ReceiptDuplicateFingerprintCollisionTest
  vr-20261015-102956-ac4124b1 7/7; ReceiptDuplicateDetectorTest vr-20261015-104002-0839cde0 8/8; ReceiptScanViewModelTest
  vr-20261015-104205-9796fc47 28/28; ReceiptLifecycleCoordinatorTest vr-20261015-104411-b6ce26d7 37/37;
  ReceiptPartialOcrPipelineTest vr-20261015-104611-458c78de 22/22; ReceiptScanViewModelStressTest
  vr-20261015-104817-d1d64bd3 20/20; BankStatementSourceBoundaryTest vr-20261015-105016-3d23dcef 4/4.
  After the F1 test-only cleanup, R0-3 must be re-run once (queued with the R3 targeted runs).
- R2 STATUS: PARTIAL (pending R0-3 re-run + shared unit-tests/static-guards after R6).

### Batch R3 (RG06A-1 + RG06A-4) — implementation, review, targeted validation (2026-10-15)
- R0-6 harness fixed (see above) and re-run pre-fix: RG06A-1 RUNTIME-CONFIRMED.
- Writer: BudgetViewModel sole stamping point (addBudget/updateBudget(budget, confirmedCurrency); resolveBudgetForWrite;
  ERROR_BUDGET_CURRENCY_UNCONFIRMED); BudgetScreen read-only currency text, suggestion Create gated on home currency,
  Confirm-currency affordance; FinancialRunwayCard onReviewBudget; HomeScreen OVERALL link; 3 strings en/es/el; new
  BudgetViewModelCurrencyProvenanceTest; BudgetViewModelStressTest mechanical (class @Ignore pre-existing at HEAD :32).
- reviewer-strict: CHANGES-REQUIRED — ISSUE-1 MAJOR duplicated provenance rule (KNOWN_BUDGET_PROVENANCE copy of the set in
  SavingsGoalCurrencyPolicy / BudgetStatusSnapshot) -> must call SavingsGoalCurrencyPolicy.knownCurrencyOrNull. Verified:
  stamping matches D-05 a + lead adjustment; no amount conversion; legal path VM -> BudgetRepository; CE rethrown; gate and
  Money Radar untouched; R0-6 chain reasoning. Notes: N1 suggestion banner string hardcodes "€" (pre-existing, follow-up);
  N2 create does not require budget.currency == confirmed (no caller hits it); N3 missing home-read failure / CE tests;
  N4 stale StateFlow read in a test; N5 no timeout on homeCurrency().first(); N6 legacy unsupported-currency rows fail closed.
  Follow-up debt: BudgetStatusSnapshot :28 inline copy of the set.
- Targeted queue (default settings, EXECUTED, fingerprint 9bdc08ce…): DashboardBudgetProvenanceChainTest
  vr-20261015-105727-bcd5e991 PASS 3/3 (R0-6 now passes: RG06A-1 fixed in worktree); BudgetViewModelCurrencyProvenanceTest
  vr-20261015-110549-b66a4367 FAIL 9/10 ("unsupported or malformed..." :190 stale state read after clearError — test issue,
  reviewer N4); BudgetViewModelAutopilotQualityTest 8/8; BudgetAutopilotUiContractTest 5/5;
  ComputeDashboardWidgetsUseCasePaceWiringTest 20/20; NotificationExpenseDashboardPipelineTest 3/3;
  CancellationPropagationContractTest 4/4; R2 re-run ReceiptDuplicateFingerprintCollisionTest vr-20261015-111827-253d2f20
  7/7 (after the F1 cleanup).
- Iteration 1 (writer): ISSUE-1 fix, split the failing test, add home-read failure + CE tests.
- R3 iteration 1 (writer): duplicated provenance rule removed (both sites call SavingsGoalCurrencyPolicy.knownCurrencyOrNull);
  failing test split; home-read failure + CE tests added. reviewer-strict confirmation: ACCEPT-WITH-NOTES (ISSUE-1 fixed,
  stamping table unchanged; notes: non-scope CE leaves _manualState Loading (pre-existing pattern; finally-reset follow-up);
  BudgetStatusSnapshot :28 inline set debt; Confirm button shows for any row if a stored home currency were unsupported —
  fails closed).
- Re-runs (EXECUTED, default settings, fingerprint b0b10b0a…): BudgetViewModelCurrencyProvenanceTest
  vr-20261015-112827-00c2bf03 13/13; DashboardBudgetProvenanceChainTest vr-20261015-113450-4f3beb76 3/3;
  BudgetAutopilotUiContractTest vr-20261015-113654-eee14a31 5/5. Earlier unchanged-file R3 runs remain valid for those
  classes. R3 STATUS: targeted gate PASS; PARTIAL pending shared unit-tests/static-guards after R6.

### Batch R4 (RG01A-1)
- R0-4 fail-first RestoreMaintenanceModeConfirmationFaultTest vr-20261015-113845-343e4c2b: EXECUTED, 5 run / 1 passed /
  4 failed: b1, b2, c_next_launch, d -> "RG01A-1: latched CRITICAL after reschedule failure (mode=CRITICAL_RECOVERY_REQUIRED,
  ...)"; c_control (seeded NORMAL+pending recovers next launch) PASS. => RG01A-1 RUNTIME-CONFIRMED (Robolectric).
- R4 writer: RestoreMaintenanceMode split catches + retainDurableResumePending; 3 P7BugFixesTest + 2 ASCRT approved
  expectation changes; 2 new RG01A-2 tests; CL-19 WI-5 line.
- reviewer-strict: ACCEPT-WITH-NOTES (classification correct, no durability failure downgraded, barriers keep denying,
  next-launch retry bounded; N1 already-CRITICAL early return should re-attempt durable CRITICAL; N3 restart wording for
  export/asset-rollback flows; N4 test-only checkRestoreJournal mapping -> D-23). RG01A-1 is fixed for startup checked
  resume and backup-export paths ONLY; in-app restore-abort exit(false) paths (DBRI :868-881, e.g. :985/:1016/:1039) still
  latch CRITICAL -> D-23/R4b (owner).
- architecture-guardian: CHANGES-REQUIRED — FG-10 JUnit CancellationSafetyArchitectureGuardTest predicted FAIL at the new
  broad catch (~:331) because with CRLF the CE sibling catch falls outside the 200-char window; Python scanner, FG-11/13/16,
  legal paths PASS; no baseline/allowlist edit needed. Notes: LEGAL_PATHS :439 wording; CL-19 "once per launch".
- Targeted (EXECUTED, default settings, fingerprint 33a63e72…): RestoreMaintenanceModeConfirmationFaultTest
  vr-20261015-114338-d4274eae 5/5 (R0-4 now passes); P7BugFixesTest vr-20261015-115039-5d1670ab 37/37;
  AppStartupCoordinatorRecoveryTest vr-20261015-115305-cf3ea625 44/44; WorkerRestoreRegressionTest
  vr-20261015-115515-6ee3f69e 20/20; BackupRestoreContractTest vr-20261015-115704-5c9bfb91 23/23.
- Iteration 1 (writer): restructure both resume catches (CE first; single catch(Exception) with PersistenceException
  branch) for the guard; N1 fail-closed; LEGAL_PATHS + CL-19 wording. Re-run R4 filters + CancellationSafetyArchitectureGuardTest.
- R4 iteration 1 confirmation (architecture-guardian): ACCEPT-WITH-NOTES (catch structure equivalent; JUnit guard passes
  independent of CRLF/LF; N1 change safe, reentrant lock; note: CRITICAL reason overwritten with MODE_PERSISTENCE_FAILURE
  as at HEAD). Lead narrowed the LEGAL_PATHS line to the RestoreMaintenanceMode resume paths and named D-23.
- Re-runs (EXECUTED, default settings, fingerprint a5fc17a0…): CancellationSafetyArchitectureGuardTest
  vr-20261015-120030-80137f2a 22/22; RestoreMaintenanceModeConfirmationFaultTest vr-20261015-120520-dcba4c39 5/5;
  P7BugFixesTest vr-20261015-120723-1c42daad 37/37; AppStartupCoordinatorRecoveryTest vr-20261015-120938-7e9181b8 44/44;
  WorkerRestoreRegressionTest vr-20261015-121137-9de2d499 20/20; BackupRestoreContractTest vr-20261015-121328-0ac6ed2f 23/23.
- R4 STATUS: targeted gate PASS; PARTIAL pending shared unit-tests/static-guards after R6; RG01A-1 closure limited to
  RestoreMaintenanceMode resume paths until D-23/R4b.

### Batch R5 (RG01-N1)
- R0-5 fail-first ReceiptAssetRepeatRestoreTest vr-20261015-121819-fd3c410f: EXECUTED, 7 run / 4 passed / 3 failed.
  repeat_restore_adopts_proof_matching_restored_file FAIL "RG01-N1 second restore task status (error=ASSET_TARGET_COLLISION)
  expected COMPLETED but was FAILED"; adopted_file_survives_not_applied_cas FAIL (begin() rejects with ASSET_TARGET_COLLISION);
  controls (non-matching bytes, same-size different hash, no-proof repo + engine) PASS. => RG01-N1 RUNTIME-CONFIRMED.
  Unexpected 3rd failure engine_not_applied_still_deletes_operation_published_file: JournalDurabilityException — harness
  (hand-built JournalEntry fails recovery-identity validation on re-read -> Corrupt). Lead test-only fix (debug iteration
  1): engine fixture now builds the entry via beginJournal -> transitionTo(ASSETS_RESTORING).copy(assetTasks) + writeJournal.
  Pre-fix re-run skipped (decision already made by test a); the corrected controls run with the post-fix queue.
- R5 writer: ReceiptAssetRecovery adopt-on-proof (begin + stage re-check; adoptedExisting through admit/NeedsCas;
  NOT_APPLIED keeps adopted files); BackupRestoreViewModel receiptAssetWarningCount (count only) + screen text; string en/es/el;
  BackupRestoreViewModelTest count test.
- reviewer-strict: ACCEPT-WITH-NOTES (adoption only on matching proof, never overwrites; flag flow correct; CAS/journal/DAO
  unchanged; count-only UI). Notes: N1 File.isFile follows symlinks (use NOFOLLOW_LINKS or fix comment); N2 count = warnings
  not images; N3 no test for the stage() re-check; N4 residual comment should mention the unjournaled flag; N5 legacy task
  reason order changed (still fails closed); N6 count only shown in restart banner (fine).
- Targeted (EXECUTED, default settings, fingerprint 23e21153…): ReceiptAssetRepeatRestoreTest vr-20261015-122617-197e6218 7/7
  (R0-5 passes incl. corrected engine control); DatabaseBackupRepositoryImplTest vr-20261015-123203-3313245b 78/78;
  AppStartupCoordinatorRecoveryTest vr-20261015-123434-f1e4960a 44/44; BackupRestoreViewModelTest
  vr-20261015-123657-6e96432e 18/18. Privacy review pending; iteration for N1/N3/N4 queued.

### R4b plan (D-23; release-planner read-only) — APPROVED by owner for restore + reset scope
- exit(true) never throws WRE; WRE reaches lockCriticalForFailure only at pre-destructive sites: restore :985, :1016, :1039,
  :1064, :1096/:1107-1116, :1136/:1365, :1387; reset :2840, :2938 (content state CERTAIN: destructiveStarted=false, live DB
  untouched, journal FAILED preserved). Import sites (debug-only) and cancel-cleanup sites stay CRITICAL.
- Today: permanent "unrecoverable" CRITICAL screen (MainActivity :437-449) over an intact DB with no in-app recovery — user
  likely clears data (real loss). NEW PRIVACY FINDING (NEW-R4B-P): exit throwing before cleanup leaves the decrypted
  extraction tempDir under cacheDir and staged DB files on disk; no startup cleanup exists. The R4b helper lets the existing
  cleanup run.
- Fix: DBRI-only helper exitAfterPreDestructiveAbort() catching only WorkerRescheduleException (bounded log), used at the
  listed sites; PersistenceException / JournalDurabilityException / rollback failures / destructiveStarted paths stay CRITICAL;
  restart screen neutral wording ("The app must restart to finish a database operation. Changes are paused until it
  restarts.") with es/el. Out of scope noted: :1142-1152 returns without exit; :2737/:2741 in-process exit retry;
  BackupRestoreViewModel.dismissRestartRequired :339-342 uncaught WRE.
- Tests: fail-first RestoreAbortWorkerResumeDeferredTest (wrong-password abort + injected reschedule failure -> RESTART_REQUIRED,
  live DB unchanged, journal reason BUNDLE_EXTRACTION_FAILED, temp/staged files gone, next launch recovers) + reset variant;
  CRITICAL controls. Filters listed in the plan.
- R5 privacy review (privacy-security-guardian): PASS / ACCEPT-WITH-NOTES (count only reaches UI; no new logs; app-private
  storage + hash equality make adoption non-exposing; residual unjournaled flag = data-loss not exposure; count = warnings
  not images; out of scope: BackupRestoreViewModel :359 full exception log, debug only).
- R5 iteration 1 (writer): matchesProof uses Files.isRegularFile(NOFOLLOW_LINKS) (minSdk 26); residual KDoc; new test
  engine_stage_recheck_rejects_file_changed_after_begin. Re-run: ReceiptAssetRepeatRestoreTest vr-20261015-130938-d3060d8b
  8/8; DatabaseBackupRepositoryImplTest vr-20261015-131134-dd287ddb 78/78. R5 STATUS: targeted gate PASS; PARTIAL pending
  shared gate; iteration confirmation review pending.

### Batch R6 (RG02B-1 + RG02B-4; HD-1/HD-2/HD-3 approved)
- Writer session 1 stopped on a pinned test (ExplainPendingReviewUseCaseTest :327-338 asserted the raw "Network timeout" text
  in the persisted errorMessage = the HD-2 leak itself). Lead ruled HD-2 covers changing that one assertion (disclosed to
  the owner). Writer session 2 finished tests. Production: 5 providers bounded PARSER_FAILED/UNKNOWN_ERROR + CE rethrow +
  class-name-only logs; JudgePendingReviewDuplicateUseCase CE rethrow + bounded persisted code + fixed returned message;
  ExplainPendingReviewUseCase bounded persisted code (+ throwable dropped from its log). HD-1: config/baselines/
  cancellation.json — exactly the HybridDedupeJudgeService fingerprint + occurrence_counts entries removed (48/48 remain).
- reviewer-strict: ACCEPT-WITH-NOTES (CE handling correct; only bounded text persisted/displayed; logs bounded; error types and
  hybrid fallback unchanged; baseline valid, ratchet should pass). Notes: stale exemptions now cover fixed code
  (cancellation_allowlist.yml ~:250-522; CancellationSafetyArchitectureGuardTest KNOWN_VIOLATIONS :78-88) — removal needs owner
  approval; ReviewViewModel :376/:730 catch(Exception) without CE rethrow + Timber.e(e) (RG02B residual); cancelled Judge
  leaves a RUNNING row (non-blocking); success rationale stored by design.
- privacy-security-guardian: ACCEPT-WITH-NOTES. NEW SAME-CLASS FINDINGS OUTSIDE R6 (candidate batch R6b, privacy P2 where
  persisted): raw e.message persisted/returned — GenerateDashboardBriefingUseCase :125; SuggestReceiptExtractionUseCase :165/:170;
  SuggestCategoryFallbackUseCase :218/:223; InterpretFinancialQueryUseCase :50; CategorizeReceiptItemsUseCase :225 (when
  persistItemTextAllowed); AiServiceError.Unknown(e.message) — OnDeviceDashboardBriefingService :50; OnDeviceReceiptAssistService
  :56/:96 -> SmartReceiptAssistService :373-374 -> persisted (SuggestReceiptExtractionUseCase :356); ~20 raw-throwable Timber
  sites (debug-only logging) incl. CategorizationAssistInputBuilder :138 logging a merchant key. Guard gap:
  verify_pii_logging_boundaries.py only flags e.message inside log calls / exception constructors (:114-137), not
  Failure(Unknown(e.message)). Rationale payloadJson uncapped (CloudDedupeJudgeService :177, OnDeviceDedupeJudgeService :115).
- Targeted (EXECUTED, default settings, fingerprint 3ae5b17c…): CloudDedupeJudgeServiceTest vr-20261015-124509-f585ffbb 9/9;
  CloudReviewExplanationServiceTest vr-20261015-125223-a6931ae7 6/6; HybridDedupeJudgeServiceTest vr-20261015-125419-13cb5083 2/2;
  OnDeviceReviewExplanationServiceTest vr-20261015-125615-3c95f888 9/9; OnDeviceDedupeJudgeServiceTest
  vr-20261015-125810-868f8eab 11/11; JudgePendingReviewDuplicateUseCaseTest vr-20261015-130006-8b563ff2 9/9;
  ExplainPendingReviewUseCaseTest vr-20261015-130201-9af2024b 13/13; HybridRouterIntegrationTest vr-20261015-130357-1eac749d 20/20;
  CloudProviderTransportPayloadTest vr-20261015-130553-0edc9762 45/45; CancellationSafetyArchitectureGuardTest
  vr-20261015-130748-1e967c7e 22/22. R6 STATUS: targeted gate PASS; test-file review pending; PARTIAL pending shared gate.
- Confirmation review (reviewer-strict) of R6 tests: ACCEPT-WITH-NOTES (each new test fails on pre-R6 code; HD-2 assertion change
  confined; OnDevice reflection sound). Test-strength follow-ups: JSON-parse sentinel never reaches e.message under JVM org.json
  (use a duplicate-key payload); no Timber-tree test for ExplainPendingReviewUseCase :127 log change; OnDevice UNKNOWN tests do
  not assert a log line exists. R5 iteration: ACCEPT-WITH-NOTES (re-check test genuinely exercises stage(); no symlink test;
  KDoc omits two low-risk TOCTOU windows).

### Batch R4b (D-23)
- Fail-first RestoreAbortWorkerResumeDeferredTest (release-test-author; extract mocked to write decrypted residue then fail with
  WrongBackupPasswordException; scheduleAll failed summary) vr-20261015-131832-fe2e2577: EXECUTED, 5 run / 3 passed / 2 failed:
  wrong_password_abort_with_worker_resume_failure... FAIL "D-23: original WrongBackupPasswordException must be returned (was
  WorkerRescheduleException)"; reset_safety_backup_failure_with_worker_resume_failure... FAIL "D-23: reset must not surface the
  worker reschedule failure"; controls (healthy resume -> NORMAL; PersistenceException on exit -> CRITICAL; post-destructive
  rollback failure -> CRITICAL) PASS. => D-23 RUNTIME-CONFIRMED. Note: failure journal reason is stored as UNKNOWN_ERROR
  (RestoreJournal.failureReasonCode allowlist), not BUNDLE_EXTRACTION_FAILED as the plan text said.
- R4b writer: DBRI private helper exitAfterPreDestructiveAbort() (catches only WorkerRescheduleException; bounded Timber.w)
  at restore :1001/:1032/:1055/:1080/:1112/:1132/:1152/:1403 and reset :2856/:2954; skipped cancel-cleanup :1377/:2942,
  import (debug-only), exit(true) sites, :495/:796/:2757 runCatching, :2753 retry. MainActivity restart message ->
  string restart_required_database_operation (en/es/el). LEGAL_PATHS :440 + CL-19 :62 updated (lead corrected the
  cancel-cleanup mechanism to finishCancelledRestore per reviewer N2).
- reviewer-strict: ACCEPT-WITH-NOTES — every replaced site precedes destructiveStarted (restore :1188 / reset :2881);
  :1403 reachable only pre-swap (SWAPPING-without-destructive window takes exit(true)); helper lets PersistenceException /
  JournalDurabilityException / CE propagate to CRITICAL; cleanup (tempDir.deleteRecursively, cleanupRestoreStaging) now runs ->
  NEW-R4B-P privacy residue fixed on these paths; writes stay denied; no existing test breaks. Notes: N1 no deferral test for
  :1403/:2954/:1112; N3 PersistenceException from the helper still skips cleanup on that CRITICAL path (pre-existing); N4
  deferral only Timber-logged, no diagnostics event; N5 lock-screen title/button still hard-coded English.
- architecture-guardian: ACCEPT-WITH-NOTES — FG-10 Python + JUnit predicted PASS (specific non-CE catch; per-file baseline
  count unchanged), FG-13/16/26 PASS, db_access/ownership unchanged.
- Targeted (EXECUTED, default settings, fingerprint e762bcb6…): RestoreAbortWorkerResumeDeferredTest vr-20261015-132847-10c70c3a
  5/5; DatabaseBackupRepositoryImplTest vr-20261015-133532-d95cebab 78/78; P7BugFixesTest vr-20261015-133812-83aaffd5 37/37;
  RestoreMaintenanceModeConfirmationFaultTest vr-20261015-134040-51b303d9 5/5; AppStartupCoordinatorRecoveryTest
  vr-20261015-134241-45a2eca2 44/44; BackupRestoreContractTest vr-20261015-134451-191a856f 23/23; WorkerRestoreRegressionTest
  vr-20261015-134652-7d026ede 20/20; CancellationSafetyArchitectureGuardTest vr-20261015-134847-cc0d6f76 22/22.
  R4b STATUS: targeted gate PASS; PARTIAL pending shared gate.

### Shared gate for R2..R6 + R4b (queued 2026-10-15): unit-tests then static-guards on the combined uncommitted worktree.
- unit-tests vr-20261015-135135-1be3c8c9: PASS, exit 0, EXECUTED, default settings, fingerprint start == end (f16e83d2…);
  8009 run / 7733 passed / 0 failed / 276 skipped (baseline before R1 was 7939/7663/0/276; +70 new tests, skip count
  unchanged).
- static-guards vr-20261015-140235-da3782f3: PASS, exit 0, 25/25 blocking guards PASS (incl. cancellation with the HD-1
  baseline shrink, pii_logging, privacy, receipt_link, db_access, event_writers, money, guard_docs_truth, ignored_test_budget);
  known_good_state rows 1-6 PASS, row 7 test_result_freshness SKIP (stamp missing — not a pass). Lead re-checked both
  result.json files (status/exit/HEAD/fingerprints).
- BATCH STATUS (uncommitted worktree, HEAD 279b7c92): R1, R2, R3, R4, R4b, R5, R6 = PASS at batch gate (independent reviews
  + targeted tests + shared unit-tests + static-guards). Not final acceptance: final-candidate app-check, lint (new strings /
  unused-resource warnings), connected-tests (R0-1b, D-22) and independent final review remain. RG01A-1 now closed for all
  RestoreMaintenanceMode resume paths and restore/reset pre-destructive aborts; import (debug-only) and cancel-cleanup stay CRITICAL.
- Open follow-ups recorded: R6b privacy sweep candidates (other AI use cases/providers persisting e.message; pii_logging guard
  gap); stale cancellation exemptions (owner approval needed to remove); ReviewViewModel :376/:730 catches; R1 follow-ups
  (email path :1238-1245 HZ-02, cloud category id validation, DefaultExpenseCategoryAssignmentService FG-16 log); R2 F3
  ScannedReceipt TODO wording; R3 N1 "€" suggestion string, BudgetStatusSnapshot inline set, Loading-on-CE; R5 symlink test,
  TOCTOU KDoc; R6 test-strength notes; R4b N1 extra deferral tests, N5 lock-screen title/button English; V11-NEW-1/R1b P3;
  D-05b; MainActivity CRITICAL screen still has no in-app recovery for genuinely uncertain states.

## Remediation lane — second sequence (R6b, R7..R13, G1; approved 2026-10-15 "do all your recommendations")
- Commit: branch release-1.0-remediation, d908405d (fixes R1..R6+R4b) + b9e98347 (records); bug-fixes untouched; no push.
- R6b writer: E1-E8 + CategorizationAssistInputBuilder log + HD-R6b-1 (CE -> PENDING reset in NonCancellable, rethrow).
  reviewer-strict ACCEPT-WITH-NOTES (all catches CE-first; only bounded text; behaviour unchanged; notes N1 reset failure
  masks CE -> wrap; N2 CE test uses mock CE, add real-cancellation variant; N3-N5 minor). privacy-security-guardian
  ACCEPT-WITH-NOTES (no leak to persistence/UI/logs on changed paths; F3 undercount: 34 raw-throwable log sites in 14 AI
  files, debug-only logging; NEW privacy follow-up: CategorizeReceiptItemsUseCase.validateResult writes item amount (:417)
  and AI category name (:428) into errorMessage even in STORE_REDACTED mode).
- R6b targeted (EXECUTED; fingerprint ff06bb7d…): GenerateDashboardBriefingUseCaseTest vr-20261015-145526-f2bb5f94 10/10;
  SuggestReceiptExtractionUseCaseTest vr-20261015-145727-ecc64457 10/10; SuggestCategoryFallbackUseCaseTest
  vr-20261015-145933-3d8ee450 11/11; CategorizeReceiptItemsUseCaseTest vr-20261015-150127-483e8ba5 8/8;
  OnDeviceDashboardBriefingServiceTest vr-20261015-150324-dcf5049a 7/7; OnDeviceReceiptAssistServiceTest
  vr-20261015-150517-a5e722e0 FAIL 12/13 (parseResponse malformed-JSON test expected a PARSER_FAILED log; lead diagnosis:
  CloudJsonParser.extractFirstJsonObject pre-validates JSON and returns null, so the catch is unreachable for syntax errors;
  test-only fix: drop that one positive-log expectation, keep null + no-throwable/no-sentinel oracle; debug iteration 1);
  SmartReceiptAssistServiceTest vr-20261015-150721-8eeaac8e 9/9; ReviewViewModelAiAssistCancellationTest
  vr-20261015-150918-93bb4cb7 8/8; ReviewViewModelPrivacyDenialTest vr-20261015-151115-1df4e0c4 6/6;
  CancellationSafetyArchitectureGuardTest vr-20261015-151315-985f2716 22/22.
- R7 fail-first ReceiptSideEffectPlannerSuggestionRaceTest (first run vr-20261015-143538-a337f044 compile error: invalid
  `import io.mockk.coAnswers` — lead removed it) vr-20261015-144358-9874ae7d: EXECUTED 3 run / 1 passed / 2 failed —
  interleave "RG07A-1: concurrent claim link was reverted by stale suggestion write"; identical retry "RG07A-1: identical
  retry must not add a second MATCH_SUGGESTED event"; control PASS. => RG07A-1 RUNTIME-CONFIRMED.
- Plans received: R8 (owner D-06 for coordinator test), R9 (H-R9-1 pending: A shared partial flag vs B goal-only flag),
  R10+R11 (no new decisions), R12/R13/G1 (decisions listed in the handoff). R13 planner: V17B-N1 CONFIRMED by reading —
  NotificationIntakeDao enqueue-failure path sets FAILED_FINAL without terminalAt, NotificationIntakePayloadRepairer purges
  only terminalAt != null -> payload retained (privacy); fix = DAO SQL change (owner decision R13b). New V-R13-1 (P3):
  coordinator reports FAILED_RETRYABLE on the transition that just became FAILED_FINAL.
- R6b iteration (writer): N1 reset failure no longer masks CE (try/catch inside NonCancellable, bounded log); N2 real-
  cancellation test added. R6b re-runs: CategorizeReceiptItemsUseCaseTest vr-20261015-153503-e4b1ee4a 9/9;
  OnDeviceReceiptAssistServiceTest vr-20261015-153658-7f692768 13/13. R6b STATUS: targeted gate PASS; PARTIAL pending
  shared gate.
- R7 writer: ReceiptSideEffectPlanner Suggested branch -> conditional updateMatchSuggestion, event only on 1 row, else
  Skipped(ALREADY_PROCESSED); receipt_link_allowlist.yml :100 rationale corrected. STOPPED (correctly) on the
  db_ownership_policy edit: the policy is pinned by sha256 in db_ownership_policy.signatures.accounting.json
  (sourcePolicySha256/candidateSha256/sourceTreeSha, counts, coverage records) and db_ownership_policy.promotion.json
  (active/candidate/accounting/evidence/tree sha) + candidate.yml -> requires regeneration via
  scripts/migrate_db_policy_signatures.py + scripts/ci/promote_db_policy_v2.py (beyond the approved 1:1 hand edit) ->
  owner decision R7-REGEN pending. R7 targeted (EXECUTED, fingerprint dcc34d6b…): ReceiptSideEffectPlannerSuggestionRaceTest
  vr-20261015-153859-4c294550 3/3; ReceiptSideEffectPlanner*Test vr-20261015-154100-908d566f 17/17;
  ReceiptMatchLifecycleServiceTest vr-20261015-154304-6087f72a 18/18; ReceiptMatchingWorkerTest vr-20261015-154504-d80cc0ca
  27/27. R7 STATUS: BLOCKED-ON-HUMAN (static-guards db_access expected to fail until R7-REGEN).
- R8 fail-first (EXECUTED, same fingerprint): SavingsSweepAcceptanceCoordinatorTest vr-20261015-152243-06e71943 29 run / 24 / 5
  failed — the 4 rg05a1 suppression tests + the approved changed recomputed_sweep... test ("... but was Available(...)");
  negative controls PASS. SavingsGoalsViewModelTest vr-20261015-153101-01312d61 34 / 33 / 1 failed (terminal clear).
  SavingsSweepMonthMigrationTest vr-20261015-153303-d3644ea9 5/5 (new v150 test is a guard, not fail-first).
  => RG05A-1 RUNTIME-CONFIRMED.
- R7 reviewer-strict: ACCEPT-WITH-NOTES (no code defects; WHERE clause prevents clobbering claim/match/reject; event only on
  1 row; Skipped not treated as failure/retry; event_writers counts unchanged; allowlist rationale now truthful). Notes: policy
  rename still required (R7-REGEN, owner-approved 2026-10-15); REJECTED/MANUALLY_MATCHED receipts no longer re-suggested on
  re-save (intended); residual stale full-row write ReceiptLifecycleCoordinator :517-526 (follow-up).
- R8 writer: NEW SavingsSweepMonthConsumption (coordinator check moved verbatim); coordinator uses it in-transaction + public
  const; MonthlySavingsSweepUseCase returns NoRecommendation for consumed month before goal/budget reads; VM terminal clear.
  reviewer-strict ACCEPT-WITH-NOTES (exact same predicate; month-end identical incl. boundaries/DST; fail-closed; Hilt OK; notes:
  stale test comments; no 1st-of-month/DST test). Targeted (EXECUTED, fingerprint afe1f8bd…): SavingsSweepAcceptanceCoordinatorTest
  vr-20261015-154955-90f3f636 29/29; MonthlySavingsSweepUseCaseTest vr-20261015-155608-412da8e1 28/28; SavingsGoalsViewModelTest
  vr-20261015-155808-b34e74f0 34/34; SavingsSweepMonthMigrationTest vr-20261015-160003-d9c97102 5/5. R8 STATUS: targeted gate PASS;
  PARTIAL pending shared gate.
- R7-REGEN procedure (architecture-guardian, read-only): sanctioned route from precedent (GR-14_HANDOFF Pattern E, GR-14h.yml,
  ffe0a69b/e9d3839b): edit generation input docs/ci/db-findings/GR-08-seeds.yml + active policy line; regenerate candidate +
  accounting via `migrate_db_policy_signatures.py --generate --seed-rows docs/ci/db-findings/GR-08-seeds.yml`; `--verify` exit 0;
  NOT --force-repromote (would drop 22 active-only rows; archive check refuses); promotion.json untouched; candidate stays 407.
  Drift risk: accounting sourceMutations rescans the whole tree -> regenerate last with only R7 uncommitted. Guard pytest suites
  have no dedicated runner profile (guard_tests guard in static-guards to be checked).
- R9 writer (option B) + reviewer-strict ACCEPT-WITH-NOTES (same-currency goals bit-identical; reuse convertAmount/policy; MC gate
  untouched; key new tests fail on HEAD; notes N1 shared helper feeds legacy goal to all DashboardBudgetProvenanceChainTest
  tests; N2 SynthesisEngineStressTest goal helper defaults LEGACY -> fixture to EXPLICIT; NaN goal now skipped). Targeted
  (EXECUTED, fingerprint 4fc98592…): SynthesisEngineGoalReserveCurrencyTest vr-20261015-160717-76577966 8/8 (stdout count);
  SynthesisEngineTest vr-20261015-161400-4772285e 13/13; SynthesisEngineGoldenTest vr-20261015-161616-a45e54ba 3/3;
  SynthesisEngineStressTest vr-20261015-161830-43b5bac9 56/56; SynthesisEngineRatePolicyPinTest vr-20261015-162030-688926dc 8/8;
  FinancialWeatherRepositoryTest vr-20261015-162231-d5f8c63a 13/13; DashboardBudgetProvenanceChainTest vr-20261015-162431-0e2f21a7
  4/4; CalculateFinancialForecastUseCaseTest vr-20261015-162632-4983f691 6/6; ForecastRunwayIntegrationTest
  vr-20261015-162832-0d60f73a 4/4; DashboardCurrencyIntegrationTest vr-20261015-163033-b2a13352 7/7; CrossGroupIntegrationTest
  vr-20261015-163233-6ed08213 9/9; GoldenMasterVerificationTest vr-20261015-163437-943f9f12 22/22. Iteration (N1/N2 fixtures) queued.
- R9 iteration (test hygiene N1/N2): DashboardBudgetProvenanceChainTest vr-20261015-164034-b121c1f1 4/4 (legacy goal only in the
  MC test; asserts the goal reached forecast input); SynthesisEngineStressTest vr-20261015-164449-3147cd62 56/56 (EXPLICIT
  fixtures). R9 STATUS: targeted gate PASS; PARTIAL pending shared gate.
- R10 fail-first ExportOptionsViewModelTest vr-20261015-164646-b8558768: 25 run / 22 / 3 failed — "RG12-03: stale success must not
  survive a range change", "RG12-03: stale run must not publish success", "RG12-02: format switch must not leave loading stuck"
  (all three planned fail-first tests; runner mislabelled the RG12-02 one as unexpected). => RG12-03 and RG12-02 RUNTIME-CONFIRMED.
- R11 fail-first: GroupTransactionCoordinatorTest vr-20261015-164853-dfb8a430 39 / 38 / 1 failed (RG04A-1 Room test: Success
  instead of Error); GroupsRepositoryImplTest vr-20261015-165106-21752155 16 / 15 / 1 failed ("RG04A-1:
  createSystemExpenseAndLinkToGroup/UNEQUAL must return Error, was Success"); boundary pins PASS. => RG04A-1 RUNTIME-CONFIRMED.
- R10 + R11 writer; reviewer-strict ACCEPT-WITH-NOTES for both (R10: generation bump before cancel prevents stale publication;
  range snapshot; privacy block untouched; notes: PRE-EXISTING orphan final export file (possibly plaintext) when cancellation
  lands after file work but before main-thread resume (ExportOptionsViewModel :386 vs cleanup :444-451; sweep keeps final files)
  -> follow-up; cancelActive nulls job so runs may overlap briefly (RP-19); RG12-02 test does not assert secondOut deleted.
  R11: same SplitCalculator rule; same date to coordinator; residuals: CUSTOM_AMOUNT/PERCENT fail closed when any member joined
  after the date (D-10 batch), payer check not date-aware, no positive test for UNEQUAL naming only earlier members).
- Targeted (EXECUTED, fingerprint d512937d…): ExportOptionsViewModelTest vr-20261015-165508-a43d8cda 25/25;
  ExportOptionsViewModelPrivacyDenialTest vr-20261015-170243-32103b04 5/5; ExportJobSerializerTest vr-20261015-170444-c9b2eece 4/4;
  GroupsRepositoryImplTest vr-20261015-170645-e2eb6628 16/16; GroupTransactionCoordinatorTest vr-20261015-170841-737bebba 39/39;
  SplitCalculatorTest vr-20261015-171044-36e962c5 12/12; CustomSplitParserTest vr-20261015-171247-ebec1b6b 12/12.
  R10, R11 STATUS: targeted gate PASS; PARTIAL pending shared gate.
- R13b plan (planner): one SQL line in NotificationIntakeDao.markEnqueueFailed (terminalAt = CASE WHEN attempts + 1 >= maxAttempts
  THEN :nowMs ELSE terminalAt END); no signature/schema/policy change (policy keyed on caller, not SQL). Existing stuck rows NOT
  repaired (data repair = owner decision). V-R13-1 coordinator status mismatch and FAILED_RETRYABLE double-count = separate.
- G1 cloud_payload entry: suppresses nothing (read-only scan of 1110 production files: 0 raw R1/R2 findings) -> replace with
  explicit empty list `[]` (a comments-only file is rejected by allowlist_compliance); matcher fix still required (RG17A2-1);
  symbol constants to be defined by the matcher fix.
- R12/R13/R13b writer + reviewer-strict ACCEPT-WITH-NOTES (R12: Settle fully removed; SharedExpenseManager dormant confirmed
  (never injected); follow-up doc drift LEGAL_PATHS :643-663/:697-703 + GroupTransactionCoordinator :64 still describe the dormant
  manager; es/el title untranslated (baselined). R13b: SQL correct; other FAILED_FINAL writers already set terminalAt;
  RECALIBRATION: the enqueue-failure FINAL branch is currently UNREACHABLE (markEnqueueFailed only on freshly inserted rows,
  attempts 0 of 5) -> V17B-N1 latent (P3), fix is preventive; NEW follow-up: encrypted (ciphertext) payloads of FAILED_FINAL rows
  are not purged by any terminalAt-keyed path (retention purgeRawPayload clears plaintext only) — pre-existing, unreachable via
  enqueue today. R13: harness avoids hang; T5a fails on HEAD; T5b cannot distinguish ELSE terminalAt vs ELSE NULL (seed non-null).)
- Targeted (EXECUTED, fingerprint a16c1981…): NotificationIntakeEnqueueFailureRoomTest vr-20261015-171931-7ad054a0 6/6;
  NotificationIntakeCoordinatorTest vr-20261015-172709-62160ebf 19/19; NotificationIntakeRestoreBarrierTest
  vr-20261015-172911-349fc05e 6/6; ui.screens.groups.* vr-20261015-173114-7cb00df0 21/21.
  R12, R13, R13b STATUS: targeted gate PASS; PARTIAL pending shared gate.
- G1 + stale exemptions (writer): cloud_payload matcher exact rule+path+symbol (SYMBOL_R1_RAW_REQUEST_BODY / SYMBOL_R2_UNPROVED_POST_BODY,
  scan_file_findings), tests rewritten/added; cloud_payload_allowlist.yml -> `[]`; cancellation_allowlist.yml 77 -> 19 (53 class-only
  orphans + 5 stale exact entries for R6/R6b-fixed providers); owner DECISION line (D-19); expiry plan §5a (+ expired
  RAW_RUN_CATCHING note); CancellationSafetyArchitectureGuardTest KNOWN_VIOLATIONS -7 (Cloud/OnDevice dedupe/review/briefing,
  Hybrid, SuggestCategoryFallbackUseCase).
- reviewer-strict ACCEPT-WITH-NOTES (no weakening; matcher strictly stronger; detection unchanged; remaining 19 byte-identical;
  53 could never match; commit coupling: R6b CE fix must be committed with/before the exemption removals). architecture-guardian
  ACCEPT-WITH-NOTES (read-only scan of 1110 files: cancellation findings identical with HEAD (77) vs new (19) allowlist — 111
  violations, ratchet NEW=0 RESOLVED=0; cloud_payload 0 findings; allowlist_compliance clean; no guard test pins counts).
- CancellationSafetyArchitectureGuardTest vr-20261015-173647-b931b800 PASS 22/22 (EXECUTED; fingerprint db7ea268…) — the 7 removed
  KNOWN_VIOLATIONS are proven unused.

### Shared gate (second sequence; tree = HEAD b9e98347 + R6b, R7 code, R8..R13b, G1 uncommitted)
- unit-tests vr-20261015-174137-443d2e41: PASS, exit 0, EXECUTED, default settings, fingerprint a20ac298…; 8067 run / 7791 passed /
  0 failed / 276 skipped (skip count unchanged).
- static-guards vr-20261015-175559-2cec18ef: FAIL, exit 2. 22/25 PASS; guard_tests VIOLATION (4 failed / 4961 passed / 28 skipped):
  (1) test_verify_cloud_payload_boundaries.py::test_unsafe_entry_paths_never_match["domain/privacy/*.kt"] — G1 matcher accepts a
  segment CONTAINING "*" (rejects only a segment equal to "*") -> G1 defect, fix required; (2-4) db_access tests with
  DB_POLICY_SOURCE_EVIDENCE_INVALID / INVALID_SOURCE_IDENTITY; db_access INFRA (exit 2, empty log); known_good_state active_db_gate
  gate_exit=2 (INFRA). Status: shared gate FAILED -> no commit; diagnosis dispatched (architecture-guardian, read-only).
- Diagnosis (architecture-guardian, read-only reproduction of db_access stages): exactly ONE untrusted evidence group — the R7
  planner row (ReceiptSideEffectPlanner.processMatchResult scannedReceiptDao `update` vs code `updateMatchSuggestion`,
  DB_V2_POLICY_MUTATION_NOT_FOUND; scan finding DB_UNAUTHORIZED_MUTATION :450). No other batch affects any policy row
  (R11/R8/R13b/R6b/R9/R10/R12/R13/G1 verified; sourceMutations 269 vs 269, only the R7 pair differs). cloud_payload test failure =
  G1 matcher accepted a segment containing "*". Lead fix (G1 debug iteration 1): reject any segment containing "*"
  (scripts/verify_cloud_payload_boundaries.py :134); read-only sanity check: "domain/privacy/*.kt" -> False, exact path -> True.
- Next: commit all batches except R7's three files (ReceiptSideEffectPlanner.kt, ReceiptSideEffectPlannerSuggestionRaceTest.kt,
  receipt_link_allowlist.yml), then R7 regeneration on a tree where only R7 is uncommitted, then full static-guards
  (which also validates the G1 fix; if it fails, fix forward in a new commit).
