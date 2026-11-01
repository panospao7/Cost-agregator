# Feature plan: restore-semantic-verify (UF-46)

**Status: APPROVED (owner "Ok proceed", plan gate 2026-10-26), NOT STARTABLE.** Two things must both happen before work starts:
- (a) MEC+loan is merged into integration;
- (b) the owner lifts "planning only".

feature-planner wrote this plan (24 read-only calls, `Cost-agregator-int` @ e6967db1), and the orchestrator persisted it.

- **Inputs:** `docs/features/restore-semantic-verify/domain-spec.md` (revised; INV-1..17, AE-1..19), `impact-map.md`, and `decision-sheet.md` (all accepted, incl. O-2).
- **Line refs:** every line ref below was taken before the merge. S-pre re-anchors them.
- **Path prefixes:**
  - `src/` = `app/src/main/java/com/yourname/expensetracker/`
  - `test/` = `app/src/test/java/com/yourname/expensetracker/`

**Summary**
- **Risk tier:** T3.
- **Room/schema change:** none.
- **Guard/baseline/allowlist edits:** none (frozen until 2026-12-01).
- **Lane:**
  - One writer, in a fresh worktree from the post-merge integration tip, on branch `feature/restore-semantic-verify`.
  - Commit each slice after its review gates.
  - Push once the batch validation that covers it is PASS.
  - No merge or PR without an owner request.
- **Order:** S-pre → S1 → S2 → S3 → S4 → S5 → S6.
  - S2, S3 and S4 are strictly sequential because they edit the same file.
  - S5 only needs S3, but runs after S4 because there is a single lane.

## Confirmed facts (planner, e6967db1)

**F1 — `SafeEventMetadata`** (`src/domain/diagnostics/SafeEventMetadata.kt`)
- There is no key allowlist and no count limit. It delegates to `EventMetadataSanitizer`, which never throws and applies:
  - blocked exact keys, and blocked substrings (`raw`, `path`, `pin`, `token`, `amount`, …);
  - string values capped at 256 chars, with anything over 512 replaced by `[REDACTED]`;
  - value scans for paths, IBANs, 10+ digit runs and JWTs;
  - enums stored as `.name`.
- `semanticCode`, `aggregateId`, `checkpoint` and `mismatchedBuckets` are not blocked.
- `currency` passes the sanitizer but is **banned by INV-14**.
- Never put "amount" in a key. New semantic events must not pass `exception =`.

**F2 — `RestoreJournal.failJournal(entry, msg)`** persists `failureReasonCode(msg)`.
- Only codes in `FAILURE_REASON_CODES` survive (`RestoreJournal.kt:910-923`): UNKNOWN_ERROR, PARSER_FAILED, RESTORE_BLOCKED, WRITE/READ_BARRIER_DENIED, TIMEOUT, and the schema LEGACY/NEWER codes. Anything else is stored as `UNKNOWN_ERROR`.
- So the reused reasons are already persisted as `UNKNOWN_ERROR` today: `STAGED_VERIFICATION_FAILED` (`:1176`), `POST_MIGRATION_VERIFICATION_FAILED` (`:1208`) and `VERIFICATION_FAILED_ROLLED_BACK` (`:1458`).
- How the plan reads AE-8/AE-9: "journal reason X" means the literal passed to `failJournal` plus the returned result text. The persisted `error` stays exactly as it is today.
- Do NOT grow `FAILURE_REASON_CODES` (Q4, spec §5). This is recorded as spec erratum E-1.

**F3 — `verifySummaryPreservedForVerification`** (`DatabaseBackupRepositoryImpl.kt:252-309`)
- It compares field by field (the Tier-1 `allTableCounts`, or the five legacy counts), never with data-class `==`.
- So a new defaulted field on `DatabaseImportSummary` (`DatabaseBackupRepository.kt:128-141`) is safe there.
- Residual risk: tests that `assertEquals` a whole real restore result. S4 handles these.

**F4 — `LEGAL_PATHS.md:1795`** (App Lock section)
- It says app-lock settings are "device-local, never Room, never in .costbackup". That confirms spec §6; there is no conflict.
- The O-6 insert goes in the Backup/Restore section (`:489-551` pre-merge), not at this line.

**Incidental facts**
- (a) Event stages are free-form strings (`RestoreDiagnosticsSink.event`, `OperationRunRecorder.event`). No test or config pins stage names, so `SEMANTIC_CAPTURED` needs no registry edit.
- (b) `snapshotDb` is closed in the `finally` at `:776-778`. That `runCatching { snapshotDb.close() }` is one of the 13 baselined. Capture must therefore run inside that try, after `collectTableCountsStrict`.
- (c) The BackupVerifier `openDatabase` exceptions key on `method_pattern: "verify"` / `"verifyQuick"`, with no signature. Removing the param is therefore guard-neutral, and the semantic checks run inside those existing opens.
- (d) The VM already maps `data.repository.BackupSchemaUnsupportedException` with an FQCN `as?` cast (`BackupRestoreViewModel.kt:299`, `:334-336`). The new exception goes next to it and is mapped the same way.
- (e) `config/validation/test-shards.json` has no backup entries, so this plan uses targeted filters only.

## Global rules (every slice)
- **No `ExpenseDao` edit.** Reuse `SPENDING_TYPE_SQL`, `DEPOSIT_TYPE_SQL` and `EFFECTIVE_AMOUNT_SQL` by symbol, unchanged, with no added predicate. That means no `isNotMine` filter (orchestrator decision): not-mine rows contribute 0 to the sums but count in n.
- **No money engines:** no `MoneyAggregateBuilder`, `ComparableValue` or `roundSnapshot`.
- **No other changes to:** Room/schema, journal states, maintenance modes, or `DiagnosticReasonCode`.
- **No new `openDatabase` call site.** Pass open handles, or use the typed parameter on the existing `verify`/`verifyQuick`. `SemanticAggregates.kt` contains no `openDatabase` call and no write SQL.
- **No `runCatching` added to `DatabaseBackupRepositoryImpl`** (its G-CANCEL-02 baseline is 13). Use `CancellationSafe.runCatchingCancellable`, or try/catch with a CancellationException (CE) rethrow.
- **No guard, baseline or allowlist edits.** If a guard would need one, stop and return to the orchestrator.
- **Bounded text only** (INV-14).
- **Iteration limit:** at most 2 review and 2 debug iterations per slice, then mark it PARTIAL or BLOCKED.

## Slices

### S-pre — Re-anchor on the post-merge tip (scout, Sonnet; read-only)
- **Goal:** re-check every line ref in the impact map and this plan. The orchestrator records the results in `workflows/active/restore-semantic-verify-handoff.md`.
- **Checklist:**
  1. BackupVerifier after loan S2/S2b: `TABLE_TIERS`, the 151→152 constants, the orphan checks, `expectedCountsAfterMigration`, both `verify` overloads, `verifyInternal` step 5, `:612-666`, the TODO at `:20-34`, and the catch at `:656`.
  2. The capture region: `sanitizeExport`; **whether loan S6c's reconcile has landed, and where**; the snapshotDb try/finally; `:781`; `:788`; `CostBackupSnapshot`.
  3. Restore: `validateManifestCompleteness`, the staged `verifyQuick`, `postMigrationCheck`, `:1352-1355`, the catch/rollback at `:1427-1466`, the success summary at `:1408`, `importDatabase` at `:1915`, the mapper at `:3072`, and `BackupSchemaUnsupportedException` at `:3170`.
  4. **The v151→152 migration body:** does it UPDATE, INSERT, DELETE or rebuild `expenses` or `manual_recurring_expenses`? ADD COLUMN counts as identity.
  5. The three ExpenseDao constants: are they still public, unaliased and named the same after MC?
  6. The current `runCatching` count in the repository vs the baseline of 13, and whether BackupVerifier is still absent from `cancellation.json`.
  7. `FAILURE_REASON_CODES` and the BackupVerifier guard entries are unchanged.
  8. Any existing real-schema JVM test helper (e.g. in RoundtripGolden or IntegrityE2E) that can be reused as fixture F.
  9. How `CostbackupBundle.extract` surfaces a manifest parse failure.
  10. Whether the VM create-failure path (`:147-160`) displays `exception.message`.
- **Write set:** none.
- **Gate:** none. The orchestrator reviews the scout output.
- **Done when:** all refs are re-anchored and items 2, 4, 6, 9 and 10 are answered. Any delta that changes a write set is amended into this plan before S1.

### S1 — Verifier core (unwired, behaviour-neutral)

**Invariants:** INV-1, 2, 3, 4, 9, 10, 12 (section parser), 13, 14 (issue text), 16, plus the outcome types for INV-11 and INV-15.

**Acceptance examples:**
- AE-1 (static, hostile keys)
- AE-2, AE-3, AE-4
- AE-6 (the parser rejects a numeric `s`)
- AE-10 (chain test)
- AE-11 (compare level)
- AE-13 (parse level)
- AE-14 (QUERY_FAILED + CE)
- AE-15 (no value text in issues)
- AE-17
- AE-18 (257 buckets at function level, timing recorded)

**Source write set**
- `src/data/backup/BackupVerifier.kt`:
  - **Delete:** the TODO, `SEMANTIC_AGGREGATE_QUERIES`, `verifySemanticAggregates`, the `manifestSemanticAggregates` param (on both `verify` overloads and on `verifyInternal`), and step 5.
  - **Add** `expectedAggregatesAfterMigration(set, sourceDbVersion)` beside `expectedCountsAfterMigration`. It is identity for v145..v151, plus the v151→152 declaration from S-pre item 4.
  - **Add** an optional typed `semanticExpectation: SemanticExpectation? = null` to `verify`/`verifyQuick`. It runs inside the existing read-only open, after the existing checks, and its result goes in a new defaulted `VerificationResult` field.
- New `src/data/backup/SemanticAggregates.kt`:
  - The four-ID registry, built from the ExpenseDao constants by symbol.
  - Types: `AggregateBucket`, `AggregateEntry`, `SemanticAggregateSet`, `SemanticCode`, `SemanticCheckpoint`.
  - `capture(db, tableCounts)` with the conservation check and the 256-bucket cap.
  - `compare`, `toJson`, and `parse` → `Parsed | Malformed(code)`:
    - limits 256 / 64 / 2048;
    - `s` is a JSON string, read via `BigDecimal(double)`;
    - equality uses `compareTo`.
  - `definitionHash`.
  - CE is always rethrown.
- New `src/domain/backup/SemanticVerificationOutcome.kt`:
  - `Verified | NotVerifiable(reason)`, with a domain-local bounded reason enum: LEGACY, VERSION, NOT_CAPTURED, DEFINITION_CHANGED, NO_MANIFEST.
  - The domain layer never imports the data-layer `SemanticCode`.

**Test write set**
- `test/data/backup/BackupVerifierManifestTest.kt`: **replace `:144-204` only.**
  - This is O-2, the owner-approved replacement; it is not a deletion.
  - The replacement uses real-schema tests for AE-1, AE-2, AE-4 and AE-15 (revised spec numbering).
  - Leave `:26-142` untouched.
- New in `test/data/backup/`: `SemanticAggregatesTest.kt`, `SemanticAggregatesParseTest.kt`, `SemanticAggregatesStaticTest.kt`, `SemanticAggregatesMigrationChainTest.kt`.
  - The chain test enumerates the *registered* migration list, not a hard-coded one.
  - It covers start versions 145..current.
  - It fails on any undeclared write to, or rebuild of, a fingerprinted table.
- New `test/data/backup/RealSchemaFixture.kt` (fixture F at the current schema), unless S-pre item 8 finds a reusable helper.

**Gate data**
- **Legal path preserved:** Backup/Restore (`LEGAL_PATHS.md:489-551` pre-merge). The verifier stays read-only; no new path.
- **FG:** FG-10, FG-13, FG-16, FG-17, FG-19, FG-20, FG-21, FG-23.
- **Gates:** architecture-guardian, privacy-security-guardian, and room-migration-guardian (advisory: INV-9 and the v151→152 declaration).
- **Batch:** B1. **Depends on:** S-pre.
- **Done when:**
  - the SQL-keyed API no longer exists (static test);
  - the listed AEs pass in B1;
  - migration-tests is PASS;
  - production callers are unchanged (they still pass two args, so the semantic pass is null);
  - there is no guard-file diff.

### S2 — Capture, manifest field, C0 (backup side only)

**Invariants:** INV-5, INV-6, INV-12 (`fromJson` wiring; never throws), INV-14 (`:788`), INV-16.

**Acceptance examples:**
- AE-5 (backup half)
- AE-6 (backup level)
- AE-7
- AE-13 (`fromJson` never throws and returns the Malformed marker)
- AE-15 (`:788` text is exactly `SNAPSHOT_VERIFICATION_FAILED`)
- AE-18 (257 currencies at backup level)

**Source write set**
- `src/data/backup/CostbackupBundle.kt`:
  - Add `BackupManifest.semanticAggregates: SemanticAggregateSet? = null`, plus the malformed marker.
  - `toJson` writes it, and `fromJson` delegates to the S1 parser.
  - Thread the field through both `create` overloads, `writeToStream` and `buildZip`.
  - No change to `backupFormatVersion` or `FORMAT_VERSION`, and nothing goes in the plaintext header.
- `src/data/repository/DatabaseBackupRepositoryImpl.kt`, **backup region only** (`runCostBackupExport`, `CostBackupSnapshot`):
  - Capture inside the snapshotDb try, after `collectTableCountsStrict`, sanitize, and the S6c reconcile. It gets its own try/catch: CE is rethrown, anything else degrades.
  - C0 runs before `:781`.
  - Emit one `SEMANTIC_CAPTURED` event (SKIPPED, VALIDATION_FAILED) carrying `semanticCode` only.
  - `:788` becomes `Exception("SNAPSHOT_VERIFICATION_FAILED")`.
  - The existing `runCatching { snapshotDb.close() }` stays untouched.
  - If S6c has not landed: place capture after the last `tempDb` write, and leave a marked ordering obligation for loan (O-5).

**Test write set**
- `test/data/repository/DatabaseBackupRepositoryImplTest.kt`:
  - Upgrade `createFullSchemaDatabase` to the real schema via `RealSchemaFixture`.
  - Keep a renamed id-only helper only for a test that provably needs that shape, without weakening any assertion.
  - Assert that the upgraded fixture yields CAPTURED.
  - Change assertions on the old joined `:788` text to expect the bounded code (approved under Q3).
  - Add the capture tests.
- `test/data/backup/CostbackupBundleTest.kt`: round-trip with and without the section; unknown keys are ignored; a malformed section gives the marker and does not throw.

**Gate data**
- **Legal path preserved:** backup creation via `runCostBackupExport`. Only the CAPTURE and C0 steps are added (spec §3).
- **FG:** FG-07, FG-10, FG-13, FG-16, FG-17, FG-20, FG-23.
- **Gates:** architecture-guardian, privacy-security-guardian, reviewer-strict.
- **Batch:** B2. **Depends on:** S1.
- **Done when:**
  - a backup of F writes a CAPTURED section that round-trips;
  - every forced capture or C0 failure still yields a successful backup, with NOT_CAPTURED (or a null section) and exactly one bounded event;
  - restore code is untouched.

### S3 — Restore pre-destructive: malformed block, C1, C2, typed exception, summary field

**Invariants:** INV-7, INV-11 (classification), INV-12 (block), INV-13, INV-14 (new exception text).

**Acceptance examples:**
- AE-1 (restore blocked)
- AE-8 (C1 and C2; the live DB is byte-identical; no safety backup or swap)
- AE-10 (C2 identity, v149 source)
- AE-13 (blocked before the swap with `SEMANTIC_MANIFEST_MALFORMED`; `version=99` → NOT_VERIFIABLE)
- AE-14

**Source write set**
- `DatabaseBackupRepositoryImpl.kt`, **pre-swap region only**:
  - The malformed check goes next to `validateManifestCompleteness` and reuses its failure handling and reason.
  - C1 calls `verifyQuick(staged, expectation)`.
  - C2 runs in `postMigrationCheck` and calls `verify(…, expectedAggregatesAfterMigration(…))`.
  - On failure:
    - use the existing `exitAfterPreDestructiveAbort` plus `failJournal`, with the reused literal (F2);
    - return `BackupSemanticVerificationException(code)`;
    - event metadata contains only `semanticCode`, `aggregateId`, `checkpoint` and `mismatchedBuckets`.
  - The exception class goes beside `BackupSchemaUnsupportedException`, with `message == code`.
  - Add a mapper entry at `:3072`.
- `src/domain/backup/DatabaseBackupRepository.kt`: add `DatabaseImportSummary.semanticVerification`, with a fail-closed default of `NotVerifiable(NO_MANIFEST)` (never Verified).

**Test write set**
- `DatabaseBackupRepositoryImplTest.kt`:
  - keep the existing `:1515` assertion;
  - assert the literal passed to `failJournal` and that the persisted `error` is unchanged (F2);
  - add a test that the default is never Verified.
- New `test/data/repository/RestoreSemanticPreSwapTest.kt`.

**Gate data**
- **Legal path preserved:** the pre-destructive abort. The live DB is never touched, and there is no override (O-4).
- **FG:** FG-07, FG-10, FG-13, FG-16, FG-20, FG-23.
- **Gates:** architecture-guardian, privacy-security-guardian, reviewer-strict.
- **Batch:** B2. **Depends on:** S2.
- **Done when:**
  - every C1, C2, malformed or query failure aborts before the safety backup, with bounded text only;
  - CE propagates at C2;
  - LEGACY, VERSION and NOT_CAPTURED proceed under all existing gates.

### S4 — C3 via the existing rollback; outcome; `:1354`

**Invariants:** INV-8, INV-11, INV-14 (`:1354`), INV-15.

**Acceptance examples:**
- AE-5 (restore half)
- AE-9
- AE-10 (C3 VERIFIED)
- AE-11
- AE-12 (LEGACY, NO_MANIFEST; `DebugViewModel` unchanged)
- AE-15 (`:1354` text is exactly `LIVE_VERIFICATION_FAILED`)
- AE-16

**Source write set:** `DatabaseBackupRepositoryImpl.kt`, **only `:1352-1466`, the success summary at `:1408`, and `importDatabase` at `:1915`:**
- C3 calls `verify(live, counts, expectation)`. A semantic failure throws `BackupSemanticVerificationException` into the existing catch.
- The rollback code is unchanged, and `isRestoreDurabilityFailure` is untouched.
- `:1354` becomes `Exception("LIVE_VERIFICATION_FAILED")`.
- Add an internal companion `combineSemanticOutcome(c1, c2, c3)` that implements INV-15.
- The success summary is populated, and `importDatabase` returns NO_MANIFEST.

**Test write set**
- `DatabaseBackupRepositoryImplTest.kt`: update the whole-summary expectations here.
- New `test/data/repository/RestoreSemanticPostSwapTest.kt`.
- If whole-summary equality on a real restore result exists outside this write set, stop; the orchestrator amends the plan.

**Gate data**
- **Legal path preserved:** the existing rollback (`restoreFromSafetyBackup`). CRITICAL_RECOVERY_REQUIRED is unchanged.
- **FG:** FG-07, FG-10, FG-13, FG-16, FG-20, FG-23.
- **Gates:** architecture-guardian, privacy-security-guardian, reviewer-strict.
- **Batch:** B3. **Depends on:** S3.
- **Done when:**
  - an F round-trip gives `SuccessNeedsRestart(VERIFIED)`;
  - a C3 mismatch rolls back with the existing reason;
  - a rollback failure gives CRITICAL_RECOVERY_REQUIRED;
  - the `DebugViewModel` diff is empty.

### S5 — UI and strings

**Invariants:** INV-17.

**Acceptance examples:**
- AE-19
- AE-8 (VM half: the "blocked" string, with no override action)
- AE-5 (the notice is shown)

**Source write set**
- `src/ui/screens/backup/BackupRestoreViewModel.kt`:
  - Map `BackupSemanticVerificationException(SEMANTIC_MISMATCH)` with an FQCN `as?` cast, placed before the `"password"` match (`:309`).
  - Add the NOT_VERIFIABLE notice to the UI state from the success branches (`:264-286`).
  - MALFORMED and QUERY_FAILED keep the generic mapping.
  - The create-failure path keeps the existing string for `SNAPSHOT_VERIFICATION_FAILED` (see S-pre item 10).
- The backup screen composable / UI-state file in `src/ui/screens/backup/`: a non-blocking info notice.
- `app/src/main/res/values/strings.xml`, `values-el/strings.xml`, `values-es/strings.xml`: **exactly two keys**, placed next to `backup_schema_too_old`. The implementer drafts the el/es copy.

**Test write set:** `test/ui/screens/backup/BackupRestoreViewModelTest.kt`. `BackupRestoreViewModelPrivacyDenialTest` is kept as is.

**Gate data**
- **Legal path preserved:** the UI only consumes repository results; there is no new UI→DAO path.
- **FG:** FG-12, FG-16, FG-20.
- **Gates:** architecture-guardian, privacy-security-guardian, and the **owner el/es sign-off (required before merge)**.
- **Batch:** B3. **Depends on:** S3 (types and summary field); runs after S4 in the lane.
- **Done when:** AE-19 passes, lint is clean, and the diff shows exactly two new keys × three locales.

### S6 — Docs (orchestrator, under the doc lock)
- **Goal:** add the approved O-6 text (spec §8) under Backup/Restore RESTORE and FORBIDDEN in `docs/architecture/LEGAL_PATHS.md`, re-anchored first. Add a `SemanticAggregates.kt` entry to `docs/architecture/CODEBASE_SEGMENTS.md` only if the data/backup segment lists files.
- **Test write set:** none.
- **Legal path:** documents the unchanged path.
- **FG:** FG-23 (docs touch no guard config).
- **Gates:** architecture-guardian, privacy-security-guardian.
- **Batch:** covered by the candidate `static-guards` run. **Depends on:** S4 and S5.
- **Done when:** the text matches the merged behaviour and the doc lock is released.

## Validation

Run by validation-runner only, serially. RUNNING is not a failure, and a missing or stale result is not a PASS.

**B1 (S1)**
1. `compile`
2. `targeted-unit-test` with:
   - `*BackupVerifier*`
   - `*SemanticAggregate*`
   - `*BackupRestoreArchitectureGuardTest*`
   - `*DbGuardPolicyFixtureTest*`
3. `migration-tests` (its only run)

**B2 (S2+S3)**
1. `compile`
2. `targeted-unit-test` with B1's filters plus:
   - `*CostbackupBundle*`
   - `*DatabaseBackupRepositoryImplTest*`
   - `*RestoreSemantic*`
   - `*BackupRestoreContract*`
   - `*BackupRestoreRoundtripGolden*`
   - `*BackupRestoreIntegrityE2E*`
   - `*BackupRestoreMoneyIntegrityScenario*`
   - `*RestoreCancelAfterSwapRecovery*`
   - `*RestoreMaintenanceMode*` (added because it constructs `DatabaseImportSummary`)
   - `*RestoreAbortWorker*` (added for the same reason)

**B3 (S4+S5)**
1. `compile`
2. `targeted-unit-test` with B2's filters plus:
   - `*BackupRestoreViewModel*`
   - `*DebugViewModel*`
3. `lint`
4. `registered-guard` for `cancellation`, `pii_logging`, `db_access` and `db_artifact_sync`

**Candidate (after S6)**
1. `static-guards`, once.
2. A fresh-session final acceptance (Opus). The reviewer must differ from every slice reviewer.

No `app-check` unless a gate demands it. The ExpenseDao parity filters are not needed, because ExpenseDao is not edited.

## Status
| Step | Status | Gate |
|---|---|---|
| S-pre re-anchor | NOT STARTABLE (hold, awaiting MEC+loan merge) | orchestrator review |
| S1 verifier core | NOT STARTABLE | arch, privacy, room-migration (advisory) · B1 |
| S2 capture + C0 | NOT STARTABLE | arch, privacy, reviewer-strict · B2 |
| S3 C1/C2 + malformed | NOT STARTABLE | arch, privacy, reviewer-strict · B2 |
| S4 C3 + outcome | NOT STARTABLE | arch, privacy, reviewer-strict · B3 |
| S5 UI + strings | NOT STARTABLE | arch, privacy, el/es sign-off · B3 |
| S6 LEGAL_PATHS docs | NOT STARTABLE | arch, privacy · candidate |
| Candidate static-guards | PENDING | validation-runner |
| Final acceptance (fresh session) | PENDING | — |
| Merge | owner request only | — |

## Open risks
1. **Stale base, re-anchor needed.** All refs were taken at e6967db1, and loan changes `BackupVerifier` (+40/−7) and the capture region (S6c).
   - S-pre is mandatory, and any write-set delta is amended into this plan before S1.
   - If S6c has not landed, S2 leaves an ordering obligation: whichever lands second enforces reconcile-before-capture (AE-7).
2. **Fixture blast radius.** `createFullSchemaDatabase` is widely shared, and real NOT NULL or FK columns can break id-only inserts.
   - Under the revised INV-5, a minimal fixture no longer fails the backup. It silently degrades to NOT_CAPTURED, so S2 asserts CAPTURED.
   - Stop and report if more than about 5 existing tests need edits, or if any edit would weaken an assertion.
3. **Maintenance-window duration.** Capture plus three full scans (C1–C3) run under maintenance, and there is no timeout in v1. AE-18 records timing only; a timeout is a follow-up if the numbers warrant one.
4. **The old joined-message leak, now closed by INV-14.** Changing the `:788` and `:1354` text can break code that reads `.message`, and tests pinned to the old text. S2 and S4 update those expectations, and S5 keeps the create-failure UI on an existing string.
5. **The loan INV-9 declaration.** This feature owns the v151→152 declaration (S-pre item 4 → S1).
   - If loan's migration changes again before merge, re-run S-pre item 4.
   - Any later migration that writes a fingerprinted table fails the chain test until it declares a transform. Cross-note this in `loan-installments-plan.md`.
6. **Journal-reason normalization (F2, erratum E-1).** AE-8 and AE-9 are pinned to current behaviour (a persisted `UNKNOWN_ERROR`), and the registry does not grow.
7. **`db_artifact_sync` / source-hash drift** from the repository edit. If passing would require regenerating a frozen guard artifact, stop and escalate; do not regenerate it.

## Human decisions
1. **Plan gate:** approve this plan.
2. **Hold / start:** lift "planning only" once the MEC+loan merge has landed on integration.
3. **el/es sign-off** for the two S5 strings (the implementer drafts them), before merge.

## Deferred
- **v2 aggregates**, each needing a `version` bump: `EXPENSE_BASE_SNAPSHOT_BY_BASE_CURRENCY`, the savings ledger, receipt totals, and loan aggregates. Not fingerprinting `base*`/`exchangeRateUsed` is an accepted v1 gap.
- A capture/verify timeout, after the AE-18 measurements.
- A semantic outcome in `DebugViewModel.importDatabase` (Q9: no).
- Rescue-restore (`FinancialRescueCoordinator`) coverage.

## Re-anchor revision (2026-11-02; feature-planner, Sonnet; persisted by the orchestrator)
**Status: APPROVED plan and revision. D-SIS (a) and D-P071 (fold S0b) were confirmed by the owner on 2026-11-02 (owner-approvals 101).**

### 1. Base and lane
- Worktree `../Cost-agregator-rsv`, branch `feature/restore-semantic-verify`, from origin/feature/ai-evolution (a5d659de or later). Copy `local.properties`. One writer.
- **O-5 is moot.** The loan S6c pre-reconcile (`DatabaseBackupRepositoryImpl.kt:684-705`) runs before the snapshot stage. Capture goes after `collectTableCountsStrict` (:806), inside the snapshotDb try. Delete the S2 "ordering obligation" fallback.
- **Schema v152.** MIGRATION_151_152 (`DatabaseMigrations.kt:191-271`) only creates the installment tables plus `ALTER TABLE expenses ADD COLUMN spendingExclusionReason` (:269), so it is identity for the fingerprinted columns.
- **S-pre:** items 2, 4 and 6 are answered; the G-CANCEL-02 baseline is still 13. The remaining S-pre items (9 CostbackupBundle anchors, 10 VM create-failure path, 8 real-schema fixture reuse) become the first step of the S1/S2 writers.

### 2. Anchors, old to new ((v) = verified by the planner)
- **DatabaseBackupRepositoryImpl**
  - snapshot counts + finally close: :805-815 (v)
  - snapshot verify: :818 (v); joined text: :825 (v)
  - staged verifyQuick: :1206 (v); postMigrationCheck: :1235 (v)
  - live verify and joined text: :1389-1391 (v); summary: :1396 (v)
  - SuccessNeedsRestart: :1444; rollback: :1495; importDatabase: :1952; toImportSummary: :2461
  - BackupSchemaUnsupportedException: :3207 (v)
- **BackupVerifier**
  - TODO: :26
  - TABLE_TIERS: :50-128 (v)
  - expectedCountsAfterMigration: :249 (v)
  - verify: :314/:334; verifyInternal: :342 (v); step 5: :465 (v); verifyQuick: :494 (v)
  - SEMANTIC_AGGREGATE_QUERIES: :652 (v)
- **Other**
  - VM mapper: `BackupRestoreViewModel.kt:299/309/334`
  - `RestoreJournal.kt:910-923`
  - `DatabaseBackupRepository.kt:128`
  - LEGAL_PATHS: BACKUP :654, RESTORE :661, App Lock :2467
  - fixture `createFullSchemaDatabase`: `DatabaseBackupRepositoryImplTest.kt:3004-3017` (v)

### 3. D-SIS: loan `SpendingInclusionSql`
- **Facts:**
  - `SPENDING_PREDICATE` = `isNotMine = 0 AND spendingExclusionReason IS NULL`.
  - `EFFECTIVE_AMOUNT` = `CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 ELSE (ExpenseDao.EFFECTIVE_AMOUNT_SQL) END`.
  - Every ExpenseDao spending aggregate now applies the predicate.
- **Options:**
  - (a) SPENDING uses `SpendingInclusionSql.EFFECTIVE_AMOUNT`.
  - (b) Keep the ExpenseDao constants. This misses lost or flipped flags (double-counted installment spend) and disagrees with what the app shows.
  - (c) Fingerprint both. This adds a 5th ID, an extra scan, and only marginal value.
- **Recommendation: (a).**
  - INCOME is unchanged. This amends erratum E-2 to E-3: "SPENDING uses `SpendingInclusionSql.EFFECTIVE_AMOUNT`; still no `isNotMine` filter and no ExpenseDao edit". No guard or allowlist edit.
  - Consequence: the aggregate needs v152 or later. A manifest with `sourceDbVersion < 152` and a section is NotVerifiable(VERSION). AE-10's v149 case becomes a synthetic NOT_VERIFIABLE(VERSION) test. The chain test covers 152..current; 145..151 get a static "no undeclared write to a fingerprinted table" check.
  - Slices affected:
    - S1: registry, definitionHash, `expectedAggregatesAfterMigration` gated at 152, chain range, AE-2/3/10, a flagged-PURCHASE fixture (n+1, s unchanged), and a "dropping the flag changes s" test.
    - S2: the fixture comes from the real 152.json.
    - S3/S4: the VERSION case only.
    - S6: the docs mention the flagged-row rule.

### 4. D-P071: fold review P-07-1 as a new S0b, before S1
- **Recommendation: fold.** It is in the same file, `BackupVerifier.kt`.
- **Goal:** classify every AppDatabase entity in TABLE_TIERS. Recount against 152.json; the review's 61/73 is likely now 63/75.
- **Source:** `BackupVerifier.kt`:
  - add the missing tables, gated by introduction version, using the `requiredManifestTables`/`expectedCountsAfterMigration` pattern (:203, :249);
  - fix the stale "61" comments.
- **Tests:** new `BackupVerifierTableCoverageTest` (152.json table names == `allTableNames()` exactly, no exclusion list); extend `BackupVerifierTest`.
- **Tier policy:** version-gated TIER_1_EXACT for user data, TIER_3 for caches and telemetry. The S0b writer proposes the table list.
- **Risk:** an ungated Tier-1 table breaks old-backup `validateManifestCompleteness`.
- **Gates:** room-migration-guardian and architecture-guardian.
- **Not folded:**
  - P-07-3: destructive restore-swap durability; needs its own plan.
  - J-20-1: new destructive UI and legal paths; owner-gated.

### 5. P-07-2 is fixed by this plan
S1 deletes the inert `SEMANTIC_AGGREGATE_QUERIES`, S2 records at export, and S3/S4 wire C1-C3.

### 6. fix-1.0.4 overlap
- Disjoint: ExportAnonymizer sanitizers are compatible (capture happens after sanitize).
- LEGAL_PATHS and db_ownership edits are in different regions; rebase LEGAL_PATHS at S6.
- Can start before fix104 merges.

### 7. Validation
- B1 = S0b + S1: the original filters plus `*DatabaseBackupRepositoryImplTest*`, `*BackupRestoreContract*` and `*BackupRestoreRoundtripGolden*`. No migration-tests unless room-migration-guardian asks.
- B2 and B3 are unchanged.
- Candidate: static-guards once, then fresh-session final acceptance.
- Order: S0b, S1, S2, S3, S4, S5, S6.

### 8. Owner decisions
1. D-SIS (a).
2. Fold S0b, with the tier policy above.
3. el/es sign-off on the S5 strings (unchanged).
