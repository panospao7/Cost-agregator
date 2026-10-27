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
Start gate approved 2026-10-22 (owner-approvals item 6, tip 6b44823e ancestor of base). Owner's standing instruction 2026-10-23: orchestrator decides and continues; only blocking/very important items go to the owner. tech-decisions.md written from privacy-security-guardian (PASS) + architecture-guardian (conditional PASS; TECH-10 pending owner). Discovery drift: S6 anchors moved (BudgetMonitor :390/401/412 -> :469/:510; AnomalyAlertOrchestrator :194/:200), cancellation.json no longer has AnomalyAlertOrchestrator/BudgetViewModel entries (money-aggregates OG-1/OG-2) -> S6 must add no catch; BudgetMonitorStressTest does not exist; DeepLinkParserTest RequireConfirmation rows at :32/:36/:38/:55 (no :91). Collisions to serialise at merge: S6/S7 vs ai-fixes B3a (DeliverProactiveBriefingNotificationUseCase, WarrantyExpirationWorker), S8 vs loan (BillReminderWorker), S10/S20/S21 vs loan/nl-quick-add (MainActivity). Also vs feature/manual-entry-currency (added at G1a final acceptance): the `DiagnosticReasonCode` enum tail (MEC adds `MANUAL_SAVE_BLOCKED_NO_RATE` at the same spot; resolve by keeping both; codes are stored by name) and the shared architecture docs (LEGAL_PATHS, ENGINE_INTERACTION_MAP, CODEBASE_SEGMENTS, CODEBASE_INVENTORY).
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

## Owner decisions 2026-10-23 (verbatim: "i agree with c6 and i agree to them all")
- C6: FOLD IN (§9 option (i); TECH-9 shape). S5's new kind-aware methods catch SecurityException → DeliveryResult.PERMISSION_DENIED; parity tests per caller land with S6/S7; the S9 sweep always catches.
- USE_BIOMETRIC + androidx.biometric 1.1.0 (USE_FINGERPRINT stripped) + explicit androidx.fragment: APPROVED (for S15).
- C7: folded into S6 as one bounded-message hunk (orchestrator decision under the standing instruction; privacy-positive, no behaviour change).
- Still open: TECH-10/APP-1 (before S16/S20), APP-3 (before G1b), APP-4, C5.
Record: Cost-agregator/build/release-lead-notes/owner-approvals-20261022.md items 8-10.
Next: S5 (unblocked).

## S5a (2026-10-23) — implemented (plan split seam: S5a contract/applier/marker/texts/new methods; S5b legacy hardening + explicit PendingIntents next)
- NotificationService: DeliveryResult.PERMISSION_DENIED (C6 / TECH-9) + 5 kind-aware suspend methods (sendBudgetAlertForKind / postBudgetAlertForKind (NotificationId), sendAiBriefingReadyForKind, sendAnomalyAlertForKind / postAnomalyAlertForKind (NotificationId)).
- AndroidNotificationService(context, resolver, applier):
  - New methods: areNotificationsEnabled → resolve(kind, SDK_INT) (outside any catch, so CE propagates) → build → applier → notify.
  - notify wrapped in catch(SecurityException) only → PERMISSION_DENIED, with a bounded Log.w (kind token + class).
  - The new methods already use explicit MainActivity + ACTION_VIEW + FLAG_IMMUTABLE tap targets. Legacy methods are untouched (S5b).
- New NotificationPresentationApplier (PRIVATE, generic public version, discreet constants, BigText only in FULL, action policy, marker), NotificationKindMarker (EXTRA_KEY com.yourname.expensetracker.extra.NOTIFICATION_KIND; value = NotificationKind.stableToken; deviation from "enum name": S4 tokens are pinned literals), NotificationPresentationTexts.
- strings_notification_presentation.xml in en/es/el (es/el DRAFT, owner spot-check); added the missing el action_dismiss and es/el notification_briefing_ready_title.
- No DeliveryResult `when` exists; every caller compares == DELIVERED. Warranty already maps PERMISSION_DENIED → markFailed (retryable). **S7 must add ReceiptMatchingWorker's explicit PERMISSION_DENIED → RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED branch** (TECH-9 parity).
- Layering note for architecture-guardian: data/service now imports ui.MainActivity (TECH-6 explicit component).
- Tests: pass 1 (existing fixes + applier/marker/texts) running; pass 2 (AndroidNotificationServicePresentationTest + C6 tests) next.
- S5a tests: pass 1 (applier/marker/texts + existing fixes) and pass 2 (AndroidNotificationServicePresentation contract on SDK 26/28/30/31/34 + C6 tests via a ContextWrapper NotificationManager seam).
- S5a validation:
  - targeted (incl. caller regressions) vr-20261023-085445-a21e2ca9 PASS;
  - lint vr-20261023-090348-99bb5cdf PASS;
  - pii_logging vr-20261023-090806-86f423bd PASS;
  - cancellation vr-20261023-090824-49f34033 PASS.
- Reviews:
  - PSG ACCEPT-WITH-MINORS (action titles unscanned in DISCREET; briefing URI concatenation; AE-32 extras assert).
  - AG ACCEPT-WITH-MINORS (S7 receipt branch, S6 anomaly diagnostic, S5b helper consolidation) → plan carry-forwards.
- Test-only fix batch running; then reviewer-strict on S5a.

## S5a DONE (2026-10-23)
- Validation:
  - targeted vr-20261023-085445-a21e2ca9;
  - lint vr-20261023-090348-99bb5cdf;
  - pii_logging vr-20261023-090806-86f423bd;
  - cancellation vr-20261023-090824-49f34033;
  - after the test-only fixes: targeted (Applier + Presentation SDK 26-34) vr-20261023-093609-794cbaa2.
  - All PASS.
- Reviews: AG, PSG and reviewer-strict all ACCEPT-WITH-MINORS; carry-forwards in the plan ("Orchestrator carry-forwards from the S5a reviews"), incl. S8 now depends on S5b.
- KNOWN GAP test pins caller-supplied action titles passing through in DISCREET (no S5a caller passes actions); S5b/S8 flips it.
- Next: S5b (legacy hardening + explicit PendingIntents onto the S5a helpers, Uri.Builder briefing URI, resource-only action titles, fixtures object), then the S5 end shards data + domain-n-z.

## S5b DONE (2026-10-23) — S5 complete
- Legacy methods use the shared explicit tap helpers + presentationApplier.hardenLegacy (PRIVATE + generic public version, no marker, visible text unchanged).
- Briefing URI via Uri.Builder (briefingKey percent-encoded; DeepLinkPolicy decodes via getQueryParameter; pre-upgrade raw ':' links still accepted).
- NotificationActionSpec titles are @StringRes only (closes the DISCREET action-title gap; the KNOWN GAP test flipped to assert absence).
- Test fixtures wrapped in PresentationFixtures.
Validation (S5-end batch):
- targeted vr-20261023-101942-4f74dc9d;
- lint vr-20261023-102444-edd147b3;
- pii_logging vr-20261023-102847-ad8b5f2a;
- shards data vr-20261023-102903-47726589 and domain-n-z vr-20261023-103249-acf74a16;
- static-guards vr-20261023-103438-6d979bad;
- after the minors (request-code asserts + comment wording): targeted vr-20261023-112959-aa4a87d2.
- All PASS.
Review: reviewer-strict (covering arch + privacy for the S5b half) ACCEPT-WITH-MINORS; both minors fixed. S5 "Done when" met.
Next: wave D {S6 ∥ S7 ∥ S11} (S8 after S5b, now satisfied). C7 folds into S6; S7 adds the receipt PERMISSION_DENIED branch.

## S6 DONE (2026-10-23)
Source:
- BudgetMonitor (sendNotification now suspend → sendBudgetAlertForKind BUDGET), AnomalyAlertOrchestrator (postAnomalyAlertForKind ANOMALY, constant result logging) and DeliverProactiveBriefingNotificationUseCase (sendAiBriefingReadyForKind BRIEFING) are on the kind-aware path. Ids unchanged (TECH-12 a).
- PERMISSION_DENIED:
  - budget = NOT_DELIVERED (no cooldown write, SKIPPED diagnostic; the loop continues for other budgets, where previously a SecurityException aborted the cycle; intended per TECH-9);
  - anomaly = row stays inserted (insert-then-post), constant Timber.e without a throwable;
  - briefing = no lastDelivered, nothing recorded.
- C7 + C7b: merchant/amount/deviation removed from anomaly logs (:200-207 and :261).
Tests: 4 suites retargeted (no intent change; relaxed-mock vacuity removed) + parity/kind/C7/cancellation tests.
Validation:
- targeted vr-20261023-121721-5cf643d9, cancellation vr-20261023-122625-8317bee1, pii_logging vr-20261023-122643-b3971204, domain-a-m vr-20261023-122659-85dcf773, integration-golden vr-20261023-122800-448ac234;
- after C7b: targeted vr-20261023-125554-d160b4f7, pii_logging vr-20261023-125842-640e26ea.
- All PASS.
Reviews: reviewer-strict (lead), privacy-security-guardian and architecture-guardian all ACCEPT-WITH-MINORS.
Residuals/follow-ups:
- DailyBriefingWorker counts a PERMISSION_DENIED briefing as notificationsSent (only if permission is revoked mid-run). Closed by the cross-lane B3a merge requirement on Cost-agregator/build/release-lead-notes/lanes-board.md.
- Anomaly debug logs with expense ids (:83/:112/:119/:124/:154/:160/:169/:192/:218 with throwable/:241): debug-only; follow-up for a later log-hygiene pass.
- WarrantyExpirationWorker / ReceiptMatchingWorker legacy calls → S7.
Next: S7 (warranty + receipt workers onto kind-aware methods; explicit receipt PERMISSION_DENIED → RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED), then S11, then S8.

## S7 (2026-10-23) — implemented (validation pending)
Source:
- WarrantyExpirationWorker.deliverReminder: sendBudgetAlert → sendBudgetAlertForKind(WARRANTY, ...). Signature, name, DAO call order and checkpoints unchanged (no db_ownership_policy.yml change). PERMISSION_DENIED falls to the existing markFailed("notification_not_delivered") branch (retryable, no addNotificationsSent).
- ReceiptMatchingWorker: postBudgetAlert → postBudgetAlertForKind(RECEIPT_MATCH, NotificationId.forReceipt, ...). The result is now an exhaustive `when` (the NOT_DELIVERED branch became explicit after review): DELIVERED → addNotificationsSent; PERMISSION_DENIED (new, carry-forward) → constant Timber.w + recordNotificationSuppressed(RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED, errorClass = constant "SecurityException"), no addRowsSkipped; NOT_DELIVERED → addRowsSkipped + NOT_DELIVERED suppression (unchanged). areNotificationsEnabled() pre-check, requiresNotificationPermission = false and the defensive SecurityException / Exception catches kept; no new catch.
Tests (modified, never deleted):
- WarrantyExpirationWorkerTest: every legacy sendBudgetAlert stub/verify retargeted to coEvery/coVerify sendBudgetAlertForKind(any x4); new AE-5/AE-13/AE-11 tests (kind WARRANTY, id and texts, never BUDGET/legacy, PERMISSION_DENIED → FAILED, DELIVERED/NOT_DELIVERED counters).
- ReceiptMatchingWorkerTest: every postBudgetAlert stub/verify retargeted to postBudgetAlertForKind(any x4); new AE-13/AE-11/AE-111 tests, including a real AndroidNotificationService with a throwing NotificationManager (parity with a PERMISSION_DENIED result) and a real-manager DISCREET test (sentinel absent, channel budget_alerts, id forReceipt, tap home + CLEAR_TASK).
Validation requested: targeted *WarrantyExpirationWorker*, *ReceiptMatchingWorker*, *SourceScanningArchitectureGuardTest*, *WorkerGuardArchitectureGuardTest*; shards runtime-ui, architecture-contracts; registered guards worker, db_access, cancellation, pii_logging.

Intended TECH-9 behaviour change (warranty, owner decision C6):
- Before S7 a platform SecurityException escaped deliverReminder: the run failed, the guard recorded WORKER_NOTIFICATION_PERMISSION_DENIED, and the delivery row stayed CLAIMED until stale-claim recovery (1 hour).
- Now the service maps it to PERMISSION_DENIED, deliverReminder does markFailed("notification_not_delivered") (row FAILED, re-claimable), the loop continues and the run succeeds. No addNotificationsSent.
- The guard's pre-check (requiresNotificationPermission) still reports NOTIFICATION_PERMISSION_DENIED on the next run when notifications are disabled.

S7 review verdicts (2026-10-23): reviewer-strict part 1 and part 2 ACCEPT / ACCEPT-WITH-MINORS; architecture-guardian ACCEPT-WITH-MINORS; privacy-security-guardian ACCEPT-WITH-MINORS. No blockers.
Review minors applied (uncommitted, validation pending):
- ReceiptMatchingWorker: the `else ->` branch of the DeliveryResult `when` is now an explicit NOT_DELIVERED branch (no else), so a new enum value forces a compile-time decision. Behaviour unchanged.
- WarrantyExpirationWorker: class and deliverReminder KDoc now state PERMISSION_DENIED → markFailed, same as NOT_DELIVERED (TECH-9). The warranty check stays an if (DELIVERED vs everything else) by design.
- PresentationFixtures (AndroidNotificationServicePresentationTest.kt) now owns the recursive everyString / flattenInto; the presentation contract delegates to it and ReceiptMatchingWorkerTest and WarrantyExpirationWorkerTest import it (the earlier receipt scan only read top-level extras).
- ReceiptMatchingWorkerTest: DISCREET test strengthened through a shared assertion (title/text equal notif_discreet_receipt_match_*, differ from the BUDGET constants, "Receipt from" absent, explicit MainActivity component, FLAG_IMMUTABLE, generic public pair); new variant with an unreadable settings store (IO_ERROR) → DISCREET; the throwing-NotificationManager parity test now asserts the sentinel merchant is absent from ShadowLog and a planted Timber tree.
- WarrantyExpirationWorkerTest: new real-service DISCREET test (product/store names and "7 days" absent, WARRANTY discreet constants, budget_alerts and id unchanged); new real-service SecurityException test (row FAILED "notification_not_delivered", no addNotificationsSent for it, run continues to the next reminder); new cancellation test at the sendBudgetAlertForKind suspension point (propagates, row stays CLAIMED, no outcome write).
- Plan S7 text corrected (orchestrator corrections): deliverReminder signature is (WorkerRunContext, Warranty, Int, Long, String, String); the "SecurityException catch ... all stay" wording now points to the carry-forward explicit PERMISSION_DENIED branch (defensive catch kept).
Re-validation requested after the minors: same targeted set as above plus *AndroidNotificationServicePresentation* (PresentationFixtures moved code).
Next: S11, then S8.

## S7 DONE (2026-10-23)
- Validation (initial): targeted vr-20261023-145426-5ecc5484, guards worker vr-20261023-150145-9b28c320 / db_access vr-20261023-150203-651c62e4 / cancellation vr-20261023-150437-20b972d7 / pii_logging vr-20261023-150455-f5e2df90, shards runtime-ui vr-20261023-150510-2ae9521d / architecture-contracts vr-20261023-150642-15d538ac — all PASS.
- After review fixes: targeted (incl. *AndroidNotificationServicePresentation*) vr-20261023-162037-4c9e291a, worker vr-20261023-163112-975c7883, cancellation vr-20261023-163130-90943d04, pii_logging vr-20261023-163148-1519d4fa — all PASS. (Source delta after the first run: explicit NOT_DELIVERED branch + KDoc only, so db_access and the shards were not rerun.)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS (warranty part) + ACCEPT (receipt part; whole-slice attempts died twice on API timeouts, so it was split), architecture-guardian ACCEPT-WITH-MINORS, privacy-security-guardian ACCEPT-WITH-MINORS. Minors applied. Optional nit not taken: helper to dedupe the PERMISSION_REVOKED mapping between the new branch and the defensive catch.
- Pre-existing follow-ups (log hygiene): ReceiptMatchingWorker Timber.w(e, …) at ~:151/:162/:254 log full exceptions (debug-only trees).
Next: S11 (manifest: remove BROWSABLE, OD-3a), then S8 (bill reminder worker applies the decision).

## S11 (2026-10-23) — implemented
- Changed: `app/src/main/AndroidManifest.xml` only. Removed `<category android:name="android.intent.category.BROWSABLE" />` from the MainActivity VIEW filter (expensetracker, 8 hosts; was :85). VIEW action, DEFAULT category, all 8 `<data>` entries and the exported MAIN/LAUNCHER filter unchanged. INV-20 (not BROWSABLE) / OD-3a. No tests, no manifest guard (APP-1).
- Caller check: BROWSABLE occurs once under app/src (that line). All in-repo `expensetracker://` uses are internal: notification PendingIntents (AndroidNotificationService, ReceiptMatchingWorker, tests) and MainActivity handleIntent; remaining hits are docs (UI_REFERENCE_INDEX, NAVIGATION_ARCHITECTURE, COMPREHENSIVE_UI_MAP, feature docs) describing the scheme, none tells users to open links from a browser. No external-looking caller found. Explicit-component notification intents and implicit VIEW from installed apps still resolve (F9); validation (INV-14..INV-16) remains the boundary.
- Doc drift (not edited, outside write set): docs/architecture/COMPREHENSIVE_UI_MAP.md:1117 still shows BROWSABLE in a manifest snippet; refresh with the architecture-doc pass.
- Validation needed: compile (manifest merge). FG-22 exported-components evidence deferred to G1a. R17/C5 re-check: confirm at G1a that the merged manifest has no BROWSABLE for the expensetracker scheme and that deep-link runtime proof (safe links do not crash, invalid fail safely; AE-16 browser link is a no-op) still holds.
Next: S8.

## S11 DONE (2026-10-23)
- Validation: compile vr-20261023-163650-081e2194 PASS. FG-22 / AE-16 evidence deferred to G1a.
- Reviews: reviewer-strict ACCEPT, privacy-security-guardian PASS, architecture-guardian (INV-20 owner) ACCEPT-WITH-MINORS.
- Correction: only AndroidNotificationService builds expensetracker:// intents (explicit MainActivity component, FLAG_IMMUTABLE); ReceiptMatchingWorker posts through it and builds none itself.
- G1a carry-forward (binding): scripts/verify_release_artifact.py has no intent-filter/BROWSABLE check, so release-artifact-evidence alone does NOT prove INV-20. G1a must show the merged release manifest (e.g. aapt2 xmltree dump) with no BROWSABLE on the expensetracker filter, plus the AE-16 browser no-op and the malformed `am start` no-crash device checks. Evidence only, no new guard (APP-1).
- S13 carry-forwards: COMPREHENSIVE_UI_MAP.md ~:1117 manifest snippet — remove BROWSABLE AND add the missing DEFAULT line; add a DEEP LINKS section to LEGAL_PATHS.md (none today).
- Release-note wording (APP-4): "links from web browsers no longer open the app" — not "links can't open the app" (other installed apps can still send VIEW; validation is the boundary).
- Pre-existing, outside S11 (log hygiene follow-up): MainActivity.kt ~:839 Timber.e(error, "...expenseId=%s") logs the full exception plus an expense id.
Next: S8 (BillReminderWorker applies the decision; builder owner 2).

## S8 (2026-10-23) — implemented (validation pending)
Source (`service/reminder/BillReminderWorker.kt` only):
- Constructor gains `presentationResolver: NotificationPresentationResolver` and `presentationApplier: NotificationPresentationApplier` (appended after `timeProvider`; Hilt assisted injection, both are @Singleton).
- The decision is resolved inside the guard lambda, per reminder, after claim and revalidation and immediately before the post: `presentationResolver.resolve(NotificationKind.BILL, Build.VERSION.SDK_INT)`. It is passed into the non-suspend `sendNotification(..., decision)`. No `runBlocking`. A CancellationException from the bounded read propagates through the existing rethrow-first catch (nothing posted, claim untouched, no log).
- `sendNotification` builds channel, small icon, priority and auto-cancel itself, then calls `presentationApplier.apply(...)`, which sets title/text, the generic public version, PRIVATE visibility, the action policy and the BILL marker. No marker code in the worker. Still no content intent, same as before.
- Actions: `NotificationActionSpec(R.drawable.ic_snooze, R.string.notif_action_snooze, ...)` and `(R.drawable.ic_dismiss, R.string.action_dismiss, ...)`. Resources already existed (en/es/el), so no strings were added.
- INV-10 comes from the applier policy (`NotificationContentPolicy`: BILL API 31+ = AUTH_REQUIRED for every user, API 30 and below = UNCHANGED; NONE is handled by the applier and is reachable once S14 changes the policy). No parallel mechanism.
- `CHANNEL_ID` is now `internal const` ("bill_reminders", value unchanged); `SMALL_ICON` private const added so the icon is declared once.

Parity table (unchanged on purpose):
- claim, revalidate (`getDispatchableClaimedReminder`, cancel `not_dispatchable_after_claim`), send, `markReminderSent`, `addNotificationsSent` only when Sent and marked, diagnostic emit, `Failed` handling (permission_denied -> `cancelClaimedReminderDelivery("notification_permission_revoked")`, others -> `markReminderFailed`), `addRowsSkipped` counts.
- `SecurityException` mapping and `Exception` mapping around `notify`; no new catch (FG-11); `WorkerGuardRequest` unchanged; `android.util.Log` class names only.
- Notification id `NotificationId.forBill`, no tag, channel id/name/description/importance, Snooze/Dismiss intents, extras, request codes (`id`, `id xor 0x40000000`), flags, receivers, action workers, RecurringLifecycleCoordinator path.
Intended visible changes: PRIVATE visibility plus a generic public version (all API levels); DISCREET text when the setting is on or unreadable; AUTH_REQUIRED actions on API 31+ for every user (APP-4 release note must ship with the first release that contains S8).

Tests:
- `BillReminderWorkerTest`, `BillReminderWorkerTimeProviderTest`: constructor only. A mocked resolver returns a FULL / UNCHANGED BILL decision and a real applier is passed, so all existing assertions are unchanged (no test deleted or weakened).
- New `BillReminderWorkerNotificationPresentationTest` (abstract contract + SDK 26/28/29/30/31/34 subclasses; real manager, resolver, applier, texts; scripted settings repository from `PresentationFixtures`): FULL control, DISCREET constants with merchant/amount/currency absent from every string incl. the public version and action titles, generic public version, BILL marker on the private notification only (not a text extra), id/tag/channel, action titles from resources, PendingIntent receivers/extras/request codes/flags, AUTH_REQUIRED on 31+ (unchanged titles on <=30), AE-11 bill-site order and counters, one read per post, AE-9 cancellation (nothing posted, no outcome, no warn log), Unreadable(IO_ERROR) and read-throws -> DISCREET (no exception text in logs), SecurityException mapping still cancels the claim.

Intended visible changes (feed the APP-4 release note, which ships with the first release containing S8):
- Action titles are now localised: el "Αναβολή" / "Απόρριψη", es "Posponer" / "Descartar" (en unchanged: "Snooze" / "Dismiss").
- The FULL title "Bill due" and the FULL body stay hardcoded English. Pre-existing, outside S8; follow-up.
- PRIVATE visibility plus a generic public version on all API levels.
- AUTH_REQUIRED actions on API 31+ for every user.

Architecture rulings (architecture-guardian, ACCEPT-WITH-MINORS):
- `service/reminder` -> `data.service` applier injection is accepted. No domain port: the applier's API is `NotificationCompat.Builder`, so a port would only re-export it.
- The S9 classifier may import ONLY the `internal const val CHANNEL_ID` from `BillReminderWorker` (const-inlined at compile time; no other member). The S9 architecture gate must check this. Precedent is `domain/workers/WorkerRegistry.kt:16`, not `RecommendationRepository`.

Review verdicts: reviewer-strict, architecture-guardian, privacy-security-guardian all ACCEPT-WITH-MINORS. The minors were applied to the test file after these validations (no production change): SDK 29 subclass (AE-12), AE-9 also pins `cancelClaimedReminderDelivery` never called (row stays CLAIMED), two new tests pin that resolve sits after claim and after revalidation (0 settings reads, nothing posted), AE-7 traceability (the former "AE-7" lifecycle test is now AE-11 bill site; the unreadable-store test is AE-7 and verifies NOTIF_POLICY_SETTINGS_UNREADABLE(IO_ERROR) through a captured emitter).

Validation (all PASS, pre-fix; the test-only minors need a re-run of `*BillReminderWorker*`):
- targeted-unit-test vr-20261023-170515-589aa108
- runtime-ui vr-20261023-171447-b4b97b58
- worker vr-20261023-174114-035eb356
- cancellation vr-20261023-174132-3df850c1
- pii_logging vr-20261023-174150-55dde3b4

Carry-forwards:
- S13 docs: `import-graph.json` BillReminderWorker dependencies, and LEGAL_PATHS "DISPATCH reminder" gains the resolve -> applier step.
- S14: on API 26-30 an unreadable settings store must suppress Snooze/Dismiss (spec 4.4; applier mode NONE). No S8 test pins the current <=30 behaviour (actions unchanged), so S14 changes it without a failing S8 test; S14 must add the pin.

Suggested validation:
- `targeted-unit-test` `*BillReminderWorker*`, `*WorkerGuardArchitectureGuardTest*`, `*SourceScanningArchitectureGuardTest*`, `*NotificationPresentationApplier*`;
- shard `runtime-ui`;
- `registered-guard` `worker`, `cancellation`, `pii_logging`.
Gates: reviewer-strict (lead), privacy-security-guardian, architecture-guardian.
Next: S9 (needs the CHANNEL_ID import ruling).

## S8 DONE (2026-10-23)
- Post-fix validation: targeted *BillReminderWorker* (SDK 26/28/29/30/31/34 matrix) vr-20261023-174351-c574eee3 PASS. Pre-fix runs (runtime-ui, worker, cancellation, pii_logging) listed above; review fixes were test/doc-only.
Next: S9 (discreet re-post sweep + write use case; remove legacy kind-less methods). The S9 architecture gate must check the classifier imports only BillReminderWorker.CHANNEL_ID.

## S9a (2026-10-23) — implemented (validation pending)
Scope: remove the legacy kind-less send*/post* methods and migrate their tests. S9b (classifier, sweep, trigger) NOT started.

Removed from `domain/service/NotificationService.kt` and `data/service/AndroidNotificationService.kt`:
- `sendBudgetAlert`, `postBudgetAlert`, `sendAiBriefingReady`, `sendAiBriefingReadyWithResult`, `postAiBriefingReady`, `sendAnomalyAlert`, `postAnomalyAlert` (kind-less forms). Only the `*ForKind` methods remain (INV-12: no kind-less method exists).
- Removed helpers: `NotificationPresentationApplier.hardenLegacy` and the legacy-only post path in `AndroidNotificationService`. No production caller of any removed name remains in app/src/main (grep of main, test, androidTest).

Migrated tests (old -> new, no assertion dropped without an equivalent):
| Old | New |
|---|---|
| AndroidNotificationServiceTest: sendAiBriefingReady returns not delivered when notifications are disabled | sendAiBriefingReadyForKind ... (kind BRIEFING, runTest) |
| AndroidNotificationServiceTest: sendAiBriefingReady returns delivered after dispatch | sendAiBriefingReadyForKind returns delivered after dispatch |
| AndroidNotificationServiceTest: S5b legacy sendAiBriefingReadyWithResult private + generic public version (asserted no marker) | S9a sendAiBriefingReadyForKind ... and the kind marker (marker now equals BRIEFING token, absent on the public version) |
| AndroidNotificationServiceTest: S5b legacy sendBudgetAlert explicit MainActivity tap target | S9a sendBudgetAlertForKind explicit MainActivity tap target |
| PresentationTest: LEGACY_POST_CASES / LegacyPostCase / postLegacyAndRead fixtures | removed (POST_CASES already covers every kind) |
| PresentationTest: AE-32 briefing data string, both paths | kind-aware FULL and DISCREET |
| PresentationTest: AE-32 legacy tap intents explicit / accepted by DeepLinkPolicy | covered by the existing kind-aware tests (all 5 kinds x FULL/DISCREET); legacy copies were duplicates |
| PresentationTest: AE-32 control legacy cases cover flags (4 cases) | control kind cases cover flags (5 cases) |
| PresentationTest: INV-7 legacy PRIVATE and never PUBLIC | INV-7 kind-aware PRIVATE and never PUBLIC, FULL and DISCREET |
| PresentationTest: INV-7 legacy generic public version, no actions/style/marker/content intent | INV-7 public version has no ticker/action/style/marker/content intent, FULL and DISCREET |
| PresentationTest: AE-121 legacy carries no kind marker | AE-121 each kind carries only its own token and no other kind token |
| PresentationTest: INV-7 legacy visible title/text/BigText as supplied | INV-7 FULL visible title/text/BigText as supplied (BigText only for BRIEFING and ANOMALY) |
| PresentationTest: INV-7 control legacy FULL text vs kind-aware DISCREET public version | control FULL vs DISCREET same kind, same public version |
| PresentationTest: legacy sendBudgetAlert / sendAiBriefingReadyWithResult / sendAiBriefingReady / sendAnomalyAlert posts supplied text, no marker, never reads settings | FULL posts the supplied text with the kind marker and reads the settings exactly once, every kind (the "never reads the settings" and "no marker" properties belong to the removed methods) |
| NotificationPresentationApplierTest: 7 hardenLegacy tests (+ control) | apply-based: private over caller PUBLIC (both modes, with control), control builder without apply has no public version, public version of a builder with a pre-existing action, caller tap target/category/channel kept, no action when no specs, sdk 26 holds. "returns same builder" and "stamps no marker" are already covered by apply tests (INV-4, AE-112) |
| WarrantyExpirationWorkerTest, ReceiptMatchingWorkerTest, NotificationIdGeneratorTest | partially migrated by the first S9a attempt; legacy-method verify(exactly = 0) lines replaced by verifies on the other ForKind form |

Removed "never legacy" verifies: the verify(exactly = 0) lines on the removed legacy methods in the worker tests (the methods no longer exist; the other-ForKind-form verify remains).
Test fixture note: `SourceScanningArchitectureGuardTest` keeps the text `sendBudgetAlert` inside a negative-fixture source string and in the scanner's contains check (line ~147); it is data, not a call. Left unchanged (guard files are out of S9a scope); S9b/architecture gate may decide whether the scanner token needs `sendBudgetAlertForKind`.

Cross-lane (see build/release-lead-notes/lanes-board.md CROSS-LANE): the ai-fixes branch still calls the legacy methods (for example the briefing use case). Whichever lane lands second must use the `*ForKind` forms (`sendAiBriefingReadyForKind` with kind BRIEFING).
Validation requested: targeted *AndroidNotificationService*, *NotificationPresentationApplier*, *WarrantyExpirationWorker*, *ReceiptMatchingWorker*, NotificationIdGeneratorTest, compile of main + test + androidTest.

## S9a DONE (2026-10-23)
- Validation: targeted (*AndroidNotificationService*, applier, warranty/receipt/bill workers, NotificationIdGenerator, briefing use case, BudgetMonitor, BudgetAlertPipeline, AnomalyAlert, SourceScanning) vr-20261023-185650-29c58b86 PASS; cancellation vr-20261023-190254-51abe6a1 PASS; data vr-20261023-190309-de79b4b5 PASS; domain-n-z vr-20261023-190656-efcf3b2f PASS.
- Reviews: reviewer-strict ACCEPT-WITH-MINORS (verified row by row that every removed legacy test/assertion is covered by a cited kind-aware test or is obsolete with the removed API; nothing to restore); architecture-guardian ACCEPT-WITH-MINORS. privacy-security-guardian (S9 lead) runs on S9b (classifier/sweep), where the privacy surface is; S9a is pure API removal.
- S9b carry-forwards: rename ReceiptMatchingWorkerTest ~:764 / WarrantyExpirationWorkerTest ~:326 "never the legacy or BUDGET path" → "never BUDGET or the other ForKind form"; ReceiptMatchingWorkerTest ~:438/:470 comments "sendBudgetAlert throws" → postBudgetAlertForKind.
- S13 docs carry-forwards: LEGAL_PATHS ~:1075 "NotificationService.send()" → postAnomalyAlertForKind(ANOMALY, …); add a notification-posting legal-path entry (kind-aware methods only, INV-12; NotificationPresentationApplier shared presentation owner; exactly two builder owners: AndroidNotificationService.postForKind and BillReminderWorker); ENGINE_INTERACTION_MAP ~:129-133 add NotificationService/applier rows; CODEBASE_SEGMENTS / CODEBASE_INVENTORY list NotificationPresentationApplier/Resolver/NotificationKind.
- Cross-lane: see lanes-board (expanded list of ai-fixes legacy call sites; B3a requirement).
Next: S9b (NotificationKindClassifier, repost sweep, DiscreetRepostTrigger, UpdateDiscreetNotificationsUseCase).

## S9b pass 1 (2026-10-23) — implemented (validation pending)
Scope: classifier, sweep, service contract, tests, S9a carry-forwards. DiscreetRepostTrigger and UpdateDiscreetNotificationsUseCase are PASS 2 (not started).

Source:
- NEW `data/service/NotificationKindClassifier.kt`: `classify(channelId, notificationId, rawMarker): NotificationKind?` and `isSweepChannel`. Marker first only when present, known token and allowed on the channel; else the 4.6 table (budget_alerts 10000-19999 WARRANTY, 20000-29999 RECEIPT_MATCH, other BUDGET; bill/anomaly/ai by channel); other channels null. Imports ONLY `BillReminderWorker.CHANNEL_ID` from the worker (S8 ruling). The three service channel ids are now `internal const` in `AndroidNotificationService.Companion` (no duplicated literal).
- `domain/service/NotificationService.kt`: `suspend fun repostActiveAsDiscreet(): RepostResult` plus `data class RepostResult(reposted, failed)` and `RepostResult.NONE`.
- `data/service/AndroidNotificationService.kt`: constructor gains `privacyDiagnostics: PrivacyDiagnosticsEmitter` (4th param). Sweep: one `activeNotifications` call, classifier, `rebuildDiscreet` (fresh builder, DISCREET decision, applier stamps the kind, reuses id, tag, channel, contentIntent, action PendingIntents mapped by position onto the resource-titled BILL templates, `when`; `setOnlyAlertOnce(true)`), `SecurityException` per notification skips and emits NOTIF_REPOST_FAILED(exception class), no retry, CancellationException propagates (only SecurityException is caught), NOTIF_REPOSTED_DISCREET(count) emitted when count > 0. Small shared refactor: `PostShape` (icon, priority, category) per channel now used by both the kind-aware posts and the sweep, values unchanged.
- `domain/notification/presentation/NotificationContentPolicy.kt`: NEW `decideDiscreet(kind, sdkInt)` (DISCREET, no fallbackReason) so the sweep needs no second settings read and still gets its decision from the single policy owner (INV-4). Not in the plan write set; flagged for the architecture gate.

Tests: NEW `NotificationKindClassifierTest` (pure JVM), NEW `AndroidNotificationServiceRepostTest` (Robolectric, sdk 34; AE-15, AE-102 x4, AE-112 marker-less and with markers, AE-121, FGS untouched, no sentinel via `PresentationFixtures.everyString`, nothing copied, tag/id/channel/contentIntent/when preserved, silent, one query). `AndroidNotificationServiceTest`, `AndroidNotificationServicePresentationTest`, `WarrantyExpirationWorkerTest`, `ReceiptMatchingWorkerTest`: constructor gets a relaxed emitter mock. `NotificationIdGeneratorTest` fake implements `repostActiveAsDiscreet`. S9a carry-forwards done: two test renames ("never BUDGET or the other ForKind form") and two comments (postBudgetAlertForKind).

Diagnostics used (existing codes): NOTIF_REPOSTED_DISCREET (COUNT), NOTIF_REPOST_FAILED (EXCEPTION_CLASS).
Residuals: BILL builder shape (icon, priority) and action templates are duplicated from `BillReminderWorker` inside `AndroidNotificationService` (BILL_SHAPE, BILL_ACTION_TEMPLATES) because the S8 ruling forbids importing other worker members; keep in sync. Non-SecurityException failures from `notify` propagate out of the sweep (pass 2 use case decides handling). A `NotificationContentPolicy.decideDiscreet` unit test is still to be added by test-author.
Pass 2 todo: `DiscreetRepostTrigger` (reused by S18), `UpdateDiscreetNotificationsUseCase` and their tests (AE-105 no sweep on failed write, turning discreet off never re-posts, INV-46 trigger only after commit); Hilt wiring if any.
Validation requested: compile (main + test), targeted `*AndroidNotificationService*`, `*NotificationKindClassifier*`, `*NotificationIdGenerator*`, `*WarrantyExpirationWorker*`, `*ReceiptMatchingWorker*`, `*NotificationContentPolicy*`, `*SourceScanningArchitectureGuardTest*`.

## S9b pass 2 (2026-10-23) — implemented (validation pending)
Source (domain/privacy/applock, no new infrastructure, no Hilt module change: both classes are concrete with `@Inject` constructors; `NotificationService` and `AppLockSettingsRepository` are already bound):
- NEW `DiscreetRepostTrigger.kt` (reused by S18): `onCommittedWrite(wasEffectiveDiscreet, isEffectiveDiscreet): DiscreetRepostOutcome` (`NOT_TRIGGERED`, `SWEPT`, `SWEEP_FAILED`). Calls `NotificationService.repostActiveAsDiscreet()` only on the false to true edge. `CancellationException` rethrown first; any other `Exception` is caught at this boundary, recorded as `NOTIF_REPOST_FAILED` with `exceptionClass` (class simple name only) and returned as `SWEEP_FAILED`, so the committed write is not failed. Callers must call it only after a committed result.
- NEW `UpdateDiscreetNotificationsUseCase.kt` with `UpdateDiscreetNotificationsResult` (`Committed(discreetNotifications, repost)`, `Rejected(reason)`, `Failed(field)`). Reads `getLoadState()` before the write (previous effective value via `EffectivePrivacyValues`), writes through `AppLockSettingsRepository.setDiscreetNotifications` (the only legal writer), and on `Committed` only passes before/after effective values to the trigger. `Failed` records `LOCK_SETTING_PERSIST_FAILED(FIELD=DISCREET)` here (the repository leaves that diagnostic to its caller, per `AppLockSettingsRepositoryImpl` KDoc); `Rejected` records nothing and sweeps nothing.
- Rules implemented: INV-46 (sweep only after commit), INV-13/AMD-7 (false to true only), AE-105 (failed write, no sweep), turning off never re-posts, on to on is not an edge (no sweep; spec 4.6 trigger is "turns effectiveDiscreet from false to true").

Tests: NEW `DiscreetRepostTriggerTest` (all four edges, non-CE sweep failure swallowed with class-name-only metadata, CE propagates and records nothing), NEW `UpdateDiscreetNotificationsUseCaseTest` (in-memory committed-only fake repository: off to on one sweep, first-run default to on, on to on none, off none, AE-105 failed write none plus persist diagnostic with FIELD=DISCREET, rejected none, sweep throws still Committed plus NOTIF_REPOST_FAILED, CE from sweep and from write propagate), `NotificationContentPolicyTest` +3 tests for `decideDiscreet` (every kind x sdk: DISCREET, no fallback, private/generic; equals `decide(kind, discreetOn, sdk)`; action policy BILL AUTH_REQUIRED on sdk >= 31 else UNCHANGED).

Deviations / notes:
- Trigger takes before/after effective values instead of a single boolean; the plan did not fix a signature and S18 (lock-enable) needs the same edge detector.
- The before value is a separate `getLoadState()` read, not inside the repository's atomic edit. Safe in 1a (single writer of the field); S18 must pass its own before/after. Possible follow-up: have the repository return the previous committed value (not done; out of this slice's write set).
- No UI (S12 adds the switch); the use case has no caller yet, so nothing reaches it until S12.
- Not touched: guard files, baselines, `SourceScanningArchitectureGuardTest` scanner token (S9a carry-forward still open for the architecture gate).
Suggested validation: compile main + test; targeted `*AndroidNotificationService*`, `*NotificationKindClassifier*`, `*DiscreetRepostTrigger*`, `*UpdateDiscreetNotifications*`, `*NotificationIdGenerator*`, `*NotificationContentPolicy*`, `*WarrantyExpirationWorker*`, `*ReceiptMatchingWorker*`, `*SourceScanningArchitectureGuardTest*`; shards `data`, `domain-n-z`; registered-guard `cancellation`, `pii_logging`.

## S9b review fixes (2026-10-23) — implemented (validation pending)
A. Unit-test compile errors fixed: `AndroidNotificationServiceRepostTest` used `any<String?>()` (mockk `any` requires `T : Any`); all six uses are now `any<String>()` (a constant matcher, still matches a null tag). No other S9b test file uses a nullable type argument.
B1. Sweep totality (`AndroidNotificationService.repostActiveAsDiscreet`): marker read, classify, rebuild and notify are inside one per-notification `try`; `catch (CancellationException) { throw e }`, then `catch (Exception)` counts `failed++`, logs the class name only and records `NOTIF_REPOST_FAILED` (`emitRepostFailed` widened to `Exception`). A `SecurityException` from `activeNotifications` still returns `RepostResult(0, 1)` with one `NOTIF_REPOST_FAILED`. `NotificationService.RepostResult` KDoc says `failed` counts a query failure as 1; `repostActiveAsDiscreet` KDoc updated.
B2. `UpdateDiscreetNotificationsUseCase`: the before value is trusted only for `Loaded` / `FirstRunDefault`; `Unreadable` counts as was-off (a committed ON then sweeps; a redundant sweep is silent and content-free). KDoc: S12 must call it off Main from an application-scoped job, or wrap write + trigger in `withContext(NonCancellable)` (defers, does not swallow, cancellation). New test: unreadable before-read + committed ON gives exactly one sweep.
B3. NEW `service/reminder/BillNotificationSweepParityTest` (Robolectric sdk 34): runs `BillReminderWorker` once, then the sweep over the same notification, and asserts the same small icon resource, priority, action icon resources, action titles and order.
B4. `AndroidNotificationServiceRepostTest` +4 tests: `activeNotifications` throws `SecurityException` gives (0,1), one `NOTIF_REPOST_FAILED`, no notify; BILL original with no actions gets none invented; BILL original with one action keeps only that PendingIntent (Snooze template, no second action); `notify` throws `IllegalStateException` for one id gives (1,1), class-only record, next id still re-posted. `postOriginal` gained `actionNames` (default keeps the old `withActions` behaviour).
B5. `NotificationKindClassifier.isSweepChannel` removed (test-only use); `NotificationKindClassifierTest` updated (the not-a-sweep-kind test already asserts null for other channels; the four-channels test now asserts a non-null classification).

Carry-forwards:
- S13 / follow-up: move the BILL shape and action templates (BILL_SHAPE, BILL_ACTION_TEMPLATES) into `data/service`, shared by `BillReminderWorker` and the sweep (or match actions by icon id instead of position). Until then `BillNotificationSweepParityTest` pins the two.
- S18 seam (ii): the review-save path must also call `DiscreetRepostTrigger` (spec 4.6) with its own before/after effective values.
- 1b: `NotificationContentPolicy.decideDiscreet` must take the lock inputs, and its equality test against `decide` must include the lock states.
- `AppLockSettingsWriteResult.Committed` could carry the previous committed settings, which removes the separate before-read in the use case.
Suggested validation: compile main + test; targeted `*AndroidNotificationServiceRepost*`, `*BillNotificationSweepParity*`, `*NotificationKindClassifier*`, `*UpdateDiscreetNotifications*`, `*DiscreetRepostTrigger*`, `*BillReminderWorkerNotificationPresentation*`, `*NotificationContentPolicy*`.

## S9b privacy review fixes (2026-10-23) — implemented (validation pending)
Source: privacy-security-guardian findings.
1. MEDIUM, system group summaries: `AndroidNotificationService.repostActiveAsDiscreet` skips any active notification with `Notification.FLAG_GROUP_SUMMARY` set, before the marker read and classify. The app never posts summaries (no `setGroup` in `app/src/main`), so a summary on an app channel is the system's own auto-group summary (ranker_group). It is neither counted as reposted nor as failed and is left untouched.
2. Tests (test-author scope, assigned by the orchestrator): `AndroidNotificationServiceRepostTest` +3 (a group-summary original on budget_alerts stays untouched and is not counted; a non-BILL ANOMALY original with sentinel action titles is re-posted with no actions and no sentinel; group, sort key and large icon of the original are not copied; `postOriginal` gained a `customize` hook). `NotificationKindClassifierTest`: the production capture channel literal `expense_tracker_service` (`NotificationCaptureService.CHANNEL_ID`, private, so the literal) classifies to null, including with every kind marker.

Accepted residual (spec 4.6): a notification the user dismisses between the `activeNotifications` query and its re-post comes back as a NEW post and can alert on a HIGH-importance channel. `setOnlyAlertOnce(true)` does not silence a new post. `setSilent(true)` was rejected because NotificationCompat then assigns the group "silent", which changes shade grouping. The window is a few milliseconds and the content is discreet.

S18 decision carry-forward: the review-save path starts from `Unreadable` (effective true). Under the edge design (sweep only on false to true) it therefore never sweeps. S18 must decide this explicitly (for example by passing its own before value) and not inherit it by accident.
Suggested validation: targeted `*AndroidNotificationServiceRepost*`, `*NotificationKindClassifier*`.

## S9b DONE (2026-10-23)
- Validation (final tree): targeted vr-20261023-201242-775130e3 PASS; data vr-20261023-202429-cd3e3563 PASS; domain-n-z vr-20261023-202816-92ebec91 PASS; cancellation vr-20261023-202949-7092a328 PASS; pii_logging vr-20261023-203007-ec12a89b PASS. Earlier run vr-20261023-194409-466417b7 FAIL (test compile: nullable mockk type args; fixed).
- Reviews: privacy-security-guardian (lead) ACCEPT-WITH-MINORS (privacy PASS; group-summary skip fixed), reviewer-strict ACCEPT-WITH-MINORS (sweep totality, before-read fail-toward-sweep, parity + extra tests fixed), architecture-guardian ACCEPT-WITH-MINORS (all three deviations accepted).
- Plan write-set amendment (orchestrator): S9 also touches NotificationContentPolicy.kt (+ test) and adds BillNotificationSweepParityTest.kt.
- Carry-forwards:
  - Recommended follow-up (replaces "move into data/service"): one data/service holder (e.g. BillNotificationSpec) for CHANNEL_ID + BILL shape + action templates; BillReminderWorker imports it; the classifier then no longer imports the worker (removes the data → service/reminder edge, the duplication and the parity-test dependency). Until then, every gate on these files re-checks the const-only import by hand.
  - SourceScanningArchitectureGuardTest :147 scanner token: CLOSED with no change (substring + NotificationService token still catch every kind-aware form).
  - S12: serialize discreet writes (one app-scoped job or a mutex) and call the use case off Main from an application scope or NonCancellable.
  - S18: review-save starts from Unreadable (effective true) → never a false→true edge under this design; decide explicitly; seam (ii) review-save must also call DiscreetRepostTrigger.
  - 1b: decideDiscreet must take lock inputs; equality test includes lock states.
  - Accepted residual (§4.6): a notification dismissed between the query and its re-post comes back as a new post and can alert on HIGH channels.
  - S13 docs: LEGAL_PATHS NOTIFICATION PRESENTATION (builder owners: AndroidNotificationService postForKind + rebuildDiscreet sweep, and BillReminderWorker; sweep decision from decideDiscreet; classifier rules incl. group-summary skip) and APP LOCK settings write path (UI → UpdateDiscreetNotificationsUseCase → AppLockSettingsRepository.setDiscreetNotifications → on Committed false→true DiscreetRepostTrigger → repostActiveAsDiscreet); CODEBASE_SEGMENTS placement (classifier Segment 11; trigger/use case Segment 28 or 11 — decide); ENGINE_INTERACTION_MAP row for the post-commit re-post with NOTIF_REPOSTED_DISCREET(COUNT)/NOTIF_REPOST_FAILED(EXCEPTION_CLASS); DEPENDENCY_MAP/import-graph const-only edge; hilt-bindings-map (AndroidNotificationService 4 params); CODEBASE_INVENTORY three new files. Final sweep behaviour: per-notification catch(Exception) with CE rethrown first; no isSweepChannel.
Next: S12 (settings UI "App lock & privacy" section with the discreet row; 1a user switch). Check R17/R23/R19 landed per plan.

## S12 (2026-10-23) — implemented (validation pending)
Source (ui/screens/privacysettings; `PrivacySettingsViewModel` untouched):
- NEW `AppLockPrivacySettingsViewModel.kt` (@HiltViewModel; deps `AppLockSettingsRepository`, `UpdateDiscreetNotificationsUseCase`, `PrivacyDiagnosticsEmitter`, `@ApplicationScope CoroutineScope`). `uiState` is derived only from `observeLoadState()` (committed state, INV-46): `Loading` (null, never Off), `Ready(discreetOn, isWriting, notice)`, `TransientUnreadable` (IO_ERROR/TIMEOUT), `NeedsReview(discreetChoice default true, isSaving, notice)` (CORRUPTED/INVALID_VALUE). `setDiscreetNotifications` is ignored unless `Ready` and idle (INV-44; the repository rejects as well). `retry()` shows Loading and re-subscribes; `refreshIfTransient()` (called on ON_RESUME) re-reads only while transient (INV-47). `saveReview()` writes `AppLockPrivacySettings(discreet = choice)` through `repository.reviewSave` (1a record, no auth: INV-45 1a note), emits LOCK_SETTINGS_REVIEW_SAVED on commit and LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE) on failure (AE-89). Per-field failure -> notice SAVE_FAILED (the use case records LOCK_SETTING_PERSIST_FAILED(DISCREET)); `Rejected` re-subscribes the observer.
- NEW `AppLockPrivacySection.kt` (stateful `AppLockPrivacySection` + stateless `internal AppLockPrivacySectionContent`, `AppLockPrivacySectionTags`). Own header (same styling as `SectionHeader`), own trailing `HorizontalDivider`. Loading draws NO switch (text + "Checking...", disabled semantics). Rows are one merged toggleable node (`Role.Switch`), inner Switch has no handler.
- `PrivacySettingsScreen.kt`: ONE insert `item { AppLockPrivacySection() }` before the Notification Capture section (after Disabled Features / error line, ux 2.1).
- Resources: NEW `values{,-el,-es}/strings_privacy_section.xml` (12 ids: privacy_section_app_lock, privacy_toggle_discreet_notifications, privacy_discreet_notifications_support, privacy_discreet_notifications_unreadable, privacy_app_lock_checking, privacy_section_unreadable, privacy_app_lock_retry, privacy_review_card_title/_body/_save, privacy_review_saved, privacy_setting_save_failed). el/es are best effort: OWNER REVIEW pending. `privacy_section_unreadable` and `privacy_setting_save_failed` are new ids (1a-only wording: the ux transient text mentions the lock). The 1b-only ids (forced, lock, timeout, secure, PIN) are not added.
Carry-forwards from S9b handled: (1) serialisation: one process-wide write lock (superseded by review fix 2: singleton `AppLockSettingsWriteSerializer`) around every write, so two instances of the ViewModel (screen left and re-entered) cannot interleave before-read, write and sweep; (2) the use case runs in the `@ApplicationScope` (IO dispatcher, SupervisorJob), inside `withContext(NonCancellable)`, so a committed ON cannot miss its sweep when the screen is left. CancellationException is rethrown first in every catch.
Deviations: (a) the ux "Settings saved" snackbar is an inline notice (the screen has no SnackbarHost; no screen change beyond the one insert); (b) the notice is cleared by the next write or a load-state category change (`dismissNotice()` removed in the review fixes); (c) retry shows Loading ("Checking...") while re-reading.
Tests (assigned in the S12 write set): NEW `AppLockPrivacySettingsViewModelTest` (Loading first; Ready; committed-only display; AE-105 failure; non-CE exception; rejected re-read; AE-88 persistent/transient blocked; retry/foreground re-read; review-save success/failure (AE-85 1a, AE-89); two-instance serialisation; write survives the screen scope), NEW `AppLockPrivacySectionTest` (createComposeRule under Robolectric sdk 34; Loading has no toggle; On/Off/disabled; transient; review card; notices).
Not run: no build, test or guard was executed. Risks to check on first compile: mockk of final classes; `data object`; Role import; Robolectric Compose rule needing ui-test-manifest (debug).
Suggested validation: compile main + test; targeted `*AppLockPrivacy*`, `*PrivacySettings*`; shard `runtime-ui`; `lint` (new strings in 3 locales, MissingTranslation); registered-guard `ui_dao`.

### S12 review fixes (after reviewer-strict REJECT + privacy-security-guardian ACCEPT-WITH-MINORS; validation pending)
1. `AppLockPrivacySectionTest`: `createComposeRule()` is wrapped in `RuleChain.outerRule(ExternalResource)` that registers `androidx.activity.ComponentActivity` with Robolectric's package manager (`shadowOf(pm).addActivityIfNotPresent`), so `testReleaseUnitTest` resolves the activity. `ui-test-manifest` is NOT added to release/main.
2. Write serialisation moved from a static companion `Mutex` to the `@Singleton AppLockSettingsWriteSerializer` (new file, `@Inject constructor()`), injected into the ViewModel; tests construct a fresh one (shared between two ViewModels in the serialisation test). Test gates are completed in try/finally.
3. `startObserving()` runs on Main only: the Rejected branches use `viewModelScope.launch { startObserving() }` (`resubscribeOnMain`); `retry()` / `refreshIfTransient()` unchanged (Main).
4. Review-save `Rejected` is reachable (repository returns `Rejected(UNREADABLE)` when the read after a failed commit is transient): comment fixed; keeps SAVE_FAILED + LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE); re-subscribes via the Main-safe path.
5. `reviewChoice` resets to `AppLockPrivacySettings.FAIL_CLOSED.discreetNotifications` (a) on a committed review-save and (b) whenever the observed state moves from a non-review category into persistent-unreadable (single `applyLoadState` entry). Tests for both.
6. Notices: cleared when the load-state category changes (Transient/Review to Ready etc.), except a REVIEW_SAVED notice survives the move into Ready it announces. After a committed review-save the card's Save stays disabled (`reviewCommitted`, reset on every move into the review state) until the observer shows a readable state, so no duplicate review-save. `dismissNotice()` removed.
7. Non-IO exception in `saveReview` also records LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE). Diagnostic emits are wrapped (`emitSafely`, CE rethrown) so a diagnostics failure cannot turn a committed save into a reported failure.
8. Accessibility: `NoticeText` has `liveRegion = Polite`; `CheckingRow` drops the duplicate `stateDescription` (the visible merged text is announced).
9. Strings: es "Factura pendiente" to "Factura por pagar" (matches `strings_notification_presentation.xml`); `privacy_section_unreadable` ends "...until the settings can be read." (en) with equivalent es/el wording; "Checking…" / "Comprobando…" / "Έλεγχος…" use the single ellipsis character.
Owner judgement note: ux-flows ~:269 transient view shows the row with no checked state; the implementation shows discreet On + disabled (fail-closed). Owner to confirm.
### S12 round-2 minors (after second review; validation pending)
1. `AppLockPrivacySettingsViewModel.setDiscreetNotifications`: the `catch (Exception)` now also calls `recordPersistFailed(AppLockSettingField.DISCREET)` (through `emitSafely`), so both write paths record LOCK_SETTING_PERSIST_FAILED on an unexpected non-IO exception. CancellationException is still rethrown first.
2. `AppLockPrivacySettingsViewModelTest`: new test where the emitter throws `IllegalStateException` on LOCK_SETTINGS_REVIEW_SAVED after a committed review-save; asserts `Ready(true, false, REVIEW_SAVED)` (isWriting=false) and no LOCK_SETTING_PERSIST_FAILED.
3. Same test file: the three `emit(LOCK_SETTING_PERSIST_FAILED, any())` verifications now match `entries[PrivacyDiagnosticKey.FIELD] == REVIEW_SAVE` (helper `MockKMatcherScope.fieldIs`); the non-IO discreet-exception test additionally verifies `FIELD == DISCREET`.
Owner-visible questions:
- (a) The transient-unreadable view shows discreet ON and disabled (fail-closed) vs ux-flows ~:269 (no checked state). Owner to confirm.
- (b) A discreet-write `Rejected` whose re-read is readable gives no notice (the tap is silently ignored; the observer shows the true state). Intended?

S13 carry-forward: LEGAL_PATHS must record the 1a review-save path (ViewModel -> `AppLockSettingsRepository.reviewSave`; ViewModel emits LOCK_SETTINGS_REVIEW_SAVED / LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)); 1b moves authorisation into AppLockController.
S18 carry-forward: move write serialisation from `AppLockSettingsWriteSerializer` (ui) to the domain owner before any new writer is added.
Suggested validation: targeted `*AppLockPrivacy*`, `*PrivacySettings*`; targeted-release-unit-test `*AppLockPrivacySection*`; lint.

## S12 DONE (2026-10-24)
- Reviews: privacy-security-guardian (lead) ACCEPT-WITH-MINORS; reviewer-strict round 1 REJECT (release unit-test ComponentActivity registration) → fixed; round 2 ACCEPT-WITH-MINORS → round-2 minors fixed; architecture-guardian ACCEPT-WITH-MINORS.
- Validation (final tree): targeted vr-20261023-213537-9244b33d PASS; targeted-release-unit-test *AppLockPrivacySection* vr-20261023-214023-4d68bee3 PASS; lint vr-20261023-215341-ff579ddc PASS; ui_dao vr-20261023-224218-ec04c68b PASS; runtime-ui vr-20261023-224234-a90e1ea8 PASS.
- OWNER 2026-10-24 (owner-approvals 15, 16): transient view ON+disabled confirmed; a refused discreet tap (Rejected, re-read readable) must show the SAVE_FAILED notice → small follow-up commit next.
- Plan write-set amendment (orchestrator): S12 adds ui/screens/privacysettings/AppLockSettingsWriteSerializer.kt (review-driven; interim owner).
- S18 BINDING carry-forward (architecture-guardian): domain code cannot depend on the ui package, so S18 must move write serialization (AppLockSettingsWriteSerializer) to the domain owner and edit AppLockPrivacySettingsViewModel BEFORE AppLockController writes settings; S18 also takes over review-save diagnostics ownership (1a: ViewModel emits LOCK_SETTINGS_REVIEW_SAVED / PERSIST_FAILED(REVIEW_SAVE)).
- S13 docs carry-forwards (required): CODEBASE_SEGMENTS placement of the three new UI files (serializer marked interim, retired at S18); hilt-bindings-map: @Singleton AppLockSettingsWriteSerializer + the ViewModel's @ApplicationScope use (map has no AppLock entries yet → stale by construction; promote to required); LEGAL_PATHS app-lock settings write path: both 1a writers run under AppLockSettingsWriteSerializer in @ApplicationScope + withContext(NonCancellable), review-save diagnostics owned by the ViewModel in 1a (interim).
Next: S12 follow-up (Rejected → SAVE_FAILED notice), then S13 (docs/inventory + final 1a acceptance prep), then G1a.

## S12 follow-up (2026-10-24) — Rejected shows SAVE_FAILED
- Owner decision (owner-approvals 16): a per-field discreet write answered Rejected, whose re-read is readable, shows the SAVE_FAILED notice instead of a silently ignored tap.
- Code: `AppLockPrivacySettingsViewModel.handleDiscreetResult` Rejected branch sets `notice = SAVE_FAILED` and still re-subscribes via the Main-safe `resubscribeOnMain()`. No new diagnostic (nothing was written). `applyLoadState` unchanged: it clears the notice only on a load-state category change, so a re-read that lands in the same READY category keeps it; `beginWrite` clears it on the next write.
- Tests (`AppLockPrivacySettingsViewModelTest`, owner-approved change): old "rejected write clears nothing and re-reads the store" renamed to "rejected write shows SAVE_FAILED, survives the same category re-read and records no diagnostic" (asserts Ready(false,false,SAVE_FAILED), 2 observe calls, no PERSIST_FAILED emit); new "SAVE_FAILED from a rejected write clears on the next successful write".
- Residual: if the observer had already flipped to a transient/review category before the notice was set, the later re-read into READY clears it (category change); the notice is not shown in those unreadable states anyway.
- Validation pending: targeted `*AppLockPrivacySettingsViewModel*`.
Next: S13 (docs/inventory + final 1a acceptance prep), then G1a.
- Validation: targeted *AppLockPrivacySettingsViewModel*,*AppLockPrivacySection* vr-20261023-225040-6db7e0dd PASS. Committed.

## S13 (2026-10-24) — implemented
Docs only (no source, test, guard, baseline or allowlist change). No validation run (plan: architecture-guardian + a cheap docs-accuracy reviewer; FG-26). Every claim was checked against current source first.

Docs touched (all under the worktree C:\Users\panos\Desktop\Cost-agregator-al):
- `docs/architecture/LEGAL_PATHS.md`: NEW sections NOTIFICATION PRESENTATION, DEEP LINKS and APP LOCK SETTINGS WRITE PATH; DISPATCH reminder gains the resolve -> applier step; Anomaly Alerting `NotificationService.send()` corrected to `postAnomalyAlertForKind(ANOMALY, ...)` (in-section only); last-updated line.
- `docs/architecture/CODEBASE_SEGMENTS.md`: Segment 11 (policy, resolver, applier, texts, marker, classifier, kind registry, strings; sweep executor) and Segment 28 (settings store/impl/module, emitter, trigger, use case, deep-link types, DeepLinkPolicy/Applier in ui/navigation, briefing-opened use case, privacysettings UI files, interim serializer retired at S18, strings). Placement decision: trigger + use case = Segment 28 (settings write path); no segment owned `ui/navigation` before, so the deep-link boundary is recorded under Segment 28 with its paths.
- `docs/architecture/NAVIGATION_ARCHITECTURE.md`: components table, tests table, invariant 6, Deep Link Security section rewritten (policy, applier, no BROWSABLE, 1b gate).
- `docs/architecture/UI_REFERENCE_INDEX.md`: deep-link hosts policy paragraph, pattern 5, PrivacySettings entry.
- `docs/architecture/COMPREHENSIVE_UI_MAP.md`: manifest snippet (BROWSABLE removed, DEFAULT added), section 8.5 deep-link handling/parameters, Privacy Settings screen "App lock & privacy" section.
- `docs/architecture/CODEBASE_INVENTORY.md`: 2026-10-24 drift-sync block (new files), ViewModels, navigation infrastructure, domain packages, DI (AppLockModule), manifest line.
- `docs/architecture/hilt-bindings-map.md`: ServiceModule `AndroidNotificationService` 4 params; NEW `AppLockModule` section with the auto-provided app-lock/presentation singletons (`@Singleton AppLockSettingsWriteSerializer`, the ViewModel's `@ApplicationScope` use, BillReminderWorker extra params); header and stats.
- `docs/architecture/ENGINE_INTERACTION_MAP.md`: matrix rows (BillReminderWorker updated; NotificationService/AndroidNotificationService; applier/policy/resolver; classifier/marker; post-commit re-post with NOTIF_REPOSTED_DISCREET(COUNT) / NOTIF_REPOST_FAILED(EXCEPTION_CLASS); DeepLinkPolicy/Applier), impact chains, safe/dangerous lists.
- `docs/architecture/import-graph.json` and `docs/architecture/DEPENDENCY_MAP.md`: BillReminderWorker dependencies (resolver, applier), const-only edge NotificationKindClassifier -> BillReminderWorker.CHANNEL_ID, and removal of a stale "BillReminderWorker -> NotificationService" line (the worker uses no NotificationService).
- `pr-e-actual-release-verification-plan.md` section 6.4: status note (what 1a meets, the confirmation screen NOT implemented, evidence owed at G1a).
- NEW `docs/features/app-lock-privacy-mode/release-note-1a.md` (owner review pending; ai-fixes B3a paragraph dropped; lock-screen wording corrected from "Expense Tracker" to the real generic pair "Reminder" / "Open the app to see details.").
- Checked, no change needed: ARCHITECTURE.md, COMPLETE-BACKEND-MAP.md, BACKEND-MAP-INDEX.md, BACKEND-DEPENDENCIES.md (file names only, no stale signatures).

Carry-forwards closed:
- S11: COMPREHENSIVE_UI_MAP manifest snippet (BROWSABLE out, DEFAULT in); DEEP LINKS section in LEGAL_PATHS.
- S8: import-graph BillReminderWorker dependencies; LEGAL_PATHS DISPATCH reminder resolve -> applier.
- S9a: LEGAL_PATHS stale `send()`; notification-posting legal-path entry (kind-aware only, applier = shared owner, two builder owners); ENGINE_INTERACTION_MAP NotificationService/applier rows; SEGMENTS/INVENTORY listings.
- S9b: sweep and classifier rules (marker first, channel/id-range fallback, group-summary skip, per-notification catch with CE rethrown, no isSweepChannel); APP LOCK write path to trigger to sweep; Segment placement decided; ENGINE re-post row with diagnostics; const-only edge in import-graph + DEPENDENCY_MAP; hilt map 4 params; INVENTORY new files.
- S12: SEGMENTS placement of the three UI files (serializer interim, retired at S18); hilt map serializer + ViewModel `@ApplicationScope`; LEGAL_PATHS write path with both 1a writers under the serializer in `@ApplicationScope` + `NonCancellable`; review-save path and ViewModel-owned diagnostics (interim, S18); noted that 1a review-save does not call DiscreetRepostTrigger.
- Plan list: NAVIGATION_ARCHITECTURE, UI_REFERENCE_INDEX, pr-e section 6.4, release-note-1a.

NOT closed / still open (not docs work):
- S9b recommended follow-up: one data/service holder for BILL channel id + shape + action templates (removes the classifier -> worker edge and the BILL_SHAPE/BILL_ACTION_TEMPLATES duplication). The docs describe the current duplication and the parity test.
- S18 BINDING: move write serialisation out of ui, take over review-save diagnostics, decide the review-save false -> true edge.
- G1a evidence: merged release manifest (no BROWSABLE), AE-16, malformed `am start`; documented as owed in pr-e 6.4 and the release note.
- es/el copy owner spot-check; release note owner review (APP-4).
- Docs-accuracy reviewer + architecture-guardian gates for S13 not yet run.
- Merge note: feature/ai-fixes separately set the hilt-bindings-map.md header to 34 with `AppForegroundModule`. At merge, hilt-bindings-map.md :3 and the stats line (~:526) become 35 (33 + `AppLockModule` + `AppForegroundModule`).
- For G1a reviewer-strict: check whether `PrivacyDiagnosticsEmitter.emit` can throw inside the sweep's per-notification catch (`emitRepostFailed`); an emit throw there could escape the per-notification handling.
Next: S13 gates, then G1a.

## S13 DONE (2026-10-24)
- Review: architecture-guardian (+ docs accuracy) ACCEPT-WITH-MINORS; fixes applied (import-graph dangling nodes, release-note lock-screen wording, deep-link path wording, LEGAL_PATHS SWEEP_FAILED clause, hilt count note). Validation: guard_docs_truth vr-20261023-234156-7e4539fb PASS (docs-only slice; later edits docs-only; import-graph.json JSON-validated by the orchestrator).
Next: G1a (increment 1a gate): unit-tests → static-guards → app-check → assemble-release → release-artifact-evidence, plus device checks on approved AVDs (wave5-api35, release-api26): AE-14, AE-16, API 31+ auth-required Snooze/Dismiss on a locked device, AE-12 (API 26), AE-15/AE-102. Merged-manifest evidence (aapt2 dump) for INV-20; malformed `am start` no-crash.

## G1a device test (2026-10-24)
- File: `app/src/androidTest/java/com/yourname/expensetracker/data/service/NotificationPresentationDeviceTest.kt` (plain AndroidJUnit4, no Hilt, no mocking library, so it runs on API 26 where inline mocking is unavailable). Authored without being run; the first device run is the first compile of it.
- Run: `connected-test-class -TestFilter com.yourname.expensetracker.data.service.NotificationPresentationDeviceTest -DeviceSerial emulator-5554` on API 35 and again on API 26.
- Setup: real `AndroidNotificationService`, `NotificationPresentationApplier`, `NotificationPresentationTexts`, `NotificationPresentationResolver` on the target context, real system NotificationManager. A scripted settings repository gives FULL or DISCREET; the diagnostics emitter is the real class over no-op writer/sink fakes. POST_NOTIFICATIONS is granted with `UiAutomation.grantRuntimePermission` on API 33+. BILL is posted the way BillReminderWorker does (resolver, applier, Snooze/Dismiss broadcast PendingIntents) because the worker's send method is private. Sentinel strings are distinctive on purpose (merchant, person, amount). The system's own auto-group summary is excluded from counts.
- Proves on a device:
  - AE-14 structure: all six kinds in FULL and DISCREET are VISIBILITY_PRIVATE with a public version that is exactly the generic pair (no actions, ticker, style, content intent, marker, sentinel) after the system round trip.
  - AE-111: DISCREET leaks no sentinel in a recursive extras scan; a FULL control proves the scan is not vacuous.
  - AE-112/AE-121 marker survival: the kind token is in `getActiveNotifications()` extras for every kind and mode, on the private notification only, in no text field.
  - AE-32: PendingIntent introspection (FLAG_NO_CREATE lookup matches on request code and `filterEquals`) shows an activity PendingIntent with the explicit MainActivity component and the expected data exists, and that the same target without the component is a different PendingIntent. Supplementary `dumpsys activity intents` check: every startActivity record for the app has `cmp=<pkg>/...MainActivity`. It only logs, and does not assert, if the dump layout is not recognised on the API level.
  - INV-10 on API 31+: BILL Snooze/Dismiss have `isAuthenticationRequired == true` in FULL and DISCREET (plus a control that a plain action reports false). Below 31 only the action titles and PendingIntents are checked.
  - AE-15/AE-102: after posting FULL, `repostActiveAsDiscreet()` re-posts all six in place (`RepostResult(6, 0)`), keeping id, tag, channel, original `when` and the tap PendingIntent, adding FLAG_ONLY_ALERT_ONCE (absent on the originals), with no sentinel anywhere, a fresh marker, and the same active count.
  - AE-112 upgrade path 1 (marker-less fallback): originals posted without a marker are classified by channel and id range, and the re-post gives each kind its own discreet constants.
  - AE-12: the same class on API 26 shows the 1a presentation and re-post paths work there (UNCHANGED BILL actions).
- Still manual (not provable by this test):
  - True lock-screen rendering: the public version actually shown with "hide sensitive content" on, and the shade/lock-screen screenshot for AE-14.
  - Tapping Snooze or Dismiss on a locked device and seeing the unlock prompt (API 31+), and that no receiver runs before unlock.
  - Audible/visual silence of the re-post (the test checks FLAG_ONLY_ALERT_ONCE and the original `when`, not the sound).
  - AE-112 upgrade path 1 across a real app update (pre-1a build installed, then the 1a build): this test simulates marker-less originals with the same shape but does not install the old build.
  - AE-16 (browser link does nothing) and the FG-22 malformed-link `adb am start` no-crash checks: separate shell steps.
- Risks to watch on first run: if the `RestoreMaintenanceMode` or `NotificationDiagnosticEmitter` constructors fail to wire on a device, the failure is in test setup (`diagnostics()`), not in the code under test. A leak-scan failure on `android.appInfo`-style Parcelable strings would be a fixture issue (sentinels are distinctive to avoid it).

## G1a fixes (2026-10-24)
Source: first API 35 device run (emulator-5554, wave5-api35). Implemented, validation pending (unit `AndroidNotificationServiceRepostTest`, then a device rerun of `NotificationPresentationDeviceTest`).

Root causes
- (a) Test bug: the INV-10 device test ran the whole-notification no-sentinel scan in FULL mode, where the detailed title and text legitimately carry the merchant. Fix: in FULL only the action titles/extras and the public version are scanned; DISCREET keeps the full scan. The `isAuthenticationRequired` assertions are unchanged.
- (b) Product risk (fail-open privacy gap): Android's NotificationManagerService silently drops updates to already-posted notifications once a package exceeds the enqueue rate (`max_notification_enqueue_rate`, default 5/s); `notify()` does not throw. `AndroidNotificationService.repostActiveAsDiscreet` re-posted every active notification back-to-back, so some updates could be dropped and stay FULL. On the device the sweep returned `RepostResult(6, 0)` yet the titles stayed FULL.

Product fix
- `AndroidNotificationService`: the sweep now pauses `SWEEP_REPOST_SPACING_MS = 300L` before EVERY re-post, the first included (so a post from any source just before the sweep cannot push the first update over the rate; sweep rate about 3.3/s, worst case about 4.2/s with one foreign post, under the 5/s limit; added time is bounded by re-posts x 300 ms). Skipped notifications (summary, non-sweep kinds) cost no pause. The pause goes through an internal `fun interface SweepPacer` (production `DELAY_PACER` = kotlinx `delay`, cancellable). The primary constructor is now `internal` and takes the pacer; the `@Inject` constructor keeps the 4-argument signature and fixes `DELAY_PACER`, so Hilt and all existing callers are unchanged. `CancellationException` from the pacer propagates through the existing per-notification catch (rethrown). NonCancellable comes from the CALLER (`AppLockPrivacySettingsViewModel`: `appScope.launch { withContext(NonCancellable) { ... } }`), NOT from `DiscreetRepostTrigger`. For S18: the lock-enable caller must also run the trigger under NonCancellable. KDoc/comments record the rationale. No global setting is changed. `RepostResult.reposted` and the `NOTIF_REPOSTED_DISCREET` COUNT count `notify()` calls accepted without throwing, NOT updates shown (documented in `NotificationService.kt`).
- Device test hardening: `postAll` and a new `postAndAwait` wait for the mode's title (sentinel in FULL, kind constant in DISCREET), not only the id, so a dropped update over an existing id can no longer pass silently; the test's own posts are spaced 250 ms (`POST_SPACING_MS`), and a 1 s quiet gap (`PRE_SWEEP_QUIET_MS`) separates the last post from `repostActiveAsDiscreet` in AE-15/AE-102 and AE-112, so the measured result does not depend on platform rate state. The paced sweep itself must make AE-112/AE-15 pass on the device.
- Unit tests in `AndroidNotificationServiceRepostTest`: 7 active notifications give 7 pauses of 300 ms, each notify preceded by exactly one more pause (first included); one re-post plus one skipped notification gives exactly one pause; a sweep of only skipped notifications gives none; cancellation thrown by the pacer on its 2nd pause propagates, leaves 1 notify call and records nothing.
- Residual risk: posts by other code DURING the sweep are not paced. Any budget/anomaly/bill post within 300 ms before a sweep update can still cause a drop; the window is about N x 300 ms. `BillReminderWorker` posts directly via `NotificationManager`, so the service cannot track it. `reposted` is not proof of display.
- G1b carry-forward (post-sweep verification): stamp a content-free presentation-mode token ("D"/"F") in the applier extras next to `NotificationKindMarker.EXTRA_KEY`; after the sweep settle >= 500 ms to 1 s, re-read the active notifications and re-post once, with the same pacing, any sweep-kind notification whose token is NOT "D" (an absent token counts: pre-1a / marker-less originals and the FULL-posted-after-the-query residual); keep the token off the public version; emit one bounded COUNT-only diagnostic (e.g. `NOTIF_REPOST_UNCONFIRMED`) through the diagnostic registry; never block cleanup.

Device evidence so far (orchestrator, 2026-10-24, emulator-5554 = wave5-api35, SDK 35)
- APK: Cost-agregator-al app/build/outputs/apk/release/app-release.apk sha256 099060211a9222ee26acfd46ed8b0713696bcf6fabde384b4b6a6c369ce15190 (release-artifact-evidence vr-20261024-011932-940e19da; debug-signed, debuggable=false, v1.0.2(3)); installed with `adb install -r -d`.
- INV-20 merged-manifest evidence: `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` shows 0 occurrences of BROWSABLE; expensetracker scheme data entries present on the VIEW filter.
- AE-16: `am start -a VIEW -c BROWSABLE -d expensetracker://home` gives "unable to resolve Intent"; `cmd package query-activities -a VIEW -c BROWSABLE -d expensetracker://home` gives "No activities found". Control: same query with DEFAULT resolves to MainActivity (non-browser VIEW still works).
- FG-22 runtime: 10 malformed/hostile links via `am start -W -n .../.ui.MainActivity -a VIEW -d <uri>` (empty host, extra path, unknown host, non-numeric/negative expenseId, bad percent-encoding, upper-case host, 3000-char query, opaque URI, bank host): process stayed alive for all; logcat crash buffer FATAL count 0.

### G1a device runs (2026-10-24, after the pacing fix)
- Unit: targeted *AndroidNotificationService*/sweep parity/workers/trigger vr-20261024-085841-3ff05225 PASS.
- NotificationPresentationDeviceTest (11 tests): API 35 wave5-api35 vr-20261024-090413-9d37fde7 PASS (11/0/0/0); API 26 release-api26 vr-20261024-091202-b0f1c22c PASS (11/0/0/0). The API 26 run used -AllowOverlap -OverlapReasonCode REQUIRED_FINAL_GATE because both AVDs use serial emulator-5554 and the runner otherwise treats it as a duplicate of the API 35 run.
- Earlier API 35 run vr-20261024-082529-92f78c4f FAILED 2/10 (inv10 FULL-mode mode-blind scan = test bug; ae112 sweep updates dropped by the platform enqueue rate = product fail-open → pacing fix above).
- Both runs predate the commit of this section; the fingerprinted tree was the committed source.
- Still manual (owner phone, optional): true lock-screen rendering and tapping Snooze while locked.

## G1a review fixes (2026-10-24)
Base: worktree Cost-agregator-al, branch feature/app-lock-privacy-mode, HEAD c1d8e7c6. Implemented, validation pending (rerun after this pass).

Review verdicts (G1a)
- reviewer-strict Part A (notifications): ACCEPT-WITH-CONDITIONS.
- reviewer-strict Part B (settings, links, UI): ACCEPT-WITH-CONDITIONS.
- architecture-guardian: ACCEPT-WITH-MINORS.
- privacy-security-guardian: ACCEPT-WITH-MINORS.

Validation on c1d8e7c6 (before this pass; superseded by the rerun after it)
- app-check vr-20261024-092838-8e88db9e PASS.
- static-guards vr-20261024-091350-33ce89c4 PASS.

Fixed
- C1: `AppLockPrivacySettingsViewModel` now injects `DiscreetRepostTrigger`. A COMMITTED review-save runs `onCommittedWrite(wasEffectiveDiscreet = false, isEffectiveDiscreet = effective(committed settings))` inside the same appScope / NonCancellable / `AppLockSettingsWriteSerializer` block, after the review-saved notice and diagnostic. Rejected, failed and thrown saves never reach the trigger. A non-cancellation failure of the trigger call is logged by class name only and never turns the committed save into a failure. VM tests added: discreet on gives one `(false, true)` call; discreet off still calls the trigger with `(false, false)` (the edge detector, not the VM, decides NOT_TRIGGERED, so no sweep); refused / failed / thrown saves never call it.
- C2: `NavigationController` logs `event.destination::class.simpleName` only (the data-class `toString` carried link parameters such as initialLocationQuery / initialExpenseId; INV-18, AE-21). No existing test covers this log; none added.
- D1: release-note-1a refreshed (device checks done vs owed; residuals a, b, c; switch busy during the sweep; no instant-update promise).
- D2: domain-spec 4.6 "Active only" bullet now bounds the dismissed-in-window residual by N x 300 ms.
- D3: plan carry-forwards (binding) for G1b post-sweep verification and S18 (trigger under NonCancellable; serializer and review-save diagnostics move to the domain owner).
- D4: LEGAL_PATHS RE-POST sweep (pacing, residuals, G1b carry-forward, both triggers, two new FORBIDDEN lines), ENGINE_INTERACTION_MAP (pacing, review-save trigger chain), CODEBASE_SEGMENTS and hilt-bindings-map (`@Inject` secondary ctor with 4 params; internal primary adds `SweepPacer`), import-graph.json and DEPENDENCY_MAP (`AndroidNotificationService` to `MainActivity`, approved TECH-6).

Dispositions
- AE-112 evidence is the SIMULATED marker-less path. Gap: the legacy implicit content intent is not exercised. The AE-32 explicit-component property does not hold for swept legacy notifications until they are replaced.
- Handoff precision: the AE-112 originals are posted back to back; only the sweep UPDATES are subject to the platform rate drop.
- ReceiptMatchingWorker `PERMISSION_DENIED` records `RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED` (constant errorClass "SecurityException") without `addRowsSkipped`. Read against TECH-9 ("receipt -> existing PERMISSION_REVOKED") and the legacy `SecurityException` catch, which also omits `addRowsSkipped`: it matches.
- Pre-existing, not part of this release (follow-ups): `WarrantyExpirationWorker.kt:155` `Timber.e(e, ...)` (throwable in a log); no `dataExtractionRules` (D2D backup/transfer; relates to OD-19/C5).
- Switch-busy UX accepted: the discreet switch stays disabled for the duration of the sweep (seconds with a full shade); documented in release-note-1a.

Tests the test-author may still add: none required beyond the three VM tests above.
Validation to request: targeted `AppLockPrivacySettingsViewModelTest` plus the notification/sweep filters, then static-guards and app-check on the final tree.

### STOP POINT (2026-10-24, owner asked to stop after running tasks)
- The G1a review fix pass (C1 review-save sweep, C2 NavigationController log, D1–D5 docs) is in the worktree, UNCOMMITTED and NOT validated.
- Held gate jobs (orchestrator queue): unit-tests, assemble-release, release-artifact-evidence on c1d8e7c6 — obsolete; re-enqueue on the fixed tree instead.
- NEXT ACTION (serial, validation-runner only): targeted "*AppLockPrivacySettingsViewModel*,*AndroidNotificationService*,*DiscreetRepostTrigger*,*UpdateDiscreetNotifications*,*NavigationController*,*DeepLink*" → static-guards (import-graph.json / DEPENDENCY_MAP edits are guard-relevant; check guard_docs_truth) → app-check → unit-tests → assemble-release → release-artifact-evidence → connected-test-class NotificationPresentationDeviceTest on wave5-api35 and release-api26 (API 26 needs -AllowOverlap -OverlapReasonCode REQUIRED_FINAL_GATE). If green: commit + push, then a fresh-session final reviewer (Opus max) over bcd8e921..HEAD. Owner: APP-4 wording, OD-4a ship confirmation, whether G1b post-sweep verification must precede the 1a release.

### G1a runner gate on the review-fix tree (2026-10-25) — all PASS
- targeted (*AppLockPrivacySettingsViewModel*, *AndroidNotificationService*, *DiscreetRepostTrigger*, *UpdateDiscreetNotifications*, *NavigationController*, *DeepLink*) vr-20261025-181709-44725d2b
- static-guards vr-20261025-182714-da9715cd
- app-check vr-20261025-184519-5a8c6268
- unit-tests vr-20261025-191611-d281d801 (UP-TO-DATE: it replayed app-check's testDebugUnitTest on identical inputs; it is not an independent second run)
- assemble-release vr-20261025-191627-d86091f9
- release-artifact-evidence vr-20261025-192147-4632f361
- NotificationPresentationDeviceTest API 35 (wave5-api35) vr-20261025-192203-7a4f5f8a 11/11; API 26 (release-api26) vr-20261025-192651-e97805c8 11/11 (-AllowOverlap -OverlapReasonCode REQUIRED_FINAL_GATE, shared serial).
- All runs on the same worktree fingerprint (the uncommitted review-fix tree = this commit's content minus this section).
- Earlier device evidence still valid for unchanged surfaces: AE-16 / INV-20 aapt2 dump / FG-22 malformed links (see "G1a device test").
- Next: fresh-session final reviewer over bcd8e921..HEAD; owner APP-4 wording + OD-4a ship confirmation (+ whether G1b post-sweep verification must precede the 1a release).

## G1a final acceptance (2026-10-25)
Verdicts: Part 1 ACCEPT-WITH-CONDITIONS; Part 2 ACCEPT-WITH-CONDITIONS (two fresh reviewers, base HEAD 111087f9).

Fixed in this pass (UNCOMMITTED, NOT validated; needs a targeted run, see below):
- Docs: LEGAL_PATHS review-save line now says the trigger is called on Committed (stale "does NOT call" removed); release-note-1a user text no longer promises a quiet update (one notification at a time, rare notification may keep details, dismissed one may reappear discreet, new ones always discreet); accepted residuals (i) FULL decision resolved before the commit but posted after the sweep's query, (ii) process death mid-sweep, added to domain-spec 4.6, LEGAL_PATHS RE-POST sweep and the release-note owner notes; G1b carry-forward is now "re-post any sweep-kind notification whose token is NOT D (absent counts), token stays off the public version" in plan, LEGAL_PATHS and this handoff; hilt-bindings-map (AppLockPrivacySettingsViewModel 6 ctor params, adds DiscreetRepostTrigger); import-graph.json (nodes AndroidNotificationService and MainActivity added); ARCHITECTURE.md Hilt module count 34 and AppLockModule listed; collision list gains feature/manual-entry-currency; unit-tests run note above.
- Code (UI only): AppLockPrivacySection title has heading semantics (new tag TITLE); review card title and body are one merged node (new tag REVIEW_CARD_TEXT) with the save button separate. Tests added to AppLockPrivacySectionTest (heading; merged text group, button separate).
- Test hygiene: NotificationPresentationDeviceTest sweep BILL actions assertion now uses the awaited result instead of the earlier snapshot. This is androidTest source only; it is not exercised by unit-tests. A device rerun of NotificationPresentationDeviceTest (API 35, and API 26 with -AllowOverlap) is recommended but only to confirm; the change tightens a wait, it does not change production behaviour.
- Needed validation (serial, validation-runner): targeted "*AppLockPrivacySection*", then static-guards (import-graph.json and docs edits are guard-relevant), then app-check (compiles androidTest).

Open OWNER conditions:
- C-b: device scope. Either waive or run: AE-112 real pre-1a to 1a upgrade smoke, AE-14 real lock-screen rendering, tapping Snooze/Dismiss on a locked API 31+ phone.
- C-c: APP-4 release wording ("links from web browsers no longer open the app") and the updated notification-update paragraph in release-note-1a.md.
- OD-4a: ship confirmation.
- Whether G1b post-sweep verification must precede the 1a release (accepted residuals (a), (i), (ii) stay open until G1b).
- C-d: merge instructions. feature/ai-fixes also sets the Hilt count (34 with AppForegroundModule); merging both makes it 35 (33 + AppLockModule + AppForegroundModule). Update by hand: hilt-bindings-map.md :3 and its stats line (~:527), ARCHITECTURE.md :39 and :896 (and add AppForegroundModule to the module list). Also serialise the collisions listed in the S0 section (including DiagnosticReasonCode with manual-entry-currency, keep both codes) and the shared architecture docs.

### G1a final-acceptance fixes — validation (2026-10-25)
- targeted *AppLockPrivacySection* vr-20261025-194327-a1e6353a PASS; static-guards vr-20261025-194816-18b6fb79 PASS; app-check vr-20261025-200320-e26b1f53 PASS (same fingerprint; this section added after).
- The androidTest-only hygiene tweak (NotificationPresentationDeviceTest awaited result) compiled in app-check; device rerun for confirmation still owed (API 35 + API 26) together with the AE-14 / locked-Snooze emulator checks.
- Owner decisions 2026-10-25 (build/release-lead-notes/owner-approvals-20261022.md item 24): APP-4 wording approved, OD-4a ship confirmed, G1b verification stays binding for G1b (not pulled into 1a), AE-112 simulated path accepted; orchestrator runs AE-14 + locked Snooze/Dismiss on wave5-api35 where automatable.
- AE-14 rendering / locked Snooze: not automatable without a UiAutomator dependency or out-of-runner instrumentation → accepted deviation (owner-approvals item 27); optional owner-phone check; candidate 1b UiAutomator test.
- Device confirmation after the androidTest wait fix: NotificationPresentationDeviceTest API 35 vr-20261025-222558-625cee45 PASS 11/11. (API 26 last ran 11/11 on the pre-tweak test at vr-20261025-192651-e97805c8; the tweak only tightens one wait, production unchanged.)
- G1a STATUS: ACCEPTED (final acceptance 2 parts ACCEPT-WITH-CONDITIONS; conditions fixed or owner-decided 2026-10-25). Increment 1a ready to merge on owner request. Next increment: 1b (G1b post-sweep verification binding).

## S14 (2026-10-26)
Status: implemented, NOT validated (no build/test run by the implementer). Store 1b fields and policy lock terms.

Files (source):
- domain/privacy/applock/AppLockPrivacySettings.kt: new `AutoLockTimeout` enum (IMMEDIATE 0, S30, M1, M5, M15; `token` = enum name, `millis` derived, `DEFAULT` = M1, `FAIL_CLOSED` = IMMEDIATE, `fromToken`); settings gain `appLockEnabled` (false), `autoLockTimeout` (M1), `secureScreen` (false); `FAIL_CLOSED` = all four restrictive (record F).
- domain/privacy/applock/EffectivePrivacyValues.kt: `effectiveDiscreet` now `discreet OR appLockEnabled OR Unreadable/null`; new `effectiveLockEnabled(loadState)`; new `effectiveSecureScreen(loadState, resolving)` (settings part only: `secureScreen OR appLockEnabled OR Unreadable/null OR resolving`, no AUTHENTICATING term).
- data/privacy/applock/AppLockSettingsRepositoryImpl.kt: keys `app_lock_enabled` (boolean), `auto_lock_timeout` (string token), `secure_screen` (boolean). Load precedence unchanged (CORRUPTED, bad marker), then a non-Boolean lock flag gives whole-state `Unreadable(INVALID_VALUE)` (also with no marker, so never a first run); first run needs no marker and none of the four real keys; absent field = default; unknown or non-string timeout = IMMEDIATE; non-Boolean secure screen = true. `reviewSave` writes all four fields explicit plus marker NORMAL in one `edit` (INV-44). `setDiscreetNotifications` unchanged (still rejected while Unreadable, including the new invalid-lock-flag state). Same DataStore `edit` single writer; IO mapping and CancellationException propagation untouched.
- domain/notification/presentation/NotificationContentPolicy.kt: BILL on API <=30 gets `NONE` when the lock is enabled or the state is unreadable/timed out (also in `decideFailClosed`), else `UNCHANGED`; API 31+ stays `AUTH_REQUIRED`. Mode uses the new `effectiveDiscreet` (lock forces DISCREET on all APIs, so inert and API-unsupported are covered because the term reads the stored flag). `decideDiscreet(kind, sdkInt, actionsSuppressed = false)` gained a defaulted parameter.
- domain/notification/presentation/NotificationPresentationResolver.kt: emits `NOTIF_ACTIONS_SUPPRESSED_LOCK(sdkInt)` (fire and forget, via the existing rate-limited emitter) when a BILL decision is `NONE`, in both the normal and the exception-fallback paths. `NotificationContentPolicy` stays pure and total (S4 note).
Files (tests): AppLockSettingsRepositoryImplTest (updated complete-record map; 1b defaults, invalid values, review-save, failure), AppLockSettingsRepositoryImplCorruptionTest (F record, 1b review-save cold start), EffectivePrivacyValuesTest (1b truth tables), NotificationContentPolicyTest (AE-64/65 decision rows; the two 1a pins "unchanged on <=30" were narrowed to lock-off-and-readable, the unreadable rows now expect NONE), NotificationPresentationResolverTest (AE-64 diagnostic rows).

Decisions:
- No schema/DB impact; DataStore keys only. Old 1a files load unchanged (absent keys default).
- Invalid lock flag = `Unreadable(INVALID_VALUE)` (persistent), per spec 2.1/2.2; recovered only by review-save.
- Resolver, not the policy, emits the diagnostic (policy is pure; resolver already owns the emitter).

STOPPED / not done (outside the S14 write set):
- No per-field setters for `appLockEnabled`, `autoLockTimeout`, `secureScreen`, and `AppLockSettingField` has no `APP_LOCK_ENABLED` etc.: `AppLockSettingsRepository.kt` is not in S14's write set (the plan's S18 text expects `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)`). The first slice that writes these per field (S18/S12 owner) must add the interface methods, the enum entries and the Impl bodies following `setDiscreetNotifications` (reject while Unreadable, one `edit`, marker NORMAL, `writeFailure`). Until then 1b fields are written only by `reviewSave`. Orchestrator: confirm or extend a write set.
- Sweep action rule (INV-10 for re-posted BILL): `AndroidNotificationService.rebuildDiscreet` (not in the write set) calls `decideDiscreet(kind, SDK_INT)` with the default, so a re-posted BILL on API 30 keeps its actions even if the lock was just enabled. Carry-forward to G1b / S9 (whichever next edits that file): pass `actionsSuppressed = EffectivePrivacyValues.effectiveLockEnabled(<committed state>)`. Dormant until a lock can be enabled (S18+).
- `BillReminderWorkerNotificationPresentationTest` (S8, not in the write set) runs the unreadable case at SDK 26 to 30 with the real policy; those posts now drop actions. Its AE-7 test asserts no actions either way (read at ~:436), but the validation run of the S8 filter should be included to confirm.
- Binding items from the carry-forward sections: none bind S14 (the S5a items go to S5b to S11; the G1a items bind G1b and S18).

Validation to request (plan S14), serially via validation-runner: targeted-unit-test `*AppLockSettingsRepository*`, `*EffectivePrivacyValues*`, `*NotificationContentPolicy*`, `*NotificationPresentationResolver*`; then shards `data` and `domain-n-z`. Suggested extra: targeted `*BillReminderWorkerNotificationPresentation*`. Gates: privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
- Orchestrator decisions on S14 stops (2026-10-26): (1) 1b per-field setters + AppLockSettingField.APP_LOCK_ENABLED belong to S18 (settings operations) — until then the 1b fields are written only by reviewSave; accepted. (2) BINDING carry-forward for S18/G1b: AndroidNotificationService.rebuildDiscreet must pass actionsSuppressed = EffectivePrivacyValues.effectiveLockEnabled(committed state) so a re-posted BILL on API ≤ 30 drops its actions once the lock is on (dormant until a lock can be enabled).

### S14 review-fix pass (2026-10-26)
Orchestrator decisions:
- Increment 1a ships/merges from 25096c65 (the accepted G1a tip), NOT with S14. release-note-1a stays accurate for that tip.
- S14 is NOT fully dormant: on API <= 30 the unreadable, timeout and policy-exception paths drop BILL Snooze/Dismiss now (INV-10); only the lock term is dormant. Plan S14 release state updated; release-note-1b item added (S23).
- Policy exception => NONE on API <= 30 is an explicit decision (INV-9 fail-closed reading of INV-10).
Review verdicts: privacy-security ACCEPT-WITH-MINORS; architecture ACCEPT-WITH-MINORS; reviewer-strict ACCEPT-WITH-CONDITIONS.
Fixed in this pass:
- Test (strict ISSUE-1): AppLockSettingsRepositoryImplTest lone-key-without-marker test now covers all four real keys (discreet, app_lock_enabled=true, auto_lock_timeout=M15, secure_screen=true): each alone gives Loaded with only that field set, never FirstRunDefault.
- Source KDoc (arch minor 1): toLoadState precedence list now includes the invalid-lock-flag step.
- Plan: S14 release state; S18/G1b carry-forwards (rebuildDiscreet actionsSuppressed, remove the decideDiscreet default and update its pinning test, lock-on SDK <= 30 row in BillNotificationSweepParityTest, NOTIF_ACTIONS_SUPPRESSED_LOCK on sweep decision, INV-45 owner, G1b device re-run on API 26 and 30); S23 write set doc lines and release-note-1b item.
Validation still to run (validation-runner, serial): targeted-unit-test with the same filters plus `*BillNotificationSweepParity*`; then shard `domain-n-z`.
- S14 DONE (2026-10-26): validation on the final tree — targeted vr-20261025-230038-4e5d7051, domain-n-z vr-20261025-230510-13b8936c, data vr-20261025-230641-b8a5d9dc PASS (earlier targeted vr-20261025-223346-eda773c4 / data vr-20261025-224534-834f96d5 on the pre-fix tree). Next: S15 (biometric dependency + USE_BIOMETRIC — owner-approved, owner-approvals item 9).

## S15 (2026-10-26)
Status: implemented (source and resources), validation not run, tests not written (test-author). Dormant: no consumer yet; do not ship in a public build before S22 (the USE_BIOMETRIC permission would appear with no feature behind it).
Owner approval: handoff "Owner decisions 2026-10-23" (USE_BIOMETRIC + androidx.biometric 1.1.0 + USE_FINGERPRINT stripped + explicit androidx.fragment) and owner-approvals-20261022.md items 8-10.

Files (source):
- NEW domain/privacy/applock/DeviceAuthCapability.kt: enum `DeviceAuthCapability` (UNSUPPORTED_API | AVAILABLE | NO_CREDENTIAL | HW_UNAVAILABLE | UNKNOWN) and port `DeviceAuthCapabilityProvider.current()` (re-evaluates every call, no cache).
- NEW domain/privacy/applock/DeviceAuthenticator.kt: port `DeviceAuthenticator.authenticate(attemptId: Long, copy, callback): AuthLaunch` (strict-review fix: was Boolean) and `cancel(attemptId)`; bounded `AuthLaunch` (LAUNCHED | ALREADY_IN_FLIGHT | HOST_UNAVAILABLE | AWAITING_PRIOR_TERMINAL | PROMPT_UNAVAILABLE); closed `AuthPromptCopy` (UNLOCK | ENABLE | DISABLE | CHANGE); bounded `DeviceAuthOutcome` (Succeeded | Cancelled | LockedOut(permanent) | NotAvailable | Failed(bucket)); `DeviceAuthErrorBucket` (HW_UNAVAILABLE | TIMEOUT | SYSTEM_CANCELED | OTHER); `DeviceAuthCallback`.
- NEW data/privacy/applock/AndroidDeviceAuthCapabilityProvider.kt: API < 30 -> UNSUPPORTED_API without calling the library; otherwise `BiometricManager.canAuthenticate(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)` via internal companion `mapCanAuthenticate(code)`; RuntimeException -> UNKNOWN (CancellationException rethrown). Internal const `APP_LOCK_ALLOWED_AUTHENTICATORS`.
- NEW ui/applock/BiometricPromptDeviceAuthenticator.kt: `BiometricPromptDeviceAuthenticator(activity: FragmentActivity)`; internal pure helpers `promptTitleRes`, `promptSubtitleRes` (ENABLE only), `buildPromptInfo(context, copy)` (title + optional subtitle, never description, never negative button, `PROMPT_ALLOWED_AUTHENTICATORS` = STRONG or DEVICE_CREDENTIAL), `mapAuthenticationError(code)`. One prompt in flight; exactly one outcome per attempt, duplicate callbacks dropped; library error text never read.
- M di/AppLockModule.kt: binds `DeviceAuthCapabilityProvider` -> `AndroidDeviceAuthCapabilityProvider` (singleton). The Activity-bound authenticator is NOT bound in Hilt (created by the UI shell per Activity, S20).
Resources: NEW res/values, values-es, values-el `strings_applock_prompt.xml` (5 ids per the plan).
Build/manifest:
- gradle/libs.versions.toml: `biometric = "1.1.0"`, `fragment = "1.8.5"` and catalog entries `androidx-biometric`, `androidx-fragment`.
- app/build.gradle.kts: `implementation(libs.androidx.biometric)`, `implementation(libs.androidx.fragment)` (no other new dependency).
- AndroidManifest.xml: `<uses-permission android:name="android.permission.USE_FINGERPRINT" tools:node="remove" />`. USE_BIOMETRIC arrives via the library manifest merge (reviewer reads the merged manifest after assemble-debug).

Outcome mapping (ux-flows 4.3 + spec buckets): USER_CANCELED/NEGATIVE_BUTTON -> Cancelled; LOCKOUT -> LockedOut(false); LOCKOUT_PERMANENT -> LockedOut(true); NO_DEVICE_CREDENTIAL/NO_BIOMETRICS/HW_NOT_PRESENT -> NotAvailable; HW_UNAVAILABLE/UNABLE_TO_PROCESS/NO_SPACE/VENDOR -> Failed(HW_UNAVAILABLE); TIMEOUT -> Failed(TIMEOUT); CANCELED -> Failed(SYSTEM_CANCELED); SECURITY_UPDATE_REQUIRED and any unknown code -> Failed(OTHER). Capability mapping: SUCCESS -> AVAILABLE; NONE_ENROLLED -> NO_CREDENTIAL; HW_UNAVAILABLE and NO_HARDWARE -> HW_UNAVAILABLE; everything else (UNSUPPORTED, STATUS_UNKNOWN, SECURITY_UPDATE_REQUIRED, unknown) -> UNKNOWN.

Decisions / deviations (for reviewers):
1. ERROR_CANCELED: ux-flows 4.3 lists it under CANCELLED; domain-spec T10 and INV-37 have a SYSTEM_CANCELED error bucket. Followed the spec (Failed(SYSTEM_CANCELED)). Both return to the origin with nothing written; only the settings message and diagnostic code differ. S16/S19 owner may remap if the ENABLE copy "App lock was not turned on." is wanted after a system cancel.
2. androidx.fragment version 1.8.5 chosen by the implementer (approval and TECH-1 say only "explicit, aligned with activity-compose 1.9.x, e.g. 1.8.x"). Reviewer/validation must confirm resolved versions (dependencies report) and that no other androidx.fragment/activity version bump results.
3. `attemptId` is typed `Long` in the port (the plan does not fix a type); S16's `AUTHENTICATING(attemptId, purpose)` should reuse it or adapt at the controller seam.
4. `MainActivity` -> `FragmentActivity` is NOT in the S15 write set: not changed. Carry to S20 (only MainActivity; not RescueActivity/UnsupportedDatabaseActivity). Until then the adapter cannot be instantiated from MainActivity; nothing references it.
5. The authenticator-combination constant exists twice (data `APP_LOCK_ALLOWED_AUTHENTICATORS`, ui `PROMPT_ALLOWED_AUTHENTICATORS`) to keep ui from importing data; tests must pin both to STRONG or DEVICE_CREDENTIAL (and never WEAK).
6. `authenticate` returns a non-LAUNCHED `AuthLaunch` when nothing was launched (no callback follows). S16 must map each value to a bounded result without a state change (superseded the original Boolean; see "S15 strict-review fixes").
7. No machine-readable permission allowlist for FG-22 was found under config/guards or scripts by name search; the guard run decides. No guard, baseline or allowlist was edited.
8. Host loss (PROMPT_HOST_LOST, INV-54) is not in S15; S16/S20 own it. `cancel(attemptId)` is provided for them.

Tests the test-author must add (T = app/src/test/java/com/yourname/expensetracker):
- data/privacy/applock/AndroidDeviceAuthCapabilityProviderTest.kt (Robolectric SDK 26, 28, 29 -> UNSUPPORTED_API without a library call; 30, 31, 34 -> mapping; `mapCanAuthenticate` table incl. unknown code -> UNKNOWN; re-evaluates on each call; constant == STRONG or DEVICE_CREDENTIAL and has no BIOMETRIC_WEAK bit).
- ui/applock/BiometricPromptOutcomeMappingTest.kt (every error code per the table above plus an unknown code and SECURITY_UPDATE_REQUIRED; none maps to Succeeded; AE-45 lockout, AE-50 NotAvailable; duplicate-callback and one-prompt-in-flight behaviour via a fake if feasible).
- ui/applock/BiometricPromptCopyTest.kt (title per copy; subtitle only for ENABLE; description never set; no negative button; allowed authenticators STRONG or DEVICE_CREDENTIAL; AE-114 DISABLE -> applock_prompt_title_disable; all five ids exist in values, values-es, values-el and none contains a placeholder).

Validation to request (validation-runner, serial): `compile`; `assemble-debug` (reviewer reads the merged manifest read-only: USE_BIOMETRIC present, USE_FINGERPRINT absent, no other new permission); `targeted-unit-test` `*AndroidDeviceAuthCapability*`, `*BiometricPromptOutcomeMapping*`, `*BiometricPromptCopy*`, `*BiometricPromptHostGating*`, `*AppLockBiometricSourceScan*`, `*BiometricPromptAdapterStateMachine*`; shards `data`, `runtime-ui`; `lint`; because a dependency changes also `app-check` and `static-guards` (FG-22 permission policy, FG-20, FG-18); dependency resolution check for androidx.fragment/activity/lifecycle.
Gates: privacy-security-guardian (lead), architecture-guardian, reviewer-strict.
Owner wording review (best-effort translations): es: "Activar el bloqueo de la app" / "Confirma con tu huella o con el bloqueo de pantalla." / "Desactivar el bloqueo de la app" / "Cambiar los ajustes del bloqueo de la app" / "Desbloquear". el: "Ενεργοποίηση κλειδώματος εφαρμογής" / "Επιβεβαιώστε με το δακτυλικό αποτύπωμα ή το κλείδωμα οθόνης." / "Απενεργοποίηση κλειδώματος εφαρμογής" / "Αλλαγή ρυθμίσεων κλειδώματος εφαρμογής" / "Ξεκλείδωμα".
Rollback: revert the dependency, the fragment pin and the manifest line together.

### S15 privacy-review fixes (2026-10-26; validation not run)
- F1: `authenticate` returns false as its first statement below API 30 (the library is never called). New internal pure gate `canLaunchPrompt(sdkInt, stateSaved, finishing, destroyed)`.
- F2: the `BiometricPrompt` is built once in the adapter constructor (androidx guidance, so the callback reattaches after a configuration change) and forwards to the current in-flight sink. KDoc states the binding rule: S20 creates the adapter in `MainActivity.onCreate`; attempt identity and in-flight state across recreation belong to the process-level controller (S16, INV-24), not to the adapter.
- F3: `authenticate` returns false when `supportFragmentManager.isStateSaved`, `isFinishing` or `isDestroyed`. `cancel(attemptId)` on the in-flight attempt clears the flight, calls the library cancel, then delivers exactly one `Failed(SYSTEM_CANCELED)` itself; the library echo is dropped. (Order deviation from "deliver then cancel": delivery is last so a new attempt started from the callback is not cancelled by our own library cancel.)
- F4: callbacks complete a flight only when `inFlight === flight` (reference), not by id. With a constructor-built prompt the flight is read at callback entry. S16 note: attempt ids must be strictly increasing.
- F5: tests pin the exact value 0x800F everywhere; `(x and BIOMETRIC_WEAK)` is asserted equal to `BIOMETRIC_STRONG` (STRONG is a subset of WEAK), never to 0. New `ui/applock/AppLockBiometricSourceScanTest` (no BIOMETRIC_WEAK in code of the two S15 files; `setAllowedAuthenticators(` / `canAuthenticate(` use the named constant; no `setNegativeButtonText` / `setDescription` / `setDeviceCredentialAllowed`). New `ui/applock/BiometricPromptHostGatingTest` (Robolectric 26/28/29 -> false and no callback; finishing host; `canLaunchPrompt` truth table).
- Decisions 1 (SYSTEM_CANCELED), 5 (two constants, both pinned) and 8 (host loss owned by S16/S20) ACCEPTED.
- Carry-forward S22/S23: add USE_BIOMETRIC to the release-evidence expected permission list and to CODEBASE_INVENTORY permissions (~:600-610); reconcile ux-flows 4.3 so the CANCELLED row no longer lists ERROR_CANCELED (it is SYSTEM_CANCELED).
- Carry-forward S16/S20 (BINDING): the controller owns attempt identity and must handle PROMPT_HOST_LOST before the cancel echo (T12/T35); S20 creates the adapter in `MainActivity.onCreate`; G1b device test: rotation during the prompt.
- Residual (SUPERSEDED by the strict-review fix C1 below): a late library echo after `cancel` could be attributed to a newer attempt. It can no longer be: the adapter now drops the next library terminal after a client cancel and refuses launches meanwhile. What stays open for G1b is whether the library echoes a client cancel at all (the wait then ends by timeout or ON_START).
- F4 text correction: callbacks complete a flight only when `inFlight === flight`; this protects against duplicates and stale flights, but NOT against a late echo of an older prompt while a newer flight is open. That case is handled by the wait-for-echo flag (C1).

### S15 strict-review fixes (2026-10-26; validation not run)
- C1 / ISSUE-1: after a client `cancel` of a launched prompt the adapter sets `awaitingLibraryTerminal` (before the library cancel, so a synchronous echo clears it). The next library terminal callback (success or error) is dropped and clears the flag; while set, `authenticate` returns `AWAITING_PRIOR_TERMINAL` (no launch). The flag also clears after `AWAITING_TERMINAL_TIMEOUT_MS` = 1500 ms (main-thread Handler, removed on clear, so a stale timeout cannot cut a later wait short), on the host's next ON_START and on ON_DESTROY. KDoc rewritten accordingly; the "exactly one outcome per launched attempt" claim is replaced by "at most one outcome per attempt on this adapter instance" (not across Activity recreation).
- Bounded launch result: port `authenticate` returns `AuthLaunch` (LAUNCHED | ALREADY_IN_FLIGHT | HOST_UNAVAILABLE | AWAITING_PRIOR_TERMINAL | PROMPT_UNAVAILABLE); no codes or text. Nothing consumed the port, so only the adapter and tests changed.
- C2 / ISSUE-2: internal constructor `(activity, promptFactory: (AuthenticationCallback) -> PromptHandle?)`; the public constructor passes the real `BiometricPrompt`-backed handle. New `PromptHandle` (internal; authenticate, cancelAuthentication). Test hooks `isPromptBuilt`, `isAwaitingLibraryTerminal` (VisibleForTesting). New `BiometricPromptAdapterStateMachineTest` (Robolectric sdk 34). `BiometricPromptHostGatingTest` now asserts `AuthLaunch.HOST_UNAVAILABLE`, and the finishing-host test first asserts the prompt is built.
- ISSUE-3: domain port KDoc corrected (per-instance guarantee; cancel delivers `Failed(SYSTEM_CANCELED)` synchronously inside the call). Plan S16/S20/G1b carry-forward block extended (S20 creates the adapter after `super.onCreate()`; G1b covers a silent library skip when a prompt fragment is already showing).
- ISSUE-4: `BiometricServiceContext` in `AndroidDeviceAuthCapabilityProviderTest` overrides `getApplicationContext()` to return itself.
- ISSUE-5: plan S15 write set and validation extended (androidx.fragment 1.8.5, the three new tests, their targeted filters); the validation list above now includes them.
- ISSUE-6: `AppLockBiometricSourceScanTest` scans all of `app/src/main/java`: BIOMETRIC_WEAK in code, `setNegativeButtonText(`, `setDeviceCredentialAllowed(`, `setDescription(` in files importing a biometric API, and no-arg `canAuthenticate()` are forbidden everywhere; `setAllowedAuthenticators(`, `canAuthenticate(` and `BiometricPrompt(` are allowed only in the two allowlisted files (constant-argument checks kept there). Planted self-checks for violations and for comment/string-only mentions.
- Residual: whether the real library echoes a client cancel, and how it behaves for a launch while a prompt fragment is already showing, is unobserved until G1b. If a library terminal arrives in the wait window that belongs to something other than the cancel echo, it is dropped and the (still open) attempt, if any, would not be affected because the wait only starts when no flight is open.
- S15 fix verification (reviewer-strict, 2026-10-26): ACCEPT-WITH-CONDITIONS — ISSUE-1 comment-only (outcome not guaranteed; controller closes via cancel/timeout) fixed by the orchestrator. BINDING S16/S20/G1b additions: S16 records the attempt as open before calling authenticate and tolerates a re-entrant outcome; S16 watchdog/timeout closes unanswered attempts (never relies on an outcome); exactly ONE adapter per Activity instance (S20); after recreation relaunch only for the same operation/prompt text or close the old attempt via PROMPT_HOST_LOST first; S20 note on ON_START observer order (one transient AWAITING_PRIOR_TERMINAL); G1b measures cancel-echo latency (raise AWAITING_TERMINAL_TIMEOUT_MS if > ~500 ms on any device); optional test: ON_DESTROY clears the flag.
- S15 validation so far (same tree before this comment edit): targeted debug vr-20261025-234233-459b65b2, release vr-20261025-234650-8806630c, runtime-ui vr-20261025-235440-5062ab1b, data vr-20261025-235627-2b312861 — PASS.
- S15 static-guards vr-20261026-000115-c0c35512 FAILED only known_good_state (DB_SIGNATURE_UNRESOLVED 22 vs 21): the db_guard callable parser does not resolve bare `CharSequence` (not in its builtins) → the whole BiometricPromptDeviceAuthenticator owner was unresolved (confirmed in build/guard-debug/known-good-state/active-db-gate.findings.json). Source fix: `errString: kotlin.CharSequence` (same type). No guard/baseline change. app-check vr-20261026-001604-d2d320cd PASS on the pre-fix tree.

### S15 final validation (2026-10-26, serial, final tree)
- targeted (*BiometricPromptAdapterStateMachine*, *BiometricPromptHostGating*, *AppLockBiometricSourceScan*, *BiometricPromptOutcomeMapping*, *BiometricPromptCopy*) vr-20261026-011520-aff324ab PASS
- static-guards vr-20261026-011739-b5345a14 PASS (known_good_state back to 21 DB_SIGNATURE_UNRESOLVED after `kotlin.CharSequence`)
- app-check vr-20261026-075043-57ff0d28 PASS (final tree)
- Merged manifests (debug + release, read-only check): USE_BIOMETRIC present, USE_FINGERPRINT absent (tools:node="remove" effective). Matches owner-approvals item 9.
- Status: S15 validated + accepted. Committed. es/el prompt strings remain on the owner native-review list.
- Next: S16 (controller-owned attempt identity; binding carry-forwards above).

## S16 part (i) — implemented (2026-10-26; implementer, validation NOT run)
Status: implemented, NOT validated (no build/test/guard run). Dormant: nothing in production calls the controller; the
controller must not be wired to a consumer (S20) before part (ii) lands (see "Part (ii) must-haves"). No schema/DB impact.
Legal path: new APP LOCK path (domain port-only controller). No Room, no barrier, no new Hilt module (one `@Binds` added).

Files (source, `M/` = app/src/main/java/com/yourname/expensetracker):
- NEW M/domain/privacy/applock/AppLockState.kt: `LockCause`, `InertReason`, `AppLockOrigin`, `AuthPurpose(origin, isOpenOrigin)`
  (UNLOCK->LOCKED, ENABLE->DISABLED, CONFIRM_CHANGE->UNLOCKED), sealed `AppLockState` (`Resolving`, `Disabled`, `Locked(cause)`,
  `Authenticating(attemptId: Long, purpose)`, `Unlocked`, `Inert(reason)`). `attemptId` is the S15 port's `Long`.
- NEW M/domain/privacy/applock/LockNotice.kt: type only (`Inert(reason)`, `ReviewSettings`, `BiometricInvalidated`).
- NEW M/domain/privacy/applock/MonotonicClock.kt: port `elapsedRealtimeMillis()`. NEW M/data/privacy/applock/SystemMonotonicClock.kt:
  `SystemClock.elapsedRealtime()` (the existing `MonotonicTimeProvider` is `System.nanoTime`-backed and does not count deep sleep,
  so it was not reused). FG-17 `time_boundaries` does not list `SystemClock`; no exception entry needed.
- NEW M/domain/privacy/applock/AppLockController.kt: `@Singleton`, ports only (`AppLockSettingsRepository`, `DeviceAuthCapabilityProvider`,
  `MonotonicClock`, `PrivacyDiagnosticsEmitter`, `@ApplicationScope CoroutineScope`). Also holds `UnlockTapResult`.
- M M/di/AppLockModule.kt: `@Binds @Singleton MonotonicClock -> SystemMonotonicClock` (module count unchanged; the controller needs no binding).
- M M/domain/diagnostics/DiagnosticReasonCode.kt: 18 spec-10 "Lock" codes appended at the tail (existing order kept):
  LOCK_SHOWN_COLD_START, LOCK_SHOWN_TIMEOUT, LOCK_SHOWN_SETTINGS_UNREADABLE, LOCK_UNLOCKED, LOCK_AUTH_CANCELLED, LOCK_AUTH_LOCKOUT,
  LOCK_AUTH_ERROR, LOCK_UNAVAILABLE_API, LOCK_UNAVAILABLE_NOT_SECURE, LOCK_UNAVAILABLE_CAPABILITY, LOCK_INERT_NO_CREDENTIAL,
  LOCK_INERT_HW_UNAVAILABLE, LOCK_INERT_UNKNOWN, LOCK_INERT_API_UNSUPPORTED, LOCK_INERT_SETTINGS_UNREADABLE, LOCK_ENABLED,
  LOCK_DISABLED, LOCK_SETTINGS_RECOVERED. (PIN codes are Inc 2 and not added; LOCK_SETTING_PERSIST_FAILED/UNREADABLE/REVIEW_SAVED existed.)
- M M/domain/privacy/applock/PrivacyDiagnosticsEmitter.kt (OUTSIDE the plan's S16 write set, needed to emit): 9 new `PrivacyDiagnosticCode`
  entries with no metadata keys: LOCK_SHOWN_COLD_START/TIMEOUT/SETTINGS_UNREADABLE (BLOCKED), LOCK_UNLOCKED (COMPLETED),
  LOCK_INERT_* x5 (SKIPPED, WARNING). The other lock codes get their entries with part (ii)/S18.
Files (tests, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock):
- NEW T/AppLockControllerResolutionTest.kt, T/AppLockControllerInertTest.kt, T/AppLockControllerIllegalTransitionTest.kt.
- NEW T/AppLockControllerTestFixtures.kt (outside the three listed tests; shared fakes: monotonic clock, capability, read-only settings store
  that counts write attempts, harness over a mockk emitter and an unconfined test scope). Part (ii) tests reuse it.

Transitions implemented (public API = one method per spec 3.1 event):
- T1 (initial `Resolving`); T2, T3, T4, T5, T6 via `onSettingsResolved(loadState)` and `onResolveTimeout()`; `suspend resolve()` owns T_resolve
  (2 s, `RESOLVE_TIMEOUT_MILLIS`): bounded read, then RESOLVE_TIMEOUT, then the late read as LATE_SETTINGS_RESOLVED; idempotent; non-IO read exception
  maps to `Unreadable(IO_ERROR)`; CancellationException rethrown and a later `resolve()` may retry.
- T6b via `onLateSettingsResolved`: only if the fallback was the controller's own RESOLVE_TIMEOUT, state is exactly `Locked(SETTINGS_UNREADABLE)`
  (so NOT while `Authenticating`, AMD-22), and the late read is readable with the lock off (Loaded or FirstRunDefault). Else dropped, not retained.
- T7/T7b via `onUnlockTapped()` (DEVICE_AUTH only): capability re-evaluated at the tap; not AVAILABLE -> `Inert(reason)` + code, no attempt (T7b);
  else strictly increasing attempt id + `Authenticating(id, UNLOCK)` with the origin cause kept (T7). `AlreadyInFlight` / `NotLocked` otherwise.
- T8 via `onAuthSucceeded(attemptId)`: only a success whose id equals the open attempt and whose purpose is UNLOCK unlocks; clears the pending
  background; `LOCK_UNLOCKED`. Everything else is ignored (T12 for the success event).
- T13 `onAppBackgrounded()`: UNLOCKED and INERT record the first background (kept until consumed). T14/T15/T16 via `onAppForegrounded()` and
  `onLockQuery()` (T16 foreground only). Pending-background consumption (AMD-22): consumed when T14/T15/T16 evaluate, see decisions D3/D4.
- Diagnostics (fire and forget on the app scope, INV-37): shown cold start/timeout/unreadable, unlocked, inert x5. Failure of the sink never
  affects a transition; CE rethrown.

AEs covered: AE-38 (controller part), AE-40, AE-41, AE-43, AE-46 (double tap), AE-47 (stale success after re-lock; the abandoned-attempt half is
part ii), AE-48, AE-49, AE-51 (controller part; the review notice is S18), AE-52, AE-53, AE-54, AE-58 (controller part), AE-61, AE-63, AE-90.
Not covered in part (i): AE-42, AE-44, AE-45, AE-50, AE-116, AE-117, AE-118 (prompt outcomes, T9 to T12, T32 to T35), AE-68 and the isolation test
(INV-36; subject to TECH-10, so no source scan; part (i) only pins the constructor to the five ports and the public method signatures by reflection).

Seams left for part (ii) (all marked `TODO(S16-ii)` in AppLockController.kt; S17/S18 seams marked `TODO(S17)` / `TODO(S18)`):
1. After `onUnlockTapped` moves to `Authenticating`, the controller does NOT launch the prompt: part (ii) adds the authenticator binding, records
   the attempt as open before `authenticate`, maps each non-LAUNCHED `AuthLaunch`, retries AWAITING_PRIOR_TERMINAL, owns the watchdog and tolerates
   re-entrant outcomes (S15 binding carry-forwards).
2. Outcome events other than success: `onAuthCancelled`, `onAuthError` (T9, T10, T11), `PROMPT_HOST_LOST` (T35, before the adapter echo), stale
   handling (T12) for them, the `LOCK_AUTH_*` diagnostics and their `PrivacyDiagnosticCode` entries. `InFlightAttempt.originLockCause` is kept for the T9 return.
3. ENABLE / CONFIRM_CHANGE start API (S18 binds it), `promptBackgroundAt`, T32/T33/T34, the `AuthPromptCopy` per attempt. `onAppBackgrounded` has the
   T32 hook comment in its non-open branch; `onAuthSucceeded` rejects non-UNLOCK purposes until T33 exists.
4. Held link (S17): T2/T4/T6/T6b/T8 "apply held link", T14 "in-flight link held". SETTINGS_REREAD/T28/T29 and LockNotice derivation (S18).
Part (ii) must-haves before any consumer is wired: today an UNLOCK attempt can end only by success (no cancel/error/host-loss exit). That is why
the controller must stay unwired until part (ii); S20 must not start before it.

Decisions for review (spec ambiguous; fail-closed reading chosen):
- D1. T7/T8 (UNLOCK start and success) are in part (i) as requested ("T1-T8"), but the prompt launch and every non-success outcome are part (ii). The plan's seam text says (i) = "resolution, inert and T6b"; the AEs assigned (AE-40/41/43/90/63) need an unlock path to reach UNLOCKED.
- D2. Only the controller's own RESOLVE_TIMEOUT qualifies for T6b. A repository-reported `Unreadable(TIMEOUT)` (a cause normally set by bounded readers) is treated like CORRUPTED/IO_ERROR/INVALID_VALUE: no T6b. A late read arriving while `Authenticating(UNLOCK)` is dropped, not retained for after the prompt (recovery is the S18 re-read, T28/T29). A late read for an INERT(SETTINGS_UNREADABLE_NO_AUTH) fallback is also dropped (T6b names LOCKED only).
- D3. T14/T15 evaluate only when a background is pending. A foreground or query with nothing pending (rotation, shade, query while in use) changes nothing and consumes nothing, so INERT keeps its banner for the session (AE-49) and IMMEDIATE never locks without a background (AE-41).
- D4. Consumption rule: `APP_FOREGROUNDED` always consumes in UNLOCKED/INERT. `LOCK_QUERY` consumes only when the lock is active (T14/T15 fired); a query while capability is not AVAILABLE leaves the pending background so a transient failure of the capability check cannot erase time away. The first background wins until consumed (a repeated stop never shortens elapsed).
- D5. Negative elapsed (clock going backwards) counts as timed out (lock).
- D6. T14 from INERT: INERT + lock active + elapsed >= timeout -> LOCKED(TIMEOUT) (AE-49). INERT + lock active + elapsed < timeout (a real return) -> UNLOCKED (T15 clears the banner). INERT keeps its original reason across foregrounds while the capability stays unavailable (T16 is UNLOCKED only).
- D7. Unreadable states and a RESOLVE_TIMEOUT fallback stay under record F after an unlock (IMMEDIATE timer) until a later slice (S18) re-reads; the controller never writes settings in part (i) (verified by a write-counting fake).
- D8. Lock diagnostics are emitted per transition (rate-limited by the emitter, 1 per code per 60 s), not deduplicated "once per process per cause": per-process dedup would hide later re-locks. Confirm.
- D9. Verifier-present ([2]) is absent, so T5/T6/T7b decide on capability alone (marked in code).
- D10. `PrivacyDiagnosticsEmitter.kt` was edited although outside the plan's S16 write set (9 code entries), plus the shared test fixtures file. Confirm or relocate.

Open questions: (1) D1 split reading; (2) D8; (3) whether `onLockQuery()` should return the state (as now) or a bounded S17 result type; (4) the PIN codes in spec 10 stay unadded (Inc 2).

Validation to request (validation-runner, serial, per plan S16 part (i)): `targeted-unit-test` `*AppLockController*`; shard `domain-n-z`; `registered-guard` `time_boundaries` and `cancellation`.
Suggested extra: targeted `*PrivacyDiagnosticsEmitter*` (new enum entries), and `compile` first (Kotlin compile of the new controller was not run; mockk `answers` blocks and `synchronized` non-local returns are the likeliest compile findings).
Gates: privacy-security-guardian (lead), architecture-guardian, reviewer-strict.

## S16 part (i) — review fixes (privacy-security-guardian ACCEPT-WITH-CONDITIONS, architecture-guardian ACCEPT-WITH-CONDITIONS)
Applied 2026-10-26 by the implementer under the orchestrator's final decisions (owner standing instruction). Validation NOT run. No schema impact.
C = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock/AppLockController.kt; T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.
1. Spec 10 once per process per cause (privacy F1 / architecture F3). D8 RESOLVED: follow the spec (supersedes the D8 reading above).
   - C:109 `claimedDiagnosticCodes` (`EnumSet.noneOf(PrivacyDiagnosticCode::class.java)`, guarded by `stateLock`); C:395-426 `enterLocked` / `enterInert` return
     `claim(code)` (null on a repeat) under the monitor; C:282-300 `onAuthSucceeded` claims `LOCK_UNLOCKED` under the monitor. `record()` (C:~450) launches outside
     the monitor as before and ignores null. The emitter's rate limit is untouched (backstop). Class KDoc updated (C:71).
   - Tests added in T/AppLockControllerResolutionTest.kt: `spec 10 a second re-lock or unlock with the same code emits nothing new` (:284), `spec 10 a different code
     still emits once after another was already claimed` (:309), `spec 10 the once-per-process set belongs to one controller, a new process emits again` (:336).
   - Existing tests re-checked by reading: none asserts a repeated emission (the `.last()` and `count == 1` assertions each see a first-time code), so none changed.
2. Visibility (privacy F3 / architecture F1): C:159 `onSettingsResolved`, C:168 `onResolveTimeout`, C:180 `onLateSettingsResolved`, C:282 `onAuthSucceeded` are now
   `@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE) internal` (import `androidx.annotation.VisibleForTesting`, precedent domain/workers/FileWorkerTerminalDiagnosticSink.kt).
   `resolve()` is the only production entry. The existing reflection test (T/AppLockControllerIllegalTransitionTest.kt:~335) filters by parameter types, not names, and
   is unchanged. Added T/AppLockControllerIllegalTransitionTest.kt:349 `the resolution events and the auth success are test seams...`, which pins the exact unmangled
   public method set {getState, resolve, onAppBackgrounded, onAppForegrounded, onLockQuery, onUnlockTapped} (internal members are name-mangled with `$`).
3. Architecture F2 (comment only): the `TODO(S16-ii)` about the prompt launch moved out of the `synchronized(stateLock)` block to just after it (C:263-267), with
   "never call authenticate under stateLock; the outcome callback re-enters the public transitions (cancel delivers Failed(SYSTEM_CANCELED) synchronously)".
4. Architecture F6: C:442-447 `readLoadState` broad catch now logs `Timber.w("App-lock settings read failed: class=%s", DiagnosticThrowableSummary.className(e))` after the
   CancellationException rethrow (matches AppLockSettingsRepositoryImpl.kt:94). Class name only. No test (Timber output is not asserted in this suite).
5. Privacy F7 (tests): T/AppLockControllerTestFixtures.kt:102,110 `emittedMetadata` captures the emitter's 2nd argument; T/AppLockControllerResolutionTest.kt:73-74 (AE-38 T3 test)
   asserts `assertSame(PrivacyDiagnosticMetadata.EMPTY, ...)`. T/PrivacyDiagnosticsEmitterTest.kt:172 `the nine S16 lock codes carry no metadata keys at all` pins empty `allowedKeys`
   for the 9 new codes.
6. Privacy F4 (comment only): C:134 KDoc on `resolve()`: S20 must call it from the application scope, or again on each ON_START while the state is RESOLVING.

Validation to request: `compile`, then `targeted-unit-test` `*AppLockController*` and `*PrivacyDiagnosticsEmitter*`; shard `domain-n-z`. Likeliest compile findings: `secondArg<T>()` in the
mockk `answers` block, and `claim(...)` inside the `synchronized` lambdas that assign the captured `var code`.

### S16 part (i) validation (2026-10-26)
- Guards on the review-fixed tree all PASS:
  - registered-guard time_boundaries vr-20261026-091053-fca536a1
  - cancellation vr-20261026-091111-b4e7529b
  - known_good_state vr-20261026-091129-87b85a57
- targeted vr-20261026-090519-4863d0f0 and shard domain-n-z vr-20261026-091430-2d32f77d FAILED on one test only: AppLockControllerIllegalTransitionTest "no public method takes ...".
  - Cause: Kotlin synthetic accessors `access$readLoadState` / `access$getDiagnostics$p` (static, first param = the controller), generated for lambdas that touch private members.
  - Fix (test-only, debug iteration 1): filter `!it.isSynthetic`, the same filter the exact-method-set pin uses. Every real public method is still checked, so this is not a loosening.
- Re-run: targeted + shard domain-n-z.

### S16 part (i) — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS)
Fixed (implementer, validation NOT run). All paths are under `app/src/main|test/java/com/yourname/expensetracker/domain/privacy/applock/`.
1. Finding 4, an `Error` from the read left RESOLVING for the whole process.
   - Fix: `AppLockController.kt:142-149` now catches `Throwable` (not only `CancellationException`), then `read.cancel()`, `resolveStarted.set(false)` and rethrows. KDoc at `:131-134` updated.
   - Test: `AppLockControllerResolutionTest.kt:218` "an Error from the read propagates and a later resolve can still complete" (ExceptionInInitializerError, then a healthy read resolves to LOCKED COLD_START).
2. Finding 3, the INV-37 isolation test could not fail.
   - Harness scope now has a collecting `CoroutineExceptionHandler`: `AppLockControllerTestFixtures.kt:115` (`uncaught`) and the scope at `:126-128`.
   - Emitter mock moved to `coAnswers` with an `emitSuspendsForever` switch (`:112-124`). `@OptIn(ExperimentalCoroutinesApi::class)` on the harness (`:94`).
   - `AppLockControllerResolutionTest.kt:245` now asserts the code in `h.emitted` and an empty `h.uncaught`.
   - New `:261` "a diagnostic emit that never returns does not block this transition or the next one" (`awaitCancellation()`; resolve, unlock and a re-lock all return with the new state).
3. Finding 5, the blanket `!it.isSynthetic` filter.
   - `AppLockControllerIllegalTransitionTest.kt:342-352` excludes only methods named `access$...`, and asserts no other synthetic method is declared (`declaredMethods`).
4. Nits:
   - `AppLockState.kt:66-69`: the `Unlocked` KDoc now covers T15 (INERT to UNLOCKED within the timeout).
   - `PrivacyDiagnosticsEmitterTest.kt:185-203`: the vacuous `assertEquals(9, size)` is replaced by an explicit list of the 9 names compared with the emitter's real lock entries (LOCK_SHOWN_*, LOCK_INERT_*, LOCK_UNLOCKED).
   - `AppLockControllerIllegalTransitionTest.kt:107-123` (AE-46): now asserts exactly one capability re-check across the three taps (`capabilityProvider.evaluations` delta == 1).

Not changed in this pass: visibility and StateFlow publishing. These are binding for part (ii); the orchestrator records them.
Validation to request: `compile`, `targeted-unit-test` `*AppLockController*` and `*PrivacyDiagnosticsEmitter*`, shard `domain-n-z`.

### S16 part (i) — accepted (2026-10-26)
- Final-tree validation (serial, all PASS):
  - targeted `*AppLockController*,*PrivacyDiagnosticsEmitter*,*AppLockModule*` vr-20261026-100353-da6730f3
  - registered-guard cancellation vr-20261026-100827-a1c36e4b
  - known_good_state vr-20261026-100845-52861264
  - shard domain-n-z vr-20261026-101147-4b92e684
  - time_boundaries vr-20261026-091053-fca536a1 (the production change since then is only the resolve() Throwable re-arm; that guard scans wall-clock APIs only)
- Gates:
  - privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS, fixed
  - architecture-guardian ACCEPT-WITH-CONDITIONS, fixed
  - reviewer-strict ACCEPT-WITH-CONDITIONS: findings 3/4/5 + nits fixed; findings 1/2 BINDING for part (ii)
- BINDING for part (ii)/S19/S20: see the plan amendments "S16 part (i) review outcome" (P1–P7) and "S16 part (i) final gate" (R1–R4, incl. R3: an exception-derived UNKNOWN capability keeps the lock, owner-approvals item 31).
- Controller stays DORMANT until part (ii) lands.
- Next: S16 part (ii).

## S16 part (ii-a) — implemented
Status (2026-10-26, implementer): implemented, NOT validated (no build, test or guard run). Scope: the UNLOCK attempt machinery, so that every AUTHENTICATING(UNLOCK) exits. Dormant: nothing in production calls the controller. No schema/DB impact. Legal path: the APP LOCK path (domain port-only controller; no Room, no barrier). Part (ii-b) is untouched and marked `TODO(S16-ii-b)`: ENABLE/CONFIRM_CHANGE start API, T32/T33/T34, `promptBackgroundAt`, the P6 foreground flag, AuthPromptCopy beyond UNLOCK, and moving the timer/T7/T8 tests into Timer/AuthOutcome files.

Files (M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock):
- M/domain/privacy/applock/AppLockController.kt (rewritten around the seams): `DeviceAuthenticator` and `@AppLockMainDispatcher CoroutineDispatcher` added to the constructor (now seven ports); `current` + `version` under `stateLock`, publication after the lock; per-attempt `InFlightAttempt` (also the `DeviceAuthCallback`), orchestrator + watchdog jobs, one `closeAttempt(attempt, Closing)` for every exit; public `onPromptHostLost(attemptId)`; `onAppForegrounded()` also nudges a launch that waits for AWAITING_PRIOR_TERMINAL; `UnlockTapResult.CapabilityCheckFailed` added.
- M/domain/privacy/applock/DeviceAuthCapability.kt: new enum value `CHECK_FAILED` (R3).
- M/data/privacy/applock/AndroidDeviceAuthCapabilityProvider.kt: the RuntimeException catch now returns `CHECK_FAILED` (was UNKNOWN); KDoc updated. `mapCanAuthenticate` still returns UNKNOWN for unrecognised codes.
- M/domain/privacy/applock/PrivacyDiagnosticsEmitter.kt (outside the plan write set, as in part (i); TECH-8 front door): 3 entries `LOCK_AUTH_CANCELLED`, `LOCK_AUTH_LOCKOUT` (no keys) and `LOCK_AUTH_ERROR` (allowed key `CAUSE`, value = the bounded `DeviceAuthErrorBucket` enum name). The part (i) pin test filters by prefix (LOCK_SHOWN_/LOCK_INERT_/LOCK_UNLOCKED), so it still passes unchanged.
- M/domain/privacy/applock/AppLockState.kt: one KDoc sentence.
- M/di/AppLockModule.kt: `@AppLockMainDispatcher` qualifier (file-level, BINARY retention) and a `companion object { @Provides @AppLockMainDispatcher ... = Dispatchers.Main }` in the existing module. No new module, DispatchersModule untouched.
- Tests: NEW T/AppLockControllerAuthOutcomeTest.kt, T/AppLockControllerPromptOutcomeTest.kt. Updated T/AppLockControllerTestFixtures.kt (FakeDeviceAuthenticator, MarkingMainDispatcher, `settle/advance/tap/activeJobs`, `lockedControllerFor`, `TestScope.resolveTimedOut`; `resolvedController()` now runs the real `resolve()`, `unlock()` goes through the callback), T/AppLockControllerResolutionTest.kt, T/AppLockControllerInertTest.kt, T/AppLockControllerIllegalTransitionTest.kt (every call of the now-private events replaced by `resolve()` over a fake repository + test scheduler, or by the callback; behaviour asserted is unchanged), and test/data/privacy/applock/AndroidDeviceAuthCapabilityProviderTest.kt (exception cases now CHECK_FAILED, plus two new tests).

How each binding item is met:
- P1 / R1: T7 sets `inFlight` and publishes `Authenticating(id, UNLOCK)`; only then `startAttemptJobs` launches (on the injected scope) an orchestrator that calls `authenticate` via `withContext(mainDispatcher)`, never under `stateLock`. The attempt object is the callback; `routeOutcome` requires `attemptId == attempt.attemptId` AND `inFlight === attempt`, so a closed or older attempt's callback is stale (T12). `onSettingsResolved`, `onResolveTimeout`, `onLateSettingsResolved` are `private`; `onAuthSucceeded` no longer exists (success arrives only through the callback). Nothing stays `internal` except the two inspection getters `pendingBackgroundAtMillis` / `inFlightAttemptId`; a source scan (in the IllegalTransition test) fails if any main file other than the controller mentions them or any former seam. The method-set pin test is renamed `the public method set is exactly the production API, and the resolution and auth events are private` and asserts the exact set {getState, resolve, onAppBackgrounded, onAppForegrounded, onLockQuery, onUnlockTapped, onPromptHostLost} plus that the three former seams are private. Re-entrant outcomes inside `authenticate` or `cancel` are safe: the attempt is open before the call, and `closeAttempt` takes the lock only briefly.
- P2: ALREADY_IN_FLIGHT / HOST_UNAVAILABLE / PROMPT_UNAVAILABLE (and a throwing `authenticate`) call `closeAttempt(NotLaunched)`: id-checked, back to the origin LOCKED cause, no LOCK_AUTH_* code, no cancel. AWAITING_PRIOR_TERMINAL keeps the attempt open and retries every `AWAITING_PRIOR_TERMINAL_STEP_MILLIS` (250 ms) for `AWAITING_PRIOR_TERMINAL_BOUND_MILLIS` (1500 ms; 7 calls in total), or at once on `onAppForegrounded()` (the adapter's ON_START); then the orchestrator ends and the watchdog owns the attempt.
- P3: the watchdog is a lazy coroutine on the app scope, started with the orchestrator; it is recorded on the attempt under the lock (an attempt that already closed cancels it instead). After `PROMPT_WATCHDOG_MILLIS` it calls `closeAttempt(attempt, Abandoned)`: identity-checked, T35/T9 semantics, `LOCK_AUTH_CANCELLED`, then a main-thread `cancel`. It never waits on an outcome and `closeAttempt` cancels it (and the orchestrator) on every exit. The spec names no timeout, so `PROMPT_WATCHDOG_MILLIS` = 5 minutes is a named constant with a KDoc rationale (long enough for a person entering a credential, since the system gives the credential prompt no timeout and the lock timer accrues nothing while it is shown; closing is always fail-closed). Reviewers: confirm the value; G1b may tune it.
- P4: `onPromptHostLost(attemptId)` is id-checked, closes the attempt (T35: origin LOCKED, `LOCK_AUTH_CANCELLED`), and only then posts `authenticator.cancel(id)` to main; the adapter's synchronous `Failed(SYSTEM_CANCELED)` echo finds the attempt closed and is ignored (a test records the state at the moment of the `cancel` call).
- P5: both `authenticate` and `cancel` run through `withContext/launch(mainDispatcher)`; the dispatcher is injected as `@AppLockMainDispatcher CoroutineDispatcher` (AppLockModule). No Android type in domain. The controller depends only on the `DeviceAuthenticator` port.
- P7: the constructor pin lists the two exact additions (`DeviceAuthenticator`, `CoroutineDispatcher`) and is renamed to "seven ports"; the "no public method takes ..." pin only tightens (the `AppLockSettingsLoadState` exemption is removed because no public method takes it now); the synthetic-method check is unchanged (no default parameters and no non-suspend lambdas were added to the controller, which would create `$default` / `lambda$` synthetic methods).
- R2: `current` (volatile) and `version` are written only under `stateLock` through `setState`; `transition { ... }` runs the block under the lock, reads `(result, version, current)` in the same critical section, releases, then `publish(version, state)` takes a separate `publishLock`, drops anything with `version <= publishedVersion`, and sets `mutableState.value`. Tests: an Unconfined collector that calls `onLockQuery()` and, from another thread, a no-op `onPromptHostLost(-1)` (join with a 5 s bound) on every publication; and a collector that starts a full background+foreground while `Unlocked` is being published (the newest state `Locked(TIMEOUT)` ends up published; no older value afterwards). `publishLock` is held while collectors resume inline, so S19/S20 still must not call transitions or the authenticator from a collector, and must not block one on another thread.
- R3: `DeviceAuthCapability.CHECK_FAILED` is produced only by the provider's exception path. In the controller it behaves as "lock still applies": T3/T5 give LOCKED, T14/T15 evaluate the timer, T16 never fires, T7 returns `UnlockTapResult.CapabilityCheckFailed` (stays LOCKED, no attempt, next tap re-checks), and a prompt `NotAvailable` outcome with a failed re-check returns to the origin as a bounded error. Genuine UNKNOWN, NO_CREDENTIAL, HW_UNAVAILABLE and UNSUPPORTED_API keep the spec INERT behaviour. Enabling (T26, S18) still refuses it, as "not AVAILABLE".

AEs covered: AE-42 and AE-117 (UNLOCK purpose, success before and after the foreground), AE-44, AE-45, AE-46 (also one prompt only), AE-47 (via the old callback), AE-50, AE-118 (host loss, stale late success even after a re-lock, rotation keeps the attempt, UNLOCK purpose). Not covered (ii-b): AE-116, AE-117/118 for ENABLE/CONFIRM_CHANGE.

Decisions where the spec is ambiguous (fail-closed reading); please confirm:
1. T11 enters `INERT(reason)` with the reason mapped from the capability re-check (same mapping as T7b), not literally `NO_CREDENTIAL`; AE-50 and the table only say "INERT". A re-check that says AVAILABLE (platform and capability disagree) or CHECK_FAILED returns to the origin LOCKED as `LOCK_AUTH_ERROR(OTHER)`.
2. `LOCK_AUTH_ERROR` is claimed once per process per bucket (the bucket is the cause); the bucket travels under the existing closed `CAUSE` metadata key (enum name only), not a new key, to avoid a new sanitizer/key surface. `LOCK_AUTH_CANCELLED` and `LOCK_AUTH_LOCKOUT` are once per process each; the watchdog and host loss share `LOCK_AUTH_CANCELLED` with T9 (spec T35 says so).
3. `DeviceAuthenticator` is a constructor port, but the Activity-bound adapter is not a Hilt binding. S20 must bind a process-level relay for the port (registers the current Activity adapter, drops it at onDestroy, answers HOST_UNAVAILABLE when none is registered, `cancel` no-op). The controller is not reachable from any Hilt entry point until then, so the missing binding is not in any graph yet. Carry-forward to S20.
4. S20 maps "Activity destroyed while not changing configurations" to `onPromptHostLost(state.attemptId)` (read the id from `Authenticating`), and the adapter's ON_START to `onAppForegrounded()`. A rotation must call neither.
5. `PROMPT_WATCHDOG_MILLIS` is real (coroutine) time, not deep-sleep inclusive; the lock timer still uses only `MonotonicClock`. A watchdog can dismiss a live credential prompt after 5 minutes (returns to LOCKED; fail-closed). AE-116's 10-minute foreground prompt (ii-b) must advance the monotonic clock only, not coroutine time.
6. S18 carry-forward: T26 refusals map `CHECK_FAILED` to `LOCK_UNAVAILABLE_CAPABILITY(UNKNOWN)`; S23: spec amendment note for domain-modeler (R3) and hilt-bindings-map for `@AppLockMainDispatcher`.
7. Tests use a queued fake "main" (runs on `harness.settle()`), so a prompt call never happens on the caller's path; two tests use `thread{}` + `join(5_000)` as a bounded probe that the monitor is free. They are not sleeps, but the hanging-test guard may want to see them.

Validation to request (validation-runner, serial): `compile`; `targeted-unit-test` `*AppLockController*,*AndroidDeviceAuthCapabilityProvider*,*PrivacyDiagnosticsEmitter*`; shard `domain-n-z`; `registered-guard` `cancellation`, `time_boundaries`, `known_good_state` (new `inner class`, `Channel`, a `Triple` destructuring and a `companion object` provider are the likeliest db_guard parser surprises after the S15 CharSequence incident); `static-guards` at the S16 candidate. Likeliest compile findings: `return@transition` in Unit lambdas of the private inline `transition`, the `when (closing)` expression assigned to a captured `var`, `StandardTestDispatcher.dispatch` use in `MarkingMainDispatcher`, and the mockk `thenThrows ... andThen` chain in the provider test.
Gates: privacy-security-guardian (lead), architecture-guardian, reviewer-strict.

### S16 part (ii-a) — debug + privacy-review fixes
Status (2026-10-26, implementer): applied, NOT validated (no build, test or guard run). Paths: C = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Debugger root cause (test-only; production code is correct): two stale assertions.
- A1. T/AppLockControllerPromptOutcomeTest.kt, "P2 every non-LAUNCHED result rolls back ...": `lockedControllerFor(cause)` already performed one authenticate call (h.unlock for TIMEOUT). The test now captures `callsBefore = h.authenticator.calls.size` before its own tap and asserts `callsBefore + 1` (still exactly one launch).
- A2. T/AppLockControllerResolutionTest.kt AE-38: `resolvedController()` runs the real `resolve()`, which performs one settings read. Now `assertEquals(1, h.repository.readCount)` ("exactly one settings read (the DataStore), no Room"); `writeAttempts == 0` kept.

Privacy-security-guardian conditions (decisions final; owner-approvals item 32 refines R3):
- B3 (F1 / item 32). C/AppLockController.kt:362-368 (`onUnlockTapped`): a CHECK_FAILED capability no longer returns early. It claims a once-per-process diagnostic and continues into a normal UNLOCK attempt (T7), launching the prompt; only a matching Succeeded opens the app. Prompt `NotAvailable` with a re-check that still fails returns to LOCKED(origin) as `LOCK_AUTH_ERROR(OTHER)` (unchanged). Resolution, timer and T16 with CHECK_FAILED are unchanged (locked, never INERT). `UnlockTapResult.CapabilityCheckFailed` is REMOVED (unused); the public method set is unchanged and no pin referenced the result subclasses. Diagnostic: `PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY` (new entry at the tail of C/PrivacyDiagnosticsEmitter.kt, allowed key `CAUSE` only, SKIPPED/WARNING) over the EXISTING `DiagnosticReasonCode.LOCK_UNAVAILABLE_CAPABILITY` (spec 10 / AMD-3 / T26; no new reason code). The controller attaches `CAUSE = DeviceAuthCapability.UNKNOWN`, claimed once per process (`claimCapabilityCheckFailed`, C/AppLockController.kt:781). `PendingDiagnostic.bucket` became `cause: Enum<*>?` (private class). Spec note: spec 10 lists this code only for T26 (enable refusal); using it at an Unlock tap reuses the code, so S23 should mention it in spec 10 / T7 (domain-modeler note, together with R3).
- B4 (F2). C/AppLockController.kt:445-470 (`launchPrompt` wraps `launchWithRetries`): a spent retry budget (line ~462) closes with `closeAttempt(attempt, Closing.NotLaunched)` at once (no LOCK_AUTH_* code, no cancel); a `CancellationException` ending the launch is caught, closes the attempt with the non-suspending `closeAttempt(NotLaunched)` (a no-op when the attempt is already closed) and is rethrown unchanged. The watchdog now covers only a LAUNCHED prompt that never answers (KDoc and constants updated). Tests: "P2 F2 a retry budget that runs out closes the attempt as not launched at once, without the watchdog" and "F2 a foreign CancellationException from the launch propagates and closes the attempt as not launched at once".
- B5 (F4). C/AppLockController.kt:565-576 (`closeAttempt`): before `Closing.Success` can reach `setState(Unlocked)`, `current` must equal `Authenticating(attempt.attemptId, UNLOCK)`; otherwise the attempt is dropped as stale (inFlight cleared, jobs cancelled, state untouched, returns false). Not reachable through any public sequence or the fixtures (every writer keeps `inFlight` and the state in step), so there is no forcing test; the rationale is in the KDoc at that site.
- B6 (F5). KDoc in C/PrivacyDiagnosticsEmitter.kt:259-261 (rate-limit section) and C/AppLockController.kt:822 (`record`): the emitter's 60 s window is per code, not per bucket, so a second `LOCK_AUTH_ERROR` bucket within 60 s may be counted but not written. Accepted.

New/changed tests (T/AppLockControllerAuthOutcomeTest.kt, replacing the old CapabilityCheckFailed test): CHECK_FAILED tap launches the prompt and success unlocks (a wrong id does not; diagnostic with CAUSE=UNKNOWN); cancel returns to LOCKED and the next tap re-checks; NotAvailable plus CHECK_FAILED re-check returns to LOCKED, never INERT; the diagnostic is recorded once per process over three taps; AVAILABLE records none.

BINDING for S20 / S23 (privacy F3 / F7):
- Rotation during an UNLOCK prompt orphans the attempt: the recreated Activity gets a new adapter, and the open attempt's callback is held by the old one. S20's new adapter or relay MUST adopt the open attempt's id and callback, not wait for the watchdog.
- The process-level relay for `DeviceAuthenticator` MUST: (a) be weak or cleared at ON_DESTROY; (b) answer HOST_UNAVAILABLE when no adapter is registered; (c) route `cancel(id)` to the adapter that launched that id; (d) report host loss (`onPromptHostLost`) only from the hosting Activity and only when `!isChangingConfigurations`; (e) be unreachable from workers and receivers.
- G1b MUST verify late library terminals that arrive after the post-cancel wait (privacy F6): a late terminal for a closed attempt is stale and must not unlock or double-record.

Decisions 1-7 of "S16 part (ii-a) — implemented" are ACCEPTED by the privacy lead. R3 as written in that section (item "R3", `UnlockTapResult.CapabilityCheckFailed`, "no attempt") is superseded by B3; the S18 carry-forward in decision 6 (T26 maps CHECK_FAILED to `LOCK_UNAVAILABLE_CAPABILITY(UNKNOWN)`) now uses the same entry and the same bounded cause.

Validation to request (validation-runner, serial): `compile`; `targeted-unit-test` `*AppLockController*,*PrivacyDiagnosticsEmitter*,*AndroidDeviceAuthCapabilityProvider*`; shard `domain-n-z`; `registered-guard` `cancellation`, `time_boundaries`, `known_good_state` (new try/catch around a suspend call; the `Enum<*>?` cause).

### S16 part (ii-a) — architecture review fixes
Status (2026-10-26, implementer): applied, NOT validated (no build, test or guard run; the tree before these edits had passed validation). C = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock. No schema impact.

Supersedes (earlier lines in this file, kept as history):
- Any line that names `UnlockTapResult.CapabilityCheckFailed` (the "implemented" section, files list and R3 bullet): that result does not exist; B3 removed it.
- "3 entries" in the PrivacyDiagnosticsEmitter.kt files line of "S16 part (ii-a) — implemented": there are four ii-a entries (`LOCK_AUTH_CANCELLED`, `LOCK_AUTH_LOCKOUT`, `LOCK_AUTH_ERROR`, `LOCK_UNAVAILABLE_CAPABILITY`); the last two allow the `CAUSE` key.
- The watchdog hand-off in P2 ("then the orchestrator ends and the watchdog owns the attempt") and the "watchdog covers an exhausted retry budget" reading: a spent retry budget now closes `NotLaunched` at once (B4); the watchdog covers only a LAUNCHED prompt that never answers.

Fixes:
- F1. `claimCapabilityCheckFailed` (C/AppLockController.kt) is now claimed once per process per CAUSE: new `claimedCapabilityCauses: EnumSet<DeviceAuthCapability>`, like `claimedAuthErrorBuckets`. The tap-time claim of `UNKNOWN` no longer puts `LOCK_UNAVAILABLE_CAPABILITY` into `claimedDiagnosticCodes`, so a later S18 T26 emission with a different cause still emits once. S18 must claim T26 causes through the same `claimedCapabilityCauses` set. Test (T/AppLockControllerAuthOutcomeTest.kt): one emission with CAUSE=UNKNOWN over repeated taps, and a reflection pin that the claim lives in `claimedCapabilityCauses` and not in `claimedDiagnosticCodes`. A second cause cannot be driven until S18 adds T26, so that part is a state pin, not an emission test.
- F2. `closeAttempt` F4 mismatch branch: if `current is Authenticating` it now returns to `originState(attempt)` (LOCKED, fail-closed) after `inFlight = null` and the job cancel; any other state is left alone; it never unlocks. KDoc rationale at the site. Not reachable through any public sequence or the fixtures, so there is no forcing test.
- F3. Exact-set pin (T/PrivacyDiagnosticsEmitterTest.kt): LOCK_AUTH_CANCELLED = {}, LOCK_AUTH_LOCKOUT = {}, LOCK_AUTH_ERROR = {CAUSE}, LOCK_UNAVAILABLE_CAPABILITY = {CAUSE}, plus the set of `LOCK_AUTH_*` code names.
- F6. The internal-member source scan (T/AppLockControllerIllegalTransitionTest.kt) now covers main (java and kotlin dirs; fails closed if neither exists) plus debug and release (java and kotlin dirs, whichever exist), and `.kt` and `.java` files.
- F7 (hardening). `launchPrompt` now closes as `Closing.Abandoned` (the adapter is asked to cancel) when a `CancellationException` ends the launch after `authenticate` already returned LAUNCHED, else `NotLaunched`. `InFlightAttempt.launched` (volatile) is set on the main dispatcher right after `authenticate` returns LAUNCHED. The exception is rethrown unchanged. Test (T/AppLockControllerPromptOutcomeTest.kt) uses a new fixture `AppLockControllerHarness.cancelControllerJobs()`.
- F8. KDoc of `UnlockTapResult.BecameInert` now says the capability re-check says it is genuinely not available; CHECK_FAILED never makes the lock INERT.

Validation to request (validation-runner, serial): `compile`; `targeted-unit-test` `*AppLockController*,*PrivacyDiagnosticsEmitter*`; `registered-guard` `cancellation` (new catch branch that rethrows), `known_good_state`.
- (2026-10-26) targeted vr-20261026-113002-408245a3 FAILED on the new F1 test only. `emittedMetadata.single()` also saw the LOCK_AUTH_CANCELLED emission from the test's own cancel (the lists are captured in parallel). Test fix: read the metadata at the LOCK_UNAVAILABLE_CAPABILITY index. Assertion strength is unchanged.

### S16 part (ii-a) — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS)
Status (2026-10-26, test author): three test strengthenings applied, test-only (no production change), NOT validated (nothing was run). T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Test fixes (T/AppLockControllerPromptOutcomeTest.kt, T/AppLockControllerTestFixtures.kt):
1. Monitor-free probes (P1 test, R2 collector test): the probe thread no longer counts itself. The joining side does `t.join(5_000); if (!t.isAlive) probesInTime.incrementAndGet()` and both tests assert on `probesInTime`. A regression that runs authenticate, cancel or publication under the monitor now fails (the probe is still alive at the join) instead of only running slower. Probe threads are daemon threads.
2. R2 "a stale value is never published": new test `R2 a stale value is never published - publish drops a stamp no newer than the published version`. It reaches the private `publish(Long, AppLockState)` and the private `publishedVersion` field by reflection, after a normal unlock and re-lock. It feeds a different state with stamps equal to, one below, and far below the published version (state and version unchanged), then a newer stamp (published). Removing the version drop in `publish` fails it. The earlier ordering test is kept.
3. F2 cancellation: new fixture `AppLockControllerHarness.activeControllerJobs()`; the F2 test snapshots the running jobs (orchestrator, watchdog) right after the tap and asserts all are completed and cancelled after `settle()`. LIMIT, reported honestly: `closeAttempt` calls `attempt.cancelJobs()`, which cancels the orchestrator job itself before `launchPrompt` rethrows, so a swallowing `launchPrompt` would also end with `isCancelled`. The job state therefore pins "nothing left running or completed normally", not the rethrow itself; the comment in the test says so. A test that distinguishes the rethrow would need production to expose it (for example not cancelling the own job in the catch path); decide at the next gate whether that is worth a production change.
4. Nit: the `emittedMetadata` KDoc now says LOCK_AUTH_ERROR and LOCK_UNAVAILABLE_CAPABILITY carry a `CAUSE`.

Validation to request (validation-runner, serial): `targeted-unit-test` `*AppLockControllerPromptOutcomeTest*`, then `*AppLockController*`.

S20 questions (carry-forward):
- Map ON_START versus rotation precisely: ON_START calls `onAppForegrounded()` (it also nudges a launch waiting for AWAITING_PRIOR_TERMINAL); a configuration change (rotation) must call neither `onAppForegrounded`/`onAppBackgrounded` nor `onPromptHostLost`; host loss is only "Activity destroyed and `!isChangingConfigurations`", with the open attempt's id read from `Authenticating`. Define how a recreated Activity's ON_START after rotation is distinguished from a real foreground (it must not re-run a foreground transition that the lock timer would count), and how the new adapter adopts the open attempt (privacy F3/F7).
- The 5-minute watchdog when the library silently skips a launch: if `authenticate` answered LAUNCHED but the library never shows a prompt and never reports a terminal, the attempt stays AUTHENTICATING until `PROMPT_WATCHDOG_MILLIS` (5 minutes, coroutine time) closes it. G1b measures how often the library does this and whether a shorter or adapter-reported signal is needed.

G1b privacy F6: verify late library terminals that arrive after the post-cancel wait: a late terminal for a closed attempt is stale and must not unlock or double-record (see "BINDING for S20 / S23 (privacy F3 / F7)" above).

Remaining at the candidate level (not run here): `static-guards` for the S16 candidate and the `time_boundaries` registered guard (the part (ii-a) run list also names `cancellation` and `known_good_state`). The new probes use `thread(isDaemon = true)` plus `join(5_000)`, which the hanging-test and time-boundary guards may want to see.

### S16 part (ii-a) — accepted (2026-10-26)
- Final tree (serial, all PASS):
  - targeted `*AppLockController*,*PrivacyDiagnosticsEmitter*,*AndroidDeviceAuthCapabilityProvider*` vr-20261026-121124-a0f9444a
  - registered-guard time_boundaries vr-20261026-121342-b193d26e
- Production unchanged since these PASS runs:
  - cancellation vr-20261026-113451-8bbd59c8
  - known_good_state vr-20261026-113509-d99d4933
  - shard domain-n-z vr-20261026-115935-1d17cb6e
  Only tests changed after them.
- Gates:
  - privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS, fixed
  - architecture-guardian ACCEPT-WITH-CONDITIONS, fixed
  - reviewer-strict ACCEPT-WITH-CONDITIONS: test findings 1–2 fixed. For finding 3 a test cannot distinguish a swallowed CancellationException there; the FG-10 cancellation guard (PASS) enforces the rethrow, and this is recorded.
- Controller still DORMANT. Next: S16 part (ii-b): ENABLE/CONFIRM_CHANGE purposes, T32–T34, AuthPromptCopy selection, test-file moves. Then static-guards at the S16 candidate.

## S16 part (ii-b) — implemented
Status (2026-10-26, implementer): implemented, NOT validated (no build, test or guard run). Dormant: nothing in production calls the controller. No schema/DB impact. No new Hilt module and no new binding. No new user-visible string: the S15 prompt copy already has `enable`, `disable` and `change` titles (values, values-es, values-el), so no es/el review item arises. Legal path: the APP LOCK path (domain port-only controller; no Room, no barrier).
M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Files (source):
- M/domain/privacy/applock/AppLockController.kt:
  - `startOpenOriginAttempt(prompt: OpenOriginPrompt, onSuccess: OpenOriginSuccessHandler): OpenOriginStartResult` (public, see decision 1).
  - `Closing.OpenOriginSuccess` and `Closing.EnableRefused(cause)`, with their own success check inside `closeAttempt`.
  - `InFlightAttempt.promptBackgroundAt` and `successHandler`.
  - The foreground flag `appInForeground`.
  - `handOffPromptBackground` (T34), `deliverOpenOriginSuccess` (T33 hand-off) and an `internal val promptBackgroundAtMillis` inspection getter.
  - `claimCapabilityCheckFailed` generalised to `claimCapabilityCause(cause)`; the claimed set is unchanged.
  - New public types in the same file: `OpenOriginStartResult` and `fun interface OpenOriginSuccessHandler`.
- M/domain/privacy/applock/AppLockState.kt: new enum `OpenOriginPrompt(purpose, copy)` { ENABLE -> (ENABLE, ENABLE), DISABLE -> (CONFIRM_CHANGE, DISABLE), CHANGE -> (CONFIRM_CHANGE, CHANGE) }. A KDoc sentence on `AuthPurpose` changed.
- M/domain/privacy/applock/PrivacyDiagnosticsEmitter.kt: comment only (the cause of `LOCK_UNAVAILABLE_CAPABILITY` may also be an ENABLE refusal cause). No new entry, no new allowed key.

Files (tests):
- NEW T/AppLockControllerTimerTest.kt: the timer and pending-background tests MOVED out of `AppLockControllerResolutionTest` with every assertion unchanged (T13 to T15, AE-40, AE-41, AE-43, AE-58, AE-90, the pending-background consumption tests, `no background is pending after a re-lock and unlock`).
- T/AppLockControllerAuthOutcomeTest.kt: `T7 and T8 a tap starts one attempt...` and `attempt ids are strictly increasing...` MOVED in, unchanged.
- T/AppLockControllerResolutionTest.kt: now only resolution, spec 10 once-per-process, isolation (INV-37), AE-61 and AE-63. No test is duplicated and none was deleted: the moved ones exist exactly once, in the new file.
- T/AppLockControllerPromptOutcomeTest.kt: the "ii-b" section (start API, T33 hand-off, T34 for every non-success end, NotAvailable, AE-116 in all three callback orders at 59.999 s / 60.000 s on the monotonic clock only, T32, P6, AE-117 timer part, T35 and AE-118 for every purpose, the F5 watchdog test on coroutine time).
- T/AppLockControllerIllegalTransitionTest.kt: exact additions to the pins (decision 1) and three new spec 3.3 entries (wrong origin or any prompt in flight, one prompt across purposes, cross-purpose outcome).
- T/AppLockControllerTestFixtures.kt: `RecordingSuccessHandler`, `AppLockControllerHarness.startOpenOrigin`, `unlockedControllerFor`, `disabledControllerFor`.

Transitions implemented:
- Start (S18 binds it): ENABLE from exactly `DISABLED`, CONFIRM_CHANGE (DISABLE or CHANGE copy) from exactly `UNLOCKED`.
  - `AUTHENTICATING(any purpose)` or any open attempt gives `AlreadyInFlight`.
  - Every other state, including `INERT`, `RESOLVING`, `LOCKED` and the wrong open state, gives `NotInOrigin`.
  - Both refusals change nothing and launch nothing.
  - Otherwise: a strictly increasing id (shared with UNLOCK), the attempt is open and `AUTHENTICATING(id, purpose)` is published, then the same orchestrator and watchdog as UNLOCK (P1, P5). The copy comes from the closed `OpenOriginPrompt` selector, so no caller can pass free text.
- T32: `onAppBackgrounded` during `AUTHENTICATING(ENABLE | CONFIRM_CHANGE)` sets the attempt's `promptBackgroundAt` once, under `stateLock`. Later backgrounds do not move it. Foreground and `LOCK_QUERY` evaluate nothing while a prompt is in flight, so no time accrues while a prompt is shown. A background during `AUTHENTICATING(UNLOCK)` records nothing (unchanged).
- P6: `appInForeground` is set in `onAppForegrounded` before its early return and cleared in `onAppBackgrounded` before its early return. It starts false.
- T33 (success hand-off, S16 part only): a matching `Succeeded` for an ENABLE or CONFIRM_CHANGE attempt closes with the separate `Closing.OpenOriginSuccess`. The F4-style check is against `Authenticating(id, thatPurpose)` and an open-origin purpose. The UNLOCK-only `Closing.Success` check is unchanged. The state returns to the origin (`DISABLED` / `UNLOCKED`); `lastBackgroundedAt` is cleared (spec 3.4: an auth success that ends in an open state clears it); the attempt's `promptBackgroundAt` is discarded with the attempt; `onSuccess.onSucceeded(attemptId, purpose)` is called once, after the monitor is released and the state is published. Nothing is applied, no setting is written, nothing unlocks, and no diagnostic is claimed.
- T9 / T10 / T34: cancel, lockout, error buckets, the launch that never happened and the watchdog return to the origin with `LOCK_AUTH_*` per spec 10 (the same once-per-process claims as UNLOCK). For CONFIRM_CHANGE with `promptBackgroundAt` set, `lastBackgroundedAt := promptBackgroundAt` in the same critical section as the close. If the app is in the foreground, T14/T15/T16 are evaluated at once, in that same critical section, so a lock is published as `LOCKED(TIMEOUT)` and never as `UNLOCKED` first. In the background the evaluation waits for the next `APP_FOREGROUNDED` or `LOCK_QUERY`. With origin `DISABLED` the prompt background is dropped.
- T35: `onPromptHostLost(id)` already closed any purpose. It now carries the T34 hand-off for CONFIRM_CHANGE. A late callback is stale (id and identity check), also after a re-lock.
- NotAvailable:
  - UNLOCK: unchanged (T11).
  - ENABLE: if the re-check is not AVAILABLE, a T26-style refusal (`Closing.EnableRefused`). Back to `DISABLED`, `LOCK_UNAVAILABLE_CAPABILITY` with `CAUSE` = the re-check's `DeviceAuthCapability` (CHECK_FAILED maps to UNKNOWN). Claimed once per process per cause through the existing `claimedCapabilityCauses`, shared with the Unlock-tap claim and with S18's T26. A re-check that says AVAILABLE gives `LOCK_AUTH_ERROR(OTHER)`.
  - CONFIRM_CHANGE: `LOCK_AUTH_ERROR(OTHER)` and back to `UNLOCKED`; there is no INERT row (spec T34), so T16 applies at the next foreground.
- Cross-purpose: a success whose purpose does not match the state, or whose id or attempt object is stale, changes nothing. An abandoned ENABLE or CONFIRM_CHANGE callback cannot unlock `LOCKED` or answer an UNLOCK attempt; an UNLOCK success never reaches an open-origin handler.

AEs covered (controller level): AE-116 (10-minute foreground prompt accrues nothing; Home then the system cancel at 59.999 s / 60.000 s; the cancel before the background, while backgrounded, and after the foreground), AE-118 (host loss for ENABLE, DISABLE and CHANGE; late success stale, also after a re-lock; rotation keeps the attempt; the control is enabled again), the timer part of AE-117 (success before and after the foreground discards the prompt background; the apply itself is S18), AE-42/AE-44/AE-45/AE-46/AE-47/AE-50 unchanged from ii-a. The held-link part of AE-118 is S17.

Decisions where the spec is ambiguous (fail-closed reading); please confirm:
1. Public API, not internal. S18 (another main file) must call the start API, so an `internal` + source-scan pin that forbids other main files would block the intended caller, and `internal` is not enforced by lint here (R1). The pins are therefore updated by exact additions only (P7):
   - the public method set gains exactly `startOpenOriginAttempt`;
   - the "no public method takes ..." allow-list gains exactly the two bounded domain types `OpenOriginPrompt` and `OpenOriginSuccessHandler`;
   - the internal-member source scan gains the new test-only getter `promptBackgroundAtMillis`.
   No pin was loosened. The constructor pin (seven ports) is unchanged.
2. The T33 hand-off is a per-attempt handler passed at start (`OpenOriginSuccessHandler`), not a controller-wide registry and not an eighth constructor port. That needs no Hilt binding and no mutable global, and the handler can capture its request (for example the new timeout). It is invoked once, only for a matching success, after the attempt is closed and the state is already back on the origin; S18 then performs the durable write and moves the state (T17 to `UNLOCKED`) through a controller method it adds. Between the hand-off and that call the state is the origin; S18 must keep its own request in flight (disabled switch) in that gap. A throwing handler is logged by class name and ignored.
3. T33's timer clause is implemented in S16 for the only case that exists in Increment 1: every ENABLE or CONFIRM_CHANGE prompt uses the UNLOCK authenticators (unlock-equivalent), so the success discards `promptBackgroundAt` and clears `lastBackgroundedAt` immediately. The "not unlock-equivalent: hand the background to the timer" branch (AE-120, Increment 2) has no case yet and is left for S25/Inc 2. The plan puts "plus the unlock-equivalent discard" in S18; leaving it out would have left a stale pending background after a success, so it is done here. S18 must not repeat it.
4. T34 with an already pending `lastBackgroundedAt` keeps the earlier of the two (the spec assigns `promptBackgroundAt`). The two cannot differ in practice, since a prompt only starts from the foreground, but if they ever did this can only lock sooner.
5. The foreground flag starts false: with no foreground seen a T34 evaluation is deferred to the next `APP_FOREGROUNDED` or `LOCK_QUERY` and never lost, whereas a wrongly true flag could consume time away. S20 must deliver `APP_FOREGROUNDED` at every real foreground including the first ON_START (carry-forward). `LOCK_QUERY` does not set the flag (a deep link can arrive while stopped).
6. T34 also runs for a launch that never happened (`NotLaunched`) and for the watchdog: spec 3.3 forbids an open-origin prompt that ends without success returning to `UNLOCKED` without the timer evaluating a recorded background, whatever the reason it ended.
7. ENABLE `NotAvailable` is a refusal (the plan's "preferably" reading), with the capability enum name as `CAUSE` for every non-AVAILABLE cause, including `NO_CREDENTIAL` and `UNSUPPORTED_API`. The spec gives those two their own codes (`LOCK_UNAVAILABLE_NOT_SECURE`, `LOCK_UNAVAILABLE_API`) at the T26 request-time check, and the emitter has no entries for them yet. S18 must decide the T26 mapping; the per-cause claim set is shared either way. A CHECK_FAILED re-check refuses as UNKNOWN (enabling never proceeds on an unknown capability).
8. The start API does not check capability or API level: that is T26 and the caller's request-time check (S18). It also does not check the settings load state (T27 and the others are S18).
9. F5 as decided in the ii-a review: the watchdog applies to every purpose. AE-116's 10-minute prompt is tested on the monotonic clock only (no coroutine time); a separate test advances coroutine time past `PROMPT_WATCHDOG_MILLIS` for ENABLE, DISABLE and CHANGE and shows fail-closed abandonment (change not applied, late success stale).

Carry-forwards: S18 (bind `startOpenOriginAttempt`; do the durable write inside the handler's request; T17 and T18 state moves; T26 request-time refusal and codes; keep a request-in-flight guard between hand-off and the state move; claim T26 causes in `claimedCapabilityCauses`); S20 (deliver `APP_FOREGROUNDED` at every real foreground, `onPromptHostLost` only for real host loss, adopt the open attempt after rotation as already recorded); S23 (spec note for decisions 3, 4 and 7; hilt-bindings-map is unaffected).

Validation to request (validation-runner, serial):
- `compile`
- `targeted-unit-test` `*AppLockController*,*PrivacyDiagnosticsEmitter*`
- shard `domain-n-z`
- `registered-guard` `cancellation` (a new catch that rethrows in `deliverOpenOriginSuccess`), `time_boundaries`, `known_good_state`
- then `static-guards` at the S16 candidate
Likeliest compile findings: the `Triple` / `Pair` list typing in the new tests, `return@transition` in the Unit lambdas of `onAppBackgrounded` and `startOpenOriginAttempt`, and the `when (closing)` expression with the two new branches assigned to a captured `var`. Likeliest test findings: the timing in `homeAndReturn` (the cancel callback is delivered through the fake authenticator) and the synthetic-method pin (no default parameter or non-inline lambda was added to the controller).
Gates: privacy-security-guardian (lead), architecture-guardian, reviewer-strict.

### S16 part (ii-b) — privacy-review fixes
Status (2026-10-26, implementer): implemented, NOT validated (no build, test or guard run). Resumed after an API-error abort of the first fix pass. Still DORMANT, no schema/DB impact, no new string, no new binding.
M = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Fixes (the first pass left no usable edit in the controller; all of the main-file edits below were made in the resume pass):
- F1 (M/AppLockController.kt:860 `handOffPromptBackground`): no early return when `promptBackgroundAt == null`. `merged = listOfNotNull(promptBackgroundAt, lastBackgroundedAtMillis).minOrNull() ?: return null`, `lastBackgroundedAtMillis = merged`, then the same foreground/capability check and the evaluation in the same critical section. Test: T/AppLockControllerPromptOutcomeTest.kt:1312 (background, +60 s, start CHANGE, foreground, cancel gives LOCKED(TIMEOUT); +59.999 s gives UNLOCKED).
- F2 (M/AppLockController.kt:697 `routeNotAvailable`): ENABLE emits `LOCK_UNAVAILABLE_CAPABILITY` only for HW_UNAVAILABLE and UNKNOWN (CHECK_FAILED is reported as UNKNOWN). NO_CREDENTIAL, UNSUPPORTED_API and AVAILABLE give `Closing.Error(OTHER)`, staying DISABLED with nothing written; `TODO(S18)` at :706 for `LOCK_UNAVAILABLE_NOT_SECURE` / `LOCK_UNAVAILABLE_API`. The once-per-cause claim set is re-keyed by (code, cause): `claimedCodeCauses` (:289, replaces `claimedCapabilityCauses`), used by `claimCapabilityCause` (:1047). Tests updated, none loosened: T/AppLockControllerPromptOutcomeTest.kt:894 (HW_UNAVAILABLE / UNKNOWN), :932 (new, NO_CREDENTIAL / UNSUPPORTED_API), :991 (reflection on `claimedCodeCauses`), T/AppLockControllerAuthOutcomeTest.kt:486 (reflection on `claimedCodeCauses`, the Unlock-tap leg).
- F3 (T/AppLockControllerIllegalTransitionTest.kt, source-scan pin `no production file other than the controller touches the controller's internal inspection members`): `startOpenOriginAttempt` added to the forbidden list (exact addition). No main, debug or release file other than AppLockController.kt references it. Commented: S18 adds exactly its own file as the one caller and extends the pin to that single file.
- F4 (M/AppLockController.kt:731, `closeAttempt` CONFIRM_CHANGE capability read): any Throwable from the read is caught, the close continues with capability = null, and the same throwable is rethrown unchanged after the close (also when the attempt was already closed). CancellationException is therefore rethrown too, but only after the attempt is closed, so it never stays open. Test: T/AppLockControllerPromptOutcomeTest.kt:1339 (an Error and a CancellationException; the attempt closes, the error surfaces, a late success is stale, a new attempt starts). Fixture: `FakeCapabilityProvider.failure` added in T/AppLockControllerTestFixtures.kt.
- F5 (M/AppLockController.kt:769, defensive stale-success branch): when the state is still AUTHENTICATING and returns to the origin, `handOffPromptBackground(attempt, null)` runs (merge only; no capability was read for a success close, so the evaluation waits for the next foreground). Test: T/AppLockControllerPromptOutcomeTest.kt:1377 (forces the mismatch by reflection on `current`).
- F6 (T/AppLockControllerPromptOutcomeTest.kt:991): the ENABLE test renamed to `ENABLE refusals under a failed capability check claim UNKNOWN once per process, keyed by code and cause`. The real Unlock-tap leg already exists in T/AppLockControllerAuthOutcomeTest.kt (`F1 the tap-time capability claim is per cause...`, now asserting the (code, cause) pair).
- F7 (M/AppLockController.kt:72 KDoc of `OpenOriginSuccessHandler`): CancellationException is rethrown, S18 must never throw it from the handler; the handler runs outside the monitor and may call back into the controller. Test: T/AppLockControllerPromptOutcomeTest.kt:1403 (the handler starts a new attempt, queries from another thread with a 5 s join, and backgrounds; no deadlock, the handler saw UNLOCKED with no open attempt, the final state is the new attempt's AUTHENTICATING).

Decisions 1 to 9 of S16 part (ii-b) above: accepted by the privacy lead (decision 7 is superseded in its detail by F2: NO_CREDENTIAL and UNSUPPORTED_API are a bounded OTHER error, not a capability refusal, until S18 adds their codes).

Carry-forwards:
- S18:
  - re-check T17/T18 under the monitor;
  - the durable write goes off the main thread;
  - keep the request guard between the hand-off and the state move;
  - never throw CancellationException from the handler;
  - T26 claims per (code, cause) through `claimedCodeCauses`, and S18 adds `LOCK_UNAVAILABLE_NOT_SECURE` / `LOCK_UNAVAILABLE_API` for NO_CREDENTIAL / UNSUPPORTED_API.
- S20:
  - `APP_FOREGROUNDED` at every real foreground, including the first;
  - `onPromptHostLost` only for real host loss.
- S23:
  - spec notes for decisions 3, 4 and 7;
  - F8: a success while backgrounded clears the pending background (the G1b device check).

Validation to request (validation-runner, serial): `compile`; `targeted-unit-test` `*AppLockController*,*PrivacyDiagnosticsEmitter*`; shard `domain-n-z`; `registered-guard` `cancellation`, `time_boundaries`, `known_good_state`; then `static-guards` at the S16 candidate.
Likeliest findings: the local `class CapabilityBoom : Error` inside the F4 test, the lambda-captured vars of the F7 test, the reflection on the private `current` field (F5 test) and `claimedCodeCauses` (the field is a `Set<Pair<...>>`, compared with `setOf(code to cause)`).

### S16 part (ii-b) — architecture review (ACCEPT-WITH-CONDITIONS) and fixes (2026-10-26)
- Validation on the post-privacy-fix tree (serial, all PASS):
  - targeted vr-20261026-134327-f8b307fb
  - cancellation vr-20261026-134816-9469d50f
  - known_good_state vr-20261026-134834-556415ca
  - domain-n-z vr-20261026-135136-88e206c0
  - static-guards vr-20261026-135309-71b86c76
- Fixes (code; applied in a resumed pass after an API-error abort):
  - (1) The closeAttempt capability read uses the guard-recognised `catch (e: Throwable) { closeWith(..., null); throw e }` shape.
  - (2) The routeNotAvailable re-check is guarded the same way.
  - (3) The stale-success branch locks (COLD_START) if an open-origin attempt faces Authenticating(_, UNLOCK).
  - (4) Test: a handler that throws CancellationException rethrows the same instance.
- Superseded text:
  - "claimed set is unchanged" (~:1019) and the `claimedCapabilityCauses` names (~:1045, :1066) are superseded by privacy fix F2 (`claimedCodeCauses`, keyed by (code, cause)).
- BINDING carry-forwards:
  - S17:
    - onLockQuery returns AUTHENTICATING without evaluating. Hold the link during an open-origin prompt and never decide to apply it from that state.
    - Release the link only after the T34 hand-off and evaluation, or after T33 AND S18's apply completes or fails. Never release it when the close publishes the origin state.
    - HeldDeepLink must not call startOpenOriginAttempt.
    - The apply-now/hold decision is made under the monitor.
  - S18:
    - Bind startOpenOriginAttempt from exactly one main file, and extend the F3 pin to that file only.
    - The handler belongs to an application-scoped operation: no ViewModel, Activity or Compose capture. It is non-blocking, does the durable write off main, and never throws CancellationException.
    - Re-check T17/T18 under the monitor before moving state. Define the gap where a T14 lock or a settings re-read lands between hand-off and state move (AE-114).
    - Do not repeat the unlock-equivalent discard.
    - T26 request-time check before start. Add LOCK_UNAVAILABLE_NOT_SECURE and LOCK_UNAVAILABLE_API (emitter entries and pins) via claimedCodeCauses, and resolve the TODO(S18).
  - S20:
    - APP_FOREGROUNDED at every real foreground, including the first ON_START; APP_BACKGROUNDED at every real stop; a rotation sends neither.
    - onPromptHostLost only for real host loss.
    - G1b: check whether the device-credential UI stops the host or the process (T32, then an IMMEDIATE lock via T34).
    - The adapter keeps clearing its callback on cancel and terminal, and tolerates a handler that calls back in.

### S16 part (ii-b) — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes
All in `AppLockController.kt`; line numbers are after the fixes.
- ISSUE-1 (closeWith, :774-:808): the state/attempt mismatch check was success-only.
  - It now runs before the `when (closing)`, for every closing, against `Authenticating(attempt.attemptId, attempt.purpose)`. A purpose mismatch (Success on a non-UNLOCK attempt, OpenOriginSuccess on an UNLOCK attempt) also counts.
  - On a mismatch: inFlight is cleared, the jobs are cancelled, no handler is called, no outcome code is claimed, and the app never unlocks.
  - A stranded `Authenticating` state with purpose UNLOCK, or a purpose other than the attempt's, becomes `Locked(COLD_START)`. The same purpose with another id returns to the attempt's origin, with the T34 hand-off. Any other state is left as it is.
  - The KDoc now says "the last fail-closed check" truthfully.
  - Behaviour change: an UNLOCK attempt with a stranded `Authenticating(other id, UNLOCK)` now goes to `COLD_START`, not to `Locked(originLockCause)`. Unreachable through the public API.
  - Test: "ISSUE-1 a cancel of an attempt whose state is AUTHENTICATING(UNLOCK) of another id ..." (PromptOutcomeTest; DISABLE, CHANGE, ENABLE).
- ISSUE-2 (closeAttempt catch, :754; stale branch, :799-:803): the guarded capability-read catch passed `null`, so T34 waited. It now passes `DeviceAuthCapability.CHECK_FAILED`, and the stale branch uses `capability ?: CHECK_FAILED`.
  - `CHECK_FAILED` maps to no inert reason (`toInertReason`, R3), so it can never yield INERT.
  - The throwable is still rethrown after the close.
  - A T34 lock that really happens in a stale close records its own `LOCK_SHOWN_TIMEOUT`. The stale outcome claims no code.
  - Test: "ISSUE-2 a capability read that throws during a CHANGE cancel in the foreground past the timeout still locks, then rethrows".
  - Not changed: `routeNotAvailable`'s re-check catch (:704) still passes `null` (waits for the next foreground). Its F4 test pins that. S18 may align it.
- ISSUE-3 (closeAttempt, :745; launchPrompt, :593-:598): `if (!isOpen(attempt)) return false` (read under stateLock) now comes before the capability read. In `launchPrompt` a throwable from the close is attached to the original `CancellationException` with `addSuppressed`, and the original is rethrown.
- ISSUE-4 (PendingDiagnostic KDoc, :209-:214): the `LOCK_UNAVAILABLE_CAPABILITY` cause is `HW_UNAVAILABLE` or `UNKNOWN`.
- S18 questions:
  - (a) Call `onLockQuery()` before `startOpenOriginAttempt` if S20 cannot guarantee that `APP_FOREGROUNDED` runs before the first tap.
  - (b) T27 lists INERT as a review-save origin, but the start API refuses INERT. S18 decides INV-45.
- Test-coverage note: the "rotation" tests are plain success tests by contract (a rotation sends no lifecycle event). G1b covers real rotation.
- Condition 1: re-run `static-guards` on the final tree. Also request `compile`, `targeted-unit-test` `*AppLockController*`, `cancellation`, `known_good_state`, and shard `domain-n-z`.

### S16 part (ii-b) — accepted (2026-10-26)
- Final tree (serial, all PASS):
  - targeted vr-20261026-155724-cfa3aa00
  - cancellation vr-20261026-160213-b9ca974f
  - known_good_state vr-20261026-160231-bcdff955
  - domain-n-z vr-20261026-160532-1b72a5a1
  - static-guards vr-20261026-160706-8f3ce006
- Gates (ii-b):
  - privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS, fixed
  - architecture-guardian ACCEPT-WITH-CONDITIONS, fixed
  - reviewer-strict ACCEPT-WITH-CONDITIONS, ISSUE-1..4 fixed
  - review iteration limit reached; accepted on validation
- S16 COMPLETE (parts i, ii-a, ii-b). The controller is still DORMANT until S20 wiring.
- Next: S17 (held deep links).

## S17 — implemented

Status (2026-10-26, implementer): implemented, NOT validated (no build, test or guard run). DORMANT (no production wiring, S20), no schema/DB impact, no new string, no new binding, no new diagnostic code (the existing `DL_HELD_*` entries are used).
M = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Files:
- M/HeldDeepLink.kt (new): `HeldDeepLink` (internal; wraps the S3 `DeepLinkRequest`, `heldAtMillis`, `unlockCountAtHold`; no raw URI, not serializable or parcelable, `toString` names the host only; `HELD_LINK_TTL_MILLIS` = 5 min), `LinkDecision { APPLY_NOW, HOLD }`, `HeldLinkRelease { None, Apply(request, afterUnlock), Expired }`. It never references the open-origin start API (source-scan pin intact).
- M/AppLockController.kt: new `onLockQuery(link: DeepLinkRequest): LinkDecision` (overload), `releaseHeldLink(): HeldLinkRelease`, `onOpenOriginApplyCompleted(attemptId: Long)`; private `decideLinkLocked`, `releaseLocked`, `applyGateOpenLocked`, `readQueryCapability`, three `DL_*` diagnostic builders; fields `heldLink`, `unlockCount`, `applyGateAttemptId`, `applyGateSinceMillis`; `PendingDiagnostic` gained optional ready-made metadata (secondary constructor keeps the old call sites); T8 increments `unlockCount`; the T33 close opens the gate; the four `TODO(S17)` markers became comments.
- T/AppLockControllerHeldLinkTest.kt (new, 29 tests).
- T/AppLockControllerIllegalTransitionTest.kt: pins changed by exact addition only (below).

Transitions:
- T20: `onLockQuery(link)` runs the LOCK_QUERY evaluation (`evaluateForegroundLocked`) and decides in the same critical section. HOLD when the state is `LOCKED`, `RESOLVING` or `AUTHENTICATING` of ANY purpose, when an attempt is open, or when the T33 gate is open. Newest wins; `DL_HELD_LOCKED` (host) or `DL_HELD_REPLACED` (old and new host).
- T21: APPLY_NOW from `DISABLED`, `UNLOCKED`, `INERT` with nothing pending, after the evaluation (a long background locks first, AE-58). A link still held at that time is older and is dropped (so it can never apply after the newer one).
- T22: memory only; nothing to do. A new process has no link.
- Release (T2, T4, T6, T6b, T8, T33, T34): PULL through `releaseHeldLink()`, a LOCK_QUERY itself. Only from `DISABLED`/`UNLOCKED`/`INERT`, with no open attempt and no open T33 gate; the link is removed under the monitor (at most once). Expired after `T_held` (5 min, monotonic, a backwards clock counts as expired): `DL_HELD_EXPIRED(host)` and the caller opens Home (AE-60).
- The close of a prompt never releases. After T34 the link is released only once the hand-off and its evaluation are done (immediate in the foreground, or in the release's own LOCK_QUERY when backgrounded). After T33 the controller opens the gate (`applyGateAttemptId`) in the close's critical section; it stays open until `onOpenOriginApplyCompleted(attemptId)` (S18, on apply success OR failure). The gate is bounded by `T_held` on the monotonic clock, so a forgotten completion cannot hold a link for the whole process (the link would be expired by then anyway).
- `HeldDeepLink` never starts a prompt; the held link triggers no write.

AEs: AE-55, AE-56, AE-57, AE-58 (both event orders), AE-59, AE-60 (4:59.999, 5:00, 6:00), AE-118 link part (before and after host loss; stale late success), hold during UNLOCK / ENABLE / CONFIRM_CHANGE, release after T34 (foreground lock, backgrounded deferred timeout) and after T33 plus completion, link between hand-off and completion, gate lapse, RESOLVING (T2, T3), INERT/DISABLED, no link content in diagnostics or logs, memory-only type, CancellationException from the capability read propagates.

Pins (P7, exact additions only; the constructor pin (seven ports) and the source-scan list are unchanged):
- public method set gains exactly `releaseHeldLink` and `onOpenOriginApplyCompleted` (`onLockQuery` is an overload, the name already pinned);
- the "no public method takes ..." allow-list gains exactly `DeepLinkRequest` (a validated, bounded domain type);
- no new synthetic method, no default parameter on the controller.

Decisions where the spec is ambiguous (fail-closed reading); please confirm:
1. `onLockQuery()` (no argument) keeps returning `AppLockState` (13 existing tests and the S20 binder use it). The bounded decision is the new overload `onLockQuery(link)`. A decision needs the link, and a new method name would have added a name to the pin; the overload adds none. The plan's open question 3 is thus answered by the overload.
2. Pull, not push: the controller has no navigation port (seven-port pin), so a held link is handed back by `releaseHeldLink()` instead of being applied. The caller applies it (re-validating INV-16) and emits `DL_APPLIED` with `afterUnlock` from `Apply.afterUnlock` (true only when a T8 unlock happened since the link was held).
3. Completion entry is public, id-bound, not internal: its caller (S18) is another main file and `internal` is not enforced here (the S16 decision 1 reasoning). A wrong id changes nothing, a repeat is harmless, and it releases nothing itself (a wrong call can at worst open the gate for the id it names).
4. No auto-clear on disable or data wipe. The spec does not list them (T22 and the memory-only rule are the only clears). T33 requires the link to be released after a successful DISABLE/CONFIRM_CHANGE apply, so clearing it on disable would contradict INV-54. A data wipe or restore ends in a process restart (AE-61), which drops the link. If the privacy lead wants a clear on a disable, S18 adds a private clear in the same critical section as the T18 state move.
5. A held link survives a T14 lock and stays held while `LOCKED`; the TTL is measured from the moment it was held (not from the unlock).
6. A link that arrives in an open state while an older one is still held (not yet pulled) applies now and drops the older one, with `DL_HELD_REPLACED`, so an older link can never apply after a newer one.
7. Expiry returns `Expired` (the caller opens Home) rather than applying a Home request, so the controller never invents a link.
8. A failed capability read in the link path is `CHECK_FAILED` (transient, never INERT, the timer still evaluates); `CancellationException` and `Error` propagate. Nothing is held or applied if the read is cancelled.
9. `DL_HELD_*` are not claimed once per process (each held link is an event); the emitter's own rate limit is the backstop.

Carry-forwards:
- S18: call `onOpenOriginApplyCompleted(attemptId)` on every end of the apply (success and write failure, also a throwing handler path), then trigger a release; the T17/T18 state move must happen before the completion call.
- S20: after the state becomes open (collector or ON_START), after `onOpenOriginApplyCompleted`, and after T2/T4/T6/T6b, call `releaseHeldLink()` and apply `Apply` once (re-validate INV-16, emit `DL_APPLIED(afterUnlock)`), or open Home on `Expired`. Call `onLockQuery(link)` from `onNewIntent`/`onCreate` instead of the no-argument query, and apply only on `APPLY_NOW`. Never put the link in a Bundle or saved state.
- S23: spec note for decisions 1 to 4 and 6.

Validation to request (validation-runner, serial):
- `compile`
- `targeted-unit-test` `*AppLockControllerHeldLink*,*AppLockController*`
- shard `domain-n-z`
- `registered-guard` `cancellation` (new catch in `readQueryCapability`), `known_good_state`
Likeliest compile findings: `out += if (...) a else b` on a `MutableList` parameter; the `?.let { pending += it }` statement in the inline `transition` lambdas; `Timber.Tree.log` override visibility in the privacy test. Likeliest test findings: the deferred-timeout test (backgrounded cancel then release) and the 60.000 s boundary in the foreground T34 test.
Gates: privacy-security-guardian (lead), architecture-guardian, reviewer-strict.

### S17 — review fixes (privacy-security-guardian + architecture-guardian, both ACCEPT-WITH-CONDITIONS)

Status (2026-10-26, implementer): applied, NOT validated (nothing was run). Still DORMANT, no schema/DB impact, no new string, no new diagnostic code. Public method set unchanged (no new method). No new public type.
M = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock. Line numbers are of the tree after these fixes.

Findings:
- F1 (HIGH): M/AppLockController.kt:638, first statement of the `releaseHeldLink()` transition: `if (!appInForeground) return@transition HeldLinkRelease.None`. No evaluation, nothing consumed, the link stays held and the pending background stays pending. NOT added to `onLockQuery(link)` (AE-58 needs the decision before onStart). KDoc of `releaseHeldLink` (:620-629) and of `HeldLinkRelease.None` (M/HeldDeepLink.kt) updated.
  - Tests changed to call `onAppForegrounded()` before a release (T/AppLockControllerHeldLinkTest.kt): "release after T34 evaluates the deferred timeout first" (:290; the lock now happens at the foreground, then the release is None, then Apply after unlock); the RESOLVING tests (T2 and T3), the INERT/DISABLED test and AE-57 (restarted controller) now report the foreground first; the shared fixture `lockedControllerFor` is NOT changed: a local `foregroundLockedControllerFor` wrapper (T/AppLockControllerHeldLinkTest.kt, top) replaces every `lockedControllerFor(COLD_START)` in that file (AE-55, AE-56, AE-60, backwards clock, T20 UNLOCK, privacy, capability, no-prompt, UNLOCK-never-opens-gate tests). Tests that use `unlockedControllerFor`/`disabledControllerFor` already report the foreground.
  - New repro tests: (a) `F1 a backgrounded T34 cancel within the timeout ...` (:307): release None, pending background still pending, advance past the timeout, `onAppForegrounded` gives LOCKED(TIMEOUT), unlock, release gives Apply(afterUnlock = true). (b) `F1 a T33 success, then a background, then the apply completion ...` (:336): release None and pending kept, a foreground after the timeout locks, Apply(afterUnlock = true) after unlock.
- F2/F6: M/AppLockController.kt:710 `releaseLocked`: a T33 gate that lapsed (T_held without `onOpenOriginApplyCompleted`) with a link held drops the link as `Expired` (`DL_HELD_EXPIRED`, the caller opens Home), also when the link itself is younger than T_held. `Apply` is never returned before the apply completed. New `applyGateWithinBoundLocked` (pure, no clearing); `applyGateOpenLocked` (decide path) still clears on lapse and a newer link then applies now. Test: `F2 a link that arrives after the T33 hand-off and a gate that then lapses ...` (T/AppLockControllerHeldLinkTest.kt:448). Existing "a gate that nobody completes lapses" test unchanged and still valid.
- F3: T/AppLockControllerIllegalTransitionTest.kt:571 `onLockQuery has exactly two forms ...`: exactly `onLockQuery()` returning `AppLockState` and `onLockQuery(DeepLinkRequest)` returning `LinkDecision`; `releaseHeldLink()` returns `HeldLinkRelease`; `onOpenOriginApplyCompleted(long)` returns void.
- F4: T/AppLockControllerIllegalTransitionTest.kt:618-619 source-scan list gains exactly `"releaseHeldLink"` and `"onOpenOriginApplyCompleted"` (startOpenOriginAttempt precedent). To keep the scan green M/HeldDeepLink.kt KDoc no longer names `releaseHeldLink` (reworded, no code change).
- F5: M/AppLockController.kt:574 `startOpenOriginAttempt` returns the existing `OpenOriginStartResult.NotInOrigin` while the T33 gate is open and not lapsed (no new type; KDoc updated). A lapsed gate does not block and is not cleared there (so a link still held is dropped by the next release, F2). Test: `F5 an open-origin start is refused while the T33 gate is open ...` (T/AppLockControllerHeldLinkTest.kt:471). Existing test changed: the F7 re-entrancy test in T/AppLockControllerPromptOutcomeTest.kt:1527 now calls `controller.onOpenOriginApplyCompleted(attemptId)` in its handler before it starts the next attempt (S18 does the same when its apply has finished).
- F7: KDocs of `record()` (M/AppLockController.kt:~1347) and `PendingDiagnostic` (:226-243) now cover the host metadata (`HOST` key, the `DeepLinkHost` enums only; `DL_HELD_*`); `PendingDiagnostic` gained `init { require(metadata == null || cause == null) }`.
- F8: M/AppLockController.kt:1124 `deliverOpenOriginSuccess` and :1142 `clearApplyGate(attemptId)`: when the success handler throws, the gate of that attempt id is cleared under `stateLock`. DEVIATION (small, fail-closed for the apply, not for privacy): also on `CancellationException`, before it is rethrown unchanged, because the existing test "a success handler that throws CancellationException ... then the next start is Started" would otherwise break on F5 and a handler that did not return started no apply. Tests: `F8 a success handler that throws clears the gate ...` (:505) and `F8 ... CancellationException ...` (:522).

Corrected wording (supersedes the S17 "implemented" section where it differs):
- Gate lapse: a gate that lapses after T_held with a link held does NOT let the link apply. The link is dropped as `Expired` (`DL_HELD_EXPIRED`) and the caller opens Home. The old line "the link would be expired by then anyway" is wrong for a link that arrived after the hand-off.
- AE-61 (decision 4): a data wipe or restore ends in a restart that is the user tapping "Restart Now", not an automatic one; the process restart drops the link, and there is no auto-clear on disable.
- The old carry-forward text "release from a collector or when backgrounded" (S20: "after the state becomes open (collector or ON_START)") and the S17 line "or in the release's own LOCK_QUERY when backgrounded" are SUPERSEDED: `releaseHeldLink()` evaluates and releases only while the app is in the foreground (F1); a collector never calls it.

Decisions 1 to 9 of the S17 section: accepted by the privacy lead (decision 4 with the AE-61 wording above; decisions 5 and 6 as written).

BINDING carry-forwards:
- S18:
  - call `onOpenOriginApplyCompleted(attemptId)` on every end of the apply, in a `finally`: success, write failure, a throwing handler, a cancelled apply (`CancellationException` is still rethrown);
  - do the T17/T18 state move under the monitor BEFORE that call;
  - be the only production caller of `onOpenOriginApplyCompleted` (extend the new source-scan pin by exactly that one file).
- S20:
  - `onLockQuery(link)` from `onCreate`/`onNewIntent`, apply only on `APPLY_NOW`; never decide with the no-argument `onLockQuery()`;
  - pull `releaseHeldLink()` only at ON_START right after `onAppForegrounded()`, and after the apply completion; never inline in a collector (a collector may only post a separate main-thread call);
  - decide and apply synchronously on main in the same frame; apply a released link before processing the next intent; never switch dispatchers in between;
  - on `Apply`: re-check INV-16, apply once and emit `DL_APPLIED(afterUnlock)`; on `Expired`: open Home;
  - be the only production caller of `releaseHeldLink` (extend the new source-scan pin by exactly that one file);
  - no Bundle/SavedState/`rememberSaveable` for the link;
  - after a recreation or a process-death restore do not resubmit the original launch intent: skip when `savedInstanceState != null` or `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, or clear the intent data once consumed (a boolean "consumed" flag in saved state is allowed);
  - deliver `APP_FOREGROUNDED` at the first ON_START (the controller starts with `appInForeground = false`, so no release happens before it).

Validation to request (validation-runner, serial): `compile`; `targeted-unit-test` `*AppLockController*`; shard `domain-n-z`; `registered-guard` `cancellation` (new rethrow path in `deliverOpenOriginSuccess`), `known_good_state`.
Likeliest findings: Kotlin smart cast in `assertTrue(... is Started && x.attemptId ...)` (used in the new F5 test), `Long::class.javaPrimitiveType!!` in the F3 test, test ordering of `onAppForegrounded()` in RESOLVING.

### S17 — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes

Implemented by the implementer; validation NOT run (request `compile`; `targeted-unit-test` `*AppLockController*`; shard `domain-n-z`).

- Issue 2 (code, `AppLockController.kt:702`): `decideLinkLocked` now computes `val gateOpen = applyGateOpenLocked(now)` BEFORE `mustHold` (no short-circuit) and uses `gateOpen` in it. A T33 gate that lapsed before the link arrived is cleared at hold time, even while LOCKED, so the later release is `Apply`, not `Expired`. A link held under a still-open gate is still dropped at lapse (F2 preserved). New test: `AppLockControllerHeldLinkTest.kt:471` (gate lapses, app locks, link held, unlock, release in the foreground returns `Apply(afterUnlock = true)`).
- Issue 3 (pin, `AppLockControllerIllegalTransitionTest.kt:622`): `"onLockQuery"` added to the single-caller source-scan list. There is no production caller today, so it stays green. S20 extends it by exactly the host Activity file.
- Note: `AppLockController.kt:636` `releaseHeldLink` folds `appInForeground` into the early-return pre-check (`heldLink == null || !appInForeground`), so the capability read is skipped when backgrounded. The in-transition guard stays; behaviour is otherwise identical.
- Note: stale test title renamed at `AppLockControllerHeldLinkTest.kt:285` (no longer claims the release evaluates the deferred timeout first).
- Note: the foreground-only release condition (F1) is added to the class KDoc paragraph "The held link" (`AppLockController.kt:103-108`); the `releaseHeldLink` KDoc caller sentence now matches the S20 binding below.

BINDING S20 (Issue 1, MAJOR; this supersedes the earlier "only at ON_START" text at line 1259):
- The state collector posts ONE separate main-thread `releaseHeldLink()` call on every publication of `DISABLED`, `UNLOCKED` or `INERT` (T2, T4, T6, T6b, T8, the T34 return). It never calls it inline under `publishLock`.
- ON_START (right after `onAppForegrounded()`) and after-apply-completion remain additional triggers.
- Required S20 integration test: link at `onCreate`, then ON_START while `RESOLVING`, then resolve to `DISABLED`: the link is applied exactly once.
- S20 allows exactly the host Activity file as the caller of `onLockQuery(link)` and `releaseHeldLink` (both pins extended by that one file).

BINDING S18 (Issue 4): starting the apply is the handler's LAST statement. The handler must not throw after the apply has started. The completion call `onOpenOriginApplyCompleted(attemptId)` stays in the apply's `finally`.
- (2026-10-26) The final re-run targeted vr-20261026-175959-4910ab7e and domain-n-z vr-20261026-180504-dd26d771 FAILED, on one test only: the new "onLockQuery" single-caller scan matched a KDoc reference in HeldDeepLink.kt. Comment reworded (the same approach used for releaseHeldLink); no code change. cancellation vr-20261026-180448-c053fdfe PASS.

### S17 — accepted (2026-10-26)
- Final tree (serial, PASS):
  - targeted vr-20261026-181956-021c6c6d
  - domain-n-z vr-20261026-182242-cc1edda6
  - cancellation vr-20261026-180448-c053fdfe (production unchanged since; the only later edit is a comment)
  - earlier on the post-review-fix tree: known_good_state vr-20261026-173012-d51ee97f, time_boundaries vr-20261026-173315-9ab2a6a9
- Gates:
  - privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS; architecture-guardian ACCEPT-WITH-CONDITIONS. Their shared HIGH F1 (release while backgrounded) was fixed.
  - reviewer-strict ACCEPT-WITH-CONDITIONS: issues 2 and 3 fixed; issues 1 and 4 recorded as BINDING for S20/S18.
  - Review iteration limit reached; accepted on validation.
- The controller is still DORMANT. Next: S18 (settings requests: T17–T19, T26–T29; binds startOpenOriginAttempt and onOpenOriginApplyCompleted).

## S18 part (i-a) — implemented

Status (2026-10-26, implementer): written, NOT validated (nothing was run). Still DORMANT (no UI caller of `AppLockSettingsOperations`). No schema/DB impact, no new Hilt module, no new user-visible string.
M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker.

Files:
- NEW M/domain/privacy/applock/AppLockSettingsOperations.kt: `@Singleton`, `@ApplicationScope` scope; `requestEnable()` (T26 capability check, then ENABLE), `requestDisable()`; the success handler; `SettingsRequestResult` (STARTED, ALREADY_IN_FLIGHT, NOT_IN_ORIGIN, REFUSED_UNAVAILABLE). The ONLY production caller of `startOpenOriginAttempt`, `onOpenOriginApplyCompleted` and the two new controller entries.
- M/domain/privacy/applock/AppLockController.kt: two new public entries (below); `routeNotAvailable` resolves its `TODO(S18)` (NO_CREDENTIAL and UNSUPPORTED_API are now `Closing.EnableRefused` with their own codes); `claimEnableRefusal` / `claimCodeCause` over `claimedCodeCauses`; `TODO(S18-i-b)` and `TODO(S18-ii)` left.
- M/domain/privacy/applock/PrivacyDiagnosticsEmitter.kt: entries `LOCK_UNAVAILABLE_NOT_SECURE`, `LOCK_UNAVAILABLE_API` (CAUSE only), `LOCK_ENABLED`, `LOCK_DISABLED` (no keys).
- M/domain/privacy/applock/AppLockSettingsRepository.kt: `AppLockSettingField.APP_LOCK_ENABLED`; `setAppLockEnabled(enabled)` (see DEVIATION 1).
- M/data/privacy/applock/AppLockSettingsRepositoryImpl.kt: `setAppLockEnabled` (one atomic DataStore edit, Unreadable gives `Rejected`, IO failure gives `Failed(APP_LOCK_ENABLED)`).
- Tests: T/domain/privacy/applock/AppLockControllerEnableDisableTest.kt (new; holds the shared `SettingsOperationsRig`), AppLockControllerOpenOriginSuccessTest.kt (new), AppLockControllerTestFixtures.kt (fake `setAppLockEnabled`: `lockWrites`, `lockWriteResult`, `lockWriteFailure`, `lockWriteGate`), AppLockControllerIllegalTransitionTest.kt (pins), PrivacyDiagnosticsEmitterTest.kt (new pin test), AppLockControllerPromptOutcomeTest.kt (the NO_CREDENTIAL / UNSUPPORTED_API ENABLE `NotAvailable` test now expects the new codes instead of `LOCK_AUTH_ERROR(OTHER)`).

Transitions:
- T26: `onEnableRefusedAtRequest(capability)`: from exactly DISABLED with no attempt; claims `LOCK_UNAVAILABLE_NOT_SECURE` (NO_CREDENTIAL), `LOCK_UNAVAILABLE_API` (UNSUPPORTED_API) or `LOCK_UNAVAILABLE_CAPABILITY` (HW_UNAVAILABLE, UNKNOWN; CHECK_FAILED as UNKNOWN) once per (code, cause); no state change.
- T17 / T18 (by T33): ONE new entry `onOpenOriginSettingApplied(prompt, committed)`, called after the durable write and before `onOpenOriginApplyCompleted`. Re-checks under `stateLock`. Committed: `loadState` takes the new flag, `LOCK_ENABLED` / `LOCK_DISABLED`, and the state moves only from exactly DISABLED to UNLOCKED (ENABLE) or UNLOCKED to DISABLED (DISABLE) with no open attempt. Not committed (Failed, Rejected or an exception): no state change, `LOCK_SETTING_PERSIST_FAILED(FIELD = APP_LOCK_ENABLED)`. `CHANGE` is a no-op (S18-i-b). The unlock-equivalent discard is NOT repeated (the close already did it).
- Apply order in `AppLockSettingsOperations`: handler (last statement: `scope.launch`) -> [enable: read effective discreet before] -> write (`NonCancellable`) -> `onOpenOriginSettingApplied` -> [enable and committed: `DiscreetRepostTrigger.onCommittedWrite(before, after)`, INV-13] -> `finally { onOpenOriginApplyCompleted(attemptId) }`. Cancellation is rethrown by the unwinding; write exceptions other than cancellation become a failed write (class name only in the log).

AEs covered by the new tests: AE-36, AE-37 (T26 refusals), AE-102 (lock-enable trigger args), AE-113, AE-114 (DISABLE copy, success, write failure, cancel/error/lockout), AE-117 (ENABLE variant, both callback orders; the M5 variant is S18-i-b), AE-116 cancel half for DISABLE (nothing written, UNLOCKED); T33 gate and link release (hold, none before completion, apply after, also after a failed write); cancelled write and cancelled scope; no content in diagnostics.

Decisions (please confirm):
- D1. T14 gap: a lock that lands between the hand-off and the write leaves the state LOCKED (never turned into DISABLED without an unlock); the committed flag is still recorded in the controller's view and `LOCK_DISABLED` is emitted. The state is then LOCKED with the lock off in storage until the next unlock/cold start. Residual: after that unlock the state is UNLOCKED with the lock off (no auto DISABLED).
- D2. The reverse gap for ENABLE (a background between the close and the T17 move, state DISABLED records no background) is not tracked: no pending background is set by T17. Residual (the window is the length of one DataStore write).
- D3. `LOCK_ENABLED`, `LOCK_DISABLED` and `LOCK_SETTING_PERSIST_FAILED` are events, not once-per-process claims (the emitter's 60 s per-code rate limit is the backstop). The T26 refusals ARE claimed once per (code, cause).
- D4. Two new public controller entries instead of one: the T26 claim lives in the controller because `claimedCodeCauses` is private. Pin changes: method set gains both; allowed parameter types gain `DeviceAuthCapability` and `Boolean`; the single-caller scan now allows exactly `AppLockSettingsOperations.kt` for `startOpenOriginAttempt`, `onOpenOriginApplyCompleted` and the two new names.
- D5. [SUPERSEDED by architecture finding 4 (ATOMIC start), see "S18 part (i-a) — architecture review" below and the final gate section.] The scope is launched with the default start: a scope that is already cancelled at the hand-off never runs the apply (so no completion; the controller's F8/`T_held` lapse covers the gate). The application scope is not cancelled in production.
- D6. `onLockQuery()` is not called before the start (S16 question (a)): S20 must deliver `APP_FOREGROUNDED` before the first tap. INV-45 for INERT stays S18-ii.

DEVIATIONS from the brief / plan (outside the controller + Operations write set):
1. The S14 repository had NO lock-flag write (only `setDiscreetNotifications` and `reviewSave`). Added `setAppLockEnabled` (interface with a fail-closed default `Failed(APP_LOCK_ENABLED)`, so the 5 other fakes still compile and can never report a commit; override in the Impl), plus the `APP_LOCK_ENABLED` field enum value. Needed tests for the Impl are NOT written (test-author).
2. BINDING S12 carry-forward not done: `AppLockSettingsWriteSerializer` (ui) is not moved to the domain owner and the ViewModel is not edited (UI edits are out of this brief). The lock write is a per-field atomic store edit, so the store stays consistent; only the discreet trigger's before/after read can race a concurrent discreet write (benign: the sweep is idempotent). Must be done before S18-ii (review-save) or S19/S20 wiring.
3. Existing test `AppLockControllerPromptOutcomeTest` (ENABLE NotAvailable with NO_CREDENTIAL / UNSUPPORTED_API) was changed to the new codes: it pinned the superseded `LOCK_AUTH_ERROR(OTHER)` behaviour.

Test-author to add: `AppLockSettingsRepositoryImplTest` cases for `setAppLockEnabled` (commit, Rejected on each Unreadable cause, IO failure Failed(APP_LOCK_ENABLED), sentinel untouched); the other fakes of the repository need no change (default method).

Validation to request (validation-runner, serial): `compile`; `targeted-unit-test` `*AppLockController*,*AppLockSettingsOperations*,*PrivacyDiagnosticsEmitter*,*DiscreetRepost*` (suggest adding `*AppLockSettingsRepositoryImpl*`); shard `domain-n-z` (and `data`, because of the Impl); `registered-guard` `cancellation`, `known_good_state`.
Likeliest findings: mockk of the final `DiscreetRepostTrigger`; the `when (val view = loadState)` with `is Unreadable, null`; a lambda ending in an assignment in the failure-map test; other existing tests that still expect `LOCK_AUTH_ERROR(OTHER)` for NO_CREDENTIAL / UNSUPPORTED_API on an ENABLE `NotAvailable`.

### S18 part (i-a) — privacy-review fixes (ACCEPT-WITH-CONDITIONS)

Status (2026-10-26, implementer): written, NOT validated (nothing was run). Still DORMANT. No schema impact. M = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Fixed in this pass:
- F1 (INV-13 regression): M/AppLockSettingsOperations.kt:155-169 (`readEffectiveDiscreetBeforeWrite`) now uses the same `when` as `UpdateDiscreetNotificationsUseCase.kt:63-67`: Loaded/FirstRunDefault give `effectiveDiscreet(before)`, `Unreadable` gives false (a thrown read was already false). Test: T/AppLockControllerEnableDisableTest.kt:367 (store Unreadable(IO_ERROR), lock write commits, trigger called with (false, true)).
- F2 (runtime binding of T17/T18): M/AppLockController.kt:1190-1192 (close of the OpenOriginSuccess records a one-shot token: attempt id + that attempt's `OpenOriginPrompt`, under `stateLock`; new fields next to `applyGateAttemptId`, which lapses; the token does not). `onOpenOriginSettingApplied(attemptId: Long, committed: Boolean)` at :657 derives ENABLE/DISABLE from the recorded prompt, is a no-op unless the id matches the unconsumed token, then consumes it. The token is also dropped by `onOpenOriginApplyCompleted(id)` and by the failed-hand-off `clearApplyGate(id)` (id-bound). `InFlightAttempt` gained a trailing defaulted `prompt` parameter. Operations passes the attempt id (M/AppLockSettingsOperations.kt:131). Pins (T/AppLockControllerIllegalTransitionTest.kt): `Boolean` removed from the global parameter allow-list and allowed ONLY for the method `onOpenOriginSettingApplied` (it is still needed there as `committed`, so it cannot be removed outright); the signature pin now asserts `(long, boolean) -> void` for that method; the source-scan exemptions for `AppLockController.kt` and `AppLockSettingsOperations.kt` are path-based (`invariantSeparatorsPath.endsWith("domain/privacy/applock/...kt")`). `Long` was already allowed. Test: AppLockControllerEnableDisableTest.kt:388 (no prior success, wrong id, reused id: no state change, no codes).
- F5: M/AppLockSettingsOperations.kt:90-146 (`applyRunning`, set before `scope.launch`, cleared in the inner `finally` after `onOpenOriginApplyCompleted`); `requestEnable` (:94, before the capability read) and `requestDisable` (:105) return `ALREADY_IN_FLIGHT` while set. Test: AppLockControllerEnableDisableTest.kt:422.
- F6: M/AppLockController.kt:711 (ENABLE move). DEVIATION from the brief (needed for AE-117): the condition is "the app was in the foreground when the prompt CLOSED (token field `pendingApplyForegroundAtClose`) and is not now", then `lastBackgroundedAtMillis = clock.elapsedRealtimeMillis()`. A plain "not in foreground at the move" would turn the AE-117 OEM-stop case (background during the prompt, discarded as unlock-equivalent) into a LOCKED(TIMEOUT) on the next foreground. Only set when the state really moved to UNLOCKED and no background is pending. Residual: a background AND foreground both inside the write window is not seen. Tests: AppLockControllerEnableDisableTest.kt:440 (measured, next foreground locks) and :459 (prompt background or none sets nothing; the two AE-117 tests in AppLockControllerOpenOriginSuccessTest.kt are unchanged).
- F8 (spec section 10 "once per process per cause"; supersedes D3 above): M/AppLockController.kt:716 `LOCK_ENABLED` / `LOCK_DISABLED` through `claim(code)` (`claimedDiagnosticCodes`); M/AppLockController.kt:685 `LOCK_SETTING_PERSIST_FAILED(FIELD)` through `claimedCodeCauses` keyed by (code, `AppLockSettingField`) (the set's cause type widened from `DeviceAuthCapability` to `Enum<*>`; `claimCodeCause` takes `Enum<*>`). Test: AppLockControllerEnableDisableTest.kt:479. Note the consequence: a second enable in the same process records no second `LOCK_ENABLED`.
- D2 above (no pending background set by T17) is superseded by F6; D4 above: the `Boolean` pin is now method-scoped.

BLOCKING for ANY wiring of `AppLockSettingsOperations` (S18-ii, S19, S20):
- F3: `AndroidNotificationService.kt:314` `decideDiscreet` must pass `actionsSuppressed` per the S18 / G1b binding (the committed lock state, `EffectivePrivacyValues.effectiveLockEnabled(<committed state>)`); otherwise a re-posted BILL on API 30 keeps its actions after the lock is enabled (see the S14 carry-forward).
- F4: move `AppLockSettingsWriteSerializer` from the ui package into the domain, and run the discreet pre-read, the write and the trigger call INSIDE it, shared with `AppLockPrivacySettingsViewModel`; OR have the repository return the pre-edit settings from the same atomic `store.edit`. This fixes (a) the stale-review-save fail-open (a review save built from a stale read can overwrite the lock flag) and (b) the discreet before/after race.
- Correction of DEVIATION 2 above: it is NOT "benign". The unshared serializer allows both the stale review-save overwrite of `appLockEnabled` (privacy fail-open) and a wrong before value for the INV-13 trigger. It is dormant only because nothing calls the Operations yet.

Carry-forward (record for G1b):
- F7: if the lock write throws `CancellationException` inside `NonCancellable`, the durable outcome is unknown (the edit may have committed). Re-read the store in that case and decide the state move, the trigger and the code from the re-read, instead of treating it as not committed. Today the apply ends cancelled, no code, no move, the gate closes. Not done here.
- [SUPERSEDED by architecture finding 4 (ATOMIC start)] D5 stays: a scope cancelled at the hand-off never runs the apply, so the `applyRunning` flag would stay set (the application scope is not cancelled in production). With ATOMIC the apply body and its finally always run, so the flag is cleared.

Test-author / validation: `AppLockControllerEnableDisableTest` gained 6 tests (F1, F2, F5, F6 x2, F8); `AppLockControllerIllegalTransitionTest` pins changed as above. Validation to request (serial): `compile`; `targeted-unit-test` `*AppLockController*,*AppLockSettingsOperations*,*PrivacyDiagnosticsEmitter*,*DiscreetRepost*`; shard `domain-n-z`; `registered-guard` `cancellation`, `known_good_state`.

Corrections to the S18 (i-a) entries above (2026-10-27):
- The signature of the state move is now `onOpenOriginSettingApplied(attemptId: Long, committed: Boolean)` (id-bound; the direction is derived from the prompt recorded for that id). Any earlier mention of a direction or a bare `committed` argument is superseded.
- The 16 `setAppLockEnabled` repository tests exist (`AppLockSettingsRepositoryImpl` coverage); the "needed tests for the Impl are NOT written" note under DEVIATION 1 is superseded.
- The token field `pendingApplyForegroundAtClose` is renamed `pendingApplyForegroundSinceClose` (F6 residual "a background AND foreground inside the write window is not seen" is closed by architecture finding 3 below).

### S18 part (i-a) — architecture review (ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27)

Status: fixes written by the implementer, NOT validated (nothing was run). Still DORMANT, now pinned by a source scan. No schema impact. M = app/src/main/java/com/yourname/expensetracker/domain/privacy/applock, T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Findings and what was done:
1. Docs: docs/architecture/LEGAL_PATHS.md gained "WRITE 3 — lock flag (S18 i-a, DORMANT)" (:1691; owner AppLockSettingsOperations; steps T26 -> prompt -> T33 token -> NonCancellable `setAppLockEnabled` -> `onOpenOriginSettingApplied(attemptId, committed)` -> `DiscreetRepostTrigger` on enable -> completion; "not legal to wire until F3, F4, F7 and findings 3/4/8 are closed"); the "only per-field writer" claim (:1654) and the SERIALISATION rule (:1716-1721) now name the dormant writer and the blocking condition. docs/architecture/CODEBASE_SEGMENTS.md lists the dormant writer in the app-lock segment.
2. Dormancy pin (test only): T/AppLockControllerIllegalTransitionTest.kt:680 allows ZERO production references to `AppLockSettingsOperations`, `requestEnable`, `requestDisable` outside AppLockSettingsOperations.kt (no DI module exists for it: `@Singleton @Inject constructor`). Comment lines (KDoc links) are skipped. Lifting it needs F3, F4, F7 and findings 3/4/8.
3. Fail-open window: M/AppLockController.kt:492 (`onAppForegrounded`, inside the synchronized block) sets `pendingApplyForegroundSinceClose = true` while a setting-move token is open; the field (:345, was `pendingApplyForegroundAtClose`) and the move parameter are renamed to "foregroundSinceClose" semantics (:671, :1200). Test: T/AppLockControllerEnableDisableTest.kt:479 (closed in the background, foreground then background during the write, the move lands: the pending background is recorded; the next foreground after the timeout locks).
4. M/AppLockSettingsOperations.kt:119 `startApply`: `scope.launch(start = CoroutineStart.ATOMIC)` inside `try { ... } catch (t: Throwable) { applyRunning.set(false); throw t }`; `CancellationException` is a Throwable and is rethrown unchanged.
5. Exact source-scan exemptions: T/AppLockControllerIllegalTransitionTest.kt:662 and :665 compare `file.relativeTo(root).invariantSeparatorsPath == "com/yourname/expensetracker/domain/privacy/applock/<File>.kt"` against the real scanned root (replaces the `endsWith` suffix match); same helper used by the dormancy pin (:691).
6. Tests: T/AppLockControllerEnableDisableTest.kt:502 (after `onOpenOriginApplyCompleted(id)` a late `onOpenOriginSettingApplied(id, true)` is a no-op) and :517 (after a throwing handler, `clearApplyGate`, the same call is a no-op).
7. Not itemised in the brief given to the implementer; no change made for it here (orchestrator to confirm what it covered).
8. `setAppLockEnabled` (M/AppLockSettingsRepository.kt:75) has a fail-closed DEFAULT body so the other fakes compile; it must become ABSTRACT once the fakes are updated, so a fake can never silently report a failed lock write. Not done here (BLOCKING before wiring).
9. This handoff: the corrections above (signature `(attemptId, committed)`; the 16 `setAppLockEnabled` repository tests exist).

Validation PASS run IDs on the PRE-FIX tree (these do NOT cover the fixes above; re-run on this tree): targeted vr-20261026-203021-d2c692c8; cancellation vr-20261026-203455-095ec8fc; known_good_state vr-20261026-203513-f92f0174; domain-n-z vr-20261026-203814-a889f29f.

BLOCKING before ANY wiring of `AppLockSettingsOperations` (S18-ii, S19, S20): F3 (`decideDiscreet` `actionsSuppressed`), F4 (domain-owned write serialiser, pre-read, write and trigger inside it), F7 (re-read the store after a `CancellationException` inside `NonCancellable`), and finding 8 (`setAppLockEnabled` abstract once the fakes are updated). Findings 3 and 4 above are fixed in this pass, pending validation.

Validation to request (serial): `compile`; `targeted-unit-test` `*AppLockController*,*AppLockSettingsOperations*`; shard `domain-n-z`; `registered-guard` `cancellation`, `known_good_state`.

### S18 part (i-a) — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes

Status (2026-10-27): fixes written (tests and docs only, NO production code), NOT validated (nothing was run). Still DORMANT. No schema impact. T = app/src/test/java/com/yourname/expensetracker/domain/privacy/applock.

Findings and what was done:
1. (NEW, wiring blocker) INV-45: the review record must start from F for every 1b field and go through the controller's auth/capability check (plan :1494) before any wiring. Today the 1a review card writes lock=false with no authentication (`AppLockPrivacySettingsViewModel.kt:195/:200`). Added to the BLOCKING list (docs and dormancy-pin comment); not fixed (S18-ii).
2. Pins (test only), T/AppLockControllerIllegalTransitionTest.kt: new `assertSingleProductionCaller` helper (exact relative path under every production root via `relativeToRoot`, comment lines skipped, and the allowed file must really contain the call). Two tests: the call site `.setAppLockEnabled(` is allowed only in `com/yourname/expensetracker/domain/privacy/applock/AppLockSettingsOperations.kt`; `.reviewSave(` is pinned to its single current caller `com/yourname/expensetracker/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt` (grep of main showed exactly these two call sites). Moving the reviewSave caller behind the controller (INV-45) changes the second pin deliberately.
3. Stale D5 text: D5 (:1326) and its carry-forward (:1358) are marked SUPERSEDED by architecture finding 4 (ATOMIC start: the apply body and its finally always run, so the flag clears).
4. Wiring conditions made identical in LEGAL_PATHS WRITE 3, CODEBASE_SEGMENTS.md:603 and the dormancy-pin comment, and the unshared serializer is now attributed only to F4 (not to architecture finding 8) in LEGAL_PATHS SERIALISATION. (Finding numbers 3 and 4 follow the order of the orchestrator brief: docs/handoff items.)
5. Test: T/AppLockControllerOpenOriginSuccessTest.kt `ATOMIC start - a scope cancelled right after the hand-off ...`: deferred rig, success delivered, `rig.job.cancel()` BEFORE `settle()`; asserts the write (`lockWrites == [true]`), the move to UNLOCKED, `LOCK_ENABLED`, no uncaught, and that `requestDisable()` is `STARTED` afterwards (completion and flag clear ran). Risk to check on first run: ATOMIC launch under a cancelled parent on the StandardTestDispatcher; if the test dispatcher refuses to dispatch for a cancelled job the assertion shows it (that would be a real finding).
6. Test: T/AppLockControllerEnableDisableTest.kt `a sweep failure after the commit does not undo the enable` now also asserts `rig.uncaught.single() is IllegalStateException` and that `requestDisable()` is `STARTED` (not ALREADY_IN_FLIGHT: completion and flag clear ran).

FINAL BLOCKING list before ANY wiring of `AppLockSettingsOperations` (S18-ii, S19, S20); supersedes the lists at :1351-1354 and :1384:
- F3: the whole plan :1490-1493 binding: remove the `decideDiscreet` default, pass `actionsSuppressed`, add the SDK <= 30 lock-on parity row, decide `NOTIF_ACTIONS_SUPPRESSED_LOCK`.
- F4: the domain write serializer shared with the ViewModel.
- F7: a store re-read after a cancelled NonCancellable write.
- Finding 8: `setAppLockEnabled` becomes abstract.
- INV-45 (final gate finding 1): the review record starts from F for every 1b field and goes through the controller's auth/capability check (plan :1494).

Residual notes (accepted, not fixed):
- F6 undercount: the foreground-since-close tracking can undercount in some interleavings (no false lock, only a possibly missed pending background); recorded as a residual of the F6 timer rule.
- `requestEnable` order is capability-first: the `applyRunning` check is first, then the capability read, so a refused enable (T26) is reported even while another prompt is open; the controller's own gate (INV-54) still decides the start.
- D1 residual: a lock that lands between the hand-off and the write leaves LOCKED with the lock off in storage until the next unlock or cold start; after that unlock the state is UNLOCKED with the lock off (no auto DISABLED).

Test-author: nothing further beyond the tests above. Validation to request (serial): `compile`; `targeted-unit-test` `*AppLockController*,*AppLockSettingsOperations*`; shard `domain-n-z`; `registered-guard` `cancellation`, `known_good_state`.

### S18 part (i-a) — accepted (2026-10-27)
- Final tree (serial, PASS): targeted vr-20261026-221202-74fde420. The edits after the production-code runs below were test and docs only.
- Production-code runs on the post-architecture-fix tree (PASS):
  - targeted vr-20261026-213800-235fec07
  - cancellation vr-20261026-214248-0afa451e
  - known_good_state vr-20261026-214307-db4654c0
  - domain-n-z vr-20261026-214608-fa231566
- Gates:
  - privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS, fixed;
  - architecture-guardian ACCEPT-WITH-CONDITIONS, fixed;
  - reviewer-strict ACCEPT-WITH-CONDITIONS, fixed (single-caller pins for setAppLockEnabled and reviewSave; INV-45 added to the BLOCKING list).
- DORMANT. BLOCKING before any wiring of AppLockSettingsOperations: F3, F4, F7, finding 8, INV-45 (see the final-gate section).
- Next: S18 part (i-b): timeout change, weakening (INV-3), TECH-13, forced values.

## S18 part (i-b1) — implemented (implementer; validation NOT run)
Scope: the auto-lock TIMEOUT CHANGE only. DORMANT (no production caller outside AppLockSettingsOperations.kt).
- Files (source):
  - `domain/privacy/applock/AppLockSettingsRepository.kt`: `AppLockSettingField.AUTO_LOCK_TIMEOUT`; `setAutoLockTimeout(AutoLockTimeout)` with a fail-closed default body `Failed(AUTO_LOCK_TIMEOUT)`.
  - `data/privacy/applock/AppLockSettingsRepositoryImpl.kt`: atomic per-field `setAutoLockTimeout` (one DataStore edit; Unreadable gives `Rejected(UNREADABLE)` and writes nothing; IO gives `Failed(AUTO_LOCK_TIMEOUT)` or Rejected if the store cannot be read).
  - `domain/privacy/applock/AppLockController.kt`: `startTimeoutChangeAttempt(AutoLockTimeout, OpenOriginSuccessHandler)` (CHANGE copy, delegates to a private start shared with `startOpenOriginAttempt`, whose public signature is unchanged); `onTimeoutWrittenWithoutPrompt(AutoLockTimeout, Boolean)`; the one-shot token now also carries the requested timeout (`pendingApplyTimeout`, set at the close, cleared with the token); `onOpenOriginSettingApplied` applies the TOKEN's timeout for CHANGE (committed: settings view; not committed: `LOCK_SETTING_PERSIST_FAILED(FIELD=AUTO_LOCK_TIMEOUT)` once per (code, field)); the state never moves. TODO(S18-i-b) became TODO(S18-i-b2).
  - `domain/privacy/applock/AppLockSettingsOperations.kt`: `suspend requestTimeoutChange(AutoLockTimeout): TimeoutChangeResult` (COMMITTED, FAILED, STARTED, ALREADY_IN_FLIGHT, NOT_IN_ORIGIN).
- Behaviour:
  - Equal or shorter, or the lock is off: direct NonCancellable write, no prompt, then the controller view entry.
  - Longer with the lock on: CONFIRM_CHANGE with the CHANGE copy; the success handler's last statement launches the ATOMIC apply (NonCancellable write, id-bound `onOpenOriginSettingApplied`, then completion in `finally` and `applyRunning` clear).
  - An unreadable store or a throwing pre-read: FAILED, nothing written, no prompt, recorded.
  - A longer timeout while INERT: NOT_IN_ORIGIN, no write, no prompt (fail closed). TECH-13's `Rejected(AUTH_UNAVAILABLE)` is S18-i-b2.
  - CancellationException is never swallowed.
- Files (tests):
  - `AppLockControllerIllegalTransitionTest.kt`: pins (method set +`startTimeoutChangeAttempt`, `onTimeoutWrittenWithoutPrompt`; parameter allow-list +`AutoLockTimeout`, Boolean allowed for the two committed-flag methods; both new methods single-caller in AppLockSettingsOperations.kt; new `.setAutoLockTimeout(` single-caller pin; dormancy list +`requestTimeoutChange`; signature pins).
  - `AppLockControllerTestFixtures.kt`: fake repository `setAutoLockTimeout` (`timeoutWrites`, `timeoutWriteResult`, `timeoutWriteFailure`).
  - `AppLockSettingsOperationsTimeoutTest.kt` (new).
  - `AppLockSettingsRepositoryImplTest.kt`: 13 `setAutoLockTimeout_*` tests.
- AEs: AE-116 (a cancelled request for M5 keeps M1), INV-3 / OD-8a, INV-44, INV-46, INV-54 (own row only); AE-114 pattern for the write failure.
- Validation to request (serial, validation-runner):
  - targeted-unit-test `*AppLockController*,*AppLockSettingsOperations*,*AppLockSettingsRepositoryImpl*`;
  - shards domain-n-z and data;
  - registered-guard cancellation;
  - known_good_state.
- Not done (left): TODO(S18-i-b2) weakening writes, TECH-13, forced values; TODO(S18-ii) review-save, re-read, notices. Gates (privacy-security-guardian, reviewer-strict) still to run for i-b1.
- (2026-10-27) The S18 (i-b1) targeted run vr-20261026-231624-ce9063e1 and the shards domain-n-z/data failed with a TEST compile error: a duplicate AutoLockTimeout import and a duplicate `timeoutKey` declaration in AppLockSettingsRepositoryImplTest (the implementer flagged this as a risk). Both duplicates are removed (test-only). Guards cancellation vr-20261026-232012-45504b19 and known_good_state vr-20261026-232031-78ce6750 PASS.

### S18 part (i-b1) — privacy-review fixes (ACCEPT-WITH-CONDITIONS)
Implementer pass; validation NOT run. Paths: M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker.
1. Finding 1 (MEDIUM, the no-prompt check and write were not atomic): fixed with the stronger option.
   - M/domain/privacy/applock/AppLockSettingsRepository.kt:26 new bounded reason `AppLockWriteRejection.WOULD_WEAKEN`; :112 new `setAutoLockTimeoutIfNotWeakening(AutoLockTimeout)` with a fail-closed default body `Failed(AUTO_LOCK_TIMEOUT)`.
   - M/data/privacy/applock/AppLockSettingsRepositoryImpl.kt:153-186: ONE `store.edit`: Unreadable gives `Rejected(UNREADABLE)`; lock on and `timeout.millis > stored.millis` gives `Rejected(WOULD_WEAKEN)` and writes nothing; else the write. An invalid stored timeout reads as IMMEDIATE (fail closed). IO and cancellation as the other per-field writes (cancellation propagates).
   - M/domain/privacy/applock/AppLockSettingsOperations.kt:123 `directWriteMutex`; :164 the pre-read and the direct write are one `withLock` section, `applyRunning` re-checked inside; :197 the direct write uses `writeTimeout(timeout, conditional = true)`, `WOULD_WEAKEN` answers NOT_IN_ORIGIN (no view change, no record); :231 the prompt-path apply keeps the unconditional write (`conditional = false`). The prompt itself is started outside the mutex.
   - Pin: T/domain/privacy/applock/AppLockControllerIllegalTransitionTest.kt (new test after the `.setAutoLockTimeout(` pin) allows exactly one production call site of `.setAutoLockTimeoutIfNotWeakening(`, AppLockSettingsOperations.kt (exact addition; the existing pins are unchanged).
   - Tests: T/data/privacy/applock/AppLockSettingsRepositoryImplTest.kt `conditionalTimeout_*` (9: rejected lengthening with lock on, allowed shortening/equal, allowed with lock off, first run, invalid stored timeout, sentinel, IO read failure, IO write failure, cancellation); T/domain/privacy/applock/AppLockSettingsOperationsTimeoutTest.kt `F1 ...` (conditional vs unconditional use, stale-pre-read answer, two overlapping direct requests, a longer request after an overlapping shortening); fixture T/domain/privacy/applock/AppLockControllerTestFixtures.kt `setAutoLockTimeoutIfNotWeakening` (`conditionalTimeoutWrites`, `conditionalWriteGate`).
2. Finding 2 (LOW-MEDIUM): M/domain/privacy/applock/AppLockController.kt:717-731 `onTimeoutWrittenWithoutPrompt(timeout, committed = true)` leaves the view unchanged when the effective view has the lock on and the timeout is longer than the view's (fail closed). Tests: AppLockSettingsOperationsTimeoutTest.kt `F2 ...` (longer left unchanged, shorter still applied).
3. Tests added in AppLockSettingsOperationsTimeoutTest.kt: 8a (INERT: longer NOT_IN_ORIGIN, no write, no prompt; shorter COMMITTED), 8c (shorten to IMMEDIATE, background and foreground within 0 and 1 ms gives LOCKED(TIMEOUT)), 8d (AE-117 M5 variant, IMMEDIATE stored, both callback orders, M5 committed, UNLOCKED, no lock), 8e (an invalid stored timeout reads IMMEDIATE and forces the prompt), 8f (first-run store commits directly), 8g (unreadable path metadata is FIELD=AUTO_LOCK_TIMEOUT).
4. Findings 3, 4, 5 (docs): LEGAL_PATHS.md new "WRITE 4 — auto-lock timeout (S18 i-b1, DORMANT)" next to WRITE 3 (owner, steps, conditional write, blocking conditions), the WRITE 1 header no longer calls WRITE 3 the only dormant per-field writer, the SERIALISATION paragraph covers WRITE 4; CODEBASE_SEGMENTS.md:603 mentions the timeout writer.
5. BLOCKING before ANY wiring of `AppLockSettingsOperations` (S18-ii, S19, S20); supersedes the FINAL list at :1400-1405 (same in LEGAL_PATHS WRITE 3 and CODEBASE_SEGMENTS.md:603):
   - F3: the whole plan :1490-1493 binding (remove the `decideDiscreet` default, pass `actionsSuppressed`, SDK <= 30 lock-on parity row, decide `NOTIF_ACTIONS_SUPPRESSED_LOCK`).
   - F4: the domain write serializer shared with the ViewModel; it also covers the timeout writes (both paths).
   - F7: a store re-read after a cancelled NonCancellable write, for the lock write AND both timeout paths (no-prompt and prompt-apply); the controller view is updated from the re-read.
   - Finding 8: `setAppLockEnabled`, `setAutoLockTimeout` and `setAutoLockTimeoutIfNotWeakening` become abstract (default bodies removed, fakes updated).
   - INV-45: the review record starts from F for every 1b field, the timeout included (IMMEDIATE), and goes through the controller's auth/capability check (plan :1494).
6. Behaviour note: a conditional write rejected as WOULD_WEAKEN is reported as NOT_IN_ORIGIN (retry takes the prompt path); it records nothing. A cancellation inside the direct NonCancellable write still leaves the durable outcome unknown (F7, not done).
7. Validation to request (serial): `compile`; targeted-unit-test `*AppLockController*,*AppLockSettingsOperations*,*AppLockSettingsRepositoryImpl*`; shards domain-n-z and data; registered-guard cancellation; known_good_state.
- (2026-10-27) Post-fix targeted vr-20261026-235057-d07beefc and domain-n-z vr-20261026-235951-5c26bc1d FAILED on three AppLockSettingsOperationsTimeoutTest tests only.
  - Root cause (debugger): unlockedRig never cleared the rig's own unlock prompt from authenticator.calls, so the `calls.isEmpty()` assertions saw one stale call.
  - Fix (test-only): clear the calls in unlockedRig; the two `calls.size == 1` assertions become `isEmpty()`.
  - Unchanged PASS runs: cancellation vr-20261026-235616-35f3696f, known_good_state vr-20261026-235634-bdf22def, data vr-20261027-000137-2a523052.

### S18 part (i-b1) — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes
Implementer pass; validation NOT run. M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker.
1. ISSUE-1 (a direct write could interleave with a CONFIRM_CHANGE prompt or its apply, so store and view could diverge): fixed.
   - M/domain/privacy/applock/AppLockSettingsOperations.kt:171 inside the `directWriteMutex.withLock` block (:165) the direct path answers ALREADY_IN_FLIGHT while `isConfirmChangeOpen()` (:256: the controller state is `Authenticating` with purpose CONFIRM_CHANGE), in addition to `applyRunning` (:170). Chosen over an `AtomicBoolean timeoutChangePending`: the controller has no "prompt ended without success" hook, so a flag would stick after a cancel or error; the state cannot stick.
   - M/domain/privacy/applock/AppLockSettingsOperations.kt:239 `runTimeoutApply` takes `directWriteMutex` around the unconditional write plus `onOpenOriginSettingApplied` (inside the existing NonCancellable block; no prompt is held under it). The short window between the controller's close (state back on UNLOCKED) and the handler setting `applyRunning` is therefore harmless: a direct write that slipped in finishes, view included, before the apply writes.
   - Test: T/domain/privacy/applock/AppLockSettingsOperationsTimeoutTest.kt:271 (open prompt: ALREADY_IN_FLIGHT, no write, store and view still M5 after cancel, then a direct request works again) and :293 (during the apply: ALREADY_IN_FLIGHT, store and view both M15 afterwards).
   - Not tested (residual): the close-to-handler window itself (a direct request between the controller's close and `applyRunning.set(true)`); it needs a hook that parks the handler.
2. ISSUE-2 (test rig): T/domain/privacy/applock/AppLockSettingsOperationsTimeoutTest.kt:30 `unlockedRig(timeout)` now calls `unlockedControllerFor(timeout)` (the fixture already took a timeout), so the controller view matches the store. Before it, the view was always M1, which made the "view still holds M5" assertions vacuous. The equal-timeout test (:109 area) now asserts an equal M5 request keeps a view that does not lock at 61 s.
3. ISSUE-3 (comments only): AppLockControllerIllegalTransitionTest.kt dormancy pin comment (~:714) now points to "LEGAL_PATHS WRITE 3/4 and handoff S18 (i-b1) item 5" instead of an inline list (no pin changed); the AppLockSettingsOperations class KDoc (:61-64) now lists `startTimeoutChangeAttempt` and `onTimeoutWrittenWithoutPrompt`.
4. Validation to request (serial): `compile`; targeted-unit-test `*AppLockSettingsOperations*,*AppLockController*`; shard domain-n-z; registered-guard cancellation; known_good_state.
5. OPEN QUESTION for S23 (diagnostics): is a success diagnostic defined for a committed timeout change (direct or prompt path)? Today a commit records nothing (only LOCK_SETTING_PERSIST_FAILED(AUTO_LOCK_TIMEOUT) on failure; LOCK_ENABLED and LOCK_DISABLED exist for the lock flag). Decide whether S23 adds a bounded code or leaves it silent.

### S18 part (i-b1) — accepted (2026-10-27)
- Final tree (serial, all PASS):
  - targeted vr-20261027-004839-fa8250cc
  - cancellation vr-20261027-005328-c0955671
  - known_good_state vr-20261027-005346-44ab0a9b
  - domain-n-z vr-20261027-005702-f3142193
  - Earlier: data vr-20261027-000137-2a523052. The repository Impl has not changed since that run.
- Gates:
  - privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS, fixed: atomic WOULD_WEAKEN conditional write, directWriteMutex, fail-closed view.
  - reviewer-strict ACCEPT-WITH-CONDITIONS, fixed: the direct path refuses while CONFIRM_CHANGE is open; the apply runs under the mutex; the rig view matches the store.
  - Review iteration limit reached; accepted on validation.
- DORMANT. The BLOCKING-before-wiring list is in LEGAL_PATHS WRITE 3/4 and the handoff.
- Next: S18 part (i-b2): weakening (INV-3/OD-8a) beyond the timeout, TECH-13 INERT, forced values. Then part (ii).

## S18 part (i-b2) — implemented
Implementer pass; validation NOT run (nothing was run). DORMANT. No schema/DB impact, no new Hilt module, no new string. M = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker.

Decisions (please confirm):
- D1. The spec's weakening set while the lock is on is: disable (done, i-a), a longer timeout (done, i-b1), "turning off an UNFORCED discreet or secure-screen setting", and mode switch (Inc 2). Under OD-2 (a) / INV-48 discreet and secure are FORCED while the lock is on, so the "unforced" set is empty: they are refused (`Rejected(FORCED)`, AE-103: no write, no prompt), and with the lock off they are not a weakening (INV-3 covers only an enabled lock). So NO new CONFIRM_CHANGE request type exists in i-b2; the only prompt-guarded weakenings remain disable and the longer timeout. Consequence: no new token/apply machinery.
- D2. Two request functions (smaller than one generic type with an enum guard): `requestDiscreetChange(Boolean)` and `requestSecureScreenChange(Boolean)` returning the new bounded `PlainSettingResult` (COMMITTED, FAILED, REJECTED_FORCED, ALREADY_IN_FLIGHT), sharing one private `writePlainSetting`. Pre-read (discreet only), write and trigger run under the existing `directWriteMutex`; `applyRunning` is honoured.
- D3. TECH-13: reuse of the existing `TimeoutChangeResult.NOT_IN_ORIGIN` for the spec's `Rejected(AUTH_UNAVAILABLE)` (per the brief). The INERT refusal for a longer timeout was already behaviourally in place from i-b1 (TODO comments resolved, tests added). Equal or shorter timeouts commit in INERT. Disable in INERT: `requestDisable()` stays NOT_IN_ORIGIN (no write, no prompt) — OPEN QUESTION for the orchestrator: spec T19 says an INERT DISABLE_REQUESTED writes lock=false WITHOUT auth and moves to DISABLED (INV-45: "needs none in INERT, as T19"), which conflicts with the brief's "every weakening is refused in INERT". T19 is NOT implemented here; the dormant refusal is fail-closed. Decide for S18-ii or a follow-up.
- D4. "Forced values" are implemented as INV-48 defines them: write-rejection while the STORED lock flag is on (so INERT and API-unsupported states are covered), decided inside ONE store edit (a stale pre-read cannot write a forced value). The derived effective values (`EffectivePrivacyValues`) already apply the stored value again when forcing ends (T18/T19/T30); nothing else was needed. Nothing is "forced" by capability loss beyond that: the forcing term reads the stored flag, not the lock state.
- D5. No diagnostic for REJECTED_FORCED (INV-48: no auth, nothing written; a rejection is a caller-visible result, not an event). Failures record `LOCK_SETTING_PERSIST_FAILED(FIELD = DISCREET | SECURE_SCREEN)` once per (code, field) through the controller (`claimPersistFailedLocked`, generalised from the timeout claim).
- D6. The 1a path (`UpdateDiscreetNotificationsUseCase` -> `setDiscreetNotifications`, used by the 1a ViewModel) has NO FORCED check and is untouched (UI/1a write set). It must be routed through WRITE 5 before wiring (added to the BLOCKING list).

Files (source):
- M/domain/privacy/applock/AppLockSettingsRepository.kt: `AppLockSettingField.SECURE_SCREEN`; `AppLockWriteRejection.FORCED`; `setDiscreetNotificationsIfNotForced(Boolean)` and `setSecureScreenIfNotForced(Boolean)` with fail-closed default bodies (`Failed(DISCREET)` / `Failed(SECURE_SCREEN)`).
- M/data/privacy/applock/AppLockSettingsRepositoryImpl.kt: both overrides via one private `setBooleanIfNotForced` (ONE store edit: Unreadable -> Rejected(UNREADABLE), stored lock on -> Rejected(FORCED), else write + marker NORMAL; IO as the other per-field writes; cancellation propagates).
- M/domain/privacy/applock/AppLockController.kt: new public entry `onPlainSettingWritten(AppLockSettingField, Boolean value, Boolean committed)` (view update on commit; failure record once per (code, field); other fields ignored; state never changes); `claimPersistFailedLocked(field)`; TODO(S18-i-b2) comments resolved; TODO(S18-ii) kept.
- M/domain/privacy/applock/AppLockSettingsOperations.kt: `PlainSettingResult`; `requestDiscreetChange`, `requestSecureScreenChange`; KDoc/TODO updates for TECH-13.
Files (tests):
- T/domain/privacy/applock/AppLockControllerInertWeakeningTest.kt (new), AppLockControllerForcedValueTest.kt (new); fixture `FakeAppLockSettingsRepository` gained `plainWrites`, `plainWriteResult`, `plainWriteFailure`, `plainWriteGate` and the two overrides (default applies the store's rules).
- T/data/privacy/applock/AppLockSettingsRepositoryImplTest.kt: 7 `forcedAware_*` tests (both fields: FORCED with either value and nothing written, lock-off commit, first run, sentinel, IO read, IO write, cancellation).
- T/domain/privacy/applock/AppLockControllerIllegalTransitionTest.kt: pins: method set +`onPlainSettingWritten`; parameter allow-list +`AppLockSettingField`, Boolean allowed for the third method; exact signature `(AppLockSettingField, boolean, boolean) -> void`; single-caller scan (Operations only); dormancy list +`requestDiscreetChange`, `requestSecureScreenChange`; two new single-call-site pins for `.setDiscreetNotificationsIfNotForced(` and `.setSecureScreenIfNotForced(`.
Docs: docs/architecture/LEGAL_PATHS.md: new "WRITE 5" block, WRITE 4 step 3 updated for TECH-13.

AEs: AE-103 (forced read-only, both values, no prompt; stored value applies after T18), AE-105 pattern (plain write failure: committed state only, record once per field), AE-36/AE-37/AE-70 unchanged (i-a), INV-3/OD-8a/INV-23/INV-48/TECH-13 as above. NOT covered here (S18-ii, by design): AE-85, AE-86, AE-87, AE-89 (review-save), AE-97, AE-98 (re-read), AE-99, AE-101 (notices); AE-93 is i-a; AE-115 derivation is S18-ii.
Races covered: a lock committed OUTSIDE the ops mutex (e.g. a review-save) while a lock-off discreet write is pending makes that write Rejected(FORCED) (the ops handling of a FORCED decided at write time; the ops-level enable-apply interleaving is excluded by `directWriteMutex`, pinned by the C1 test; store-level atomicity is `forcedAware_check_reads_inside_the_same_edit_as_a_concurrent_lock_commit`); a plain write during a lock apply is ALREADY_IN_FLIGHT; an unreadable store is FAILED fail-closed; CancellationException from the write/pre-read propagates, is not recorded, releases the mutex.

BLOCKING before ANY wiring (supersedes the i-b1 list; same in LEGAL_PATHS): F3, F4 (the serializer must also cover WRITE 5), F7 (re-read after a cancelled NonCancellable write, now also WRITE 5), finding 8 (`setAppLockEnabled`, `setAutoLockTimeout`, `setAutoLockTimeoutIfNotWeakening`, `setDiscreetNotificationsIfNotForced`, `setSecureScreenIfNotForced` become abstract), INV-45, and NEW: the settings ViewModel's discreet and secure-screen writes go through WRITE 5 (the 1a `setDiscreetNotifications` has no FORCED check).
Left: TODO(S18-ii) review-save (T27, INV-45), re-read (T28, T29), notices (INV-51); T19 (INERT disable, see D3).

Validation to request (serial, validation-runner): `compile`; targeted-unit-test `*AppLockController*,*AppLockSettingsOperations*,*AppLockSettingsRepositoryImpl*`; shards domain-n-z and data; registered-guard `cancellation`; `known_good_state`.
Likeliest findings: the INERT rig with a stored IMMEDIATE timeout is not used (foreground ordering untested); `onPlainSettingWritten` smart-cast on `loadState`; the Impl test `forcedAware_from_first_run` assumes an empty store; mockk trigger arguments in the race test.

### S18 part (i-b2) — privacy-review fixes (ACCEPT-WITH-CONDITIONS)
Applied by the implementer; validation NOT run for this pass (nothing was run). DORMANT. No schema impact.
- C1 (Medium): `runApply`'s `withContext(NonCancellable)` body (enable/disable pre-read, write, controller move, trigger) now runs inside `directWriteMutex.withLock`, as `runTimeoutApply` does. No prompt is held under it. New test (AppLockControllerForcedValueTest): a plain discreet-off write pending at `plainWriteGate` holds the mutex; the enable prompt succeeds; the apply writes nothing until the gate opens, then pre-reads the committed value and the trigger gets (false, true), never (true, true).
- C2 (doc): LEGAL_PATHS WRITE 3 BLOCKING list and its source-scan sentence now carry the WRITE 5 items (F4 and F7 cover WRITE 5; finding 8 covers the two IfNotForced methods; the 1a ViewModel discreet / secure-screen routing through WRITE 5; scan pins for requestDiscreetChange / requestSecureScreenChange, the two IfNotForced call sites and the single 1a `setDiscreetNotifications`); WRITE 5 gets a step 5 for the shared mutex. CORRECTION: the S18 i-b2 entry above says the BLOCKING list is "same in LEGAL_PATHS". That was false when written (LEGAL_PATHS WRITE 3 lacked the WRITE 5 items; only WRITE 5 carried the routing note). It is true now.
- F3 (pin): AppLockControllerIllegalTransitionTest gains `assertSingleProductionCaller(".setDiscreetNotifications(", ".../UpdateDiscreetNotificationsUseCase.kt")` (exact addition). There is NO unforced `.setSecureScreen(` call site in production (grep: only the IfNotForced variant), so no second pin was added.
- F4 (doc): KDoc on `AppLockController.onPlainSettingWritten`: the view's discreet and secure-screen fields are bookkeeping only; effective values OR in the lock flag.

BINDING conditions for S18-ii T19 (INERT disable without auth; resolves D3):
- (a) Re-read the capability FRESH at request time. Allow a no-auth T19 only if the device still cannot authenticate; otherwise use the DISABLE prompt, or NOT_IN_ORIGIN when not in the origin state.
- (b) The apply is id-bound, with an under-monitor re-check that the state is still INERT, honouring `applyRunning` and the mutex.
- (c) Commit before effect: DISABLED only after `Committed`. A failed write stays INERT and records `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)` once. Success emits `LOCK_DISABLED`.
- (d) `SETTINGS_UNREADABLE_NO_AUTH` does not bypass INV-44 and goes through review-save (INV-45).
- (e) Only an explicit user settings action: no deep link, no background caller. The dormancy pin is kept.
- (f) Disable never re-posts; the F7 re-read after a cancelled write applies (CancellationException is never swallowed).

Validation to request (serial, validation-runner): `compile`; targeted-unit-test `*AppLockControllerForcedValueTest*,*AppLockControllerIllegalTransitionTest*,*AppLockControllerEnableDisableTest*,*AppLockSettingsOperations*`; then shard domain-n-z; registered-guard `cancellation`.
Likeliest findings: the C1 test relies on the unconfined test dispatcher resuming the queued apply after the mutex unlock (`h.settle()` follows `pending.await()`); the apply is blocked at the mutex with `applyRunning` true, so a later plain request answers ALREADY_IN_FLIGHT.
- (2026-10-27) The revalidation hung: targeted vr-20261027-021120-44a658bd and domain-n-z vr-20261027-022626-3997cc37 both E_NO_OUTPUT_TIMEOUT.
  - Root cause (debugger): a TEST pinned the pre-C1 interleaving. The enable apply committing while a plain write held the mutex can no longer happen, so the assertion failed inside runBlocking with the gate never completed, and the test hung. This is not a production deadlock, because nothing re-enters directWriteMutex.
  - Fix (test-only): the race test now commits the lock outside the ops mutex (simulating a review-save) and keeps every assertion, with the gate completed in a finally so it fails fast.
  - cancellation guard vr-20261027-022610-e2ca5285 PASS.

### S18 part (i-b2) — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes
Applied by the test author (tests, one comment-only KDoc edit, docs); NO production logic changed; validation NOT run for this pass. DORMANT. No schema impact.
- ISSUE-1 (test): the C1 test in AppLockControllerForcedValueTest now wraps its body in `try { ... } finally { gate.complete(Unit) }`, like the race test, so a regression fails fast and never hangs `runBlocking`.
- ISSUE-2 (test): the rewritten race test now requests `requestDiscreetChange(true)` over the stored false (so a leaked write is detectable), asserts REJECTED_FORCED, the stored discreet is still false, `h.repository.loadState == AppLockTestSettings.enabled()`, `triggerCalls.isEmpty()` and no prompt (setup prompts cleared from `authenticator.calls`). Its comment states it proves the ops handling of a FORCED decided at write time; store-level atomicity is ISSUE-3.
- ISSUE-3 (test): AppLockSettingsRepositoryImplTest `forcedAware_check_reads_inside_the_same_edit_as_a_concurrent_lock_commit` (both fields). A `GatedStore` wrapper over the REAL file-backed DataStore holds the first edit (`setAppLockEnabled(true)`) open inside its transform; the forced-aware write (false over a stored true) is issued and queues behind it; after the gate opens it must be Rejected(FORCED) with its key unchanged and the lock key on. A pre-read check (reads are not blocked by a held edit) would see the lock off and write, so the test fails for that regression. Awaits are bounded by `withTimeout` and the gate is completed in a `finally`.
- ISSUE-4 (doc): LEGAL_PATHS WRITE 3 BLOCKING "WRITE 5 routing" item and the WRITE 5 block now state the EXIT CRITERION: before wiring, either retire `UpdateDiscreetNotificationsUseCase` -> `setDiscreetNotifications` (and flip the F3 pin to zero production call sites), or make `setDiscreetNotifications` forced-aware. Routing the ViewModel alone is insufficient. THIS MUST BE CLOSED BEFORE ANY WIRING.
- ISSUE-5 (doc): the `directWriteMutex` KDoc now says it serialises all four ops write paths (lock apply, timeout apply, direct timeout write, plain discreet/secure writes); the "Races covered" line above describes the rewritten race test.

Validation to request (serial, validation-runner): targeted-unit-test `*AppLockControllerForcedValueTest*,*AppLockSettingsRepositoryImplTest*`; then the batch with `*AppLockControllerIllegalTransitionTest*,*AppLockSettingsOperations*` and shard domain-n-z if the batch is combined.
- S18 part (i-b2) ACCEPTED (2026-10-27).
  - Final targeted vr-20261027-033521-f845b9ca PASS. The tests and docs changed after the production runs, so only the affected classes were re-run.
  - Production-tree runs: targeted vr-20261027-031244-bf7caa60, domain-n-z vr-20261027-031500-f262e54e, cancellation vr-20261027-022610-e2ca5285.
  - Gates:
    - privacy-security-guardian ACCEPT-WITH-CONDITIONS: C1 race fix, C2 BLOCKING list, F3 pin, F4 KDoc, all applied.
    - reviewer-strict ACCEPT-WITH-CONDITIONS: ISSUE-1..5 applied. ISSUE-4 (retire or make forced-aware `UpdateDiscreetNotificationsUseCase` → `setDiscreetNotifications`) must be closed BEFORE any wiring.
  - Next: S18-ii, which is review-save, re-read, notices, and T19 under the binding conditions (a)–(f).
