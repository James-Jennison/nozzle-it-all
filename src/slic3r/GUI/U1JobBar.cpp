#include "U1JobBar.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "NIATheme.hpp"
#include "Widgets/Button.hpp"
#include "format.hpp"

#include <boost/filesystem/path.hpp>

#include <wx/sizer.h>
#include <wx/stattext.h>

#include <utility>

namespace Slic3r::GUI {

U1JobBar::U1JobBar(wxWindow *parent, std::function<void()> open_printer_tab) : wxPanel(parent)
{
    auto *line = new wxBoxSizer(wxHORIZONTAL);
    m_text     = new wxStaticText(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxDefaultSize, wxST_ELLIPSIZE_MIDDLE);
    m_open     = new ::Button(this, _L("Open Printer tab"));
    m_open->SetStyle(ButtonStyle::Regular, ButtonType::Window);
    m_open->Bind(wxEVT_BUTTON, [open = std::move(open_printer_tab)](wxCommandEvent &) {
        if (open)
            open();
    });
    line->Add(m_text, 1, wxALIGN_CENTER_VERTICAL | wxLEFT, FromDIP(12));
    line->Add(m_open, 0, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(4));
    SetSizer(line);
    on_sys_color_changed();
    Hide();
}

void U1JobBar::on_sys_color_changed()
{
    const bool dark = wxGetApp().dark_mode();
    SetBackgroundColour(NIATheme::accent_soft(dark));
    m_text->SetForegroundColour(NIATheme::text(dark));
    Refresh();
}

void U1JobBar::set_active(const std::vector<U1JobWatcher::Active> &active)
{
    const bool was_shown = IsShown();
    if (active.empty()) {
        if (was_shown) {
            Hide();
            GetParent()->Layout();
        }
        return;
    }
    const U1JobWatcher::Active &first = active.front();
    const wxString name = from_u8(first.job.origin.file_name.empty() ? boost::filesystem::path(first.job.remote_path).filename().string() :
                                                                        first.job.origin.file_name);
    wxString text;
    if (!first.reachable)
        // TRN %1% is a file name, %2% a printer address.
        text = format_wxstr(_L("%1% on %2%: the printer is not answering; last known as printing"), name, first.job.printer);
    else if (first.progress < 0.)
        text = format_wxstr(first.paused ? _L("%1% on %2%: paused") : _L("%1% on %2%: printing"), name, first.job.printer);
    else
        // TRN %1% is a file name, %2% a printer address, %3% a whole number of percent.
        text = format_wxstr(first.paused ? _L("%1% on %2%: paused at %3%%%") : _L("%1% on %2%: printing, %3%%%"), name, first.job.printer,
                            int(first.progress * 100. + 0.5));
    if (active.size() > 1)
        // TRN %1% is how many other jobs are printing.
        text += "  " + format_wxstr(_L_PLURAL("(and %1% more job)", "(and %1% more jobs)", active.size() - 1), active.size() - 1);
    m_text->SetLabel(text);
    if (!was_shown) {
        Show();
        GetParent()->Layout();
    } else
        Layout();
}

} // namespace Slic3r::GUI
