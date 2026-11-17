#include <catch2/catch_test_macros.hpp>
#include <catch2/catch_approx.hpp>

#include "libslic3r/SemanticPaint.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <set>
#include <string>
#include <vector>


using namespace Slic3r;
namespace SP = Slic3r::SemanticPaint;

TEST_CASE("SemanticPaint: a cube splits into its six sides", "[SemanticPaint]")
{
    const TriangleMesh cube = make_cube(10., 10., 10.);
    const auto seg = SP::segment_mesh(cube.its);
    REQUIRE(seg.segments.size() == 6);
    REQUIRE(seg.face_segment.size() == cube.its.indices.size());
    double total = 0.;
    for (const auto &s : seg.segments) {
        CHECK(s.area_fraction == Catch::Approx(1. / 6.).margin(1e-6));
        total += s.area_fraction;
    }
    CHECK(total == Catch::Approx(1.).margin(1e-6));
}

TEST_CASE("SemanticPaint: a sphere stays in few segments", "[SemanticPaint]")
{
    const TriangleMesh sphere = make_sphere(10., 0.2);
    const auto seg = SP::segment_mesh(sphere.its);
    CHECK(seg.segments.size() >= 1);
    CHECK(seg.segments.size() <= 32);
}

TEST_CASE("SemanticPaint: views render non-empty and encode to PNG", "[SemanticPaint]")
{
    const TriangleMesh cube = make_cube(10., 20., 30.);
    const auto seg = SP::segment_mesh(cube.its);
    for (const auto &view : SP::default_views()) {
        const SP::Image img = SP::render_view(cube.its, seg, view, 128);
        REQUIRE(img.rgb.size() == 128u * 128u * 3u);
        size_t background = 0;
        for (size_t i = 0; i < img.rgb.size(); i += 3)
            background += img.rgb[i] == 235 && img.rgb[i + 1] == 235 && img.rgb[i + 2] == 235;
        CHECK(background < 128u * 128u);
        CHECK(background > 0);
        const std::string png = SP::encode_png(img);
        REQUIRE(png.size() > 8);
        CHECK(png.substr(1, 3) == "PNG");
    }
}

TEST_CASE("SemanticPaint: base64", "[SemanticPaint]")
{
    CHECK(SP::base64_encode("") == "");
    CHECK(SP::base64_encode("f") == "Zg==");
    CHECK(SP::base64_encode("fo") == "Zm8=");
    CHECK(SP::base64_encode("foo") == "Zm9v");
    CHECK(SP::base64_encode("foobar") == "Zm9vYmFy");
}

TEST_CASE("SemanticPaint: colour names", "[SemanticPaint]")
{
    CHECK(SP::color_name("#000000") == "black");
    CHECK(SP::color_name("#FFFFFF") == "white");
    CHECK(SP::color_name("#FF0000") == "red");
    CHECK(SP::color_name("#0000FF") == "blue");
    CHECK(SP::color_name("#00C800") == "green");
}

TEST_CASE("SemanticPaint: prompt", "[SemanticPaint]")
{
    const TriangleMesh cube = make_cube(10., 10., 10.);
    const auto seg = SP::segment_mesh(cube.its);
    const std::vector<SP::FilamentInfo> fil{{1, "PLA", "#FF0000", "Red"}, {2, "PLA", "#FFFFFF", ""}};
    const std::string prompt = SP::build_prompt(seg, fil, "box", {"front", "top"}, "make it festive");
    CHECK(prompt.find("box") != std::string::npos);
    CHECK(prompt.find("festive") != std::string::npos);
    CHECK(prompt.find("#FF0000") != std::string::npos);
}

TEST_CASE("SemanticPaint: assignment parsing", "[SemanticPaint]")
{
    const std::string reply = "Sure! ```json\n{\"object\":\"a {weird} box\",\"assignments\":[{\"segment\":0,\"filament\":2},"
                              "{\"segment\":\"1\",\"filament\":1},{\"segment\":2,\"filament\":9},{\"segment\":7,\"filament\":1}]}\n```";
    const auto out = SP::parse_assignments(reply, 3, 2);
    REQUIRE(out.has_value());
    CHECK(*out == std::vector<int>{2, 1, 0});
    CHECK_FALSE(SP::parse_assignments("no json here", 3, 2).has_value());
    CHECK_FALSE(SP::parse_assignments("{\"foo\":1}", 3, 2).has_value());

    const TriangleMesh cube = make_cube(1., 1., 1.);
    const auto seg = SP::segment_mesh(cube.its);
    const auto faces = SP::face_filaments(seg, std::vector<int>(seg.segments.size(), 2));
    REQUIRE(faces.size() == cube.its.indices.size());
    for (int f : faces) CHECK(f == 2);
}

TEST_CASE("SemanticPaint: command provider helpers", "[SemanticPaint]")
{
    const std::string p = SP::command_prompt("question", {"/tmp/a.png", "/tmp/b.png"});
    CHECK(p.find("/tmp/a.png") != std::string::npos);
    CHECK(p.find("/tmp/b.png") != std::string::npos);
    CHECK(p.find("question") != std::string::npos);
    CHECK(SP::command_reply_text(R"({"type":"result","is_error":false,"result":"answer"})") == "answer");
    CHECK(SP::command_reply_text(R"({"is_error":true,"result":"boom"})").empty());
    CHECK(SP::command_reply_text("plain text answer") == "plain text answer");
    CHECK(SP::command_reply_text(R"({"assignments":[]})") == R"({"assignments":[]})");
}

TEST_CASE("SemanticPaint: OpenAI-compatible provider helpers", "[SemanticPaint]")
{
    const auto req = nlohmann::json::parse(SP::openai_request_json("m", "q", {"AAAA", "BBBB"}));
    CHECK(req["model"] == "m");
    const auto &content = req["messages"][0]["content"];
    REQUIRE(content.size() == 3);
    CHECK(content[0]["text"] == "q");
    CHECK(content[1]["image_url"]["url"] == "data:image/png;base64,AAAA");
    CHECK(SP::openai_reply_text(R"({"choices":[{"message":{"role":"assistant","content":"hi"}}]})") == "hi");
    CHECK(SP::openai_reply_text(R"({"choices":[]})").empty());
    CHECK(SP::openai_reply_text("nope").empty());
}

namespace {

// A flat sheet of 20 x 20 squares, two triangles each; faces 2c and 2c+1 belong to square c = y * 20 + x.
indexed_triangle_set sheet()
{
    const int w = 20;
    indexed_triangle_set its;
    for (int y = 0; y <= w; ++y)
        for (int x = 0; x <= w; ++x)
            its.vertices.emplace_back(float(x), float(y), 0.f);
    for (int y = 0; y < w; ++y)
        for (int x = 0; x < w; ++x) {
            const int a = y * (w + 1) + x, b = a + 1, c = a + w + 1, d = c + 1;
            its.indices.emplace_back(a, b, d);
            its.indices.emplace_back(a, d, c);
        }
    return its;
}

// Left half filament 1, right half filament 2, and one triangle of filament 1 poking into the right half.
std::vector<int> halves_with_tip(const indexed_triangle_set &its, int &tip)
{
    std::vector<int> slots(its.indices.size());
    for (size_t f = 0; f < slots.size(); ++f)
        slots[f] = (f / 2) % 20 < 10 ? 1 : 2;
    tip        = (10 * 20 + 10) * 2 + 1;
    slots[tip] = 1;
    return slots;
}

} // namespace

TEST_CASE("Boundary cleanup takes back one triangle poking into another colour", "[SemanticPaint]")
{
    const indexed_triangle_set its = sheet();
    int                        tip = 0;
    const std::vector<int>     slots = halves_with_tip(its, tip);
    const std::vector<int>     before = slots;
    const std::vector<int>     cleaned = SP::clean_paint_boundaries(its, slots);
    CHECK(cleaned[tip] == 2);
    CHECK(slots == before);
    size_t changed = 0;
    for (size_t f = 0; f < slots.size(); ++f)
        changed += cleaned[f] != slots[f];
    CHECK(changed < 20);
}

TEST_CASE("Boundary cleanup keeps small details, faces left as they are, and open edges", "[SemanticPaint]")
{
    const indexed_triangle_set its = sheet();
    std::vector<int>           slots(its.indices.size(), 1);
    const int                  detail = (10 * 20 + 10) * 2;
    slots[detail] = 2;
    CHECK(SP::clean_paint_boundaries(its, slots) == slots);
    slots[detail] = 0;
    CHECK(SP::clean_paint_boundaries(its, slots) == slots);

    indexed_triangle_set lone;
    lone.vertices = {{0.f, 0.f, 0.f}, {1.f, 0.f, 0.f}, {0.f, 1.f, 0.f}};
    lone.indices.emplace_back(0, 1, 2);
    CHECK(SP::clean_paint_boundaries(lone, {2}) == std::vector<int>{2});
}

TEST_CASE("Boundary cleanup gives the same answer for a mesh stored as loose triangles", "[SemanticPaint]")
{
    const indexed_triangle_set its = sheet();
    indexed_triangle_set       soup;
    for (const auto &t : its.indices) {
        const int at = int(soup.vertices.size());
        for (int k = 0; k < 3; ++k)
            soup.vertices.push_back(its.vertices[t(k)]);
        soup.indices.emplace_back(at, at + 1, at + 2);
    }
    int                    tip = 0;
    const std::vector<int> slots = halves_with_tip(its, tip);
    CHECK(SP::clean_paint_boundaries(its, slots) == SP::clean_paint_boundaries(soup, slots));
}

TEST_CASE("Boundary cleanup never moves a colour across a sharp crease", "[SemanticPaint]")
{
    indexed_triangle_set folded = sheet();
    for (stl_vertex &v : folded.vertices)
        if (v.x() > 10.f)
            v.z() = (v.x() - 10.f) * 2.f;
    int                    tip = 0;
    const std::vector<int> slots = halves_with_tip(folded, tip);
    CHECK(SP::clean_paint_boundaries(folded, slots)[tip] == 1);
}

TEST_CASE("Boundary cleanup returns its input when the list does not match the mesh", "[SemanticPaint]")
{
    const indexed_triangle_set its = sheet();
    CHECK(SP::clean_paint_boundaries(its, {1, 2, 3}) == std::vector<int>{1, 2, 3});
}

TEST_CASE("A painted draft is drawn in the filaments' own colours, kept faces in grey", "[SemanticPaint]")
{
    const std::vector<SP::FilamentInfo> filaments = {{1, "PLA", "#FF0000", ""}, {2, "PLA", "#0000ff", ""}, {3, "PLA", "not a colour", ""}};
    const std::vector<SP::FaceColor>    colors = SP::draft_face_colors({0, 1, 2, 3, 9}, filaments);
    const SP::FaceColor                 grey{200, 200, 200};
    REQUIRE(colors.size() == 5);
    CHECK(colors[0] == grey);
    CHECK(colors[1] == SP::FaceColor{255, 0, 0});
    CHECK(colors[2] == SP::FaceColor{0, 0, 255});
    CHECK(colors[3] == grey);
    CHECK(colors[4] == grey);
}

TEST_CASE("A draft view shows the model in the colours given, and there is a view from underneath", "[SemanticPaint]")
{
    const TriangleMesh cube = make_cube(10., 10., 10.);
    const auto         views = SP::draft_views();
    REQUIRE(views.size() == SP::default_views().size() + 1);
    CHECK(views.back().name == "underneath");

    const std::vector<SP::FaceColor> red(cube.its.indices.size(), SP::FaceColor{255, 0, 0});
    const SP::Image                  img = SP::render_draft(cube.its, views.back(), red, 64);
    REQUIRE(img.rgb.size() == size_t(64) * 64 * 3);
    // The middle of the picture is the cube's underside: red, shaded, with no green or blue in it.
    const size_t mid = (size_t(32) * 64 + 32) * 3;
    CHECK(img.rgb[mid] > 100);
    CHECK(img.rgb[mid + 1] == 0);
    CHECK(img.rgb[mid + 2] == 0);
}

TEST_CASE("SemanticPaint: a view says which numbers it carries, and each is a black-on-white badge", "[SemanticPaint]")
{
    const TriangleMesh cube = make_cube(10., 20., 30.);
    const auto         seg  = SP::segment_mesh(cube.its);
    REQUIRE(seg.segments.size() == 6);
    const std::vector<SP::View> views = SP::default_views();
    std::set<int>               seen;
    for (const SP::View &view : views) {
        std::vector<int> labelled{99}; // overwritten, never appended to
        const SP::Image  img = SP::render_view(cube.its, seg, view, 256, &labelled);
        // A straight-on view of a box shows one face, the isometric view three.
        CHECK(labelled.size() == (view.name == "isometric" ? 3u : 1u));
        CHECK(std::is_sorted(labelled.begin(), labelled.end()));
        seen.insert(labelled.begin(), labelled.end());
        size_t white = 0;
        for (size_t i = 0; i < img.rgb.size(); i += 3)
            white += img.rgb[i] == 255 && img.rgb[i + 1] == 255 && img.rgb[i + 2] == 255;
        CHECK(white > 20 * labelled.size());
    }
    // Every face of the box is numbered in some view except the one nothing looks at: the underside.
    CHECK(seen.size() == 5);

    const std::vector<SP::FilamentInfo> fil = {{1, "PLA", "#FF0000", "Red"}, {2, "PLA", "#FFFFFF", "White"}};
    const std::string prompt = SP::build_prompt(seg, fil, "box", {"front", "top"}, {}, {{2}, {}});
    CHECK(prompt.find("Numbers drawn in each view") != std::string::npos);
    CHECK(prompt.find("  front: 2\n") != std::string::npos);
    CHECK(prompt.find("  top: none\n") != std::string::npos);
    CHECK(prompt.find("\"parts\":[{\"segment\":0,\"name\"") != std::string::npos);
    // A list that does not match the views is left out, not shown wrongly.
    CHECK(SP::build_prompt(seg, fil, "box", {"front", "top"}, {}, {{2}}).find("Numbers drawn") == std::string::npos);
}

TEST_CASE("SemanticPaint: a crowded view drops badges before it lets them cover each other", "[SemanticPaint]")
{
    // Thirty thin strips side by side: at this size only some of their numbers fit.
    indexed_triangle_set its;
    SP::Segmentation     seg;
    seg.segments.resize(30);
    for (int i = 0; i < 30; ++i) {
        const float x0 = float(i), x1 = float(i) + 1.f;
        const int   v  = int(its.vertices.size());
        its.vertices.emplace_back(x0, 0.f, 0.f);
        its.vertices.emplace_back(x1, 0.f, 0.f);
        its.vertices.emplace_back(x1, 0.f, 30.f);
        its.vertices.emplace_back(x0, 0.f, 30.f);
        its.indices.emplace_back(v, v + 1, v + 2);
        its.indices.emplace_back(v, v + 2, v + 3);
        seg.face_segment.push_back(i);
        seg.face_segment.push_back(i);
        seg.segments[size_t(i)].id = i;
    }
    std::vector<int> labelled;
    SP::render_view(its, seg, SP::default_views().front(), 256, &labelled);
    CHECK(!labelled.empty());
    CHECK(labelled.size() < 30);
}

TEST_CASE("SemanticPaint: an answer with part names and doubts is read, and so is the earlier shape", "[SemanticPaint]")
{
    const std::string reply = "Here you go:\n```json\n{\"object\":\"a cat\",\"parts\":[{\"segment\":0,\"name\":\"body\",\"filament\":2,\"sure\":true},"
                              "{\"segment\":1,\"name\":\"left\\near\\u0007\",\"filament\":1,\"sure\":false},"
                              "{\"segment\":2,\"name\":\"tail\",\"filament\":9},{\"segment\":7,\"name\":\"nothing\",\"filament\":1}]}\n```";
    const auto out = SP::parse_suggestion(reply, 3, 2);
    REQUIRE(out.has_value());
    CHECK(out->object == "a cat");
    CHECK(out->filament == std::vector<int>{2, 1, 0}); // a filament that does not exist leaves the part as it is
    CHECK(out->name[0] == "body");
    CHECK(out->name[1] == "leftear"); // control characters never reach the screen
    CHECK(out->name[2] == "tail");
    CHECK(out->unsure == std::vector<char>{0, 1, 0});

    const auto old = SP::parse_suggestion("{\"assignments\":[{\"segment\":1,\"filament\":2}]}", 3, 2);
    REQUIRE(old.has_value());
    CHECK(old->filament == std::vector<int>{0, 2, 0});
    CHECK(old->name[1].empty());
    CHECK_FALSE(SP::parse_suggestion("{\"foo\":1}", 3, 2).has_value());

    // A long name is cut, and never inside a character.
    std::string longname;
    for (int i = 0; i < 30; ++i)
        longname += "\xC3\xA9"; // é
    const auto cut = SP::parse_suggestion("{\"parts\":[{\"segment\":0,\"name\":\"" + longname + "\",\"filament\":1}]}", 1, 1);
    REQUIRE(cut.has_value());
    CHECK(cut->name[0].size() <= 40);
    CHECK(cut->name[0].size() % 2 == 0);
    CHECK(cut->name[0].size() >= 30);
}

TEST_CASE("SemanticPaint: a part no view numbers is not asked about and follows the part around it", "[SemanticPaint]")
{
    // Three strips side by side, the narrow middle one unseen: A | m | B, plus a strip C that touches nothing.
    indexed_triangle_set its;
    SP::Segmentation     seg;
    seg.segments.resize(4);
    const auto strip = [&](float x0, float x1, float z0, float z1, int segment, bool share_left) {
        const int v = int(its.vertices.size());
        // The left edge reuses the previous strip's right-hand vertices, so the two strips share an edge.
        const int a = share_left ? v - 3 : v, d = share_left ? v - 2 : v + 3;
        if (!share_left) {
            its.vertices.emplace_back(x0, 0.f, z0);
        }
        its.vertices.emplace_back(x1, 0.f, z0);
        its.vertices.emplace_back(x1, 0.f, z1);
        if (!share_left)
            its.vertices.emplace_back(x0, 0.f, z1);
        const int b = share_left ? v : v + 1, c = share_left ? v + 1 : v + 2;
        its.indices.emplace_back(a, b, c);
        its.indices.emplace_back(a, c, d);
        seg.face_segment.push_back(segment);
        seg.face_segment.push_back(segment);
    };
    strip(0.f, 10.f, 0.f, 10.f, 0, false); // A: vertices 0 (x0,z0) 1 (x1,z0) 2 (x1,z1) 3 (x0,z1)
    // m shares A's right edge (vertices 1 and 2).
    {
        const int v = int(its.vertices.size());
        its.vertices.emplace_back(11.f, 0.f, 0.f);
        its.vertices.emplace_back(11.f, 0.f, 10.f);
        its.indices.emplace_back(1, v, v + 1);
        its.indices.emplace_back(1, v + 1, 2);
        seg.face_segment.insert(seg.face_segment.end(), {1, 1});
        // B shares m's right edge, but only half of it is B's: B is shorter, so A has the longer border with m.
        its.vertices.emplace_back(11.f, 0.f, 4.f);
        its.vertices.emplace_back(20.f, 0.f, 0.f);
        its.vertices.emplace_back(20.f, 0.f, 4.f);
        its.indices.emplace_back(v, v + 3, v + 4);
        its.indices.emplace_back(v, v + 4, v + 2);
        seg.face_segment.insert(seg.face_segment.end(), {2, 2});
    }
    strip(40.f, 50.f, 0.f, 10.f, 3, false); // C, apart

    const std::vector<char> unseen = SP::unseen_segments(4, {{0, 2}, {2}, {}});
    CHECK(unseen == std::vector<char>{0, 1, 0, 1});
    // m takes A's filament (the longer border; B touches m at no whole shared edge here), C has no neighbour and keeps its own.
    const std::vector<int> out = SP::follow_neighbours(its, seg, {2, 0, 1, 3}, unseen);
    CHECK(out == std::vector<int>{2, 2, 1, 3});
    // Nothing marked: nothing changes. Sizes that do not fit: nothing changes.
    CHECK(SP::follow_neighbours(its, seg, {2, 0, 1, 3}, {0, 0, 0, 0}) == std::vector<int>{2, 0, 1, 3});
    CHECK(SP::follow_neighbours(its, seg, {2, 0}, unseen) == std::vector<int>{2, 0});

    // The question leaves the unseen parts out and says so; a colour word in the object's name is called what it is.
    const std::vector<SP::FilamentInfo> fil = {{1, "PLA", "#FF0000", "Red"}, {2, "PLA", "#000000", "Black"}};
    for (size_t i = 0; i < seg.segments.size(); ++i)
        seg.segments[i].id = int(i);
    const std::string prompt = SP::build_prompt(seg, fil, "Capybara_Black", {"front", "back", "top"}, {}, {{0, 2}, {2}, {}});
    CHECK(prompt.find("\n  0: ") != std::string::npos);
    CHECK(prompt.find("\n  1: ") == std::string::npos);
    CHECK(prompt.find("\n  3: ") == std::string::npos);
    CHECK(prompt.find("do not answer for them") != std::string::npos);
    CHECK(prompt.find("a colour word in it is not an instruction") != std::string::npos);
}
