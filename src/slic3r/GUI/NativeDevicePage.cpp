#include "NativeDevicePage.hpp"

#include "DirectLanPage.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "Widgets/Button.hpp"

#include <wx/sizer.h>

namespace Slic3r::GUI {

NativeDevicePage::NativeDevicePage(wxWindow* parent) : wxPanel(parent)
{
    auto* sizer = new wxBoxSizer(wxVERTICAL);

    // Shown only while a web interface is hosted (host_web_page()).
    m_switch_bar = new wxPanel(this);
    m_switch_bar->SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    auto* bar     = new wxBoxSizer(wxHORIZONTAL);
    m_btn_monitor = new ::Button(m_switch_bar, _L("Monitor"));
    m_btn_web     = new ::Button(m_switch_bar, _L("Web interface"));
    m_btn_monitor->SetToolTip(_L("Job, temperatures, controls and camera"));
    m_btn_web->SetToolTip(_L("The printer's own web interface"));
    m_btn_monitor->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_web(false); });
    m_btn_web->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { show_web(true); });
    bar->Add(m_btn_monitor, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(12));
    bar->Add(m_btn_web, 0, wxALIGN_CENTER_VERTICAL | wxLEFT | wxTOP, FromDIP(8));
    m_switch_bar->SetSizer(bar);
    m_switch_bar->Hide();
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
    SetSizer(sizer);

    Bind(wxEVT_SHOW, [this](wxShowEvent& evt) {
        set_active(evt.IsShown());
        evt.Skip();
    });
}

NativeDevicePage::~NativeDevicePage() = default;

void NativeDevicePage::set_active(bool active)
{
    m_active = active;
    // The native panel holds no connection while the web interface covers it.
    m_panel->set_active(active && !m_web);
}

void NativeDevicePage::reload()
{
    // Drop the connection to the previous printer and reconnect to the new one.
    m_panel->set_active(false);
    m_panel->set_active(m_active && !m_web);
}

void NativeDevicePage::on_sys_color_changed()
{
    m_switch_bar->SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    apply_switch_state();
    m_panel->on_sys_color_changed();
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
    m_switch_bar->Show();
    m_web = false;
    apply_switch_state();
    m_panel->Show();
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
    m_switch_bar->Hide();
    m_panel->Show();
    m_panel->set_active(m_active);
    Layout();
    return page;
}

void NativeDevicePage::show_web(bool web)
{
    web = web && m_web_page != nullptr;
    if (web == m_web)
        return;
    m_web = web;
    apply_switch_state();
    if (web) {
        m_panel->set_active(false);
        m_panel->Hide();
        if (m_before_web_show)
            m_before_web_show();
        m_web_page->Show();
    } else {
        m_web_page->Hide();
        m_panel->Show();
        m_panel->set_active(m_active);
    }
    Layout();
}

void NativeDevicePage::apply_switch_state()
{
    m_btn_monitor->SetStyle(m_web ? ButtonStyle::Regular : ButtonStyle::Confirm, ButtonType::Window);
    m_btn_web->SetStyle(m_web ? ButtonStyle::Confirm : ButtonStyle::Regular, ButtonType::Window);
    m_switch_bar->Refresh();
}

} // namespace Slic3r::GUI
