#ifndef slic3r_MjpegStream_hpp_
#define slic3r_MjpegStream_hpp_

#include <atomic>
#include <functional>
#include <string>
#include <thread>

namespace Slic3r {

/// Reads a camera's MJPEG stream (multipart/x-mixed-replace, as mjpg-streamer, ustreamer and camera-streamer
/// serve it) and hands over its pictures one by one. One GET request that stays open; nothing is sent to
/// the printer. The pictures are passed as the JPEG bytes the camera sent; decoding is the receiver's.
class MjpegStream
{
public:
    /// Called on the stream's own thread with one picture.
    using FrameFn = std::function<void(std::string jpeg)>;

    /// Starts reading *url* at once. At most *max_per_second* pictures are handed over; the others are dropped.
    MjpegStream(std::string url, FrameFn on_frame, int max_per_second = 5);
    /// Stops the stream and waits for its thread (the read is given up within about a second).
    ~MjpegStream();

    MjpegStream(const MjpegStream &)            = delete;
    MjpegStream &operator=(const MjpegStream &) = delete;

    const std::string &url() const { return m_url; }
    /// The connection is over: refused, closed by the camera, or silent for too long.
    bool ended() const { return m_ended.load(); }
    /// Pictures handed over so far.
    unsigned long frames() const { return m_frames.load(); }

    /// Takes the first complete JPEG picture out of *buffer* into *frame* and removes it, and everything before
    /// it, from the buffer. False when the buffer holds no complete picture yet; then only bytes that cannot
    /// belong to one are removed. A buffer grown past *limit* without a complete picture is emptied.
    static bool next_frame(std::string &buffer, std::string &frame, size_t limit = 8 * 1024 * 1024);

private:
    void run();

    std::string                m_url;
    FrameFn                    m_on_frame;
    int                        m_max_per_second;
    std::atomic<bool>          m_stop{false};
    std::atomic<bool>          m_ended{false};
    std::atomic<unsigned long> m_frames{0};
    std::thread                m_thread;
};

} // namespace Slic3r

#endif // slic3r_MjpegStream_hpp_
