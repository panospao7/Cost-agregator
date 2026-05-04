#!/usr/bin/env python3
"""
Fix ALL "No value passed for parameter" errors.
Strategy: For each error, read the file, find the constructor call at that line,
and add the missing parameter. 

Usage: python fix_final.py
"""

import re
import os
import sys

TEST_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
ERROR_FILE = r"C:\Users\panos\.local\share\opencode\tool-output\fresh_errors.txt"

# Parse errors
def parse_errors():
    errors = []
    error_pattern = re.compile(
        r"e:\s*file:///C:.*?expensetracker/(.+?):(\d+):\d+\s+"
        r"No value passed for parameter '(\w+)'"
    )
    try:
        with open(ERROR_FILE, 'r', encoding='utf-8-sig') as f:
            for line in f:
                m = error_pattern.search(line)
                if m:
                    rel_path = m.group(1).replace('%20', ' ')
                    line_num = int(m.group(2))
                    param_name = m.group(3)
                    abs_path = os.path.join(TEST_DIR, rel_path)
                    errors.append((abs_path, line_num, param_name))
    except FileNotFoundError:
        print(f"Error file not found: {ERROR_FILE}")
        sys.exit(1)
    return errors

# For each param name, what to declare and what to pass
# "decl": None means it's a function-level param (don't add field)
# "pass": the value to pass to the constructor
FIXES = {
    "currencySettingsRepository": {
        "import": "import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository",
        "decl": '    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)',
    },
    "timeProvider": {
        "import": None,
        "decl": '    private val timeProvider = FakeTimeProvider()',
        "alt_decl": '    private val timeProvider = mockk<TimeProvider>(relaxed = true)',
    },
    "currencyConverter": {
        "import": "import com.yourname.expensetracker.domain.currency.CurrencyConverter",
        "decl": '    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)',
    },
    "privacyGate": {
        "import": "import com.yourname.expensetracker.domain.privacy.PrivacyGate",
        "decl": '    private val privacyGate = mockk<PrivacyGate>(relaxed = true)',
    },
    "multiCurrencyRepository": {
        "import": "import com.yourname.expensetracker.data.repository.MultiCurrencyRepository",
        "decl": '    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)',
    },
    "analyticsCurrencyNormalizer": {
        "import": "import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer",
        "decl": '    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)',
    },
    "receiptLifecycleCoordinator": {
        "import": "import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator",
        "decl": '    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true)',
    },
    "receiptLinkService": {
        "import": "import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService",
        "decl": '    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)',
    },
    "categoryRepository": {
        "import": "import com.yourname.expensetracker.data.repository.CategoryRepository",
        "decl": '    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)',
    },
    "expenseRepository": {
        "import": "import com.yourname.expensetracker.data.repository.ExpenseRepository",
        "decl": '    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)',
    },
    "cashFlowCalculator": {
        "import": "import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator",
        "decl": '    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)',
    },
    "restoreMaintenanceMode": {
        "import": "import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode",
        "decl": '    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)',
    },
    "recurringLifecycleCoordinator": {
        "import": None,
        "decl": '    private val recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)',
    },
    "recurringOccurrenceDao": {
        "import": "import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao",
        "decl": '    private val recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)',
    },
    "coordinator": {
        "import": "import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator",
        "decl": '    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)',
    },
    "legacyDataMigrationService": {
        "import": None,
        "decl": '    private val legacyDataMigrationService = mockk<LegacyDataMigrationService>(relaxed = true)',
    },
    "receiptParser": {
        "import": None,
        "decl": '    private val receiptParser = mockk<ReceiptParser>(relaxed = true)',
    },
    "forecastInputAssembler": {
        "import": None,
        "decl": '    private val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)',
    },
    "anomalyAlertRepository": {
        "import": None,
        "decl": '    private val anomalyAlertRepository = mockk<AnomalyAlertRepository>(relaxed = true)',
    },
    "monthlySavingsSweepUseCase": {
        "import": None,
        "decl": '    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)',
    },
    "recurringPatternsProvider": {
        "import": None,
        "decl": '    private val recurringPatternsProvider = mockk<RecurringPatternsProvider>(relaxed = true)',
    },
    "hybridExpenseClassifier": {
        "import": None,
        "decl": '    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)',
    },
    "categorizationEngine": {
        "import": None,
        "decl": '    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)',
    },
    "notificationService": {
        "import": None,
        "decl": '    private val notificationService = mockk<NotificationService>(relaxed = true)',
    },
    # Below are function-level params (not constructor fields)
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

# VALUES for function-level params
FUNCTION_VALUES = {
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

def add_import(content, import_line):
    if not import_line or import_line in content:
        return content
    lines = content.split('\n')
    last_import = -1
    for i, line in enumerate(lines):
        if line.startswith('import '):
            last_import = i
    if last_import >= 0:
        lines.insert(last_import + 1, import_line)
    return '\n'.join(lines)

def has_field(content, param_name):
    """Check if the param already has a field declaration."""
    for line in content.split('\n'):
        stripped = line.strip()
        if f"val {param_name}" in stripped or f"var {param_name}" in stripped or f"lateinit var {param_name}" in stripped:
            return True
    return False

def add_field(content, decl):
    """Add a field declaration to the file."""
    if not decl:
        return content
    
    # Extract param name from the declaration
    param_name = None
    for part in decl.split():
        if part.startswith("val ") and "=" in decl:
            param_name = part.split("val ")[1] if "val " in part else None
            break
    
    lines = content.split('\n')
    
    # Check if field already exists
    if param_name:
        for line in lines:
            if f"val {param_name}" in line or f"var {param_name}" in line or f"lateinit var {param_name}" in line:
                return content
    
    # Find insertion point
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
    
    return '\n'.join(lines)


def find_multi_line_constructor(lines, start_idx):
    """Starting from start_idx, find if there's a multi-line constructor.
    Returns (constructor_start, constructor_end) or None."""
    
    # Look for constructor start: variable = ClassName(
    for i in range(max(0, start_idx - 2), min(len(lines), start_idx + 2)):
        m = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\($', lines[i])
        if m:
            # Check if it's multi-line (paren not closed on same line, or has named params)
            indent = m.group(1)
            
            # Find matching close paren
            depth = 1
            for j in range(i + 1, len(lines)):
                depth += lines[j].count('(') - lines[j].count(')')
                if depth <= 0:
                    return (i, j, indent)
            
            return (i, len(lines) - 1, indent)
        
        # Also check single-line constructor: variable = ClassName(args)
        m2 = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\((.*)\)\s*$', lines[i])
        if m2:
            indent = m2.group(1)
            return (i, i, indent)
    
    return None


def fix_positional_constructor_single_line(lines, idx, param_name, param_value):
    """Fix a single-line positional constructor like: ClassName(arg1, arg2)"""
    line = lines[idx]
    m = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\((.*)\)\s*$', line)
    if not m:
        return False
    
    indent = m.group(1)
    var_name = m.group(2)
    class_name = m.group(3)
    args = m.group(4).rstrip()
    
    if param_name in args or param_value in args:
        return False
    
    # Add the new argument
    if args.strip():
        new_line = f"{indent}{var_name} = {class_name}({args}, {param_value})"
    else:
        new_line = f"{indent}{var_name} = {class_name}({param_value})"
    
    lines[idx] = new_line
    return True


def is_named_param(lines, start, end):
    """Check if the constructor between start and end uses named params."""
    for i in range(start + 1, end):
        if re.match(r'^\s+\w+\s*=', lines[i]):
            return True
    return False


def add_to_multi_line_constructor(lines, start, end, indent, param_name, param_value, is_named):
    """Add a parameter to a multi-line constructor."""
    # First check if param already present
    for i in range(start, end + 1):
        if param_name in lines[i] or param_value in lines[i]:
            return False
    
    inner_indent = indent + '    '
    
    if is_named:
        # Add: paramName = paramValue before closing paren
        new_line = f"{inner_indent}{param_name} = {param_value}"
        lines.insert(end, new_line)
        return True
    else:
        # Positional: add after the last argument
        # Check if last arg line ends with comma
        last_arg_idx = end - 1
        last_arg_line = lines[last_arg_idx].rstrip()
        
        if last_arg_line.endswith(','):
            # Already has comma, just add new param
            new_line = f"{inner_indent}{param_value}"
            lines.insert(end, new_line)
            return True
        elif last_arg_line.endswith(')'):
            # This means it's a nested constructor like: SomeClass(OtherClass(arg))
            # Add after the nested constructor's closing paren
            new_line = f"{inner_indent}{param_value}"
            lines.insert(end, new_line)
            return True
        else:
            # Add comma to last line and add new param
            lines[last_arg_idx] = last_arg_line + ','
            new_line = f"{inner_indent}{param_value}"
            lines.insert(end, new_line)
            return True


def fix_line_constructor(lines, idx, param_name, param_value):
    """Fix a constructor that is referenced at the given line index."""
    # Check if this line itself is a single-line constructor
    if re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\(.*\)\s*$', lines[idx]):
        return fix_positional_constructor_single_line(lines, idx, param_name, param_value)
    
    # Check if this is a multi-line constructor
    constr = find_multi_line_constructor(lines, idx)
    if constr:
        start, end, indent = constr
        is_named = is_named_param(lines, start, end)
        return add_to_multi_line_constructor(lines, start, end, indent, param_name, param_value, is_named)
    
    # Check if the PREVIOUS line starts a constructor
    if idx > 0:
        constr = find_multi_line_constructor(lines, idx - 1)
        if constr:
            start, end, indent = constr
            is_named = is_named_param(lines, start, end)
            return add_to_multi_line_constructor(lines, start, end, indent, param_name, param_value, is_named)
    
    # Check if NEXT line continues a constructor
    if idx + 1 < len(lines):
        constr = find_multi_line_constructor(lines, idx + 1)
        if constr:
            start, end, indent = constr
            is_named = is_named_param(lines, start, end)
            return add_to_multi_line_constructor(lines, start, end, indent, param_name, param_value, is_named)
    
    return False


def fix_file(file_errors):
    """Fix all errors in a file."""
    if not file_errors:
        return 0
    
    abs_path = file_errors[0][0]
    if not os.path.exists(abs_path):
        return 0
    
    with open(abs_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    
    # Get unique param names
    param_names = set(e[2] for e in file_errors)
    
    # Step 1: Add imports and field declarations
    for param_name in param_names:
        if param_name not in FIXES:
            continue
        
        fix = FIXES[param_name]
        
        # Add import
        if fix.get("import"):
            content = add_import(content, fix["import"])
        
        # Add field declaration (for constructor-level params, not function params)
        if fix.get("decl") and not has_field(content, param_name):
            content = add_field(content, fix["decl"])
    
    # Step 2: Fix constructor calls
    lines = content.split('\n')
    
    for abs_path, line_num, param_name in sorted(file_errors):
        if param_name not in FIXES:
            continue
        
        idx = line_num - 1  # Convert to 0-indexed
        
        if idx >= len(lines):
            continue
        
        # Determine the value to pass
        if FIXES[param_name].get("decl") is not None:
            # Constructor-level param: pass the field name
            param_value = param_name
        else:
            # Function-level param: pass the literal value
            param_value = FUNCTION_VALUES.get(param_name, param_name)
        
        # Try to fix this line
        if param_value:
            fix_line_constructor(lines, idx, param_name, param_value)
    
    content = '\n'.join(lines)
    
    if content != original:
        with open(abs_path, 'w', encoding='utf-8') as f:
            f.write(content)
        return len(param_names)
    
    return 0


def main():
    errors = parse_errors()
    print(f"Found {len(errors)} missing-param errors")
    
    # Group by file
    file_map = {}
    for abs_path, line_num, param_name in errors:
        if abs_path not in file_map:
            file_map[abs_path] = []
        file_map[abs_path].append((abs_path, line_num, param_name))
    
    print(f"Errors in {len(file_map)} files")
    
    total_fixed = 0
    total_files = 0
    
    for abs_path, file_errors in file_map.items():
        try:
            fixed = fix_file(file_errors)
            if fixed > 0:
                total_fixed += fixed
                total_files += 1
                param_names = [e[2] for e in file_errors if e[2] in FIXES]
                unique = list(set(param_names))
                print(f"  FIXED {unique} in {os.path.basename(abs_path)}")
        except Exception as e:
            print(f"  ERROR {os.path.basename(abs_path)}: {e}")
    
    print(f"\nFixed {total_files} files with {total_fixed} parameters added")

if __name__ == "__main__":
    main()
