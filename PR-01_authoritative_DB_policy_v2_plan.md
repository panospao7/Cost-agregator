# PR-GR-01 — Define authoritative DB policy v2 model

## Agent mission

Create the shared, typed, fail-closed **DB ownership policy v2 model** that later PRs will use to migrate and enforce exact DB authorization.

This PR defines the authority model. It does **not** activate it, migrate active policy, alter the ratchet baseline, or make the DB gate green.

The existing system has a v2 scanner shape, but active policy is legacy-shaped, candidate signatures omit mandatory identity fields, and source evidence still unions overloads. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/scanner.py))

---

## Prerequisites

Do not start until GR-00 has produced:

- clean exact-SHA evidence;
- successful inventory-only result;
- hashes for current policy and baseline;
- recorded normal CLI / ratchet / Gradle outcomes;
- confirmation that no policy/baseline changes were used to hide the blocked state.

If inventory-only is not trustworthy, stop and repair source-root / Room discovery first.

---

## Non-goals

Do **not** in this PR:

- modify `config/guards/db_ownership_policy.yml`;
- activate a v2 policy;
- regenerate `db_ownership_policy.signatures.candidate.yml`;
- update `config/baselines/db_access.json`;
- rewrite the ratchet;
- change Gradle or workflow behavior;
- remove legacy scanner behavior;
- introduce PSI, a new Gradle module, call-graph analysis, or barrier-dominance proof;
- treat helper or worker mediation as proven;
- add compatibility defaults such as “missing kind means function.”

A v2 schema without strict identity is not a v2 policy.

---

## Authoritative v2 identity contract

Every policy entry must authorize exactly **one DB mutation pair** in exactly **one callable**.

### Required document fields

| Field | Required rule |
|---|---|
| `schemaVersion` | Integer exactly `2` |
| `entries` | Non-empty list; no unknown top-level keys |
| `path` | Canonical repository-relative POSIX Kotlin path under declared production roots |
| `ownerFqcn` | Exact package-qualified owner identity, including nested owner segments |
| `kind` | Exact callable kind |
| `method` | Exact callable name; no wildcard, regex, suffix, or name fallback |
| `receiver` | Extension receiver type or null; this is not the DAO property |
| `parameterTypes` | Ordered canonical type list; empty list allowed |
| `daoAccessor` | Exact property/constructor/local DAO accessor symbol |
| `daoFqcn` | Exact Room DAO interface identity |
| `operation` | Exact DAO method invoked |
| `barrierMode` | `direct`, `helper`, or `workerMediated`; metadata only in this PR |
| `reason` | Non-empty justification |
| `owner` | Non-empty accountable owner |
| `linkedIssue` | Non-empty work-item / architectural issue identifier |

### Allowed callable kinds

Use the same closed set supported by the scanner:

- `function`
- `constructor`
- `property_getter`
- `property_setter`
- `top_level_function`
- `initializer`

A mutation policy entry may use only a kind the callable parser can resolve exactly.

### Canonical keys

Define these immutable identities in the shared model:

- **Callable key:** path, ownerFqcn, kind, method, receiver, ordered parameterTypes.
- **Mutation key:** callable key, daoAccessor, daoFqcn, operation.
- **Policy entry key:** mutation key.

There must be no class-simple-name authorization identity.

### Important semantic rules

1. `ownerFqcn` must equal the parser’s discovered owner identity exactly.
2. `parameterTypes` are ordered. Reordered parameters are a different callable.
3. `String` and `String?` are different types.
4. A normal member has `receiver: null`; an extension function has its canonical extension receiver.
5. `daoAccessor` and `receiver` are different concepts.
6. One callable that writes two DAOs or invokes two operations gets multiple entries with the same callable key and distinct mutation keys.
7. Duplicate mutation keys fail configuration validation.
8. Unknown keys fail configuration validation.
9. No v1 field such as `class`, `daos`, or nested legacy `signature` is accepted by the v2 loader.
10. No auto-detection or silent v1-to-v2 conversion is permitted.

The current scanner’s simple-class match is insufficient, which is why `ownerFqcn` is mandatory. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/scanner.py))

---

## Required module design

Create a shared package below `scripts/db_guard/`. Exact filenames may vary slightly, but responsibilities must remain separated.

| Module | Responsibility |
|---|---|
| `policy_model.py` | Frozen typed value objects, canonical keys, closed enums |
| `policy_v2_loader.py` | YAML loading, schema validation, canonicalization, controlled errors |
| `policy_v2_evidence.py` | Exact callable and DAO source-evidence verification |
| `policy_errors.py` | Stable controlled error codes and error objects |
| `policy_legacy.py` | Extracted legacy v1 loader/evidence compatibility implementation |
| `source_roots.py` | Root-contract interface; initially receives current roots as input |

### Dependency rules

- Shared modules must not import `scripts/verify_db_access_boundaries.py`.
- The CLI may import shared modules.
- The scanner may import the v2 model.
- Tests may import all shared modules.
- Legacy compatibility wrappers may import `policy_legacy.py`.
- No module may call `sys.exit` except a CLI adapter.
- Library failures return typed controlled errors; CLI maps them to report diagnostics and exit `2`.

This prevents the monolithic legacy CLI from remaining the hidden source of truth.

---

## Extraction plan

### Step 1 — Freeze legacy behavior with characterization tests

Before moving code, add/retain tests that characterize:

- legacy policy metadata validation;
- canonical path behavior;
- legacy source-evidence behavior;
- legacy CLI diagnostic mapping;
- current blocked result with the active policy.

Do not assert that legacy overload union is correct. Assert only that it remains unchanged until the dedicated replacement PR.

### Step 2 — Extract legacy code without semantic changes

Move existing v1 policy loader/validation and legacy source evidence out of `verify_db_access_boundaries.py` into `policy_legacy.py`.

Keep temporary wrappers with existing public names so old tests continue passing.

Examples of legacy-only concepts:

- simple `class`;
- `daos` list;
- optional nested `signature`;
- overload union by method name.

Make their names explicitly legacy. Do not name them generic `load_policy` or `verify_policy_evidence`.

### Step 3 — Implement v2 model and loader

The v2 loader must reject:

- missing `schemaVersion`;
- schema version other than 2;
- unknown fields;
- missing ownerFqcn;
- ownerFqcn using only a simple class name;
- missing kind;
- unknown kind;
- missing receiver;
- malformed receiver;
- missing parameterTypes;
- non-list parameterTypes;
- noncanonical parameter types;
- duplicate entry keys;
- wildcard method;
- noncanonical path;
- missing DAO FQCN;
- mismatch between DAO accessor and DAO FQCN when source evidence is evaluated.

Use the same canonical type normalization logic as the callable parser. Do not duplicate type normalization rules.

### Step 4 — Implement exact v2 source evidence

Create a pure function conceptually equivalent to:

`verify_v2_policy_source_evidence(policy, sourceRoots, roomInventory)`

For each v2 entry it must:

1. resolve canonical path only beneath approved roots;
2. parse and mask the exact source file;
3. discover owners;
4. find exactly one owner with exact `ownerFqcn`;
5. find exactly one callable whose full key matches path, ownerFqcn, kind, method, receiver, and ordered parameterTypes;
6. reject ambiguity, parser uncertainty, unsupported body, missing owner, and missing callable as infrastructure error;
7. resolve DAO accessor through the same typed logic used by D4;
8. require resolved DAO FQCN to equal policy `daoFqcn`;
9. require the exact operation to occur in that exact callable;
10. group policy entries only by the full callable key;
11. require every actual DB mutation extracted from that exact callable to have a corresponding v2 entry;
12. never inspect sibling overloads as evidence;
13. never fall back to file-wide token matching, simple class names, or last-match behavior.

Barrier evaluation is deliberately out of scope. Preserve `barrierMode` as typed metadata but do not let it prove direct/helper/worker correctness.

### Step 5 — Add scanner-facing v2 matching API

Add a pure v2 policy-match function that matches:

- exact canonical path;
- exact ownerFqcn;
- exact kind;
- exact method;
- exact extension receiver;
- exact parameter tuple;
- exact DAO accessor;
- exact DAO FQCN;
- exact operation.

Do not wire it into active production enforcement yet. That activation belongs in GR-06/GR-07.

### Step 6 — Document the transitional state

Update DB guard documentation to state:

- v1 remains active temporarily;
- v2 model exists but is not active;
- v2 candidate generation belongs to GR-02;
- active policy activation belongs to GR-07;
- no policy/baseline update is authorized by GR-01;
- current “one approved owner” language is an architectural objective until exact-v2 enforcement is active.

The current document’s stronger claim must not remain unqualified while the active gate is blocked. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/docs/DB_WRITE_OWNERSHIP.md))

---

## Required test matrix

### Model / loader tests

Add a dedicated v2 policy test file covering:

1. minimal valid function entry;
2. valid overloads with different ordered parameter lists;
3. valid nested ownerFqcn;
4. valid extension receiver;
5. valid top-level function;
6. missing schemaVersion;
7. schemaVersion 1, 3, string, null;
8. unknown top-level field;
9. unknown entry field;
10. missing ownerFqcn;
11. simple-name-only ownerFqcn;
12. wrong package ownerFqcn;
13. missing kind;
14. kind defaulting attempt;
15. invalid kind;
16. missing receiver;
17. malformed/noncanonical receiver;
18. missing parameterTypes;
19. unordered/incorrect parameter type entry;
20. `String` versus `String?`;
21. swapped parameter order;
22. wildcard method;
23. duplicate mutation key;
24. same callable / distinct operation is allowed;
25. same callable / distinct DAO is allowed;
26. invalid DAO FQCN;
27. path traversal, backslash, basename-only, absolute path;
28. non-Kotlin path;
29. no raw source text in controlled error objects.

### Exact source-evidence tests

Use synthetic Kotlin fixtures to prove:

1. same method name with two overloads; each policy entry proves only its own body;
2. a policy pair present only in sibling overload fails;
3. nested `Outer.Handle` cannot be authorized by unrelated `Other.Handle`;
4. same simple class name in different packages cannot collide;
5. extension receiver mismatch fails;
6. parameter nullability mismatch fails;
7. parameter order mismatch fails;
8. one policy entry per DAO/operation works;
9. missing source mutation is rejected;
10. unlisted source mutation in the same exact callable is rejected;
11. DAO accessor resolves to wrong DAO FQCN and fails;
12. ambiguous DAO identity fails closed;
13. comment/string lookalikes do not count as mutation evidence;
14. unsupported Kotlin body fails as infrastructure error;
15. invalid source root fails;
16. source evidence never scans a sibling overload.

### Legacy regression tests

Run all existing legacy DB tests after extraction. The legacy wrappers must preserve behavior until GR-06 removes their authority.

### Current-repository regression tests

The v2 loader must reject both:

- the active v1 policy as not-v2;
- the current candidate because it lacks required `kind` and `ownerFqcn`.

That rejection is a desired test at this stage. The candidate is not to be “made to pass” in GR-01.

---

## Required post-completion checks

### Python checks

```bash
python3 -m pytest \
  scripts/test_kotlin_callable_parser.py \
  scripts/test_migrate_db_policy_signatures.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  -v --tb=short
```

Use the actual created test filenames if they differ, but do not omit categories.

### Guard integration checks

```bash
python3 scripts/ci/verify_guard_registry.py

python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/pr01-inventory.json \
  --dump-room-mutators build/guard-debug/pr01-room-mutators.json
```

### Legacy behavior / transition checks

Run the normal active CLI and capture the result using GR-00 tooling.

Expected outcome: it remains blocked for the pre-existing active-policy reason. A changed failure must be investigated; it must not be dismissed as “expected because the guard is red.”

### Diff-preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/src/main
```

### Static-suite handling

Run the static suite for observation and artifact capture. It may remain red because GR-01 intentionally does not activate or repair the active policy. Do not call the PR green merely because the new unit tests pass.

---

## Definition of done

GR-01 is complete only when all of the following are true:

- a documented v2 schema exists;
- ownerFqcn, kind, receiver, and ordered parameterTypes are mandatory;
- DAO identity is exact and typed;
- v2 loader rejects malformed / incomplete / ambiguous input fail-closed;
- exact v2 source evidence uses full callable identity;
- overload union is impossible in v2 code;
- shared modules have no reverse dependency on the legacy CLI;
- legacy behavior remains characterized and unchanged;
- active policy and baseline remain untouched;
- current candidate is explicitly proven invalid for promotion;
- GR-02 has a clear implementation target.

## Required agent completion report

```text
PR:
START SHA:
END SHA:
FILES CHANGED:
ACTIVE POLICY CHANGED: no
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
LEGACY CLI SEMANTICS CHANGED: no
NEW V2 MODULES:
NEW TESTS:
COMMANDS RUN:
RESULTS:
KNOWN EXPECTED RED STATE:
UNEXPECTED DIFFERENCES:
NEXT PR: GR-02
```