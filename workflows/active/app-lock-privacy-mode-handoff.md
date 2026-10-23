# app-lock-privacy-mode — feature handoff

Feature id: `app-lock-privacy-mode`
Branch at start: release-1.0-remediation (release fix batches R16..R25 in progress — do not touch app/ or release docs)
Status: STAGE 1 (Brief) running. Paper-only until Release 1.0 accepted.

## Origin
- Owner 2026-10-16: "lets do those things that can be done now" (paper-only lane from opportunity-scan).
- Source: `docs/features/_discovery/opportunity-scan/portfolio.md` candidate 1;
  `portfolio-critique.md` M5 (scope: notification lock-screen visibility, FLAG_SECURE,
  physical-access justification rather than PRV-16 deep links); `area-G-trust-shell.md`.
- Related: `docs/features/loan-installments/ux-flows.md` OQ-14 (no app-wide hide-amounts mode).

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/app-lock-privacy-mode/brief.md | DONE (feature-strategist). Inc 1 lock shell (device-credential/biometric, timeout, deep-link gating, FLAG_SECURE, discreet notifications for 5 builders); inc 2 hide-amounts; 8 owner OQs; recommends Pursue. Found: 5 notification builders leak amount/merchant, no setVisibility/setPublicVersion. |
| 1r Brief v2 | docs/features/app-lock-privacy-mode/brief.md (v1 → brief-v1.md) | DONE (single reshape pass used). 1a notification policy + deep-link hardening; 1b lock API 30+ (26-29 unavailable), forced secure screen; 2 optional app PIN (API 30+, recovery = clear data/restore); hide-amounts moved out. 4 owner OQs. Further reshape → owner. |
| 2 Critique | docs/features/app-lock-privacy-mode/critique.md | DONE (feature-critic) — verdict RESHAPE. Split: 1a notification content policy + deep-link hardening (lock-independent); 1b lock + forced secure screen (per-API authenticator matrix, FragmentActivity migration, after R17/R24/R21); hide-amounts → own brief. 3 blocking (API 28-29 authenticators / ComponentActivity; deep-link hold doesn't fix flaw; secure screen must be forced with lock). 7 owner Qs. |

## Human decisions
- PENDING Gate 1 (go / reshape / drop). Precedent (loan-installments): the single allowed strategist
  reshape pass runs WITH owner answers to critique questions, not before.
- 2026-10-16 owner answers (verbatim): "we dont want to mess with face unlock . let older android
  unsopported is fine. app lock or pin or both are fine . for the others what you sduggest"
  Recorded as Gate 1 = RESHAPE with owner answers:
  - No face unlock / no BIOMETRIC_WEAK (Class 2). Strong biometrics + device credential only.
  - Older Android: lock feature unavailable where that combination is unsupported (API < 30).
    Interpreted as FEATURE unavailable on those versions; minSdk stays 26 (not raised).
  - Device lock, app PIN, or both acceptable → strategist to recommend sequencing.
  - Remaining questions delegated to orchestrator recommendation (owner can override at
    reshaped-brief confirmation):
    a. Discreet notifications: offered to all users as a setting; auto-on while lock on;
       generic public version always provided.
    b. API 30 (lock supported, no setAuthenticationRequired): with lock on, no Snooze/Dismiss
       actions on lock screen; API 31+ actions require auth.
    c. Deep links: remove BROWSABLE from internal-only hosts (after verifying no external caller),
       explicit-component PendingIntents, validate params, no raw value in diagnostics.
    d. Lock work waits for release batches R17/R21/R24 to land.
    e. Auto-lock default 1 minute.
    f. Hide-amounts → separate future brief (answers loan-installments OQ-14).
    g. RescueActivity out of scope.
- 2026-10-16 owner: "go" → Gate 1 = GO on brief v2. OQ1-4 not individually answered; orchestrator
  recommendations carried as PROVISIONAL defaults, to be listed as open decisions for Gate 2:
  OQ1 build Inc 2 app PIN as option, last; OQ2 discreet notifications forced while lock on;
  OQ3 remove BROWSABLE only (keep VIEW filter); OQ4 Inc 1a may ship ahead of 1b regardless of C4.
- 2026-10-16 owner: "accept all reccomendations" → GATE 2 DECIDED. All gate2-decisions.md §A/§B rows
  = recommended option: OD-1a (Inc 2 last, conditional on OD-15a/OD-16a + OD-19 escalation), OD-2a,
  OD-3a, OD-4a, OD-5a (release note), OD-6a (S1 included), OD-7a (5 min), OD-8a, OD-9a, OD-10a,
  OD-11a, OD-12a, OD-13a, OD-14 as written, OD-15a, OD-16a, OD-17a, OD-18a, OD-20a, OD-21a
  (generic public text), NEW-1a (RECEIPT_MATCH kind); OD-19 escalated to release track; TECH-1/TECH-2
  to guardians at plan stage. Scope flags S2, S8-S11 stay out. §C approvals requested later.
- 2026-10-17 owner (verbatim): "I approve the plan yes" → PLAN GATE = APPROVED (plan v2).
  Owner did not itemise plan §8 items 2-8; recorded as PROVISIONALLY following the orchestrator
  recommendations, to be explicitly re-confirmed at S0 start (new permission, new dependency and
  guard-related items require explicit approval per CLAUDE.md "stop and ask"):
  C6 + C7 fold-in (adopt release-track fix if it lands first); USE_BIOMETRIC; androidx.biometric
  only if TECH-1 = A; new legal paths NOTIFICATION PRESENTATION / DEEP LINKS / APP LOCK; APP-1
  defer §8.3 guards to SG (feature tests outside guard registry, TECH-10); translations
  agent-drafted + owner spot-check; acknowledgements (DeepLinkParserTest rewritten not deleted,
  1a before 1b, S15 permission not public before S22, no db_ownership_policy amendment).
- Orchestrator side action: critique issue 2 (raw caller string in diagnostics, any web page can
  suppress the briefing) is a present-day issue → added as advisory item C4 in
  `docs/features/_discovery/opportunity-scan/release-suggestions.md` for the release track. Not approved.

## Observed
- 2026-10-16: branch switched during session to `feature/ai-evolution` (HEAD 1b026317, owner docs
  commit). This lane's files are untracked docs only; no commits made by this lane.

## Constraints
- No implementation until Release 1.0 accepted and release worktree merged.
- Light overlap with release batches R17/R19 (MainActivity / Home / navigation) — re-check at impact-map stage.

| 3 Domain rules | docs/features/app-lock-privacy-mode/domain-spec.md | DONE (domain-modeler). 43 invariants, 84 AEs, 20 open decisions OD-1..OD-20 (OD-1..4 = provisional OQ1-4). Schema impact NONE (device-local prefs + process memory; .costbackup = manifest+DB+receipts+checksums, CostbackupBundle.kt:21-26,577-642). Key findings: manifest not the security boundary (exported launcher activity accepts any URI) → INV-16 briefingKey accepted only if equals last delivered; lock gate must wrap MainScreen from outside (reads before operational-lock early return, MainActivity.kt:364-410 vs :425); unlock re-runs LaunchedEffect(Unit) (permission dialog collision with R24, OD-9); duplicate unused DeepLinkParser (test-only); warranty must pass own kind; API 30 lock users lose Snooze/Dismiss; Inc 2 PIN bypasses (new fingerprint enrolment OD-16; D2D transfer OD-15/OD-19); forgotten-PIN recovery needs exported .costbackup. Reviewers: architecture-guardian, privacy-security-guardian, reviewer-strict; FG-10/11/16/22. |
| 4b UX reconcile | docs/features/app-lock-privacy-mode/ux-flows.md | DONE. 24 DDs: 11 resolved, 11 mapped to ODs (OD→UI variant table 12.2), 2 spec gaps; 8 UX Qs → 4 UQ-1..4; spec gaps G-1..G-13 in §13.2 with assumptions. Flags: R-1 INV-7 public version = discreet text (diverges from brief "generic"); R-2 OD-5(a) needs release note (API 31+ actions require unlock even with lock off); R-4 scope growth not designed (API 30 in-app snooze, in-app erase, last-export indicator, Inc 2 < API 30); DD-24 R19 privacy tile must land with/before 1a. |
| 3b Spec amendment | docs/features/app-lock-privacy-mode/domain-spec.md | DONE (AMD-1..16). 52 INV, 110 AE, T26-T31, OD-21 new (public version generic vs kind text). G-4 PIN removal ordered op; G-12 PIN only while lock on + secure device; S8-S11 scope flags. 4 ux-flows follow-ups listed in gate sheet §F. |
| Gate 2 sheet | docs/features/app-lock-privacy-mode/gate2-decisions.md | DECIDED 2026-10-16 (all recommendations) |
| 4c UX follow-up | docs/features/app-lock-privacy-mode/ux-flows.md | DONE (§13.4 log). OD variants collapsed (rejected = one line in §12.2); public pair "Reminder"/"Open the app to see details."; RECEIPT_MATCH copy "Receipt matched"/"Open the app to review."; PIN-mode turn-off; review card default On; App PIN row unavailable while paused; briefing title kept "Your AI briefing is ready"; OD-5a release note §9.5. Not proofread end-to-end by the agent. Spec follow-ups: six kinds (INV-12, AE-13, §2.5, §4.3), §4.3 briefing title + point to UX §9.4; `action_dismiss` missing values-el. UQ-1..4 open (UX-only). |
| 3c Spec amendment | docs/features/app-lock-privacy-mode/domain-spec.md | DONE (AMD-17..21). Six kinds (RECEIPT_MATCH); OD-1..21 marked decided; copy source = ux §9.4; F19 corrected, F22-F25 added; 112 AEs; TECH-1/2/3 open for guardians. TECH-3 (budget notifications use raw budget id → id-range kind detection wrong) added to gate sheet §B; impact-map headline 4 annotated. Briefing title resource currently unreferenced (use case hardcodes English, :65). |
| 3d Spec AMD-22 | docs/features/app-lock-privacy-mode/domain-spec.md | DONE. INV-26/51/§3.6 apply to AUTHENTICATING(UNLOCK) only; ENABLE/CONFIRM_CHANGE keep MainScreen composed; FLAG_SECURE follows saved settings; timer rules (promptBackgroundAt); host loss T35; INV-53/54, T32-T35, AE-113..121; legacy no-marker notifications rule in §4.6. Counts: 54 INV, 121 AE, T1-T35. Open gap (technical, planner + architecture-guardian): weakening change while INERT. ux follow-ups: §5 diagram, §3.8 row, §4.2 PIN_* note. |
| 4d UX touch-up | docs/features/app-lock-privacy-mode/ux-flows.md | DONE (§13.5). §5 diagram, §3.8 rows (unlock / settings prompt / host loss), §4.2 PinStep split, §0/§3.2/§11.1 aligned. No new screens/strings. Agent did not proofread untouched sections. |
| 6r Plan revision | workflows/active/app-lock-privacy-mode-plan.md | DONE — v2 (feature-planner, vs spec AMD-1..22). v1 archived at docs/features/app-lock-privacy-mode/plan-v1-archive.md. TECH-12 = spec TECH-3 (kind marker; S5/S8 stamp, S9 reads); TECH-13 weakening while INERT (rec. reject); all 54 INV / 121 AE traced; "budget 1-9999" removed. PLAN GATE pending owner (§8 checklist). |
| 6 Slice plan (v1) | workflows/active/app-lock-privacy-mode-plan.md | DRAFT (feature-planner, vs spec AMD-1..16). S0 decision record (TECH-1..11) + 29 slices: 1a S1-S13 + G1a; 1b S14-S23 + G1b; 2 S24-S28 + G2; SG optional guards. §8 owner checklist, §9 C6/C7 fold-in options (recommended fold-in), §10 risk register. Orchestrator reconciliation note appended: TECH-3 id collision (spec TECH-3 → plan TECH-12), S9 stale id-range kind detection, planner-requested AUTHENTICATING(UNLOCK)-only rule → spec AMD-22 (running), traceability to add AE-111/112. Planner revision pass after AMD-22, then plan gate. |
| 5 Impact & tier | docs/features/app-lock-privacy-mode/impact-map.md | DONE (impact-mapper). Tier T3 all three increments; reviewer-strict + architecture-guardian + privacy-security-guardian; no Room change (room-migration-guardian not needed). Headlines: 6th notification site (ReceiptMatchingWorker merchant) → new kind decision; NotificationService non-suspend vs bounded read (3 planner options; localized constants not in pure domain); F19 wrong (Compose test dep exists); OD-6 re-post needs getActiveNotifications; pre-existing notify() w/o SecurityException + Timber PII log (→ release-suggestions C6/C7). Collisions High: MainActivity (R24/R18/R19), PrivacySettings screen (R17/R23), BillReminderWorker (R21). Order: R17/R21/R24 → 1a → 1b → 2, from post-R24 release tip. Alternative: platform BiometricPrompt avoids FragmentActivity + androidx dep. API 30/31/33/34 device images need owner approval. |
| 4 UX flows | docs/features/app-lock-privacy-mode/ux-flows.md | DONE (ux-flow-designer, written without domain-spec). DD-1..DD-24 to reconcile; 8 UX OQs; §0.1 owner-decision map for OQ1-4. Flags: lock-prevents-composition loses unsaved forms / picker+camera results (recommend timer suspension during self-initiated external flows, DD-5); corrupt PIN record = permanent lockout (recommend device-credential fallback, DD-18); already-posted notifications keep old content (DD-23); bill notification has no content intent today; Privacy Settings strings missing el/es (pre-existing, out of scope); settings entry depends on R19 tile label (DD-24). |

## Status
PLAN APPROVED (v2, 2026-10-17). PAUSED until Release 1.0 accepted + R17/R21/R24 landed.

2026-10-21 release state (orchestrator check against git and release records):
- R17, R19, R21, R23, R24 and R18/R20/R25/R31/R32 landed. Release 1.0 final acceptance is CONDITIONAL
  (owner accepted, candidate 01fae80c, `release-1.0-final-acceptance.md`). Owner-deferred: production
  signing, RT-05 minified smoke, RT-03 upgrade path (waived), device probes P3/P4/P5/P9/P14, short beta.
- Release 1.0.1 (code 2, f150a1ac) and 1.0.2 (code 3, 27db3a03) shipped. 1.0.2 = TB-2 renewal and
  PRIV-03 flag removal only. Merge 044be2c8 is the current tip of feature/ai-evolution.
- The start gate in plan §0.1 is met on the release criteria. Branch operations still need owner approval.
- Advisories C4-C8 (`release-suggestions.md`) are STILL OPEN in source (MainActivity briefingKey,
  AndroidNotificationService notify() x3, AnomalyAlertOrchestrator Timber.i, ReceiptMatchingWorker merchant
  text, manifest without dataExtractionRules). None appear in the 1.0.1 or 1.0.2 batches. Timber is
  planted only in debug builds, so C7 is a debug-only exposure. Line numbers drifted: briefingKey is now
  MainActivity:219-226, merchant text is ReceiptMatchingWorker:111.
- Still PAUSED: the owner must say "resume app lock" to start S0.

## Next action (on resume)
1. Verify start gate (plan §0.1): Release 1.0 accepted; R17/R21/R24 landed; record R18/R19/R23
   status; record post-R24 release-line tip SHA; cut feature branch off it (owner approval for branch ops).
2. Re-confirm with owner the provisional plan-§8 items (permission, dependency, legal paths, APP-1, C6/C7).
3. Re-read files touched by R16..R25 (line numbers drift); stop on mismatch.
4. S0: architecture-guardian + privacy-security-guardian decision record (TECH-1..13).
5. Then S1 per plan (implementer → test-author → guardians → validation-runner → reviewer-strict).

## 2026-10-23 S0 (orchestrator, worktree C:\Users\panos\Desktop\Cost-agregator-al, branch feature/app-lock-privacy-mode from bcd8e921)
Start gate approved 2026-10-22 (owner-approvals item 6, tip 6b44823e ancestor of base). Owner's standing instruction 2026-10-23: orchestrator decides and continues; only blocking/very important items go to the owner. tech-decisions.md written from privacy-security-guardian (PASS) + architecture-guardian (conditional PASS; TECH-10 pending owner). Discovery drift: S6 anchors moved (BudgetMonitor :390/401/412 -> :469/:510; AnomalyAlertOrchestrator :194/:200), cancellation.json no longer has AnomalyAlertOrchestrator/BudgetViewModel entries (money-aggregates OG-1/OG-2) -> S6 must add no catch; BudgetMonitorStressTest does not exist; DeepLinkParserTest RequireConfirmation rows at :32/:36/:38/:55 (no :91). Collisions to serialise at merge: S6/S7 vs ai-fixes B3a (DeliverProactiveBriefingNotificationUseCase, WarrantyExpirationWorker), S8 vs loan (BillReminderWorker), S10/S20/S21 vs loan/nl-quick-add (MainActivity).
Next: wave 1a-A S1 ∥ S2 ∥ S3.

## Wave 1a-A (S1 ∥ S2 ∥ S3) DONE 2026-10-23
S1 AppLockSettingsRepository (own DataStore, CORRUPTED sentinel, fail-closed EffectivePrivacyValues); S2 23 DiagnosticReasonCode values + typed PrivacyDiagnosticsEmitter (per-code 60 s monotonic limiter, lazy suppressed summary); S3 DeepLinkPolicy + DeepLinkRequest (domain/privacy/deeplink) + TimePeriodTokens. First run: INV-17 reflection test saw Compose `$stable` static field; G-RCPT-LINK-01 flagged `.copy(expenseId = …)` in the parser (false positive) -> request now built once, no allowlist change. Review (reviewer-strict) ACCEPT-WITH-MINORS; minors fixed (+ decoding, encoded key reported) or recorded (AE-33 lazy reading, spec 5.2 NotADeepLink) in tech-decisions.md.
Validation: targeted vr-20261023-002122-e3b25849 PASS; receipt_link vr-20261023-002540-8bad708a PASS; static-guards vr-20261023-002558-64598dc6 PASS; earlier pii_logging/di_release/event_writers/cancellation PASS (vr-20261022-2329..2330).
Next: wave 1a-B (S4 resolver; S5 needs owner C6 decision).

## S4 DONE (2026-10-23)
domain/notification/presentation: NotificationKind (6 kinds, literal stable tokens), NotificationPresentationDecision (PRIVATE + GENERIC computed), NotificationContentPolicy (pure; fail-closed DISCREET; BILL AUTH_REQUIRED on API 31+, UNCHANGED ≤30 in 1a per INV-10 tag), NotificationPresentationResolver (@Singleton; withTimeoutOrNull(500); CE rethrow before the TECH-2 catch; diagnostics launched on @ApplicationScope, never awaited). sdkInt is a resolve() parameter (callers pass Build.VERSION.SDK_INT). Review ACCEPT-WITH-MINORS; minor fixed (BILL fail-closed action-policy test). Notes for later slices: keep NotificationContentPolicy total or move inside the try when 1b adds lock terms; S5 builder tests carry AE-5/109/111 meaning; S13 maps domain/notification/presentation/** to Segment 11 + LEGAL_PATHS "post app notification".
Validation: targeted vr-20261023-011906-4eddca6c PASS; cancellation vr-20261023-012325-75320afb PASS; pii_logging vr-20261023-012342-7be0e542 PASS. static-guards at wave 1a-B end (with S10).

## S10 DONE (2026-10-23) — wave 1a-B complete (S4 ∥ S10)
MainActivity.handleIntent → DeepLinkPolicy.evaluate + new ui/navigation/DeepLinkApplier (bounded DL_* diagnostics launched non-blocking; ACTION rejection = no navigation; other rejections → Home; read-only expense lookup with CE rethrow); new domain/ai/usecase/MarkDashboardBriefingOpenedUseCase (writes lastOpened only when the validated key equals the last delivered key, read at apply time). C4 CLOSED: no raw query/host/briefingKey in AiRuntimeDiagnostics, Timber or diagnostics.
First validation: DeepLinkApplierTest NPE (relaxed mockk of AiRuntimeDiagnostics → null TimeProvider on the default-arg path) → spyk(real instance); known_good_state active_db_gate 21→23 DB_SIGNATURE_UNRESOLVED advisories from S4 resolver + S10 applier helper headers taking `PrivacyDiagnosticMetadata.Builder.() -> Unit` (scanner cannot parse function-type-with-receiver params) → helpers now take a built PrivacyDiagnosticMetadata (= EMPTY default); count back to 21, no pin change.
Review (reviewer-strict) ACCEPT-WITH-MINORS: ISSUE-1 ACTION_VIEW requirement added to the plan's S5 bullet (+ AE-32 test must assert it); ISSUE-2 sentinel test now advances the monotonic clock past the 60 s window per link. DL_APPLIED on degraded outcomes accepted (spec §5.7 "link applied"). R18 HZ-03 F1 status not traced (follow-up check before S20/S21).
Validation: targeted (S4+S10 filters) vr-20261023-022316-72183a7a PASS; static-guards vr-20261023-022804-6f78a941 PASS; ui_dao/pii_logging/cancellation PASS (vr-20261023-0155..).
Next: S5 (blocked on owner C6 decision) → wave D {S6 ∥ S7 ∥ S8 ∥ S11}.
