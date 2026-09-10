#!/usr/bin/env python3
"""Verify release APK using aapt2 and apksigner."""
import subprocess, sys, argparse, json, shutil, os
from pathlib import Path

# Auto-detect build-tools path, falling back to known locations
def _find_build_tools():
    """Find the latest build-tools directory in the Android SDK."""
    candidates = []

    # 1) Environment variables (ANDROID_HOME / ANDROID_SDK_ROOT)
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = os.environ.get(env_var)
        if sdk_root:
            candidates.append(Path(sdk_root) / "build-tools")

    # 2) Known paths (Windows, macOS, Linux CI)
    candidates.extend([
        Path("C:/Users/panos/AppData/Local/Android/Sdk/build-tools"),
        Path.home() / "Android" / "Sdk" / "build-tools",
        Path.home() / "Library" / "Android" / "sdk" / "build-tools",
        Path("/usr/local/lib/android/sdk/build-tools"),
        Path("/opt/android-sdk/build-tools"),
        Path.home() / "android-sdk" / "build-tools",
        Path.home() / ".android" / "sdk" / "build-tools",
    ])

    for base in candidates:
        if base.exists():
            versions = sorted(
                [d for d in base.iterdir() if d.is_dir() and d.name[0].isdigit()],
                key=lambda d: tuple(int(x) for x in d.name.split(".")),
                reverse=True,
            )
            for v in versions:
                aapt2 = v / "aapt2"
                apksigner = v / "apksigner"
                if sys.platform == "win32":
                    aapt2 = v / "aapt2.exe"
                    apksigner = v / "apksigner.bat"
                if aapt2.exists() and apksigner.exists():
                    return aapt2, apksigner
    return None, None

AAPT2, APKSIGNER = _find_build_tools()

if AAPT2 is None:
    # Fall back to searching PATH
    AAPT2 = shutil.which("aapt2") or shutil.which("aapt2.exe")
    if AAPT2:
        AAPT2 = Path(AAPT2)
    APKSIGNER = shutil.which("apksigner") or shutil.which("apksigner.bat")
    if APKSIGNER:
        APKSIGNER = Path(APKSIGNER)

def verify_manifest(apk_path):
    """Use aapt2 to dump and verify the AndroidManifest.xml."""
    if AAPT2 is None:
        return ["G-RELEASE-01: aapt2 not found — verify build-tools are installed"]
    result = subprocess.run(
        [str(AAPT2), "dump", "badging", str(apk_path)],
        capture_output=True, text=True, timeout=30
    )
    if result.returncode != 0:
        return [f"G-RELEASE-01: aapt2 failed: {result.stderr}"]
    
    violations = []
    output = result.stdout
    
    # Check debuggable
    if "debuggable" in output:
        # aapt2 badging includes debuggable key if true
        violations.append("G-RELEASE-01: APK is debuggable (must be false for release)")
    
    # Check testOnly
    if "testOnly" in output:
        violations.append("G-RELEASE-01: APK is testOnly (must be false for release)")
    
    # Report APK info
    for line in output.splitlines():
        if line.startswith("package:"):
            print(f"  Package: {line}")
        if line.startswith("application:"):
            print(f"  Application: {line}")
    
    return violations

def verify_signing(apk_path):
    """Verify APK is signed."""
    if APKSIGNER is None:
        return ["G-RELEASE-01: apksigner not found — verify build-tools are installed"]
    result = subprocess.run(
        [str(APKSIGNER), "verify", "--verbose", str(apk_path)],
        capture_output=True, text=True, timeout=30
    )
    if result.returncode != 0:
        return [f"G-RELEASE-01: APK verification failed: {result.stderr}"]
    print("  Signing: verified")
    return []

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fail-on-violation", action="store_true")
    parser.add_argument("--apk", help="Path to release APK")
    args = parser.parse_args()
    
    apk_dir = Path("app/build/outputs/apk/release")
    if args.apk:
        apks = [Path(args.apk)]
    else:
        apks = list(apk_dir.glob("*.apk"))
    
    if not apks:
        print("G-RELEASE-01: No release APK found")
        if args.fail_on_violation:
            sys.exit(1)
        return
    
    violations = []
    for apk in apks:
        size_mb = apk.stat().st_size / (1024 * 1024)
        print(f"Release APK: {apk.name} ({size_mb:.1f} MB)")
        violations.extend(verify_manifest(apk))
        violations.extend(verify_signing(apk))
    
    if violations:
        for v in violations:
            print(v)
        if args.fail_on_violation:
            sys.exit(1)
    else:
        print("PASS: Release artifact verification complete")
        sys.exit(0)

if __name__ == "__main__":
    main()
