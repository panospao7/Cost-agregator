# Decision sheet: restore-semantic-verify, human gate after the impact map (2026-10-26)

**Sources:** `domain-spec.md` (O-1 to O-6) and `impact-map.md` (Q1 to Q9).

**Tier: T3.** There is **no Room or schema change**: the manifest gets one optional field, and the format and header versions stay the same. **There is no live security issue.** The manifest-SQL path is unreachable in production.

Reply with "accept recommendations" or override items by ID. **O-2 needs your explicit yes**, because it replaces existing tests. The el/es copy needs your sign-off before merge.

## Decisions
| ID | Question | Recommendation | Why |
|---|---|---|---|
| O-1 | What the user sees when a backup can't be verified | A non-blocking info notice on restore success | Old backups must keep restoring. Silence would hide the gap. |
| **O-2** | Replace `BackupVerifierManifestTest.kt:144-204` | **Replace, don't delete.** New tests cover AE-1, 2, 4 and 14 on the real schema. Lines 26-142 stay. | The current tests pin the broken behaviour: a fake `effectiveAmount` column, `'EXPENSE'`/`'INCOME'`, and raw `expected=999.0` text. **Needs your explicit approval.** |
| O-3 | Which totals go in the manifest | **Four:** raw amount by stored type and currency, spending by currency, income by currency, and recurring rules by active flag and currency. Leave out the base-snapshot total (see Q1). Savings ledger, receipts and loans come later. | Keeps the set small, and every total reuses an existing `ExpenseDao` constant unchanged. |
| O-4 | Allow "restore anyway" when totals don't match | **No.** A mismatch blocks the restore, fail-closed. | A mismatch means the backup content is corrupted or wrong. |
| O-5 | Order against loan-installments | **Reversed from the spec: loan goes first.** MEC and loan merge into integration first, and this feature is cut from the merged tip. If loan S6c's reconcile isn't merged yet, whichever lands second puts reconcile **before** capture. This feature owns the INV-9 declaration for the loan's v151→152 migration. | Loan S2b through S6b are already committed. `BackupVerifier.kt` already differs by about 40 lines, and that merge is next in line. |
| O-6 | Add the new step to `LEGAL_PATHS.md` (spec §8) | **Yes.** It goes in the docs slice, under the doc lock. | — |
| Q1 | Total 4 has no reusable `ExpenseDao` constant | **(c) Defer it.** Extracting a constant would edit money SQL that MC touched, which is a stop-gate. A local copy would duplicate a rule. | Avoids both stop-gates. Can come back as v2. |
| Q2 | How many new strings | **Two**: the "not verifiable" notice and "restore blocked: backup content didn't match". A backup-capture failure needs no string once Q6 is decided. All three languages (EN/EL/ES); the implementer drafts and you sign off. | Lint requires all three languages. |
| Q3 | Existing verifier messages (table names, counts, integrity text) end up in exception text | **Use only bounded codes in the exceptions** at `DatabaseBackupRepositoryImpl:788` and `:1354`. Internal issue lists stay as they are. | This is the only way those messages escape. Closing it fixes the existing leak along with the new one, in the same lines. |
| Q4 | Journal reason strings for the C1/C2/C3 checks | **Reuse** `STAGED_VERIFICATION_FAILED` and `POST_MIGRATION_VERIFICATION_FAILED`, with the C3 check going through the existing rollback reason. The semantic detail goes in bounded event metadata. | No registry growth, and no journal-state change (stop-gate 4). |
| Q5 | Speed and size limits on large databases | Cap at 256 buckets; above that, mark the totals NOT_CAPTURED and record a bounded diagnostic. No timeout in v1. Add a synthetic large-database test that measures the time. | Simple and observable. A timeout can come later if the measurements call for one. |
| Q6 | If capturing totals fails, should the whole backup fail? | **No. The backup still completes**, marked NOT_CAPTURED with a bounded diagnostic, and a later restore of it reports "not verifiable". This revises spec INV-5. The C0 self-check works the same way. | A verifier bug must never stop someone from backing up. A backup without totals is no worse than any backup made today. |
| Q7 | Merge order | See O-5. | — |
| Q8 | Overlap with uf-quick-fixes | **Closed.** The orchestrator checked: the QF plan touches no backup files. | — |
| Q9 | Show "not verifiable" in `DebugViewModel.importDatabase` too? | **No.** Restore screen and diagnostics code only. | The debug import is a separate path, and this keeps the change smaller. |

## Consequences if accepted
- The **domain-modeler revises the spec**:
  - INV-5 changes to the degrade rule from Q6.
  - The total set drops to four (O-3/Q1).
  - Two strings (Q2).
  - Bounded exception text (Q3).
  - Journal reasons are reused (Q4).
  - Merge order changes (O-5).
- After that, the **feature-planner** writes `workflows/active/restore-semantic-verify-plan.md`. It starts from the impact map's slices S1 to S6, without S0.
- **Implementation waits** for the MEC+loan merge into integration, and for you to lift "planning only".

## Owner answer (2026-10-26)
Owner: "I approve". **All recommendations are accepted, including an explicit yes on O-2** (replace
`BackupVerifierManifestTest.kt:144-204` rather than delete it).
The el/es copy still needs owner sign-off before merge. Implementation stays on hold: the owner chose "planning only",
and it also waits for the MEC+loan merge.
