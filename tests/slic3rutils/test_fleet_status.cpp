#include <catch2/catch_all.hpp>

#include "slic3r/Utils/FleetStatus.hpp"

#include <nlohmann/json.hpp>

using namespace Slic3r;
using Fleet::Band;
using Fleet::Reason;

namespace {

Fleet::Reading printer(const char *print_state, const char *firmware = "ready")
{
    Fleet::Reading reading;
    reading.has_address = reading.reached = true;
    Fleet::read_status({{"webhooks", {{"state", firmware}, {"state_message", "MCU shutdown"}}},
                        {"print_stats", {{"state", print_state}, {"filename", "part.gcode"}}},
                        {"virtual_sdcard", {{"progress", 0.25}}}},
                       reading);
    return reading;
}

} // namespace

TEST_CASE("A printer is sorted by what it reports, and one that needs a person is never shown as printing or ready", "[Fleet]")
{
    CHECK(Fleet::classify(Fleet::Reading{}).band == Band::Offline);
    CHECK(Fleet::classify(Fleet::Reading{}).reason == Reason::NoAddress);

    Fleet::Reading silent;
    silent.has_address = true;
    CHECK(Fleet::classify(silent).band == Band::Offline);
    CHECK(Fleet::classify(silent).reason == Reason::NoAnswer);

    // Something answers, but not with a Moonraker status: its state is not guessed.
    Fleet::Reading other = silent;
    other.reached        = true;
    CHECK(Fleet::classify(other).band == Band::NotMonitored);
    Fleet::read_status(nlohmann::json::object(), other);
    CHECK(Fleet::classify(other).band == Band::NotMonitored);
    Fleet::read_status({{"webhooks", {{"state", "ready"}}}}, other);
    CHECK(Fleet::classify(other).band == Band::NotMonitored);

    CHECK(Fleet::classify(printer("printing")).band == Band::Printing);
    CHECK(printer("printing").progress == 0.25);
    CHECK(printer("printing").filename == "part.gcode");
    for (const char *state : {"standby", "complete", "cancelled"}) {
        CHECK(Fleet::classify(printer(state)).band == Band::Ready);
        CHECK(Fleet::classify(printer(state)).reason == Reason::None);
    }
    CHECK(Fleet::classify(printer("paused")).band == Band::NeedsAttention);
    CHECK(Fleet::classify(printer("paused")).reason == Reason::Paused);
    CHECK(Fleet::classify(printer("error")).reason == Reason::PrintError);

    // The firmware not being ready outranks the print state, and its message is kept.
    for (const char *state : {"printing", "standby", "paused"}) {
        const Fleet::Reading down = printer(state, "shutdown");
        CHECK(Fleet::classify(down).band == Band::NeedsAttention);
        CHECK(Fleet::classify(down).reason == Reason::FirmwareNotReady);
        CHECK(down.firmware_message == "MCU shutdown");
    }
    CHECK(printer("printing").firmware_message.empty());

    // A Start of ours whose outcome is open outranks everything the printer says.
    for (const char *state : {"printing", "standby", "paused", "error"}) {
        Fleet::Reading held = printer(state);
        held.start_open     = true;
        CHECK(Fleet::classify(held).band == Band::NeedsAttention);
        CHECK(Fleet::classify(held).reason == Reason::StartOpen);
    }
    // Also when the printer does not answer now: the open Start still waits for a person.
    silent.start_open = true;
    CHECK(Fleet::classify(silent).band == Band::NeedsAttention);
    CHECK(Fleet::classify(silent).reason == Reason::StartOpen);

    // The groups are shown in this order.
    CHECK(int(Band::NeedsAttention) < int(Band::Printing));
    CHECK(int(Band::Printing) < int(Band::Ready));
    CHECK(int(Band::Ready) < int(Band::Offline));
    CHECK(int(Band::Offline) < int(Band::NotMonitored));
}

TEST_CASE("A card's camera is the printer's first enabled camera, by an address this application can read", "[FleetStatus]")
{
    using Slic3r::Fleet::camera_addresses;
    // A camera server on its own port, whole addresses.
    auto camera = camera_addresses(R"({"result": {"webcams": [{"name": "webcam", "enabled": true, "service": "mjpegstreamer",
        "stream_url": "http://192.168.1.114:8080/stream", "snapshot_url": "http://192.168.1.114:8080/snapshot"}]}})", "192.168.1.114");
    CHECK(camera.stream == "http://192.168.1.114:8080/stream");
    CHECK(camera.snapshot == "http://192.168.1.114:8080/snapshot");
    // Paths are on the printer's web port, whatever port the preset names for Moonraker.
    camera = camera_addresses(R"({"result": {"webcams": [{"name": "off", "enabled": false, "stream_url": "/other/stream"},
        {"name": "cam", "stream_url": "/webcam/?action=stream", "snapshot_url": "webcam/?action=snapshot"}]}})", "192.168.1.50:7125");
    CHECK(camera.stream == "http://192.168.1.50/webcam/?action=stream");
    CHECK(camera.snapshot == "http://192.168.1.50/webcam/?action=snapshot");
    // A WebRTC camera, as a Snapmaker U1 lists it: the MJPEG stream beside it is the one a card can read.
    camera = camera_addresses(R"({"result": {"webcams": [{"name": "u1", "enabled": true, "service": "webrtc-camerastreamer",
        "stream_url": "/webcam/webrtc", "snapshot_url": "/webcam/snapshot.jpg"}]}})", "192.168.1.243");
    CHECK(camera.stream == "http://192.168.1.243/webcam/stream.mjpg");
    CHECK(camera.snapshot == "http://192.168.1.243/webcam/snapshot.jpg");
    // A web server on a port of its own keeps it, for a path and for the stream beside a WebRTC camera.
    camera = camera_addresses(R"({"result": {"webcams": [{"name": "cam", "stream_url": "/webcam/?action=stream"}]}})", "192.168.1.50:8080");
    CHECK(camera.stream == "http://192.168.1.50:8080/webcam/?action=stream");
    camera = camera_addresses(R"({"result": {"webcams": [{"name": "u1", "service": "webrtc-camerastreamer", "stream_url": "/webcam/webrtc"}]}})", "127.0.0.1:47225");
    CHECK(camera.stream == "http://127.0.0.1:47225/webcam/stream.mjpg");
    // No camera, or no list.
    CHECK(camera_addresses(R"({"result": {"webcams": []}})", "192.168.1.50").stream.empty());
    CHECK(camera_addresses("not json", "192.168.1.50").stream.empty());
}
