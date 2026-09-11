# Test Suite Audit 2026-09

**Status:** review complete (static). Execution of the consolidation plan has **not** started — see [03-CONSOLIDATION-PLAN.md](03-CONSOLIDATION-PLAN.md) decision points.
**Date:** 2026-09-07 · **Scope:** every `.kt` file under `app/src/test` + `app/src/androidTest` — 675 files, ~180k LOC. Guardrail tests (`architecture/`, 14 files) inventoried light-touch only, per scope decision. `GR00-worktree/` (duplicate tree) excluded.

## Why this audit exists

The suite grew organically across development stages: sophisticated golden/anchor tests sit next to scattered, overlapping, incomplete, or mirrored tests. Refactors break ~100 tests at a time; compile time and complexity suffer; nobody knows which tests are load-bearing. This audit answers, per file: **what does it test, how well, is it duplicated, and what should happen to it** — and proposes a safe consolidation order.

## Documents

| File | Contents |
|---|---|
| [01-INVENTORY.md](01-INVENTORY.md) | Master per-file table — all 675 files with verdict, priority, style, overlap, notes (generated from batches; batches are authoritative) |
| [02-FINDINGS.md](02-FINDINGS.md) | Cross-batch synthesis: anchors to protect, predicted-failing/stale tests, 25 negative-value files, 9 duplication clusters, 35 wrong-object tests, 47 FRAGILE hotspots, systemic gaps, ledger F-01…F-21 status, prior-audit reconciliation |
| [03-CONSOLIDATION-PLAN.md](03-CONSOLIDATION-PLAN.md) | Phased execution plan (0–7) with entry criteria, gates, and the 6 decisions that need a human |
| [04-CLASS-COVERAGE-MATRIX.md](04-CLASS-COVERAGE-MATRIX.md) | Generated mechanical cross-reference: 570 production classes referenced by ≥3 test files; 350 zero-reference declarations (gap candidates) |
| [batches/](batches/) | 26 per-area review files — the authoritative evidence, per-file rows with citations |
| [batch-plans/](batch-plans/) | The exact file lists each batch reviewed (reproducibility) |
| [REVIEW_INSTRUCTIONS.md](REVIEW_INSTRUCTIONS.md) | The method/criteria every reviewer applied (reuse for re-audits) |

## Method

Static review only (no Gradle runs — per repo Gradle-coordination rules). Each of 26 batches read every assigned file in full, classified style, asserted-value, overlaps (grep-verified), compile fragility, and ledger-family membership, then assigned a verdict:

**KEEP · STRENGTHEN · MERGE (survivor named) · REWRITE · DELETE · NIGHTLY · UNKNOWN** — priority P0 (protects money/lifecycle/privacy/backup/worker invariants) … P4 (negative value).

Prior stale audit (`docs/testing/generated/`, 2026-05, 489 files) was used as prior evidence and **re-verified file-by-file** — ~20 verdicts were overturned; the suite has grown 489 → 675 since.

## Headline numbers

| Verdict | Files | | Priority | Files |
|---|---|---|---|---|
| KEEP | 436 | | P0 | 129 |
| STRENGTHEN | 102 | | P1 | 261 |
| MERGE | 61 | | P2 | 181 |
| REWRITE | 35 | | P3 | 74 |
| DELETE | 25 | | P4 | 16 |
| NIGHTLY | 15 | | | |
| UNKNOWN | 1 | | FRAGILE flagged | 47 |

Key facts: 9 duplication clusters (one barrier invariant has 3 copies across golden/DB-test/guard-script layers) · 35 tests run test-local mirrors of production logic (regressions cannot fail them) · ~a third of the suite (golden/scenarios/e2e/ui) has **never been measured** in the failure ledger · the connected androidTest suite is `continue-on-error` and effectively manual · 15+ tests are statically predicted to fail their next run (one guard allowlist already expired 2026-08-15).

## How to act on this

1. Read [03-CONSOLIDATION-PLAN.md](03-CONSOLIDATION-PLAN.md) — start with Phase 0 (measured baseline) and the 6 decision points.
2. For any specific file, look it up in [01-INVENTORY.md](01-INVENTORY.md), then follow the batch link for evidence.
3. Do not delete/merge anything whose batch row names unique content to port first.
