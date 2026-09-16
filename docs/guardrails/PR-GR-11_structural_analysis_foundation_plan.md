# PR-GR-11 — Structural-analysis foundation

## Agent mission

Build the conservative, deterministic, intraprocedural structural-analysis foundation required for later proof that:

```text
a direct write barrier dominates a DB mutation
```

This PR creates the model, source spans, control-flow graph representation, mutation/barrier observation seam, test corpus, shadow report, and fail-closed unsupported-syntax behavior.

It does **not** activate dominance enforcement. It does **not** prove helper mediation or worker-root reachability. Those belong to GR-12 and GR-13.

## Why this PR is necessary

The current D4 scanner detects a `direct` barrier through a text search in source preceding a DAO call. That detects nearby syntax but cannot prove every path to the mutation crossed the barrier. A barrier in another branch, after the mutation, in an unrelated lambda, or in dead code can satisfy a text-before-call check. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/scanner.py))

The v2 policy model has `direct`, `helper`, and `workerMediated` modes, but they are metadata; v2 source evidence explicitly says it makes no dominance, reachability, helper, or worker-mediation claim. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/policy_model.py))

The final acceptance contract requires a write barrier to dominate the mutation on the same control-flow path and explicitly rejects a barrier merely existing elsewhere in the file. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Required ordering

Do not start until all are true:

```text
GATE-00R exact-SHA evidence exists
GR-10A canonical execution ownership is complete
GR-10B shared production source scope is complete
GR-10D documentation truth sync is complete
GR-08 current DB finding batches are complete, or no trusted DB findings exist
GR-10C has either closed advisory debt or recorded an explicit blocker
```

Required starting DB state:

```text
direct DB CLI trusted: true
blocking DB diagnostics: 0
advisory diagnostics: 0, unless an explicit approved blocker says otherwise
current findings: 0, unless GR-08 has an open exact batch
active v2 policy/evidence/source roots: valid
baseline: byte-identical and valid
```

Why: GR-11 must not mix structural-model failures with unresolved parser, source-root, ownership, baseline, or control-plane failures.

---

# Non-goals

GR-11 must not:

- change production Kotlin;
- change active ownership policy;
- change structural exception policy;
- change `barrierMode` meanings;
- update any baseline;
- make helper or worker mediation pass;
- make direct barriers enforceable;
- add a call graph;
- infer function-call behavior;
- follow dynamic dispatch;
- assume a lambda executes immediately;
- assume `try`, `catch`, `finally`, coroutine, callback, or worker behavior;
- introduce a Kotlin compiler plugin/PSI dependency unless a separately approved architecture decision requires it;
- alter normal DB CLI, ratchet, static suite, or Gradle DB exit behavior.

The foundation is **shadow-only**. Existing DB outcomes must remain semantically unchanged.

---

# Scope

## Allowed

- new pure structural-analysis modules under `scripts/db_guard/`;
- narrow extraction of existing mutation-observation data from D4;
- scanner/declaration parser refactor only where behavior parity is proven;
- synthetic Kotlin fixtures;
- structural model/report tests;
- explicit shadow CLI;
- architecture decision record and corpus/debt manifests;
- narrowly related closed diagnostic catalog/protocol tests.

## Forbidden

```text
app/src/main changes
config/baselines changes
config/guards/db_ownership_policy.yml changes
structural-exception changes
ratchet changes
Gradle/workflow command changes
time-policy changes
policy rows used to hide unsupported structure
```

---

# Core safety rule

The structural analyzer must be conservative:

```text
Supported and modeled → report exact structural facts.
Unsupported or ambiguous → report controlled structural uncertainty.
Never infer safety from missing information.
Never convert uncertainty into “barrier present.”
```

GR-11 may report:

```text
SUPPORTED
UNSUPPORTED_CONSERVATIVELY
INFRASTRUCTURE_FAILURE
```

It must never report:

```text
PROVEN_SAFE
DOMINATED
WORKER_MEDIATED_PROVEN
HELPER_PROVEN
```

Those conclusions are reserved for later PRs.

---

# Required deliverables

## 1. Structural-analysis ADR

Create:

```text
docs/architecture/DB_STRUCTURAL_ANALYSIS_MODEL.md
```

It must define:

- purpose and non-goals;
- intraprocedural-only boundary;
- exact source-span model;
- graph node/edge semantics;
- supported Kotlin subset;
- unsupported constructs;
- lambda/callback treatment;
- exception-flow treatment;
- barrier observation vocabulary;
- mutation-observation contract;
- report schema;
- fail-closed behavior;
- GR-12/13/14/15 handoff.

It must state plainly:

> GR-11 does not prove a write barrier. It creates the evidence structure that GR-12 will use to prove or reject direct barrier dominance.

## 2. Structural corpus inventory

Create:

```text
docs/ci/db-structural/GR-11_CORPUS.yml
```

This is a review/corpus artifact, not policy and not authorization.

Required fields:

```yaml
schemaVersion: 1
startSha: <40-char SHA>
activePolicySha256: <sha256>
sourceRootManifestSha256: <sha256>
dbEvidenceSha256: <sha256>

entries:
  - corpusId: direct-sequential-001
    path: app/src/main/java/...
    callableKey: <exact v2 callable key>
    barrierMode: direct|helper|workerMediated
    mutationKeys:
      - <exact mutation key>
    syntaxFamilies:
      - BRACED_FUNCTION
      - IF_ELSE
      - TRY_FINALLY
      - NESTED_LAMBDA
    expectedModelStatus: SUPPORTED|UNSUPPORTED_CONSERVATIVELY
    reason: <bounded explanation>
    owner: "@panospao7"
```

Requirements:

- Every active-policy callable with a DB mutation is classified.
- Every observed `barrierMode` is represented.
- Every production syntax family encountered is represented.
- Every `direct` callable has a classification; none may silently disappear.
- Unsupported cases identify a closed reason code and future owner PR.
- This file must never authorize, suppress, or baseline a mutation.

## 3. Shared resolved-mutation observation seam

Create or extract a typed immutable model, for example:

```text
scripts/db_guard/mutation_observation.py
```

Conceptual fields:

```text
path
callableKey
sourceStart
sourceEnd
line
column
daoAccessor
daoFqcn
operation
mutationKind
sourceIdentity
```

Rules:

- Observations come from the same exact D4 DAO-resolution path.
- No second regex-only mutation detector is allowed.
- No policy authorization state belongs in the observation.
- No raw source text belongs in report output.
- Source offsets are internal; reports render bounded path/line/column only.
- Existing scanner findings must remain byte/semantic equivalent after extraction.

## 4. Pure structural model

Create a package such as:

```text
scripts/db_guard/structural_analysis/
  __init__.py
  model.py
  tokenizer.py
  parser.py
  cfg.py
  barrier_markers.py
  shadow_report.py
  diagnostics.py
```

Exact filenames may differ, but responsibilities must remain separate.

### Required immutable model concepts

```text
SourceSpan
CallableStructuralInput
MutationSite
BarrierMarker
StructuralNode
StructuralEdge
ControlFlowGraph
StructuralDiagnostic
StructuralAnalysisResult
```

### Node kinds

Use a closed vocabulary equivalent to:

```text
ENTRY
EXIT_NORMAL
EXIT_EXCEPTIONAL
STATEMENT
MUTATION
BARRIER_CALL
BARRIER_SCOPE
BRANCH
WHEN
LOOP_HEADER
LOOP_BODY
TRY
CATCH
FINALLY
RETURN
THROW
BREAK
CONTINUE
LAMBDA
LOCAL_FUNCTION
UNKNOWN_CONSTRUCT
```

### Edge kinds

Use a closed vocabulary equivalent to:

```text
NORMAL
TRUE_BRANCH
FALSE_BRANCH
WHEN_BRANCH
LOOP_BODY
LOOP_EXIT
RETURN_EXIT
THROW_EXIT
EXCEPTION
FINALLY
LAMBDA_DEFERRED
UNKNOWN
```

The graph must contain exactly one `ENTRY` node and at least one exit node for a supported callable.

## 5. Structural shadow CLI

Create:

```text
scripts/ci/inspect_db_structural_model.py
scripts/ci/test_inspect_db_structural_model.py
```

Suggested invocation:

```bash
python3 scripts/ci/inspect_db_structural_model.py \
  --root . \
  --policy config/guards/db_ownership_policy.yml \
  --output build/guard-debug/gr11/structural-shadow.json
```

Rules:

- explicit, read-only, shadow-only;
- does not modify policy/baseline/source;
- does not run as normal DB ratchet child;
- does not alter normal DB CLI result;
- uses declared production roots;
- uses active v2 policy only to enumerate relevant callable/mutation identities;
- writes deterministic safe JSON;
- returns:
  - `0`: all selected corpus callables structurally modeled;
  - `1`: valid analysis with one or more unsupported/ambiguous callables;
  - `2`: infrastructure, malformed input, invalid policy/root, or report failure.

---

# Supported syntax strategy

Do not pretend to parse all Kotlin.

## Initial required support

The initial model must support, where present in corpus:

```text
braced functions
sequential statements
nested blocks
if / else
when with explicit branches
while / for / do-while
try / catch / finally
return
throw
break
continue
property getters/setters with braced bodies
simple direct barrier calls
simple DAO mutation sites supplied by D4
```

## Initial conservative handling

The following may be represented but must not yield future proof until explicitly supported:

```text
expression bodies
local functions
anonymous objects
coroutines
launch / async
callbacks
escaping lambdas
unknown higher-order functions
reflection
dynamic dispatch
exception type filtering
inline/reified behavior
non-local returns
labelled returns
complex Elvis/short-circuit control flow
```

For each unsupported form, emit one controlled diagnostic/result. Do not silently flatten it into sequential statements.

## Lambda rule

A lambda is not assumed to run synchronously.

Only a later explicitly reviewed contract may treat an exact known wrapper as synchronous. In GR-11:

```text
writeBarrier.runWrite { ... }
```

may be recognized as a `BARRIER_SCOPE` syntactic candidate, but no proof result is emitted.

Any unrecognized lambda/callback/coroutine boundary must preserve an `UNKNOWN` or `LAMBDA_DEFERRED` edge.

---

# Barrier-marker contract

## Current syntax inventory first

Before implementing marker recognition, inspect actual production forms and record them in the corpus.

At minimum, review existing forms currently recognized by D4:

```text
writeBarrier.checkWritesAllowed(...)
writeBarrier.runWrite(...)
```

The current scanner checks those patterns lexically before a mutation; GR-11 must centralize recognition without broadening accepted syntax accidentally. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/scanner.py))

## Marker types

Use a closed marker vocabulary:

```text
DIRECT_CHECK
DIRECT_SCOPE
WORKER_GUARD_CANDIDATE
UNKNOWN_BARRIER_LIKE_CALL
```

Rules:

- Marker extraction is syntax observation, not proof.
- A same-name method on an unknown receiver is not automatically a barrier.
- A barrier-like token in comments/strings is not a marker.
- A barrier after a mutation is recorded after it, not normalized before it.
- A barrier in a sibling lambda/local function is not attached to the mutation’s path.
- Unknown barrier-like syntax is explicit uncertainty, not a successful marker.

---

# Structural diagnostic contract

Register all codes in the current closed catalog and protocol tests. Reuse existing naming patterns if they conflict.

Minimum conceptual codes:

```text
DB_STRUCTURAL_MODEL_CALLABLE_UNRESOLVED
DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED
DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED
DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED
DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE
DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED
DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED
DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED
DB_STRUCTURAL_MODEL_GRAPH_INVARIANT_FAILED
DB_STRUCTURAL_MODEL_REPORT_INVALID
```

Rules:

- no raw Kotlin source;
- no raw exception message;
- no absolute path;
- bounded path, callable identity, line, code, and syntax family only;
- deterministic ordering;
- an internal assertion/error is `2`, not silently converted into unsupported source syntax.

---

# Implementation sequence

## Step 1 — Freeze exact input

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr11/db-before.json
```

Require:

```text
trusted: true
blocking diagnostics: 0
advisory diagnostics: 0, unless explicitly approved blocker
```

Record hashes for:

```text
active policy
source-root manifest
structural policy
structural manifest
DB v2 baseline
DB before report
```

## Step 2 — Build the real-source corpus before parser work

Generate a read-only inventory of:

```text
all active v2 policy callables
all mutation keys
all barrier modes
all source body shapes
all direct barrier syntax forms
all worker/helper candidate forms
```

Review every `direct` writer. Group by syntax family, not merely class/package.

Hard stop if the corpus cannot identify the exact callable/mutation source range using current D4 evidence.

## Step 3 — Write model and protocol tests first

Before implementation, add tests for:

1. immutable node/edge/result models;
2. duplicate node IDs rejected;
3. invalid source spans rejected;
4. graph with no entry/exits rejected;
5. deterministic ordering;
6. no raw source in output;
7. closed diagnostic vocabulary;
8. unsupported syntax produces controlled result, not a guessed graph;
9. infrastructure failure differs from source unsupported status.

## Step 4 — Extract mutation observation with parity proof

Extract only the resolved DAO mutation observation necessary for structural input.

Run existing D4 test suite before and after extraction.

Required parity:

```text
same finding fingerprints
same diagnostic codes
same trusted state
same policy matching
same report schema
same active DB result
```

Any semantic DB scanner change blocks GR-11 and must be split into a dedicated repair PR.

## Step 5 — Implement tokenizer and statement boundaries

Reuse:

```text
mask_kotlin_source
declaration ranges
exact callable spans
declared production root resolution
```

The existing declaration scanner already records callable body spans and distinguishes braced/expression/property forms. Reuse that information; do not rediscover callable boundaries through a separate file-wide regex. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/declaration_scanner.py))

Requirements:

- comments/strings masked;
- balanced delimiter tracking;
- nested braces/parens/brackets handled;
- source spans remain half-open and deterministic;
- unsupported grammar stops the affected callable conservatively;
- no partial graph is labeled supported.

## Step 6 — Build conservative CFG

For each supported callable:

1. create `ENTRY`;
2. parse direct statements/control constructs;
3. create nodes with source spans;
4. create all syntactically possible branch/loop paths;
5. create normal and exceptional exits where modeled;
6. create `UNKNOWN` edges/nodes instead of assuming behavior;
7. attach resolved mutation sites by exact span;
8. attach barrier markers by exact span;
9. validate graph invariants.

Do not calculate dominance in GR-11.

## Step 7 — Add shadow reporting

The shadow report must include:

```json
{
  "schemaVersion": 1,
  "reportOnly": true,
  "targetSha": "<if supplied by caller>",
  "summary": {
    "callableCount": 0,
    "supportedCount": 0,
    "unsupportedCount": 0,
    "infrastructureFailureCount": 0
  },
  "callables": [
    {
      "callableKey": "...",
      "status": "SUPPORTED",
      "nodeCount": 0,
      "edgeCount": 0,
      "mutationCount": 0,
      "barrierMarkerCount": 0,
      "diagnostics": []
    }
  ]
}
```

No graph source text, raw code, SQL, exception trace, absolute path, or unbounded body excerpt may appear.

## Step 8 — Add real-tree corpus checks

The shadow tool must verify:

```text
all policy callables are accounted for
all direct-mode callables are accounted for
every result is supported, unsupported, or infrastructure-failed
no callable is silently omitted
corpus and report agree on identity/counts
```

Unsupported direct callables create a tracked structural-debt row, for example:

```text
docs/ci/db-structural/GR-11_STRUCTURAL_DEBT.yml
```

Each row requires:

```text
callable key
mutation key(s)
controlled unsupported code
syntax family
why no safe model exists yet
masking risk
owner
linked issue
next intended PR
```

This debt file is not a baseline, suppression, or authorization mechanism.

## Step 9 — Keep active DB enforcement unchanged

Run before/after semantic comparison of normal DB CLI:

```text
active report trusted state unchanged
finding set unchanged
diagnostic set unchanged
ratchet outcome unchanged
baseline hash unchanged
policy hash unchanged
```

GR-11 output remains opt-in shadow evidence only.

## Step 10 — Run twice and compare

Run structural shadow analysis twice on the exact same SHA.

Require equal:

```text
callable keys
statuses
node/edge counts
mutation/barrier counts
diagnostic codes/context
corpus coverage
semantic report digest
```

Any difference is a determinism defect.

---

# Required test matrix

## Graph shape

1. sequential statements;
2. one mutation;
3. multiple mutations;
4. nested blocks;
5. `if` without `else`;
6. `if/else`;
7. `when` with `else`;
8. loop with body/exit;
9. `break`;
10. `continue`;
11. `return`;
12. `throw`;
13. `try/catch/finally`;
14. nested try/finally;
15. malformed delimiters;
16. comments/string lookalikes.

## Barrier-placement adversarial fixtures

1. barrier immediately before mutation;
2. barrier after mutation;
3. barrier only on one `if` branch;
4. barrier in `else`, mutation after branch;
5. barrier in a loop that may not execute;
6. barrier in `catch` after mutation-capable try body;
7. barrier in `finally`;
8. barrier in unrelated lambda;
9. barrier in uncalled local function;
10. barrier-like unknown receiver;
11. `runWrite` lambda containing mutation;
12. mutation outside `runWrite` lambda;
13. worker guard candidate in worker body;
14. direct DAO mutation in callback/coroutine;
15. comments/strings containing barrier text.

GR-11 does not assert pass/fail dominance for these fixtures. It asserts that the model preserves the distinction required for GR-12 to decide later.

## Mutation-observation parity

1. exact DAO mutation becomes one structural mutation site;
2. wrong DAO identity does not fabricate a site;
3. unresolved DAO remains current scanner diagnostic behavior;
4. sibling overload mutation does not leak;
5. property/accessor mutation uses exact callable identity;
6. structural operation is not misclassified as DAO mutation;
7. source comments/strings do not create mutation sites.

## Shadow-report safety

1. deterministic JSON twice;
2. schema version required;
3. raw source omitted;
4. absolute paths omitted;
5. unknown diagnostic code rejected;
6. duplicate callable key rejected;
7. missing corpus item fails;
8. untrusted input fails with exit `2`;
9. unsupported source yields exit `1`, not `0` or `2`;
10. internal graph invariant failure yields exit `2`.

---

# Required validation

```bash
python3 -m pytest \
  scripts/db_guard/structural_analysis/test_model.py \
  scripts/db_guard/structural_analysis/test_tokenizer.py \
  scripts/db_guard/structural_analysis/test_cfg.py \
  scripts/db_guard/structural_analysis/test_barrier_markers.py \
  scripts/ci/test_inspect_db_structural_model.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_guard_findings.py \
  -v --tb=short
```

```bash
python3 scripts/ci/inspect_db_structural_model.py \
  --root . \
  --policy config/guards/db_ownership_policy.yml \
  --output build/guard-debug/gr11/structural-shadow-run-01.json
```

Repeat into `structural-shadow-run-02.json`, then compare semantic reports.

```bash
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr11/db-after.json
```

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/gr11/static-suite
```

Expected: active suite/DB results remain semantically unchanged by GR-11.

Run Gradle only if no other agent owns it:

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

---

# Preservation checks

```bash
git diff --exit-code -- \
  app/src/main \
  config/baselines \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/production_source_roots.yml \
  scripts/ci/guard_ratchet.py \
  app/build.gradle.kts \
  .github/workflows
```

Expected:

```text
production Kotlin changed: no
active DB policy changed: no
DB baseline changed: no
ratchet behavior changed: no
normal DB CLI semantic result changed: no
```

---

# Definition of done

GR-11 is complete only when:

- one conservative structural model exists;
- D4 mutation observations are shared, not reimplemented;
- every active policy callable/mutation is accounted for;
- every direct barrier-mode callable is classified;
- supported graphs have deterministic nodes, edges, spans, markers, and mutation sites;
- unsupported syntax produces controlled debt, not guessed safety;
- no active enforcement claim is strengthened;
- no helper/worker proof claim is made;
- normal DB CLI/ratchet/suite/Gradle semantics remain unchanged;
- shadow reports reproduce across two exact runs;
- GR-12 has a clear, typed input contract for dominance analysis.

## Required completion report

```text
PR: GR-11
START SHA:
END SHA:
GATE-00R EVIDENCE ID:
GR-10A SHA:
GR-10B SHA:
GR-10D SHA:

ACTIVE POLICY SHA:
SOURCE ROOT MANIFEST SHA:
DB BASELINE SHA:

POLICY CALLABLES ACCOUNTED FOR:
DIRECT-MODE CALLABLES ACCOUNTED FOR:
HELPER-MODE CALLABLES ACCOUNTED FOR:
WORKER-MEDIATED CALLABLES ACCOUNTED FOR:

STRUCTURAL RESULTS:
  SUPPORTED:
  UNSUPPORTED_CONSERVATIVELY:
  INFRASTRUCTURE_FAILURE:

STRUCTURAL DEBT ROWS:
NEW CLOSED DIAGNOSTIC CODES:
MUTATION-OBSERVATION PARITY: yes/no
SHADOW REPORT REPRODUCIBLE: yes/no

ACTIVE DB CLI BEFORE/AFTER EQUIVALENT: yes/no
RATCHET BEFORE/AFTER EQUIVALENT: yes/no
STATIC SUITE BEFORE/AFTER EQUIVALENT: yes/no
GRADLE DB TASK BEFORE/AFTER EQUIVALENT: yes/no

PRODUCTION KOTLIN CHANGED: no
ACTIVE DB POLICY CHANGED: no
DB BASELINE CHANGED: no
NORMAL DB ENFORCEMENT CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR: GR-12
```