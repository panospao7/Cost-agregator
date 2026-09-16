# PR-GATE-00R — Re-establish exact-SHA guard evidence

## Agent mission

Create a reproducible, exact-SHA evidence bundle that answers one question truthfully:

> At one immutable repository state, which guards pass, which report real violations, and which are blocked by infrastructure or guard defects?

This is an **evidence and diagnosis PR**, not a product-fix PR. It must not make a red result disappear. Its job is to replace historical assumptions with current, reproducible facts.

The starting historical anchor is:

```text
8b45879e445e8750fcb9210bcf44b9a26e6468dd
```

That commit was made on August 29, 2026 and changed 40 files in the direct-clock migration. If `HEAD` is no longer exactly that SHA when work begins, record the actual full 40-character `HEAD`; do not label newer evidence as evidence for `8b45879`. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

## Why this PR is necessary

The repository’s acceptance contract requires exact target/base/merge-base identity, a clean tree, preserved artifacts, and two consecutive complete runs. It explicitly rejects reports from different commits or vague “latest” evidence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

The DB scanner is now a protocol-v2 pipeline with trusted `0`, finding `1`, and infrastructure/configuration `2` outcomes. It also intentionally allows bounded advisory diagnostics that must still be recorded rather than discarded. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_db_access_boundaries.py))

The static suite runs every guard and writes per-guard logs plus `summary.json`/`summary.md`; its suite exit is `0` for clean, `1` for violations, and `2` for infrastructure failure. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/run_static_guard_suite.py))

Therefore, this PR establishes the current route:
- `TIME-01` if time source findings or time migration behavior failures exist;
- `GR-08q` only if DB is trusted and returns real findings;
- DB/control-plane repair if DB exits `2` or command paths disagree;
- GR-10 control-plane work if direct/suite/Gradle authority differs.

---

# Scope

## Allowed

- Existing evidence-capture tooling, if present and adequate;
- Otherwise, one narrow capture runner under `scripts/ci/`;
- Capture-runner tests;
- Documentation/template for evidence interpretation;
- Untracked artifacts under `build/guard-debug/`;
- CI artifact upload wiring only if existing CI cannot preserve the bundle and the change is strictly artifact handling.

## Forbidden

- `app/src/main/**` production Kotlin changes;
- DB ownership, structural, source-root, or time policy semantic changes;
- any baseline change, especially:
  - `config/baselines/db_access_v2.json`;
  - legacy DB baseline files;
  - ratchet baseline updates;
- time exception expansion;
- Gradle task semantics;
- workflow behavior changes unrelated to artifact preservation;
- registry/suite command canonicalization; that belongs to GR-10;
- `--update-baseline`;
- suppressing, downgrading, or relabeling a finding to make capture look clean.

## Preservation checks

Before completion:

```bash
git diff --exit-code -- \
  app/src/main \
  config/baselines \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/production_source_roots.yml \
  config/guards/time_boundary_exceptions.yml \
  app/build.gradle.kts
```

Expected: no differences.

If a CI artifact-only change is necessary, review `.github/workflows/**` separately and prove it does not change a required command, timeout, guard mode, or failure handling.

---

# Preconditions and hard stops

## Required before implementation

1. Read:
   - `AGENTS.md`;
   - `CODEBASE_SEGMENTS.md`, `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md`, and `ENGINE_INTERACTION_MAP.md` if present;
   - `FINAL_CI_GUARD_ACCEPTANCE_GATE.md`;
   - current guard registry, suite runner, ratchet, DB CLI, and time CLI.
2. Record:
   - full `HEAD`;
   - `HEAD^{tree}`;
   - explicit base ref/SHA;
   - merge-base SHA;
   - current branch;
   - clean-tree status;
   - relevant policy/baseline hashes.
3. Confirm full Git history is available; no shallow-history comparison.
4. Confirm no other agent owns Gradle.
5. Confirm sufficient disk space for two static-suite bundles and Gradle logs.

Repository rules require strict mode for guard work, minimal diffs, exact test reporting, and one Gradle owner at a time. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/AGENTS.md))

## Stop immediately if

- `git status --porcelain=v1` is non-empty;
- target SHA is abbreviated or does not equal current `HEAD`;
- base SHA is unspecified, abbreviated, or cannot be resolved;
- merge-base cannot be calculated;
- output directory already contains evidence for a different SHA;
- the capture tool would invoke shell strings instead of tokenized argv;
- a proposed “fix” touches product code, policy, exceptions, or baselines;
- another Gradle process/agent owns compilation;
- generated reports cannot be parsed or validated.

A failure to capture evidence is a **BLOCKED** result, not a green result.

---

# Deliverables

## 1. One canonical capture entry point

First inspect whether the branch already contains an equivalent reviewed GR-00/evidence-capture tool.

- If it exists and satisfies this plan, extend it minimally.
- If it does not, add:

```text
scripts/ci/capture_guard_evidence.py
scripts/ci/test_capture_guard_evidence.py
```

Suggested invocation:

```bash
python3 scripts/ci/capture_guard_evidence.py \
  --root . \
  --target-sha "$TARGET_SHA" \
  --base-ref "$BASE_SHA" \
  --output-dir "build/guard-debug/gate-00r/$TARGET_SHA/run-01" \
  --run-id run-01 \
  --include-gradle
```

The runner must require full SHA values and reject implicit “current branch” or “latest” behavior.

## 2. Evidence bundle schema

For each run, create:

```text
build/guard-debug/gate-00r/<target-sha>/<run-id>/
  manifest.json
  preflight.json
  inputs.sha256
  commands/
    <command-id>.argv.json
    <command-id>.stdout.log
    <command-id>.stderr.log
    <command-id>.result.json
  reports/
    db-inventory.json
    db-findings.json
    time-direct.txt
    static-suite/
  semantic-summary.json
  artifact-sha256.txt
  evidence-summary.md
```

Do not commit `build/` output. Upload it as a CI artifact or retain it externally with the exact SHA in its name.

### `preflight.json` minimum fields

```text
schemaVersion
targetSha
targetTreeSha
baseSha
mergeBaseSha
branch
workingTreeClean
captureStartedUtc
captureCompletedUtc
pythonExecutable
pythonVersion
gradleVersionOrUnavailable
locale
timezone
osIdentifier
```

Do not store usernames, home paths, tokens, secrets, environment dumps, raw source, or financial/user data.

### `inputs.sha256` minimum files

```text
scripts/ci/guard_registry.py
scripts/ci/run_static_guard_suite.py
scripts/ci/guard_ratchet.py
scripts/verify_db_access_boundaries.py
scripts/verify_time_boundaries.py
config/baselines/db_access_v2.json
config/guards/db_ownership_policy.yml
config/guards/db_structural_exceptions.yml
config/guards/db_structural_exceptions_expected_methods.yml
config/guards/production_source_roots.yml
config/guards/time_boundary_exceptions.yml
app/build.gradle.kts
gradlew
```

Include every file actually passed as a policy, baseline, allowlist, manifest, or command implementation input.

## 3. Semantic comparison output

`semantic-summary.json` must contain a canonical projection of each run.

It must retain:
- target/base/merge-base/tree SHA;
- command identifier;
- exact tokenized argv;
- exit code;
- classification;
- trusted status where applicable;
- DB finding fingerprints;
- DB diagnostics, including advisory/blocking classification;
- DB policy/root/baseline identifiers;
- time finding lines and count;
- static-suite guard name/mode/exit/outcome;
- Gradle task outcome;
- hashes of raw artifacts.

It may ignore only explicitly volatile fields:
- wall-clock timestamps;
- elapsed duration;
- generated log path prefixes;
- cache/download progress;
- run-directory names.

It must **never** ignore:
- a finding;
- fingerprint;
- diagnostic code;
- trust state;
- exit code;
- guard mode;
- child argv;
- baseline hash;
- policy hash;
- source-root hash.

---

# Required capture behavior

## Capture runner safety rules

1. Use `subprocess.run([...])`, never `shell=True`.
2. Write an argv JSON file before each child command runs.
3. Capture stdout/stderr separately.
4. Continue to later independent checks after a child exit `1` or `2`; preflight failures remain hard stops.
5. Never reinterpret `2` as `1`.
6. Never use `|| true`, `continue-on-error`, or a shell pipeline that loses the child exit code.
7. Mark timeout, executable-not-found, malformed report, or parser crash as infrastructure failure.
8. Make output writes atomic.
9. Refuse output paths outside the chosen output root.
10. Refuse to overwrite nonempty run directories.

---

# Exact capture sequence

## Step 1 — Freeze identity

```bash
git status --porcelain=v1
git rev-parse HEAD
git rev-parse HEAD^{tree}
git rev-parse "$BASE_SHA"
git merge-base HEAD "$BASE_SHA"
git branch --show-current
git diff --check
```

Required:
- clean status;
- all SHAs are full 40-character values;
- target tree and merge-base recorded.

## Step 2 — Capture tool self-tests

Before using the tool against the repository:

```bash
python3 -m pytest \
  scripts/ci/test_capture_guard_evidence.py \
  -v --tb=short
```

Required test coverage:

1. abbreviated target SHA rejected;
2. target SHA mismatch with `HEAD` rejected;
3. dirty tree rejected;
4. missing/unresolvable base rejected;
5. merge-base recorded correctly;
6. tokenized argv preserved exactly;
7. no shell invocation;
8. exit `0`, `1`, and `2` classified correctly;
9. timeout/executable-missing classified as infrastructure;
10. required artifact missing fails capture;
11. malformed DB JSON fails capture;
12. semantic comparator catches a changed finding;
13. semantic comparator catches a changed diagnostic/trust state;
14. semantic comparator catches a changed child argv;
15. semantic comparator ignores only approved volatile fields;
16. artifact hash manifest is deterministic;
17. output collision rejected;
18. no absolute local paths or environment secrets leak into summary files.

## Step 3 — Run direct Python guard evidence

Use the capture runner’s current interpreter (`sys.executable`) for direct Python commands.

At minimum capture:

```bash
python3 scripts/ci/verify_guard_registry.py .
```

```bash
python3 scripts/verify_time_boundaries.py \
  --root . \
  --allowlist config/guards/time_boundary_exceptions.yml \
  --fail-on-violation
```

```bash
python3 -m pytest \
  scripts/test_verify_time_boundaries.py \
  -v --tb=short
```

The time guard is strict-zero: it detects direct platform clock APIs, has no baseline, and treats malformed/stale exceptions as exit `2`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

## Step 4 — Capture DB inventory before DB enforcement

Run inventory-only first. Use actual supported current CLI flags discovered from `--help`; serialize the final argv in the evidence bundle.

Expected shape:

```bash
python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/gate-00r/.../reports/db-inventory.json \
  --dump-room-mutators build/guard-debug/gate-00r/.../reports/room-mutators.json
```

Interpretation:

| Result | Meaning | Route |
|---|---|---|
| `0`, trusted, no blocking diagnostics | inventory can support DB scanning | continue |
| `1` | unexpected for inventory-only | inspect as tool defect |
| `2` | root/inventory/config infrastructure defect | stop DB triage; repair guard infrastructure |

## Step 5 — Capture normal authoritative DB scan

Run the canonical current CLI with a findings output file.

```bash
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gate-00r/.../reports/db-findings.json
```

Record:
- exit code;
- `trusted`;
- policy mode/schema;
- scanner protocol/version;
- finding count and ordered fingerprints;
- diagnostics and each advisory/blocking marker;
- active policy/root-manifest/baseline hashes.

The active DB scanner is v2-only: it validates roots, Room inventory, v2 policy loading, exact source evidence, structural validation, D4 discovery, and then emits a protocol-v2 report. A blocking pre-scan failure must be exit `2`; advisory scanner diagnostics must remain visible. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_db_access_boundaries.py))

Interpretation:

| Result | Required next action |
|---|---|
| `0`, trusted | DB is currently clean; preserve empty baseline |
| `1`, trusted | create small GR-08q finding batches; never baseline |
| `2`, untrusted | stop DB triage; repair source-root/policy/evidence/scanner/control-plane owner |
| trusted but semantic results vary between runs | treat as determinism/infrastructure defect |

## Step 6 — Capture the actual static suite

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/gate-00r/.../reports/static-suite
```

Do not reconstruct the DB ratchet child argv manually. Preserve:
- suite `summary.json`;
- suite `summary.md`;
- every guard log;
- DB ratchet log;
- derived timeout information;
- command argv as actually executed.

The current suite’s own manifest includes a time blocking command and a DB ratchet command. Capture the real result first; command consolidation is a later GR-10 task. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/run_static_guard_suite.py))

## Step 7 — Compare direct vs suite DB authority

Extract and compare:
- direct DB CLI evidence;
- DB ratchet child report;
- suite DB log;
- baseline hash;
- policy/root-manifest hashes;
- child argv.

A difference in policy, root scope, trust, report protocol, or findings is a **control-plane discrepancy**, not a product-code result.

Do not fix it in GATE-00R. Record it and route it to GR-10A or the specific scanner/ratchet owner.

## Step 8 — Gradle evidence, sequential only

Only after Python evidence is captured and only with explicit Gradle ownership:

```bash
./gradlew :app:compileDebugKotlin \
  --no-daemon --stacktrace --console=plain
```

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

Capture each in a distinct command log.

Do not run Gradle commands in parallel. If Gradle cannot be run because another agent owns it, record:

```text
GRADLE STATUS: NOT RUN — ownership unavailable
```

That is incomplete evidence, not a passing result. Repository rules require focused sequential Gradle work and recorded log paths. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/AGENTS.md))

## Step 9 — Repeat the complete evidence sequence

Run all mandatory Python evidence again in `run-02`.

If Gradle was run in `run-01`, run the same required Gradle commands again in `run-02`, sequentially.

The acceptance contract requires two consecutive complete passing runs for final acceptance; GATE-00R uses the same principle to establish reproducibility even when the outcome is currently RED or BLOCKED. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## Step 10 — Compare runs

Required equalities:

```text
target SHA / tree SHA
base SHA / merge-base SHA
input hash manifest
time direct result
time guard-test result
DB inventory trusted semantic result
DB normal trusted semantic result
DB fingerprints
DB diagnostics and advisory markers
static-suite per-guard semantic result
DB ratchet semantic result
Gradle task result, if run
```

If any semantic item differs, record the smallest exact difference and classify the result as:

```text
EVIDENCE STATUS: INCOMPLETE — NONDETERMINISTIC OR DRIFTING
```

---

# Required result matrix

| Observation | Classification | Next PR |
|---|---|---|
| Time guard `1` | real direct-clock finding(s) | TIME-01 |
| Time guard `2` | time guard/policy/source failure | TIME-01 only if time-owned; otherwise guard-infra repair |
| DB `1`, trusted | real DB findings | GR-08q…n |
| DB `2` | DB scanner/policy/root/evidence failure | owning DB repair, not GR-08 |
| DB direct vs suite mismatch | command authority drift | GR-10A |
| Static suite `2` | suite/runtime/timeout/missing tool | GR-10A or suite repair |
| Gradle DB task differs from Python DB result | Gradle control-plane drift | GR-10A / Gradle task repair |
| Compile fails after time migration | production regression | TIME-01 |
| All evidence clean and identical | current facts established | TIME-01 validation may still proceed; then GR-10A |

---

# Definition of done

GATE-00R is complete only when:

- all evidence is tied to one exact target SHA;
- base and merge-base are recorded;
- checkout was clean before each run;
- two run bundles have matching semantic results;
- raw logs, structured reports, and hashes are preserved;
- direct, ratchet, suite, and Gradle outcomes are separately visible;
- advisory DB diagnostics are counted, not hidden;
- no product code, policy, allowlist, baseline, or guard severity changed;
- the next PR is chosen from actual evidence, not historical claims.

## Required completion report

```text
PR: GATE-00R
START SHA:
TARGET SHA:
TARGET TREE SHA:
BASE SHA:
MERGE-BASE SHA:
WORKING TREE CLEAN: yes/no

CAPTURE TOOL:
CAPTURE TOOL TESTS:

RUN-01 SEMANTIC DIGEST:
RUN-02 SEMANTIC DIGEST:
REPRODUCIBLE: yes/no

TIME DIRECT EXIT:
TIME FINDING COUNT:
TIME GUARD TEST RESULT:

DB INVENTORY EXIT / TRUST:
DB NORMAL EXIT / TRUST:
DB FINDING COUNT:
DB ADVISORY DIAGNOSTIC COUNT:
DB BLOCKING DIAGNOSTIC COUNT:
DB RATC HET RESULT:
DB BASELINE SHA:
ACTIVE DB POLICY SHA:
SOURCE-ROOT MANIFEST SHA:

STATIC SUITE EXIT:
STATIC SUITE FAILED GUARDS:
STATIC SUITE INFRASTRUCTURE ERRORS:

COMPILE DEBUG KOTLIN:
GRADLE DB TASK:
GRADLE NOT RUN REASON, IF ANY:

PRODUCTION KOTLIN CHANGED: no
TIME EXCEPTIONS CHANGED: no
DB POLICY CHANGED: no
BASELINE CHANGED: no

EVIDENCE STATUS: COMPLETE | INCOMPLETE
CURRENT STATE: CLEAN | VIOLATIONS | BLOCKED | MIXED
NEXT PR:
```