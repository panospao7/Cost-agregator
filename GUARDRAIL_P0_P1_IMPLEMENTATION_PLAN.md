# Guardrail P0/P1 Implementation Plan

**Starting commit:** `6f0e46c846e71d3506be53c8b1a8b4abf86a6cab`  
**Scope:** Six DB/time guardrail correctness defects  
**Primary guard:** `scripts/verify_db_access_boundaries.py`

## 1. Mission

Implement and prove:

1. Room-declaration-derived DAO mutator discovery.
2. Helper and WorkerExecutionGuard mediation verification.
3. Fail-closed Gradle DB verification.
4. Correct direct-time detection.
5. Structurally safe barrier verification.
6. DAO parameter, safe-call, assertion and scope-function support.

Final invariant:

> Every production Room mutation must be discovered independently of its method name, attributed to its exact caller, authorized by exact policy, and protected by a directly dominating barrier or a statically proven mediation chain.

---

# 2. Orchestrator rules

## Prohibited shortcuts

Agents must not:

- add mutation verbs to `MUTATION_VERBS` as the final solution;
- increase the DB baseline merely because new detection finds more writes;
- broaden ownership policy entries;
- authorize wildcard methods or generic `operation: write`;
- treat `barrier_via` as documentation;
- accept a barrier merely because it appears on an earlier line;
- silence unsupported syntax;
- exempt time calls using arbitrary source-line substrings;
- weaken existing adversarial tests.

## Required evidence for every PR

Each PR report must contain:

```text
Starting SHA
Ending SHA
Files changed
Tests added
Tests failing before implementation
Tests passing after implementation
Commands and exit codes
Newly discovered production findings
Policy or baseline changes
Remaining unsupported syntax
```

## Exit-code contract

All guard components must preserve:

```text
0 = pass
1 = architecture violation
2 = infrastructure/configuration/parser failure
```

Unexpected exceptions must become controlled exit `2`, without raw sensitive exception messages.

---

# 3. Dependency order

```text
PR-GR-01 Gradle fail-closed ───────────────┐
                                           ├── integration
PR-GR-02 Direct-time guard ────────────────┘

PR-GR-03 Room mutator inventory
        ↓
PR-GR-04 DAO receiver/call-form support
        ↓
PR-GR-05 Barrier structural dominance
        ↓
PR-GR-06 Helper/worker mediation proof
        ↓
PR-GR-07 Final integration and policy migration
```

PR-GR-01 and PR-GR-02 may run in parallel.

PR-GR-03 through PR-GR-06 must be integrated sequentially because they modify the same scanner model.

---

# 4. Phase 0 — Freeze and baseline current behavior

## 4.1 Create branch

```bash
git fetch --all --prune
git checkout --detach 6f0e46c846e71d3506be53c8b1a8b4abf86a6cab
git checkout -b guardrail-p0-p1-hardening
git status --short
```

## 4.2 Capture baseline

```bash
mkdir -p build/guardrail-hardening/before

python3 -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short \
  2>&1 | tee build/guardrail-hardening/before/db-tests.log

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  2>&1 | tee build/guardrail-hardening/before/db-scan.log

python3 scripts/ci/run_static_guard_suite.py \
  2>&1 | tee build/guardrail-hardening/before/static-suite.log

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace \
  2>&1 | tee build/guardrail-hardening/before/gradle-db.log

./gradlew :app:checkDirectTimeCalls \
  --no-daemon --stacktrace \
  2>&1 | tee build/guardrail-hardening/before/time-guard.log
```

## 4.3 Create implementation ledger

Create:

```text
docs/ci/DB_GUARD_HARDENING_LEDGER.md
```

Record current:

- scanner exit code;
- violation count by rule;
- baseline count;
- ownership-policy entry count;
- structural-exception count;
- test count;
- execution duration;
- unsupported parser findings;
- exact commands.

Do not change policy or baseline in Phase 0.

---

# 5. PR-GR-01 — Make Gradle DB guard fail closed

## Objective

`:app:verifyDbAccessBoundaries` must fail if any required guard component is absent, unreadable, malformed or unexecutable.

## Files

```text
app/build.gradle.kts
scripts/ci/test_gradle_db_guard_contract.py
.github/workflows/ci.yml
docs/ci/local-ci.md
```

Prefer extracting task logic into:

```text
buildSrc/src/main/kotlin/VerifyDbAccessBoundariesTask.kt
buildSrc/src/test/kotlin/VerifyDbAccessBoundariesTaskTest.kt
```

if `buildSrc` already exists or can be added without destabilizing Gradle configuration.

## Implementation

### 5.1 Validate all inputs

Before execution, require:

```text
scripts/ci/guard_ratchet.py
scripts/verify_db_access_boundaries.py
config/baselines/db_access.json
config/guards/db_ownership_policy.yml
config/guards/db_structural_exceptions.yml
config/guards/db_structural_exceptions_expected_methods.yml
```

For each input validate:

- exists;
- is a regular file;
- is readable;
- canonical path remains inside repository root.

Missing input must throw `GradleException`, never warn and return.

### 5.2 Validate Python

Support:

```text
-PpythonExecutable=/path/to/python3
```

Default to `python3`.

Run a preflight:

```bash
python3 --version
```

Failure to launch Python is infrastructure exit `2`.

### 5.3 Eliminate shell-string ambiguity

Do not construct an unsafe command string containing unescaped absolute paths.

Preferred change to `guard_ratchet.py`:

```text
--command-arg python3
--command-arg scripts/verify_db_access_boundaries.py
--command-arg --fail-on-violation
```

Internally invoke with `subprocess.run(list_args)`.

Maintain the old `--command` temporarily only for compatibility, then deprecate it.

### 5.4 Correct failure messages

Remove the stale recommendation to update:

```text
config/db_access_allowlist.yml
```

The message must direct developers to:

```text
config/guards/db_ownership_policy.yml
config/guards/db_structural_exceptions.yml
docs/DB_WRITE_OWNERSHIP.md
```

### 5.5 Tests

Required negative tests:

1. Ratchet script missing.
2. DB guard script missing.
3. Baseline missing.
4. Ownership policy missing.
5. Structural policy missing.
6. Manifest missing.
7. Python executable missing.
8. Ratchet exits `1`.
9. Ratchet exits `2`.
10. Ratchet exits unexpected code.
11. Input path points outside repository.
12. Malformed baseline.
13. Successful invocation returns zero.

Each missing-input test must prove the Gradle task fails.

Expose test-only path overrides:

```text
-PdbGuardRatchetPath=...
-PdbGuardScriptPath=...
-PdbGuardBaselinePath=...
```

Do not use overrides in production CI.

## Acceptance criteria

```bash
./gradlew :app:verifyDbAccessBoundaries --no-daemon --stacktrace
./gradlew :app:check --no-daemon --stacktrace
python3 -m pytest scripts/ci/test_gradle_db_guard_contract.py -v
```

All pass.

Deleting or overriding any required input causes nonzero Gradle execution.

## Commit

```text
fix(ci): make Gradle DB guard fail closed
```

---

# 6. PR-GR-02 — Replace the defective direct-time guard

## Objective

Detect direct production wall-clock calls without blanket source-text exemptions.

## Recommended design

Move time scanning out of inline Gradle Kotlin and into a tested canonical script:

```text
scripts/verify_time_boundaries.py
scripts/test_verify_time_boundaries.py
config/guards/time_boundary_exceptions.yml
```

The Gradle task becomes a fail-closed wrapper around the script.

## APIs to detect

Initially enforce:

```text
System.currentTimeMillis()
System.nanoTime()
Date()
Calendar.getInstance()
Instant.now()
LocalDate.now()
LocalDateTime.now()
OffsetDateTime.now()
ZonedDateTime.now()
Clock.systemDefaultZone()
Clock.systemUTC()
```

Do not classify `System.nanoTime()` as wall-clock if it is used exclusively for elapsed-duration measurement. Such cases require exact symbol authorization.

## Exact exception schema

```yaml
version: 1
exceptions:
  - path: app/src/main/java/.../SystemTimeProvider.kt
    class: SystemTimeProvider
    method: now
    api: Instant.now
    reason: "Canonical platform clock adapter"
    owner: "@panospao7"
    linked_issue: "MIT-003"
```

Reject:

- wildcard paths;
- wildcard methods;
- missing owner/reason/issue;
- stale entries without matching source evidence;
- file-level exemptions.

## Scanner behavior

- Scan production sources only.
- Mask comments, strings, triple strings and character literals.
- Ignore imports.
- Resolve enclosing class and method.
- Match exact API token sequences.
- Report rule, path, line, class and method.
- Fail with exit `2` if method attribution is unsupported.

Remove all exemptions based on:

```text
contains("now()")
contains("now =")
contains("TimeProvider(")
```

## Required tests

Negative fixtures:

```kotlin
val now = Instant.now()
val date = LocalDate.now()
val timestamp = System.currentTimeMillis()
val calendar = Calendar.getInstance()
fun now() = ZonedDateTime.now()
val now = LocalDate.now()
```

The variable or method being named `now` must not suppress detection.

Positive fixtures:

- injected `TimeProvider.now()`;
- exact authorized `SystemTimeProvider`;
- API name in comment;
- API name in ordinary string;
- import statement;
- `System.nanoTime()` in an exactly approved monotonic-duration adapter.

Infrastructure tests:

- missing exception file;
- malformed YAML;
- stale exception;
- unreadable source;
- unsupported declaration.

## Gradle wiring

```text
checkDirectTimeCalls
    -> python3 scripts/verify_time_boundaries.py --fail-on-violation
```

Missing script/config/Python must fail.

Add the script and its tests to the static guard registry.

## Acceptance criteria

```bash
python3 -m pytest scripts/test_verify_time_boundaries.py -v
python3 scripts/verify_time_boundaries.py --fail-on-violation
./gradlew :app:checkDirectTimeCalls --no-daemon --stacktrace
```

## Commit

```text
fix(ci): enforce exact direct-time boundaries
```

---

# 7. PR-GR-03 — Derive mutator inventory from Room declarations

## Objective

Replace naming-based mutation discovery with an inventory generated from actual DAO declarations.

`MUTATION_VERBS` may remain temporarily for comparison diagnostics, but it must no longer decide whether a call is a mutation.

## New modules

Refactor the scanner into testable components:

```text
scripts/db_guard/__init__.py
scripts/db_guard/kotlin_masking.py
scripts/db_guard/kotlin_declarations.py
scripts/db_guard/room_mutator_inventory.py
scripts/db_guard/models.py
scripts/test_db_guard_room_inventory.py
```

Avoid duplicating masking/parsing logic. Move existing stable helpers rather than rewriting them unnecessarily.

## Core data model

```text
DaoType
- qualified_name
- simple_name
- path
- parent_interfaces
- is_room_dao

DaoMethod
- dao_type
- method_name
- parameter_count
- normalized_signature
- annotation_kind
- mutation_kind
- declaration_path
- declaration_line
- inherited_from
```

Mutation kinds:

```text
INSERT
UPDATE
DELETE
UPSERT
MUTATING_QUERY
RAW_QUERY_WRITE
TRANSITIVE_WRAPPER
```

## 7.1 Discover DAO declarations

Scan Kotlin declarations based on `@Dao`, not filename.

A file named `SomethingDao.kt` is not automatically a DAO declaration.

A DAO declaration in a differently named file must still be discovered.

Support:

- interfaces;
- abstract classes;
- nested declarations if present;
- multiline annotations;
- annotations with qualified names;
- generic base interfaces;
- multiple declarations in one file.

## 7.2 Classify direct mutators

Always mutating:

```text
@Insert
@Update
@Delete
@Upsert
```

Support qualified annotations such as:

```kotlin
@androidx.room.Insert
```

The exact method name is the operation. Examples:

```text
save
persist
remove
wipe
applyStatus
```

must be detected if the declaration says they mutate.

## 7.3 Classify `@Query`

Extract the compile-time SQL string from `@Query`.

Mask SQL comments and normalize whitespace.

Classify these as mutations:

```text
INSERT
UPDATE
DELETE
REPLACE
CREATE
DROP
ALTER
VACUUM
ATTACH
DETACH
```

Handle CTEs:

```sql
WITH ... UPDATE ...
WITH ... DELETE ...
WITH ... INSERT ...
WITH ... SELECT ...
```

Implement a small SQL tokenizer that tracks:

- parentheses;
- quoted identifiers;
- quoted strings;
- comments;
- top-level statement keywords.

Do not classify using a naive substring such as `"UPDATE" in sql`, because a SELECT string or column name could contain that token.

If SQL cannot be classified deterministically, return infrastructure error:

```text
DB_SCAN_QUERY_CLASSIFICATION_UNSUPPORTED
```

## 7.4 Handle `@RawQuery`

`@RawQuery` cannot be assumed read-only.

Create:

```text
config/guards/db_raw_query_classification.yml
```

Exact schema:

```yaml
version: 1
methods:
  - dao: SomeDao
    method: executeRaw
    parameter_count: 1
    classification: write
    reason: "Executes controlled repair SQL"
    owner: "@panospao7"
    linked_issue: "MIT-003"
```

Allowed classifications:

```text
read
write
```

An unclassified `@RawQuery` is infrastructure error `2`.

Classification entries require source evidence and must reject stale entries.

## 7.5 Handle inheritance

Build the DAO inheritance graph.

For:

```kotlin
interface ExpenseDao : BaseDao<Expense>
```

include mutators inherited from `BaseDao`.

Use a fixed-point graph traversal.

Reject:

- unresolved parent interface;
- inheritance cycle;
- ambiguous same-name DAO declarations;
- unsupported external mutating base DAO.

If a common base DAO is imported from another module, include that module’s production Kotlin source root.

## 7.6 Handle DAO default wrappers

Example:

```kotlin
@Transaction
suspend fun replace(item: Item) {
    delete(item)
    insert(item)
}
```

A default DAO method is mutating when it invokes a known mutator.

Run fixed-point transitive classification:

1. Seed annotated mutators.
2. Parse same-DAO calls in default method bodies.
3. Mark wrappers calling a mutator as `TRANSITIVE_WRAPPER`.
4. Repeat until stable.

Reject unresolved function references or dynamic wrapper calls rather than silently treating them as reads.

## 7.7 Map DAO accessors to DAO types

Parse `AppDatabase` and equivalent Room database declarations:

```kotlin
abstract fun expenseDao(): ExpenseDao
```

Build:

```text
expenseDao -> ExpenseDao
```

Do not rely only on lowercasing the DAO type name.

Fail on:

- duplicate accessor names;
- accessor returning unknown DAO;
- DAO property with ambiguous type;
- database accessor parser failure.

## 7.8 Replace call classification

The call-site scanner should:

1. Resolve receiver to DAO type/accessor.
2. Extract receiver method name and argument count.
3. Query the generated Room mutator inventory.
4. Treat the call as a mutation only when inventory says it mutates.
5. Preserve the exact operation name for policy comparison.

The old verb grammar may run temporarily in comparison mode:

```text
INVENTORY_ONLY
VERB_ONLY
BOTH
```

Any `VERB_ONLY` result must be reviewed because it may represent:

- a missed DAO declaration;
- a non-mutating method with a mutation-like name;
- parser failure.

After migration, remove the grammar from authoritative detection.

## Required red tests

DAO declarations:

```kotlin
@Insert suspend fun save(item: Item)
@Upsert suspend fun persist(item: Item)
@Delete suspend fun remove(item: Item)
@Query("DELETE FROM items") suspend fun wipe()
@Query("UPDATE items SET state = :state") suspend fun applyStatus(state: Int)
```

Additional tests:

- harmless read method named `getUpdatedRows` is not a mutation;
- inherited `BaseDao.save`;
- multiline annotation;
- qualified annotation;
- DAO in non-`Dao.kt` file;
- helper class in `SomethingDao.kt` is still scanned as a caller;
- mutating CTE;
- read-only CTE;
- unclassified raw query fails;
- transitive default DAO wrapper;
- duplicate DAO declaration;
- inheritance cycle;
- unsupported SQL fails closed.

## Rollout rule

Run old and new detection against the starting SHA and produce:

```text
build/guardrail-hardening/mutator-inventory-delta.json
```

Classify every newly visible write:

```text
LEGITIMATE_AUTHORIZED_WRITER
REAL_ARCHITECTURE_VIOLATION
PREEXISTING_UNRESOLVED_DEBT
PARSER_FALSE_POSITIVE
```

No finding may remain `UNCLASSIFIED`.

A newly detected legitimate writer may receive an exact policy entry.

A real unsafe path must be fixed.

A baseline change is allowed only after proof that the finding existed before this scanner PR, with owner review and linked expiry. Prefer fixing it instead.

## Acceptance criteria

```bash
python3 -m pytest \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short

python3 scripts/verify_db_access_boundaries.py \
  --dump-mutator-inventory build/db-mutator-inventory.json \
  --fail-on-violation
```

Adding a new `@Insert fun save()` and calling it from an unauthorized class must fail without editing any verb list.

## Commit

```text
feat(ci): derive DB mutators from Room declarations
```

---

# 8. PR-GR-04 — Handle DAO parameters, safe calls and scope functions

## Objective

Every supported way of calling a known DAO must resolve to the same canonical DAO identity and type.

## Receiver model

Replace plain `variable -> accessor` mappings with:

```text
DaoReceiver
- variable_name
- dao_type
- accessor_identity
- declaration_scope
- declaration_line
- nullable
- origin
```

Origins:

```text
CONSTRUCTOR_PARAMETER
CLASS_PROPERTY
METHOD_PARAMETER
LOCAL_VARIABLE
DATABASE_ACCESSOR
PROPERTY_GETTER
DELEGATED_PROPERTY
LAMBDA_RECEIVER
LAMBDA_ARGUMENT
```

## 8.1 Ordinary method parameters

Parse parameters from every function declaration:

```kotlin
private suspend fun write(dao: ExpenseDao)
```

Register `dao` as `ExpenseDao`.

Support:

- multiline parameter lists;
- nullable DAO types;
- fully qualified types;
- annotations on parameters;
- default values;
- `crossinline`/`noinline`;
- generic DAO bounds when resolvable.

## 8.2 Receiver syntax

Recognize:

```kotlin
dao.insert(item)
dao?.insert(item)
dao!!.insert(item)
this.dao.insert(item)
database.expenseDao().insert(item)
database
    .expenseDao()
    .insert(item)
```

Safe-call and non-null assertion operators must not affect DAO identity.

## 8.3 Property getters and delegates

Support:

```kotlin
private val dao: ExpenseDao
    get() = database.expenseDao()

private val dao by lazy { database.expenseDao() }
```

Only accept getters/delegates that resolve to one exact DAO accessor.

Ambiguous or conditional getters must fail closed:

```text
UNSUPPORTED_DAO_PROPERTY_RESOLUTION
```

## 8.4 Scope functions

Support these semantics:

### Receiver-lambda forms

```kotlin
with(dao) { insert(item) }
dao.run { insert(item) }
dao.apply { insert(item) }
```

Unqualified mutator calls inside the lambda use the DAO as implicit receiver.

### Argument-lambda forms

```kotlin
dao.let { it.insert(item) }
dao.let { expenseDao -> expenseDao.insert(item) }
dao.also { it.insert(item) }
```

Register the lambda argument for the exact lambda extent.

### Nested scopes

Correctly resolve:

```kotlin
with(dao) {
    transaction {
        insert(item)
    }
}
```

Do not leak a lambda receiver outside its braces.

### Prohibited ambiguous forms

Fail closed for:

- DAO passed into unknown higher-order function;
- stored DAO lambda;
- escaped function reference;
- ambiguous nested implicit receivers;
- destructured DAO lambda argument;
- unsupported custom scope function.

Error:

```text
UNSUPPORTED_DAO_CALL_FORM
```

## 8.5 Candidate extraction

For known DAO receivers, inspect all method calls and compare them to the Room inventory.

Do not first filter by method-name grammar.

## Required tests

Each mutator must fail outside policy in these forms:

```text
ordinary method parameter
nullable parameter with ?.
non-null assertion with !!
this.dao
database.dao()
multiline direct chain
property getter
lazy property
with(dao)
dao.run
dao.apply
dao.let with it
dao.let with named parameter
dao.also
nested scope function
```

Positive tests:

- read-only DAO call in all supported forms;
- local variable shadowing does not inherit outer DAO type;
- lambda argument does not escape;
- non-DAO object named `dao` does not trigger;
- unrelated `run` block does not trigger.

## Acceptance criteria

A mutation cannot evade detection solely by changing:

```kotlin
dao.insert(x)
```

to:

```kotlin
dao?.insert(x)
with(dao) { insert(x) }
```

## Commit

```text
feat(ci): resolve all supported DAO call forms
```

---

# 9. PR-GR-05 — Add structurally safe barrier verification

## Objective

Replace “barrier appears on an earlier line” with per-mutation structural evidence.

## Short-term safety model

This PR does not need a complete Kotlin CFG. It must implement a conservative structural dominance model that rejects uncertain cases.

## Barrier modes

Migrate policy entries toward:

```yaml
barrier:
  mode: direct_check
```

or:

```yaml
barrier:
  mode: run_write
```

Mediated modes are introduced in PR-GR-06.

Temporarily accept old `barrier_required` only during policy migration. Final integration must reject the old schema.

## 9.1 Build structural regions

For each method, parse a region tree:

```text
METHOD_ROOT
IF_BRANCH
ELSE_BRANCH
WHEN_BRANCH
LOOP
TRY
CATCH
FINALLY
LAMBDA
LOCAL_FUNCTION
RUN_WRITE_LAMBDA
WORKER_GUARD_LAMBDA
```

Each mutation and barrier call receives:

- enclosing region chain;
- statement position;
- source line;
- nesting depth.

## 9.2 `direct_check` acceptance

Accept `writeBarrier.checkWritesAllowed()` only when:

1. receiver is the exact `DatabaseWriteBarrier` property;
2. call is a standalone statement;
3. call is in the method root region;
4. call occurs before the mutation;
5. call is not inside:
   - `if`;
   - `when`;
   - loop;
   - `try`/`catch`;
   - lambda;
   - local function;
   - `runCatching`;
6. mutation remains in a region dominated by the root-level check.

A root-level check followed by an `if` containing the mutation is acceptable because all paths reaching the branch passed the check.

A check inside the branch is conservatively rejected in the first implementation.

## 9.3 `run_write` acceptance

For:

```kotlin
writeBarrier.runWrite {
    dao.insert(item)
}
```

accept only mutations structurally contained inside that exact lambda.

Do not accept:

```kotlin
writeBarrier.runWrite { audit() }
dao.insert(item)
```

Do not let one `runWrite` protect later mutations outside its body.

## 9.4 Unsafe examples that must fail

```kotlin
if (debug) {
    writeBarrier.checkWritesAllowed()
}
dao.insert(item)
```

```kotlin
runCatching {
    writeBarrier.checkWritesAllowed()
}
dao.insert(item)
```

```kotlin
val check = {
    writeBarrier.checkWritesAllowed()
}
dao.insert(item)
```

```kotlin
writeBarrier.runWrite {
    auditDao.insert(audit)
}
expenseDao.insert(expense)
```

```kotlin
dao.insert(item)
writeBarrier.checkWritesAllowed()
```

```kotlin
try {
    writeBarrier.checkWritesAllowed()
} catch (_: Exception) {
}
dao.insert(item)
```

## 9.5 Source-evidence changes

Replace `_barrier_before_line()` with something equivalent to:

```text
analyze_barrier_evidence(method_model, mutation)
```

Return:

```text
BarrierEvidence
- mode
- valid
- barrier_line
- mutation_line
- barrier_region
- mutation_region
- reason_code
```

Controlled reason codes:

```text
MISSING_WRITE_BARRIER
NON_DOMINATING_WRITE_BARRIER
BARRIER_AFTER_MUTATION
MUTATION_OUTSIDE_RUN_WRITE
AMBIGUOUS_BARRIER_RECEIVER
UNSUPPORTED_BARRIER_CONTROL_FLOW
```

The scan-side authorization and policy-side source validation must use the same evidence engine.

## Required tests

At least:

- top-level direct check passes;
- direct check after mutation fails;
- check in false branch fails;
- check in `runCatching` fails;
- check in lambda variable fails;
- check in local function fails;
- check in catch fails;
- mutation inside `runWrite` passes;
- mutation after `runWrite` fails;
- nested mutation inside `runWrite` passes;
- qualified fake barrier receiver fails;
- comment/string evidence fails;
- expression-bodied `runWrite` passes;
- unsupported structural form exits `2`.

## Acceptance criteria

No lexical barrier occurrence can authorize a mutation unless it structurally dominates that mutation or directly encloses it through `runWrite`.

## Commit

```text
feat(ci): prove structural write-barrier dominance
```

---

# 10. PR-GR-06 — Prove helper and worker mediation

## Objective

A method without a direct barrier may be approved only when every production call path to it is statically proven guarded.

## New policy schema

Replace:

```yaml
barrier_required: false
barrier_via: WorkerExecutionGuard
```

with explicit contracts.

### Worker-owned mutation

```yaml
barrier:
  mode: worker_guard
  mediation:
    guard_type: WorkerExecutionGuard
    guard_methods:
      - runGuardedWithContext
    root:
      path: app/src/main/java/.../WarrantyExpirationWorker.kt
      class: WarrantyExpirationWorker
      method: doWork
    call_path:
      - doWork
      - deliverReminder
```

### Guarded delegated writer

```yaml
barrier:
  mode: guarded_delegate
  mediation:
    allowed_callers:
      - path: app/src/main/java/.../WorkerExecutionGuard.kt
        class: WorkerExecutionGuard
        method: startRunSafely
        required_barrier_mode: direct_check
```

## 10.1 Build a conservative call graph

Method identity:

```text
path + class + method + parameter_count
```

Capture:

- visibility;
- enclosing class;
- direct method calls;
- typed receiver calls;
- same-class calls;
- function references;
- call-site structural region;
- whether call site is inside a proven guard region.

Do not use method-name-only global matching.

## 10.2 Worker guard roots

Recognize exact calls to configured guard methods, for example:

```kotlin
workerExecutionGuard.runGuardedWithContext(...) {
    // guarded region
}
```

Validate receiver type as `WorkerExecutionGuard`.

The guard lambda itself becomes a `WORKER_GUARD_LAMBDA` region.

A similarly named method on another type must not count.

## 10.3 Same-class private helper propagation

A helper such as `deliverReminder` is mediated only when:

1. it is actually `private`;
2. all call sites are known;
3. every call site is inside a proven guarded region or another proven mediated helper;
4. no call site exists outside the chain;
5. no function reference escapes:
   - `::deliverReminder`;
   - storing in variable;
   - returning it;
   - passing it to unknown callback;
6. recursive call groups have a guarded root and no unguarded entry.

Use fixed-point propagation:

```text
guarded root
    -> private helper called only from guarded regions
        -> deeper private helper called only from mediated helpers
```

## 10.4 Cross-class delegated writers

For `WorkerRunLoggerImpl.start`, `Handle.terminal`, or similar paths:

1. list exact allowed callers in policy;
2. resolve caller receiver type;
3. scan all production call sites;
4. require actual call-site set to exactly match or be a subset of allowed callers;
5. prove every allowed caller has direct or worker-guard evidence;
6. fail on any additional caller.

Public delegated writers are discouraged. Prefer reducing visibility to `internal` or `private` where architecture permits.

## 10.5 Worker helper validation

For each DB-writing `CoroutineWorker`:

- `doWork` must enter the configured WorkerExecutionGuard.
- Direct mutations in `doWork` must be inside the guard lambda.
- Helper mutations must have a fully proven helper chain.
- A helper called before entering the guard must fail.
- A helper callable from an unguarded receiver/action must fail.
- Guard callbacks must not escape for later execution.

## 10.6 Mediation source evidence

Required errors:

```text
MEDIATION_ROOT_MISSING
MEDIATION_GUARD_TYPE_MISMATCH
MEDIATION_GUARD_METHOD_MISSING
MEDIATION_CALL_PATH_BROKEN
MEDIATED_HELPER_NOT_PRIVATE
MEDIATED_WRITER_UNGUARDED_CALLER
MEDIATED_WRITER_UNKNOWN_CALLER
MEDIATED_REFERENCE_ESCAPE
MEDIATION_RECURSION_UNPROVEN
MEDIATION_POLICY_STALE
```

Mediation uncertainty is infrastructure/configuration failure `2`, not an authorized pass.

## Required adversarial tests

### Worker tests

- mutation directly inside guard lambda passes;
- mutation before guard invocation fails;
- helper called only inside guard passes;
- same helper also called outside guard fails;
- helper is public fails;
- fake `WorkerExecutionGuard` type fails;
- similarly named guard method fails;
- guard lambda stored and executed later fails;
- function reference to helper escapes and fails;
- recursive helper with unguarded entry fails.

### Delegated writer tests

- exact guarded caller passes;
- extra unlisted caller fails;
- allowed caller lacks direct barrier and fails;
- caller invokes writer before barrier and fails;
- caller’s barrier is in conditional and fails;
- policy names nonexistent caller and fails;
- receiver type cannot be resolved and fails;
- interface call resolves to expected implementation where provable;
- ambiguous implementation fails closed.

## Production migration

Inventory every current `barrier_via: WorkerExecutionGuard` entry.

At minimum review:

```text
DataRetentionWorker.doWork
WarrantyExpirationWorker.doWork
WarrantyExpirationWorker.deliverReminder
WorkerRunLoggerImpl.start
Handle.terminal
```

For each entry produce:

```text
root guarded method
guard invocation line
mutation method
all call sites
visibility
proven call chain
unsupported/escaping call forms
```

Do not retain `barrier_via` after migration.

## Acceptance criteria

Deleting a WorkerExecutionGuard wrapper, moving a helper call outside it, adding an unguarded caller, or changing a helper from private to public must fail CI.

## Commit

```text
feat(ci): prove worker and helper barrier mediation
```

---

# 11. PR-GR-07 — Final integration

## 11.1 Remove transitional behavior

After all production findings are triaged:

- remove authoritative use of `MUTATION_VERBS`;
- reject old `barrier_required` and `barrier_via`;
- remove `_barrier_before_line`;
- stop skipping entire `*Dao.kt` files;
- remove duplicate inline Gradle time scanning;
- remove stale references to the legacy DB allowlist;
- remove comparison-only diagnostics if no longer needed.

## 11.2 Policy validation

Final ownership entries must include exactly one barrier mode:

```text
direct_check
run_write
worker_guard
guarded_delegate
```

Structural exceptions remain separate and must not bypass DAO mutation authorization.

Every mediation entry must have source evidence.

## 11.3 Report format

DB guard summary should include:

```text
DAO declarations discovered
DAO mutators discovered
Inherited mutators
Raw queries classified
Production Kotlin files scanned
DAO call sites resolved
Mutations found
Direct-check protected mutations
runWrite-protected mutations
Worker-mediated mutations
Delegated mutations
Authorized mutations
Known debt
New violations
Infrastructure errors
Unsupported syntax
```

## 11.4 CI commands

Required local sequence:

```bash
python3 -m pytest \
  scripts/test_db_guard_*.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_verify_time_boundaries.py \
  scripts/ci/test_*.py \
  -v --tb=short

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --dump-mutator-inventory build/db-mutator-inventory.json

python3 scripts/verify_time_boundaries.py \
  --fail-on-violation

python3 scripts/ci/verify_guard_registry.py

python3 scripts/ci/run_static_guard_suite.py

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace

./gradlew :app:checkDirectTimeCalls \
  --no-daemon --stacktrace

./gradlew :app:testDebugUnitTest \
  --tests "*DbGuard*" \
  --no-daemon --stacktrace

./gradlew :app:check \
  --no-daemon --stacktrace
```

## 11.5 Artificial end-to-end violations

Before declaring completion, inject each change one at a time into a temporary working tree:

1. Add `@Insert fun save`.
2. Call `dao?.save`.
3. Call it through `with(dao)`.
4. Put barrier inside `if (false)`.
5. Move helper call outside worker guard.
6. Add second unguarded delegated-writer caller.
7. Remove ratchet script through path override.
8. Add `Instant.now()` to ordinary production class.

Every case must fail the correct job with a controlled rule ID.

Revert each artificial violation afterward.

## Final commit

```text
docs(ci): record verified DB guard hardening
```

---

# 12. Agent assignment format

## Agent A — Gradle fail-closed

Owns PR-GR-01 only. Must not modify DB parser semantics.

## Agent B — Time guard

Owns PR-GR-02 only. Must not modify DB ownership policy.

## Agent C — Room inventory

Owns PR-GR-03. Must deliver generated inventory and old-vs-new delta report.

## Agent D — Receiver resolution

Starts only after Agent C is integrated.

## Agent E — Barrier structural analysis

Starts after receiver model is stable.

## Agent F — Mediation proof

Starts only after structural barrier evidence is merged.

## Agent G — Independent adversarial reviewer

Does not implement the primary scanner. Attempts bypasses involving:

- unusual mutation names;
- aliases;
- safe calls;
- scope functions;
- branches;
- lambdas;
- worker helpers;
- delegated writers;
- missing CI inputs.

Agent G must report bypasses before final integration.

---

# 13. Definition of done

All six issues are complete only when:

- Room annotations, SQL semantics and inheritance determine mutators.
- Mutation names such as `save`, `persist`, `remove` and `wipe` are detected.
- DAO parameters and supported Kotlin call forms cannot bypass detection.
- Direct barriers structurally dominate each mutation.
- `runWrite` authorizes only mutations inside its own lambda.
- Worker/helper mediation proves every caller and complete guard chain.
- Additional unguarded callers fail.
- Missing Gradle guard components fail the build.
- Direct `Instant.now()` and related APIs fail outside exact clock adapters.
- Guard tests, static suite and `:app:check` pass.
- No unclassified production findings remain.
- No baseline or policy was broadened without exact evidence and review.
- A green CI run is recorded against the exact final SHA.