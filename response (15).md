I reviewed the supplied SHA `c7aace49…` and its recent guardrail history. Verdict: **the direction is sound and GR-00–GR-04 were substantially implemented, but this is still RED/BLOCKED—not a trustworthy green DB gate.** The last recorded full sweep was at parent `8b4ef1f` with 10 failures; `c7aace4` needs an exact-SHA rerun. I also found two corrective gaps before GR-05: the evidence tool is pinned to the old SHA, and v2 path validation still hard-codes the `app/src/main` topology. ([github.com](https://github.com/panospao7/Cost-agregator/commits/c7aace49a469deebcaaf6d121e9fc38ac3a68df2))

<guardrail_verdict_and_next_prs.md>
# Guardrail reassessment and next PR sequence
## Exact review target

**Reviewed commit:** `c7aace49a469deebcaaf6d121e9fc38ac3a68df2`  
**Commit date:** August 25, 2026  
**Review type:** source, architecture/control-plane documentation, and recent-commit review. I did **not** execute the repository or inspect a private CI artifact for this SHA.

## Executive verdict

**Verdict: RED / BLOCKED for trustworthy DB enforcement; AMBER for implementation progress.**

You did follow the intended order. The recent history shows the major GR milestones landed serially:

| Milestone | Commit | Status |
|---|---:|---|
| GR-00 reproducible DB evidence tooling | `cf07b04` | Implemented, needs corrective reuse work |
| GR-01 typed policy-v2 model | `ee28643` | Implemented, not authoritative yet |
| GR-02 v2 candidate generation | `ce02c6d` | Implemented, candidate coverage incomplete by design |
| GR-03 production source-root contract | `9d1764f` | Implemented, contains a remaining topology-authority gap |
| GR-04 structural-count decoupling | `247b21c` | Implemented |
| Validation repair loop | `882b1bd` through `c7aace4` | Ongoing; exact-tip validation not yet recorded |

The commit history confirms this was a coherent multi-commit guardrail program, not random churn. ([github.com](https://github.com/panospao7/Cost-agregator/commits/c7aace49a469deebcaaf6d121e9fc38ac3a68df2))

The work is **not accepted yet** because the program’s own acceptance specification treats missing proof, infrastructure failure, malformed reports, and skipped verification as failure—not a pass. The recovery directive likewise prohibits blind baseline/allowlist expansion and requires preserved evidence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/CI_GUARDRAILS_RECOVERY_DIRECTIVE.md))

---

# What has actually been achieved

## GR-00 — Reproducible DB evidence

**Verdict: PARTIAL; good security intent, but not reusable at the current SHA.**

The evidence tool is substantial: it uses argv execution rather than shell strings, hashes inputs/outputs, records command results, validates reports, writes atomically, rejects dirty trees by default, and generates a semantic summary. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/ci/capture_db_guard_evidence.py))

However, two issues prevent treating it as the current debugging authority:

1. It hard-pins `TARGET_SHA` to `9b97e797…`, while your reviewed work is now at `c7aace49…`. Its own source says a mismatched HEAD must fail closed. That makes the checked-in tool unsuitable for producing trusted current-SHA evidence without a corrective change. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/ci/capture_db_guard_evidence.py))
2. It declares a 20,000-character persisted child-output limit. A diagnostic evidence tool must either preserve a complete log or explicitly fail the capture as incomplete when a configured limit is exceeded; it must never let a truncated log masquerade as complete evidence. The current behavior must be tested and made explicit. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/ci/capture_db_guard_evidence.py))

**Conclusion:** GR-00 is a strong implementation foundation but is not complete as a durable workflow.

## GR-01 — Authoritative policy-v2 model

**Verdict: IMPLEMENTED AS A TRANSITIONAL MODEL; correctly not activated.**

The v2 loader enforces `schemaVersion: 2`, exact required fields, rejects unknown and legacy v1 fields, and requires `ownerFqcn`, `kind`, receiver, ordered parameter types, DAO accessor/FQCN, operation, barrier mode, and metadata. That is materially aligned with the original safety goal: no simple-class authorization or implicit v1 upgrade path. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/db_guard/policy_v2_loader.py))

The transitional DB ownership document accurately says v1 remains active, v2 is not wired into enforcement, and GR-07—not GR-01—owns activation. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/docs/DB_WRITE_OWNERSHIP.md))

**Conclusion:** Do not rewrite GR-01. Preserve it and repair its integration boundaries.

## GR-02 — v2 candidate generation

**Verdict: SUCCESSFUL ON SHAPE; INTENTIONALLY INCOMPLETE ON COVERAGE.**

The checked-in candidate is genuine v2 YAML and has nine exact-identity entries, including FQCN owner identity, callable kind, ordered types, DAO accessor, DAO FQCN, operation, and barrier metadata. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/config/guards/db_ownership_policy.signatures.candidate.yml))

The last recorded migration validation, at `247b21c`, reported 99 legacy rows, 9 resolved, and 90 unresolved. That is not a failure of GR-02’s mission: its job was to produce safe candidate evidence and visible debt, not invent authorization for unresolved rows. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/docs/ci/GR00-GR04_validation_findings.md))

**Conclusion:** Candidate schema is ready; candidate coverage is not. Do not promote it or update the active baseline.

## GR-03 — Production source roots

**Verdict: MOSTLY IMPLEMENTED, BUT NOT YET A SINGLE AUTHORITY.**

The manifest exists and accurately declares the current real production root as `:app`, `main`, `app/src/main/java`. The documentation says all DB guard subsystems are intended to use this manifest through `source_roots.py`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/config/guards/production_source_roots.yml))

But there is a genuine remaining design breach:

- `kotlin_callable_parser.canonical_source_path()` still requires a path beginning `app/src/main`.
- `policy_v2_loader.py` directly calls that function while validating policy paths.

So a future valid manifest entry such as `:feature` / `feature/src/main/kotlin/...` would still be rejected by a private parser-level topology rule. This violates GR-03’s intended contract: generic path syntax belongs in generic parsing; production-root membership belongs exclusively in `source_roots.py`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/kotlin_callable_parser.py))

**Conclusion:** Correct this before GR-05. Otherwise “multi-root support” is only partial.

## GR-04 — Decouple ownership count from structural manifest

**Verdict: IMPLEMENTED; validation closure still pending.**

The current DB ownership documentation states that structural exceptions retain their 62-entry structural pin, while ownership cardinality is no longer structural authorization evidence; old `ownership_entries` metadata fails closed. This matches the intended GR-04 correction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/docs/DB_WRITE_OWNERSHIP.md))

**Conclusion:** Do not revisit its architectural choice. Its remaining job is regression proof, not redesign.

---

# Why the branch is currently failing

At `8b4ef1f` on August 24, 2026, the recorded full Python sweep was:

- **10 failed**
- **2,232 passed**
- **23 skipped**

The residual failures were grouped as:

1. structural findings sometimes emitted without trustworthy callable identity;
2. structural findings sometimes disappeared where fully signed findings were expected;
3. property initializer/getter/setter fixture scans became untrusted;
4. three Kotlin callable parser error-contract failures. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/docs/ci/GR00-GR04_validation_findings.md))

Your current commit `c7aace4` changes shared unresolved-symbol logic and parser arrow handling. It may fix some of those failures, because the scanner imports that shared unresolved-symbol helper, but the validation ledger records no full rerun for `c7aace4`. Do **not** assume the ten failures are gone until you run the exact commands below. ([github.com](https://github.com/panospao7/Cost-agregator/commit/c7aace49a469deebcaaf6d121e9fc38ac3a68df2))

---

# Immediate debugging order — do this before another policy or baseline change

Use a clean worktree. Do **not** edit:

- `config/baselines/db_access.json`
- active ownership policy
- candidate policy
- structural exceptions
- production Kotlin
- Gradle/workflow wiring

The recovery directive explicitly prohibits baseline/allowlist expansion as a way to obtain green. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/CI_GUARDRAILS_RECOVERY_DIRECTIVE.md))

## 1. Freeze the exact state

```text
git status --porcelain=v1
git rev-parse HEAD
git rev-parse HEAD^{tree}
git log --oneline -30
python --version
```

If `git status --porcelain=v1` is non-empty, stop. Either commit/stash intentional work or use a separate clean worktree.

## 2. Run the residual cluster first

```text
python -m pytest ^
  scripts/test_db_guard_scanner_d4.py ^
  scripts/test_verify_db_access_v2.py ^
  scripts/test_kotlin_callable_parser.py ^
  -v --tb=short
```

Classify every failure as exactly one of:

- real scanner/parser defect;
- stale fixture/assertion after a deliberate contract change;
- unsupported source shape that must fail closed;
- environment/platform behavior;
- unrelated regression.

Do not update an assertion merely because it is red. First inspect the serialized report, diagnostic codes, trust state, and exact callable symbol.

## 3. Run the full Python sweep only after the focused cluster is understood

```text
python -m pytest scripts -q
```

Record exact fail/pass/skip counts for `c7aace4`; do not reuse the `8b4ef1f` numbers.

## 4. Re-establish the DB control-plane state

```text
python scripts/ci/verify_guard_registry.py

python scripts/ci/verify_production_source_roots.py ^
  --root . ^
  --manifest config/guards/production_source_roots.yml

python scripts/verify_db_access_boundaries.py ^
  --inventory-only ^
  --findings-output build/guard-debug/c7/inventory.json ^
  --dump-room-mutators build/guard-debug/c7/room-mutators.json

python scripts/migrate_db_policy_signatures.py ^
  --check ^
  --policy config/guards/db_ownership_policy.yml ^
  --report build/guard-debug/c7/migration.json
```

## 5. Observe the active gate; do not “fix” it yet

```text
python scripts/verify_db_access_boundaries.py ^
  --fail-on-violation ^
  --ownership-policy config/guards/db_ownership_policy.yml ^
  --structural-exceptions config/guards/db_structural_exceptions.yml ^
  --structural-manifest config/guards/db_structural_exceptions_expected_methods.yml ^
  --raw-query-policy config/guards/db_raw_query_classification.yml ^
  --findings-output build/guard-debug/c7/active-db-gate.json
```

For this stage, an active-gate exit `2` with `DB_POLICY_SOURCE_EVIDENCE_INVALID` remains the expected transitional state. It is **not** a reason to modify policy or baseline. The previous validation recorded exactly that state. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/docs/ci/GR00-GR04_validation_findings.md))

## 6. Do not use the checked-in evidence tool for c7 as-is

The current tool is pinned to `9b97e797…`; use the first corrective PR below before relying on it as c7 evidence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/ci/capture_db_guard_evidence.py))

---

# Next three PRs

## PR-GR-00R — Make DB evidence capture reusable, complete, and run-pinned

### Mission

Repair GR-00 so trusted evidence can be captured for **any explicitly declared clean SHA**, including the post-PR SHA, without editing a checked-in constant.

This PR is diagnostic-only. It must not make the DB gate green or alter DB authorization.

### Why this PR is first

The current evidence capture implementation hard-codes `TARGET_SHA = 9b97e797…`; therefore it cannot act as a continuing evidence workflow for the current `c7aace4` line. It also has a bounded child-output setting that must be proven non-lossy or fail-closed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/ci/capture_db_guard_evidence.py))

### Preconditions

1. Start from a clean checkout.
2. Record start SHA, tree SHA, branch/ref, interpreter, OS, Java, and Gradle version.
3. Do not run Gradle concurrently with another agent.
4. Read the actual `capture_db_guard_evidence.py` before editing; preserve its existing atomic-write, hashing, redaction, and argv-only properties.
5. If the current focused residual suite is red, that is acceptable for this PR: capture records child failure. It must not hide it.

### Allowed changes

- `scripts/ci/capture_db_guard_evidence.py`
- `scripts/ci/test_capture_db_guard_evidence.py`
- `docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`
- `docs/ci/DB_GUARD_HARDENING_LEDGER.md`
- narrowly related evidence fixtures/tests/documentation

### Forbidden changes

- all policy, candidate, structural-exception, raw-query, and baseline files;
- all production Kotlin;
- scanner, parser, ratchet semantics, Gradle, workflow, registry, or static suite;
- baseline proposal/update commands;
- allowlists.

### Required design

#### A. Replace the permanent SHA lock with an explicit run pin

Delete the permanent `TARGET_SHA` authority.

Add:

```text
--expected-sha <40-lowercase-hex>
```

Rules:

1. It is mandatory for a trusted capture.
2. It must equal `git rev-parse HEAD` before any matrix command starts.
3. The tool must record both requested SHA and observed SHA.
4. It must record observed tree SHA.
5. Re-run HEAD/tree/status checks after the matrix.
6. If HEAD, tree, or clean status changes during capture, mark the bundle incomplete/untrusted and return capture exit `2`.
7. A mismatch must fail before any guard command is launched.
8. Do not derive “expected SHA” silently from HEAD; that is tautological. The caller must state it.

Expected use:

```text
$sha = (git rev-parse HEAD).Trim()
python scripts/ci/capture_db_guard_evidence.py `
  --root . `
  --expected-sha $sha `
  --out "build/guard-evidence/$sha/run-1"
```

#### B. Eliminate silent evidence truncation

The command log contract must be one of only two states:

| State | Required behavior |
|---|---|
| Complete | Entire combined stdout/stderr is captured, hashed, and marked complete |
| Too large / unreadable / interrupted | Capture is explicitly incomplete, has a controlled reason, and exits `2` |

Requirements:

1. Never return capture exit `0` with a silently truncated command log.
2. Stream child stdout/stderr together to an atomic temporary log file.
3. If a size cap is retained for safety, exceedance must produce a controlled `output-limit-exceeded` state and exit `2`.
4. Do not store raw log text inside `evidence.json` or `summary.md`.
5. Preserve path/secret redaction rules in metadata.
6. Add `logBytes`, `logComplete`, and optional controlled `logFailureCode` fields to command evidence.
7. Hash only the final, complete command artifact; do not hash a partial artifact as though it were valid evidence.

#### C. Preserve existing evidence semantics

Retain:

- argv arrays, never shell text;
- repository-relative metadata paths;
- atomic output files;
- child exit-code preservation;
- capture exit `0` even where observed guard child exits `1` or `2`;
- capture exit `2` for capture corruption/incompleteness;
- clean checkout default;
- `--allow-dirty` only as explicitly untrusted local evidence;
- deterministic semantic summaries.

#### D. Documentation changes

Document:

- required `--expected-sha`;
- pre/post capture Git-state checks;
- log completeness contract;
- output-limit behavior;
- distinction between “guard failed truthfully” and “capture failed/incomplete”;
- how to compare two same-SHA bundles;
- why raw logs may differ but semantic summaries must match.

### Required tests

Add or update tests for:

1. arbitrary valid SHA can be supplied through `--expected-sha`;
2. no hard-coded historical SHA remains authoritative;
3. mismatched expected SHA rejects before command execution;
4. invalid SHA syntax rejects;
5. post-capture HEAD/tree change returns `2`;
6. clean expected-SHA capture succeeds despite observed child exit `1` and `2`;
7. dirty capture rejects by default;
8. `--allow-dirty` marks evidence untrusted;
9. a small combined log is complete and hashable;
10. a large log is either fully preserved or fails capture explicitly—never “successful but truncated”;
11. evidence JSON contains no absolute temporary path;
12. command argv remains an array;
13. output files remain atomic under success and failure;
14. two fixture runs with same inputs have identical semantic summaries;
15. a missing report/log/required artifact returns `2`;
16. child invalid JSON is preserved as an artifact but marked parser failure;
17. active policy/baseline preservation checker still catches forbidden edits.

### Required post-completion checks

```text
python -m pytest scripts/ci/test_capture_db_guard_evidence.py -v --tb=short
python scripts/ci/verify_guard_registry.py
```

Then, from a **clean end-SHA checkout**:

```text
$sha = (git rev-parse HEAD).Trim()

python scripts/ci/capture_db_guard_evidence.py `
  --root . `
  --expected-sha $sha `
  --out "build/guard-evidence/$sha/run-1"

python scripts/ci/capture_db_guard_evidence.py `
  --root . `
  --expected-sha $sha `
  --out "build/guard-evidence/$sha/run-2"
```

Manual review:

1. Both capture commands exit `0`.
2. Both bundles have every required log/report/hash file.
3. Both declare the same SHA/tree/input-manifest hash.
4. Both semantic summaries are byte-identical.
5. If normal DB CLI/ratchet/Gradle are red, their child exit codes and reports are present—not hidden.
6. Every log is marked complete.
7. No active policy, baseline, candidate, structural policy, production Kotlin, Gradle, or workflow file changed.

### Definition of done

GR-00R is done when the evidence tool can truthfully capture the **actual end SHA** twice, not merely the historical SHA `9b97e797…`.

---

## PR-GR-03R — Finish source-root authority; remove private `app/src/main` policy topology

### Mission

Make `source_roots.py` the only authority for whether a repository-relative Kotlin file is an approved production source file.

The generic Kotlin parser may validate path syntax. It must not authorize a fixed `app/src/main` topology.

### Why this PR is second

The v2 loader imports `canonical_source_path()`, and that parser function still enforces an `app/src/main` prefix. That makes valid declared production paths from other modules impossible even though GR-03 introduced a multi-root manifest model. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/c7aace49a469deebcaaf6d121e9fc38ac3a68df2/scripts/kotlin_callable_parser.py))

### Preconditions

1. GR-00R merged and two trusted evidence bundles exist.
2. Checkout clean.
3. Current real topology is re-verified with the source-root meta-guard.
4. Do not add a real feature module merely to prove this behavior; use fixtures.

### Allowed changes

- `scripts/kotlin_callable_parser.py`
- `scripts/db_guard/source_roots.py`
- `scripts/db_guard/policy_v2_loader.py`
- `scripts/db_guard/policy_v2_evidence.py`
- `scripts/db_guard/policy_v2_candidate.py`
- `scripts/db_guard/scanner.py`
- legacy evidence/path adapters only where necessary
- source-root, v2 loader/evidence, candidate, scanner tests
- narrowly related documentation

### Forbidden changes

- actual source-root manifest contents unless the repository topology genuinely changed;
- active v1 policy;
- v2 candidate;
- baseline;
- structural exception data;
- production Kotlin;
- Gradle/workflow/registry redesign;
- v2 activation.

### Required architecture

#### A. Split syntax validation from authorization

Introduce two distinct concepts:

1. **Generic repository Kotlin path validation**
   - repository-relative POSIX syntax;
   - no absolute paths, Windows drives, UNC, backslashes, traversal, blank components;
   - Kotlin `.kt` file;
   - bounded depth/length;
   - does **not** require `app/src/main`.

2. **Declared production-root membership validation**
   - implemented only in `source_roots.py`;
   - receives repository root, loaded `SourceRootSet`, and generic canonical path;
   - proves the file lies under exactly one manifest root;
   - rejects undeclared, ambiguous, missing, unreadable, or escaping paths fail-closed.

The parser must not decide whether a path belongs to `:app`, `:feature`, or any future module.

#### B. Keep the v2 loader structurally pure

`load_policy_v2()` should validate document shape and generic safe Kotlin paths only.

Root membership must be checked by a root-aware validation/evidence stage that has actual repository and manifest context. Do not make the YAML loader guess project topology.

#### C. Remove hidden app-only behavior everywhere

Audit all call sites of:

- `canonical_source_path`;
- literal `app/src/main`;
- prefix checks such as `startswith("app/src/")`;
- parser-created synthetic source paths.

Requirements:

1. No executable DB authorization decision may require `app/src/main`.
2. The scanner must accept safe repository-relative diagnostic paths from any declared root, not just `app/src/...`.
3. A synthetic parser placeholder must never become an authorization identity.
4. The current checked-in candidate must still regenerate byte-identically.
5. Existing `:app` behavior must remain unchanged.

### Required implementation sequence

1. Add failing fixture tests for a declared `:feature` root at `feature/src/main/kotlin`.
2. Add generic-path tests showing that this path is syntactically valid.
3. Add root-membership tests showing it is rejected if absent from the manifest and accepted if declared.
4. Move all production-membership decisions into `source_roots.py`.
5. Update v2 loader/evidence/candidate/scanner call sites.
6. Audit parser synthetic-path construction and eliminate any use as policy identity.
7. Re-run current-repository candidate generation; it must not modify the tracked candidate.
8. Update docs only after code/tests prove the contract.

### Required tests

1. `app/src/main/java/...kt` remains valid.
2. `app/src/main/kotlin/...kt` can be valid when declared.
3. `feature/src/main/kotlin/...kt` is syntactically valid.
4. A declared feature root validates.
5. An undeclared feature root fails with a controlled source-root diagnostic.
6. Absolute, UNC, drive, back

:warning: The provider stream ended early, so this response may be incomplete.