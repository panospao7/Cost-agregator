# Domain spec: restore-semantic-verify (UF-46, T3)

Revised 2026-10-26 per `decision-sheet.md` (owner accepted every recommendation, including O-2). Corrected facts come from `impact-map.md`.
Facts were read at `Cost-agregator-int` @ `e6967db1`. **Build base:** the integration tip after the MEC+loan merge (O-5), so line numbers will move. Paths are relative to `app/src/main/java/com/yourname/expensetracker/`.
Evidence row: `docs/features/_discovery/unfinished-features/verification-backend.md:21`.

## 0. Current state (re-checked in -int)

- `data/backup/BackupVerifier.kt`: `:26-34` TODO. `:298-330` `verify(..., manifestSemanticAggregates = emptyMap())`. `:448-454` runs the semantic pass only if the map is non-empty. `:612-666` holds `SEMANTIC_AGGREGATE_QUERIES` and `verifySemanticAggregates`.
- `DatabaseBackupRepositoryImpl` lives in **`data/repository/`**, not `data/backup/`. Both of its production `verify()` calls pass two arguments: `:781` (backup snapshot) and `:1352` (live post-swap). `BackupManifest` (`data/backup/CostbackupBundle.kt:49-100`) has no aggregate field.
- **The manifest-SQL path is unreachable in production today.** `verifySemanticAggregates` is called only from `verifyInternal:450` and from tests (`BackupVerifierManifestTest:171,196`), so there is no live security issue. **The SQL-keyed API must still be deleted** (INV-1).
- Defects that make the existing code unusable if it were wired in:
  - D1: `'EXPENSE'`/`'INCOME'` don't exist. `TransactionType` is PURCHASE/WITHDRAWAL/TRANSFER/DEPOSIT/UNKNOWN (`data/database/entity/Expense.kt:243-249`).
  - D2: `effectiveAmount` is a Kotlin getter (`Expense.kt:164`), not a column. The test passes only on a fake schema (`BackupVerifierManifestTest.kt:151`).
  - D3: the map key is manifest-supplied SQL, which is executed (`:642-644`).
  - D4: messages embed `expected=`/`actual=` values (`:652`). They reach exception text at repository `:788`/`:1354`, and a test pins this (`:200`).
  - D5: `catch (e: Exception)` swallows `CancellationException` (`:656`).
  - D6: SQL `SUM(REAL)` depends on the SQLite version, which risks a false cross-device mismatch.
  - D7: the COUNT aggregates duplicate the Tier-1 `tableCounts`.
- **The same leak exists today:** `BackupVerifier:340/347/359/381/399-403` (integrity text, table names, counts) is joined into the exception text at `:788` and `:1354` (Q3).
- **Test fixture:** `DatabaseBackupRepositoryImplTest.createFullSchemaDatabase` (`:2998-3017`) builds every table as `(id INTEGER PRIMARY KEY)`. Once capture is wired it would silently degrade to NOT_CAPTURED. **The capture slice must upgrade it to the real schema**, or add a real-schema variant, so that capture is actually exercised.
- Migrations v145→v151 (`DatabaseMigrations.kt:24-173`) never write `expenses` or `manual_recurring_expenses`, and every fingerprinted column exists at v145. Loan's v151→152 is covered by INV-9.
- Confirmed during this revision:
  - C1/C2 failures already return the bounded `Exception("STAGED_VERIFICATION_FAILED")`/`("POST_MIGRATION_VERIFICATION_FAILED")` (`:1176-1181`, `:1208-1213`).
  - At C3, every non-durability exception folds into the rollback: `failJournal("VERIFICATION_FAILED_ROLLED_BACK")` and a result with the same text (`:1427-1466`).
  - `isRestoreDurabilityFailure` (`:915-918`) matches on type only.
  - `EventOutcome.SKIPPED` exists.
  - The canonical income query (`ExpenseDao:1600`) is `DEPOSIT_TYPE_SQL … AND isNotMine = 0`, where `isNotMine` is a literal and not a constant.
- The planner still confirms these:
  - the `SafeEventMetadata` key limits;
  - `failJournal` reason validation;
  - `verifySummaryPreservedForVerification` equality, before adding a summary field;
  - `LEGAL_PATHS.md:1795`.

## 1. Entities and value objects (all new; nothing is persisted in Room)

- `SemanticAggregateId`: a closed enum of **four** IDs, and the only keys allowed in the manifest.
  - `EXPENSE_RAW_BY_TYPE_CURRENCY`: every `expenses` row, with no filter. Key is (stored `transactionType` text, stored `currency` text). Value is raw `amount`.
  - `SPENDING_EFFECTIVE_BY_CURRENCY`: the filter is exactly `ExpenseDao.SPENDING_TYPE_SQL`. Key is `currency`. Value is exactly `ExpenseDao.EFFECTIVE_AMOUNT_SQL`.
  - `INCOME_EFFECTIVE_BY_CURRENCY`: the filter is exactly `ExpenseDao.DEPOSIT_TYPE_SQL`. Key is `currency`. Value is `EFFECTIVE_AMOUNT_SQL`.
  - `RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY`: every `manual_recurring_expenses` row. Key is (`isActive`, `currency`). Value is raw `amount`.
- **Composition rule:**
  - The three constants are referenced by symbol and used unchanged. `ExpenseDao` is not edited.
  - No other predicate is added or copied, and that includes `isNotMine = 0`. `EFFECTIVE_AMOUNT_SQL` already yields `0.0` for not-mine rows, so such a row adds to `n` and contributes 0 to `s`.
  - These aggregates are integrity fingerprints, not dashboard totals. Parity with any UI total is neither claimed nor tested.
- **Deferred to v2** (each needs a `version` bump):
  - `EXPENSE_BASE_SNAPSHOT_BY_BASE_CURRENCY`. It has no reusable constant. Extracting one would edit MC-owned money SQL, and a local copy would duplicate the rule (Q1(c)).
  - The savings ledger, receipt totals and loan aggregates.
- `AggregateBucket`:
  - `key: List<String>` holds the raw stored text. Comparison is case-sensitive.
  - `n: Int >= 0` is the row count.
  - `u: Int >= 0` counts NULL or non-finite values.
  - `s: BigDecimal` is the exact sum of the finite values.
- **Value semantics:**
  - Each per-row value is the SQLite REAL that the code-owned SQL returns. `Cursor.isNull` is checked first, then the value goes through `BigDecimal(double)`, which is the exact binary expansion (never `valueOf`). Sums are exact.
  - Scale is unbounded and there is no rounding mode, because no rounding happens.
  - `s` is serialized as a JSON **string** (`toPlainString()`). Equality uses `compareTo == 0`.
  - Why not minor units: `CurrencyFractionDigits.of` can be null or differ between JDK versions, and rounding would hide corruption. Why not SQL `SUM`: see D6.
- `SemanticAggregateSet`:
  - `version: Int = 1`
  - `status ∈ {CAPTURED, NOT_CAPTURED}`
  - `notCapturedReason ∈ {SEMANTIC_CAPTURE_FAILED, SEMANTIC_SELF_CHECK_FAILED, SEMANTIC_BUCKET_LIMIT}?`, non-null exactly when the status is NOT_CAPTURED
  - `aggregates: Map<SemanticAggregateId, AggregateEntry>`, empty exactly when the status is NOT_CAPTURED
  - `AggregateEntry = (definitionHash, buckets)`. `definitionHash` is the first 16 hex characters of SHA-256 over the canonical definition text (filter SQL, value SQL, key columns). It contains no user data.
- `BackupManifest.semanticAggregates: SemanticAggregateSet? = null` lives in `manifest.json`, inside the encrypted zip. `null` means either a legacy backup or the absent-section fallback (INV-5). `fromJson` never throws because of this section; it yields a parsed set or a `Malformed(code)` marker (INV-12).
- `SemanticVerificationOutcome` (`domain/backup`) is `VERIFIED` or `NOT_VERIFIABLE(reason)`. It is added to `DatabaseImportSummary` (`DatabaseBackupRepository.kt:128`).
- A mismatch never produces a summary:
  - at C1/C2 it is `BackupSemanticVerificationException(code)` (bounded `message = code`, modeled on `BackupSchemaUnsupportedException`);
  - at C3 it is the existing rollback result (INV-8).
- `SemanticCode` is a bounded enum local to `data/backup`. It is used only as the `semanticCode` metadata value and the exception code, and is **never added to `DiagnosticReasonCode`**. Its values:
  - `SEMANTIC_VERIFIED`, `SEMANTIC_MISMATCH`, `SEMANTIC_QUERY_FAILED`, `SEMANTIC_MANIFEST_MALFORMED`
  - `SEMANTIC_CAPTURE_FAILED`, `SEMANTIC_SELF_CHECK_FAILED`, `SEMANTIC_BUCKET_LIMIT`
  - `SEMANTIC_NOT_VERIFIABLE_{LEGACY, VERSION, NOT_CAPTURED, DEFINITION_CHANGED, NO_MANIFEST}`

  Checkpoints are `C0_BACKUP_SELF_CHECK`, `C1_STAGED_SOURCE`, `C2_STAGED_POST_MIGRATION` and `C3_LIVE_POST_SWAP`.
- **Limits (Q5):**
  - At most 256 buckets per aggregate. At capture, exceeding this makes the whole set `NOT_CAPTURED(SEMANTIC_BUCKET_LIMIT)`. At parse, exceeding it is malformed.
  - At most 64 characters per key element and 2048 characters per `s`, checked at parse.
  - **There is no timeout in v1.**

## 2. Invariants

| ID | Rule | Single owner |
|---|---|---|
| INV-1 | Aggregates come only from the closed four-ID registry, and all SQL is code-owned. No manifest string is ever executed or interpolated. `verifySemanticAggregates(db, Map<String,String>)`, `SEMANTIC_AGGREGATE_QUERIES` and the `manifestSemanticAggregates` param are **deleted**, not deprecated (§0, D3, D7). | `BackupVerifier` |
| INV-2 | Type classification only ever references `ExpenseDao.SPENDING_TYPE_SQL` or `DEPOSIT_TYPE_SQL`, and the value only `EFFECTIVE_AMOUNT_SQL`, all unchanged and with no added or copied predicate (§1). No type literal appears. `EXPENSE_RAW…` keys by stored text, so no row drops out. **Conservation check at capture:** Σ`n`(EXPENSE_RAW…) must equal `tableCounts["expenses"]` and Σ`n`(RECURRING…) must equal `tableCounts["manual_recurring_expenses"]`. A violation gives `NOT_CAPTURED(SEMANTIC_CAPTURE_FAILED)` (INV-5), never a failed backup. | `BackupVerifier` |
| INV-3 | No aggregate crosses currencies: each money bucket key holds exactly one currency text. Aggregates never convert anything and never read the home currency, the `base*` columns, `MoneyAggregate*`, CV or `roundSnapshot`. | `BackupVerifier` |
| INV-4 | Exact value semantics as defined in §1, with zero tolerance. NULL and non-finite values count in `u` and never in `s`. An absent bucket is not equal to a bucket with `s=0`. | `BackupVerifier` |
| INV-5 | **Capture never fails a backup (Q6).** Capture reads the bundled bytes. It uses the read-only `snapshotDb` handle (`:765-778`), runs after every write to `tempDb` (`sanitizeExport`, then loan S6c's reconcile when present), runs after `collectTableCountsStrict`, and finishes before `writeBundle`. Any non-CE capture failure (query error, conservation violation, more than 256 buckets, any other exception) lets the backup **complete**. In that case: the section is `NOT_CAPTURED(reason)` with empty aggregates; one bounded event `SEMANTIC_CAPTURED` (`EventOutcome.SKIPPED`, `VALIDATION_FAILED`, `semanticCode=reason`) is emitted; there is no user-visible string. A partial CAPTURED section is never written. If even the NOT_CAPTURED section can't be built, the section is omitted (`null`). Either way, a later restore is NOT_VERIFIABLE (INV-11). `CancellationException` is rethrown, and cancellation then works as it does today. The existing Tier-1/integrity failure at `:781` still fails the backup, unchanged. | Orchestration: `DatabaseBackupRepositoryImpl.runCostBackupExport`. Computation: `BackupVerifier` |
| INV-6 | **C0:** the captured set is serialized, parsed back and compared before `:781`. On failure the set degrades per INV-5 to `NOT_CAPTURED(SEMANTIC_SELF_CHECK_FAILED)` and the backup completes. The status is CAPTURED only if C0 passed. | `DatabaseBackupRepositoryImpl` |
| INV-7 | **C1** runs on the staged DB at the source schema, after `verifyQuick` (`:1169`). **C2** runs inside `postMigrationCheck` (`:1198`). Both run before the safety backup and the swap. A mismatch or query failure takes the existing pre-destructive abort (`exitAfterPreDestructiveAbort`, `failJournal`, staging cleanup) with the **reused** journal reason `STAGED_VERIFICATION_FAILED` (C1) or `POST_MIGRATION_VERIFICATION_FAILED` (C2). The returned failure is `BackupSemanticVerificationException(code)`. The live DB is not touched. **There is no override (O-4).** | `DatabaseBackupRepositoryImpl.restoreCostBackup` |
| INV-8 | **C3** runs at `:1352`. A mismatch or query failure throws `BackupSemanticVerificationException` into the existing catch (`:1427`), then the existing rollback, `failJournal("VERIFICATION_FAILED_ROLLED_BACK")` and the existing result. The rollback code does not change. The exception is not a durability type, so `isRestoreDurabilityFailure` stays false. A rollback failure means CRITICAL_RECOVERY_REQUIRED, unchanged. Semantic detail goes only into bounded event metadata. There is no override. | `DatabaseBackupRepositoryImpl.restoreCostBackup` |
| INV-9 | The C2/C3 expectation is `expectedAggregatesAfterMigration(set, sourceDbVersion)`, modeled on `expectedCountsAfterMigration` (`BackupVerifier.kt:238`). It is the identity for v145..v151. **This feature owns the declaration for loan's v151→152:** identity unless that migration UPDATEs, INSERTs, DELETEs or rebuilds `expenses` or `manual_recurring_expenses`, in which case a transform is declared. Any later migration that writes a fingerprinted table must declare a transform. Otherwise sources below that version get `NOT_VERIFIABLE_DEFINITION_CHANGED` for the affected aggregate at C2/C3, and C1 still verifies them. A pinned test enumerates every registered migration up to the current version. | `BackupVerifier` |
| INV-10 | A manifest `definitionHash` that differs from the current hash makes that aggregate `NOT_VERIFIABLE_DEFINITION_CHANGED`, never MISMATCH. A future change to a reused constant therefore cannot block a good backup. | `BackupVerifier` |
| INV-11 | These cases are NOT_VERIFIABLE, never VERIFIED: a missing section (`LEGACY`), an unknown `version` (`VERSION`), `NOT_CAPTURED` (including an unknown or missing `notCapturedReason`), and `importDatabase` with no manifest (`NO_MANIFEST`). The restore then proceeds under every existing gate (tiers, integrity, FK, orphans, schema floor). The outcome surfaces only on the restore screen and in diagnostics. `DebugViewModel` is unchanged (Q9). | Orchestration: `DatabaseBackupRepositoryImpl`. Classification: `BackupVerifier` |
| INV-12 | At a known `version`, a section is malformed if any of these holds: CAPTURED without exactly the four IDs; an unknown ID; NOT_CAPTURED with aggregates present; a bad type; a negative `n`/`u`; an unparseable `s`; a duplicate key; or a limit exceeded. A malformed section blocks the restore before any destructive step (`SEMANTIC_MANIFEST_MALFORMED`). The check sits next to `validateManifestCompleteness` (`:1141`) and reuses that block's existing failure handling and reason. Adding an ID requires a `version` bump (pinned test). | Structure: `BackupManifest.fromJson`. Outcome: `BackupVerifier` |
| INV-13 | A query failure at C1, C2 or C3 is `SEMANTIC_QUERY_FAILED` and fails closed per INV-7 or INV-8. It is never NOT_VERIFIABLE and never a pass. CE is always rethrown (D5). | `BackupVerifier` |
| INV-14 | **Bounded text (Q3).** The exceptions at `DatabaseBackupRepositoryImpl` `:788` and `:1354` carry **only** a bounded code (`SNAPSHOT_VERIFICATION_FAILED`, `LIVE_VERIFICATION_FAILED`) and never `errors.joinToString`. This also removes the existing count, table and integrity text from those messages. The internal `VerificationResult.errors` issue lists are unchanged. Diagnostics, journal reasons, Timber output and every new exception carry only these: code, aggregate ID, checkpoint and the count of mismatched buckets. They never carry amounts, sums, per-bucket counts, currency or type text, SQL, paths or `Throwable.message`. There is no journal-state change and no `DiagnosticReasonCode` change (Q4). | Exception text: `DatabaseBackupRepositoryImpl`. Issue text: `BackupVerifier` |
| INV-15 | Any mismatch at any checkpoint is a failure (INV-7/8). Otherwise the outcome is VERIFIED only if all four aggregates verified at C1, C2 and C3; C2/C3 may be NOT_VERIFIABLE only under INV-9. Otherwise it is NOT_VERIFIABLE with the first reason in registry order. | `DatabaseBackupRepositoryImpl` |
| INV-16 | The verifier is read-only: it uses `OPEN_READONLY`, never writes, and never touches the Room singleton (`DatabaseReadBarrier` is intact). New functions take already-open handles, so no new `openDatabase` guard entry is needed. No new legal path is added. | `BackupVerifier` |
| INV-17 | **UI (O-1, Q2).** There are exactly two new strings, in EN, EL and ES. (1) The not-verifiable notice, shown as non-blocking info on restore success when the outcome is NOT_VERIFIABLE. (2) "Restore blocked: backup content didn't match", shown for `BackupSemanticVerificationException(SEMANTIC_MISMATCH)`. The mapping is by type, before the `"password"` substring match (`BackupRestoreViewModel:309`). MALFORMED and QUERY_FAILED use the existing generic restore-failure mapping. C3 failures show the existing rolled-back message. A capture failure has no string. | `BackupRestoreViewModel` |

## 3. State machines

**Capture (backup).** Steps: SNAPSHOT_CREATED → SANITIZE → [loan S6c RECONCILE, if present] → COUNTS → CAPTURE → C0 → TIER_VERIFY (`:781`) → BUNDLE.
- If CAPTURE and C0 both pass, the set is CAPTURED.
- If CAPTURE fails, the set is `NOT_CAPTURED(reason)` and C0 is skipped.
- If C0 fails, the set is `NOT_CAPTURED(SEMANTIC_SELF_CHECK_FAILED)`.
- Each degrade emits one `SEMANTIC_CAPTURED`/SKIPPED event, and the backup **continues to success**.
- Illegal:
  - a backup that fails because of semantic capture;
  - a CAPTURED status after a failed C0;
  - a partial CAPTURED section;
  - a capture that runs before the last write to `tempDb`.
- CE is rethrown and the backup is cancelled as today. A retry captures again from scratch. Process death uses the existing BACKUP_EXPORTING path, and there is no persisted capture state.

**Restore outcome (per run; no new RestoreJournal states or reasons).**

```
manifest parsed ─► section? ─ absent ──────────────────────► NOT_VERIFIABLE(LEGACY) ─────┐
                   │ unknown version ──────────────────────► NOT_VERIFIABLE(VERSION) ────┤ existing gates only
                   │ NOT_CAPTURED ─────────────────────────► NOT_VERIFIABLE(NOT_CAPTURED)┘
                   │ malformed (known version) ────────────► BLOCKED (pre-destructive, existing manifest reason)
                   ▼ CAPTURED
 C1 staged ─ mismatch/query-fail ─► BLOCKED (journal STAGED_VERIFICATION_FAILED)
   ▼ ok
 C2 post-migration ─ mismatch/query-fail ─► BLOCKED (journal POST_MIGRATION_VERIFICATION_FAILED)
   ▼ ok
 [safety backup, SWAPPING] ─► C3 live (VERIFYING) ─ mismatch/query-fail ─► ROLLING_BACK ─► FAILED (VERIFICATION_FAILED_ROLLED_BACK)
   ▼ ok                                                          └ rollback fail ─► CRITICAL_RECOVERY_REQUIRED
 ASSETS_RESTORING ─► COMPLETE ─► SuccessNeedsRestart(outcome = VERIFIED | NOT_VERIFIABLE)
```

- Illegal transitions:
  - VERIFIED when any aggregate was skipped;
  - VERIFIED for a LEGACY or NOT_CAPTURED backup;
  - NOT_VERIFIABLE after a query failure;
  - any "restore anyway" after BLOCKED (O-4);
  - SWAPPING after a C1 or C2 failure;
  - NORMAL after a failed rollback (already forbidden).
- Process death:
  - During C1/C2, the journal is PREPARING or STAGED and the live DB is intact, so the existing recovery applies.
  - During C3, the journal is VERIFYING and `checkAndRecover` rolls back. Nothing new is persisted.
- Retry means re-running the restore, which is idempotent because it is read-only. Cancellation rethrows CE at every checkpoint, and `finishCancelledRestore` applies.

## 4. Edge cases

- **Mixed currencies:** there is one bucket per raw currency text. `"EUR"` ≠ `"eur"`, stricter than the dashboard `UPPER()`. The `""` and `XXX` sentinels are ordinary keys.
- **Missing or stale FX:** irrelevant, because nothing is converted. In v1 the `base*` and `exchangeRateUsed` columns are **not** fingerprinted, because that aggregate is deferred to v2. Their corruption is caught only by the existing tier, integrity and FK gates. This is an accepted gap.
- **Not-mine rows:** a not-mine row appears in SPENDING/INCOME with `n` incremented and an effective value of `0.0`. A flipped `isNotMine` flag changes `s` and is detected.
- **Unavailable vs zero:** an empty table gives `[]`, which verifies only against an empty restored table. NULL or ∞ values count in `u`. SQLite stores NaN as NULL.
- **Rounding:** none. Sums are exact and independent of order, SQLite version, JDK version, and VACUUM INTO vs file copy.
- **Time zones, DST, month-end:** no time dimension is used. The `savings_sweep_month_acceptance` seed is outside the set.
- **Edits, deletes, recurring:** inactive rules are keyed under `isActive = 0`. `expenses` has no soft delete, so every physical row counts.
- **Receipts:** there is no value aggregate, because OCR totals are partial or nullable. The existing Tier-1 checks and orphan check cover receipts.
- **Duplicates and hostile manifests:** a duplicate key is malformed. An over-limit section, or a CAPTURED status with missing IDs, is malformed. Nothing from the manifest is executed (INV-1).
- **Large DB (Q5):** more than 256 buckets in any aggregate makes the backup NOT_CAPTURED, and that backup still succeeds. Capture and C1–C3 lengthen the maintenance window, and v1 has no timeout. A synthetic large-DB test measures the time taken.
- **Redacted backups:** capture follows `sanitizeExport` and the S6c reconcile (INV-5). Expenses are not sanitized (loan D-5).
- **Raw `.db` import** (`importDatabase`, `:1915`) gives `NO_MANIFEST`. `DebugViewModel.importDatabase` shows nothing new (Q9).
- **Old schema:** v145–v151 backups from older apps are LEGACY. Below v145 or above the current version is still rejected first (`BACKUP_SCHEMA_UNSUPPORTED_*`).
- **Older app reading a new backup:** `fromJson` ignores unknown keys, so `backupFormatVersion` and the header `FORMAT_VERSION` stay at 1.

## 5. Persistence impact

- **Room: none.** There is no entity, column, DAO, schema JSON, version bump or migration. Fingerprinted columns are read through the existing raw read-only handles.
- **Bundle:** one optional `semanticAggregates` object is added inside the encrypted `manifest.json`. There is no format-version change and no backfill. Existing backups restore as LEGACY.
- **Nothing is destructive.** The change only adds pre-destructive aborts, and post-swap failures use the existing rollback. It changes no journal state, journal reason, maintenance mode or `DiagnosticReasonCode`.

## 6. Privacy

- **Stored:** exact per-currency sums and counts. This is the same class of data as the bundled DB, in the same encrypted container. Nothing new goes in the plaintext header. Nothing new is exported.
- **Logged:** bounded codes only (INV-14). The `SafeEventMetadata` keys are `semanticCode`, `aggregateId`, `checkpoint` and `mismatchedBuckets`, and events use the existing `DiagnosticReasonCode.VALIDATION_FAILED`.
- The exceptions at `:788` and `:1354` become bounded codes, which closes the existing table, count and integrity leak (Q3).
- UI copy never shows values. Discreet mode and app-lock are unaffected (app-lock state is never in `.costbackup`).
- `BackupSemanticVerificationException.message` equals its code. The VM maps it by type and never by message text.

## 7. Acceptance examples

Synthetic fixture **F** uses the real current schema (v152 after the MEC+loan merge), built through the upgraded fixture (§0).
- E1: PURCHASE EUR 12.5, own.
- E2: PURCHASE EUR 40.0, with `isSharedExpense = 1`, `mySharePercentage = 50`, `myShareAmount = NULL`.
- E3: DEPOSIT EUR 1000.0.
- E4: PURCHASE USD 9.99, `isNotMine = 1`.
- E5: TRANSFER EUR 200.0.
- E6: UNKNOWN GBP 5.0, with `baseAmount = 5.8` (not fingerprinted).
- R1: active EUR 9.0. R2: inactive USD 15.0.

- **AE-1 (INV-1):** Given a manifest key `"SELECT 1"` or `"DROP TABLE x"`, then the section is MALFORMED and blocked before the swap. No manifest string reaches `rawQuery`. A static test asserts that the registry is the only SQL source and that the SQL-keyed API no longer exists.
- **AE-2 (INV-2):** Given F, capture gives:
  - SPENDING EUR `n=2`, `s=32.5` (12.5 + 40.0×50/100);
  - INCOME EUR `n=1`, `s=1000`;
  - Σn(EXPENSE_RAW) = 6 = `tableCounts.expenses`.

  A definition that uses `'EXPENSE'` fails this test. A static test asserts that no `'EXPENSE'` or `'INCOME'` literal appears, that the filter and value are the `ExpenseDao` constants by reference, and that no extra predicate is present.
- **AE-3 (INV-3/4):** Given F, SPENDING USD is `n=1`, `s=0`, because E4 is not-mine. That bucket is present, which is different from an absent bucket. EXPENSE_RAW includes `(PURCHASE,USD)` and `(UNKNOWN,GBP)`. RECURRING has `(1,EUR)` and `(0,USD)`. No bucket mixes currencies.
- **AE-4 (INV-4):** Given E1–E6 in two row orders, the sums are identical. `s(PURCHASE,USD)` equals `BigDecimal(9.99).toPlainString()`. Changing E1 to 12.500000001 gives MISMATCH. A forced NULL `amount` gives `u=1`.
- **AE-5 (INV-5, Q6):** Capture failure means the backup succeeds and the restore reports not verifiable.
  - Given a query failure forced during capture, `createCostBackup` returns success and a bundle is written.
  - The manifest section is `NOT_CAPTURED(SEMANTIC_CAPTURE_FAILED)` with empty aggregates, and exactly one `SEMANTIC_CAPTURED`/SKIPPED event is emitted, carrying only `semanticCode`. No user string is shown.
  - When that bundle is restored, the result is `SuccessNeedsRestart(NOT_VERIFIABLE(NOT_CAPTURED))` and the restore screen shows the notice.
  - The same holds for a forced conservation violation. If NOT_CAPTURED itself can't be built, the section is `null` and the restore is `NOT_VERIFIABLE(LEGACY)`.
- **AE-6 (INV-6):** Given a serializer that writes `s` as a JSON number, C0 fails, and the backup succeeds with `NOT_CAPTURED(SEMANTIC_SELF_CHECK_FAILED)`. A later restore is NOT_VERIFIABLE.
- **AE-7 (INV-5 order):** Given an injected write to `tempDb` after sanitize (stand-in for S6c), the captured set equals a set recomputed from the bundled DB file.
- **AE-8 (INV-7, O-4):** Given a staged DB with E3 altered:
  - the restore fails at C1 with journal reason `STAGED_VERIFICATION_FAILED` and result `BackupSemanticVerificationException(SEMANTIC_MISMATCH)`;
  - the live DB is byte-identical, and there is no safety backup or swap;
  - the VM shows the "blocked" string, and no override action exists.

  A post-migration-only alteration fails at C2 with `POST_MIGRATION_VERIFICATION_FAILED`.
- **AE-9 (INV-8):** Given an injected C3 mismatch, the existing rollback runs. The journal and the result are `VERIFICATION_FAILED_ROLLED_BACK`, the event metadata has `checkpoint=C3_LIVE_POST_SWAP`, and the pre-restore data is intact. If the rollback also fails, the state is CRITICAL_RECOVERY_REQUIRED.
- **AE-10 (INV-9):** Given a v149-sourced backup with a section, C2/C3 use the identity transform and the outcome is VERIFIED. The enumeration test covers every registered migration, including loan's v151→152. It fails if any migration writes `expenses` or `manual_recurring_expenses` without a declared transform.
- **AE-11 (INV-10):** Given a wrong SPENDING `definitionHash` and correct other hashes, the outcome is `NOT_VERIFIABLE(DEFINITION_CHANGED)` and the restore succeeds.
- **AE-12 (INV-11, Q9):** Given `databaseVersion = 149` and no section, the outcome is `NOT_VERIFIABLE(LEGACY)` and the count, FK and orphan gates still apply. `importDatabase(raw.db)` gives `NO_MANIFEST`. `DebugViewModel` output is unchanged.
- **AE-13 (INV-12, Q5):** Given `version = 1` with a negative `n`, a duplicate key, 257 buckets, CAPTURED with three IDs, or NOT_CAPTURED with aggregates present, the restore is blocked before the swap with `SEMANTIC_MANIFEST_MALFORMED`. `fromJson` does not throw. `version = 99` gives `NOT_VERIFIABLE(VERSION)`.
- **AE-14 (INV-13):** Given a staged DB missing `manual_recurring_expenses.isActive`, the result is `SEMANTIC_QUERY_FAILED` and BLOCKED. Cancellation during C2 propagates CE.
- **AE-15 (INV-14, Q3):**
  - For every failing AE, logs, events, journal and exception text contain none of `12.5`, `1000`, `EUR`, `USD`, `PURCHASE`, `SELECT` or a file path.
  - A Tier-1 count mismatch at `:788` gives exactly `SNAPSHOT_VERIFICATION_FAILED`, and one at `:1354` exactly `LIVE_VERIFICATION_FAILED`, with no table name, count or integrity text.
  - `VerificationResult.errors` still holds the existing detail.
- **AE-16 (INV-15):** Given F backed up and restored unchanged, the result is `SuccessNeedsRestart(VERIFIED)`.
- **AE-17 (INV-16):** The verifier opens only `OPEN_READONLY` handles, and a write attempt fails the test.
- **AE-18 (Q5):**
  - Given 257 distinct `expenses.currency` values, the backup succeeds with `NOT_CAPTURED(SEMANTIC_BUCKET_LIMIT)`.
  - A synthetic large DB records how long capture takes and asserts only correctness, with no timing threshold in v1.
- **AE-19 (INV-17):** Exactly two new keys exist in `values`, `values-es` and `values-el`. MISMATCH maps to the "blocked" string by type, before the `"password"` substring check, and is never mapped to the password error. A NOT_VERIFIABLE success shows the notice. MALFORMED and QUERY_FAILED use the existing generic mapping. Capture failure adds no string.

## 8. Rule ownership and legal path

Ownership is per invariant (§2). The legal path is unchanged: the existing "Backup / Restore" flow (`LEGAL_PATHS.md:489-551`) through `DatabaseBackupRepositoryImpl` (`runCostBackupExport`, `restoreCostBackup`, `importDatabase`), `BackupVerifier` and `CostbackupBundle`.

Approved addition (O-6), applied in the docs slice under the doc lock:
- **Under RESTORE:** "Semantic aggregates (UF-46): four fingerprints captured from the final sanitized snapshot. A failed capture never fails the backup (NOT_CAPTURED). They are verified at C1/C2 before the swap and at C3 after it. Legacy, unknown-version and not-captured backups give NOT_VERIFIABLE (a non-blocking notice). A mismatch, a malformed section or a query failure blocks the restore, or rolls it back after the swap. There is no override."
- **Under FORBIDDEN:** "❌ Executing manifest-supplied SQL", "❌ Semantic outcome VERIFIED without full comparison" and "❌ Joined verifier messages in exception text".

## 9. Interactions

- **loan-installments / MEC (O-5, binding order):**
  - MEC+loan merges into integration first, and this feature is cut from the merged tip.
  - Capture runs after loan S6c's reconcile. If S6c hasn't landed yet, whichever change lands second guarantees that order (INV-5, AE-7).
  - This feature owns the INV-9 declaration for v151→152.
  - Shared files (`BackupVerifier`, `DatabaseBackupRepositoryImpl`, `CostbackupBundle`) are edited sequentially. A loan aggregate would come later as v2.
- **money-correctness:** the three `ExpenseDao` constants are reused unchanged, and `ExpenseDao` is not edited (stop-gate). The change does not use `MoneyAggregateBuilder`, CV or `roundSnapshot`. Rescue restore (`FinancialRescueCoordinator`) is not covered.
- **Wave-2 restore work:** edits to `restoreCostBackup` around `:1120-1466` are sequential with this change.
- **uf-quick-fixes:** no overlap (Q8, closed).
- **App-lock and discreet mode:** no interaction.

## 10. Resolved (2026-10-26)

- **O-1:** a non-blocking info notice on restore success when the outcome is NOT_VERIFIABLE (INV-17).
- **O-2:** approved. Replace `BackupVerifierManifestTest.kt:144-204` (do not delete it) with real-schema tests for AE-1, AE-2, AE-4 and AE-15. Lines 26-142 stay.
- **O-3:** four aggregates (§1). Base-snapshot, savings ledger, receipts and loans are deferred to v2.
- **O-4:** no override. A mismatch blocks the restore at C1/C2 or rolls it back at C3 (INV-7/8).
- **O-5:** MEC+loan goes first. Capture runs after S6c's reconcile, and whichever lands second enforces that. This feature owns the v151→152 INV-9 declaration.
- **O-6:** the `LEGAL_PATHS.md` addition in §8 is approved for the docs slice.
- **Q1:** (c) defer the base-snapshot aggregate. There is no `ExpenseDao` edit and no local copy.
- **Q2:** exactly two new strings (the notice and "blocked: didn't match") in EN, EL and ES. There is no capture-failure string.
- **Q3:** the exceptions at `:788` and `:1354` carry bounded codes only, and the internal issue lists are unchanged (INV-14).
- **Q4:** reuse `STAGED_VERIFICATION_FAILED` and `POST_MIGRATION_VERIFICATION_FAILED`; C3 uses the existing rollback reason. Detail goes in bounded metadata only. There is no journal-state change and no `DiagnosticReasonCode` change.
- **Q5:** above 256 buckets, record NOT_CAPTURED with a bounded diagnostic. No timeout in v1. A synthetic timing test is added.
- **Q6:** capture or C0 failure lets the backup complete as NOT_CAPTURED (or with the section absent), with a bounded diagnostic. The later restore is NOT_VERIFIABLE (INV-5/6).
- **Q7:** same as O-5.
- **Q8:** closed. uf-quick-fixes touches no backup files.
- **Q9:** the restore screen and diagnostics only. `DebugViewModel` is unchanged.

**No OPEN decisions remain.** Before merge (a sign-off, not a design decision): the owner signs off the el/es copy. Implementation stays on hold, because the owner chose "planning only" and it waits for the MEC+loan merge.

## Errata (orchestrator, 2026-10-26, from planner fact F2)
- **E-1:** `RestoreJournal.failJournal` keeps only reasons in `FAILURE_REASON_CODES` (`RestoreJournal.kt:910-923`) and
  stores any other reason as `UNKNOWN_ERROR`. That already happens today to the reused reasons
  `STAGED_VERIFICATION_FAILED`, `POST_MIGRATION_VERIFICATION_FAILED` and `VERIFICATION_FAILED_ROLLED_BACK`.
  - In AE-8/AE-9, "journal reason X" therefore means the literal passed to `failJournal` plus the returned result text.
  - The persisted `error` field does not change.
  - `FAILURE_REASON_CODES` must not grow (Q4).
- **E-2:** the SPENDING and INCOME aggregates use only the three `ExpenseDao` constants, with no `isNotMine` filter.
  - Not-mine rows add 0 to the sum but are counted in n.
  - These are integrity fingerprints, not dashboard totals.
