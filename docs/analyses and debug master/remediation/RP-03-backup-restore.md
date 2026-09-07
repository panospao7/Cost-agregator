# RP-03 — Backup/restore integrity (Pipeline 7)

> **Scope class:** pipeline-isolated + one crossover (encryption service shared with P12). **Mode:** strict (backup/restore is on the strict list).
> **Files (all ⟂-verify dirs by class name):** `DatabaseBackupRepositoryImpl` (data/repository/), `RestoreJournal`, `RestoreMaintenanceMode`, `MaintenanceOperationRunner`, `BackupVerifier`, `CostbackupBundle`, `BackupEncryptionService` (data/privacy/), `RestoreDatabaseOpener`, `AppStartupCoordinator` (startup/), `BackupRestoreViewModel` + `BackupRestoreScreen` (ui/screens/backup/), `MainActivity` (ui/), `ExportAnonymizer` (data/privacy/), `AiChatSessionEntity` (data/database/entity/).
> **Recommended split:** 3 PRs — (3a) save path & durability = P7-003, P7-005, P12-008; (3b) state machine = P7-001, P7-002, P7-008, P7-004, P7-006; (3c) smalls = P7-009, P7-010, P7-011.

---

## P7-003 — Backup saving fails on every supported device (HIGH)

**Problem.** `DatabaseBackupRepositoryImpl.kt:657-665` writes the `.costbackup` via `java.io.File` into `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)`; manifest lacks `MANAGE_EXTERNAL_STORAGE`/`requestLegacyExternalStorage` (targetSdk 35); on API 26–28 `WRITE_EXTERNAL_STORAGE` (maxSdk 28) is a runtime permission never requested. `mkdirs()` silently returns false; `CostbackupBundle`'s `FileOutputStream` throws.

**Fix design (SAF save, consistent with the app's existing CreateDocument usage for CSV/text export — `ExportOptionsScreen.kt:58-59` uses `CreateDocument("text/plain")`, launched `:237`, and copies into the SAF sink via `contentResolver.openOutputStream(uri)` at `:70-76`).**
1. Split bundle creation from destination: add `CostbackupBundle.create(output: OutputStream, ...): Result<Unit>` — verified feasible because `BackupEncryptionService.encrypt(plaintextFile: File, outputStream: OutputStream, password)` already exists (`data/privacy/BackupEncryptionService.kt:80-99`). **Two mechanical notes:** (a) `buildZip` takes a `File` (`CostbackupBundle.kt:489-495`) and the current temp ZIP is staged in `outputFile.parentFile` (`:290-292`) — a SAF stream has no parent dir, so the stream overload must stage the temp ZIP in `context.cacheDir`; (b) keep the existing `create(outputFile: File, ...): Result<File>` as a thin delegate for tests/legacy callers.
2. `BackupRestoreViewModel`: register `ActivityResultContracts.CreateDocument("application/octet-stream")` (suggested name `costbackup-<yyyyMMdd-HHmm>.costbackup`); on URI result, run the existing create flow but hand the resolver `contentResolver.openOutputStream(uri)!!` as the destination. (The repo interface `DatabaseBackupRepository.createCostBackup` has defaults and ~6 call sites/tests — prefer the `BackupDestination` sealed type (`Stream(Uri)` / `File(path)`) over changing the parameter type in place.)
3. `DatabaseBackupRepositoryImpl.createCostBackup`: change the destination parameter to `BackupDestination`; final name/journal/verify flow unchanged — the destination is only opened after verification succeeds, exactly where the current code opens the FileOutputStream (`:657+`).
4. `BackupRestoreScreen`: wire the launcher (mirror `ExportOptionsScreen`'s `rememberLauncherForActivityResult`) around the existing backup button (`:174-192`); keep progress/error UI identical.
5. Remove the public-Documents dir construction entirely (no fallback to it). The user picks the location, which is what makes the file retrievable after uninstall.

**What it solves.** Backup creation works on API 26–35 with a user-chosen location; no new permissions.

**Guardrails.**
- Do **not** add `MANAGE_EXTERNAL_STORAGE` (Play policy; unnecessary).
- Privacy gates (`ENCRYPTED_BACKUP`) and maintenance-mode enter/drain run **before** any destination I/O — gates at `DatabaseBackupRepositoryImpl.kt:533-543`, `enterAndDrain` `:545+`, destination `:657+` (note: `:366-420` is `exportDatabase`'s gate block, a different method). Order unchanged.
- Restore input uses `ActivityResultContracts.GetContent()` with `"application/octet-stream"` (`BackupRestoreScreen.kt:55`, `:222`) — do not touch restore.
- Stream must be closed on all paths; partial destination file: SAF-managed (CreateDocument creates an empty target — on failure, delete via `DocumentsContract.deleteDocument` best-effort; log sanitized failure).
- Crossover: `CostbackupBundle` signature change is used by restore's extract path too — the old File overload delegates to the new one, so restore is untouched.
- Safety backups never go through `CostbackupBundle` (plain DB copy at `:2339-2344` into `filesDir/safety_backups`) — unaffected by this change.

**Tests.** `BackupRestoreRoundtripGoldenTest` gains a stream-sink variant (write to ByteArrayOutputStream → read back); ViewModel test: launcher delivers uri → repository called with stream; failure path (sink throws) → destination deleted + generic error, no journal damage.

## P7-005 — Safety backup not fsynced (MED)

**Problem.** `createSafetyBackupInternalAssumingMaintenance` (`:2342-2344`, stream `input.copyTo(output)` — not `File.copyTo`) uses a plain stream copy; the journal write that records `SAFETY_BACKUP_CREATED` **is** fsynced (`RestoreJournal.writeTextSynced:531-540`) → power loss can durably record a backup that is still partially in page cache.

**Fix design.** Copy via explicit streams and sync: `FileInputStream(src).use { input -> FileOutputStream(dst).use { out -> input.copyTo(out); out.fd.sync() } }`; on any exception delete the partial dst before rethrowing. Reuse/extract the `writeTextSynced` sync pattern into a small `FileUtil.syncedCopy(src, dst)` ⟂ (next to `RestoreJournal`'s helper).

**Solves.** Durable journal implies durable safety copy; worst case stays fail-closed CRITICAL instead of restoring a truncated file. **Guardrails:** WAL checkpoint already precedes the copy (`wal_checkpoint(TRUNCATE)`) — keep order. **Tests:** hard to unit-test fsync; assert partial-file deletion on a forced failure (mock stream throwing mid-copy).

## P12-008 — `.enc` envelope has no version/KDF header (MED, forward-compat) — **crossover, land in 3a**

**Problem.** `BackupEncryptionService`: prefix = `salt(16)+IV(12)+ciphertext`; `ITERATION_COUNT = 600_000` compile-time. Any future KDF change bricks every existing encrypted export **and DB backup** (same service).

**Fix design.**
1. New envelope: `"EENC1".toByteArray()` magic (5B) + `kdfAlgoId: Byte` (1 = PBKDF2WithHmacSHA256) + `iterations: Int` (4B BE) + `saltLen: Byte` + salt + `ivLen: Byte` + IV + ciphertext.
2. `decryptStream`: if input starts with the magic → parse params; **else** legacy path (16+12 fixed) — legacy stays readable forever.
3. `encrypt*` always writes the new envelope. Never persist the raw passphrase; params are not secret.
4. Use the same service for both DB-backup encryption and `.enc` exports (it already is) — one change covers P7-007 and P12-008.

**Guardrails.** Wrong password on legacy files must still map to the existing "Incorrect password or corrupt backup" UX (`AEADBadTagException` path). Do not make iterations attacker-visible-configurable at runtime. **Tests:** golden vectors — legacy bytes decrypt via fallback; new-format roundtrip; magic-present-but-truncated → clean failure.

---

## P7-002 — Cancelled asset restore → infinite restart lock (HIGH)

**Problem.** `AppStartupCoordinator.kt:71-78` handles `AssetsIncomplete` by entering RESTART_REQUIRED and returning — before the auto-reset block — on **every** startup; nothing consumes the journaled `assetTasks`, so there is no exit. Reachable without a crash: `restoreCostBackup`'s outer catch rethrows `CancellationException` (`:1106-1108`) *before* any cleanup when the user leaves the screen mid-asset-restore (`viewModelScope`), leaving journal ASSETS_RESTORING + mode ASSETS_RESTORING persisted.

**Fix design (two halves).**
1. **Cancellation half** (`DatabaseBackupRepositoryImpl.kt:1105-1112`): **there is no existing NonCancellable finalize on this path** — `restoreCostBackup` calls `run.start()` directly (`:710`), not `runOperation` (whose NonCancellable CANCELLED finalize at `OperationRunRecorder.kt:135` is the only one in the codebase), and the `:838-867` range is staged-DB Room migration, not finalization. Add a new `catch (e: CancellationException)` around the asset-restore loop that, inside `withContext(NonCancellable)`, writes a **terminal journal event only** (journal/safe sink — the code's own F6 rule at `:985-986` forbids run-handle Room writes after the swap, so do **not** call `run.cancelled()` here; the orphaned run row is reclaimed by stale-run recovery), keeps the journal at ASSETS_RESTORING (resumable), then `exit(forceRestartRequired = true)` (a swapped DB exists — writes must not resume), then rethrows. Today's behavior on CE: inner catch `:1066` rethrows → outer `:1106` rethrows → **no exit, no failJournal** → journal + mode stay ASSETS_RESTORING — the leak this half closes.
2. **Resume half** (`AppStartupCoordinator` AssetsIncomplete branch, `:71-78`): instead of unconditional lock:
   - Load `assetTasks` + `extractTempDirPath` from the journal — `RestoreJournal.readJournal()` (`:482`) already exists; **note** JSON persists `assetTasks[].targetPath` as a **basename only** (`:89`, parsed `:144-145`) — derive final paths fresh during resume (consistent with P7-009's rename-first flow), never from the persisted value.
   - Resume: for tasks still PENDING, copy/rename from tempDir (if missing → mark task FAILED, continue — assets are best-effort per the documented idempotency contract, `:1145-1158`); then re-verify the swapped DB via `RestoreDatabaseOpener`, transition journal → COMPLETE, `restoreMaintenanceMode.exit(forceRestartRequired = true)`, continue startup.
   - If tempDir is gone and tasks are PENDING: still COMPLETE the journal (DB itself is verified good; missing receipt images are the documented best-effort loss) + exit with restart. Do **not** roll back from safety on missing images.
   - Only if the swapped DB **fails** verification → existing CRITICAL path.
3. `MainActivity` RESTART_REQUIRED screen stays as-is (`:397-406`; it now only appears for genuine failures).

**Solves.** No user-reachable permanent lock; asset restore becomes resumable/cancel-safe.

**Guardrails.** Never leave writes enabled against a swapped-but-unverified DB (`forceRestartRequired = true` on every post-swap exit — matches the code's own rationale at `:2269-2271`). Journal transitions must stay single-writer (`synchronized(journalLock)`). **Tests:** `AssetRestoreAtomicityTest` extensions — (a) cancel mid-restore → relaunch resumes tasks, journal COMPLETE, mode NORMAL(+restart flag); (b) cancel + tempDir deleted → completes with FAILED tasks; (c) swapped DB verify-fail → CRITICAL (existing behavior preserved).

## P7-001 — `resetDatabase` crash window → empty DB (MED-HIGH, debug-caller)

**Problem.** `resetDatabase` (`:2356-2454`) journals only PREPARING→(delete live DB + WAL/SHM)→COMPLETE. Crash in the window leaves PREPARING, which `RestoreJournal.checkAndRecover:705-711` classifies non-destructive and deletes → empty DB, NORMAL mode, safety backup unreferenced.

**Fix design.** Mirror `restoreCostBackup`'s state machine: after `createSafetyBackup...` → `transitionTo(SAFETY_BACKUP_CREATED, safetyBackupPath = …)`; before the deletes → `transitionTo(SWAPPING)`; then delete + reopen + `transitionTo(COMPLETE)`. The existing `RecoveredFromSwap` startup branch then restores the safety backup automatically. Additionally gate the debug entry (`DebugViewModel:511`) behind a confirmation dialog (destructive action).

**Guardrails.** No new journal states needed (reuse SWAPPING — its recovery semantics are exactly "restore safety backup"). Debug-only caller keeps severity bounded but fix the state machine regardless. **Tests:** journal-sequence unit test (states observed per step); simulated "crash" (run to SWAPPING, skip COMPLETE, run `checkAndRecover` + startup recovery) → DB restored from safety.

## P7-008 — Post-swap outer catch exits to NORMAL (MED)

**Problem.** `:1105-1112` — any exception escaping the inner handlers after the swap exits maintenance with `forceRestartRequired = false` while the journal says SWAPPING.

**Fix design.** Track `swapped: Boolean` (set right after the swap block); in the outer catch: `failJournal(...)` then `exit(forceRestartRequired = swapped)`. Pre-swap failures keep the current non-restart exit.

**Guardrails.** Don't alter inner handlers' behavior (verification-failure rollback path already handles its own cleanup after P7-006's fix). **Tests:** exception thrown at the `:983` Room rebuild (injectable failing opener) → mode RESTART_REQUIRED, journal failed, staged files cleaned.

## P7-004 — `.pre_restore` never consulted/cleaned (MED)

**Fix design.** (a) In `AppStartupCoordinator` `RecoveredFromSwap` recovery: order of recovery sources = safety backup → **`.pre_restore`** (verify by opening via `RestoreDatabaseOpener` + integrity check before swapping in) → CRITICAL. (b) Delete `.pre_restore` on: successful restore commit (already, `:1029`), swap-failed path (`:962-979`), rollback path (`:1096-1104`), and after it is consumed by recovery. **Solves.** Missed recovery source + DB-sized leak. **Guardrails.** `.pre_restore` is pre-swap state — only valid when the journal says a swap happened; never use it when the live DB is healthy. **Tests:** recovery-unit test with all three sources present/partial combinations.

## P7-006 — Rollback leaks staged DB + WAL/SHM (MED)

**Fix design.** In the verification-failure rollback (`:1096-1103`) and swap-failed (`:962-979`) paths: delete `stagedDbFile`/`-wal`/`-shm` explicitly (extract the success path's `:1030-1032` cleanup into `deleteStagedFiles()`) — place it *before* `failJournal` so an exception in cleanup still lands in a consistent journal state, but delete unconditionally via `runCatching`. **Tests:** rollback unit test asserts zero `import_stage_*` files remain.

## P7-009 — Asset restore commits DB row before file exists (LOW)

**Fix design.** In `restoreReceiptAssets` (`:1248-1268`): perform `tempFile.renameTo(finalFile)` (with existing-dir mkdirs) **first**; on success → `dao.update(receipt.copy(imagePath = finalFile...))`; on failure → delete only `tempFile` and record the task FAILED (do **not** touch the row; keep the old path). **Tests:** `AssetRestoreAtomicityTest` — rename failure leaves DB row untouched.

## P7-010 — `commitJournal` clobbers per-task asset ledger (LOW)

**Fix design.** `restoreReceiptAssets` returns (or mutates via a holder) its `currentJournalEntry.assetTasks`; the outer flow merges them into `journalEntry.copy(assetTasks = merged)` before `transitionTo(COMPLETE)`/`commitJournal` (`:1039-1040`). **Tests:** golden — success journal contains per-task PENDING/COMPLETED/FAILED records.

## P7-011 — `ai_chat_sessions.title` survives anonymization (LOW)

**Fix design.** In `ExportAnonymizer` add a step nulling `UPDATE ai_chat_sessions SET title = NULL` (same style as the `ai_chat_messages` step at `:216-224`); extend the anonymizer unit test's sink list; note `RawStoragePolicyAuditTest`'s tautological coverage (REVAL-3, RP-21) — do not rely on it. **Guardrails.** Retention worker does **not** need this target (titles are low-sensitivity; only the *redacted export* must be clean — matches the P7-011 finding scope).

## P7-P1-05 (tracked) — semantic-equivalence verification
Stays **deferred-by-design** (documented 5-item plan in `BackupVerifier.kt:20-33`). Out of remediation scope; revisit after 3a/3b land.

## Validation & sequencing (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*BackupRestore*" --tests "*RestoreJournal*" --tests "*AssetRestore*" --tests "*CostbackupBundle*" --tests "*BackupEncryption*"
./gradlew :app:testDebugUnitTest --tests "*DatabaseBackupRepositoryImpl*" --tests "*AppStartupCoordinatorRecovery*" --tests "*MaintenanceOperationRunner*"
```
PR order: 3a (save path + envelope) → 3b (state machine) → 3c (smalls). 3a touches `CostbackupBundle`/`BackupEncryptionService` — RP-19 must rebase on it.

## Verification addendum (2026-09-07 re-evaluation)

All 36 concrete claims re-verified against HEAD; corrections above applied (restore uses `GetContent` not `OpenDocument`; privacy-gate ref `:366-420` belongs to `exportDatabase` — createCostBackup's gates are `:533-543`; the stream overload stages its temp ZIP in `cacheDir`; the P7-002 cancellation finalize is **new** code — none exists today, and it must be journal-only per the F6 no-Room-writes-post-swap rule at `:985-986`). Resolved paths: `BackupEncryptionService` → data/privacy/; `ExportAnonymizer` → data/privacy/; `BackupRestoreViewModel`/`Screen` → ui/screens/backup/; `CostbackupBundle`/`RestoreJournal`/`RestoreMaintenanceMode`/`MaintenanceOperationRunner`/`BackupVerifier`/`RestoreDatabaseOpener` → data/backup/; `AiChatSessionEntity` → data/database/entity/. The six cited test files all exist. Wrong-password UX mapping (AEADBadTagException → `WrongBackupPasswordException` → "Incorrect password or corrupt backup file", VM `:205-206`) verified for the P12-008 envelope change.
