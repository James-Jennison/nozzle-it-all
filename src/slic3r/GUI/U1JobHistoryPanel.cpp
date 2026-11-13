#include "U1JobHistoryPanel.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/Label.hpp"
#include "format.hpp"
#include "slic3r/Utils/U1JobJournal.hpp"

#include <boost/filesystem/path.hpp>

#include <wx/datetime.h>
#include <wx/panel.h>
#include <wx/sizer.h>
#include <wx/stattext.h>

#include <array>
#include <vector>

namespace Slic3r::GUI {

namespace {

constexpr int REFRESH_MS = 10000;

// Stored value, in the order the choices are listed.
const std::array<const char *, 4> VERDICTS = {"", "success", "partial", "failed"};

wxString when(long long ms)
{
    return ms > 0 ? wxDateTime(static_cast<time_t>(ms / 1000)).Format("%Y-%m-%d %H:%M") : wxString();
}

wxString machine_state(U1Jobs::State state)
{
    switch (state) {
    case U1Jobs::State::Uploading: return _L("Uploading");
    case U1Jobs::State::Verifying: return _L("Checking the uploaded file");
    case U1Jobs::State::Starting: return _L("Start sent");
    case U1Jobs::State::StartUnconfirmed: return _L("Start not confirmed");
    case U1Jobs::State::Held: return _L("Held: Start not confirmed");
    case U1Jobs::State::Printing: return _L("Printing");
    case U1Jobs::State::Uploaded: return _L("Uploaded, not started");
    case U1Jobs::State::Completed: return _L("Finished");
    case U1Jobs::State::Cancelled: return _L("Cancelled");
    case U1Jobs::State::Failed: return _L("Failed");
    }
    return {};
}

wxString leaf(const std::string &path) { return from_u8(boost::filesystem::path(path).filename().string()); }

} // namespace

U1JobHistoryPanel::U1JobHistoryPanel(wxWindow *parent) : wxScrolledWindow(parent, wxID_ANY)
{
    SetScrollRate(0, FromDIP(12));
    auto *outer = new wxBoxSizer(wxVERTICAL);
    m_empty     = new wxStaticText(this, wxID_ANY, wxEmptyString);
    outer->Add(m_empty, 0, wxALL, FromDIP(16));
    m_rows = new wxBoxSizer(wxVERTICAL);
    outer->Add(m_rows, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, FromDIP(12));
    SetSizer(outer);
    m_timer.SetOwner(this);
    Bind(wxEVT_TIMER, [this](wxTimerEvent &) { rebuild(false); });
    on_sys_color_changed();
}

void U1JobHistoryPanel::set_active(bool active)
{
    if (active) {
        rebuild(false);
        m_timer.Start(REFRESH_MS);
    } else
        m_timer.Stop();
}

void U1JobHistoryPanel::reload() { rebuild(true); }

void U1JobHistoryPanel::on_sys_color_changed()
{
    SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    rebuild(true);
}

void U1JobHistoryPanel::rebuild(bool force)
{
    const bool        dark = wxGetApp().dark_mode();
    const std::string host = resolve_printer_lan_host();
    const std::vector<U1Jobs::Job> jobs = host.empty() ? std::vector<U1Jobs::Job>() : U1Jobs::history(host);

    // The rows hold a control the person may be using: rebuild only when what they show changed.
    std::string shown = host + (dark ? "|dark" : "|light");
    for (const U1Jobs::Job &job : jobs)
        shown += "|" + job.id + ":" + U1Jobs::state_name(job.state) + ":" + job.assessment + ":" + std::to_string(job.started_ms) + ":" +
                 std::to_string(job.ended_ms);
    if (!force && shown == m_shown)
        return;
    m_shown = shown;

    Freeze();
    m_rows->Clear(true);
    m_empty->SetForegroundColour(NIATheme::text_muted(dark));
    m_empty->SetLabel(host.empty() ? _L("No printer address is set for the selected printer.") :
                      jobs.empty() ? _L("No job has been sent to this printer from this app yet.") :
                                     _L("Jobs sent to this printer from this app, newest first."));

    for (const U1Jobs::Job &job : jobs) {
        auto *row = new wxPanel(this);
        row->SetBackgroundColour(NIATheme::surface(dark));
        auto *line = new wxBoxSizer(wxHORIZONTAL);
        auto *text = new wxBoxSizer(wxVERTICAL);

        auto *name = new wxStaticText(row, wxID_ANY, job.origin.file_name.empty() ? leaf(job.remote_path) : from_u8(job.origin.file_name),
                                      wxDefaultPosition, wxDefaultSize, wxST_ELLIPSIZE_MIDDLE);
        name->SetFont(Label::Head_14);
        name->SetForegroundColour(NIATheme::text(dark));
        text->Add(name, 0, wxEXPAND);

        // Where it came from: project, plate, and which state of the project.
        wxString origin;
        if (job.origin.plate > 0)
            // TRN %1% is a plate number.
            origin = format_wxstr(_L("Plate %1%"), job.origin.plate);
        if (!job.origin.plate_name.empty())
            origin += " (" + from_u8(job.origin.plate_name) + ")";
        if (!job.origin.project.empty()) {
            origin += (origin.empty() ? "" : wxString::FromUTF8(" · ")) + leaf(job.origin.project);
            if (job.origin.project_saved_ms > 0)
                // TRN %1% is a date and time. Follows a project file name.
                origin += ", " + format_wxstr(job.origin.project_changed ? _L("saved %1%, with later changes not saved") : _L("saved %1%"),
                                              when(job.origin.project_saved_ms));
        } else if (job.origin.plate > 0)
            origin += wxString::FromUTF8(" · ") + _L("project not saved");
        if (!origin.empty()) {
            auto *from = new wxStaticText(row, wxID_ANY, origin, wxDefaultPosition, wxDefaultSize, wxST_ELLIPSIZE_END);
            from->SetForegroundColour(NIATheme::text_muted(dark));
            text->Add(from, 0, wxEXPAND | wxTOP, FromDIP(2));
        }

        // Times are the printer's own; the send time is this computer's and says so.
        wxString times;
        if (job.started_ms > 0 && job.ended_ms > 0)
            // TRN %1% and %2% are dates with times.
            times = format_wxstr(_L("Started %1% · ended %2% (printer's record)"), when(job.started_ms), when(job.ended_ms));
        else if (job.started_ms > 0)
            times = format_wxstr(_L("Started %1% (printer's record)"), when(job.started_ms));
        else
            times = format_wxstr(_L("Sent %1%"), when(job.created_ms));
        // TRN %1% is a short job id.
        times += wxString::FromUTF8(" · ") + format_wxstr(_L("job %1%"), job.id.substr(0, 8));
        auto *time_line = new wxStaticText(row, wxID_ANY, times);
        time_line->SetForegroundColour(NIATheme::text_muted(dark));
        text->Add(time_line, 0, wxTOP, FromDIP(2));
        line->Add(text, 1, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(10));

        auto *machine_col   = new wxBoxSizer(wxVERTICAL);
        auto *machine_title = new wxStaticText(row, wxID_ANY, _L("Printer reported"));
        machine_title->SetForegroundColour(NIATheme::text_muted(dark));
        auto *machine = new wxStaticText(row, wxID_ANY, machine_state(job.state));
        machine->SetFont(Label::Head_14);
        machine->SetForegroundColour(job.state == U1Jobs::State::Completed ? NIATheme::ready(dark) :
                                     job.state == U1Jobs::State::Failed || job.state == U1Jobs::State::Held ? NIATheme::danger(dark) :
                                                                                                              NIATheme::text(dark));
        machine_col->Add(machine_title);
        machine_col->Add(machine, 0, wxTOP, FromDIP(2));
        line->Add(machine_col, 0, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(10));

        auto *verdict_col   = new wxBoxSizer(wxVERTICAL);
        auto *verdict_title = new wxStaticText(row, wxID_ANY, _L("Your result"));
        verdict_title->SetForegroundColour(NIATheme::text_muted(dark));
        auto *verdict = new ::ComboBox(row, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(130), -1), 0, nullptr, wxCB_READONLY);
        verdict->Append(_L("Not judged"));
        verdict->Append(_L("Success"));
        verdict->Append(_L("Partial"));
        verdict->Append(_L("Failed"));
        int selected = 0;
        for (size_t i = 0; i < VERDICTS.size(); ++i)
            if (job.assessment == VERDICTS[i])
                selected = int(i);
        verdict->SetSelection(selected);
        const std::string id = job.id;
        verdict->Bind(wxEVT_COMBOBOX, [this, verdict, id](wxCommandEvent &event) {
            const int choice = verdict->GetSelection();
            if (choice >= 0 && choice < int(VERDICTS.size()) && !U1Jobs::set_assessment(id, VERDICTS[size_t(choice)]))
                show_error(this, _L("Your result could not be saved."));
            event.Skip();
        });
        verdict_col->Add(verdict_title);
        verdict_col->Add(verdict, 0, wxTOP, FromDIP(2));
        line->Add(verdict_col, 0, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(10));

        row->SetSizer(line);
        m_rows->Add(row, 0, wxEXPAND | wxBOTTOM, FromDIP(8));
    }
    FitInside();
    Layout();
    Thaw();
}

} // namespace Slic3r::GUI
