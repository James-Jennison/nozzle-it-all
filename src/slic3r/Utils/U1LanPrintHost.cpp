#include "U1LanPrintHost.hpp"

#include "Http.hpp"
#include "NozzleSession.hpp"
#include "U1JobJournal.hpp"
#include "slic3r/GUI/I18N.hpp"
#include "slic3r/GUI/GUI.hpp"
#include "slic3r/GUI/format.hpp"

#include <boost/algorithm/string/predicate.hpp>
#include <boost/asio/connect.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/log/trivial.hpp>
#include <boost/algorithm/string.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>

#ifndef _WIN32
#include <sys/socket.h>
#include <sys/time.h>
#endif

#include <chrono>
#include <cmath>
#include <mutex>
#include <random>
#include <set>
#include <thread>

namespace Slic3r {

namespace {

constexpr long QUERY_TIMEOUT_S = 5;
// How long the printer is watched after a lost Start reply before the job is held for a person.
constexpr int  RECONCILE_SECONDS = 20;

// Printers with a send in flight; a second send to the same printer is refused, not queued behind it.
std::mutex            s_busy_mutex;
std::set<std::string> s_busy;

struct BusyPrinter
{
    std::string key;
    bool        acquired;
    explicit BusyPrinter(std::string k) : key(std::move(k))
    {
        std::lock_guard<std::mutex> lock(s_busy_mutex);
        acquired = s_busy.insert(key).second;
    }
    ~BusyPrinter()
    {
        if (acquired) {
            std::lock_guard<std::mutex> lock(s_busy_mutex);
            s_busy.erase(key);
        }
    }
};

std::string sha256_hex(const std::string &data)
{
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int  length = 0;
    if (EVP_Digest(data.data(), data.size(), digest, &length, EVP_sha256(), nullptr) != 1)
        return {};
    static const char *hex = "0123456789abcdef";
    std::string        out;
    for (unsigned int i = 0; i < length; ++i) {
        out.push_back(hex[digest[i] >> 4]);
        out.push_back(hex[digest[i] & 0x0f]);
    }
    return out;
}

// print_stats, print_task_config and the four extruders in one read; null when the printer does not answer.
nlohmann::json query_status(const std::string &base_url)
{
    nlohmann::json status;
    Http::get(base_url + "/printer/objects/query?print_stats&print_task_config&extruder&extruder1&extruder2&extruder3")
        .timeout_connect(QUERY_TIMEOUT_S)
        .timeout_max(QUERY_TIMEOUT_S)
        .on_complete([&](std::string body, unsigned) {
            const auto doc = nlohmann::json::parse(body, nullptr, false);
            if (!doc.is_discarded() && doc.contains("result"))
                status = doc["result"].value("status", nlohmann::json());
        })
        .on_error([](std::string, std::string, unsigned) {})
        .perform_sync();
    return status;
}

std::string print_state(const nlohmann::json &status)
{
    return status.is_object() && status.contains("print_stats") ? status["print_stats"].value("state", std::string()) : std::string();
}

// What the prepared G-code says it was sliced for (Orca writes its settings as trailing comments).
struct GcodeFacts
{
    std::string         printer_model;
    std::vector<double> nozzles;
};

GcodeFacts read_gcode_facts(const boost::filesystem::path &file)
{
    GcodeFacts              facts;
    boost::nowide::ifstream in(file.string());
    std::string             line;
    while (std::getline(in, line)) {
        if (line.size() < 4 || line[0] != ';')
            continue;
        if (boost::starts_with(line, "; printer_model = "))
            facts.printer_model = boost::trim_copy(line.substr(18));
        else if (boost::starts_with(line, "; nozzle_diameter = ")) {
            std::vector<std::string> parts;
            const std::string        value = line.substr(20);
            boost::split(parts, value, boost::is_any_of(","));
            facts.nozzles.clear();
            for (const std::string &part : parts)
                try { facts.nozzles.push_back(std::stod(boost::trim_copy(part))); } catch (const std::exception &) {}
        }
    }
    return facts;
}

std::string with_scheme(std::string host)
{
    while (!host.empty() && host.back() == '/')
        host.pop_back();
    if (!boost::istarts_with(host, "http://") && !boost::istarts_with(host, "https://"))
        host = "http://" + host;
    return host;
}

bool has_port(const std::string &url)
{
    const auto scheme_end = url.find("://");
    const auto authority  = url.substr(scheme_end == std::string::npos ? 0 : scheme_end + 3);
    return authority.find(':') != std::string::npos;
}

} // namespace

U1LanPrintHost::U1LanPrintHost(std::string base_url, U1PrintTask::Options options)
    : m_base_url(with_scheme(std::move(base_url))), m_options(std::move(options))
{}

std::optional<U1PrintTask::PrinterState> U1LanPrintHost::query_printer(const std::string &host, std::string &base_url, wxString &error)
{
    std::vector<std::string> candidates{with_scheme(host)};
    if (!has_port(candidates.front()))
        candidates.push_back(candidates.front() + ":7125");

    for (const std::string &candidate : candidates) {
        std::optional<U1PrintTask::PrinterState> state;
        Http::get(candidate + "/printer/objects/query?print_task_config")
            .timeout_connect(QUERY_TIMEOUT_S)
            .timeout_max(QUERY_TIMEOUT_S)
            .on_complete([&](std::string body, unsigned) {
                try {
                    const auto json = nlohmann::json::parse(body);
                    state = U1PrintTask::parse_printer_state(json.at("result").at("status").at("print_task_config"));
                } catch (const std::exception &e) {
                    error = GUI::format_wxstr(_L("Unexpected reply from the printer: %s"), e.what());
                }
            })
            .on_error([&](std::string body, std::string err, unsigned status) {
                error = status != 0 ? wxString::Format("HTTP %u: %s", status, wxString::FromUTF8(body)) : wxString::FromUTF8(err);
            })
            .perform_sync();
        if (state) {
            base_url = candidate;
            return state;
        }
        BOOST_LOG_TRIVIAL(warning) << "U1 LAN: print_task_config query failed at " << candidate;
    }
    return std::nullopt;
}

std::optional<nlohmann::json> U1LanPrintHost::query_filament_report(const std::string &host, wxString &error)
{
    std::string base;
    if (!query_printer(host, base, error))
        return std::nullopt;

    std::optional<nlohmann::json> report;
    Http::get(base + "/printer/objects/query?print_task_config&extruder&extruder1&extruder2&extruder3")
        .timeout_connect(QUERY_TIMEOUT_S)
        .timeout_max(QUERY_TIMEOUT_S)
        .on_complete([&](std::string body, unsigned) {
            try {
                auto built = U1PrintTask::build_filament_report(nlohmann::json::parse(body).at("result").at("status"));
                if (!built.is_null())
                    report = std::move(built);
                else
                    error = _L("The printer did not report its loaded filaments.");
            } catch (const std::exception &e) {
                error = GUI::format_wxstr(_L("Unexpected reply from the printer: %s"), e.what());
            }
        })
        .on_error([&](std::string, std::string err, unsigned) { error = wxString::FromUTF8(err); })
        .perform_sync();
    return report;
}

bool U1LanPrintHost::test(wxString &curl_msg) const
{
    std::string base;
    return query_printer(m_base_url, base, curl_msg).has_value();
}

wxString U1LanPrintHost::get_test_ok_msg() const { return _L("Connected to the Snapmaker U1 over the local network."); }

wxString U1LanPrintHost::get_test_failed_msg(wxString &msg) const
{
    return GUI::format_wxstr("%s: %s", _L("Could not reach the Snapmaker U1 on the local network"), msg);
}

std::string U1LanPrintHost::reconcile(U1Jobs::Job &job, const std::string &base_url)
{
    const nlohmann::json status = query_status(base_url);
    const std::string    state  = print_state(status);
    if ((state == "printing" || state == "paused") && status["print_stats"].value("filename", std::string()) == job.remote_path) {
        job.recovered_from_lost_ack = true;
        U1Jobs::save(job, U1Jobs::State::Printing,
                     "The Start reply was lost. The printer reports it is printing this exact file, so the job was bound to it without sending the Start again.");
    }
    return state;
}

wxString U1LanPrintHost::blockers(const boost::filesystem::path &file, const nlohmann::json &status, bool start) const
{
    const GcodeFacts facts = read_gcode_facts(file);
    if (!facts.printer_model.empty() && !boost::icontains(facts.printer_model, "U1"))
        return GUI::format_wxstr(_L("This file was sliced for %1%, but the selected printer is a Snapmaker U1."), facts.printer_model);
    if (!start)
        return {};
    const std::string state = print_state(status);
    if (state != "standby" && state != "complete" && state != "cancelled")
        return GUI::format_wxstr(_L("The printer is %1%, so a print cannot be started now."), state.empty() ? std::string("not answering") : state);
    const nlohmann::json loaded = status.value("print_task_config", nlohmann::json::object()).value("filament_exist", nlohmann::json::array());
    for (size_t filament = 0; filament < m_options.filament_to_toolhead.size(); ++filament) {
        const int toolhead = m_options.filament_to_toolhead[filament];
        if (toolhead == U1PrintTask::UNMAPPED)
            continue;
        if (size_t(toolhead) < loaded.size() && loaded[toolhead].is_boolean() && !loaded[toolhead].get<bool>())
            return GUI::format_wxstr(_L("Toolhead %1% has no filament loaded (needed for filament %2%)."), toolhead + 1, filament + 1);
        const std::string object = toolhead == 0 ? "extruder" : "extruder" + std::to_string(toolhead);
        const double      fitted = status.value(object, nlohmann::json::object()).value("nozzle_diameter", 0.);
        const double      sliced = filament < facts.nozzles.size() ? facts.nozzles[filament] : (facts.nozzles.empty() ? 0. : facts.nozzles.back());
        if (fitted > 0. && sliced > 0. && std::abs(fitted - sliced) > 0.01)
            return GUI::format_wxstr(_L("Filament %1% was sliced for a %2% mm nozzle, but toolhead %3% has a %4% mm nozzle."), filament + 1, sliced,
                                     toolhead + 1, fitted);
    }
    return {};
}

bool U1LanPrintHost::upload(PrintHostUpload upload_data, ProgressFn progress_fn, ErrorFn error_fn, InfoFn /*info_fn*/) const
{
    std::string error;
    if (!U1PrintTask::validate(m_options, error)) {
        error_fn(wxString::FromUTF8(error));
        return false;
    }
    const bool        start   = upload_data.post_action == PrintHostPostUploadAction::StartPrint;
    const std::string printer = U1Jobs::printer_key(m_base_url);
    const wxString    nothing_sent = " " + _L("Nothing was sent to the printer.");

    // One send per printer at a time, and none while an earlier Start is still unsettled.
    BusyPrinter busy(printer);
    if (!busy.acquired) {
        error_fn(_L("Another job is already being sent to this printer.") + nothing_sent);
        return false;
    }
    if (auto open_job = U1Jobs::unresolved(printer)) {
        if (reconcile(*open_job, m_base_url).empty() || open_job->unresolved()) {
            if (open_job->state != U1Jobs::State::Held)
                U1Jobs::save(*open_job, U1Jobs::State::Held, "The Start was sent before the app closed and was never confirmed.");
            error_fn(_L("An earlier Start on this printer was never confirmed. Check the printer, then press Print again and say whether it started.") +
                     nothing_sent);
            return false;
        }
    }

    // The job is bound to the exact bytes handed over here.
    U1Jobs::Job job;
    job.id           = U1Jobs::new_id();
    job.printer      = printer;
    job.sha256       = NozzleSession::sha256_file_hex(upload_data.source_path.string());
    job.start        = start;
    job.toolhead_map = m_options.filament_to_toolhead;
    job.created_ms   = U1Jobs::now_ms();
    boost::system::error_code size_error;
    job.bytes = boost::filesystem::file_size(upload_data.source_path, size_error);
    if (job.sha256.empty() || size_error) {
        error_fn(_L("The prepared file could not be read.") + nothing_sent);
        return false;
    }
    if (const wxString why = blockers(upload_data.source_path, query_status(m_base_url), start); !why.empty()) {
        error_fn(why + nothing_sent);
        return false;
    }

    const std::string folder = upload_data.upload_path.parent_path().generic_string();
    const std::string filename = U1Jobs::remote_name(upload_data.upload_path.filename().string(), job.sha256);
    const std::string path     = folder.empty() ? filename : folder + "/" + filename;
    job.remote_path            = path;
    if (!U1Jobs::save(job, U1Jobs::State::Uploading)) {
        error_fn(_L("The job record could not be written, so the job was not sent."));
        return false;
    }
    BOOST_LOG_TRIVIAL(warning) << "U1 LAN: job " << job.id << " sha256 " << job.sha256 << " uploading " << upload_data.source_path << " as " << path
                               << " to " << m_base_url;

    bool res = true;
    // No timeout_max: the U1 accepts uploads at ~1.25 MB/s, so a large job
    // legitimately takes minutes. Stalls are the user's to cancel.
    Http::post(m_base_url + "/server/files/upload")
        .form_add("root", "gcodes")
        .form_add("path", folder)
        .form_add("print", "false")
        .form_add_file("file", upload_data.source_path.string(), filename)
        .on_complete([&](std::string body, unsigned status) {
            BOOST_LOG_TRIVIAL(warning) << "U1 LAN: upload complete, HTTP " << status;
        })
        .on_error([&](std::string body, std::string err, unsigned status) {
            BOOST_LOG_TRIVIAL(error) << "U1 LAN: upload failed: " << err << ", HTTP " << status << ", body: " << body;
            U1Jobs::save(job, U1Jobs::State::Failed, "The upload failed: " + err + ". Nothing was started.");
            error_fn(format_error(body, err, status));
            res = false;
        })
        .on_progress([&](Http::Progress progress, bool &cancel) {
            progress_fn(std::move(progress), cancel);
            if (cancel)
                res = false;
        })
        .perform_sync();
    if (!res) {
        if (job.state == U1Jobs::State::Uploading)
            U1Jobs::save(job, U1Jobs::State::Cancelled, "Cancelled during the upload. Nothing was started.");
        return false;
    }

    // Read the file back: a Start is only offered for bytes that match the prepared file.
    U1Jobs::save(job, U1Jobs::State::Verifying);
    std::vector<std::string> parts;
    boost::split(parts, path, boost::is_any_of("/"));
    std::string encoded;
    for (const std::string &part : parts)
        encoded += (encoded.empty() ? "" : "/") + Http::url_encode(part);
    Http::get(m_base_url + "/server/files/gcodes/" + encoded)
        .timeout_connect(QUERY_TIMEOUT_S)
        .on_complete([&](std::string body, unsigned) { job.uploaded_sha256 = sha256_hex(body); })
        .on_error([&](std::string, std::string err, unsigned) { BOOST_LOG_TRIVIAL(error) << "U1 LAN: read-back failed: " << err; })
        .perform_sync();
    if (job.uploaded_sha256 != job.sha256) {
        U1Jobs::save(job, U1Jobs::State::Failed, "The file on the printer does not match the prepared file. Nothing was started.");
        error_fn(_L("The file on the printer does not match the prepared file, so the print was not started."));
        return false;
    }
    if (!start) {
        U1Jobs::save(job, U1Jobs::State::Uploaded, "Uploaded and verified. No Start was requested.");
        return true;
    }

    // Re-check the printer immediately before the Start, then record the Start as sent before sending it.
    if (const wxString why = blockers(upload_data.source_path, query_status(m_base_url), true); !why.empty()) {
        U1Jobs::save(job, U1Jobs::State::Failed, "The printer changed before the Start. The file is on the printer; nothing was started.");
        error_fn(why + " " + _L("The file is on the printer; nothing was started."));
        return false;
    }
    job.start_sent = true;
    if (!U1Jobs::save(job, U1Jobs::State::Starting)) {
        job.start_sent = false;
        error_fn(_L("The job record could not be written, so the Start was not sent. The file is on the printer."));
        return false;
    }
    wxString detail;
    switch (start_print(path, detail)) {
    case StartOutcome::Accepted:
        U1Jobs::save(job, U1Jobs::State::Printing, "The printer accepted the Start.");
        return true;
    case StartOutcome::Rejected:
        U1Jobs::save(job, U1Jobs::State::Failed, "The printer refused the Start. The file is on the printer; nothing was started.");
        error_fn(detail);
        return false;
    case StartOutcome::Unknown: break;
    }

    // The Start may or may not have run. It is never sent again: the printer's own state decides.
    U1Jobs::save(job, U1Jobs::State::StartUnconfirmed, GUI::into_u8(detail));
    std::string seen;
    for (int i = 0; i < RECONCILE_SECONDS; ++i) {
        const std::string state = reconcile(job, m_base_url);
        if (!state.empty())
            seen = state;
        if (job.state == U1Jobs::State::Printing) {
            BOOST_LOG_TRIVIAL(warning) << "U1 LAN: job " << job.id << " bound to the running print after a lost Start reply";
            return true;
        }
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    U1Jobs::save(job, U1Jobs::State::Held, "The Start reply was lost and the printer does not show this job. The Start was not sent again.");
    error_fn(GUI::format_wxstr(_L("The printer did not confirm the Start, and it was not sent again (%1%). The printer reports: %2%.\n\n"
                                  "Check the printer. The next time you press Print you will be asked whether this job started."),
                               detail, seen.empty() ? std::string("no answer") : seen));
    return false;
}

U1LanPrintHost::StartOutcome U1LanPrintHost::start_print(const std::string &path, wxString &detail) const
{
    namespace beast     = boost::beast;
    namespace websocket = beast::websocket;
    using tcp           = boost::asio::ip::tcp;

    // The U1 registers start_local_print for websocket/MQTT only (HTTP is
    // excluded), so call it as JSON-RPC on Moonraker's websocket.
    std::mt19937         rng(std::random_device{}());
    const int            request_id = std::uniform_int_distribution<int>(100000, 2000000000)(rng);
    const nlohmann::json request    = {{"jsonrpc", "2.0"},
                                       {"method", "server.files.start_local_print"},
                                       {"params", U1PrintTask::build_start_local_print_body(path, 1, m_options)},
                                       {"id", request_id}};
    BOOST_LOG_TRIVIAL(warning) << "U1 LAN: start_local_print " << request.dump();

    std::string host = m_base_url.substr(m_base_url.find("://") + 3);
    std::string port = "80";
    if (const auto colon = host.rfind(':'); colon != std::string::npos) {
        port = host.substr(colon + 1);
        host = host.substr(0, colon);
    }

    // Until the request has been written the printer cannot have started anything.
    bool sent = false;
    try {
        boost::asio::io_context            ioc;
        websocket::stream<beast::tcp_stream> ws{ioc};
        beast::get_lowest_layer(ws).expires_after(std::chrono::seconds(10));
        beast::get_lowest_layer(ws).connect(tcp::resolver{ioc}.resolve(host, port));
        ws.handshake(host + ":" + port, "/websocket");
        sent = true;
        ws.write(boost::asio::buffer(request.dump()));

        // Blocking reads ignore the stream's expiry, so bound each one at the socket: a printer
        // that goes silent must end in "unknown", not in a thread that waits for ever.
        const auto native = beast::get_lowest_layer(ws).socket().native_handle();
#ifdef _WIN32
        const DWORD read_timeout = 5000;
        ::setsockopt(native, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char *>(&read_timeout), sizeof(read_timeout));
#else
        const timeval read_timeout{5, 0};
        ::setsockopt(native, SOL_SOCKET, SO_RCVTIMEO, &read_timeout, sizeof(read_timeout));
#endif
        beast::get_lowest_layer(ws).expires_never();

        // Moonraker also pushes status notifications on this socket; wait for
        // our reply. The printer scans metadata before starting, so allow time.
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
        for (;;) {
            if (std::chrono::steady_clock::now() > deadline)
                throw std::runtime_error("no reply within 60 s");
            beast::flat_buffer buffer;
            beast::error_code  read_error;
            ws.read(buffer, read_error);
            if (read_error == boost::asio::error::would_block || read_error == boost::asio::error::try_again ||
                read_error == boost::asio::error::timed_out)
                continue; // nothing arrived in this slice; the deadline above decides
            if (read_error)
                throw beast::system_error(read_error);
            const auto reply = nlohmann::json::parse(beast::buffers_to_string(buffer.data()), nullptr, false);
            if (reply.is_discarded() || reply.value("id", -1) != request_id)
                continue;
            beast::error_code ignored;
            beast::get_lowest_layer(ws).socket().shutdown(tcp::socket::shutdown_both, ignored);

            BOOST_LOG_TRIVIAL(warning) << "U1 LAN: start_local_print reply " << reply.dump();
            if (reply.contains("error")) {
                detail = GUI::format_wxstr(_L("The file was uploaded, but the print could not be started: %s"),
                                           reply["error"].value("message", std::string("unknown error")));
                return StartOutcome::Rejected;
            }
            const auto result = reply.value("result", nlohmann::json::object());
            if (result.is_object() && result.value("state", "") == "error") {
                detail = GUI::format_wxstr(_L("The printer refused to start the print: %s"), result.value("message", std::string("unknown error")));
                return StartOutcome::Rejected;
            }
            return StartOutcome::Accepted;
        }
    } catch (const std::exception &e) {
        BOOST_LOG_TRIVIAL(error) << "U1 LAN: start_local_print failed (request sent: " << sent << "): " << e.what();
        if (!sent) {
            detail = GUI::format_wxstr(_L("The file was uploaded, but the printer could not be reached to start it: %s"), e.what());
            return StartOutcome::Rejected;
        }
        detail = wxString::FromUTF8(e.what());
        return StartOutcome::Unknown;
    }
}

} // namespace Slic3r
