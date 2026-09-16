# PR-CI-11 — Workflow, artifact, and required-check integrity

## Agent mission

Make GitHub Actions execute, preserve, aggregate, and expose the canonical guard/migration/release truth established by earlier PRs.

After this PR, a pull request cannot appear green when:
- a required job is skipped/cancelled/times out;
- a required artifact is missing or malformed;
- a required command uses `continue-on-error`, `|| true`, warning mode, or swallowed exit status;
- static/migration/release proof is not run on the PR;
- a stable required-check name is absent or no longer represents all required child work;
- workflow/action/tool supply-chain integrity cannot be established.

## Why this PR exists

At historical SHA `8b45879e…`, the workflow has a `Release Check` that only assembles a release APK, while migration proof is restricted to main/master pushes or manual dispatch. The generic instrumented-test job also uses job-level `continue-on-error: true`. These states are incompatible with the final contract for PR-blocking migration/release proof and definitive required checks. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

The acceptance specification requires seven stable check categories, explicitly requires all required jobs to have definitive conclusions, forbids required `continue-on-error` and suppressed command exits, and requires always-uploaded artifacts whose absence fails the relevant aggregator. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## Required predecessors

```text
GATE-00R complete
GR-10A canonical registry/execution ownership complete
GR-10B source scope complete
GR-10D documentation/evidence truth sync complete
GUARD-QA-00 coverage audit complete
MIG-01 blocking migration proof complete
REL-00 release artifact/smoke implementation complete
```

## Hard stops

Stop with `BLOCKED` if:

- a required child job cannot emit a safe structured result;
- stable required-check names cannot be agreed with branch protection owner;
- an action/tool cannot be pinned or checksum-verified under repository policy;
- a required workflow needs unavailable PR secrets;
- a required test is inherently flaky and proposed solution is `continue-on-error`;
- a required artifact cannot be produced on failure paths;
- workflow behavior cannot be validated on an actual pull-request event;
- branch/ruleset visibility is unavailable when required-check migration needs confirmation.

---

# Scope

## Allowed

```text
.github/workflows/**
config/ci/workflow_contract.yml
config/ci/artifact_contract.yml
config/ci/action_lock.yml
config/ci/required_checks.yml
scripts/ci workflow/artifact/aggregate validators
tests for those validators
CI documentation and generated evidence templates
```

## Forbidden

```text
production Kotlin changes
DB/time policy or baseline changes
migration/release semantic changes except wiring their already-complete contracts
new warning mode for required checks
new continue-on-error on required jobs/steps
path filters that bypass relevant PR work
manual duplicate guard command lists
unverified action/tool downloads
workflow-only test filters that omit required suites
branch-rule changes without exact transition evidence
```

---

# Target stable required checks

Use these exact stable display names unless branch-protection owner approves an explicit migration:

```text
Validate Workflow
Static Guards
Guard Integrity
Unit Tests
Lint & Check
Migration Proof
Release Verification
```

These names must be emitted by final aggregator jobs, not by fragile leaf jobs. The acceptance gate requires stable aggregators for these categories. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## Required workflow topology

```text
validate-workflow
  └─ Validate Workflow

static-guards
  └─ Static Guards

guard-integrity
  └─ Guard Integrity

unit-tests
  └─ Unit Tests

lint-check
  └─ Lint & Check

migration-proof-preflight
migration-proof-lane[api]
migration-proof-aggregate
  └─ Migration Proof

release-build
release-smoke-lane[api]
release-verification-aggregate
  └─ Release Verification
```

Optional/non-blocking experiments may exist only if:
- their name clearly says `Non-blocking`;
- no stable required aggregator depends on them;
- they do not duplicate or replace migration/release proof;
- they cannot be confused with required proof.

The historical generic “Instrumented Tests” job must not remain an ambiguous optional substitute for required migration/release runtime testing.

---

# Required deliverables

## 1. CI workflow contract

Create:

```text
config/ci/workflow_contract.yml
```

Required fields:

```yaml
schemaVersion: 1
workflow:
  path: .github/workflows/ci.yml
  name: CI
  requiredTriggers:
    - pull_request
    - push
    - workflow_dispatch

requiredChecks:
  - displayName: Validate Workflow
    jobId: validate-workflow
  - displayName: Static Guards
    jobId: static-guards
  - displayName: Guard Integrity
    jobId: guard-integrity
  - displayName: Unit Tests
    jobId: unit-tests
  - displayName: Lint & Check
    jobId: lint-check
  - displayName: Migration Proof
    jobId: migration-proof-aggregate
  - displayName: Release Verification
    jobId: release-verification-aggregate

requiredJobRules:
  timeoutRequired: true
  forbidContinueOnError: true
  requireExplicitFailurePropagation: true
  requireAlwaysArtifacts: true
  forbidWarningMode: true
  forbidPathSkip: true

security:
  defaultPermissions:
    contents: read
  forbidPullRequestTargetForVerification: true
  productionSecretsAllowedOnPullRequest: false
```

Unknown keys fail closed.

## 2. Action/tool lock

Create:

```text
config/ci/action_lock.yml
```

Every executable action/image/tool source used in a required workflow must be pinned according to a closed policy:

```text
GitHub Action: immutable commit SHA
Docker action/image: immutable digest
Downloaded executable/JAR: exact version + SHA-256
```

The historical workflow uses moving major-version action references and a tagged Docker action. CI-11 must replace them with audited immutable identities or an approved lockfile mechanism. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

## 3. Artifact contract

Create:

```text
config/ci/artifact_contract.yml
```

For each required job define:

```yaml
- jobId: static-guards
  artifactName: static-guards-${{ github.sha }}
  requiredFiles:
    - summary.json
    - execution-plan.json
    - per-guard-results.json
  requiredJson:
    targetSha: required
    schemaVersion: required
  uploadOnFailure: true
```

Minimum required artifact families:

```text
workflow validation report
workflow contract report
guard registry/execution plan
static suite summary/per-guard logs/reports
guard contract/audit report
JUnit test reports and test-result verifier output
lint/check reports
migration proof artifacts/JUnit/schema diffs
release metadata/AAB/APKS/APK reports/signing/smoke reports
aggregate dependency/result report
final CI evidence manifest
```

## 4. Workflow integrity validator

Create:

```text
scripts/ci/verify_workflow_integrity.py
scripts/ci/test_verify_workflow_integrity.py
```

It must parse all workflows and verify:

1. YAML syntax and required workflow existence;
2. canonical required jobs/check names;
3. required PR triggers;
4. no relevant `paths`/`paths-ignore` bypass;
5. no `pull_request_target` for untrusted PR verification;
6. minimal permissions;
7. every required job has timeout;
8. no `continue-on-error`;
9. no `|| true`;
10. no uncollected `set +e`;
11. no `if: false`/event condition that skips a required PR job;
12. required actions are lock-pinned;
13. always-run upload steps exist;
14. artifact names and paths match artifact contract;
15. aggregators use `if: always()` and explicitly reject failed/cancelled/skipped/missing needs;
16. no duplicate or manual static-guard manifest;
17. canonical GR-10A command runner is used;
18. release/migration jobs invoke REL-00/MIG-01 contracts;
19. workflow dispatch supports an explicit expected target SHA for FIN-00;
20. reports carry exact target SHA.

Exit semantics:

```text
0 valid workflow contract
1 workflow-policy violation
2 malformed YAML, missing configuration, unsupported expression/parser uncertainty
```

## 5. Artifact verifier

Create:

```text
scripts/ci/verify_ci_artifacts.py
scripts/ci/test_verify_ci_artifacts.py
```

Inputs:

```text
artifact contract
downloaded artifacts directory
exact target SHA
workflow run ID
expected required jobs
```

It must reject:

```text
missing artifact
empty required artifact
wrong SHA
wrong schema
malformed JSON/XML
duplicate artifact identity
missing required report
unsafe absolute path/secret fields
missing failed-job evidence
unexpected artifact from another run
```

## 6. Aggregator verifier

Create:

```text
scripts/ci/verify_required_check_aggregate.py
scripts/ci/test_verify_required_check_aggregate.py
```

Inputs:
- required `needs` result data;
- artifact verification result;
- expected check identity;
- exact target SHA.

An aggregator must fail if any dependency is:

```text
failure
cancelled
skipped
timed_out
missing
neutral where required
success without required artifact evidence
```

It must not use a loose expression such as “all non-failure states pass.”

---

# Required implementation sequence

## Step 1 — Freeze the actual workflow state

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

mkdir -p build/guard-debug/ci-11
cp .github/workflows/ci.yml build/guard-debug/ci-11/ci-before.yml
```

Record:
- workflow SHA;
- current job IDs/display names;
- triggers;
- permissions;
- action references;
- `continue-on-error`;
- current artifact uploads;
- current check names;
- current branch rule/ruleset evidence if visible.

Do not assume current job names are already protected checks.

## Step 2 — Create validator fixtures before workflow edits

Add fixtures for:

```text
missing pull_request trigger
PR path filter bypass
required job with no timeout
required job with continue-on-error
required command with || true
required command with set +e and no final propagation
unpinned action/tagged Docker action
workflow using pull_request_target
required job with no always-upload
artifact upload with missing/ignored files
aggregator missing if: always()
aggregator accepts skipped child
aggregator accepts cancelled child
required display name changed
workflow has duplicate guard command list
manual guard command differs from GR-10A plan
migration/release job uses debug artifact
workflow dispatch missing expected SHA verification
```

## Step 3 — Define exact required-check transition

Before renaming any check:

1. publish the new stable aggregator jobs;
2. run them successfully on a test PR;
3. obtain administrator/ruleset owner approval;
4. update branch protection/ruleset to require exact new display names;
5. retrieve platform/API evidence;
6. retire old required names only after the new rules are active.

A check-name change without a branch-policy transition is prohibited by the acceptance contract. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## Step 4 — Harden action/tool supply chain

For every required workflow action:
- resolve exact immutable identity;
- record owner/repository/reference/checksum/digest in action lock;
- verify it exists;
- replace mutable tags;
- keep permissions minimal.

For externally downloaded tools:
- use REL-00/MIG-01 toolchain lock;
- verify checksum before execution;
- never execute a downloaded file just because its filename/version appears correct.

## Step 5 — Implement safe per-job evidence

Every required job writes a safe `job-evidence.json` before exit.

Required fields:

```text
schemaVersion
targetSha
workflowRunId
jobId
displayName
started/completed timestamps
exact canonical command identity
exit/result classification
required artifact names
artifact checksums if available
```

Use a failure-preserving wrapper/trap:

```text
record command result
preserve original non-zero result
write safe evidence
exit original result
```

Do not replace failure with success merely to upload evidence.

## Step 6 — Wire canonical jobs

### Validate Workflow

Must run:
```text
actionlint
workflow integrity validator
action/tool lock validator
```

### Static Guards

Must run GR-10A canonical suite only.

Must upload:
```text
suite summary
execution plan
per-guard JSON/logs
```

### Guard Integrity

Must run:
```text
registry validator
guard contracts validator
protected-base/policy-delta checks where available
documentation truth validator
```

### Unit Tests

Must run:
```text
canonical unit test task
JUnit result verifier
critical-test/ignored-test verifier
```

### Lint & Check

Must run:
```text
lintDebug
assembleDebug
:app:check
report collection
```

### Migration Proof

Must consume MIG-01 preflight/matrix/result-verifier artifacts and aggregate all required API lanes.

### Release Verification

Must consume REL-00 build/artifact/smoke artifacts and aggregate all required API lanes.

## Step 7 — Remove unsafe optional substitutions

The historical job-level `continue-on-error` instrumented test path cannot be a required proof source. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

Choose one:
- retire it;
- rename it clearly as exploratory/non-blocking;
- keep it independent, but prove no required aggregator uses it.

Migration and release proof must remain separately blocking.

## Step 8 — Add artifact download/verification aggregate

A required aggregator must:
1. download artifacts produced by all required child jobs;
2. run artifact verifier;
3. run dependency-result verifier;
4. emit its own aggregate report;
5. fail on any child/result/artifact uncertainty.

Artifact upload uses `if: always()`, but missing artifacts are still failures. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## Step 9 — Validate real PR execution

Open/run against an actual pull request at exact SHA.

Verify:
```text
all seven stable required checks appear
every required job has definitive conclusion
artifact reports identify same target SHA
migration/release jobs run on pull_request
no PR secret exposure
aggregators fail simulated/fixture child failures
```

---

# Required adversarial checks

1. Mark Static Guards `continue-on-error: true` → validator fails.
2. Add `|| true` after migration/release command → validator fails.
3. Skip Release Verification on PR with conditional → validator fails.
4. Change required artifact path to nonexistent → artifact aggregate fails.
5. Remove `if: always()` from artifact upload → validator fails.
6. Let a matrix lane cancel/skip → Migration/Release aggregate fails.
7. Remove JUnit XML → corresponding aggregate fails.
8. Replace canonical guard runner with manual Python command → validator fails.
9. Change action SHA/tag → action lock fails.
10. Add broad workflow path filter → validator fails.
11. Supply wrong target SHA in artifact → verifier fails.
12. Simulate uploaded artifact from a different workflow run → verifier fails.
13. Rename required check without branch-rule migration record → validator fails.
14. Use production signing secret on `pull_request` → validator fails.
15. Re-run same target SHA → stable jobs/artifact semantics match.

---

# Required validation

```bash
python3 -m pytest \
  scripts/ci/test_verify_workflow_integrity.py \
  scripts/ci/test_verify_ci_artifacts.py \
  scripts/ci/test_verify_required_check_aggregate.py \
  scripts/ci/test_guard_execution_plan.py \
  scripts/ci/test_run_registered_guard.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_workflow_integrity.py \
  --root . \
  --workflow .github/workflows/ci.yml \
  --contract config/ci/workflow_contract.yml \
  --artifact-contract config/ci/artifact_contract.yml \
  --action-lock config/ci/action_lock.yml
```

Then:
- validate workflow with actionlint;
- run canonical suite;
- run migration/release contract commands;
- execute actual PR CI;
- download and validate artifacts;
- retrieve branch-rule/ruleset evidence.

---

# Definition of done

CI-11 is complete only when:

- all seven stable required checks exist and aggregate exact child evidence;
- migration and release proof run on every relevant PR;
- required jobs never use `continue-on-error`, `|| true`, warning mode, or silent skip;
- action/tool supply chain is pinned/locked;
- required artifacts upload on success/failure and missing artifacts fail aggregates;
- all required reports identify exact target SHA;
- no manual guard command list conflicts with GR-10A;
- workflow validates itself through fixtures and actual PR execution;
- branch-policy transition evidence exists for stable check names;
- no production/config/baseline policy was weakened.

## Required completion report

```text
PR: CI-11
START SHA:
END SHA:
GATE-00R EVIDENCE ID:
MIG-01 SHA:
REL-00 SHA:

WORKFLOW CONTRACT SHA:
ARTIFACT CONTRACT SHA:
ACTION LOCK SHA:
REQUIRED CHECKS SHA:

REQUIRED CHECKS:
  Validate Workflow:
  Static Guards:
  Guard Integrity:
  Unit Tests:
  Lint & Check:
  Migration Proof:
  Release Verification:

PR TRIGGERS VERIFIED:
REQUIRED JOBS WITH TIMEOUTS:
REQUIRED CONTINUE-ON-ERROR COUNT: 0
REQUIRED || TRUE COUNT: 0
UNPINNED REQUIRED ACTIONS: 0
MISSING REQUIRED ARTIFACTS: 0
AGGREGATOR SKIP/CANCEL ESCAPES: 0

ACTUAL PR RUN ID:
ARTIFACT VERIFICATION:
BRANCH-POLICY TRANSITION EVIDENCE:
DOUBLE-RUN SEMANTIC REPRODUCIBILITY:

PRODUCTION KOTLIN CHANGED: no
DB POLICY CHANGED: no
BASELINE CHANGED: no
NEXT PR: FIN-00
```