#!/usr/bin/env python3
"""
Static money-boundary guard for ExpenseTracker.

Prevents regression back to mixed-currency arithmetic by detecting:
- Raw sumOf { it.amount } or sumOf { it.effectiveAmount }
- Silent EUR fallback patterns
- Raw conversion fallback (?: effectiveAmount)
- Ambiguous legacy APIs

CURR-C62-17: Static guard to prevent mixed-currency regressions.
"""

import re
import sys
from pathlib import Path
from typing import List, Tuple

# Patterns that indicate mixed-currency bugs
FORBIDDEN_PATTERNS = [
    # Raw mixed-currency sums
    (r'sumOf\s*\{\s*it\.amount\s*\}', 'Raw sumOf { it.amount } without currency check'),
    (r'sumOf\s*\{\s*it\.effectiveAmount\s*\}', 'Raw sumOf { it.effectiveAmount } without currency check'),
    
    # Silent EUR fallback in financial math
    (r'\.getOrDefault\s*\(\s*"EUR"\s*\)', 'Silent EUR fallback in financial calculation'),
    
    # Raw conversion fallback
    (r'\?\s*:\s*\w*\.?effectiveAmount', 'Raw effectiveAmount fallback on conversion failure'),
    (r'\?\s*:\s*\w*\.?amount(?!.*convert)', 'Raw amount fallback on conversion failure'),
    
    # Deprecated legacy APIs (should be internal or removed)
    (r'Result<\s*Double\s*>', 'Legacy Result<Double> aggregate API'),
    (r'Map<[^>]+,\s*Double\s*>', 'Legacy Map<..., Double> aggregate API'),
]

# Allowlist patterns (legitimate uses)
ALLOWLIST_PATTERNS = [
    r'MoneyNormalizationEngine\.kt',
    r'MoneyAggregateBuilder\.kt',
    r'MoneyAggregate\.kt',
    r'/test/',
    r'/androidTest/',
    r'Test\.kt$',
    r'Spec\.kt$',
    r'Fixture\.kt$',
    r'Mock\.kt$',
    r'Fake\.kt$',
    # Row display code
    r'formatDisplay',
    r'formatAmount',
    r'displayAmount',
    # Source bucket construction before conversion
    r'MoneyBucket\(',
    r'sourceBuckets',
    # Migration files
    r'Migration_',
    r'AppDatabase\.kt',
    # UI components showing original amounts
    r'BentoCard\.kt',
    r'ExpenseRow\.kt',
]

def is_allowlisted(file_path: str, line: str) -> bool:
    """Check if file or line is allowlisted."""
    for pattern in ALLOWLIST_PATTERNS:
        if re.search(pattern, file_path) or re.search(pattern, line):
            return True
    return False

def check_file(file_path: Path) -> List[Tuple[int, str, str]]:
    """Check a single Kotlin file for money boundary violations."""
    violations = []
    
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"Warning: Could not read {file_path}: {e}", file=sys.stderr)
        return violations
    
    for line_num, line in enumerate(lines, start=1):
        if is_allowlisted(str(file_path), line):
            continue
        
        for pattern, description in FORBIDDEN_PATTERNS:
            if re.search(pattern, line):
                violations.append((line_num, description, line.strip()))
    
    return violations

def main():
    """Scan all Kotlin files in app/src/main for money boundary violations."""
    root = Path(__file__).parent.parent
    src_dir = root / 'app' / 'src' / 'main' / 'java'
    
    if not src_dir.exists():
        print(f"Error: Source directory not found: {src_dir}", file=sys.stderr)
        sys.exit(1)
    
    kotlin_files = list(src_dir.rglob('*.kt'))
    print(f"Scanning {len(kotlin_files)} Kotlin files for money boundary violations...")
    
    total_violations = 0
    files_with_violations = 0
    
    for kt_file in kotlin_files:
        violations = check_file(kt_file)
        if violations:
            files_with_violations += 1
            total_violations += len(violations)
            print(f"\n❌ {kt_file.relative_to(root)}:")
            for line_num, description, line in violations:
                print(f"  Line {line_num}: {description}")
                print(f"    {line}")
    
    print(f"\n{'='*70}")
    if total_violations == 0:
        print("✅ No money boundary violations found!")
        sys.exit(0)
    else:
        print(f"❌ Found {total_violations} violation(s) in {files_with_violations} file(s)")
        print("\nMoney boundary violations prevent mixed-currency arithmetic bugs.")
        print("If these are legitimate uses, add them to the allowlist in this script.")
        sys.exit(1)

if __name__ == '__main__':
    main()
