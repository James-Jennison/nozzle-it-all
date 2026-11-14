#include <catch2/catch_all.hpp>

#include "slic3r/Utils/MjpegStream.hpp"

#include <string>
#include <vector>

using Slic3r::MjpegStream;

namespace {

// A JPEG picture in outline: the start marker, an application segment (*inside* is its content), a
// start-of-scan segment, the compressed data, the end marker.
std::string picture(const std::string &body, const std::string &inside = "JF")
{
    const size_t length = inside.size() + 2;
    return std::string("\xFF\xD8\xFF\xE0", 4) + char(length >> 8) + char(length & 0xFF) + inside + std::string("\xFF\xDA\x00\x02", 4) + body +
           std::string("\xFF\xD9", 2);
}

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

TEST_CASE("A picture that carries a small picture inside a segment is taken whole", "[MjpegStream]")
{
    // A thumbnail is a whole JPEG, start and end marker included, inside an application segment.
    const std::string thumbnail = picture("tiny"), a = picture("outer data", "Exif" + thumbnail), b = picture("next");
    std::string       buffer = part(a) + part(b), frame;
    REQUIRE(MjpegStream::next_frame(buffer, frame));
    CHECK(frame == a);
    REQUIRE(MjpegStream::next_frame(buffer, frame));
    CHECK(frame == b);

    // The same one byte at a time, and with a restart marker and fill bytes in the data.
    const std::string c = picture(std::string("da\xFF\xD0ta\xFF\xFF\x00more", 14), "Exif" + thumbnail);
    std::vector<std::string> got;
    buffer.clear();
    for (const char byte : part(c) + part(b)) {
        buffer.push_back(byte);
        while (MjpegStream::next_frame(buffer, frame))
            got.push_back(frame);
    }
    REQUIRE(got.size() == 2);
    CHECK(got[0] == c);
    CHECK(got[1] == b);
}

TEST_CASE("A camera's answer gives a page leave to read it by naming its origin or everyone", "[MjpegStream]")
{
    const std::string origin = "http://192.168.1.50:80";
    CHECK(MjpegStream::allows_origin("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: multipart/x-mixed-replace\r\n\r\n", origin));
    CHECK(MjpegStream::allows_origin("HTTP/1.0 200 OK\r\naccess-control-allow-origin:  HTTP://192.168.1.50:80 \r\n\r\n", origin));
    CHECK_FALSE(MjpegStream::allows_origin("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace\r\n\r\n", origin));
    CHECK_FALSE(MjpegStream::allows_origin("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: http://192.168.1.51:80\r\n\r\n", origin));
    CHECK_FALSE(MjpegStream::allows_origin("HTTP/1.1 200 OK\r\nX-Access-Control-Allow-Origin: *\r\n\r\n", origin));
    CHECK_FALSE(MjpegStream::allows_origin("", origin));
}
