# Batch 14: Database & Repository Fixes (C2, M2, M3)

## Technical Plan (Advanced)

### Scope
- In:
  - `C2` rollback-safety hardening for database import file replacement flow in `DatabaseBackupRepositoryImpl.kt` (`~182-244`).
  - `M2` source schema version guardrails in `DatabaseBackupRepositoryImpl.kt` (`~272-283`).
  - `M3` source stats durability for `parsed == null` branch in `NotificationProcessingPipeline.kt` (`~160-186`).
  - Focused automated tests (repository import safety + pipeline stats behavior).
- Out:
  - UI/UX redesign of import/export debug screen.
  - Broad backup format redesign (zip/encryption/multi-file manifest).
  - Non-related parser/routing logic changes.

### Complexity Assessment
- Estimated files touched: **5–8**
  - Production: `DatabaseBackupRepositoryImpl.kt`, `NotificationProcessingPipeline.kt` (and optionally a schema-version constant source, if centralized).
  - Tests: new/updated tests for import safety and parsed-null stats behavior.
  - Docs: this plan file.
- Risk level: **high** (C2), **medium** overall.
- Cross-module impact: **yes** (backup/import safety, Room reopen behavior, source trust statistics).

---

### Batch Plan

1. **Batch name: C2 — Rollback-safe import after file swap**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
     - Tests (new): `app/src/androidTest/.../data/repository/DatabaseBackupRepositoryImplImportSafetyTest.kt` *(name suggestion)*
   - objective:
     - Ensure any failure after start of file replacement restores the pre-import DB automatically.
   - risks:
     - Partial swap failure can leave DB unavailable.
     - Rollback failure could still leave app in degraded state.
     - File-lock timing issues around Room close/reopen.
   - validation:
     - Instrumented scenario that triggers post-swap verification failure and confirms old DB content is restored.

   #### Root Cause Analysis
   - **Exact location:** `DatabaseBackupRepositoryImpl.kt:182-244`.
   - Current sequence:
     1) copy source -> temp,
     2) delete active DB/WAL/SHM,
     3) rename temp -> live DB,
     4) verify and return.
   - If verification/opening fails after step 3, method returns failure, but **no restore path runs**.
   - Impact:
     - Import may fail while leaving the active DB replaced by incompatible/corrupt file.
     - Safety backup exists, but is not auto-used for rollback.
     - User can end in a broken state despite receiving an error.

   #### Implementation Strategy
   1. **Introduce staged swap with explicit rollback candidate**
      - Keep existing source pre-validation first.
      - Create two transient files in DB dir:
        - `incoming` (copied source)
        - `rollback` (renamed current DB snapshot)
   2. **Perform reversible swap sequence**
      - Close Room/openHelper as currently done.
      - Rename current DB -> `rollback` (instead of deleting first).
      - Rename `incoming` -> live DB.
      - Only after successful verification, delete `rollback` and stale WAL/SHM artifacts.
   3. **Add structured failure handling by phase**
      - If failure before swap: no rollback needed.
      - If failure after current DB renamed but before successful verification:
        - Attempt restore: remove bad live DB if needed, rename `rollback` back to live DB.
        - Reopen DB handle and emit explicit rollback outcome in logs/error message.
   4. **Harden cleanup**
      - Always remove temp/incoming files in `finally`.
      - Keep rollback file only during active import transaction window.
   5. **Error contract update (message-level)**
      - Distinguish:
        - `Import failed and database restored` vs
        - `Import failed and restore also failed (manual recovery required)`.

   #### Dependencies
   - **Recommended sequencing dependency:** apply `M2` first to reject known-incompatible newer schemas early.
   - Depends on current Room reopen behavior (`database.openHelper.writableDatabase`) remaining valid after close/restore.

   #### Risk Assessment
   - **What could go wrong**
     - Rename operation failures on some filesystems/devices.
     - WAL/SHM leftovers conflicting with restored file.
     - Rollback path itself fails due IO/storage conditions.
   - **Mitigation**
     - Keep all rename operations in same directory (same volume) to maximize atomicity.
     - Phase-specific `runCatching` + telemetry markers.
     - Preserve safety backup as secondary recovery path when immediate rollback fails.

   #### Verification Plan
   - **Automated (androidTest preferred):**
     1. Seed real DB with sentinel expense/category row.
     2. Build import file that passes lightweight validation but causes Room schema verification failure post-swap.
     3. Call `importDatabase` and assert:
        - result is failure,
        - sentinel row still exists after method returns,
        - app DB remains openable.
     4. Add happy-path import test confirming rollback artifact is cleaned up on success.
   - **Manual checks:**
     - Perform failed import from Debug screen; verify previous data remains intact and app continues functioning.

   #### Estimated Effort
   - **High**

   #### Helpful Existing Snippet (for coder context)
   ```kotlin
   // Current non-rollback-safe core (simplified)
   dbFile.delete()
   dbWalFile.delete()
   dbShmFile.delete()
   if (!tempFile.renameTo(dbFile)) throw Exception("Failed to move imported database into place")
   // ...post-swap verification may fail here with no restore...
   ```

---

2. **Batch name: M2 — Reject newer source schema versions**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
     - (Optional) schema-version constant source if centralized for reuse.
     - Tests (new): source validation/import version guard tests.
   - objective:
     - Prevent importing DB files with `user_version` greater than app-supported schema.
   - risks:
     - Hardcoded version drift if app DB version increments later.
   - validation:
     - Import of `schemaVersion > current` fails early with actionable message.

   #### Root Cause Analysis
   - **Exact location:** `DatabaseBackupRepositoryImpl.kt:272-283`.
   - Current validation only rejects old schemas `1..5`.
   - It does **not** reject newer schemas (e.g., backup from future app version).
   - Current app schema is `70` (`AppDatabase.kt`, `@Database(... version = 70 ...)`).
   - Impact:
     - Newer backup may pass validation, proceed to swap, then fail when Room cannot open due missing migration path/downgrade incompatibility.
     - Increases chance of C2 failure mode.

   #### Implementation Strategy
   1. Define `currentSupportedSchemaVersion` from a single source of truth.
   2. Extend validation conditions:
      - reject old destructive-risk versions (existing rule), and
      - reject `schemaVersion > currentSupportedSchemaVersion`.
   3. Return explicit error text:
      - indicate source version and current app-supported version,
      - instruct user to update app or export from compatible version.
   4. Keep this check before any file mutation.
   5. Add/update tests for boundary values:
      - valid: current,
      - invalid: current+1,
      - invalid: 1..5.

   #### Dependencies
   - No hard dependency on other batch items.
   - **Logical predecessor for C2** (reduces incompatible import attempts before swap).

   #### Risk Assessment
   - **What could go wrong**
     - Version constant drift can create false rejects/accepts.
   - **Mitigation**
     - Centralize version reference and document maintenance rule in code comment.
     - Add a regression test tied to boundary behavior.

   #### Verification Plan
   - **Automated:** create temporary SQLite files with controlled `PRAGMA user_version` and required tables.
   - Assert `importDatabase` returns failure without touching active DB for `current+1`.
   - **Manual:** attempt import from artificially bumped schema backup; confirm clear user-facing failure message.

   #### Estimated Effort
   - **Low**

   #### Helpful Existing Snippet (for coder context)
   ```kotlin
   if (schemaVersion in 1..5) {
       return Result.failure(Exception("Backup from old app version ..."))
   }
   // Missing guard for schemaVersion > current app version
   ```

---

3. **Batch name: M3 — Prevent source stats loss in `parsed == null` path**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
     - Tests: `NotificationProcessingPipeline...Test.kt` (new or extend reliability tests)
   - objective:
     - Ensure stats updates are durable even when parser returns `null` and source package has no prior `source_stats` row.
   - risks:
     - Small risk of call-order regressions or duplicate logic divergence between parsed/null branches.
   - validation:
     - First-ever notification from a package in `parsed == null` branch must create source row and increment counters.

   #### Root Cause Analysis
   - **Exact location:** `NotificationProcessingPipeline.kt:160-186`.
   - In `parsed == null` branch, transaction increments stats directly:
     - `incrementTotalAndPending(...)` or `incrementTotalAndAutoRejected(...)`.
   - If `source_stats` row does not exist for package, SQL `UPDATE` affects 0 rows.
   - Parsed branch already guards this via `insertIfNotExists(...)` at line `~204`; null branch does not.
   - Impact:
     - Lost stats for new sources on parser-null events (including oversized/manual-review path).
     - Trust/routing metrics become skewed.

   #### Implementation Strategy
   1. In `parsed == null` transaction branch, insert source row before any increment:
      - `sourceStatsDao.insertIfNotExists(SourceStats(packageName = notification.packageName))`.
   2. Keep this inside the same DB transaction as raw insert + relevance mark.
   3. Refactor shared behavior to avoid future drift:
      - optional helper for “ensure source row + increment method”.
   4. Preserve existing duplicate behavior (`rawId == -1L` early return).

   #### Dependencies
   - Independent from C2/M2; can run in parallel.

   #### Risk Assessment
   - **What could go wrong**
     - Additional insert call may slightly increase DB write cost.
     - Refactor could accidentally alter duplicate/relevance behavior.
   - **Mitigation**
     - Keep change minimal and branch-local first.
     - Add focused test coverage for both oversized and auto-rejected null-parse variants.

   #### Verification Plan
   - **Automated:**
     1. Build pipeline test with real in-memory Room DAOs (or robust integration test harness).
     2. Force parser to return `null`.
     3. Case A (non-oversized): assert `source_stats` row exists and `totalNotifications + autoRejected` increment.
     4. Case B (oversized candidate): assert `source_stats` row exists and `totalNotifications + pendingReview` increment.
   - **Manual:**
     - Feed a first-time package notification that parser rejects; verify Source Stats UI/debug output increments.

   #### Estimated Effort
   - **Medium**

   #### Helpful Existing Snippet (for coder context)
   ```kotlin
   // parsed == null branch currently increments without ensuring row
   sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, timeProvider.now())

   // parsed != null branch already does:
   sourceStatsDao.insertIfNotExists(SourceStats(packageName = notification.packageName))
   ```

---

### Dependencies
- **Execution order (recommended):**
  1. `M2` (cheap compatibility guard, reduces invalid attempts),
  2. `C2` (core safety mechanism),
  3. `M3` (independent, can be parallelized with C2 if separate coder stream).
- Existing prerequisites assumed available:
  - Room migration registry (`AppDatabase.ALL_MIGRATIONS`) and no destructive fallback in production DB module.

### Rollback / Safety
- **For implementation rollout:**
  - Ship C2 with extra logging around phases: `preSwap`, `swap`, `postSwapVerify`, `rollback`.
  - Keep recovery path deterministic: always attempt rollback-on-failure when swap has started.
  - Maintain safety backup as secondary recovery if immediate rollback fails.
- **For release safety:**
  - Run instrumented import-failure scenario before merge.
  - If any rollback test is flaky/failing, block release of C2 and keep current import hidden behind existing debug-only flows.

### Acceptance Criteria
- [ ] `C2`: Any failure after swap initiation restores previous DB (verified by automated test with sentinel data).
- [ ] `C2`: Successful import cleans temporary rollback artifacts and keeps DB openable.
- [ ] `M2`: Imports with `source user_version > app supported version` fail pre-swap with clear message.
- [ ] `M2`: Boundary tests cover old unsupported, current supported, and newer unsupported schemas.
- [ ] `M3`: `parsed == null` path always ensures `source_stats` row before increments.
- [ ] `M3`: First-notification null-parse paths (auto-reject + oversized->pending) update stats correctly.
- [ ] No regressions in existing notification processing and import/export smoke tests.

---

## Assumptions & Unknowns
- Assumptions:
  - Import source is a single SQLite DB file (`user_version` meaningful).
  - File operations occur on same filesystem path (rename expected atomic enough for local app storage).
  - App is effectively single-writer during import (no concurrent import/reset).
- Unknowns to confirm before coding:
  - Preferred canonical source for current schema version (constant vs annotation reflection).
  - Whether new tests should be JVM+Robolectric or androidTest-only for file swap realism.
  - Desired user-facing wording for “restore succeeded/failed” import error states.
