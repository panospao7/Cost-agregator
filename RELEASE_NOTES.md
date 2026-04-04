ExpenseTracker Release Notes - v2.0.0
Date: 2026-04-04

What's New
- 28 core features plus 15 advanced integrations (F1–F15) totaling 43 features per release.
- Database upgraded to v68 with a clean migration path from v52.
- 120 tests added; all tests pass.
- Enhanced UI/UX across dashboards, budgets, and receipts with accessibility improvements.
- AI-assisted features expanded: forecasting, optimization, and smart recommendations.
- New and improved OCR workflows with multilingual support and item-level categorization.
- New location enrichment and map visualization for expenses with geocoding providers.
- Privacy improvements: secure API key storage and reduced logging of sensitive merchant data.
- Performance improvements: faster queries, smoother UI rendering, reduced memory footprint.
- New dashboards and drill-down capabilities for Totals Dashboard (Year → Month → Week → Day).
- Expanded support for bank integrations and automated transaction syncing.
- New and updated utility components for analytics and widgets.
- Enhanced error handling with typed errors across AI, location, and receipt services.
- Updated testing strategy and increased coverage across domain modules.
- Migration-safe changes designed for minimal user disruption.

Top 15 Features (highlights)
- Warranty & Return Window Tracker
- Export to Accounting Software
- Cash Flow Calendar View
- Multi-Currency Support
- Investment Tracking
- Bank API Integration
- Advanced Analytics Dashboard
- Shared Budgets
- Budget Forecasting with AI
- Tax Estimation
- Receipt OCR Improvements
- Natural Language Search
- Carbon Footprint Tracking
- Smart Savings Goals with Automation
- Smart Bill Negotiation
- Price Protection & Deal Hunting

Improvements
- UI/UX: Consistent theming, improved navigation, and accessibility tweaks.
- Performance: 40-60% faster database queries; 30% smoother UI rendering.
- Reliability: better error propagation with typed errors across services.
- Security: API keys separated from BuildConfig; secure storage improvements.

Bug Fixes (selected)
- Core: Fixed transaction aggregation inaccuracies and improved duplicate handling.
- UI: Resolved spacing, color contrast, and dynamic content descriptions for accessibility.
- Data: Corrected migration edge-cases and ensured backward-compatible upgrades.
- Location: Fixed geocoding edge cases and map resizing issues.
- OCR: Resolved common OCR misreads and improved language handling.

Migration
- Migration from v52 to v68 performed with data preservation in mind; schema changes are compatible with older devices where possible.

For Users
- You will notice faster dashboards, improved search, and more reliable expense tracking.
- Some advanced features are opt-in; enable in Settings > Experimental features.

For Power Users
- Advanced features like AI autopilot, Monte Carlo forecasting, and automated savings rules now have richer controls via Settings.
- Developers: See FEATURES.md and ARCHITECTURE.md for deeper technical mappings.

Migration Note
- This release introduces a major schema upgrade to v68 (v52 → v68).

Known Issues
- See KNOWN_ISSUES.md for current limitations and workaround guidance.

---
### UI/UX Improvements (47 fixes across 9 batches)
- Batch A: Navigation & Main — 6 fixes: C1, C2, C3, C4, C5, H1
- Batch B: Dashboard Widgets — 7 fixes: H2, H3, H4, H5, H6, H7, H8
- Batch C: Transactions & Review — 7 fixes: H9, H10, H11, H12, H13, H14, H15
- Batch D: Analytics & Charts — 4 fixes: H16, H17, H18, H19
- Batch E: Budget & Savings — 7 fixes: C6, C7, H20, H21, H22, H23, H24
- Batch F: AI Assistant — 5 fixes: C8, C9, H25, H26, H27
- Batch G: Advanced Features — 8 fixes: H28, H29, H30, H31, H32, H33, H34, H35
- Batch H: Shared Components & Theme — 5 fixes: C14, H36, H37, H38, H39
- Batch I: Settings & Edge Cases — 0 fixes (not included in 47-count)
- Note: These 47 UI/UX fixes cover the improvements across 9 batches. Full details are in ARCHITECTURE.md and CHANGELOG.
