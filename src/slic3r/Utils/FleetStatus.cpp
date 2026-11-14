#include "FleetStatus.hpp"

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

} // namespace Fleet
} // namespace Slic3r
