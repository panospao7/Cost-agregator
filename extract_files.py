#!/usr/bin/env python3
"""
Script to extract Kotlin source files into a markdown document.
"""

import os
from pathlib import Path

# Base directory
BASE_DIR = Path(r"C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\main\java\com\yourname\expensetracker")

# Files to extract (relative to BASE_DIR)
FILES = [
    ("data/database/dao/ExpenseDao.kt", "ExpenseDao.kt"),
    ("data/database/entity/Expense.kt", "Expense.kt"),
    ("data/database/entity/PendingReview.kt", "PendingReview.kt"),
    ("data/database/entity/RawNotification.kt", "RawNotification.kt"),
    ("domain/intelligence/DuplicateDetectionPolicy.kt", "DuplicateDetectionPolicy.kt"),
    ("domain/intelligence/ConfidenceRouter.kt", "ConfidenceRouter.kt"),
    ("domain/intelligence/TransactionClassifier.kt", "TransactionClassifier.kt"),
    ("domain/intelligence/ml/HybridExpenseClassifier.kt", "HybridExpenseClassifier.kt"),
    ("domain/intelligence/ml/MerchantNormalizer.kt", "MerchantNormalizer.kt"),
    ("domain/ai/service/NotificationFallbackParser.kt", "NotificationFallbackParser.kt"),
    ("data/ai/provider/OnDeviceNotificationParser.kt", "OnDeviceNotificationParser.kt"),
    ("service/NotificationCaptureService.kt", "NotificationCaptureService.kt"),
]

OUTPUT_FILE = "extracted_files.md"

def extract_file(file_path: Path) -> tuple[str, str]:
    """Extract file content and return (filename, content)."""
    try:
        content = file_path.read_text(encoding='utf-8')
        return file_path.name, content
    except Exception as e:
        return file_path.name, f"Error reading file: {e}"

def main():
    md_content = []
    md_content.append("# Extracted Kotlin Files\n")
    md_content.append("This document contains the source code of key files from the ExpenseTracker application.\n")
    
    for rel_path, display_name in FILES:
        file_path = BASE_DIR / rel_path
        print(f"Extracting: {rel_path}")
        
        if file_path.exists():
            filename, content = extract_file(file_path)
            
            md_content.append(f"## {display_name}\n")
            md_content.append(f"**Path:** `{rel_path}`\n")
            md_content.append("\n```kotlin\n")
            md_content.append(content)
            md_content.append("\n```\n")
            md_content.append("\n---\n\n")
        else:
            md_content.append(f"## {display_name}\n")
            md_content.append(f"**Path:** `{rel_path}`\n")
            md_content.append(f"\n⚠️ **File not found**: {file_path}\n")
            md_content.append("\n---\n\n")
    
    # Write to output
    output_path = Path(OUTPUT_FILE)
    output_path.write_text("\n".join(md_content), encoding='utf-8')
    print(f"\nDone! Output written to: {output_path.absolute()}")

if __name__ == "__main__":
    main()