# assistant-follow-ups (increment 1) — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` (OD-F1..F4, RF-F1..F9), `ux-flows.md` (UX-Q1..Q7), `impact-map.md` §0, §6. Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "Recommended approved" → every G2-n = Recommended.

| # | Decision | Recommended |
|---|---|---|
| G2-1 | OD-F1: "largest/smallest" with no matching rows → typed empty card ("No matching transactions"); all rows excluded for missing exchange rate → its own "missing exchange rate" empty variant (split) | **Yes** |
| G2-2 | OD-F2: chip turns stored as a new message kind FOLLOW_UP (kept out of AI prompts; older app versions would show them as errors after a downgrade — release-note line) | **Yes** |
| G2-3 | OD-F3: "Previous {period}" on "this month/quarter/year so far" → the previous FULL unit (matches typing "last month") | **Yes** |
| G2-4 | OD-F4: typed "compare" questions also show "Comparison unavailable: multiple currencies" when currencies are mixed (one rule; release note) | **Yes** |
| G2-5 | UX-Q1 / RF-F4: drop the "Show transactions" chip — the answer card's existing "Open transactions" button stays on every answer | **Yes** |
| G2-6 | UX-Q2: restored follow-up rows show as the muted "↳ follow-up" line, not as user bubbles (amend INV-F16) | **Yes** |
| G2-7 | UX-Q3: category breakdown with only uncategorized rows shows "No categorized transactions in this period" | **Yes** |
| G2-8 | UX-Q6: after re-enabling the assistant, show the "Context cleared" note once | **Yes** |
| G2-9 | UX-Q7: existing assistant screen text has no Greek → ship new Greek strings anyway (mixed-language sheet) + release note; full Greek backfill on the l10n track | **Ship + note** |
| G2-10 | UX-Q5 (pre-existing): Summary supporting text is shown twice on the answer card → small separate fix on the follow-up list | **Follow-up** |
| G2-11 | E5 guard: new code never imports the deprecated period type — helpers live in the allowlisted models file, resolver uses plain start/end values; no guard allowlist change | **Yes** |
| G2-12 | Tests (stop-and-ask): retarget `ExecuteFinancialQueryUseCaseTest` "previous period supporting text" (:90-131) to the typed comparison (2nd retarget after Part C); retarget Part C AE-C9 / AE-C27 (no-rows) / AE-C12 to the typed empty/comparison results; Part C C-4 tests must stay green unchanged | **Approve** |
| G2-13 | New automated checks: (a) no "N/A" literal in the assistant executor; (b) session-context / chip types never referenced from data layer, input builders or interpretation services; "chip never calls the AI interpreter" proven by a VM test instead of a source scan | **Approve** |
| G2-14 | RF-F2/F3 drilldown parity out of scope for inc 1 ("Open transactions" may show a broader list for multi-category/merchant answers — same as today); MIN drilldown enablement belongs to ai-fixes Part C C-5; multi-filter drilldown → money-correctness follow-up | **Out of scope** |
| G2-15 | Home-currency watching uses money-correctness's typed observable (`observeReadHomeCurrency`): "loading" never clears context; a first-run default becoming explicit with the same code is not a change. Dependency: money-correctness 1a-3a lands first (it's behaviour-neutral) — no silent fallback to the weaker existing flow | **Yes (wait for 1a-3a)** |
| G2-16 | Design seams: separate `FollowUpDiagnosticCode` enum (never persisted); a thin use case combining category/home-currency/settings observation (fail-closed) instead of VM-direct repositories | **Yes** |
| G2-17 | Slice order F1 (pure domain) → F2 (empty + comparison; ships value alone) → F3 (FOLLOW_UP kind + builder; behaviour-neutral) → F4 (VM context + chips) → F5 (UI + strings); after ai-fixes Part C + money-correctness 1a-3a | **Approve** |
| G2-18 | Risk tier T3 overall (F2, F3, F4 T3; F1, F5 T2); reviewer-strict + privacy-security-guardian on F2–F4; no schema change, no migration tests | **Accept** |
