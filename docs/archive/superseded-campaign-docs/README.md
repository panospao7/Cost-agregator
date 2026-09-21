# Archive — superseded campaign docs

Archived 2026-09-19 during docs cleanup. Nothing here was deleted; moved out of the
active tree so ongoing work stays uncluttered. **Do not use these as truth** — see
"Current truth" below. Archived 2026-09-19; contents predate or were superseded by
the 2026-09-06/07 revalidation chain.

## Current truth (NOT in this archive — live locations)

| What | Where |
|---|---|
| Authoritative open-issue list | `docs/analyses and debug master/REVALIDATED_ISSUE_REGISTRY_2026-09-07.md` |
| Active remediation plans (RP-00…RP-21) + wave status | `docs/analyses and debug master/remediation/` |
| Evidence chain for the registry | `CROSS_VALIDATION_REPORT_2026-09-06.md`, `FRESH_AUDIT_FINDINGS_2026-09-06.md`, `STILL_OPEN_ISSUES_2026-09-06.md` (same folder) |
| Current audit-campaign prompt | `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md` |
| Architecture reference | `docs/architecture/` (live, never archived) |

## What's in here and why it's superseded

### `trackers/` — the five old issue registries + fixed-claims audit
- `PIPELINE_ISSUES_MASTER_TRACKER.md` — declared "most contradictory" by the 2026-09-06
  cross-validation; header vs tables disagree; superseded by the revalidated registry.
- `ENGINE_ISSUES_MASTER_TRACKER.md` — most accurate of the old five, still superseded.
- `MASTER_ISSUE_TRACKER.md` + `MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md` — MIT namespace,
  ~2 months stale at cross-validation time.
- `UNIVERSAL_ISSUE_TRACKER.md` — all U-PR1…U-PR8 verified landed (cross-validation §2.2).
- `FIXED_CLAIMS_VALIDATION_AUDIT_v4.md` — May-era audit that exposed the false-FIXED
  epidemic ("fix in the dead path" etc.). Historical lesson doc; its findings are
  adjudicated in the revalidated registry.

**ID namespaces live on as cross-references** (`P*-P0/1-*`, `NEW-P*-*`, `MIT-*`,
`E*/W-A-C-G-I-T-M-*`, `U-*`, `REVAL-*`, `O-*`) — the revalidated registry and the
remediation README cite them; resolve details here if needed.

### `engine-campaigns/` — engine 1…5 folders (May–June 2026)
Completed vertical campaigns (warranty/subscription, analytics, categorization,
groups/investment/tax, money/time primitives). Audits, implementation plans, PR
completion reports, final gate reviews. Their surviving issues were folded into the
revalidated registry and remediation packages.

### `pipeline-prompts/` — the 12 pipeline prompt packs + master planner prompt
Superseded by `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md`.
Kept as templates: they contain the per-pipeline file inventories and legal-path
checklists that Phase 0 of the v3 campaign may reuse.

### `fresh-debug-sessions-2026-09-04/` — raw per-pipeline fresh-validation reports
The 18-file session behind `FRESH_AUDIT_FINDINGS_2026-09-06.md`. Every finding in
them was adjudicated (112/124 confirmed, 3 refuted, 9 corrected) into the
revalidated registry — read the registry, not these.

## Also deleted from disk earlier (uncommitted, recoverable via git)

The working tree already carried deletions before this archive existed:
`PIPELINE_1..12_CONSOLIDATED_ISSUES.md`, `new debugging reports/` (P13–P18 reviews),
`old issues ( dont read)/`, `pipelines issues implementantion plan/`,
`universal issues implementation plan/`. All remain in git history.
