# DB Finding Triage Procedure

**Scope:** PR-D5 -- Classify findings and migrate DB baseline  
**Status:** PENDING -- triage tools created, awaiting final inventory  
**Ledger:** `docs/ci/GUARD_FINDING_DB_V2_LEDGER.md`  
**Plan:** `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` §12

---

## 1. Purpose

This document defines the procedure for classifying DB access findings and
migrating the baseline from v1 (coarse rule+file fingerprints) to v2
(symbol-level, multiplicity-aware structured fingerprints).

The triage process reconciles the old 15 coarse baseline entries with the
final v2 finding inventory without normalizing unknown violations.

---

## 2. Prerequisites

Before triage can begin:

1. **PR-D1 through PR-D4 must be complete** -- the final scanner must emit
   protocol-v2 structured reports with exact callable signatures,
   Room-derived mutators, declaration-level scanning, and structured output.

2. **Final inventory must be generated:**
   ```bash
   python scripts/verify_db_access_boundaries.py \
     --inventory-only \
     --findings-output build/guard-v2/db-final-inventory.json
   ```

3. **V1 baseline must be frozen** in `build/guard-v2/before/db_access_v1.json`.

4. **Reference SHA must be approved** for historical proof.

---

## 3. Step 1: Generate Triage File

Run the triage builder to crosswalk old entries and create PENDING entries:

```bash
python scripts/ci/build_db_finding_triage.py \
  --v2-report build/guard-v2/db-final-inventory.json \
  --v1-baseline config/baselines/db_access.json \
  --output build/guard-v2/DB_ACCESS_V2_TRIAGE.yml \
  --reference-sha <APPROVED_SHA>
```

The tool will:
- Parse every v1 baseline entry (rule family + path)
- Crosswalk each to v2 findings by semantic rule/path
- Build PENDING entries for every current v2 finding
- Diagnose duplicate exact source occurrences
- Write deterministic YAML

**No classification is inferred.** Every entry starts as PENDING.

---

## 4. Step 2: Review Crosswalk Results

Examine the crosswalk section of the triage file:

| Outcome | Action |
|---|---|
| `ONE_TO_ONE` | Direct mapping; review the v2 finding for classification |
| `ONE_TO_MANY` | Multiple v2 findings from one v1 entry; each needs classification |
| `NO_CURRENT_MATCH` | V1 entry has no v2 counterpart; may be resolved or migrated |
| `UNRESOLVED_RULE_MAPPING` | V1 rule family cannot be mapped; manual investigation needed |

---

## 5. Step 3: Classify Findings

Process findings in batches of no more than 25 per agent session.

For each PENDING entry, determine the classification:

### 5.1 `LEGAL_WRITER_POLICY_MISSING`

**Criteria:**
- The writer is a legitimate lifecycle/coordinator writer
- It writes through an approved DAO with proper barriers
- It needs an exact signature policy entry

**Action:**
1. Inspect lifecycle ownership
2. Inspect barrier behavior
3. Add exact signature policy entry
4. Add policy-source evidence test
5. Change classification to `LEGAL_WRITER_POLICY_MISSING`
6. Set `owner`, `linked_issue`, `reason`

**Never baseline this classification.** Remove from finding set by adding policy.

### 5.2 `REAL_ARCHITECTURE_VIOLATION`

**Criteria:**
- The writer bypasses established lifecycle paths
- It writes directly to DAOs from forbidden layers
- It violates architecture invariants

**Action:**
1. Route through legal owner
2. Add regression fixture
3. Change classification to `REAL_ARCHITECTURE_VIOLATION`
4. Set `owner`, `linked_issue`, `reason`

**Never baseline this classification.** Fix the violation.

### 5.3 `PARSER_FALSE_POSITIVE`

**Criteria:**
- The scanner incorrectly identifies a non-mutation as a mutation
- The finding is caused by unsupported syntax the analyzer misparses
- The finding does not correspond to any actual DB operation

**Action:**
1. Fix the analyzer
2. Add positive and negative parser fixtures
3. Change classification to `PARSER_FALSE_POSITIVE`
4. Set `owner`, `linked_issue`, `reason`

**Never baseline this classification.** Fix the parser.

### 5.4 `PREEXISTING_TEMPORARY_DEBT`

**Criteria:**
- Proven present at the approved reference SHA
- No safer immediate fix exists
- Reviewer approves the debt

**Action:**
1. Verify historical presence at reference SHA
2. Assign owner
3. Link issue
4. Provide reason
5. Set expiry date
6. Add evidence (e.g., worktree comparison output)
7. Change classification to `PREEXISTING_TEMPORARY_DEBT`
8. Set all required metadata

**Only this classification can become a baseline entry.**

### 5.5 `STRUCTURAL_OPERATION`

**Criteria:**
- The operation is a structural DB operation (migration, backup, restore)
- It can be represented in the structural exceptions policy

**Action:**
1. Approve through exact structural policy if legal
2. Otherwise remove/fix the operation
3. Change classification to `STRUCTURAL_OPERATION`

**Do not baseline if structural policy can represent it.**

### 5.6 `ANALYZER_UNSUPPORTED`

**Criteria:**
- The analyzer cannot parse the syntax
- The method body is unsupported
- The expression body is unsupported

**Action:**
1. The analyzer must exit 2 for this case
2. Parser support must be added
3. Change classification to `ANALYZER_UNSUPPORTED`

**Never baseline this classification.** Add parser support.

### 5.7 `DUPLICATE_DETECTION`

**Criteria:**
- The finding is a duplicate of another finding by exact source range
- Same rule, path, location, symbol, identity

**Action:**
1. Deduplicate by exact source range
2. Add regression test
3. Change classification to `DUPLICATE_DETECTION`

**Never baseline this classification.** Deduplicate.

---

## 6. Step 4: Generate Baseline Candidate

After all entries are classified (none remain PENDING):

```bash
python scripts/ci/generate_db_baseline_v2.py \
  --triage build/guard-v2/DB_ACCESS_V2_TRIAGE.yml \
  --v2-report build/guard-v2/db-final-inventory.json \
  --output build/guard-v2/db_access_v2_candidate.json
```

The generator will:
- Accept only `PREEXISTING_TEMPORARY_DEBT` entries with complete metadata
- Reject entries with missing owner/issue/reason/expiry
- Reject expired entries
- Reject entries with unknown reference SHA
- Reject entries not in the current v2 report
- Write a v2 baseline candidate (never the active baseline)

---

## 7. Step 5: Review Candidate

```bash
git diff --no-index \
  config/baselines/db_access.json \
  build/guard-v2/db_access_v2_candidate.json
```

Review:
- All entries have complete metadata
- No expired debt
- All entries have approved reference SHA
- Entry counts match current report

---

## 8. Step 6: Activate Baseline

Only after review approval:

```bash
cp build/guard-v2/db_access_v2_candidate.json \
  config/baselines/db_access.json
```

Then verify:

```bash
python scripts/ci/guard_ratchet.py \
  --guard-name db_access \
  --command-arg=python \
  --command-arg=scripts/verify_db_access_boundaries.py \
  --command-arg=--fail-on-violation \
  --baseline config/baselines/db_access.json \
  --fail-on-violation \
  --ci-mode
```

Expected:
```
exit 0
schema mismatch: 0
new keys/counts: 0
resolved keys/counts: 0
expired debt: 0
infrastructure diagnostics: 0
```

---

## 9. Commit Strategy

Commits should remain separate:

```
fix(db): resolve unsafe DB writer batch N
fix(ci): correct DB scanner false positives batch N
chore(ci): register exact legal DB writers batch N
chore(ci): migrate reviewed DB debt baseline to v2
```

---

## 10. Batched Review Rules

- Process no more than 25 findings per agent batch
- Each batch reports: finding count, classifications, policy additions,
  code fixes, parser fixes, remaining pending, baseline candidates
- An independent reviewer validates all `LEGAL_WRITER_POLICY_MISSING` and
  `PREEXISTING_TEMPORARY_DEBT` classifications

---

## 11. Files

| File | Purpose |
|---|---|
| `scripts/ci/build_db_finding_triage.py` | Triage builder |
| `scripts/ci/generate_db_baseline_v2.py` | Baseline candidate generator |
| `scripts/ci/test_db_finding_triage.py` | Tests |
| `docs/ci/DB_ACCESS_V2_TRIAGE_SCHEMA.md` | Triage schema documentation |
| `docs/ci/DB_TRIAGE_PROCEDURE.md` | This procedure document |

---

## 12. References

| Document | Location |
|---|---|
| Discovery plan | `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` §12 |
| V2 ledger | `docs/ci/GUARD_FINDING_DB_V2_LEDGER.md` |
| Finding protocol | `docs/ci/GUARD_FINDING_PROTOCOL.md` |
| Guard framework | `docs/ci/guard-framework.md` |
| Guard policy | `docs/ci/guard-policy.md` |
