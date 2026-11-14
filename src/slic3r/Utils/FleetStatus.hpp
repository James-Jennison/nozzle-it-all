#ifndef slic3r_FleetStatus_hpp_
#define slic3r_FleetStatus_hpp_

#include <nlohmann/json.hpp>

#include <string>

namespace Slic3r {

/// Where a printer stands among several: the rule behind the cards of the Printers view. No wxWidgets or
/// network dependency, so it is unit-tested directly. It only sorts what was read; it sends nothing.
namespace Fleet {

/// The groups of the Printers view, in the order they are shown.
enum class Band {
    NeedsAttention, ///< answered, and something waits for a person
    Printing,
    Ready,
    Offline,       ///< no address, or nothing answered there
    NotMonitored   ///< something answered that is not a Moonraker printer; its state is not read here
};

enum class Reason {
    None,
    NoAddress,
    NoAnswer,
    NotMoonraker,
    FirmwareNotReady, ///< Klippy is not ready; *firmware_message* says why
    PrintError,       ///< the last print ended in an error
    Paused,
    StartOpen         ///< a job sent from this app has a Start whose outcome is not settled
};

/// One read of one printer. Anything not known stays false or empty.
struct Reading
{
    bool        has_address = false;
    bool        reached     = false; ///< something answered HTTP at the address
    bool        moonraker   = false; ///< the answer was a Moonraker status with a print state
    bool        ready       = false; ///< webhooks.state is "ready"
    std::string firmware_message;    ///< webhooks.state_message when not ready
    std::string print_state;         ///< print_stats.state: "printing", "paused", "standby", "complete", "cancelled", "error"
    std::string filename;
    double      progress = 0.;       ///< 0..1
    bool        start_open = false;  ///< from this app's job journal, not from the printer
};

struct Verdict
{
    Band   band   = Band::Offline;
    Reason reason = Reason::NoAddress;
};

/// Fills the printer's part of a Reading from the "status" object of Moonraker's
/// printer/objects/query?webhooks&print_stats&virtual_sdcard. *moonraker* stays false unless the print state is there.
void read_status(const nlohmann::json &status, Reading &reading);

/// The group a printer belongs to and why. A printer that needs a person is never shown as Printing or Ready.
Verdict classify(const Reading &reading);

} // namespace Fleet
} // namespace Slic3r

#endif // slic3r_FleetStatus_hpp_
