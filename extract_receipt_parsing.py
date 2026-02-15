import os

def extract_specific_files(file_list, output_file):
    """
    Extracts specific source files into a single Markdown file.
    """
    project_root = os.getcwd()
    
    with open(output_file, 'w', encoding='utf-8') as out:
        out.write("# Receipt Parsing Functionality - Source Code\n\n")
        out.write("This file contains the core logic for receipt OCR, parsing, and management.\n\n")
        
        # 1. Generate Table of Contents
        out.write("## Table of Contents\n")
        
        valid_files = []
        for rel_path in file_list:
            abs_path = os.path.join(project_root, rel_path.replace('/', os.sep))
            if os.path.exists(abs_path):
                valid_files.append(rel_path)
            else:
                print(f"Warning: File not found: {rel_path}")

        for i, path in enumerate(valid_files, 1):
            anchor = path.lower().replace('\\', '').replace('/', '').replace('.', '').replace(' ', '-')
            out.write(f"{i}. [{path}](#{anchor})\n")
        
        out.write("\n---\n\n")
        
        # 2. Append File Contents
        for rel_path in valid_files:
            abs_path = os.path.join(project_root, rel_path.replace('/', os.sep))
            anchor = rel_path.lower().replace('\\', '').replace('/', '').replace('.', '').replace(' ', '-')
            
            # Determine language for markdown code block
            ext = os.path.splitext(rel_path)[1].lower()
            lang = "kotlin" if ext == ".kt" else "python" if ext == ".py" else ""
            
            out.write(f"## {rel_path} <a name=\"{anchor}\"></a>\n")
            
            try:
                with open(abs_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                    out.write(f"```{lang}\n{content}\n```\n\n")
            except Exception as e:
                out.write(f"*Error reading file: {str(e)}*\n\n")
            
            out.write("---\n\n")

if __name__ == "__main__":
    # List of files identified as responsible for receipt parsing
    receipt_files = [
        "app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt",
        "app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt",
        "app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt",
        "app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt",
        "app/src/test/java/com/yourname/expensetracker/OcrDocumentTest.kt",
        "app/src/test/java/com/yourname/expensetracker/domain/receipt/GreekNormalizationTest.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt"
    ]
    
    output_markdown = os.path.join(os.getcwd(), "receipt_parsing_files.md")
    
    print(f"Starting extraction of {len(receipt_files)} files...")
    extract_specific_files(receipt_files, output_markdown)
    print(f"Extraction complete! Codebase saved to: {output_markdown}")
