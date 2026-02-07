# ExpenseTracker - Architecture Document

## Project Overview

**ExpenseTracker** is a personal Android application that captures transaction notifications from multiple payment sources (Revolut, Google Pay, bank SMS, etc.) and aggregates them into a unified expense tracker with user-defined categories.

**Core Value Proposition**: One place to see ALL your expenses, categorized YOUR way, regardless of which bank or payment app you used.

---

## System Architecture

### High-Level Flow

```mermaid
graph TD
    A[Android Notifications] --> B[NotificationCaptureService]
    B --> C[RawNotification (DB)]
    C --> D[NotificationRepository]
    D --> E[AppParserRegistry]
    E --> F{Parsed?}
    F -- No --> G[Auto-Reject]
    F -- Yes --> H[MerchantNormalizer]
    H --> I[ConfidenceRouter]
    I --> J{Routing Decision}
    J -- High Confidence --> K[Create Expense]
    J -- Medium Confidence --> L[Pending Review Queue]
    J -- Low Confidence --> G
    L --> M[ReviewScreen]
    M -- Approve/Edit --> K
    M -- Reject --> G
    K --> N[UserCorrection (DB)]
    G --> N
```

### Layered Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ANDROID SYSTEM LAYER                               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │ Revolut  │ │ Ethniki  │ │ Google   │ │ SMS App  │ │  Other   │          │
│  │   App    │ │Bank App  │ │   Pay    │ │          │ │  Apps    │          │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘          │
│       │            │            │            │            │                  │
│       └────────────┴────────────┴─────┬──────┴────────────┘                  │
│                                       ▼                                      │
│                        ┌──────────────────────────┐                          │
│                        │   NotificationManager    │                          │
│                        └────────────┬─────────────┘                          │
└─────────────────────────────────────┼────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            EXPENSE TRACKER APP                               │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    SERVICE LAYER                                       │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │              NotificationCaptureService                          │  │  │
│  │  │         (extends NotificationListenerService)                    │  │  │
│  │  │  • Receives ALL notifications, extracts raw metadata              │  │  │
│  │  │  • Saves to raw_notifications (Persistent Deduplication)          │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                       │
│                                      ▼                                       │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    INTELLIGENCE LAYER                                  │  │
│  │  ┌─────────────────┐       ┌────────────────────┐                     │  │
│  │  │ AppParserRegistry│──────►│ MerchantNormalizer │                     │  │
│  │  └────────┬────────┘       └──────────┬─────────┘                     │  │
│  │           │                           │                               │  │
│  │           ▼                 ┌─────────▼──────────┐                    │  │
│  │  ┌─────────────────┐        │  ConfidenceRouter  │                    │  │
│  │  │ Categorization  │◄───────┤ (Decision Logic)   │                    │  │
│  │  │     Engine      │        └─────────┬──────────┘                    │  │
│  │  └─────────────────┘                  │                               │  │
│  └───────────────────────────────────────┼───────────────────────────────┘  │
│                                          │                                   │
│                                          ▼                                   │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    DATA LAYER                                          │  │
│  │  ┌─────────────────┐       ┌────────────────────────────────────────┐  │  │
│  │  │  Repository     │◄──────┤              Room Database             │  │  │
│  │  │                 │       │ ┌───────────────┐ ┌────────────────┐   │  │  │
│  │  │ Notification    │       │ │   Expenses    │ │ PendingReviews │   │  │  │
│  │  │ Repository      │       │ └───────────────┘ └────────────────┘   │  │  │
│  │  │                 │       │ ┌───────────────┐ ┌────────────────┐   │  │  │
│  │  └────────┬────────┘       │ │SourceStats    │ │UserCorrections │   │  │  │
│  │           │                │ └───────────────┘ └────────────────┘   │  │  │
│  │           │                └────────────────────────────────────────┘  │  │
│  └───────────┼───────────────────────────────────────────────────────────┘  │
│              │                                                               │
│              ▼                                                               │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    PRESENTATION LAYER                                  │  │
│  │  ┌─────────────────┐       ┌─────────────────────────────────────┐    │  │
│  │  │  MainScreen     │──────►│      Bottom Navigation Tabs         │    │  │
│  │  └───────┬───────┘         └─────┬──────────┬─────────┬──────────┘    │  │
│  │          │                       │          │         │               │  │
│  │          ▼                       ▼          ▼         ▼               │  │
│  │  ┌─────────────────┐   ┌────────────┐ ┌───────────┐ ┌───────────┐     │  │
│  │  │ ReviewViewModel │   │ HomeScreen │ │ReviewScreen│ │Transactions│     │  │
│  │  │ • approve/reject│◄──│ Dashboards │ │ Review    │ │ History     │     │  │
│  │  │ • handle edits  │   └────────────┘ │ Queue     │ └───────────┘     │  │
│  │  └─────────────────┘                  └───────────┘                   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Component Details

### 1. Intelligence Layer (New in Phase 1 Expansion)

#### ConfidenceRouter
- **Decision Matrix**: Determines the fate of a parsed transaction based on confidence scores and history.
    - **AUTO_ACCEPT (≥ 85%)**: High-confidence signal from trusted source/parser.
    - **NEEDS_REVIEW (50-84%)**: Ambiguous transctions (e.g., generic parser with weak signal).
    - **AUTO_REJECT (< 50%)**: Likely noise or non-transaction alerts.
- **Dynamic Scoring**: Adjusts base parser confidence using `SourceStats` (trust score) and `UserCorrection` (past behavior for specific merchants).

#### MerchantNormalizer
- **Cleaning**: Strips noise characters, store IDs, and card suffixes using Regex patterns.
- **Standardization**: Trims and capitalizes to improve matching accuracy.
- **User Preference**: Automatically swaps parsed names with user's preferred names based on historical corrections.

### 2. Data Layer

#### Entities
**1. RawNotification**
- Persistent store of system notifications for deduplication and traceback.

**2. Expense**
- Verified transactions ready for budgeting display.

**3. PendingReview**
- Staging area for medium-confidence transactions awaiting user approval.

**4. UserCorrection** ("The Learning Log")
- Logs every user action (approval, rejection, field edit) to train the intelligence layer.

**5. SourceStats** ("Trust Meter")
- Tracks performance of notification sources (e.g., "Google Pay" = 99% trusted, "Viber" = 20% trusted).

#### NotificationRepository
- **Orchestration**: Manages the process from raw notification capture to routing, storage, and user-led refinement.

### 3. Presentation Layer

#### ReviewScreen
- **Human-in-the-Loop**: Interactive swipe/card queue for resolving pending transactions.
- **Quick Actions**: Approve as-is or reject with one tap.
- **Inline Editing**: Fix merchant name or amount before final approval.

---

## Parser Engine (Modular Architecture)

The app uses a **3-tier parsing strategy**:

1.  **Specialized App Parsers**: Hardcoded logic for known apps (Revolut, Bank Apps, SMS).
2.  **Generic System Parser**: Fallback for unknown apps based on regex "Strong Signals".
3.  **Confidence Check**: All results must pass the `ConfidenceRouter` before becoming an `Expense`.

---

## File Structure

```
ExpenseTracker/
├── app/src/main/java/com/yourname/expensetracker/
│   ├── data/
│   │   ├── database/
│   │   │   ├── dao/  (RawNotification, Expense, PendingReview, UserCorrection, SourceStats)
│   │   │   └── entity/ (All entities)
│   │   └── repository/ (NotificationRepository, CategoryRepository)
│   ├── domain/
│   │   ├── categorization/ (CategorizationEngine)
│   │   ├── intelligence/ (ConfidenceRouter, MerchantNormalizer)
│   │   └── parser/ (Registry and specialized parsers)
│   ├── service/ (NotificationCaptureService)
│   └── ui/ (Screens for Home, Transactions, Review, Categories, Debug)
```

---

## Changelog

### 2026-02-07 - Phase 1 Expansion: Confidence-Based Review System
- **Intelligent Core**: Implemented `ConfidenceRouter` and `MerchantNormalizer`.
- **Review System**: Added `PendingReview` queue and `ReviewScreen` UI with badge notification.
- **Learning Loop**: Implemented `UserCorrection` tracking and `SourceStats` trust scoring.
- **Schema Update**: Bumped database to Version 5.
- **Navigation**: Added "Review" tab to main navigation.

### 2026-02-07 - Phase 5 & 6: Robust Engine & Full Dashboard
- **Tiered Parser Engine**: Modular registry with specialized parsers.
- **Rich Dashboard**: Redesigned Home Screen with spending statistics.
- **Fuzzy Matching**: Enhanced categorization accuracy.

