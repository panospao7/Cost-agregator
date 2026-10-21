# B6-22 map + decisions (scout 2026-10-20 on HEAD 945afffc; scout had no git → writer runs `git diff 8671b844 945afffc --` on DatabaseWriteBarrier.kt, DatabaseReadBarrier.kt and both test files first; keep every release-added case)

T = app/src/test/java/com/yourname/expensetracker/data/backup. Review: reviewer-strict (barrier semantics). Validation: targeted `com.yourname.expensetracker.data.backup.*Barrier*` + the new Robolectric class; plus one `legacy-tests` run (HANG-003 filter must still match).

## Facts
- DatabaseBarrierTest 21 (class-level Robolectric sdk 28; only :89 `persisted_resume_pending_blocks_current_and_fresh_write_barriers` needs it — real SharedPreferences + noBackupFilesDir sentinel + 2 real RestoreMaintenanceMode, try/finally cleanup :108-111).
- ExportReadBarrierTest 10, plain JVM. Policy cases :36/:62/:71/:80 duplicate DatabaseBarrierTest :180/:168/:174/:149; :49 and :93 unique; :110/:118/:130/:142 are guardedDatabaseRead / blockedDuringRestore Flow-helper tests (F-16 UncompletedCoroutinesError history).
- config/validation/known-hanging-tests.json HANG-003 filter = `com.yourname.expensetracker.data.backup.ExportReadBarrierTest` (legacy-tests profile; Gradle fails on a no-match filter).

## Decisions (DO; no config change)
1. KEEP class ExportReadBarrierTest (name + package) containing the 4 Flow-helper tests unchanged → HANG-003 filter still valid, no known-hanging-tests.json edit, no ledger/doc churn. Move its policy cases out: delete :36/:62/:71/:80 (survivors above, confirm by reading) and fold :49 and :93 into the DatabaseBarrierTest read table.
2. DatabaseBarrierTest: write table over Mode.entries (allowed only NORMAL; else throws with accessType == WRITE and ex.mode == mode) replacing the per-mode write tests; read table over {NORMAL_APP_READ, EXPORT_OR_BACKUP_SNAPSHOT_READ, RESTORE_INTERNAL_STAGED_DB_READ} × Mode.entries with the allow-set DERIVED from DatabaseReadBarrier.kt (not guessed), asserting accessType == READ and ex.mode on every blocked row. Row names in messages. Keep runWrite (:124/:131), string overloads (:205-:223), metadata (:227).
3. Move :89 into new T/RestoreMaintenancePersistedBarrierRobolectricTest.kt (Robolectric sdk 28, same body + cleanup); remove the Robolectric runner from DatabaseBarrierTest only if nothing else in it needs Android (verify).
4. Docs: none required (class names kept). TEST_FAILURE_LEDGER untouched.
Expected: 31 → ~17 methods, no assertion weakened; one class leaves Robolectric.
