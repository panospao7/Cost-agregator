# Batch 18: Database & Repository fixes (8 issues)

> **Last updated:** April 5, 2026 — expanded after deep code review  
> **Review reference:** `docs/quality/REVIEW-database-repository-subsystem.md` (score: 61/100)

## Technical Plan (Advanced)

### Scope
- In:
  - **ISSUE-1 [CRITICAL]**: FK validation must occur **before** transaction commit for table-rebuild migrations in `AppDatabase.kt` (`MIGRATION_48_49`, `MIGRATION_49_50`, `MIGRATION_65_66`).
  - **ISSUE-2 [HIGH]**: rollback restore must re-quiesce Room/openHelper before writing DB files in `DatabaseBackupRepositoryImpl.kt`.
  - **ISSUE-3 [MAJOR]**: remove heavy split parsing CPU work from `withTransaction` in `GroupsRepositoryImpl.deleteMember`.
  - **ISSUE-4 [MAJOR]**: replace non-deterministic offset pagination for NL expense query with deterministic paging strategy.
  - **ISSUE-5 [HIGH] (NEW)**: `permanentlyDeleteGroup()` in `GroupTransactionCoordinator.kt` performs 3 sequential deletes without a DB transaction — must wrap in `withTransaction` or delegate to existing `deleteGroupAtomic()`.
  - **ISSUE-6 [MEDIUM] (NEW)**: `GroupTransactionCoordinator`, `GroupsRepositoryImpl`, and `DatabaseBackupRepositoryImpl` use hardcoded `Dispatchers.IO` instead of injected `@IoDispatcher` — violates project dispatcher pattern and breaks testability.
  - **ISSUE-7 [MEDIUM] (NEW)**: `ExpenseRepository.updateExpenseCategory` performs 3 writes under mutex but without a DB transaction — crash between writes corrupts ML learning ground truth.
  - **ISSUE-8 [MEDIUM] (NEW)**: `ReceiptRepository.processReceipt` inserts receipt and pending review in separate non-atomic operations — crash between inserts orphans the receipt.
  - Focused regression tests (migration, repository import rollback, groups deletion behavior, NL pagination stability, transaction atomicity).
- Out:
  - Schema redesign beyond the targeted migrations.
  - Backup format changes (zip/encryption/manifest).
  - Broad groups domain rewrite.
  - Unrelated analytics/search features.
  - Architecture violations (Context/R.string in data layer) — tracked separately.
  - Dashboard over-fetching (`DashboardContractsAdapter`) — tracked in analytics batch.

### Complexity Assessment
- Estimated files touched: **12–16**
  - Production likely: `AppDatabase.kt`, `DatabaseBackupRepositoryImpl.kt`, `GroupsRepositoryImpl.kt`, `GroupTransactionCoordinator.kt`, `GroupExpenseDao.kt` (optional), `ExpenseDao.kt`, `NaturalLanguageExpenseQueryRepositoryImpl.kt`, `ExpenseRepository.kt`, `ReceiptRepository.kt`.
  - Tests likely: `DatabaseMigrationTest.kt` + new focused tests under `androidTest`/`test`.
- Risk level: **high** (critical data integrity + restore paths)
- Cross-module impact: **yes**

### Batch Plan

1. Batch name: **ISSUE-1 — Pre-commit FK validation for rebuild migrations**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
     - `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt` (extend)
   - objective:
     - Ensure FK violations fail migration **before commit**, so Room can roll back table-rebuild changes.
   - risks:
     - Migration behavior differences across SQLite/Android API levels.
     - Over-correcting pattern in unrelated migrations.
   - validation:
     - Migration tests verify valid datasets pass and invalid FK datasets fail without leaving partially committed schema changes.

   #### 1) Root Cause Analysis
   - **Location:**
     - `AppDatabase.kt:1628-1637` (`MIGRATION_48_49`)
     - `AppDatabase.kt` (`MIGRATION_49_50`) — same pattern, multi-table rebuild
     - `AppDatabase.kt:3001-3010` (`MIGRATION_65_66`)
   - **Why it happens:**
     - All three migrations call `setTransactionSuccessful()` then `endTransaction()` first, and only after that run `PRAGMA foreign_key_check` in the outer `finally` block.
     - If FK violations are detected, exception is thrown **post-commit**, so the rebuilt tables are already committed.
   - **Confirmed:** `MIGRATION_67_68` uses a `repairTable` helper that validates inside the transaction — the correct pattern already exists in the codebase but was not backported to these earlier migrations.
   - **Impact:**
     - Migration can fail while database is left in a partially-accepted state.
     - No rollback path exists once transaction is committed.
     - On next app launch, Room sees the new schema version but data is corrupt — effectively bricking the upgrade path on dirty legacy data.

   #### 2) Implementation Strategy
   1. In all three migrations (48→49, 49→50, 65→66), move `PRAGMA foreign_key_check` into the transaction block **before** `setTransactionSuccessful()`.
   2. Use table-scoped FK check (`PRAGMA foreign_key_check(table_name)`) for faster and targeted validation.
   3. Keep `foreign_keys=OFF` only for rebuild operations; keep `foreign_keys=ON` restoration in `finally` after transaction end.
   4. On FK violation, throw immediately so transaction ends without `setTransactionSuccessful` (rollback semantics preserved).
   5. Extract a shared `validateForeignKeysInTransaction(database, tableName)` helper following the pattern already used by `MIGRATION_67_68`'s `repairTable` to prevent future regressions.

   **Illustrative sequencing (not implementation code):**
   ```kotlin
   database.execSQL("PRAGMA foreign_keys=OFF")
   try {
       beginTransaction()
       try {
           rebuildTable()
           // Validate BEFORE marking success
           checkForeignKeys("scanned_receipts") // throws => rollback
           setTransactionSuccessful()
       } finally {
           endTransaction()
       }
   } finally {
       database.execSQL("PRAGMA foreign_keys=ON")
   }
   ```

   #### 3) Dependencies
   - No hard dependency on other Batch 18 issues.
   - Should be delivered before any future migration additions that copy this rebuild pattern.

   #### 4) Risk Assessment
   - **What could go wrong:**
     - `foreign_key_check` inside transaction may surface latent data inconsistencies earlier than before (devices with orphaned FK rows will now fail migration instead of silently continuing).
   - **Mitigation:**
     - Add positive/negative migration tests.
     - Keep scope limited to the three cited migrations.
     - Consider a recovery migration (68+) that cleans orphaned FK rows proactively.

   #### 5) Verification Plan
   - **Automated (androidTest):**
     - Extend migration tests around 48→49, 49→50, and 65→66:
       - valid fixture: no FK violations after migration.
       - invalid fixture: expect migration failure path and confirm no silently committed invalid state.
   - **Manual:**
     - Install old DB fixture and run app upgrade path; verify app opens and data is intact.

   #### 6) Estimated Effort
   - **Medium**

---

2. Batch name: **ISSUE-2 — Safe rollback restore with Room re-close before file write**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
     - New/extended import safety tests (likely `androidTest`)
   - objective:
     - Prevent rollback copy from silently failing due to active Room/openHelper handles.
   - risks:
     - File-lock/rename behavior varies by device/OS.
     - Error-handling complexity can introduce regressions in successful import path.
   - validation:
     - Forced import failure after swap must restore original DB reliably and reopen app DB cleanly.
   - **severity revised:** HIGH (not CRITICAL — the risk window is narrow, only triggered when import verification fails after Room reopen at line 220)

   #### 1) Root Cause Analysis
   - **Location:**
     - reopen after swap: `DatabaseBackupRepositoryImpl.kt:220`
     - rollback trigger path: `:250-256`
     - rollback writer: `:500-542`
   - **Why it happens:**
     - Import flow may reopen DB (`openHelper.writableDatabase`) for verification.
     - If verification fails afterward, `restoreFromSafetyBackup` writes DB files without first re-closing Room/openHelper.
     - File deletes/copies do not assert success strongly; this can mask partial restore.
   - **Impact:**
     - Rollback may report success while active DB files are not fully replaced.
     - Potential silent corruption/degraded open state.

   #### 2) Implementation Strategy
   1. Introduce a single internal **quiesce** step (close Room + openHelper) used:
      - before initial swap,
      - and again immediately before rollback restore writes.
   2. In rollback, enforce write operations with explicit success checks (delete/copy/rename outcomes); fail fast on any mismatch.
   3. Reopen DB only after restore completes successfully; then refresh invalidation tracker.
   4. Improve phase-specific telemetry/error messages (`swap_failed`, `rollback_started`, `rollback_reopen_failed`) for supportability.
   5. Preserve existing safety-backup fallback semantics; do not weaken current user-facing failure contract.

   **Illustrative sequencing (not implementation code):**
   ```text
   import failure after swap
   -> quiesce handles again
   -> restore files from safety backup (strict checks)
   -> reopen DB
   -> refresh invalidation
   ```

   #### 3) Dependencies
   - Independent from ISSUE-1/3/4.
   - Builds on existing import safety flow already present in repository.

   #### 4) Risk Assessment
   - **What could go wrong:**
     - Additional close/reopen may fail on edge devices.
     - Stricter file operation checks may increase explicit failures initially.
   - **Mitigation:**
     - Retry-with-backoff for close/reopen boundaries (small bounded attempts).
     - Ensure failure messages clearly distinguish import failure vs rollback failure.

   #### 5) Verification Plan
   - **Automated (androidTest preferred):**
     1. Seed DB with sentinel row.
     2. Trigger import path that fails post-swap verification.
     3. Assert rollback result preserves sentinel row.
     4. Assert database can reopen and queries execute.
   - **Manual:**
     - Debug import of intentionally incompatible backup; confirm error indicates rollback outcome and app remains usable.

   #### 6) Estimated Effort
   - **High**

---

3. Batch name: **ISSUE-3 — Move heavy split parsing out of transaction in member deletion**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
     - (optional) `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt` for candidate prefilter query
     - New tests for delete-member race/perf-sensitive behavior
   - objective:
     - Reduce DB lock time by removing O(N) custom split parsing from `withTransaction`.
   - risks:
     - TOCTOU window if validation is moved outside transaction without final guard.
     - Behavior drift in edge malformed split payloads.
   - validation:
     - `deleteMember` still returns same domain outcomes, with transaction containing only DB-critical operations.

   #### 1) Root Cause Analysis
   - **Location:** `GroupsRepositoryImpl.kt:133-149` (transaction), and `:171-200` (`countSplitReferences`).
   - **Why it happens:**
     - `countSplitReferences` fetches members/expenses and runs parse/validate per expense (`CustomSplitParser.parseAndValidate`) inside transaction.
     - This includes non-DB CPU loops and parsing while transaction lock is held.
   - **Impact:**
     - Longer lock duration and contention.
     - Increased risk of slow operations under large groups/expense histories.

   #### 2) Implementation Strategy
   1. Split delete flow into **preflight** and **commit** phases:
      - Preflight (outside transaction): compute split-reference count from fetched payloads.
      - Commit phase (inside transaction): re-check critical DB invariants and perform delete.
   2. Keep transaction body DB-focused (member existence, group ownership, payer FK-safe count, delete).
   3. Add a lightweight final guard inside transaction to reduce TOCTOU risk (e.g., candidate split-reference check scoped to affected group/member).
   4. Preserve current result mapping (`CannotDeleteMemberWithExpenses`, `CannotDeleteMemberReferencedInSplits`, `Error`, `Success`).

   **Illustrative decomposition (not implementation code):**
   ```text
   preflight: fetch + parse outside txn
   if blocked -> return
   withTransaction {
     re-check critical counts
     delete member
   }
   ```

   #### 3) Dependencies
   - Independent from ISSUE-1/2/4.
   - Optional DAO helper query adds minor dependency within same batch.

   #### 4) Risk Assessment
   - **What could go wrong:**
     - Race: new group expense added between preflight and commit.
   - **Mitigation:**
     - Keep transactional re-check for critical predicates.
     - Prefer conservative failure when post-preflight state changed.

   #### 5) Verification Plan
   - **Automated (unit/integration):**
     - Case A: member referenced as payer -> blocked.
     - Case B: member referenced only in custom split -> blocked.
     - Case C: no references -> success.
     - Regression: behavior on malformed `customSplitsJson` matches prior semantics.
   - **Manual:**
     - Delete member from group with large history; verify no UI stalls and correct error/success outcomes.

   #### 6) Estimated Effort
   - **Medium**

---

4. Batch name: **ISSUE-4 — Deterministic NL pagination (remove offset instability risk)**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
     - Tests for stable page iteration with same-date rows
   - objective:
     - Ensure paged retrieval cannot skip/duplicate rows due unstable ordering.
   - risks:
     - Query contract changes can impact callers if shared DAO method is modified directly.
   - validation:
     - Pagination returns full, unique, deterministic set across large datasets with identical timestamps.

   #### 1) Root Cause Analysis
   - **Location:**
     - looped offset paging in `NaturalLanguageExpenseQueryRepositoryImpl.kt:23-29, 45-50`
     - upstream DAO order in `ExpenseDao.kt:399` (`ORDER BY date DESC` only)
   - **Why it happens:**
     - Offset paging assumes stable total ordering.
     - Ordering by `date` alone is non-deterministic for ties, so page boundaries can drift.
   - **Impact:**
     - Potential missing or duplicated expenses in NL query corpus.
     - Inconsistent NLP results and analytics derived from this list.

   #### 2) Implementation Strategy
   1. Prefer dedicated NL query paging method in DAO using deterministic order (`date DESC, id DESC`).
   2. Replace offset loop with **keyset pagination** cursor (`lastDate`, `lastId`) to avoid offset drift.
   3. Keep existing generic DAO methods intact unless low-risk to update globally.
   4. Add defensive loop guard (break if cursor does not advance).

   **Illustrative query shape (not implementation code):**
   ```sql
   WHERE date in range
     AND (date < :lastDate OR (date = :lastDate AND id < :lastId))
   ORDER BY date DESC, id DESC
   LIMIT :pageSize
   ```

   #### 3) Dependencies
   - No dependency on other Batch 18 issues.
   - Internal dependency on DAO contract addition/update.

   #### 4) Risk Assessment
   - **What could go wrong:**
     - Cursor boundary bug can cause infinite loop or dropped tail rows.
   - **Mitigation:**
     - Add explicit monotonic cursor assertions in tests.
     - Keep PAGE_SIZE constant and terminate on empty page.

   #### 5) Verification Plan
   - **Automated:**
     - Insert >1 page of expenses sharing identical date values.
     - Assert final list count equals source count and IDs are unique.
     - Assert deterministic order across repeated runs.
   - **Manual:**
     - Run NL search queries on large historical dataset and compare before/after row counts.

   #### 6) Estimated Effort
   - **Medium**

---

5. Batch name: **ISSUE-5 — Wrap `permanentlyDeleteGroup` in database transaction**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
   - objective:
     - Ensure the 3-step delete (expenses → members → group) is atomic — crash between steps must not leave orphaned records.
   - risks:
     - Minimal — the class already has `deleteGroupAtomic()` with the correct pattern.
   - validation:
     - Interrupted deletion does not leave partial state.

   #### 1) Root Cause Analysis
   - **Location:** `GroupTransactionCoordinator.kt:237-255`
   - **Why it happens:**
     - `permanentlyDeleteGroup()` performs three sequential DAO calls without `database.withTransaction`:
       ```kotlin
       groupExpenseDao.deleteAllForGroup(groupId)   // Step 1
       memberDao.deleteAllForGroup(groupId)          // Step 2
       groupDao.delete(group)                        // Step 3
       ```
     - The same class already has `deleteGroupAtomic()` at line 313 that does the exact same thing inside `database.withTransaction`, but the public interface method doesn't delegate to it.
   - **Impact:**
     - Crash or cancellation between steps leaves orphaned members or an empty group shell.
     - FK integrity depends on delete ordering being perfect — fragile.

   #### 2) Implementation Strategy
   1. **Option A (preferred):** Delegate `permanentlyDeleteGroup()` to `deleteGroupAtomic()` — zero new code, just a one-line change.
   2. **Option B:** Wrap the existing body in `database.withTransaction { ... }`.
   3. Either way, preserve the `try/catch` returning `Boolean` contract.

   #### 3) Dependencies
   - Independent from other issues. Combine with ISSUE-6 since both touch `GroupTransactionCoordinator.kt`.

   #### 4) Risk Assessment
   - **Very low.** The atomic variant already exists and is tested via other code paths.

   #### 5) Verification Plan
   - **Automated:** Existing group deletion tests should continue passing.
   - **Manual:** Delete group via UI, verify no orphaned rows in DB inspector.

   #### 6) Estimated Effort
   - **Low** (5-minute fix)

---

6. Batch name: **ISSUE-6 — Inject dispatchers in GroupTransactionCoordinator, GroupsRepositoryImpl, DatabaseBackupRepositoryImpl**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
     - `app/src/main/java/com/yourname/expensetracker/di/GroupsModule.kt` (update provider if needed)
   - objective:
     - Replace hardcoded `Dispatchers.IO` with injected `@IoDispatcher` to match project convention and enable test dispatcher override.
   - risks:
     - Constructor signature changes may require DI module updates.
   - validation:
     - All existing tests pass with injected dispatcher; new tests can use `TestCoroutineDispatcher`.

   #### 1) Root Cause Analysis
   - **Location:** `GroupTransactionCoordinator.kt:51,86,119,176,224,237`, `GroupsRepositoryImpl.kt:35`, `DatabaseBackupRepositoryImpl.kt:41,133,444,549,568,640`
   - **Why it happens:** These classes were written before the `@IoDispatcher` convention was established, or were not updated during the dispatcher refactoring pass.
   - **Impact:** Cannot override dispatcher in unit tests. Inconsistent with the rest of the codebase.

   #### 2) Implementation Strategy
   1. Add `@IoDispatcher private val ioDispatcher: CoroutineDispatcher` to each constructor.
   2. Replace `withContext(Dispatchers.IO)` with `withContext(ioDispatcher)`.
   3. Update DI modules if they explicitly construct these classes (GroupsModule provides GroupsRepository).

   #### 3) Dependencies
   - Independent from other issues. Can be combined with ISSUE-5 since both touch `GroupTransactionCoordinator.kt`.

   #### 4) Risk Assessment
   - **Low.** Mechanical refactoring with no behavioral change.

   #### 5) Verification Plan
   - **Automated:** Existing tests pass. Add one test using `StandardTestDispatcher` to confirm overridability.

   #### 6) Estimated Effort
   - **Low-Medium**

---

7. Batch name: **ISSUE-7 — Wrap `updateExpenseCategory` in database transaction**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
   - objective:
     - Ensure the 3-write sequence (update category + learn pattern + insert correction) is atomic.
   - risks:
     - `merchantCategoryRepository.learnPattern` may internally start its own transaction — verify it's Room-compatible (nested transactions are fine with `withTransaction`).
   - validation:
     - Crash between writes does not leave inconsistent category ↔ correction state.

   #### 1) Root Cause Analysis
   - **Location:** `ExpenseRepository.kt:209-232`
   - **Why it happens:**
     - The method uses a `Mutex` (which prevents concurrent calls) but not a DB transaction (which prevents partial writes on crash).
     - Three separate writes: `expenseDao.updateCategory`, `merchantCategoryRepository.learnPattern`, `userCorrectionDao.insert`.
     - Compare with `updateExpenseMerchant(applyToAll=true)` at line 288 which correctly uses `database.withTransaction`.
   - **Impact:**
     - Crash after write 1 but before write 3: expense re-categorized but no correction record → ML pipeline has corrupted ground truth.

   #### 2) Implementation Strategy
   1. Wrap the body of the `categoryUpdateMutex.withLock` block in `database.withTransaction { ... }`.
   2. Verify `merchantCategoryRepository.learnPattern` is safe inside a Room transaction (it should be — Room supports nested transactions).
   3. Apply same fix to `updateExpenseCategoryBulk` at line 244 for consistency.

   #### 3) Dependencies
   - Independent.

   #### 4) Risk Assessment
   - **Low.** Adding a transaction wrapper around existing sequential writes.

   #### 5) Verification Plan
   - **Automated:** Extend existing category-update tests to verify correction record is always created atomically with the category change.

   #### 6) Estimated Effort
   - **Low** (10-minute fix)

---

8. Batch name: **ISSUE-8 — Make receipt insert + pending review creation atomic**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
   - objective:
     - Ensure receipt + pending review are created atomically when `autoCreateReview=true`.
   - risks:
     - Warranty extraction currently runs between receipt insert and review insert. It must remain outside the transaction (best-effort).
   - validation:
     - Receipt insert + review insert either both succeed or both roll back.

   #### 1) Root Cause Analysis
   - **Location:** `ReceiptRepository.kt:133-178`
   - **Why it happens:**
     - `processReceipt` inserts the receipt (line 133), runs warranty extraction (line 137), then optionally inserts a pending review (line 178). These are three separate DB operations.
     - If the process crashes after step 1 but before step 3, the receipt exists but no review is created. The receipt is orphaned with no review path.
   - **Impact:**
     - Orphaned receipt that user cannot act on via the review queue.

   #### 2) Implementation Strategy
   1. Group receipt insert + review insert inside `database.withTransaction { ... }`.
   2. Move warranty extraction outside (after) the transaction — it's best-effort and already has its own try/catch.
   3. Return the receiptId from the transaction block for the warranty use case.

   **Illustrative sequencing:**
   ```kotlin
   val receiptId = database.withTransaction {
       val rid = scannedReceiptDao.insert(receipt)
       if (autoCreateReview) {
           pendingReviewDao.insert(buildReview(rid, ...))
       }
       rid
   }
   // Best-effort warranty extraction outside transaction
   warrantyUseCase.execute(receiptId, ocrResult.fullText)
   ```

   #### 3) Dependencies
   - Independent.

   #### 4) Risk Assessment
   - **Low-Medium.** Transaction wrapping is straightforward. Main risk is ensuring warranty extraction still receives the correct `receiptId` after the transaction.

   #### 5) Verification Plan
   - **Automated:** Test that both receipt and review exist after successful `processReceipt(autoCreateReview=true)`.
   - **Manual:** Process a receipt via batch scan; verify review appears in queue.

   #### 6) Estimated Effort
   - **Low-Medium**

---

### Additional Issues Tracked (not in this batch)

The following issues were identified during review but are tracked in other batches or deferred:

| ID | Severity | Type | Location | Tracking |
|----|----------|------|----------|----------|
| ISSUE-9 | MEDIUM | Performance | `DashboardContractsAdapter.kt:48-49` — over-fetches 500 expenses regardless of dashboard period | Analytics batch |
| ISSUE-10 | LOW | Transaction | `ReceiptRepository.kt:453-646` — non-DB dedup computation inside `withTransaction` in `processStatement` | Future optimization |
| ISSUE-11 | LOW | Architecture | `DatabaseBackupRepositoryImpl.kt` — WAL checkpoint logic duplicated in `exportDatabase` and `createSafetyBackup` | Future refactor |
| ISSUE-12 | MEDIUM | Architecture | `ReviewQueueRepository.kt` — imports `android.content.Context` and `R.string` (layer violation) | Architecture batch |
| ISSUE-13 | MEDIUM | Architecture | `ReceiptRepository.kt` — injects `@ApplicationContext Context` for `DebugIssueDetector` (layer violation) | Architecture batch |
| ISSUE-14 | LOW | Security | `ExpenseRepository.kt:92-202` — `sortOrder.sql` interpolated in query string (safe via closed enum, but fragile pattern) | Future hardening |

---

### Dependencies
- Recommended execution order:
  1. **ISSUE-1** (migration safety; critical integrity) — P0
  2. **ISSUE-5** (permanentlyDeleteGroup atomicity; quick win) — P0
  3. **ISSUE-2** (rollback reliability; high recovery) — P1
  4. **ISSUE-4** and **ISSUE-3** (major correctness/perf) in parallel if staffing allows — P1
  5. **ISSUE-7** and **ISSUE-8** (transaction wrapping; quick fixes) — P1, can be combined
  6. **ISSUE-6** (dispatcher injection; testability) — P2, combine with ISSUE-5 since both touch GroupTransactionCoordinator
- Cross-issue dependencies:
  - ISSUE-5 and ISSUE-6 both modify `GroupTransactionCoordinator.kt` — combine in one commit.
  - ISSUE-3 and ISSUE-5 both touch the Groups subsystem — coordinate to avoid conflicts.
  - All others are independent and can be merged separately.
  - If batching in one PR, keep commits isolated per issue for safer revert.

### Rollback / Safety
- Ship each issue in separate commit/PR slice.
- For critical/high issues (1, 2, 5), gate merge on androidTest pass and one manual device verification.
- Keep behavior-preserving fallback paths:
  - ISSUE-2: if strict rollback fails, maintain explicit "manual recovery required" failure signal.
  - ISSUE-4: isolate DAO method for NL path to avoid accidental global behavior changes.
- Post-merge monitoring:
  - Add temporary logs/metrics for migration FK failures, rollback attempts/success, and NL pagination anomalies (duplicate IDs detected in-memory sanity check during development).

### Acceptance Criteria
- [ ] `MIGRATION_48_49`, `MIGRATION_49_50`, and `MIGRATION_65_66` perform FK validation before commit; FK violation triggers rollback semantics.
- [ ] Migration tests cover valid and FK-invalid fixtures for the affected migrations.
- [ ] Rollback restore path re-closes Room/openHelper before writing files and verifies file operations strictly.
- [ ] Import failure after swap restores prior DB and app DB reopens successfully.
- [ ] `deleteMember` no longer performs heavy split parsing work inside transaction; domain outcomes remain unchanged.
- [ ] NL expense paging is deterministic and cannot skip/duplicate rows under tie-heavy date data.
- [ ] `permanentlyDeleteGroup` is wrapped in a database transaction (or delegates to `deleteGroupAtomic`).
- [ ] `GroupTransactionCoordinator`, `GroupsRepositoryImpl`, and `DatabaseBackupRepositoryImpl` use injected `@IoDispatcher` instead of hardcoded `Dispatchers.IO`.
- [ ] `ExpenseRepository.updateExpenseCategory` wraps all three writes in `database.withTransaction`.
- [ ] `ReceiptRepository.processReceipt` wraps receipt insert + review insert in a single transaction; warranty extraction remains best-effort outside.
- [ ] New/updated tests for all eight issues pass in CI.

### Effort Summary

| Issue | Severity | Effort | Risk |
|-------|----------|--------|------|
| ISSUE-1 | CRITICAL | Medium | High (migration paths) |
| ISSUE-2 | HIGH | High | Medium (device variance) |
| ISSUE-3 | MAJOR | Medium | Medium (TOCTOU) |
| ISSUE-4 | MAJOR | Medium | Low-Medium |
| ISSUE-5 | HIGH | Low | Very Low |
| ISSUE-6 | MEDIUM | Low-Medium | Low |
| ISSUE-7 | MEDIUM | Low | Low |
| ISSUE-8 | MEDIUM | Low-Medium | Low |
| **Total** | | **~3-4 dev-days** | |
