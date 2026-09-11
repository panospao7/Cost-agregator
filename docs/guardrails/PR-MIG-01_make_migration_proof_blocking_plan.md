# PR-MIG-01 — Make Room migration proof blocking

## Agent mission

Implement a required, executable migration-proof system that blocks a pull request unless the repository proves:

1. every supported version migrates to current;
2. every active migration edge executes;
3. the real production Room builder registers and executes the canonical migration registry;
4. representative synthetic data survives supported migrations;
5. fresh and migrated databases are semantically equivalent;
6. schema snapshots are present and trustworthy;
7. unsupported older databases follow exactly one tested product policy;
8. migration failure/rollback behavior is explicitly tested;
9. migration test execution is real, complete, non-skipped, and artifact-verified;
10. CI runs the proof on every pull request with no `continue-on-error`.

The current migration matrix ratchet is not sufficient: it is a static source/snapshot checker with an empty ratchet baseline, while the acceptance contract requires SQL/Room execution, real builder coverage, structured schema parity, no skips, and PR-blocking CI. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

## Required predecessors

Do not begin unless:

```text
GATE-00R is complete for exact start SHA
GR-10A canonical command ownership is merged
GR-10B source-scope authority is merged
GR-10D documentation truth sync is merged
GUARD-QA-00 is complete
MIG-00 is complete with executable evidence or an explicit infrastructure blocker
checkout is clean
```

## Absolute policy gate

MIG-01 must not choose a product data-loss policy by convenience.

Before implementation, MIG-00 must provide one approved answer:

```text
SUPPORTED_RANGE: [baseline, current]
UNSUPPORTED_OLDER_DATABASE_POLICY:
  A. BLOCK_AND_RESCUE_WITHOUT_MUTATION
  or
  B. EXPLICIT_DESTRUCTIVE_FALLBACK
DOWNGRADE_POLICY:
  A. REJECT_WITHOUT_MUTATION
  or
  B. explicit supported downgrade path
```

If the existing app has contradictory comments/tests/builders, do not merge a half-proof PR. Land or obtain the explicit policy decision first.

The final acceptance contract rejects “data may be preserved or destroyed” as a valid migration policy. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Scope

## Allowed

- `DatabaseSchemaPolicy` and related migration policy ownership;
- canonical migration registry/builder wiring where proven necessary;
- Android migration-proof test package and test fixtures;
- schema descriptor/parity utilities;
- migration proof manifests;
- `verify_migration_matrix.py` and new migration proof validators;
- canonical registry metadata/GR-10A runner integration;
- Gradle task wiring for deterministic test/schema results;
- CI migration proof jobs, artifacts, and stable aggregator;
- migration documentation.

## Forbidden

```text
DB access ratchet baseline changes
migration_matrix baseline updates
new migration debt baselines
“known gap” warning lists for supported versions
Assume/assumeTrue/@Ignore/@Disabled in blocking migration-proof package
continue-on-error for migration proof
test filters that can omit required migration proof classes
manual builder tests presented as production-builder tests
table-name-only schema parity
wildcard schema/test exclusions
real PII or production database fixtures
changing an unsupported policy only in tests/docs
raising timeout before diagnosing runtime behavior
```

Production Kotlin changes are allowed only where necessary to establish one explicit production migration policy or prove the real builder’s behavior. No unrelated database refactor belongs here.

---

# Target architecture

```text
DatabaseSchemaPolicy
      ↓
DatabaseMigrations.ALL
      ↓
all production AppDatabase builders
      ↓
static migration preflight
      ↓
MigrationProofCatalog / contracts
      ↓
Android migration-proof package
      ↓
JUnit XML verifier + schema artifacts
      ↓
Migration Proof CI matrix + stable aggregator
      ↓
branch-protection configuration in later CI-11
```

There must be one active migration registry. Historical migration declarations outside that registry must not be treated as supported merely because their symbols remain in `AppDatabase.kt`.

The current builder appears to call `.addMigrations(*ALL_MIGRATIONS)` and aliases that array to `DatabaseMigrations.ALL`; MIG-01 must preserve or strengthen that one-source relationship, then prove it through runtime tests. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt))

---

# Required deliverables

## 1. Canonical migration support policy

Create or strengthen one source-owned model, preferably:

```text
app/src/main/java/.../data/database/DatabaseSchemaPolicy.kt
```

Required API concept:

```kotlin
object DatabaseSchemaPolicy {
    const val currentVersion: Int
    const val minimumSupportedVersion: Int
    val migrations: Array<Migration>
    val unsupportedUpgradePolicy: UnsupportedUpgradePolicy
    val downgradePolicy: DowngradePolicy
    val databaseName: String
    val databaseClass: KClass<AppDatabase>
}
```

Exact Kotlin syntax may differ. Required semantics may not.

### Rules

1. Current version derives from the production Room database declaration.
2. Supported baseline is declared once.
3. Active migrations derive from the real canonical registry.
4. Every production builder receives this same migration array.
5. Unsupported upgrade policy is typed, explicit, and testable.
6. Downgrade policy is typed, explicit, and testable.
7. No test hardcodes current version or baseline.
8. No Python/Gradle checker silently falls back to comments or lowest discovered migration edge.

## 2. Migration proof manifest

Create:

```text
config/guards/migration_proof_manifest.yml
```

This is a strict proof-plan manifest, not an authorization/baseline file.

```yaml
schemaVersion: 1
database:
  policySource: app/src/main/java/.../DatabaseSchemaPolicy.kt
  migrationsSource: app/src/main/java/.../DatabaseMigrations.kt
  productionBuilderSource: app/src/main/java/.../AppDatabase.kt
  proofPackage: com.yourname.expensetracker.data.database.migrationproof

execution:
  requiredClasses:
    - DatabaseMigrationPreconditionsTest
    - DatabaseMigrationEdgeTest
    - DatabaseMigrationChainTest
    - DatabaseMigrationRuntimeBuilderTest
    - DatabaseFreshSchemaParityTest
    - DatabaseUnsupportedVersionPolicyTest
    - DatabaseMigrationFailureAtomicityTest
  requiredArtifacts:
    - junit-results
    - migration-proof-summary.json
    - schema-diff.json
    - schema-diff.md

contracts:
  - edge: [<from>, <to>]
    contractId: migration-<from>-<to>
    requiredSourceSchema: <from>
    requiredTargetSchema: <to>
```

Requirements:

- No current version/baseline number is manually copied unless validated against `DatabaseSchemaPolicy`.
- Every active registry edge has exactly one contract row.
- No contract row exists for an inactive/unsupported edge.
- Every required test class has a real source file.
- Unknown fields fail closed.
- Canonical ordering is required.

## 3. Typed Android test-side contract catalog

Create under a dedicated package:

```text
app/src/androidTest/java/.../data/database/migrationproof/
```

Suggested types:

```text
MigrationDataContract
MigrationFixtureSeeder
MigrationFixtureAssertions
MigrationProofCatalog
SchemaDescriptor
SchemaDescriptorDiff
MigrationProofArtifactWriter
```

### `MigrationDataContract` must define

```text
edge identity
source tables required
synthetic seed operation
expected preserved rows
expected new columns/defaults
expected transformed values, if any
expected indexes/FKs/constraints
expected integrity assertions
```

The catalog must derive active edges from `DatabaseSchemaPolicy.migrations` and fail when:
- a migration edge lacks a contract;
- a contract references inactive edge;
- a contract duplicates another edge.

## 4. Static preflight tools

### A. Rewrite/strengthen `verify_migration_matrix.py`

It must become a strict static preflight:

```text
0 = complete static migration configuration
1 = migration contract/config violation
2 = source/config/parser/I/O uncertainty
```

It must verify:

1. policy source exists and parses;
2. current/baseline policy values resolve;
3. active migration registry is contiguous over supported range;
4. each registry entry has exactly one actual definition;
5. no duplicate/conflicting edge;
6. every supported source/target schema snapshot exists;
7. snapshot filename and JSON internal version agree;
8. production builder references canonical migration policy/registry;
9. migration proof manifest matches active registry;
10. required Android proof classes exist;
11. no blocking proof class uses `Assume`, `assumeTrue`, `@Ignore`, or `@Disabled`;
12. no hardcoded current/baseline literal appears where policy should be referenced;
13. no static result relies on old historical migration declarations outside active registry.

Do not scan the giant `AppDatabase.kt` for every `MIGRATION_X_Y` symbol. The active registry—not historical declarations—is the support boundary.

### B. Add proof-suite static verifier

Create:

```text
scripts/verify_migration_proof_suite.py
scripts/test_verify_migration_proof_suite.py
```

It verifies:
- proof package/class manifest;
- no assumptions/ignores;
- no filtered-out required classes;
- every active edge has a data contract;
- builder test exists;
- parity test exists;
- unsupported-policy test exists;
- result verifier is configured;
- output artifact manifest is valid.

## 5. JUnit result verifier

Create:

```text
scripts/verify_migration_test_results.py
scripts/test_verify_migration_test_results.py
```

Inputs:

```text
--results <JUnit XML directory>
--manifest config/guards/migration_proof_manifest.yml
--api-level <number>
--output <safe summary JSON>
```

It must fail if:
- XML is absent/malformed;
- any proof test fails/errors;
- any proof test is skipped/ignored;
- a required class is missing;
- a duplicate test identifier exists;
- executed class/test count is implausible;
- a test suite reports zero tests;
- the instrumentation process crashed before results;
- result files are stale/wrong SHA where SHA metadata is available.

Exit mapping:

```text
0 = all required proof results present and passed
1 = actual migration test/proof failure
2 = missing/malformed/incomplete result evidence
```

Do not rely only on a global minimum test count. Verify required class identity and dynamically expected edge cases.

---

# Android migration-proof test suite

## Package boundary

All blocking migration proof must live under:

```text
com.yourname.expensetracker.data.database.migrationproof
```

The CI runner selects this package with instrumentation runner arguments. Do not use Gradle `--tests` filtering as the proof selector.

## Required classes

### 1. `DatabaseMigrationPreconditionsTest`

Proves:
- policy current/baseline resolve;
- active migration edges are contiguous;
- required schema assets exist;
- proof catalog covers all active edges;
- synthetic fixture set is safe/no PII;
- no stale proof manifest entry exists.

### 2. `DatabaseMigrationEdgeTest`

For every active migration edge:

1. create source DB from committed source schema via `MigrationTestHelper`;
2. seed only the contract’s source data;
3. run exactly that edge;
4. validate against target Room schema;
5. assert target schema changes;
6. assert source data preserved/transformed as contract says;
7. assert indexes/FKs/defaults/constraints;
8. run `PRAGMA integrity_check`;
9. run `PRAGMA foreign_key_check`;
10. close/delete test DB safely.

Use the current supported Room `MigrationTestHelper` API compatible with the resolved dependency version; do not copy an obsolete constructor blindly. The helper is designed to create an old-schema database and validate migrations against target schema. ([developer.android.com](https://developer.android.com/reference/androidx/room/testing/MigrationTestHelper?utm_source=openai))

### 3. `DatabaseMigrationChainTest`

For every supported start version:

1. create old DB at start version;
2. seed applicable representative graph;
3. run full canonical migration registry;
4. validate current schema;
5. assert all expected data contracts along path;
6. run integrity/FK checks;
7. assert current `PRAGMA user_version`.

Full-chain tests must explicitly supply the canonical migration registry. Empty migration varargs are forbidden.

### 4. `DatabaseMigrationRuntimeBuilderTest`

For every supported start version:

1. create old DB through migration test helper;
2. seed representative data;
3. close it;
4. open the **same physical database file** through the actual production `AppDatabase` builder entrypoint identified by MIG-00;
5. force Room open;
6. query at least one real DAO;
7. assert current `user_version`;
8. assert data preservation;
9. assert integrity/FK checks.

This test must not manually add migrations. It is the proof that shipping builder registration matches tested migration registry.

### 5. `DatabaseFreshSchemaParityTest`

Create two current-version databases:

```text
fresh path:
  actual production builder → force open

migrated path:
  supported old DB → production builder upgrade → force open
```

Capture a structured descriptor for each and compare semantics.

Descriptor must include:

```text
tables
columns: type/nullability/default/primary-key order/hidden metadata
foreign keys: columns/target/actions/match
indexes: columns/order/unique/partial/origin
triggers
views
Room identity hash
PRAGMA user_version
integrity result
foreign-key-check result
```

Normalize only SQLite-internal nondeterminism, such as generated auto-index names where semantic uniqueness remains equivalent.

The current table-name-only comparison is not sufficient. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationMatrixTest.kt))

### 6. `DatabaseUnsupportedVersionPolicyTest`

Implements exactly one approved product decision.

#### If `BLOCK_AND_RESCUE_WITHOUT_MUTATION`

Prove:

1. create synthetic pre-baseline DB;
2. hash/inspect it before opening;
3. invoke production upgrade preflight;
4. Room is not opened;
5. user DB file and selected rows remain unchanged;
6. no user data appears in diagnostics;
7. typed rescue-required result is produced.

#### If `EXPLICIT_DESTRUCTIVE_FALLBACK`

Prove:

1. only policy-approved old versions take destructive path;
2. synthetic old data is removed;
3. current schema is created;
4. no supported version takes fallback;
5. result/reason is explicit and sanitized;
6. fresh/migrated integrity checks pass.

It is forbidden to accept both outcomes.

### 7. `DatabaseMigrationFailureAtomicityTest`

Use a test-only failing migration fixture:

1. create a source-version DB;
2. seed data;
3. execute controlled migration that changes schema then throws;
4. close/reopen;
5. verify version did not advance;
6. verify source data/schema remains valid;
7. verify no partial commit;
8. rerun valid migration and prove success.

Do not alter production migration SQL simply to create a failure.

---

# Representative data requirements

## General rules

- Fixed synthetic values only.
- No real PII, secrets, URLs, bank data, or device data.
- Use a small internally consistent graph.
- Seed valid foreign-key parents before children.
- Assert primary keys, relationships, values, nulls, defaults, and row counts.
- For financial values, assert exact stored representation and currency; do not use floating comparison without an explicit existing contract.

## Current-edge examples

The exact contracts must derive from current active migrations, not this prose. At the anchor, likely contract areas include:
- the 145→146 creation of `negotiation_outcomes`, including its foreign key and indexes;
- the 146→147 additions to group tables and index uniqueness change;
- the 147→148 worker-run tracing columns and preservation of existing background job rows. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt))

For every future edge, the migration PR must add:
1. manifest row;
2. test-side data contract;
3. source seed;
4. target assertions;
5. chain coverage;
6. runtime-builder coverage;
7. schema parity coverage.

---

# Schema snapshot contract

## Required behavior

1. Exported schemas are required for every supported start and target version.
2. Schema assets used by Android tests must be present and readable.
3. Generated current schema must be committed.
4. Schema generation must not leave uncommitted tracked differences.
5. Missing supported snapshots are failures, not informational “known gaps.”
6. Historical snapshots outside supported range may remain archival, but may not substitute for supported proof.

## Gradle task changes

Replace any Gradle task behavior that:
- scans stale historical migration declarations;
- relies on hardcoded known-gap lists;
- warns by default for missing supported snapshots;
- derives expected schema set from inactive symbols.

The canonical task must consume:
```text
DatabaseSchemaPolicy
active migration registry
migration proof manifest
```

Run schema generation in CI, then verify repository cleanliness for schema output.

---

# Registry and baseline transition

## Static migration preflight

The current `migration_matrix` ratchet has an empty baseline. MIG-01 must transition it to strict blocking static proof:

```text
guard ID: migration_matrix (retain stable identity if possible)
mode: blocking
baseline: none
canonical script: verify_migration_matrix.py --fail-on-violation
```

Do not update or regenerate `config/baselines/migration_matrix.json`.

After GR-10A plan validation proves no command consumes it:
- remove/archive the unused empty baseline in a dedicated reviewed diff;
- document why it is retired;
- add regression test proving migration proof cannot be downgraded to ratchet debt.

## New proof-suite guard

Register `migration_proof_suite` as a blocking policy/meta-guard, with:
- script;
- test file;
- source/config inputs;
- documentation anchor;
- artifact contract;
- external CI job identity.

No migration proof component may run in warning mode.

---

# CI implementation

## Required job structure

```text
migration-proof-preflight
migration-proof-tests
migration-proof
```

### `migration-proof-preflight`

Runs on:
```text
every PR
main/master pushes
manual dispatch
```

Responsibilities:
- static migration matrix;
- proof-suite static verifier;
- emit validated API matrix;
- emit expected artifact/test manifest;
- upload preflight artifacts.

### `migration-proof-tests`

Matrix job. It:
- depends on preflight;
- runs all migration-proof instrumentation classes;
- executes on every PR;
- uses deterministic emulator configuration;
- uploads results on success/failure;
- runs JUnit result verifier;
- exits nonzero on test failure, skipped proof, absent XML, or malformed result.

Required lanes:
```text
oldest currently supported Android API, derived from actual minSdk
API 34
```

Do not hardcode a copied API value without a verifier. At the anchor `minSdk` is 26, but the implementation must derive/validate the oldest lane from current configuration. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/build.gradle.kts))

### `migration-proof`

Stable aggregator job:

```text
name: Migration Proof
if: always()
```

It fails unless:
- preflight passed;
- every required matrix lane passed;
- no lane was skipped/cancelled/timed out;
- expected JUnit/result artifacts exist;
- result verifier summaries are valid;
- schema diff/artifact manifests exist.

It becomes the stable future branch-protection check. Actual branch-protection configuration is verified later by CI-11; do not claim it is configured solely because this workflow exists.

## Required CI rules

```text
No continue-on-error
No main-only condition
No manual-dispatch-only condition
No generic instrumented-test job used as substitute
No Gradle --tests selector for proof scope
No hidden retry of failed migration assertions
Artifacts upload with if: always()
Missing artifacts fail aggregator
```

Use instrumentation runner package filtering:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.yourname.expensetracker.data.database.migrationproof \
  --no-daemon --stacktrace --console=plain
```

The current workflow’s migration job is main/manual-only and uses `--tests "*DatabaseMigration*"`, so replace its behavior rather than treating it as sufficient proof. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

---

# Implementation sequence

## Step 1 — Freeze MIG-00 decisions

Record:

```text
START_SHA
START_TREE_SHA
MIG-00 audit SHA
GUARD-QA-00 audit SHA
current policy/registry/builder hashes
schema manifest hash
migration baseline hash
current workflow hash
```

Require:
```text
unsupported policy explicitly approved
downgrade policy explicitly approved
production builder identified
current test execution facts recorded
```

## Step 2 — Write negative/static-validator tests first

Before production/test changes, add fixtures for:

1. missing active migration edge;
2. duplicate edge;
3. inactive historical edge mistaken as active;
4. missing source schema snapshot;
5. missing target snapshot;
6. internal schema version mismatch;
7. builder missing migration registry;
8. proof manifest missing edge contract;
9. stale proof manifest edge;
10. `assumeTrue`;
11. `@Ignore`/`@Disabled`;
12. hardcoded version in proof package;
13. missing required test class;
14. missing result-verifier manifest;
15. malformed source policy;
16. malformed YAML;
17. missing JUnit XML;
18. skipped JUnit proof test;
19. absent required class in JUnit;
20. stale/incorrect artifact SHA.

## Step 3 — Establish canonical policy and builder behavior

Implement only the selected unsupported/downgrade policy.

Then prove:
- all production builder entrypoints consume one migration source;
- current version/baseline are sourced once;
- source policy and tests agree;
- no test builder is mistaken for production builder.

If the selected rescue policy requires missing product UX/service behavior, stop. Do not silently implement destructive fallback instead.

## Step 4 — Add proof manifest and static tools

Create strict manifest and validator, then migrate registry mode from ratchet to blocking only after:
- static preflight returns correct `0/1/2`;
- no active execution plan references old baseline;
- regression tests prove no fallback/warning mode remains.

## Step 5 — Build Android proof package incrementally

Recommended commit slices within the PR:

```text
M1: policy/manifest/static preflight
M2: edge + chain tests and contracts
M3: runtime-builder + parity tests
M4: unsupported/atomicity tests
M5: result verifier + CI jobs/aggregator
M6: cleanup, docs, evidence
```

Each slice remains locally testable; do not merge isolated slices that weaken or skip proof.

## Step 6 — Add schema parity/report artifacts

On mismatch, write safe structural artifacts:

```text
app/build/outputs/migration-proof/
  migration-proof-summary.json
  fresh-schema.json
  migrated-schema.json
  schema-diff.json
  schema-diff.md
```

Never write seeded values, database files, user data, SQL payloads containing test values, or absolute paths to public CI artifacts.

## Step 7 — Wire CI and validate job semantics

Before changing required status checks:
1. validate workflow syntax;
2. verify job triggers include PR;
3. verify no `continue-on-error`;
4. verify package filter is present;
5. verify artifacts upload always;
6. verify aggregator fails missing/skip/cancel;
7. verify job name is stable.

## Step 8 — Run local/CI evidence sequence

### Static checks

```bash
python3 scripts/verify_migration_matrix.py \
  --root . \
  --fail-on-violation
```

```bash
python3 scripts/verify_migration_proof_suite.py \
  --root . \
  --fail-on-violation
```

```bash
python3 -m pytest \
  scripts/test_verify_migration_matrix.py \
  scripts/test_verify_migration_proof_suite.py \
  scripts/test_verify_migration_test_results.py \
  -v --tb=short
```

### Schema generation

```bash
./gradlew :app:kspDebugKotlin \
  --no-daemon --stacktrace --console=plain

git diff --exit-code -- app/schemas
```

### Instrumented migration proof

Only with explicit Gradle/emulator ownership:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.yourname.expensetracker.data.database.migrationproof \
  --no-daemon --stacktrace --console=plain
```

```bash
python3 scripts/verify_migration_test_results.py \
  --results app/build/outputs/androidTest-results \
  --manifest config/guards/migration_proof_manifest.yml \
  --api-level <actual-api> \
  --output build/guard-debug/mig-01/result-summary.json
```

### Broad validation

```bash
python3 scripts/ci/verify_guard_registry.py --root .
python3 scripts/ci/run_static_guard_suite.py \
  --mode ci \
  --output-dir build/guard-debug/mig-01/static-suite

./gradlew :app:testDebugUnitTest \
  --no-daemon --stacktrace --console=plain

./gradlew :app:verifyRoomSchemaSnapshots \
  -PstrictRoomSchemas=true \
  --no-daemon --stacktrace --console=plain
```

Run GATE-00R twice after final changes. Migration proof execution must also be reproduced in CI or through two documented exact-SHA emulator runs.

---

# Mandatory adversarial acceptance checks

1. Remove one migration from active registry → static preflight fails.
2. Define a migration but omit it from active registry → static preflight fails.
3. Add version N+1 without contiguous edge → static preflight fails.
4. Delete supported schema JSON → static preflight fails.
5. Alter schema JSON internal version → static preflight fails.
6. Corrupt migration SQL in test fixture → executable edge test fails.
7. Remove a migration from production builder → runtime-builder test fails.
8. Change unique index to non-unique only on one path → parity test fails.
9. Remove/change foreign key action → parity/integrity test fails.
10. Add non-null column without valid migration/default → edge/chain/runtime test fails.
11. Mark proof test ignored/skipped → proof-suite/JUnit verifier fails.
12. Filter out a required proof class → JUnit verifier/aggregator fails.
13. Delete JUnit XML → result verifier exits `2`.
14. Omit artifact → aggregator fails.
15. Make proof matrix job PR-skipped → workflow test fails.
16. Add `continue-on-error` → workflow/contract test fails.
17. Run pre-baseline source under selected policy → exact chosen behavior passes; opposite behavior fails.
18. Simulate failing migration → atomicity test detects no partial state.
19. Run same suite twice → structural summaries/test identities match.

---

# Definition of done

MIG-01 is complete only when:

- one explicit unsupported-upgrade policy and one downgrade policy are implemented and tested;
- every supported migration edge executes through `MigrationTestHelper`;
- every supported start version reaches current through the canonical registry;
- actual production builder opening is tested from every supported start;
- fresh/migrated schema parity is structured and semantic;
- representative synthetic data preservation is contract-driven;
- integrity and foreign-key checks pass;
- migration failure atomicity is tested;
- missing/ignored/skipped proof tests fail;
- static migration proof is blocking and has no debt baseline;
- migration proof runs on every PR and required emulator lanes;
- no `continue-on-error` or skipped matrix lane can produce a green aggregator;
- safe artifacts/JUnit reports are present and verified;
- direct, static-suite, Gradle, and CI migration evidence is reproducible;
- no DB access baseline or policy was weakened.

## Required completion report

```text
PR: MIG-01
START SHA:
END SHA:
MIG-00 AUDIT SHA:
GUARD-QA-00 AUDIT SHA:
GATE-00R EVIDENCE ID:

CURRENT VERSION:
SUPPORTED BASELINE:
SUPPORTED START VERSION COUNT:
ACTIVE MIGRATION EDGE COUNT:
ACTIVE EDGE CONTRACT COUNT:
UNSUPPORTED UPGRADE POLICY:
DOWNGRADE POLICY:

STATIC PREFLIGHT:
PROOF-SUITE STATIC VALIDATOR:
SCHEMA SNAPSHOT VALIDATION:
SCHEMA GENERATION CLEAN:

EDGE TESTS:
CHAIN TESTS:
RUNTIME-BUILDER TESTS:
FRESH/MIGRATED PARITY:
UNSUPPORTED-VERSION TEST:
FAILURE-ATOMICITY TEST:
INTEGRITY/FK CHECKS:

JUNIT RESULT VERIFIER:
REQUIRED TEST CLASSES EXECUTED:
SKIPPED PROOF TESTS: 0
API LANES:
PR TRIGGER ENABLED: yes/no
CONTINUE-ON-ERROR: no
STABLE AGGREGATOR: Migration Proof
ARTIFACT VERIFICATION:

MIGRATION_MATRIX BASELINE RETIRED: yes/no
DB ACCESS BASELINE CHANGED: no
PRODUCTION MIGRATION SQL CHANGED: yes/no
UNEXPECTED DIFFERENCES:
GATE-00R REPRODUCIBLE: yes/no
NEXT PR:
```