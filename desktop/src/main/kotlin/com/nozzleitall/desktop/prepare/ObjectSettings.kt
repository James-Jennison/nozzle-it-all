package com.nozzleitall.desktop.prepare

import com.nozzleitall.desktop.settings.Scope
import com.nozzleitall.desktop.settings.SettingDef
import com.nozzleitall.desktop.settings.SettingsCatalog

/**
 * Per-object print settings, as Orca's object list offers them. The menu is Snapmaker Orca's SettingsFactory
 * (src/slic3r/GUI/GUI_Factories.cpp at the engine pin, AGPL-3.0): for an object, each category's part settings
 * (PART_CATEGORY_SETTINGS) and object settings (OBJECT_CATEGORY_SETTINGS), ordered by their priority, as
 * get_visible_options(category, false) lists them. The engine accepts any object or region option on one object
 * (SettingsFactory::get_options(false)); those are the schema's perObject keys, and anything else is dropped on import.
 */
object ObjectSettings {
    private val part = linkedMapOf(
        "Quality" to listOf("ironing_type" to 8, "ironing_flow" to 9, "ironing_spacing" to 10, "ironing_inset" to 11, "bridge_flow" to 11,
            "make_overhang_printable" to 11, "bridge_density" to 1),
        "Strength" to listOf("wall_loops", "top_shell_layers", "top_shell_thickness", "top_surface_density", "bottom_shell_layers", "bottom_shell_thickness",
            "bottom_surface_density", "sparse_infill_density", "sparse_infill_pattern", "sparse_infill_filament", "lateral_lattice_angle_1", "lateral_lattice_angle_2",
            "infill_overhang_angle", "infill_anchor", "infill_anchor_max", "top_surface_pattern", "bottom_surface_pattern", "internal_solid_infill_pattern",
            "align_infill_direction_to_model", "extra_solid_infills", "infill_combination", "infill_combination_max_layer_height", "infill_wall_overlap",
            "top_bottom_infill_wall_overlap", "solid_infill_direction", "infill_direction", "bridge_angle", "internal_bridge_angle", "minimum_sparse_infill_area").map { it to 1 },
        "Speed" to listOf("outer_wall_speed" to 1, "inner_wall_speed" to 2, "sparse_infill_speed" to 3, "top_surface_speed" to 4, "internal_solid_infill_speed" to 5,
            "enable_overhang_speed" to 6, "overhang_1_4_speed" to 7, "overhang_2_4_speed" to 8, "overhang_3_4_speed" to 9, "overhang_4_4_speed" to 10,
            "bridge_speed" to 11, "gap_infill_speed" to 12, "internal_bridge_speed" to 13),
    )
    private val objectOnly = linkedMapOf(
        "Quality" to listOf("layer_height" to 1, "seam_position" to 2, "slice_closing_radius" to 3, "resolution" to 4, "xy_hole_compensation" to 5,
            "xy_contour_compensation" to 6, "elefant_foot_compensation" to 7, "make_overhang_printable_angle" to 8, "make_overhang_printable_hole_size" to 9,
            "wall_sequence" to 10, "precise_z_height" to 10),
        "Support" to listOf("brim_type" to 1, "brim_width" to 2, "brim_object_gap" to 3, "enable_support" to 4, "support_type" to 5, "support_threshold_angle" to 6,
            "support_threshold_overlap" to 6, "support_on_build_plate_only" to 7, "support_filament" to 8, "support_interface_filament" to 9, "support_expansion" to 24,
            "support_style" to 25, "tree_support_brim_width" to 26, "tree_support_branch_angle" to 10, "tree_support_branch_angle_organic" to 10,
            "tree_support_wall_count" to 11, "tree_support_branch_diameter_angle" to 11, "support_top_z_distance" to 13, "support_bottom_z_distance" to 12,
            "support_base_pattern" to 14, "support_base_pattern_spacing" to 15, "support_interface_top_layers" to 16, "support_interface_bottom_layers" to 17,
            "support_interface_spacing" to 18, "support_bottom_interface_spacing" to 19, "support_object_xy_distance" to 20, "bridge_no_support" to 21,
            "max_bridge_length" to 22, "support_critical_regions_only" to 23, "support_remove_small_overhang" to 27, "support_object_first_layer_gap" to 28),
        "Speed" to listOf("support_speed" to 12, "support_interface_speed" to 13),
    )

    /** The categories and their settings, in upstream's menu order (only the ones this engine has). */
    fun categories(catalog: SettingsCatalog = SettingsCatalog.bundled): List<Pair<String, List<SettingDef>>> {
        val defs = catalog.all.filter { it.scope == Scope.PROCESS && it.perObject }.associateBy { it.key }
        return (part.keys + objectOnly.keys).distinct().map { cat ->
            cat to (part[cat].orEmpty() + objectOnly[cat].orEmpty()).sortedBy { it.second }.mapNotNull { defs[it.first] }
        }.filter { it.second.isNotEmpty() }
    }

    /** Every setting the engine takes for one object, for search (the menu's categories cover the common ones). */
    fun all(catalog: SettingsCatalog = SettingsCatalog.bundled): List<SettingDef> = catalog.all.filter { it.scope == Scope.PROCESS && it.perObject && it.mode != "develop" && !it.readonly }

    /** An imported or saved object's settings, keeping only keys the engine accepts for one object. */
    fun usable(settings: Map<String, String>, catalog: SettingsCatalog = SettingsCatalog.bundled): Map<String, String> {
        if (settings.isEmpty()) return settings
        val keys = catalog.all.filter { it.scope == Scope.PROCESS && it.perObject }.map { it.key }.toSet()
        return settings.filterKeys { it in keys }
    }
}
