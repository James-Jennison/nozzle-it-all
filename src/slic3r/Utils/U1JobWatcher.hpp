#ifndef slic3r_U1JobWatcher_hpp_
#define slic3r_U1JobWatcher_hpp_

#include "U1JobJournal.hpp"

#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>

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

    explicit U1JobWatcher(ChangeFn on_change = {});
    ~U1JobWatcher();

    U1JobWatcher(const U1JobWatcher &)            = delete;
    U1JobWatcher &operator=(const U1JobWatcher &) = delete;

    /// Looks now instead of at the next interval, e.g. right after a job was sent.
    void look_now();

    /// One pass over the journal. Public for the worker and for tests of a live simulator.
    static void look_once(const ChangeFn &on_change);

private:
    void run();

    ChangeFn                m_on_change;
    std::mutex              m_mutex;
    std::condition_variable m_wake;
    bool                    m_stop{false};
    bool                    m_look{false};
    std::thread             m_thread;
};

} // namespace Slic3r

#endif // slic3r_U1JobWatcher_hpp_
