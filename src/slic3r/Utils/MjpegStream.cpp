#include "MjpegStream.hpp"

#include <curl/curl.h>

#include <chrono>
#include <utility>

namespace Slic3r {

bool MjpegStream::next_frame(std::string &buffer, std::string &frame, size_t limit)
{
    // A JPEG picture starts with FF D8 FF and ends with FF D9. Inside its compressed data an FF is always
    // followed by 00 or a restart marker, so the first FF D9 after the start is its end.
    static const std::string start("\xFF\xD8\xFF", 3), end("\xFF\xD9", 2);
    const size_t from = buffer.find(start);
    if (from == std::string::npos) {
        // Nothing of a picture yet. The last two bytes may be the beginning of a start marker.
        if (buffer.size() > 2)
            buffer.erase(0, buffer.size() - 2);
        return false;
    }
    if (from > 0)
        buffer.erase(0, from);
    const size_t to = buffer.find(end, start.size());
    if (to == std::string::npos) {
        if (buffer.size() > limit)
            buffer.clear(); // not a stream of pictures, or one far too large
        return false;
    }
    frame.assign(buffer, 0, to + end.size());
    buffer.erase(0, to + end.size());
    return true;
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
};

size_t on_data(char *data, size_t size, size_t count, void *user)
{
    auto *reader = static_cast<Reader *>(user);
    if (reader->stop->load())
        return 0; // ends the transfer
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

MjpegStream::MjpegStream(std::string url, FrameFn on_frame, int max_per_second)
    : m_url(std::move(url)), m_on_frame(std::move(on_frame)), m_max_per_second(max_per_second < 1 ? 1 : max_per_second)
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
    if (CURL *curl = ::curl_easy_init()) {
        ::curl_easy_setopt(curl, CURLOPT_URL, m_url.c_str());
        ::curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
        ::curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
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
