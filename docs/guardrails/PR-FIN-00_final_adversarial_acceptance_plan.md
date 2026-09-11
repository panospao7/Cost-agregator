# PR-FIN-00 — Final adversarial acceptance

## Agent mission

Run the complete guardrail program twice against the **same immutable target SHA**, collect actual CI/platform evidence, execute seeded adversarial checks in isolated worktrees, verify branch protection, and issue one truthful verdict:

```text
GREEN
RED
BLOCKED
```

This PR is the final acceptance mechanism. It must never convert missing evidence into a green result.

## Why this PR exists

The acceptance specification requires:
- exact target/base/merge-base evidence;
- full guard/test/migration/release execution;
- actual release AAB/generated-APK smoke;
- required artifacts and branch-protection evidence;
- two complete successful executions on the same candidate SHA. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

A local “looks green” run is insufficient: missing permissions, unavailable emulators, missing artifacts, cancelled jobs, or missing branch-protection visibility are explicitly `BLOCKED`, not accepted. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Absolute prerequisites

Do not start FIN-00 until all are complete and evidenced:

```text
GATE-00R
TIME-01 where required
GR-08 remediation, if current trusted DB findings existed
GR-10A / GR-10B / GR-10C / GR-10D
GR-11 / GR-12 / GR-13 / GR-14 / GR-15
GUARD-QA-00 and all required QA remediation slices
MIG-00 / MIG-01
REL-00
CI-11
```

Required exact start state:

```text
checkout clean
full Git history available
target SHA immutable
protected base SHA immutable
merge-base recorded
all stable required checks configured
all required policies/baselines valid
no open blocker from QA/migration/release/DB proof
```

## Hard stops

Immediately return `BLOCKED`, never GREEN, if:

- target SHA changes after freeze;
- any report refers to a different SHA;
- source/config/workflow changes after run 01 and before run 02;
- workflow run is cancelled/superseded;
- emulator/artifact/platform API evidence unavailable;
- a required job is skipped, neutral, missing, or timed out;
- branch protection cannot be queried/exported;
- an adversarial seed cannot run actual detector;
- a required result is only a human assertion;
- an artifact contains unsafe data and cannot be safely retained;
- any policy/baseline/guard is weakened to make a result pass.

---

# Scope

## Allowed

```text
scripts/ci/final_acceptance/**
scripts/ci/run_final_acceptance.py
scripts/ci/verify_final_acceptance.py
scripts/ci/verify_branch_protection.py
scripts/ci/run_guard_adversarial_corpus.py
scripts/ci/test_*
config/ci/final_acceptance_contract.yml
config/ci/final_adversarial_corpus.yml
documentation template for final artifact report
```

## Forbidden

```text
production Kotlin changes after acceptance freeze
baseline updates
policy/allowlist/exemption changes
detector behavior changes
workflow behavior changes
test exclusions
timeout increases
branch-rule weakening
manual editing of generated evidence
committing final generated report into the accepted target after it is verified
```

Important: the final report belongs in CI artifacts/PR evidence. Committing it after the run changes the SHA and therefore requires a new final acceptance cycle.

---

# Required deliverables

## 1. Final acceptance contract

Create:

```text
config/ci/final_acceptance_contract.yml
```

It must define:

```yaml
schemaVersion: 1

requiredChecks:
  - Validate Workflow
  - Static Guards
  - Guard Integrity
  - Unit Tests
  - Lint & Check
  - Migration Proof
  - Release Verification

requiredArtifacts:
  - workflow-validation
  - static-guard-summary
  - guard-integrity
  - unit-junit
  - lint-check
  - migration-proof
  - release-verification

requiredEvidence:
  exactSha: true
  cleanCheckout: true
  baseAndMergeBase: true
  branchProtection: true
  twoCompleteRuns: true
  protectedBaseScan: true
  adversarialCorpus: true

allowedVolatileFields:
  - timestamps
  - durations
  - runIds
  - temporary paths
  - ephemeralSigningCertificateFingerprint
  - signedArtifactSha256
```

`allowedVolatileFields` must be minimal. It must never exclude:
- guard fingerprints;
- rule IDs;
- exit results;
- trust state;
- test identities/counts/skips;
- policy/baseline hashes;
- migration/release semantic results;
- artifact-policy findings;
- required-job conclusions.

## 2. Final adversarial corpus

Create:

```text
config/ci/final_adversarial_corpus.yml
```

Each entry must execute the **real canonical detector** in an isolated target-SHA worktree.

Required fields:

```yaml
- id: fin-db-new-mutation
  guardId: db_access
  mutationType: sourcePatch
  fixturePatch: scripts/ci/fixtures/final/...
  expectedExit: 1
  expectedRuleIds:
    - DB_UNAUTHORIZED_MUTATION
  expectedClassification: violation
  cleanupProof: revert-and-rerun
```

Minimum corpus categories:

```text
new unauthorized DB mutation
direct barrier bypass
helper/worker mediation bypass
UI direct DAO access
direct wall-clock read
PII logging
cloud payload bypass
cancellation swallow
raw money aggregation
forbidden release artifact predicate
malformed policy/allowlist/root configuration
new ratchet finding
stale/resolved ratchet baseline fixture
missing artifact / skipped required job fixture
```

Rules:

1. Corpus changes never touch target checkout.
2. Corpus patch cannot modify guard, policy, baseline, workflow, or test contracts unless test explicitly validates fail-closed configuration behavior.
3. Each seed runs detector, asserts `1` or `2`, reverts/fixes, reruns, and asserts `0`.
4. Each seed report must assert rule ID, symbol/path/category where supported.
5. A seed test that reproduces detector logic independently is invalid.

## 3. Protected-base execution harness

Create:

```text
scripts/ci/run_protected_base_guard_check.py
scripts/ci/test_run_protected_base_guard_check.py
```

It must materialize a controlled workspace:

```text
base guard engine + base policy/config
head application source
head source-root topology evidence
```

Required behavior:

1. load base engine from exact protected base SHA;
2. analyze target/head application source;
3. preserve base policy/allowlist/baseline rules;
4. record every overlaid source path/hash;
5. run normal head engine against head source separately;
6. run guard policy/detector delta verifier;
7. reject unsupported base/head layout changes with exit `2`.

This is required because a PR must not weaken its own detector/configuration and then use the weakened detector to prove itself. The acceptance contract explicitly requires protected-base engine, head engine, policy delta, and guard adversarial corpus. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## 4. Branch-protection verifier

Create:

```text
scripts/ci/verify_branch_protection.py
scripts/ci/test_verify_branch_protection.py
```

It must consume GitHub API/platform-export evidence and verify:

```text
pull request required
required approval policy
required stable check names
branch up-to-date policy
conversation resolution policy
admin bypass policy
force-push/deletion policy
CODEOWNERS coverage for guard/workflow/policy/migration/release areas
```

If API permission is unavailable, return:

```text
exit 2
status BLOCKED
```

Documentation screenshots alone are insufficient unless they are auditable and identify repository/ruleset/branch. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

## 5. Final evidence collector and validator

Create:

```text
scripts/ci/run_final_acceptance.py
scripts/ci/verify_final_acceptance.py
scripts/ci/test_run_final_acceptance.py
scripts/ci/test_verify_final_acceptance.py
```

### Collector responsibilities

```text
freeze target/base/merge-base
validate clean worktree
dispatch/wait for two exact-SHA CI executions
collect job/API/artifact metadata
run local protected-base and adversarial evidence where appropriate
download artifacts
validate reports/schemas/checksums
create run-01/run-02 semantic summaries
write final report
```

### Validator responsibilities

```text
validate all required job conclusions
validate all artifact contracts
validate branch policy evidence
validate two-run semantic equivalence
validate no unresolved prohibited debt
produce verdict GREEN / RED / BLOCKED
```

No library API may call `sys.exit`; CLI maps final state to:
```text
0 GREEN
1 RED
2 BLOCKED
```

---

# Two-run exact-SHA strategy

## Freeze

Before run 01:

```bash
git status --porcelain=v1
git rev-parse HEAD
git rev-parse HEAD^{tree}
git rev-parse "$BASE_SHA"
git merge-base HEAD "$BASE_SHA"
git diff --check
```

Record full 40-character SHAs only.

## Run execution

Use CI-11’s workflow-dispatch input contract:

```text
target_sha
base_sha
expected_tree_sha
acceptance_run_id
```

The workflow must checkout `target_sha` explicitly and fail if:

```text
HEAD != target_sha
HEAD tree != expected_tree_sha
base/merge-base evidence mismatches
```

Run twice:

```text
run-01: exact target SHA
run-02: same exact target SHA
```

Do not accept a rerun of a different commit, a branch tip that moved, or an older successful run.

## Semantic comparison

Compare:

```text
required job names/conclusions
guard registry/execution-plan hashes
guard fingerprints and trust states
ratchet new/resolved/stale sets
policy/baseline/source-root hashes
test class identities/counts/skips
lint/check status
migration contract/API lanes/results
release manifest/content/smoke predicates/API lanes
artifact contract/checksums
branch-protection result
```

Allowed differences only:

```text
timestamps
durations
workflow run IDs
temporary paths
ephemeral signing certificate fingerprint
signed binary hashes caused solely by ephemeral signing
```

Even when signed APK hashes differ, all normalized release predicates, AAB provenance, package metadata, component/permission policies, and smoke results must match.

---

# Mandatory final execution sequence

## Phase A — Freeze and self-protection

1. Freeze exact target/base/merge-base.
2. Verify clean checkout/full history.
3. Validate workflow contract/action lock.
4. Validate registry, guard contracts, documentation truth.
5. Run protected-base engine against target source.
6. Run head engine against target source.
7. Run policy/detector/baseline delta checks.
8. Run final adversarial corpus in isolated worktrees.

## Phase B — Required CI execution

For each exact run:
1. Validate Workflow.
2. Static Guards.
3. Guard Integrity.
4. Unit Tests.
5. Lint & Check.
6. Migration Proof.
7. Release Verification.
8. Artifact download/validation.
9. Branch-protection verification.
10. Final run evidence collection.

## Phase C — Compare and decide

1. Compare run-01/run-02 semantic summaries.
2. Review each allowed volatile difference.
3. Reject unexplained difference.
4. Generate final report artifact.
5. Emit `GREEN`, `RED`, or `BLOCKED`.

---

# Required adversarial acceptance matrix

| Area | Seeded proof |
|---|---|
| DB ownership | New direct DAO mutation yields finding `1`. |
| DB barrier | Barrier in unrelated branch/lambda does not pass. |
| Helper mediation | Unguarded/public caller produces finding or unproven failure. |
| Worker mediation | Mutation outside worker guard scope fails. |
| Ratchet | New finding, stale resolved finding, malformed report/baseline behave correctly. |
| Time | Direct/aliased clock API produces finding. |
| UI | Direct DAO injection/access produces finding. |
| Privacy | PII logging/payload bypass produces finding. |
| Cancellation | Swallowed cancellation produces finding. |
| Money | Raw aggregate bypass produces finding. |
| Guard infrastructure | Malformed policy/root/allowlist produces exit `2`. |
| Migration | Missing proof class/JUnit/edge causes blocking failure. |
| Release | Debug/test-only/incorrect-signing/forbidden-content fixture causes blocking failure. |
| Workflow | `continue-on-error`, skipped required job, missing artifact, unpinned action cause workflow-contract failure. |
| Branch policy | Missing required stable check produces `BLOCKED`/failure. |

---

# Required validation

```bash
python3 -m pytest \
  scripts/ci/test_run_final_acceptance.py \
  scripts/ci/test_verify_final_acceptance.py \
  scripts/ci/test_run_protected_base_guard_check.py \
  scripts/ci/test_verify_branch_protection.py \
  scripts/ci/test_run_guard_adversarial_corpus.py \
  -v --tb=short
```

```bash
python3 scripts/ci/run_guard_adversarial_corpus.py \
  --root . \
  --target-sha "$TARGET_SHA" \
  --manifest config/ci/final_adversarial_corpus.yml \
  --output build/guard-debug/fin-00/adversarial
```

```bash
python3 scripts/ci/run_protected_base_guard_check.py \
  --root . \
  --base-sha "$BASE_SHA" \
  --target-sha "$TARGET_SHA" \
  --output build/guard-debug/fin-00/protected-base
```

```bash
python3 scripts/ci/run_final_acceptance.py \
  --root . \
  --target-sha "$TARGET_SHA" \
  --base-sha "$BASE_SHA" \
  --output build/guard-debug/fin-00
```

The final CI workflow executions, artifact downloads, and branch-policy API evidence are mandatory; local output cannot replace them.

---

# Failure routing

| Result | Meaning | Action |
|---|---|---|
| `GREEN` | Every required predicate and both runs passed. | Final acceptance complete. |
| `RED` | Real policy/code/test/release/workflow violation. | Preserve artifacts; fix root cause in new focused PR. |
| `BLOCKED` | Missing permission/artifact/emulator/tool/run evidence. | Resolve infrastructure/access; do not merge. |

No outcome may be called “mostly green,” “expected green,” or “green except for unavailable evidence.”

---

# Definition of done

FIN-00 is complete only when:

- two complete CI executions passed for the same immutable SHA;
- all seven stable required checks have definitive successful conclusions;
- protected-base and head guard engines pass required checks;
- final adversarial corpus executes real detectors and passes;
- all required artifacts exist, validate, and identify target SHA;
- migration and release runtime proof passed on required lanes;
- branch protection/ruleset evidence proves required checks and bypass policy;
- no policy/baseline/exemption/timeout/test-skip/workflow weakening occurred;
- final report is an artifact tied to exact target SHA;
- verdict is `GREEN`.

## Required final acceptance report

```text
FINAL CI GUARD ACCEPTANCE REPORT

Verdict: GREEN | RED | BLOCKED
Target SHA:
Target tree SHA:
Base SHA:
Merge-base SHA:
Branch:
Pull request:
Verification date:
Run-01 workflow ID:
Run-02 workflow ID:

REQUIRED CHECKS
- Validate Workflow:
- Static Guards:
- Guard Integrity:
- Unit Tests:
- Lint & Check:
- Migration Proof:
- Release Verification:

SELF-PROTECTION
- Protected-base result:
- Head guard result:
- Policy delta result:
- Detector migration result:
- Adversarial corpus result:

GUARD SUMMARY
- Registered/executed guards:
- Strict findings:
- Ratchet new/resolved/stale:
- Diagnostics/infrastructure errors:
- Policy/baseline hashes:

TEST SUMMARY
- Unit/architecture/guard tests:
- Required test classes:
- Skips:
- Failures:
- Timeouts:

MIGRATION SUMMARY
- Supported range:
- Tested starts:
- API lanes:
- Builder proof:
- Parity/integrity/FK:
- Unsupported-version policy:

RELEASE SUMMARY
- AAB/APKS/APK provenance:
- Minify/resource shrinking:
- Manifest/signing/content policies:
- Smoke API lanes:
- Hilt/Room/WorkManager:
- Crash/ANR/network/demo-data:

BRANCH PROTECTION
- Required checks:
- Up-to-date requirement:
- Review/conversation rules:
- Admin bypass:
- CODEOWNERS:

ARTIFACTS
- Artifact manifest/checksums:
- Final evidence report checksum:
- Sensitive-data scan:

RUN COMPARISON
- Semantic equivalence:
- Allowed volatile differences:
- Unexpected differences:

FINAL DECISION
- Merge permitted: YES | NO
- Blocking gates:
- Required remediation:
```