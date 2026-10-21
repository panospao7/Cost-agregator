# B6-1 map (scout 2026-10-19) + orchestrator scope decision

Files are in app/src/test/java/com/yourname/expensetracker/domain/util/ unless marked. Short names:
- VAL = TimePeriodUtilsValidationTest (77 tests)
- STR = TimePeriodUtilsStressTest (40)
- TPU = TimePeriodUtilsTest (59)
- T1 / 2A / 2B / 2C / 2D = TimePeriodUtilsT4CBatch{1,2A,2B,2C,2D}Test (25 / 20 / 19 / 28 / 36)
- ALN = metrics/TimePeriodAlignmentTest (21)

All of these pass today (domain.util.* 652 PASS in B3). The GR00-GR04 failure counts in docs/ci/GR00-GR04_validation_findings.md:950-959 are historical.

## Key facts
- VAL never pins a zone: it uses Calendar.getInstance() through createDate at :23-28. STR sets the default zone in 7 tests (:106 :133 :167 :330 :488 :721 :743) without GlobalTimeZoneTestLock. TPU :505 does setDefault(America/New_York) without the lock. ALN uses default-zone ts() at :32-37.
- T4C files already use the target pattern: `withZone(zoneId)` with GlobalTimeZoneTestLock and restore in finally (2D:109-119), plus a zone matrix.
- The TPU anchors :376/:393 are valid (week-key 2026-01, SQLite key 2024-53); keep them, along with :361 :410 :426 :456.

## Scope decision (standing approval)
- Do NOT merge or rename the T4C files. Their class names are cited in GR docs, and a rename gains little and costs doc churn. Reduce inside files only, by merging same-scenario tests into tables and only where every row keeps exact expected values. Keep standalone every DST, last-ms-exclusive, cutover (1582-10-15), extreme (Long/Int, epoch, negative, year 9999) and leap test listed in the scout report.
- VAL (−~45): delete the tests the scout lists as exact duplicates of TPU/T1/T4C. Re-check each claimed duplicate before deleting: same instant/zone semantics and same expected value. If VAL's version is the only one in a specific zone or instant, keep it as a row. Turn the remaining scenarios into explicit-zone tables (ZoneId rows, no default zone). DST 347/784/798 become one table pinned to America/New_York (23 h / 25 h), and no test may depend on the JVM zone.
- STR (−~15):
  - Move every setDefault use to withZone + GlobalTimeZoneTestLock.
  - Pin the weak or vacuous DST tests (:26 :46 :63 :78) to an explicit DST zone with exact 23 h / 25 h expectations, or fold them into the 2A rows if those are exact duplicates.
  - Drop or seed the fuzz test :633 (unseeded Random, "no exception" only). Prefer seeding it with a fixed seed and asserting an invariant.
  - Delete the exact duplicates the scout lists.
- TPU (−~15..19): replace the unlocked setDefault at :505 with withZone. Pin the helper's Calendar to an explicit zone or wrap the class in the lock. Make tables of the same-scenario groups the scout lists, keeping the anchors.
- T1 / 2A..2D: only in-file table merges of identical-shape pairs (e.g. the "migrated vs oracle" pairs, "Int extremes valid + deterministic"). They are optional, and must never touch the standalone categories.
- ALN (−~6): collapse the edge/contiguity/stress groups that duplicate TPU/STR. Keep the cross-engine tests (:62 :70 :80 :95 :267 :275 :283 :301). Pin the zone.
- Docs: do not rename classes, so no GR-doc edits. Update the COVERAGE_REPORT count line for STR only in the final docs pass (B-final).

## Writers (one at a time)
- W1: VAL, T1, TPU.
- W2: STR, 2A, 2B.
- W3: 2C, 2D, ALN.

Validation: one targeted-unit-test run with all nine FQNs (`com.yourname.expensetracker.domain.util.TimePeriodUtils*` classes + `com.yourname.expensetracker.metrics.TimePeriodAlignmentTest`), checking per-class counts.
