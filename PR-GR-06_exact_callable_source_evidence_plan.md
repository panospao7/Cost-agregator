# PR-GR-06 — Implement exact callable source evidence

## Agent mission

Replace legacy overload-union source evidence with a v2 verifier that proves policy coverage against one **exact callable-v2 identity** at a time.

This PR is shadow-only:

- it validates the GR-05 candidate;
- it records legacy-vs-v2 differences;
- it does not activate v2;
- it does not change active scanner authority;
- it does not update a baseline.

The existing scanner still compares legacy fields and uses the final component of `owner_fqcn`; that cannot remain the authority after activation. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e7b7597098dc2e7ac0a35f8342b20f9f4a420f41/scripts/db_guard/scanner.py))

## Preconditions

Do not start until:

- GR-05 candidate and accounting artifact reproduce;
- inventory-only is trusted;
- every activation-blocking GR-05 unresolved item is either resolved or explicitly accepted as a GR-07 blocker;
- checkout is clean;
- active policy, candidate, baseline, and source-root manifest hashes are recorded.

## Non-goals

Do not:

- promote the candidate;
- modify active policy;
- alter ratchet, baseline, Gradle, workflow, or static-suite behavior;
- make source-evidence success mean barrier dominance;
- reintroduce a compatibility fallback based on `(path, class, method)`;
- use legacy evidence as an authorization result;
- change production Kotlin.

## Required implementation boundary

Use and extend the existing `scripts/db_guard/policy_v2_evidence.py` module. Do not create a competing source-evidence engine.

The module must consume:

- typed `PolicyEntry` objects from `load_policy_v2`;
- the shared source-root set;
- Room inventory;
- declaration/callable parser results;
- the same typed DAO target resolution used by D4.

It must not import the DB CLI as its implementation authority.

## Exact evidence contract

Group candidate entries only by this full callable key:

```text
path
ownerFqcn
kind
method
receiver
ordered parameterTypes
```

For each callable group, the verifier must prove:

1. policy path is under a declared production root;
2. source file resolves safely and uniquely;
3. owner FQCN exists exactly once;
4. callable kind matches exactly;
5. method name matches exactly;
6. receiver matches exactly;
7. ordered parameter types match exactly;
8. the callable range is unique;
9. direct Room mutations in that exact range are discovered deterministically;
10. every discovered mutation key exactly equals the policy mutation-key set;
11. every policy mutation key actually occurs in that exact callable;
12. DAO accessor, DAO FQCN, Room operation, and Room target overload all match exactly.

A policy row for `save(String)` must never prove a mutation in `save(Long)`. A nested `Outer.Handle` must never be authorized by policy for an unrelated `Handle`.

## Required result model

Provide a pure result API equivalent to:

```text
verify_v2_policy_source_evidence(
    entries: Sequence[PolicyEntry],
    repo_root: Path,
    source_roots: SourceRootSet,
    inventory: RoomInventory,
) -> EvidenceResult
```

The result must contain only:

- trusted/untrusted state;
- deterministic callable-group results;
- deterministic controlled diagnostics;
- stable mutation-key counts;
- repository-relative paths where protocol allows them.

No `sys.exit`, raw source, raw exception messages, SQL, absolute paths, or unbounded parser details.

## Controlled failure behavior

Register all codes in the existing closed catalog and protocol tests. At minimum distinguish:

```text
DB_V2_POLICY_PATH_NOT_DECLARED
DB_V2_POLICY_SOURCE_UNREADABLE
DB_V2_POLICY_OWNER_MISSING
DB_V2_POLICY_OWNER_AMBIGUOUS
DB_V2_POLICY_CALLABLE_MISSING
DB_V2_POLICY_CALLABLE_AMBIGUOUS
DB_V2_POLICY_CALLABLE_IDENTITY_MISMATCH
DB_V2_POLICY_DAO_SCOPE_UNRESOLVED
DB_V2_POLICY_DAO_TARGET_AMBIGUOUS
DB_V2_POLICY_MUTATION_MISSING
DB_V2_POLICY_MUTATION_UNCOVERED
DB_V2_POLICY_MUTATION_EXTRA
DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT
```

Use current catalog naming conventions if they differ. Do not emit ad hoc free text.

Any source-evidence uncertainty is an untrusted infrastructure/configuration condition and maps to exit `2` in a CLI adapter.

## Barrier metadata rule

The source-evidence verifier may validate local consistency only:

- `direct`: exact local barrier syntax is present before the mutation in the same callable;
- `helper`: direct mode is not falsely claimed;
- `workerMediated`: direct mode is not falsely claimed.

It must not claim:

- control-flow dominance;
- helper reachability;
- worker-root mediation;
- exception/callback/lambda safety.

Those remain GR-11 onward.

## Shadow-comparison deliverable

Add or extend a thin explicit CLI adapter, for example:

```text
scripts/ci/verify_db_policy_v2_evidence.py
```

Its default behavior must be candidate-only and read-only.

Required flags/behavior:

- explicit v2 candidate path;
- explicit output report path;
- optional explicit legacy-shadow input;
- tokenized arguments only;
- no active-policy overwrite;
- no ratchet interaction;
- no default activation mode.

The shadow report must classify differences using the GR-05 crosswalk:

```text
EXPECTED_LEGACY_OVERLOAD_UNION
CANDIDATE_GAP
LEGACY_STALE_ENTRY
PARSER_OR_RESOLVER_DEFECT
UNREVIEWED_DIFFERENCE
```

A legacy/v2 difference never creates compatibility authorization. `CANDIDATE_GAP`, parser defects, and unreviewed differences block GR-07.

## Implementation sequence

### Step 1 — Lock the negative contract

Before changing implementation, add tests proving that evidence fails for:

- same method name, different overload;
- same simple class name, different FQCN;
- nested owner versus top-level owner;
- different receiver;
- swapped parameters;
- nullability difference;
- correct callable but wrong DAO accessor;
- correct accessor but wrong DAO FQCN;
- correct DAO but wrong operation;
- sibling callable containing the only mutation.

### Step 2 — Make exact callable lookup authoritative

Use parser declarations and canonical v2 signatures. Reject zero or multiple matches. Do not select first/last.

### Step 3 — Reuse scanner-grade mutation resolution

Do not add regex-only DAO resolution. Extract each mutation from the exact callable range using the same inventory-backed resolver used by D4.

### Step 4 — Compare complete mutation sets

For each exact callable:

```text
actual_mutation_keys == policy_mutation_keys
```

This is set equality, not containment and not overload union.

A candidate can contain multiple rows for one callable. That is expected. It cannot use one row to cover a different DAO operation or sibling callable.

### Step 5 — Implement shadow reporting

Read legacy evidence only after v2 evidence has been run. Keep the comparison report independent of enforcement exit status.

Every delta must be reviewed before GR-07. Do not “fix” a delta by adding a v1 fallback.

### Step 6 — Preserve candidate data

If v2 evidence exposes candidate-data defects, return work to GR-05. Do not hand-edit candidate rows in GR-06.

## Required test matrix

### Exact identity

1. exact matching member function;
2. nested owner;
3. same simple name across packages;
4. overloads by count/type/nullability/order;
5. extension versus member function;
6. top-level function;
7. constructor, initializer, getter, setter where parser supports them;
8. duplicate callable declaration ambiguity;
9. candidate path outside declared root;
10. source symlink/path-resolution rejection.

### Mutation closure

1. policy has one/multiple mutation rows in one callable;
2. actual source mutation missing from policy;
3. policy mutation absent from source;
4. wrong accessor;
5. wrong DAO FQCN;
6. wrong target overload;
7. complex/safe receiver becomes controlled diagnostic;
8. comments and strings do not count;
9. deterministic ordering across two runs.

### Barrier metadata

1. direct metadata with local direct syntax;
2. direct metadata without syntax fails;
3. helper metadata does not claim direct;
4. worker-mediated metadata does not claim direct;
5. tests explicitly prove no dominance/call-graph claim is made.

### Shadow mode

1. legacy overload union is reported as a difference, never accepted;
2. a clean v2 result remains clean even if legacy report-only data differs;
3. unreviewed comparison delta blocks the GR-07 readiness check;
4. shadow result cannot influence ratchet or active scanner behavior.

## Completion checks

```bash
python3 -m pytest \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_kotlin_callable_parser.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short
```

```bash
mkdir -p build/guard-debug/gr06

python3 scripts/ci/verify_db_policy_v2_evidence.py \
  --root . \
  --policy config/guards/db_ownership_policy.signatures.candidate.yml \
  --output build/guard-debug/gr06/v2-evidence.json
```

Expected: trusted result and exit `0` only if every candidate callable has exact closure.

Run optional legacy shadow mode and review every delta against the GR-05 accounting artifact.

Run normal DB CLI through GR-00 evidence capture. Expected: it remains on the legacy active-policy path and must not be silently altered by GR-06.

## Preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/src/main \
  app/build.gradle.kts \
  .github/workflows/ci.yml
```

Candidate changes are not expected. If they are needed, stop and reopen GR-05.

## Definition of done

GR-06 is complete only when:

- exact callable-v2 identity is the sole v2 evidence grouping key;
- overload union is impossible;
- every candidate callable has exact mutation-set closure;
- all shadow deltas are explicit and reviewed;
- active enforcement remains untouched;
- no barrier-proof claim exceeds local metadata consistency.

## Required completion report

```text
PR: GR-06
START SHA:
END SHA:
CANDIDATE HASH:
V2 CALLABLE GROUP COUNT:
V2 MUTATION KEY COUNT:
V2 EVIDENCE RESULT:
LEGACY/V2 SHADOW DELTAS BY CLASS:
UNREVIEWED DELTAS: 0
ACTIVE POLICY CHANGED: no
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
COMMANDS RUN:
RESULTS:
EXPECTED BLOCKED STATE:
NEXT PR: GR-07
```