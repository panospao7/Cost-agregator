# Migration Test Procedure

## Prerequisites
- Android emulator running (Pixel_8a or similar)
- ADB available on PATH

## Running Tests
```bash
# Start emulator
emulator -avd Pixel_8a -no-window &

# Build and install
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Run tests
adb shell am instrument -w com.yourname.expensetracker.test/androidx.test.runner.AndroidJUnitRunner

# Or via Gradle (installs APKs automatically)
./gradlew :app:connectedDebugAndroidTest
```

## Known Issues
- Schema JSONs may have BOM bytes — run `python fix_bom.py` if needed
- "Starting 0 tests" means APKs need manual installation
- DatabaseMigrationMatrixTest has 6 tests covering v145-v148 chain
