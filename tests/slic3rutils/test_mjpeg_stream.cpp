#include <catch2/catch_all.hpp>

#include "slic3r/Utils/MjpegStream.hpp"

#include <string>
#include <vector>

using Slic3r::MjpegStream;

namespace {

// Something shaped like a JPEG picture: the start marker, a body, the end marker.
std::string picture(const std::string &body) { return std::string("\xFF\xD8\xFF\xE0", 4) + body + std::string("\xFF\xD9", 2); }

std::string part(const std::string &jpeg)
{
    return "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + std::to_string(jpeg.size()) + "\r\n\r\n" + jpeg + "\r\n";
}

} // namespace

TEST_CASE("Pictures are taken out of a camera stream one by one, whole, however the bytes arrive", "[MjpegStream]")
{
    const std::string a = picture("first"), b = picture(std::string("sec\xFF\x00ond", 8)), c = picture("third");
    const std::string stream = part(a) + part(b) + part(c);

    // All at once.
    {
        std::string buffer = stream, frame;
        REQUIRE(MjpegStream::next_frame(buffer, frame));
        CHECK(frame == a);
        REQUIRE(MjpegStream::next_frame(buffer, frame));
        CHECK(frame == b); // an FF 00 inside the data does not end the picture
        REQUIRE(MjpegStream::next_frame(buffer, frame));
        CHECK(frame == c);
        CHECK_FALSE(MjpegStream::next_frame(buffer, frame));
        CHECK(buffer.size() <= 2); // the part's trailing line break at most
    }

    // One byte at a time: the same three pictures, each exactly once, whatever the cut.
    {
        std::string              buffer, frame;
        std::vector<std::string> got;
        for (const char byte : stream) {
            buffer.push_back(byte);
            while (MjpegStream::next_frame(buffer, frame))
                got.push_back(frame);
        }
        REQUIRE(got.size() == 3);
        CHECK(got[0] == a);
        CHECK(got[1] == b);
        CHECK(got[2] == c);
    }

    // Half a picture is kept until the rest arrives.
    {
        std::string buffer = part(a).substr(0, part(a).size() - 6), frame;
        CHECK_FALSE(MjpegStream::next_frame(buffer, frame));
        CHECK(buffer.find("fir") != std::string::npos); // what has arrived of it is still there
        buffer += part(a).substr(part(a).size() - 6);
        REQUIRE(MjpegStream::next_frame(buffer, frame));
        CHECK(frame == a);
    }

    // What is no picture stream does not pile up: without a start it is dropped, and a start that never ends
    // is given up at the limit.
    {
        std::string buffer(5000, 'x'), frame;
        CHECK_FALSE(MjpegStream::next_frame(buffer, frame));
        CHECK(buffer.size() <= 2);
        buffer = std::string("\xFF\xD8\xFF", 3) + std::string(200, 'y');
        CHECK_FALSE(MjpegStream::next_frame(buffer, frame, 100));
        CHECK(buffer.empty());
    }
}
