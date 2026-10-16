# ai-fixes — Gate 2 decision sheet

Prepared by the orchestrator from `domain-spec.md` §14, `ux-flows.md` (UX-Q-1..5), `impact-map.md` §8. Date 2026-10-16.
Status: DECIDED 2026-10-16 — owner: "do the recommended" → every G2-n = Recommended column.

| # | Decision | Options | Recommended |
|---|---|---|---|
| G2-1 | OD-1 privacy guard for local warranty reading | (a) receipt privacy rule (Policy A, in-memory OCR only) is the guard; remove the cloud check + PrivacyGate dep from the warranty use case; fix LEGAL_PATHS :992/:998 · (b) new local-only PrivacyCapability (~10 files, 4+ tests) | **(a)** |
| G2-2 | OD-2 WarrantyExpirationWorker requires notification permission | (A) decouple in this bundle (same pattern as briefing) · (B) defer (UI still correct) | **(A)** |
| G2-3 | Guard change (stop-and-ask) | Allowlist `DailyBriefingWorker.kt` (+ `WarrantyExpirationWorker.kt` if G2-2=A) in `SourceScanningArchitectureGuardTest.kt:408`; rule itself not weakened (allowlisted workers must check permission locally) | **Approve** |
| G2-4 | New guards (stop-and-ask) | 3 new source-scan guards: cloud warranty service referenced only from DI; insight use case can't import cloud/hybrid briefing services; warranty VM can't build status in write paths | **Approve** |
| G2-5 | DB ownership policy (GR-08) edits (stop-and-ask) | Delete stale `toWarrantyEntityOrNull` row; add `confirmWarranty` rows; regenerate signatures per R7 Pattern E | **Approve** |
| G2-6 | Test deletions (stop-and-ask, FG-19) | Delete `WarrantyTrackerRepositoryTest` :641 `cloudWarrantyConfidence_0_8_autoCreatesWithoutReview`, :673 `…_0_4_createsNeedsReviewDraft`, :705 `…_0_1_discardsWithDiagnostic`, :735 `…_0_75_exactBoundary_autoAccepts`, :767 `…_0_30_exactBoundary_createsReviewDraft` (all test the retired cloud warranty path). Retarget (not delete): :127, :171, :217, :316, :347; `GenerateTransactionInsightUseCaseTest` :71, :114; `SyncProactiveBriefingWorkUseCaseTest` :43; `DeliverProactiveBriefingNotificationUseCaseTest` (6); `DailyBriefingWorkerTest` :757 | **Approve** |
| G2-7 | Translations / lint | Provide Greek + Spanish for every new/changed string key (no lint-baseline refresh) · English-only now | **Greek + Spanish for new/changed keys**; backfill of older untranslated AI/warranty keys = follow-up |
| G2-8 | UX-Q-1 Expired filter | Expired chip includes expired-unconfirmed + optional "N expired without confirmation" line | **Yes** |
| G2-9 | UX-Q-2 Reject confirmation | Confirmation dialog before Reject (irreversible delete; also applies to review cards) | **Yes** |
| G2-10 | UX-Q-3 Notifications-blocked caption | AI settings shows a caption when notifications are blocked (read-only state; no new permission, no deep link) | **Yes** |
| G2-11 | FEATURES.md :911 "AI-generated negotiation scripts" | In scope (same mislabel class) · out | **In** |
| G2-12 | Insight timing | Shared recommendation-job timeout 5 s (> insight 3 s + 2 s, INV-18); insight 3 s kept pending a measurement on owner's Nano phone; GenAI errors collapse to a bounded PROVIDER_ERROR (no typed carrier) | **Approve** |
| G2-13 | Sequencing | Wait for Release 1.0 acceptance + `release-1.0-wt` merge; branch from `bug-fixes`; if release batch R24 fixes `WarrantyTrackerViewModel` first, Part A keeps only the remaining deltas | **Approve** |
| G2-14 | Adjacent log leak in touched file | `AutoCreateWarrantyFromReceiptUseCase.kt:110-115` logs product/merchant names + `:151` logs a throwable → fold into B1 (privacy hygiene, file already touched) · leave | **Fold in** |
| G2-15 | Other adjacent findings | Retry of deterministic failure (`ReceiptSideEffectPlanner.kt:216-219`), `AI_WARRANTY_CREATED` actor label, hard-coded "€" in fallback recommendation text (`DashboardFollowThroughEngine.kt:150`), warranty worker gated by NOTIFICATION_CAPTURE (`PrivacyRuntimeWorkerPolicy:60-63`), 1 ms expiry boundary | **Out of scope → follow-up list** in ai-evolution handoff |
| G2-16 | Risk tier | Part A T1; B1/B2/B3 (+OD-2) T3 → planner-first, architecture-guardian + privacy-security-guardian + reviewer-strict per T3 slice; room-migration-guardian advisory only; no schema change, no migration tests | **Accept T3** |

UX-Q-4 (hidden warranty capability may still feed the "Needs attention" card) and UX-Q-5 are handled by the planner (verify exclusion) and G2-7 respectively.
