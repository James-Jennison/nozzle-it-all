#include "U1SendDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_Utils.hpp"
#include "I18N.hpp"
#include "libslic3r/U1PrintTask.hpp"
#include "MsgDialog.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/CheckBox.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/StateColor.hpp"
#include "Widgets/TextInput.hpp"

#include <wx/arrstr.h>
#include <wx/bitmap.h>
#include <wx/dcmemory.h>
#include <wx/defs.h>
#include <wx/event.h>
#include <wx/gdicmn.h>
#include <wx/image.h>
#include <wx/panel.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/string.h>
#include <wx/toplevel.h>

#include <cstddef>
#include <set>
#include <string>
#include <vector>

namespace Slic3r {
namespace GUI {

namespace {

wxBitmap swatch(wxWindow *window, const std::string &color, int size)
{
    wxBitmap   bmp;
    bmp.CreateWithDIPSize(wxSize(size, size), window->GetDPIScaleFactor());
    wxMemoryDC dc(bmp);
    wxColour   fill(color.empty() ? wxString("#808080") : wxString(color));
    dc.SetBackground(wxBrush(fill));
    dc.Clear();
    dc.SetPen(wxPen(wxColour(128, 128, 128)));
    dc.SetBrush(*wxTRANSPARENT_BRUSH);
    dc.DrawRectangle(0, 0, size, size);
    dc.SelectObject(wxNullBitmap);
    return bmp;
}

wxString describe(const U1PrintTask::Filament &f)
{
    if (!f.present)
        return _L("empty");
    if (!f.name.empty())
        return wxString::FromUTF8(f.name);
    wxString text;
    for (const std::string *part : {&f.vendor, &f.type, &f.sub_type})
        if (!part->empty())
            text += (text.empty() ? wxString() : " ") + wxString::FromUTF8(*part);
    if (text.empty())
        text = _L("unknown");
    return text;
}

Label *section_label(wxWindow *parent, const wxString &text)
{
    auto *label = new Label(parent, Label::Head_14, text);
    label->SetForegroundColour(StateColor::darkModeColorFor(wxColour("#24292F")));
    return label;
}

} // namespace

U1SendDialog::U1SendDialog(wxWindow                                 *parent,
                           const boost::filesystem::path            &default_name,
                           const std::vector<U1PrintTask::Filament> &plate_filaments,
                           const U1PrintTask::PrinterState          &printer,
                           const wxString                           &project_name,
                           int                                       plate_number,
                           const wxString                           &estimated_time,
                           const wxBitmap                           &thumbnail)
    : DPIDialog(parent, wxID_ANY, _L("Print on Snapmaker U1"), wxDefaultPosition, wxDefaultSize,
                wxCAPTION | wxCLOSE_BOX)
    , m_plate_filaments(plate_filaments)
    , m_toolheads(printer.toolheads)
{
    SetBackgroundColour(*wxWHITE);
    SetFont(Label::Body_14);

    const int gap = FromDIP(16);
    auto     *top = new wxBoxSizer(wxVERTICAL);

    auto *heading = new wxBoxSizer(wxHORIZONTAL);
    auto *title   = new Label(this, Label::Head_20, _L("Print on Snapmaker U1"));
    heading->Add(title, 1, wxALIGN_CENTER_VERTICAL);
    auto *ready = new Label(this, Label::Body_14, _L("Online • Ready"));
    ready->SetForegroundColour(StateColor::darkModeColorFor(wxColour("#17823B")));
    heading->Add(ready, 0, wxALIGN_CENTER_VERTICAL);
    top->Add(heading, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, gap);

    auto *summary = new wxBoxSizer(wxHORIZONTAL);
    if (thumbnail.IsOk()) {
        wxImage image = thumbnail.ConvertToImage();
        image.Rescale(FromDIP(132), FromDIP(100), wxIMAGE_QUALITY_HIGH);
        summary->Add(new wxStaticBitmap(this, wxID_ANY, wxBitmap(image)), 0, wxRIGHT, gap);
    }
    auto           *details           = new wxBoxSizer(wxVERTICAL);
    const wxString  displayed_project = project_name.empty() ? from_path(default_name.stem()) : project_name;
    details->Add(new Label(this, Label::Head_16, wxString::Format(_L("Project: %s"), displayed_project)), 0,
                 wxBOTTOM, FromDIP(6));
    details->Add(new Label(this, wxString::Format(_L("Plate %d"), plate_number)), 0, wxBOTTOM, FromDIP(4));
    auto *printer_filename = new Label(this, wxString::Format(_L("Printer file: %s"), from_path(default_name.filename())));
    details->Add(printer_filename, 0, wxBOTTOM, FromDIP(4));
    details->Add(
        new Label(this, estimated_time.empty() ? _L("Estimated duration unavailable") :
                                                 wxString::Format(_L("Estimated normal-mode duration: %s"), estimated_time)),
        0);
    summary->Add(details, 1, wxALIGN_CENTER_VERTICAL);
    top->Add(summary, 0, wxEXPAND | wxALL, gap);

    top->Add(section_label(this, _L("Filament and toolhead mapping")), 0, wxLEFT | wxRIGHT | wxTOP, gap);
    top->Add(new Label(this, _L("Confirm that each sliced filament is mapped to the loaded material you intend to use."),
                       LB_AUTO_WRAP, wxSize(FromDIP(620), -1)),
             0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, gap);
    auto *grid = new wxFlexGridSizer(3, FromDIP(10), FromDIP(12));
    grid->AddGrowableCol(2);

    wxArrayString toolhead_labels;
    for (size_t t = 0; t < printer.toolheads.size(); ++t)
        toolhead_labels.Add(wxString::Format(_L("Toolhead %d"), int(t + 1)) + " (" + describe(printer.toolheads[t]) + ")");

    const std::vector<int> suggested = U1PrintTask::suggest_mapping(plate_filaments, printer.toolheads);
    for (size_t i = 0; i < plate_filaments.size(); ++i) {
        const U1PrintTask::Filament &f = plate_filaments[i];
        if (!f.present)
            continue;
        grid->Add(new wxStaticBitmap(this, wxID_ANY, swatch(this, f.color, 28)), 0, wxALIGN_CENTER_VERTICAL);
        grid->Add(new Label(this, wxString::Format(_L("Filament %d"), int(i + 1)) + ": " + describe(f)), 0,
                  wxALIGN_CENTER_VERTICAL);
        auto *choice = new ::ComboBox(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(290), -1), 0,
                                      nullptr, wxCB_READONLY);
        for (const wxString &label : toolhead_labels)
            choice->Append(label);
        choice->SetSelection(suggested[i] >= 0 ? suggested[i] : 0);
        grid->Add(choice, 0, wxEXPAND);
        m_rows.push_back({i, choice});
    }
    top->Add(grid, 0, wxEXPAND | wxALL, gap);

    top->Add(section_label(this, _L("Print preparation")), 0, wxLEFT | wxRIGHT | wxTOP, gap);
    auto *options_grid = new wxFlexGridSizer(2, FromDIP(8), FromDIP(10));
    options_grid->AddGrowableCol(1);
    auto add_check = [&](const wxString &label, const wxString &help, bool value) {
        auto *check = new ::CheckBox(this);
        check->SetValue(value);
        options_grid->Add(check, 0, wxALIGN_TOP);
        auto *copy = new wxBoxSizer(wxVERTICAL);
        copy->Add(new Label(this, Label::Head_14, label));
        copy->Add(new Label(this, help, LB_AUTO_WRAP, wxSize(FromDIP(570), -1)), 0, wxTOP, FromDIP(2));
        options_grid->Add(copy, 1, wxEXPAND);
        return check;
    };
    m_bed_level = add_check(_L("Bed leveling"), _L("Measure the build plate before this print."), printer.bed_level);
    m_flow_calibrate = add_check(_L("Flow calibration"), _L("Calibrate only the toolheads used by this print."),
                                 printer.flow_calibrate);
    m_shaper_calibrate = add_check(_L("Input shaper calibration"), _L("Measure vibration compensation before printing."),
                                   printer.shaper_calibrate);
    m_time_lapse = add_check(_L("Timelapse"), _L("Capture a timelapse with the printer camera."),
                             printer.time_lapse_camera);
    top->Add(options_grid, 0, wxEXPAND | wxALL, gap);

    auto *advanced_button = new Button(this, _L("Advanced"), "", 0, 0);
    advanced_button->SetStyle(ButtonStyle::Regular, ButtonType::Choice);
    top->Add(advanced_button, 0, wxLEFT | wxRIGHT, gap);
    auto *advanced_panel = new wxPanel(this);
    advanced_panel->SetBackgroundColour(*wxWHITE);
    auto *advanced_sizer = new wxBoxSizer(wxVERTICAL);
    advanced_sizer->Add(new Label(advanced_panel, _L("File name on the printer")), 0, wxBOTTOM, FromDIP(6));
    m_filename = new ::TextInput(advanced_panel, from_path(default_name.filename()), wxEmptyString, wxEmptyString,
                                 wxDefaultPosition, wxSize(FromDIP(620), -1), wxTE_PROCESS_ENTER);
    m_filename->GetTextCtrl()->Bind(wxEVT_TEXT, [this, printer_filename](wxCommandEvent &event) {
        printer_filename->SetLabel(wxString::Format(_L("Printer file: %s"), event.GetString()));
        Layout();
        event.Skip();
    });
    advanced_sizer->Add(m_filename, 0, wxEXPAND);
    advanced_panel->SetSizer(advanced_sizer);
    advanced_panel->Hide();
    top->Add(advanced_panel, 0, wxEXPAND | wxALL, gap);
    advanced_button->Bind(wxEVT_BUTTON, [this, advanced_panel](wxCommandEvent &) {
        advanced_panel->Show(!advanced_panel->IsShown());
        GetSizer()->SetSizeHints(this);
        Layout();
    });

    auto *buttons = new DialogButtons(this, {"Cancel", "Upload without starting", "Start Print"}, _L("Start Print"));
    auto *cancel = buttons->GetCANCEL();
    auto *upload = buttons->GetButtonFromIndex(1);
    auto *print  = buttons->GetButtonFromIndex(2);
    top->Add(buttons, 0, wxEXPAND);

    cancel->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
    upload->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_confirm(false); });
    print->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_confirm(true); });

    SetSizerAndFit(top);
    CenterOnParent();
    wxGetApp().UpdateDlgDarkUI(this);
}

boost::filesystem::path U1SendDialog::filename() const
{
    return boost::filesystem::path(into_u8(m_filename->GetTextCtrl()->GetValue()));
}

U1PrintTask::Options U1SendDialog::options() const
{
    U1PrintTask::Options options;
    options.filament_to_toolhead.assign(m_plate_filaments.size(), U1PrintTask::UNMAPPED);
    std::set<int> used_toolheads;
    for (const Row &row : m_rows) {
        const int toolhead                         = row.toolhead->GetSelection();
        options.filament_to_toolhead[row.filament] = toolhead;
        used_toolheads.insert(toolhead);
    }
    options.bed_level         = m_bed_level->GetValue();
    options.flow_calibrate    = m_flow_calibrate->GetValue();
    options.shaper_calibrate  = m_shaper_calibrate->GetValue();
    options.time_lapse_camera = m_time_lapse->GetValue();
    if (*options.flow_calibrate)
        options.flow_calibrate_toolheads.assign(used_toolheads.begin(), used_toolheads.end());
    return options;
}

void U1SendDialog::on_confirm(bool start_print)
{
    static const wxString forbidden = "#*;\\/:\"<>|";
    const wxString name = m_filename->GetTextCtrl()->GetValue().Strip(wxString::both);
    if (name.IsEmpty() || name.find_first_of(forbidden) != wxString::npos) {
        MessageDialog(this, _L("Please enter a file name without the characters #, *, ;, \\, /, :, \", <, >, or |."),
                      _L("Send to Snapmaker U1"), wxOK | wxICON_WARNING)
            .ShowModal();
        return;
    }
    if (!name.Lower().EndsWith(".gcode"))
        m_filename->GetTextCtrl()->ChangeValue(name + ".gcode");

    std::string error;
    if (!U1PrintTask::validate(options(), error)) {
        MessageDialog(this, wxString::FromUTF8(error), _L("Send to Snapmaker U1"), wxOK | wxICON_WARNING).ShowModal();
        return;
    }
    // Mapping onto a different material is allowed (it can be deliberate),
    // but the G-code keeps the sliced filament's temperatures and speeds.
    wxString mismatches;
    for (const Row &row : m_rows) {
        const auto  &want     = m_plate_filaments[row.filament];
        const int    toolhead = row.toolhead->GetSelection();
        if (toolhead < 0 || size_t(toolhead) >= m_toolheads.size())
            continue;
        const auto &have = m_toolheads[toolhead];
        if (have.present && !want.type.empty() && !have.type.empty() && wxString::FromUTF8(want.type).Upper() != wxString::FromUTF8(have.type))
            mismatches += "\n" + wxString::Format(_L("Filament %d is %s, but toolhead %d has %s loaded."), int(row.filament + 1),
                                                   wxString::FromUTF8(want.type), toolhead + 1, wxString::FromUTF8(have.type));
    }
    if (!mismatches.IsEmpty() &&
        MessageDialog(this, _L("The filament types do not match:") + "\n" + mismatches + "\n\n" +
                                _L("The print will use the temperatures and speeds sliced for the original filament. Continue anyway?"),
                      _L("Send to Snapmaker U1"), wxYES_NO | wxNO_DEFAULT | wxICON_WARNING)
                .ShowModal() != wxID_YES)
        return;

    m_start_print = start_print;
    EndModal(wxID_OK);
}

void U1SendDialog::on_dpi_changed(const wxRect & /*suggested_rect*/)
{
    m_filename->Rescale();
    for (const Row &row : m_rows)
        row.toolhead->Rescale();
    for (::CheckBox *check : {m_bed_level, m_flow_calibrate, m_shaper_calibrate, m_time_lapse})
        check->Rescale();
    GetSizer()->SetSizeHints(this);
    Refresh();
}

} // namespace GUI
} // namespace Slic3r
