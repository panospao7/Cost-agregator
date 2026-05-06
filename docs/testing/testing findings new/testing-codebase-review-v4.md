# Cost-agregator testing/codebase review v4 — coverage expansion

Target commit: `31d9e1bbb10976b648788b91fd1922aa3564759a`  
Review type: static GitHub/code inventory review, not local execution.

## Executive update

The previous plan was directionally correct, but this pass found **additional high-value gaps**:

1. Newer DB entities/DAOs from phases 3–10 are not equally covered.
2. Transaction/receipt/recurring lifecycle packages appear under-tested directly.
3. Privacy/backup/restore primitives need their own contract tests, not only repository smoke tests.
4. UI coverage is uneven: many routed screens have no matching ViewModel/screen tests.
5. Schema snapshot verification should not only check “latest exists”; it should check migration-start versions and known version jumps.
6. `.gitignore` still does not protect DB backups / repo dumps / session dumps from being committed.

---

## 1. New highest-risk coverage gaps

## A. New lifecycle DAOs are probably missing direct DAO tests

`AppDatabase.kt` currently includes newer entities/DAOs:

- `TransactionEvent`
- `ReceiptEvent`
- `ReceiptExpenseLink`
- `RecurringOccurrence`
- `RecurringReminderDelivery`
- `RecurringLifecycleEvent`
- `PrivacyAuditEvent`
- `BackgroundJobRun`

But the visible `androidTest/data/database/dao` list does not show direct tests for:

- `TransactionEventDaoTest`
- `ReceiptEventDaoTest`
- `ReceiptExpenseLinkDaoTest`
- `RecurringOccurrenceDaoTest`
- `RecurringReminderDeliveryDaoTest`
- `RecurringLifecycleEventDaoTest`
- `PrivacyAuditDaoTest`
- `BackgroundJobRunDaoTest`

These are not minor tables. They are the backbone of lifecycle correctness, restore safety, privacy auditability, and worker idempotency.

### Add immediately

Create these DAO tests:

```text
app/src/androidTest/java/.../data/database/dao/TransactionEventDaoTest.kt
app/src/androidTest/java/.../data/database/dao/ReceiptEventDaoTest.kt
app/src/androidTest/java/.../data/database/dao/ReceiptExpenseLinkDaoTest.kt
app/src/androidTest/java/.../data/database/dao/RecurringOccurrenceDaoTest.kt
app/src/androidTest/java/.../data/database/dao/RecurringReminderDeliveryDaoTest.kt
app/src/androidTest/java/.../data/database/dao/RecurringLifecycleEventDaoTest.kt
app/src/androidTest/java/.../data/database/dao/PrivacyAuditDaoTest.kt
app/src/androidTest/java/.../data/database/dao/BackgroundJobRunDaoTest.kt
```

Each should test:

- insert
- query by owner key
- ordering by timestamp
- foreign key behavior
- uniqueness/index behavior
- retention/delete rules where applicable

### CI guardrail

Add a script:

```text
scripts/testing/check-dao-test-coverage.kts
```

Rule:

> Every `abstract fun xxxDao()` in `AppDatabase` must either have `XxxDaoTest` or be explicitly allowlisted.

---

## B. Transaction lifecycle has weak direct coverage

The architecture says all expense CUD must go through `TransactionLifecycleCoordinator`.

But the test inventory does not show a clear `domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt`.

Existing e2e tests indirectly touch expense creation, but that is not enough.

### Add

```text
domain/transaction/lifecycle/TransactionLifecycleCoordinatorDbContractTest.kt
```

Use in-memory Room through `AppDatabase.inMemoryBuilder()`.

Test cases:

1. create manual expense
2. create notification expense
3. create duplicate by external ID / raw notification ID
4. update amount/category/merchant
5. soft or hard delete according to current contract
6. create event log entries
7. side effects called once
8. recurring occurrence auto-link attempted
9. dashboard total changes after CUD

Assertions:

- expense row exists
- `source` is correct
- `transaction_events` sequence is correct
- duplicate is skipped
- raw DAO insert bypass is not used in production path

---

## C. Receipt lifecycle has weak direct coverage

Receipt parsing tests exist. Email receipt tests exist. Receipt processing e2e exists.

But the lifecycle-specific package needs a contract test:

```text
domain/receipt/lifecycle/ReceiptLifecycleCoordinatorDbContractTest.kt
```

Test:

1. camera receipt
2. email receipt
3. bank statement receipt
4. duplicate by image hash/text fingerprint/semantic fingerprint
5. receipt-expense link
6. document-type-gated side effects
7. status progression
8. immutable event log

Assertions:

- `scanned_receipts.processingStatus`
- `receipt_events`
- `receipt_expense_links`
- no duplicate receipt
- no duplicate expense
- warranty/price protection only for eligible receipt type

---

## D. Recurring lifecycle has weak direct coverage

There are recurring engine tests, but lifecycle tables added in newer phases need contract tests.

Add:

```text
domain/recurring/lifecycle/RecurringLifecycleCoordinatorDbContractTest.kt
```

Test:

1. rule expansion
2. occurrence materialization
3. reminder delivery creation
4. linking actual expense to planned occurrence
5. no double count planned + actual
6. lifecycle event emission
7. idempotent re-run

Assertions:

- `recurring_occurrences`
- `recurring_reminder_deliveries`
- `recurring_lifecycle_events`
- linked actual expense ID
- future forecast includes future planned only
- current dashboard includes actual once

---

## E. Privacy subsystem is much larger than current tests suggest

Production has:

- `BackupPrivacyGate`
- `CloudAiPrivacyGate`
- `CompositePrivacyGate`
- `LocationPrivacyGate`
- `NotificationPrivacyGate`
- `RedactionSanitizer`
- `PrivacyAuditLogger`
- `PrivacySettingsRepository`
- `BackupEncryptionService`
- `DataRetentionWorker`
- `ExportAnonymizer`
- `PrivacySettingsRepositoryImpl`

The test inventory does not show a matching `domain/privacy` or `data/privacy` test package.

### Add

```text
domain/privacy/PrivacyGateContractTest.kt
domain/privacy/RedactionSanitizerTest.kt
data/privacy/PrivacySettingsRepositoryImplTest.kt
data/privacy/PrivacyAuditLoggerImplTest.kt
data/privacy/BackupEncryptionServiceTest.kt
data/privacy/ExportAnonymizerTest.kt
data/privacy/DataRetentionWorkerTest.kt
ui/screens/privacysettings/PrivacySettingsViewModelTest.kt
```

High-value scenarios:

1. cloud AI denied → no provider call → audit event written
2. notification capture denied → listener skips DB write
3. external geocoding denied → no HTTP call
4. backup export denied → no raw export
5. redaction enabled → notification/OCR text sanitized before cloud AI
6. data retention worker purges raw notification/OCR text but keeps parsed financial records
7. privacy setting persisted and affects next app session

---

## F. Backup/restore primitives need low-level contract tests

Production has:

- `CostbackupBundle`
- `BackupVerifier`
- `RestoreJournal`
- `RestoreMaintenanceMode`

There is `DatabaseBackupRepositoryImplTest`, but the primitives need their own tests.

### Add

```text
data/backup/CostbackupBundleTest.kt
data/backup/BackupVerifierTest.kt
data/backup/RestoreJournalTest.kt
data/backup/RestoreMaintenanceModeTest.kt
```

Test matrix:

#### `CostbackupBundleTest`

- valid bundle roundtrip
- wrong password
- unsupported version
- invalid magic header
- checksum mismatch
- missing manifest
- tampered ciphertext
- receipt file preservation

#### `BackupVerifierTest`

- exact table count match
- missing required table
- FK violation
- integrity check failure
- optional/cache table absent is allowed
- event-log tables verified

#### `RestoreJournalTest`

- non-destructive crash cleanup
- destructive crash requires critical recovery
- recovered swap
- failed restore journal persists reason

#### `RestoreMaintenanceModeTest`

- workers cancelled by tag
- writes blocked in restore modes
- writes allowed in normal/exporting modes
- restart-required mode blocks writes until explicit reset

---

## G. Schema snapshot verification needs smarter logic

Current facts:

- `AppDatabase` version is `113`.
- Schema directory visibly includes `100.json`–`113.json`.
- It also includes many `33.json`–`96.json` files.
- It is missing some intermediate versions visible from the directory listing, for example `54`, `55`, `58`, `61`, `62`, `63`, `66`, and `97`–`99`.
- `AppDatabase.ALL_MIGRATIONS` includes migrations such as `54_55`, `55_56`, `58_59`, `61_62`, `62_63`, `63_64`, `66_67`, and a jump `96_100`.

So the verifier must not use naive `1..current` unless you actually commit every old schema. But it also must not silently ignore migration-start versions that are still supported.

### Replace verifier logic

Expected schemas should be:

```text
supportedMigrationStartVersions = starts of ALL_MIGRATIONS
latestVersion = APP_DATABASE_SCHEMA_VERSION
expected = supportedMigrationStartVersions + latestVersion
```

Then explicitly allow known unavailable historical versions only if you truly no longer support testing those starts.

Recommended policy:

```text
minSupportedSchemaForMigrationTests = 33
expected = migrationStartVersions.filter { it >= 33 } + 113
knownUnsupportedSnapshotVersions = setOf(97, 98, 99) // because migration jumps 96→100
```

But do not silently miss `54`, `55`, `58`, `61`, `62`, `63`, `66` if corresponding migrations exist and you intend to test them.

### Add migration tests for latest risky migrations

Add targeted tests for:

- `104_105` invariant indexes
- `105_106` background job table
- `106_107` rebuilds
- `107_108` planned expenses rebuild
- `108_109` lifecycle/receipt/recurring event/link tables
- `109_110` raw notification fingerprint + rawNotificationId uniqueness
- `110_111` historical exchange-rate unique index + nullable pending review amount
- `111_112` budget category FK `RESTRICT`
- `112_113` case-insensitive category name uniqueness

---

## H. In-memory database tests may bypass the production fresh-install callback

`AppDatabase.inMemoryBuilder()` applies the configured builder and fresh-install callback.

Risk: tests that call `Room.inMemoryDatabaseBuilder(...)` directly may miss production supplemental indexes and fresh-install invariants.

### Add guardrail

Search tests for direct Room builder usage:

```text
Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
Room.databaseBuilder(context, AppDatabase::class.java, ...)
```

Allow only:

- `AppDatabaseTestFactory`
- migration tests
- special fresh-install parity tests

All other tests should use:

```kotlin
AppDatabase.inMemoryBuilder(context)
```

or the official fixture wrapper around it.

---

## 2. UI coverage gaps found by comparing `ui/screens` to test inventory

Production has routed screen folders including:

- `backup`
- `bank`
- `categories`
- `investment`
- `naturallanguage`
- `negotiation`
- `privacysettings`
- `recurring`
- `reminder`
- `tax`

The visible unit test inventory has no obvious direct tests for several of these UI surfaces.

### Add missing ViewModel/screen contract tests

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

Each ViewModel test should cover:

- loading
- empty
- success
- error
- permission/privacy denied
- partial data
- user action
- persistence effect

### Add route smoke test

```text
ui/navigation/NavigationDestinationRouteContractTest.kt
```

Assert:

- every route serializes/deserializes
- parameterized routes roundtrip
- deep links map to expected route
- unknown route fails safely
- all production screen folders with routes appear in the route catalog

---

## 3. Domain package coverage gaps

Based on the domain folder map and test inventory, add direct tests for:

```text
domain/core/money/*
domain/core/time/*
domain/config/*
domain/diagnostics/*
domain/dto/*
domain/notification/*
domain/performance/*
domain/service/*
domain/transaction/*
domain/recurring/lifecycle/*
domain/receipt/lifecycle/*
domain/workers/*
```

### Especially important

#### `domain/core/money`

The architecture designates `MoneyAmount` and `MoneyAggregate` as approved types. The old ignored `domain/util/MoneyTest.kt` is not enough.

Add:

```text
domain/core/money/MoneyAmountTest.kt
domain/core/money/MoneyAggregateTest.kt
domain/core/money/ConvertedMoneyTest.kt
domain/core/money/MoneyBucketTest.kt
domain/core/money/ConversionFailureTest.kt
```

Test:

- no cross-currency addition
- safe aggregation by bucket
- partial conversion flag
- failure reasons
- formatting does not hide partials
- rounding rules

#### `domain/core/time`

Add:

```text
domain/core/time/PeriodKindToPeriodRangeTest.kt
domain/core/time/PeriodRangeTest.kt
```

Test:

- day/week/month boundaries
- DST
- leap day
- custom range requires explicit bounds
- half-open interval behavior

#### `domain/diagnostics`

Add:

```text
domain/diagnostics/DatabaseIntegrityScannerTest.kt
```

Seed DB violations and assert detection of:

- duplicate active budgets
- duplicate current group user
- duplicate group-expense links
- duplicate planned occurrence keys
- raw notification fingerprint duplicates
- invalid currency
- orphaned warranties
- orphaned receipt links

---

## 4. Add these new canonical scenarios to the master plan

The previous scenario list should remain. Add these for broader coverage.

## Scenario 19 — `privacy_settings_to_runtime_gate`

Covers:

- privacy settings UI
- DataStore repository
- privacy gates
- audit logger
- AI/location/notification/backup callers

Input:

- cloud AI disabled
- external geocoding disabled
- notification capture disabled
- encrypted backup disabled
- sensitive notification/OCR/query text

Assert:

- settings persist
- gates deny expected capabilities
- audit events written
- cloud/location/notification/backup side effects not called
- redaction occurs before any allowed cloud call

Priority: highest.

---

## Scenario 20 — `backup_bundle_low_level_roundtrip`

Covers:

- `CostbackupBundle`
- `BackupVerifier`
- receipt file packaging
- encryption/checksums

Input:

- small seeded DB
- fake receipt files
- password
- tampered bundle variants

Assert:

- valid backup restores
- wrong password fails
- checksum mismatch fails
- missing manifest fails
- table count verification works
- receipt files preserved

Priority: highest.

---

## Scenario 21 — `category_uniqueness_budget_restrict`

Covers:

- category repository
- category DAO
- migration `112_113`
- budget FK `RESTRICT`

Input:

- categories: `Food`, `food`, `FOOD`
- active category budget
- attempt to delete category with active budget

Assert:

- case-insensitive duplicates deduplicated or rejected
- unique NOCASE index exists
- deleting category with active budget fails
- budget does not silently become overall budget

Priority: high.

---

## Scenario 22 — `background_job_run_idempotency`

Covers:

- `BackgroundJobRunDao`
- `WorkerSpec`
- worker execution logging
- restore maintenance pause

Input:

- schedule/run daily briefing, receipt matching, location backfill
- simulate failure/retry
- simulate restore mode

Assert:

- run rows inserted/updated
- stale running rows detected
- retry reason saved
- restore mode blocks work
- workers do not double-run side effects

Priority: high.

---

## Scenario 23 — `natural_language_voice_to_navigation`

Covers:

- speech gateway
- natural language search
- AI query interpretation
- navigation resolver
- dashboard/query result

Input:

- voice text: “show groceries last month”
- ambiguous query
- denied cloud AI variant

Assert:

- voice gateway emits text
- query interpreted
- route generated
- fallback works when cloud AI denied
- no raw sensitive query stored if privacy forbids

Priority: medium-high.

---

## Scenario 24 — `investment_portfolio_multicurrency_contract`

Covers:

- investment tracking
- currency
- dashboard card/UI state

Input:

- EUR home currency
- USD stock
- EUR ETF
- crypto holding with missing price
- stale price

Assert:

- holding values correct
- total converted where possible
- partial flag/warning for missing price/rate
- gain/loss correct
- dashboard/investment UI state exposes warning

Priority: medium.

---

## Scenario 25 — `reminder_worker_recurring_contract`

Covers:

- bill reminders
- recurring lifecycle
- worker idempotency
- notification delivery

Input:

- recurring bill due in 7 days
- reminder worker run twice
- paid actual expense linked

Assert:

- one reminder delivery
- no duplicate notification
- paid occurrence suppresses future reminder
- background job run logged

Priority: high.

---

## Scenario 26 — `navigation_all_routes_smoke`

Covers:

- all routed UI surfaces
- deep links
- parameterized destinations

Input:

- every `NavigationDestination`
- representative params
- deep links

Assert:

- route roundtrip
- ViewModel can be instantiated or fake-bound
- missing route/screen mismatch fails test
- deep link opens expected destination

Priority: medium-high.

---

## 5. Repository hygiene remains P0

Root contains committed generated/local artifacts such as:

- `expense_tracker_backup_2026-04-20_21-58-14.db`
- `repomix-output.xml`
- `data.json`
- `revodata.json`
- session files

`.gitignore` currently does not include `*.db`, `*.sqlite`, `repomix-output.xml`, or session dumps.

### Add to `.gitignore`

```gitignore
# Local databases / backups
*.db
*.sqlite
*.sqlite3
*.costbackup

# Repo analysis dumps
repomix-output.xml
repomix-output.*
session-*.md
*_audit.json
data.json
revodata.json

# Local generated reports
build/reports/testing/
```

### Also do

- inspect committed `.db` for real user data
- if sensitive, purge from Git history
- add secret scan in CI
- block future `.db` files in pre-commit/CI

---

## 6. Updated implementation order

## P0 — coverage safety baseline

1. Add `.gitignore` protections.
2. Add `.github/workflows/ci.yml` if absent.
3. Add DAO coverage guardrail.
4. Add schema snapshot verifier based on migration starts, not hardcoded `92`.
5. Add direct Room builder bypass guard.
6. Add ignored-test-count guard.
7. Add lifecycle bypass guard.

## P1 — newest DB/lifecycle coverage

1. DAO tests for lifecycle/privacy/background-job tables.
2. `TransactionLifecycleCoordinatorDbContractTest`
3. `ReceiptLifecycleCoordinatorDbContractTest`
4. `RecurringLifecycleCoordinatorDbContractTest`
5. migration tests for `104_105` through `112_113`.

## P2 — privacy + backup primitives

1. privacy gate tests
2. redaction tests
3. privacy settings repository tests
4. audit logger tests
5. backup encryption tests
6. bundle/verifier/journal/maintenance tests
7. data retention worker tests

## P3 — UI route and missing ViewModels

Add ViewModel/screen tests for:

- backup
- bank
- categories
- investment
- natural language
- negotiation
- privacy settings
- recurring
- reminders
- tax

Also add all-route navigation smoke.

## P4 — canonical scenario expansion

Add scenarios 19–26 from this file.

## P5 — coverage control system

Generate:

```text
docs/testing/COVERAGE_MATRIX.md
build/reports/testing/test-inventory.json
build/reports/testing/dao-coverage.json
build/reports/testing/route-coverage.json
```

---

## 7. Revised definition of done

The broader coverage plan is done when:

1. Every `AppDatabase` DAO has a direct DAO contract test or explicit allowlist.
2. Transaction lifecycle is tested directly with DB/event/dashboard assertions.
3. Receipt lifecycle is tested directly with receipt events and receipt-expense links.
4. Recurring lifecycle is tested directly with occurrence/reminder/event tables.
5. Privacy gates, redaction, audit logging, DataStore settings, and data retention are tested.
6. Backup bundle, verifier, journal, and maintenance mode have low-level tests.
7. Restore/backup roundtrip scenario passes.
8. Schema snapshot verifier understands v113 and supported migration starts.
9. Migration tests cover v104–v113 risk points.
10. Missing UI ViewModel tests are added for backup/bank/categories/investment/natural-language/negotiation/privacy/recurring/reminder/tax.
11. Navigation route smoke test covers every destination.
12. Core money and time approved types have direct tests.
13. Database integrity scanner has seeded violation tests.
14. `.db` and generated repo dumps cannot be recommitted.
15. Coverage matrix is generated automatically, not guessed manually.

---

## Sources reviewed

- Root tree / committed artifacts:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a

- `.gitignore`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/.gitignore

- Codebase segments:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/CODEBASE_SEGMENTS.md

- Architecture guide:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/ARCHITECTURE.md

- Stale inventory doc:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/CODEBASE_INVENTORY.md

- Test inventory:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/_all_rel_paths.txt

- Test scout report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-scout-report.md

- Test quality audit:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-quality-audit.md

- App Gradle config:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/build.gradle.kts

- AppDatabase:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- Schema snapshots:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/schemas/com.yourname.expensetracker.data.database.AppDatabase

- Android DAO tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/androidTest/java/com/yourname/expensetracker/data/database/dao

- UI screens tree:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/ui/screens

- Backup package:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/data/backup

- Privacy packages:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/domain/privacy  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/data/privacy