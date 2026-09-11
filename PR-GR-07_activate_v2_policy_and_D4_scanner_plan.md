# PR-GR-07 — Activate v2 policy and authoritative D4 scanner

## Agent mission

Make policy v2 the only authorization authority for DB ownership findings.

After this PR:

- v2 policy is active;
- typed v2 source evidence runs before D4;
- D4 matches full callable and mutation identity;
- legacy policy is report-only and cannot authorize a mutation;
- the normal CLI produces either:
  - trusted `0` clean,
  - trusted `1` real findings,
  - untrusted `2` controlled diagnostics.

A trusted `1` is success for activation: it means real triage can begin. It is not a reason to generate a baseline.

## Preconditions / activation gate

Do not start unless all are true:

1. GR-05 candidate/accounting artifacts reproduce.
2. GR-06 v2 evidence exits `0`, is trusted, and has no unresolved comparison delta.
3. Every candidate mutation is source-proven.
4. Inventory-only is trusted.
5. Source-root verifier passes.
6. Structural exception policy/manifest validation passes.
7. Checkout is clean.
8. A GR-00 bundle exists for the actual start SHA.

Any failure means return to the owning earlier PR. Do not make GR-07 a repair bucket.

## Non-goals

Do not:

- triage real findings;
- update or generate a ratchet baseline;
- claim worker/helper barrier proof;
- alter structural exception semantics;
- change production Kotlin;
- use a v1 fallback if v2 matching finds an issue;
- make legacy shadow output a gate or authorization source.

## Promotion model

Keep the canonical active filename:

`config/guards/db_ownership_policy.yml`

Promote v2 into that path through a controlled promotion operation, not manual paste.

Required promotion steps:

1. verify candidate loader validity;
2. verify GR-06 evidence report/hash;
3. verify candidate/accounting crosswalk;
4. atomically write active v2 policy;
5. archive the exact old v1 policy as:

`config/guards/db_ownership_policy.legacy.yml`

6. preserve legacy file byte-for-byte;
7. record candidate SHA, active SHA, old-v1 SHA, and source-tree SHA in a promotion record.

The active file must have `schemaVersion: 2`. The candidate and active policy must be byte-identical at promotion time.

Legacy archive may be used only by an explicit report-only shadow command. No normal CLI, ratchet, Gradle task, or workflow may read it for authorization.

## Required scanner changes

The current D4 scanner has legacy-field matching and simple-owner comparison. Replace active authorization matching with typed `PolicyEntry` / `MutationKey` matching. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e7b7597098dc2e7ac0a35f8342b20f9f4a420f41/scripts/db_guard/scanner.py))

### Authoritative match contract

For every discovered direct DAO mutation, authorization requires exact equality on:

```text
path
ownerFqcn
kind
method
receiver
ordered parameterTypes
daoAccessor
daoFqcn
operation
```

Use `PolicyEntry.mutation_key()` or `match_mutation()` as the single matching primitive.

Forbidden:

- `owner_fqcn.rsplit(".", 1)[-1]`;
- legacy `class`, `daos`, or nested `signature`;
- name-only operation matching;
- union of entries from other overloads;
- fallback from v2 mismatch to v1 authorization;
- wildcard support.

### Pipeline contract

The normal CLI must execute in this order:

```text
source-root validation
→ Room inventory
→ active v2 loader
→ v2 exact source evidence
→ structural policy/manifest validation
→ D4 discovery and full-v2 authorization
→ protocol-v2 findings report
```

If any pre-scan stage is uncertain, return untrusted report + exit `2`; do not emit partial findings.

### Structured report requirements

Normal reports must include stable safe statistics such as:

- `trusted`;
- active policy schema version;
- policy mode: `authoritative-v2`;
- scanner mode/version;
- source-root manifest hash or safe identifier;
- finding count;
- diagnostic count.

Do not include local absolute paths, raw source, raw SQL, or exception messages.

### Legacy report-only mode

If retained, legacy analysis must require an explicit opt-in flag such as:

```text
--legacy-shadow-report <path>
```

Rules:

- it cannot affect normal CLI exit code;
- it cannot be passed to ratchet;
- it cannot influence Gradle success;
- output states `reportOnly: true`;
- it must disappear in GR-10 after migration confidence is established.

## Ratchet / Gradle / CI wiring

GR-07 changes the command authority, not the baseline.

1. Ratchet child command must invoke normal v2 CLI.
2. It must not consume legacy report-only output.
3. Existing legacy baseline incompatibility is expected to surface as controlled exit `2` until GR-09.
4. Update Gradle/workflow only where they explicitly hard-code legacy assumptions.
5. If default paths already use canonical active policy filename, avoid needless wiring diffs.
6. Validate static suite, ratchet, and Gradle invoke the same authoritative v2 CLI argv.

## Implementation sequence

### Step 1 — Add activation tests before promotion

Prove:

- a fully matching v2 mutation passes;
- wrong owner FQCN fails;
- nested owner collision fails;
- overload collision fails;
- wrong receiver/parameter/DAO FQCN/accessor/operation fails;
- legacy policy cannot authorize after activation;
- a missing/invalid v2 policy exits `2`;
- legacy shadow cannot change result.

### Step 2 — Make v2 loader/evidence the CLI preflight

Remove the current “all legacy entries must have `signature`” active condition. Do not replace it with tolerant legacy parsing.

### Step 3 — Convert D4 match path

Pass typed v2 entries into scanner. Remove active-path use of legacy dict matching. Keep structural exception logic unchanged unless necessary to preserve current exact tuple protection.

### Step 4 — Promote atomically

Use the promotion mechanism only after all readiness checks pass. Verify candidate and new active policy hashes/equality.

### Step 5 — Run the first real trusted scan

Run normal CLI with findings output. Interpret results strictly:

| Result | Meaning | Next action |
|---|---|---|
| `0`, trusted | no current findings | proceed to GR-09 only after adversarial checks |
| `1`, trusted | real DB findings | begin GR-08a |
| `2`, untrusted | infrastructure/evidence/analyzer defect | stop; repair owner PR |

Do not baseline any result in this PR.

## Required test matrix

### Active policy / promotion

1. candidate and active policy byte equality at promotion;
2. v2 loader rejects v1 active file;
3. v1 archive cannot be used accidentally;
4. promotion refuses failed evidence;
5. promotion refuses candidate/accounting mismatch;
6. promotion is atomic;
7. active-policy unknown field/duplicate mutation key fails closed.

### D4 exact authorization

1. full exact match passes;
2. simple-name owner collision fails;
3. nested FQCN collision fails;
4. overload, receiver, type order, nullability, kind differences fail;
5. DAO accessor/FQCN/operation mismatch fails;
6. one callable’s policy cannot cover another callable;
7. an observed mutation absent from policy becomes a finding;
8. unresolved target remains diagnostic, not a guessed finding.

### Control plane

1. normal CLI uses v2 only;
2. ratchet child command uses v2 only;
3. static suite uses v2 only;
4. Gradle task uses v2 only;
5. legacy shadow cannot alter CLI/ratchet/Gradle outcome;
6. invalid v2 baseline remains a controlled separate GR-09 blocker.

## Completion checks

```bash
python3 -m pytest \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_guard_registry.py

python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/gr07/inventory.json \
  --dump-room-mutators build/guard-debug/gr07/room-mutators.json
```

Expected: exit `0`, trusted, no diagnostics.

```bash
set +e
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr07/db-findings.json
rc=$?
set -e
test "$rc" -eq 0 -o "$rc" -eq 1
```

Then assert JSON `trusted == true` and diagnostics are empty. Exit `2` is a blocker.

Run GR-00 capture twice. Record normal CLI, ratchet, static-suite, Gradle DB-task, and task-graph results. Ratchet may still be blocked by the legacy baseline until GR-09; that must be the only expected transitional block.

## Preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/src/main
```

Expected policy changes:

- active ownership policy converted to v2;
- byte-preserved v1 archive;
- promotion record/documentation;
- candidate retained unchanged unless generated metadata contract requires otherwise.

## Definition of done

GR-07 is complete only when:

- v2 is the exclusive DB authorization authority;
- D4 uses full callable-v2 and mutation identity;
- v1 cannot authorize anything;
- normal CLI produces a trusted `0` or `1`, never a known legacy-policy block;
- real findings are ready for GR-08;
- baseline remains unchanged.

## Required completion report

```text
PR: GR-07
START SHA:
END SHA:
ACTIVE POLICY SCHEMA:
ACTIVE POLICY SHA:
CANDIDATE SHA:
CANDIDATE == ACTIVE: yes/no
LEGACY ARCHIVE SHA:
V2 SOURCE EVIDENCE RESULT:
NORMAL CLI EXIT / TRUST / FINDING COUNT:
RATCHET EXIT / EXPECTED STATUS:
STATIC SUITE EXIT / EXPECTED STATUS:
GRADLE DB TASK EXIT / EXPECTED STATUS:
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
NEXT PR: GR-08a or GR-09
```