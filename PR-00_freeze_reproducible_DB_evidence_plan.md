# PR-GR-00 — Freeze reproducible DB evidence

## Agent mission

Create a diagnostic-only, reproducible evidence workflow for the DB guard at one exact commit.

This PR is successful when it produces a complete evidence bundle that lets a reviewer answer:

- Which exact Git SHA was tested?
- Was the checkout clean before execution?
- Which guard, policy, baseline, scanner, ratchet, Gradle, and workflow inputs were used?
- Which commands ran?
- What exit code, duration, report schema, trust status, diagnostic codes, and report hashes resulted?
- Can a second run from the same clean SHA reproduce the same semantic results?

This PR must **not** make the DB guard green.

The current DB CLI is expected to be blocked before D4 scanning because the active policy is incomplete for v2. Capturing that state truthfully is the deliverable. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

---

## Hard scope boundary

### Allowed changes

Create or modify only:

- `scripts/ci/capture_db_guard_evidence.py`
- `scripts/ci/test_capture_db_guard_evidence.py`
- `docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`
- `docs/ci/DB_GUARD_HARDENING_LEDGER.md`
- narrowly related documentation or test fixtures

The evidence output itself belongs under ignored `build/guard-evidence/`; do not commit raw logs, local paths, or generated reports.

### Forbidden changes

Do not modify:

- `config/baselines/db_access.json`
- `config/guards/db_ownership_policy.yml`
- `config/guards/db_ownership_policy.signatures.candidate.yml`
- structural exception policy / structural manifest / raw-query policy
- production Kotlin or Room schemas
- Gradle guard behavior
- workflow trigger behavior
- ratchet semantics
- scanner semantics
- allowlists

Do not invoke:

- `guard_ratchet.py --update-baseline`
- any baseline proposal command against the active baseline
- a policy migration command whose output overwrites active policy
- bulk source formatting or unrelated cleanup

The recovery directive explicitly prohibits blind baseline or allowlist expansion and requires preservation of command output and SHA evidence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/CI_GUARDRAILS_RECOVERY_DIRECTIVE.md))

---

## Deliverables

### 1. Diagnostic capture tool

Create `scripts/ci/capture_db_guard_evidence.py`.

It must:

- run a fixed, declared command matrix;
- capture stdout and stderr together for each command;
- preserve the child exit code;
- continue after expected nonzero commands;
- write outputs atomically;
- never use shell-string execution;
- use repository-relative paths in metadata;
- redact environment values except explicitly allowlisted version fields;
- generate a machine-readable `evidence.json`;
- generate a human-readable `summary.md`;
- calculate SHA-256 for every listed input and output artifact;
- reject a dirty checkout by default;
- support an explicit `--allow-dirty` option only for local investigation, and mark that evidence untrusted.

The capture tool is not an architecture guard. Do not add it to the guard registry.

### 2. Evidence protocol documentation

Create `docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`.

Document:

- output directory layout;
- evidence JSON schema;
- semantic-vs-volatile fields;
- command matrix;
- trusted-state definition;
- redaction policy;
- how CI uploads artifacts;
- how a reviewer compares two evidence bundles;
- why a checked-in prose ledger must never pretend to prove a later SHA.

### 3. Ledger template

Create `docs/ci/DB_GUARD_HARDENING_LEDGER.md`.

It is a durable template/index, not a substitute for raw artifacts. Each entry must include:

- evidence SHA;
- tree SHA;
- branch;
- capture date;
- clean/dirty state;
- platform and interpreter versions;
- input-manifest hash;
- command IDs;
- exit codes;
- first controlled diagnostic;
- trusted-state;
- artifact location or CI run reference;
- reviewer classification;
- next PR.

Do not insert fabricated “green” or “done” statements.

### 4. Tests

Create `scripts/ci/test_capture_db_guard_evidence.py`.

Tests must use temporary fake commands and files; they must not invoke Gradle or scan the real repository.

---

## Required evidence bundle layout

Use a layout equivalent to:

```text
build/guard-evidence/<full-sha>/<run-id>/
  evidence.json
  summary.md
  input-manifest.json
  input-sha256.txt
  environment.json
  git-state.json
  commands/
    00-registry.log
    01-focused-python-tests.log
    02-room-inventory.log
    02-room-inventory.findings.json
    02-room-mutators.json
    03-db-cli.log
    03-db-cli.findings.json
    04-db-ratchet.log
    05-static-suite.log
    05-static-suite/
    06-gradle-db.log
    07-gradle-task-graph.log
  output-sha256.txt
```

`run-id` may contain a timestamp, but the semantic comparison file must exclude timestamps, duration, temporary paths, and machine-specific absolute paths.

---

## Required input manifest

Hash at least these inputs:

- current Git commit and tree;
- `scripts/verify_db_access_boundaries.py`;
- all tracked Python files beneath `scripts/db_guard/`;
- `scripts/ci/guard_ratchet.py`;
- `scripts/ci/guard_registry.py`;
- `scripts/ci/run_static_guard_suite.py`;
- `scripts/ci/guard_findings.py`;
- `config/baselines/db_access.json`;
- all DB policy and structural-policy files beneath `config/guards/`;
- `app/build.gradle.kts`;
- `.github/workflows/ci.yml`;
- `settings.gradle.kts`.

For each input record:

- repository-relative path;
- Git blob ID when tracked;
- SHA-256 of bytes;
- size in bytes.

The current control plane spans registry, static-suite manifest, ratchet, CLI, policy, baseline, Gradle wrapper, and workflow; that breadth is why the manifest is mandatory. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/ci/guard_registry.py))

---

## Command matrix

The capture tool must execute these commands in this order.

### A. Preflight

Record:

```bash
git rev-parse HEAD
git rev-parse HEAD^{tree}
git status --porcelain=v1
git diff --name-only
git log --oneline -20
python --version
python3 --version
java -version
./gradlew --version
```

Record whether `python` exists separately from `python3`. The current static suite invokes the DB child command as `python`, so this is a real portability diagnostic. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/ci/run_static_guard_suite.py))

### B. Registry validation

```bash
python3 scripts/ci/verify_guard_registry.py
```

### C. Focused DB Python test suite

```bash
python3 -m pytest \
  scripts/ci/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  scripts/test_kotlin_callable_parser.py \
  scripts/test_migrate_db_policy_signatures.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_sql_classifier.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short
```

If a named test file no longer exists at the tested SHA, the tool must record that as infrastructure failure rather than silently dropping it.

### D. Inventory-only run

```bash
python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output <bundle>/02-room-inventory.findings.json \
  --dump-room-mutators <bundle>/02-room-mutators.json
```

Expected target state:

- exit `0`;
- v2 report exists;
- no diagnostics;
- `trusted: true`.

If inventory-only fails, stop the program after PR-00: PR-01 must not begin until production-root and Room inventory reliability are repaired.

### E. Normal DB CLI

```bash
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --ownership-policy config/guards/db_ownership_policy.yml \
  --structural-exceptions config/guards/db_structural_exceptions.yml \
  --structural-manifest config/guards/db_structural_exceptions_expected_methods.yml \
  --findings-output <bundle>/03-db-cli.findings.json
```

At SHA `9b97e79`, the anticipated result is exit `2`, an untrusted report, and a controlled policy/source-evidence diagnostic because active entries lack v2 signatures. Record the result; do not edit policy to make it differ. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

### F. DB ratchet

Invoke the ratchet with tokenized child-command arguments, never a shell command string. Use the same effective policy, structural, and manifest paths as the static suite.

The ratchet result must be captured even when it exits nonzero. It is expected to be blocked by the child CLI initially; later, after policy activation, it will expose the separate baseline-format issue.

### G. Full static suite

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir <bundle>/05-static-suite
```

Capture the suite exit code and preserve its per-guard logs and summary files.

### H. Gradle wrapper and wiring

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace

./gradlew :app:check --dry-run \
  --no-daemon
```

The first is expected to fail while DB infrastructure is blocked; the second captures actual task wiring without running the entire application test suite.

The Gradle wrapper itself is already fail-closed for missing DB inputs and ratchet infrastructure; do not alter it in this PR. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/app/build.gradle.kts))

---

## Capture-tool behavior requirements

### Exit behavior

The capture tool itself returns:

- `0` only when evidence capture completed and all required artifacts are present;
- `2` when the capture process itself is incomplete, corrupt, dirty, or missing an expected artifact.

It must **not** return `1` merely because the guarded DB CLI returns `1` or `2`; those are observations stored in `evidence.json`.

### Evidence JSON requirements

For each command record:

- stable command ID;
- argv as an array;
- working directory as repository-relative `.` only;
- start/end UTC timestamps;
- elapsed milliseconds;
- exit code;
- combined-log path and SHA-256;
- structured-report path and SHA-256 when applicable;
- parsed report schema version;
- parsed report trusted state;
- parsed diagnostic codes;
- parsed finding count;
- parser error if report is absent/invalid.

Never store absolute temp paths, environment secrets, or raw exception text.

### Atomicity

Write every JSON, Markdown, and checksum file to a sibling temporary file, flush/fsync where supported, then replace atomically.

### Determinism

Create `semantic-summary.json` excluding:

- timestamps;
- durations;
- machine paths;
- Gradle cache paths;
- transient temp file names.

Two clean runs at the same SHA must have equal semantic summaries. Logs may differ in timing only.

---

## Test plan for the capture tool

Add tests for:

1. clean checkout succeeds;
2. dirty checkout fails by default;
3. `--allow-dirty` captures but marks evidence untrusted;
4. command exit `0`, `1`, and `2` are recorded exactly;
5. unexpected process launch failure creates capture exit `2`;
6. missing required log/report causes capture exit `2`;
7. report with invalid JSON is preserved as artifact but marked parser failure;
8. report with v2 diagnostics parses correctly;
9. output paths are repository-relative;
10. no absolute temp path leaks into `evidence.json`;
11. input hashes change when input bytes change;
12. command output is written atomically;
13. semantic summaries from two fixture runs compare equal;
14. policy/baseline preservation checker fails if any forbidden file changed;
15. command argv is stored as an array, not shell text.

---

## Mandatory preservation checks

Run before and after capture:

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/db_raw_query_classification.yml

git diff --exit-code -- app/src/main
```

Also inspect the full PR diff. Permitted production changes are **none**.

---

## Completion checks after implementation

### Required automated checks

```bash
python3 -m pytest scripts/ci/test_capture_db_guard_evidence.py -v --tb=short

python3 -m pytest \
  scripts/ci/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short

python3 scripts/ci/capture_db_guard_evidence.py \
  --root . \
  --out build/guard-evidence/$(git rev-parse HEAD)/run-1

python3 scripts/ci/capture_db_guard_evidence.py \
  --root . \
  --out build/guard-evidence/$(git rev-parse HEAD)/run-2
```

### Required manual checks

1. Both capture directories have complete artifact sets.
2. Both input manifests identify the same commit/tree and file hashes.
3. Both semantic summaries are identical.
4. The normal DB CLI report is untrusted and has a controlled diagnostic, not fabricated findings.
5. Inventory-only output is reviewed independently.
6. No active policy, structural policy, baseline, production source, Gradle, or workflow file changed.
7. The PR description includes the evidence SHA, bundle hashes, exit matrix, and explicit statement: “DB gate remains blocked pending GR-01 onward.”

### Definition of done

GR-00 is done only when:

- an exact, clean SHA has two reproducible evidence bundles;
- the ledger protocol exists;
- all capture-tool tests pass;
- no forbidden file changed;
- the PR does not claim a green DB guard;
- a reviewer can reproduce every observed status from recorded argv and input hashes.