# Guardrail P1/P2 Implementation Plan

Starting SHA: `6f0e46c846e71d3506be53c8b1a8b4abf86a6cab`

## 1. Scope

Implement:

1. Stop whole-file `Dao.kt` exclusion.
2. Distinguish overloaded function signatures.
3. Make migration proof a required PR gate.
4. Replace regex Kotlin parsing with Kotlin PSI.
5. Generate manifests/docs from canonical policy.
6. Remove or redefine overlapping Gradle guards.

Final invariant:

> One canonical analyzer discovers every supported DB mutation, identifies the exact source symbol, applies one canonical policy, and is executed identically by local Gradle, static CI, and required PR checks.

---

# 2. Mandatory agent rules

Agents must not:

- add another overlapping guard;
- preserve filename-based DAO exclusions;
- authorize overloads by method-name union;
- update DB baselines to hide parser regressions;
- copy policy tuples into tests manually;
- pin arbitrary policy counts;
- use `--tests` to filter Android instrumentation tests;
- remove a legacy guard before proving canonical coverage;
- change production architecture while implementing analyzer infrastructure;
- treat zero executed migration tests as success.

Every PR report must include:

```text
START SHA
END SHA
FILES CHANGED
RED TESTS ADDED
COMMANDS RUN
EXIT CODES
POLICY CHANGES
BASELINE CHANGES
NEW PRODUCTION FINDINGS
UNSUPPORTED SYNTAX
REMAINING RISKS
```

Exit codes remain:

```text
0 = pass
1 = architecture violation
2 = infrastructure/parser/configuration failure
```

---

# 3. Dependency graph

```text
GR-08 Dao.kt exclusion removal ───────┐
                                      ├── GR-10 PSI analyzer
GR-09 overload identity model ────────┘        │
                                               v
                                      GR-12 generated contracts
                                               │
                                               v
                                      GR-13 legacy guard removal

GR-11 migration PR gate may run in parallel.
GR-14 performs final integration and adversarial proof.
```

GR-08 and GR-09 establish behavior that the PSI implementation must preserve.

---

# 4. Phase 0 — Capture the starting baseline

Create branch:

```bash
git fetch --all --prune
git checkout --detach 6f0e46c846e71d3506be53c8b1a8b4abf86a6cab
git checkout -b guardrail-p1-p2-hardening
mkdir -p build/guardrail-p1-p2/before
```

Run:

```bash
python3 -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short \
  2>&1 | tee build/guardrail-p1-p2/before/db-tests.log

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  2>&1 | tee build/guardrail-p1-p2/before/db-scan.log

python3 scripts/ci/run_static_guard_suite.py \
  2>&1 | tee build/guardrail-p1-p2/before/static-suite.log

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace \
  2>&1 | tee build/guardrail-p1-p2/before/gradle-db.log

./gradlew :app:check \
  --no-daemon --stacktrace \
  2>&1 | tee build/guardrail-p1-p2/before/app-check.log
```

Inventory files that would currently be skipped:

```bash
find app/src/main -type f -name '*Dao.kt' \
  | sort > build/guardrail-p1-p2/before/dao-named-files.txt
```

Inventory overloaded production methods:

```bash
python3 scripts/verify_db_access_boundaries.py \
  --dump-overload-inventory \
  build/guardrail-p1-p2/before/overloads.json
```

If that option does not exist, add it as a diagnostic-only change before semantic work.

Create:

```text
docs/ci/GUARDRAIL_P1_P2_LEDGER.md
```

Do not update policy or baselines in Phase 0.

---

# 5. GR-08 — Stop whole-file `Dao.kt` exclusion

## Objective

A filename must never determine whether source code is scanned.

Room DAO declarations should not be treated as production callers, but every other declaration in the same file must be scanned.

## Files

```text
scripts/verify_db_access_boundaries.py
scripts/test_verify_db_access_boundaries.py
docs/DB_WRITE_OWNERSHIP.md
```

## Implementation

Remove logic equivalent to:

```text
if filename.endswith("Dao.kt"):
    continue
```

Replace it with declaration-level classification.

For every Kotlin file:

1. Read and parse the complete file.
2. Identify declarations annotated with `@Dao`.
3. Mark only the body/range of the actual DAO interface or abstract class as a DAO declaration region.
4. Continue scanning:
   - top-level functions;
   - top-level properties;
   - companion objects;
   - helper classes;
   - helper objects;
   - non-DAO interfaces;
   - extension functions;
   - initializers.
5. DAO default methods must still be analyzed by the Room-mutator inventory component.
6. Direct production writes in non-DAO declarations must be checked normally.

Do not classify a declaration as a DAO because:

- its filename ends in `Dao.kt`;
- its class name ends in `Dao`;
- it contains a property whose name ends in `Dao`.

DAO status must come from the actual annotation or the canonical Room declaration inventory.

## Required red fixtures

### Helper class inside DAO-named file

```kotlin
// ExpenseDao.kt

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(expense: Expense)
}

class UnsafeExpenseWriter(
    private val expenseDao: ExpenseDao
) {
    suspend fun write(expense: Expense) {
        expenseDao.insert(expense)
    }
}
```

Expected: unauthorized mutation.

### Top-level writer

```kotlin
suspend fun unsafeWrite(
    dao: ExpenseDao,
    expense: Expense
) {
    dao.insert(expense)
}
```

Expected: unsupported/unauthorized top-level writer.

### Companion writer

```kotlin
class DaoUtilities {
    companion object {
        suspend fun write(dao: ExpenseDao, expense: Expense) {
            dao.insert(expense)
        }
    }
}
```

Expected: exact enclosing symbol or controlled unsupported-scope failure.

### Misleading filename

```kotlin
// ReportingDao.kt
class ReportingCoordinator {
    suspend fun save() = expenseDao.insert(expense)
}
```

Expected: scanned normally.

### DAO in non-DAO filename

```kotlin
// StorageContracts.kt
@Dao
interface ExpenseDao
```

Expected: discovered as a DAO declaration.

## Positive tests

- Pure abstract DAO declaration does not become a caller violation.
- Read-only DAO default method is not a mutation.
- Comments and strings remain ignored.
- Multiple DAO declarations in one file are supported or fail explicitly.
- Non-DAO declaration after a DAO declaration remains scanned.

## Acceptance

```bash
python3 -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  -k "dao_file or dao_declaration" \
  -v

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation
```

Produce:

```text
build/guardrail-p1-p2/dao-file-scan-delta.json
```

Every new finding must be classified:

```text
REAL_VIOLATION
LEGITIMATE_POLICY_GAP
PARSER_ERROR
KNOWN_UNAUTHORIZED_DEBT
```

No finding may remain unclassified.

Commit:

```text
fix(ci): scan non-DAO declarations in Dao-named files
```

---

# 6. GR-09 — Distinguish overloaded signatures

## Objective

Authorization must target one exact function declaration, not the union of all functions sharing a method name.

## Canonical symbol identity

Introduce:

```text
FunctionSymbolId
- canonical_path
- owner_fqcn
- function_name
- extension_receiver_type
- parameter_types
```

Canonical textual form:

```text
app/src/main/.../SomeClass.kt::
com.example.SomeClass#save(
  com.example.Expense,
  com.example.SaveMode
)
```

Do not include:

- return type;
- parameter names;
- default values;
- visibility;
- `suspend`;
- annotations.

These do not identify an overload.

Retain in canonical parameter types:

- package-qualified type;
- generic arguments;
- nullability;
- function type structure;
- array element type;
- vararg marker;
- nested class qualification.

## Policy schema

Migrate from:

```yaml
class: ExpenseRepository
method: save
```

to:

```yaml
class: ExpenseRepository
method: save
signature:
  parameters:
    - com.yourname.expensetracker.data.entity.Expense
    - com.yourname.expensetracker.domain.CreateMode
  receiver: null
```

A policy loader must reject:

- overloaded source method with missing signature;
- unknown signature key;
- duplicate exact signature;
- parameter count mismatch;
- unresolved parameter type;
- stale signature;
- signature naming a nonexistent overload.

During migration, non-overloaded methods may temporarily omit `signature`. Final integration should generate and require signatures for all entries to prevent a future overload from silently changing identity.

## Immediate parser containment

Before PSI migration, change current behavior:

- If a policy entry names an overloaded method and has no exact signature, fail with exit `2`.
- Never union overload bodies.
- Each overload receives its own mutation-pair set.
- Source evidence is checked per overload.
- A mutation added to overload A cannot be authorized by policy for overload B.

## Required tests

```kotlin
suspend fun save(expense: Expense) {
    expenseDao.insert(expense)
}

suspend fun save(receipt: Receipt) {
    receiptDao.insert(receipt)
}
```

Required outcomes:

1. Expense signature authorizes only the first overload.
2. Receipt signature authorizes only the second.
3. Method-name-only policy fails.
4. Swapping DAO operations between overloads fails.
5. Same method name and same arity but different types remain distinct.
6. Nullable/generic/vararg parameter parsing is deterministic.
7. Import aliases either resolve exactly or fail closed.
8. Type aliases either resolve exactly or fail closed.
9. Extension function overloads include receiver type.
10. Constructor-like factory methods remain ordinary functions.

## Migration tool

Add:

```text
scripts/migrate_db_policy_signatures.py
```

Modes:

```bash
python3 scripts/migrate_db_policy_signatures.py --check
python3 scripts/migrate_db_policy_signatures.py --write
```

The tool must:

1. Parse every canonical policy entry.
2. Resolve its exact source declaration.
3. Add normalized signature metadata.
4. Refuse ambiguous matches.
5. Produce deterministic YAML ordering.
6. Never modify reasons, owners or linked issues.
7. Emit a review report.

Required report:

```text
build/guardrail-p1-p2/policy-signature-migration.json
```

Commit:

```text
feat(ci): authorize DB writers by exact function signature
```

---

# 7. GR-10 — Replace regex parser with Kotlin PSI

## Objective

Move Kotlin structural analysis from regex/balanced-text heuristics to Kotlin PSI.

The Python layer may remain as CI orchestration and ratchet compatibility, but it must not parse Kotlin declarations, calls, scopes or annotations after migration.

## Module structure

Add:

```text
include(":guard-analysis")
```

Files:

```text
guard-analysis/build.gradle.kts
guard-analysis/src/main/kotlin/.../Main.kt
guard-analysis/src/main/kotlin/.../PsiEnvironment.kt
guard-analysis/src/main/kotlin/.../SourceInventory.kt
guard-analysis/src/main/kotlin/.../RoomDaoAnalyzer.kt
guard-analysis/src/main/kotlin/.../MutationCallAnalyzer.kt
guard-analysis/src/main/kotlin/.../BarrierAnalyzer.kt
guard-analysis/src/main/kotlin/.../StructuralOperationAnalyzer.kt
guard-analysis/src/main/kotlin/.../PolicyLoader.kt
guard-analysis/src/main/kotlin/.../PolicyMatcher.kt
guard-analysis/src/main/kotlin/.../Models.kt
guard-analysis/src/main/kotlin/.../JsonReporter.kt
guard-analysis/src/test/kotlin/...
```

Use a plain Kotlin/JVM module, not Android.

Pin parser dependencies to the project Kotlin version. Do not silently load a different compiler version.

## PSI responsibilities

Use PSI nodes for:

- `KtFile`;
- `KtClassOrObject`;
- `KtNamedFunction`;
- `KtProperty`;
- `KtParameter`;
- `KtAnnotationEntry`;
- `KtCallExpression`;
- `KtDotQualifiedExpression`;
- `KtSafeQualifiedExpression`;
- `KtLambdaExpression`;
- `KtIfExpression`;
- `KtWhenExpression`;
- `KtTryExpression`;
- `KtObjectDeclaration`;
- `KtNamedDeclaration`;
- type references and imports.

No Kotlin source semantics may be obtained through regex except:

- SQL tokenization inside constant Room query strings;
- validation of bounded migration symbol names.

## Analyzer pipeline

```text
1. Discover source roots.
2. Create PSI files.
3. Build package/import/type index.
4. Discover @Dao declarations.
5. Build Room mutator inventory.
6. Build AppDatabase accessor inventory.
7. Build exact function symbol inventory.
8. Resolve DAO receiver identities.
9. Discover mutation call sites.
10. Build structural region tree.
11. Prove barrier evidence.
12. Analyze helper/worker mediation.
13. Analyze raw DB/file operations.
14. Load canonical policies.
15. Match source findings to policy.
16. Emit deterministic JSON/text reports.
```

## Python compatibility wrapper

Reduce:

```text
scripts/verify_db_access_boundaries.py
```

to:

1. validate CLI arguments;
2. locate repository and analyzer artifact;
3. invoke the Kotlin analyzer;
4. forward controlled output;
5. preserve exit codes;
6. support existing ratchet fingerprint format during migration.

It must no longer contain Kotlin declaration parsing logic.

## Dual-run rollout

Introduce:

```text
--engine legacy
--engine psi
--engine compare
```

`compare` runs both and emits:

```text
build/guardrail-p1-p2/parser-parity.json
```

Classify differences:

```text
PSI_CORRECT_LEGACY_MISSED
LEGACY_CORRECT_PSI_MISSED
IDENTITY_CHANGED
LINE_LOCATION_CHANGED
EXPECTED_DIAGNOSTIC_CHANGE
```

Required rollout:

### Stage A

PSI runs non-blocking in comparison mode.

### Stage B

PSI blocks infrastructure errors; legacy remains authorization authority.

### Stage C

PSI becomes authorization authority; legacy produces comparison output.

### Stage D

Delete legacy parser code after two exact-SHA green CI runs and adversarial proof.

## PSI fixture coverage

Required syntax fixtures:

- nested classes;
- companion objects;
- object expressions;
- expression-bodied functions;
- multiline declarations;
- generic functions;
- extension functions;
- overloaded functions;
- safe calls;
- non-null assertions;
- scope functions;
- lambda receivers;
- local functions;
- property getters;
- delegated properties;
- import aliases;
- backtick identifiers;
- annotations with qualified names;
- multiple declarations in one file;
- top-level functions;
- DAO and helper class in same file;
- comments, raw strings and interpolation;
- function references;
- inherited DAO methods.

## Unsupported syntax rule

PSI parse errors, unresolved critical symbols or ambiguous DAO call targets must produce exit `2`.

They must not be silently skipped.

Required diagnostics:

```text
PSI_PARSE_ERROR
SYMBOL_RESOLUTION_FAILED
DAO_TYPE_AMBIGUOUS
CALL_TARGET_AMBIGUOUS
FUNCTION_SIGNATURE_AMBIGUOUS
UNSUPPORTED_FUNCTION_REFERENCE
UNSUPPORTED_DYNAMIC_CALL
```

## Performance requirements

Record:

- file count;
- PSI parse duration;
- index duration;
- analysis duration;
- policy validation duration;
- peak memory if available.

Target:

```text
warm local run: under 10 seconds
clean CI run: under 30 seconds
```

If exceeded, cache by:

```text
source file hash
policy hash
Kotlin analyzer version
```

Never cache final authorization independently of policy hash.

## Acceptance

```bash
./gradlew :guard-analysis:test --no-daemon --stacktrace

./gradlew :guard-analysis:run --args="--root . --fail-on-violation"

python3 scripts/verify_db_access_boundaries.py \
  --engine compare \
  --fail-on-violation

python3 scripts/ci/run_static_guard_suite.py

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace
```

Commit sequence:

```text
feat(ci): add PSI DB architecture analyzer
feat(ci): make PSI analyzer authoritative
chore(ci): remove legacy Kotlin regex parser
```

Do not combine all three into one commit.

---

# 8. GR-11 — Make migration proof a required PR gate

## Objective

Every pull request targeting `main` or `master` must execute a representative real Room migration test suite on an emulator.

The job must fail when:

- migration tests fail;
- emulator setup fails;
- zero migration tests execute;
- expected schemas are absent;
- reports are missing;
- the selected test set does not match the declared migration suite.

## Test classification

Create an exact marker:

```kotlin
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class MigrationProof
```

Annotate every PR-gate migration class or method.

Prefer a test suite containing:

```text
minimum supported version -> latest
last major historical version -> latest
145 -> latest
146 -> latest
latest-1 -> latest
fresh latest schema validation
fresh-vs-migrated schema parity
representative non-empty data preservation
```

The agent must derive actual versions from:

- `@Database(version = ...)`;
- exported Room schema directories;
- registered migration objects;
- supported minimum-version documentation.

Do not invent version numbers.

## Correct instrumentation invocation

Replace JVM-style filtering with Android instrumentation-runner filtering:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=com.yourname.expensetracker.test.MigrationProof \
  --no-daemon \
  --stacktrace
```

Alternatively, use an exact comma-separated class list generated from the migration-test manifest.

## Execution-proof script

Add:

```text
scripts/ci/verify_migration_test_results.py
scripts/ci/test_verify_migration_test_results.py
config/guards/migration_pr_suite.yml
```

Manifest example:

```yaml
version: 1
minimum_test_count: 5
required_classes:
  - com.yourname.expensetracker.data.database.DatabaseMigrationTest
required_scenarios:
  - minimum_supported_to_latest
  - recent_to_latest
  - fresh_schema
  - migrated_schema_parity
  - representative_data_preservation
```

The verifier must parse Android test XML and fail if:

- no XML exists;
- zero tests executed;
- tests were skipped;
- required class absent;
- required scenario absent;
- failures/errors are nonzero;
- report SHA differs from checked-out SHA metadata.

## Workflow design

Add required PR job:

```text
migration-proof-pr
name: Migration Proof
```

Trigger:

```yaml
pull_request:
  branches: [main, master]
```

Initially run on every PR. Do not use path filtering until the gate has been stable and the dependency surface is proven.

Required steps:

1. Checkout exact SHA.
2. Set up JDK 17.
3. Set up Gradle cache.
4. Enable KVM.
5. Verify Room schemas statically.
6. Boot API 34 emulator.
7. Run only `@MigrationProof` tests.
8. Verify nonzero expected test execution.
9. Upload XML, HTML, logcat and Gradle logs.
10. Append summary to `$GITHUB_STEP_SUMMARY`.

Command execution must preserve exit code:

```bash
set -o pipefail
./gradlew ... 2>&1 | tee build/ci/migration-proof.log
```

## Full matrix

Keep a separate job:

```text
migration-proof-full
```

Run on:

- `main`/`master` push;
- `workflow_dispatch`;
- scheduled nightly run;
- release branch/tag.

The full job tests every supported historical schema version to latest.

## Branch protection

Document and configure the exact required status:

```text
Migration Proof
```

Do not require a conditional job that can disappear from PRs.

## Acceptance

Create temporary tests proving:

1. Missing migration fails.
2. Schema mismatch fails.
3. Data loss assertion fails.
4. Zero selected tests fails result verification.
5. Missing report fails.
6. PR event starts the job.
7. Main push starts full matrix.

Commit:

```text
fix(ci): require real Room migration proof on pull requests
```

---

# 9. GR-12 — Generate manifests and tests from canonical policy

## Objective

Authorization information must be manually maintained in one place only.

## Canonical files

Retain:

```text
config/guards/db_ownership_policy.yml
config/guards/db_structural_exceptions.yml
```

Move required classification metadata into canonical entries.

Structural entry example:

```yaml
- path: ...
  class: DatabaseBackupRepositoryImpl
  method: restoreCostBackup
  signature:
    parameters:
      - android.net.Uri
  operation: getDatabasePath
  classification: approved_baseline
  provenance:
    approved_commit: c891c3bb
  reason: ...
  owner: "@panospao7"
  linked_issue: MIT-003
```

Allowed classifications:

```text
approved_baseline
approved_addition
temporary_debt
test_fixture
```

A production policy must reject `test_fixture`.

## Remove duplicated authority

Deprecate and then remove:

```text
config/guards/db_structural_exceptions_expected_methods.yml
```

Its useful information must first move into canonical structural entries.

Remove hardcoded assumptions such as:

```text
ownership == 99
structural == 62
expected == 58
fixtures == 4
```

Policy count changes should be visible in generated diffs and CODEOWNERS review, not blocked merely because a number changed.

## Generator

Add:

```text
guard-analysis generate-contracts
```

or:

```text
scripts/generate_db_guard_contracts.py
```

Generated outputs:

```text
config/generated/db_guard_contract_snapshot.json
docs/generated/DB_WRITE_OWNER_TABLE.md
docs/generated/DB_STRUCTURAL_EXCEPTION_TABLE.md
build/reports/db-guard/policy-summary.json
```

The snapshot should contain:

- policy schema version;
- deterministic canonical tuples;
- policy SHA-256;
- counts by classification;
- counts by owner;
- counts by DAO;
- counts by barrier mode;
- no duplicated reason prose unless needed.

## Check mode

```bash
./gradlew generateDbGuardContracts
./gradlew verifyDbGuardContractsClean
```

`verifyDbGuardContractsClean` must:

1. Generate into a temporary directory.
2. Compare with checked-in generated outputs.
3. Fail on differences.
4. Print the command needed to regenerate.

Wire check mode into CI, not write mode.

CI must never modify the repository.

## Test redesign

Replace the giant policy-copy fixture test with:

### Schema tests

- required fields;
- unknown keys;
- duplicate symbol IDs;
- stale source evidence;
- invalid classification;
- malformed canonical paths;
- invalid operation;
- invalid barrier mode.

### Behavioral tests

- unauthorized writer fails;
- exact writer passes;
- wrong overload fails;
- wrong DAO fails;
- wrong operation fails;
- missing barrier fails;
- structural operation outside exact method fails;
- stale policy entry fails.

### Generation tests

- deterministic ordering;
- same input produces byte-identical output;
- one policy addition creates one derived tuple;
- one removal removes one derived tuple;
- documentation tables match policy;
- generated files contain no authorization not present in canonical policy.

Tests must not manually reproduce all production tuples.

## Compatibility period

For one PR:

1. Generate new snapshot.
2. Compare it to the old manifest.
3. Prove no authorization was added or removed accidentally.
4. Commit migration report.
5. Remove old manifest in the following commit.

Required report:

```text
build/guardrail-p1-p2/manifest-migration-diff.json
```

Commit sequence:

```text
feat(ci): generate DB contracts from canonical policy
chore(ci): remove duplicated structural manifest
test(ci): replace policy-copy fixtures with behavioral tests
```

---

# 10. GR-13 — Remove or redefine overlapping Gradle guards

## Objective

`:app:check` must invoke one implementation per architectural rule.

## Current overlap inventory

Create:

```text
docs/ci/GUARD_OWNERSHIP_MATRIX.md
```

Columns:

```text
Task/script
Rule ID
Patterns detected
Source scope
Authorization source
Baseline
Tests
Canonical replacement
Decision
```

At minimum classify:

```text
checkLifecycleBypasses
checkLifecycleBypass
verifyDbAccessBoundaries
```

## Expected decisions

### `checkLifecycleBypass`

Remove its inline scanner.

Its direct `expenseDao.insert/update/delete` behavior is a strict subset of canonical DB mutation ownership once Room-derived mutator detection is active.

### `checkLifecycleBypasses`

Choose one:

#### Option A — Remove

Use when all listed `ExpenseDao.update*` operations are already represented in exact canonical ownership policy.

#### Option B — Redefine as a separate invariant

Use only if it enforces a distinct domain rule such as:

> These specific ExpenseDao operations may only be called by TransactionLifecycleCoordinator, even if another class is otherwise an approved ExpenseDao owner.

If retained:

- give it a unique rule ID;
- implement it in the PSI analyzer;
- use canonical policy;
- remove its Gradle filename allowlist;
- add independent adversarial tests;
- document why DB ownership alone is insufficient.

Do not retain an inline string scanner.

## Compatibility aliases

For one release cycle, legacy task names may remain as aliases:

```text
checkLifecycleBypass -> dependsOn verifyDbAccessBoundaries
checkLifecycleBypasses -> dependsOn verifyDbAccessBoundaries
```

Aliases must contain no scanning logic and no allowlists.

Mark them deprecated in task descriptions.

Remove aliases after local scripts and workflow references are migrated.

## Canonical Gradle wiring

Desired task graph:

```text
:app:check
  -> :app:verifyDbAccessBoundaries
       -> :guard-analysis:installDist or shadowJar
       -> canonical PSI analyzer
       -> canonical policy
       -> ratchet
```

The static suite must invoke the same analyzer, not a separate implementation.

## Tests

Add Gradle TestKit or script-level contract tests proving:

1. `:app:check` includes canonical DB verification.
2. Legacy task aliases delegate only.
3. No inline lifecycle allowlist remains.
4. No duplicate DB scanner runs.
5. Canonical violation fails both static suite and Gradle.
6. Canonical infrastructure error fails both paths.
7. Both paths produce the same fingerprint.

## Acceptance

```bash
./gradlew :app:tasks --all
./gradlew :app:check --dry-run
./gradlew :app:check --no-daemon --stacktrace
python3 scripts/ci/run_static_guard_suite.py
```

Search for prohibited remnants:

```bash
rg "allowlistForGuard|checkLifecycleBybasses|expenseDao\\\\.insert|expenseDao\\\\.update|expenseDao\\\\.delete" \
  app/build.gradle.kts
```

Expected result: no inline DB authorization scanner.

Commit:

```text
refactor(ci): consolidate DB enforcement under canonical analyzer
```

---

# 11. GR-14 — Final integration and adversarial proof

Run:

```bash
./gradlew :guard-analysis:test --no-daemon --stacktrace

python3 -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  scripts/ci/test_*.py \
  -v --tb=short

python3 scripts/ci/verify_guard_registry.py
python3 scripts/ci/run_static_guard_suite.py

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace

./gradlew :app:verifyRoomSchemaSnapshots \
  -PstrictRoomSchemas=true \
  --no-daemon --stacktrace

./gradlew :app:testDebugUnitTest \
  --tests "*DbGuard*" \
  --no-daemon --stacktrace

./gradlew :app:check \
  --no-daemon --stacktrace
```

Then verify migration proof on an emulator.

## Artificial violations

Inject one at a time in a temporary worktree:

1. Helper writer inside `ExpenseDao.kt`.
2. Top-level writer inside `ExpenseDao.kt`.
3. Unauthorized DAO in a non-DAO-named file.
4. Two overloads writing different DAOs.
5. Authorize only the wrong overload.
6. Add a third overload after policy approval.
7. Add a structural operation to an unapproved overload.
8. Remove a generated file update.
9. Edit generated snapshot manually.
10. Add direct ExpenseDao mutation formerly caught only by legacy Gradle guard.
11. Break a migration.
12. Configure migration selection to execute zero tests.

Every case must fail with the intended rule and exit code.

Revert each artificial mutation.

---

# 12. Agent assignments

## Agent A — Dao.kt exclusion

Owns GR-08 only.

Must not modify policy schema.

## Agent B — Signature identity

Owns GR-09.

Must provide policy migration tooling and overload fixtures.

## Agent C — Migration gate

Owns GR-11 independently.

Must produce emulator execution evidence.

## Agent D — PSI foundation

Owns PSI environment, source inventory and symbol models.

## Agent E — PSI DB analysis

Starts after Agent D. Ports Room, mutation, barrier and structural analyzers.

## Agent F — Generated contracts

Starts after PSI policy loading is stable.

## Agent G — Gradle consolidation

Starts after PSI becomes authoritative.

## Agent H — Independent adversarial reviewer

Attempts bypasses and must not modify the primary implementation.

---

# 13. Merge order

```text
1. GR-08 Dao.kt declaration-level scanning
2. GR-09 exact overload signatures
3. GR-11 migration PR gate
4. GR-10A PSI foundation
5. GR-10B PSI comparison mode
6. GR-10C PSI authoritative mode
7. GR-12 generated contracts
8. GR-13 legacy guard consolidation
9. GR-14 final evidence and documentation
```

Do not merge GR-13 before PSI is authoritative and parity evidence is reviewed.

---

# 14. Definition of done

The work is complete only when:

- DAO filenames no longer control scanning.
- Non-DAO code inside `*Dao.kt` files is analyzed.
- Exact function signatures identify policy owners.
- Overload bodies are never union-authorized.
- Kotlin source structure is parsed through PSI.
- Regex is not used for Kotlin declaration or call analysis.
- Parser ambiguity fails closed.
- Migration proof executes on every relevant PR.
- Migration test selection uses instrumentation-runner arguments.
- Zero executed migration tests fail CI.
- Policy metadata has one canonical source.
- Manifests and documentation are generated.
- Tests validate behavior rather than duplicate all policy tuples.
- Hardcoded 99/62/58/4 contracts are removed.
- Inline overlapping Gradle DB scanners are removed or redefined as genuinely separate rules.
- Static CI and Gradle invoke the same canonical analyzer.
- All adversarial fixtures fail as expected.
- One exact final SHA has a completely green required CI run.