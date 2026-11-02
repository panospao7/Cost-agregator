# Feature plan: uf-quick-fixes

Status: **APPROVED with amendments A-1..A-8 (owner "I approve", plan gate 2026-10-26), NOT STARTABLE (owner: planning only).** feature-planner wrote it (read-only, bounded run) and the orchestrator persisted it.

- **Tier:** T2 overall. QF-1 is T2; QF-2 and QF-3 are T1.
- **Sources:** `docs/features/uf-quick-fixes/impact-map-qf1.md`, `-qf2.md`, `-qf3.md`, and `decision-sheet.md`. The "Owner answers (2026-10-26)" section is binding.
- There is no domain-spec. The owner decision IDs (D-*) serve as the invariants.
- **Path prefixes:** `M/` = `app/src/main/java/com/yourname/expensetracker/`, `T/` = `app/src/test/java/com/yourname/expensetracker/`, `R/` = `app/src/main/res/`.
- Line numbers come from the maps, which were taken at commit 044be2c8. **The amendments below override them.**

## Orchestrator amendments (2026-10-26, from git facts; these take precedence over the planner text)

**A-1. Base changed.** Since the maps were taken, `integration/ai-evolution-1a` (e6967db1, worktree `Cost-agregator-int`) has absorbed money-correctness, money-aggregates, ai-fixes (through Z2/B3b) and app-lock 1a (through 25096c65, including **S10 569ef5c0**).
- Cut the Lane B worktree from **integration/ai-evolution-1a @ e6967db1**, not 044be2c8. Cutting from 044be2c8 would conflict with all of that work.
- Within the QF write sets, these files changed between 044be2c8 and e6967db1:
  - `MainActivity.kt`: S10 replaced the `handleIntent` body with `DeepLinkApplier`.
  - `WarrantyTrackerScreen.kt`: +137 lines (ai-fixes Z2).
  - `NavigationController.kt`: 4 lines.
  - `PrivacySettingsScreen.kt`: 3 lines.
  - `ManualExpenseRepository.kt`: 17 lines.
  - `strings.xml`.
  - `NAVIGATION_ARCHITECTURE.md`.
  - `DeepLinkParserTest.kt`.
- These files are unchanged: the negotiation engine/VM/screen, `DashboardFollowThroughEngine(Test)`, `HomeScreen.kt`, `MoneyRadarWidget.kt`, `CategoryBreakdownSheet.kt`, `SubscriptionManagementScreen.kt`, `emptystate/`, `DeferredFeatureGate.kt`, `FeatureConfig.kt`, `MultiCurrencyRepository.kt`.
- A re-anchor scout is checking the drifted line refs at e6967db1. Its results go in A-5.

**A-2. "Merge before AL S10" is void.** S10 has already landed on integration. QF-2d edits only the screen-routing `when`, which is disjoint from the `DeepLinkApplier` delegation. Plan risk 1 is resolved.

**A-3. The deep-link interim (Lane C) is SUPERSEDED by AL S10.** S10 already ships:
- `MarkDashboardBriefingOpenedUseCase`, with the equality-to-last-delivered write rule (D-DL2) and the CE rethrow;
- `DeepLinkPolicy`, `DeepLinkApplier`, and `DeepLinkDiagnosticsSentinelTest`.

It also closes advisory C4. See `workflows/active/deeplink-diag-hardening-plan.md` (marked SUPERSEDED). Lane C is not needed for this program. UF-62 (the stale briefing display) stays on the backlog.

**A-4. D-SB correction, which supersedes the planner's QF-2b "keep strings" and its risk 3.**
- `empty_action_scan_bank_title` and `empty_action_scan_bank_desc` are used ONLY at `DefaultEmptyStateRegistryInitializer.kt:82-83`. Keeping them after the action is removed creates UnusedResources, which policy says must be fixed in source and never baselined.
- Fix: delete both strings (EN plus any el/es copies) in the same commit, the same way as D-BETA.
- MissingTranslation can only go down, which the policy allows.
- `lint-baseline.xml` is never hand-edited; any stale entries stay, as with `label_beta`.
- So QF-2b gains `R/values*/strings.xml` (scan_bank keys) in its write set, and Batch 2 lint covers it.

**A-5. Re-anchor results at e6967db1** (scout on `Cost-agregator-int`, 2026-10-26). Every targeted defect is still present.
Updated refs follow; the implementer still re-checks them at edit time.

| Item | Current location at e6967db1 | Note |
|---|---|---|
| UF-03 Add manually | `WarrantyTrackerScreen.kt:329`: `AddWarranty -> { /* handled by primary button */ }` | No-op. The FAB at :121 already sets `showManualAddDialog = true`, so reuse it. |
| UF-03 Scan receipt | `MainActivity.kt:691-695` never passes `onNavigateToScanReceipt`; the param default `{}` is at `WarrantyTrackerScreen.kt:72` | The fix is in MainActivity: pass `{ navigation.navigateTo(NavigationDestination.ScanReceipt) }`. The old :609-611 ref is void. |
| UF-06 NL route | `MainActivity.kt:707-715`, `SmartSearch` branch: `navigation.navigateToTab(1)`, id unused | Replaces old L773-796. |
| UF-06 pattern | `activeTransactionFilter` declared at MA:320; Home sets it at MA:551-554; highlight consumer at MA:578 reads `Transactions.initialExpenseId` | Use the filter plus `navigateTo(Transactions(initialExpenseId = id))`. |
| UF-06 date | `NaturalLanguageSearchScreen.kt:51/139/194/264`; `searchResult.expense.date` (epoch ms) is in scope (:398-399) | No DAO read needed. Plan risk 6 is resolved. |
| handleIntent | `MainActivity.kt:191-197` delegates to `deepLinkApplier.apply(...)` | Disjoint from the QF-2d hunks. |
| UF-20 restore | `NavigationController.kt:213` (unchanged) | Deferred-token comment at ~:217. |
| UF-13 | `PrivacySettingsScreen.kt:48-57` (TODO :55-56) | Still stale. The KDoc should also mention `AppLockPrivacySection()` at :144. |
| UF-10 doc | `NAVIGATION_ARCHITECTURE.md:73` | Was :72. |
| Unchanged | `DashboardFollowThroughEngine.kt:150`, `HomeScreen.kt:909-914`, `DeferredFeatureGate.kt:21/24-28/36-39` | Exact. |

**A-6. QF-3c is UNBLOCKED.** `feature/money-correctness` (681d49a3, 1a-G passed) is an ancestor of e6967db1 (git-verified). The exporter is at `domain/export/AccountantReportPdfExporter.kt:40-42`, not `data/export/`. It is comment-only (UF-54 + UF-45) and joins Batch 2 with no extra profile. It goes right after QF-3a in writer order or at the end; the orchestrator picks the end.

**A-7. UF-22 returns to this program.** ai-fixes B3b did **not** rename the label: `strings.xml:2048 ai_section_experimental = "Phase 4A Experimental"`, used at `AiSettingsScreen.kt:313`. It is release-visible, so it joins **QF-1** as an in-place EN value edit to "Experimental features". This creates no new key and no UnusedResources, and Batch 1 lint confirms there is no MissingTranslation growth. `debug_phase_4a_rollout` (:1728) is debug-only and left alone.

**A-8. UF-47 is confirmed STALE (orchestrator, 2026-10-26, at e6967db1).**
- `ManualExpenseRepository.kt:186` calls `recurringExpenseRepository.insert(...)`.
- `RecurringExpenseRepository.kt:116-118` delegates that call to `ruleLifecycleCoordinator.get().createRule(expense)`, which is the legal path (`LEGAL_PATHS.md:296-298`).
- The TODO names the wrong coordinator, and there is no bypass.
- It stays handed off (D-MER: reword the comment in whichever slice next edits that file). It is not T3.

## Lane setup (orchestrator)
- **Worktree:** fresh `Cost-agregator-qf`, branch `feature/uf-quick-fixes`, cut from integration/ai-evolution-1a @ e6967db1 (A-1). Never the main worktree.
- **Writer:** a single serial writer, in this order: QF-3a → QF-1 → QF-2a → QF-2b → QF-2c → QF-2d → QF-3b.
  - QF-3c is a blocked stub.
  - D-MER/UF-47 is handed off to ai-fixes/MEC and is out of this plan, unless A-5 shows it already fixed.
- **Commits and pushes:** commit each slice on the lane branch as it is implemented; fixes are follow-up commits. Push after the batch gate. No merge or PR without an owner request.
- **Bans:**
  - No guard, baseline or allowlist edits, and no hand edits to `lint-baseline.xml`.
  - No test deletions.
  - No DAO access from the UI.
  - No LEGAL_PATHS change.
  - No manifest, permission or network change.
- **Validation:**
  - validation-runner only, through the serial cross-worktree queue.
  - Docs-only and test-only steps trigger no run.
  - Max 2 review and 2 debug iterations per slice, then hand back PARTIAL/BLOCKED.

## Orchestrator docs-only steps (no implementer, no validation run)
- **O-1 (D-OPP):** fix the false line at `workflows/active/opportunity-scan-handoff.md:57`. First check that no other session has the file dirty.
- **O-2 (D-MR3):** add a one-line note at S3 in `workflows/active/briefing-grounded-signals-plan.md`:
  > UF-08 (QF-2c) landed: Radar AdjustBudget → Budget for MEDIUM and HIGH, chip hidden at LOW; do not revert to MEDIUM → Analytics.

  Do this in the same window as the QF-2c commit.

## Slices

### 1. QF-3a — stale comments (UF-53, UF-13) — T1
- **Write set:**
  - `M/data/speech/AndroidSpeechInputGateway.kt` L112-113
  - `M/ui/screens/privacysettings/PrivacySettingsScreen.kt` L48-57: header KDoc and TODO only. Re-anchor per A-5.
  - Tests: none.
- **Conflicts:** `PrivacySettingsScreen` is owned later by AL S22 and CH S7/S19. The hunk is header-only.
- **Gate:** Batch 1 cheap review. **Validation:** Batch 1 compile.
- **Done when:** the diff touches only comment lines, and the text matches current behaviour.

### 2. QF-1 — release truth (UF-20a hide, UF-21, UF-56 text) — T2
- **Invariants:**
  - D-PP: hide.
  - D-PP2: no `MainActivity` change.
  - D-NEG: honest labels for everyone.
  - D-NEG2: count non-EUR skips only.
  - D-NEG3: leave the `minBy` fallback.
  - D-FT: text only.
- **Write set (source):**
  - `M/ui/navigation/DeferredFeatureGate.kt`:
    - L21: add `"price"` to `DEFERRED_FEATURE_IDS`.
    - L24-28: add `"price_protection"` to `DEFERRED_SAVE_TOKENS`.
    - L36-39: add `|| destination is NavigationDestination.PriceProtection`.
    - Update the KDoc.
  - `M/ui/navigation/NavigationController.kt`: restore `price_protection` only with `.takeIf { deferredAvailable }`. Old L213; re-anchor per A-5.
  - `M/domain/negotiation/SmartBillNegotiationEngine.kt`:
    - Add `NegotiationAnalysis(opportunities, skippedNonEurCount)` and `analyze()`.
    - Turn `analyzeNegotiationOpportunities()` into a wrapper that returns `analyze().opportunities`.
    - Count only the non-EUR skips (L42-51).
    - **Do not change the `recordNegotiationOutcome` signature**; it is pinned in `config/guards/db_ownership_policy.yml:4179-4215`.
    - Keep the CancellationException (CE) rethrow. Leave `minBy` at L124.
  - `M/ui/screens/negotiation/BillNegotiationViewModel.kt` L24-44: add `skippedNonEurCount: StateFlow<Int>`. The non-CE catch sets it to 0; a CE still propagates.
  - `M/ui/screens/negotiation/BillNegotiationScreen.kt`:
    - A static "reference prices, Greece, EUR, approximate" note near L100-117.
    - A banner when the skipped count is above 0.
    - Conditional honest empty copy at L126 / ~L700-720.
    - The baselined `bill_negotiation_empty_subtitle` stays in use on the default branch, with its value unedited.
  - `M/domain/engine/DashboardFollowThroughEngine.kt:150` → `CurrencyFormatter.formatMoney(transaction.amount, transaction.currency)`. The L73 comparison is unchanged.
  - New `R/values/strings_release_truth.xml`, `R/values-el/…`, `R/values-es/…`:
    - About 4 keys, with identical key sets in all three files.
    - Use `%1$d`, not `<plurals>`, with identical format args in every locale.
    - The implementer drafts the el/es copy.
  - Not touched: `MainActivity`, `FeatureConfig`, `HomeScreen`, `strings.xml`, `PriceProtection*`.
- **Write set (tests, test-author):**
  - `T/ui/navigation/DeferredFeatureGateTest.kt`:
    - L13: the exact ID set gains `"price"`.
    - L22-29: the token set gains `"price_protection"`.
    - Extend L32-62 to cover Price Protection.
  - `T/ui/navigation/NavigationRouteContractTest.kt` L298-316 and L333-347: add Price Protection.
  - `FeatureConfigNavigationContractTest`: edit only if a fixture needs it.
  - `DeepLinkParserTest`: edit only if it lists the deferred tokens. It changed under S10; re-anchor.
  - `T/domain/negotiation/NegotiationEngineTest.kt`: add `analyze()` cases near L747.
  - New `T/ui/screens/negotiation/BillNegotiationViewModelTest.kt`.
  - `T/domain/engine/DashboardFollowThroughEngineTest.kt` L120: pin the locale (restore it in `@After`/`finally`) or compute the expectation with `CurrencyFormatter`. Add a non-EUR case. Additive only.
- **Acceptance:**
  - **AE-1:** with the gate unavailable, Price Protection is absent from the menu, a saved `price_protection` token restores to Home, and `isDeferredDestination(PriceProtection)` is true. With the gate available, behaviour is unchanged.
  - **AE-2:** given subscriptions {EUR valid, USD, GBP, EUR with an invalid amount, an energy skip}, `skippedNonEurCount == 2`. Also, `analyze().opportunities == analyzeNegotiationOpportunities()`.
  - **AE-3:** the VM exposes the count. When the engine throws a non-CE exception, the count is 0 and the existing error path runs. A CE propagates.
  - **AE-4:** the note is always shown, the banner only when the count is above 0, and the honest copy only when there are no opportunities.
  - **AE-5:** the follow-through text for USD 250 contains no "€" and equals the `CurrencyFormatter` output. The EUR expectation is locale-pinned.
  - **AE-6:**
    - The `recordNegotiationOutcome` signature is byte-identical.
    - MissingTranslation does not grow.
    - The el/es key sets equal the EN set.
- **FG:** FG-12, FG-13, FG-10, FG-17, FG-07, FG-20.
- **Gate:**
  - Batch 1 cheap review.
  - architecture-guardian on the engine edit (FG-12/13).
  - **The owner signs off the el/es copy before merge.**
- **Conflicts:**
  - `DashboardFollowThroughEngineTest` is shared with ai-fixes B2d. Keep the hunk to L120 plus new test functions. B2d may already be on integration; re-anchor.
  - `FeatureConfig`: loan S10a's `installments` item must be deferred consistently with `DeferredFeatureGate`.

### 3. QF-2a — Category "show all" (UF-01) — T1
- **Write set:** `M/ui/components/CategoryBreakdownSheet.kt`:
  - L40-41: local `expanded` state.
  - L48: `verticalScroll`.
  - L95: the button sets `expanded` and is then hidden.
  - No new strings. The caller at `HomeScreen.kt:1061` is unchanged.
- **Acceptance:**
  - Collapsed, the sheet shows today's list and the button.
  - A tap shows all items and hides the button.
  - A long list scrolls.
  - No nested same-direction scroll.
- **Gate:** Batch 2 cheap review, plus a manual smoke check.

### 4. QF-2b — Subscription empty-state actions (UF-04) — T1
- **Write set:**
  - `M/ui/screens/subscription/SubscriptionManagementScreen.kt`:
    - L50-51: drop `onOpenNotificationSettings` and the dead `onOpenBankConnections`.
    - L141-148: the L147 branch calls `NotificationListenerSettingsLauncher.open(context)`, with `context = LocalContext.current` read at composable scope.
  - `M/ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt` L80-87: remove the `scan_bank` action.
  - **A-4:** delete `empty_action_scan_bank_title` and `empty_action_scan_bank_desc` from `R/values*/strings.xml`.
  - If a call site passes a dropped argument, remove only that named argument and report it.
- **Tests:** in `T/ui/components/emptystate/EmptyStateRegistryCompletenessTest.kt`, add a check that no action targets `BankConnections` and that `scan_bank` is absent. Additive only.
- **Acceptance:**
  - The notification-access action opens system Settings.
  - If opening fails, nothing happens: no toast, no string, no log (D-NS).
  - No action targets BankConnections.
  - Lint shows no UnusedResources.
- **FG:** FG-16, FG-12, FG-20, FG-07. **Gate:** Batch 2 cheap review.

### 5. QF-2c — Money Radar CTA (UF-08) — T1
- **Write set:**
  - `M/ui/screens/home/HomeScreen.kt` L909-914: remove the tier `if`. AdjustBudget calls `onNavigateToFeature(NavigationDestination.Budget)` for MEDIUM and HIGH.
  - `M/ui/components/dashboard/MoneyRadarWidget.kt` ~L272: no chip at LOW.
  - Do not touch the chip labels. Do not edit `ComputeMoneyRadarUseCase`.
- **Acceptance:**
  - At MEDIUM or HIGH, AdjustBudget opens Budget.
  - No chip at LOW.
  - The labels are byte-identical.
- **Depends on:** O-2, in the same window.
- **Conflicts:** BGS S3/S4 (Lane A) and CH S7/S19.

### 6. QF-2d — Warranty add and NL result tap (UF-03, UF-06) — T1
- **Write set** (old lines; re-anchor all per A-5):
  - `M/ui/screens/warranty/WarrantyTrackerScreen.kt` (old L293):
    - `AddWarranty` sets `showManualAddDialog = true`; fix the wrong comment.
    - Pass `onNavigateToScanReceipt` (old :609-611). The file changed by +137 lines in ai-fixes Z2.
  - `M/ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt` L51, L139, L194, L264: `onViewTransaction(id, expenseDateMillis)`, taking both values from the result already in memory.
  - `M/ui/MainActivity.kt`, in the NL route of the screen-routing `when` (old L773-776, L788-796):
    1. `activeTransactionFilter = TransactionFilter(dateRange = TimePeriodUtils.getDayRange(date))`
    2. `navigateTo(Transactions(initialExpenseId = id))`, mirroring HomeRoute.
    - Do not copy the deep-link "activity" handler.
    - Do not touch the `DeepLinkApplier` delegation.
- **Acceptance:**
  - In the warranty empty state, "Add manually" opens the dialog and "Scan receipt" opens scan.
  - An NL result tap opens Transactions filtered to that local day, with the row tinted.
  - No DAO read in the UI. No new strings.
- **FG:** FG-12, FG-17, FG-20, FG-07.
- **Stop-and-ask:** if the NL result lacks the date (FG-12).

### 7. QF-3b — dead code (UF-51, UF-10, UF-11 VM method) — T1
- **Write set:**
  - `M/data/repository/MultiCurrencyRepository.kt` L1115-1123: delete `updateExpenseCurrency`.
  - `M/ui/navigation/FeatureConfig.kt` L25, L35, L281-282: remove `isBeta`, `getBetaFeatures`, `getNewFeatures`.
  - `M/ui/screens/home/HomeScreen.kt` L1645, L1708, L1760-1769: remove the badge use.
  - `R/values/strings.xml`: remove `label_beta` (old L78) in the same commit.
  - `docs/architecture/NAVIGATION_ARCHITECTURE.md`: one line (old L72; it changed, so re-anchor).
  - `M/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt` L155-160: delete `executeVoiceQuery`. Remove the `launch` import only if it becomes unused.
- **Do not touch:**
  - the speech stack;
  - `lint-baseline.xml`;
  - the `CancellationSafetyArchitectureGuardTest.kt:146` allowlist;
  - `config/baselines/cancellation.json`.
- **Tests:** none. If any test references a removed symbol, stop and ask.
- **Acceptance:**
  - No references remain.
  - No UnusedResources finding and no MissingTranslation growth.
  - The voice gateway and engine are unchanged.

### 8. QF-3c — BLOCKED stub (UF-54 and the UF-45 TODO rewrite in `AccountantReportPdfExporter.kt` L40-42, plus the business-flags comment)
- Blocked until MC 1a-G. A-5 checks whether that gate is now done.

## Validation batches (validation-runner, serial, from the Lane B worktree root)

**Batch 1 (QF-3a + QF-1):**
1. `compile`
2. `targeted-unit-test`: `*DeferredFeatureGateTest* *FeatureConfigNavigationContractTest* *NavigationRouteContractTest* *DeepLinkParserTest* *DestinationPersistencePolicyTest* *NegotiationEngineTest* *BillNegotiationViewModelTest* *DashboardFollowThroughEngineTest* *CancellationSafetyArchitectureGuardTest*`
3. `lint` (new strings; MissingTranslation must not grow)

**Batch 2 (QF-2a-d + QF-3b):**
1. `compile`
2. `targeted-unit-test`: `*EmptyStateRegistryCompletenessTest* *ContextualActionRegistryTest* *NotificationListenerSettingsLauncherTest* *ComputeMoneyRadarUseCaseTest* *FeatureConfig* *DeferredFeatureGate* *NaturalLanguageSearch* *CancellationSafetyArchitectureGuardTest* *MultiCurrency*`
3. `lint` (removes `label_beta` and the scan_bank strings)
4. `static-guards`, once on the lane tip. This also covers FG-13 for the QF-1 engine edit and FG-12/17 for MainActivity.

No shard, no app-check, no migration run.

## Status
| Step | Items | Tier | Batch | Owner | State |
|---|---|---|---|---|---|
| O-1 | D-OPP | docs | none | orchestrator | planned |
| QF-3a | UF-53, UF-13 | T1 | 1 | implementer | planned |
| QF-1 | UF-20a, UF-21, UF-56, UF-22 (A-7) | T2 | 1 | implementer + test-author | planned (el/es sign-off before merge) |
| QF-2a | UF-01 | T1 | 2 | implementer | planned |
| QF-2b | UF-04 | T1 | 2 | implementer + test-author | planned |
| O-2 | D-MR3 | docs | none | orchestrator | planned (with QF-2c) |
| QF-2c | UF-08 | T1 | 2 | implementer | planned |
| QF-2d | UF-03, UF-06 | T1 | 2 | implementer | planned |
| QF-3b | UF-51, UF-10, UF-11 | T1 | 2 | implementer | planned |
| QF-3c | UF-45, UF-54 | T1 | 2 | implementer | planned (unblocked, A-6) |
| (handed off) | UF-47 / D-MER | none | none | ai-fixes / MEC | out of plan |

## Open risks
1. ~~AL S10 timing~~: resolved (A-2).
2. ~~Lane C MainActivity concurrency~~: resolved (A-3, Lane C dropped).
3. ~~Kept scan_bank strings trip UnusedResources~~: resolved by deleting them (A-4).
4. FG-13 is confirmed only by static-guards at the end of Batch 2, after QF-1 is committed. Any failure is fixed on the lane before merge.
5. There are no Compose tests, so a nested-scroll crash (QF-2a) or `LocalContext` misuse (QF-2b) would not be caught automatically. These rely on review plus a manual smoke check. Optionally, append QF-2 items to `build/release-lead-notes/owner-manual-smoke-checklist.md`.
6. UF-06 assumes the NL result already carries the date. Otherwise, stop.
7. The MissingTranslation counts differ (2223 baseline entries vs a cap of 2219). The rule is no growth, and lint is the source of truth.
8. After UF-11, the `NaturalLanguageSearchViewModel` entry in the CancellationSafety allowlist may be stale. Leave it: removing it is a guard edit and belongs in a follow-up.
9. The locale pin in `DashboardFollowThroughEngineTest` must restore the default locale afterwards.
10. Residual: `ReceiptSideEffectPlanner.kt:541` still calls `findBetterDeals`, discards the result, and nothing shows it.

## Deferred
- UF-20(b) strip, which needs test deletions.
- The UF-56 raw-amount comparison (money sweep, T3).
- D-NEG3.
- Hiding negotiation for non-EUR users.
- The UF-11 speech stack.
- UF-47.
- QF-3c.
- Transaction detail view (Track C).
- UF-62.

## Human decisions
- **Plan gate:** approve this plan with amendments A-1 to A-4.
- **Before merge:** the owner signs off the Greek and Spanish copy in `strings_release_truth.xml`.
- **Stop-and-ask triggers:**
  - Risk 6 (the NL result lacks the date).
  - Any test that references a QF-3b symbol.
  - Any guard or baseline edit.

## Re-anchor amendments (2026-11-02, orchestrator; take precedence over the earlier text)
The owner lifted "planning only" on 2026-11-02 ("start this work; I leave organising to you"). The plan is re-anchored at origin/feature/ai-evolution `a5d659de` by an explorer (Sonnet, read-only). Full report: `docs/features/uf-quick-fixes/reanchor-20261102.md`.

- **A-9 (base):** worktree `Cost-agregator-qf`, branch `feature/uf-quick-fixes`, cut from `a5d659de`, not e6967db1. Since the original base, MEC 1b, loan S13, release 1.0.3, nl-quick-add inc 1 and app-lock inc 1-2 have merged. Prerequisites are satisfied: AL S10 and MC 1a (unblocks QF-3c).
- **A-10 (fix-1.0.4 lane):** `feature/fix-1.0.4-review-findings` (build/fix-wt) is owned by another session and is unmerged. Its D3 (S4) gates price, price_protection, carbon and negotiation on the same lines as QF-1 UF-20a:
  - `DeferredFeatureGate.kt`;
  - `NavigationController.kt:227`;
  - the MainActivity render branches at ~:806-830;
  - the gate and route tests.

  Consequences:
  - **QF-1 is RESCOPED:** the UF-20a gate and token-restore parts are SUPERSEDED by fix104 D3. QF-1 keeps UF-56 (`DashboardFollowThroughEngine.kt:150`) and UF-22 (`ai_section_experimental` `strings.xml:2156`, `AiSettingsScreen.kt:313`).
  - UF-21 becomes debug-only once negotiation is gated. **Owner call:** park it until negotiation is un-gated (recommended), or drop it.
  - **QF-1 and QF-2d wait** until fix104 merges into feature/ai-evolution. QF-2d sits next to the fix104 MainActivity hunk, so a textual conflict is expected.
- **A-11 (batches):**
  - **Batch A, startable now:** QF-3a → QF-2a → QF-2b → QF-2c → QF-3b → QF-3c. They are disjoint from fix104.
  - **Batch B, after fix104 merges:** rebase or merge the integration tip, then QF-2d → QF-1 (rescoped).
  - Validation:
    - Batch A: compile, targeted (the Batch 2 filter, minus the QF-1 classes), lint, then static-guards once on the lane tip.
    - Batch B: the Batch 1 filter plus the NL/MainActivity classes, lint, then static-guards.
- **A-12 (corrected anchors):**
  - QF-3a: `AndroidSpeechInputGateway.kt:112-113`; `PrivacySettingsScreen.kt:48-57`, `AppLockPrivacySection()` :145.
  - QF-2a: `CategoryBreakdownSheet.kt:40-41`, onClick :95; caller `HomeScreen.kt:1061`.
  - QF-2b: `SubscriptionManagementScreen.kt:50-51`, `when` :140-151; `DefaultEmptyStateRegistryInitializer.kt:82-83`; scan_bank strings `strings.xml:1123-1124`.
  - QF-2c: `HomeScreen.kt:909-914`; chip `MoneyRadarWidget.kt:270-285` (onClick :283).
  - QF-2d: `WarrantyTrackerScreen.kt:329/:72`; `MainActivity.kt:814-819`, SmartSearch :830-838, `activeTransactionFilter` :369, :624-627, :653.
  - QF-3b:
    - `MultiCurrencyRepository.kt:1115`;
    - `NaturalLanguageSearchViewModel.kt:155`;
    - `HomeScreen.kt:1645/1708/1760-1764`;
    - `NAVIGATION_ARCHITECTURE.md:73`;
    - `FeatureConfig.kt` isBeta :35, getNewFeatures :286, getBetaFeatures :293;
    - `label_beta` `strings.xml:82`.
  - QF-3c: `AccountantReportPdfExporter.kt:40-42`. `SourceSection.total` is already a MoneyAggregate, so re-read before rewriting the UF-45 text.
- **A-13 (review findings):**
  - J-19-1 (HIGH, from the whole-app review: the subscription empty state hides detected candidates) is in the same `when` as QF-2b. It is **not folded in** (scope growth); QF-2b does not touch the candidate condition. J-19-1 stays with the review-findings backlog.
  - P-05-5 and E-01-5 relate to QF-2c but are on different lines; not folded in.
- **Deep-link plan:** `deeplink-diag-hardening-plan.md` DL-1/DL-2 are SUPERSEDED by app-lock S21: `MainActivity.handleIntent` :239 now uses `DeepLinkPolicy.evaluate`, then `AppLockDeepLinkRouter`. UF-62 stays in the backlog.
- **A-14 (2026-11-02 12:00Z, orchestrator, under owner item 101): Batch B unblocked before the fix104 merge.**
  - Lane fast-forwarded to origin/feature/ai-evolution `81234d08` (Batch A + SGD + sfa + RSV already merged).
  - `git merge-tree 81234d08 03d519a8` (fix104 lane tip): MainActivity auto-merges; the only conflicts are el/es `strings.xml` (end-of-file appends), which the fix104 lane has to resolve against 81234d08 regardless of QF. QF-2d edits the `SmartSearch` / `WarrantyTracker` branches of the routing `when`, not the CarbonFootprint/PriceProtection/BillNegotiation branches fix104 gates.
  - So the A-10 wait is lifted for: **QF-2d** (as planned) and **QF-1 rescoped** = UF-56 (`DashboardFollowThroughEngine` money text + test) and UF-22 (EN value edit of `ai_section_experimental` → "Experimental features"). No `strings_release_truth.xml`, no negotiation, no DeferredFeatureGate/NavigationController edits (superseded by fix104 D3).
  - **UF-21 PARKED** until negotiation is un-gated (the recommended option of the A-10 owner call; item 101).
  - Batch B validation: targeted `*DashboardFollowThroughEngineTest* *NaturalLanguageSearch* *MainScreenCallSiteTest* *Warranty*Screen* *CancellationSafetyArchitectureGuardTest*`, lint, static-guards once on the lane tip.
