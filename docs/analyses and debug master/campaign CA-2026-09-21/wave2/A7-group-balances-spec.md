# A7 — settlement-aware displayed group balances

Starting HEAD: `a4807632`. Source inspection confirms A7 remains open after CL-29. This is a bounded, read-only CL-29 follow-up within the requested remaining-work session; implementation and independent/live gates are separate.

## Current-source evidence

- `SharedExpenseManager.calculateBalances` reads only members/expenses and rounds its expense-only net balances using the existing two-decimal HALF_UP contract.
- The actual groups screen independently calls `SplitCalculator.calculateBalances` through `SharedExpenseGroupsViewModel`. Fixing only the manager would leave the displayed defect open.
- `GroupsRepositoryImpl.getActiveGroupsWithDetails` uses chunked DAO reads, not the unused Room relation aggregate. Extend that actual read path without per-group query fan-out.
- `GroupBalanceCalculator.calculateMemberBalance` already applies RECORDED/COMPLETED settlements in the exact group currency with outgoing positive and incoming negative. Preserve this direction, historical participation, zero payments and overpayment behavior.
- `SettlementCalculator.calculateSettlements` consumes already-computed net balances and does not apply settlement history again.
- `SettlementCalculator.recordSettlement` has a dormant implementation but no call sites in main/test/androidTest source. LEGAL_PATHS describes no legal production writer. Do not call, revive, modify, or authorize that dormant API; document the wording discrepancy rather than expanding mutation scope.

## Planned change

1. Add a minimal domain settlement balance record, excluding notes, linked expense payloads and other unused fields. Add one shared pure settlement policy used by the existing single-member calculator, manager and screen. It owns status/currency/group filtering and outgoing-minus-incoming direction; decimal arithmetic avoids introducing new floating-point accumulation.
2. Extend SharedExpenseDataPort with a required settlement read and its data adapter. Extend the existing groups aggregate/batched repository path with settlements using one chunked DAO read per group-ID chunk. No default empty production implementation or catch-to-empty fallback.
3. Apply settlement adjustments after existing expense split calculations. Keep expense paid/owed totals, split validation, joinedAt/leftAt semantics, manager rounding, and settlement suggestion solver unchanged.
4. Propagate cancellation and show a bounded load error at the touched screen boundary; never expose a settlement query exception message or present missing settlement history as a successful expense-only balance.

## Tests and gates

Author focused tests for partial/full/multiple payments, excluded statuses/currency/groups, overpayments/zero, precision, departed members, expense totals unchanged, manager-to-suggestion no double counting, batched repository/adapter transport, UI display and cancellation/failure behavior. Preserve existing GroupBalanceCalculator tests. Update explicit port fakes and required constructor callers.

No Room schema/entity, migration, settlement mutation, guard baseline or allowlist change is intended. New validation: NOT RUN (human-owned runner policy). Independent strict/architecture review remains pending; no A7 closure or new waiver is claimed.
