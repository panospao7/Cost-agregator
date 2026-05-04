#!/usr/bin/env python3
"""
Precise fixer for missing constructor parameters.

For each error (file, line, param):
- The error line is the LAST argument line before the closing paren
- OR the single-line constructor call

Strategy:
1. For multi-line constructors: find the constructor start, determine named/positional,
   add comma to current line, insert new param line before the closing paren.
2. For single-line constructors: add param inside the parens.
"""

import re
import os

TEST_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
ERROR_FILE = r"C:\Users\panos\.local\share\opencode\tool-output\fresh_errors2.txt"

FIXES = {
    "currencySettingsRepository": {
        "import": "import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository",
        "decl": '    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)',
    },
    "timeProvider": {
        "import": None,
        "decl": '    private val timeProvider = FakeTimeProvider()',
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
    # Function-level params (no field declaration needed)
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

# Values for function-level params when they need to be passed
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

def parse_errors():
    errors = []
    with open(ERROR_FILE, 'r', encoding='utf-8-sig') as f:
        for line in f:
            m = re.search(r"expensetracker/(.+?):(\d+):\d+\s+No value passed for parameter '(\w+)'", line)
            if m:
                rel_path = m.group(1).replace('%20', ' ')
                line_num = int(m.group(2))
                param_name = m.group(3)
                abs_path = os.path.join(TEST_DIR, rel_path)
                errors.append((abs_path, line_num, param_name))
    return errors


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
    """Check if field already declared."""
    for line in content.split('\n'):
        s = line.strip()
        if f"val {param_name}" in s or f"var {param_name}" in s or f"lateinit var {param_name}" in s:
            return True
    return False


def add_field(content, decl):
    if not decl:
        return content
    lines = content.split('\n')
    
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


def fix_errors_in_file(file_errors):
    """Fix all errors in one file."""
    if not file_errors:
        return 0
    
    abs_path = file_errors[0][0]
    if not os.path.exists(abs_path):
        return 0
    
    with open(abs_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    
    # Group unique param names
    all_param_names = set(e[2] for e in file_errors)
    
    # Step 1: Add imports and field declarations
    for param_name in all_param_names:
        if param_name not in FIXES:
            continue
        fix = FIXES[param_name]
        if fix.get("import"):
            content = add_import(content, fix["import"])
        if fix.get("decl") and not has_field(content, param_name):
            content = add_field(content, fix["decl"])
    
    # Step 2: Fix each error location
    lines = content.split('\n')
    param_names_at_each_line = {}
    
    for abs_path, line_num, param_name in file_errors:
        if param_name not in FIXES:
            continue
        
        # 0-indexed
        idx = line_num - 1
        if idx >= len(lines):
            continue
        
        if idx not in param_names_at_each_line:
            param_names_at_each_line[idx] = []
        param_names_at_each_line[idx].append(param_name)
    
    # Process from bottom to top to keep line numbers stable
    for idx in sorted(param_names_at_each_line.keys(), reverse=True):
        params = param_names_at_each_line[idx]
        
        # Get the line
        cur_line = lines[idx]
        stripped = cur_line.strip()
        
        # Check if this is a single-line constructor: `var = ClassName(args)`
        single_line_match = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\((.*)\)\s*$', cur_line)
        
        if single_line_match:
            # Single-line constructor
            indent = single_line_match.group(1)
            var_name = single_line_match.group(2)
            class_name = single_line_match.group(3)
            args = single_line_match.group(4).rstrip()
            
            for param_name in params:
                fix = FIXES[param_name]
                if fix.get("decl") is not None:
                    param_value = param_name
                else:
                    param_value = FUNCTION_VALUES.get(param_name, param_name)
                
                if param_name in args or param_value in args:
                    continue
                
                if args.strip():
                    args = args + ", " + param_value
                else:
                    args = param_value
            
            new_line = f"{indent}{var_name} = {class_name}({args})"
            lines[idx] = new_line
        else:
            # Multi-line constructor: the error line is the LAST ARG LINE
            # We need to add comma to this line and insert new param line(s)
            
            # First, try to find the constructor start and determine if named or positional
            # Look backwards from idx to find `= ClassName(`
            constr_start = None
            for i in range(idx, -1, -1):
                if re.match(r'^(\s*)(\w+\s*=\s*)?(\w+)\s*\($', lines[i]):
                    constr_start = i
                    break
                elif re.match(r'^\s*\)', lines[i]) or re.match(r'^class ', lines[i]) or re.match(r'^fun ', lines[i]):
                    break
            
            if constr_start is None:
                continue
            
            # Determine if named or positional
            is_named = False
            for i in range(constr_start + 1, idx + 1):
                if re.match(r'^\s+\w+\s*=', lines[i]):
                    is_named = True
                    break
            
            # Find the matching close paren for this constructor
            depth = 1
            constr_end = None
            for i in range(constr_start + 1, len(lines)):
                depth += lines[i].count('(') - lines[i].count(')')
                if depth <= 0:
                    constr_end = i
                    break
            
            if constr_end is None:
                constr_end = len(lines) - 1
            
            # Determine indent from the constructor start
            indent_match = re.match(r'^(\s*)', lines[constr_start])
            base_indent = indent_match.group(1) if indent_match else ''
            inner_indent = base_indent + '    '
            
            # For each param to add (in reverse order)
            for param_name in reversed(params):
                fix = FIXES[param_name]
                if fix.get("decl") is not None:
                    param_value = param_name
                else:
                    param_value = FUNCTION_VALUES.get(param_name, param_name)
                
                # Check if already present in the constructor
                constr_text = '\n'.join(lines[constr_start:constr_end + 1])
                if param_name in constr_text or param_value in constr_text:
                    continue
                
                # Add comma to current line (if not already ending with comma)
                current_line = lines[idx].rstrip()
                if not current_line.endswith(','):
                    lines[idx] = current_line + ','
                
                # Insert new param line before the closing paren
                if is_named:
                    new_line = f"{inner_indent}{param_name} = {param_value}"
                else:
                    new_line = f"{inner_indent}{param_value}"
                
                lines.insert(constr_end, new_line)
                # After insert, constr_end shifts by 1
                constr_end += 1
    
    content = '\n'.join(lines)
    
    if content != original:
        with open(abs_path, 'w', encoding='utf-8') as f:
            f.write(content)
        return len(all_param_names)
    
    return 0


def main():
    errors = parse_errors()
    print(f"Found {len(errors)} missing-param errors")
    
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
            fixed = fix_errors_in_file(file_errors)
            if fixed > 0:
                total_fixed += fixed
                total_files += 1
                unique_params = list(set(e[2] for e in file_errors if e[2] in FIXES))
                print(f"  FIXED {unique_params} in {os.path.basename(abs_path)}")
        except Exception as e:
            print(f"  ERROR {os.path.basename(abs_path)}: {e}")
            import traceback
            traceback.print_exc()
    
    print(f"\nFixed {total_files} files with {total_fixed} parameters")

if __name__ == "__main__":
    main()
