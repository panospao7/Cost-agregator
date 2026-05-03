# Validate MAJOR Issues — Forecast + Shared Subsystems

> Re-verified: 2026-05-03 | Source: `REMAINING-ISSUES-PLAN.md` | Verdict key: CONFIRMED / ALREADY FIXED / WRONG SEVERITY

## Summary Table

| Issue | Subsystem | Verdict |
|-------|-----------|---------|
| FCST‑5: Stress forecast balance starts at 0 | Forecast | **CONFIRMED** |
| SHR‑7: Hard delete orphans expenses | Shared | **CONFIRMED** |

## Evidence

### FCST‑5 — CONFIRMED

- **File:** `domain/forecasting/FinancialStressForecastEngine.kt`
- **Lines:** 587–593
- **Code:**

  ```kotlin
  /**
   * Forecasting has no canonical account-balance source in this pipeline.
   * Use a neutral baseline instead of fabricating a balance from cashflow.
   */
  private fun resolveStartingBalanceBaseline(): Double {
      return 0.0
  }
  ```

- **Assessment:** Still hardcoded `0.0`. KDoc acknowledges the limitation (no account-balance source). The plan's downgrade from CRITICAL to MAJOR is **correct** — the limitation is documented and the method is clearly named as a baseline. No uncommitted changes touch this file (`git diff` shows only debug UI / strings.xml modifications).

### SHR‑7 — CONFIRMED

- **File:** `data/database/GroupTransactionCoordinator.kt`
- **Lines:** 613–635
- **Code:**

  ```kotlin
  /**
   * Atomic group deletion with cleanup.
   * Removes all associated members and group expenses.
   *
   * J2: This performs a HARD delete — linked system expenses are NOT removed.
   * After this operation, those expenses lose their group association metadata
   * (isSharedExpense, myShareAmount, etc.) and become semantically orphaned.
   * Prefer [deleteGroup] (soft archive via isActive = false) whenever possible
   * to preserve referential integrity.
   */
  suspend fun deleteGroupAtomic(groupId: Long) {
      database.withTransaction {
          groupExpenseDao.deleteAllForGroup(groupId)
          memberDao.deleteAllForGroup(groupId)
          val group = groupDao.getGroupById(groupId)
          group?.let { groupDao.delete(it) }
      }
  }
  ```

- **Assessment:** Hard delete still does **not** call `unlinkSystemExpensesForGroup()` or clear `isSharedExpense`/`myShareAmount` flags on orphaned Expense rows. KDoc explicitly warns about this and recommends `deleteGroup` (soft archive) instead. The plan's downgrade from CRITICAL to MAJOR is **correct** — a documented soft-archive alternative exists and is the recommended path. No uncommitted changes touch this file.

## Metadata

- **Plan MAJOR count (Forecast + Shared):** 2
- **Confirmed:** 2
- **Already fixed in uncommitted changes:** 0
- **Wrong severity (should be CRITICAL/MINOR):** 0
- **Files checked:** `FinancialStressForecastEngine.kt`, `GroupTransactionCoordinator.kt`
- **Uncommitted changes reviewed:** `git diff` — debug UI and strings.xml only; no impact on these issues
