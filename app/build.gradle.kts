plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.27"
    id("com.google.dagger.hilt.android") version "2.51.1"
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

        // Geocoding API keys — read from local.properties (not committed to VCS)
        val localProps = com.android.build.gradle.internal.cxx.configure.gradleLocalProperties(
            rootDir, providers
        )
        buildConfigField("String", "GEOAPIFY_API_KEY",
            "\"${localProps.getProperty("geoapify.api.key", "")}\"")
        buildConfigField("String", "GOOGLE_PLACES_API_KEY",
            "\"${localProps.getProperty("google.places.api.key", "")}\"")
        buildConfigField("String", "GEMINI_API_KEY",
            "\"${localProps.getProperty("gemini.api.key", "")}\"")
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
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")


    // Encrypted SharedPreferences (for SQLCipher passphrase storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
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
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // DataStore — AI settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Activity Extensions for viewModels()
    implementation("androidx.activity:activity-ktx:1.9.3")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20231013")
    // Hilt Testing
    testImplementation("com.google.dagger:hilt-android-testing:2.51.1")
    kspTest("com.google.dagger:hilt-android-compiler:2.51.1")
    // Robolectric
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Turbine - Flow testing
    testImplementation("app.cash.turbine:turbine:1.2.0")
    // Truth - readable assertions
    testImplementation("com.google.truth:truth:1.4.4")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    // WorkManager testing
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("verifyRoomSchemaSnapshots") {
    group = "verification"
    description = "Reports Room schema snapshot coverage by version"

    doLast {
        val maxVersion = 35
        val schemaDir = file("$projectDir/schemas/com.yourname.expensetracker.data.database.AppDatabase")
        val existing = if (schemaDir.exists()) {
            schemaDir.listFiles()
                ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                ?.toSet()
                ?: emptySet()
        } else {
            emptySet()
        }
        val expected = (1..maxVersion).toSet()
        val missing = expected - existing

        logger.lifecycle("Room schema snapshots present: ${existing.size}/$maxVersion")
        logger.lifecycle("Present versions: ${existing.sorted()}")
        if (missing.isNotEmpty()) {
            logger.warn("Missing versions: ${missing.sorted()}")
            if ((findProperty("strictRoomSchemas")?.toString()?.toBoolean() == true)) {
                throw GradleException("Missing Room schema snapshots: ${missing.sorted()}")
            }
        } else {
            logger.lifecycle("All schema snapshots are present.")
        }
    }
}
