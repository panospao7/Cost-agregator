# Deep Analysis — Batch 11: Database - AppDatabase & Migrations (@reviewer)

> **[B.4 SYNC]** All B.4-scope issues in this file have been resolved. See `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-11.md` and `docs/reviews/REVIEW-B4.md` for evidence and waivers.

## Scope
- data/database/AppDatabase.kt
- data/database/AppDatabaseMigrations.kt (not found — migrations inline in AppDatabase.kt)
- data/database/DatabaseModule.kt
- data/database/DatabaseModels.kt (not found)
- data/database/converter/Converters.kt
- data/database/converter/DateConverters.kt (not found)
- data/database/converter/MapConverters.kt (not found)
- data/database/converter/UriConverters.kt (not found)
- data/database/GroupTransactionCoordinator.kt
- data/database/TransactionRollback.kt (not found)

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `data/database/GroupTransactionCoordinator.kt` | MAJOR | Transaction/Race | `addMemberToGroup()`, `addExpenseToGroup()`, and `addExpenseWithLink()` do validation reads and the insert in separate operations, outside a single DB transaction. A concurrent archive/delete can invalidate the preconditions after they were checked, so writes can slip into inactive groups or fail nondeterministically. **[RESOLVED BY B.4 — Batch 2]** | Wrap validation + write in one `database.withTransaction` block and re-check group/member state inside that transaction. |
| 2 | `data/database/GroupTransactionCoordinator.kt` | MINOR | Logic/API contract | `addExpenseToGroupAtomic()` accepts `memberBalanceUpdates`, but never applies them. It loads each member and writes back `it.copy()` unchanged, so the method silently no-ops on the balance-update part of its contract. **[RESOLVED BY B.4 — Batch 2]** | Either remove this API/parameter, or persist the intended balance state in a real field/table and update it here; do not silently ignore the input. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | GroupTransactionCoordinator + GroupsRepositoryImpl + SharedExpenseGroupsViewModel | MAJOR | End-to-end "create system expense + create linked group expense" is still a two-step workflow with best-effort rollback. A crash/process death between the two writes can still orphan the system expense. **[RESOLVED BY B.4 — Batch 2]** | Move both writes behind one repository/coordinator transaction boundary so UI calls a single atomic operation. |
| 2 | Batch plan paths + current repo layout | MINOR | 6 of the 10 requested batch files are not present in the repo (`AppDatabaseMigrations.kt`, `DatabaseModels.kt`, `DateConverters.kt`, `MapConverters.kt`, `UriConverters.kt`, `TransactionRollback.kt`). The implementation is consolidated elsewhere (`AppDatabase.kt`, `Converters.kt`, `di/DatabaseModule.kt`, `data/database/model/*`), which breaks plan-to-code traceability. **[RESOLVED BY B.4]** Batch plan notes updated in REVIEW-B4.md; file layout mismatch documented as acceptable given B.4 consolidated approach. | Update the batch manifest/review checklist to the actual file layout, or restore the intended split if that structure is required. |

### Summary
- Total issues: 4
- Files with issues: 1 direct file; only 4 of the 10 requested paths exist in the current repo
- Requirements met: **no** — migrations 68→70 and DB builder configuration look generally solid, but the transaction coordinator still does not fully guarantee atomicity for all public write paths, and the batch/file mapping is out of sync with the implementation
- Testing adequate: **no** — migration coverage for 69→70 exists, but converter tests only cover `TransactionType`, and there is no focused regression coverage for coordinator race conditions or the two-step group-expense creation flow
