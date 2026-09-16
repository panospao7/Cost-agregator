# PR-GR-10A — Canonicalize registry → suite → ratchet → Gradle command ownership

## Agent mission

Make one canonical registry-derived execution plan the authority for how every active guard runs.

After this PR, a guard’s executable command, mode, interpreter, ratchet settings, baseline, required inputs, timeout profile, and child argv must be defined once and compiled for:

1. the static suite;
2. direct registered execution;
3. ratchet execution;
4. Gradle wrapper tasks;
5. CI evidence/artifacts.

This is a **control-plane PR**. It must not alter guard policy semantics, baselines, source scanning scope, or production Kotlin.

## Why this PR is necessary

At SHA `8b45879e445e8750fcb9210bcf44b9a26e6468dd`, `guard_registry.py` calls itself the single source of truth, but it stores mainly script/mode/policy metadata while `run_static_guard_suite.py` separately owns a hard-coded `GUARD_MANIFEST` with actual commands and arguments. The registry validator then imports that suite manifest to compare names, reversing the intended ownership direction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

The suite’s DB entry also embeds a nested child interpreter token of `python`, while outer commands use `python3`/runtime resolution. The suite’s interpreter fallback only resolves the outer executable, not a nested ratchet `--command-arg=python`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/run_static_guard_suite.py))

Gradle independently rebuilds both the time command and the DB ratchet command, including policy paths and child argv. It also contains inline lifecycle and money scanners rooted at `src/main/java`; CI additionally invokes a PowerShell currency guard with a manually supplied source directory. Those are active enforcement paths outside the registry-derived suite authority. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/build.gradle.kts))

The acceptance gate requires a canonical registry with engine, entry point, source scope, configuration, timeout, test manifest, and no independently enforced conflicting scanner. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

## Required order and entry gate

### Must follow

- `GATE-00R` must have captured exact-SHA direct, suite, ratchet, and Gradle evidence.
- No active GR-08 finding batch may be changing DB policy at the same time.
- No active TIME-01 source refactor may be changing time behavior at the same time.
- No other agent may own Gradle during final validation.

### Do not begin if

- current direct DB, ratchet, suite, and Gradle results cannot be recorded;
- the checkout is dirty;
- a current guard has an unexplained exit `2`;
- a proposed implementation requires changing a baseline or source exception;
- an unregistered scanner cannot be classified as retained, extracted, or retired.

---

# Scope

## Allowed

- `scripts/ci/guard_registry.py`;
- new pure registry/plan compiler modules under `scripts/ci/`;
- `scripts/ci/run_static_guard_suite.py`;
- `scripts/ci/guard_ratchet.py`, only for registered-command fail-closed integration;
- `scripts/ci/verify_guard_registry.py`;
- guard-runner/plan-renderer tests;
- Gradle task wrappers in `app/build.gradle.kts`;
- CI workflow command cleanup where it removes duplicated guard lists;
- extraction/retirement of unregistered inline enforcement only after equivalence proof;
- command-authority documentation and evidence artifacts.

## Forbidden

- production Kotlin changes;
- DB policy, structural exception, source-root manifest, time exceptions, or baselines;
- baseline generation or `--update-baseline`;
- changing a rule merely because its command becomes canonical;
- broad Gradle task refactors unrelated to guard execution;
- changing source-scope semantics; GR-10B owns that;
- deleting a legacy scanner without proof that its canonical replacement detects every required rule.

## Required preservation checks

```bash
git diff --exit-code -- \
  app/src/main \
  config/baselines \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/production_source_roots.yml \
  config/guards/time_boundary_exceptions.yml
```

Expected: no changes.

---

# Core architecture contract

## One direction of authority

The dependency direction must become:

```text
guard_registry
   ↓
guard_execution_plan compiler
   ├── run_static_guard_suite
   ├── run_registered_guard
   ├── Gradle wrapper tasks
   └── registry validator / plan renderer
        ↓
guard_ratchet executes supplied tokenized child argv
```

Forbidden directions:

```text
registry validator → imports suite manifest
Gradle → rebuilds DB/time child command
suite → owns independent production GUARD_MANIFEST
workflow → maintains a manual executable guard list
ratchet → silently falls back from a registered CI guard to an unknown legacy protocol
```

## Canonical terms

| Term | Meaning |
|---|---|
| **Guard specification** | Static registry metadata: identity, engine, mode, input references, invocation template, baseline/protocol, timeout profile. |
| **Execution context** | Runtime-only values: repository root, actual Python executable, output directory, CI mode, timeout override, test-only overrides. |
| **Execution plan** | Fully resolved, tokenized command graph derived from one guard specification plus one context. |
| **Outer command** | A direct guard command or ratchet invocation. |
| **Child command** | The guard command executed by `guard_ratchet.py`. |
| **Semantic command equality** | Same guard ID, mode, executable identity, script, normalized child tokens, policies, baseline, protocol, timeout semantics, and required inputs. Absolute path spelling may differ. |

## Non-negotiable command rules

1. Production commands are token lists only; no shell strings.
2. A canonical plan never emits bare `python` or `python3`; it emits the resolved runtime interpreter.
3. Ratchet child commands use repeated single-token `--command-arg=<value>` only.
4. Registered protocol-v2 guards pass explicit protocol-v2 intent; registered CI execution must not fall back silently to protocol v1.
5. Paths are repository-relative in registry data and resolved only by the execution context.
6. The registry owns semantic arguments; adapters own only runtime path resolution and process execution.
7. Test-only input overrides must be explicit, root-contained, typed, and rejected in production CI.
8. Exit mapping remains universal: `0` pass, `1` violation, `2` infrastructure/configuration failure. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Required deliverables

## 1. Typed guard-execution compiler

Create a neutral pure module, for example:

```text
scripts/ci/guard_execution_plan.py
scripts/ci/test_guard_execution_plan.py
```

It must expose pure APIs equivalent to:

```text
load_guard_specs()
validate_guard_specs()
compile_guard_plan(guard_id, context)
compile_static_suite_plan(context)
canonicalize_plan_for_comparison(plan)
write_plan_json(plan, output_path)
```

No library function may call `sys.exit`.

### Required models

| Model | Required fields |
|---|---|
| `GuardSpec` | guard ID, description, mode, engine, entry point, rule IDs, required inputs, test manifest, timeout profile, execution template |
| `RatchetSpec` | baseline path, finding protocol, fingerprint schema, child argument template, CI restrictions |
| `ExecutionContext` | repo root, interpreter path, CI boolean, output directory, timeout override, explicitly permitted test overrides |
| `ExecutionPlan` | guard ID, mode, outer argv, child argv if any, resolved required inputs, baseline, timeout, protocol, output contract |
| `PlanDiagnostic` | stable code, guard ID, bounded context, severity/category |

Use immutable typed models or equivalently strict validated mappings. Do not leave production execution dependent on arbitrary nested dictionaries.

## 2. Registry execution schema

Extend each active registry entry with an `execution` section.

It must define, at minimum:

| Field | Rule |
|---|---|
| `engine` | `python-direct`, `python-ratchet`, `gradle-native`, or explicitly non-suite external job |
| `entrypoint` | repository-relative executable script/task identity |
| `arguments` | token template; no shell command string |
| `mode` | must match the existing registry mode |
| `requiredInputs` | all policy/baseline/allowlist/config/script inputs required for execution |
| `timeoutProfile` | named timeout semantics, not duplicated numeric arithmetic |
| `outputContract` | stdout/report protocol and expected exit mapping |
| `ratchet` | required for ratchet guards only |
| `testManifest` | current test file(s), or explicit documented absence |
| `documentationAnchor` | current architecture/documentation path |

For the DB guard, the execution schema must own:
- `db_access` guard ID;
- `db_access_v2.json`;
- protocol 2;
- fingerprint schema 2;
- exact policy, structural policy, structural manifest, and source-root inputs;
- tokenized child argv;
- D4 timeout profile.

The current DB registry already records key policy/baseline/protocol metadata; GR-10A must turn that metadata into executable authority rather than duplicate it in the suite and Gradle. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

## 3. Registered execution adapter

Add:

```text
scripts/ci/run_registered_guard.py
scripts/ci/test_run_registered_guard.py
```

Suggested behavior:

```text
run_registered_guard --guard-id <id> --context suite|gradle|direct
                     --root <repo-root> --ci-mode
                     [--output-summary <path>]
                     [test-only explicit input overrides]
```

Responsibilities:

1. load and validate registry;
2. compile the guard plan;
3. validate required inputs;
4. execute outer argv with `shell=False`;
5. preserve exact exit codes;
6. write a safe machine-readable summary;
7. never create/update a baseline;
8. reject unknown guard IDs and unsupported execution contexts with exit `2`.

The adapter is not a new guard. It is the one runtime bridge used by suite/Gradle.

## 4. Plan evidence output

Every suite run must write:

```text
execution-plan.json
execution-plan.sha256
effective-inputs.json
```

For each guard, record:

```text
guardId
mode
engine
resolvedOuterArgv
resolvedChildArgv
interpreter
timeoutSeconds
requiredInputs
inputHashes
baselinePath/hash where applicable
findingProtocol where applicable
```

Do not include secrets, user home paths, environment dumps, or raw source.

## 5. Command-authority matrix

Create:

```text
docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md
```

For every active enforcement path, record:

| Enforcement path | Current owner | Canonical post-PR owner | Resolution |
|---|---|---|---|
| Static suite direct guard | suite manifest | registry execution spec | migrate |
| Static suite ratchet guard | suite manifest | registry execution spec | migrate |
| DB Gradle task | KTS command construction | registered runner | migrate |
| Time Gradle task | KTS command construction | registered runner | migrate |
| Inline lifecycle scanner | Gradle KTS | prove subsumed / extract / retire | mandatory decision |
| Inline money scanner | Gradle KTS | prove subsumed / extract / retire | mandatory decision |
| PowerShell currency guard in CI | workflow | register / extract / retire | mandatory decision |
| Release artifact verifier | workflow job | register as external execution entry | classify explicitly |

No row may be left `UNKNOWN`.

---

# Mandatory decision gate: legacy/unregistered enforcement

The current Gradle build includes `checkLifecycleBypass` and `checkRawMoneyAggregates`, and CI separately invokes `scripts/currency_guardrails.ps1` against `app/src/main/java`. These cannot remain invisible parallel guard authorities. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/build.gradle.kts))

For each, choose exactly one disposition:

| Disposition | Requirements |
|---|---|
| `SUBSUMED_AND_RETIRED` | Prove canonical guard catches all positive fixtures and relevant real-tree patterns; remove task/CI invocation only after regression tests. |
| `EXTRACTED_AND_REGISTERED` | Extract behavior into a standalone canonical guard with stable rule IDs, tests, registry entry, suite participation, and Gradle bridge. |
| `REGISTERED_EXTERNAL_ENGINE` | Only for genuinely non-Python/non-suite proof such as release artifact verification; registry records owner, command, scope, artifacts, and CI job. |
| `BLOCKED` | Stop PR if equivalence cannot be proven. Do not leave it unregistered. |

Do not call a scanner “redundant” based on rule-name similarity.

---

# Implementation sequence

## Step 0 — Freeze and inspect

Record:

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}
git hash-object scripts/ci/guard_registry.py
git hash-object scripts/ci/run_static_guard_suite.py
git hash-object scripts/ci/guard_ratchet.py
git hash-object app/build.gradle.kts
```

Read current:
- registry;
- suite;
- ratchet;
- registry validator;
- Gradle DB/time tasks;
- CI workflow;
- all existing guard-runner tests;
- GATE-00R evidence bundle.

Hard stop if current command behavior cannot be reproduced.

## Step 1 — Characterize existing commands before changing them

Create untracked artifacts:

```text
build/guard-debug/gr10a/before/
  registry.json
  suite-summary.json
  suite-executed-argv.json
  db-direct-plan.json
  db-suite-plan.json
  db-gradle-plan.json
  time-direct-plan.json
  time-suite-plan.json
  time-gradle-plan.json
```

For DB and time, record:
- interpreter;
- direct argv;
- ratchet outer argv;
- ratchet child argv;
- policy/allowlist/baseline paths;
- timeout;
- protocol;
- exit mapping.

This is characterization evidence, not a pass/fail gate.

## Step 2 — Add compiler tests before moving commands

Tests must prove:

1. every guard compiles to exactly one valid plan;
2. unknown execution keys fail closed;
3. a blocking guard cannot compile as warning;
4. a ratchet guard requires baseline and ratchet metadata;
5. a direct guard cannot contain ratchet-only fields;
6. token templates cannot contain shell syntax;
7. output cannot contain a bare `python`/`python3`;
8. DB compiles to protocol 2 and `db_access_v2.json`;
9. DB child argv includes exact structural and ownership inputs;
10. timeout derivation is deterministic;
11. malformed timeout profile fails closed;
12. missing required input fails before child execution;
13. output plan is deterministic;
14. semantic comparator detects changed baseline, policy path, protocol, child token, or timeout;
15. only approved volatile values are ignored;
16. test overrides outside root fail;
17. production/CI mode rejects test overrides;
18. duplicate guard IDs/rule IDs/entrypoints fail where prohibited.

## Step 3 — Implement registry-to-plan compilation

Do not copy the suite manifest into the registry verbatim.

Instead:
1. model each guard’s semantic invocation;
2. resolve interpreter and filesystem paths only in `ExecutionContext`;
3. compile direct and ratchet plans through one code path;
4. make all default ratchet plans use tokenized `--command-arg=<token>`;
5. make protocol explicit for registered protocol-v2 plans;
6. preserve generic legacy ratchet compatibility only for explicit non-CI test callers.

For a registered CI command, failure to load corresponding registry metadata must be exit `2`, not protocol-v1 fallback. The current ratchet otherwise falls back to v1 if registry import/format lookup fails; production canonical execution must not rely on that fallback. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_ratchet.py))

## Step 4 — Migrate the static suite

Replace the production default `GUARD_MANIFEST` with:

```text
compile_static_suite_plan(ExecutionContext(...))
```

Requirements:
- all registered guards execute once;
- guard order is deterministic;
- suite still runs all guards after a violation;
- suite still marks exit `2` as infrastructure;
- logs/summary remain available;
- actual resolved argv is written per guard;
- custom manifests remain test-only and cannot replace the canonical registry in CI mode;
- no production default command list remains in `run_static_guard_suite.py`.

The existing suite correctly uses `shell=False`, captures logs, and runs all commands; preserve those behaviors while replacing command ownership. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/run_static_guard_suite.py))

## Step 5 — Reverse registry-validator ownership

Replace:

```text
verify_guard_registry → import suite → inspect GUARD_MANIFEST
```

with:

```text
verify_guard_registry → load registry → compile canonical suite plan → validate plan
```

Validator checks must include:
- every active registry guard appears once in canonical suite plan unless declared external;
- no external guard is accidentally included in the suite;
- every ratchet plan has baseline/protocol;
- every required input exists;
- no unresolved execution template token;
- no duplicate invocation identity;
- command plan contains no shell string;
- plan order is deterministic;
- legacy `GUARD_MANIFEST` is absent from production suite code.

## Step 6 — Migrate Gradle to the registered-runner bridge

Preserve public Gradle task names:

```text
checkDirectTimeCalls
verifyDbAccessBoundaries
```

But their implementation must become thin wrappers around `run_registered_guard.py`.

Gradle may own:
- configured Python executable;
- repository root;
- Gradle task name/description;
- exit-to-GradleException mapping;
- test-only override forwarding.

Gradle must not own:
- DB child command;
- ratchet argv;
- time guard argv;
- baseline/policy/allowlist path list;
- timeout arithmetic;
- source-root input semantics.

The current Gradle tasks manually construct these details; remove that duplication. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/build.gradle.kts))

### Test-only Gradle overrides

If current Gradle tests rely on properties such as DB path overrides:
1. preserve the external property names temporarily;
2. convert them into typed named input overrides;
3. validate root containment and declared input key;
4. reject overrides in CI unless a dedicated test mode is active;
5. never let an override change guard ID, mode, baseline mode, or protocol.

## Step 7 — Resolve all unregistered enforcement paths

Use the mandatory decision ledger.

### Lifecycle scanner

For `checkLifecycleBypass`:
- build positive fixtures for direct `ExpenseDao.insert/update/delete`;
- prove whether DB D4 catches each case;
- prove whether policy/baseline semantics are equivalent;
- retire only if D4 is at least as strict;
- otherwise extract as a registered guard.

### Money scanner and PowerShell currency guard

For `checkRawMoneyAggregates` and `currency_guardrails.ps1`:
- inventory exact rule families;
- add positive/negative fixtures;
- compare against `verify_money_boundaries.py`;
- retain no hidden manual workflow invocation;
- either extract/register or retire after proof.

### Release verification

`verify_release_artifact.py` runs in a separate release job. It need not enter the static suite, but it must become a registry-declared external execution entry with owner, input/output contract, job identity, and documentation anchor. The current registry explicitly treats release artifact verification as outside the suite; GR-10A must make that exclusion explicit and auditable. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

## Step 8 — Clean workflow duplication

Remove or replace the long manual list of individual static-guard reproduction commands in CI documentation/comments.

The workflow should invoke the canonical suite, not describe itself as an independent command manifest. Keep only:
- the canonical suite command;
- required setup;
- artifact upload;
- external jobs explicitly registered as such.

Do not alter required-job semantics, `continue-on-error`, or CI failure policy in this PR.

## Step 9 — Add adversarial command-authority tests

Required checks:

1. Change DB baseline path in a temporary registry → suite, direct runner, and Gradle bridge all show the same changed plan.
2. Change DB policy path → all contexts show the same changed plan.
3. Replace child interpreter with a bare `python` → compiler rejects it.
4. Use shell metacharacters in an argument → compiler rejects it.
5. Use legacy `--command` in a production ratchet plan → compiler rejects it.
6. Change protocol from 2 to 1 for DB → validator rejects it.
7. Register a guard but omit suite eligibility → validator fails.
8. Add a suite-only guard → validator fails.
9. Use custom suite manifest in CI mode → exit `2`.
10. Pass an absolute test override outside root → exit `2`.
11. Missing script/baseline/policy/allowlist → exit `2` before child launch.
12. Child exits `0`, `1`, `2`, timeout, executable missing → exact summary and suite outcome mapping.
13. Gradle task report plan semantically equals suite plan for DB/time.
14. Legacy inline scanner cannot disappear without a matching ledger disposition and proof artifact.

## Step 10 — Final semantic comparison

For DB and time, produce:

```text
build/guard-debug/gr10a/final/
  db-direct-plan.json
  db-suite-plan.json
  db-gradle-plan.json
  time-direct-plan.json
  time-suite-plan.json
  time-gradle-plan.json
  semantic-plan-comparison.json
```

Expected:

```text
db: direct == suite child == Gradle child
time: direct == suite == Gradle
```

Differences permitted only for:
- output paths;
- absolute versus canonical relative path representation;
- test-only explicit overrides.

---

# Required validation

## Python

```bash
python3 -m pytest \
  scripts/ci/test_guard_execution_plan.py \
  scripts/ci/test_run_registered_guard.py \
  scripts/ci/test_guard_registry.py \
  scripts/ci/test_run_static_guard_suite.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  -v --tb=short
```

Use actual existing filenames where they differ; do not omit categories.

```bash
python3 scripts/ci/verify_guard_registry.py --root .
```

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --mode ci \
  --output-dir build/guard-debug/gr10a/static-suite
```

## Direct command-plan proof

```bash
python3 scripts/ci/run_registered_guard.py \
  --guard-id db_access \
  --context direct \
  --root . \
  --ci-mode \
  --output-summary build/guard-debug/gr10a/db-direct-summary.json
```

```bash
python3 scripts/ci/run_registered_guard.py \
  --guard-id time_boundaries \
  --context direct \
  --root . \
  --ci-mode \
  --output-summary build/guard-debug/gr10a/time-direct-summary.json
```

## Gradle — sequentially, only with ownership

```bash
./gradlew :app:checkDirectTimeCalls \
  --no-daemon --stacktrace --console=plain
```

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

```bash
./gradlew :app:check \
  --no-daemon --stacktrace --console=plain
```

Finally rerun GATE-00R twice for the resulting SHA.

---

# Definition of done

GR-10A is complete only when:

- one registry-derived plan is the sole default authority for suite/direct/ratchet/Gradle guard commands;
- no default suite `GUARD_MANIFEST` owns production commands;
- registry validation no longer imports suite authority;
- DB and time direct/suite/Gradle plans are semantically equal;
- all canonical plans use the resolved interpreter and tokenized argv;
- registered CI execution cannot silently fall back to legacy protocol behavior;
- all active scanners are registered, extracted, proven subsumed, or explicitly external;
- no independent inline KTS/PowerShell guard remains unexplained;
- policies, baselines, source roots, exceptions, and production Kotlin are byte-identical;
- two GATE-00R captures reproduce semantic execution results.

## Required completion report

```text
PR: GR-10A
START SHA:
END SHA:
GATE-00R INPUT SHA:
REGISTRY SHA BEFORE/AFTER:

REGISTERED ACTIVE GUARDS:
REGISTERED EXTERNAL GUARDS:
UNREGISTERED ENFORCEMENT PATHS BEFORE:
UNREGISTERED ENFORCEMENT PATHS AFTER:

SUITE DEFAULT MANIFEST REMOVED: yes/no
REGISTRY VALIDATOR OWNS PLAN VALIDATION: yes/no
BARE PYTHON TOKENS IN CANONICAL PLANS: 0
SHELL-STRING COMMANDS IN CANONICAL PLANS: 0

DB DIRECT/SUITE/GRADLE PLAN EQUIVALENT: yes/no
TIME DIRECT/SUITE/GRADLE PLAN EQUIVALENT: yes/no
LEGACY GRADLE SCANNER DISPOSITIONS:
POWERSHELL CURRENCY GUARD DISPOSITION:
RELEASE ARTIFACT ENTRY REGISTERED: yes/no

PYTHON TESTS:
STATIC SUITE:
GRADLE TIME TASK:
GRADLE DB TASK:
GRADLE CHECK:
GATE-00R DOUBLE CAPTURE REPRODUCIBLE: yes/no

PRODUCTION KOTLIN CHANGED: no
DB POLICY CHANGED: no
BASELINE CHANGED: no
TIME EXCEPTIONS CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR: GR-10B
```