# Cost-agregator deep coverage inspection v5

Target commit: `31d9e1bbb10976b648788b91fd1922aa3564759a`  
Review type: GitHub static inspection, not local test execution.

## 1. Main correction from previous pass

I previously treated some lifecycle areas as possibly missing. After deeper inspection:

These tests **do exist**:

- `domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt`
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt`
- `domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt`

But they are mostly **mock-based coordinator tests**.

They validate method orchestration, for example:

- validator called
- DAO insert called
- event insert called
- side effects invoked
- invalid input rejected

That is useful, but it still does **not** prove:

- real Room rows are inserted correctly
- foreign keys work
- event tables persist correctly
- dashboard/analytics see the result
- duplicate indexes actually protect the DB
- restore maintenance mode blocks real writes
- receipt links survive real persistence
- recurring planned/actual matching avoids double count in real query paths

So the updated recommendation is:

> Keep the existing lifecycle tests, but add DB-backed contract tests beside them.

Recommended names:

```text
TransactionLifecycleCoordinatorDbContractTest.kt
ReceiptLifecycleCoordinatorDbContractTest.kt
RecurringLifecycleCoordinatorDbContractTest.kt
```

---

# 2. Biggest new coverage finding: DAO coverage is incomplete

`AppDatabase.kt` exposes many DAOs. The visible Android DAO test folder only contains direct tests for a subset.

Currently visible DAO tests include:

- `AiArtifactDaoTest`
- `AiChatMessageDaoTest`
- `AiChatSessionDaoTest`
- `BudgetDaoTest`
- `CategoryDaoTest`
- `ExchangeRateDaoTest`
- `ExpenseDaoTest`
- `ExpenseGroupDaoTest`
- `GroupMemberDaoTest`
- `MerchantLocationDaoTest`
- `MerchantNormalizationDaoTest`
- `PendingReviewDaoTest`
- `RecommendationDaoTest`
- `RecurringExpenseDaoTest`
- `SavingsGoalDaoTest`
- `ScannedReceiptDaoTest`
- `UserCorrectionDaoTest`
- `WarrantyDaoTest`
- plus parity/stress/complex-query tests

But `AppDatabase` has many more DAOs.

## Missing or not clearly directly covered DAO tests

Add direct DAO contract tests for:

```text
RawNotificationDaoTest
BlockedPackageDaoTest
MerchantCategoryDaoTest
SourceStatsDaoTest
ManualRecurringExpenseDaoTest
PlannedExpenseDaoTest
ReceiptItemCategorizationDaoTest
ReturnWindowDaoTest
SubscriptionPriceHistoryDaoTest
SubscriptionUsageDaoTest
MileageTrackingDaoTest
GroupExpenseDaoTest
BudgetForecastDaoTest
InvestmentDaoTest
InvestmentValueDaoTest
SplitTemplateDaoTest
SplitItemAssignmentDaoTest
AnomalyAlertDaoTest
PromptStateDaoTest
HealthScoreHistoryDaoTest
SavingsSweepPlanDaoTest
SubscriptionCandidateDaoTest
BudgetAdjustmentDaoTest
StressForecastSnapshotDaoTest
SpendingPersonalityProfileDaoTest
EmailReceiptDaoTest
SpendingChallengeDaoTest
TransactionEventDaoTest
ReceiptEventDaoTest
ReceiptExpenseLinkDaoTest
RecurringOccurrenceDaoTest
RecurringReminderDeliveryDaoTest
RecurringLifecycleEventDaoTest
PrivacyAuditDaoTest
BackgroundJobRunDaoTest
```

Some of these may be indirectly covered through repository tests, but they still need direct DAO tests because they represent important persisted contracts.

## DAO test minimum contract

Every DAO test should cover:

1. insert
2. query by primary key
3. query by owner/foreign key
4. ordering
5. update/delete if allowed
6. uniqueness/index behavior
7. foreign key behavior
8. retention/cleanup behavior if applicable

## Add DAO coverage guardrail

Create a script:

```text
scripts/testing/check-dao-test-coverage.kts
```

Rule:

> Every `abstract fun xyzDao()` in `AppDatabase` must have `XyzDaoTest`, or be explicitly allowlisted with a reason.

This will stop new DAOs from being added silently without tests.

---

# 3. Domain package coverage gaps from package comparison

The production `domain` folder contains many packages that do not have matching test packages.

Production has packages such as:

```text
backup
common
config
core
diagnostics
dto
lifestyle
negotiation
notification
performance
privacy
service
subscription
text
workers
```

The visible test `domain` tree does not show matching direct test packages for many of these.

## Add direct tests for these packages

### `domain/core`

This is important because architecture now treats typed money/time as critical.

Add:

```text
domain/core/money/MoneyAmountTest.kt
domain/core/money/MoneyAggregateTest.kt
domain/core/money/ConvertedMoneyTest.kt
domain/core/money/MoneyBucketTest.kt
domain/core/money/ConversionFailureTest.kt
domain/core/time/PeriodRangeTest.kt
domain/core/time/PeriodKindTest.kt
```

Must test:

- no raw cross-currency addition
- bucket aggregation
- partial conversion
- rounding
- stale/missing exchange rate failure
- day/week/month boundaries
- DST and leap day
- half-open interval behavior

### `domain/config`

Add:

```text
domain/config/AppConfigTest.kt
```

Test:

- default values
- invalid config rejected
- feature flags
- environment overrides
- safe fallback behavior

### `domain/diagnostics`

Add:

```text
domain/diagnostics/DatabaseIntegrityScannerTest.kt
```

Seed violations and assert detection of:

- duplicate active budgets
- duplicate category names
- orphan receipt links
- duplicate dedupe keys
- invalid currency codes
- missing lifecycle events
- stale running background jobs

### `domain/dto`

Add mapper/serialization contract tests for DTOs that cross feature boundaries.

Test:

- default values
- unknown enum handling
- backward-compatible JSON
- nullability
- currency/date fields

### `domain/negotiation`

Add:

```text
domain/negotiation/SmartBillNegotiationEngineTest.kt
```

Test:

- bill increase detected
- negotiation candidate generated
- no-offer state
- provider failure
- privacy/AI denied behavior
- not enough history = no recommendation

### `domain/privacy`

Add direct contract tests for:

```text
CloudAiPrivacyGateTest
LocationPrivacyGateTest
NotificationPrivacyGateTest
BackupPrivacyGateTest
CompositePrivacyGateTest
RedactionSanitizerTest
PrivacyAuditLoggerTest
```

### `domain/workers` and `domain/service`

Add tests for:

- idempotency policy
- retry policy
- restore maintenance blocking
- notification delivery contracts
- stale run recovery

---

# 4. DI / Hilt coverage is still a major hole

Production has many DI modules:

```text
AiModule
BackupRepositoryModule
CashFlowModule
CurrencyModule
DaoModule
DatabaseModule
DispatchersModule
EmailIngestionModule
ExportModule
GroupsModule
NaturalLanguageModule
NetworkModule
ParserModule
PrivacyModule
SecurityModule
ServiceModule
SubscriptionModule
TaxModule
TimeModule
...
```

But the visible test roots do not show a direct `di` test package, and `androidTest` appears to contain only `data`.

## Add Hilt graph tests

Create:

```text
app/src/androidTest/java/.../di/HiltGraphSmokeTest.kt
```

Test that these resolve:

- `AppDatabase`
- all DAOs from `DaoModule`
- core repositories
- lifecycle coordinators
- workers
- notification service dependencies
- AI providers
- backup/restore services
- privacy gates
- key ViewModels if possible

Also add:

```text
app/src/test/java/.../di/ModuleContractTest.kt
```

For modules that can be tested on JVM with fakes.

This catches broken bindings that unit tests with mocks will never catch.

---

# 5. Android/instrumented coverage is too narrow

`androidTest` mainly has data/database DAO tests. That is good, but not enough for an Android app with:

- NotificationListenerService
- WorkManager
- Hilt
- DataStore
- encrypted key storage
- file provider / camera receipts
- backup/restore filesystem behavior
- permissions
- Compose navigation

## Add Android smoke tests

Recommended:

```text
androidTest/di/HiltGraphSmokeTest.kt
androidTest/workers/WorkerConstructionSmokeTest.kt
androidTest/service/NotificationCapturePermissionSmokeTest.kt
androidTest/security/SecureKeyStorageAndroidTest.kt
androidTest/privacy/PrivacySettingsDataStoreAndroidTest.kt
androidTest/backup/BackupRestoreFilesystemAndroidTest.kt
androidTest/ui/NavigationSmokeTest.kt
```

Keep these small. The goal is to prove Android wiring works, not to create hundreds of flaky UI tests.

---

# 6. UI screen coverage gaps remain

The architecture inventory lists routed or feature screens including:

- backup
- bank connections
- category management
- investment portfolio
- natural language search
- bill negotiation
- privacy settings
- recurring expenses
- bill reminders
- tax configuration

The visible unit test inventory does not show direct ViewModel tests for several of these.

## Add ViewModel/screen state tests

Add:

```text
ui/screens/backup/BackupRestoreViewModelTest.kt
ui/screens/bank/BankConnectionsViewModelTest.kt
ui/screens/categories/CategoryViewModelTest.kt
ui/screens/investment/InvestmentViewModelTest.kt
ui/screens/naturallanguage/NaturalLanguageSearchViewModelTest.kt
ui/screens/negotiation/BillNegotiationViewModelTest.kt
ui/screens/privacysettings/PrivacySettingsViewModelTest.kt
ui/screens/recurring/RecurringExpensesViewModelTest.kt
ui/screens/reminder/BillRemindersViewModelTest.kt
ui/screens/tax/TaxConfigurationViewModelTest.kt
```

Each should test:

- loading
- empty
- success
- error
- permission denied
- privacy denied
- partial data
- user action
- persistence effect

Also add:

```text
ui/navigation/NavigationDestinationRouteContractTest.kt
```

Assert:

- every destination roundtrips
- parameterized routes serialize/deserialize
- deep links map correctly
- every routed production screen appears in the route catalog

---

# 7. Import/export coverage needs roundtrip tests

There are export/import-related tests, but the app needs stronger financial roundtrip tests.

Add these scenarios:

## `csv_export_import_roundtrip_contract`

Seed:

- multi-currency expenses
- special CSV characters
- groups/splits
- tax/business fields
- refunds/transfers

Assert:

- CSV escaping safe
- import into fresh DB succeeds
- dashboard totals match
- category totals match
- business/tax fields preserved
- unsupported fields reported explicitly

## `accounting_export_contract`

Assert:

- expense source retained
- tax category retained
- converted and original currency fields retained
- receipt links represented correctly
- no raw private text leaks

## `backup_restore_full_app_roundtrip`

Seed:

- expenses
- receipts
- receipt links
- lifecycle events
- recurring occurrences
- groups
- exchange rates
- privacy audit events
- background job runs

Assert:

- backup restores into fresh DB
- dashboard equals original
- analytics equals original
- receipt links preserved
- recurring state preserved
- workers paused during restore
- restore journal completed

---

# 8. New scenario additions for master plan

Keep all previous scenarios. Add these.

## Scenario 27 — `all_dao_contract_matrix`

Purpose:

Prove every DAO in `AppDatabase` has a minimum persistence contract.

Expected:

- every DAO has a direct test or allowlist
- new DAO without test fails CI

Priority: highest.

---

## Scenario 28 — `hilt_graph_all_modules_smoke`

Purpose:

Prove dependency graph can actually start.

Expected:

- database resolves
- all DAOs resolve
- core repositories resolve
- lifecycle coordinators resolve
- workers resolve
- privacy gates resolve
- backup services resolve
- AI providers resolve

Priority: highest.

---

## Scenario 29 — `core_money_time_boundary_contract`

Purpose:

Protect the new money/time primitives.

Expected:

- no cross-currency mixing
- partial aggregate warnings visible
- stale/missing rate failure visible
- month/week/day boundaries correct
- DST/leap-day safe

Priority: highest.

---

## Scenario 30 — `privacy_settings_datastore_runtime_gate`

Purpose:

Connect privacy settings to actual runtime behavior.

Input:

- cloud AI disabled
- location disabled
- notification capture disabled
- backup export disabled

Assert:

- settings persist
- gates deny correctly
- audit events written
- providers/services not called
- redaction happens before allowed cloud calls

Priority: high.

---

## Scenario 31 — `notification_listener_permission_restart_dedupe`

Purpose:

Android-level reliability for notification capture.

Input:

- permission denied
- listener disabled
- listener enabled
- service restart
- duplicate notification

Assert:

- denied state does not write expense
- enabled state captures
- restart does not duplicate
- duplicate fingerprint blocks duplicate
- review state created for low confidence

Priority: high.

---

## Scenario 32 — `bank_sync_failure_recovery_lifecycle`

Purpose:

Bank integration failure safety.

Input:

- expired token
- partial sync
- duplicate bank transaction
- low-confidence merchant
- approved review

Assert:

- auth failure surfaced
- partial sync does not corrupt DB
- duplicate skipped
- approved item creates expense through lifecycle
- dashboard only includes approved non-duplicates

Priority: high.

---

## Scenario 33 — `diagnostics_seeded_integrity_violations`

Purpose:

Prove diagnostics can catch broken DB states.

Seed:

- orphan receipt link
- duplicate active budget
- duplicate current group user
- invalid currency
- stale running job
- missing lifecycle event

Assert:

- every violation reported
- safe/healthy DB reports no false positives

Priority: medium-high.

---

## Scenario 34 — `ui_route_viewmodel_matrix_smoke`

Purpose:

Prevent routed UI screens from existing without a test.

Expected:

- every route has ViewModel or explicit no-ViewModel reason
- every ViewModel has state-contract test
- every parameterized destination roundtrips
- every deep link resolves

Priority: medium-high.

---

# 9. Updated priority order

## P0 — CI/repo safety

1. Add real `.github/workflows/ci.yml` if absent.
2. Fix schema verifier to use `APP_DATABASE_SCHEMA_VERSION = 113`, not default `92`.
3. Add `.gitignore` blocks for DB/backups/generated dumps.
4. Add secret scan.
5. Add ignored-test-count guard.
6. Add lifecycle bypass guard.
7. Add DAO coverage guard.

## P1 — DAO and DB contracts

1. Add missing DAO tests.
2. Add `all_dao_contract_matrix`.
3. Add migration tests for v104–v113.
4. Add fresh-install vs migrated v113 parity.
5. Add direct Room-builder bypass guard.

## P2 — DB-backed lifecycle tests

1. `TransactionLifecycleCoordinatorDbContractTest`
2. `ReceiptLifecycleCoordinatorDbContractTest`
3. `RecurringLifecycleCoordinatorDbContractTest`
4. connect to dashboard/analytics assertions

## P3 — DI and Android smoke

1. Hilt graph smoke.
2. Worker construction smoke.
3. Notification permission/restart smoke.
4. Secure key storage Android smoke.
5. DataStore privacy settings Android smoke.
6. backup/restore filesystem smoke.

## P4 — domain package gaps

1. `domain/core/money`
2. `domain/core/time`
3. `domain/config`
4. `domain/diagnostics`
5. `domain/dto`
6. `domain/negotiation`
7. `domain/privacy`
8. `domain/workers`
9. `domain/service`

## P5 — UI route/ViewModel matrix

1. Add missing ViewModel tests.
2. Add route roundtrip tests.
3. Add deep-link tests.
4. Add route-to-screen coverage guard.

## P6 — roundtrip scenarios

1. CSV export/import roundtrip.
2. accounting export contract.
3. full backup/restore app roundtrip.
4. bank sync failure recovery.
5. email receipt to receipt lifecycle to analytics.

---

# 10. Revised definition of done

The broader coverage plan is done when:

1. Every `AppDatabase` DAO has a direct DAO test or explicit allowlist.
2. Lifecycle coordinator tests include both mock tests and DB-backed contract tests.
3. Transaction lifecycle proves event log + dashboard/analytics effects.
4. Receipt lifecycle proves event log + receipt-expense links + no double count.
5. Recurring lifecycle proves occurrence/reminder/event rows + no double count.
6. Hilt graph smoke passes.
7. Worker construction smoke passes.
8. Android notification permission/restart smoke passes.
9. Privacy settings persist and affect runtime gates.
10. Core money/time primitives have direct boundary tests.
11. Domain config/diagnostics/dto/privacy/negotiation/workers packages have tests.
12. All routed UI surfaces have ViewModel state tests or explicit no-ViewModel reason.
13. Navigation route roundtrip/deep-link tests pass.
14. CSV/accounting/backup roundtrip scenarios pass.
15. CI generates a coverage matrix automatically.
16. CI fails if a new DAO, route, ViewModel, lifecycle bypass, or schema version appears without coverage.

---

# Sources used

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/31d9e1bbb10976b648788b91fd1922aa3564759a

- Production root package:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker

- Test root package:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker

- Android test root package:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/androidTest/java/com/yourname/expensetracker

- AppDatabase / DAO list / DB version:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- Android DAO tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/androidTest/java/com/yourname/expensetracker/data/database/dao

- Existing lifecycle coordinator tests:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt

- Segment map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/CODEBASE_SEGMENTS.md

- App Gradle config:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/build.gradle.kts

- `.gitignore`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/.gitignore

- Test path inventory:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/_all_rel_paths.txt