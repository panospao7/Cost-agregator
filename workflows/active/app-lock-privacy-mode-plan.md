# Feature plan: app-lock-privacy-mode (v2)

Author: `feature-planner`, 2026-10-17. Revised the same day as v2. Persisted verbatim by the orchestrator. Planned against domain-spec AMD-1..22.

## Revision log (v2) — 2026-10-17

This revision folds in domain-spec AMD-17..22 (54 INV, 121 AE, T1–T35), gate2-decisions §B TECH-3 (kind marker) and the impact-map headline-4 annotation. It replaces the v1 "Orchestrator reconciliation note", whose deltas are all folded in below. v1 text that is still valid is kept as it was.

**TECH ID mapping (fixes the collision)**

| Plan ID | Topic | Spec / gate2 ID |
|---|---|---|
| TECH-1, TECH-2 | Prompt API; `NotificationService` contract | Same IDs (spec 13.1, gate2 §B) |
| TECH-3 | Background signal for INV-25 | Plan-only, no spec ID. Kept unchanged. |
| TECH-4..TECH-11 | As in v1 | Plan-only |
| **TECH-12** | Kind marker for the re-post sweep | **spec 13.1 TECH-3 = gate2 §B TECH-3.** Spec 13.1 already says "the plan calls this item TECH-12". |
| **TECH-13** | Weakening setting change while INERT (new) | No spec ID. Domain-modeler gap; the spec and ux bookkeeping follow the verdict. |

Wherever the spec or the gate2 sheet says "TECH-3" for the kind marker, read plan TECH-12.

**Changes**

1. **TECH-12** (spec AMD-21, the 4.6 legacy rule, AE-112, AE-121):
   - S0 adds TECH-12 with options and recommends (a).
   - S5: the shared `NotificationPresentationApplier` stamps a hidden kind marker through a new `NotificationKindMarker`. Write set, tests and AEs updated (AE-112/AE-121, post side).
   - S8: bill posts get the BILL marker through the same applier, and `CHANNEL_ID` becomes `internal` for the classifier. Write-set note, tests and AE-121 (post side) updated.
   - S9: a new pure `NotificationKindClassifier` checks the marker first (it must be present, known and allowed on the channel). Otherwise it uses the channel + id-range table. Other channels are never touched. The marker is never visible text or a text extra, and it never changes the id or tag.
   - The v1 "budget 1-9999" assumption is removed everywhere. Budget alerts post with the raw `budget.id.toInt()` (`BudgetMonitor.kt:354,365,376 → :474`, F23). S6 now states that ids are unchanged.
2. **AMD-22:**
   - S15: constant prompt copy (`AuthPromptCopy` plus `strings_applock_prompt.xml`; INV-53, last bullet; the AE-114 title).
   - S16: purpose-bound attempt machinery, T32/T34/T35, pending-background consumption and the AMD-22 illegal transitions. Adds AE-42 (controller part), AE-116 and AE-118, and a new test file.
   - S17: T20 for any purpose, and the held link is released when a prompt ends. Adds the AE-118 link part.
   - S18: T33 (including the unlock-equivalent discard), T29 (AMD-22), the INV-51 origin notice, prompt-copy selection and TECH-13. Adds the controller parts of AE-113..AE-117 and new test files.
   - S19: no composition only in AUTHENTICATING(UNLOCK); the INV-53 gate parts. The AE-38 citation is fixed (AMD-18 → AMD-22). Adds AE-113, AE-114, AE-115 and AE-118, and a new test file.
   - S20: FLAG_SECURE follows committed settings only. New `AppLockPromptHost` (PROMPT_HOST_LOST, re-attach on rotation). The binder reports every real stop. Adds AE-113, AE-114, AE-116, AE-117 and AE-118.
   - S21: adds AE-118 (integration).
   - S22: the `Authenticating(purpose)` UI over the still-composed screen, cancel and host-loss copy, and the TECH-13 timeout row. Adds AE-113, AE-114, AE-116 and AE-118.
   - S25: PIN steps are not prompts (INV-54 [2]). Adds AE-119.
   - S26: a prompt in PIN mode that is not unlock-equivalent (AE-120, conditional), and constant fingerprint prompt copy.
   - S27: the `PinStep` UI starts empty after a re-lock. Adds AE-119 (UI).
   - INV-53 and INV-54 are mapped. G1b and G2 device lanes are extended.
3. **TECH-13** is added to S0 with options and recommendation (a). S18 seam (i), S22 seam (ii) and S23 depend on it. A 1b precondition and a §8 item are added.
4. **Traceability (§7)** is rebuilt for 54 INV and 121 AE. The provisional AE-5r is replaced by AE-111. INV-12 is now also mapped to the composition-site slices S6, S7 and S8.
5. **§8:** removed "Spec bookkeeping (AMD-17 six kinds; AMD-18 AUTHENTICATING rule)", now done as spec AMD-17 and AMD-22. S0 no longer carries that bookkeeping. The 1b precondition "AMD-18 recorded" is replaced by TECH-13 plus the ux-flows AMD-22 touch-up.
6. **Risk register:** RP-6 and RP-8 updated, RP-13 closed, RP-20..RP-24 added. §9 adds a row for the pre-existing F23 issue.
7. The orchestrator reconciliation note is removed; its content is folded in above.

**Unchanged:**
- tier, and schema impact NONE;
- release-batch gating (§6);
- roles, and the serial runner with one `-TestFilter` (one `--tests` pattern) per targeted run;
- the guard freeze (APP-1, TECH-10);
- the C6/C7 options (§9);
- the parallel waves (§5), apart from the TECH-12/13 ordering note.

---

- **Risk tier:** T3 for Inc 1a, 1b and 2 (impact-map §8). Schema impact is NONE: settings, PIN and lock state are device-local or held in process memory, and new reason codes are stored as TEXT. So:
  - there is no Room or migration slice;
  - `room-migration-guardian` is not needed;
  - the `migration-tests` profile is never requested;
  - FG-21 does not apply.
- **Status:** plan only. This plan declares nothing accepted and contains no code. Start gate: owner approved 2026-10-22; post-R24 tip recorded as 6b44823e (see build/release-lead-notes/owner-approvals-20261022.md).
- **Path shorthand:** `M/` = `app/src/main/java/com/yourname/expensetracker/`, `T/` = `app/src/test/java/com/yourname/expensetracker/`, `R/` = `app/src/main/res/`.
- **Binding inputs:**
  - brief v2;
  - domain-spec (AMD-1..22: 54 INV, 121 AE, T1–T35);
  - gate2-decisions (DECIDED, all recommendations, including NEW-1a, OD-6a, OD-21a and OD-5a; §B TECH-3 kind marker = plan TECH-12);
  - impact-map (headline 4 annotated: budget ids are raw);
  - ux-flows (Gate-2 follow-up pass plus the AMD-22 touch-up);
  - handoff, AGENTS.md, CLAUDE.md;
  - FG-IDs only.

---

## 0. Preconditions and operating rules (apply to every slice)

1. **Start gate.** No slice starts until all of these hold:
   - Release 1.0 is accepted.
   - R17, R21 and R24 have landed.
   - The feature branch is cut from the post-R24 release-line tip, and S0 records the SHA. Never work on the release line.
   - Order is 1a → G1a → 1b → G1b → 2 → G2.
   - Per-slice release-batch dependencies are listed in each slice and summarised in §6.
2. **Line numbers will drift.** Citations come from the 0a9cab1c baseline, and R16..R25 will shift them. Each slice re-reads its files first. If a file no longer matches what the plan assumes (for example, R21 restructured `BillReminderWorker`), stop and return to the orchestrator.
3. **Roles.**
   - The implementer (Sonnet high) writes the source write set only.
   - `test-author` writes the test write set only.
   - `validation-runner` is the only executor. Runs are serial, with one `-TestFilter` per `targeted-unit-test` run. RUNNING is polled. Missing, stale or errored results are never PASS.
   - `ci-build-debugger` diagnoses persisted logs.
   - Each slice gets at most 2 review + 2 debug iterations. If it runs out, hand back PARTIAL/BLOCKED and re-cut the slice at its pre-identified split seam.
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
7. **Strings.** Each slice adds its own `strings_<area>.xml` in `values`, `values-el` and `values-es` (TECH-7). This avoids merge conflicts with R17/R19/R23/R24 in `strings.xml`, and between slices. The pre-existing missing el/es for `privacy_*` stays out of scope.
8. **Dormancy.** The user-visible switch ships last in each increment: S12 (1a), S22 (1b), S27 (2). Every earlier slice is releasable because nothing can turn the new state on.

---

## 1. S0 — Technical decision record and preflight (paper only)

- **Goal:** settle the technical decisions the plan depends on, and refresh the preconditions. No code.
- **Write set (docs):**
  - `docs/features/app-lock-privacy-mode/tech-decisions.md` (new). The orchestrator records the guardians' verdicts on TECH-1..13.
  - `docs/features/app-lock-privacy-mode/domain-spec.md`. Domain-modeler records the TECH-13 outcome only (an INV-3 / 3.3 clarification). No other spec bookkeeping is pending.
  - `docs/features/app-lock-privacy-mode/ux-flows.md`. Ux-designer adds the INERT timeout-row state and copy, only if TECH-13 is (a) or (c).
  - `workflows/active/app-lock-privacy-mode-handoff.md`
- **Gates:** `architecture-guardian` decides. `privacy-security-guardian` co-decides TECH-1, TECH-2, TECH-8 and TECH-13, and reviews TECH-12 (spec 13.1). No validation.

**Preflight record.**
- Post-R24 tip SHA.
- Landed or pending state of R17/R18/R19/R21/R23/R24.
- Status of C4, C5 (HZ-06 F1), C6 and C7.
- External deep-link caller check (spec 5.6; internal docs are not callers).
- ux-flows follow-up pass landed: generic public pair, RECEIPT_MATCH copy, PIN-mode turn-off copy, paused-state rows, briefing title, OD-5a release-note text.
- ux-flows AMD-22 touch-up landed:
  - the §5 layer diagram reads `Authenticating(UNLOCK)`;
  - the §3.8 rows for a settings prompt during background and for host loss;
  - the §4.2 `PinStep` note.
- `BudgetMonitor` still posts with the raw `budget.id.toInt()` (`:354,365,376 → :474`, F23). If the release track changed this, re-evaluate TECH-12.

### TECH-1 — Prompt API

| Option | For | Against |
|---|---|---|
| **A. androidx.biometric 1.1.0 (stable); `MainActivity` changes from `ComponentActivity` to `FragmentActivity`** | Maintained handling of the prompt across rotation and OEM credential screens (the riskiest 1b behaviour: AE-42, AE-47, AE-117, AE-118). Standard `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` mapping. `CryptoObject` for Inc 2. Matches the brief and spec. The host change is one line: `FragmentActivity` extends `ComponentActivity`, and Hilt, `setContent`, `viewModels()` and the result launchers are unaffected. | New dependency, and there is no `verification-metadata.xml`. The merge adds `USE_BIOMETRIC` plus `USE_FINGERPRINT`, so `USE_FINGERPRINT` must be stripped with `tools:node="remove"` (check the merged manifest). One-line edit in the most contended file. Fragment/activity versions need an alignment check. |
| **B. Platform `android.hardware.biometrics.BiometricPrompt`** (API 30+ only path) | No dependency, no host change, no `USE_FINGERPRINT`. | `USE_BIOMETRIC` still has to be declared explicitly (the platform prompt and `BiometricManager` need it). `CancellationSignal`/executor handling across Activity recreation is ours to write. Fewer Robolectric shadows. No OEM workarounds. |
| C. `AppCompatActivity` | — | Rejected: more surface, no gain. |
| D. Separate translucent auth Activity | — | Rejected: new component and legal path, and an `onPause`-only timer (spec R1); needs a human decision. |

- **Recommendation: A.** Both A and B add `USE_BIOMETRIC` (FG-22 exact permission policy), which needs owner approval (§8).
- **If B is chosen:**
  - S15 drops the libs/build changes and the `USE_FINGERPRINT` strip, adds an explicit `USE_BIOMETRIC`, and its adapter manages `CancellationSignal`.
  - S20 drops the host change. Its `AppLockPromptHost` re-attaches the in-flight attempt across rotation itself and emits PROMPT_HOST_LOST (T35, AE-118).
  - S26 uses the platform `CryptoObject`.
  - G1b adds a rotation-during-prompt check (unlock and settings prompts) on every API lane.
- **Under either option**, the prompt host emits PROMPT_HOST_LOST itself rather than relying on a library cancel callback. If both a cancel and a host loss arrive, the first one ends the attempt and the other is stale (T12).

### TECH-2 — `NotificationService` contract

The interface is non-suspend today (`M/domain/service/NotificationService.kt:5-86`). INV-9 needs a settings read that is bounded by `T_read` and can fail.

| Option | For | Against |
|---|---|---|
| **(a) Suspend, kind-aware posting methods.** The builder owner calls `NotificationPresentationResolver`, which wraps the read in `withTimeoutOrNull(T_read = 500 ms)` and rethrows `CancellationException` before any broad catch. While callers migrate (S5–S8), the legacy kind-less methods are hardened in place: PRIVATE visibility, generic public version, explicit component, FULL text, no settings read, no kind marker. S9 removes them. | Matches the spec literally (INV-9/AE-8: a bounded fresh read per post). One invocation point per builder owner. No `runBlocking`: I checked that all six composition sites already run in suspend contexts (`BudgetMonitor.processBudgetStatus`, `AnomalyAlertOrchestrator.checkAndAlert`, the briefing use case `invoke`, `WarrantyExpirationWorker.deliverReminder`, and the guard lambdas of `ReceiptMatchingWorker` and `BillReminderWorker`). Once the legacy methods are gone, the compiler enforces "no kind-less posting". | Contract change. Nine test files change `every` to `coEvery` (all mockk), plus one hand-written fake (`NotificationIdGeneratorTest.CapturingNotificationService`). `BudgetMonitor.sendNotification` becomes suspend. Worst case adds up to 500 ms per post. |
| (b) Caller-resolved decision passed into a non-suspend service | Interface stays non-suspend. | Six sites each own an extra mandatory step. A caller can build a FULL decision itself and bypass the policy, which would need a guard 8.3(1)-style check, and guard additions are frozen (APP-1). Five more constructor changes. |
| (c) Cached snapshot: hot `StateFlow` in app scope; DISCREET until the first emission | No suspend change, no added latency. | When a worker starts a fresh process, its posts can come out DISCREET for users who have discreet off, unpredictably and hard to test. AE-8 changes meaning (it never waits). Needs a warm-up hook (`MainApplication` or `AppStartupCoordinator`) and a guard so a post racing a just-committed write cannot slip past the re-post sweep. |

- **Recommendation: (a).**
- **Localized text, under every option:** the domain policy returns only mode, kind, action policy and flags. Mapping kind to `R.string` happens in the data layer (`NotificationPresentationTexts`, which receives the kind only). The domain stays resource-free, and INV-5 holds by construction.
- **If (b):** S4's resolver becomes the callers' API; S5 adds a decision parameter instead of suspend; S6/S7/S8 inject the resolver into every site; ask for a guard via APP-1.
- **If (c):** S1 adds a snapshot holder plus warm-up (the write set gains `MainApplication.kt` or `AppStartupCoordinator.kt`, so a collision check is needed); S4 reads the snapshot without a timeout; AE-8 is rewritten; S9 triggers the sweep on the snapshot edge; S5–S8 lose the `coEvery` churn.

### TECH-12 — Kind detection for the re-post sweep (= spec 13.1 TECH-3 / gate2 §B TECH-3)

Context: budget alerts post with the raw `budget.id.toInt()` (`BudgetMonitor.kt:354,365,376 → :474`), not `NotificationId.forBudget`. Ids therefore cannot identify the kind for budgets whose id is 10000 or more (F23). The cost is a wrong label only; there is no content leak (INV-5).

| Option | For | Against |
|---|---|---|
| **(a) Hidden kind marker stamped at post time.** The shared applier stamps it, so both builder owners do. The sweep reads the marker first and falls back to the channel + id-range table under the spec 4.6 legacy rule. | Exact kind for every notification posted after the upgrade, including budgets with raw ids ≥10000. Ids, tags and dedupe are unchanged. Touches only files 1a already edits (S5 applier, S8 via the applier, S9 sweep). Spec-recommended. | One extras entry on every private notification. Notification listeners and companion devices can read it, but it carries the kind token only, which is no more than the discreet title says (OD-10a). Pre-upgrade budget alerts without a marker and with raw ids 10000–29999 stay mislabelled until they are replaced or dismissed (AE-121 residual, content-free). |
| (b) `BudgetMonitor` allocates ids through `NotificationId.forBudget` (1–9999) | No marker. | Changes budget-alert ids: a pre-upgrade notification is not replaced by the next post, and budgets whose ids differ by a multiple of 9999 share an id. A dedupe behaviour change in S6 (`BudgetMonitor` plus 3 tests) beyond scope. |
| (c) Accept the residual | Smallest change. | The mislabel stays for every budget ≥10000, indefinitely. AE-121 becomes N/A. |

- **Recommendation: (a).** Mechanism (S5):
  - one app-namespaced extras key whose value is the kind's stable token (its enum name, pinned per kind by a test);
  - set on the private notification only, never on the public version;
  - never a platform text extra, never user-visible;
  - the `notify(id, …)` call is unchanged (same id, no tag change).
- **If (b):** S6 gains the id-allocation change and the `BudgetMonitor*` test updates. S5/S8 drop the stamp. S9 uses the table only. Owner acknowledgement is needed (visible dedupe change).
- **If (c):** S5/S8 drop the stamp. S9 uses the table only. AE-121 is recorded N/A with the residual documented.

### TECH-13 — Weakening setting change while INERT (domain-modeler gap)

**Context.**
- INV-3 / OD-8a: while the lock is enabled, any weakening change needs a fresh authentication.
- In INERT the lock is enabled but nothing can authenticate, and 3.3 (AMD-22) says no prompt is launched from INERT.
- The explicit exceptions are:
  - T19: disable with no auth;
  - INV-45: a weakening review-save needs no auth in INERT, and the lock choice there is off-only.
- In INERT, discreet and secure screen are forced (INV-48 → `Rejected(FORCED)`), and mode switch or PIN setup is excluded (INV-50, and PIN mode is never INERT).
- So the only weakening per-field change that can be reached is a **longer auto-lock timeout**, and the spec does not say what happens to it.
- Scope: `INERT(NO_CREDENTIAL | HW_UNAVAILABLE | UNKNOWN | API_UNSUPPORTED)` with readable settings. While settings are unreadable, INV-44 already rejects every per-field write.

| Option | For | Against |
|---|---|---|
| **(a) Reject.** In INERT, `WEAKENING_CHANGE_REQUESTED` returns a typed `Rejected(AUTH_UNAVAILABLE)`: no write, no prompt, no state change, no new reason code. Non-weakening timeout changes (equal or shorter) commit without auth, as they would from UNLOCKED. UI: the timeout row stays visible (ux 4.2 `Content.LockOn.Inert`); longer values are shown as unavailable with a short paused note (copy owned by ux-flows). | Literal reading of INV-3 plus 3.3. Fail-closed. Consistent with "lock choice is off-only" (INV-45) and with the App PIN row being unavailable while paused (INV-50). Needs no INV change, only a spec clarification. | Friction, and an apparent asymmetry: the user can turn the lock off without auth (T19) but cannot lengthen the timeout. Needs one ux string and a ux state row. |
| (b) Allow without auth, like T19 | Consistent with T19: rejecting protects nothing, because disable is already free in INERT. No UI change. | Extends an owner-decided OD-8a exception beyond the escape that justified T19 (Goal 5, "never stranded"). A longer timeout persists, and silently weakens the lock once it re-activates, set by whoever held the open phone. Needs an INV-3 amendment and owner acknowledgement. |
| (c) Timeout row read-only in INERT (all timeout writes rejected, strengthening too) | Simplest UI; mirrors the App PIN row. | Blocks a strengthening change for no security reason. |

- **Recommendation: (a).** Architecture-guardian decides with privacy-security-guardian. If (b) is chosen, the owner must acknowledge the narrowing of OD-8a (§8).
- **Slices that depend on the verdict:**
  - S18 seam (i): the controller rule and `AppLockControllerInertWeakeningTest`;
  - S22 seam (ii): the timeout row and dialog in INERT, plus strings;
  - S23: docs.
- **Not dependent:** S14 (the store has no INERT notion), S19, S20, and Inc 2.

### Other technical decisions (architecture-guardian)

- **TECH-3 — background signal (INV-25).** This is a plan-only ID, not spec TECH-3; see TECH-12.
  - Recommended: count started activities via `Application.registerActivityLifecycleCallbacks`, ignoring `isChangingConfigurations`. It is registered once per process from `MainActivity.onCreate`, so `MainApplication` is not edited. This keeps the spec's IMMEDIATE semantics (AE-41).
  - Alternative: `ProcessLifecycleOwner`. It delays ON_STOP by about 700 ms, so an IMMEDIATE background shorter than 0.7 s would not lock, which is a spec deviation.
  - (v2, AMD-22) The binder reports every real stop, including stops caused by the app's own prompts (OEM credential activities). It never suppresses events while a prompt is in flight; the controller decides what they mean:
    - UNLOCK: there is no T13 from LOCKED or AUTHENTICATING;
    - ENABLE/CONFIRM_CHANGE: T32 records the background, T33 discards it, T34 hands it to the timer.
  - The same Activity callbacks give the S20 prompt host its host-loss signal: `onActivityDestroyed` with `isChangingConfigurations == false`, while an attempt is in flight with no outcome, emits `PROMPT_HOST_LOST` (T35).
- **TECH-4 — packages, chosen for shard coverage.** `domain.service` is in no shard.
  - `M/domain/privacy/applock/` (shard domain-n-z): settings, controller, ports, diagnostics emitter.
  - `M/domain/notification/presentation/` (domain-n-z): kind, decision, policy, resolver.
  - `M/domain/deeplink/`: `DeepLinkRequest` as a layer-neutral value, so the domain controller can hold it.
  - `M/data/privacy/applock/` (data): store, capability, clock, PIN store.
  - `M/data/service/` (data): applier, texts, kind marker, kind classifier.
  - `M/ui/applock/` (runtime-ui): gate, lock surface, notices, prompt host, the Activity-bound authenticator.
  - Segment ownership: Segment 11 (policy) and Segment 28 (lock/PIN), documented in S13/S23.
- **TECH-5 — bill builder.** Recommended: the builder stays in `BillReminderWorker`. This keeps the `SecurityException` → `permission_denied` lifecycle mapping where it is and limits the R21 collision. The worker applies the decision through the shared `NotificationPresentationApplier`, so both builder owners follow one rule. That includes the TECH-12 kind marker.
- **TECH-6 — explicit component from the data layer.** Recommended: `Intent(context, MainActivity::class.java)`. No guard forbids data importing ui, and there is precedent (`data/ai/provider/DashboardBriefingPromptFormatter.kt`). Alternatives: a class-name string pinned by a test, or an injected intent-factory port.
- **TECH-7 — strings.** Recommended: one new `strings_<area>.xml` per slice (no guard or test pins `strings.xml`; I checked).
- **TECH-8 — diagnostics sink (OD-12a).** Recommended: the new rate-limited `PrivacyDiagnosticsEmitter` delegates to the existing restore-safe `M/domain/diagnostics/NotificationDiagnosticEmitter` rather than adding a second restore-routing rule. It adds only the rate limit and a metadata allowlist.
- **TECH-9 — C6 fold-in shape (only if the owner approves fold-in, §9).** `DeliveryResult` gains `PERMISSION_DENIED`. `ReceiptMatchingWorker` keeps its `..._PERMISSION_REVOKED` diagnostic, and warranty maps it like NOT_DELIVERED (`markFailed`, retryable).
- **TECH-10 — structural tests vs the guard freeze (APP-1).** Recommended: feature-scoped source-scan tests (INV-36 isolation, the "`MainScreen(` only from the gate" check) live in feature test packages, outside `T/architecture/`, and are not registered in `scripts/ci/guard_registry.py`. The owner must confirm this is not a guard-registry addition. If not confirmed, those tests drop out of S16/S20 and wait for SG.
- **TECH-11 — parameters (spec §11), to confirm:** `T_resolve` 2 s, `T_read` 500 ms, `T_held` 5 min (monotonic), `DIAG_RATE` 1 per code per 60 s, URI/location/key limits 2048/100/32.

- **Done when:**
  - TECH-1..13 are recorded with a guardian verdict;
  - the TECH-13 outcome is reflected in the spec (and in ux-flows when it is (a) or (c));
  - the preflight record is complete;
  - the owner has worked through the plan-gate checklist (§8).

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
  - `M/data/privacy/applock/AppLockSettingsRepositoryImpl.kt` (new: its own DataStore `app_lock_privacy_settings`, single instance, CORRUPTED sentinel mirroring `data/privacy/PrivacySettingsRepositoryImpl.kt`, enum tokens)
  - `M/di/AppLockModule.kt` (new)
- **Write set (tests):**
  - `T/data/privacy/applock/AppLockSettingsRepositoryImplTest.kt`
  - `T/data/privacy/applock/AppLockSettingsRepositoryImplCorruptionTest.kt`
  - `T/domain/privacy/applock/EffectivePrivacyValuesTest.kt`
- **Legal path preserved:** a new single writer. Not Room, not in `.costbackup`. Restore, import and DB reset never touch it.
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
- **Acceptance examples:** AE-33 (rate limit plus the `DL_DIAGNOSTICS_SUPPRESSED` count); AE-21 (the emitter rejects free-text metadata).
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
- **Done when:** the limiter and allowlist are proven, and no raw strings are accepted.
- **Release state / rollback:** dormant; clean revert.

### S3 — Deep-link policy (pure parser)
- **Goal / invariants:** INV-14 (parser), INV-15, INV-17, INV-21; F9, F10, F13; spec flag S5 (`RequireConfirmation` replaced).
- **Acceptance examples (parse level):** AE-19, AE-23, AE-25, AE-26, AE-27, AE-28, AE-29, AE-30, AE-31.
- **Write set (source):**
  - `M/ui/navigation/DeepLinkParser.kt` becomes the pure `DeepLinkPolicy`:
    - structural checks (spec 5.4), a closed host enum, and per-host allowlists (5.3);
    - drop unknown, duplicate and invalid parameters; ignore extras; reject non-VIEW;
    - bank-host rejection moved in, using `BankFeatureAvailability.isBankDeepLinkHost`.
  - `M/domain/deeplink/DeepLinkRequest.kt` (new)
  - `M/domain/analytics/TimePeriodTokens.kt` (new: shared period allowlist)
  - `M/ui/screens/analytics/AnalyticsScreen.kt` (the private parser at `:1841-1851` is replaced by the shared one; same behaviour)
- **Write set (tests):**
  - `T/ui/navigation/DeepLinkParserTest.kt` (MODIFY: the `RequireConfirmation` cases at `:34-54` and `:91` are rewritten, none deleted)
  - `T/ui/navigation/DeepLinkPolicyTest.kt` (new)
  - `T/domain/analytics/TimePeriodTokensTest.kt` (new)
- **Legal path preserved:** none yet (pure). The RP-17 rule moves; it is not duplicated.
- **Depends on:** S0. Release: re-check `AnalyticsScreen.kt` for R19/R20 edits.
- **Guards / approvals:** FG-22 (JVM half of the malformed-link runtime proof), FG-16. The owner acknowledges the R11 test modification (§8).
- **Gates:** privacy-security-guardian (lead), architecture-guardian (parser consolidation), reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*DeepLink*`
  - `targeted-unit-test` `*TimePeriodTokens*`
  - shard `runtime-ui`
  - shard `domain-a-m`
- **Done when:** every host and parameter case in 5.3/5.4 is covered, and no input throws.
- **Release state / rollback:** dormant (the old `parseDeepLink` had no production caller); clean revert.

### S4 — Notification presentation policy and bounded resolver (domain)
- **Goal / invariants:**
  - INV-4 (decision), INV-6 (1a terms), INV-9.
  - INV-10 (API 31+ BILL is AUTH_REQUIRED; API ≤30 is UNCHANGED in 1a).
  - INV-12 (closed registry of six kinds: BILL, ANOMALY, BUDGET, WARRANTY, BRIEFING, RECEIPT_MATCH; AMD-17).
  - OD-10a.
- **Acceptance examples:**
  - AE-7, AE-8, AE-9, AE-10 (resolver)
  - AE-12 (decision)
  - decision rows for AE-1..AE-6, AE-109 and AE-111
- **Assumes TECH-2 (a).** Under (b) the resolver is the callers' API; under (c) it reads a snapshot with no timeout.
- **Write set (source):** all new, in `M/domain/notification/presentation/`:
  - `NotificationKind.kt`
  - `NotificationPresentationDecision.kt` (carries the kind, so the applier can stamp the TECH-12 marker)
  - `NotificationContentPolicy.kt` (pure)
  - `NotificationPresentationResolver.kt` (suspend, bounded; emits NOTIF_POLICY_* through S2)
- **Write set (tests):**
  - `T/domain/notification/presentation/NotificationContentPolicyTest.kt` (6 kinds × FULL / DISCREET / 4 unreadable causes / timeout / exception × SDK 26/28/29/30/31/34)
  - `T/domain/notification/presentation/NotificationPresentationResolverTest.kt` (virtual time, hanging fake, cancellation)
- **Legal path preserved:** a new NOTIFICATION PRESENTATION step (documented in S13). Workers' when/whether logic is unchanged.
- **Depends on:** S1, S2.
- **Guards:** FG-10, FG-16.
- **Gates:** architecture-guardian (lead: TECH-2 shape), privacy-security-guardian, reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*NotificationContentPolicy*`
  - `targeted-unit-test` `*NotificationPresentationResolver*`
  - shard `domain-n-z`
  - `registered-guard` `cancellation`
- **Done when:** the table is complete, cancellation propagates, and every failure gives DISCREET.
- **Release state / rollback:** dormant; clean revert.

### S5 — NotificationService contract and builder owner 1 (`AndroidNotificationService`)
- **Goal / invariants:**
  - INV-4 (applying the decision), INV-5, INV-7 (OD-21a generic public pair), INV-8, INV-9 (service), INV-12 (texts for six kinds), INV-19.
  - INV-13 support (TECH-12, post side): the applier stamps the hidden kind marker on every private notification it builds.
- **Acceptance examples:**
  - AE-3 (builder: DISCREET, explicit `MainActivity`, CLEAR_TOP, FLAG_IMMUTABLE)
  - AE-32, AE-109
  - AE-111 (builder and texts: RECEIPT_MATCH discreet pair; never the BUDGET constants; no merchant in any text field, text extra or public version; tap target unchanged)
  - AE-112 and AE-121, post side: each of the five service kinds carries its marker token. The marker is absent from the public version and from every platform text extra. The `notify` id is unchanged and no tag is added.
  - AE-13 (capture FGS untouched; diff check)
  - AE-14 (device check at G1a)
- **Assumes TECH-2 (a), TECH-5, TECH-6, TECH-12 (a).** IDs are allocated as today: each new method takes the id type its caller uses now. There is no RP-16 change, and budget ids stay raw.
- **Write set (source):**
  - `M/domain/service/NotificationService.kt` (additive suspend kind-aware methods; `DeliveryResult` unchanged unless TECH-9)
  - `M/data/service/AndroidNotificationService.kt`
    - inject the resolver and the applier; implement the new methods;
    - harden the legacy methods (they stamp no marker, because they have no kind; the 4.6 fallback covers their posts);
    - explicit-component PendingIntents at `:112, :147, :172`, keeping the data URI, CLEAR_TOP/CLEAR_TASK, request codes and FLAG_IMMUTABLE, and keeping `Intent.ACTION_VIEW` (S10's DeepLinkPolicy rejects any other action with no navigation — every notification tap would be ignored); the AE-32 test asserts `intent.action == Intent.ACTION_VIEW` next to the component and FLAG_IMMUTABLE checks.
  - `M/data/service/NotificationPresentationApplier.kt` (new). One rule for both builder owners:
    - visibility PRIVATE;
    - a generic public version with no actions, no style and no marker;
    - discreet constants;
    - BigText only in FULL;
    - action policy UNCHANGED / AUTH_REQUIRED / NONE applied to the supplied action specs;
    - stamps the TECH-12 marker via `NotificationKindMarker`.
  - `M/data/service/NotificationKindMarker.kt` (new; TECH-12 post side):
    - one app-namespaced extras key whose value is the kind's stable token (the enum name, pinned per kind by test so a rename or shrinker change cannot alter it across releases);
    - private notification only; never a platform text extra (title, text, big text, sub-text, info text, summary text, ticker, text lines); never user-visible;
    - never changes the id or tag passed to `notify`;
    - a read helper returns the raw token or null. Validation is the S9 classifier's job.
  - `M/data/service/NotificationPresentationTexts.kt` (new; takes the kind only)
- **Resources:** `R/values{,-el,-es}/strings_notification_presentation.xml` (new): six discreet pairs including RECEIPT_MATCH, the generic public pair, and `notif_action_snooze` (reuse `action_dismiss` if it fits). Copy comes from ux-flows §2.7/§9.4 after the follow-up pass.
- **Write set (tests):**
  - `T/data/service/AndroidNotificationServiceTest.kt` (modify)
  - `T/data/service/AndroidNotificationServicePresentationTest.kt` (new; Robolectric SDK 26/28/30/31/34)
    - sentinel PII checks over title, text, bigText, subText, ticker, extras and the public version;
    - the marker per kind in FULL and DISCREET;
    - legacy methods carry no marker.
  - `T/data/service/NotificationPresentationApplierTest.kt` (new)
  - `T/data/service/NotificationPresentationTextsTest.kt` (new)
  - `T/data/service/NotificationKindMarkerTest.kt` (new)
    - the six tokens pinned;
    - the key is not a platform text extra;
    - absent from the public version.
  - `T/domain/util/NotificationIdGeneratorTest.kt` (modify the fake)
- **Legal path preserved:** the worker or coordinator still decides when. Posting still goes NotificationService → `AndroidNotificationService`. Presentation, including the marker, is inserted inside the builder owner only.
- **Depends on:** S4; the TECH-12 verdict (S0). Release: none (the impact map rates this Low).
- **Guards / approvals:** FG-16, FG-10, FG-22 (PendingIntent explicitness), FG-20 (lint MissingTranslation). Approvals: TECH-2 and TECH-12 (S0), and the C6 choice (§9).
- **Gates:** architecture-guardian (lead: contract), privacy-security-guardian (also the TECH-12 marker), reviewer-strict.
- **Validation:**
  - `compile`
  - `targeted-unit-test` `*AndroidNotificationService*`
  - `targeted-unit-test` `*NotificationPresentation*`
  - `targeted-unit-test` `*NotificationKindMarker*`
  - `targeted-unit-test` `*NotificationIdGenerator*`
  - shards `data`, `domain-n-z`
  - `lint`
  - `registered-guard` `pii_logging`
- **Split seam:** S5a (contract, applier including the marker, texts, new methods) / S5b (legacy hardening and explicit PendingIntents).
- **Done when:**
  - every service-built notification is PRIVATE, with a generic public version and an explicit, immutable tap target;
  - DISCREET text fields hold only constants;
  - every notification built through the applier carries exactly one kind marker, on its private version only.
- **Release state / rollback:** releasable (visible text is still FULL). Revert restores implicit intents; there is no stored state.

### S6 — Coordinator sites pass kind (budget, anomaly, briefing)
- **Goal / invariants:** INV-4 (callers pass kind plus detail), INV-9, INV-11, INV-12 (each site declares exactly one kind).
- **Acceptance examples:**
  - AE-3 (anomaly site), AE-4, AE-6
  - AE-11 for these 3 sites: notify count, `DeliveryResult` and cooldown writes are identical with discreet off, on and unreadable
  - AE-13 (site declarations: BUDGET, ANOMALY, BRIEFING)
- **Assumes TECH-2 (a):** `BudgetMonitor.sendNotification` becomes suspend. Under (b) each class also injects the resolver.
- **Ids are unchanged:** `BudgetMonitor` keeps posting with the raw `budget.id.toInt()` (`:354,365,376 → :474`), and `NotificationId.forBudget` is not adopted (TECH-12 (b) is not recommended).
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
- **Legal path preserved:** thresholds, cooldowns, dedupe and notification ids are unchanged. The `TransactionSideEffectPlanner` post-commit path is untouched.
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
- **Done when:** parity holds at all three sites, and the ids passed to the service are unchanged.
- **Rollback:** a revert falls back to the legacy methods. S9 must not be merged while S6 is reverted.

### S7 — Worker sites pass kind (warranty, receipt match)
- **Goal / invariants:** INV-4, INV-9, INV-11, INV-12; NEW-1a / AMD-17.
- **Acceptance examples:**
  - AE-5 (WARRANTY; channel `budget_alerts` unchanged; never the BUDGET constants)
  - AE-111 (worker: RECEIPT_MATCH; the sentinel merchant is absent; channel `budget_alerts` and id `NotificationId.forReceipt` unchanged; tap target `home` with CLEAR_TASK)
  - AE-11 for these 2 sites: notify count, `markSentFromClaimed` / `markFailed`, `addNotificationsSent`, and for receipt the `receiptLinkService` call, `addRowsSkipped` and `recordNotificationSuppressed`, all identical
  - AE-13 (site declarations: WARRANTY, RECEIPT_MATCH)
- **Assumes TECH-2 (a).**
- **Write set (source):**
  - `M/service/warranty/WarrantyExpirationWorker.kt` (`:218` → WARRANTY)
    - `deliverReminder` keeps its parameter list `(WorkerRunContext, Warranty, Int, Long, String, String)`, its name and its DAO call order. (Orchestrator correction, S7 review: the earlier text omitted the leading `WorkerRunContext` parameter.)
    - Therefore there is **no `db_ownership_policy.yml` amendment (APP-2 not needed)**.
  - `M/service/receiptmatching/ReceiptMatchingWorker.kt` (`:108` → RECEIPT_MATCH)
    - `notificationPermissionChecker.areNotificationsEnabled()` and `requiresNotificationPermission = false` stay. For the `SecurityException` catch and the explicit `PERMISSION_DENIED` branch, see the S5a carry-forward (explicit `PERMISSION_DENIED` branch to `RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED`); the defensive catch is kept. (Orchestrator correction, S7 review: replaces the earlier "the `SecurityException` catch ... all stay" wording.)
- **Write set (tests):**
  - `T/service/warranty/WarrantyExpirationWorkerTest.kt` (modify)
  - `T/service/receiptmatching/ReceiptMatchingWorkerTest.kt` (modify, never delete)
- **Legal path preserved:** the warranty claim lifecycle, `ReceiptLinkService` and `ReceiptMatchLifecycleService` are unchanged. Edits stay inside the `runGuardedWithContext` lambda.
- **Depends on:** S5. Release: R24 touches only `WarrantyTrackerViewModel`, so there is no overlap.
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
- **Done when:** parity holds, and the `db_access` guard passes without a policy change.
- **Rollback:** clean revert (the legacy methods exist until S9).

### S8 — Bill reminder worker applies the decision (builder owner 2)
- **Goal / invariants:**
  - INV-4, INV-7, INV-8, INV-9, INV-11, INV-12 (the site declares BILL).
  - INV-10: AUTH_REQUIRED on API 31+ for **every** user (OD-5a); unchanged on API ≤30 in 1a. NONE handling is wired ready for S14.
  - INV-13 support (TECH-12, post side): bill posts carry the BILL marker through the shared applier.
- **Acceptance examples:**
  - AE-1, AE-2
  - AE-7 (DISCREET; `markReminderSent` ×1; `notificationsSent` = 1)
  - AE-9 (cancellation during the read: nothing posted; the guard records it as cancelled)
  - AE-11 (bill site)
  - AE-12 (API 26/28/29/30)
  - AE-13 (site declaration BILL)
  - AE-121, post side: marker `BILL` on the private notification only; not a text extra; id `NotificationId.forBill` and the (absent) tag unchanged
- **Assumes TECH-2 (a):** the decision is resolved inside the guard lambda and passed into the non-suspend `sendNotification`. No `runBlocking`. Under (c) it reads the snapshot. TECH-5 and TECH-12 (a) apply.
- **Write set (source):** `M/service/reminder/BillReminderWorker.kt` only.
  - Inject the resolver and the applier. The applier stamps the marker; no marker code lives in the worker.
  - Move the literals `"Snooze"`/`"Dismiss"` (`:259-260`) to the S5 resources.
  - Expose `CHANNEL_ID` (`:295`) as `internal const`, value unchanged, for the S9 classifier. Architecture-guardian confirms the `data/service → service/reminder` import; the precedent is `domain/workers/WorkerRegistry.kt:16` (orchestrator correction; not `RecommendationRepository`), and the S9 classifier may import only the `internal const val CHANNEL_ID`. If it is refused, the constant moves to a shared holder, which needs an orchestrator-approved write-set extension and an R21 re-check.
  - Keep the claim → revalidate → send → mark/fail/cancel order, `addNotificationsSent` only after Sent and marked, and the `SecurityException` mapping (`:266`).
- **Write set (tests):**
  - `T/service/reminder/BillReminderWorkerTest.kt` (constructor only)
  - `T/service/reminder/BillReminderWorkerTimeProviderTest.kt` (constructor only)
  - `T/service/reminder/BillReminderWorkerNotificationPresentationTest.kt` (new; SDK 26/28/29/30/31/34 (orchestrator correction: AE-12 needs 29); includes the BILL marker, its absence from the public version, and the unchanged id and channel)
- **Legal path preserved:** the recurring reminder lifecycle via `RecurringLifecycleCoordinator`. The Snooze/Dismiss receivers, the action workers and the action PendingIntents are untouched.
- **Depends on:** S5. Release: **R21 landed (hard; same file and test).**
- **Guards:** FG-11 (`BillReminderWorker.broadCatch`; no new catch; `WorkerGuardRequest` unchanged), FG-10, FG-07/FG-16 (`android.util.Log`: class names only).
- **Gates:** reviewer-strict (lead: worker and recurring action surface), privacy-security-guardian, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*BillReminderWorker*`
  - `targeted-unit-test` `*WorkerGuardArchitectureGuardTest*`
  - `targeted-unit-test` `*SourceScanningArchitectureGuardTest*`
  - shard `runtime-ui`
  - `registered-guard` `worker`, `cancellation`, `pii_logging`
- **Done when:** parity holds, the lifecycle order is identical, and bill posts carry the BILL marker.
- **Release state:** **visible change for all API 31+ users.** The APP-4 release note must go out with the first release that contains S8.
- **Rollback:** clean revert; re-run the R21 tests afterwards.

### S9 — Discreet re-post sweep and write use case; remove legacy kind-less methods
- **Goal / invariants:**
  - INV-13 (AMD-7, AMD-21, and the AMD-22 4.6 marker and legacy rule), INV-46 (trigger only after a commit), INV-12 (no kind-less posting API left).
  - OD-6a (scope flag S1 included); TECH-12 (read side).
- **Acceptance examples:**
  - AE-15, AE-102
  - AE-105 (no sweep on a failed write); turning discreet off never re-posts
  - AE-112 (channel + id-range classification, with marker-less fixtures, and again with matching markers)
  - AE-121 (marker precedence and legacy fallback)
- **Assumes TECH-2 (a) and TECH-12 (a).** Under TECH-2 (c) the sweep triggers on the committed snapshot edge. Under TECH-12 (b) or (c) the classifier uses the table only, and AE-121 is N/A.
- **Write set (source):**
  - `M/domain/service/NotificationService.kt`: add suspend `repostActiveAsDiscreet`; remove the legacy `send*`/`post*` methods.
  - `M/data/service/AndroidNotificationService.kt`: the sweep.
    - Calls `getActiveNotifications()` once; never `recoverBuilder`.
    - The kind comes from `NotificationKindClassifier` (below).
    - **Rebuild, never copy:** a fresh notification is built from the decision for the detected kind, through the applier, which stamps the detected kind. It reuses only the id, channel, `contentIntent`, action PendingIntents (with the action policy re-applied) and the post time. It copies no text, extras or style.
    - Silent: only-alert-once, and the original `when`.
    - `SecurityException` → skip and record `NOTIF_REPOST_FAILED`; no retry. `CancellationException` propagates. Record `NOTIF_REPOSTED_DISCREET(count)`. No metrics, claim/mark or cooldown changes.
  - `M/data/service/NotificationKindClassifier.kt` (new; pure function of channel, id and raw marker token; spec 4.6):
    - **Marker first.** The marker decides only if it is present, is a known token, and names a kind allowed on the channel:
      - `budget_alerts`: BUDGET, WARRANTY, RECEIPT_MATCH;
      - `bill_reminders`: BILL;
      - `anomaly_alerts`: ANOMALY;
      - `ai_briefings`: BRIEFING.
    - **Otherwise, the table:**
      - `bill_reminders` → BILL; `anomaly_alerts` → ANOMALY; `ai_briefings` → BRIEFING;
      - `budget_alerts` 10000–19999 → WARRANTY; 20000–29999 → RECEIPT_MATCH; any other id → BUDGET. Budget ids are the raw `budget.id.toInt()`; there is no 1–9999 assumption.
    - Any other channel, including the capture FGS channel → not one of the six kinds; never touched. A marker never brings a notification on another channel into the sweep.
    - Uses the existing channel-id constants: the three in `AndroidNotificationService`, and `BillReminderWorker.CHANNEL_ID`, made `internal` in S8. No duplicated literal.
  - `M/domain/privacy/applock/DiscreetRepostTrigger.kt` (new; reused by S18)
  - `M/domain/privacy/applock/UpdateDiscreetNotificationsUseCase.kt` (new)
- **Write set (tests):**
  - `T/data/service/NotificationKindClassifierTest.kt` (new; pure JVM)
    - every 4.6 row;
    - the AE-121 cases: BUDGET marker on id 15003; no marker on 10003; BILL marker on `budget_alerts` 20042; no marker on `bill_reminders` 30011; unknown "X" on id 7;
    - raw budget ids 7, 15003, 20042 and 30500 with and without markers;
    - a marker on a non-sweep channel.
  - `T/data/service/AndroidNotificationServiceRepostTest.kt` (new; Robolectric)
    - AE-15, AE-102, AE-112, AE-121;
    - FGS untouched;
    - no sentinel in any text field, text extra or style;
    - nothing copied from the original.
  - `T/domain/privacy/applock/DiscreetRepostTriggerTest.kt`
  - `T/domain/privacy/applock/UpdateDiscreetNotificationsUseCaseTest.kt`
  - `T/data/service/AndroidNotificationServiceTest.kt` (legacy tests migrated, not deleted)
  - `T/domain/util/NotificationIdGeneratorTest.kt` (fake)
- **Depends on:** S5 and S6, S7 (every service caller migrated before removal); S8 (bill action parity and the channel constant).
- **Guards:** FG-10, FG-16; the AGENTS.md notification rule (new code catches `SecurityException`).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian (contract removal, classifier placement).
- **Validation:**
  - `compile`
  - `targeted-unit-test` `*AndroidNotificationService*`
  - `targeted-unit-test` `*NotificationKindClassifier*`
  - `targeted-unit-test` `*DiscreetRepostTrigger*`
  - `targeted-unit-test` `*UpdateDiscreetNotifications*`
  - `targeted-unit-test` `*NotificationIdGenerator*`
  - `targeted-unit-test` `*SourceScanningArchitectureGuardTest*`
  - shards `data`, `domain-n-z`
  - `registered-guard` `cancellation`
- **Split seam:** S9a (legacy removal) / S9b (classifier, sweep and trigger).
- **Release state / rollback:** dormant sweep; clean revert.

### S10 — Deep-link applier, MainActivity wiring, briefing-opened use case
- **Goal / invariants:** INV-14 (no inline parsing), INV-16, INV-18, INV-22; whatever is left of C4.
- **Acceptance examples:** AE-17, AE-18, AE-19 (nothing written), AE-20, AE-21, AE-22, AE-23 (no lookup), AE-24, AE-25/AE-26 (navigation), AE-27 (empty sheet), AE-29, AE-31.
- **Write set (source):**
  - `M/ui/MainActivity.kt`: the `handleIntent` region `:170-251` only.
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
    - A read failure means no write, plus `DL_BRIEFING_STATE_UNREADABLE`. The existing `AiEngagementRepository` reads suffice.
- **Write set (tests):**
  - `T/domain/ai/usecase/MarkDashboardBriefingOpenedUseCaseTest.kt`
  - `T/ui/navigation/DeepLinkApplierTest.kt`
  - `T/ui/navigation/DeepLinkDiagnosticsSentinelTest.kt` (AE-21 across `AiRuntimeDiagnostics`, emitted metadata and a captured Timber tree)
- **Legal path preserved:** the new DEEP LINKS path. The only persistent write goes through the use case → `AiEngagementRepository` (DataStore). The lookup goes through `ExpenseRepository`.
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
- **Done when:** no raw value reaches any sink, and only the delivered key writes.
- **Release state / rollback:** releasable. A revert re-opens C4.

### S11 — Manifest: remove BROWSABLE (OD-3a)
- **Goal / invariants:** INV-20.
- **Acceptance examples:** AE-16 (device check at G1a).
- **Write set (source):** `app/src/main/AndroidManifest.xml`: remove `android.intent.category.BROWSABLE` (`:85`; orchestrator correction, was `:77`). The VIEW filter and the exported LAUNCHER stay.
- **Write set (tests):** none. Do not add a manifest guard (APP-1).
- **Depends on:** the S0 caller check; S10 (ordering preference only). Release: re-check R17 ("stop if manifest changes") and C5 `<application>` edits.
- **Guards:** FG-22 (evidence at G1a).
- **Gates:** privacy-security-guardian, architecture-guardian (INV-20 owner), reviewer-strict.
- **Validation:** `compile`. Evidence is deferred to G1a (`assemble-release` + `release-artifact-evidence`).
- **Rollback:** one-line revert.

### S12 — Settings UI: "App lock & privacy" section with the discreet row (1a user switch)
- **Goal / invariants:** INV-44 (UI review card; per-field writes blocked while unreadable), INV-46 (UI shows committed state; error on failure), INV-51 (1a: the card is the only notice surface), OD-20a.
- **Acceptance examples:**
  - AE-85, AE-88, AE-89 (UI with the 1a record), AE-105 (UI);
  - loading shows "Checking…" and never Off;
  - the transient retry re-reads.
- **Write set (source):**
  - `M/ui/screens/privacysettings/AppLockPrivacySection.kt` (new; 1a-only copy variant)
  - `M/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt` (new; `PrivacySettingsViewModel` untouched)
  - `M/ui/screens/privacysettings/PrivacySettingsScreen.kt` (one call-site insert)
- **Resources:** `R/values{,-el,-es}/strings_privacy_section.xml` (new)
- **Write set (tests):**
  - `T/ui/screens/privacysettings/AppLockPrivacySettingsViewModelTest.kt`
  - `T/ui/screens/privacysettings/AppLockPrivacySectionTest.kt` (`createComposeRule` under Robolectric; merged semantics)
- **Legal path preserved:** writes go only through `AppLockSettingsRepository` (via the S9 use case).
- **Depends on:** S1, S6, S7, S8, S9 (every kind honours discreet, and the re-post exists, before users can switch it on). Release: **R17 and R23 landed (screen); the R19 privacy tile landed (DD-24).**
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
- **Rollback:** after release, prefer a forward fix. Reverting the UI alone leaves users who enabled discreet with no way to turn it off (the privacy-safe direction). A revert before release is clean.

- **Orchestrator amendment (S12 review, 2026-10-24):** S12 write set also includes `M/ui/screens/privacysettings/AppLockSettingsWriteSerializer.kt` (interim write serializer). **S18** write set must include moving that serializer to the domain owner and editing `AppLockPrivacySettingsViewModel.kt` before AppLockController writes settings; S18 also owns review-save diagnostics.

### S13 — Docs (1a)
- **Write set (docs):**
  - `docs/architecture/LEGAL_PATHS.md`: new NOTIFICATION PRESENTATION and DEEP LINKS sections, as implemented. NOTIFICATION PRESENTATION includes the applier stamping the hidden kind marker, and the sweep's marker-first classification with the channel/id-range fallback (spec 4.6). In the edited Anomaly Alerting step, correct the stale `NotificationService.send()` reference (`:938`). This correction is flagged as in-section only.
  - `docs/architecture/CODEBASE_SEGMENTS.md`: Segment 11 (policy, applier, texts, kind marker, classifier); Segment 28 (store, emitter); navigation (policy, applier).
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
  - AE-16 (a browser link does nothing)
  - API 31+ auth-required Snooze/Dismiss on a locked device
  - AE-12 (API 26)
  - AE-15/AE-102 (silent re-post, original time)
  - AE-112/AE-121 upgrade check on `wave5-api35`, which exercises both paths:
    1. Post FULL notifications from the pre-1a build, install the 1a build, and turn discreet on. This exercises the marker-less fallback.
    2. Post again with 1a and repeat. This exercises the marker path, and checks that the marker survives in `getActiveNotifications()` extras.
  - FG-22 runtime: `adb am start` with malformed links, no crash
  - AE-32 (`dumpsys` shows the explicit component)
  - APP-3 is not needed for 1a.
- **Reviews:** reviewer-strict over the whole increment diff, architecture-guardian, privacy-security-guardian, then a fresh final reviewer. Owner: APP-4, and OD-4a ship confirmation.

---

## 3. Increment 1b — app lock and forced secure screen

**Preconditions:**
- G1a accepted.
- TECH-1 decided.
- TECH-13 decided, and its outcome recorded in the spec (and in ux-flows if (a) or (c)).
- APP-3 requested.
- S0 preflight refreshed (tip; R18/R19/R23/R24).
- ux-flows AMD-22 touch-up landed (§5, §3.8, §4.2).
- UQ-2 and UQ-3 confirmed.

### S14 — Store 1b fields and policy lock terms
- **Goal / invariants:**
  - INV-2 (1b invalid-value rules)
  - INV-44 (full review record)
  - INV-6 (lock term, including inert and API-unsupported; OD-2a)
  - INV-10 (API ≤30: lock on or unreadable → NONE plus `NOTIF_ACTIONS_SUPPRESSED_LOCK`)
  - The settings part of `effectiveSecureScreen`. It is computed from committed settings plus RESOLVING only, with no AUTHENTICATING term (INV-53, INV-46).
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

### S15 — Device-auth capability, authenticator adapter and constant prompt copy (TECH-1)
- **Goal / invariants:**
  - INV-23: evaluated at request time; strong biometric or device credential; never BIOMETRIC_WEAK.
  - INV-34 (adapter): one prompt per attemptId; outcome mapping.
  - INV-53 (prompt text): the system prompt shows only constant, resource-backed text: a title naming the action, at most one constant subtitle naming the method (ENABLE only), and never a description.
- **Acceptance examples:**
  - AE-34 (UNSUPPORTED_API on API 26/28/29), AE-35, AE-93 (mapping), AE-45 (lockout mapping), AE-50
  - AE-114 (copy part: the DISABLE copy maps to `applock_prompt_title_disable`, with no subtitle and no description)
- **Assumes TECH-1 A.** For B, see S0.
- **Write set (source):**
  - `M/domain/privacy/applock/DeviceAuthCapability.kt` (new)
  - `M/domain/privacy/applock/DeviceAuthenticator.kt` (new port; includes the closed `AuthPromptCopy` enum `UNLOCK | ENABLE | DISABLE | CHANGE`, selected by the controller per attempt)
  - `M/data/privacy/applock/AndroidDeviceAuthCapabilityProvider.kt` (new)
  - `M/ui/applock/BiometricPromptDeviceAuthenticator.kt` (new; bound to an Activity; maps `AuthPromptCopy` to `R.string`)
  - `M/di/AppLockModule.kt`
- **Resources:** `R/values{,-el,-es}/strings_applock_prompt.xml` (new): `applock_prompt_title_enable`, `applock_prompt_subtitle_enable`, `applock_prompt_title_disable`, `applock_prompt_title_change`, `applock_prompt_title_unlock` (ux-flows 9.2).
- **Build / manifest:**
  - `gradle/libs.versions.toml` and `app/build.gradle.kts` (androidx.biometric 1.1.0)
  - `app/src/main/AndroidManifest.xml`: `USE_FINGERPRINT` with `tools:node="remove"`; `USE_BIOMETRIC` arrives through the merge.
- **Write set (tests):**
  - `T/data/privacy/applock/AndroidDeviceAuthCapabilityProviderTest.kt` (Robolectric SDK 26/28/29/30/31/34)
  - `T/ui/applock/BiometricPromptOutcomeMappingTest.kt`
  - `T/ui/applock/BiometricPromptCopyTest.kt` (new)
    - title per copy;
    - subtitle only for ENABLE;
    - description never set;
    - authenticators `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`.
- **Depends on:** G1a. Release: re-check manifest edits from R17 and C5.
- **Guards / approvals:** FG-22 (exact permission policy), FG-20, FG-18. **Owner approval required:** the new permission and the new dependency.
- **Gates:** privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
- **Validation:**
  - `compile`
  - `assemble-debug` (the reviewer then reads the merged manifest read-only)
  - `targeted-unit-test` `*AndroidDeviceAuthCapability*`
  - `targeted-unit-test` `*BiometricPromptOutcomeMapping*`
  - `targeted-unit-test` `*BiometricPromptCopy*`
  - shards `data`, `runtime-ui`
  - `lint`
- **Release state:** dormant. Recommendation: do not ship S15 in a public build before S22, because the new permission would appear with no feature behind it.
- **Rollback:** revert the dependency and the manifest line together.

### S16 — AppLockController core: resolution, lock/unlock, timer, inert escapes, attempt machinery
- **Goal / invariants:**
  - INV-24, INV-25 (including the AMD-22 note), INV-28, INV-32, INV-33, INV-34 (including AMD-22), INV-36, INV-37.
  - INV-54 (controller part):
    - purpose-bound attempt ids, each with its origin (2.4);
    - at most one prompt in flight across all purposes;
    - no time accrues while a prompt is shown;
    - `promptBackgroundAt` recording (T32);
    - non-success handoff (T34);
    - host loss for every purpose (T35).
  - Transitions: T1–T16; T6b (AMD-22: not while AUTHENTICATING(UNLOCK)); T9 and T10 returning to the origin; T12; T32; T34; T35.
  - 3.4 pending-background consumption (AMD-22):
    - every foreground evaluation (T14/T15/T16, from APP_FOREGROUNDED or LOCK_QUERY) sets `lastBackgroundedAt := null`;
    - every auth success that ends in an open state clears it.
  - The illegal transitions in spec 3.3 that apply here, including the AMD-22 entries:
    - a purpose entered from the wrong origin, or any prompt from INERT/RESOLVING;
    - a cross-purpose outcome;
    - T34 skipped;
    - AUTHENTICATING outliving its host.
  - The timer of the fail-closed record F.
- **Scope boundary:**
  - ENABLE/CONFIRM_CHANGE attempts are started through an internal controller API, which S18 binds to the settings requests. S16 tests drive that API with a no-op change.
  - T33 (a success applies its owning row, plus the unlock-equivalent discard) is S18.
  - Held-link release at prompt end is S17.
  - No new reason codes: T35 records `LOCK_AUTH_CANCELLED`.
- **Acceptance examples:**
  - AE-38 (controller: `LOCK_SHOWN_COLD_START`, no Room)
  - AE-40, AE-41 (semantics)
  - AE-42 (controller part: an UNLOCK success clears `lastBackgroundedAt`; no loop)
  - AE-43
  - AE-44, AE-45, AE-46, AE-47
  - AE-48, AE-49, AE-50, AE-51, AE-52, AE-53, AE-54
  - AE-61, AE-63, AE-68 (isolation), AE-90
  - AE-116 (timer part, via the internal attempt API):
    - a 10-minute foreground prompt accrues nothing;
    - Home during the prompt, then the system cancel, gives the 59.999 s / 60.000 s boundary;
    - both callback orders.
  - AE-118 (controller):
    - host loss for each purpose;
    - a late success for the abandoned id is stale, even after a re-lock;
    - a rotation keeps the attempt.
- **Assumes TECH-3.** Events come from the S20 binder and the S20 prompt host. The controller uses ports only.
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt` (new process singleton; the only writer of lock state; holds `lastBackgroundedAt`, `inFlightAttemptId` with purpose and copy, and `promptBackgroundAt`)
  - `M/domain/privacy/applock/AppLockState.kt` (new; `AUTHENTICATING(attemptId, purpose)` with purpose → origin)
  - `M/domain/privacy/applock/LockNotice.kt` (new; type only)
  - `M/domain/privacy/applock/MonotonicClock.kt` (new port)
  - `M/data/privacy/applock/SystemMonotonicClock.kt` (new)
  - `M/di/AppLockModule.kt`
  - `M/domain/diagnostics/DiagnosticReasonCode.kt` (all lock codes from spec §10)
- **Write set (tests):** in `T/domain/privacy/applock/`:
  - `AppLockControllerResolutionTest.kt`
  - `AppLockControllerTimerTest.kt` (including pending-background consumption)
  - `AppLockControllerAuthOutcomeTest.kt`
  - `AppLockControllerPromptOutcomeTest.kt` (new: purpose binding, origin return, T32, T34 in both callback orders, T35 for every purpose, rotation vs host loss)
  - `AppLockControllerInertTest.kt`
  - `AppLockControllerIllegalTransitionTest.kt` (including the AMD-22 entries)
  - `AppLockControllerIsolationTest.kt` (subject to TECH-10)
- **Legal path preserved:** the new APP LOCK path. No Room, no dependency on barriers.
- **Depends on:** S14, S15.
- **Guards:** FG-10, FG-16, FG-17 (`time_boundaries`), FG-14.
- **Gates:** privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
- **Validation:**
  - `targeted-unit-test` `*AppLockController*`
  - shard `domain-n-z`
  - `registered-guard` `time_boundaries`, `cancellation`
- **Split seam:** (i) resolution, inert and T6b; (ii) timer, auth outcomes, and the attempt machinery (T32/T34/T35).
- **Release state:** dormant.

### S17 — AppLockController: held deep link and LOCK_QUERY
- **Goal / invariants:**
  - INV-30, INV-31; T20 (AMD-22: held during AUTHENTICATING of any purpose), T21, T22; OD-7a.
  - INV-54 (link part): a link held during an open-origin prompt is released through LOCK_QUERY when the prompt ends, after T33 or T34.
- **Acceptance examples:**
  - AE-55, AE-56, AE-57, AE-58, AE-59, AE-60 (controller level)
  - AE-118 (link part: the budget link is applied once, whether it arrived before or after the host loss)
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt`
  - `M/domain/privacy/applock/HeldDeepLink.kt` (new; memory only; never in a Bundle or log)
- **Write set (tests):** `T/domain/privacy/applock/AppLockControllerHeldLinkTest.kt` (including hold during open-origin prompts and release after T34 and after a stubbed T33 via the internal attempt API)
- **Depends on:** S16, S3.
- **Gates:** privacy-security-guardian, reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockControllerHeldLink*`
  - `targeted-unit-test` `*AppLockController*`
  - shard `domain-n-z`

### S18 — AppLockController: settings operations
- **Goal / invariants:**
  - INV-3 (OD-8a), INV-23 (enable path), INV-45, INV-47, INV-48.
  - INV-51: INERT and REVIEW_SETTINGS notice derivation. (AMD-22) During AUTHENTICATING(ENABLE | CONFIRM_CHANGE) the origin's notice is derived unchanged.
  - INV-13: lock-enable fires `DiscreetRepostTrigger`.
  - INV-54 (T33): an ENABLE/CONFIRM_CHANGE success applies only its own owning row (T17, T18, T27, the INV-3 weakening write; T31 is in S25), including that row's write-failure outcome.
    - If the prompt is unlock-equivalent, `promptBackgroundAt` is discarded and `lastBackgroundedAt := null`. In 1b every prompt is unlock-equivalent: ENABLE, and CONFIRM_CHANGE in DEVICE_AUTH.
    - Then the held link is released (S17).
  - T17, T18, T19, T26, T27, T28, T29 (AMD-22: a load state kept during an open-origin prompt is applied by T28 once the prompt ends in an open state), T33.
  - Prompt-copy selection: ENABLE → `ENABLE`; T18 → `DISABLE`; longer timeout and weakening review-save → `CHANGE`.
  - **TECH-13** (per the S0 verdict; recommended (a)): in INERT a weakening change returns `Rejected(AUTH_UNAVAILABLE)` with no write, no prompt and no state change. Equal or shorter timeouts commit without auth.
- **Acceptance examples:**
  - AE-36, AE-37, AE-70
  - AE-85, AE-86, AE-87, AE-89
  - AE-93, AE-97, AE-98
  - AE-99 (1b rows), AE-101, AE-103
  - AE-102 (the lock-enable trigger)
  - AE-113 (controller: ENABLE success → UNLOCKED plus `LOCK_ENABLED`; cancel → DISABLED, nothing written)
  - AE-114 (controller: DISABLE copy; success → DISABLED; write failure → stays UNLOCKED plus `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)`)
  - AE-115 (derivation: the REVIEW_SETTINGS notice stays derived during a weakening review-save prompt)
  - AE-116 (end to end: a request for M5 that is cancelled keeps M1)
  - AE-117 (controller: OEM stop during the prompt, success before or after APP_FOREGROUNDED, M5 committed, no lock; the ENABLE variant with stored IMMEDIATE gives UNLOCKED)
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt`
  - `M/domain/privacy/applock/AppLockSettingsOperations.kt` (new, optional split in the same directory)
- **Write set (tests):** in `T/domain/privacy/applock/`:
  - `AppLockControllerEnableDisableTest.kt`
  - `AppLockControllerOpenOriginSuccessTest.kt` (new: T33, unlock-equivalent discard, both callback orders, link release, copy selection)
  - `AppLockControllerInertWeakeningTest.kt` (new: TECH-13)
  - `AppLockControllerReviewSaveTest.kt`
  - `AppLockControllerRereadTest.kt` (including T29 during an open-origin prompt)
  - `AppLockControllerForcedValueTest.kt`
  - `AppLockControllerNoticeTest.kt` (including the origin notice during open-origin prompts)
- **Depends on:** S17 (same file), S9, S15 (`AuthPromptCopy`); the TECH-13 verdict (seam (i)).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockController*`
  - shard `domain-n-z`
- **Split seam:** (i) enable, disable, weakening (including TECH-13), forced values, T33 machinery; (ii) review-save (including its T33 binding), re-read, notices.

### S19 — AppLockGate, lock surface and notice slot (Compose, no Hilt)
- **Goal / invariants:**
  - INV-26 (AMD-22): no composition in RESOLVING, LOCKED and AUTHENTICATING(UNLOCK) only.
  - INV-53 (gate parts), during AUTHENTICATING(ENABLE | CONFIRM_CHANGE):
    - the gate renders the origin; the `MainScreen` content slot stays composed and is never disposed or rebuilt;
    - the lock surface is not composed;
    - the notice slot shows the origin's notice;
    - operational precedence follows the origin's 3.6 row;
    - the gate starts nothing because of the prompt (no clipboard read, no permission prompt, no navigation, no deep-link lookup).
  - INV-27 (spec 3.6, including the AMD-22 open-origin row).
  - INV-34: Unlock is disabled while AUTHENTICATING(UNLOCK) and enabled again after T9, T10 or T35; no auto-prompt.
  - INV-35, INV-51 (rendering), INV-52.
- **Acceptance examples:**
  - AE-38 (the content slot is composed 0 times in RESOLVING, LOCKED and AUTHENTICATING(UNLOCK); AMD-22)
  - AE-44, AE-46 (UI)
  - AE-62, AE-69
  - AE-99 (no notice in RESOLVING, LOCKED and AUTHENTICATING(UNLOCK)), AE-101, AE-108
  - AE-113 (gate):
    - the slot composition count stays 1 across entering and leaving AUTHENTICATING(ENABLE), with no dispose;
    - a `LaunchedEffect(Unit)` probe runs once;
    - a non-saveable `remember` probe is kept;
    - the lock surface is composed 0 times.
  - AE-114 (gate: the slot stays composed during CONFIRM_CHANGE)
  - AE-115 (the notice slot keeps the origin notice without removal during CONFIRM_CHANGE; the UNLOCK prompt composes the slot 0 times and shows no notice)
  - AE-118 (UI: Unlock is enabled again after host loss)
- **Write set (source):**
  - `M/ui/applock/AppLockGate.kt`
  - `M/ui/applock/AppLockSurface.kt`
  - `M/ui/applock/LockNoticeBanner.kt`
- **Resources:** `R/values{,-el,-es}/strings_applock.xml` (lock surface, paused banners, review banner)
- **Write set (tests):**
  - `T/ui/applock/AppLockGateTest.kt`
  - `T/ui/applock/AppLockGateOpenOriginPromptTest.kt` (new; composition count, dispose and effect probes, notice continuity, operational row per origin, no gate callbacks fired on prompt transitions)
  - `T/ui/applock/AppLockSurfaceTest.kt`
  - `T/ui/applock/LockNoticeBannerTest.kt` (`createComposeRule`; TalkBack; 200% font scale)
- **Depends on:** S16 (state, purpose and notice types). Can run in parallel with S17 and S18. FLAG_SECURE is applied in S20, not here.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockGate*`
  - `targeted-unit-test` `*AppLockSurface*`
  - `targeted-unit-test` `*LockNoticeBanner*`
  - shard `runtime-ui`
  - `lint`

### S20 — MainActivity shell integration
- **Goal / invariants:**
  - INV-24, INV-25 (binder), INV-26, INV-27, INV-29; OD-9a.
  - R2: the POST_NOTIFICATIONS prompt runs at most once per process (AE-39) and keeps R24's rules.
  - A prompt host for all purposes.
  - INV-53 (shell part): FLAG_SECURE follows committed state only (2.3 `effectiveSecureScreen`) and is never set or cleared by AUTHENTICATING of any purpose:
    - for ENABLE it is set from the frame after the enable write commits;
    - a weakening change clears it only after its write commits;
    - a persist failure leaves it set.
    - The prompt host passes the controller's constant `AuthPromptCopy`.
  - INV-54 (signals):
    - the binder reports every real stop during prompts, with no suppression (TECH-3);
    - the prompt host emits `PROMPT_HOST_LOST(attemptId)` exactly once when the host Activity is destroyed with `isChangingConfigurations == false` while an attempt is in flight with no outcome;
    - on rotation the host re-attaches the attempt, and a matching success on the recreated Activity is accepted.
- **Acceptance examples:**
  - AE-38 (FLAG_SECURE before the first frame; no `ReviewViewModel`, no clipboard read, no permission dialog)
  - AE-39, AE-41 (configuration change, shade and split-screen are not backgrounding)
  - AE-61, AE-62, AE-63
  - AE-42, AE-66, AE-67 (device)
  - AE-113 (FLAG_SECURE not set during an ENABLE prompt while the committed lock is off, and set from the next frame after the commit; the POST_NOTIFICATIONS effect does not run again)
  - AE-114 (FLAG_SECURE stays set for the whole CONFIRM_CHANGE, is cleared only after the commit, and stays set on write failure)
  - AE-116 (binder: Home during a settings prompt gives APP_BACKGROUNDED; device)
  - AE-117 (binder: an OEM credential UI stopping the host; device)
  - AE-118 (host loss: a CLEAR_TASK notification tap that finishes the host gives one PROMPT_HOST_LOST; rotation gives none)
- **Assumes TECH-1 A and TECH-3.**
- **Write set (source):**
  - `M/ui/MainActivity.kt`
    - the class declaration;
    - FLAG_SECURE set before `setContent`;
    - `AppLockGate` wraps `MainScreen` inside `ProvideNavigationController`, with a `SaveableStateHolder` (still needed for T14 re-locks);
    - attaching the prompt host;
    - a once-per-process guard on the `:471-478` effect.
  - `M/ui/applock/AppLockLifecycleBinder.kt` (new)
  - `M/ui/applock/AppLockPromptHost.kt` (new):
    - launches the authenticator for the in-flight attempt with its copy;
    - maps outcomes to AUTH_* with the attempt id;
    - emits PROMPT_HOST_LOST;
    - re-attaches on rotation.
  - `M/ui/applock/SecureWindowController.kt` (new)
  - `M/ui/applock/NotificationPermissionPromptOnce.kt` (new)
- **Write set (tests):**
  - `T/ui/applock/AppLockLifecycleBinderTest.kt` (including stops during prompts and both orders relative to outcome callbacks)
  - `T/ui/applock/AppLockPromptHostTest.kt` (new: host loss vs rotation, exactly-once emission, stale outcome after loss, copy pass-through)
  - `T/ui/applock/SecureWindowControllerTest.kt` (including committed-only cases across prompt open and close)
  - `T/ui/applock/MainScreenCallSiteTest.kt` (TECH-10)
  - plus a run of the pinned `GsonR8KeepRulesContractTest`
- **Legal path preserved:** `MainScreen` is invoked only from the gate. The operational lock inside `MainScreen` is unchanged.
- **Depends on:** S15, S16, S19. Release: **R24 and R19 landed; R18 HZ-03 F1 checked.**
- **Guards:** FG-12, FG-16, FG-22 (runtime), the GsonR8 pin.
- **Gates:** architecture-guardian (lead: host, cross-module), privacy-security-guardian, reviewer-strict.
- **Validation:**
  - `compile`
  - `targeted-unit-test` `*AppLockLifecycleBinder*`
  - `targeted-unit-test` `*AppLockPromptHost*`
  - `targeted-unit-test` `*SecureWindowController*`
  - `targeted-unit-test` `*MainScreenCallSite*`
  - `targeted-unit-test` `*GsonR8KeepRulesContractTest*`
  - `targeted-unit-test` `*MainViewModel*`
  - shard `runtime-ui`
  - `registered-guard` `ui_dao`
- **Split seam:** (i) host, binder, FLAG_SECURE; (ii) gate wrap, state holder, prompt host (including host loss), permission-once.
- **Release state:** the lock itself is dormant, but every user now sees a brief RESOLVING surface with FLAG_SECURE while settings resolve (R14). Device check at G1b.
- **Rollback:** clean revert while no user can enable the lock (before S22).

### S21 — MainActivity deep-link hold integration
- **Goal / invariants:** INV-30, INV-31, INV-26 (no deep-link lookup or write while not open, nor because of an open-origin prompt), INV-14.
- **Acceptance examples:**
  - AE-55, AE-56, AE-57, AE-58, AE-59, AE-60 (integration)
  - AE-118 (integration: a budget CLEAR_TASK tap during a settings prompt is held, the host loss ends the attempt, and Home is applied exactly once)
- **Write set (source):**
  - `M/ui/MainActivity.kt` (`onCreate`/`onNewIntent` → policy → LOCK_QUERY → hold or apply)
  - `M/ui/navigation/DeepLinkApplier.kt` (applies on release from hold; `afterUnlock` flag)
- **Write set (tests):**
  - `T/ui/navigation/DeepLinkApplierHoldTest.kt`
  - `T/ui/navigation/DeepLinkApplierTest.kt` (modify if signatures change)
- **Depends on:** S17, S20. Release: as S20.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*DeepLinkApplier*`
  - `targeted-unit-test` `*GsonR8KeepRulesContractTest*`
  - shard `runtime-ui`

### S22 — Settings UI, 1b rows (1b user switch)
- **Goal / invariants:**
  - INV-23 (unavailable variants by API and capability)
  - INV-3 / OD-8a (prompts on weakening changes)
  - INV-45 (review card; in INERT the lock choice is off-only)
  - INV-48 (forced rows are read-only)
  - OD-20a (secure-screen row on API 26+)
  - the API 30 note (UQ-3)
  - INV-53 (UI part), following ux-flows 4.2 `Authenticating(purpose)`:
    - the switch is disabled and "Waiting for confirmation..." (`privacy_app_lock_waiting`) is shown;
    - the system prompt is drawn over the still-composed settings screen;
    - ViewModel and section state are never reset by entering or leaving a prompt.
  - Outcomes (ux 4.2 `AuthCancelled(purpose)`):
    - cancel copy "App lock was not turned on." for ENABLE only;
    - host loss (T35) re-enables the switch with no message;
    - a background cancel (T34) returns with nothing saved and no message.
  - TECH-13 outcome: the timeout row and `AutoLockTimeoutDialog` in INERT, with copy from ux-flows after S0.
- **Acceptance examples:**
  - AE-34, AE-35, AE-36 (UI)
  - AE-54 (the paused row is off-only)
  - AE-65, AE-70, AE-85, AE-86, AE-87, AE-93 (UI), AE-103 (UI)
  - AE-113 (UI: cancel copy; a `remember` probe in the section survives the prompt states)
  - AE-114 (UI: disable dialog → prompt → result)
  - AE-116 (UI: the timeout still reads M1 on return)
  - AE-118 (UI: the switch is enabled again)
- **Write set (source):**
  - `M/ui/screens/privacysettings/AppLockPrivacySection.kt`
  - `M/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt`
  - new dialogs in the same package: `AppLockEnableDialog.kt`, `AutoLockTimeoutDialog.kt`, `AppLockDisableDialog.kt`
  - `R/values{,-el,-es}/strings_privacy_section.xml` (add the 1b strings, including `privacy_app_lock_waiting` and the TECH-13 INERT note if (a) or (c))
- **Write set (tests):** modify `AppLockPrivacySettingsViewModelTest.kt` and `AppLockPrivacySectionTest.kt`; new `AppLockDialogsTest.kt` (including the INERT timeout behaviour under TECH-13).
- **Depends on:** S18, S20, S21; the TECH-13 verdict and its ux copy (seam (ii)). Release: R17, R23, R19 landed.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockPrivacy*`
  - `targeted-unit-test` `*AppLockDialogs*`
  - `targeted-unit-test` `*PrivacySettings*`
  - shard `runtime-ui`
  - `lint`
  - `registered-guard` `ui_dao`
- **Split seam:** (i) lock switch, unavailable variants, enable/disable, `Authenticating(purpose)` states; (ii) timeout (including TECH-13), forced rows, secure toggle, review card.
- **Release state:** 1b is live.
- **Rollback:** after release, use a forward fix only. The rollback unit would be {S22, S21, S20} plus the S14 lock term: reverting S22 alone strands users behind a lock they cannot turn off.

### S23 — Docs (1b)
- **Write set (docs):**
  - `LEGAL_PATHS.md`:
    - APP LOCK section, including: open-origin prompts keep `MainScreen` composed (INV-53); purpose-bound attempts; PROMPT_HOST_LOST; the timer handoff (INV-54); the TECH-13 rule;
    - DEEP LINKS updated with the hold.
  - `CODEBASE_SEGMENTS.md`: Segment 28 lock owners; an ownership note for the UI shell gate and prompt host (`MainActivity` has no segment today).
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
    - AE-113/AE-114: no rebuild flash or replayed permission dialog across enable and disable prompts; FLAG_SECURE holds during the disable prompt (recents, screenshot)
    - AE-116: Home during a settings prompt, returning before and after the timeout
    - AE-117: an OEM credential UI during a settings prompt, alongside AE-42
    - AE-118: a notification tap with CLEAR_TASK during a settings prompt; rotation during a settings prompt
    - R3: re-lock with system screens (SAF, camera, share sheet, permission dialogs)
    - R14: RESOLVING flash
- If APP-3 is denied, 1b stays PARTIAL. The minimum is API 30 + API 31.
- **Reviews:** as at G1a.

---

## 4. Increment 2 — optional app PIN

**Conditional on OD-1a with OD-15a and OD-16a.** Preconditions:
- G1b accepted.
- The owner re-confirms Inc 2 after 1b ships.
- OD-19/C5 status recorded (if there is no `dataExtractionRules`, protection relies on the OD-15a hard lock).
- UQ-1 and UQ-4 confirmed.
- The Inc 2 preflight re-checks write sets. Recommended: the PIN screens are sub-screens inside Privacy Settings, with no new `NavigationDestination`, avoiding nav files touched by R19.

### S24 — AppPinStore
- **Goal / invariants:** INV-38, INV-39 (schedule, durable-before-result, boot-aware, shared counter, repair), INV-42 (store), INV-1, INV-51 (flag persistence); OD-13a, OD-14.
- **Acceptance examples:** AE-71, AE-73 (math), AE-74, AE-75, AE-84, AE-91, AE-92, AE-100 (flag), AE-106.
- **Write set (source):**
  - `M/domain/privacy/applock/pin/AppPinStore.kt`
  - `M/domain/privacy/applock/pin/PinAttemptPolicy.kt`
  - `M/domain/privacy/applock/pin/PinFormatPolicy.kt`
  - `M/domain/privacy/applock/pin/BootCountSource.kt`
  - `M/data/privacy/applock/pin/AppPinStoreImpl.kt`
    - Its own app-private store.
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
  - INV-54 [2], "PIN steps are not prompts":
    - PIN confirmations in effective APP_PIN mode are in-app steps inside UNLOCKED (no AUTHENTICATING state, no `promptBackgroundAt`);
    - a background during a step is an ordinary T13, and T14 may re-lock; the step's change is not applied.
    - T31's device prompt is a CONFIRM_CHANGE in DEVICE_AUTH, so it is unlock-equivalent and T33 discards the background.
- **Acceptance examples:**
  - AE-72, AE-74, AE-77 (logic), AE-78, AE-79, AE-80 (logic), AE-81, AE-82, AE-83
  - AE-91, AE-92, AE-94, AE-95, AE-96, AE-99 (PIN), AE-107, AE-110
  - AE-119 (controller: Home during a PIN step at t=0; return at 60 s → LOCKED(TIMEOUT), nothing written; return at 30 s → UNLOCKED)
- **Write set (source):**
  - `M/domain/privacy/applock/AppLockController.kt`
  - `M/domain/privacy/applock/LockNotice.kt`
  - `AppLockSettingsOperations.kt` (if present)
- **Write set (tests):**
  - `T/domain/privacy/applock/AppLockControllerPinTest.kt`
  - `T/domain/privacy/applock/AppLockControllerPinOrderingTest.kt` (process killed between steps)
  - `T/domain/privacy/applock/AppLockControllerPinStepTest.kt` (new: AE-119; a PIN step never enters AUTHENTICATING)
- **Depends on:** S24.
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*AppLockControllerPin*`
  - `targeted-unit-test` `*AppLockController*`
  - shard `domain-n-z`

### S26 — Fingerprint unlock in PIN mode (OD-16a)
- **Goal / invariants:**
  - INV-40: BIOMETRIC_STRONG only, never device credential; "Use PIN" negative button.
  - INV-51: one write turns fingerprint unlock off and sets the flag.
  - INV-39: a fingerprint success resets the counter.
  - INV-54 [2]: if the design shows a system prompt when fingerprint unlock is enabled (key binding), that prompt runs in APP_PIN mode and is **not** unlock-equivalent. After its success the change applies, and the recorded background goes to the timer (T33). T35 applies to every fingerprint prompt.
  - Prompt copy is constant: `applock_pin_prompt_title`, negative `applock_pin_prompt_negative` (INV-35/INV-53 limits).
- **Acceptance examples:**
  - AE-76, AE-100 (trigger), AE-72 (prompt configuration)
  - AE-120 (conditional: if the design uses no system prompt to enable fingerprint unlock, AE-120 is recorded N/A with architecture-guardian sign-off)
- **Assumes TECH-1 A** (androidx `CryptoObject`). Under B: the platform `CryptoObject`.
- **Write set (source):**
  - `M/data/privacy/applock/pin/BiometricPinKeyManager.kt` (new; Keystore key with user-auth required, invalidated by new enrolment)
  - `M/ui/applock/BiometricPromptDeviceAuthenticator.kt`
  - `M/domain/privacy/applock/DeviceAuthenticator.kt` (adds the PIN-mode fingerprint copy values)
  - `M/domain/privacy/applock/AppLockController.kt` (invalidation hook; the non-unlock-equivalent T33 branch)
- **Resources:** `R/values{,-el,-es}/strings_applock_pin_prompt.xml` (new: `applock_pin_prompt_title`, `applock_pin_prompt_negative`), kept separate from S27's file so the two can run in parallel.
- **Write set (tests):**
  - `T/data/privacy/applock/pin/BiometricPinKeyManagerTest.kt`
  - `T/domain/privacy/applock/AppLockControllerPinBiometricTest.kt` (including AE-120 when applicable)
- **Depends on:** S25. Device check: AE-76 via `adb emu finger` on API 30+ (APP-3).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*BiometricPinKeyManager*`
  - `targeted-unit-test` `*AppLockControllerPinBiometric*`
  - shards `data`, `domain-n-z`, `runtime-ui`
  - `lint`

### S27 — PIN UI (Inc 2 user switch)
- **Goal / invariants:**
  - INV-38 (the PIN is never in a String, Bundle or `SavedStateHandle`)
  - INV-42 (no in-app reset)
  - INV-43
  - INV-50 (row unavailable in INERT and DISABLED)
  - UQ-1 (in-app pad), UQ-4
  - PIN-mode turn-off copy
  - INV-54 [2] (UI): the `PinStep(purpose)` UI state is an in-app screen inside the unlocked app (ux-flows 4.2), not a system prompt and without "Waiting for confirmation...". After a re-lock and unlock, the step starts empty and nothing has changed.
- **Acceptance examples:** AE-72, AE-77 (copy), AE-82, AE-83, AE-104 (no backup call; "Back up now" only navigates), AE-107, AE-119 (UI: no digit in any saved-state Bundle; the step starts empty after unlock).
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
  - `T/ui/screens/privacysettings/applockpin/PinSetupFlowTest.kt` (including AE-119 UI)
- **Depends on:** S25 (S26 optional; the two can run in parallel because the write sets are disjoint).
- **Gates:** privacy-security-guardian (lead), reviewer-strict, architecture-guardian.
- **Validation:**
  - `targeted-unit-test` `*PinPad*`
  - `targeted-unit-test` `*PinLockSurface*`
  - `targeted-unit-test` `*AppPinSettings*`
  - `targeted-unit-test` `*PinSetupFlow*`
  - `targeted-unit-test` `*AppLockGate*`
  - shard `runtime-ui`
  - `lint`
- **Split seam:** (i) lock surface and gate; (ii) settings, setup, change, remove, PIN steps.
- **Rollback:** after release, forward fix only. Reverting would silently drop PIN users back to device-auth protection.

### S28 — Docs (2)
- `LEGAL_PATHS.md` (APP LOCK: PIN ordering; PIN steps are not prompts)
- `CODEBASE_SEGMENTS.md` (Segment 28)
- UI maps
- `CODEBASE_INVENTORY.md`
- a release note (a forgotten PIN means clear data or restore an exported backup)

### G2 — Increment 2 gate
- **Runner:** as G1b, including the evidence run (proves no permission drift).
- **Device checks:** Keystore binding, reboot delay (AE-73), enrolment invalidation (AE-76), simulated Keystore loss (AE-80), PIN step plus background (AE-119), AE-120 if applicable.
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
  - C: S5 (needs the TECH-12 verdict)
  - D: {S6 ∥ S7 ∥ S8 ∥ S11} (S8 needs R21)
  - E: S9
  - F: S12
  - then S13 → G1a
- **1b:**
  - A: {S14 ∥ S15}
  - B: S16
  - C: {S17 ∥ S19}
  - D: {S18 ∥ S20} (S18 seam (i) needs the TECH-13 verdict)
  - E: S21
  - F: S22 (seam (ii) needs the TECH-13 verdict and ux copy)
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

## 7. Traceability (all 54 INV and 121 AE mapped)

**Invariants**

| Slice(s) | INV |
|---|---|
| S1 | 1, 2, 44, 46 |
| S2 | 18 |
| S3 | 14, 15, 17, 21 |
| S4 | 4, 6, 9, 10, 12 |
| S5 | 4, 5, 7, 8, 12, 13 (marker, post side), 19 |
| S6, S7 | 4, 9, 11, 12 |
| S8 | 4, 7, 8, 9, 10, 11, 12, 13 (marker, post side) |
| S9 | 12, 13, 46 |
| S10 | 14, 16, 18, 22 |
| S11 | 20 |
| S12 | 44, 46, 51 |
| S14 | 2, 6, 10, 44 |
| S15 | 23, 34, 53 (prompt text) |
| S16 | 24, 25, 28, 32, 33, 34, 36, 37, 54 |
| S17 | 30, 31, 54 (link release) |
| S18 | 3, 13, 23, 45, 47, 48, 51, 54 (T33) |
| S19 | 26, 27, 34, 35, 51, 52, 53 |
| S20 | 24, 25, 26, 27, 29, 53 (FLAG_SECURE), 54 (signals) |
| S21 | 14, 26, 30, 31 |
| S22 | 3, 23, 45, 48, 53 (UI part) |
| S24 | 1, 38, 39, 42, 51 |
| S25 | 39, 40, 41, 43, 49, 50, 51, 54 (PIN steps) |
| S26 | 39, 40, 51, 54 (non-unlock-equivalent prompt) |
| S27 | 38, 42, 43, 50, 54 (PinStep UI) |

Coverage check: INV-1…INV-54 each appear at least once (INV-16: S10; INV-20: S11; INV-29: S20; INV-52: S19; INV-53: S15/S19/S20/S22; INV-54: S16/S17/S18/S20/S25/S26/S27).

**Acceptance examples**

| Slice(s) | AE |
|---|---|
| S1 | 7, 51, 85, 88, 89, 105 (store parts) |
| S2 | 21, 33 |
| S3 | 19, 23, 25–31 (parse level) |
| S4 | 1–6 (decision rows), 7–10, 12, 109 and 111 (decision rows) |
| S5 | 3, 13, 14 (device), 32, 109, 111 (builder/texts), 112 and 121 (post side: marker) |
| S6 | 3, 4, 6, 11, 13 |
| S7 | 5, 11, 13, 111 (worker) |
| S8 | 1, 2, 7, 9, 11, 12, 13, 121 (post side: BILL marker) |
| S9 | 15, 102, 105, 112, 121 |
| S10 | 17–27, 29, 31 |
| S11 | 16 |
| S12 | 85, 88, 89, 105 (UI) |
| S14 | 51, 54, 64, 65 |
| S15 | 34, 35, 45, 50, 93, 114 (prompt copy) |
| S16 | 38, 40, 41, 42 (controller), 43–54, 61, 63, 68, 90, 116 (timer), 118 (controller) |
| S17 | 55–60, 118 (link) |
| S18 | 36, 37, 70, 85–87, 89, 93, 97–99, 101–103, 113–117 (controller) |
| S19 | 38, 44, 46, 62, 69, 99, 101, 108, 113, 114, 115, 118 (UI) |
| S20 | 38, 39, 41, 42, 61–63, 66, 67, 113, 114 (FLAG_SECURE), 116, 117 (binder/device), 118 (host loss) |
| S21 | 55–60, 118 (integration) |
| S22 | 34–36, 54, 65, 70, 85–87, 93, 103, 113, 114, 116, 118 (UI) |
| S24 | 71, 73–75, 84, 91, 92, 100, 106 |
| S25 | 72, 74, 77–83, 91, 92, 94–96, 99, 107, 110, 119 (controller) |
| S26 | 72, 76, 100, 120 (conditional) |
| S27 | 72, 77, 82, 83, 104, 107, 119 (UI) |

Coverage check: AE-1…AE-121 each appear at least once (AE-8: S4; AE-28 and AE-30: S3; AE-64: S14; AE-78: S25; AE-104: S27; AE-108: S19; AE-110: S25; AE-111…AE-121 as listed). The provisional AE-5r of v1 is retired: it is AE-111.

## 8. Human decisions required (plan-gate checklist for the owner)

- [ ] Release 1.0 accepted; R17/R21/R24 landed; R18/R19/R23 status recorded; post-R24 tip SHA recorded; feature branch off the release line.
- [ ] **TECH-1 and TECH-2** decided by architecture-guardian with privacy-security-guardian (recommended: A and (a)), plus TECH-3..11.
- [ ] **TECH-12** (= spec/gate2 TECH-3, kind marker) decided by architecture-guardian and reviewed by privacy-security-guardian. Recommended: (a). If (b) is chosen, the owner acknowledges the budget-alert id/dedupe change.
- [ ] **TECH-13** (weakening change while INERT) decided by architecture-guardian with privacy-security-guardian. Recommended: (a), reject. If (b) is chosen, the owner acknowledges the narrowing of OD-8a. The domain-modeler records the outcome in the spec, and the ux-designer adds the INERT timeout-row state and copy, before S18/S22.
- [ ] **New permission `USE_BIOMETRIC`** approved (needed under both TECH-1 options; FG-22 exact permission policy).
- [ ] **New dependency androidx.biometric 1.1.0** approved, with `USE_FINGERPRINT` stripped (only if TECH-1 A).
- [ ] **New legal paths** NOTIFICATION PRESENTATION, DEEP LINKS and APP LOCK (spec 8.2) approved for S13/S23/S28.
- [ ] **APP-1:** spec §8.3 guards deferred to SG (recommended). TECH-10 classification of the feature-scoped source-scan tests confirmed or refused.
- [ ] **APP-2:** acknowledge that no `db_ownership_policy` amendment is planned, because `deliverReminder`'s signature is kept. Any deviation stops S7.
- [ ] **APP-3:** emulator images for API 30/31/33/34 before G1b (and for the Inc 2 device checks).
- [ ] **APP-4:** OD-5a release-note text and where it goes. It ships with the first release that contains S8.
- [ ] **C6 and C7 choices** (§9).
- [ ] ux-flows follow-up pass landed before S5 (copy), S12, S19, S22 and S27, including the AMD-22 touch-up (§5 diagram, §3.8 rows, §4.2 note) before S19/S22. UQ-2 and UQ-3 confirmed before S19/S22; UQ-1 and UQ-4 before S27.
- [ ] Translations for el/es: agent-drafted with owner spot-check, or owner-provided.
- [ ] Acknowledge that `DeepLinkParserTest` `RequireConfirmation` cases are rewritten, not deleted (R11; spec flag S5).
- [ ] Acknowledge 1a shipping in 1.0.x ahead of 1b (OD-4a), and that S15's permission should not ship publicly before S22.
- [ ] Inc 2 go/no-go re-confirmation after G1b, with OD-19/C5 status.

## 9. Pre-existing issues the feature touches

| Issue | Where it is touched | Disposition |
|---|---|---|
| **C6** `notify()` without `SecurityException` handling (`AndroidNotificationService.kt:84,133,168`) | S5 (new methods), S9 (sweep) | **Left to the release track by default.** But AGENTS.md rule 2 binds new code, so the owner must choose. (i) **Fold in (recommended):** the new kind-aware methods catch the exception and return `PERMISSION_DENIED` (TECH-9), with a parity test per caller (warranty → `markFailed`, retryable; receipt keeps its `PERMISSION_REVOKED` diagnostic), so C6 closes when S6/S7 land. (ii) **Keep it out:** the new methods deliberately propagate like the legacy ones, with the AGENTS.md deviation documented, until the release track fixes C6. If the release track lands C6 first, S5 adopts it. The S9 sweep always catches (AE-102). |
| **C7** merchant and amount in `Timber.i` (`AnomalyAlertOrchestrator.kt:199-205`) | S6 edits `:193` next to it | **Left to the release track by default.** Owner option: fold into S6 as one hunk (constant bounded message), which is privacy-positive and changes no behaviour. Recommended if still open. |
| C4 raw deep-link value in diagnostics and briefing suppression | S10 | Covered by S10 unless the release track ships it first (then adopt). |
| C5 / OD-19 device-to-device transfer | Inc 2 value | Release track; not fixed here. |
| **F23** `BudgetMonitor` posts with the raw `budget.id.toInt()` (`:354,365,376 → :474`). Budget ids ≥10000 leave the reserved 1–9999 range and can collide with warranty or receipt notification ids. | S6 (ids unchanged), S9 (fallback classification) | Left as is. TECH-12 (a) keeps ids. The mislabel residual for marker-less pre-upgrade notifications is accepted per spec 4.6 (AE-121; content-free). The id collision itself is pre-existing and out of scope; it is a candidate note for the release track. |
| Stale `NotificationService.send()` in LEGAL_PATHS Anomaly Alerting | S13 | Corrected inside the section being edited (flagged). |
| Hard-coded "Snooze"/"Dismiss" | S8 | Moved to resources (ux 2.7 recommendation; in scope). |
| Unused `DeepLinkParser.RequireConfirmation` | S3 | Replaced; test modified. |
| `privacy_*` strings missing el/es; English channel names; English briefing title literal | — | Out of scope, not fixed. |
| Spec F19 was wrong (the Compose test dependency exists; corrected by AMD-20) | S19/S12 tests | No new test dependency. |

## 10. Risk register

| ID | Risk | Mitigation / slice |
|---|---|---|
| RP-1 | Merge contention in `MainActivity` (R24/R18/R19) | Start from the post-R24 tip; keep S10/S20/S21 hunks minimal (the prompt host lives in its own file); split seams |
| RP-2 | TECH-2 contract churn across 9 test files | Additive transition S5→S9; mechanical `coEvery`; S9 removal only after migration |
| RP-3 | `BillReminderWorker` collision with R21 | S8 after R21; S14 avoids a second edit via the applier; the marker is stamped by the applier, not by worker code |
| RP-4 | OD-5a visible change for all API 31+ users | The APP-4 note ships with S8's release |
| RP-5 | RESOLVING surface and FLAG_SECURE at every start for all users after S20 (spec R14) | Device check at G1b; an optional pre-warm needs a write-set extension (`MainApplication.kt`) |
| RP-6 | Unwanted re-locks or loops with system screens on API 30+ (R3) | The TECH-3 exact counter reports every real stop. The own-prompt exemption is decided by the controller (UNLOCK: the state stays LOCKED; ENABLE/CONFIRM_CHANGE: T32–T34, INV-54). Device lanes need APP-3. |
| RP-7 | FLAG_SECURE not inherited by a Compose window (AE-67) | Device check; no `SecureOff` anywhere |
| RP-8 | Robolectric fidelity for `getActiveNotifications`, including the round-trip of the kind marker in extras; SDK 35 not on the JVM | Device check of re-post and marker at G1a on API 35, including the pre-1a → 1a upgrade path |
| RP-9 | POST_NOTIFICATIONS replayed after unlock (R2) clashes with R24 | S20 once-per-process guard; R24 logic re-read; open-origin prompts no longer recompose `MainScreen` (INV-53) |
| RP-10 | API 30 lock users lose Snooze/Dismiss (R9) | Disclosed (UQ-3); in-app actions (scope flag S2) stay out |
| RP-11 | A late or slow settings read locks a lock-off user (T5) | Bounded by `T_resolve`; T6b releases without auth (AE-53) |
| RP-12 | Diagnostic codes travel in exported backups (OD-12a) | Codes/enums only; privacy-security-guardian acknowledgement in S2 |
| RP-13 | Spec/ux drift: 5 vs 6 kinds; AUTHENTICATING purpose | **Closed in v2:** AMD-17 and AMD-22 landed. Residual: the ux AMD-22 touch-up is verified in the S0 preflight. |
| RP-14 | The guard freeze leaves invariants without guard enforcement | Unit tests per INV; compile-time enforcement after S9; SG later |
| RP-15 | A slice exceeds 2+2 iterations (S5, S9, S16, S18, S20, S22 and S27 are the largest) | Pre-identified seams; hand back PARTIAL |
| RP-16 | Permission visible before the feature if S15 ships early | Ship 1b as one release (§8) |
| RP-17 | Inc 2: a forgotten PIN with no exported backup means data loss (R8); D2D (R6) | Setup warning; OD-15a hard lock; owner re-confirmation |
| RP-18 | Post-release rollback strands users (S12/S22/S27) | Forward fix; rollback units documented per slice |
| RP-19 | Translation quality or lint failures | Per-slice `lint`; owner decision on the translation source |
| RP-20 | The kind marker is readable by notification listeners and companion devices | Kind token only, the same information as the discreet title (OD-10a); never on the public version; privacy-security-guardian review of TECH-12 (S0, S5) |
| RP-21 | Host-loss misdetection (T35): a rotation treated as loss cancels a valid attempt, or a missed loss leaves Unlock or the switch disabled | `isChangingConfigurations` check in `AppLockPromptHost`; unit tests both ways; stale outcomes ignored (T12); device lane AE-118 (APP-3) |
| RP-22 | TECH-13 stays undecided | Blocks only S18 seam (i) and S22 seam (ii); decided in S0 before 1b starts |
| RP-23 | Timer handoff depends on callback order (cancel before or after APP_BACKGROUNDED; success before or after APP_FOREGROUNDED) | Controller tests in both orders (AE-116, AE-117); device lane with an OEM credential UI (APP-3) |
| RP-24 | Kind-marker tokens drift across releases (refactor rename or shrinker) | Tokens pinned by `NotificationKindMarkerTest`; an unknown token falls back to the table (AE-121) and stays content-free |

## 11. Deferred to later increments / out of scope

- Hide-amounts (separate brief; answers loan-installments OQ-14).
- Scope flags: S2 in-app Snooze/Dismiss for API 30; S8 last-export indicator; S9 in-app erase; S10 PIN below API 30; S11 PIN-only lock without a screen lock.
- Removing the VIEW filter (OD-3b; revisit after S5 and an external-caller check).
- App-wide `dataExtractionRules` (C5/OD-19, release track).
- API 34 accessibility sensitive-data flag; `RescueActivity`; bill notification content intent (spec flag S4).
- Spec §8.3 guards (SG, APP-1).
- RP-16 typed IDs for briefing/budget, including any `NotificationId.forBudget` adoption (TECH-12 (b) is not planned).
- Pre-existing el/es gaps in `privacy_*`.

---

Sources read for this revision (all read-only):
- `C:\Users\panos\Desktop\Cost-agregator\workflows\active\app-lock-privacy-mode-plan.md`
- `C:\Users\panos\Desktop\Cost-agregator\docs\features\app-lock-privacy-mode\domain-spec.md`
- `C:\Users\panos\Desktop\Cost-agregator\docs\features\app-lock-privacy-mode\gate2-decisions.md`
- `C:\Users\panos\Desktop\Cost-agregator\docs\features\app-lock-privacy-mode\impact-map.md`
- `C:\Users\panos\Desktop\Cost-agregator\docs\features\app-lock-privacy-mode\ux-flows.md`
- I spot-checked `app\src\main\java\com\yourname\expensetracker\data\service\AndroidNotificationService.kt` and `service\reminder\BillReminderWorker.kt`. The channel-id constants are `private` in both, which is why S8 exposes `CHANNEL_ID` as `internal`. I also checked the data → service import precedent: `data\repository\RecommendationRepository.kt`.

Notes for the orchestrator:
1. **AMD-22 ux touch-up.** The AMD-22 touch-up already appears in ux-flows (§3.8 lines 939–941, §4.2 lines 1054–1055, §5 lines 1135–1138). The S0 preflight only needs to confirm it is final.
2. **Placement choices beyond the deltas.** Each of the following is needed to implement an AMD-22 / TECH-12 rule, and each is a new file inside its slice's own write set:
   - S15 adds a new strings file and test for the INV-53 constant prompt copy.
   - S20 gets a separate `AppLockPromptHost.kt`.
   - S26 gets its own prompt strings file, so it stays disjoint from S27.
   - The S9 classifier is a new pure file, `NotificationKindClassifier.kt`.
3. **Possible owner escalations.**
   - If architecture-guardian picks TECH-13 (b), that narrows OD-8a and needs the owner's acknowledgement.
   - If architecture-guardian refuses the `data/service → service/reminder` import, S8/S9 need an approved write-set extension for a shared channel-constant holder.

## Orchestrator carry-forwards from the S5a reviews (2026-10-23; binding on later slices)
- **S5b:** move the legacy methods onto the S5a tap-intent helpers (one builder per tap target). Build the briefing URI with `Uri.Builder().appendQueryParameter("briefingKey", key)` (both paths; update expectedData). Use resource-only (`@StringRes`) action titles, or document that the caller must pass resource strings. S5 "Done when" / FG-22 / AE-32 for the legacy methods close only at S5b.
- **S6:** today a SecurityException from anomaly posting reaches AnomalyAlertOrchestrator's `catch (e: Exception)` (~:210). After rewiring it returns PERMISSION_DENIED silently, so S6 parity must choose the diagnostic and keep insert-then-post (TECH-9). C7 is folded into S6 (owner record item 8 note).
- **S7:** replace "the SecurityException catch … all stay" in the receipt bullet with an explicit branch PERMISSION_DENIED → RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED (constant errorClass = "SecurityException", no addRowsSkipped), plus an AE-11 parity test where the manager throws.
- **Ordering (S5a reviewer-strict):** S8 depends on **S5b** (not just S5): BillReminderWorker is the first caller passing actions, so the resource-only action-title fix must land first. Whichever slice lands it flips the KNOWN GAP test (NotificationPresentationApplierTest ~:379) to assert absence. Wave D = {S6 ∥ S7 ∥ S11} after S5b, S8 after S5b.
- **S5 validation:** shards `data` and `domain-n-z` run once at the S5 end (after S5b), together with S5b's targeted tests.
- **S5b test hygiene:** wrap the public top-level test fixtures in AndroidNotificationServicePresentationTest.kt (ScriptedSettingsRepository, PostCase, POST_CASES, SENTINEL_*, BRIEFING_KEY, EXPENSE_ID) in one fixtures object to avoid package collisions.
