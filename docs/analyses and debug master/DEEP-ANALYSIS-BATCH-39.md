# Deep Analysis — Batch 39: Debug, Backup & Export (@reviewer)

## Scope
- domain/debug/AiRuntimeDiagnostics.kt
- domain/debug/DebugData.kt
- domain/debug/DebugIssue.kt
- domain/debug/DebugIssueDetector.kt
- domain/debug/NotificationSeeder.kt
- domain/debug/ServiceDiagnostics.kt
- domain/backup/DatabaseBackupRepository.kt
- domain/backup/DatabaseOperationResults.kt
- domain/export/AccountingExporters.kt
- domain/export/ExportTransaction.kt
- domain/engine/DashboardFollowThroughEngine.kt
- domain/bank/BankApiIntegration.kt
- domain/reminder/BillReminderManager.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | BillReminderManager.kt:125-132 | HIGH | Logic | `getMonthlyBillsTotal()` switches on `frequency.name` and checks `"YEARLY"`, but the actual enum uses `ANNUALLY` and `SEMI_ANNUALLY`. Annual and semi-annual bills therefore fall into the `else` branch and are counted as full monthly expenses. | Switch on `RecurrenceFrequency` directly and handle `ANNUALLY`/`SEMI_ANNUALLY` exhaustively. |
| 2 | BillReminderManager.kt:140-150 | HIGH | Logic | `calculateNextDate()` has the same stringly-typed frequency bug: `ANNUALLY`/`SEMI_ANNUALLY` are not handled, so those reminders advance by one month instead of one year/six months. | Replace string comparisons with an exhaustive enum `when`. |
| 3 | BillReminderManager.kt:25-29,59-64 | HIGH | Logic | The implemented urgency rules do not match the documented semantics. Due-today reminders are marked `URGENT` instead of `CRITICAL`, 2-day reminders are downgraded to `WARNING`, and 4-7 day reminders remain `INFO`. | Rework urgency thresholds so overdue/today = `CRITICAL`, 1-2 days = `URGENT`, 3-7 days = `WARNING`, else `INFO`. |
| 4 | BillReminderManager.kt:102-109,140-154 | HIGH | Logic | `markBillPaid()` advances only one interval from the stored due date. If the user pays late after missing multiple cycles, the next due date can remain in the past and keep re-triggering reminders. | Advance from `max(now, currentDueDate)` or loop until the next date is in the future. |
| 5 | BankApiIntegration.kt:68-82,87-109,141-163 | HIGH | Functional/Security | The integration reports success using fake OAuth URLs, demo tokens, and generated mock transactions. Because it returns successful `BankConnection`/`SyncResult` objects, the rest of the app can treat a non-existent bank link as real. | Gate the feature behind an explicit “not implemented” failure, or wire in a real provider before exposing it. |
| 6 | BankApiIntegration.kt:214-226 | HIGH | Logic | `mapTransactionToExpense()` converts every bank movement into `TransactionType.PURCHASE` and applies `abs(amount)`. Deposits, refunds, and transfers will be imported as positive expenses. | Preserve sign/type from the bank payload and map incoming/outgoing movements to the correct transaction type. |
| 7 | BankApiIntegration.kt:197-209 | MEDIUM | Logic | `shouldSync()` ignores `isActive` and `isConnected`, so disconnected or disabled bank connections can still be scheduled for auto-sync. | Require an active, connected account before returning `true`, and consider pausing sync after repeated failures. |
| 8 | AccountingExporters.kt:12,49,100 | MEDIUM | Concurrency | Each exporter keeps a mutable `SimpleDateFormat` field. These exporters are provided as singletons elsewhere, so concurrent exports can race and emit corrupted dates. | Use `java.time`, `ThreadLocal`, or instantiate the formatter inside the export call. **[RESOLVED BY A.8]** |
| 9 | AccountingExporters.kt:29,34-35 | HIGH | Export correctness | QuickBooks IIF export writes the category/account to both the `TRNS` and `SPL` rows. QuickBooks expects the source account on `TRNS` and the expense account on `SPL`; using the same account on both sides produces invalid/self-canceling entries. | Add a real source account parameter (bank/card/cash) and use the category only for `SPL`. |
| 10 | ExportTransaction.kt:6-12 | HIGH | Data loss | The export DTO omits currency entirely, so multi-currency expenses are serialized as bare amounts with no way for exporters to distinguish EUR from USD/GBP/etc. | Include `currency` in `ExportTransaction` and either emit it in supported formats or reject mixed-currency exports. |
| 11 | DebugData.kt:17-72 | MEDIUM | Serialization/Security | `toJson()` manually assembles JSON with partial escaping only. Backslashes/control characters in merchant names, parser names, logs, suggestions, or categories can generate invalid JSON, and raw OCR/log text is exported without redaction. | Replace string concatenation with a JSON serializer and redact account numbers / other sensitive substrings before export. |
| 12 | NotificationSeeder.kt:104-115 | MEDIUM | Data quality | `packageName` is built from display names. For `"Alpha Bank"` this becomes `com.simulation.alpha bank`, which is not a valid package name and can break routing/normalization assumptions in downstream parsers. | Map each source to a fixed valid package ID instead of lowercasing the display label. |
| 13 | NotificationSeeder.kt:46,132-145 | MEDIUM | Logic | The code claims to seed “recurring candidates”, but `generateRecurring()` emits isolated random charges instead of repeated merchant patterns. The seeded dataset usually will not exercise recurring-payment detection or reminder flows. | Generate several spaced occurrences per recurring merchant so the debug dataset actually contains recognizable recurring series. |
| 14 | ServiceDiagnostics.kt:24-41 | MEDIUM | Concurrency | The counters use unsynchronized read-modify-write operations on `SharedPreferences`. Concurrent lifecycle callbacks can overwrite each other and lose increments. | Guard writes with a lock or move the counters to an atomic/DataStore-backed implementation. **[RESOLVED BY A.8]** |
| 15 | DashboardFollowThroughEngine.kt:60-70 | MEDIUM | Logic | `generateRecommendations()` accepts `userId` but line 69 calls `thresholdCalculator.getThreshold()`, which uses the default user. Personalized thresholds are ignored for any non-default user. | Call `calculateHighAmountThreshold(userId)` instead of the default-user helper. |
| 16 | DashboardFollowThroughEngine.kt:179-183,208-210,237-240 | MEDIUM | Logic | Category and recent recommendations hardcode `PURCHASE`, and merchant recommendations omit transaction type entirely. Tapping a follow-through CTA can exclude the triggering transaction or mix in unrelated deposits/transfers. | Preserve the source transaction’s type when building every follow-through filter. |
| 17 | DatabaseBackupRepository.kt:27,53-59; DatabaseOperationResults.kt:12-16 | MEDIUM | API design | Import restart state exists in `DatabaseImportResult.SuccessNeedsRestart`, but the repository contract returns `Result<DatabaseImportSummary>` with no restart flag. This forces callers into brittle sentinel-based handling. | Collapse onto one explicit import result model that carries summary + `requiresRestart`. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | Expense model → ExportTransaction → AccountingExporters | HIGH | Data loss | The export pipeline strips important accounting semantics before formatting. `ExportTransaction` carries gross amount only, so ownership/split semantics and currency context are lost before any exporter runs. | Expand the DTO to include currency and an explicit export amount (or ownership flags), and block unsupported cases instead of silently flattening them. |
| 2 | Export repository path ↔ streaming ViewModel export path | MEDIUM | Duplication | There are two separate export implementations in the app: one buffers the entire export in memory and one streams pages. Format/escaping fixes now have to be maintained twice and can drift. | Consolidate on a single export service used by both repository and UI flows. |
| 3 | `domain.debug.*` → Android framework/resources/preferences | MEDIUM | Architecture | Multiple “domain” classes depend directly on `Context`, `SharedPreferences`, and Android string resources. That couples domain logic to Android and makes these classes hard to test or reuse. | Move Android-specific concerns to data/platform layers and keep domain APIs platform-neutral. |
| 4 | Backup import UI → repository contract → result models | MEDIUM | Contract mismatch | Backup import exposes two incompatible success models: a generic `Result<DatabaseImportSummary>` and a sealed `DatabaseImportResult` with restart semantics. This increases the chance that UI and repository code interpret success differently. | Standardize import/export status on a single result type shared across layers. |

## Summary
- Total issues: 17
- Critical: 0, High: 8, Medium: 9, Low: 0
- Files with issues: 10/13

## Key Patterns
- Stringly-typed branching (`frequency.name`) is causing real scheduling and calculation bugs.
- Placeholder integrations return success instead of failing closed, which makes incomplete features look production-ready.
- Manual serialization is used where structured codecs should be used, leading to malformed output and data-exposure risk.
- Export logic is duplicated across layers and loses domain semantics before formatting.
- Several classes under `domain/` are tightly coupled to Android platform APIs, weakening layering and testability.
