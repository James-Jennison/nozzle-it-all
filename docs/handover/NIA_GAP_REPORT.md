# NIA gap check against Orca 2.5.0 (2026-10-17)

Method: Orca's option list is every config key that its Tab pages (`Tab.cpp`: Print, Filament, Printer, Printer-extruder, Object/Model, Plate) put on screen, matched against the 992 keys `PrintConfig.cpp` defines. That gave 601 on-screen options. NIA Desktop was compared through `schemas/slicing/settings-schema.json` and `settings-groups.json`; NIA Android through `SettingsCatalog.kt`. Menus were compared from `MainFrame.cpp` and `AppMenus.kt`. Counts are from scripts, not by eye. Nothing was run in the apps: this is a code and data check, not a UI walkthrough.

## 1. Root cause on Desktop: the settings schema is stale
`schemas/slicing/settings-schema.json` has 568 options and was exported from the OLD engine (`1b744555`, 2026-10-01). It was never re-exported from the Orca 2.5.0 engine. The Desktop settings sheet is built from it, so every option 2.5.0 added or changed is missing.
- Orca shows 601 options; 454 of them are in the schema; **147 are missing**.
- 5 more are in the schema but hidden by `settings-groups.json`.
- Fix: run `nozzle-engine --schema` from `9cc8f78`, regenerate the groups so every new key is grouped or hidden (a test enforces that), and rerun `SettingsCatalogTest`.

## 2. Android has a 32-setting panel
`AdvancedSettingsPanel` shows `SettingsCatalog.kt`, a hand-picked list of 32 keys (31 of Orca's 601 appear on it). Desktop shows up to 454. There is no per-object settings page, no Setting Overrides page, no filament or printer settings editor, and no option-states rules (greyed/hidden rows, notices). This is the largest gap.

## 3. The 147 options missing from Desktop, by Orca page
**Print / Quality (24):** bridge_line_width elefant_foot_layers_density enable_mixed_color_sublayer first_layer_flow_ratio gap_fill_flow_ratio hole_to_polyhole_max_edges inner_wall_flow_ratio internal_solid_infill_flow_ratio ironing_angle_fixed outer_wall_flow_ratio overhang_flow_ratio set_other_flow_ratios sparse_infill_flow_ratio support_flow_ratio support_interface_flow_ratio unsupported_wall_last wall_maximum_deviation wall_maximum_resolution wipe_inward wipe_inward_distance zaa_dont_alternate_fill_direction zaa_enabled zaa_min_z zaa_minimize_perimeter_height

**Print / Strength (21):** bottom_color_penetration_layers top_color_penetration_layers bottom_layer_direction top_layer_direction bottom_surface_fill_order top_surface_fill_order center_of_surface_pattern gyroid_optimized infill_complete_top lightning_overhang_angle lightning_prune_angle lightning_straightening_angle relative_bridge_angle separated_infills sparse_infill_smooth_factor top_surface_expansion top_surface_expansion_direction top_surface_expansion_margin tpms_adaptive tpms_adaptive_gradient tpms_interior_density

**Print / Speed (4):** initial_layer_travel_acceleration initial_layer_travel_jerk small_support_perimeter_speed small_support_perimeter_threshold

**Print / Multimaterial (16):** belt_purge_tower_width bottom_surface_filament_id top_surface_filament_id inner_wall_filament_id outer_wall_filament_id internal_solid_filament_id sparse_infill_filament_id enable_tower_interface_cooldown_during_tower enable_tower_interface_features prime_tower_enable_framework prime_tower_infill_gap prime_tower_skip_points toolchange_cyclic_first_layer toolchange_cyclic_order toolchange_ordering wipe_tower_sparse_layers_combination

**Print / Others (12):** brim_ears_outer_only brim_flow_ratio brim_use_efc_outline combine_brims enable_wrapping_detection extra_brim_width fuzzy_skin_layers_between_ripple_offset fuzzy_skin_ripple_offset fuzzy_skin_ripples_per_layer leading_brim_length process_change_extrusion_role_gcode slicing_pipeline_plugin

**Filament (22):** filament_change_extrusion_role_gcode; activate_air_filtration_during_print activate_air_filtration_on_completion initial_layer_fan_speed; chamber_minimal_temperature filament_adaptive_volumetric_speed filament_adhesiveness_category filament_change_length; filament_tower_interface_pre_extrusion_dist filament_tower_interface_pre_extrusion_length filament_tower_interface_print_temp filament_tower_interface_purge_volume long_retractions_when_ec retraction_distances_when_ec; Setting Overrides: filament_ironing_flow filament_ironing_inset filament_ironing_spacing filament_ironing_speed

**Printer (32):** belt printer group (belt_frame_tilt_angle belt_frame_tilt_decouple belt_printer belt_printer_infinite_y belt_slice_rotation belt_slice_rotation_angle belt_support_floor_offset build_plate_tilt_x build_plate_tilt_y enable_belt_purge_tower), cooling_filter_enabled enable_power_loss_recovery extruder_clearance_dist_to_rod fan_direction gcode_skip_config_block parallel_printheads_count part_cooling_fan_min_pwm printer_agent use_3mf printer_extruder_variant; Machine G-code: file_start_gcode wrapping_detection_gcode; Motion ability: input_shaping_type input_shaping_emit input_shaping_freq_x/_y input_shaping_damp_x/_y machine_max_acceleration_ machine_max_jerk_ (the trailing-underscore names are Orca's per-axis families); IDEX/IQEX group: is_imex imex_gantry_count imex_tool_layout imex_tools_per_gantry imex_mode_active_tools imex_mode_gcodes imex_mode_names imex_carriage_margin imex_firmware_managed_zones imex_nozzle_clearance_x imex_nozzle_clearance_y imex_viz_theme; tool_change_on_wipe_tower wait_for_temp_on_wipe_tower bed_temperature_formula extruder_printable_area extruder_printable_height

**Plate settings (6):** curr_bed_type first_layer_print_sequence first_layer_sequence_choice other_layers_print_sequence other_layers_sequence_choice (the plate-level page itself is absent).

Some of these are printer-specific (belt, IDEX, Bambu); the schema export decides which ones the engine actually accepts, so re-export first and recount.

## 4. Menus
NIA Desktop has File (New, Open, Add model, Save, Save as, Export 3MF, Export sliced file, Quit), Edit (Duplicate, Delete, Arrange plate, Show every setting), Go (the six pages) and Help (four links and About).

Missing compared with Orca's MainFrame:
- **Edit:** Undo and Redo (paint tools have their own undo, nothing app-wide), Select all, Deselect all, Copy, Paste, Delete all, Fill bed, Clone.
- **File:** Recent files, Reload from disk, Import configs / profile bundle, Export preset bundle, Export all objects as STL, Export toolpaths as OBJ, Export plate sliced file / all sliced files, ZIP archive import, New window, Preferences.
- **View menu: none.** Orca's View has Iso, Top, Bottom, Front, Rear, Left, Right, Show labels, Show overhang, Show wireframe, Show G-code window, Show gridlines, Reset window layout.
- **Plate actions:** Slice plate, Slice all, Print plate, Print all, Send all, Add or delete plate, Duplicate current plate. None of these is in the Desktop menus. Both apps mention plates in code, so I cannot say from a search alone whether multi-plate projects exist; not verified.
- **Calibration menu** (Temperature, Flow rate, Pressure advance, Retraction, Max flowrate, VFA, Cornering): Android has a Calibration dialog; Desktop has no calibration code at all (no matches in `desktop/src/main`).
- **Help / tools:** Troubleshoot centre, Show configuration folder, Setup wizard, Check for updates, Tip of the day, G-code viewer (standalone).

## 5. Things I could not judge from code alone
- Whether Android's project editor offers a Setting Overrides page per filament and per object beyond the 32-key panel (search found none).
- Whether the Desktop settings sheet applies the 2.5.0 option-states rules to the new keys once the schema is refreshed (the rules client is in place: `OptionRules.kt`).
- Orca's Preferences dialog; NIA has its own Settings page and I did not compare the two item by item.

## Status after the first fixes (2026-10-17)
- **Done, Desktop settings:** schema re-exported from the 2.5.0 engine (762 options); 147 gaps became 7 (plate-level keys the engine does not take). New keys grouped; `SettingsCatalogTest` passes. Commit a5f56441.
- **Done, Desktop menus:** Plate menu (slice, add, delete, previous, next), View menu (isometric, top, side, rotate, centre), File > Reload from disk, Edit > Delete all, Help > Show the data folder. 4 tests. Commit 9667adea.
- **Done, Android settings:** the panel now lists the 392 process settings the engine accepts that take one value, beyond the curated 32 (Advanced tier for Orca's "simple" options, Expert for the rest), searchable, validated, and sent to the engine. Device tests on the Razr pass: the new settings are accepted by the real engine, and one can be searched and set.
- **Correction:** Desktop does have multi-plate (plateNames, currentPlate); the earlier line about plates being unverified is resolved.
- **Still open:** Undo/Redo and Select all (no app-wide history or multi-select yet); Recent files; Import/Export config bundles; Export objects as STL; Preferences; Calibration on Desktop; Android filament and printer settings editors, per-object settings and Setting Overrides on Android; the 6 plate-level options.

## Status, second pass (2026-10-17)
- **Done, Android filament and printer settings:** the Advanced settings panel has Print / Filament / Printer scopes; each lists the engine's single-valued settings for that scope (a per-filament or per-extruder list takes one value, repeated for each filament or extruder when slicing). Every one accepted by the real engine on the Razr (default value, 1 slice each). Commit 56d43391.
- **Done, Desktop calibration:** Calibration menu (temperature tower, pressure advance tower, flow cube) starts the model on the plate with the settings it needs; the sliced G-code gets the height-dependent step. The calibration is not saved with the project. Commit 078a45fa.
- **Done, Desktop undo and redo:** plates, objects, project overrides and selection; Edit > Undo / Redo (Ctrl+Z / Ctrl+Shift+Z, after the focused text field). Mixed filaments, printer choice and the slice are not part of it. 6 tests.
- **Done, Recent projects** in File (commit ce1fbff7).
- **Still open:** Select all / multi-select; Import/Export config bundles; Export all objects as STL; Preferences; the 6 plate-level options; Android per-object settings, Setting Overrides page and option-states rules (greyed rows); Android has no undo/redo.
