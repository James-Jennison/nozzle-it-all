#include "U1JobJournal.hpp"

#include "libslic3r/Utils.hpp"

#include <boost/algorithm/string.hpp>
#include <boost/filesystem.hpp>
#include <boost/interprocess/sync/file_lock.hpp>
#include <boost/log/trivial.hpp>
#include <boost/nowide/cstdio.hpp>
#include <boost/nowide/fstream.hpp>
#include <boost/uuid/uuid_generators.hpp>
#include <boost/uuid/uuid_io.hpp>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <set>

#ifdef _WIN32
#include <io.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

namespace Slic3r {
namespace U1Jobs {

namespace {

using json   = nlohmann::json;
namespace fs = boost::filesystem;

// Guards every read-modify-write of the records within this process.
std::mutex s_mutex;
// Printers this process is sending to right now.
std::mutex            s_sending_mutex;
std::set<std::string> s_sending;
fs::path              s_test_dir;

const std::pair<State, const char *> STATE_NAMES[] = {
    {State::Uploading, "UPLOADING"},   {State::Verifying, "VERIFYING"}, {State::Starting, "STARTING"},
    {State::StartUnconfirmed, "START_UNCONFIRMED"}, {State::Held, "HELD"}, {State::Printing, "PRINTING"},
    {State::Uploaded, "UPLOADED"},     {State::Completed, "COMPLETED"}, {State::Cancelled, "CANCELLED"},
    {State::Failed, "FAILED"}};

fs::path journal_dir() { return s_test_dir.empty() ? fs::path(data_dir()) / "u1_jobs" : s_test_dir; }

bool is_hex(const std::string &s, size_t length)
{
    return s.size() == length && std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isxdigit(c) != 0; });
}

bool is_job_id(const std::string &s)
{
    if (s.size() != 36)
        return false;
    for (size_t i = 0; i < s.size(); ++i) {
        const bool dash = i == 8 || i == 13 || i == 18 || i == 23;
        if (dash ? s[i] != '-' : std::isxdigit(static_cast<unsigned char>(s[i])) == 0)
            return false;
    }
    return true;
}

json to_json(const Job &job)
{
    json events = json::array();
    for (const auto &event : job.events)
        events.push_back({{"state", event.first}, {"at", event.second}});
    return {{"format", 1},
            {"jobId", job.id},
            {"printer", job.printer},
            {"sha256", job.sha256},
            {"uploadedSha256", job.uploaded_sha256},
            {"remotePath", job.remote_path},
            {"message", job.message},
            {"bytes", job.bytes},
            {"start", job.start},
            {"startSent", job.start_sent},
            {"recoveredFromLostAck", job.recovered_from_lost_ack},
            {"state", state_name(job.state)},
            {"toolheadMap", job.toolhead_map},
            {"createdAt", job.created_ms},
            {"progress", job.progress},
            {"startedAt", job.started_ms},
            {"endedAt", job.ended_ms},
            {"events", events}};
}

// A record is trusted only when everything a safety decision reads is present and well formed.
std::optional<Job> from_json(const json &doc)
{
    try {
        if (!doc.is_object())
            return std::nullopt;
        Job job;
        job.id          = doc.at("jobId").get<std::string>();
        job.printer     = doc.at("printer").get<std::string>();
        job.sha256      = doc.at("sha256").get<std::string>();
        job.remote_path = doc.at("remotePath").get<std::string>();
        job.start_sent  = doc.at("startSent").get<bool>();
        const std::string state = doc.at("state").get<std::string>();
        bool              known = false;
        for (const auto &entry : STATE_NAMES)
            if (state == entry.second) {
                job.state = entry.first;
                known     = true;
            }
        if (!known || !is_job_id(job.id) || job.printer.empty() || job.remote_path.empty() || !is_hex(job.sha256, 64))
            return std::nullopt;
        job.uploaded_sha256         = doc.value("uploadedSha256", std::string());
        job.message                 = doc.value("message", std::string());
        job.bytes                   = doc.value("bytes", std::uintmax_t(0));
        job.start                   = doc.value("start", false);
        job.recovered_from_lost_ack = doc.value("recoveredFromLostAck", false);
        job.toolhead_map            = doc.value("toolheadMap", std::vector<int>());
        job.created_ms              = doc.value("createdAt", 0LL);
        job.progress                = doc.value("progress", 0.);
        job.started_ms              = doc.value("startedAt", 0LL);
        job.ended_ms                = doc.value("endedAt", 0LL);
        for (const auto &event : doc.value("events", json::array()))
            job.events.emplace_back(event.value("state", std::string()), event.value("at", 0LL));
        return job;
    } catch (const std::exception &) {
        return std::nullopt;
    }
}

// False when the directory entry could not be forced to disk.
bool sync_directory(const fs::path &dir)
{
#ifndef _WIN32
    // The rename is only durable once the directory entry is on disk.
    const int fd = ::open(dir.string().c_str(), O_RDONLY);
    if (fd < 0)
        return false;
    // A file system that has no directory sync (EINVAL) has nothing more to offer; any other failure is one.
    const bool ok = ::fsync(fd) == 0 || errno == EINVAL;
    ::close(fd);
    return ok;
#else
    // NTFS journals the rename itself; there is no directory handle to flush here.
    (void) dir;
    return true;
#endif
}

// Writes *text* so that the file is either the old or the new content, never a mix.
bool write_durably(const fs::path &target, const std::string &text)
{
    boost::system::error_code ec;
    fs::create_directories(target.parent_path(), ec);
    // A temp name of its own for every write: two writers can never share one.
    const fs::path tmp = target.parent_path() / ("." + target.filename().string() + "." + new_id() + ".tmp");
    FILE *file = boost::nowide::fopen(tmp.string().c_str(), "wb");
    if (file == nullptr)
        return false;
    bool ok = std::fwrite(text.data(), 1, text.size(), file) == text.size() && std::fflush(file) == 0;
#ifdef _WIN32
    ok = ok && _commit(_fileno(file)) == 0;
#else
    ok = ok && ::fsync(fileno(file)) == 0;
#endif
    ok = std::fclose(file) == 0 && ok;
    if (ok) {
        fs::rename(tmp, target, ec);
        ok = !ec;
    }
    if (!ok) {
        fs::remove(tmp, ec);
        return false;
    }
    // The record may now be newer on disk than in memory. Callers treat that as "not written" and
    // send nothing, and a record that says a Start was sent holds the printer: both are the safe side.
    return sync_directory(target.parent_path());
}

std::optional<Job> read_record(const fs::path &path)
{
    boost::nowide::ifstream in(path.string());
    if (!in)
        return std::nullopt;
    return from_json(json::parse(in, nullptr, false));
}

// Caller holds s_mutex.
bool save_locked(Job &job, State state, const std::string &message, const std::string &event)
{
    Job next = job;
    if (!event.empty())
        next.events.emplace_back(event, now_ms());
    if (next.events.empty() || next.events.back().first != state_name(state))
        next.events.emplace_back(state_name(state), now_ms());
    next.state = state;
    if (!message.empty())
        next.message = message;
    if (!write_durably(journal_dir() / (next.id + ".json"), to_json(next).dump(2))) {
        BOOST_LOG_TRIVIAL(error) << "U1 jobs: could not write the record for job " << job.id << " (" << state_name(state) << ")";
        return false;
    }
    job = std::move(next);
    return true;
}

std::vector<Job> load_all_locked(std::vector<std::string> *unreadable)
{
    std::vector<Job>          jobs;
    boost::system::error_code ec;
    for (fs::directory_iterator it(journal_dir(), ec), end; !ec && it != end; it.increment(ec)) {
        const fs::path &path = it->path();
        if (path.extension() != ".json" || boost::starts_with(path.filename().string(), "."))
            continue;
        if (auto job = read_record(path); job && job->id + ".json" == path.filename().string())
            jobs.push_back(std::move(*job));
        else if (unreadable != nullptr)
            unreadable->push_back(path.string());
    }
    std::sort(jobs.begin(), jobs.end(), [](const Job &a, const Job &b) { return a.created_ms < b.created_ms; });
    return jobs;
}

std::string host_of(const std::string &key)
{
    if (!key.empty() && key.front() == '[') { // [v6]:port
        const auto close = key.find(']');
        return close == std::string::npos ? key : key.substr(0, close + 1);
    }
    const auto colon = key.rfind(':');
    return colon == std::string::npos || key.find(':') != colon ? key : key.substr(0, colon);
}

std::string port_of(const std::string &key)
{
    const std::string host = host_of(key);
    return key.size() > host.size() + 1 ? key.substr(host.size() + 1) : std::string();
}

} // namespace

const char *state_name(State state)
{
    for (const auto &entry : STATE_NAMES)
        if (entry.first == state)
            return entry.second;
    return "FAILED";
}

std::string new_id() { return boost::uuids::to_string(boost::uuids::random_generator()()); }

long long now_ms()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

void set_journal_dir_for_tests(const fs::path &dir)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    s_test_dir = dir;
}

std::string printer_key(const std::string &address)
{
    std::string key = address;
    if (const auto scheme = key.find("://"); scheme != std::string::npos)
        key = key.substr(scheme + 3);
    if (const auto slash = key.find('/'); slash != std::string::npos)
        key = key.substr(0, slash);
    boost::algorithm::to_lower(key);
    return key;
}

bool same_printer(const std::string &a, const std::string &b)
{
    const std::string ka = printer_key(a), kb = printer_key(b);
    if (ka.empty() || kb.empty())
        return false;
    if (ka == kb)
        return true;
    const auto u1_port = [](const std::string &port) { return port.empty() || port == "80" || port == "7125"; };
    return host_of(ka) == host_of(kb) && u1_port(port_of(ka)) && u1_port(port_of(kb));
}

std::string remote_name(const std::string &requested, const std::string &sha256, const std::string &job_id)
{
    const fs::path path(requested);
    std::string    ext  = path.extension().string();
    std::string    base = path.stem().string();
    if (ext.empty())
        ext = ".gcode";
    for (char &c : base)
        if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '-' || c == '_' || c == '.' || c == ' '))
            c = '_';
    if (base.size() > 60)
        base.resize(60);
    if (base.empty())
        base = "print";
    return base + "-" + sha256.substr(0, 24) + "-" + job_id.substr(0, 8) + ext;
}

bool is_job_file(const Job &job, const std::string &reported)
{
    if (reported.empty() || job.remote_path.empty())
        return false;
    const auto leaf = [](const std::string &path) {
        const auto slash = path.find_last_of('/');
        return slash == std::string::npos ? path : path.substr(slash + 1);
    };
    // The name is unique to the job, so matching the leaf cannot pick up another job.
    return reported == job.remote_path || leaf(reported) == leaf(job.remote_path);
}

bool save(Job &job, State state, const std::string &message)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    return save_locked(job, state, message, {});
}

std::optional<Job> update(const std::string &id, const std::function<std::optional<State>(Job &)> &change, const std::string &message,
                          const std::string &event)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    if (!is_job_id(id))
        return std::nullopt;
    std::optional<Job> job = read_record(journal_dir() / (id + ".json"));
    if (!job)
        return std::nullopt;
    const std::optional<State> next = change(*job);
    if (next && !save_locked(*job, *next, message, event))
        return std::nullopt;
    return job;
}

std::vector<Job> load_all(std::vector<std::string> *unreadable)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    return load_all_locked(unreadable);
}

std::optional<Job> find(const std::string &printer, const std::string &reported_filename)
{
    std::optional<Job> found;
    for (Job &job : load_all())
        if (is_job_file(job, reported_filename) && same_printer(job.printer, printer))
            found = std::move(job);
    return found;
}

std::vector<Job> printing()
{
    std::vector<Job> jobs = load_all();
    jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [](const Job &job) { return job.state != State::Printing; }), jobs.end());
    return jobs;
}

namespace {

std::optional<State> outcome_named(const std::string &state)
{
    if (state == "complete" || state == "completed")
        return State::Completed;
    if (state == "cancelled")
        return State::Cancelled;
    // Moonraker's history names the ways a job can end without finishing.
    if (state == "error" || state == "klippy_shutdown" || state == "klippy_disconnect" || state == "server_exit" || state == "interrupted")
        return State::Failed;
    return std::nullopt;
}

long long epoch_ms(const json &entry, const char *key)
{
    if (!entry.contains(key) || !entry[key].is_number())
        return 0;
    const double seconds = entry[key].get<double>();
    return seconds > 0. ? static_cast<long long>(seconds * 1000.) : 0;
}

} // namespace

std::optional<State> outcome_from_status(const Job &job, const json &status)
{
    if (!status.is_object() || !status.contains("print_stats") || !status["print_stats"].is_object())
        return std::nullopt;
    const json &stats = status["print_stats"];
    if (!stats.contains("filename") || !stats["filename"].is_string() || !stats.contains("state") || !stats["state"].is_string())
        return std::nullopt;
    if (!is_job_file(job, stats["filename"].get<std::string>()))
        return std::nullopt;
    return outcome_named(stats["state"].get<std::string>());
}

std::optional<PrinterRecord> record_from_history(const Job &job, const json &history)
{
    if (!history.is_object() || !history.contains("jobs") || !history["jobs"].is_array())
        return std::nullopt;
    for (const json &entry : history["jobs"]) {
        if (!entry.is_object() || !entry.contains("filename") || !entry["filename"].is_string() ||
            !is_job_file(job, entry["filename"].get<std::string>()))
            continue;
        PrinterRecord record;
        record.started_ms = epoch_ms(entry, "start_time");
        if (entry.contains("status") && entry["status"].is_string())
            record.outcome = outcome_named(entry["status"].get<std::string>());
        // An end time belongs to an ended job only.
        if (record.outcome)
            record.ended_ms = epoch_ms(entry, "end_time");
        return record;
    }
    return std::nullopt;
}

std::optional<Job> record_outcome(const std::string &id, const PrinterRecord &record, const std::string &message)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    std::optional<Job> job = is_job_id(id) ? read_record(journal_dir() / (id + ".json")) : std::nullopt;
    if (!job)
        return std::nullopt;
    const bool takes_outcome = record.outcome && job->state == State::Printing;
    const bool takes_start   = record.started_ms > 0 && job->started_ms == 0;
    const bool takes_end     = record.ended_ms > 0 && job->ended_ms == 0 && (takes_outcome || job->state != State::Printing);
    if (!takes_outcome && !takes_start && !takes_end)
        return std::nullopt;
    if (takes_start)
        job->started_ms = record.started_ms;
    if (takes_end)
        job->ended_ms = record.ended_ms;
    if (!save_locked(*job, takes_outcome ? *record.outcome : job->state, takes_outcome ? message : job->message,
                     takes_outcome ? std::string() : std::string("times from the printer's history")))
        return std::nullopt;
    return job;
}

std::optional<Job> unresolved(const std::string &printer)
{
    for (Job &job : load_all())
        if (job.unresolved() && same_printer(job.printer, printer))
            return std::move(job);
    return std::nullopt;
}

// ---------------------------------------------------------------------------

struct PrinterLock::Impl
{
    boost::interprocess::file_lock file;
    bool                           file_locked = false;
};

PrinterLock::PrinterLock(const std::string &printer) : m_impl(std::make_unique<Impl>()), m_key(host_of(printer_key(printer)))
{
    {
        std::lock_guard<std::mutex> lock(s_sending_mutex);
        if (m_key.empty() || !s_sending.insert(m_key).second)
            return;
    }
    // Another running copy of the app must be kept out as well: an OS file lock,
    // released by the system if this process dies.
    try {
        std::string name = m_key;
        for (char &c : name)
            if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '-'))
                c = '_';
        const fs::path            dir = journal_dir() / "locks";
        boost::system::error_code ec;
        fs::create_directories(dir, ec);
        const fs::path path = dir / (name + ".lock");
        {
            boost::nowide::ofstream touch(path.string(), std::ios::app);
        }
        m_impl->file        = boost::interprocess::file_lock(path.string().c_str());
        m_impl->file_locked = m_impl->file.try_lock();
    } catch (const std::exception &e) {
        BOOST_LOG_TRIVIAL(error) << "U1 jobs: could not take the printer lock: " << e.what();
    }
    if (!m_impl->file_locked) {
        std::lock_guard<std::mutex> lock(s_sending_mutex);
        s_sending.erase(m_key);
        return;
    }
    m_acquired = true;
}

PrinterLock::~PrinterLock()
{
    if (!m_acquired)
        return;
    try {
        m_impl->file.unlock();
    } catch (const std::exception &) {}
    std::lock_guard<std::mutex> lock(s_sending_mutex);
    s_sending.erase(m_key);
}

bool sending_now(const std::string &printer)
{
    std::lock_guard<std::mutex> lock(s_sending_mutex);
    return s_sending.count(host_of(printer_key(printer))) > 0;
}

// ---------------------------------------------------------------------------

GcodeFacts read_gcode_facts(const fs::path &file)
{
    GcodeFacts              facts;
    boost::nowide::ifstream in(file.string());
    std::string             line;
    while (std::getline(in, line)) {
        if (line.size() < 4 || line[0] != ';')
            continue;
        if (boost::starts_with(line, "; printer_model = "))
            facts.printer_model = boost::trim_copy(line.substr(18));
        else if (boost::starts_with(line, "; nozzle_diameter = ")) {
            std::vector<std::string> parts;
            const std::string        value = line.substr(20);
            boost::split(parts, value, boost::is_any_of(","));
            facts.nozzles.clear();
            for (const std::string &part : parts)
                try {
                    facts.nozzles.push_back(std::stod(boost::trim_copy(part)));
                } catch (const std::exception &) {}
        }
    }
    return facts;
}

Blocker start_blocker(const GcodeFacts &facts, const json &status, const std::vector<int> &filament_to_toolhead, bool start)
{
    Blocker blocker;
    // A file that names another machine never goes to a U1. A file that names none is the user's own G-code.
    if (!facts.printer_model.empty() && !boost::iequals(facts.printer_model, "Snapmaker U1")) {
        blocker.kind   = Blocker::Kind::WrongModel;
        blocker.detail = facts.printer_model;
        return blocker;
    }
    if (!start)
        return blocker;

    const auto object = [&](const char *name) -> const json * {
        if (!status.is_object())
            return nullptr;
        const auto it = status.find(name);
        return it != status.end() && it->is_object() ? &*it : nullptr;
    };
    const json *stats = object("print_stats");
    if (stats == nullptr || !stats->contains("state") || !(*stats)["state"].is_string()) {
        blocker.kind = Blocker::Kind::PrinterUnreachable;
        return blocker;
    }
    const std::string state = (*stats)["state"].get<std::string>();
    if (state != "standby" && state != "complete" && state != "cancelled") {
        blocker.kind   = Blocker::Kind::PrinterNotIdle;
        blocker.detail = state;
        return blocker;
    }

    const json *task   = object("print_task_config");
    const json *loaded = task != nullptr && task->contains("filament_exist") && (*task)["filament_exist"].is_array() ? &(*task)["filament_exist"] : nullptr;
    for (size_t filament = 0; filament < filament_to_toolhead.size(); ++filament) {
        const int toolhead = filament_to_toolhead[filament];
        if (toolhead < 0)
            continue;
        blocker.filament = int(filament);
        blocker.toolhead = toolhead;
        if (loaded == nullptr || size_t(toolhead) >= loaded->size() || !(*loaded)[toolhead].is_boolean()) {
            blocker.kind = Blocker::Kind::ToolheadUnknown;
            return blocker;
        }
        if (!(*loaded)[toolhead].get<bool>()) {
            blocker.kind = Blocker::Kind::ToolheadEmpty;
            return blocker;
        }
        // The file lists one nozzle per extruder it was sliced for, and filament N was sliced for
        // extruder N. A filament beyond that list (a mixed colour) has no nozzle of its own to compare.
        if (filament >= facts.nozzles.size())
            continue;
        const std::string name    = toolhead == 0 ? "extruder" : "extruder" + std::to_string(toolhead);
        const json       *extruder = object(name.c_str());
        if (extruder == nullptr || !extruder->contains("nozzle_diameter") || !(*extruder)["nozzle_diameter"].is_number()) {
            blocker.kind = Blocker::Kind::NozzleUnknown;
            return blocker;
        }
        blocker.sliced = facts.nozzles[filament];
        blocker.fitted = (*extruder)["nozzle_diameter"].get<double>();
        if (std::abs(blocker.fitted - blocker.sliced) > 0.01) {
            blocker.kind = Blocker::Kind::NozzleMismatch;
            return blocker;
        }
    }
    return Blocker();
}

} // namespace U1Jobs
} // namespace Slic3r
