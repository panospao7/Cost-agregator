# PR-GR-08q…n — Triage newly observed trusted DB findings

## Agent mission

Resolve **only real, current, trusted DB findings** exposed after GR-09.

This is a repeatable series of small PRs, not a bulk cleanup. Each batch may address **1–25 unique finding fingerprints**. The batch must use one frozen authoritative D4 report and must remove every selected finding without hiding it through a baseline, a broad policy expansion, or advisory relabeling.

## Current-state grounding

At the reviewed branch state, the active policy is schema v2, the DB CLI runs source-root validation, Room inventory, v2 policy loading, exact source evidence, structural validation, then D4 discovery. A trusted report exits `0` when clean and `1` when it has findings; blocking diagnostics produce exit `2`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_db_access_boundaries.py))

The previous GR-08 program closed 497 historical findings and GR-09 intentionally created an empty `db_access_v2.json` baseline. A new finding must therefore remain visible as a ratchet violation; it is not approved debt. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717d0bf4325a54e7022437095aff09474a2c))

The repository already has:
- tracked GR-08 batch manifests and exact-policy seed artifacts;
- `build_db_finding_triage.py`, which generates **PENDING** triage entries only;
- `generate_db_baseline_v2.py`, which accepts only reviewed temporary debt and must not be used in GR-08. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/ci/db-findings/GR-08p2.yml))

## Naming rule

`GR-08q…n` is a placeholder series name.

Before creating a branch or manifest:
1. inspect `docs/ci/db-findings/`;
2. find the highest real closed batch identifier;
3. choose the next unused identifier;
4. do not collide with archival filenames such as `db_ownership_policy.legacy.gr08q.yml`.

Use, for example:

```text
GR-08q
GR-08q1
GR-08r
```

only if that identifier is unambiguous in the repository.

---

# Entry gate — mandatory

Do **not** start a GR-08 batch unless GATE-00R produced two semantically identical runs for the exact current SHA and all conditions below hold.

| Condition | Required |
|---|---|
| Checkout | clean |
| Direct DB CLI | exit `1` |
| DB report | protocol v2, `trusted: true` |
| Blocking diagnostics | zero |
| Finding count | at least one |
| Reports | same finding/advisory sets across GATE-00R run 01 and run 02 |
| Active policy | v2 loader/evidence pass |
| Source roots / inventory | trusted |
| Baseline | valid empty/current v2 baseline, hash recorded |
| Ratchet | expected violation behavior, not infrastructure error |
| Direct versus ratchet/suite | same DB authority inputs and semantically compatible result |

### Decision table

| GATE-00R DB result | Action |
|---|---|
| Exit `0`, trusted | Do not create GR-08. There are no findings to triage. |
| Exit `1`, trusted, no blocking diagnostics | Create one GR-08 batch. |
| Exit `2` | Stop. This is scanner/policy/root/evidence infrastructure work, not triage. |
| Exit `1` plus new/unreviewed advisory diagnostics | Freeze the report; perform GR-10C inventory/relevance review before changing policy. |
| Direct CLI and ratchet disagree | Stop. Route to GR-10A/control-plane repair. |
| Reports differ between run 01 and run 02 | Stop. Determinism failure; do not triage unstable fingerprints. |

### Advisory-diagnostic rule

Historical evidence recorded 20 advisory `DB_SIGNATURE_UNRESOLVED` diagnostics, but **do not assume 20 is still correct**. The batch input must record the exact current advisory-diagnostic set from GATE-00R. Any new, removed, reclassified, or changed advisory diagnostic is an explicit delta and may not be silently absorbed by a GR-08 policy change. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717d0bf4325a54e7022437095aff09474a2c))

---

# Hard stops

Stop and report instead of guessing if any condition occurs:

- direct DB CLI exits `2`;
- report is untrusted, malformed, missing, or has duplicate findings;
- a selected fingerprint is absent from the frozen report;
- selected count exceeds 25 unique fingerprints;
- a batch would modify `config/baselines/db_access_v2.json`;
- a finding’s architecture role is unclear;
- a proposed exact-policy row relies on a simple owner name, wildcard, inferred DAO, sibling overload, or guessed receiver;
- a structural-operation finding is being “fixed” through ownership policy;
- source changes would affect unselected writers without explicit review;
- policy/source evidence has diagnostics;
- direct, ratchet, static-suite, and Gradle command authority differ;
- a policy seed/generator/promotion command is unclear from current `--help` or source;
- another agent owns Gradle.

A trusted finding is never justification to weaken the detector.

---

# Scope

## Allowed

- one tracked batch manifest;
- one exact-policy seed artifact where needed;
- narrow active v2 policy regeneration/promotion produced by existing tooling;
- narrowly related source changes for selected `CODE_FIX` findings;
- scanner/parser fixtures for selected `ANALYZER_REPAIR` findings;
- focused Kotlin and Python tests;
- reviewed before/after report artifacts under `build/guard-debug/`.

## Forbidden

- `config/baselines/db_access_v2.json`;
- `guard_ratchet.py --update-baseline`;
- bulk baseline generation;
- time policy or time exception changes;
- unrelated structural-exception changes;
- unrelated Gradle/workflow/registry changes;
- blanket allowlists;
- wildcard policy rows;
- “temporary” policy authorization;
- manually pasting unreviewed rows into the active policy;
- treating advisory status as a disposition.

## Preservation checks

For every batch:

```bash
git diff --exit-code -- config/baselines/db_access_v2.json
```

Also require no unrelated changes to:

```bash
git diff --exit-code -- \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/production_source_roots.yml \
  scripts/ci/guard_ratchet.py \
  scripts/ci/run_static_guard_suite.py \
  app/build.gradle.kts \
  .github/workflows
```

Production Kotlin may change only for selected `CODE_FIX` findings.

---

# Definitions

## Finding occurrence versus fingerprint

- A **finding occurrence** is one report finding at one source location.
- A **fingerprint** is the stable ratchet identity.
- A batch cap is **25 unique fingerprints**.
- If one fingerprint has multiple source occurrences, record every occurrence and its count. Never silently collapse locations.

## Allowed implementation dispositions

Every selected fingerprint gets exactly one implementation disposition:

| Disposition | Meaning |
|---|---|
| `CODE_FIX` | The write is architecturally illegal. Move/remove it through the documented legal lifecycle/coordinator/repository path. |
| `EXACT_POLICY` | The write is already a deliberate legal writer, but its exact v2 mutation identity is missing from policy. |
| `ANALYZER_REPAIR` | The report is false because parser/resolver/scanner behavior is wrong or incomplete. |

The existing generic triage tool uses classifications such as `PENDING`, `LEGAL_WRITER_POLICY_MISSING`, `REAL_ARCHITECTURE_VIOLATION`, and `PARSER_FALSE_POSITIVE`. Keep its generated output as evidence; do not replace its schema. In the reviewed batch manifest, map the selected finding’s implementation disposition to the corresponding generated classification where applicable. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/test_db_finding_triage.py))

## Rule-family routing

| Report rule | Allowed GR-08 disposition |
|---|---|
| `DB_UNAUTHORIZED_MUTATION` | `CODE_FIX`, `EXACT_POLICY`, or `ANALYZER_REPAIR` |
| `DB_FORBIDDEN_STRUCTURAL_OPERATION` | Usually `CODE_FIX` or a separately approved structural-policy PR; never ownership-policy authorization |
| Any unresolved signature/scope diagnostic | Not a finding batch item; route to GR-10C or the owning analyzer repair |
| Unknown/new rule | Stop and inspect the closed rule catalog before choosing a disposition |

---

# Required artifacts

## A. Frozen input evidence

Create:

```text
build/guard-debug/gr08q/
  before.json
  before.sha256
  before-semantic-summary.json
  input-hashes.txt
  advisory-set.json
  direct-cli.log
  ratchet.log
```

Record:

```text
START_SHA
START_TREE_SHA
START_REPORT_SHA256
ACTIVE_POLICY_SHA256
SOURCE_ROOT_MANIFEST_SHA256
STRUCTURAL_POLICY_SHA256
STRUCTURAL_MANIFEST_SHA256
BASELINE_SHA256
ADVISORY_SET_SHA256
```

`advisory-set.json` must be canonical: diagnostics sorted by code, path, line/context, and bounded advisory marker. It is evidence only, not an allowlist.

## B. Generated scratch triage artifact

Use the existing generator, not manual extraction:

```bash
python3 scripts/ci/build_db_finding_triage.py \
  --v2-report build/guard-debug/gr08q/before.json \
  --v1-baseline config/baselines/db_access.json \
  --reference-sha "$START_SHA" \
  --output build/guard-debug/gr08q/generated-triage.yml
```

The old v1 baseline is used only for historical crosswalk context by this tool. It is not active authorization and must not be edited. The tool emits PENDING entries; it must not infer ownership, expiry, legal status, or debt. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/build_db_finding_triage.py))

## C. Tracked reviewed batch manifest

Create:

```text
docs/ci/db-findings/GR-08q.yml
```

First copy the currently accepted structure from the latest tracked GR-08 manifest. Do not invent a competing parser/schema.

It must contain, at minimum:

```yaml
schemaVersion: <existing supported value>
batchId: GR-08q
startSha: <40-char SHA>
startReportPath: build/guard-debug/gr08q/before.json
startReportSha256: <sha256>
activePolicySha256: <sha256>
sourceRootManifestSha256: <sha256>
baselineSha256: <sha256>
advisorySetSha256: <sha256>

selectedFingerprints:
  - <exact fingerprint>

selectedOccurrenceCounts:
  <fingerprint>: <positive integer>

findings:
  - fingerprint: <exact fingerprint>
    ruleId: <exact report rule>
    path: <repository-relative path>
    line: <report line if present>
    callable:
      ownerFqcn: <exact>
      kind: <exact>
      method: <exact>
      receiver: <exact/null>
      parameterTypes: <ordered list>
    mutation:
      daoAccessor: <exact source spelling>
      daoFqcn: <exact Room DAO FQCN>
      operation: <exact Room operation>
    implementationDisposition: CODE_FIX | EXACT_POLICY | ANALYZER_REPAIR
    generatedTriageClassification: <existing-tool classification>
    rationale: <specific architecture decision>
    owner: "@panospao7"
    linkedIssue: <issue/work item>
    evidence:
      - <source/test/report reference>
    expectedResolution: <specific source/policy/analyzer outcome>
    outOfBatchImpactExpected: <none or listed exact fingerprints>
```

The manifest must state:

> This is a reviewed resolution plan for current findings. It is not a baseline, an allowlist, or authorization by itself.

## D. Exact-policy seed, only when needed

If one or more selected items are `EXACT_POLICY`, add:

```text
docs/ci/db-findings/GR-08q-seed.yml
```

Reuse the exact accepted seed format. Existing GR-08 seed files are full v2-shaped policy documents loaded through the v2 loader and evidence verifier; they do not bypass duplicate-key or promotion checks. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/ci/db-findings/GR-08p2-seed.yml))

No seed file is needed for a pure code-fix or analyzer-repair batch.

## E. Post-change evidence

```text
build/guard-debug/gr08q/
  after.json
  after.sha256
  after-semantic-summary.json
  finding-delta.json
  advisory-delta.json
  policy-evidence.json
  static-suite/
```

`finding-delta.json` must contain:

```text
selectedRemoved
selectedStillPresent
unexpectedRemoved
unexpectedAdded
unchanged
diagnosticsBefore
diagnosticsAfter
advisoryBefore
advisoryAfter
```

---

# Mandatory workflow

## Step 1 — Freeze the exact trusted report

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

mkdir -p build/guard-debug/gr08q

set +e
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr08q/before.json
rc=$?
set -e

test "$rc" -eq 1
sha256sum build/guard-debug/gr08q/before.json \
  > build/guard-debug/gr08q/before.sha256
```

Then programmatically verify:

```text
report.guard == "db_access"
report.schema_version == 2
report.statistics.trusted == true
blocking diagnostics == []
finding count > 0
```

Do not use stdout as finding authority.

## Step 2 — Confirm the current control plane

Run the exact DB ratchet argv currently used by the static suite. Extract it from current code; do not manually reconstruct it from memory.

Expected result while untriaged findings remain:

```text
direct CLI: exit 1
ratchet: exit 1
static suite: exit 1 because db_access is a blocking violation
```

Expected result must **not** be exit `2`.

If the suite invokes a materially different child policy, root manifest, baseline, interpreter, or report mode, stop and route to GR-10A.

## Step 3 — Generate scratch triage and select a batch

Generate `generated-triage.yml`.

Select at most 25 unique fingerprints according to this order:

1. clear direct DAO mutations in forbidden UI/ViewModel/service locations;
2. related mutations caused by one small architecture defect;
3. strongly evidenced legal writers missing exact v2 policy rows;
4. isolated analyzer false positives with minimal fixtures.

Do not select based only on count. Do not select more than 25 merely because historical batches did.

If one architecture refactor affects more than 25 fingerprints:
- split by independently reviewable callable groups; or
- stop and create a separately approved architecture-refactor plan.

## Step 4 — Review every selected finding before editing

For each selected item answer all questions below in the tracked manifest.

### Common questions

1. What exact callable and mutation key created this fingerprint?
2. Is the source location under the declared production root?
3. Is the report’s DAO accessor/FQCN/operation identity exact?
4. Is this direct mutation a real source construct, not a string/comment?
5. Does the finding appear in both GATE-00R runs?
6. Does changing it affect another selected or unselected fingerprint?
7. Which tests demonstrate the intended behavior after resolution?

### `CODE_FIX` decision questions

1. Why is this writer illegal in its current architectural layer?
2. What documented legal owner should perform the mutation?
3. How are transaction, cancellation, retry, restore, barrier, and lifecycle behavior preserved?
4. Will the source still expose a direct DAO write from an unauthorized callable?
5. Which Kotlin tests protect the repaired behavior?

### `EXACT_POLICY` decision questions

All answers must be “yes”:

1. Is the owner a documented legal writer rather than a UI/ViewModel/service convenience path?
2. Does one exact callable own this mutation?
3. Are owner FQCN, callable kind, method, receiver, and ordered parameters exact?
4. Are DAO accessor, DAO FQCN, and operation exact?
5. Does the entire callable’s observed mutation set have exact closure under v2 evidence?
6. Is barrier metadata locally consistent without claiming unimplemented dominance or call-graph proof?
7. Can four near-miss tests prove authorization does not broaden?

A policy row must never be added merely because a writer “already works.”

### `ANALYZER_REPAIR` decision questions

1. What exact analyzer assumption is wrong?
2. Can a minimal synthetic fixture reproduce the false result before repair?
3. Can an adjacent forbidden case still fail after repair?
4. Does the repair preserve exact owner/callable/DAO identity?
5. Could the repair convert a DB-relevant diagnostic into advisory or invisible?
6. Does the real-tree report improve without adding new findings or diagnostics?

## Step 5 — Add tests before implementation

Add the narrowest necessary regression tests before changing source, policy, or scanner behavior.

Do not mix unrelated dispositions in one batch unless they resolve the same small root cause and remain within the cap.

## Step 6 — Implement one disposition at a time

### A. `CODE_FIX`

Requirements:

- route mutation through the legal lifecycle/coordinator/repository path;
- preserve transaction boundaries and error/cancellation semantics;
- add focused Kotlin tests;
- remove the original direct unauthorized mutation;
- do not “fix” it by adding policy to a forbidden caller.

### B. `EXACT_POLICY`

Requirements:

1. create exact seed rows through the existing accepted seed mechanism;
2. run current generator/promotion tooling discovered from its `--help`;
3. never hand-edit generated active policy output;
4. verify v2 loader and exact source evidence;
5. inspect canonical ordering and exact policy diff;
6. prove these near misses remain unauthorized:
   - wrong owner FQCN;
   - sibling or nested owner;
   - wrong overload / receiver / parameter type or order;
   - wrong DAO accessor or DAO FQCN;
   - wrong Room operation.

### C. `ANALYZER_REPAIR`

Requirements:

1. retain a minimal failing fixture;
2. prove it produced the wrong finding/diagnostic before repair;
3. prove the intended result after repair;
4. add at least one adjacent forbidden fixture;
5. add a DB-relevant adversarial variant;
6. run the real repository scan;
7. document why source/policy changes are not the correct fix.

Never “repair” an analyzer by adding an exception, broad regex, name-only match, or advisory marker.

## Step 7 — Produce and review the after report

```bash
set +e
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr08q/after.json
rc=$?
set -e

test "$rc" -eq 0 -o "$rc" -eq 1
```

Required postconditions:

```text
trusted == true
blocking diagnostics == []
every selected fingerprint absent
no unexpected finding added
advisory set == before advisory set
```

Unexpectedly removed findings may be acceptable only if:
- they result directly from the reviewed selected repair;
- every removed fingerprint is listed in `finding-delta.json`;
- the manifest explains why it disappeared;
- it was not hidden through policy/baseline broadening.

## Step 8 — Validate all relevant control planes

Run:

```bash
python3 -m pytest \
  scripts/ci/test_db_finding_triage.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  -v --tb=short
```

Then:

```bash
python3 scripts/ci/verify_guard_registry.py
```

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/gr08q/static-suite
```

### Expected exit behavior

| State after this batch | DB CLI / ratchet / suite expectation |
|---|---|
| More trusted DB findings remain | DB path remains exit `1`, not `2` |
| This was the final finding batch | DB CLI and ratchet exit `0` |
| Any blocking diagnostic occurs | exit `2`; batch is blocked |
| Any new advisory appears | stop; record and route to GR-10C |

Only when no other agent owns Gradle:

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

For a partial batch, a Gradle failure caused by remaining real findings is expected; an infrastructure failure is not.

## Step 9 — Run GATE-00R again

After the batch, run the exact GATE-00R capture sequence for the new SHA.

Do not claim completion from only a local before/after DB command.

---

# Mandatory adversarial checks

Use temporary fixtures/reports; do not contaminate production policy.

1. Add one new unauthorized mutation → direct scan and empty-baseline ratchet exit `1`.
2. Remove a selected policy row → original finding reappears.
3. Alter owner FQCN, overload, receiver, parameter order, DAO accessor, DAO FQCN, or operation → authorization fails.
4. Add a sibling callable with the same method name → it remains unauthorized.
5. Try to add selected finding to `db_access_v2.json` → preservation check fails.
6. Try `guard_ratchet.py --update-baseline` for protocol v2 → controlled refusal.
7. For analyzer repair, place the same syntax next to a true DAO mutation → it must become a finding or blocking diagnostic, never an invisible/advisory pass.
8. Re-run after report twice → selected-removal and advisory semantics remain identical.

---

# Definition of done — one batch

A GR-08 batch is complete only when:

- it began from exact-SHA GATE-00R evidence;
- it selected 1–25 unique current fingerprints;
- every selected fingerprint is absent from the trusted after report;
- no new finding was introduced;
- no blocking diagnostic appeared;
- advisory diagnostics did not drift;
- every policy row, source change, or parser repair has required evidence;
- the active DB baseline is byte-identical;
- all out-of-batch changes are explained;
- direct CLI, ratchet, suite, and Gradle outcomes are recorded truthfully.

## Definition of done — series

The GR-08q…n series ends only when a fresh exact-SHA direct DB scan returns:

```text
exit 0
trusted true
finding count 0
blocking diagnostic count 0
```

That does **not** by itself close advisory debt; that is GR-10C.

## Required completion report

```text
PR: GR-08q
START SHA:
END SHA:
START REPORT SHA256:
END REPORT SHA256:
ACTIVE POLICY SHA BEFORE/AFTER:
SOURCE ROOT MANIFEST SHA:
DB BASELINE SHA BEFORE/AFTER:

SELECTED UNIQUE FINGERPRINTS:
SELECTED OCCURRENCES:
DISPOSITIONS:
  CODE_FIX:
  EXACT_POLICY:
  ANALYZER_REPAIR:

SELECTED REMOVED:
UNEXPECTEDLY REMOVED:
NEW FINDINGS:
BLOCKING DIAGNOSTICS BEFORE/AFTER:
ADVISORY DIAGNOSTICS BEFORE/AFTER:

POLICY ROWS ADDED:
PRODUCTION KOTLIN CHANGED: yes/no
BASELINE CHANGED: no
STRUCTURAL POLICY CHANGED: no

DIRECT DB CLI:
RATCHET:
STATIC SUITE DB RESULT:
GRADLE DB RESULT:
ADVERSARIAL CHECKS:
GATE-00R REPRODUCED: yes/no

NEXT BATCH OR GR-10C READINESS:
UNEXPECTED DIFFERENCES:
```