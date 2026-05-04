Write-Host "=== Batch Fix: Test Compilation Errors ==="

$testDir = "C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"

# Helper: apply regex replacement and verify
function Fix-File {
    param($Path, $Pattern, $Replacement, $Description)
    $fullPath = Join-Path $testDir $Path
    if (-not (Test-Path $fullPath)) {
        Write-Host "  SKIP (not found): $Path" -ForegroundColor Yellow
        return $false
    }
    $content = Get-Content $fullPath -Raw -Encoding UTF8
    if ($content -match $Pattern) {
        $newContent = $content -replace $Pattern, $Replacement
        if ($newContent -ne $content) {
            Set-Content $fullPath $newContent -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $Path - $Description" -ForegroundColor Green
            return $true
        }
    }
    return $false
}

Write-Host "`n=== 1. GreekBankParser: add homeCurrency ==="

# Pattern: GreekBankParser(currencyNormalizer, merchantCleaner) 
Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    
    # GreekBankParser with 2 args but not 3
    if ($content -match 'GreekBankParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)' -and 
        $content -notmatch 'GreekBankParser\([^)]*,[^)]*,[^)]*\)') {
        $newContent = $content -replace 
            'GreekBankParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)',
            'GreekBankParser($1, $2, "EUR")'
        if ($newContent -ne $content) {
            Set-Content $path $newContent -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $($_.Name) - GreekBankParser homeCurrency" -ForegroundColor Green
        }
    }
}

Write-Host "`n=== 2. GenericTransactionParser: add timeProvider ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    
    # GenericTransactionParser with 3 args but not 4 (end paren after 3 args)
    if ($content -match 'GenericTransactionParser\(\s*([^)]*?)\s*,\s*([^)]*?)\s*,\s*([^)]*?)\s*\)' -and
        $content -notmatch 'GenericTransactionParser\([^)]*,[^)]*,[^)]*,[^)]*\)') {
        # Only fix if it doesn't already have 4 args
        $newContent = $content -replace
            '(GenericTransactionParser\(\s*\w[\w.]*\s*,\s*\w[\w.]*\s*,\s*\w[\w.]*\s*)\)',
            '$1, io.mockk.mockk())'
        if ($newContent -ne $content) {
            # Check if mockk is imported
            if ($newContent -notmatch 'import io.mockk') {
                $newContent = $newContent -replace
                    '(package [\w.]+)', 
                    "`$1`nimport io.mockk.mockk"
            }
            Set-Content $path $newContent -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $($_.Name) - GenericTransactionParser timeProvider" -ForegroundColor Green
        }
    }
}

Write-Host "`n=== 3. AppParserRegistry: add timeProvider ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    
    # AppParserRegistry without timeProvider - look for the closing paren after aiFallbackParser
    if ($content -match 'aiFallbackParser\s*=\s*io\.mockk\.mockk\(\)\s*\)' -and
        $content -notmatch 'timeProvider') {
        $newContent = $content -replace
            '(aiFallbackParser\s*=\s*io\.mockk\.mockk\(\))\s*\)',
            "`$1,`n        timeProvider = io.mockk.mockk()"
        if ($newContent -ne $content) {
            Set-Content $path $newContent -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $($_.Name) - AppParserRegistry timeProvider" -ForegroundColor Green
        }
    }
}

Write-Host "`n=== 4. ReceiptParser: add timeProvider ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    
    # ReceiptParser with 1 arg but not 2
    if ($content -match 'ReceiptParser\(\s*(MerchantRulesRepository|MerchantRulesPolicy)\(\)\s*\)' -and
        $content -notmatch 'ReceiptParser\([^)]*,[^)]*\)') {
        # Check if mockk is already imported
        $importExists = $content -match 'import io\.mockk' -or $content -match 'import io\.mockk\.mockk'
        $mockPrefix = if ($importExists) { '' } else { 'io.mockk.' }
        
        $newContent = $content -replace
            '(ReceiptParser\(\s*(MerchantRulesRepository|MerchantRulesPolicy)\(\))\s*\)',
            "`$1, ${mockPrefix}mockk())"
        if ($newContent -ne $content) {
            if (-not $importExists -and $newContent -notmatch 'import io\.mockk') {
                $newContent = $newContent -replace
                    '(package [\w.]+)',
                    "`$1`nimport io.mockk.mockk"
            }
            Set-Content $path $newContent -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $($_.Name) - ReceiptParser timeProvider" -ForegroundColor Green
        }
    }
}

Write-Host "`n=== 5. Add 'displayCurrency' param to data class constructors ==="

# Handle EnhancedCategoryAnalytics, EnhancedMerchantAnalytics, StatisticalInsights, etc.
# that need displayCurrency="EUR"
Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # EnhancedCategoryAnalytics(..., displayCurrency = ...) - already has it
    # Check for EnhancedCategoryAnalytics( that doesn't have displayCurrency
    if ($content -match 'EnhancedCategoryAnalytics\(' -and $content -notmatch 'EnhancedCategoryAnalytics\([^)]*displayCurrency') {
        $content = $content -replace 
            '(EnhancedCategoryAnalytics\(\s*[\w.:(),\n\r\s]*?)\)(?=\s*\))',
            "`$1,`n        displayCurrency = `"EUR`")"
    }
    
    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $($_.Name) - EnhancedCategoryAnalytics displayCurrency" -ForegroundColor Green
    }
}

Write-Host "`n=== 6. Add 'effectiveLimit' to BudgetStatus constructors ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # BudgetStatus without effectiveLimit - add it after healthStatus
    if ($content -match 'BudgetStatus\(' -and $content -notmatch 'effectiveLimit') {
        $content = $content -replace
            '(healthStatus\s*:\s*\w[\w.]*\s*,)',
            "`$1`n        effectiveLimit = 1000.0,"
        if ($content -ne $orig) {
            Set-Content $path $content -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $($_.Name) - BudgetStatus effectiveLimit" -ForegroundColor Green
        }
    }
}

Write-Host "`n=== 7. Add 'currency' field to GroupExpense/ParticipantShare data class constructors ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # Look for constructors that create expense/group objects without currency
    # Replace common patterns
    
    # ParticipantShare(...) without currency
    if ($content -match 'ParticipantShare\(' -and $content -notmatch 'ParticipantShare\([^)]*currency') {
        $content = $content -replace
            '(ParticipantShare\(\s*[\w.:,\n\r\s]*?)\)(?=\s*\))',
            "`$1,`n                currency = `"EUR`""
    }
    
    # SharedExpense(...) without currency  
    if ($content -match 'SharedExpense\(' -and $content -notmatch 'SharedExpense\([^)]*currency') {
        $content = $content -replace
            '(SharedExpense\(\s*[\w.:,\n\r\s]*?)\)(?=\s*\))',
            "`$1,`n                currency = `"EUR`""
    }
    
    # Settlement(...) without currency
    if ($content -match 'Settlement\(' -and $content -notmatch 'Settlement\([^)]*currency') {
        $content = $content -replace
            '(Settlement\(\s*[\w.:,\n\r\s]*?)\)(?=\s*\))',
            "`$1,`n                currency = `"EUR`""
    }
    
    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $($_.Name) - group/expense currency" -ForegroundColor Green
    }
}

Write-Host "`n=== 8. Add 'bigText' parameter to NotificationChannel ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # NotificationChannel(..., name, description) without bigText
    if ($content -match 'NotificationChannel\(' -and $content -notmatch 'bigText') {
        # Add bigText = null before the closing paren for NotificationChannel
        # This is a tricky pattern - let's look for a specific pattern
        $content = $content -replace
            '(NotificationChannel\(\s*[\w.:,\n\r\s]*?packageName[\w.:,\n\r\s]*?)\)',
            "`$1,`n        bigText = null)"
        if ($content -ne $orig) {
            Set-Content $path $content -Encoding UTF8 -NoNewline
            Write-Host "  FIXED: $($_.Name) - NotificationChannel bigText" -ForegroundColor Green
        }
    }
}

Write-Host "`n=== 9. Add 'createdAt' and 'updatedAt' parameters ==="

Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # Look for entity constructors that are missing createdAt/updatedAt/expiresAt
    $fixes = @(
        @{Pattern = '(Recommendation\([\s\S]*?)(\)\s*(?:\n|$))'; Replacement = "`$1,`n    createdAt = System.currentTimeMillis(),`n    updatedAt = System.currentTimeMillis()`$2"}
    )
    
    foreach ($fix in $fixes) {
        if ($content -match $fix.Pattern -and $content -notmatch 'createdAt\s*=') {
            $content = $content -replace $fix.Pattern, $fix.Replacement
        }
    }
    
    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $($_.Name) - createdAt/updatedAt" -ForegroundColor Green
    }
}

Write-Host "`n=== 10. Add mock parameters to constructor calls ==="

# Add common missing mock params like currencySettingsRepository, currencyConverter, etc.
Get-ChildItem $testDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # These are harder to fix generically, but we can try some patterns
    
    # FlowPipelineTestHarness(...) without timeProvider
    if ($content -match 'FlowPipelineTestHarness\(' -and $content -notmatch 'timeProvider') {
        $content = $content -replace
            '(FlowPipelineTestHarness\(\s*[\w.:(),\n\r\s]*?)\)(\s*\))',
            "`$1,`n            timeProvider = io.mockk.mockk()`$2"
    }
    
    # AnalyticsEngine/InsightsEngine etc without displayCurrency
    # CarbonFootprintCalculator without analyticsCurrencyNormalizer
    if ($content -match 'CarbonFootprintCalculator\(' -and $content -notmatch 'analyticsCurrencyNormalizer' -and $content -notmatch 'currencySettingsRepository') {
        $content = $content -replace
            '(CarbonFootprintCalculator\(\s*[\w.:(),\n\r\s]*?)\)(\s*\))',
            "`$1,`n            analyticsCurrencyNormalizer = io.mockk.mockk(),`n            currencySettingsRepository = io.mockk.mockk()`$2"
    }
    
    # Create a fake exchange rate store for AnalyticsCurrencyNormalizer
    if ($content -match 'FakeExchangeRateStore' -and $content -notmatch 'getRateAsOf') {
        $content = $content -replace
            '(class\s+FakeExchangeRateStore.*?\{)',
            "`$1`n        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? = null"
    }
    
    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $($_.Name) - various params" -ForegroundColor Green
    }
}

Write-Host "`n=== 11. Fix ReceiptMatchingWorker unresolved reference ==="
# Add import for ReceiptMatchingWorker in test files
Get-ChildItem $testDir -Recurse -Filter "ReceiptMatchingWorkerTest.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    if ($content -notmatch 'import com\.yourname\.expensetracker\.service\.receiptmatching\.ReceiptMatchingWorker') {
        $content = $content -replace
            '(package [\w.]+)',
            "`$1`nimport com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker"
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $($_.Name) - ReceiptMatchingWorker import" -ForegroundColor Green
    }
}

Write-Host "`n=== 12. Fix addMember return type ==="
Get-ChildItem $testDir -Recurse -Filter "*NotificationExpenseDashboardPipelineTest.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    
    # Fix addMember return type
    $content = $content -replace
        'override suspend fun addMember.*?\{[\s\S]*?return@mockk',
        'override suspend fun addMember(groupId: Long, name: String, email: String?, isCurrentUser: Boolean): Result<Unit, GroupValidationError> { return@mockk Result.success(Unit)'
    
    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $($_.Name) - addMember return type" -ForegroundColor Green
    }
}

Write-Host "`n=== 13. Fix Calendar-related errors in WarrantyTextExtractorTest ==="
# Already fixed manually

Write-Host "`n=== DONE ==="
