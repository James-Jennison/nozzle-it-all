// Adapted from the owner's own orcaslicer-android-engine project (local, /mnt/faststorage/orcaslicer-android-engine),
// which cross-compiles upstream OrcaSlicer's libslic3r for Android. Licensed AGPL-3.0-or-later
// (same as OrcaSlicer itself, and the same family already governing this app since the Helix port).
// See THIRD_PARTY_NOTICES.md and docs/WORK_ORDER.md's WO-13 entry.
// The load -> configure -> process -> export sequence mirrors OrcaSlicer's
// own tests/fff_print/test_helpers.cpp (init_print / init_and_process_print
// / gcode()), the canonical reference for driving libslic3r headlessly.
#include "slic3r_engine.hpp"

#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>

#include "libslic3r/libslic3r.h"
#include "libslic3r/AABBMesh.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include "thumbnail_render.hpp"

namespace engine {

namespace {

// Shared by slice_file() and load_mesh_preview(): load a model file and place it exactly where
// slicing will actually place it - real coordinates, not the mesh's own local origin. See
// slice_file()'s own history for why centering matters (a real off-bed print abort on real
// hardware, not a hypothetical).
Slic3r::Model load_and_place_model(const std::string& input_model_path, Slic3r::DynamicPrintConfig& config) {
    using namespace Slic3r;
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
    Points bed_shape = Slic3r::get_bed_shape(config);
    if (!bed_shape.empty()) {
        BoundingBox bed_bbox(bed_shape);
        model.center_instances_around_point(unscale(bed_bbox.center()));
    }

    for (ModelObject* object : model.objects) {
        object->ensure_on_bed();
    }
    return model;
}

// The actual process/export tail, shared by slice_file() (fresh load from disk) and
// slice_paint_session() (an already-loaded, possibly support-painted in-memory model) - both end
// the same way, just start from a different Model.
void slice_model(Slic3r::Model& model, Slic3r::DynamicPrintConfig& config, const std::string& output_gcode_path) {
    using namespace Slic3r;

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

} // namespace

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

    Model model = load_and_place_model(input_model_path, config);
    slice_model(model, config, output_gcode_path);
}

std::vector<float> load_mesh_preview(const std::string& input_model_path) {
    using namespace Slic3r;

    DynamicPrintConfig config = DynamicPrintConfig::full_print_config();
    Model model = load_and_place_model(input_model_path, config);
    TriangleMesh mesh = model.mesh();
    const indexed_triangle_set& its = mesh.its;

    std::vector<float> buffer;
    buffer.reserve(its.indices.size() * 3 * 6);
    for (const Vec3i32& tri : its.indices) {
        const Vec3f& v0 = its.vertices[tri(0)];
        const Vec3f& v1 = its.vertices[tri(1)];
        const Vec3f& v2 = its.vertices[tri(2)];
        Vec3f normal = (v1 - v0).cross(v2 - v0);
        float len = normal.norm();
        if (len > 1e-9f) normal /= len;
        for (const Vec3f& v : {v0, v1, v2}) {
            buffer.push_back(v.x()); buffer.push_back(v.y()); buffer.push_back(v.z());
            buffer.push_back(normal.x()); buffer.push_back(normal.y()); buffer.push_back(normal.z());
        }
    }
    return buffer;
}

// --- Support painting (WO-14 part D) ---------------------------------------------------------
//
// Every transform/argument shape below was traced against the real upstream GUI source
// (slic3r/GUI/Gizmos/GLGizmoPainterBase.cpp, vendored but not compiled into this headless
// engine - SLIC3R_GUI=OFF) rather than guessed, since a wrong transform here would silently
// paint the wrong triangles - a real, safety-relevant correctness risk for a feature whose whole
// point is controlling where supports print. In particular:
//   - AABBMesh/TriangleSelector operate on the volume's own RAW LOCAL mesh, not the world-
//     transformed one - the GUI's own mouse-to-mesh raycast inverse-transforms the ray into
//     local space first (GLGizmoPainterBase.cpp's own `camera_pos = trafo_matrix.inverse() *
//     camera.get_position()` and equivalent for the ray), then intersects locally.
//   - `select_patch()` itself takes the *no-translate* transform (rotation/scale only) - used
//     internally for face-normal-relative math where translation is irrelevant - while the
//     Cursor object passed into it is built with the *full* (translated) transform, since it
//     needs real-world-sized radius comparisons against local-space geometry.
namespace {

using namespace Slic3r;

struct PaintSession {
    // Guards every field below against concurrent JNI calls on the same handle. The Kotlin side
    // is expected to serialize its own paint-stroke dispatch (a drag gesture fires many touch
    // samples quickly), but TriangleSelector/AABBMesh have no thread-safety of their own, and a
    // wrong-triangle race here is a real correctness risk, not just a performance one - this is
    // defense in depth, not a substitute for that Kotlin-side serialization.
    std::mutex mutex;
    Model model;
    DynamicPrintConfig config;
    ModelVolume* volume = nullptr;
    std::unique_ptr<AABBMesh> aabb;
    std::unique_ptr<TriangleSelector> selector;
    Transform3d trafo;               // instance * volume, WITH translation
    Transform3d trafo_not_translate; // instance * volume, WITHOUT translation
};

std::mutex g_paint_sessions_mutex;
std::unordered_map<PaintSessionHandle, std::unique_ptr<PaintSession>> g_paint_sessions;
std::atomic<PaintSessionHandle> g_next_paint_handle{1};

// Only looks the session up (briefly holding the map lock); does NOT lock the session's own
// mutex - every caller below does that itself, immediately, so the map lock is never held while
// doing real work on a session.
PaintSession& find_paint_session(PaintSessionHandle handle) {
    std::lock_guard<std::mutex> lock(g_paint_sessions_mutex);
    auto it = g_paint_sessions.find(handle);
    if (it == g_paint_sessions.end()) {
        throw std::runtime_error("Unknown or already-closed paint session.");
    }
    return *it->second;
}

} // namespace

PaintSessionHandle open_paint_session(const std::string& input_model_path) {
    auto session = std::make_unique<PaintSession>();
    session->config = DynamicPrintConfig::full_print_config();
    session->model = load_and_place_model(input_model_path, session->config);

    ModelObject* object = session->model.objects.front();
    if (object->instances.empty() || object->volumes.empty()) {
        throw std::runtime_error("No paintable volume found in " + input_model_path);
    }
    // v1 scope: the first object's first volume - the common single-part case every model used
    // in this app so far actually is. A multi-volume/multi-object model still slices (and still
    // gets a real, correctly-centered 3D preview via load_mesh_preview), it just can't be
    // support-painted yet.
    ModelInstance* instance = object->instances.front();
    ModelVolume* volume = object->volumes.front();
    session->volume = volume;
    session->trafo = instance->get_transformation().get_matrix() * volume->get_matrix();
    session->trafo_not_translate = instance->get_transformation().get_matrix_no_offset() * volume->get_matrix_no_offset();
    session->aabb = std::make_unique<AABBMesh>(volume->mesh());
    session->selector = std::make_unique<TriangleSelector>(volume->mesh());

    PaintSessionHandle handle = g_next_paint_handle++;
    std::lock_guard<std::mutex> lock(g_paint_sessions_mutex);
    g_paint_sessions[handle] = std::move(session);
    return handle;
}

void paint_stroke(PaintSessionHandle handle, double originX, double originY, double originZ,
                   double dirX, double dirY, double dirZ, double radiusMm, bool enforcer) {
    PaintSession& s = find_paint_session(handle);
    std::lock_guard<std::mutex> lock(s.mutex);

    Transform3d inv = s.trafo.inverse();
    Vec3d local_origin = inv * Vec3d(originX, originY, originZ);
    Vec3d local_dir = (inv.linear() * Vec3d(dirX, dirY, dirZ)).normalized();

    AABBMesh::hit_result hit = s.aabb->query_ray_hit(local_origin, local_dir);
    if (!hit.is_hit()) return; // a stray touch missing the mesh entirely - a normal, silent no-op

    Vec3f mesh_hit = hit.position().cast<float>();
    Vec3f camera_pos = local_origin.cast<float>();
    TriangleSelector::ClippingPlane clip; // default-constructed: inactive (offset == FLT_MAX)
    std::unique_ptr<TriangleSelector::Cursor> cursor = TriangleSelector::SinglePointCursor::cursor_factory(
        mesh_hit, camera_pos, static_cast<float>(radiusMm), TriangleSelector::CursorType::SPHERE, s.trafo, clip);
    s.selector->select_patch(hit.face(), std::move(cursor),
        enforcer ? EnforcerBlockerType::ENFORCER : EnforcerBlockerType::BLOCKER,
        s.trafo_not_translate, /*triangle_splitting=*/true);
    // Persisted after every stroke, not just on commit: get_painted_facets() (the live overlay
    // while painting) and slice_paint_session() both read supported_facets, and a mid-drag crash
    // or dismissed dialog should still leave whatever was actually painted, not lose it.
    s.volume->supported_facets.set(*s.selector);
}

std::vector<float> get_painted_facets(PaintSessionHandle handle) {
    PaintSession& s = find_paint_session(handle);
    std::lock_guard<std::mutex> lock(s.mutex);
    indexed_triangle_set enforced = s.volume->supported_facets.get_facets(*s.volume, EnforcerBlockerType::ENFORCER);
    // Position-only (no normals) - this is an unlit highlight overlay, not a second lit mesh, and
    // transformed into the same world-space coordinates load_mesh_preview's vertices already use
    // so it draws in registration with the base model without the Kotlin side needing its own
    // copy of this transform.
    std::vector<float> buffer;
    buffer.reserve(enforced.indices.size() * 3 * 3);
    for (const Vec3i32& tri : enforced.indices) {
        for (int i = 0; i < 3; ++i) {
            Vec3d local = enforced.vertices[tri(i)].cast<double>();
            Vec3d world = s.trafo * local;
            buffer.push_back(static_cast<float>(world.x()));
            buffer.push_back(static_cast<float>(world.y()));
            buffer.push_back(static_cast<float>(world.z()));
        }
    }
    return buffer;
}

void slice_paint_session(PaintSessionHandle handle, const std::string& output_gcode_path,
                          const std::vector<std::string>& profile_paths,
                          const std::vector<std::pair<std::string, std::string>>& config_overrides) {
    PaintSession& s = find_paint_session(handle);
    std::lock_guard<std::mutex> lock(s.mutex);
    for (const std::string& profile_path : profile_paths) {
        DynamicPrintConfig profile_config;
        profile_config.load(profile_path, ForwardCompatibilitySubstitutionRule::Enable);
        s.config.apply(profile_config);
    }
    for (const auto& [key, value] : config_overrides) {
        s.config.set_deserialize_strict(key, value);
    }
    slice_model(s.model, s.config, output_gcode_path);
}

void close_paint_session(PaintSessionHandle handle) {
    std::unique_ptr<PaintSession> session;
    {
        std::lock_guard<std::mutex> lock(g_paint_sessions_mutex);
        auto it = g_paint_sessions.find(handle);
        if (it == g_paint_sessions.end()) return; // already closed, or never opened - a safe no-op
        session = std::move(it->second);
        g_paint_sessions.erase(it);
    }
    // Removed from the map above (so no new operation can find it) - now wait for any operation
    // already in flight on it to finish before letting `session` go out of scope and destroy it.
    std::lock_guard<std::mutex> session_lock(session->mutex);
}

} // namespace engine
