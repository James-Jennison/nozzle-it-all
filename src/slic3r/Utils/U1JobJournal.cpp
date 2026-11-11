#include "U1JobJournal.hpp"

#include "libslic3r/Utils.hpp"

#include <boost/algorithm/string/case_conv.hpp>
#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>
#include <boost/nowide/cstdio.hpp>
#include <boost/nowide/fstream.hpp>
#include <boost/uuid/uuid_generators.hpp>
#include <boost/uuid/uuid_io.hpp>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>

#ifdef _WIN32
#include <io.h>
#else
#include <unistd.h>
#endif

namespace Slic3r {
namespace U1Jobs {

namespace {

using json   = nlohmann::json;
namespace fs = boost::filesystem;

std::mutex s_mutex;

const std::pair<State, const char *> STATE_NAMES[] = {
    {State::Uploading, "UPLOADING"},   {State::Verifying, "VERIFYING"}, {State::Starting, "STARTING"},
    {State::StartUnconfirmed, "START_UNCONFIRMED"}, {State::Held, "HELD"}, {State::Printing, "PRINTING"},
    {State::Uploaded, "UPLOADED"},     {State::Completed, "COMPLETED"}, {State::Cancelled, "CANCELLED"},
    {State::Failed, "FAILED"}};

fs::path journal_dir() { return fs::path(data_dir()) / "u1_jobs"; }

json to_json(const Job &job)
{
    json events = json::array();
    for (const auto &event : job.events)
        events.push_back({{"state", event.first}, {"at", event.second}});
    return {{"jobId", job.id},
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
            {"events", events}};
}

std::optional<Job> from_json(const json &doc)
{
    if (!doc.is_object() || !doc.contains("jobId"))
        return std::nullopt;
    Job job;
    job.id                      = doc.value("jobId", "");
    job.printer                 = doc.value("printer", "");
    job.sha256                  = doc.value("sha256", "");
    job.uploaded_sha256         = doc.value("uploadedSha256", "");
    job.remote_path             = doc.value("remotePath", "");
    job.message                 = doc.value("message", "");
    job.bytes                   = doc.value("bytes", std::uintmax_t(0));
    job.start                   = doc.value("start", false);
    job.start_sent              = doc.value("startSent", false);
    job.recovered_from_lost_ack = doc.value("recoveredFromLostAck", false);
    job.toolhead_map            = doc.value("toolheadMap", std::vector<int>());
    job.created_ms              = doc.value("createdAt", 0LL);
    job.progress                = doc.value("progress", 0.);
    const std::string state     = doc.value("state", "FAILED");
    job.state                   = State::Failed;
    for (const auto &entry : STATE_NAMES)
        if (state == entry.second)
            job.state = entry.first;
    for (const auto &event : doc.value("events", json::array()))
        job.events.emplace_back(event.value("state", ""), event.value("at", 0LL));
    return job;
}

// Writes *text* so that the file is either the old or the new content, never a mix.
bool write_durably(const fs::path &target, const std::string &text)
{
    boost::system::error_code ec;
    fs::create_directories(target.parent_path(), ec);
    const fs::path tmp = target.parent_path() / ("." + target.filename().string() + ".tmp");
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
    if (!ok)
        return false;
    fs::rename(tmp, target, ec);
    return !ec;
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
    if (ka == kb)
        return true;
    // "host" and "host:7125" are the same U1: its web proxy and Moonraker's own port.
    const auto host = [](const std::string &key) {
        const auto colon = key.rfind(':');
        return colon == std::string::npos || key.find(']') > colon ? key : key.substr(0, colon);
    };
    const bool a_has_port = host(ka) != ka, b_has_port = host(kb) != kb;
    return a_has_port != b_has_port && host(ka) == host(kb);
}

std::string remote_name(const std::string &requested, const std::string &sha256)
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
    return base + "-" + sha256.substr(0, 24) + ext;
}

bool save(Job &job, State state, const std::string &message)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    if (job.events.empty() || job.events.back().first != state_name(state))
        job.events.emplace_back(state_name(state), now_ms());
    job.state = state;
    if (!message.empty())
        job.message = message;
    const bool ok = write_durably(journal_dir() / (job.id + ".json"), to_json(job).dump(2));
    if (!ok)
        BOOST_LOG_TRIVIAL(error) << "U1 jobs: could not write the record for job " << job.id;
    return ok;
}

std::vector<Job> load_all()
{
    std::lock_guard<std::mutex> lock(s_mutex);
    std::vector<Job>            jobs;
    boost::system::error_code   ec;
    for (fs::directory_iterator it(journal_dir(), ec), end; !ec && it != end; it.increment(ec)) {
        if (it->path().extension() != ".json")
            continue;
        boost::nowide::ifstream in(it->path().string());
        if (auto job = from_json(json::parse(in, nullptr, false)))
            jobs.push_back(std::move(*job));
    }
    std::sort(jobs.begin(), jobs.end(), [](const Job &a, const Job &b) { return a.created_ms < b.created_ms; });
    return jobs;
}

std::optional<Job> find(const std::string &printer, const std::string &remote_path)
{
    std::optional<Job> found;
    for (Job &job : load_all())
        if (job.remote_path == remote_path && same_printer(job.printer, printer))
            found = std::move(job);
    return found;
}

std::optional<Job> unresolved(const std::string &printer)
{
    for (Job &job : load_all())
        if (job.unresolved() && same_printer(job.printer, printer))
            return std::move(job);
    return std::nullopt;
}

} // namespace U1Jobs
} // namespace Slic3r
