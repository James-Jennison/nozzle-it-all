#!/usr/bin/env python3
"""Bundle a printer model's whole profile family from its own slicer, flattened: one machine per nozzle size, and for
each the process presets and filament profiles that slicer marks compatible with it (compatible_printers), plus its
defaults. Prepare then offers the same nozzle sizes, process presets and per-slot filaments the vendor's slicer does.
Source for the Snapmaker U1: Snapmaker Orca's own profiles at the engine pin (engine/snapmaker/ENGINE_PIN.json).

  bundle_printer_library.py --profiles <Snapmaker Orca>/resources/profiles --vendor Snapmaker \
      --machine "Snapmaker U1 (0.2 nozzle)" --machine "Snapmaker U1 (0.4 nozzle)" ... --out engine/snapmaker/library/snapmaker_u1

Writes machine/<id>.json, process/<id>.json, filament/<id>.json and index.json.
"""
import argparse, json, os, re, shutil, sys
sys.path.insert(0, os.path.dirname(__file__))
from flatten_orca_profile import index, flatten  # noqa: E402


def slug(name): return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
def match_name(name): return re.sub(r"\s+\d+(\.\d+)?\s+nozzle$", "", name).strip()
def first(d, k):
    v = d.get(k)
    return (v[0] if v else "") if isinstance(v, list) else (v or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", required=True); ap.add_argument("--vendor", required=True)
    ap.add_argument("--machine", action="append", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    by_name = index(a.profiles, a.vendor)
    if os.path.isdir(a.out): shutil.rmtree(a.out)
    for sub in ("machine", "process", "filament"): os.makedirs(os.path.join(a.out, sub))

    def write(kind, name):
        flat = flatten(kind, name, by_name); flat["name"] = name
        with open(os.path.join(a.out, kind, slug(name) + ".json"), "w") as f: json.dump(flat, f, indent=1, sort_keys=True); f.write("\n")
        return flat

    def compatible(kind, machine):
        out = []
        for (k, name), data in sorted(by_name.items(), key=lambda kv: str(kv[0][1])):
            if k != kind or not name or str(data.get("instantiation")).lower() != "true": continue
            try: flat = flatten(kind, name, by_name)
            except SystemExit: continue
            if machine in (flat.get("compatible_printers") or []): out.append((name, flat))
        return out

    machines, processes, filaments = [], {}, {}
    for mname in a.machine:
        m = write("machine", mname)
        procs = compatible("process", mname); fils = compatible("filament", mname)
        for n, f in procs:
            if slug(n) not in processes:
                write("process", n)
                processes[slug(n)] = {"id": slug(n), "name": n, "label": n.split(" @")[0].strip(), "layer_height": first(f, "layer_height")}
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
        exact = [i for i in fil_ids if filaments[i]["family"].split(" @")[0].lower() == df.lower()]
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
        json.dump({"vendor": a.vendor, "source": f"{a.vendor} profiles from its own slicer (AGPL-3.0); see engine/snapmaker/ENGINE_PIN.json",
                   "machines": machines, "processes": list(processes.values()), "filaments": list(filaments.values())}, f, indent=1); f.write("\n")
    for mm in machines: print(f"{mm['name']}: {len(mm['processes'])} processes, {len(mm['filaments'])} filaments, default {mm['default_process']} / {mm['default_filament']}")


if __name__ == "__main__":
    sys.exit(main())
