#include <catch2/catch_all.hpp>

#include "libslic3r/SpoolTags.hpp"

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>

#include <string>

using namespace Slic3r;
using nlohmann::json;
namespace fs = boost::filesystem;

namespace {

struct ScratchFile
{
    fs::path path = fs::temp_directory_path() / fs::unique_path("spool-tags-%%%%-%%%%.json");
    ~ScratchFile()
    {
        boost::system::error_code ec;
        fs::remove(path, ec);
        fs::remove(path.string() + ".tmp", ec);
    }
};

} // namespace

TEST_CASE("A U1 toolhead's tag is named by the bytes of its card", "[SpoolTags]")
{
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", {112, 73, 143, 226}}, {"CARD_TYPE", "M1"}}) == "70498FE2");
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", {4, 111, 254, 166, 141, 38, 129}}}) == "046FFEA68D2681");
}

TEST_CASE("A toolhead that read no tag has no tag identity", "[SpoolTags]")
{
    // The printer reports 0, not a list, when no card was read.
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", 0}}).empty());
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", json::array()}}).empty());
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", {0, 0, 0, 0}}}).empty());
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", {112, 300}}}).empty());
    CHECK(SpoolTags::u1_card_uid(json{{"CARD_UID", {112, "x"}}}).empty());
    CHECK(SpoolTags::u1_card_uid(json::object()).empty());
    CHECK(SpoolTags::u1_card_uid(json(3)).empty());
}

TEST_CASE("A tag identity is compared without regard to case or separators", "[SpoolTags]")
{
    CHECK(SpoolTags::normalized("04:6f:fe:a6") == "046FFEA6");
    CHECK(SpoolTags::normalized(" 046FFEA6 ") == "046FFEA6");
    CHECK(SpoolTags::normalized("").empty());
    CHECK(SpoolTags::normalized("0000").empty());
    CHECK(SpoolTags::normalized(std::string(65, 'A')).empty());
}

TEST_CASE("A choice made for a tag is found again for that tag and printer model only", "[SpoolTags]")
{
    SpoolTags::Memory memory;
    REQUIRE(memory.remember("70498FE2", "Snapmaker U1", "Polymaker PLA @U1", 10));
    CHECK(memory.preset_for("70498fe2", "Snapmaker U1") == "Polymaker PLA @U1");
    CHECK(memory.preset_for("70498FE2", "Elegoo Centauri Carbon (COSMOS AFC)").empty());
    CHECK(memory.preset_for("AABBCCDD", "Snapmaker U1").empty());
    // The same spool on another printer model keeps a choice of its own.
    REQUIRE(memory.remember("70498FE2", "Elegoo Centauri Carbon (COSMOS AFC)", "Polymaker PLA @ECC", 11));
    CHECK(memory.preset_for("70498FE2", "Snapmaker U1") == "Polymaker PLA @U1");
    CHECK(memory.preset_for("70498FE2", "Elegoo Centauri Carbon (COSMOS AFC)") == "Polymaker PLA @ECC");
    CHECK(memory.size() == 1);
}

TEST_CASE("Nothing is remembered without a tag, a printer model or a preset", "[SpoolTags]")
{
    SpoolTags::Memory memory;
    CHECK_FALSE(memory.remember("", "Snapmaker U1", "Preset", 1));
    CHECK_FALSE(memory.remember("0000", "Snapmaker U1", "Preset", 1));
    CHECK_FALSE(memory.remember("70498FE2", "", "Preset", 1));
    CHECK_FALSE(memory.remember("70498FE2", "Snapmaker U1", "", 1));
    CHECK(memory.size() == 0);
}

TEST_CASE("The tag memory reads back from its file as it was written", "[SpoolTags]")
{
    ScratchFile       file;
    SpoolTags::Memory memory;
    REQUIRE(memory.remember("70498FE2", "Snapmaker U1", "Polymaker PLA @U1", 10));
    REQUIRE(memory.remember("046FFEA68D2681", "Elegoo Centauri Carbon (COSMOS AFC)", "ABCWavePrint PLA", 20));
    REQUIRE(memory.save(file.path.string()));
    const SpoolTags::Memory read = SpoolTags::Memory::load(file.path.string());
    CHECK(read.size() == 2);
    CHECK(read.preset_for("70498FE2", "Snapmaker U1") == "Polymaker PLA @U1");
    CHECK(read.preset_for("046FFEA68D2681", "Elegoo Centauri Carbon (COSMOS AFC)") == "ABCWavePrint PLA");
}

TEST_CASE("A missing or damaged tag memory file is an empty memory", "[SpoolTags]")
{
    ScratchFile file;
    CHECK(SpoolTags::Memory::load(file.path.string()).size() == 0);
    {
        boost::nowide::ofstream out(file.path.string());
        out << "{ not json";
    }
    CHECK(SpoolTags::Memory::load(file.path.string()).size() == 0);
    {
        boost::nowide::ofstream out(file.path.string(), std::ios::trunc);
        out << R"({"tags": {"70498FE2": {"presets": {"Snapmaker U1": 5}}, "": {"presets": {"M": "P"}}, "AABB": "x"}})";
    }
    CHECK(SpoolTags::Memory::load(file.path.string()).size() == 0);
}

TEST_CASE("The tag memory keeps its newest entries when it is full", "[SpoolTags]")
{
    SpoolTags::Memory memory;
    for (size_t i = 0; i < SpoolTags::Memory::CAPACITY + 3; ++i) {
        char id[16];
        std::snprintf(id, sizeof(id), "A%07zu", i);
        REQUIRE(memory.remember(id, "Model", "Preset", static_cast<long long>(i) + 1));
    }
    CHECK(memory.size() == SpoolTags::Memory::CAPACITY);
    CHECK(memory.preset_for("A0000000", "Model").empty());
    CHECK(memory.preset_for("A0000002", "Model").empty());
    CHECK(memory.preset_for("A0000003", "Model") == "Preset");
}
