# Cost Aggregator Guardrail Deep Review

**Reviewed HEAD:** `6f0e46c846e71d3506be53c8b1a8b4abf86a6cab`  
**Review date:** August 7, 2026  
**Commits examined:** `c226c67`, `530ac96`, `6596903`, `220d1fd`, `6f0e46c`

## 1. Executive verdict

The recent work significantly improves DB guard precision:

- canonical path matching;
- exact class, method, DAO and operation authorization;
- interface-type DAO resolution;
- comment/string masking;
- multiline-call handling;
- source-to-policy reverse validation;
- controlled infrastructure errors;
- structural exception validation;
- extensive positive and adversarial fixtures.

The final commit alone adds an exact 99-entry ownership policy, a 62-entry structural policy contract, a structural manifest and extensive Kotlin/Python tests. ([github.com](https://github.com/panospao7/Cost-agregator/commit/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab))

However, the implementation is **not yet strong enough to claim that unauthorized DB writes are impossible to merge**.

### Current assessment

| Area | Assessment |
|---|---|
| Policy metadata validation | Strong |
| Exact matching of detected calls | Strong |
| Kotlin source parsing | Improved, but still heuristic |
| Mutation discovery completeness | Weak/unsafe |
| Barrier verification | Partial and sometimes documentary only |
| Worker/helper mediation verification | Weak |
| Gradle fail-closed behavior | Defective |
| CI architecture | Reasonable, but not fully release-proof |
| Maintainability | High complexity and duplication |
| Verified green evidence for HEAD | Not established in this review |

**Overall:** **YELLOW-RED**. Good architectural direction, but remaining false-negative paths affect the guard’s fundamental security claim.

---

# 2. What the recent commits improved

## `530ac96` — DAO interface identity resolution

This correctly addresses aliases such as:

```kotlin
private val groupDao: ExpenseGroupDao
```

The scanner maps them to the Room-style identity `expenseGroupDao` rather than trusting the variable name. It also adds negative tests proving unrelated DAOs remain rejected. ([github.com](https://github.com/panospao7/Cost-agregator/commit/530ac96))

This is a meaningful precision improvement.

## `6596903` and `220d1fd` — parser fail-open hardening

These commits substantially expanded:

- balanced declaration parsing;
- expression-body handling;
- comment/string masking;
- multiline DAO calls;
- class/method scope attribution;
- unsupported-body failure modes;
- structural operation parsing;
- barrier receiver validation.

The scanner now deliberately reports unsupported scopes and unbounded bodies rather than silently skipping them. ([github.com](https://github.com/panospao7/Cost-agregator/commit/6596903))

This work fixes real earlier weaknesses.

## `6f0e46c` — exact policy contract

The latest commit replaces broad ownership authorization with exact tuples:

```text
path + class + method + DAO + exact operation
```

Wildcard methods and generic `operation: write` are rejected. Policy entries are also checked against source evidence so stale or invented entries fail as configuration errors. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

That is the correct conceptual model.

---

# 3. Critical findings

## CRITICAL-1 — Mutation discovery remains naming-based

The scanner does not determine mutators from Room annotations or SQL semantics. It identifies calls only when the method name starts with one of a manually maintained set of verbs such as:

```text
insert, update, delete, mark, set, claim, archive, purge...
```

plus two special names. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

Therefore, these Room mutators could escape detection:

```kotlin
@Insert
suspend fun save(expense: Expense)

@Delete
suspend fun remove(expense: Expense)

@Query("UPDATE expenses SET status = :status")
suspend fun applyStatus(id: Long, status: String)

@Query("DELETE FROM expenses")
suspend fun wipe()

@Upsert
suspend fun persist(expense: Expense)
```

Because policy source validation uses the same extractor, it cannot independently detect this blind spot. Both sides agree on the same incomplete result.

### Required fix

Build a canonical DAO method inventory by parsing DAO source declarations:

- `@Insert`
- `@Update`
- `@Delete`
- `@Upsert`
- mutating `@Query`
- `@RawQuery` where writes are permitted
- inherited mutators from base DAO interfaces

Then use the discovered exact method names to scan call sites.

The mutator grammar may remain only as a secondary consistency check:

```text
Every Room mutator must be in the generated inventory.
Every detected call to an inventory mutator must be authorized.
```

Add negative fixtures using `save`, `remove`, `persist`, `applyStatus`, and `wipe`.

---

## CRITICAL-2 — DAO parameters and alternative call forms can bypass detection

DAO resolution currently recognizes:

- `val`/`var` properties with explicit DAO types;
- locals assigned from `database.someDao()`;
- receiver names ending in `Dao`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

It does not reliably cover ordinary method parameters:

```kotlin
private suspend fun write(dao: ExpenseDao) {
    dao.insert(expense)
}
```

Because `dao` does not end in `Dao` and the parameter has no `val` or `var`, the receiver may remain unresolved.

Other likely bypass forms include:

```kotlin
dao?.insert(expense)
dao!!.insert(expense)

with(dao) {
    insert(expense)
}

dao.run {
    insert(expense)
}

private val dao
    get() = database.expenseDao()
```

### Required fix

At minimum, add fixtures for every form above.

The durable solution is a Kotlin parser or compiler-based analysis rather than further regex expansion.

Recommended options:

1. Kotlin Analysis API/compiler plugin.
2. Detekt custom rule with PSI.
3. KSP-generated inventory plus PSI call-site scanner.
4. Tree-sitter Kotlin as an intermediate improvement.

Do not describe the current scanner as complete until these forms are handled or explicitly prohibited by another guard.

---

## CRITICAL-3 — Mediated barrier claims are not proven

Many policy entries use:

```yaml
barrier_required: false
```

because the barrier is allegedly supplied by:

- a helper method;
- a public wrapper;
- `WorkerExecutionGuard`;
- another lifecycle path.

The policy contains many helper-mediated transaction writers and several `barrier_via: WorkerExecutionGuard` entries. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/config/guards/db_ownership_policy.yml))

But the scanner does not prove the full call chain.

For `barrier_via`, it mainly rejects contradictory direct-barrier evidence. It does not prove that every invocation of the writer occurs within `WorkerExecutionGuard`, nor that the named mediation mechanism actually dominates the write. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

Fields such as `private` and `delegate_of` are type-validated but are not semantically checked against Kotlin visibility or call relationships. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

This permits a dangerous regression:

```kotlin
suspend fun unsafePath() {
    createExpenseMutation(...) // approved private writer, no barrier here
}
```

If the writer itself remains policy-approved with `barrier_required: false`, DB ownership can pass even though a new caller bypasses the guarded wrapper.

### Required fix

Introduce explicit mediation contracts:

```yaml
barrier:
  mode: direct | helper | worker_guard | transaction_owner
  symbol: checkWritesAllowed
  owner_method: createExpense
```

Then verify:

- helper declaration exists;
- helper calls the real barrier;
- helper call occurs before the writer invocation;
- private writer is actually `private`;
- every call site of the private writer originates from an approved guarded owner;
- worker methods are invoked inside `runGuardedWithContext`;
- callbacks passed into the guard cannot escape and execute later.

Until this exists, helper/worker-mediated entries should be classified as **architectural debt**, not fully proven safe paths.

---

## CRITICAL-4 — Direct barrier checking is lexical, not control-flow-safe

The scanner accepts a barrier when a matching call appears on an earlier source line in the method. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

That does not prove the call dominates the mutation.

For example:

```kotlin
if (debugMode) {
    writeBarrier.checkWritesAllowed()
}

expenseDao.insert(expense)
```

or:

```kotlin
runCatching {
    writeBarrier.checkWritesAllowed()
}

expenseDao.insert(expense)
```

or:

```kotlin
writeBarrier.runWrite {
    auditDao.insert(audit)
}

expenseDao.insert(expense) // outside runWrite
```

All can potentially satisfy lexical “barrier before mutation” evidence even though the guarded call does not protect the later write.

### Required fix

Short-term rule:

- require `checkWritesAllowed()` as an unconditional top-level statement before the first write;
- reject barriers inside nested blocks, lambdas, conditions, catches or local functions;
- for `runWrite`, require the mutation to be structurally inside the lambda body.

Long-term: use PSI/control-flow analysis.

---

## CRITICAL-5 — Gradle DB verification still fails open if the ratchet script is absent

The current Gradle task does:

```kotlin
if (!ratchetScript.exists()) {
    logger.warn(...)
    return@doLast
}
```

That allows `:app:check` to pass without executing DB enforcement if the script is renamed, omitted or incorrectly checked out. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/app/build.gradle.kts))

This directly violates the non-negotiable fail-closed requirement.

### Required fix

Replace all missing dependency cases with `GradleException`:

```kotlin
if (!ratchetScript.isFile) {
    throw GradleException("Required DB guard ratchet is missing")
}
if (!guardScript.isFile) {
    throw GradleException("Required DB guard script is missing")
}
if (!baseline.isFile) {
    throw GradleException("Required DB guard baseline is missing")
}
```

Also validate Python availability and distinguish:

- exit 1: architectural violations;
- exit 2: infrastructure/configuration failure;
- unexpected exit: infrastructure failure.

Add a Gradle TestKit regression test proving every missing input fails.

---

## CRITICAL-6 — The direct-time Gradle guard suppresses the calls it is supposed to reject

The guard detects expressions such as:

```kotlin
Instant.now()
LocalDate.now()
ZonedDateTime.now()
```

but then skips a matching line whenever it contains the substring `now()`. Every normal direct `.now()` call contains that substring. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/app/build.gradle.kts))

Therefore, calls such as `Instant.now()` can be silently accepted.

### Required fix

Remove:

```kotlin
stripped.contains("now()")
```

Do not implement exemptions through line substrings.

Use exact path/symbol allowlisting for:

- `SystemTimeProvider`;
- platform adapters;
- explicitly approved clock implementations.

Add fixtures proving all listed direct APIs fail in normal production files.

---

# 4. Major findings

## MAJOR-1 — Overloads are authorized by method-name union

The source-evidence validator deliberately unions mutation pairs across all overloads with the same method name. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

This means the policy cannot distinguish:

```kotlin
save(expense: Expense)
save(receipt: Receipt)
```

Once the method-name union authorizes both DAO pairs, either overload may later acquire the other pair without requiring a new policy dimension.

### Fix

Policy identity should include at least:

```text
class + method + parameter-type signature
```

Prefer compiler symbol identity.

---

## MAJOR-2 — Every file ending in `Dao.kt` is skipped

The scanner skips a source file solely when its filename ends with `Dao.kt`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

A helper class or object placed in such a file could contain direct mutations and escape scanning.

### Fix

Do not skip the whole file. Exclude only declarations proven to be Room DAO declarations, while continuing to scan:

- companion objects;
- helper classes;
- top-level functions;
- non-DAO declarations in the same file.

---

## MAJOR-3 — Policy claims and baseline semantics contradict each other

The ownership policy header says a direct mutation must match policy, structural exception, **or be recorded in the ratchet baseline**. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/config/guards/db_ownership_policy.yml))

The documentation later says baselines never authorize writes. The commit documentation also describes several unresolved writer categories that remain outside the canonical policy. ([github.com](https://github.com/panospao7/Cost-agregator/commit/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab))

These statements must be reconciled.

Correct rule:

> A baseline permits temporary CI no-growth only. It never makes a writer legal or architecture-compliant.

Reports should distinguish:

```text
AUTHORIZED
KNOWN_UNAUTHORIZED_DEBT
NEW_UNAUTHORIZED_VIOLATION
INFRASTRUCTURE_ERROR
```

Do not print “pass” for baselined unauthorized debt without clearly reporting the debt count.

---

## MAJOR-4 — The structural manifest is self-referential and over-fitted

The implementation now maintains the same structural contract in several forms:

1. structural exceptions YAML;
2. expected-methods manifest;
3. immutable tuple sets embedded in Python;
4. a 2,141-line Kotlin fixture test;
5. pinned entry counts.

The Kotlin test explicitly expects 99 ownership entries and 62 structural entries. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

This protects against accidental edits, but the files are normally updated together in the same commit. Therefore, the duplication is not an independent trust boundary.

It also means a legitimate migration or restore operation can require synchronized edits across several large generated-looking structures.

### Fix

Keep:

- one canonical authorization policy;
- behavioral positive/negative fixtures;
- source-evidence verification;
- CODEOWNERS review.

Generate any mirror manifests from the canonical policy and check generated-file cleanliness.

Remove hardcoded counts unless they represent a separately approved release policy.

---

## MAJOR-5 — Legacy Gradle guards remain separate policy systems

`build.gradle.kts` still contains a hardcoded lifecycle-bypass guard with its own allowlist model and a narrow pattern covering only direct `expenseDao.insert/update/delete`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/app/build.gradle.kts))

This conflicts with the documentation’s claim that DB authorization has exactly two canonical policy files.

The supplemental guard is weaker because it can miss:

- aliases;
- other DAOs;
- custom mutator names;
- multiline calls;
- exact class/method ownership.

### Fix

Either:

1. remove the legacy guard after proving the canonical DB scanner supersedes it; or
2. redefine it as a narrow independent invariant with a clearly different rule ID.

Do not maintain overlapping authorization allowlists.

---

## MAJOR-6 — Migration proof is not a pull-request gate

The workflow’s migration job runs only for main/master pushes or manual dispatch, not ordinary pull requests. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/.github/workflows/ci.yml))

Therefore, a PR can pass required checks without executing the real migration proof.

### Fix

Run the representative migration matrix on pull requests affecting:

```text
entities
DAOs
AppDatabase
DatabaseMigrations
Room schemas
migration tests
backup/restore schema logic
```

Run the full historical matrix on release/manual/main.

---

## MAJOR-7 — No verified green evidence was available for this exact HEAD

The commit exists and the source changes are inspectable, but this review could not establish a completed green GitHub Actions run for `6f0e46c`.

Until exact-SHA evidence exists, the status is:

```text
implementation reviewed
execution proof pending
```

Do not update completion documents to “DONE” solely from local fixture counts.

---

# 5. Maintainability concerns

The DB scanner is now approximately 3,464 lines and its Python test file approximately 4,565 lines. The latest commit adds another 2,141-line Kotlin fixture test plus large policy manifests. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/6f0e46c846e71d3506be53c8b1a8b4abf86a6cab/scripts/verify_db_access_boundaries.py))

This produces several risks:

- difficult code review;
- behavior duplicated between Python and Kotlin parsers;
- high fixture-update burden;
- accidental test implementation mirroring production implementation;
- longer unit compilation;
- reluctance to make legitimate policy changes;
- increasingly fragile regex grammar.

The scanner has reached the point where adding more regex state machines is likely less economical than adopting Kotlin PSI.

---

# 6. Exact next work plan

## Phase 1 — Prove current behavior before changing code

Run:

```bash
git checkout 6f0e46c846e71d3506be53c8b1a8b4abf86a6cab

python3 -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation

python3 scripts/ci/run_static_guard_suite.py

./gradlew :app:testDebugUnitTest \
  --tests "*DbGuardPolicyFixtureTest" \
  --no-daemon --stacktrace

./gradlew :app:checkDirectTimeCalls \
  --no-daemon --stacktrace

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace

./gradlew :app:check \
  --no-daemon --stacktrace
```

Capture:

- exit code;
- duration;
- current DB debt count;
- infrastructure error count;
- exact baseline comparison;
- first failing task.

## Phase 2 — Add adversarial tests before fixes

Required new fixtures:

### Mutation names

```text
save
persist
remove
wipe
applyStatus
store
create
put
```

### DAO receiver forms

```text
ordinary DAO method parameter
nullable DAO safe-call
non-null assertion receiver
with(dao)
dao.run
property getter returning DAO
delegated/lazy DAO property
inherited BaseDao mutator
```

### Barrier forms

```text
barrier inside false branch
barrier inside runCatching
barrier inside unused lambda
barrier after mutation
runWrite block followed by unguarded mutation
helper writer called by unguarded caller
worker writer called outside WorkerExecutionGuard
```

### Parsing forms

```text
overloaded method signatures
extension functions
backtick method names
multiple declarations in one file
non-DAO writer inside a file ending Dao.kt
```

Every fixture must fail before its corresponding fix.

## Phase 3 — Immediate P0 fixes

1. Make missing Gradle guard dependencies fatal.
2. Fix `checkDirectTimeCalls`.
3. Stop skipping complete `*Dao.kt` files.
4. Parse ordinary DAO parameters.
5. Verify `barrier_via` rather than treating it as documentation.
6. Reject non-dominating lexical barrier evidence.

## Phase 4 — Replace naming-based mutation detection

Generate the exact mutation method inventory from DAO definitions and Room annotations.

Acceptance requirement:

> Adding any new Room mutator—regardless of its name—must cause a call from an unauthorized class to fail CI.

## Phase 5 — Reduce policy duplication

Choose one canonical policy representation.

Generate:

- structural manifest;
- documentation tables;
- fixture snapshots.

Do not manually maintain all copies.

## Phase 6 — CI proof

Require a green run for the same SHA containing:

```text
Validate Workflow
Static Guards
Unit Tests
Lint & Check
Release Check
Migration Proof
```

Record exact SHA, run ID, test counts, guard debt and migration versions.

---

# 7. Recommended issue priority

| Priority | Issue |
|---|---|
| P0 | Derive mutator inventory from Room declarations |
| P0 | Prove helper/worker barrier mediation |
| P0 | Make Gradle DB guard fail closed |
| P0 | Fix direct-time guard’s `now()` suppression |
| P1 | Add control-flow-safe barrier verification |
| P1 | Handle DAO parameters/safe calls/scope functions |
| P1 | Stop whole-file `Dao.kt` exclusion |
| P1 | Distinguish overload signatures |
| P1 | Make migration proof a PR gate |
| P2 | Replace regex parser with Kotlin PSI |
| P2 | Generate manifests/tests from canonical policy |
| P2 | Remove or redefine legacy overlapping Gradle guards |

---

# 8. Final conclusion

The last commits are **real progress**, not cosmetic work. They substantially improve exact matching, parser failure handling and policy integrity.

But the implementation currently proves:

> “Every mutation recognized by the scanner’s naming and receiver grammar has an exact policy entry.”

It does **not yet prove**:

> “Every actual Room database mutation in production code is discovered, properly owned and barrier-protected.”

Closing that gap—especially mutation inventory generation and real barrier mediation verification—is the next architectural objective. Do not add more ownership entries until those two foundations are fixed.