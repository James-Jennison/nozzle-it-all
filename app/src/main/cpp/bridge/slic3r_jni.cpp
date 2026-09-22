// Adapted from the owner's own orcaslicer-android-engine project (local, /mnt/faststorage/orcaslicer-android-engine),
// which cross-compiles upstream OrcaSlicer's libslic3r for Android. Licensed AGPL-3.0-or-later
// (same as OrcaSlicer itself, and the same family already governing this app since the Helix port).
// See THIRD_PARTY_NOTICES.md and docs/WORK_ORDER.md's WO-13 entry.
// JNI bridge between the Android app and libslic3r. Java-side counterpart:
// org.orcaslicer.engine.NativeEngine (not yet written -- see docs/PLAN.md
// phase 6). The actual slicing pipeline lives in slic3r_engine.cpp, shared
// with the standalone on-device CLI test tool (cli_test.cpp).
//
// Print::process()/export_gcode() are explicitly documented (Print.hpp) as
// safe to call from a background thread, with no wxWidgets/GUI dependency
// in the slicing path itself.
//
// Not yet implemented: progress reporting back to Java during a slice
// (Print::process() takes no progress callback param; OrcaSlicer's GUI
// gets progress via a separate status-update mechanism this bridge doesn't
// wire up yet) and cancellation. Both are real follow-up work, not
// stubbed-out here -- nativeSliceFile() is a complete, working slice call
// as it stands, just a blocking one.
#include <jni.h>
#include <string>
#include <vector>
#include <stdexcept>

#include "slic3r_engine.hpp"
#include "libslic3r/libslic3r.h"
#include "libslic3r/PrintConfig.hpp"

namespace {

std::string jstring_to_string(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

void throw_java_exception(JNIEnv* env, const std::string& message) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message.c_str());
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_orcaslicer_engine_NativeEngine_nativeGetVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(SLIC3R_VERSION);
}

// Temporary diagnostic, not part of the copied original - see WO-13's investigation notes
// (docs/WORK_ORDER.md). nativeSliceFile throws "Some EditGcodeDialog defs were not specified
// properly" (GCode.cpp's ORCA_CHECK_GCODE_PLACEHOLDERS block) only through this JNI path, never
// through the standalone CLI test tool with an identical config. That check reads
// custom_gcode_specific_config_def, a global const object (PrintConfig.cpp) whose constructor
// only calls ConfigDef::add() on itself - no cross-translation-unit dependency - so if .has()
// returns false for a key its own constructor unconditionally adds, that's direct evidence the
// global's constructor never ran (or a duplicate, unconstructed copy is being read) when loaded
// into this .so, as opposed to statically linked into an executable.
extern "C" JNIEXPORT jstring JNICALL
Java_org_orcaslicer_engine_NativeEngine_nativeDiagnoseConfigDef(JNIEnv* env, jclass) {
    std::string result = "custom_gcode_specific_config_def.has(\"layer_num\")=";
    result += Slic3r::custom_gcode_specific_config_def.has("layer_num") ? "true" : "false";
    const auto& placeholders = Slic3r::custom_gcode_specific_placeholders();
    result += "; s_CustomGcodeSpecificPlaceholders.size()=" + std::to_string(placeholders.size());
    result += "; has(\"machine_end_gcode\")=";
    result += (placeholders.find("machine_end_gcode") != placeholders.end()) ? "true" : "false";
    return env->NewStringUTF(result.c_str());
}

// Slices inputModelPath (STL/3MF/OBJ) to outputGcodePath. profilePaths is an
// optional array of printer/filament/process profile JSON paths (OrcaSlicer's
// resources/profiles format via ConfigBase::load()), applied in array order
// on top of DynamicPrintConfig::full_print_config()'s factory defaults --
// same layering PresetBundle uses in the GUI, just without preset-selection
// UI. Pass an empty array to slice with stock defaults only.
//
// Throws java.lang.RuntimeException on any failure (missing file, invalid
// config, unsliceable geometry, ...) rather than returning an error code,
// since libslic3r itself reports failures as C++ exceptions
// (Slic3r::SlicingError et al.) that this bridge translates one-to-one.
// Local addition, not in the copied original (see this file's attribution header): the
// original nativeSliceFile never forwarded config_overrides to engine::slice_file, only
// profilePaths. Loading a bare override through a standalone "profile" JSON file (via
// ConfigBase::load()/apply(), the profilePaths path) does not behave the same as a direct
// config_overrides call - it tripped GCode.cpp's placeholder-resolution check
// (ORCA_CHECK_GCODE_PLACEHOLDERS, hardcoded on in GCode.hpp) in a way the CLI test tool's own
// direct config_overrides call (cli_test.cpp) never did. Exposing the already-proven-working
// path through JNI, rather than switching NativeEngineSmokeTest to the profile-file path,
// fixes that.
extern "C" JNIEXPORT void JNICALL
Java_org_orcaslicer_engine_NativeEngine_nativeSliceFile(
    JNIEnv* env, jclass,
    jstring jInputModelPath, jstring jOutputGcodePath, jobjectArray jProfilePaths, jobjectArray jOverrideKeys, jobjectArray jOverrideValues) {
    try {
        const std::string input_path = jstring_to_string(env, jInputModelPath);
        const std::string output_path = jstring_to_string(env, jOutputGcodePath);

        std::vector<std::string> profile_paths;
        if (jProfilePaths != nullptr) {
            jsize profile_count = env->GetArrayLength(jProfilePaths);
            for (jsize i = 0; i < profile_count; ++i) {
                auto jPath = static_cast<jstring>(env->GetObjectArrayElement(jProfilePaths, i));
                profile_paths.push_back(jstring_to_string(env, jPath));
                env->DeleteLocalRef(jPath);
            }
        }

        std::vector<std::pair<std::string, std::string>> config_overrides;
        if (jOverrideKeys != nullptr) {
            jsize override_count = env->GetArrayLength(jOverrideKeys);
            for (jsize i = 0; i < override_count; ++i) {
                auto jKey = static_cast<jstring>(env->GetObjectArrayElement(jOverrideKeys, i));
                auto jValue = static_cast<jstring>(env->GetObjectArrayElement(jOverrideValues, i));
                config_overrides.emplace_back(jstring_to_string(env, jKey), jstring_to_string(env, jValue));
                env->DeleteLocalRef(jKey);
                env->DeleteLocalRef(jValue);
            }
        }

        engine::slice_file(input_path, output_path, profile_paths, config_overrides);
    } catch (const std::exception& ex) {
        throw_java_exception(env, ex.what());
    } catch (...) {
        throw_java_exception(env, "Unknown native error during slicing");
    }
}

// Loads inputModelPath (STL/3MF/OBJ) the same real way nativeSliceFile does - not a second,
// weaker parser - and returns a flat interleaved vertex buffer for an in-app 3D preview: 6
// floats per vertex (x,y,z,nx,ny,nz), 3 vertices per triangle. See engine::load_mesh_preview.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_orcaslicer_engine_NativeEngine_nativeLoadMeshPreview(
    JNIEnv* env, jclass, jstring jInputModelPath) {
    try {
        const std::string input_path = jstring_to_string(env, jInputModelPath);
        std::vector<float> buffer = engine::load_mesh_preview(input_path);
        // One bulk SetFloatArrayRegion call, not per-element JNI calls - a real perf cliff at
        // the vertex counts a detailed model can reach.
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(buffer.size()));
        if (result == nullptr) {
            throw_java_exception(env, "Could not allocate the mesh preview buffer.");
            return nullptr;
        }
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(buffer.size()), buffer.data());
        return result;
    } catch (const std::exception& ex) {
        throw_java_exception(env, ex.what());
        return nullptr;
    } catch (...) {
        throw_java_exception(env, "Unknown native error while loading the mesh preview");
        return nullptr;
    }
}
