# RP-00..RP-10 merged lanes — strict review and guard-regression attribution

> **Status:** review record, 2026-09-19. Read-only review; no code changed by this pass.
> **Reviewer:** strict-reviewer pass (six independent per-lane reviews + guard-suite attribution).
> **Scope:** remediation lanes RP-00..RP-10 that are MERGED into `bug-fixes` at tip `80979714`.
> Not merged (excluded): RP-04, RP-08, RP-09 (in-flight/unstarted). RP-00 is decisions-only (no code).
> In-span but out-of-scope lanes whose guard effects ARE attributed below: RP-12 (042a4296, 87e99385),
> RP-18 (22b82b2c), GR-14e/f/15 engine work (cbf1eeae, d48409c0, c866324c, PRs #6/#7).

## Method

1. Six independent strict reviews (one per merged lane) against each lane's landed diff,
   its plan doc under `docs/analyses and debug master/remediation/`, and the AGENTS.md invariants.
2. Guard attribution by running the identical 25-guard static suite at three points, all through
   `scripts/validation-runner.ps1`:
   - **BASE0** `86efaf89` (last commit before the first RP merge): run `vr-20260920-090845-86756f8f`
     (executed in the rp-07 worktree pinned to BASE0; the runner script itself postdates BASE0 and was
     copied in temporarily as an untracked helper, then removed).
   - **c92c4709** (pre-RP-12-remainder/RP-18 merge): run `vr-20260920-073837-afa2af4d`.
   - **TIP** `80979714`: run `vr-20260920-072646-fc267164`.
3. Per-finding blame (`git log 86efaf89..HEAD -- <file>`) for every guard whose state differed
   between BASE0 and TIP.

Suite shape:

| Point | Commit | Pass | Violations | Infra errors | Exit |
|---|---|---|---|---|---|
| BASE0 | 86efaf89 | 15 | 10 | 0 | 1 |
| c92c4709 | c92c4709 | 12 | 11 | 2 | 2 |
| TIP | 80979714 | 12 | 11 | 2 | 2 |

c92c4709 and 80979714 are byte-identical in the failing set (established 2026-09-20); the
BASE0→TIP delta below is therefore the whole program span.

## Verdicts (merged lanes)

| Lane | Landed as | VERDICT | Blocking issues |
|---|---|---|---|
| RP-01 cancellation safety | 44cb16fe | **PASS** | none (2 minor, 3 notes) |
| RP-02 write-barrier ownership | c57d37f1 + 8c11ecb3 | **PASS** (conditional) | 1 MAJOR (unlanded named deliverable + status overclaim) |
| RP-03 backup/restore | 367213d7 | **FAIL** | 3 MAJOR |
| RP-05 dashboard window | d808c48e / PR #9 | **FAIL** | 2 MAJOR |
| RP-06 pace/synthesis 6a+6b | 0f1f587b, b65439aa, PR #13, 686b3256 | **PASS** | none (2 minor, 4 notes) |
| RP-06 test-sweep commits | 7ef4188d..042d68db, 4b837ff1 | **PASS** (diff discipline) | none (1 minor: production code under a `test:` label) |
| RP-07 runway/calendar/insight | c92c4709 | **PASS** | none (3 minor, 3 notes) |
| RP-10/10c notification hygiene | 419e38c0, 2d128468, 73a50936, PR #11/#12 | **PASS** | none (2 minor, 3 notes) |

Overall: **FAIL as a set** — two lanes failed strict review and the program span introduced four
guard-state regressions (three attributable to in-scope lanes), partially offset by real improvements.

## Per-lane findings (evidence-based summary)

### RP-01 — PASS
Verified: U-001 best-effort event writes rethrow CE first (`TransactionLifecycleCoordinator.kt:247-255`
and both conversion catches); U-006 startup recovery rethrows CE (`AppStartupCoordinator.kt:351-397`);
U-002 guard rework is real (comment/string-blind scanner, `SourceTextSanitizer`, coordinator file-level
exclusion removed); behavioral runtime tests assert side-effect freedom after cancellation.
Issues:
- MINOR: U-003 clock seam is nominal — `guardToday()` is hardcoded `LocalDate.now()`, private
  (`CancellationSafetyArchitectureGuardTest.kt:397`); plan asked for an injected date.
- MINOR (FG-06/07-relevant): guard allowlist grew — +10 `KNOWN_VIOLATIONS` and +2 raw-`runCatching`
  entries (`:183-216`), five with `owner = "UNASSIGNED"`; ratchet-shaped (newly-exposed real
  violations, all with reason/issue/expiry) but no human approval is recorded for the growth and
  UNASSIGNED entries have no owner lane.
- NOTE: `TimeoutCancellationException` in create/update now aborts the mutation instead of falling
  back to base-currency defaults (`TransactionLifecycleCoordinator.kt:451-459, 864-874`) — plan-
  mandated and fail-closed, but a real timeout-path behavior change; pinned by a runtime test.
- NOTE: one new test landed `@Ignore`d, un-ignored later by 7ef4188d with a documented harness fix.

### RP-02 — PASS (conditional)
Verified: real check-before-write ownership landed (`SourceLinkWriterImpl.kt:88-92`,
`RecurringLifecycleEventWriter.kt:45-48`, independent `DataRetentionWorker` audit checks
`:203-217`); merge supersession vs the gr-14f mainline is documented and sound; 8c11ecb3's registry
20→18 claim is corroborated by the board JSON (`proven_helper 344→346`) and is fail-closed in
direction.
Issues:
- MAJOR (completeness/truthfulness): the plan's direct-event routing deliverable did NOT land —
  `RecurringOccurrenceMaterializer.kt` still makes 8 direct `lifecycleEventDao.insert(...)` calls
  (lines 114, 135, 151, 184, 200, 220, 251, 289) and `DirectEventDaoInsertGuardTest.kt:165-169`
  still allowlists the file as a known LEGACY_PATHS deviation. WAVE-1-STATUS.md declares
  "WAVE 1 COMPLETE" despite this. Barrier coverage is otherwise present, so this is not a live
  write-barrier hole — it is an unlanded named deliverable inside a lane recorded as complete.
- MINOR (FG-06/07-relevant): `WriteBarrierArchitectureGuardTest` EXEMPT_CLASSES grew from 4 standing
  entries to 4+16 (`owner=UNASSIGNED`, expiry 2026-10-31); `RecurringArchitectureGuardTest.kt:52-56`
  adds a new `SubscriptionManagementRepository` exemption (owner RP-04). Documented ratchets, net
  stronger enforcement — but baseline growth without recorded human approval.
- MINOR: `materialize()` no longer checks the barrier before opening the transaction (superseded by
  in-transaction `runWrite`); equivalent protection, plan letter unmet at HEAD.
- NOTE: pre-existing stack-trace log adjacent to the diff (`DataRetentionWorker.kt:142`).

### RP-03 — FAIL
Verified correct: SAF export ordering/envelope compat (P7-003), durability fsync (P7-005),
ASSETS_RESTORING re-lock fix (P7-002), CE handling with journal-honoring outer catch (P7-008),
copy→fsync→rename→DB ordering inside `RestoreInternalWriteScope` (P7-009), no Room schema change,
no test deletions, honest non-completion wording in the plan doc.
Issues:
- MAJOR-1: batch "3b" landed WITHOUT the plan's 3b content — no stable SHA-256 asset identity
  (names are `UUID.randomUUID()` / `restored_<id>_<uuid>`, `DatabaseBackupRepositoryImpl.kt:1408-1455`,
  startup fallback in `AppStartupCoordinator`), no hash/size revalidation, no
  TEMP_WRITTEN/FINAL_DURABLE/DB_UPDATED states (`RestoreJournal.kt:46` keeps PENDING/COMPLETED/FAILED),
  no extension allowlist (arbitrary bundle entry extensions flow into `files/receipts`), no duplicate
  asset-ID rejection. The e48aed43 commit message claims "copy+fsync → rename → **verify** →
  **conditional** DB update"; no verify step exists and the DB update is unconditional. No re-scope
  approval recorded.
- MAJOR-2: violates the plan's explicit "never trust `targetPath` from an old journal" contract —
  the final name is taken from the journal's `targetPath` basename
  (`DatabaseBackupRepositoryImpl.kt:1451-1455` and startup resume). Directory traversal is blocked
  (double basename + re-derived dirs), but a stale/tampered journal can force cross-asset filename
  collision (`File.renameTo` replaces an existing target).
- MAJOR-3: main-thread blocking asset resume at startup — `MainApplication.onCreate` → … →
  `runBlocking { resumePendingAssetTasks(entry) }` executes N × (copy + fsync + rename + Room write)
  on the main thread; unbounded by asset count/size (ANR risk on image-heavy restores).
- MINORs: post-swap generic-failure path deletes the extraction dir the resumable journal needs
  (`:1270-1279`); the merge itself failed the cancellation guard (5 raw `runCatching` CE swallows),
  repaired post-merge in c0f2434b — the lane never ran the static-guard suite; journal-write failures
  swallowed around task transitions; success journal loses the per-task ledger (repository path);
  `.pre_restore`/extract-dir residue on two paths. NOTE: pre-existing raw-filename logs inside the
  reworked function (`:1431,1440,1513`) not fixed by the lane.

### RP-05 — FAIL
Verified correct: P5-001 period scoping (half-open slices, calendar-safe previous-month bounds),
P5-004 shared-deposit identity (round-trip tested), P5-005 six-month single-query window
(calendar-safe, DAO half-open), P5-003 MTD-fallback removal, P5-012 aggregation side (denominator
keeps the Uncategorized bucket), no money-math regressions, no RP-06 ownership encroachment, no
weakened tests.
Issues:
- MAJOR-1: P5-012 production click path unmet — the `isUncategorized` flag has zero production
  consumers; `RetroTopCategoriesCard.kt:64` filters `categoryId == category.category.id` (empty for
  the pseudo-category) and `HomeScreen.kt:646-653` passes `categoryId = 0L` into `TransactionFilter`,
  querying category 0 as a real category (`ExpenseDao.kt:164`) → empty results when Uncategorized
  ranks first. Violates the plan's acceptance criterion and triggers its own stop condition.
- MAJOR-2: P5-003 zero-spend policy deviation — completed zero-spend months are EXCLUDED from the
  mean via `filter { it.hasPurchases }` (`ComputeDashboardWidgetsUseCase.kt:647-650`); the plan
  (and the inline comment two lines above) require zero-spend months to stay in the mean. The test
  models a "zero-spend month" as a 0.0-amount purchase row, so the deviation is untested.
  `DashboardNormalizedInput.kt:28-29` KDoc misdescribes `hasPurchases` accordingly.
- MINORs: plan-doc header still says "not implemented" after two merges (documentation rule); stale
  "TWO-month window" comment (`:386-390`); plan item P5-004-4 skipped (dead `ComputeContext.deposits`
  still unfiltered, `:294/:524`); test-inventory gaps vs the plan's required list (fallback baseline,
  null-baseline assertion, M-4 trend end-to-end, DST key test).
- NOTE: streaks now silently span six months of history (`calculateStreakData`, `:1319-1373`) —
  arguably more correct, mentioned nowhere.

### RP-06 (6a+6b) and test-sweep commits — PASS
Verified: canonical `SpendingPaceCalculator` wired with no duplicate pace math in UI/ViewModel and
every sentinel gated; P5-006 reload is a real recompute with exactly-once bump semantics and no loop;
6b made conversion suspend + typed, removed ALL raw fallbacks (grep-verified), CE rethrow before the
broad catch, bounded controlled diagnostics only; 6c confirmed NOT implemented (matches plan record);
post-merge drift on RP-06 files is RP-07 only. Test sweeps: no test functions deleted, no `@Ignore`
added by the sweeps, every assertion change checked against production truth — legitimate
stale-contract repair, not failure-masking; one previously ignored test was revived.
Issues:
- MINOR: production changes inside the `test:`-labeled commit 042d68db — `Money.kt` gains
  `splitEvenly(parts)` (CRITICAL-blast-radius money code; content reviewed and sound) and
  `CalculateFinancialForecastUseCase` changes behavior (`manualRecurringEntities = emptyList()` →
  real recurring entities). Both justified, but strict-mode signaling was bypassed by the label.
- MINOR: block-party conversion failures are log-only and not surfaced in `ForecastDataQuality`
  (`SynthesisEngine.kt:701` vs `:501-503`) — documented deferral in the plan doc, not silent.
- NOTE: P5-006's only end-to-end spec test is `@Ignore`d with documented hang evidence and an RP-21
  revival path — and this @Ignore is one of the two entries that flipped the ignored-test budget
  guard (see attribution).

### RP-07 — PASS (independent re-review)
Verified: all four fixes genuinely implemented (P5-010 nullable no-burn model with complete adapter/
UI migration and exhaustive status handling; P5-011 DST-safe keys by construction; P5-013 projected
MoM insight gated on an existing projection; P5-014 every excludeCurrent caller classified
individually); the repaired WEEK pin is a genuine fixture repair (expected value unchanged); the
known-red week-labels test is byte-untouched; encoding clean (no BOM/mojibake); no config/guard
surface touched.
Issues (all minor):
- Insight wording drift: `strings.xml:933` still says "…less than last month **so far.**" while the
  value is now a full-month projection delta ("on pace to spend X less" would be accurate).
- The DST fixtures are NOT regression-sensitive to the retired keying (the retired code missed keys
  → silent zeros, so every fixture passes under both keyings); the "proving the spill" claim in the
  test KDoc/plan/commit message is factually wrong. The production fix itself is correct and needed.
- `HomeViewModel.kt:677` can pass `excludeCurrent = true` at DAY level (currently inert; outside the
  documented matrix).
- Notes: dead `previousMonthTotal` context field; BOM removal in HomeScreen (harmless); "static
  proof" wording slightly loose.

### RP-10/10c — PASS
Verified: monotonic dedupe window immune to wall-clock jumps (`NotificationCaptureDeduper.kt:34-49`,
fail-safe pinned by test); transient payload crypto is keystore AES-256-GCM with random nonce,
content-free fixed failure message, legacy-frame fallback preserved (no migration needed); P1-005
sensitive keys filtered end-to-end; cleanup runs unconditionally on reconnect (not gated on the
capability it enforces); notification permission still gates posting only; the 2d128468 close-out is
a genuine correctness restoration (test-only inversion against unchanged production semantics), and
the Continuation pin is strictly stronger than the stale assertion it replaced; privacy scan of the
production diff is clean; 17 new behavioral tests, none weakened.
Issues:
- MINOR: the post-merge combination (merge resolution a0c4ae0d) was never re-validated as a unit
  (evidence gap, content-identical to validated sides).
- MINOR: documented legacy/new frame-ambiguity window in `NotificationTransientPayloadCrypto.kt:105-145`
  (theoretical, pinned by test, flagged for deletion after the upgrade window).
- NOTE: `windowMs * 1_000_000L` overflow unreachable with current callers; pre-existing privacy debt
  adjacent to but NOT introduced by the lane (`NotificationCaptureService.kt:620,767` `e.message` in
  extrasJson under STORE_RAW; `Timber.d` raw packageName; `NotificationIntakeWorker.kt:359` stack
  trace) — follow-up list, not charged to RP-10.

## Guard regression attribution (BASE0 → TIP)

Four guard states worsened; each is attributed below with evidence. Two improved. The rest are
byte-identical pre-existing failures (ui_dao, receipt_link, import_lifecycle, deprecation_escalations,
db_artifact_sync, raw_money_aggregates, event_writers, guard_tests, known_good_state-violation-part).

| Guard | BASE0 → TIP | Introduced by | Evidence |
|---|---|---|---|
| `time_boundaries` | PASS → VIOLATION (1 finding) | **RP-03 3a** (0835b5c7) | only in-span commit touching `BackupRestoreScreen.kt`; finding is `BackupRestoreScreen.kt:190` direct `LocalDateTime.now()` |
| `ignored_test_budget` | PASS → VIOLATION (27 → 28 entries, 24→26 files) | **RP-06 6a-resume** (b65439aa: `HomeViewModelCurrencyReloadTest.kt`, reason documented but the guard's parser cannot read a concatenated string constant → "missing reason") **+ RP-10 10b** (442411b8: `NotificationIntakeEnqueueFailureTest.kt`) | entry diff of the two suite runs |
| `known_good_state` | VIOLATION → INFRA_ERROR (exit 2) | **RP-03 3b** (e48aed43 P7-009 reorder made `restoreReceiptAssets ScannedReceiptDao.update` an `unsupported_source` GR13 subject; regen 8c11ecb3 documents it as NOT owner-acceptable, holding the gate fail-closed) | registry header + WAVE-1-STATUS item 1 |
| `db_access` | PASS (457s full scan) → INFRA_ERROR (18s early exit) | **GR-lane engine work in-span** (GR-15 v3 proof-contract model d48409c0 / GR-14e/f dispatch prototype; 20x DB_SIGNATURE_UNRESOLVED engine debt) — no RP-00..10 lane added DB surface; scripts and allowlist unchanged in span | run logs; WAVE-1-STATUS |
| `cancellation` | VIOLATION → VIOLATION, net +1 finding (NEW 0 → NEW 3; 2 further resolved) | NEW-1 `NotificationIntakeCoordinator.kt` — **RP-10 10a/10b** broad catches, but CE IS rethrown via a preceding sibling catch the Python scanner's forward-only window cannot see → **guard false-positive**; NEW-2/3 `ReceiptOcrService.kt`, `AssetCleanupCoordinator.kt` — **RP-12 12c** (0a96e99a, out-of-scope lane); resolved+2: **RP-01** (`TransactionLifecycleCoordinator`, `FinancialHealthScoreV2` G-CANCEL-02) | blame per file; sibling-catch verification at HEAD |
| `event_writers` | unchanged (14 findings at both) | — | run logs |

Improvements in span: RP-01 resolved two G-CANCEL-02 findings; RP-02 barrier ownership made two
acceptance-registry rows PROVEN (20→18, 8c11ecb3, fail-closed direction).

Suite exit moved 1 → 2 because of the two infra errors; FG-03 fail-closed behaved correctly
throughout (no run was ever green, and no result was assumed).

## FG-06/FG-07 assessment

- No `config/baselines/`, `config/db_access_allowlist.yml`, or `config/release_block_denylist.yml`
  change exists in `86efaf89..HEAD`. The only guard-config change in span is 8c11ecb3 (acceptance
  registry 20→18), which SHRINKS the accepted set — strengthening, not weakening.
- However, **in-code guard allowlists grew during the program** without recorded human approval:
  - `CancellationSafetyArchitectureGuardTest`: +10 KNOWN_VIOLATIONS +2 raw-runCatching entries
    (5 `owner=UNASSIGNED`) — RP-01.
  - `WriteBarrierArchitectureGuardTest`: +16 exemption entries (`owner=UNASSIGNED`, expiry
    2026-10-31) — RP-02; `RecurringArchitectureGuardTest`: +1 (`SubscriptionManagementRepository`,
    owner RP-04).
  These are ratchet-shaped (newly-exposed real violations with reasons/expiries; net enforcement
  stronger than before), but per AGENTS.md FG-06/FG-07 baseline growth needs explicit human
  approval, and UNASSIGNED entries have no accountable remediation lane. **Decision required.**
- The two new `@Ignore`d tests (RP-06, RP-10 10b) were NOT written into any budget — the guard now
  fails closed on them, which is the correct no-dodge behavior. Regularizing them (budget entry or
  parser fix for concatenated reason strings + revive path) also needs a decision.

## Documentation-rule violations found

1. `RP-05-dashboard-window.md:3` still says "Status: … not implemented" after two merges of its
   batches — stale (no later status section exists).
2. `WAVE-1-STATUS.md` declares "WAVE 1 COMPLETE" while RP-02's named deliverable (materializer
   direct-insert routing) did not land.
3. RP-03 3b commit message (e48aed43) overclaims ("verify → conditional DB update"; "3 repository
   tests dropped" is also misleading — no test was deleted).
4. 8c11ecb3 cites `build/guard-debug/wave1-merged-gate2.log` which is 0 bytes (the board JSON is the
   real evidence).

## Recommended remediation order (decision menu; nothing started)

1. RP-05 MAJOR-1: consume `isUncategorized` / special-case `0L` in `RetroTopCategoriesCard` +
   `HomeScreen` click path (small, UI-layer).
2. RP-05 MAJOR-2: decide the zero-spend-mean policy (plan says include) and fix code+KDoc+test.
3. RP-03: move startup asset resume off the main thread; stop trusting journal `targetPath` for
   final names; record an explicit 3b re-scope (or implement identity/hash items in 3c).
4. RP-02: route the materializer's 8 direct inserts through `writeCritical` or record the deviation
   as a plan amendment; correct WAVE-1-STATUS.
5. Small guard/code fixes: `BackupRestoreScreen.kt:190` → TimeProvider (RP-03-introduced);
   cancellation-scanner sibling-catch window (or accept the documented false-positive); the two
   verify-script crashes (`verify_receipt_link_boundaries.py:202`, import_lifecycle — `'str' object
   has no attribute 'stem'`, pre-program); assign owners to UNASSIGNED allowlist entries.
6. FG-06/FG-07 decisions for the user: approve/retro-approve the three in-code allowlist growths;
   decide the ignored-test budget regularization.

## Validation runs referenced

- `vr-20260920-090845-86756f8f` — static-guards @ 86efaf89 (BASE0): FAIL exit 1, 15/10/0.
- `vr-20260920-073837-afa2af4d` — static-guards @ c92c4709: FAIL exit 2, 12/11/2.
- `vr-20260920-072646-fc267164` — static-guards @ 80979714 (TIP): FAIL exit 2, 12/11/2.
- No Gradle build/test was executed by this review; per-lane validation claims above are quoted from
  each lane's own recorded evidence and were checked for existence where cited on disk.
