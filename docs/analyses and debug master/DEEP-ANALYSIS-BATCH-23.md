# Deep Analysis — Batch 23: Utils & Helpers (@reviewer)

## Scope
- app/src/main/java/com/yourname/expensetracker/domain/util/AmountExtractionUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/AmountUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/AppConstants.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/BKTree.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/CommonPatterns.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyFormatter.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyNormalizer.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/GeoUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/MerchantCleaner.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/MerchantKeyGenerator.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/StatisticsUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/StringDistanceUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/SystemTimeProvider.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt
- app/src/main/java/com/yourname/expensetracker/domain/util/TimeProvider.kt
- app/src/main/java/com/yourname/expensetracker/ui/util/ClipboardAmountParser.kt
- app/src/main/java/com/yourname/expensetracker/ui/util/ColorExtensions.kt
- app/src/main/java/com/yourname/expensetracker/ui/util/HapticFeedback.kt
- app/src/main/java/com/yourname/expensetracker/ui/util/ModifierExtensions.kt
- app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt:29,37-39 | HIGH | Logic / range overlap | `RANGE_SIZE` is `9999`, but the 30-day warranty path adds a `5000` offset on top of `WARRANTY_RANGE_START`. That produces IDs up to `24998`, which overruns the documented `15000-19999` band and overlaps the receipt range. | Split warranty IDs into two disjoint subranges (for example modulo `5000` per band), or reduce the per-band span so 7-day and 30-day IDs cannot spill into other notification types. |
| 2 | app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt:37,46,53,60,67,77 | HIGH | Integer overflow / negative modulo | All generators use `% RANGE_SIZE` directly on `Long`. In Kotlin, negative operands keep the sign, so negative IDs (or a negative mixed hash in `fromLong`) can generate values below the reserved range and collide with unrelated notifications. | Use `Math.floorMod(value, RANGE_SIZE.toLong()).toInt()` (and the same for `mixed`) before adding the range start. |
| 3 | app/src/main/java/com/yourname/expensetracker/domain/util/BKTree.kt:20-25 | HIGH | Thread safety | `root` and `_size` are mutated under `mutex`, but `size` and `isEmpty` read them without synchronization or `@Volatile`. Concurrent readers can observe stale state even though writers are locked. | Guard reads with the same mutex, or make the exposed state atomic/volatile and document the concurrency contract. |
| 4 | app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt:32-33,165-171 | HIGH | Database anti-pattern | The importer calls `AppDatabase.getInstance(context)` twice, and the extension at lines 165-171 always builds a brand-new Room database. That creates multiple DB instances/connections instead of using the app singleton/DI graph. | Inject a single `AppDatabase` (or the DAOs) into the importer and delete the local `getInstance` extension that rebuilds the DB. |
| 5 | app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt:79-86 | MEDIUM | CSV parsing | CSV rows are parsed with `line.split(",")`, which breaks on quoted commas (`"ACME, Inc"`) and shifts merchant/category/description columns. That will silently corrupt imported data. | Use a real CSV parser or implement RFC-4180 style quoted-field handling before splitting fields. |
| 6 | app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt:31,89-90 | HIGH | Thread safety | `dateFormat` is a mutable `SimpleDateFormat` field shared by the importer. `importFromContent()` runs on `Dispatchers.IO`; concurrent imports on the same instance can race and parse wrong dates. | Replace it with `java.time` parsing (`LocalDate`/`DateTimeFormatter`) or create a new formatter per call. |
| 7 | app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt:152-153 | HIGH | Integer overflow | `Math.abs(hash)` is unsafe for `Int.MIN_VALUE`; it stays negative, so `colors[Math.abs(hash) % colors.size]` can index the list with a negative value and crash. | Replace with `Math.floorMod(hash, colors.size)` (or `(hash.toUInt() % colors.size.toUInt()).toInt()`). |
| 8 | app/src/main/java/com/yourname/expensetracker/domain/util/AmountExtractionUtils.kt:9,25-26 | MEDIUM | Logic | `CURRENCY_SYMBOL_PATTERN` is declared but never used. `extractAmount()` only looks for ISO codes, so inputs like `$12.34` or `£9.99` are parsed as amount + default `EUR`, which silently assigns the wrong currency. | Map symbol matches before defaulting; e.g. inspect both code and symbol patterns and normalize `$/£/€/¥` to ISO codes. |
| 9 | app/src/main/java/com/yourname/expensetracker/domain/util/AmountUtils.kt:98-105 | MEDIUM | Input validation | Comma-group validation only checks that groups after the first all have the same length. Invalid inputs such as `1,0000` or `12,3456` are accepted as thousands-grouped numbers instead of being rejected. | Require every grouping chunk after the first to be exactly 3 digits when the comma is used as a thousands separator. |
| 10 | app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyNormalizer.kt:18 | MEDIUM | Locale | `uppercase(Locale.getDefault())` is locale-sensitive. On locales such as Turkish, `inr` becomes `İNR`, which then propagates an invalid ISO code instead of `INR`. | Use `Locale.ROOT` (or `Locale.US`) for protocol/token normalization. |
| 11 | app/src/main/java/com/yourname/expensetracker/domain/util/MerchantCleaner.kt:17-20,33-40 | MEDIUM | Logic | Stop-word removal truncates at the first occurrence of `" at"`, `" on"`, `" to"`, etc. anywhere in the string, not just at a metadata tail. Legitimate names like `At Home`, `Cafe Onyx`, or `Road to Athens` can be cut down incorrectly. | Only strip stop words when they appear in an anchored suffix/prefix pattern that matches the notification grammar, not via first-match `indexOf`. |
| 12 | app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt:139-141 | MEDIUM | Locale | `Money.format()` uses `String.format("%.2f", amount)` without an explicit locale. On devices with comma decimal locales, `format()`/`toString()` become locale-dependent, which is risky for logs, snapshots, tests, and any code expecting a stable dot-decimal representation. | Use `String.format(Locale.US, "%.2f", amount)` or a fixed `DecimalFormat` configured with `Locale.ROOT`/US symbols. |
| 13 | app/src/main/java/com/yourname/expensetracker/ui/util/HapticFeedback.kt:14,18 | MEDIUM | API level mismatch | `HapticFeedbackConstants.CONFIRM` and `REJECT` are API 30+ constants, while the app `minSdk` is 26. There is no compatibility fallback for Android 8-10 devices. | Gate these calls with `Build.VERSION.SDK_INT >= 30` and fall back to older supported constants (for example `VIRTUAL_KEY` / `LONG_PRESS`) on API 26-29. |
| 14 | app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt:13-24,28-33 | LOW | Memory leak / thread-local cache | The deprecated formatter path stores a per-thread `MutableMap<String, SimpleDateFormat>` and never clears it. Because the key is caller-supplied `pattern`, long-lived pooled threads can retain one formatter per distinct pattern indefinitely. | Replace this with fixed formatter constants or a bounded cache of `DateTimeFormatter`; if `ThreadLocal` must stay, restrict the key space and clear it explicitly when appropriate. |
| 15 | app/src/main/java/com/yourname/expensetracker/ui/util/ClipboardAmountParser.kt:8 | LOW | Regex | The pattern uses `$` as a regex alternative outside a character class, so it means end-of-string rather than a literal dollar sign. The parser still finds many amounts via substring matching, but the currency token is not modeled as intended. | Escape the dollar (`\$`) or use a character class / symbol map, and add boundaries so the parser matches explicit currency tokens instead of relying on incidental substring matches. |

## Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | AmountUtils, AmountExtractionUtils, ClipboardAmountParser, CsvExpenseImporter | HIGH | Amount parsing is fragmented across four helpers with different regexes, grouping rules, currency handling, and range checks. The result is inconsistent behavior: CSV import bypasses `AmountUtils`, clipboard parsing has its own regex quirks, and extraction defaults symbol-based amounts to EUR. | Consolidate onto one canonical parse/normalize pipeline and make the other helpers thin adapters around it. |
| 2 | DateFormatterUtils and all call sites still using `monthDay()/fullDate()/get()` | MEDIUM | The codebase mixes deprecated `SimpleDateFormat` access with `java.time` helpers. That leaves formatting behavior split across two caching/threading models and keeps the unsafe legacy API on the hot path in multiple screens. | Migrate callers to `java.time` only, delete the deprecated `SimpleDateFormat` accessors, and centralize locale/zone selection in one formatter layer. |
| 3 | MerchantCleaner, MerchantKeyGenerator, downstream merchant identity consumers | MEDIUM | Merchant display cleanup and merchant key generation are separate pipelines with different normalization rules. Aggressive cleaner truncation can change the merchant text before key generation in some flows, while other flows key the raw name directly, creating inconsistent merchant identity across components. | Define one canonical merchant-normalization sequence (clean -> normalize -> key) and ensure every ingestion path uses the same order. |

## Summary
- Total issues: 15
- Critical: 0, High: 6, Medium: 7, Low: 2
- Files with issues: 11/23

## Key Patterns
- Utility responsibilities are still duplicated instead of centralized: amount parsing, merchant normalization, and date formatting each exist in multiple partially-overlapping helpers.
- Several helpers assume “happy-path” inputs and platform conditions, then fail at the boundaries: negative modulo, `Int.MIN_VALUE`, quoted CSV fields, locale-sensitive uppercasing/formatting, and API-30-only constants on a minSdk 26 app.
- A few “utility” classes bypass app-wide infrastructure entirely (notably Room DI/singleton usage in the CSV importer), which reintroduces lifecycle, resource, and consistency problems the rest of the app is already trying to solve.
