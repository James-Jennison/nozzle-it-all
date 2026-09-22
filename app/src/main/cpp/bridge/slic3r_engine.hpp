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

// A real, user-applied placement on the bed (WO-15 part E: object transform), applied via the
// actual libslic3r ModelInstance transform (set_offset/set_rotation/set_scaling_factor) inside
// load_and_place_model() - the same call every slice already goes through - not a cosmetic
// preview-only value. v1 scope, matching what the touch UI actually offers: XY position on the
// bed plane, rotation about Z only (turntable - arbitrary/place-on-face rotation isn't supported
// yet), and uniform scale. Default-constructed = identity placement (today's existing behavior,
// unchanged for every call site that doesn't pass one).
struct ModelTransform {
    double offset_x_mm = 0.0;
    double offset_y_mm = 0.0;
    double rotation_z_deg = 0.0;
    double scale = 1.0;
};

// config_overrides are applied (via ConfigBase::set_deserialize) after
// profile_paths, so callers can tweak individual keys without needing a
// full profile file -- e.g. for a bare smoke test with no real printer
// profile at hand.
void slice_file(const std::string& input_model_path,
                 const std::string& output_gcode_path,
                 const std::vector<std::string>& profile_paths,
                 const std::vector<std::pair<std::string, std::string>>& config_overrides = {},
                 const ModelTransform& transform = {});

// Phase 1 (Consumer Slicer Plan §16): slices a real multi-object build plate - each (model path,
// placement) pair loaded and placed exactly the way slice_file()'s single-object path already
// does, then merged into one Model and sliced together into one G-code file. See the .cpp for why
// this doesn't itself do collision detection (a separate, real UI concern).
void slice_multi_object(const std::vector<std::pair<std::string, ModelTransform>>& objects,
                         const std::string& output_gcode_path,
                         const std::vector<std::string>& profile_paths,
                         const std::vector<std::pair<std::string, std::string>>& config_overrides = {});

// Loads input_model_path (STL/3MF/OBJ) the same way slice_file() does - real Model::read_from_file,
// bed-centered - but stops short of slicing. Returns 3 floats (the first object's first
// instance's own world-space offset - the real pivot a later transform's rotate/scale will use,
// see the .cpp) followed by a flat interleaved vertex buffer for a real-time GL preview: 6 floats
// per vertex (x,y,z,nx,ny,nz), 3 vertices per triangle, nx/ny/nz the triangle's own flat face
// normal (matches thumbnail_render.cpp's flat-shading choice).
std::vector<float> load_mesh_preview(const std::string& input_model_path);

// Phase 0 (WO-16): loads input_model_path the same real way slice_file()/load_mesh_preview() do
// and returns how many separate ModelObjects it actually contains - proof, not assumption, that
// the native bridge's model load already preserves a real multi-object Model::objects list rather
// than collapsing everything into one mesh (load_mesh_preview does that collapse deliberately, for
// its own single-mesh preview use case - this function doesn't, and exists specifically so a
// multi-object model's true object count is observable from the Kotlin side ahead of any UI
// actually using it, per the Consumer Slicer Plan's Phase 0 acceptance criteria).
int count_model_objects(const std::string& input_model_path);

// Support painting (WO-14 part D): a stateful session over a loaded model's first object/volume
// (v1 scope - the common single-part case every real model used so far actually is), reusing the
// same real, headless-usable libslic3r machinery the upstream GUI relies on for the identical
// feature: AABBMesh for ray-mesh hit testing, TriangleSelector for the actual brush-paint
// algorithm, ModelVolume::supported_facets for the result print.apply() already reads during
// support generation with zero new slicing-side wiring. See slic3r_engine.cpp for the concrete
// per-call reasoning (each traced against the real upstream GUI source, not guessed).
using PaintSessionHandle = int64_t;

// Opens a session (loads + bed-centers the model, same as slice_file()/load_mesh_preview()) and
// returns an opaque handle. Throws if the model has no paintable volume.
// `transform` is applied once, at open time, and frozen for the session's lifetime - painting
// operates on the volume's raw local mesh via a `trafo` captured at open (see slic3r_engine.cpp's
// own header comment on that), so a transform change after a paint session is already open isn't
// reflected; the Kotlin caller keeps transform controls and Paint mode mutually exclusive so this
// never happens in practice (see ModelViewer.kt).
PaintSessionHandle open_paint_session(const std::string& input_model_path, const ModelTransform& transform = {});

// origin/dir are a world-space ray (the same bed-centered coordinate space load_mesh_preview's
// vertices are already in, so the Kotlin GL camera's own unprojection needs no extra transform).
// A ray that misses the mesh entirely is a silent no-op, not an error - a normal outcome for an
// ordinary stray touch during a drag.
void paint_stroke(PaintSessionHandle handle, double originX, double originY, double originZ,
                   double dirX, double dirY, double dirZ, double radiusMm, bool enforcer);

// The currently enforcer-painted triangles, transformed into the same world-space coordinates
// load_mesh_preview uses, as a flat position-only buffer (3 floats/vertex, no normals - this is
// meant for an unlit highlight overlay, not a second lit mesh).
std::vector<float> get_painted_facets(PaintSessionHandle handle);

// Slices using the session's own already-loaded (and possibly painted) in-memory model directly -
// no re-load from disk, no serialization round-trip for the painted state. Does not close the
// session; the caller closes it explicitly once done.
void slice_paint_session(PaintSessionHandle handle, const std::string& output_gcode_path,
                          const std::vector<std::string>& profile_paths,
                          const std::vector<std::pair<std::string, std::string>>& config_overrides = {});

void close_paint_session(PaintSessionHandle handle);

} // namespace engine
