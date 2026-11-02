# Decision sheet: stress-forecast-actions (UF-07), human gate after the impact map (2026-10-26)

**Sources:**
- `domain-spec.md` (O-1..O-6)
- `ux-flows.md` (UX-1..7)
- `impact-map.md` (§8 and the orchestrator verification)

**Tier: T2.** No schema change, guard edit or permission change. No existing test changes. This is display and navigation only.

Reply "accept recommendations", or override items by ID. The el/es copy needs your sign-off before merge.

## Decisions
| ID | Question | Recommendation | Why |
|---|---|---|---|
| O-1 | Where the suggestion text comes from | **Localize now.** The engine emits only a type plus parameters. The UI maps each type to EN, EL and ES strings. The engine stops formatting money. | The domain layer doesn't depend on Android resources, and routing no longer depends on the text. |
| O-2 | Suggestions with no destination: "delay non-essential purchases", "healthy outlook", "no immediate concerns" | **Information only.** Plain rows with no tap. Today two of them are dead taps and one wrongly opens Transactions. | Don't invent a destination. |
| O-3 | Card labels | Localize the "Recommendations" header now. "Min projected:" and the card's other English labels become a follow-up task. | The header sits directly above the localized rows. Keeps scope small. |
| O-4 | "No immediate concerns" shows at MODERATE risk, and the card shows only two suggestions | **Out of scope.** Record as a follow-up. The emission rules don't change (INV-2). | Changing them alters forecast behaviour. With today's order, an information-only row can never push out one that leads somewhere. |
| O-5 / O-6 | Stored `recommendationsJson`; serialization | **Leave it as is.** Nothing writes or reads it, and no schema change is needed. | Removing the column would be destructive. |
| D-PL | Plural for "run out in N days" | Use `<plurals>`, as in ux-flows. | Greek and Spanish grammar, and lint-safe, even though English only ever shows 30, 60 or 90 days. |
| UX-1 | Look of information-only rows | Neutral tint with no border or chevron. Rows that lead somewhere keep the chip and get a chevron. | Not distinguished by colour alone. |
| UX-2 | Partial localization of the card | Accept for now (header only), with one follow-up to localize the whole card. | Same as O-3. |
| UX-3 / UX-4 | "No concerns" at MODERATE; `take(2)` | Follow-ups (same as O-4). | — |
| UX-5 | The buffer amount uses the forecast's display currency, while the card's other amounts use the home currency | Use the forecast's own currency for the buffer (INV-9). Add a follow-up to confirm the two currencies always match. | It never shows an amount in the wrong currency. |
| UX-6 | EL/ES wording | You sign off before merge. | Standing rule. |
| UX-7 | The bulb emoji | Hide it from TalkBack. | Accessibility. |
| ORD | Order against the quick fixes | QF-2 (Money Radar, about 5 lines away in `HomeScreen.kt`) lands **first**, then this feature, rebased onto it. One writer on `HomeScreen.kt` at a time, or both in one lane. The briefing S-fix ordering is **not** needed, since this feature doesn't edit the dashboard calculation file. | Avoids conflicting edits to the same file. |
| R-1 | Review | T2: one cheap review with a money-formatting check, and no reviewer-strict. The validation runner runs targeted tests, lint once and `static-guards` once (cancellation guard). | T2 rule. |

## Verified facts (orchestrator)
- The cancellation allowlist is keyed by file name, and the forecast engine file isn't on it, so no guard risk.
- A source-scan test anchors on the engine's `StressForecastResult(` construction. The plan keeps it in place.

## Consequences if accepted
- The domain-modeler records the answers in the spec.
- The feature-planner writes `workflows/active/stress-forecast-actions-plan.md` (S1 domain and engine, S2 UI and strings, both in one compile batch).
- Implementation stays on hold. It needs "planning only" lifted, and QF-2 first (or a rebase onto it).

## Resolution (2026-10-26)
The orchestrator applied **all recommendations** under the owner's standing instruction (2026-10-25: "apply my stated
recommendations on owner-gate questions; still ask for merges, destructive/guard/test/permission items, es/el
sign-off"). This sheet has no guard, test, destructive or permission item. The el/es sign-off remains open until
merge. The owner may override any item at the plan gate.
