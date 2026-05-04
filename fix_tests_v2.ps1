param(
    [string]$TestDir = "C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker"
)

Write-Host "=== Fix v2: Comprehensive Test Compilation Error Fix ===" -ForegroundColor Cyan

$totalFixed = 0

function Add-ImportIfMissing {
    param($Content, $Import)
    if ($Content -notmatch [regex]::Escape($Import)) {
        $Content = $Content -replace "(package [\w.]+)", "`$1`n$Import"
        Write-Host "  Added import: $Import"
    }
    return $Content
}

function Fix-FileContent {
    param($Path)
    $content = Get-Content $Path -Raw -Encoding UTF8
    $orig = $content
    $name = Split-Path $Path -Leaf

    # 1. GreekBankParser - add homeCurrency parameter
    if ($content -match 'GreekBankParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)' -and 
        $content -notmatch 'GreekBankParser\([^)]*,[^)]*,[^)]*\)') {
        $content = $content -replace 
            'GreekBankParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)',
            'GreekBankParser($1, $2, "EUR")'
    }

    # 2. GenericTransactionParser - add timeProvider
    if ($content -match 'GenericTransactionParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)' -and
        $content -notmatch 'GenericTransactionParser\([^)]*,[^)]*,[^)]*,[^)]*\)') {
        $needsImport = $content -match 'io\.mockk\.mockk' -or $content -match 'import io\.mockk'
        $mockStr = if ($needsImport) { "mockk()" } else { "io.mockk.mockk()" }
        $content = $content -replace
            '(GenericTransactionParser\(\s*\w[\w.]*\s*,\s*\w[\w.]*\s*,\s*\w[\w.]*\s*)\)',
            "`$1, $mockStr)"
        if (-not $needsImport) {
            $content = Add-ImportIfMissing -Content $content -Import "import io.mockk.mockk"
        }
    }

    # 3. AppParserRegistry - add timeProvider  
    if ($content -match 'aiFallbackParser\s*=\s*(io\.mockk\.mockk|mockk)\(\)\s*\)' -and
        $content -notmatch 'timeProvider\s*=') {
        $content = $content -replace
            '(aiFallbackParser\s*=\s*(?:io\.mockk\.)?mockk\(\))\s*\)',
            "`$1,`n        timeProvider = io.mockk.mockk()"
    }

    # 4. ReceiptParser - add timeProvider
    if ($content -match 'ReceiptParser\(\s*(MerchantRulesRepository|MerchantRulesPolicy)\(\)\s*\)' -and
        $content -notmatch 'ReceiptParser\([^)]*,[^)]*\)') {
        $needsImport = $content -match 'import io\.mockk'
        $mockStr = if ($needsImport) { "mockk()" } else { "io.mockk.mockk()" }
        $content = $content -replace
            '(ReceiptParser\(\s*(?:MerchantRulesRepository|MerchantRulesPolicy)\(\))\s*\)',
            "`$1, $mockStr)"
        if (-not $needsImport) {
            $content = Add-ImportIfMissing -Content $content -Import "import io.mockk.mockk"
        }
    }

    # 5. ReceiptTransactionMatcher - add timeProvider and receiptLinkService
    if ($content -match 'ReceiptTransactionMatcher\(' -and $content -notmatch 'timeProvider' -and $content -notmatch 'receiptLinkService') {
        $content = $content -replace
            '(stringDistance\s*=\s*\w[\w.]*\s*)',
            "`$1`n        timeProvider = mockk(),`n        receiptLinkService = mockk()"
    }

    # 6. BudgetSnapshot - add currency
    if ($content -match 'BudgetSnapshot\(\s*categoryId\s*=\s*\d+\s*,\s*amount\s*=\s*[\d.]+\s*\)') {
        $content = $content -replace
            '(BudgetSnapshot\(\s*categoryId\s*=\s*\d+\s*,\s*amount\s*=\s*[\d.]+)\s*\)',
            "`$1, currency = `"EUR`")"
    }

    # 7. getCategoryAnalytics - add displayCurrency param
    if ($content -match '\.getCategoryAnalytics\(\s*(\w[\w.]*)\s*\)\.first\(\)') {
        $content = $content -replace
            '\.getCategoryAnalytics\(\s*(\w[\w.]*)\s*\)\.first\(\)',
            '.getCategoryAnalytics($1, "EUR").first'
    }
    if ($content -match '\.getCategoryAnalytics\(\s*(\w[\w.]*)\s*\)\.first') {
        $content = $content -replace
            '\.getCategoryAnalytics\(\s*(\w[\w.]*)\s*\)\.first(?!\w)',
            '.getCategoryAnalytics($1, "EUR").first'
    }

    # 8. getStatisticalInsights - add displayCurrency
    if ($content -match '\.getStatisticalInsights\(\s*(\w[\w.]*)\s*\)\.first') {
        $content = $content -replace
            '\.getStatisticalInsights\(\s*(\w[\w.]*)\s*\)\.first(?!\w)',
            '.getStatisticalInsights($1, "EUR").first'
    }

    # 9. getSpendingPatterns - add displayCurrency
    if ($content -match '\.getSpendingPatterns\(\s*(\w[\w.]*)\s*\)\.first') {
        $content = $content -replace
            '\.getSpendingPatterns\(\s*(\w[\w.]*)\s*\)\.first(?!\w)',
            '.getSpendingPatterns($1, "EUR").first'
    }

    # 10. getMerchantAnalytics - fix second param (was Int, should be String)
    if ($content -match '\.getMerchantAnalytics\(\s*(\w[\w.]*)\s*,\s*\d+\s*\)') {
        $content = $content -replace
            '\.getMerchantAnalytics\(\s*(\w[\w.]*)\s*,\s*\d+\s*\)',
            '.getMerchantAnalytics($1, "EUR")'
    }
    if ($content -match '\.getMerchantAnalytics\(\s*(\w[\w.]*)\s*,\s*"EUR"\s*\)\.size') {
        $content = $content -replace
            '\.getMerchantAnalytics\(\s*(\w[\w.]*)\s*,\s*"EUR"\s*\)\.size',
            '.getMerchantAnalytics($1, "EUR").first.size'
    }

    if ($content -ne $orig) {
        Set-Content $Path $content -Encoding UTF8 -NoNewline
        Write-Host "  FIXED: $name" -ForegroundColor Green
        return $true
    }
    return $false
}

# Process all Kotlin test files
Get-ChildItem $TestDir -Recurse -Filter "*.kt" | ForEach-Object {
    if (Fix-FileContent -Path $_.FullName) {
        $script:totalFixed++
    }
}

Write-Host "`n=== Total files fixed: $totalFixed ===" -ForegroundColor Cyan
