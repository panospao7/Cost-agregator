# AI Phase 2 QA Checklist

Use this checklist to close out the Phase 2 assistant/query layer behind feature flags.

## Preconditions

- App builds successfully on the target branch.
- Database upgrade path includes `34 -> 35`.
- Test account has representative expenses across multiple periods, merchants, and categories.
- AI flags can be toggled for `ai_enabled`, `ai_assistant_enabled`, `ai_query_interpretation_enabled`, and `ai_store_conversation_history`.

## Manual Checks

- With AI disabled, open and close the assistant. Verify the app stays responsive and shows a disabled state instead of blocking navigation.
- Ask a supported question like `total this month`. Verify the numeric answer matches the equivalent deterministic screen data.
- Ask a drilldown question like `show groceries this month`. Open the result and verify the Transactions screen lands on the expected filtered list.
- Ask a comparison question like `compare this month to last month`. Verify the comparison text matches deterministic totals for the current and previous equivalent period.
- With conversation history off, submit multiple queries, reopen the assistant, and verify prior turns are not restored.
- With conversation history on, submit multiple queries, reopen the assistant, and verify the session persists and can be cleared.
- Verify assistant actions do not create, edit, approve, or delete `Expense`, `PendingReview`, budgets, or planned expenses.
- Smoke test notification capture, review approval, and receipt scanning to confirm Phase 2 did not regress non-assistant flows.

## Environment Notes

- `connectedDebugAndroidTest` packaging is healthy, but execution still requires `adb` plus a connected device or emulator.
- If instrumented execution is unavailable, record the missing environment dependency in release notes or the Phase 2 completion report.
