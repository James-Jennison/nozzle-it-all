#include "U1JobWatcher.hpp"

#include "Http.hpp"

#include <nlohmann/json.hpp>

#include <boost/log/trivial.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <iterator>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace Slic3r {

namespace {

constexpr long QUERY_TIMEOUT_S = 5;
constexpr auto FIRST_LOOK      = std::chrono::seconds(5);
constexpr auto INTERVAL        = std::chrono::seconds(10);

// The "result" of a Moonraker GET, or null when the printer did not answer with one.
nlohmann::json get_result(const std::string &url, const std::atomic<bool> *stop)
{
    nlohmann::json result;
    Http::get(url)
        .timeout_connect(QUERY_TIMEOUT_S)
        .timeout_max(QUERY_TIMEOUT_S)
        // The watcher is being stopped: the request gives up instead of running to its time limit.
        .on_progress([stop](Http::Progress, bool &cancel) { cancel = stop != nullptr && stop->load(); })
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

U1JobWatcher::U1JobWatcher(ChangeFn on_change, ActiveFn on_active, FedFn on_fed)
    : m_on_change(std::move(on_change)), m_on_active(std::move(on_active)), m_on_fed(std::move(on_fed)), m_thread([this] { run(); })
{}

U1JobWatcher::~U1JobWatcher()
{
    m_stopping.store(true);
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
            look_once(m_on_change, m_on_active, &m_unanswered, &m_stopping, m_on_fed, &m_fed_passes);
        } catch (const std::exception &error) {
            BOOST_LOG_TRIVIAL(warning) << "U1 job watcher: " << error.what();
        }
    }
}

bool U1JobWatcher::asks_after(int misses)
{
    return misses <= BACKOFF_AFTER || (misses - BACKOFF_AFTER) % MAX_SKIP == 0;
}

bool U1JobWatcher::lacks_fed(const U1Jobs::Job &job, long long now_ms)
{
    return job.start_sent && (job.state == U1Jobs::State::Cancelled || job.state == U1Jobs::State::Failed) && job.fed_part < 0. &&
           job.created_ms > 0 && now_ms - job.created_ms < FED_WINDOW_MS;
}

bool U1JobWatcher::asks_fed(int pass)
{
    // 0, 1, 3, 7, 15, 31: one less than a power of two.
    return pass >= 0 && pass <= FED_LAST_PASS && ((pass + 1) & pass) == 0;
}

void U1JobWatcher::look_once(const ChangeFn &on_change, const ActiveFn &on_active, std::map<std::string, int> *unanswered,
                             const std::atomic<bool> *stop, const FedFn &on_fed, std::map<std::string, int> *fed_passes)
{
    // Jobs that stopped before their end and do not yet say how much filament they fed: the printer's history
    // has that figure, though not always at the moment the end is first read (and the Printer tab reads an end
    // from the live state, without the history). Only the figure and missing times are taken, never an outcome.
    {
        const long long now = U1Jobs::now_ms();
        std::vector<U1Jobs::Job> lacking = U1Jobs::load_all();
        lacking.erase(std::remove_if(lacking.begin(), lacking.end(), [now](const U1Jobs::Job &job) { return !lacks_fed(job, now); }), lacking.end());
        if (fed_passes != nullptr)
            for (auto it = fed_passes->begin(); it != fed_passes->end();)
                it = std::any_of(lacking.begin(), lacking.end(), [&](const U1Jobs::Job &job) { return job.id == it->first; }) ? std::next(it) :
                                                                                                                             fed_passes->erase(it);
        for (const U1Jobs::Job &job : lacking) {
            if (stop != nullptr && stop->load())
                return;
            const int pass = fed_passes != nullptr ? (*fed_passes)[job.id]++ : 0;
            if (!asks_fed(pass) || U1Jobs::sending_now(job.printer))
                continue;
            auto listed = U1Jobs::record_from_history(job, get_result("http://" + job.printer + "/server/history/list?limit=50&order=desc", stop));
            if (!listed || listed->fed_part < 0.)
                continue;
            listed->outcome.reset();
            if (const auto written = U1Jobs::record_outcome(job.id, *listed, {}); written && written->fed_part >= 0.) {
                BOOST_LOG_TRIVIAL(info) << "U1 job watcher: job " << written->id << " fed " << written->fed_part << " of its filament";
                if (on_fed)
                    on_fed(*written);
            }
        }
    }

    std::vector<Active> active;
    const std::vector<U1Jobs::Job> printing = U1Jobs::printing();
    // A job that is no longer printing needs no counter.
    if (unanswered != nullptr)
        for (auto it = unanswered->begin(); it != unanswered->end();)
            it = std::any_of(printing.begin(), printing.end(), [&](const U1Jobs::Job &job) { return job.id == it->first; }) ? std::next(it) :
                                                                                                                           unanswered->erase(it);
    for (const U1Jobs::Job &job : printing) {
        // A send to this printer is settling its own record right now.
        if (U1Jobs::sending_now(job.printer))
            continue;
        Active entry;
        entry.job = job;
        if (stop != nullptr && stop->load())
            return;
        // A printer that has stopped answering is asked less often: see asks_after().
        int *misses = unanswered != nullptr ? &(*unanswered)[job.id] : nullptr;
        if (misses != nullptr && !asks_after(*misses)) {
            ++*misses;
            active.push_back(std::move(entry));
            continue;
        }
        const std::string base = "http://" + job.printer;
        const nlohmann::json answer = get_result(base + "/printer/objects/query?print_stats&virtual_sdcard&display_status", stop);
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
        if (const auto listed = U1Jobs::record_from_history(job, get_result(base + "/server/history/list?limit=50&order=desc", stop))) {
            record.started_ms = listed->started_ms;
            record.ended_ms   = listed->ended_ms;
            record.fed_part   = listed->fed_part;
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
    if (on_active && !(stop != nullptr && stop->load()))
        on_active(active);
}

} // namespace Slic3r
