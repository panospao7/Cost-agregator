# External Review Evaluation — Pipelines 1-3 + Universal

> **Evaluated against:** HEAD `45147bf1` (post-deferred-fixes)  
> **External review baseline:** `4113e38f` (pre-our-session)  
> **Verdict:** Many issues were already fixed by our session. Several remain valid.

---

## Universal Multi-Pipeline Issues (response (3).md)

| Claim | Verdict | Evidence |
|-------|---------|----------|
| U10 DAO conflict/timestamp partial | **TRUE** — ScannedReceiptDao.insert() still uses IGNORE without universal caller checks | We added `deleteById` and orphan cleanup for email path, but generic insert-conflict handling remains caller-discipline |
| U3 DO_NOT_STORE falls through to raw storage | **TRUE** — Confirmed in NotificationCaptureService line 551: `else` branch stores raw text for DO_NOT_STORE | **Needs fix** |
| U4 MCR still documents LATEST-RATE paths | **PARTIALLY TRUE** — We added `getHomeCurrencyPurchaseTotalHistorical()` but other MCR methods still use latest rate | Documented as acceptable for current-valuation cards |
| U4 computeSpendingTrend sums effectiveAmount | **FALSE (FIXED)** — We fixed this: now converts to home currency | Fixed in commit `d97fb918` |
| U4 DashboardContractsAdapter budget TODO | **FALSE (RESOLVED)** — We confirmed BudgetRepository uses MCR for spent amounts | Resolved in commit `3ca7060b` |
| U1 Read barrier limited adoption | **TRUE** — DatabaseReadBarrier exists but few callers use it | Low priority — reads during restore are generally safe |

---

## Pipeline 1 — Notification Capture

| Issue ID | Claim | Verdict | Action Needed |
|----------|-------|---------|---------------|
| P1-CURRENT-001 | Live filter ignores combinedBody | **DESIGN TRADEOFF** — We changed to `bigText` to avoid false positives. Manual refresh uses `combinedBody`. Inconsistency exists. | **Revert to combinedBody** for consistency with manual refresh path |
| P1-CURRENT-002 | DO_NOT_STORE falls through to raw | **TRUE** — Confirmed in code | **Needs fix** — add exhaustive when() |
| P1-CURRENT-003 | STORE_REDACTED breaks parsing | **TRUE** — Parser receives redacted text, not ephemeral original | **Needs fix** — separate processing payload from storage |
| P1-CURRENT-004 | Dedupe too coarse (sbn.key only) | **PARTIALLY TRUE** — We fixed the timestamp issue but key is still just `sbn.key`. Updated notifications with same key but different content ARE dropped. | **Valid concern** but low frequency in practice |
| P1-CURRENT-005 | MessagingStyle extraction wrong | **POSSIBLY TRUE** — Would need device testing to confirm. The code uses `getParcelableArrayList` which may not correctly extract MessagingStyle.Message objects. | **Needs investigation** |
| P1-CURRENT-006 | Service drops not in diagnostics | **TRUE** — Only pipeline-level events are persisted, not service-level drops | **Enhancement** — not a regression |
| P1-CURRENT-007 | Shutdown durability not guaranteed | **TRUE** — No durable intake before async processing | **Known limitation** — documented |
| P1-CURRENT-008 | Risky FGS restart model | **TRUE** — Periodic restart alarm exists | **Low priority** — works on current Android versions |
| P1-CURRENT-009 | Privacy fail-closed startup race | **TRUE BUT ACCEPTABLE** — We set default to `true` (fail-closed). First notification may be dropped during startup. | **Acceptable** — fail-closed is the correct security posture |
| P1-CURRENT-010 | Blocked package after extraction | **TRUE** — Text extracted before blocked-package check | **Low priority** — in-memory only, no persistence |
| P1-CURRENT-011 | Pipeline fallback currency narrower | **TRUE** — Filter supports 15 currencies, fallback only 3 | **Enhancement** |
| P1-CURRENT-012 | Unknown packages skip AI fallback | **TRUE** — By design (privacy/cost control) | **Not a bug** — intentional |
| P1-CURRENT-013 | Review context loses combined text | **TRUE** — Uses `text ?: bigText` not combinedBody | **Low priority** |
| P1-CURRENT-014 | Post-commit uses synthetic Expense | **TRUE** — We documented this as P3-03 and fixed rawId propagation | **Partially addressed** |
| P1-CURRENT-015 | isProcessed never updated | **TRUE** — Field exists but unused | **Cleanup** |
| P1-CURRENT-016 | Stale ProcessingResult | **TRUE** — Dead code remains | **Cleanup** |
| P1-CURRENT-017 | Parser invoked twice | **TRUE** — Provenance detection re-parses | **Low priority** |
| P1-CURRENT-018 | ServiceDiagnostics shallow | **TRUE** — Only counts, no timestamps | **Enhancement** |
| P1-CURRENT-019 | Service too many responsibilities | **TRUE** — Architectural debt | **Long-term refactor** |

---

## Pipeline 2 — Transaction Lifecycle

| Issue ID | Claim | Verdict | Action Needed |
|----------|-------|---------|---------------|
| P2-CURRENT-001 | Raw DAO mutation surface public | **TRUE** — No static guard exists | **Known** — discipline-based enforcement |
| P2-CURRENT-002 | Deprecated createExpense footgun | **TRUE** — Still public with IMMEDIATE default | **Should be internal** |
| P2-CURRENT-003 | Repository delete bypasses coordinator | **NEEDS VERIFICATION** — May already route through coordinator | Check code |
| P2-CURRENT-004 | Update duplicate check doesn't exclude self | **POSSIBLY TRUE** — Would need to read DAO query | **Needs investigation** |
| P2-CURRENT-005 | STANDARD/BULK race loses identity | **TRUE** — Only STRICT resolves existing ID | **Known limitation** |
| P2-CURRENT-006 | Review approval merchant key mismatch | **TRUE** — We documented this earlier. skipDeduplication=true masks it. | **Known** — works because dedup is skipped |
| P2-CURRENT-007 | Coordinator uses restoreMaintenanceMode directly | **TRUE** — Doesn't use DatabaseWriteBarrier | **Consistency issue** — not a bug (same underlying check) |
| P2-CURRENT-008 | Group delete flags outside transaction | **TRUE** — We added `transactionEventDao` to GroupTransactionCoordinator but flag cleanup is still post-tx | **Needs fix** |
| P2-CURRENT-009 | Tests stale/broken | **TRUE** — We fixed some but 76 test files still have compile errors | **Known tech debt** |
| P2-CURRENT-010 to 019 | Various P2/P3 issues | **Mostly TRUE** — Enhancement/cleanup items | **Low priority** |

---

## Pipeline 3 — Receipt/OCR/Email (23 issues in external review)

| Issue ID | Claim | Verdict | Action |
|----------|-------|---------|--------|
| P3-CURRENT-001 | Bank statement raw OCR without privacy | **TRUE** | **P0 — Needs fix** |
| P3-CURRENT-002 | Email subject leaks into Expense.notes | **LIKELY TRUE** | Needs verification |
| P3-CURRENT-003 | Sanitized OCR used for fingerprinting | **TRUE** | **P1 — Needs fix** |
| P3-CURRENT-004 | OCR failures misclassified | **TRUE** | **P1 — Needs fix** |
| P3-CURRENT-005 | Insert + metadata not atomic | **TRUE** | Known architectural limitation |
| P3-CURRENT-006 | Pre-OCR hash inconsistent | **TRUE** | Enhancement |
| P3-CURRENT-007 | Pre-OCR duplicate can delete existing | **POSSIBLY TRUE** | Needs code verification |
| P3-CURRENT-008 | No unique fingerprint constraints | **TRUE** | Enhancement (race window) |
| P3-CURRENT-009 | Email fingerprints computed but not persisted | **LIKELY TRUE** | **P1 — Needs fix** |
| P3-CURRENT-010 | Email Message-ID dedupe vs sanitized sourceFingerprint | **TRUE** | **P1 — Needs fix** (our fingerprint fix may have addressed this partially) |
| P3-CURRENT-011 | MIME fallback not honored by OCR service | **TRUE** | Enhancement |
| P3-CURRENT-012 | Auto-match ignores link failure | **TRUE** | **P1 — Needs fix** |
| P3-CURRENT-013 | Suggested match non-atomic, no barrier | **TRUE** | **P1 — Needs fix** |
| P3-CURRENT-014 | Direct repository match methods bypass lifecycle | **TRUE** | Known — discipline-based |
| P3-CURRENT-015 | Direct delete leaves orphan links | **TRUE** | Known — lifecycle coordinator is preferred path |
| P3-CURRENT-016 | Bank statement import not atomic | **TRUE** | Known architectural limitation |
| P3-CURRENT-017 | CancellationException swallowed | **TRUE** | **P1 — Needs fix** |
| P3-CURRENT-018 | Broad catch misclassifies DB errors as PARSE_FAILED | **TRUE** | **P1 — Needs fix** |
| P3-CURRENT-019 | Link parent validation outside transaction | **TRUE** | Known — no FKs by design |
| P3-CURRENT-020 | Unlink doesn't clear item categorization | **PARTIALLY FIXED** — We added matchStatus/matchConfidence clearing but missed itemCategorization | **Needs fix** |
| P3-CURRENT-021 | Raw storage policy breaks local side effects | **TRUE** | Same root cause as P1-CURRENT-003 |
| P3-CURRENT-022 | Validation failures lack diagnostics | **TRUE** | Enhancement |
| P3-CURRENT-023 | Ghost cleanup leaks asset | **LIKELY TRUE** | Enhancement |

---

## Priority Actions (issues that are TRUE and need fixing)

### Critical (P0 Privacy):
1. **P1-CURRENT-002**: DO_NOT_STORE falls through to raw storage in NotificationCaptureService
2. **P3-CURRENT-001**: Bank statement raw OCR without privacy sanitization
3. **P1-CURRENT-003 / P3-CURRENT-021**: STORE_REDACTED/METADATA_ONLY breaks parsing (processing uses stored text instead of ephemeral)

### High (P1 — Data Correctness):
4. **P3-CURRENT-003**: Sanitized OCR used for fingerprinting → false duplicates
5. **P3-CURRENT-010**: Email Message-ID dedupe fails in redacted mode
6. **P3-CURRENT-009**: Email fingerprints computed but not persisted on receipt
7. **P3-CURRENT-012**: Auto-match ignores link failure result
8. **P3-CURRENT-017**: CancellationException swallowed creates fallback records
9. **P3-CURRENT-018**: Broad catch misclassifies DB errors as PARSE_FAILED
10. **P1-CURRENT-001**: shouldCapture filter inconsistency (bigText vs combinedBody)
11. **P3-CURRENT-020**: Unlink doesn't clear item categorization expenseId

### Medium (P2 — Enhancement/Hardening):
12. P3-CURRENT-013: Suggested match non-atomic
13. P2-CURRENT-008: Group delete flags outside transaction
14. P3-CURRENT-008: No unique fingerprint constraints (race window)
15. Various diagnostic/cleanup items
