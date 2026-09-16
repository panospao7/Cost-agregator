# PR-GR-09 — Approve the v2 DB ratchet baseline

## Agent mission

Replace the obsolete legacy DB baseline with a narrow, reviewed v2 debt baseline.

This PR must not bulk-copy current findings into a baseline. Every retained finding must be individually reviewed, owned, linked to work, and expiring.

The current checked-in baseline still uses legacy `generated` plus raw `fingerprints` fields, so it must not be treated as v2 authority. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e7b7597098dc2e7ac0a35f8342b20f9f4a420f41/config/baselines/db_access.json))

## Preconditions / hard stops

Do not begin unless:

1. GR-07 active v2 CLI is trusted.
2. Every GR-08 batch is merged or the remaining finding set is explicitly approved debt.
3. Current full D4 report has no diagnostics.
4. Active v2 policy and v2 source evidence pass.
5. Every remaining finding appears in a reviewed debt manifest.
6. Checkout is clean.
7. Exact SHA, report SHA, active-policy SHA, source-root-manifest SHA, and current baseline SHA are recorded.

Stop if:

- current CLI exits `2`;
- a baseline entry cannot be tied to an actual current trusted finding;
- a finding has no owner, issue, rationale, or expiry;
- the tool attempts broad `--update-baseline` generation;
- expiry policy cannot be enforced by code/tests.

## Scope

### Allowed

- `config/baselines/db_access.json`;
- reviewed debt manifest/documentation;
- ratchet v2 baseline parser/writer validation;
- ratchet tests;
- narrowly related CI documentation.

### Forbidden

- active v2 ownership-policy changes;
- source scanner semantics;
- production Kotlin;
- structural policy changes;
- Gradle/workflow changes unless required to remove a verified baseline-format incompatibility;
- baseline generation from an unreviewed full report.

## Baseline design

First inspect the exact current `guard_ratchet.py` contract at the start SHA. Do not guess field names or invent a parallel schema.

The new baseline must contain, at minimum, whatever the active v2 ratchet parser requires for:

- guard name;
- v2 generation timestamp field;
- structured entries;
- stable fingerprint;
- owner;
- linked issue;
- rationale;
- expiration;
- reviewed source report / evidence reference.

Each entry must represent one current, trusted, unresolved finding only.

### Required debt rules

1. finite expiry is mandatory;
2. expiry must be no more than 60 calendar days after approval in this PR;
3. no permanent exemption;
4. no wildcard fingerprint;
5. no path/class-level suppression;
6. no baseline entry without a matching current finding;
7. no current finding omitted from the approved-debt decision;
8. resolved/stale baseline entries fail the ratchet;
9. expired entry fails closed with exit `2`;
10. baseline order is canonical and deterministic.

If no finding remains, encode empty baseline state only in the exact form supported by current ratchet tests. Do not retain historical debt merely to keep an old file non-empty.

## Reviewed debt manifest

Add a tracked manifest, for example:

`docs/ci/DB_ACCESS_V2_RATCHET_DEBT.md`

or a machine-readable companion file.

For every baseline entry include:

```text
fingerprint
rule ID
callable/mutation identity
why it cannot be fixed in GR-08
owner
linked issue
approval date
expiry date
evidence report SHA
planned resolution PR/batch
reviewer
```

The manifest must state clearly:

> This is temporary reviewed debt, not authorization and not an allowlist.

## Required tooling behavior

Use the existing triage/baseline-candidate tooling where possible. Strengthen it rather than adding another baseline writer.

A baseline writer must require:

```text
trusted findings report
explicit reviewed-debt manifest
explicit generated-at value
explicit output path
```

It must reject:

- report diagnostics;
- untrusted report;
- mismatched report hash;
- unapproved finding;
- manifest entry missing owner/issue/rationale/expiry;
- expiry out of allowed range;
- duplicate fingerprint;
- stale manifest fingerprint;
- baseline/output collision;
- bulk mode;
- active-policy overwrite.

`guard_ratchet.py --update-baseline` must not be used as a shortcut. If retained for other guards, DB v2 use must be blocked unless the reviewed-debt manifest is supplied and validated.

## Required ratchet semantics

Given a trusted child report:

| State | Ratchet result |
|---|---|
| current fingerprints exactly equal valid unexpired baseline | `0` |
| new current finding not in baseline | `1` |
| baseline entry no longer present | `1` |
| malformed/missing/untrusted child report | `2` |
| malformed baseline | `2` |
| expired entry | `2` |
| missing required review metadata | `2` |

No diagnostic, expiration, or format failure may degrade into a normal violation result.

## Implementation sequence

### Step 1 — Freeze final finding inventory

Run trusted v2 D4 scan and create:

```text
current-findings.json
current-findings.sha256
```

Do not edit baseline yet.

### Step 2 — Review every remaining finding

For every fingerprint, choose exactly one:

```text
FIXED_IN_GR08
APPROVED_TEMPORARY_DEBT
BLOCKER_RETURN_TO_GR08
```

Only `APPROVED_TEMPORARY_DEBT` can enter baseline.

### Step 3 — Build a selected baseline candidate

Generate from the reviewed manifest, never directly from all findings.

Confirm:

```text
baseline fingerprints == approved-debt fingerprints
approved-debt fingerprints == current unresolved fingerprints
```

Any non-equality is a hard stop.

### Step 4 — Add ratchet tests before writing tracked baseline

Add fake-command tests for:

1. exact match;
2. one new finding;
3. one resolved baseline finding;
4. malformed report;
5. untrusted report;
6. missing report;
7. malformed baseline;
8. missing metadata;
9. duplicate fingerprint;
10. expired entry;
11. manifest/report hash mismatch;
12. attempted bulk generation rejection;
13. deterministic candidate output.

### Step 5 — Write the approved baseline atomically

Use explicit `generated-at` from review evidence. Do not use a hidden current-time default.

### Step 6 — Verify all control planes

Run CLI, ratchet, static suite, Gradle DB task, and GR-00 twice. All must consume the same v2 child command and baseline.

## Completion checks

```bash
python3 -m pytest \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  scripts/ci/test_guard_findings.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  scripts/test_db_guard_scanner_d4.py \
  -v --tb=short
```

```bash
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr09/current-findings.json
```

Require trusted report and no diagnostics.

Run the exact ratchet command used by static suite/Gradle with tokenized child argv. Expected exit `0` only when the approved baseline exactly matches current reviewed debt.

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/gr09/static-suite
```

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

Run Gradle only sequentially and only when no other agent owns Gradle.

Finally run GR-00 capture twice and verify semantic summaries match.

## Mandatory adversarial checks

Using temporary fixtures/reports only:

1. inject one new v2 finding → ratchet exits `1`;
2. remove one baseline finding from child report → ratchet exits `1`;
3. alter a fingerprint format → ratchet exits `1` or `2`, never `0`;
4. mark baseline debt expired → exit `2`;
5. remove owner/issue/expiry → exit `2`;
6. make child report untrusted → exit `2`;
7. attempt unreviewed baseline generation → refusal before write;
8. verify no production source or policy change can be hidden by an unchanged baseline entry.

## Preservation checks

```bash
git diff --exit-code -- \
  config/guards/db_ownership_policy.yml \
  config/guards/db_ownership_policy.legacy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/src/main
```

Expected production/config change is only v2 ratchet baseline, debt-review artifact, and narrowly necessary ratchet tests/docs.

## Definition of done

GR-09 is complete only when:

- baseline contains only individually reviewed current debt;
- every entry has owner, issue, rationale, evidence, and finite expiry;
- baseline cannot be broadly regenerated;
- v2 ratchet detects new and resolved findings;
- invalid/untrusted/expired states fail closed;
- normal CLI, ratchet, static suite, and Gradle DB task consume v2 authority;
- two GR-00 evidence runs reproduce semantic results.

## Required completion report

```text
PR: GR-09
START SHA:
END SHA:
CURRENT TRUSTED FINDING COUNT:
APPROVED DEBT COUNT:
BASELINE ENTRY COUNT:
EXPIRY RANGE:
BASELINE SHA:
ACTIVE POLICY SHA:
RATCHET RESULT:
STATIC SUITE DB RESULT:
GRADLE DB RESULT:
ADVERSARIAL CHECKS:
PRODUCTION KOTLIN CHANGED: no
ACTIVE POLICY CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR: GR-10
```