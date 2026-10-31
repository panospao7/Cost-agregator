# ai-evolution — feature handoff (umbrella ideation track)

Feature id: `ai-evolution` (umbrella; each chosen idea gets its own `<feature-id>` pipeline)
Branch at start: release-1.0-remediation (clean)
Status: ROADMAP — PLANS APPROVED (all parked post-1.0): ai-fixes, money-correctness 1a/1b, manual-entry-currency inc 1, nl-quick-add inc 1 (+ loan-installments outside this program). Not started: assistant-follow-ups, on-device-briefing, briefing-grounded-signals, nl-quick-add inc 1b/2, MEC inc 1b.

## Raw idea (owner, 2026-10-16, verbatim)
"as you will see we already have ai integration in the app but i believe we can make it better
smarter and we can include it in more functionalities. this is a broad spectrum subject so i will
need ideas etc"

## Constraints
- Release 1.0 candidate frozen (see `release-1.0-assurance-handoff.md`); any app/ change invalidates
  release evidence. This track is DOCS-ONLY until the owner decides timing (same as loan-installments).
- `loan-installments` is plan-approved and paused post-1.0; one feature pipeline at a time.

## Plan for this track
0. Scout: inventory of existing AI capabilities → `docs/features/ai-evolution/ai-inventory.md`
1. feature-strategist: idea portfolio (improve existing + extend to new areas) → `docs/features/ai-evolution/idea-portfolio.md`
2. feature-critic: adversarial ranking of the portfolio → `docs/features/ai-evolution/critique.md`
— Human gate: owner picks 1 idea (or a small bundle) → becomes its own `<feature-id>` and runs the normal pipeline from stage 1 (brief).

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 0 Inventory | docs/features/ai-evolution/ai-inventory.md | DONE (scout, Sonnet) — 12 capabilities, Gemini-only cloud + Nano on-device; dormant: review priority, semantic dedupe use case, LOCATION_SUMMARY; no feedback loop |
| 1 Portfolio | docs/features/ai-evolution/idea-portfolio.md | DONE (feature-strategist) — 26 ideas (A1-A8 improve, B1-B12 new areas, C1-C6 foundations); shortlist A1, C1, A4, B1, A2+B2, A3+C2, B4; 8 owner questions |
| 2 Critique | docs/features/ai-evolution/critique.md | DONE (feature-critic, attempt 2; attempt 1 hit turn limit, no output) — verdict RESHAPE; first pick B1 nl-quick-add (deterministic-first); 6 defects routed to bugfix track |

## Human decisions
- PENDING gate: (1) first feature-id (critic: nl-quick-add; strategist: briefing-for-everyone); (2) open a separate AI bugfix track for critique §4 items 1-6?; (3) answers to critique §6 Q1-Q6 (and optionally portfolio §7).
- 2026-10-16 owner answers (verbatim): "Typing can be in English and Greek, depending on the circumstance. Also the bug
  fixes should happen. I think they must be part of this AI plan or something. My phone definitely has Gemini Nano.
  That's why I also included it as a very simple model for simple stuff on the phone that doesn't need you to pay
  anything extra. The feel of the currency is not a bad idea. As far as the text type and saving, I'm not very sure
  what you mean by that. I want more clarifications. Also explain to me what you mean about the quick add or another idea."
  Recorded as:
  - Input language: English and Greek (mixed by circumstance). Greeklish not mentioned → treat as nice-to-have, confirm.
  - Bug fixes (critique §4 items 1-6): APPROVED in principle; owner wants them inside the AI plan → proposed as
    first item `ai-fixes` of the ai-evolution program (still post-1.0 freeze unless owner says otherwise — asked).
    Item 4 (warranty) target behaviour still needs owner choice (asked).
  - Device: owner phone has Gemini Nano; owner intent = Nano as the free, on-device model for simple tasks
    (no paid cloud needed). Deterministic fallback still required for non-AICore devices.
  - Foreign currency: interpreted as "prefill original currency, convert at save via existing converter" = acceptable
    (asked to confirm).
  - Raw-text persistence, first-feature pick, post-save insight (Q5), cash share (Q1), talk-vs-answer direction:
    still OPEN — clarification sent to owner.
- 2026-10-16 owner answers #2 (verbatim, voice-transcribed): "Quickart seems nice. I have to talk about it. It's nice
  actually. Do you have a recommendation about the Senestroute? I don't think it holds value. The order you propose
  seems good, yes, but still I don't want us to do the AI fixes. I want this to be just part of the plan for now.
  AI fixes will be part of it."
  Recorded as (transcription interpreted; confirm):
  - "Quickart" = nl-quick-add: owner likes it, still deciding (not yet a GO).
  - "Senestroute" = the typed-sentence question → owner sees no value in keeping it → Option A (discard typed
    sentence; never persisted) PROVISIONALLY ACCEPTED, pending confirmation.
  - Roadmap order APPROVED: 1 ai-fixes → 2 nl-quick-add → 3 assistant-follow-ups → 4 briefing-grounded-signals
    (+ A7 labels / A8 key copy alongside).
  - ai-fixes: NOT to be executed now; recorded as roadmap item 1 only. No source changes, no pipeline stages run
    for it until the owner says so. Timing therefore = after Release 1.0 (no reopening of the freeze).
- 2026-10-16 owner answers #3 (verbatim): "do the recommended thing , understanding greeklish is good yes"
  Recorded as DECIDED:
  - Typed sentence: Option A CONFIRMED — used once to prefill, never persisted anywhere.
  - Warranty (ai-fixes item 4): option (a) — AI-found warranties always shown as suggestions confirmed by a tap;
    no auto-persist at ≥0.75.
  - Post-save insight after quick-add: orchestrator's recommended default recorded — keep it, but only after ai-fixes
    item 5 gives the insight its own setting/purpose; Gemini Nano on-device by default, cloud only if the user has
    enabled cloud for that purpose. (Owner may revisit at the nl-quick-add brief.)
  - Greeklish input (e.g. "kafes 3.50") in scope for nl-quick-add.

## Open risks
- Cloud/AI ideas touch privacy fail-closed, consent, network → T3 territory for most picks.
- Portfolio claims a possible defect: briefing worker requires CLOUD_AI_DAILY_BRIEFING + notification permission, so default (cloud-off) users never get a Home briefing; may belong in a bugfix track (pending critic fact-check).

## Next action
Await owner GO for a specific roadmap item (none given). On GO: create docs/features/<feature-id>/ and run feature-strategist for a single-feature brief using owner answers + critique §5/§6. ai-fixes is roadmap item 1 inside this program (not a separate track); runs only on explicit owner GO, post-1.0 freeze.

## Defects found (candidate bugfix track, not yet approved)
See `docs/features/ai-evolution/critique.md` §4: (1) briefing generation blocked by notification permission (vs AGENTS.md:165-167); (2) hybrid briefing gated as cloud-only (+ PrivacyRuntimeWorkerPolicy:58); (3) dismissed suggestions regenerate; (4) warranty default-on + auto-persist ≥0.75; (5) transaction insight shares DASHBOARD_BRIEFING purpose; (6) docs/strings mislabels.

## Approved roadmap (planning only, post-1.0; each item becomes its own <feature-id> pipeline when owner says GO)
| # | Feature-id | Scope source | Status |
|---|---|---|---|
| 1 | ai-fixes | critique.md §4 items 1-6 | PLAN APPROVED 2026-10-16 (workflows/active/ai-fixes-plan.md) — parked until Release 1.0 merge; next S0 |
| 2 | nl-quick-add | portfolio B1 + critique §5 reshape; EN+GR input; Nano on-device + deterministic fallback; foreign currency prefilled + converted at save; typed sentence discarded (Option A, confirmed); Greeklish in scope; post-save insight on-device by default, after ai-fixes #5 | INC 1 PLAN APPROVED 2026-10-16 (workflows/active/nl-quick-add-plan.md) — parked until 1.0 + ai-fixes B2d; before loan S11 |
| 2b | manual-entry-currency | split from nl-quick-add (Gate 1, 2026-10-16): currency control on add-expense, no-rate → block save, currency-aware rounding, recurring/edit paths; T3 money; then nl-quick-add inc 1b maps currency markers. Input: docs/features/manual-entry-currency/no-rate-verification.md (known P3 no-rate sort/filter fallback + fix direction) | INC 1 PLAN APPROVED 2026-10-16 (workflows/active/manual-entry-currency-plan.md); slice D done (loan handoff note); rest parked post-1.0 |
| 2a | money-correctness-1.0.x | split from manual-entry-currency (Gate 1, 2026-10-16): unconverted marker for all sources, home-aware non-comparable sort/filter, one snapshot rounding rule, legacy/home-switch matrix; optional FinancialRescue fabrication + CURR-6; T3; lands first after 1.0 (outside AddExpense queue). Input: no-rate-verification.md, manual-entry-currency/critique.md §3.1, §3.4 | INC 1a+1b PLAN APPROVED 2026-10-16 (workflows/active/money-correctness-plan.md) — parked post-1.0; after ai-fixes, before MEC core |
| 2c | nl-quick-add inc 2 (Nano enhancer) | merchant/category proposals only, on-device, after ai-fixes B2 | ROADMAP |
| 3 | assistant-follow-ups | portfolio A4 + critique; + assistant raw-amount filters (money-correctness RF-3) | STARTED 2026-10-16 (owner "go") — stage 1 brief |
| 2d | cloud-alias-hashing | NEW privacy item (assistant-follow-ups critique F-D): merchant/category cloud aliases are unsalted SHA-256 prefixes (dictionary-reversible) in FinancialQueryInterpretationInputBuilder, ReceiptItemCategorizationInputBuilder, DedupeJudgeInputBuilder, CategorizationAssistInputBuilder, RedactionSanitizer, CloudWarrantyExtractionService → move to InstallationSecretHasher; P2; T3 | PLAN APPROVED 2026-10-16 (workflows/active/cloud-alias-hashing-plan.md) — parked post-1.0; S1..S4b any time after 1.0, S5 after ai-fixes C1 + assistant-follow-ups F3 |
| 3b | on-device-briefing | split from ai-fixes item 2 (Gate 1, 2026-10-16) | MERGED 2026-10-16 into item 4 briefing-grounded-signals inc 2 (Nano narration behind Phase B); worker cloud→on-device fallback → stub `briefing-worker-on-device-fallback` |
| 4 | briefing-grounded-signals | portfolio A2 (+B2, B7 narration); 3b merged as inc 2 | PLAN APPROVED 2026-10-16 (workflows/active/briefing-grounded-signals-plan.md) — merged into Money Radar; parked post-1.0; S-fix home-insight-gap before ai-fixes B3a; inc 1 after ai-fixes Part B + money-correctness 1a |
| side | honest-ai-labels + cloud-key-transparency | A7 labels, A8 copy | ROADMAP (could fold into ai-fixes item 6) |

## Follow-up list (from ai-fixes Gate 2, G2-15; out of scope there)
- `ReceiptSideEffectPlanner.kt:216-219` retries deterministic warranty Failure.
- Misleading `AI_WARRANTY_CREATED` actor label.
- Hard-coded "€" in fallback recommendation text (`DashboardFollowThroughEngine.kt:150`) — user-visible for non-EUR users.
- Warranty expiration worker gated by NOTIFICATION_CAPTURE (`PrivacyRuntimeWorkerPolicy:60-63`).
- 1 ms warranty expiry boundary gap.
- Non-AI raw `e.message` in ~12 ViewModels; backfill of older untranslated ai_*/warranty_* keys (el/es).

## Follow-ups from nl-quick-add Gate 2 (G2-17)
- Hard-coded "(€)" amount label / "Euro" content description on add-expense → manual-entry-currency.
- nl-quick-add domain-spec §15: five pre-existing defects (see spec).
- Validation runner has no shard-coverage check: tests in packages missing from config/validation/test-shards.json are silently skipped by unit-test-shard runs (check ai-fixes/loan-installments new packages too).
- MerchantNormalizer.normalize(autoCreate=false) writes alias rows + logs raw name (MerchantNormalizer.kt:96-104); categorize() logs raw merchant (:185) — pre-existing privacy/side-effect issue beyond quick-add.

## Approved global post-1.0 build order (manual-entry-currency Gate 2 G2-1, 2026-10-16)
Release 1.0 (incl. R16, R23 part 1) → ai-fixes → money-correctness-1.0.x (if specced; else after MEC a) → MEC core (a, setter-less)
→ loan S1..S10 (S3/S6a serialised vs TLC work) → nl-quick-add inc 1 → MEC (b) add-expense UI → MEC (c) receipt review
→ loan S11 on the general currency control (plan edits at loan S0: S11 write set, D-8, S6a AUTO_RECORD_DEFERRED_FX) → nl-quick-add inc 1b → MEC inc 1b.
Source: docs/features/manual-entry-currency/impact-map.md §9.
- Follow-up (MEC G2-18): ReceiptParser.kt:690 silently drops OCR totals > 50,000 (JPY/KRW/HUF/ISK receipts) → receipt owner.
- Follow-ups (money-correctness Gate 1): AI assistant amount filters compare RAW amounts (ExecuteFinancialQueryUseCase via ExpenseRepository.kt:356-364) → SRH-7 / assistant owner (relevant to roadmap item 3 assistant-follow-ups); app-wide rounding inconsistency (Money.kt HALF_UP vs formatter/allocator HALF_EVEN) → money-policy item; AnomalyAlert baseAmount/baseCurrency (AppDatabase.kt:6115-6116) → separate.
- Follow-ups (money-correctness Gate 2 G2-15): ManualRecurringExpenseViewModel.kt:108-116 creates recurring rules without currency ('EUR' default); CsvExpenseImporter.kt:238-239 "EUR" fallback; mixed-currency date-header signing (TransactionsScreen.kt:535); SortOrder labels hard-coded English; package com.yourname.expensetracker.currency in no test shard.
- Follow-up (ai-fixes PC-10): six AI input builders still OR redaction flags outside EffectiveCloudAiPolicyResolver (ReviewExplanationInputBuilder.kt:32, ReceiptItemCategorizationInputBuilder.kt:44, ReceiptAssistInputBuilder.kt:29, DedupeJudgeInputBuilder.kt:32, CategorizationAssistInputBuilder.kt:40,:83; GenerateTransactionInsightUseCase.kt:97 rides with ai-fixes B2d) → pair with cloud-alias-hashing.
- Follow-up (assistant-follow-ups G2-10): Summary.supportingText rendered twice (AssistantResultCard.kt:69 + AssistantSheet.kt:177-187) → small UI fix.
- 2026-10-16: assistant-follow-ups inc 1 PLAN APPROVED (Gate 3), parked. Slot: after ai-fixes (incl. Part C) and money-correctness 1a (F4 hard-needs 1a-3a); before nl-quick-add; inc 1b after nl-quick-add slice B. ai-fixes Part C plan APPROVED same day.
- 2026-10-16: owner "Go" on cloud-alias-hashing (item 2d) → STARTED, stage 1 brief. Handoff workflows/active/cloud-alias-hashing-handoff.md.
- Orchestrator recommendation recorded (owner asked): next big AI feature = briefing-grounded-signals (item 4); on-device-briefing = measure AICore first, likely fold into item 4; honest-ai-labels fold into ai-fixes; nl-quick-add inc 2 / 1b, MEC 1b wait for inc 1 usage.
- 2026-10-16: cloud-alias-hashing Gate 1 GO (owner "Go"); stage 3 domain spec. PSG backlog (Q-J): local-only unsalted hashes SourceLinkEventMetadataBuilder.kt:92, SafeEventMetadata.kt:58, DefaultCloudPayloadPolicy.kt:50.
- 2026-10-16: cloud-alias-hashing PLAN APPROVED (Gate 3, owner "Recommended and approved"), parked post-1.0.
- 2026-10-16: briefing-grounded-signals STARTED (stage 1 brief); handoff workflows/active/briefing-grounded-signals-handoff.md; brief to decide whether 3b on-device-briefing folds in.
- 2026-10-16: briefing-grounded-signals Gate 1 GO (owner "Go"): merged grounded summary into Money Radar; stages 3+4 in progress.
- New roadmap entries (from briefing-grounded-signals Gate 1): `home-insight-gap` (small standalone bug: Home Insight widget returns null for 0–20% pace band / no pace + no spend → card and hosted AI briefing vanish; ComputeDashboardWidgetsUseCase.kt:1352,1387-1407; EN/EL "on track" + neutral line + unit test; land before ai-fixes B3; post-1.0 unless owner says "send to release"); `briefing-worker-on-device-fallback` (parked stub; ai-fixes/brief.md:153); `why-this-number` (B2, future brief); `period-recap` (B7, future brief).
- Follow-up (briefing-grounded-signals G2-8): Money Radar whole score hidden when monthly income is foreign-currency without a rate (ComputeMoneyRadarUseCase getMonthlyIncome :479 via calculateDueBillsScore).
- 2026-10-16: briefing-grounded-signals PLAN APPROVED (Gate 3, owner "Recommended and approved"), parked post-1.0. home-insight-gap = its S-fix (ships alone, before ai-fixes B3a; loan-installments rebases if needed).

## ai-fixes follow-ups and deferred items (Z closeout, 2026-10-24; collected from workflows/active/ai-fixes-handoff.md A..B3b + Part C)
Status of the item: ai-fixes slices A, B1a-c, B2a-e, B3a-b, C1-C5, CX are DONE and validated per slice; bundle gate + fresh-session final acceptance pending (see ai-fixes-handoff.md Z). None of the items below blocks acceptance; each needs an owner or a later feature to pick it up.

### Owner decisions pending
- GenAI error collapse (INV-17 last bullet / INV-19): BUSY, QUOTA_EXCEEDED, MODEL_UNAVAILABLE and BACKGROUND_USE_BLOCKED all become `PROVIDER_ERROR` because `OnDeviceDashboardBriefingService` carries the code only as free text and INV-19 forbids parsing it; the four `TransactionInsightSkipReason` values are unused and the B2c "BACKGROUND_USE_BLOCKED backstop" is not delivered (the foreground short-circuit is the only background guard). Reason: a typed error carrier (widening `AiServiceError`) is a scope decision, together with insight timeout tuning (plan Deferred).
- Nano warm-up vs the 3 s insight timeout (G2-12): owner measures on device. Reason: non-blocking, needs hardware.
- Translation review (es/el are best-effort everywhere below; owner reviews before release), by slice: A (27 AiSettings keys); B1b (12 warranty keys; el dialog Cancel shows English, no el `action_cancel`); B1c (`ai_warranty_local_note`); B2a (`ai_capability_transaction_insight`); B2e (`ai_insight_label`, `ai_insight_desc`, `ai_insight_needs_ai`, `ai_insight_needs_on_device`); B3b (`ai_toggle_proactive`, `ai_proactive_desc`, `ai_proactive_notifications_blocked`; es/el name the English toggle labels and drift if those are translated); C2 (7 assistant unsupported-reason strings); C4 (5 clarification labels); C5 (`domain_ai_smallest_purchase`). Reason: PC-13/R28 require owner review of Greek.
- Missing `domain_ai_largest_purchase` es/el (pre-existing; MAX title shows English). Reason: found at C5, outside Part C write set.

### Privacy / log hygiene
- `AiSettingsRepositoryImpl.settings()` `Timber.e(error, ...)` logs the IOException throwable: use a bounded message. Reason: pre-existing, debug-only, outside INV-3 scope of B2b.
- `DailyBriefingWorker` log hygiene: ~:138 `Timber.w(e, ...)` on timeout and ~:155 `${result.error}` in a log message. Reason: pre-existing; B3a did not touch those lines.
- Delivered briefing notification shows `summary.take(180)` on the lock screen. Reason: presentation belongs to the app-lock-privacy-mode lane at merge.
- Other `Timber.w(e)` throwable logs: `OkHttpCloudProviderConnectionTester.kt:50`, `ReceiptSideEffectPlanner` ~:222, `WarrantyTrackerRepository` ~:197/:215. Reason: later INV-3 sweep.
- `AutoCreateWarrantyFromReceiptUseCase` `Failure(e.message)` -> bounded code. Reason: B1a follow-up.
- `dataExtractionRules`: exclude `ai_settings` from device-to-device transfer (allowBackup=false does not stop D2D on Android 12+). Reason: pre-existing, manifest/policy change outside ai-fixes.
- Unredacted prior queries keep internal newlines (user's own text); optional collapse. Reason: C1 info item.
- Six other AI input builders still OR redaction flags outside `EffectiveCloudAiPolicyResolver` (see PC-10 line above; pair with cloud-alias-hashing). Reason: deferred by PC-10/R29.

### Dead code and duplicated tables
- Dead redact branches: the `shouldRedact` branch in `TransactionInsightInputBuilder` and the `insight.redactForPrompt` branch in `DashboardBriefingPromptFormatter` are unreachable now that the insight is on-device and unredacted. Reason: cleanup out of B2d scope.
- Router/policy duplicate toggle tables: `DefaultAiCapabilityRouter.isCapabilityEnabled` and `AiPolicyImpl.shouldAllowOnDevice` encode the same per-capability switch; make the router delegate to `AiPolicy`. Reason: pre-existing drift risk, B2a/B2b had to edit both.
- `DebugScreen.supportsCloudFallback` re-encodes cloud eligibility and already disagrees with policy on LOCATION_SUMMARY: derive from AiPolicy/router. Reason: pre-existing, debug-only.
- `createWarrantyForReview`/`promoteReviewDraft` have no caller and would reactivate expired rows; `CloudWarrantyExtractionService`/`WarrantyExtractionModels`/provider remain (A6, plan Deferred). Reason: removal is a separate approved increment.
- ExecuteFinancialQueryUseCase `METRIC_NOT_SUPPORTED` is defensive-only (Interpret branch unreachable); `containsRichIntentCue` still lists dead "compare"/"vs" substrings; `PackageInstallProvenance.classify()` could rethrow CancellationException before its broad catch (synchronous, outside CANCEL-01). Reason: optional cleanups noted by reviewers.

### Data / lifecycle
- Recommendation retention: `RecommendationDao.deleteExpired` deletes only `status = 'EXPIRED'` and no production caller of the cleanup was found. Reason: pre-existing; needs a retention decision.
- `DashboardFollowThroughEngine` stores `sourceArtifactId` "0" (the `AiArtifactRecord.id` default). Reason: pre-existing, found at B2d; also hard-coded "€" in its fallback text (already in the G2-15 list).
- `WarrantyTrackerViewModel` `totalProtectedValue` is 0.0 on aggregate failure instead of an "unavailable" state. Reason: money rule "never render unavailable as zero", pre-existing, B1b follow-up.
- R7 Pattern E / candidate truth-sync: candidate signatures still list the dead `toWarrantyEntityOrNull` row and lack `confirmWarranty`; stale accounting records (NotificationRepository, ReviewQueueRepository, RecurringLifecycleCoordinator, RetentionModule) and GR-14 registry / GR-11 corpus pins need the next full regeneration. Reason: B1a took the R21 minimal path; the regenerated set is in the orchestrator scratchpad (b1a-regen-set) if still present.

### Guard and allowlist hygiene
- Expired 2026-09-30 broad-catch entries remain (not enforced; first-allowlist-only expiry check): LocationBackfillWorker, MerchantKeyBackfillWorker, ReceiptMatchingWorker, BillReminderWorker. Only the WarrantyExpirationWorker entry was removed (B3a). Reason: owner-visible; renew or fix in a guard-hygiene slice, never extend silently.
- `NotificationProcessingPipeline.kt` keeps its CancellationSafety KNOWN_VIOLATIONS entry (`catch (t: Throwable)` ~:706 inside a Room transaction); `OnDeviceQueryInterpretationService` KNOWN_VIOLATIONS (~:81, MIT-034) may now be removable (C2). Reason: baseline shrink proposals, not made in ai-fixes.
- `ai-fixes` domain-spec reconciliation (domain-modeler): INV-C9 connector wording (looser regex shipped) and `domain-spec-part-c.md` ~:50/~:200 (executor `else` -> exhaustive `when`). Reason: spec drift recorded at C3/C5.

### Product follow-ups
- Plan Deferred list: A6 (Nano warranty route; delete cloud warranty service/models/provider); G2-15 list (above); backfill of older untranslated `ai_*`/`warranty_*` keys; localizing `runtime.message`; typed GenAI error carrier + insight timeout tuning; "7 AI services" vs "8 providers" doc inconsistency.
- Part C Deferred: restored-transcript half of OD-C3; Greek period vocabulary (nl-quick-add normaliser + assistant-follow-ups 1b); session context + chips (assistant-follow-ups inc 1); MAX/MIN AmountSemantics alignment (money-correctness RF-C3: `convertAsOf(expense.date)` vs snapshot gap); backfill of EN-only `assistant_*`/`domain_ai_*` keys in `app/lint-baseline.xml`.
- FEATURES.md optional wording: ":20 AI Forecasting" and ":225 AI suggestions". Reason: slice A reviewer minor.
- UX: blocked-notifications caption refreshes only at existing refresh points (not on resume). Reason: B3b by design; revisit with a lifecycle hook.
- `ai_proactive_desc` INV-25 behaviour change (cloud briefing generated at midnight with proactive off) is owner-visible; see release-note.md.
- Merge notes for feature/app-lock-privacy-mode and feature/manual-entry-currency are in ai-fixes-handoff.md (Z section).
- (ai-fixes Z, domain-modeler) Assistant comparison cue gap: the shipped connector accepts "comparing/comparison", but the cue keyword check (InterpretFinancialQueryUseCase ~:380, substring "compare"/"vs") does not match "comparing", so "comparing X with last month" is probably not treated as a comparison. One-word cue addition + spec INV-C10 update; product/owner call (behaviour change, small).
