#include "SemanticPaint.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <optional>
#include <limits>
#include <map>
#include <set>
#include <sstream>
#include <utility>

#include <miniz.h>
#include <nlohmann/json.hpp>

namespace Slic3r { namespace SemanticPaint {

namespace {

struct FaceGeom
{
    Vec3d  normal{0., 0., 1.};
    double area{0.};
    Vec3d  centroid{0., 0., 0.};
};

std::vector<FaceGeom> face_geometry(const indexed_triangle_set &its)
{
    std::vector<FaceGeom> out(its.indices.size());
    for (size_t i = 0; i < its.indices.size(); ++i) {
        const Vec3d a = its.vertices[its.indices[i](0)].cast<double>();
        const Vec3d b = its.vertices[its.indices[i](1)].cast<double>();
        const Vec3d c = its.vertices[its.indices[i](2)].cast<double>();
        const Vec3d n = (b - a).cross(c - a);
        const double len = n.norm();
        out[i].area     = 0.5 * len;
        out[i].normal   = len > 1e-12 ? Vec3d(n / len) : Vec3d(0., 0., 1.);
        out[i].centroid = (a + b + c) / 3.;
    }
    return out;
}

} // namespace

Segmentation segment_mesh(const indexed_triangle_set &its, const SegmentParams &params)
{
    Segmentation result;
    const size_t nf = its.indices.size();
    result.face_segment.assign(nf, -1);
    if (nf == 0)
        return result;

    const std::vector<FaceGeom> geom      = face_geometry(its);
    const std::vector<Vec3i32>  neighbors = its_face_neighbors(its);
    const double                cos_step  = std::cos(params.angle_deg * M_PI / 180.);
    const double                cos_drift = std::cos(params.max_drift_deg * M_PI / 180.);

    // 1. Region growing over the face adjacency graph.
    std::vector<int> label(nf, -1);
    int              next_label = 0;
    for (size_t seed = 0; seed < nf; ++seed) {
        if (label[seed] >= 0)
            continue;
        const int    id = next_label++;
        Vec3d        sum = geom[seed].normal * std::max(geom[seed].area, 1e-12);
        std::vector<int> stack{int(seed)};
        label[seed] = id;
        while (!stack.empty()) {
            const int f = stack.back();
            stack.pop_back();
            const Vec3d mean = sum.norm() > 1e-12 ? Vec3d(sum.normalized()) : geom[f].normal;
            for (int k = 0; k < 3; ++k) {
                const int g = neighbors[f](k);
                if (g < 0 || label[g] >= 0)
                    continue;
                if (geom[f].normal.dot(geom[g].normal) < cos_step || mean.dot(geom[g].normal) < cos_drift)
                    continue;
                label[g] = id;
                sum += geom[g].normal * std::max(geom[g].area, 1e-12);
                stack.push_back(g);
            }
        }
    }

    // 2. Areas and shared border lengths between segments.
    const size_t         nseg = size_t(next_label);
    std::vector<double>  area(nseg, 0.);
    for (size_t f = 0; f < nf; ++f)
        area[label[f]] += geom[f].area;
    const double total_area = std::accumulate(area.begin(), area.end(), 0.);

    std::vector<std::map<int, double>> border(nseg);
    for (size_t f = 0; f < nf; ++f)
        for (int k = 0; k < 3; ++k) {
            const int g = neighbors[f](k);
            if (g < 0 || label[g] == label[f] || size_t(g) < f)
                continue;
            const Vec3d a = its.vertices[its.indices[f](k)].cast<double>();
            const Vec3d b = its.vertices[its.indices[f]((k + 1) % 3)].cast<double>();
            const double len = (b - a).norm();
            border[label[f]][label[g]] += len;
            border[label[g]][label[f]] += len;
        }

    // 3. Merge the smallest segments into their longest-bordering neighbour.
    std::vector<int>             parent(nseg);
    std::iota(parent.begin(), parent.end(), 0);
    std::set<std::pair<double, int>> by_area;
    for (size_t s = 0; s < nseg; ++s)
        by_area.insert({area[s], int(s)});
    size_t alive = nseg;
    std::set<int> unmergeable;
    while (!by_area.empty()) {
        const auto smallest = std::find_if(by_area.begin(), by_area.end(), [&](const auto &e) { return !unmergeable.count(e.second); });
        if (smallest == by_area.end())
            break;
        const int    s    = smallest->second;
        const double frac = total_area > 0. ? area[s] / total_area : 0.;
        if (frac >= params.min_area_fraction && alive <= params.max_segments)
            break;
        int    best = -1;
        double best_len = 0.;
        for (const auto &[u, len] : border[s])
            if (len > best_len) {
                best_len = len;
                best     = u;
            }
        if (best < 0) {
            unmergeable.insert(s); // a floating shell: nothing to merge into
            continue;
        }
        by_area.erase({area[s], s});
        by_area.erase({area[best], best});
        area[best] += area[s];
        by_area.insert({area[best], best});
        for (const auto &[u, len] : border[s]) {
            if (u == best)
                continue;
            border[best][u] += len;
            border[u][best] += len;
            border[u].erase(s);
        }
        border[best].erase(s);
        border[s].clear();
        parent[s] = best;
        --alive;
    }
    auto root = [&](int s) {
        while (parent[s] != s)
            s = parent[s];
        return s;
    };

    // 4. Final ids ordered by area, largest first.
    std::map<int, double> root_area;
    for (size_t f = 0; f < nf; ++f)
        root_area[root(label[f])] += geom[f].area;
    std::vector<std::pair<double, int>> order;
    for (const auto &[r, a] : root_area)
        order.push_back({-a, r});
    std::sort(order.begin(), order.end());
    std::map<int, int> final_id;
    for (size_t i = 0; i < order.size(); ++i)
        final_id[order[i].second] = int(i);
    for (size_t f = 0; f < nf; ++f)
        result.face_segment[f] = final_id[root(label[f])];

    // 5. Statistics.
    result.bbox = BoundingBoxf3();
    for (const Vec3f &v : its.vertices)
        result.bbox.merge(v.cast<double>());
    const Vec3d bmin = result.bbox.min;
    Vec3d       size = result.bbox.size();
    for (int i = 0; i < 3; ++i)
        size(i) = std::max(size(i), 1e-9);

    result.segments.assign(order.size(), Segment());
    std::vector<Vec3d> centroid_sum(order.size(), Vec3d::Zero()), normal_sum(order.size(), Vec3d::Zero());
    for (size_t i = 0; i < order.size(); ++i) {
        result.segments[i].id       = int(i);
        result.segments[i].bbox_min = Vec3d::Constant(std::numeric_limits<double>::max());
        result.segments[i].bbox_max = Vec3d::Constant(std::numeric_limits<double>::lowest());
    }
    for (size_t f = 0; f < nf; ++f) {
        Segment &s = result.segments[result.face_segment[f]];
        ++s.faces;
        s.area += geom[f].area;
        centroid_sum[s.id] += geom[f].centroid * geom[f].area;
        normal_sum[s.id] += geom[f].normal * geom[f].area;
        for (int k = 0; k < 3; ++k) {
            const Vec3d p = ((its.vertices[its.indices[f](k)].cast<double>() - bmin).array() / size.array()).matrix();
            s.bbox_min    = s.bbox_min.cwiseMin(p);
            s.bbox_max    = s.bbox_max.cwiseMax(p);
        }
    }
    for (Segment &s : result.segments) {
        s.area_fraction = total_area > 0. ? s.area / total_area : 0.;
        if (s.area > 0.)
            s.centroid = ((centroid_sum[s.id] / s.area - bmin).array() / size.array()).matrix();
        s.normal = normal_sum[s.id].norm() > 1e-12 ? Vec3d(normal_sum[s.id].normalized()) : Vec3d(0., 0., 1.);
    }
    return result;
}

std::vector<View> default_views()
{
    return {
        {"front", Vec3d(0, 1, 0), Vec3d(0, 0, 1)},
        {"back", Vec3d(0, -1, 0), Vec3d(0, 0, 1)},
        {"left", Vec3d(1, 0, 0), Vec3d(0, 0, 1)},
        {"right", Vec3d(-1, 0, 0), Vec3d(0, 0, 1)},
        {"top", Vec3d(0, 0, -1), Vec3d(0, 1, 0)},
        {"isometric", Vec3d(1, 1, -1).normalized(), Vec3d(0, 0, 1)},
    };
}

namespace {

// 3x5 digit font, one row per 3 bits, top row first.
const uint8_t k_font[10][5] = {{7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
                               {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 1, 1}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7}};

void segment_color(int id, unsigned char out[3])
{
    const double h = std::fmod(id * 0.61803398875, 1.0), s = 0.65, v = 0.95;
    const double c = v * s, x = c * (1. - std::fabs(std::fmod(h * 6., 2.) - 1.)), m = v - c;
    double       r = 0, g = 0, b = 0;
    switch (int(h * 6.) % 6) {
    case 0: r = c; g = x; break;
    case 1: r = x; g = c; break;
    case 2: g = c; b = x; break;
    case 3: g = x; b = c; break;
    case 4: r = x; b = c; break;
    default: r = c; b = x; break;
    }
    out[0] = (unsigned char) std::lround((r + m) * 255.);
    out[1] = (unsigned char) std::lround((g + m) * 255.);
    out[2] = (unsigned char) std::lround((b + m) * 255.);
}

void put_pixel(Image &img, int x, int y, unsigned char r, unsigned char g, unsigned char b)
{
    if (x < 0 || y < 0 || x >= img.width || y >= img.height)
        return;
    unsigned char *p = &img.rgb[(size_t(y) * img.width + x) * 3];
    p[0] = r; p[1] = g; p[2] = b;
}

// The box a number's badge takes, centred on (cx, cy): the digits, a white margin and a black frame.
struct Badge
{
    int x0, y0, x1, y1; // inclusive
    bool overlaps(const Badge &o, int gap) const { return x0 - gap <= o.x1 && o.x0 - gap <= x1 && y0 - gap <= o.y1 && o.y0 - gap <= y1; }
};

Badge badge_box(int number, int cx, int cy, int scale)
{
    const int digits = int(std::to_string(number).size());
    const int w = digits * 4 * scale - scale, h = 5 * scale, pad = scale + 1;
    const int x0 = cx - w / 2 - pad, y0 = cy - h / 2 - pad;
    return {x0, y0, x0 + w + 2 * pad - 1, y0 + h + 2 * pad - 1};
}

// Black digits on a white box with a black frame: readable on any tint and at a reduced size.
void draw_number(Image &img, int number, int cx, int cy, int scale)
{
    const std::string digits = std::to_string(number);
    const Badge       box    = badge_box(number, cx, cy, scale);
    for (int y = box.y0; y <= box.y1; ++y)
        for (int x = box.x0; x <= box.x1; ++x) {
            const bool frame = x == box.x0 || x == box.x1 || y == box.y0 || y == box.y1;
            put_pixel(img, x, y, frame ? 0 : 255, frame ? 0 : 255, frame ? 0 : 255);
        }
    const int x0 = box.x0 + scale + 1, y0 = box.y0 + scale + 1;
    for (size_t d = 0; d < digits.size(); ++d)
        for (int row = 0; row < 5; ++row)
            for (int col = 0; col < 3; ++col)
                if (k_font[digits[d] - '0'][row] & (4 >> col)) {
                    const int px = x0 + int(d) * 4 * scale + col * scale, py = y0 + row * scale;
                    for (int dy = 0; dy < scale; ++dy)
                        for (int dx = 0; dx < scale; ++dx)
                            put_pixel(img, px + dx, py + dy, 0, 0, 0);
                }
}

} // namespace

namespace {
Image render(const indexed_triangle_set &its, const Segmentation &seg, const View &view, int size, const std::vector<FaceColor> *face_colors,
             std::vector<int> *labelled = nullptr);
}

std::vector<View> draft_views()
{
    std::vector<View> views = default_views();
    views.push_back({"underneath", Vec3d(0, 0, 1), Vec3d(0, 1, 0)});
    return views;
}

std::vector<FaceColor> draft_face_colors(const std::vector<int> &face_filament, const std::vector<FilamentInfo> &filaments)
{
    const FaceColor kept{200, 200, 200};
    std::vector<FaceColor> by_slot(1, kept);
    for (const FilamentInfo &f : filaments) {
        if (f.index < 1)
            continue;
        if (by_slot.size() <= size_t(f.index))
            by_slot.resize(size_t(f.index) + 1, kept);
        const std::string &hex = f.color_hex;
        if (hex.size() >= 7 && hex[0] == '#' && hex.find_first_not_of("0123456789abcdefABCDEF", 1) >= 7) {
            const unsigned long rgb = std::strtoul(hex.substr(1, 6).c_str(), nullptr, 16);
            by_slot[size_t(f.index)] = FaceColor{(unsigned char) (rgb >> 16), (unsigned char) (rgb >> 8), (unsigned char) rgb};
        }
    }
    std::vector<FaceColor> out(face_filament.size(), kept);
    for (size_t i = 0; i < face_filament.size(); ++i)
        if (face_filament[i] > 0 && size_t(face_filament[i]) < by_slot.size())
            out[i] = by_slot[size_t(face_filament[i])];
    return out;
}

namespace {
// A view of the mesh as the pictures show it: every vertex on the screen (x, y in pixels, z its depth), and for
// every pixel the nearest face and its depth. Shared by the pictures and by the search for the faces under a spot,
// so that a position in a picture means the same place for both.
struct Raster
{
    Vec3d              f, right, up;
    std::vector<Vec3d> screen;
    std::vector<float> zbuf;
    std::vector<int>   idbuf;
    double             scale{1.}, cx{0.}, cy{0.};
    int                size{0};

    // The point of the model that a pixel shows, from the pixel's place and the depth kept for it.
    Vec3d point(int px, int py) const
    {
        return right * ((px + 0.5 - size * 0.5) / scale + cx) + up * ((size * 0.5 - (py + 0.5)) / scale + cy) +
               f * double(zbuf[size_t(py) * size + px]);
    }
};

Raster rasterize(const indexed_triangle_set &its, const View &view, int size)
{
    Raster out;
    out.f     = view.view_dir.normalized();
    out.right = out.f.cross(view.up).normalized();
    out.up    = out.right.cross(out.f);

    std::vector<Vec3d> &proj = out.screen;
    proj.resize(its.vertices.size());
    double minx = 1e300, maxx = -1e300, miny = 1e300, maxy = -1e300;
    for (size_t i = 0; i < its.vertices.size(); ++i) {
        const Vec3d p = its.vertices[i].cast<double>();
        proj[i]       = Vec3d(p.dot(out.right), p.dot(out.up), p.dot(out.f));
        minx = std::min(minx, proj[i].x()); maxx = std::max(maxx, proj[i].x());
        miny = std::min(miny, proj[i].y()); maxy = std::max(maxy, proj[i].y());
    }
    const double margin = size * 0.06;
    const double range  = std::max({maxx - minx, maxy - miny, 1e-9});
    const double scale  = (size - 2 * margin) / range;
    const double cx = 0.5 * (minx + maxx), cy = 0.5 * (miny + maxy);
    out.scale = scale;
    out.cx    = cx;
    out.cy    = cy;
    out.size  = size;
    for (Vec3d &p : proj)
        p = Vec3d((p.x() - cx) * scale + size * 0.5, size * 0.5 - (p.y() - cy) * scale, p.z());

    out.zbuf.assign(size_t(size) * size, std::numeric_limits<float>::max());
    out.idbuf.assign(size_t(size) * size, -1);
    for (size_t t = 0; t < its.indices.size(); ++t) {
        const Vec3d &a = proj[its.indices[t](0)], &b = proj[its.indices[t](1)], &c = proj[its.indices[t](2)];
        const double ax = a.x(), ay = a.y(), bx = b.x(), by = b.y(), cxs = c.x(), cys = c.y();
        const double det = (bx - ax) * (cys - ay) - (cxs - ax) * (by - ay);
        if (std::fabs(det) < 1e-9)
            continue;
        const int x0 = std::max(0, int(std::floor(std::min({ax, bx, cxs})))), x1 = std::min(size - 1, int(std::ceil(std::max({ax, bx, cxs}))));
        const int y0 = std::max(0, int(std::floor(std::min({ay, by, cys})))), y1 = std::min(size - 1, int(std::ceil(std::max({ay, by, cys}))));
        for (int y = y0; y <= y1; ++y)
            for (int x = x0; x <= x1; ++x) {
                const double px = x + 0.5, py = y + 0.5;
                const double w1 = ((px - ax) * (cys - ay) - (cxs - ax) * (py - ay)) / det;
                const double w2 = ((bx - ax) * (py - ay) - (px - ax) * (by - ay)) / det;
                const double w0 = 1. - w1 - w2;
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6)
                    continue;
                const float z = float(w0 * a.z() + w1 * b.z() + w2 * c.z());
                const size_t idx = size_t(y) * size + x;
                if (z < out.zbuf[idx]) {
                    out.zbuf[idx]  = z;
                    out.idbuf[idx] = int(t);
                }
            }
    }
    return out;
}
} // namespace

std::vector<SpotPatch> apply_spots(const indexed_triangle_set &its, Segmentation &seg, Suggestion &suggestion, const std::vector<View> &views, int size)
{
    std::vector<SpotPatch> patches;
    if (seg.face_segment.size() != its.indices.size() || suggestion.spots.empty() || size <= 0)
        return patches;

    // Where each mark lands on the surface, and how large it is there.
    struct Place
    {
        std::string name;
        Vec3d       point{Vec3d::Zero()};
        double      radius{0.};
        int         filament{0};
        int         count{1};
    };
    std::vector<Place>                 places; // one per ball, in the order first marked
    std::map<int, Raster>              rasters;
    for (const Spot &spot : suggestion.spots) {
        if (spot.view < 0 || size_t(spot.view) >= views.size() || spot.filament <= 0 || !(spot.r > 0.))
            continue;
        auto found = rasters.find(spot.view);
        if (found == rasters.end())
            found = rasters.emplace(spot.view, rasterize(its, views[size_t(spot.view)], size)).first;
        const Raster &raster = found->second;
        // The pixel under the mark's middle, or the nearest one inside the mark that shows the model.
        int    hit_x = -1, hit_y = -1;
        double best  = spot.r * spot.r;
        const int reach = int(std::ceil(spot.r));
        for (int dy = -reach; dy <= reach; ++dy)
            for (int dx = -reach; dx <= reach; ++dx) {
                const int px = int(std::floor(spot.x)) + dx, py = int(std::floor(spot.y)) + dy;
                if (px < 0 || py < 0 || px >= size || py >= size || raster.idbuf[size_t(py) * size + px] < 0)
                    continue;
                const double d = double(dx) * dx + double(dy) * dy;
                if (d <= best && (hit_x < 0 || d < best)) {
                    best  = d;
                    hit_x = px;
                    hit_y = py;
                }
            }
        if (hit_x < 0)
            continue;
        const Vec3d  point  = raster.point(hit_x, hit_y);
        const double radius = spot.r / raster.scale;
        // The same feature seen in another view lands close by: one place, at the mean.
        Place *same = nullptr;
        for (Place &place : places)
            if (place.name == spot.name && (place.point - point).norm() < 1.5 * std::max(place.radius, radius)) {
                same = &place;
                break;
            }
        if (same != nullptr) {
            same->point  = (same->point * same->count + point) / double(same->count + 1);
            same->radius = (same->radius * same->count + radius) / double(same->count + 1);
            ++same->count;
        } else
            places.push_back({spot.name, point, radius, spot.filament, 1});
    }
    if (places.empty())
        return patches;

    const size_t        faces = its.indices.size();
    std::vector<Vec3d>  middle(faces);
    std::vector<double> area(faces, 0.);
    double              total = 0.;
    std::vector<Vec3d>  normal(faces, Vec3d::Zero());
    // The faces across each face's three edges (-1 where there is none or more than one).
    std::vector<std::array<int, 3>>                    across(faces, std::array<int, 3>{-1, -1, -1});
    std::map<std::pair<int, int>, std::pair<int, int>> edge_first; // an edge's first face and which of its edges it is
    for (size_t t = 0; t < faces; ++t) {
        const Vec3d a = its.vertices[its.indices[t](0)].cast<double>(), b = its.vertices[its.indices[t](1)].cast<double>(),
                    c = its.vertices[its.indices[t](2)].cast<double>();
        middle[t] = (a + b + c) / 3.;
        const Vec3d n = (b - a).cross(c - a);
        area[t]   = 0.5 * n.norm();
        normal[t] = area[t] > 0. ? Vec3d(n / (2. * area[t])) : Vec3d::Zero();
        total += area[t];
        for (int k = 0; k < 3; ++k) {
            const int u = its.indices[t](k), v = its.indices[t]((k + 1) % 3);
            const auto key   = std::make_pair(std::min(u, v), std::max(u, v));
            const auto found = edge_first.find(key);
            if (found == edge_first.end())
                edge_first.emplace(key, std::make_pair(int(t), k));
            else if (found->second.first >= 0) {
                across[t][size_t(k)]                                             = found->second.first;
                across[size_t(found->second.first)][size_t(found->second.second)] = int(t);
                found->second.first = -1; // a third face on this edge is joined to neither
            }
        }
    }
    // The faces reached from a seed without leaving a ball around it, and, with `bend` under 1, without crossing an
    // edge where the surface bends by more than that (as a cosine): the fill tool's rule.
    std::vector<char> seen(faces, 0);
    const auto reach = [&](size_t seed, double radius, double bend) {
        std::vector<int> out{int(seed)}, queue{int(seed)};
        std::fill(seen.begin(), seen.end(), 0);
        seen[seed] = 1;
        while (!queue.empty()) {
            const int t = queue.back();
            queue.pop_back();
            for (const int other : across[size_t(t)])
                if (other >= 0 && !seen[size_t(other)] && (middle[size_t(other)] - middle[seed]).norm() <= radius &&
                    (bend >= 1. || normal[size_t(t)].dot(normal[size_t(other)]) >= bend)) {
                    seen[size_t(other)] = 1;
                    queue.push_back(other);
                    out.push_back(other);
                }
        }
        return out;
    };
    const auto area_of = [&](const std::vector<int> &list, size_t seed, double within) {
        double sum = 0.;
        for (const int t : list)
            if ((middle[size_t(t)] - middle[seed]).norm() <= within)
                sum += area[size_t(t)];
        return sum;
    };
    const double bend_limit = std::cos(k_spot_fill_angle * M_PI / 180.) - 1e-9;
    int next_id = 0;
    for (const Segment &s : seg.segments)
        next_id = std::max(next_id, s.id + 1);
    suggestion.filament.resize(seg.segments.size(), 0);
    suggestion.name.resize(seg.segments.size());
    suggestion.unsure.resize(seg.segments.size(), 0);

    std::map<std::string, int> part_of; // the part made for a name
    for (const Place &place : places) {
        // The ball sits on the surface: at the middle of the face nearest to where the marks landed.
        size_t seed = 0;
        double best = std::numeric_limits<double>::max();
        for (size_t t = 0; t < faces; ++t) {
            const double d = (middle[t] - place.point).squaredNorm();
            if (d < best) {
                best = d;
                seed = t;
            }
        }
        auto known = part_of.find(place.name);
        if (known == part_of.end()) {
            Segment s;
            s.id  = next_id++;
            known = part_of.emplace(place.name, int(seg.segments.size())).first;
            seg.segments.push_back(s);
            suggestion.filament.push_back(place.filament);
            suggestion.name.push_back(place.name);
            suggestion.unsure.push_back(0);
        }
        SpotPatch patch;
        patch.segment   = known->second;
        patch.center    = middle[seed].cast<float>();
        patch.radius    = float(place.radius);
        patch.seed_face = int(seed);
        // Filled like the fill tool, in a ball somewhat larger than the mark. When the model's edges hold the fill
        // back there (it takes well under what the ball holds beyond the mark's own size), the feature has an outline
        // of its own and is what was filled. Otherwise it has none, and the feature is the ball of the mark's size.
        const double           wide   = 1.6 * place.radius;
        std::vector<int>       ball   = reach(seed, place.radius, 1.);
        const std::vector<int> around = reach(seed, wide, 1.);
        std::vector<int>       filled = reach(seed, wide, bend_limit);
        const double ball_area = area_of(ball, seed, place.radius), filled_inside = area_of(filled, seed, place.radius);
        const double ring = area_of(around, seed, wide) - area_of(around, seed, place.radius), filled_ring = area_of(filled, seed, wide) - filled_inside;
        if (ring > 0. && filled_ring < 0.6 * ring && filled_inside >= 0.3 * ball_area) {
            std::fill(seen.begin(), seen.end(), 0);
            for (const int t : filled)
                seen[size_t(t)] = 1;
            for (const int t : around)
                if (!seen[size_t(t)])
                    patch.excluded.push_back(t);
            patch.radius = float(wide);
            patch.faces  = std::move(filled);
        } else
            patch.faces = std::move(ball);
        for (const int t : patch.faces)
            seg.face_segment[size_t(t)] = patch.segment;
        patches.push_back(std::move(patch));
    }
    // What the list shows of a marked feature is its share of the surface.
    for (const auto &[name, at] : part_of) {
        Segment &s = seg.segments[size_t(at)];
        s.faces = 0;
        s.area  = 0.;
        for (size_t t = 0; t < faces; ++t)
            if (seg.face_segment[t] == at) {
                ++s.faces;
                s.area += area[t];
            }
        s.area_fraction = total > 0. ? s.area / total : 0.;
    }
    return patches;
}

Image render_view(const indexed_triangle_set &its, const Segmentation &seg, const View &view, int size, std::vector<int> *labelled)
{
    return render(its, seg, view, size, nullptr, labelled);
}

Image render_draft(const indexed_triangle_set &its, const View &view, const std::vector<FaceColor> &face_colors, int size)
{
    Segmentation one; // the renderer wants a segment for every face; the draft has no numbers to draw
    one.face_segment.assign(its.indices.size(), 0);
    one.segments.resize(1);
    return face_colors.size() == its.indices.size() ? render(its, one, view, size, &face_colors) : render(its, one, view, size, nullptr);
}

namespace {
Image render(const indexed_triangle_set &its, const Segmentation &seg, const View &view, int size, const std::vector<FaceColor> *face_colors,
             std::vector<int> *labelled)
{
    if (labelled != nullptr)
        labelled->clear();
    Image img;
    img.width = img.height = size;
    img.rgb.assign(size_t(size) * size * 3, 235); // light background
    if (its.indices.empty() || seg.face_segment.size() != its.indices.size())
        return img;

    const Raster             raster = rasterize(its, view, size);
    const Vec3d             &f = raster.f, &right = raster.right, &up = raster.up;
    const std::vector<int>  &idbuf = raster.idbuf;
    const Vec3d              light = (-f + 0.4 * up + 0.3 * right).normalized();

    // Shade and collect label anchors.
    const size_t nseg = seg.segments.size();
    std::vector<double> sumx(nseg, 0.), sumy(nseg, 0.);
    std::vector<size_t> cnt(nseg, 0);
    for (int y = 0; y < size; ++y)
        for (int x = 0; x < size; ++x) {
            const int t = idbuf[size_t(y) * size + x];
            if (t < 0)
                continue;
            const int s = seg.face_segment[t];
            const Vec3d n = ((its.vertices[its.indices[t](1)] - its.vertices[its.indices[t](0)]).cast<double>())
                                .cross((its.vertices[its.indices[t](2)] - its.vertices[its.indices[t](0)]).cast<double>());
            const double shade = 0.55 + 0.45 * std::fabs(n.norm() > 1e-12 ? n.normalized().dot(light) : 0.);
            unsigned char col[3];
            if (face_colors != nullptr)
                std::copy((*face_colors)[t].begin(), (*face_colors)[t].end(), col);
            else
                segment_color(s, col);
            put_pixel(img, x, y, (unsigned char) (col[0] * shade), (unsigned char) (col[1] * shade), (unsigned char) (col[2] * shade));
            sumx[s] += x; sumy[s] += y; ++cnt[s];
        }
    if (face_colors != nullptr)
        return img; // a painted draft carries no segment numbers
    // A dark line where two segments meet, so that a segment's extent does not rest on telling two tints apart.
    for (int y = 0; y + 1 < size; ++y)
        for (int x = 0; x + 1 < size; ++x) {
            const int t = idbuf[size_t(y) * size + x], r = idbuf[size_t(y) * size + x + 1], d = idbuf[size_t(y + 1) * size + x];
            if (t < 0)
                continue;
            const int s = seg.face_segment[t];
            if ((r >= 0 && seg.face_segment[r] != s) || (d >= 0 && seg.face_segment[d] != s))
                put_pixel(img, x, y, 30, 30, 30);
        }

    // Badges, largest showing segment first. A segment showing too little here gets none in this view, and neither
    // does one whose badge would cover another: fewer, clear numbers are read better than many crowded ones.
    const int    label_scale = std::max(3, size / 128);
    const size_t too_little  = size_t(size) * size / 2500;
    std::vector<size_t> order;
    for (size_t s = 0; s < nseg; ++s)
        if (cnt[s] >= too_little && cnt[s] > 0)
            order.push_back(s);
    std::sort(order.begin(), order.end(), [&cnt](size_t a, size_t b) { return cnt[a] != cnt[b] ? cnt[a] > cnt[b] : a < b; });
    // Candidate places for each segment: its own pixels on a coarse grid, the one nearest its centre first.
    const int step = std::max(2, label_scale);
    std::vector<std::vector<std::pair<int, int>>> places(nseg);
    for (int y = 0; y < size; y += step)
        for (int x = 0; x < size; x += step) {
            const int t = idbuf[size_t(y) * size + x];
            if (t >= 0)
                places[seg.face_segment[t]].emplace_back(x, y);
        }
    std::vector<Badge> drawn;
    std::vector<int>   numbers;
    for (size_t s : order) {
        const double mx = sumx[s] / cnt[s], my = sumy[s] / cnt[s];
        std::sort(places[s].begin(), places[s].end(), [mx, my](const std::pair<int, int> &a, const std::pair<int, int> &b) {
            return std::pow(a.first - mx, 2) + std::pow(a.second - my, 2) < std::pow(b.first - mx, 2) + std::pow(b.second - my, 2);
        });
        for (const std::pair<int, int> &p : places[s]) {
            const Badge box = badge_box(int(s), p.first, p.second, label_scale);
            if (box.x0 < 0 || box.y0 < 0 || box.x1 >= size || box.y1 >= size)
                continue;
            if (std::any_of(drawn.begin(), drawn.end(), [&box, label_scale](const Badge &o) { return box.overlaps(o, label_scale); }))
                continue;
            draw_number(img, int(s), p.first, p.second, label_scale);
            drawn.push_back(box);
            numbers.push_back(int(s));
            break;
        }
    }
    std::sort(numbers.begin(), numbers.end());
    if (labelled != nullptr)
        *labelled = numbers;
    return img;
}
} // namespace

std::string encode_png(const Image &image)
{
    if (image.width <= 0 || image.height <= 0 || image.rgb.size() != size_t(image.width) * image.height * 3)
        return {};
    size_t len = 0;
    void  *buf = tdefl_write_image_to_png_file_in_memory_ex(image.rgb.data(), image.width, image.height, 3, &len, 6, MZ_FALSE);
    if (buf == nullptr)
        return {};
    std::string out(static_cast<const char *>(buf), len);
    mz_free(buf);
    return out;
}

std::string base64_encode(const std::string &bytes)
{
    static const char *tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string        out;
    out.reserve((bytes.size() + 2) / 3 * 4);
    size_t i = 0;
    for (; i + 2 < bytes.size(); i += 3) {
        const uint32_t v = (uint8_t(bytes[i]) << 16) | (uint8_t(bytes[i + 1]) << 8) | uint8_t(bytes[i + 2]);
        out += tbl[(v >> 18) & 63]; out += tbl[(v >> 12) & 63]; out += tbl[(v >> 6) & 63]; out += tbl[v & 63];
    }
    if (i + 1 == bytes.size()) {
        const uint32_t v = uint8_t(bytes[i]) << 16;
        out += tbl[(v >> 18) & 63]; out += tbl[(v >> 12) & 63]; out += "==";
    } else if (i + 2 == bytes.size()) {
        const uint32_t v = (uint8_t(bytes[i]) << 16) | (uint8_t(bytes[i + 1]) << 8);
        out += tbl[(v >> 18) & 63]; out += tbl[(v >> 12) & 63]; out += tbl[(v >> 6) & 63]; out += '=';
    }
    return out;
}

std::string color_name(const std::string &hex)
{
    if (hex.size() < 7 || hex[0] != '#')
        return "unknown";
    auto comp = [&](int i) { return std::strtol(hex.substr(1 + 2 * i, 2).c_str(), nullptr, 16) / 255.0; };
    const double r = comp(0), g = comp(1), b = comp(2);
    const double mx = std::max({r, g, b}), mn = std::min({r, g, b}), d = mx - mn;
    const double v = mx, s = mx > 0 ? d / mx : 0;
    if (v < 0.18)
        return "black";
    if (s < 0.15)
        return v > 0.88 ? "white" : (v > 0.6 ? "light gray" : (v > 0.35 ? "gray" : "dark gray"));
    double h = 0;
    if (d > 0) {
        if (mx == r) h = std::fmod((g - b) / d, 6.);
        else if (mx == g) h = (b - r) / d + 2.;
        else h = (r - g) / d + 4.;
        h *= 60.;
        if (h < 0) h += 360.;
    }
    // Browns and tans are dull reds, oranges and yellows: named as such, a brown filament is not called "dark red"
    // nor a tan one "light yellow".
    if ((h < 50 || h >= 345) && v < 0.68 && (s < 0.72 || (h >= 15 && h < 50)))
        return "brown";
    if (h >= 20 && h < 65 && s < 0.5)
        return v > 0.8 && s < 0.3 ? "beige" : "tan";
    std::string name;
    if (h < 15 || h >= 345) name = "red";
    else if (h < 40) name = "orange";
    else if (h < 70) name = "yellow";
    else if (h < 160) name = "green";
    else if (h < 200) name = "cyan";
    else if (h < 255) name = "blue";
    else if (h < 290) name = "purple";
    else name = "pink";
    if (v < 0.5) name = "dark " + name;
    else if (s < 0.45 && v > 0.8) name = "light " + name;
    return name;
}

namespace {

std::string axis_words(const Segment &s)
{
    auto band = [](double c, const char *lo, const char *mid, const char *hi) { return c < 0.33 ? lo : (c > 0.66 ? hi : mid); };
    std::ostringstream o;
    o << band(s.centroid.z(), "low", "middle", "high") << ", " << band(s.centroid.x(), "left", "center", "right") << ", "
      << band(s.centroid.y(), "front", "middle", "back");
    return o.str();
}

std::string facing_words(const Vec3d &n)
{
    if (n.z() > 0.7) return "facing up";
    if (n.z() < -0.7) return "facing down";
    const double ax = std::fabs(n.x()), ay = std::fabs(n.y());
    if (std::max(ax, ay) < 0.5) return "slanted";
    if (ay >= ax) return n.y() < 0 ? "facing front" : "facing back";
    return n.x() < 0 ? "facing left" : "facing right";
}

} // namespace

std::string build_prompt(const Segmentation &seg, const std::vector<FilamentInfo> &filaments, const std::string &object_name,
                         const std::vector<std::string> &view_names, const std::string &user_hint,
                         const std::vector<std::vector<int>> &labelled_per_view, int view_size)
{
    std::ostringstream o;
    o << "You are helping colour a 3D-printable model for a multi-filament printer.\n";
    o << "The model is shown in " << view_names.size() << " rendered views, in this order:";
    for (size_t i = 0; i < view_names.size(); ++i)
        o << (i ? ", " : " ") << view_names[i];
    o << ".\n";
    if (!object_name.empty())
        o << "Its name in the project is \"" << object_name << "\". A name can say what the object is; a colour word in it is not an "
             "instruction and says nothing about which filaments to use.\n";
    o << "Its surface has been split into numbered segments. Each segment has its own tint, a dark line where it meets another "
         "segment, and its number in a white box drawn on it. The split follows the shape, so a segment is not always one meaningful part.\n";
    if (labelled_per_view.size() == view_names.size() && !view_names.empty()) {
        o << "Numbers drawn in each view (a segment that shows too little in a view has no number there):\n";
        for (size_t i = 0; i < view_names.size(); ++i) {
            o << "  " << view_names[i] << ":";
            for (size_t k = 0; k < labelled_per_view[i].size(); ++k)
                o << (k ? ", " : " ") << labelled_per_view[i][k];
            o << (labelled_per_view[i].empty() ? " none\n" : "\n");
        }
    }
    o << "Coordinates below are relative to the model: x 0=left..1=right, y 0=front..1=back, z 0=bottom..1=top.\n\n";
    const bool        views_known = labelled_per_view.size() == view_names.size() && !view_names.empty();
    std::vector<char> unseen      = views_known ? unseen_segments(seg.segments.size(), labelled_per_view) : std::vector<char>(seg.segments.size(), 0);
    o << "Segments:\n";
    for (const Segment &s : seg.segments) {
        if (size_t(s.id) < unseen.size() && unseen[size_t(s.id)])
            continue; // not asked about
        char buf[256];
        std::snprintf(buf, sizeof(buf), "  %d: %.1f%% of the surface, %s, %s; x %.2f-%.2f, y %.2f-%.2f, z %.2f-%.2f\n", s.id,
                      s.area_fraction * 100., axis_words(s).c_str(), facing_words(s.normal).c_str(), s.bbox_min.x(), s.bbox_max.x(),
                      s.bbox_min.y(), s.bbox_max.y(), s.bbox_min.z(), s.bbox_max.z());
        o << buf;
    }
    if (std::count(unseen.begin(), unseen.end(), 1) > 0)
        o << "Some further segments are too small or too hidden to carry a number in any view. They are left out here: do not "
             "answer for them, they take the filament of the part around them.\n";
    o << "\nAvailable filaments (slot: type, colour):\n";
    for (const FilamentInfo &f : filaments)
        o << "  " << f.index << ": " << (f.type.empty() ? "filament" : f.type) << ", " << f.color_hex << " (" << color_name(f.color_hex) << ")"
          << (f.name.empty() ? std::string() : ", " + f.name) << "\n";
    o << "\nTask, in this order: decide what the object is. Then, for every segment, find its number in the views and name the part "
         "of the object it covers (for example \"left ear\", \"body\", \"base\"). Only then give it the filament slot that makes a "
         "sensible, attractive coloured print. Use only the slots listed above. Use 0 for a segment that should stay the base colour. "
         "Keep related parts (for example both eyes, or all petals) the same colour. Set \"sure\" to false for a segment whose number "
         "you could not find or read, or whose part you are guessing.\n";
    o << "The user may ask for a feature that is no segment of its own (an eye, a nose, a paw at the end of a leg). Do not give its "
         "colour to a whole segment. List it under \"spots\" instead, once for every view that shows it: the view's name, the pixel "
         "position of its centre in that view (x from the left edge, y from the top edge; every view is " << view_size << " by " << view_size
      << " pixels), the radius in pixels of a circle that just covers it, its name (the same in every view) and its filament slot. A "
         "spot listed later is painted over an earlier one, so a pupil comes after the white of its eye. Give spots only for what the "
         "user asked for, and none for a feature the views do not show. In \"notes\", say what of the user's request could not be done "
         "and why, for example a colour that no filament has.\n";
    if (!user_hint.empty())
        o << "Extra instruction from the user: " << user_hint << "\n";
    o << "\nAnswer with JSON only, exactly in this shape: "
         "{\"object\":\"short description\",\"parts\":[{\"segment\":0,\"name\":\"part name\",\"filament\":1,\"sure\":true}],"
         "\"spots\":[{\"view\":\"front\",\"name\":\"feature name\",\"x\":0,\"y\":0,\"r\":0,\"filament\":1}],"
         "\"notes\":\"one or two sentences\"}\n";
    return o.str();
}

std::string command_prompt(const std::string &prompt, const std::vector<std::string> &image_paths)
{
    std::ostringstream o;
    o << "Open each of these image files (rendered views of the model, in order) and look at them before answering:\n";
    for (const std::string &p : image_paths)
        o << "  " << p << "\n";
    o << "\n" << prompt;
    return o.str();
}

std::string command_reply_text(const std::string &stdout_text)
{
    const nlohmann::json j = nlohmann::json::parse(stdout_text, nullptr, false);
    if (!j.is_discarded() && j.is_object() && j.contains("result")) {
        if (j.value("is_error", false) || !j["result"].is_string())
            return {};
        return j["result"].get<std::string>();
    }
    return stdout_text;
}

std::string openai_request_json(const std::string &model, const std::string &prompt, const std::vector<std::string> &png_b64)
{
    nlohmann::json content = nlohmann::json::array();
    content.push_back({{"type", "text"}, {"text", prompt}});
    for (const std::string &b : png_b64)
        content.push_back({{"type", "image_url"}, {"image_url", {{"url", "data:image/png;base64," + b}}}});
    nlohmann::json body = {{"model", model}, {"messages", nlohmann::json::array({{{"role", "user"}, {"content", content}}})}};
    return body.dump();
}

std::string openai_reply_text(const std::string &body)
{
    const nlohmann::json j = nlohmann::json::parse(body, nullptr, false);
    if (j.is_discarded() || !j.is_object() || !j.contains("choices") || !j["choices"].is_array() || j["choices"].empty())
        return {};
    const auto &c = j["choices"][0];
    if (!c.is_object() || !c.contains("message") || !c["message"].is_object() || !c["message"].contains("content") ||
        !c["message"]["content"].is_string())
        return {};
    return c["message"]["content"].get<std::string>();
}

namespace {

std::optional<int> as_int(const nlohmann::json &v)
{
    if (v.is_number_integer())
        return v.get<int>();
    if (v.is_number_float())
        return int(std::lround(v.get<double>()));
    if (v.is_string()) {
        const std::string s = v.get<std::string>();
        size_t            i = 0;
        while (i < s.size() && !std::isdigit(static_cast<unsigned char>(s[i])))
            ++i;
        if (i < s.size())
            return std::atoi(s.c_str() + i);
    }
    return std::nullopt;
}

} // namespace

namespace {
// A name from the answer as it may be shown: printable, single-line, and no longer than `limit` bytes.
std::string shown_name(const std::string &raw, size_t limit = 40)
{
    std::string out;
    for (const char c : raw) {
        const unsigned char u = static_cast<unsigned char>(c);
        if (u < 0x20 || u == 0x7f)
            continue;
        // A new character is begun only while there is room for all of it, so the cut never falls inside one.
        if ((u & 0xC0) != 0x80 && out.size() + 3 >= limit)
            break;
        out += c;
    }
    return out;
}
} // namespace

namespace {
std::optional<double> as_number(const nlohmann::json &v)
{
    if (v.is_number())
        return v.get<double>();
    return std::nullopt;
}

// Which view an answer means: its name, its file name ("view2.png") or its number from 1. -1 when none.
int view_index(const nlohmann::json &v, const std::vector<std::string> &view_names)
{
    const auto in_range = [&](long n) { return n >= 1 && size_t(n) <= view_names.size() ? int(n - 1) : -1; };
    if (v.is_number_integer())
        return in_range(v.get<long>());
    if (!v.is_string())
        return -1;
    std::string word = v.get<std::string>();
    std::transform(word.begin(), word.end(), word.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    for (size_t i = 0; i < view_names.size(); ++i)
        if (word == view_names[i])
            return int(i);
    if (word.rfind("view", 0) == 0)
        return in_range(std::strtol(word.c_str() + 4, nullptr, 10));
    return -1;
}
} // namespace

std::optional<Suggestion> parse_suggestion(const std::string &text, size_t segment_count, size_t filament_count,
                                           const std::vector<std::string> &view_names)
{
    for (size_t pos = text.find('{'); pos != std::string::npos; pos = text.find('{', pos + 1)) {
        // Find the matching close brace, ignoring braces inside strings.
        int  depth = 0;
        bool in_str = false, esc = false;
        size_t end = std::string::npos;
        for (size_t i = pos; i < text.size(); ++i) {
            const char c = text[i];
            if (in_str) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') in_str = false;
            } else if (c == '"') in_str = true;
            else if (c == '{') ++depth;
            else if (c == '}' && --depth == 0) { end = i; break; }
        }
        if (end == std::string::npos)
            continue;
        const nlohmann::json j = nlohmann::json::parse(text.substr(pos, end - pos + 1), nullptr, false);
        if (j.is_discarded() || !j.is_object())
            continue;
        const char *list = j.contains("parts") && j["parts"].is_array() ? "parts" :
                           j.contains("assignments") && j["assignments"].is_array() ? "assignments" : nullptr;
        if (list == nullptr)
            continue;
        Suggestion out;
        out.filament.assign(segment_count, 0);
        out.name.assign(segment_count, std::string());
        out.unsure.assign(segment_count, 0);
        if (j.contains("object") && j["object"].is_string())
            out.object = shown_name(j["object"].get<std::string>(), 160);
        for (const auto &a : j[list]) {
            if (!a.is_object() || !a.contains("segment"))
                continue;
            const auto s = as_int(a["segment"]);
            if (!s || *s < 0 || size_t(*s) >= segment_count)
                continue;
            if (a.contains("name") && a["name"].is_string())
                out.name[*s] = shown_name(a["name"].get<std::string>());
            if (a.contains("sure") && a["sure"].is_boolean())
                out.unsure[*s] = a["sure"].get<bool>() ? 0 : 1;
            const auto f = a.contains("filament") ? as_int(a["filament"]) : std::nullopt;
            if (f && *f >= 1 && size_t(*f) <= filament_count)
                out.filament[*s] = *f;
        }
        if (j.contains("notes") && j["notes"].is_string())
            out.notes = shown_name(j["notes"].get<std::string>(), 400);
        // Spots: at most a few dozen, each a circle that lies in its view and a filament that exists.
        if (!view_names.empty() && j.contains("spots") && j["spots"].is_array())
            for (const auto &a : j["spots"]) {
                if (out.spots.size() >= 48 || !a.is_object() || !a.contains("view") || !a.contains("x") || !a.contains("y") || !a.contains("r"))
                    continue;
                Spot spot;
                spot.view = view_index(a["view"], view_names);
                const auto x = as_number(a["x"]), y = as_number(a["y"]), r = as_number(a["r"]);
                const auto f = a.contains("filament") ? as_int(a["filament"]) : std::nullopt;
                if (spot.view < 0 || !x || !y || !r || !(*r >= 1.) || *r > 400. || *x < 0. || *y < 0. || *x > 4096. || *y > 4096. || !f || *f < 1 ||
                    size_t(*f) > filament_count)
                    continue;
                spot.x = *x; spot.y = *y; spot.r = *r; spot.filament = *f;
                spot.name = a.contains("name") && a["name"].is_string() ? shown_name(a["name"].get<std::string>()) : std::string();
                if (spot.name.empty())
                    spot.name = "spot";
                out.spots.push_back(std::move(spot));
            }
        return out;
    }
    return std::nullopt;
}

std::optional<std::vector<int>> parse_assignments(const std::string &text, size_t segment_count, size_t filament_count)
{
    if (auto parsed = parse_suggestion(text, segment_count, filament_count))
        return std::move(parsed->filament);
    return std::nullopt;
}

std::vector<char> unseen_segments(size_t segment_count, const std::vector<std::vector<int>> &labelled_per_view)
{
    std::vector<char> unseen(segment_count, 1);
    for (const std::vector<int> &view : labelled_per_view)
        for (const int number : view)
            if (number >= 0 && size_t(number) < segment_count)
                unseen[size_t(number)] = 0;
    return unseen;
}

std::vector<int> follow_neighbours(const indexed_triangle_set &its, const Segmentation &seg, std::vector<int> filament, const std::vector<char> &follows)
{
    const size_t count = seg.segments.size();
    if (filament.size() != count || follows.size() != count || seg.face_segment.size() != its.indices.size())
        return filament;
    // Length of the border between each pair of segments, from the edges two faces of different segments share.
    std::map<std::pair<int, int>, int>    edge_face; // undirected edge by vertex index -> the first face on it
    std::map<std::pair<int, int>, double> border;    // (follower, neighbour) -> shared length
    for (size_t t = 0; t < its.indices.size(); ++t)
        for (int k = 0; k < 3; ++k) {
            const int a = its.indices[t](k), b = its.indices[t]((k + 1) % 3);
            const auto [it, first] = edge_face.emplace(std::minmax(a, b), int(t));
            if (first)
                continue;
            const int s1 = seg.face_segment[size_t(it->second)], s2 = seg.face_segment[t];
            if (s1 == s2)
                continue;
            const double length = (its.vertices[a] - its.vertices[b]).cast<double>().norm();
            border[{s1, s2}] += length;
            border[{s2, s1}] += length;
        }
    std::vector<char> open(follows);
    for (bool settled_one = true; settled_one;) {
        settled_one = false;
        std::vector<std::pair<int, int>> now; // (segment, filament) settled in this round, applied together
        for (size_t s = 0; s < count; ++s) {
            if (!open[s])
                continue;
            double best = 0.;
            int    take = -1;
            for (auto it = border.lower_bound({int(s), 0}); it != border.end() && it->first.first == int(s); ++it)
                if (!open[size_t(it->first.second)] && it->second > best) {
                    best = it->second;
                    take = filament[size_t(it->first.second)];
                }
            if (take >= 0)
                now.emplace_back(int(s), take);
        }
        for (const auto &[s, f] : now) {
            filament[size_t(s)] = f;
            open[size_t(s)]     = 0;
            settled_one         = true;
        }
    }
    return filament;
}

std::vector<int> face_filaments(const Segmentation &seg, const std::vector<int> &seg_to_filament)
{
    std::vector<int> out(seg.face_segment.size(), 0);
    for (size_t f = 0; f < out.size(); ++f) {
        const int s = seg.face_segment[f];
        if (s >= 0 && size_t(s) < seg_to_filament.size())
            out[f] = seg_to_filament[s];
    }
    return out;
}

std::vector<int> clean_paint_boundaries(const indexed_triangle_set &its, const std::vector<int> &face_filament)
{
    const size_t n = its.indices.size();
    std::vector<int> out = face_filament;
    if (face_filament.size() != n)
        return out;

    // Vertices at the same position count as one, so that a mesh stored as loose triangles has the same neighbours.
    std::map<std::array<float, 3>, int> by_position;
    std::vector<int> same(its.vertices.size());
    for (size_t i = 0; i < its.vertices.size(); ++i) {
        const stl_vertex &v = its.vertices[i];
        same[i] = by_position.emplace(std::array<float, 3>{v.x(), v.y(), v.z()}, int(i)).first->second;
    }

    struct Side { int face; int edge; };
    std::map<std::pair<int, int>, std::vector<Side>> edge_faces;
    std::vector<std::array<double, 3>> lengths(n);
    std::vector<Vec3d> normals(n, Vec3d::Zero());
    for (size_t f = 0; f < n; ++f) {
        const stl_triangle_vertex_indices &t = its.indices[f];
        const Vec3d a = its.vertices[t(0)].cast<double>(), b = its.vertices[t(1)].cast<double>(), c = its.vertices[t(2)].cast<double>();
        const Vec3d normal = (b - a).cross(c - a);
        if (normal.norm() > 0.)
            normals[f] = normal.normalized();
        for (int e = 0; e < 3; ++e) {
            const int p = t(e), q = t((e + 1) % 3);
            lengths[f][e] = (its.vertices[p].cast<double>() - its.vertices[q].cast<double>()).norm();
            edge_faces[std::minmax(same[p], same[q])].push_back({int(f), e});
        }
    }
    std::vector<std::array<int, 3>> neighbors(n, std::array<int, 3>{-1, -1, -1});
    for (const auto &entry : edge_faces) {
        const std::vector<Side> &sides = entry.second;
        if (sides.size() == 2 && sides[0].face != sides[1].face) {
            neighbors[sides[0].face][sides[0].edge] = sides[1].face;
            neighbors[sides[1].face][sides[1].edge] = sides[0].face;
        }
    }

    // Connected patches of one filament, to leave small ones alone.
    std::vector<int>    patch(n, -1);
    std::vector<size_t> patch_size;
    std::vector<int>    queue;
    for (size_t seed = 0; seed < n; ++seed) {
        if (patch[seed] >= 0)
            continue;
        const int id = int(patch_size.size());
        queue.assign(1, int(seed));
        patch[seed] = id;
        size_t count = 0;
        while (!queue.empty()) {
            const int f = queue.back();
            queue.pop_back();
            ++count;
            for (int g : neighbors[f])
                if (g >= 0 && patch[g] < 0 && face_filament[g] == face_filament[f]) {
                    patch[g] = id;
                    queue.push_back(g);
                }
        }
        patch_size.push_back(count);
    }

    constexpr size_t k_detail_faces = 8;    // a patch this small is kept as it is
    constexpr double k_smooth_cos   = 0.94; // neighbours further apart than this are across a crease
    constexpr double k_shorter_by   = 1.1;  // the boundary must get this much shorter
    for (size_t f = 0; f < n; ++f) {
        const std::array<int, 3> &nb = neighbors[f];
        if (face_filament[f] <= 0 || patch_size[patch[f]] <= k_detail_faces || nb[0] < 0 || nb[1] < 0 || nb[2] < 0)
            continue;
        bool smooth = true;
        for (int g : nb)
            smooth = smooth && normals[f].dot(normals[g]) >= k_smooth_cos;
        if (!smooth)
            continue;
        int candidate = 0;
        for (int i = 0; i < 3 && candidate == 0; ++i)
            for (int j = i + 1; j < 3; ++j)
                if (face_filament[nb[i]] > 0 && face_filament[nb[i]] != face_filament[f] && face_filament[nb[i]] == face_filament[nb[j]])
                    candidate = face_filament[nb[i]];
        if (candidate == 0)
            continue;
        double removed = 0., total = 0.;
        for (int e = 0; e < 3; ++e) {
            total += lengths[f][e];
            if (face_filament[nb[e]] == candidate)
                removed += lengths[f][e];
        }
        if (removed > (total - removed) * k_shorter_by)
            out[f] = candidate;
    }
    return out;
}

}} // namespace Slic3r::SemanticPaint
