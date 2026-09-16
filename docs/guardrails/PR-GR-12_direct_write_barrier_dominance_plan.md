# PR-GR-12 — Prove direct write-barrier dominance

## Agent mission

Replace the current **lexical “barrier appears earlier in the callable”** rule with a conservative control-flow proof:

> Every reachable path from a callable entry to a direct DB mutation must cross a canonical write-barrier check, or the mutation must be lexically inside a verified canonical guarded scope.

This PR activates enforcement for policy entries whose barrier requirement is currently `direct`.

It does **not** prove `helper` or `workerMediated` entries. Those remain outside active mediation enforcement until GR-13 through GR-15.

## Why this PR is necessary

At the current anchor SHA, D4 treats a direct-mode mutation as guarded when the masked text before the call contains `writeBarrier.checkWritesAllowed(...)` or `writeBarrier.runWrite(...)`. That can accept a barrier in a different branch, an unrelated lambda, after a prior mutation, or in dead code. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/scanner.py))

The v2 source-evidence verifier has the same limitation: for `direct`, it requires local syntax before each mutation but explicitly makes no dominance or reachability claim. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/policy_v2_evidence.py))

The acceptance contract requires the stronger property: the barrier must dominate the mutation on the same control-flow path; merely existing elsewhere in the file is a hard failure. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

## Required predecessors

Do not begin unless all are true:

```text
GATE-00R completed for the exact start SHA
GR-10A canonical command ownership merged
GR-10B shared source-scope authority merged
GR-10D documentation/evidence truth sync merged
GR-11 structural model merged and reproducible
Active DB report is trusted
Blocking DB diagnostics = 0
Advisory DB diagnostics = 0, unless an explicit approved blocker exists
No unresolved GR-08 finding batch is changing DB policy concurrently
```

Record:

```text
START_SHA
START_TREE_SHA
active policy SHA
DB baseline SHA
source-root manifest SHA
GR-11 structural-shadow report SHA
normal DB report SHA
```

## Hard stop conditions

Stop; do not weaken policy or downgrade severity if:

- GR-11 cannot model a direct-mode callable deterministically;
- a canonical barrier receiver cannot be resolved exactly;
- the proposed proof relies on a simple class name, token search, filename, wildcard, or sibling overload;
- a `runWrite`-like lambda cannot be proven to be the canonical synchronous guard scope;
- a direct writer has a real counterexample path;
- policy source evidence and D4 would use different proof engines;
- direct CLI, ratchet, suite, and Gradle use different policy/root inputs;
- another agent owns Gradle.

A known invalid direct path is a source-design problem, not a reason to change `direct` into `helper`.

---

# Scope

## Allowed

- GR-11 structural-analysis modules;
- a shared direct-barrier proof module;
- exact barrier-receiver/type resolution;
- DB scanner and v2 source-evidence integration;
- report/protocol/rule-catalog updates;
- synthetic Kotlin fixtures and Python tests;
- direct-barrier proof inventory and evidence documents;
- narrowly related documentation.

## Forbidden

```text
app/src/main changes
DB baseline changes
--update-baseline
new DB debt baseline entries
ownership-policy expansion
structural-exception changes
helper/worker proof activation
call-graph construction beyond direct local proof
broad allowlists or exemptions
fallback to lexical “barrier-before-text” authorization
```

Expected production Kotlin change:

```text
no
```

---

# Exact proof contract

## Terms

| Term | Meaning |
|---|---|
| Mutation site | Exact D4-resolved DAO mutation keyed by full policy mutation identity. |
| Canonical barrier check | Exact resolved invocation of the approved barrier API that, on normal return, establishes write permission. |
| Canonical guarded scope | Exact resolved invocation of approved barrier API whose synchronous lambda body runs only after the barrier check. |
| Dominates | Every modeled executable path from callable `ENTRY` to mutation node passes through the barrier node/scope entry. |
| Counterexample path | A concrete modeled path from entry to mutation that lacks required barrier evidence. |
| Unsupported | The analyzer cannot model the syntax/control flow safely; this is exit `2`, never a clean result. |

## Direct proof outcomes

| Outcome | Meaning | Normal DB result |
|---|---|---|
| `PROVEN` | Exact barrier dominates mutation, or mutation is in canonical guarded scope. | No barrier finding |
| `COUNTEREXAMPLE` | CFG is trusted and contains an unguarded path. | Finding / exit `1` |
| `UNSUPPORTED` | Required source/control-flow/canonical-call semantics cannot be proven. | Diagnostic / exit `2` |
| `INFRASTRUCTURE_FAILURE` | Policy, roots, source, report, or internal graph invariant failed. | Diagnostic / exit `2` |

Never convert `UNSUPPORTED` into `COUNTEREXAMPLE` merely to avoid exit `2`, and never convert `COUNTEREXAMPLE` into advisory.

---

# Canonical barrier API contract

## Mandatory discovery before implementation

Inspect the real current production barrier API and record its exact facts in:

```text
docs/ci/db-structural/GR-12_CANONICAL_BARRIER_API.md
```

The record must include:

```text
barrier receiver FQCN
implementation FQCN(s), if interface-backed
exact check method name/signature
exact guarded-scope method name/signature
lambda parameter/return contract
whether invocation is synchronous
whether lambda execution is exactly once or at least once
normal-return semantics
Kotlin test location proving runtime behavior
source-evidence location proving implementation identity
```

Do not guess an FQCN from the variable name `writeBarrier`.

## Required typed contract

Create one code-owned, closed contract, conceptually:

```text
CanonicalBarrierContract
  receiverFqcn
  directCheckCallable
  guardedScopeCallable
  contractVersion
```

Requirements:

1. Receiver type must resolve exactly from lexical/source type evidence.
2. Bare `writeBarrier` spelling is never sufficient.
3. Same-name methods on another receiver are not barriers.
4. An overload not explicitly represented by the contract is not a barrier.
5. Contract source verification must prove the guarded scope checks write permission before invoking its lambda.
6. Contract tests must prove normal return only occurs after the check passes.
7. Contract changes require a dedicated reviewed diff; no YAML wildcard may redefine it.

Use a typed code contract rather than a permissive user-editable allowlist.

---

# Required shared API

GR-11 must already expose immutable callable CFG and mutation observations. Add one pure shared proof API; both source evidence and D4 consume it.

Conceptually:

```text
prove_direct_barrier(
  callableStructuralInput,
  cfg,
  mutationSites,
  canonicalBarrierContract,
  typeResolution,
) -> DirectBarrierProofResult
```

Minimum result fields:

```text
callableKey
mutationKey
status
proofVersion
barrierSite
mutationSite
counterexampleNodeKinds
counterexampleLineSequence
diagnostics
```

Safety requirements:

- repository-relative path only;
- no raw Kotlin source;
- no SQL;
- no absolute paths;
- no source excerpts longer than bounded identifiers/line numbers;
- deterministic ordering by callable key then mutation identity.

---

# Proof semantics

## A. Direct check

For a canonical direct check:

```text
writeBarrier.checkWritesAllowed(...)
```

the proof engine may mark the normal successor as “barrier established.”

It must not mark these paths guarded:

- exceptional edge from the barrier call;
- branch that bypasses the call;
- lambda/local function that contains the call but is not proven invoked;
- sibling scope;
- code before the call;
- a later loop iteration if the current path can reach mutation before the check.

## B. Canonical guarded scope

For a canonical guarded scope:

```text
writeBarrier.runWrite { ... mutation ... }
```

the mutation is eligible only if all are true:

1. receiver resolves to canonical barrier type;
2. invoked overload exactly matches contract;
3. mutation is inside that exact lambda source span;
4. lambda is not passed onward, stored, returned, or invoked through unknown callback semantics;
5. the contract verifies guard check precedes lambda invocation;
6. no modeled control-flow edge reaches the mutation from outside the guarded lambda scope.

## C. CFG dominance

For each direct mutation `m`, calculate domination over GR-11’s modeled CFG.

A direct check succeeds only when:

```text
barrier node dominates m
AND
the barrier node lies on the normal-return path
AND
no unknown/unsupported edge can reach m without the barrier
```

A guarded scope succeeds only when the scope entry dominates the mutation and the mutation is inside its exact canonical lambda body.

## D. Required treatment of difficult forms

| Form | Required handling |
|---|---|
| Barrier after mutation | `COUNTEREXAMPLE` |
| Barrier only in one branch; mutation reachable after join | `COUNTEREXAMPLE` |
| Barrier and mutation both inside same guarded branch | May be `PROVEN` |
| Barrier in `else`, mutation in `then` | `COUNTEREXAMPLE` |
| Barrier in loop that may execute zero times; mutation after loop | `COUNTEREXAMPLE` |
| Barrier before mutation in same loop body | May be `PROVEN` if CFG proves all entries cross it |
| Barrier in `catch` only | `COUNTEREXAMPLE` unless every path to mutation reaches it |
| Barrier in `finally` after possible mutation | `COUNTEREXAMPLE` |
| Unresolved exception flow reaching mutation | `UNSUPPORTED` |
| Barrier in unknown callback/lambda | `UNSUPPORTED` |
| Barrier in uncalled local function | `COUNTEREXAMPLE` or `UNSUPPORTED`; never proven |
| `launch`, `async`, callback, escaping lambda | `UNSUPPORTED` unless future contract explicitly supports it |
| Comments/strings containing barrier API names | No marker |

---

# Required artifacts

## 1. Direct-mode inventory

Create:

```text
docs/ci/db-structural/GR-12_DIRECT_BARRIER_INVENTORY.yml
```

Each active-policy mutation with `barrierMode: direct` must appear exactly once.

```yaml
schemaVersion: 1
startSha: <40-char SHA>
activePolicySha256: <sha256>
sourceRootManifestSha256: <sha256>
structuralShadowSha256: <sha256>
canonicalBarrierContractVersion: 1

entries:
  - mutationKey: <canonical exact mutation key>
    callableKey: <canonical exact callable key>
    path: <repository-relative path>
    expectedBarrierForm: DIRECT_CHECK | GUARDED_SCOPE
    structuralStatus: SUPPORTED
    proofStatus: PENDING | PROVEN | COUNTEREXAMPLE | UNSUPPORTED
    owner: "@panospao7"
    linkedIssue: <work item>
    evidence:
      - <test/report reference>
```

This inventory is not policy, a baseline, or an allowlist.

## 2. Direct-proof shadow report

Create a read-only CLI, for example:

```text
scripts/ci/inspect_direct_write_barrier_proof.py
```

Output:

```text
build/guard-debug/gr12/direct-proof-before.json
build/guard-debug/gr12/direct-proof-before.sha256
build/guard-debug/gr12/direct-proof-after.json
```

Exit behavior:

| State | Exit |
|---|---:|
| All direct entries proven | `0` |
| Valid analysis with one or more counterexamples | `1` |
| Unsupported/infrastructure uncertainty | `2` |

It must never modify policy, source, or baseline.

## 3. Proof-to-D4 bridge

Create one bridge/index keyed by exact mutation identity. It must be the single proof source for:

```text
policy source evidence
D4 scanner
normal DB CLI report
ratchet child report
static suite
Gradle DB task
```

No second regex proof is allowed after activation.

---

# Implementation sequence

## Step 1 — Freeze exact pre-state

Capture:

```text
GATE-00R evidence ID
GR-11 shadow report
normal DB report
active policy hash
baseline hash
source-root manifest hash
```

Run direct DB scan twice. Require equal trusted semantic output.

## Step 2 — Inventory every direct entry

Generate the inventory from active policy plus GR-11 mutation observations.

Hard failures:

- policy direct entry has no exact mutation observation;
- observed direct mutation has no policy entry;
- one mutation maps to multiple inconsistent direct barrier forms;
- direct callable absent from GR-11 corpus;
- corpus status is unsupported.

Do not start dominance implementation until the inventory is complete.

## Step 3 — Add failing tests before replacing lexical logic

Add fixtures covering all required adversarial placements.

Required initial red tests:

1. barrier earlier in unrelated `if`;
2. barrier after mutation;
3. barrier in one branch with mutation after join;
4. barrier in unrelated lambda;
5. barrier in uncalled local function;
6. barrier in `finally` after mutation;
7. `runWrite` on wrong receiver;
8. correct API name in comment/string;
9. same method name on fake barrier type;
10. unknown lambda/coroutine escape;
11. exact direct check before mutation;
12. exact canonical scope around mutation.

## Step 4 — Implement canonical receiver resolution

Reuse exact declaration/type-resolution infrastructure where possible.

Required failure behavior:

```text
wrong FQCN → not a barrier
ambiguous receiver type → exit 2
unresolved receiver type in direct writer → exit 2
wrong overload → not a barrier
unknown callback/scope contract → exit 2
```

No simple-name match such as `WriteBarrier`.

## Step 5 — Implement proof algorithm over GR-11 CFG

Implement, test, and validate:

1. reachability from entry;
2. normal and exceptional successor handling;
3. standard dominance calculation;
4. guarded-scope lexical containment;
5. counterexample reconstruction;
6. deterministic proof result ordering;
7. graph/model invariant checking.

A counterexample witness must show node kinds and bounded line numbers, for example:

```text
ENTRY → BRANCH(false) → MUTATION
```

Do not emit raw source.

## Step 6 — Run shadow comparison before activation

For every direct-mode mutation, compare:

```text
legacy lexical result
GR-12 dominance result
```

Classify every difference:

| Legacy / new | Meaning | Required action |
|---|---|---|
| pass / proven | expected | retain |
| pass / counterexample | real false pass in old guard | source remediation required |
| pass / unsupported | old guard made unproved claim | analyzer/design remediation required |
| fail / proven | investigate; old scanner false positive | repair only with regression proof |
| fail / counterexample | expected violation | source remediation required |
| fail / unsupported | analyzer uncertainty | repair analyzer or split work |

Do not activate if any direct policy mutation remains `COUNTEREXAMPLE` or `UNSUPPORTED`.

### Important sequencing exception

If GR-12 exposes real direct-path counterexamples, create narrowly scoped **GR-14 direct-remediation slices** early, using the frozen proof inventory. Do not merge GR-12 with a knowingly red active gate.

## Step 7 — Replace active lexical enforcement

Only after the full direct inventory is `PROVEN`:

1. remove the D4 substring authorization path;
2. remove/replace the source-evidence “line before mutation” check;
3. use the shared proof index;
4. retain stable finding/report behavior;
5. emit a new exact rule for proven unsafe paths.

Recommended rule/diagnostic split:

```text
DB_MISSING_WRITE_BARRIER
  no canonical direct barrier path exists

DB_WRITE_BARRIER_NOT_DOMINATING
  canonical barrier exists but a modeled unguarded path reaches mutation

DB_DIRECT_BARRIER_PROOF_UNSUPPORTED
  source structure/call contract cannot be safely modeled

DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED
  barrier-looking receiver cannot resolve exactly

DB_DIRECT_BARRIER_CONTRACT_INVALID
  canonical barrier API itself no longer matches verified contract
```

The first two are findings/exit `1`; the latter three are blocking diagnostics/exit `2`.

## Step 8 — Prove all control planes consume the same result

Run:

```text
direct DB CLI
ratchet child command
static suite
Gradle verifyDbAccessBoundaries
GATE-00R twice
```

Required:

```text
same policy hash
same source-root hash
same proof-engine version
same direct-barrier outcomes
same exit semantics
```

---

# Mandatory test matrix

## Dominance correctness

1. sequential check then mutation;
2. mutation then check;
3. check only in true branch; mutation after join;
4. check and mutation both true branch;
5. check in else; mutation true branch;
6. nested branches;
7. loop zero iterations;
8. barrier/mutation in same loop body;
9. `return` before mutation;
10. `throw`/`catch` path to mutation;
11. `finally` cases;
12. early return from guarded branch;
13. multiple mutations with different results;
14. multiple direct checks.

## Canonical scope correctness

1. exact canonical `runWrite` containing mutation;
2. mutation outside canonical lambda;
3. nested `runWrite`;
4. wrong receiver FQCN;
5. wrong overload;
6. fake same-name method;
7. lambda passed to unknown wrapper;
8. lambda escapes/stored/returned;
9. callback invokes mutation later;
10. comment/string lookalikes.

## Safety / protocol

1. no raw source in proof report;
2. deterministic output twice;
3. malformed structural model exits `2`;
4. unknown diagnostic code rejected;
5. source-root failure exits `2`;
6. direct policy row missing proof result exits `2`;
7. new direct mutation under exact owner is still discovered;
8. policy row cannot authorize sibling overload;
9. v2 source evidence and D4 agree;
10. ratchet maps proof diagnostic to `2`, proof finding to `1`.

---

# Required validation

Run the exact test files introduced by GR-11 and this PR, plus:

```text
DB policy model/loader tests
DB v2 source-evidence tests
D4 scanner tests
declaration scanner tests
guard findings/report tests
ratchet v1/v2 tests
direct-proof CLI tests
registry/static-suite tests
```

Then run, sequentially where applicable:

```text
direct proof shadow CLI twice
normal DB CLI twice
static suite
Gradle :app:verifyDbAccessBoundaries
GATE-00R twice
```

---

# Preservation checks

```text
No baseline change
No DB ownership-policy change
No structural-policy change
No source-root-manifest change
No production Kotlin change
No time-policy change
```

If source code must change to repair a counterexample, stop this PR’s activation path and create a GR-14 remediation batch.

---

# Definition of done

GR-12 is complete only when:

- every `direct` policy mutation has one exact proof result;
- all direct mutations are `PROVEN`;
- no direct mutation depends on lexical text-before-call matching;
- every barrier receiver and guarded scope is exact-contract resolved;
- real counterexamples become findings;
- unsupported proof states fail closed with exit `2`;
- v2 source evidence and D4 consume the same proof engine;
- direct CLI, ratchet, suite, and Gradle agree;
- the active DB baseline is unchanged;
- two GATE-00R runs reproduce semantic results.

## Required completion report

```text
PR: GR-12
START SHA:
END SHA:
GR-11 SHADOW REPORT SHA:
ACTIVE POLICY SHA BEFORE/AFTER:
BASELINE SHA BEFORE/AFTER:

DIRECT POLICY MUTATION COUNT:
DIRECT PROVEN:
DIRECT COUNTEREXAMPLES:
DIRECT UNSUPPORTED:
CANONICAL BARRIER CONTRACT VERSION:

LEXICAL-TO-CFG DIFFERENCES:
SOURCE REMEDIATION REQUIRED:
ACTIVE ENFORCEMENT SWITCHED: yes/no

DIRECT DB CLI:
RATCHET:
STATIC SUITE DB RESULT:
GRADLE DB RESULT:
GATE-00R REPRODUCIBLE: yes/no

PRODUCTION KOTLIN CHANGED: no
POLICY CHANGED: no
BASELINE CHANGED: no
NEXT PR: GR-13
```