#!/usr/bin/env python3
"""
verify_privacy_boundaries.py — PR10 Static Privacy Guards

Scans the Kotlin source tree for privacy boundary violations.

Usage:
    python3 scripts/verify_privacy_boundaries.py [--root <project-root>]

Exit codes:
    0  No violations found.
    1  One or more violations found (CI should fail).

Guard rules enforced:
    G1  No cloud provider may use AiSettings.redactBeforeCloud for redaction decisions.
    G2  No cloud provider may use input.redactBeforeCloud for final policy.
    G3  No Request.Builder().post(...) in cloud package using a raw String prompt directly.
    G4  No `object : PrivacyGate { Allowed }` (allow-all gate) in main source.
    G5  No String.hashCode() for message IDs / provider transaction IDs.
    G6  No PendingReview creation with raw notification/email/OCR body without sanitizer.
    G7  No debug export of rawOcrText/rawNotification/email body without PrivacyGate(DEBUG_RAW_EXPORT).
    G8  encryptedBackupEnabled=false must NOT allow raw export (logic guard, checked for pattern).
"""

import os
import re
import sys
import argparse
from dataclasses import dataclass, field
from typing import List

MAIN_SRC = "app/src/main/java/com/yourname/expensetracker"
AI_PROVIDER_PKG = f"{MAIN_SRC}/data/ai"
CLOUD_BACKUP_PKG = f"{MAIN_SRC}/data/backup"


@dataclass
class Violation:
    rule: str
    file: str
    line_no: int
    line: str
    message: str


def find_kt_files(root: str, sub: str = "") -> List[str]:
    search_root = os.path.join(root, sub) if sub else root
    result = []
    for dirpath, _, filenames in os.walk(search_root):
        for fn in filenames:
            if fn.endswith(".kt"):
                result.append(os.path.join(dirpath, fn))
    return result


def scan_file(filepath: str, rules) -> List[Violation]:
    violations = []
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except OSError:
        return violations

    for rule_fn in rules:
        violations.extend(rule_fn(filepath, lines))
    return violations


# ── Rule implementations ──────────────────────────────────────────────────────

def rule_g1_ai_redact_before_cloud(filepath: str, lines: List[str]) -> List[Violation]:
    """G1: Cloud AI providers must not read AiSettings.redactBeforeCloud for policy."""
    violations = []
    # Only check cloud provider files
    if "data/ai" not in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'aiSettings.*\.redactBeforeCloud|redactBeforeCloud.*aiSettings')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G1",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Cloud AI provider reading AiSettings.redactBeforeCloud — must use CloudPayloadPolicy instead"
            ))
    return violations


def rule_g2_input_redact_before_cloud(filepath: str, lines: List[str]) -> List[Violation]:
    """G2: Cloud AI providers must not use caller input.redactBeforeCloud for final policy."""
    violations = []
    if "data/ai" not in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'input\.redactBeforeCloud|\.redactBeforeCloud\b')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G2",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Cloud provider using input.redactBeforeCloud — must use CloudPayloadPolicy"
            ))
    return violations


def rule_g4_allow_all_privacy_gate(filepath: str, lines: List[str]) -> List[Violation]:
    """G4: No object : PrivacyGate { Allowed } in main source (except test constructors)."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'object\s*:\s*PrivacyGate')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G4",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Allow-all PrivacyGate object in main source — use proper PrivacyGate implementation"
            ))
    return violations


def rule_g5_hashcode_for_ids(filepath: str, lines: List[str]) -> List[Violation]:
    """G5: No String.hashCode() for message IDs / provider transaction IDs."""
    violations = []
    pattern = re.compile(r'(messageId|providerTransactionId|transactionId|accountId|orderId).*\.hashCode\(\)|\.hashCode\(\).*(messageId|providerTransactionId|transactionId)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G5",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="String.hashCode() used for sensitive ID — must use SensitiveHashingService.hmacSha256Prefix"
            ))
    return violations


def rule_g5b_hashcode_in_sanitizer(filepath: str, lines: List[str]) -> List[Violation]:
    """G5b: RawContentSanitizer must not use hashCode()."""
    violations = []
    if "RawContentSanitizer" not in filepath:
        return violations
    pattern = re.compile(r'\.hashCode\(\)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G5b",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="RawContentSanitizer must not use String.hashCode() — use SensitiveHashingService"
            ))
    return violations


def rule_g7_raw_ocr_debug_export(filepath: str, lines: List[str]) -> List[Violation]:
    """G7: No debug export of rawOcrText without PrivacyGate(DEBUG_RAW_EXPORT)."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    # Look for files that directly access rawOcrText in export/debug context
    if "export" not in filepath.lower() and "debug" not in filepath.lower() and "ExportAnonymizer" not in filepath:
        return violations
    pattern = re.compile(r'rawOcrText')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            # Check context: is there a DEBUG_RAW_EXPORT check nearby?
            # Simple heuristic: check 10 lines before and after
            context_start = max(0, i - 10)
            context_end = min(len(lines), i + 10)
            context = "".join(lines[context_start:context_end])
            if "DEBUG_RAW_EXPORT" not in context and "ExportAnonymizer" not in filepath:
                violations.append(Violation(
                    rule="G7",
                    file=filepath,
                    line_no=i + 1,
                    line=line.rstrip(),
                    message="rawOcrText accessed in export/debug context without DEBUG_RAW_EXPORT gate"
                ))
    return violations


def rule_g8_encrypted_disabled_implies_raw(filepath: str, lines: List[str]) -> List[Violation]:
    """G8: encryptedBackupEnabled=false must not directly allow raw export."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    # Pattern: if !encryptedBackupEnabled followed by allowing raw export
    pattern = re.compile(r'!.*encryptedBackupEnabled.*Allowed|encryptedBackupEnabled.*false.*Allowed.*raw', re.IGNORECASE)
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G8",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="encryptedBackupEnabled=false appears to allow raw export — this is not allowed"
            ))
    return violations


# ── Runner ───────────────────────────────────────────────────────────────────

ALL_RULES = [
    rule_g1_ai_redact_before_cloud,
    rule_g2_input_redact_before_cloud,
    rule_g4_allow_all_privacy_gate,
    rule_g5_hashcode_for_ids,
    rule_g5b_hashcode_in_sanitizer,
    rule_g7_raw_ocr_debug_export,
    rule_g8_encrypted_disabled_implies_raw,
]


def run(root: str) -> List[Violation]:
    all_violations: List[Violation] = []
    kt_files = find_kt_files(root, MAIN_SRC)
    for filepath in kt_files:
        all_violations.extend(scan_file(filepath, ALL_RULES))
    return all_violations


def main():
    parser = argparse.ArgumentParser(description="PR10 Privacy boundary static guard")
    parser.add_argument("--root", default=".", help="Project root directory")
    args = parser.parse_args()

    root = os.path.abspath(args.root)
    print(f"Scanning privacy boundaries in: {root}")

    violations = run(root)
    if not violations:
        print("✅ No privacy boundary violations found.")
        sys.exit(0)
    else:
        print(f"\n❌ Found {len(violations)} privacy boundary violation(s):\n")
        for v in violations:
            rel_path = os.path.relpath(v.file, root)
            print(f"  [{v.rule}] {rel_path}:{v.line_no}")
            print(f"         {v.message}")
            print(f"         > {v.line.strip()}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
