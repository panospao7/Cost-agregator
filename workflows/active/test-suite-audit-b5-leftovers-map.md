# B5 leftovers map + decisions (scout 2026-10-20 on HEAD 945afffc; orchestrator decisions under standing approval)

T = app/src/test/java/com/yourname/expensetracker. None of these files is referenced by known-hanging-tests.json, test-shards.json, release_block_denylist.yml, scripts or CI. Writers must run `git diff 8671b844 945afffc -- <file>` before editing (scout had no Bash) and preserve every release-added test.

## B5-4 Statistics — DO (T2 · cheap review; reviewer checks IEEE expectations are derived)
- T/domain/util/StatisticsUtilsStressTest.kt (35) → rename to StatisticsUtilsTest.kt (no collision); update docs/testing/TESTING_ASSESSMENT.md:69 name only.
- Fold B3-6: delete wall-clock budgets (:112 `duration < 100_000_000`, :357-374, :376-387); keep behavioural asserts.
- Exact table (names in messages): [1..5]→sqrt(2.5); [10,20]→sqrt(50); [1,2,3]→1.0 (N-1 proof :272); [2,4,4,4,5,5,7,9]→2.138089935299395; [0,10]; [-5,5]; 100× alternating 0/10; 1..100 (:335 formula); n=10000 1..n → sqrt(n(n+1)/12). Zero rows: empty, singleton, uniform (100×50.0, 1000×1e6, 1000×42.0). Invariants: shift +100, scale ×2, order independence.
- Vacuous :135/:167/:182 → exact IEEE results derived from StatisticsUtils.kt:12-17 (writer reasons it out; if not exactly derivable, keep as-is and report). Weak `> 0` tests subsumed by exact rows. Drop `deterministic results` (:287, Math.random).
- Expected 35 → ~12-14.

## B5-7 navigation — DO (T1 · cheap), two writer runs
- W1: (a) NavigationRouteContractTest 29→13: delete 16 roundtrip tests (:37, :48, :60, :92, :102, :123, :131, :140, :151, :165, :176, :213, :221, :231, :239, :250) after folding every unique exact-token assertion into the :305 table as (destination, exactToken) rows (analytics?period=MONTH, transactions?expenseId=123, bare transactions/analytics for null, spending_map, budget_detail, spending_challenges false, BudgetDetail(42,"Groceries")/(7,null), forTemplateEdit(88), forTemplateCreation()); trim duplicate VisualSplitEditor row (:331/:343). Keep :74, :80, :112, :190, :262, :305, :364, :373, :383, :403, :418, :436, :471. Fix the :262 all-destinations list to compare against `NavigationDestination::class.sealedSubclasses` (PrivacySettings missing) — strengthening. (b) FeatureConfigNavigationContractTest 12→8: merge :18/:24/:35/:47/:56 into one with per-feature messages; release tests :68-:114 untouched; keep :116 and :134; class name stays (NAVIGATION_ARCHITECTURE.md:80-82). (c) NavigationControllerBehaviorTest 13→11: merge canNavigateBack :146/:152/:158, all asserts kept.
- W2: (d) DeepLinkParserTest 13→~3-4: one exact table (uri → decision kind + exact destination) incl. strengthened dashboard/add/abc rows (read DeepLinkParser.kt); keep release test :86 deferred hosts; keep Robolectric. (e) DashboardWidgetRenderCoverageTest 3→1: `assertEquals(sealedSubclasses.toSet(), RENDERED_WIDGETS)` with missing/stale message. (f) T/service/NavigationTargetResolverTest 32→~21: canHandle table (:33-:91) and null/blank filter table (:133-:166) asserting exact default filter if production returns it; analytics tests (:229-:322) unchanged.

## B5-14 RecurringOccurrenceDaoTest — DO (T2 · cheap; no retention SQL here)
- 16 → ~11: all-fields read-back via getById/getByKey/getBySource (merge :88, :96, :140, :157); missing lookups (:131+:150); status update merged into :214 with exact updatedAt and untouched id2. Keep :106-:129 (release), :168, :179, :189, :225, :252; ordering :239 → exact key order. Report only: no duplicate-occurrenceKey test.

## B5-9 — PARTIAL (test strengthening only)
- Receivers: add `verify(exactly = 1) { startForegroundService(...) }` (or the exact equivalent) to the assertion-less "does not crash when service start throws" tests (BootReceiverStressTest :90, ServiceRestartReceiverStressTest :46). No rename, no merge.
- Worker merge (−3, needs RS + worker guardian) → DROPPED as not worth the review cost; recorded DONE-WITH-REMAINDER.

## B5-18 ReceiptScan — DROPPED (DONE-WITH-REMAINDER)
- Release reworked the file; tests carry unique proofs; at most −1. No rename (would collide with B3b-R doc edits).

## Validation (one batch after all writers)
targeted-unit-test: com.yourname.expensetracker.domain.util.StatisticsUtilsTest, ui.navigation.*, ui.screens.home.DashboardWidgetRenderCoverageTest, service.NavigationTargetResolverTest, data.database.dao.RecurringOccurrenceDaoTest, receiver.*; registered-guard guard_docs_truth.
