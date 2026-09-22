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
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Config.hpp"

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
        object->ensure_on_bed();
    }

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
    print.export_gcode(output_gcode_path, nullptr, nullptr);
}

} // namespace engine
