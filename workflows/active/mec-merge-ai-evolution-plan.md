# Merge plan: origin/feature/ai-evolution (4add2466) into feature/manual-entry-currency (3d17c4be)

Merge-base: bcd8e921. T3. The feature-planner wrote this plan on 2026-10-28; the orchestrator persisted it. It is BINDING.

Decisions:
- D-M1 = A: apply BILL presentation to installment reminders, a privacy strengthening (owner-approvals 79).
- Q1 (retiring the 3 enabled-path parseDeepLink tests): RESOLVED, owner-approvals 81a (2026-10-28); restated as S13-a acceptance examples in loan-installments-handoff.md.

## Room
- int is at v151 (AppDatabase.kt:33). mec is at v152 (152.json, loan S2). Only mec bumped the version, so there is no STOP.
- Migration tests still run. 152.json must stay byte-identical.

## Per-file resolution
Anchors refer to the trial-merge marker files.
1. **ManualExpenseRepository.kt :15-22.** MECHANICAL, union of imports: the 4 MEC currency imports plus AppConfig.
2. **DiagnosticReasonCode.kt :82-140.** MECHANICAL, union.
   - MANUAL_SAVE_BLOCKED_NO_RATE first (with a comma), then int's NOTIF_/DL_/LOCK_ block.
   - Nothing pins order or count, and there is no allowlist.
3. **BillReminderWorker.kt :59-64.** SEMANTIC.
   - Constructor: `..., timeProvider, presentationResolver, presentationApplier, billReminderManager`.
   - Keep: the guard block, the collapse try, the installment try (rethrows CE and WorkerCheckpointBlockedException), the NonCancellable release paths, and the recurring resolve/sendNotification(decision) path.
   - D-M1 A:
     - In the claim→post try, after buildInstallmentReminderText, add `val decision = presentationResolver.resolve(NotificationKind.BILL, Build.VERSION.SDK_INT)`.
     - Change the call to `sendInstallmentNotification(id, title, body, decision)`.
     - Inside it: ensureChannelExists() stays inside its try, then the builder, then `presentationApplier.apply(builder, decision, CHANNEL_ID, SMALL_ICON, detailedTitle = title, detailedText = body, actions = emptyList()).build()`.
     - The catch ladder is unchanged.
4. **MainActivity.kt :240-337.** SEMANTIC, take THEIRS for the hunk: DeepLinkPolicy.evaluate, then the NotADeepLink early return, then deepLinkRouter.route.
   - Drop mec's pre-1a when(data.host) block, including the S10a installment branches.
   - Keep the InstallmentsFeature and installment screen imports that MainScreen uses.
   - Installment hosts now go to Rejected(UNKNOWN_HOST) and then Home, which is the dormant behaviour. Loan S13-a owns the DeepLinkHost, policy and applier branches.
5. **DeepLinkParser.kt.** Take THEIRS for the whole file.
   - Fix the InstallmentsFeature.kt:10 KDoc: "DeepLinkPolicy rejects these hosts (UNKNOWN_HOST) until loan S13-a".
6. **values-el and values-es strings.xml.** MECHANICAL, union: the HEAD block (loan) first, then int's block. Check for duplicate name= values.
7. **DbGuardPolicyFixtureTest.kt.** Union, 454 entries.
   - The comment carries both chains: loan S3 +2, S6a +13, S6b +7; ai-fixes B1a −1+2, Z2 +1.
8. **BillReminderWorkerTest and BillReminderWorkerTimeProviderTest.** Union.
   - Constructor args: `..., timeProvider, presentationResolver, NotificationPresentationApplier(context, NotificationPresentationTexts(context)), billReminderManager`.
9. **CODEBASE_INVENTORY.md.** Union.
   - Header: merge date, Database Version v152.
   - Drift sections newest first.
   - A merge line: the BillReminderWorker 10th param; the pre-1a handleIntent dropped.
10. **ENGINE_INTERACTION_MAP.md.** Union of rows.
    - The BillReminderWorker row adds "installment reminders via BillReminderManager (S7b), BILL presentation applied (D-M1)".
11. **LEGAL_PATHS.md.** Union of the header, the INSTALLMENT PLAN DATA block, the int blocks, and both FORBIDDEN bullet sets.
    - In DEEP LINKS: "installment hosts are not in DeepLinkHost until S13-a".
12. **scripts/test_verify_db_access_boundaries.py.** Union, `assert len(entries) == 454` in both places.

## Semantic conflicts git will not show
- **S1:** DeepLinkParserTest.kt (auto-merged; mec's :74-163 S10a-1 tests).
  - Port them to DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, uri):
    - flag-false → Rejected(UNKNOWN_HOST) with fallsBackToHome;
    - the ENABLED canary stays;
    - unrelated hosts: home → Accepted(HOME), unknown → UNKNOWN_HOST, https → NotADeepLink.
  - The 3 enabled-path tests (:102-145) have no seam: Q1, OWNER.
- **S2:**
  - BillReminderWorkerNotificationPresentationTest.kt:148-158 and BillNotificationSweepParityTest.kt:138-148: append a BillReminderManager mock (`coEvery { getDueInstallmentReminders() } returns emptyList()`).
  - BillReminderWorkerInstallmentTest.kt:166-175 and :193-202: add a resolver mock (FULL) and a real applier.
  - D-M1 A: add a DISCREET installment case (no plan name or amount in title, text, public version or extras).
- **S3:** ManualExpenseRepositoryRecommendationTest.kt:113-130: add currencySettingsRepository (resolveHomeCurrency stub).
- **S4:** AddExpenseViewModel.mapSaveError hides RECURRING_FOREIGN_CURRENCY_ERROR_CODE. It fails closed. Fix it in a separate post-merge MEC commit (mec_ string, es/el DRAFT).
- **After the merge, check:**
  - db_ownership_policy.yml has 454 entries;
  - test-shards.json is valid JSON;
  - guard_registry has 27 entries (int 25 + mec 2).

## Guard pins
- These are unions, not weakenings: ownership 454 (Kotlin + 2 Python), guard_registry 27, the two DiagnosticReasonCode entries.
- These stay unchanged:
  - known_good_state _EXPECTED_GATE / 21 advisories;
  - MainScreenCallSite;
  - DeepLinkApplierCallerPin;
  - installment_cloud_reachability;
  - raw-query 4.
- Any re-pin goes to the OWNER.

## Execution (one implementer, mec worktree, no commit)
1. Clean status. Fetch; confirm origin/feature/ai-evolution == 4add2466. Run `git merge --no-ff --no-commit origin/feature/ai-evolution`.
2. Resolve in this order:
   1. DiagnosticReasonCode, then ManualExpenseRepository.
   2. DeepLinkParser (theirs), then the InstallmentsFeature KDoc.
   3. MainActivity.
   4. BillReminderWorker (+ D-M1 A).
   5. BillReminderWorker tests, then S2, then S3, then DeepLinkParserTest (Q1 pending).
   6. DbGuardPolicyFixtureTest, then test_verify_db_access_boundaries.py.
   7. Strings, then the duplicate check.
3. Docs; a merge entry in both mec handoffs.
4. Run `git diff --check`; check there are no conflict markers; count the ownership YAML; `git add`. Do NOT commit.

## Validation ladder
1. targeted:
   `*BillReminderWorker*,*BillNotificationSweepParity*,*BillReminderManager*,*ManualExpenseRepository*,*DeepLink*,*AppLockDeepLinkRouter*,*MainScreenCallSite*,*AppLockShell*,*UnsupportedDatabase*,*DbGuardPolicyFixture*,*GlobalDurableDiagnosticsGolden*,*PrivacyDiagnosticsEmitter*,*TransactionLifecycleCoordinator*,*HomeViewModel*,*AddExpenseViewModel*,*QuickAdd*,*InstallmentPlanArchitectureGuard*,*NotificationKind*,*NotificationPresentation*,*GsonR8KeepRulesContract*,*SourceScanningArchitectureGuard*,*BiometricPromptCopy*,*CashFlowCalculator*,*FinancialStressForecast*`
2. Migration tests (`*Migration*`, `*DatabaseSchemaPolicy*`); 152.json unchanged.
3. All six shards.
4. Guards: known_good_state, ui_dao, cancellation, db_access, db_artifact_sync, installment_cloud_reachability, spending_predicate_usage, guard_docs_truth.
5. lint, then static-guards, then app-check.

Gates: reviewer-strict, privacy-security-guardian (D-M1, deep links), architecture-guardian.

## STOP conditions
- The ownership YAML is not 454.
- The known_good_state advisory count is not 21.
- 152.json changes, or an int @Entity diff appears.
- db_artifact_sync needs anything beyond the sanctioned regeneration.
- Any guard or test needs loosening.
- There are conflicts beyond the 14 listed.

## Merge review fixes (2026-10-28; NOT validated)
- privacy-security-guardian ACCEPT-WITH-CONDITIONS and architecture-guardian ACCEPT-WITH-CONDITIONS; fixes decided under standing approval item 62.
- Applied: `presentation resolve cancellation after the claim releases the claim and propagates` in `BillReminderWorkerInstallmentTest`; `LEGAL_PATHS.md` builder owner 2 now lists `sendInstallmentNotification` plus the seventh-BILL-site INV-12 re-count note for loan S13-a; `CODEBASE_SEGMENTS.md` v152 / 2026-10-28 and the missing file entries; `config/validation/test-shards.json` assigns the 7 unassigned classes (domain-a-m: `domain.diagnostics.*`, `domain.LifecycleWritersWriteBarrierTest`; data: `di.RetentionModuleBatchedPurgeTest`).
- Q1 still OWNER-PENDING. MEC B1/B2 follow-up: quick-add Fill must use the SELECTED entry currency once B2 adds the selector (`AddExpenseViewModel.kt:551-570`); revisit `FOREIGN_CURRENCY_BLOCKED` and the `quick_add_*_foreign` copy, with a test. S4 (`mapSaveError`) is tracked.
