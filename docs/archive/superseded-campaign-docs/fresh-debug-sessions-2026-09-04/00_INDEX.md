# Fresh Debug Sessions — 2026-09-04 — INDEX

Full re-validation of every pipeline and the universal-issues program against the current source.

- **Validated at:** commit `d1fa9c68`, branch `atomicity-pr21-enforcement-final`
- **Method:** static source validation only (no Gradle/compile/test — see `01_METHOD_AND_CLASSIFICATION.md`)
- **Inputs:** the 12 `PIPELINE_N_CONSOLIDATED_ISSUES.md` docs, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `UNIVERSAL_ISSUE_TRACKER.md`, `MASTER_ISSUE_TRACKER.md`, `ENGINE_ISSUES_MASTER_TRACKER.md`, `FIXED_CLAIMS_VALIDATION_AUDIT_v4.md`
- **Headline verdict:** ~226 of ~240 pipeline issues verified fixed (94%); 7 verified open; 12 verified partial; 1 confirmed regression (latent); ~40 new issues found this session; ~103 rows of tracker/doc drift in both directions.

## Documents in this folder

| File | Content |
|---|---|
| `00_INDEX.md` | This index |
| `01_METHOD_AND_CLASSIFICATION.md` | How validation was done; status vocabulary; universal-vs-pipeline classification rules; limitations |
| `02_UNIVERSAL_ISSUES_FRESH_VALIDATION.md` | U-PR1..U-PR8 + U-EXPORT/U-DEAD revalidated in code; new universal categories (U-PR9+ candidates) |
| `03_PIPELINE_01_FRESH_VALIDATION.md` | Pipeline 1 (notification capture/intake) |
| `04_PIPELINE_02_FRESH_VALIDATION.md` | Pipeline 2 |
| `05_PIPELINE_03_FRESH_VALIDATION.md` | Pipeline 3 (receipt link/matching) |
| `06_PIPELINE_04_FRESH_VALIDATION.md` | Pipeline 4 (recurring lifecycle) |
| `07_PIPELINE_05_FRESH_VALIDATION.md` | Pipeline 5 (analytics/aggregation) |
| `08_PIPELINE_06_FRESH_VALIDATION.md` | Pipeline 6 (time periods/budgets/forecast) |
| `09_PIPELINE_07_FRESH_VALIDATION.md` | Pipeline 7 (backup/restore integrity) |
| `10_PIPELINE_08_FRESH_VALIDATION.md` | Pipeline 8 (privacy/AI gating) |
| `11_PIPELINE_09_FRESH_VALIDATION.md` | Pipeline 9 (worker guard contract) |
| `12_PIPELINE_10_FRESH_VALIDATION.md` | Pipeline 10 (bank integration) |
| `13_PIPELINE_11_FRESH_VALIDATION.md` | Pipeline 11 (email receipt ingestion) |
| `14_PIPELINE_12_FRESH_VALIDATION.md` | Pipeline 12 (export/import/accounting) |
| `15_REGRESSION_SCAN_RECENT_REFACTORS.md` | Regression hunt over T1–T4C time migration and PR18–24/PII burn-down waves |
| `16_NEW_ISSUES_FOUND.md` | Consolidated register of every new issue/regression found this session |
| `17_MASTER_STATE_SUMMARY.md` | The exact current state of the application: rollup, priorities, what to fix next |

## Reading order

1. `17_MASTER_STATE_SUMMARY.md` — the answer to "where is the app really?"
2. `16_NEW_ISSUES_FOUND.md` — what this session found that was never tracked before
3. `02_UNIVERSAL_ISSUES_FRESH_VALIDATION.md` — universal program truth (several big drift corrections)
4. Per-pipeline docs as needed for file-level evidence

## Relationship to older docs

The older consolidated-issue docs and trackers are **superseded by this folder as the status of record** (they contain ~103 drift rows in both directions, including several false fix claims). They were NOT modified. Reconciling or archiving them is a separate follow-up task. Nothing in this folder changes code, policy, baselines, or guard state — it is documentation only and is safe to land alongside the guardrail program.
