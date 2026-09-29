# A7 — author static review and validation handoff

Status: implementation and targeted regression tests authored. This is an author self-review, NOT an independent strict/architecture approval. New compile/tests/guards: NOT RUN. A7 is not closed.

## Source-level review

- Both actual consumers are covered: SharedExpenseManager and SharedExpenseGroupsViewModel. The latter receives settlement history through the existing chunked GroupsRepositoryImpl read path, rather than the unused Room relation aggregate.
- GroupSettlementBalancePolicy owns exact group/currency/status eligibility and outgoing-minus-incoming direction for the manager, screen, and existing GroupBalanceCalculator. Decimal accumulation avoids introducing floating-point payment sums. Zero and overpayments remain unclamped; non-finite effective values fail rather than hiding history.
- Expense split validation, historical participation, expense paid/owed totals, manager HALF_UP rounding and SettlementCalculator suggestion solver remain unchanged. Tests exercise the manager-to-suggestion path to prevent re-suggesting an already-paid balance.
- New persistence operations are SELECT-only. The bulk query preserves the repository’s 500-ID chunking. Query errors/cancellation propagate; the touched screen catch rethrows cancellation, clears stale balance displays on other errors, and emits a bounded message instead of exception text.
- Required port/constructor callers and the explicit E2E fake were updated. Production adapters populate history explicitly; no production catch-to-empty fallback was introduced.
- No Room entity/schema/version, migration, settlement writer, baseline or allowlist changes. The dormant recordSettlement implementation remains untouched and uncalled; the existing LEGAL_PATHS wording discrepancy is recorded in the spec, not treated as permission to revive it.

## Files touched

Production: domain/groups/GroupSettlementBalancePolicy.kt (new), SharedExpensePort.kt, SharedExpenseManager.kt, GroupBalanceCalculator.kt; data/database/dao/GroupSettlementDao.kt; data/repository/SharedExpenseDataPortAdapter.kt, GroupsRepository.kt, GroupsRepositoryImpl.kt; ui/screens/groups/SharedExpenseGroupsViewModel.kt.

Tests: GroupSettlementBalancePolicyTest (5 new), SharedExpenseManagerSettlementTest (4 new), SharedExpenseSettlementReadTest (3 new), GroupsRepositoryImplTest (4 new), SharedExpenseGroupsViewModelTest (2 new plus bounded-error assertion update), GroupTransactionCoordinatorTest (1 new real-Room historical-read test plus constructor wiring), NotificationExpenseDashboardPipelineTest (required read-port fake). Existing GroupBalanceCalculator tests remain intact.

## Human-run validation (serial; not executed by this session)

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile compile
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*GroupSettlementBalancePolicyTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*SharedExpenseManagerSettlementTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*SharedExpenseSettlementReadTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*GroupsRepositoryImplTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*SharedExpenseGroupsViewModelTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*GroupTransactionCoordinatorTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*GroupBalanceCalculatorTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*SharedExpenseManagerTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*GroupSettlementPipelineTest'
```

Record durable command/exit/log evidence before any PASS claim. Poll RUNNING work instead of starting another validation process. Independent review and live validation remain required. Existing broader test/guard debt is not cleared by this authoring work.
