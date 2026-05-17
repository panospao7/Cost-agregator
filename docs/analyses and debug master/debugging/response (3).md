Yes, the **fix strategy should mostly remain the same**, but with one clarification:

**Do not fix strictly pipeline-by-pipeline.**  
Use the pipeline reports as **debug/discovery documents**, then fix by **shared architectural contract first**, and only then by individual pipeline issues.

## Best strategy

### 1. Keep pipeline reports as the checklist
They are still useful because they show user-visible breakage:

```text
notification → expense → dashboard
receipt → expense/link → analytics
recurring → reminder → actual payment
backup → restore → workers resume
```

But they should not always define PR boundaries.

### 2. Fix grand architectural contracts globally
Many “pipeline issues” are actually the same broken contract appearing in multiple places.

Examples:

| Architectural contract | Appears in pipelines |
|---|---|
| Restore/write barrier | 1,2,3,4,6,7,9,10,11,12 |
| Worker guard + run logging | 4,7,8,9 |
| Privacy/redaction/raw storage | 1,3,7,8,11,12 |
| Money/currency quality | 5,6,12, groups/investment/tax |
| Transaction lifecycle | 1,2,3,10,11,12 |
| Receipt lifecycle/link ownership | 3,9,11,12 |
| Recurring planned/actual reconciliation | 4,6 |
| Diagnostics/drop reasons/events | almost all pipelines |
| Import/export schema/roundtrip | 7,10,11,12 |
| DAO insert conflict/timestamps | 2,3,4,6,10,11 |

So if you fix “restore guard” only inside email receipts, you still leave the same bug in budget, bank, export, workers, etc.

## Is fixing by issue and pipeline viable?

**Yes, but only for truly local issues.**

Good pipeline/local fixes:

```text
notification textLines/messages extraction
Xero CSV escaping
email parser registry
BankConnectionsViewModel no-op
PDF mixed-currency label
receipt MIME fallback
```

Bad pipeline-only fixes:

```text
add restore guard only to ReceiptLinkService
fix currency only in dashboard trend
add diagnostics only to notification pipeline
handle duplicates only in email receipt import
```

Those should become global contracts.

## Recommended next order

1. **Global write/restore barrier**
2. **WorkerExecutionGuard + BackgroundJobRun**
3. **Diagnostics/outcome ledgers**
4. **Privacy policy unification + raw storage modes**
5. **Money/currency quality propagation**
6. **Transaction lifecycle bypass guards**
7. **Receipt lifecycle/link atomicity**
8. **Recurring planned/actual/reminder reconciliation**
9. Then pipeline-specific fixes.

So: **pipeline-by-pipeline debugging, contract-by-contract fixing, pipeline-by-pipeline verification.**