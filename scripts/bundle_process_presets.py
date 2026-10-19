#!/usr/bin/env python3
"""Bundle every Android printer pack's print profiles (process presets) for Prepare's Print profile picker.

Writes app/src/main/assets/slicer_profiles/<pack>/processes.json:
  {"default": <name>, "presets": [{"name", "label", "layer_height", "infill", "file", "made_by"?, "color_mixing"?}]}
"file" is an asset path. The default is the pack's own process.json; the others are shared, flattened once per vendor
under slicer_profiles/shared_processes/<vendor>/ (a pack-local copy only where the pack's fix_bed_type changes the plate).

Which presets a pack offers is the engine's call, as in Orca: the machine vendor's own instantiable process presets that
nozzle-engine --compatible-presets (Orca's is_compatible_with_printer) accepts for the pack's machine.json.

  bundle_process_presets.py --profiles <OrcaSlicer at 824b216>/resources/profiles --engine <nozzle-engine>

- Packs from a printer library (LIBRARY_PACKS: engine/profiles/library, scripts/bundle_printer_library.py) offer its
  0.4 mm machine's presets beside the pack's own process (its default; the pack's files are left as they are). The
  library preset the pack's process came from (by name or renamed_from) isn't listed twice. Their preset files ship
  from engine/profiles/library itself (app/build.gradle.kts copies it into the APK's printer_library/ assets).
- Hand-made packs built on a vendor machine under another name (COMPATIBLE_AS) are checked as that machine.
- engine/profiles/derived/*.json presets whose machines include the pack's are added (where COMPATIBLE_AS allows).
- Packs whose machine no Orca vendor ships (generic, MMU3 and other synthetic packs) offer their own process only.
"""
import argparse, glob, json, os, shutil, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from flatten_orca_profile import index, flatten  # noqa: E402
from bundle_printer_library import slug, compatible_with, derive, first  # noqa: E402
from bundle_vendor_profiles import fix_bed_type  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PACKS = os.path.join(ROOT, "app/src/main/assets/slicer_profiles")
SHARED = "shared_processes"  # not "_processes": Android packaging leaves out asset folders whose names start with "_"
LIBRARY = os.path.join(ROOT, "engine/profiles/library")
DERIVED = sorted(glob.glob(os.path.join(ROOT, "engine/profiles/derived/*.json")))

# pack -> (library, nozzle)
LIBRARY_PACKS = {
    "snapmaker_u1": ("snapmaker_u1", "0.4"),
    "elegoo_centauri_carbon_canvas": ("elegoo_centauri_carbon_canvas", "0.4"),
    "elegoo_centauri_carbon_2_canvas": ("elegoo_centauri_carbon_2_canvas", "0.4"),
}
# pack -> (Orca vendor, the machine it is built on, whether Nozzle's derived presets apply). The COSMOS packs are Orca's
# Centauri Carbon with OpenCentauri COSMOS G-code (P-0011). Neither takes derived presets: the only ones made so far were
# for color mixing, which CANVAS doesn't get (P-0044).
COMPATIBLE_AS = {
    "elegoo_centauri_carbon_cosmos": ("Elegoo", "Elegoo Centauri Carbon 0.4 nozzle", False),
    "elegoo_centauri_carbon_cosmos_afc": ("Elegoo", "Elegoo Centauri Carbon 0.4 nozzle", False),
}


def label_of(name): return name.split(" @")[0].strip()
def is_color_mixing(name): return "color mixing" in name.lower() or "colour mixing" in name.lower()


def entry(name, flat, file, made_by=None):
    e = {"name": name, "label": label_of(name), "layer_height": str(first(flat, "layer_height")),
         "infill": str(first(flat, "sparse_infill_density") or ""), "file": file}
    if made_by: e["made_by"] = made_by
    if is_color_mixing(name): e["color_mixing"] = True
    return e


def write_json(path, data, indent=4):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(data, f, indent=indent); f.write("\n")


def finish(presets, default):
    """Distinct labels (the full name where two share one), thinnest layers first as Orca lists them."""
    labels = [p["label"] for p in presets]
    for p in presets:
        if labels.count(p["label"]) > 1: p["label"] = p["name"]
    presets.sort(key=lambda p: (float(p["layer_height"] or 0), p["label"].lower()))
    return {"default": default, "presets": presets}


def library_pack(pack, lib, nozzle):
    li = json.load(open(os.path.join(LIBRARY, lib, "index.json")))
    m = next(m for m in li["machines"] if m["nozzle"] == nozzle)
    procs = {p["id"]: p for p in li["processes"]}
    process = json.load(open(os.path.join(PACKS, pack, "process.json")))
    default = process.get("name") or "Default"
    presets = [entry(default, process, f"slicer_profiles/{pack}/process.json")]
    for i in m["processes"]:
        data = json.load(open(os.path.join(LIBRARY, lib, "process", i + ".json")))
        # The pack's own process is the vendor's default under its old name (Snapmaker renamed U1's "0.20 Standard"
        # to "0.20mm Standard", keeping the old one in renamed_from): offer it once, as the pack's proven file.
        if default in [procs[i]["name"]] + [n.strip() for n in str(data.get("renamed_from") or "").split(";")]: continue
        presets.append(entry(procs[i]["name"], data, f"printer_library/{lib}/process/{i}.json", procs[i].get("made_by")))
    print(f"{pack}: library {lib} {nozzle} mm, {len(presets)} presets, default {default}")
    return finish(presets, default)


class Vendors:
    """Orca's vendors: which one ships a machine, and each one's instantiable process presets, flattened once."""
    def __init__(self, profiles, specs):
        self.profiles, self.specs = profiles, specs
        self.owner = {}
        for v in sorted(d for d in os.listdir(profiles) if os.path.isdir(os.path.join(profiles, d))):
            for (k, n) in index(profiles, v, nested=True, only=True):
                if k == "machine" and n: self.owner.setdefault(n, v)
        self.cache = {}

    def processes(self, vendor):
        if vendor not in self.cache:
            by_name = index(self.profiles, vendor, nested=True)
            own = index(self.profiles, vendor, nested=True, only=True)
            items = []
            for (k, n), data in sorted(own.items(), key=lambda kv: str(kv[0][1])):
                if k != "process" or not n or str(data.get("instantiation")).lower() != "true": continue
                try: items.append((n, flatten("process", n, by_name), None))
                except SystemExit: continue
            for spec in self.specs:
                if ("process", spec["base"]) in own: items.append((spec["name"], derive(spec, by_name), spec))
            self.cache[vendor] = items
        return self.cache[vendor]


def orca_pack(pack, vendors, engine, tmp):
    d = os.path.join(PACKS, pack)
    machine = json.load(open(os.path.join(d, "machine.json")))
    process = json.load(open(os.path.join(d, "process.json")))
    filament = json.load(open(os.path.join(d, "filament.json")))
    default = process.get("name") or "Default"
    own = [entry(default, process, f"slicer_profiles/{pack}/process.json")]
    vendor, as_name, with_derived = COMPATIBLE_AS.get(pack, (None, machine.get("name"), False))
    vendor = vendor or vendors.owner.get(as_name)
    if not vendor: return finish(own, default), "no Orca vendor ships this machine"
    items = [it for it in vendors.processes(vendor) if it[2] is None or (with_derived or pack not in COMPATIBLE_AS) and as_name in it[2]["machines"]]
    mpath = os.path.join(tmp, "machine.json"); write_json(mpath, machine)
    paths = []
    for i, (_, flat, _) in enumerate(items):
        paths.append(os.path.join(tmp, f"p{i}.json")); write_json(paths[-1], flat, None)
    verdict = compatible_with(engine, mpath, as_name, "process", paths)
    presets = own
    for (name, flat, spec), ok in zip(items, verdict):
        if not ok or name == default: continue
        fixed = dict(flat); fixed["name"] = name
        bed = fix_bed_type(dict(machine), fixed, filament)
        rel = f"slicer_profiles/{SHARED}/{vendor}/{slug(name)}" + (f"@{slug(bed)}" if bed else "") + ".json"
        out = os.path.join(ROOT, "app/src/main/assets", rel)
        if not os.path.exists(out): write_json(out, fixed)
        presets.append(entry(name, fixed, rel, "Nozzle It All" if spec else None))
    return finish(presets, default), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", required=True, help="OrcaSlicer's resources/profiles at the commit the packs came from (824b216)")
    ap.add_argument("--engine", required=True, help="nozzle-engine with --compatible-presets")
    a = ap.parse_args()
    specs = [s for path in DERIVED for s in json.load(open(path))["presets"]]
    shutil.rmtree(os.path.join(PACKS, SHARED), ignore_errors=True)
    vendors = Vendors(a.profiles, specs)
    tmp = tempfile.mkdtemp()
    only_default, counts = [], {}
    for pack in sorted(p for p in os.listdir(PACKS) if os.path.isfile(os.path.join(PACKS, p, "machine.json"))):
        if pack in LIBRARY_PACKS: out, why = library_pack(pack, *LIBRARY_PACKS[pack]), None
        else: out, why = orca_pack(pack, vendors, a.engine, tmp)
        write_json(os.path.join(PACKS, pack, "processes.json"), out, 1)
        counts[pack] = len(out["presets"])
        if why: only_default.append((pack, why))
    shutil.rmtree(tmp)
    shared = sum(len(fs) for _, _, fs in os.walk(os.path.join(PACKS, SHARED)))
    print(f"{len(counts)} packs, {sum(counts.values())} preset entries, {shared} shared preset files")
    print(f"own process only ({len(only_default)}):", ", ".join(f"{p} ({w})" for p, w in only_default))
    for p, n in sorted(counts.items()):
        if any(e.get("made_by") or e.get("color_mixing") for e in json.load(open(os.path.join(PACKS, p, "processes.json")))["presets"]):
            print("  color mixing / derived:", p, [e["name"] for e in json.load(open(os.path.join(PACKS, p, "processes.json")))["presets"] if e.get("made_by") or e.get("color_mixing")])


if __name__ == "__main__":
    sys.exit(main())
