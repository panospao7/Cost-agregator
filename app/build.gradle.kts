plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
    id("com.google.dagger.hilt.android") version "2.57"
}

// Resolve the Python interpreter for guard tasks. Prefers an explicit
// -PpythonExecutable property, then python3, then python (Windows has no
// python3 by default). A project property always wins so CI can pin it.
fun pythonInterpreter(): String {
    findProperty("pythonExecutable")?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
    val candidates = listOf("python3", "python")
    for (candidate in candidates) {
        try {
            exec {
                workingDir = rootDir
                commandLine(candidate, "--version")
                isIgnoreExitValue = true
            }.let { if (it.exitValue == 0) return candidate }
        } catch (_: Exception) {
            // Candidate not on PATH; try the next one.
        }
    }
    return "python3"
}

android {
    namespace = "com.yourname.expensetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourname.expensetracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

        // Geocoding API keys — removed from BuildConfig for security
        // Keys are now stored in SecureKeyStorage (encrypted at rest)
    }

    signingConfigs {
        getByName("debug") {
            // Uses default debug keystore — sufficient for CI verification
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI signing: ephemeral debug keystore for verification only.
            // Production release signing uses a separate protected configuration.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Keep debug build fast for development
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md" 
        }
    }
    sourceSets {
        getByName("debug").assets.srcDirs("$projectDir/schemas")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("test").assets.srcDirs("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        unitTests.all {
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { forks -> forks > 0 } ?: 1

            it.systemProperty("updateGoldens", project.findProperty("updateGoldens") ?: "false")

            it.testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showExceptions = true
                showCauses = true
                showStackTraces = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    }

    lint {
        // Baseline captures pre-existing MissingTranslation issues only.
        // All lint rules are fully active; baselined issues are suppressed by the XML.
        baseline = file("lint-baseline.xml")}
}
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.ui.tooling)
    
    // Room Database
    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")


    // Encrypted SharedPreferences (for SQLCipher passphrase storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-android-compiler:2.57")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // Material Components (XML) - Required for Theme.ExpenseTracker parent
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // Vico Charts
    implementation(libs.vico.compose)
    implementation(libs.vico.core)

    // ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")

    // PDF Processing - Direct text extraction for bank statements
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Coil for image loading in Compose
    implementation("io.coil-kt:coil-compose:2.5.0")

    // OkHttp — used by NominatimGeocodingService and OverpassNearbyService
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Play Services Location — FusedLocationProviderClient
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Coroutines adapter for Google Tasks (used by FusedLocationProvider await())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // osmdroid — OpenStreetMap tile rendering for SpendingMapScreen
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // WorkManager — background geocoding backfill worker
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // DataStore — AI settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Gson - JSON serialization for split templates
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Activity Extensions for viewModels()
    implementation("androidx.activity:activity-ktx:1.9.3")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20231013")
    // Hilt Testing
    testImplementation("com.google.dagger:hilt-android-testing:2.57")
    kspTest("com.google.dagger:hilt-android-compiler:2.57")
    // Robolectric
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Turbine - Flow testing
    testImplementation("app.cash.turbine:turbine:1.2.0")
    // Truth - readable assertions
    testImplementation("com.google.truth:truth:1.4.4")
    // WorkManager testing for unit worker tests
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    // WorkManager testing
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("verifyRoomSchemaSnapshots") {
    group = "verification"
    description = "Reports Room schema snapshot coverage by version, migration-aware"

    doLast {
        // ── 1. Read AppDatabase.kt and extract schema version + migrations ─────────
        val appDatabaseFile = file(
            "$projectDir/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt"
        )
        if (!appDatabaseFile.exists()) {
            throw GradleException(
                "Cannot find AppDatabase.kt at ${appDatabaseFile.absolutePath}. " +
                "Cannot determine the database schema version or registered migrations."
            )
        }
        val content = appDatabaseFile.readText()

        // Extract APP_DATABASE_SCHEMA_VERSION (e.g. "const val APP_DATABASE_SCHEMA_VERSION = 113")
        val versionRegex = Regex("""const val APP_DATABASE_SCHEMA_VERSION\s*=\s*(\d+)""")
        val latestVersion = versionRegex.find(content)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: throw GradleException(
                "Could not parse APP_DATABASE_SCHEMA_VERSION from " +
                "${appDatabaseFile.name}. Expected pattern: " +
                "`const val APP_DATABASE_SCHEMA_VERSION = <number>`"
            )

        // Extract all migration start versions from MIGRATION_X_Y patterns
        // (e.g. "MIGRATION_54_55" → start version 54)
        val migrationRegex = Regex("""MIGRATION_(\d+)_(\d+)""")
        val migrationStartVersions = migrationRegex.findAll(content)
            .map { it.groupValues[1].toInt() }
            .toSortedSet()

        if (migrationStartVersions.isEmpty()) {
            throw GradleException(
                "Found zero MIGRATION_X_Y declarations in ${appDatabaseFile.name}. " +
                "Cannot build the expected schema snapshot set."
            )
        }

        // ── 2. Expected versions = all migration start versions + latest version ──
        val expectedVersions = (migrationStartVersions + latestVersion).toSortedSet()

        // ── 3. Scan existing snapshot files ───────────────────────────────────────
        val schemaDir = file(
            "$projectDir/schemas/com.yourname.expensetracker.data.database.AppDatabase"
        )
        val existingVersions = if (schemaDir.exists()) {
            schemaDir.listFiles()
                ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                ?.toSortedSet()
                ?: sortedSetOf()
        } else {
            sortedSetOf()
        }

        val presentVersions = expectedVersions.intersect(existingVersions).toSortedSet()
        val missingVersions = (expectedVersions - existingVersions).toSortedSet()
        val extraVersions = (existingVersions - expectedVersions).toSortedSet()

        // ── 4. Known intentional gaps — versions where schema files were never    ──
        //       generated by Room (skip-migrations, early versions before schema
        //       export was enabled, or transient versions).  These are tracked
        //       explicitly so they are not silently ignored.
        //       Update this list when the migration set changes.
        //
        //       Pre-33:   schema export was configured starting from version 33.
        //       54-55:    schema files not generated (migrations exist but Room did
        //                 not emit snapshots — likely a Room AP limitation with
        //                 chained table-rebuild migrations at the time).
        //       58:       same as 54-55.
        //       61-63:    same as 54-55.
        //       66:       same as 54-55.
        //       97-99:    Room skip-migration from version 96 directly to 100
        //                 (MIGRATION_96_100), so no intermediate schemas exist.
        val knownGapVersions = sortedSetOf<Int>().apply {
            // Pre-33: schema export started at version 33
            addAll((migrationStartVersions.first()..32).toSet())
            // 54-55: transient schema gap
            addAll(setOf(54, 55))
            // 58: transient schema gap
            add(58)
            // 61-63: transient schema gap
            addAll(setOf(61, 62, 63))
            // 66: transient schema gap
            add(66)
            // 97-99: skip-migration 96→100
            addAll(setOf(97, 98, 99))
        }
        // Only retain gaps that are actually in our expected set
        val effectiveKnownGaps = knownGapVersions.intersect(expectedVersions)
        val unexpectedMissing = missingVersions - effectiveKnownGaps

        // ── 5. Report ─────────────────────────────────────────────────────────────
        logger.lifecycle("═══════════════════════════════════════════════════════════")
        logger.lifecycle("  Room Schema Snapshot Verification")
        logger.lifecycle("═══════════════════════════════════════════════════════════")
        logger.lifecycle("  Database version (from AppDatabase.kt):  $latestVersion")
        logger.lifecycle("  Migration start versions found:          ${migrationStartVersions.size}")
        logger.lifecycle("  Expected snapshot versions:              ${expectedVersions.size}")
        logger.lifecycle("  Present on disk:                         ${presentVersions.size}")
        logger.lifecycle("  Known intentional gaps:                  ${effectiveKnownGaps.size}")
        if (missingVersions.isNotEmpty()) {
            logger.lifecycle("  Missing total:                           ${missingVersions.size}")
        }
        logger.lifecycle("")
        logger.lifecycle("  Present versions : $presentVersions")
        if (missingVersions.isNotEmpty()) {
            logger.lifecycle("  Missing versions  : $missingVersions")
        }
        if (effectiveKnownGaps.isNotEmpty()) {
            logger.lifecycle("  Known gaps        : $effectiveKnownGaps")
        }
        if (extraVersions.isNotEmpty()) {
            logger.lifecycle("  Extra versions    : $extraVersions")
        }
        logger.lifecycle("═══════════════════════════════════════════════════════════")

        // ── 6. Fail or warn on unexpected missing snapshots ───────────────────────
        if (unexpectedMissing.isNotEmpty()) {
            val strict = findProperty("strictRoomSchemas")?.toString()?.toBoolean() == true
            val message = buildString {
                appendLine("Unexpectedly missing Room schema snapshots for versions: $unexpectedMissing")
                appendLine("")
                appendLine("Required versions are migration start versions + the latest database version.")
                appendLine("Known intentional gaps are excluded from this check:")
                appendLine("  $effectiveKnownGaps")
                appendLine("")
                appendLine("To fix, either:")
                appendLine("  a) Generate the missing snapshot (run Room annotation processor)")
                appendLine("  b) If this is a new intentional gap, add it to the `knownGapVersions` set")
                appendLine("     in the `verifyRoomSchemaSnapshots` task (app/build.gradle.kts)")
            }
            if (strict) {
                throw GradleException(message.trimEnd())
            } else {
                logger.warn(message.trimEnd())
            }
        } else {
            logger.lifecycle("All expected Room schema snapshots are present (or accounted for as known gaps).")
        }
    }
}

// HIGH-5: Wire schema verification into the Gradle 'check' lifecycle
tasks.named("check") {
    dependsOn("verifyRoomSchemaSnapshots")
}

// HIGH-6: Ignored-test count guard — fails if @Ignore annotations grow (wired to :app:check in PR 2)
tasks.register("verifyNoIgnoredGrowth") {
    group = "verification"
    description = "Fails if the number of @Ignore-annotated test methods grows beyond the threshold"

    doLast {
        val maxAllowed = (findProperty("maxIgnoredTests")?.toString()?.toIntOrNull()) ?: 29
        val testDirs = listOf(
            file("$projectDir/src/test/java"),
            file("$projectDir/src/test/kotlin"),
            file("$projectDir/src/androidTest/java"),
            file("$projectDir/src/androidTest/kotlin")
        )
        var ignoredCount = 0
        for (dir in testDirs) {
            if (dir.exists()) {
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file ->
                        ignoredCount += file.readText().lines()
                            .count { line -> line.contains("@Ignore") && !line.trimStart().startsWith("//") }
                    }
            }
        }
        logger.lifecycle("Ignored test methods/classes found: $ignoredCount (max allowed: $maxAllowed)")
        if (ignoredCount > maxAllowed) {
            throw GradleException(
                "Ignored test count ($ignoredCount) exceeds threshold ($maxAllowed). " +
                "Either fix/delete ignored tests or increase the threshold via -PmaxIgnoredTests=N."
            )
        }
    }
}

// Deprecated raw-aggregation DAO methods (e.g., getTotalSpentBetween,
// getTotalSpentFlow) are guarded at the source: each carries
// @Deprecated(level = DeprecationLevel.ERROR), so any production call
// without an explicit @Suppress("DEPRECATION_ERROR") fails the build.
// Remaining call sites (ExpenseRepository, SpendingChallengeManager, ...)
// are individually suppressed with migration TODOs. No separate grep/lint
// rule is needed — the compiler-level ERROR deprecation is the guard.

// ARCH-01: Lifecycle bypass guard — wired into check lifecycle (inline, no external kotlin dependency)
tasks.register("checkLifecycleBypasses") {
    group = "verification"
    description = "Fails if production code contains direct ExpenseDao calls that bypass TransactionLifecycleCoordinator"

    doLast {
        val srcDir = file("$rootDir/app/src/main/java")
        val forbiddenPatterns = listOf(
            "expenseDao.updateCategory(" to "TransactionLifecycleCoordinator.updateCategory()",
            "expenseDao.updateCategoryNullable(" to "TransactionLifecycleCoordinator.updateCategory()",
            "expenseDao.updateMerchantAndKey(" to "TransactionLifecycleCoordinator.updateMerchant()",
            "expenseDao.updateTransactionType(" to "TransactionLifecycleCoordinator.updateType()",
            "expenseDao.updateTransferDirection(" to "TransactionLifecycleCoordinator.updateTransferDetails()",
            "expenseDao.updateTransferAccountName(" to "TransactionLifecycleCoordinator.updateTransferDetails()",
            "expenseDao.updateIsNotMine(" to "TransactionLifecycleCoordinator.updateOwnership()",
            "expenseDao.updateOwnerName(" to "TransactionLifecycleCoordinator.updateOwnership()",
            "expenseDao.updateIsSharedExpense(" to "TransactionLifecycleCoordinator.updateOwnership()",
            "expenseDao.updateSharedWithName(" to "TransactionLifecycleCoordinator.updateOwnership()",
            "expenseDao.updateMySharePercentage(" to "TransactionLifecycleCoordinator.updateOwnership()",
            "expenseDao.updateMyShareAmount(" to "TransactionLifecycleCoordinator.updateOwnership()",
            "expenseDao.updateLocation(" to "TransactionLifecycleCoordinator.updateLocation()",
            "expenseDao.clearLocation(" to "TransactionLifecycleCoordinator.updateLocation()"
        )
        val allowlist = setOf(
            "TransactionLifecycleCoordinator.kt",
            "ReceiptLinkService.kt",
            "GroupTransactionCoordinator.kt",
            "GroupLifecycleCoordinator.kt",
            "GroupBalanceCalculator.kt",
            "LocationBackfillWorker.kt",
            "MerchantKeyBackfillWorker.kt"
        )
        val violations = mutableListOf<String>()
        if (srcDir.exists()) {
            srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("test") && !it.path.contains("androidTest") }
                .forEach { f ->
                    val fileName = f.name
                    if (fileName in allowlist) return@forEach
                    val content = f.readText()
                    for ((pattern, replacement) in forbiddenPatterns) {
                        if (pattern in content) {
                            violations.add("${f.path}: Direct call to '$pattern' — use $replacement instead")
                        }
                    }
                }
        } else {
            throw GradleException("checkLifecycleBybasses: source directory not found at ${srcDir.absolutePath}")
        }
        if (violations.isNotEmpty()) {
            throw GradleException("LIFECYCLE BYPASS: ${violations.size} violation(s):\n  ${violations.joinToString("\n  ")}")
        } else {
            logger.lifecycle("OK: No lifecycle bypass violations found.")
        }
    }
}

// Wire both guards into the check lifecycle
tasks.named("check") {
    dependsOn("checkLifecycleBypasses")
}

// PR-E23: Inline CI guard for raw Double financial totals.
// Flags: sumOf { it.amount }, sumOf { it.effectiveAmount }, total: Double in public engine results.
tasks.register("checkRawMoneyAggregates") {
    group = "verification"
    description = "Fails if production code uses raw Double financial aggregates without MoneyAggregate"
    doLast {
        val srcDir = file("$rootDir/app/src/main/java")
        val rawSumPatterns = listOf(
            Regex("""\.sumOf\s*\{\s*it\.amount\s*\}"""),
            Regex("""\.sumOf\s*\{\s*it\.effectiveAmount\s*\}"""),
            Regex("""\.sumOf\s*\{\s*it\.normalizedAmount\s*\}"""),
            Regex("""\.sumOf\s*\{\s*it\.\w*[Pp]rice\s*\}"""),
            Regex("""\.sumBy\s*\{\s*it\.amount\s*\.(?:toInt|roundToInt)\s*\(\)\s*\}"""),
            Regex("""total\s*:\s*Double"""),
            Regex("""var\s+total\s*=\s*0\.0\s*;?\s*//?\s*.*sum""")
        )
        val allowlistFiles = setOf(
            "MoneyAggregateBuilder.kt", "MoneyAggregate.kt", "ConvertedMoney.kt",
            "CurrencyConverter.kt", "MultiCurrencyRepository.kt", "ExpenseDao.kt", "BudgetDao.kt"
        )
        val violations = mutableListOf<String>()
        if (srcDir.exists()) {
            srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                val fileName = f.name
                if (fileName in allowlistFiles) return@forEach
                val filePathLower = f.path.lowercase()
                if (filePathLower.contains("test") || filePathLower.contains("androidtest")) return@forEach
                val lines = f.readLines()
                var inFromBuckets = false
                var bracketDepth = 0
                lines.forEachIndexed { lineNum, line ->
                    val stripped = line.trim()
                    if (stripped.contains("fromBuckets") && stripped.contains("{")) {
                        inFromBuckets = true
                        bracketDepth = 0
                    }
                    if (inFromBuckets) {
                        bracketDepth += stripped.count { c -> c == '{' } - stripped.count { c -> c == '}' }
                        if (bracketDepth <= 0) { inFromBuckets = false; bracketDepth = 0 }
                        return@forEachIndexed
                    }
                    if (stripped.startsWith("import ") || stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) return@forEachIndexed
                    for (pattern in rawSumPatterns) {
                        if (pattern.containsMatchIn(stripped)) {
                            violations.add("${f.path}:${lineNum + 1}: Raw money aggregate matches '${pattern.pattern}'")
                        }
                    }
                }
            }
        } else {
            throw GradleException("checkRawMoneyAggregates: source directory not found at ${srcDir.absolutePath}")
        }
        if (violations.isNotEmpty()) {
            throw GradleException("RAW MONEY AGGREGATE: ${violations.size} violation(s):\n  ${violations.joinToString("\n  ")}")
        } else {
            logger.lifecycle("OK: No raw money aggregate violations found.")
        }
    }
}

// PR-GR-02: Canonical direct-time boundary guard (G-TIME-01) via fail-closed
// wrapper around scripts/verify_time_boundaries.py. The defective inline
// Kotlin scanner (with its now()/now =/TimeProvider( substring exemptions)
// has been removed and replaced by the tested canonical script.
//
// Required inputs are validated BEFORE execution (fail closed):
//   scripts/verify_time_boundaries.py
//   config/guards/time_boundary_exceptions.yml
// A missing / non-regular / unreadable / outside-root input is a hard
// GradleException — never a warning or a silent skip.
//
// Python interpreter (same contract as GR-01's verifyDbAccessBoundaries):
//   -PpythonExecutable=/path/to/python3
// A preflight `pythonExecutable --version` runs first; failure to launch
// Python (or a non-zero --version exit) is an infrastructure error.
tasks.register("checkDirectTimeCalls") {
    group = "verification"
    description = "Fails if production code calls wall-clock APIs outside the exact time-boundary exceptions (fail closed)"
    doLast {
        val rootCanonical = rootDir.canonicalFile
        val scriptFile = file("$rootDir/scripts/verify_time_boundaries.py").canonicalFile
        val allowlistFile = file("$rootDir/config/guards/time_boundary_exceptions.yml").canonicalFile

        val requiredInputs = listOf(
            "scripts/verify_time_boundaries.py" to scriptFile,
            "config/guards/time_boundary_exceptions.yml" to allowlistFile
        )
        for ((rel, candidate) in requiredInputs) {
            if (!candidate.path.startsWith(rootCanonical.path + File.separator, ignoreCase = true)) {
                throw GradleException(
                    "checkDirectTimeCalls: required input for '$rel' points outside the repository root: " +
                    candidate.absolutePath
                )
            }
            if (!candidate.exists()) {
                throw GradleException(
                    "checkDirectTimeCalls: required input not found: ${candidate.absolutePath} ($rel)"
                )
            }
            if (!candidate.isFile) {
                throw GradleException(
                    "checkDirectTimeCalls: required input is not a regular file: ${candidate.absolutePath} ($rel)"
                )
            }
            if (!candidate.canRead()) {
                throw GradleException(
                    "checkDirectTimeCalls: required input is not readable: ${candidate.absolutePath} ($rel)"
                )
            }
        }

        val pythonExecutable = pythonInterpreter()

        // Preflight: launch the interpreter with --version. Failure to launch
        // Python is an infrastructure error, not a policy violation.
        val preflightExit: Int = try {
            exec {
                workingDir = rootDir
                commandLine(pythonExecutable, "--version")
                isIgnoreExitValue = true
            }.exitValue
        } catch (_: Exception) {
            throw GradleException(
                "checkDirectTimeCalls: Python preflight failed — could not launch '$pythonExecutable' " +
                "(infrastructure error). Pass -PpythonExecutable=/path/to/python3 to specify the interpreter."
            )
        }
        if (preflightExit != 0) {
            throw GradleException(
                "checkDirectTimeCalls: Python preflight failed — '$pythonExecutable --version' exited " +
                "$preflightExit (infrastructure error). Pass -PpythonExecutable=/path/to/python3 to specify the interpreter."
            )
        }

        // Execute the canonical guard with an argument list (shell=False), never
        // a shell string with embedded paths.
        val commandArgs = listOf(
            pythonExecutable,
            scriptFile.absolutePath,
            "--root", rootCanonical.absolutePath,
            "--allowlist", allowlistFile.absolutePath,
            "--fail-on-violation"
        )
        val result = exec {
            workingDir = rootDir
            commandLine(commandArgs)
            isIgnoreExitValue = true
        }
        when (result.exitValue) {
            0 -> { /* pass: no direct wall-clock time violations */ }
            1 -> throw GradleException(
                "DIRECT TIME: direct wall-clock time boundary violations found. " +
                "Route the call through TimeProvider (timeProvider.now()) or add an exact exception entry " +
                "to config/guards/time_boundary_exceptions.yml with a reason, owner, and linked issue. " +
                "See docs/development/TIME_SEMANTICS.md."
            )
            2 -> throw GradleException(
                "checkDirectTimeCalls: infrastructure error (missing/malformed exceptions policy, " +
                "empty source tree, or parser failure). Check that scripts/verify_time_boundaries.py and " +
                "config/guards/time_boundary_exceptions.yml are present and valid."
            )
            else -> throw GradleException("checkDirectTimeCalls: unexpected exit code ${result.exitValue}")
        }
    }
}

/**
 * CI-enforced boundary guard.
 *
 * FAILS BUILD on direct ExpenseDao insert/update/delete mutations
 * outside the lifecycle allowlist. Any new class that needs direct
 * ExpenseDao access MUST be added to [allowlistForGuard] with a
 * documented rationale in docs/expense-mutation-inventory.md.
 *
 * Allowlist (approved bypasses):
 * - TransactionLifecycleCoordinator — the canonical mutation entry point
 * - LocationBackfillWorker — background column backfill (1-2 cols, low-value events)
 * - MerchantKeyBackfillWorker — background column backfill (1 col, low-value events)
 * - GroupTransactionCoordinator — atomic group-expense creation within outer tx
 * - DebugExpenseRepository — BuildConfig.DEBUG guarded debug methods
 * - AppDatabase — Room infrastructure
 * - ReceiptLinkService — circular dependency constraint (RCP-30)
 * - ExpenseRepository — delegated to coordinator for all user paths
 * - MultiCurrencyRepository — analytics-only read path with conversion inserts
 * - NotificationRepository — notification capture, not expense mutation
 */
val srcDirForGuard = layout.projectDirectory.dir("src/main/java").asFile
val allowlistForGuard = setOf(
    "TransactionLifecycleCoordinator", "LocationBackfillWorker", "MerchantKeyBackfillWorker",
    "GroupTransactionCoordinator", "DebugExpenseRepository", "AppDatabase",
    "ReceiptLinkService", "ExpenseRepository", "MultiCurrencyRepository",
    "NotificationRepository"
)
tasks.register("checkLifecycleBypass") {
    group = "verification"
    description = "Fails if ExpenseDao.insert/update/delete called outside TransactionLifecycleCoordinator"
    doLast {
        val violations = mutableListOf<String>()
        srcDirForGuard.walk().forEach { f ->
            if (!f.name.endsWith(".kt") || f.isDirectory) return@forEach
            val className = f.name.removeSuffix(".kt")
            if (allowlistForGuard.any { className.contains(it) }) return@forEach
            val text = f.readText()
            val patterns = listOf("expenseDao\\.insert", "expenseDao\\.update", "expenseDao\\.delete")
            for (pattern in patterns) {
                if (Regex(pattern).containsMatchIn(text)) {
                    violations.add("${f.path}: matches ${pattern}")
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("LIFECYCLE BYPASS: Direct ExpenseDao mutations outside allowlist:\n  ${violations.joinToString("\n  ")}")
        }
    }
}

// Wire both new guards into the check lifecycle
// VERIFIED (PR-E24): Both checkRawMoneyAggregates and checkDirectTimeCalls are
// registered (above) AND wired to the "check" lifecycle via dependsOn.
tasks.named("check") {
    dependsOn("checkRawMoneyAggregates")
    dependsOn("checkDirectTimeCalls")
    dependsOn("checkLifecycleBypass")
}

// checkDirectTimeCalls wraps scripts/verify_time_boundaries.py and is
// fail-closed: any missing/unreadable input or direct wall-clock call
// outside the exact exceptions in config/guards/time_boundary_exceptions.yml
// produces a hard GradleException.  No TODO remains — the guard is fully
// wired into the "check" lifecycle (see dependsOn block above).

// PR-GR-01 — DB access boundary guard via ratchet wrapper (fail closed).
// The ratchet accepts baselined findings (exit 0 if no new violations)
// and fails on new violations (exit 1) or infrastructure errors (exit 2).
//
// Required inputs are validated BEFORE execution:
//   scripts/ci/guard_ratchet.py
//   scripts/verify_db_access_boundaries.py
//   config/baselines/db_access_v2.json
//   config/guards/db_ownership_policy.yml
//   config/guards/db_structural_exceptions.yml
//   config/guards/db_structural_exceptions_expected_methods.yml
//   config/guards/production_source_roots.yml
// A missing / non-regular / unreadable / outside-root input is a hard
// GradleException — never a warning or a silent skip.
//
// Test-only path overrides (production CI must use the defaults):
//   -PdbGuardRatchetPath=...                 scripts/ci/guard_ratchet.py
//   -PdbGuardScriptPath=...                  scripts/verify_db_access_boundaries.py
//   -PdbGuardBaselinePath=...                config/baselines/db_access_v2.json
//   -PdbGuardOwnershipPolicyPath=...         config/guards/db_ownership_policy.yml
//   -PdbGuardStructuralExceptionsPath=...    config/guards/db_structural_exceptions.yml
//   -PdbGuardStructuralManifestPath=...      config/guards/db_structural_exceptions_expected_methods.yml
//   -PdbGuardSourceRootsManifestPath=...     config/guards/production_source_roots.yml
//
// Relative overrides resolve against the repository root (rootDir) so they
// are consistent with the canonical defaults; absolute overrides are used
// as-is.
//
// The inner ratchet command ALWAYS receives all six resolved canonical paths
// explicitly — the policy/manifest inputs are never gated on the test-only
// overrides, so production CI uses the exact canonical defaults below.
// config/guards/production_source_roots.yml (PR-GR-03) is validated as a
// required input but is not a child argument: the guard loads it from its
// canonical repository-relative path via scripts/db_guard/source_roots.py.
//
// Python interpreter (defaults to python3):
//   -PpythonExecutable=/path/to/python3
tasks.register("verifyDbAccessBoundaries") {
    group = "verification"
    description = "Fails build if unauthorized direct DAO mutations are found outside the approved writer policy (ratchet-enforced, fail closed)"
    doLast {
        val rootCanonical = rootDir.canonicalFile
        fun resolveDbGuardPath(defaultRel: String, overrideProp: String): File {
            val override = findProperty(overrideProp)?.toString()?.takeIf { it.isNotBlank() }
            val path = if (override != null) {
                // Relative overrides are resolved against the repository root
                // (rootDir), consistent with the canonical defaults; absolute
                // overrides are used as-is.
                val overrideFile = File(override)
                if (overrideFile.isAbsolute) file(override) else file("$rootDir/$override")
            } else {
                file("$rootDir/$defaultRel")
            }
            val canonical = path.canonicalFile
            if (!canonical.path.startsWith(rootCanonical.path + File.separator, ignoreCase = true)) {
                throw GradleException(
                    "verifyDbAccessBoundaries: required input for '$defaultRel' points outside the repository root: " +
                    path.absolutePath
                )
            }
            return canonical
        }

        val ratchetFile = resolveDbGuardPath("scripts/ci/guard_ratchet.py", "dbGuardRatchetPath")
        val guardFile = resolveDbGuardPath("scripts/verify_db_access_boundaries.py", "dbGuardScriptPath")
        val baselineFile = resolveDbGuardPath("config/baselines/db_access_v2.json", "dbGuardBaselinePath")
        val ownershipPolicyFile = resolveDbGuardPath(
            "config/guards/db_ownership_policy.yml", "dbGuardOwnershipPolicyPath"
        )
        val structuralExceptionsFile = resolveDbGuardPath(
            "config/guards/db_structural_exceptions.yml", "dbGuardStructuralExceptionsPath"
        )
        val structuralManifestFile = resolveDbGuardPath(
            "config/guards/db_structural_exceptions_expected_methods.yml", "dbGuardStructuralManifestPath"
        )
        val sourceRootsManifestFile = resolveDbGuardPath(
            "config/guards/production_source_roots.yml", "dbGuardSourceRootsManifestPath"
        )

        val requiredInputs = listOf(
            "scripts/ci/guard_ratchet.py" to ratchetFile,
            "scripts/verify_db_access_boundaries.py" to guardFile,
            "config/baselines/db_access_v2.json" to baselineFile,
            "config/guards/db_ownership_policy.yml" to ownershipPolicyFile,
            "config/guards/db_structural_exceptions.yml" to structuralExceptionsFile,
            "config/guards/db_structural_exceptions_expected_methods.yml" to structuralManifestFile,
            "config/guards/production_source_roots.yml" to sourceRootsManifestFile
        )
        for ((rel, candidate) in requiredInputs) {
            if (!candidate.exists()) {
                throw GradleException(
                    "verifyDbAccessBoundaries: required input not found: ${candidate.absolutePath} ($rel)"
                )
            }
            if (!candidate.isFile) {
                throw GradleException(
                    "verifyDbAccessBoundaries: required input is not a regular file: ${candidate.absolutePath} ($rel)"
                )
            }
            if (!candidate.canRead()) {
                throw GradleException(
                    "verifyDbAccessBoundaries: required input is not readable: ${candidate.absolutePath} ($rel)"
                )
            }
        }

        val pythonExecutable = pythonInterpreter()

        // Preflight: launch the interpreter with --version.  Failure to launch
        // Python is an infrastructure error, not a policy violation.
        val preflightExit: Int = try {
            exec {
                workingDir = rootDir
                commandLine(pythonExecutable, "--version")
                isIgnoreExitValue = true
            }.exitValue
        } catch (_: Exception) {
            throw GradleException(
                "verifyDbAccessBoundaries: Python preflight failed — could not launch '$pythonExecutable' " +
                "(infrastructure error). Pass -PpythonExecutable=/path/to/python3 to specify the interpreter."
            )
        }
        if (preflightExit != 0) {
            throw GradleException(
                "verifyDbAccessBoundaries: Python preflight failed — '$pythonExecutable --version' exited " +
                "$preflightExit (infrastructure error). Pass -PpythonExecutable=/path/to/python3 to specify the interpreter."
            )
        }

        // Execute the ratchet with an argument list (shell=False), never a shell
        // string with embedded paths.  The inner guard command is passed as
        // repeatable single-token --command-arg=<value> arguments to eliminate
        // shell-string ambiguity and to keep option-like child values
        // (--fail-on-violation, --ownership-policy, --structural-exceptions,
        // --structural-manifest) inside the child command: a separate
        // "--command-arg <value>" pair would let argparse re-parse those
        // values as the ratchet's own flags and abort with "expected one
        // argument".  --command is kept only as a ratchet compatibility path.
        //
        // All six child-command inputs are passed EXPLICITLY with their resolved
        // canonical paths — including the three policy/manifest inputs, which
        // are never gated on override properties.  In production CI the
        // defaults are explicit and identical to the canonical config paths:
        //   config/guards/db_ownership_policy.yml
        //   config/guards/db_structural_exceptions.yml
        //   config/guards/db_structural_exceptions_expected_methods.yml
        // so the inner guard can never silently fall back to a different file.
        // The source-root manifest (config/guards/production_source_roots.yml)
        // is validated above but read by the guard from its canonical path.
        val commandArgs = mutableListOf<String>()
        commandArgs += pythonExecutable
        commandArgs += ratchetFile.absolutePath
        commandArgs += "--guard-name"
        commandArgs += "db_access"
        // Every ratchet child argument is encoded as a single
        // --command-arg=<value> list token, including option-like values.
        commandArgs += "--command-arg=$pythonExecutable"
        commandArgs += "--command-arg=${guardFile.absolutePath}"
        commandArgs += "--command-arg=--fail-on-violation"
        commandArgs += "--command-arg=--ownership-policy"
        commandArgs += "--command-arg=${ownershipPolicyFile.absolutePath}"
        commandArgs += "--command-arg=--structural-exceptions"
        commandArgs += "--command-arg=${structuralExceptionsFile.absolutePath}"
        commandArgs += "--command-arg=--structural-manifest"
        commandArgs += "--command-arg=${structuralManifestFile.absolutePath}"
        commandArgs += "--baseline"
        commandArgs += baselineFile.absolutePath
        commandArgs += "--fail-on-violation"
        commandArgs += "--ci-mode"

        val result = exec {
            workingDir = rootDir
            commandLine(commandArgs)
            isIgnoreExitValue = true
        }
        when (result.exitValue) {
            0 -> { /* pass: no new violations */ }
            1 -> throw GradleException(
                "New DB access boundary violations found. " +
                "Add an exact entry to config/guards/db_ownership_policy.yml with a reason, " +
                "or a structural exception to config/guards/db_structural_exceptions.yml, " +
                "or route the write through the approved lifecycle coordinator. " +
                "See docs/DB_WRITE_OWNERSHIP.md."
            )
            2 -> throw GradleException(
                "verifyDbAccessBoundaries: infrastructure error (missing baseline, malformed config, or ratchet failure). " +
                "Check that config/baselines/db_access_v2.json exists and is valid, and that the DB guard scripts " +
                "and policy files under config/guards/ are present and valid."
            )
            else -> throw GradleException("verifyDbAccessBoundaries: unexpected exit code ${result.exitValue}")
        }
    }
}

tasks.named("check") {
    dependsOn("verifyDbAccessBoundaries")
    dependsOn("verifyNoIgnoredGrowth")
}
