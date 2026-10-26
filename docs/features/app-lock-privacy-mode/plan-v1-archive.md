# Feature plan: app-lock-privacy-mode

Author: `feature-planner` (2026-10-17). Persisted verbatim by orchestrator. Planned against domain-spec AMD-1..16; see orchestrator reconciliation note at the end for AMD-17..21.


- **Risk tier:** T3 for Inc 1a, 1b and 2 (impact-map §8). Schema impact is NONE: settings, PIN and lock state are device-local or process memory, and new reason codes are stored as TEXT. So there is no Room or migration slice, `room-migration-guardian` is not needed, the `migration-tests` profile is never requested, and FG-21 does not apply.
- **Status:** plan only. This plan declares nothing accepted and contains no code.
- **Path shorthand:** `M/` = `app/src/main/java/com/yourname/expensetracker/`, `T/` = `app/src/test/java/com/yourname/expensetracker/`, `R/` = `app/src/main/res/`.
- **Binding inputs:** brief v2; domain-spec (AMD-1..16: 52 INV, 110 AE, T1-T31); gate2-decisions (DECIDED, all recommendations, including NEW-1a, OD-6a, OD-21a, OD-5a); impact-map; ux-flows (planned against the outcomes of the Gate-2 follow-up pass); handoff; AGENTS.md; CLAUDE.md; FG-IDs only.

---

## 0. Preconditions and operating rules (apply to every slice)

1. **Start gate.** No slice starts until all of these hold:
   - Release 1.0 is accepted.
   - R17, R21 and R24 have landed.
   - The feature branch is cut from the post-R24 release-line tip. S0 records the SHA. Never work on the release line.
   - Order is 1a → G1a → 1b → G1b → 2 → G2.
   - Per-slice release-batch dependencies are listed in each slice and summarised in §6.
2. **Line numbers will drift.** Citations come from the 0a9cab1c baseline, and R16..R25 will shift them. Each slice re-reads its files first. If a file no longer matches what the plan assumes (for example R21 restructured `BillReminderWorker`), stop and return to the orchestrator.
3. **Roles.**
   - Implementer (Sonnet high) writes the source write set only.
   - `test-author` writes the test write set only.
   - `validation-runner` is the only executor. Runs are serial, with one `-TestFilter` per `targeted-unit-test` run. RUNNING is polled. Missing, stale or errored results are never PASS.
   - `ci-build-debugger` diagnoses persisted logs.
   - Each slice gets at most 2 review + 2 debug iterations. If it runs out, hand back PARTIAL/BLOCKED and re-cut it at the slice's pre-identified split seam.
4. **Write sets.** Extending a write set needs orchestrator approval. Slices that may run in parallel have disjoint write sets (§5).
5. **Never:**
   - direct DAO writes, coordinator bypasses, or duplicated rules (single owners per spec §8.1);
   - weakening a guard, or adding to a baseline or allowlist;
   - destructive migrations;
   - `runBlocking` in worker code, or swallowing `CancellationException`;
   - raw values in logs or diagnostics;
   - new broad catches in workers (the `worker_allowlist.yml` symbols cover only the existing ones).
6. **Pinned tests.**
   - `GsonR8KeepRulesContractTest` keeps `MainActivity.kt` as a Gson consumer. No slice moves `PersistedVisualSplit` or `applyVisualSplitToExpense`.
   - `DeepLinkParserTest` is modified, never deleted.
   - No `@Ignore` (FG-19).
7. **Strings.** Each slice adds its own `strings_<area>.xml` in `values`, `values-el` and `values-es` (TECH-7). This avoids merge conflicts with R17/R19/R23/R24 in `strings.xml` and between slices. The pre-existing missing el/es for `privacy_*` stays out of scope.
8. **Dormancy.** The user-visible switch ships last in each increment: S12 (1a), S22 (1b), S27 (2). Every earlier slice is releasable because nothing can turn the new state on.

---

## 1. S0 — Technical decision record and preflight (paper only)

- **Goal:** settle the technical decisions the plan depends on, and refresh preconditions. No code.
- **Write set (docs):**
  - `docs/features/app-lock-privacy-mode/tech-decisions.md` (new; the orchestrator records the guardians' verdicts)
  - `docs/features/app-lock-privacy-mode/domain-spec.md` (domain-modeler bookkeeping: AMD-17 six kinds, AMD-18 meaning of AUTHENTICATING per purpose)
  - `workflows/active/app-lock-privacy-mode-handoff.md`
- **Gates:** `architecture-guardian` decides; `privacy-security-guardian` co-decides TECH-1, TECH-2 and TECH-8. No validation.

**Preflight record.**
- Post-R24 tip SHA.
- Landed or pending state of R17/R18/R19/R21/R23/R24.
- Status of C4, C5 (HZ-06 F1), C6 and C7.
- External deep-link caller check (spec 5.6; internal docs are not callers).
- ux-flows follow-up pass landed: generic public pair, RECEIPT_MATCH copy, PIN-mode turn-off copy, paused-state rows, briefing title, OD-5a release-note text.

### TECH-1 — Prompt API

| Option | For | Against |
|---|---|---|
| **A. androidx.biometric 1.1.0 (stable); `MainActivity` changes from `ComponentActivity` to `FragmentActivity`** | Maintained handling of the prompt across rotation and OEM credential screens (the riskiest 1b behaviour, AE-42/AE-47). Standard `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` mapping. `CryptoObject` for Inc 2. Matches the brief and spec. The host change is one line: `FragmentActivity` extends `ComponentActivity`, and Hilt, `setContent`, `viewModels()` and the result launchers are unaffected. | New dependency, and there is no `verification-metadata.xml`. The merge adds `USE_BIOMETRIC` plus `USE_FINGERPRINT`, so `USE_FINGERPRINT` must be stripped with `tools:node="remove"` (check the merged manifest). One-line edit in the most contended file. Fragment/activity versions need an alignment check. |
| **B. Platform `android.hardware.biometrics.BiometricPrompt`** (API 30+ only path) | No dependency, no host change, no `USE_FINGERPRINT`. | `USE_BIOMETRIC` still has to be declared explicitly (the platform prompt and `BiometricManager` need it). `CancellationSignal`/executor handling across Activity recreation is ours to write. Fewer Robolectric shadows. No OEM workarounds. |
| C. `AppCompatActivity` | — | Rejected: more surface, no gain. |
| D. Separate translucent auth Activity | — | Rejected: new component and legal path, `onPause`-only timer (spec R1); human decision. |

- **Recommendation: A.** Both A and B add `USE_BIOMETRIC` (FG-22 exact permission policy), which needs owner approval (§8).
- **If B is chosen:**
  - S15 drops the libs/build changes and the `USE_FINGERPRINT` strip, adds an explicit `USE_BIOMETRIC`, and its adapter manages `CancellationSignal`.
  - S20 drops the host change.
  - S26 uses the platform `CryptoObject`.
  - G1b adds a rotation-during-prompt check on every API lane.

### TECH-2 — `NotificationService` contract

The interface is non-suspend today (`M/domain/service/NotificationService.kt:5-86`). INV-9 needs a settings read bounded by `T_read` that can fail.

| Option | For | Against |
|---|---|---|
| **(a) Suspend, kind-aware posting methods.** The builder owner calls `NotificationPresentationResolver`, which wraps the read in `withTimeoutOrNull(T_read = 500 ms)` and rethrows `CancellationException` before any broad catch. While callers migrate (S5–S8), the legacy kind-less methods are hardened in place (PRIVATE visibility, generic public version, explicit component, FULL text, no settings read). S9 removes them. | Matches the spec literally (INV-9/AE-8: a bounded fresh read per post). One invocation point per builder owner. No `runBlocking`: I checked that all six composition sites already run in suspend contexts (`BudgetMonitor.processBudgetStatus`, `AnomalyAlertOrchestrator.checkAndAlert`, the briefing use case `invoke`, `WarrantyExpirationWorker.deliverReminder`, and the guard lambdas of `ReceiptMatchingWorker` and `BillReminderWorker`). Once legacy methods are gone, the compiler enforces "no kind-less posting". | Contract change. Nine test files change `every` to `coEvery` (all mockk), plus one hand-written fake (`NotificationIdGeneratorTest.CapturingNotificationService`). `BudgetMonitor.sendNotification` becomes suspend. Worst case adds up to 500 ms per post. |
| (b) Caller-resolved decision passed into a non-suspend service | Interface stays non-suspend. | Six sites each own an extra mandatory step. A caller can build a FULL decision itself and bypass the policy, which would need a guard 8.3(1)-style check, and guard additions are frozen (APP-1). Five more constructor changes. |
| (c) Cached snapshot: hot `StateFlow` in app scope; DISCREET until the first emission | No suspend change, no added latency. | When a worker starts a fresh process, its posts can come out DISCREET for users who have discreet off, unpredictably and hard to test. AE-8 changes meaning (never waits). Needs a warm-up hook (`MainApplication` or `AppStartupCoordinator`) and a guard so a post racing a just-committed write cannot slip past the re-post sweep. |

- **Recommendation: (a).**
- **Localized text, under every option:** the domain policy returns only mode, kind, action policy and flags. Mapping kind to `R.string` happens in the data layer (`NotificationPresentationTexts`, which receives the kind only). The domain stays resource-free, and INV-5 holds by construction.
- **If (b):** S4's resolver becomes the callers' API; S5 adds a decision parameter instead of suspend; S6/S7/S8 inject the resolver into every site; ask for a guard via APP-1.
- **If (c):** S1 adds a snapshot holder plus warm-up (write set gains `MainApplication.kt` or `AppStartupCoordinator.kt`, collision check needed); S4 reads the snapshot without a timeout; AE-8 is rewritten; S9 triggers the sweep on the snapshot edge; S5–S8 lose the `coEvery` churn.

### Other technical decisions (architecture-guardian)

- **TECH-3 — background signal (INV-25).** Recommended: count started activities via `Application.registerActivityLifecycleCallbacks`, ignoring `isChangingConfigurations`. It is registered once per process from `MainActivity.onCreate`, so `MainApplication` is not edited. This keeps the spec's IMMEDIATE semantics (AE-41). Alternative: `ProcessLifecycleOwner`, which delays ON_STOP by about 700 ms, so an IMMEDIATE background shorter than 0.7 s would not lock — a spec deviation.
- **TECH-4 — packages, chosen for shard coverage.** `domain.service` is in no shard.
  - `M/domain/privacy/applock/` (shard domain-n-z): settings, controller, ports, diagnostics emitter.
  - `M/domain/notification/presentation/` (domain-n-z): kind, decision, policy, resolver.
  - `M/domain/deeplink/`: `DeepLinkRequest` as a layer-neutral value, so the domain controller can hold it.
  - `M/data/privacy/applock/` (data): store, capability, clock, PIN store.
  - `M/data/service/` (data): applier, texts.
  - `M/ui/applock/` (runtime-ui): gate, lock surface, notices, the Activity-bound authenticator.
  - Segment ownership: Segment 11 (policy) and Segment 28 (lock/PIN), documented in S13/S23.
- **TECH-5 — bill builder.** Recommended: it stays in `BillReminderWorker`. This keeps the `SecurityException` → `permission_denied` lifecycle mapping where it is and limits the R21 collision. It applies the decision through a shared `NotificationPresentationApplier`, so both builder owners follow one rule.
- **TECH-6 — explicit component from the data layer.** Recommended: `Intent(context, MainActivity::class.java)`. No guard forbids data importing ui, and there is precedent (`data/ai/provider/DashboardBriefingPromptFormatter.kt`). Alternatives: a class-name string pinned by a test, or an injected intent-factory port.
- **TECH-7 — strings.** Recommended: one new `strings_<area>.xml` per slice (no guard or test pins `strings.xml`; I checked).
- **TECH-8 — diagnostics sink (OD-12a).** Recommended: the new rate-limited `PrivacyDiagnosticsEmitter` delegates to the existing restore-safe `M/domain/diagnostics/NotificationDiagnosticEmitter` rather than adding a second restore-routing rule. It adds only the rate limit and a metadata allowlist.
- **TECH-9 — C6 fold-in shape (only if the owner approves fold-in, §9).** `DeliveryResult` gains `PERMISSION_DENIED`, so `ReceiptMatchingWorker` keeps its `..._PERMISSION_REVOKED` diagnostic, and warranty maps it like NOT_DELIVERED (`markFailed`, retryable).
- **TECH-10 — structural tests vs the guard freeze (APP-1).** Recommended: feature-scoped source-scan tests (INV-36 isolation, the "`MainScreen(` only from the gate" check) live in feature test packages, outside `T/architecture/`, and are not registered in `scripts/ci/guard_registry.py`. The owner must confirm this is not a guard-registry addition. If not confirmed, those tests drop out of S16/S20 and wait for SG.
- **TECH-11 — parameters (spec §11), to confirm:** `T_resolve` 2 s, `T_read` 500 ms, `T_held` 5 min (monotonic), `DIAG_RATE` 1 per code per 60 s, URI/location/key limits 2048/100/32.

- **Done when:** TECH-1..11 are recorded with a guardian verdict, the preflight record is complete, AMD-17/18 are recorded, and the owner has worked through the plan-gate checklist (§8).

---

## 2. Increment 1a — notification content policy and deep-link hardening

### S1 — Device-local settings store (1a fields)
- **Goal / invariants:** INV-1, INV-2, INV-44 (1a record), INV-46; OD-11a (dedicated store).
- **Acceptance examples:**
  - AE-7 (store: `Unreadable(IO_ERROR)`)
  - AE-51 (CORRUPTED sentinel persists)
  - AE-85 (store part: atomic review-save, marker NORMAL)
  - AE-88, AE-89 (store part)
  - AE-105 (store part: typed failure, committed value kept)
  - absent-key defaults (spec 2.1)
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockPrivacySettings.kt` (new)
  - `M/domain/privacy/applock/AppLockSettingsLoadState.kt` (new)
  - `M/domain/privacy/applock/AppLockSettingsRepository.kt` (new: read, observe committed, `setDiscreetNotifications`, `reviewSave`, typed results including `Rejected(UNREADABLE)`)
  - `M/domain/privacy/applock/EffectivePrivacyValues.kt` (new: 1a `effectiveDiscreet`, fail-closed record F)
  - `M/data/privacy/applock/AppLockSettingsRepositoryImpl.kt` (new: own DataStore `app_lock_privacy_settings`, single instance, CORRUPTED sentinel mirroring `data/privacy/PrivacySettingsRepositoryImpl.kt`, enum tokens)
  - `M/di/AppLockModule.kt` (new)
- **Write set (tests):**
  - `T/data/privacy/applock/AppLockSettingsRepositoryImplTest.kt`
  - `T/data/privacy/applock/AppLockSettingsRepositoryImplCorruptionTest.kt`
  - `T/domain/privacy/applock/EffectivePrivacyValuesTest.kt`
- **Legal path preserved:** new single writer. Not Room, not in `.costbackup`. Restore, import and DB reset never touch it.
- **Depends on:** S0. Release: baseline only.
- **Guards / approvals:** FG-10, FG-16, FG-18 (new module, no allowlist entry), FG-19. No approval needed.
- **Gates:** architecture-guardian (lead: OD-11a, Segment 28), privacy-security-guardian (fail-closed mapping, D2D note OD-19), reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*AppLockSettingsRepository*`
  - `targeted-unit-test` `*EffectivePrivacyValues*`
  - `unit-test-shard` `data`
  - `unit-test-shard` `domain-n-z`
  - `registered-guard` `pii_logging`
  - `registered-guard` `di_release`
- **Done when:**
  - Unreadable never collapses to defaults.
  - Per-field writes are rejected while unreadable.
  - Review-save is one atomic write that clears the sentinel only on success.
  - Only committed values are observed.
  - Runner green and reviews passed.
- **Release state / rollback:** dormant. Revert is clean (DataStore creates no file without a write).

### S2 — Privacy diagnostics emitter and 1a reason codes
- **Goal / invariants:** INV-18 (emitter part); OD-12a.
  - Codes: NOTIF_* (5), DL_* (15), `LOCK_SETTING_PERSIST_FAILED(field)`, `LOCK_SETTINGS_UNREADABLE(cause)`, `LOCK_SETTINGS_REVIEW_SAVED`.
- **Acceptance examples:** AE-33 (rate limit plus the `DL_DIAGNOSTICS_SUPPRESSED` count); AE-21 (emitter rejects free-text metadata).
- **Write set (source):**
  - `M/domain/diagnostics/DiagnosticReasonCode.kt` (add values only)
  - `M/domain/privacy/applock/PrivacyDiagnosticsEmitter.kt` (new, `@Singleton @Inject`)
    - Metadata allowlist: enums, booleans, counts, sdkInt, exception simple class name.
    - Uses the `TimeProvider` window for the rate limit.
    - Best effort; routes through `NotificationDiagnosticEmitter` (TECH-8).
- **Write set (tests):** `T/domain/privacy/applock/PrivacyDiagnosticsEmitterTest.kt`
- **Legal path preserved:** `DiagnosticEventWriter` via the existing safe emitter. No new `PipelineDiagnosticEvent` construction site.
- **Depends on:** S0. Release: baseline.
- **Guards / approvals:**
  - FG-14, FG-16, FG-10.
  - `GlobalDurableDiagnosticsGoldenTest` is not edited (it only checks that required names exist). Any change to it needs owner approval.
  - Rollback-safe: existing readers parse codes with `runCatching`.
- **Gates:** privacy-security-guardian (lead: codes travel in exported backups), architecture-guardian (TECH-8), reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*PrivacyDiagnosticsEmitter*`
  - `targeted-unit-test` `*GlobalDurableDiagnosticsGoldenTest*`
  - shard `domain-n-z`
  - `registered-guard` `event_writers`
  - `registered-guard` `pii_logging`
- **Done when:** the limiter and allowlist are proven; no raw strings are accepted.
- **Release state / rollback:** dormant; clean revert.

### S3 — Deep-link policy (pure parser)
- **Goal / invariants:** INV-14 (parser), INV-15, INV-17, INV-21; F9, F10, F13; S5 flag (`RequireConfirmation` replaced).
- **Acceptance examples (parse level):** AE-19, AE-23, AE-25, AE-26, AE-27, AE-28, AE-29, AE-30, AE-31.
- **Write set (source):**
  - `M/ui/navigation/DeepLinkParser.kt` becomes the pure `DeepLinkPolicy`:
    - structural checks (spec 5.4), closed host enum, per-host allowlists (5.3);
    - drop unknown, duplicate and invalid parameters; ignore extras; reject non-VIEW;
    - bank-host rejection moved in, using `BankFeatureAvailability.isBankDeepLinkHost`.
  - `M/domain/deeplink/DeepLinkRequest.kt` (new)
  - `M/domain/analytics/TimePeriodTokens.kt` (new: shared period allowlist)
  - `M/ui/screens/analytics/AnalyticsScreen.kt` (`:1841-1851` private parser replaced by the shared one; same behaviour)
- **Write set (tests):**
  - `T/ui/navigation/DeepLinkParserTest.kt` (MODIFY: the `RequireConfirmation` cases at `:34-54`, `:91` are rewritten, none deleted)
  - `T/ui/navigation/DeepLinkPolicyTest.kt` (new)
  - `T/domain/analytics/TimePeriodTokensTest.kt` (new)
- **Legal path preserved:** none yet (pure). The RP-17 rule moves; it is not duplicated.
- **Depends on:** S0. Release: re-check `AnalyticsScreen.kt` for R19/R20 edits.
- **Guards / approvals:** FG-22 (JVM half of the malformed-link runtime proof), FG-16. Owner acknowledges the R11 test modification (§8).
- **Gates:** privacy-security-guardian (lead), architecture-guardian (parser consolidation), reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*DeepLink*`
  - `targeted-unit-test` `*TimePeriodTokens*`
  - shard `runtime-ui`
  - shard `domain-a-m`
- **Done when:** every host and parameter case in 5.3/5.4 is covered and no input throws.
- **Release state / rollback:** dormant (the old `parseDeepLink` had no production caller); clean revert.

### S4 — Notification presentation policy and bounded resolver (domain)
- **Goal / invariants:**
  - INV-4 (decision), INV-6 (1a terms), INV-9.
  - INV-10 (API 31+ BILL is AUTH_REQUIRED; API ≤30 is UNCHANGED in 1a).
  - INV-12 (closed registry of six kinds: BILL, ANOMALY, BUDGET, WARRANTY, BRIEFING, RECEIPT_MATCH).
  - OD-10a.
- **Acceptance examples:**
  - AE-7, AE-8, AE-9, AE-10 (resolver)
  - AE-12 (decision)
  - decision rows for AE-1..AE-6 and AE-5r
- **Assumes TECH-2 (a).** Under (b) the resolver is the callers' API; under (c) it reads a snapshot with no timeout.
- **Write set (source):** all new, in `M/domain/notification/presentation/`:
  - `NotificationKind.kt`
  - `NotificationPresentationDecision.kt`
  - `NotificationContentPolicy.kt` (pure)
  - `NotificationPresentationResolver.kt` (suspend, bounded; emits NOTIF_POLICY_* through S2)
- **Write set (tests):**
  - `T/domain/notification/presentation/NotificationContentPolicyTest.kt` (6 kinds × FULL / DISCREET / 4 unreadable causes / timeout / exception × SDK 26/28/29/30/31/34)
  - `T/domain/notification/presentation/NotificationPresentationResolverTest.kt` (virtual time, hanging fake, cancellation)
- **Legal path preserved:** new NOTIFICATION PRESENTATION step (documented in S13). Workers' when/whether logic is unchanged.
- **Depends on:** S1, S2.
- **Guards:** FG-10, FG-16.
- **Gates:** architecture-guardian (lead: TECH-2 shape), privacy-security-guardian, reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*NotificationContentPolicy*`
  - `targeted-unit-test` `*NotificationPresentationResolver*`
  - shard `domain-n-z`
  - `registered-guard` `cancellation`
- **Done when:** the table is complete; cancellation propagates; every failure gives DISCREET.
- **Release state / rollback:** dormant; clean revert.

### S5 — NotificationService contract and builder owner 1 (`AndroidNotificationService`)
- **Goal / invariants:** INV-4 (applying the decision), INV-5, INV-7 (OD-21a generic public pair), INV-8, INV-9 (service), INV-12 (texts for six kinds), INV-19.
- **Acceptance examples:**
  - AE-3 (builder: DISCREET, explicit `MainActivity`, CLEAR_TOP, FLAG_IMMUTABLE)
  - AE-32, AE-109
  - AE-13 (capture FGS untouched; diff check)
  - AE-14 (device check at G1a)
- **Assumes TECH-2 (a), TECH-5, TECH-6.** IDs are allocated as today: each new method takes the same id type its caller uses now; no RP-16 change.
- **Write set (source):**
  - `M/domain/service/NotificationService.kt` (additive suspend kind-aware methods; `DeliveryResult` unchanged unless TECH-9)
  - `M/data/service/AndroidNotificationService.kt`
    - inject resolver and applier; implement the new methods;
    - harden the legacy methods;
    - explicit-component PendingIntents at `:112, :147, :172`, keeping the data URI, CLEAR_TOP/CLEAR_TASK, request codes and FLAG_IMMUTABLE.
  - `M/data/service/NotificationPresentationApplier.kt` (new). One rule for both builder owners:
    - visibility PRIVATE;
    - a generic public version with no actions and no style;
    - discreet constants;
    - BigText only in FULL;
    - action policy UNCHANGED / AUTH_REQUIRED / NONE applied to the supplied action specs.
  - `M/data/service/NotificationPresentationTexts.kt` (new; takes the kind only)
- **Resources:** `R/values{,-el,-es}/strings_notification_presentation.xml` (new): six discreet pairs including RECEIPT_MATCH, the generic public pair, and `notif_action_snooze` (reuse `action_dismiss` if it fits). Copy comes from ux-flows §2.7/§9.4 after the follow-up pass.
- **Write set (tests):**
  - `T/data/service/AndroidNotificationServiceTest.kt` (modify)
  - `T/data/service/AndroidNotificationServicePresentationTest.kt` (new; Robolectric SDK 26/28/30/31/34; sentinel PII checks over title, text, bigText, subText, ticker, extras and the public version)
  - `T/data/service/NotificationPresentationApplierTest.kt` (new)
  - `T/data/service/NotificationPresentationTextsTest.kt` (new)
  - `T/domain/util/NotificationIdGeneratorTest.kt` (modify the fake)
- **Legal path preserved:** the worker or coordinator still decides when; posting still goes NotificationService → `AndroidNotificationService`. Presentation is inserted inside the builder owner only.
- **Depends on:** S4. Release: none (impact map rates this Low).
- **Guards / approvals:** FG-16, FG-10, FG-22 (PendingIntent explicitness), FG-20 (lint MissingTranslation). Approvals: TECH-2 (S0), and the C6 choice (§9).
- **Gates:** architecture-guardian (lead: contract), privacy-security-guardian, reviewer-strict.
- **Validation:**
  - `compile`
  - `targeted-unit-test` `*AndroidNotificationService*`
  - `targeted-unit-test` `*NotificationPresentation*`
  - `targeted-unit-test` `*NotificationIdGenerator*`
  - shards `data`, `domain-n-z`
  - `lint`
  - `registered-guard` `pii_logging`
- **Split seam:** S5a (contract, applier, texts, new methods) / S5b (legacy hardening and explicit PendingIntents).
- **Done when:** every service-built notification is PRIVATE with a generic public version and an explicit, immutable tap target; DISCREET text fields hold only constants.
- **Release state / rollback:** releasable (visible text still FULL). Revert restores implicit intents; no stored state.

### S6 — Coordinator sites pass kind (budget, anomaly, briefing)
- **Goal / invariants:** INV-4 (callers pass kind plus detail), INV-9, INV-11.
- **Acceptance examples:** AE-3 (anomaly site), AE-4, AE-6, AE-11 for these 3 sites (notify count, `DeliveryResult`, cooldown writes identical with discreet off / on / unreadable).
- **Assumes TECH-2 (a):** `BudgetMonitor.sendNotification` becomes suspend. Under (b) each class also injects the resolver.
- **Write set (source):**
  - `M/domain/budget/BudgetMonitor.kt` (`:474` → kind BUDGET)
  - `M/domain/alerts/AnomalyAlertOrchestrator.kt` (`:193` → kind ANOMALY; the C7 one-hunk fix at `:199-205` only if approved, §9)
  - `M/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` (`:44` → kind BRIEFING; the targetKey / lastDelivered logic is unchanged)
- **Write set (tests):** all modified (`coEvery`, parity):
  - `T/domain/budget/BudgetMonitorTest.kt`
  - `T/domain/budget/BudgetMonitorStressTest.kt`
  - `T/e2e/BudgetAlertPipelineTest.kt`
  - `T/domain/alerts/AnomalyAlertOrchestratorTest.kt`
  - `T/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt`
- **Legal path preserved:** thresholds, cooldowns and dedupe unchanged; the `TransactionSideEffectPlanner` post-commit path is untouched.
- **Depends on:** S5. Release: none blocking.
- **Guards:**
  - FG-07: `config/baselines/cancellation.json` counts a G-CANCEL-02 occurrence for `AnomalyAlertOrchestrator`; add no new broad catch.
  - FG-10, FG-16.
- **Gates:** reviewer-strict (lead), privacy-security-guardian, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*BudgetMonitor*`
  - `targeted-unit-test` `*AnomalyAlertOrchestrator*`
  - `targeted-unit-test` `*DeliverProactiveBriefing*`
  - `targeted-unit-test` `*BudgetAlertPipeline*`
  - shards `domain-a-m`, `integration-golden`
  - `registered-guard` `cancellation`
  - `registered-guard` `pii_logging`
- **Done when:** parity holds at all three sites.
- **Rollback:** revert falls back to the legacy methods. S9 must not be merged while S6 is reverted.

### S7 — Worker sites pass kind (warranty, receipt match)
- **Goal / invariants:** INV-4, INV-9, INV-11; NEW-1a.
- **Acceptance examples:**
  - AE-5 (WARRANTY; channel `budget_alerts` unchanged)
  - **AE-5r** (plan-derived until AMD-17): RECEIPT_MATCH; the sentinel merchant is absent; channel unchanged; id range 20000-29999
  - AE-11 for these 2 sites (notify count, `markSentFromClaimed` / `markFailed`, `addNotificationsSent`, suppression diagnostics identical)
- **Assumes TECH-2 (a).**
- **Write set (source):**
  - `M/service/warranty/WarrantyExpirationWorker.kt` (`:218` → WARRANTY)
    - `deliverReminder` keeps its parameter list `(Warranty, Int, Long, String, String)`, its name and its DAO call order.
    - Therefore there is **no `db_ownership_policy.yml` amendment (APP-2 not needed)**.
  - `M/service/receiptmatching/ReceiptMatchingWorker.kt` (`:108` → RECEIPT_MATCH)
    - `notificationPermissionChecker.areNotificationsEnabled()`, the `SecurityException` catch and `requiresNotificationPermission = false` all stay.
- **Write set (tests):**
  - `T/service/warranty/WarrantyExpirationWorkerTest.kt` (modify)
  - `T/service/receiptmatching/ReceiptMatchingWorkerTest.kt` (modify)
- **Legal path preserved:** the warranty claim lifecycle, `ReceiptLinkService` and `ReceiptMatchLifecycleService` are unchanged. Edits stay inside the `runGuardedWithContext` lambda.
- **Depends on:** S5. Release: R24 touches only `WarrantyTrackerViewModel`, so no overlap.
- **Guards:**
  - FG-11: the allowlist symbols `WarrantyExpirationWorker.daoMutation` / `.broadCatch` and `ReceiptMatchingWorker.broadCatch` cover existing catches; add none.
  - FG-13 (the `deliverReminder` rows), FG-10, FG-16.
  - The notification-worker rule in `SourceScanningArchitectureGuardTest`.
- **Gates:** reviewer-strict (lead), architecture-guardian (FG-13), privacy-security-guardian.
- **Validation:**
  - `targeted-unit-test` `*WarrantyExpirationWorker*`
  - `targeted-unit-test` `*ReceiptMatchingWorker*`
  - `targeted-unit-test` `*SourceScanningArchitectureGuardTest*`
  - `targeted-unit-test` `*WorkerGuardArchitectureGuardTest*`
  - shards `runtime-ui`, `architecture-contracts`
  - `registered-guard` `worker`, `db_access`, `cancellation`, `pii_logging`
- **Done when:** parity holds and the `db_access` guard passes without a policy change.
- **Rollback:** clean revert (the legacy methods exist until S9).

### S8 — Bill reminder worker applies the decision (builder owner 2)
- **Goal / invariants:**
  - INV-4, INV-7, INV-8, INV-9, INV-11.
  - INV-10: AUTH_REQUIRED on API 31+ for **every** user (OD-5a); unchanged on API ≤30 in 1a. NONE handling is wired ready for S14.
- **Acceptance examples:**
  - AE-1, AE-2
  - AE-7 (DISCREET; `markReminderSent` ×1; `notificationsSent` = 1)
  - AE-9 (cancellation during the read: nothing posted; the guard records it as cancelled)
  - AE-11 (bill site)
  - AE-12 (API 26/28/29/30)
- **Assumes TECH-2 (a):** the decision is resolved inside the guard lambda and passed into the non-suspend `sendNotification`. No `runBlocking`. Under (c) it reads the snapshot. TECH-5 applies.
- **Write set (source):** `M/service/reminder/BillReminderWorker.kt` only.
  - Inject resolver and applier.
  - Move the literals `"Snooze"`/`"Dismiss"` (`:259-260`) to the S5 resources.
  - Keep the claim → revalidate → send → mark/fail/cancel order, `addNotificationsSent` only after Sent and marked, and the `SecurityException` mapping (`:266`).
- **Write set (tests):**
  - `T/service/reminder/BillReminderWorkerTest.kt` (constructor only)
  - `T/service/reminder/BillReminderWorkerTimeProviderTest.kt` (constructor only)
  - `T/service/reminder/BillReminderWorkerNotificationPresentationTest.kt` (new; SDK 26/28/30/31/34)
- **Legal path preserved:** the recurring reminder lifecycle via `RecurringLifecycleCoordinator`. Snooze/Dismiss receivers and action workers, and the action PendingIntents, are untouched.
- **Depends on:** S5. Release: **R21 landed (hard; same file and test).**
- **Guards:** FG-11 (`BillReminderWorker.broadCatch`; no new catch; `WorkerGuardRequest` unchanged), FG-10, FG-07/FG-16 (`android.util.Log`: class names only).
- **Gates:** reviewer-strict (lead: worker and recurring action surface), privacy-security-guardian, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*BillReminderWorker*`
  - `targeted-unit-test` `*WorkerGuardArchitectureGuardTest*`
  - `targeted-unit-test` `*SourceScanningArchitectureGuardTest*`
  - shard `runtime-ui`
  - `registered-guard` `worker`, `cancellation`, `pii_logging`
- **Done when:** parity holds and lifecycle order is identical.
- **Release state:** **visible change for all API 31+ users.** The APP-4 release note must go out with the first release that contains S8.
- **Rollback:** clean revert; re-run the R21 tests afterwards.

### S9 — Discreet re-post sweep and write use case; remove legacy kind-less methods
- **Goal / invariants:** INV-13 (AMD-7), INV-46 (trigger only after a commit), INV-12 (no kind-less posting API left); OD-6a (scope flag S1 included).
- **Acceptance examples:** AE-15, AE-102, AE-105 (no sweep on a failed write); turning discreet off never re-posts.
- **Assumes TECH-2 (a).** Under (c) the sweep triggers on the committed snapshot edge.
- **Write set (source):**
  - `M/domain/service/NotificationService.kt`: add suspend `repostActiveAsDiscreet`; remove the legacy `send*`/`post*` methods.
  - `M/data/service/AndroidNotificationService.kt`: the sweep.
    - `getActiveNotifications()`; never `recoverBuilder`.
    - Kind by channel and id range: budget 1-9999, warranty 10000-19999, receipt 20000-29999 (all on `budget_alerts`), bill 30000-39999; anomaly and briefing by channel.
    - Reuse the posted `contentIntent` and action PendingIntents, re-applying the action policy.
    - Silent: only-alert-once and the original `when`.
    - `SecurityException` → skip and record `NOTIF_REPOST_FAILED`; no retry. `CancellationException` propagates. Record `NOTIF_REPOSTED_DISCREET(count)`. No metrics, claim/mark or cooldown changes.
  - `M/domain/privacy/applock/DiscreetRepostTrigger.kt` (new; reused by S18)
  - `M/domain/privacy/applock/UpdateDiscreetNotificationsUseCase.kt` (new)
- **Write set (tests):**
  - `T/data/service/AndroidNotificationServiceRepostTest.kt` (new)
  - `T/domain/privacy/applock/DiscreetRepostTriggerTest.kt`
  - `T/domain/privacy/applock/UpdateDiscreetNotificationsUseCaseTest.kt`
  - `T/data/service/AndroidNotificationServiceTest.kt` (legacy tests migrated, not deleted)
  - `T/domain/util/NotificationIdGeneratorTest.kt` (fake)
- **Depends on:** S5, S6, S7 (every service caller migrated before removal); S8 (bill action parity).
- **Guards:** FG-10, FG-16; the AGENTS.md notification rule (new code catches `SecurityException`).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian (contract removal).
- **Validation:**
  - `compile`
  - `targeted-unit-test` `*AndroidNotificationService*`
  - `targeted-unit-test` `*DiscreetRepostTrigger*`
  - `targeted-unit-test` `*UpdateDiscreetNotifications*`
  - `targeted-unit-test` `*NotificationIdGenerator*`
  - `targeted-unit-test` `*SourceScanningArchitectureGuardTest*`
  - shards `data`, `domain-n-z`
  - `registered-guard` `cancellation`
- **Split seam:** S9a (legacy removal) / S9b (sweep and trigger).
- **Release state / rollback:** dormant sweep; clean revert.

### S10 — Deep-link applier, MainActivity wiring, briefing-opened use case
- **Goal / invariants:** INV-14 (no inline parsing), INV-16, INV-18, INV-22; whatever is left of C4.
- **Acceptance examples:** AE-17, AE-18, AE-19 (nothing written), AE-20, AE-21, AE-22, AE-23 (no lookup), AE-24, AE-25/AE-26 (navigation), AE-27 (empty sheet), AE-29, AE-31.
- **Write set (source):**
  - `M/ui/MainActivity.kt`: `handleIntent` region `:170-251` only.
    - Route through the policy and the applier.
    - `recordInteraction("phase4_open")` gets a constant message.
    - Drop the raw-host `Timber.w`.
    - Bank rejection becomes `DL_REJECTED_BANK_HOST`.
    - The PRV-16 TODO is replaced by a pointer.
    - One-shot consume is unchanged.
  - `M/ui/navigation/DeepLinkApplier.kt` (new, `@Inject`)
    - Read-only expense lookup that degrades on not-found / barrier / exception.
    - Navigates through `MainViewModel`'s existing methods, so `MainViewModel` is unchanged.
  - `M/domain/ai/usecase/MarkDashboardBriefingOpenedUseCase.kt` (new)
    - Writes lastOpened only when the key equals lastDelivered at apply time.
    - A read failure means no write plus `DL_BRIEFING_STATE_UNREADABLE`. The existing `AiEngagementRepository` reads suffice.
- **Write set (tests):**
  - `T/domain/ai/usecase/MarkDashboardBriefingOpenedUseCaseTest.kt`
  - `T/ui/navigation/DeepLinkApplierTest.kt`
  - `T/ui/navigation/DeepLinkDiagnosticsSentinelTest.kt` (AE-21 across `AiRuntimeDiagnostics`, emitted metadata and a captured Timber tree)
- **Legal path preserved:** new DEEP LINKS path. The only persistent write goes through the use case → `AiEngagementRepository` (DataStore). The lookup goes through `ExpenseRepository`.
- **Depends on:** S2, S3. Release: **R24 and R19 landed; R18 (HZ-03 F1) status checked; C4 re-checked (adopt the release code if it landed).**
- **Guards:** FG-12, FG-16, FG-22 (runtime), the GsonR8 pin.
- **Gates:** privacy-security-guardian (lead), architecture-guardian (cross-module; new legal path), reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*MarkDashboardBriefingOpened*`
  - `targeted-unit-test` `*DeepLinkApplier*`
  - `targeted-unit-test` `*DeepLinkDiagnosticsSentinel*`
  - `targeted-unit-test` `*GsonR8KeepRulesContractTest*`
  - `targeted-unit-test` `*MainViewModel*`
  - shards `runtime-ui`, `domain-a-m`
  - `registered-guard` `ui_dao`, `pii_logging`
- **Done when:** no raw value reaches any sink and only the delivered key writes.
- **Release state / rollback:** releasable. Revert re-opens C4.

### S11 — Manifest: remove BROWSABLE (OD-3a)
- **Goal / invariants:** INV-20.
- **Acceptance examples:** AE-16 (device check at G1a).
- **Write set (source):** `app/src/main/AndroidManifest.xml`: remove `android.intent.category.BROWSABLE` (`:77`). The VIEW filter and the exported LAUNCHER stay.
- **Write set (tests):** none. Do not add a manifest guard (APP-1).
- **Depends on:** S0 caller check; S10 (ordering preference only). Release: re-check R17 ("stop if manifest changes") and C5 `<application>` edits.
- **Guards:** FG-22 (evidence at G1a).
- **Gates:** privacy-security-guardian, architecture-guardian (INV-20 owner), reviewer-strict.
- **Validation:** `compile`. Evidence is deferred to G1a (`assemble-release` + `release-artifact-evidence`).
- **Rollback:** one-line revert.

### S12 — Settings UI: "App lock & privacy" section with the discreet row (1a user switch)
- **Goal / invariants:** INV-44 (UI review card; per-field writes blocked while unreadable), INV-46 (UI shows committed state; error on failure), INV-51 (1a: the card is the only notice surface), OD-20a.
- **Acceptance examples:** AE-85, AE-88, AE-89 (UI with the 1a record), AE-105 (UI), loading shows "Checking…" and never Off, the transient retry re-reads.
- **Write set (source):**
  - `M/ui/screens/privacysettings/AppLockPrivacySection.kt` (new; 1a-only copy variant)
  - `M/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt` (new; `PrivacySettingsViewModel` untouched)
  - `M/ui/screens/privacysettings/PrivacySettingsScreen.kt` (one call-site insert)
- **Resources:** `R/values{,-el,-es}/strings_privacy_section.xml` (new)
- **Write set (tests):**
  - `T/ui/screens/privacysettings/AppLockPrivacySettingsViewModelTest.kt`
  - `T/ui/screens/privacysettings/AppLockPrivacySectionTest.kt` (`createComposeRule` under Robolectric; merged semantics)
- **Legal path preserved:** writes only through `AppLockSettingsRepository` (via the S9 use case).
- **Depends on:** S1, S6, S7, S8, S9 (every kind honours discreet and re-post exists before users can switch it on). Release: **R17 and R23 landed (screen); the R19 privacy tile landed (DD-24).**
- **Guards:** FG-12, FG-16, FG-20.
- **Gates:** privacy-security-guardian (lead: fail-closed rendering), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockPrivacy*`
  - `targeted-unit-test` `*PrivacySettings*`
  - shard `runtime-ui`
  - `lint`
  - `registered-guard` `ui_dao`
- **Done when:** an unknown state never renders as Off.
- **Release state:** 1a is live.
- **Rollback:** after release, prefer a forward fix. Reverting the UI alone leaves users who enabled discreet with no way to turn it off (privacy-safe direction). Revert before release is clean.

### S13 — Docs (1a)
- **Write set (docs):**
  - `docs/architecture/LEGAL_PATHS.md`: new NOTIFICATION PRESENTATION and DEEP LINKS sections, as implemented. In the edited Anomaly Alerting step, correct the stale `NotificationService.send()` reference (`:938`). This correction is flagged as in-section only.
  - `docs/architecture/CODEBASE_SEGMENTS.md`: Segment 11 (policy, applier, texts); Segment 28 (store, emitter); navigation (policy, applier).
  - `docs/architecture/NAVIGATION_ARCHITECTURE.md`
  - `docs/architecture/UI_REFERENCE_INDEX.md`
  - `docs/architecture/COMPREHENSIVE_UI_MAP.md`
  - `docs/architecture/CODEBASE_INVENTORY.md`
  - `pr-e-actual-release-verification-plan.md` §6.4
  - Only if scout finds a stale signature: `ARCHITECTURE.md`, `DEPENDENCY_MAP.md`, `hilt-bindings-map.md`, `COMPLETE-BACKEND-MAP.md`, `BACKEND-MAP-INDEX.md`.
  - `docs/features/app-lock-privacy-mode/release-note-1a.md` (draft for APP-4: OD-5a Snooze/Dismiss, browser links, discreet setting).
- **Depends on:** S1–S12 merged.
- **Guards:** FG-26.
- **Gates:** architecture-guardian, plus a cheap docs-accuracy reviewer. No validation.

### G1a — Increment 1a gate
- **Runner (serial):** `unit-tests` → `static-guards` → `app-check` → `assemble-release` → `release-artifact-evidence` (intent-filter / exported-component evidence).
- **Device checks (approved lanes only: `wave5-api35`, API 26 AVD; owner spare phone optional):**
  - AE-14 (hide sensitive content shows the generic public version)
  - AE-16 (browser link does nothing)
  - API 31+ auth-required Snooze/Dismiss on a locked device
  - AE-12 (API 26)
  - AE-15/AE-102 (silent re-post, original time)
  - FG-22 runtime: `adb am start` with malformed links, no crash
  - AE-32 (`dumpsys` shows the explicit component)
  - APP-3 is not needed for 1a.
- **Reviews:** reviewer-strict over the whole increment diff, architecture-guardian, privacy-security-guardian, then a fresh final reviewer. Owner: APP-4, and OD-4a ship confirmation.

---

## 3. Increment 1b — app lock and forced secure screen

**Preconditions:**
- G1a accepted.
- TECH-1 decided.
- APP-3 requested.
- S0 preflight refreshed (tip; R18/R19/R23/R24).
- AMD-18 recorded.
- UQ-2 and UQ-3 confirmed.

### S14 — Store 1b fields and policy lock terms
- **Goal / invariants:**
  - INV-2 (1b invalid-value rules)
  - INV-44 (full review record)
  - INV-6 (lock term, including inert and API-unsupported; OD-2a)
  - INV-10 (≤30: lock on or unreadable → NONE plus `NOTIF_ACTIONS_SUPPRESSED_LOCK`)
  - the settings part of `effectiveSecureScreen`
- **Acceptance examples:** AE-51 (store), AE-54 (decision), AE-64 (decision), AE-65 (decision).
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockPrivacySettings.kt`
  - `M/domain/privacy/applock/EffectivePrivacyValues.kt`
  - `M/data/privacy/applock/AppLockSettingsRepositoryImpl.kt`
  - `M/domain/notification/presentation/NotificationContentPolicy.kt`
  - `M/domain/notification/presentation/NotificationPresentationResolver.kt`
  - `BillReminderWorker` is **not** edited: the S5 applier already applies NONE, so there is no second R21-colliding edit.
- **Write set (tests):** modify `AppLockSettingsRepositoryImplTest`, `AppLockSettingsRepositoryImplCorruptionTest`, `EffectivePrivacyValuesTest`, `NotificationContentPolicyTest`, `NotificationPresentationResolverTest`.
- **Depends on:** G1a. Release: none.
- **Gates:** privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*AppLockSettingsRepository*`
  - `targeted-unit-test` `*EffectivePrivacyValues*`
  - `targeted-unit-test` `*NotificationContentPolicy*`
  - `targeted-unit-test` `*NotificationPresentationResolver*`
  - shards `data`, `domain-n-z`
- **Release state / rollback:** dormant; clean revert.

### S15 — Device-auth capability and authenticator adapter (TECH-1)
- **Goal / invariants:** INV-23 (evaluated at request time; strong biometric or device credential; never BIOMETRIC_WEAK), INV-34 (adapter: one prompt per attemptId; outcome mapping).
- **Acceptance examples:** AE-34 (UNSUPPORTED_API on API 26/28/29), AE-35, AE-93 (mapping), AE-45 (lockout mapping), AE-50.
- **Assumes TECH-1 A.** For B, see S0.
- **Write set (source):**
  - `M/domain/privacy/applock/DeviceAuthCapability.kt` (new)
  - `M/domain/privacy/applock/DeviceAuthenticator.kt` (new port)
  - `M/data/privacy/applock/AndroidDeviceAuthCapabilityProvider.kt` (new)
  - `M/ui/applock/BiometricPromptDeviceAuthenticator.kt` (new; bound to an Activity)
  - `M/di/AppLockModule.kt`
- **Build / manifest:**
  - `gradle/libs.versions.toml` and `app/build.gradle.kts` (androidx.biometric 1.1.0)
  - `app/src/main/AndroidManifest.xml`: `USE_FINGERPRINT` with `tools:node="remove"`; `USE_BIOMETRIC` arrives through the merge.
- **Write set (tests):**
  - `T/data/privacy/applock/AndroidDeviceAuthCapabilityProviderTest.kt` (Robolectric SDK 26/28/29/30/31/34)
  - `T/ui/applock/BiometricPromptOutcomeMappingTest.kt`
- **Depends on:** G1a. Release: re-check manifest edits from R17 and C5.
- **Guards / approvals:** FG-22 (exact permission policy), FG-20, FG-18. **Owner approval required:** the new permission and the new dependency.
- **Gates:** privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
- **Validation:**
  - `compile`
  - `assemble-debug` (the reviewer then reads the merged manifest read-only)
  - `targeted-unit-test` `*AndroidDeviceAuthCapability*`
  - `targeted-unit-test` `*BiometricPromptOutcomeMapping*`
  - shards `data`, `runtime-ui`
- **Release state:** dormant. Recommendation: do not ship S15 in a public build before S22, because the new permission would appear with no feature behind it.
- **Rollback:** revert the dependency and the manifest line together.

### S16 — AppLockController core: resolution, lock/unlock, timer, inert escapes
- **Goal / invariants:**
  - INV-24, INV-25, INV-28, INV-32, INV-33, INV-34, INV-36, INV-37
  - T1–T16 and T6b
  - the illegal transitions in spec 3.3 that apply to these
  - the timer of the fail-closed record F
- **Acceptance examples:**
  - AE-38 (controller: `LOCK_SHOWN_COLD_START`, no Room)
  - AE-40, AE-41 (semantics), AE-43
  - AE-44, AE-45, AE-46, AE-47
  - AE-48, AE-49, AE-50, AE-51, AE-52, AE-53, AE-54
  - AE-61, AE-63, AE-68 (isolation), AE-90
- **Assumes TECH-3.** Events come from the S20 binder; the controller uses ports only.
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt` (new process singleton; the only writer of lock state)
  - `M/domain/privacy/applock/AppLockState.kt` (new)
  - `M/domain/privacy/applock/LockNotice.kt` (new; type only)
  - `M/domain/privacy/applock/MonotonicClock.kt` (new port)
  - `M/data/privacy/applock/SystemMonotonicClock.kt` (new)
  - `M/di/AppLockModule.kt`
  - `M/domain/diagnostics/DiagnosticReasonCode.kt` (all lock codes from spec §10)
- **Write set (tests):** in `T/domain/privacy/applock/`:
  - `AppLockControllerResolutionTest.kt`
  - `AppLockControllerTimerTest.kt`
  - `AppLockControllerAuthOutcomeTest.kt`
  - `AppLockControllerInertTest.kt`
  - `AppLockControllerIllegalTransitionTest.kt`
  - `AppLockControllerIsolationTest.kt` (subject to TECH-10)
- **Legal path preserved:** new APP LOCK path. No Room, no dependency on barriers.
- **Depends on:** S14, S15.
- **Guards:** FG-10, FG-16, FG-17 (`time_boundaries`), FG-14.
- **Gates:** privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*AppLockController*`
  - shard `domain-n-z`
  - `registered-guard` `time_boundaries`, `cancellation`
- **Split seam:** (i) resolution, inert and T6b; (ii) timer and auth outcomes.
- **Release state:** dormant.

### S17 — AppLockController: held deep link and LOCK_QUERY
- **Goal / invariants:** INV-30, INV-31; T20–T22; OD-7a.
- **Acceptance examples:** AE-55, AE-56, AE-57, AE-58, AE-59, AE-60 (controller level).
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt`
  - `M/domain/privacy/applock/HeldDeepLink.kt` (new; memory only; never in a Bundle or log)
- **Write set (tests):** `T/domain/privacy/applock/AppLockControllerHeldLinkTest.kt`
- **Depends on:** S16, S3.
- **Gates:** privacy-security-guardian, reviewer-strict, architecture-guardian.
- **Validation:** `targeted-unit-test` `*AppLockControllerHeldLink*`; `targeted-unit-test` `*AppLockController*`; shard `domain-n-z`.

### S18 — AppLockController: settings operations
- **Goal / invariants:**
  - INV-3 (OD-8a), INV-23 (enable path), INV-45, INV-47, INV-48
  - INV-51 (INERT and REVIEW_SETTINGS notice derivation)
  - INV-13 (lock-enable fires `DiscreetRepostTrigger`)
  - T17, T18, T19, T26, T27, T28, T29
- **Acceptance examples:**
  - AE-36, AE-37, AE-70
  - AE-85, AE-86, AE-87, AE-89
  - AE-93, AE-97, AE-98
  - AE-99 (1b rows), AE-101, AE-103
  - AE-102 (the lock-enable trigger)
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt`
  - `M/domain/privacy/applock/AppLockSettingsOperations.kt` (new, optional split in the same directory)
- **Write set (tests):** in `T/domain/privacy/applock/`:
  - `AppLockControllerEnableDisableTest.kt`
  - `AppLockControllerReviewSaveTest.kt`
  - `AppLockControllerRereadTest.kt`
  - `AppLockControllerForcedValueTest.kt`
  - `AppLockControllerNoticeTest.kt`
- **Depends on:** S17 (same file), S9.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:** `targeted-unit-test` `*AppLockController*`; shard `domain-n-z`.
- **Split seam:** (i) enable, disable, weakening, forced values; (ii) review-save, re-read, notices.

### S19 — AppLockGate, lock surface and notice slot (Compose, no Hilt)
- **Goal / invariants:**
  - INV-26 (composition)
  - INV-27 (spec 3.6)
  - INV-34 (Unlock is disabled while AUTHENTICATING; no auto-prompt)
  - INV-35, INV-51 (rendering), INV-52
- **Acceptance examples:**
  - AE-38 (the content slot is composed 0 times in RESOLVING, LOCKED and AUTHENTICATING(UNLOCK); AMD-18)
  - AE-44, AE-46 (UI)
  - AE-62, AE-69
  - AE-99 (no notice in non-open states), AE-101, AE-108
- **Write set (source):**
  - `M/ui/applock/AppLockGate.kt`
  - `M/ui/applock/AppLockSurface.kt`
  - `M/ui/applock/LockNoticeBanner.kt`
- **Resources:** `R/values{,-el,-es}/strings_applock.xml` (lock surface, paused banners, review banner)
- **Write set (tests):**
  - `T/ui/applock/AppLockGateTest.kt`
  - `T/ui/applock/AppLockSurfaceTest.kt`
  - `T/ui/applock/LockNoticeBannerTest.kt` (`createComposeRule`; TalkBack; 200% font scale)
- **Depends on:** S16 (state and notice types). Can run in parallel with S17 and S18.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockGate*`
  - `targeted-unit-test` `*AppLockSurface*`
  - `targeted-unit-test` `*LockNoticeBanner*`
  - shard `runtime-ui`
  - `lint`

### S20 — MainActivity shell integration
- **Goal / invariants:**
  - INV-24, INV-25 (binder), INV-26, INV-27, INV-29
  - OD-9a
  - R2: the POST_NOTIFICATIONS prompt runs at most once per process (AE-39) and keeps R24's rules
  - a prompt host for all purposes
- **Acceptance examples:**
  - AE-38 (FLAG_SECURE before the first frame; no `ReviewViewModel`, no clipboard read, no permission dialog)
  - AE-39, AE-41 (configuration change, shade and split-screen are not backgrounding)
  - AE-61, AE-62, AE-63
  - AE-42, AE-66, AE-67 (device)
- **Assumes TECH-1 A and TECH-3.**
- **Write set (source):**
  - `M/ui/MainActivity.kt`
    - the class declaration;
    - FLAG_SECURE set before `setContent`;
    - `AppLockGate` wraps `MainScreen` inside `ProvideNavigationController`, with a `SaveableStateHolder`;
    - the prompt host;
    - a once-per-process guard on the `:471-478` effect.
  - `M/ui/applock/AppLockLifecycleBinder.kt` (new)
  - `M/ui/applock/SecureWindowController.kt` (new)
  - `M/ui/applock/NotificationPermissionPromptOnce.kt` (new)
- **Write set (tests):**
  - `T/ui/applock/AppLockLifecycleBinderTest.kt`
  - `T/ui/applock/SecureWindowControllerTest.kt`
  - `T/ui/applock/MainScreenCallSiteTest.kt` (TECH-10)
  - plus a run of the pinned `GsonR8KeepRulesContractTest`
- **Legal path preserved:** `MainScreen` is invoked only from the gate. The operational lock inside `MainScreen` is unchanged.
- **Depends on:** S15, S16, S19. Release: **R24 and R19 landed; R18 HZ-03 F1 checked.**
- **Guards:** FG-12, FG-16, FG-22 (runtime), the GsonR8 pin.
- **Gates:** architecture-guardian (lead: host, cross-module), privacy-security-guardian, reviewer-strict.
- **Validation:**
  - `compile`
  - `targeted-unit-test` `*AppLockLifecycleBinder*`
  - `targeted-unit-test` `*SecureWindowController*`
  - `targeted-unit-test` `*MainScreenCallSite*`
  - `targeted-unit-test` `*GsonR8KeepRulesContractTest*`
  - `targeted-unit-test` `*MainViewModel*`
  - shard `runtime-ui`
  - `registered-guard` `ui_dao`
- **Split seam:** (i) host, binder, FLAG_SECURE; (ii) gate wrap, state holder, prompt host, permission-once.
- **Release state:** the lock itself is dormant, but every user now sees a brief RESOLVING surface with FLAG_SECURE while settings resolve (R14). Device-check at G1b.
- **Rollback:** clean revert while no user can enable the lock (before S22).

### S21 — MainActivity deep-link hold integration
- **Goal / invariants:** INV-30, INV-31, INV-26 (no deep-link lookup or write while not open), INV-14.
- **Acceptance examples:** AE-55, AE-56, AE-57, AE-58, AE-59, AE-60 (integration).
- **Write set (source):**
  - `M/ui/MainActivity.kt` (`onCreate`/`onNewIntent` → policy → LOCK_QUERY → hold or apply)
  - `M/ui/navigation/DeepLinkApplier.kt` (applies on release from hold; `afterUnlock` flag)
- **Write set (tests):**
  - `T/ui/navigation/DeepLinkApplierHoldTest.kt`
  - `T/ui/navigation/DeepLinkApplierTest.kt` (modify if signatures change)
- **Depends on:** S17, S20. Release: as S20.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:** `targeted-unit-test` `*DeepLinkApplier*`; `targeted-unit-test` `*GsonR8KeepRulesContractTest*`; shard `runtime-ui`.

### S22 — Settings UI, 1b rows (1b user switch)
- **Goal / invariants:**
  - INV-23 (unavailable variants by API and capability)
  - INV-3 / OD-8a (prompts on weakening changes)
  - INV-45 (review card; in INERT the lock choice is off only)
  - INV-48 (forced rows are read-only)
  - OD-20a (secure-screen row on API 26+)
  - the API 30 note (UQ-3)
- **Acceptance examples:** AE-34, AE-35, AE-36 (UI), AE-54 (paused row is off-only), AE-65, AE-70, AE-85, AE-86, AE-87, AE-93 (UI), AE-103 (UI).
- **Write set (source):**
  - `M/ui/screens/privacysettings/AppLockPrivacySection.kt`
  - `M/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt`
  - new dialogs in the same package: `AppLockEnableDialog.kt`, `AutoLockTimeoutDialog.kt`, `AppLockDisableDialog.kt`
  - `R/values{,-el,-es}/strings_privacy_section.xml` (add the 1b strings)
- **Write set (tests):** modify `AppLockPrivacySettingsViewModelTest.kt` and `AppLockPrivacySectionTest.kt`; new `AppLockDialogsTest.kt`.
- **Depends on:** S18, S20, S21. Release: R17, R23, R19 landed.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockPrivacy*`
  - `targeted-unit-test` `*AppLockDialogs*`
  - `targeted-unit-test` `*PrivacySettings*`
  - shard `runtime-ui`
  - `lint`
  - `registered-guard` `ui_dao`
- **Split seam:** (i) lock switch, unavailable variants, enable/disable; (ii) timeout, forced rows, secure toggle, review card.
- **Release state:** 1b is live.
- **Rollback:** after release, use a forward fix only. The rollback unit would be {S22, S21, S20} plus the S14 lock term: reverting S22 alone strands users behind a lock they cannot turn off.

### S23 — Docs (1b)
- **Write set (docs):**
  - `LEGAL_PATHS.md`: APP LOCK section; DEEP LINKS updated with the hold.
  - `CODEBASE_SEGMENTS.md`: Segment 28 lock owners; an ownership note for the UI shell gate (`MainActivity` has no segment today).
  - `NAVIGATION_ARCHITECTURE.md`, `UI_REFERENCE_INDEX.md`, `COMPREHENSIVE_UI_MAP.md`
  - `CODEBASE_INVENTORY.md` (dependency, permission)
  - `hilt-bindings-map.md` if stale
  - `docs/features/app-lock-privacy-mode/release-note-1b.md` (API 30 Snooze/Dismiss loss, screenshots blocked, unavailable on older Android)
- **Depends on:** S14–S22. **Gates:** architecture-guardian.

### G1b — Increment 1b gate
- **Runner (serial):** `unit-tests` → `static-guards` → `app-check` → `assemble-release` → `release-artifact-evidence` (adds `USE_BIOMETRIC`; no `USE_FINGERPRINT`).
- **Device lanes:**
  - API 26 (approved): AE-34, OD-20a.
  - API 35 (approved): full flow.
  - **API 30, 31, 33, 34 (need APP-3):**
    - AE-38, AE-39 (API 33+), AE-41, AE-42 (OEM credential screens)
    - AE-44–47, AE-48/49
    - AE-55–59 (adb kill with a held link)
    - AE-61, AE-64 (API 30 vs 31), AE-66, AE-67, AE-69
    - R3: re-lock with system screens (SAF, camera, share sheet, permission dialogs)
    - R14: RESOLVING flash
- If APP-3 is denied, 1b stays PARTIAL. The minimum is API 30 + API 31.
- **Reviews:** as at G1a.

---

## 4. Increment 2 — optional app PIN

**Conditional on OD-1a with OD-15a and OD-16a.** Preconditions:
- G1b accepted.
- The owner re-confirms Inc 2 after 1b ships.
- OD-19/C5 status recorded (if no `dataExtractionRules`, protection relies on the OD-15a hard lock).
- UQ-1 and UQ-4 confirmed.
- Inc 2 preflight re-checks write sets. Recommended: the PIN screens are sub-screens inside Privacy Settings, with no new `NavigationDestination`, avoiding nav files touched by R19.

### S24 — AppPinStore
- **Goal / invariants:** INV-38, INV-39 (schedule, durable-before-result, boot-aware, shared counter, repair), INV-42 (store), INV-1, INV-51 (flag persistence); OD-13a, OD-14.
- **Acceptance examples:** AE-71, AE-73 (math), AE-74, AE-75, AE-84, AE-91, AE-92, AE-100 (flag), AE-106.
- **Write set (source):**
  - `M/domain/privacy/applock/pin/AppPinStore.kt`
  - `M/domain/privacy/applock/pin/PinAttemptPolicy.kt`
  - `M/domain/privacy/applock/pin/PinFormatPolicy.kt`
  - `M/domain/privacy/applock/pin/BootCountSource.kt`
  - `M/data/privacy/applock/pin/AppPinStoreImpl.kt`
    - Own app-private store.
    - PBKDF2-HMAC-SHA256 through `javax.crypto`, calibrated to 200 ms–1 s; salt ≥16 B; output ≥32 B.
    - Constant-time compare; arrays zeroed after use.
    - Never `security-crypto` (R13).
  - `M/data/privacy/applock/pin/KeystorePinKeyBinder.kt` (non-exportable AndroidKeyStore HMAC; no user-auth requirement)
  - `M/data/privacy/applock/pin/AndroidBootCountSource.kt`
  - `M/di/AppLockModule.kt`
- **Write set (tests):**
  - `T/domain/privacy/applock/pin/PinAttemptPolicyTest.kt`
  - `T/domain/privacy/applock/pin/PinFormatPolicyTest.kt`
  - `T/data/privacy/applock/pin/AppPinStoreImplTest.kt` (scans for plaintext)
  - `T/data/privacy/applock/pin/PinVerifierKdfTest.kt`
- **Depends on:** G1b.
- **Guards:** FG-16 (`pii_logging`).
- **Gates:** privacy-security-guardian (lead; a design review **before** implementation), architecture-guardian, reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*AppPinStore*`
  - `targeted-unit-test` `*PinAttemptPolicy*`
  - `targeted-unit-test` `*PinFormatPolicy*`
  - `targeted-unit-test` `*PinVerifierKdf*`
  - shards `data`, `domain-n-z`
  - `registered-guard` `pii_logging`
- **Release state:** dormant.

### S25 — Controller PIN substates and orderings
- **Goal / invariants:**
  - INV-39 (enforcement), INV-40, INV-41, INV-43, INV-49, INV-50, INV-51 (PIN priority)
  - `effectiveLockMode`
  - T23–T25, T30, T31, and the verifier branch of T5
  - OD-15a, OD-17a, OD-18a
- **Acceptance examples:** AE-72, AE-74, AE-77 (logic), AE-78, AE-79, AE-80 (logic), AE-81, AE-82, AE-83, AE-91, AE-92, AE-94, AE-95, AE-96, AE-99 (PIN), AE-107, AE-110.
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt`
  - `M/domain/privacy/applock/LockNotice.kt`
  - `AppLockSettingsOperations.kt` (if present)
- **Write set (tests):**
  - `T/domain/privacy/applock/AppLockControllerPinTest.kt`
  - `T/domain/privacy/applock/AppLockControllerPinOrderingTest.kt` (process killed between steps)
- **Depends on:** S24.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:** `targeted-unit-test` `*AppLockControllerPin*`; `targeted-unit-test` `*AppLockController*`; shard `domain-n-z`.

### S26 — Fingerprint unlock in PIN mode (OD-16a)
- **Goal / invariants:** INV-40 (BIOMETRIC_STRONG only, never device credential; "Use PIN" negative button), INV-51 (one write turns it off and sets the flag), INV-39 (a fingerprint success resets the counter).
- **Acceptance examples:** AE-76, AE-100 (trigger), AE-72 (prompt configuration).
- **Assumes TECH-1 A** (androidx `CryptoObject`). Under B: the platform `CryptoObject`.
- **Write set (source):**
  - `M/data/privacy/applock/pin/BiometricPinKeyManager.kt` (new; Keystore key with user-auth required and invalidated by new enrolment)
  - `M/ui/applock/BiometricPromptDeviceAuthenticator.kt`
  - `M/domain/privacy/applock/DeviceAuthenticator.kt`
  - `M/domain/privacy/applock/AppLockController.kt` (invalidation hook)
- **Write set (tests):**
  - `T/data/privacy/applock/pin/BiometricPinKeyManagerTest.kt`
  - `T/domain/privacy/applock/AppLockControllerPinBiometricTest.kt`
- **Depends on:** S25. Device check: AE-76 via `adb emu finger` on API 30+ (APP-3).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*BiometricPinKeyManager*`
  - `targeted-unit-test` `*AppLockControllerPinBiometric*`
  - shards `data`, `domain-n-z`, `runtime-ui`

### S27 — PIN UI (Inc 2 user switch)
- **Goal / invariants:**
  - INV-38 (PIN never in a String, Bundle or `SavedStateHandle`)
  - INV-42 (no in-app reset)
  - INV-43
  - INV-50 (row unavailable in INERT and DISABLED)
  - UQ-1 (in-app pad), UQ-4
  - PIN-mode turn-off copy
- **Acceptance examples:** AE-72, AE-77 (copy), AE-82, AE-83, AE-104 (no backup call; "Back up now" only navigates), AE-107.
- **Write set (source):**
  - `M/ui/applock/pin/PinPad.kt`
  - `M/ui/applock/pin/PinLockSurface.kt`
  - `M/ui/applock/AppLockGate.kt`
  - `M/ui/screens/privacysettings/applockpin/AppPinSettingsScreen.kt`
  - `M/ui/screens/privacysettings/applockpin/PinSetupFlow.kt`
  - `M/ui/screens/privacysettings/applockpin/AppPinSettingsViewModel.kt`
  - `M/ui/screens/privacysettings/AppLockPrivacySection.kt`
  - `R/values{,-el,-es}/strings_applock_pin.xml`
- **Write set (tests):**
  - `T/ui/applock/pin/PinPadTest.kt`
  - `T/ui/applock/pin/PinLockSurfaceTest.kt`
  - `T/ui/screens/privacysettings/applockpin/AppPinSettingsViewModelTest.kt`
  - `T/ui/screens/privacysettings/applockpin/PinSetupFlowTest.kt`
- **Depends on:** S25 (S26 optional; can run in parallel with S26 because the write sets are disjoint).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*PinPad*`
  - `targeted-unit-test` `*PinLockSurface*`
  - `targeted-unit-test` `*AppPinSettings*`
  - `targeted-unit-test` `*PinSetupFlow*`
  - `targeted-unit-test` `*AppLockGate*`
  - shard `runtime-ui`
  - `lint`
- **Split seam:** (i) lock surface and gate; (ii) settings, setup, change, remove.
- **Rollback:** after release, forward fix only. Reverting would silently drop PIN users back to device-auth protection.

### S28 — Docs (2)
- `LEGAL_PATHS.md` (APP LOCK: PIN ordering), `CODEBASE_SEGMENTS.md` (Segment 28), UI maps, `CODEBASE_INVENTORY.md`, and a release note (forgotten PIN means clear data or restore an exported backup).

### G2 — Increment 2 gate
- **Runner:** as G1b, including the evidence run (proves no permission drift).
- **Device checks:** Keystore binding, reboot delay (AE-73), enrolment invalidation (AE-76), simulated Keystore loss (AE-80).
- **Reviews:** as before.

### SG — Optional proposed guards (spec §8.3, items 1–5)
- Only with APP-1 approval (the freeze lasts until 2026-12-01).
- Each needs a registry entry, guard tests and FG-02/FG-05/FG-23 evidence.
- Blocks no increment.

---

## 5. Parallel waves (disjoint write sets)

- **1a:**
  - A: {S1 ∥ S2 ∥ S3}
  - B: {S4 ∥ S10} (S10 also needs R24/R19)
  - C: S5
  - D: {S6 ∥ S7 ∥ S8 ∥ S11} (S8 needs R21)
  - E: S9
  - F: S12
  - then S13 → G1a
- **1b:**
  - A: {S14 ∥ S15}
  - B: S16
  - C: {S17 ∥ S19}
  - D: {S18 ∥ S20}
  - E: S21
  - F: S22
  - then S23 → G1b
- **2:** S24 → S25 → {S26 ∥ S27} → S28 → G2.

## 6. Release-batch dependencies per slice (beyond the global R17/R21/R24 gate)

| Slice | Extra release dependency |
|---|---|
| S8 (`BillReminderWorker` + test) | **R21 landed** (V-C1/V-C2/V-C3) — hard |
| S10, S20, S21 (`MainActivity`) | **R24, R19 landed; R18 HZ-03 F1 status checked; C4 re-checked (S10)** |
| S12, S22 (`PrivacySettingsScreen`, tile) | **R17, R23 landed; R19 privacy tile landed (DD-24)** |
| S11, S15 (manifest) | re-check R17 manifest edits and C5 `<application>` edits |
| S3 (`AnalyticsScreen.kt`) | re-check R19/R20 edits |
| All others | none (new files, or files with no batch overlap) |

## 7. Traceability (all 52 INV and 110 AE mapped)

**Invariants**

| Slice(s) | INV |
|---|---|
| S1 | 1, 2, 44, 46 |
| S2 | 18 |
| S3 | 14, 15, 17, 21 |
| S4 | 4, 6, 9, 10, 12 |
| S5 | 4, 5, 7, 8, 12, 19 |
| S6, S7 | 4, 9, 11 |
| S8 | 4, 7, 8, 9, 10, 11 |
| S9 | 12, 13, 46 |
| S10 | 14, 16, 18, 22 |
| S11 | 20 |
| S12 | 44, 46, 51 |
| S14 | 2, 6, 10, 44 |
| S15 | 23, 34 |
| S16 | 24, 25, 28, 32, 33, 34, 36, 37 |
| S17 | 30, 31 |
| S18 | 3, 13, 23, 45, 47, 48, 51 |
| S19 | 26, 27, 34, 35, 51, 52 |
| S20 | 24, 25, 26, 27, 29 |
| S21 | 14, 26, 30, 31 |
| S22 | 3, 23, 45, 48 |
| S24 | 1, 38, 39, 42, 51 |
| S25 | 39, 40, 41, 43, 49, 50, 51 |
| S26 | 39, 40, 51 |
| S27 | 38, 42, 43, 50 |

**Acceptance examples**

| Slice(s) | AE |
|---|---|
| S1 | 7, 51, 85, 88, 89, 105 (store parts) |
| S2 | 21, 33 |
| S3 | 19, 23, 25–31 (parse level) |
| S4 | 7–10, 12 |
| S5 | 3, 13, 14 (device), 32, 109 |
| S6 | 3, 4, 6, 11 |
| S7 | 5, 5r, 11 |
| S8 | 1, 2, 7, 9, 11, 12 |
| S9 | 15, 102, 105 |
| S10 | 17–27, 29, 31 |
| S11 | 16 |
| S12 | 85, 88, 89, 105 (UI) |
| S14 | 51, 54, 64, 65 |
| S15 | 34, 35, 45, 50, 93 |
| S16 | 38, 40, 41, 43–54, 61, 63, 68, 90 |
| S17 | 55–60 |
| S18 | 36, 37, 70, 85–87, 89, 93, 97–99, 101–103 |
| S19 | 38, 44, 46, 62, 69, 99, 101, 108 |
| S20 | 38, 39, 41, 42, 61–63, 66, 67 |
| S21 | 55–60 |
| S22 | 34–36, 54, 65, 70, 85–87, 93, 103 |
| S24 | 71, 73–75, 84, 91, 92, 100, 106 |
| S25 | 72, 74, 77–83, 91, 92, 94–96, 99, 107, 110 |
| S26 | 72, 76, 100 |
| S27 | 72, 77, 82, 83, 104, 107 |

## 8. Human decisions required (plan-gate checklist for the owner)

- [ ] Release 1.0 accepted; R17/R21/R24 landed; R18/R19/R23 status recorded; post-R24 tip SHA recorded; feature branch off the release line.
- [ ] **TECH-1 and TECH-2** decided by architecture-guardian with privacy-security-guardian (recommended: A and (a)), plus TECH-3..11.
- [ ] **New permission `USE_BIOMETRIC`** (needed under both TECH-1 options; FG-22 exact permission policy) approved.
- [ ] **New dependency androidx.biometric 1.1.0** approved, with `USE_FINGERPRINT` stripped (only if TECH-1 A).
- [ ] **New legal paths** NOTIFICATION PRESENTATION, DEEP LINKS and APP LOCK (spec 8.2) approved for S13/S23/S28.
- [ ] **APP-1:** spec §8.3 guards deferred to SG (recommended). TECH-10 classification of the feature-scoped source-scan tests confirmed or refused.
- [ ] **APP-2:** acknowledge that no `db_ownership_policy` amendment is planned, because `deliverReminder`'s signature is kept. Any deviation stops S7.
- [ ] **APP-3:** emulator images for API 30/31/33/34 before G1b (and Inc 2 device checks).
- [ ] **APP-4:** OD-5a release-note text and where it goes. It ships with the first release that contains S8.
- [ ] **C6 and C7 choices** (§9).
- [ ] **Spec bookkeeping (domain-modeler) before S4/S19:**
  - AMD-17: six kinds (INV-12, spec 2.5, AE-11/AE-13/AE-109; AE-5r).
  - AMD-18: INV-26 / INV-51 / spec 3.6 apply to AUTHENTICATING(UNLOCK) only. Recommendation: ENABLE and CONFIRM_CHANGE start from an open state and keep `MainScreen` composed; otherwise every enable or weakening prompt tears down and rebuilds the app.
- [ ] ux-flows follow-up pass landed before S5 (copy), S12, S19, S22, S27. UQ-2 and UQ-3 confirmed before S19/S22; UQ-1 and UQ-4 before S27.
- [ ] Translations for el/es: agent-drafted with owner spot-check, or owner-provided.
- [ ] Acknowledge that `DeepLinkParserTest` `RequireConfirmation` cases are rewritten, not deleted (R11/S5).
- [ ] Acknowledge 1a shipping in 1.0.x ahead of 1b (OD-4a), and that S15's permission should not ship publicly before S22.
- [ ] Inc 2 go/no-go re-confirmation after G1b, with OD-19/C5 status.

## 9. Pre-existing issues the feature touches

| Issue | Where it is touched | Disposition |
|---|---|---|
| **C6** `notify()` without `SecurityException` handling (`AndroidNotificationService.kt:84,133,168`) | S5 (new methods), S9 (sweep) | **Left to the release track by default.** But AGENTS.md rule 2 binds new code, so the owner must choose: (i) **fold in (recommended)** — the new kind-aware methods catch the exception and return `PERMISSION_DENIED` (TECH-9), with a parity test per caller (warranty → `markFailed`, retryable; receipt keeps its `PERMISSION_REVOKED` diagnostic), so C6 closes when S6/S7 land; or (ii) keep it out — the new methods deliberately propagate like the legacy ones, with the AGENTS.md deviation documented, until the release track fixes C6. If the release track lands C6 first, S5 adopts it. The S9 sweep always catches (AE-102). |
| **C7** merchant and amount in `Timber.i` (`AnomalyAlertOrchestrator.kt:199-205`) | S6 edits `:193` next to it | **Left to the release track by default.** Owner option: fold into S6 as one hunk (constant bounded message), which is privacy-positive and changes no behaviour. Recommended if still open. |
| C4 raw deep-link value in diagnostics and briefing suppression | S10 | Covered by S10 unless the release track ships it first (then adopt). |
| C5 / OD-19 device-to-device transfer | Inc 2 value | Release track; not fixed here. |
| Stale `NotificationService.send()` in LEGAL_PATHS Anomaly Alerting | S13 | Corrected inside the section being edited (flagged). |
| Hard-coded "Snooze"/"Dismiss" | S8 | Moved to resources (ux 2.7 recommendation; in scope). |
| Unused `DeepLinkParser.RequireConfirmation` | S3 | Replaced; test modified. |
| `privacy_*` strings missing el/es; English channel names; English briefing title literal | — | Out of scope, not fixed. |
| Spec F19 is wrong (the Compose test dependency exists) | S19/S12 tests | No new test dependency. |

## 10. Risk register

| ID | Risk | Mitigation / slice |
|---|---|---|
| RP-1 | Merge contention in `MainActivity` (R24/R18/R19) | Start from the post-R24 tip; S10/S20/S21 hunks kept minimal; split seams |
| RP-2 | TECH-2 contract churn across 9 test files | Additive transition S5→S9; mechanical `coEvery`; S9 removal only after migration |
| RP-3 | `BillReminderWorker` collision with R21 | S8 after R21; S14 avoids a second edit via the applier |
| RP-4 | OD-5a visible change for all API 31+ users | APP-4 note ships with S8's release |
| RP-5 | RESOLVING surface and FLAG_SECURE at every start for all users after S20 (spec R14) | Device check at G1b; optional pre-warm needs a write-set extension (`MainApplication.kt`) |
| RP-6 | Unwanted re-locks or loops with system screens on API 30+ (R3) | TECH-3 exact counter; own prompt exempt by state; device lanes need APP-3 |
| RP-7 | FLAG_SECURE not inherited by a Compose window (AE-67) | Device check; no `SecureOff` anywhere |
| RP-8 | Robolectric fidelity for `getActiveNotifications`; SDK 35 not on the JVM | Device check of re-post at G1a on API 35 |
| RP-9 | POST_NOTIFICATIONS replayed after unlock (R2) clashes with R24 | S20 once-per-process guard; R24 logic re-read |
| RP-10 | API 30 lock users lose Snooze/Dismiss (R9) | Disclosed (UQ-3); S2 in-app actions stays out |
| RP-11 | Late or slow settings read locks a lock-off user (T5) | Bounded by `T_resolve`; T6b releases without auth (AE-53) |
| RP-12 | Diagnostic codes travel in exported backups (OD-12a) | Codes/enums only; privacy-security-guardian acknowledgment in S2 |
| RP-13 | Spec/ux drift: 5 vs 6 kinds; AUTHENTICATING purpose | AMD-17/18 before S4/S19 |
| RP-14 | Guard freeze leaves invariants without guard enforcement | Unit tests per INV; compile-time enforcement after S9; SG later |
| RP-15 | A slice exceeds 2+2 iterations (S5, S16, S20, S22, S27 are the largest) | Pre-identified seams; hand back PARTIAL |
| RP-16 | Permission visible before the feature if S15 ships early | Ship 1b as one release (§8) |
| RP-17 | Inc 2: forgotten PIN with no exported backup means data loss (R8); D2D (R6) | Setup warning; OD-15a hard lock; owner re-confirmation |
| RP-18 | Post-release rollback strands users (S12/S22/S27) | Forward fix; rollback units documented per slice |
| RP-19 | Translation quality or lint failures | Per-slice `lint`; owner decision on translation source |

## 11. Deferred to later increments / out of scope

- Hide-amounts (separate brief; answers loan-installments OQ-14).
- S2 in-app Snooze/Dismiss for API 30; S8 last-export indicator; S9 in-app erase; S10 PIN below API 30; S11 PIN-only lock without a screen lock.
- Removing the VIEW filter (OD-3b; revisit after S5 and an external-caller check).
- App-wide `dataExtractionRules` (C5/OD-19, release track).
- API 34 accessibility sensitive-data flag; `RescueActivity`; bill notification content intent (S4 spec flag).
- Spec §8.3 guards (SG, APP-1).
- RP-16 typed IDs for briefing/budget.
- Pre-existing el/es gaps in `privacy_*`.

---

## Orchestrator reconciliation note (2026-10-17) — open deltas before plan gate

The plan was written against domain-spec AMD-1..16; AMD-17..21 landed in parallel.

1. **ID collision — TECH-3.** Spec §13.1 TECH-3 = *re-post kind detection* (budget notifications post with raw `budget.id.toInt()`, `BudgetMonitor.kt:354,365,376 → :474`, so id ranges cannot identify budget kind; recommended (a): stamp a hidden kind marker at post time). Plan §1 TECH-3 = *background signal (INV-25)*. In this plan, read spec TECH-3 as **TECH-12**.
2. **S9 kind detection is stale.** S9 still detects kind by id range ("budget 1-9999 …"). It must follow spec AMD-21 / AE-112 + TECH-12 (kind marker stamped by the posting owners → S5 and S8 write sets gain the stamp; S9 reads the marker; legacy notifications without a marker handled per spec 4.6).
3. **AMD-17 (six kinds) — landed** as the plan expected (AE-111 = receipt-match; plan's provisional AE-5r maps to AE-5 ext. / AE-111).
4. **Plan-requested "AMD-18" (INV-26 / INV-51 / spec 3.6 apply to AUTHENTICATING(UNLOCK) only; ENABLE and CONFIRM_CHANGE keep `MainScreen` composed) — NOT yet in the spec.** Spec AMD-18 is the Gate-2 outcomes column instead. Sent to domain-modeler as AMD-22.
5. Traceability §7 must add AE-111, AE-112 and any AE from AMD-22.

Status: deltas 2, 4, 5 to be folded by a feature-planner revision pass after AMD-22.
