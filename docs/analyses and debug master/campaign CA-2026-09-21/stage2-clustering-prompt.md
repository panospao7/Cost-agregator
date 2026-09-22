# STAGE 2 — ROOT-CAUSE CLUSTERING PROMPT (CA-2026-09-21)

Run in a fresh codex session on `gpt-6-astra`, reasoning effort `high`. Paste everything below the line.

---

## 0. Pin verify (execute first, stop on failure)

Working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker`.

```
git rev-parse --short HEAD                                  # must print 37601232
git diff --stat 37601232..HEAD -- app config scripts        # must print nothing
```

If either check fails, STOP and report the mismatch in ≤5 lines. Do not proceed on a moved tree.

## 1. Role

You are the Stage-2 clusterer for audit campaign CA-2026-09-21. Twenty-two cell audits produced 89
findings, consolidated into a findings ledger. Your job is to turn that ledger into a **cluster map**:
groups of findings that share a root cause and are fixable by ONE coherent change — i.e., groups that
would form one sensible PR. This is a consolidation and planning task, NOT a re-audit and NOT
implementation. You write no fix code.

## 2. Inputs (in read order)

1. `docs/analyses and debug master/campaign CA-2026-09-21/findings-ledger.md` — THE primary input.
   89 rows + a mechanical cross-reference section (file citation frequency + 8 pattern candidates
   marked INDICATIVE — they are seeds, not verdicts; you may confirm, split, or dissolve each).
2. `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md` — read ONLY §2 (known-debt
   register: D1–D8, RP-21 test debt, NEW-P1-011). Findings that restate known debt are NOT coder
   work; they go to the decision register.
3. Cell reports `cell-{P,E,I}-*-audit.md` in the same campaign directory — drill down ONLY for
   specific finding IDs whose mechanism or fix shape is ambiguous in the ledger row. Read the
   finding's section, never the whole report.
4. `docs/architecture/LEGAL_PATHS.md`, `docs/architecture/ENGINE_INTERACTION_MAP.md`,
   `docs/architecture/CODEBASE_SEGMENTS.md` — read ONLY the sections relevant to clusters being
   formed (e.g., expense-mutations section when clustering lifecycle-coordinator findings). Do NOT
   preload whole documents.

## 3. Hard rules

- Source tree is READ-ONLY. The only files you may create/modify: `cluster-map.md` (the artifact)
  and one appended line in `JOURNAL.md`.
- Cluster by **mechanism and fix shape** — never by cell, never by severity alone, never by file
  proximity alone.
- Every cluster must pass the **PR test**: "would one competent PR review unit fix all members?"
  If no, split it.
- Hard isolation boundaries (never crossed inside one cluster): money/currency math · Room
  schema/migrations · everything else. A money cluster contains only money findings; a schema
  cluster only schema findings.
- Do not hunt for new findings. If you notice something off-ledger, add one line to a
  `## Non-ledger observations` appendix and move on. Do not develop it.
- Do not verify or refute findings — verification is a separate track. Carry each finding's
  verification status (only P-01 is verified; P-07's four P0s and E-04's two P0s are unverified).
- Never narrate unearned completion. The artifact on disk is the deliverable, not your chat output.
- Ground every placement recommendation in real code at the pin: grep the exact
  coordinator/service/helper name before recommending it; check whether a shared helper already
  exists before recommending a new one. Invented APIs are a campaign-grade failure.

## 4. Method (in order)

1. Pin verify (§0).
2. Read the ledger fully. Skim-derive a first grouping from rows alone; mark ambiguous IDs.
3. Read the known-debt register (§2 of the master prompt); tag ledger rows that collide.
4. Drill down (step 3 of inputs) for ambiguous IDs only.
5. For SYSTEMIC cluster candidates (see §5), ground the placement: confirm the owning
   coordinator/service exists, check for an existing shared helper (e.g., a sanitized-logging
   utility for the exception-leakage cluster). Bounded: ≤10 targeted greps total.
6. Create `cluster-map.md` early with its header, then append clusters as they settle
   (report-first; never hold the whole artifact in your head).
7. Completeness audit (§7), decision register, journal line, return.

## 5. Cluster schema (exact fields, per cluster)

```
### CL-NN — <short name>
- Class: LOCAL | SYSTEMIC | DEBT-COLLISION | DOCS-ONLY
- Severity: <max member severity>
- Findings (<n>): <IDs>
- Root cause: 1–3 sentences synthesizing the shared mechanism.
- Fix shape: the one coherent change that fixes all members, 2–4 sentences.
- Placement: owning layer/coordinator/service. For SYSTEMIC: your recommendation, the
  alternative you rejected, and the code evidence (file:symbol at pin) grounding it.
- Files (union): <deduped basenames>
- Blast radius: money-currency | room-schema | worker | privacy | ui | ci-guard | docs | none
- Dependencies: lands before/after CL-xx because <reason> (acyclic; break and document any cycle)
- Verification gate: which member findings are UNVERIFIED and must be revalidated before this
  cluster's wave starts (mandatory listing for any cluster containing a P0/P1)
- Review gate: reviewer-strict | privacy-security-guardian | room-migration-guardian |
  architecture-guardian
- Complexity: S | M | L
- Suggested wave: 1 | 2 | 3 | 4  (rules in §6)
```

Expect roughly 15–30 clusters from 89 findings. Every ledger ID appears in exactly one cluster or
in the explicit UNCLUSTERED list with a one-line reason. DOCS-ONLY findings (P3 doc drift) batch
into one or two clusters. DEBT-COLLISION rows become decision-register items, not clusters with
fix shapes.

## 6. Wave assignment rules

- **Wave 1 — P0/urgent**: clusters containing any P0, or urgent privacy leakage. Each lands as a
  small independent PR.
- **Wave 2 — systemic sweeps**: SYSTEMIC clusters (invariant placement + multi-site sweeps). One
  PR per sweep even at N call sites.
- **Wave 3 — P1 correctness**: grouped so parallel worktree lanes don't collide (disjoint segments).
- **Wave 4 — P2/P3 batches + docs**: mechanical, cheapest to implement.
- Cluster severity = max member severity. Dependency edges override severity ordering only when
  a Wave-2 systemic fix changes a contract that Wave-1/3 fixes depend on — if so, say so
  explicitly in both clusters' Dependencies fields.

## 7. Completeness audit (append at the end of the artifact)

- [ ] 89 IDs accounted for: clustered <N> + unclustered <M>, N+M = 89 (state the numbers)
- [ ] Each of the ledger's 8 pattern candidates explicitly CONFIRMED / SPLIT / DISSOLVED, one line each
- [ ] Known-debt collisions listed against D1–D8 / RP-21 / NEW-P1-011
- [ ] Dependency graph acyclic
- [ ] Every cluster passes the PR test (one line each if non-trivial)
- [ ] Verification gates marked on all P0/P1 clusters

## 8. Artifact skeleton (`cluster-map.md`)

```
# CA-2026-09-21 — CLUSTER MAP (Stage 2)
Provenance: <date>; pin 37601232 verified (<the two commands' results>); direct astra-high session.
Totals: <n> clusters — <counts by class>; <n> findings clustered, <n> unclustered, 89 accounted.

## Wave map
Wave 1: CL-xx, ...   Wave 2: CL-xx, ...   Wave 3: ...   Wave 4: ...

## Clusters
<CL-NN blocks per §5>

## Decision register (human required)
<debt collisions: finding IDs × which D/RP decision × the accept/reopen question>
<systemic placement decisions needing approval: cluster × recommendation × alternative>

## Unclustered findings
<ID — one-line reason>

## Non-ledger observations
<one-liners, or "none">

## Completeness audit
<§7 checklist, filled>
```

Path: `docs/analyses and debug master/campaign CA-2026-09-21/cluster-map.md`.

## 9. Context discipline

- Ledger-first: the ledger + this prompt must fit comfortably; drill-downs are exceptions.
- ≤20 full-file reads beyond the ledger; architecture docs by section only.
- If your context exceeds ~150K tokens: flush the current cluster block to `cluster-map.md`
  immediately, then continue.
- After any compaction, re-anchor: re-read this prompt's §5 schema and your own `cluster-map.md`
  so far before continuing.
- PARTIAL-cell note: 9 cells are coverage-partial (P-02, P-03, P-05, P-07, P-10, E-01, E-02,
  E-05, I-04). Clustering is unaffected, but flag any cluster whose members come ONLY from
  partial cells with `coverage-confidence: partial` in its block.

## 10. Return contract (final chat output, ≤20 lines)

Cluster count by class · wave counts · top 3 systemic clusters by risk · decision-register item
count · completeness result (N+M=89?) · artifact path · confirmation the journal line was written.
Nothing else.

## 11. Journal (mandatory, append one line to `JOURNAL.md`)

```
2026-09-22T<HH:MM>+03:00 | stage2-clusterer (direct session) | STAGE-2-CLUSTER-MAP | <n> clusters (<counts by class>); 89 findings accounted (<n> unclustered); <n> decision-register items | docs/analyses and debug master/campaign CA-2026-09-21/cluster-map.md
```
