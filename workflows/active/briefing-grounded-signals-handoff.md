# Handoff: briefing-grounded-signals

Status: PLAN APPROVED — PARKED (post-1.0). DOCS-ONLY (Release 1.0 frozen; implementation post-1.0).
Feature-id: briefing-grounded-signals. Program: AI evolution roadmap item 4 (+ B2 explain-this-number, B7 period-recap narration merged per ai-evolution critique) with roadmap item 3b on-device-briefing evaluated for folding in.

## Origin
- docs/features/ai-evolution/idea-portfolio.md (A2, B2, B7), docs/features/ai-evolution/critique.md (A2 KEEP on-device first, template baseline; B2/B7 MERGE-INTO A2).
- on-device-briefing split from ai-fixes item 2 (docs/features/ai-fixes/brief.md:13, critique.md:42): foreground-only Nano on Home open; measure AICore on owner device first.
- 2026-10-16 orchestrator recommended it as the next big AI feature (owner asked). Owner: "The next big AI idea: the home-screen briefing based on your real spending patterns ... This also seems cool" → treated as GO for stage 1 brief (docs-only).

## Stages
| Stage | Artifact | Status |
|---|---|---|
| 1 Brief | docs/features/briefing-grounded-signals/brief.md (v2; v1 = brief-v1.md) | DONE v2 — merge into Money Radar (retire Insight card); facts from MoneyRadarData + RunwayResult pace + pendingCount + excluded count; AI-ready precedence kept; template independent of AI toggles; separate `home-insight-gap` bug; inc 2 Nano behind Phase B; 3b worker fallback → parked stub; B2/B7 → own briefs; owner Q1..Q10 |
| 2 Critique | docs/features/briefing-grounded-signals/critique.md | DONE — RESHAPE (duplicates Money Radar; anomaly signal mis-sourced; Q3 cloud privacy regression; Phase A wrong path + real ledger writes; 0–20% insight-slot gap found). Reshape round 1 sent to strategist (2nd reshape → owner). v1 kept as brief-v1.md |
| Gate 1 | (this handoff) | GO 2026-10-16 (owner: "Go") — Q1..Q10 as recommended |
| 3 Domain spec | docs/features/briefing-grounded-signals/domain-spec.md | DONE — INV-B1..B19, AE-B1..B13, OD-B1..B10, U1..U14; schema NONE (layout JSON in SharedPreferences, `insight` id filtered on read); findings: excludedTransactionCount over-counts → new distinct no-rate count; Radar needs per-component availability (score/badge/CTA parity test); old insight text no currency/Greek; plurals via UI renderer; inc 1 blocked on `home-insight-gap` |
| 4 UX flows | docs/features/briefing-grounded-signals/ux-flows.md | DONE — merged card layout + state tables, ~45 radar_* keys EN/EL draft (EL needs owner review), UX-Q1..Q12; nav gaps: no anomaly-alerts screen, MEDIUM risk no-op, no no-rate list, whole-card CD hides sentence nodes, chips scroll at 200%, badge uppercase not locale-aware |
| 5 Impact | docs/features/briefing-grounded-signals/impact-map.md | DONE — T3; schema NONE (prefs not backed up); slices S-fix home-insight-gap → S1 MRU components → S2 digest + distinct no-rate count → S3 UI → S4 retire Insight → Z; spec/UX conflicts C1..C10; new N1..N5 |
| Gate 2 sheet | docs/features/briefing-grounded-signals/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 6 Slice plan | workflows/active/briefing-grounded-signals-plan.md | APPROVED 2026-10-16 (Gate 3, owner "Recommended and approved"; HD-2 (a), HD-3 ES drafts) |

## Slice statuses
| Slice | Status |
|---|---|
| S0, S-fix (home-insight-gap) | planned — S-fix before ai-fixes B3a |
| S1, S2, S3, S4, Z | planned — after ai-fixes Part B + money-correctness 1a; S2 CDW after loan-installments S4a–S4d |

## Constraints
- Post-1.0; depends on ai-fixes Part B briefing items (notification-permission decoupling, hybrid gated as cloud). Not committed.
- On-device measurement needs the OWNER's device (human action) — brief to propose a measurement protocol; no app edits.

## Next action
PARKED. Next action after Release 1.0 ships: run S0 (Sonnet scout re-verify; domain-modeler + ux-flow-designer apply amendment set; validation-runner baselines), then S-fix home-insight-gap with its own handoff workflows/active/home-insight-gap-handoff.md, timed before ai-fixes B3a. Optional owner Phase A device record (no ledger writes) can arrive any time → save as docs/features/briefing-grounded-signals/nano-measurement.md. Record `home-insight-gap` (separate small bug) and `briefing-worker-on-device-fallback` (parked stub), `why-this-number`, `period-recap` in the roadmap.

## Human decisions
- 2026-10-16 Gate 1 GO. Owner (verbatim): "Go" → Q1 merge into Money Radar; Q2 retire separate Insight card (shipped-behaviour change approved); Q3 AI briefing display moves into merged card narrative (same precedence, cloud generation unchanged); Q4 template for everyone independent of AI toggles, off = hide Radar widget, labelled non-AI; Q5 standalone `home-insight-gap` fix before ai-fixes B3; Q6 localize Radar chrome + reasons EN/EL in inc 1; Q7 ≤ 3 sentences; Q8 after ai-fixes Part B + money-correctness 1a (Phase A any time, Phase B after B2d); Q9 3b folded as inc 2, worker fallback parked stub, B2/B7 separate briefs; Q10 inc 1b after 2 weeks dogfood.
- Phase A device record: optional owner action, not yet received.
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "Recommended approved" → G2-1..G2-21 recommended: AI Ready replaces template; anomaly tap 30-day window; MEDIUM → Analytics; no layout migration + release note; unchecked = this month distinct; drop today-spent; "Money Radar" Latin in EL; income-FX score follow-up; budget fact follows tier (no p gate); no bills total, pace difference in H, symbol formatter; pace tap → transactions month; unchecked non-tappable; spec order (no pin); NoData "Getting started"; drop diagnostics text; edit-mode static preview; a11y/chip/locale fixes + EL AI title; keep topReasons unrendered; APPROVED retargets (InsightMoMTest 5 tests incl :242/:273, HomeViewModelStressTest:473, DashboardWidgetRenderCoverageTest:31, DashboardWidgetMetaContractTest:18); slices S-fix → S1 → S2 → S3 → S4 → Z; T3.
