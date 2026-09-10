# DB Structural Analysis Model (PR-GR-11)

> **Status: IMPLEMENTED (shadow-only) — enforcement claims: none.**
> Implemented: the shared mutation-observation seam
> (`scripts/db_guard/mutation_observation.py` plus the optional
> `mutation_observation_sink` on `scan_db_access`); the structural model,
> tokenizer, parser, CFG, barrier-marker module, and shadow report under
> `scripts/db_guard/structural_analysis/`; the shadow CLI
> (`scripts/ci/inspect_db_structural_model.py`); the corpus inventory
> (`docs/ci/db-structural/GR-11_CORPUS.yml`) and the structural-debt manifest
> (`docs/ci/db-structural/GR-11_STRUCTURAL_DEBT.yml`).
> Real-tree state at generation: 276 policy callables observed — 114
> SUPPORTED, 162 UNSUPPORTED_CONSERVATIVELY (structural debt rows), 0
> infrastructure failures; two shadow runs byte-identical.  GR-11 makes no
> dominance, mediation, or safety claim: everything below describes the
> shadow evidence GR-12 will consume.

> GR-11 does not prove a write barrier. It creates the evidence structure
> that GR-12 will use to prove or reject direct barrier dominance.

## Purpose

The current D4 scanner detects a `direct` barrier through a text search in the
source preceding a DAO call. That detects nearby syntax; it cannot prove that
every path to the mutation crossed the barrier. A barrier in another branch,
after the mutation, in an unrelated lambda, or in dead code can satisfy a
text-before-call check.

GR-11 builds the deterministic, conservative, intraprocedural foundation a
later dominance proof needs:

- a typed structural model of one callable body (nodes, edges, source spans);
- barrier-marker and mutation-site observations attached to exact spans;
- a shadow-only report that makes structural uncertainty explicit;
- fail-closed behavior for everything the model cannot support.

The foundation is **shadow-only**. GR-11 is not enforcement. Existing DB CLI,
ratchet, static-suite, and Gradle DB outcomes must remain semantically
unchanged by everything in this document.

## Non-goals

GR-11 does **not**:

- change production Kotlin, active ownership policy, structural exception
  policy, or `barrierMode` meanings;
- update any baseline or alter normal DB CLI / ratchet / static-suite /
  Gradle DB exit behavior;
- make helper or worker mediation pass, or make direct barriers enforceable;
- add a call graph, infer function-call behavior, or follow dynamic dispatch;
- assume a lambda executes immediately;
- assume `try`, `catch`, `finally`, coroutine, callback, or worker behavior;
- introduce a Kotlin compiler plugin / PSI dependency;
- calculate dominance (reserved for GR-12).

## Intraprocedural-only boundary

Analysis is bounded to **one callable body**. There is no call graph, no
interprocedural reachability, no dynamic-dispatch following, and no inference
of what an invoked function does. Helper mediation (GR-13) and
worker-root reachability are explicitly out of scope.

Control that leaves the callable body — lambdas, callbacks, coroutines,
local functions — is represented conservatively (`LAMBDA`, `LOCAL_FUNCTION`,
`UNKNOWN_CONSTRUCT` nodes with `LAMBDA_DEFERRED` / `UNKNOWN` edges); it is
never resolved, flattened into sequential flow, or dropped.

## Source-span model

- Spans are **half-open**: `[source_start, source_end)` character offsets
  into the exact file text the scanner scanned.
- `line` / `column` are 1-based and derived from `source_start` with the same
  rule as the scanner's `_line` helper (`source.count("\n", 0, offset) + 1`),
  so observation coordinates and finding coordinates can never disagree.
- Offsets are **internal coordinates**. Reports render bounded
  path/line/column only (see `bounded_fields()`); span data never appears in
  report output.
- Callable boundaries are **reused** from the existing declaration scanner's
  recorded body spans (which distinguish braced / expression / property
  forms). They are never rediscovered through a separate file-wide regex.
- Spans are deterministic. Construction fails closed: a span whose end
  precedes its start, or any negative offset, is rejected rather than
  normalized.

## Graph node and edge semantics

The structural model is a control-flow graph per callable, built from
supported syntax only. Vocabularies are **closed**; adding a node or edge
kind requires extending this ADR, not a silent widening.

### Node kinds (closed vocabulary)

| Node kind | Semantics |
|---|---|
| `ENTRY` | Synthetic entry of the callable. Exactly one per supported graph. |
| `EXIT_NORMAL` | Normal termination point. |
| `EXIT_EXCEPTIONAL` | Exceptional termination point. |
| `STATEMENT` | Plain sequential statement. |
| `MUTATION` | Resolved DAO mutation site, attached by exact span. |
| `BARRIER_CALL` | Direct barrier call expression (e.g. `writeBarrier.checkWritesAllowed(...)`). |
| `BARRIER_SCOPE` | Syntactic barrier-scope candidate (e.g. `writeBarrier.runWrite { ... }`). Candidate only — never a proof result. |
| `BRANCH` | `if` / `else` decision. |
| `WHEN` | `when` with explicit branches. |
| `LOOP_HEADER` | `while` / `for` / `do-while` header. |
| `LOOP_BODY` | Loop body region. |
| `TRY` | `try` region. |
| `CATCH` | `catch` clause. |
| `FINALLY` | `finally` clause. |
| `RETURN` | `return` statement. |
| `THROW` | `throw` statement. |
| `BREAK` | `break`. |
| `CONTINUE` | `continue`. |
| `LAMBDA` | Lambda boundary. Execution timing is unknown; never assumed synchronous. |
| `LOCAL_FUNCTION` | Local-function boundary. Not analyzed interprocedurally. |
| `UNKNOWN_CONSTRUCT` | Construct outside the supported subset. Conservatively stops the affected region — never flattened into sequential flow. |

### Edge kinds (closed vocabulary)

| Edge kind | Semantics |
|---|---|
| `NORMAL` | Sequential fall-through. |
| `TRUE_BRANCH` | `if` true outcome. |
| `FALSE_BRANCH` | `if` false outcome (including absent `else`). |
| `WHEN_BRANCH` | One explicit `when` branch. |
| `LOOP_BODY` | Edge into a loop body. |
| `LOOP_EXIT` | Edge out of a loop (a loop may execute zero times). |
| `RETURN_EXIT` | `return` to the normal exit. |
| `THROW_EXIT` | `throw` to the exceptional exit. |
| `EXCEPTION` | Exceptional edge (`try` → `catch`). Modeled syntactically; no runtime assumption. |
| `FINALLY` | `finally` edge. |
| `LAMBDA_DEFERRED` | Edge into a lambda whose execution timing is unknown. |
| `UNKNOWN` | Any boundary the model cannot resolve. Used instead of assuming behavior. |

### Graph invariants

- Exactly one `ENTRY` node and at least one exit node per supported callable.
- Duplicate node IDs are rejected; invalid source spans are rejected; a graph
  with no entry or no exits is rejected.
- An unbuildable region never yields a partial graph labeled `SUPPORTED`.

## Supported Kotlin subset

The initial model supports, where present in the corpus (no pretense of
parsing all Kotlin):

```text
braced functions
sequential statements
nested blocks
if / else (braced or single-statement unbraced bodies)
when with explicit branches (with or without subject)
while / for / do-while
try / catch / finally
return (plain, and `return try/if/when ...` construct returns)
throw
break
continue
property getters/setters with braced bodies
`val`/`var` declarations whose initializer is an if/when/try construct
simple direct barrier calls
simple DAO mutation sites supplied by D4
```

### GR-12 capability extension (soundness rules)

PR-GR-12 extended the GR-11 tokenizer/CFG without changing the closed
vocabularies. Three conservative rejections were relaxed under the following
fail-closed rules (enforced by tests in
`scripts/db_guard/structural_analysis/`):

1. **`return try/if/when ...` and `val/var x = if/when/try ...`** — the
   wrapped construct is parsed as the region's child and carries the flow
   (`RETURN` with children exits via its construct's normal completion).
   When the construct parse fails, the statement may fall back to the
   historical leaf model ONLY when the opaque-lambda gate (below) proves the
   statement hides no mutation site and no barrier-like call; otherwise the
   construct's conservative failure stands.
2. **Unbraced single-statement if/else bodies** — the body is exactly one
   parsed statement (or the next statement part when the header ends the
   line). A trailing `else` after a nested unbraced `if` fails closed
   (`ambiguous-dangling-else`: Kotlin binds it to the nearest `if`, and
   misplacing branch flow would be unsound).
3. **Opaque argument lambdas** — a brace-containing leaf statement may be
   modeled as one plain `STATEMENT` only when every brace group inside it
   contains no mutation-site start offset and no barrier-like call span
   (`barrier_markers.lambda_opacity_predicate`). A mutation or barrier call
   hidden in a lambda body keeps the strict
   `DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE` failure. The gate is active only when
   mutation sites are supplied (the analyzer must know what must never hide).

## Unsupported constructs

The following may be **represented**, but must not yield any future proof
until explicitly supported:

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

Each unsupported form emits one controlled diagnostic/result. Unsupported
syntax is never silently flattened into sequential statements.

## Lambda and callback treatment

- A lambda is **not assumed to run synchronously**.
- Only a later, explicitly reviewed contract may treat an exact known wrapper
  as synchronous. That decision is **deferred** — it is not made in GR-11.
- `writeBarrier.runWrite { ... }` may be recognized as a `BARRIER_SCOPE`
  **syntactic candidate**. No proof result is emitted for it in GR-11.
- Any unrecognized lambda / callback / coroutine boundary preserves a
  `LAMBDA_DEFERRED` or `UNKNOWN` edge.
- A barrier inside a sibling lambda or local function is **not** attached to
  the mutation's path.
- GR-12 opaque-lambda modeling: a lambda body that provably contains no
  mutation site and no barrier-like call may be collapsed into its enclosing
  statement node (nothing relevant is hidden, so no synchronicity assumption
  is needed). Any lambda that hides a mutation site or barrier-shaped call
  keeps the strict conservative failure — see the GR-12 capability extension
  rules above.

## Exception-flow treatment

- `try` / `catch` / `finally` are modeled structurally (`TRY` / `CATCH` /
  `FINALLY` nodes; `EXCEPTION` / `FINALLY` / `THROW_EXIT` edges) where the
  syntax is supported.
- The model **never assumes runtime exception behavior**: which exception
  fires, whether `finally` runs relative to a mutation, or whether a `catch`
  filters by type. Exception type filtering is unsupported and yields a
  controlled diagnostic (`DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED`).
- A barrier observed in `catch` or `finally` is recorded at its own span on
  its own path — it is never normalized into the fall-through path of the
  `try` body.
- Dominance across exceptional edges is a GR-12 decision, not a GR-11 result.

## Barrier observation vocabulary

Marker extraction is **syntax observation, not proof**. Closed vocabulary:

| Marker type | Semantics |
|---|---|
| `DIRECT_CHECK` | Direct barrier call: `writeBarrier.checkWritesAllowed(...)`. |
| `DIRECT_SCOPE` | Direct barrier scope call: `writeBarrier.runWrite(...)` — the call form, distinct from the `BARRIER_SCOPE` lambda candidate. |
| `WORKER_GUARD_CANDIDATE` | Worker-guard-shaped call observed in a worker body. Observation only; no reachability claim. |
| `UNKNOWN_BARRIER_LIKE_CALL` | Barrier-like syntax that does not match a closed marker form. Explicit uncertainty — **not** a successful marker. |

Rules:

- Marker recognition is centralized in one module, without broadening
  accepted syntax accidentally. Before implementing recognition, the actual
  production forms currently recognized by D4 — at minimum
  `writeBarrier.checkWritesAllowed(...)` and `writeBarrier.runWrite(...)` —
  must be inventoried in the corpus.
- A same-name method on an unknown receiver is **not** automatically a
  barrier.
- A barrier-like token in comments or strings is **not** a marker.
- A barrier after a mutation is recorded **after** it, never normalized
  before it.
- A barrier in a sibling lambda or local function is not attached to the
  mutation's path.
- Unknown barrier-like syntax is explicit uncertainty, never a success.

## Mutation-observation contract

**Implemented (Slice 1).** One typed, immutable record of a DAO mutation that
the D4 scanner (`scan_db_access`) has *fully* resolved through its existing
DAO-resolution path: receiver typing, DAO FQCN narrowing, overload
disambiguation, and mutator-gate membership.

- There is **no second mutation detector**: `mutation_observation.py` scans
  nothing. Observations exist only where the scanner already resolved them.
- Observations carry **resolved identity only**. No policy authorization
  state (matched/unmatched, `barrierMode`, findings) belongs in an
  observation.
- No raw source text flows through the module.

### `MutationObservation`

Frozen dataclass. Plan concept → implemented attribute:

| Plan concept | Implemented field |
|---|---|
| `path` | `path` |
| `callableKey` | `callable_key` |
| `sourceStart` | `source_start` |
| `sourceEnd` | `source_end` |
| `line` | `line` |
| `column` | `column` |
| `daoAccessor` | `dao_accessor` |
| `daoFqcn` | `dao_fqcn` |
| `operation` | `operation` |
| `mutationKind` | `mutation_kind` |
| `sourceIdentity` | `source_identity` |

Construction **fails closed**: every identity field must be a non-empty
string; every offset field a non-negative `int` (bools rejected);
`source_end >= source_start`; `line` / `column` are 1-based and `>= 1`.
Violations raise `TypeError` / `ValueError` instead of producing a
half-resolved observation.

- `callable_key` is the exact v2 `CallableKey.canonical_key()` spelling:
  six pipe-separated segments
  `path|ownerFqcn|kind|method|receiver|param,param`, with the absent receiver
  rendered as the literal `null` (`NULL_RECEIVER`) and parameter types joined
  verbatim with `,` — including commas inside generic parameter spellings.
- `source_identity` is the resolved inventory mutator identity string
  (`daoPath::daoFqcn#operation(params)`): a bounded structural coordinate,
  never source text.

### `canonical_callable_key(...)`

Returns the exact v2 callable-key spelling for a resolved callable. `kind`
accepts a `CallableKind` enum member or its plain string value; both render
as the kind's `value`, so the key stays byte-identical to the policy model's
canonical form.

### `build_mutation_observation(...)`

Keyword-only factory used by the scanner at the exact point where it builds
its authorization comparison. Every identity argument must be the **same**
value the scanner passes to `match_mutation` for the same call; `source` is
the full file text being scanned and `call_start` / `call_end` the resolved
call's half-open span inside it. Line/column are derived from `call_start`
exactly like the scanner's `_line` helper, so observation coordinates and
finding coordinates can never disagree.

### `bounded_fields()`

Report rendering: `{"path", "line", "column"}` — nothing else. Source offsets
and identity strings stay internal; a report built from this dict can never
carry raw source text or internal span data.

### Sink parameter on `scan_db_access`

```text
scan_db_access(source_root, ownership_policy=None, structural_policy=None,
               raw_query_policy=None, mutation_observation_sink=None)
```

- `mutation_observation_sink` is optional, default `None` (the production
  default): the scan then behaves **exactly as before**.
- When supplied, the sink collects one `MutationObservation` per fully
  resolved DAO mutation and **only observes** — it never alters the report.
- Observations are built from the same resolution values the findings
  consume; the scanner consumes the observation's fields for the
  `match_mutation` call and both finding emissions.
- The extraction requires **parity**: same finding fingerprints, same
  diagnostic codes, same trusted state, same policy matching, same report
  schema, same active DB result. Any semantic DB scanner change is blocked
  and must be split into a dedicated repair PR.

## Report schema (shadow report)

*Planned — not yet implemented.* The shadow CLI writes deterministic,
safe JSON:

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

Note the casing split: Python seam attributes are `snake_case`; shadow-report
JSON keys follow the schema above (`camelCase`), as specified in the GR-11
plan.

No graph source text, raw code, SQL, exception trace, absolute path, or
unbounded body excerpt may appear in a report. The `targetSha` semantics are
caller-defined ("if supplied by caller") and are deliberately not pinned
further here.

## Fail-closed behavior

The analyzer is conservative:

```text
Supported and modeled → report exact structural facts.
Unsupported or ambiguous → report controlled structural uncertainty.
Never infer safety from missing information.
Never convert uncertainty into "barrier present."
```

The only reportable structural result statuses are:

```text
SUPPORTED
UNSUPPORTED_CONSERVATIVELY
INFRASTRUCTURE_FAILURE
```

It must **never** report:

```text
PROVEN_SAFE
DOMINATED
WORKER_MEDIATED_PROVEN
HELPER_PROVEN
```

Those conclusions are reserved for later PRs (GR-12/GR-13 and beyond).

- Unsupported or ambiguous structure yields `UNSUPPORTED_CONSERVATIVELY`
  with a closed diagnostic code — never a guessed graph, never a
  synthesized barrier.
- An internal assertion/error is **infrastructure failure**, not silently
  converted into unsupported source syntax. The planned shadow CLI returns:
  `0` all selected callables modeled; `1` valid analysis with one or more
  unsupported/ambiguous callables; `2` infrastructure failure, malformed
  input, invalid policy/root, or report failure.

## Structural diagnostic vocabulary

Closed, registered in the current catalog and protocol tests. Final
spellings reuse existing naming patterns if they conflict with the catalog.

| Code | Meaning |
|---|---|
| `DB_STRUCTURAL_MODEL_CALLABLE_UNRESOLVED` | Callable could not be resolved to an exact body/span from existing declaration evidence. |
| `DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED` | Callable body uses a construct outside the supported subset. |
| `DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED` | Delimiters not balanced; affected callable stops conservatively. |
| `DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED` | Control construct outside the modeled subset. |
| `DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE` | Lambda/callback boundary escapes the modeled scope (non-local return, escaping lambda). |
| `DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED` | Exception-flow construct that cannot be modeled conservatively. |
| `DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED` | A mutation could not be attached to an exact span. |
| `DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED` | Barrier-like syntax not matching a closed marker form. |
| `DB_STRUCTURAL_MODEL_GRAPH_INVARIANT_FAILED` | Internal graph invariant violated. Infrastructure failure, not unsupported syntax. |
| `DB_STRUCTURAL_MODEL_REPORT_INVALID` | Report could not be built/validated. Infrastructure failure. |

Diagnostic rules:

- no raw Kotlin source; no raw exception message; no absolute path;
- bounded path, callable identity, line, code, and syntax family only;
- deterministic ordering;
- unknown diagnostic codes are rejected, not passed through.

## Report privacy rules

Consistent with `SENSITIVE_DIAGNOSTICS_POLICY.md`: structural reports are
evidence artifacts and carry **no raw source, no SQL, no exception text, no
absolute paths**. Everything rendered is a bounded structural coordinate
(path relative to the declared production root, callable identity, line,
column, code, syntax family, counts).

## Handoff to GR-12 / GR-13 / GR-14 / GR-15

Each later PR owns its own plan; this section fixes only what GR-11 hands
over. Any later consumer of structural evidence must extend this ADR rather
than silently widening GR-11's closed vocabularies.

- **GR-12 — direct write barrier dominance.** Consumes this model's typed
  output: supported graphs (nodes, edges, spans), exact-span mutation sites,
  and barrier markers with their paths. GR-12 proves or rejects that a
  direct barrier dominates a mutation on the same control-flow path. GR-11
  itself calculates no dominance and emits no dominance result.
- **GR-13 — helper/worker mediation proof.** Shadow-only proof/unprovability
  evidence and the remediation inventory for GR-14. It consumes
  callable/mutation identities via the observation seam. GR-11 makes no
  helper or worker claim; `WORKER_GUARD_CANDIDATE` markers are observation
  only, never reachability evidence.
- **GR-14 — refactor paths that cannot be proven.** Consumes unproven
  results and structural-debt rows. The debt manifest is review evidence,
  not a baseline, suppression, or authorization mechanism.
- **GR-15 — enforce proven mediation; retire metadata-only authorization.**
  The controlled activation step making proven protection required by the
  normal DB gate. GR-11 output remains opt-in shadow evidence; GR-15
  consumes GR-12/GR-13/GR-14 proof results, not GR-11 shadow reports
  directly.

### Deferred decisions (explicitly undecided in the GR-11 plan)

- Whether `writeBarrier.runWrite { ... }` `BARRIER_SCOPE` candidates may
  ever be treated as synchronous — deferred to a later explicitly reviewed
  contract; not decided in GR-11.
- Exact module filenames under `scripts/db_guard/structural_analysis/` —
  the plan fixes responsibilities (model / tokenizer / parser / CFG /
  barrier markers / shadow report / diagnostics), not names.
- Final diagnostic code spellings — subject to the closed-catalog conflict
  rule above.
- Structural-debt manifest path — the plan shows
  `docs/ci/db-structural/GR-11_STRUCTURAL_DEBT.yml` as an example, not a
  frozen requirement.
