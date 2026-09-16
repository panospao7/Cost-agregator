# PR-GR-13 — Prove helper and worker mediation

## Agent mission

Build a bounded, exact, conservative interprocedural proof system for policy mutations marked:

```text
helper
workerMediated
```

The analyzer must determine whether every reachable production path to a helper mutation is already inside a proven direct write-barrier context or a proven canonical worker-guard context.

This PR is **shadow-only**. It produces proof/unprovability evidence and the remediation inventory for GR-14. It does not yet make helper/worker proof an active DB authorization requirement.

## Why this PR is necessary

The active policy uses all three barrier modes, including real `helper` and `workerMediated` entries. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/guards/db_ownership_policy.yml)) The current v2 model explicitly defines these modes as metadata, and current source evidence applies no local requirement to them. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/policy_model.py))

The current worker guard is useful as a legacy detector, but it is regex/text based: it looks for a `CoroutineWorker`, a guard method name, and broad line patterns; it is not an exact call graph or scope proof. It also currently describes direct DAO mutations as a coarse source scan. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_worker_boundaries.py))

The acceptance contract requires more: worker DB work must occur only within a reachable canonical `WorkerExecutionGuard` scope; a guard imported or called in an unused helper is not sufficient. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Required predecessors

Do not begin unless:

```text
GR-12 active direct-dominance proof is merged and green
GR-11 structural model is reproducible
GR-10A and GR-10B are merged
GATE-00R evidence exists for exact start SHA
Current DB report is trusted
Blocking diagnostics = 0
Advisory diagnostics = 0
Direct policy mutations are all proven by GR-12
No active GR-08/GR-14 source or policy batch is concurrently changing the same writers
```

Record:

```text
START_SHA
active policy SHA
baseline SHA
source-root manifest SHA
GR-12 proof report SHA
normal DB report SHA
worker inventory/report SHA
```

## Hard stops

Stop and classify the case as unprovable or infrastructure failure if:

- callable identity cannot be resolved exactly;
- a call edge has multiple potential targets;
- virtual/interface/dynamic/reflection dispatch cannot be closed;
- a lambda/callback may escape or run later;
- a worker root cannot be discovered or cross-checked;
- a public/protected/external callable has no complete in-repository caller set;
- recursion/SCC behavior cannot be modeled safely;
- a call edge crosses an unsupported module/source root;
- a policy mutation is reachable from unknown production entry;
- proof requires a simple-name owner or method-only matching;
- proof relies on current legacy worker regex output;
- an attempt is made to mark an unprovable edge advisory.

Unproven is an honest result. It becomes GR-14 work, not a policy exception.

---

# Scope

## Allowed

- interprocedural callable/call-site graph modules;
- exact worker discovery and canonical worker-guard contract;
- shadow report CLI;
- typed mediation evidence models;
- synthetic Kotlin fixtures;
- DB/worker parser and report tests;
- remediation inventory documentation;
- narrowly related docs.

## Forbidden

```text
active DB policy semantics changes
DB baseline changes
production Kotlin changes
worker allowlist expansion
new broad worker exemptions
changing normal DB CLI / ratchet enforcement
changing direct-barrier proof semantics
using reflection/heuristics as a call-edge fallback
claiming full WorkManager correctness beyond DB mediation scope
```

Expected production Kotlin change:

```text
no
```

---

# What GR-13 proves—and does not prove

## In scope

| Mode | Required proof |
|---|---|
| `helper` | Every production path into the helper mutation is proven to carry direct-barrier or canonical worker-guard context. |
| `workerMediated` | Every production path into the mutation originates in an exact Worker root and stays within canonical WorkerExecutionGuard scope. |

## Out of scope

```text
general app call-graph completeness
reflection
dynamic proxy dispatch
full coroutine scheduling semantics
all worker cancellation/result/lease correctness
privacy proof unrelated to the DB mutation
structural raw-SQL/file-operation mediation
arbitrary higher-order function behavior
```

Existing worker/cancellation/privacy guards remain independently responsible for their own rules.

---

# Definitions

## Exact callable node

A callable graph node is keyed by the same full identity family used by v2 policy:

```text
path
ownerFqcn
kind
method
receiver
ordered parameterTypes
```

No simple-name matching.

## Call edge

A call edge is valid only when all are exact:

```text
caller callable key
source span
callee callable key
receiver identity, if applicable
ordered argument/parameter compatibility
dispatch category
synchronous-call contract
```

## Guard context

A guard context is an immutable analysis fact:

```text
NONE
DIRECT_BARRIER
WORKER_GUARD
```

It may enter a callee only across a supported, synchronous, exact call edge.

## Root

A root is a callable entered from outside the bounded project call graph.

Closed root categories:

```text
WORKER_DO_WORK
FRAMEWORK_CALLBACK
PUBLIC_OR_PROTECTED_EXTERNAL
TOP_LEVEL_EXTERNAL
CONSTRUCTOR_EXTERNAL
UNKNOWN_EXTERNAL
```

A helper reachable from an `UNKNOWN_EXTERNAL` root is not proven.

---

# Required models

Create or extend a dedicated package, for example:

```text
scripts/db_guard/mediation_analysis/
```

Use immutable typed objects equivalent to:

```text
CallableNode
CallSite
CallEdge
CallResolution
EntryRoot
GuardContext
MediationPath
MediationProofResult
MediationDiagnostic
WorkerRoot
WorkerGuardScope
```

## Closed call-resolution states

```text
EXACT_SYNCHRONOUS
EXACT_CANONICAL_SCOPE
AMBIGUOUS_TARGET
UNRESOLVED_TARGET
VIRTUAL_DISPATCH
INTERFACE_DISPATCH
FUNCTION_REFERENCE
ESCAPING_LAMBDA
ASYNC_DISPATCH
RECURSIVE_UNSUPPORTED
EXTERNAL_ENTRY
UNSUPPORTED_SYNTAX
```

No generic `OTHER` state.

## Closed proof states

```text
PROVEN_HELPER
PROVEN_WORKER_MEDIATED
COUNTEREXAMPLE_UNGUARDED_CALL_PATH
COUNTEREXAMPLE_NON_WORKER_ROOT
COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE
UNPROVEN_EXTERNAL_ENTRY
UNPROVEN_AMBIGUOUS_CALL
UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
UNPROVEN_RECURSION
UNSUPPORTED_SOURCE
INFRASTRUCTURE_FAILURE
```

---

# Canonical worker contract

## Mandatory discovery record

Create:

```text
docs/ci/db-mediation/GR-13_WORKER_GUARD_CONTRACT.md
```

It must identify, from actual source:

```text
WorkerExecutionGuard receiver FQCN
exact runGuarded/runGuardedWithContext overloads
lambda execution semantics
barrier-check ordering
worker context parameters required by the contract
result bridge behavior relevant to scope
worker root method identity
known Worker base classes and supported aliases
registry/source crosswalk source
Kotlin tests demonstrating the guard contract
```

Do not infer the canonical worker guard from the string `runGuarded`.

## Worker scope requirement

For a `workerMediated` mutation, all must be true:

1. owning worker is discovered exactly;
2. mutation is reachable from the exact `doWork` root;
3. the path enters a canonical WorkerExecutionGuard scope before the mutation;
4. no protected mutation occurs before entering the scope;
5. no protected mutation is reachable after leaving the scope;
6. all intermediate calls are exact synchronous edges;
7. no non-worker root reaches the mutation;
8. worker discovery and worker registry agree.

A worker guard call in dead code, a sibling helper, or a different branch does not satisfy the requirement.

---

# Call graph boundary

## Supported exact edge families

GR-13 may support only these initially:

1. direct member call on exact concrete receiver;
2. unqualified same-owner direct function call;
3. exact top-level function call with unambiguous import/FQCN;
4. exact private/local helper call where declaration is unique;
5. exact canonical direct-barrier scope call from GR-12;
6. exact canonical WorkerExecutionGuard scope call;
7. direct call from a known worker root into a concrete helper.

## Unsupported unless later explicitly added

```text
interface dispatch
open/override dispatch
generic service locator lookup
reflection
delegated property call targets
function references
callbacks stored in fields/collections
coroutine launch/async
Flow collectors
executor dispatch
Handler posting
Rx subscription callbacks
unknown higher-order functions
cross-module source not in declared roots
recursive SCCs
```

A synchronous wrapper may be added only after a separate source contract proves exact invocation behavior and adversarial fixtures prove no callback escape.

---

# Required artifacts

## 1. Mediation inventory

Create:

```text
docs/ci/db-mediation/GR-13_MEDIATION_INVENTORY.yml
```

Every active policy mutation with `helper` or `workerMediated` mode must be represented exactly once.

```yaml
schemaVersion: 1
startSha: <40-char SHA>
activePolicySha256: <sha256>
sourceRootManifestSha256: <sha256>
directProofReportSha256: <sha256>
status: OPEN

entries:
  - mutationKey: <exact mutation key>
    callableKey: <exact callable key>
    barrierMode: helper | workerMediated
    expectedRootKinds:
      - DIRECT_BARRIER
      - WORKER_GUARD
    currentProofStatus: PENDING
    incomingCallSiteCount: <integer>
    sourceVisibility: private | internal | public | protected | unknown
    owner: "@panospao7"
    linkedIssue: <work item>
```

This is a review ledger, not authorization.

## 2. Generated mediation report

Create a read-only CLI, for example:

```text
scripts/ci/inspect_db_mediation_proof.py
```

Required output fields:

```text
schemaVersion
reportOnly: true
targetSha
activePolicySha256
sourceRootManifestSha256
summary
proof results by exact mutation
call-edge counts by status
worker-root inventory
unproven inventory
bounded counterexample paths
diagnostics
```

Exit mapping:

| State | Exit |
|---|---:|
| All helper/worker entries proven | `0` |
| Valid analysis, but unproven/counterexample entries exist | `1` |
| Infrastructure or unsupported source uncertainty | `2` |

The report must never alter normal DB CLI exit status in GR-13.

## 3. GR-14 remediation manifest seed

Generate:

```text
docs/ci/db-mediation/GR-13_UNPROVEN_WRITERS.yml
```

Each unproven item must include:

```text
mutationKey
callableKey
proofStatus
root/call-edge reason
bounded path evidence
risk classification
recommended remediation family
owner
linked issue
```

No expiry, baseline, or “accepted debt” state is allowed.

---

# Proof algorithm

## Step 1 — Build exact callable inventory

Use GR-11 declaration and source-span infrastructure.

For every policy mutation in helper/worker modes:

1. resolve exact callable;
2. resolve exact mutation site;
3. record callable visibility;
4. record source body status;
5. fail closed if any item is ambiguous.

## Step 2 — Build reverse call-site index

Enumerate declared production Kotlin source through GR-10B shared scope.

For every potential call site:

1. parse caller’s structural region;
2. resolve only closed supported edge forms;
3. record exact source span;
4. classify unsupported forms explicitly;
5. index edges by callee exact callable key;
6. preserve deterministic order.

Do not build a name-only project graph.

## Step 3 — Determine roots

A callable has an external-root risk if it is:

```text
public/protected
top-level exposed
constructor/initializer externally reachable
framework callback
unresolved through a supported caller inventory
called from unsupported dispatch
```

Private helpers can still be unproven if source has zero callers or a callback escape.

## Step 4 — Propagate guard contexts

For every root-to-target path:

1. start context as `NONE`, except exact canonical scopes;
2. GR-12-proven direct scope creates `DIRECT_BARRIER`;
3. canonical worker scope creates `WORKER_GUARD`;
4. supported synchronous calls propagate current context;
5. scope exit removes scope-only context;
6. unknown/async/escaping edge stops proof and emits unproven result;
7. a target mutation is proven only if every path reaching it has required context.

Use fixed-point analysis only over acyclic supported graph regions. Detect recursion/SCCs explicitly; do not silently iterate until a convenient answer appears.

## Step 5 — Mode-specific criteria

### `helper`

Pass only when:

```text
all inbound production paths are resolved
every inbound path has DIRECT_BARRIER or WORKER_GUARD context
no EXTERNAL_ENTRY reaches target
no unsupported edge can reach target
```

### `workerMediated`

Pass only when:

```text
all inbound paths begin at discovered Worker doWork roots
every path enters canonical worker guard before target
target remains inside scope through all intermediate calls
no direct/non-worker/external root reaches target
```

---

# Mandatory test matrix

## Exact call binding

1. same method name in two owners;
2. overload difference by type/count/nullability;
3. nested owner;
4. same simple class name in packages;
5. member vs extension function;
6. top-level function;
7. private same-owner helper;
8. exact imported function;
9. wrong receiver FQCN;
10. interface dispatch;
11. overridden open method;
12. ambiguous imported symbol;
13. unresolved parameter type;
14. generic callable ambiguity.

## Helper proof

1. private helper called only after direct barrier;
2. helper called from two guarded paths;
3. helper called from guarded and unguarded caller;
4. helper called from public unknown root;
5. helper with zero call sites;
6. helper called through function reference;
7. helper called in coroutine launch;
8. helper called inside escaping callback;
9. recursive helper pair;
10. barrier in caller’s unrelated branch;
11. direct scope exits before helper call;
12. helper has direct local barrier and is eligible for GR-14 conversion to direct.

## Worker proof

1. direct CoroutineWorker subclass;
2. fully qualified worker base;
3. alias import;
4. intermediate abstract worker base;
5. nested worker;
6. DB mutation inside canonical worker guard lambda;
7. mutation before guard;
8. mutation after guard scope;
9. guard in dead/uninvoked helper;
10. worker invokes helper inside guard;
11. worker invokes helper before guard;
12. non-worker invokes same helper;
13. worker guard receiver wrong type;
14. worker callback/launch escapes scope;
15. registry/source discovery mismatch;
16. duplicate worker FQCN;
17. cancellation/result paths remain outside scope of this proof but do not break parser.

## Protocol and determinism

1. report contains no raw source;
2. output is stable across two runs;
3. unknown proof code rejected;
4. malformed policy/root report exits `2`;
5. unproven result exits `1` only in shadow CLI;
6. unsupported source exits `2`;
7. no normal DB CLI semantics change;
8. no ratchet semantics change.

---

# Implementation sequence

## Step 1 — Freeze evidence

Run GATE-00R and GR-12 direct proof against the exact start SHA.

Require:

```text
DB trusted true
direct proofs all proven
no advisory/blocking diagnostics
```

## Step 2 — Generate complete helper/worker inventory

Do not begin call-graph coding until every helper/worker policy mutation is represented.

Compare:

```text
policy helper/worker entry count
exact mutation-observation count
inventory row count
```

All must match.

## Step 3 — Add call-edge fixtures first

Build the negative corpus before supporting real source patterns.

No real-tree “green” result can substitute for adversarial fixtures.

## Step 4 — Implement exact graph and root classification

Use GR-11 callable source spans and GR-10B declared roots.

Do not import or adapt the legacy worker regex script as authority.

## Step 5 — Implement canonical worker scope recognition

Source-verify exact WorkerExecutionGuard implementation and scope lambda behavior.

Add Kotlin runtime tests only for the guard’s actual contract; do not test an imagined one.

## Step 6 — Run shadow analysis twice

Classify every policy mutation:

```text
PROVEN
COUNTEREXAMPLE
UNPROVEN
UNSUPPORTED
INFRASTRUCTURE_FAILURE
```

Any unexpected report drift is a stop condition.

## Step 7 — Review and split remediation work

For every non-proven entry, create exactly one GR-14 remediation disposition.

Do not activate helper/worker enforcement in this PR.

---

# Definition of done

GR-13 is complete only when:

- every helper/worker policy mutation has a deterministic proof result;
- all call edges used for proof are exact and source-backed;
- every worker root is discovered and cross-checked;
- no text-only worker guard result is treated as proof;
- every unproven path is captured in the GR-14 manifest;
- no helper/worker proof is active in normal DB enforcement yet;
- policy, baseline, production Kotlin, ratchet, and normal DB results are unchanged;
- two shadow reports match semantically.

## Required completion report

```text
PR: GR-13
START SHA:
END SHA:
GR-12 PROOF REPORT SHA:
ACTIVE POLICY SHA:
BASELINE SHA:
SOURCE ROOT MANIFEST SHA:

HELPER MUTATIONS:
WORKER-MEDIATED MUTATIONS:
PROVEN HELPER:
PROVEN WORKER:
COUNTEREXAMPLES:
UNPROVEN EXTERNAL:
UNPROVEN AMBIGUOUS:
UNPROVEN ASYNC/CALLBACK:
UNPROVEN RECURSIVE:
UNSUPPORTED:
INFRASTRUCTURE FAILURES:

EXACT CALL EDGES:
UNRESOLVED CALL EDGES:
WORKER ROOTS DISCOVERED:
WORKER REGISTRY/SOURCE MISMATCHES:

GR-14 REMEDIATION ROWS:
SHADOW REPORT REPRODUCIBLE: yes/no
NORMAL DB CLI UNCHANGED: yes/no
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no

NEXT PR: GR-14a…n
```