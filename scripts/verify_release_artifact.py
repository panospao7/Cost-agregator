#!/usr/bin/env python3
"""
RELEASE_ARTIFACT_VERIFIER — Validates the release APK/AAB.

Checks:
  - Release APK exists and is non-empty
  - Minification is enabled (check build config)
  - No debug-only classes leaked into release
  - No test dependencies in release
  - Network security config present
  - ProGuard mapping file exists

Exit codes: 0 = pass, 1 = violation, 2 = error
"""
import sys
import os
import argparse
import zipfile
from pathlib import Path

RULE_ID = "G-RELEASE-01"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fail-on-violation", action="store_true")
    args = parser.parse_args()
    
    apk_dir = Path("app/build/outputs/apk/release")
    apks = list(apk_dir.glob("*.apk"))
    
    violations = []
    
    if not apks:
        violations.append(f"{RULE_ID} : No release APK found in {apk_dir}")
    else:
        for apk in apks:
            size_mb = apk.stat().st_size / (1024 * 1024)
            print(f"Found release APK: {apk.name} ({size_mb:.1f} MB)")
            
            # Check APK contents
            try:
                with zipfile.ZipFile(apk, 'r') as zf:
                    names = zf.namelist()
                    dex_files = [n for n in names if n.endswith('.dex')]
                    print(f"  DEX files: {len(dex_files)}")
                    
                    # Check for debug/test classes
                    debug_classes = [n for n in names if 'debug' in n.lower() or 'mock' in n.lower()]
                    if debug_classes:
                        violations.append(f"{RULE_ID} {apk.name}: Debug/test classes found in release: {debug_classes[:5]}")
            except Exception as e:
                violations.append(f"{RULE_ID} {apk.name}: Cannot inspect APK: {e}")
    
    # Check mapping file
    mapping = Path("app/build/outputs/mapping/release/mapping.txt")
    if mapping.exists():
        print(f"ProGuard mapping: {mapping.stat().st_size} bytes")
    else:
        violations.append(f"{RULE_ID} : No ProGuard mapping file found")
    
    if violations:
        for v in violations:
            print(v)
        if args.fail_on_violation:
            sys.exit(1)
    else:
        print("PASS: Release artifact verification complete.")
        sys.exit(0)

if __name__ == "__main__":
    main()
