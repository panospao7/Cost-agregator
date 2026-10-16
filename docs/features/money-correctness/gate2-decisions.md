# money-correctness (inc 1a + 1b) — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` (OD-1, OD-2, routed findings), `ux-flows.md` (UX-Q-1..6), `impact-map.md` §0, §9, §11. Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "recommended" → every G2-n = Recommended.

| # | Decision | Recommended |
|---|---|---|
| G2-1 | OD-1 build-order cycle: money-correctness 1b creates the single fraction-digits accessor + HALF_EVEN `roundSnapshot` helper in `domain/core/money`; MEC A1's `CurrencyAmountPolicy` reuses it. Order: ai-fixes → 1a → 1b → MEC S0 → A1 → A2 (matches approved global order). Update MEC plan A1 dependency + MEC INV-12 owner text, and MEC domain-spec AE-21/AE-29/§5 (no-rate rows now `""`, not `'EUR'`) | **Option A** |
| G2-2 | OD-2 Rescue: import takes an optional home currency; the (disabled) caller passes none → every rescued row stored as "not converted". Keep method names so existing guard exemptions stay exact | **Yes** |
| G2-3 | Guard config (stop-and-ask): the Transactions "without a home value" count needs a new `@RawQuery` + one `read` row in `config/guards/db_raw_query_classification.yml` (vs reusing/renaming the assistant-only count method) | **New method + one yml row** |
| G2-4 | Count vs search wildcards (`%`, `_`): escape them only in the new home-comparable (Transactions) mode so the count matches the visible list exactly; assistant SQL untouched | **Escape in Transactions mode only** |
| G2-5 | Old raw amount-sort SQL strings: keep, documented as assistant-only (smallest diff, enum unchanged); `Expense.normalizedEffectiveAmount` delegates to the new rule owner (date-header total) | **Keep + delegate** |
| G2-6 | Currency screen shows "EUR" before your real home currency loads → treat home as Loading and always show the switch warning while unknown | **Yes** |
| G2-7 | Diagnostics: reuse the existing (unused) `MISSING_RATE` reason code with bounded metadata rather than adding a new enum value | **Reuse** |
| G2-8 | `CurrencyConverter.kt:231` also logs the currency pair on a missing rate — remove/bound it in 1b alongside the TLC log fix | **Include in 1b** |
| G2-9 | `HomeCurrencyResolution.Failed` carries raw exception text → the new typed home-currency state maps it to bounded codes in 1a (privacy) | **Yes** |
| G2-10 | UX: optional predicted count in the switch dialog (UX-Q-1); filtered-empty empty state (UX-Q-2); copy says "for now", no feature promise (UX-Q-3); add a bounded `CountUnavailable` state (UX-Q-4); amount filter banner shows the currency ("Min: EUR 15.00") | **Yes to all** |
| G2-11 | UX-Q-5 sort labels hard-coded English → Transactions owner follow-up; UX-Q-6 resolved (bounds ARE editable in `TransactionFilterSheet.kt:280-288`) | **Follow-up / resolved** |
| G2-12 | Exports: empty home-currency cells for unconverted rows also appear in the two accounting CSV exporters (accounting CSV, FreshBooks) — covered by the same release note | **Accept** |
| G2-13 | S0 pre-flight: measure the amount-sort query plan + timing and the extra count on a large synthetic database; if slow, debounce/lazy-count (no index, no schema) | **Yes** |
| G2-14 | Validation hygiene: new tests must not live in the unsharded `com.yourname.expensetracker.currency` package; the general "packages missing from test shards" gap stays on the follow-up list | **Yes** |
| G2-15 | Routed follow-ups: assistant preview count ignores min/max + dead deprecated assistant query (→ ai-fixes/SRH-7 / assistant-follow-ups, which must rebase on 1a); recurring-rule screen creates rules without currency (`ManualRecurringExpenseViewModel.kt:108-116`); CSV import "EUR" fallback (`CsvExpenseImporter.kt:238-239`); mixed-currency date-header signing | **Follow-up list** |
| G2-16 | Risk tier: 1a and 1b both T3 (reviewer-strict + architecture-guardian + privacy-security-guardian; currency warning may be a separate cheaper-reviewed slice); no schema; no new registered guard (parity + "H never interpolated" pinned by tests) | **Accept** |
