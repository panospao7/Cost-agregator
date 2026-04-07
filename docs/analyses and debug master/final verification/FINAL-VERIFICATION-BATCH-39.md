# Final Verification — Batch 39: Debug, Backup & Export

## Scope
### Primary scope files
- `domain/debug/AiRuntimeDiagnostics.kt`
- `domain/debug/DebugData.kt`
- `domain/debug/DebugIssue.kt`
- `domain/debug/DebugIssueDetector.kt`
- `domain/debug/NotificationSeeder.kt`
- `domain/debug/ServiceDiagnostics.kt`
- `domain/backup/DatabaseBackupRepository.kt`
- `domain/backup/DatabaseOperationResults.kt`
- `domain/export/AccountingExporters.kt`
- `domain/export/ExportTransaction.kt`
- `domain/engine/DashboardFollowThroughEngine.kt`
- `domain/bank/BankApiIntegration.kt`
- `domain/reminder/BillReminderManager.kt`

### Supporting validation files read during verification
- `data/database/entity/ManualRecurringExpense.kt`
- `domain/model/RecurringPattern.kt`
- `data/database/entity/Expense.kt`
- `domain/parser/AppParserRegistry.kt`
- `domain/analytics/SpendingThresholdCalculator.kt`
- `domain/model/navigation/DomainTransactionFilter.kt`
- `service/TransactionFilterSerializer.kt`
- `data/repository/RecurringExpenseRepository.kt`
- `data/repository/AccountingExportRepository.kt`
- `ui/screens/export/ExportOptionsViewModel.kt`
- `data/database/entity/BankConnection.kt`
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/reminder/BillRemindersScreen.kt`
- `data/database/dao/ExpenseDao.kt`
- `di/ExportModule.kt`
- `domain/parser/parsers/GreekBankParser.kt`
- `domain/parser/parsers/RevolutParser.kt`
- `domain/parser/parsers/SmsParser.kt`
- `ui/screens/debug/DebugDataStorage.kt`
- `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `domain/util/TimePeriodUtils.kt`
- `service/NotificationCaptureService.kt`
- `ui/screens/bank/BankConnectionsViewModel.kt`
- `data/repository/ExportDataRepository.kt`
- `data/repository/ExpenseRepository.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/reminder/BillReminderManager.kt:125-131` | High | Logic | `getMonthlyBillsTotal()` string-matches `frequency.name` and handles `YEARLY`, but the enum is `ANNUALLY` and also includes `SEMI_ANNUALLY`/`IRREGULAR`. Semi-annual and annual bills are therefore overstated, and irregular bills are treated as fixed monthly spend. | B | CONFIRMED | Switch to `when (expense.frequency)` and handle all enum values explicitly. |
| 2 | `domain/reminder/BillReminderManager.kt:140-153` | High | Logic | `calculateNextDate()` has the same stringly-typed enum drift. `ANNUALLY`, `SEMI_ANNUALLY`, and `IRREGULAR` fall through to the default monthly advance. | B | CONFIRMED | Use an exhaustive enum `when` and define explicit behavior for irregular items. |
| 3 | `domain/reminder/BillReminderManager.kt:25-29,59-64` | High | Logic | Urgency thresholds do not match the enum’s documented semantics: due-today bills become `URGENT` instead of `CRITICAL`, 2-day reminders are downgraded to `WARNING`, and 4-7 day reminders stay `INFO`, shrinking the notification window. | R | CONFIRMED | Align the `when` thresholds with the enum comments: overdue/today = `CRITICAL`, 1-2 = `URGENT`, 3-7 = `WARNING`. |
| 4 | `domain/reminder/BillReminderManager.kt:102-109,140-153` | High | Logic | `markBillPaid()` advances only one interval from the stored due date. Paying a long-overdue recurring bill can leave the new `nextDate` still in the past. | R | CONFIRMED | Advance from `max(now, currentDueDate)` or loop until the next date is in the future. |
| 5 | `domain/bank/BankApiIntegration.kt:68-82,87-109,115-163` | Medium | Functional / Fail-open placeholder | The bank integration still returns successful OAuth URLs, demo tokens, and mock sync results instead of failing closed. Any caller can treat a non-existent bank connection as real. | B | DOWNGRADED | Gate the feature behind an explicit “not implemented” error or a non-production flag until a real provider exists. |
| 6 | `domain/bank/BankApiIntegration.kt:214-226` | High | Data corruption | `mapTransactionToExpense()` forces every bank movement to `TransactionType.PURCHASE` and applies `abs(amount)`, so deposits/refunds/transfers are imported as positive expenses. | B | CONFIRMED | Preserve sign and map bank movement direction/type to the correct `TransactionType`. |
| 7 | `domain/bank/BankApiIntegration.kt:197-209` | Medium | Logic | `shouldSync()` only checks `autoSync` and elapsed time; it ignores `isActive` and `isConnected`, so disabled/disconnected connections still qualify for scheduled sync. | R | CONFIRMED | Require an active, connected account before returning `true`. |
| 8 | `domain/export/AccountingExporters.kt:12,49,100` | Medium | Concurrency | All three exporters keep `SimpleDateFormat` as instance state, and Hilt provides them as singletons. Concurrent exports can race on shared formatter state. | B | DOWNGRADED | Use `java.time`, `ThreadLocal`, or instantiate the formatter inside each export call. |
| 9 | `domain/export/AccountingExporters.kt:29,34-35` | High | Export correctness | QuickBooks IIF uses the category account on both the `TRNS` and `SPL` rows. Without a real source account on `TRNS`, imports become self-canceling or invalid. | R | CONFIRMED | Pass a real source account (bank/card/cash) for `TRNS` and keep the expense category on `SPL`. |
| 10 | `domain/export/AccountingExporters.kt:30,34-35,65,69,116,120` | Medium | Export correctness | All exporters emit raw `Double.toString()` values for money. That can produce inconsistent precision or scientific notation, which is unsafe for accounting exports. | D | CONFIRMED | Centralize money formatting and always emit fixed-point decimal strings. |
| 11 | `domain/export/ExportTransaction.kt:6-12` | High | Data loss | `ExportTransaction` omits `currency`, so accounting exporters cannot distinguish multi-currency rows or reject unsupported mixed-currency exports. | R | CONFIRMED | Add `currency` to the DTO and surface it in formats that support it or block mixed-currency export. |
| 12 | `domain/debug/DebugData.kt:17-72` | Medium | Serialization | `toJson()` hand-builds JSON and only escapes a subset of fields. Backslashes/control characters in preview text, merchant names, issue categories/messages, suggestions, logs, or `parserUsed` can produce invalid JSON. | B | DOWNGRADED | Replace string concatenation with a real serializer and remove manual escaping logic. |
| 13 | `domain/debug/NotificationSeeder.kt:103-115` | Medium | Data quality | Seeded transaction notifications derive package names from display labels (for example `Alpha Bank` → `com.simulation.alpha bank`) instead of using supported parser package IDs. That makes the dataset unrealistic and bypasses bank-specific parser routing. | R | CONFIRMED | Map each simulated source to a stable, valid package name that matches the parser coverage you want to test. |
| 14 | `domain/debug/NotificationSeeder.kt:132-145` | Low | Test-data fidelity | `generateRecurring()` produces isolated random charges rather than repeated merchant/date patterns, so the seeded dataset rarely exercises recurring-payment detection or reminder flows. | R | DOWNGRADED | Generate clustered occurrences per recurring merchant across realistic intervals. |
| 15 | `domain/debug/ServiceDiagnostics.kt:24-41` | Medium | Concurrency | The diagnostic counters use unsynchronized read-modify-write operations on `SharedPreferences`, so concurrent lifecycle callbacks can lose increments. | B | CONFIRMED | Guard updates with a lock or move counters to an atomic/transactional store. |
| 16 | `domain/engine/DashboardFollowThroughEngine.kt:179-183,208-210,237-240` | Medium | Logic | Category and recent recommendations hardcode `PURCHASE`, and merchant recommendations omit `transactionType` entirely. Recommendations triggered by deposits/transfers can open filtered views that exclude the triggering transaction or mix in unrelated purchases. | R | CONFIRMED | Preserve the source transaction’s type in every generated filter. |
| 17 | `domain/backup/DatabaseBackupRepository.kt:27; domain/backup/DatabaseOperationResults.kt:12-16` | Medium | API design | Import restart semantics live in `DatabaseImportResult.SuccessNeedsRestart`, but the repository contract returns `Result<DatabaseImportSummary>` and forces callers to infer restart via sentinel summary values. | R | CONFIRMED | Return one explicit import result model from the repository that carries both summary data and `requiresRestart`. |
| 18 | `domain/debug/DebugIssueDetector.kt:121-129` | Low | Logic | The OCR-quality heuristic counts literal `?` characters as “unrecognized characters”, so normal text with question marks can trigger false OCR warnings. | D | CONFIRMED | Count only replacement characters or use a more targeted corruption heuristic. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/repository/AccountingExportRepository.kt:50-77` | High | Data loss | The repository-backed export path still calls `expenseRepository.getExpensesBetween(startDate, endDate)`, which inherits the DAO’s default 2000-row cap. Large accounting exports are silently truncated, while the streaming UI export path pages correctly. | Reuse the paged deterministic export path (`getExpensesBetweenForExport`) or move both flows behind a single paged export service. |
| 2 | `domain/export/ExportTransaction.kt:6-12` | High | Semantic loss | The export DTO also omits `transactionType`, and current export callers do not filter to purchases before building it. Deposits/withdrawals/transfers therefore get serialized as expense rows in accounting formats. | Add `transactionType` to the DTO and either filter to supported types up front or emit type-specific accounting rows. |
| 3 | `ui/screens/export/ExportOptionsViewModel.kt:158-170` | Medium | Data loss | The generic CSV export header is `Date,Merchant,Amount,Category,Notes,ID` and omits currency entirely, so mixed-currency CSV exports flatten unlike amounts into one column with no disambiguation. | Add a `Currency` column or block generic CSV export when multiple currencies are present. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `D-2` | `domain/debug/DebugData.kt:41` | Transaction dates are intentionally exported as epoch millis and read back that way by `DebugDataStorage`; using ISO for metadata and epoch for row data is not a functional bug. |
| 2 | `D-5` | `domain/export/AccountingExporters.kt:74,81,124,131` | Preserving the original field instead of trimming it is a style/readability concern, not a correctness defect in current exports. |
| 3 | `D-6` | `domain/export/AccountingExporters.kt:73-74` | The whitespace-only edge case is not materially reachable for current exported merchant/category fields and does not represent an actual observed failure. |
| 4 | `D-9` | `domain/reminder/BillReminderManager.kt:57` | Overdue reminders are rendered as “Overdue” in `BillRemindersScreen`; the negative `daysUntilDue` value is not surfaced as “Due in -3 days” in the current UI. |
| 5 | `D-11` | `domain/debug/ServiceDiagnostics.kt:28,35,41,51` | `commit()` is synchronous, but these diagnostic writes happen only on rare lifecycle callbacks; the report overstates the practical performance impact. |
| 6 | `D-12` | `domain/debug/NotificationSeeder.kt:39` | `twoMonthsMs` is an imprecise variable name, not a functional defect. |
| 7 | `D-15` | `domain/bank/BankApiIntegration.kt:128-138` | The placeholder sync path never uses the access token after refresh and only returns mock data, so the lack of an updated connection object is not an observable bug in the current implementation. |
| 8 | `D-17` | `domain/bank/BankApiIntegration.kt:246` | Generating only debit-like mock transactions is a test-coverage limitation, not a production defect by itself. |
| 9 | `D-18` | `domain/engine/DashboardFollowThroughEngine.kt:86,145,203` | The nullable-merchant branches are dead code/readability debt, but they do not break runtime behavior. |
| 10 | `D-19` | `domain/engine/DashboardFollowThroughEngine.kt:99` | This is only a micro-optimization/style suggestion, not an impact-bearing bug. |
| 11 | `D-20` | `domain/debug/DebugIssueDetector.kt:47` | `ParsedTransaction` already rejects non-positive/non-finite amounts in its constructor; this detector branch is redundant but harmless defensive code. |
| 12 | `D-23` | `domain/debug/NotificationSeeder.kt:14` | `categories` is intentionally consumed by `DebugViewModel`, so its visibility is not a bug. |
| 13 | `D-24` | `domain/reminder/BillReminderManager.kt:55` | Bills due exactly on the cutoff are already included because the code excludes only `nextDate > cutoff`, not equality. |
| 14 | `D-C3` | `domain/debug/AiRuntimeDiagnostics.kt:15-52; domain/debug/ServiceDiagnostics.kt:10-68` | Separate in-memory and persisted diagnostics are a design choice. The lack of a unified export API is not, by itself, a defect. |
| 15 | `D-C4` | `domain/engine/DashboardFollowThroughEngine.kt:86,145,203` | This cross-component finding is the same dead-code/nullability mismatch as `D-18`; it is not a functional bug. |
| 16 | `R-15` | `domain/engine/DashboardFollowThroughEngine.kt:69` | `SpendingThresholdCalculator` is explicitly documented as a single-user helper and does not query per-user data. Using `getThreshold()` does not change results in the current codebase. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `BillReminderManager` ↔ `RecurrenceFrequency` | High | Enum drift | `BillReminderManager` string-matches frequency names while `RecurringExpenseRepository` uses exhaustive enum handling. New or renamed enum values silently mis-schedule bills in one path but not the other. | `domain/reminder/BillReminderManager.kt`, `data/repository/RecurringExpenseRepository.kt`, `domain/model/RecurringPattern.kt` | Use enum-based `when` logic everywhere recurrence is interpreted. |
| 2 | Repository export path ↔ streaming export path | High | Divergent behavior / truncation | The legacy repository export path buffers a capped result set, while the newer UI export path pages deterministically. Export correctness fixes can drift, and one path already truncates large exports. | `data/repository/AccountingExportRepository.kt`, `data/repository/ExportDataRepository.kt`, `data/repository/ExpenseRepository.kt`, `data/database/dao/ExpenseDao.kt`, `ui/screens/export/ExportOptionsViewModel.kt` | Consolidate on one paged export service shared by repository and UI flows. |
| 3 | `Expense` → `ExportTransaction` → accounting exporters | High | Semantics loss | Currency and transaction-type semantics are stripped before formatting, so mixed-currency exports and non-purchase transactions cannot be represented safely by downstream exporters. | `data/database/entity/Expense.kt`, `domain/export/ExportTransaction.kt`, `domain/export/AccountingExporters.kt`, `data/repository/AccountingExportRepository.kt`, `ui/screens/export/ExportOptionsViewModel.kt` | Carry `currency` and `transactionType` through the export DTO and validate unsupported mixes explicitly. |
| 4 | `DebugData.toJson()` ↔ debug storage / JSON consumers | Medium | Serialization fragility | The debug export pipeline writes hand-built JSON, while import/storage paths expect valid JSON objects. Real-world text payloads can break round-tripping and downstream parsing. | `domain/debug/DebugData.kt`, `ui/screens/debug/DebugDataStorage.kt` | Use a real serializer for both export and import. |
| 5 | Backup import UI ↔ repository contract ↔ result models | Medium | Contract mismatch | Restart-required imports are represented in the UI sealed model but not in the repository contract; the current flow tunnels this state through `transactionCount == -1`. | `domain/backup/DatabaseBackupRepository.kt`, `domain/backup/DatabaseOperationResults.kt`, `data/repository/DatabaseBackupRepositoryImpl.kt`, `ui/screens/debug/DebugViewModel.kt` | Standardize on one repository result type that includes summary + restart semantics. |
| 6 | `domain.debug.*` ↔ Android framework/resources/preferences | Low | Architecture | Several “domain” debug classes depend directly on `Context`, string resources, and `SharedPreferences`, which weakens layering and testability. | `domain/debug/DebugIssueDetector.kt`, `domain/debug/NotificationSeeder.kt`, `domain/debug/ServiceDiagnostics.kt` | Move Android-specific concerns to a platform/data layer and keep domain APIs platform-neutral. |

## Summary
- Total verified issues: 18
- Confirmed: 18 (Critical: 0, High: 7, Medium: 9, Low: 2)
- False positives: 16
- Missed issues found: 3
- Files affected: 13/39

## Key Patterns
- **Stringly-typed enum handling** caused the most concrete logic defects in reminder scheduling and billing math.
- **Placeholder integrations that succeed instead of failing closed** create misleadingly “working” flows and phantom data.
- **Export paths are fragmented**: one path pages, one truncates, and both lose accounting semantics before formatting.
- **Hand-rolled serialization remains brittle** and is still a recurring source of malformed output.
- **Debug/test helpers are under-modelled**: seeded data and diagnostics favor convenience over realistic behavior and atomic correctness.
