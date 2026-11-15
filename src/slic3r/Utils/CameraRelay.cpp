#include "CameraRelay.hpp"

#include <boost/log/trivial.hpp>
#include <curl/curl.h>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <future>
#include <random>
#include <utility>

#ifdef __linux__
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#endif

namespace Slic3r {

namespace {

std::string lower(std::string text)
{
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    return text;
}

std::string trimmed(std::string text)
{
    text.erase(0, text.find_first_not_of(" \t\r\n"));
    text.erase(text.find_last_not_of(" \t\r\n") + 1);
    return text;
}

// The name=value pairs of a query, as written.
std::vector<std::string> pairs_of(const std::string &query)
{
    std::vector<std::string> pairs;
    for (size_t from = 0; from <= query.size();) {
        size_t to = query.find('&', from);
        if (to == std::string::npos)
            to = query.size();
        if (to > from)
            pairs.push_back(query.substr(from, to - from));
        from = to + 1;
    }
    return pairs;
}

} // namespace

std::string CameraRelay::Address::origin() const
{
    return scheme + "://" + (host.find(':') != std::string::npos ? "[" + host + "]" : host) + ":" + std::to_string(port);
}

bool CameraRelay::parse(const std::string &url, Address &address)
{
    const size_t colon = url.find("://");
    if (colon == std::string::npos)
        return false;
    address        = Address();
    address.scheme = lower(url.substr(0, colon));
    if (address.scheme != "http" && address.scheme != "https")
        return false;
    size_t      at   = colon + 3;
    size_t      end  = url.find_first_of("/?#", at);
    std::string host = url.substr(at, end == std::string::npos ? std::string::npos : end - at);
    if (host.empty() || host.find('@') != std::string::npos) // no address with a user name in it
        return false;
    address.port = address.scheme == "https" ? 443 : 80;
    std::string port;
    if (host.front() == '[') { // an IPv6 address
        const size_t close = host.find(']');
        if (close == std::string::npos)
            return false;
        if (close + 1 < host.size()) {
            if (host[close + 1] != ':')
                return false;
            port = host.substr(close + 2);
        }
        host = host.substr(1, close - 1);
    } else if (const size_t p = host.rfind(':'); p != std::string::npos) {
        port = host.substr(p + 1);
        host = host.substr(0, p);
    }
    if (!port.empty()) {
        if (port.size() > 5 || !std::all_of(port.begin(), port.end(), [](unsigned char c) { return std::isdigit(c) != 0; }))
            return false;
        address.port = std::atoi(port.c_str());
        if (address.port < 1 || address.port > 65535)
            return false;
    }
    if (host.empty())
        return false;
    address.host = lower(host);
    address.path = "/";
    if (end != std::string::npos && url[end] == '/') {
        const size_t stop = url.find_first_of("?#", end);
        address.path      = url.substr(end, stop == std::string::npos ? std::string::npos : stop - end);
        end               = stop;
    }
    if (end != std::string::npos && url[end] == '?') {
        const size_t stop = url.find('#', end);
        address.query     = url.substr(end + 1, stop == std::string::npos ? std::string::npos : stop - end - 1);
    }
    return true;
}

bool CameraRelay::names(const std::string &listed, const std::string &base, const std::string &asked)
{
    Address entry, want;
    if (listed.empty() || !parse(listed.front() == '/' ? base + listed : listed, entry) || !parse(asked, want))
        return false;
    if (entry.origin() != want.origin() || entry.path != want.path)
        return false;
    const std::vector<std::string> have = pairs_of(want.query);
    for (const std::string &pair : pairs_of(entry.query))
        if (std::find(have.begin(), have.end(), pair) == have.end())
            return false;
    return true;
}

std::vector<std::string> CameraRelay::listed_streams(const std::string &body)
{
    std::vector<std::string> streams;
    const auto doc = nlohmann::json::parse(body, nullptr, false);
    if (doc.is_discarded() || !doc.is_object() || !doc.contains("result") || !doc["result"].is_object() || !doc["result"].contains("webcams") ||
        !doc["result"]["webcams"].is_array())
        return streams;
    for (const auto &camera : doc["result"]["webcams"])
        if (camera.is_object() && camera.contains("stream_url") && camera["stream_url"].is_string() && !camera["stream_url"].get<std::string>().empty())
            streams.push_back(camera["stream_url"].get<std::string>());
    return streams;
}

std::string CameraRelay::type_for(const std::string &type, const std::string &accept)
{
    static const std::string refused = "multipart/x-mixed-replace";
    const std::string        kind    = trimmed(type);
    if (kind.size() < refused.size() || lower(kind.substr(0, refused.size())) != refused)
        return type;
    if (lower(accept).find("image/") != std::string::npos)
        return type; // a picture element plays it as it is
    return "multipart/mixed" + kind.substr(refused.size());
}

std::string CameraRelay::unescape(const std::string &text)
{
    std::string out;
    for (size_t i = 0; i < text.size(); ++i)
        if (text[i] == '%' && i + 2 < text.size() && std::isxdigit(static_cast<unsigned char>(text[i + 1])) != 0 &&
            std::isxdigit(static_cast<unsigned char>(text[i + 2])) != 0) {
            out.push_back(char(std::strtol(text.substr(i + 1, 2).c_str(), nullptr, 16)));
            i += 2;
        } else
            out.push_back(text[i]);
    return out;
}

CameraRelay &CameraRelay::instance()
{
    static CameraRelay relay;
    return relay;
}

CameraRelay::~CameraRelay() { stop(); }

void CameraRelay::allow_host(const std::string &host)
{
    if (host.empty())
        return;
    std::lock_guard<std::mutex> lock(m_mutex);
    m_hosts.insert(lower(host));
}

#ifndef __linux__

std::string CameraRelay::start() { return {}; }
void        CameraRelay::stop() {}
void        CameraRelay::serve() {}
void        CameraRelay::answer(int) {}
std::vector<std::string> CameraRelay::cameras_of(const Address &) { return {}; }

#else

namespace {

bool send_all(int socket, const char *data, size_t size)
{
    while (size > 0) {
        const ssize_t sent = ::send(socket, data, size, MSG_NOSIGNAL);
        if (sent <= 0)
            return false;
        data += sent;
        size -= size_t(sent);
    }
    return true;
}

bool send_all(int socket, const std::string &text) { return send_all(socket, text.data(), text.size()); }

size_t to_string(char *data, size_t size, size_t count, void *user)
{
    auto *text = static_cast<std::string *>(user);
    if (text->size() + size * count > 256 * 1024)
        return 0; // no camera list is this long
    text->append(data, size * count);
    return size * count;
}

// One read request, answered whole. Empty when it failed or was not answered with 200.
std::string get(const std::string &url)
{
    std::string body;
    if (CURL *curl = ::curl_easy_init()) {
        long status = 0;
        ::curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
        ::curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
        ::curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
        ::curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 3L);
        ::curl_easy_setopt(curl, CURLOPT_TIMEOUT, 5L);
        ::curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, to_string);
        ::curl_easy_setopt(curl, CURLOPT_WRITEDATA, &body);
        const CURLcode done = ::curl_easy_perform(curl);
        ::curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
        ::curl_easy_cleanup(curl);
        if (done != CURLE_OK || status != 200)
            body.clear();
    }
    return body;
}

// What is passed on from the camera to the page, and how.
struct Passing
{
    int                socket = -1;
    std::atomic<bool> *stop   = nullptr;
    std::string        accept, page_origin, camera_origin;
    std::string        status = "200 OK", type, leave; ///< of the answer the body comes with
    bool               head_sent = false, broken = false;
};

std::string head_of(const Passing &passing, bool with_body)
{
    std::string head = "HTTP/1.1 " + passing.status + "\r\n";
    if (!passing.type.empty())
        head += "Content-Type: " + CameraRelay::type_for(passing.type, passing.accept) + "\r\n";
    // The camera's own leave, unchanged; for a camera of the page's own origin the page needs none, and
    // gets it here because this relay is another origin.
    if (!passing.leave.empty())
        head += "Access-Control-Allow-Origin: " + passing.leave + "\r\n";
    else if (!passing.page_origin.empty() && passing.camera_origin == passing.page_origin)
        head += "Access-Control-Allow-Origin: *\r\n";
    if (!with_body)
        head += "Content-Length: 0\r\n";
    return head + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
}

size_t on_header(char *data, size_t size, size_t count, void *user)
{
    auto             *passing = static_cast<Passing *>(user);
    const std::string line(data, size * count);
    if (line.compare(0, 5, "HTTP/") == 0) { // a new answer: what an interim one said is dropped
        const size_t space = line.find(' ');
        passing->status    = space == std::string::npos ? "200 OK" : trimmed(line.substr(space + 1));
        passing->type.clear();
        passing->leave.clear();
        return size * count;
    }
    const size_t colon = line.find(':');
    if (colon == std::string::npos)
        return size * count;
    const std::string name = lower(line.substr(0, colon)), value = trimmed(line.substr(colon + 1));
    if (name == "content-type")
        passing->type = value;
    else if (name == "access-control-allow-origin")
        passing->leave = value;
    return size * count;
}

size_t on_body(char *data, size_t size, size_t count, void *user)
{
    auto *passing = static_cast<Passing *>(user);
    if (passing->stop->load())
        return 0;
    if (!passing->head_sent) {
        passing->head_sent = true;
        if (!send_all(passing->socket, head_of(*passing, true))) {
            passing->broken = true;
            return 0;
        }
    }
    if (!send_all(passing->socket, data, size * count)) {
        passing->broken = true; // the page closed the stream
        return 0;
    }
    return size * count;
}

int on_progress(void *user, curl_off_t, curl_off_t, curl_off_t, curl_off_t) { return static_cast<Passing *>(user)->stop->load() ? 1 : 0; }

std::string value_of(const std::string &query, const std::string &name)
{
    for (const std::string &pair : pairs_of(query))
        if (pair.compare(0, name.size() + 1, name + "=") == 0)
            return CameraRelay::unescape(pair.substr(name.size() + 1));
    return {};
}

} // namespace

std::string CameraRelay::start()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_where.empty() || m_listener >= 0)
        return m_where;
    const int listener = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0)
        return {};
    sockaddr_in address{};
    address.sin_family      = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // this computer only
    address.sin_port        = 0;                      // any free port
    socklen_t length        = sizeof(address);
    if (::bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0 || ::listen(listener, 8) != 0 ||
        ::getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) != 0) {
        ::close(listener);
        return {};
    }
    // The secret: without it nothing is served, so that no other program or page on this computer can use the relay.
    std::random_device random;
    static const char  digits[] = "0123456789abcdef";
    m_secret.clear();
    for (int i = 0; i < 32; ++i)
        m_secret.push_back(digits[random() & 15]);
    m_listener = listener;
    m_where    = "http://127.0.0.1:" + std::to_string(ntohs(address.sin_port)) + "/" + m_secret;
    m_stop.store(false);
    m_thread = std::thread([this] { serve(); });
    return m_where;
}

void CameraRelay::stop()
{
    int listener = -1;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_stop.store(true);
        std::swap(listener, m_listener);
        m_where.clear();
    }
    if (listener >= 0) {
        ::shutdown(listener, SHUT_RDWR);
        ::close(listener);
    }
    if (m_thread.joinable())
        m_thread.join(); // it waits for the streams it is passing on; each ends within about a second
}

void CameraRelay::serve()
{
    std::vector<std::future<void>> answering;
    for (;;) {
        int listener;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            listener = m_listener;
        }
        if (m_stop.load() || listener < 0)
            break;
        const int socket = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        if (socket < 0) {
            if (m_stop.load())
                break;
            continue;
        }
        answering.erase(std::remove_if(answering.begin(), answering.end(),
                                       [](const std::future<void> &done) { return done.wait_for(std::chrono::seconds(0)) == std::future_status::ready; }),
                        answering.end());
        // A page shows a handful of cameras at most.
        if (m_open.load() >= 8) {
            send_all(socket, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            ::close(socket);
            continue;
        }
        ++m_open;
        try {
            answering.push_back(std::async(std::launch::async, [this, socket] {
                answer(socket);
                ::close(socket);
                --m_open;
            }));
        } catch (const std::system_error &) {
            ::close(socket);
            --m_open;
        }
    }
    // The futures are waited for here: every stream being passed on sees m_stop and ends.
}

std::vector<std::string> CameraRelay::cameras_of(const Address &printer)
{
    const std::string origin = printer.origin();
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        const auto known = m_listed.find(origin);
        if (known != m_listed.end() && std::chrono::steady_clock::now() - known->second.read < std::chrono::seconds(60))
            return known->second.streams;
    }
    // The address the page came from, then Moonraker's own port when the page is on the scheme's own.
    std::string body = get(origin + "/server/webcams/list");
    if (body.empty() && (printer.port == 80 || printer.port == 443)) {
        Address direct = printer;
        direct.scheme  = "http";
        direct.port    = 7125;
        body           = get(direct.origin() + "/server/webcams/list");
    }
    std::vector<std::string>    streams = listed_streams(body);
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!body.empty()) // an unanswered question is asked again next time
        m_listed[origin] = Listed{streams, std::chrono::steady_clock::now()};
    return streams;
}

void CameraRelay::answer(int socket)
{
    timeval patience{5, 0};
    ::setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &patience, sizeof(patience));
    ::setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &patience, sizeof(patience));
    std::string request;
    char        chunk[2048];
    while (request.find("\r\n\r\n") == std::string::npos && request.size() < 16 * 1024) {
        const ssize_t got = ::recv(socket, chunk, sizeof(chunk), 0);
        if (got <= 0)
            return;
        request.append(chunk, size_t(got));
    }
    const auto refuse = [socket](const char *status) {
        send_all(socket, std::string("HTTP/1.1 ") + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    };
    // "GET /<secret>?u=<address>&p=<page origin> HTTP/1.1"
    const size_t line_end = request.find("\r\n");
    const std::string line = request.substr(0, line_end);
    const size_t first = line.find(' '), last = line.rfind(' ');
    if (line.compare(0, 4, "GET ") != 0 || first == last)
        return refuse("405 Method Not Allowed");
    const std::string target = line.substr(first + 1, last - first - 1);
    const size_t      mark   = target.find('?');
    std::string       secret;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        secret = m_secret;
    }
    if (secret.empty() || mark == std::string::npos || target.substr(0, mark) != "/" + secret)
        return refuse("403 Forbidden");
    const std::string asked = value_of(target.substr(mark + 1), "u"), page = value_of(target.substr(mark + 1), "p");
    Address           camera, printer;
    if (asked.find_first_of("\r\n") != std::string::npos || !parse(asked, camera))
        return refuse("400 Bad Request");
    // What is not served is sent back to its own address: the page then gets what it would have had.
    const auto back = [socket, &asked] {
        send_all(socket, "HTTP/1.1 307 Temporary Redirect\r\nLocation: " + asked + "\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    };
    bool shown;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        shown = m_hosts.count(camera.host) != 0;
    }
    if (!shown || !parse(page, printer) || printer.host != camera.host)
        return back();
    const std::vector<std::string> streams = cameras_of(printer);
    if (std::none_of(streams.begin(), streams.end(), [&](const std::string &listed) { return names(listed, printer.origin(), asked); })) {
        BOOST_LOG_TRIVIAL(warning) << "Printer page camera: " << asked.substr(0, asked.find('?')) << " is not in the printer's list of cameras; left to the page";
        return back();
    }

    Passing passing;
    passing.socket        = socket;
    passing.stop          = &m_stop;
    passing.page_origin   = printer.origin();
    passing.camera_origin = camera.origin();
    for (size_t from = line_end; from != std::string::npos && from + 2 < request.size();) {
        const size_t      to     = request.find("\r\n", from + 2);
        const std::string header = request.substr(from + 2, to == std::string::npos ? std::string::npos : to - from - 2);
        if (lower(header.substr(0, 7)) == "accept:")
            passing.accept = trimmed(header.substr(7));
        from = to;
    }
    BOOST_LOG_TRIVIAL(warning) << "Printer page camera: passing on " << asked.substr(0, asked.find('?'));
    if (CURL *curl = ::curl_easy_init()) {
        ::curl_easy_setopt(curl, CURLOPT_URL, asked.c_str());
        ::curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
        ::curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
        ::curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 5L);
        // A camera that sends nothing for 15 seconds is given up; the page asks again.
        ::curl_easy_setopt(curl, CURLOPT_LOW_SPEED_LIMIT, 1L);
        ::curl_easy_setopt(curl, CURLOPT_LOW_SPEED_TIME, 15L);
        ::curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, on_header);
        ::curl_easy_setopt(curl, CURLOPT_HEADERDATA, &passing);
        ::curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, on_body);
        ::curl_easy_setopt(curl, CURLOPT_WRITEDATA, &passing);
        ::curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
        ::curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, on_progress);
        ::curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &passing);
        const CURLcode done = ::curl_easy_perform(curl);
        long           status = 0;
        ::curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
        ::curl_easy_cleanup(curl);
        if (!passing.head_sent && !passing.broken) {
            if (status != 0)
                send_all(socket, head_of(passing, false)); // an answer without a body, passed on as it was
            else
                refuse("502 Bad Gateway");
        }
        BOOST_LOG_TRIVIAL(warning) << "Printer page camera: " << asked.substr(0, asked.find('?')) << " ended ("
                                   << (passing.broken ? "the page closed it" : done == CURLE_OK ? "the camera closed it" : ::curl_easy_strerror(done)) << ")";
    } else
        refuse("502 Bad Gateway");
}

#endif // __linux__

} // namespace Slic3r
