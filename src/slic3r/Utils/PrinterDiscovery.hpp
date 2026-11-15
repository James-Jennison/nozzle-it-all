#ifndef slic3r_PrinterDiscovery_hpp_
#define slic3r_PrinterDiscovery_hpp_

#include <nlohmann/json.hpp>

#include <atomic>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace Slic3r {

/// Finding Moonraker printers on the local network for "Add printer". It only reads: two GET requests to an
/// address that answers, never a command. The rules (which addresses, what an answer means, which answers
/// are one printer) have no network dependency and are unit-tested directly.
namespace PrinterDiscovery {

enum class Kind {
    Moonraker,   ///< a Klipper printer behind Moonraker; nothing more is known
    SnapmakerU1, ///< it reports the Snapmaker U1's print task configuration
    AfcLanes     ///< it reports a filament changer driven by AFC
};

struct Found
{
    std::string address;  ///< "host" or "host:port", as it is entered into a printer's Connection settings
    std::string hostname; ///< the name the printer gives itself; may be empty
    Kind        kind = Kind::Moonraker;
};

/// The other addresses of the /24 network *own_ipv4* is in, lowest first. Empty for anything that is not a
/// private IPv4 address (10.x, 172.16-31.x, 192.168.x): a sweep never leaves the local network.
std::vector<std::string> sweep_addresses(const std::string &own_ipv4);

/// What the answers of one address mean. *info* is the body of /printer/info, *objects* the body of
/// /printer/objects/query?print_task_config&AFC (may be empty). Nothing unless *info* is Moonraker's answer.
std::optional<Found> read_answer(const std::string &address, const std::string &info, const std::string &objects);

/// One entry per printer: a printer that answers on its web port and on Moonraker's own (7125) is listed
/// once, by the address without a port.
std::vector<Found> one_per_printer(std::vector<Found> found);

/// This computer's address on the network it reaches others through; empty when there is none.
std::string own_ipv4();

/// Asks one address ("host" or "host:port"). Blocks for a few seconds at most.
std::optional<Found> probe(const std::string &address, const std::atomic<bool> &stop);

/// Asks every address of this computer's /24 network on ports 80 and 7125, several at a time. *progress* is
/// called from the calling thread with how many of the addresses were asked so far. Blocks until done or *stop*.
std::vector<Found> sweep(const std::atomic<bool> &stop, const std::function<void(size_t done, size_t total)> &progress = {});

} // namespace PrinterDiscovery
} // namespace Slic3r

#endif // slic3r_PrinterDiscovery_hpp_
