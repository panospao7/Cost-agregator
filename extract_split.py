import os
import shutil

def get_category(file_path):
    """
    Determines the category/output file based on the file path.
    """
    path_lower = file_path.lower().replace('\\', '/')
    
    # Configuration Files
    if any(x in path_lower for x in ['build.gradle', 'settings.gradle', 'androidmanifest.xml', 'proguard-rules']):
        return "5_Project_Config.md"
        
    # Source Code Categories
    if '/ui/' in path_lower:
        return "1_Presentation_Layer.md"
    elif '/domain/' in path_lower:
        return "2_Domain_Layer.md"
    elif '/data/' in path_lower:
        return "3_Data_Layer.md"
    elif any(x in path_lower for x in ['/di/', '/service/', '/receiver/', 'expensetrackerapp.kt', '/application/']):
        return "4_Infrastructure_DI.md"
    
    # Fallback for other source files
    if path_lower.endswith('.kt') or path_lower.endswith('.java'):
        return "4_Infrastructure_DI.md" # Put miscellaneous code here
        
    return None

def extract_split_codebase(root_dir, output_dir):
    """
    Extracts code into categorized Markdown files.
    """
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir)
    
    # Initialize content buffers
    categories = {
        "1_Presentation_Layer.md": [],
        "2_Domain_Layer.md": [],
        "3_Data_Layer.md": [],
        "4_Infrastructure_DI.md": [],
        "5_Project_Config.md": []
    }
    
    print(f"Scanning project from: {root_dir}")
    
    # 1. Scan and collect files
    for root, dirs, files in os.walk(root_dir):
        # Exclude directories
        dirs[:] = [d for d in dirs if d not in {
            "build", ".gradle", ".idea", ".git", "gradle", 
            "test", "androidTest", "res", "generated", "assets"
        }]
        
        for file in files:
            abs_path = os.path.join(root, file)
            rel_path = os.path.relpath(abs_path, root_dir)
            
            # Filter by extension/type
            ext = os.path.splitext(file)[1].lower()
            if ext in {'.png', '.jpg', '.jpeg', '.webp', '.ico', '.jar', '.class', '.dex', '.so', '.pdf'}:
                continue
                
            # Determine category
            category = get_category(rel_path)
            if category:
                categories[category].append((rel_path, abs_path))

    # 2. Write Output Files
    for filename, file_list in categories.items():
        if not file_list:
            continue
            
        output_path = os.path.join(output_dir, filename)
        with open(output_path, 'w', encoding='utf-8') as out:
            # Header
            out.write(f"# {filename.replace('.md', '').replace('_', ' ')}\n\n")
            
            # Table of Contents
            out.write("## Table of Contents\n")
            sorted_files = sorted(file_list, key=lambda x: x[0])
            for i, (rel_path, _) in enumerate(sorted_files, 1):
                anchor = rel_path.lower().replace('\\', '').replace('/', '').replace('.', '').replace(' ', '-')
                out.write(f"{i}. [{rel_path}](#{anchor})\n")
            out.write("\n---\n\n")
            
            # File Contents
            for rel_path, abs_path in sorted_files:
                anchor = rel_path.lower().replace('\\', '').replace('/', '').replace('.', '').replace(' ', '-')
                ext = os.path.splitext(rel_path)[1].lower()
                lang = "kotlin" if ext == ".kt" else "xml" if ext == ".xml" else "groovy" if "gradle" in rel_path else ""
                
                out.write(f"## {rel_path} <a name=\"{anchor}\"></a>\n")
                try:
                    with open(abs_path, 'r', encoding='utf-8') as f:
                        content = f.read()
                        # Simple minification: remove multiple empty lines
                        import re
                        content = re.sub(r'\n\s*\n', '\n\n', content)
                        out.write(f"```{lang}\n{content}\n```\n\n")
                except Exception as e:
                    out.write(f"*Error reading file: {str(e)}*\n\n")
                out.write("---\n\n")
                
        print(f"Generated: {filename} ({len(file_list)} files)")

if __name__ == "__main__":
    project_root = os.getcwd()
    output_folder = os.path.join(project_root, "extracted_codebase")
    extract_split_codebase(project_root, output_folder)
    print(f"\nExtraction success! Files saved to: {output_folder}")
