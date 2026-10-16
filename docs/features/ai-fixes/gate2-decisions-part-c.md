# ai-fixes Part C (assistant Track 0) — decision sheet

Prepared by the orchestrator from `domain-spec-part-c.md` (OD-C1..C6), `impact-map-part-c.md` §7. Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "Recommended" → every PC-n = Recommended.

| # | Decision | Recommended |
|---|---|---|
| PC-1 | OD-C1: prompts (on-device AND cloud — they share one prompt builder) carry only the last prior USER questions, never assistant answers | **Yes, both** |
| PC-2 | OD-C6: number of prior user questions kept | **4** |
| PC-3 | OD-C2: "smallest/cheapest" — implement a MIN executor mirroring "largest" (vs localized "not supported"; don't remove from schema) | **Implement** |
| PC-4 | OD-C3: clarification options on older cards in the same session shown disabled (no "restored transcript" exists today) | **Yes** |
| PC-5 | OD-C4: "compare" for "this month" mid-month: full October vs full September (vs Oct 1–16 vs Sep 1–16) | **Full vs full** |
| PC-6 | OD-C5: add LAST_WEEK / LAST_QUARTER / LAST_YEAR to the model's period keywords | **Yes** |
| PC-7 | Tests (stop-and-ask): retarget `FinancialQueryInterpretationInputBuilderTest` "build trims truncates and enriches…" (:54-97) and "build redacts when privacy requires it…" (:164-190); **delete** `AssistantViewModelTest` "submitQuery strips history payloadJson when privacy requires redaction…" (:484-544) because the VM branch is removed and its coverage moves to the builder tests; mechanical retargets of ~20 provider/interpret assertions on the reason type | **Approve (delete :484-544, coverage moved)** |
| PC-8 | Engine5 guard: the new period resolver returns plain start/end values, callers wrap them — no allowlist entry added to the guard | **Yes (no guard weakening)** |
| PC-9 | 3 new automated checks: assistant VM never reads `redactBeforeCloud`; only the builder builds the interpretation input; no assistant-answer line in a cloud request body | **Approve** |
| PC-10 | The new "don't OR redaction flags outside the policy resolver" rule: scope it to the assistant now; migrating the 6 other AI input builders goes on the follow-up list (pairs with `cloud-alias-hashing`) | **Scope to assistant + follow-up** |
| PC-11 | Stop logging amounts in `validateAmountBound` in both places (Interpret use case and on-device service) | **Both, in C2** |
| PC-12 | Placement inside ai-fixes: right after Part A (privacy exposure reduced sooner) vs after Part B | **Right after A** |
| PC-13 | Strings: ~13 new keys ship with EN + Greek + Spanish (no lint-baseline refresh); owner reviews Greek | **Yes** |
| PC-14 | Docs: create an assistant section in LEGAL_PATHS (completion path, forbidden lines); update the stale line in money-correctness impact map (Part C now touches assistant use cases; no source conflict); release-note line for corrected period answers | **Yes** |
| PC-15 | Risk tier: Part C T3 overall (C1, C2, C5 T3; C3, C4 T2); privacy-security-guardian + architecture-guardian on C1/C2; reviewer-strict on C1, C2, C4, C5; no schema change | **Accept** |
