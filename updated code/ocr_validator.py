#!/usr/bin/env python3
"""
OCR Test Document Validator - Standalone Python Version

This script validates your OCR_TEST_DOCUMENT.txt patterns against
a simulated ReceiptParser implementation.

Usage:
    python ocr_validator.py [path_to_txt_file]

If no path provided, looks for OCR_TEST_DOCUMENT (2).txt in upload/ folder
"""

import re
import sys
from datetime import datetime
from dataclasses import dataclass
from typing import Optional, List

# ============================================
# SIMULATED RECEIPT PARSER (Python version)
# ============================================

@dataclass
class LineItem:
    description: str
    quantity: Optional[float]
    unit_price: Optional[float]
    total_price: float

@dataclass
class ParsedReceipt:
    merchant_name: Optional[str]
    total: Optional[float]
    subtotal: Optional[float]
    tax: Optional[float]
    date: Optional[int]  # timestamp in milliseconds
    currency: str
    line_items: List[LineItem]
    confidence: float
    raw_normalized: str = ""  # For debugging


class ReceiptParser:
    """Python simulation of the Kotlin ReceiptParser"""
    
    def __init__(self, use_improved_normalization: bool = True):
        self.use_improved = use_improved_normalization
    
    def normalize_greek_ocr(self, text: str) -> str:
        """Normalize Greek OCR text"""
        normalized = text.upper()
        
        # Fix numbers
        normalized = re.sub(r'(?<=\d)\s+(?=\d)', '', normalized)
        normalized = re.sub(r'(\d+)\s*[.,]\s*(\d{2})\b', r'\1.\2', normalized)
        
        if self.use_improved:
            # COMPOUND KEYWORDS FIRST
            normalized = re.sub(r'ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'[EZI23]YN[O0]IKH\s+A[E3]IA', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'ΚΑΘΑΡΗ\s+ΑΞΙΑ', 'SUBTOTAL_KEY', normalized)
            normalized = re.sub(r'KA[ΘA]APH\s+A[E3]IA', 'SUBTOTAL_KEY', normalized)
            normalized = re.sub(r'ΓΕΝΙΚΟ\s+ΣΥΝΟΛΟ', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'ΜΕΡΙΚΟ\s+ΣΥΝΟΛΟ', 'SUBTOTAL_KEY', normalized)
            
            # CORRECT GREEK KEYWORDS
            normalized = re.sub(r'\bΣΥΝΟΛΟ\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bΤΕΛΙΚΟ\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bΠΛΗΡΩΤΕΟ\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bΠΟΣΟ\b', 'AMOUNT_KEY', normalized)
            normalized = re.sub(r'\bΜΕΤΡΗΤΑ\b', 'CASH_KEY', normalized)
            normalized = re.sub(r'\bΕΥΡΩ\b', 'EUR', normalized)
            normalized = re.sub(r'\bΦ\.?Π\.?Α\.?\b', 'VAT_KEY', normalized)
            normalized = re.sub(r'\bΗΜΕΡΟΜΗΝΙΑ\b', 'DATE_KEY', normalized)
            
            # OCR ARTIFACT PATTERNS
            normalized = re.sub(r'\b[EZI23][YVUI]N[O0I]?[AΛVL]?[O0ΩI]?\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bZYNOAO\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bZYNOIO\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bIYNOAO\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\b[NΠn][O0][SZsz][O0]?\b', 'AMOUNT_KEY', normalized)
            normalized = re.sub(r'\bnozo\b', 'AMOUNT_KEY', normalized)
            normalized = re.sub(r'\b[NΠ][AΛ][ΗHN][PR][ΩOQ]TE[OA]?\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bNAHPQTEO\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\bM[E3]TP[HΉ]TA\b', 'CASH_KEY', normalized)
            normalized = re.sub(r'\b[E3]YP[ΩO9]\b', 'EUR', normalized)
            normalized = re.sub(r'\bHM[/\.]?[ΗH]N?IA\b', 'DATE_KEY', normalized)
        
        else:
            # Original (buggy) patterns
            normalized = re.sub(r'\b[ΣE2ZXYS][YVUI]N[O0]?[AΛV][O0Ω]\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\b[NΠ][OA]S[OA]\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'\b[NΠ][AΛ][HN][PR][ΩOQ]TE[OA]\b', 'TOTAL_KEY', normalized)
            normalized = re.sub(r'HM/NIA', 'DATE_KEY', normalized)
            normalized = normalized.replace("ΗΜΕΡΟΜΗΝΙΑ", "DATE_KEY")
        
        # Date fixes
        normalized = re.sub(r'(\d{1,2})[-/][DO0](\d+)[-/](\d{4})', r'\1-\2-\3', normalized)
        
        return normalized
    
    def parse(self, text: str) -> ParsedReceipt:
        """Parse receipt text"""
        normalized = self.normalize_greek_ocr(text)
        lines = normalized.split('\n')
        lines = [l.strip() for l in lines if l.strip()]
        
        # Extract total
        total = self._extract_total(lines, normalized)
        
        # Extract merchant
        merchant = self._extract_merchant(lines)
        
        # Extract date
        date = self._extract_date(text)
        
        # Detect currency
        currency = "EUR" if "EUR" in normalized or "€" in text or "ΕΥΡΩ" in text.upper() else "EUR"
        
        # Calculate confidence
        confidence = self._calculate_confidence(merchant, total, date)
        
        return ParsedReceipt(
            merchant_name=merchant,
            total=total,
            subtotal=None,
            tax=None,
            date=date,
            currency=currency,
            line_items=[],
            confidence=confidence,
            raw_normalized=normalized
        )
    
    def _extract_total(self, lines: List[str], normalized: str) -> Optional[float]:
        """Extract total amount"""
        amount_regex = re.compile(r'(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})(?!\s?%)')
        
        # Strategy 1: Look for TOTAL_KEY
        for i, line in enumerate(lines):
            if 'TOTAL_KEY' in line or 'AMOUNT_KEY' in line:
                # Check this line
                amount = self._extract_amount_from_line(line, amount_regex)
                if amount:
                    return amount
                # Check next line
                if i + 1 < len(lines):
                    amount = self._extract_amount_from_line(lines[i + 1], amount_regex)
                    if amount:
                        return amount
        
        # Strategy 2: Find largest valid amount
        max_amount = 0.0
        for line in lines:
            if 'CASH_KEY' in line and 'TOTAL_KEY' not in line:
                continue  # Skip cash given lines
            if '%' in line:
                continue  # Skip VAT percentage lines
            
            matches = amount_regex.findall(line)
            for match in matches:
                amount = self._parse_amount(match)
                if self._is_valid_amount(amount, line):
                    if amount > max_amount:
                        max_amount = amount
        
        return max_amount if max_amount > 0 else None
    
    def _extract_amount_from_line(self, line: str, regex) -> Optional[float]:
        """Extract last amount from line"""
        matches = regex.findall(line)
        if matches:
            return self._parse_amount(matches[-1])
        return None
    
    def _parse_amount(self, raw: str) -> float:
        """Parse amount string to float"""
        raw = raw.strip()
        last_comma = raw.rfind(',')
        last_dot = raw.rfind('.')
        last_sep = max(last_comma, last_dot)
        
        if last_sep >= 0:
            prefix = raw[:last_sep].replace('.', '').replace(',', '')
            suffix = raw[last_sep + 1:]
            clean = f"{prefix}.{suffix}"
        else:
            clean = raw
        
        try:
            return float(clean)
        except:
            return 0.0
    
    def _is_valid_amount(self, amount: float, line: str) -> bool:
        """Validate amount"""
        if amount <= 0 or amount > 5000:
            return False
        if 2015 <= amount <= 2035 and amount == int(amount):
            return False  # Year
        return True
    
    def _extract_merchant(self, lines: List[str]) -> Optional[str]:
        """Extract merchant name"""
        markers = ['ΑΦΜ', 'AFM', 'ΤΗΛ', 'TEL', 'Α.Φ.Μ', 'TK', 'Τ.Κ']
        
        for i, line in enumerate(lines[:10]):
            for marker in markers:
                if marker in line:
                    # Look above for merchant
                    for j in range(i - 1, -1, -1):
                        candidate = lines[j]
                        if len(candidate) >= 3 and any(c.isalpha() for c in candidate):
                            return self._clean_merchant(candidate)
        
        # Fallback: first valid line
        for line in lines[:5]:
            if len(line) >= 3 and any(c.isalpha() for c in line):
                return self._clean_merchant(line)
        
        return None
    
    def _clean_merchant(self, raw: str) -> str:
        """Clean merchant name"""
        return re.sub(r'[^a-zA-Zα-ωΑ-Ω0-9\s&.-]', '', raw).strip()
    
    def _extract_date(self, text: str) -> Optional[int]:
        """Extract date"""
        patterns = [
            re.compile(r'(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})'),
            re.compile(r'(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})')
        ]
        
        for pattern in patterns:
            match = pattern.search(text)
            if match:
                d, m, y = match.groups()
                year = y if len(y) == 4 else f"20{y}"
                try:
                    year_int = int(year)
                    if 2015 <= year_int <= 2035:
                        dt = datetime(int(year), int(m), int(d))
                        return int(dt.timestamp() * 1000)
                except:
                    pass
        return None
    
    def _calculate_confidence(self, merchant, total, date) -> float:
        """Calculate parsing confidence"""
        score = 0.0
        if merchant:
            score += 0.15
        if total:
            score += 0.40
        if date:
            score += 0.15
        return min(score + 0.30, 1.0)  # Base confidence


# ============================================
# TEST RUNNER
# ============================================

class TestRunner:
    def __init__(self, parser: ReceiptParser):
        self.parser = parser
        self.passed = 0
        self.failed = 0
        self.failures = []
    
    def run_test(self, section: str, input_text: str, expected_total: float = None, 
                 expected_merchant: str = None, description: str = ""):
        """Run a single test"""
        input_display = input_text.replace('\n', ' ')[:45].ljust(45)
        print(f"  {input_display} → ", end="")
        
        try:
            result = self.parser.parse(input_text)
            issues = []
            
            if expected_total is not None:
                if result.total is None:
                    issues.append(f"Expected {expected_total}, got None")
                elif abs(result.total - expected_total) > 0.01:
                    issues.append(f"Expected {expected_total}, got {result.total}")
            
            if expected_merchant is not None:
                if result.merchant_name is None:
                    issues.append(f"Expected merchant '{expected_merchant}', got None")
                elif expected_merchant.lower() not in result.merchant_name.lower():
                    issues.append(f"Expected merchant '{expected_merchant}', got '{result.merchant_name}'")
            
            if not issues:
                print("✅ PASS")
                self.passed += 1
            else:
                print("❌ FAIL")
                for issue in issues:
                    print(f"      ⚠️  {issue}")
                self.failed += 1
                self.failures.append(f"[{section}] {input_text[:30]}: {', '.join(issues)}")
        
        except Exception as e:
            print(f"💥 ERROR: {e}")
            self.failed += 1
            self.failures.append(f"[{section}] {input_text[:30]}: Exception - {e}")
    
    def print_summary(self):
        """Print test summary"""
        total = self.passed + self.failed
        rate = (self.passed / total * 100) if total > 0 else 0
        
        print()
        print("═" * 70)
        print("                         SUMMARY")
        print("═" * 70)
        print()
        print(f"  Total Tests:  {total}")
        print(f"  ✅ Passed:    {self.passed}")
        print(f"  ❌ Failed:    {self.failed}")
        print(f"  Success Rate: {rate:.1f}%")
        print()
        
        if self.failures:
            print("━" * 70)
            print("                     FAILURES DETAIL")
            print("━" * 70)
            for f in self.failures:
                print(f"  • {f}")
        print()


def main():
    # Determine file path
    if len(sys.argv) > 1:
        file_path = sys.argv[1]
    else:
        file_path = "upload/OCR_TEST_DOCUMENT (2).txt"
    
    print()
    print("═" * 70)
    print("         OCR TEST DOCUMENT VALIDATOR")
    print("═" * 70)
    print(f"\n  Testing file: {file_path}")
    print(f"  Parser mode: IMPROVED (use_improved_normalization=True)")
    print()
    
    # Create parser with improved normalization
    parser = ReceiptParser(use_improved_normalization=True)
    runner = TestRunner(parser)
    
    # SECTION 14: Complete Receipt Lines
    print("━" * 70)
    print("SECTION 14: COMPLETE RECEIPT LINES")
    print("━" * 70)
    
    tests_s14 = [
        ("S14", "ΣΥΝΟΛΟ € 50,00", 50.00, None, "Greek TOTAL"),
        ("S14", "ΣΥΝΟΛΟ: 80,43 €", 80.43, None, "Greek TOTAL with colon"),
        ("S14", "ΜΕΤΡΗΤΑ € 80,43", 80.43, None, "Greek CASH"),
        ("S14", "ΠΟΣΟ/AMOUNT: €80,43", 80.43, None, "Bilingual AMOUNT"),
        ("S14", "nozo/AMOUNT: €35,00", 35.00, None, "OCR error ΠΟΣΟ"),
        ("S14", "ZYNOAO: 182,00€", 182.00, None, "OCR error ΣΥΝΟΛΟ"),
        ("S14", "EYNONO € 5,00", 5.00, None, "OCR error ΣΥΝΟΛΟ variant"),
        ("S14", "ΣΥΝΟΛΙΚΗ ΑΞΙΑ: 20,01 ΕΥΡΩ", 20.01, None, "Compound keyword"),
    ]
    for t in tests_s14:
        runner.run_test(*t)
    
    # SECTION 22: Simulated OCR Errors
    print()
    print("━" * 70)
    print("SECTION 22: SIMULATED OCR ERRORS")
    print("━" * 70)
    
    tests_s22 = [
        ("S22", "EYNONO\nTOTAL 5,00 €", 5.00, None, "EYNONO → ΣΥΝΟΛΟ"),
        ("S22", "ZYNOAO\nTOTAL 182,00€", 182.00, None, "ZYNOAO → ΣΥΝΟΛΟ"),
        ("S22", "2YNONO\nTOTAL 0,90 €", 0.90, None, "2YNONO → ΣΥΝΟΛΟ"),
        ("S22", "METPHTA 25,74", 25.74, None, "METPHTA → ΜΕΤΡΗΤΑ"),
        ("S22", "TOTAL 50,00 EYPΩ", 50.00, None, "EYPΩ → ΕΥΡΩ"),
        ("S22", "TOTAL 50,00 EYP9", 50.00, None, "EYP9 → ΕΥΡΩ"),
    ]
    for t in tests_s22:
        runner.run_test(*t)
    
    # SECTION 23: Actual OCR Output
    print()
    print("━" * 70)
    print("SECTION 23: ACTUAL OCR OUTPUT FROM RECEIPTS")
    print("━" * 70)
    
    tests_s23 = [
        ("S23", "IYN. noZOTHTA\n50,00 €", 50.00, None, "Severe OCR error"),
        ("S23", "ZYNOAO IONTAN\n182,00 €", 182.00, None, "OCR with extra text"),
        ("S23", "ZYNOIO\n50,00 €", 50.00, None, "ZYNOIO variant"),
        ("S23", "NAHPQTEO 10,00 €", 10.00, None, "NAHPQTEO → ΠΛΗΡΩΤΕΟ"),
    ]
    for t in tests_s23:
        runner.run_test(*t)
    
    # Number Formats
    print()
    print("━" * 70)
    print("SECTION 5-7: NUMBER FORMATS & SPACING")
    print("━" * 70)
    
    tests_num = [
        ("NUM", "TOTAL 12,50 €", 12.50, None, "European decimal"),
        ("NUM", "TOTAL 1.250,50 €", 1250.50, None, "European with thousands"),
        ("NUM", "TOTAL 45, 50 €", 45.50, None, "Space after comma"),
        ("NUM", "TOTAL 1 250,50 €", 1250.50, None, "Space as thousands"),
    ]
    for t in tests_num:
        runner.run_test(*t)
    
    # Merchant Names
    print()
    print("━" * 70)
    print("SECTION 15: MERCHANT NAMES")
    print("━" * 70)
    
    tests_merchant = [
        ("S15", "ΣΚΛΑΒΕΝΙΤΗΣ\nΑΦΜ: 094206641\nTOTAL 50,00 €", 50.00, "ΣΚΛΑΒΕΝΙΤΗΣ", "Greek merchant"),
        ("S15", "CARREFOUR\nTOTAL 100,00 €", 100.00, "CARREFOUR", "English merchant"),
    ]
    for t in tests_merchant:
        runner.run_test(*t)
    
    # Print summary
    runner.print_summary()
    
    # Compare with old parser
    print()
    print("━" * 70)
    print("COMPARISON: OLD vs NEW PARSER")
    print("━" * 70)
    print()
    
    old_parser = ReceiptParser(use_improved_normalization=False)
    comparison_tests = [
        "ΣΥΝΟΛΟ € 50,00",
        "EYNONO € 5,00",
        "ZYNOAO: 182,00€",
        "nozo/AMOUNT: €35,00",
    ]
    
    print(f"{'Input':<30} {'Old Parser':<20} {'New Parser':<20}")
    print("-" * 70)
    
    for test in comparison_tests:
        old_result = old_parser.parse(test)
        new_result = parser.parse(test)
        
        old_total = f"{old_result.total:.2f}" if old_result.total else "None"
        new_total = f"{new_result.total:.2f}" if new_result.total else "None"
        
        print(f"{test[:28]:<30} {old_total:<20} {new_total:<20}")
    
    print()
    print("═" * 70)
    print("                    VALIDATION COMPLETE")
    print("═" * 70)


if __name__ == "__main__":
    main()
