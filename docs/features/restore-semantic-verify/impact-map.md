# Impact map: restore-semantic-verify (UF-46), 2026-10-26

**Sources**
- Written by impact-mapper (read-only, about 59 tool calls, no shell) and persisted by the orchestrator.
- Code was read at `Cost-agregator-int` (`integration/ai-evolution-1a` @ e6967db1).
- Spec: `domain-spec.md` in this folder.
- Paths are relative to `app/src/main/java/com/yourname/expensetracker/` unless prefixed otherwise.

**Git facts checked by the orchestrator**
- Loan S2b (`a63ec1d7`), plus S3, S4a-d, S5, S6a and S6b, are committed on `feature/manual-entry-currency` (tip 34b20715).
- That branch is **not** in e6967db1.
- It already changes `data/backup/BackupVerifier.kt` (+40/−7) relative to e6967db1.
- MEC+loan is the "next merge" into integration (lanes-board :104).

## Verdict
- **Tier: T3.** The change touches:
  - the restore lifecycle (staging, swap, rollback);
  - the backup manifest format;
  - privacy (exception text and diagnostics);
  - cancellation;
  - a cross-segment contract across `data/backup`, `data/repository`, `domain/backup`, `ui/screens/backup` and `ExpenseDao` constants.
- **Room/schema change: none.** No entity, DAO, version bump, migration or schema JSON changes. `backupFormatVersion` and the header `FORMAT_VERSION` (`CostbackupBundle.kt:40-45`) are unchanged, because the manifest field is optional and `fromJson` ignores unknown keys (`:81-98`).
- **Two spec claims do not hold as written:**
  1. Aggregate 4 (the base-snapshot validity predicate) has no standalone `ExpenseDao` constant. See Q1.
  2. The shared test fixture will break once capture is wired. See Tests.

## Reachability today
**The manifest-SQL execution at `BackupVerifier.kt:642-644` is not reachable in production.**
- Both production `verify()` callers (`DatabaseBackupRepositoryImpl:781`, `:1352`) pass two arguments.
- `BackupManifest` has no field that feeds the map.
- `verifySemanticAggregates` is called only from `verifyInternal:450` and from tests (`BackupVerifierManifestTest:171,196`).

So there is no live security issue. Deleting the SQL-keyed API is still **mandatory**, together with a static test that no manifest string reaches `rawQuery` (AE-1).

## Segments and legal paths
**Segments:**
- `data/backup`: `BackupVerifier`, `CostbackupBundle`, `RestoreJournal`.
- `data/repository/DatabaseBackupRepositoryImpl`.
- `domain/backup`: `DatabaseImportSummary` at `DatabaseBackupRepository.kt:128`.
- `domain/diagnostics`: `DiagnosticReasonCode`, which already has `VALIDATION_FAILED`, and `SafeEventMetadata`.
- `ui/screens/backup`: the spec omits this one.
- `ExpenseDao` constants, read-only.
- `res/values{,-es,-el}`.

**Legal paths:**
- All work stays on the existing Backup/Restore legal path (`LEGAL_PATHS.md:489-551`):
  - capture and C0 happen in creation;
  - C1 and C2 sit at the pre-destructive abort (`exitAfterPreDestructiveAbort`, `failJournal`);
  - C3 uses the existing rollback (`restoreFromSafetyBackup`).
- `importDatabase` (a raw `.db` file) returns NOT_VERIFIABLE_NO_MANIFEST.
- Only `OPEN_READONLY` handles are used, the Room singleton is never read, and there are no expense writes.
- O-6 adds `LEGAL_PATHS.md` text under RESTORE/FORBIDDEN (`:542-551`).
- Money engines (`MoneyAggregateBuilder`, `ComparableValue`, `roundSnapshot`) must not be used (INV-3).

**Consumers:**
- `BackupVerifier.verify`/`verifyQuick` at repository `:781`, `:1169`, `:1198`, `:1352`.
- `BackupManifest` creation, `writeToStream` and `buildZip`; the repository at `:570`/`:877`.
- `DatabaseImportSummary` at `:1359`, `:1408` and in `importDatabase`. Also `DebugViewModel:408`. Check equality use in `verifySummaryPreservedForVerification` before adding a field.
- `BackupRestoreViewModel:260-320`.

## Write set (lines at e6967db1; the loan merge will move them)
1. **`data/backup/BackupVerifier.kt`**
   - Delete `SEMANTIC_AGGREGATE_QUERIES` (`:612-625`) and `verifySemanticAggregates` (`:627-666`).
   - Remove the `manifestSemanticAggregates` param from both `verify` overloads (`:298-324`) and from `verifyInternal` (`:326-330`). Remove step 5 (`:448-454`) and the TODO block (`:20-34`).
   - Add `expectedAggregatesAfterMigration` next to `expectedCountsAfterMigration` (`:238-255`).
   - Put new logic in a sibling file such as **`data/backup/SemanticAggregates.kt`**, to keep `BackupVerifier.kt` small, since loan also edits it.
   - New functions take an already open `SQLiteDatabase` (like `collectTableCountsStrict` `:271`). This avoids a new `openDatabase` guard exception.
2. **`data/backup/CostbackupBundle.kt`**
   - `BackupManifest` (`:49-64`) and `toJson` (`:65-74`) gain `semanticAggregates: SemanticAggregateSet? = null`.
   - Thread it through the `create` overloads (`:284`, `:329`), `writeToStream` (`:364-384`) and `buildZip` (`:577-607`).
   - The `db_ownership_policy.yml:982-992` FQCN pin on `restoreReceiptAssets` is unaffected.
3. **`data/repository/DatabaseBackupRepositoryImpl.kt`**
   - **Capture:** inside the read-only `snapshotDb` try (`:765-778`), after `sanitizeExport` (`:756-759`) and **after loan S6c's reconcile**, which also lands before snapshot verify (lanes-board 2026-10-24).
   - **C0:** serialize, parse back and compare before `:781`. `CostBackupSnapshot` (`:600`, `:799`) gains the set.
   - **C1:** after the staged `verifyQuick` (`:1167-1183`).
   - **C2:** in `postMigrationCheck` (`:1197-1215`).
   - **Malformed-section check:** next to `validateManifestCompleteness` (`:1141-1156`).
   - **C3:** at `:1352-1355`, entering the existing catch at `:1427` and rollback at `:1433-1466`. The rollback code is unchanged.
   - **Outcome:** in the success summaries (`:1408`) and in `importDatabase` (`:1915`).
   - **Exception:** a new typed `BackupSemanticVerificationException(code)`, modeled on `:3170`, plus a mapper entry at `:3072`.
4. **`ExpenseDao`**
   - `EFFECTIVE_AMOUNT_SQL` (`:77`), `SPENDING_TYPE_SQL` (`:51`) and `DEPOSIT_TYPE_SQL` (`:57`) are public, unaliased consts. They can be reused directly with **no edit**.
   - The aggregate-4 predicate exists only inside `COMPARABLE_HOME_VALUE_E_SQL` branch 3 (`:112-119`, aliased, bound to `baseCurrency = ?`). See Q1.
   - Unverified: whether the canonical income query (`:1600`) has `isNotMine = 0`.
5. **UI:** `ui/screens/backup/BackupRestoreViewModel.kt` and the screen.
   - Success branches (`:264-286`): a NOT_VERIFIABLE notice in a new UI-state field.
   - Failure fold (`:293-320`): a type-based mapping that runs **before** the `"password"` substring match at `:309`.
   - Create-failure (`:147-160`): bounded mapping.
6. **Strings:** EN+EL+ES, anchored next to `backup_schema_too_old` (values:2700, es:20, el:29).

## Privacy
- **The raw-value route:** `BackupVerifier:652` builds `expected=…/actual=…`. `:452` joins it into `errors`, which ends up in `Exception(...)` text at repository `:788` and `:1354`.
- **Where it shows today:**
  - The VM logs only the class (`:148`, `:1433`).
  - User-facing errors are bounded (e.g. `VERIFICATION_FAILED_ROLLED_BACK` `:1466`).
  - Exposure therefore comes through any other `.message` reader and the test pin at `BackupVerifierManifestTest:200`.
- **The same route already exists:** `BackupVerifier:340/347/359/381/399-403` (integrity text, table names, counts) flows into the same joined strings. See Q3.
- **Registries:** `DiagnosticReasonCode` has `VALIDATION_FAILED`, so the enum is unchanged. The `SafeEventMetadata` allowed keys for `semanticCode`/`aggregateId`/`checkpoint`/`mismatchedBuckets` are unverified. Whether `RestoreJournal` reason strings are a bounded set is unverified (`RestoreJournal.kt:918-919` bounds the schema codes). See Q4.

## Cancellation
- The swallowing `catch (e: Exception)` is at `BackupVerifier.kt:656`.
- `BackupVerifier.kt` is **not** in `config/baselines/cancellation.json`, so rewriting it needs no baseline edit.
- `DatabaseBackupRepositoryImpl.kt` has a G-CANCEL-02 baseline of **13** (`cancellation.json:28,67`). New code must not add `runCatching`. Use `CancellationSafe.runCatchingCancellable` (`:1197`) or a try/catch that rethrows CE (`:1172`).

## Guards (FG-IDs only; frozen until 2026-12-01, so design to need **no** guard edits)
**FG-IDs in scope:** FG-10, FG-12, FG-13, FG-16, FG-17, FG-20, FG-23. Also FG-06/07 if any entry is touched, FG-19 for the test replacement, and FG-21 if migration tests run.

**Guard files that reference these sources:**
- `config/guards/db_structural_exceptions.yml:552-566` and `db_structural_exceptions_expected_methods.yml:541-553`. `BackupVerifier` has `openDatabase` exceptions only for `verify`/`verifyQuick`. Pass open handles. Whether the pattern match is exact, prefix or regex is unverified.
- The same files at `:177-323` have about 16 entries for the repository. Reusing `snapshotDb` adds no new entry.
- `config/db_access_allowlist.yml:169,579`, `baselines/db_access.json`, `event_writers.json`, `db_ownership_policy*.yml` and the source-hash accounting all reference the repository. Run `db_access` and `db_artifact_sync`.
- `db_raw_query_classification.yml` has no BackupVerifier entry.

## Tests
**Replace (O-2):** `app/src/test/.../data/backup/BackupVerifierManifestTest.kt:144-204`. It pins the SQL-keyed API, a fake `effectiveAmount` column, `'EXPENSE'`/`'INCOME'`, and `expected=999.0` (`:200`). Replace it with AE-1/2/4/14 tests against the real schema.

**Keep:**
- `BackupVerifierManifestTest:26-142`
- `BackupVerifierRequiredSemanticQueryTest`
- `BackupVerifierSavingsLedgerTest`
- `CostbackupBundleTest`
- `CostbackupBundleExtractFailureTest`
- `BackupRestoreContractTest`
- restore journal and maintenance tests
- `RestoreCancelAfterSwapRecoveryTest`
- `BackupRestoreArchitectureGuardTest`
- `BackupRestoreRoundtripGoldenTest`
- `BackupRestoreIntegrityE2ETest`
- `BackupRestoreMoneyIntegrityScenarioTest`
- `BackupRestoreViewModelTest` and its PrivacyDenial variant
- `DbGuardPolicyFixtureTest`

**Extend:**
- `DatabaseBackupRepositoryImplTest`
  - **Fixture break:** `createFullSchemaDatabase` (`:2998-3017`) builds every table as `(id INTEGER PRIMARY KEY)`. Once capture is wired, it fails with `SEMANTIC_CAPTURE_FAILED`. The capture slice must update the fixture or add a real-schema variant.
  - The `tier1Manifest` tests (`:531`, `:1507`, `:1545`, `:1632`) take the LEGACY path and are unaffected.
- `CostbackupBundleTest`: round-trip with and without the new section.

**New:**
- an INV-9 migration-chain enumeration test
- a static no-`'EXPENSE'`/`'INCOME'` test
- a static test that the registry is the only SQL source
- a read-only write-attempt test (AE-16)
- VM notice and failure-mapping tests

**Shards:** no explicit `data.backup` entry was found in `config/validation/test-shards.json`. Check it if a new package is used.

## Workers and barriers
- No worker changes.
- Maintenance modes and journal states are unchanged.
- Capture and the C1/C2/C3 scans lengthen the maintenance windows on large databases, and no timeout exists. See Q5.

## Conflicts
- **loan-installments (main conflict):** loan S2 (`TABLE_TIERS`, the 151→152 constants), S2b (orphan checks next to `BackupVerifier:517-546`, manifest counts, privacy stripping) and S6c (reconcile before snapshot verify). Shared files are always sequential (`loan-installments-plan.md:102-170,494-507`). INV-9 needs the 151→152 transform declared.
- **ai-fixes and app-lock:** already in the base; no conflict.
- **uf-quick-fixes:** its write set does not include any backup files (orchestrator-checked against `uf-quick-fixes-plan.md`). Q8 is closed.
- **`ExpenseDao.kt`:** uncommitted MC changes exist in the main worktree only. Any constant extraction must rebase on the final MC state.
- **Other restore work:** any edit to `restoreCostBackup` (`:1120-1460`) must be sequential with it.

## Validation (validation-runner; batched)
1. `compile`, per batch.
2. `targeted-unit-test` with these filters:
   - `*BackupVerifier*`
   - `*SemanticAggregate*`
   - `*CostbackupBundle*`
   - `*DatabaseBackupRepositoryImplTest*`
   - `*BackupRestoreContract*`
   - `*BackupRestoreArchitectureGuardTest*`
   - `*BackupRestoreViewModel*`
   - `*BackupRestoreRoundtripGolden*`
   - `*BackupRestoreIntegrityE2E*`
   - `*BackupRestoreMoneyIntegrityScenario*`
   - `*RestoreCancelAfterSwapRecovery*`
   - `*DbGuardPolicyFixtureTest*`
   - If `ExpenseDao` is touched, also `*ComparableHomeValueSqlParity*`, `*ExpenseRepositoryAmountSemanticsSql*` and `*ExpenseRepositoryAssistantSqlGolden*`.
3. `lint` after the strings slice.
4. `registered-guard`: `cancellation`, `pii_logging`, `db_access`, `db_artifact_sync`. Then `static-guards` once at the candidate gate.
5. `migration-tests`: one confirmation run, because the INV-9 test reads the migration chain.
6. No app-check unless a gate requires it.

## Gates
- privacy-security-guardian (required)
- architecture-guardian (required)
- reviewer-strict on every slice that touches the repository or the bundle, plus a fresh-session final acceptance
- room-migration-guardian (advisory, for INV-9 and the v152 interaction)
- planner-first

## Slice candidates
| Slice | Content | Depends on |
|---|---|---|
| S0 (optional) | Extract `BASE_SNAPSHOT_VALID_SQL` in `ExpenseDao` with parity tests. Only if Q1 = (a). | — |
| S1 | Pure verifier core: new `SemanticAggregates.kt` plus outcome types. In `BackupVerifier`: remove the dead SQL-keyed API; make parse/compare bounded and CE-safe; add `expectedAggregatesAfterMigration`. Replace the test at `:144-204` and add new tests. Nothing is wired yet, so behaviour is unchanged and the dead SQL-execution risk is removed. | — |
| S2 | Capture, manifest field and C0: `CostbackupBundle`, the repository capture region (`:756-809`), `CostBackupSnapshot`, the fixture update, `CostbackupBundleTest`. Restore ignores the section until S3. | S1 |
| S3 | Restore C1/C2, the malformed check, the typed exception, the `DatabaseImportSummary` outcome, the mapper at `:3072`. Pre-destructive only. | S2 |
| S4 | C3 via rollback, the outcome in the success summary and in `importDatabase` (`:1352-1466`). | S3 (same file region) |
| S5 | UI and strings: VM, UI state, screen, EN/ES/EL. | S3 types; can run alongside S4 |
| S6 | Docs: the `LEGAL_PATHS.md` addition, plus CODEBASE_SEGMENTS if needed. | — |

S2, S3 and S4 all edit the repository, so they run sequentially.

## Stop-gates
1. Any edit to `config/guards/*`, baselines or allowlists. This includes a new `openDatabase` exception and a G-CANCEL-02 count above 13.
2. Any `ExpenseDao` constant edit, or a verifier-local copy of the predicate.
3. The test replacement (O-2).
4. Any change to the order of restore steps, journal states, maintenance modes, or the `DiagnosticReasonCode` enum.
5. Any Room or schema change (none expected).
6. es/el strings sign-off.
7. Merge ordering with MEC/loan.

## Owner questions beyond O-1..O-6
Q1–Q9 are answered with recommendations in `decision-sheet.md`.

## Unverified (the planner or implementer must confirm)
- `Expense.kt:164` and `:243-249`
- `CostbackupBundle.kt:23,388` (manifest inside the encrypted zip)
- `ExpenseDao:1600` (`isNotMine`)
- `failJournal` reason validation
- the `SafeEventMetadata` key limits
- `isRestoreDurabilityFailure` (does it match on type or on message?)
- the `verify` exception pattern matching mode
- `verifySummaryPreservedForVerification` equality
- how a malformed-section parse failure surfaces in `extract`
- `LEGAL_PATHS.md:1795`
