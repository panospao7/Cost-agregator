# Decision sheet: warranty-edit (UF-02), human gate after the impact map (2026-10-26)

**Sources:**
- `domain-spec.md` (O-1..O-11)
- `ux-flows.md` (UX-1..7)
- `impact-map.md` (§8, plus the orchestrator verification)

**Tier: T3.**
- No Room/schema change.
- **No guard edit:** the design reuses the existing `updateWarranty(Warranty)` policy rows. The promotion-bypass guard exempts the repository file (checked by the orchestrator).
- **No permissions or network.**
- **Implementation needs QF-2d first**, because both change the warranty screen file. The QF plan is still parked.

**How to reply:** "accept recommendations", or override items by ID.
- **T-1 needs your explicit yes**, because it inverts an existing test.
- **O-3 and O-5 change shipped behaviour**, so check that you agree with them.
- The el/es copy needs your sign-off before merge.

## Decisions
| ID | Question | Recommendation | Why |
|---|---|---|---|
| O-1 | Editable fields | The same six fields for every origin: product, merchant, purchase date, duration in months, warranty type, support phone. Provenance (origin, confidence, receipt link, created/claimed dates) stays read-only. Support email and notes are deferred (O-1b). | This is the WRN-19 list. The card never shows email or notes. |
| O-2 | Editing a warranty that is in review | Save = **"Save and confirm"**. The edit leaves review in the same transaction, using the existing `confirmWarranty`. A row that isn't in review never re-enters it. | A row can never be "edited but unconfirmed", so receipt re-processing can't overwrite your edits. |
| **O-3** | Date input; add-flow change | A Material3 date picker plus duration in months (1..120). The end date is calculated by **one** extracted function, an exact copy of today's add-flow arithmetic. There is no free-form end-date field. **The add and edit flows share one form, so the shipped "Add warranty" dialog gains the picker and type field.** This is scope growth. | One form, one behaviour. Today the add dialog takes a typed `yyyy-MM-dd`. |
| O-4 | Type dropdown | Yes: Manufacturer, Extended, Store, Third party. The add flow defaults to Manufacturer. | Today, manual rows are always MANUFACTURER. |
| **O-5** | Status after an edit | If an edit changes the end date (or leaves review): EXPIRED when the new end is in the past, otherwise ACTIVE. **This lets an expired warranty become active again, but only when you move its end date into the future.** Claimed and transferred rows are never touched. The "never revived" wording in LEGAL_PATHS is reworded. | Fixes a mistyped date after the sweep has already expired the row. Otherwise the row stays stuck as expired, with no reminders. |
| O-6 | Today's `updateWarranty` isn't transactional and swallows event-write failures | **Fix it in scope:** one transaction, fail-closed, same signature, barrier first. It has no production callers today. | Matches the other warranty writes (R29), and it's the building block for edit. |
| O-7 | Return-window sync | None. It updates itself on the next receipt upsert. | — |
| O-8 | Concurrency | Last writer wins on the six content fields, applied over a fresh read inside the transaction. Status and review flags always come from the fresh row. | Same contract as `promoteReviewDraft`. An `updatedAt` check would raise false conflicts with the sweep. |
| O-9 | Which cards show Edit | ACTIVE, PENDING_REVIEW, EXPIRED. Not CLAIMED or TRANSFERRED; the repository returns NotEditable for those. | — |
| O-10 | The end-date arithmetic is duplicated 3× (VM, use case, extractor) | Extract the VM copy and add a parity test against the other two. **Don't edit the receipt-path files here.** Merging the three copies is a follow-up. | Keeps the receipt path (T3) out of this feature. |
| O-11 | Limits | Names 1..120 characters, phone ≤ 40 (blank means none), purchase date from 1970 to today + 365 days, duration 1..120 months. | Tunable constants, all in the validator. |
| UX-1 | Form container | A scrollable AlertDialog. | Smaller diff. |
| UX-2 | Errors while the form is open | An in-dialog banner. Use a snackbar only after the dialog closes. | A snackbar would sit under the dialog scrim. |
| UX-3 | Success feedback | A snackbar ("Warranty saved" / "… saved and confirmed"). | A confirmed row can disappear from a "needs review" filter. |
| UX-4 | Older manual rows linked to a placeholder receipt show "Linked to a receipt" | Accept for v1. | Rare and harmless. |
| UX-5 | Preview wording | Reuse "Expires / Expired on". | Fewer strings. |
| UX-6 | Claim/Delete ignore the "in progress" state today | Disable them while a mutation runs. Adding product names to the Confirm/Reject descriptions is a follow-up. | A small fix on the same row. |
| UX-7 | Add-flow scope growth | Same as O-3. | — |
| S-1 | Strings | **26 new keys × EN/EL/ES.** The implementer drafts EL/ES and you sign off. | Lint fails on English-only strings, and the baseline must never grow. |
| **T-1** | Test change | **Invert** `WarrantyTrackerRepositoryTest.kt:820-837` from "an event-write failure is swallowed" to "it propagates and rolls back", using the sibling `confirmWarranty` pattern (`:750-818`). It is not deleted. The similar `deleteWarranty` swallow test is left alone. **Needs your explicit yes.** | The test pins the fail-open behaviour that O-6 removes. |
| F-1 | Follow-ups recorded, not done here | Reminder-race hardening (needs a guard file, so after the freeze). The stale `reason:` text on the `updateWarranty` policy row (after the freeze). `deleteWarranty` and the batch-expiry fail-open siblings. Merging the end-date copies (O-10). | Guard freeze until 2026-12-01; scope. |
| R-1 | Gates | architecture-guardian and privacy-security-guardian, then reviewer-strict. No room-migration-guardian (no schema change). | T3 rule. |

## Consequences if accepted
1. The domain-modeler records the answers in the spec and adds T-1.
2. The feature-planner writes `workflows/active/warranty-edit-plan.md` (S-pre, S1 domain, S2 repository, S3 VM, S4 UI after QF-2d, S5 docs).
3. Implementation stays on hold. It needs "planning only" lifted, **and** QF-2d landed (so the QF plan needs approval).

## Owner answer (2026-10-26)
Owner: "Accept recommendations". **All recommendations are accepted**, including:
- an explicit yes on **T-1**: invert `WarrantyTrackerRepositoryTest.kt:820-837`; it is not deleted;
- **O-3**: the add flow moves onto the shared form;
- **O-5**: EXPIRED → ACTIVE only when the end date moves into the future.

Still open: el/es sign-off before merge. Implementation stays on hold until "planning only" is lifted and QF-2d has
landed.
