# CA-2026-09-21 — Cell I-02 Audit

## Provenance
- Date: 2026-09-22 (Europe/Bucharest)
- Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965` (`git rev-parse --short HEAD` = `37601232`)
- Session: direct astra session
- Cell: I-02 (Persistence and schema)
- Mode: AUDIT, static only; no production-code edits; no builds/tests run
- Pin guard: `git diff --stat 37601232..HEAD -- app config scripts` empty

## Scope and coverage
- Governing prompt: §2 known-debt/intended-behavior rules and §4 defect classes/finding schema/severity.
- Coverage matrix I-02 row and Phase-0 file-to-cell/orphan reconciliation.
- Legal-path sections: Expense Mutations; Lifecycle Events; Backup / Restore; Money / Currency; Group Mutations.
- Segment entries: 9 Core Expense Management; 16 Currency & Exchange; 18 Export & Backup; 23 Savings Prompts & Nudges; 24 Shared Expense Groups; 25 Shared Expense Budget Offset; 30 Dependency Injection; 32 Utilities & Shared Helpers; 39 Static Guardrails & CI Verification (as applicable to persistence ownership).
- Primary files to inspect: `AppDatabase.kt`, `DatabaseMigrations.kt`, `DatabaseSchemaPolicy.kt`, `RoomDomainTransactionRunner.kt`, `DatabaseWriteBarrier.kt`, `RestrictedExpenseDaoMutation` and related entities/DAOs.
- Required lenses: migration ordering/data preservation, index coverage, FK/provenance integrity, and legal-path/barrier/atomicity implications across all 15 defect classes.
- Covered so far: `AppDatabase.kt` (annotation/entity/DAO registry, fresh-install callback, builder); `DatabaseMigrations.kt` (full v145→149 chain); `DatabaseSchemaPolicy.kt`; `RoomDomainTransactionRunner.kt`; `DatabaseWriteBarrier.kt`; `RestoreMaintenanceMode.kt`; `RestrictedExpenseDaoMutation.kt`; `DatabaseModule.kt`; `BackgroundJobRun.kt`; `PendingReview.kt`; `GroupMember.kt`; `GroupExpense.kt`; migration-proof/registration tests and schema inventory references.

## Findings

### CA-I-02-001 | Fresh-install callback contract is stale after the v145 baseline cutover | defect class 13 | severity P3

- Evidence: `docs/DATABASE_BASELINE_POLICY.md`, 34-38, explicitly requires schema to come only from Room entities and registered migrations and forbids fresh-install callbacks that create/drop/alter schema. The pinned production `AppDatabase.configureBuilder`, 8742-8748, follows that policy by registering migrations and WAL without `addCallback`; git history records the same deliberate removal in `27ca3bd8` (the v145 baseline cutover). However, the production `fileBuilder` KDoc, 8717-8722, still claims the callback is pre-configured and the legacy `FRESH_INSTALL_CALLBACK` DDL remains at 5095-5244.
- Impact path: `DatabaseModule.provideDatabase`, 31-37, and restore/rescue/staged-verification callers use the policy-compliant builder, while `FreshInstallIndexParityTest` and `FreshInstallBatch8ParityTest` (setups 62-67 and 53-57) assert the forbidden callback-managed schema. Maintainers can therefore treat stale parity tests/comments as proof of a runtime contract that production intentionally does not implement; manually adding the callback (as `DatabaseMigrationTest`, 386-392, does) exercises a different schema and also omits v149 `pending_reviews` columns in its rebuild SQL.
- Caller trace: production `DatabaseModule.provideDatabase` -> `AppDatabase.fileBuilder` -> `configureBuilder`; `FRESH_INSTALL_CALLBACK` production caller: NONE-FOUND. Test-only callback callers are `DatabaseMigrationTest` and no canonical builder.
- Existing tests/guards: baseline policy is the intended-behavior guard, but no source guard rejects stale callback KDocs/tests or checks fresh-vs-migrated schema under the canonical builder. No tests were executed.
- Cross-cell impact: I-02 documentation/test contract; restore/rescue and any persistence cell relying on the named fresh-install parity tests can receive false schema-assurance signals. No new user-data corruption is claimed because the runtime builder follows the v145 policy.
- Old-ID cross-refs: none found in the I-02 cell block or the revalidated registry. The callback DDL is treated as legacy/dead code, not as a second runtime finding.

### Additional persistence coverage

- `BankStatementImportRun.kt`, `BankStatementImportItem.kt`, `BankStatementImportRunDao.kt`, and `BankStatementImportItemDao.kt` were read end-to-end; run/item writes remain owned by the bank statement lifecycle processor. `GroupExpenseDao.kt` and the relevant `GroupTransactionCoordinator.kt` transaction/idempotency ranges were checked against the group legal path.
- The Phase-0 orphan persistence files were read: `PromptState.kt`/`PromptStateDao.kt`, `SavingsGoal.kt`/`SavingsGoalDao.kt`, `SavingsSweepPlan.kt`/`SavingsSweepPlanDao.kt`, `SplitTemplate.kt`/`SplitTemplateDao.kt`, and `SplitItemAssignment.kt`/`SplitItemAssignmentDao.kt`. Pure UI/engine algorithms remain with their owning pipeline cells.

### Phase-0 file-to-segment reconciliation

- The matrix assigns I-02 the persistence portions of Dependency Injection, Utilities, Notification/Review, Receipt Lifecycle, Recurring, Core Expenses, Groups, Group Budget Offset, Backup, Bank, Investments, Tax, Warranty/Subscriptions, Bill Reminders, Savings Prompts & Nudges, and Enhanced Split Transactions. The pinned `AppDatabase` annotation contains 70 entities (43-120) and 68 `@Dao` interfaces/accessors; the v149 migration registry contains four entries (145->146 through 148->149).
- Enhanced Split persistence is inventoried as `SplitTemplate.kt`, `SplitItemAssignment.kt`, `SplitTemplateDao.kt`, and `SplitItemAssignmentDao.kt`. Savings Prompts & Nudges persistence is inventoried as `PromptState.kt`, `PromptStateDao.kt`, `SavingsGoal.kt`, `SavingsGoalDao.kt`, `SavingsSweepPlan.kt`, `SavingsSweepPlanDao.kt`, and `PromptStateRepository.kt`.
- `docs/architecture/CODEBASE_INVENTORY.md` remains a stale v148/68-DAO snapshot while the pinned source is v149 with 70 entities. The matrix marks this as deferred documentation drift; it is recorded here as a reconciliation limitation, not promoted as a production finding.
- `DatabaseSchemaPolicy.UNSUPPORTED_VERSIONS` says "use destructive migration" while `DatabaseMigrations`/`DATABASE_BASELINE_POLICY` route pre-v145 databases through rescue and forbid fallback in normal builds. The same ambiguity is explicitly recorded in `docs/guardrails/PR-MIG-00_real_migration_execution_evidence_audit_plan.md`, 255-257, so it is retained as known policy debt rather than counted again.

## All 15 defect classes considered

| Class | Static disposition |
|---|---|
| 1 Legal path | Expense, lifecycle-event, backup/restore, money/currency, group, and bank-statement legal sections were read; DAO registry and restricted mutation marker checked. No new legal-path bypass asserted. |
| 2 Barrier | `DatabaseWriteBarrier`, `RestoreMaintenanceMode`, `RoomDomainTransactionRunner`, and group transaction entry checks read. Universal check-vs-lease debt and cross-cell writer findings were not restated. |
| 3 Atomicity / TOCTOU | Current migrations are additive and Room-applied; group writes use the transaction runner. No separate migration/schema TOCTOU finding established. |
| 4 Idempotency | v149 bank-review identity index, expense/group-link uniqueness, planned/open-source indexes, and prompt/sweep persistence declarations checked. No new duplicate-admission defect established. |
| 5 Cancellation | Transaction runner does not catch cancellation; no schema-owned cancellation swallow found. |
| 6 Side-effect timing | Persistence coordinators separate transaction work from post-commit actions; no schema-owned side-effect timing defect found. |
| 7 Money / currency | Currency columns/defaults and migration additions inspected; no new persistence-specific money defect found. |
| 8 Time | Persisted timestamp columns and migration additions inspected; no new persistence-specific time defect found. |
| 9 Privacy | Raw/review/import tables and retention-sensitive DAO declarations scoped; no new persistence-specific privacy finding beyond other cells. |
| 10 Worker hygiene | `BackgroundJobRun` v147->149 columns/defaults and DAO shape checked; no new worker-record schema defect found. |
| 11 Data integrity | Migration FK/index/data-preservation checks produced no new data-integrity finding; the callback contract issue is intentionally documentation/test-signal scope. |
| 12 Error handling | Migration registration is continuous and explicit; no new migration error-path finding. |
| 13 Wiring / dead code | CA-I-02-001: the legacy callback and builder KDoc remain after the deliberate v145 removal, while production builders follow the baseline policy. |
| 14 Test correctness | Fresh-install parity tests assume the removed callback; this stale test contract is included in CA-I-02-001, not counted separately. |
| 15 Fix-regression | No source drift from the pin; v149 additions are registered. The stale callback contract is the sole new documentation/test-signal regression finding. |

## Limits and validation

- Core database, migration, builder, barrier, runner, entity/DAO, restore/rescue, and staged-verification paths were read with the required mutation/IO-focused package-mate scoping. Pure UI and engine algorithms were not re-audited when another cell owns them.
- Coverage status: complete for the named primary persistence files and the mutation/IO package mates listed above; read-only data declarations and unrelated UI/engine consumers were inventoried but not individually full-read.
- Validation: NOT RUN by instruction. No builds, tests, lint, guards, source edits, commits, or pushes.
- Finding count: 1 (P0 0 / P1 0 / P2 0 / P3 1).
