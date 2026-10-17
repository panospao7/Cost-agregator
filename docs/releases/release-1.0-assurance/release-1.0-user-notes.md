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

- **Money totals (R20).** The subscriptions total and the financial-stress forecast no longer add up
  amounts in different currencies as if they were your home currency: amounts that can't be converted
  are left out and a "partial" warning is shown, or the total is shown as unavailable. The monthly
  savings suggestion now keeps upcoming bills inside the "Buffer" figure, so it no longer suggests
  moving money you need for bills.

- **Shared-group expenses (R18).** An expense that belongs to a shared group can no longer be deleted
  from the transaction screens, and its amount, currency, type and sharing can't be changed there
  (this would silently break the group's balances). Category, merchant, notes and date can still be
  edited.
- **Saving during backup or restore (R18).** Saves on screens such as savings goals, home, categories,
  recurring expenses, the spending map, warranties, the assistant and split templates now show
  "Changes are paused while a backup or restore is running" instead of crashing or failing silently.
  "Savings goal created" appears only after the goal is saved.

- **Analytics and export (R20).** Percentage changes now compare the same stretch of each period
  (for example 1-17 October with 1-17 September; year-to-date with the same part of last year); the
  "All" view shows no comparison. Exports include the chosen end day, a single-day export works, and
  dates no longer shift with your time zone. The forecast refreshes when your home currency or exchange
  rates change.
- **Dates (R20).** Date pickers (add/review expense, receipt scan, manual recurring) keep the day you
  picked in every time zone.
- **Bank-statement import (R22).** The statement importer (a developer/test tool in 1.0, not shown in
  release builds) now keeps credit transfers, direct debits, card payments and transfers to other banks,
  reads "+" amounts as income, no longer treats card purchases as income, reads US-format and ambiguous
  dates consistently, never creates two pending reviews for one row, and still skips summary lines.
- **Amounts with a decimal comma (R16).** Amounts like "12,50" now work in the planned-expense dialog,
  bill negotiation, the split editor, the currency calculator and the cash-flow starting balance.

- **Notification permission (R24).** Android 13+ asks for notification permission once per install and
  never again automatically; if Android will no longer show its dialog after a denial, the app offers
  "Open settings" once instead.
- **Receipts without a currency (R24).** If the currency can't be read from a scanned receipt, the review
  shows "We couldn't read the currency on this receipt. Check it before saving." and quick-save stays off
  until you confirm it; normal save still works. "Scan another" keeps your home currency selected.
- **Receipt review errors (R24).** Approval errors show short, specific messages instead of a generic
  failure or raw error text.

- **Home notices (R31).** The notification-access, currency and partial-data notices on Home are shown
  above the dashboard instead of being hidden behind it. The Safe-to-spend card no longer shows a false
  "could not be converted" warning; it asks you to set a budget only when you have none.

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
- After changing your home currency, sorting or filtering transactions by amount may use the previous
  currency's values for older entries; totals, the dashboard and analytics are unaffected (planned for 1.0.1).
- An expense that is part of a shared group can't be deleted or have its amount changed anywhere in the
  app: there is no way yet to remove a single expense from a group, and "Delete group" archives the
  group (its expenses stay linked). A "remove from group" option is planned after 1.0.
- Android 15 and later: after the phone restarts, the ongoing "Expense Tracker Active" notification
  appears only once the app is opened (Android no longer lets apps start it at boot). Notification
  capture itself keeps working.
- Split templates: if a template save is blocked by a running backup, the editor closes without a
  message and the template is not saved; save it again afterwards.
- The home screen's "vs last period" figure still compares with a block of the same length, so it can
  differ from the Analytics month percentage.
- Bank-statement import skips a real row whose description contains a summary word such as "limit"
  (for example an "OVER LIMIT FEE").
- Privacy settings (notification capture, cloud AI, exchange-rate downloads) is reached from the Features
  menu on Home (grid icon) and is the last entry in that list; it is not in the Settings menu yet.
- The Safe-to-spend amount is not calculated yet in 1.0; the card shows a placeholder.
- After a phone restart on Android 14+, captured notifications get no location until the app is opened once.
