#include "NiaBridgePrintHost.hpp"

#include "Http.hpp"
#include "NozzleSession.hpp"
#include "slic3r/GUI/GUI.hpp"
#include "slic3r/GUI/I18N.hpp"
#include "slic3r/GUI/format.hpp"

#include <boost/log/trivial.hpp>
#include <boost/nowide/cstdlib.hpp>
#include <nlohmann/json.hpp>

#include <chrono>
#include <thread>

namespace Slic3r {

namespace {

using json = nlohmann::json;

constexpr long REQUEST_TIMEOUT_S = 10;
constexpr auto POLL_INTERVAL     = std::chrono::milliseconds(250);

struct Reply
{
    bool        ok = false;
    unsigned    status = 0;
    json        body;
    std::string error;
};

Reply request(Http http)
{
    Reply reply;
    http.timeout_connect(REQUEST_TIMEOUT_S)
        .timeout_max(REQUEST_TIMEOUT_S)
        .on_complete([&](std::string body, unsigned status) {
            reply.status = status;
            reply.body   = json::parse(body, nullptr, false);
            reply.ok     = !reply.body.is_discarded();
            if (!reply.ok)
                reply.error = "The NIA bridge sent a reply that is not JSON.";
        })
        .on_error([&](std::string body, std::string error, unsigned status) {
            reply.status   = status;
            const json doc = json::parse(body, nullptr, false);
            reply.error    = !doc.is_discarded() && doc.contains("error") ? doc.value("error", error) : error;
        })
        .perform_sync();
    return reply;
}

Reply post_json(const std::string &url, const json &body)
{
    return request(std::move(Http::post(url).header("Content-Type", "application/json").set_post_body(body.dump())));
}

} // namespace

std::string NiaBridgePrintHost::bridge_url()
{
    const char *value = boost::nowide::getenv("NIA_BRIDGE_URL");
    std::string url   = value != nullptr ? value : "";
    while (!url.empty() && url.back() == '/')
        url.pop_back();
    return url;
}

std::optional<nlohmann::json> NiaBridgePrintHost::lookup_job(const std::string &printer_address, const std::string &filename)
{
    const std::string bridge = bridge_url();
    if (bridge.empty() || filename.empty())
        return std::nullopt;
    Reply reply = request(Http::get(bridge + "/v1/lookup?address=" + Http::url_encode(printer_address) + "&filename=" + Http::url_encode(filename)));
    if (!reply.ok || !reply.body.is_object() || !reply.body.contains("jobId"))
        return std::nullopt;
    return reply.body;
}

NiaBridgePrintHost::NiaBridgePrintHost(std::string bridge_url, std::string printer_address, std::string intent_id, U1PrintTask::Options options)
    : m_bridge_url(std::move(bridge_url))
    , m_printer_address(std::move(printer_address))
    , m_intent_id(std::move(intent_id))
    , m_options(std::move(options))
{}

bool NiaBridgePrintHost::test(wxString &curl_msg) const
{
    const Reply reply = request(Http::get(m_bridge_url + "/v1/health"));
    if (!reply.ok)
        curl_msg = wxString::FromUTF8(reply.error);
    return reply.ok;
}

wxString NiaBridgePrintHost::get_test_ok_msg() const { return _L("Connected to the NIA bridge."); }

wxString NiaBridgePrintHost::get_test_failed_msg(wxString &msg) const
{
    return GUI::format_wxstr("%s: %s", _L("Could not reach the NIA bridge"), msg);
}

bool NiaBridgePrintHost::upload(PrintHostUpload upload_data, ProgressFn progress_fn, ErrorFn error_fn, InfoFn /*info_fn*/) const
{
    std::string error;
    if (!U1PrintTask::validate(m_options, error)) {
        error_fn(wxString::FromUTF8(error));
        return false;
    }

    // The digest is taken here, from the file this job was handed, so the
    // bridge can refuse anything that is not byte-for-byte this prepared file.
    const std::string source = upload_data.source_path.string();
    const std::string sha256 = NozzleSession::sha256_file_hex(source);
    if (sha256.empty()) {
        error_fn(_L("The prepared file could not be read."));
        return false;
    }

    const bool start = upload_data.post_action == PrintHostPostUploadAction::StartPrint;
    const json request_body = {{"intentId", m_intent_id},
                               {"printerAddress", m_printer_address},
                               {"sourcePath", source},
                               {"sha256", sha256},
                               {"remoteName", upload_data.upload_path.generic_string()},
                               {"start", start},
                               {"toolheadMap", m_options.filament_to_toolhead},
                               {"printerModel", "Snapmaker U1"}};
    BOOST_LOG_TRIVIAL(warning) << "NIA bridge: submit intent " << m_intent_id << " sha256 " << sha256 << " start=" << start;

    Reply reply = post_json(m_bridge_url + "/v1/jobs", request_body);
    if (!reply.ok) {
        error_fn(GUI::format_wxstr(_L("The NIA bridge did not accept the job. Nothing was sent to the printer.\n\n%s"), reply.error));
        return false;
    }

    const std::string job_url = m_bridge_url + "/v1/jobs/" + m_intent_id;
    const std::string empty_buffer;
    bool              cancel_sent = false;
    int               failures    = 0;
    for (;;) {
        const json       &job   = reply.body;
        const std::string state = job.value("state", "");
        const size_t      total = job.value("totalBytes", size_t(0));
        const size_t      sent  = job.value("sentBytes", size_t(0));

        bool cancel = false;
        progress_fn(Http::Progress(0, 0, total, sent, empty_buffer), cancel);
        if (cancel && !cancel_sent) {
            // Honoured only while nothing has been asked of the printer's motion system.
            post_json(job_url + "/cancel", json::object());
            cancel_sent = true;
        }

        if (state == "PRINTING" || state == "UPLOADED") {
            BOOST_LOG_TRIVIAL(warning) << "NIA bridge: intent " << m_intent_id << " finished as " << state << " " << job.dump();
            return true;
        }
        if (state == "FAILED" || state == "CANCELLED" || state == "HELD") {
            BOOST_LOG_TRIVIAL(warning) << "NIA bridge: intent " << m_intent_id << " ended as " << state << " " << job.dump();
            const std::string message = job.value("message", state);
            if (state == "HELD")
                error_fn(GUI::format_wxstr(_L("The printer did not confirm the Start, and it was not sent again.\n\n%s\n\n"
                                              "Check the printer on the Printer tab before starting another print."), message));
            else if (state == "FAILED")
                error_fn(wxString::FromUTF8(message));
            return false;
        }

        std::this_thread::sleep_for(POLL_INTERVAL);
        Reply next = request(Http::get(job_url));
        if (next.ok) {
            reply    = std::move(next);
            failures = 0;
        } else if (++failures > 40) {
            error_fn(GUI::format_wxstr(_L("Lost contact with the NIA bridge while it was handling the job. It does not resend a Start; "
                                          "check the printer on the Printer tab.\n\n%s"), next.error));
            return false;
        }
    }
}

} // namespace Slic3r
