# PR-GR-10D — Documentation and evidence truth sync

## Agent mission

Make repository documentation, status claims, local commands, CI commands, registry data, and exact-SHA evidence agree.

This is not a cosmetic documentation PR. It removes operationally dangerous ambiguity: a developer or AI agent must be able to read the current docs and determine:

1. what is actually enforced now;
2. what is only architectural intent;
3. what was verified, at which exact SHA;
4. what remains partial, blocked, or future work;
5. which command is canonical;
6. which policy/baseline/scope file is authoritative.

At the reviewed SHA, `docs/DB_WRITE_OWNERSHIP.md` still contains pre-activation claims that v1 is active and GR-07 is pending, while the live DB loader requires schema-v2 active policy. This PR must correct that mismatch without rewriting historical evidence as though it were current evidence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/docs/DB_WRITE_OWNERSHIP.md))

## Required ordering

Start only after:

```text
GATE-00R complete for the actual start SHA
GR-10A canonical command ownership merged
GR-10B unified production-source scope merged
```

GR-10C does not have to be fully complete to begin, but its exact current state must be represented honestly:

```text
zero advisory diagnostics → VERIFIED_AT_SHA only with actual evidence
advisory work open → PARTIAL / IN_PROGRESS
blocking diagnostics → BLOCKED
```

Do not wait for perfect guard results before fixing stale documentation. Do not claim perfect guard results unless evidence proves them.

## Current documentation contract

`AGENTS.md` requires documentation to match code and prohibits `DONE`, `GREEN`, or `complete` claims unless implementation, tests, review, and required guard gates have actually passed. The final acceptance gate also requires registry-backed documentation, exact-SHA evidence, generated counts rather than copied counts, and explicit treatment of partial/unsupported scope. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/AGENTS.md))

---

# Scope

## Allowed

- Guard/framework documentation;
- README guard/status sections;
- DB ownership and barrier documentation;
- architecture documents affected by current guard authority;
- current-status/evidence records;
- documentation generators and validators;
- registry documentation anchors/metadata;
- narrow tests for documentation/evidence validation;
- CI artifact references only where already produced by GATE-00R.

## Forbidden

- Production Kotlin changes;
- guard scanner semantic changes;
- DB policy, structural policy, source-root manifest, baseline, or time-exception changes;
- changing a command merely because the document is stale;
- changing a guard severity to match a document;
- deleting historical evidence;
- editing a historical report to make it look current;
- committing generated `build/` reports;
- claiming branch protection or CI success without platform evidence.

## Preservation checks

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

Expected: no changes.

---

# Required deliverables

First inspect whether equivalent artifacts already exist. Extend one existing authority rather than creating a competing document system.

## 1. Documentation authority index

Create or strengthen:

```text
docs/ci/GUARD_DOCUMENT_INDEX.yml
```

This is the machine-readable map of every guard-related document.

Each entry must include:

```yaml
- id: db-write-ownership
  path: docs/DB_WRITE_OWNERSHIP.md
  classification: CURRENT_ARCHITECTURE
  authorityType: explanatory
  owner: "@panospao7"
  lastReviewedSha: <40-char SHA>
  currentStateSource:
    registry: scripts/ci/guard_registry.py
    implementation: scripts/verify_db_access_boundaries.py
    evidence: docs/ci/GUARD_EVIDENCE_INDEX.yml
  generatedSections:
    - guard-command-reference
    - guard-status-summary
  historicalClaimsAllowed: false
```

Allowed classifications:

```text
CURRENT_ARCHITECTURE
CURRENT_OPERATIONS
CURRENT_EVIDENCE
NORMATIVE_TARGET
HISTORICAL_RECORD
PLAN_OR_BACKLOG
GENERATED_REFERENCE
```

Rules:

- Every document that claims a guard state, command, policy authority, baseline meaning, source scope, release status, or PR completion state appears exactly once.
- Historical documents are preserved, but must be labelled `HISTORICAL_RECORD`.
- Plans may describe future work but may not claim it is active.
- Current documents may not rely on historical prose as authority.

## 2. Exact-SHA evidence index

Create:

```text
docs/ci/GUARD_EVIDENCE_INDEX.yml
```

This is a compact tracked index of evidence bundles, not a copy of raw logs.

Required shape:

```yaml
schemaVersion: 1
records:
  - evidenceId: gate-00r-<target-sha>
    targetSha: <40-char SHA>
    targetTreeSha: <40-char SHA>
    baseSha: <40-char SHA>
    mergeBaseSha: <40-char SHA>
    workingTreeClean: true
    captureRuns:
      - runId: run-01
        semanticDigestSha256: <sha256>
        artifactManifestSha256: <sha256>
      - runId: run-02
        semanticDigestSha256: <sha256>
        artifactManifestSha256: <sha256>
    reproducible: true|false
    guardResults:
      db_access:
        directExit: 0|1|2
        trusted: true|false
        findingCount: <integer>
        blockingDiagnosticCount: <integer>
        advisoryDiagnosticCount: <integer>
        ratchetExit: 0|1|2
        staticSuiteOutcome: PASS|VIOLATION|INFRASTRUCTURE
        gradleOutcome: PASS|VIOLATION|INFRASTRUCTURE|NOT_RUN
      time_boundaries:
        exit: 0|1|2
        findingCount: <integer>
    artifactReference:
      workflowRunId: <optional non-secret ID>
      artifactName: <artifact name>
      artifactChecksum: <sha256>
    status: COMPLETE|INCOMPLETE|HISTORICAL
```

Rules:

- No raw source, absolute local paths, user data, tokens, logs, or secret values.
- A current evidence record must name the exact target SHA.
- A record is not “current” merely because it is newest in the file.
- An evidence record may be called `VERIFIED_AT_SHA` only when two semantic captures match and all required commands/results are recorded.
- If Gradle was unavailable, state `NOT_RUN`; do not substitute “pass.”

## 3. Generated current guard reference

Generate, do not hand-maintain:

```text
docs/ci/GUARD_COMMANDS.generated.md
docs/ci/GUARD_STATUS.generated.md
```

Inputs must be:

```text
canonical registry/execution plan from GR-10A
source-scope metadata from GR-10B
evidence index
```

The generated reference must list, per guard:

```text
guard ID
mode
owner
canonical local/CI command identity
source-scope classification
policy / allowlist / baseline inputs where applicable
current evidence status
exact verified SHA, if any
documentation anchor
```

Do not paste command lines independently into README, DB ownership docs, or workflow comments. Those documents may link/reference the generated section.

## 4. Documentation truth validator

Create:

```text
scripts/ci/verify_guard_docs_truth.py
scripts/ci/test_verify_guard_docs_truth.py
```

The validator must:

1. load the document index;
2. load canonical registry/execution metadata;
3. load evidence index;
4. verify every indexed document exists;
5. verify every active guard has a documentation anchor;
6. verify every active registry entry has one current documentation owner;
7. verify current documents do not contain unsupported completion claims;
8. verify generated documents are byte-reproducible;
9. verify current command sections derive from the canonical plan;
10. verify evidence claims reference real exact-SHA records;
11. verify historical documents are visibly marked with SHA/date/scope;
12. reject stale/manual count claims in generated/current status sections;
13. reject unknown baseline/policy/source-root references;
14. produce deterministic safe JSON/Markdown output.

The validator must not try to infer every prose sentence through AI or unrestricted regex. It validates a **closed structured claim contract**, then uses narrow stale-claim checks for known dangerous wording.

## 5. Current-state terminology contract

Use only these status labels:

| Label | Meaning |
|---|---|
| `IMPLEMENTED_UNVERIFIED` | Code/config exists, but no qualifying exact-SHA evidence is recorded. |
| `VERIFIED_AT_SHA` | Exact SHA and qualifying evidence are linked. |
| `PARTIAL` | Some defined slices complete; remaining scope is named. |
| `BLOCKED` | A known prerequisite/diagnostic prevents the claim. |
| `HISTORICAL` | True only for a named older SHA/time period. |
| `SUPERSEDED` | Retained for history; not current authority. |
| `PLANNED` | Future work only. |

Forbidden in current-state documents unless validator-backed evidence supports them:

```text
DONE
GREEN
COMPLETE
fully enforced
release ready
all guards pass
current
latest
verified
```

A historical document may use such wording only with an explicit marker such as:

```text
Historical record — applies only to SHA <full SHA>, captured on <UTC date>.
It is not evidence for current HEAD.
```

---

# Mandatory audit

## Step 1 — Freeze actual start state

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}
git hash-object scripts/ci/guard_registry.py
git hash-object docs/DB_WRITE_OWNERSHIP.md
git hash-object FINAL_CI_GUARD_ACCEPTANCE_GATE.md
```

Record the GATE-00R evidence ID and GR-10A/GR-10B end SHAs.

Hard stop if the checkout is dirty.

## Step 2 — Inventory all guard-related documentation

Search tracked text only:

```bash
git grep -nEi \
  'guard|ratchet|baseline|allowlist|ownership policy|write barrier|\
source root|static suite|GR-[0-9]|TIME-[0-9]|migration|release ready|green|done' \
  -- \
  '*.md' '*.yml' '*.yaml' '*.txt' \
  > build/guard-debug/gr10d/documentation-inventory.txt
```

For every result, classify it:

```text
CURRENT_FACT
NORMATIVE_REQUIREMENT
HISTORICAL_FACT
PLAN
GENERATED_REFERENCE
STALE_OR_AMBIGUOUS
NOT_GUARD_RELATED
```

No file may remain `STALE_OR_AMBIGUOUS` at PR completion.

## Step 3 — Establish source-of-truth hierarchy

The documentation must reflect this order:

```text
1. Executed exact-SHA evidence
2. Active implementation and loaded configuration
3. Canonical registry/execution plan
4. Generated current reference documents
5. Current explanatory architecture docs
6. Historical reports
7. Plans/backlogs
```

Important distinctions to state clearly:

```text
DB ownership policy ≠ DB ratchet baseline
DB ratchet baseline ≠ Room schema/migration baseline
implemented ≠ verified at current SHA
advisory diagnostic ≠ authorization
historical finding count ≠ current finding count
shadow analyzer ≠ active enforcement
```

## Step 4 — Correct current DB documentation

Review and correct `docs/DB_WRITE_OWNERSHIP.md` first.

Required corrections:

1. Remove current-state assertions that v1 remains active.
2. Remove claims that GR-07 activation is pending.
3. State that active ownership policy is schema v2 only if actual active loader/config confirms it.
4. State exact typed ownership identity at the appropriate abstraction level.
5. State that `barrierMode` is not dominance/call-graph proof until GR-12/GR-13/GR-15 complete.
6. State that the DB baseline is ratchet debt only and never authorization.
7. State advisory diagnostics truthfully:
   - reported;
   - not authorization;
   - current count comes from evidence, not copied prose;
   - closure status must be linked to GR-10C evidence.
8. Link command usage to generated command reference rather than copy obsolete commands.
9. Move obsolete PR-01/GR-02/GR-07 transition prose into a clearly labelled historical section or archive file.

The policy model currently defines `direct`, `helper`, and `workerMediated` as metadata modes, while source evidence explicitly avoids dominance/reachability claims. Documentation must not overstate that model. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/policy_model.py))

## Step 5 — Correct current guard-framework documentation

Audit and synchronize:

```text
README.md
AGENTS.md
FINAL_CI_GUARD_ACCEPTANCE_GATE.md
docs/architecture/LEGAL_PATHS.md
docs/architecture/* guard-related documents
docs/ci/* status/ledger documents
TEST_FAILURE_LEDGER.md
VALIDATION_FINDINGS*.md
```

Only edit files that the inventory proves are guard-relevant.

Rules:

- `AGENTS.md` remains instruction authority; do not turn it into an evidence ledger.
- The final acceptance gate remains normative unless an accepted implementation change is needed; do not dilute requirements to match incomplete evidence.
- `LEGAL_PATHS.md` may say which path is architecturally legal, but must not claim that structural proof exists before GR-12/13/15.
- Old failure ledgers must be labelled historical with their exact SHA.
- Current README claims must be generated/validated against registry/evidence rather than hand-copied counts.

## Step 6 — Add structured current-state blocks

Use a bounded marker rather than scattered prose, for example:

```markdown
<!-- GUARD_STATUS:BEGIN db_access -->
Status: VERIFIED_AT_SHA | PARTIAL | BLOCKED
Evidence: gate-00r-<sha>
Canonical command reference: GUARD_COMMANDS.generated.md#db_access
Scope: production-kotlin-all
<!-- GUARD_STATUS:END db_access -->
```

The renderer owns content inside generated markers.

Rules:

- Human prose may explain the guard.
- Generated status blocks own state, counts, SHA, commands, and mode.
- Manual edits inside generated blocks fail validation.
- Historical documents do not use current generated blocks.

## Step 7 — Preserve historical material honestly

Do not delete useful prior plans/reassessments.

For each historical file, choose:

```text
RETAIN_IN_PLACE_WITH_BANNER
MOVE_TO_docs/history_WITH_BANNER
SUPERSEDE_WITH_POINTER
```

A historical banner must include:

```text
as-of SHA
capture date if known
scope limitation
replacement current authority
```

Example:

```text
Historical assessment — applies to SHA 9b97e797... only.
Current implementation/evidence is indexed in docs/ci/GUARD_EVIDENCE_INDEX.yml.
```

Never alter historical numeric findings to make them match a later scan.

## Step 8 — Render and validate

Suggested commands:

```bash
python3 scripts/ci/render_guard_docs.py \
  --root . \
  --registry scripts/ci/guard_registry.py \
  --evidence docs/ci/GUARD_EVIDENCE_INDEX.yml \
  --document-index docs/ci/GUARD_DOCUMENT_INDEX.yml \
  --output-dir build/guard-debug/gr10d/rendered
```

```bash
python3 scripts/ci/verify_guard_docs_truth.py \
  --root . \
  --registry scripts/ci/guard_registry.py \
  --evidence docs/ci/GUARD_EVIDENCE_INDEX.yml \
  --document-index docs/ci/GUARD_DOCUMENT_INDEX.yml
```

Generated tracked files must byte-match the render output.

---

# Required validator tests

## Evidence integrity

1. abbreviated SHA fails;
2. nonexistent SHA fails;
3. evidence target SHA/report SHA mismatch fails;
4. one capture run only cannot produce `VERIFIED_AT_SHA`;
5. differing run semantic digests cannot produce `VERIFIED_AT_SHA`;
6. `trusted: false` DB report cannot support verified-clean DB wording;
7. missing artifact checksum fails;
8. unknown guard ID fails;
9. evidence from another branch/SHA cannot be labelled current.

## Documentation classification

1. every indexed document exists;
2. every active guard has one current documentation anchor;
3. every implementation has a registry anchor after GR-10A;
4. historical documents require an as-of SHA marker;
5. historical wording is permitted only in historical documents;
6. current documents cannot contain unqualified `v1 active` / `GR-07 pending` DB-state claims;
7. current docs cannot say a baseline authorizes writes;
8. current docs cannot call advisory diagnostics “approved” or “ignored”;
9. `DONE`, `GREEN`, and `complete` claims fail without linked qualifying evidence;
10. plan documents cannot present a future PR as active implementation.

## Generated-reference integrity

1. generated command content matches canonical GR-10A plan;
2. generated scope content matches GR-10B source-scope metadata;
3. policy/baseline paths match registry;
4. current counts come only from evidence index;
5. manual modification inside generated block fails;
6. generated output is deterministic across two runs;
7. no raw logs, absolute paths, secrets, or source snippets appear.

---

# Required validation

```bash
python3 -m pytest \
  scripts/ci/test_verify_guard_docs_truth.py \
  scripts/ci/test_render_guard_docs.py \
  scripts/ci/test_guard_registry.py \
  scripts/ci/test_guard_execution_plan.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_guard_registry.py --root .
```

```bash
python3 scripts/ci/verify_guard_docs_truth.py --root .
```

```bash
git diff --check
git diff --exit-code -- app/src/main config/baselines config/guards
```

Run GATE-00R after the PR only if command/doc rendering changes could affect capture invocation. Documentation-only results do not replace pre-existing GATE-00R evidence.

---

# Definition of done

GR-10D is complete only when:

- every guard-relevant document is classified;
- current implementation claims reference exact current authority;
- old claims are historical/superseded, not silently deleted or rewritten;
- DB docs no longer describe v1/pre-GR-07 state as current;
- barrier documentation does not claim proof beyond implemented analysis;
- policy, baseline, source-root, and exception distinctions are explicit;
- commands and counts are generated/validated, not manually copied;
- every `VERIFIED_AT_SHA` claim has reproducible exact-SHA evidence;
- partial/blocked work is explicitly partial/blocked;
- no production/configuration/baseline behavior changed.

## Required completion report

```text
PR: GR-10D
START SHA:
END SHA:
GATE-00R EVIDENCE ID:
GR-10A SHA:
GR-10B SHA:

DOCUMENTS INVENTORIED:
CURRENT DOCUMENTS UPDATED:
HISTORICAL DOCUMENTS BANNERED:
SUPERSEDED DOCUMENTS:
STALE/AMBIGUOUS CLAIMS BEFORE:
STALE/AMBIGUOUS CLAIMS AFTER: 0

GENERATED COMMAND REFERENCE: pass/fail
GENERATED STATUS REFERENCE: pass/fail
EVIDENCE INDEX VALID: yes/no
DOCUMENT INDEX VALID: yes/no
CURRENT DB DOC MATCHES V2 AUTHORITY: yes/no
UNQUALIFIED COMPLETION CLAIMS: 0

PYTHON TESTS:
REGISTRY VALIDATION:
DOC-TRUTH VALIDATION:

PRODUCTION KOTLIN CHANGED: no
DB POLICY CHANGED: no
DB BASELINE CHANGED: no
TIME EXCEPTIONS CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR: GR-11
```
