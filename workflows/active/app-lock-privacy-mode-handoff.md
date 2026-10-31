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

## S18-ii-a — implemented (2026-10-27; NOT validated, nothing was run)

Scope: domain serializer (F4) and cancelled-write re-read (F7). Lane A sub-slice a. Dormancy holds (no new production caller of Operations).

Files (M = main, T = test, relative to `app/src/`):
- M/domain/privacy/applock/AppLockSettingsWriteSerializer.kt: NEW (moved; same API `withExclusiveWrite`, `@Singleton @Inject constructor()`, no Hilt module). KDoc: non-reentrant, no prompt inside.
- M/ui/screens/privacysettings/AppLockSettingsWriteSerializer.kt: emptied to a zero-byte file by the implementer (no delete tool). THE ORCHESTRATOR MUST `git rm` IT. The ViewModel imports the domain class explicitly.
- M/domain/privacy/applock/AppLockSettingsOperations.kt: injects the serializer (constructor order: controller, repository, capability, trigger, SERIALIZER, scope); `directWriteMutex` and the Mutex imports removed; all four paths run in `writeSerializer.withExclusiveWrite` (runApply, runTimeoutApply, direct timeout write inside requestTimeoutChange, writePlainSetting). F7 helper `settleCancelledWrite` + `rereadAfterCancelledWrite` + `WriteOutcome.applyThenRethrow`.
- M/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt: import + KDoc only.
- M/domain/privacy/applock/AppLockController.kt: comments only (the two stale `TODO(S18-i-b2)` labels, now `TODO(S18-ii)` and `TODO(S18-ii-d)`).
- T/domain/privacy/applock/AppLockSettingsWriteSerializerSharingTest.kt: NEW. T/domain/privacy/applock/AppLockSettingsOperationsCancelledWriteTest.kt: NEW.
- T/domain/privacy/applock/AppLockControllerTestFixtures.kt: `SettingsOperationsRig` MOVED here from AppLockControllerEnableDisableTest.kt (now the one construction site) with a `serializer` parameter (default a fresh instance).
- T EnableDisableTest (rig and unused imports removed), ForcedValueTest, OpenOriginSuccessTest, SettingsOperationsTimeoutTest (see decision 4), T/ui/.../AppLockPrivacySettingsViewModelTest.kt (import).
- Docs: LEGAL_PATHS (SERIALISATION domain-owned; WRITE 1/5 wording; F4 and F7 struck as CLOSED in the WRITE 3 BLOCKING list), CODEBASE_SEGMENTS, CODEBASE_INVENTORY, hilt-bindings-map, ENGINE_INTERACTION_MAP.

Decisions:
1. (Corrected in the guardian fixes.) The plain writes and the direct timeout write run `serializer { NonCancellable { ... } }`: the lock wait is cancellable, which is safe because nothing is written or moved before the lock is held (a cancelled waiter just leaves). The two applies (lock apply, prompt timeout apply) keep `NonCancellable { serializer { ... } }`, so an apply that started always runs to its completion `finally`. The earlier text that claimed `NonCancellable { serializer }` for the plain writes was wrong.
2. F7 evidence rule: only a `Loaded` re-read whose field equals the request counts as committed. `FirstRunDefault` (nothing ever stored), `Unreadable`, another value, a throwing re-read, or a cancellation inside the re-read all count as NOT committed (`Failed(field)`: no move, the existing claim-once PERSIST_FAILED, no trigger). The held CancellationException is rethrown AFTER the outcome was applied (`applyThenRethrow`, in a `finally`, so the cancellation wins even if the trigger throws). The completion `finally` (INV-54) is untouched.
3. Plain writes: (superseded by the guardian fixes, privacy C2) a cancelled write whose re-read shows the stored lock ON gives `Rejected(FORCED)` (REJECTED_FORCED semantics) for an equal AND a different value, exactly the uncancelled answer: no move, no trigger, no failure record; then the cancellation is rethrown. Timeout (WOULD_WEAKEN case): a cancelled conditional timeout write whose re-read shows the lock on and a stored timeout shorter than requested is `Failed(AUTO_LOCK_TIMEOUT)` (recorded once), where the uncancelled path would answer WOULD_WEAKEN (NOT_IN_ORIGIN, no record). The caller sees only the cancellation, so no result differs; the one record is the conservative choice (a retry takes the prompt path). Only a `Loaded` re-read equal to the request counts as committed.
4. Four existing tests asserted "a cancellation records NOTHING" (OpenOriginSuccessTest lock write, TimeoutTest direct and prompt-apply, ForcedValueTest plain). F7 changes that by design (no commit seen = failure recorded once). They are MODIFIED (renamed and assertion changed to the one PERSIST_FAILED record), not deleted. Report to the reviewer.
5. Pins: none changed. The serializer KDoc DOES mention Operations; the dormancy and single-call-site scans do not trip because they skip comments (corrected in the guardian fixes).

Risks:
- Not compiled or run. Verify: `private inline fun WriteOutcome.applyThenRethrow` (private class in a private inline) and the `try { return ... } finally { throw }` shape compile cleanly; the new tests rely on the fake store committing then throwing CancellationException from `lockWriteResult`/`timeoutWriteResult`/`plainWriteResult`.
- Non-reentrant lock: no inner call re-enters it (controller, repository, trigger do not use the serializer). The future S18 ii-g must not wrap `requestDiscreetChange` in the serializer.
- The F7 equality test can mistake another writer's identical value for ours (benign: same value).
- Residual 5 (privacy): an ENABLE whose write really committed but whose F7 re-read is unreadable (or throws) stays `Disabled` in-process, with one PERSIST_FAILED record, until a re-read of the store. INV-46 holds (no trigger, no move on unproven evidence) and notifications fail closed via INV-2 (the stored lock is on, so the effective values are forced on at the next read).

### S18-ii-a — guardian fixes (architecture + privacy ACCEPT-WITH-CONDITIONS)

Implemented, NOT validated, nothing was run. Write set: Operations, the CancelledWrite test, the OpenOriginSuccess test, LEGAL_PATHS, this handoff. S18-ii-b files untouched.
1. MAJOR (architecture 1, G-CANCEL-01 + U-PR1): the broad `catch (e: Exception)` of the F7 re-read moved into the standard helper `rereadStoreOrNull()` (`catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.w(class name only); null }`). `rereadAfterCancelledWrite` wraps only that call in a catch for `CancellationException` alone (maps to null, no broad catch left). Loaded + requested value gives Committed, anything else gives Failed(field). No behaviour change. Every other broad catch in Operations already has the rethrowing CE sibling; the new `applyThenRethrow` catch uses `if (e === held) throw e`.
2. Architecture 2 / privacy 4: `applyThenRethrow` no longer uses `finally { throw }`. When apply throws X while a cancellation is held, X is added as suppressed to the held cancellation (unless it is the same object) and the held cancellation is thrown. A second cancellation thrown by the re-read is added as suppressed to the first, which is the one rethrown.
3. Privacy C2: `settleCancelledWrite(..., lockOnIsForced = true)` for WRITE 5; a re-read with the stored lock on returns `Rejected(FORCED)` for an equal and a different value (see decision 3).
4. Privacy C3 and architecture 5 tests (AppLockSettingsOperationsCancelledWriteTest): lock-on equal and different value for both plain fields; a cancellation thrown by the re-read itself (WRITE 4 direct, WRITE 5, WRITE 3; original propagates, second suppressed, Failed recorded once); a throwing re-read isolated from the pre-read (failure set inside the write lambda) on both WRITE 4 paths and WRITE 5; the F7 DISABLE case (unreadable re-read keeps the lock on, PERSIST_FAILED(APP_LOCK_ENABLED) once, gate released); an outcome-step failure kept as suppressed. OpenOriginSuccessTest ~:131 now asserts the exact emitted list and the FIELD metadata. Gates are completed in a `finally` (unchanged).
5. Docs: LEGAL_PATHS ~:1714 and ~:1792 reworded (F4 serializer part CLOSED, F4(a) D8 BLOCKING for S18-ii-d); decisions 1, 3, 5 corrected above; residual 5 added.
Pins unchanged. Dormancy holds (no new production caller of Operations).
Targeted filter: `*AppLockSettingsOperations*,*AppLockControllerOpenOriginSuccess*,*AppLockControllerForcedValue*,*AppLockSettingsWriteSerializer*`, plus the registered guards cancellation (G-CANCEL-01) and `CancellationSafetyArchitectureGuardTest`.

Validation to request (serial, one batch):
- targeted `*AppLockSettingsWriteSerializer*,*AppLockSettingsOperations*,*AppLockController*,*AppLockPrivacySettingsViewModel*`
- shards domain-n-z and runtime-ui
- registered-guard cancellation, known_good_state, ui_dao

Gates: privacy-security-guardian, architecture-guardian, reviewer-strict.

## S18-ii-b — implemented

Files (source):
- `domain/notification/presentation/NotificationContentPolicy.kt`: `decideDiscreet(kind, sdkInt, actionsSuppressed)` has no default (KDoc updated).
- `data/service/AndroidNotificationService.kt`: `rebuildDiscreet` is now `suspend` and passes `actionsSuppressed = actionsSuppressedFor(kind)` (new private suspend helper).

Files (tests):
- `NotificationContentPolicyTest`: the default-pin case (`decideDiscreet drops bill actions on api 30 and below only when told ...`) is MODIFIED (the former default call is now an explicit `actionsSuppressed = false`, still asserting UNCHANGED on SDK 26 and 30); the three other `decideDiscreet(kind, sdk)` calls became explicit `false` (semantics unchanged). Nothing deleted.
- `BillNotificationSweepParityTest` (`T/service/reminder`): `settingsState` var behind the scripted repo, `sweepOnce()` and `lockOn()` helpers, two `@Config(sdk = [30])` rows: lock on (control: unlocked worker BILL has 2 actions; after the lock flips the sweep rebuild has none; a lock-on worker BILL has the same icon and priority and no actions) and unreadable (no actions).
- `AndroidNotificationServicePresentationTest`: new class `AndroidNotificationServiceSweepActionsSdk30Test` (lock on, unreadable, throwing read, lock off control). Asserts the action count after a real sweep and the single `NOTIF_ACTIONS_SUPPRESSED_LOCK` with metadata exactly `{SDK_INT: 30}`.

Decisions:
1. One rule, no copy. The service does not hold the settings repository. For BILL it calls `presentationResolver.resolve(kind, sdkInt)` and sets `actionsSuppressed = (actionPolicy == NONE)`. On API 30 and below `NONE` is exactly `EffectivePrivacyValues.effectiveLockEnabled(loadState)` (timeout, `Unreadable(any)` and a thrown read all count as lock on, as on the post path); on API 31+ the policy is `AUTH_REQUIRED` so the term is irrelevant. Non-BILL kinds pass `false` without a read (they have no actions). Deviation from the literal plan wording ("pass `effectiveLockEnabled(<committed state>)`"): same value, but it avoids a second rule, a new constructor parameter on the Hilt/androidTest/6 test construction sites, and an edit to the resolver (outside the write set).
2. D5: the resolver's existing private `reportActionsSuppressed` helper emits `NOTIF_ACTIONS_SUPPRESSED_LOCK(SDK_INT)` on that same call, so sweep-rebuilt BILLs record it exactly as the post path does. The code and its emitter entry already exist (`PrivacyDiagnosticsEmitter.kt:60`): no allowlist, registry or guard change.
3. `CancellationException` propagates from the resolver (it is rethrown before its broad catch); the sweep loop already rethrows it before its own `catch (Exception)`. No new catch added.
4. The read happens per BILL rebuild, after the pacer pause, so it reflects the state at that moment (one extra bounded read per swept BILL, `T_READ_MILLIS` = 500 ms bound inside the resolver).

Risks:
- Not compiled or run. Verify the new Robolectric rows: BILL channel creation in the new class, `actions` platform type on `unlockedWorker.actions.size`, and that `BillReminderWorker` runs under `@Config(sdk = [30])` in the parity test (it was only run at 34 before).
- Side effect: a sweep of a BILL under unreadable settings also records `NOTIF_POLICY_SETTINGS_UNREADABLE(cause)` through the resolver (bounded, rate limited, same as a post). Accepted; a reviewer may prefer a quiet path.
- The resolver's emitter is fire-and-forget on the application scope, so D5 emission is not awaited by the sweep (matches the post path).
- G1b still owns the device re-run of `NotificationPresentationDeviceTest` on API 26 and 30; `NotificationPresentationDeviceTest.kt:192` constructs the service with the unchanged 4-argument constructor (no change needed).

Validation to request (serial, one batch):
- targeted `*NotificationContentPolicy*,*BillNotificationSweepParity*,*AndroidNotificationService*,*NotificationPresentationResolver*`
- shards `data`, `domain-n-z` and `runtime-ui` (config/validation/test-shards.json: `service.*`, which holds `service/reminder`, is in `runtime-ui`; `domain.notification` falls under `domain-n-z`; `data.service` under `data`).

Gates: privacy-security-guardian (lead), reviewer-strict.

### S18-ii-b — privacy review (ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27; NOT validated, nothing was run)

- F1 (hardening): `AndroidNotificationService.actionsSuppressedFor` now tests `actionPolicy != NotificationActionPolicy.UNCHANGED` (was `== NONE`), so any unexpected policy value fails closed. KDoc updated. On API 31+ AUTH_REQUIRED now answers true, but `decideDiscreet` returns AUTH_REQUIRED first, so the output is unchanged.
- F2 (stale doc): `NotificationContentPolicy.decideDiscreet` KDoc no longer says the sweep "does not read the settings again"; it says the sweep does not re-read for the mode and the caller supplies the lock term (the service obtains it through the resolver for BILL).
- F3 (test): `AndroidNotificationServiceSweepActionsSdk30Test` gains a fifth row (a read that never completes via `awaitCancellation()`; the resolver's 500 ms bound is skipped by virtual time) asserting 0 actions and exactly one `NOTIF_ACTIONS_SUPPRESSED_LOCK` with `{SDK_INT: 30}`. The unreadable, throwing and timeout rows now assert `actionCount(BILL_ID) == 2` before the sweep (control). The old inline service construction in the throwing row was replaced by a shared `sweepWith(repo)` helper (`sweep(state)` delegates to it).
- INFO 4: the extra settings read on API 31+ BILL rebuilds is accepted, to avoid keeping a second SDK-gated rule beside the resolver.
- INFO 5: no code change required.
- C1 (condition): validation must include `*AndroidNotificationServiceRepost*` in the targeted batch, in addition to the S18-ii-b batch above.

### S18-ii-a+b — first combined batch (2026-10-27)
- Targeted vr-20261027-045853-80c28864 FAILED on exactly one test: `AppLockSettingsOperationsCancelledWriteTest` "WRITE 3 … re-read shows committed …".
- The cause was a TEST bug. The shared helper probed the T33 gate with an ENABLE prompt, which is legal only from DISABLED. In the committed case the state is UNLOCKED, so the probe got NotInOrigin.
- Fix (test-only): the probe now uses the legal open-origin prompt for the resulting state (ENABLE from DISABLED, DISABLE from UNLOCKED). Any other state fails, so no assertion is weakened.
- cancellation vr-20261027-050442-ab6e7ac8 PASS. The remaining jobs in the batch were held and are re-queued.
- architecture-contracts vr-20261027-050820-588a1bf7 FAILED on CANCEL-01 (U-PR1) at AppLockController.kt:1094. This is a PRE-EXISTING violation introduced by S16 part (ii-b) (fca5663b): the nested `catch (closeFailure: Throwable)` inside `launchPrompt`'s CE catch.
  - It went unnoticed because the app-lock batches since S16 did not include the architecture-contracts shard. The registered G-CANCEL-01 guard accepted it.
  - Fix (same behaviour): a preceding `catch (closeFailure: CancellationException)` sibling that attaches it to `e` as suppressed. `e` is still rethrown. The comment sits above the try, so the sibling stays inside the guard's 200-character window (the first attempt failed in vr-20261027-051556-650244a9 because of the comment).
  - Process: every app-lock batch that changes production code now includes the architecture-contracts shard.
- Passed on the current tree before this controller fix: targeted vr-20261027-050936-cd9bbaa4 and known_good_state vr-20261027-051224-29a2330d.
- Passed earlier on the pre-test-fix tree: ui_dao vr-20261027-050804-42457c64, cancellation vr-20261027-050442-ab6e7ac8.
- The batch is re-queued after the controller fix.

### S18-ii-a+b — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27)
- ISSUE-1: the LEGAL_PATHS BLOCKING list now strikes F3, as CLOSED by S18-ii-b.
- ISSUE-2: the re-post sweep legal path (LEGAL_PATHS) and the ENGINE map rows now show the BILL resolver read, `actionsSuppressed = actionPolicy != UNCHANGED`, and the D5 and unreadable codes.
- ISSUE-3: ui_dao is re-run on the final tree.
- N5: S18-ii-b decision 1's `== NONE` is superseded by F1 (`!= UNCHANGED`).
- N2, N3, N4 and N6 are recorded as accepted:
  - N2: an Error is suppressed under a held cancellation, the same primary-wins semantics as `use`.
  - N3: a WRITE 4 cancelled-differ records a failure; this cannot happen under the shared serializer.
  - N4: the serializer is held across the sweep; this is bounded.
  - N6: Hilt sharing is proven at app-check.
- Final-tree validation (fingerprint 170e3954…), all PASS:
  - targeted vr-20261027-052203-f4e739fd
  - cancellation vr-20261027-052451-f87ff41b
  - architecture-contracts vr-20261027-052507-76056dbc
  - domain-n-z vr-20261027-052553-d8b56820
  - runtime-ui vr-20261027-052740-eb499ae2
  - data vr-20261027-052941-9743300b
  - known_good_state vr-20261027-053500-6ab5bb4a
  - ui_dao: re-run after these doc-only edits
- Open BLOCKING after S18-ii-a+b: F4(a)/D8 (S18-ii-d), finding 8 (S18-ii-f), WRITE 5 routing / ISSUE-4 exit (S18-ii-g, owner D4), INV-45 ViewModel routing (S18-ii-h with S20, owner D1).
- S18-ii-a and S18-ii-b ACCEPTED (2026-10-27). ui_dao vr-20261027-060124-3941740e PASS on the final tree. Committed. Next: S18-ii-c (notices INV-51; re-read T28/T29). It needs the owner's D9 (the emitter allowlist entry LOCK_SETTINGS_RECOVERED) before its emitter part.

## S18-ii-c — implemented (2026-10-27; NOT validated, nothing was run)

### Files
- New main: `domain/privacy/applock/LockNoticeDerivation.kt` (pure, `derive(state, loadState, biometricPending = false): LockNotice?`).
- Edited main: `domain/privacy/applock/AppLockController.kt` only (the `notice` flow, the publication, `requestSettingsReread`, the foreground re-read, private `onSettingsReread`, T28/T29, the D6 write counter). `LockNotice.kt` (S16 types) is reused unchanged.
- Docs: `docs/architecture/LEGAL_PATHS.md` (the controller READ + RECOVERY paragraph under the App Lock Settings Write Path).
- New tests: `LockNoticeDerivationTest`, `AppLockControllerNoticeTest`, `AppLockControllerRereadTest`.
- Edited tests: `AppLockControllerIllegalTransitionTest` (method-set pin gains `getNotice` and `requestSettingsReread`; the source-scan `forbidden` list gains `requestSettingsReread` with no allowed file = ZERO production callers), `AppLockControllerTestFixtures` (`FakeAppLockSettingsRepository.scriptRead(result, gate)`: scripted, optionally gated, later reads; the default read is unchanged).

### Decisions
1. Publication (R2): `transition` now returns a `Publication(stamp, state, notice)`. The notice is derived under the monitor at the end of every transition from `(current, loadState)`. If it changed, `version` is bumped (so a load-state-only change is republished). State and notice are set together under `publishLock`, after the monitor is released, and only if the stamp is newer than `publishedVersion`. An equal value is not re-emitted (StateFlow), so AE-115 never sees the notice leave and return.
2. R4: REVIEW_SETTINGS comes from the load state (persistent causes only). A null load state (the resolve-timeout fallback) and transient causes give none.
3. AMD-22: `Authenticating(ENABLE)` derives the notice of `Disabled`, `Authenticating(CONFIRM_CHANGE)` that of `Unlocked`; `Authenticating(UNLOCK)`, `Locked`, `Resolving` give none.
4. Re-read: `requestSettingsReread()` (public, zero callers) and the foreground re-read (launched AFTER the foreground evaluation, on the controller scope, never awaited; one foreground re-read at a time) both go through `launchReread`, which only issues a read while the load state is `Unreadable` with a transient cause. Each issue takes a new sequence number and records the write counter. The result goes to the private `onSettingsReread(seq, read)`, separate from `onLateSettingsResolved`.
5. `onSettingsReread` drops: a stale sequence; a write counter that moved since the issue (a superset of D6, see risks); a view that is no longer transient-unreadable; an unreadable result (the view and state stay; a cause change to persistent is NOT adopted, T28's trigger is Loaded or FirstRunDefault only). A readable result is applied by T28 from UNLOCKED / INERT, kept by T29 in LOCKED / AUTHENTICATING, ignored otherwise.
6. T28 (`applyRereadLocked`): takes the read as the view; lock off → DISABLED; lock on and no genuine capability gap (CHECK_FAILED counts as able, as in T14/T15) → UNLOCKED; else INERT(reason) via the existing `enterInert` (existing LOCK_INERT_* code, once per process). Capability is read before the monitor (`readQueryCapability`). No auth, no write, never locks. The pending background is not touched.
7. T29 apply: `closeWith` calls `applyKeptRereadAfterClose()` after an UNLOCK success or after ANY close of an open-origin attempt (after the T33 hand-off to the handler). While the state is still LOCKED / AUTHENTICATING / RESOLVING the snapshot stays kept (LOCKED is never relaxed; a T34 hand-off that locks keeps it). In an open state it is consumed: dropped if `writeSequenceAtKeep != writeSequence` (D6) or the view is no longer transient, else T28. T6b clears a kept snapshot.
8. D6 write counter (`writeSequence`, under `stateLock`) is bumped by the committed entries: `onOpenOriginSettingApplied(committed = true)` (after the token check; SUPERSEDED by F3: the counter is bumped BEFORE the token check), `onPlainSettingWritten(committed = true)` (for DISCREET / SECURE_SCREEN), `onTimeoutWrittenWithoutPrompt(committed = true)`. A non-committed call does not bump it.
9. No new catch was added. The re-read job is `scope.launch`; the foreground flag is reset by `invokeOnCompletion` (also on cancellation). `CancellationException` from the read propagates (`readLoadState` rethrows it).

### D9 seam (OWNER GATE, not approved) (SUPERSEDED by the 2026-10-27 D9 section: the owner approved D9 and it is now emitted)
- `PrivacyDiagnosticsEmitter.kt` and every allowlist are untouched. `LOCK_SETTINGS_RECOVERED` is NOT emitted anywhere.
- The single marked point is in `AppLockController.applyRereadLocked` (the only T28 apply, also reached by the kept-snapshot path): `// TODO(S18-ii-c D9): emit LOCK_SETTINGS_RECOVERED(cause) once the owner approves the emitter entry`. The cause to emit is the PRIOR load state's `Unreadable.cause` (IO_ERROR or TIMEOUT); it must be captured before `loadState = read` overwrites it. The emission should be a `PendingDiagnostic` claimed once per (code, cause) in `claimedCodeCauses` like the other cause-carrying codes.
- No test requires the emission. The tests do not assert the emitted list for T28.

### Risks / residual
- The foreground re-read reads the store at every foreground while transient-unreadable (one at a time). Dormant until S20 delivers APP_FOREGROUNDED.
- `onAppForegrounded` now has a side effect (a launch) in transient-unreadable states; existing tests that foreground over `IO_ERROR` (InertTest `T16 does not fire...`) get an extra read that returns the same unreadable value and is ignored. No existing test asserts `readCount` after a foreground in that state.
- The write counter check is taken from the ISSUE of the read (a superset of D6's "after it was kept"): a write that commits while a read is in flight also drops that read. Conservative: the next foreground re-reads.
- The controller's resolve-timeout fallback (load state null) is not re-read by the foreground (the spec's trigger is `Unreadable(IO_ERROR | TIMEOUT)`); only T6b recovers it, as before. If the owner wants `null` treated as transient, `transientUnreadableLocked` is the one place.
- A kept snapshot can linger across a T7b/T11 INERT until the next close or re-read; it is consumed or replaced there and is always re-checked (transient view, D6) before it applies.
- A load-state-only notice change cannot be exercised through the public API yet (REVIEW_SETTINGS ends only by the S18-ii-d review-save), so the republish path is covered by the derivation and the shared stamp only. S18-ii-d should add that test.
- The biometric notice stays false (AE-99's fingerprint rows and AE-100 are Increment 2).
- `AppLockControllerTestFixtures.kt` is shared by many classes: a compile error there breaks the whole targeted run.

### Validation to request (validation-runner, serial)
- targeted `*AppLockController*,*LockNoticeDerivation*,*PrivacyDiagnosticsEmitter*`
- shards `domain-n-z` and `architecture-contracts`
- registered-guard `cancellation` and `known_good_state`
- Gates: privacy-security-guardian (lead), reviewer-strict.

### S18-ii-c — privacy review (ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27; NOT validated, nothing was run)

Fixes applied (main: `AppLockController.kt`; tests: `AppLockControllerNoticeTest`, `AppLockControllerRereadTest`):
- F1 (Medium): torn state / notice pair. `publish` now writes a NULL notice before the state and a non-null notice after it. KDoc of `notice` states the guarantee: no closed state (RESOLVING, LOCKED, AUTHENTICATING(UNLOCK)) is ever observable with a non-null notice. Test: a Unconfined collector on `state` records `(state, notice.value)` across resolve, unlock, UNLOCKED+ReviewSettings -> LOCKED(TIMEOUT), re-unlock, re-lock, and asserts no closed state carries a notice.
- F2: BINDING, see below (no code in this slice).
- F3: `onOpenOriginSettingApplied` bumps `writeSequence` on `committed` BEFORE the token check (a committed write with no matching token still drops older re-reads and kept snapshots; fail closed).
- F4 (comment only): the D9 TODO moved above `loadState = read` in `applyRereadLocked`, with the capture instruction. Nothing is emitted; `LOCK_SETTINGS_RECOVERED` is still not emitted anywhere.
- F5: a newer Unreadable re-read result supersedes an older kept snapshot. The Unreadable early return moved inside the transition, after the sequence, D6 and transient checks; in LOCKED / AUTHENTICATING it sets `keptReread = null`; in every other state it changes nothing (no capability is read for it). Test covers LOCKED and AUTHENTICATING(UNLOCK), then a later foreground still recovers.
- F6 (test): pins the INV-47 order (evaluateForeground BEFORE launchReread): transientUnlocked, background, +1 s, scriptRead(DISABLED), foreground -> Locked(TIMEOUT), unlock -> Disabled. If the calls were swapped, T28 would apply DISABLED from UNLOCKED first and the evaluation would find the lock off, so the Locked(TIMEOUT) assertion would fail.
- F7 (test): a D6 row through `onOpenOriginSettingApplied(committed = true)` with a CHANGE token (and a second row with a non-matching id, which pins F3): the kept snapshot is dropped, the unlock ends UNLOCKED, not DISABLED.

F2 is BINDING for later slices (a committed write that does not bump `writeSequence` lets a stale kept snapshot or in-flight re-read relax the state):
- S18-ii-d (review-save), S18-ii-e (T19) and S18-ii-g (WRITE 5) must EACH bump `writeSequence` on commit, with one D6 test per entry.
- S20 must NOT lift dormancy (APP_FOREGROUNDED delivery and the re-read triggers) before d, e, g and h have landed.

Decisions: the 4 decisions put to this review are confirmed (KEEP).

Open spec-owner questions:
1. Should a transient -> persistent cause change be adopted by the re-read? Today it is not (T28 triggers on Loaded or FirstRunDefault only), so a review notice for a newly CORRUPTED store stays hidden until the next cold start.
2. Is a null load state after RESOLVE_TIMEOUT intended to stay for the whole process (only T6b recovers it), given INV-47 "not sticky"?
3. Should T28 also cover DISABLED after a T18 commit over a transient view (F8)?

INFO: F9, F10 (no change made; recorded by the review only).

Targeted filter for this fix pass: `*AppLockControllerRereadTest*,*AppLockControllerNoticeTest*` (plus the earlier `*AppLockController*,*LockNoticeDerivation*,*PrivacyDiagnosticsEmitter*` batch when validation resumes).
- Orchestrator detail for the S18-ii-c review section (supersedes handoff Decisions 5 and 8 in "S18-ii-c — implemented"):
  - Confirmed decisions (all KEEP, fail-closed):
    - (1) The write-counter check is taken when the read is issued. This is a superset of D6: it only drops more results.
    - (2) An Unreadable re-read result does not change the view. Since F5, in LOCKED/AUTH it clears an older kept snapshot.
    - (3) A null load state (RESOLVE_TIMEOUT) is not re-read by the foreground; it is recovered only by T6b or a cold start.
    - (4) The foreground re-read is dormant because `onAppForegrounded` has no production caller. This must be re-checked when S20 wires it (see F2).
  - F9 (INFO): after T28, a pending background is measured against the stored timeout, which may be longer than F. This is spec-consistent; RereadTest pins it.
  - F10 (INFO): the re-read has no timeout. A hung read keeps `foregroundRereadRunning` set and suppresses further foreground re-reads for the process. `requestSettingsReread` still works. Fail-closed.

### S18-ii-c — first batch (2026-10-27)

- Targeted vr-20261027-070351-9439d264 FAILED on 3 tests. The root cause is test-side for all 3 (debugger). Test-only fixes applied, uncommitted, not re-run:
  - AppLockControllerNoticeTest F1: observes both the state and the notice flows (publish writes a non-null notice after the state; a reordered publish shows up in `torn`).
  - AppLockControllerPromptOutcomeTest R2: reflection moved to `publish(view: Publication)` and the 3-parameter `Publication` constructor. Stale stamps drop both state and notice, a newer stamp publishes both, a stale null notice does not clear a published one.
  - AppLockControllerRereadTest T29: scripts a readable `DISABLED` before `onAppForegrounded()` (the foreground issues its own re-read, seq 2).
- Cancellation vr-20261027-070854-6ec4e08d PASS.
- The remaining jobs are held and re-queued.

### S18-ii-c — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27; NOT validated, nothing was run)

Applied:
- ISSUE-1 (comment only): the `notice` KDoc in `AppLockController.kt` now states the guarantee per instant (values written under publishLock): a closed state (RESOLVING, LOCKED, AUTHENTICATING(UNLOCK)) is never paired with a non-null notice; two separate reads or a multi-threaded `combine` can pair values from different instants.
- ISSUE-2 (test): `AppLockControllerRereadTest` "a cancelled foreground re-read frees the next foreground to read again" now asserts `Unlocked` after the `finally` (the held read did not apply) and that the second `onAppForegrounded()` increases `readCount` by exactly 1.
- N4 (docs): `LEGAL_PATHS.md` READ + RECOVERY now records the F5 rule (a newer unreadable re-read result clears a kept snapshot in LOCKED / AUTHENTICATING) and F3 (the write counter is bumped before the token check).
- Decisions item 8 in "S18-ii-c — implemented" ("after the token check") is marked SUPERSEDED by F3.

BINDING:
- S20 (AppLockGate, the notice consumer): render a notice only together with an open state from the same sample, or collect both flows on Main.
- S20 / S18-ii-h (N1): debounce `requestSettingsReread` retries, or route them through the in-flight guard.
- Carried forward: F2 stays binding on S18-ii-d, S18-ii-e, S18-ii-g and S20 (each committed write entry bumps `writeSequence` with a D6 test; S20 must not lift dormancy before d, e, g and h).

INFO:
- N2: the "link query capability read failed" log wording now also covers the re-read paths; it is bounded.
- N3: T28 to DISABLED leaves `lastBackgroundedAtMillis` set, like T18. Spec-owner note next to F9.

Targeted filter for this pass: `*AppLockControllerRereadTest*` (plus the pending `*AppLockController*,*LockNoticeDerivation*,*PrivacyDiagnosticsEmitter*` batch).
- S18-ii-c ACCEPTED (2026-10-27), without D9; the LOCK_SETTINGS_RECOVERED emission stays owner-gated behind the TODO seam. (SUPERSEDED by the 2026-10-27 D9 section: D9 is approved and emitted.)
  - Final targeted vr-20261027-075534-aed6567a PASS, after the test-only and comment-only final-gate fixes.
  - Earlier runs on the final production code, all PASS: targeted vr-20261027-072634-4c7caf0f, architecture-contracts vr-20261027-072201-01eceafa, domain-n-z vr-20261027-072433-c12f8e58, cancellation vr-20261027-070854-6ec4e08d, known_good_state vr-20261027-070912-75653578.
  - Gates: privacy-security-guardian ACCEPT-WITH-CONDITIONS (F1–F7); reviewer-strict ACCEPT-WITH-CONDITIONS (ISSUE-1/2, N4).
  - Committed. Next: S18-ii-d (review-save, domain).

## S18-ii-d — implemented (PARTIAL: production complete; tests and LEGAL_PATHS partly left; NOT validated)

Files changed (production):
- domain/privacy/applock/AppLockSettingsRepository.kt: `AppLockWriteRejection.NOT_UNDER_REVIEW` plus KDoc of the conditional `reviewSave`.
- data/privacy/applock/AppLockSettingsRepositoryImpl.kt: `reviewSave` writes only if, INSIDE the same store edit, `prefs.toLoadState()` is `Unreadable` with a persistent cause (CORRUPTED, INVALID_VALUE); otherwise nothing is written and the result is `Rejected(NOT_UNDER_REVIEW)` (F4(a), D8). IO handling unchanged (`Failed(REVIEW_SAVE)`).
- domain/privacy/applock/ReviewSaveAuthorisation.kt (new): pure `classify(record)` / `isWeakening(record)`, `ReviewSaveClass`. Weakening = lock off, or timeout longer than IMMEDIATE; forced discreet and secure do not count.
- domain/privacy/applock/AppLockController.kt: `startReviewSaveAttempt(record, handler)` (CHANGE prompt, only from UNLOCKED, and only while the load state is persistent-unreadable, checked under the monitor), the record in the attempt and the one-shot token (`pendingApplyRecord`), `onReviewSavedWithoutPrompt(saved, committed)`, shared `applyReviewSaveCommittedLocked`, `internal val isPersistentUnreadable`. `onOpenOriginSettingApplied` applies T27 from the token. The controller owns `LOCK_SETTINGS_REVIEW_SAVED` (`claim`, once per process) and `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)` (`claimPersistFailedLocked`, once per (code, field)); both are recorded through the existing fire-and-forget `record()`, so a diagnostics failure cannot fail a committed save. No emitter, allowlist or config change: REVIEW_SAVE already exists in `AppLockSettingField` and both codes already have emitter entries.
- domain/privacy/applock/AppLockSettingsOperations.kt: `ReviewSaveResult` and `requestReviewSave(record)` with the ten steps of the brief in order (applyRunning, review-open check, fresh capability, classify, prompt apply, D3 INERT, direct write, controller entry, trigger). The weakening apply runs in the serializer under NonCancellable with `settleCancelledWrite` (F7), then `onOpenOriginSettingApplied`, then the trigger `(false, effective after)`, completion in a `finally`. No new broad catch except `writeReviewSave` (rethrowing CancellationException catch directly above, same shape as `writeTimeout`); the launch-failure release uses try/finally, no catch.

Files changed (tests): AppLockControllerIllegalTransitionTest (public set +2: startReviewSaveAttempt, onReviewSavedWithoutPrompt; forbidden/ops-only sets +3 incl. `isPersistentUnreadable`; dormancy list +`requestReviewSave`; the `.reviewSave(` pin is now exactly {ViewModel, Operations} through a new `assertProductionCallers`, `assertSingleProductionCaller` delegates to it). New: ReviewSaveAuthorisationTest.

How F2 and D8 are met:
- F2: `onOpenOriginSettingApplied(committed)` bumps `writeSequence` (existing F3 bump) and `applyReviewSaveCommittedLocked` bumps it and drops `keptReread`; `onReviewSavedWithoutPrompt(committed)` bumps it as well. The D6 test is NOT yet written.
- D8: the in-edit check above. The GatedStore race test is NOT yet written.

Decisions: a direct-path `Rejected(NOT_UNDER_REVIEW)` gives `NOT_IN_REVIEW` with no record and no state change; in the prompt path any non-commit goes through `onOpenOriginSettingApplied(id, false)` and so records `PERSIST_FAILED(REVIEW_SAVE)` (the token must be consumed). The discreet trigger also runs after the direct commit (before = false, as for enable), which keeps AE-87 ({lock off, discreet on}) re-posting discreet. The controller moves state on a commit only from UNLOCKED/INERT with no open attempt (fail closed otherwise); the view is always replaced by the committed record.

LEFT TO DO (not done, tool budget reached):
1. AppLockSettingsRepositoryImplTest: EXISTING reviewSave cases that call `reviewSave` on a Loaded/first-run/IO store now return `Rejected(NOT_UNDER_REVIEW)`; MODIFY them (seed CORRUPTED or INVALID_VALUE first, never delete), add conditional cases (Loaded, FirstRun, IO_ERROR -> Rejected, store untouched) and the GatedStore race (hold `setAppLockEnabled(true)` open, queue `reviewSave` over a recovered store, expect Rejected(NOT_UNDER_REVIEW) and the lock key on; pattern of S18 i-b2 ISSUE-3). Also check AppLockSettingsRepositoryImplCorruptionTest and AppLockPrivacySettingsViewModelTest for `reviewSave` over a non-review fake; FakeAppLockSettingsRepository.reviewSave (Fixtures :107) may need a persistent-unreadable guard to mirror D8.
2. New AppLockControllerReviewSaveTest: AE-85 (cancel keeps sentinel, success -> DISABLED, REVIEW_SAVED), AE-86 (no prompt, stays UNLOCKED), AE-87 (INERT, lock-on record REJECTED_CAPABILITY, lock-off no auth -> DISABLED), D3 (AVAILABLE or CHECK_FAILED -> NOT_IN_ORIGIN), AE-89 (failing write -> FAILED, PERSIST_FAILED(REVIEW_SAVE) once), applyRunning/ALREADY_IN_FLIGHT, NOT_IN_REVIEW for a non-persistent cause, F7 cancelled-write re-read, trigger (false, after), and the F2 D6 test: a kept (T29) or in-flight re-read snapshot is dropped after a committed review-save.
3. AppLockControllerOpenOriginSuccessTest: review-save T33 cases (both callback orders, link release after completion, the copy table ENABLE->ENABLE, T18->DISABLE, longer timeout and weakening review-save -> CHANGE).
4. AppLockControllerNoticeTest: AE-115 review variant (review notice stays during the CHANGE prompt), AE-87 inert notice gone, AE-101 notice ends after the save, and the S18-ii-c gap "republish on a load-state-only change" via review-save.
5. AppLockPrivacySettingsViewModelTest: one parity case (a stale 1a save is Rejected(NOT_UNDER_REVIEW) and gives SAVE_FAILED).
6. LEGAL_PATHS (docs/architecture/LEGAL_PATHS.md): WRITE 2 (~:1702-1745) gets the 1b path through the controller (requestReviewSave -> startReviewSaveAttempt / onReviewSavedWithoutPrompt -> conditional reviewSave); BLOCKING list (~:1736, ~:1815) marks F4(a)/D8 CLOSED and reduces the INV-45 entry to "ViewModel routing (S18-ii-h)".

Risks: (a) the weakening prompt path starts from UNLOCKED even when the capability has just become unavailable; the prompt then reports NotAvailable and returns as a bounded error (fail closed, no write). (b) The ViewModel's direct `reviewSave` (1a) can still run until S18-ii-h; D8 makes a stale call harmless. (c) A stale controller view (store recovered by another writer) stays unreadable until the next process start; unreachable while Operations is the only controller-side writer. (d) Not compiled or run.

Validation to request (serial, validation-runner): compile; targeted-unit-test `*AppLockController*,*AppLockSettingsOperations*,*ReviewSaveAuthorisation*,*AppLockSettingsRepositoryImpl*,*AppLockPrivacySettingsViewModel*`; shards domain-n-z, data, runtime-ui, architecture-contracts; registered-guard cancellation and known_good_state. Run them only after items 1 to 5 above are done (item 1 would fail today).

### S18-ii-d — tests part A

Done: LEFT TO DO items 1 (repository tests, fake), 5 (ViewModel parity) and 6 (LEGAL_PATHS). NOT compiled or run. Items 2, 3 and 4 (controller review-save tests) are part B.

Modified cases in AppLockSettingsRepositoryImplTest (none deleted; only the SEED changed, no assertion changed). New private helper `commitViaReview(record)` sets the real store's marker to CORRUPTED, then commits the record through `repository.reviewSave` and asserts Committed:
- `setDiscreet_keeps_the_stored_1b_values`: reviewSave on an empty store -> commitViaReview.
- `reviewSave_writes_a_complete_1b_record_and_reads_it_back`: empty store -> `real.edit { marker = CORRUPTED }` first.
- `reviewSave_write_failure_keeps_a_committed_1b_record`: seed reviewSave -> commitViaReview (the failing second call fails before the transform, so it is still `Failed(REVIEW_SAVE)` over the Loaded record).
- `setAppLockEnabled_leaves_the_other_fields_untouched`, `setAutoLockTimeout_leaves_the_other_fields_untouched`: seed -> commitViaReview.
- `seedRecord` helper (used by the conditional-timeout, forced-aware and other tests): now commitViaReview.
- `forcedAware_check_reads_inside_the_same_edit_as_a_concurrent_lock_commit`: the per-iteration seed -> commitViaReview (the second iteration starts from a Loaded store).
- Already seeded CORRUPTED / INVALID_VALUE and unchanged: `reviewSave_clears_the_sentinel_with_one_atomic_complete_record`, `reviewSave_also_repairs_an_invalid_value_state`, `reviewSave_write_failure_leaves_store_and_sentinel_untouched`, `reviewSave_does_not_clear_the_sentinel_when_the_write_fails_then_succeeds_on_retry`, `reviewSave_repairs_an_invalid_lock_flag_state`. AppLockSettingsRepositoryImplCorruptionTest already starts from a corrupt file: unchanged.

New cases (repository):
- `reviewSave_over_a_loaded_store_is_rejected_not_under_review_and_writes_nothing`
- `reviewSave_over_a_first_run_default_store_is_rejected_not_under_review_and_writes_nothing` (store stays empty)
- `reviewSave_with_a_failing_read_but_a_loaded_store_in_the_edit_is_rejected_not_under_review`
- `reviewSave_with_a_transient_io_error_store_is_rejected_and_writes_nothing`: asserts `Rejected(UNREADABLE)`, NOT `NOT_UNDER_REVIEW`. DEVIATION to confirm: a transient IO_ERROR cannot be seen inside the edit transform (the transform only runs over data that was read; `toLoadState()` never yields IO_ERROR). A read failure that also fails the edit goes through `writeFailure(rejectOnPersistentUnreadable = false)` and gives `Rejected(UNREADABLE)`. The "transient -> NOT_UNDER_REVIEW" wording of the KDoc is therefore unreachable on the real store; the case of a failing read over a Loaded edit is the second test above.
- `reviewSave_over_each_persistent_unreadable_cause_commits_the_full_record_and_marker` (CORRUPTED with a stale key, unknown marker, bare invalid lock flag: exact five-key map, marker NORMAL, Loaded read-back)
- `reviewSave_a_second_stale_save_after_the_recovery_is_rejected_and_keeps_the_first_record`
- `reviewSave_check_reads_inside_the_same_edit_as_a_concurrent_review_save` (the race, below)

Race construction: a lock write over a CORRUPTED store is refused (INV-44: setAppLockEnabled returns without writing while Unreadable), so it cannot be the concurrent writer. Used instead: the closest legal writer that makes the in-edit state readable, another review-save. Store seeded CORRUPTED; `GatedStore` holds review-save A (lock on, M5) open inside its edit; review-save B (defaults, lock off) is issued and queues behind it (`secondCalled`, and B is asserted not complete); while A is held a plain `getLoadState()` still reads CORRUPTED (what a check before the edit would see, and would then overwrite A). After the release: A Committed(A), B `Rejected(NOT_UNDER_REVIEW)`, lock key still true, timeout still M5. The gate is completed in a `finally`.

Fake (AppLockControllerTestFixtures.FakeAppLockSettingsRepository.reviewSave) now mirrors D8: counts `writeAttempts`, then writes only if `loadState` is Unreadable with a persistent cause (records into new `reviewSaves`, honours new optional `reviewSaveResult` / `reviewSaveFailure`, otherwise commits and sets `loadState = Loaded(record)`); any other state returns `Rejected(NOT_UNDER_REVIEW)`. Controller tests whose seed changed: NONE (grep of src/test: no controller test calls the fake's reviewSave; its old unconditional `Failed(REVIEW_SAVE)` had no caller; the other fakes in the repo are separate local classes and unchanged). Part B tests that need a Failed review-save set `reviewSaveResult`.

ViewModel parity (AppLockPrivacySettingsViewModelTest): new case `a stale 1a review save on a no longer corrupted store is Rejected NOT_UNDER_REVIEW and shows SAVE_FAILED (S18 ii-d D8 parity)` using the existing local FakeRepository with `reviewSaveResult = Rejected(NOT_UNDER_REVIEW)`: NeedsReview(true, false, SAVE_FAILED), re-read (observeCalls == 2), `PERSIST_FAILED(REVIEW_SAVE)`, no REVIEW_SAVED, no trigger. The ViewModel needed no change (its Rejected branch is generic). No existing ViewModel case was changed (all seed CORRUPTED).

LEGAL_PATHS: WRITE 2 gets the conditional-write paragraph; new "WRITE 2b" (1b review-save through requestReviewSave -> prompt / D3 / direct -> serializer -> conditional reviewSave -> controller T27 entries); F4 and the SERIALISATION paragraph mark F4(a)/D8 CLOSED (S18-ii-d); the INV-45 entry is now "ViewModel routing (S18-ii-h, merges with S20)".

Filter for part A: `*AppLockSettingsRepositoryImpl*,*AppLockPrivacySettingsViewModel*` (plus `*AppLockController*` for the fixture change; the full set stays as listed above).

### S18-ii-d — tests part B

Done (NOT compiled or run): LEFT TO DO items 2, 3 and 4 (controller review-save tests), plus a comment-only KDoc fix.

- AppLockSettingsRepository.kt: the `reviewSave` KDoc now says a transient read failure gives `Rejected(UNREADABLE)` on the real store; only a readable in-edit state (`Loaded`, first run) gives `NOT_UNDER_REVIEW`. No code change.
- Fixtures: `FakeAppLockSettingsRepository.reviewSaveGate` (new, a rig helper: the review-save suspends on it after the D8 check and the recording, before it decides).
- New `AppLockControllerReviewSaveTest` (AE-85, AE-86, AE-87, AE-89, D3, REJECTED_CAPABILITY, NOT_IN_REVIEW, D8 stale store on both paths, start guards of the two controller entries, ALREADY_IN_FLIGHT, INV-46 (no move before the durable write), cancelled scope, F7 on the direct and the prompt path (committed / no commit / another value / throwing re-read), trigger `(false, effective after)` on both paths, F2 D6 (kept, in-flight, and the not-committed control), D7 once-per-process and bounded, a failing or stuck sink).
- `AppLockControllerOpenOriginSuccessTest` (+7): hand-off returns before the apply, AE-117 both callback orders for a lock-off and a lock-on weakening record, held link waits and is released after completion and after a failed write (gate closed afterwards), trigger once + flag cleared, prompt copy table (ENABLE, DISABLE, CHANGE for timeout and review-save), a non-weakening save starts no prompt.
- `AppLockControllerNoticeTest` (+5): AE-115 review variant (both weakening records, cancelled then success, seen == [ReviewSettings, null]), failed prompt / failing write keeps the notice, AE-87 inert notice gone (and kept on a refused lock-on record), AE-101 ends after the save and does not return after relock / unlock / cold start, S18-ii-c gap (republish on a load-state-only change on the direct and the prompt path; state stays UNLOCKED).

Findings to confirm (no production change made):
1. D6 for review-save is not observable independently. A kept T29 snapshot or an in-flight re-read exists only while the view is TRANSIENT unreadable, and a review-save needs a PERSISTENT view, so through the public API (`requestReviewSave`, `startReviewSaveAttempt`) the two never coexist. The D6 tests therefore call the public entry `onReviewSavedWithoutPrompt(saved, true)` directly over a transient view (as RereadTest does for the other entries). They pass through the view change to `Loaded` as well as through the `writeSequence` bump, so they cannot tell the two apart. The token (prompt) path cannot be given a D6 test at all (`startReviewSaveAttempt` refuses a transient view).
2. A second `requestReviewSave` while the weakening prompt is open answers `NOT_IN_REVIEW` (state is AUTHENTICATING), not `ALREADY_IN_FLIGHT`. ALREADY_IN_FLIGHT arises only while the apply runs. The test accepts either, plus asserts one prompt and no write.
3. The prompt-path `Rejected(NOT_UNDER_REVIEW)` (D8 race) is not asserted for the PERSIST_FAILED record (the decision "any non-commit goes through onOpenOriginSettingApplied(id, false)" is left unpinned); the test pins state, store, no REVIEW_SAVED, no trigger, and that the completion freed the flag and the gate.
4. The `reviewSave` hook cannot gate the DIRECT path's in-serializer `applyRunning` re-check (no overlap probe for that line).

Filter (targeted-unit-test): `*AppLockControllerReviewSaveTest*,*AppLockControllerOpenOriginSuccessTest*,*AppLockControllerNoticeTest*` for part B alone. Combined with the production set: `*AppLockController*,*AppLockSettingsOperations*,*ReviewSaveAuthorisation*,*AppLockSettingsRepositoryImpl*,*AppLockPrivacySettingsViewModel*,*LockNoticeDerivation*`.

### S18-ii-d — compile fix + privacy review (ACCEPT-WITH-CONDITIONS) fixes

Applied (2026-10-27; NOT compiled or run; validation HELD):

- Compile fix (targeted run vr-20261027-084324-4154bf4b, `AppLockController.kt:632:55 No value passed for parameter 'requestedRecord'`): `startOpenOriginAttempt` now passes the 4th argument, `startOpenOriginAttemptWith(prompt, onSuccess, null, null)`. Other callers re-checked: `startTimeoutChangeAttempt` (4 args), `startReviewSaveAttempt` (4 args), both `InFlightAttempt(...)` constructions (`requestedRecord` has a default), `onReviewSavedWithoutPrompt(saved, committed)` (unchanged signature, test callers unchanged). No `when` over `AppLockWriteRejection` or `ReviewSaveResult` exists in main or test (all uses are `==` comparisons or constructions), so no exhaustiveness risk.
- C1 (INV-45), applied: `requestReviewSave` passes `noAuthUnderD3` (true only when the direct path was taken because of D3 in INERT) into `writeReviewSaveDirect(record, noAuthUnderD3)`. Inside the serializer, after the `applyRunning` and `isReviewOpen` re-checks and before the write: a fresh `readCapability()`; under D3 the state must still be `Inert` and the capability neither `AVAILABLE` nor `CHECK_FAILED`, else `NOT_IN_ORIGIN` with nothing written; a lock-on record re-requires a fresh `AVAILABLE`, else `REJECTED_CAPABILITY`. New tests in `AppLockControllerReviewSaveTest` hold the shared serializer with a gate completed in a `finally`: D3 save with the capability becoming AVAILABLE or CHECK_FAILED while waiting gives `NOT_IN_ORIGIN` and nothing written; a D3 control that commits when nothing changed; a lock-on save whose capability is lost while waiting gives `REJECTED_CAPABILITY`. The INERT to UNLOCKED variant is not driven (no public way to move INERT to UNLOCKED mid-wait without a re-evaluation); the capability variant exercises the same in-serializer guard, and the `state !is Inert` half is covered by code review only.
- C2 (doc), applied: `docs/architecture/LEGAL_PATHS.md` WRITE 2b states that on the DIRECT path a `Rejected(NOT_UNDER_REVIEW)` returns `NOT_IN_REVIEW` with no record and no controller call; only the PROMPT path records `PERSIST_FAILED(REVIEW_SAVE)` via `onOpenOriginSettingApplied(id, false)`. It also records the in-serializer re-proof of D3 and of the lock-on capability.
- F3 (test vacuity), applied: the D7 "carry nothing but enums" test now also runs a healthy-sink failing save then a committed save, and asserts the emitted list is non-empty, `PERSIST_FAILED` once with `FIELD = REVIEW_SAVE`, the `REVIEW_SAVED` metadata is empty, and every metadata value is an enum.
- Info 5, applied: the direct path now passes `result.settings` (the committed record the store returned) to `onReviewSavedWithoutPrompt`, not the caller's record.
- Part B findings 1, 2 and 4 accepted (privacy-confirmed). Finding 3 is resolved by C2 (documented asymmetry) and stays unpinned for the prompt-path PERSIST_FAILED record.
- Note: `LOCK_SETTINGS_REVIEW_SAVED` has two owners until S18-ii-h removes the ViewModel's diagnostics (the controller on the 1b path, the 1a ViewModel on the legacy path). No emitter or allowlist change was made; dormancy holds (no production caller of `requestReviewSave`).

Filter (targeted-unit-test): `*AppLockControllerReviewSaveTest*,*AppLockControllerOpenOriginSuccessTest*,*AppLockControllerNoticeTest*,*AppLockControllerIllegalTransitionTest*,*AppLockControllerRereadTest*`. Because the compile error blocked the whole module, a first run should be the production-set filter above (`*AppLockController*,*AppLockSettingsOperations*,*ReviewSaveAuthorisation*,*AppLockSettingsRepositoryImpl*,*AppLockPrivacySettingsViewModel*,*LockNoticeDerivation*`) so every touched class compiles and runs once.
- S18-ii-d batch 2 (2026-10-27): targeted vr-20261027-090941-4bee9de3 FAILED on exactly one pin test, "no public method takes an operational state …". The two new controller entries take `AppLockPrivacySettings`, and onReviewSavedWithoutPrompt also takes the committed flag. Fix (exact additions, the same pattern as i-a..i-b2): AppLockPrivacySettings joins the allowed parameter types (a bounded record of booleans and enums, with no text or platform detail), and "onReviewSavedWithoutPrompt" joins committedFlagMethods. The remaining jobs were held and are re-queued.

### S18-ii-d — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes

Applied (2026-10-27; NOT compiled or run; no validation was running):

- ISSUE-1 (MAJOR), applied: `applyReviewSaveCommittedLocked(record, direct)` skips the state move when `direct && ReviewSaveAuthorisation.isWeakening(record) && current !is AppLockState.Inert`. The writeSequence bump, the kept-snapshot drop, the view replacement and the `LOCK_SETTINGS_REVIEW_SAVED` claim are unchanged (the store did commit). `onReviewSavedWithoutPrompt` passes `direct = true`; the prompt path (`applyOpenOriginSettingLocked`, token-bound) passes `direct = false` and still moves from the token. Existing direct-call tests were checked: all use `notWeakening` (committed) or `committed = false`, or start from LOCKED, so none expected the old behaviour and no seed changed. New tests in `AppLockControllerReviewSaveTest`: a direct committed `weakeningOff` from UNLOCKED stays UNLOCKED, the view is replaced (`isPersistentUnreadable` false), `REVIEW_SAVED` is claimed once, and an in-flight re-read is dropped (proves the writeSequence bump); control: the same call from INERT moves to DISABLED.
- ISSUE-2 (pin hygiene), applied: in `AppLockControllerIllegalTransitionTest` `AppLockPrivacySettings` left the global allowed parameter types; it is allowed for `{startReviewSaveAttempt, onReviewSavedWithoutPrompt}` only (`reviewSaveRecordMethods`, like `committedFlagMethods`). Comment corrected (no text or platform detail; the lock flag selects the T27 target and is guarded by ISSUE-1).
- ISSUE-3 (doc), applied: LEGAL_PATHS WRITE 2b reworded: a non-commit (Failed, Rejected(UNREADABLE), throw; and on the PROMPT path also Rejected(NOT_UNDER_REVIEW)) records `PERSIST_FAILED(REVIEW_SAVE)`; the direct-path asymmetry note is kept; the ISSUE-1 guard is added to the T27 description.
- ISSUE-4 (comment only), applied: `AppLockPrivacySettingsViewModel` Rejected branch comment notes it also covers a stale card under D8 and goes away with S18-ii-h.

Filter (targeted-unit-test): `*AppLockControllerReviewSaveTest*,*AppLockControllerIllegalTransitionTest*,*AppLockControllerOpenOriginSuccessTest*,*AppLockControllerNoticeTest*,*AppLockControllerRereadTest*,*AppLockSettingsOperations*`.
- S18-ii-d ACCEPTED (2026-10-27).
  - Final-tree runs, all PASS: targeted vr-20261027-101007-5ee0b722, cancellation vr-20261027-101511-4a6c538c, known_good_state vr-20261027-101529-5ade27e0, architecture-contracts vr-20261027-101830-8d781945, domain-n-z vr-20261027-101916-d945f25c.
  - Earlier on the pre-final-fix production tree: data vr-20261027-092238-e4f1dc45, runtime-ui vr-20261027-092654-a36cc046. The final fixes touched the controller, a test, LEGAL_PATHS and a ViewModel comment only.
  - Gates: privacy-security-guardian ACCEPT-WITH-CONDITIONS (C1, C2, F3); reviewer-strict ACCEPT-WITH-CONDITIONS (ISSUE-1 MAJOR guard, ISSUE-2..4).
  - Committed.
  - Next: S18-ii-e (T19, INERT disable without auth, conditions (a)–(f)). F2 is binding: bump writeSequence on commit, with a D6 test.

## S18-ii-e — implemented

Applied by the implementer (2026-10-27). NOT compiled or run; no validation was run. DORMANT (no new production caller). No schema impact. No new diagnostic code, no new emitter entry (existing `LOCK_DISABLED`, `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)`).

Files:
- `domain/privacy/applock/AppLockController.kt`: new `InertDisableBegin` (Begun(id) / ReviewRequired / NotInOrigin); `beginInertDisable()` and `onInertDisableApplied(id, committed)`; token fields `pendingInertDisableId` / `lastInertDisableId` (own id sequence, not an attempt id); the stale "TECH-13 ... no controller entry" seam comment extended. There was no `TODO(S18-ii...)` seam belonging to T19 in the controller (only the unrelated `TODO(S18-ii-c D9)` and `TODO(S25)` exist).
- `domain/privacy/applock/AppLockSettingsOperations.kt`: `SettingsRequestResult.REVIEW_REQUIRED`; `requestDisable()` keeps its non-suspend signature and gets the INERT branch (`requestInertDisable`, `runInertDisableApply`).
- `docs/architecture/LEGAL_PATHS.md`: WRITE 3 T19 branch and the pin sentence.
- Tests: new `AppLockControllerInertDisableTest`; modified `AppLockControllerInertWeakeningTest` (the "disable in INERT is NOT_IN_ORIGIN" assertion became the T19 behaviour; a NOT_IN_ORIGIN case for a fresh AVAILABLE / CHECK_FAILED was kept; nothing deleted); `AppLockControllerIllegalTransitionTest` (+2 public-method names, +2 signature assertions, `onInertDisableApplied` added to `committedFlagMethods` for that method only, +2 names in the single-caller `forbidden` and `settingsOperationsOnly` sets; no new allowed parameter type was needed).

How the conditions are met:
- (a) `requestDisable()` while the state is INERT: the capability is read fresh at request time (`readCapability()`, a failed read is CHECK_FAILED). AVAILABLE or CHECK_FAILED gives `NOT_IN_ORIGIN`, nothing written. The decision is RE-PROVED inside the shared `AppLockSettingsWriteSerializer` before the write (state still INERT, FRESH capability still neither AVAILABLE nor CHECK_FAILED, store readable), as in S18-ii-d C1. Tests: stale AVAILABLE, CHECK_FAILED, throwing capability read, held-serializer re-proof (capability change, state change with a no-auth capability restored, store turned unreadable).
- (b) The apply is id-bound: `beginInertDisable()` records a one-shot token under the monitor, `onInertDisableApplied(id, committed)` consumes it only for the exact id and re-checks under the monitor that the state is still INERT with no open attempt before moving to DISABLED. `applyRunning` is claimed with `compareAndSet` before the begin (so two requests cannot both begin) and cleared in the apply's `finally` (and on every path that does not launch). The shared serializer wraps the pre-proof, the write and the controller entry. Tests: wrong/reused ids, replaced token, ALREADY_IN_FLIGHT for a second disable and an enable while one is held at the serializer.
- (c) DISABLED only after `Committed`. A failure (Failed, Rejected, a thrown store exception) stays INERT and claims `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)` once per process (claim-once, second failure records nothing); success emits `LOCK_DISABLED`. If INERT ended during the write the commit is still taken (view lock off, `LOCK_DISABLED`) but the state is NOT moved to DISABLED (fail closed). Tests cover each.
- (d) `INERT(SETTINGS_UNREADABLE_NO_AUTH)` or a null/unreadable load state gives `REVIEW_REQUIRED` (checked under the monitor in `beginInertDisable`, before the capability, so it applies for every capability). Nothing is written or recorded; the review-save path still works afterwards (tested).
- (e) No new production caller. `requestDisable` was already in the dormancy list; `beginInertDisable` and `onInertDisableApplied` joined the single-caller pins (Operations only).
- (f) No re-post: no `DiscreetRepostTrigger` call on disable (follows the T18 pattern and the INV-13 rule: only a false-to-true effective edge sweeps, and a disable only loosens the forced value). The F7 re-read (`settleCancelledWrite` with `{ !it.appLockEnabled }`) settles a cancelled write; the `CancellationException` is rethrown by `applyThenRethrow` (tests grab the apply's job and assert `isCancelled`, plus the committed / not committed / throwing re-read variants). No new `catch` was added.
- F2 (BINDING): a committed `onInertDisableApplied` bumps `writeSequence` (also when no token matches, like `onOpenOriginSettingApplied`). D6 tests: `F2 D6 a re-read still in flight when a T19 write commits drops its result` (in-flight re-read is dropped) and the control `D6 a T19 write that did not commit does not drop an in-flight re-read`. The `onInertDisableApplied` also clears the pending background on the move to DISABLED.

Design notes and deviations:
- `requestDisable()` stays non-suspend (many existing tests call it from non-suspend code). So the T19 write runs on the application scope (ATOMIC launch, like the prompt applies) and the request returns `SettingsRequestResult.STARTED`; the outcome is observed in `AppLockState` and the diagnostics. The plan did not name a result for the success path; `STARTED` is the existing one with that meaning.
- The new result `SettingsRequestResult.REVIEW_REQUIRED` is the one enum addition.
- `beginInertDisable()` has no argument. The capability check lives in Operations (before and inside the serializer), as for review-save. An unused token (capability refused after the begin) lingers until replaced; it can be consumed only by its exact id, which only the launching apply holds.
- A coverage gap: `!readable` with a non-NO_AUTH INERT reason (a readable-state INERT whose view later became unreadable) has no test, because no harness path produces it (a transient-unreadable view while INERT(NO_CREDENTIAL) only arises through T28 which then makes the view Loaded). The branch is one line in `beginInertDisable`.

Risks / likeliest findings (nothing was run):
- Race tests rely on T15 (`onAppBackgrounded` then `onAppForegrounded` with an AVAILABLE capability, within the timeout) moving INERT to UNLOCKED; they assert only "not INERT and not DISABLED".
- The D6 control test assumes a T28 re-read from INERT applies a lock-off read as DISABLED.
- Held-serializer tests rely on the unconfined test dispatcher resuming the queued apply after the mutex unlock (`h.settle()` follows `holder.await()`), as the S18-ii-d C1 tests do.
- The F7 rethrow tests rely on a launch that ends by a `CancellationException` reporting `isCancelled` on its job.

Validation to request (serial, validation-runner):
- targeted-unit-test `*AppLockController*,*AppLockSettingsOperations*`
- shards domain-n-z and architecture-contracts
- registered-guard cancellation and known_good_state
Gates: privacy-security-guardian (lead), reviewer-strict.

### S18-ii-e — privacy review (ACCEPT-WITH-CONDITIONS) and fixes

Applied (2026-10-27; NOT compiled or run; no validation was running):

- Finding 1 (test gap), applied: in `AppLockControllerInertDisableTest` "T19 INERT ending during the write ...", after `gate.complete` the test asserts the moved state is `UNLOCKED`, then `onAppBackgrounded()`, `h.clock.advance(120_000L)` (past M1), `onAppForegrounded()`, and asserts the state is still `UNLOCKED` with no prompt. Reasoned: deleting the view update in `onInertDisableApplied` (Ctl ~:889-894) leaves the view at lock on with M1, so the foreground after 120 s T14-locks and the assertion fails.
- INFO 2, accepted (D1-like residual): if the device regains authentication between the in-serializer re-proof and the NonCancellable commit, the store keeps the user's lock-off and the state is not relaxed. One sentence added to LEGAL_PATHS WRITE 3 T19 (e).
- INFO 5, 6, 8, accepted: (5) the lingering unused token is not exploitable (consumable only by its exact id, held by the launching apply); (6) the re-proof checks store readability only; (8) the Timber label "timeout pre-read" is reused.

BINDING carry-forwards for S18-ii-h / S20:
- (3) After the race, the session is UNLOCKED with the lock off; ENABLE is refused until a T18 with authentication or a cold start.
- (4) The UI maps REVIEW_REQUIRED for transient or null views to retry / `requestSettingsReread`, NOT to a review card.
- (7) The UI observes `AppLockState` and never infers success from `STARTED` (a silent no-op is possible). `requestDisable` is bound only to an explicit settings user action (no deep link, intent or worker).

Filter (targeted-unit-test): `*AppLockControllerInertDisableTest*`.

### S18-ii-e — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27)
- Applied (comments and docs only):
  - Finding 1: the beginInertDisable KDoc says the capability check is made AFTER begin, so REVIEW_REQUIRED applies whatever the capability.
  - Finding 2: the controller KDoc and LEGAL_PATHS WRITE 3 (e) no longer say "fail closed" / "NOT relaxed". The state is not moved, and the view follows the commit: an UNLOCKED session stays open with the lock off, while a LOCKED state stays LOCKED.
  - Finding 3: the REVIEW_REQUIRED KDocs mention retry or re-read for temporarily unreadable views.
- Finding 4 (condition): re-run targeted `*AppLockControllerInertDisableTest*` on the final test file.
- INFO 5: the in-serializer re-check reads the published state (the decisive move is checked under the monitor). Optional hardening later.
- S18-ii-e ACCEPTED (2026-10-27).
  - Final targeted vr-20261027-113534-0c07a9a1 PASS, on the final test file.
  - Production-tree runs, all PASS: targeted vr-20261027-111000-ac91e5ef, cancellation vr-20261027-111504-042ed693, known_good_state vr-20261027-111522-6fe89536, architecture-contracts vr-20261027-111823-dc197ba7, domain-n-z vr-20261027-111910-508a9c8c. Later edits were comment-only in production.
  - Gates: privacy-security-guardian PASS / ACCEPT-WITH-CONDITIONS; reviewer-strict ACCEPT-WITH-CONDITIONS (doc findings 1–3 fixed, re-run done).
  - Committed.
  - Next: S18-ii-f (finding 8: the repository write defaults become abstract), then S18-ii-g (needs owner D4).

## S18-ii-f — implemented

NOT validated; nothing was run. No production behaviour change (interface signature only; the production store already overrides every method).

- Production: `domain/privacy/applock/AppLockSettingsRepository.kt`. `setAppLockEnabled`, `setAutoLockTimeout`, `setAutoLockTimeoutIfNotWeakening`, `setDiscreetNotificationsIfNotForced` and `setSecureScreenIfNotForced` lose their fail-closed default bodies and are abstract. The three `TODO(S18 privacy review finding 8)` comments are resolved (KDoc now states "Abstract (S18 ii-f, finding 8)"). No other write method had a default (`setDiscreetNotifications` and `reviewSave` were already abstract).
- Verified: `data/privacy/applock/AppLockSettingsRepositoryImpl.kt` overrides all five (:117, :135, :153, :189, :192). `AppLockControllerTestFixtures.kt` `FakeAppLockSettingsRepository` already overrides all five with behaviour (:153, :179, :202, :236, :239), untouched. `AppLockControllerIllegalTransitionTest` only uses the class literal (reflection), untouched.
- Fakes touched (each now overrides the five methods, returning `Failed(field)`: APP_LOCK_ENABLED, AUTO_LOCK_TIMEOUT x2, DISCREET, SECURE_SCREEN; imports `AppLockSettingField` and `AutoLockTimeout` added where missing; no assertion changed):
  - `app/src/test/.../data/service/AndroidNotificationServiceTest.kt` (`FakeSettingsRepository`)
  - `app/src/test/.../data/service/AndroidNotificationServicePresentationTest.kt` (`PresentationFixtures.ScriptedSettingsRepository`, also used by `BillNotificationSweepParityTest`, which has no implementer of its own and needed no edit)
  - `app/src/test/.../domain/notification/presentation/NotificationPresentationResolverTest.kt` (`FakeRepository`)
  - `app/src/test/.../ui/screens/privacysettings/AppLockPrivacySettingsViewModelTest.kt` (`FakeRepository`)
  - `app/src/test/.../domain/privacy/applock/UpdateDiscreetNotificationsUseCaseTest.kt` (`FakeRepository`, same package, no import needed)
  - `app/src/androidTest/.../data/service/NotificationPresentationDeviceTest.kt` (`DeviceSettingsRepository`, fact F-e)
- No anonymous `object : AppLockSettingsRepository` and no other implementer exists in `app/src/test` or `app/src/androidTest` (grep of every `AppLockSettingsRepository` reference).
- Docs: LEGAL_PATHS WRITE 3 BLOCKING list records finding 8 as CLOSED (S18-ii-f; F3, F4, F7 were already CLOSED there). CODEBASE_SEGMENTS (AppLockSettingsOperations entry) records F3 and finding 8 as CLOSED.
- Still open BLOCKING: WRITE 5 routing / ISSUE-4 exit (S18-ii-g, owner D4), INV-45 ViewModel routing (S18-ii-h with S20, owner D1). Dormancy holds: no new caller of any write method.

Validation to request (serial, via validation-runner):
1. targeted-unit-test `*AndroidNotificationService*,*NotificationPresentationResolver*,*AppLockPrivacySettingsViewModel*,*UpdateDiscreetNotificationsUseCase*,*AppLockController*,*AppLockSettingsOperations*,*BillNotificationSweepParity*`
2. shards domain-n-z, data, runtime-ui
3. registered-guard known_good_state
4. app-check (includes the androidTest compile for `NotificationPresentationDeviceTest`, fact F-e)
- S18-ii-f ACCEPTED (2026-10-27).
  - Runs, all PASS: targeted vr-20261027-115625-77ce0e49, known_good_state vr-20261027-120915-3fdf80eb, domain-n-z vr-20261027-121217-3a2f7221, data vr-20261027-121403-63026730, runtime-ui vr-20261027-121804-a6868dd0, app-check vr-20261027-122006-066ef4a7 (androidTest compile, F-e).
  - Gate: reviewer-strict with the privacy checklist (gates merged, owner-approvals 48), ACCEPT-WITH-CONDITIONS. ISSUE-1 fixed after validation, docs only: the CODEBASE_SEGMENTS :603 open items are now WRITE 5 routing/ISSUE-4 (S18-ii-g, D4) and the INV-45 ViewModel routing (S18-ii-h, D1).
  - Committed.
  - The app-lock lane is now BLOCKED on owner decisions:
    - D4, test deletion for S18-ii-g;
    - D1, S18-ii-h merges with S20;
    - D9, the LOCK_SETTINGS_RECOVERED emitter entry.
  - S19 can start, since D11 takes it off the BLOCKING list.

## S19 — implemented

Status (2026-10-27, implementer): implemented, NOT validated (no build, test, lint or guard run). DORMANT: no Hilt, no injection, no production caller (S20 wires it), no change to MainActivity or MainScreen, no change to the controller, no schema/DB impact, no guard/allowlist/baseline change, no new diagnostic code, no new dependency.

Files (all new; nothing existing was edited except this handoff):
- `app/src/main/java/com/yourname/expensetracker/ui/applock/AppLockGate.kt`: `AppLockGate` (parameters) and a second overload over the plain interface `AppLockGateSource { state; notice }`; `AppLockGateRender` and `AppLockState.toGateRender()`.
- `.../ui/applock/AppLockSurface.kt`: `AppLockSurface`, `LockSurfaceMode`, `LockSurfaceMessage`, `AppLockUiTags`.
- `.../ui/applock/LockNoticeBanner.kt`: `LockNoticeBanner` plus the pure `bannerCopy()` / `bannerActions()` helpers (internal).
- `app/src/main/res/values/strings_applock.xml`, `values-es/strings_applock.xml`, `values-el/strings_applock.xml`.
- Tests: `app/src/test/.../ui/applock/AppLockComposeTestBase.kt` (shared rule + `ContentSlotProbe`/`ProbeContent`; the existing registerActivity + `createComposeRule` Robolectric pattern of `AppLockPrivacySectionTest`), `AppLockGateTest.kt`, `AppLockGateOpenOriginPromptTest.kt`, `AppLockSurfaceTest.kt`, `LockNoticeBannerTest.kt`. No existing test touched or deleted.

Gate contract for S20:
- `AppLockGate(state, notice, inAppActionsAllowed, onUnlock, onOpenSecuritySettings, onOpenAppLockSettings, onReviewSettings, modifier, unlockMessage = null) { content }`, or `AppLockGate(source: AppLockGateSource, ...)`. S20 adapts `AppLockController.state` / `.notice` to `AppLockGateSource` (the controller does not implement it) and maps `AppOperationalState` to `inAppActionsAllowed` (true only for Normal and BackupExporting; the gate takes a Boolean so `ui/applock` gets no new `data.backup` import).
- `onUnlock` is S20's `controller.onUnlockTapped()`. The gate never calls the controller, the authenticator, the clipboard, a permission API, navigation or a link lookup. Callbacks fire only from a user tap.

How each binding is met:
- INV-26 / AE-38: `when (state.toGateRender())`; `content()` exists only in the OPEN branch. RESOLVING gives the neutral surface (no text, no button); LOCKED and AUTHENTICATING(UNLOCK) give the lock surface. The content slot and the banner are composed 0 times there (tests: gate matrix, probe).
- INV-53 / AE-113, AE-114: AUTHENTICATING is mapped by `purpose.isOpenOrigin` only, so ENABLE and CONFIRM_CHANGE take the same branch as DISABLED / UNLOCKED. The slot is one stable `Box` after the conditional banner group, so it is neither disposed nor rebuilt. Probe tests: DisposableEffect entered 1 / disposed 0, `LaunchedEffect(Unit)` ran 1, one distinct non-saveable `remember` token, lock surface 0 across prompt open / close / retry / success. FLAG_SECURE is not touched here (S20).
- INV-51 / AE-99, AE-101, AE-115 and S18-ii-c ISSUE-1: the notice is a parameter and is composed ONLY inside the OPEN branch, so a torn sample (stale notice with a closed state) can never draw a banner, whatever sampling produced the pair. The `AppLockGateSource` overload also collects both flows with `collectAsState()` (composition's Main dispatcher). During open-origin prompts the controller keeps the origin notice and the gate passes it through; tested continuity across CONFIRM_CHANGE. UNLOCK prompt: slot 0, no banner (even with a stale notice passed).
- R2 collector constraint: no Main.immediate / Unconfined collection; the collection is read-only and never calls a transition or the authenticator. Nothing in the gate calls the controller.
- INV-34 / AE-44, AE-46, AE-118: Unlock enabled only when `state is Locked`; disabled while AUTHENTICATING(UNLOCK); re-enabled when the state is back at LOCKED (T9, T10, T35 incl. host loss). No auto-prompt, no effect or timer calls `onUnlock` (tested: 0 callbacks across all states and transitions).
- INV-27 / INV-52 / AE-62, AE-108: the operational lock screen stays inside `content` (unchanged). `inAppActionsAllowed = false` removes the in-app banner actions (not just disables them); the system-intent action "Open security settings" stays; a review banner then has no action. Origin vs prompt rendering is identical for every notice x allowed combination (tested).
- INV-35 / AE-69: lock surface shows only constant resource strings (tested by collecting all text), single scrollable centred column, 56 dp button, status area reserves 2 lines, polite live region only when non-empty, heading semantics, decorative icon, start/end padding only (RTL test), 200% font scale test.
- S18-ii-e carry-forwards 3, 4, 7: they concern the settings UI and `requestDisable`; the gate has no settings, `requestDisable`, `requestSettingsReread` or `SettingsRequestResult` surface at all. REVIEW_REQUIRED is therefore never mapped to a review card here, STARTED is never read, and the gate infers no success from anything; it renders only the published `AppLockState`. The review banner's "Review settings" action is a plain callback that S20 / S18-ii-h route to the settings review section (user tap only).
- N1 (debounce `requestSettingsReread`): not applicable to the gate (it has no retry control); stays with S20 / S18-ii-h.

Decisions and risks:
1. `LockNotice.BiometricInvalidated` (Inc 2, PIN) draws nothing in 1b: there is no 1b copy and no dismiss API on the controller, and the controller never derives it in 1b. Revisit in Inc 2 (S25+).
2. The status line (try again, too many attempts, fingerprint locked, unavailable now) is supplied by the caller through `unlockMessage`; the controller publishes no outcome message today, so S20 must decide the source (for example the outcome of `onUnlockTapped()` / the prompt host). With `unlockMessage = null` only the T5 "Couldn't check the lock setting" line appears (cause SETTINGS_UNREADABLE). No status line during the prompt.
3. Initial TalkBack focus on the headline (ux-flows) is not forced; there is no auto focus jump to the button, which is the intended property. A device check belongs to G1b.
4. The Back behaviour (background the task) on the lock surface is S20's (Activity level).
5. ux-flows says banners reuse the `PrivacyBlockedCard` pattern; a small sibling was added (neutral warning colour, no error colour) instead of generalising the shared card. Design colours: `SemanticColors.WarningOrange` at 18% alpha container.
6. Compose tests rely on the Robolectric activity registration workaround (same as `AppLockPrivacySectionTest`); no new test dependency.
7. The test-only `ContentSlotProbe` observes entry/dispose per composition; "banner not removed" in AE-115 is asserted at idle after each transition (the structure, not a frame trace, guarantees no flicker).

es / el DRAFT strings for owner sign-off (all new keys in `strings_applock.xml`; apostrophes are escaped in the en file; `applock_banner_description` is the format string "%1$s. %2$s" in all three, the title then the body, one TalkBack stop):
- applock_lock_title: es "App bloqueada" / el "Η εφαρμογή είναι κλειδωμένη"
- applock_lock_subtitle: es "Desbloquea para continuar." / el "Ξεκλειδώστε για να συνεχίσετε."
- applock_unlock_button: es "Desbloquear" / el "Ξεκλείδωμα"
- applock_msg_try_again: es "No se pudo desbloquear. Inténtalo de nuevo." / el "Αδυναμία ξεκλειδώματος. Δοκιμάστε ξανά."
- applock_msg_too_many_attempts: es "Demasiados intentos. Espera un momento y vuelve a intentarlo." / el "Πολλές προσπάθειες. Περιμένετε λίγο και δοκιμάστε ξανά."
- applock_msg_biometric_locked: es "La huella está bloqueada. Usa el bloqueo de pantalla." / el "Το δακτυλικό αποτύπωμα είναι κλειδωμένο. Χρησιμοποιήστε το κλείδωμα οθόνης."
- applock_msg_unavailable_now: es "El desbloqueo no está disponible ahora. Inténtalo de nuevo en un momento." / el "Το ξεκλείδωμα δεν είναι διαθέσιμο αυτή τη στιγμή. Δοκιμάστε ξανά σε λίγο."
- applock_msg_setting_unreadable: es "No se pudo comprobar el ajuste de bloqueo. Desbloquea para continuar." / el "Αδυναμία ελέγχου της ρύθμισης κλειδώματος. Ξεκλειδώστε για να συνεχίσετε."
- applock_banner_inactive_title: es "El bloqueo de la app está en pausa" / el "Το κλείδωμα εφαρμογής είναι σε παύση"
- applock_banner_inactive_body, _body_unavailable, _body_os, _body_unreadable, applock_banner_open_settings, applock_banner_open_security_settings, applock_banner_review_title, _body, _action: see the two locale files (drafts, same status).
- Wording consistency note for the owner: S15's es/el prompt strings say "bloqueo de la app" / "κλείδωμα εφαρμογής"; S19 follows them, but uses "App bloqueada" / "Η εφαρμογή είναι κλειδωμένη" for the headline.

Validation to request (serial, via validation-runner):
1. targeted-unit-test `*AppLockGate*`, `*AppLockSurface*`, `*LockNoticeBanner*` (one combined run; the new `AppLockComposeTestBase.kt` is compiled with them)
2. shard `runtime-ui`
3. `lint` (new resources: es/el parity, apostrophe escaping, unused resources are all referenced from code, hardcoded text)
4. no static-guards or app-check needed for S19 alone unless a guard-relevant production file changed (none did); they run at the next candidate.
Gates for S19: privacy-security-guardian (lead), reviewer-strict, architecture-guardian. Items for them: no production caller (grep for `AppLockGate(` outside tests must be empty), notice composed only in OPEN, `inAppActionsAllowed` semantics, compile-risk check of the test file (first compile, Compose test APIs: `assertTextEquals("")` on the empty status line, `SemanticsConfiguration.getOrNull`).

### S19 — privacy review (ACCEPT-WITH-CONDITIONS) and fixes

Status (2026-10-27, test-author): test-only fixes applied; NOT validated (no build, test, lint or guard run; validation HELD). No production file changed.

Findings applied:
1. Finding 1 (torn pairs through the SOURCE overload, S20's production path): `AppLockGateTest` has three new tests over a mutable `AppLockGateSource` that starts at `Locked` + `ReviewSettings`. Notice-before-state and state-before-notice are published through the open and close transitions (also through `Authenticating(UNLOCK)` and `Resolving`), stepwise with idle between writes and back to back in both orders. Whenever the state is closed: `NOTICE_BANNER` count 0, live probe entries (entered minus disposed) 0, no probe node, no content slot, no probe text; the first closed phase also asserts `probe.entered == 0`.
2. Finding 2: `AppLockSurfaceTest.collectText` now also collects `SemanticsProperties.ContentDescription`; `AppLockGateTest.assertClosedRendering` asserts `onAllNodesWithText(PROBE_TEXT, substring = true, useUnmergedTree = true)` count 0.
3. Finding 3: new `AppLockGateSourceScanTest` (feature-scoped, not a registered guard; same source-root pattern as `AppLockBiometricSourceScanTest`, comments and strings blanked) asserts `AppLockGate.kt` contains none of `immediate`, `Unconfined`, `collectAsStateWithLifecycle`, `LaunchedEffect`, `SideEffect`, `DisposableEffect`; two self-checks prove it can fail.

INFO (no change required): 4 and 5 recorded as informational by the review; 6 = G1b TalkBack device checks (initial focus on the headline, no focus jump to Unlock, banner as one TalkBack stop).

BINDING carry-forwards for S20:
- CF-1: back `AppLockGateSource` directly with `controller.state` / `controller.notice` (no `stateIn` or `map` with an open initial value).
- CF-2: use the source overload, never `collectAsStateWithLifecycle`, `Main.immediate` or `Unconfined`.
- CF-3: `onOpenSecuritySettings` is a direct system intent (constant action, no extras, not through the MainViewModel navigation channel); `ActivityNotFoundException` is handled with a bounded code; `onOpenAppLockSettings` / `onReviewSettings` re-check the operational state at tap time.
- CF-4: derive `unlockMessage` only from bounded outcome enums, cleared when leaving LOCKED or when a new lock episode starts.
- CF-5: no Activity-level dialog or overlay outside the gate (FLAG_SECURE inheritance, INV-29).
- CF-6 (G1b device check): no content frame drawn after a re-lock at APP_FOREGROUNDED; confirm no activity-scoped ViewModel holds clipboard listeners or permission flows across re-lock.

Validation to request (serial, via validation-runner): targeted-unit-test `*AppLockGate*`, `*AppLockSurface*`, `*LockNoticeBanner*` (one combined run; `*AppLockGate*` also matches the new `AppLockGateSourceScanTest`).

### S19 — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes

Status (2026-10-27, implementer): fixes applied; NOT validated (no build, test, lint or guard run; validation HELD).

Findings applied:
1. Finding 1 (window insets; the app is edge-to-edge, targetSdk 35, `MainActivity` `enableEdgeToEdge`):
   - `AppLockGate.kt` OPEN branch: the notice banner gets `Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(Top + Horizontal))`. The content slot `Box` (same node, same tag, same position) gets `consumeWindowInsets` of the same insets through a conditional `.then(...)` modifier, only while a banner is composed. No wrapping composable was added or removed, so slot identity is kept (INV-53): the banner toggle changes a modifier, not the composition structure.
   - `AppLockSurface.kt` LOCK branch: `windowInsetsPadding(WindowInsets.safeDrawing)` inside the navy background, so the scrollable column never goes under the system bars. The NEUTRAL surface is unchanged (draws nothing).
   - Existing probe tests still hold by reasoning (entered/disposed/launched are driven by the slot's position in the composition, which is unchanged). New test `toggling the banner keeps the same content slot under the conditional insets modifier` toggles the notice twice in `Unlocked` and asserts entered 1, disposed 0, launched 1, one `remember` token.
2. Finding 2 (fail-closed exhaustiveness, test-only): the `AppLockGateTest` mapping test iterates `AuthPurpose.values()` and compares `Authenticating(id, purpose).toGateRender()` with an exhaustive `when (purpose)` with no else (`UNLOCK` -> `LOCK_SURFACE`; `ENABLE`, `CONFIRM_CHANGE` -> `OPEN`), so a new purpose does not compile until it is classified.

INFO (no change): the RTL test is weak; the scan scope covers the gate file only (`AppLockGateSourceScanTest`); banner contrast and the TalkBack live-region announcement are G1b device checks.

S20 note: with the insets now handled in the gate, `MainScreen`'s Scaffold insets must not double-pad when a banner is shown. The content slot consumes Top + Horizontal safeDrawing insets only while a banner is composed; verify in S20 that the Scaffold sees the consumed insets (no double top padding) and that without a banner it still pads normally.

Validation to request (serial, via validation-runner): targeted-unit-test `*AppLockGate*`, `*AppLockSurface*`, `*LockNoticeBanner*` (one combined run).
- S19 final batch: targeted vr-20261027-135835-50132250 FAILED to compile, because the tests called `SemanticsConfiguration.getOrNull` without an import (AppLockSurfaceTest :157-158, LockNoticeBannerTest :151). Fix (test-only): use the member `getOrElseNullable(key) { null }`. The remaining jobs were held and are re-queued.
- S19 ACCEPTED (2026-10-27).
  - Final-tree runs, all PASS: targeted vr-20261027-140842-3d486312, runtime-ui vr-20261027-141113-ab437ac3, architecture-contracts vr-20261027-141330-fc654f8d, lint vr-20261027-141416-1aa69e1d.
  - Gates: privacy-security-guardian ACCEPT-WITH-CONDITIONS (findings 1–3 applied; CF-1..6 BINDING on S20); reviewer-strict ACCEPT-WITH-CONDITIONS (insets and the exhaustive purpose mapping applied).
  - The es/el strings are DRAFTS pending the owner's native sign-off.
  - Committed.
  - The app-lock lane is now BLOCKED on owner decisions D4 (S18-ii-g), D1 (S18-ii-h with S20) and D9 (the LOCK_SETTINGS_RECOVERED emitter entry). S20 needs S18-ii-g and S18-ii-h first.

## 2026-10-27 OWNER DECISIONS D1, D4 and D9 (owner-approvals item 51a)
- The owner replied "I approve" to the orchestrator's recommendations:
  - D4 option (a): retire UpdateDiscreetNotificationsUseCase and the unforced setDiscreetNotifications in S18-ii-g. Deleting UpdateDiscreetNotificationsUseCaseTest and the Impl setDiscreetNotifications cases is approved.
  - D1: S18-ii-h merges together with S20.
  - D9: the PrivacyDiagnosticsEmitter allowlist entry LOCK_SETTINGS_RECOVERED (CAUSE: IO_ERROR or TIMEOUT), emitted at the TODO(S18-ii-c D9) seam in applyRereadLocked.
- The es/el native sign-off is still open; the strings stay DRAFTS.
- Next: one lane-A batch, D9 (the S18-ii-c remainder), then S18-ii-g, in a single implementer pass. Then the guardians, validation, and the S18-ii candidate static-guards plus app-check (after g). S18-ii-h waits for S20.

### S18-ii-c D9 + S18-ii-g implementation (2026-10-27, implementer; NOT validated)

Status: Part 1 (D9) IMPLEMENTED. Part 2 (S18-ii-g) NOT STARTED: STOPPED on a Hilt-graph blocker (below). Nothing was built or run.

Files changed (D9):
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/applock/PrivacyDiagnosticsEmitter.kt`: new enum entry `LOCK_SETTINGS_RECOVERED` (COMPLETED, INFO, allowed keys `{CAUSE}`), after `LOCK_SETTINGS_REVIEW_SAVED`. The emitter allowlist is by KEY, so the value limit (IO_ERROR or TIMEOUT) is enforced by the controller, which only emits a non-persistent prior cause. No config/guards file, registry JSON or pinned-count test needed a change (no existing pin matches the `LOCK_SETTINGS_` prefix).
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/applock/AppLockController.kt`:
  - `applyRereadLocked(read, capability, out: MutableList<PendingDiagnostic>)` now returns Unit. It captures the PRIOR `Unreadable.cause` (only if not persistent) BEFORE `loadState = read`, and adds `claimCodeCause(LOCK_SETTINGS_RECOVERED, cause)` to `out` (once per process per (code, cause), `claimedCodeCauses`). A new INERT diagnostic is added to `out` as well, so both are recorded.
  - Both callers (`onSettingsReread`, `applyKeptRereadAfterClose`) collect into a list inside `transition<Unit> { }` and call `record(it)` after the monitor is released. The T29 kept-snapshot path therefore also emits.
  - The `TODO(S18-ii-c D9)` is removed. KDoc of `PendingDiagnostic` and `record` mention the new code. No new catch; no CancellationException handling changed.
- `docs/architecture/LEGAL_PATHS.md`: the READ + RECOVERY paragraph now states the D9 emission instead of the TODO.
- This handoff section.

Tests: none edited or deleted by the implementer. `AppLockControllerRereadTest` header still says the emission is "NOT covered" (stale comment, test-author's file).

Pins changed: none. Tests deleted: none.

Blocker for S18-ii-g (decision needed, nothing edited for it):
- Routing `AppLockPrivacySettingsViewModel.setDiscreetNotifications` to `AppLockSettingsOperations.requestDiscreetChange` makes the `@HiltViewModel` inject `AppLockSettingsOperations` -> `AppLockController`. The controller's constructor takes the `DeviceAuthenticator` port, which has NO Hilt binding (`AppLockModule` comment and S16 note at handoff :922/:948: S20 binds the process-level relay; the controller is "not reachable from any Hilt entry point until then"). The change would make the Hilt graph fail with a missing binding for `DeviceAuthenticator`, so every build and test run would fail.
- This contradicts D10 ("g lands before S20") unless g brings an interim binding. Options for the orchestrator or owner:
  1. Add a minimal fail-closed interim `DeviceAuthenticator` binding in g (a hostless authenticator that answers `HOST_UNAVAILABLE` for authenticate and no-ops cancel), replaced by S20's relay (S20 constraints a-e at :948 stay binding). New class plus `@Binds` in `AppLockModule`, plus a test and a hilt-bindings-map line. Neither this nor any guard/baseline effect was assessed. Needs approval because it is new wiring outside the g write set.
  2. Move the ViewModel routing (and the use-case retirement, since it depends on it) to S20 together with the relay and S18-ii-h.
  3. Inject a narrower port for the discreet write that does not pull in the controller (a design change to Operations; not in the plan).
- Everything else of g (use case and `setDiscreetNotifications` retirement, F3 pin flip, dormancy-pin entry, test deletions, doc updates) is unchanged and waits for this decision. Doc mentions found for that work: LEGAL_PATHS (:1556, :1678-1690, :1804-1823, :1859-1876), CODEBASE_SEGMENTS (:294, :598), CODEBASE_INVENTORY (:17), ENGINE_INTERACTION_MAP (:161-164), hilt-bindings-map (:382, :394).

### D9 tests (2026-10-27, test-author; NOT validated)

Written, not run. Files: `PrivacyDiagnosticsEmitterTest.kt` and `AppLockControllerRereadTest.kt` (domain/privacy/applock, test sources). No fixture change was needed. The stale "emission NOT covered" header of `AppLockControllerRereadTest` was replaced.

PrivacyDiagnosticsEmitterTest (INV-18 allowlist):
- `D9 LOCK_SETTINGS_RECOVERED allows exactly the CAUSE key` (also pins the LOCK_SETTING* family: PERSIST_FAILED, UNREADABLE, REVIEW_SAVED, RECOVERED)
- `D9 LOCK_SETTINGS_RECOVERED is stored as a completed info privacy event with the cause as an enum name`
- `D9 LOCK_SETTINGS_RECOVERED drops every key other than CAUSE and keeps CAUSE`
- `D9 LOCK_SETTINGS_RECOVERED without metadata stores none`

AppLockControllerRereadTest (INV-47, INV-37; payload is exactly `{CAUSE: <enum>}` and no recorded metadata holds a string):
- `D9 a T28 recovery from a transient IO_ERROR or TIMEOUT view records LOCK_SETTINGS_RECOVERED once with that cause`
- `D9 a recovery that keeps the state UNLOCKED still records LOCK_SETTINGS_RECOVERED`
- `D9 a foreground re-read records LOCK_SETTINGS_RECOVERED too`
- `D9 a recovery is recorded once, later re-reads and foregrounds record nothing more`
- `D9 each recovery records its own prior cause (IO_ERROR then TIMEOUT)` (retitled by the review fixes below)
- `D9 a stale re-read result records nothing and the newest result records exactly one recovery`
- `D9 a re-read dropped by a committed write records nothing, the later recovery records it`
- `D9 an unreadable or failing re-read is not a recovery and records nothing`
- `D9 a recovery to INERT records both the new INERT diagnostic and LOCK_SETTINGS_RECOVERED`
- `D9 a recovery from INERT to a non-INERT state records only LOCK_SETTINGS_RECOVERED`
- `D9 T29 a snapshot kept in LOCKED is recorded when the unlock applies it, not when it is kept`
- `D9 T29 a snapshot kept in AUTHENTICATING UNLOCK is recorded only when a later unlock applies it`
- `D9 T29 a snapshot kept during an open-origin prompt is recorded once the prompt ends in an open state`
- `D9 T29 a kept snapshot that INERT applies records both diagnostics after the unlock`
- `D9 T29 a kept snapshot that D6 drops or a newer unreadable result supersedes records nothing`
- `D9 a readable view never records LOCK_SETTINGS_RECOVERED`
- `D9 a persistent unreadable view never records LOCK_SETTINGS_RECOVERED, the re-read is not even made`
- `INV-37 a failing emitter does not change the recovery and does not escape` (retitled; now also a stuck-sink row)

Not testable through the public API: the once-per-(code, cause) dedupe inside ONE controller. A view that recovered is readable for good (only `resolve`, which is one-shot, ever sets an unreadable view), so a second recovery with the same cause cannot happen on the same instance; the claim set is per instance. Pinned instead: one record per recovery, later re-reads and foregrounds add none, and a different cause is its own claim. Persistent-unreadable: the re-read is never issued (`transientUnreadableLocked` gate in `launchReread`), so the guarantee asserted is no read and no emission, not a filter inside `applyRereadLocked`.

Runner filter: `*AppLockController*,*LockNoticeDerivation*,*PrivacyDiagnosticsEmitter*`.

### 2026-10-27 S18-ii-g RE-SEQUENCED into S20 (owner-approvals item 52, orchestrator; D10 revised)
- Routing the ViewModel through Operations would put AppLockController into the Hilt graph before the S20 relay binds DeviceAuthenticator, which fails with MissingBinding.
- Decision: g (plus the D4 retirement and its approved test deletions) moves into S20 together with h. No interim stub authenticator.
- Release prerequisites are already ancestors of this branch: R24 9b920430, R19 df769791, R18 0bd05c58. The "R18 HZ-03 F1 status" check is assigned to the S20 planner.
- Current batch: D9 only (AppLockController.kt, PrivacyDiagnosticsEmitter.kt, LEGAL_PATHS.md, and tests in PrivacyDiagnosticsEmitterTest and AppLockControllerRereadTest).

### D9 review fixes (2026-10-27, implementer; NOT validated)

Privacy-security-guardian verdict on D9: PASS (privacy), ACCEPT-WITH-CONDITIONS. All six findings applied in one pass. Nothing was built or run.

- F1 (production, `AppLockController.applyRereadLocked`): the prior-cause filter is now a positive allowlist, `takeIf { it == SettingsUnreadableCause.IO_ERROR || it == SettingsUnreadableCause.TIMEOUT }`, replacing `takeUnless { it.isPersistent }`. A cause added to the enum later is not emitted until it is admitted here. Behaviour is identical today (the transient set is exactly IO_ERROR and TIMEOUT). No test pin added: no public-API path reaches the difference (a re-read is only issued while `transientUnreadableLocked()`, which still uses `isPersistent`, and the enum has no other cause), so a pin would need a new enum entry. The existing `D9 a T28 recovery from a transient ...` test already asserts `transientCauses == {IO_ERROR, TIMEOUT}`, which fails if the enum grows.
- F2 (`AppLockControllerRereadTest`): the test "a different cause is a different claim ..." is retitled `D9 each recovery records its own prior cause (IO_ERROR then TIMEOUT)` (disclosure in the header kept). The tautological `!cause.isPersistent` assertion in `assertRecoveries` is removed.
- F3 (`AppLockControllerRereadTest`): the INV-37 test is retitled `INV-37 a failing emitter does not change the recovery and does not escape` and now loops over a "failing" row (`emitFailure`) and a "stuck" row (`emitSuspendsForever = true`). Each asserts the state still applies (`Disabled`), one attempt, `h.uncaught` empty, and no writes.
- F4 (`AppLockControllerRereadTest`): the stale-result test now holds BOTH reads with two gates, releases the older first and asserts no recovery and the state unchanged (`Unlocked`), then releases the newest and asserts exactly one recovery. Both gates are completed in `finally`.
- F5 (`AppLockControllerTestFixtures`): the `emittedMetadata` KDoc now says "the codes with a `CAUSE` key" instead of naming two codes.
- F6 (docs): `LEGAL_PATHS.md` READ + RECOVERY paragraph adds "The CAUSE value limit (IO_ERROR or TIMEOUT only) is enforced by the controller; emission is fire-and-forget (INV-37)." This handoff marks the old "D9 seam (OWNER GATE, not approved)" heading and the "S18-ii-c ACCEPTED ... without D9" line as SUPERSEDED by the D9 section, and the two retitled test names in the "D9 tests" list are updated.

Runner filter: `*AppLockController*,*LockNoticeDerivation*,*PrivacyDiagnosticsEmitter*`.

### S18-ii-c D9 ACCEPTED (2026-10-27)
- Gate: privacy-security-guardian PASS / ACCEPT-WITH-CONDITIONS. F1-F6 applied; the F1 positive cause allowlist is in production.
- Validation, all PASS:
  - pre-fix: targeted vr-20261027-202723-71c43899; domain-n-z vr-20261027-203811-f40c252d;
  - final tree: targeted vr-20261027-204259-eba2043b.
- Committed.
- Next: S20-a (relay, prompt host, lifecycle binder) per "## Amendment (2026-10-27) — S20 sub-slice plan" (owner-approvals item 55).

### S20-a implementation (2026-10-27, implementer; NOT validated)

Nothing was built, run or committed. Lane worktree `Cost-agregator-al`, branch `feature/app-lock-privacy-mode`, base bf885ee2. P = app/src/main/java/com/yourname/expensetracker. Dormant: nothing in production instantiates the relay (except the DI binding), the host or the binder. No schema impact. No pin changed, no guard/allowlist entry.

Files:
- N P/ui/applock/AppLockAuthenticatorRelay.kt (also holds the internal `AttemptAdopter` and `RelayRegistration`)
- N P/ui/applock/AppLockPromptHost.kt
- N P/ui/applock/AppLockLifecycleBinder.kt
- M P/ui/applock/BiometricPromptDeviceAuthenticator.kt (internal `adopt`, one KDoc bullet; no existing rule touched)
- M P/di/AppLockModule.kt (`@Binds @Singleton bindDeviceAuthenticator(impl: AppLockAuthenticatorRelay): DeviceAuthenticator`; comment replaced)
- M docs/architecture: hilt-bindings-map.md, LEGAL_PATHS.md (new section "App Lock Prompt Path and Lifecycle Signals"), CODEBASE_SEGMENTS.md, CODEBASE_INVENTORY.md, ENGINE_INTERACTION_MAP.md

Design:
- Relay. Main-thread only (injectable `onMainThread` seam, `check` otherwise). State: `current` (weak registration) and one `open` record (id, controller callback, weak holder or null = orphaned).
  - `authenticate`: no registration gives HOST_UNAVAILABLE (b); a record already open gives ALREADY_IN_FLIGHT; otherwise it records the attempt BEFORE the adapter call, passes the adapter a relay callback bound to (record, registration), and clears the record in `finally` unless the result is LAUNCHED.
  - Relay callback: forwards at most once, only while `open === record`, the id matches and the registration still holds the record; otherwise drops. The record is cleared before the controller callback runs.
  - `cancel(id)`: ignores an unknown id; routes to the holder adapter; if the holder is gone, or the adapter did not close the record (or threw), the relay delivers `Failed(SYSTEM_CANCELED)` itself, once and synchronously (c).
  - `register(adapter, adopter)`: becomes current; adopts an ORPHANED record (holder not alive) immediately through `adopter` (holder set first); refused adoption closes with the relay's own Failed(SYSTEM_CANCELED).
  - `unregister(registration, isChangingConfigurations): Long?`: clears current if equal. If this registration holds the record and !isChangingConfigurations, it removes the record and returns the id (once). If changing configurations it orphans the record. Otherwise null. (a), (d).
- Host. Unscoped `@Inject`; `attach(activity)` checks it is not attached, skips a DESTROYED activity, builds `BiometricPromptDeviceAuthenticator(activity)` (factory seam), registers it (the registration owns the adopter lambda `adapter.adopt`), and adds a lifecycle observer. At ON_DESTROY it removes itself and calls `relay.unregister(registration, activity.isChangingConfigurations)`; a non-null id gives `controller.onPromptHostLost(id)`. It does not touch the copy: the controller's constant copy goes controller → relay → adapter unchanged.
- Binder. `@Singleton`; `install(application)` uses `AtomicBoolean.compareAndSet`, registers the callbacks, launches resolve. Started count (main-confined): start increments; 0→1 calls onAppForegrounded unless `suppressNextForeground` is set (a config-change stop at 1→0 sets it, the next 0→1 consumes it); stop with count 0 is ignored; 1→0 with !isChangingConfigurations calls onAppBackgrounded. Every start while `controller.state.value is Resolving` launches resolve again. `launchResolve` rethrows CancellationException and logs other exceptions by class name only.
- Adapter `adopt(attemptId, callback): Boolean` (internal): false when SDK < 30, the prompt was not built, an attempt is in flight, or a terminal is awaited; otherwise sets `inFlight` and returns true. Terminals are still dropped by reference through the existing `complete`.

Assumptions:
- androidx.biometric 1.1.0 re-attaches the client callback of the `BiometricPrompt` built in the new Activity's constructor to the surviving prompt fragment (the adapter's existing KDoc and lifecycle contract rely on it). So the terminal reaches the new adapter's `libraryCallback`; `adopt` only supplies the attempt identity. Not verifiable without a device (AE-42, AE-118 are device cases).
- The controller's ids are unique, and it opens at most one attempt at a time; the relay therefore treats an open record with a different id as ALREADY_IN_FLIGHT (adapter parity) rather than self-healing a stale record. If the controller ever closes an attempt without an outcome and without Abandoned, the relay would keep refusing launches (HOST loss or the watchdog Abandoned cancel clear it). Not reachable by construction; residual risk.
- Activity order for a recreate is: old onStop, onDestroy(isChangingConfigurations=true), then new onCreate/onStart. For a CLEAR_TASK launch the new host may register before the old one is destroyed; the new host then does not adopt (live holder) and the old ON_DESTROY reports the loss.
- The binder is installed before the first Activity starts (S20-c installs it in onCreate).
- Public `AppLockPromptHost` and `AppLockLifecycleBinder` and `AppLockAuthenticatorRelay` are public classes (Hilt field injection from MainActivity); their test seams and registration types are internal.

Tests owed (test-author): AppLockAuthenticatorRelayTest, AppLockRelaySourceScanTest, AppLockPromptHostTest (Robolectric, sdk >= 30 for adopt), AppLockLifecycleBinderTest, BiometricPromptAdapterStateMachineTest (adopt cases). Scan note: the relay is referenced only by AppLockModule and AppLockPromptHost today.
Validation to request: compile; targeted `*AppLockAuthenticatorRelay*,*AppLockRelaySourceScan*,*AppLockPromptHost*,*AppLockLifecycleBinder*,*BiometricPrompt*,*AppLockBiometricSourceScan*,*AppLockController*`; shards runtime-ui and architecture-contracts; registered guard cancellation; Hilt graph compile (kapt/ksp) covers the new binding.

### S20-a tests (2026-10-27, test-author; NOT validated)

Nothing was built or run. T = app/src/test/java/com/yourname/expensetracker/ui/applock. Test files only; no production change.

New classes (T): AppLockAuthenticatorRelayTest (plain JVM), AppLockRelaySourceScanTest (plain JVM), AppLockPromptHostTest (Robolectric sdk 34; also holds the test-only `AppLockPromptHostTestActivity`, a FragmentActivity whose `isChangingConfigurations` the test drives), AppLockLifecycleBinderTest (plain JVM, mockk Application and Activities, StandardTestDispatcher). Modified: BiometricPromptAdapterStateMachineTest (adopt cases appended).

Test names:
- AppLockAuthenticatorRelayTest: mainThread_authenticateOffMain_throws_andReachesNeitherTheAdapterNorTheRecord, mainThread_cancelRegisterAndUnregisterOffMain_throw, mainThread_controlOnMain_allEntryPointsWork, mainThread_theOutcomeCallbackIsNotMainThreadChecked_andStillForwards, ruleB_noRegistration_returnsHostUnavailable_andNoCallbackFollows, ruleB_afterTheRegistrationIsCleared_returnsHostUnavailable_andTheAdapterIsNotCalled, ruleB_control_withARegistration_theRequestReachesTheAdapter, inv34_secondAuthenticateWhileOpen_returnsAlreadyInFlight_andTheFirstStillCompletes, inv34_afterTheOutcome_aNewAttemptIsAccepted, record_isOpenBeforeTheAdapterIsCalled_soAReentrantAuthenticateIsRefused, record_aReentrantOutcomeInsideAuthenticate_isDeliveredExactlyOnce, record_aReentrantOutcomeThenAThrow_stillDeliversOnce_andLeavesNothingOpen, record_removedOnEveryNonLaunchedResult_resultPassesThrough_andTheNextRequestIsAccepted, record_removedWhenTheAdapterThrows_theSameExceptionPropagates_andNoCallbackFollows, record_removedWhenTheAdapterThrowsCancellation_andItPropagates, record_control_launchedKeepsTheRecordOpenUntilTheOutcome, copy_everyValueReachesTheAdapterUnchanged_withTheSameId, forwarding_everyOutcomeKind_isForwardedUnchangedAndOnce, forwarding_aSecondOutcomeForTheSameAttempt_isDropped, forwarding_anOutcomeWithAWrongId_isDropped_andTheAttemptStaysOpenForTheRightOne, forwarding_aLateOutcomeOfAClosedAttempt_isDropped_evenWhenANewAttemptIsOpen, forwarding_theRecordIsClosedBeforeTheControllerCallbackRuns_soANewAttemptFromItIsAccepted, ruleC_cancelGoesToTheAdapterThatLaunchedTheId_notTheNewestRegistration, ruleC_cancelOfAnUnknownId_isIgnored_andTheAttemptStaysOpen, ruleC_cancelWithNothingOpen_isANoOp_withOrWithoutARegistration, ruleC_cancelOfTheOpenAttempt_deliversExactlyOneSystemCanceled_whenTheAdapterDeliversIt, ruleC_holderGone_cancelDeliversOneSyntheticSystemCanceled_synchronously_andNeverCallsAnAdapter, ruleC_adapterDidNotCloseTheRecord_cancelDeliversOneSynthetic, ruleC_adapterThatDeliversAWrongIdOnCancel_stillLeavesExactlyOneSyntheticForTheRightId, ruleC_adapterCancelThrows_theExceptionPropagates_andTheSyntheticIsStillDeliveredOnce, ruleC_adapterThatClosesThenThrows_deliversExactlyOnce, ruleC_aNewAttemptFromTheSyntheticCallback_isAccepted, adoption_registerAdoptsAnOrphanedRecord_handingTheAdapterTheIdAndACallbackThatForwardsOnce, adoption_aLateOutcomeFromTheOldAdapterAfterAdoption_isDropped, adoption_anOutcomeFromTheOldAdapterBeforeAnyNewRegistration_isDropped, adoption_isNotRepeatedByALaterRegistration, adoption_withNoOpenAttempt_theAdopterIsNotCalled, adoption_aRefusedAdoption_closesTheAttemptWithOneSyntheticSystemCanceled, adoption_aRefusedAdoptionThatAlreadyDeliveredAnOutcome_doesNotDeliverASecondOne, adoption_anOutcomeDeliveredInsideTheAdoption_isForwardedOnce, adoption_cancelAfterAdoption_goesToTheAdoptingAdapter, clearTask_aNewerRegistrationDoesNotAdoptFromALiveHolder_andTheOldHostStillReportsTheLoss, clearTask_unregisteringTheOlderHost_keepsTheNewerRegistrationCurrent, ruleD_unregisterOfTheHoldingHost_returnsTheIdOnce_removesTheRecord_andDropsLateOutcomes, ruleD_unregisterWhileChangingConfigurations_returnsNull_andOrphansTheRecord, ruleD_unregisterWithNoOpenAttempt_returnsNull_forBothConfigurationFlags, ruleD_unregisterOfANonHoldingRegistration_returnsNull_andLeavesTheAttemptAlone, ruleA_unregisterClearsTheCurrentRegistration_onlyWhenItIsTheCurrentOne, ruleA_registerReplacesTheCurrentRegistration_forNewAttempts, ruleD_aRealLossThenTheHostRecreated_theNewHostHasNothingToAdopt.
- AppLockRelaySourceScanTest: ruleE_noWorkerReceiverOrServiceFileReferencesTheRelayTheAuthenticatorOrTheController, ruleE_theRelayIsReferencedOnlyByItsOwnFileTheModuleAndThePromptHost, selfCheck_plantedBackgroundReferences_areDetected_andOtherFilesAreNotScanned, selfCheck_plantedRelayReference_outsideTheAllowlist_isDetected_andComments_areNot. Background file = name ends in Worker.kt, Receiver.kt or Service.kt, or a worker, workers, receiver, receivers, service or services package directory; forbidden identifiers AppLockAuthenticatorRelay, BiometricPromptDeviceAuthenticator, DeviceAuthenticator, AppLockController.
- AppLockPromptHostTest: attach_registersTheAdapterWithTheRelay_soAPromptCanBeLaunched, attach_twice_throws_andLeavesTheFirstAttachmentIntact, attach_toADestroyedActivity_getsNoRegistration, ae118_finishWithAnOpenAttempt_reportsTheHostLostExactlyOnce, ae118_aLateLibraryTerminalAfterTheLoss_isDropped_andReportsNothingMore, ae118_control_finishWithNoOpenAttempt_reportsNothing, ae118_control_finishAfterTheOutcomeWasDelivered_reportsNothing, ae118_aHostThatNeverHeldTheAttempt_doesNotReportIt_butTheHoldingHostDoes, rotation_reportsNoLoss_theNewAdapterAdoptsTheAttempt_andAMatchingSuccessIsAccepted, rotation_aSuccessThroughTheOldAdapterIsDropped_andOnlyTheNewAdapterCanAcceptIt, rotation_thenARealFinishOfTheNewHost_reportsTheAdoptedAttemptLostExactlyOnce, rotation_twoRotationsInARow_keepTheAttemptAndStillAcceptTheSuccess, rotation_aRefusedAdoption_closesTheAttemptWithOneSystemCanceled_andReportsNoLoss, rotation_throughRobolectricRecreate_reportsNoLoss_andTheNewAdapterAcceptsTheSuccess, exactlyOnce_aSecondDestroyOrALateOutcomeCannotReportAgain, exactlyOnce_oneOutcomeOnly_forTwoLibraryTerminals, exactlyOnce_aCancelThroughTheRelay_reachesTheHandleAndDeliversOneSystemCanceled, copy_everyValueReachesThePromptWithItsConstantResourceText.
- AppLockLifecycleBinderTest: count_isZeroBeforeAnythingStarts, count_tracksStartsAndStopsExactly, count_aStopAtZeroIsIgnored_neverNegative_andReportsNothing, count_afterAnIgnoredStopAtZero_theNextStartStillReportsTheForeground_evenIfTheStopWasAConfigChange, signals_firstStartReportsTheForegroundExactlyOnce_laterStartsDoNot, signals_onlyTheLastStopReportsTheBackground, signals_aFullForegroundBackgroundForegroundCycle_reportsInOrder, signals_otherLifecycleCallbacksChangeNothing, ae41_aConfigurationChangeStopStartPair_isNotReported, ae41_theSuppressionIsConsumedByTheNextStart_soTheNextRealCycleIsReported, ae41_twoConfigurationChangesInARow_areBothSilent, ae41_aConfigurationChangeStopThatIsNotTheLastOne_setsNoSuppression, ae41_control_theSameSequenceWithoutTheConfigurationFlag_isReported, prompts_aRealStopWhileAPromptIsInFlight_isReported_andNotSuppressed, prompts_stopBeforeTheOutcomeCallback_andOutcomeBeforeTheStop_areBothReportedExactlyOnce, prompts_everyControllerStateStillReportsTheRealSignals, install_isIdempotent_registersOnceAndLaunchesTheFirstResolveOnce, install_neverReportsASignalByItself, resolve_isLaunchedAtInstall_onTheApplicationScope, resolve_everyStartWhileResolving_launchesItAgain, resolve_aStartWhileNotResolving_doesNotLaunchIt, resolve_stopsDoNotLaunchIt_evenWhileResolving, resolve_afterTheStateLeavesResolving_laterStartsStopLaunchingIt, resolveFailure_aCancellationException_propagatesCancelsTheJob_andIsNotLogged, resolveFailure_anotherException_isLoggedByClassNameOnly_andTheScopeSurvives, resolveFailure_control_aSuccessfulResolveLogsNothing.
- BiometricPromptAdapterStateMachineTest (added): adopt_idle_returnsTrue_setsInFlight_andNeverLaunchesOrCallsBack, adopt_whileAnAttemptIsInFlight_returnsFalse_andChangesNothing, adopt_whileAwaitingAClientCancelTerminal_returnsFalse_andIsPossibleOnceTheEchoArrived, adopt_theTerminalOfAnOldAttempt_isNeverCreditedToTheAdoptedOne, adopt_theAdoptedAttemptsTerminal_isDeliveredOnce_withTheAdoptedIdAndCallback, adopt_aLibraryErrorIsMappedForTheAdoptedAttempt_exactlyLikeALaunchedOne, adopt_cancelOfTheAdoptedAttempt_deliversExactlyOneSystemCanceled_afterTheHandleCancel, adopt_aStaleCancelIdIsIgnored_forAnAdoptedAttempt, adopt_whenThePromptWasNotBuilt_returnsFalse_andLeavesTheAdapterIdle, adopt_belowApi30_returnsFalse_andLeavesTheAdapterIdle (sdk 29), adopt_atApi30_isAccepted_theBoundary (sdk 30).

Not testable on the JVM (G1b device): androidx.biometric 1.1.0 re-attaching the client callback of the prompt built in the new Activity's constructor to the surviving fragment (the adopt assumption), the system prompt surviving a real rotation (AE-42, AE-118 on hardware), late library terminals arriving after the post-cancel wait on a real prompt (privacy F6), and OEM credential Activity stop/start ordering around a real prompt. Weak-reference collection of the registration (rule (a) "weak") is not asserted: JVM garbage collection is nondeterministic, so the tests keep registrations strongly reachable and pin the clearing at unregister instead. Least-certain test: rotation_throughRobolectricRecreate_... (depends on Robolectric 4.11.1 `ActivityController.recreate()` reporting `isChangingConfigurations` true to the old instance); the driven-subclass rotation tests cover the same behaviour deterministically.

Runner filter: `*AppLockAuthenticatorRelay*,*AppLockRelaySourceScan*,*AppLockPromptHost*,*AppLockLifecycleBinder*,*BiometricPrompt*,*AppLockBiometricSourceScan*,*AppLockController*`.

### S20-a review fixes (2026-10-27, implementer; NOT validated)

Nothing was built, run or committed. Source: privacy-security-guardian lead gate on S20-a (ACCEPT-WITH-CONDITIONS), applied in one pass; the targeted run vr-20261027-210450-98d6a35c PASSED on the tree BEFORE these fixes, so it is stale for them. P = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker/ui/applock. No schema impact, no guard/baseline/allowlist change.

- F1 (binder destroy-time background). `AppLockLifecycleBinder.onActivityDestroyed`: `if (startedCount == 0 && suppressNextForeground && !activity.isChangingConfigurations) { suppressNextForeground = false; controller.onAppBackgrounded() }`. An unpaired config-change stop (the relaunched instance is destroyed without starting, for example UnsupportedDatabaseActivity finishing in onCreate) now reports the held-back background once, and the next start is a foreground. KDoc updated.
- F2 (relay `current` = visible host). `AppLockAuthenticatorRelay.activate(registration)` (internal, main thread, sets `current`, never adopts, never touches the record). `AppLockPromptHost` observer calls `relay.activate(attached)` at ON_START (the observer now handles ON_START and ON_DESTROY).
- F3 (orphan closed without the watchdog). Orphaning in `unregister` posts one check through an injectable `postToMain: (Runnable) -> Unit` seam (production default `Handler(Looper.getMainLooper()).post`; primary internal constructor takes `(onMainThread, postToMain)`, the old one-argument internal constructor stays and uses the real looper; `@Inject` constructor unchanged). The check closes the record with the relay's `Failed(SYSTEM_CANCELED)` only if it is still the open record, still the same orphaning (per-record `orphanSeq`, so a stale check cannot close a later orphaning) and still has no holder. A real adoption always wins. Real loss and "nothing open" post nothing.
- F5 (singleton pin). `AppLockRelaySourceScanTest.scope_theRelayClassIsDeclaredSingleton_...` asserts class-level `@Singleton` on `AppLockAuthenticatorRelay` in the comment-and-string-stripped source; a self-check proves it fails with no scope, the scope on another class, or the scope only in a comment.
- F7 (KDoc). `BiometricPromptDeviceAuthenticator` class KDoc no longer says the new instance has nothing and nobody carries the attempt: the relay carries it through `adopt`. One sentence added to the `DeviceAuthenticator.authenticate` KDoc (domain, doc only, no API change).
- LEGAL_PATHS "App Lock Prompt Path and Lifecycle Signals": one line group each for the ON_START re-assert, the orphan close, and the unpaired config-change stop reported at destroy.
- F4 (accepted, no code). Activities started before `install` are not counted (fail-closed; the binder KDoc discloses it). S20-c MUST install the binder before any in-process Activity can start (first thing in the entry Activity's onCreate, before any other Activity can be started in this process).
- F6 (G1b device list, no code). Add to the G1b device checks: the adapter's echo guard (the wait for the library terminal after a client cancel) is not carried across an Activity recreation. Check on a device that a rotation right after a cancel does not let a late terminal of the cancelled prompt reach the next attempt, and that the new adapter's `adopt` is refused (then closed by the relay) rather than mis-crediting it.

New tests (all unvalidated):
- AppLockLifecycleBinderTest: ae41Unpaired_theRelaunchedInstanceDestroyedWithoutAStart_reportsOneBackground_andTheNextStartIsAForeground, ae41Unpaired_theBackgroundIsReportedExactlyOnce_laterDestroysAddNothing, ae41Unpaired_aNormalRotationWithTheDestroyOfTheOriginal_isStillSilent, ae41Unpaired_aDestroyWhileAnActivityIsStarted_afterTheSuppressionWasConsumed_reportsNothing, ae41Unpaired_aDestroyWithNoPendingSuppression_reportsNothing.
- AppLockAuthenticatorRelayTest: activate_aRestartedOlderRegistrationBecomesCurrent_forNewAttempts, activate_afterTheOlderIsActivated_aNewerRegistrationsDestroyDoesNotStrandIt, activate_control_withoutItTheNewestRegistrationsDestroyStrandsTheOlderOne, activate_neverAdopts_anOrphanedRecordStaysOrphanedUntilARegisterAdoptsIt, activate_offMain_throws_andChangesNothing, orphanCheck_orphaningPostsExactlyOneCheck_andRunsNothingByItself, orphanCheck_anOrphanNotAdoptedBeforeTheCheck_isClosedOnceWithSystemCanceled, orphanCheck_anOrphanAdoptedBeforeTheCheck_isNotClosed_andStillAcceptsTheSuccess, orphanCheck_aStaleCheckOfAnEarlierOrphaning_doesNotCloseALaterOrphaning, orphanCheck_afterTheAttemptWasAlreadyClosed_deliversNothing, orphanCheck_doesNotCloseANewerAttemptOpenedAfterTheOrphanWasClosed, orphanCheck_aRealLoss_orNoOpenAttempt_postsNothing, orphanCheck_aRefusedAdoptionClosesOnce_andTheLaterCheckAddsNothing.
- AppLockPromptHostTest: rotation_theQueuedOrphanCheckRunsAfterTheAdoption_andClosesNothing, rotation_theRecreatedHostNeverAttaches_theQueuedOrphanCheckClosesTheAttemptOnce_withoutALossReport, twoHosts_theOlderOneRestarted_becomesCurrent_andANewerOnesDestroyDoesNotStrandIt, twoHosts_control_withoutARestartTheNewestHostIsCurrent.
- AppLockRelaySourceScanTest: scope_theRelayClassIsDeclaredSingleton_soTheHostAndTheControllerShareOneInstance, selfCheck_singletonPin_detectsAMissingOrMisplacedScope.
- Changed fixtures: AppLockAuthenticatorRelayTest builds its relay with a recording "post to main" list; AppLockPromptHostTest builds its relay the same way (Robolectric idles the main looper after each ActivityController call, which would otherwise run the orphan check between a simulated destroy and the re-attach; in production both are one message).

Residual risk: the two-host test relies on Robolectric 4.11.1 `ActivityController.restart()` dispatching onStart (and clearing the fragment manager's saved-state flag). Real relaunch timing (destroy, create and attach in one main-thread message) is a G1b device check. Runner filter: the S20-a filter above.

### S20-a ACCEPTED (2026-10-28)
- Gate: privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS. F1, F2, F3, F5 and F7 applied; F4 and F6 recorded.
- Orchestrator decision: architecture-guardian and reviewer-strict for S20 run ONCE over the full S20 candidate diff (a..e), not per sub-slice. S20-a is dormant and releasable on its own.
- Validation, final tree, all PASS:
  - targeted vr-20261027-211608-11abd602
  - runtime-ui vr-20261027-212125-1d8f349d
  - architecture-contracts vr-20261027-212342-22b61093
  - cancellation vr-20261027-212430-2daaa1f1
  - pre-fix targeted vr-20261027-210450-98d6a35c
- Committed.
- Next: S20-b (SecureWindowController, NotificationPermissionPromptOnce).

### S20-b implementation (2026-10-28, implementer; NOT validated)

Nothing was built, run or committed. Lane worktree `Cost-agregator-al`, base 384d1e56. P = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker. Dormant: nothing instantiates either class (S20-c wires them). No schema impact, no DI module change (both are `@Singleton @Inject constructor`), no pin, guard, baseline or allowlist change. No public API change to AppLockController or the settings repository; R24's NotificationPromptPolicy and NotificationPromptStore are untouched.

Files:
- N P/ui/applock/SecureWindowController.kt
- N P/ui/applock/NotificationPermissionPromptOnce.kt
- N T/ui/applock/SecureWindowControllerTest.kt
- N T/ui/applock/NotificationPermissionPromptOnceTest.kt
- M docs/architecture: CODEBASE_SEGMENTS.md, CODEBASE_INVENTORY.md, LEGAL_PATHS.md (FLAG_SECURE block in "App Lock Prompt Path and Lifecycle Signals": single writer, effective from S20-c)

Design:
- `SecureWindowController(repository: AppLockSettingsRepository, controller: AppLockController)`. `applyInitial(window: Window)` adds FLAG_SECURE synchronously. `bind(activity: ComponentActivity)` launches in `activity.lifecycleScope` (not repeatOnLifecycle: the flag must keep following while the Activity is stopped, because the recents snapshot is taken after onStop) and collects `combine(committed load states, controller.state.map { it is Resolving })` through the pure `SecureWindowController.decide(loadState, resolving)` (= `EffectivePrivacyValues.effectiveSecureScreen`), with `distinctUntilChanged`.
- Fail-closed: the committed flow is wrapped `.catch { emit(null) }` (null gives true) and the state flow `.catch { emit(true) }`; the flag is cleared only by a computed false from a real emission. After a flow error the stream is finished and the flag stays set (SUPERSEDED by the review fixes below: the stream is now re-subscribed with a bounded backoff, the flag stays set while it is down). No try/catch, so a CancellationException is never caught.
- Seams: `internal fun interface WindowFlagSink { fun setSecure(secure: Boolean) }`, `internal fun applyInitial(sink)`, `internal suspend fun follow(sink)`, `internal fun secureFlow(): Flow<Boolean>`, `internal companion object { fun decide(...) }`.
- `NotificationPermissionPromptOnce`: `@Singleton`, `fun tryClaim(): Boolean = claimed.compareAndSet(false, true)`; in memory only.

Tests owed (written here, unvalidated): SecureWindowControllerTest, NotificationPermissionPromptOnceTest. SecureWindowControllerTest uses mockk for the repository and controller, a MutableStateFlow for `state`, a replay-1 DROP_OLDEST MutableSharedFlow for committed states, and `runCurrent()` after every step (standard test dispatcher). Least-certain: the exact sink-call lists in the ae113/distinctUntilChanged/flowError tests (they assume `combine` delivers one value per settled step).

Validation to request: targeted `*SecureWindowController*,*NotificationPermissionPromptOnce*,*NotificationPromptPolicy*` (compile covers the new classes and Hilt). S20-c must: call `applyInitial(window)` first after `super.onCreate`, `bind(this)` after the DB gate, and make the R24 effect start with `if (!notificationPromptOnce.tryClaim()) return@LaunchedEffect`.

### S20-b review fixes (2026-10-28, implementer; NOT validated)

Source: privacy-security-guardian S20-b ACCEPT-WITH-CONDITIONS (no fail-open found; findings F1..F6). Applied in one pass. Nothing was built, run or committed. No schema impact; no guard, baseline or allowlist change.

Files: M P/ui/applock/SecureWindowController.kt, M T/ui/applock/SecureWindowControllerTest.kt, N T/ui/applock/SecureWindowControllerWindowTest.kt (Robolectric), M docs/architecture/LEGAL_PATHS.md (FLAG_SECURE block), this handoff.

- F1 + F3 (INV-47, AE-97): `committedLoadStates()` is now `flow { while (true) { emitAll(repository.observeLoadState() with onEach/catch); emit(null); delay(backoff(failures)); failures++ } }`. A null state is the fail-closed input (decision gives true), so the flag stays set while the stream is down, and a clean completion after Loaded(lock off) no longer freezes a stale false. Backoff: `SecureWindowController.defaultBackoffMillis(failures)` = 1 s doubling, capped at 30 s, never below 1 ms; `failures` restarts on any emission that is not `Unreadable` (the repository's terminal `Unreadable(IO_ERROR)` does not reset it, so a persistent IO error backs off). Seam: the primary constructor is now `internal constructor(repository, controller, backoffMillis: (Int) -> Long)` and the `@Inject` constructor is a secondary one delegating with the default, so Hilt wiring and the public `(repository, controller)` call shape are unchanged. `.catch {}` is kept for the foreign-exception case; `delay` and `emitAll` propagate this collector's CancellationException (no try/catch).
- F2: `WindowSink` is now `internal class SecureWindowController.WindowSink` (was private) so it is testable. Tests: mockk<Window>(relaxed) mapping, and a Robolectric sdk 34 class (`SecureWindowControllerWindowTest`, set-up copied from AppLockPromptHostTest) asserting `window.attributes.flags` after applyInitial, after a committed false, after committed true/false, and after ON_DESTROY followed by an emission (no write).
- F4: (a) wording changed in the KDoc, LEGAL_PATHS and this handoff (re-subscribes with backoff); (b) KDoc states that `.catch` maps a foreign upstream CancellationException (collector still active) to "set" while this collector's own cancellation propagates; (c) "and secure-screen is off" added to the AE-113 ENABLE sentence in the KDoc and LEGAL_PATHS.
- F5: (a) new test moves controller.state Resolving then Disabled while the stream is down and asserts no false follows; (b) the cancellation test now asserts the follower job completes within `runCurrent()` and no further writes (the tautological `isCancelled` assertion was dropped); a new test cancels during the backoff delay and asserts no re-subscription. (c) the AE-114 write-failure re-emit is left as is (accepted).
- F6 forward items (recorded, not implemented):
  - S20-c: call `bind(this)` once per Activity instance, never from `onNewIntent`.
  - S20-c: add a feature-scoped source scan that FLAG_SECURE and window addFlags/clearFlags appear only in SecureWindowController.kt (candidate: extend or sit beside AppLockRelaySourceScan; allowlist/guard changes need the usual owner approval).
  - S20-c: optionally add class-name-only logs (DiagnosticThrowableSummary.className) in the two `.catch` branches; not done in S20-b (no raw messages, no stack traces).
  - G1b (AE-67 device list): a Compose `Dialog` window inherits FLAG_SECURE only when it is created; check a flip of the flag while a dialog is open on a device.

New tests (SecureWindowControllerTest): flowError_afterTheFlagWasCleared_controllerStateChangesWhileTheStreamIsDown_neverClearIt, flowError_foreignCancellationException_isASourceFailure_mapsToSet_andTheCollectorKeepsRunning, resubscribe_unreadableThenCompletion_keepsTheFlagSet_thenClearsOnceALockOffStateArrives, resubscribe_aCleanCompletionAfterLockOff_setsTheFlagAgain_insteadOfFreezingAStaleClear, resubscribe_backoffGrowsWhileTheStreamKeepsEnding_andNeverSpins, resubscribe_aRealEmissionRestartsTheBackoff_butAnUnreadableOneDoesNot, resubscribe_cancellationDuringTheBackoffDelay_endsTheFollower_andNeverSubscribesAgain, defaultBackoffMillis_growsFromOneSecond_isCapped_andIsAlwaysPositive. Changed: cancellation_isNeverSwallowed_theFollowerEndsAndWritesNothingMore. (SecureWindowControllerWindowTest): windowSink_true_addsFlagSecure_false_clearsFlagSecure_andNothingElse, applyInitial_onAWindow_addsFlagSecureBeforeAnythingElse, realWindow_applyInitialSets_aCommittedFalseClears_aCommittedTrueSetsAgain, realWindow_afterOnDestroy_aLaterEmissionWritesNothing.

Least-certain (not run): the exact `[true, false, true]` sink lists rely on `combine` delivering one value per settled step (its internal `yield` after each send); the Robolectric tests assume Dispatchers.Main.immediate plus `Looper.idle()` flush the `lifecycleScope` collection and that `Window.attributes.flags` reflects addFlags/clearFlags on an un-attached PhoneWindow.

Validation to request (validation-runner, serial): targeted `*SecureWindowController*,*NotificationPermissionPromptOnce*,*NotificationPromptPolicy*,*AppLockRelaySourceScan*,*AppLockController*`.

### S20-b ACCEPTED (2026-10-28)
- Gate: privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS. F1-F5 applied; F6 forwarded to S20-c and G1b.
- Validation, final tree, all PASS:
  - targeted vr-20261027-220201-78c8084b
  - runtime-ui vr-20261027-220703-39595a65
  - cancellation vr-20261027-220922-db0ca088
- Committed. Dormant: not wired until S20-c.
- Next: S20-c (MainActivity shell integration; the controller goes LIVE for all users).

### S20-c implementation (2026-10-28, implementer; NOT validated)

Nothing was built, run or committed. Lane worktree `Cost-agregator-al`, base 9b9bb46e. P = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker. No schema impact, no DI module change, no new PrivacyDiagnosticCode or emitter entry, no new controller API, no guard/baseline/allowlist change, no pin loosened. AppLockGate.kt, AppLockSurface.kt, MainApplication.kt, handleIntent and onNewIntent are untouched. The controller is now LIVE for all users (resolves to DISABLED for a readable lock-off store; the fail-closed paths (T5, T6b, RP-11) can show LOCKED or INERT; the lock is still not user-enableable until S22). S20-c is release-safe only together with S20-d and S20-e.

Files:
- M P/ui/MainActivity.kt
- N P/ui/applock/AppLockShell.kt
- N T/ui/applock/MainScreenCallSiteTest.kt
- N T/ui/applock/AppLockShellTest.kt
- M T/ui/applock/AppLockGateSourceScanTest.kt (scanned set = gate + shell, exactly)
- M docs/architecture: LEGAL_PATHS.md, CODEBASE_SEGMENTS.md, CODEBASE_INVENTORY.md, ENGINE_INTERACTION_MAP.md, PRIVACY_UI_ARCHITECTURE.md (one stale "no FLAG_SECURE" sentence)
- AppLockRelaySourceScanTest: NOT changed. MainActivity references the prompt host provider, not the relay (a source test asserts it references neither the relay, the DeviceAuthenticator, the settings operations or repository, nor the controller's lifecycle API or resolve()).

MainActivity hunks (minimal; all in the listed regions plus the necessary imports):
- imports: `androidx.fragment.app.FragmentActivity` replaces `androidx.activity.ComponentActivity`; AppLockController, AppLockLifecycleBinder, AppLockPromptHost, AppLockShell, NotificationPermissionPromptOnce, SecureWindowController, javax.inject.Provider.
- `:105` `class MainActivity : FragmentActivity()`. Nothing in MainActivity relied on a ComponentActivity-only API (setContent, enableEdgeToEdge, viewModels, lifecycleScope are all inherited). The manifest theme is `Theme.ExpenseTracker` for every activity; FragmentActivity needs no AppCompat theme.
- Injected fields: appLockController, appLockLifecycleBinder, `Provider<AppLockPromptHost>` (unscoped, one per Activity), secureWindowController, notificationPromptOnce.
- onCreate: `secureWindowController.applyInitial(window)` is the first statement after `super.onCreate`, before the DB gate; after the DB gate and before `enableEdgeToEdge`/`handleIntent`/`setContent`: `appLockLifecycleBinder.install(application)`, `appLockPromptHostProvider.get().attach(this)`, `secureWindowController.bind(this)` (once per Activity instance; never from onNewIntent).
- setContent: `AppLockShell(controller = appLockController, operationalState = mainViewModel.operationalState, navigateTo = mainViewModel::navigateTo) { MainScreen(..., notificationPromptOnce = notificationPromptOnce) }` inside Surface inside ProvideNavigationController.
- MainScreen: new last parameter `notificationPromptOnce: NotificationPermissionPromptOnce`; the R24 effect starts with `if (!notificationPromptOnce.tryClaim()) return@LaunchedEffect`; its logic is otherwise unchanged.

Decisions and notes:
- Binder install point (S20-a F4): there is no Application-level point without editing MainApplication.kt (a stop condition), so the binder is installed in MainActivity.onCreate after the DB gate. Activities started before it (RescueActivity, UnsupportedDatabaseActivity, any other Activity that runs before MainActivity in this process) are NOT counted. SUPERSEDED by the S20-c review fix #1 (see "S20-c review fixes" below): the binder now counts started Activities by identity, so a pre-install Activity's later stop is ignored and cannot cause a fail-open background miss; the former "fail-closed F4" wording no longer applies. If MainActivity redirects to the DB-gate screen, the binder is never installed in that process (the controller stays RESOLVING, the lock never opens; the redirected screens are export-only and not gated).
- CF-4 (status line) needs NO new controller API, but it is intentionally narrow: the controller publishes no outcome enum after a prompt. `nextUnlockMessage(previous, current, held)` derives the one line `TRY_AGAIN` only from the published state sequence `AUTHENTICATING(UNLOCK) → LOCKED(cause != SETTINGS_UNREADABLE)`; it is null for every non-LOCKED state, for LOCKED reached from anywhere else, and after a cause change (a new episode); SETTINGS_UNREADABLE keeps the surface's own T5 line. TOO_MANY_ATTEMPTS, BIOMETRIC_LOCKED and UNAVAILABLE_NOW are never shown because the outcome kind is not published. Showing them needs a controller API (a published bounded last-outcome), which is out of scope here; OWNER/PLANNER DECISION recorded as an open item. The tracker is mutated during composition (idempotent for the same state) because the shell source must stay free of the effect tokens the gate source scan now also pins (LaunchedEffect etc.).
- The notice actions: Security settings = constant `Intent(Settings.ACTION_SECURITY_SETTINGS)` (no extras, data, component or flags) started from the tap with the composition's Context (an Activity); `ActivityNotFoundException` is caught and logged as the constant `applock_security_settings_unavailable:ACTIVITY_NOT_FOUND` only (no exception, message or class name); any other exception propagates. App-lock-settings and Review both re-read `mainViewModel.operationalState.value` at tap time and, if allowed, navigate with `mainViewModel.navigateTo(NavigationDestination.PrivacySettings)`. The Security settings action is not re-checked (a system intent, not an in-app action; INV-52 keeps it).
- `AppOperationalState.allowsInAppLockActions()` is an exhaustive `when` with no else (Normal, BackupExporting allow; RestoreInProgress, RestartRequiredAfterRestore, CriticalRecoveryRequired block).
- Insets (S19 note): MainScreen's Scaffold keeps its default `contentWindowInsets` (a source test pins that MainActivity.kt has no `contentWindowInsets`), so it honours the slot's consumed Top + Horizontal insets while a banner is composed. AppLockShellTest drives real status-bar insets through `ViewCompat.dispatchApplyWindowInsets` on the compose view.
- Surface: the shell is inside the existing `Surface` (so the content keeps its background; the neutral/lock surfaces draw their own navy).

Tests owed validation (all new, unvalidated):
- MainScreenCallSiteTest: inv26_exactlyOneProductionMainScreenCall_andItIsInsideTheAppLockShellLambda, inv26_theShellIsInsideTheNavigationProvider_andMainScreenKeepsItsDeclaration, cf5_noDialogAlertDialogOrPopupInSetContentOutsideTheShell, inv29_applyInitialIsTheFirstStatementAfterSuperOnCreate_andBeforeTheDbGateAndSetContent, s20a_f4_installAttachAndBindComeAfterTheDbGateAndBeforeSetContent_inThatOrder, s20b_f6_bindInstallAndAttachAreNeverCalledFromOnNewIntent, ae39_theR24EffectStartsWithThePromptOnceClaim_beforeThePolicyAndTheStore, s19InsetsNote_mainScreenKeepsTheDefaultScaffoldInsets_soTheSlotsConsumedInsetsAreHonoured, mainActivity_reachesTheLockOnlyThroughTheShellTheBinderTheHostAndTheWindowController, tech1_mainActivityExtendsFragmentActivity, inv29_flagSecureAndWindowFlagWritesAppearOnlyInSecureWindowController, and six selfCheck_* tests (call counting, call outside the lambda, nested lambda, overlays outside the shell, window-write scan, order scan).
- AppLockShellTest: cf1_controllerGateSource_exposesTheControllersOwnFlowInstances, cf1_theGateFollowsTheControllersFlowsDirectly_fromResolvingToLockedToOpenWithANotice, publicOverload_unlockTapCallsTheControllerOnce_andTheNoticeActionNavigatesToPrivacySettings, inv34_unlockCallsTheCallbackFromATapOnly_neverFromCompositionOrAStateChange, cf3_securitySettingsIntent_isTheConstantActionWithNoExtrasDataComponentOrFlags, cf3_open_startsTheIntentOnce_andReportsTrue, cf3_activityNotFound_isHandled_returnsFalse_andLogsOnlyTheBoundedConstantReason, cf3_aFailureThatIsNotActivityNotFound_isNotSwallowed, cf3_theSecurityActionTap_callsTheSystemCallbackOnly_notTheInAppOnes, cf3_theAppLockSettingsAndReviewActionsBothOpenTheSettingsCallback_whenTheStateAllowsIt, cf3_tapTimeRecheck_aRestoreThatBeganAfterTheBannerWasDrawn_blocksTheInAppActions_butNotTheSystemOne, cf3_tapTimeRecheck_appliesToTheReviewAction_too, inv52_inAppActionsAllowed_followsTheOperationalState_andTheSystemActionStays, inv52_allowsInAppLockActions_coversEveryOperationalState, cf4_nextUnlockMessage_isTryAgainOnlyWhenAnUnlockPromptReturnsToLocked, cf4_nextUnlockMessage_isAlwaysClearedOutsideLocked, cf4_unlockMessageTracker_isIdempotentForTheSameState, cf4_composed_theLineAppearsAfterAFailedPrompt_andIsClearedByANewPromptAnUnlockAndANewEpisode, cf4_composed_anUnreadableSettingKeepsTheSurfacesOwnLine_afterAFailedPrompt, t14_rememberSaveableInTheContentSurvivesALockedUnlockedCycle, t14_control_withoutTheHolderTheSameCycleLosesTheState, ae113_launchedEffectInTheContent_doesNotRerunAcrossAnEnablePrompt, ae113_confirmChangePrompt_keepsTheContentAndItsEffect_too, ae113_control_aLockedCycleDoesRebuildTheContent_soTheClaimBelowIsWhatCapsThePrompt, ae39_theClaimAtTheContentsEffect_capsTheAutoPromptToOnePerProcess_acrossLockAndUnlock, insets_withABanner_theScaffoldInTheSlotDoesNotPadTheTopTwice, insets_withoutABanner_theScaffoldPadsTheTopNormally, insets_togglingTheBanner_movesTheScaffoldBetweenConsumedAndNormalPadding.
- AppLockGateSourceScanTest (added): inv53_r2_appLockShellSourceHasNoImmediateUnconfinedLifecycleCollectionOrEffects, scannedFileSet_isExactlyTheGateAndTheShell.

Least-certain (first compile, not run): the three insets tests (they rely on Robolectric honouring `ViewCompat.dispatchApplyWindowInsets` on the compose view and on the compose insets listener being installed by the safeDrawing read; status bar visibility defaults to visible in `WindowInsetsCompat.Builder`); the stale-collecting `StateFlow` fake (collect returns Nothing); the mocked controller in a Robolectric test (mockk of a final class, same as SecureWindowControllerTest); `Timber.uproot(tree)`; `SemanticsNodeInteraction` status-line text on an empty status area (copied from AppLockGateTest :187-191).

Validation to request (validation-runner, serial): targeted `*MainScreenCallSite*,*AppLockShell*,*AppLockGate*,*AppLockSurface*,*LockNoticeBanner*,*SecureWindowController*,*NotificationPermissionPromptOnce*,*AppLockRelaySourceScan*,*GsonR8KeepRulesContract*,*AppLockBiometricSourceScan*,*AppLockPromptHost*,*AppLockLifecycleBinder*`; then shard runtime-ui; lint; registered guards ui_dao and cancellation; the Hilt graph compile (kapt/ksp) covers the new MainActivity injection (Provider<AppLockPromptHost>, five new fields). static-guards/app-check at the S20 candidate (after d and e).

Residual risks:
- G1b device checks remain: real rotation with an open prompt (adopt), CF-6 (no content frame drawn after a re-lock at APP_FOREGROUNDED; no activity-scoped ViewModel holds clipboard listeners or permission flows across re-lock; ReviewViewModel is an Activity-scoped hiltViewModel in MainScreen and survives a lock), the Back behaviour on the lock surface (S19 decision 4: background the task) was NOT implemented in the first S20-c pass and is now implemented by review fix #11 (AppLockShell BackHandler), and a Compose Dialog opened while the flag flips (S20-b F6).
- Deep links and notification taps still apply immediately (S21 gates handleIntent); with the lock not enableable until S22 this is not user-visible, but S20-c must not ship alone.
- Status-line scope (CF-4) is the single TRY_AGAIN described above; richer lines need a controller outcome API.
- The tracker's write-in-composition is idempotent per state but is an unusual pattern; a published outcome would remove it.

### S20-c review fixes (2026-10-28, implementer; NOT validated)

Nothing was built, run or committed. Applies the privacy-security-guardian lead findings (ACCEPT-WITH-CONDITIONS) in one pass. P = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker. No schema, DI, diagnostics-code, controller-API, guard, baseline or allowlist change. AppLockGate.kt, AppLockSurface.kt, MainApplication.kt and handleIntent are untouched.

Per finding:
- #1 (MEDIUM, binder identity): AppLockLifecycleBinder counts started Activities in an identity set (`Collections.newSetFromMap(IdentityHashMap)`); a stop of an Activity that was never counted is ignored; a repeated start of a counted instance changes nothing. New tests: identity_aPreInstallActivityThatStopsAfterMainActivityStarted_reportsNothing_andDoesNotUncountTheStart, identity_afterAnIgnoredPreInstallStop_theNextRealStopReportsTheBackground, identity_aPreInstallConfigurationChangeStop_setsNoSuppression_andIsNotUncounted, identity_aSecondStopOfTheSameInstance_isIgnored_soItCannotUncountAnotherActivity, identity_aRepeatedStartOfTheSameInstance_isCountedOnce. All existing binder tests were re-read against the new semantics (each uses distinct instances per start, or start/stop pairs of the same instance).
- #4: LEGAL_PATHS and this handoff now say "DISABLED for a readable lock-off store; the fail-closed paths (T5, T6b, RP-11) can show LOCKED or INERT". The "fail-closed F4" wording (LEGAL_PATHS binder paragraph, handoff binder install note) is replaced by the identity-count statement.
- #5: PRIVACY_UI_ARCHITECTURE.md restores the historical 2026-09-07 bullet (marked superseded) and adds a dated "2026-10-28 S20-c" entry: conditional FLAG_SECURE (on while effectiveSecureScreen holds: RESOLVING, lock on, secure-screen on, store unreadable or missing; cleared for lock-off and secure-off users), UnsupportedDatabaseActivity not covered, the gate as privacy enforcement.
- #6: stale DORMANT / "nothing calls it yet" removed from the KDoc of AppLockLifecycleBinder, AppLockPromptHost, SecureWindowController, NotificationPermissionPromptOnce, and from CODEBASE_SEGMENTS (S20-a and S20-b headings), CODEBASE_INVENTORY (S20-a and S20-b bullets) and hilt-bindings-map (S20-a block).
- #7: AppLockGateSourceScanTest scans every file of `scannedFiles` in one loop (inv53_r2_everyScannedFileHasNoImmediateUnconfinedLifecycleCollectionOrEffects), and requires `Regex("""\bAppLockGate\s*\(\s*source\s*=""")` in the shell (inv53_r2_theShellHandsTheGateTheSourceOverload) with selfCheck_sourceOverloadRegex_acceptsTheSourceOverloadOnly. The gate's collectAsState check is its own test (inv53_r2_theGateStillCollectsWithPlainCollectAsState). The previous two per-file tests are replaced by these.
- #8: MainScreenCallSiteTest overlay regex now covers Dialog, Popup, ModalBottomSheet, BottomSheet, DropdownMenu and Tooltip (TooltipBox); new selfCheck_otherWindowOpeningComposables_outsideTheShell_areDetected_andInsideAreNot.
- #11: AppLockShell composes `BackHandler(enabled = state.backLeavesTheApp())`, where `backLeavesTheApp()` is `toGateRender() != OPEN` (RESOLVING, LOCKED, AUTHENTICATING(UNLOCK)); the public overload passes `context.moveTaskToBackIfActivity()` (unwraps ContextWrapper to the Activity, `moveTaskToBack(true)`). The internal overload gains `onBackWhileClosed: () -> Unit = {}`. BackHandler is not a forbidden token of the gate scan (tokens: immediate, Unconfined, collectAsStateWithLifecycle, LaunchedEffect, SideEffect, DisposableEffect), so no scan change was needed. New AppLockShellTest tests: back_whileLocked_movesTheTaskBack_andComposesNoContent, back_whileResolving_andWhileAnUnlockPromptIsInFlight_alsoMovesTheTaskBack, back_whileTheGateIsOpen_theShellHandlerIsDisabled_andNeverCalled, back_theHandlerFollowsTheGate_disabledAgainOnceUnlocked, back_backLeavesTheApp_isTrueExactlyForTheClosedStates, back_moveTaskToBackIfActivity_movesTheActivitysTask_directlyAndThroughWrappers, back_moveTaskToBackIfActivity_withNoActivityInTheChain_movesNothing. The first-pass residual risk "Back on the lock surface not implemented" is closed (device check remains in G1b).
- Plan text: the S20-c "relay scan gains MainActivity.kt" note is amended in app-lock-privacy-mode-plan.md to say it was correctly not applied (MainActivity references the prompt host, not the relay).

TECH-10 CLOSED (owner-approvals-20261022.md item 61(a)): AppLockGateSourceScanTest, AppLockBiometricSourceScanTest, AppLockRelaySourceScanTest and MainScreenCallSiteTest are FEATURE-SCOPED tests, not registered guards. No guard-registry change.

BINDING before S22 (the first user-enableable slice; owner-approvals item 61(c)):
- #2 Controller timeout re-read: after a resolve TIMEOUT with no settings view, the controller never re-reads in that process (a lock-off user gets a fail-closed lock-on or an unclearable banner until the next cold start). Controller fix: treat `resolvedByTimeout && null` as transient for INV-47, never relaxing LOCKED.
- #9 Deep links: handleIntent still runs lookups and writes while RESOLVING or LOCKED until S21 gates it. Nothing is composed while locked. S21 must close this.
- #10 UnsupportedDatabaseActivity DB export bypasses the app lock. Before S22, require device auth when the committed lock is on, or record an owner exception.

Validation to request (validation-runner, serial): targeted `*MainScreenCallSite*,*AppLockShell*,*AppLockGate*,*AppLockLifecycleBinder*,*AppLockPromptHost*,*SecureWindowController*,*AppLockRelaySourceScan*`.

Least-certain (first compile, not run): `LocalOnBackPressedDispatcherOwner.current!!` and `OnBackPressedDispatcher.hasEnabledCallbacks()` in the Robolectric compose rule; `mockk<Activity>` and `ContextWrapper(mock)` in a Robolectric test; the public-overload wiring `context.moveTaskToBackIfActivity()` is covered by the helper tests, not by an end-to-end task move.

### S20-c ACCEPTED (2026-10-28)
- Gate: privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS. #1, #4-#8 and #11 applied.
- TECH-10 closed (owner-approvals 61a). #2, #9 and #10 are BINDING before S22 (61c).
- Validation, final tree, all PASS:
  - targeted vr-20261027-232145-dceab959
  - runtime-ui vr-20261027-232917-62168d16
  - lint vr-20261027-233119-1c09b79e
  - ui_dao vr-20261027-233537-ee0bb28e
  - cancellation vr-20261027-233555-f776377f
  - pre-fix targeted vr-20261027-223806-2b69e0e1
- Committed. The controller is LIVE from this commit, so the S20 candidate merges only as a unit with S20-d and S20-e (D1), and no release ships S20 without S22 (item 55a).
- Next: S20-d (S18-ii-g: WRITE 5 discreet routing + D4 retirement).

### S20-d implementation (2026-10-28, implementer; NOT validated)
Scope: S18-ii-g, WRITE 5 discreet routing + D4 option (a) retirement (owner items 51a, 52). No build, test or guard was run. The implementer had NO shell and NO file-deletion tool, so two files are neutralised placeholders that the orchestrator must `git rm` (see Orchestrator actions).

Production:
- ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt: ctor swaps `updateDiscreetNotifications` for `operations: AppLockSettingsOperations`. `setDiscreetNotifications` = `withContext(NonCancellable) { handlePlain(operations.requestDiscreetChange(enabled)) }`, NO outer serializer. `handlePlain` is an exhaustive `when` over PlainSettingResult with no else: COMMITTED does nothing; FAILED, REJECTED_FORCED, ALREADY_IN_FLIGHT set SAVE_FAILED and call resubscribeOnMain(). The exception-path `recordPersistFailed(DISCREET)` is KEPT. The review-save path, `writeSerializer` and `discreetRepostTrigger` params are untouched (S20-e). KDoc updated.
- domain/privacy/applock/UpdateDiscreetNotificationsUseCase.kt: emptied to a package + comment placeholder (use case and `UpdateDiscreetNotificationsResult` were both only in that file). To be deleted.
- AppLockSettingsRepository.kt: unforced `setDiscreetNotifications` removed (KDoc cross-reference repointed to `setDiscreetNotificationsIfNotForced`). AppLockSettingsRepositoryImpl.kt: implementation removed.
- Hilt: the use case had only an @Inject constructor, no module binding to remove. Graph check: AppLockSettingsOperations is `@Singleton @Inject` with controller (@Singleton @Inject since S20-c; DeviceAuthenticator bound to the relay in AppLockModule), settingsRepository (@Binds), capabilityProvider (@Binds), DiscreetRepostTrigger (@Inject), AppLockSettingsWriteSerializer (@Singleton @Inject), @ApplicationScope scope. All resolve; no binding problem.

Deleted tests (approved, D4):
- UpdateDiscreetNotificationsUseCaseTest (whole class, 11 cases; file emptied, to be `git rm`): `turning discreet on from off commits and runs exactly one sweep`; `turning discreet on from the first run default runs one sweep`; `turning discreet on when already on commits without a sweep`; `turning discreet off never re-posts`; `turning discreet off when already off never re-posts`; `AE-105 a failed write keeps the committed value, records the field and runs no sweep`; `a write rejected while unreadable runs no sweep and records no persist failure`; `an unreadable before-read with a committed ON is treated as was-off and runs exactly one sweep`; `a sweep that throws leaves the committed write successful and records the failure`; `cancellation from the sweep propagates after the write committed`; `cancellation from the repository write propagates and runs no sweep`.
- AppLockSettingsRepositoryImplTest (10 removed; each exercised only the removed method, and an equivalent for the surviving writes exists): `cancellation_during_write_propagates_and_is_not_a_typed_failure` (equivalent: `setAppLockEnabled_cancellation_during_write_propagates_and_is_not_a_typed_failure`); `setDiscreet_from_first_run_commits_and_marks_normal`; `setDiscreet_false_on_first_run_still_writes_marker_so_it_is_loaded_not_first_run`; `setDiscreet_can_turn_the_value_back_off_when_readable`; `setDiscreet_while_sentinel_present_is_rejected_unreadable_and_writes_nothing`; `setDiscreet_while_invalid_value_is_rejected_unreadable`; `setDiscreet_when_the_read_fails_with_io_error_is_rejected_unreadable`; `setDiscreet_write_failure_is_typed_failed_and_keeps_the_committed_value`; `setDiscreet_keeps_the_stored_1b_values`; `setDiscreet_leaves_the_lock_flag_untouched_after_setAppLockEnabled`.
- PORTED, not deleted (they cover behaviour beyond the removed method; moved to `setDiscreetNotificationsIfNotForced`, the lock being off so it commits): ImplTest `observe_emits_committed_state_only_and_a_failed_write_changes_nothing`, `observe_reflects_a_committed_write`, `setDiscreet_is_rejected_while_the_lock_flag_is_invalid_and_writes_nothing`; CorruptionTest `per_field_write_after_corruption_is_rejected_and_sentinel_remains`, `store_uses_its_own_file_and_never_touches_the_privacy_settings_store`. So CorruptionTest has 0 deletions instead of the 2 approved (deviation: more coverage kept, not less).
- AppLockPrivacySettingsViewModelTest: no case deleted. `writes from two ViewModel instances never overlap (serialised)` was REWRITTEN as `the discreet write takes no outer serializer (...)` (the serialisation is now owned by Operations and covered by AppLockSettingsWriteSerializerSharingTest; the new case proves no deadlock when the operations take the shared serializer inside).

Tests modified: AppLockPrivacySettingsViewModelTest (mock `AppLockSettingsOperations`; every PlainSettingResult value in `every PlainSettingResult value is mapped (WRITE 5), ...`; no serializer wrap; new `a write whose application scope is cancelled mid write still completes (NonCancellable)`; exception path still records PERSIST_FAILED(DISCREET); the VM-local FakeRepository, a 7th `setDiscreetNotifications` fake not on the FS-14 list, lost its override); the 6 FS-14 fakes lost the override (AT NotificationPresentationDeviceTest, AndroidNotificationServiceTest, AndroidNotificationServicePresentationTest, NotificationPresentationResolverTest, AppLockControllerTestFixtures; AppLockControllerEnableDisableTest had only a KDoc/comment mention, repointed); AppLockControllerRereadTest gains the owed D6 test `D6 a discreet write committed through requestDiscreetChange after a kept snapshot drops that snapshot (S20-d WRITE 5)`.

Pin deltas (AppLockControllerIllegalTransitionTest), exact:
- F3 pin renamed `D4 retired - no production call site of the unforced discreet write` and now `assertProductionCallers(".setDiscreetNotifications(", emptySet())`.
- Dormancy pin: the single-file exemption became a per-file map. AppLockSettingsOperations.kt keeps all 7 tokens; ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt is exempt for EXACTLY {"AppLockSettingsOperations", "requestDiscreetChange"}. The token list itself is unchanged.
- reviewSave two-callers pin and every other pin unchanged.

Docs: LEGAL_PATHS (trigger line, WRITE 1, WRITE 2 wrapper note, WRITE 3 BLOCKING list WRITE 5 routing item + source-scan sentence, WRITE 5 header and ISSUE-4 paragraph, SERIALISATION), SEGMENTS (:294, :598, :603), INVENTORY (:17), ENGINE (:39, :161-165), hilt-bindings-map (use case entry removed, VM entry, graph note).

Orchestrator actions: `git rm` app/src/main/java/com/yourname/expensetracker/domain/privacy/applock/UpdateDiscreetNotificationsUseCase.kt and app/src/test/java/com/yourname/expensetracker/domain/privacy/applock/UpdateDiscreetNotificationsUseCaseTest.kt (both are comment-only placeholders now, so the tree compiles either way).

Validation to request (serial): targeted `*AppLockPrivacySettingsViewModel*,*AppLockSettingsRepositoryImpl*,*AppLockSettingsOperations*,*AppLockSettingsWriteSerializer*,*AppLockController*,*AndroidNotificationService*,*NotificationPresentationResolver*` (S20-e shares the shard/guard run). The androidTest fake edit (NotificationPresentationDeviceTest) needs the androidTest compile at app-check.

Least-certain (first compile, not run): `mockk<AppLockSettingsOperations>()` on a final class (the suite already mocks final DiscreetRepostTrigger); the cancel-mid-write test relies on `gate.await()` inside `coAnswers` running in the NonCancellable context; the D6 test relies on the harness `plainWriteResult` hook and an unconfined rig scope (as AppLockControllerForcedValueTest); unused imports may remain in the repository tests (AppLockSettingField, SettingsUnreadableCause, etc.), harmless unless the build treats warnings as errors.

### S20-e implementation (2026-10-28, implementer; NOT validated)
Scope: S18-ii-h, INV-45 review-save routing (owner items 45, 51a D1, 55a D2 kept). No build, test or guard was run. S20-d (discreet routing, D4 retirement) is intact.

BLOCKING list status: h is CLOSED. Remaining before any release: the S22 UI fields (a real timeout and secure-screen choice) plus the 61c carry-forwards. Release condition (55a): no release ships S20 without S22, because D2 writes secure screen ON on every review-save.

Production:
- ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt: ctor is now (repository, operations, controller: AppLockController, privacyDiagnostics, @ApplicationScope appScope). Removed writeSerializer and discreetRepostTrigger (nothing else used them). `saveReview` builds the D2 record `AppLockPrivacySettings(discreetNotifications = reviewChoice, appLockEnabled = false, autoLockTimeout = IMMEDIATE, secureScreen = true)` and calls `withContext(NonCancellable) { handleReviewResult(operations.requestReviewSave(record)) }` with NO outer serializer. Removed the VM's LOCK_SETTINGS_REVIEW_SAVED emit, the review recordPersistFailed(REVIEW_SAVE) (result path and exception path) and the sweep (sweepAfterCommittedReview). The exception path keeps Timber (class name only) and SAVE_FAILED. The discreet exception-path recordPersistFailed(DISCREET) is KEPT (S20-d). `retry()` now also calls `controller.requestSettingsReread()` (no Operations pass-through exists; adding one is outside the KDoc-only Operations edit). refreshIfTransient reaches it through retry().
- Mapping (exhaustive `when`, no else), ReviewSaveResult has exactly the 7 names: COMMITTED resets the choice, reviewCommitted = true, REVIEW_SAVED; STARTED no notice; FAILED SAVE_FAILED; REJECTED_CAPABILITY, NOT_IN_REVIEW, NOT_IN_ORIGIN, ALREADY_IN_FLIGHT SAVE_FAILED + resubscribeOnMain().
- domain/privacy/applock/AppLockSettingsOperations.kt: KDoc only (class KDoc review-save paragraph, the requestReviewSave single-caller note, the stale UpdateDiscreetNotificationsUseCase mention).

Diagnostics exactly-once proof (D7, owner of both codes = the controller, claimed once per (code, field) per process; the sweep = Operations runReviewSaveTrigger): AppLockControllerReviewSaveTest asserts REVIEW_SAVED once on the prompt path (`AE-85 ... REVIEW_SAVED once`, `AE-85 a weakening lock-on record ...`), on the direct path (`AE-86`, `AE-87`, `ISSUE-1`), after a cancelled scope and F7 (`a cancelled scope cannot leave a half applied review-save`, `F7 direct/prompt`); PERSIST_FAILED(REVIEW_SAVE) once on a failing direct write (`AE-89 a failing write on the direct path`), after a prompt (`AE-89 ... after a successful prompt`), and a throwing write or UNREADABLE refusal (`AE-89 a write that throws`); `D7 REVIEW_SAVED is claimed once per process ...` and `D7 ... carry nothing but enums, and a failing or stuck sink never fails a committed save`; the trigger call `(false, effective after)` on both paths (`the discreet trigger gets ...`). Gap accepted by D8: a stale card (Rejected NOT_UNDER_REVIEW on the direct path) is NOT_IN_REVIEW and records no diagnostic anywhere (the old VM recorded PERSIST_FAILED(REVIEW_SAVE) for it; the spec says it is not a persist failure). No emitter or allowlist change was needed.

Pin deltas (AppLockControllerIllegalTransitionTest), exact:
- Dormancy pin: the VM per-file exemption is now EXACTLY {"AppLockSettingsOperations", "requestDiscreetChange", "requestReviewSave"} (gained exactly "requestReviewSave"). The `dormant` token list is unchanged. The path is the new const SETTINGS_VIEW_MODEL_PATH.
- Controller-entry pin (`no production file other than the controller touches ...`): this pin, not the dormancy pin, lists "requestSettingsReread" in `forbidden` (zero callers), so the VM needed an exemption there: the `allowed` set became a `when` over the file path, Operations.kt keeps `settingsOperationsOnly`, and the VM path gets exactly setOf("requestSettingsReread"). The forbidden list is unchanged (comment reworded).
- reviewSave pin renamed `S20-e - reviewSave has exactly one production caller, AppLockSettingsOperations` and now `assertSingleProductionCaller(".reviewSave(", ".../AppLockSettingsOperations.kt")`. All other pins unchanged.

Tests (AppLockPrivacySettingsViewModelTest, rewritten file; no test deleted, the review tests were modified to the operations mock): new `retry also asks the controller for a settings re-read exactly once`, `the foreground refresh asks for the controller re-read only while transient unreadable`, `the review-save record is exactly the D2 record for both discreet choices`, `... keeps the fail closed discreet choice when the user did not change it`, `every ReviewSaveResult value is mapped (S20-e), with no VM diagnostic on any path`, `a started review save shows no notice and frees the card, and the commit arrives through the observer`, `the review save takes no outer serializer either`, `a review save whose application scope is cancelled mid request still completes (NonCancellable)`. Modified (diagnostic assertions changed to "no VM diagnostic", via confirmVerified(emitter)): the AE-85 review-save case, the two sweep cases (now assert the on/off D2 record and no VM trigger), `refused or failed ...` (now `a failed or thrown review save emits no VM diagnostic ...`), AE-89, the transient-rejected case (now NOT_IN_ORIGIN), the D8 stale-card case (now NOT_IN_REVIEW), the unexpected-exception case, and the diagnostics-failure case (now: a sink that would fail is never reached). The FakeRepository lost reviewSaveResult/reviewSaveThrows/publishCommit; its reviewSave records and fails closed so tests assert the VM never reaches it.

Docs: LEGAL_PATHS (WRITE 2 single path and mapping, WRITE 2b live, BLOCKING INV-45 CLOSED, source-scan sentence, WRITE 5 tail, SERIALISATION, READ line for requestSettingsReread), SEGMENTS (:603), ENGINE (saveReview chain + retry), hilt-bindings-map (VM entry). INVENTORY not touched (no inventory line mentions the review path).

Flags for the orchestrator and guardians:
- UX: ux-flows.md :1051/:300 describe a "Settings saved" snackbar after a successful review-save. Under D2 the record is lock-off, which is WEAKENING, so from UNLOCKED every 1a review-save returns STARTED (CHANGE prompt) and, per the plan table, shows no notice; REVIEW_SAVED appears only for the direct paths (INERT under D3). The commit still reaches the UI through the observer (card gone). I treated the plan table as binding (the prompt-path snackbar belongs to the S22 UI); if the owner wants a snackbar after STARTED the VM needs a signal from the controller/Operations, which this slice does not add.
- The VM now injects AppLockController directly (UI to domain controller, as AppLockShell already does). REJECTED_CAPABILITY is unreachable with the D2 record; it is mapped fail-closed.
- N1 (debounce requestSettingsReread): the controller already makes a newer request supersede older ones; no VM debounce was added.
- Carry-forward (7) holds: the VM never infers success from STARTED.

Validation to request (serial, one batch with S20-d): targeted `*AppLockPrivacySettingsViewModel*,*AppLockSettingsRepositoryImpl*,*AppLockSettingsOperations*,*AppLockSettingsWriteSerializer*,*AppLockController*,*AndroidNotificationService*,*NotificationPresentationResolver*`; shards domain-n-z, data, runtime-ui; guards known_good_state, ui_dao, cancellation; then the candidate gate (static-guards, lint, app-check incl. androidTest compile).

Least-certain (first compile, not run): `mockk<AppLockController>(relaxed = true)` on a final class; `confirmVerified(emitter)` on the relaxed suspend-API mock; the all-values loop re-stubs `coEvery` and builds a new VM per value; the NonCancellable review test relies on `gate.await()` inside `coAnswers` (same pattern as the S20-d discreet test).

### S20-e review-save notice (owner-approvals 64) (2026-10-28, implementer; NOT validated)

Decision: after `requestReviewSave` returns STARTED the user still gets "Settings saved" (REVIEW_SAVED) when the save commits. VM only (`AppLockPrivacySettingsViewModel`); no controller or Operations API, no new injected dependency.

Design:
- `pendingReviewSave` (AtomicBoolean, private, never rendered). `handleReviewResult` sets it to `result == STARTED` for every result except ALREADY_IN_FLIGHT (the confirmed apply may still be writing, so that result leaves it untouched).
- `applyLoadState` (the single observer entry): on REVIEW -> READY with `compareAndSet(true, false)` it runs the shared `markReviewCommitted()` helper (reviewChoice reset, reviewCommitted = true; the same helper the COMMITTED branch now uses) and sets REVIEW_SAVED. The existing keep-REVIEW_SAVED-on-READY rule retains the notice. REVIEW -> LOADING/TRANSIENT clears the flag without a notice. CAS makes the notice at most once per STARTED.
- Cancelled prompt: the outcome is not observable from the VM (no controller state is observed; the store simply stays unreadable). Rule: cleared by the next review-save result (other than ALREADY_IN_FLIGHT), by `retry()`, and by the observer leaving the review category for loading/transient; dies with the VM. Residual: a cancelled prompt followed by no action leaves it armed; only an unrelated external store recovery before the next action would then show the notice. Also, if the commit emission were to arrive before the STARTED result is processed, no notice is shown (the prompt needs user authentication, so practically unreachable).
- Never swallows CancellationException (no catch added). No dormancy-pin token added: the VM still uses exactly {AppLockSettingsOperations, requestDiscreetChange, requestReviewSave} from the pinned set; the pin test and guard files are untouched.

Tests (AppLockPrivacySettingsViewModelTest): modified `a started review save shows no notice and frees the card...` (the post-commit state is now Ready with REVIEW_SAVED); new: STARTED then a committed Loaded shows REVIEW_SAVED, later emissions keep the one notice and a later unreadable/readable cycle shows none; STARTED then a later FAILED clears the flag, a subsequent Loaded shows no notice; STARTED then retry() clears the flag; Loaded without a pending STARTED shows no notice.

Docs: LEGAL_PATHS WRITE 2 mapping sentence updated (STARTED no longer "NO notice"). The earlier flag above (UX :1051/:300) is resolved by this decision.

Validation to request: targeted `*AppLockPrivacySettingsViewModel*` plus the dormancy/controller-entry pins in `*AppLockControllerIllegalTransition*`; the existing S20-d/S20-e batch otherwise unchanged.

### S20 candidate fixes (2026-10-28, implementer; NOT validated)

Nothing was built or run. S20-d and S20-e are uncommitted and were kept intact. No guard, baseline or `scripts/ci/verify_known_good_state.py` change.

A. static-guards known_good_state (owner-approvals item 67), behaviour-neutral rewrites so the db_guard callable parser resolves every function parameter type:
- `ui/applock/AppLockGate.kt`: public `typealias AppLockContent = @Composable () -> Unit` after the imports, explicit `import com.yourname.expensetracker.ui.applock.AppLockContent` (the parser has no `@` token, and the project index does not index typealiases, so the alias path needs the explicit import); both `AppLockGate` overloads now take `content: AppLockContent`.
- `ui/applock/AppLockShell.kt`: same import; both `AppLockShell` overloads take `content: AppLockContent`.
- `ui/applock/AppLockAuthenticatorRelay.kt`: the top-level `private fun postToMainLooper` and its KDoc are deleted; the two delegating constructors pass `{ task -> Handler(Looper.getMainLooper()).post(task) }` (Boolean result coerces to Unit under the expected `(Runnable) -> Unit`). `AttemptAdopter` stays a top-level `fun interface`. No other reference to `postToMainLooper` exists in the worktree (searched all files).

B. reviewer-strict findings:
- F1: `AppLockController.requestSettingsReread` KDoc now says one production caller, the 1a settings section's retry (S20-e; pinned).
- F2: `AppLockSettingsWriteSerializer` KDoc: only `AppLockSettingsOperations` paths take the lock; the ViewModel reaches WRITE 1 and 2 only through WRITE 5 and 2b and must not wrap them.
- F3: the item 64 ALREADY_IN_FLIGHT exception reason is now "the confirmed apply may still be writing" (comment only) in the ViewModel (`handleReviewResult`, `pendingReviewSave` KDoc), LEGAL_PATHS WRITE 2 and the S20-e review-save notice design bullet above.
- F4: test `S20 F4 the settings ViewModel source never names the write serializer or withExclusiveWrite` (AppLockPrivacySettingsViewModelTest; comments and strings stripped).
- F5: tests in AppLockPrivacySettingsViewModelTest: `S20 F5 a started review save then ALREADY_IN_FLIGHT leaves the flag armed, so the commit shows REVIEW_SAVED`; `S20 F5 a started review save then the observer moving to transient unreadable clears the flag without a notice`; `S20 F5 retry itself clears a pending flag that applyLoadState did not, so a later commit shows no notice` (the flag is armed while the state is already TRANSIENT, via a held request, so TRANSIENT -> LOADING in applyLoadState cannot clear it; only `retry()` does) plus its control `S20 F5 control - a flag armed while transient and not retried is consumed by the next review to readable move`. The older retry test is unchanged (it clears via REVIEW -> LOADING as well, so it was not discriminating).
- F6: AppLockControllerIllegalTransitionTest: `S20 F6 - the settings ViewModel's only use of the controller is requestSettingsReread` (per-file, comment/string stripped) and `S20 F6 - the dormancy token list equals the public request surface of AppLockSettingsOperations` (reflection; the token list moved to the companion `DORMANT_TOKENS`, used by the existing dormancy test unchanged in content). Strengthenings only.

Validation to request: targeted `*AppLockPrivacySettingsViewModel*`, `*AppLockControllerIllegalTransition*`, `*AppLockAuthenticatorRelay*`, `*AppLockGate*`, `*AppLockShell*` (compile of the typealias and constructor lambdas), then `static-guards` for the known_good_state item.

### S20 CANDIDATE ACCEPTED (2026-10-28): S20-a..e, absorbing S18-ii-g and S18-ii-h (D1 closed)
- Gates:
  - per-sub-slice privacy-security-guardian reviews (a, b, c);
  - reviewer-strict on d+e plus integration (ACCEPT-WITH-CONDITIONS; B1 static-guards fixed in source per item 67; F1-F6 applied);
  - item 64 review-save notice.
- Validation, final tree, all PASS:
  - targeted vr-20261028-023059-e445ebf2
  - runtime-ui vr-20261028-023801-321ac210
  - known_good_state vr-20261028-024004-9cbada8f
  - static-guards vr-20261028-024253-7cc72b6d
  - lint vr-20261028-025756-1bd346c1
  - app-check vr-20261028-030213-fb01c0db (includes the androidTest compile)
  - the earlier d+e shards also PASS: domain-n-z, data, runtime-ui, ui_dao, cancellation, lint, app-check vr-20261028-013911-ff434979
- Committed. D4 retired (approved test deletions only). No release may ship S20 without S22 (55a), and G1b must run before any release containing S20.
- Next: S21-a (controller #2 timeout re-read + releaseOpportunity), then S21-b (held deep links), then S21-c (#10, OWNER DECISION PENDING item 69).
- The S21/S22 amendment is persisted in the plan.

### S21-a implementation (2026-10-28, implementer; NOT validated)

Nothing was built or run. Write set: `AppLockController.kt` plus three controller test files (`AppLockControllerRereadTest`, `AppLockControllerHeldLinkTest`, `AppLockControllerIllegalTransitionTest`). No guard, baseline, allowlist, diagnostic-code or schema change.

First pass (an implementer that died on an API timeout; its uncommitted work was kept) and the second-pass verification:
- Controller. `transientUnreadableLocked` now also returns true for `loadState == null && resolvedByTimeout` (KDoc updated at `launchReread`, `requestSettingsReread` and `transientUnreadableLocked`). `applyRereadLocked` takes priorCause = TIMEOUT for that prior view (the existing D9 `claimCodeCause(LOCK_SETTINGS_RECOVERED, ...)` path; no new code) and clears `resolvedByTimeout` on apply. `launchReread` bounds the read with `withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) { readLoadState() }`: null means no change and the foreground in-flight flag is released through `invokeOnCompletion`. `readLoadState` itself was unbounded and rethrows `CancellationException`. `onLateSettingsResolved` (T6b) and `onSettingsReread`'s T29 keep path are untouched. `applyRereadLocked` is reachable only from UNLOCKED or INERT (T28) and the post-unlock keep (T29), so no path opens LOCKED. New public `releaseOpportunity: StateFlow<Long>` (private `MutableStateFlow(0L)` behind `asStateFlow()`), bumped in `onOpenOriginApplyCompleted` after the monitor is released, only when the id matched the T33 gate and a link is held; it carries no link data.
- Tests: `AppLockControllerRereadTest` has 14 "61c-2" cases; `AppLockControllerHeldLinkTest` has 5 "S21-a releaseOpportunity" cases; `AppLockControllerIllegalTransitionTest` has the pins.
- Pins: `getReleaseOpportunity` is an exact addition to the public-method-set pin and to the signature pin (no args, returns `StateFlow`, the only member matching `eleaseOpportunity`). `releaseOpportunity` is in the controller-entry forbidden list, with no allowed file yet. S21-b must add the router-file allowance {onLockQuery, releaseHeldLink, releaseOpportunity}.

Completed in the second pass: nothing was missing in the source or the tests. Every item was verified against the code and the helpers the tests use (`resolveTimedOut`, `holdReads`, `cancelControllerJobs`, `startOpenOrigin`, `unlockedControllerFor`, ...). This handoff entry is the only edit.

Validation to request: targeted `*AppLockController*` (includes IllegalTransition, Reread and HeldLink; Resolution, Timer and Notice also run). Then in the S21 batch: `*DeepLink*`, `*MainScreenCallSite*`, shard domain-n-z.

Residual risk: the Reread timeout-bound tests use `advanceTimeBy` on the harness scheduler (not run). `signal.value is Long` in the HeldLink test may warn (USELESS_IS_CHECK); warnings are not errors in this build.

### S21-a review fixes (2026-10-28, test-author; NOT validated)

Test-only; nothing was built or run. Source of the privacy-security-guardian findings F1, F2, F4, F7 (F3 is deferred to the S21-b router tests: a bump while LOCKED returns None).
- F1 `AppLockControllerRereadTest` (cancellation test): a readable `DISABLED` result is scripted for the held read. After `cancelControllerJobs()` and before `releaseReads()` a second `onAppForegrounded()` + `runCurrent()` asserts `readCount == 3`. After the release the state is still UNLOCKED and `assertRecoveries` proves no LOCK_SETTINGS_RECOVERED was emitted.
- F2 (hang test): a readable `DISABLED` result is scripted for the hung read. After the release, UNLOCKED stays and no recovery is emitted (the timed-out read's result is never applied); the later readable re-read still recovers.
- F4 `AppLockControllerIllegalTransitionTest`: the forbidden token is now `eleaseOpportunity`, so it also matches the Java-facing `getReleaseOpportunity`.
- F7 `AppLockControllerHeldLinkTest`: removed the useless `is Long` checks and the `toString().contains` assertion; kept the "not a MutableStateFlow" check.
- Note: INERT(SETTINGS_UNREADABLE_NO_AUTH) to DISABLED via a readable re-read lets the fail-closed discreet and secure values give way to the stored values without auth, the same as existing T28.

Validation to request: targeted `*AppLockControllerRereadTest*`, `*AppLockControllerHeldLinkTest*`, `*AppLockControllerIllegalTransitionTest*` (or `*AppLockController*`).

### S21-a ACCEPTED (2026-10-28)
- Gate: privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS; C1 met; F1, F2, F4 and F7 applied; F3 is deferred to the S21-b router tests.
- Validation, final tree, all PASS:
  - targeted *AppLockController* vr-20261028-044851-7dd79d4c
  - domain-n-z vr-20261028-050642-9a099821
  - cancellation vr-20261028-050830-aff4cdc4
  - pre-fix targeted vr-20261028-041945-c5b073be
- 61c #2 closed (timeout re-read, LOCKED never relaxed).
- Committed.
- Next: S21-b (held deep links via AppLockDeepLinkRouter).

### S21-b implementation (2026-10-28, implementer; NOT validated)

Nothing was built or run (no shell was available to this implementer, so `git status` could not be run either). No schema, guard, baseline, allowlist, diagnostic-code or controller change.

Production:
- N `ui/applock/AppLockDeepLinkRouter.kt` (`@Inject`, unscoped; ctor = controller + `DeepLinkApplier`). `route(result, scope, vm)`: Accepted goes through `controller.onLockQuery(request)` (APPLY_NOW is `scope.launch { applier.apply }`, HOLD is `applier.onHeld`); Rejected asks the no-argument `controller.onLockQuery()` for the state (DISABLED, UNLOCKED, INERT: `scope.launch { applier.apply }`; RESOLVING, LOCKED, AUTHENTICATING: `applier.onRejectedWhileClosed`). `startReleasing(owner, vm)`: `owner.lifecycleScope` + `repeatOnLifecycle(STARTED)` + `combine(state, releaseOpportunity)`; on an open state it posts `ownerScope.launch(Dispatchers.Main)` (the lifecycle scope, not the repeat scope, so a stop does not cancel a release that is in flight) and inside that runs the one `when (controller.releaseHeldLink())`. A backgrounded app gets None from the controller (F1), and the next STARTED re-collects the current state, which is the ON_START opportunity.
- M `ui/navigation/DeepLinkApplier.kt`: `apply` keeps its signature (rejection reporting extracted to `reportRejection`; an accepted link goes through `applyAccepted(afterUnlock = false, reportDrops = true)`); `applyAccepted` gains `afterUnlock` and `reportDrops`; N `onHeld`, `onRejectedWhileClosed`, `applyReleased`, `applyExpired`; the private drops helper was renamed `reportDroppedParameters` (it clashed with the new `reportDrops` parameter name); KDoc updated.
- M `ui/MainActivity.kt`: hunks (1) field `deepLinkRouter`, (2) `deepLinkRouter.startReleasing(this, mainViewModel)` after `secureWindowController.bind(this)`, (3) `handleIntent` calls `deepLinkRouter.route(result, lifecycleScope, mainViewModel)` and the PRV-16 KDoc is rewritten. Plus two import lines (the `DeepLinkApplier` import removed because the new pin requires its absence from the sanitized file, and the router import added). onNewIntent and the intent block are untouched.

Tests:
- N `ui/applock/AppLockDeepLinkRouterTest` (Robolectric, PAUSED looper, real controller via the controller fixtures, real applier over strict mocks). Names (backticked in the file): AE-55 (hold, rotation with a new router, one navigation after unlock, second release None), AE-56, AE-57, AE-58 (both orders), AE-59, AE-60, RESOLVING-to-DISABLED (afterUnlock false), `61c 9` (RESOLVE_TIMEOUT to LOCKED held), T33 signal, AE-118, rejected while LOCKED, rejected while open, F1 background, S21-a F3, posted never inline, INERT applies now, unreadable store keeps the link held, two source-scan tests (no `immediate`/`Unconfined`/`runBlocking`, `releaseHeldLink` once and only inside `launch(Dispatchers.Main)`; no bind/install/attach names, no `fun interface`, no `@Composable`).
- N `ui/navigation/DeepLinkApplierHoldTest` (applyReleased true/false, no re-reported drops, lookup and briefing write only on release, onHeld drops only, onRejectedWhileClosed no navigation, applyExpired Home only, apply unchanged).
- M `DeepLinkDiagnosticsSentinelTest`: one held-then-released and held-then-expired case (bounded metadata, no sentinel strings).
- M `MainScreenCallSiteTest`: `s21_handleIntentRoutesOnlyThroughTheRouter`, `s21_startReleasingIsInOnCreateAfterTheDbGateAndBindAndBeforeHandleIntentAndSetContent`, `s20b_f6` extended (no `startReleasing(` in onNewIntent; exactly one `.startReleasing(` in the file), "onLockQuery" and "releaseHeldLink" added to the MainActivity forbidden list.
- M `AppLockControllerIllegalTransitionTest`: the controller-entry `when` gains `".../ui/applock/AppLockDeepLinkRouter.kt" -> setOf("onLockQuery", "releaseHeldLink", "eleaseOpportunity")`; comments reworded ("S21: exactly the router file (RP-1), not MainActivity"). `DeepLinkApplierTest` is unchanged (no visible behaviour change).

Docs: LEGAL_PATHS DEEP LINKS (ENTRY, new ROUTER block, APPLIER afterUnlock line, FORBIDDEN), SEGMENTS, INVENTORY, ENGINE. `hilt-bindings-map.md` was not touched (the router is a new unscoped `@Inject` class; add a line at the next docs pass).

Validation to request (one batch): targeted `*AppLockController*,*DeepLink*,*AppLockDeepLinkRouter*,*MainScreenCallSite*,*AppLockShell*,*MainViewModel*,*GsonR8KeepRulesContract*`; then known_good_state, cancellation, ui_dao guards in the S21 batch.

Residual risk (unrun):
- The Robolectric timing assumptions: the `isIdle == false` assertion in the "posted never inline" test assumes the state publication reaches the collector on the main thread before `runBlocking` returns; the lifecycle `repeatOnLifecycle` restart in the F1 test relies on a stop/start of a bare `LifecycleRegistry`.
- The S21-a F3 test drives a CONFIRM_CHANGE success, a 2 min background and a foreground to reach LOCKED(TIMEOUT) with the T33 gate still open; the controller keeps the gate in that case per the existing "gate lapsed" test, but this exact order was not run.
- R-S21-1 and R-S21-2 stay accepted. A release task already posted when the owner is stopped runs after the stop and gets None (F1); the link stays held until the next start.

### S21-b review fixes (2026-10-28, test-author; NOT validated)

Test and docs only; nothing was built or run. Source: privacy-security-guardian findings C2, C3, Info 3.
- C2 tests in `AppLockDeepLinkRouterTest` (next to F1): `C2 a release task posted before a stop runs after it, gets nothing in the background and the link is released once at the next start` (route while RESOLVING, resolve, background, owner stop, idle: no lookup and no navigation; foreground + start + idle: exactly one day navigation) and `C2 a released lookup that completes only after the owner stops still applies and is not lost` (the lookup is gated on a CompletableDeferred; stop, then complete, idle: one day navigation).
- Optional router cases: `a link while AUTHENTICATING for the unlock is held and applies once after the success`; `a rejected link while RESOLVING records one bounded code, never navigates and leaves the held slot untouched`.
- C3: R-S21-1 in the plan and in LEGAL_PATHS (ROUTER block) now says the bounded DL_APPLIED / DL_BRIEFING_* outcome may be recorded after the re-lock.
- Info 3: LEGAL_PATHS ROUTER block gains an ordering note (open app + open T33 gate: a rejected link goes Home at once, an older held link applies after the gate closes; not a privacy issue).
- `hilt-bindings-map.md`: line added for `AppLockDeepLinkRouter` (unscoped `@Inject`, per Activity; AppLockController, DeepLinkApplier).

Validation to request: targeted `*AppLockDeepLinkRouterTest*` (with the S21-b batch filter above).

Residual risk (unrun): the second C2 test assumes the gated lookup resumes on the main looper after `gate.complete` + `idle()` and that `applyReleased` awaits the lookup in the posted task; the AUTHENTICATING test assumes `h.tap` leaves the controller in `Authenticating` with the link held (no diagnostic code is asserted there).

### S21-b VALIDATED (2026-10-28)
- Gate: privacy-security-guardian (lead) ACCEPT-WITH-CONDITIONS. C2, C3 and Info 3 are applied as above (test and docs only).
- Validation, one batch on the final S21-b tree, all PASS:
  - targeted `*AppLockController*,*DeepLink*,*AppLockDeepLinkRouter*,*MainScreenCallSite*,*AppLockShell*,*MainViewModel*,*GsonR8KeepRulesContract*`: vr-20261028-063825-6752a72d
  - runtime-ui: vr-20261028-064442-42bd6eea
  - domain-n-z: vr-20261028-064659-9b264954
  - known_good_state: vr-20261028-064847-a1b1d9b1
  - ui_dao: vr-20261028-065135-3fbeea78
  - cancellation: vr-20261028-065154-36f6c258
- #9 is closed: handleIntent routes only through AppLockDeepLinkRouter; a held link is released through releaseHeldLink (posted on Dispatchers.Main).
- Still due at the S21 candidate (a + b + c): architecture-guardian on S21-b, reviewer-strict on the whole of S21, then static-guards, lint and app-check.

### Owner decision (owner-approvals 73, 2026-10-28)
- 73a: #10 / S21-c option (A) is APPROVED. Device auth runs before the UnsupportedDatabaseActivity export when the committed lock is on or the store is unreadable, through one narrow read-only Hilt @EntryPoint.
  - The R25 pin may allow exactly one `EntryPointAccessors.fromApplication(`. All other R25 forbidden tokens stay.
- 73b: the merge plan is approved. app-lock S20 + S21 go to feature/ai-evolution after the S21 candidate gates pass, after nl-quick-add.
- Next: S21-c implementation (feature-implementer), in parallel with an architecture-guardian review of S21-b.

### S21-c implementation (2026-10-28, implementer; NOT validated)

Nothing was built or run. No schema, permission, manifest, guard, baseline, allowlist, diagnostic-code, controller, MainActivity or router change. No STOP condition was hit.

Files changed (P = app/src/main/java/com/yourname/expensetracker):
- N `P/ui/unsupported/UnsupportedDatabaseExportAuth.kt`: public `@EntryPoint @InstallIn(SingletonComponent)` `UnsupportedDatabaseExportAuthEntryPoint` (exactly `appLockSettingsRepository()` and `deviceAuthCapabilityProvider()`); `internal enum ExportLockRead {ON, OFF, UNREADABLE}`; `internal const EXPORT_AUTH_READ_TIMEOUT_MILLIS = 2000`; `internal object UnsupportedDatabaseExportAuth` with `isAuthRequired(context)` (the ONE `EntryPointAccessors.fromApplication(`), `decide(repository?, provider?, timeoutMillis)`, `readLock`, `readCapability`, `capabilityRequiresAuth`.
- M `P/ui/unsupported/UnsupportedDatabaseActivity.kt`: `ComponentActivity` becomes `FragmentActivity`; per-Activity `BiometricPromptDeviceAuthenticator(this)` built in `onCreate` after the gate check; new private states CHECKING, AUTHENTICATING, AUTH_DENIED; the export button now calls `onExportRequested { launcher.launch(...) }`, which reads the gate at the tap and opens the SAF picker only when auth is not required or succeeded. `writeBackupTo`, `writeBackupZip`, `deletePartialOutput` and the existing `UNSUPPORTED_DB_*` codes are unchanged.
- M `res/values/strings.xml`, `values-el/strings.xml`, `values-es/strings.xml`: one new string `unsupported_db_export_auth_denied` (EN final; el and es carry the comment `DRAFT: owner native sign-off pending`).
- M test (pin only) `app/src/test/.../ui/unsupported/UnsupportedDatabaseActivityTest.kt`: the R25 pin is renamed `activity surface has no hilt activity room privacy gate or export coupling and exactly one entry point lookup`; it scans the activity file AND `UnsupportedDatabaseExportAuth.kt` for @AndroidEntryPoint, AppDatabase, Dao, exportDatabase, PrivacyGate (all still forbidden) and requires exactly one `EntryPointAccessors.fromApplication(` across the two.
- Docs: LEGAL_PATHS (DATABASE OPEN GATE: auth gate paragraph and two FORBIDDEN lines), hilt-bindings-map (new entry point section), CODEBASE_SEGMENTS (new file line), PRIVACY_UI_ARCHITECTURE (one clause). CODEBASE_INVENTORY lists no `ui/unsupported` file, so it is untouched.

Decisions:
- Graph check: `AppLockSettingsRepositoryImpl` takes `@ApplicationContext Context` only (a dedicated DataStore); `AndroidDeviceAuthCapabilityProvider` takes `@ApplicationContext Context` only. No AppDatabase, Dao or Room-backed binding is reachable.
- Capability rule (spec silent on the export; chosen to match the lock state machine T4/T6/T7b/T16 where capability other than AVAILABLE is INERT and the app opens with no prompt): auth required iff (lock ON, or store unreadable, read threw, read timed out at 2 s, or the entry point could not be resolved) AND capability in {AVAILABLE, CHECK_FAILED, no answer}. UNSUPPORTED_API, NO_CREDENTIAL, HW_UNAVAILABLE and UNKNOWN: not gated (nothing can be asked; gating would lock the user out of the only data-recovery path). FirstRunDefault reads as its settings (lock off). Lock OFF: no prompt, the capability is not even read.
- Prompt copy: reuses `AuthPromptCopy.UNLOCK` (constant title "Unlock", no subtitle). A dedicated EXPORT copy would need a new enum value in the domain port plus adapter and pin changes, so it was not done.
- Where auth sits: at the tap, before the SAF picker, so nothing is created at the destination before auth. There is no "authorised" flag carried through the picker result (a rotation during the picker must not lose a legitimate export); the exposure is that auth precedes the picker rather than the write.
- Outcome: every non-Succeeded outcome and every non-LAUNCHED launch (including below API 30, host unavailable, prompt unavailable) gives AUTH_DENIED with one constant string. No log line, no new diagnostic code, no allowlist entry; no outcome detail, path or exception text is kept. A late or repeated callback of an older attempt is ignored.
- The entry point is public (matching `AppStartupEntryPoint`); the object and enum are internal.
- Parser traps avoided: all parameter types imported or builtin, no `@Composable` function-type parameter, no top-level `fun interface`, no top-level fun in the new file (only a const, an enum, an interface and an object).

Test-author work (AE and cases to write):
- N `UnsupportedDatabaseExportAuthTest` (pure, runTest, fakes for the two ports):
  1. lock ON + AVAILABLE -> required; lock ON + CHECK_FAILED -> required;
  2. lock ON with UNSUPPORTED_API, NO_CREDENTIAL, HW_UNAVAILABLE, UNKNOWN -> not required (one case each, parameterised);
  3. lock OFF (Loaded and FirstRunDefault) with every capability -> not required, and the capability port is not called;
  4. `Unreadable` (each cause: IO_ERROR, CORRUPTED, INVALID_VALUE, TIMEOUT if present) + AVAILABLE -> required; + NO_CREDENTIAL -> not required;
  5. repository that never returns (virtual time) -> UNREADABLE at 2000 ms -> required with AVAILABLE; one just under 2000 ms returning OFF -> not required;
  6. repository that throws RuntimeException -> UNREADABLE; capability provider that throws -> treated as no answer -> required;
  7. a CancellationException from the repository or the provider is rethrown (not swallowed), and cancelling the caller during the read propagates;
  8. null ports (entry point unresolved) -> required;
  9. no exception text or setting value in any return value (the result is a boolean).
- M `UnsupportedDatabaseActivityTest` / N `UnsupportedDatabaseActivityExportAuthTest` (Robolectric; the lock state is injected through Hilt test wiring or a Robolectric Application with a test entry point, whichever the existing test setup supports; if the test app is not Hilt, resolve failure must behave as "unreadable" and the prompt path on API 28 ends as AUTH_DENIED):
  1. tap with auth not required -> picker launched, no prompt;
  2. tap with auth required and Succeeded -> picker launched exactly once; no document created before Succeeded;
  3. Cancelled / LockedOut / NotAvailable / Failed / Succeeded from an older attempt id -> no picker, state AUTH_DENIED with `unsupported_db_export_auth_denied` shown;
  4. authenticate returns HOST_UNAVAILABLE or PROMPT_UNAVAILABLE -> AUTH_DENIED, no picker;
  5. a second tap while CHECKING or AUTHENTICATING does nothing (button disabled);
  6. a synchronous callback before `authenticate` returns still works (state recorded first);
  7. the existing tests (launch, zip content, gate-not-blocking finish, cleanup) still pass with a `FragmentActivity` base;
  8. the gate-not-blocking finish path builds no authenticator.
  (The authenticator is a private field today; the test-author may add an `internal` constructor seam or drive the real adapter under Robolectric. Any such seam is a source change and goes back to the implementer.)
- Source scans: a case in `UnsupportedDatabaseExportAuthTest` that the new file has no `fun interface`, no `@Composable`, no `Dao`, no `AppDatabase`, and exactly one entry point lookup (the pin in `UnsupportedDatabaseActivityTest` already covers the last).
- Strings parity: en/el/es all carry `unsupported_db_export_auth_denied`.

Targeted validation filter to request (one combined batch with the rest of S21): `*UnsupportedDatabase*,*AppLockBiometricSourceScan*,*AppLockRelaySourceScan*,*BiometricPrompt*,*AppLockControllerIllegalTransition*,*GsonR8KeepRulesContract*`; then guards known_good_state, cancellation, ui_dao; lint (MissingTranslation, hardcoded text); then the S21 candidate gates (static-guards, app-check). A Hilt compile (kapt/ksp) of the new `@EntryPoint` is exercised by any app build.

Residual risks (unrun):
- Robolectric: `BiometricPromptDeviceAuthenticator(this)` is now built in `onCreate` of the unsupported screen; the existing activity tests run it at SDK 28. Other tests construct the adapter under Robolectric, so it is expected to be fine.
- The `Theme.ExpenseTracker` manifest theme is used by `FragmentActivity` here exactly as for `MainActivity`; not checked on a device. The G1b list should include a device check of #10: lock ON, forced gate (schema too new), tap export -> prompt -> picker; cancel -> message, no picker.
- A `FragmentActivity` is created for a process whose Room cannot open; the Hilt component is the same one the Application already builds.
- The decision to not gate INERT capability states (see above) is a judgement the privacy-security-guardian and the owner should confirm; the stricter reading would lock a user out of export on those devices.
- A rotation during the prompt loses that attempt (the user taps again); a rotation during the picker keeps the export (no flag).
- el and es strings are DRAFT until the owner signs off.

### S21-c tests + S21-b architecture fixes (2026-10-28, test-author; NOT validated)

Test and docs only; nothing was built or run; no production source was edited. T = app/src/test/java/com/yourname/expensetracker.

Files (new):
- N `T/ui/unsupported/UnsupportedDatabaseExportAuthTest.kt` (pure, `runTest`, virtual time; reuses the internal `FakeAppLockSettingsRepository` and `FakeCapabilityProvider` fixtures).
- N `T/ui/unsupported/UnsupportedDatabaseActivityExportAuthTest.kt` (Robolectric sdk 28, `ActivityScenario` + `createEmptyComposeRule`; the DENIED branch only).
- N `T/ui/unsupported/UnsupportedDatabaseActivitySourceScanTest.kt` (structural pins standing in for the SEAM-NEEDED behaviours).
- N `T/ui/unsupported/UnsupportedDatabaseExportAuthStringsTest.kt` (Robolectric sdk 34; en / es / el parity for `unsupported_db_export_auth_denied`; no existing test covered it).
- N `T/ui/applock/DeepLinkApplierCallerPinTest.kt` (S21-b architecture-guardian P2).
- Docs: LEGAL_PATHS (onCreate order and the handleIntent sentence), hilt-bindings-map (DeepLinkApplier is injected only through the router), CODEBASE_SEGMENTS (own S21-b heading, S21-c moved to its own heading, `startReleasing`/`route` added to the S20-c MainActivity line), CODEBASE_INVENTORY (S21-b bullet).

Tests by name (all `s21c_` or `s21b_` prefixed, backticks not needed):
- `UnsupportedDatabaseExportAuthTest`:
  - matrix: `everyCapabilityIsDecidedExactlyOnce`, `capabilityRule_perValue`, `lockOn_available_requiresAuth_control`, `lockOn_checkFailed_requiresAuth`, `lockOn_inertCapabilities_doNotRequireAuth`, `firstRunDefaultWithLockFlagOn_isTreatedAsLockOn`;
  - lock OFF: `lockOff_loaded_neverRequiresAuth_andNeverReadsTheCapability`, `lockOff_firstRunDefault_...`, `lockOff_evenWhenTheCapabilityProviderWouldThrow_doesNotRequireAuth`;
  - UNREADABLE (all four causes): `unreadable_everyCause_isNeverReadAsOff`, `..._withAvailable_requiresAuth`, `..._withCheckFailed_requiresAuth`, `..._withInertCapabilities_doesNotRequireAuth`;
  - timeout: `theTimeoutConstantIs2000Millis`, `aReadThatNeverReturns_isUnreadableAtExactly2000Millis_andRequiresAuthWhenAvailable`, `aReadThatNeverReturns_readLockIsUnreadable`, `..._withNoCredential_doesNotRequireAuth`, `readJustUnderTheBound_returnsItsValue_lockOffIsNotGated` (1999 ms), `..._lockOnIsGated`, `readJustOverTheBound_isUnreadable_evenThoughItWouldHaveSaidOff` (2001 ms), `aTimedOutReadDoesNotCancelTheCaller_theNextCallReadsFresh`;
  - throws: `aRepositoryThatThrows_isUnreadable_andRequiresAuthWhenAvailable`, `..._withInertCapability_doesNotRequireAuth`, `aProviderThatThrows_isNoAnswer_andRequiresAuthWhenTheLockIsOn`, `aProviderThatThrows_requiresAuthWhenTheStoreIsUnreadable`, `readCapability_returnsTheValueAndEvaluatesEveryCall`;
  - stale snapshots: `everyCallReadsTheLockFresh`, `everyCallEvaluatesTheCapabilityFresh`;
  - cancellation: `aCancellationExceptionFromTheRepository_isRethrown_notSwallowed`, `aCancellationExceptionFromTheProvider_isRethrown_notSwallowed`, `cancellingTheCallerDuringTheRead_propagates_andNothingIsDecided`;
  - null ports: `nullPorts_requireAuth`, `nullRepository_isUnreadable_andFollowsTheCapability`, `nullProvider_isNoAnswer`;
  - source scan: `sourceScan_newFileHasNoFunInterfaceComposableDaoAppDatabaseLoggingOrWrites`, `sourceScan_exactlyOneEntryPointLookup`, `sourceScan_entryPointIsSingletonScopedWithExactlyTwoReadMembers`, `sourceScan_selfCheck_eachRuleCanFail`.
- `UnsupportedDatabaseActivityExportAuthTest` (real activity, entry point unresolvable under `HiltTestApplication` without a rule, so the gate fails closed; real adapter at SDK 28 answers HOST_UNAVAILABLE): `activityIsAFragmentActivity_forThePromptHost`, `beforeAnyTap_theExportButtonIsEnabled_andNoDeniedMessageIsShown`, `tapWithAuthRequiredAndNoPromptAvailable_showsTheConstantDeniedMessage_andOpensNoPicker`, `aDeniedTap_keepsTheButtonUsable_andRecordsNoLogLineOrDiagnostic`, `aSecondTapAfterADeniedAttempt_triesAgain_andStillOpensNoPicker`.
- `UnsupportedDatabaseActivitySourceScanTest` (pins): `gateNotBlockingFinishPath_buildsNoAuthenticator`, `thePickerIsOpenedOnlyFromTheExportGate`, `aStaleRepeatedOrLateOutcome_isIgnoredBeforeItCanOpenThePicker`, `theAttemptIsRecordedOpenBeforeAuthenticateRuns`, `aNonLaunchedAnswerOrAMissingAuthenticator_failsClosed`, `aSecondTapWhileCheckingAuthenticatingOrRunning_doesNothing`, `theAuthPathWritesNoLogLineAndNoDiagnostic`.
- `UnsupportedDatabaseExportAuthStringsTest`: `defaultDeniedMessage_isNonBlankAndPlaceholderFree`, `spanishDeniedMessage_existsAndDiffersFromDefault`, `greekDeniedMessage_existsAndDiffersFromDefault`, `threeLocalesCarryThreeDistinctTexts`, `theStringIsDeclaredExactlyOnceInEachResourceFile`.
- `DeepLinkApplierCallerPinTest`: `s21b_scanCoversTheProductionTreeAndTheAllowedFilesExist`, `s21b_deepLinkApplierIsReferencedOnlyByItsOwnFileAndTheRouter`, `s21b_noStringLiteralNamesTheApplier`, `s21b_heldLinkReleaseApplyIsConstructedOnlyInTheController`, `s21b_theRouterMatchesOnAReleasedLinkButNeverConstructsOne`, and three `selfCheck_*` planted cases.

Pin allow-sets (verified by text search before writing, whole of app/src; debug and release have no reference):
- token `DeepLinkApplier` (main): exactly `ui/navigation/DeepLinkApplier.kt` and `ui/applock/AppLockDeepLinkRouter.kt`.
- `HeldLinkRelease.Apply(` construction: exactly `domain/privacy/applock/AppLockController.kt` (line 1444). DEVIATION from the brief ("controller file and the router"): the router has no construction, it only matches `is HeldLinkRelease.Apply`, so the pin is stricter and the router is not in the allow-set (a separate test asserts it matches but never constructs). An unqualified `Apply(` is allowed only in `HeldDeepLink.kt` (the declaration).

SEAM-NEEDED (not reachable without a production change; no reflection hack was used):
- The lock state and the authenticator of `UnsupportedDatabaseActivity` are not injectable: `UnsupportedDatabaseExportAuth.isAuthRequired(applicationContext)` is called directly and `exportAuthenticator` is a private field built with `BiometricPromptDeviceAuthenticator(this)`. Blocked as a result: auth not required then picker with no prompt; auth required + Succeeded then picker exactly once; Cancelled / LockedOut / NotAvailable / Failed / stale attempt id; PROMPT_UNAVAILABLE and the other non-LAUNCHED answers (only HOST_UNAVAILABLE is reached, through the real adapter at SDK 28); a double tap while CHECKING or AUTHENTICATING; a synchronous callback before `authenticate` returns; the behavioural form of "the gate-not-blocking finish path builds no authenticator".
- Smallest seam proposed (implementer decides): two `@VisibleForTesting internal` vars in the `UnsupportedDatabaseActivity` companion, `exportAuthRequired: suspend (Context) -> Boolean` (default `UnsupportedDatabaseExportAuth.isAuthRequired`) and `authenticatorFactory: (FragmentActivity) -> DeviceAuthenticator` (default `BiometricPromptDeviceAuthenticator(it)`), reset in `@After`. With them the missing cases are about 10 Robolectric tests using `FakeDeviceAuthenticator` and a `CompletableDeferred` gate for the CHECKING state; the picker is observable already through `shadowOf(activity).peekNextStartedActivityForResult()`. Needs privacy-security-guardian view because it adds mutable static test state to a production class; the alternative with no production change is the first `@HiltAndroidTest` (rule + `@BindValue`/`@UninstallModules`), which pulls the whole app graph into the unit-test Hilt component and was judged riskier unrun.
- Until then the structural pins in `UnsupportedDatabaseActivitySourceScanTest` stand in, and G1b keeps the on-device #10 check.

Recorded residual R-S21-3 (architecture-guardian): in multi-window, two started MainActivity instances can both observe the controller; the held link goes to whichever posted release task runs first. The controller hands a held link out at most once (removed under its monitor), so a link is never applied twice and there is no privacy impact; at most the navigation lands in the other window.

Combined targeted filter to request (one batch): `*UnsupportedDatabase*,*AppLockBiometricSourceScan*,*AppLockRelaySourceScan*,*BiometricPrompt*,*AppLockControllerIllegalTransition*,*GsonR8KeepRulesContract*,*DeepLinkApplier*,*MainScreenCallSite*`. The new classes are covered by `*UnsupportedDatabase*` (ExportAuthTest, ActivityExportAuthTest, ActivitySourceScanTest, ExportAuthStringsTest) and `*DeepLinkApplier*` (DeepLinkApplierCallerPinTest); no extra filter is needed. Then guards known_good_state, cancellation, ui_dao, lint (MissingTranslation, hardcoded text), as listed in the S21-c implementation section.

Residual risks (unrun):
- `UnsupportedDatabaseActivityExportAuthTest` assumes `EntryPointAccessors.fromApplication` on `HiltTestApplication` without a Hilt rule throws an `Exception` (an IllegalStateException), which the gate maps to fail closed. If it ever resolved (lock off) the first-tap tests would see the picker, not the denied message; if it threw an Error it would crash the activity. It is also the first use of `ActivityScenario` + `createEmptyComposeRule` in this repo (existing tests use `Robolectric.buildActivity` and `createComposeRule`).
- The "no picker" assertions there have no positive control until the seam exists (a picker launch is assumed to show in `ShadowActivity`).
- The pure suite does not assert at exactly 2000 ms: whether a read completing at the bound wins is dispatcher-order dependent. It brackets with 1999 ms (value kept) and 2001 ms (timeout wins) and pins that a never-returning read ends at exactly 2000 ms.
- The pure suite pins the owner-decision table (INERT capabilities not gated). That is the judgement the privacy-security-guardian and owner still confirm; a change there is a one-table edit.
- The source-scan pins are intentionally structural and will need an edit when the activity is refactored; the DeepLinkApplier pin also rejects a new mention of the class in any main, debug or release text file, not only an import.
- `DeepLinkApplierCallerPinTest` reads every Kotlin, Java and common text file under app/src/main, debug and release (a few thousand small files).

### S21-c review fixes (2026-10-28, implementer; NOT validated)

Nothing was built or run. Source of the fixes: privacy-security-guardian review plus the orchestrator decision under standing approval item 62. No schema, manifest, permission, guard, baseline, allowlist, diagnostic-code, string or controller change. P = app/src/main/java/com/yourname/expensetracker.

Fixes:
1. (P3, liveness) `P/ui/unsupported/UnsupportedDatabaseActivity.kt` new `onStop`: when `exportState == AUTHENTICATING` it calls `exportAuthenticator?.cancel(exportAuthAttemptId)`. The adapter delivers `Failed(SYSTEM_CANCELED)` synchronously, which reaches `onExportAuthOutcome` and ends in AUTH_DENIED with no picker. If no outcome was delivered (a fake, a stale id) and the state is still AUTHENTICATING after `cancel`, it is set to AUTH_DENIED directly (fail closed). `super.onStop()` is called last.
2. (P3) The CHECKING coroutine in `onExportRequested` is wrapped in `try { ... } finally { if (exportState == ExportState.CHECKING) exportState = ExportState.IDLE }`. There is no catch, so `CancellationException` (and any other throwable) still propagates. The finally only resets the state; the picker is called only from the try body (not required) and from `onExportAuthOutcome` (Succeeded).
3. (P3) KDoc "Capability rule, Inc 2 note" on `UnsupportedDatabaseExportAuth` in `P/ui/unsupported/UnsupportedDatabaseExportAuth.kt` (PIN mode is never INERT, ux-flows.md:211, T6 "no verifier"; NO_CREDENTIAL with PIN mode must require auth once a lock mode exists). Plan item added under S21-c in `workflows/active/app-lock-privacy-mode-plan.md`: "S21-c follow-up (Inc 2): export gate must treat PIN-mode locks as never INERT".
4. (P3) `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` :141 now reads "gated by device auth instead when the lock is on or unreadable and capability is AVAILABLE or CHECK_FAILED (INERT states are not gated)".
5. Test seams (both in the `UnsupportedDatabaseActivity` companion, `@VisibleForTesting internal var`, default null, always null in production; a test sets them and resets them to null in `@After`):
   - `UnsupportedDatabaseActivity.exportAuthRequiredForTest: (suspend (android.content.Context) -> Boolean)?`. When non-null, `onExportRequested` calls it with `applicationContext` instead of `UnsupportedDatabaseExportAuth.isAuthRequired(applicationContext)`. A suspending fake (for example one awaiting a `CompletableDeferred`) holds the CHECKING state. It runs inside the try/finally of fix 2.
   - `UnsupportedDatabaseActivity.authenticatorFactoryForTest: ((androidx.fragment.app.FragmentActivity) -> DeviceAuthenticator)?`. When non-null, `onCreate` builds `exportAuthenticator = authenticatorFactoryForTest?.invoke(this) ?: BiometricPromptDeviceAuthenticator(this)` (after the gate-not-blocking finish/return, so that path still builds nothing). Set both seams BEFORE the activity is created; the factory is read once in `onCreate`.
   - Kotlin access: both are companion members, so a test in the same module uses `UnsupportedDatabaseActivity.exportAuthRequiredForTest = { _ -> true }` and `UnsupportedDatabaseActivity.authenticatorFactoryForTest = { FakeDeviceAuthenticator(...) }`.
   - known_good_state parser: scripts/kotlin_callable_parser.py scans only `fun` keywords and reads only parameter lists, so function-type properties are not parsed as signatures (precedent: `RestoreJournal.kt` has `internal var ...: ((File, String) -> Unit)? = null`). No `fun interface` was added; no new top-level declaration; no new fun, so no new callable signature. The companion is not a parser owner, so the existing companion funs are unchanged.

Test-author work:
- Existing pins still hold by construction: `BiometricPromptDeviceAuthenticator(this)` is still the only construction (one match of `BiometricPromptDeviceAuthenticator(`) and sits in `onCreate` after the early return and before `setContent`; `launchPicker()` still appears once in `onExportRequested` and once in `onExportAuthOutcome`; the auth-path scan range (`fun onExportRequested(` to `fun startExport(`) now also contains `onStop` and the try/finally, neither has a forbidden token. `UnsupportedDatabaseActivityTest` R25 forbidden tokens are not present in the new code. Re-check `s21c_gateNotBlockingFinishPath_buildsNoAuthenticator` (it matches the literal `BiometricPromptDeviceAuthenticator(this)`, which is kept).
- New Robolectric cases, unblocked by the seams: auth not required then picker with no prompt; required + Succeeded then picker exactly once and no document before Succeeded; Cancelled, LockedOut, NotAvailable, Failed and a stale attempt id give AUTH_DENIED with no picker; PROMPT_UNAVAILABLE, HOST_UNAVAILABLE and the other non-LAUNCHED answers give AUTH_DENIED; double tap during CHECKING (held by the suspending gate fake) and during AUTHENTICATING does nothing; a synchronous callback before `authenticate` returns; the gate-not-blocking finish path never invokes the factory.
- New for fixes 1 and 2: `onStop` while AUTHENTICATING calls `cancel` with the open attempt id, ends AUTH_DENIED, opens no picker (fake that delivers Failed(SYSTEM_CANCELED) on cancel, and a fake that delivers nothing); `onStop` while IDLE or CHECKING does not call `cancel`; cancelling the CHECKING coroutine (destroy the activity or cancel the gate fake) leaves the state IDLE and opens no picker; a gate that throws a non-cancellation exception resets the state and the exception still propagates.
- Reset both seams to null in `@After` (static mutable state).

Validation to request (add to the S21 combined batch): the same targeted filter `*UnsupportedDatabase*,*AppLockBiometricSourceScan*,*AppLockRelaySourceScan*,*BiometricPrompt*,*AppLockControllerIllegalTransition*,*GsonR8KeepRulesContract*,*DeepLinkApplier*,*MainScreenCallSite*`; guards known_good_state (the seam properties), cancellation (the new try/finally in a coroutine), ui_dao; lint.

Residual risks (unrun):
- `onStop` cancels an in-flight prompt, so the user leaving the app during the prompt must tap export again (the intended outcome). A configuration change also stops the activity, so a rotation during the prompt now ends the attempt as AUTH_DENIED instead of leaving the state AUTHENTICATING on a dead adapter.
- The seams add mutable static state to a production class (guardian review requested; they are `internal`, annotated, and null by default).

### S21-c seam tests + pin hardening (2026-10-28, test-author; NOT validated)

Test and handoff only; nothing was built or run; no production source was edited. T = app/src/test/java/com/yourname/expensetracker/ui/unsupported.

Files:
- N `T/UnsupportedDatabaseActivityExportAuthSeamTest.kt` (Robolectric sdk 28, `ActivityScenario` + `createEmptyComposeRule`, same pattern as `UnsupportedDatabaseActivityExportAuthTest`). Both seams are set before the activity is created and reset to null in `@After`. Fakes: `FakeDeviceAuthenticator` (records `authenticate`/`cancel` ids and copies; optional synchronous outcome; optional outcome delivered on `cancel`; the test delivers outcomes through the stored callback) and `HeldGate` (suspending gate held by a `CompletableDeferred`). The picker is observed through `shadowOf(activity).nextStartedActivityForResult` (action `ACTION_CREATE_DOCUMENT`), which the positive control consumes and re-checks as null, so "exactly once" is asserted.
- M `T/UnsupportedDatabaseActivitySourceScanTest.kt` (pin hardening, the surface is the activity file plus `UnsupportedDatabaseExportAuth.kt`).

Tests by name (`UnsupportedDatabaseActivityExportAuthSeamTest`, all `s21c_seam_` prefixed):
- not required: `authNotRequired_opensThePickerWithoutAnyPrompt`;
- positive controls: `requiredAndSucceeded_opensThePickerExactlyOnce_control`, `aRepeatedSucceededCallback_doesNotOpenASecondPicker`, `aDeniedAttempt_canBeRetried_andASucceededRetryOpensThePicker`, `whenTheGateBlocks_theFactoryIsInvokedOnceWithTheActivity_control`;
- outcomes (denied string, no picker, button usable): `cancelled_`, `lockedOutTemporary_`, `lockedOutPermanent_`, `notAvailable_`, `failed_everyBucket_` (all four buckets), each `...deniesWithNoPicker`;
- stale ids: `aStaleAttemptId_isIgnored_andTheOpenAttemptStillDecides`, `aLateSucceededOfAClosedEarlierAttempt_neverOpensThePicker`;
- non-LAUNCHED answers (`ALREADY_IN_FLIGHT`, `HOST_UNAVAILABLE`, `AWAITING_PRIOR_TERMINAL`, `PROMPT_UNAVAILABLE`): `launchAnswer<X>_deniesWithNoPicker`, each also delivers a late Succeeded and asserts no picker;
- double tap: `aSecondTapWhileChecking_doesNothing` (gate held, gate calls stay 1, then released to one prompt and one picker), `aSecondTapWhileAuthenticating_doesNothing`;
- synchronous callback: `aSynchronousSucceededInsideAuthenticate_opensThePickerOnce`, `aSynchronousCancelledInsideAuthenticate_deniesWithNoPicker`;
- finish path: `whenTheGateDoesNotBlock_theActivityFinishesAndTheFactoryIsNeverInvoked` (with the control above);
- onStop (via `moveToState(CREATED)` then `RESUMED`, the denied message is read after the restart): `onStopWhileAuthenticating_cancelsTheOpenAttempt_andEndsDeniedWithNoPicker_whenCancelDelivers`, `..._whenNothingIsDelivered` (also a late Succeeded for the closed id), `aSecondStopAfterTheAttemptEnded_doesNotCancelAgain`, `onStopWhileIdle_doesNotCancel`, `onStopWhileChecking_doesNotCancel`;
- CHECKING coroutine: `cancellingTheCheckingCoroutine_leavesTheStateIdleWithNoPicker` (`lifecycleScope` children cancelled), `aGateThatThrowsCancellation_leavesTheStateIdleWithNoPicker`, `aGateThatThrowsANonCancellation_resetsTheState_andTheExceptionStillPropagates`.

Pin hardening (`UnsupportedDatabaseActivitySourceScanTest`, `s21c_pin_` prefixed):
- `entryPointMembersAreExactlyTheLockRepositoryAndTheCapabilityProvider` (member names AND return types as an exact map, nothing else declared in the body, and the two domain imports present; the earlier `ExportAuthTest` case only checked the names);
- `theSurfaceHasAtMostOneEntryPointAndNoOtherLookupForm` (`@EntryPoint` count across both files is 1, no `EntryPoints.get(`, `fromActivity(`, `fromFragment(`, exactly one `fromApplication(`);
- `theSeamsAreDeclaredOnceAsNullInternalVarsInTheActivityCompanion`;
- `noProductionFileAssignsATestSeam` (every .kt/.java under app/src main, debug, release; flags `name =` and a `setName(` call; the declaration lines and reads are allowed);
- planted self-checks: `selfCheck_theEntryPointMemberRuleCanFail` (wrong type, swapped types, extra, missing, parameter, property), `selfCheck_theLookupAndEntryPointCountRulesCanFail`, `selfCheck_theSeamAssignmentRuleCanFail` (also asserts declarations, reads, `==`, comments and strings are allowed).

Deviations from the brief:
- A stale attempt id does NOT give the AUTH_DENIED string. The activity ignores it (state stays AUTHENTICATING, button disabled, no message). The tests assert that, then that the real outcome of the open attempt decides (Cancelled gives the message; Succeeded opens the picker). A closed earlier attempt's late Succeeded is asserted not to open the picker.
- The "missing authenticator" fail-closed branch (`exportAuthenticator == null`) cannot be reached through the factory seam (its type is non-null); it stays covered by the structural pin `s21c_aNonLaunchedAnswerOrAMissingAuthenticator_failsClosed`.

SKIPPED-WITH-REASON: none. Judgement call on the non-cancellation throw: it cannot reach the main looper as a crash in the JVM because the test installs a capturing uncaught-exception handler (on the test thread and as the process default, both restored in a `finally`) before the tap. The test asserts the state reset (button enabled, no message, no prompt, no picker) and that the very exception instance reached that handler. If a Robolectric or kotlinx version routes an uncaught coroutine exception elsewhere (for example rethrows it from the looper), only the last assertion would fail, not the JVM; then drop that last line and keep the state assertions.

Filter (unchanged): `*UnsupportedDatabase*,*AppLockBiometricSourceScan*,*AppLockRelaySourceScan*,*BiometricPrompt*,*AppLockControllerIllegalTransition*,*GsonR8KeepRulesContract*,*DeepLinkApplier*,*MainScreenCallSite*`. The new class `UnsupportedDatabaseActivityExportAuthSeamTest` is matched by `*UnsupportedDatabase*`; the modified scan class too.

Residual risks (unrun):
- The seam tests assume `ShadowActivity.nextStartedActivityForResult` records a `rememberLauncherForActivityResult` launch of `CreateDocument` under `ActivityScenario` (the positive control is the first test to fail if not, and every "no picker" assertion would then be vacuous; check that control first).
- `ActivityScenario.moveToState(CREATED)` / `RESUMED` on the unsupported screen is the first use of lifecycle moves in this suite; compose is only touched while RESUMED. `failed_everyBucket_` launches and closes four scenarios in one test.
- The `onStop` tests rely on a `moveToState(CREATED)` stopping (not destroying) the activity, which is the documented behaviour.
- The seams are static mutable state; the classes reset them in `@After`, and nothing else in the module sets them. If Gradle runs test classes in one JVM concurrently the seam tests would interfere; Robolectric tests here run serially per JVM fork.
- The structural pins stay: they are cheap and cover the unreachable null-authenticator branch.

### S21 strict review (reviewer-strict) + fix pass (2026-10-28; NOT validated)

Verdict: ACCEPT-WITH-CONDITIONS.
- C1: the batch passes, and the positive control `s21c_seam_requiredAndSucceeded_opensThePickerExactlyOnce_control` passes (it proves a picker launch is visible to the `ShadowActivity` observation; every "no picker" assertion depends on it).
- C2: F1 and F2 applied (done in this pass, below).
- C3: the candidate gates pass.

Findings and dispositions:
- F1 (P2) LEGAL_PATHS READ + RECOVERY paragraph contradicted itself ("ZERO" vs "ONE" production callers of `requestSettingsReread`) and missed the S21-a re-read for the controller's own `RESOLVE_TIMEOUT` fallback. DONE. Truth checked in source: ONE production caller (`AppLockPrivacySettingsViewModel`, the 1a retry). The paragraph now states the fallback re-read (no loaded view plus `resolvedByTimeout`), the `RESOLVE_TIMEOUT_MILLIS` bound, that a timed-out re-read changes nothing, and adds the new STORE READ reader (`UnsupportedDatabaseExportAuth`, `getLoadState`, 2 s bound, read-only).
- F2 (P3) CODEBASE_SEGMENTS: blank line missing before the S20-b bold heading. DONE.
- F3 seam-assignment scan: the setter pattern now accepts the `$module` mangled form (`[\w$]*`); a planted Java `setExportAuthRequiredForTest$app_release(` case (and an `$app_debug` one) is in the self-check; `::exportAuthRequiredForTest` and `::authenticatorFactoryForTest` property references are forbidden in production and planted. DONE.
- F4 docs. DONE: hilt-bindings-map "Obtained once" now "Resolved at every export tap"; LEGAL_PATHS export-gate paragraph adds "or no answer (provider threw or entry point unresolved)" and that onStop cancels an open prompt and denies; the plan S21-c heading is "RESOLVED: owner-approvals 73a, option (A)"; CODEBASE_INVENTORY app-lock drift sync lists `UnsupportedDatabaseExportAuth.kt` (S21-c bullet).
- F5 stale KDocs ("cannot be driven without a production seam") in `UnsupportedDatabaseActivityExportAuthTest` and `UnsupportedDatabaseActivitySourceScanTest` updated to point at `UnsupportedDatabaseActivityExportAuthSeamTest`. DONE.
- F6 bounded prompt-timeout liveness (CHECKING finishing after onStop can launch the picker in the background; no privacy impact). FOLLOW-UP for S22-b.
- F7 reflection pin that the `@Inject` constructors of `AppLockSettingsRepositoryImpl` and `AndroidDeviceAuthCapabilityProvider` take exactly one parameter, `android.content.Context` (keeps the unsupported-database entry-point graph Room-free). DONE in `UnsupportedDatabaseExportAuthTest` (`s21c_entryPointGraph_*`, with a planted self-check over wrong-arity, wrong-type, no-parameter and not-injected classes).
- F8 AE-57 passes by construction. ACCEPTED: no persistence path exists.

Compile fix: `UnsupportedDatabaseActivityExportAuthSeamTest` and `UnsupportedDatabaseActivityExportAuthTest` imported `androidx.compose.ui.test.assertExists` and `assertDoesNotExist`; both are MEMBERS of `SemanticsNodeInteraction`, not top-level extensions. The four import lines are removed (the calls stay and resolve as members). The other Compose test imports in both files (`assertIsEnabled`, `assertIsNotEnabled`, `onNodeWithText`, `performClick`, `junit4.createEmptyComposeRule`) are top-level and match how `AppLockSurfaceTest` / `AppLockGateTest` import them; `createEmptyComposeRule` is used only by the S21 files (a real `ui-test-junit4` top-level function).

Batch results so far:
- targeted vr-20261028-082614-8431cf10 FAIL (compile, fixed here);
- runtime-ui vr-20261028-083340-5dd9b18d FAIL (same compile);
- known_good_state vr-20261028-083003-9b78fac9 PASS;
- cancellation vr-20261028-083306-4ad2e0df PASS;
- ui_dao vr-20261028-083324-62328ed9 PASS;
- lint vr-20261028-083426-b872fc6d PASS.
The tree changed after these runs (tests and docs only), so the targeted and runtime-ui results must be re-run; the PASS results above were taken before this pass, and this pass edited tests and docs only (no production code).

Merge-readiness notes (reviewer):
- merge from origin/feature/ai-evolution 73c593dd in an integration worktree, merge base 25096c65;
- expect clean merges; re-read CODEBASE_SEGMENTS and CODEBASE_INVENTORY afterwards;
- the later mec rebase must drop its pre-1a `handleIntent` (it will fail `MainScreenCallSiteTest` and `DeepLinkApplierCallerPinTest`), and loan S13-a host branches stay inside `DeepLinkApplier.kt`.

KSP fix: the F7 self-check plants use a test-local @PlantedInject marker (Dagger rejected @Inject on private test classes; vr-20261028-084359-0f4d8f61)

Debug iteration 1 (debugger): DeepLinkApplierCallerPinTest self-check regex over-broad on a spaced qualified call (fixed with a lookbehind); seam throw test routed boom through the global ExceptionCollector (now inside runTest). Positive control s21c_seam_requiredAndSucceeded_opensThePickerExactlyOnce_control PASSED in vr-20261028-084602-dc39f4c6 (285/287).

### S21 ACCEPTED (2026-10-28): S21-a + S21-b + S21-c candidate
- Gates:
  - privacy-security-guardian on S21-a, S21-b and S21-c (all ACCEPT-WITH-CONDITIONS; conditions met);
  - architecture-guardian on S21-b (ACCEPT-WITH-CONDITIONS; conditions met);
  - reviewer-strict on the whole of S21 (ACCEPT-WITH-CONDITIONS).
- reviewer-strict conditions:
  - C1 met: targeted PASS, including the positive control s21c_seam_requiredAndSucceeded_opensThePickerExactlyOnce_control;
  - C2 met: F1 and F2 applied;
  - C3 met: the candidate gates below.
- Final candidate validation, all PASS:
  - targeted `*UnsupportedDatabase*,*AppLockBiometricSourceScan*,*AppLockRelaySourceScan*,*BiometricPrompt*,*AppLockControllerIllegalTransition*,*GsonR8KeepRulesContract*,*DeepLinkApplier*,*MainScreenCallSite*`: vr-20261028-085219-facdb3d2
  - runtime-ui: vr-20261028-085521-781d85d2
  - static-guards: vr-20261028-085755-e1e24a43
  - app-check: vr-20261028-091358-7d60c257
  - known_good_state vr-20261028-083003-9b78fac9, cancellation vr-20261028-083306-4ad2e0df, ui_dao vr-20261028-083324-62328ed9 and lint vr-20261028-083426-b872fc6d (only test and docs edits after these)
- Debug iterations used: 1 of 2 (compile imports, KSP @Inject plants, a self-check regex, ExceptionCollector routing; all test-only).
- #9 and #10 are closed. Follow-ups:
  - F6: bounded prompt timeout (S22-b);
  - AuthPromptCopy.EXPORT (S22/S23);
  - the Inc-2 PIN-mode export rule;
  - the G1b device check for #10.
- Next: merge app-lock (S20 + S21) into feature/ai-evolution (owner-approvals 73b) in Cost-agregator-int. The trial merge against 73c593dd is clean. Then S22.

### S22-a.0 implementation (2026-10-28, implementer; NOT validated)

Nothing was built or run. No guard, baseline, allowlist, diagnostic-code, schema, Operations, manifest or string change. No STOP condition was hit: no existing transition outcome changed, no new diagnostic code, no existing test expectation changed (only the planned pin additions), `AppLockSettingsOperations.kt` is untouched.

Files changed (P = app/src/main/java/com/yourname/expensetracker, T = app/src/test/java/com/yourname/expensetracker):
- M `P/domain/privacy/applock/AppLockState.kt`: `enum class OpenOriginEndKind {APPLIED, NOT_COMMITTED, CANCELLED, LOCKED_OUT, NOT_AVAILABLE, ERROR, HOST_LOST, BACKGROUNDED}` and `data class OpenOriginEnd(val attemptId: Long, val prompt: OpenOriginPrompt, val kind: OpenOriginEndKind)`. No top-level fun.
- M `P/domain/privacy/applock/AppLockController.kt`: `val openOriginEnd: StateFlow<OpenOriginEnd?>` (private `MutableStateFlow(null)` behind `asStateFlow()`); private `openOriginEndKindOf(Closing)` (exhaustive `when`, no else), `publishOpenOriginClose(attempt, kind)` and `publishOpenOriginEnd(...)`; publication calls in `closeWith`, `onOpenOriginSettingApplied` and `onInertDisableApplied`. Every publication is outside `stateLock` and `publishLock`, after the transition published its state.
- N `T/domain/privacy/applock/AppLockControllerOpenOriginEndTest.kt`.
- M `T/domain/privacy/applock/AppLockControllerIllegalTransitionTest.kt` (pins only, below).

Kind mapping per closing path (open-origin attempts only; an UNLOCK attempt never publishes):
- Cancelled (user dismissal) -> CANCELLED.
- LockedOut (either permanence) -> LOCKED_OUT.
- Failed(any bucket) -> ERROR. NotAvailable on DISABLE / CHANGE, and on ENABLE when the re-check says AVAILABLE, is Closing.Error(OTHER) -> ERROR.
- ENABLE NotAvailable refused by the re-check (HW_UNAVAILABLE, UNKNOWN, CHECK_FAILED, NO_CREDENTIAL, UNSUPPORTED_API) = Closing.EnableRefused -> NOT_AVAILABLE.
- T35 host loss, the watchdog (P3 / F5) and a cancellation that races a LAUNCHED answer all close as Closing.Abandoned -> HOST_LOST.
- Closing.NotLaunched (ALREADY_IN_FLIGHT / HOST_UNAVAILABLE / PROMPT_UNAVAILABLE launch, a throwing authenticate, an exhausted AWAITING_PRIOR_TERMINAL budget, a foreign cancellation before the launch) -> ERROR.
- Closing.BecameInert -> NOT_AVAILABLE in the exhaustive `when`; it is UNLOCK-only today, so it is never published.
- Closing.Success (UNLOCK) -> none. Closing.OpenOriginSuccess -> none at the close.
- A close that finds its state out of step (the F4 / ISSUE-1 guard, `staleClose`) of an open-origin attempt -> ERROR, so a consumer is never left waiting.
- onOpenOriginSettingApplied (T17 / T18 / timeout change / review-save token) -> APPLIED when committed, NOT_COMMITTED otherwise, only for the exact one-shot token, published after the transition (and so after the state move). A call with no token, a wrong id or a repeat publishes nothing.
- onInertDisableApplied (T19) -> APPLIED / NOT_COMMITTED for the exact token, published after the move to DISABLED, with prompt DISABLE and attemptId = the NEGATED token id (T19 has no attempt; negation keeps it from ever equalling an attempt id, so a StateFlow cannot drop it as equal to an earlier DISABLE end).
- BACKGROUNDED has NO producer: no controller path closes an attempt because the app went to the background (T32 only records `promptBackgroundAt`). The kind is reserved by the plan's enum; a test pins that backgrounds, foregrounds and queries publish nothing.

Ambiguous mappings (conservative choices, for the guardians):
1. Closing.Abandoned covers host loss AND the 5 min watchdog (and a cancellation racing a launch); all map to HOST_LOST. The S22-a.1 UI maps HOST_LOST to "nothing", so a watchdog timeout shows no notice. Splitting them would need a new private Closing subtype.
2. Closing.NotLaunched maps to ERROR, not HOST_LOST, even for HOST_UNAVAILABLE: the person asked and the prompt never showed, so ENABLE shows enable_failed rather than nothing.
3. Closing.Error(SYSTEM_CANCELED) (an OEM / system dismissal) is ERROR, not CANCELLED. S23's ux 4.3 move of ERROR_CANCELED to SYSTEM_CANCELED does not change this.
4. A committed apply publishes APPLIED even when the state did not move (T17 / T18 only move from exactly the origin with no open attempt; the commit itself happened). A CHANGE token with neither a timeout nor a record (test-only use of `startOpenOriginAttempt(CHANGE)`) also publishes by `committed`.

Tests (`*AppLockController*` covers all of them):
- N `AppLockControllerOpenOriginEndTest` (22 cases): shape and read-only; UNLOCK never publishes (9 endings); the non-success matrix per prompt (cancel, both lockouts, every error bucket; a second attempt with the same kind is a new value); NotAvailable by capability (ENABLE vs DISABLE / CHANGE); launch failures, retry budget, foreign cancellation, cancellation racing LAUNCHED; T35 host loss; watchdog; T34 (the end after the T34 lock at 60_000 ms and after UNLOCKED at 59_999 ms, per closer); out-of-step close; T12 stale ids (while open, after close, older callback while a newer attempt is open); T32 background events; T33 success publishes nothing at the close and the apply completion alone publishes nothing; T17 / T18 APPLIED after the state move and NOT_COMMITTED, consumed token, wrong ids; CHANGE timeout; no-token moves; end-to-end ENABLE / DISABLE through `SettingsOperationsRig` (committed and failed writes); T19 direct (exact id, replaced token, NOT_COMMITTED stays INERT) and end-to-end. A collector records the state and whether `stateLock` or `publishLock` was held at each emission (never).
- M `AppLockControllerIllegalTransitionTest`, pin additions only: `getOpenOriginEnd` added to the public-method-set pin; a signature block (the only member matching "penOriginEnd", no args, returns `StateFlow`); "penOriginEnd" added to the controller-entry forbidden list with no allowed production reader.
  - Deviation from "NO allowed file yet": the scan also reads `AppLockState.kt`, which declares `OpenOriginEnd` / `OpenOriginEndKind` and so contains the substring. It gets an exact-path allowance for this token only (`APPLOCK_PACKAGE_PATH/AppLockState.kt -> setOf("penOriginEnd")`). Every other file, including the VM, still gets none. S22-a.1 must add the settings ViewModel path (O-1).

Validation to request: targeted `*AppLockController*` (includes IllegalTransition and the new OpenOriginEnd test). Batch a per the plan adds `*AppLockPrivacy*`, `*AppLockEnableDialog*`, `*PrivacySettings*`, `*AppLockShell*`, domain-n-z, runtime-ui, known_good_state, cancellation, ui_dao, lint.

Residual risks (unrun):
- The test file was written without a compile: the least-certain parts are the nested private `Ends` collector (unconfined, records `Thread.holdsLock` of the two monitors by reflection on the private fields `stateLock` / `publishLock`) and the `private inline fun collecting`.
- The out-of-step ERROR path is exercised only through the same reflection forcing of `current` that the F5 tests use.
- Replay: the StateFlow replays the latest end to a new collector, so S22-a.1 must treat the first value it sees as a baseline, not an event, or a re-entry to the settings screen would show an old attempt's notice (documented on the property).
- Two ends published from different threads are not ordered against each other; attempts are serial and a new prompt cannot start and end before the previous end is published, so this is theoretical. T19 ends can interleave with an attempt end only if both run at once (the one-apply flag in the Operations prevents it).

### S22-a.0 privacy review (2026-10-28)

Verdict: ACCEPT-WITH-CONDITIONS (privacy PASS), privacy-security-guardian. Nothing was built or run for the follow-up below.

Findings and dispositions:
1. LOCK_ON / LOCK_OFF must follow the end's prompt (ENABLE / DISABLE only, T19's negative id included; CHANGE never). Disposition: plan binding B-1 for S22-a.1.
2. Replay of the latest end to a new collector. Disposition: plan binding B-2 (init baseline) for S22-a.1.
3. Some success paths publish no end, so nothing may wait for one. Disposition: plan binding B-3 for S22-a.1. (B-4, the ERROR_CANCELED-from-background copy, is added as a UX note for S23.)
4. (P3) APPLIED is published even when the state was no longer at the origin. Disposition: a test plus a KDoc fix. Test: `AppLockControllerOpenOriginEndTest` "S22-a_0 privacy review F4 - an applied DISABLE whose app timed out first still publishes APPLIED while the state stays LOCKED" (DISABLE success, background past 60_000 ms, foreground to LOCKED(TIMEOUT), then `onOpenOriginSettingApplied(id, true)` publishes APPLIED(DISABLE) with the state still LOCKED(TIMEOUT); the token is consumed). KDoc: `OpenOriginEndKind.APPLIED` now reads "after the apply's state move, if the state was still at the origin" (KDoc only, no code change).
5. The claim-carrying cases run inline. Disposition: acceptable, no change.
6. Optional tightening, approved as a strengthening under owner-approvals item 62. Disposition: added in `AppLockControllerIllegalTransitionTest`, test "S22-a_0 privacy review F6 - the code of AppLockState_kt names neither the controller nor its end getter": the code lines of `AppLockState.kt` (comment lines excluded) contain neither `AppLockController` nor `getOpenOriginEnd`. The file's pins had no planted self-check pattern, so the test carries a small inline one (the end type is declared in code, the raw file does name the controller in KDoc so the comment filter is not vacuous, a planted offending code line is flagged and a planted comment line is not).
   - The AppLockState.kt exact-path allowance (`setOf("penOriginEnd")`) is acknowledged as a necessary plan deviation (orchestrator, item 62): the file declares `OpenOriginEnd` / `OpenOriginEndKind`, so the substring is unavoidable there.

Files changed (this follow-up):
- T `domain/privacy/applock/AppLockControllerOpenOriginEndTest.kt` (one test added, now 23 cases)
- T `domain/privacy/applock/AppLockControllerIllegalTransitionTest.kt` (one test added)
- P `domain/privacy/applock/AppLockState.kt` (KDoc only)
- plan: "### S22-a.1 bindings from the S22-a.0 privacy review" (B-1..B-4)

Validation to request: unchanged, targeted `*AppLockController*` (covers both test classes). Not compiled or run.

### S22-a.0 ACCEPTED (2026-10-28)
- Gate: privacy-security-guardian ACCEPT-WITH-CONDITIONS (privacy PASS). F4 and F6 applied. F1-F3 are now plan bindings B-1..B-3 for S22-a.1.
- Validation, all PASS:
  - targeted `*AppLockController*,*AppLockShell*,*AppLockDeepLinkRouter*`: vr-20261028-103702-9d360dc4
  - known_good_state: vr-20261028-104221-37e46084
  - cancellation: vr-20261028-104525-19a72076
  - domain-n-z: vr-20261028-104541-1c694e02
- architecture-guardian and reviewer-strict run at the atomic S22 candidate.
- Next: S22-a.1 (settings UI). It is BLOCKED on owner decision O-1, the VM controller-member pin {requestSettingsReread, state, openOriginEnd}.

## 2026-10-28: O-1 / O-3 approved (owner-approvals 81)
- O-1 APPROVED: the VM controller-member pin is exactly {requestSettingsReread, state, openOriginEnd}; the controller-entry VM allow-set gains "penOriginEnd".
- O-3 APPROVED: downloading the API 30/31/33/34 google_apis x86_64 images for G1b. AVDs are created after the download.
- O-2 (es/el sign-off) is still open and needed before a store release.
- S22-a.1: feature-implementer is running (VM, Section, N AppLockEnableDialog, strings ×3, pin test, docs).
- Next: test-author (VM/Section/Dialog/Strings tests, B-1/B-2), then Batch a validation, privacy-security-guardian and architecture-guardian.
- O-3 DONE (2026-10-28): the API 30/31/33/34 google_apis x86_64 images are installed. AVDs g1b-api30, g1b-api31, g1b-api33 and g1b-api34 exist (pixel_6), alongside release-api26 and wave5-api35. The full G1b matrix is now runnable after S22/S23.
- S22-a.1 IMPLEMENTED (uncommitted, not compiled):
  - Changed: VM, Section, N AppLockEnableDialog, strings ×3 (24 keys, es/el DRAFT, O-2), pin test (O-1 plus dormancy {requestEnable, requestDisable}, plus 2 strengthenings), LEGAL_PATHS/SEGMENTS/INVENTORY.
  - Deviations 1-7 are under privacy and architecture review.
  - Docs follow-up for S23: hilt-bindings-map.md ~:409 lists a stale VM constructor.
  - In progress: test-author (VM/Section/Dialog/Strings tests), privacy-security-guardian, architecture-guardian.
  - Next: Batch a validation (targeted *AppLockController*,*AppLockPrivacy*,*AppLockEnableDialog*,*PrivacySettings*,*AppLockShell*; domain-n-z, runtime-ui; known_good_state, cancellation, ui_dao; lint). It runs after the mec ladder in the serial queue.
- S22-a.1 VALIDATED and COMMITTED (2026-10-28).
  - Batch a, all PASS:
    - targeted vr-20261028-155950-f72ad87f;
    - domain-n-z vr-20261028-160708-3dfc488e;
    - runtime-ui vr-20261028-160854-bd2f50f6;
    - known_good_state vr-20261028-161113-dcf0fea5;
    - cancellation vr-20261028-161402-abd12bd0;
    - ui_dao vr-20261028-161420-709255e9;
    - guard_docs_truth vr-20261028-161438-dd575a77;
    - lint vr-20261028-161454-0c95b037.
  - privacy-security-guardian and architecture-guardian: ACCEPT-WITH-CONDITIONS. All fixes are applied and the dispositions are in the plan (corrected face-unlock copy, purpose-aware refusal, the discreet-forced guard, LEGAL_PATHS WRITE 3 live).
  - Next: S22-b (b.1 timeout/secure/review card; b.2 F6 watchdog plus EXPORT copy), then the atomic S22 candidate (union targeted, runtime-ui, domain-n-z, static-guards, app-check) and reviewer-strict, then S23 docs, then G1b on the 6 AVDs.
  - The S22-b UI-files pin must add AutoLockTimeoutDialog.kt.

## S22-b (2026-10-29): atomic S22 candidate validated; reviewer-strict ACCEPT-WITH-CONDITIONS, copy fix applied (uncommitted)
- Files: AppLockPrivacySettingsViewModel (VM), AppLockPrivacySection (Section), AutoLockTimeoutDialog, strings_privacy_section ×3 (en/es/el, es/el DRAFT, O-2), UnsupportedDatabaseActivity, UnsupportedDatabaseExportAuth, DeviceAuthenticator (EXPORT purpose), BiometricPromptDeviceAuthenticator, strings_applock_prompt ×3, pins, docs (LEGAL_PATHS / SEGMENTS / INVENTORY / ux-flows).
- Guardian dispositions: plan "### S22-b review dispositions".
- Candidate validation, all PASS: targeted vr-20261028-192353-0325429c; runtime-ui vr-20261028-193126-f4069a01; domain-n-z vr-20261028-193513-fb47bc57; known_good_state, cancellation, ui_dao, guard_docs_truth; lint vr-20261028-194214-de06c5cb; static-guards vr-20261028-194718-ba5e63e5; app-check vr-20261028-200352-5aa22cc7.
- reviewer-strict ACCEPT-WITH-CONDITIONS; conditions applied 2026-10-29 (strings and docs only, no code change): (1) secure-screen title "Secure screen" and support "Hides the app's content in recent apps and blocks screenshots, screen recording and casting." (FLAG_SECURE does not remove the app from recents), en/es/el; (2) timeout note qualified "When App lock is on, ..." en/es/el; (3) ux-flows 4.x mocks and 9.1 updated; (4) AppLockPrivacySectionStringsTest English expectations updated for the 3 changed keys.
- The strings-only edit is after the validated tree: re-run the targeted AppLockPrivacySectionStringsTest (and lint if the runner requires it) before commit.
- S23 follow-ups added to the plan (end of "### S22-b review dispositions").
- Next: S23 docs, then G1b.
- S22 copy fix re-validated: targeted vr-20261028-211654-8077dc98 PASS, lint vr-20261028-212026-92511d91 PASS.
  - The secure-screen title and support copy, the timeout note, and enable_point_screen no longer claim the app is removed from recent apps.
  - S22-b is COMMITTED. The atomic S22 candidate (a.0 + a.1 + b) is accepted.
- Next session (fresh orchestrator, owner-approvals 87): S23 docs (single Sonnet writer plus a combined guardian), then G1b on the AVDs g1b-api30/31/33/34, release-api26 and wave5-api35.

## S23 DONE (docs) (2026-10-29, feature-implementer; NOT validated, no build/test/guard run)
- Files (docs, plus one KDoc-only Kotlin comment):
  - `app/src/main/java/.../domain/privacy/applock/AppLockSettingsOperations.kt`: requestReviewSave KDoc "D2 record" -> full review record (comment only, no code token changed).
  - `docs/features/app-lock-privacy-mode/ux-flows.md`: 4.3 ERROR_CANCELED -> SYSTEM_CANCELED; silent outcomes (DISABLE ERROR/CANCELLED, CHANGE ERROR/not launched, HOST_LOST/BACKGROUNDED, ALREADY_IN_FLIGHT/NOT_IN_ORIGIN/REVIEW_REQUIRED); B-4 decision "keep enable_failed for v1"; REJECTED_CAPABILITY copy note; raw discreet/secure choices stored while forced; TECH-13 INERT timeout row; device-mode disable dialog dropped (D-S22-2); Unavailable(OS)/NOT_SECURE "Open security settings" as built.
  - `docs/features/app-lock-privacy-mode/release-note-1b.md` (new; USE_BIOMETRIC, features, known limits, gating "S20 never ships without S22; G1b before release").
  - `docs/architecture/LEGAL_PATHS.md`: DEEP LINKS retitled 1a/1b plus the AE-118 settings-prompt hold and releaseOpportunity trigger; App Lock Settings Write Path retitled 1a/1b with an S23 status note; Last updated.
  - `docs/architecture/hilt-bindings-map.md`: VM constructor (DeviceAuthCapabilityProvider) and the S22 writes.
  - `docs/architecture/ENGINE_INTERACTION_MAP.md`: requestReviewSave full record; S22 settings write chains.
  - `docs/architecture/CODEBASE_INVENTORY.md`: USE_BIOMETRIC in Permissions. `CODEBASE_SEGMENTS.md`: stale S20-c "not user-enableable" wording. `NAVIGATION_ARCHITECTURE.md`, `UI_REFERENCE_INDEX.md`, `COMPREHENSIVE_UI_MAP.md`: router hold, S22 section controls.
- Owed: release-artifact-evidence must show USE_BIOMETRIC at G1b (a runner artifact, not edited); es/el sign-off O-2; the combined architecture-guardian review of S23; then G1b.
- S23 accepted (combined guardian ACCEPT-WITH-CONDITIONS; 4 doc fixes applied); guard_docs_truth vr-20261028-220634-f9d864ed "status":"PASS". COMMITTED. Next: G1b device checks on the 6 AVDs.

## G1b-0 implemented (2026-10-29; docs updated, NOT validated, no build/test/guard run by the docs author)
- NotificationPresentationApplier stamps a content-free mode token "D"/"F" in private extras (never on the public version). AndroidNotificationService.repostActiveAsDiscreet then runs verifySweepOnce(): settle SWEEP_VERIFY_SETTLE_MS = 750 ms (paid on every sweep, accepted latency; never changes RepostResult or the SWEPT outcome), re-read active once, re-post once (paced 300 ms) every sweep-kind non-summary notification whose token is not "D" via rebuildDiscreet, then one NOTIF_REPOST_UNCONFIRMED(COUNT) via PrivacyDiagnosticsEmitter. Never loops, never blocks cleanup, CancellationException propagates. Verify re-posts/failures are NOT counted in RepostResult (failures emit NOTIF_REPOST_FAILED).
- Residuals: (1) the verify pass does not re-check the effective discreet value: a legitimate FULL post within ~750 ms + N x 300 ms of turning discreet off could be re-posted discreet (fail-closed); (2) a notification dismissed between the read and the paced notify could be resurrected (same accepted race as the first sweep; setOnlyAlertOnce).
- Docs updated: LEGAL_PATHS.md (re-post sweep: G1b carry-forward converted to the implemented path plus residuals), ENGINE_INTERACTION_MAP.md (settings row and write-chain tree).
- Owed: tests for verifySweepOnce / token stamping; targeted validation; then G1b device checks on the 6 AVDs.
- G1b-0 VALIDATED and COMMITTED (2026-10-29). All PASS: targeted vr-20261028-223516-38c96fa4, data, domain-n-z, cancellation, known_good_state, guard_docs_truth, lint vr-20261028-230348-2fbb0e8d, static-guards vr-20261028-232054-1cf634f0. Combined guardian ACCEPT-WITH-CONDITIONS (tests and docs done; the 750 ms settle latency is documented). Next: the new androidTest classes (AppLockLifecycleDeviceTest, AppLockPromptHostDeviceTest), then the device runs api35, api26, api30, api31, api33, api34.
- G1b API 35 (wave5-api35, emulator-5554), B-tests all PASS:
  - AppLockLifecycleDeviceTest vr-20261029-012131-fe1dc5ed (9/9);
  - AppLockPromptHostDeviceTest vr-20261029-010827-77d48421 (5/5, including AE-118 double recreation then PIN unlock end-to-end);
  - NotificationPresentationDeviceTest vr-20261029-012803-b9df63d7 (including the G1b-0 verify rows).
  - Test-harness findings fixed: the IME eats the first Back on the API 35 credential prompt; Compose a11y lookups use the merged semantics tree; the first post-boot run hit an ANR during collection (environment).
  - FINDING (minor, owner FYI): CF-4. The spec says a user cancel is silent, but in some paths production shows TRY_AGAIN (AppLockShell.kt:92-93). It is documented as a simplification and not changed.
  - Next: API 35 C-steps (adb evidence), then api26, api30, api31, api33, api34.
- G1b API 35 C-steps (adb, 2026-10-29): steps 0-7 PASS, no privacy leak. Evidence: build/release-lead-notes/g1b/35/ in the main checkout (runs.md, residuals.md, screenshots, dumps).
  - AE-36 enable; AE-66 SECURE flag, black screencap and blank recents card; re-lock after the 1-min default timeout; deep links home/activity held then applied after unlock; AE-114 disable clears SECURE.
  - Residuals: the "Immediately" timeout and secure-screen alone with the lock off are not exercised.
- NOW (2026-10-29): the emulator was switched to release-api26 (emulator-5554). API 26 connected runs (lifecycle, prompt host, notification) are queued; queue ids are in scratchpad vq/al-g1b26.ids.
- Next: api30 → api31 → api33 → api34. For each: boot, queue the 3 classes with -AllowOverlap -OverlapReasonCode REQUIRED_FINAL_GATE, then a short adb C-step agent per the matrix. Then the app-lock merge candidate (reviewer-strict) and ask the owner to merge.
- G1b API 26 (release-api26, emulator-5554), B-tests all PASS (2026-10-29):
  - AppLockLifecycleDeviceTest vr-20261029-073047-8adec31d. 5 lock-on tests SKIPPED by design: assumeLockCapableSdk needs API 30+, and below that the lock is INERT (AE-54). The rest pass.
  - AppLockPromptHostDeviceTest vr-20261029-074408-489af884.
  - NotificationPresentationDeviceTest vr-20261029-074454-383885a5.
  - The queue driver had died before this. It was restarted 2026-10-29 ~10:20 local.
- NOW: API 26 C-steps (adb agent; evidence build/release-lead-notes/g1b/26/ in the main checkout): AE-34/AE-54 INERT, OD-20a secure screen, export not gated, deep link, discreet row.
- Next: api30, then api31, api33, api34; then the merge candidate.
- G1b API 26 C-steps (adb, 2026-10-29): all PASS, no privacy leak. Evidence: build/release-lead-notes/g1b/26/ in the main checkout.
  - AE-34/54: the row reads "App lock needs Android 11 or newer…", the switch is disabled, and it stays inert with a PIN set.
  - OD-20a: the secure-screen row is enabled; FLAG_SECURE bit 0x2000 is on and off as toggled; screencap is refused (0 bytes) while it is on.
  - Export (backup) is not gated: no auth prompt, straight to DocumentsUI.
  - The home deep link goes directly to Home. The discreet row is available (not toggled).
  - Residuals: export not completed; discreet behaviour, the recents card and the "Open security settings" tap not exercised; only the home deep link.
- NOW: api30 (g1b-api30, emulator-5554) booted; 3 connected classes queued (vq/al-g1b30.ids).
- G1b API 30 (g1b-api30, emulator-5554):
  - B-tests:
    - Lifecycle PASS vr-20261029-080950-761731c1.
    - Notification PASS vr-20261029-081354-333eea42; this covers AE-64, the lock-on SDK 30 BILL action rows.
    - PromptHost FAIL vr-20261029-081152-7e592993. AE41 "No compose hierarchies" after returnToApp.
  - AE41 diagnosis: the adb step 6 showed our MainActivity resumed with the lock surface ("Couldn't unlock. Try again.") after Home mid-prompt plus a relaunch. So this is a TIMING RACE in the test, not a product defect.
  - AE41 harness fix (test-only):
    - presentOrNull tolerates "no compose hierarchy" while waiting;
    - the intermediate wait also accepts a resumed system credential UI (excluding the launcher and our package);
    - the end state (Locked, lock surface only, Unlock enabled) is unchanged and still required.
    - Re-run on API 30, then on API 35 (regression).
  - C-steps all PASS, no leak (evidence g1b/30/):
    - AE-35 NO_CREDENTIAL row text and disabled switch;
    - the Android 11 bullet and note;
    - AE-66 SECURE, screencap refused, blank recents;
    - re-lock after 75 s; disable clears SECURE.
    - AE-64 UI statements only; the actions themselves are covered by the B-test.
    - Observation (not a defect): after `adb locksettings set-pin` the switch enabled only after a relaunch. The capability is re-read on resume, which the real "Open security settings" round-trip provides.
- API 30 PromptHost re-run after the AE41 harness fix: PASS vr-20261029-082530-c690889b. API 30 DONE.
- Owed before the merge candidate: re-run PromptHost on API 35 (regression for the AE41 harness change).
- NOW: api31.
- G1b API 31 C-steps (adb, 2026-10-29): no leak. Evidence: g1b/31/.
  - AE-36 PASS. Prompt title "Turn on app lock", subtitle "Confirm with your fingerprint or screen lock."; the forced rows show.
  - AE-48 PASS (UI). The PIN was removed in the background, and the "App lock is paused" banner shows with SECURE kept.
  - AE-49 PASS. With the PIN re-set, re-lock works after 75 s.
  - Common checks PASS: SECURE, screencap refused, blank recents, re-lock, unlock, and disable clears SECURE.
  - AE-117 RESIDUAL: it needs an OEM credential UI that stops the host, and the emulator prompt runs in-process. The D-class real-device item stays residual.
  - Diagnostic codes are not visible in logcat. They are durable bounded codes covered by unit and golden tests, not logcat (residual note).
- G1b API 31 B-tests all PASS: Lifecycle vr-20261029-091345-3503618f, PromptHost vr-20261029-091531-54b5b421 (with the AE41 harness fix), Notification vr-20261029-091702-973f9f63. API 31 DONE.
- NOW: api33.
- G1b API 33 B-tests all PASS: Lifecycle vr-20261029-092220-43ef44d4, PromptHost vr-20261029-092421-362a889c, Notification vr-20261029-092552-7b0b3a0a. The C-steps agent is running (AE-39, AE-85..87).
- G1b API 33 C-steps: no leak; evidence g1b/33/.
  - Enable, common checks and disable PASS.
  - AE-39 PARTIAL: lock surface first at a cold start, with no notification prompt over it. The positive first-prompt-after-unlock case is not reachable via the UI once the prompt-once flag is set (residual).
  - AE-85/86/87 RESIDUAL: they need a CORRUPTED settings sentinel, which the UI cannot create. The instrumented AE-51 corrupted-settings test plus unit tests cover the mapping.
  - API 33 DONE.
- NOW: api34.
- G1b API 34 B-tests all PASS: Lifecycle vr-20261029-093611-83f03813, PromptHost vr-20261029-093757-be93360f, Notification vr-20261029-093928-73663fab. The C-steps agent is running (AE-42/116/117/118, R3).
- G1b API 34 C-steps: no leak; evidence g1b/34/.
  - Enable, AE-116 (timeout change prompt; re-lock at 1 min), common checks and disable PASS.
  - AE-118 PASS with a note: the prompt survives rotation and the change commits; on host loss (a new task start) the change is NOT applied and the switch is usable. OBSERVATION: the AOSP BiometricPrompt window stays over the new activity until Back twice (no leak, the change is not applied). Flag to reviewer-strict at the merge candidate.
  - AE-42/117 OEM part RESIDUAL (AOSP credential UI on the emulator); R3 partial-share RESIDUAL (not exercisable via adb).
- G1b MATRIX COMPLETE (35, 26, 30, 31, 33, 34). Remaining: the API 35 PromptHost regression for the AE41 harness change, then the app-lock merge candidate (reviewer-strict), then ASK the owner to merge into feature/ai-evolution.
- API 35 PromptHost regression after the AE41 harness fix: PASS vr-20261029-100010-15ff58ea. G1b COMPLETE.
- Next: the app-lock merge candidate (reviewer-strict, Opus xhigh) on 4add2466..HEAD, then ASK the owner to merge into feature/ai-evolution.
- Merge-candidate review (reviewer-strict, 2026-10-29): ACCEPT-WITH-CONDITIONS, no blocker. Fixed: the ENGINE_INTERACTION_MAP lock-on/off sweep sentence; domain-spec AE-102 plus the §10 NOTIF_REPOST_UNCONFIRMED row; the duplicate weSetPin. Pre-release conditions (OWNER): AE-118 discarded-success UX (finding 3), CF-4 sign-off, an OEM device pass for AE-42/117, and an R3 share/SAF/camera pass with the 'Immediately' timeout.
- MERGE CANDIDATE 5048db7a (integration/ai-evolution-1b, local, NOT pushed) VALIDATED: guard_docs_truth vr-20261029-101048-a1fa2963, static-guards vr-20261029-101106-50c570f9, app-check vr-20261029-102739-9b90ef18, all PASS. AWAITING OWNER approval to push to feature/ai-evolution.
- 2026-10-29: OWNER approved (owner-approvals 89). Merge 5048db7a pushed to feature/ai-evolution (fast-forward from 4add2466). Next for app-lock: S24-S27 and G2; the pre-release owner items (AE-118, CF-4, OEM pass, R3 pass).
- G1c (2026-10-29), uncommitted, in progress:
  - JVM ladder all PASS: targeted vr-20261029-162550-bcfc27eb, runtime-ui vr-20261029-163022-22700b42, cancellation vr-20261029-163310-1535c46c, known_good_state vr-20261029-163328-324f7902, static-guards vr-20261029-163632-8561fac7.
  - API 34: AppLockLifecycleDeviceTest PASS vr-20261029-171302-d1d668d2; AppLockPromptHostDeviceTest PASS 6/6 vr-20261029-181528-90b01fc1 (after the harness fix: the API 34 prompt window is titled 'BiometricPrompt'). Manual AE-118 PASS (evidence g1c/34).
  - R3 (API 34): SAF, FLAG_SECURE, screencap and CF-4 PASS; share and camera follow-up running.
  - Guardian ACCEPT-WITH-CONDITIONS (LOW-2 fixed; LOW-1 declined).
  - The runner rejects Class#method filters (E_INVALID_TEST_FILTER); use whole classes.
  - Next: R3 follow-up, API 35 device classes, commit G1c.
  - R3 (API 34) COMPLETE: share (the chooser shows only the file name, re-lock on return), SAF, camera (re-lock on return; the photo survives unlock into receipt review), FLAG_SECURE, screencap refused, CF-4 silent. No leak. Evidence g1c/34 runs.md Parts A-E. Residual: the credential prompt sometimes needs the PIN re-entered (emulator input timing).
  - NOW: API 35 device classes for G1c.
  - API 35: AppLockPromptHostDeviceTest PASS vr-20261029-191103-c329656a; AppLockLifecycleDeviceTest PASS vr-20261029-191234-a9276ccc.
- G1c COMMITTED (owner-approvals 90 items AE-118, CF-4 and R3 closed; CF-4b deferred; AE-42/117 OEM screens remain the release-candidate real-phone residual). reviewer-strict at the next app-lock merge candidate. Next app-lock: S24-S27, G2.

## 2026-10-30: increment 2 (S24-S27, G2) re-anchored; S24 design review next
- The plan's "Increment 2 re-anchor amendment" (BINDING) records drift fixes D-I2-1..7, the order S24 -> S25 -> {S26 || S27} -> G2, per-slice validation, the PIN storage decisions, the open items for the S24 privacy design review (D-I2-8), and OD-19 as a pre-S27 gate (D-I2-9). Orchestrator decisions are under owner-approvals 92.
- Next: the S24 privacy-guardian DESIGN review (resolve D-I2-8), then the S24 Sonnet writer plus a separate test-author.
- S24 implemented (writers A+B) and tested (three test-authors; one attempt timed out and was split). Targeted vr-20261030-033610-06f65b18 PASS. The remaining ladder was withdrawn for the guardian fixes.
- S24 combined guardian (privacy-security): ACCEPT-WITH-CONDITIONS. Dispositions (orchestrator, owner-approvals 92):
  - G24-1 (MEDIUM): the cancellation/exception catch in setup deleted the key even if DataStore had committed (verifier with no key = permanent lockout). FIX: re-read under NonCancellable and delete the key only if no verifier is present.
  - G24-2 (MEDIUM): setup deleted an existing key before checking for an existing verifier. FIX: setup returns FAILED when a verifier exists, before createForSetup().
  - G24-3 (LOW): a matches() failure after the write-ahead leaves the counter incremented. FIX: best-effort restore of the previous attempt state.
  - G24-4 (LOW): the RECORD_UNREADABLE KDoc is inaccurate. FIX: the guardian's wording.
  - G24-5 (LOW): the observeState `.catch` ends the flow and a transient IOException reads as RECORD_UNREADABLE. S25 NOTE: retry via readState() and re-collect; the UI gets a retry action.
  - G24-6 (LOW): reviewSave writes record.lockMode. S25 NOTE: pass the stored mode through (INV-45).
  - G24-7 (LOW): import order in AppLockModule. FIX.
  - deleteAll on a corrupt store: CONFIRMED as spec intent (§6.4/6.5, INV-41/42; no device-credential fallback, OD-15). S25 recovery copy explains clearing data and restoring a .costbackup.
  - "TEE only" can't be forced: G2 checks KeyInfo security level.
  - Docs: LEGAL_PATHS (Segment 28 PIN-store ownership and verify path), ENGINE_INTERACTION_MAP (PIN-store row), CODEBASE_SEGMENTS Segment 28 files, CODEBASE_INVENTORY and hilt-bindings-map (bindings, @AppPinDataStore, 3 codes). FIX in the same pass.
- S24 validation, all PASS:
  - targeted (pre-cancel-fix fingerprint) vr-20261030-040550-9136a0b6
  - data vr-20261030-040851-d4e58edd
  - domain-n-z vr-20261030-041308-0fa03eb3
  - known_good_state vr-20261030-041530-1e95f38a
  - guard_docs_truth vr-20261030-041833-282377c1
- Cancellation guard G-CANCEL-01 (vr-20261030-041512-bc808b9f, static-guards vr-20261030-041851-3dfb7f9a): setup's CE catch did cleanup before `throw e`, which the guard's sibling rule does not recognise. Debugger root cause; fixed by merging the two catches into one `catch (e: Exception)` that runs the cleanup and then `if (e is CancellationException) throw e`. No behaviour change; no allowlist or baseline edit.
- Post-fix runs, all PASS:
  - AppPinStore/PinVerifierKdf vr-20261030-045128-d84008f4
  - cancellation vr-20261030-045432-240cd2db
  - static-guards vr-20261030-045450-02e5467e
  - lint vr-20261030-051039-332d0155
- Orchestrator process note: one test edit landed while a data-shard run was active. That run was discarded and re-run (vr-20261030-040851-d4e58edd).
- S24 COMMITTED (see git log). Dormant: nothing calls setup. Next: S25 (controller PIN substates; resolves TODO(S25) at AppLockController.kt:636; S25 notes G24-5/G24-6; freezes the "use fingerprint" API per D-I2-7).
- S25 implemented in three small writer steps (two full-slice writers died on API timeouts): state types/effective mode/G24-6; the unlock-by-PIN path; enable/change/disable plus the inert fingerprint API. Part A tests (FakeAppPinStore, unlock path) are being written.
- S25 combined guardian: ACCEPT-WITH-CONDITIONS. Dispositions (orchestrator, owner-approvals 92); all FIX unless noted:
  - G25-1 HIGH: the T31/step machinery is missing. Add requestPinSetup (the T31 CONFIRM_CHANGE device prompt) and enter/exit step APIs; enable/change/disable require the matching open PinStep (INV-50, AMD-12, AE-119).
  - G25-2 MEDIUM: a resolve timeout leaves pinState null with no observer (AMD-4 downgrade with FAIL_CLOSED settings). On timeout set pinState=UNREADABLE (retry offered); start the observation outside the timeout.
  - G25-3 MEDIUM: an in-flight UNLOCK device-auth success while the effective mode is APP_PIN is stale: back to LOCKED, no unlock.
  - G25-4 MEDIUM: T28 apply uses the mode-aware deviceAuthInertReasonLocked.
  - G25-5 MEDIUM: unlockByPin also runs applyKeptRereadAfterClose (T29).
  - G25-6 MEDIUM: re-check the UNLOCKED/step precondition inside withExclusiveWrite before the first write (TOCTOU). changePin is atomic in the store (documented).
  - G25-7 MEDIUM: AMD-4/T30 when capability != AVAILABLE: removing the PIN turns the lock off (appLockEnabled=false, DISABLED). Implemented, not deferred.
  - G25-8 LOW: PinRejection.Wrong toString must not print the tier.
  - G25-9 LOW: skip the cancel-revert when readState shows PRESENT.
  - G25-10 LOW: map non-cancellation store exceptions to Unavailable/Failed.
  - G25-11 LOW: reviewSave falls back to LockMode.DEFAULT, not the record's value.
  - Docs: LEGAL_PATHS Segment 28 controller paths, ENGINE_INTERACTION_MAP AppPinStore callers, CODEBASE_SEGMENTS Segment 28 files.
- S25 targeted vr-20261030-072137-03d0882e FAIL (30 of 1619). Debugger: no regression for DEVICE_AUTH users without a PIN. Causes:
  - test harness: the long-lived PIN observer counted as an active job;
  - FakeAppPinStore emit shadowing;
  - a stale Publication reflection pin;
  - rig counter offsets;
  - exact-set pins needing extension (9 ports, the PIN public API, a method-scoped CharArray allowance);
  - the obsolete reviewSave expectation (G25-11).
- REAL issue G25-7b: writeLockOff added a second production `.setAppLockEnabled(` call site, bypassing AppLockSettingsOperations and its F7 settle re-read. Decision (owner-approvals 92): the pin is NOT loosened. The controller returns a lock-off-required result; AppLockSettingsOperations.removePin performs the lock-off through its existing write path. Production fix and test fixes are running in parallel.
- S25 final fixes: removePin writes lockMode=DEVICE_AUTH before the lock-off (INV-49; the G25-7b test caught it); setupInOrder's CE handling merged into one recognised catch (G-CANCEL-01). Residual LOW F-S25-DIAG-FIELD: a failed lockMode write during removal is recorded under the APP_LOCK_ENABLED persist code. Process note: one MEC targeted run was made STALE by a concurrent edit and is re-run.
- S25 final ladder, all PASS:
  - targeted vr-20261030-082814-8dac2c86
  - domain-n-z vr-20261030-083446-3d897c4e
  - runtime-ui vr-20261030-083632-75b9a2a6
  - cancellation vr-20261030-083951-697228b4
  - known_good_state vr-20261030-084009-da9a92b7
  - guard_docs_truth vr-20261030-084313-b6fc0859
  - static-guards vr-20261030-084331-1838d99c
  - lint vr-20261030-085950-35a49a6d
- S25 COMMITTED. Dormant: no UI caller until S27. Next: S26 (fingerprint in PIN mode) || S27 (PIN UI, the switch; INV-38 CharArray pad, D-I2-9 OD-19 gate), then G2 and the increment-2 merge candidate (reviewer-strict).
- S26 written in two steps (BiometricPinKeyManager; authenticator + controller hook + deleteAll step 3 + strings).
- S26 combined guardian: REJECT as submitted (ACCEPT-WITH-CONDITIONS once the fixes land). Dispositions (orchestrator, owner-approvals 92):
  - G26-1/G26-2 (bypass): `onFingerprintUnlockSucceeded()` is public and takes no proof. REMOVED. New `suspend fun requestFingerprintUnlock()`:
    - user tap only;
    - requires canOffer, no in-flight attempt, and no PIN operation running;
    - opens an UNLOCK attempt with PIN_FINGERPRINT copy through the existing authenticate/watchdog machinery;
    - closeWith accepts a PIN_FINGERPRINT success only when the mode is APP_PIN, the verifier is PRESENT and biometricEnabled is true;
    - routeNotAvailable/LockedOut/Cancelled for PIN_FINGERPRINT return to LOCKED (never INERT, no device credential);
    - resetAttempts runs after the close, NonCancellable.
    - PLAN AMENDMENT: this changes D-I2-7's frozen fingerprint API; S27 had not consumed it.
  - G26-3 (rotation bypass): carry `copy` through relay adoption (OpenAttempt + AttemptAdopter.adopt(attemptId, copy, callback); pinFingerprint derived from copy; no default) so an adopted PIN attempt still requires verifyAuthenticatedCrypto.
  - G26-4: move BiometricPinKeyManager + BiometricKeyResult to domain/privacy/applock/pin; AndroidBiometricPinKeyManager stays in data; AppLockPromptHost injects the port; no default data instance in ui.
  - G26-5: UnrecoverableKeyException from getKey maps to KeyInvalidated.
  - G26-6: stale AppLockModule comment.
  - D-I2-5: extend AppLockBiometricSourceScanTest (PIN_MODE_FINGERPRINT_AUTHENTICATORS and setNegativeButtonText allowed only in the prompt file on the PIN_FINGERPRINT branch; constant == BIOMETRIC_STRONG; BIOMETRIC_WEAK ban kept; planted-violation self-check; no allowlist growth). Approved by the privacy guardian's ruling.
  - Fingerprint during an active PIN delay: ALLOWED (INV-39, ux 4.4). Advisory adopted: hidden when the delay reason is ATTEMPT_STATE_UNREADABLE.

## 2026-10-30: PAUSED by the owner — RESUME HERE
- Committed on this lane (pushed): S24 271513f7, S25 3890e731 (increment-2 amendment 24928ea1).
- S26 (fingerprint in PIN mode): steps 1-2 IMPLEMENTED but UNCOMMITTED. The guardian's verdict is REJECT as submitted (rulings above).
  - DO NOT COMMIT S26 as it stands: it contains the public `onFingerprintUnlockSucceeded()` (a proof-less unlock; dormant, no caller) and the rotation-adoption gap.
  - Fix pass A (controller) was attempted and died on an API timeout with NO changes.
- EXACT NEXT ACTION (small writers, edits under ~40 lines each; S26 is the only controller writer):
  1. Fix A, controller: remove onFingerprintUnlockSucceeded; add requestFingerprintUnlock (user tap, preconditions); closeWith accepts only a verified PIN_FINGERPRINT success in APP_PIN with the verifier PRESENT and biometricEnabled; NotAvailable/LockedOut/Cancelled go to LOCKED (never INERT); NonCancellable resetAttempts after the close; plan amendment "D-I2-7 amended".
  2. Fix B: carry `copy` through AppLockAuthenticatorRelay adoption (no default; AppLockPromptHost lambdas); move BiometricPinKeyManager + BiometricKeyResult to domain/privacy/applock/pin, with AppLockPromptHost injecting the port and no default data instance in ui; UnrecoverableKeyException maps to KeyInvalidated; fix the stale AppLockModule comment.
  3. Tests (separate test-author): extend the D-I2-5 scan test without weakening it; BiometricPromptCopyTest asserts the constant == BIOMETRIC_STRONG; key manager tests; AppLockControllerPinBiometric (no unlock without a verified attempt; rotation adopt; NotAvailable in PIN mode never INERT; fingerprint allowed during a delay but not for ATTEMPT_STATE_UNREADABLE); update callers that used onFingerprintUnlockSucceeded (PinUnlockTest:410, PinOperationsTest:780/784, IllegalTransitionTest:639 and its exact method set); BiometricPromptAdapterStateMachineTest adopt calls.
  4. Re-review with the guardian, then the S26 ladder (targeted *AppLock*,*BiometricPrompt*,*BiometricPinKeyManager*,*AppPinStore*; data, domain-n-z, runtime-ui; cancellation, pii_logging; lint; static-guards).
  5. Then S27 (PIN UI, the user-visible switch: CharArray pad (INV-38), the OD-19 dataExtractionRules pre-S27 gate D-I2-9, fingerprint enable AE-120, strings), then G2 device checks (incl. PBKDF2 timing and KeyInfo security level), then reviewer-strict at the increment-2 merge candidate and the self-approved merge into feature/ai-evolution.
- Residuals: F-S25-DIAG-FIELD (LOW); AE-42/117 OEM real-phone check (owner, release candidate); es/el sign-off for strings_applock_pin_prompt.xml.

## 2026-10-30: resumed; S26 ACCEPTED and COMMITTED
- Fix A (controller):
  - onFingerprintUnlockSucceeded removed;
  - `requestFingerprintUnlock()` opens a PIN_FINGERPRINT UNLOCK attempt;
  - closeWith re-checks APP_PIN, verifier PRESENT and biometricEnabled;
  - PIN_FINGERPRINT NotAvailable, LockedOut, Cancelled and BecameInert all go to Locked (never INERT, no device-credential fallback);
  - NonCancellable resetAttempts with ATOMIC start;
  - the pinDelayReason field hides the fingerprint for ATTEMPT_STATE_UNREADABLE.
  - Plan amendment "D-I2-7 amended" recorded.
- Fix B:
  - copy carried through relay adoption with no default (G26-3);
  - the BiometricPinKeyManager port moved to domain, the impl stays in data, injected into AppLockPromptHost (G26-4);
  - UnrecoverableKeyException maps to KeyInvalidated (G26-5);
  - the module comment is fixed (G26-6).
  - Accepted deviation: the authenticator's nullable keyManager means PIN_FINGERPRINT fails closed (UnsupportedDatabaseActivity export gate).
- Tests:
  - AppLockControllerPinBiometricTest (new);
  - updated callers;
  - relay rotation-copy tests;
  - BiometricPromptPinFingerprintAdoptTest (new);
  - AppLockBiometricSourceScanTest D-I2-5 extension (branch rule, planted self-check, constant == BIOMETRIC_STRONG, no allowlist growth);
  - AndroidBiometricPinKeyManagerTest, a source-scan pin; the behaviour is deferred to G2;
  - AppPinStoreImplTest deleteAll step 3;
  - pin tests updated to the approved design (six copies, ten ports, PIN_FINGERPRINT = BIOMETRIC_STRONG with "Use PIN").
- Guardian re-review, split after two API timeouts:
  - part A (controller) ACCEPT-WITH-FIXES: LOWs applied (KDoc, ATOMIC, docs); the hard-coded Dispatchers.Default in fingerprintReadyFor is noted and not changed;
  - part B (relay, key, scan) ACCEPT.
- Ladder, all PASS:
  - targeted vr-20261030-134418-72b499dd
  - data vr-20261030-135020-85a509ad
  - domain-n-z vr-20261030-135437-a266aebb
  - runtime-ui vr-20261030-135639-c5cbab9d
  - static-guards vr-20261030-135958-1b0f36a0
  - lint vr-20261030-141632-26dc5cda
- NEXT:
  1. S27: PIN UI, the user-visible switch, the CharArray pad (INV-38), the OD-19 dataExtractionRules gate (D-I2-9), the fingerprint enable (AE-120), strings.
  2. G2 device checks: PBKDF2 timing, KeyInfo security level, the real Keystore behaviour of AndroidBiometricPinKeyManager.
  3. reviewer-strict at the increment-2 candidate, then the merge.

## 2026-10-30: pre-S27 gates decided (orchestrator under owner-approvals 92; recorded as item 96)
- D-I2-9 / OD-19: Option A, no manifest change. D2D moves the settings but not the verifier or Keystore keys, so the new device resolves to DEVICE_AUTH or INERT (AMD-4): fail closed, no stranding. S27 tests pin it. App-wide Option C is an OWNER release-track item.
- UQ-1: the in-app pad (binding condition D / INV-38: CharArray, no String TextField).
- UQ-4: the single "I understand..." acknowledgement checkbox only; no export-first requirement.
- S27 is executed in small steps, following the plan's split seam:
  - a1: PinPad plus strings;
  - a2: PinLockSurface plus AppLockGate;
  - b1: the AppPinSettingsViewModel plus the PinSetupFlow state;
  - b2: AppPinSettingsScreen, the AppLockPrivacySection row and the fingerprint toggle (AE-120).
  - A separate test-author per step.

## 2026-10-30: S27 (PIN UI, the increment-2 user switch) ACCEPTED and COMMITTED
- Steps:
  - a1: PinPad, PinEntryBuffer (CharArray, not saveable, zeroed; cleared on ON_STOP) and strings.
  - a2: PinLockSurface (delay, unreadable, forgotten-PIN help, fingerprint key gated) plus the AppLockGate APP_PIN branch (no auto prompt) plus the ControllerGateSource pin/pinActions.
  - b1: AppPinSettingsViewModel (setup with ack, UQ-4; change; remove through AppLockSettingsOperations.removePin; INV-50 availability; onScreenLeft clears held entries) plus PinSetupFlow.
  - b2: AppPinSettingsScreen, the App PIN row in AppLockPrivacySection (hidden when Off, disabled when paused), the AppPinSettings route and save token, and the MainActivity branch.
- Combined guardian (split A/B): ACCEPT-WITH-FIXES. All applied:
  - B1 MEDIUM: activity-scoped VM, so onScreenLeft is called on dispose.
  - B2 and B3.
  - A1: ON_STOP clear. A3: the fingerprint key has its own enabled flag. A4: broad catch in submit. A5: the unused tries-left plural removed.
- Fix-pass lessons:
  - Restoring the same-package `AppLockContent` import was REQUIRED; it was removed by mistake. The db_access parser resolves the typealias only via an import (S20). The known_good_state count went 21 to 23 without it.
  - Robolectric pad tests need `qualifiers = "w411dp-h891dp"`.
  - The INV-42 scan regex was made stricter.
  - The S22-b dormancy pin was updated to EXACTLY three callers, adding AppPinSettingsViewModel through removePin (owner-approvals 81b plus 92/96).
- DEFERRED as S26b, a new follow-up slice:
  - Fingerprint ENABLE in PIN mode: a controller path plus a settings toggle plus the AE-120 prompt. Today no path sets biometricEnabled, so fingerprint unlock stays dormant and hidden (D-I2-7 allows S27 without it).
  - The tries-left copy was dropped, because PinRejection carries no count.
- Validation, all PASS:
  - targeted vr-20261030-202901-5e4b337e
  - runtime-ui vr-20261030-203202-6cee4a32
  - domain-n-z vr-20261030-203534-044f08ae
  - architecture-contracts vr-20261030-200536-a3e39ffa
  - static-guards vr-20261030-200624-1282004e (21x DB_SIGNATURE_UNRESOLVED again)
  - lint vr-20261030-192923-32ba08ab (before the import-only restore)
- NEXT:
  1. S28 docs (LEGAL_PATHS APP LOCK PIN ordering, CODEBASE_SEGMENTS seg 28, UI maps, CODEBASE_INVENTORY, the release note "a forgotten PIN means clear data or restore a backup").
  2. Decide S26b now or after the merge (recommendation: after; the increment can merge with fingerprint dormant).
  3. G2 device checks on emulator-5554: PBKDF2 timing, KeyInfo security level, reboot delay AE-73, Keystore loss AE-80, PIN step plus background AE-119, the PIN UI smoke.
  4. reviewer-strict at the increment-2 candidate.
  5. Merge into feature/ai-evolution.

## 2026-10-30: S28 docs and G2 (automatable part) DONE
- S28 docs:
  - LEGAL_PATHS App PIN Store, with the S27 UI callers;
  - CODEBASE_SEGMENTS seg 28;
  - COMPREHENSIVE_UI_MAP;
  - NAVIGATION_ARCHITECTURE;
  - CODEBASE_INVENTORY;
  - docs/features/app-lock-privacy-mode/release-note-2.md.
  - guard_docs_truth PASS vr-20261030-205342-4b47b174.
- G2 device: AppLockPinG2DeviceTest 5/5 PASS on wave5-api35 (vr-20261030-205358-ecf494f0).
  - PBKDF2 at 150k: 349 ms on the emulator.
  - The HMAC key KeyInfo: SIGN purpose, no user auth; securityLevel=0 (SOFTWARE on the emulator).
  - The real store setup/verify/wrong/deleteAll round trip works.
  - The boot count is available.
  - BIOMETRIC_STRONG is not enrolled (status 11), so the key manager fails closed.
- G2 items left for the OWNER's real phone (release candidate):
  - condition C timing on a low-end API-30 device;
  - the TEE/StrongBox security level;
  - AE-73 reboot delay;
  - AE-76 enrolment invalidation (S26b scope once fingerprint enabling exists);
  - AE-42/117 OEM screens.
  - AE-80 (Keystore loss) and AE-119 (background) are pinned by unit and Robolectric tests.
- NEXT:
  1. Increment-2 integration merge into feature/ai-evolution, with the full suites.
  2. reviewer-strict on S24..S28, split in two (domain/data, UI).
  3. Merge. S26b (fingerprint enable) is a follow-up.

## 2026-10-31: increment-2 candidate review and fix pass (IN PROGRESS, UNCOMMITTED)
- Integration merge 98549381 (unpushed; it will be redone after the fixes): static-guards, unit-tests and app-check PASS (vr-20261030-210102-dca79951, vr-20261030-211906-b4e3e801, vr-20261030-213726-f4c368a6).
- reviewer-strict part A (S24-S26): REJECT.
  - HIGH-1: in effective APP_PIN, the device credential could disable the lock, lengthen the timeout or review-save.
  - LOW-2, LOW-3, LOW-4.
- reviewer-strict part B (S27-S28): ACCEPT-WITH-FIXES.
  - B1: setup scroll. B2: stale step on setup. B3: LEGAL_PATHS order.
  - B4-B9 LOWs.
- Fix pass (orchestrator decision under item 92: fail closed):
  - In APP_PIN, startOpenOriginAttemptWith refuses, and requestDisable, requestTimeoutChange and requestReviewSave return PIN_MODE with no prompt.
  - removePin(current, turnLockOff) is the T30 disable variant, behind a new "Turn off app lock" flow in App PIN settings.
  - The privacy section routes Off to App PIN; the timeout row is disabled with a note.
  - LOW-2/3/4 and B1/B2/B3/B5/B6/B7/B9 applied.
  - Timeout change and review-save with PIN entry are DEFERRED to S27b (plan).
- reviewer-strict delta on the fix pass: ACCEPT-WITH-FIXES. HIGH-1 is CLOSED. Two MINORs applied:
  - the PIN-mode review dead end: PIN_MODE_REVIEW notice, a disablePin guard while unreadable, lock-on IMMEDIATE review-save allowed without capability;
  - TURN_OFF_INCOMPLETE.
- Fix-pass targeted run vr-20261030-224428-17d45321: 21 failures, under diagnosis by the debugger.
- NEXT: apply the diagnosis, run the full ladder, commit; redo the integration merge; run the full suites; fast-forward feature/ai-evolution.
- Fix pass diagnosed by the debugger and fixed: one production bug (a second capability re-check inside writeReviewSaveDirect) plus test-harness issues. Kotlin default arguments were replaced with explicit overloads, so the reflection API pin stays strict.
- Fix-pass ladder, all PASS (in order): targeted, runtime-ui, domain-n-z, architecture-contracts, static-guards, lint = vr-20261030-230636-075b96b9 vr-20261030-231253-722919bd vr-20261030-231610-ef45cd3d vr-20261030-231811-206e644b vr-20261030-231900-9278933b vr-20261030-233503-6bfea5f9 
- COMMITTED. NEXT: merge onto the integration branch (on top of 98549381), run the full suites, fast-forward feature/ai-evolution.

## 2026-10-31: INCREMENT 2 MERGED into feature/ai-evolution (d9c55d4d)
- Merge-commit gate PASS:
  - static-guards vr-20261030-234122-a26f8071
  - unit-tests vr-20261030-235926-ea4926c3
  - app-check vr-20261031-001700-9b29fe44
- Recorded as owner-approvals 98.
- The lane remains open for the follow-ups:
  - S26b: fingerprint enable plus the AE-120 prompt plus the AE-76 device check;
  - S27b: PIN-entry timeout and review-save, hardware keyboard, neutral delay copy.
- OWNER items: es/el sign-off, the G2 real-phone checks, a TalkBack check, OD-19 Option C.

## 2026-10-31: S27b ACCEPTED and COMMITTED
- **Domain:**
  - PinStep TIMEOUT_CONFIRM / REVIEW_CONFIRM;
  - controller beginPinConfirm, verifyPinForStep (shared counter, AMD-2), a one-shot step authority (cleared on close or re-lock, and on a failed re-verify);
  - onPinConfirmed* always apply a committed durable write (D6), and only the authority closes the step;
  - operations requestTimeoutChangeWithPin / requestReviewSaveWithPin (verify, then re-check the authority in the serializer, then write with the F7 settle);
  - PIN_MODE renamed PIN_REQUIRED for timeout/review; disable keeps PIN_MODE (Turn off in App PIN).
- **UI:**
  - PinConfirmDialog from the privacy settings (lengthening timeout / weakening review-save in PIN mode);
  - PinPad hardware keyboard (digits/numpad, Backspace/Delete, Enter consumed, confirm only at MIN_LENGTH; Ctrl/Alt/Meta ignored);
  - neutral delay copy (PinPresentation.delayNeutral).
- Combined guardian A/B: ACCEPT-WITH-FIXES. All applied (A1 MEDIUM durable apply on re-lock, A2, B1, B2).
- Debugger fix passes:
  - two production fixes: Enter consumed below MIN_LENGTH; the dialog message node uses mergeDescendants;
  - test pins updated to the approved API, exact and scoped per method;
  - stale expectations and idles fixed.
- Ladder, all PASS:
  - targeted vr-20261031-093839-84379af1
  - runtime-ui vr-20261031-094527-f44c3214
  - domain-n-z vr-20261031-094943-b3cb21f6
  - architecture-contracts vr-20261031-095200-c7c9060f
  - static-guards vr-20261031-095303-f0aff219
  - lint vr-20261031-101709-9406ffee
  - The first lint attempt was a transient E_EXTERNAL_GRADLE_ACTIVE.
- NEXT:
  1. S26b (fingerprint enable): C1 -> C2 -> C3 -> C4 per the plan, with the orchestrator decisions STOP-1 (no guard change; neutral "not available") and STOP-5 (disable is direct).
  2. Then reviewer-strict on S27b+S26b, then the merge.
