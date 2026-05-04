#!/usr/bin/env python3
"""
Fix all test compilation errors by adding missing constructor parameters.
Reads the gradle error log, identifies all "No value passed for parameter" errors,
and fixes them by adding mock declarations and constructor arguments.

Run from the ExpenseTracker root directory:
  python fix_tests.py
"""

import re
import os
import glob as glob_module

TEST_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
MAIN_DIR = r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\main\java\com\yourname\expensetracker"
LOG_FILE = r"C:\Users\panos\.local\share\opencode\tool-output\tool_df3de245c001NTzxMWbG9eUYkV"

# ── Parameter fix definitions ──────────────────────────────────────────────────
# Each entry: (param_name, type_name, declaration_line, import_line)
# declaration_line: template for the field declaration
# import_line: template for the import (None = no import needed, False = skip check)

PARAM_FIXES = {
    "currencySettingsRepository": {
        "type": "CurrencySettingsRepository",
        "declaration": '    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository',
    },
    "timeProvider": {
        "type": "TimeProvider",
        "declaration": '    private val timeProvider = FakeTimeProvider()',
        "import": None,  # already imported in most files, or uses FakeTimeProvider
        "alt_declaration": '    private val timeProvider = mockk<TimeProvider>(relaxed = true)',
    },
    "currencyConverter": {
        "type": "CurrencyConverter",
        "declaration": '    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.currency.CurrencyConverter',
    },
    "privacyGate": {
        "type": "PrivacyGate",
        "declaration": '    private val privacyGate = mockk<PrivacyGate>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.privacy.PrivacyGate',
    },
    "multiCurrencyRepository": {
        "type": "MultiCurrencyRepository",
        "declaration": '    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.MultiCurrencyRepository',
    },
    "analyticsCurrencyNormalizer": {
        "type": "AnalyticsCurrencyNormalizer",
        "declaration": '    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer',
    },
    "receiptLifecycleCoordinator": {
        "type": "ReceiptLifecycleCoordinator",
        "declaration": '    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator',
    },
    "receiptLinkService": {
        "type": "ReceiptLinkService",
        "declaration": '    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService',
    },
    "homeCurrency": {
        "type": "String",
        "declaration": '    private val homeCurrency = "EUR"',
        "import": None,
    },
    "ioDispatcher": {
        "type": "CoroutineDispatcher",
        "declaration": '    private val ioDispatcher = testDispatcher',  # Uses existing testDispatcher
        "import": None,
        "alt_declaration": '    private val ioDispatcher = DefaultTestDispatcher()',
    },
    "recurringOccurrenceDao": {
        "type": "RecurringOccurrenceDao",
        "declaration": '    private val recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao',
    },
    "recurringLifecycleCoordinator": {
        "type": "RecurringLifecycleCoordinator",
        "declaration": '    private val recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.recurring.RecurringLifecycleCoordinator',
    },
    "expenseRepository": {
        "type": "ExpenseRepository",
        "declaration": '    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.ExpenseRepository',
    },
    "cashFlowCalculator": {
        "type": "CashFlowCalculator",
        "declaration": '    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator',
    },
    "categoryRepository": {
        "type": "CategoryRepository",
        "declaration": '    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.repository.CategoryRepository',
    },
    "restoreMaintenanceMode": {
        "type": "RestoreMaintenanceMode",
        "declaration": '    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode',
    },
    "recurringPatternsProvider": {
        "type": "RecurringPatternsProvider",
        "declaration": '    private val recurringPatternsProvider = mockk<RecurringPatternsProvider>(relaxed = true)',
        "import": None,  # might need to find import
    },
    "coordinator": {
        "type": "TransactionLifecycleCoordinator",
        "declaration": '    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)',
        "import": 'import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator',
    },
    "forecastInputAssembler": {
        "type": "ForecastInputAssembler",
        "declaration": '    private val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)',
        "import": None,
    },
    "anomalyAlertRepository": {
        "type": "AnomalyAlertRepository",
        "declaration": '    private val anomalyAlertRepository = mockk<AnomalyAlertRepository>(relaxed = true)',
        "import": None,
    },
    "monthlySavingsSweepUseCase": {
        "type": "MonthlySavingsSweepUseCase",
        "declaration": '    private val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)',
        "import": None,
    },
    "receiptParser": {
        "type": "ReceiptParser",
        "declaration": '    private val receiptParser = mockk<ReceiptParser>(relaxed = true)',
        "import": None,
    },
    "legacyDataMigrationService": {
        "type": "LegacyDataMigrationService",
        "declaration": '    private val legacyDataMigrationService = mockk<LegacyDataMigrationService>(relaxed = true)',
        "import": None,
    },
    "lastSeen": {
        "type": "Long",
        "declaration": '    private val lastSeen = 0L',
        "import": None,
    },
    "notificationService": {
        "type": "NotificationService",
        "declaration": '    private val notificationService = mockk<NotificationService>(relaxed = true)',
        "import": None,
    },
    "categorizationEngine": {
        "type": "CategorizationEngine",
        "declaration": '    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)',
        "import": None,
    },
    "expiresAt": {
        "type": "Long",
        "declaration": '    // expiresAt will be passed inline',
        "import": None,
    },
    "hybridExpenseClassifier": {
        "type": "HybridExpenseClassifier",
        "declaration": '    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)',
        "import": None,
    },
    "priority": {
        "type": "PlannedExpensePriority",
        "declaration": '    private val priority = PlannedExpensePriority.NORMAL',
        "import": None,
    },
}


def extract_error_files_and_params(log_file):
    """Extract (file_path, param_name) pairs from the gradle error log."""
    errors = []
    param_pattern = re.compile(r"No value passed for parameter '(\w+)'")
    
    with open(log_file, 'r', encoding='utf-8') as f:
        for line in f:
            m = param_pattern.search(line)
            if not m:
                continue
            param_name = m.group(1)
            
            # Extract file path
            file_match = re.search(r'file:///C:.*?expensetracker/(.+?):\d+:\d+', line)
            if file_match:
                raw_path = file_match.group(1)
                # URL decode spaces
                raw_path = raw_path.replace('%20', ' ')
                file_path = os.path.join(TEST_DIR, raw_path)
                errors.append((file_path, param_name))
    
    return errors


def fix_positional_constructor(content, file_path, param_name, fix_info):
    """Fix a positional constructor call by appending the new parameter."""
    # This is tricky because we need to find the constructor call site.
    # The typical pattern is: ClassName(\n    arg1,\n    arg2,\n    arg3\n)
    # We need to append the new argument before the closing paren.
    
    # Find constructor call in the file
    # Look for: variableName = ClassName(
    # We'll try to find it by looking at where the missing param error points
    
    lines = content.split('\n')
    
    # Strategy: Find the constructor call that doesn't have the param
    # We look for a pattern like: ClassName(
    # followed by arguments, then )
    # and ensure the param isn't already there
    
    # Simple approach: look for lines with " = " that contain "("
    # and are followed eventually by a ")" without the param name
    
    return content  # Placeholder for now


def fix_named_constructor(content, file_path, param_name, fix_info):
    """Add a named parameter to a constructor call that uses named params."""
    decl = fix_info["declaration"]
    import_line = fix_info.get("import")
    
    # Check if this is already fixed
    if param_name in content:
        return content
    
    # Add the import if needed and not already present
    if import_line and import_line not in content:
        # Add import after the last existing import
        import_match = re.search(r'^import\s+', content, re.MULTILINE)
        if import_match:
            # Find the last import line
            last_import = list(re.finditer(r'^import .+$', content, re.MULTILINE))[-1]
            pos = last_import.end()
            content = content[:pos] + '\n' + import_line + content[pos:]
    
    # Add the field declaration before the constructor call
    # Find where the other field declarations are
    field_pattern = re.compile(r'^\s+private (val|var|lateinit var) \w+', re.MULTILINE)
    field_matches = list(field_pattern.finditer(content))
    
    if field_matches:
        # Add after the last field declaration
        last_field = field_matches[-1]
        pos = last_field.end()
        content = content[:pos] + '\n' + decl + content[pos:]
    else:
        # Add before the class body closes or before setup/before method
        before_match = re.search(r'^\s+(override fun setup|@Before|fun setup)', content, re.MULTILINE)
        if before_match:
            pos = before_match.start()
            content = content[:pos] + decl + '\n\n' + content[pos:]
    
    # Add the named parameter to constructor calls
    # Look for: ClassName(\n    ...
    # We need to find the appropriate constructor call
    # The parameter should be added where it's missing
    
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        # Check if this line is a constructor call that ends with ")" or has params
        # We need to find lines like: viewModel = SomeViewModel(
        # and then find where to add the missing param
        new_lines.append(line)
    
    return '\n'.join(new_lines)


def add_missing_param_to_constructor(content, param_name):
    """Add a named parameter to constructor calls that are missing it.
    
    Looks for constructor calls in the format:
      variableName = ClassName(
          param1 = value1,
          param2 = value2
      )
    
    And adds: paramName = paramName
    before the closing paren.
    """
    # Find all constructor assignments
    # Pattern: identifier = ClassName(\n    ... \n)
    
    lines = content.split('\n')
    result = []
    i = 0
    
    while i < len(lines):
        line = lines[i]
        
        # Check if this line starts a constructor call
        # Pattern: variable = ClassName(  or just ClassName(
        # Look for named params pattern
        constructor_match = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\($', line)
        named_param_start = re.match(r'^(\s*)(\w+)\s*=\s*(\w+)\s*\($', line)
        
        if named_param_start and param_name not in line:
            indent = named_param_start.group(1)
            # Check if this constructor uses named params
            # Scan ahead to find the matching close paren
            depth = 1
            j = i + 1
            has_named_params = False
            while j < len(lines) and depth > 0:
                jline = lines[j]
                # Count parens
                depth += jline.count('(') - jline.count(')')
                if depth <= 0:
                    break
                # Check for named param pattern
                if re.match(r'^\s+\w+\s*=', jline):
                    has_named_params = True
                j += 1
            
            if has_named_params:
                # Find the closing paren line and add the param before it
                depth = 1
                k = i + 1
                while k < len(lines) and depth > 0:
                    depth += lines[k].count('(') - lines[k].count(')')
                    if depth <= 0:
                        # This is the closing line, add param before it
                        insert_line = indent + '    ' + param_name + ' = ' + param_name
                        if k > i + 1:
                            # Add before the closing paren
                            result.append(insert_line)
                        break
                    k += 1
        
        result.append(line)
        i += 1
    
    return '\n'.join(result)


def main():
    errors = extract_error_files_and_params(LOG_FILE)
    print(f"Found {len(errors)} missing-param errors")
    
    # Group by file
    file_errors = {}
    for file_path, param_name in errors:
        if file_path not in file_errors:
            file_errors[file_path] = []
        file_errors[file_path].append(param_name)
    
    # Filter to only errors we have fixes for
    fixable = {}
    for file_path, params in file_errors.items():
        fixable_params = [p for p in params if p in PARAM_FIXES]
        if fixable_params:
            fixable[file_path] = fixable_params
    
    print(f"Found {len(fixable)} files with fixable errors")
    
    total_fixed = 0
    for file_path, params in fixable.items():
        if not os.path.exists(file_path):
            print(f"  SKIP: file not found: {file_path}")
            continue
        
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        original = content
        file_fixed_params = []
        
        for param_name in params:
            if param_name in content:
                # Already has the param declared, probably needs it in constructor call only
                # Try to add it to the constructor call
                new_content = add_missing_param_to_constructor(content, param_name)
                if new_content != content:
                    content = new_content
                    file_fixed_params.append(param_name + " (constructor)")
                    continue
                else:
                    print(f"  SKIP {param_name} in {os.path.basename(file_path)}: already in content")
                    continue
            
            fix_info = PARAM_FIXES[param_name]
            decl = fix_info["declaration"]
            import_line = fix_info.get("import")
            
            # Add import
            if import_line and import_line not in content:
                # Find the package statement and add import after existing imports
                lines = content.split('\n')
                insert_pos = None
                for idx, line in enumerate(lines):
                    if line.startswith('import '):
                        insert_pos = idx + 1
                    elif insert_pos and not line.startswith('import '):
                        break
                
                if insert_pos:
                    lines.insert(insert_pos, import_line)
                    content = '\n'.join(lines)
            
            # Add field declaration
            lines = content.split('\n')
            
            # Find a good place to add the field declaration
            # Look for the last private val/var declaration before @Before or setup
            insert_idx = None
            for idx, line in enumerate(lines):
                if re.match(r'^\s+private (val|var|lateinit var) \w+', line):
                    insert_idx = idx + 1
            
            if insert_idx is None:
                # Look for @Before or setup
                for idx, line in enumerate(lines):
                    if re.match(r'^\s*(override fun setup|@Before|fun setup)', line):
                        insert_idx = idx
                        break
            
            if insert_idx is not None:
                lines.insert(insert_idx, decl)
                content = '\n'.join(lines)
            
            # Add to constructor calls
            content = add_missing_param_to_constructor(content, param_name)
            
            file_fixed_params.append(param_name)
        
        if content != original:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(content)
            total_fixed += len(file_fixed_params)
            print(f"  FIXED {file_fixed_params} in {os.path.basename(file_path)}")
        else:
            print(f"  NO CHANGE: {os.path.basename(file_path)} (params: {file_fixed_params})")
    
    print(f"\nTotal fixes applied: {total_fixed}")
    print(f"Files modified: {len(fixable)}")


if __name__ == "__main__":
    main()
