// Adapted from the owner's own orcaslicer-android-engine project (local, /mnt/faststorage/orcaslicer-android-engine),
// which cross-compiles upstream OrcaSlicer's libslic3r for Android. Licensed AGPL-3.0-or-later
// (same as OrcaSlicer itself, and the same family already governing this app since the Helix port).
// See THIRD_PARTY_NOTICES.md and docs/WORK_ORDER.md's WO-13 entry.
// The load -> configure -> process -> export sequence mirrors OrcaSlicer's
// own tests/fff_print/test_helpers.cpp (init_print / init_and_process_print
// / gcode()), the canonical reference for driving libslic3r headlessly.
#include "slic3r_engine.hpp"

#include <stdexcept>

#include "libslic3r/libslic3r.h"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Config.hpp"

#include "thumbnail_render.hpp"

namespace engine {

void slice_file(const std::string& input_model_path,
                 const std::string& output_gcode_path,
                 const std::vector<std::string>& profile_paths,
                 const std::vector<std::pair<std::string, std::string>>& config_overrides) {
    using namespace Slic3r;

    DynamicPrintConfig config = DynamicPrintConfig::full_print_config();

    for (const std::string& profile_path : profile_paths) {
        DynamicPrintConfig profile_config;
        profile_config.load(profile_path, ForwardCompatibilitySubstitutionRule::Enable);
        config.apply(profile_config);
    }

    for (const auto& [key, value] : config_overrides) {
        config.set_deserialize_strict(key, value);
    }

    Model model = Model::read_from_file(input_model_path, &config);
    if (model.objects.empty()) {
        throw std::runtime_error("No printable objects found in " + input_model_path);
    }
    for (ModelObject* object : model.objects) {
        if (object->instances.empty()) {
            object->add_instance();
        }
    }

    // Model::read_from_file() places each instance at the mesh's own local origin, not the
    // printer's actual bed coordinates. For a part whose local origin isn't already at its
    // footprint centroid (most STL exports aren't), that can put much of the object outside
    // printable_area - e.g. a part centered on (0,0) locally, sliced against a bed spanning
    // (0,0)-(256,256), ends up half off-bed. OrcaSlicer's own GUI always centers on import
    // (Plater::load_files() -> model.center_instances_around_point(bed center)); this headless
    // path skipped that step entirely. Caught live: a real print on the CC1 aborted right after
    // the purge line with Klipper's own "Move out of range" error, because roughly half of
    // squatchee_spin_mount's toolpath fell into negative Y.
    {
        Points bed_shape = Slic3r::get_bed_shape(config);
        if (!bed_shape.empty()) {
            BoundingBox bed_bbox(bed_shape);
            model.center_instances_around_point(unscale(bed_bbox.center()));
        }
    }

    for (ModelObject* object : model.objects) {
        object->ensure_on_bed();
    }

    // Captured now (world/bed coordinates, after centering) and before print.apply()/process(),
    // which are free to mutate `model` - see thumbnail_render.hpp. Real printer screens (COSMOS,
    // Klipperscreen, Mainsail/Fluidd file browsers) read this straight out of the .gcode file's
    // own embedded "; THUMBNAIL_BLOCK" comments; without a thumbnail_cb here, print.export_gcode()
    // silently skips writing them even though the default "thumbnails" config value already asks
    // for 48x48 and 300x300 PNGs.
    ThumbnailsGeneratorCallback thumbnail_cb = make_thumbnail_callback(model.mesh());

    Print print;
    for (ModelObject* object : model.objects) {
        print.auto_assign_extruders(object);
    }
    print.apply(model, config);

    StringObjectException validation_error = print.validate();
    if (!validation_error.string.empty()) {
        throw std::runtime_error("Validation failed: " + validation_error.string);
    }

    print.process();
    print.export_gcode(output_gcode_path, nullptr, thumbnail_cb);
}

} // namespace engine
