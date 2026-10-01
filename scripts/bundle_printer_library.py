#!/usr/bin/env python3
"""Bundle a printer model's whole profile family from its own slicer, flattened: one machine per nozzle size, and for
each the process presets and filament profiles that slicer marks compatible with it (compatible_printers), plus its
defaults. Prepare then offers the same nozzle sizes, process presets and per-slot filaments the vendor's slicer does.
Source for the Snapmaker U1: Snapmaker Orca's own profiles at the engine pin (engine/profiles/SOURCES.json).

  bundle_printer_library.py --profiles <Snapmaker Orca>/resources/profiles --vendor Snapmaker \
      --machine "Snapmaker U1 (0.2 nozzle)" --machine "Snapmaker U1 (0.4 nozzle)" ... --out engine/profiles/library/snapmaker_u1

Writes machine/<id>.json, process/<id>.json, filament/<id>.json and index.json.
Elegoo's Centauri Carbon / Centauri Carbon 2 come from ElegooSlicer (--nested, since it keeps profiles in sub-folders,
and --source naming it).

Which presets suit a machine is the engine's call (--engine: a nozzle-engine with --compatible-presets, which runs Orca's
own is_compatible_with_printer: compatible_printers, else compatible_printers_condition evaluated against the machine).
--derived adds print presets Nozzle It All makes itself where the vendor ships none (engine/profiles/derived/*.json).
"""
import argparse, json, os, re, shutil, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from flatten_orca_profile import index, flatten, pin_defaults  # noqa: E402


def slug(name): return re.sub(r"[^a-z0-9]+", "_", name.lower().replace("+", " plus")).strip("_")  # "PLA+" must not become "PLA"
def match_name(name): return re.sub(r"\s+\d+(\.\d+)?\s+nozzle$", "", name).strip()
def compatible_with(engine, machine_path, machine_name, kind, preset_paths):
    """Orca's own compatibility verdict for each flattened preset file against a flattened machine (nozzle-engine)."""
    if not preset_paths: return []
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump({"printer": machine_path, "printerName": machine_name, "type": "print" if kind == "process" else kind,
                   "presets": preset_paths}, f)
    try: out = subprocess.run([engine, "--compatible-presets", f.name], capture_output=True, text=True, timeout=600)
    finally: os.unlink(f.name)
    try: verdict = json.loads(out.stdout.strip().splitlines()[-1])["compatible"]
    except (IndexError, KeyError, ValueError): raise SystemExit(f"--compatible-presets failed (exit {out.returncode}): {out.stdout[-400:]}{out.stderr[-400:]}")
    if len(verdict) != len(preset_paths): raise SystemExit("--compatible-presets answered for the wrong number of presets")
    return verdict


def derive(spec, by_name):
    """A Nozzle It All print preset: the vendor's [base] preset with [set] applied (engine/profiles/derived/*.json)."""
    flat = flatten("process", spec["base"], by_name)
    flat.update(spec["set"])
    flat.update({"name": spec["name"], "description": spec["description"], "compatible_printers": spec["machines"],
                 "compatible_printers_condition": "", "from": "Nozzle It All", "instantiation": "true", "inherits": ""})
    for k in ("setting_id", "print_settings_id", "renamed_from"): flat.pop(k, None)
    return flat


def first(d, k):
    v = d.get(k)
    return (v[0] if v else "") if isinstance(v, list) else (v or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", required=True); ap.add_argument("--vendor", required=True)
    ap.add_argument("--machine", action="append", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--nested", action="store_true", help="profiles in kind sub-folders (ElegooSlicer's layout)")
    ap.add_argument("--source", default=None, help="the source note written to index.json")
    ap.add_argument("--pin", action="append", default=[], help="KEY=JSON process setting added where unset (source slicer default)")
    ap.add_argument("--engine", required=True, help="nozzle-engine with --compatible-presets (Orca's compatibility rules)")
    ap.add_argument("--derived", action="append", default=[], help="a Nozzle It All print preset spec (engine/profiles/derived/*.json)")
    a = ap.parse_args()
    by_name = index(a.profiles, a.vendor, a.nested)
    if os.path.isdir(a.out): shutil.rmtree(a.out)
    for sub in ("machine", "process", "filament"): os.makedirs(os.path.join(a.out, sub))

    def write(kind, name):
        flat = dict(next(f for n, f in candidates[kind] if n == name)) if kind != "machine" else flatten(kind, name, by_name)
        flat["name"] = name
        if kind == "process": pin_defaults(flat, a.pin)
        with open(os.path.join(a.out, kind, slug(name) + ".json"), "w") as f: json.dump(flat, f, indent=1, sort_keys=True); f.write("\n")
        return flat

    # Every instantiable preset of the kind, flattened once; derived presets join the processes.
    tmp = tempfile.mkdtemp()
    candidates = {"process": [], "filament": []}
    # The printer vendor's own presets: the ones Orca loads with that vendor's printers. (Orca would also offer
    # OrcaFilamentLibrary's generic filaments; the family keeps the vendor's own filaments.)
    own = set(index(a.profiles, a.vendor, a.nested, only=True))
    for (k, name), data in sorted(by_name.items(), key=lambda kv: str(kv[0][1])):
        if (k, name) not in own: continue
        if k not in candidates or not name or str(data.get("instantiation")).lower() != "true": continue
        try: flat = flatten(k, name, by_name)
        except SystemExit: continue
        candidates[k].append((name, flat))
    for path in a.derived:
        for spec in json.load(open(path))["presets"]:
            candidates["process"].append((spec["name"], derive(spec, by_name)))
    derived_names = {n for path in a.derived for n in (s["name"] for s in json.load(open(path))["presets"])}
    for k, items in candidates.items():
        for i, (name, flat) in enumerate(items):
            with open(os.path.join(tmp, f"{k}{i}.json"), "w") as f: json.dump(flat, f)

    def compatible(kind, machine):
        mpath = os.path.join(tmp, "machine.json")
        with open(mpath, "w") as f: json.dump(flatten("machine", machine, by_name), f)
        items = candidates[kind]
        verdict = compatible_with(a.engine, mpath, machine, kind, [os.path.join(tmp, f"{kind}{i}.json") for i in range(len(items))])
        return [item for item, ok in zip(items, verdict) if ok]

    machines, processes, filaments = [], {}, {}
    for mname in a.machine:
        m = write("machine", mname)
        procs = compatible("process", mname); fils = compatible("filament", mname)
        for n, f in procs:
            if slug(n) not in processes:
                write("process", n)
                processes[slug(n)] = {"id": slug(n), "name": n, "label": n.split(" @")[0].strip(), "layer_height": first(f, "layer_height")}
                if n in derived_names: processes[slug(n)]["made_by"] = "Nozzle It All"
        for n, f in fils:
            if slug(n) not in filaments:
                write("filament", n)
                filaments[slug(n)] = {"id": slug(n), "name": n, "vendor": first(f, "filament_vendor"), "type": first(f, "filament_type"), "family": match_name(n)}
        dp = m.get("default_print_profile") or ""
        df = first(m, "default_filament_profile")
        proc_ids = [slug(n) for n, _ in procs]; fil_ids = [slug(n) for n, _ in fils]
        # The machine's own defaults. Profile makers spell them loosely ("0.20 Standard" for "0.20mm Standard"; "Snapmaker
        # PLA" for the family "Snapmaker PLA Basic @U1"), so match leniently and prefer the closest name.
        norm = lambda n: re.sub(r"(\d)mm\b", r"\1", n.lower()).replace(" ", "")
        default_proc = next((i for i in proc_ids if norm(processes[i]["name"]) == norm(dp)), None) \
            or next((i for i in proc_ids if norm(processes[i]["label"]) == norm(dp.split(" @")[0])), None) or (proc_ids[0] if proc_ids else None)
        # The named default when it is compatible with this machine; else its "Basic" line for this printer (Snapmaker's U1
        # machines name "Snapmaker PLA", compatible only with the A-series; its U1 line is "Snapmaker PLA Basic @U1").
        # The default's exact preset name first (Elegoo's machines name "Elegoo PLA @ECC").
        exact = [i for i in fil_ids if filaments[i]["name"] == df] or [i for i in fil_ids if filaments[i]["family"].split(" @")[0].lower() == df.lower()]
        basic = [i for i in fil_ids if filaments[i]["family"].split(" @")[0].lower() == (df + " Basic").lower()]
        # Failing both, the same material: the generic profile for it, then any filament of that type.
        mat = (df.split()[-1] if df else "PLA").upper()
        same = sorted([i for i in fil_ids if filaments[i]["type"].upper() == mat],
                      key=lambda i: (not filaments[i]["family"].startswith(a.vendor), len(filaments[i]["family"])))
        generic = [i for i in fil_ids if filaments[i]["family"].split(" @")[0].lower() == ("generic " + mat).lower()]
        default_fil = (exact or basic or generic or same or fil_ids or [None])[0]
        machines.append({"id": slug(mname), "name": mname, "nozzle": first(m, "nozzle_diameter"),
                         "default_process": default_proc, "default_filament": default_fil, "processes": proc_ids, "filaments": fil_ids})
    with open(os.path.join(a.out, "index.json"), "w") as f:
        json.dump({"vendor": a.vendor, "source": a.source or f"{a.vendor} profiles from its own slicer (AGPL-3.0); see engine/profiles/SOURCES.json",
                   "machines": machines, "processes": list(processes.values()), "filaments": list(filaments.values())}, f, indent=1); f.write("\n")
    shutil.rmtree(tmp)
    for mm in machines: print(f"{mm['name']}: {len(mm['processes'])} processes, {len(mm['filaments'])} filaments, default {mm['default_process']} / {mm['default_filament']}")


if __name__ == "__main__":
    sys.exit(main())
