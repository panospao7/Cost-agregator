# fc-paid-unlinked: impact map (impact-mapper, 2026-11-06, mainline e4376a77)

**Tier: T3.** Recurring link lifecycle (money), a new event type, coordinators under workflow guards, and a change to the merged DR-11 behaviour. No schema change.

## Legal paths
- **Confirm** goes through `claimLinkSuppressInWrite`, which reuses existing rows (`db_ownership_policy.yml` 5966-6015). That method's signature must not change.
- **Decline** goes through `eventWriter.writeCritical` with the new event `LINK_SUGGESTION_DECLINED`, covered by FG-14, `RecurringArchitectureGuardTest:556-573` and `DirectEventDaoInsertGuardTest`.
- **Reads** use `occurrenceDao.getByDateRange`/`getById`, `expenseDao.getExpensesBetween` and `lifecycleEventDao.getEventsForOccurrence`. Check the FG-13 classification output in the S2 run.

## Consumers that change after a confirm
ForecastInputAssembler, CashFlowCalculator, FinancialStressForecastEngine, ComputeDashboardWidgetsUseCase/MoneyRadar, FinancialWeatherRepository, MonthlySavingsSweepUseCase and BudgetForecastingEngine.
- Monte Carlo and Runway follow `rule.nextDate`, so `advanceRuleAfterAutoLink` carries the load.

## Tests
- D-4 flips two tests in `RecurringAutoLinkWindowLinkTest`: line 460 (weekly +3, now kept) and line 477 (monthly +4, now kept).
- D-3 needs new tests where the nearest occurrence is SKIPPED or PAID.
- Adding a `DatabaseReadBarrier` to the coordinator touches about 12 fixture files.
- Resolver tests: `OccurrenceConflictResolverClaimedTest`, `InstallmentExclusionTest`, `RecurringLinkTypeEligibilityParityTest`.
- D-8 affects six `resolve()` sites: `RecurringRuleLifecycleCoordinator` 198, 260, 413, 702 and `RecurringLifecycleCoordinator` 235, 336.

## Slices (run serially; S1 and S2 both edit RecurringLifecycleCoordinator)
- **S1, domain:** `RecurringPaymentLinkMatcher` (the amount rule, the S window, period extraction), D-3, D-4 and the decline exclusion in link and keep, plus resolver delegation. Sonnet writer and a separate test-author.
- **S2, coordinator and use case:** `findPaymentLinkSuggestions`, `confirmPaymentLink`, `declinePaymentLink`, the read barrier, the `MarkRecurringBillPaidUseCase` methods, an INV-1 architecture test and a LEGAL_PATHS update. After S2 merges, the Radar redo (P-05-5) can resume.
- **S3, UI:** the Recurring row affordance, the Home hint and the ViewModels, plus strings in en, with el and es DRAFT.

## Risks
- **L-7:** the advance after a confirm can be lost on process death (known limit).
- Avoid any new policy rows or db_access classification entries.
