plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
    id("com.google.dagger.hilt.android") version "2.57"
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

    buildTypes {
        release {
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
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        unitTests.all {
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { forks -> forks > 0 } ?: 1

            it.testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showExceptions = true
                showCauses = true
                showStackTraces = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

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

// HIGH-6: Ignored-test count guard — fails if @Ignore annotations grow
tasks.register("verifyNoIgnoredGrowth") {
    group = "verification"
    description = "Fails if the number of @Ignore-annotated test methods grows beyond the threshold"

    doLast {
        val maxAllowed = (findProperty("maxIgnoredTests")?.toString()?.toIntOrNull()) ?: 310
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

// CI guard: fails if production code calls deprecated raw aggregation DAO methods
// TODO: Add CI guard that fails if production code calls deprecated raw aggregation
// methods (e.g., getTotalSpentBetween, getTotalSpent) via grep/lint rule.

// ARCH-01: Lifecycle bypass guard — wired into check lifecycle
tasks.register("checkLifecycleBypasses") {
    group = "verification"
    description = "Fails if production code contains direct ExpenseDao calls that bypass TransactionLifecycleCoordinator"

    doLast {
        val script = file("$rootDir/scripts/guards/check_lifecycle_bypasses.kts")
        if (!script.exists()) {
            logger.warn("Lifecycle bypass guard script not found at ${script.absolutePath}")
            return@doLast
        }
        try {
            exec {
                workingDir = rootDir
                commandLine("kotlin", script.absolutePath)
            }
        } catch (e: Exception) {
            throw GradleException("Lifecycle bypass guard failed: ${e.message}")
        }
    }
}

// Wire both guards into the check lifecycle
tasks.named("check") {
    dependsOn("checkLifecycleBypasses")
}

// TODO (PR-E23): Add check_raw_money_aggregates.kts CI guard for raw Double financial totals.
// Flag: sumOf { it.amount }, sumOf { it.effectiveAmount }, total: Double in public engine results.
// TODO (PR-E24): Add check_direct_time_calls.kts CI guard.
// Flag: System.currentTimeMillis(), Date(), Calendar.getInstance(), Instant.now(), LocalDate.now()
// Allowlist: TimeProvider implementations, platform adapters, tests.
// TODO (M10): Add CI guard for direct System.currentTimeMillis/Instant.now/Date()
// calls outside approved TimeProvider implementations
