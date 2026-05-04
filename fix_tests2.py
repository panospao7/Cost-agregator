#!/usr/bin/env python3
"""
Fix test compilation errors by adding missing constructor parameters.
Reads the gradle error log, identifies all "No value passed for parameter" errors,
extracts file paths and parameter names, and fixes them.
"""

import re
import os

TEST_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
ERROR_FILE = r"C:\Users\panos\.local\share\opencode\tool-output\errors3.txt"

# ── Pattern: extract file:line:col and param name ──────────────────────────────
# Format:
# e: file:///C:/Users/.../expensetracker/consistency/CrossParserConsistencyTest.kt:34:63 No value passed for parameter 'homeCurrency'.
# But the file path may have %20 instead of spaces.

ERROR_RE = re.compile(
    r"e:\s*file:///C:.*?expensetracker/(.+?):(\d+):\d+\s+"
    r"No value passed for parameter '(\w+)'"
)

# ── Parameter fix definitions ──────────────────────────────────────────────────
# Maps param_name -> (type_name_for_mock, declaration_template, import_statement_or_None)
# For positional args: we need to know the number of existing args to insert at the right position
# For named args: we add `param = param` before the closing paren

PARAM_FIXES = {
    "currencySettingsRepository": {
        "mock_type": "CurrencySettingsRepository",
        "declaration": '    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository',
        "value_for_constructor": "currencySettingsRepository",
    },
    "timeProvider": {
        "mock_type": "TimeProvider",
        "declaration": '    private val timeProvider = FakeTimeProvider()',
        "import": None,
        "alt_declaration": '    private val timeProvider = mockk<TimeProvider>(relaxed = true)',
        "value_for_constructor": "timeProvider",
    },
    "currencyConverter": {
        "mock_type": "CurrencyConverter",
        "declaration": '    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.currency.CurrencyConverter',
        "value_for_constructor": "currencyConverter",
    },
    "privacyGate": {
        "mock_type": "PrivacyGate",
        "declaration": '    private val privacyGate = mockk<PrivacyGate>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.privacy.PrivacyGate',
        "value_for_constructor": "privacyGate",
    },
    "multiCurrencyRepository": {
        "mock_type": "MultiCurrencyRepository",
        "declaration": '    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.MultiCurrencyRepository',
        "value_for_constructor": "multiCurrencyRepository",
    },
    "analyticsCurrencyNormalizer": {
        "mock_type": "AnalyticsCurrencyNormalizer",
        "declaration": '    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer',
        "value_for_constructor": "analyticsCurrencyNormalizer",
    },
    "receiptLifecycleCoordinator": {
        "mock_type": "ReceiptLifecycleCoordinator",
        "declaration": '    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator',
        "value_for_constructor": "receiptLifecycleCoordinator",
    },
    "receiptLinkService": {
        "mock_type": "ReceiptLinkService",
        "declaration": '    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService',
        "value_for_constructor": "receiptLinkService",
    },
    "homeCurrency": {
        "mock_type": None,  # Not a mock
        "declaration": '    private val homeCurrency = "EUR"',
        "import": None,
        "value_for_constructor": '"EUR"',
    },
    "ioDispatcher": {
        "mock_type": None,
        "declaration": '    private val ioDispatcher = testDispatcher',
        "import": None,
        "value_for_constructor": "ioDispatcher",
    },
    "recurringOccurrenceDao": {
        "mock_type": "RecurringOccurrenceDao",
        "declaration": '    private val recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao',
        "value_for_constructor": "recurringOccurrenceDao",
    },
    "recurringLifecycleCoordinator": {
        "mock_type": "RecurringLifecycleCoordinator",
        "declaration": '    private val recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)',
        "import": None,
        "value_for_constructor": "recurringLifecycleCoordinator",
    },
    "expenseRepository": {
        "mock_type": "ExpenseRepository",
        "declaration": '    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.ExpenseRepository',
        "value_for_constructor": "expenseRepository",
    },
    "cashFlowCalculator": {
        "mock_type": "CashFlowCalculator",
        "declaration": '    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator',
        "value_for_constructor": "cashFlowCalculator",
    },
    "categoryRepository": {
        "mock_type": "CategoryRepository",
        "declaration": '    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.CategoryRepository',
        "value_for_constructor": "categoryRepository",
    },
    "restoreMaintenanceMode": {
        "mock_type": "RestoreMaintenanceMode",
        "declaration": '    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode',
        "value_for_constructor": "restoreMaintenanceMode",
    },
    "recurringPatternsProvider": {
        "mock_type": "RecurringPatternsProvider",
        "declaration": '    private val recurringPatternsProvider = mockk<RecurringPatternsProvider>(relaxed = true)',
        "import": None,
        "value_for_constructor": "recurringPatternsProvider",
    },
    "coordinator": {
        "mock_type": "TransactionLifecycleCoordinator",
        "declaration": '    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator',
        "value_for_constructor": "coordinator",
    },
    "forecastInputAssembler": {
        "mock_type": "ForecastInputAssembler",
        "declaration": '    private val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)',
        "import": None,
        "value_for_constructor": "forecastInputAssembler",
    },
    "anomalyAlertRepository": {
        "mock_type": "AnomalyAlertRepository",
        "declaration": '    private val anomalyAlertRepository = mockk<AnomalyAlertRepository>(relaxed = true)',
        "import": None,
        "value_for_constructor": "anomalyAlertRepository",
    },
    "monthlySavingsSweepUseCase": {
        "mock_type": "MonthlySavingsSweepUseCase",
        "declaration": '    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)',
        "import": None,
        "value_for_constructor": "monthlySavingsSweepUseCase",
    },
    "receiptParser": {
        "mock_type": "ReceiptParser",
        "declaration": '    private val receiptParser = mockk<ReceiptParser>(relaxed = true)',
        "import": None,
        "value_for_constructor": "receiptParser",
    },
    "legacyDataMigrationService": {
        "mock_type": "LegacyDataMigrationService",
        "declaration": '    private val legacyDataMigrationService = mockk<LegacyDataMigrationService>(relaxed = true)',
        "import": None,
        "value_for_constructor": "legacyDataMigrationService",
    },
    "lastSeen": {
        "mock_type": None,
        "declaration": '    private val lastSeen = 0L',
        "import": None,
        "value_for_constructor": "0L",
    },
    "notificationService": {
        "mock_type": "NotificationService",
        "declaration": '    private val notificationService = mockk<NotificationService>(relaxed = true)',
        "import": None,
        "value_for_constructor": "notificationService",
    },
    "categorizationEngine": {
        "mock_type": "CategorizationEngine",
        "declaration": '    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)',
        "import": None,
        "value_for_constructor": "categorizationEngine",
    },
    "expiresAt": {
        "mock_type": None,
        "declaration": '    private val expiresAt = 0L',
        "import": None,
        "value_for_constructor": "expiresAt",
    },
    "hybridExpenseClassifier": {
        "mock_type": "HybridExpenseClassifier",
        "declaration": '    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)',
        "import": None,
        "value_for_constructor": "hybridExpenseClassifier",
    },
    "priority": {
        "mock_type": "PlannedExpensePriority",
        "declaration": '    private val priority = PlannedExpensePriority.NORMAL',
        "import": None,
        "value_for_constructor": "priority",
    },
    "bigText": {
        "mock_type": None,
        "declaration": '    private val bigText = "test notification text"',
        "import": None,
        "value_for_constructor": "bigText",
    },
    "displayCurrency": {
        "mock_type": None,
        "declaration": None,  # function param, handled differently
        "import": None,
        "value_for_constructor": '"EUR"',
    },
    "currency": {
        "mock_type": None,
        "declaration": None,  # function param, handled differently
        "import": None,
        "value_for_constructor": '"EUR"',
    },
    "effectiveLimit": {
        "mock_type": None,
        "declaration": None,  # function param, handled differently
        "import": None,
        "value_for_constructor": "1000.0",
    },
    "createdAt": {
        "mock_type": None,
        "declaration": None,  # function param
        "import": None,
        "value_for_constructor": "0L",
    },
    "updatedAt": {
        "mock_type": None,
        "declaration": None,  # function param
        "import": None,
        "value_for_constructor": "0L",
    },
}


def parse_errors():
    """Parse the error file and return list of (file_path, line_num, param_name)."""
    errors = []
    with open(ERROR_FILE, 'r', encoding='utf-8') as f:
        for line in f:
            if 'No value passed for parameter' not in line:
                continue
            
            m = ERROR_RE.search(line)
            if m:
                rel_path = m.group(1).replace('%20', ' ')
                line_num = int(m.group(2))
                param_name = m.group(3)
                abs_path = os.path.join(TEST_DIR, rel_path)
                errors.append((abs_path, line_num, param_name))
            else:
                # Try simpler regex - sometimes the line starts without "e: "
                m2 = re.search(r"expensetracker/(.+?):(\d+):\d+\s+No value passed for parameter '(\w+)'", line)
                if m2:
                    rel_path = m2.group(1).replace('%20', ' ')
                    line_num = int(m2.group(2))
                    param_name = m2.group(3)
                    abs_path = os.path.join(TEST_DIR, rel_path)
                    errors.append((abs_path, line_num, param_name))
    return errors


def file_exists(path):
    return os.path.isfile(path)


def add_import_if_missing(content, import_line):
    """Add an import line if not already present."""
    if import_line is None or import_line in content:
        return content
    
    lines = content.split('\n')
    
    # Find the last import line
    last_import_idx = -1
    for i, line in enumerate(lines):
        if line.startswith('import '):
            last_import_idx = i
    
    if last_import_idx >= 0:
        lines.insert(last_import_idx + 1, import_line)
    
    return '\n'.join(lines)


def add_field_declaration(content, decl):
    """Add a field declaration before the @Before/setup method or after existing declarations."""
    if decl is None:
        return content
    
    lines = content.split('\n')
    
    # Find where to insert: after the last field declaration or before @Before/setup
    insert_idx = None
    
    # Look for last private val/var/lateinit var declaration
    for i, line in enumerate(lines):
        if re.match(r'^\s+private (val|var|lateinit var) \w+', line):
            insert_idx = i + 1
    
    if insert_idx is None:
        # Look for @Before or setup
        for i, line in enumerate(lines):
            if re.match(r'^\s*(override fun setup|@Before|fun setup)', line):
                insert_idx = i
    
    if insert_idx is not None:
        lines.insert(insert_idx, decl)
    
    return '\n'.join(lines)


def add_named_param_to_constructor(content, param_name, constructors_starting_lines):
    """Add `paramName = paramName` to named-parameter constructor calls.
    
    Looks for constructor calls like:
      viewModel = SomeViewModel(
          param1 = value1,
          param2 = value2
      )
    
    And adds the missing param before the closing paren.
    """
    lines = content.split('\n')
    
    # We need to find constructor calls that use named parameters
    # Pattern: variable = ClassName(
    # Followed by at least one `param = value` line
    
    i = 0
    while i < len(lines):
        line = lines[i]
        
        # Check if this line starts a constructor with named params
        m = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\($', line)
        if m:
            indent = m.group(1)
            depth = 1
            j = i + 1
            has_named_params = False
            
            # Scan ahead to find matching close paren and check for named params
            while j < len(lines) and depth > 0:
                jline = lines[j]
                depth += jline.count('(') - jline.count(')')
                if depth <= 0:
                    break
                # Check for named param pattern:   param = value
                if re.match(r'^\s+\w+\s*=', jline):
                    has_named_params = True
                j += 1
            
            if has_named_params and depth <= 0:
                # Found the closing paren at line j
                # Check if this specific constructor is missing the param
                # Only add if the param_name isn't already in the constructor call
                constructor_text = '\n'.join(lines[i:j+1])
                if param_name not in constructor_text:
                    # Also check if the line number matches one of our targets
                    # Add the param before the closing paren
                    insert_line = indent + '    ' + param_name + ' = ' + param_name
                    lines.insert(j, insert_line)
                    i = j + 1  # Skip past the inserted line
                    continue
        
        i += 1
    
    return '\n'.join(lines)


def add_function_param(content, param_name, line_num, value):
    """Add a function parameter to a function call at a specific line."""
    lines = content.split('\n')
    
    if line_num < 1 or line_num > len(lines):
        return content
    
    idx = line_num - 1  # 0-indexed
    line = lines[idx]
    
    if param_name in line:
        return content  # Already has it
    
    # Try to add the named parameter
    # Pattern: functionName(
    # We need to find if this line starts a function call or is a continuation
    
    # Look for patterns like: someFunction(
    # or: someFunction(param1 = value1,
    
    new_line = line.rstrip()
    
    # Check if we're at the start of a function call or in the middle
    if new_line.endswith('(') or new_line.endswith(',') or new_line.endswith(', '):
        # Add a new parameter
        if new_line.endswith('('):
            # First param
            pass
        else:
            # Additional param - need to handle comma
            pass
    
    # Simple approach: if the line ends with a paren and needs the param added inside
    # Or if we're adding to a specific position
    
    return '\n'.join(lines)


def main():
    errors = parse_errors()
    print(f"Parsed {len(errors)} errors from log")
    
    # Group by file
    file_errors = {}
    for abs_path, line_num, param_name in errors:
        if abs_path not in file_errors:
            file_errors[abs_path] = []
        file_errors[abs_path].append((line_num, param_name))
    
    print(f"Errors span {len(file_errors)} files")
    
    # Filter to fixable errors only
    fixable_files = {}
    for abs_path, params in file_errors.items():
        fixable_params = [(ln, p) for ln, p in params if p in PARAM_FIXES]
        if fixable_params:
            fixable_files[abs_path] = fixable_params
    
    print(f"Fixable errors in {len(fixable_files)} files")
    
    total_params_fixed = 0
    
    for abs_path, params in fixable_files.items():
        if not file_exists(abs_path):
            print(f"  SKIP: file not found: {abs_path}")
            continue
        
        # Read file
        with open(abs_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        original = content
        file_fixes = []
        
        for line_num, param_name in params:
            fix = PARAM_FIXES[param_name]
            
            # Skip if param_name already in content (already fixed)
            if f" {param_name} " in content or f" {param_name}\n" in content or f"={param_name}" in content or f"= {param_name}" in content:
                # Check if it's already declared
                if f"val {param_name}" in content or f"var {param_name}" in content:
                    file_fixes.append(f"{param_name} (already in file)")
                    continue
            
            # Add import
            content = add_import_if_missing(content, fix.get("import"))
            
            # Add field declaration (only for constructor-level params, not function params)
            if fix["declaration"] is not None and param_name not in content:
                content = add_field_declaration(content, fix["declaration"])
            
            # Add to constructor call (named params)
            content = add_named_param_to_constructor(content, param_name, set())
            
            file_fixes.append(param_name)
        
        if content != original:
            with open(abs_path, 'w', encoding='utf-8') as f:
                f.write(content)
            
            modified_params = [p for p in file_fixes if "(already" not in p]
            total_params_fixed += len(modified_params)
            if modified_params:
                print(f"  FIXED [{', '.join(modified_params)}] in {os.path.basename(abs_path)}")
            else:
                print(f"  NO NEW FIXES: {os.path.basename(abs_path)} ({file_fixes})")
        else:
            print(f"  NO CHANGE: {os.path.basename(abs_path)} ({file_fixes})")
    
    print(f"\nTotal parameters fixed: {total_params_fixed}")


if __name__ == "__main__":
    main()
