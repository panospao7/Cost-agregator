# Known/Suspected Hanging Test Ledger

Machine-readable authority: `config/validation/known-hanging-tests.json`.

## Policy

This ledger is routing evidence, not a skip list.

- An entry never adds `@Ignore`/`@Disabled`.
- Entries remain included in `unit-tests` and their normal named shard.
- `legacy-tests` runs ledger entries separately with tighter no-output
  detection so one suspected class cannot hide inside the full suite.
- `suspected` means historical evidence has not been reproduced on current
  HEAD. It must not be described as a current failure.
- `confirmed` requires a current run ID and log evidence.
- Resolving an entry means changing its status to `resolved` with a validating
  run ID; do not simply delete history.
- Every active entry needs an owner and review date. `unassigned` is visible
  debt, not approval to remain indefinitely.

## Current entries

The initial entries are historical suspects from `TEST_FAILURE_LEDGER.md`:

| ID | Filter | Status | Evidence |
|---|---|---|---|
| HANG-001 | `domain.receipt.lifecycle.*` | suspected | Historical F-02 |
| HANG-002 | `domain.transaction.lifecycle.*` | suspected | Historical F-02 |
| HANG-003 | `data.backup.ExportReadBarrierTest` | suspected | Historical F-16 |

The exact fully qualified filters, reason codes, owners, and review dates live
in the machine-readable ledger.

## Updating an entry

Record only bounded operational metadata:

- stable ID;
- fully qualified Gradle test filter;
- `suspected`, `confirmed`, or `resolved` status;
- `legacy` or `normal` routing;
- controlled reason code;
- source run ID or historical document reference;
- owner and review date.

Do not copy stack traces, exception messages, user payloads, or absolute paths
into the ledger.
