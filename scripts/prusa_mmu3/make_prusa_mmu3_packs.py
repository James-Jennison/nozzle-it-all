#!/usr/bin/env python3
"""Generate Prusa MMU3 slicing packs (MK4S MMU3, MK3.9 MMU3, MK3.5 MMU3, 0.4 nozzle) for Nozzle It All.

Reproducible: every value is read from, and checked against, these sources (read-only):
  * the app's non-MMU packs  <APP>/app/src/main/assets/slicer_profiles/prusa_mk4s|prusa_mk3_5/{machine,process,filament}.json
  * PrusaSlicer 3.x YAML presets  <TS>/PrusaSlicer/resources/presets/prusa-research-fff/PrusaResearch/
    (resolved with scripts/prusa_mmu3/pseval.py, a re-implementation of PresetCollectionEvaluator)
  * upstream OrcaSlicer Prusa profiles  <TS>/OrcaSlicer/resources/profiles/Prusa/  (inherits resolved with scripts/prusa_mmu3/orca.py);
    the CORE One MMU3 vs CORE One diff gives "Orca's own MMU3 deltas"
  * the engine's option / placeholder vocabulary  <TS>/engine/src/libslic3r/PrintConfig.cpp and GCode.cpp

Writes out/<pack>/{machine,process,filament}.json (json.dump indent=4 + "\\n", like scripts/flatten_orca_profile.py)
and REPORT.md (every changed key and its source) next to this script. The packs go to app/src/main/assets/slicer_profiles/.

Every decision that is not a plain copy is encoded in the POLICY tables below with its reason; the script asserts
that the Orca and PrusaSlicer sources still produce the values those policies were written against, so a changed
upstream profile makes it fail loudly instead of silently producing something different.
"""
import copy, json, os, re, sys, difflib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pseval as PS   # noqa: E402  (PrusaSlicer 3.x YAML evaluator)
import orca as OR     # noqa: E402  (Orca inherits resolver)

APP_PROFILES = os.path.normpath(os.path.join(HERE, "../../app/src/main/assets/slicer_profiles"))
TS = os.environ.get("TEST_SLICER", "/mnt/faststorage/Test Slicer")  # local OrcaSlicer, PrusaSlicer and engine checkouts
PS_DIR = PS.D
ENGINE = os.path.join(TS, "engine/src/libslic3r")
OUT = os.environ.get("MMU3_OUT", APP_PROFILES)  # the packs are written next to their non-MMU sources

# ----------------------------------------------------------------------------------------------------------------
# small helpers
# ----------------------------------------------------------------------------------------------------------------

def load_pack(d):
    return {f: json.load(open(os.path.join(APP_PROFILES, d, f + ".json"))) for f in ("machine", "process", "filament")}


def num(v):
    """PrusaSlicer number -> Orca string ("19" not "19.0")."""
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return str(v)


def fmt2(x):
    s = f"{x:.2f}".rstrip("0").rstrip(".")
    return s


def like(old, new):
    """Keep the container style of the source pack: list-with-one-string stays a list, scalar stays scalar."""
    if isinstance(old, list):
        return new if isinstance(new, list) else [new]
    if isinstance(new, list):
        assert len(new) == 1, new
        return new[0]
    return new


def scalar(v):
    return v[0] if isinstance(v, list) and len(v) == 1 else v


class Pack:
    """A pack being built; records every change with its source."""

    def __init__(self, key, title, src_dir, src):
        self.key, self.title, self.src_dir = key, title, src_dir
        self.src = copy.deepcopy(src)
        self.data = copy.deepcopy(src)
        self.log = {"machine": [], "process": [], "filament": []}
        self.notes = {"machine": [], "process": [], "filament": []}

    def set(self, f, k, new, source, style_from=None):
        d = self.data[f]
        if k in d:
            new = like(d[k], new)
        elif style_from is not None:
            new = like(style_from, new)
        old = self.src[f].get(k, "<unset>")
        if k in d and d[k] == new:
            self.notes[f].append(f"`{k}` already `{json.dumps(new)}` in the source pack; left as is ({source}).")
            return
        d[k] = new
        self.log[f].append((k, old, new, source))

    def delete(self, f, k, source):
        old = self.data[f].pop(k)
        self.log[f].append((k, old, "<removed>", source))

    def note(self, f, text):
        self.notes[f].append(text)


# ----------------------------------------------------------------------------------------------------------------
# PrusaSlicer provenance (file + line of the fragment value that won)
# ----------------------------------------------------------------------------------------------------------------
_yaml_lines = {}


def _lines(fn):
    if fn not in _yaml_lines:
        _yaml_lines[fn] = open(os.path.join(PS_DIR, fn)).read().split("\n")
    return _yaml_lines[fn]


def ps_source(ctx, key, what):
    """'<file> l.<n> (<fragment id>)' of the last fragment in the resolved chain that sets `key`."""
    hit = None
    for fid in ctx["id"]:
        node = PS.paths[fid][-1]
        if key in (node.get("values") or {}):
            hit = fid
    if hit is None:
        return f"PrusaSlicer {what}: `{key}` not set by any fragment (built-in default)"
    fn = PS.paths[hit][0]["__file"]
    lines = _lines(fn)
    start = next(i for i, l in enumerate(lines) if re.match(rf"\s*-?\s*id:\s*'?{re.escape(hit)}'?\s*$", l))
    rx = re.compile(rf"^\s*{re.escape(key)}:")
    ln = None
    for i in range(start + 1, len(lines)):
        if i > start + 1 and re.match(r"\s*-?\s*id:", lines[i]):
            break
        if rx.match(lines[i]):
            ln = i + 1
            break
    loc = f"l.{ln}" if ln else f"fragment at l.{start + 1}"
    return f"PrusaSlicer {what}: `{fn}` {loc} (fragment `{hit}`)"


# ----------------------------------------------------------------------------------------------------------------
# Engine vocabulary (for placeholder checks and "does the engine know this key")
# ----------------------------------------------------------------------------------------------------------------
_pc = open(os.path.join(ENGINE, "PrintConfig.cpp")).read()
_gc = open(os.path.join(ENGINE, "GCode.cpp")).read()
ENGINE_KEYS = set(re.findall(r'this->add(?:_nullable)?\("([A-Za-z0-9_]+)"', _pc))
GCODE_PLACEHOLDERS = set(re.findall(r'placeholder_parser\(\)\.set\("([A-Za-z0-9_]+)"', _gc)) | \
    set(re.findall(r'\.set_key_value\("([A-Za-z0-9_]+)"', _gc)) | set(re.findall(r'\.set\("([A-Za-z0-9_]+)"', _gc))
PARSER_WORDS = {"if", "elsif", "else", "endif", "and", "or", "not", "true", "false", "min", "max", "is_nil",
                "interpolate_table", "int", "round", "digits", "zdigits", "one_of", "empty", "size", "let", "local",
                "global", "random", "filament_extruder_id"}
# filament_extruder_id: set per call in GCode.cpp (config.set_key_value("filament_extruder_id", ...)) - also in the set.


def placeholders(g):
    """Identifiers used by macros in a G-code template: everything inside {...} and bare [name] outside it."""
    ids, bare = set(), set()
    for block in re.findall(r"\{([^{}]*)\}", g):
        b = re.sub(r'"[^"]*"', " ", block)                 # string literals
        b = re.sub(r"=~\s*/[^/]*/", " ", b)                # regex literals
        b = re.sub(r"!~\s*/[^/]*/", " ", b)
        for w in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", b):
            ids.add(w)
    outside = re.sub(r"\{[^{}]*\}", " ", g)
    for w in re.findall(r"\[([A-Za-z_][A-Za-z0-9_]*)\]", outside):
        bare.add(w)
    return (ids | bare) - PARSER_WORDS


def check_placeholders(g):
    confirmed, unconfirmed = {}, []
    for w in sorted(placeholders(g)):
        where = []
        if w in ENGINE_KEYS:
            where.append("PrintConfig.cpp option")
        if w in GCODE_PLACEHOLDERS:
            where.append("GCode.cpp placeholder")
        if where:
            confirmed[w] = " + ".join(where)
        else:
            unconfirmed.append(w)
    return confirmed, unconfirmed


# ----------------------------------------------------------------------------------------------------------------
# Orca CORE One MMU3 deltas
# ----------------------------------------------------------------------------------------------------------------
C1 = dict(machine=("Prusa CORE One 0.4 nozzle", "Prusa CORE One MMU3 0.4 nozzle"),
          process=("0.20mm SPEED @CORE One 0.4", "0.20mm SPEED @COREONE0.4 + MMU3"),
          filament=("Generic PLA @Prusa CORE One", "Generic PLA @Prusa CORE One MMU3"))
COLL = dict(machine=OR.machines, process=OR.processes, filament=OR.filaments)


def orca_delta(kind):
    a, b = C1[kind]
    A, B = OR.resolve(COLL[kind], a), OR.resolve(COLL[kind], b)
    return A, B, {k: (A.get(k, "<unset>"), B.get(k, "<unset>")) for k in sorted(set(A) | set(B)) if A.get(k) != B.get(k)}


OA_M, OB_M, OD_M = orca_delta("machine")
OA_P, OB_P, OD_P = orca_delta("process")
OA_F, OB_F, OD_F = orca_delta("filament")
ORCA_FILE = {k: OR.chain(COLL[k], C1[k][1])[0]["__file"] for k in C1}


def _clip(v, n=70):
    s = json.dumps(v)
    return s if len(s) <= n and "\\n" not in s else "(long value)"


def orca_src(kind, key):
    a, b = OD[kind][key]
    return f"Orca CORE One MMU3 delta: `{ORCA_FILE[kind]}` `{key}` ({_clip(a)} -> {_clip(b)} vs `{C1[kind][0]}`)"


OD = dict(machine=OD_M, process=OD_P, filament=OD_F)

# Every key in Orca's CORE One MMU3 delta must be classified here; anything new upstream stops the script.
ORCA_MACHINE_POLICY = {
    "name": "renamed per pack",
    "inherits": "flattened pack has no inherits",
    "setting_id": "identity of the Orca system preset; the generated packs are not Orca system presets, so the key is removed",
    "single_extruder_multi_material": "APPLY (also PrusaSlicer MMU3 delta)",
    "cooling_tube_length": "APPLY with the PrusaSlicer per-printer value",
    "cooling_tube_retraction": "APPLY with the PrusaSlicer per-printer value",
    "parking_pos_retraction": "APPLY with the PrusaSlicer per-printer value",
    "extra_loading_move": "APPLY with the PrusaSlicer per-printer value",
    "machine_load_filament_time": "APPLY: PrusaSlicer filament_change_time split in Orca's CORE One ratio",
    "machine_unload_filament_time": "APPLY: PrusaSlicer filament_change_time split in Orca's CORE One ratio",
    "machine_start_gcode": "APPLY: PrusaSlicer MMU3 start_gcode translated the way Orca translated CORE One's",
    "machine_end_gcode": "APPLY: PrusaSlicer MMU3 end_gcode (Orca kept CORE One's verbatim)",
    "change_filament_gcode": "APPLY: empty (engine emits Tn; Prusa firmware drives the MMU3 on Tn)",
    "printer_model": "APPLY per pack",
    "printer_notes": "APPLY per pack (markers, see report)",
    "default_print_profile": "APPLY: points at the generated process name",
    "default_filament_profile": "APPLY: points at the generated filament name",
    "machine_max_speed_z": "NOT APPLIED: CORE One-specific ([12,12] -> [12,40]); PrusaSlicer machine limits are identical "
                           "with and without MMU3 for MK4S/MK3.9/MK3.5 (research 1.3)",
    "nozzle_height": "NOT APPLIED: CORE One MMU3 nozzle-tip height (4) is a physical toolhead value, not an MMU setting, and "
                     "PrusaSlicer has no such key; value for MK4S/MK3.5 unknown -> engine default 2.5 kept",
    "before_layer_change_gcode": "NOT APPLIED: Orca dropped CORE One's M201 interpolate_table block in the MMU3 file; "
                                 "PrusaSlicer keeps before_layer_gcode identical with and without MMU3 (research 1.4), so the "
                                 "source pack's block stays",
    "layer_change_gcode": "NOT APPLIED: only a trailing newline / list-vs-string difference in Orca; PrusaSlicer layer_gcode "
                          "is unchanged by MMU3",
    "printer_extruder_id": "NOT APPLIED: not an option in the engine's PrintConfig.cpp (Orca/Bambu variant bookkeeping)",
    "printer_extruder_variant": "NOT APPLIED: not an option in the engine's PrintConfig.cpp",
    "wipe_tower_type": "NOT APPLIED: not an option in the engine's PrintConfig.cpp; the engine always uses WipeTower2 "
                       "for non-Bambu printers (Print.cpp `bUseWipeTower2 = is_BBL_printer() ? false : true`), which is "
                       "what Orca's `type2` selects",
}
ORCA_PROCESS_POLICY = {
    "name": "renamed per pack",
    "inherits": "flattened pack has no inherits",
    "setting_id": "removed (Orca system preset identity)",
    "compatible_printers": "APPLY: [generated machine name] (Orca pattern)",
    "compatible_printers_condition": "APPLY: \"\" (Orca pattern)",
    "prime_tower_brim_width": "APPLY Orca value 2 (PrusaSlicer wipe_tower_brim_width is 3; Orca chose 2)",
    "wipe_tower_cone_angle": "APPLY 25 (Orca and PrusaSlicer agree)",
    "wipe_tower_extra_flow": "APPLY with PrusaSlicer per-family value (MK4 family 250% = Orca; MK3.5 100%)",
    "wipe_tower_extra_spacing": "APPLY with PrusaSlicer per-family value (MK4 family 110% = Orca; MK3.5 100%)",
    "filename_format": "NOT APPLIED: Orca's MMU3 format hard-codes filament_type[0..4] and 'COREONE_MMU3'; output naming "
                       "is the app's business and the source pack's format stays",
    "print_extruder_id": "NOT APPLIED: not an option in the engine's PrintConfig.cpp",
    "print_extruder_variant": "NOT APPLIED: not an option in the engine's PrintConfig.cpp",
}
ORCA_FILAMENT_POLICY = {
    "name": "renamed per pack",
    "inherits": "flattened pack has no inherits",
    "setting_id": "removed (Orca system preset identity)",
    "compatible_printers": "APPLY: [generated machine name]",
    "filament_cooling_final_speed": "APPLY (MK4 family = Orca = PrusaSlicer; MK3.5 PrusaSlicer MK3.5 value)",
    "filament_cooling_initial_speed": "APPLY (as above)",
    "filament_cooling_moves": "APPLY (as above)",
    "filament_loading_speed": "APPLY (as above)",
    "filament_loading_speed_start": "APPLY (as above)",
    "filament_unloading_speed": "APPLY (as above)",
    "filament_unloading_speed_start": "APPLY (as above)",
    "filament_stamping_distance": "APPLY (as above)",
    "filament_stamping_loading_speed": "APPLY (as above)",
    "filament_ramming_parameters": "APPLY (as above)",
    "filament_start_gcode": "MK4 family: APPLY Orca's MMU3 string (M572 S0.03 @0.4 = PrusaSlicer Generic PLA "
                            "pressure_advance_value for MK4S and MK3.9 0.4, and it no longer depends on printer_notes "
                            "tokens that MK3.9 lacks). MK3.5: NOT APPLIED (MK3.5 pack uses enable_pressure_advance/"
                            "pressure_advance 0.035 = PrusaSlicer MK3.5 0.4 value)",
    "idle_temperature": "NOT APPLIED: 150 -> 170 is the CORE One Generic PLA idle temperature (PrusaSlicer CORE One uses "
                        "170 with and without MMU3); PrusaSlicer MK4S/MK3.9/MK3.5 idle_temperature is not MMU-dependent "
                        "and the generated start G-code does not read it",
    "filament_extruder_variant": "NOT APPLIED: Orca variant bookkeeping; the engine has the option but none of the "
                                 "machine-side variant keys, and the non-MMU packs carry none",
}
for kind, pol in (("machine", ORCA_MACHINE_POLICY), ("process", ORCA_PROCESS_POLICY), ("filament", ORCA_FILAMENT_POLICY)):
    missing = set(OD[kind]) - set(pol)
    assert not missing, f"unclassified Orca CORE One MMU3 {kind} delta keys: {missing}"

# ----------------------------------------------------------------------------------------------------------------
# PrusaSlicer resolutions
# ----------------------------------------------------------------------------------------------------------------
PSP = {c: PS.printer_preset(c, 0.4) for c in ("mk4s", "mk4smmu3", "mk3.9", "mk3.9mmu3", "mk3.5", "mk3.5mmu3", "c1mmu3")}
PSF = {c: PS.filament("Generic PLA", c, 0.4) for c in ("mk4s", "mk4smmu3", "mk3.9mmu3", "mk3.5", "mk3.5mmu3")}


def ps_print(cfg, name):
    r = [c for c in PS.eval_kind("print", PS.hw_vars(cfg, 0.4)) if c["name"] == name]
    assert len(r) == 1, (cfg, name, len(r))
    return r[0]


def ps_diff(a, b):
    A, B = a["values"], b["values"]
    return {k: (A.get(k, "<unset>"), B.get(k, "<unset>")) for k in sorted(set(A) | set(B)) if A.get(k) != B.get(k)}


# The PrusaSlicer MMU3 printer deltas must be exactly what research 1.2 found.
for base, mmu in (("mk4s", "mk4smmu3"), ("mk3.9", "mk3.9mmu3"), ("mk3.5", "mk3.5mmu3")):
    assert set(ps_diff(PSP[base], PSP[mmu])) == {"single_extruder_multi_material", "filament_change_time",
                                                   "start_gcode", "end_gcode"}, (mmu, ps_diff(PSP[base], PSP[mmu]).keys())

# ----------------------------------------------------------------------------------------------------------------
# G-code translation (PrusaSlicer 3.x -> engine / Orca placeholder syntax), following Orca's CORE One MMU3 rewrite
# ----------------------------------------------------------------------------------------------------------------

def _line(g, prefix):
    ls = [l for l in g.split("\n") if l.startswith(prefix)]
    assert len(ls) == 1, (prefix, ls)
    return ls[0]


PS_M8621 = _line(PSP["c1mmu3"]["values"]["start_gcode"], "M862.1 ")
ORCA_M8621 = _line(scalar(OB_M["machine_start_gcode"]), "M862.1 ")
assert "filament_abrasive" in PS_M8621 and "printer_notes=~/.*ABRASIVE_NOZZLE.*/" in ORCA_M8621

TRANSLATIONS = []  # (description, source) - filled as they are applied


def translate(g, label):
    applied = []
    if "M862.1 " in g:
        l = _line(g, "M862.1 ")
        assert l == PS_M8621, f"{label}: unexpected M862.1 line {l!r}"
        g = g.replace(PS_M8621, ORCA_M8621)
        applied.append("M862.1 line: `filament_abrasive[n]`/`is_extruder_used[n]` chain -> "
                       "`printer_notes=~/.*ABRASIVE_NOZZLE.*/`, `nozzle_high_flow[0]` -> `printer_notes=~/.*HF_NOZZLE.*/` "
                       "(the whole line is copied from Orca's CORE One MMU3 machine_start_gcode; PS's M862.1 line is "
                       "identical for CORE One, MK4S, MK3.9 and MK3.5 MMU3, checked)")
    n = len(re.findall(r"is_nil\(", g))
    if n:
        g = re.sub(r"is_nil\(([A-Za-z_]+\[[A-Za-z_0-9]+\])\)", r"\1 == 0", g)
        applied.append(f"`is_nil(x)` -> `x == 0` ({n}x; Orca idle_temperature is a non-nullable int, default 0)")
    if re.search(r"\bspiral_vase\b", g):
        g = re.sub(r"\bspiral_vase\b", "spiral_mode", g)
        applied.append("`spiral_vase` -> `spiral_mode`")
    for bad in ("filament_abrasive", "nozzle_high_flow", "is_nil(", "spiral_vase", "chamber_minimal_temperature"):
        assert bad not in g, f"{label}: untranslated {bad}"
    TRANSLATIONS.append((label, applied))
    return g


# ----------------------------------------------------------------------------------------------------------------
# Build
# ----------------------------------------------------------------------------------------------------------------
SRC_MK4S = load_pack("prusa_mk4s")
SRC_MK35 = load_pack("prusa_mk3_5")

# Orca's own split of CORE One's PrusaSlicer filament_change_time 19.8 into load + unload.
C1_LOAD, C1_UNLOAD = float(OB_M["machine_load_filament_time"]), float(OB_M["machine_unload_filament_time"])
C1_TOTAL = float(PSP["c1mmu3"]["values"]["filament_change_time"])
assert abs(C1_LOAD + C1_UNLOAD - C1_TOTAL) < 1e-9, (C1_LOAD, C1_UNLOAD, C1_TOTAL)
LOAD_RATIO = C1_LOAD / C1_TOTAL


def split_time(total):
    load = round(total * LOAD_RATIO, 2)
    return fmt2(load), fmt2(round(total - load, 2))


PACKS = []


def build_machine(p, cfg, printer_model, notes_add, notes_replace=None, extra_note_line=None):
    m = p.data["machine"]
    v = PSP[cfg]["values"]
    mname = f"{printer_model} 0.4 nozzle"
    p.set("machine", "name", mname, "pack naming (Orca pattern `Prusa CORE One MMU3 0.4 nozzle`)")
    p.set("machine", "printer_model", printer_model,
          orca_src("machine", "printer_model") + "; value is this pack's MMU3 model name")
    # PrusaSlicer MMU3 printer deltas + SEMM geometry
    p.set("machine", "single_extruder_multi_material", num(v["single_extruder_multi_material"]),
          ps_source(PSP[cfg], "single_extruder_multi_material", cfg))
    for k in ("cooling_tube_retraction", "cooling_tube_length", "parking_pos_retraction", "extra_loading_move"):
        p.set("machine", k, num(v[k]), ps_source(PSP[cfg], k, cfg) + f"; engine default would be used otherwise "
              f"({'not in source pack' if k not in p.src['machine'] else 'in source pack'})")
    p.set("machine", "high_current_on_filament_swap", num(v["high_current_on_filament_swap"]),
          ps_source(PSP[cfg], "high_current_on_filament_swap", cfg) + " (same as engine default 0; set explicitly)")
    total = float(v["filament_change_time"])
    load, unload = split_time(total)
    src = (ps_source(PSP[cfg], "filament_change_time", cfg) + f" = {num(v['filament_change_time'])} s, split "
           f"{C1_LOAD:g}/{C1_TOTAL:g} : {C1_UNLOAD:g}/{C1_TOTAL:g} like Orca's CORE One MMU3 (ESTIMATE ONLY: time "
           f"estimate, no effect on motion)")
    p.set("machine", "machine_load_filament_time", load, src)
    p.set("machine", "machine_unload_filament_time", unload, src)
    p.set("machine", "change_filament_gcode", "",
          orca_src("machine", "change_filament_gcode") + "; PrusaSlicer toolchange_gcode unset (default \"\")")
    # G-code
    sg = translate(v["start_gcode"], f"{p.key} machine_start_gcode")
    eg = translate(v["end_gcode"], f"{p.key} machine_end_gcode")
    p.set("machine", "machine_start_gcode", sg,
          ps_source(PSP[cfg], "start_gcode", cfg) + ", translated (see G-code section)")
    p.set("machine", "machine_end_gcode", eg, ps_source(PSP[cfg], "end_gcode", cfg) + ", translated (see G-code section)")
    # printer_notes
    old_notes = scalar(m["printer_notes"])
    lines = old_notes.split("\n")
    if notes_replace:
        lines = [notes_replace.get(l, l) for l in lines]
    lines += notes_add
    if extra_note_line:
        lines.append(extra_note_line)
    p.set("machine", "printer_notes", "\n".join(lines),
          "markers modelled on " + orca_src("machine", "printer_notes").split(";")[0] + " (see printer_notes section)")
    p.delete("machine", "setting_id", ORCA_MACHINE_POLICY["setting_id"])
    return mname


def build_process(p, mname, suffix_name):
    pr = p.data["process"]
    p.set("process", "name", suffix_name, "pack naming (Orca pattern `... + MMU3`)")
    p.set("process", "compatible_printers", [mname], orca_src("process", "compatible_printers") + "; this pack's machine")
    p.set("process", "compatible_printers_condition", "", orca_src("process", "compatible_printers_condition"))
    p.set("process", "prime_tower_brim_width", scalar(OB_P["prime_tower_brim_width"]),
          orca_src("process", "prime_tower_brim_width") + "; PrusaSlicer wipe_tower_brim_width = 3 (preset-print-common.yaml)")
    p.set("process", "wipe_tower_cone_angle", scalar(OB_P["wipe_tower_cone_angle"]),
          orca_src("process", "wipe_tower_cone_angle") + "; PrusaSlicer wipe_tower_cone_angle = 25 too")
    assert pr.get("enable_prime_tower") in ("1", ["1"]), "enable_prime_tower must be on"
    p.note("process", "`enable_prime_tower` is already \"1\" and `prime_tower_width` \"60\" in the source pack; Orca's CORE One "
                      "MMU3 resolves to the same (1 / 60) and PrusaSlicer wipe_tower 1 / width default 60, so both stay.")
    p.delete("process", "setting_id", ORCA_PROCESS_POLICY["setting_id"])


def ps_print_source(cfg, name, key):
    return ps_source(ps_print(cfg, name), key, f"{cfg} print `{name}`")


def build_filament(p, mname, fname):
    p.set("filament", "name", fname, "pack naming (Orca pattern `Generic PLA @Prusa CORE One MMU3`)")
    p.set("filament", "compatible_printers", [mname], orca_src("filament", "compatible_printers") + "; this pack's machine")
    for k in ("setting_id", "renamed_from"):
        if k in p.data["filament"]:
            p.delete("filament", k, "identity metadata of the Orca non-MMU system preset; removed")


SEMM_FIL_KEYS = ["filament_loading_speed", "filament_loading_speed_start", "filament_unloading_speed",
                 "filament_unloading_speed_start", "filament_cooling_moves", "filament_cooling_initial_speed",
                 "filament_cooling_final_speed", "filament_stamping_loading_speed", "filament_stamping_distance",
                 "filament_ramming_parameters"]

# ---------------------------------------------------------------- MK4S MMU3
mk4s = Pack("prusa_mk4s_mmu3", "Prusa MK4S MMU3", "prusa_mk4s", SRC_MK4S)
MARKER_LINE = "The MK4 marker enables WipeTower2 cold ramming and filament monitoring control for the {}."
m4 = build_machine(mk4s, "mk4smmu3", "Prusa MK4S MMU3", ["PRINTER_MODEL_MK4SMMU3", "PRINTER_MODEL_MK4"],
                   extra_note_line=MARKER_LINE.format("MK4S MMU3"))
PROC_MK4S = SRC_MK4S["process"]["name"] + " + MMU3"
FIL_MK4S = "Generic PLA @Prusa MK4S MMU3"
mk4s.set("machine", "default_print_profile", PROC_MK4S, orca_src("machine", "default_print_profile") + "; this pack's process")
mk4s.set("machine", "default_filament_profile", FIL_MK4S, orca_src("machine", "default_filament_profile") + "; this pack's filament")
build_process(mk4s, m4, PROC_MK4S)
psn = "0.20mm SPEED @MK4S 0.4"
assert ps_diff(ps_print("mk4s", psn), ps_print("mk4smmu3", psn)) == {}, "PS MK4S print must not change with MMU3"
for k in ("wipe_tower_extra_flow", "wipe_tower_extra_spacing"):
    psv = ps_print("mk4smmu3", psn)["values"][k]
    assert psv == scalar(OB_P[k]), (k, psv, OB_P[k])
    mk4s.set("process", k, psv, orca_src("process", k) + "; equals " + ps_print_source("mk4smmu3", psn, k))
build_filament(mk4s, m4, FIL_MK4S)
fv = PSF["mk4smmu3"]["values"]
for k in SEMM_FIL_KEYS:
    ov = scalar(OB_F[k])
    assert num(fv[k]) == ov, (k, fv[k], ov)       # Orca C1 MMU3 value == PrusaSlicer MK4S MMU3 value
    mk4s.set("filament", k, [ov], orca_src("filament", k) + "; equals " + ps_source(PSF["mk4smmu3"], k, "mk4smmu3 Generic PLA"))
assert num(fv["filament_minimal_purge_on_wipe_tower"]) == scalar(SRC_MK4S["filament"]["filament_minimal_purge_on_wipe_tower"])
mk4s.note("filament", "`filament_minimal_purge_on_wipe_tower` stays [\"15\"]: equals PrusaSlicer MK4S Generic PLA (15) and "
                      "Orca CORE One MMU3 (inherited 15).")
fsg = scalar(OB_F["filament_start_gcode"])
assert "M572 S{if nozzle_diameter[filament_extruder_id]==0.4}0.03{" in fsg and float(fv["pressure_advance_value"]) == 0.03
mk4s.set("filament", "filament_start_gcode", [fsg], orca_src("filament", "filament_start_gcode").split(" (")[0] +
         " (whole string); 0.03 @0.4 = " + ps_source(PSF["mk4smmu3"], "pressure_advance_value", "mk4smmu3 Generic PLA"))
PACKS.append(mk4s)

# ---------------------------------------------------------------- MK3.9 MMU3 (derived from MK4S MMU3)
# PrusaSlicer: MK3.9 MMU3 printer == MK4S MMU3 printer except these keys (checked here).
p39 = ps_diff(PSP["mk4smmu3"], PSP["mk3.9mmu3"])
assert set(p39) == {"printer_model", "start_gcode"}, p39
sd = [l for l in difflib.ndiff(PSP["mk4smmu3"]["values"]["start_gcode"].split("\n"),
                               PSP["mk3.9mmu3"]["values"]["start_gcode"].split("\n")) if l[:1] in "+-"]
assert sd == ['- M862.3 P "MK4S" ; printer model check', '+ M862.3 P "MK3.9" ; printer model check'], sd
f39 = ps_diff(PSF["mk4smmu3"], PSF["mk3.9mmu3"])
mk39 = Pack("prusa_mk3_9_mmu3", "Prusa MK3.9 MMU3", "prusa_mk4s (no Orca MK3.9 profile exists)", SRC_MK4S)
m39 = build_machine(mk39, "mk3.9mmu3", "Prusa MK3.9 MMU3", ["PRINTER_MODEL_MK3.9MMU3", "PRINTER_MODEL_MK4"],
                    notes_replace={"PRINTER_MODEL_MK4S": "PRINTER_MODEL_MK3.9"},
                    extra_note_line=MARKER_LINE.format("MK3.9 MMU3"))
PROC_MK39 = SRC_MK4S["process"]["name"].replace("@MK4S", "@MK3.9") + " + MMU3"
FIL_MK39 = "Generic PLA @Prusa MK3.9 MMU3"
mk39.set("machine", "default_print_profile", PROC_MK39, orca_src("machine", "default_print_profile") + "; this pack's process")
mk39.set("machine", "default_filament_profile", FIL_MK39, orca_src("machine", "default_filament_profile") + "; this pack's filament")
build_process(mk39, m39, PROC_MK39)
ps39n = "0.20mm SPEED @MK4 0.4"
for k in ("wipe_tower_extra_flow", "wipe_tower_extra_spacing"):
    psv = ps_print("mk3.9mmu3", ps39n)["values"][k]
    assert psv == scalar(OB_P[k]), (k, psv)
    mk39.set("process", k, psv, orca_src("process", k) + "; equals " + ps_print_source("mk3.9mmu3", ps39n, k))
PS39_PRINT_DIFF = ps_diff(ps_print("mk4smmu3", psn), ps_print("mk3.9mmu3", ps39n))
build_filament(mk39, m39, FIL_MK39)
fv39 = PSF["mk3.9mmu3"]["values"]
for k in SEMM_FIL_KEYS:
    ov = scalar(OB_F[k])
    assert num(fv39[k]) == ov, (k, fv39[k], ov)
    mk39.set("filament", k, [ov], orca_src("filament", k) + "; equals " + ps_source(PSF["mk3.9mmu3"], k, "mk3.9mmu3 Generic PLA"))
assert float(fv39["pressure_advance_value"]) == 0.03
mk39.set("filament", "filament_start_gcode", [fsg], orca_src("filament", "filament_start_gcode").split(" (")[0] +
         " (whole string); 0.03 @0.4 = " + ps_source(PSF["mk3.9mmu3"], "pressure_advance_value", "mk3.9mmu3 Generic PLA"))
PACKS.append(mk39)

# ---------------------------------------------------------------- MK3.5 MMU3
mk35 = Pack("prusa_mk3_5_mmu3", "Prusa MK3.5 MMU3", "prusa_mk3_5", SRC_MK35)
m35 = build_machine(mk35, "mk3.5mmu3", "Prusa MK3.5 MMU3", ["PRINTER_MODEL_MK3.5MMU3"],
                    extra_note_line="No MK4-family ramming marker on purpose (it must never be added to these notes): PrusaSlicer's "
                                    "MK3.5 MMU3 uses neither cold ramming nor the ramming delay nor stuck-filament monitoring.")
PROC_MK35 = SRC_MK35["process"]["name"] + " + MMU3"
FIL_MK35 = "Generic PLA @Prusa MK3.5 MMU3"
mk35.set("machine", "default_print_profile", PROC_MK35, orca_src("machine", "default_print_profile") + "; this pack's process")
mk35.set("machine", "default_filament_profile", FIL_MK35, orca_src("machine", "default_filament_profile") + "; this pack's filament")
build_process(mk35, m35, PROC_MK35)
ps35 = {n: (ps_print("mk3.5", n), ps_print("mk3.5mmu3", n)) for n in
        ("0.20mm SPEED @MK3.5 0.4", "0.20mm STRUCTURAL @MK3.5 0.4", "0.15mm SPEED @MK3.5 0.4", "0.15mm STRUCTURAL @MK3.5 0.4")}
ps35d = {n: ps_diff(a, b) for n, (a, b) in ps35.items()}
for k in ("wipe_tower_extra_flow", "wipe_tower_extra_spacing"):
    psv = ps35["0.20mm SPEED @MK3.5 0.4"][1]["values"][k]
    mk35.set("process", k, psv, ps_print_source("mk3.5mmu3", "0.20mm SPEED @MK3.5 0.4", k) +
             f" (Orca CORE One MMU3 uses {scalar(OB_P[k])} = MK4-family value; not used for MK3.5)")
# MMU override values shared by BOTH 0.20mm/0.4 PrusaSlicer profiles (the source pack's "0.20mm Standard @MK3.5" is
# neither of them). Mapping PS key -> Orca key.
PS2ORCA_PRINT = {"default_acceleration": "default_acceleration", "infill_acceleration": "sparse_infill_acceleration",
                 "solid_infill_acceleration": "internal_solid_infill_acceleration", "bridge_speed": "bridge_speed",
                 "wipe_tower_bridging": "wipe_tower_bridging", "infill_speed": "sparse_infill_speed",
                 "perimeter_speed": "inner_wall_speed", "external_perimeter_speed": "outer_wall_speed",
                 "small_perimeter_speed": "small_perimeter_speed", "perimeter_acceleration": "inner_wall_acceleration"}
d20s, d20t = ps35d["0.20mm SPEED @MK3.5 0.4"], ps35d["0.20mm STRUCTURAL @MK3.5 0.4"]
COMMON35 = {k: d20s[k][1] for k in d20s if k in d20t and d20t[k][1] == d20s[k][1]}
DIFFER35 = {k: (d20s.get(k, ("-", "-"))[1], d20t.get(k, ("-", "-"))[1]) for k in set(d20s) | set(d20t) if k not in COMMON35}
assert COMMON35 == {"default_acceleration": 1500, "infill_acceleration": 2500, "solid_infill_acceleration": 2500,
                    "bridge_speed": 40, "wipe_tower_bridging": 8}, COMMON35
MK35_PROC_SKIPPED = []
for pk, val in COMMON35.items():
    ok = PS2ORCA_PRINT[pk]
    cur = mk35.data["process"].get(ok)
    src = (ps_print_source("mk3.5mmu3", "0.20mm SPEED @MK3.5 0.4", pk) + " and " +
           ps_print_source("mk3.5mmu3", "0.20mm STRUCTURAL @MK3.5 0.4", pk) + f" (MMU override, same value in both; PS `{pk}`)")
    if cur is not None and pk != "wipe_tower_bridging" and float(scalar(cur)) <= float(val):
        MK35_PROC_SKIPPED.append((ok, cur, val, src))
        continue
    mk35.set("process", ok, num(val), src, style_from="x")
build_filament(mk35, m35, FIL_MK35)
f35 = PSF["mk3.5mmu3"]["values"]
fd35 = ps_diff(PSF["mk3.5"], PSF["mk3.5mmu3"])
assert set(fd35) == {"filament_max_volumetric_speed", "temperature"}, fd35
mk35.set("filament", "nozzle_temperature", num(f35["temperature"]),
         ps_source(PSF["mk3.5mmu3"], "temperature", "mk3.5mmu3 Generic PLA") +
         f" (PS non-MMU {num(fd35['temperature'][0])} -> MMU3 {num(fd35['temperature'][1])}; absolute MMU3 value applied)")
mk35.set("filament", "filament_max_volumetric_speed", num(f35["filament_max_volumetric_speed"]),
         ps_source(PSF["mk3.5mmu3"], "filament_max_volumetric_speed", "mk3.5mmu3 Generic PLA") +
         f" (PS non-MMU {num(fd35['filament_max_volumetric_speed'][0])} -> MMU3 {num(fd35['filament_max_volumetric_speed'][1])})")
for k in SEMM_FIL_KEYS:
    mk35.set("filament", k, [num(f35[k])], ps_source(PSF["mk3.5mmu3"], k, "mk3.5mmu3 Generic PLA") +
             f" (Orca CORE One MMU3 sets this key to the MK4-family value {json.dumps(scalar(OB_F[k]))}; MK3.5 value used)")
assert num(f35["filament_minimal_purge_on_wipe_tower"]) == scalar(SRC_MK35["filament"]["filament_minimal_purge_on_wipe_tower"])
mk35.note("filament", "`filament_minimal_purge_on_wipe_tower` stays [\"15\"] = PrusaSlicer MK3.5 MMU3 Generic PLA (15).")
assert float(f35["pressure_advance_value"]) == float(scalar(SRC_MK35["filament"]["pressure_advance"]))
mk35.note("filament", "`enable_pressure_advance` [\"1\"] / `pressure_advance` [\"0.035\"] stay: equal PrusaSlicer MK3.5 0.4 "
                      "Generic PLA pressure_advance_value 0.035 (MMU and non-MMU).")
PACKS.append(mk35)

# ----------------------------------------------------------------------------------------------------------------
# Write packs + placeholder checks
# ----------------------------------------------------------------------------------------------------------------
PH = {}
for p in PACKS:
    od = os.path.join(OUT, p.key)
    os.makedirs(od, exist_ok=True)
    for f in ("machine", "process", "filament"):
        with open(os.path.join(od, f + ".json"), "w") as fh:
            json.dump(p.data[f], fh, indent=4)
            fh.write("\n")
    for f, keys in (("machine", ("machine_start_gcode", "machine_end_gcode", "before_layer_change_gcode",
                                 "layer_change_gcode", "change_filament_gcode", "machine_pause_gcode")),
                    ("filament", ("filament_start_gcode", "filament_end_gcode")),
                    ("process", ("filename_format",))):
        for k in keys:
            if k in p.data[f]:
                PH[(p.key, f, k)] = check_placeholders(scalar(p.data[f][k]))
    # the engine's MMU3 flag, evaluated exactly like WipeTower2.cpp
    notes = scalar(p.data["machine"]["printer_notes"]).lower()
    p.mk4mmu3 = ("printer_model_mk4" in notes) and ("mmu" in notes)
    # every key written must be an engine option (or pack metadata)
    META = {"type", "name", "from", "instantiation", "inherits", "setting_id", "renamed_from", "filament_id",
            "compatible_printers", "compatible_printers_condition", "print_settings_id", "printer_settings_id",
            "filament_settings_id", "default_filament_colour"}
    for f in ("machine", "process", "filament"):
        for k, _, new, _ in p.log[f]:
            if new != "<removed>" and k not in META:
                assert k in ENGINE_KEYS, f"{p.key}/{f}: {k} is not an engine option"

assert [p.mk4mmu3 for p in PACKS] == [True, True, False]

# ----------------------------------------------------------------------------------------------------------------
# REPORT.md
# ----------------------------------------------------------------------------------------------------------------

def short(v, n=90):
    s = json.dumps(v)
    if len(s) > n or "\\n" in s:
        return "*(long; full text below)*"
    return s


R = []
w = R.append
w("# Prusa MMU3 packs for Nozzle It All: generation report\n")
w("Generated by `make_prusa_mmu3_packs.py` (this directory). Re-run with `python3 make_prusa_mmu3_packs.py`; "
  "it only reads the sources below and writes `out/` and this file.\n")
w("## Sources\n")
w(f"- Non-MMU packs (read-only): `{APP_PROFILES}/prusa_mk4s/`, `{APP_PROFILES}/prusa_mk3_5/`. Both are exactly the "
  "resolved Orca `Prusa MK4S 0.4 nozzle` / `0.20mm SPEED @MK4S 0.4` / `Generic PLA @Prusa MK4S` and `Prusa MK3.5 0.4 "
  "nozzle` / `0.20mm Standard @MK3.5` / `Generic PLA @Prusa MK3.5` minus `inherits` (checked).")
w(f"- PrusaSlicer 3.x YAML presets: `{PS_DIR}` resolved with `scripts/prusa_mmu3/pseval.py` (copy of the research scratch helper). "
  "PS printer configs used: `mk4smmu3`, `mk3.9mmu3`, `mk3.5mmu3` (and `mk4s`, `mk3.9`, `mk3.5`, `c1mmu3` for comparison), 0.4 nozzle, filament `Generic PLA`.")
w(f"- Orca: `{OR.OD}` via `scripts/prusa_mmu3/orca.py`. Orca MMU3 deltas = resolved `{C1['machine'][1]}` vs `{C1['machine'][0]}`, "
  f"`{C1['process'][1]}` vs `{C1['process'][0]}`, `{C1['filament'][1]}` vs `{C1['filament'][0]}`.")
w(f"- Engine vocabulary: `{ENGINE}/PrintConfig.cpp` ({len(ENGINE_KEYS)} option keys via `this->add(\"...\"`) and "
  f"`GCode.cpp` ({len(GCODE_PLACEHOLDERS)} names via `placeholder_parser().set(\"...\"` / `set_key_value(\"...\"` / `.set(\"...\"`).\n")

w("## Output\n")
for p in PACKS:
    w(f"- `out/{p.key}/` - {p.title} (from `{p.src_dir}`): machine `{p.data['machine']['name']}`, process "
      f"`{p.data['process']['name']}`, filament `{p.data['filament']['name']}`. Engine MMU3 flag "
      f"(`m_is_mk4mmu3`, WipeTower2.cpp l.1361): **{'ON' if p.mk4mmu3 else 'OFF'}**.")
w("\nAll three: one `nozzle_diameter` entry (`[\"0.4\"]`), `single_extruder_multi_material` \"1\", `change_filament_gcode` "
  "empty (the engine writes `Tn`; Prusa firmware runs the MMU3 load/unload on `Tn`), `enable_prime_tower` \"1\". "
  "The 5 filament slots are expected to come from the app.\n")

w("## Orca CORE One MMU3 deltas and what was done with each\n")
for kind, pol in (("machine", ORCA_MACHINE_POLICY), ("process", ORCA_PROCESS_POLICY), ("filament", ORCA_FILAMENT_POLICY)):
    w(f"### {kind} (`{C1[kind][0]}` -> `{C1[kind][1]}`)\n")
    w("| key | CORE One | CORE One MMU3 | decision |\n|---|---|---|---|")
    for k, (a, b) in OD[kind].items():
        w(f"| `{k}` | {_clip(a, 60)} | {_clip(b, 60)} | {pol[k]} |")
    w("")

for p in PACKS:
    w(f"## {p.title} (`out/{p.key}/`, source pack `{p.src_dir}`)\n")
    for f in ("machine", "process", "filament"):
        w(f"### {f}.json\n")
        w("| key | old | new | source |\n|---|---|---|---|")
        for k, old, new, src in p.log[f]:
            w(f"| `{k}` | {short(old)} | {short(new)} | {src} |")
        w("")
        for n in p.notes[f]:
            w(f"- {n}")
        if p.notes[f]:
            w("")
        long_changes = [(k, old, new) for k, old, new, _ in p.log[f]
                        if new != "<removed>" and ("\\n" in json.dumps(new) or len(json.dumps(new)) > 90)]
        for k, old, new in long_changes:
            if k in ("machine_start_gcode", "machine_end_gcode"):
                continue
            w(f"`{k}` old:\n```\n{scalar(old) if old != '<unset>' else '<unset>'}\n```\nnew:\n```\n{scalar(new)}\n```\n")
    if p is mk35:
        w("### MK3.5 process: PrusaSlicer MMU print overrides\n")
        w("The source pack's process is Orca's `0.20mm Standard @MK3.5`, which is not one of the four PrusaSlicer "
          "profiles that carry an MMU override (`0.15/0.20mm SPEED/STRUCTURAL @MK3.5 0.4`, preset-print-mk35.yaml "
          "l.565-658). Rule used: apply the override values that are **identical in both 0.20mm profiles**; for speed/"
          "acceleration keys apply them only when they lower the pack's value (the override exists to slow MMU "
          "prints down, and raising a value the Orca profile already keeps lower would contradict it).")
        w("\nApplied: see the process table above. Skipped because the pack value is already lower or equal:\n")
        for ok, cur, val, src in MK35_PROC_SKIPPED:
            w(f"- `{ok}`: pack {json.dumps(cur)} <= PS MMU value {val} ({src}).")
        w("\nNot applied because the two 0.20mm profiles disagree (the Orca Standard profile matches neither):\n")
        w("| PS key | Orca key | 0.20mm SPEED MMU | 0.20mm STRUCTURAL MMU | pack value (kept) |\n|---|---|---|---|---|")
        for k, (a, b) in sorted(DIFFER35.items()):
            ok = PS2ORCA_PRINT.get(k, "?")
            w(f"| `{k}` | `{ok}` | {a} | {b} | {json.dumps(mk35.data['process'].get(ok, '<unset>'))} |")
        w("\nPS-to-Orca key mapping for accelerations: PS `infill_acceleration` -> Orca `sparse_infill_acceleration`, PS "
          "`solid_infill_acceleration` -> Orca `internal_solid_infill_acceleration` (the source pack's 4000/3000 equal PS "
          "non-MMU 4000/3000, which supports the mapping). `internal_bridge_speed` (Orca-only, 50) was left alone: "
          "PrusaSlicer has one `bridge_speed`.\n")
    if p is mk39:
        w("### MK3.9 derivation (from the MK4S pack)\n")
        w("Orca has no MK3.9 profile. PrusaSlicer `mk3.9mmu3` vs `mk4smmu3` printer presets differ only in "
          f"{sorted(p39)} (checked by the script); the start G-code differs in exactly one line "
          "(`M862.3 P \"MK4S\"` -> `M862.3 P \"MK3.9\"`); end G-code identical. Generic PLA filament differences "
          f"PS MK4S MMU3 -> MK3.9 MMU3: {json.dumps({k: [str(a), str(b)] for k, (a, b) in f39.items()})}.\n")
        w("Every difference applied relative to the MK4S MMU3 pack:\n")
        diffs = []
        for f in ("machine", "process", "filament"):
            A, B = mk4s.data[f], mk39.data[f]
            for k in sorted(set(A) | set(B)):
                if A.get(k) != B.get(k):
                    if k == "machine_start_gcode":
                        dl = [l for l in difflib.ndiff(scalar(A[k]).split("\n"), scalar(B[k]).split("\n")) if l[:1] in "+-"]
                        diffs.append(f"- {f} `{k}`: {' / '.join('`' + x + '`' for x in dl)}")
                    else:
                        diffs.append(f"- {f} `{k}`: {short(A.get(k, '<unset>'), 200)} -> {short(B.get(k, '<unset>'), 200)}")
        R.extend(diffs)
        w("\nMK3.9 MMU3 `printer_notes`:\n```\n" + scalar(mk39.data["machine"]["printer_notes"]) + "\n```")
        w("\n`idle_temperature`: PS MK4S Generic PLA 150, MK3.9 unset; both packs keep the Orca MK4S value \"0\" (not "
          "used by the generated start G-code). PrusaSlicer's MK3.9 print profile `0.20mm SPEED @MK4 0.4` differs from "
          f"`0.20mm SPEED @MK4S 0.4` in {json.dumps({k: [str(a), str(b)] for k, (a, b) in PS39_PRINT_DIFF.items()})}; "
          "not applied, because the source process is Orca's MK4S profile (which already differs from PS MK4S in "
          "these families of keys, e.g. Orca `support_threshold_angle` is already 40) and PS `overhang_speed_2` has no "
          "confirmed one-to-one Orca key (Orca `overhang_1_4..4_4_speed` are defined by different overhang bands).\n")

w("## printer_notes and the engine's MMU3 ramming behaviour\n")
w("The engine (WipeTower2.cpp l.1361) sets `m_is_mk4mmu3 = icontains(printer_notes, \"PRINTER_MODEL_MK4\") && "
  "icontains(printer_notes, \"MMU\")`. When on it: keeps linear advance on during ramming and turns it off for the "
  "cooling moves (= PS `enable_pressure_advance_during_ramming = true`), sets the nozzle to old-20 C for ramming "
  "(= PS `filament_ramming_temperature_delta = -20`), sends filament-monitoring off + `G4` 1.5 s before ramming and "
  "monitoring on after the change (= PS `filament_ramming_initial_delay = 1.5` + `stuck_filament_detection = 1`).\n")
w("| pack | PS ramming delta / delay | PS stuck_filament_detection | PS enable_pressure_advance_during_ramming | markers added | engine flag |")
w("|---|---|---|---|---|---|")
for p, cfg, fcfg in ((mk4s, "mk4smmu3", "mk4smmu3"), (mk39, "mk3.9mmu3", "mk3.9mmu3"), (mk35, "mk3.5mmu3", "mk3.5mmu3")):
    fvv = PSF[fcfg]["values"]
    pv = PSP[cfg]["values"]
    added = [l for l in scalar(p.data["machine"]["printer_notes"]).split("\n") if l not in scalar(p.src["machine"]["printer_notes"]).split("\n")]
    w(f"| {p.title} | {fvv.get('filament_ramming_temperature_delta', '0 (default)')} / {fvv.get('filament_ramming_initial_delay', '0 (default)')} "
      f"| {pv.get('stuck_filament_detection')} | {pv.get('enable_pressure_advance_during_ramming')} | "
      f"{'<br>'.join('`' + a + '`' for a in added)} | {'ON' if p.mk4mmu3 else 'OFF'} |")
w("\nDecision for MK3.5: PrusaSlicer gives MK3.5 MMU3 no -20 C / 1.5 s ramming values (the `*mmu specific*` fragment "
  "only matches `printer.base_model=~/(COREONE|MK4)/`), `stuck_filament_detection = 0` and "
  "`enable_pressure_advance_during_ramming = False`. With the flag OFF the engine disables linear advance before "
  "ramming (when `m_change_pressure`), does no cold ramming and no monitoring toggling - which matches all three PS "
  "values. So MK3.5 does **not** carry `PRINTER_MODEL_MK4`; its notes get `PRINTER_MODEL_MK3.5MMU3` (contains `MMU`, "
  "harmless without the MK4 marker).\n")
w("**Known gap (MK4S / MK3.9):** the engine flag is all-or-nothing. PS MK4S/MK3.9 MMU3 want cold ramming + 1.5 s delay "
  "+ stuck detection (flag ON gives these) but `enable_pressure_advance_during_ramming = False` (flag ON instead keeps "
  "LA on during ramming, which PS only does for CORE One and plain MK4). The engine has no "
  "`enable_pressure_advance_during_ramming`, `filament_ramming_temperature_delta`, `filament_ramming_initial_delay` "
  "or `stuck_filament_detection` option to express the difference. The packs follow the Orca CORE One MMU3 approach "
  "(flag ON), i.e. PA stays on during ramming on MK4S/MK3.9 - a deviation from PrusaSlicer.\n")
w("Orca's CORE One MMU3 notes also contain `COREMMU` and `RAMMING_EXTRA`. Neither string is referenced anywhere in "
  "Orca's `src/` or in any other Orca profile (grep), nor in the engine, so they were not copied. `PRINTER_MODEL_MK4` "
  "is added explicitly (for MK4S it is already a substring of `PRINTER_MODEL_MK4S`).\n")

w("## Translated G-code\n")
w("Rules (from Orca's CORE One MMU3 translation, research sections 2 and 4) and where they applied:\n")
for label, applied in TRANSLATIONS:
    w(f"- `{label}`: " + ("; ".join(applied) if applied else "no placeholder translation needed (PrusaSlicer text kept verbatim)"))
w("\nNot applicable to these three printers (nothing to translate): `is_nil(idle_temperature[...])` and "
  "`chamber_minimal_temperature` appear only in the CORE One start G-code; `spiral_vase` appears only in PS "
  "`layer_gcode`, which is not replaced (the source packs' `layer_change_gcode` already uses `spiral_mode`). "
  "`[initial_tool]` is used everywhere PS uses it; the MK4S/MK3.9/MK3.5 PS MMU3 start G-code has no `[0]`-indexed "
  "second MBL `M109` (the Orca slot-0 regression is CORE One-only), so no slot-0 indexing was introduced.\n")
w("Deliberately **not** copied from Orca's CORE One rewrite, because they are not placeholder translations and the "
  "PrusaSlicer MK4S/MK3.9/MK3.5 text is valid in the engine: `M302 S155` -> `S160`, `M190 S` -> `M190 R`, dropping the "
  "`filament_notes` `MBL160` branch, `(FLEX|TPU|TPE)` regex -> `== \"FLEX\"`, the PET 175 C branch Orca added to CORE "
  "One's first `M109` (PS MK4S/MK3.9 MMU3 already have it; PS MK3.5 MMU3 uses a fixed 170), the CORE One chamber-logic "
  "rewrite, comment rewording/typo fixes (`feeedrate`) and the `; define print area` comment. The non-MMU source packs "
  "(older Orca start G-code) use `M302 S160` and no MBL160 branch; the MMU3 packs follow PrusaSlicer. The PrusaSlicer MMU3 end G-code is used as is "
  "(the plain-MK4 MMU3 end G-code with the commented YAML lines is not involved: MK4S/MK3.9 use fragment "
  "`sDaINpExS2qxgbDnvceoEA` / `rsVS1tgtQyKf7pWb54FcZA`).\n")
for p in PACKS:
    for k in ("machine_start_gcode", "machine_end_gcode"):
        w(f"### {p.title} `{k}`\n")
        w("```gcode\n" + scalar(p.data["machine"][k]) + "\n```\n")
        old = scalar(p.src["machine"][k])
        dl = list(difflib.unified_diff(old.split("\n"), scalar(p.data["machine"][k]).split("\n"),
                                       f"{p.src_dir} (non-MMU pack)", p.key, lineterm="", n=0))
        w("<details><summary>diff vs the non-MMU pack</summary>\n\n```diff\n" + "\n".join(dl) + "\n```\n</details>\n")

w("## Placeholder check\n")
w("Every identifier inside `{...}` and every bare `[name]` in the written G-code fields was looked up in the engine's "
  "`PrintConfig.cpp` option keys and the names `GCode.cpp` sets on the placeholder parser. Parser keywords/functions "
  f"ignored: {', '.join(sorted(PARSER_WORDS - {'filament_extruder_id'}))}.\n")
allun = {}
for (pk, f, k), (conf, un) in PH.items():
    w(f"- `{pk}` {f} `{k}`: " + (", ".join(f"`{x}`" for x in conf) if conf else "(no placeholders)") +
      (f" - **UNCONFIRMED: {', '.join(un)}**" if un else ""))
    for u in un:
        allun.setdefault(u, []).append(f"{pk}/{f}/{k}")
w("")
if allun:
    w("**Unconfirmed placeholders:**\n")
    for u, where in allun.items():
        w(f"- `{u}` in {', '.join(where)}")
else:
    w("**Unconfirmed placeholders: none.** (`filament_extruder_id` is set per call in GCode.cpp "
      "`config.set_key_value(\"filament_extruder_id\", ...)`; `first_layer_temperature`, `first_layer_bed_temperature`, "
      "`max_print_height`, `print_bed_max`, `first_layer_print_min/max`, `initial_tool`, `has_wipe_tower`, "
      "`is_extruder_used`, `layer_z`, `max_layer_z` are placeholder_parser values set in GCode.cpp, not config options.)")
w("")
conf_all = {}
for (_, _, _), (conf, _) in PH.items():
    conf_all.update(conf)
w("| name | confirmed in |\n|---|---|")
for k in sorted(conf_all):
    w(f"| `{k}` | {conf_all[k]} |")
w("")

w("## Things that could not be determined or have no engine equivalent\n")
v4, v35 = PSP["mk4smmu3"]["values"], PSP["mk3.5mmu3"]["values"]
w(f"- **Load/unload split is an estimate.** PS has one `filament_change_time` (MK4S/MK3.9 {num(v4['filament_change_time'])} s, "
  f"MK3.5 {num(v35['filament_change_time'])} s). Orca split CORE One's {C1_TOTAL:g} s into {C1_LOAD:g} + {C1_UNLOAD:g}; "
  f"the same ratio ({LOAD_RATIO:.6f}) was used, rounded to 0.01 s with unload = total - load: MK4S/MK3.9 "
  f"{'+'.join(split_time(float(v4['filament_change_time'])))}, MK3.5 {'+'.join(split_time(float(v35['filament_change_time'])))} "
  "(MK3.5's source pack had 17 + 16 = 33 s). Only print-time estimates use these values.")
w("- **`enable_pressure_advance_during_ramming`**: no engine option (see the ramming section; MK4S/MK3.9 get LA-on-"
  "during-ramming from the MK4 marker although PS says False).")
w(f"- **`multimaterial_purging`** (PS MK4S/MK3.9 {v4['multimaterial_purging']}, MK3.5 {v35['multimaterial_purging']}) and "
  f"**`filament_purge_multiplier`** (PS Generic PLA MK4S/MK3.9 {PSF['mk4smmu3']['values'].get('filament_purge_multiplier')}, "
  f"MK3.5 {PSF['mk3.5mmu3']['values'].get('filament_purge_multiplier')}): no engine option. The engine purges per "
  "`flush_volumes_matrix` x `flush_multiplier` (project/app-level; the Orca CORE One MMU3 profile does not set them "
  "either). The app should supply the flush matrix for the 5 slots; the PS numbers are the reference.")
w(f"- **`stuck_filament_detection`** (PS MK4S/MK3.9 {v4['stuck_filament_detection']}, MK3.5 {v35['stuck_filament_detection']}): "
  "no engine option; covered only through the MK4 marker (M591 toggling around ramming).")
w("- **`filament_ramming_temperature_delta` / `filament_ramming_initial_delay`**: no engine option; hard-coded -20 C / "
  "1.5 s behind the MK4 marker (MK4S/MK3.9 ON = PS; MK3.5 OFF = PS defaults 0/0).")
w("- **Abrasive-nozzle / high-flow checks in `M862.1`**: PS derives `A` from `filament_abrasive` of the used slots; "
  "the engine has no `filament_abrasive`/`nozzle_high_flow` option, so (as in Orca) `A`/`F` come from "
  "`ABRASIVE_NOZZLE`/`HF_NOZZLE` tokens in printer_notes. None of these packs has those tokens, so the generated "
  "G-code always sends `A0 F0` (a 0.4 brass/standard-flow nozzle). An abrasive-filament print will not raise "
  "the firmware's abrasive check.")
w("- **Double `Tn` at print start**: the start G-code loads the first slot with `T[initial_tool]` (PS and Orca "
  "CORE One MMU3 do the same) and the engine then writes its own initial toolchange (`set_extruder(initial_extruder_id)`, "
  "GCode.cpp l.3129) for the same slot. Prusa firmware treats `Tn` for the already-loaded slot as a no-op as far as "
  "known, but this was not verified on hardware.")
w("- **`color_change_gcode`** (PS `M600` + prime): no engine key; M600 colour changes are not part of an MMU pack.")
w("- **`binary_gcode`, `remaining_times`, `variable_layer_height`**: PS-only, unchanged by MMU3, not mapped.")
w("- **MK3.5 `retract_length_toolchange`** stays \"2\" (Orca MK3.5 value). PS `*common MK35*` sets `retract_length_toolchange 0` "
  "for both MMU and non-MMU, and Orca CORE One MMU3 has \"0\"; it is not an MMU3 delta in either source, so it was "
  "not changed - but with the MMU3 every tool change will retract 2 mm before the wipe-tower unload. Worth a test print.")
w("- **MK3.5 `nozzle_temperature_initial_layer`** stays \"220\" (Orca). PS first_layer_temperature is 230 for MK3.5 with "
  "and without MMU3 (not an MMU delta). `nozzle_temperature` 220 -> 205 is the PS MMU3 absolute value; the PS non-MMU "
  "value is 225, so the PS MMU delta is -20 C relative to PS, -15 C relative to the Orca source value.")
w("- **MK3.5 printer_model / MK3.5S**: PS `mk3.5mmu3` is named \"Original Prusa MK3.5 & MK3.5S\"; the pack is named "
  "MK3.5 MMU3 and uses `M862.3 P \"MK3.5\"` exactly as PS does.")
w("- **Engine keys Orca uses that the engine lacks** (not written): `wipe_tower_type`, `printer_extruder_id`, "
  "`printer_extruder_variant`, `print_extruder_id`, `print_extruder_variant`.")
w("- **Not verified on hardware or by slicing**: the script did not build or run the engine. The packs are profile "
  "data only; a test slice with 2+ filaments on each pack is the next step.")

with open(os.path.join(HERE, "REPORT.md"), "w") as fh:
    fh.write("\n".join(R) + "\n")

print("wrote", ", ".join(f"out/{p.key}" for p in PACKS), "and REPORT.md")
for p in PACKS:
    print(p.key, {f: len(p.log[f]) for f in p.log}, "mk4mmu3 flag:", p.mk4mmu3)
print("unconfirmed placeholders:", allun or "none")
