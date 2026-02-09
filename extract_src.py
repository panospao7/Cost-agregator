import os

def extract_codebase(src_path, output_file):
    """
    Extracts all text files from a source directory into a single Markdown file.
    """
    with open(output_file, 'w', encoding='utf-8') as out:
        out.write("# ExpenseTracker Full Source Code Extraction\n\n")
        out.write("This file contains the complete source code from the `src` directory.\n\n")
        
        # 1. Generate Table of Contents
        out.write("## Table of Contents\n")
        file_list = []
        for root, dirs, files in os.walk(src_path):
            # Exclude specific directories for UI refactoring
            excluded_dirs = {
                "test", "androidTest", "di", "service", "receiver", 
                "dao", "converter", "parser"
            }
            if any(part in root.split(os.sep) for part in excluded_dirs):
                continue
                
            for file in files:
                # Exclude specific logic-heavy files
                excluded_files = {"MerchantCategoryProvider", "ReceiptParser"}
                if any(ex in file for ex in excluded_files):
                    continue
                
                # Exclude binary and irrelevant assets
                ext = os.path.splitext(file)[1].lower()
                binary_exts = {".png", ".jpg", ".jpeg", ".webp", ".ico", ".pdf", ".bin"}
                if ext in binary_exts:
                    continue
                    
                rel_path = os.path.relpath(os.path.join(root, file), src_path)
                file_list.append(rel_path)
        
        for i, path in enumerate(sorted(file_list), 1):
            anchor = path.lower().replace('\\', '').replace('/', '').replace('.', '').replace(' ', '-')
            out.write(f"{i}. [{path}](#{anchor})\n")
        
        out.write("\n---\n\n")
        
        # 2. Append File Contents
        for rel_path in sorted(file_list):
            abs_path = os.path.join(src_path, rel_path)
            anchor = rel_path.lower().replace('\\', '').replace('/', '').replace('.', '').replace(' ', '-')
            
            # Determine language for markdown code block
            ext = os.path.splitext(rel_path)[1].lower()
            lang = "kotlin" if ext == ".kt" else "xml" if ext == ".xml" else ""
            
            out.write(f"## {rel_path} <a name=\"{anchor}\"></a>\n")
            
            try:
                with open(abs_path, 'r', encoding='utf-8') as f:
                    # Eliminate empty lines to make it more compact
                    lines = f.readlines()
                    compact_content = "".join([line for line in lines if line.strip()])
                    out.write(f"```{lang}\n{compact_content}\n```\n\n")
            except Exception as e:
                out.write(f"*Error reading file: {str(e)}*\n\n")
            
            out.write("---\n\n")


if __name__ == "__main__":
    # Path to the app/src directory relative to project root
    src_folder = os.path.join(os.getcwd(), "app", "src")
    output_markdown = os.path.join(os.getcwd(), "codebase_summary.md")
    
    print(f"Starting extraction from: {src_folder}")
    extract_codebase(src_folder, output_markdown)
    print(f"Extraction complete! Result saved to: {output_markdown}")
