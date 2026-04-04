ExpenseTracker Known Issues and Workarounds

Current limitations by feature
- OCR Accuracy: multi-language receipts may occasionally misclassify line items. Workaround: review itemized lines in Receipt Item Breakdown UI; allow manual correction.
- AI Integrations (F1–F15): Beta features may show intermittent prompts or require server connectivity for cloud-based categorization.
- Location Enrichment: Geocoding may have limited accuracy in remote areas; offline maps are not fully supported.
- Totals Dashboard drill-down: some older devices may experience minor UI lag when navigating deep drill-downs.
- Bank API Integration: OAuth flows require user interaction; tokens may expire; ensure network connectivity for sync.
- Password and API key handling: All keys are stored in Secure Key Storage; regenerating keys may be necessary if migration issues are detected.
- Subscription and billing analyses rely on consistent transaction tagging; edge cases may miss some recurring charges.
- Export to Accounting Software: Some formats may require manual adjustments after export depending on the target system.
- Email Receipt Ingestion: Email ingestion service may lag during service outages or when inbox is extremely busy.
- Performance: Large data sets (months of data) may impact initial startup; recommended to run cleanup and archive yearly data.

Edge cases not yet handled
- Transactions with missing merchants or ambiguous dates
- Very large purchases with multiple currencies not fully converted in analytic summaries
- Quick toggling between locales with different decimal separators without reloading UI

Performance characteristics
- Expect 40-60% faster queries after indexing improvements; in some edge conditions performance may vary on older devices.
- Memory usage has been reduced; on constrained devices, some animations may still cause frame drops during heavy screens.

Unsupported scenarios
- Background-only screens without user interaction may not render certain widgets until the next foreground session.
- Some cloud-connected features require network availability; offline mode is partial.

Workarounds and mitigations
- For OCR issues, upload receipts again or correct misclassified items via ReceiptItemCategorization UI.
- If a migration seems stuck, re-run app upgrade with a fresh install and restore from backup if available.
- When bank sync fails, manual import of recent transactions is recommended.

Planned fixes (roadmap)
- Improve OCR accuracy with additional training data and fallback heuristics.
- Expand edge-case handling for unusual merchant names and multi-merchant invoices.
- Strengthen offline behavior and queue resilience for sync tasks.
- Enhance accessibility features and screen-reader support in more screens.

---
