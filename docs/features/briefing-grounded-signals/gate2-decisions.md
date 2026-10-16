# briefing-grounded-signals (inc 1) — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` (OD-B1..B10), `ux-flows.md` (UX-Q1..Q12), `impact-map.md` §0 (findings, conflicts C1..C10), §11 (N1..N5). Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "Recommended approved" → every G2-n = Recommended.

| # | Decision | Recommended |
|---|---|---|
| G2-1 | OD-B1/UX-Q1: AI briefing Ready fully replaces the data summary; source tags only on the data summary | **Yes** |
| G2-2 | OD-B2/UX-Q4/C5: tapping the unusual-spending sentence opens transactions for the last 30 days (today: this month) — shipped-behaviour change | **Yes (30 days)** |
| G2-3 | OD-B3/UX-Q5: medium budget risk tap opens Analytics (today: does nothing) | **Yes** |
| G2-4 | OD-B4/UX-Q2: no saved-layout migration when the Insight card is retired; release note for users who hid Radar but kept Insight | **Yes** |
| G2-5 | OD-B5: "N expenses not included" counts this month only, each expense once | **Yes** |
| G2-6 | OD-B6: drop the old "spent today" line (Period Summary already shows it) — removes shipped behaviour; retargets one test | **Yes** |
| G2-7 | OD-B7: keep "Money Radar" as the Latin brand name on the Greek card | **Yes** |
| G2-8 | OD-B8: whole score hidden when monthly income is foreign without a rate → separate follow-up | **Follow-up** |
| G2-9 | N1: budget fact follows the risk tier (no 25 % probability gate), so the sentence always matches the button; chip reconciled | **Yes** |
| G2-10 | C1/C2/C3: bills sentence has no total (no mixed-currency sum); pace sentence shows the difference in home currency; amounts use the phone's currency symbol | **Yes** |
| G2-11 | C4: pace sentence tap → this month's transactions (existing precedent) | **Yes** |
| G2-12 | C6: "N expenses not included" sentence not tappable in inc 1 (no screen lists them) | **Yes** |
| G2-13 | C7/UX-Q3: do NOT pin "not included" as 3rd line; keep the spec order (it can be cut by higher facts) | **Spec order** |
| G2-14 | C8/UX-Q7: brand-new user (empty ledger) sees a "Getting started" line, not "All clear" + score (adds a digest field) | **Yes** |
| G2-15 | N3: the AI "diagnostics" text currently under the briefing is dropped from the card (privacy) | **Drop** |
| G2-16 | N4: in edit mode the card shows a static preview, not the live narrative | **Static preview** |
| G2-17 | UI fixes riding along: remove whole-card screen-reader label, wrap chips (no sideways scroll), locale-aware capitals for Greek, Greek for `home_ai_briefing_title` | **Yes** |
| G2-18 | Keep Radar's old reason fields unrendered through inc 1 (no test churn); clean up later | **Yes** |
| G2-19 | Tests (stop-and-ask): retarget `ComputeDashboardWidgetsInsightMoMTest` (5 tests incl. `:242` today-spent and `:273` band), `HomeViewModelStressTest:473`, `DashboardWidgetRenderCoverageTest:31`, `DashboardWidgetMetaContractTest:18`; never delete without listing; extend `ComputeMoneyRadarUseCaseTest` with assertions unchanged | **Approve** |
| G2-20 | Slices: S-fix `home-insight-gap` first (separate) → S1 Radar per-part availability + parity test → S2 digest + correct no-rate count → S3 card UI + strings + taps → S4 retire Insight card → Z; after ai-fixes Part B + money-correctness 1a; CDW edits after loan-installments | **Approve** |
| G2-21 | Risk T3 overall (reviewer-strict, privacy, money review); no schema change, no migration tests; Greek wording reviewed by owner | **Accept** |
