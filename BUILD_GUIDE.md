ExpenseTracker - Development Build Guide

Prerequisites
- Android Studio (Arctic Fox or newer) with Android SDK Platform 33+ installed
- JDK 11 (or as required by the project) and Kotlin plugin
- Gradle 7.x (wrapper included in repo)
- Node/Yarn (if any web tooling is used in later modules)
- Git for source control

Setup
- Clone the repository: git clone <repo-url> && cd ExpenseTracker
- Open in Android Studio and let Gradle sync complete
- Ensure Android SDK licenses are accepted
- Check environment: JAVA_HOME points to JDK 11+; build uses Gradle wrapper

Build
- Clean: ./gradlew clean
- Assemble: ./gradlew assembleDebug
- Assemble Release: ./gradlew assembleRelease
- To run tests: ./gradlew testDebugUnitTest

Running on device/emulator
- Create or start an Android Emulator (or connect a real device via USB)
- Run the app from Android Studio or via command: adb install -r app/build/outputs/apk/debug/app-debug.apk
- Ensure that USB debugging is enabled on the device
- Optional: use Android Studio's Run/Debug configurations to launch on a selected device

Configuration
- API keys and endpoints must be securely stored; BuildConfig should not contain secrets.
- Use SecureKeyStorage (production) for API keys; avoid putting secrets in repo
- If you enable experimental features, update BuildConfig flags as documented in BUILD_GUIDE or FEATURES.md

Project structure overview
- app/ or module containing UI (Compose), domain logic, data layer
- data/ - database, repositories, network providers
- domain/ - use cases, engines, models
- ui/ - screens, viewmodels, components
- di/ - dependency injection and binding modules
- data/location/ - geocoding integrations (NEW Mar 2026)
- data/ai/ - AI-related providers
- data/security/ - key storage and related security utilities (NEW Apr 2026)

Common issues and solutions
- Gradle sync issues: Invalidate caches and restart, check internet connection, ensure JDK version is compatible.
- Missing dependencies: Run ./gradlew clean and then ./gradlew build to re-resolve; refresh Gradle in IDE.
- Build failures due to Kotlin version: Check project Gradle wrapper and Kotlin plugin versions in build.gradle files.
- Emulator slow or API level mismatch: Adjust AVD settings or use a physical device.
- Network calls failing in emulator: Ensure proper network configuration or use a real device for testing behind corporate proxies.

Tips for contributors
- Use feature branches per task; follow commit messages with a concise why and what.
- Run unit tests locally; consider integration tests for critical flows.
- Update or add tests when introducing new domain logic.

End-to-end quick start
- Start Android Studio > Open project
- Build > Run on device or emulator
- Navigate to the features via the app; check the 77 screens and 32 routes for coverage

---
