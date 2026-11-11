#include <catch2/catch_all.hpp>

#include "slic3r/Utils/U1JobJournal.hpp"

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>

#include <string>
#include <thread>
#include <vector>

using namespace Slic3r;
namespace fs = boost::filesystem;

namespace {

// Each test gets its own journal folder and gives the default back afterwards.
struct ScratchJournal
{
    fs::path dir = fs::temp_directory_path() / fs::unique_path("u1-jobs-%%%%-%%%%");
    ScratchJournal()
    {
        fs::create_directories(dir);
        U1Jobs::set_journal_dir_for_tests(dir);
    }
    ~ScratchJournal()
    {
        U1Jobs::set_journal_dir_for_tests({});
        boost::system::error_code ec;
        fs::remove_all(dir, ec);
    }
    void write(const std::string &name, const std::string &text) const
    {
        boost::nowide::ofstream out((dir / name).string());
        out << text;
    }
};

const std::string SHA(64, 'a');

U1Jobs::Job new_job(const std::string &printer = "192.168.1.50")
{
    U1Jobs::Job job;
    job.id          = U1Jobs::new_id();
    job.printer     = printer;
    job.sha256      = SHA;
    job.created_ms  = U1Jobs::now_ms();
    job.remote_path = U1Jobs::remote_name("part.gcode", job.sha256, job.id);
    return job;
}

nlohmann::json idle_u1()
{
    return {{"print_stats", {{"state", "standby"}, {"filename", ""}}},
            {"print_task_config", {{"filament_exist", {true, true, false, true}}}},
            {"extruder", {{"nozzle_diameter", 0.4}}},
            {"extruder1", {{"nozzle_diameter", 0.4}}},
            {"extruder2", {{"nozzle_diameter", 0.4}}},
            {"extruder3", {{"nozzle_diameter", 0.6}}}};
}

} // namespace

TEST_CASE("A saved U1 job reads back as it was written", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    job = new_job();
    job.toolhead_map   = {0, 1, -1, 3};
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Uploading));
    job.start_sent = true;
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Starting, "sent"));

    const auto jobs = U1Jobs::load_all();
    REQUIRE(jobs.size() == 1);
    CHECK(jobs[0].id == job.id);
    CHECK(jobs[0].state == U1Jobs::State::Starting);
    CHECK(jobs[0].start_sent);
    CHECK(jobs[0].toolhead_map == job.toolhead_map);
    CHECK(jobs[0].message == "sent");
    REQUIRE(jobs[0].events.size() == 2);
    CHECK(jobs[0].events[0].first == "UPLOADING");
    CHECK(jobs[0].events[1].first == "STARTING");
    // No temp files are left behind.
    for (fs::directory_iterator it(scratch.dir), end; it != end; ++it)
        CHECK(it->path().extension() != ".tmp");
}

TEST_CASE("A job whose Start was sent but not settled holds its printer", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    job = new_job("192.168.1.50:7125");
    job.start_sent     = true;
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Starting));

    CHECK(U1Jobs::unresolved("http://192.168.1.50").has_value()); // web proxy and Moonraker port are one printer
    CHECK(U1Jobs::unresolved("192.168.1.50:7125").has_value());
    CHECK_FALSE(U1Jobs::unresolved("192.168.1.51").has_value());

    REQUIRE(U1Jobs::save(job, U1Jobs::State::Held));
    CHECK(U1Jobs::unresolved("192.168.1.50").has_value());
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Printing));
    CHECK_FALSE(U1Jobs::unresolved("192.168.1.50").has_value());
}

TEST_CASE("A failed write leaves the job in memory as it was", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    job = new_job();
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Verifying));
    // A file where the folder should be makes every write fail.
    const fs::path blocked = scratch.dir / "not-a-folder";
    { boost::nowide::ofstream out(blocked.string()); out << "x"; }
    U1Jobs::set_journal_dir_for_tests(blocked);
    job.start_sent = true;
    CHECK_FALSE(U1Jobs::save(job, U1Jobs::State::Starting));
    CHECK(job.state == U1Jobs::State::Verifying);
    CHECK(job.events.size() == 1);
    U1Jobs::set_journal_dir_for_tests(scratch.dir);
    // The record on disk still says the Start was not sent.
    const auto jobs = U1Jobs::load_all();
    REQUIRE(jobs.size() == 1);
    CHECK_FALSE(jobs[0].start_sent);
}

TEST_CASE("Records that cannot be trusted are reported, not guessed at", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    good = new_job();
    REQUIRE(U1Jobs::save(good, U1Jobs::State::Printing));
    const std::string id = U1Jobs::new_id();
    scratch.write(id + ".json", "{ this is not json");
    scratch.write(U1Jobs::new_id() + ".json", R"({"jobId":"x","printer":"","startSent":true,"state":"STARTING"})");
    // A well-formed record under the wrong file name is not trusted either.
    U1Jobs::Job other = new_job();
    other.start_sent  = true;
    REQUIRE(U1Jobs::save(other, U1Jobs::State::Starting));
    fs::rename(scratch.dir / (other.id + ".json"), scratch.dir / (U1Jobs::new_id() + ".json"));
    scratch.write("notes.txt", "ignored");

    std::vector<std::string> unreadable;
    const auto               jobs = U1Jobs::load_all(&unreadable);
    REQUIRE(jobs.size() == 1);
    CHECK(jobs[0].id == good.id);
    CHECK(unreadable.size() == 3);
    // A record with an empty printer can never hold every printer.
    CHECK_FALSE(U1Jobs::unresolved("192.168.1.50").has_value());
}

TEST_CASE("Updates re-read the record, so two writers cannot overwrite each other", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    job = new_job();
    job.start_sent     = true;
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Held));

    // Another thread settles the job while this thread still holds its stale copy.
    std::thread([&] {
        U1Jobs::update(job.id, [](U1Jobs::Job &record) -> std::optional<U1Jobs::State> { return U1Jobs::State::Failed; }, "not started",
                       "USER_SAID_NOT_STARTED");
    }).join();
    const auto result = U1Jobs::update(job.id, [](U1Jobs::Job &record) -> std::optional<U1Jobs::State> {
        return record.unresolved() ? std::optional<U1Jobs::State>(U1Jobs::State::Printing) : std::nullopt;
    });
    REQUIRE(result.has_value());
    CHECK(result->state == U1Jobs::State::Failed);
    CHECK(result->message == "not started");
    CHECK(result->events.back().first == "FAILED");
    CHECK(result->events[result->events.size() - 2].first == "USER_SAID_NOT_STARTED");
    CHECK_FALSE(U1Jobs::update(U1Jobs::new_id(), [](U1Jobs::Job &) -> std::optional<U1Jobs::State> { return U1Jobs::State::Failed; }).has_value());
}

TEST_CASE("Each job's file name on the printer is its own", "[U1Jobs]")
{
    const std::string a = U1Jobs::new_id(), b = U1Jobs::new_id();
    const std::string name_a = U1Jobs::remote_name("Cube 20mm (v2).gcode", SHA, a);
    CHECK(name_a != U1Jobs::remote_name("Cube 20mm (v2).gcode", SHA, b)); // same bytes, different job
    CHECK(name_a == "Cube 20mm _v2_-" + SHA.substr(0, 24) + "-" + a.substr(0, 8) + ".gcode");
    CHECK(U1Jobs::remote_name("", SHA, a) == "print-" + SHA.substr(0, 24) + "-" + a.substr(0, 8) + ".gcode");

    U1Jobs::Job job;
    job.id          = a;
    job.remote_path = "folder/" + name_a;
    CHECK(U1Jobs::is_job_file(job, "folder/" + name_a));
    CHECK(U1Jobs::is_job_file(job, name_a)); // Moonraker may report the leaf only
    CHECK_FALSE(U1Jobs::is_job_file(job, ""));
    CHECK_FALSE(U1Jobs::is_job_file(job, U1Jobs::remote_name("Cube 20mm (v2).gcode", SHA, b)));
}

TEST_CASE("Printer addresses are matched as the same U1 only when they can be", "[U1Jobs]")
{
    CHECK(U1Jobs::printer_key("HTTP://192.168.1.50:7125/printer/info") == "192.168.1.50:7125");
    CHECK(U1Jobs::same_printer("192.168.1.50", "http://192.168.1.50:7125"));
    CHECK(U1Jobs::same_printer("192.168.1.50:80", "192.168.1.50"));
    CHECK_FALSE(U1Jobs::same_printer("192.168.1.50:8080", "192.168.1.50"));
    CHECK_FALSE(U1Jobs::same_printer("127.0.0.1:47125", "127.0.0.1:47225")); // two simulators on one host
    CHECK_FALSE(U1Jobs::same_printer("192.168.1.50", "192.168.1.51"));
    CHECK_FALSE(U1Jobs::same_printer("", ""));
    CHECK(U1Jobs::same_printer("[fd00::1]:7125", "[fd00::1]"));
}

TEST_CASE("Only one send to a printer at a time", "[U1Jobs]")
{
    ScratchJournal scratch;
    {
        U1Jobs::PrinterLock first("192.168.1.50");
        REQUIRE(first.acquired());
        CHECK(U1Jobs::sending_now("http://192.168.1.50:7125"));
        U1Jobs::PrinterLock second("192.168.1.50:7125");
        CHECK_FALSE(second.acquired());
        U1Jobs::PrinterLock other("192.168.1.51");
        CHECK(other.acquired());
    }
    CHECK_FALSE(U1Jobs::sending_now("192.168.1.50"));
    U1Jobs::PrinterLock again("192.168.1.50");
    CHECK(again.acquired());
}

TEST_CASE("The prepared G-code says what it was sliced for", "[U1Jobs]")
{
    ScratchJournal scratch;
    scratch.write("a.gcode", "G1 X1\n; nozzle_diameter = 0.4,0.4,0.6,0.4\n; printer_model = Snapmaker U1\n");
    const auto facts = U1Jobs::read_gcode_facts(scratch.dir / "a.gcode");
    CHECK(facts.printer_model == "Snapmaker U1");
    CHECK(facts.nozzles == std::vector<double>{0.4, 0.4, 0.6, 0.4});
    CHECK(U1Jobs::read_gcode_facts(scratch.dir / "missing.gcode").printer_model.empty());
}

TEST_CASE("A Start is refused unless the printer shows it is ready for this file", "[U1Jobs]")
{
    using Kind = U1Jobs::Blocker::Kind;
    const U1Jobs::GcodeFacts u1{"Snapmaker U1", {0.4, 0.4, 0.4, 0.4}};
    const std::vector<int>   map{0, 1, -1, -1};

    CHECK(U1Jobs::start_blocker(u1, idle_u1(), map, true).kind == Kind::None);

    SECTION("a file sliced for another machine is refused even for upload only")
    {
        for (const char *model : {"Bambu Lab X1 Carbon", "Snapmaker U10", "Snapmaker J1"}) {
            const auto blocker = U1Jobs::start_blocker({model, {0.4}}, idle_u1(), map, false);
            CHECK(blocker.kind == Kind::WrongModel);
            CHECK(blocker.detail == model);
        }
        CHECK(U1Jobs::start_blocker({"snapmaker u1", {0.4}}, idle_u1(), map, false).kind == Kind::None);
        CHECK(U1Jobs::start_blocker({"", {}}, idle_u1(), map, false).kind == Kind::None);
    }
    SECTION("upload only needs no printer state")
    {
        CHECK(U1Jobs::start_blocker(u1, nlohmann::json(), map, false).kind == Kind::None);
    }
    SECTION("no status, or a status without a print state, is a refusal")
    {
        CHECK(U1Jobs::start_blocker(u1, nlohmann::json(), map, true).kind == Kind::PrinterUnreachable);
        CHECK(U1Jobs::start_blocker(u1, nlohmann::json::object(), map, true).kind == Kind::PrinterUnreachable);
        auto status = idle_u1();
        status["print_stats"]["state"] = 3;
        CHECK(U1Jobs::start_blocker(u1, status, map, true).kind == Kind::PrinterUnreachable);
    }
    SECTION("only an idle printer is started")
    {
        for (const char *state : {"complete", "cancelled", "standby"}) {
            auto status = idle_u1();
            status["print_stats"]["state"] = state;
            CHECK(U1Jobs::start_blocker(u1, status, map, true).kind == Kind::None);
        }
        for (const char *state : {"printing", "paused", "error", "ready", ""}) {
            auto status = idle_u1();
            status["print_stats"]["state"] = state;
            const auto blocker = U1Jobs::start_blocker(u1, status, map, true);
            CHECK(blocker.kind == Kind::PrinterNotIdle);
            CHECK(blocker.detail == state);
        }
    }
    SECTION("a mapped toolhead must be reported as loaded")
    {
        const auto empty = U1Jobs::start_blocker(u1, idle_u1(), {0, 2}, true);
        CHECK(empty.kind == Kind::ToolheadEmpty);
        CHECK(empty.filament == 1);
        CHECK(empty.toolhead == 2);

        auto status = idle_u1();
        status["print_task_config"]["filament_exist"] = {1, 1, 0, 1}; // not booleans: not evidence
        CHECK(U1Jobs::start_blocker(u1, status, map, true).kind == Kind::ToolheadUnknown);
        status["print_task_config"].erase("filament_exist");
        CHECK(U1Jobs::start_blocker(u1, status, map, true).kind == Kind::ToolheadUnknown);
        status.erase("print_task_config");
        CHECK(U1Jobs::start_blocker(u1, status, map, true).kind == Kind::ToolheadUnknown);
        CHECK(U1Jobs::start_blocker(u1, idle_u1(), {7}, true).kind == Kind::ToolheadUnknown);
    }
    SECTION("the nozzle a filament was sliced for must be the one fitted")
    {
        const auto mismatch = U1Jobs::start_blocker(u1, idle_u1(), {3}, true); // filament 1 sliced for 0.4, toolhead 4 has 0.6
        CHECK(mismatch.kind == Kind::NozzleMismatch);
        CHECK(mismatch.sliced == Catch::Approx(0.4));
        CHECK(mismatch.fitted == Catch::Approx(0.6));
        CHECK(U1Jobs::start_blocker({"Snapmaker U1", {0.6}}, idle_u1(), {3}, true).kind == Kind::None);

        auto status = idle_u1();
        status["extruder1"].erase("nozzle_diameter");
        CHECK(U1Jobs::start_blocker(u1, status, map, true).kind == Kind::NozzleUnknown);
        // A filament beyond the sliced nozzle list has no nozzle of its own to compare.
        CHECK(U1Jobs::start_blocker({"Snapmaker U1", {0.4}}, idle_u1(), {0, 3}, true).kind == Kind::None);
    }
}
