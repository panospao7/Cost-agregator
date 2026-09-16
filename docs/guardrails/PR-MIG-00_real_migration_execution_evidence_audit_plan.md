# PR-MIG-00 — Audit real migration execution evidence

## Agent mission

Produce an exact-SHA, evidence-backed answer to:

> Does this repository currently execute and prove supported Room migrations through the actual production database builder, with data preservation, schema parity, unsupported-version behavior, and CI enforcement?

This is a discovery/audit PR. It must not claim migration proof merely because:
- a Python script parses migration names;
- a Room schema snapshot exists;
- an Android test source file exists;
- a workflow has a migration-named job;
- an emulator job is green/ignored/skipped;
- a test uses `MigrationTestHelper` but does not prove production-builder registration.

At the anchor snapshot, `DatabaseSchemaPolicy` identifies current version through `APP_DATABASE_SCHEMA_VERSION`, sets the migration baseline to 145, and delegates active migrations to `DatabaseMigrations.ALL`; that active array contains 145→146, 146→147, and 147→148. The canonical `AppDatabase` builder adds `ALL_MIGRATIONS`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/data/database/DatabaseSchemaPolicy.kt))

However, the current Python matrix verifier is static/text-oriented and has fallback/informational behavior; the current matrix Android test hardcodes 145/148, uses `assumeTrue`, compares fresh/migrated table names only, and contains an ambiguous pre-baseline data assertion. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_migration_matrix.py))

The current `Migration Proof` workflow runs only on main/master pushes or manual dispatch, not pull requests, and currently has one API-34 lane. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

## Why this PR is necessary

The final acceptance contract requires actual executable proof for every supported start version:

```text
create old schema
seed synthetic representative data
execute migration
open using production Room builder
verify current version
verify representative data and DAO access
run integrity and foreign-key checks
compare fresh and migrated schema semantics
test one explicit unsupported-version policy
run blocking CI on every PR
```

It additionally requires no skipped migration tests and no `continue-on-error` escape route for migration proof. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

`MigrationTestHelper` is appropriate for creating older schema databases and validating migrations, but the execution proof must also exercise the production builder and the app’s actual migration registration. ([developer.android.com](https://developer.android.com/reference/androidx/room/testing/MigrationTestHelper?utm_source=openai))

---

# Required order

Start only after:

```text
GATE-00R complete
GR-10A merged
GR-10B merged
GR-10D merged
GUARD-QA-00 audit completed
```

MIG-00 must consume QA-00’s migration coverage findings; it must not create a competing audit system.

## Hard stops

Stop and report `BLOCKED`, not `COMPLETE`, if:

- checkout is dirty;
- the exact start SHA cannot be recorded;
- migration source policy/registration cannot be resolved;
- schema assets cannot be located;
- production builder entry point cannot be identified;
- Android test discovery cannot be observed;
- no emulator/device execution can be run or retrieved as a CI artifact;
- JUnit XML is absent/unparseable;
- the unsupported-version product policy remains contradictory or undefined;
- any report would expose real user data, DB contents, or absolute local paths.

---

# Scope

## Allowed

- read-only migration evidence/audit tooling;
- audit-specific Python tests;
- migration evidence documentation and exact-SHA ledger;
- CI/test-run inspection documentation;
- untracked debug artifacts;
- narrowly required audit metadata.

## Forbidden

```text
production migration SQL changes
AppDatabase builder behavior changes
DatabaseSchemaPolicy semantic changes
schema version changes
schema snapshot changes
migration baseline changes
ratchet baseline changes
Android migration test rewrites
workflow triggering/failure behavior changes
new CI jobs
new product destructive/rescue behavior
```

MIG-00 identifies what MIG-01 must change. It does not make those changes.

---

# Required deliverables

## 1. Migration execution audit report

Create:

```text
docs/ci/migration/MIG-00_EXECUTION_AUDIT.yml
```

Required shape:

```yaml
schemaVersion: 1
auditId: mig-00
startSha: <40-char SHA>
startTreeSha: <40-char SHA>
gateEvidenceId: <GATE-00R ID>
guardQaAuditSha256: <sha256>
status: COMPLETE | BLOCKED

canonicalSources:
  databasePolicy:
    path: <repo-relative>
    sha256: <sha256>
  databaseMigrations:
    path: <repo-relative>
    sha256: <sha256>
  appDatabase:
    path: <repo-relative>
    sha256: <sha256>
  gradleConfig:
    path: app/build.gradle.kts
    sha256: <sha256>
  workflow:
    path: .github/workflows/ci.yml
    sha256: <sha256>

observedPolicy:
  currentVersion: <integer or unresolved>
  supportedBaseline: <integer or unresolved>
  registeredEdges: []
  productionBuilderRegistration: PROVEN | UNPROVEN | AMBIGUOUS
  unsupportedUpgradePolicy: DESTRUCTIVE | BLOCK_AND_RESCUE | CONTRADICTORY | UNRESOLVED
  downgradePolicy: REJECT | DESTRUCTIVE | UNRESOLVED

staticEvidence:
  migrationMatrix:
    command: []
    exitCode: <0|1|2>
    limitations: []
  schemaSnapshots:
    status: PASS | FAIL | PARTIAL | UNRUN
  proofSuiteGuard:
    status: PRESENT | ABSENT | PARTIAL

androidEvidence:
  testClassesDiscovered: []
  testClassesExecuted: []
  junitXmlStatus: PRESENT | ABSENT | MALFORMED
  passed: <integer>
  failed: <integer>
  errors: <integer>
  skipped: <integer>
  runtimeBuilderTestPresent: yes/no
  runtimeBuilderTestExecuted: yes/no
  freshMigratedParityStrength: NONE | TABLE_NAMES_ONLY | STRUCTURED_SEMANTIC
  dataPreservationStrength: NONE | PARTIAL | EDGE_CONTRACT
  unsupportedPolicyStrength: NONE | AMBIGUOUS | EXPLICIT_TESTED

ciEvidence:
  pullRequestTrigger: yes/no
  requiredBlockingJob: yes/no
  continueOnError: yes/no
  apiLanes: []
  resultArtifactVerifier: PRESENT | ABSENT
  stableAggregator: PRESENT | ABSENT

gaps: []
nextPrRequirements: []
```

This is an evidence ledger, not a policy file.

## 2. Read-only audit tool

Add:

```text
scripts/ci/audit_migration_execution.py
scripts/ci/test_audit_migration_execution.py
```

Inputs:

```text
repository root
current source policy/registry/builder files
schema directory
Android-test source roots
Gradle build file
workflow file
optional JUnit XML directory
optional GATE-00R evidence reference
```

Outputs:

```text
build/guard-debug/mig-00/migration-execution-candidate.yml
build/guard-debug/mig-00/migration-source-inventory.json
build/guard-debug/mig-00/migration-test-inventory.json
build/guard-debug/mig-00/migration-ci-topology.json
build/guard-debug/mig-00/migration-evidence-summary.md
```

The tool may discover and classify. It may not certify actual runtime migration execution without valid JUnit/artifact evidence.

## 3. Migration proof decision record

Create:

```text
docs/architecture/MIGRATION_SUPPORT_POLICY_DECISION.md
```

It must record the actual decision state:

```text
SUPPORTED_RANGE
CURRENT_SCHEMA_VERSION_SOURCE
CANONICAL_MIGRATION_REGISTRY_SOURCE
UNSUPPORTED_OLDER_DATABASE_POLICY
DOWNGRADE_POLICY
PRODUCTION_BUILDER_ENTRYPOINT
DATA-LOSS/RESCUE UX OWNER
```

Allowed statuses:

```text
DECIDED_AND_IMPLEMENTED
DECIDED_NOT_IMPLEMENTED
CONTRADICTORY
UNRESOLVED
```

MIG-00 may document `CONTRADICTORY`; it must not silently select a policy.

Current source/comments/tests need reconciliation: one current policy comment says versions below 145 use destructive migration, `DatabaseMigrations` says versions below 145 require a rescue/import path, and historical Android tests construct a separate builder using destructive fallback. This is exactly the kind of product-policy ambiguity MIG-00 must expose rather than resolve by assumption. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/data/database/DatabaseSchemaPolicy.kt))

## 4. Evidence classification matrix

Every required proof dimension gets one exact status:

| Dimension | Allowed audit status |
|---|---|
| Canonical current version | `PROVEN`, `PARTIAL`, `UNRESOLVED` |
| Canonical supported baseline | `PROVEN`, `PARTIAL`, `UNRESOLVED` |
| Contiguous active registry | `PROVEN`, `PARTIAL`, `UNRESOLVED` |
| Schema snapshot availability | `PROVEN`, `PARTIAL`, `UNRESOLVED` |
| Direct edge SQL execution | `EXECUTED`, `STATIC_ONLY`, `NOT_EXECUTED`, `BLOCKED` |
| Full-chain execution | `EXECUTED`, `STATIC_ONLY`, `NOT_EXECUTED`, `BLOCKED` |
| Production-builder registration | `EXECUTED`, `SOURCE_ONLY`, `NOT_EXECUTED`, `BLOCKED` |
| Data preservation | `EDGE_CONTRACT`, `PARTIAL`, `NONE` |
| Fresh/migrated parity | `STRUCTURED_SEMANTIC`, `TABLE_NAMES_ONLY`, `NONE` |
| Integrity/FK checks | `EXECUTED`, `NOT_EXECUTED` |
| Unsupported version policy | `EXPLICIT_TESTED`, `AMBIGUOUS`, `NONE` |
| Failure atomicity | `EXECUTED`, `NOT_EXECUTED` |
| PR CI blocking | `YES`, `NO`, `UNKNOWN` |
| JUnit result validation | `YES`, `NO`, `PARTIAL` |

No generic `PASS`, `GOOD`, or `LIKELY`.

---

# Mandatory workflow

## Step 1 — Freeze exact evidence

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

mkdir -p build/guard-debug/mig-00
```

Record hashes for:

```text
DatabaseSchemaPolicy.kt
DatabaseMigrations.kt
AppDatabase.kt
app/build.gradle.kts
scripts/verify_migration_matrix.py
scripts/test_verify_migration_matrix.py
all Android migration test files
.github/workflows/ci.yml
config/baselines/migration_matrix.json
```

## Step 2 — Discover canonical source facts without guessing

The audit must determine, from current code:

1. current Room database version;
2. current supported baseline;
3. every active migration edge in the runtime registry;
4. all old migration declarations not in active registry;
5. every production builder path;
6. whether each builder registers the same canonical migration array;
7. whether destructive fallback is configured in each builder;
8. schema export path and Android-test asset availability;
9. app `minSdk`;
10. actual Android test package/class inventory;
11. current CI event triggers, matrix lanes, filters, artifact uploads, and error behavior.

Do not use historical constants such as `145`, `148`, or `26` as implementation input. They are facts to observe at the audit SHA, not permanent contract values.

## Step 3 — Run static preflight as static evidence only

```bash
python3 scripts/verify_migration_matrix.py \
  --root . \
  --fail-on-violation \
  |& tee build/guard-debug/mig-00/static-matrix.log
```

Capture:
- exact argv;
- exit code;
- parsed source versions/edges;
- output classification;
- all fallbacks/warnings;
- schema coverage behavior.

The current checker parses source and schema files; it does not execute Room SQL or prove production-builder opening. Record that distinction clearly. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_migration_matrix.py))

## Step 4 — Inventory current Android migration tests

For every Android test matching migration-related scope:

```text
class FQCN
source file
test methods
parameterized cases
Assume usage
Ignore/Disabled usage
hardcoded version literals
MigrationTestHelper usage
explicit migration array usage
production-builder usage
schema assertions
data assertions
integrity/FK assertions
cleanup behavior
```

Do not assume a test executes merely because it is in source.

The audit must specifically flag:
- `assumeTrue`/JUnit assumptions;
- `@Ignore`, `@Disabled`, and runner filters;
- hardcoded current/baseline versions;
- no-migration-vararg calls;
- fresh DB created from schema helper instead of real builder;
- table-name-only parity;
- ambiguous preservation/destroy assertions;
- tests that use a manually configured builder instead of the production builder.

## Step 5 — Inspect source/schema asset path

Verify actual:
- Room schema export configuration;
- committed schema directory;
- Android test assets visibility;
- class-name/schema-path conventions;
- Room testing dependency;
- test orchestrator configuration/dependency;
- generated schema reproducibility mechanism.

The project currently includes `androidx.room:room-testing`, but MIG-00 must verify the resolved configuration and assets at the actual start SHA rather than rely on dependency presence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/build.gradle.kts))

## Step 6 — Inspect actual runtime execution evidence

Only with Gradle/emulator ownership:

1. run the current migration-specific instrumentation command exactly as CI runs it;
2. preserve Gradle stdout/stderr;
3. locate Android JUnit XML and test reports;
4. parse executed classes, cases, failures, errors, skips, and durations;
5. compare executed tests to source discovery;
6. identify whether the current filter excludes any expected migration test;
7. repeat once if environment allows, comparing semantic result/test inventory.

The current workflow uses a Gradle `--tests "*DatabaseMigration*"` filter. MIG-00 must measure what actually executes under that command rather than assume the filter is correct for instrumentation tests. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

If no emulator can be run or no existing CI artifact can be obtained, the audit finishes:

```text
STATUS: BLOCKED — EXECUTION EVIDENCE UNAVAILABLE
```

It must not claim `STATIC_ONLY` is sufficient.

## Step 7 — Inspect CI topology

Capture:
- PR trigger behavior;
- push/manual behavior;
- job names;
- matrix lanes;
- `continue-on-error`;
- test command;
- artifact paths;
- always-upload semantics;
- aggregator/no-aggregator state;
- branch-protection evidence availability.

Current migration proof is not PR-triggered; document the exact condition instead of saying it is merely “non-blocking.” ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

## Step 8 — Produce MIG-01 design inputs

MIG-00 must produce:
1. one exact supported-range decision;
2. one exact unsupported-version decision state;
3. one production-builder entrypoint inventory;
4. one migration test execution manifest;
5. one current gap list;
6. one proposed runtime matrix;
7. one artifact/JUnit path map;
8. one list of stale tests to retain, rewrite, move, or retire.

No source implementation happens in MIG-00.

---

# Required audit-tool tests

```bash
python3 -m pytest \
  scripts/ci/test_audit_migration_execution.py \
  scripts/test_verify_migration_matrix.py \
  -v --tb=short
```

Audit-tool fixture cases:

1. missing `DatabaseSchemaPolicy.kt`;
2. malformed current/baseline source;
3. duplicate migration edge;
4. active migration definition absent from registry;
5. registry reference absent from definition;
6. production builder missing migration registration;
7. schema asset absent;
8. Android test with `assumeTrue`;
9. Android test with `@Ignore`;
10. stale hardcoded version;
11. no-migration full-chain call;
12. manual non-production builder;
13. missing JUnit XML;
14. malformed JUnit XML;
15. skipped test case;
16. test class discovered but not executed;
17. workflow job skipped on pull request;
18. `continue-on-error`;
19. missing artifact upload;
20. deterministic candidate report across two runs;
21. no local absolute paths/raw seed values in report.

---

# Definition of done

MIG-00 is complete only when:

- it is anchored to one exact SHA;
- all canonical migration sources/builders/tests/workflow paths are inventoried;
- actual migration test execution is observed through JUnit results or explicitly marked blocked;
- static evidence is separated from executable evidence;
- every current migration-proof claim is classified precisely;
- unsupported-version and downgrade policy contradictions are explicit;
- MIG-01 inputs are concrete and reviewable;
- no production migration/CI behavior changed.

## Required completion report

```text
PR: MIG-00
START SHA:
END SHA:
GATE-00R EVIDENCE ID:
GUARD-QA-00 AUDIT SHA:

CURRENT VERSION:
SUPPORTED BASELINE:
ACTIVE MIGRATION EDGES:
PRODUCTION BUILDER PATHS:
BUILDERS USING CANONICAL REGISTRY:
BUILDERS WITH DESTRUCTIVE FALLBACK:

STATIC MATRIX RESULT:
SCHEMA ASSET RESULT:
ANDROID MIGRATION TEST CLASSES DISCOVERED:
ANDROID MIGRATION TEST CLASSES EXECUTED:
JUNIT XML RESULT:
PASSED / FAILED / ERRORED / SKIPPED:

DIRECT EDGE EXECUTION:
FULL-CHAIN EXECUTION:
PRODUCTION-BUILDER EXECUTION:
DATA-PRESERVATION EVIDENCE:
FRESH/MIGRATED PARITY STRENGTH:
INTEGRITY / FK CHECK EVIDENCE:
UNSUPPORTED-VERSION POLICY:
FAILURE-ATOMICITY EVIDENCE:

PR-TRIGGERED MIGRATION JOB: yes/no
CURRENT API LANES:
CURRENT CONTINUE-ON-ERROR:
CURRENT STABLE AGGREGATOR:
CURRENT ARTIFACT VERIFIER:

BLOCKERS:
MIG-01 REQUIRED DECISIONS:
MIG-01 REQUIRED IMPLEMENTATION SLICES:

PRODUCTION KOTLIN CHANGED: no
MIGRATION SQL CHANGED: no
BASELINE CHANGED: no
WORKFLOW SEMANTICS CHANGED: no
NEXT PR: MIG-01
```