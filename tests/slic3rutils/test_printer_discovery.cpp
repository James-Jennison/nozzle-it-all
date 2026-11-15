#include <catch2/catch_all.hpp>

#include "slic3r/Utils/PrinterDiscovery.hpp"

#include <algorithm>
#include <string>
#include <vector>

using namespace Slic3r;
using PrinterDiscovery::Found;
using PrinterDiscovery::Kind;

namespace {

const std::string MOONRAKER_INFO = R"({"result": {"state": "ready", "hostname": "carbon", "software_version": "v0.12"}})";

} // namespace

TEST_CASE("A sweep asks the other addresses of this computer's own /24 network", "[PrinterDiscovery]")
{
    const std::vector<std::string> addresses = PrinterDiscovery::sweep_addresses("192.168.1.20");
    REQUIRE(addresses.size() == 253);
    CHECK(addresses.front() == "192.168.1.1");
    CHECK(addresses.back() == "192.168.1.254");
    CHECK(std::find(addresses.begin(), addresses.end(), "192.168.1.20") == addresses.end());
    CHECK(std::find(addresses.begin(), addresses.end(), "192.168.1.0") == addresses.end());
    CHECK(std::find(addresses.begin(), addresses.end(), "192.168.1.255") == addresses.end());
}

TEST_CASE("A sweep never leaves a private network", "[PrinterDiscovery]")
{
    CHECK(PrinterDiscovery::sweep_addresses("10.0.5.7").size() == 253);
    CHECK(PrinterDiscovery::sweep_addresses("172.16.0.9").size() == 253);
    CHECK(PrinterDiscovery::sweep_addresses("172.32.0.9").empty());
    CHECK(PrinterDiscovery::sweep_addresses("8.8.8.8").empty());
    CHECK(PrinterDiscovery::sweep_addresses("203.0.113.5").empty());
    CHECK(PrinterDiscovery::sweep_addresses("").empty());
    CHECK(PrinterDiscovery::sweep_addresses("not an address").empty());
    CHECK(PrinterDiscovery::sweep_addresses("fe80::1").empty());
}

TEST_CASE("Only Moonraker's own answer is listed as a printer", "[PrinterDiscovery]")
{
    CHECK_FALSE(PrinterDiscovery::read_answer("192.168.1.5", "", "").has_value());
    CHECK_FALSE(PrinterDiscovery::read_answer("192.168.1.5", "<html>router</html>", "").has_value());
    CHECK_FALSE(PrinterDiscovery::read_answer("192.168.1.5", R"({"result": {"hostname": "nas"}})", "").has_value());
    CHECK_FALSE(PrinterDiscovery::read_answer("192.168.1.5", R"({"result": {"state": 3}})", "").has_value());
    CHECK_FALSE(PrinterDiscovery::read_answer("192.168.1.5", R"(["state"])", "").has_value());
    const auto found = PrinterDiscovery::read_answer("192.168.1.5", MOONRAKER_INFO, "");
    REQUIRE(found.has_value());
    CHECK(found->address == "192.168.1.5");
    CHECK(found->hostname == "carbon");
    CHECK(found->kind == Kind::Moonraker);
}

TEST_CASE("A printer is told apart by what it reports, not by its name", "[PrinterDiscovery]")
{
    const auto u1 = PrinterDiscovery::read_answer("192.168.1.5", MOONRAKER_INFO,
                                                  R"({"result": {"status": {"print_task_config": {"filament_exist": [true, true, false, true]}}}})");
    REQUIRE(u1.has_value());
    CHECK(u1->kind == Kind::SnapmakerU1);
    const auto lanes = PrinterDiscovery::read_answer("192.168.1.5", MOONRAKER_INFO, R"({"result": {"status": {"AFC": {"current_state": "Idle"}}}})");
    REQUIRE(lanes.has_value());
    CHECK(lanes->kind == Kind::AfcLanes);
    // Objects the printer does not have come back empty or not at all.
    const auto plain = PrinterDiscovery::read_answer("192.168.1.5", MOONRAKER_INFO, R"({"result": {"status": {"AFC": {}, "print_task_config": {}}}})");
    REQUIRE(plain.has_value());
    CHECK(plain->kind == Kind::Moonraker);
    const auto odd = PrinterDiscovery::read_answer("192.168.1.5", MOONRAKER_INFO, "not json");
    REQUIRE(odd.has_value());
    CHECK(odd->kind == Kind::Moonraker);
}

TEST_CASE("A printer that answers on its web port and on Moonraker's own is listed once", "[PrinterDiscovery]")
{
    const std::vector<Found> listed = PrinterDiscovery::one_per_printer(
        {{"192.168.1.114:7125", "carbon", Kind::AfcLanes}, {"192.168.1.30:7125", "voron", Kind::Moonraker}, {"192.168.1.114", "carbon", Kind::AfcLanes}});
    REQUIRE(listed.size() == 2);
    CHECK(listed[0].address == "192.168.1.114");
    CHECK(listed[1].address == "192.168.1.30:7125");
}
