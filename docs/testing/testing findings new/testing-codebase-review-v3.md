# Cost-agregator deeper testing/codebase review v3

Target commit: `31d9e1bbb10976b648788b91fd1922aa3564759a`  
Review type: GitHub static review, not local execution.

## 1. Important correction from previous reviews

The app is broader-tested than I first described.

The committed inventory shows many tests I had not previously considered:

- AI cloud/on-device provider tests
- AI use-case/input-builder tests
- email receipt ingestion tests
- email provider parser tests for Amazon/Apple/Uber
- location/geocoding tests
- backup repository test
- security key storage test
- speech gateway test
- daily briefing worker test
- many dashboard/analytics/currency/forecast/group tests
- service-level tests for recommendation and notification capture
- UI tests for several more screens than I initially listed

So some areas are **not untested**, but many are still **not scenario-tested**.

The main gap remains: you have many unit/isolated tests, but not enough canonical multi-pipeline acceptance tests.

---

## 2. New high-risk codebase findings

### Finding A — repo hygiene/security risk

The root contains files that should be audited:

- `repomix-output.xml` — 4.33 MB generated repo dump
- `expense_tracker_backup_2026-04-20_21-58-14.db` — committed database backup
- `data.json`
- `revodata.json`
- `session-ses_2204.md`
- `session-ses_235e.md`
- `hardcoded_string_audit.json`
- many one-off plan/review files

Action:

1. Check whether the `.db` backup contains real user data, API keys, emails, merchants, locations, or receipts.
2. If yes, rotate anything sensitive and purge it from Git history.
3. Add `*.db`, `*.sqlite`, `repomix-output.xml`, session dumps, and local analysis artifacts to `.gitignore`.
4. Move generated analysis outputs to CI artifacts, not source control.

This is now a P0 repo cleanup item.

---

### Finding B — no visible real GitHub Actions CI folder

I saw a top-level `workflows/` directory, but not `.github/workflows/` in the root listing.

A top-level `workflows/` folder is not GitHub Actions CI.

Action:

- add `.github/workflows/ci.yml`
- run at least:
  - `./gradlew :app:verifyRoomSchemaSnapshots -PstrictRoomSchemas=true`
  - `./gradlew :app:testDebugUnitTest`
  - lifecycle bypass script
  - ignored-test-count guard
  - docs drift check

Without real CI, “BUILD SUCCESSFUL” can be local-only.

---

### Finding C — committed test inventory is useful but should become automated

`docsplans/_all_rel_paths.txt` contains about 426 test paths. This is very useful, but it is static.

Action:

Create:

```text
scripts/testing/generate-test-inventory.kt
```

or Python/Bash equivalent.

It should generate:

```text
build/reports/testing/test-inventory.json
build/reports/testing/coverage-map.md
```

Track:

- production package
- corresponding test package
- test count
- ignored count
- stress/nightly count
- scenario coverage yes/no
- golden coverage yes/no

Do not keep stale hand-written inventories as the source of truth.

---

### Finding D — `src/test/kotlin` also exists

The test tree has both:

- `app/src/test/java/...`
- `app/src/test/kotlin/...`

Guardrails and inventory scripts must scan both.

Otherwise you will miss tests or bypasses.

---

### Finding E — instrumented tests look too narrow

`app/src/androidTest` appears to focus under `java/com/yourname/expensetracker/data`.

That is good for Room, but weak for:

- Hilt graph smoke tests
- WorkManager integration
- notification/service behavior
- Compose navigation
- permission flows
- encrypted storage behavior on Android
- backup/restore using Android filesystem/content APIs

Action:

Add an `androidTest` smoke layer, not hundreds of UI tests.

---

## 3. Newly identified weak or possibly missing areas

These are not necessarily totally untested; they are weakly tested or not scenario-tested.

## P0 / P1 gaps

### 1. Startup package

Main code has a `startup` package. I did not see matching test coverage in the test inventory excerpts.

Needs tests for:

- startup restore gate
- worker scheduling idempotency
- service restart policy
- notification capture enable/disable
- database maintenance mode
- critical recovery state

### 2. DI/Hilt graph

There are many Hilt modules, but I still did not see a real app graph smoke test.

Needs:

- app graph starts
- database/repositories resolve
- workers resolve
- key ViewModels resolve
- fake/test bindings work

### 3. Domain negotiation

Main domain has `negotiation`. I did not see matching test paths.

Needs:

- bill negotiation eligibility
- no-offer state
- provider failure
- privacy/AI blocked state
- subscription price increase trigger

### 4. Domain config/core/diagnostics/dto/performance/service/workers

These main-domain folders do not have clear same-name test coverage.

Add at least contract/smoke tests:

- config defaults and overrides
- diagnostic event formatting/sanitization
- DTO mapping stability
- performance cache eviction
- worker policy/idempotency
- service orchestration boundaries

### 5. Backup/restore end-to-end

There is `DatabaseBackupRepositoryImplTest`, which is good, but backup/restore needs a stronger scenario.

Needs:

- encrypted backup creation
- restore journal
- partial restore failure
- workers paused during restore
- schema/version compatibility
- restored analytics/dashboard totals match pre-backup totals

### 6. Email receipt pipeline

I found email ingestion and provider parser tests. Good.

Missing higher-level scenario:

```text
email receipt -> parser -> receipt lifecycle -> matching -> warranty/price/subscription -> analytics
```

This is important because email receipts are another ingestion pipeline parallel to notification/OCR/bank import.

### 7. Import/export roundtrip

You have export/import-related tests, but add a roundtrip contract:

```text
seed DB -> export CSV/accounting -> import into fresh DB -> dashboard/analytics equal expected
```

Financial apps need this.

### 8. Location/privacy/map pipeline

Location/geocoding tests exist. But the app also has map UI and privacy implications.

Scenario needed:

```text
expense merchant -> merchant key -> geocoding -> location enrichment -> map aggregate -> privacy redaction/export behavior
```

### 9. Bank connection/auth failure lifecycle

There is `BankApiIntegrationTest`, but add scenarios for:

- token expired
- sync partial failure
- duplicate bank transaction
- review required
- source tracking
- dashboard only includes approved transactions

### 10. Android notification capture/service reliability

There are notification service tests and stress tests, but many are likely isolated.

Needs Android/Robolectric-ish contract:

- notification permission denied
- notification listener disabled
- oversized amount rejected
- parser failure goes to review/error state
- service restart does not duplicate captured transactions

---

## 4. Updated canonical scenario catalog

Previous scenarios remain, but add these.

### Scenario 13 — `email_receipt_lifecycle_warranty_price`

Input:

- Amazon/Apple/Uber-style receipt email
- existing matching expense
- category rules
- warranty-eligible item
- price-protection-eligible item

Assert:

- email parser output
- receipt created through lifecycle
- receipt event log
- receipt linked to existing expense
- no duplicate expense
- warranty created only when eligible
- price protection created only when eligible
- analytics counts once

Priority: high.

---

### Scenario 14 — `backup_restore_roundtrip_workers_paused`

Input:

- seeded DB with expenses, receipts, rates, groups, recurring, AI artifacts
- active pending workers
- backup
- restore into fresh DB

Assert:

- restore journal sequence
- workers paused during restore
- schema version accepted
- restored dashboard equals original
- restored analytics equals original
- receipt links preserved
- group balances preserved
- exchange rates preserved
- privacy audit preserved
- workers resume only after successful restore

Priority: highest.

---

### Scenario 15 — `csv_accounting_export_import_roundtrip`

Input:

- deterministic seeded month
- special CSV characters
- multi-currency expenses
- split/group expenses
- business/tax fields

Assert:

- CSV escaping safe
- import into fresh DB succeeds
- totals match original
- category totals match original
- business/tax fields preserved
- unsupported fields are explicitly reported, not silently lost

Priority: high.

---

### Scenario 16 — `location_map_privacy_contract`

Input:

- merchants with ambiguous names
- location permission variants
- geocoding success/failure
- privacy export setting

Assert:

- merchant key stable
- geocoding retry/cancellation behavior
- map aggregate correct
- location failure visible but non-fatal
- private location fields redacted from export/AI payloads when policy requires

Priority: medium-high.

---

### Scenario 17 — `bank_sync_auth_failure_review_lifecycle`

Input:

- connected bank
- expired token
- partial transaction sync
- duplicate imported transaction
- one low-confidence merchant

Assert:

- auth failure state shown
- partial sync does not corrupt DB
- duplicate skipped
- low-confidence item goes to review
- approved item creates expense through lifecycle
- source/origin preserved
- dashboard only includes approved non-duplicates

Priority: high.

---

### Scenario 18 — `ai_provider_fallback_policy_persistence`

Input:

- cloud enabled/disabled variants
- on-device available/unavailable
- sensitive query/receipt/notification
- DataStore AI settings

Assert:

- correct provider chosen
- denied capability writes privacy/audit event
- redaction happens before cloud call
- fallback state is deterministic
- settings persist and affect later use case
- AI artifact stores sanitized source hash, not raw sensitive text if forbidden

Priority: high.

---

## 5. Updated action plan v3

## P0 — repository and release safety

1. Remove/audit committed `.db` backup.
2. Remove generated repo dumps from source.
3. Add `.github/workflows/ci.yml`.
4. Make Room schema verifier v113 source-of-truth-driven.
5. Wire schema verifier into `check`.
6. Add docs drift check:
   - DB version
   - SDK versions
   - Kotlin version
   - test coverage claims
7. Add lifecycle bypass scanner.
8. Add ignored-test-count guard.
9. Add secret scan / hardcoded key scan.
10. Add test inventory generation scanning both `src/test/java` and `src/test/kotlin`.

## P1 — coverage map and fixture infrastructure

1. Generate production-vs-test package map.
2. Mark each package as:
   - no test
   - unit only
   - repository/integration
   - scenario
   - instrumented
3. Create official `testfixtures` package.
4. Create scenario seeder/runner.
5. Create golden verifier.
6. Standardize fake clock/coroutine/Room DB factory.
7. Convert existing `FlowPipelineTestHarness` into official scenario harness.

## P2 — highest-value scenarios

Implement first:

1. `schema_v113_migration_fresh_install_parity`
2. `transaction_lifecycle_db_contract`
3. `notification_review_dashboard_budget`
4. `receipt_matching_analytics_no_double_count`
5. `multicurrency_partial_rate_dashboard_analytics`
6. `backup_restore_roundtrip_workers_paused`

## P3 — broaden ingestion pipelines

Add:

1. `email_receipt_lifecycle_warranty_price`
2. `bank_sync_auth_failure_review_lifecycle`
3. `csv_accounting_export_import_roundtrip`
4. `location_map_privacy_contract`

## P4 — under-tested feature contracts

Add focused tests for:

- negotiation
- startup
- config
- diagnostics
- performance/cache
- domain workers/service orchestration
- lifestyle domain engine
- investment repository/UI scenario if existing investment tests are only pure-domain

## P5 — Android/instrumented smoke

Add:

- Hilt graph smoke
- WorkManager worker construction smoke
- encrypted key storage Android smoke
- notification permission/listener smoke
- backup restore filesystem smoke
- Compose navigation smoke for top-level screens

---

## 6. Revised “untested/weakly tested” list

### Probably weak or missing direct tests

- `startup`
- `domain/negotiation`
- `domain/config`
- `domain/core`
- `domain/diagnostics`
- `domain/dto`
- `domain/performance`
- `domain/service`
- `domain/workers`
- `data/provider`
- full DI graph
- Android permission/service integration
- backup/restore full app roundtrip
- CSV/accounting import/export roundtrip
- startup + restore + workers combined flow

### Tested but needs scenario promotion

- AI providers/use cases
- email receipt ingestion
- notification processing
- receipt processing
- location/geocoding
- backup repository
- security key storage
- recommendation service
- dashboard metrics
- recurring/group/currency/forecast engines

---

## 7. Main strategic update

Your test suite is not just “missing tests.” It is missing a **coverage control system**.

Add this as a first-class artifact:

```text
docs/testing/COVERAGE_MATRIX.md
```

Columns:

```text
Segment | Production package | Unit tests | Repo tests | Scenario tests | Android tests | Risk | Next action
```

This should be generated or checked from the repo, not manually guessed.

That will prevent exactly the problem we hit here: the codebase is large enough that humans miss folders.

---

## Sources reviewed

- Root tree / README / repo artifacts  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a

- Main app package tree  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker

- Main domain package tree  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/domain

- Main data package tree  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/data

- Test root tree  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker

- Test domain tree  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker/domain

- Test path inventory  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/_all_rel_paths.txt

- App Gradle config  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/app/build.gradle.kts

- Version catalog  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/gradle/libs.versions.toml