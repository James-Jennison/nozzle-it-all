#include "U1LanPrintHost.hpp"

#include "Http.hpp"
#include "NozzleSession.hpp"
#include "U1JobJournal.hpp"
#include "slic3r/GUI/I18N.hpp"
#include "slic3r/GUI/GUI.hpp"
#include "slic3r/GUI/format.hpp"

#include <boost/algorithm/string/predicate.hpp>
#include <boost/asio/connect.hpp>
#include <boost/asio/io_context.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/log/trivial.hpp>
#include <boost/algorithm/string.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>

#include <algorithm>
#include <chrono>
#include <functional>
#include <random>
#include <thread>
#include <utility>
#include <vector>

namespace Slic3r {

namespace {

constexpr long QUERY_TIMEOUT_S = 5;
// After a lost Start reply the printer is watched for as long as the Start itself may take.
constexpr int  RECONCILE_SECONDS = 60;
constexpr int  START_REPLY_SECONDS = 60;
// Connecting, the websocket handshake and writing the request each get this long.
constexpr int  CONNECT_SECONDS = 10;
// The read-back is fetched in ranges so the file is never held in memory whole.
constexpr size_t READBACK_CHUNK = 4 * 1024 * 1024;

// print_stats, print_task_config and the four extruders in one read; null when the printer does not answer.
nlohmann::json query_status(const std::string &base_url)
{
    nlohmann::json status;
    Http::get(base_url + "/printer/objects/query?print_stats&print_task_config&extruder&extruder1&extruder2&extruder3")
        .timeout_connect(QUERY_TIMEOUT_S)
        .timeout_max(QUERY_TIMEOUT_S)
        .on_complete([&](std::string body, unsigned) {
            const auto doc = nlohmann::json::parse(body, nullptr, false);
            if (!doc.is_discarded() && doc.is_object() && doc.contains("result") && doc["result"].is_object())
                status = doc["result"].value("status", nlohmann::json());
        })
        .on_error([](std::string, std::string, unsigned) {})
        .perform_sync();
    return status;
}

std::string stats_string(const nlohmann::json &status, const char *key)
{
    if (!status.is_object() || !status.contains("print_stats") || !status["print_stats"].is_object())
        return {};
    const auto &stats = status["print_stats"];
    return stats.contains(key) && stats[key].is_string() ? stats[key].get<std::string>() : std::string();
}

std::string url_path(const std::string &path)
{
    std::vector<std::string> parts;
    boost::split(parts, path, boost::is_any_of("/"));
    std::string encoded;
    for (const std::string &part : parts)
        encoded += (encoded.empty() ? "" : "/") + Http::url_encode(part);
    return encoded;
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
    const std::string    state  = stats_string(status, "state");
    // The file name is unique to this job, and a Start is only ever sent to an idle printer, so the
    // printer running this name can only be this job's own Start having taken effect.
    if ((state == "printing" || state == "paused") && U1Jobs::is_job_file(job, stats_string(status, "filename"))) {
        const auto updated = U1Jobs::update(
            job.id,
            [](U1Jobs::Job &record) -> std::optional<U1Jobs::State> {
                if (!record.unresolved())
                    return std::nullopt;
                record.recovered_from_lost_ack = true;
                return U1Jobs::State::Printing;
            },
            "The Start reply was lost. The printer reports it is printing this job's own file, so the job was bound to it without sending the Start again.",
            "BOUND_FROM_PRINTER_STATE");
        if (updated)
            job = *updated;
    }
    return state;
}

std::function<wxString(const std::vector<int> &, bool)> U1LanPrintHost::readiness_check(const std::string &base_url, U1Jobs::GcodeFacts facts)
{
    return [status = query_status(base_url), facts = std::move(facts)](const std::vector<int> &filament_to_toolhead, bool start) {
        return describe(U1Jobs::start_blocker(facts, status, filament_to_toolhead, start));
    };
}

wxString U1LanPrintHost::describe(const U1Jobs::Blocker &blocker)
{
    using Kind = U1Jobs::Blocker::Kind;
    switch (blocker.kind) {
    case Kind::None: return {};
    case Kind::WrongModel: return GUI::format_wxstr(_L("This file was sliced for %1%, but the selected printer is a Snapmaker U1."), blocker.detail);
    case Kind::PrinterUnreachable: return _L("The printer did not report its state, so a print cannot be started now.");
    case Kind::PrinterNotIdle: return GUI::format_wxstr(_L("The printer is %1%, so a print cannot be started now."), blocker.detail);
    case Kind::ToolheadUnknown:
        return GUI::format_wxstr(_L("The printer did not report whether toolhead %1% has filament loaded (needed for filament %2%)."), blocker.toolhead + 1,
                                 blocker.filament + 1);
    case Kind::ToolheadEmpty:
        return GUI::format_wxstr(_L("Toolhead %1% has no filament loaded (needed for filament %2%)."), blocker.toolhead + 1, blocker.filament + 1);
    case Kind::NozzleUnknown: return GUI::format_wxstr(_L("The printer did not report the nozzle fitted to toolhead %1%."), blocker.toolhead + 1);
    case Kind::NozzleMismatch:
        return GUI::format_wxstr(_L("Filament %1% was sliced for a %2% mm nozzle, but toolhead %3% has a %4% mm nozzle."), blocker.filament + 1,
                                 blocker.sliced, blocker.toolhead + 1, blocker.fitted);
    }
    return {};
}

bool U1LanPrintHost::read_back_sha256(const std::string &path, std::uintmax_t expected_bytes, std::string &sha256) const
{
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (ctx == nullptr || EVP_DigestInit_ex(ctx, EVP_sha256(), nullptr) != 1) {
        EVP_MD_CTX_free(ctx);
        return false;
    }
    const std::string url    = m_base_url + "/server/files/gcodes/" + url_path(path);
    std::uintmax_t    offset = 0;
    bool              ok     = true;
    // A server that ignores Range answers 200 with the whole file: accepted only as the first answer
    // and only when it is exactly the expected length.
    while (ok && offset < expected_bytes) {
        const std::uintmax_t last = std::min<std::uintmax_t>(expected_bytes, offset + READBACK_CHUNK) - 1;
        std::uintmax_t       got  = 0;
        ok                        = false;
        Http::get(url)
            .set_range(std::to_string(offset) + "-" + std::to_string(last))
            .timeout_connect(QUERY_TIMEOUT_S)
            .timeout_max(120)
            .size_limit(size_t(expected_bytes) + 1024)
            .on_complete([&](std::string body, unsigned status) {
                if ((status == 206 && body.size() == size_t(last - offset + 1)) || (status == 200 && offset == 0 && body.size() == expected_bytes)) {
                    ok  = EVP_DigestUpdate(ctx, body.data(), body.size()) == 1;
                    got = body.size();
                }
            })
            .on_error([&](std::string, std::string err, unsigned status) {
                BOOST_LOG_TRIVIAL(error) << "U1 LAN: read-back failed at byte " << offset << ": " << err << ", HTTP " << status;
            })
            .perform_sync();
        offset += got;
        if (got == 0)
            ok = false;
    }
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int  length = 0;
    ok = ok && offset == expected_bytes && EVP_DigestFinal_ex(ctx, digest, &length) == 1;
    EVP_MD_CTX_free(ctx);
    if (!ok)
        return false;
    static const char *hex = "0123456789abcdef";
    sha256.clear();
    for (unsigned int i = 0; i < length; ++i) {
        sha256.push_back(hex[digest[i] >> 4]);
        sha256.push_back(hex[digest[i] & 0x0f]);
    }
    return true;
}

bool U1LanPrintHost::upload(PrintHostUpload upload_data, ProgressFn progress_fn, ErrorFn error_fn, InfoFn /*info_fn*/) const
{
    std::string error;
    if (!U1PrintTask::validate(m_options, error)) {
        error_fn(wxString::FromUTF8(error));
        return false;
    }
    const bool        start        = upload_data.post_action == PrintHostPostUploadAction::StartPrint;
    const std::string printer      = U1Jobs::printer_key(m_base_url);
    const wxString    nothing_sent = " " + _L("Nothing was sent to the printer.");

    // One send per printer at a time, in this and in any other running copy of the app.
    U1Jobs::PrinterLock lock(printer);
    if (!lock.acquired()) {
        error_fn(_L("Another job is already being sent to this printer.") + nothing_sent);
        return false;
    }
    // A record that cannot be read might be an unsettled Start. It is never guessed at.
    std::vector<std::string> unreadable;
    U1Jobs::load_all(&unreadable);
    if (!unreadable.empty()) {
        error_fn(GUI::format_wxstr(_L("A job record could not be read, so it is not known whether an earlier print is unsettled:\n%1%\n\n"
                                      "Check the printer, then move that file out of the folder to send again."), unreadable.front()) + nothing_sent);
        return false;
    }
    if (auto open_job = U1Jobs::unresolved(printer)) {
        reconcile(*open_job, m_base_url);
        if (open_job->unresolved()) {
            U1Jobs::update(open_job->id, [](U1Jobs::Job &record) -> std::optional<U1Jobs::State> {
                return record.unresolved() && record.state != U1Jobs::State::Held ? std::optional<U1Jobs::State>(U1Jobs::State::Held) : std::nullopt;
            }, "The Start was sent and was never confirmed.");
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
    if (job.sha256.empty() || size_error || job.bytes == 0) {
        error_fn(_L("The prepared file could not be read.") + nothing_sent);
        return false;
    }
    const U1Jobs::GcodeFacts facts = U1Jobs::read_gcode_facts(upload_data.source_path);
    if (const auto blocker = U1Jobs::start_blocker(facts, query_status(m_base_url), m_options.filament_to_toolhead, start)) {
        error_fn(describe(blocker) + nothing_sent);
        return false;
    }

    const std::string folder   = upload_data.upload_path.parent_path().generic_string();
    const std::string filename = U1Jobs::remote_name(upload_data.upload_path.filename().string(), job.sha256, job.id);
    const std::string path     = folder.empty() ? filename : folder + "/" + filename;
    job.remote_path            = path;
    if (!U1Jobs::save(job, U1Jobs::State::Uploading)) {
        error_fn(_L("The job record could not be written, so the job was not sent."));
        return false;
    }
    BOOST_LOG_TRIVIAL(warning) << "U1 LAN: job " << job.id << " sha256 " << job.sha256 << " uploading " << upload_data.source_path << " as " << path
                               << " to " << m_base_url;

    bool res = true;
    std::string upload_error;
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
            upload_error = err.empty() ? "HTTP " + std::to_string(status) : err;
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
        if (upload_error.empty())
            U1Jobs::save(job, U1Jobs::State::Cancelled, "Cancelled during the upload. Nothing was started.");
        else
            U1Jobs::save(job, U1Jobs::State::Failed, "The upload failed: " + upload_error + ". Nothing was started.");
        return false;
    }

    // Read the file back: a Start is only offered for bytes that match the prepared file.
    U1Jobs::save(job, U1Jobs::State::Verifying);
    std::string read_back;
    if (!read_back_sha256(path, job.bytes, read_back)) {
        U1Jobs::save(job, U1Jobs::State::Failed, "The uploaded file could not be read back from the printer. Nothing was started.");
        error_fn(_L("The uploaded file could not be read back from the printer to check it, so the print was not started."));
        return false;
    }
    job.uploaded_sha256 = read_back;
    if (read_back != job.sha256) {
        U1Jobs::save(job, U1Jobs::State::Failed, "The file on the printer does not match the prepared file. Nothing was started.");
        error_fn(_L("The file on the printer does not match the prepared file, so the print was not started."));
        return false;
    }
    if (!start) {
        U1Jobs::save(job, U1Jobs::State::Uploaded, "Uploaded and verified. No Start was requested.");
        return true;
    }

    // Re-check the printer immediately before the Start, then record the Start as sent before sending it.
    if (const auto blocker = U1Jobs::start_blocker(facts, query_status(m_base_url), m_options.filament_to_toolhead, true)) {
        U1Jobs::save(job, U1Jobs::State::Failed, "The printer changed before the Start. The file is on the printer; nothing was started.");
        error_fn(describe(blocker) + " " + _L("The file is on the printer; nothing was started."));
        return false;
    }
    job.start_sent = true;
    if (!U1Jobs::save(job, U1Jobs::State::Starting)) {
        // Nothing has been sent and the record on disk still says so.
        job.start_sent = false;
        error_fn(_L("The job record could not be written, so the Start was not sent. The file is on the printer."));
        return false;
    }
    wxString detail;
    switch (start_print(path, detail)) {
    case StartOutcome::Accepted:
        // If this write fails the record stays "Starting": the next send reconciles it against the printer.
        U1Jobs::save(job, U1Jobs::State::Printing, "The printer accepted the Start.");
        return true;
    case StartOutcome::NotSent:
        job.start_sent = false;
        U1Jobs::save(job, U1Jobs::State::Failed, "The Start request never left this computer. The file is on the printer; nothing was started.");
        error_fn(detail);
        return false;
    case StartOutcome::Rejected:
        U1Jobs::save(job, U1Jobs::State::Failed, "The printer refused the Start. The file is on the printer; nothing was started.");
        error_fn(detail);
        return false;
    case StartOutcome::Unknown: break;
    }

    // The Start may or may not have run. It is never sent again: the printer's own state decides.
    U1Jobs::save(job, U1Jobs::State::StartUnconfirmed, "The Start was sent and no reply came back: " + GUI::into_u8(detail));
    std::string seen;
    // Bounded by the clock, not by a count of reads: a printer that stops answering makes each read slow.
    const auto watch_until = std::chrono::steady_clock::now() + std::chrono::seconds(RECONCILE_SECONDS);
    while (std::chrono::steady_clock::now() < watch_until) {
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

    // Before the first byte of the request is written the printer cannot have started anything.
    // From then on, anything short of a reply is "unknown" and is settled from the printer's state.
    // Every step is an asynchronous operation run to completion under a deadline on the stream (the
    // stream's expiry does not apply to blocking calls), so no step can hang this thread: run()
    // returns only once the step's handler has run, with success, an error or the timeout.
    bool write_begun = false;
    try {
        boost::asio::io_context            ioc;
        websocket::stream<beast::tcp_stream> ws{ioc};
        auto                              &stream = beast::get_lowest_layer(ws);
        beast::error_code                  step_error;
        const auto finish = [&](const char *what) {
            ioc.restart();
            ioc.run();
            if (step_error == beast::error::timeout)
                throw std::runtime_error(std::string(what) + " timed out");
            if (step_error)
                throw beast::system_error(step_error);
        };
        const auto endpoints = tcp::resolver{ioc}.resolve(host, port);

        step_error = boost::asio::error::operation_aborted;
        stream.expires_after(std::chrono::seconds(CONNECT_SECONDS));
        stream.async_connect(endpoints, [&step_error](beast::error_code ec, const tcp::endpoint &) { step_error = ec; });
        finish("connecting");

        step_error = boost::asio::error::operation_aborted;
        stream.expires_after(std::chrono::seconds(CONNECT_SECONDS));
        ws.async_handshake(host + ":" + port, "/websocket", [&step_error](beast::error_code ec) { step_error = ec; });
        finish("the websocket handshake");

        const std::string text = request.dump();
        write_begun            = true;
        step_error             = boost::asio::error::operation_aborted;
        stream.expires_after(std::chrono::seconds(CONNECT_SECONDS));
        ws.async_write(boost::asio::buffer(text), [&step_error](beast::error_code ec, std::size_t) { step_error = ec; });
        finish("sending the Start");

        // Moonraker also pushes status notifications on this socket; wait for our reply. The printer
        // scans metadata before starting, so allow time. One deadline covers the whole wait, so a
        // printer that goes silent, or stalls part-way through a frame, ends it as "unknown".
        stream.expires_after(std::chrono::seconds(START_REPLY_SECONDS));
        for (;;) {
            beast::flat_buffer buffer;
            step_error = boost::asio::error::operation_aborted;
            ws.async_read(buffer, [&step_error](beast::error_code ec, std::size_t) { step_error = ec; });
            finish("waiting for the reply");
            const auto reply = nlohmann::json::parse(beast::buffers_to_string(buffer.data()), nullptr, false);
            if (reply.is_discarded() || !reply.is_object() || reply.value("id", -1) != request_id)
                continue;
            beast::error_code ignored;
            beast::get_lowest_layer(ws).socket().shutdown(tcp::socket::shutdown_both, ignored);

            BOOST_LOG_TRIVIAL(warning) << "U1 LAN: start_local_print reply " << reply.dump();
            if (reply.contains("error")) {
                detail = GUI::format_wxstr(_L("The file was uploaded, but the print could not be started: %s"),
                                           reply["error"].is_object() ? reply["error"].value("message", std::string("unknown error")) : std::string("unknown error"));
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
        BOOST_LOG_TRIVIAL(error) << "U1 LAN: start_local_print failed (request begun: " << write_begun << "): " << e.what();
        if (!write_begun) {
            detail = GUI::format_wxstr(_L("The file was uploaded, but the printer could not be reached to start it: %s"), e.what());
            return StartOutcome::NotSent;
        }
        detail = wxString::FromUTF8(e.what());
        return StartOutcome::Unknown;
    }
}

} // namespace Slic3r
