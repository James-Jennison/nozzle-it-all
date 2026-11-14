#include "U1JobWatcher.hpp"

#include "Http.hpp"

#include <nlohmann/json.hpp>

#include <boost/log/trivial.hpp>

#include <algorithm>
#include <chrono>
#include <map>
#include <optional>
#include <string>
#include <utility>

namespace Slic3r {

namespace {

constexpr long QUERY_TIMEOUT_S = 5;
constexpr auto FIRST_LOOK      = std::chrono::seconds(5);
constexpr auto INTERVAL        = std::chrono::seconds(10);
// Passes without an answer before a printer is asked less often, and the most passes skipped between two tries
// (30 passes of 10 s: once in five minutes).
constexpr int  BACKOFF_AFTER   = 6;
constexpr int  MAX_SKIP        = 30;

// The "result" of a Moonraker GET, or null when the printer did not answer with one.
nlohmann::json get_result(const std::string &url)
{
    nlohmann::json result;
    Http::get(url)
        .timeout_connect(QUERY_TIMEOUT_S)
        .timeout_max(QUERY_TIMEOUT_S)
        .on_complete([&](std::string body, unsigned) {
            const auto doc = nlohmann::json::parse(body, nullptr, false);
            if (!doc.is_discarded() && doc.is_object() && doc.contains("result"))
                result = doc["result"];
        })
        .on_error([](std::string, std::string, unsigned) {})
        .perform_sync();
    return result;
}

} // namespace

U1JobWatcher::U1JobWatcher(ChangeFn on_change, ActiveFn on_active)
    : m_on_change(std::move(on_change)), m_on_active(std::move(on_active)), m_thread([this] { run(); })
{}

U1JobWatcher::~U1JobWatcher()
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_stop = true;
    }
    m_wake.notify_all();
    if (m_thread.joinable())
        m_thread.join();
}

void U1JobWatcher::look_now()
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_look = true;
    }
    m_wake.notify_all();
}

void U1JobWatcher::run()
{
    auto wait = FIRST_LOOK;
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            m_wake.wait_for(lock, wait, [this] { return m_stop || m_look; });
            if (m_stop)
                return;
            m_look = false;
        }
        wait = INTERVAL;
        try {
            look_once(m_on_change, m_on_active, &m_unanswered);
        } catch (const std::exception &error) {
            BOOST_LOG_TRIVIAL(warning) << "U1 job watcher: " << error.what();
        }
    }
}

void U1JobWatcher::look_once(const ChangeFn &on_change, const ActiveFn &on_active, std::map<std::string, int> *unanswered)
{
    std::vector<Active> active;
    for (const U1Jobs::Job &job : U1Jobs::printing()) {
        // A send to this printer is settling its own record right now.
        if (U1Jobs::sending_now(job.printer))
            continue;
        Active entry;
        entry.job = job;
        // A printer that has stopped answering is asked less and less often, down to once in MAX_SKIP passes.
        int *misses = unanswered != nullptr ? &(*unanswered)[job.id] : nullptr;
        if (misses != nullptr && *misses > BACKOFF_AFTER && *misses % std::min(MAX_SKIP, *misses - BACKOFF_AFTER + 1) != 0) {
            ++*misses;
            active.push_back(std::move(entry));
            continue;
        }
        const std::string base = "http://" + job.printer;
        const nlohmann::json answer = get_result(base + "/printer/objects/query?print_stats&virtual_sdcard&display_status");
        if (!answer.is_object()) {
            if (misses != nullptr)
                ++*misses;
            active.push_back(std::move(entry)); // no answer: nothing is known, nothing is written
            continue;
        }
        if (misses != nullptr)
            *misses = 0;
        entry.reachable             = true;
        const nlohmann::json status = answer.value("status", nlohmann::json());
        U1Jobs::PrinterRecord record;
        record.outcome = U1Jobs::outcome_from_status(job, status);
        // The history gives the times, and the outcome of a job the printer no longer shows.
        if (const auto listed = U1Jobs::record_from_history(job, get_result(base + "/server/history/list?limit=50&order=desc"))) {
            record.started_ms = listed->started_ms;
            record.ended_ms   = listed->ended_ms;
            if (!record.outcome)
                record.outcome = listed->outcome;
        }
        const auto written = U1Jobs::record_outcome(job.id, record, "Outcome read from the printer by the job watcher.");
        if (written) {
            BOOST_LOG_TRIVIAL(info) << "U1 job watcher: job " << written->id << " is " << U1Jobs::state_name(written->state);
            if (on_change)
                on_change(*written);
        }
        if (written ? written->state != U1Jobs::State::Printing : record.outcome.has_value())
            continue; // ended
        if (written)
            entry.job = *written;
        // Progress only when the printer is showing this job's own file.
        if (status.is_object() && status.contains("print_stats") && status["print_stats"].is_object()) {
            const nlohmann::json &stats = status["print_stats"];
            if (stats.contains("filename") && stats["filename"].is_string() && U1Jobs::is_job_file(job, stats["filename"].get<std::string>())) {
                entry.paused = stats.value("state", std::string()) == "paused";
                for (const char *object : {"display_status", "virtual_sdcard"})
                    if (entry.progress < 0. && status.contains(object) && status[object].is_object() && status[object].contains("progress") &&
                        status[object]["progress"].is_number())
                        entry.progress = std::min(1., std::max(0., status[object]["progress"].get<double>()));
            }
        }
        active.push_back(std::move(entry));
    }
    if (on_active)
        on_active(active);
}

} // namespace Slic3r
