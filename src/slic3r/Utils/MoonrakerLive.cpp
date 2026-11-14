#include "MoonrakerLive.hpp"

#include <memory>

#include "Http.hpp"

#include <boost/asio/connect.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/log/trivial.hpp>
#include <nlohmann/json.hpp>

#include <boost/asio/io_context.hpp>

#include <chrono>
#include <stdexcept>
#include <string>

namespace Slic3r {

namespace {

using json = nlohmann::json;

constexpr int SUBSCRIBE_ID = 1;
// Moonraker pushes proc stats every second, so a quiet socket means a dead link.
// Moonraker sends process statistics about once a second, so this much silence means the link is gone. It is
// generous on purpose: a quiet but healthy printer must not be reported as unreachable.
constexpr auto READ_TIMEOUT = std::chrono::seconds(15);
// Coalesce bursts of status deltas into at most a few repaints per second.
constexpr auto EMIT_INTERVAL = std::chrono::milliseconds(250);

std::optional<json> get_json(const std::string &url)
{
    std::optional<json> out;
    Http::get(url)
        .timeout_connect(5)
        .timeout_max(10)
        .on_complete([&](std::string body, unsigned) {
            auto parsed = json::parse(body, nullptr, false);
            if (!parsed.is_discarded())
                out = std::move(parsed);
        })
        .perform_sync();
    return out;
}

void split_host_port(const std::string &base_url, std::string &host, std::string &port)
{
    host = base_url.substr(base_url.find("://") == std::string::npos ? 0 : base_url.find("://") + 3);
    port = "80";
    if (host.size() > 1 && host.front() == '[') { // IPv6 literal
        const auto close = host.find(']');
        if (close != std::string::npos && close + 1 < host.size() && host[close + 1] == ':')
            port = host.substr(close + 2);
        host = host.substr(1, close - 1);
        return;
    }
    if (const auto colon = host.rfind(':'); colon != std::string::npos) {
        port = host.substr(colon + 1);
        host = host.substr(0, colon);
    }
}

} // namespace

MoonrakerLiveClient::MoonrakerLiveClient(std::string base_url, Callbacks callbacks)
    : m_base_url(std::move(base_url)), m_callbacks(std::move(callbacks))
{
    m_thread = std::thread([this] { run(); });
}

MoonrakerLiveClient::~MoonrakerLiveClient()
{
    m_stop = true;
    if (m_thread.joinable())
        m_thread.join();
}

void MoonrakerLiveClient::run()
{
    int backoff_s = 1;
    while (!m_stop) {
        if (m_callbacks.link)
            m_callbacks.link(Link::Connecting, {});
        if (session())
            backoff_s = 1; // had a working session; retry quickly
        if (m_stop)
            break;
        for (int i = 0; i < backoff_s * 10 && !m_stop; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        backoff_s = std::min(backoff_s * 2, 10);
    }
}

bool MoonrakerLiveClient::session()
{
    namespace beast     = boost::beast;
    namespace websocket = beast::websocket;
    using tcp           = boost::asio::ip::tcp;

    const auto objects = get_json(m_base_url + "/printer/objects/list");
    if (!objects || !objects->contains("result")) {
        if (m_callbacks.link)
            m_callbacks.link(Link::Unreachable, "The printer did not answer at " + m_base_url + ".");
        return false;
    }
    const auto webcams = get_json(m_base_url + "/server/webcams/list");
    const PrinterPanel::Capabilities caps = PrinterPanel::discover(
        (*objects)["result"].value("objects", json::array()),
        webcams ? webcams->value("result", json::object()).value("webcams", json::array()) : json());
    if (m_callbacks.capabilities)
        m_callbacks.capabilities(caps);

    PrinterPanel::StatusTracker tracker(caps);
    std::string                 filename;
    std::optional<double>       estimate;
    bool                        subscribed = false;

    try {
        std::string host, port;
        split_host_port(m_base_url, host, port);

        boost::asio::io_context              ioc;
        tcp::resolver                        resolver{ioc};
        websocket::stream<beast::tcp_stream> ws{ioc};
        // Every step on the socket is asynchronous and is run to its end here. A time limit set with
        // expires_after only acts on asynchronous operations, and the destructor joins this thread from the
        // GUI thread: a blocking read on a printer that has gone silent would hang the whole window for as long
        // as the silence lasts. finish() also looks at m_stop every 100 ms and closes the socket when it is set.
        beast::error_code step_error;
        bool              step_done = false;
        const auto step = [&](const beast::error_code &ec, auto &&...) {
            step_error = ec;
            step_done  = true;
        };
        const auto finish = [&](const char *what) {
            while (!step_done) {
                ioc.restart();
                ioc.run_for(std::chrono::milliseconds(100));
                if (m_stop && !step_done) {
                    // Closing the socket (and stopping a name lookup) ends the pending operation with "aborted".
                    // The wait for that is bounded too, so stopping cannot hang whatever the operation does.
                    beast::error_code ignored;
                    resolver.cancel();
                    beast::get_lowest_layer(ws).socket().close(ignored);
                    for (int i = 0; i < 10 && !step_done; ++i) {
                        ioc.restart();
                        ioc.run_for(std::chrono::milliseconds(100));
                    }
                    if (!step_done)
                        BOOST_LOG_TRIVIAL(warning) << "Printer panel: an operation on the link to " << m_base_url << " did not end when it was closed";
                    break;
                }
            }
            step_done = false;
            if (m_stop)
                throw std::runtime_error("stopped");
            if (step_error)
                throw std::runtime_error(std::string(what) + ": " + step_error.message());
        };
        // The name lookup is asynchronous as well: a resolver that does not answer must not hold this thread.
        tcp::resolver::results_type endpoints;
        resolver.async_resolve(host, port, [&](const beast::error_code &ec, tcp::resolver::results_type found) {
            endpoints = std::move(found);
            step(ec);
        });
        finish("looking up the printer's address");
        beast::get_lowest_layer(ws).expires_after(std::chrono::seconds(10));
        beast::get_lowest_layer(ws).async_connect(endpoints, step);
        finish("connecting");
        beast::get_lowest_layer(ws).expires_after(std::chrono::seconds(10));
        ws.async_handshake(host + ":" + port, "/websocket", step);
        finish("opening the websocket");

        const json subscribe = {{"jsonrpc", "2.0"},
                                {"method", "printer.objects.subscribe"},
                                {"params", {{"objects", PrinterPanel::subscription(caps)}}},
                                {"id", SUBSCRIBE_ID}};
        const std::string subscribe_text = subscribe.dump();
        beast::get_lowest_layer(ws).expires_after(std::chrono::seconds(10));
        ws.async_write(boost::asio::buffer(subscribe_text), step);
        finish("subscribing");

        auto last_emit = std::chrono::steady_clock::now() - EMIT_INTERVAL;
        bool dirty     = false;
        while (!m_stop) {
            // A printer that sends nothing for READ_TIMEOUT is treated as gone: the read fails, this session
            // ends, and run() reports the link as lost and tries again.
            beast::get_lowest_layer(ws).expires_after(READ_TIMEOUT);
            beast::flat_buffer buffer;
            ws.async_read(buffer, step);
            finish("reading");
            const json msg = json::parse(beast::buffers_to_string(buffer.data()), nullptr, false);
            if (msg.is_discarded())
                continue;

            const std::string method = msg.value("method", "");
            if (msg.value("id", -1) == SUBSCRIBE_ID) {
                if (msg.contains("error"))
                    throw std::runtime_error(msg["error"].value("message", std::string("subscribe failed")));
                tracker.merge(msg["result"].value("status", json::object()));
                subscribed = true;
                dirty      = true;
                if (m_callbacks.link)
                    m_callbacks.link(Link::Connected, {});
            } else if (method == "notify_status_update" && msg.contains("params") && !msg["params"].empty()) {
                tracker.merge(msg["params"][0]);
                dirty = true;
            } else if (method == "notify_klippy_ready" || method == "notify_klippy_disconnected" ||
                       method == "notify_klippy_shutdown") {
                // Objects can change across a Klipper restart: rediscover.
                break;
            }

            if (dirty && subscribed && std::chrono::steady_clock::now() - last_emit >= EMIT_INTERVAL) {
                PrinterPanel::Snapshot snap = tracker.snapshot(estimate);
                if (snap.filename != filename) {
                    filename = snap.filename;
                    estimate = filename.empty() ? std::nullopt : estimated_time(filename);
                    snap     = tracker.snapshot(estimate);
                }
                if (m_callbacks.snapshot)
                    m_callbacks.snapshot(snap);
                last_emit = std::chrono::steady_clock::now();
                dirty     = false;
            }
        }
        // No closing handshake: it would wait on the printer. Dropping the stream closes the socket.
    } catch (const std::exception &e) {
        if (!m_stop)
            BOOST_LOG_TRIVIAL(warning) << "Printer panel: link to " << m_base_url << " dropped: " << e.what();
        if (m_callbacks.link && !m_stop)
            m_callbacks.link(Link::Unreachable, e.what());
    }
    return subscribed;
}

std::optional<double> MoonrakerLiveClient::estimated_time(const std::string &filename) const
{
    const auto meta = get_json(m_base_url + "/server/files/metadata?filename=" + Http::url_encode(filename));
    if (!meta)
        return std::nullopt;
    const json result = meta->value("result", json::object());
    const auto it     = result.find("estimated_time");
    if (it != result.end() && it->is_number() && it->get<double>() > 0.)
        return it->get<double>();
    return std::nullopt;
}

void MoonrakerLiveClient::send_gcode(const std::string &script, ErrorFn on_error) const
{
    post("/printer/gcode/script", json{{"script", script}}.dump(), std::move(on_error));
}

void MoonrakerLiveClient::post(const std::string &path, const std::string &json_body, ErrorFn on_error) const
{
    BOOST_LOG_TRIVIAL(warning) << "Printer panel: POST " << m_base_url << path << " " << json_body;
    Http::post(m_base_url + path)
        .header("Content-Type", "application/json")
        .set_post_body(json_body.empty() ? std::string("{}") : json_body)
        .timeout_connect(5)
        .timeout_max(30)
        .on_error([on_error](std::string body, std::string error, unsigned status) {
            std::string message = error;
            const auto  reply   = json::parse(body, nullptr, false);
            if (!reply.is_discarded() && reply.contains("error"))
                message = reply["error"].value("message", error);
            else if (status != 0)
                message = "HTTP " + std::to_string(status);
            if (on_error)
                on_error(message);
        })
        .perform();
}

void MoonrakerLiveClient::send_once(const std::string &script, SentFn done) const
{
    BOOST_LOG_TRIVIAL(warning) << "Printer panel: POST " << m_base_url << "/printer/gcode/script " << script;
    Http::post(m_base_url + "/printer/gcode/script")
        .header("Content-Type", "application/json")
        .set_post_body(json{{"script", script}}.dump())
        .timeout_connect(5)
        .timeout_max(90)
        .on_complete([done](std::string, unsigned) { done(Sent::Accepted, {}); })
        .on_error([done](std::string body, std::string error, unsigned status) {
            const auto reply = json::parse(body, nullptr, false);
            // Only a 4xx is the printer's refusal. After a 5xx the script may have run or be running.
            if (status >= 400 && status < 500) {
                done(Sent::Rejected, !reply.is_discarded() && reply.is_object() && reply.contains("error") && reply["error"].is_object() ?
                                         reply["error"].value("message", error) : "HTTP " + std::to_string(status));
                return;
            }
            done(Sent::Unknown, error);
        })
        .perform();
}

void MoonrakerLiveClient::resume_guarded(const std::string &shown_file, std::function<void()> on_sending, ResumeFn done) const
{
    const std::string base  = m_base_url;
    const std::string query = base + "/printer/objects/query?print_stats";
    // Reads print_stats and hands the judged state on. No answer is a refusal.
    const auto read = [query, shown_file](std::function<void(U1Controls::ResumeCheck)> then) {
        auto answered = std::make_shared<bool>(false);
        Http::get(query)
            .timeout_connect(5)
            .timeout_max(5)
            .on_complete([then, answered, shown_file](std::string body, unsigned) {
                *answered         = true;
                const auto  reply = json::parse(body, nullptr, false);
                const json  status = !reply.is_discarded() && reply.is_object() && reply.contains("result") && reply["result"].is_object() ?
                                         reply["result"].value("status", json()) : json();
                then(U1Controls::check_resume(status, shown_file));
            })
            .on_error([then, answered](std::string, std::string, unsigned) {
                if (!*answered)
                    then(U1Controls::ResumeCheck{});
            })
            .perform();
    };
    read([base, read, on_sending = std::move(on_sending), done = std::move(done)](U1Controls::ResumeCheck check) {
        ResumeResult result;
        result.check = check;
        if (!check) {
            done(result);
            return;
        }
        if (on_sending)
            on_sending();
        BOOST_LOG_TRIVIAL(warning) << "Printer panel: POST " << base << "/printer/print/resume (guarded, sent once)";
        Http::post(base + "/printer/print/resume")
            .header("Content-Type", "application/json")
            .set_post_body(std::string("{}"))
            .timeout_connect(5)
            .timeout_max(30)
            .on_complete([done, result](std::string, unsigned) mutable {
                result.kind = ResumeResult::Kind::Accepted;
                done(result);
            })
            .on_error([done, result, read](std::string body, std::string error, unsigned status) mutable {
                const auto reply = json::parse(body, nullptr, false);
                if (status >= 400 && status < 500) {
                    // The printer answered and refused: nothing ran.
                    result.kind   = ResumeResult::Kind::Rejected;
                    result.detail = !reply.is_discarded() && reply.is_object() && reply.contains("error") && reply["error"].is_object() ?
                                        reply["error"].value("message", error) : "HTTP " + std::to_string(status);
                    done(result);
                    return;
                }
                // It may or may not have run. The printer is asked what it shows now; the Resume is not sent again.
                result.kind   = ResumeResult::Kind::Unknown;
                result.detail = status != 0 ? "HTTP " + std::to_string(status) : error;
                read([done, result](U1Controls::ResumeCheck after) mutable {
                    result.after = after;
                    done(result);
                });
            })
            .perform();
    });
}

} // namespace Slic3r
