#include "FleetStatus.hpp"

#include <nlohmann/json.hpp>
#include <algorithm>

namespace Slic3r {
namespace Fleet {

void read_status(const nlohmann::json &status, Reading &reading)
{
    if (!status.is_object())
        return;
    const auto object = [&status](const char *name) -> const nlohmann::json * {
        const auto it = status.find(name);
        return it != status.end() && it->is_object() ? &*it : nullptr;
    };
    const auto text = [](const nlohmann::json &from, const char *key) {
        const auto it = from.find(key);
        return it != from.end() && it->is_string() ? it->get<std::string>() : std::string();
    };
    if (const nlohmann::json *stats = object("print_stats")) {
        reading.print_state = text(*stats, "state");
        reading.filename    = text(*stats, "filename");
        reading.moonraker   = !reading.print_state.empty();
    }
    if (const nlohmann::json *webhooks = object("webhooks")) {
        reading.ready = text(*webhooks, "state") == "ready";
        if (!reading.ready)
            reading.firmware_message = text(*webhooks, "state_message");
    }
    if (const nlohmann::json *sd = object("virtual_sdcard"))
        if (const auto it = sd->find("progress"); it != sd->end() && it->is_number())
            reading.progress = std::clamp(it->get<double>(), 0., 1.);
}

Verdict classify(const Reading &reading)
{
    if (!reading.has_address)
        return {Band::Offline, Reason::NoAddress};
    // A Start of ours whose outcome is open waits for a person whether or not the printer answers now.
    if (reading.start_open)
        return {Band::NeedsAttention, Reason::StartOpen};
    if (!reading.reached)
        return {Band::Offline, Reason::NoAnswer};
    if (!reading.moonraker)
        return {Band::NotMonitored, Reason::NotMoonraker};
    // What waits for a person comes first, whatever else the printer is doing.
    if (!reading.ready)
        return {Band::NeedsAttention, Reason::FirmwareNotReady};
    if (reading.print_state == "error")
        return {Band::NeedsAttention, Reason::PrintError};
    if (reading.print_state == "paused")
        return {Band::NeedsAttention, Reason::Paused};
    if (reading.print_state == "printing")
        return {Band::Printing, Reason::None};
    return {Band::Ready, Reason::None};
}

CameraAddresses camera_addresses(const std::string &list_body, const std::string &host)
{
    CameraAddresses found;
    const auto doc = nlohmann::json::parse(list_body, nullptr, false);
    if (doc.is_discarded() || !doc.is_object() || !doc.contains("result") || !doc["result"].is_object())
        return found;
    const auto cams = doc["result"].find("webcams");
    if (cams == doc["result"].end() || !cams->is_array())
        return found;
    const std::string web = "http://" + host.substr(0, host.find(':'));
    // Moonraker gives a path on the printer's web port, or a whole address.
    const auto address = [&web](const nlohmann::json &cam, const char *key) {
        const auto it = cam.find(key);
        if (it == cam.end() || !it->is_string() || it->get<std::string>().empty())
            return std::string();
        std::string url = it->get<std::string>();
        if (url.rfind("http://", 0) != 0 && url.rfind("https://", 0) != 0)
            url = web + (url.front() == '/' ? "" : "/") + url;
        return url;
    };
    for (const auto &cam : *cams) {
        if (!cam.is_object() || !cam.value("enabled", true))
            continue;
        found = {address(cam, "snapshot_url"), address(cam, "stream_url")};
        const auto service = cam.find("service");
        if (service != cam.end() && service->is_string() && service->get<std::string>().find("webrtc") != std::string::npos)
            found.stream = web + "/webcam/stream.mjpg"; // its stream address is a WebRTC one; this is the MJPEG beside it
        if (!found.snapshot.empty() || !found.stream.empty())
            return found;
    }
    return CameraAddresses();
}

} // namespace Fleet

} // namespace Slic3r
