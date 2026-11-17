#include <catch2/catch_test_macros.hpp>
#include <catch2/catch_approx.hpp>

#include "libslic3r/SemanticPaint.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <nlohmann/json.hpp>


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
