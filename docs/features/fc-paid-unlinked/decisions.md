# FC-PAID-UNLINKED: decisions (orchestrator, owner item 143, 2026-11-06)

Every open decision in `domain-spec.md` takes the modeler's recommended default:

| ID | Topic | Decision |
|---|---|---|
| D-1 | Where suggestions appear | The Recurring bill row, plus one Home hint. New strings in en, with el and es marked DRAFT for later sign-off. |
| D-2 | Widen the auto-link window | No. |
| D-3 | Nearest same-merchant bill already PAID or SKIPPED | Refuse the auto-link (tightens DR-11). |
| D-4 | Editing a linked expense | Keep the link within S(P) as the spec defines it: weekly 3 days, biweekly 6, monthly 7. Corrected after the impact map. |
| D-5 | Backfill | None. Bills due in the last 31 days appear as suggestions. |
| D-6 | "Not this bill" on an already-linked bill | Not in v1. |
| D-7 | Cross-currency matching | No. |
| D-8 | Duplicate amount check in `OccurrenceConflictResolver` | Delegate it to the new `RecurringPaymentLinkMatcher` (one owner of the amount rule). Do not drop the check. Corrected after the impact map. |
| D-9 | Undo a decline | Not offered. |
| D-10 | Radar redo (P-05-5) | Resumes after slice S2 merges. |
| D-11 | Windows | 7 days for suggestions, 31-day lookback. |

Constraints: no schema change and no new permissions. Cloud use is unchanged.

Authority: owner item 143 (`build/release-lead-notes/owner-approvals-20261022.md`). It covers the D-3 tightening of DR-11 and the new strings, with el and es kept DRAFT. Reads are not policy rows; adding a policy row or a db_access classification entry is avoided by design.

D-3 clarified (S1 guardian, 2026-11-06): CANCELLED and IGNORED occurrences are not candidates and never block. Only PAID, SKIPPED and MISSED as the nearest owner block an auto-link. An inactive rule as the nearest owner blocks only the suggestion tier, not the auto-link, per the INV-6 amendment below.

INV-6 amended (S1 validation, 2026-11-06): an inactive rule does not block an auto-link, but it still yields no suggestion (the merged R21 pin autoLinkDoesNotAdvanceAnInactiveRule stands: link, PAID, no advance).
