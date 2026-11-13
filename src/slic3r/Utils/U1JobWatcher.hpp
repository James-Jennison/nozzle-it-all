#ifndef slic3r_U1JobWatcher_hpp_
#define slic3r_U1JobWatcher_hpp_

#include "U1JobJournal.hpp"

#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace Slic3r {

/// Records how each job sent to a Snapmaker U1 ended, whichever screen is showing.
///
/// One background thread looks, at an interval, at every job the journal still lists as printing and
/// asks that job's printer for its print state and its job history. It only reads from the printer:
/// two GET requests, no command of any kind. An outcome is written only when the printer names this
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

    explicit U1JobWatcher(ChangeFn on_change = {}, ActiveFn on_active = {});
    ~U1JobWatcher();

    U1JobWatcher(const U1JobWatcher &)            = delete;
    U1JobWatcher &operator=(const U1JobWatcher &) = delete;

    /// Looks now instead of at the next interval, e.g. right after a job was sent.
    void look_now();

    /// One pass over the journal. Public for the worker and for tests of a live simulator.
    static void look_once(const ChangeFn &on_change, const ActiveFn &on_active = {});

private:
    void run();

    ChangeFn                m_on_change;
    ActiveFn                m_on_active;
    std::mutex              m_mutex;
    std::condition_variable m_wake;
    bool                    m_stop{false};
    bool                    m_look{false};
    std::thread             m_thread;
};

} // namespace Slic3r

#endif // slic3r_U1JobWatcher_hpp_
