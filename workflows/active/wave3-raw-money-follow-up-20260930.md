# Raw-money follow-up

Status: CARRIED AS INHERITED DEBT. Not part of Wave 3. No baseline or allowlist change is authorized.

## Evidence

The current static-guard run `vr-20260930-205753-36cad537` reports 84 blocking
`raw_money_aggregates` findings. The guard suite remains `FAIL` with 24 of 25
guards passing; this is not a green result.

Rule breakdown:

- 5 `G-MONEY-RAW-01` — raw `sumOf { it.amount }`
- 55 `G-MONEY-RAW-02` — raw `sumOf { it.effectiveAmount }`
- 6 `G-MONEY-RAW-03` — raw `sumOf { it.normalizedAmount }`
- 4 `G-MONEY-RAW-04` — raw price-field aggregation
- 14 `G-MONEY-RAW-06` — raw `total: Double` declarations

The findings span 29 production files across analytics, forecasting, health,
receipt parsing, savings, dashboard, export, and UI surfaces. They predate and
are outside the Wave 3 implementation items; no Wave 3 diff changes their
aggregation semantics.

## Disposition

Carry the backlog as a separately planned money-architecture batch. Do not:

- add findings to a baseline;
- broaden the raw-money allowlist;
- add source comments or exceptions solely to suppress findings; or
- describe the static-guard suite as passing while this guard exits nonzero.

The remediation must be decomposed by surface. Each batch should route totals
through the existing normalized money primitives, preserve currency conversion,
rounding, partial-conversion, and unavailable-home-currency semantics, and add
focused tests before broader validation.

Wave 3 may only carry this debt if the human commit decision explicitly accepts
the inherited `raw_money_aggregates` failure and keeps it visible in the handoff.
