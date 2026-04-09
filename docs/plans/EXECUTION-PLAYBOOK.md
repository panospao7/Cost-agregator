its cruc# Execution Playbook — ExpenseTracker Refactoring

> **Project:** ExpenseTracker (Android/Kotlin/Clean Architecture)
> **Branch:** `master-refactor`
> **Current Phase:** Phase 1 — Universal Epics
> **Active Epic:** A.8 — Shared Mutable State / Thread Safety Gaps
> **Status:** A.1 COMPLETE — A.2 COMPLETE — A.3 COMPLETE — A.4 COMPLETE — A.5 COMPLETE — A.6 COMPLETE — A.7 COMPLETE — A.8 next

---

## 0. AUTONOMOUS EXECUTION RULES (CRITICAL)
- **Full Cycle Completion:** Only move to the next epic when the current one is FULLY complete (Plan → Code → Review PASS → Documentation → Commit).
- **No Permission Requests:** Do NOT ask the user for permission or directions. Follow the instructions autonomously.
- **No Stopping:** Do NOT stop until ALL 10 epics (A.1 through A.10) are resolved, then continue through Phase B until all pipeline issues are resolved.
- **Commit but Don't Push:** At the end of each epic, commit the changes but DO NOT push to remote. Push only when all 10 epics are complete.
- **Documentation is Mandatory:** Every epic MUST update the Registry and affected Batch reports BEFORE the commit. No exceptions.
- **Review Loop:** If the reviewer finds issues, fix them one at a time using @coder, then re-review. Repeat until PASS.
- **Micro-Batch Coding:** Always use @specialist-coder for 1-5 files at a time. Never batch large changes.
- **Sequential Execution:** Complete A.1 → A.2 → A.3 → ... → A.10 in order. Do not skip or parallelize epics.
- **Phase Gate:** Do NOT begin any Phase B pipeline until **all A epics are fully complete** (PASS + docs + commit).

---

## 1. Mission Statement
We are systematically fixing **~580+ verified issues** across the ExpenseTracker codebase. The work is driven by the **MASTER-ISSUE-REGISTRY.md**, which consolidates findings from 48 batch verification reports.

**Core Principles:**
- **Treat Documentation as Code:** Every code change must be accompanied by a documentation update in the Registry and affected Batch reports.
- **Single Source of Truth:** Define canonical utilities/rules for each epic and enforce them strictly. No ad-hoc fixes.
- **Micro-Batch Execution:** Coding agents must work on 1-5 files at a time to avoid context overflow and ensure precision.
- **Living Documentation:** The Registry is not static. It MUST be updated in the same commit as the code fix.

---

## 2. The Master Registry
**File:** `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`

This is the **single source of truth** for all issues. It is organized into:
- **Section A:** Universal Architectural Epics (10 items) — Fix these first.
- **Section B:** Domain-Specific Pipelines (12 pipelines) — Fix after Epics.
- **Section C:** Cross-Component Dependencies — Fix as blockers arise.
- **Section D:** Isolated/Quick-Win Bugs — Fix last or in parallel.

**How to use it:**
1.  **Select an Epic/Issue:** Read the exact text from the Registry.
2.  **Plan:** Create a detailed `PLAN-<ID>.md` in `docs/plans/`.
3.  **Execute:** Code changes + Test updates.
4.  **Update Docs:** Mark the Registry entry as `[RESOLVED BY <ID>]` and update the 15+ affected Batch reports.

---

## 3. Execution Workflow

### Step 1: Planning
- **Input:** Registry text for one Epic/Issue.
- **Action:** Call `@planner` to create `PLAN-<ID>.md`.
- **Output:** A strict, file-by-file checklist with constraints and verification steps.

### Step 2: Coding (Micro-Batches)
- **Input:** `PLAN-<ID>.md`.
- **Action:** Call `@specialist-coder` for **ONE micro-batch** (1-5 files).
- **Constraint:** "Do NOT touch files outside this batch. Do NOT change schemas/entities. Do NOT break public APIs."
- **Output:** Code changes + passing tests.
- **Repeat:** Continue calling `@specialist-coder` for each micro-batch until ALL batches for the Epic are complete.

### Step 3: Verification
- **Action:** Run `compileDebugKotlin`, `testDebugUnitTest`, and check for Room SQL validation errors.
- **Output:** Build success + green tests.

### Step 4: Review (CRITICAL — After ALL Batches of an Epic Are Complete)
- **Action:** Call `@reviewer` to review the ENTIRE Epic implementation against the plan.
- **Rules for @reviewer:**
  - Read the original `PLAN-<ID>.md` for the Epic.
  - Read ALL modified files from ALL batches.
  - Cross-reference every checklist item in the plan against the actual code changes.
  - Verify no constraints were violated (no schema changes, no API breaks, etc.).
  - Check for regressions in dependent pipelines.
  - Write a review report to `docs/reviews/REVIEW-<ID>.md` with:
    - ✅ Items correctly implemented
    - ⚠️ Items partially implemented or needing attention
    - ❌ Items not implemented or incorrectly implemented
    - Remedy plan for any issues found
- **If issues found:**
  - Break each issue into a small, targeted fix (1-3 files per fix).
  - Call `@coder` for **ONE fix at a time** (never batch multiple fixes together).
  - After each fix, re-read the review report and update it.
  - Once ALL issues are fixed, call `@reviewer` AGAIN to re-evaluate the entire epic.
  - **Repeat this loop** until the reviewer gives a ✅ PASS verdict.
- **Output:** Clean review report (PASS) or remediated code + updated review.

### Step 5: Documentation Update (CRITICAL)
- **Action:** Update `MASTER-ISSUE-REGISTRY.md` and all affected `FINAL-VERIFICATION-BATCH-XX.md` files.
- **Rule:** If the fix changes the nature of downstream issues, mark them as `[RESOLVED BY <ID>]` or `[OBSOLETE BY <ID>]`.
- **Output:** Commit includes code + docs + review report.

---

## 4. Agent Directives

### @planner
- **Task:** Create `PLAN-<ID>.md` from Registry text.
- **Structure:**
  1. Objective & Blast Radius
  2. Single Source of Truth (The Standard)
  3. File-by-File Checklist (Domain → Data → UI)
  4. Verification Plan (Tests, Lint, Room)
  5. **Documentation & Registry Updates** (Explicit list of files to edit)
- **Constraint:** Plans must be executable by a coding agent. No vague instructions.

### @specialist-coder (Primary Coding Agent)
- **Task:** Execute ONE micro-batch from the Plan.
- **Rules:**
  - Read the Plan and the target files FIRST.
  - Make precise edits. Do NOT refactor unrelated code.
  - **NEVER** change Room entities, schemas, or migrations unless explicitly authorized.
  - **NEVER** break public repository APIs.
  - **ALWAYS** verify build compiles and tests pass.
  - **REPORT:** List every file changed and the exact SQL/logic modified.

### @coder
- **Task:** Simple, single-file edits or quick fixes. Use for minor changes only.

### @reviewer
- **Task:** Verify fixes against the plan and find regressions.
- **Rules:**
  - Read the original `PLAN-<ID>.md` for the batch being reviewed.
  - Read all modified files from the batch.
  - Cross-reference every checklist item in the plan against the actual code changes.
  - Verify no constraints were violated.
  - Check for regressions in dependent pipelines.
  - Write a review report to `docs/reviews/REVIEW-<ID>-<BATCH>.md`.
  - If issues found, provide a remedy plan for the `@coder` to execute.

## 4a. Agent Prompt Templates

### @reviewer — Initial Review (After Code Implementation)
```
You are the @reviewer agent. Review the implementation of [EPIC-NAME] against the plan.

## READ FIRST
1. Read the plan: `docs/plans/PLAN-[ID].md`
2. Read all modified files from the implementation

## VERIFICATION CHECKS
- [ ] Cross-reference every checklist item in the plan against actual code
- [ ] Check for constraint violations (Room schemas, public APIs, etc.)
- [ ] Search for regressions in dependent pipelines
- [ ] Run grep for anti-patterns specific to this epic

## OUTPUT
Write a review report to `docs/reviews/REVIEW-[ID].md` with:
- ✅ Items correctly implemented
- ⚠️ Items partially implemented or needing attention
- ❌ Items not implemented or incorrectly implemented
- Remedy plan for any issues found (use @coder for ONE fix at a time)

**If issues found:** Do NOT fix them yourself. Call @coder for each fix individually, then re-review.
```

### @reviewer — Re-review (After Fixes)
```
You are the @reviewer agent. Re-evaluate [EPIC-NAME] after fixes were applied.

## READ FIRST
1. Read the previous review: `docs/reviews/REVIEW-[ID].md`
2. Read the fix that was applied

## VERIFICATION CHECKS
- [ ] Verify the fix was implemented correctly
- [ ] Check for new regressions introduced by the fix
- [ ] Run verification commands to confirm fix works

## OUTPUT
Update the review report with:
- Status of previously found issues (✅ fixed / ❌ still broken)
- Any new issues discovered
- Final verdict: ✅ PASS or ❌ FAIL
```

### @coder — Fix Single Issue
```
Fix issue #[N] from the review report.

## PROBLEM
[Description from review report]

## FILE
- [File path]

## REMEDY
[Exact fix required - be specific about what to change]

## VERIFICATION
After the fix, run: `./gradlew.bat :app:testDebugUnitTest --tests "[RelevantTest]"`
```

### @planner — Create Plan
```
You are the @planner agent. Your task is to read the selected epic from our MASTER-ISSUE-REGISTRY.md and generate an implementation_plan.md artifact. This plan will be handed off to an AI Coding Agent for execution.

## INPUT: EXACT REGISTRY TEXT (Do NOT read the full registry, use only this)
```
[PASTE THE EXACT EPIC TEXT FROM MASTER-ISSUE-REGISTRY.md]
```

## REQUIRED OUTPUT STRUCTURE
Write the plan to: `docs/plans/PLAN-[ID].md`
The plan MUST strictly follow this structure:

### 1. Objective & Blast Radius
- **The Core Issue:** State exactly what the bug/drift is in 1-2 sentences.
- **Blast Radius:** List all downstream repositories, engines, or UI components that will be affected.

### 2. The Single Source of Truth (The Standard)
- Define the canonical rule or utility that will fix this.

### 3. File-by-File Execution Checklist
- Group changes logically (Domain Layer first, then Data Layer, then UI Layer).
- For each file, be relentlessly specific about what to change and what NOT to change. Use checkboxes `[ ]`.
- Include `> [!WARNING]` GitHub-style alerts to explicitly forbid dangerous changes.

### 4. Verification Plan
- **Unit Tests:** Tell the agent exactly which existing test files to update or run.
- **Syntax/Lint:** Instruct it to ensure no imports were broken.

### 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:** Specify exactly which lines/sections in `MASTER-ISSUE-REGISTRY.md` must be marked `[RESOLVED BY ID]`.
- **Batch Reports:** Instruct the agent to update the affected batch report files.

## CONSTRAINTS

### Universal (Always Apply)
- Do NOT break public repository APIs (add deprecation path if needed)
- Keep all changes backward-compatible where possible
- ALWAYS verify build compiles and tests pass after each micro-batch
- Do NOT hallucinate — if a file doesn't exist or method signature is unclear, READ it first

### Job-Specific (From Epic Analysis)
- [Specify based on the epic, e.g.:]
- Do NOT change Room entity definitions or @Entity annotations
- All new domain DTOs must be placed in `domain/model/` or `domain/dto/`
- Do NOT modify specific files/layers as identified in blast radius
```

### @specialist-coder — Execute Micro-Batch
```
You are the @specialist-coder agent. Your task is to execute **Batch N** of the [EPIC-NAME] plan.

## READ FIRST (In this exact order)
1. Read the Execution Playbook: `docs/plans/EXECUTION-PLAYBOOK.md`
2. Read the full plan: `docs/plans/PLAN-[ID].md` (focus on the assigned batch)
3. Read all target files for this batch

## EXECUTE: Files in this batch ONLY
Apply changes as specified in the plan. Do NOT touch any other files.

## ⚠️ STRICT CONSTRAINTS
> [!WARNING]
> - Do NOT change Room entities, @Entity annotations, table schemas, migrations, or column names.
> - Do NOT change the public API without adding a deprecation path.
> - Do NOT touch files outside this batch.
> - Do NOT refactor unrelated code.

## ✅ VERIFICATION (Do this before finishing)
1. Read every modified file and verify correctness.
2. Check imports — ensure no broken imports.
3. Run compile to verify: `./gradlew.bat :app:compileDebugKotlin`
4. Report back with exact changes made in each file.
```

### @coder — Fix Single Issue
```
You are the @coder agent. Fix ISSUE-[N] from the [EPIC] final verification report.

## READ FIRST
1. Read the final verification report: `docs/reviews/REVIEW-[ID].md` (find ISSUE-[N])
2. Understand the exact problem and remedy specified

## PROBLEM
[Description of what is broken]

## FILE
- [File path to fix]

## REMEDY
[Exact fix required - be specific about what to change]

## ⚠️ CONSTRAINTS
> [!WARNING]
> - Do NOT change Room entities, schemas, or migrations.
> - Only change what's needed to fix this specific issue.
> - Do NOT refactor unrelated code.

## ✅ VERIFICATION
After the fix, run verification commands to confirm:
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "[RelevantTest]"`

Report back with the exact changes made.
```

### @debugger — Deep Investigation
```
Investigate the root cause of [ISSUE/DESCRIPTION].

## SYMPTOMS
[What is broken / error message / unexpected behavior]

## INVESTIGATION
1. Read relevant source files
2. Trace the code path to find the root cause
3. Identify what needs to change to fix it

## OUTPUT
Provide:
- Root cause analysis
- Exact file and line numbers
- Recommended fix (use @coder to implement)
```

### @debugger
- **Task:** Deep bug/root-cause investigation when implementation is blocked.

---

## 5. Critical Rules

### The "Ripple Effect" Rule
When a Universal Epic (like A.1) is fixed, it likely resolves or alters downstream issues in other batches.
- **Action:** After fixing an Epic, scan the Registry for related issues.
- **Update:** Mark them as `[RESOLVED BY <ID>]` or update their severity/description.

### Context Management
- **Problem:** Agents hit token limits when processing too many files.
- **Solution:** Always split work into micro-batches (1-5 files). Provide exact file paths and line numbers.

### No Hallucination
- **Rule:** If a file doesn't exist or a method signature is unclear, READ the file first. Do NOT guess.
- **Rule:** If a test fails, fix the test or the code. Do NOT ignore it.

### Documentation is Code
- **Rule:** A fix is NOT complete until the Registry and Batch reports are updated.
- **Rule:** Use the exact format `[RESOLVED BY A.1]` for tracking.

---

## 6. Current Status Tracker

### Phase 1: Universal Epics
- [x] **A.1:** effectiveAmount vs amount Inconsistency
  - [x] Plan created (`PLAN-A1-effectiveAmount-standardization.md`)
  - [x] Batch 1.1: ExpenseDao.kt SQL Helper — **COMPLETED** (30 queries migrated)
  - [x] Batch 1.2: ExpenseRepository.kt — **COMPLETED** (inline expr replaced)
  - [x] Batch 1.3: BudgetRepository.kt & MultiCurrencyRepository.kt — **COMPLETED** (already compliant)
  - [x] Batch 1.4: AccountingExportRepository.kt & ExpenseWithCategory — **COMPLETED** (7 changes)
  - [x] Batch 2.1: Analytics Engines — **COMPLETED** (InsightsEngine 2 lines changed)
  - [x] Batch 2.2: Budget/Forecast Engines — **COMPLETED** (already compliant, tests added)
  - [x] Batch 2.3: Business/Tax/Income Engines — **COMPLETED** (already compliant)
  - [x] Batch 2.4: Receipt/Challenge Engines — **COMPLETED** (already compliant)
  - [x] Batch 3: UI Layer (Screens/Formatters) — **COMPLETED** (already compliant)
  - [x] Batch 4: Verification (Tests) — **COMPLETED** (review PASS)
  - [x] Batch 5: Documentation Updates — **COMPLETED**
  - [x] **Step 4: Review** — **PASS**
- [x] **A.2:** Domain/Data Layer Boundary Violations
  - [x] Plan created (`PLAN-A2-domain-data-boundary.md`)
  - [x] Batch 1: Dashboard/BlockParty boundary cleanup — **COMPLETED**
  - [x] Batch 2: Domain enums + AI DTO migration — **COMPLETED**
  - [x] Batch 3: AI Artifact repository DTO migration — **COMPLETED**
  - [x] Batch 4: Narrative/resource boundary cleanup — **COMPLETED**
  - [x] Review: PASS (8 issues found → all resolved after 3 review passes)
  - [x] Documentation Updates — **COMPLETED**
  - [x] Commit — **COMPLETED**
- [x] **A.3:** Non-deterministic Default Values
  - [x] Plan created (`PLAN-A3-non-deterministic-defaults.md`)
  - [x] All batches implemented (TimeProvider injection, correlationId fixes, test updates)
  - [x] Review: **PASS** (after 4 review passes, 9 issues fixed)
  - [x] Registry updated (`[RESOLVED BY A.3]`)
  - [x] Committed
- [x] **A.4:** Duplicate Detection Logic Inconsistencies
  - [x] Plan created (`PLAN-A4-duplicate-detection.md`)
  - [x] All issue-review batches completed (ISSUES 1-9 resolved)
  - [x] Review: **PASS**
  - [x] Registry updated (`[RESOLVED BY A.4]`)
  - [x] Batch reports + deep-analysis mirrors updated
  - [x] Committed (`b857b28`)
- [x] **A.5:** Time Boundary / Calendar Arithmetic Inconsistencies
  - [x] Plan created (`PLAN-A5-time-boundaries.md`)
  - [x] All batches implemented
  - [x] Review: **PASS**
  - [x] Registry updated (`[RESOLVED BY A.5]`)
  - [x] Batch reports + deep-analysis mirrors updated
  - [x] Committed
- [x] **A.6:** Mixed Numeric Types (Float vs Double)
  - [x] Plan created (`PLAN-A6-mixed-numeric-types.md`)
  - [x] All batches implemented
  - [x] Review: **PASS**
  - [x] Registry updated (`[RESOLVED BY A.6]`)
  - [x] Batch reports + deep-analysis mirrors updated
  - [x] Committed
- [x] **A.7:** Fire-and-Forget Coroutine Anti-Pattern
  - [x] Plan created (`PLAN-A7-fire-and-forget-coroutines.md`)
  - [x] All batches implemented
  - [x] Review: **PASS**
  - [x] Registry updated (`[RESOLVED BY A.7]`)
  - [x] Batch reports + deep-analysis mirrors updated
  - [x] Committed
- [ ] **A.8:** Shared Mutable State / Thread Safety Gaps
- [ ] **A.9:** Hidden Data Truncation / DAO Default Limits
- [ ] **A.10:** Transaction Type Blindness

### Phase 1 Todo Roadmap (Strict Priority)
1. **A.8 — Shared Mutable State / Thread Safety Gaps**
   - Priority rationale: concurrency safety across singleton services, caches, formatters, and managers
2. **A.9 — Hidden Data Truncation / DAO Default Limits**
   - Priority rationale: critical data correctness issue with large blast radius; defer until concurrency and time-contract foundations are stable
3. **A.10 — Transaction Type Blindness**
   - Priority rationale: high-severity aggregation correctness pass that should land after A.9 query contract cleanup to avoid rework

### Phase 2: Pipeline Fixes
- [ ] B.1: AI/ML Pipeline
- [ ] B.2: Budget/Forecasting Pipeline
- [ ] B.3: Receipt/OCR Pipeline
- [ ] B.4: Database/DAO/Entity Pipeline
- [ ] B.5: Location/Geocoding Pipeline
- [ ] B.6: Notification/Service/Worker Pipeline
- [ ] B.7: Export/Backup Pipeline
- [ ] B.8: Savings/Investment Pipeline
- [ ] B.9: UI/Compose Pipeline
- [ ] B.10: Categorization/Intelligence Pipeline
- [ ] B.11: Email/Parsing Pipeline
- [ ] B.12: Groups/Shared Expenses Pipeline

### Phase 2 Todo Roadmap (Priority Order)
0. **Gate:** Phase 2 may start only after **A.1–A.10 are all complete, documented, and committed**.
1. **B.4 — Database/DAO/Entity Pipeline**
   - Must run first and in isolation because schema/entity/DAO contract changes can invalidate other pipelines instantly.
2. **B.1 — AI/ML Pipeline**
   - Contains stop-ship privacy/GDPR issues and multiple high-severity routing/data-loss bugs.
3. **B.3 — Receipt/OCR Pipeline**
   - High-risk extraction, OCR normalization, warranty, and statement parsing correctness.
4. **B.6 — Notification/Service/Worker Pipeline**
   - Core ingestion/service reliability and worker correctness; heavily user-facing.
5. **B.11 — Email/Parsing Pipeline**
   - Parsing correctness and email import data integrity with overlaps to receipt/notification semantics.
6. **B.2 — Budget/Forecasting Pipeline**
   - Large business-critical pipeline with many high-severity forecasting/math issues.
7. **B.10 — Categorization/Intelligence Pipeline**
   - Important intelligence consistency issues, but safer after upstream AI/data contracts stabilize.
8. **B.5 — Location/Geocoding Pipeline**
   - Can often run safely in parallel with unrelated pipelines after B.4 is done.
9. **B.7 — Export/Backup Pipeline**
   - Fairly isolated and good candidate for parallel execution after B.4.
10. **B.8 — Savings/Investment Pipeline**
   - Financial-model heavy, but mostly independent after foundations stabilize.
11. **B.9 — UI/Compose Pipeline**
   - Large user-facing cleanup layer best done after deeper domain/data pipelines settle.
12. **B.12 — Groups/Shared Expenses Pipeline**
   - Domain-specific but intertwined with shared-expense correctness; safer after broader financial/data semantics stabilize.

### Phase 2 Parallelization Rules (CRITICAL)
- **Golden Rule:** Parallelize **across different pipelines**, never **within the same pipeline**.
- Safe example: one agent on B.3, one on B.5, one on B.7.
- Unsafe example: two agents both fixing separate issues inside B.1 at the same time.
- **B.4 Isolation Rule:** While B.4 is active, do **not** run other B pipelines in parallel. Finish B.4 completely first.
- After B.4 is complete, fan out only across **disjoint pipelines** with clearly non-overlapping file sets.
- Each active pipeline still follows its own internal sequence: plan → micro-batches → review loop → docs → commit.
- Never let two agents edit the same file, same package hotspot, or same shared contract concurrently.

### Phase 2 Compile / Verification Protocol for Parallel Work
- When multiple Phase B pipelines run in parallel, **coding agents should not all run full compile/test tasks independently**, because Gradle daemons and Windows file locks will conflict.
- During parallel pipeline execution:
  - coding agents perform **static verification only** where possible:
    - read-back verification of changed files
    - import/signature checks
    - smallest-scope test/source updates
  - if compilation is required inside a batch, prefer the narrowest compile target and avoid overlapping long Gradle runs across agents
- Use a **single serialized verification lane** after code batches land locally:
  1. verify pipeline P1 changes
  2. verify pipeline P2 changes
  3. verify pipeline P3 changes
- The orchestrator owns the verification lane and schedules compile/test runs **one pipeline at a time**.
- Recommended verification order for parallel Phase B:
  1. merge/stabilize one pipeline’s micro-batches in working tree
  2. run `:app:compileDebugKotlin`
  3. run focused tests for that pipeline only
  4. send to reviewer
  5. move to next active pipeline
- If multiple pipelines are active in parallel, do **not** claim test success from coding agents unless the orchestrator’s serialized verification lane has produced actual evidence.

### Phase 2 Prompting Rules
- Planner/reviewer may discover cross-pipeline issues and classify them.
- Coding agents must remain inside the currently assigned pipeline and batch.
- If a discovered issue crosses into another pipeline, record it and defer it to that pipeline instead of fixing it opportunistically.

---

## 7. Quick Reference: File Paths

### Core Docs
- Registry: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- Plans: `docs/plans/`
- Batch Reports: `docs/analyses and debug master/final verification/`

### Key Code Locations
- DAOs: `app/src/main/java/com/yourname/expensetracker/data/database/dao/`
- Repositories: `app/src/main/java/com/yourname/expensetracker/data/repository/`
- Domain Engines: `app/src/main/java/com/yourname/expensetracker/domain/`
- UI/ViewModels: `app/src/main/java/com/yourname/expensetracker/ui/`
- Tests: `app/src/test/java/` and `app/src/androidTest/java/`

---

*Last Updated: 2026-04-08*
*Next Action: Begin A.3 — Non-deterministic Default Values*
