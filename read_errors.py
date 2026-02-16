
import re

try:
    with open('build_output.txt', 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()
        
    # Search for TransactionsScreen.kt and print lines around it
    if "TransactionsScreen.kt" in content:
        print("Found TransactionsScreen.kt in output:")
        lines = content.split('\n')
        for i, line in enumerate(lines):
            if "TransactionsScreen.kt" in line:
                # Print 5 lines before and 20 after
                start = max(0, i - 5)
                end = min(len(lines), i + 20)
                for j in range(start, end):
                    print(lines[j])
                print("-" * 20)

    # Search for "error" case insensitive
    print("\nScanning for generic errors:")
    lines = content.split('\n')
    for line in lines:
        if "error" in line.lower() and "compilation error" not in line.lower(): # skip generic "compilation error" message
             print(line.strip())

except Exception as e:
    print(f"Error reading file: {e}")
