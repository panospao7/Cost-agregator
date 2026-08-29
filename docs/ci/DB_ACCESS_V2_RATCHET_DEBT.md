# DB Access v2 Ratchet Debt Manifest (GR-09)

PR: GR-09 — Approve the v2 DB ratchet baseline.
Status: reviewed decision recorded; control-plane verification (CLI, ratchet,
static suite, Gradle DB task, GR-00 double capture) is orchestrator-run.

## Reviewed state: EMPTY (zero approved debt)

This manifest documents the reviewed debt state of the protocol-v2 DB access
ratchet (`guard_ratchet.py --finding-protocol 2`, guard `db_access`) at the
GR-09 freeze:

- **Current trusted finding count: 0** (down from 497 across the GR-08a..GR-08p2
  batches; every batch finding was dispositioned `FIXED_IN_GR08` in its tracked
  batch manifest under `docs/ci/db-findings/GR-08*.yml`).
- **`APPROVED_TEMPORARY_DEBT` entries: 0.**
- **Baseline entries: 0** — `config/baselines/db_access_v2.json` encodes the
  empty baseline state in the exact form the current v2 ratchet parser accepts
  (closed v2 envelope with `entries: []`). No historical debt was retained
  merely to keep the baseline non-empty.
- The frozen evidence report (`build/guard-debug/gr09/current-findings.json`,
  SHA-256 `3d452c7f2d7a993ebb187d39b8c319d559f42abe7e2685b1d16934c7a695c588`)
  is trusted with zero findings and 20 advisory-only diagnostics
  (`DB_SIGNATURE_UNRESOLVED` with the bounded
  `controlled_context["advisory"]` marker, GR-07 Option-B amendment). Advisory
  diagnostics are never baseline-able debt and never enter this manifest.

> This is temporary reviewed debt, not authorization and not an allowlist.
> In the current reviewed state the approved-debt set is empty: the baseline
> authorizes nothing, exempts nothing, and suppresses nothing. Any current or
> future `db_access` finding is a ratchet violation (exit 1) unless it is
> individually reviewed, owned, linked to work, and expiring in a future
> manifest revision.

## Evidence chain

| Field | Value |
|---|---|
| START SHA | `ce1918c9ee7b3c53f2842d62fa030db37fa7fdcd` |
| START tree | `9005e9584fd8dc46fa554dddcb00051592be1df6` |
| Evidence report | `build/guard-debug/gr09/current-findings.json` |
| Evidence report SHA-256 | `3d452c7f2d7a993ebb187d39b8c319d559f42abe7e2685b1d16934c7a695c588` |
| Findings in evidence report | 0 |
| Advisory diagnostics in evidence report | 20 (advisory-only; trusted) |
| Approval date | 2026-08-29 |
| Reviewer | @panospao7 |
| Machine-readable companion | `docs/ci/db-findings/GR-09-debt-manifest.json` |

## Baseline entries

The baseline contains zero entries, so the per-entry manifest table is
vacuous. For any future entry, every row below is mandatory (plan
"Reviewed debt manifest"):

```text
fingerprint
rule ID
callable/mutation identity
why it cannot be fixed in the current batch
owner
linked issue
approval date
expiry date (<= 60 calendar days after approval; no permanent exemption)
evidence report SHA
planned resolution PR/batch
reviewer
```

| fingerprint | rule ID | callable/mutation identity | why not fixed now | owner | linked issue | approval date | expiry date | evidence report SHA | planned resolution | reviewer |
|---|---|---|---|---|---|---|---|---|---|---|
| (none — zero approved debt) | — | — | — | — | — | — | — | — | N/A (none outstanding) | — |

## Disposition summary

| Disposition | Count |
|---|---|
| `FIXED_IN_GR08` | 497 (all GR-08a..GR-08p2 batch findings) |
| `APPROVED_TEMPORARY_DEBT` | 0 |
| `BLOCKER_RETURN_TO_GR08` | 0 |

Planned resolution: N/A — no approved debt is outstanding, so there is no
expiry to honor and no resolution PR to track. The ratchet enforces the empty
state: any new finding exits 1, any baseline/resolved delta exits 1, and
expired debt, missing review metadata, malformed baselines, and blocking
report diagnostics fail closed with exit 2.
