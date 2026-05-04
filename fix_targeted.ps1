param([string]$TestDir = "C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\test\java\com\yourname\expensetracker")

Write-Host "=== Targeted Test Fix Script ===" -ForegroundColor Cyan

$fixed = 0

Get-ChildItem $TestDir -Recurse -Filter "*.kt" | ForEach-Object {
    $path = $_.FullName
    $content = Get-Content $path -Raw -Encoding UTF8
    $orig = $content

    # ==== Fix 1: OversightEngine missing privacyGate ====
    # Look for "OversightEngine(" constructor that doesn't have privacyGate
    if ($content -match 'OversightEngine\(' -and $content -notmatch 'privacyGate') {
        # This pattern needs more careful handling - skip for now
    }

    # ==== Fix 2: Add missing imports when using mockk without import ====
    if ($content -match '(?<![.\w])mockk\(' -and 
        $content -notmatch 'import io\.mockk\.mockk' -and
        $content -notmatch 'import io\.mockk\.\*') {
        $content = $content -replace '(package [\w.]+)', "`$1`nimport io.mockk.mockk"
    }

    # ==== Fix 3: SmartReceiptAssistService - add privacyGate param ====
    if ($path -match 'SmartReceiptAssistServiceTest\.kt') {
        # Add privacyGate as last constructor param
        $content = $content -replace 
            'SmartReceiptAssistService\(\s*aiSettingsRepository\s*=\s*mockk\(\),\s*secureKeyStorage\s*=\s*mockk\(\)\s*\)',
            'SmartReceiptAssistService(aiSettingsRepository = mockk(), secureKeyStorage = mockk(), privacyGate = mockk())'
    }
    
    # ==== Fix 4: Geocoding-related tests with missing privacyGate ====
    if ($path -match 'GeocodingCancellationTest\.kt') {
        $content = $content -replace
            'NominatimGeocodingService\(\s*mockk\(\),\s*mockk\(\),\s*mockk\(\)\s*\)',
            'NominatimGeocodingService(mockk(), mockk(), mockk(), mockk())'
    }
    
    if ($path -match 'GeocodingRetryHttpSemanticsTest\.kt') {
        $content = $content -replace
            'NominatimGeocodingService\(\s*mockk\(\),\s*mockk\(\),\s*mockk\(\)\s*\)',
            'NominatimGeocodingService(mockk(), mockk(), mockk(), mockk())'
    }

    # ==== Fix 5: LocationBackfillWorkerTest - add privacyGate and restoreMaintenanceMode ====
    if ($path -match 'LocationBackfillWorkerTest\.kt') {
        # Add privacyGate and restoreMaintenanceMode after existing params
        $content = $content -replace
            'LocationBackfillWorker\(\s*context\s*=\s* mockk\(\),\s*params\s*=\s* mockk\(\),\s*geocodingService\s*=\s* mockk\(\)\)',
            'LocationBackfillWorker(context =  mockk(), params =  mockk(), geocodingService =  mockk(), privacyGate = mockk(), restoreMaintenanceMode = mockk())'
    }

    # ==== Fix 6: OverpassNearbyServiceTest - add privacyGate ====
    if ($path -match 'OverpassNearbyServiceTest\.kt') {
        $content = $content -replace
            'OverpassNearbyService\(\s*mockk\(\),\s*mockk\(\)\s*\)',
            'OverpassNearbyService(mockk(), mockk(), mockk())'
    }

    # ==== Fix 7: DailyBriefingWorkerTest - add privacyGate ====
    if ($path -match 'DailyBriefingWorkerTest\.kt') {
        $content = $content -replace
            'DailyBriefingWorker\(\s*context\s*=\s*mockk\(\),\s*params\s*=\s*mockk\(\),\s*aiRoutingService\s*=\s*mockk\(\),\s*briefingRepository\s*=\s*mockk\(\),\s*settingsRepository\s*=\s*mockk\(\)\)',
            'DailyBriefingWorker(context = mockk(), params = mockk(), aiRoutingService = mockk(), briefingRepository = mockk(), settingsRepository = mockk(), privacyGate = mockk())'
    }

    # ==== Fix 8: NaturalLanguageSearchEngine - add timeProvider, currencyConverter, currencySettingsRepository ====
    if ($path -match 'NaturalLanguageSearchEngineVoiceInputTest\.kt') {
        $content = $content -replace
            'NaturalLanguageSearchEngine\(\s*searchRepository\s*=\s*mockk\(\),\s*modelLoader\s*=\s*mockk\(\)\s*\)',
            'NaturalLanguageSearchEngine(searchRepository = mockk(), modelLoader = mockk(), timeProvider = mockk(), currencyConverter = mockk(), currencySettingsRepository = mockk())'
    }

    # ==== Fix 9: LocationResolver - add timeProvider, privacyGate ====
    if ($path -match 'LocationResolver(Stress)?Test\.kt') {
        $content = $content -replace
            'LocationResolver\(\s*mockk\(\),\s*mockk\(\),\s*mockk\(\)\s*\)',
            'LocationResolver(mockk(), mockk(), mockk(), mockk(), mockk())'
    }

    # ==== Fix 10: AnomalyDetectorTest - add timeProvider ====
    if ($path -match 'AnomalyDetectorTest\.kt') {
        $content = $content -replace
            'AnomalyDetector\(\s*mockk\(\),\s*mockk\(\),\s*mockk\(\)\s*\)',
            'AnomalyDetector(mockk(), mockk(), mockk(), mockk())'
    }

    # ==== Fix 11: Update SpendingPersonalityClassifier currency params ====
    if ($path -match 'SpendingPersonalityClassifierTest\.kt') {
        $content = $content -replace
            'SpendingPersonality\([\s\S]*?type = [\w.]+\)',
            '${0}, currency = "EUR"'
        # Too risky - skip
    }

    if ($content -ne $orig) {
        Set-Content $path $content -Encoding UTF8 -NoNewline
        Write-Host "  Fixed: $(Split-Path $path -Leaf)" -ForegroundColor Green
        $fixed++
    }
}

Write-Host "`n=== Total files fixed: $fixed ===" -ForegroundColor Cyan
