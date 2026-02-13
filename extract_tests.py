import os

def extract_tests(src_path, output_file):
    """
    Extracts all test files (src/test and src/androidTest) into a single Markdown file.
    """
    with open(output_file, 'w', encoding='utf-8') as out:
        out.write("# ExpenseTracker Test Suite Extraction\n\n")
        out.write("This file contains all unit tests and instrumentation tests from the codebase.\n\n")
        
        # 1. Generate Table of Contents
        out.write("## Table of Contents\n")
        file_list = []
        for root, dirs, files in os.walk(src_path):
            # ONLY include test and androidTest directories
            path_parts = root.split(os.sep)
            if "test" not in path_parts and "androidTest" not in path_parts:
                continue
            
            # Exclude build artifacts
            if "build" in path_parts or ".gradle" in path_parts or ".idea" in path_parts:
                continue
                
            for file in files:
                # Exclude binary and irrelevant assets
                ext = os.path.splitext(file)[1].lower()
                binary_exts = {
                    ".png", ".jpg", ".jpeg", ".webp", ".ico", ".pdf", ".bin",
                    ".ttf", ".otf", ".woff", ".woff2",  # Fonts
                    ".mp4", ".mov", ".avi",            # Video
                    ".mp3", ".wav",                   # Audio
                    ".zip", ".tar", ".gz", ".7z",      # Archives
                    ".jar", ".aar", ".so", ".exe"      # Binaries
                }
                if ext in binary_exts:
                    continue
                    
                abs_path = os.path.join(root, file)
                rel_path = os.path.relpath(abs_path, src_path)
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
    output_markdown = os.path.join(os.getcwd(), "tests_summary.md")
    
    print(f"Starting test extraction from: {src_folder}")
    extract_tests(src_folder, output_markdown)
    print(f"Extraction complete! Result saved to: {output_markdown}")
