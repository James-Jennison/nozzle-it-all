#!/usr/bin/env python3
"""Bundle OrcaSlicer's own printer profiles for every vendor's 0.4 mm models, and generate the Kotlin model catalog.

Each model gets a flattened machine/process/filament pack under app/src/main/assets/slicer_profiles/<dir>/ (same
flattening as flatten_orca_profile.py - the JNI bridge has no `inherits` resolution). The catalog
(domain/.../SlicingModelCatalog.kt, GENERATED - do not edit) declares the SlicingPrinterModel enum and one entry per
model, so the picker, the pack lookup and the tests all read one list.

  bundle_vendor_profiles.py --profiles <orca>/resources/profiles

Existing packs (Snapmaker U1, Elegoo Centauri Carbon COSMOS, generic Klipper, Bambu A1, Prusa MK4, Prusa XL 5T) are
kept as they are and only listed in the catalog. Everything else here is unverified on hardware; the catalog says so.
"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from flatten_orca_profile import index, flatten  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "app/src/main/assets/slicer_profiles")
CATALOG = os.path.join(ROOT, "domain/src/main/kotlin/net/jamesjennison/klippercompanion/SlicingModelCatalog.kt")

# id, label, vendor, asset dir, verified-on-hardware. The first six are the hand-made/earlier packs and keep their names
# (the enum is persisted by name, so these must never be renamed).
EXISTING = [
    ("SNAPMAKER_U1", "Snapmaker U1", "SNAPMAKER", "snapmaker_u1", True),
    ("ELEGOO_CENTAURI_CARBON", "Elegoo Centauri Carbon (OpenCentauri COSMOS)", "ELEGOO", "elegoo_centauri_carbon_cosmos", True),
    ("BAMBU_GENERIC", "Bambu Lab A1", "BAMBU", "bambu_generic", False),
    ("PRUSA_GENERIC", "Prusa MK4", "PRUSA", "prusa_generic", False),
    ("GENERIC_KLIPPER", "Generic Klipper", "GENERIC", "generic_klipper", True),
    ("PRUSA_XL_5T", "Prusa XL 5T (five tools)", "PRUSA", "prusa_xl_5t", False),
]

# Prusa: explicit picks (Orca's names are irregular). (id, label, machine, process, filament)
PRUSA = [
    ("PRUSA_MK4S", "Prusa MK4S", "Prusa MK4S 0.4 nozzle", "0.20mm SPEED @MK4S 0.4", "Generic PLA @Prusa MK4S"),
    ("PRUSA_MK3_5", "Prusa MK3.5", "Prusa MK3.5 0.4 nozzle", "0.20mm Standard @MK3.5", "Generic PLA @Prusa MK3.5"),
    ("PRUSA_MK3S", "Prusa MK3S / MK3S+", "Prusa MK3S 0.4 nozzle", "0.20mm Standard @MK3S", "Generic PLA @Prusa"),
    ("PRUSA_MINI", "Prusa MINI / MINI+", "Prusa MINI 0.4 nozzle", "0.20mm Standard @MINI", "Generic PLA @Prusa"),
    ("PRUSA_MINI_IS", "Prusa MINI IS", "Prusa MINIIS 0.4 nozzle", "0.20mm Standard @MINIIS", "Generic PLA @Prusa MINIIS"),
    ("PRUSA_CORE_ONE", "Prusa CORE One", "Prusa CORE One 0.4 nozzle", "0.20mm SPEED @CORE One 0.4", "Generic PLA @Prusa CORE One"),
    ("PRUSA_CORE_ONE_L", "Prusa CORE One L", "Prusa CORE One L 0.4 nozzle", "0.20mm SPEED @CORE One L 0.4", "Generic PLA @Prusa CORE One"),
    ("PRUSA_XL", "Prusa XL (single tool)", "Prusa XL 0.4 nozzle", "0.20mm Speed @Prusa XL 0.4", "Generic PLA @Prusa XL"),
]

# Bambu: the six models we care most about are labelled explicitly; the rest come from the library's model list.
BAMBU_LABELS = {  # machine model name -> (id, label)
    "Bambu Lab A1 mini": ("BAMBU_A1_MINI", "Bambu Lab A1 mini"),
    "Bambu Lab A2L": ("BAMBU_A2L", "Bambu Lab A2L"),
    "Bambu Lab H2C": ("BAMBU_H2C", "Bambu Lab H2C"),
    "Bambu Lab H2D": ("BAMBU_H2D", "Bambu Lab H2D"),
    "Bambu Lab H2D Pro": ("BAMBU_H2D_PRO", "Bambu Lab H2D Pro"),
    "Bambu Lab H2S": ("BAMBU_H2S", "Bambu Lab H2S"),
    "Bambu Lab P1P": ("BAMBU_P1P", "Bambu Lab P1P"),
    "Bambu Lab P1S": ("BAMBU_P1S", "Bambu Lab P1S"),
    "Bambu Lab P2S": ("BAMBU_P2S", "Bambu Lab P2S"),
    "Bambu Lab X1": ("BAMBU_X1", "Bambu Lab X1"),
    "Bambu Lab X1 Carbon": ("BAMBU_X1_CARBON", "Bambu Lab X1 Carbon"),
    "Bambu Lab X1E": ("BAMBU_X1E", "Bambu Lab X1E"),
    "Bambu Lab X2D": ("BAMBU_X2D", "Bambu Lab X2D"),
}


def slug(model_id): return model_id.lower()


def write_pack(by_name, out_dir, machine, process, filament, filament_index=None):
    os.makedirs(out_dir, exist_ok=True)
    for kind, name in (("machine", machine), ("process", process), ("filament", filament)):
        flat = flatten(kind, name, filament_index if (kind == "filament" and filament_index is not None) else by_name)
        flat["name"] = name
        with open(os.path.join(out_dir, f"{kind}.json"), "w") as f:
            json.dump(flat, f, indent=4); f.write("\n")


def flat_compat(by_name, kind, name):
    try: return flatten(kind, name, by_name).get("compatible_printers") or []
    except SystemExit: return []


def pick_bambu(by_name, machine):
    procs = sorted(n for (k, n) in by_name if k == "process" and n and n.startswith("0.20mm Standard"))
    proc = next((n for n in procs if machine in flat_compat(by_name, "process", n)), None)
    fils = sorted(n for (k, n) in by_name if k == "filament" and n and "PLA" in n and "@BBL" in n)
    def rank(n): return (0 if n.startswith("Bambu PLA Basic") else 1 if n.startswith("Generic PLA") else 2, len(n), n)
    fil = next((n for n in sorted(fils, key=rank) if machine in flat_compat(by_name, "filament", n)), None)
    return proc, fil


SKIP_VENDORS = {"Custom"}  # OrcaSlicer's placeholder vendor; the generic Klipper pack already covers it
# Machines handled elsewhere (hand-made or earlier packs) that must not be regenerated under another id.
LEGACY_MACHINES = {
    "Snapmaker U1 (0.4 nozzle)", "Elegoo Centauri Carbon 0.4 nozzle",  # Elegoo CC: stock-firmware profile is the wrong one (COSMOS pack)
    "Bambu Lab A1 0.4 nozzle", "Prusa MK4 0.4 nozzle", "Prusa XL 5T 0.4 nozzle",
}
VENDOR_KEYS = {"BBL": ("BAMBU", "Bambu Lab"), "Prusa": ("PRUSA", "Prusa"), "Snapmaker": ("SNAPMAKER", "Snapmaker"), "Elegoo": ("ELEGOO", "Elegoo")}
import re
_NOZ = re.compile(r"0\.4 nozzle\)?$")


def ident(text): return re.sub(r"_+", "_", re.sub(r"[^A-Za-z0-9]+", "_", text)).strip("_").upper()


def vendor_key(vendor): return VENDOR_KEYS.get(vendor, (ident(vendor), vendor))


def first(v):
    if isinstance(v, list): v = v[0] if v else None
    return v.split(";")[0].strip() if isinstance(v, str) and v.strip() else None


def resolve_process(by_name, name, m):
    dp = first(m.get("default_print_profile"))
    if dp and ("process", dp) in by_name: return dp
    model = m.get("printer_model") or ""
    cands = []
    for (k, n) in by_name:
        if k != "process" or not n or n.startswith("fdm_"): continue
        try: f = flatten("process", n, by_name)
        except SystemExit: continue
        if str(f.get("layer_height")) not in ("0.2", "0.20"): continue
        compat = f.get("compatible_printers") or []
        if name in compat or (not compat and model and model in n): cands.append(n)
    if not cands: return None
    pref = lambda n: (0 if "Standard" in n else 1 if any(w in n for w in ("Normal", "Optimal", "Quality", "Balanced")) else 2, len(n), n)
    return sorted(cands, key=pref)[0]


FALLBACK_FILAMENT = "Generic PLA @System"
LIBRARY_INDEX = {}  # OrcaSlicer's own filament library, filled in main(); the fallback filament always comes from here


def has_temp_range(by_name, name):
    try: f = flatten("filament", name, by_name)
    except SystemExit: return False
    return bool(f.get("nozzle_temperature_range_low")) and bool(f.get("nozzle_temperature_range_high"))


def resolve_filament(by_name, name, m, vendor):
    df = first(m.get("default_filament_profile"))
    if df and ("filament", df) in by_name and has_temp_range(by_name, df): return df  # a vendor filament with no temperature range cannot be validated
    cands = []
    for (k, n) in by_name:
        if k != "filament" or not n or "PLA" not in n or f"@{vendor}" in n[:0]: continue
        try: f = flatten("filament", n, by_name)
        except SystemExit: continue
        if name in (f.get("compatible_printers") or []): cands.append(n)
    cands = [c for c in cands if has_temp_range(by_name, c)]
    if cands: return sorted(cands, key=lambda n: (0 if "Generic" in n else 1 if "Basic" in n else 2, len(n), n))[0]
    return FALLBACK_FILAMENT if has_temp_range(LIBRARY_INDEX, FALLBACK_FILAMENT) else None


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--profiles", required=True); a = ap.parse_args()
    entries = list(EXISTING); report = []; skipped = []
    LIBRARY_INDEX.update(index(a.profiles, "OrcaFilamentLibrary"))
    used_ids = {e[0] for e in entries}; used_labels = {(e[2], e[1]) for e in entries}
    # 1) the curated Bambu/Prusa picks made earlier keep their ids and hand-checked process/filament choices.
    bbl = index(a.profiles, "BBL"); prusa = index(a.profiles, "Prusa"); curated = set()
    for full in sorted(n for (k, n) in bbl if k == "machine" and n and n.startswith("Bambu Lab") and n.endswith("0.4 nozzle")):
        model = full[: -len(" 0.4 nozzle")]
        if model == "Bambu Lab A1": continue
        mid, label = BAMBU_LABELS[model]; proc, fil = pick_bambu(bbl, full)
        if not proc or not fil: raise SystemExit(f"no compatible process/filament for {full}")
        write_pack(bbl, os.path.join(OUT, slug(mid)), full, proc, fil)
        entries.append((mid, label, "BAMBU", slug(mid), False)); used_ids.add(mid); used_labels.add(("BAMBU", label)); curated.add(full)
    for mid, label, machine, proc, fil in PRUSA:
        write_pack(prusa, os.path.join(OUT, slug(mid)), machine, proc, fil)
        entries.append((mid, label, "PRUSA", slug(mid), False)); used_ids.add(mid); used_labels.add(("PRUSA", label)); curated.add(machine)
    # 2) every other vendor: one pack per 0.4 mm machine model, defaults from the machine itself, then heuristics.
    for vendor in sorted(d for d in os.listdir(a.profiles) if os.path.isdir(os.path.join(a.profiles, d)) and d not in SKIP_VENDORS):
        mdir = os.path.join(a.profiles, vendor, "machine")
        if not os.path.isdir(mdir): continue
        vkey, vlabel = vendor_key(vendor)
        if vkey in ("BAMBU", "PRUSA"): continue  # done above
        by_name = None
        for f in sorted(os.listdir(mdir)):
            if not f.endswith(".json"): continue
            try: raw = json.load(open(os.path.join(mdir, f)))
            except Exception: continue
            name = raw.get("name") or ""
            if not raw.get("printer_model") or not (_NOZ.search(name) or raw.get("printer_variant") == "0.4"): continue
            if name in LEGACY_MACHINES or name in curated: continue
            if by_name is None: by_name = index(a.profiles, vendor)
            try: m = flatten("machine", name, by_name)
            except SystemExit as e: skipped.append((vendor, name, f"machine: {e}")); continue
            if not (m.get("printable_area") and m.get("machine_start_gcode") is not None): skipped.append((vendor, name, "no bed/start gcode")); continue
            proc = resolve_process(by_name, name, m)
            if not proc: skipped.append((vendor, name, "no 0.20 mm process found")); continue
            fil = resolve_filament(by_name, name, m, vendor)
            if not fil: skipped.append((vendor, name, "no filament found")); continue
            model = raw["printer_model"].strip()
            label = model if vlabel.lower() in model.lower() else f"{vlabel} {model}"
            mid = f"{vkey}_{ident(model.replace(vlabel, ''))}".strip("_") if vlabel.lower() in model.lower() else f"{vkey}_{ident(model)}"
            base = mid; n = 2
            while mid in used_ids: mid = f"{base}_{n}"; n += 1
            if (vkey, label) in used_labels: label = f"{label} ({name.replace(' 0.4 nozzle', '')})"
            try: write_pack(by_name, os.path.join(OUT, slug(mid)), name, proc, fil, LIBRARY_INDEX if fil == FALLBACK_FILAMENT else None)
            except SystemExit as e: skipped.append((vendor, name, f"pack: {e}")); continue
            entries.append((mid, label, vkey, slug(mid), False)); used_ids.add(mid); used_labels.add((vkey, label))
            report.append((label, name, proc, fil))
    for r in report: print(" | ".join(r))
    print(f"\nSKIPPED {len(skipped)}:")
    for sk in skipped: print("  ", " | ".join(sk))
    vendors = sorted({e[2] for e in entries} - {"SNAPMAKER", "ELEGOO", "BAMBU", "PRUSA", "GENERIC"})
    labels = {vendor_key(v)[0]: vendor_key(v)[1] for v in os.listdir(a.profiles) if os.path.isdir(os.path.join(a.profiles, v))}
    labels.update({"SNAPMAKER": "Snapmaker", "ELEGOO": "Elegoo", "BAMBU": "Bambu Lab", "PRUSA": "Prusa", "GENERIC": "Generic"})
    order_keys = ["SNAPMAKER", "ELEGOO", "BAMBU", "PRUSA"] + vendors + ["GENERIC"]
    ids = [e[0] for e in entries]
    assert len(set(ids)) == len(ids), "duplicate model ids"
    vlines = ", ".join(f'{k}("{labels[k]}")' for k in order_keys)
    lines = [
        "package net.jamesjennison.klippercompanion", "",
        "// GENERATED by scripts/bundle_vendor_profiles.py - do not edit by hand; rerun the script.",
        "// One entry per bundled OrcaSlicer profile pack (app/src/main/assets/slicer_profiles/<assetDir>). The enum is persisted by",
        "// name(), so existing names are never renamed or removed; new models are only ever appended.",
        "enum class SlicingPrinterModel { " + ", ".join(ids) + " }", "",
        "enum class SlicingVendor(val label: String) { " + vlines + " }", "",
        "/** verifiedOnHardware: this pack has produced a real print on a real printer of this model; everything else is profile-only. */",
        "data class SlicingModelInfo(val model: SlicingPrinterModel, val label: String, val vendor: SlicingVendor, val assetDir: String, val verifiedOnHardware: Boolean)", "",
        "object SlicingModelCatalog {", "    val all: List<SlicingModelInfo> = listOf(",
    ]
    rank = {k: i for i, k in enumerate(order_keys)}
    for mid, label, vendor, d, ver in sorted(entries, key=lambda e: (rank[e[2]], e[1].lower())):
        lines.append(f'        SlicingModelInfo(SlicingPrinterModel.{mid}, "{label}", SlicingVendor.{vendor}, "{d}", {str(ver).lower()}),')
    lines += ["    )", "    private val byModel = all.associateBy { it.model }",
              "    fun info(model: SlicingPrinterModel): SlicingModelInfo = byModel.getValue(model)", "}", ""]
    open(CATALOG, "w").write("\n".join(lines))
    print(f"\n{len(entries)} models across {len(order_keys)} vendors; wrote {CATALOG}")


if __name__ == "__main__":
    main()
