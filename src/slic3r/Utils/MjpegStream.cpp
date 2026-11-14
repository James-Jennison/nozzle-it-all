#include "MjpegStream.hpp"

#include <curl/curl.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <utility>

namespace Slic3r {

namespace {

// Where the picture that starts at the beginning of *b* (FF D8) ends, one past its FF D9; 0 while not all
// of it has arrived. A JPEG file is a row of segments, each a marker and a length, and the first FF D9
// in the bytes need not be the picture's end: a camera may put a small picture of its own (a thumbnail,
// with its own FF D8 .. FF D9) inside a segment. So the segments are stepped over by their lengths, and only
// in the compressed data after a start-of-scan segment, where an FF is always followed by 00 or a restart
// marker, is the next marker looked for. Bytes that are no segment where one must be are not a JPEG file
// as the standard has it; then the first FF D9 from there on is taken, as the simplest reading.
size_t picture_end(const std::string &b)
{
    const auto at = [&b](size_t i) { return static_cast<unsigned char>(b[i]); };
    size_t pos     = 2;
    bool   in_scan = false;
    for (;;) {
        if (in_scan) {
            const size_t ff = b.find('\xFF', pos);
            if (ff == std::string::npos || ff + 1 >= b.size())
                return 0;
            const unsigned char next = at(ff + 1);
            if (next == 0x00 || (next >= 0xD0 && next <= 0xD7))
                pos = ff + 2; // a stuffed FF or a restart marker: still data
            else if (next == 0xFF)
                pos = ff + 1; // fill
            else {
                pos     = ff;
                in_scan = false;
            }
            continue;
        }
        if (pos + 1 >= b.size())
            return 0;
        if (at(pos) != 0xFF) {
            const size_t end = b.find(std::string("\xFF\xD9", 2), pos);
            return end == std::string::npos ? 0 : end + 2;
        }
        const unsigned char marker = at(pos + 1);
        if (marker == 0xFF) {
            ++pos; // fill before a marker
            continue;
        }
        if (marker == 0xD9)
            return pos + 2;
        if (marker == 0x01 || marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) {
            pos += 2; // markers without a length
            continue;
        }
        if (pos + 3 >= b.size())
            return 0;
        const size_t length = (static_cast<size_t>(at(pos + 2)) << 8) | at(pos + 3);
        if (length < 2) {
            const size_t end = b.find(std::string("\xFF\xD9", 2), pos);
            return end == std::string::npos ? 0 : end + 2;
        }
        if (pos + 2 + length > b.size())
            return 0;
        pos += 2 + length;
        in_scan = marker == 0xDA;
    }
}

} // namespace

bool MjpegStream::next_frame(std::string &buffer, std::string &frame, size_t limit)
{
    static const std::string start("\xFF\xD8\xFF", 3);
    const size_t from = buffer.find(start);
    if (from == std::string::npos) {
        // Nothing of a picture yet. The last two bytes may be the beginning of a start marker.
        if (buffer.size() > 2)
            buffer.erase(0, buffer.size() - 2);
        return false;
    }
    if (from > 0)
        buffer.erase(0, from);
    const size_t to = picture_end(buffer);
    if (to == 0) {
        if (buffer.size() > limit)
            buffer.clear(); // not a stream of pictures, or one far too large
        return false;
    }
    frame.assign(buffer, 0, to);
    buffer.erase(0, to);
    return true;
}

bool MjpegStream::allows_origin(const std::string &headers, const std::string &origin)
{
    static const std::string name = "access-control-allow-origin:";
    const auto same = [](const std::string &a, const std::string &b) {
        return a.size() == b.size() && std::equal(a.begin(), a.end(), b.begin(), [](unsigned char x, unsigned char y) { return std::tolower(x) == std::tolower(y); });
    };
    for (size_t line = 0; line < headers.size();) {
        size_t end = headers.find('\n', line);
        if (end == std::string::npos)
            end = headers.size();
        if (end - line >= name.size() && same(headers.substr(line, name.size()), name)) {
            std::string value = headers.substr(line + name.size(), end - line - name.size());
            value.erase(0, value.find_first_not_of(" \t"));
            value.erase(value.find_last_not_of(" \t\r") + 1);
            if (value == "*" || same(value, origin))
                return true;
        }
        line = end + 1;
    }
    return false;
}

namespace {

struct Reader
{
    std::string                            buffer;
    const MjpegStream::FrameFn            *on_frame = nullptr;
    std::atomic<bool>                     *stop     = nullptr;
    std::atomic<unsigned long>            *frames   = nullptr;
    std::chrono::steady_clock::duration    gap{};
    std::chrono::steady_clock::time_point  last{};
    std::string                            headers; ///< the answer's header lines, kept only when leave is needed
    const std::string                     *origin  = nullptr;
    std::atomic<bool>                     *refused = nullptr;
    bool                                   leave_checked = false;
};

size_t on_header(char *data, size_t size, size_t count, void *user)
{
    auto *reader = static_cast<Reader *>(user);
    if (!reader->origin->empty() && reader->headers.size() < 64 * 1024)
        reader->headers.append(data, size * count);
    return size * count;
}

size_t on_data(char *data, size_t size, size_t count, void *user)
{
    auto *reader = static_cast<Reader *>(user);
    if (reader->stop->load())
        return 0; // ends the transfer
    if (!reader->origin->empty() && !reader->leave_checked) {
        reader->leave_checked = true;
        if (!MjpegStream::allows_origin(reader->headers, *reader->origin)) {
            reader->refused->store(true);
            return 0;
        }
    }
    reader->buffer.append(data, size * count);
    std::string frame;
    while (MjpegStream::next_frame(reader->buffer, frame)) {
        const auto now = std::chrono::steady_clock::now();
        if (now - reader->last < reader->gap)
            continue; // faster than wanted: dropped
        reader->last = now;
        ++*reader->frames;
        (*reader->on_frame)(std::move(frame));
        frame.clear();
    }
    return size * count;
}

int on_progress(void *user, curl_off_t, curl_off_t, curl_off_t, curl_off_t)
{
    return static_cast<Reader *>(user)->stop->load() ? 1 : 0;
}

} // namespace

MjpegStream::MjpegStream(std::string url, FrameFn on_frame, int max_per_second, Limits limits)
    : m_url(std::move(url)), m_on_frame(std::move(on_frame)), m_max_per_second(max_per_second < 1 ? 1 : max_per_second), m_limits(std::move(limits))
{
    m_thread = std::thread([this] { run(); });
}

MjpegStream::~MjpegStream()
{
    m_stop.store(true);
    if (m_thread.joinable())
        m_thread.join();
}

void MjpegStream::run()
{
    Reader reader;
    reader.on_frame = &m_on_frame;
    reader.stop     = &m_stop;
    reader.frames   = &m_frames;
    reader.gap      = std::chrono::milliseconds(1000 / m_max_per_second);
    reader.origin   = &m_limits.leave_for_origin;
    reader.refused  = &m_refused;
    if (CURL *curl = ::curl_easy_init()) {
        ::curl_easy_setopt(curl, CURLOPT_URL, m_url.c_str());
        ::curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
        ::curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, m_limits.redirects ? 1L : 0L);
        ::curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, on_header);
        ::curl_easy_setopt(curl, CURLOPT_HEADERDATA, &reader);
        ::curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 3L);
        ::curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 5L);
        // A camera that sends nothing for 15 seconds is given up; the owner may start it again.
        ::curl_easy_setopt(curl, CURLOPT_LOW_SPEED_LIMIT, 1L);
        ::curl_easy_setopt(curl, CURLOPT_LOW_SPEED_TIME, 15L);
        ::curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, on_data);
        ::curl_easy_setopt(curl, CURLOPT_WRITEDATA, &reader);
        ::curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
        ::curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, on_progress);
        ::curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &reader);
        ::curl_easy_perform(curl);
        ::curl_easy_cleanup(curl);
    }
    m_ended.store(true);
}

} // namespace Slic3r
