# PR-GR-08a…n — Triage real DB findings in reviewed batches

## Agent mission

Resolve trustworthy v2 D4 findings in small, reviewable batches.

A batch contains **at most 25 unique finding fingerprints** from one trusted report. A finding is resolved only by one of these dispositions:

1. **CODE_FIX** — fix Kotlin through the documented legal lifecycle architecture path;
2. **EXACT_POLICY** — add one or more exact v2 policy rows proved by source evidence;
3. **ANALYZER_REPAIR** — repair a false positive/false diagnostic with regression fixtures.

No baseline update is permitted in any GR-08 PR.

## Preconditions

Do not start a batch unless:

- GR-07 normal CLI output is `trusted: true`;
- normal CLI exits `0` or `1`, never `2`;
- report diagnostics are empty;
- report schema/version is accepted by current ratchet tooling;
- active policy is v2;
- source-root, v2 evidence, and structural checks pass;
- the exact start report, SHA, report hash, policy hash, and source-root-manifest hash are recorded.

If diagnostics appear at any point, stop. Diagnostics are not triage findings; return to GR-05, GR-06, or GR-07/analyzer repair.

## Batch artifact

For each batch create one tracked review manifest, for example:

`docs/ci/db-findings/GR-08a.yml`

Reuse the reviewed triage tooling introduced earlier in the branch; do not create a second incompatible manifest/parser.

Required fields:

```text
schemaVersion
batchId
startSha
startReportSha256
activePolicySha256
selectedFingerprints
findings
  fingerprint
  ruleId
  path
  callable identity
  disposition
  rationale
  owner
  linkedIssue
  expiry (only for unresolved debt candidates)
  expectedResolution
```

Rules:

- `selectedFingerprints` contains 1–25 unique fingerprints;
- every listed finding exists in the exact start report;
- no finding appears in two open batches;
- disposition is one of the three closed values above;
- no generic “accepted”, “ignored”, “later”, or “false positive” text-only disposition;
- baseline candidate status is documentation only; GR-09 owns baseline changes.

## Batch selection rules

Prioritize:

1. clear direct DAO writes in forbidden layers;
2. duplicate findings from one small architecture defect;
3. candidate-policy omissions with strong exact evidence;
4. analyzer false positives with minimal reproducible fixture.

Do not select by raw count alone. Keep related mutations together only if total unique fingerprints remain ≤25.

If a source refactor would touch more than 25 findings, split it before implementation or create a separately approved architectural refactor PR.

## Disposition contracts

### A. CODE_FIX

Use only when the source behavior violates ownership architecture.

Requirements:

- route the write through the documented legal coordinator/service/repository;
- preserve transaction, barrier, worker, privacy, cancellation, retry, and lifecycle semantics;
- no direct DAO move to another forbidden class;
- add targeted Kotlin tests;
- remove the original finding without authorizing it in policy.

A code fix may incidentally resolve other findings. Record all incidental deltas and explain them. Do not let an incidental resolution justify a baseline update.

### B. EXACT_POLICY

Use only when the source callable is already an intentional legal writer and all of the following are exact:

- owner FQCN;
- callable kind/name/receiver/ordered parameters;
- DAO accessor;
- DAO FQCN;
- Room target operation;
- source evidence closure for the whole callable.

Requirements:

- add only the minimum exact v2 mutation rows;
- add/revise reason, owner, linked issue;
- rerun v2 source evidence;
- add near-miss tests proving an overload, sibling owner, wrong DAO, and wrong operation remain unauthorized.

Do not use policy to legitimize a forbidden UI/ViewModel/service writer merely because it currently works.

### C. ANALYZER_REPAIR

Use only when the D4 result is false.

Requirements:

1. preserve a minimal fixture that fails before repair;
2. prove it passes after repair;
3. add an adjacent forbidden fixture that still fails;
4. prove no source range, root, callable, or DAO identity was guessed;
5. rerun the actual repository scan;
6. document why the result was analyzer error rather than policy debt.

No analyzer repair may merely suppress a rule code or add a broad allowlist.

## Mandatory per-batch workflow

### 1. Freeze input

```bash
git status --short
git rev-parse HEAD
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr08a/before.json
```

Require:

```text
exit = 1 or 0
trusted = true
diagnostics = []
```

Generate the batch manifest from the exact report using the existing reviewed triage tool.

### 2. Validate batch scope

The verifier must reject:

- more than 25 fingerprints;
- duplicate fingerprints;
- missing report fingerprint;
- changed source report hash;
- unregistered disposition;
- missing rationale/owner/issue;
- baseline modification;
- unreviewed out-of-batch newly authorized mutation.

### 3. Implement one disposition at a time

Do not mix unconnected policy additions, production refactors, and parser repairs in one batch unless they directly resolve selected fingerprints and the 25 cap still holds.

### 4. Re-run full trusted scan

Produce `after.json`, then calculate:

```text
selected findings removed
unexpected findings removed
unexpected findings added
unchanged findings
diagnostics
```

Required outcome:

- every selected fingerprint is removed or the PR is blocked;
- no new finding is introduced;
- no diagnostic appears;
- all out-of-batch deltas are explicitly reviewed.

### 5. Review diff semantically

Review:

- source diff;
- active v2 policy diff;
- evidence result;
- batch manifest;
- before/after finding deltas;
- baseline diff, which must be empty.

## Required tests for every batch

### Always

```bash
python3 -m pytest \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_guard_registry.py
```

### CODE_FIX additionally

- focused Kotlin unit tests for the lifecycle/coordinator changed;
- compile affected Kotlin;
- worker tests when a worker changed;
- transaction/restore/barrier tests when relevant.

### EXACT_POLICY additionally

- v2 loader test;
- v2 source-evidence test;
- exact D4 pass fixture;
- four near-miss failure fixtures:
  - wrong overload;
  - wrong owner;
  - wrong DAO FQCN/accessor;
  - wrong operation.

### ANALYZER_REPAIR additionally

- before/after regression fixture;
- adjacent forbidden fixture;
- actual repository scan;
- deterministic report comparison.

## Completion checks

```bash
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr08a/after.json
```

Expected: trusted `0` or `1`; no diagnostics.

Run GR-00 capture after every batch. Do not require ratchet green yet: GR-09 owns approved v2 debt baseline.

## Preservation checks

```bash
git diff --exit-code -- config/baselines/db_access.json
```

Also reject unrelated changes to:

```bash
git diff --exit-code -- \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/build.gradle.kts \
  .github/workflows/ci.yml
```

Production Kotlin may change only for selected `CODE_FIX` findings.

## Definition of done for one batch

A GR-08x PR is complete only when:

- it addressed 1–25 exact fingerprints;
- all selected findings disappeared;
- the post-report is trusted and diagnostic-free;
- each disposition has required evidence/tests;
- no baseline changed;
- no unreviewed policy expansion or out-of-batch delta exists.

## Required completion report

```text
PR: GR-08x
START SHA:
END SHA:
START REPORT SHA:
END REPORT SHA:
SELECTED FINDINGS: N (must be <=25)
DISPOSITIONS:
  CODE_FIX:
  EXACT_POLICY:
  ANALYZER_REPAIR:
SELECTED FINDINGS REMOVED:
INCIDENTAL FINDINGS REMOVED:
NEW FINDINGS:
DIAGNOSTICS:
ACTIVE POLICY DELTA:
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: yes/no
COMMANDS RUN:
RESULTS:
NEXT BATCH / GR-09 READINESS:
```