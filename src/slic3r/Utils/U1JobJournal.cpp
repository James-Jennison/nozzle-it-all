#include "U1JobJournal.hpp"

#include "libslic3r/AfcLanes.hpp"
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
#include <ctime>
#include <iterator>
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

json origin_json(const Origin &origin)
{
    json filaments = json::array();
    for (const Origin::Filament &filament : origin.filaments)
        filaments.push_back({{"used", filament.used}, {"name", filament.name}, {"type", filament.type}, {"color", filament.color},
                             {"grams", filament.grams}});
    return {{"fileName", origin.file_name},
            {"project", origin.project},
            {"projectSavedAt", origin.project_saved_ms},
            {"projectChanged", origin.project_changed},
            {"plate", origin.plate},
            {"plateName", origin.plate_name},
            {"reprintOf", origin.reprint_of},
            {"filaments", filaments},
            {"estimatedTime", origin.estimated_time}};
}

Origin origin_of(const json &doc)
{
    Origin origin;
    if (!doc.is_object())
        return origin;
    origin.file_name        = doc.value("fileName", std::string());
    origin.project          = doc.value("project", std::string());
    origin.project_saved_ms = doc.value("projectSavedAt", 0LL);
    origin.project_changed  = doc.value("projectChanged", false);
    origin.plate            = doc.value("plate", 0);
    origin.plate_name       = doc.value("plateName", std::string());
    origin.reprint_of       = doc.value("reprintOf", std::string());
    origin.estimated_time   = doc.value("estimatedTime", std::string());
    if (doc.contains("filaments") && doc["filaments"].is_array())
        for (const json &entry : doc["filaments"]) {
            Origin::Filament filament;
            filament.used  = entry.value("used", false);
            filament.name  = entry.value("name", std::string());
            filament.type  = entry.value("type", std::string());
            filament.color = entry.value("color", std::string());
            filament.grams = entry.value("grams", 0.);
            origin.filaments.push_back(std::move(filament));
        }
    return origin;
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
            {"sourceSha256", job.source_sha256},
            {"remotePath", job.remote_path},
            {"message", job.message},
            {"bytes", job.bytes},
            {"start", job.start},
            {"startSent", job.start_sent},
            {"recoveredFromLostAck", job.recovered_from_lost_ack},
            {"state", state_name(job.state)},
            {"toolheadMap", job.toolhead_map},
            {"spools", job.spools},
            {"createdAt", job.created_ms},
            {"progress", job.progress},
            {"startedAt", job.started_ms},
            {"endedAt", job.ended_ms},
            {"assessment", job.assessment},
            {"origin", origin_json(job.origin)},
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
        job.source_sha256           = doc.value("sourceSha256", std::string());
        job.message                 = doc.value("message", std::string());
        job.bytes                   = doc.value("bytes", std::uintmax_t(0));
        job.start                   = doc.value("start", false);
        job.recovered_from_lost_ack = doc.value("recoveredFromLostAck", false);
        job.toolhead_map            = doc.value("toolheadMap", std::vector<int>());
        job.spools                  = doc.value("spools", std::vector<int>());
        job.created_ms              = doc.value("createdAt", 0LL);
        job.progress                = doc.value("progress", 0.);
        job.started_ms              = doc.value("startedAt", 0LL);
        job.ended_ms                = doc.value("endedAt", 0LL);
        job.assessment              = doc.value("assessment", std::string());
        if (doc.contains("origin"))
            job.origin = origin_of(doc["origin"]);
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
    // A state is listed when it is entered, not again each time something else about the job is noted.
    if (job.events.empty() || job.state != state || (event.empty() && job.events.back().first != state_name(state)))
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
    // A job whose Start is still open, or that never started, is not this function's to touch.
    if (job->state != State::Printing && job->state != State::Completed && job->state != State::Cancelled && job->state != State::Failed)
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

std::optional<Job> set_assessment(const std::string &id, const std::string &assessment)
{
    if (!assessment.empty() && assessment != "success" && assessment != "partial" && assessment != "failed")
        return std::nullopt;
    std::lock_guard<std::mutex> lock(s_mutex);
    std::optional<Job> job = is_job_id(id) ? read_record(journal_dir() / (id + ".json")) : std::nullopt;
    if (!job)
        return std::nullopt;
    if (job->assessment == assessment)
        return job;
    job->assessment = assessment;
    if (!save_locked(*job, job->state, job->message, "judged: " + (assessment.empty() ? std::string("not judged") : assessment)))
        return std::nullopt;
    return job;
}

std::vector<Job> history(const std::string &printer)
{
    std::vector<Job> jobs = load_all();
    jobs.erase(std::remove_if(jobs.begin(), jobs.end(), [&printer](const Job &job) { return !same_printer(job.printer, printer); }), jobs.end());
    std::stable_sort(jobs.begin(), jobs.end(), [](const Job &a, const Job &b) { return a.created_ms > b.created_ms; });
    return jobs;
}

fs::path kept_file(const Job &job) { return journal_dir() / "files" / (job.sha256 + ".gcode"); }

std::optional<Job> get(const std::string &id)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    return is_job_id(id) ? read_record(journal_dir() / (id + ".json")) : std::nullopt;
}

bool keep_file(const Job &job, const fs::path &prepared)
{
    if (!is_hex(job.sha256, 64))
        return false;
    boost::system::error_code ec;
    const fs::path            target = kept_file(job);
    fs::create_directories(target.parent_path(), ec);
    if (!fs::exists(target, ec)) {
        // Copied under another name first, so a file with a job's name is always a whole one.
        const fs::path part = target.parent_path() / ("." + job.id + ".part");
        fs::copy_file(prepared, part, fs::copy_options::overwrite_existing, ec);
        if (!ec)
            fs::rename(part, target, ec);
        if (ec) {
            boost::system::error_code ignored;
            fs::remove(part, ignored);
            BOOST_LOG_TRIVIAL(warning) << "U1 jobs: the prepared file of job " << job.id << " could not be kept: " << ec.message();
            return false;
        }
    }
    // The newest jobs keep their files. A file sent again is one file with several jobs.
    std::lock_guard<std::mutex> lock(s_mutex);
    std::vector<Job>            jobs = load_all_locked(nullptr);
    std::set<std::string>       keep = {target.filename().string()};
    size_t                      kept = 1;
    for (auto it = jobs.rbegin(); it != jobs.rend() && kept < KEPT_FILES; ++it) {
        if (it->id == job.id || !fs::exists(kept_file(*it), ec))
            continue;
        keep.insert(kept_file(*it).filename().string());
        ++kept;
    }
    for (fs::directory_iterator it(target.parent_path(), ec), end; !ec && it != end; it.increment(ec)) {
        const fs::path &path = it->path();
        if (path.extension() == ".gcode" && keep.count(path.filename().string()) == 0) {
            boost::system::error_code ignored;
            fs::remove(path, ignored);
        }
    }
    return true;
}

namespace {

bool is_verdict(const std::string &text) { return text.empty() || text == "success" || text == "partial" || text == "failed"; }

// One file per printer host, beside the job records but in a folder the job list does not read.
fs::path external_file(const std::string &printer)
{
    std::string name = host_of(printer_key(printer));
    for (char &c : name)
        if (std::isalnum(static_cast<unsigned char>(c)) == 0 && c != '.' && c != '-')
            c = '_';
    return journal_dir() / "external" / (name + ".json");
}

std::vector<ExternalPrint> read_external(const fs::path &path)
{
    std::vector<ExternalPrint> prints;
    boost::nowide::ifstream    in(path.string());
    if (!in)
        return prints;
    const json doc = json::parse(in, nullptr, false);
    if (!doc.is_object() || !doc.contains("prints") || !doc["prints"].is_array())
        return prints;
    for (const json &entry : doc["prints"]) {
        if (!entry.is_object())
            continue;
        ExternalPrint print;
        print.printer_job_id = entry.value("printerJobId", std::string());
        print.file_name      = entry.value("fileName", std::string());
        print.status         = entry.value("status", std::string());
        print.started_ms     = entry.value("startedAt", 0LL);
        print.ended_ms       = entry.value("endedAt", 0LL);
        print.assessment     = entry.value("assessment", std::string());
        if (!print.printer_job_id.empty() && is_verdict(print.assessment))
            prints.push_back(std::move(print));
    }
    return prints;
}

bool write_external(const fs::path &path, std::vector<ExternalPrint> prints)
{
    std::stable_sort(prints.begin(), prints.end(), [](const ExternalPrint &a, const ExternalPrint &b) { return a.started_ms > b.started_ms; });
    json list = json::array();
    for (const ExternalPrint &print : prints)
        list.push_back({{"printerJobId", print.printer_job_id},
                        {"fileName", print.file_name},
                        {"status", print.status},
                        {"startedAt", print.started_ms},
                        {"endedAt", print.ended_ms},
                        {"assessment", print.assessment}});
    return write_durably(path, json{{"format", 1}, {"prints", list}}.dump(2));
}

} // namespace

std::vector<ExternalPrint> external_from_history(const json &history, const std::vector<Job> &jobs)
{
    std::vector<ExternalPrint> prints;
    if (!history.is_object() || !history.contains("jobs") || !history["jobs"].is_array())
        return prints;
    for (const json &entry : history["jobs"]) {
        if (!entry.is_object() || !entry.contains("filename") || !entry["filename"].is_string() || !entry.contains("job_id") ||
            !entry["job_id"].is_string())
            continue;
        const std::string file = entry["filename"].get<std::string>();
        if (std::any_of(jobs.begin(), jobs.end(), [&file](const Job &job) { return is_job_file(job, file); }))
            continue;
        ExternalPrint print;
        print.printer_job_id = entry["job_id"].get<std::string>();
        print.file_name      = file;
        print.status         = entry.contains("status") && entry["status"].is_string() ? entry["status"].get<std::string>() : std::string();
        print.started_ms     = epoch_ms(entry, "start_time");
        print.ended_ms       = print.status == "in_progress" ? 0 : epoch_ms(entry, "end_time");
        if (!print.printer_job_id.empty())
            prints.push_back(std::move(print));
    }
    return prints;
}

bool remember_external(const std::string &printer, const std::vector<ExternalPrint> &seen)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    const fs::path              path   = external_file(printer);
    std::vector<ExternalPrint>  stored = read_external(path);
    bool                        changed = false;
    for (const ExternalPrint &print : seen) {
        auto it = std::find_if(stored.begin(), stored.end(), [&print](const ExternalPrint &old) { return old.printer_job_id == print.printer_job_id; });
        if (it == stored.end()) {
            stored.push_back(print);
            changed = true;
        } else if (it->file_name != print.file_name || it->status != print.status || it->started_ms != print.started_ms ||
                   it->ended_ms != print.ended_ms) {
            const std::string verdict = it->assessment;
            *it                       = print;
            it->assessment            = verdict;
            changed                   = true;
        }
    }
    return !changed || write_external(path, std::move(stored));
}

std::vector<ExternalPrint> external_prints(const std::string &printer)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    std::vector<ExternalPrint>  prints = read_external(external_file(printer));
    std::stable_sort(prints.begin(), prints.end(), [](const ExternalPrint &a, const ExternalPrint &b) { return a.started_ms > b.started_ms; });
    return prints;
}

bool set_external_assessment(const std::string &printer, const std::string &printer_job_id, const std::string &assessment)
{
    if (!is_verdict(assessment))
        return false;
    std::lock_guard<std::mutex> lock(s_mutex);
    const fs::path              path   = external_file(printer);
    std::vector<ExternalPrint>  stored = read_external(path);
    for (ExternalPrint &print : stored)
        if (print.printer_job_id == printer_job_id) {
            if (print.assessment == assessment)
                return true;
            print.assessment = assessment;
            return write_external(path, std::move(stored));
        }
    return false;
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
        if (line.empty() || line[0] != ';') {
            // The command a line runs. A file has a few dozen different ones; the bound is for a file that is not G-code.
            const size_t first = line.find_first_not_of(" \t");
            if (first == std::string::npos || line[first] == ';')
                continue;
            if (facts.commands.size() >= 4096) {
                facts.commands_complete = false;
                continue;
            }
            size_t      from    = first;
            size_t      end     = line.find_first_of(" \t;\r", from);
            std::string command = line.substr(from, end == std::string::npos ? std::string::npos : end - from);
            // A line number ("N12 G1 ...") stands before the command; Klipper skips it.
            if (command.size() > 1 && (command[0] == 'N' || command[0] == 'n') && std::all_of(command.begin() + 1, command.end(), [](unsigned char c) { return std::isdigit(c) != 0; })) {
                from = end == std::string::npos ? std::string::npos : line.find_first_not_of(" \t", end);
                if (from == std::string::npos || line[from] == ';' || line[from] == '\r')
                    continue;
                end     = line.find_first_of(" \t;\r", from);
                command = line.substr(from, end == std::string::npos ? std::string::npos : end - from);
            }
            boost::to_upper(command);
            facts.commands.insert(std::move(command));
            continue;
        }
        if (line.size() < 4)
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

namespace {
// Where in a macro's body an emergency stop stands that every run of the macro reaches: an action_emergency_stop
// call, or an M112 line, outside every {% if %} / {% for %} / {% macro %} block. npos when there is none. (A stop
// inside such a block is the macro's answer to a fault of its own, not to being called.)
size_t unconditional_stop(const std::string &body)
{
    static const std::string name = "action_emergency_stop";
    const auto word_char = [](char c) { return std::isalnum(static_cast<unsigned char>(c)) != 0 || c == '_'; };
    int    depth = 0;
    size_t pos   = 0;
    while (pos < body.size()) {
        if (body.compare(pos, 2, "{#") == 0) { // a comment
            const size_t end = body.find("#}", pos);
            if (end == std::string::npos)
                return std::string::npos;
            pos = end + 2;
        } else if (body.compare(pos, 2, "{%") == 0) {
            const size_t end = body.find("%}", pos);
            if (end == std::string::npos)
                return std::string::npos;
            std::string tag = boost::trim_copy_if(body.substr(pos + 2, end - pos - 2), boost::is_any_of(" \t\r\n-"));
            tag             = tag.substr(0, tag.find_first_of(" \t\r\n"));
            if (tag == "if" || tag == "for" || tag == "macro" || tag == "call" || tag == "filter")
                ++depth;
            else if (tag == "endif" || tag == "endfor" || tag == "endmacro" || tag == "endcall" || tag == "endfilter")
                depth = std::max(0, depth - 1);
            pos = end + 2;
        } else if (depth == 0 && body.compare(pos, name.size(), name) == 0 && (pos == 0 || !word_char(body[pos - 1]))) {
            // The call, with or without space before its parenthesis.
            const size_t after = body.find_first_not_of(" \t", pos + name.size());
            if (after != std::string::npos && body[after] == '(')
                return pos;
            ++pos;
        } else if (depth == 0 && (body[pos] == 'M' || body[pos] == 'm') && body.compare(pos + 1, 3, "112") == 0 && (pos == 0 || !word_char(body[pos - 1])) &&
                   (pos + 4 == body.size() || !word_char(body[pos + 4]))) {
            return pos; // M112 as a word, wherever on its line
        } else
            ++pos;
    }
    return std::string::npos;
}
} // namespace

Blocker afc_start_blocker(const GcodeFacts &facts, const json &status, const std::vector<int> &filament_to_tool, bool start, const std::string &printer_model)
{
    Blocker blocker;
    if (!facts.printer_model.empty() && !printer_model.empty() && !boost::iequals(facts.printer_model, printer_model)) {
        blocker.kind   = Blocker::Kind::WrongModel;
        blocker.detail = facts.printer_model;
        return blocker;
    }
    const auto object = [&](const char *name) -> const json * {
        if (!status.is_object())
            return nullptr;
        const auto it = status.find(name);
        return it != status.end() && it->is_object() ? &*it : nullptr;
    };
    // A command the printer's own configuration answers with an emergency stop and nothing else: the printer's
    // way of refusing a file made for other firmware (COSMOS does so for the stock profile's start G-code). Such a
    // file is not sent at all: started from the printer's own screen it would shut the printer down as well.
    const json *config   = object("configfile");
    const json *settings = config != nullptr && config->contains("settings") && (*config)["settings"].is_object() ? &(*config)["settings"] : nullptr;
    if (settings != nullptr)
        for (auto it = settings->begin(); it != settings->end(); ++it) {
            static const std::string prefix = "gcode_macro ";
            if (!boost::starts_with(it.key(), prefix) || !it->is_object() || !it->contains("gcode") || !(*it)["gcode"].is_string())
                continue;
            const std::string body = (*it)["gcode"].get<std::string>();
            const size_t      call = unconditional_stop(body);
            if (call == std::string::npos)
                continue;
            const std::string command = boost::to_upper_copy(boost::trim_copy(it.key().substr(prefix.size())));
            if (facts.commands.count(command) == 0)
                continue;
            const bool   said  = body.compare(call, 21, "action_emergency_stop") == 0; // an M112 says nothing
            const size_t open  = said ? body.find_first_of("\"'", call) : std::string::npos;
            const size_t close = open != std::string::npos ? body.find(body[open], open + 1) : std::string::npos;
            blocker.kind       = Blocker::Kind::RefusedCommand;
            blocker.detail     = command + "\n" + (close != std::string::npos ? body.substr(open + 1, close - open - 1) : std::string());
            return blocker;
        }
    // A printer that answered and reports a changer, but not its configuration: the check above was not made. The
    // file is not uploaded either, since nothing here could keep it from being started at the printer.
    if (settings == nullptr && object("AFC") != nullptr) {
        blocker.kind = Blocker::Kind::PrinterUnreachable;
        return blocker;
    }
    // The check above was made against a list of the file's commands that is not whole.
    if (settings != nullptr && !facts.commands_complete) {
        blocker.kind = Blocker::Kind::FileNotChecked;
        return blocker;
    }
    if (!start)
        return blocker;

    const json *stats = object("print_stats");
    // The configuration is part of what a Start depends on: without it the check above was not made.
    if (stats == nullptr || !stats->contains("state") || !(*stats)["state"].is_string() || settings == nullptr) {
        blocker.kind = Blocker::Kind::PrinterUnreachable;
        return blocker;
    }
    const std::string state = (*stats)["state"].get<std::string>();
    if (state != "standby" && state != "complete" && state != "cancelled") {
        blocker.kind   = Blocker::Kind::PrinterNotIdle;
        blocker.detail = state;
        return blocker;
    }
    // The changer itself: an error it has not been cleared of, or anything but idle, is a refusal, and so is
    // a status that does not say.
    const json *afc = object("AFC");
    if (afc == nullptr || !afc->contains("error_state") || !(*afc)["error_state"].is_boolean() || !afc->contains("current_state") ||
        !(*afc)["current_state"].is_string()) {
        blocker.kind = Blocker::Kind::PrinterUnreachable;
        return blocker;
    }
    if ((*afc)["error_state"].get<bool>()) {
        blocker.kind   = Blocker::Kind::ChangerNotReady;
        blocker.detail = "error";
        return blocker;
    }
    if (const std::string changer = (*afc)["current_state"].get<std::string>(); !boost::iequals(changer, "Idle")) {
        blocker.kind   = Blocker::Kind::ChangerNotReady;
        blocker.detail = changer;
        return blocker;
    }
    // In bypass the changer feeds nothing: filament comes from outside it, and a tool change would not change it.
    if (const auto bypass = afc->find("bypass_state"); bypass != afc->end() && bypass->is_boolean() && bypass->get<bool>()) {
        blocker.kind   = Blocker::Kind::ChangerNotReady;
        blocker.detail = "bypass";
        return blocker;
    }
    // Every lane the changer names has to have been read: a status without them says nothing about the slots.
    for (const std::string &name : AfcLanes::lane_names(status))
        if (object(("AFC_lane " + name).c_str()) == nullptr) {
            blocker.kind = Blocker::Kind::PrinterUnreachable;
            return blocker;
        }

    bool any = false;
    for (size_t filament = 0; filament < filament_to_tool.size(); ++filament) {
        const int tool = filament_to_tool[filament];
        if (tool < 0)
            continue;
        any              = true;
        blocker.filament = int(filament);
        blocker.toolhead = tool;
        const json *lane = AfcLanes::lane_for_tool(status, tool);
        if (lane == nullptr) {
            blocker.kind = Blocker::Kind::LaneUnknown;
            return blocker;
        }
        if (!AfcLanes::lane_ready(*lane)) {
            blocker.kind = Blocker::Kind::LaneEmpty;
            return blocker;
        }
    }
    // One nozzle prints every filament. The file lists the nozzle it was sliced for first.
    if (any && !facts.nozzles.empty()) {
        blocker.filament = -1;
        blocker.toolhead = -1;
        const json *extruder = object("extruder");
        if (extruder == nullptr || !extruder->contains("nozzle_diameter") || !(*extruder)["nozzle_diameter"].is_number()) {
            blocker.kind = Blocker::Kind::NozzleUnknown;
            return blocker;
        }
        blocker.sliced = facts.nozzles.front();
        blocker.fitted = (*extruder)["nozzle_diameter"].get<double>();
        if (std::abs(blocker.fitted - blocker.sliced) > 0.01) {
            blocker.kind = Blocker::Kind::NozzleMismatch;
            return blocker;
        }
    }
    return Blocker();
}

// ---------------------------------------------------------------------------
// The queue

namespace {

fs::path queue_dir() { return journal_dir() / "queue"; }

std::optional<Queued> read_queued(const fs::path &path)
{
    boost::nowide::ifstream in(path.string());
    if (!in)
        return std::nullopt;
    const json doc = json::parse(in, nullptr, false);
    try {
        if (!doc.is_object())
            return std::nullopt;
        Queued entry;
        entry.id      = doc.at("queueId").get<std::string>();
        entry.printer = doc.at("printer").get<std::string>();
        entry.sha256  = doc.at("sha256").get<std::string>();
        if (!is_job_id(entry.id) || entry.printer.empty() || !is_hex(entry.sha256, 64) || entry.id + ".json" != path.filename().string())
            return std::nullopt;
        entry.printer_name  = doc.value("printerName", std::string());
        entry.printer_model = doc.value("printerModel", std::string());
        entry.slots         = doc.value("slots", false);
        entry.bytes         = doc.value("bytes", std::uintmax_t(0));
        entry.added_ms      = doc.value("addedAt", 0LL);
        entry.position      = doc.value("position", 0LL);
        if (doc.contains("origin"))
            entry.origin = origin_of(doc["origin"]);
        return entry;
    } catch (const std::exception &) {
        return std::nullopt;
    }
}

bool write_queued(const Queued &entry)
{
    return write_durably(queue_dir() / (entry.id + ".json"),
                         json{{"format", 1}, {"queueId", entry.id}, {"printer", entry.printer}, {"printerName", entry.printer_name},
                              {"printerModel", entry.printer_model}, {"slots", entry.slots}, {"sha256", entry.sha256}, {"bytes", entry.bytes},
                              {"addedAt", entry.added_ms}, {"position", entry.position}, {"origin", origin_json(entry.origin)}}.dump(2));
}

// Caller holds s_mutex. Every readable entry, in the order they are to be printed.
std::vector<Queued> queue_locked()
{
    std::vector<Queued>       entries;
    boost::system::error_code ec;
    for (fs::directory_iterator it(queue_dir(), ec), end; !ec && it != end; it.increment(ec)) {
        const fs::path &path = it->path();
        if (path.extension() != ".json" || boost::starts_with(path.filename().string(), "."))
            continue;
        // An entry without its file cannot be printed and is not listed.
        if (auto entry = read_queued(path); entry && fs::exists(queued_file(*entry), ec))
            entries.push_back(std::move(*entry));
    }
    std::stable_sort(entries.begin(), entries.end(), [](const Queued &a, const Queued &b) {
        return a.position != b.position ? a.position < b.position : a.added_ms < b.added_ms;
    });
    return entries;
}

} // namespace

fs::path queued_file(const Queued &entry) { return queue_dir() / (entry.id + ".gcode"); }

std::optional<Queued> queue_add(Queued entry, const fs::path &prepared, const std::string &sha256)
{
    boost::system::error_code ec;
    entry.id       = new_id();
    entry.printer  = printer_key(entry.printer);
    entry.sha256   = sha256;
    entry.added_ms = now_ms();
    entry.bytes    = fs::file_size(prepared, ec);
    if (ec || entry.bytes == 0 || entry.printer.empty() || !is_hex(entry.sha256, 64))
        return std::nullopt;
    std::lock_guard<std::mutex> lock(s_mutex);
    fs::create_directories(queue_dir(), ec);
    // The file first, under another name, so that an entry never points at half a file.
    const fs::path part = queue_dir() / ("." + entry.id + ".part");
    fs::copy_file(prepared, part, fs::copy_options::overwrite_existing, ec);
    if (!ec)
        fs::rename(part, queued_file(entry), ec);
    if (ec) {
        boost::system::error_code ignored;
        fs::remove(part, ignored);
        return std::nullopt;
    }
    const std::vector<Queued> entries = queue_locked();
    entry.position = entries.empty() ? 1 : entries.back().position + 1;
    if (!write_queued(entry)) {
        boost::system::error_code ignored;
        fs::remove(queued_file(entry), ignored);
        fs::remove(queue_dir() / (entry.id + ".json"), ignored);
        return std::nullopt;
    }
    return entry;
}

std::vector<Queued> queue(const std::string &printer)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    std::vector<Queued>         entries = queue_locked();
    if (!printer.empty())
        entries.erase(std::remove_if(entries.begin(), entries.end(), [&printer](const Queued &entry) { return !same_printer(entry.printer, printer); }),
                      entries.end());
    return entries;
}

std::optional<Queued> queued(const std::string &id)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    boost::system::error_code   ec;
    if (!is_job_id(id))
        return std::nullopt;
    std::optional<Queued> entry = read_queued(queue_dir() / (id + ".json"));
    return entry && fs::exists(queued_file(*entry), ec) ? entry : std::nullopt;
}

bool queue_remove(const std::string &id)
{
    if (!is_job_id(id))
        return false;
    std::lock_guard<std::mutex> lock(s_mutex);
    boost::system::error_code   ec;
    // The entry first: what is left of a removal that stopped half-way is a file nothing lists.
    const bool removed = fs::remove(queue_dir() / (id + ".json"), ec) && !ec;
    fs::remove(queue_dir() / (id + ".gcode"), ec);
    return removed;
}

bool queue_move(const std::string &id, bool earlier)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    std::vector<Queued>         entries = queue_locked();
    const auto at = std::find_if(entries.begin(), entries.end(), [&id](const Queued &entry) { return entry.id == id; });
    if (at == entries.end())
        return false;
    // Its neighbour among the entries of the same printer: the order within one printer is what a person sees.
    const auto same = [&at](const Queued &entry) { return same_printer(entry.printer, at->printer); };
    auto other = entries.end();
    if (earlier) {
        for (auto it = entries.begin(); it != at; ++it)
            if (same(*it))
                other = it;
    } else {
        other = std::find_if(std::next(at), entries.end(), same);
    }
    if (other == entries.end())
        return false;
    // Positions are made distinct first: entries added by two copies of the app may share one. Only an entry
    // whose position changes is written, which in the ordinary case is the two that change places.
    std::vector<long long> before;
    for (const Queued &entry : entries)
        before.push_back(entry.position);
    for (size_t i = 0; i < entries.size(); ++i)
        entries[i].position = static_cast<long long>(i) + 1;
    std::swap(at->position, other->position);
    bool ok = true;
    for (size_t i = 0; i < entries.size(); ++i)
        if (entries[i].position != before[i])
            ok = write_queued(entries[i]) && ok;
    return ok;
}

size_t remove_stale_send_copies()
{
    size_t                    removed = 0;
    boost::system::error_code ec;
    const std::time_t         day_ago = std::time(nullptr) - 24 * 60 * 60;
    for (fs::directory_iterator it(fs::temp_directory_path(ec), ec), end; !ec && it != end; it.increment(ec)) {
        const std::string name = it->path().filename().string();
        const bool        ours = boost::starts_with(name, "u1-also-") || boost::starts_with(name, "u1-queued-") || boost::starts_with(name, "u1-reprint-");
        if (!ours || !boost::ends_with(name, ".gcode"))
            continue;
        boost::system::error_code file_error;
        const std::time_t         written = fs::last_write_time(it->path(), file_error);
        if (file_error || written > day_ago || !fs::is_regular_file(it->path(), file_error))
            continue;
        if (fs::remove(it->path(), file_error) && !file_error)
            ++removed;
    }
    return removed;
}

} // namespace U1Jobs
} // namespace Slic3r
