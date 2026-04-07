# Final Verification — Batch 11: Database - AppDatabase & Migrations

## Scope
- `com/yourname/expensetracker/data/database/AppDatabase.kt`
- `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/data/database/converter/Converters.kt`
- `com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt`
- `com/yourname/expensetracker/data/database/dao/ExpenseGroupDao.kt`
- `com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt`
- `com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt`
- `com/yourname/expensetracker/data/database/entity/BankConnection.kt`
- `com/yourname/expensetracker/data/database/entity/Budget.kt`
- `com/yourname/expensetracker/data/database/entity/ExpenseGroup.kt`
- `com/yourname/expensetracker/data/database/entity/GroupExpense.kt`
- `com/yourname/expensetracker/data/database/entity/GroupMember.kt`
- `com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt`
- `com/yourname/expensetracker/data/security/BankTokenCipher.kt`
- `com/yourname/expensetracker/di/DatabaseModule.kt`
- `com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`
- `com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt:91-103,124-149,181-207` | Medium | Concurrency / TOCTOU | `addMemberToGroup()`, `addExpenseToGroup()`, and `addExpenseWithLink()` validate group/member state before the write and outside a single DB transaction. A concurrent archive/delete can invalidate those checks and still allow writes into inactive groups or produce nondeterministic FK failures. | B | CONFIRMED | Move validation and insert into one `database.withTransaction {}` block and re-read state inside that transaction. |
| 2 | `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt:280-293` | Low | Logic / API contract | `addExpenseToGroupAtomic()` accepts `memberBalanceUpdates`, but the loop writes `memberDao.update(it.copy())` and ignores `newBalance`. `GroupMember` has no balance field, so this path is a silent no-op. | B | DOWNGRADED | Remove the unused parameter/API, or add/persist the balance state in the correct entity/table and update it explicitly. |
| 3 | `com/yourname/expensetracker/data/database/AppDatabase.kt:1311-1315` | Low | Historical migration mismatch | Migration `42→43` created `group_expenses.expenseId` as `NOT NULL` even though `GroupExpense.expenseId` is nullable for standalone group expenses. The schema is repaired later in `49→50`, so this is a historical shipped-schema defect rather than a current v70 runtime bug. | D | DOWNGRADED | Keep `49→50` as the repair step and add a regression migration test covering standalone `GroupExpense(expenseId = null)`. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt:226-229` | Medium | Logic / API contract | `deleteGroup()` always returns `true` if `archiveGroup()` does not throw. Because the DAO update returns `Unit`, deleting a nonexistent group ID is reported as success even when no row was archived. | Return the affected-row count from `ExpenseGroupDao.archiveGroup()` and map `0` rows to `false` or an explicit not-found result. |
| 2 | `com/yourname/expensetracker/data/database/AppDatabase.kt:2077-2090` | Low | Historical migration mismatch | Migration `49→50` recreated `group_expenses` with `paidById INTEGER NOT NULL` but kept `ON DELETE SET NULL` on the same FK. That schema is self-contradictory and was only corrected in `51→52` when the FK changed to `RESTRICT`. | Add a regression migration test for the `49→50 → 51→52` path and document that `51→52` is repairing the earlier FK/nullability mismatch. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #3 | `AppDatabase.kt:3481-3482`, `Budget.kt:36` | The SQL default and the entity default both use `ROLLING`. The immediate `UPDATE ... SET periodMode = 'CALENDAR'` is an intentional backfill for pre-existing rows, not a schema drift bug. |
| 2 | Debugger #4 | `GroupTransactionCoordinator.kt:89-106` | `addMemberToGroup()` is explicitly typed as `Long?` and its contract already uses `null` for failure. This is weak diagnostics, but not a correctness defect in the implementation. |
| 3 | Debugger #5 | `GroupTransactionCoordinator.kt:226-232` | The report's stated bug is inaccurate: the method does **not** return `false` for "group not found". It actually returns `true` on no-op updates. The real defect is captured above as Missed Issue #1. |
| 4 | Debugger #6 | `GroupTransactionCoordinator.kt:313` | This is only a deprecation warning; `getGroupById()` and `getById()` are behaviorally equivalent here. |
| 5 | Debugger #7 | `GroupTransactionCoordinator.kt:290` | Same as above: this is a warning-only deprecated alias, not a functional bug. |
| 6 | Debugger #8 | `AppDatabase.kt:2725-2754` | `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` make this migration idempotent. It is redundant, but not a runtime defect. |
| 7 | Debugger #9 | `AppDatabase.kt:2888-2931` | Same as #6: redundant DDL, but no behavioral breakage. |
| 8 | Debugger #11 | `AppDatabase.kt:1580-1640` | The migration already restores `PRAGMA foreign_keys` in `finally`, and SQLite connection state does not persist across app restarts the way the report suggests. |
| 9 | Debugger #12 | `Converters.kt:24-139` | The enum fallbacks are explicit compatibility behavior for unknown persisted values. They may hide version skew, but they are not an implementation bug in this batch. |
| 10 | Debugger #15 | `DatabaseModule.kt:28-36` | The absence of destructive fallback is intentional and desirable for user data. `ALL_MIGRATIONS` is currently contiguous from 6→70, so there is no present migration gap. |
| 11 | Debugger #16 | `AppDatabase.kt:3454-3456` | `tableName` is only passed from hardcoded internal migration strings, so there is no exploitable injection path here. |
| 12 | Debugger CP-3 | `AppDatabase.kt:3710-3774`, `DatabaseModule.kt:32` | This is a speculative future-maintenance risk, not a current defect. The registry is centralized and presently complete. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Manual expense creation → linked group expense creation | High | Atomicity / data integrity | `SharedExpenseGroupsViewModel.addExpense()` still creates the system expense first and only then creates the linked `group_expenses` row. Best-effort rollback handles ordinary failures, but crash/process death between the two writes can still orphan the system expense. | `com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`, `com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt`, `com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`, `com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`, `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt` | Move both writes behind one repository/coordinator transaction boundary so the UI calls a single atomic operation. |
| 2 | Group expense creation validation pipeline | Medium | Concurrency / TOCTOU | Validation is spread across `AddGroupExpenseUseCase`, `GroupsRepositoryImpl`, and `GroupTransactionCoordinator`, but the final write still happens without one transactionally consistent read-set. The pipeline remains vulnerable to archive/member-change races. | `com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`, `com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`, `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt` | Collapse validation and insert into a single transactional coordinator entry point and keep higher layers free of redundant preflight reads. |
| 3 | Migration `69→70` → Android Keystore encryption | Medium | Availability | The migration calls `BankTokenCipher.encryptIfNeeded()` while opening the database. If Android Keystore access fails for any row, the migration aborts and the app cannot open the DB. | `com/yourname/expensetracker/data/database/AppDatabase.kt`, `com/yourname/expensetracker/data/security/BankTokenCipher.kt` | Catch per-row encryption failures inside the migration, preserve the row, and schedule follow-up re-encryption instead of failing DB open. |
| 4 | Batch plan ↔ implementation layout | Low | Traceability | The approved B11 plan names six database files that do not exist in the repo (`AppDatabaseMigrations.kt`, `DatabaseModels.kt`, `DateConverters.kt`, `MapConverters.kt`, `UriConverters.kt`, `TransactionRollback.kt`). The implementation is consolidated elsewhere, which breaks plan-to-code traceability for this batch. | `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-PLAN.md`, `com/yourname/expensetracker/data/database/AppDatabase.kt`, `com/yourname/expensetracker/data/database/converter/Converters.kt`, `com/yourname/expensetracker/di/DatabaseModule.kt` | Update the batch plan/checklist to match the actual repo layout, or restore the intended file split if that structure is required. |

## Summary
- Total verified issues: 8 unique issues
- Confirmed: 8 (Critical: 0, High: 1, Medium: 3, Low: 4)
- False positives: 12
- Missed issues found: 2
- Files affected: 7/19

## Key Patterns
- Transaction boundaries are still too narrow around group writes: validation often happens before the transaction instead of inside it.
- The migration chain contains repaired historical schema defects that are not covered by targeted regression tests (`42→43`, `49→50`, `49→50→51→52`, `68→69`).
- Batch-plan traceability is poor for this area because the documented file split no longer matches the real implementation layout.
- Existing test coverage is skewed toward `49→50`, `69→70`, and `TransactionType` conversion; the riskiest older migration edge cases and coordinator race paths remain untested.
