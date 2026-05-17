# Database Write Ownership Map

Part of: Global Write/Read/Restore Barrier — PR 5

Every table family has exactly one approved write owner.
Direct DAO mutation outside this map is a violation caught by the static guard (PR 6/10).

---

## Table family → approved writer

| Table family | Approved writer(s) | Status |
|---|---|---|
| expenses, transaction_events | `TransactionLifecycleCoordinator` | ✅ |
| expenses (backfill only) | `ExpenseRepository` guarded methods | ⚠️ migrate to `ExpenseWriteStore` (PR 11) |
| raw_notifications, pending_reviews | `NotificationRepository` | ✅ |
| raw_notifications, pending_reviews (purge) | `DataRetentionWorker` | ⚠️ migrate to `RetentionCoordinator` |
| scanned_receipts, receipt_events, email_receipts, receipt_expense_links | `ReceiptLifecycleCoordinator` | ✅ |
| receipt_expense_links (link/unlink) | `ReceiptLinkService` | ⚠️ must use `DatabaseWriteBarrier` (PR 7) |
| recurring_expenses, recurring_lifecycle_events | `RecurringRuleLifecycleCoordinator` | ✅ |
| recurring_occurrences | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer` | ✅ |
| recurring_reminder_deliveries | `ReminderDeliveryCoordinator` | ✅ |
| budgets, budget_adjustments | `BudgetRepository` | ✅ |
| budget_forecasts | `BudgetForecastingEngine` | ✅ |
| planned_expenses | `PlannedExpenseRepository` | ✅ |
| bank_connections | `BankConnectionLifecycleCoordinator` | ⚠️ create coordinator (PR 7) |
| investments, investment_transactions, investment_values | `InvestmentRepository` | ✅ |
| savings_goals, savings_sweep_plans | `SavingsGoalRepository` | ✅ |
| subscription_candidates, subscription_price_history, subscription_usage | `SubscriptionRepository` | ✅ |
| warranties, warranty_lifecycle_events | `WarrantyRepository` | ✅ |
| expense_groups, group_members, group_expenses, group_settlements | `GroupLifecycleCoordinator` | ✅ |
| categories | `CategoryRepository` | ✅ |
| merchant_categories, merchant_normalizations, merchant_locations | `MerchantCategoryRepository` | ✅ |
| spending_challenges | `SpendingChallengeRepository` | ✅ |
| exchange_rates | `ExchangeRateRepository` | ✅ |
| ai_artifacts, ai_chat_messages, ai_chat_sessions | `AiArtifactRepository` | ✅ |
| background_job_runs | `WorkerRunLoggerImpl` | ✅ |
| pipeline_diagnostic_events | `PipelineDiagnosticEventRepository` | ⚠️ route through `MaintenanceSafeDiagnosticSink` (PR 9) |
| DB file operations | `DatabaseBackupRepositoryImpl` | ✅ (file-level only, under maintenance mode) |

---

## Rules

1. **One owner per table family.** If two classes write the same table, one must delegate to the other.
2. **Every write entrypoint checks `DatabaseWriteBarrier`.** No exception except Room migrations.
3. **Workers do not write DAOs directly.** They call coordinator/repository methods.
4. **Debug-only writes** require `BuildConfig.DEBUG` guard AND `writeBarrier.checkWritesAllowed()`.
5. **Entries marked ⚠️** are temporary allowlist exceptions. Each has a `allowed_until` target in `config/db_access_allowlist.yml`.

---

## Not approved

| Pattern | Reason |
|---|---|
| UI / ViewModel direct DAO calls | No lifecycle coordination, no barrier |
| Worker direct DAO calls (outside allowlist) | Must go through coordinator |
| Email service direct DAO writes | Must delegate to `ReceiptLifecycleCoordinator` |
| Any DAO write after DB file swap without fresh Room instance | Stale Room — use `AppDatabase.fileBuilder()` |

---

## Enforcement

- **Runtime:** `DatabaseWriteBarrier.checkWritesAllowed()` throws `DatabaseAccessBlockedException` in all non-NORMAL modes.
- **Static (warning):** `scripts/verify_db_access_boundaries.py` reports violations (PR 6).
- **Static (CI failure):** Same script exits non-zero on new violations (PR 10).
- **Config:** `config/db_access_allowlist.yml` is the source of truth for the static guard.
