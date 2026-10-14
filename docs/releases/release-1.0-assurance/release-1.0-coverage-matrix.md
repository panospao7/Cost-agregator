# Release 1.0 coverage matrix and dispatch queue (plan)

```text
Workflow / phase / cell: release-1.0-assurance / coverage planning / all
Source candidate SHA: 84d819be6e288b82649a1c125fbcaa10f026d0d9 local HEAD, pushed
  (app/ identical to 8fdfd205 and ea446316; app-source pin for static audits)
Worktree fingerprint: NOT COMPUTED; app/ clean vs HEAD; see baseline dossier
Comparison baselines: campaign 37601232 / Wave 5 parent 3e8377e4 / Wave 4 a1c66173
Scope and write set: this file only (planning artifact)
Role, requested and actual model/effort: release-orchestrator Opus 5.5/high.
  Inputs: scout (Haiku 5.5/medium) whole-app crosswalk report and scout
  (Haiku/medium) fix-closure claims report. Both are T3 indexes. Lead
  spot-checks of the high-impact claims are noted where used.
Independent of discovery/implementation: planning only
Read depth: NO application source was audited. Every cell is NOT-READ/PENDING.
Commands executed: read-only git/gh/file listing (see baseline)
Status: PENDING (plan awaiting human approval)
```

This is a plan, not coverage. A file mapped to a cell has not been inspected,
and a test that exists has not been executed. Every row stays PENDING until a
slice report and an independent verifier dispose of it.

## 1. Axes kept from the original campaign

- Horizontal pipelines P-01..P-12 and shared engines E-01..E-05.
- Infrastructure I-01..I-05.
- New: complete user journeys VJ-01..VJ-24 (section 5) and runtime/artifact rows RT-* (section 7).
- Original ledger: 89 findings (7 P0 / 15 P1 / 54 P2 / 13 P3 per ledger header)
  in 30 clusters CL-01..CL-30, waves 1-4. Addenda A1-A8. Wave 5 money track
  M0-M2d, batches B1-B8, review findings F1-F10, G1, F2-GOAL-DELETE.

## 2. Cell matrix (all PENDING)

Tier: T1 is money, identity, persistence, privacy, restore, worker or guard. T2 is
feature logic or a consumer. Model column: discovery -> verifier.

| Cell | Scope | Segments | Owner seed (current paths) | Old clusters | Tier | Discovery -> verifier | Read | Tests/oracle | Runtime | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P-01 | Notification capture/intake | 3, 11, 12 | service/NotificationCaptureService, worker/NotificationIntakeWorker, domain/notification/capture | CL-01,02,03 | T1 privacy/worker | Sonnet/high (Opus/xhigh boundary) -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-02 | Transaction lifecycle | 9, 31 | domain/transaction/lifecycle/TransactionLifecycleCoordinator -> ExpenseDao.insertAtomic; domain/provenance, domain/sideeffect, domain/event | CL-04,07,08,09 | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-03 | Receipt/OCR/statement/matching | 4, 5, 38 | domain/receipt/lifecycle/*, ReceiptMatchLifecycleService, service/receiptmatching | CL-10,11 | T1 owner, T2 parser/UI | Opus/xhigh + Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-04 | Recurring and reminders | 7, 36 | domain/recurring/lifecycle/*, service/reminder/* | CL-12,13 | T1 lifecycle/claim | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-05 | Dashboard/synthesis | 10 | domain/usecase/dashboard, TotalsAggregationEngine, ui/screens/home | CL-14 | T2 (T1 money/unavailable) | Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-06 | Budget/forecast/cashflow | 1, 2, 13 | domain/budget, domain/forecasting (+ domain/forecast duplicate), domain/cashflow | CL-16,28 | T1 money, T2 rest | Opus/xhigh + Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-07 | Backup/restore/recovery | 18 | data/repository/DatabaseBackupRepositoryImpl, data/backup/* (BackupVerifier, RestoreJournal, RestoreMaintenanceMode, DatabaseWriteBarrier), data/rescue | CL-19,20 | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-08 | Privacy/retention/cloud AI | 20, 28, 29 | domain/privacy, data/privacy, domain/ai/HybridRouter, data/ai/provider | CL-17,18,21 | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-09 | Worker runtime | 12 | domain/workers/WorkerExecutionGuard, WorkerRegistry, startup/AppStartupCoordinator | CL-23 | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-10 | Bank sync/statement import | 14 | domain/bank (debug-only per BankFeatureAvailability; scope H-1), BankStatementParser | CL-19 (P10-001) | T1 identity, T2 adapters | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-11 | Email receipts | 4 (unowned data/email) | data/email/*, domain parser | CL-24, F5 | T2 (T1 privacy/source currency) | Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| P-12 | Export/import/accounting | 17, 18 | domain/export/*, AccountingExportPolicy, util/ImportCoordinator, Csv/JsonExpenseImporter | CL-25,26 | T1 accounting/privacy | Opus/xhigh + Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| E-01 | Money and time | 16, 32 | domain/currency/CurrencyConverter, MultiCurrencyRepository, domain/core/money (27 files), TimeProvider | CL-05,27 | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | n/a | PENDING |
| E-02 | Analytics shared | 8, 22, 27 | domain/analytics (25), AnalyticsInputAssembler, M2a-d | CL-28 | T1 normalized contracts, T2 engines | Opus/xhigh + Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | n/a | PENDING |
| E-03 | Merchant/categorization | 5, 6 | domain/intelligence/ml, domain/categorization, MerchantKeyBackfillWorker | none (zero findings at pin) | T2 (T1 dedupe) | Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| E-04 | Groups/investment/tax | 15, 17, 21, 24, 25 | domain/groups, data/database/GroupTransactionCoordinator, domain/investment, domain/tax, DemoTaxRateProvider binding | CL-29, A7 | T1 persistence/money | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| E-05 | Warranty/subscriptions/offers | 34 | domain/subscription, service/warranty, domain/price, domain/negotiation | (E05 rows in CL-06/07/09) | T2 (T1 lifecycle) | Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| I-01 | Worker/backup integration | 12, 18 | barrier/guard/restore pause/resume | CL-19 (I01-001) | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| I-02 | Persistence/schema | data/database (156, unowned) | AppDatabase v151, DatabaseSchemaPolicy, DatabaseMigrations, 70 DAOs/72 entities | CL-06 (I02) | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| I-03 | DI breadth | 30 | di/ (35), release bindings (DemoTaxRateProvider, StaticMarketRateProvider, PrivacyModule isDebugBuild) | CL-06 (I03) | T2 | Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |
| I-04 | Static guards/CI | 39 | scripts/ci/guard_registry.py, verify_*.py, config/guards, config/baselines, ci.yml | CL-22 | T1 | Opus/xhigh -> Opus/xhigh | NOT-READ | PENDING | n/a | PENDING |
| I-05 | UI/VM mutation + insight, navigation, debug reachability | 29, 33 + UI shell | ui/navigation, MainActivity, ui/screens/*, debug screens | CL-30, CL-08 (I05-001) | T2 (T1 dangerous writes) | Sonnet/high -> Opus/xhigh | NOT-READ | PENDING | PENDING | PENDING |

## 3. Segment disposition (39/39)

Every segment has an owning cell and a minimum journey. Scope inclusion is
PENDING human confirmation (H-1).

| Seg | Name | Cell(s) | Journey | Scope note |
| --- | --- | --- | --- | --- |
| 1 | Forecasting & runway | P-06 | VJ-08 | |
| 2 | Budget | P-06 | VJ-08 | |
| 3 | Notification capture/parsing/review | P-01 | VJ-02 | |
| 4 | Receipt OCR & lifecycle | P-03 | VJ-03 | |
| 5 | AI receipt item categorization | P-03, E-03 | VJ-03 | |
| 6 | Merchant categorization | E-03 | VJ-02, VJ-03 | |
| 7 | Recurring | P-04 | VJ-04 | |
| 8 | Analytics & insights | E-02 | VJ-09 | |
| 9 | Core expense management | P-02 | VJ-01 | |
| 10 | Dashboard totals & widgets | P-05 | VJ-07 | |
| 11 | Notifications & alerts | P-01, P-09 | VJ-05 | |
| 12 | Startup & background runtime | P-09, I-01 | VJ-20 | |
| 13 | Cash flow | P-06 | VJ-08 | |
| 14 | Bank integration | P-10 | VJ-15 | release FeatureUnavailable(RELEASE_BUILD) per scout; H-1 |
| 15 | Investment | E-04 | VJ-16 | |
| 16 | Currency & exchange | E-01 | VJ-06 | |
| 17 | Tax | E-04, P-12 | VJ-17 | DemoTaxRateProvider bound ungated (candidate, unverified) |
| 18 | Export & backup | P-07, P-12 | VJ-11, VJ-12 | |
| 19 | Location enrichment | I-05/P-09 (LocationBackfillWorker) | VJ-18 | |
| 20 | AI platform/assistant | P-08 | VJ-13 | |
| 21 | Split transactions | E-04 | VJ-14 | |
| 22 | Lifestyle inflation | E-02 | VJ-09 | |
| 23 | Savings nudges | E-01/E-02 (savings track) | VJ-10 | |
| 24 | Shared groups | E-04 | VJ-14 | |
| 25 | Shared budget offset | E-04 | VJ-14 | |
| 26 | Natural-language search | P-08 | VJ-13 | |
| 27 | Carbon | E-02 | VJ-09 | |
| 28 | Security & API keys | P-08 | VJ-13, VJ-21 | |
| 29 | Debug & diagnostics | P-08, I-05 | VJ-22 | release reachability of CategorizationDebugScreen/SourceLinkDebugScreen UNKNOWN |
| 30 | DI | I-03 | VJ-20 | |
| 31 | Use cases | P-02 | VJ-01 | |
| 32 | Utilities | E-01 | consumers | |
| 33 | Config/perf/accessibility | I-05 | VJ-23 | |
| 34 | Warranty/subscription/offers | E-05 | VJ-19 | StaticMarketRateProvider bound ungated |
| 35 | Savings optimization & health | E-02 + savings track | VJ-10 | |
| 36 | Bill reminders | P-04 | VJ-05 | |
| 37 | Spending challenges | E-05 (T2) | VJ-24 | |
| 38 | Receipt matching | P-03 | VJ-03 | |
| 39 | Static guards & CI | I-04 | n/a | |

Packages with no documented segment owner, as assigned for audit: `domain/provenance`
(28) and `domain/sideeffect` (20) -> P-02; `domain/event` -> P-02; `data/rescue`
(RescueActivity) -> P-07; `data/email` -> P-11; `data/store` -> P-05/P-02;
`data/database` (156) -> I-02; `domain/common|consistency|text|widget|dto|income|alerts|model`
-> owning consumer slice (Phase 1 resolves per file); `ui/navigation|util|theme|model|components shared`
-> I-05; `domain/forecast` vs `domain/forecasting` duplicate `NormalizedForecastInput` -> P-06.
Manifest components and permissions (MainActivity deep links, RescueActivity,
NotificationCaptureService, Boot/ServiceRestart receivers, reminder receivers,
FileProvider, location/camera/notification permissions) -> I-05 + owning
pipeline. RescueActivity exported-comment drift -> P-07.

## 4. Original fix-closure universe (input to Phase 2)

The claims below were indexed by Haiku and spot-checked by the lead where marked.
They are hypotheses, not verdicts. Seeded per-ID in `release-1.0-regression-ledger.md`.

- Wave 1 (CL-01, 17, 18, 19, 29): the documents record independent review FAIL
  verdicts with no later PASS (lead-confirmed by grep of the five review files).
  The journal records them as merged. They hold all 7 original P0s.
- Wave 2 (CL-05, 09, 15, 21, 22, 23, 27): mostly author self-review; CL-05 FAIL
  then human-waived; S1 recovery batch has both an independent PASS (journal)
  and a strict-review FAIL document that are not reconciled; RP-03 S2 asset-recovery
  gaps (six) have no closure record.
- Wave 3 (CL-07, 08, 10, 11, 25) and Wave 4 (CL-02..CL-30 remainder): validated
  by runner evidence with no independent review. Wave 4 final strict review is
  recorded as PENDING.
- Addenda: A3 SpendingMap privacy OPEN; A4 runtime evidence missing; A7
  independent review PENDING; A2 privacy-guardian approval PENDING.
- Wave 5: independent review FAIL; remediation re-review waived; F4/F5 appear
  only as approved intent in the remediation handoff. Lead spot-check: ea446316
  does touch the dashboard budget files, so F4 status is undetermined, not
  "missing". M-3..M-5 money track recorded as unstarted (2026-10-04).
- Guard debt: raw_money_aggregates went from 83-84 findings (W2-W4) to a 25/25
  PASS in W5 with no recorded fix or baseline (lead: no raw-money baseline file
  exists in config/baselines). Cancellation ratchet shrank 125 -> 112 with
  claimed approvals. Path UNKNOWN; I-04 must explain it.
- Contradictions to resolve: severity drift (P-06-003, I05-001, P12-001),
  Wave 2 commit vs BLOCKED closure review, A1 assignment, W4-7 meaning, 76-failure tail.

Old P0/P1 IDs with no independent PASS on record (per index): P01-001,
P07-003/004/005, P07-006, E04-001, E04-002 (P0); P02-002, P03-001, P03-002,
P03-003, P03-004, P04-005, P07-001, P07-002, P07-009, P08-001, P08-002,
P08-003, P10-001, P12-001, I05-001 (P1 or recalibrated).

## 5. Vertical journeys (Phase 4)

| ID | Journey | Cells | Minimum proof |
| --- | --- | --- | --- |
| VJ-01 | Manual expense create/edit/delete/filter -> events -> totals; duplicate/conflict | P-02, P-05 | real Room orchestration + device |
| VJ-02 | Notification live/deferred intake -> parse -> review -> durable expense; permission denied/retry | P-01, P-02, E-03 | worker + device |
| VJ-03 | Receipt camera/image -> OCR -> draft -> review/link/match; tax allocation; duplicate | P-03, E-03, E-04 | device |
| VJ-04 | Recurring rule generate -> match/pay -> reminder; retries, period anchors | P-04 | worker + device |
| VJ-05 | Bill reminder post/snooze/dismiss; notification permission denied | P-04, P-01 | device API 33+ and <33 |
| VJ-06 | Multi-currency entry -> conversion basis/rates -> every consumer; stale/missing rate | E-01 | real converter fixtures |
| VJ-07 | Dashboard totals/drill-down/widgets; empty/unavailable consistency | P-05 | device |
| VJ-08 | Budget status/rollover/alerts -> forecast/runway/cashflow; FX/time boundaries | P-06 | real fixtures |
| VJ-09 | Analytics/lifestyle/carbon trends and insights; incomplete data | E-02 | real fixtures |
| VJ-10 | Savings goals -> sweep recommendation -> accept -> history -> delete/recreate -> backup | savings, P-07 | Room + device (v151) |
| VJ-11 | Backup create -> verify -> restore (current + older supported) -> workers resume; crash mid-restore | P-07, I-01 | device |
| VJ-12 | Export CSV/JSON/accounting/PDF; import CSV/JSON; privacy mode | P-12 | device PDF |
| VJ-13 | AI assistant/NL search local/cloud/offline/denied; consent; API keys | P-08 | device |
| VJ-14 | Split -> group -> settlement -> balances -> budget offset | E-04 | Room |
| VJ-15 | Bank connect/sync/import (if shipped) or verified unavailable state in release | P-10 | release artifact |
| VJ-16 | Investment holding -> valuation; stale quote/source currency | E-04 | Room |
| VJ-17 | Tax configuration -> allocation -> report/export; rate provider provenance | E-04, P-12 | Room |
| VJ-18 | Location permission -> enrichment/map -> lifecycle update; denied | P-09, I-05 | device |
| VJ-19 | Warranty/return/subscription/price-protection/negotiation | E-05 | Room + worker |
| VJ-20 | Fresh install, cold start, upgrade from supported schemas, boot/package-replaced, process death | P-09, I-02 | device matrix |
| VJ-21 | Privacy settings/retention cleanup/raw-data deletion/consent revoke | P-08 | worker + device |
| VJ-22 | Diagnostics in release: no debug surfaces, bounded reason codes only | P-08, I-05 | release artifact |
| VJ-23 | Navigation/deep links/saved-state redaction/accessibility/rotation | I-05 | device |
| VJ-24 | Spending challenge start -> progress -> completion | E-05 | Room |

## 6. Dispatch queue (dependency-ordered, one slice per invocation)

Rules: one slice per dispatch, about 8-15 primary files plus real callers, at most two
independent read-only slices in parallel, a fresh `release-finding-verifier`
(Opus/xhigh) refutation pass after every discovery report, and no slice
starts before the human approves it. "Opus/xhigh override" means the
`release-regression-auditor` profile (Sonnet/high pinned) runs with an
explicit Opus 5.5/xhigh request because the slice is T1. The kit README requires this
(money/Room/privacy/workers start at Opus/xhigh). It is recorded per dispatch.

### Phase 2 - fix closure and regression (all read-only)

| Slice | Content | Primary files (seed) | Tier | Discovery | Depends on |
| --- | --- | --- | --- | --- | --- |
| RG-01 | CL-19 restore/backup fail-closed state machine: P07-003/004/005 (P0), P10-001 (P1), I01-001; plus classification of RP-03 S2 six asset-recovery gaps on the same files | RestoreMaintenanceMode, DatabaseWriteBarrier, RestoreJournal, RestoreJournalImporter, AppStartupCoordinator (restore/rollback ranges), DatabaseBackupRepositoryImpl (journal/swap ranges), BankConnectionLifecycleCoordinator, BankApiIntegration (persistOutcome), BankConnectionDao, WorkerRegistry | T1 | regression-auditor Opus/xhigh override | none |
| RG-02 | CL-18 privacy gate fail-closed/cancellation (P07-001, P08-001) + CL-17 P0/P1 subset (P07-006, E04-001, P08-003) + A2 denial contract | PrivacyGate family, BackupRestoreViewModel, EventMetadataSanitizer, diagnostics writers | T1 | Opus/xhigh override | RG-01 (shared restore preflight) |
| RG-03 | CL-01 notification capture privacy (P01-001 P0, P01-004) + CL-02/CL-03 deferred identity/retry + A1 cancellation sites | NotificationCaptureService, NotificationIntakeWorker, intake coordinator, capture gate | T1 | Opus/xhigh override | none |
| RG-04 | CL-29 group split/settlement (E04-002 P0, E04-003) + A7 settlement-in-balances + CL-05 E04-004 | SplitCalculator, GroupBalanceCalculator, SharedExpenseManager, GroupTransactionCoordinator | T1 money | Opus/xhigh override | none |
| RG-05 | Wave 5 remediation delta 3e8377e4..ea446316 (first independent review): F1-F3, F6-F8, F10, F2-GOAL-DELETE, v151 marker migration, backup tier 59, writer grant 429, G1 removals | the 20 changed main files + migrations/tests in ea446316 | T1 | Opus/xhigh override (room-migration-guardian as verifier option) | RG-01 (restore tier) |
| RG-06 | Wave 5 F4/F5 disposition + B1/B2 dashboard/forecast + M2a-d analytics normalization | ComputeDashboardWidgetsUseCase, BudgetStatusSnapshot, ForecastInputAssembler, analytics normalizer/aggregate builders, email currency path | T1 money | Opus/xhigh override | RG-05 |
| RG-07 | CL-09 stale-write CAS (P03-001, P04-005 P1, P09-001, E05-008) + A1 TLC site | TransactionLifecycleCoordinator CAS ranges, receipt/recurring writers | T1 | Opus/xhigh override | none |
| RG-08 | CL-15 failure-to-unavailable (8 findings; P03-003, P07-002, P07-009 P1) + S1 independent-PASS vs strict-FAIL reconciliation | per cluster files | T1 | Opus/xhigh override | RG-01 |
| RG-09 | CL-05 + CL-27 money contract + A5/A6/A8 + 76-failure-tail mapping | CurrencyConverter, MultiCurrencyRepository, money aggregates, consumers | T1 | Opus/xhigh override | RG-05 |
| RG-10 | CL-21 cloud policy/hybrid routing (P08-002 P1) + CL-10 OCR consent (P03-002 P1) | HybridRouter, CloudPayloadPolicy, receipt OCR consent | T1 | Opus/xhigh override | RG-02 |
| RG-11 | CL-07 + CL-08 lifecycle side effects/single writer (P02-002 P1, I05-001) + CL-04 latent backfill | TransactionSideEffectDispatcher, ReviewQueue approval, provenance backfill | T1 | Opus/xhigh override | RG-07 |
| RG-12 | CL-11 receipt duplicate fingerprint (P03-004 P1) + CL-25 CSV/JSON importer provenance (P12-001) + CL-26 export invalidation | receipt dedupe, importers, ExportOptionsViewModel | T1/T2 | Sonnet/high -> Opus verify | RG-11 |
| RG-13 | CL-23 worker diagnostics/drain + CL-12 reminder delivery + CL-13 recurring materialization | worker logger, reminder delivery DAO/workers, recurring reconciliation | T1 worker | Opus/xhigh override | RG-01 |
| RG-14 | CL-14, CL-16, CL-28 dashboard windows/budget clamp/analytics inputs | dashboard use cases, BudgetAutopilotEngine, analytics assembler | T2 | Sonnet/high -> Opus verify | RG-06 |
| RG-15 | CL-20 bundle privacy mode, CL-24 email parser, CL-30 nav saved-state redaction, CL-17 remaining P2/P3, CL-06 docs, A3/A4 | per cluster | T2 | Sonnet/high -> Opus verify | RG-02 |
| RG-16 | B3-B8 savings/source/PDF batches not covered by RG-05 (B4 provenance, B5 history, B6 bank/receipt fail-closed, B7 PDF policy) | savings history, BankStatementParser boundary, AccountantReportPdfExporter | T1 money | Opus/xhigh override | RG-05 |
| RG-17 | CL-22 guard fail-closed + raw_money 83->PASS path + cancellation ratchet shrinks + 28 skipped guard tests + 276/277 skipped unit tests inventory | scripts/ci/*, config/guards, config/baselines | T1 | Opus/xhigh (architecture-guardian verifier) | none (read-only, can parallel RG-01) |

### Phase 1 indexing (parallel-safe, T3)

| Slice | Content | Model |
| --- | --- | --- |
| IX-01 | File-granular index for MULTI/UNMAPPED packages; tag every test as pure/mock/Room/orchestration/connected; shard-overlap check | scout Haiku/medium (cannot certify) |
| IX-02 | Old cell reports and verification reports -> per-ID current path re-resolution | scout Haiku/medium |

### Phase 3 horizontal (after Phase 2 for the same owners)

HZ-01 E-01 money/time contract and all consumers. HZ-02 I-02 schema/migration
chain v145->v151 and writer ownership. HZ-03 P-07 restore beyond CL-19 (backup
tiers, old backup formats, rescue). HZ-04 P-02/P-03 mutation + provenance +
sideeffect packages. HZ-05 P-09/I-01 runtime (10 workers, guard, barriers,
leases). HZ-06 P-08 privacy/cloud/diagnostics/retention. HZ-07 I-03 DI release
bindings (Demo/Static providers) + I-05 debug reachability in release. HZ-08
I-04 guard enforcement vs actual architecture. All use release-horizontal-auditor
(Opus/xhigh) with a fresh release-finding-verifier.

### Phase 4 vertical

VJ-01..VJ-24 above with release-vertical-auditor Sonnet/high, at most two in parallel,
ordered VJ-11, VJ-10, VJ-01, VJ-02, VJ-03, VJ-06, VJ-20, VJ-21, then the rest.
Fresh Opus/xhigh verification for each findings batch.

### Later (need separate approval)

Remediation batches (05), strict review, the serialized validation queue,
runtime/device/artifact (08: RT-01 fresh install API 26/29/31/33/35, RT-02
supported upgrade chain, RT-03 old/current backup restore, RT-04 permission
denied paths, RT-05 optimized release artifact + verify_release_artifact,
RT-06 controlled beta), and final acceptance (06, Opus/max).

## 7. Expected evidence per slice

Each discovery report must contain: provenance block (artifact-templates);
per old ID the classification (NEW-REGRESSION / CHANGED / STILL-OPEN /
PRE-EXISTING / TEST-DEBT / DOC-DRIFT / UNKNOWN), with SHA:path:symbol:line
evidence; caller/consumer trace; full/scoped/search-only/not-read lists; test
evidence tagged by type (pure, mock contract, real Room, orchestration,
connected) and with its oracle independence assessed; candidate findings with
severity and NEEDS_RUNTIME flags; pending scope. The verifier report must give a per-candidate
CONFIRMED / RECALIBRATED / REFUTED / NEEDS_RUNTIME result with its own evidence. The lead
records both into the ledgers and verifies the app-source pin before and after
(`git diff --quiet ea446316 -- app` against the worktree, plus no untracked app files;
also record `git rev-parse HEAD`).

## 8. Recommended first slice

RG-01 (CL-19 restore/backup fail-closed state machine). Rationale: three of
the seven original P0s (all seven are Wave 1); both independent reviews FAIL with no recorded PASS;
P-07 restore is a dependency of restore-sensitive workers, CL-18/CL-15, and
the Wave 5 v151 backup tier. The audit is static and read-only, so it does not depend
on B-1..B-3.
