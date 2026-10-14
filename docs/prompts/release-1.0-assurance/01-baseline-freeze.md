# Prompt: Freeze the Release 1.0 Baseline

Act as the Release 1.0 baseline owner. This is a read-only gate and evidence
reconciliation task. Do not modify production code, tests, guards, baselines,
or schemas.

## Scope limit

This prompt inspects evidence only. A missing gate is a request for an approved
closeout batch/validation queue, not authorization to execute it. Do not commit,
stage, reset, clear app data, change schemas, or alter existing user work.

## Objective

Prove that Wave 5 is finished enough to become the candidate under review. If
not, return `BASELINE: BLOCKED` with the exact missing gates.

## Inspect

- `git status`, candidate commit, parent commit, and final diff;
- latest Wave 5 handoff and remaining implementation plan;
- Wave 4 deferred-gate handoff;
- architecture signposts and current schema/inventory facts;
- validation-runner `result.json`, logs, completion markers, and fingerprints;
- independent reviewer/guardian verdicts;
- connected migration, fresh-install, backup/restore, and PDF/device evidence.

## Required checks

- No dirty source or test changes are silently included.
- DB version, migrations, schema snapshots, DAO ownership, and backup policy
  agree with the candidate.
- Current evidence, not historical evidence, covers compile, focused tests,
  migration, static guards, required shards, app-check, and connected gates.
- Cancellation, privacy, Room, money, worker, and lifecycle exceptions have an
  explicit human disposition where required.
- Architecture and inventory docs describe the actual candidate.
- Original campaign source pin and Wave 4 predecessor are verified ancestors;
  document both comparisons rather than silently auditing only Wave 5.
- Shipped 1.0 scope and excluded features are human-confirmed, not inferred from
  menu visibility or old release notes. Record current source and runtime facts.

## Output

Return:

```text
BASELINE: READY | BLOCKED
Candidate: <commit and fingerprint>
Required evidence: <PASS/PENDING/FAIL table>
Deferred risks: <explicit list>
Dirty or ambiguous paths: <list>
Next action: <one action>
```

Return the report; the orchestrator writes `release-1.0-baseline.md` after
confirming the facts. A read-only auditor never gains artifact write access.
