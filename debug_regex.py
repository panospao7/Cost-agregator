import re

with open(r'C:\Users\panos\.local\share\opencode\tool-output\tool_df3de245c001NTzxMWbG9eUYkV', 'r', encoding='utf-8') as f:
    content = f.read()

# Find first few 'No value passed' errors
pattern = re.compile(r"No value passed for parameter '(\w+)'")
matches = list(pattern.finditer(content))
print(f'Found {len(matches)} missing param errors')
for m in matches[:3]:
    start = max(0, m.start() - 200)
    ctx = content[start:m.end()]
    print('===')
    print(repr(ctx[:200]))
    fpat = re.compile(r'file:///C:.*?expensetracker/(.+?):\d+:\d+')
    fm = fpat.search(ctx)
    if fm:
        print('File:', fm.group(1))
    else:
        print('No file match')
