# PR-GR-10C — DB diagnostic precision and advisory-debt closure

## Agent mission

Eliminate the current DB scanner’s advisory diagnostic debt **without weakening DB detection**.

The goal is not “keep the gate green despite diagnostics.” The goal is:

> A clean DB report means no unauthorized mutation, no blocking diagnostic, and no residual advisory scanner uncertainty in the real production tree.

This PR may be implemented as a bounded serial series:

```text
GR-10C-00  inventory and proof design
GR-10C-01  one parser/relevance root-cause family
GR-10C-02  next root-cause family
...
GR-10C-final  zero-advisory closure
```

Do not land a giant “fix all diagnostics” rewrite if the diagnostics have multiple causes.

## Current-state grounding

The scanner currently distinguishes blocking versus advisory diagnostics by whether the enclosing declaration contains recognized DB-surface evidence. Advisory diagnostics retain `controlled_context["advisory"] = true`; they remain in reports but do not make the run untrusted. Pre-scan failures and unresolved DB-relevant behavior remain blocking. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_db_access_boundaries.py))

The GR-09 evidence recorded 20 advisory-only `DB_SIGNATURE_UNRESOLVED` diagnostics, but that is historical evidence from August 29, 2026—not a permanent approved count. GATE-00R must establish the current inventory. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717d0bf4325a54e7022437095aff09474a2c))

The empty v2 DB baseline is not an advisory-diagnostic allowlist. It must remain unchanged. ([github.com](https://github.com/panospao7/Cost-agregator/commit/f96f717d0bf4325a54e7022437095aff09474a2c))

---

# Core principle

## What advisory means today

Advisory currently means:

```text
The scanner could not completely model a callable,
but its present direct source range did not show recognized DB-surface evidence.
```

It does **not** mean:

```text
This code is approved.
This diagnostic is harmless forever.
This source is excluded from future DB scanning.
This diagnostic can be omitted.
This diagnostic can be baselined.
```

## Required final state

```text
Current real-tree advisory diagnostic count: 0
Current real-tree blocking diagnostic count: 0
Current real-tree DB finding count: 0
Direct CLI / ratchet / static suite semantic results reproducible twice
```

If zero advisory diagnostics cannot be reached because a parser capability requires larger design work, GR-10C must stop honestly and split into a dedicated parser/design PR. It must not convert unresolved cases into permanent “acceptable” advisory debt.

---

# Entry gate and ordering

## Mandatory GATE-00R inputs

Do not begin code changes until GATE-00R provides:

```text
actual target SHA
run-01 and run-02 DB reports
trusted status
finding fingerprints
blocking diagnostics
advisory diagnostics
policy/root/baseline hashes
direct CLI result
ratchet result
static-suite result
```

## Decision table

| GATE-00R result | GR-10C action |
|---|---|
| DB exit `2` | Do not treat as advisory debt. Repair the blocking root cause first. |
| DB exit `1`, trusted, advisory set stable | GR-08 has priority for real findings; GR-10C-00 may inventory only. |
| DB exit `0`, trusted, advisory count > 0 | Start GR-10C. |
| DB exit `0`, trusted, advisory count = 0 | GR-10C is unnecessary; retain zero-diagnostic regression tests. |
| Advisory set differs between runs | Stop. Determinism/report-contract defect. |
| New advisory appears after unrelated work | Freeze it; do not update a test pin. Start GR-10C inventory for that delta. |
| Direct and ratchet disagree | Stop. Route to GR-10A/control-plane ownership. |

## Why GR-08 normally comes first

Real trusted DB findings are architecture violations. Advisory diagnostics are scanner-quality debt. Do not use analyzer cleanup to delay fixing a known unauthorized mutation.

Exception: GR-10C may take priority when a newly introduced advisory diagnostic makes it unclear whether the scanner still sees the relevant DB surface. In that case, only the narrow diagnostic-proof slice may proceed; no broad policy changes may be merged.

---

# Scope

## Allowed

- `scripts/db_guard/scanner.py`;
- declaration/callable parser and resolver code needed for a named diagnostic;
- controlled diagnostic catalog/report-schema code, only if required for stable identification;
- Python scanner/CLI/ratchet tests;
- synthetic Kotlin fixtures;
- diagnostic inventory/closure documentation;
- narrowly related DB evidence tests.

## Forbidden

- production Kotlin changes merely to make diagnostics disappear;
- DB ownership policy changes;
- DB structural-exception changes;
- DB baseline changes;
- ratchet baseline generation;
- changing the time guard;
- changing suite/registry/Gradle authority; that is GR-10A;
- broad “ignore non-DB files” behavior;
- blanket suppression by package/path/class;
- making `advisory: true` easier to emit;
- deleting real-tree regression assertions without a replacement proof;
- changing a blocking diagnostic into advisory merely to obtain exit `0`.

---

# Hard stops

Stop and report if:

- a current advisory cannot be reproduced from the frozen report;
- a diagnostic has no safe stable identity;
- the parser repair requires guessing callable/DAO identity;
- a proposed relevance classifier cannot distinguish a direct DB mutation from non-DB syntax;
- a change makes a fixture with a DAO mutation advisory or clean;
- a source-root, inventory, active policy, source-evidence, or structural failure becomes advisory;
- a change increases DB findings or blocking diagnostics;
- current direct and ratchet reports disagree;
- the work grows beyond one diagnostic root-cause family;
- a solution is “update expected advisory count from N to M.”

---

# Required deliverables

## 1. Advisory diagnostic inventory

Create:

```text
docs/ci/db-diagnostics/GR-10C_ADVISORY_INVENTORY.yml
```

This is a **review ledger**, not an allowlist.

Required form:

```yaml
schemaVersion: 1
guard: db_access
startSha: <40-char SHA>
startReportSha256: <sha256>
activePolicySha256: <sha256>
sourceRootManifestSha256: <sha256>
baselineSha256: <sha256>
advisorySetSha256: <sha256>
status: OPEN | CLOSED

entries:
  - diagnosticKey: <canonical reviewed key>
    code: <closed diagnostic code>
    path: <repository-relative path>
    line: <line if report provides one>
    controlledContext: <bounded report context only>
    sourceCallable: <exact identity if safely available>
    rootCauseFamily: <closed classification>
    dbRelevanceDecision: ADVISORY
    whyNotBlockingToday: <precise scanner-contract explanation>
    maskingRisk: LOW | MEDIUM | HIGH
    disposition: ELIMINATE_BY_PARSER_REPAIR |
                 RECLASSIFY_BLOCKING_AND_FIX |
                 REQUIRE_SEPARATE_DESIGN |
                 INVALID_OR_STALE
    owner: "@panospao7"
    linkedIssue: <work item>
    plannedSlice: GR-10C-01
    fixture: <test fixture path/name>
    completionEvidence: []
```

The document must say:

> This inventory does not authorize diagnostics, suppress findings, alter trust, or permit future advisory output. A new advisory diagnostic is a regression until individually reviewed.

## 2. Canonical advisory-set comparator

Add a narrow audit utility only if the repository lacks one:

```text
scripts/ci/verify_db_advisory_inventory.py
scripts/ci/test_verify_db_advisory_inventory.py
```

It must:

- consume a validated protocol-v2 DB report;
- reject untrusted reports;
- extract only diagnostics whose bounded context has boolean `advisory == true`;
- sort canonically;
- compare exact current advisory keys against the inventory;
- fail on new, missing, duplicate, malformed, or ambiguous diagnostic entries;
- write deterministic review output;
- never alter CLI trust, ratchet behavior, policy, baseline, or exit classification;
- support `--require-zero`, which fails unless the report has zero advisory diagnostics.

It is an **audit companion**, not a runtime authorization input.

### Diagnostic identity rule

Use the report’s existing canonical diagnostic fields where possible:

```text
code
path
bounded controlled context
line, if present
```

Before adding a new identity field:
1. inspect the current `GuardDiagnostic` schema and protocol tests;
2. prove the field is bounded, deterministic, source-safe, and non-secret;
3. register it in the closed report contract;
4. add protocol compatibility tests.

If two current diagnostics cannot be distinguished safely, that is a report-model defect. Fix the model; do not silently merge them.

## 3. Root-cause matrix

Add to the inventory:

```text
diagnostic count by code
diagnostic count by rootCauseFamily
diagnostic count by source package
diagnostic count by masking risk
diagnostic count by planned slice
```

Use only closed root-cause families:

```text
CALLABLE_DECLARATION_PARSE_GAP
PROPERTY_ACCESSOR_PARSE_GAP
EXPRESSION_BODY_PARSE_GAP
TYPE_NORMALIZATION_GAP
DECLARATION_RANGE_GAP
DAO_SURFACE_RELEVANCE_GAP
STRUCTURAL_SCOPE_PARSE_GAP
REPORT_IDENTITY_GAP
STALE_OR_NONREPRODUCIBLE
```

Do not add vague categories such as `OTHER`, `KNOWN`, or `IGNORE`.

---

# Required safety model

## Permitted resolution paths

### A. Eliminate by parser/resolver repair

Use when the callable can be modeled exactly after a narrow repair.

Required proof:

```text
before: advisory diagnostic appears
after: diagnostic disappears
adjacent non-DB case: still behaves correctly
adjacent DB mutation: produces finding or blocking diagnostic
real tree: selected advisory key disappears only
```

### B. Reclassify blocking and fix

Use when review reveals the diagnostic could conceal a real DB mutation or the relevance classifier cannot prove DB irrelevance.

Required outcome:

```text
diagnostic becomes blocking
source/analyzer ambiguity is repaired
final state has neither advisory nor blocking diagnostic
```

Never leave a high-risk unresolved case as advisory.

### C. Require separate design

Use when resolution needs a larger parser architecture, such as a safe Kotlin construct family that cannot be modeled with a narrow fix.

Required outcome:

```text
GR-10C slice stops
dedicated parser/design PR is created
no severity suppression is merged
```

## Forbidden resolution path

This is forbidden:

```text
diagnostic exists
→ set advisory = true
→ update expected count
→ declare report trusted
```

The current classifier already marks some diagnostics advisory based on no recognized DB surface. GR-10C must improve precision or remove the unresolved condition; it must not broaden that escape hatch. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/scanner.py))

---

# Implementation sequence

## Step 1 — Freeze current diagnostics

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

mkdir -p build/guard-debug/gr10c

set +e
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr10c/before.json
rc=$?
set -e

test "$rc" -eq 0 -o "$rc" -eq 1
```

Require:

```text
statistics.trusted == true
blocking diagnostics == []
```

Extract advisory diagnostics with a small reviewed script or the new comparator. Persist:

```text
before-advisories.json
before-advisories.sha256
before-report.sha256
```

Repeat the scan. The canonical advisory set must match exactly.

## Step 2 — Validate provenance one diagnostic at a time

For every advisory entry:

1. open the exact repository-relative source path;
2. locate the reported declaration/range;
3. reproduce the parser/scanner route in a minimal fixture;
4. identify why exact callable identity failed;
5. identify why current DB relevance classified it advisory;
6. inspect the callable for:
   - DAO accessor calls;
   - DAO operation calls;
   - DAO-typed parameters;
   - local DAO aliases;
   - property DAO accessors;
   - structural handles;
   - `execSQL`, writable database, open-helper, file/database operations;
7. classify masking risk;
8. choose exactly one permitted disposition.

Do not infer that “UI code cannot touch DB.” The scanner’s architecture exists specifically to catch UI/service writes.

## Step 3 — Prove the relevance classifier is not hiding DB work

The current classifier treats known DAO names, DAO operation names, and structural patterns as DB-surface evidence. Unknown behavior on a verified structural handle remains blocking. ([github.com](https://github.com/panospao7/Cost-agregator/commit/478c6873ff1bdfdc1730b8893636ac439eece974))

For every root-cause family, add temporary synthetic fixtures proving:

| Fixture | Required result |
|---|---|
| Same unresolved syntax, no DB use | May be advisory before repair; must be clean after valid parser repair |
| Same syntax + direct DAO mutation | finding or blocking diagnostic; never advisory-only clean |
| Same syntax + DAO-typed parameter | finding or blocking diagnostic |
| Same syntax + local DAO alias | finding or blocking diagnostic |
| Same syntax + property DAO accessor | finding or blocking diagnostic |
| Same syntax + structural database handle | blocking diagnostic or structural finding |
| Unknown operation on verified DB handle | blocking diagnostic |
| Comments/string lookalikes | no false DB finding |
| Invalid policy/root/inventory | exit `2`, never advisory |

A fixture that becomes trusted-clean while containing an unauthorized direct DB mutation is a release blocker.

## Step 4 — Split implementation by one root-cause family

Default slice size:

```text
one root-cause family
up to five canonical advisory keys
one parser/scanner area
```

Split if:
- more than one parser family is involved;
- a change reaches both declaration parsing and policy source evidence;
- a repair changes relevance classification;
- a repair touches a structural operation path;
- review cannot explain every affected real-tree diagnostic.

Suggested series:

```text
GR-10C-00  freeze inventory and add audit comparator
GR-10C-01  exact callable-signature parser family
GR-10C-02  accessor/property parser family
GR-10C-03  expression-body/range family
GR-10C-04  relevance-classifier hardening, only if evidence requires it
GR-10C-final  require zero advisory diagnostics in real-tree tests
```

## Step 5 — Add regression tests before implementation

Every slice must add:

1. **Before fixture** — reproduces exact advisory diagnostic.
2. **After fixture** — diagnostic disappears or becomes correctly blocking.
3. **Adjacent legal non-DB fixture** — remains clean.
4. **Adjacent direct DAO fixture** — never becomes advisory/clean.
5. **Adjacent structural-handle fixture** — stays blocking when unsupported.
6. **Determinism test** — two scanner runs yield identical diagnostics/findings.
7. **Real-tree regression test** — expected advisory set decreases only by selected keys.

Where a diagnostic has no stable fixture, stop and repair report identity before touching parser behavior.

## Step 6 — Implement conservatively

### Parser/resolver repairs

Allowed:
- exact syntax support;
- bounded declaration range correction;
- canonical type normalization reuse;
- exact accessor resolution;
- exact property/accessor parsing;
- exact expression-body support.

Forbidden:
- selecting first/last ambiguous callable;
- owner simple-name fallback;
- file-wide or sibling-overload evidence;
- “best effort” DAO identity;
- regex broadening without negative fixtures;
- silently discarding unsupported declaration bodies.

### Relevance-classifier repairs

A relevance change is high risk.

It may be changed only when tests prove both:

```text
non-DB unsupported declaration does not emit needless scanner uncertainty
AND
every nearby direct DB mutation remains visible as finding or blocking diagnostic
```

Do not change relevance simply to reduce the report count.

## Step 7 — Review real-tree deltas

After each slice:

```bash
set +e
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr10c/after.json
rc=$?
set -e

test "$rc" -eq 0 -o "$rc" -eq 1
```

Required:

```text
trusted == true
blocking diagnostic count == 0
new DB findings == 0
new advisory keys == 0
selected advisory keys removed == planned count
unselected advisory keys unchanged
policy hash unchanged
baseline hash unchanged
```

If the scan turns exit `2`, do not relabel it advisory. Diagnose and fix the actual uncertainty.

## Step 8 — Validate ratchet behavior

The ratchet currently excludes only diagnostics with the exact bounded `advisory` marker from its blocking diagnostic gate. A malformed or unmarked diagnostic must remain exit `2`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/guard_ratchet.py))

Add/maintain tests proving:

1. advisory-only report is accepted for normal fingerprint comparison;
2. non-advisory report diagnostic exits `2`;
3. `advisory: "true"` string is not accepted as boolean true;
4. missing advisory marker is blocking;
5. a new finding with no diagnostics exits `1`;
6. zero findings + zero diagnostics exits `0`;
7. advisory inventory audit detects a new key;
8. final `--require-zero` audit passes only when no advisory diagnostics remain.

## Step 9 — Final zero-advisory closure

Only after all slices are complete:

```bash
python3 scripts/ci/verify_db_advisory_inventory.py \
  --report build/guard-debug/gr10c/final.json \
  --inventory docs/ci/db-diagnostics/GR-10C_ADVISORY_INVENTORY.yml \
  --require-zero
```

Then run:

```bash
python3 -m pytest \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  scripts/ci/test_verify_db_advisory_inventory.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_guard_registry.py
```

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/gr10c/static-suite
```

Only when no other agent owns Gradle:

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

Finally rerun GATE-00R twice for the final SHA.

---

# Preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access_v2.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/production_source_roots.yml \
  app/src/main \
  app/build.gradle.kts \
  .github/workflows
```

Expected production Kotlin change:

```text
no
```

Expected DB policy/baseline change:

```text
no
```

If a source fixture is needed, place it under existing test fixture locations, never production source.

---

# Definition of done

GR-10C is complete only when:

- every advisory diagnostic from the frozen inventory has a reviewed disposition;
- no current diagnostic is accepted merely because it is advisory;
- every parser/relevance change has positive, negative, and DB-adversarial fixtures;
- no direct DB mutation can become advisory or invisible because of an unsupported syntax shape;
- all pre-scan/policy/root/inventory uncertainty remains blocking;
- current real-tree advisory diagnostic count is zero;
- current real-tree blocking diagnostic count is zero;
- current real-tree DB finding count is zero, or any remaining finding is handled by a separately active GR-08 batch;
- DB policy, structural policy, and DB baseline are byte-identical;
- direct CLI, ratchet, suite, Gradle DB task, and two GATE-00R captures agree semantically.

## Required completion report

```text
PR: GR-10C
START SHA:
END SHA:
START REPORT SHA256:
FINAL REPORT SHA256:
ACTIVE POLICY SHA BEFORE/AFTER:
ROOT MANIFEST SHA BEFORE/AFTER:
BASELINE SHA BEFORE/AFTER:

ADVISORY COUNT BEFORE:
ADVISORY COUNT AFTER:
BLOCKING DIAGNOSTIC COUNT BEFORE/AFTER:
DB FINDING COUNT BEFORE/AFTER:

ROOT-CAUSE FAMILIES:
SLICES COMPLETED:
DIAGNOSTICS ELIMINATED:
DIAGNOSTICS RECLASSIFIED BLOCKING THEN FIXED:
DIAGNOSTICS REQUIRING FOLLOW-UP DESIGN:
NEW ADVISORY DIAGNOSTICS: 0
NEW DB FINDINGS: 0

ADVISORY INVENTORY AUDIT:
REQUIRE-ZERO RESULT:
PARSER/SCANNER TESTS:
RATCHET TESTS:
REAL-TREE DIRECT CLI:
STATIC SUITE DB RESULT:
GRADLE DB RESULT:
GATE-00R DOUBLE-CAPTURE REPRODUCIBLE: yes/no

PRODUCTION KOTLIN CHANGED: no
ACTIVE DB POLICY CHANGED: no
DB BASELINE CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR:
```