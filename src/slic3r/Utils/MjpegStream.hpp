#ifndef slic3r_MjpegStream_hpp_
#define slic3r_MjpegStream_hpp_

#include <atomic>
#include <functional>
#include <future>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace Slic3r {

/// Reads a camera's MJPEG stream (multipart/x-mixed-replace, as mjpg-streamer, ustreamer and camera-streamer
/// serve it) and hands over its pictures one by one. One GET request that stays open; nothing is sent to
/// the printer. The pictures are passed as the JPEG bytes the camera sent; decoding is the receiver's.
class MjpegStream
{
public:
    /// Called on the stream's own thread with one picture.
    using FrameFn = std::function<void(std::string jpeg)>;

    /// What a stream read on a web page's behalf must keep to.
    struct Limits
    {
        /// When set, the camera's answer must give this origin ("http://host:port") leave to read it
        /// (Access-Control-Allow-Origin naming it or "*"), as a browser would require; otherwise the
        /// stream is ended with refused() set and no picture is handed over.
        std::string leave_for_origin;
        /// A redirect is followed (three at most). Off, the address asked for is the address read.
        bool        redirects = true;
    };

    /// Starts reading *url* at once. At most *max_per_second* pictures are handed over; the others are dropped.
    MjpegStream(std::string url, FrameFn on_frame, int max_per_second = 5);
    /// The same, kept to *limits*.
    MjpegStream(std::string url, FrameFn on_frame, int max_per_second, Limits limits);
    /// Stops the stream and waits for its thread (the read is given up within about a second).
    ~MjpegStream();

    MjpegStream(const MjpegStream &)            = delete;
    MjpegStream &operator=(const MjpegStream &) = delete;

    const std::string &url() const { return m_url; }
    /// The connection is over: refused, closed by the camera, or silent for too long.
    bool ended() const { return m_ended.load(); }
    /// The stream was ended because the camera did not give Limits::leave_for_origin leave.
    bool refused() const { return m_refused.load(); }
    /// Pictures handed over so far.
    unsigned long frames() const { return m_frames.load(); }

    /// Takes the first complete JPEG picture out of *buffer* into *frame* and removes it, and everything before
    /// it, from the buffer. False when the buffer holds no complete picture yet; then only bytes that cannot
    /// belong to one are removed. A buffer grown past *limit* without a complete picture is emptied.
    static bool next_frame(std::string &buffer, std::string &frame, size_t limit = 8 * 1024 * 1024);
    /// Whether an answer's header lines give *origin* leave to read it: an Access-Control-Allow-Origin line
    /// whose value is "*" or *origin* (compared without regard to case, and with a scheme's own port, 80 for
    /// http and 443 for https, the same written or left out). *headers* is one answer's header lines.
    static bool allows_origin(const std::string &headers, const std::string &origin);

private:
    void run();

    std::string                m_url;
    FrameFn                    m_on_frame;
    int                        m_max_per_second;
    Limits                     m_limits;
    std::atomic<bool>          m_stop{false};
    std::atomic<bool>          m_ended{false};
    std::atomic<bool>          m_refused{false};
    std::atomic<unsigned long> m_frames{0};
    std::thread                m_thread;
};

/// Stops streams off the calling thread: a stream's reader takes up to a second to give up, and the GUI thread
/// is not made to wait for it. What is being stopped is waited for when this object is destroyed, so no
/// stream handed to it outlives its owner.
class MjpegRetirement
{
public:
    void retire(std::unique_ptr<MjpegStream> stream);

private:
    std::vector<std::future<void>> m_stopping;
};

} // namespace Slic3r

#endif // slic3r_MjpegStream_hpp_
