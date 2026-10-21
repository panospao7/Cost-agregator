# Release 1.0.2 — acceptance record (small follow-up to 1.0.1)

```text
Candidate:  27db3a03 on branch release-1.0.2 (docs commit with this record follows it)
Base:       2ff78968 (bug-fixes = Release 1.0.1, already pushed)
Version:    1.0.2 (code 3)
Scope:      TB-2 renewal and PRIV-03 flag removal, both owner-approved on 2026-10-21.
Status:     CONDITIONAL — same owner-only conditions as 1.0.1 (production signing, manual smoke).
```

## Changes

- `config/validation/known-hanging-tests.json`: HANG-001..003 `review_by` 2026-12-01 -> 2027-03-01
  (owner-approved renewal; legacy-tests clean).
- `RawPersistencePolicy`: `allowParsedMerchant`, `allowParsedItems`, `allowExternalIdHash` and
  `allowDebugBody` removed. Nothing in production read or stored them; production consumes only
  `mode`. Tests that asserted only those flags were removed or trimmed; docs corrected.
- Version 1.0.1 (2) -> 1.0.2 (3).
- No schema, guard, baseline or allowlist change other than the HANG review dates.

## Evidence

| Profile | Run | Revision | Result |
| --- | --- | --- | --- |
| targeted (7 privacy classes) | vr-20261021-132850-f095f4a8 | a5b67d19 | PASS, 60/0 |
| static-guards | vr-20261021-134009-deb62dd2 | a5b67d19 | PASS, 25/25 |
| legacy-tests | vr-20261021-135531-9b4d5a2b | a5b67d19 | PASS, 238/0/1 |
| app-check | vr-20261021-135648-bdfc6de5 | a5b67d19 | PASS, 14,967 passed / 0 failed / 3 skipped |
| assemble-release | vr-20261021-142702-c52c2599 | 27db3a03 | PASS |
| release-artifact-evidence | vr-20261021-143214-94b137df | 27db3a03 | PASS; APK SHA-256 `947dd71b…12b3`; 1.0.2 (3); not debuggable; debug certificate |
| connected-tests (emulator-5554, wave5-api35) | vr-20261021-143253-a5041781 | 27db3a03 | PASS, 159/0/0 |

Disclosure: 27db3a03 differs from a5b67d19 by the version bump only; docs-only commits follow.
The owner's phone was not used by any run.

## Review

privacy-security-guardian: APPROVE-WITH-FIXES on a5b67d19 (flags verified unused on the parent
tree; no production-enforced behaviour lost test coverage; the doc corrections were applied in
2ae5a8ab). Follow-ups recorded in the 1.0.1 backlog (wrong KDoc at `RawPersistencePolicy.kt:13`;
optional corrupted-settings dedupe-hash test).

## Owner conditions (unchanged)

Production signing (debug certificate); manual smoke on the release APK; guard review session
before 2026-12-31 (TB-3, includes F-R28-a/c); PRIV-12 retention as a feature slice.
