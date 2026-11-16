#ifndef slic3r_U1JobWatcher_hpp_
#define slic3r_U1JobWatcher_hpp_

#include "U1JobJournal.hpp"

#include <atomic>
#include <condition_variable>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace Slic3r {

/// Records how each job sent to a Snapmaker U1 ended, whichever screen is showing.
///
/// One background thread looks, at an interval, at every job the journal still lists as printing and
/// asks that job's printer for its print state and its job history. It only reads from the printer:
/// two GET requests, no command of any kind. A job that stopped before its end is looked up in the history a
/// few more times afterwards, for how much of its filament was fed. An outcome is written only when the printer names this
/// job's own file as finished, cancelled or failed; a printer that does not answer, or that shows
/// another file and has no history entry for this one, leaves the job as it is.
///
/// Because the jobs come from the journal on disk, a job sent before the app was closed is picked up
/// again at the next start.
class U1JobWatcher
{
public:
    /// *on_change* runs on the watcher's thread each time a record was written.
    using ChangeFn = std::function<void(const U1Jobs::Job &)>;

    /// A job the journal lists as printing, with what the printer said about it in this pass.
    struct Active
    {
        U1Jobs::Job job;
        bool        reachable = false; ///< the printer answered
        bool        paused    = false;
        double      progress  = -1.;   ///< 0..1; negative when the printer did not give one for this job's file
    };
    /// Runs on the watcher's thread after every pass, with the jobs still printing (empty when none).
    using ActiveFn = std::function<void(const std::vector<Active> &)>;

    /// Runs on the watcher's thread when a job that had already ended before its end (cancelled or failed after
    /// its Start) is given the part of its filament the printer's history says was fed.
    using FedFn = std::function<void(const U1Jobs::Job &)>;

    explicit U1JobWatcher(ChangeFn on_change = {}, ActiveFn on_active = {}, FedFn on_fed = {});
    ~U1JobWatcher();

    U1JobWatcher(const U1JobWatcher &)            = delete;
    U1JobWatcher &operator=(const U1JobWatcher &) = delete;

    /// Looks now instead of at the next interval, e.g. right after a job was sent.
    void look_now();

    /// One pass over the journal. Public for the worker and for tests of a live simulator.
    /// *unanswered* counts, per job, the passes in which its printer gave no answer; with it, such a printer is
    /// asked less often. *stop*, when set, ends the pass and cancels its request.
    /// *fed_passes* counts, per stopped job that lacks it, the passes since the watcher first looked for how much
    /// filament it fed; with it the printer's history is asked in the passes asks_fed() names, without it in
    /// every pass. *on_fed* is told each such job that was given the figure.
    static void look_once(const ChangeFn &on_change, const ActiveFn &on_active = {}, std::map<std::string, int> *unanswered = nullptr,
                          const std::atomic<bool> *stop = nullptr, const FedFn &on_fed = {}, std::map<std::string, int> *fed_passes = nullptr);

    /// A job that stopped before its end and whose record does not say how much filament it fed is looked up in
    /// its printer's history while it is no older than this (by the time it was sent), ...
    static constexpr long long FED_WINDOW_MS = 7LL * 24 * 3600 * 1000;
    /// ... in the passes numbered 0, 1, 3, 7, 15 and 31 since the watcher first saw it so (about five minutes in
    /// all), and then no more in this run of the app: a printer without a job history never has the figure.
    static constexpr int FED_LAST_PASS = 31;
    /// Whether *job* is such a job at *now_ms*.
    static bool lacks_fed(const U1Jobs::Job &job, long long now_ms);
    /// Whether the history is asked in pass *pass* (counted from 0) for such a job.
    static bool asks_fed(int pass);

    /// Passes without an answer before a printer is asked less often, and the passes between two tries after
    /// that (30 passes of 10 s: once in five minutes).
    static constexpr int BACKOFF_AFTER = 6;
    static constexpr int MAX_SKIP      = 30;
    /// Whether a printer that gave no answer in its last *misses* passes is asked in this one: always for the
    /// first BACKOFF_AFTER misses, then once in MAX_SKIP passes.
    static bool asks_after(int misses);

private:
    void run();

    ChangeFn                m_on_change;
    ActiveFn                m_on_active;
    FedFn                   m_on_fed;
    std::mutex              m_mutex;
    std::condition_variable m_wake;
    bool                    m_stop{false};
    std::atomic<bool>       m_stopping{false}; ///< set first when stopping, so a request under way gives up
    bool                    m_look{false};
    std::map<std::string, int> m_unanswered; ///< only touched by the watcher's thread
    std::map<std::string, int> m_fed_passes; ///< likewise
    std::thread             m_thread;
};

} // namespace Slic3r

#endif // slic3r_U1JobWatcher_hpp_
