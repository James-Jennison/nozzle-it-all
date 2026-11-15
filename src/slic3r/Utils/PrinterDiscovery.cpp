#include "PrinterDiscovery.hpp"

#include "Http.hpp"
#include "U1JobJournal.hpp"

#include <boost/asio/io_context.hpp>
#include <boost/asio/ip/address.hpp>
#include <boost/asio/ip/udp.hpp>

#include <algorithm>
#include <chrono>
#include <mutex>
#include <thread>

namespace Slic3r {
namespace PrinterDiscovery {

namespace {

// Addresses asked at the same time, and how long one may take. A sweep of 253 addresses that do not answer
// then takes about ten seconds.
constexpr size_t WORKERS         = 48;
constexpr long   CONNECT_SECONDS = 1;
constexpr long   ANSWER_SECONDS  = 3;

std::string get(const std::string &url, const std::atomic<bool> &stop)
{
    std::string answer;
    Http::get(url)
        .timeout_connect(CONNECT_SECONDS)
        .timeout_max(ANSWER_SECONDS)
        .on_progress([&stop](Http::Progress, bool &cancel) { cancel = stop.load(); })
        .on_complete([&answer](std::string body, unsigned) { answer = std::move(body); })
        .on_error([](std::string, std::string, unsigned) {})
        .perform_sync();
    return answer;
}

} // namespace

std::vector<std::string> sweep_addresses(const std::string &own_ipv4)
{
    std::vector<std::string>  addresses;
    boost::system::error_code ec;
    const auto address = boost::asio::ip::make_address_v4(own_ipv4, ec);
    if (ec)
        return addresses;
    const auto bytes   = address.to_bytes();
    const bool private_network = bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) || (bytes[0] == 192 && bytes[1] == 168);
    if (!private_network)
        return addresses;
    const std::string network = std::to_string(bytes[0]) + "." + std::to_string(bytes[1]) + "." + std::to_string(bytes[2]) + ".";
    for (int last = 1; last <= 254; ++last)
        if (last != bytes[3])
            addresses.push_back(network + std::to_string(last));
    return addresses;
}

std::optional<Found> read_answer(const std::string &address, const std::string &info, const std::string &objects)
{
    const auto doc = nlohmann::json::parse(info, nullptr, false);
    // Moonraker's /printer/info carries Klippy's state; anything else that answers HTTP is not listed.
    if (doc.is_discarded() || !doc.is_object() || !doc.contains("result") || !doc["result"].is_object() || !doc["result"].contains("state") ||
        !doc["result"]["state"].is_string())
        return std::nullopt;
    Found found;
    found.address = address;
    if (const auto &name = doc["result"].value("hostname", nlohmann::json()); name.is_string())
        found.hostname = name.get<std::string>();
    const auto status = nlohmann::json::parse(objects, nullptr, false);
    if (!status.is_discarded() && status.is_object() && status.contains("result") && status["result"].is_object() &&
        status["result"].contains("status") && status["result"]["status"].is_object()) {
        const auto &reported = status["result"]["status"];
        if (reported.contains("print_task_config") && reported["print_task_config"].is_object() && !reported["print_task_config"].empty())
            found.kind = Kind::SnapmakerU1;
        else if (reported.contains("AFC") && reported["AFC"].is_object() && !reported["AFC"].empty())
            found.kind = Kind::AfcLanes;
    }
    return found;
}

std::vector<Found> one_per_printer(std::vector<Found> found)
{
    // The address without a port first, so that it is the one kept.
    std::stable_sort(found.begin(), found.end(), [](const Found &a, const Found &b) {
        return (a.address.find(':') == std::string::npos) > (b.address.find(':') == std::string::npos);
    });
    std::vector<Found> kept;
    for (Found &one : found)
        if (std::none_of(kept.begin(), kept.end(), [&one](const Found &had) {
                return U1Jobs::same_printer(U1Jobs::printer_key(had.address), U1Jobs::printer_key(one.address));
            }))
            kept.push_back(std::move(one));
    std::stable_sort(kept.begin(), kept.end(), [](const Found &a, const Found &b) {
        return a.address.size() != b.address.size() ? a.address.size() < b.address.size() : a.address < b.address;
    });
    return kept;
}

std::string own_ipv4()
{
    // Nothing is sent: connecting a UDP socket only makes the system choose the address it would send from.
    try {
        boost::asio::io_context      io;
        boost::asio::ip::udp::socket socket(io);
        boost::system::error_code    ec;
        socket.connect(boost::asio::ip::udp::endpoint(boost::asio::ip::make_address_v4("192.0.2.1"), 9), ec);
        if (ec)
            return {};
        const auto local = socket.local_endpoint(ec);
        return ec || !local.address().is_v4() ? std::string() : local.address().to_string();
    } catch (const std::exception &) {
        return {};
    }
}

std::optional<Found> probe(const std::string &address, const std::atomic<bool> &stop)
{
    const std::string info = get("http://" + address + "/printer/info", stop);
    if (info.empty() || stop.load())
        return std::nullopt;
    // Only an address that gave Moonraker's answer is asked the second question.
    if (!read_answer(address, info, {}))
        return std::nullopt;
    return read_answer(address, info, get("http://" + address + "/printer/objects/query?print_task_config&AFC", stop));
}

std::vector<Found> sweep(const std::atomic<bool> &stop, const std::function<void(size_t, size_t)> &progress)
{
    std::vector<std::string> targets;
    for (const std::string &address : sweep_addresses(own_ipv4())) {
        targets.push_back(address);
        targets.push_back(address + ":7125");
    }
    std::vector<Found>   found;
    std::mutex           mutex;
    std::atomic<size_t>  next{0}, done{0};
    std::vector<std::thread> workers;
    for (size_t i = 0; i < std::min(WORKERS, targets.size()); ++i)
        workers.emplace_back([&] {
            for (size_t at = next.fetch_add(1); at < targets.size() && !stop.load(); at = next.fetch_add(1)) {
                if (auto one = probe(targets[at], stop)) {
                    std::lock_guard<std::mutex> lock(mutex);
                    found.push_back(std::move(*one));
                }
                done.fetch_add(1);
            }
        });
    // The caller's thread reports while the workers ask.
    while (done.load() < targets.size() && !stop.load()) {
        if (progress)
            progress(done.load() / 2, targets.size() / 2);
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    for (std::thread &worker : workers)
        worker.join();
    return one_per_printer(std::move(found));
}

} // namespace PrinterDiscovery
} // namespace Slic3r
