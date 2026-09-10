# Worker Requirements Matrix

> **PRs 1–5 Complete** — Requirements declared for each worker, derived from reading actual source code at commit `241adee`.
> **PR6A Complete** — Guard result policy + lease stop gate (commit `89ee4ee`).

## Legend

| Field | Values | Meaning |
|---|---|---|
| `requiresDatabaseRead` | Yes / No | Worker performs SQL reads via DAOs or repositories |
| `requiresDatabaseWrite` | Yes / No | Worker performs SQL writes, inserts, updates, or deletes |
| `requiresNetwork` | Yes / No | Worker needs network access (either via constraint or at runtime) |
| `requiresNotificationPermission` | Yes / No | Worker posts notifications; guard checks `POST_NOTIFICATIONS` |
| `requiresCloudAllowed` | Yes / No | Worker calls cloud AI APIs; gated by privacy capability |
| `requiresPrivacyCapability` | List or `None` | Which `PrivacyCapability` values gate this worker at runtime |
| `blocksDuringMaintenance` | Yes / No | Worker MUST NOT run during restore/backup-export (write-barrier gated) |
| `allowDuringMaintenanceWithOwner` | String or `None` | Worker allowed to run as read-only during backup-export with this owner tag |
| `requiresForeground` | Yes / No | Worker uses `setForegroundAsync()` |
| `retryOnMaintenance` | Yes / No | Worker should be retried after maintenance exits (vs. skipped and re-scheduled) |
| `retryOnPermissionDenied` | Yes / No | Worker should be retried when notification permission is denied |
| `maxAttemptsPolicy` | Description | How many retry attempts before giving up |

---

## Matrix

### Registered Workers (in `WorkerRegistry`)

#### `DailyBriefingWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — dashboard data, AI artifacts |
| `requiresDatabaseWrite` | No (read-only pipeline; artifact writes are inside the use-case) |
| `requiresNetwork` | Yes — UNMETERED network constraint (for cloud AI) |
| `requiresNotificationPermission` | No (notifications sent via use-case, not direct) |
| `requiresCloudAllowed` | Yes — `PrivacyCapability.CLOUD_AI_DAILY_BRIEFING` |
| `requiresPrivacyCapability` | `CLOUD_AI_DAILY_BRIEFING` |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; midnight chain re-seeds on next success |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | Spec backoff |

#### `LocationBackfillWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — unlocated expenses |
| `requiresDatabaseWrite` | Yes — conditional location updates, backfill attempt increments |
| `requiresNetwork` | Yes — UNMETERED network constraint (Nominatim geocoding) |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | `BACKGROUND_LOCATION_BACKFILL` |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; next periodic schedule re-attempts |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | Per-expense: `MAX_ATTEMPTS` (in `getUnlocatedExpensesForBackfill`); worker-level: spec backoff |

#### `MerchantKeyBackfillWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — expenses with null merchantKey |
| `requiresDatabaseWrite` | Yes — `updateMerchantKey` |
| `requiresNetwork` | No — purely local computation (MerchantKeyGenerator) |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; re-scheduled on next app start / version bump |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | Spec backoff; per-run budget of 25 batches |

#### `DataRetentionWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — all retention targets (notifications, OCR, emails, chat, diagnostics) |
| `requiresDatabaseWrite` | Yes — purging (nulling fields), audit events |
| `requiresNetwork` | No |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; next periodic schedule re-attempts |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | Spec backoff; per-target checkpoint with resume |

#### `BillReminderWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — due reminders, settings |
| `requiresDatabaseWrite` | Yes — claim, mark sent/failed, cancel claim |
| `requiresNetwork` | No |
| `requiresNotificationPermission` | Yes — sends notifications via `NotificationManagerCompat` (catches `SecurityException` manually) |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; next periodic schedule re-attempts |
| `retryOnPermissionDenied` | No — currently handles via try/catch (not declarative `requiresNotificationPermission`) |
| `maxAttemptsPolicy` | Spec backoff |

#### `ReceiptMatchingWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — processable receipts, candidate transactions |
| `requiresDatabaseWrite` | Yes — link receipts, save suggestions, match events |
| `requiresNetwork` | No (matching is local; network not required by constraint) |
| `requiresNotificationPermission` | No — sends budget alert via `NotificationService` (indirect) |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; next periodic schedule re-attempts |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | Spec backoff |

#### `WarrantyExpirationWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — warranties, delivery rows |
| `requiresDatabaseWrite` | Yes — reconcile, seed, claim, mark sent/failed, prune |
| `requiresNetwork` | No |
| `requiresNotificationPermission` | Yes — declarative `requiresNotificationPermission=true` via guard |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | Yes — `allowDuringBackupExport=false` |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | No — skipped; next periodic schedule re-attempts |
| `retryOnPermissionDenied` | No — skipped (next periodic run re-checks) |
| `maxAttemptsPolicy` | Spec backoff; per-delivery claim protocol prevents duplicate sends |

---

### Non-Registered Workers

#### `NotificationIntakeWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — intake entity, raw notifications |
| `requiresDatabaseWrite` | Yes — mark terminal, purge, processAndSave |
| `requiresNetwork` | No (intake processing is local) |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None (gated by `NotificationFilter` at capture time) |
| `blocksDuringMaintenance` | Yes — full guard checks write barrier before first DAO read |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | Yes — guard returns `Result.retry()` when writes are blocked |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | `NotificationIntakeEntity.maxAttempts` (per-row), with custom backoff: 30s, 2m, 10m, 30m, 1h |
| `guardEntryPoint` | `runGuardedWithContext` (full guard since PR 5) |

#### `SourceLinkBackfillWorker`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | Yes — expenses, source links, pending reviews |
| `requiresDatabaseWrite` | Yes — `EntitySourceLink` inserts |
| `requiresNetwork` | No |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | Yes — calls `writeBarrier.checkWritesAllowed` at start |
| `allowDuringMaintenanceWithOwner` | None |
| `requiresForeground` | No |
| `retryOnMaintenance` | N/A — blocks until maintenance exits (synchronous call from ViewModel) |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | N/A — runs once per user invocation |

---

### BroadcastReceivers

#### `BootReceiver`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | No |
| `requiresDatabaseWrite` | No |
| `requiresNetwork` | No |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | No (not guarded) |
| `allowDuringMaintenanceWithOwner` | N/A |
| `requiresForeground` | No |
| `retryOnMaintenance` | N/A |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | N/A |

#### `ServiceRestartReceiver`

| Field | Value |
|---|---|
| Same as `BootReceiver` — no DB, no network, no guard. | |

#### `DismissReminderReceiver`

| Field | Value |
|---|---|
| `requiresDatabaseRead` | No (via coordinator — writes only) |
| `requiresDatabaseWrite` | Yes — `coordinator.dismissReminderDelivery` |
| `requiresNetwork` | No |
| `requiresNotificationPermission` | No |
| `requiresCloudAllowed` | No |
| `requiresPrivacyCapability` | None |
| `blocksDuringMaintenance` | No (not guarded) |
| `allowDuringMaintenanceWithOwner` | N/A |
| `requiresForeground` | No |
| `retryOnMaintenance` | N/A |
| `retryOnPermissionDenied` | N/A |
| `maxAttemptsPolicy` | N/A |

#### `SnoozeReminderReceiver`

| Field | Value |
|---|---|
| Same as `DismissReminderReceiver` — writes via `coordinator.snoozeReminderDelivery`. | |

---

## Summary: Privacy Capability Gates

| Worker | Privacy Capability |
|---|---|
| `DailyBriefingWorker` | `CLOUD_AI_DAILY_BRIEFING` |
| `LocationBackfillWorker` | `BACKGROUND_LOCATION_BACKFILL` |
| `MerchantKeyBackfillWorker` | None |
| `DataRetentionWorker` | None |
| `BillReminderWorker` | None |
| `ReceiptMatchingWorker` | None |
| `WarrantyExpirationWorker` | None |
| `NotificationIntakeWorker` | None (runtime filter only) |
| `SourceLinkBackfillWorker` | None |

## Summary: Notification Permission Requirement

| Worker | Declarative (`requiresNotificationPermission`) | Manual (try/catch) |
|---|---|---|
| `WarrantyExpirationWorker` | Yes | No |
| `BillReminderWorker` | No | Yes (catches `SecurityException`) |
| All others | No | No |

## Summary: Network Constraint

| Worker | Network Type |
|---|---|
| `location_backfill` | UNMETERED (Wi-Fi only) |
| `ai_daily_briefing` | UNMETERED + BatteryNotLow + Charging |
| All others | NOT_REQUIRED or no constraint |
