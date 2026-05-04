#!/usr/bin/env python3
"""
Final fix script for all test compilation errors.

Strategy:
1. Read errors from gradle compile output
2. For each file with errors:
   a. Add missing imports
   b. Add missing field declarations 
   c. For each error, identify the constructor at the error line and add the param
3. Test on a batch, verify, then run on all files

Usage: python fix_all.py [--batch N]
"""

import re
import os
import subprocess
import sys

TEST_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
PROJECT_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker"

# ── Parameter definitions ──────────────────────────────────────────────────────
# "decl": field declaration (None for function-level params)
# "import": import statement (None if not needed)

PARAMS = {
    "currencySettingsRepository": {
        "import": "import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository",
        "decl": "    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)",
    },
    "timeProvider": {
        "import": None,
        "decl": "    private val timeProvider = FakeTimeProvider()",
    },
    "currencyConverter": {
        "import": "import com.yourname.expensetracker.domain.currency.CurrencyConverter",
        "decl": "    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)",
    },
    "privacyGate": {
        "import": "import com.yourname.expensetracker.domain.privacy.PrivacyGate",
        "decl": "    private val privacyGate = mockk<PrivacyGate>(relaxed = true)",
    },
    "multiCurrencyRepository": {
        "import": "import com.yourname.expensetracker.data.repository.MultiCurrencyRepository",
        "decl": "    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)",
    },
    "analyticsCurrencyNormalizer": {
        "import": "import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer",
        "decl": "    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)",
    },
    "receiptLifecycleCoordinator": {
        "import": "import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator",
        "decl": "    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true)",
    },
    "receiptLinkService": {
        "import": "import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService",
        "decl": "    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)",
    },
    "categoryRepository": {
        "import": "import com.yourname.expensetracker.data.repository.CategoryRepository",
        "decl": "    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)",
    },
    "expenseRepository": {
        "import": "import com.yourname.expensetracker.data.repository.ExpenseRepository",
        "decl": "    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)",
    },
    "cashFlowCalculator": {
        "import": "import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator",
        "decl": "    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)",
    },
    "restoreMaintenanceMode": {
        "import": "import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode",
        "decl": "    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)",
    },
    "recurringLifecycleCoordinator": {
        "import": None,
        "decl": "    private val recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)",
    },
    "recurringOccurrenceDao": {
        "import": "import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao",
        "decl": "    private val recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)",
    },
    "coordinator": {
        "import": "import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator",
        "decl": "    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)",
    },
    "legacyDataMigrationService": {
        "import": None,
        "decl": "    private val legacyDataMigrationService = mockk<LegacyDataMigrationService>(relaxed = true)",
    },
    "receiptParser": {
        "import": None,
        "decl": "    private val receiptParser = mockk<ReceiptParser>(relaxed = true)",
    },
    "forecastInputAssembler": {
        "import": None,
        "decl": "    private val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)",
    },
    "anomalyAlertRepository": {
        "import": None,
        "decl": "    private val anomalyAlertRepository = mockk<AnomalyAlertRepository>(relaxed = true)",
    },
    "monthlySavingsSweepUseCase": {
        "import": None,
        "decl": "    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)",
    },
    "recurringPatternsProvider": {
        "import": None,
        "decl": "    private val recurringPatternsProvider = mockk<RecurringPatternsProvider>(relaxed = true)",
    },
    "hybridExpenseClassifier": {
        "import": None,
        "decl": "    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)",
    },
    "categorizationEngine": {
        "import": None,
        "decl": "    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)",
    },
    "notificationService": {
        "import": None,
        "decl": "    private val notificationService = mockk<NotificationService>(relaxed = true)",
    },
    # Function-level params - no field or import needed
    "homeCurrency": {"decl": None, "import": None},
    "ioDispatcher": {"decl": None, "import": None},
    "displayCurrency": {"decl": None, "import": None},
    "currency": {"decl": None, "import": None},
    "effectiveLimit": {"decl": None, "import": None},
    "createdAt": {"decl": None, "import": None},
    "updatedAt": {"decl": None, "import": None},
    "bigText": {"decl": None, "import": None},
    "lastSeen": {"decl": None, "import": None},
    "expiresAt": {"decl": None, "import": None},
    "priority": {"decl": None, "import": None},
}

# Values for function-level params
FN_VALUES = {
    "homeCurrency": '"EUR"',
    "ioDispatcher": "testDispatcher",
    "displayCurrency": '"EUR"',
    "currency": '"EUR"',
    "effectiveLimit": "1000.0",
    "createdAt": "0L",
    "updatedAt": "0L",
    "bigText": '"test notification text"',
    "lastSeen": "0L",
    "expiresAt": "0L",
    "priority": "PlannedExpensePriority.NORMAL",
}


def get_errors():
    """Read errors from pre-captured file or run gradle."""
    error_file = os.path.join(r"C:\Users\panos\.local\share\opencode\tool-output", "fresh3.txt")
    
    errors = []
    pat = re.compile(r"expensetracker/(.+?):(\d+):\d+\s+No value passed for parameter '(\w+)'")
    
    # Try reading from file first
    if os.path.exists(error_file):
        import codecs
        with codecs.open(error_file, 'r', encoding='utf-8-sig') as f:
            for line in f:
                m = pat.search(line)
                if m:
                    rel_path = m.group(1).replace('%20', ' ')
                    line_num = int(m.group(2))
                    param = m.group(3)
                    abs_path = os.path.join(TEST_DIR, rel_path)
                    errors.append((abs_path, line_num, param))
        
        if errors:
            return errors
    
    # Fall back to running gradle
    result = subprocess.run(
        ["./gradlew.bat", ":app:compileDebugUnitTestKotlin", "--no-daemon"],
        cwd=PROJECT_DIR, capture_output=True, timeout=300
    )
    stderr = result.stderr.decode('utf-8', errors='replace')
    
    for line in stderr.split('\n'):
        m = pat.search(line)
        if m:
            rel_path = m.group(1).replace('%20', ' ')
            line_num = int(m.group(2))
            param = m.group(3)
            abs_path = os.path.join(TEST_DIR, rel_path)
            errors.append((abs_path, line_num, param))
    
    return errors


def fix_file(file_errors):
    """Fix all errors in one file."""
    if not file_errors:
        return 0
    
    abs_path = file_errors[0][0]
    if not os.path.exists(abs_path):
        return 0
    
    with open(abs_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    params_set = set(e[2] for e in file_errors)
    
    # Step 1: Add imports
    for p in params_set:
        if p not in PARAMS or not PARAMS[p].get("import"):
            continue
        imp = PARAMS[p]["import"]
        if imp not in content:
            lines = content.split('\n')
            last_import = -1
            for i, line in enumerate(lines):
                if line.startswith('import '):
                    last_import = i
            if last_import >= 0:
                lines.insert(last_import + 1, imp)
                content = '\n'.join(lines)
    
    # Step 2: Add field declarations
    for p in params_set:
        if p not in PARAMS or not PARAMS[p].get("decl"):
            continue
        decl = PARAMS[p]["decl"]
        # Check if field already declared
        has_field = False
        for line in content.split('\n'):
            s = line.strip()
            if f"val {p}" in s or f"var {p}" in s or f"lateinit var {p}" in s:
                has_field = True
                break
        if has_field:
            continue
        
        lines = content.split('\n')
        insert_idx = None
        for i, line in enumerate(lines):
            if re.match(r'^\s+private (val|var|lateinit var) \w+', line):
                insert_idx = i + 1
        if insert_idx is None:
            for i, line in enumerate(lines):
                if re.match(r'^\s*(override fun setup|@Before|fun setup)', line):
                    insert_idx = i
        if insert_idx is None:
            for i, line in enumerate(lines):
                if re.match(r'^class ', line):
                    insert_idx = i + 1
        
        if insert_idx is not None:
            lines.insert(insert_idx, decl)
            content = '\n'.join(lines)
    
    # Step 3: Fix constructor calls
    lines = content.split('\n')
    total_fixes = 0
    
    for abs_path, line_num, param_name in sorted(file_errors, key=lambda x: x[1], reverse=True):
        if param_name not in PARAMS:
            continue
        
        idx = line_num - 1  # 0-indexed
        if idx >= len(lines):
            continue
        
        fix = PARAMS[param_name]
        if fix.get("decl") is not None:
            # Constructor-level param
            param_value = param_name
        else:
            # Function-level param
            param_value = FN_VALUES.get(param_name, param_name)
        
        # Try to find the constructor that contains this line
        # Look backwards for `ClassName(\n` or `= ClassName(`
        constr_start = None
        for i in range(idx, -1, -1):
            # Check for constructor start pattern
            m = re.match(r'^(\s*)(\w+\s*=\s*)?(\w+)\s*\(\s*$', lines[i])
            if m:
                constr_start = i
                break
            # Check for single-line constructor: `ClassName(args)`
            m2 = re.match(r'^(\s*)(\w+\s*=\s*)?(\w+)\(.*\)\s*$', lines[i])
            if m2:
                constr_start = i
                break
            # If we hit a standalone closing paren or class/fun declaration, stop
            if re.match(r'^\s*\)', lines[i]) or re.match(r'^class ', lines[i]) or re.match(r'^fun ', lines[i]):
                break
        
        if constr_start is None:
            continue
        
        # Check if single-line constructor
        single = re.match(r'^(\s*)(\w+\s*=\s*)?(\w+)\((.*)\)\s*$', lines[constr_start])
        if single:
            indent = single.group(1)
            var_class = single.group(2) or ''
            class_name = single.group(3)
            args = single.group(4).strip()
            
            if param_name in args or param_value in args:
                continue
            
            if args:
                lines[constr_start] = f"{indent}{var_class}{class_name}({args}, {param_value})"
            else:
                lines[constr_start] = f"{indent}{var_class}{class_name}({param_value})"
            total_fixes += 1
            continue
        
        # Multi-line constructor
        # Find the matching close paren
        depth = 1
        constr_end = None
        for i in range(constr_start + 1, len(lines)):
            depth += lines[i].count('(') - lines[i].count(')')
            if depth <= 0:
                constr_end = i
                break
        
        if constr_end is None:
            continue
        
        # Get constructor text to check if param already there
        constr_text = '\n'.join(lines[constr_start:constr_end + 1])
        if param_name in constr_text or param_value in constr_text:
            continue
        
        # Check if named or positional
        is_named = False
        for i in range(constr_start + 1, constr_end):
            if re.match(r'^\s+\w+\s*=', lines[i]):
                is_named = True
                break
        
        indent = re.match(r'^(\s*)', lines[constr_start]).group(1)
        inner = indent + '    '
        
        # The error line is the LAST provided argument line
        # Add the param AFTER this line, with proper comma handling
        last_arg_line = idx
        
        # Check if this is the exact error line we should fix
        # (The error might point to the constructor start, not the last arg)
        if idx == constr_start:
            # Error on the constructor start line - this happens for named params
            # Find the actual last argument line
            last_arg_line = constr_end - 1
        
        # Add comma to the last arg line if not already present
        cur_line = lines[last_arg_line].rstrip()
        if not cur_line.endswith(',') and not cur_line.endswith('('):
            lines[last_arg_line] = cur_line + ','
        
        # Add new param line
        if is_named:
            new_line = f"{inner}{param_name} = {param_value}"
        else:
            new_line = f"{inner}{param_value}"
        
        # Insert after the last arg line (before the closing paren)
        lines.insert(constr_end, new_line)
        total_fixes += 1
    
    content = '\n'.join(lines)
    
    if content != original:
        with open(abs_path, 'w', encoding='utf-8') as f:
            f.write(content)
    
    return total_fixes


def main():
    batch = None
    if len(sys.argv) > 1 and sys.argv[1].startswith('--batch='):
        batch = int(sys.argv[1].split('=')[1])
    
    errors = get_errors()
    print(f"Found {len(errors)} missing-param errors")
    
    # Group by file
    file_map = {}
    for abs_path, line_num, param in errors:
        if abs_path not in file_map:
            file_map[abs_path] = []
        file_map[abs_path].append((abs_path, line_num, param))
    
    print(f"Errors in {len(file_map)} files")
    
    # Fix files
    if batch:
        file_list = list(file_map.items())[:batch]
        print(f"Fixing batch of {batch} files...")
    else:
        file_list = list(file_map.items())
    
    total_fixed = 0
    files_fixed = 0
    
    for abs_path, file_errors in file_list:
        try:
            fixed = fix_file(file_errors)
            if fixed > 0:
                total_fixed += fixed
                files_fixed += 1
                params = list(set(e[2] for e in file_errors if e[2] in PARAMS))
                print(f"  FIXED {len(params)} params in {os.path.basename(abs_path)}: {params}")
        except Exception as e:
            print(f"  ERROR {os.path.basename(abs_path)}: {e}")
    
    print(f"\nFixed {files_fixed} files, {total_fixed} params added")
    
    if batch:
        print("\nRe-run without --batch to fix remaining files")


if __name__ == "__main__":
    main()
