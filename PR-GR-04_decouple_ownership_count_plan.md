# PR-GR-04 — Decouple ownership count from structural manifest

## Agent mission

Remove the structural-manifest dependency on the number of ownership-policy entries while preserving every structural-exception integrity control.

At the reviewed SHA, the structural manifest stores `ownership_entries: 99` beside `structural_entries: 62`; the verifier compares that ownership count to current ownership entries and the DB CLI passes `len(ownership_entries)` into structural validation. This makes a valid v2 split into exact mutation entries fail for an unrelated structural-manifest reason. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/verify_db_access_boundaries.py))

---

## Preconditions

Do not begin until:

- GR-02 candidate output is strict v2 and reproducible;
- GR-03 source-root manifest/inventory proof passes;
- checkout is clean;
- structural manifest currently validates through its direct test suite;
- active policy/baseline hashes are recorded.

This PR is intentionally narrow. It must not become a way to relax structural exceptions or hide ownership-policy migration work.

---

## Non-goals

Do **not**:

- alter `db_ownership_policy.yml`;
- alter the v2 candidate;
- alter `db_structural_exceptions.yml` tuple content;
- update `db_access.json`;
- reduce structural tuple-set equality;
- remove immutable structural classification checks;
- remove structural source evidence;
- replace ownership count with another ownership-count lock elsewhere;
- activate v2 enforcement;
- modify production Kotlin.

---

## Target contract

The structural expected-method manifest governs **structural exceptions only**.

It retains:

- exact `expected` and `fixtures` tuple classification;
- exact tuple-set equivalence with `db_structural_exceptions.yml`;
- duplicate detection;
- structural source evidence;
- structural entry count audit pin.

It no longer governs:

- ownership policy entry count;
- candidate entry count;
- migration report count;
- v1-to-v2 entry splitting.

### Required manifest schema after change

```yaml
counts:
  structural_entries: 62
```

`ownership_entries` must be removed from the checked-in manifest and rejected as an unknown count key thereafter.

Do not remove `structural_entries`; retain it as a structural-only audit tripwire even though tuple equality also supplies strong integrity evidence.

---

## Required implementation changes

### Step 1 — Locate the one authoritative structural verifier

At this point in the sequence, it may remain in:

```text
scripts/verify_db_access_boundaries.py
```

or have been extracted by GR-01.

Modify only the authoritative implementation. Do not leave a compatibility verifier that still accepts or checks `ownership_count`.

### Step 2 — Narrow manifest count schema

Update manifest metadata validation:

- allowed count keys become exactly `{structural_entries}`;
- `ownership_entries` becomes an explicit unknown-key failure;
- `counts` remains required if current structural contract requires it;
- `structural_entries` remains integer, non-boolean, non-negative.

Update comments/docs so they no longer say the manifest pins ownership-policy counts.

### Step 3 — Remove ownership coupling from verifier API

Change the verifier conceptually from:

```text
verify_structural_exceptions_manifest(
    structural_entries,
    manifest,
    source_root,
    ownership_count=...,
    enforce_canonical_contract=...,
)
```

to:

```text
verify_structural_exceptions_manifest(
    structural_entries,
    manifest,
    source_roots,
    enforce_canonical_contract=...,
)
```

Requirements:

- remove `ownership_count` parameter completely;
- remove `PINNED_OWNERSHIP_ENTRY_COUNT`;
- remove every call-site calculation/passing of ownership count;
- retain canonical structural count checking:
  - canonical manifest → pinned structural count;
  - fixture/custom manifest → current structural tuple count;
- retain exact tuple-set and source-evidence checks.

Do not leave a deprecated unused parameter; that permits future accidental recoupling.

### Step 4 — Update CLI pipeline without changing ownership evidence

The DB CLI must continue to:

1. independently validate ownership policy/evidence;
2. independently validate structural policy/manifest/evidence;
3. fail with controlled infrastructure status when either is invalid.

The structural validation call must not inspect ownership entry cardinality.

### Step 5 — Update fixture builders and tests carefully

Search all tests for:

```text
ownership_entries
PINNED_OWNERSHIP_ENTRY_COUNT
ownership_count=
99
```

Do not perform blind text replacement. Update fixture builders so their default manifest has only `structural_entries`.

Keep any `99` values that are legitimate historical fixture inputs only if they are no longer structural-manifest authority.

### Step 6 — Update documentation

Update:

- comments in the structural expected-method manifest;
- DB guard documentation;
- GR-00/GR-03 evidence documentation only if it inaccurately identifies ownership count as structural input.

State clearly:

> Ownership cardinality is an observational migration metric, not structural authorization evidence.

---

## Required test matrix

### Manifest schema tests

1. checked-in manifest with only `structural_entries` is valid;
2. `ownership_entries` is rejected as unknown;
3. missing `structural_entries` fails;
4. boolean, negative, string, float, and extra count keys fail;
5. structural count mismatch fails;
6. canonical structural count mismatch fails;
7. fixture structural count mismatch fails.

### Structural integrity regression tests

1. missing structural tuple fails;
2. extra structural tuple fails;
3. duplicate tuple fails;
4. moved expected/fixture classification fails;
5. changed operation on same path/class/method pattern fails;
6. structural source evidence remains required;
7. unchanged checked-in structural policy + manifest passes direct structural verification.

### Decoupling tests

1. structural verifier API has no ownership-count parameter;
2. a valid structural manifest passes regardless of independent ownership entry count;
3. one valid ownership entry versus two valid exact ownership entries produces the same structural-verifier result;
4. v2-style splitting of one legacy multi-DAO authorization into multiple ownership rows cannot trigger structural count failure;
5. CLI does not pass ownership count into structural validation;
6. old manifest with `ownership_entries` exits controlled failure rather than being silently tolerated.

### Current-repository regression tests

1. no executable code references `PINNED_OWNERSHIP_ENTRY_COUNT`;
2. no manifest parser permits `ownership_entries`;
3. structural policy contents are byte-identical before/after;
4. active ownership policy and candidate are byte-identical before/after;
5. current normal DB CLI remains blocked for the known active-policy reason, not a newly introduced structural-count error.

---

## Required post-completion checks

```bash
python3 -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_guard_registry.py

python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/gr04/inventory.json \
  --dump-room-mutators build/guard-debug/gr04/room-mutators.json
```

Expected: inventory-only remains trusted and exits `0`.

```bash
git grep -nE \
  "PINNED_OWNERSHIP_ENTRY_COUNT|ownership_entries|ownership_count=" \
  -- scripts config docs \
  | tee build/guard-debug/gr04/ownership-count-audit.txt
```

Review every result. Allowed matches are only historical prose/test names that explicitly assert rejection; no live structural-manifest authority may remain.

Run normal CLI, ratchet, static suite, and Gradle DB task through the GR-00 capture workflow.

Expected transitional outcome:

- normal active DB gate may still exit `2` because v2 policy is not active/complete;
- it must not fail because structural manifest expects an ownership count;
- no baseline update is permitted.

### Preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml \
  config/guards/db_structural_exceptions.yml \
  app/src/main
```

The only policy-data change expected is:

```text
config/guards/db_structural_exceptions_expected_methods.yml
```

Inspect its diff manually: it should remove ownership-count coupling only, not structural tuples or evidence metadata.

---

## Definition of done

GR-04 is complete only when:

- structural manifest schema contains only structural count authority;
- ownership count is absent from structural verifier API and CLI wiring;
- old ownership-count manifest metadata fails closed;
- structural tuple-set, classification, count, and source-evidence protections remain intact;
- valid ownership-policy entry splitting can no longer break structural validation;
- active ownership policy, candidate, baseline, structural exception tuples, and production Kotlin remain unchanged;
- normal DB gate remains truthfully blocked pending GR-05 onward.

## Required agent completion report

```text
PR: GR-04
START SHA:
END SHA:
FILES CHANGED:
STRUCTURAL TUPLES CHANGED: no
STRUCTURAL ENTRY COUNT BEFORE/AFTER:
ACTIVE POLICY CHANGED: no
CANDIDATE CHANGED: no
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
OWNERSHIP-COUNT REFERENCES REMOVED:
STRUCTURAL REGRESSION TESTS:
COMMANDS RUN:
RESULTS:
EXPECTED BLOCKED STATE:
UNEXPECTED DIFFERENCES:
NEXT PR: GR-05
```