#include <catch2/catch_all.hpp>

#include "slic3r/Utils/U1JobJournal.hpp"
#include "libslic3r/U1PrintTask.hpp"

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

TEST_CASE("The toolheads suggested for like filaments pass the readiness rules", "[U1Jobs]")
{
    using Kind = U1Jobs::Blocker::Kind;
    // The idle printer of these tests has toolhead 3 empty. Four filaments that look alike must not be
    // suggested onto it, and the suggestion must be one the Start check accepts as far as loading goes.
    const nlohmann::json status = idle_u1();
    const auto           state  = U1PrintTask::parse_printer_state(status["print_task_config"]);
    REQUIRE(state.toolheads.size() == 4);
    REQUIRE_FALSE(state.toolheads[2].present);
    const std::vector<U1PrintTask::Filament> plate(4, U1PrintTask::Filament{true, "", "", "PLA", "", "#26A69A"});
    const std::vector<int>                   suggested = U1PrintTask::suggest_mapping(plate, state.toolheads);
    for (int toolhead : suggested)
        CHECK(toolhead != 2);
    const auto blocker = U1Jobs::start_blocker({"Snapmaker U1", {}}, status, suggested, true);
    CHECK(blocker.kind != Kind::ToolheadEmpty);
    CHECK(blocker.kind != Kind::ToolheadUnknown);
}

TEST_CASE("A job's outcome is taken from the printer only when it names that job's file", "[U1Jobs]")
{
    U1Jobs::Job job = new_job();
    const auto  stats = [](const std::string &state, const std::string &file) {
        return nlohmann::json{{"print_stats", {{"state", state}, {"filename", file}}}};
    };
    CHECK(U1Jobs::outcome_from_status(job, stats("complete", job.remote_path)) == U1Jobs::State::Completed);
    CHECK(U1Jobs::outcome_from_status(job, stats("cancelled", job.remote_path)) == U1Jobs::State::Cancelled);
    CHECK(U1Jobs::outcome_from_status(job, stats("error", "gcodes/" + job.remote_path)) == U1Jobs::State::Failed);
    // Still running, another job's file, no file, no answer: nothing is known.
    CHECK_FALSE(U1Jobs::outcome_from_status(job, stats("printing", job.remote_path)));
    CHECK_FALSE(U1Jobs::outcome_from_status(job, stats("paused", job.remote_path)));
    CHECK_FALSE(U1Jobs::outcome_from_status(job, stats("complete", "someone-elses.gcode")));
    CHECK_FALSE(U1Jobs::outcome_from_status(job, stats("standby", "")));
    CHECK_FALSE(U1Jobs::outcome_from_status(job, nlohmann::json()));
    CHECK_FALSE(U1Jobs::outcome_from_status(job, nlohmann::json{{"print_stats", {{"state", 3}}}}));
}

TEST_CASE("The printer's job history gives a job its times and its outcome", "[U1Jobs]")
{
    U1Jobs::Job          job     = new_job();
    const nlohmann::json history = {{"count", 3},
                                    {"jobs",
                                     {{{"filename", "other.gcode"}, {"status", "completed"}, {"start_time", 10.0}, {"end_time", 20.0}},
                                      {{"filename", job.remote_path}, {"status", "completed"}, {"start_time", 1763000000.5}, {"end_time", 1763003600.25}},
                                      {{"filename", 7}}}}};
    const auto record = U1Jobs::record_from_history(job, history);
    REQUIRE(record);
    CHECK(record->outcome == U1Jobs::State::Completed);
    CHECK(record->started_ms == 1763000000500LL);
    CHECK(record->ended_ms == 1763003600250LL);

    // A job the history still lists as running has a start and no end, whatever the entry carries.
    const nlohmann::json running = {{"jobs", {{{"filename", job.remote_path}, {"status", "in_progress"}, {"start_time", 5.0}, {"end_time", 9.0}}}}};
    const auto           open    = U1Jobs::record_from_history(job, running);
    REQUIRE(open);
    CHECK_FALSE(open->outcome);
    CHECK(open->started_ms == 5000);
    CHECK(open->ended_ms == 0);

    const nlohmann::json shutdown = {{"jobs", {{{"filename", job.remote_path}, {"status", "klippy_shutdown"}, {"end_time", nullptr}}}}};
    const auto           failed   = U1Jobs::record_from_history(job, shutdown);
    REQUIRE(failed);
    CHECK(failed->outcome == U1Jobs::State::Failed);
    CHECK(failed->ended_ms == 0);

    CHECK_FALSE(U1Jobs::record_from_history(job, nlohmann::json{{"jobs", nlohmann::json::array()}}));
    CHECK_FALSE(U1Jobs::record_from_history(job, nlohmann::json()));
}

TEST_CASE("Only a job recorded as printing takes an outcome, and a known time is not replaced", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    job = new_job();
    job.start_sent     = true;
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Printing));
    REQUIRE(U1Jobs::printing().size() == 1);

    // Nothing known: nothing written.
    CHECK_FALSE(U1Jobs::record_outcome(job.id, {}, "nothing"));

    // A start time alone is kept while the job runs; an end time is not taken without an outcome.
    U1Jobs::PrinterRecord running;
    running.started_ms = 1000;
    running.ended_ms   = 2000;
    auto written       = U1Jobs::record_outcome(job.id, running, "running");
    REQUIRE(written);
    CHECK(written->state == U1Jobs::State::Printing);
    CHECK(written->started_ms == 1000);
    CHECK(written->ended_ms == 0);

    U1Jobs::PrinterRecord done;
    done.outcome    = U1Jobs::State::Completed;
    done.started_ms = 1500;
    done.ended_ms   = 9000;
    written         = U1Jobs::record_outcome(job.id, done, "Outcome read from the printer.");
    REQUIRE(written);
    CHECK(written->state == U1Jobs::State::Completed);
    CHECK(written->started_ms == 1000);
    CHECK(written->ended_ms == 9000);
    CHECK(written->message == "Outcome read from the printer.");
    CHECK(U1Jobs::printing().empty());

    // A later, different reading changes nothing.
    U1Jobs::PrinterRecord other;
    other.outcome  = U1Jobs::State::Failed;
    other.ended_ms = 9999;
    CHECK_FALSE(U1Jobs::record_outcome(job.id, other, "late"));
    const auto jobs = U1Jobs::load_all();
    REQUIRE(jobs.size() == 1);
    CHECK(jobs[0].state == U1Jobs::State::Completed);
    CHECK(jobs[0].ended_ms == 9000);

    // A held job is never settled by the watcher's reading.
    U1Jobs::Job held = new_job("192.168.1.51");
    held.start_sent  = true;
    REQUIRE(U1Jobs::save(held, U1Jobs::State::Held));
    CHECK_FALSE(U1Jobs::record_outcome(held.id, done, "no"));
    CHECK(U1Jobs::unresolved("192.168.1.51"));
}

TEST_CASE("A job keeps where it came from and the person's own verdict, apart from the machine's state", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    job          = new_job();
    job.origin.file_name        = "bracket.gcode";
    job.origin.project          = "/home/user/bracket.3mf";
    job.origin.project_saved_ms = 1763000000000LL;
    job.origin.project_changed  = true;
    job.origin.plate            = 2;
    job.origin.plate_name       = "lids";
    job.start_sent              = true;
    REQUIRE(U1Jobs::save(job, U1Jobs::State::Completed));

    auto judged = U1Jobs::set_assessment(job.id, "partial");
    REQUIRE(judged);
    CHECK(judged->state == U1Jobs::State::Completed);
    CHECK_FALSE(U1Jobs::set_assessment(job.id, "great"));

    const auto jobs = U1Jobs::history("192.168.1.50:7125");
    REQUIRE(jobs.size() == 1);
    CHECK(jobs[0].assessment == "partial");
    CHECK(jobs[0].state == U1Jobs::State::Completed);
    CHECK(jobs[0].origin.file_name == "bracket.gcode");
    CHECK(jobs[0].origin.project == "/home/user/bracket.3mf");
    CHECK(jobs[0].origin.project_saved_ms == 1763000000000LL);
    CHECK(jobs[0].origin.project_changed);
    CHECK(jobs[0].origin.plate == 2);
    CHECK(jobs[0].origin.plate_name == "lids");

    // Newest first, this printer only.
    U1Jobs::Job later = new_job();
    later.created_ms  = job.created_ms + 5000;
    REQUIRE(U1Jobs::save(later, U1Jobs::State::Printing));
    U1Jobs::Job elsewhere = new_job("192.168.1.99");
    REQUIRE(U1Jobs::save(elsewhere, U1Jobs::State::Printing));
    const auto both = U1Jobs::history("192.168.1.50");
    REQUIRE(both.size() == 2);
    CHECK(both[0].id == later.id);

    // A record written before these fields existed still loads.
    CHECK(U1Jobs::set_assessment(job.id, ""));
    CHECK(U1Jobs::history("192.168.1.50")[1].assessment.empty());
}

TEST_CASE("Prints the printer lists that this app did not send are kept apart and remembered", "[U1Jobs]")
{
    ScratchJournal scratch;
    U1Jobs::Job    ours = new_job();
    REQUIRE(U1Jobs::save(ours, U1Jobs::State::Completed));

    const nlohmann::json history = {{"jobs",
                                     {{{"job_id", "00000A"}, {"filename", ours.remote_path}, {"status", "completed"}, {"start_time", 100.0}, {"end_time", 200.0}},
                                      {{"job_id", "00000B"}, {"filename", "benchy.gcode"}, {"status", "completed"}, {"start_time", 300.0}, {"end_time", 400.0}},
                                      {{"job_id", "00000C"}, {"filename", "vase.gcode"}, {"status", "in_progress"}, {"start_time", 500.0}, {"end_time", 999.0}},
                                      {{"filename", "no-id.gcode"}}}}};
    const auto outside = U1Jobs::external_from_history(history, U1Jobs::load_all());
    REQUIRE(outside.size() == 2);
    CHECK(outside[0].printer_job_id == "00000B");
    CHECK(outside[0].ended_ms == 400000);
    CHECK(outside[1].file_name == "vase.gcode");
    CHECK(outside[1].ended_ms == 0); // still running: no end

    REQUIRE(U1Jobs::remember_external("192.168.1.50", outside));
    REQUIRE(U1Jobs::set_external_assessment("192.168.1.50:7125", "00000B", "failed"));
    CHECK_FALSE(U1Jobs::set_external_assessment("192.168.1.50", "00000B", "meh"));
    CHECK_FALSE(U1Jobs::set_external_assessment("192.168.1.50", "nope", "success"));

    // The running print ends; the printer has forgotten the older one. Both stay, the verdict too.
    U1Jobs::ExternalPrint ended = outside[1];
    ended.status   = "cancelled";
    ended.ended_ms = 600000;
    REQUIRE(U1Jobs::remember_external("192.168.1.50", {ended}));
    const auto kept = U1Jobs::external_prints("192.168.1.50");
    REQUIRE(kept.size() == 2);
    CHECK(kept[0].printer_job_id == "00000C"); // newest first
    CHECK(kept[0].status == "cancelled");
    CHECK(kept[0].ended_ms == 600000);
    CHECK(kept[1].assessment == "failed");

    // Another printer has its own list, and the job records are not disturbed.
    CHECK(U1Jobs::external_prints("192.168.1.51").empty());
    std::vector<std::string> unreadable;
    CHECK(U1Jobs::load_all(&unreadable).size() == 1);
    CHECK(unreadable.empty());
}

TEST_CASE("The newest jobs keep their prepared file, and a job sent again is recorded as such", "[U1Jobs]")
{
    ScratchJournal scratch;
    const fs::path prepared = scratch.dir / "prepared.tmp";
    scratch.write("prepared.tmp", "G28\nG1 X10\n");

    // Twelve jobs, each with its own file contents by name: only the newest ten keep a file.
    std::vector<U1Jobs::Job> jobs;
    for (int i = 0; i < 12; ++i) {
        U1Jobs::Job job = new_job();
        job.sha256      = std::string(62, 'a') + (i < 10 ? "0" : "") + std::to_string(i);
        job.created_ms  = 1763000000000LL + i * 1000;
        job.origin.filaments = {{true, "Generic PLA", "PLA", "#FF0000", 12.5}, {false, "", "", "", 0.}};
        job.origin.estimated_time = "1h 2m";
        REQUIRE(U1Jobs::save(job, U1Jobs::State::Completed));
        REQUIRE(U1Jobs::keep_file(job, prepared));
        jobs.push_back(job);
    }
    CHECK_FALSE(fs::exists(U1Jobs::kept_file(jobs[0])));
    CHECK_FALSE(fs::exists(U1Jobs::kept_file(jobs[1])));
    for (int i = 2; i < 12; ++i)
        CHECK(fs::exists(U1Jobs::kept_file(jobs[i])));
    // The source is copied, not moved, and the records are still all there.
    CHECK(fs::exists(prepared));
    CHECK(U1Jobs::history("192.168.1.50").size() == 12);

    // Sent again: a new job with the same bytes. It shares the kept file and names the job it came from.
    U1Jobs::Job again       = new_job();
    again.sha256            = jobs[5].sha256;
    again.created_ms        = 1763000100000LL;
    again.origin            = jobs[5].origin;
    again.origin.reprint_of = jobs[5].id;
    REQUIRE(U1Jobs::save(again, U1Jobs::State::Printing));
    REQUIRE(U1Jobs::keep_file(again, prepared));
    CHECK(U1Jobs::kept_file(again) == U1Jobs::kept_file(jobs[5]));
    CHECK(fs::exists(U1Jobs::kept_file(again)));
    CHECK_FALSE(fs::exists(U1Jobs::kept_file(jobs[2]))); // the eleventh newest job with a file

    const auto read = U1Jobs::get(again.id);
    REQUIRE(read);
    CHECK(read->origin.reprint_of == jobs[5].id);
    CHECK(read->origin.estimated_time == "1h 2m");
    REQUIRE(read->origin.filaments.size() == 2);
    CHECK(read->origin.filaments[0].used);
    CHECK(read->origin.filaments[0].color == "#FF0000");
    CHECK(read->origin.filaments[0].grams == Catch::Approx(12.5));
    CHECK_FALSE(read->origin.filaments[1].used);
    CHECK_FALSE(U1Jobs::get("not-a-job"));

    // A file that cannot be copied is reported and nothing else changes.
    U1Jobs::Job lost = new_job();
    lost.sha256      = std::string(64, 'b');
    REQUIRE(U1Jobs::save(lost, U1Jobs::State::Completed));
    CHECK_FALSE(U1Jobs::keep_file(lost, scratch.dir / "missing.tmp"));
    CHECK(fs::exists(U1Jobs::kept_file(again)));
}
