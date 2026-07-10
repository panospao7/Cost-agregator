# PR D — Make Migration Proof Blocking

## 1. PR definition

**Suggested title:**  
`ci(database): make Room migration execution and schema parity blocking`

**Base:** Successful final commit of PR C.

**Reference snapshot:**  
`ebb5aa93348282b31c1c669d1bf1271d584b9eb0`

**Primary issues:**

- MIT-004 — Real migration execution matrix in CI
- MIT-010 — Supported migration-chain policy
- MIT-011 — Fresh-versus-migrated schema parity
- MIT-033 — DB uniqueness/idempotency constraints, verification only
- MIT-001 — Required CI coverage

**Estimated effort:** 5–9 engineering days.

---

# 2. Objective

Create a required CI check that proves, by executing Room and SQLite:

1. Every supported database version migrates to the current version.
2. Every migration edge executes successfully.
3. The production database builder registers the same migrations tested by CI.
4. Required schema snapshots cannot be absent or skipped.
5. Existing representative data survives supported migrations.
6. Fresh-install and migrated schemas are semantically equivalent.
7. Tables, columns, defaults, indexes, uniqueness, foreign keys, triggers and views are verified.
8. SQLite integrity and foreign-key checks pass.
9. Unsupported versions follow one explicit, tested product policy.
10. Migration test failures, skipped tests and emulator failures block merging.

The existing static migration guard checks migration declarations and registration, but it does not execute the SQL. The actual instrumented-test job only runs on protected-branch pushes or manual dispatch and is marked `continue-on-error`, so it cannot currently block a pull request. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/.github/workflows/ci.yml))

---

# 3. Current gaps to correct

## 3.1 Static registration is not execution proof

`verify_migration_matrix.py` is useful as a fast preflight, but it cannot prove:

- SQL statements execute on SQLite.
- Data survives table rebuilds.
- Defaults and constraints are correct.
- Foreign keys point to valid tables.
- The production Room builder actually opens an old database.
- Fresh-install and migrated schemas are equivalent.

Keep the static guard, but treat it as complementary—not sufficient.

## 3.2 Existing matrix tests can skip silently

`DatabaseMigrationMatrixTest`:

- Hardcodes baseline `145`.
- Hardcodes current version `148`.
- Calls `assumeTrue()` when schemas are missing.
- Uses table-name-only comparison for fresh/migrated parity.
- Creates its “fresh” DB from an exported schema rather than the actual production builder.
- Contains full-chain calls without explicitly supplying the migration registry.
- Has a pre-baseline test whose comments accept either preserved or destroyed data.

These behaviors can produce skipped or weakly passing tests instead of proof. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationMatrixTest.kt))

## 3.3 Runtime registration needs direct proof

At the reference snapshot, `APP_DATABASE_SCHEMA_VERSION` is 148. `DatabaseMigrations.ALL` contains `145→146`, `146→147` and `147→148`, and the canonical AppDatabase builder calls `.addMigrations(*ALL_MIGRATIONS)`, where `ALL_MIGRATIONS` delegates to that registry. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt))

Tests that manually pass migration objects can still succeed if the production builder accidentally omits one. PR D must test both:

- Individual migration implementation.
- Actual production-builder registration.

## 3.4 Schema asset configuration exists but must fail closed

The project exports Room schemas through KSP, packages them as Android-test assets and includes `room-testing`. That provides the necessary foundation, but missing assets must become failures rather than assumptions/skips. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/build.gradle.kts))

---

# 4. Non-goals

Do not include:

- Restore-safe singleton DB lifetime changes.
- Full historical migration support below the selected baseline.
- New DB uniqueness constraints unrelated to the current schema.
- General instrumented-test stabilization.
- Release APK/AAB verification.
- Full migration of legacy inline migration code.
- Automatic acceptance of schema drift.
- Increasing emulator retries until a real migration failure disappears.

Do not:

- Use `continue-on-error`.
- Use `assumeTrue`, `@Ignore` or `@Disabled` in the blocking suite.
- Accept “data may be preserved or destroyed.”
- Compare only table names.
- test only manually supplied migrations.
- add a migration-test baseline that tolerates failures.

---

# 5. Workstream D1 — Establish one migration policy source

## 5.1 Add a production policy object

Create:

`data/database/DatabaseSchemaPolicy.kt`

It should expose:

- Current schema version.
- Minimum supported migration version.
- Canonical migration registry.
- Unsupported-version policy.
- Downgrade policy.
- Database class/name.

Conceptual fields:

```kotlin
object DatabaseSchemaPolicy {
    const val MIN_SUPPORTED_VERSION = 145
    const val CURRENT_VERSION = APP_DATABASE_SCHEMA_VERSION

    val migrations: Array<Migration>
        get() = DatabaseMigrations.ALL

    val unsupportedUpgradePolicy =
        UnsupportedUpgradePolicy.BLOCK_AND_RESCUE

    val downgradePolicy =
        DowngradePolicy.REJECT_WITHOUT_MUTATION
}
```

Tests, Gradle tasks and Python guards must derive from this source instead of duplicating `145` or `148`.

## 5.2 Resolve the unsupported-version contradiction

Before coding, choose exactly one policy for versions below the supported baseline.

### Preferred policy: block and rescue

For financial data, prefer:

1. Inspect `PRAGMA user_version` before opening Room.
2. If below the supported baseline, do not open through Room.
3. Preserve the original DB file unchanged.
4. Route the user to the financial rescue/export/import flow.
5. Emit only a sanitized reason code.
6. Never automatically wipe the database.

Tests must prove the old file and rows remain unchanged.

### Alternative: destructive migration

If the product explicitly chooses destructive fallback:

- Enumerate exactly which source versions may be destroyed.
- Test the production builder, not `MigrationTestHelper` alone.
- Prove old rows are removed.
- Prove the new schema is current and valid.
- Require explicit product documentation and user-facing warning.
- Do not describe both destruction and preservation as acceptable.

PR D cannot complete while the policy remains ambiguous.

## 5.3 Downgrade policy

Define downgrades as unsupported unless there is a real downgrade migration.

Test that opening a DB with a version newer than the app:

- Fails deterministically.
- Does not mutate the file.
- Does not reset `user_version`.
- Does not delete user tables.

---

# 6. Workstream D2 — Strengthen static migration preflight

Retain and extend `verify_migration_matrix.py`.

## 6.1 Required validations

The guard must verify:

1. Current version comes from the production version constant.
2. Minimum supported version comes from the production policy.
3. Exactly one contiguous upgrade edge exists for every supported step.
4. Every migration definition appears in the canonical registry.
5. Every registry entry has a definition.
6. No duplicate or conflicting edge exists.
7. No edge moves backward or skips a version without an explicit approved policy.
8. Every supported version has a committed schema JSON.
9. Latest schema JSON exists.
10. Schema JSON filename and internal version agree.
11. The production builder registers the canonical registry.
12. Blocking migration test classes exist.
13. The blocking suite contains no assumptions or ignored tests.

## 6.2 Add migration-proof suite guard

Create:

`scripts/verify_migration_proof_suite.py`

It should fail when:

- `Assume` or `assumeTrue` appears in the blocking migration package.
- `@Ignore` or `@Disabled` appears.
- A required migration test class disappears.
- A new migration edge has no corresponding data contract.
- A hardcoded current-version literal appears in migration tests.
- The production builder no longer references the canonical registry.
- Expected JUnit test-count metadata is stale.

## 6.3 Static-guard tests

Add fixtures for:

- Missing migration.
- Duplicate edge.
- Missing registry entry.
- Missing snapshot.
- Snapshot internal-version mismatch.
- Hardcoded current version.
- Assumption-based test.
- Ignored migration test.
- Alternate builder omitting migrations.
- Missing edge data contract.
- Malformed policy source.

All configuration/read failures must exit with code 2.

---

# 7. Workstream D3 — Replace the migration execution matrix

Refactor or replace `DatabaseMigrationMatrixTest`.

Recommended blocking classes:

```text
DatabaseMigrationEdgeTest
DatabaseMigrationChainTest
DatabaseMigrationRuntimeBuilderTest
DatabaseFreshSchemaParityTest
DatabaseUnsupportedVersionPolicyTest
DatabaseMigrationFailureAtomicityTest
```

Keep them in a dedicated package such as:

```text
app/src/androidTest/.../data/database/migrationproof/
```

## 7.1 Direct edge tests

For every migration in the canonical registry:

1. Create a DB at the edge’s start version from the committed schema.
2. Insert representative pre-migration rows.
3. Run that exact migration with `MigrationTestHelper`.
4. Validate against the target Room schema.
5. Verify expected schema changes.
6. Verify seeded data.
7. Run SQLite integrity checks.
8. Close and delete the DB.

Use a parameterized edge catalog derived from `DatabaseMigrations.ALL`.

Do not manually maintain an unrelated list of versions.

## 7.2 Current edge data contracts

### 145 → 146

Verify:

- `negotiation_outcomes` exists.
- All intended columns and nullability are correct.
- Foreign key to `manual_recurring_expenses` exists.
- Delete behavior is `CASCADE`.
- All three expected indexes exist.
- A seeded recurring/subscription row survives.
- A valid negotiation outcome can be inserted.
- An invalid foreign-key value is rejected when foreign keys are enabled.

### 146 → 147

Verify:

- `group_members.leftAt` exists and is nullable.
- `group_expenses.idempotencyKey` exists and is nullable.
- The `(groupId, name)` index is non-unique after migration.
- Existing group/member/expense rows survive.
- The new idempotency field can be populated.
- The index behavior matches the product decision for member re-admission.

### 147 → 148

Verify:

- All worker-run tracing columns exist.
- Existing `background_job_runs` rows survive.
- New nullable columns are null for old rows.
- Column types and defaults match the entity schema.
- DAO-level read/write succeeds after opening Room.

## 7.3 Full-chain tests from every supported start

For current reference versions, execute:

- 145 → current
- 146 → current
- 147 → current
- Fresh current install

Future version bumps must add the new starting version automatically.

For `MigrationTestHelper` tests, explicitly pass the canonical registry:

```kotlin
helper.runMigrationsAndValidate(
    databaseName,
    DatabaseSchemaPolicy.CURRENT_VERSION,
    true,
    *DatabaseSchemaPolicy.migrations
)
```

No full-chain test may rely on an empty migration vararg.

## 7.4 Production-builder tests

For every supported start version:

1. Create the old DB with `MigrationTestHelper`.
2. Seed representative data.
3. Close the helper DB.
4. Open the same file using `AppDatabase.fileBuilder(...)`.
5. Trigger opening through `openHelper.writableDatabase`.
6. Query through at least one real DAO.
7. Assert `PRAGMA user_version` equals current.
8. Assert seeded data survived.
9. Run integrity checks.

This test must not pass migrations manually. It proves the shipping builder is configured correctly.

A regression that removes a migration from the production registry must fail even if direct edge tests still pass.

---

# 8. Workstream D4 — Add representative data-preservation fixtures

## 8.1 Fixture strategy

Create:

```text
MigrationFixtureSeeder
MigrationFixtureAssertions
MigrationDataContract
```

Each migration edge should define:

- Required source tables.
- Seed SQL.
- Expected target rows.
- Expected defaults.
- Expected transformations.
- Expected constraints.
- Intentionally discarded fields, if any.

## 8.2 Full-chain representative fixture

Seed a small, internally consistent graph covering critical tables that exist at the baseline:

- Category.
- Expense.
- Manual recurring expense.
- Planned/recurring row.
- Receipt and receipt link where available.
- Group/member/group expense.
- Background operation/job row.
- Lifecycle/event row where available.

Use fixed synthetic values. Do not include real PII.

## 8.3 Assertions

After migration, prove:

- Primary keys remain stable.
- Foreign-key references remain valid.
- Financial amount/currency values remain unchanged unless migration explicitly transforms them.
- Timestamps remain unchanged.
- Null values remain semantically correct.
- Boolean/integer flags remain correct.
- Unique indexes still reject duplicates.
- Required defaults apply to newly added columns.
- Row counts are as expected.

Avoid tests that merely prove the table can be queried.

---

# 9. Workstream D5 — Implement full schema-semantic parity

## 9.1 Correct fresh-install path

Create the fresh DB using the actual production Room builder:

```kotlin
AppDatabase.fileBuilder(context, freshDatabaseName)
    .build()
```

Force the database open before inspecting it.

Do not use `MigrationTestHelper.createDatabase(currentVersion)` as the only definition of “fresh install”; that creates a database from the committed schema snapshot rather than proving current entities and callbacks produce the expected schema.

## 9.2 Correct migrated path

1. Create a DB at the minimum supported version.
2. Seed representative data.
3. Close it.
4. Open it with the production builder.
5. Let the canonical production registry execute.
6. Capture its schema.

## 9.3 Build a structured schema descriptor

Create a test utility that captures:

### Tables and columns

Use `PRAGMA table_xinfo` or `table_info`:

- Name.
- Type.
- Nullability.
- Default value.
- Primary-key order.
- Hidden/generated-column metadata.

### Foreign keys

Use `PRAGMA foreign_key_list`:

- Source columns.
- Target table and columns.
- Update action.
- Delete action.
- Match behavior.

### Indexes

Use `PRAGMA index_list` and `index_xinfo`:

- Indexed columns and ordering.
- Unique flag.
- Partial flag.
- Origin.
- Expressions where available.

Normalize SQLite auto-index names while retaining their semantic uniqueness constraints.

### Triggers and views

Read normalized definitions from `sqlite_master`.

### Room identity

Capture:

- `PRAGMA user_version`.
- `room_master_table` identity hash.

### Integrity

Execute:

```sql
PRAGMA integrity_check
PRAGMA foreign_key_check
```

`integrity_check` must return `ok`; `foreign_key_check` must return zero rows.

## 9.4 Comparison rules

Fresh and migrated descriptors must match exactly after normalization.

Do not ignore:

- Missing indexes.
- Unique versus non-unique differences.
- Foreign-key actions.
- Default values.
- Nullability.
- Partial-index predicates.
- Trigger definitions.
- Column ordering where it affects Room validation.

Allow exclusions only for documented SQLite-internal metadata.

## 9.5 Diagnostic output

On mismatch, write:

```text
app/build/outputs/migration-proof/
  fresh-schema.json
  migrated-schema.json
  schema-diff.json
  schema-diff.md
```

The diff should group mismatches by:

- Missing object.
- Extra object.
- Column mismatch.
- Index mismatch.
- FK mismatch.
- Trigger/view mismatch.
- Room identity mismatch.

Do not expose seeded row values in CI output.

---

# 10. Workstream D6 — Test unsupported-version behavior

Replace the ambiguous pre-baseline matrix test.

## If block-and-rescue is selected

Test that:

1. A pre-baseline database is created and seeded.
2. Its file hash and selected rows are recorded.
3. Production preflight detects the unsupported version.
4. Room is not opened.
5. No journal/schema mutation occurs.
6. File hash or semantic DB content remains unchanged.
7. A typed `UpgradeRequiresRescue` result is returned.
8. Sanitized diagnostics contain no file path or row data.

## If destructive fallback is selected

Test that:

1. Only policy-approved versions trigger fallback.
2. Seeded data is definitely removed.
3. Current schema is created.
4. Integrity and parity pass.
5. A durable sanitized reason code records the destructive transition.
6. Supported versions never take the destructive path.

There must be no assertion or comment stating that either data outcome is acceptable.

---

# 11. Workstream D7 — Test migration failure atomicity

Add a controlled failing migration fixture.

## Required scenario

1. Create a source-version database.
2. Seed data.
3. Execute a test migration that performs one schema change and then throws.
4. Close the failed connection.
5. Reopen the original version safely.
6. Verify:
   - User version was not advanced.
   - Original data remains.
   - No half-created committed schema remains.
   - Retrying with the valid migration succeeds.

This verifies the expected transactional behavior and protects against migrations that manually manage transactions incorrectly.

Also add a source guard discouraging nested manual `beginTransaction()` inside Room migrations unless narrowly justified and tested.

---

# 12. Workstream D8 — Make schema snapshots authoritative

## 12.1 Remove test assumptions

Replace:

```kotlin
assumeTrue(hasSchema(version))
```

with assertions that fail:

```kotlin
assertTrue(
    "Required Room schema snapshot missing for version $version",
    hasSchema(version)
)
```

Prefer a class-level precondition that validates the complete supported snapshot set before any migration runs.

## 12.2 Verify generated schemas are committed

In CI:

1. Run the Room/KSP schema-generation task.
2. Compare `app/schemas/` with Git.
3. Fail if generation changes a tracked schema unexpectedly.
4. Fail if the current-version snapshot is untracked.
5. Fail if a supported-version snapshot was deleted.

Conceptual command:

```bash
./gradlew :app:kspDebugKotlin --no-daemon --stacktrace
git diff --exit-code -- app/schemas
```

If generated output includes nondeterministic metadata, normalize or fix generation rather than ignoring the entire file.

## 12.3 Rewrite snapshot verification source

The Gradle task and Python script must read:

- Version from `DatabaseSchemaPolicy`.
- Baseline from `DatabaseSchemaPolicy`.
- Migration edges from `DatabaseMigrations.ALL`.

Do not scan the giant AppDatabase source for every historical `MIGRATION_X_Y` token, because unused legacy declarations should not imply runtime support.

---

# 13. Workstream D9 — Create the blocking CI job

## 13.1 Dedicated jobs

Add a matrix execution job:

```text
migration-proof-tests
```

Add a stable aggregator job:

```text
migration-proof
name: Migration Proof
```

The aggregator gives branch protection one stable required check name.

## 13.2 Trigger policy

Run on:

- Every pull request targeting `main` or `master`.
- Every push to `main` or `master`.
- Manual dispatch.

Do not restrict it to database-file changes in the first blocking implementation. Entity, converter, plugin or callback changes can alter the schema indirectly.

## 13.3 API matrix

Run on:

- The oldest emulator API supported and maintained for this app.
- API 34.

Determine the oldest lane from the actual application `minSdk`, not a duplicated undocumented number.

Both matrix lanes must pass. SQLite behavior can differ across Android releases.

If initial runtime is prohibitive, land API 34 as blocking first only with a short, explicit follow-up issue for the oldest-supported API. Do not call cross-version migration compatibility complete until both lanes block.

## 13.4 Job configuration

For each lane:

- `ubuntu-latest`.
- JDK 17.
- Gradle cache.
- KVM enabled.
- Deterministic emulator configuration.
- No network dependency.
- Test animations disabled.
- 45–60 minute timeout based on measured runtime.
- No `continue-on-error`.

Use the existing emulator runner, but invoke only migration-proof classes.

Conceptual command:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.yourname.expensetracker.data.database.migrationproof \
  --no-daemon \
  --stacktrace
```

## 13.5 Test Orchestrator

The build config requests AndroidX Test Orchestrator. Confirm the corresponding `androidTestUtil` dependency is present and version-compatible; add it if absent.

Use one isolated process per test where practical so a failed migration cannot contaminate later tests.

## 13.6 Stable aggregator

The final `Migration Proof` job must use `if: always()` and fail unless:

- Static migration preflight passed.
- Both emulator matrix lanes passed.
- Result postprocessing passed.
- Expected artifacts exist.

A cancelled, skipped or timed-out matrix lane is a failed migration proof.

## 13.7 Branch protection

After the first successful run:

1. Add `Migration Proof` to required status checks.
2. Keep the check name stable.
3. Enable “require branch to be up to date.”
4. Do not allow administrator bypass.
5. Update branch-protection documentation.

The general `Instrumented Tests` job may remain non-blocking, but migration proof must be independent from it.

---

# 14. Workstream D10 — Detect skipped or missing test execution

Create:

`scripts/verify_migration_test_results.py`

Inputs:

- JUnit XML directory.
- Expected test manifest.
- API level.

Fail when:

- Any test failed.
- Any test errored.
- Any test was skipped.
- A required class did not execute.
- Actual test count is below expected.
- Duplicate test identifiers appear.
- JUnit XML is absent or malformed.
- Instrumentation crashed before producing results.

Maintain an expected-class manifest, not merely a minimum global count.

Example:

```json
{
  "required_classes": [
    "DatabaseMigrationEdgeTest",
    "DatabaseMigrationChainTest",
    "DatabaseMigrationRuntimeBuilderTest",
    "DatabaseFreshSchemaParityTest",
    "DatabaseUnsupportedVersionPolicyTest",
    "DatabaseMigrationFailureAtomicityTest"
  ]
}
```

The manifest should not hardcode individual parameterized-case counts unless generated from the migration registry.

---

# 15. Workstream D11 — Clean up stale migration tests

Inventory:

- `DatabaseMigrationMatrixTest`
- `DatabaseMigrationTest`
- `MigrationContractTest`
- `MigrationRegistrationTest`

## Required cleanup

1. Move active executable proof into the dedicated blocking package.
2. Remove obsolete version names such as tests claiming an outdated “current” version.
3. Remove `assumeTrue` from release-critical migration coverage.
4. Delete contradictory tests that claim unsupported versions both migrate and destructively reset.
5. Keep static registration tests as supplemental coverage.
6. Keep historical tests only if they represent an intentionally supported path.
7. Do not rename stale tests merely to escape the release-critical denylist.
8. Keep `DatabaseMigrationTest` or its direct replacement on the release-critical test policy.

`MigrationContractTest` and Python registration checks may remain, but they must not be described as substitutes for actual SQLite execution.

---

# 16. Required seeded-regression scenarios

CI/test fixtures must prove detection of these regressions:

1. Remove one migration from `DatabaseMigrations.ALL`.
2. Define a migration but omit it from the registry.
3. Add schema version N+1 without migration N→N+1.
4. Delete a supported schema JSON.
5. Corrupt a migration SQL statement.
6. Add a new non-null column without a valid migration/default.
7. Change an index from unique to non-unique.
8. Remove a foreign key.
9. Change an `ON DELETE` action.
10. Alter a default value only on the fresh path.
11. Add an entity index without migration SQL.
12. Add migration SQL without updating the entity/schema.
13. Introduce `assumeTrue` into the blocking suite.
14. Remove a required migration test class.
15. Make the production builder omit the canonical registry.
16. Leave a half-completed migration after injected failure.
17. Change unsupported-version behavior without updating policy/tests.
18. Produce no JUnit result files.
19. Skip a parameterized migration edge.
20. Pass on API 34 but fail on the oldest supported API.

---

# 17. CI artifacts and diagnostics

Always upload, with `if: always()`:

```text
app/build/reports/androidTests/**
app/build/outputs/androidTest-results/**
app/build/outputs/migration-proof/**
build/ci/migration-proof/**
```

Include:

- JUnit XML.
- HTML reports.
- Device/API information.
- SQLite version.
- Test manifest comparison.
- Fresh schema descriptor.
- Migrated schema descriptor.
- Schema diff.
- Gradle profile.
- Sanitized logcat around failures.

Use:

```yaml
if-no-files-found: error
```

Retention recommendation:

- Successful proof: 7 days.
- Failed proof: 14 days if workflow structure permits.

Do not upload database files containing seeded user-like values.

---

# 18. Local developer workflow

Document:

```bash
# Fast static preflight
python3 scripts/verify_migration_matrix.py --fail-on-violation
python3 scripts/verify_migration_proof_suite.py --fail-on-violation

# Schema snapshots
./gradlew :app:verifyRoomSchemaSnapshots \
  -PstrictRoomSchemas=true \
  --stacktrace

# Generate schema and prove repository is clean
./gradlew :app:kspDebugKotlin --stacktrace
git diff --exit-code -- app/schemas

# Blocking migration package on a connected emulator
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.yourname.expensetracker.data.database.migrationproof \
  --stacktrace

# Validate JUnit results
python3 scripts/verify_migration_test_results.py \
  --results app/build/outputs/androidTest-results
```

---

# 19. Schema-version bump workflow after PR D

Every future version bump must include, in one PR:

1. Increment `APP_DATABASE_SCHEMA_VERSION`.
2. Add `MIGRATION_N_N+1`.
3. Register it in `DatabaseMigrations.ALL`.
4. Generate and commit schema N+1.
5. Add the edge data contract.
6. Add representative source rows.
7. Add post-migration assertions.
8. Pass migration from every supported start version.
9. Pass production-builder migration.
10. Pass fresh-versus-migrated semantic parity.
11. Pass oldest-supported and current API lanes.
12. Update migration documentation.

CI must fail if any step is omitted.

---

# 20. Recommended commit sequence

## Commit D1

`refactor(database): centralize supported schema and migration policy`

Contains:

- `DatabaseSchemaPolicy`.
- Canonical baseline/current/unsupported policy.
- Registry alignment.
- Static policy tests.

## Commit D2

`test(database): replace skippable migration tests with executable matrix`

Contains:

- Direct edge tests.
- Every-supported-start chain tests.
- Production-builder tests.
- Data fixtures.
- Removal of assumptions.

## Commit D3

`test(database): prove fresh and migrated schema parity`

Contains:

- Schema descriptor.
- Full semantic comparison.
- Integrity/FK checks.
- Diff artifacts.

## Commit D4

`test(database): enforce unsupported and failed migration behavior`

Contains:

- Pre-baseline policy tests.
- Downgrade tests.
- Failure-atomicity test.

## Commit D5

`ci(database): make migration proof a required PR check`

Contains:

- Emulator matrix.
- Stable aggregator.
- Result verifier.
- Artifact upload.
- Removal of non-blocking migration reliance.

## Commit D6

`docs(database): document supported migration policy and blocking proof`

Created only after an actual successful Actions run.

---

# 21. Risks and mitigations

## Emulator instability

**Risk:** Infrastructure flakes block unrelated PRs.

**Mitigation:**

- Run only migration-proof classes.
- Use cached AVDs.
- Disable animations.
- Avoid network/service dependencies.
- Use isolated DB names.
- Delete files in teardown.
- Separate emulator boot failures from test failures in reporting.
- Do not automatically retry failed migration assertions.

## Schema-comparison false positives

**Risk:** Harmless SQL formatting differences fail parity.

**Mitigation:**

- Compare structured PRAGMA output.
- Normalize quoting and internal auto-index names.
- Compare semantics, not raw `CREATE TABLE` formatting.
- Keep triggers/views normalized separately.

## Insufficient data fixtures

**Risk:** Schema validates while data is silently corrupted.

**Mitigation:**

- Seed each changed table.
- Assert values, keys and relationships.
- Require one data contract per migration edge.
- Review migrations involving table rebuilds more strictly.

## Runtime inflation

**Risk:** Two emulator lanes significantly increase CI duration.

**Mitigation:**

- Run lanes in parallel.
- Compile/cache Gradle outputs.
- Keep the migration suite isolated.
- Avoid running the entire instrumented suite.
- Measure p95 and set bounded timeouts.

## Unsupported-version policy changes scope

**Risk:** Resolving destructive-versus-rescue behavior expands the PR.

**Mitigation:**

- Make the decision before implementation.
- Prefer preserving data and routing to existing rescue mechanisms.
- If necessary, land the policy abstraction first, but do not enable blocking claims while behavior remains ambiguous.

---

# 22. PR acceptance checklist

## Policy

- [ ] One minimum supported version source.
- [ ] Current version is derived, not hardcoded in tests.
- [ ] Unsupported upgrade policy is explicit.
- [ ] Downgrade policy is explicit.
- [ ] Production and tests use the same migration registry.

## Static verification

- [ ] Complete contiguous supported chain verified.
- [ ] Every supported snapshot exists.
- [ ] Registry/definition parity verified.
- [ ] Production builder registration verified.
- [ ] Assumptions and ignored migration tests rejected.
- [ ] Guard errors exit with code 2.

## Execution proof

- [ ] Every direct migration edge executes.
- [ ] Every supported start version reaches current.
- [ ] Production builder opens every supported source version.
- [ ] Representative data survives.
- [ ] Defaults and transformations are asserted.
- [ ] Integrity check passes.
- [ ] Foreign-key check passes.
- [ ] DAO smoke query passes.

## Schema parity

- [ ] Fresh DB is created through production Room builder.
- [ ] Migrated DB uses production registry.
- [ ] Tables match.
- [ ] Columns/nullability/defaults match.
- [ ] Indexes and uniqueness match.
- [ ] Foreign keys/actions match.
- [ ] Triggers and views match.
- [ ] Room identity/current version match.
- [ ] Machine-readable diff is generated.

## Unsupported/failure behavior

- [ ] Pre-baseline behavior has one deterministic outcome.
- [ ] Data preservation or destruction is explicitly asserted.
- [ ] Downgrade leaves DB unmodified.
- [ ] Injected migration failure rolls back.
- [ ] Valid retry succeeds.

## CI

- [ ] Migration Proof runs on every PR.
- [ ] No `continue-on-error`.
- [ ] Required API lanes pass.
- [ ] Skipped tests fail.
- [ ] Missing JUnit reports fail.
- [ ] Artifacts upload on pass and failure.
- [ ] Stable `Migration Proof` check is required by branch protection.
- [ ] Two consecutive Actions runs pass.

---

# 23. Definition of done

PR D is complete only when an actual pull-request Actions run proves:

- Static migration registration passes.
- Every supported migration executes.
- The shipping builder—not a test-only builder—opens every supported source version.
- Missing schemas fail rather than skip.
- Representative financial and relational data survives.
- Fresh and migrated schemas are semantically identical.
- Unsupported versions follow one deterministic policy.
- Failed migrations leave no partial committed state.
- Every required migration test executes on the configured API lanes.
- The stable `Migration Proof` status check blocks merging.
- No migration failure is hidden by assumptions, ignored tests, warning mode or `continue-on-error`.

The required invariant is:

> **A schema or migration change cannot merge unless CI executes the shipping migration path and proves both data preservation and final-schema correctness.**