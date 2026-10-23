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
