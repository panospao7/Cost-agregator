# PR-GR-14 — Refactor paths that cannot be proven

## Agent mission

Refactor only production writer paths that GR-12 or GR-13 has demonstrated to be genuinely unprovable or unguarded.

This is a **series of small remediation PRs**, not one bulk architecture rewrite:

```text
GR-14a
GR-14b
...
GR-14n
```

Each batch must make selected exact mutation paths provable without weakening the detector, broadening policy, adding baseline debt, or asserting a caller relationship that source cannot prove.

## Why this PR is necessary

GR-13 will identify cases where helper/worker metadata cannot be proven due to escaping callbacks, public/external entry points, ambiguous dispatch, direct worker writes outside guard scope, or unsupported asynchronous boundaries. The right answer is to simplify/refactor the writer path—not mark unprovable code safe.

The current architecture documentation states that workers must enter `WorkerExecutionGuard` before protected work and must not bypass it. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/architecture/LEGAL_PATHS.md)) GR-14 turns that architecture into source shapes that GR-12/GR-13 can verify.

---

# Entry gate

Do not create a GR-14 batch until it has one exact source input:

```text
GR-12 direct proof report, if direct remediation is needed
GR-13 mediation report
GR-13 unproven-writers manifest
GATE-00R evidence for actual start SHA
```

The selected row must be:

```text
COUNTEREXAMPLE_UNGUARDED_CALL_PATH
COUNTEREXAMPLE_NON_WORKER_ROOT
COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE
UNPROVEN_EXTERNAL_ENTRY
UNPROVEN_AMBIGUOUS_CALL
UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
UNPROVEN_RECURSION
UNSUPPORTED_SOURCE
```

Do not refactor speculative paths not present in the frozen proof report.

## Batch cap

One GR-14 batch may change at most:

```text
5 mediation graphs
or
10 unique policy mutation keys
or
one coherent worker/coordinator ownership path
```

Choose the smallest limit reached.

If a refactor touches a broad lifecycle architecture, split it before implementation.

---

# Scope

## Allowed

- production Kotlin directly required to eliminate selected unprovable paths;
- exact active-policy updates caused by moved/renamed legal mutation identities;
- focused Kotlin/unit/worker tests;
- GR-12/GR-13 fixtures and reports;
- legal-path documentation for changed architecture;
- narrow dependency injection changes needed to make ownership explicit.

## Forbidden

```text
DB baseline changes
--update-baseline
broad policy additions
new permanent/temporary debt
changing helper to workerMediated by text only
changing direct to helper by text only
new generic writer callback interfaces
new source-scope exclusions
new worker whole-class exemptions
unrelated production refactors
scanner weakening
```

---

# Required remediation principle

Every selected mutation must finish in one of these analyzable forms.

## Pattern A — Local direct proof

Move the mutation into an exact canonical direct barrier path:

```text
legal owner callable
  → exact canonical write barrier check/scope
  → direct DAO mutation
```

Use this when the write truly belongs in the owning callable and no mediated layering is needed.

Result:

```text
policy entry may become direct
GR-12 proves it locally
GR-13 no longer needs helper/worker proof for that mutation
```

## Pattern B — Private, synchronous helper

Use a helper only when all are true:

```text
helper is private/local or otherwise has fully closed callers
every call site is exact and synchronous
every caller enters with proven direct/worker context
helper does not store/pass DB-writing lambda
helper does not expose DAO or generic writer capability
```

Result:

```text
GR-13 helper proof succeeds
```

## Pattern C — Canonical worker scope

For worker-owned work:

```text
Worker doWork root
  → WorkerExecutionGuard canonical scope
  → exact concrete coordinator/helper calls
  → DAO mutation
```

Requirements:

- no DB mutation before entering scope;
- no DB mutation after scope exit;
- no detached coroutine/callback performs protected work;
- helper calls remain exact/synchronous;
- result/cancellation behavior remains correct.

## Pattern D — Eliminate indirect writer callback

Replace a callback shape such as:

```text
helper { dao.insert(...) }
```

or generic writer interface with one of:

```text
typed command/data return
specific coordinator method
private direct helper
canonical guarded scope owned by legal writer
```

Do not pass DAO mutation capability through `() -> Unit`, generic repository callbacks, maps, or service locators.

## Pattern E — Remove dead/unreachable writer

Only if source and caller inventory prove it is dead:

1. remove the dead mutation code;
2. remove its exact policy entry through reviewed tooling;
3. prove no remaining caller or mutation observation exists;
4. update tests/docs as needed.

Do not leave historical policy debt just to preserve an entry count.

---

# Required remediation manifest

For each batch create:

```text
docs/ci/db-mediation/GR-14a.yml
```

Minimum schema:

```yaml
schemaVersion: 1
batchId: GR-14a
startSha: <40-char SHA>
gateEvidenceSha256: <sha256>
directProofReportSha256: <sha256 or null>
mediationReportSha256: <sha256>
activePolicySha256: <sha256>
baselineSha256: <sha256>

selectedMutationKeys:
  - <exact mutation key>

entries:
  - mutationKey: <exact>
    callableKey: <exact>
    priorProofStatus: <exact GR-12/13 status>
    rootCause: EXTERNAL_ENTRY | ESCAPING_CALLBACK | AMBIGUOUS_DISPATCH |
               WORKER_SCOPE_ESCAPE | RECURSION | UNSUPPORTED_STRUCTURE |
               DIRECT_COUNTEREXAMPLE
    selectedPattern: A | B | C | D | E
    currentLegalOwner: <FQCN/callable>
    targetLegalOwner: <FQCN/callable>
    behaviorPreservation:
      transaction: <specific>
      cancellation: <specific>
      retry: <specific>
      lifecycle: <specific>
      workerResult: <specific or N/A>
      privacy: <specific or N/A>
    policyImpact: NONE | EXACT_IDENTITY_MOVE | HELPER_TO_DIRECT | REMOVE_DEAD_ENTRY
    tests: []
    owner: "@panospao7"
    linkedIssue: <work item>
```

The manifest must say:

> This batch changes source architecture to satisfy proof requirements. It is not a baseline, exception, or authorization document.

---

# Mandatory review questions

For every selected writer, answer before coding:

1. What exact DB mutation key is affected?
2. What exact unproven/counterexample path did GR-12/GR-13 report?
3. Why is the current path not safely analyzable?
4. What legal owner should execute the mutation?
5. Which refactor pattern is selected and why?
6. Does the refactor preserve transaction atomicity?
7. Does it preserve cancellation propagation?
8. Does it preserve retry/result semantics for workers?
9. Does it preserve restore/maintenance/write-barrier ordering?
10. Does it introduce new DAO injection or direct write in UI/service layers?
11. Does it change any policy identity or mode?
12. Which new direct/mediation proof result must appear after the change?

A vague answer such as “makes analyzer happy” is insufficient.

---

# Policy handling rules

## Default

```text
Policy changes are not expected.
```

## Permitted exact changes

A policy update is permitted only when source refactoring genuinely changes an exact legal mutation identity.

Examples:

```text
mutation moved to another legal callable
callable renamed with same legal ownership
dead mutation removed
helper converted to direct canonical barrier scope
```

Requirements:

1. use existing exact v2 tooling;
2. regenerate/promote only through reviewed mechanism;
3. run exact source evidence;
4. prove no unselected authorization was added;
5. compare active policy mutation-key sets before/after;
6. record every added/removed/changed key in manifest.

## Prohibited policy changes

```text
helper → workerMediated without source proof
workerMediated → helper without source proof
direct → helper to avoid GR-12
new policy row for a forbidden UI/service writer
wildcard/sibling/overload authorization
reason-only justification without source closure
```

---

# Implementation sequence

## Step 1 — Freeze one proof-driven batch

Run:

```text
GATE-00R
GR-12 direct proof, if relevant
GR-13 mediation proof
normal DB CLI
```

Require reports to be reproducible twice.

Extract selected keys from the generated unproven manifest. Do not manually select a vaguely similar writer.

## Step 2 — Add behavior characterization tests first

Before source changes, add or identify tests that preserve existing behavior:

| Writer type | Required behavior coverage |
|---|---|
| Coordinator/repository | transaction, failure, rollback, idempotency |
| Worker | guard scope, retry/result bridge, cancellation |
| Restore/backup | maintenance barrier, journal/rollback behavior |
| Privacy/audit | sanitized payload and privacy capability behavior |
| Callback-heavy writer | callback result/order/error behavior |
| UI-triggered operation | ViewModel/coordinator boundary remains intact |

If no behavior test exists, add it before moving the mutation.

## Step 3 — Refactor one root cause at a time

### External/public helper

Preferred order:

1. make helper private if no valid external API requires it;
2. move mutation into a specific legal owner;
3. return data/commands rather than accept a write callback;
4. put direct mutation in canonical local barrier scope.

Never solve this by adding a manual list of “approved callers.”

### Escaping callback/lambda

Preferred order:

1. remove mutation from callback;
2. return a typed result;
3. make legal owner decide and perform mutation;
4. ensure any scope lambda is canonical and synchronous.

### Ambiguous/interface dispatch

Preferred order:

1. make the writer concrete/final where architecture permits;
2. move mutation behind one exact legal coordinator method;
3. split read interface from write implementation;
4. avoid generic `Writer`, `Repository<T>`, or service-locator write dispatch.

Do not add call-graph special cases for one ambiguous class merely to preserve indirection.

### Direct worker write outside scope

Preferred order:

1. enter WorkerExecutionGuard at `doWork`;
2. move all protected DB work inside its exact lambda;
3. call only concrete/private helpers inside scope;
4. move post-scope DB work into the guarded lambda or eliminate it;
5. preserve `toWorkerResult` and cancellation semantics.

### Recursive helper chain

Preferred order:

1. break recursion around DB mutation;
2. move write to a non-recursive leaf;
3. pass pure values, not callbacks/writer objects;
4. avoid teaching GR-13 to guess recursive safety.

## Step 4 — Update exact policy only if source requires it

If the mutation key changes:

1. create a reviewed exact seed;
2. run v2 loader/evidence;
3. compare mutation-key delta;
4. verify new entry remains in documented legal owner;
5. run GR-12 for any new `direct` entry;
6. run GR-13 for any remaining helper/worker entry.

## Step 5 — Re-run proof reports

Required postconditions for each selected item:

| Prior status | Required after status |
|---|---|
| Direct counterexample | `PROVEN` under GR-12 |
| Helper unproven | `PROVEN_HELPER` under GR-13 |
| Worker scope escape | `PROVEN_WORKER_MEDIATED` under GR-13 |
| Escaping callback | no callback escape reaches mutation |
| Dead writer | no mutation observation and no policy row |

No selected item may merely move to a different unproven category.

## Step 6 — Review out-of-batch effects

Create:

```text
build/guard-debug/gr14a/proof-delta.json
```

It must list:

```text
selected proof states removed
selected proof states added
unselected proof states changed
new DB findings
removed DB findings
new diagnostics
policy mutation-key delta
```

An incidental improvement is acceptable only if explicitly explained.

## Step 7 — Run full control-plane validation

Run:

```text
focused Kotlin tests
full affected unit tests
GR-12 direct proof
GR-13 mediation proof
normal DB CLI
static suite
Gradle DB task
GATE-00R twice
```

Do not use a passing source test as a substitute for a passing proof result.

---

# Mandatory adversarial checks

1. Reintroduce an unguarded caller to private helper → GR-13 fails.
2. Move worker mutation outside guard lambda → GR-13 fails.
3. Call helper from non-worker root → worker-mediated proof fails.
4. Change receiver type to ambiguous/interface → proof becomes unproven, not pass.
5. Reintroduce generic callback carrying DAO work → proof becomes unproven.
6. Remove direct barrier after conversion to direct → GR-12 finds violation.
7. Alter DAO accessor/FQCN/operation → policy authorization fails.
8. Add unselected mutation in changed owner → D4 reports it.
9. Attempt baseline update → preservation check fails.
10. Run proof twice → same result.

---

# Required validation

Each batch must run:

```text
exact focused Kotlin tests
relevant worker tests where workers changed
transaction/restore tests where relevant
DB policy v2 loader/evidence tests if policy changed
D4 scanner tests
GR-12 proof tests
GR-13 proof tests
normal DB CLI
static suite
Gradle DB task, sequentially
GATE-00R twice
```

---

# Definition of done — one batch

A GR-14 batch is complete only when:

- every selected mutation came from a frozen GR-12/GR-13 proof result;
- all selected paths become proven or are removed;
- behavior-preservation tests pass;
- no new DB finding/diagnostic appears;
- no baseline change occurs;
- any policy update is exact and source-driven;
- all out-of-batch proof changes are reviewed;
- direct/suite/Gradle outcomes are recorded.

# Definition of done — GR-14 series

The series ends only when:

```text
GR-12 direct counterexamples = 0
GR-12 direct unsupported = 0
GR-13 helper unproven/counterexample = 0
GR-13 worker unproven/counterexample = 0
All active policy mutations have a proof result
```

Only then may GR-15 begin.

## Required completion report

```text
PR: GR-14a
START SHA:
END SHA:
INPUT PROOF REPORT SHA:
ACTIVE POLICY SHA BEFORE/AFTER:
BASELINE SHA BEFORE/AFTER:

SELECTED MUTATION KEYS:
ROOT-CAUSE COUNTS:
REFACTOR PATTERNS USED:
PROVEN AFTER:
STILL UNPROVEN: 0
NEW DB FINDINGS:
NEW DB DIAGNOSTICS:

POLICY DELTA:
PRODUCTION FILES CHANGED:
FOCUSED TESTS:
WORKER/TRANSACTION TESTS:
DIRECT PROOF RESULT:
MEDIATION PROOF RESULT:
NORMAL DB CLI:
STATIC SUITE:
GRADLE DB RESULT:
GATE-00R REPRODUCIBLE:

BASELINE CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT BATCH OR GR-15 READINESS:
```