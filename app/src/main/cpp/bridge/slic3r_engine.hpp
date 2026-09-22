// Adapted from the owner's own orcaslicer-android-engine project (local, /mnt/faststorage/orcaslicer-android-engine),
// which cross-compiles upstream OrcaSlicer's libslic3r for Android. Licensed AGPL-3.0-or-later
// (same as OrcaSlicer itself, and the same family already governing this app since the Helix port).
// See THIRD_PARTY_NOTICES.md and docs/WORK_ORDER.md's WO-13 entry.
#pragma once
#include <string>
#include <utility>
#include <vector>

// Shared slicing pipeline used by both the JNI bridge (slic3r_jni.cpp) and
// the standalone on-device CLI test tool (cli_test.cpp), so the two stay in
// lockstep instead of duplicating the load/configure/process/export
// sequence. Throws std::runtime_error (or whatever libslic3r itself throws)
// on failure.
namespace engine {

// config_overrides are applied (via ConfigBase::set_deserialize) after
// profile_paths, so callers can tweak individual keys without needing a
// full profile file -- e.g. for a bare smoke test with no real printer
// profile at hand.
void slice_file(const std::string& input_model_path,
                 const std::string& output_gcode_path,
                 const std::vector<std::string>& profile_paths,
                 const std::vector<std::pair<std::string, std::string>>& config_overrides = {});

} // namespace engine
