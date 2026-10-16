# Critique: assistant-follow-ups

Author: feature-critic (independent, read-only, 25 tool calls). Under review: `brief.md`. Date 2026-10-16. Persisted by the orchestrator. [V] checked in source; [UNVERIFIED] not checked.

## 1) Fact-check of brief §0
| Claim | Status | Evidence |
|---|---|---|
| F-A live assistant converts min/max in memory with `convertAsOf`, drops no-rate rows | HOLDS | `ExecuteFinancialQueryUseCase.kt:411-420` (min/max null), `:435-440` `convertAsOf(atMillis = expense.date)`, `:442-445` drops failures + `isPartial`; private `@Deprecated assistantFilteredExpenses` `:466-488` ("no callers" file-wide UNVERIFIED; private → low-risk delete) |
| F-B count branch can't ignore min/max | UNVERIFIED | — |
| F-C per-currency totals; merchant + amount in warning | HOLDS | `:525-534`, `:504-518`, `:444`. **Addition:** empty match renders total as `"0.00 N/A"` (`:505`) — fake zero, fake currency; brief example 2 hits it |
| F-D cloud route sends raw conversation history | HOLDS, broader | `CloudQueryInterpretationService.kt:98` uses `toCloudPromptInput()` (`:246-258`) which never touches `conversationHistory`; `OnDeviceQueryInterpretationService.kt:77-79, 94-95` up to 8 turns `role: text`; assistant turn text = `toDisplayText()` (`AssistantViewModel.kt:466-472`) = Summary `primaryText` + `supportingText` → **exact computed totals, averages, largest amounts**. Amount removal [V] **NO**: `CloudPiiSanitizer.kt:42-44, 110` exempts decimal amounts; `sanitizeFreeText` (`FinancialQueryInterpretationInputBuilder.kt:162-177`) strips only email, IBAN, card, phone, 10+ digit numbers. Merchant/category names in history aliased **only when `shouldRedact`** (`:99-115`), aliases are **unsalted SHA-256 prefixes** (`:124, 131`; `domain/common/Hashing.kt:5-8`) → common merchants ("Lidl") dictionary-reversible; conflicts with the installation-secret HMAC `DefaultCloudPayloadRedactor.kt:18-22` says merchant pseudonyms require |
| F-E VM reads `redactBeforeCloud` directly | HOLDS on rule, OVERSTATED on impact | `AssistantViewModel.kt:242` reads `AiSettings.redactBeforeCloud` (forbidden `LEGAL_PATHS.md:408`), but only nulls `payloadJson`, which `buildPrompt` never sends (uses `role` + `text`); OR of two flags → only stricter. Low effect; legal-path breach. Builder `:29-32` also reads `privacySettingsRepository…redactBeforeCloud` directly (forbidden? UNVERIFIED) |
| F-F "food last month" → this month; duration-based previous period | HOLDS, broader | `InterpretFinancialQueryUseCase.kt:301` shadows `:302` (unreachable); same for "last week" → this week (`:300`), "last year" → YTD (`:304`); bites only where period filled on-device (`:92` fill-if-missing, `:223` deterministic). `ExecuteFinancialQueryUseCase.kt:496-499` duration-based; previous range ends at `period.start` → boundary row may double-count (inclusive end UNVERIFIED) |
| F-G clarification tap sends fresh context-free query | HOLDS, already wrong answers | `AssistantViewModel.kt:384-387`; executor's "Which time period?" (`ExecuteFinancialQueryUseCase.kt:46-49`) offers "Last month" → resubmitted bare → bare-period branch (`InterpretFinancialQueryUseCase.kt:186`) = total spending with **no filters** → original category/merchant silently dropped, unfiltered total shown |
| F-I raw `e.message` to user + history | HOLDS, further | `InterpretFinancialQueryUseCase.kt:48-51` → shown (`AssistantViewModel.kt:309`), persisted as text (`:471`) + `payloadJson` (`:513-516`), **then sent back as history into the next prompt incl. cloud**; `Timber.w(e, …)` `:48` logs the throwable (release stripping UNVERIFIED) |
| F-J MIN has no executor | HOLDS, worse | `ExecuteFinancialQueryUseCase.kt:51-60`; schema still offers `MIN` (`OnDevice…:98`) → model can return MIN → English "not supported yet"; `values-el` absence UNVERIFIED |
| Session lifetime | RESOLVED, contradicts OQ1 | `AssistantSheet.kt:53` default `hiltViewModel()`; `MainActivity.kt:648` renders from custom `NavigationDestination`; no per-destination `ViewModelStoreOwner`/`NavHost` under `ui/` → VM **Activity-scoped**: survives sheet close and drilldown round-trip; dies on Activity finish/process death. `NavigationController.kt:30` persists navigation → after process death Assistant can return with empty VM. Clearing context on sheet close (OQ1) = new code, transcript still visible → inconsistent |

## 2) Per-scope verdict
| Item | Verdict | Reason |
|---|---|---|
| Memory-only session context, separate from `storeConversationHistory` | KEEP | no schema/egress; lifetime must follow the (Activity-scoped) transcript |
| Deterministic EN+GR free-text merge lexicon (ex. 1–7) | DEFER to inc 1b | cost/risk concentration: 8 open domain questions, over-inheritance, ambiguity, Greek normalisation, unproven lexicon; chips give most value without parsing |
| Clarification continuity (ex. 8) | KEEP — defect fix | fixes F-G silent filter loss |
| Follow-up chips (≤3) as structured edits | KEEP — core of inc 1 | no interpreter/network; same on every device; testable; "Compare" only where comparison renders (today TOTAL only) |
| Context indicator + "New question" | KEEP | main over-inheritance protection |
| F-F heuristic fixes | MOVE OUT → bugfix track | wrong answers today, independent of follow-ups |
| `values-el` strings + chip labels | KEEP chip labels; existing strings → l10n track | |
| Dead code + stale KDocs | KEEP (trivial) or fold into money-correctness (owns RF-3) | |
| Bounded follow-up reason code | KEEP | |
| Inc 2 send structured context to cloud | DROP additive part | low marginal value; relies on "aliasing" = unsalted SHA-256; the narrowing part ("stop sending raw history") is a defect fix that shouldn't wait (§3) |
| Inc 2 align amounts with `AmountSemantics` | MOVE OUT → money-correctness follow-up | answer = drilldown belongs there |
| Inc 3 (MIN executor, YoY, shared lexicon, retire legacy route) | backlog | split MIN: remove from prompt schema now or add executor |

## 3) Defects to route elsewhere now
| ID | Severity | Track | Minimal fix |
|---|---|---|---|
| F-D cloud history carries exact computed amounts + earlier raw query text; aliases unsalted SHA-256 | **P2 privacy / data minimisation** — not a fail-closed violation (gate `:92`, payload policy `:100`, audit `:108`; user opted into cloud AI + history), but interpretation doesn't need prior *answers*; redaction-on users expect pseudonymised merchants; dictionary-reversible hashes don't meet RP-15 | privacy-security-guardian → ai-fixes (T3) | drop/strip ASSISTANT-role turns from cloud prompt, keep only sanitized USER turns. Separately (cross-cutting PSG item): move alias generation in this builder and siblings `ReceiptItemCategorizationInputBuilder`, `DedupeJudgeInputBuilder`, `CategorizationAssistInputBuilder`, `RedactionSanitizer`, `CloudWarrantyExtractionService` to `InstallationSecretHasher` |
| F-I raw exception text shown, persisted, fed back to cloud | **P2** (bounded diagnostics; leaves device via history loop) | PSG → ai-fixes | map to bounded reason code + localized message; same for LLM-supplied `unsupportedReason` (flows into `Unsupported.reason`? UNVERIFIED) |
| F-G executor clarification "Last month" drops all filters | **P2 wrong answer**, silent | ai-fixes / assistant bugfix | pending-intent completion only (small part of ex. 8) |
| F-F period shadowing (month/week/year) + duration-based previous period | **P2 wrong answer** on deterministic/fill paths | ai-fixes / assistant bugfix, pinned tests + release note | "last X" before bare "X"; calendar `getMonthRange(now, -1)`-style previous periods |
| Empty result renders `"0.00 N/A"` (`:505`) | P3 money display | money-correctness follow-up | typed "no matching transactions" result |
| F-E direct `redactBeforeCloud` read | P3 legal-path hygiene | PSG | resolve via `EffectiveCloudAiPolicy` |
| MIN in prompt schema, no executor | P3 UX | ai-fixes | remove from schema or implement |

## 4) Hidden scope and risks
- **Lifetime:** VM Activity-scoped → context outlives sheet; rec: context lives exactly as long as the visible transcript, cleared by "New question"/clear-session (or clear transcript on close too — behaviour change). After Activity recreation/process death transcript + context gone; restorable from history only if on — don't restore context from `ai_chat_*`.
- **Invalidation:** stored intent holds `categoryIds` → category deleted/renamed/merged mid-session → chips silently return empty/zero; home-currency change affects min/max conversion; assistant disabled → drop context.
- **Money in "Compare":** mixed-currency sets ("100 EUR + 20 USD" vs "80 EUR") → no cross-currency delta, no conversion without provenance; show "unavailable", not zero delta (current TOTAL comparison handling UNVERIFIED).
- **Non-TOTAL metrics:** comparison renders only for TOTAL → chips gated by metric/grouping or inc 1 grows into rendering work.
- **Greek normalisation:** single normaliser (accents, final sigma, case); nl-quick-add Parser C1 (slice B, `workflows/active/nl-quick-add-plan.md:18`) natural owner; share normaliser + relative-date words, keep query cue lexicon separate; post-1.0 after nl-quick-add S0/B.
- **Drilldown parity:** chip-edited intents via `MapFinancialQueryToNavigationUseCase`; `convertAsOf` vs stored-snapshot mismatch (F-C) affects chip results until money-correctness aligns.
- **ai-fixes overlap:** §3 fixes touch `InterpretFinancialQueryUseCase`, `AssistantViewModel` (ai-fixes plan touching them UNVERIFIED) → sequence §3 before this feature.

## 5) Recommended shape
- **Track 0** (ai-fixes / PSG, before this feature): F-D history minimisation, F-I bounded reasons, F-F period fixes, clarification "Last month" filter loss, MIN schema, F-E. Mostly T3 (privacy). Small, independent.
- **Inc 1 (T2):** memory-only last intent + pending clarification intent; ≤3 deterministic chips as structured edits, metric-gated; pending-intent clarification completion; context indicator + "New question"; chip labels in `values-el`; dead-code/KDoc cleanup; one bounded follow-up reason code. No free-text merge, no cloud change, no schema.
- **Inc 1b (T2):** EN+GR free-text follow-up lexicon on the shared nl-quick-add normaliser, after nl-quick-add B; golden fixtures ex. 1–7 + negatives; optional local Nano with structured context.
- **Drop:** additive cloud structured context. Assistant `AmountSemantics` alignment → money-correctness after 1a.

## 6) Owner questions (recommended answers)
1. F-D/F-I/F-F/F-G defects to ai-fixes/PSG now rather than wait? **Rec: yes, Track 0 first.**
2. F-D fix: drop assistant turns from cloud prompt entirely, or send with amounts stripped? **Rec: drop** (interpretation never needs past answers).
3. Inc 1 chips-only, free-text follow-up parsing deferred to 1b? **Rec: yes.**
4. Context lifetime = visible transcript (Activity-scoped; cleared by "New question"/clear-session), not sheet dismissal? **Rec: yes.**
5. Drop sending structured context to cloud altogether? **Rec: yes**; revisit only with evidence chips + deterministic merge fail cloud users.
6. Greek normaliser shared with nl-quick-add C1, cue lexicon separate; 1b waits for nl-quick-add slice B? **Rec: yes.**

## 7) Verdict: **RESHAPE**
Blocking: brief parks real privacy + wrong-answer defects (F-D, F-I, F-F, F-G) behind a post-1.0 feature increment; free-text EN+GR lexicon (most expensive/risky) placed in inc 1 though chips deliver most value; context-lifetime recommendation rests on a wrong assumption (VM Activity-scoped); inc 2 "aliased" cloud context rests on unsalted SHA-256.
Flips to GO: §3 defects → Track 0; inc 1 = chips + clarification continuity; lexicon → 1b on shared normaliser; drop additive cloud context; context lifetime tied to transcript.
