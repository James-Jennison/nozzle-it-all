# Slicer feature survey for the native Desktop workspace

Research date: **2026-09-26**. Purpose: decide what Nozzle It All for Desktop builds natively, beyond the four
items already planned (settings from engine metadata, per-object/part settings and modifiers, painting, detailed
G-code preview). Read with [COMPETITIVE_MATRIX.md](COMPETITIVE_MATRIX.md) (product-level comparison) and
[PRODUCT_FAMILY.md](PRODUCT_FAMILY.md) (layout and structure).

**Versions surveyed**
- OrcaSlicer 2.4.x: official wiki, plus the engine source at **824b216f**. That is the `main` branch and can be
  newer than 2.4.2.
- Bambu Studio 2.x.
- Snapmaker Orca: source tree at `cbf7bbb0b3` in `/mnt/faststorage/Snapmaker-Orca/OrcaSlicer`, and official docs.
- PrusaSlicer 2.9.6, plus 3.0.0-alpha12 where noted.
- SuperSlicer 2.7.6x. It has had only prereleases since 2024, so treat it as a legacy reference.
- UltiMaker Cura 5.11-5.13.
- Simplify3D 5.x.
- ideaMaker 5.x.
- Creality Print 6/7.

**What Desktop has today** (`desktop/src/main/kotlin/com/nozzleitall/desktop`)
- **Prepare:** add STL/3MF/OBJ; move, turn about Z and scale; duplicate; shelf arrange; one material slot per object;
  Draft/Standard/Fine, supports on/off and infill %; slice; a layer slider preview; export; send and start.
  Sources: `prepare/PrepareScreen.kt`, `PrepareState.kt`, `SliceEngine.kt`.
- **Elsewhere:** Printers, Projects (3MF library), Print & Monitor with live camera, Materials & Toolheads, Full
  Spectrum (view only), and the Advanced Workspace handoff (`workspace/AdvancedWorkspace.kt`).
- **Missing:** everything in this survey. Ctrl+1…7 navigation is the only keyboard support (`Main.kt`).
- **In progress:** uncommitted changes in the working tree start planned item 1. They add `nozzle-engine --schema`
  (`engine/native/bridge/native_cli.cpp`), `schemas/slicing/settings-schema.json` and `desktop/.../settings/`.
  The object request format described in §2 is unchanged by them.

**Evidence rules**
- Every competitor cell cites a source from §7.
- `?` means UNVERIFIED: no official source was found this pass.
- `p` means the feature exists only as a plugin or script.
- `y*` means the feature is in the Snapmaker Orca source tree (upstream Orca GUI code), but no Snapmaker doc
  confirms it.
- Engine claims cite files under the 824b216f tree.

---

## 1. Feature × slicer matrix

Columns: **Orca**, **BBS** (Bambu Studio), **SMO** (Snapmaker Orca), **Prusa**, **SS** (SuperSlicer), **Cura**,
**S3D** (Simplify3D), **iM** (ideaMaker), **CP** (Creality Print). The **§3** column links to the detail row.
Planned items 1-4 are not repeated here.

| Feature | §3 | Orca | BBS | SMO | Prusa | SS | Cura | S3D | iM | CP |
|---|---|---|---|---|---|---|---|---|---|---|
| Multi-plate | P1 | y O1 | y X4 | y X6 | y, up to 9 beds, K1 | n SS1 | ? C2 | ? S2 | ? I1 | ? |
| Arrange | P2 | y O2 | y X4 | y* | y K2 | y SS1 | y C1 | y S1 | y I3 | ? |
| Auto-orient | P3 | y O3 | y X4 | y* | ?, place-on-face only, K3 | ? | p C9 | ? | y I1 | y (7.3 pre) CP1 |
| Split to objects/parts | P4 | y O4 | ? | y* | y K4 | y SS1 | p C5 | ? | y I3 | ? |
| Assemblies / assembly view | P5 | y O5 | y B1 | y* | n K4 | n | ? | ? | y I3 | ? |
| Negative volumes | P6 | y E1 | y B2 | y* | y K5 | y SS1 | y (cutting mesh) C4 | ? | ? | ? |
| Text emboss | P7 | y O6 | y B3 | y* | y K6 | y SS2 | ? | ? | y I2 | ? |
| SVG emboss | P8 | y O7, E2 | ? | y* | y K7 | y SS2 | ? | ? | ? | ? |
| Measure | P9 | y O1 | y B4 | y* | y K8 | y SS2 | p C6 | ? | y I2 | ? |
| Cut (with connectors) | P10 | y O8 | y B5 | y* | y K9 | y SS2 | ? | ? | y I7 | y CP1 |
| Mesh boolean | P11 | y O9 | y B6 | y* | n | n | n C4 | ? | y I1 | ? |
| Mesh repair | P12 | y O10 (Windows), E3 | y X5 | y* (Windows) | ? | y SS1 | p C5 | y S2 | y I5 | ? |
| Simplify | P13 | y O10 | ? | y* | y K10 | y SS2 | ? | ? | ? | ? |
| STEP import | P14 | y O7 | y X4 | y* | ? | ? | ? | ? | ? | ? |
| Colour/texture to paint | P15 | y (main) E4 | y X4 | ? | ? | ? | ? | ? | ? | y (7.3 pre) CP1 |
| Height-range modifiers | L1 | y E5 | y B2 | y* | y K11 | y SS1 | p C3 | y S4 | y I8 | ? |
| Variable / adaptive layer height | L2 | y O11 | y B7 | y* | y K12 | y SS1 | adaptive C10 | adaptive S2 | adaptive I1 | ? |
| Per-layer pause / colour change | L3 | y E6 | y X4 | y* | ? | ? | p C3 | ? | ? | ? |
| Tree / organic supports | S1 | y O12 | y B8 | y* | y K13 | y SS1 | y C4 | ? | y I3 | ? |
| Support painting | (item 3) | y O13 | y B9 | y* | y K14 | y SS2 | n (5.14 alpha) C2 | manual S3 | y I1 | ? |
| Support blockers / enforcers | S2 | y O13 | y B2 | y* | y K15 | y SS1 | y C4 | manual S2 | ? | ? |
| Fuzzy skin painting | F1 | y O14 | y B10 | y* | y (2.9) K16 | n SS2 | n (setting only) C10 | ? | ? | y CP2 |
| Seam painting | (item 3) | y O15 | y B11 | y* | y K12 | y SS1 | y C1 | n S2 | y I3 | ? |
| Scarf seam | F2 | y E7 | ? | y* | y X9 | ? | ? | ? | ? | ? |
| Ironing | F3 | y O16 | y B12 | y* | y K17 | y SS3 | y C10 | y S3 | y I1 | ? |
| Brim ears / painted brim | F4 | y O17 | y B13 | y* | n K18 | y SS4 | p C9 | ? | ? | ? |
| Flush volume matrix | M1 | y O18 | y (auto) B14 | y* | y K19 | y SS1 | ? | ? | ? | y CP1 |
| Prime / wipe tower | M2 | y O19 | y B15 | y SM1 | y K20 | y SS5 | y C1 | y S5 | y I3 | ? |
| Colour painting | (item 3) | y O20 | y B16 | y X6 | y K21 | y SS2 | y C1 | n S2 | y I2 | y CP3 |
| Filament grouping (dual nozzle) | M4 | y O21 | y B17 | ? | n | n | ? | ? | ? | ? |
| Mixed-colour filament | M5 | engine only E8 | ? | y SM2 | y (ColorMix) X8 | ? | ? | ? | ? | gradient (7.3 pre) CP1 |
| Sequential printing | Q1 | y O22 | y B18 | y* | y K22 | y SS6 | y C3 | y S5 | y I1 | ? |
| Calib: flow | C1 | y O23 | y B19 | y* | 3.0 alpha K23 | y SS7 | p C7 | ? | ? | y CP4 |
| Calib: pressure advance | C2 | y O23 | y B20 | y* | n | y SS7 | ? | ? | y I3 | y CP4 |
| Calib: temperature tower | C3 | y O23 | y X4 | y* | 3.0 alpha K23 | y SS7 | p C7 | ? | ? | y CP4 |
| Calib: retraction | C4 | y O23 | y X4 | y* | n | y SS7 | p C7 | ? | ? | ? |
| Calib: max volumetric speed | C5 | y O23 | y X4 | y* | n | n SS7 | ? | ? | ? | ? |
| Calib: VFA | C6 | y O23 | y X4 | y* | n | n SS7 | ? | ? | ? | ? |
| Preset inheritance | R1 | y O24 | ? | y* | y K24 | y SS1 | ? | ? | ? | ? |
| Compare presets | R2 | y E9 | ? | y* | y K25 | y SS7 | ? | y S2 | ? | ? |
| Import / export bundles | R3 | y O7 | filament only B21 | ? | y K24 | y SS7 | ? | y S1 | y I8 | ? |
| Unsaved-changes transfer | R4 | y O31 | ? | y* | ? | ? | ? | ? | ? | ? |
| Undo with history list | H1 | y E10 | ? | y* | y K26 | y SS7 | ? | no list S1 | y I1 | ? |
| Custom G-code placeholders | G1 | y O25 | y B22 | y* | y K27 | y SS8 | ? | y S1 | y I8 | ? |
| Post-processing scripts | G2 | y O26 | ? | y SM3 | y K28 | y SS1 | y C3 | ? | ? | ? |
| Timelapse G-code | G3 | y (doc "WIP") O22 | y B23 | y SM4 | n | n | p C3 | ? | y I6 | ? |
| Object labels (exclude object) | G4 | y E11 | ? | y* | ? | ? | ? | ? | ? | ? |
| Standalone G-code viewer | G5 | ? | ? | ? | y K29 | y SS1 | ? | ? | ? | ? |
| Send and monitor | (exists) | y O27 | y X4 | y SM5 | y K30 | send only SS1 | p C8 | y S3 | y I1 | y CP5 |
| Multi-printer send / queue | X1 | Bambu devices only E12 | y B24 | y* (Bambu-device code) | y via Connect K1 | n | ? | ? | y (RaiseCloud) I6 | y CP6 |
| Model repository | O1 | publish only O28 | y (MakerWorld) B25 | n | y (Printables) K31 | n | ? | n S2 | cloud library I6 | y (Creality Cloud) CP7 |
| Plugins / marketplace | O2 | nightly only O29 | ? | n | 3.0 alpha Lua K23 | n | y C9 | n S2 | n I8 | ? |
| Command palette / shortcuts | O3 | y (Speed Dial) O30, O32, E13 | ? | y* | y (search + keys) K26 | y SS7 | ? | y (editable) S2 | y I7 | ? |
| Accessibility beyond localisation | O4 | ? | "enhancements" B26 | ? | screen reader listed as to-do K23 | ? | ? | dark mode only S2 | ? | ? |

---

## 2. What the engine at 824b216f already gives Nozzle

The upstream split is:
- **libslic3r** holds the slicing and geometry logic: arrange, orient, cut, boolean, emboss, measure, simplify,
  adaptive layers, paint facets, calibration G-code and flush calculation.
- **The GUI layer** (`src/slic3r`) holds the orchestration: plates, undo, calibration setup, post-processing,
  preset bundle export, multi-send and plugins.

Nozzle links only libslic3r, built with `SLIC3R_GUI=OFF` and `SLIC3R_CAD=OFF`
(`/mnt/faststorage/build-work/nozzle-native/dist/PROVENANCE.txt`). So "E" rows need UI only, and "G" rows need
Nozzle's own implementation (usually small, in Kotlin).

The main gap is not the engine but the **request format**. Desktop's `nozzle-engine` takes only objects with an
XY/Z-rotation/scale transform, a tool index and key overrides (`engine/native/bridge/native_cli.cpp`). None of the
per-object data libslic3r supports can reach it yet: volumes, paint, layer ranges, layer-height profiles and per-Z
G-code. §3.0 treats this as the first enabler.

## 3. Feature detail

Columns: **Engine** = what libslic3r at 824b216f already does (E = in libslic3r, UI only; E+B = in libslic3r but the
Nozzle bridge/request format must carry it; G = logic lives in upstream's GUI layer `src/slic3r`, Nozzle must
reimplement it; N = not in the engine). **Effort** for Nozzle Desktop: S = up to about 1 week, M = 1-3 weeks,
L = more than 3 weeks (one developer, including tests). **Rec.** = Must / Should / Could / Skip.

Source-path shorthand: `lib/` = `/mnt/faststorage/orcaslicer-android-engine/orcaslicer/src/libslic3r/`,
`gui/` = `.../orcaslicer/src/slic3r/GUI/`, `utils/` = `.../orcaslicer/src/slic3r/Utils/`, `PC` = `lib/PrintConfig.cpp`.

### 3.0 Enabler (not a user feature, but most rows below depend on it)

| Item | What it is | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|
| Full-project slice request | Let `nozzle-engine` slice a whole project: objects with several volumes, volume types, per-object/volume config, layer ranges, layer-height profile, paint facets, per-Z custom G-code, one plate at a time | E+B | M | Must | Today the request carries only `object <path> x y rotZ scale tool` plus `set` overrides (`engine/native/bridge/native_cli.cpp` header); paint strokes and box volumes exist only on the Android JNI path (`app/src/main/cpp/bridge/slic3r_engine.hpp`, `ObjectExtras`). Every libslic3r data item listed is already read from 3MF by `lib/Format/bbs_3mf.cpp` and `lib/Format/3mf.cpp`, so the cheapest route is: write an Orca-compatible 3MF from `project-format` and have the bridge load it with `LoadStrategy::LoadModel|LoadConfig` for the chosen plate. `project-format` today stores one mesh per object and no per-object config (`project-format/src/main/kotlin/com/nozzleitall/project/ThreeMf.kt`, `ProjectManifest.kt`), so it needs volumes and config too. The first four planned items need this as well. |
| Desktop uses `:domain` | Link the plain-JVM `:domain` module into Desktop | n/a | S | Must | `domain/` already has auto-orient and lay-flat (`MeshEdit.kt`), arrange and collision footprints (`ProjectArrange.kt`), paint/volume codecs (`ObjectExtras.kt`), calibration specs (`Calibration.kt`), multi-material compatibility (`MultiMaterial.kt`), MyMiniFactory client, Spoolman, timelapse listing. `desktop/build.gradle.kts` does not depend on it (grep, 2026-09-26). |

### 3.1 Plate and object handling

| # | Feature | What it does | Engine (824b216f) | Effort | Rec. | Reason for Nozzle's users |
|---|---|---|---|---|---|---|
| P1 | Multi-plate | Several beds in one project, sliced one at a time or all together | G: plates are a GUI concept (`gui/PartPlate.cpp`); libslic3r stores per-plate data only for 3MF I/O (`PlateData` in `lib/Format/bbs_3mf.hpp`). Manifest already has `PlateEntry` | M | Must | Android already has plates (COMPETITIVE_MATRIX §2), and a project must keep its plates when it moves between platforms. |
| P2 | Arrange (outline-aware) | Packs objects by their real outline, with spacing and rotation, over one or all plates | E: `lib/ModelArrange.cpp` `arrange_objects`; `lib/Arrange.cpp` | S | Must | Desktop's shelf packing uses bounding boxes (`PrepareState.arrange`); the engine version fits more parts per plate. |
| P3 | Auto-orient / lay on face | Picks the orientation that needs the least support; puts a chosen face down | E: `lib/Orient.cpp` `orientation::orient`, `lib/LayOnFace.cpp`; Kotlin version in `domain/.../MeshEdit.kt` | S | Must | Downloaded models are often exported in a bad orientation; this is table stakes and already exists in `:domain`. |
| P4 | Split to objects / parts | Breaks a mesh into its disconnected shells | E: `ModelObject::split`, `ModelVolume::split` (`lib/Model.hpp`:521, 950) | S | Should | Multi-part plates from repositories often arrive as one STL. |
| P5 | Multi-part objects and assemblies | One object made of several parts with their own settings and materials; assembly view shows how parts fit | E for parts (`ModelObject::volumes`); G for assembly view (`gui/Gizmos/GLGizmoAssembly.cpp`) | M | Should | Colour 3MFs for the U1 are multi-part; without parts Nozzle can't open them faithfully. Assembly view itself is Could. |
| P6 | Negative volumes | Subtracts a shape at slice time (holes, recesses) | E: `ModelVolumeType::NEGATIVE_VOLUME` (`lib/Model.hpp`:343); bridge accepts only modifier/blocker/enforcer (`slic3r_engine.cpp`:244-247) | S | Should | Cheap once volumes exist; covers the common "add a hole for a magnet" edit without a boolean. |
| P7 | Text emboss | Adds raised or engraved text to a model or plate | E: `lib/Emboss.cpp`, `lib/TextConfiguration.hpp`; font discovery is GUI (`utils/EmbossStyleManager.cpp`, `utils/FontConfigHelp.cpp`) | M | Should | Labels, name tags and version marks are common owner edits; fonts can come from the three bundled OFL families. |
| P8 | SVG emboss | Turns an SVG into a raised/engraved part | E: `lib/Format/svg.cpp`, `lib/NSVGUtils.cpp`, `lib/EmbossShape.hpp` | S (after P7) | Could | Useful for logos; shares P7's UI. |
| P9 | Measure | Distances, angles and diameters between features; optional scale from a measurement | E: `lib/Measure.cpp` (`Measuring`) | M | Should | "Will it fit?" checks before printing a replacement part; Android has point-to-point measure already. |
| P10 | Cut (any plane, connectors) | Cuts along any plane, optionally with plugs/dowels/dovetails | E: `lib/CutUtils.cpp`; bridge exposes horizontal cut only (`cut_mesh_soup`) | M (L with connectors) | Should | Splitting parts that exceed the bed or need a flat face; connectors can come later. |
| P11 | Mesh boolean | Union / difference / intersection of parts into a new mesh | E: `lib/MeshBoolean.cpp` (CGAL; CGAL is in the native build per `PROVENANCE.txt`) | M | Could | Negative volumes (P6) cover most needs; true booleans are niche for print owners. |
| P12 | Mesh repair with report | Fixes open edges, flipped normals, degenerate faces, and says what it did | E for basic repair on load (`lib/TriangleMesh.hpp`:44 `repaired()` stats, `its_merge_vertices`, `its_remove_degenerate_faces`); G for Orca's CGAL "Fix model" (`utils/FixModelByCgal.cpp`; the 2.4 wiki still says repair is Windows-only, O10) | S (report) / M (CGAL fix) | Must | COMPETITIVE_MATRIX #1: downloaded models are often broken, and Nozzle's honesty rule means telling the user what changed. |
| P13 | Simplify | Reduces triangle count | E: `lib/QuadricEdgeCollapse.cpp` | S | Could | Helps huge scans slice and render faster; rare for most users. |
| P14 | STEP import | Opens CAD STEP files | E: `lib/Format/STEP.cpp`; OCCT STEP symbols are present in the built `nozzle-engine` (strings check 2026-09-26). End-to-end load via the `object` line UNVERIFIED | S (if it loads) | Should | Owners who design their own parts export STEP; Orca/BBS open it. |
| P15 | Textured/coloured model to paint | Converts OBJ vertex colours or textures into colour paint | E: `lib/TexturePainting.cpp` (`texture_to_painting`, `face_colors_to_painting`), `lib/ObjColorUtils.cpp` | M | Should | Coloured models from repositories map straight onto U1 toolheads; builds on planned item 3. |
| P16 | Primitive shapes | Adds a box, cylinder, etc. as part or modifier | G: `gui/Gizmos/GLGizmoPrimitive.cpp` (meshes are trivial) | S | Could | Needed as modifier shapes anyway (planned item 2); as printable parts it is minor. |
| P17 | Parametric sketch/CAD | Sketches and extrusions inside the slicer | G + `lib/CAD/*` behind `SLIC3R_CAD`; Nozzle builds with `SLIC3R_CAD=OFF` (`PROVENANCE.txt`) | L | Skip | Not a slicer owner's core job; release status in Orca UNVERIFIED (present on main at 824b216f). |

### 3.2 Per-object and per-layer controls

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| L1 | Height-range modifiers | Different settings for a Z range of one object | E+B: `ModelObject::layer_config_ranges` (`lib/Model.hpp`:372), read from 3MF | M | Should | Common fix for weak tops or tall thin sections; adjacent to planned item 2, so schedule it with or right after it. |
| L2 | Variable layer height | Adaptive (by curvature), manual brush and smoothing of layer heights along Z | E+B: `layer_height_profile_adaptive`, `smooth_height_profile`, `adjust_layer_height_profile` (`lib/Slicing.hpp`:157-192); `ModelObject::layer_height_profile` | M-L | Should | Better curved surfaces at the same time; all desktop competitors have it. Adaptive-only first is S-M. |
| L3 | Per-layer G-code: pause, colour change, custom | Inserts a pause, a filament change or custom G-code at a layer | E+B: `lib/CustomGCode.hpp` (ColorChange, PausePrint, Template); processed as move types in `lib/GCode/GCodeProcessor.hpp`:31-45 | S (with planned preview) | Must | Pause for magnets/nuts and single-tool colour swaps are everyday owner tasks; best placed on the preview's layer slider. |

### 3.3 Supports

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| S1 | Tree / organic supports | Branching supports that touch less of the model | E: `support_type` `tree(auto)`/`tree(manual)`, `support_style` `organic`, `tree_slim`, `tree_strong`, `tree_hybrid` (`PC`:362-386); `lib/Support/TreeSupport3D.cpp` | S | Must | The single biggest quality lever for figurines and repository models; a guided choice, not just a key. |
| S2 | Support blockers/enforcers as shapes | Placeable volumes that forbid or force supports | E: `SUPPORT_BLOCKER`, `SUPPORT_ENFORCER`; bridge has boxes on Android | S (inside planned item 2) | Must | Already on Android; comes with planned item 2's modifier UI. |
| S3 | Overhang highlight before slicing | Shades faces that will need support | G (Orca draws it in the 3D scene); maths is trivial from face normals | S | Should | Lets users decide on supports and orientation before slicing, fits the guided flow. |

### 3.4 Surface

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| F1 | Fuzzy skin (+ painting) | Rough, textured outer walls, globally or only where painted | E: `fuzzy_skin` (`PC`:3910), noise types classic/perlin/billow/ridgedmulti/voronoi/ripple (`PC`:224-229); `ModelVolume::fuzzy_skin_facets` (`lib/Model.hpp`:888) used in `lib/MultiMaterialSegmentation.cpp`:2226 | S (after planned item 3) | Could | Cosmetic; painting reuses item 3's brush with one more channel. |
| F2 | Scarf seams | Sloped seam that hides the Z seam | E: `seam_slope_type`, `scarf_*` (`PC`:6168) | S | Should | Visible quality win with one guided toggle. |
| F3 | Ironing | Smooths top surfaces with a second low-flow pass | E: `ironing_type` none/top/topmost/solid (`PC`:287-290, 4794) | S | Should | Simple "smooth top" choice for signs and lids. |
| F4 | Brim ears / painted brim | Small discs only at sharp corners, auto or placed by hand | E: `brim_type` `brim_ears`, `painted` (`PC`:1857), `brim_ears_*`; `ModelObject::brim_points` (`lib/Model.hpp`:390); placement UI G (`gui/Gizmos/GLGizmoBrimEars.cpp`) | S (auto) / M (placed) | Should | Stops corner lift on ABS/ASA/PETG with less cleanup than a full brim. |

### 3.5 Multi-material

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| M1 | Flush volume matrix | Purge amount per colour pair, auto-calculated from colours | E: `flush_volumes_matrix` (`PC`:7580), `lib/FlushVolCalc.cpp`, `lib/FlushVolPredictor.cpp` | S-M | Should | Matters for single-nozzle changers (Bambu AMS, Prusa MMU3); the U1 is a toolchanger, so hide it there by capability. |
| M2 | Prime/wipe tower placement and options | Where the tower goes, its width and brim, preview of its footprint | E: `enable_prime_tower` (`PC`:7558), `wipe_tower_type` (`PC`:6652), `prime_tower_brim_width`; `lib/GCode/WipeTower2.cpp` | S | Should | Tower collisions and wasted plate area are common multi-material complaints. |
| M3 | Flush into infill/objects/support | Uses purge material inside the print instead of the tower | E: `flush_into_infill`/`_objects`/`_support` (`PC`:7782-7794) | S | Could | Saves material on single-nozzle changers only. |
| M4 | Filament grouping / nozzle mapping | Assigns filaments to nozzles on dual-nozzle machines, auto for least flush or best match | E: `filament_map_mode` Auto For Flush / Auto For Match / Manual / Nozzle Manual (`PC`:634-637), `lib/FilamentGroup.cpp` | M | Could | Only Bambu H2D-class printers need it; add when an adapter for one is verified. |
| M5 | Mixed filament (Full Spectrum) settings | Mix two toolheads' filaments into new apparent colours, with gradients | E: `filament_is_mixed`, `filament_mixed_*`, `enable_mixed_color_sublayer` (`PC`), `lib/FilamentMixer.cpp` | M | Should | Desktop's Full Spectrum screen already shows possible mixes but sends users to the Advanced Workspace to apply them (`desktop/.../screens/FullSpectrumScreen.kt` doc comment). Primary-printer feature. |
| M6 | Interlocking between materials | Interlocking beams where two materials meet | E: `interlocking_beam*`, `mmu_segmented_region_interlocking_depth` (`PC`) | S | Could | Strength for multi-material functional parts; a single toggle. |

### 3.6 Sequential printing

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| Q1 | Print by object with clearance check | Prints objects one after another, checking the toolhead won't hit finished ones | E: `print_sequence` by layer / by object (`PC`:331-332, 2003), `Print::sequential_print_clearance_valid` (`lib/Print.cpp`:651), `extruder_clearance_*`; visual clearance zones are G | S-M | Should | Small batches finish some parts even if one fails; needs the printer's clearance values from the profile. |

### 3.7 Calibration suite

The engine side is `CalibMode` (`lib/calib.hpp`:16-30: PA line/pattern/tower, auto PA line, flow rate, temp tower,
volumetric speed tower, VFA tower, retraction tower, input shaping freq/damp, cornering) and per-layer G-code changes
in `lib/GCode.cpp` (e.g. :5693-5723). Test models are in `resources/calib/` and the setup (loading the model,
setting overrides and `Print::set_calib_params`) is in the GUI (`gui/Plater.cpp`:15809-16698). So Nozzle needs:
a bridge call for `Calib_Params` (E+B) and its own port of the Plater setup (G). `:domain` has a smaller
post-processing approach for three tests (`domain/.../Calibration.kt`).

| # | Test | What it measures | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| C0 | Calibration framework | Pick printer + material → print test → enter result → saved into the material profile | E+B + G | M | Must | COMPETITIVE_MATRIX #20: Orca copies results by hand; ending in a saved profile is Nozzle's differentiator. |
| C1 | Flow rate | Extrusion multiplier (coarse then fine) | E+B+G (`Calib_Flow_Rate`, `calib_flowrate`) | S | Must | Most common tuning step for new filament. |
| C2 | Pressure advance (line, pattern, tower) | Klipper PA / Marlin linear advance | E+B+G (`Calib_PA_*`, `lib/calib.hpp` `CalibPressureAdvanceLine/Pattern`) | S | Must | U1/PAXX is Klipper; PA is the second most important tuning value. |
| C3 | Temperature tower | Best nozzle temperature | E+B+G (`Calib_Temp_Tower`) | S | Must | Already on Android; every new filament brand needs it. |
| C4 | Retraction | Retraction length for stringing | E+B+G (`Calib_Retraction_tower`) | S | Should | Stringing is a top complaint. |
| C5 | Max volumetric speed | Highest flow before under-extrusion | E+B+G (`Calib_Vol_speed_Tower`) | S | Should | Needed to use a fast printer's speed safely with a new filament. |
| C6 | VFA | Vertical fine artefacts vs speed | E+B+G (`Calib_VFA_Tower`) | S | Could | Niche diagnostics. |
| C7 | Input shaping, cornering | Resonance frequency/damping, jerk/cornering | E+B+G (`Calib_Input_shaping_*`, `Calib_Cornering`) | S | Could | PAXX/Klipper users often use accelerometer-based shaping instead. |

### 3.8 Profiles

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| R1 | User presets inheriting system presets | Save only the changed keys on top of a vendor preset; updates flow through | E: `inherits` resolution in `lib/PresetBundle.hpp`:573-637, `lib/Preset.cpp`; Desktop today uses flattened profiles (`prepare/ProfileCatalog.kt`) | M | Must | Owners tune per printer and per spool; flattened copies go stale when vendor profiles update. |
| R2 | Compare presets | Side-by-side diff of two presets, all keys | G: `gui/UnsavedChangesDialog.cpp` (`DiffPresetDialog`, `FullCompareDialog`) | S | Should | Cheap once settings metadata (planned item 1) exists; answers "why does this print differently". |
| R3 | Import/export preset bundles | Move presets between machines and from other slicers | G: `gui/ExportPresetBundleDialog.cpp`; libslic3r bundle export is commented out (`lib/PresetBundle.hpp`:535-537) | M | Should | On-ramp for Orca/Prusa users bringing their tuned profiles; local-first backup. |
| R4 | Unsaved-changes handling | Keep/discard/save changes when switching preset | G: `gui/UnsavedChangesDialog.cpp` | S | Must | Prevents silent loss of tuning; pairs with R1. |

### 3.9 Project history and undo

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| H1 | Undo/redo with visible history | Step back through edits, including paint and settings | G: `utils/UndoRedo.cpp` | M | Must | Painting and modifiers (planned items 2-3) are unusable without undo. Compose state snapshots of the project model make this straightforward if designed in early. |
| H2 | Autosave and crash recovery | Restores the open project after a crash | G: `gui/ProjectDirtyStateManager.cpp` plus Orca's backup | S | Must | Local-first means there is no cloud copy to fall back on. |
| H3 | Project timeline / named versions | Keeps earlier revisions of a project with what was printed from each | N (manifest already has `revision`, `ProjectManifest.kt`) | M | Should | Lets an owner go back to "the version that printed well"; nobody surveyed offers it (see §4). |

### 3.10 G-code (excluding the planned detailed preview)

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| G1 | Custom G-code editor with placeholder help | Edit start/end/layer/toolchange G-code with a list of valid placeholders and a check | E: `lib/PlaceholderParser.cpp`; keys `machine_start_gcode`, `layer_change_gcode`, `change_filament_gcode`, `time_lapse_gcode`, `template_custom_gcode` (`PC`:4926-7415) | M | Should | Owners customise start G-code (e.g. PAXX macros); validation before print avoids a failed start. |
| G2 | Post-processing scripts | Runs user scripts on the G-code after slicing | G: `gui/PostProcessor.hpp` (not in libslic3r); option `post_process` (`PC`:5621) | S | Should | Every desktop slicer has it; run only with explicit opt-in per script and show the command (COMPETITIVE_MATRIX #30). |
| G3 | Timelapse G-code | Parks the head for a frame each layer (smooth) or snaps in place (traditional) | E: `timelapse_type` (`PC`:6551, values `PC`:472-473), `lib/GCode/TimelapsePosPicker.cpp` | S | Should | Printer cameras are already live in Desktop; offer only where the printer's capability says it records. |
| G4 | Object labels for mid-print exclusion | Labels objects so Klipper/Bambu can skip a failed one | E: `gcode_label_objects`, `exclude_object` (`PC`:4355, 4363) | S (slicer) + M (monitor UI) | Must | Saves the rest of a plate; the Monitor screen is where the payoff is. |
| G5 | Standalone G-code viewer | Opens any .gcode/.gcode.3mf without a project | E: `lib/GCode/GCodeProcessor.cpp` | S (after planned item 4) | Should | Owners get G-code from other slicers or the printer; reuses the planned preview. |
| G6 | G-code substitutions | Regex find/replace on output | N in Orca (no `gcode_substitutions` key in `PC`); Prusa only | S | Could | Post-processing (G2) covers it. |

### 3.11 Printing

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| X1 | Send to several printers / job queue | One sliced job to N printers, or a queue that feeds idle printers | N; Orca's multi-send works on Bambu device objects (`gui/SendMultiMachinePage.cpp`:33, 367 use `MachineObject`/`DeviceManager`) | L | Should | Nozzle's home is the fleet; a local queue with "bed cleared?" confirmation is a natural lead (COMPETITIVE_MATRIX #32). |
| X2 | Upload queue with progress | Background uploads that survive navigation | G: Orca `PrintHostQueue` (`gui/Shortcuts.hpp`), `gui/PrintHostDialogs.cpp` | S | Must | Large .gcode.3mf uploads to several printers need it. |
| X3 | Printer-aware slice checks | Warn before sending if the plate doesn't match the loaded filament, nozzle or bed | N (Nozzle domain logic; `MultiMaterial.kt` `MaterialCompatibility`) | S | Must | Nozzle's differentiator; already built on Android (WO-35). |

### 3.12 Other

| # | Feature | What it does | Engine | Effort | Rec. | Reason |
|---|---|---|---|---|---|---|
| O1 | Model repository browsing | Search and open models from an online library | N; Nozzle has an MMF client (`domain/.../MyMiniFactory.kt`) | M | Should | Bring Android's MyMiniFactory browsing to Desktop; keep attribution in the project (`Attribution` in the manifest). |
| O2 | Plugins / marketplace | Third-party code inside the app | G: Orca Python plugins on main (`src/slic3r/plugin/`, `PythonPluginInterface.hpp`) | L | Skip | COMPETITIVE_MATRIX #39 rejects it for security and support cost; post-processing (G2) covers the need. |
| O3 | Command palette and assignable shortcuts | Type any action or setting name; rebind keys | G: Orca 824b216f "assignable keyboard shortcuts" (`gui/Shortcuts.hpp`: contexts Global/Plater/Preview/ObjectList/Painting); Orca Speed Dial palette (O30) | M | Must | Makes the searchable settings (planned item 1) and every tool reachable without a sidebar; see §4. |
| O4 | Accessibility | Screen reader names, keyboard focus, contrast, scalable UI | N (GUI concern); Compose semantics already used in Desktop (`ui/Components.kt`) | M (ongoing) | Must | COMPETITIVE_MATRIX #22 makes it a release criterion; the 3D view needs text equivalents (object list, layer summary). |
| O5 | Localisation | Translated UI | N | M | Should | Strings are hard-coded today; set up the resource path before the new workspace adds hundreds more. |

## 4. Where Nozzle can be different, not a copy

All the surveyed desktop slicers use the same layout: a 3D plate in the middle, a tabbed settings sidebar, a toolbar
of gizmos, and (in Orca/BBS/SM-Orca) a separate Device tab. Sources: COMPETITIVE_MATRIX §5; upstream layout in
`gui/Plater.cpp` and `gui/Tab.cpp`. Nozzle's Desktop already departs from it (navigation rail, fleet home, guided
Prepare: `desktop/.../Main.kt`, `prepare/PrepareScreen.kt`). Ideas for the native workspace that keep that
difference:

1. **Intent-first tools instead of a gizmo toolbar.** Tools are named for the result: "Needs a hole", "Too big for
   the bed", "Add a label", "Stop corners lifting", "Pause here for magnets". Each opens the matching engine
   feature (negative volume, cut + arrange, emboss, brim ears, per-layer pause). This is the task-centred rule in
   COMPETITIVE_MATRIX §5 applied to geometry. SuperSlicer's "brim patch" and "seam cylinder" sub-parts show that
   placeable intent shapes work (SS1).
2. **Command palette as the main way in** (O3). One search field finds actions, settings (planned item 1), objects
   and printers. The sidebar becomes optional. Orca's Speed Dial (Space, with favourites on Alt+1-9, O30) is a
   palette added on top of its sidebar. In Nozzle the palette would be the main way in, and the same index serves
   screen readers.
3. **An inspector that follows the selection, not tabs.** Selecting an object, a painted region, a height range or
   a layer shows only what applies to it, plus a short "differs from the preset" list. Global Print, Filament and
   Printer tabs go away. The comparison (R2) is the same diff view.
4. **The Z axis as a first-class timeline.** One vertical strip beside the view carries height ranges (L1),
   variable layer height (L2), pauses and colour changes (L3), calibration bands (C0) and, after slicing, per-layer
   time. It is one control instead of the three separate places Orca uses (layer-range list, VLH dialog, preview
   slider). The Simplify3D Variable Settings Wizard's Z-plane "Add Location / Split Process" flow is the closest
   precedent (S4).
5. **Printer-state-aware plate.** The plate shows what the target printer reports: loaded filaments and colours per
   toolhead, nozzle size, bed, and whether a camera/timelapse is available. Tools that the printer can't use are
   absent (capability model, PRODUCT_FAMILY §8). Incumbents treat the printer as a profile dropdown.
6. **Calibration as a conversation that ends in a saved material.** Pick the spool (or Spoolman entry) → Nozzle
   prints the test on the chosen printer → the user taps the best band on a photo or picks a number → the value is
   written into that material profile with the test recorded as evidence. Orca and SuperSlicer stop at generating
   the test.
7. **Project timeline instead of undo alone** (H1 + H3). Edits, slices and prints appear on one timeline for the
   project ("sliced 14:02 · printed on U1 · succeeded"). Undo walks the timeline, and "print the version that
   worked" is one click. None of the surveyed slicers ties undo history to print outcomes.
8. **Queue as a fleet view, not a dialog** (X1). Jobs are cards that drop onto printers on the Printers screen.
   Each printer asks "bed cleared?" before the next job, following the confirm-once rule.
9. **Plain-language diff before sending.** Before upload, show the settings that differ from the preset and the
   printer-compatibility checks (X3) as one short list, e.g. "Supports: tree · Ironing on top · Pause at 12.4 mm".

## 5. Suggested build order after the first four

Effort totals assume one developer; S ≈ up to 1 week, M ≈ 1-3 weeks, L ≈ over 3 weeks.

| Step | Items | Why here | Effort |
|---|---|---|---|
| 0 (before or with the first four) | Full-project slice request; link `:domain`; undo/redo (H1); autosave (H2) | The first four need project-level engine input, and painting/modifiers need undo from day one | M + S + M + S |
| 1 | Auto-orient / lay flat (P3), engine arrange (P2), multi-plate (P1), mesh repair report (P12) | Parity with Android and every competitor; mostly existing code | S + S + M + S |
| 2 | Tree/organic supports (S1), ironing (F3), scarf seam (F2), brim ears auto (F4), object labels (G4) | Setting-only features exposed as guided choices; very cheap given planned item 1 | S each |
| 3 | Per-layer pause/colour change (L3) and height-range modifiers (L1) on the Z strip | Built on the planned preview and item 2; one shared control | S + M |
| 4 | Calibration framework + flow, PA, temperature (C0-C3) | Nozzle's clearest lead over incumbents, for the owners of the printers | M + 3×S |
| 5 | Presets: inheritance (R1), unsaved changes (R4), compare (R2), bundle import/export (R3) | Needed before users tune seriously; import is the on-ramp from Orca/Prusa | M + S + S + M |
| 6 | Multi-part objects (P5), split (P4), negative volumes (P6), STEP (P14), colour-to-paint (P15) | Faithful opening of repository and CAD models, especially multi-colour U1 files | M + S + S + S + M |
| 7 | Mixed filament (M5), prime tower (M2), flush matrix (M1, capability-gated) | Primary printer's Full Spectrum moves out of the Advanced Workspace | M + S + S-M |
| 8 | Upload queue (X2), printer-aware checks (X3), then multi-printer queue (X1) | Fleet strength; X1 is the biggest single item | S + S + L |
| 9 | Command palette + shortcuts (O3), accessibility pass (O4), localisation setup (O5) | Should run alongside from step 1; listed here as a checkpoint | M + M + M |
| 10 | Measure (P9), cut on any plane (P10), text/SVG emboss (P7/P8), VLH editor (L2), sequential (Q1), custom G-code editor (G1), post-processing (G2), timelapse (G3), standalone viewer (G5), retraction/MVS tests (C4/C5), project timeline (H3), MMF browsing (O1) | Should-level tools; order by user requests | mixed |
| Later | Boolean, simplify, fuzzy painting, primitives, VFA/input shaping, filament grouping, flush-into, interlocking | Could | — |
| Not planned | Plugins/marketplace (O2), parametric CAD (P17) | Skip | — |

## 6. Limits of this survey

- Engine support was checked by reading source at 824b216f, not by slicing each feature through `nozzle-engine`.
  "E" means the logic is in libslic3r. It does not mean Nozzle's request format can reach it yet (see §3.0).
- Competitor cells come from official docs and release notes found on 2026-09-26. Cells marked UNVERIFIED had no
  official source this pass. Some Prusa citations point to a related knowledge-base page (noted in the matrix).
- Effort estimates are rough. They assume planned items 1-4 and the §3.0 enabler exist.

---

## 7. Sources (accessed 2026-09-26)

**OrcaSlicer wiki** (prefix `https://www.orcaslicer.com/wiki/`, add `.html`)
- O1 `print_prepare/prepare_basic` (Add Plate, Measure)
- O2 `print_prepare/prepare_auto_arrange`
- O3 `print_prepare/prepare_auto_orient`
- O4 `print_prepare/prepare_object_set`
- O5 `print_prepare/prepare_assembly_tools`
- O6 `print_prepare/prepare_emboss`
- O7 `general_settings/import_export`
- O8 `print_prepare/prepare_cutting_tool`
- O9 `print_prepare/prepare_mesh_boolean`
- O10 `print_prepare/prepare_stl_transformation` (repair is Windows-only there)
- O11 `print_prepare/prepare_variable_layer_height`
- O12 `print_settings/support/support_settings_tree`
- O13 `print_prepare/prepare_support_painting`
- O14 `print_prepare/prepare_paint_on_fuzzy_skin`
- O15 `print_prepare/prepare_seam_painting`
- O16 `print_settings/quality/quality_settings_ironing`
- O17 `print_prepare/prepare_brim_ears_painting`
- O18 `print_settings/multimaterial/multimaterial_settings_flush_options`
- O19 `print_settings/multimaterial/multimaterial_settings_prime_tower`
- O20 `print_prepare/prepare_color_painting`
- O21 https://github.com/OrcaSlicer/OrcaSlicer/releases/tag/v2.3.2 (filament grouping)
- O22 `print_settings/others/others_settings_special_mode` (sequential; timelapse marked WIP)
- O23 `calibration/flow_ratio_calib`, `pressure_advance_calib`, `temp_calib`, `retraction_calib`,
  `volumetric_speed_calib`, `vfa_calib`
- O24 `user_profiles/user_profiles`
- O25 `developer_reference/built_in_placeholders_variables`
- O26 `print_settings/others/others_settings_post_processing_scripts`
- O27 `releases/release_2_4_0`
- O28 `publishing_3mf/publish_3mf`
- O29 `plugins/plugins_getting_started` (nightly builds after 2.4.2 only)
- O30 `speed_dial/speed_dial`
- O31 `general_settings/transfer_discard_changes`
- O32 `general_settings/keyboard_shortcuts`

**Engine and upstream source** (824b216f, `/mnt/faststorage/orcaslicer-android-engine/orcaslicer/src/`)
- E1 `libslic3r/Model.hpp`:343 `ModelVolumeType::NEGATIVE_VOLUME`
- E2 `slic3r/GUI/Gizmos/GLGizmoSVG.cpp`
- E3 `slic3r/Utils/FixModelByCgal.cpp` (a cross-platform fix on main)
- E4 `libslic3r/TexturePainting.cpp`
- E5 `libslic3r/Model.hpp`:372 `layer_config_ranges`, `slic3r/GUI/GUI_ObjectLayers.cpp`
- E6 `libslic3r/CustomGCode.hpp`
- E7 `libslic3r/PrintConfig.cpp` `seam_slope_type`
- E8 `libslic3r/FilamentMixer.cpp`, `filament_is_mixed` in `PrintConfig.cpp` (release UI status UNVERIFIED)
- E9 `slic3r/GUI/UnsavedChangesDialog.cpp` (`DiffPresetDialog`)
- E10 `slic3r/Utils/UndoRedo.cpp`
- E11 `PrintConfig.cpp`:4355 `gcode_label_objects`, :4363 `exclude_object`
- E12 `slic3r/GUI/SendMultiMachinePage.cpp`:33, 367 (uses Bambu `MachineObject`/`DeviceManager`)
- E13 `slic3r/GUI/Shortcuts.hpp` (assignable shortcuts)

**Bambu Studio**
- B-prefix pages: `https://wiki.bambulab.com/en/software/bambu-studio/`
  - B1 `assembly-view-guide`
  - B2 `modifier`
  - B3 `3d-text`
  - B4 `measurement_tool`
  - B5 `cut-tool`
  - B6 `mesh-boolean`
  - B7 `adaptive-layer-height`
  - B8 `support`
  - B9 `support-painting`
  - B10 `parameter/fuzzy-skin`
  - B11 `Seam`
  - B12 `parameter/ironing`
  - B13 `brim-ears`
  - B14 `reduce-wasting-during-filament-change`
  - B15 `parameter/prime-tower`
  - B16 `multi-color-printing`
  - B17 `manual/dual-nozzles-slicing-filament-grouping`
  - B18 `sequent-print`
  - B19 `calibration_flow_rate`
  - B20 `calibration_pa`
  - B22 `placeholder-list`
  - B23 `Timelapse`
  - B24 `multi-device-management`
- Other Bambu sources:
  - B21 https://wiki.bambulab.com/en/bambu-studio/export-filament
  - B25 https://wiki.bambulab.com/en/makerworld
  - B26 https://github.com/bambulab/BambuStudio/releases

**Snapmaker Orca**
- SM1 https://github.com/Snapmaker/OrcaSlicer/releases/tag/v2.3.3
- SM2 https://wiki.snapmaker.com/en/snapmaker_orca/snapmaker_orca_full_spectrum
- SM3 `/mnt/faststorage/Snapmaker-Orca/OrcaSlicer/src/libslic3r/GCode/PostProcessor.hpp`
- SM4 https://github.com/Snapmaker/OrcaSlicer/releases/tag/v2.3.6
- SM5 https://wiki.snapmaker.com/en/snapmaker_orca/qsg
- `y*` cells: `/mnt/faststorage/Snapmaker-Orca/OrcaSlicer/src/slic3r/GUI/Gizmos/`, `CalibrationWizard*.cpp`,
  `UnsavedChangesDialog.cpp`, `KBShortcutsDialog.cpp`, `SendMultiMachinePage.cpp`, `../Utils/UndoRedo.cpp`,
  `../Utils/FixModelByWin10.cpp`

**PrusaSlicer** (prefix `https://help.prusa3d.com/article/`)
- K1 `multiple-build-plates-on-prusaslicer_823894`
- K2 `auto-arrange-tool_1770`
- K3 `place-on-face-tool_1781`
- K4 `split-to-objects-parts_1751`
- K5 `negative-volume_238503`
- K6 `text-tool_399460`
- K7 `svg-embossing-tool_686167`
- K8 `measurement-tool_399451`
- K9 `cut-tool_1779`
- K10 `simplify-mesh_238941`
- K11 `modifiers_1767`
- K12 `variable-layer-height-function_1750` (also the weak seam-painting cite)
- K13 `organic-supports_480131`
- K14 `paint-on-supports_168584`
- K15 `modifier-meshes-custom-supports-and-other-magic_114258`
- K16 `fuzzy-skin_246186`
- K17 `ironing_177488`
- K18 `skirt-and-brim_133969`
- K19 `purging-volumes-mmu_125097`
- K20 `wipe-tower_125010`
- K21 `multi-material-painting_262620`
- K22 `sequential-printing_124589`
- K23 https://github.com/prusa3d/PrusaSlicer/releases/tag/version_3.0.0-alpha11 (flow/temp towers as Lua plugins,
  Lua API, accessibility to-do)
- K24 `how-to-import-and-export-custom-profiles-in-prusaslicer_382766`
- K25 `compare-presets_301482`
- K26 `keyboard-shortcuts_1764`
- K27 `macros_1775`
- K28 `post-processing-scripts_283913`
- K29 `prusaslicer-g-code-viewer_193152`
- K30 `sending-g-codes-to-printer-via-network-prusa-connect-prusalink-octoprint_196761`
- K31 `printables-in-prusaslicer_822448`

**SuperSlicer**
- SS1 https://github.com/supermerill/SuperSlicer/releases/tag/2.7.61.0
- SS2 https://github.com/supermerill/SuperSlicer/tree/master_27/src/slic3r/GUI/Gizmos
- SS3 https://github.com/supermerill/SuperSlicer/wiki/Ironing
- SS4 https://github.com/supermerill/SuperSlicer/blob/master_27/src/libslic3r/PrintConfig.cpp (brim ears)
- SS5 https://github.com/supermerill/SuperSlicer/releases
- SS6 the same PrintConfig.cpp (`complete_objects`)
- SS7 https://github.com/supermerill/SuperSlicer/blob/master_27/src/slic3r/GUI/MainFrame.cpp (Calibration menu,
  compare, bundles, undo, shortcuts)
- SS8 https://github.com/supermerill/SuperSlicer/wiki/Macro-&-Variable-list

**Cura**
- C1 https://github.com/Ultimaker/Cura/releases/tag/5.11.0
- C2 https://github.com/Ultimaker/Cura/releases
- C3 https://github.com/Ultimaker/Cura/tree/main/plugins/PostProcessingPlugin/scripts
- C4 https://support.ultimaker.com/s/article/1667417606331
- C5 https://marketplace.ultimaker.com/app/cura/plugins/fieldofview/MeshTools
- C6 https://marketplace.ultimaker.com/app/cura/plugins/fieldofview/MeasureTool
- C7 https://marketplace.ultimaker.com/app/cura/plugins/5axes/CalibrationShapes
- C8 https://marketplace.ultimaker.com/app/cura/plugins/fieldofview/OctoPrintPlugin
- C9 https://marketplace.ultimaker.com/app/cura/plugins
- C10 https://support.ultimaker.com/s/article/cura-release-notes

**Simplify3D**
- S1 https://www.simplify3d.com/products/simplify3d-software/release-notes/
- S2 https://www.simplify3d.com/products/simplify3d-software/whats-new/version-5-0/
- S3 https://www.simplify3d.com/simplify3d-version-5-1-provides-key-print-quality-advancements-and-expanded-machine-integrations/
- S4 https://www.simplify3d.com/resources/articles/different-settings-for-different-regions-of-a-model/
- S5 https://www.simplify3d.com/products/simplify3d-software/whats-new/version-4-1/

**ideaMaker**
- I1 https://www.raise3d.com/download/ideamaker-release-notes/
- I2 https://www.raise3d.com/news/ideamaker-5-3-0-beta-release-notes/
- I3 https://www.raise3d.com/news/ideamaker-5-4-1-beta-release-notes/
- I5 https://support.raise3d.com/ideaMaker/4-2-11-repair-15-1338.html
- I6 https://support.raise3d.com/ideaMaker/4-2-14-raisecloud-15-1341.html
- I7 https://support.raise3d.com/ideaMaker/4-1-2-4-shortcuts-15-816.html
- I8 https://www.raise3d.com/ideamaker/

**Creality Print**
- CP1 https://github.com/CrealityOfficial/CrealityPrint/releases (7.1.1 cut, 7.3.0 pre-release auto-orient, OBJ
  colour separation, gradients, skeleton purge)
- CP2 https://wiki.creality.com/en/software/6-0/release-notes-7-1-0
- CP3 https://wiki.creality.com/en/software/update-released/multi-color-printing
- CP4 https://wiki.creality.com/en/Software/creality-print/CalibrationTutorial
- CP5 https://wiki.creality.com/en/software/creality-print/LAN-printing
- CP6 https://wiki.creality.com/en/software/6-0/release-notes-6-2-0
- CP7 https://wiki.creality.com/en/creality-cloud

**COMPETITIVE_MATRIX.md sources reused**
- X4 is its source [4], the Bambu Studio repository and 2.8.2 release notes. It is used for multi-plate,
  arrange/orient, STEP, colour decomposition, pause display, the calibration set and the Device tab.
- X5 is its source [5], the Bambu wiki's "fix model" page.
- X6 is its source [6], Snapmaker Orca v2.4.0.
- X8 is its source [8], PrusaSlicer 2.9.6 (ColorMix).
- X9 is its source [9], the PrusaSlicer 2.9 announcement (scarf seams).
