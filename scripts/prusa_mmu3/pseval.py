"""Minimal re-implementation of PrusaSlicer 3.x YAML preset evaluation
(PresetCollectionEvaluator.cpp) for research purposes."""
import yaml, glob, os, re, copy, sys

import os as _os
D = _os.path.join(_os.environ.get("TEST_SLICER", "/mnt/faststorage/Test Slicer"), "PrusaSlicer/resources/presets/prusa-research-fff/PrusaResearch")

docs = []
for f in sorted(glob.glob(os.path.join(D, "preset-*.yaml"))):
    for d in yaml.safe_load_all(open(f)):
        if d:
            d["__file"] = os.path.basename(f)
            docs.append(d)
vendor = [d for d in yaml.safe_load_all(open(os.path.join(D, "vendor.yaml"))) if d]

named = {}
for d in docs:
    named.setdefault((d["kind"], d["id"]), d)
    named.setdefault(("*", d["id"]), d)

paths = {}
def _walk(n, path):
    path = path + [n]
    for key in (n.get("id"), n.get("name")):
        if key: paths.setdefault(key, path)
    for v in n.get("variants") or []: _walk(v, path)
for d in docs: _walk(d, [])

def lookup(kind, i):
    return named.get((kind, i)) or named[("*", i)]

# ---------- condition evaluation ----------
class NS(dict):
    def __getattr__(self, k):
        return self.get(k)

def _m(val, rx):
    return re.fullmatch(rx.replace("\\/", "/"), str(val if val is not None else "")) is not None

def translate(cond):
    s = cond
    s = re.sub(r"([\w.]+)\s*=~\s*/(.*?)/", lambda m: f"_m({m.group(1)}, r'{m.group(2)}')", s)
    s = re.sub(r"([\w.]+)\s*!~\s*/(.*?)/", lambda m: f"(not _m({m.group(1)}, r'{m.group(2)}'))", s)
    s = re.sub(r"!(?!=)", " not ", s)
    s = s.replace("$.", "dollar.")
    return s

def ev(cond, vars):
    code = translate(cond)
    try:
        return bool(eval(code, {"_m": _m, **vars}))
    except Exception as e:
        raise RuntimeError(f"cond {cond!r} -> {code!r}: {e}")

# ---------- tree evaluation ----------
def new_ctx(mm="first"):
    return {"id": [], "name": "", "values": {}, "mm": mm, "files": []}

def derive_name(name, parent):
    if not name: return parent
    base = name.split("@")[0]
    ntags = name.split("@")[1:]
    ptags = parent.split("@")[1:] if parent else []
    r = base
    for t in ntags:
        if t not in ptags: r += "@" + t
    for t in ptags: r += "@" + t
    return r

def eval_node(node, kind, parents, vars, mode="down", skip=False):
    cond = node.get("condition")
    if not skip and cond is not None and not ev(cond, vars):
        return []
    ret = copy.deepcopy(parents)
    for inh in node.get("inherits", []) or []:
        ret = eval_node(lookup(kind, inh), kind, ret, vars, "down", skip or cond is not None)
        if not ret:
            return []
    unc = {}
    for inh in node.get("unconditional_inherits", []) or []:
        for pn in paths[inh]:
            for c in eval_node(pn, kind, [new_ctx()], vars, "nodeonly", True):
                unc.update(c["values"])
    for c in ret:
        if node.get("id"): c["id"].append(node["id"])
        if node.get("name"): c["name"] = derive_name(node["name"], c["name"])
        if node.get("match_mode"): c["mm"] = "first" if node["match_mode"] == "first_match" else "all"
        c["values"].update(unc)
        c["values"].update(node.get("values") or {})
        if "__file" in node: c["files"].append(node["__file"])
    if mode != "down":
        return ret
    first = all(c["mm"] == "first" for c in ret)
    varctx = []
    for var in node.get("variants", []) or []:
        vc = var.get("condition")
        if vc is not None and not ev(vc, vars):
            continue
        varctx += eval_node(var, kind, [new_ctx("first" if first else "all")], vars, "down", True)
        if vc is not None and first:
            break
    if not varctx:
        return ret
    out = []
    for c in ret:
        for v in varctx:
            n = copy.deepcopy(c)
            n["id"] += v["id"]; n["name"] = derive_name(v["name"], n["name"])
            n["values"].update(v["values"])
            n["mm"] = v["mm"]
            out.append(n)
    return out

# ---------- HW variables ----------
vendor_features = next(d for d in vendor if d.get("kind") == "vendor")["features"]
printers = {d["id"]: d for d in vendor if d.get("kind") == "printer"}
feeders = {d["id"]: d for d in vendor if d.get("kind") == "feeder"}
pconfigs = {d["id"]: d for d in vendor if d.get("kind") == "printer_config"}

def hw_vars(pcfg_id, nozzle, hf=False, layer_height=0.2):
    pc = pconfigs[pcfg_id]
    pr = printers[pc["printer"]]
    pf = {k: v["default"] for k, v in vendor_features["printer"].items()}
    for k, v in (pr.get("features") or {}).items():
        pf[k] = v["default"] if isinstance(v, dict) else v
    for k, v in (pc.get("features") or {}).items():
        pf[k] = v["default"] if isinstance(v, dict) else v
    printer = NS(model=pr["model"]["model"], base_model=pr["model"]["base_model"],
                 tool_count=pc.get("tool_count", pr.get("tool_count", 1)), **pf)
    if pc.get("feeders"):
        fd = feeders[pc["feeders"][0]["feeder"]]
        feeder = NS(model=fd["model"]["model"], base_model=fd["model"]["base_model"], single_mode=False)
    else:
        feeder = NS(model="", base_model="", single_mode=False)
    tool = NS(nozzle_diameter=nozzle, nozzle_high_flow=hf)
    sheet = NS(type=pc.get("sheet"), cold=False)
    prnt = NS(layer_height=layer_height)
    return {"printer": printer, "feeder": feeder, "tool": tool, "sheet": sheet, "print": prnt,
            "true": True, "false": False}

def eval_kind(kind, vars, root_filter=None):
    res = []
    for d in docs:
        if d["kind"] != kind: continue
        if root_filter and not root_filter(d): continue
        # only root presets that are "public" or have a name somewhere
        res += [dict(c, root=d["id"], rootfile=d["__file"]) for c in eval_node(d, kind, [new_ctx()], vars)]
    return res

def fullname(c):
    # PrusaSlicer derive_name: take last non-abstract name; approximate by joining
    return c["name"]

def printer_preset(pcfg, noz, hf=False):
    r = [c for c in eval_kind('printer', hw_vars(pcfg, noz, hf)) if c['name']]
    assert len(r) == 1, [(x['root'], x['name']) for x in r]
    return r[0]

def filament(fid, cfg, noz, hf=False):
    d = [x for x in docs if x['kind'] == 'filament' and x['id'] == fid][0]
    r = eval_node(d, 'filament', [new_ctx()], hw_vars(cfg, noz, hf))
    assert len(r) == 1, [x['name'] for x in r]
    return r[0]
