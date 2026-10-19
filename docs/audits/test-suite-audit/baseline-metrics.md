# Test suite audit — baseline metrics (2026-10-16)

Source: persisted results only (no new validation run).

## Inventory
| Suite | Files | Tests | Notes |
|---|---|---|---|
| Kotlin JVM unit (`app/src/test`) | 762 suites / ~785 .kt | 8,089 (277 skipped) | 227,965 LOC test vs 235,415 LOC main |
| Kotlin instrumented (`app/src/androidTest`) | 33 | 391 | connected-tests ~16 min |
| Python guard self-tests (`guard_tests` step) | ~100+ | 5,153 (28 skipped) | 736 s |

## Wall clock per validation profile (median PASS, build/validation-runs)
| Profile | Median |
|---|---|
| static-guards | 17.6 min (guard_tests 736 s = 69%; known_good_state 152 s; db_access 137 s) |
| unit-tests (full) | 11.0 min |
| app-check | 26.8 min |
| connected-tests | 16.3 min |
| targeted-unit-test | 1.9 min (mostly test compilation) |
| compile | 2.3 min |

## Kotlin unit runtime distribution (testReleaseUnitTest, 2026-10-15 full run)
- Summed 477 s. Top 10 classes = 43%, top 50 = 67%, top 100 = 80%.
- `WriteBarrierArchitectureGuardTest` alone = 98 s (20%).
- 73 cases > 1 s total 255 s; 5,432 cases < 10 ms.
- 133 files use Robolectric; only 2 use parameterized tests.
- Skips: 19 `@Ignore("Stress test ...")`, 9 bare `@Ignore`, 3 `@Ignore` for removed APIs, others.

Per-class / per-case timings: `kotlin-unit-class-times.tsv`, `kotlin-unit-case-times.tsv` (seconds, class, name).
