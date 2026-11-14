#ifndef slic3r_MoonrakerLive_hpp_
#define slic3r_MoonrakerLive_hpp_

#include "libslic3r/PrinterSnapshot.hpp"
#include "U1Controls.hpp"

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace Slic3r {

/// Live connection to one Moonraker printer for the native printer panel.
///
/// A worker thread discovers the printer's capabilities over HTTP, subscribes
/// to its objects on /websocket and keeps a PrinterPanel::StatusTracker up to
/// date, reconnecting with backoff when the link drops. Callbacks run on the
/// worker thread; the GUI marshals them to the main thread itself.
class MoonrakerLiveClient
{
public:
    enum class Link { Connecting, Connected, Unreachable };

    struct Callbacks
    {
        std::function<void(const PrinterPanel::Capabilities &)> capabilities;
        std::function<void(const PrinterPanel::Snapshot &)>     snapshot;
        std::function<void(Link, const std::string &detail)>    link;
    };
    using ErrorFn = std::function<void(const std::string &error)>;

    /// *base_url* is the printer root, e.g. "http://192.168.1.113".
    MoonrakerLiveClient(std::string base_url, Callbacks callbacks);
    ~MoonrakerLiveClient();

    MoonrakerLiveClient(const MoonrakerLiveClient &)            = delete;
    MoonrakerLiveClient &operator=(const MoonrakerLiveClient &) = delete;

    const std::string &base_url() const { return m_base_url; }

    /// Controls sent once without a check: harmless to send twice. *on_error* runs on an HTTP thread.
    void send_gcode(const std::string &script, ErrorFn on_error) const;
    void pause(ErrorFn on_error) const { post("/printer/print/pause", "{}", std::move(on_error)); }
    void cancel(ErrorFn on_error) const { post("/printer/print/cancel", "{}", std::move(on_error)); }

    /// How a control sent once ended.
    enum class Sent {
        Accepted, ///< the printer answered that it ran
        Rejected, ///< the printer answered and refused (HTTP 4xx)
        Unknown   ///< no usable answer in time, or a server error; it may be running, may have run, or may never have arrived
    };
    using SentFn = std::function<void(Sent, const std::string &detail)>;
    /// Sends *script* once and reports how it ended. It is never sent again from here. A script that moves or
    /// heats can take longer than the wait; that is Unknown, not a failure. *done* runs on an HTTP thread.
    void send_once(const std::string &script, SentFn done) const;

    /// How a guarded Resume went.
    struct ResumeResult
    {
        enum class Kind {
            Refused,  ///< not sent: see *check*
            Accepted, ///< sent once; the printer answered that it took it
            Rejected, ///< sent once; the printer answered and refused
            Unknown   ///< sent once; no usable answer. *after* is what the printer reported when asked again
        };
        Kind                     kind = Kind::Refused;
        U1Controls::ResumeCheck  check;  ///< the read made just before sending
        std::string              detail; ///< the printer's refusal, or the transport error
        U1Controls::ResumeCheck  after;  ///< only for Unknown
    };
    using ResumeFn = std::function<void(const ResumeResult &)>;
    /// Reads the printer, and only if it is paused on *shown_file* sends one Resume. The Resume is never
    /// sent a second time: when its answer is lost the printer is read once more and that state is reported.
    /// *on_sending* runs just before the Resume leaves, so the caller can record it first. Both callbacks run
    /// on an HTTP thread.
    void resume_guarded(const std::string &shown_file, std::function<void()> on_sending, ResumeFn done) const;

private:
    void run();
    bool session();
    /// *json_body* must not be empty: Http posts without a body read from an
    /// unset curl read callback and crash.
    void post(const std::string &path, const std::string &json_body, ErrorFn on_error) const;
    std::optional<double> estimated_time(const std::string &filename) const;

    std::string       m_base_url;
    Callbacks         m_callbacks;
    std::atomic<bool> m_stop{false};
    std::thread       m_thread;
};

} // namespace Slic3r

#endif // slic3r_MoonrakerLive_hpp_
