# Release 1.0 — user-facing notes (draft, maintained during the assurance campaign)

This file collects user-visible changes and known limitations introduced by the Release 1.0
assurance batches. Technical evidence lives in `release-1.0-phase3-4-findings-ledger.md` and
`workflows/active/release-1.0-assurance-handoff.md`.

## Behaviour changes

- **Exchange-rate downloads (R23).** Downloading daily rates from the European Central Bank now has
  its own switch in *Privacy settings > Network*, **off by default**. If you previously used Cloud AI
  (which used to allow rate downloads), turn this switch on to keep downloading rates. No expense data
  is sent; the ECB can see your device's IP address.
- **Backups (R23).** In-app backups do not include receipt images; the backup screen now says so.
- **Recurring bills (R21).** "Mark paid" now records the expense (dated on the bill's due date, in the
  bill's own currency) and links it to that bill; "Already recorded" closes the bill and its reminders
  without creating an expense. Future bills are kept scheduled 12 months ahead automatically, and
  overdue reminders collapse to one per bill.
- **Warranties (R29).** If saving a warranty's history entry fails, the whole save now fails visibly
  ("Couldn't save the change. Please try again.") instead of silently losing the warranty.
- **Backup and restore (R27).** Backup, restore, import and reset wait (up to 10 s) for saves already in
  progress, or cancel cleanly with a "timed out" reason. After a successful restore the app relaunches
  itself.
- **Notification auto-accept (R30).** If recording an auto-accepted notification expense fails, the
  notification is retried or marked failed instead of being reported as saved.
- **Privacy log and diagnostics (R26).** Repeated routine entries are summarised once per hour with a
  count.
- **Retention (R28).** The retention clean-up also deletes the encrypted copy of old captured
  notification text.
- **Notification access (R17).** If notification capture is on but Android has not given the app notification access, Home shows a card with "Manage access"; Privacy settings always shows the current access state.
- **Database safety (R25).** If the app finds a database it cannot safely open (from a much older or
  newer app version, or unreadable), it does not touch it: a dedicated screen lets you export a copy of
  the untouched file. Backups older than schema v145 or from a newer app are rejected with a specific
  message.
- **Unfinished features hidden (R19).** Tax configuration, investment portfolio and spending
  challenges are not part of 1.0 and are hidden from Home, the feature list and links; a saved screen
  pointing to them opens Home instead.

## Known limitations (1.0)

- About 60 smaller screens (budgets, categories, savings, groups, importers) are not awaited by the
  backup/restore write drain; they are short user-started saves (see
  `docs/backup-restore-barrier-contract.md`).
- A bank statement import that overlaps a backup/restore can leave that statement marked "parsed".
- Recurring bills created before 1.0 may keep old overdue planned entries; "Mark paid" only pays the
  bill on the date shown. Bills created before 1.0 default to EUR (the card shows the bill's currency).
- Paying a bill due on the 31st can move later due dates to the 28th/30th in some cases.
- In rare races an unlinked recurring expense may remain after "Mark paid" reports an error; delete it
  or use "Already recorded".
