# PR-GUARD-QA-00 — Guard contract and test-coverage audit

## Agent mission

Produce one complete, reviewed, exact-SHA inventory showing whether **every active guard and external guard-like enforcement path** has sufficient proof of correctness.

This is an audit and contract-registration PR. It must not pretend missing tests exist, downgrade a guard, change a baseline, or bulk-rewrite guard engines. Its outcome is:

1. register existing adequate coverage precisely;
2. identify verified gaps;
3. create small follow-up PR seeds for those gaps;
4. establish one machine-checkable definition of “guard test coverage complete.”

At the reference snapshot, the registry explicitly lists no dedicated test file for at least `source_provenance`, `lint_baseline_policy`, `privacy`, `event_writers`, and `money`. That does not prove those guards have no tests elsewhere, but it proves the registry does not currently point to dedicated evidence for them. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_registry.py))

## Why this PR is necessary

A guard can appear to work while lacking proof that it:

- detects a real violation;
- avoids false positives;
- rejects common bypasses;
- fails closed when malformed;
- produces correct subprocess exit codes;
- scans the real repository scope;
- remains deterministic;
- is actually invoked by the canonical suite/Gradle/CI path.

The acceptance contract requires every critical rule to have positive, negative, at least three adversarial fixtures, infrastructure-failure coverage, real-repository integration, and actual subprocess exit-code coverage. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

The audit must therefore be rule-oriented, not merely file-oriented. A Python test file existing beside a guard is not automatically sufficient evidence.

---

# Required order

Start only after:

```text
GATE-00R complete for the actual start SHA
GR-10A canonical registry → execution-plan ownership merged
GR-10B source-scope authority merged
GR-10D documentation/evidence truth sync merged
```

Recommended but not strictly required:

```text
GR-12 through GR-15 complete
```

Reason: QA-00 should audit the final active guard contracts where possible. If a structural/migration/release guard is still being redesigned, record it as `IN_FLIGHT_CONTRACT_CHANGE`, not as complete.

## Hard stops

Stop and report `BLOCKED` if:

- canonical registry/execution-plan loading fails;
- the working tree is dirty;
- a registered guard has no stable guard ID, owner, mode, or execution plan;
- a source scanner has no declared scope classification;
- an external enforcement path cannot be assigned a disposition;
- test evidence cannot be tied to exact source/test identifiers;
- another agent owns Gradle when an execution check is required;
- the audit would need to alter a guard’s detector/policy/baseline to obtain a passing result.

---

# Scope

## Allowed

- canonical registry metadata introduced by GR-10A;
- guard test-contract metadata;
- audit/report generators and validators;
- audit-specific fixtures proving the audit tool itself;
- documentation indexes and remediation manifests;
- narrowly related registry-validator tests;
- untracked `build/guard-debug/` evidence.

## Forbidden

```text
app/src/main/** production Kotlin changes
guard semantic/parser/scanner rewrites
DB policy or structural-policy changes
source-root manifest changes
time-exception changes
baseline updates or deletion without an explicit later owner
ratchet updates
CI severity changes
workflow required-check changes
bulk test rewrites for uncovered guards
```

## Preservation checks

```bash
git diff --exit-code -- \
  app/src/main \
  config/baselines \
  config/guards \
  app/build.gradle.kts \
  .github/workflows
```

Expected: no changes, except narrowly approved registry/test-contract metadata and audit artifacts outside `config/guards`.

---

# Definitions

| Term | Meaning |
|---|---|
| Guard | A registered enforcement rule executed through the GR-10A canonical plan. |
| External guard | A required enforcement path not in the static suite, such as release-artifact verification or migration proof. |
| Rule family | The individual rule IDs/semantic checks owned by a guard. |
| Positive fixture | Deliberately violating input that must produce a finding/exit `1`. |
| Negative fixture | Legal input that must pass/exit `0`. |
| Adversarial fixture | Bypass-shaped input intended to defeat a naive detector. |
| Infrastructure fixture | Missing/malformed/unreadable/ambiguous input that must exit `2`. |
| Real-repository integration | Execution against the actual checkout with safe expected assertions. |
| Subprocess test | Test that invokes the actual CLI process and verifies exit/result artifacts. |
| Determinism proof | Two runs with equal semantic results after excluding explicitly volatile fields. |
| Evidence pointer | Exact test path, test ID, fixture ID, command, and expected result. |

Do not use vague labels such as:

```text
covered
probably tested
manual verification
known good
same as another guard
N/A
```

---

# Required deliverables

## 1. Canonical guard-quality contract

Extend the canonical GR-10A registry schema. Do not create another independent guard list.

Each active guard must include a structured `qualityContract`, conceptually:

```yaml
qualityContract:
  owner: "@panospao7"
  ruleFamilies:
    - G-EXAMPLE-01
  sourceScopeType: production-kotlin-all
  enforcementClass: direct | ratchet | policy | external
  requiredInputs:
    policies: []
    allowlists: []
    baselines: []
  tests:
    positive: []
    negative: []
    adversarial: []
    infrastructure: []
    subprocess: []
    realRepository: []
    determinism: []
    ratchetSemantics: []
  coverageStatus: AUDITED_COMPLETE | AUDITED_GAP | IN_FLIGHT_CONTRACT_CHANGE
  documentationAnchor: docs/...
```

Rules:

1. Every item is an exact repository-relative path plus test identifier.
2. `tests: None` is not a valid final quality contract.
3. Shared test files are allowed, but must name exact test functions/classes.
4. A test may support multiple guards only when its fixture and assertions explicitly cover each guard.
5. A guard may mark an evidence category `NOT_APPLICABLE` only with a closed reason code.
6. `NOT_APPLICABLE` is invalid for:
   - positive;
   - negative;
   - infrastructure;
   - real-repository;
   - subprocess;
   - determinism.
7. A direct guard may omit `ratchetSemantics`; a ratchet guard may not.
8. External guards require CI-job/artifact evidence instead of static-suite execution evidence, but still need subprocess/result verification where applicable.

## 2. Exact-SHA audit ledger

Create:

```text
docs/ci/guard-qa/GUARD_QA_AUDIT.yml
```

This is a reviewed evidence ledger, not an allowlist.

```yaml
schemaVersion: 1
auditId: guard-qa-00
startSha: <40-char SHA>
startTreeSha: <40-char SHA>
registrySha256: <sha256>
executionPlanSha256: <sha256>
sourceScopeMatrixSha256: <sha256>
status: OPEN | COMPLETE | BLOCKED

guards:
  - guardId: source_provenance
    owner: "@panospao7"
    mode: blocking
    executionClass: direct
    sourceScopeType: production-kotlin-targeted
    ruleFamilies: []
    inputs:
      policy: []
      allowlist: []
      baseline: []
    evidence:
      positive: []
      negative: []
      adversarial: []
      infrastructure: []
      subprocess: []
      realRepository: []
      determinism: []
      ratchetSemantics: []
    findings:
      - gapId: GQA-SOURCE-PROVENANCE-001
        category: MISSING_DEDICATED_TEST_REGISTRATION
        severity: HIGH
        disposition: FOLLOW_UP_REQUIRED
        proposedFollowup: PR-GUARD-QA-01
    status: AUDITED_GAP
```

The ledger must cover:

```text
every active registry guard
every policy/meta-guard
every ratchet guard
every external registered engine
every retained Gradle/PowerShell/external enforcement path
```

No item may be omitted because it is “legacy” or “already green.”

## 3. Coverage audit generator

Add:

```text
scripts/ci/audit_guard_quality.py
scripts/ci/test_audit_guard_quality.py
```

The tool may generate a candidate audit ledger into `build/`, but it must not self-approve coverage.

Required inputs:

```text
canonical registry
GR-10A execution plan
GR-10B source-scope matrix
guard test files
test discovery output
static-suite artifact
optional GATE-00R evidence bundle
```

Required output:

```text
build/guard-debug/guard-qa-00/candidate-audit.yml
build/guard-debug/guard-qa-00/coverage-matrix.json
build/guard-debug/guard-qa-00/unregistered-enforcement.json
```

The generator must classify evidence as:

```text
DECLARED_AND_VERIFIED
DECLARED_BUT_UNVERIFIED
DISCOVERED_UNREGISTERED
MISSING
AMBIGUOUS
NOT_APPLICABLE_WITH_REASON
```

It must never infer that a test verifies a rule solely because:
- the filename resembles the guard;
- the source imports the guard;
- the test calls a helper with a similar name;
- a test passes generally.

## 4. Guard-contract validator

Create:

```text
scripts/ci/verify_guard_contracts.py
scripts/ci/test_verify_guard_contracts.py
```

Modes:

```text
--validate-schema
--report-gaps
--require-complete
```

Behavior:

| Mode | Exit behavior |
|---|---|
| `--validate-schema` | `0` valid ledger/contract; `2` malformed/missing/ambiguous structure |
| `--report-gaps` | `0` when the audit is structurally complete, even if gaps are recorded |
| `--require-complete` | `0` only with zero open gaps; `1` if gaps remain; `2` invalid evidence/configuration |

`--report-gaps` is an audit mode, not a CI success claim. Final acceptance must use `--require-complete`.

## 5. Remediation backlog

Create:

```text
docs/ci/guard-qa/GUARD_QA_REMEDIATION_BACKLOG.yml
```

Every gap must produce exactly one row:

```yaml
- gapId: GQA-MONEY-003
  guardId: money
  ruleFamily: G-MONEY-14
  missingEvidence: adversarial
  risk: HIGH
  rationale: "Alias-import bypass has no fixture."
  requiredOutcome: "Three bypass fixtures and one subprocess test."
  owner: "@panospao7"
  linkedIssue: <issue/work-item>
  plannedPr: PR-GUARD-QA-03
  status: OPEN
```

This backlog is not a suppression list. It must never alter a guard’s result.

---

# Audit taxonomy

## Required evidence for every guard

| Evidence class | Minimum requirement |
|---|---|
| Positive | At least one real violating fixture per rule family. |
| Negative | At least one lawful fixture per rule family. |
| Adversarial | At least three bypass-shaped fixtures per high-risk rule family. |
| Infrastructure | At least one fail-closed invalid/missing/malformed input fixture. |
| Subprocess | Actual CLI invocation with asserted `0`, `1`, and `2` behavior as applicable. |
| Real repository | Actual checkout execution with deterministic expected report/result assertions. |
| Determinism | Two semantic-equivalent runs. |
| Scope | Second-root and undeclared-root behavior for source-scanning guards where applicable. |
| Policy/allowlist | Exact/stale/malformed policy behavior where applicable. |
| Ratchet | Exact match, new finding, resolved finding, malformed child/baseline, and expiry behavior where applicable. |

## Additional requirements by guard mode

### Direct blocking guards

Must demonstrate:

```text
0 = legal code
1 = actual violation
2 = detector/configuration failure
```

### Ratchet guards

Must demonstrate:

```text
child clean + baseline clean = 0
new finding = 1
resolved baseline finding = 1
malformed/untrusted child report = 2
malformed baseline = 2
expired or stale debt = 2 where schema supports debt expiry
```

### Policy/meta-guards

Must demonstrate:

```text
valid canonical configuration = 0
bad configuration = 2 or 1 according to documented contract
unknown/missing required key = fail closed
```

### External CI guards

Must demonstrate:

```text
canonical job exists
job runs in intended event contexts
required artifacts are produced on pass and fail
artifact verifier rejects missing/malformed results
job result reaches stable aggregator
```

---

# Mandatory workflow

## Step 1 — Freeze exact start state

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

python3 scripts/ci/verify_guard_registry.py --root .
python3 scripts/ci/verify_guard_contracts.py --root . --validate-schema
```

Record:

```text
START_SHA
START_TREE_SHA
registry hash
execution-plan hash
source-scope matrix hash
GATE-00R evidence ID
```

## Step 2 — Enumerate all enforcement paths

Use GR-10A’s command-authority matrix, registry, Gradle task graph, and workflow inventory.

For each path, choose exactly one:

```text
REGISTERED_ACTIVE_GUARD
REGISTERED_EXTERNAL_GUARD
SUBSUMED_AND_RETIRED
BLOCKED_UNREGISTERED
```

Hard fail the audit if an enforcement path remains `UNKNOWN`.

## Step 3 — Build a rule-family inventory

For each guard:

1. read its script and associated policy/baseline;
2. identify closed rule IDs;
3. identify source scope and important bypass surfaces;
4. identify configuration failure conditions;
5. identify whether it emits structured reports, text findings, or ratchet child reports;
6. identify its canonical command from GR-10A;
7. identify current tests, including shared tests.

Do not use filename matching as final evidence.

## Step 4 — Map existing tests to evidence

For every proposed test pointer, verify:

```text
test exists
test executes in canonical test discovery
test invokes the relevant detector or public CLI
fixture actually contains the claimed legal/illegal condition
assertion checks the claimed rule/outcome
test would fail if the covered behavior regressed
```

A test may be marked `DECLARED_AND_VERIFIED` only after all checks pass.

## Step 5 — Run focused proof of test discoverability

For each registered Python test path:

```bash
python3 -m pytest <exact-test-path> -v --tb=short
```

For shared test suites, use exact node IDs where needed.

For Kotlin/instrumented guard tests, record:
- task;
- test class;
- test package;
- JUnit XML location;
- whether it is executed by the canonical CI path.

Do not run Gradle without explicit ownership.

## Step 6 — Run actual subprocess/real-tree checks

For each guard class, run the canonical GR-10A registered runner or suite command in a controlled output directory.

Record:

```text
actual argv
exit code
report/log path
semantic digest
input hashes
scope evidence
```

A successful static-suite row alone does not substitute for a direct subprocess test.

## Step 7 — Add audit-tool regression tests

The audit validator itself must prove:

1. unknown guard ID fails;
2. duplicate guard ledger row fails;
3. missing owner fails;
4. missing rule family fails;
5. unregistered execution path fails;
6. missing positive/negative/infrastructure evidence fails schema validation;
7. invalid `NOT_APPLICABLE` use fails;
8. broken test path fails;
9. a shared test pointer without exact node ID fails;
10. a ratchet guard without ratchet evidence fails;
11. source scanner without source-scope evidence fails;
12. external guard without CI artifact evidence fails;
13. `--report-gaps` reports but does not mislabel gaps as complete;
14. `--require-complete` exits `1` with open gaps;
15. generated candidate/audit ordering is deterministic;
16. no raw logs, secrets, absolute paths, or source payload leak into tracked ledger.

## Step 8 — Review and create narrow follow-ups

Group only related gaps. Example grouping:

```text
PR-GUARD-QA-01: source provenance + lint baseline test contracts
PR-GUARD-QA-02: privacy guard fixture/CLI coverage
PR-GUARD-QA-03: event writer rule-family and ratchet coverage
PR-GUARD-QA-04: money bypass/mutation/subprocess coverage
PR-MIG-00: migration proof audit
```

Do not combine unrelated parser rewrites into QA-00.

---

# Required checks

```bash
python3 -m pytest \
  scripts/ci/test_audit_guard_quality.py \
  scripts/ci/test_verify_guard_contracts.py \
  scripts/ci/test_guard_registry.py \
  scripts/ci/test_guard_execution_plan.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_guard_registry.py --root .
```

```bash
python3 scripts/ci/verify_guard_contracts.py \
  --root . \
  --validate-schema
```

```bash
python3 scripts/ci/verify_guard_contracts.py \
  --root . \
  --report-gaps \
  --output build/guard-debug/guard-qa-00/report.json
```

Run `--require-complete` only to establish the truthful current state:

```bash
set +e
python3 scripts/ci/verify_guard_contracts.py --root . --require-complete
rc=$?
set -e
test "$rc" -eq 0 -o "$rc" -eq 1
```

Exit `1` is expected if verified gaps remain. It is not a reason to weaken the contract.

---

# Definition of done

QA-00 is complete only when:

- every active and external enforcement path is classified;
- every active guard has owner, rule families, scope, inputs, documentation anchor, and execution plan;
- every existing test claim is verified rather than assumed;
- all gaps are explicit, uniquely identified, risk-ranked, and assigned;
- no unregistered enforcement path remains unexplained;
- no baseline/policy/source/production code changed;
- `--validate-schema` passes;
- the audit ledger is deterministic and exact-SHA anchored;
- the remediation backlog contains the next PR(s).

QA-00 may complete with open quality gaps. It must not claim final guard quality is complete until `--require-complete` returns `0`.

## Required completion report

```text
PR: GUARD-QA-00
START SHA:
END SHA:
GATE-00R EVIDENCE ID:
REGISTRY SHA:
EXECUTION PLAN SHA:
SOURCE-SCOPE MATRIX SHA:

ACTIVE REGISTERED GUARDS:
REGISTERED EXTERNAL GUARDS:
UNREGISTERED ENFORCEMENT PATHS BEFORE/AFTER:

GUARDS AUDITED:
RULE FAMILIES AUDITED:
AUDITED_COMPLETE:
AUDITED_GAP:
IN_FLIGHT_CONTRACT_CHANGE:
BLOCKED:

MISSING POSITIVE COVERAGE:
MISSING NEGATIVE COVERAGE:
MISSING ADVERSARIAL COVERAGE:
MISSING INFRASTRUCTURE COVERAGE:
MISSING SUBPROCESS COVERAGE:
MISSING REAL-REPOSITORY COVERAGE:
MISSING DETERMINISM COVERAGE:
MISSING RATCHET COVERAGE:

AUDIT VALIDATOR TESTS:
REGISTRY VALIDATION:
CONTRACT SCHEMA VALIDATION:
REQUIRE-COMPLETE EXIT:

PRODUCTION KOTLIN CHANGED: no
POLICY CHANGED: no
BASELINE CHANGED: no
WORKFLOW SEMANTICS CHANGED: no
NEXT PR(S):
```