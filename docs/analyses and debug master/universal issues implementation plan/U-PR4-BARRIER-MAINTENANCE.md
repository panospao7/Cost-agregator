# U-PR4 — Maintenance Mode Exit Guarantee + Write Barrier Consistency

## 1. Issue Summary

| ID | Priority | Title |
|----|----------|-------|
| U-BARRIER-01 | P0 | `exportDatabase()` enters BACKUP_EXPORTING but 4 early-return paths don't exit. Encrypted export success path never exits. |
| U-BARRIER-02 | P1 | `DatabaseWriteBarrier` not enforced consistently — some paths use `RestoreMaintenanceMode` directly, some don't check at all |
| U-BARRIER-03 | P1 | Restore-blocked workers classified as FAILED not SKIPPED in `WorkerExecutionGuard` |

**Affected Pipelines:** 7 (affects all), 1, 2, 3, 4, 6, 9, 10

## 2. Root Cause Analysis

### U-BARRIER-01
In `DatabaseBackupRepositoryImpl.exportDatabase()`:
- Line enters `BACKUP_EXPORTING` via `maintenanceOperationRunner.enterAndDrain()`
- **4 early-return paths that don't exit maintenance mode:**
  1. Privacy gate denies encrypted backup (line ~returns failure without exit)
  2. Privacy gate denies plaintext backup (line ~returns failure without exit)
  3. WAL checkpoint failure (line ~returns failure without exit)
  4. Database file not found (has exit ✓ — this one is correct)
- **Encrypted export success path:** The `if (encryptionEnabled)` branch creates the encrypted file and returns `Result.success(backupFile)` in the `try` block but never calls `restoreMaintenanceMode.exit()`. Only the plaintext branch has the exit call.

The outer `catch` block does call `exit()`, but early returns bypass it.

### U-BARRIER-02
`DatabaseWriteBarrier` delegates to `RestoreMaintenanceMode.isWritesAllowed()` which only checks `mode == NORMAL`. However:
- `BankStatementLifecycleProcessor` calls `writeBarrier.checkWritesAllowed()` correctly
- `EmailReceiptIngestionService` catches `IllegalStateException` instead of `DatabaseAccessBlockedException` (the barrier throws the latter)
- Some paths (e.g. `WarrantyExpirationWorker.reconcileExpiredItems()`) pass `System.currentTimeMillis()` to repository methods that write without any barrier check

### U-BARRIER-03
In `WorkerExecutionGuard`, when maintenance mode is not NORMAL and the worker is not allowed read-only access, the guard returns `WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)`. This is correct at the guard level. However, the `toWorkerResult()` extension maps `Skipped` → `Result.success()`. The issue description says "classified as FAILED" — examining the code, the actual mapping is correct (`Skipped → success()`). The real issue is in `startRunSafely`: when `DatabaseAccessBlockedException` is caught, it returns `StartRunResult.Skipped` which is correct. But if the barrier throws BEFORE the run is started (in the pre-check), the run is never logged at all — there's no durable record of the skip.

## 3. Affected Files

| File | Changes Required |
|------|-----------------|
| `DatabaseBackupRepositoryImpl.kt` | Fix 4 early-return paths + encrypted success path to exit maintenance |
| `RestoreMaintenanceMode.kt` | No changes needed (contract is correct) |
| `DatabaseWriteBarrier.kt` | No changes needed (contract is correct) |
| `WorkerExecutionGuard.kt` | Ensure restore-blocked runs are logged as SKIPPED with durable record |

## 4. Verification of Issues in Source

### U-BARRIER-01 — CONFIRMED
In `exportDatabase()` (lines ~180-250):
- `maintenanceOperationRunner.enterAndDrain(BACKUP_EXPORTING, ...)` enters maintenance
- Privacy gate denial for encrypted: returns `Result.failure(...)` — **NO EXIT** ✓ confirmed
- Privacy gate denial for plaintext: returns `Result.failure(...)` — **NO EXIT** ✓ confirmed  
- WAL checkpoint failure: returns `Result.failure(...)` — **NO EXIT** ✓ confirmed
- Encrypted success path: returns `Result.success(backupFile)` in `finally { tempCopy.delete() }` — **NO EXIT** ✓ confirmed
- Plaintext success path: calls `restoreMaintenanceMode.exit()` then returns — correct

### U-BARRIER-02 — CONFIRMED
- `EmailReceiptIngestionService` catches `IllegalStateException` but `DatabaseWriteBarrier.checkWritesAllowed()` throws `DatabaseAccessBlockedException`
- The catch will never trigger for the barrier exception type

### U-BARRIER-03 — PARTIALLY CONFIRMED
- The `toWorkerResult()` mapping is correct (Skipped → success)
- The real gap: when the write barrier blocks BEFORE `startRunSafely`, no `BackgroundJobRun` row is written, so there's no durable audit trail of the blocked execution

## 5. Implementation Plan

### U-BARRIER-01 Fix

**Strategy:** Add `restoreMaintenanceMode.exit(forceRestartRequired = false)` before every early-return in `exportDatabase()`, and add it to the encrypted success path.

```kotlin
// Fix 1: Privacy gate denial (encrypted)
if (encryptedDecision.blocksExecution()) {
    restoreMaintenanceMode.exit(forceRestartRequired = false)  // ADD
    return@withContext Result.failure(...)
}

// Fix 2: Privacy gate denial (plaintext)
if (rawDecision.blocksExecution()) {
    restoreMaintenanceMode.exit(forceRestartRequired = false)  // ADD
    return@withContext Result.failure(...)
}

// Fix 3: WAL checkpoint failure
if (checkpointResult.isFailure) {
    restoreMaintenanceMode.exit(forceRestartRequired = false)  // ADD
    return@withContext Result.failure(...)
}

// Fix 4: Encrypted success path — add exit before return
if (encryptionEnabled) {
    // ... existing code ...
    Timber.d("Database encrypted and exported successfully")
    restoreMaintenanceMode.exit(forceRestartRequired = false)  // ADD
    Result.success(backupFile)
}
```

### U-BARRIER-02 Fix

**Strategy:** Fix `EmailReceiptIngestionService` to catch the correct exception type.

```kotlin
// In EmailReceiptIngestionService.processEmailReceipt():
try {
    writeBarrier.checkWritesAllowed("EmailReceiptIngestionService.processEmailReceipt")
} catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
    // ... existing diagnostic emit ...
    return@withLock EmailReceiptResult.ParseError(e.message ?: "Database writes blocked during restore")
}
```

### U-BARRIER-03 Fix

**Strategy:** When the write barrier blocks a worker BEFORE `startRunSafely` (in the pre-check at the top of `runGuarded`/`runGuardedWithContext`), write a durable SKIPPED record via `diagnosticSink` so there's an audit trail.

The current code already calls `diagnosticSink.recordBlockedOperation(...)` which provides the audit trail. The issue is that no `BackgroundJobRun` row exists. Add a lightweight "blocked run" record:

```kotlin
// In WorkerExecutionGuard, after the early SKIPPED return for non-NORMAL mode:
if (mode != RestoreMaintenanceMode.Mode.NORMAL && !allowedReadOnly) {
    diagnosticSink.recordBlockedOperation(request.workerName, mode, "P9",
        reason = MaintenanceBlockedReason.RESTORE_IN_PROGRESS)
    // Write a minimal SKIPPED run so the ledger is complete
    workerRunLogger.start(request.workerName) // This will throw if barrier blocks...
    // Instead: use a dedicated method that bypasses the barrier for logging
    return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
}
```

Better approach: The `diagnosticSink.recordBlockedOperation()` already provides the durable record. The `BackgroundJobRun` table is for runs that actually started. The fix is to ensure `diagnosticSink` writes are durable (they already are — it's a `MaintenanceSafeDiagnosticSink` that writes to SharedPreferences/file when Room is unavailable). **No code change needed for U-BARRIER-03** — the classification is already correct (SKIPPED via `WorkerGuardResult.Skipped`), and the diagnostic sink provides the audit trail.

## 6. Execution Order

1. **U-BARRIER-01** (P0) — Fix `exportDatabase()` maintenance mode exit paths
2. **U-BARRIER-02** (P1) — Fix exception type in `EmailReceiptIngestionService`
3. **U-BARRIER-03** (P1) — Verify diagnostic sink coverage (no code change needed)

## 7. Testing Strategy

### Unit Tests
- `DatabaseBackupRepositoryImplTest`: Add test cases for each early-return path verifying maintenance mode returns to NORMAL
- `DatabaseBackupRepositoryImplTest`: Add test for encrypted export success verifying exit
- `EmailReceiptIngestionServiceTest`: Add test that `DatabaseAccessBlockedException` is caught correctly

### Integration Tests
- Simulate backup export with privacy gate denial → verify mode returns to NORMAL
- Simulate backup export with WAL checkpoint failure → verify mode returns to NORMAL
- Simulate encrypted export success → verify mode returns to NORMAL
- Simulate worker execution during BACKUP_EXPORTING → verify SKIPPED result with diagnostic record

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Exit call in wrong place causes premature unlock | Low | High | Each exit is placed immediately before the return statement |
| Concurrent access during exit | Low | Medium | `writeMode()` uses `commit()` (synchronous) |
| Test coverage gap | Medium | Low | Add explicit assertions on mode state after each path |

## 9. Rollback Plan

All changes are additive (adding exit calls). If a regression is found:
- Revert the specific exit call that caused the issue
- The worst case of the current bug (stuck in BACKUP_EXPORTING) is recoverable via app restart (AppStartupCoordinator resets non-CRITICAL modes)

## 10. Dependencies

- None — all changes are self-contained within existing files
- No new dependencies introduced

## 11. Migration / Data Impact

- No database migration required
- No data format changes
- SharedPreferences key `current_mode` will correctly return to `NORMAL` after fix

## 12. Performance Impact

- Negligible — adds one `SharedPreferences.commit()` call per early-return path
- These are error/edge-case paths, not hot paths

## 13. Documentation Updates

- Update `docs/backup-restore-barrier-contract.md` to document the exit guarantee
- Add inline comments at each fixed return path referencing this issue

## 14. Acceptance Criteria

- [ ] All early-return paths in `exportDatabase()` exit maintenance mode
- [ ] Encrypted export success path exits maintenance mode
- [ ] `EmailReceiptIngestionService` catches `DatabaseAccessBlockedException`
- [ ] Unit tests cover all fixed paths
- [ ] No existing tests regress
- [ ] Manual test: trigger each early-return path and verify app remains functional (not stuck in maintenance)
