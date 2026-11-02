# Unfinished / placeholder / partial features — audit inventory (2026-10-22)

Compiled by the orchestrator from four read-only scout passes (Sonnet; grep + spot reads,
no builds). **Every item is UNVERIFIED until its row in a `verification-*.md` file confirms it.**
Paths are relative to `app/src/main/java/com/yourname/expensetracker/` unless rooted.

Track legend (proposed, set at triage): **A** minor fix · **B** complete a partial feature ·
**C** not started → plan later · **X** dispose (hide / relabel / remove) · **R** reconcile docs/handoffs.

Navigation note: there is no NavHost; routing is `ui/navigation/NavigationDestination.kt` +
`when` in `ui/MainActivity.kt` (~660-1013) + `ui/navigation/NavigationController.kt`.

## U — Release-visible UI wiring gaps

| ID | Item | Evidence | Initial status |
|---|---|---|---|
| UF-01 | Home category breakdown "Show all N" no-op | `ui/components/CategoryBreakdownSheet.kt:93-110`; opened `HomeScreen.kt:829-831,1061` | UI-not-wired |
| UF-02 | Warranty edit missing (TODO, no edit dialog) | `ui/screens/warranty/WarrantyTrackerScreen.kt:55-66` | missing |
| UF-03 | Warranty empty-state "Scan receipt" + OpenFeature actions no-op | `WarrantyTrackerScreen.kt:71,283-296`; `MainActivity.kt:772-776` | UI-not-wired |
| UF-04 | Subscription empty-state "Notification settings" + NavigateToDestination no-op; dead `onOpenBankConnections` param | `ui/screens/subscription/SubscriptionManagementScreen.kt:50-51,141-147`; `MainActivity.kt:960-965` | UI-not-wired |
| UF-05 | Savings goal delete not exposed (VM has `deleteGoal`) | `ui/screens/savings/SavingsGoalsViewModel.kt:464` | UI-not-wired |
| UF-06 | NL search result tap ignores transactionId ("detail view coming soon") | `MainActivity.kt:791-794` | partial |
| UF-07 | Home stress-forecast actions route by English substring match | `HomeScreen.kt:923-938` | partial / fragile |
| UF-08 | Money Radar "Adjust budget" only acts on CRITICAL/HIGH | `HomeScreen.kt:909-914` | partial |
| UF-09 | Stale `challenges_unavailable_message` string | `res/values/strings.xml:1827`; `domain/challenge/SpendingChallengeManager.kt:45` | stale copy |
| UF-10 | `FeatureConfig.isBeta` never set → Beta badge dead path | `ui/navigation/FeatureConfig.kt:35,281`; `HomeScreen.kt:1760-1764` | dead path |
| UF-11 | Voice query `executeVoiceQuery` has no caller | `ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt:155` | dead code |
| UF-12 | `SourceLinkBackfillViewModel` has no screen/consumer | `ui/screens/settings/SourceLinkBackfillViewModel.kt:13-50` | orphan |
| UF-13 | Privacy settings per-setting "restart required" hints TODO | `ui/screens/privacysettings/PrivacySettingsScreen.kt:55` | cosmetic TODO |
| UF-14 | Deep links processed without auth confirmation (TODO) | `MainActivity.kt:187` | security TODO |

## P — Release-visible placeholder / static data

| ID | Item | Evidence | Initial status |
|---|---|---|---|
| UF-20 | Price Protection: simulated price drops, fake "Competitor Store" deal, "SAVE10" coupon (badged SIMULATED); invented credit-card benefits (**not badged**); hardcoded return windows (WRN-22) | `domain/price/PriceProtectionTracker.kt:213-409`; `ui/screens/price/PriceProtectionScreen.kt:263-270,606,662,696,777,812` | placeholder |
| UF-21 | Bill negotiation static Greek EUR market rates; non-EUR home currency gets no quotes | `data/negotiation/StaticMarketRateProvider.kt:19-70`; `di/NegotiationModule.kt:17-19` | partial |
| UF-22 | AI settings "Phase 4A Experimental" labels; on-device "not implemented yet" for non-listed capabilities | `res/values/strings.xml:1979-1984`; `ui/screens/aisettings/AiSettingsScreen.kt:299-318`; `domain/ai/**/DefaultAiCapabilityRouter.kt:188,343` | partial (overlaps ai-fixes) |

## G — Release-hidden (DEBUG-only) unfinished features

| ID | Item | Evidence | Initial status |
|---|---|---|---|
| UF-30 | Bank connections: stub/mock sync, `@StubForDemo`, add-connection navigates back | `domain/bank/BankApiIntegration.kt:97-214,545,834-878`; `BankFeatureAvailability.kt:11-47`; `BankApiConfig.kt:10`; `MainActivity.kt:818-836` | demo stub |
| UF-31 | Investment portfolio: add button navigates back; `InvestmentTracker.addHolding` unused by UI | `MainActivity.kt:802-817`; `domain/investment/InvestmentTracker.kt:150`; `ui/navigation/DeferredFeatureGate.kt` | partial |
| UF-32 | Tax configuration: country not persisted, VM selection disconnected from `TaxEstimator`, GR/US only, US brackets stop at 95k, demo 50k calculator | `domain/tax/TaxConfiguration.kt:3-98`; `ui/screens/tax/TaxConfigurationViewModel.kt:74-127` | partial |
| UF-33 | Spending challenges hidden by DeferredFeatureGate; deprecated-API TODOs | `DeferredFeatureGate.kt:21,31`; `SpendingChallengeManager.kt:59,214,225` | partial |
| UF-34 | Shared budgets: `getMemberContributions` throws; `SharedBudgetManager` no production caller | `domain/budget/SharedBudgetManager.kt:46,79-85` | partial / dead |
| UF-35 | Financial rescue compiled out (`ENABLE_FINANCIAL_RESCUE = false`) | `data/rescue/RescueConfig.kt:12`; `MainApplication.kt:51`; `FinancialRescueCoordinator.kt:54` | disabled |

## L — Backend partial logic (DB / money / lifecycle items need Sonnet confirmation)

| ID | Item | Evidence |
|---|---|---|
| UF-40 | Legacy NL search: parsed category/location/merchant filters not applied (W15) | `domain/naturallanguage/NaturalLanguageSearchEngine.kt:215-225` |
| UF-41 | Assistant min/max amount filters carry no currency (PR5/W32) | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:34` |
| UF-42 | Cash-flow forecast ignores recurring income | `domain/cashflow/CashFlowCalculator.kt:672` |
| UF-43 | Bill reminders read-only on legacy `BillReminderManager` (migrate to RecurringLifecycleCoordinator) | `ui/screens/reminder/BillRemindersScreen.kt:30-40` |
| UF-44 | Categorization learning: CategoryMappingWriter missing (C04), context signals (C13), 2-correction override (C10) | `domain/categorization/CategorizationEngine.kt:556-557`; `ContextualInferenceEngine.kt:18`; `HybridExpenseClassifier.kt:249` |
| UF-45 | Export: receiptLinks missing; accountant PDF raw Double totals | `domain/export/ExportTransaction.kt:56`; `AccountantReportPdfExporter.kt:40` |
| UF-46 | BackupVerifier: no semantic post-restore verification (P7-P1-05) | `data/backup/BackupVerifier.kt:26-34` |
| UF-47 | ManualExpenseRepository: recurring path may bypass recurring coordinator; `UserSessionProvider` TODO (also `HomeViewModel.kt:175`) | `data/repository/ManualExpenseRepository.kt:70,185` |
| UF-48 | GroupTransactionCoordinator soft-delete routing (G08) / member-delete validation (G09) | `data/database/GroupTransactionCoordinator.kt:649,977` |
| UF-49 | ~20 `@Suppress("DEPRECATION_ERROR")` legacy money-API call sites | `ExpenseRepository.kt:932-1203`; `BusinessExpenseRepository.kt:49-79`; importers; `LegacyDataMigrationService.kt:226` |
| UF-50 | `NoOpWorkerDrainController` used on backup/restore fallback paths | `domain/workers/NoOpWorkerDrainController.kt`; `DatabaseBackupRepositoryImpl.kt:132,136`; `RestoreMaintenanceMode.kt:55` |
| UF-51 | Dead code: `MultiCurrencyRepository.updateExpenseCurrency` empty, no callers | `data/repository/MultiCurrencyRepository.kt:1115-1123` |
| UF-52 | Repo TODOs: weather confidence penalty, budget snapshots unused, N+1 queries | `FinancialWeatherRepository.kt:68,73`; `AnalyticsRepository.kt:167`; `BudgetRepository.kt:236,462`; `DashboardContractsAdapter.kt:154` |
| UF-53 | Speech input cleanup not wired to `onCleared` (W35) | `data/speech/AndroidSpeechInputGateway.kt:112` |
| UF-54 | Business-flags update lacks coordinator (T10) | `data/repository/ExpenseRepository.kt:179` |
| UF-55 | Geoapify client-side key; TODO backend proxy | `data/location/GeoapifyGeocodingService.kt:164` |
| UF-56 | Hardcoded heuristics: health score 75 fallback; €100 follow-through threshold | `domain/health/FinancialHealthScoreV2.kt:594`; `domain/engine/DashboardFollowThroughEngine.kt:32` |
| UF-57 | Debug restore not transactional (P2-CURRENT-016) — debug only | `ui/screens/debug/DebugViewModel.kt:154` |

## D / R — Docs and workflow reconciliation

| ID | Item | Evidence |
|---|---|---|
| UF-60 | FEATURES.md claims Complete for Bank, Shared Budgets, Investment, Tax, Challenges; stale header/footer | `docs/features/FEATURES.md:485,523-543,604-619,641,684,1076-1089` |
| UF-61 | Possibly stale handoffs vs Release 1.0.2 acceptance (0a52b066): release-1.0-assurance, Wave 5 PARTIAL set, wave2/3/4, money-m2a | `workflows/active/*` |

## N — Not started (already tracked elsewhere — do not duplicate)

Approved-but-parked plans and brief-only features are tracked in
`docs/features/_discovery/planning-status-matrix.md` (ai-fixes, nl-quick-add, money-correctness,
manual-entry-currency, assistant-follow-ups, cloud-alias-hashing, briefing-grounded-signals,
loan-installments, app-lock-privacy-mode; brief-only: capture-health-and-review-safety,
money-aggregates-sweep, bills-paid-reconciliation, group-settle-up). This program only links
to them where a UF item overlaps.
