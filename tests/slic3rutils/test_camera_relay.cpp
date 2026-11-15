#include <catch2/catch_all.hpp>

#include "slic3r/Utils/CameraRelay.hpp"

#include <string>

using Slic3r::CameraRelay;

TEST_CASE("An address is taken apart with its scheme's own port filled in", "[CameraRelay]")
{
    CameraRelay::Address a;
    REQUIRE(CameraRelay::parse("http://192.168.1.114:8080/stream?cacheBust=1", a));
    CHECK(a.host == "192.168.1.114");
    CHECK(a.port == 8080);
    CHECK(a.path == "/stream");
    CHECK(a.query == "cacheBust=1");
    CHECK(a.origin() == "http://192.168.1.114:8080");
    REQUIRE(CameraRelay::parse("HTTP://Printer.Local/webcam/?action=stream#x", a));
    CHECK(a.origin() == "http://printer.local:80");
    CHECK(a.path == "/webcam/");
    CHECK(a.query == "action=stream");
    REQUIRE(CameraRelay::parse("https://[fd00::1]/stream", a));
    CHECK(a.origin() == "https://[fd00::1]:443");
    REQUIRE(CameraRelay::parse("http://[fd00::1]:8080", a));
    CHECK(a.port == 8080);
    CHECK(a.path == "/");
    // No address with a user name, another scheme, or a port that is none.
    CHECK_FALSE(CameraRelay::parse("http://user:secret@192.168.1.114/stream", a));
    CHECK_FALSE(CameraRelay::parse("file:///etc/passwd", a));
    CHECK_FALSE(CameraRelay::parse("http://192.168.1.114:99999/stream", a));
    CHECK_FALSE(CameraRelay::parse("http://192.168.1.114:80a/stream", a));
    CHECK_FALSE(CameraRelay::parse("/stream", a));
}

TEST_CASE("Only what the printer's list of cameras names is passed on", "[CameraRelay]")
{
    const std::string printer = "http://192.168.1.114:80";
    // A camera server on its own port, with the cache buster a page adds.
    CHECK(CameraRelay::names("http://192.168.1.114:8080/stream", printer, "http://192.168.1.114:8080/stream?cacheBust=1794700000"));
    // A path on the printer itself, as Moonraker lists it by default.
    CHECK(CameraRelay::names("/webcam/?action=stream", printer, "http://192.168.1.114/webcam/?action=stream&cacheBust=5"));
    CHECK(CameraRelay::names("/webcam/?action=stream", printer, "http://192.168.1.114/webcam/?cacheBust=5&action=stream"));
    // Another port, another path, another action, another machine: not named.
    CHECK_FALSE(CameraRelay::names("http://192.168.1.114:8080/stream", printer, "http://192.168.1.114:8081/stream"));
    CHECK_FALSE(CameraRelay::names("http://192.168.1.114:8080/stream", printer, "http://192.168.1.114:8080/state"));
    CHECK_FALSE(CameraRelay::names("/webcam/?action=stream", printer, "http://192.168.1.114/webcam/?action=snapshot"));
    CHECK_FALSE(CameraRelay::names("/webcam/?action=stream", printer, "http://192.168.1.115/webcam/?action=stream"));
    CHECK_FALSE(CameraRelay::names("", printer, "http://192.168.1.114/webcam/?action=stream"));
    // Written differently, meaning the same.
    CHECK(CameraRelay::names("/webcam/?action=stream&name=front%20cam", printer, "http://192.168.1.114/webcam/?name=front cam&action=stream"));
    CHECK(CameraRelay::names("/webcam/?mode=a%3Db", printer, "http://192.168.1.114/webcam/?mode=a=b"));
    CHECK_FALSE(CameraRelay::names("webcam/stream", printer, "http://192.168.1.114/webcam/stream"));
}

TEST_CASE("The stream addresses are read out of Moonraker's list of cameras", "[CameraRelay]")
{
    const auto streams = CameraRelay::listed_streams(
        R"({"result": {"webcams": [{"name": "webcam", "stream_url": "http://192.168.1.114:8080/stream", "snapshot_url": "http://192.168.1.114:8080/snapshot"},
                                   {"name": "adaptive", "stream_url": "/webcam/?action=stream"}, {"name": "none", "stream_url": ""}, {"name": "odd", "stream_url": 5}]}})");
    REQUIRE(streams.size() == 2);
    CHECK(streams[0] == "http://192.168.1.114:8080/stream");
    CHECK(streams[1] == "/webcam/?action=stream");
    CHECK(CameraRelay::listed_streams("not json").empty());
    CHECK(CameraRelay::listed_streams(R"({"result": {}})").empty());
}

TEST_CASE("A stream is handed to a script under a type the engine passes on, and to a picture element as it is", "[CameraRelay]")
{
    CHECK(CameraRelay::type_for("multipart/x-mixed-replace;boundary=boundarydonotcross", "*/*") == "multipart/mixed;boundary=boundarydonotcross");
    CHECK(CameraRelay::type_for("Multipart/X-Mixed-Replace; boundary=frame", "") == "multipart/mixed; boundary=frame");
    CHECK(CameraRelay::type_for("multipart/x-mixed-replace;boundary=frame", "image/webp,image/avif,image/*,*/*;q=0.8") == "multipart/x-mixed-replace;boundary=frame");
    CHECK(CameraRelay::type_for("image/jpeg", "*/*") == "image/jpeg");
    CHECK(CameraRelay::type_for("application/json", "*/*") == "application/json");
    CHECK(CameraRelay::unescape("http%3A%2F%2F192.168.1.114%3A8080%2Fstream%3Fa%3D1%26b%3D2") == "http://192.168.1.114:8080/stream?a=1&b=2");
    CHECK(CameraRelay::unescape("100%") == "100%");
}
