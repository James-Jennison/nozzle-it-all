#include "NativeDevicePage.hpp"

#include "DirectLanPage.hpp"
#include "FleetPanel.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "U1JobHistoryPanel.hpp"
#include "Widgets/Button.hpp"

#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "slic3r/Utils/Http.hpp"

#include <nlohmann/json.hpp>

#include <wx/sizer.h>
#include <wx/stattext.h>

#include <map>

namespace Slic3r::GUI {

namespace {
// What the printers asked so far turned out to be, by address: true for a Klipper (Moonraker) printer.
// Main thread only. An address that did not answer is not kept: it is asked again.
std::map<std::string, bool> s_is_klipper;

std::string octo_type_host()
{
    const PresetBundle* bundle = wxGetApp().preset_bundle;
    if (bundle == nullptr)
        return {};
    const auto* host_type = bundle->printers.get_edited_preset().config.option<ConfigOptionEnum<PrintHostType>>("host_type");
    return host_type != nullptr && host_type->value == htOctoPrint ? resolve_printer_lan_host() : std::string();
}
} // namespace

NativeDevicePage::NativeDevicePage(wxWindow* parent) : wxPanel(parent)
{
    view_printer({}, {}); // a new page shows the selected printer, whatever an earlier page was showing

    auto* sizer = new wxBoxSizer(wxVERTICAL);

    // Monitor and History always; Web interface only while a web page is hosted (host_web_page()).
    m_switch_bar = new wxPanel(this);
    m_switch_bar->SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    auto* bar     = new wxBoxSizer(wxHORIZONTAL);
    m_btn_monitor = new ::Button(m_switch_bar, _L("Monitor"));
    m_btn_history = new ::Button(m_switch_bar, _L("History"));
    m_btn_printers = new ::Button(m_switch_bar, _L("Printers"));
    m_btn_web     = new ::Button(m_switch_bar, _L("Web interface"));
    m_btn_printers->SetToolTip(_L("Every printer with an address, and what each is doing"));
    m_btn_printers->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_view(View::Printers); });
    m_btn_history->SetToolTip(_L("Jobs sent to this printer from this app, and how they ended"));
    m_btn_history->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_view(View::History); });
    m_btn_monitor->SetToolTip(_L("Job, temperatures, controls and camera"));
    m_btn_web->SetToolTip(_L("The printer's own web interface"));
    m_btn_monitor->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_web(false); });
    m_btn_web->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_web(true); });
    bar->Add(m_btn_monitor, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(12));
    bar->Add(m_btn_history, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(8));
    bar->Add(m_btn_printers, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(8));
    bar->Add(m_btn_web, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(8));
    m_notice = new wxStaticText(m_switch_bar, wxID_ANY, _L("This printer does not answer as a Klipper printer. Monitor, History and the controls need Klipper (Moonraker); the printer's own page is on the Web interface side."));
    m_notice->SetForegroundColour(NIATheme::text_muted(wxGetApp().dark_mode()));
    m_notice->Hide();
    bar->Add(m_notice, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(12));
    m_switch_bar->SetSizer(bar);
    m_btn_web->Hide();
    sizer->Add(m_switch_bar, 0, wxEXPAND);

    m_panel = new NativePrinterPanel(this, [this](bool active, const std::string& lan_host, const PrinterPanel::Webcam& webcam) {
        if (m_camera == nullptr)
            return;
        if (!active || lan_host.empty()) {
            m_camera->deactivate();
            return;
        }
        // An MJPEG webcam without stream_url streams at the camera service's default path. A WebRTC camera
        // (a Snapmaker U1's) is shown by its ordinary stream at that path too, as on its Printers card: the
        // printer's own page may be playing the WebRTC session, and a second one was refused (502) when tried.
        std::string mjpeg = webcam.webrtc ? std::string() : webcam.stream_url;
        if (mjpeg.empty())
            mjpeg = "/webcam/stream.mjpg";
        m_camera->set_mjpeg_only(mjpeg);
        m_camera->activate(DeviceWorkspaceEndpoint{"native-printer", std::string(), lan_host, true}, ++m_camera_generation);
    });
    m_camera = new DirectLanPage(m_panel->camera_host());
    m_camera->set_embedded(true);
    m_panel->camera_host()->GetSizer()->Add(m_camera, 1, wxEXPAND);

    sizer->Add(m_panel, 1, wxEXPAND);
    m_history = new U1JobHistoryPanel(this);
    m_history->Hide();
    sizer->Add(m_history, 1, wxEXPAND);
    m_fleet = new FleetPanel(this, [this](const std::string& host, const std::string& name) {
        // Monitor and History show the opened printer; the printer selected for slicing is not touched.
        view_printer(host, name);
        refresh();
        show_view(View::Monitor);
    });
    m_fleet->Hide();
    sizer->Add(m_fleet, 1, wxEXPAND);
    SetSizer(sizer);
    apply_switch_state();

    Bind(wxEVT_SHOW, [this](wxShowEvent& evt) {
        set_active(evt.IsShown());
        evt.Skip();
    });
}

NativeDevicePage::~NativeDevicePage()
{
    m_alive->store(false);
    // The printer opened from a card is this page's; without the page the selected printer is the one shown.
    view_printer({}, {});
}

void NativeDevicePage::ask_server_kind()
{
    const std::string host = octo_type_host();
    if (host.empty() || s_is_klipper.count(host) != 0)
        return apply_server_kind();
    // One read request, on the request's own thread; its answer is taken on the main thread.
    const auto answer = [this, alive = m_alive, host](bool reached, bool klipper) {
        wxGetApp().CallAfter([this, alive, host, reached, klipper] {
            if (!reached || !alive->load() || wxGetApp().is_closing())
                return;
            s_is_klipper[host] = klipper;
            apply_server_kind();
        });
    };
    Http::get("http://" + host + "/server/info")
        .timeout_connect(4)
        .timeout_max(6)
        .on_complete([answer](std::string body, unsigned) {
            const auto doc = nlohmann::json::parse(body, nullptr, false);
            answer(true, !doc.is_discarded() && doc.is_object() && doc.contains("result") && doc["result"].is_object() &&
                             (doc["result"].contains("klippy_state") || doc["result"].contains("klippy_connected")));
        })
        // "Not found" means a server is there and does not know the question: not a Moonraker. Any other
        // status (a Moonraker that wants a key, a server in trouble) says nothing about what it is.
        .on_error([answer](std::string, std::string, unsigned status) { answer(status == 404 || status == 405, false); })
        .perform();
}

void NativeDevicePage::apply_server_kind()
{
    const std::string host  = octo_type_host();
    const auto        known = host.empty() ? s_is_klipper.end() : s_is_klipper.find(host);
    const bool        other = known != s_is_klipper.end() && !known->second && viewed_printer_name().empty();
    if (m_notice->IsShown() != other) {
        m_notice->Show(other);
        m_switch_bar->Layout();
    }
    // Its own page first, once: the person can still go to the other sides.
    if (other && !m_web_first_done && m_web_page != nullptr && !m_web && !m_history_shown && !m_fleet_shown) {
        m_web_first_done = true;
        show_view(View::Web);
    }
}

void NativeDevicePage::set_active(bool active)
{
    if (active && !m_active)
        ask_server_kind(); // once for each address; known addresses are answered from what was learnt
    m_active = active;
    // The native panel holds no connection while another side covers it.
    m_panel->set_active(active && !m_web && !m_history_shown && !m_fleet_shown);
    m_history->set_active(active && m_history_shown);
    m_fleet->set_active(active && m_fleet_shown);
}

void NativeDevicePage::reload()
{
    // The selection changed: the page shows the selected printer again.
    view_printer({}, {});
    m_fleet->reload();
    refresh();
    m_web_first_done = false;
    ask_server_kind();
}

void NativeDevicePage::refresh()
{
    // Drop the connection to the previous printer and reconnect to the new one.
    m_panel->set_active(false);
    m_panel->set_active(m_active && !m_web && !m_history_shown && !m_fleet_shown);
    m_history->reload();
    apply_switch_state();
    m_switch_bar->Layout();
}

void NativeDevicePage::on_sys_color_changed()
{
    m_switch_bar->SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    m_notice->SetForegroundColour(NIATheme::text_muted(wxGetApp().dark_mode()));
    apply_switch_state();
    m_panel->on_sys_color_changed();
    m_history->on_sys_color_changed();
    m_fleet->on_sys_color_changed();
}

void NativeDevicePage::msw_rescale() { on_sys_color_changed(); }

void NativeDevicePage::host_web_page(wxWindow* page, std::function<void()> before_show)
{
    if (page == nullptr || page == m_web_page)
        return;
    m_before_web_show = std::move(before_show);
    m_web_page        = page;
    page->Hide();
    page->Reparent(this);
    GetSizer()->Add(page, 1, wxEXPAND);
    m_btn_web->Show();
    m_web = false;
    apply_switch_state();
    m_panel->Show(!m_history_shown && !m_fleet_shown);
    m_switch_bar->Layout();
    Layout();
    apply_server_kind();
}

wxWindow* NativeDevicePage::release_web_page()
{
    wxWindow* page = m_web_page;
    if (page == nullptr)
        return nullptr;
    page->Hide();
    GetSizer()->Detach(page);
    m_web_page        = nullptr;
    m_before_web_show = nullptr;
    m_web             = false;
    m_btn_web->Hide();
    apply_switch_state();
    m_panel->Show(!m_history_shown && !m_fleet_shown);
    m_panel->set_active(m_active && !m_history_shown && !m_fleet_shown);
    m_switch_bar->Layout();
    Layout();
    return page;
}

void NativeDevicePage::show_web(bool web)
{
    // Leaving the web side lands on Monitor; a request for Monitor leaves History too.
    show_view(web ? View::Web : View::Monitor);
}

void NativeDevicePage::show_view(View view)
{
    if (view == View::Web && m_web_page == nullptr)
        view = View::Monitor;
    // The hosted web interface is the selected printer's; it is not offered while another printer is viewed.
    if (view == View::Web && !viewed_printer_name().empty())
        view = View::Monitor;
    const bool web     = view == View::Web;
    const bool history = view == View::History;
    const bool fleet   = view == View::Printers;
    if (web == m_web && history == m_history_shown && fleet == m_fleet_shown)
        return;
    m_web           = web;
    m_history_shown = history;
    m_fleet_shown   = fleet;
    apply_switch_state();

    m_panel->set_active(false);
    m_panel->Show(view == View::Monitor);
    m_history->Show(history);
    m_history->set_active(m_active && history);
    m_fleet->Show(fleet);
    m_fleet->set_active(m_active && fleet);
    if (m_web_page != nullptr) {
        if (web && m_before_web_show)
            m_before_web_show();
        m_web_page->Show(web);
    }
    if (view == View::Monitor)
        m_panel->set_active(m_active);
    Layout();
}

void NativeDevicePage::apply_switch_state()
{
    const auto style = [](::Button* button, bool chosen) {
        button->SetStyle(chosen ? ButtonStyle::Confirm : ButtonStyle::Regular, ButtonType::Window);
    };
    style(m_btn_monitor, !m_web && !m_history_shown && !m_fleet_shown);
    style(m_btn_history, m_history_shown);
    style(m_btn_printers, m_fleet_shown);
    m_btn_web->Show(m_web_page != nullptr && viewed_printer_name().empty());
    style(m_btn_web, m_web);
    if (m_notice != nullptr && m_notice->IsShown() && !viewed_printer_name().empty())
        m_notice->Hide(); // the notice is about the selected printer
    m_switch_bar->Refresh();
}

} // namespace Slic3r::GUI
