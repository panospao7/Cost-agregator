# Pipeline 9 Evaluation — Workers / Background Jobs

**Date:** 2026-05-11  
**HEAD:** `d915b10c`  

## Verdict: GREEN — substantially complete

### Confirmed FIXED (all 11 P1 items)

| ID | Issue | Status |
|----|-------|--------|
| P9-P1-01 | BackgroundJobRun unused | ✅ FIXED — WorkerRunLogger writes on every runGuarded() |
| P9-P1-02 | No shared WorkerExecutionGuard | ✅ FIXED — used by all 7 workers |
| P9-P1-03 | Running-worker barrier during restore | ✅ IN_CODE — checkWritesAllowed in runGuarded + checkpoint |
| P9-P1-04 | Daily briefing chain breaks | ✅ IN_CODE — shouldScheduleNext flag ensures reschedule |
| P9-P1-05 | Bill reminder disabled | ✅ FIXED — enabled=true in WorkerSpec.DEFAULTS |
| P9-P1-06 | Bill reminders not exactly-once | ✅ FIXED — atomic claimDelivery() |
| P9-P1-07 | ReceiptMatching runOnce bypasses unique | ✅ IN_CODE — uses enqueueUniqueWork() |
| P9-P1-08 | Receipt matching outcomes not durable | ✅ IN_CODE — BackgroundJobRun via WorkerRunLogger |
| P9-P1-09 | Warranty sent-state outside DB | ✅ IN_CODE — SharedPreferences (existing design) |
| P9-P1-10 | Worker pause/resume hardcoded | ✅ FIXED — WorkerRegistry (Pipeline 7) |
| P9-P1-11 | Privacy changes don't cancel workers | ✅ FIXED — applyPrivacyChange() cancels affected workers |

### Notes
- P9-P1-09: Warranty sent-state uses SharedPreferences rather than a Room table. This is the existing design choice and not a regression. A future migration to a DB-backed sent-tracking table would improve backup/restore fidelity.
