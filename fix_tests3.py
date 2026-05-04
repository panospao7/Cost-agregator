#!/usr/bin/env python3
"""
Fix test compilation errors - v3.
This handles both named and positional constructor parameters correctly.
"""

import re
import os
import subprocess

TEST_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
PROJECT_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker"

# ── Parameter fix definitions ──────────────────────────────────────────────────
PARAM_FIXES = {
    "currencySettingsRepository": {
        "declaration": '    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository',
        "constructor_value": "currencySettingsRepository",
    },
    "timeProvider": {
        "declaration": '    private val timeProvider = FakeTimeProvider()',
        "import": None,
        "constructor_value": "timeProvider",
    },
    "currencyConverter": {
        "declaration": '    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.currency.CurrencyConverter',
        "constructor_value": "currencyConverter",
    },
    "privacyGate": {
        "declaration": '    private val privacyGate = mockk<PrivacyGate>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.privacy.PrivacyGate',
        "constructor_value": "privacyGate",
    },
    "multiCurrencyRepository": {
        "declaration": '    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.MultiCurrencyRepository',
        "constructor_value": "multiCurrencyRepository",
    },
    "analyticsCurrencyNormalizer": {
        "declaration": '    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer',
        "constructor_value": "analyticsCurrencyNormalizer",
    },
    "receiptLifecycleCoordinator": {
        "declaration": '    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator',
        "constructor_value": "receiptLifecycleCoordinator",
    },
    "receiptLinkService": {
        "declaration": '    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService',
        "constructor_value": "receiptLinkService",
    },
    "homeCurrency": {
        "declaration": None,
        "import": None,
        "constructor_value": '"EUR"',
    },
    "ioDispatcher": {
        "declaration": None,
        "import": None,
        "constructor_value": "testDispatcher",
    },
    "recurringOccurrenceDao": {
        "declaration": '    private val recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao',
        "constructor_value": "recurringOccurrenceDao",
    },
    "recurringLifecycleCoordinator": {
        "declaration": '    private val recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)',
        "import": None,
        "constructor_value": "recurringLifecycleCoordinator",
    },
    "expenseRepository": {
        "declaration": '    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.ExpenseRepository',
        "constructor_value": "expenseRepository",
    },
    "cashFlowCalculator": {
        "declaration": '    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator',
        "constructor_value": "cashFlowCalculator",
    },
    "categoryRepository": {
        "declaration": '    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.CategoryRepository',
        "constructor_value": "categoryRepository",
    },
    "restoreMaintenanceMode": {
        "declaration": '    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode',
        "constructor_value": "restoreMaintenanceMode",
    },
    "recurringPatternsProvider": {
        "declaration": '    private val recurringPatternsProvider = mockk<RecurringPatternsProvider>(relaxed = true)',
        "import": None,
        "constructor_value": "recurringPatternsProvider",
    },
    "coordinator": {
        "declaration": '    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator',
        "constructor_value": "coordinator",
    },
    "forecastInputAssembler": {
        "declaration": '    private val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)',
        "import": None,
        "constructor_value": "forecastInputAssembler",
    },
    "anomalyAlertRepository": {
        "declaration": '    private val anomalyAlertRepository = mockk<AnomalyAlertRepository>(relaxed = true)',
        "import": None,
        "constructor_value": "anomalyAlertRepository",
    },
    "monthlySavingsSweepUseCase": {
        "declaration": '    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)',
        "import": None,
        "constructor_value": "monthlySavingsSweepUseCase",
    },
    "receiptParser": {
        "declaration": '    private val receiptParser = mockk<ReceiptParser>(relaxed = true)',
        "import": None,
        "constructor_value": "receiptParser",
    },
    "legacyDataMigrationService": {
        "declaration": '    private val legacyDataMigrationService = mockk<LegacyDataMigrationService>(relaxed = true)',
        "import": None,
        "constructor_value": "legacyDataMigrationService",
    },
    "lastSeen": {
        "declaration": None,
        "import": None,
        "constructor_value": "0L",
    },
    "notificationService": {
        "declaration": '    private val notificationService = mockk<NotificationService>(relaxed = true)',
        "import": None,
        "constructor_value": "notificationService",
    },
    "categorizationEngine": {
        "declaration": '    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)',
        "import": None,
        "constructor_value": "categorizationEngine",
    },
    "hybridExpenseClassifier": {
        "declaration": '    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)',
        "import": None,
        "constructor_value": "hybridExpenseClassifier",
    },
    "bigText": {
        "declaration": None,
        "import": None,
        "constructor_value": '"test notification"',
    },
    "displayCurrency": {
        "declaration": None,
        "import": None,
        "constructor_value": '"EUR"',
    },
    "currency": {
        "declaration": None,
        "import": None,
        "constructor_value": '"EUR"',
    },
    "effectiveLimit": {
        "declaration": None,
        "import": None,
        "constructor_value": "1000.0",
    },
    "createdAt": {
        "declaration": None,
        "import": None,
        "constructor_value": "0L",
    },
    "updatedAt": {
        "declaration": None,
        "import": None,
        "constructor_value": "0L",
    },
    "priority": {
        "declaration": None,
        "import": None,
        "constructor_value": "PlannedExpensePriority.NORMAL",
    },
    "expiresAt": {
        "declaration": None,
        "import": None,
        "constructor_value": "0L",
    },
}

# Missing imports for types
TYPE_IMPORTS = {
    "CurrencySettingsRepository": "com.yourname.expensetracker.domain.currency.CurrencySettingsRepository",
    "CurrencyConverter": "com.yourname.expensetracker.domain.currency.CurrencyConverter",
    "PrivacyGate": "com.yourname.expensetracker.domain.privacy.PrivacyGate",
    "MultiCurrencyRepository": "com.yourname.expensetracker.data.repository.MultiCurrencyRepository",
    "AnalyticsCurrencyNormalizer": "com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer",
    "ReceiptLifecycleCoordinator": "com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator",
    "ReceiptLinkService": "com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService",
    "ExpenseRepository": "com.yourname.expensetracker.data.repository.ExpenseRepository",
    "CashFlowCalculator": "com.yourname.expensetracker.domain.cashflow.CashFlowCalculator",
    "CategoryRepository": "com.yourname.expensetracker.data.repository.CategoryRepository",
    "RestoreMaintenanceMode": "com.yourname.expensetracker.data.backup.RestoreMaintenanceMode",
    "TransactionLifecycleCoordinator": "com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator",
    "RecurringOccurrenceDao": "com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao",
}


def get_errors():
    """Read errors from pre-captured file."""
    errors = []
    pattern = re.compile(
        r"e:\s*file:///C:.*?expensetracker/(.+?):(\d+):(\d+)\s+"
        r"No value passed for parameter '(\w+)'"
    )
    
    # Try the cmd-captured file first, then the other ones
    for fname in ["errors3.txt", "errors2.txt", "compile_errors_clean.txt"]:
        fpath = os.path.join(r"C:\Users\panos\.local\share\opencode\tool-output", fname)
        if os.path.exists(fpath):
            try:
                with open(fpath, 'r', encoding='utf-8') as f:
                    for line in f:
                        m = pattern.search(line)
                        if m:
                            rel_path = m.group(1).replace('%20', ' ')
                            line_num = int(m.group(2))
                            col_num = int(m.group(3))
                            param_name = m.group(4)
                            abs_path = os.path.join(TEST_DIR, rel_path)
                            errors.append((abs_path, line_num, col_num, param_name))
            except:
                pass
            if errors:
                break
    
    return errors, 0


def add_import(content, import_line):
    if import_line is None or import_line in content:
        return content
    lines = content.split('\n')
    last_import = -1
    for i, line in enumerate(lines):
        if line.startswith('import '):
            last_import = i
    if last_import >= 0:
        lines.insert(last_import + 1, import_line)
    return '\n'.join(lines)


def add_field(content, decl_line):
    if decl_line is None:
        return content
    lines = content.split('\n')
    # Check if already present
    for line in lines:
        if decl_line.strip() in line:
            return content
    
    # Find insertion point: after last field decl, before @Before/setup
    insert_idx = None
    for i, line in enumerate(lines):
        if re.match(r'^\s+private (val|var|lateinit var) \w+', line):
            insert_idx = i + 1
    if insert_idx is None:
        for i, line in enumerate(lines):
            if re.match(r'^\s*(override fun setup|@Before|fun setup)', line):
                insert_idx = i
    if insert_idx is None:
        # Insert before class closing or after class opening
        for i, line in enumerate(lines):
            if re.match(r'^class ', line):
                insert_idx = i + 1
                break
    
    if insert_idx is not None:
        # Check if the decl already exists nearby
        nearby = '\n'.join(lines[max(0, insert_idx-2):insert_idx+5])
        if decl_line.strip() in nearby:
            return content
        lines.insert(insert_idx, decl_line)
    return '\n'.join(lines)


def find_constructor_call_at_line(lines, line_idx):
    """Find the constructor invocation that contains the given line index.
    
    Looks backwards to find the start of the constructor call.
    Returns (start_idx, end_idx, indent) or None.
    """
    if line_idx >= len(lines):
        return None
    
    line = lines[line_idx]
    
    # Check if this line is inside a constructor call: look backwards for "= ClassName("
    for start in range(line_idx, -1, -1):
        sl = lines[start]
        # Look for pattern: variable = ClassName(
        m = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\($', sl)
        if m:
            # Found the start of the constructor call
            indent = m.group(1)
            # Find the matching close paren
            depth = 1
            for end in range(start + 1, len(lines)):
                depth += lines[end].count('(') - lines[end].count(')')
                if depth <= 0:
                    return (start, end, indent)
            return (start, len(lines) - 1, indent)
        elif re.match(r'^\s*\)', sl) or re.match(r'^\s*\}', sl):
            # We went too far back (hit a closing paren)
            break
        elif re.match(r'^class ', sl) or re.match(r'^fun ', sl):
            break
    
    return None


def add_param_to_constructor(lines, constructor_start, constructor_end, param_name, param_value):
    """Add a parameter to a constructor call.
    
    For named params: add `, paramName = paramValue` before the closing paren
    For positional params: add `, paramValue` after the last arg
    """
    # Get the constructor text
    constr_lines = lines[constructor_start:constructor_end + 1]
    
    # Check if constructor uses named params
    has_named = False
    for cl in constr_lines[1:-1]:  # Skip first and last lines
        if re.match(r'^\s+\w+\s*=', cl):
            has_named = True
            break
    
    # Check if param already in constructor
    constr_text = '\n'.join(constr_lines)
    if param_name in constr_text or param_value in constr_text:
        return False
    
    closing_line = constructor_end
    closing_indent = re.match(r'^(\s*)', lines[closing_line]).group(1)
    inner_indent = closing_indent + '    '
    
    if has_named:
        # Add before closing paren: indent + "    " + paramName + " = " + paramValue
        new_line = inner_indent + param_name + " = " + param_value
        lines.insert(closing_line, new_line)
    else:
        # Positional: add before closing paren
        # First check if the last arg ends with a comma
        last_arg_line = constr_lines[-2] if len(constr_lines) > 1 else constr_lines[0]
        last_arg_line_idx = constructor_end - 1
        
        if last_arg_line.strip().endswith(','):
            new_line = inner_indent + param_value
        elif last_arg_line.strip().endswith('('):
            # Single-line constructor like: ClassName(arg1)
            # Need to change it to multi-line or just append
            if len(constr_lines) == 1:
                # Single line: ClassName(arg1) -> ClassName(arg1, arg2)
                line = lines[constructor_start]
                # Find the opening paren
                paren_idx = line.index('(')
                before_paren = line[:paren_idx + 1]
                after_parsed = line[paren_idx + 1:]
                # Remove trailing ws and closing paren
                after = after_parsed.rstrip()
                if after.endswith(')'):
                    after = after[:-1].rstrip()
                    if after:
                        new_line = before_paren + after + ', ' + param_value + ')'
                    else:
                        new_line = before_paren + param_value + ')'
                    lines[constructor_start] = new_line
                    return True
            # Multi-line inline
            new_line = inner_indent + param_value
            lines.insert(closing_line, new_line)
        else:
            # Last line has a trailing argument without comma
            # Append comma to the last arg line and add new line
            lines[last_arg_line_idx] = last_arg_line.rstrip() + ','
            new_line = inner_indent + param_value
            lines.insert(closing_line, new_line)
    
    return True


def fix_file(abs_path, file_errors):
    """Fix all errors in a single file."""
    with open(abs_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    
    # For each type of fix needed, add import and field if needed
    param_names = list(set(p[3] for p in file_errors))  # unique param names
    
    for param_name in param_names:
        if param_name not in PARAM_FIXES:
            continue
        fix = PARAM_FIXES[param_name]
        
        # Add import
        if fix["import"] and fix["import"] not in content:
            content = add_import(content, fix["import"])
        
        # Add field declaration if appropriate for this param
        if fix["declaration"] is not None:
            # Check if a similar declaration already exists
            decl_stripped = fix["declaration"].strip()
            if decl_stripped not in content:
                # Check for alternative patterns
                has_field = False
                for line in content.split('\n'):
                    if param_name in line and ('val ' + param_name in line or 'var ' + param_name in line or 'lateinit var ' + param_name in line):
                        has_field = True
                        break
                if not has_field:
                    content = add_field(content, fix["declaration"])
    
    # Now fix constructor calls
    lines = content.split('\n')
    
    # Group errors by line number to avoid processing same line twice
    processed_lines = set()
    
    for abs_p, line_num, col_num, param_name in sorted(file_errors):
        if param_name not in PARAM_FIXES:
            continue
        
        fix = PARAM_FIXES[param_name]
        if (abs_p, line_num) in processed_lines:
            continue
        
        # The error line might have shifted due to our insertions
        # But we track the original line number. After insertions, we need
        # to re-read or track offset. For now, use the original content indices.
        
        # Since we're modifying `lines` as we go, we need to be careful.
        # Let's use the original line number and track an offset.
        
        # Actually, let's just try a different approach: find ALL constructor calls
        # that need fixing, not based on line numbers but on content analysis.
        
        # For now, use the line number from the error report (which is now stale
        # after insertions). We need to re-derive.
        processed_lines.add((abs_p, line_num))
    
    # Better approach: after adding imports and fields, find all constructor calls
    # that are missing the needed params
    
    # Actually, let me re-read the content after our modifications
    content_after_imports = content
    lines = content_after_imports.split('\n')
    
    for param_name in param_names:
        if param_name not in PARAM_FIXES:
            continue
        
        fix = PARAM_FIXES[param_name]
        param_value = fix["constructor_value"]
        
        # Skip if the param name already appears as a constructor argument
        # (e.g., `paramName = value` or `, paramName`)
        already_fixed = False
        for line in lines:
            if re.search(r'\b' + param_name + r'\s*=', line) or re.search(r',\s*' + re.escape(param_value) + r'\s*[\),]', line):
                # Check it's used as a named param or positional arg
                pass
        
        # Find constructor calls that contain the known params but not this one
        i = 0
        while i < len(lines):
            line = lines[i]
            # Look for constructor start pattern
            m = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\($', line)
            if not m:
                m = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\((.*)$', line)
            
            if m:
                indent = m.group(1)
                # Check if this constructor spans multiple lines
                if '(' in line and ')' not in line[line.index('('):]:
                    # Multi-line: find the matching close paren
                    depth = 1
                    j = i + 1
                    while j < len(lines) and depth > 0:
                        depth += lines[j].count('(') - lines[j].count(')')
                        j += 1
                    end_idx = j - 1
                    
                    # Get constructor text
                    constr_text = '\n'.join(lines[i:end_idx + 1])
                    
                    # Does this constructor already have our param?
                    if param_name in constr_text or param_value in constr_text:
                        i = end_idx + 1
                        continue
                    
                    # Does this constructor have OTHER params that suggest it's the right one?
                    # Check if it has any of the known params from this file
                    # Just check if it looks like a constructor call (has params)
                    has_params = False
                    for cl in lines[i+1:end_idx]:
                        if cl.strip() and not cl.strip().startswith(')'):
                            has_params = True
                            break
                    
                    if has_params:
                        # Try to add the param
                        added = add_param_to_constructor(lines, i, end_idx, param_name, param_value)
                        if added:
                            # After adding, the end_idx shifts by 1
                            end_idx += 1
                    
                    i = end_idx + 1
                    continue
                elif ')' in line:
                    # Single-line constructor: ClassName(arg1, arg2)
                    # Check if param is missing
                    if param_name not in line and param_value not in line:
                        # Try to add at the end before the closing paren
                        paren_pos = line.rindex(')')
                        before = line[:paren_pos].rstrip()
                        after = line[paren_pos:]
                        if before.endswith(',') or before.endswith(', '):
                            new_line = before + ' ' + param_value + after
                        elif before.endswith('('):
                            new_line = before + param_value + after
                        else:
                            new_line = before + ', ' + param_value + after
                        lines[i] = new_line
                    i += 1
                    continue
            
            i += 1
    
    new_content = '\n'.join(lines)
    
    if new_content != original:
        with open(abs_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        return True
    
    return False


def main():
    errors, returncode = get_errors()
    print(f"Found {len(errors)} missing-param errors")
    print(f"Gradle exit code: {returncode}")
    
    # Group by file
    file_map = {}
    for abs_path, line_num, col_num, param_name in errors:
        if abs_path not in file_map:
            file_map[abs_path] = []
        file_map[abs_path].append((abs_path, line_num, col_num, param_name))
    
    print(f"Errors in {len(file_map)} files")
    
    fixed_files = 0
    fixed_params = 0
    
    for abs_path, file_errors in file_map.items():
        if not os.path.exists(abs_path):
            continue
        
        # Unique param names for this file
        param_names = set(e[3] for e in file_errors)
        fixable = [p for p in param_names if p in PARAM_FIXES]
        if not fixable:
            continue
        
        try:
            changed = fix_file(abs_path, file_errors)
            if changed:
                fixed_files += 1
                fixed_params += len(fixable)
                print(f"  FIXED {fixable} in {os.path.basename(abs_path)}")
        except Exception as e:
            print(f"  ERROR {os.path.basename(abs_path)}: {e}")
    
    print(f"\nTotal: {fixed_files} files fixed, {fixed_params} parameters added")


if __name__ == "__main__":
    main()
