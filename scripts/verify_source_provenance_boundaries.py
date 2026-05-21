#!/usr/bin/env python3
"""
Static source provenance boundary guard for ExpenseTracker.

PR10: Verifies that source link provenance code maintains proper boundaries
around sensitive data, identity key completeness, metadata allowlist
consistency, ExpenseSource coverage, and EntitySourceLink construction.

P1: Extended to detect JSONObject.put(), putString(), buildJsonObject, and
JSONObject().apply{put()} patterns with blocked keys.

Rules enforced:
  G-PROV-01  No raw sensitive content in source link metadata
             (covers metadataMap[], JSONObject.put(), putString(), buildJsonObject)
  G-PROV-02  All SourceEntityType values have identity key handlers
  G-PROV-03  SafeProvenanceMetadata allowlist is consistent
  G-PROV-04  All ExpenseSource values are handled in TransactionLifecycleCoordinator
  G-PROV-05  No direct EntitySourceLink constructor outside allowed files
"""

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Set, Tuple


@dataclass
class Violation:
    line_num: int
    rule: str
    description: str
    line: str


# ── Paths ──────────────────────────────────────────────────────────────────────

# Files where EntitySourceLink constructor is allowed (non-test)
ALLOWED_ENTITY_SOURCE_LINK_FILES = {
    "SourceLinkWriterImpl.kt",
    "SourceLinkBackfillWorker.kt",
}

# Files excluded from scanning for checks that blanket-scan all sources
EXCLUDED_FILES = {
    'EntitySourceLink.kt',
}

EXCLUDED_PATH_PATTERNS = [
    r'/test/',
    r'/androidTest/',
    r'Test\.kt$',
    r'Fixture\.kt$',
    r'Fake\.kt$',
    r'Mock\.kt$',
]

# ── Check 1: Raw sensitive keys (the blocked list from SafeProvenanceMetadata) ──
BLOCKED_METADATA_KEYS = [
    "rawText", "rawBody", "emailBody", "emailSubjectRaw", "emailSenderRaw",
    "bankDescription", "bankReference", "accessToken", "refreshToken",
    "prompt", "fullPath", "iban", "accountNumber", "cardNumber",
    "messageId", "providerTransactionId", "bankAccountId",
    "notificationKey", "orderId", "rawNotificationBody",
    "rawNotificationTitle", "emailSubject", "emailSender",
]

# Build regex: matches metadataMap["blockedKey"] or metadataMap["blockedKeyWithMore"]
RAW_METADATA_RE = re.compile(
    r'metadataMap\s*\[\s*"(?:' + '|'.join(re.escape(k) for k in BLOCKED_METADATA_KEYS) + r')"\s*\]'
)

# P1: JSONObject.put("blockedKey", ...) — catches direct JSON construction leaks
JSONOBJECT_PUT_RE = re.compile(
    r'\.put\s*\(\s*"(?:' + '|'.join(re.escape(k) for k in BLOCKED_METADATA_KEYS) + r')"\s*[,)]'
)

# P1: putString("blockedKey", ...) — catches builder-style leaks
PUT_STRING_RE = re.compile(
    r'putString\s*\(\s*"(?:' + '|'.join(re.escape(k) for k in BLOCKED_METADATA_KEYS) + r')"\s*[,)]'
)

# P1: buildJsonObject { put("blockedKey", ...) } — catches Kotlin DSL leaks
BUILD_JSON_OBJECT_RE = re.compile(
    r'buildJsonObject\s*\{[^}]*put\s*\(\s*"(?:' + '|'.join(re.escape(k) for k in BLOCKED_METADATA_KEYS) + r')"\s*[,)]'
)

# P1: JSONObject().apply { put("blockedKey", ...) } — catches apply-style leaks
JSONOBJECT_APPLY_PUT_RE = re.compile(
    r'JSONObject\s*\(\s*\)\s*\.apply\s*\{[^}]*\.put\s*\(\s*"(?:' + '|'.join(re.escape(k) for k in BLOCKED_METADATA_KEYS) + r')"\s*[,)]'
)

# ── Check 2: SourceEntityType enum values ──────────────────────────────────────
# Expected enum values — extracted from SourceLinkEnums.kt
EXPECTED_SOURCE_ENTITY_TYPES: Set[str] = {
    "RAW_NOTIFICATION",
    "PENDING_REVIEW",
    "SCANNED_RECEIPT",
    "EMAIL_RECEIPT_SOURCE",
    "RECEIPT_EXPENSE_LINK",
    "BANK_CONNECTION",
    "BANK_ACCOUNT",
    "BANK_TRANSACTION",
    "BANK_SYNC_RUN",
    "BANK_STATEMENT_IMPORT_RUN",
    "BANK_STATEMENT_IMPORT_ITEM",
    "CSV_IMPORT_RUN",
    "CSV_IMPORT_ROW",
    "JSON_IMPORT_RUN",
    "JSON_IMPORT_ROW",
    "FILE_IMPORT",
    "GROUP",
    "RECURRING_RULE",
    "RECURRING_OCCURRENCE",
    "PLANNED_EXPENSE",
    "MANUAL_ENTRY",
    "DEBUG_TOOL",
    "MIGRATION",
    "LEGACY_SOURCE_ONLY",
    "UNKNOWN",
}

# Regex to extract the SourceEntityType value from a when branch:
#   SourceEntityType.VALUE ->
WHEN_CASE_RE = re.compile(r'SourceEntityType\.(\w+)\s*->')

# ── Check 3: SafeProvenanceMetadata allowed keys ──────────────────────────────
# The ALLOWED_KEYS from SafeProvenanceMetadata.kt
ALLOWED_METADATA_KEYS: Set[str] = {
    "parserId", "parserVersion", "providerId", "confidence",
    "importFormat", "importSchemaVersion", "statementPageNumber",
    "transactionStatus", "bookingDate", "valueDate",
    "receiptLinkType", "dedupeReason", "matchedExpenseId",
    "receiptLinkId", "linkType", "importRowCount",
    "originalSource", "migrationVersion",
    "stage", "reason", "extractionState", "routingDecision",
    "promotedFromTargetType", "promotedFromPendingReviewId",
    "promotedFromSourceLinkId", "promotedFromRole",
    "appName", "packageNameHash", "notificationHash",
    "matchedNotificationId", "matchType",
    "provider", "messageIdHash", "contentFingerprintHash",
    "providerTransactionHash", "accountHash",
}

# Regex to extract string keys from metadataMap["key"] assignments
METADATA_MAP_KEY_RE = re.compile(r'metadataMap\s*\[\s*"([^"]+)"\s*\]')

# Files to scan for metadata key usage (payload factories)
METADATA_SCAN_FILES = {
    "ImportSourceLinkPayloadFactory.kt",
    "BankSourceLinkPayloadFactory.kt",
    "NotificationSourceLinkPayloadFactory.kt",
    "ReceiptSourceLinkPayloadFactory.kt",
    "PendingReviewSourcePayloadFactory.kt",
}

# ── Check 4: ExpenseSource values and their coverage in coordinator ────────────
EXPECTED_EXPENSE_SOURCES: Set[str] = {
    "MANUAL_ENTRY",
    "MANUAL",
    "NOTIFICATION_AUTO_ACCEPT",
    "SMS_NOTIFICATION",
    "REVIEW_APPROVAL",
    "RECEIPT_SCAN",
    "RECEIPT_BATCH_REVIEW",
    "BANK_STATEMENT_REVIEW",
    "CSV_IMPORT",
    "EMAIL_RECEIPT",
    "GROUP_EXPENSE",
    "BANK_SYNC",
    "BANK_API_SYNC",
    "RECURRING_GENERATED",
    "DEBUG_TOOL",
    "MIGRATION",
    "UNKNOWN",
}

# Regex to extract ExpenseSource values from a when block
EXPENSE_SOURCE_WHEN_CASE_RE = re.compile(r'ExpenseSource\.(\w+)\s*->')

# ── Check 5: EntitySourceLink( constructor calls ──────────────────────────────
ENTITY_SOURCE_LINK_CONSTRUCTOR_RE = re.compile(r'\bEntitySourceLink\s*\(')


# ── Helpers ────────────────────────────────────────────────────────────────────

def is_excluded(file_path: Path) -> bool:
    if file_path.name in EXCLUDED_FILES:
        return True
    path_str = str(file_path).replace('\\', '/')
    return any(re.search(p, path_str) for p in EXCLUDED_PATH_PATTERNS)


def extract_source_entity_type_when_cases(file_path: Path) -> Tuple[Set[str], List[Violation]]:
    """Parse sourceIdentityKey() when block and return handled types + violations for unhandled."""
    content = file_path.read_text(encoding='utf-8')
    handled: Set[str] = set()
    violations: List[Violation] = []

    for match in WHEN_CASE_RE.finditer(content):
        handled.add(match.group(1))

    # Check for else branch (handles UNKNOWN or any unhandled)
    has_else = bool(re.search(r'\belse\s*->', content))

    missing = EXPECTED_SOURCE_ENTITY_TYPES - handled
    if missing and not has_else:
        for enum_val in sorted(missing):
            violations.append(Violation(
                line_num=0,
                rule='G-PROV-02',
                description=f'SourceEntityType.{enum_val} has no case in sourceIdentityKey()',
                line=f'Missing case for {enum_val}'
            ))

    return handled, violations


def extract_expense_source_when_cases(content: str) -> Set[str]:
    """Parse a when(ExpenseSource) block and return handled enum values."""
    handled: Set[str] = set()
    for match in EXPENSE_SOURCE_WHEN_CASE_RE.finditer(content):
        handled.add(match.group(1))
    return handled


def extract_metadata_map_keys(content: str) -> Set[str]:
    """Extract all metadataMap string keys from a file's content."""
    keys: Set[str] = set()
    for match in METADATA_MAP_KEY_RE.finditer(content):
        keys.add(match.group(1))
    return keys


# ── Check functions ────────────────────────────────────────────────────────────

def check_raw_metadata(kt_file: Path) -> List[Violation]:
    """G-PROV-01: No raw sensitive content in source link metadata.

    P1: Extended to also detect JSONObject.put(), putString(), buildJsonObject,
    and JSONObject().apply { put() } patterns with blocked keys.
    """
    violations: List[Violation] = []
    try:
        content = kt_file.read_text(encoding='utf-8')
    except Exception as e:
        print(f"Warning: Could not read {kt_file}: {e}", file=sys.stderr)
        return violations

    lines = content.splitlines()
    for idx, line in enumerate(lines):
        if RAW_METADATA_RE.search(line):
            violations.append(Violation(
                line_num=idx + 1,
                rule='G-PROV-01',
                description='Raw sensitive key used in metadataMap — hash or use allowed key instead',
                line=line.strip()
            ))
        # P1: JSONObject.put("blockedKey", ...)
        if JSONOBJECT_PUT_RE.search(line):
            violations.append(Violation(
                line_num=idx + 1,
                rule='G-PROV-01',
                description='Raw sensitive key in JSONObject.put() — use SafeProvenanceMetadata instead',
                line=line.strip()
            ))
        # P1: putString("blockedKey", ...)
        if PUT_STRING_RE.search(line):
            violations.append(Violation(
                line_num=idx + 1,
                rule='G-PROV-01',
                description='Raw sensitive key in putString() — use SafeProvenanceMetadata instead',
                line=line.strip()
            ))
        # P1: buildJsonObject { put("blockedKey", ...) }
        if BUILD_JSON_OBJECT_RE.search(line):
            violations.append(Violation(
                line_num=idx + 1,
                rule='G-PROV-01',
                description='Raw sensitive key in buildJsonObject — use SafeProvenanceMetadata instead',
                line=line.strip()
            ))
        # P1: JSONObject().apply { put("blockedKey", ...) }
        if JSONOBJECT_APPLY_PUT_RE.search(line):
            violations.append(Violation(
                line_num=idx + 1,
                rule='G-PROV-01',
                description='Raw sensitive key in JSONObject().apply{put()} — use SafeProvenanceMetadata instead',
                line=line.strip()
            ))

    return violations


def check_identity_key_handlers(source_link_writer_path: Path) -> List[Violation]:
    """G-PROV-02: All SourceEntityType values have identity key handlers."""
    _, violations = extract_source_entity_type_when_cases(source_link_writer_path)
    return violations


def check_metadata_allowlist_consistency(pkg_dir: Path) -> List[Violation]:
    """G-PROV-03: SafeProvenanceMetadata allowlist is consistent with payload factory usage."""
    violations: List[Violation] = []
    all_used_keys: Set[str] = set()

    for file_name in METADATA_SCAN_FILES:
        file_path = pkg_dir / file_name
        if not file_path.exists():
            continue
        try:
            content = file_path.read_text(encoding='utf-8')
        except Exception as e:
            print(f"Warning: Could not read {file_path}: {e}", file=sys.stderr)
            continue

        keys = extract_metadata_map_keys(content)
        all_used_keys.update(keys)

    # Check each used key against the allowlist
    for key in sorted(all_used_keys):
        if key not in ALLOWED_METADATA_KEYS:
            violations.append(Violation(
                line_num=0,
                rule='G-PROV-03',
                description=f'Metadata key "{key}" used in factories but not in SafeProvenanceMetadata.ALLOWED_KEYS',
                line=f'Missing key: {key}'
            ))

    return violations


def check_expense_source_coverage(coordinator_path: Path) -> List[Violation]:
    """G-PROV-04: All ExpenseSource values handled in TransactionLifecycleCoordinator source mapping."""
    violations: List[Violation] = []
    try:
        content = coordinator_path.read_text(encoding='utf-8')
    except Exception as e:
        print(f"Warning: Could not read {coordinator_path}: {e}", file=sys.stderr)
        return violations

    # Find the source = when (request.source) block
    source_when_match = re.search(
        r'source\s*=\s*when\s*\(\s*request\.source\s*\)\s*\{',
        content
    )
    if not source_when_match:
        violations.append(Violation(
            line_num=0,
            rule='G-PROV-04',
            description='Could not find source = when(request.source) block in TransactionLifecycleCoordinator',
            line='Missing source mapping when block'
        ))
        return violations

    # Extract the when block content (from after the opening brace to matching closing brace)
    start = source_when_match.end()
    bracket_depth = 1  # we start after the opening '{'
    when_block_content = ''
    i = start
    while i < len(content) and bracket_depth > 0:
        ch = content[i]
        if ch == '{':
            bracket_depth += 1
        elif ch == '}':
            bracket_depth -= 1
            if bracket_depth == 0:
                when_block_content += ch
                break
        when_block_content += ch
        i += 1

    handled = extract_expense_source_when_cases(when_block_content)
    missing = EXPECTED_EXPENSE_SOURCES - handled

    for enum_val in sorted(missing):
        violations.append(Violation(
            line_num=0,
            rule='G-PROV-04',
            description=f'ExpenseSource.{enum_val} has no case in coordinator source mapping when block',
            line=f'Missing case for {enum_val}'
        ))

    return violations


def check_entity_source_link_constructors(src_dir: Path) -> List[Violation]:
    """G-PROV-05: No direct EntitySourceLink( constructor outside allowed files."""
    violations: List[Violation] = []
    for kt_file in sorted(src_dir.rglob('*.kt')):
        if is_excluded(kt_file):
            continue
        if kt_file.name in ALLOWED_ENTITY_SOURCE_LINK_FILES:
            continue

        # Skip test files
        path_str = str(kt_file).replace('\\', '/')
        if any(re.search(p, path_str) for p in EXCLUDED_PATH_PATTERNS):
            continue

        try:
            content = kt_file.read_text(encoding='utf-8')
        except Exception as e:
            print(f"Warning: Could not read {kt_file}: {e}", file=sys.stderr)
            continue

        for match in ENTITY_SOURCE_LINK_CONSTRUCTOR_RE.finditer(content):
            line_num = content[:match.start()].count('\n') + 1
            line = content.splitlines()[line_num - 1].strip()
            violations.append(Violation(
                line_num=line_num,
                rule='G-PROV-05',
                description='Direct EntitySourceLink( constructor outside allowed files',
                line=line
            ))

    return violations


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description='Static source provenance boundary guard v1'
    )
    parser.add_argument(
        '--root', type=Path, default=Path(__file__).parent.parent,
        help='Project root directory'
    )
    parser.add_argument(
        '--fix', action='store_true',
        help='Auto-fix fixable issues (currently no checks support auto-fix)'
    )
    args = parser.parse_args()

    if args.fix:
        print("Note: No checks currently support --fix auto-remediation.", file=sys.stderr)

    root = args.root
    src_dir = root / 'app' / 'src' / 'main' / 'java'
    if not src_dir.exists():
        print(f"Error: Source directory not found: {src_dir}", file=sys.stderr)
        sys.exit(1)

    provenance_dir = src_dir / 'com' / 'yourname' / 'expensetracker' / 'domain' / 'provenance'
    coordinator_path = (
        src_dir / 'com' / 'yourname' / 'expensetracker' / 'domain' /
        'transaction' / 'lifecycle' / 'TransactionLifecycleCoordinator.kt'
    )
    source_link_writer_path = provenance_dir / 'SourceLinkWriterImpl.kt'

    # Verify expected files exist
    missing_files = []
    if not provenance_dir.exists():
        missing_files.append(str(provenance_dir))
    if not coordinator_path.exists():
        missing_files.append(str(coordinator_path))
    if not source_link_writer_path.exists():
        missing_files.append(str(source_link_writer_path))

    if missing_files:
        print(f"Error: Required files not found:", file=sys.stderr)
        for f in missing_files:
            print(f"  {f}", file=sys.stderr)
        sys.exit(1)

    all_violations: List[Tuple[str, List[Violation]]] = []

    # ── Check 1: Raw metadata (scan all Kotlin source files) ─────────────────
    print("Check G-PROV-01: Scanning for raw sensitive keys in metadata (mapOf, JSONObject.put, putString, buildJsonObject)...")
    raw_violations: List[Violation] = []
    kt_files = [f for f in src_dir.rglob('*.kt') if not is_excluded(f)]
    for kt_file in sorted(kt_files):
        raw_violations.extend(check_raw_metadata(kt_file))

    if raw_violations:
        all_violations.append(("G-PROV-01 Raw metadata", raw_violations))
        print(f"  Found {len(raw_violations)} violation(s)")
    else:
        print("  PASS: No raw sensitive keys in metadataMap")

    # ── Check 2: Identity key handlers ──────────────────────────────────────
    print("\nCheck G-PROV-02: Verifying SourceEntityType identity key handlers...")
    id_key_violations = check_identity_key_handlers(source_link_writer_path)
    if id_key_violations:
        all_violations.append(("G-PROV-02 Identity key handlers", id_key_violations))
        print(f"  Found {len(id_key_violations)} violation(s)")
    else:
        print("  PASS: All SourceEntityType values have identity key handlers")

    # ── Check 3: Metadata allowlist consistency ──────────────────────────────
    print("\nCheck G-PROV-03: Verifying metadata allowlist consistency...")
    metadata_violations = check_metadata_allowlist_consistency(provenance_dir)
    if metadata_violations:
        all_violations.append(("G-PROV-03 Metadata allowlist", metadata_violations))
        print(f"  Found {len(metadata_violations)} violation(s)")
    else:
        print("  PASS: All metadata keys used in factories are in ALLOWED_KEYS")

    # ── Check 4: ExpenseSource coverage ─────────────────────────────────────
    print("\nCheck G-PROV-04: Verifying ExpenseSource coverage in coordinator...")
    source_coverage_violations = check_expense_source_coverage(coordinator_path)
    if source_coverage_violations:
        all_violations.append(
            ("G-PROV-04 ExpenseSource coverage", source_coverage_violations)
        )
        print(f"  Found {len(source_coverage_violations)} violation(s)")
    else:
        print("  PASS: All ExpenseSource values handled in coordinator source mapping")

    # ── Check 5: EntitySourceLink constructor ───────────────────────────────
    print("\nCheck G-PROV-05: Verifying EntitySourceLink constructor boundaries...")
    constructor_violations = check_entity_source_link_constructors(src_dir)
    if constructor_violations:
        all_violations.append(
            ("G-PROV-05 EntitySourceLink constructor", constructor_violations)
        )
        print(f"  Found {len(constructor_violations)} violation(s)")
    else:
        print("  PASS: No direct EntitySourceLink constructors outside allowed files")

    # ── Report ──────────────────────────────────────────────────────────────
    total_violations = sum(len(v) for _, v in all_violations)
    print(f"\n{'='*70}")

    if total_violations == 0:
        print("PASS: No source provenance boundary violations found!")
        sys.exit(0)
    else:
        for section_name, violations in all_violations:
            if not violations:
                continue
            print(f"\n--- {section_name} ---")
            for v in violations:
                if v.line_num > 0:
                    print(f"  L{v.line_num} [{v.rule}] {v.description}")
                    print(f"    {v.line[:120]}")
                else:
                    print(f"  {v.description}")
                    if v.line:
                        print(f"    {v.line[:120]}")

        print(f"\n{'='*70}")
        print(f"FAIL: Found {total_violations} violation(s) across {len(all_violations)} check(s)")
        sys.exit(1)


if __name__ == '__main__':
    main()
