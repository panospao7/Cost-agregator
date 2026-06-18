# Cost-agregator Master Testing Implementation Plan

Target repo/commit: `31d9e1bbb10976b648788b91fd1922aa3564759a`

## 1. Main objective

Build a test infrastructure that proves the app works across real business pipelines, not only isolated functions.

The final target is:

1. Fast unit tests for engines and pure business logic.
2. Reliable Room/repository integration tests with deterministic seeded data.
3. A small set of high-value multi-pipeline scenario tests.
4. Golden expected-output verification for metrics, analytics, dashboard totals, currency behavior, groups, receipts, recurring flows, and lifecycle events.
5. CI separation between fast PR tests, integration tests, instrumented tests, and nightly stress/performance tests.
6. Removal or quarantine of dead/marginal tests that create noise but do not protect the application.

---

## 2. Current situation

Based on the current docs:

- The app has 38 codebase segments.
- Database version in architecture doc is now `v113`.
- There are 421+ test files and around 4,347 test methods in the scout report.
- There are 35 files with `@Ignore`.
- The quality audit estimates:
  - meaningful tests: about 190 files
  - marginal tests: about 180 files
  - trivial tests: about 15 files
  - dead tests: 35 files
  - infrastructure/helper files: 15 files
- Existing valuable test areas already include:
  - parser tests
  - analytics golden tests
  - repository tests
  - e2e pipeline tests
  - verification/cross-source tests
  - currency consistency tests
- Main infrastructure problems:
  - missing/weak Gradle `testOptions`
  - stale Room schema verification in old docs/plans
  - ignored stress tests
  - time-dependent tests
  - coroutine timing risks
  - some duplicate or implementation-coupled tests
  - not enough realistic seeded multi-pipeline tests

Important note: because architecture says the DB is now v113, any older plan mentioning schemas only up to 92 must be updated before implementation.

---

## 3. Testing philosophy

Use this hierarchy:

## Layer A — Pure engine tests

Purpose: verify deterministic business rules.

Use for:

- analytics
- budget logic
- currency conversion
- forecast engines
- recurring calculation
- split calculation
- tax estimation
- savings/health metrics
- merchant categorization
- parser normalization

Rules:

- no Room
- no Android framework
- no ViewModels
- no broad mocks
- fixed clock
- explicit input and expected result
- no duplicate production algorithm inside the test

These should be very fast and should run on every PR.

---

## Layer B — Repository and Room integration tests

Purpose: verify SQL, DAOs, repositories, migrations, and persistence contracts.

Use for:

- DAO query correctness
- repository aggregation
- transaction events
- receipt events
- receipt-expense links
- recurring occurrences
- exchange rates
- budget status storage
- review queue behavior
- backup/restore database effects
- migration/fresh-install parity

Rules:

- use in-memory Room where possible
- use saved `.db` snapshots only for migration, restore, and large-regression corpus tests
- assert public DB-visible contracts, not every internal column
- avoid auto-generated ID assumptions unless required
- use deterministic timestamps and fixture IDs

---

## Layer C — Multi-pipeline scenario tests

Purpose: verify the actual application behavior across segments.

These are the most important missing tests.

Each scenario should start from realistic app inputs and assert final business outputs.

Examples:

- bank notification -> parser -> review queue -> lifecycle coordinator -> dashboard
- receipt -> OCR/parser -> receipt lifecycle -> matching -> analytics
- recurring rule -> planned occurrence -> actual expense -> dashboard no-double-count
- multi-currency expenses -> rate conversion -> budget -> forecast -> warning state
- shared expense -> settlement -> reimbursement-aware budget offset
- restore mode -> workers paused -> journal recovery -> workers resumed
- bank API import -> dedupe -> review/expense lifecycle -> analytics

These tests should be fewer in number but very strong.

Target: 8 to 12 canonical scenarios.

---

## Layer D — UI/ViewModel tests

Purpose: verify state mapping and user-facing behavior.

Rules:

- ViewModel tests should use fake repositories/use cases with real data contracts.
- Avoid “verify the mock I just called” tests.
- Avoid source-code text assertions.
- Compose screen tests should focus on important states:
  - empty
  - loading
  - success
  - error
  - warning/partial currency conversion
  - review required
  - permission blocked
  - privacy denied

---

## Layer E — Nightly stress/performance tests

Purpose: catch race conditions, performance regressions, and large dataset issues.

Rules:

- do not keep these permanently `@Ignore`
- tag them as nightly/performance/stress
- use deterministic timeouts
- avoid arbitrary `delay`
- have clear thresholds
- do not run them on every PR unless they are fast and deterministic

---

# 4. Target test infrastructure

## 4.1 Test fixture package

Create or standardize a test fixture area:

`app/src/test/java/com/yourname/expensetracker/testfixtures/`

Subpackages:

- `clock`
- `database`
- `scenario`
- `golden`
- `currency`
- `expenses`
- `receipts`
- `notifications`
- `groups`
- `recurring`
- `workers`
- `assertions`

Move/standardize these existing helpers into the fixture layer:

- `FakeTimeProvider`
- `GoldenAnalyticsDataset`
- `ExpectedResults`
- `GoldenDataSets`
- `FlowPipelineTestHarness`
- `ViewModelTestUtils`
- `FlowTestUtils`
- `HiltTestUtils`

Goal: one official fixture system, not scattered local builders.

---

## 4.2 Canonical test clock

Create one canonical fixed clock.

Required behavior:

- can freeze now
- can advance by duration
- can return fixed `Instant`
- can return fixed millis
- can expose app `TimeProvider` interface
- can be used in ViewModel, repository, engine, and worker tests

All tests that use:

- `System.currentTimeMillis`
- `Instant.now`
- `LocalDate.now`
- `Calendar.getInstance`
- `Date()`

should migrate to this clock unless they are explicitly testing system-clock integration.

---

## 4.3 Coroutine test rule

Create one standard coroutine rule.

It should provide:

- `StandardTestDispatcher`
- test scope
- Main dispatcher override
- cleanup/reset
- helper to advance until idle
- helper to collect Flow emissions safely

Rules:

- no uncontrolled `runBlocking`
- no arbitrary `delay`
- no background collectors without cancellation
- no shared mutable `Flow` between tests unless reset every test

---

## 4.4 Room test database factory

Create one standard in-memory DB factory.

It should provide:

- in-memory `AppDatabase`
- all required DAOs
- deterministic executor setup
- close/cleanup helper
- optional pre-seeded baseline categories/rates/settings

Also create a separate saved DB fixture system only for:

- migration tests
- restore tests
- backup tests
- large-corpus regression tests

Do not use binary `.db` snapshots for normal business tests.

---

## 4.5 Scenario seeder

Create a scenario seeder that can seed realistic data.

Recommended package:

`testfixtures/scenario/`

Conceptual components:

- `ScenarioSeed`
- `ScenarioSeeder`
- `ScenarioCatalog`
- `ExpectedScenarioResult`
- `ScenarioAssertions`

Supported seed sections:

- user settings
- categories
- exchange rates
- expenses
- transaction events
- receipts
- receipt events
- receipt-expense links
- recurring rules
- recurring occurrences
- planned expenses
- groups
- group members
- settlements
- budgets
- review queue items
- notifications
- bank connections
- bank transactions
- AI artifacts
- privacy settings

The seeder should support two modes:

1. `seedState`: insert already-existing DB state.
2. `feedInputs`: run real app pipeline entry points.

Use `feedInputs` for the most important multi-pipeline tests.

---

## 4.6 Golden expected outputs

Create:

`app/src/test/resources/scenarios/`

Suggested structure:

```text
scenarios/
  notification_review_dashboard/
    input.json
    seed.json
    expected-dashboard.json
    expected-events.json

  receipt_matching_analytics/
    input.json
    seed.json
    expected-receipts.json
    expected-analytics.json

  multicurrency_partial_rates/
    seed.json
    expected-money-aggregate.json
    expected-dashboard.json
    expected-warnings.json

  recurring_planned_actual/
    seed.json
    expected-recurring.json
    expected-dashboard.json

  shared_expense_budget_offset/
    seed.json
    expected-groups.json
    expected-budget.json
```

Golden files should assert stable contracts:

- totals
- category totals
- source buckets
- conversion failures
- warning flags
- event types
- receipt links
- duplicate result
- group balances
- budget status
- forecast percentiles with tolerance
- dashboard cards
- recommendations generated/suppressed

Avoid asserting:

- auto IDs
- raw timestamps unless part of contract
- exact ordering unless business-critical
- UI copy that is not contractually stable
- implementation-private fields

---

# 5. CI task design

## 5.1 PR fast suite

Run on every pull request.

Includes:

- pure engine tests
- parser tests
- use-case tests
- lightweight repository tests
- non-Android scenario tests with in-memory Room
- golden verification tests that finish quickly

Target runtime: under 5 to 8 minutes.

Must fail on:

- compilation error
- failing unit tests
- direct unsafe `ExpenseDao` access outside approved files
- stale Room schema check
- accidental `@Ignore` increase
- accidental direct system time usage in business tests

---

## 5.2 Integration suite

Run on main branch and before releases.

Includes:

- all repository tests
- all scenario tests
- all e2e JVM tests
- backup/restore JVM tests
- WorkManager JVM tests where possible
- multi-currency + analytics + forecast golden tests

Target runtime: under 20 minutes if possible.

---

## 5.3 Instrumented suite

Run on emulator/device.

Includes:

- Room migration tests
- DAO parity tests
- fresh install parity
- Hilt graph smoke tests
- Android service tests that require framework
- Compose UI tests if added

---

## 5.4 Nightly suite

Run overnight.

Includes:

- stress tests
- large DB corpus tests
- performance baselines
- concurrency/race tests
- backup/restore large bundle tests
- long-running WorkManager behavior

No test should be permanently ignored without a documented reason.

---

# 6. Immediate implementation phases

## Phase 0 — Baseline lock

Goal: know exactly where the suite stands before changing it.

Tasks:

1. Run full JVM test suite.
2. Run instrumented suite if available.
3. Export test result XML.
4. Count:
   - passing tests
   - failing tests
   - ignored tests
   - flaky tests
   - runtime by class
5. Record current Gradle task names.
6. Record current Room schema versions actually present.
7. Create a tracking file:
   - `docs/testing/TESTING_MASTER_STATUS.md`

Exit criteria:

- baseline test status committed
- no assumptions about current pass/fail state
- current ignored-test count known

---

## Phase 1 — Build/test infrastructure hardening

Goal: make the test runner reliable before adding more tests.

Tasks:

1. Add proper Gradle test options:
   - test logging
   - parallel forks
   - fork frequency
   - heap settings if needed
   - include/exclude patterns
   - CI properties for fast/integration/nightly
2. Fix Room schema verification:
   - do not hardcode stale max version
   - derive latest schema or update to current `v113`
   - fail if expected schema is missing
3. Add CI guardrail for `@Ignore` count.
4. Add CI guardrail for unsafe `ExpenseDao` direct access.
5. Add CI guardrail for direct `System.currentTimeMillis`/`Instant.now` in business tests, except approved files.
6. Move Espresso-based JVM test to Android test or Robolectric.
7. Migrate single Mockito test to MockK if still present.
8. Standardize test report output.

Exit criteria:

- `./gradlew test` is stable
- schema check is meaningful
- test output is readable in CI
- no accidental new ignored tests can enter unnoticed

Priority: critical.

---

## Phase 2 — Dead/trivial test cleanup

Goal: reduce noise before building the new scenario layer.

Use the existing audit and batch plan as the starting point.

Actions:

1. Delete dead stress tests that are permanently ignored and duplicate better tests.
2. Keep and fix assertion-drift tests where business value exists.
3. Delete tests that only assert source text or reimplement math inside the test.
4. Archive test-only stub fixtures if they are not real tests.
5. Deduplicate helper files.

Recommended delete/archive candidates from the existing plan:

- ignored stress suites that never run
- source-string assertion tests
- trivial model-shape tests
- duplicated parser/analytics stress tests
- value-class tests broken only by assertion-library mismatch if better coverage exists elsewhere

But keep/fix:

- CSV escaping if it protects security/export behavior
- split precision if it protects financial correctness
- custom split parser if parser behavior is still used
- warranty/price-protection tests if the features are live
- receipt assist input builder tests if AI receipt flow is live

Exit criteria:

- no dead `@Ignore` classes remain
- remaining `@Ignore` uses are documented
- trivial tests removed
- suite is smaller and clearer

Priority: critical.

---

## Phase 3 — Test fixture foundation

Goal: make future tests cheap to write and hard to make flaky.

Implement:

1. `FakeTimeProvider` standardization.
2. Coroutine test rule.
3. Room in-memory DB factory.
4. Scenario seeder.
5. Golden verifier.
6. Money assertion helpers.
7. Date/period assertion helpers.
8. Flow assertion helpers.
9. Repository fixture builders.
10. Event-log assertion helpers.

Required assertion helpers:

- assert money amount with currency
- assert money aggregate source buckets
- assert conversion failure reason
- assert partial aggregate warning
- assert dashboard total
- assert category total
- assert lifecycle event sequence
- assert receipt status sequence
- assert receipt-expense links
- assert recurring occurrence status
- assert group settlement balance
- assert budget status
- assert no double-counting

Exit criteria:

- at least one existing e2e test migrated to new fixture style
- no hidden mutable state between tests
- fixture README added

Priority: critical.

---

# 7. Canonical multi-pipeline scenarios

These are the most important new tests to implement.

## Scenario 1 — Transaction lifecycle contract

Name:

`transaction_lifecycle_contract`

Segments covered:

- Core Expense Management
- Budget
- Analytics
- Recurring hook
- Merchant categorization
- Dashboard totals

Inputs:

- manual expense create
- duplicate create attempt
- update amount/category
- delete expense

Expected:

- create result is `Created`
- duplicate result is `DuplicateSkipped`
- update writes `UPDATED` event
- delete writes `DELETED` event
- dashboard total changes after update/delete
- budget status recalculates
- analytics category total recalculates
- no raw DAO insert path bypasses lifecycle

Priority: highest.

---

## Scenario 2 — Notification to review to dashboard

Name:

`notification_review_dashboard_budget`

Segments covered:

- Notification Capture, Parsing & Review
- Merchant Categorization
- Transaction Lifecycle
- Budget Management
- Dashboard Totals
- Notifications & Alerts
- Analytics

Inputs:

- Greek bank notification
- Revolut notification
- duplicate notification
- category rules
- monthly budget

Expected:

- parser extracts amount, merchant, currency, timestamp
- confidence router chooses auto-accept or review
- approved review creates expense through lifecycle coordinator
- duplicate is skipped
- transaction event log contains create and duplicate events
- category assigned
- dashboard monthly total correct
- budget alert state correct
- analytics category total matches dashboard

Priority: highest.

---

## Scenario 3 — Receipt to matching to analytics

Name:

`receipt_matching_analytics`

Segments covered:

- Receipt Scanning
- Receipt Lifecycle
- Receipt Matching
- AI Receipt Item Categorization
- Transaction Lifecycle
- Analytics
- Warranty/Price Protection if enabled

Inputs:

- receipt OCR text or fixture image
- matching existing bank transaction
- category list
- fake AI item categorization response

Expected:

- receipt status progresses correctly
- receipt asset/hash stored
- duplicate detector result correct
- receipt saved
- receipt event log written
- receipt linked to expense
- item categorization saved
- analytics category totals include matched expense once
- warranty/price protection side effects only run for valid document type
- no duplicate expense created

Priority: highest.

---

## Scenario 4 — Multi-currency analytics and dashboard

Name:

`multicurrency_analytics_dashboard`

Segments covered:

- Currency & Exchange
- Dashboard Totals
- Analytics
- Budget
- Forecasting
- Export
- AI query if applicable

Inputs:

- home currency EUR
- EUR expense
- USD expense with valid rate
- GBP expense with valid rate
- CHF or JPY expense with missing/stale rate
- category budgets

Expected:

- source buckets preserve original currencies
- display total converts only convertible currencies
- missing/stale rate produces conversion failure
- `isPartial` is true when conversion fails
- UI/dashboard warning state is available
- analytics does not silently mix raw currencies
- budget uses home-currency normalized values
- forecast either excludes missing-rate data or marks lower confidence
- export includes original and converted money fields

Priority: highest.

---

## Scenario 5 — Shared expense with budget offset

Name:

`shared_expense_budget_offset`

Segments covered:

- Shared Expense Groups
- Shared Expense Budget Offset
- Core Expense Management
- Budget
- Dashboard
- Analytics

Inputs:

- group dinner
- payer
- participants
- split ratio
- reimbursement
- dining budget

Expected:

- group balances correct
- settlement suggestion correct
- gross spending total correct
- budget-offset net amount correct
- reimbursement does not corrupt analytics
- dashboard contract explicitly states gross vs net behavior
- event log records group-origin expense

Priority: high.

---

## Scenario 6 — Recurring planned to actual

Name:

`recurring_planned_actual_no_double_count`

Segments covered:

- Recurring Expenses
- Bill Reminders
- Core Expense Management
- Forecasting
- Dashboard
- Budget

Inputs:

- recurring subscription rule
- generated planned occurrence
- reminder delivery
- actual notification or manual expense matching the occurrence

Expected:

- occurrence generated
- planned expense materialized
- reminder delivery created once
- actual expense linked to occurrence
- occurrence status becomes paid/matched
- dashboard does not count planned and actual twice
- forecast includes future planned occurrences
- budget current period uses actual once

Priority: high.

---

## Scenario 7 — Bank statement import to review

Name:

`bank_statement_import_review_lifecycle`

Segments covered:

- Receipt Scanning / Bank Statement Processor
- Bank Integration
- Notification/Review Queue
- Transaction Lifecycle
- Deduplication
- Analytics

Inputs:

- bank statement OCR text or parsed transaction fixture
- existing duplicate expense
- new transactions
- review decisions

Expected:

- statement processor extracts transaction candidates
- candidates become pending review items
- duplicate candidate is flagged/skipped
- approved items create expenses through lifecycle coordinator
- source is `BANK_API_SYNC` or statement-specific source according to current enum contract
- analytics totals include only approved non-duplicates

Priority: high.

---

## Scenario 8 — Restore maintenance and workers

Name:

`restore_maintenance_workers_safe_startup`

Segments covered:

- Startup & Background Runtime
- Export & Backup
- Notifications & Alerts
- Receipt Matching Worker
- Data Retention
- Bill Reminders
- Daily Briefing

Inputs:

- restore journal in progress
- restore maintenance mode active
- pending workers
- notification capture enabled before restore

Expected:

- workers cancelled/paused during restore
- notification capture disabled during restore
- journal recovery happens before scheduling work
- no worker mutates DB while restore is active
- workers are rescheduled only after safe completion
- failure state requires manual/critical recovery if needed

Priority: high.

---

## Scenario 9 — Privacy gate and AI redaction

Name:

`privacy_ai_redaction_gate`

Segments covered:

- Privacy
- AI Platform
- Receipt AI
- Natural Language Search
- Dashboard Briefing
- Security

Inputs:

- privacy settings deny cloud AI
- notification/receipt/query containing sensitive text
- local AI available/unavailable variants

Expected:

- denied cloud capability blocks cloud provider call
- audit event written
- redaction sanitizer removes sensitive data before any allowed cloud call
- fallback to on-device provider happens when configured
- user-facing state explains denied capability
- no raw sensitive text stored in AI artifact when policy forbids it

Priority: high.

---

## Scenario 10 — Migration and fresh install parity

Name:

`migration_fresh_install_parity_v113`

Segments covered:

- Database
- Export & Backup
- All persisted segments indirectly

Inputs:

- old schema DB snapshots
- fresh v113 database
- migrated v113 database

Expected:

- all migrations complete
- migrated schema equals fresh schema for tables, indices, and required defaults
- no silent `assume` skips
- critical tables exist:
  - expenses
  - transaction events
  - receipt events
  - receipt-expense links
  - recurring occurrences
  - privacy audit events
  - exchange rates with historical fields
- representative queries work after migration

Priority: highest for release safety.

---

# 8. Segment coverage plan

## Highest priority segments

These need scenario-level tests, not only unit tests:

1. Core Expense Management
2. Transaction Lifecycle
3. Currency & Exchange
4. Dashboard Totals & Widgets
5. Analytics & Insights
6. Budget Management
7. Notification Capture, Parsing & Review
8. Receipt Lifecycle
9. Recurring Expenses
10. Shared Expense Groups
11. Export & Backup
12. Startup & Background Runtime
13. Privacy / Security
14. Bank Integration
15. Receipt Matching

---

## Medium priority segments

These need at least engine + repository/use-case tests:

- Forecasting & Runway
- Cash Flow Planning
- Tax Calculation & Reporting
- Savings Optimization & Health
- Merchant Categorization
- AI Platform and Assistant
- Bill Reminders
- Warranty, Subscription & Offers
- Location Enrichment
- Natural Language Search
- Carbon Footprint Tracking
- Enhanced Split Transactions
- Spending Challenges

---

## Weak/likely under-tested areas to add tests for

Add at least smoke/contract tests for:

1. Investment Tracking
   - portfolio totals
   - holding gain/loss
   - sync/import handling
   - empty state
2. Bill negotiation
   - negotiation recommendation generation
   - provider failure
   - no-offer state
   - privacy/AI blocking
3. Hilt/DI graph
   - app graph starts
   - key repositories resolve
   - workers resolve
   - ViewModels resolve where practical
4. Startup coordinator
   - restore journal checked before workers
   - worker scheduling idempotent
5. Privacy/config/performance helpers
   - privacy gate audit
   - data retention
   - image cache eviction
   - config defaults
6. UI screens with thin coverage
   - bank connections
   - investment portfolio
   - negotiation
   - reminders
   - map
   - receipt scan/review
   - transaction filters

---

# 9. Required golden datasets

Create these canonical datasets.

## Dataset A — Baseline single-currency month

Purpose:

- dashboard
- analytics
- budget
- category totals
- daily average

Contents:

- 10 EUR expenses
- 4 categories
- 1 budget
- fixed month
- one refund/income if supported

Expected outputs:

- monthly total
- category breakdown
- daily average
- budget remaining
- top merchant
- no warnings

---

## Dataset B — Multi-currency partial conversion

Purpose:

- currency safety
- partial aggregate behavior
- warnings

Contents:

- EUR, USD, GBP expenses
- one missing/stale exchange rate
- home currency EUR

Expected outputs:

- source buckets
- converted total
- conversion failure list
- `isPartial = true`
- dashboard warning
- analytics partial flag

---

## Dataset C — Dedupe and lifecycle

Purpose:

- transaction lifecycle correctness

Contents:

- same external ID twice
- same merchant/amount/time near duplicate
- manual expense
- update
- delete

Expected outputs:

- created count
- skipped duplicate count
- event log sequence
- final DB expense count

---

## Dataset D — Receipt matching

Purpose:

- receipt lifecycle
- matching
- analytics no-double-count

Contents:

- OCR receipt
- existing matching transaction
- receipt duplicate
- item categorization result

Expected outputs:

- receipt status
- receipt events
- link row
- analytics total once

---

## Dataset E — Shared expense settlement

Purpose:

- group math
- budget offset

Contents:

- 3 group members
- multiple paid expenses
- reimbursement
- budget category

Expected outputs:

- balances
- settlements
- gross vs net totals
- budget status

---

## Dataset F — Recurring planned/actual

Purpose:

- recurring and forecast no-double-count

Contents:

- monthly subscription
- generated occurrence
- actual payment
- future occurrences

Expected outputs:

- occurrence statuses
- reminder delivery count
- dashboard total
- forecast future cost

---

# 10. Metrics to track

## 10.1 Application correctness metrics

For each canonical scenario, track:

- dashboard monthly total
- category totals
- daily average
- budget remaining
- budget severity
- forecast P10/P50/P90
- forecast confidence
- health score
- source currency buckets
- conversion failure count
- group balance
- settlement count
- receipt link count
- lifecycle event count
- duplicate skip count
- privacy denial count

---

## 10.2 Test-suite health metrics

Track in CI:

- total tests
- ignored tests
- flaky retries
- suite runtime
- slowest 20 test classes
- number of scenario tests
- number of golden datasets
- mutation-sensitive areas covered
- direct system-time usage count
- direct unsafe DAO usage count
- stale golden files
- stale Room schema files
- test files deleted as dead weight

---

# 11. Acceptance gates

## PR gate

A PR can merge only if:

- fast suite passes
- no new ignored tests unless approved
- no stale Room schema
- no unsafe DAO access
- no accidental direct time usage in core business tests
- scenario/golden tests pass if touched area affects them
- no test-only source-string assertions added

---

## Release gate

A release can ship only if:

- full JVM suite passes
- instrumented migration suite passes
- all canonical scenarios pass
- backup/restore scenario passes
- no silent migration skips
- multi-currency partial-rate behavior passes
- transaction lifecycle scenario passes
- receipt lifecycle scenario passes
- recurring no-double-count scenario passes
- restore maintenance scenario passes

---

# 12. Implementation order

## Week 1 — Stabilize and clean

1. Baseline status file.
2. Gradle test options.
3. Room schema verification updated to current DB version.
4. CI filters.
5. Ignore-count guardrail.
6. Delete/archive dead tests.
7. Fix valuable ignored tests:
   - CSV escaping
   - split precision
   - custom split parser
   - receipt assist
   - warranty/price protection if still active
8. Standardize fake clock and coroutine rule.

Deliverables:

- green fast suite
- no silent schema check
- dead test cleanup PR
- test infrastructure README

---

## Week 2 — Fixture foundation

1. In-memory DB factory.
2. Scenario seeder.
3. Golden verifier.
4. Money/currency assertions.
5. Lifecycle event assertions.
6. Receipt link assertions.
7. Convert one existing e2e test to new fixture style.
8. Convert one existing analytics golden test to new golden verifier style.

Deliverables:

- reusable fixture package
- first scenario fixture
- first golden verifier

---

## Week 3 — Highest-value scenarios

Implement:

1. `transaction_lifecycle_contract`
2. `notification_review_dashboard_budget`
3. `multicurrency_analytics_dashboard`
4. `migration_fresh_install_parity_v113`

Deliverables:

- 4 canonical scenarios passing in CI
- clear expected JSON or Kotlin expected objects
- dashboard/analytics/currency parity assertions

---

## Week 4 — Receipt, recurring, groups

Implement:

1. `receipt_matching_analytics`
2. `recurring_planned_actual_no_double_count`
3. `shared_expense_budget_offset`
4. `bank_statement_import_review_lifecycle`

Deliverables:

- receipt lifecycle coverage
- recurring no-double-count coverage
- group/budget offset coverage
- bank statement import coverage

---

## Week 5 — Runtime, privacy, under-tested segments

Implement:

1. `restore_maintenance_workers_safe_startup`
2. `privacy_ai_redaction_gate`
3. Hilt graph smoke test
4. Investment tracking smoke tests
5. Bill negotiation smoke tests
6. Startup coordinator tests

Deliverables:

- runtime safety coverage
- privacy gate coverage
- major under-tested areas now have at least smoke/contract tests

---

## Week 6 — CI maturity and nightly suite

1. Create nightly stress/performance task.
2. Move valid stress tests into nightly category.
3. Delete remaining invalid stress tests.
4. Add slow-test reporting.
5. Add scenario coverage dashboard.
6. Add large DB performance smoke test.
7. Add backup/restore large corpus test if feasible.

Deliverables:

- fast PR suite
- integration suite
- instrumented suite
- nightly suite
- documented release gate

---

# 13. Rules for deciding test fate

## Keep

Keep tests that:

- catch real regressions
- assert public behavior
- use realistic data
- protect financial correctness
- protect security/privacy
- protect lifecycle/event logs
- protect migration/backup safety
- are deterministic and readable

## Rewrite

Rewrite tests that:

- use too many mocks but target important behavior
- assert implementation details
- duplicate production calculations
- rely on arbitrary delay
- use real time
- are valuable but flaky
- test only ViewModel plumbing but can be converted to state-contract tests

## Delete

Delete tests that:

- are permanently ignored
- duplicate better tests
- assert source code strings
- only test constants
- reimplement production logic inside the test
- are race-sensitive without deterministic control
- create maintenance cost but no protection

## Move to nightly

Move tests that:

- are slow but meaningful
- test large datasets
- test concurrency
- test performance
- test long-running workers
- test backup/restore corpus size
- are deterministic but too expensive for PRs

---

# 14. Naming conventions

Use names that show business behavior.

Good:

- `createsExpenseThroughLifecycleAndUpdatesDashboard`
- `skipsDuplicateNotificationAndRecordsLifecycleEvent`
- `marksMoneyAggregatePartialWhenRateMissing`
- `linksReceiptToExistingExpenseWithoutDoubleCounting`
- `doesNotScheduleWorkersDuringRestoreMaintenance`
- `linksActualExpenseToRecurringOccurrenceWithoutDoubleCounting`

Bad:

- `test1`
- `shouldWork`
- `callsRepository`
- `returnsNonEmpty`
- `screenContainsText`
- `stressTest`

---

# 15. Required documentation

Add:

`docs/testing/TESTING_STRATEGY.md`

Include:

- test pyramid
- CI tasks
- fixture system
- scenario list
- golden file rules
- how to add a new scenario
- how to update golden outputs
- how to run fast/integration/instrumented/nightly tests
- rules for `@Ignore`
- rules for time/coroutines
- rules for Room database tests

Add:

`docs/testing/SCENARIO_CATALOG.md`

Include each scenario:

- name
- segments covered
- seed data
- inputs
- expected outputs
- test file path
- golden files
- owner segment
- CI task

Add:

`docs/testing/COVERAGE_GAPS.md`

Track:

- untested segments
- weakly tested segments
- planned scenarios
- deleted tests
- rewritten tests
- known risks

---

# 16. Final definition of done

The master testing plan is complete when:

1. Dead/trivial ignored tests are removed or fixed.
2. No important tests are permanently ignored.
3. Fast PR suite is stable and useful.
4. Room schema verification reflects the current DB version.
5. Fake time is used consistently.
6. Coroutine tests do not depend on arbitrary delay.
7. Scenario seeder exists.
8. Golden verifier exists.
9. At least 8 canonical multi-pipeline tests pass.
10. Transaction lifecycle is tested from input to event log to dashboard.
11. Receipt lifecycle is tested from input to link/match to analytics.
12. Recurring planned/actual no-double-count is tested.
13. Multi-currency partial conversion is tested.
14. Shared expense budget offset is tested.
15. Restore maintenance worker safety is tested.
16. Privacy/AI redaction gate is tested.
17. Migration/fresh-install parity is tested.
18. CI tracks ignored count, runtime, and schema freshness.
19. Release gate includes full JVM + instrumented + canonical scenarios.
20. Test docs explain how future features should add tests.

---

# 17. Sources reviewed

- Commit `31d9e1b`  
  https://github.com/panospao7/Cost-agregator/commit/31d9e1bbb10976b648788b91fd1922aa3564759a

- Architecture guide  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/ARCHITECTURE.md

- Codebase segments  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/CODEBASE_SEGMENTS.md

- Test suite scout report  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-scout-report.md

- Test suite quality audit  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-quality-audit.md

- Test suite batch plan  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-batch-plan.md