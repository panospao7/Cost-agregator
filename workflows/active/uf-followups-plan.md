# Plan: uf-followups (post-program follow-ups)

The owner approved this on 2026-11-02 ("I approve follow your recommendations", under item 101).

- **Base:** origin/feature/ai-evolution `3bc48bb9`.
- **Worktree:** `Cost-agregator-qf`.
- **Branch:** `feature/uf-followups`.
- **Tier:** T1/T2. There are no schema, worker, permission or guard changes.

## Slices
| ID | Item | Write set | Tier |
|---|---|---|---|
| F1 | Privacy: bounded restore logging | `ui/screens/backup/BackupRestoreViewModel.kt`: (a) `Timber.d("Restore completed: %s", importResult)` logs the whole result object, so log only the result type plus counts; (b) `Timber.w(e, "Failed to query backup URI size")` logs a stack trace, so log only the class simple name. Tests by test-author. | T2 (privacy) |
| F2 | WE review minor 3: the rule that drops mismatched edit results is untested | Move the predicate into pure `WarrantyEditFormState.kt` (e.g. `shouldApplyEditResult(resultId, openId, awaitingId)`) and call it from `WarrantyTrackerScreen.kt`. Behaviour is identical. Unit test in `WarrantyEditFormStateTest`. | T1 |
| F3 | Missing el/es copy for warranty strings already in use | `res/values-el`, `res/values-es` `strings.xml`: add DRAFT translations for the EN warranty keys that the warranty screen, dialog and cards already reference, plus `add_expense_date_selector_cd_format`. No EN changes, no key removals, no baseline edits. Sign-off is at the release gate (item 103). | T1 |
| F4 | LEGAL_PATHS wording for EXPIRED → ACTIVE | `docs/architecture/LEGAL_PATHS.md` (~:1703): the status is recomputed from the end date against now whenever the edit changes the end date or leaves review (INV-9). It is not only the case where "the user moved the end date". | docs |

## Kept / deferred (recorded, not done here)
- **Kept as-is:** after an NL result tap, back returns to the search results, as the QF-2d plan prescribes. UF-21 stays parked until negotiation is un-gated. A `PENDING_REVIEW` row with `needsReview=false` has no Edit entry; that state is defensively unreachable.
- **Deferred, needs its own plan:**
  - The UF-56 raw-amount threshold vs currency (T3 money sweep).
  - G2-N1 Keystore (security design).
- **Deferred by policy:**
  - The `db_ownership_policy.yml` `reason` prose waits until after the guard freeze.
  - Stale lint-baseline entries: the baseline is never hand-edited; refresh it only at an owner-run baseline refresh.
  - The runner's `Test-ExternalGradleClient` stays as-is. It is a safety interlock, and the only cost so far was one 15-minute stall.
  - The RSV notice consume-before-snackbar nit is accepted: the notice is informational, and losing it once on process death is acceptable.

## Validation (one batch)
1. targeted `*BackupRestoreViewModel*,*WarrantyEditFormState*,*WarrantyTrackerViewModel*`
2. lint (F3 strings; MissingTranslation may only go down)
3. static-guards (privacy logging guards, LEGAL_PATHS)
4. Then the combined guardian, then a cheap review, then FF to feature/ai-evolution.
