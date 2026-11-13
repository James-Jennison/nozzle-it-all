#include "NativeDevicePage.hpp"

#include "DirectLanPage.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "U1JobHistoryPanel.hpp"
#include "Widgets/Button.hpp"

#include <wx/sizer.h>

namespace Slic3r::GUI {

NativeDevicePage::NativeDevicePage(wxWindow* parent) : wxPanel(parent)
{
    auto* sizer = new wxBoxSizer(wxVERTICAL);

    // Monitor and History always; Web interface only while a web page is hosted (host_web_page()).
    m_switch_bar = new wxPanel(this);
    m_switch_bar->SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    auto* bar     = new wxBoxSizer(wxHORIZONTAL);
    m_btn_monitor = new ::Button(m_switch_bar, _L("Monitor"));
    m_btn_history = new ::Button(m_switch_bar, _L("History"));
    m_btn_web     = new ::Button(m_switch_bar, _L("Web interface"));
    m_btn_history->SetToolTip(_L("Jobs sent to this printer from this app, and how they ended"));
    m_btn_history->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_view(View::History); });
    m_btn_monitor->SetToolTip(_L("Job, temperatures, controls and camera"));
    m_btn_web->SetToolTip(_L("The printer's own web interface"));
    m_btn_monitor->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_web(false); });
    m_btn_web->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_web(true); });
    bar->Add(m_btn_monitor, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(12));
    bar->Add(m_btn_history, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(8));
    bar->Add(m_btn_web, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(8));
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
        // An MJPEG webcam without stream_url streams at the camera service's default path.
        std::string mjpeg = webcam.webrtc ? std::string() : webcam.stream_url;
        if (!webcam.webrtc && mjpeg.empty())
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
    SetSizer(sizer);
    apply_switch_state();

    Bind(wxEVT_SHOW, [this](wxShowEvent& evt) {
        set_active(evt.IsShown());
        evt.Skip();
    });
}

NativeDevicePage::~NativeDevicePage() = default;

void NativeDevicePage::set_active(bool active)
{
    m_active = active;
    // The native panel holds no connection while another side covers it.
    m_panel->set_active(active && !m_web && !m_history_shown);
    m_history->set_active(active && m_history_shown);
}

void NativeDevicePage::reload()
{
    // Drop the connection to the previous printer and reconnect to the new one.
    m_panel->set_active(false);
    m_panel->set_active(m_active && !m_web && !m_history_shown);
    m_history->reload();
}

void NativeDevicePage::on_sys_color_changed()
{
    m_switch_bar->SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    apply_switch_state();
    m_panel->on_sys_color_changed();
    m_history->on_sys_color_changed();
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
    m_panel->Show(!m_history_shown);
    m_switch_bar->Layout();
    Layout();
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
    m_panel->Show(!m_history_shown);
    m_panel->set_active(m_active && !m_history_shown);
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
    const bool web     = view == View::Web;
    const bool history = view == View::History;
    if (web == m_web && history == m_history_shown)
        return;
    m_web           = web;
    m_history_shown = history;
    apply_switch_state();

    m_panel->set_active(false);
    m_panel->Show(view == View::Monitor);
    m_history->Show(history);
    m_history->set_active(m_active && history);
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
    style(m_btn_monitor, !m_web && !m_history_shown);
    style(m_btn_history, m_history_shown);
    style(m_btn_web, m_web);
    m_switch_bar->Refresh();
}

} // namespace Slic3r::GUI
