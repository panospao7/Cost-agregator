# assistant-follow-ups — feature handoff

Feature id: `assistant-follow-ups` (roadmap item 3 of the `ai-evolution` program)
Branch at start: release-1.0-remediation
Status: PLAN APPROVED — PARKED (post-1.0). DOCS-ONLY (Release 1.0 frozen; implementation post-1.0).

## Origin
Owner 2026-10-16 (verbatim): "go" — after the orchestrator named assistant follow-ups as "the natural next one".

## Scope sources / facts carried in
- docs/features/ai-evolution/idea-portfolio.md A4 `assistant-follow-ups`; §0 fact 2: follow-ups only work when
  `storeConversationHistory=true` (default false) — ui/screens/assistant/AssistantViewModel.kt:237-253; only
  OnDeviceQueryInterpretationService.kt:77 reads history.
- docs/features/ai-evolution/critique.md A4: KEEP; real usability gap, S–M, no persistence; sending prior structured intent to
  cloud = new content under existing QUERY_INTERPRETATION purpose → privacy review (borderline T3 on cloud route); ship on-device
  route + deterministic chips first.
- money-correctness (plan approved): assistant amount filters compare RAW amounts (ExecuteFinancialQueryUseCase via
  ExpenseRepository shared builder :356-364); assistant preview count ignores min/max; dead @Deprecated assistantFilteredExpenses
  (RF-3) → routed here / SRH-7. Assistant must rebase on money-correctness 1a (AmountSemantics param; assistant golden SQL test).
- ai-fixes plan: no overlap with ExecuteFinancialQueryUseCase/ExpenseRepository.
- Platform: ML Kit GenAI / Gemini Nano foreground-only (fine for assistant), Prompt API beta; owner's phone has Nano; Greek support unproven.

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/assistant-follow-ups/brief.md | v2 DONE (reshape used; further reshape → owner) — inc 1 chips + context (transcript lifetime) + indicator + invalidation + typed empty result + cleanup; inc 1b free-text EN+GR; reuses Part C C-3/C-4; rule P-1; 1 owner Q. v1 archived brief-v1.md. v1: DONE (feature-strategist) — inc 1 deterministic EN+GR follow-up merge + chips + clarification continuity, memory-only, T2; inc 2 cloud context + amount alignment T3; §0 found pre-existing defects F-D (cloud sends raw history incl. amounts when history opted in), F-E, F-F (wrong period answers), F-I (raw e.message persisted) |
| 2 Critique | docs/features/assistant-follow-ups/critique.md | DONE (feature-critic) — RESHAPE: Track 0 defects (F-D P2 privacy, F-I P2, F-F/F-G P2 wrong answers, MIN, F-E) before feature; inc 1 chips + clarification continuity; free-text lexicon → 1b on nl-quick-add normaliser; drop cloud context; VM is Activity-scoped |
| 3 Domain rules | docs/features/assistant-follow-ups/domain-spec.md | DONE (domain-modeler) — INV-F1..F20, AE-F1..F29, OD-F1..F4, RF-F1..F9; schema NONE (new AssistantMessageKind.FOLLOW_UP, empty payload, comparison in supportingText — text columns) |
| 4 UX flows | docs/features/assistant-follow-ups/ux-flows.md | DONE (ux-flow-designer) — chip row under latest answer (FlowRow), chip turn as muted follow-up line, 'Follow-ups for: Food · Oct' strip + New question, empty card, in-card comparison, invalidation note; ~30 keys EN/el/es; UX-Q1..Q7 |
| 5 Impact & tier | docs/features/assistant-follow-ups/impact-map.md | DONE (impact-mapper) — T3 overall; schema NONE; E5 guard design; F1..F5 slice cut; depends on Part C + money-correctness 1a-3a |
| Gate 2 sheet | docs/features/assistant-follow-ups/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 6 Slice plan | workflows/active/assistant-follow-ups-plan.md | APPROVED 2026-10-16 (Gate 3, owner "Recommended approved") — S0, F1..F5, Z; PD-1..PD-6 accepted; AE-F28 fix; AF-N1..N16 |

## Slice statuses
| Slice | Status |
|---|---|
| S0, F1, F2, F3, F4, F5, Z | planned (F4 also blocked on money-correctness 1a-3a) |

## Human decisions
- 2026-10-16 Gate 1 = RESHAPE. Owner (verbatim): "Recommended" → critique §6 Q1-Q6 recommended:
  1. Track 0 defects fixed FIRST, before this feature: F-D (drop assistant turns from cloud prompt), F-I (bounded reasons), F-F (period
     shadowing + calendar previous period), F-G (clarification keeps filters), MIN schema, F-E — added to ai-fixes as Part C (post-1.0).
     NOT sent to the release track (owner did not say "send to release"). Unsalted-SHA-256 alias issue → separate new privacy item.
  2. F-D fix = drop ASSISTANT-role turns from the cloud prompt entirely (keep sanitized USER turns).
  3. Inc 1 = chips only (≤3 structured edits, metric-gated) + clarification continuity + context indicator/"New question" + bounded code;
     free-text EN+GR follow-up lexicon → inc 1b.
  4. Context lifetime = visible transcript (Activity-scoped; cleared by "New question"/clear-session), not sheet dismissal.
  5. Never send structured context to cloud (inc 2 additive part dropped); AmountSemantics alignment → money-correctness follow-up.
  6. Greek normaliser shared with nl-quick-add C1, cue lexicon separate; 1b waits for nl-quick-add slice B.
  Single strategist reshape pass now runs.
- 2026-10-16 Brief v2 confirmation. Owner (verbatim): "Recommended" → v2 CONFIRMED (Gate 1 closed → GO; inc 1 + 1b, T2);
  Q1: typed "no matching transactions" empty result (replacing "0.00 N/A" on the assistant executor path) owned by THIS feature inc 1 (display-only).
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "Recommended approved" → G2-1..G2-18 recommended (docs/features/assistant-follow-ups/gate2-decisions.md):
  OD-F1 yes (+ ALL_EXCLUDED_MISSING_RATE split); OD-F2 FOLLOW_UP kind; OD-F3 previous full unit; OD-F4 one mixed-currency rule incl. typed compare;
  drop "Show transactions" chip (card button stays); restored follow-ups as muted line (amend INV-F16); uncategorized-only breakdown copy; note once
  after re-enable; ship Greek strings + release note; supportingText-twice → follow-up; no E5 allowlist; APPROVED test retargets
  (ExecuteFinancialQueryUseCaseTest :90-131 second retarget; Part C AE-C9/AE-C27/AE-C12); APPROVED new guards (a) no "N/A" in executor,
  (b) no SessionContext/FollowUpChip in data/builders/interpretation services; (c) via VM test; drilldown parity out of scope; wait for
  money-correctness 1a-3a observeReadHomeCurrency (no weaker fallback); FollowUpDiagnosticCode + snapshot use case; slices F1..F5; T3.

## Next action
PARKED. Next action when unblocked (ai-fixes incl. Part C landed + green CX): run S0 (scout on Sonnet for AF-D1..D16; domain-modeler applies spec amendment set; validation-runner baselines). F4 additionally waits for money-correctness 1a-3a.

## Pre-existing defects found (critique §3) — not acted on; owner decides routing
- F-D P2 privacy: with cloud AI + stored history ON, assistant cloud prompt includes up to 8 prior turns incl. assistant answers with exact
  computed amounts (CloudQueryInterpretationService.kt:98 → OnDeviceQueryInterpretationService.buildPrompt :77-95; CloudPiiSanitizer exempts
  amounts). Merchant/category aliases are UNSALTED SHA-256 prefixes (FinancialQueryInterpretationInputBuilder.kt:124,131; Hashing.kt:5-8) —
  dictionary-reversible; cross-cutting: also ReceiptItemCategorizationInputBuilder, DedupeJudgeInputBuilder, CategorizationAssistInputBuilder,
  RedactionSanitizer, CloudWarrantyExtractionService (should use InstallationSecretHasher per DefaultCloudPayloadRedactor.kt:18-22).
- F-I P2: raw e.message → shown, persisted, and fed back into next (cloud) prompt (InterpretFinancialQueryUseCase.kt:48-51; AssistantViewModel.kt:309,471,513-516).
- F-G P2 wrong answer: clarification "Last month" drops all filters (AssistantViewModel.kt:384-387 + InterpretFinancialQueryUseCase.kt:186).
- F-F P2 wrong answer: "last month/week/year" shadowed by "month/week/year" (InterpretFinancialQueryUseCase.kt:300-304); duration-based previous period (ExecuteFinancialQueryUseCase.kt:496-499).
- P3: empty result "0.00 N/A" (:505); F-E direct redactBeforeCloud read (AssistantViewModel.kt:242); MIN in prompt schema without executor.

## Domain-spec findings needing owner (Gate 2)
- OD-F1 "largest/smallest" with no rows → typed Empty (replacing Part C's NO_MATCHING_TRANSACTIONS Unsupported); retargets Part C AE-C9 + no-rows part of AE-C27 (rec yes).
- OD-F2 chip turns kept out of model prompts via new AssistantMessageKind.FOLLOW_UP (rec) vs not persisting chip turns vs payload marker.
- OD-F3 "Previous {period}" on to-date periods = previous FULL unit (rec, matches typed "last year") vs same-elapsed window.
- OD-F4 mixed-currency "Comparison unavailable" applies to typed "compare" queries too (one executor rule) (rec yes).
- Spec choices: context = intent the executor ran (not drilldownIntent); absolute range + granularity; brief ex.1 superseded (full Oct vs full Sep per PC-5);
  "Show transactions" opens existing drilldown; Remove precedence merchant > amount > category (amount drops min+max); "By category" dropped for AVERAGE/COUNT;
  Compare only single totals, hidden for custom ranges; invalidation incl. observation failure (fail closed).
- Risks / stop-and-ask: inc 1 retargets Part C AE-C12 + ExecuteFinancialQueryUseCaseTest:90-131 (typed comparison replaces "Previous period:" text),
  possibly AE-C9/AE-C27; narrows Part C builder history selection (USER + kind QUERY); needs money-correctness 1a typed home-currency observable.
- RF-F1: Part C RF-C1 routed "0.00 N/A" to money-correctness but owner assigned it here → reconciled in ai-fixes handoff (orchestrator).
- RF-F2/F3 drilldown parity (navigation mapper keeps category/merchant only when exactly one; nothing for "smallest"); RF-F4 "Show transactions" chip duplicates card's open-transactions button (UX).

## UX findings needing owner (Gate 2)
- UX-Q1 drop "Show transactions" chip from catalogue (card's "Open Transactions" button already exists) (rec yes; retargets AE-F1/F7/F14/F19).
- UX-Q2 amend INV-F16: restored FOLLOW_UP rows render as the follow-up line, not user bubbles (rec yes).
- UX-Q3 Breakdown(rows=[]) (uncategorized-only) copy "No categorized transactions in this period" (rec).
- UX-Q4 revisit indicator wording at inc 1b. UX-Q5 pre-existing: Summary.supportingText rendered twice (AssistantResultCard.kt:69 + AssistantSheet.kt:177-187) → separate small fix.
- UX-Q6 invalidation note shown once on re-enable after "assistant disabled". UX-Q7 existing assistant strings have no values-el → mixed-language sheet; ship with release note vs hold Greek (owner).
