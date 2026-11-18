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
    // Browns and tans by their names: a brown filament is not "dark red", a tan one not "light yellow".
    CHECK(SP::color_name("#7D6556") == "brown");
    CHECK(SP::color_name("#5A3A36") == "brown");
    CHECK(SP::color_name("#8B4513") == "brown");
    CHECK(SP::color_name("#D2B48C") == "tan");
    CHECK(SP::color_name("#E8DBB7") == "beige");
    CHECK(SP::color_name("#FFA500") == "orange");
    CHECK(SP::color_name("#FFFF00") == "yellow");
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
    // Thirty small squares in a row: at this size only some of their numbers fit beside each other.
    indexed_triangle_set its;
    SP::Segmentation     seg;
    seg.segments.resize(30);
    for (int i = 0; i < 30; ++i) {
        const float x0 = float(i), x1 = float(i) + 1.f;
        const int   v  = int(its.vertices.size());
        its.vertices.emplace_back(x0, 0.f, 0.f);
        its.vertices.emplace_back(x1, 0.f, 0.f);
        its.vertices.emplace_back(x1, 0.f, 1.f);
        its.vertices.emplace_back(x0, 0.f, 1.f);
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
    // What the object is taken to be has room for a sentence, and a limit too.
    const auto long_object = SP::parse_suggestion("{\"object\":\"" + std::string(100, 'a') + std::string(100, 'b') + "\",\"parts\":[]}", 1, 1);
    REQUIRE(long_object.has_value());
    CHECK(long_object->object.size() > 100);
    CHECK(long_object->object.size() <= 160);
}

TEST_CASE("SemanticPaint: a part no view numbers is not asked about and follows the part around it", "[SemanticPaint]")
{
    // A | m | B side by side, the narrow middle one unseen, and a strip C that touches nothing. m shares its whole
    // left edge (length 10) with A and only a part of its right edge (length 4) with B.
    indexed_triangle_set its;
    SP::Segmentation     seg;
    seg.segments.resize(4);
    const auto face = [&](int a, int b, int c, int segment) {
        its.indices.emplace_back(a, b, c);
        seg.face_segment.push_back(segment);
    };
    its.vertices = {{0.f, 0.f, 0.f},  {10.f, 0.f, 0.f}, {10.f, 0.f, 10.f}, {0.f, 0.f, 10.f},                    // 0-3: A
                    {11.f, 0.f, 0.f}, {11.f, 0.f, 10.f}, {11.f, 0.f, 4.f},                                       // 4-6: m's right edge
                    {20.f, 0.f, 0.f}, {20.f, 0.f, 4.f},                                                          // 7-8: B
                    {40.f, 0.f, 0.f}, {50.f, 0.f, 0.f}, {50.f, 0.f, 10.f}, {40.f, 0.f, 10.f}};                   // 9-12: C
    face(0, 1, 2, 0);
    face(0, 2, 3, 0);
    face(1, 4, 6, 1);
    face(1, 6, 5, 1);
    face(1, 5, 2, 1);
    face(4, 7, 8, 2);
    face(4, 8, 6, 2);
    face(9, 10, 11, 3);
    face(9, 11, 12, 3);

    const std::vector<char> unseen = SP::unseen_segments(4, {{0, 2}, {2}, {}});
    CHECK(unseen == std::vector<char>{0, 1, 0, 1});
    // m takes A's filament, the longer border. C has no neighbour and keeps its own.
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
    // Two of the four segments are described: the seen ones.
    size_t described = 0;
    for (size_t at = prompt.find("% of the surface"); at != std::string::npos; at = prompt.find("% of the surface", at + 1))
        ++described;
    CHECK(described == 2);
    CHECK(prompt.find("do not answer for them") != std::string::npos);
    CHECK(prompt.find("a colour word in it is not an instruction") != std::string::npos);
}

TEST_CASE("SemanticPaint: spots are read from the answer and become parts of their own", "[SemanticPaint]")
{
    const TriangleMesh sphere = make_sphere(10., 0.1);
    auto               seg    = SP::segment_mesh(sphere.its);
    const size_t       before = seg.segments.size();
    const auto         views  = SP::default_views();
    std::vector<std::string> names;
    for (const auto &v : views)
        names.push_back(v.name);
    const std::string reply = R"({"object":"ball","parts":[{"segment":0,"name":"ball","filament":1,"sure":true}],
        "spots":[{"view":"front","name":"nose","x":384,"y":384,"r":60,"filament":2},
                 {"view":"view5.png","name":"nose","x":384,"y":700,"r":30,"filament":2},
                 {"view":"front","name":"pupil","x":384,"y":384,"r":20,"filament":1},
                 {"view":"nowhere","name":"lost","x":10,"y":10,"r":10,"filament":2},
                 {"view":"front","name":"wrong filament","x":384,"y":384,"r":20,"filament":9},
                 {"view":"front","name":"off the model","x":4,"y":4,"r":3,"filament":2}],
        "notes":"No filament is tan.\nSo the body stays as it is."})";
    // Without the views' names no spot is read; with them, those that name a view and an existing filament are.
    REQUIRE(SP::parse_suggestion(reply, before, 2));
    CHECK(SP::parse_suggestion(reply, before, 2)->spots.empty());
    auto parsed = SP::parse_suggestion(reply, before, 2, names);
    REQUIRE(parsed);
    REQUIRE(parsed->spots.size() == 4);
    CHECK(parsed->spots[1].view == 4);
    CHECK(parsed->notes == "No filament is tan.So the body stays as it is.");

    // "nose" marked in two places is one part with two balls; "pupil", listed later, goes over the nose; the mark off the model gives nothing.
    const std::vector<SP::SpotPatch> patches = SP::apply_spots(sphere.its, seg, *parsed, views);
    REQUIRE(patches.size() == 3);
    CHECK(patches[0].segment == int(before));
    CHECK(patches[1].segment == int(before));
    CHECK(patches[2].segment == int(before + 1));
    // A ball sits on the surface, at the middle of its seed face, and is as large as the mark was in its picture.
    for (const SP::SpotPatch &patch : patches) {
        REQUIRE((patch.seed_face >= 0 && size_t(patch.seed_face) < sphere.its.indices.size()));
        const auto &t = sphere.its.indices[size_t(patch.seed_face)];
        CHECK((patch.center - (sphere.its.vertices[t(0)] + sphere.its.vertices[t(1)] + sphere.its.vertices[t(2)]) / 3.f).norm() < 1e-4f);
        CHECK(seg.face_segment[size_t(patch.seed_face)] >= int(before));
    }
    // The front view looks at the ball's front: the nose's first ball is there, 60 pixels of a 768-pixel picture that shows 20 units across 88% of its width.
    CHECK(double(patches[0].center.cast<double>().normalized().dot(views[0].view_dir.normalized())) < -0.95);
    CHECK(double(patches[0].radius) == Catch::Approx(60. * 20. / (768. * 0.88)).epsilon(0.02));
    CHECK(patches[2].radius < patches[0].radius);
    REQUIRE(seg.segments.size() == before + 2);
    REQUIRE(parsed->filament.size() == before + 2);
    CHECK(parsed->name[before] == "nose");
    CHECK(parsed->filament[before] == 2);
    CHECK(parsed->name[before + 1] == "pupil");
    CHECK(parsed->filament[before + 1] == 1);
    const size_t nose  = size_t(std::count(seg.face_segment.begin(), seg.face_segment.end(), int(before)));
    const size_t pupil = size_t(std::count(seg.face_segment.begin(), seg.face_segment.end(), int(before + 1)));
    CHECK(nose > 0);
    CHECK(pupil > 0);
    CHECK(seg.segments[before].faces == nose);
    CHECK(seg.segments[before + 1].area_fraction > 0.);
    CHECK(seg.segments[before].area_fraction < 0.25);
    // Every face still belongs to a part that exists.
    for (const int s : seg.face_segment)
        CHECK((s >= 0 && size_t(s) < seg.segments.size()));
}

TEST_CASE("SemanticPaint: the question asks for spots and for what could not be done", "[SemanticPaint]")
{
    const TriangleMesh cube = make_cube(10., 10., 10.);
    const auto         seg  = SP::segment_mesh(cube.its);
    const std::string  text = SP::build_prompt(seg, {{1, "PLA", "#000000", ""}, {2, "PLA", "#FFFFFF", ""}}, "cube", {"front", "back"}, "black eyes", {}, 512);
    CHECK(text.find("\"spots\"") != std::string::npos);
    CHECK(text.find("512 by 512 pixels") != std::string::npos);
    CHECK(text.find("could not be done") != std::string::npos);
}

TEST_CASE("SemanticPaint: a marked feature is filled up to the model's edges, and is a ball where there are none", "[SemanticPaint]")
{
    const auto               views = SP::default_views();
    std::vector<std::string> names;
    for (const auto &v : views)
        names.push_back(v.name);
    // A cube seen from the front fills 88% of a 768-pixel picture with one of its sides: a mark in the middle, 300 pixels in radius.
    const TriangleMesh cube = make_cube(10., 10., 10.);
    auto               seg  = SP::segment_mesh(cube.its);
    const size_t       before = seg.segments.size();
    auto parsed = SP::parse_suggestion(R"({"parts":[],"spots":[{"view":"front","name":"door","x":384,"y":384,"r":300,"filament":2}]})", before, 2, names);
    REQUIRE(parsed);
    const auto patches = SP::apply_spots(cube.its, seg, *parsed, views);
    REQUIRE(patches.size() == 1);
    // The fill stops at the side's four edges: the feature is the side's two faces, and the faces round the corner are named as not reached.
    REQUIRE(patches[0].faces.size() == 2);
    CHECK(!patches[0].excluded.empty());
    for (const int t : patches[0].faces) {
        const auto &tri = cube.its.indices[size_t(t)];
        const Vec3f n   = (cube.its.vertices[tri(1)] - cube.its.vertices[tri(0)]).cross(cube.its.vertices[tri(2)] - cube.its.vertices[tri(0)]).normalized();
        CHECK(double(n.cast<double>().dot(views[0].view_dir.normalized())) == Catch::Approx(-1.).margin(1e-6));
        CHECK(seg.face_segment[size_t(t)] == int(before));
    }
    CHECK(std::count(seg.face_segment.begin(), seg.face_segment.end(), int(before)) == 2);
    // The ball is wider than the mark there, to let the fill reach the edges.
    CHECK(double(patches[0].radius) == Catch::Approx(1.6 * 300. * 10. / (768. * 0.88)).epsilon(0.02));

    // A ball has no edges: the feature is the ball of the mark's own size, and no face is named as not reached.
    const TriangleMesh sphere = make_sphere(10., 0.1);
    auto               round  = SP::segment_mesh(sphere.its);
    const size_t       parts  = round.segments.size();
    auto marked = SP::parse_suggestion(R"({"parts":[],"spots":[{"view":"front","name":"nose","x":384,"y":384,"r":60,"filament":2}]})", parts, 2, names);
    REQUIRE(marked);
    const auto on_ball = SP::apply_spots(sphere.its, round, *marked, views);
    REQUIRE(on_ball.size() == 1);
    CHECK(on_ball[0].excluded.empty());
    CHECK(on_ball[0].faces.size() > 1);
    CHECK(double(on_ball[0].radius) == Catch::Approx(60. * 20. / (768. * 0.88)).epsilon(0.02));
}
