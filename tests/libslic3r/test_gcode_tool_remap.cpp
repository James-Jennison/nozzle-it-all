#include <catch2/catch_test_macros.hpp>

#include "libslic3r/GCodeToolRemap.hpp"

#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>
#include <boost/nowide/fstream.hpp>

#include <ios>
#include <iterator>
#include <string>
#include <vector>

using namespace Slic3r;
using GCodeToolRemap::LineResult;

namespace {

std::string remapped(std::string line, const std::vector<int> &map, LineResult expected)
{
    CHECK(GCodeToolRemap::remap_line(line, map) == expected);
    return line;
}

std::string read_all(const boost::filesystem::path &path)
{
    boost::nowide::ifstream in(path.string(), std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

void write_all(const boost::filesystem::path &path, const std::string &text)
{
    boost::nowide::ofstream out(path.string(), std::ios::binary | std::ios::trunc);
    out.write(text.data(), static_cast<std::streamsize>(text.size()));
}

} // namespace

TEST_CASE("A tool change line gets the mapped tool and keeps everything else", "[GCodeToolRemap]")
{
    const std::vector<int> map{2, 0, 3, 1};
    CHECK(remapped("T0", map, LineResult::Rewritten) == "T2");
    CHECK(remapped("T1 PURGE_LENGTH=120.5", map, LineResult::Rewritten) == "T0 PURGE_LENGTH=120.5");
    CHECK(remapped("  T3 ; change filament", map, LineResult::Rewritten) == "  T1 ; change filament");
    CHECK(remapped("T2\r", map, LineResult::Rewritten) == "T3\r");
    CHECK(remapped("t1", map, LineResult::Rewritten) == "t0");
    // A filament already on its own tool still counts as a tool line.
    CHECK(remapped("T1", {0, 1}, LineResult::Rewritten) == "T1");
    // A two-digit tool number.
    CHECK(remapped("T1", {0, 12}, LineResult::Rewritten) == "T12");
}

TEST_CASE("The start macro's TOOL parameter gets the mapped tool", "[GCodeToolRemap]")
{
    const std::vector<int> map{2, 0, 3, 1};
    CHECK(remapped("PRINT_START EXTRUDER=220 BED=60 CHAMBER=0 TOOL=1", map, LineResult::Rewritten) == "PRINT_START EXTRUDER=220 BED=60 CHAMBER=0 TOOL=0");
    CHECK(remapped("PRINT_START TOOL=0 BED=60 ; start", map, LineResult::Rewritten) == "PRINT_START TOOL=2 BED=60 ; start");
    CHECK(remapped("print_start tool=3", map, LineResult::Rewritten) == "print_start tool=1");
    CHECK(remapped("PRINT_START EXTRUDER=220 BED=60", map, LineResult::Unchanged) == "PRINT_START EXTRUDER=220 BED=60");
    CHECK(remapped("PRINT_START TOOL=1 TOOL=2", map, LineResult::NotUnderstood) == "PRINT_START TOOL=1 TOOL=2");
    CHECK(remapped("PRINT_START TOOL=T1", map, LineResult::NotUnderstood) == "PRINT_START TOOL=T1");
    CHECK(remapped("PRINT_START_EXTRA TOOL=1", map, LineResult::NotUnderstood) == "PRINT_START_EXTRA TOOL=1");
}

TEST_CASE("Lines that name no tool are left alone", "[GCodeToolRemap]")
{
    const std::vector<int> map{1, 0};
    for (const char *text : {"", "   ", "; T1", ";T0 PURGE_LENGTH=5", "G1 X10 Y20 E0.5 F3000", "M104 S220", "M73 P10 R5", "TIMELAPSE_TAKE_FRAME",
                             "SET_PRINT_STATS_INFO TOTAL_LAYER=209", "G1 X1 ; T1 is next", "EXCLUDE_OBJECT_START NAME=T1_part", "M106 S255", "PRINT_END"})
        CHECK(remapped(text, map, LineResult::Unchanged) == text);
}

TEST_CASE("A tool named in a form that is not rewritten is refused, and so is a filament without a tool", "[GCodeToolRemap]")
{
    const std::vector<int> map{1, 0, GCodeToolRemap::UNMAPPED};
    CHECK(remapped("M104 S220 T1", map, LineResult::NotUnderstood) == "M104 S220 T1");
    CHECK(remapped("M109 T0 S220", map, LineResult::NotUnderstood) == "M109 T0 S220");
    CHECK(remapped("T0 T1", map, LineResult::NotUnderstood) == "T0 T1");
    // A source chosen past the tool numbers, or a change to which lane answers to a number.
    CHECK(remapped("TOOL_LOAD LANE=CANVAS_2", map, LineResult::NotUnderstood) == "TOOL_LOAD LANE=CANVAS_2");
    CHECK(remapped("set_map lane=CANVAS_1 MAP=T3", map, LineResult::NotUnderstood) == "set_map lane=CANVAS_1 MAP=T3");
    CHECK(remapped("ACTIVATE_EXTRUDER EXTRUDER=extruder1", map, LineResult::NotUnderstood) == "ACTIVATE_EXTRUDER EXTRUDER=extruder1");
    CHECK(remapped("AFC_SWAP_MAPPING FROM=CANVAS_1 TO=CANVAS_2", map, LineResult::NotUnderstood) == "AFC_SWAP_MAPPING FROM=CANVAS_1 TO=CANVAS_2");
    CHECK(remapped("TOOL_UNLOAD", map, LineResult::Unchanged) == "TOOL_UNLOAD");
    CHECK(remapped("PRINT_END", map, LineResult::Unchanged) == "PRINT_END");
    CHECK(remapped("T1X", map, LineResult::NotUnderstood) == "T1X");
    CHECK(remapped("T12345", map, LineResult::NotUnderstood) == "T12345");
    CHECK(remapped("CHANGE_TOOL TOOL=1", map, LineResult::NotUnderstood) == "CHANGE_TOOL TOOL=1");
    CHECK(remapped("T2", map, LineResult::NoTool) == "T2");
    CHECK(remapped("T7", map, LineResult::NoTool) == "T7");
    CHECK(remapped("PRINT_START TOOL=2", map, LineResult::NoTool) == "PRINT_START TOOL=2");
}

TEST_CASE("A map that keeps every used filament on its own tool is the identity", "[GCodeToolRemap]")
{
    CHECK(GCodeToolRemap::is_identity({}));
    CHECK(GCodeToolRemap::is_identity({0, 1, 2, 3}));
    CHECK(GCodeToolRemap::is_identity({0, GCodeToolRemap::UNMAPPED, 2}));
    CHECK_FALSE(GCodeToolRemap::is_identity({1, 0}));
    CHECK_FALSE(GCodeToolRemap::is_identity({0, 1, 3}));
}

TEST_CASE("A file is copied with only its tool numbers changed", "[GCodeToolRemap]")
{
    const boost::filesystem::path dir = boost::filesystem::temp_directory_path() / boost::filesystem::unique_path("nia-remap-%%%%%%%%");
    boost::filesystem::create_directories(dir);
    const boost::filesystem::path source = dir / "in.gcode", target = dir / "out.gcode";
    const std::vector<int> map{3, 2, 1, 0};

    SECTION("mixed line endings and no final newline are kept")
    {
        write_all(source, "; HEADER T1\nPRINT_START BED=60 TOOL=0\r\nG1 X1\nT1 PURGE_LENGTH=90\n; T2\nT0\nG1 X2 ; end");
        const GCodeToolRemap::Result r = GCodeToolRemap::remap_file(source.string(), target.string(), map);
        REQUIRE(r.ok);
        CHECK(r.tool_lines == 3);
        CHECK(r.changed_lines == 3);
        CHECK(read_all(target) == "; HEADER T1\nPRINT_START BED=60 TOOL=3\r\nG1 X1\nT2 PURGE_LENGTH=90\n; T2\nT3\nG1 X2 ; end");
    }
    SECTION("the identity map gives the same bytes")
    {
        const std::string text = "PRINT_START TOOL=1\nT1\nG1 X1\nT0\n";
        write_all(source, text);
        const GCodeToolRemap::Result r = GCodeToolRemap::remap_file(source.string(), target.string(), {0, 1});
        REQUIRE(r.ok);
        CHECK(r.tool_lines == 3);
        CHECK(r.changed_lines == 0);
        CHECK(read_all(target) == text);
    }
    SECTION("a refusal names the line and leaves no file")
    {
        write_all(source, "T0\nG1 X1\nM104 S200 T1\nT1\n");
        const GCodeToolRemap::Result r = GCodeToolRemap::remap_file(source.string(), target.string(), map);
        CHECK_FALSE(r.ok);
        CHECK(r.failed_line == 3);
        CHECK(r.failure == LineResult::NotUnderstood);
        CHECK(r.failed_text == "M104 S200 T1");
        CHECK_FALSE(boost::filesystem::exists(target));
    }
    SECTION("a file that cannot be read is an error, not an empty result")
    {
        const GCodeToolRemap::Result r = GCodeToolRemap::remap_file((dir / "missing.gcode").string(), target.string(), map);
        CHECK_FALSE(r.ok);
        CHECK_FALSE(r.io_error.empty());
    }
    boost::filesystem::remove_all(dir);
}
