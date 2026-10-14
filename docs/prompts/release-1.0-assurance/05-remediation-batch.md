# Prompt: Release 1.0 Remediation Batch

Act as the implementation owner for one approved Release 1.0 finding cluster.
The orchestrator must provide the finding IDs, approved scope, write set, and
acceptance tests. Do not solve multiple unrelated findings in one batch.

## Before editing

- Read the relevant segment, inventory, legal path, engine map, source files,
  callers, tests, and the finding evidence.
- Confirm whether the fix changes Room schema, migration, backup, privacy,
  worker, money, or lifecycle scope. Stop for an unapproved scope change.
- Define the smallest behavior change and the regression test before coding.

## Rules

- Preserve coordinators, barriers, event ordering, cancellation identity,
  idempotency, privacy redaction, rounding, and fail-closed behavior.
- Do not delete tests, relax assertions, broaden allowlists/baselines, or use a
  destructive migration.
- Do not run Gradle, tests, guards, or direct validation from the coder profile.
- Do not claim that local review is independent acceptance.

## Output

List changed files, exact behavior fixed, tests added or adjusted, legal path
preserved, risks, and requested serialized validation profiles. The orchestrator
must obtain strict review before validation and must record the resulting
fingerprint.
