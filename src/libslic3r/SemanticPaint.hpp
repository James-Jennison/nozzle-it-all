// Semantic auto-paint: split a single-colour mesh into surface segments, describe them (as data and as
// rendered views) to a vision-language model, and turn the model's segment -> filament answer into
// ordinary multi-material paint. Everything here is deterministic and independent of the network and the
// GUI; the provider round trip lives in slic3r/Utils/SemanticPaintClient.
#pragma once

#include <array>
#include <cstddef>
#include <optional>
#include <string>
#include <vector>

#include "Point.hpp"
#include "BoundingBox.hpp"
#include "TriangleMesh.hpp"

namespace Slic3r { namespace SemanticPaint {

struct SegmentParams
{
    // Neighbouring faces whose normals differ by less than this stay in one segment.
    double angle_deg = 35.0;
    // A segment may never drift further than this from the mean normal of its seed neighbourhood.
    double max_drift_deg = 70.0;
    // Segments smaller than this fraction of the surface are merged into their longest-bordering neighbour.
    double min_area_fraction = 0.004;
    // Never return more segments than this (the smallest are merged first).
    size_t max_segments = 32;
};

struct Segment
{
    int      id{0};
    size_t   faces{0};
    double   area{0.};
    double   area_fraction{0.};
    // Centroid and extent in [0,1] relative to the mesh bounding box.
    Vec3d    centroid{0., 0., 0.};
    Vec3d    bbox_min{0., 0., 0.};
    Vec3d    bbox_max{0., 0., 0.};
    // Area-weighted mean normal (unit).
    Vec3d    normal{0., 0., 1.};
};

struct Segmentation
{
    std::vector<int>     face_segment; // per face, index into `segments`
    std::vector<Segment> segments;
    BoundingBoxf3        bbox;
};

Segmentation segment_mesh(const indexed_triangle_set &its, const SegmentParams &params = {});

struct Image
{
    int                        width{0};
    int                        height{0};
    std::vector<unsigned char> rgb; // width * height * 3
};

struct View
{
    std::string name;
    Vec3d       view_dir; // direction the camera looks along
    Vec3d       up;
};

// Front, back, left, right, top and an isometric view.
std::vector<View> default_views();

// Renders flat-shaded orthographic views, each segment in its own colour with its id drawn on it.
Image render_view(const indexed_triangle_set &its, const Segmentation &seg, const View &view, int size = 384);

// The colour one face is drawn in.
using FaceColor = std::array<unsigned char, 3>;

// The views a painted draft is shown in: the six above and one from underneath, which nothing else shows.
std::vector<View> draft_views();

// The same flat-shaded view as render_view, each face in the colour given and no numbers: how a proposal would look.
Image render_draft(const indexed_triangle_set &its, const View &view, const std::vector<FaceColor> &face_colors, int size = 384);

// PNG bytes of an image (empty on failure).
std::string encode_png(const Image &image);

std::string base64_encode(const std::string &bytes);

struct FilamentInfo
{
    int         index{1}; // 1-based slot
    std::string type;
    std::string color_hex; // "#RRGGBB"
    std::string name;
};

// The colour of every face for a per-face filament list (0 = kept as it is, drawn in a neutral grey): each
// filament's own colour. A filament without a readable colour is drawn in the same grey.
std::vector<FaceColor> draft_face_colors(const std::vector<int> &face_filament, const std::vector<FilamentInfo> &filaments);

// Plain-English name of a colour ("dark red", "light blue", ...).
std::string color_name(const std::string &hex);

// Text of the question put to the model.
std::string build_prompt(const Segmentation &seg, const std::vector<FilamentInfo> &filaments, const std::string &object_name,
                         const std::vector<std::string> &view_names, const std::string &user_hint = {});

// Command provider: any program that reads the prompt on stdin and prints the answer on stdout (Claude Code's
// `claude -p`, `ollama run`, `llm`, a script...). Command-line tools cannot take inline images, so the views are
// written to files and the prompt lists their paths.
std::string command_prompt(const std::string &prompt, const std::vector<std::string> &image_paths);
// Answer text from a command's stdout: unwraps `{"result": "..."}` (Claude Code's --output-format json) and
// otherwise returns the output unchanged. Empty when the command reported an error.
std::string command_reply_text(const std::string &stdout_text);

// OpenAI-compatible chat completions (OpenAI, Ollama /v1, LM Studio, vLLM, OpenRouter...). `png_b64` holds the
// base64 PNGs in view order and is sent as data-URL images.
std::string openai_request_json(const std::string &model, const std::string &prompt, const std::vector<std::string> &png_b64);
std::string openai_reply_text(const std::string &body);

// seg_to_filament[i] is the 1-based filament slot for segment i, or 0 for "leave unpainted". Segments the reply
// does not mention, or maps outside 1..filament_count, stay 0. std::nullopt when no assignment could be read.
std::optional<std::vector<int>> parse_assignments(const std::string &reply_text, size_t segment_count, size_t filament_count);

// Per-face painted filament (0 = unpainted) for a segment -> filament map.
std::vector<int> face_filaments(const Segmentation &seg, const std::vector<int> &seg_to_filament);

// One conservative pass over the colour boundaries of a per-face filament list (0 = left as it is). A face is given
// its neighbours' filament only when two of its three neighbours agree on another filament and the boundary gets
// shorter by it; that takes back a single triangle poking into another colour. Never changed: faces at 0, faces
// next to a 0 or to an open or non-manifold edge, faces across a crease (normals more than about 20 degrees apart),
// and connected patches of one filament of eight faces or fewer, which are taken as intended detail. Neighbours are
// found by vertex position, so a mesh with repeated vertices gives the same answer. The input is not changed.
std::vector<int> clean_paint_boundaries(const indexed_triangle_set &its, const std::vector<int> &face_filament);

}} // namespace Slic3r::SemanticPaint
