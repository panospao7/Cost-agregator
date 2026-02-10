# 📂 ExpenseTracker: Master Application & UI Specification

A definitive architectural and visual blueprint of the **ExpenseTracker** ecosystem. This document is optimized for both developers and AI agents to understand the app's mission, logic, and Design System.

---

## 1. 🛡️ Data Privacy & Architecture (The Trust Foundation)

**ExpenseTracker** is built on a **Zero-Cloud, Local-Only** architecture. This is a critical technical and user-facing feature.

- **Storage**: 100% on-device SQLite (Room) Database.
- **Network Dependency**: Zero. The app never uploads transactions, notification content, or scanned receipts to external servers.
- **Data Subject**: The user has full sovereign control over their historical data.

---

## 2. 🎯 Application Mission: "Autonomous Financial Co-Pilot"
The app eliminates manual data entry by "listening" to financial signals.

- **UX Paradigm**: "Inbox Zero" Strategy. Open the app to verify automated work, not to perform manual logging.
- **Value**: Capture 95% of transactions silently; only require human touch for ambiguous signals.

---

## 3. 🏗️ Design System & Visual Identity
*Strict adherence to these tokens ensures a premium, professional fintech experience.*

### A. Color Palette (Midnight Theme)
- **Base Theme**: Midnight Navy (#0F172A) or Charcoal (#121212). 
- **Action/Primary**: Electric Indigo (#6366F1).
- **Positive/Income**: Emerald Teal (#10B981).
- **Warning**: Burnt Orange (#F97316).
- **Critical/Danger**: Radical Red (#EF4444).
- **Visual Style**: Bento Grid modules with subtle Glassmorphism (SurfaceVariant @ 40% alpha).

### B. Typography & Numerical Precision
- **Data Typeface**: **Tabular Lining Figures** (Monospaced Numerals) are mandatory for all currency displays.
- **Fonts**: Inter or Roboto Flex.

---

## 4. ⚙️ Technical Operational Logic

### A. Background Capture & Deduplication
- **The Service**: `NotificationCaptureService` runs as a foreground service (`DATA_SYNC` type).
- **Smart Deduplication**: A thread-safe, 5-second window prevents duplicate entries when a transaction triggers both an App Notification and an SMS.
- **Monitored Sources**: Revolut, Google Wallet, Bank Apps (NBG, Alpha, Eurobank, Piraeus), and specific SMS gateways.

### B. The Intelligence Engine
- **Confidence Routing**: 3-tiered logic (Accept ≥ 85%, Review 50-84%, Reject < 50%).
- **Categorization Strategy**: Hits a 3-tier lookup:
    1.  **Exact Match**: Known merchant name.
    2.  **Longest Substring**: Matches "UBER" in "UBER EATS".
    3.  **Word-Level Match**: Splits name into words and matches 4+ character tokens.
- **Normalizer**: Forensic regex cleaning (removes date/time stamps, POS IDs, and Greeklish noise).

---

## 5. 📱 Page-by-Page Requirements

### A. 🏠 Home (Dashboard)
- **Purpose**: At-a-glance financial status.
- **Components**: Safe-to-Spend bar, Spending Pace arc gauge, Active Service PulseDot.
- **Operations**: Reactive UI updates; one-tap transition to Review Inbox.

### B. 📥 Review (Inbox)
- **Purpose**: Rapid classification of ambiguous transactions.
- **UI**: Vertical card stream with high-contrast Action buttons.
- **Logic**: Compare Raw Text vs. Parsed Suggestion. Swipe to Approve/Reject.

### C. 📈 Plan (Budget & Analytics)
- **Purpose**: Forecasting and behavioral analysis.
- **Visuals**: Line charts with "Shadow Comparison" (vs. last month).
- **Budgets**: Supports monthly limits with **Rollover** and notification thresholds (75% / 90%).

### D. 🧾 Receipt Center
- **Purpose**: Manual digitization of physical paper.
- **Logic**: ML-Kit OCR extraction with confidence-based field mapping.
- **Storage**: Stores Image Path, Raw OCR text, and parsed line items.

---

## 6. 🛠️ State Management & UI Robustness
- **Empty States**: Encouraging "All Caught Up" visuals for the Review queue.
- **Permission States**: Clear guidance for Notification Access—without this, the app is silent.
- **Error Handling**: Non-intrusive snackbars for OCR failures or invalid manual amounts.

---
*Generated as a master reference for a "Perfect" UI design prompt.*
