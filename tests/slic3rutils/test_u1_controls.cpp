#include <catch2/catch_all.hpp>

#include "slic3r/Utils/U1Controls.hpp"

#include <nlohmann/json.hpp>

using namespace Slic3r;
using U1Controls::ResumeRefusal;

namespace {

nlohmann::json stats(const char *state, const char *filename) { return {{"print_stats", {{"state", state}, {"filename", filename}}}}; }

} // namespace

TEST_CASE("A Resume is sent only to a printer that says it is paused on the print the button was shown for", "[U1Controls]")
{
    const std::string shown = "bracket-0123-abcd.gcode";

    const auto paused = U1Controls::check_resume(stats("paused", "bracket-0123-abcd.gcode"), shown);
    CHECK(paused);
    CHECK(paused.refusal == ResumeRefusal::None);
    CHECK(paused.printer_state == "paused");

    // Moonraker may name the file with its folder.
    CHECK(U1Controls::check_resume(stats("paused", "jobs/bracket-0123-abcd.gcode"), shown));
    CHECK(U1Controls::check_resume(stats("paused", "bracket-0123-abcd.gcode"), "jobs/" + shown));

    for (const char *state : {"printing", "standby", "complete", "cancelled", "error", ""}) {
        const auto check = U1Controls::check_resume(stats(state, "bracket-0123-abcd.gcode"), shown);
        CHECK_FALSE(check);
        CHECK(check.refusal == ResumeRefusal::NotPaused);
        CHECK(check.printer_state == state);
    }

    const auto other = U1Controls::check_resume(stats("paused", "another.gcode"), shown);
    CHECK(other.refusal == ResumeRefusal::OtherFile);
    CHECK(other.filename == "another.gcode");
    // Nothing to compare with is not a match.
    CHECK(U1Controls::check_resume(stats("paused", ""), shown).refusal == ResumeRefusal::OtherFile);
    CHECK(U1Controls::check_resume(stats("paused", "bracket-0123-abcd.gcode"), "").refusal == ResumeRefusal::OtherFile);
}

TEST_CASE("What the printer does not report refuses a Resume", "[U1Controls]")
{
    const std::string shown = "bracket.gcode";
    CHECK(U1Controls::check_resume(nlohmann::json(), shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume(nlohmann::json::object(), shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume({{"print_stats", "paused"}}, shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume({{"print_stats", {{"filename", "bracket.gcode"}}}}, shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume({{"print_stats", {{"state", 3}, {"filename", "bracket.gcode"}}}}, shown).refusal == ResumeRefusal::Unreachable);
    // A state without a file name cannot be tied to the shown print.
    CHECK(U1Controls::check_resume({{"print_stats", {{"state", "paused"}}}}, shown).refusal == ResumeRefusal::OtherFile);
}
