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
