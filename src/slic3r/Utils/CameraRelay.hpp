#ifndef slic3r_CameraRelay_hpp_
#define slic3r_CameraRelay_hpp_

#include <atomic>
#include <chrono>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace Slic3r {

/// Hands a printer's camera stream to the printer's own web page in a form the embedded web view gives to a
/// page's script. WebKitGTK refuses multipart/x-mixed-replace to fetch(); the same bytes under the type
/// multipart/mixed it passes on as they arrive. The web view's extension (GUI/webext/nia_web_extension.c)
/// sends the page's camera requests here; this relay, listening on this computer only, reads the camera and
/// answers with the stream.
///
/// What it serves is narrow: a request that carries this run's secret, for an address on a printer whose page
/// this application loaded (allow_host), that the printer's own list of cameras names as a stream. Anything
/// else is answered with a redirect to the address itself, so the page gets what it would have had. The
/// camera's own Access-Control-Allow-Origin is passed on unchanged, and added only for a camera of the
/// page's own origin: the page reads nothing through here that a browser would not let it read.
class CameraRelay
{
public:
    static CameraRelay &instance();
    ~CameraRelay();

    /// Starts listening (once). Returns where: "http://127.0.0.1:<port>/<secret>"; empty when it could not.
    std::string start();
    /// A printer whose page the application shows; *host* without port.
    void allow_host(const std::string &host);
    /// The printer's page brought its web process down with the stream; nothing of that printer is served again
    /// in this run, so that its page comes back as it was without the relay.
    void deny_host(const std::string &host);
    /// Whether a stream of that printer is being passed on now, or was until less than *seconds* ago: the mark
    /// of a page that had the stream when something happened to it.
    bool had_stream(const std::string &host, int seconds);
    void stop();

    /// An address taken apart. *port* is the scheme's own when none is written.
    struct Address
    {
        std::string scheme, host, path, query;
        int         port = 0;
        std::string origin() const; ///< "http://host:port", an IPv6 host in brackets
    };
    static bool parse(const std::string &url, Address &address);
    /// Whether *asked* is the stream a camera list entry *listed* names. *listed* may be a path only; it is then
    /// on *base* (the printer's "http://host[:port]"). Origin and path must be equal and every name=value of
    /// the entry's query must be in the asked query; the page may add its own (a cache buster).
    static bool names(const std::string &listed, const std::string &base, const std::string &asked);
    /// The stream addresses in Moonraker's answer to /server/webcams/list.
    static std::vector<std::string> listed_streams(const std::string &body);
    /// The type to hand a stream back under: multipart/x-mixed-replace becomes multipart/mixed with the same
    /// parameters, unless the asker is a picture element (its Accept names image types), which plays the
    /// stream as it is. Any other type is unchanged.
    static std::string type_for(const std::string &type, const std::string &accept);
    static std::string unescape(const std::string &text);

private:
    CameraRelay() = default;
    void serve();
    void answer(int socket);
    std::vector<std::string> cameras_of(const Address &printer);

    std::mutex            m_mutex;
    std::string           m_where, m_secret;
    std::set<std::string> m_hosts, m_denied;
    struct Passed
    {
        int                                   open = 0; ///< streams of this printer being passed on now
        std::chrono::steady_clock::time_point last;     ///< when one last ended
        bool                                  any = false;
    };
    std::map<std::string, Passed> m_passed; ///< by printer host
    struct Listed
    {
        std::vector<std::string>              streams;
        std::chrono::steady_clock::time_point read;
    };
    std::map<std::string, Listed> m_listed; ///< by printer origin
    int                           m_listener = -1;
    std::set<int>                 m_sockets; ///< connections being answered, so that stop() can end them
    std::atomic<bool>             m_stop{false};
    std::atomic<int>              m_open{0};
    std::thread                   m_thread;
};

} // namespace Slic3r

#endif // slic3r_CameraRelay_hpp_
