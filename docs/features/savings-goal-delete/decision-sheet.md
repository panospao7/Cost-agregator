# Decision sheet: savings-goal-delete (UF-05), human gate after the impact map (2026-10-26)

**Sources:**
- `domain-spec.md` (O-1..O-8, SG-1..4)
- `ux-flows.md` (UX-1..6)
- `impact-map.md` (§9, plus the orchestrator verification section)

**Tier: T3**, at the low end. With the recommended option there is **no Room/schema change**. A design with no guard edit exists (see G-1).

**How to reply:** say "accept recommendations", or override individual items by ID. Two things still need you later: el/es copy sign-off before merge, and an escalation if G-1's guard run fails.

## Decisions
| ID | Question | Recommendation | Why |
|---|---|---|---|
| **O-1 (D-SG)** | What deleting a goal does | **(c) Allow delete only for an empty goal:** balance exactly 0, no ledger rows, no contribution events. The check and the delete run in one Room transaction against the database row, not the UI's snapshot. Funded and completed goals show an explanation instead. Archive becomes a separate later feature, `savings-goal-archive` (v153, after loan). | No money history can be lost, and it needs no schema change. (a) would cascade-delete sweep history and accepted plans, and it races with an in-flight sweep accept. |
| O-2 | Who owns the delete rule | Extend the existing `SavingsGoalRepository.deleteSavingsGoal`. Do not add a coordinator. | The legal path stays the same. A new coordinator would force new guard policy rows. |
| O-3 | Confirm dialog for an empty-goal delete | Yes, one dialog naming the goal. | The delete can't be undone. |
| O-4 | Archived balances in totals | Out of scope. Decide this in `savings-goal-archive`. | Applies only to (b). |
| O-5 | `first_goal` achievement re-locks if the only goal is deleted | Accept it, and pin the behaviour with a test. | The achievement is derived from goals. |
| O-6 | Harden the FK from CASCADE to RESTRICT | Defer it, and revisit with archive. | It needs a two-table rebuild. With (c), the cascade is unreachable. |
| O-7 | Clean up orphaned contribution events | No cleanup job. | No product path can create them today. |
| O-8 | Strings | **EN + EL + ES** in the same change, overriding the spec's "en + el". These are the first translated savings strings. The implementer drafts EL/ES and you sign off. | Lint fails EN-only strings, and the baseline must not grow. |
| UX-1 | Mention a future archive in the "can't delete" dialog | No, not until archive is approved. | Avoid promising a feature that may never ship. |
| UX-2 | Can the in-progress delete be cancelled? | No. Disable both buttons while the single transaction runs. Leaving the screen cancels the work cleanly (INV-12). | It's a short single transaction, so a Cancel button adds nothing. |
| UX-3 | Show Delete on funded goals? | Show it. Tapping opens the "can't delete" explanation. | Users learn why it isn't possible. |
| UX-4 | Message when the goal was already gone (a concurrent delete) | Same "Goal deleted" message. | The result the user sees is the same. |
| UX-5 | Generic failure text | Reuse `error_delete_failed`. | One fewer string. |
| UX-6 | Header text after the last goal is deleted | Verify it, and fix it only if "Unavailable" actually shows for an empty list. | Possible false error display. |
| **G-1** | Guard approach (freeze until 2026-12-01) | Use the **no-edit design**: same callable and parameter, the only mutation is `savingsGoalDao.deleteGoal`, reads have no mutation-verb names, no new DAO delete query. Validation runs the db-ownership guard on slice S2 alone, and that run is the decider. **If it fails, work stops and the question comes back to you.** No guard edit happens without your approval. | Avoids any guard-freeze edit if the analyzer accepts the transaction shape. |
| G-2 | Keep the parameter `deleteSavingsGoal(goal: SavingsGoal)` even though only the id is used | Yes, with a KDoc saying the amounts are ignored. | Keeps the guard identity unchanged, and the VM write-blocked test stays valid. |
| T-1 | Existing tests | Rewrite `SavingsGoalRepositoryCurrencyTest.kt:58-72` to the new contract, and change the constructor call in `NotificationParserDashboardTotalSeamTest.kt:408`. Extend `SavingsGoalsViewModelWriteBlockedTest`. **Nothing is deleted or ignored.** The cascade and marker pins stay unchanged. | The old test asserts the unconditional delete that this feature removes. |
| T-2 | Ordering against `test-suite-audit-plan` (B6-18, B6-23, B8-5 move or merge the savings tests we rely on) | Whichever lands second rebases. This feature does not edit those test files. | Disjoint write sets. |
| R-1 | Guardians | `architecture-guardian` (legal path, transaction ownership, FG-13), `room-migration-guardian` (writer ownership, atomic check-and-delete) and `privacy-security-guardian` (bounded diagnostics, goal name), then `reviewer-strict`. | T3 rule: run every guardian whose area is touched. |

## Verified facts (orchestrator, git)
- Loan (34b20715) and the app-lock tip (1efdda7e) don't touch this feature's write set, and `money-aggregates-sweep-plan.md` has no savings items.
- The lint MissingTranslation baseline in int is 2223, and the cap is derived from it. The rule is unchanged: never add to the baseline.

## Consequences if accepted
- **Spec revision.** The domain-modeler applies these changes:
  - O-8 becomes three languages;
  - SG-2 is reworded to the G-1 design;
  - SG-3 is replaced by T-1;
  - O-2's claim that "either way the policy entry changes" is corrected.
- **Plan.** The feature-planner writes `workflows/active/savings-goal-delete-plan.md` from impact-map §8 (S1..S6). The S2 guard run is the decision point.
- **Not started.** Implementation stays on hold while you keep "planning only". There is no dependency on the loan merge.

## Owner answer (2026-10-26)
Owner: "Accept recommend". **All recommendations are accepted**, including G-1 (the no-guard-edit design; if the S2
guard run fails, work stops and the question goes back to the owner) and T-1 (rewrite and extend the tests, with no
deletion or ignore).
Still open: el/es sign-off before merge. Implementation stays on hold while the owner keeps "planning only".
