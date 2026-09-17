"""Scratch exploration - delete after use."""
import sys, os
sys.path.insert(0, os.path.abspath('scripts'))
import verify_db_access_boundaries as m

entries = m.load_db_ownership_policy()
errors = []

for idx, e in enumerate(entries):
    label = f"entry #{idx + 1} ({e['path']} {e['class']}.{e['method']})"
    rel = e['path']
    fpath = os.path.join(m.PROJECT_ROOT, rel)
    if not os.path.exists(fpath):
        errors.append((label, "SOURCE MISSING"))
        continue
    with open(fpath, encoding="utf-8") as f:
        lines = f.readlines()
    types = m.parse_type_declarations(lines)
    class_names = [t for t in types if t["name"] == e["class"]]
    if len(class_names) == 0:
        errors.append((label, "CLASS MISSING"))
        continue
    if len(class_names) > 1:
        errors.append((label, "CLASS AMBIGUOUS"))
        continue
    t = class_names[0]
    methods = m.parse_function_declarations(lines, t["start"], t["end"])
    ms = [mm for mm in methods if mm["name"] == e["method"]]
    if len(ms) == 0:
        errors.append((label, f"METHOD MISSING (methods in class: {sorted({x['name'] for x in methods})})"))
        continue

    method_body_lines = set()
    for o in methods:
        method_body_lines.update(range(o["start"], o["end"] + 1))
    class_map = m.build_class_scope_dao_var_map(lines, t["start"], t["end"], excluded_line_numbers=method_body_lines)

    found_pairs = {}
    for mm in ms:
        body_lines = mm["body"].split("\n")
        local_map = m.build_dao_var_map(body_lines, 0, len(body_lines) - 1)
        var_map = {**class_map, **local_map}
        pairs = m.extract_mutation_pairs(mm["body"], var_map)
        found_pairs[mm["start"]] = (pairs, var_map, mm)

    for dao in e["daos"]:
        pair = (dao, e["operation"])
        hits = [start for start, (pairs, _, _) in found_pairs.items() if pair in pairs]
        if not hits:
            all_pairs = sorted({p for (pairs, _, _) in found_pairs.values() for p in pairs})
            errors.append((label, f"PAIR MISSING ({dao}, {e['operation']}); extracted: {all_pairs}"))

    if e.get("barrier_required"):
        for start, (pairs, var_map, mm) in found_pairs.items():
            matches = m._extract_mutation_matches(mm["body"], var_map=var_map)
            for match in matches:
                if (match["dao"], match["op"]) != (e["daos"][0], e["operation"]):
                    continue
                abs_lineno = mm["start"] + match["lineno"] + 1
                ok = m._barrier_before_line(lines, mm["start"], abs_lineno)
                if not ok:
                    errors.append((label, f"BARRIER MISSING before {match['dao']}.{match['op']} at line {abs_lineno}"))

print(f"checked {len(entries)} entries, {len(errors)} problems")
for label, msg in errors:
    print("-", label, "->", msg)
