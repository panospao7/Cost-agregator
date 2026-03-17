# Phase 4B Plan

## Goal

Extend Phase 4 with read-only, dashboard-driven follow-through that helps users act on an AI briefing by opening existing deterministic screens or filters.

Phase 4B should keep AI responsible for summarization only while deterministic code remains authoritative for navigation targets, filters, and all financial truth.

## Dependency On Phase 4A

- Start Phase 4B implementation only after the Phase 4A manual checklist in `docs/AI_PHASE4A_QA_CHECKLIST.md` is complete or any remaining gaps are explicitly accepted.
- Reuse the Phase 4A rollback, diagnostics, and engagement patterns instead of inventing new rollout controls.

## Selected Theme

Phase 4B should focus on structured dashboard follow-through.

Instead of adding broader automation next, the app should turn the existing AI dashboard briefing into a guided launcher for safe next steps:

1. review queue attention
2. budget attention
3. transaction drilldown
4. recurring or planned-obligation review

The user still decides whether to tap any action, and the resulting screen stays fully deterministic.

## Why This Is The Best Fit Now

- Phase 4A already established the dashboard briefing, proactive dashboard deep link, rollback toggles, and engagement tracking.
- The dashboard stack already aggregates the deterministic signals needed to point users toward the right screen.
- Home already has an AI briefing surface that can host a small follow-through layer without creating a new app surface.
- This expands AI from passive text into guided action without introducing new AI write paths.

## Repo-Grounded Hooks

Phase 4B should build on the following existing files and seams:

- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/MainViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/recurring/RecurringExpensesScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`

## Guardrails

- Deterministic code selects follow-through targets, ranking, and drilldown filters.
- AI may help summarize why attention is needed, but it must not authoritatively choose the destination or synthesize the filter truth.
- No new notification action buttons that navigate or write directly from the system notification.
- Keep the proactive notification dashboard-deep-link-only in Phase 4B.
- Keep `NotificationProcessingPipeline.kt` free of AI calls.
- Keep `CategorizationEngine.kt` authoritative for deterministic categorization.
- Do not add auto-approve, auto-reject, auto-merge, auto-delete, or auto-open behavior.
- Do not add any AI writes to `Expense`, `PendingReview`, budgets, planned expenses, recurring rules, or location data.
- Add a dedicated rollback toggle for Phase 4B follow-through instead of bundling it into the existing dashboard briefing toggle.
- Avoid any new Room migration unless implementation proves it is necessary. The default Phase 4B plan should stay in-memory and derived from current dashboard state.

## Proposed Feature Set

### 1. Dashboard Follow-Through Recommendations

Add a small, ranked set of recommendations under the existing dashboard AI briefing slot.

These recommendations should point to existing surfaces only:

- review queue
- budget screen
- filtered transactions
- recurring or planned-obligation management

### 2. Deterministic Recommendation Builder

Build a deterministic mapper from processed dashboard data into at most 3 follow-through recommendations.

Example inputs that already exist in the repo:

- pending review count
- budget warnings / critical budgets
- top spending categories
- upcoming recurring and planned items
- current-month spending context

### 3. Safe Navigation Targets

The recommendation builder should output a structured target model, for example:

- `REVIEW_QUEUE`
- `BUDGET`
- `TRANSACTIONS_FILTER`
- `RECURRING`

If a recommendation needs a transaction drilldown, the filter should be produced by deterministic app code using the existing `TransactionFilter` model.

### 4. Interaction Tracking

Record follow-through impressions and taps with the same lightweight diagnostics style already used in Phase 4A.

Track only enough information to validate rollout behavior, for example:

- recommendation shown
- recommendation opened
- recommendation dismissed if the UI adds dismissal

### 5. Debug Visibility

Expose the current Phase 4B toggle and recent follow-through interactions in the debug surface so rollout behavior stays inspectable.

## Suggested Models

Keep the model small and deterministic.

Potential additions:

- `DashboardFollowThroughRecommendation`
- `DashboardFollowThroughTarget`

Recommended target shapes:

- review queue target
- budget target
- recurring target
- transactions target with deterministic `TransactionFilter`

These do not need to be stored in Room for the first Phase 4B slice.

## Recommended PR Slicing

### PR1: Guardrails And Recommendation Builder

- Add a dedicated Phase 4B settings toggle, for example `dashboardFollowThroughEnabled`.
- Build a deterministic mapper from processed dashboard data to ranked recommendations.
- Keep AI briefing generation unchanged.
- Do not change notifications yet.

### PR2: Home Integration

- Expose follow-through recommendations from `HomeViewModel`.
- Render them under the existing `NaturalLanguageInsight` / AI briefing slot in `HomeScreen.kt`.
- Reuse existing navigation callbacks for review, recurring, and transaction drilldown.
- Add any minimal shell callback needed for budget navigation.

### PR3: Tracking And Debug Hardening

- Record recommendation impressions and opens.
- Surface Phase 4B state in the debug screen.
- Verify the toggle removes the UI immediately when disabled.
- Keep the proactive notification dashboard-only.

### PR4: Closeout And Manual QA

- Add tests for builder ranking, toggle behavior, and safe navigation routing.
- Add a manual QA checklist for Phase 4B.
- Run broad verification and confirm no regression in Phase 4A flows.

## Explicit Non-Goals

- No action buttons in the proactive notification.
- No AI-generated `TransactionFilter` or AI-generated budget/category identifiers.
- No queue-level review automation or batch apply behavior.
- No AI writes to budgets, planned expenses, recurring rules, or location records.
- No location-summary rollout in Phase 4B.
- No broader proactive coaching surface outside the dashboard flow.

## Alternatives Considered And Deferred

### Budget Coach As A Separate Surface

Deferred because the dashboard already contains the deterministic inputs and the first safe win is to add actionability to the existing briefing surface rather than create a second planning surface.

### Review Queue Triage Digest

Deferred because the repo has stronger per-item review AI hooks than queue-level orchestration, and this risks drifting toward batch automation too early.

### Location-Aware AI Summaries

Deferred because the location stack is available, but the end-to-end AI artifact and UI path is much less mature than the dashboard briefing path.

## Entry Criteria For Phase 4C Or Broader Phase 4

- Follow-through recommendations are understandable and low-noise.
- Recommendation taps land on the correct deterministic screens and filters.
- The Phase 4B toggle removes the feature immediately.
- Phase 4A receipt quick save, proactive briefing delivery, and review quick approve still behave correctly.
- Debug diagnostics remain sufficient to audit delivery, open, preview, dismiss, and follow-through behavior.

## Current Slice

Planning only.

If implementation starts, the first recommended slice is PR1: guardrails and deterministic recommendation builder.

## Related Parallel Track

Receipt OCR hardening through image-aware cloud assist is tracked separately in `Receipt Image Assist plan.md`.

That work is intentionally separate from core Phase 4B because it improves the existing receipt assist path rather than extending dashboard follow-through behavior.
