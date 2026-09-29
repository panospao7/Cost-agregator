# CL-21 implementation spec — policy-owned cloud payloads and shared routing

Starting HEAD: a4807632. Current source was inspected after the CL-17/18 merges. Scope: CA-P08-002 and CA-P08-004. CA-P08-005 remains the later CL-22 guard/provider-test work item.

## GATE

Both findings were CONFIRMED at pre-implementation inspection against starting HEAD and the historical independent verification in verification-2026-09-22-wave2.md: the query provider bypassed policy-owned preparation, and HybridRouter had no production instantiations. Implementation and focused regression tests are now authored; see CL-21-self-review.md. Runtime validation, independent implementation review, and privacy guardian approval remain PENDING, not waived.

## Legal path and preflight

LEGAL_PATHS.md Privacy / Cloud AI requires the privacy gate, policy-owned prepared payload, and provenance audit. AiModule binds six hybrid wrappers: five simple dispatch wrappers and the specialized dedupe wrapper. ReceiptAssistService is bound to SmartReceiptAssistService, not HybridReceiptAssistService. These source targets had no pre-existing diff before this batch.

## Work items

### WI-1 — query provider payload boundary (CA-P08-002, P1)

- Inject CloudPayloadPolicy into CloudQueryInterpretationService, matching the other seven providers. Preserve the existing privacy gate and current reason-code/network retry behavior.
- Obtain the query prompt exclusively from prepareText(QUERY_INTERPRETATION, rawPrompt). Use that same prepared object for provenance and its text for the HTTP body. Remove direct redactor calls and hand-built PreparedCloudPayload from this provider.
- Keep test-only convenience constructors fail-closed. Adapt existing tests to the policy dependency; do not replace behavioral assertions with source markers.
- Add provider-boundary tests capturing the outbound request and audit provenance, plus blocked-gate, policy failure, and cancellation/no-request cases. Policy exceptions must not cause an unprepared request or expose exception text.

### WI-2 — wire only matching routing contracts (CA-P08-004, P2)

- Instantiate and delegate to HybridRouter in HybridCategorizationAssistService, HybridDashboardBriefingService, HybridReviewExplanationService, HybridQueryInterpretationService, and HybridReceiptItemCategorizationService. Preserve their capabilities, nullable/result types, fallback functions, constructor order, and fresh settings reads on each request.
- Leave HybridDedupeJudgeService specialized: it implements cross-route failover, which the simple shared router does not express. Do not silently remove failover or add it to other capabilities.
- Leave the unbound HybridReceiptAssistService and production-bound SmartReceiptAssistService unchanged; this batch does not alter receipt image/retry orchestration or DI bindings.
- Update HybridRouter's documentation only after real production callers exist. Explain the specific non-migrated contracts rather than claiming universal adoption.
- Test each bound migrated wrapper across CLOUD, ON_DEVICE, DISABLED, and deterministic fallback; test fresh settings and cancellation without invoking an alternate route.

## Additional issue adjudication

A2 (backup denial reason codes) and A5 (numeric amount versus phone redaction) require current-source/test verification during this privacy batch. Do not silently weaken privacy rules or reinterpret a capability denial as an operational gate failure. Record whether landed fixes already satisfy the established contract; if a genuine policy ambiguity remains, record it explicitly rather than invent human approval.

## Blast-radius fence

ALLOWED: the query cloud provider, five named bound hybrid wrappers, HybridRouter documentation, their targeted tests/constructor adaptations, and campaign records. Any A2/A5 source fix requires a separately recorded source-grounded contract decision before editing.

FORBIDDEN: global policy/redactor weakening, new outbound paths, changing specialized failover/image orchestration, DI route changes, guard exceptions/baselines, money changes, schema changes, or broad suite recovery. The specialized dedupe wrapper's existing exception handling is an observed follow-up risk, not permission for an unrelated rewrite.

## Validation and review

Human-run policy remains in effect. Prepare serialized targeted-unit-test commands for provider/delegation/policy/privacy contract classes and compile. The later CL-22 batch owns full static-guards enumeration and reporting of exposed violations. Author static self-review is not independent reviewer or guardian approval. No cluster closure until required evidence exists.
