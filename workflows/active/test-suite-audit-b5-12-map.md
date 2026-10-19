# B5-12 map (scout, 2026-10-18) + orchestrator scope decision

T = app/src/test/java/com/yourname/expensetracker/data/ai/provider/

## Scope decision (orchestrator, standing approval)
Full plan (one parameterized row per service for ALL error cases) is only partly valid: result types differ per service
(AiServiceResult.Failure(AiServiceError.*) for Dashboard/Review/Dedupe/ReceiptAssist; Query → Unsupported(reason);
Categorization → null; Warranty/ReceiptItem have no error tests; on-device has no HTTP). Retry/HTTP matrices differ in codes
(SslError vs "UNKNOWN_ERROR"; Offline vs "NETWORK_UNAVAILABLE"; null) and Dashboard/ReceiptAssist mix use-case artifact
assertions. Runtime gain ≈ 0 (parameterized rows still execute). **Do only the clean, identical parts:**
1. **OnDevice shared contract table** — OnDevice{ReviewExplanation, DedupeJudge, DashboardBriefing, ReceiptAssist}:
   unknown-exception → Failure(Unknown("UNKNOWN_ERROR")) + no sentinel in result/logs + all logs t==null; CancellationException
   propagates + logs.none UNKNOWN_ERROR; parse-failure log hygiene. Columns: per-service sentinel (R6SENTINEL_9f2c,
   R6BSENTINEL_7d41, ...), requireUnknownLog (true for Dashboard/ReceiptAssist only), requireParserFailedLog (Review/Dedupe true;
   ReceiptAssist false by design), entry points (ReceiptAssist: suggest AND suggestFromText).
   Sources to remove after the table holds all assertions: OnDeviceReview L136, L154 (L125 only if row asserts PARSER_FAILED log);
   OnDeviceDedupe L159, L170, L188; OnDeviceDashboard L150, L169; OnDeviceReceiptAssist L195, L214, L233, L250, L264.
   Fixtures: serviceWithModel (reflection "cachedModel") at Review L116 / Dedupe L150 / Dashboard L141 / ReceiptAssist L186;
   withPlantedLogs copies at Review L100 / Dedupe L134 / Dashboard L125 / ReceiptAssist L170.
2. **Cloud Dedupe + Review identical four** (malformed 200 body → ParseError("PARSER_FAILED"); interceptor ISE(sentinel) →
   Unknown("UNKNOWN_ERROR"); timeout/SSL/IO with RecordingRetryDelay.assertSchedule (2 for SocketTimeout else 0); cancel →
   logs.none UNKNOWN_ERROR): CloudDedupe L299, L326, L345, L366; CloudReview L150, L177, L196, L217 (Review additionally asserts
   logs.any PARSER_FAILED / UNKNOWN_ERROR → column). Keep CloudReview L20, L54 (PrivacyDenied capability) and Dedupe L114/L144
   unless rows assert exactly the same. Fixtures: r6Service CloudDedupe L263-281 + sampleInput L30; r6AllowedService CloudReview
   L102-147 + r6Input L87. Capture ALL log priorities (do not weaken to priority>=5).
3. **CloudQueryPreparedPayloadTest → CloudProviderTransportPayloadTest**: L103/L108 already identical in Transport (delete);
   L113/L125 in Transport except Query result code "PROVIDER_DISABLED" (keep as an expectedCode assertion for the QUERY entry);
   L134 policy failure, L157 policy cancellation, L166 gate cancellation, L175 audit failure/cancel provenance → 4 new Transport
   tests (restrict "stage=payload_policy" log assertion to QUERY; gate.check stub shapes differ).
**DROPPED from B5-12:** merging Dashboard/ReceiptAssist/Query/Categorization retry & HTTP matrices (codes differ; artifact
assertions are use-case coverage) — keep them per service.

## Privacy rules for the writer
Per-service sentinels all asserted; union of cancellation log conditions; keep every logs.any(bounded code) positive;
keep assertSchedule; Query @After "financial_data" and "/private" checks stay where they are.
