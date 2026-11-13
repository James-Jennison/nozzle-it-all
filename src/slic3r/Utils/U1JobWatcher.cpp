#include "U1JobWatcher.hpp"

#include "Http.hpp"

#include <nlohmann/json.hpp>

#include <boost/log/trivial.hpp>

#include <chrono>
#include <optional>
#include <string>
#include <utility>

namespace Slic3r {

namespace {

constexpr long QUERY_TIMEOUT_S = 5;
constexpr auto FIRST_LOOK      = std::chrono::seconds(5);
constexpr auto INTERVAL        = std::chrono::seconds(20);

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

U1JobWatcher::U1JobWatcher(ChangeFn on_change) : m_on_change(std::move(on_change)), m_thread([this] { run(); }) {}

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
            look_once(m_on_change);
        } catch (const std::exception &error) {
            BOOST_LOG_TRIVIAL(warning) << "U1 job watcher: " << error.what();
        }
    }
}

void U1JobWatcher::look_once(const ChangeFn &on_change)
{
    for (const U1Jobs::Job &job : U1Jobs::printing()) {
        // A send to this printer is settling its own record right now.
        if (U1Jobs::sending_now(job.printer))
            continue;
        const std::string base = "http://" + job.printer;
        const nlohmann::json status = get_result(base + "/printer/objects/query?print_stats");
        if (!status.is_object())
            continue; // no answer: nothing is known, nothing is written
        U1Jobs::PrinterRecord record;
        record.outcome = U1Jobs::outcome_from_status(job, status.value("status", nlohmann::json()));
        // The history gives the times, and the outcome of a job the printer no longer shows.
        if (const auto listed = U1Jobs::record_from_history(job, get_result(base + "/server/history/list?limit=50&order=desc"))) {
            record.started_ms = listed->started_ms;
            record.ended_ms   = listed->ended_ms;
            if (!record.outcome)
                record.outcome = listed->outcome;
        }
        if (const auto written = U1Jobs::record_outcome(job.id, record, "Outcome read from the printer by the job watcher.")) {
            BOOST_LOG_TRIVIAL(info) << "U1 job watcher: job " << written->id << " is " << U1Jobs::state_name(written->state);
            if (on_change)
                on_change(*written);
        }
    }
}

} // namespace Slic3r
