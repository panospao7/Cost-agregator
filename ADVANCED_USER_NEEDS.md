# Deep Dive: Advanced User Needs & Missing Features

While ExpenseTracker excels at automated entry and trajectory forecasting, real-world finances are rarely clean. A deep, critical look from the perspective of an advanced daily user reveals several "messy" financial realities that the current architecture struggles to handle natively.

Addressing these paradigms would transform the app from a "tracker" into an indispensable "Wealth Manager."

---

## 1. 💳 The Installment & BNPL Reality (Buy Now, Pay Later)
**The Problem**: A user buys a €1,200 MacBook via Klarna or a credit card with 12 monthly installments. 
*   If they log it as a single €1,200 expense today, their "Tech" budget and runway are completely destroyed for the month, triggering false critical alerts.
*   If they log it manually as a €100 recurring expense, they lose the context of the original €1,200 receipt and total debt owed.
**The Missing Feature (Debt Amortization)**: 
*   A new `InstallmentPurchase` entity. The user scans the €1,200 receipt, selects "Paid in Installments," and inputs "12 months."
*   The `SynthesisEngine` automatically adds a €100 `PlannedExpense` to the first of every month for the next year, leaving today's discretionary budget intact while accurately projecting future committed spend.

## 2. 🍕 "Owe Me" Tracking (Micro-Reimbursements)
**The Problem**: A user pays €100 for a group dinner. Three friends instantly transfer them €25 each on Revolut. 
*   Currently, the ML tags the €100 as "Dining Out". Then, three €25 deposits hit the app. The user's "Dining Out" budget looks like they spent €100, and their "Income" looks like they made €75. Both metrics are fundamentally skewed.
**The Missing Feature (Bill Splitting / Tags)**:
*   The ability to mark the €100 expense as "Split".
*   When the €25 Revolut notifications arrive, the ML (or the user via swipe) links them to the parent expense. The dashboard then accurately reflects that the user only spent **€25** on Dining Out.

## 3. 🏦 The "Balance Sheet" (Assets vs. Cash Flow)
**The Problem**: ExpenseTracker is essentially a P&L (Profit & Loss) statement. It knows how much you spend and what your constraints are, but it doesn't know *how much money you actually have*.
**The Missing Feature (Net Worth / Wallets)**:
*   Users need to define "Accounts" (e.g., Checking €2,000, Savings €10,000, Wallet €50).
*   A new "Net Worth" or "Accounts" tab.
*   **Crucial Benefit**: This enables "Transfers" to finally make sense. Moving €500 from Checking to Savings shouldn't trigger spending alerts; it should just update account balances while tracking toward the `SavingsGoal` entity.

## 4. 🌍 Travel Mode & Multi-Currency Context
**The Problem**: A user from Greece (EUR base) travels to London. The app captures a GBP £40 notification. Currently, the parser extracts "40" and tags it as GBP, but does it intelligently deduct it from the EUR Euro discretionary budget?
**The Missing Feature (Exchange Rate Resolution)**:
*   A dedicated "Travel Trip" tag.
*   Background conversion mapping. When £40 is spent, the app fetches (or uses a cached) exchange rate to log the *original* receipt as £40, but hits the budget tracking engines as ~€47.

## 5. ✉️ Envelope Budgeting (Strict Zero-Based)
**The Problem**: The current budget system is reactive (Limits & Pacing). It answers: *"Am I spending too fast?"* 
Advanced budgeters (like YNAB users) prefer proactive budgeting: *"I just got paid €2,000. Give every euro a job."*
**The Missing Feature (Cash Stuffing)**:
*   Instead of just setting a €400 limit on Groceries, allow users to "Fund" the Groceries envelope with €400 from their actual Income. If the envelope empties, they cannot spend more unless they manually move funds from the "Entertainment" envelope.

## 6. 🧾 Magical Receipt Value (Warranty & Returns)
**The Problem**: Scanning a receipt just to extract the Total and Merchant is underutilizing ML Kit.
**The Missing Feature (Smart Document Context)**:
*   Extend `ReceiptParser.kt` to look for keywords like "Επιστροφές εντός 30 ημερών" (Returns within 30 days) or "2 Year Warranty."
*   The app extracts this and creates an actionable calendar notification: *"Your 30-day return window for IKEA ends tomorrow."* This adds immense utility beyond basic accounting.

## 7. 📈 Subscription Price Creep Auditing
**The Problem**: Inflation and silent price hikes. Netflix goes from €13.99 to €15.99. The user might miss the email.
**The Missing Feature (Pattern Anomaly Detection)**:
*   The `RecurringExpenseEngine` already knows the historical patterns.
*   If a `MATCHED` recurring transaction comes in at a higher amount than the stored `averageAmount`, the app flags it with a bright red UI alert: *"Subscription Increased: Netflix charged you €2.00 more this month."* 

---
### Summary
ExpenseTracker is incredibly smart at **data capture**, but it currently assumes all expenses are simple, final, immediate, and single-currency. By implementing Debt Amortization, Reimbursement Linking, and Account Balances, it would elevate from a tracking tool to a comprehensive financial command center.
