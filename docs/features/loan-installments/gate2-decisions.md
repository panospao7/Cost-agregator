# loan-installments — Gate 2 decision sheet

Compiled by feature-orchestrator, 2026-10-16, from `domain-spec.md` §14 (OD-1..OD-15), `impact-map.md` A7, and
`ux-flows.md` owner questions. Recommendations are the authoring agents', not the orchestrator's. Owner answers are
recorded in `workflows/active/loan-installments-handoff.md`.

## A. Domain open decisions (domain-spec.md §14)

| ID | Decision | Agent recommendation |
|---|---|---|
| OD-1 | New legal path: separate `InstallmentPlanLifecycleCoordinator` with own tables (not reuse recurring) | Approve |
| OD-2 | New `expenses.spendingExclusionReason` column + TLC op `setSpendingExclusionDbOnly` + canonical spending predicate + guard | Approve |
| OD-3 | End-plan semantics | Separate Stop / Refund-Return / Pay off early; Delete only with no recorded payments; a single "Cancel" maps to Stop |
| OD-4 | Default recording mode | AUTO_RECORD (expense created on/after due date); past-due at declaration not auto-recorded |
| OD-5 | Bank "ΔΟΣΗ n/N" charge fulfils the planned installment | Yes, via one-tap suggested link with duplicate deferral |
| OD-6 | Rounding residual placement | First payment |
| OD-7 | Rounding mode | HALF_UP |
| OD-8 | Frequencies in v1 | Monthly only |
| OD-9 | Shared / split-category / not-mine purchases | Refuse declaring installments on them in v1 |
| OD-10 | Charge shifted by business days past due month | Counts in its own (actual) month |
| OD-11 | Export | Export purchase rows marked "not counted"; no plan-data export in v1 |
| OD-12 | Health score + SynthesisEngine | Stay unwired in v1 |
| OD-13 | Plan event/audit table | None in v1 (adding one needs a retention-contract change) |
| OD-14 | Plan currency ≠ purchase currency | Allow; financing cost shows "unavailable" |
| OD-15 | Fallback if coordinator ops can't share one DB transaction | Accept two-step write repaired by reconcile (only if planner proves necessary) |

## B. Impact-map approvals (impact-map.md A7)

| ID | Approval | Note |
|---|---|---|
| IM-1 | Schema: DB 151→152, additive (2 tables + 1 column), migration + snapshot + androidTest migration tests | No destructive change |
| IM-2 | Risk tier T3 + mandatory room-migration, privacy-security, architecture guardians + reviewer-strict | |
| IM-3 | Record "Installment Plan Mutations" legal path + Expense Mutations update in `LEGAL_PATHS.md` | Pairs with OD-1/OD-2 |
| IM-4 | New guards: canonical spending predicate, plan single-writer, cloud-reachability | Strengthening, but guard registry change |
| IM-5 | `config/guards/db_ownership_policy.yml` + signature accounting entries for plan DAO | Guard config change |
| IM-6 | Possible `check_raw_money_aggregates` allowlist/ratchet change when aggregates adopt predicate | Guard config change |
| IM-7 | AUTO_RECORD host: reuse existing recurring-generation worker vs new `WorkerRegistry` entry | Planner to propose; owner approves |
| IM-8 | New enum values `ExpenseSource.INSTALLMENT_PLAN`, `TransactionUpdateKind.SPENDING_INCLUSION` | Persisted; backup compatibility |
| IM-9 | Data-wipe covers plan tables; backup privacy mode strips plan `displayName` / `lenderLabel` like merchant/notes | Planner to verify |
| IM-10 | Timing: implement after Release 1.0 work in `Cost-agregator-release` lands (conflicts in Transactions, Add expense, Budget) | Post-1.0 already confirmed |

## C. Owner-facing UX questions (ux-flows.md)

| ID | Question |
|---|---|
| OQ-7 | Add expense has no currency picker → USD installments on a *new* expense need a picker, or USD limited to standalone plans / existing USD expenses |
| OQ-14 | No app-wide "hide amounts" mode exists — acceptable for v1? |
| OQ-16 | No expense detail screen → declare from Transactions row menu (bottom sheet) acceptable? |
| OQ-18 | Payments made before you started using the app: ignore, or record as history only? |
| OQ-19 | Default category for a standalone loan/plan (e.g. "Loans") |
| OQ-22 | Should auto-recorded installment expenses be visibly marked/notified? |
| OQ-23 | Approve an event table (OD-13) for a full change history? |
| OQ-8 / 10 / 11 / 17 | History per-currency header; Spanish translation parity; Home card link; "lent money coming soon" teaser |

## Owner decisions (2026-10-16)

- A (OD-1..OD-15): accept all recommendations (OD-15 only if planner proves necessary).
- B (IM-1..IM-10): approved; IM-7 worker host proposed by planner and confirmed at plan gate.
- C: OQ-7 add a currency picker to Add expense (planner keeps scope minimal). OQ-18 pre-app payments = history only.
  OQ-19 no loan-type taxonomy, name is enough; standalone plans get a user-chosen existing spending category with a
  default; purchase-attached plans inherit the purchase category. OQ-22 auto-recorded installments visibly marked.
  OQ-16 Transactions row menu entry OK. OQ-14 hide-amounts out of scope.
