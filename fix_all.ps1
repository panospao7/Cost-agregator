param([string]$Dir = "C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker")

Write-Host "=== Comprehensive Fix Script ==="

function Ensure-Import {
    param([string]$Content, [string]$Import)
    if ($Content -notmatch [regex]::Escape($Import)) {
        $Content = $Content -replace "(package [\w.]+)", "`$1`n$Import"
    }
    return $Content
}

$allFiles = Get-ChildItem $Dir -Recurse -Filter "*.kt" | ForEach-Object { $_.FullName }
$fixed = 0

foreach ($path in $allFiles) {
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content
    $name = Split-Path $path -Leaf
    
    # ========== FIXES ==========
    
    # 1. Add mockk import if using mockk or io.mockk.mockk
    $usesMockk = $content -match '(?<![.\w])mockk\(' -or $content -match 'io\.mockk\.mockk'
    if ($usesMockk -and $content -notmatch 'import io\.mockk\.mockk' -and $content -notmatch 'import io\.mockk\*') {
        $content = Ensure-Import -Content $content -Import "import io.mockk.mockk"
    }
    
    # 2. Replace io.mockk.mockk() with mockk() if import is available
    if ($content -match 'import io\.mockk\.mockk') {
        $content = $content -replace 'io\.mockk\.mockk\(\)', 'mockk()'
    }
    
    # 3. Add missing currency parameter to BudgetSnapshot
    $content = $content -replace 
        'BudgetSnapshot\(\s*categoryId\s*=\s*(\d+)\s*,\s*amount\s*=\s*([\d.]+)\s*\)(?!\s*\{)',
        'BudgetSnapshot(categoryId = $1, amount = $2, currency = "EUR")'
    
    # 4. Fix getCategoryAnalytics(period).first() -> getCategoryAnalytics(period, "EUR").first
    $content = $content -replace
        '(\w[\w.]*)\.getCategoryAnalytics\(\s*(\w[\w.]*)\s*\)\.first\(\)',
        '$1.getCategoryAnalytics($2, "EUR").first'
    
    # 5. Fix getStatisticalInsights(period).first
    $content = $content -replace
        '(\w[\w.]*)\.getStatisticalInsights\(\s*(\w[\w.]*)\s*\)\.first(?!\w)',
        '$1.getStatisticalInsights($2, "EUR").first'
    
    # 6. Fix getSpendingPatterns(period).first
    $content = $content -replace
        '(\w[\w.]*)\.getSpendingPatterns\(\s*(\w[\w.]*)\s*\)\.first(?!\w)',
        '$1.getSpendingPatterns($2, "EUR").first'
    
    # 7. Fix getMerchantAnalytics(period, number) -> getMerchantAnalytics(period, "EUR")
    $content = $content -replace
        '(\w[\w.]*)\.getMerchantAnalytics\(\s*(\w[\w.]*)\s*,\s*(\d+)\s*\)',
        '$1.getMerchantAnalytics($2, "EUR")'
    
    # 8. Fix .getMerchantAnalytics(..., "EUR").size -> ...first.size
    $content = $content -replace
        '\.getMerchantAnalytics\(([^)]+)\)\.size',
        '.getMerchantAnalytics($1).first.size'
    
    # 9. GreekBankParser with 2 args - add "EUR"
    $content = $content -replace
        '(?<!new )GreekBankParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)(?!\s*\.)',
        'GreekBankParser($1, $2, "EUR")'
    
    # 10. GenericTransactionParser with 3 args - add mockk timeProvider
    $content = $content -replace
        'GenericTransactionParser\(\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*,\s*(\w[\w.]*)\s*\)(?!\s*\{)',
        'GenericTransactionParser($1, $2, $3, mockk())'
    
    # 11. AppParserRegistry - add timeProvider
    $content = $content -replace
        '(aiFallbackParser\s*=\s*mockk\(\))\s*\)',
        "`$1,`n        timeProvider = mockk()"
    
    # 12. ReceiptParser - add timeProvider as second arg
    # Match ReceiptParser(MerchantRulesRepository()) or ReceiptParser(MerchantRulesRepository(), ...)
    $content = $content -replace
        'ReceiptParser\(\s*(MerchantRulesRepository|MerchantRulesPolicy)\(\)\s*\)(?!\s*\{)',
        'ReceiptParser($1(), mockk())'
    
    # 13. ReceiptTransactionMatcher - add timeProvider and receiptLinkService
    $content = $content -replace
        '(stringDistance\s*=\s*\w[\w.]*\s*)',
        "`$1`n        timeProvider = mockk(),`n        receiptLinkService = mockk()"
    
    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  Fixed: $name" -ForegroundColor Green
        $fixed++
    }
}

Write-Host "`n=== Total files fixed: $fixed ===" -ForegroundColor Cyan
