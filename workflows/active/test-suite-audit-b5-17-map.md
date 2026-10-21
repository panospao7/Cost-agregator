# B5-17 map (scout, 2026-10-18, worktree Cost-agregator-tests, read-only)

T = app/src/test/java/com/yourname/expensetracker/domain. None of the six files is modified in the worktree
(B5-18 touched only CarbonFootprint**ViewModel**Test). Current 151 @Test; target about −40 (conservative set below).
Line numbers are from the scout pass; writers re-verify them.

## Orchestrator scope decision (standing approval)
- Do the conservative set below. Hard-code every expected value (no `a * b` literals, no recomputing from the report itself).
- Fix the weak and vacuous tests that are listed. Do not just keep them. If you strengthen a weak test, read production first and pin the real value.
- Locale/zone: if a pinned string depends on number formatting, check the generator uses an explicit Locale. If it uses the default, pin `Locale.setDefault(Locale.US)` in the test with restore. Do NOT change production.
- Split into two writers (API-timeout mitigation): **W1** Negotiation + MonteCarlo + FinHealthTT; **W2** Business + Carbon + DashboardFollowThrough.

## W1
### negotiation/NegotiationEngineTest.kt (46 → ~30)
- Keep setUp `mockkStatic("androidx.room.RoomDatabaseKt")` (:45-56, hang-sensitive).
- Table A analyze billing-cycle (:168 annual Netflix 120 → monthly 10.0/raw 120.0, script contains "10.00" not "120.00" (annual row only); :197 Spotify 9.99 MONTHLY → 9.99/9.99; :222 Gym 90 QUARTERLY → 30.0/90.0).
- Outcome table (:247 SUCCESS-monthly, :509 PARTIAL-monthly, :289 SUCCESS-annual, :551 PARTIAL-annual; optionally :336 weekly ≈27.0, :371 biweekly ≈77.0). Columns: oldAmount, newPrice, savings, history amount, update amount. Pin weekly/biweekly exactly if production is deterministic, otherwise keep the tolerance.
- Table D invalid recordNegotiationOutcome (:839 :862 :885 :908 :931 :954) → isFailure, 6 rows.
- Table E invalid subscription amount (:1143 :1161 :1179 :1197; NaN/+Inf/0/−5) → empty + getRates exactly 0.
- Table F quote filtering (:1219 :1252 :1285).
- Table G skipped-by-classification (:763 DEI Energy, :784 USD Netflix, :1359 Vodafone) → empty + getRates 0. :804 can become an expected-count row.
- Keep CE tests :1003 and :1030 standalone (different paths). Also keep :74 :103 :406 :440 :1060 :488 :596 :977 :1086 :1107 :1321 standalone.
- **Weak :87 "provider failure handled gracefully":** its assertions are only inside a catch, so it passes vacuously if nothing throws. Read production to find the real behaviour. If it propagates, use assertThrows with the type and message. If it handles the failure, assert the exact result.
- :467 history: strengthen to ids/mapped fields.
### forecasting/MonteCarloSpendingSimulatorTest.kt (11 → ~7)
- Check MonteCarloSpendingSimulatorGoldenTest (4) for overlap first.
- Table daysRemaining (:82 2024-02-29 NoFit→0, :100 2024-02-28→1, :120 2024-02-15→14, :140 2023-02-28 NoFit→0) + computedAt.
- Merge :158 into the :185 unavailable-reason loop (rows of day and reason; keep verify(exactly=0) assess).
- :45: hard-code 500.0. Tighten the OR at :78 to an equality with the assessor string. Use requireNotNull.
- Keep :205 :218 :240 :256.
### health/FinancialHealthCalculatorTransactionTypeTest.kt (9 → ~6)
- Table: single non-spend type exclusion (:102 DEPOSIT 5000, :124 TRANSFER 3000, :146 WITHDRAWAL 500, :312 UNKNOWN 999). The test stays relative (each result equals the purchase-only baseline). Also pin the baseline's absolute spendingControl once, read from production.
- :337: keep bonusPoints == 5 and drop the near-vacuous range lines only if a stronger exact assertion replaces them.
- Keep :172 :202 :232 :277.

## W2
### business/BusinessExpenseReportGeneratorTest.kt (42 → ~31)
- Table totals: [p,p2]→350.0, []→0.0, [p,deposit]→100.0, [p,transfer]→100.0, [p,withdrawal]→100.0, [all five]→350.0 (:171 :179 :192 :205 :217 :229).
- Merge category :253+:275 and project :262+:298. Keep the explicit exclusion keys.
- Merge top expenses :317+:326 (keep :341).
- Merge missing receipts :359+:370 as rows. :383 is near-vacuous, so fold it in.
- CSV: fold :416 into :425 (subset). :404/:425 become a two-row table. Fold the :453 verify into :404. Keep :442.
- Mileage CSV :509/:521 → includeMileage true/false rows.
- Repo hardening: fold :654 into :749. Drop :671 (it only echoes the mock value). Merge :686+:711. Keep :790.
- :609 is vacuous (generatedAt > 0; :565 already pins generatedAt == fixedNow) → drop.
- :557 "EUR 350.00": check the formatter's Locale.
### carbon/CarbonFootprintCalculatorTest.kt (23 → ~14)
- Exact emission-factor table (:46 SHELL 50→115.0, :58 PLAISIO 100→80.0, :70 EVEREST 30→10.5, :82 SKLAVENITIS 80→20.0, :94 AEGEAN 200→100.0, :217 duplicate of SHELL → drop, UNKNOWN STORE 100 → DEFAULT factor exact value read from production (scout: 0.25 → 25.0)). Greek ΣΚΛΑΒΕΝΙΤΗΣ :264 is a category row if a category column is added.
- Report sanity :33/:106/:117/:143 → one test with the hard-coded total (scout: 325.5; confirm against production), category-sum and percent-sum checks.
- Daily average :129: hard-code (scout: 300×0.25/30 = 2.5; confirm the period length; the test uses System.currentTimeMillis() → pin the clock if needed).
- isNotNull on non-null types (:174 alternatives, :185 monthlyTrend, :195 parisAgreementGap, :206 comparisonToNationalAverage): check the types. If non-null, replace with exact expected content read from production. If that isn't feasible, drop them as vacuous and list them.
- :154 offsetCost > 0: pin it if the rate is in production.
- Keep :240 (DEPOSIT filtered, 12.5), :254, :287, :306.
### engine/DashboardFollowThroughEngineTest.kt (20 → ~15)
- Table HIGH threshold (:162 150.0 → HIGH + nav TRANSACTION_LIST; :174 50.0 → no HIGH; :185 49.99 empty percentile → no HIGH). Confirm the real empty-history threshold in production and fix the test names/labels.
- Text rows :89 (AI text) and :103 (fallback): pin the exact fallback string from production. Fold :54 into :89.
- Filter content: merge :203 (categoryId 7) + :386 (PURCHASE) + :119 (valid JSON). Make :119 really deterministic (compare two calls) or rename it. Strengthen the :221 OR predicate.
- Fold :74 (size ≤ 5) into :274. Keep :138. Keep :192 :240 :260 :306 :334 :354.
- :368 triple-OR is near-vacuous → assert no merchant-based filter.
- Hygiene: add @After Dispatchers.resetMain() if setMain is used without a reset.
