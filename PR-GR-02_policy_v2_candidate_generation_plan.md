# PR-GR-02 — Repair policy-v2 candidate generation

## Agent mission

Repair the DB-policy migration tool so it generates a **schema-valid, exact-identity v2 candidate** from legacy policy entries without activating that candidate.

This PR must:

1. remove the unsafe positional-argument coercion just added to the Room-inventory **test helper**;
2. generate v2 candidate entries containing exact callable and DAO identity;
3. preserve every unresolved conversion as visible report debt;
4. prove the checked-in candidate is reproducible;
5. leave active authorization, baseline, production Kotlin, ratchet behavior, and barrier enforcement unchanged.

At the reviewed SHA, the migration tool copies legacy-shaped entries and only adds `signature.parameters` / `signature.receiver`; the checked-in candidate lacks `schemaVersion`, `ownerFqcn`, `kind`, and typed DAO identity. The newest commit instead adds silent positional coercion in `_inventory(...)`; reverse that design rather than extending it. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/migrate_db_policy_signatures.py))

---

## Preconditions / hard stops

Do not start unless all are true:

- GR-00 evidence exists for the actual start SHA and shows inventory-only is trusted.
- GR-01’s shared v2 model, loader, controlled errors, exact callable-key definitions, and source-root interface exist and pass their tests.
- Checkout is clean.
- The active policy and baseline hashes are recorded.
- The agent has inspected actual post-GR-01 module names; do not recreate competing v2 types.

Stop immediately if:

- GR-01’s v2 loader/model is absent or differs materially from its approved contract;
- Room inventory is untrusted;
- DAO resolution has no reusable typed seam;
- a proposed change would activate v2 policy or update a baseline.

Repository instructions require agents to verify imported plans against current code, work one batch at a time, and stop on architectural ambiguity. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/AGENTS.md))

---

## Non-goals

Do **not** in GR-02:

- modify `config/guards/db_ownership_policy.yml`;
- activate the candidate in the DB CLI, ratchet, Gradle, or workflow;
- update `config/baselines/db_access.json`;
- claim barrier/helper/worker proof;
- implement source-root manifest work from GR-03;
- remove legacy policy support;
- manually hand-edit candidate entries to make tests pass;
- add compatibility defaults for absent `kind`, `ownerFqcn`, DAO FQCN, or DAO accessor;
- use simple owner-name matching as authorization.

A candidate may be **schema-complete but coverage-incomplete**. GR-05, not this PR, resolves the full legacy policy population.

---

## Required v2 candidate contract

The generated YAML must be accepted by the GR-01 v2 loader.

```yaml
schemaVersion: 2
entries:
  - path: app/src/main/java/example/Repository.kt
    ownerFqcn: example.Repository
    kind: function
    method: save
    receiver: null
    parameterTypes:
      - example.Item
    daoAccessor: expenseDao
    daoFqcn: example.ExpenseDao
    operation: insert
    barrierMode: direct
    reason: Controlled repository writer
    owner: "@panospao7"
    linkedIssue: MIT-003
```

### Required conversion rules

| V2 field | Required source |
|---|---|
| `path` | Canonical source path from the legacy entry, validated through GR-01 root contract |
| `ownerFqcn` | Exact parser-discovered owner FQCN; never copied as a simple legacy class name |
| `kind` | Exact parsed callable kind; never default to `function` |
| `method` | Exact callable name |
| `receiver` | Exact normalized extension receiver or `null` |
| `parameterTypes` | Exact ordered normalized parameter list |
| `daoAccessor` | Exact receiver symbol used at the mutation call |
| `daoFqcn` | Exact Room DAO interface FQCN from typed DAO resolution/inventory |
| `operation` | Exact invoked Room mutator method |
| `barrierMode` | `direct` only when legacy metadata proves direct mode without inference |
| metadata | Copied only after v2 loader validation |

### Mandatory prohibitions

Generated v2 entries must never contain:

- legacy `class`;
- legacy `daos`;
- legacy nested `signature`;
- `barrier_required`;
- `barrier_via`;
- wildcard values;
- a DAO simple name in place of `daoFqcn`;
- a guessed owner FQCN;
- a guessed callable kind;
- a union of sibling overloads.

---

## Exact migration semantics

### Owner resolution

Legacy `class` is a migration hint only.

1. If legacy value is an FQCN, require one exact owner match.
2. If legacy value is a simple owner name, resolve it only when exactly one owner with that simple name exists **inside the exact policy file**.
3. Emit the full parser-discovered FQCN in output.
4. If zero or multiple owners match, emit controlled unresolved status; never select first/last.
5. Nested owners must emit their nested FQCN, such as `example.Outer.Handle`.

### Callable resolution

Resolve exactly one callable using the full GR-01 callable key:

`path + ownerFqcn + kind + method + receiver + ordered parameterTypes`

Rules:

- never use sibling overload bodies as evidence;
- distinguish `String` from `String?`;
- distinguish parameter order;
- distinguish member and extension functions;
- do not fabricate top-level owner identity or property/constructor kind;
- if parser support is insufficient, report unresolved rather than emitting an inaccurate candidate row.

### DAO resolution

Replace current regex-only pair checking with the same typed DAO-resolution path used by D4/GR-01 evidence.

For every exact callable:

1. identify direct mutation calls in that callable only;
2. resolve the bare accessor symbol;
3. resolve its exact DAO FQCN;
4. resolve the exact Room mutator target;
5. require the call’s accessor to match the legacy authorization intent;
6. emit one v2 entry per distinct mutation key.

If one legacy row names multiple DAOs and exact source evidence proves multiple calls, split it into multiple v2 entries. If one callable mutates two DAOs or invokes two operations, output multiple v2 rows sharing the callable key.

Do not emit a candidate row when DAO identity is ambiguous, expression-shaped, safe-called, unresolved, or absent.

### Barrier mode conversion

Use this closed conversion rule:

- `barrier_required: true` and no `barrier_via` → `barrierMode: direct`.
- Any mediated legacy entry (`barrier_required: false`, `barrier_via`, or contradictory metadata) → unresolved `BARRIER_MODE_UNRESOLVED`.

Do not infer `helper` versus `workerMediated` from a string such as `WorkerExecutionGuard`. GR-05/GR-11 onward will classify and prove those paths.

---

## Implementation steps

### Step 1 — Replace the unsafe test seam

In `scripts/test_db_guard_room_inventory.py`, change the fixture helper to a keyword-only interface:

```text
_inventory(tmp_path, source, *, relative=DEFAULT_RELATIVE, policy=None)
```

Requirements:

- delete the `if not isinstance(relative, str)` coercion;
- invoke `build_room_inventory(..., raw_query_policy=policy)` by keyword;
- update every test call site:
  - `policy=...` for policy;
  - `relative=...` for custom fixture path;
- add a negative test proving a third positional argument raises `TypeError`;
- add a test proving a policy dictionary cannot become a synthetic path.

### Step 2 — Reuse GR-01 types; do not create shadow models

Create a small migration library module if needed, for example:

```text
scripts/db_guard/policy_v2_candidate.py
```

It may depend on:

- GR-01 v2 policy model/loader;
- GR-01 source-root interface;
- Kotlin callable parser;
- Room inventory;
- the reusable typed DAO resolver.

It must not import the DB CLI as its authority.

Keep `scripts/migrate_db_policy_signatures.py` as the CLI compatibility entry point unless a rename is explicitly coordinated everywhere.

### Step 3 — Replace legacy candidate copying

Remove behavior equivalent to:

```text
deepcopy(legacy_entry)
legacy_entry["signature"] = ...
```

Build a fresh v2 entry from typed values instead. This guarantees legacy-only fields cannot leak into output.

### Step 4 — Remove fixed migration-total assumptions

Delete the hard-coded expected result contract equivalent to:

```text
input: 99
resolved: 9
unresolved: 90
```

Counts remain report facts, never migration authorization conditions. The actual count must be computed from current input and reported deterministically.

### Step 5 — Add candidate-level evidence

Before emitting a row, prove:

- exact callable exists;
- exact owner exists;
- exact DAO accessor exists in that callable’s scope;
- accessor resolves to exact DAO FQCN;
- exact operation occurs in that exact callable.

This is row-level evidence only. Do **not** require the partial candidate to cover every mutation in a callable; full callable closure is GR-05/GR-06 work.

### Step 6 — Define controlled report v2

Replace or version the migration report so it contains:

- schema identifier and version;
- repository-relative policy identifier;
- deterministic counts;
- resolved rows with full v2 identity;
- unresolved rows with stable controlled status/reason codes;
- no raw source, absolute paths, parser exception text, SQL, or user data.

Minimum unresolved categories:

```text
OWNER_MISSING
OWNER_AMBIGUOUS
CALLABLE_MISSING
CALLABLE_AMBIGUOUS
CALLABLE_KIND_UNSUPPORTED
DAO_IDENTITY_UNRESOLVED
DAO_TARGET_AMBIGUOUS
MUTATION_PAIR_MISSING
BARRIER_MODE_UNRESOLVED
```

A duplicate resulting v2 mutation key is a configuration conflict: fail exit `2`; do not silently deduplicate.

### Step 7 — Preserve deterministic, atomic CLI behavior

Required CLI behavior:

| Situation | Exit | Candidate write |
|---|---:|---|
| all source rows resolve | 0 | yes when requested |
| valid analysis with unresolved debt | 1 | yes when at least one valid v2 entry exists |
| zero resolved entries | 1 | no invalid empty v2 policy written |
| malformed input / duplicate key / parser infrastructure failure | 2 | no candidate write |
| output collision / active-policy overwrite attempt | 2 | no write |

Candidate/report output must be atomic, deterministic, newline-normalized, and cannot overwrite the active policy.

### Step 8 — Regenerate the checked-in candidate

Generate the tracked candidate only through the repaired tool.

The tracked candidate must:

- start with `schemaVersion: 2`;
- contain only v2 fields;
- be non-empty;
- be reproducible byte-for-byte from the current active legacy policy and source tree;
- remain explicitly non-authoritative.

### Step 9 — Documentation

Update transitional documentation to state:

- candidate is machine-generated;
- candidate is not active policy;
- unresolved migration rows are not authorization;
- direct barrier mode is metadata only;
- GR-05 owns complete policy coverage;
- GR-07 owns activation.

---

## Required test matrix

### Test seam tests

1. default `_inventory` call works;
2. explicit `relative=` works;
3. explicit `policy=` works;
4. positional third argument raises `TypeError`;
5. dict policy cannot alter fixture path;
6. test helper forwards raw-query policy by keyword.

### Candidate identity tests

1. member function emits full owner FQCN and `function`;
2. nested owner emits nested FQCN;
3. same simple owner name in different packages cannot collide;
4. overloaded functions generate only exact body evidence;
5. sibling-overload-only mutation does not resolve;
6. extension receiver is emitted exactly;
7. top-level callable emits only if canonical GR-01 representation exists;
8. unsupported callable kind is unresolved, never defaulted;
9. ordered parameter types are preserved;
10. swapped parameters fail;
11. nullability differs;
12. one callable/two DAO operations emits two rows;
13. legacy multi-DAO row splits to exact rows;
14. property, constructor, and local DAO accessors resolve correctly where supported;
15. DAO simple-name ambiguity fails closed;
16. wrong accessor/FQCN pairing fails;
17. comments and strings do not count as calls;
18. safe calls and complex receiver expressions remain unresolved;
19. mediated barriers remain unresolved;
20. duplicate v2 mutation key exits `2`.

### Artifact and CLI tests

1. v2 loader accepts generated candidate;
2. output contains no legacy field names;
3. report has deterministic ordering;
4. report contains no raw source or exception text;
5. `--check` is read-only;
6. candidate/report collision fails;
7. active-policy overwrite fails;
8. no temporary files remain after success/failure;
9. no fixed `99/9/90` result is enforced;
10. real checked-in candidate is reproducible.

---

## Required post-completion checks

```bash
python3 -m pytest \
  scripts/test_migrate_db_policy_signatures.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_kotlin_callable_parser.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  -v --tb=short
```

```bash
mkdir -p build/guard-debug/gr02

set +e
python3 scripts/migrate_db_policy_signatures.py \
  --check \
  --policy config/guards/db_ownership_policy.yml \
  --report build/guard-debug/gr02/migration-report.json
rc=$?
set -e
test "$rc" -eq 1
```

```bash
set +e
python3 scripts/migrate_db_policy_signatures.py \
  --write-candidate \
  --policy config/guards/db_ownership_policy.yml \
  --output build/guard-debug/gr02/candidate.yml \
  --report build/guard-debug/gr02/migration-report.json
rc=$?
set -e
test "$rc" -eq 1

cmp \
  build/guard-debug/gr02/candidate.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml
```

```bash
python3 scripts/ci/verify_guard_registry.py

python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/gr02/inventory.json \
  --dump-room-mutators build/guard-debug/gr02/room-mutators.json
```

Run the normal DB CLI, ratchet, static suite, and Gradle DB task through GR-00 evidence capture. Expected transitional outcome: the active DB gate remains blocked for pre-existing active-policy reasons; GR-02 must not make it green.

### Preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/src/main
```

The candidate file is intentionally allowed to change. Investigate any other production/configuration change.

---

## Definition of done

GR-02 is complete only when:

- positional test-helper coercion is removed;
- candidate output is strict v2 and loader-valid;
- every emitted row has exact owner, kind, receiver, ordered parameters, DAO accessor, DAO FQCN, and operation;
- no overload union is used;
- mediated barrier mode is not guessed;
- all unresolved source rows remain visible in deterministic report output;
- checked-in candidate is generated, not hand-patched;
- active policy, baseline, production Kotlin, ratchet, Gradle behavior, and scanner authority are unchanged;
- normal DB enforcement remains truthfully blocked pending later PRs.

## Required agent completion report

```text
PR: GR-02
START SHA:
END SHA:
FILES CHANGED:
ACTIVE POLICY CHANGED: no
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
CANDIDATE CHANGED: yes
CANDIDATE ENTRY COUNT:
MIGRATION STATUS COUNTS:
KEYWORD-ONLY MISUSE TEST:
COMMANDS RUN:
RESULTS:
EXPECTED BLOCKED STATE:
UNEXPECTED DIFFERENCES:
NEXT PR: GR-03
```