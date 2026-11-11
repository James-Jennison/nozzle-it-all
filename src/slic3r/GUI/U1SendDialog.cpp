#include "U1SendDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_Utils.hpp"
#include "I18N.hpp"
#include "libslic3r/U1PrintTask.hpp"
#include "MsgDialog.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/CheckBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/StateColor.hpp"
#include "Widgets/TextInput.hpp"

#include "libslic3r/Utils.hpp"

#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>

#include <wx/arrstr.h>
#include <wx/bitmap.h>
#include <wx/brush.h>
#include <wx/colour.h>
#include <wx/cursor.h>
#include <wx/dcbuffer.h>
#include <wx/dcclient.h>
#include <wx/dcgraph.h>
#include <wx/dcmemory.h>
#include <wx/defs.h>
#include <wx/event.h>
#include <wx/font.h>
#include <wx/gdicmn.h>
#include <wx/image.h>
#include <wx/menu.h>
#include <wx/panel.h>
#include <wx/pen.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/string.h>
#include <wx/toplevel.h>
#include <wx/window.h>
#include <wx/wrapsizer.h>

#include <algorithm>
#include <cstddef>
#include <functional>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace Slic3r {
namespace GUI {

namespace {

wxColour border_colour() { return wxColour(0xDB, 0xDB, 0xDB); }
wxColour warning_colour() { return wxColour(0xD0, 0x1B, 0x1B); }
wxColour outline_colour() { return wxColour(0x9A, 0x9A, 0x9A); }

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

wxColour filament_colour(const std::string &color)
{
    const wxColour parsed(wxString::FromUTF8(color));
    return !color.empty() && parsed.IsOk() ? parsed : wxColour(0xB0, 0xB0, 0xB0);
}

// Black or white, whichever reads better on *background*.
wxColour text_on(const wxColour &background)
{
    return background.GetLuminance() > 0.6 ? wxColour("#262E30") : *wxWHITE;
}

// A section of the dialog: a rounded outline with its name on the left and its content on the right.
class Section : public wxPanel
{
public:
    Section(wxWindow *parent, const wxString &name) : wxPanel(parent)
    {
        SetBackgroundColour(parent->GetBackgroundColour());
        Bind(wxEVT_PAINT, &Section::on_paint, this);
        Bind(wxEVT_SIZE, [this](wxSizeEvent &event) { Refresh(); event.Skip(); });
        auto *row  = new wxBoxSizer(wxHORIZONTAL);
        auto *head = new Label(this, Label::Head_14, name, 0, wxSize(FromDIP(150), -1));
        row->Add(head, 0, wxALL, FromDIP(16));
        m_content = new wxBoxSizer(wxVERTICAL);
        row->Add(m_content, 1, wxEXPAND | wxTOP | wxRIGHT | wxBOTTOM, FromDIP(16));
        SetSizer(row);
    }
    wxBoxSizer *content() const { return m_content; }

private:
    void on_paint(wxPaintEvent &)
    {
        wxPaintDC dc(this);
        wxGCDC    gc(dc);
        gc.SetPen(wxPen(StateColor::darkModeColorFor(border_colour()), FromDIP(2)));
        gc.SetBrush(*wxTRANSPARENT_BRUSH);
        const wxSize size = GetClientSize();
        gc.DrawRoundedRectangle(FromDIP(1), FromDIP(1), size.x - FromDIP(2), size.y - FromDIP(2), FromDIP(8));
    }
    wxBoxSizer *m_content = nullptr;
};

} // namespace

// One sliced filament and the toolhead it will print from: the filament's color and type on top, its weight, and
// below that the chosen toolhead's number on the color loaded there. Clicking it lists the toolheads.
class U1FilamentCard : public wxWindow
{
public:
    U1FilamentCard(wxWindow *parent, int number, const U1PrintTask::Filament &filament, double grams,
                   const std::vector<U1PrintTask::Filament> &toolheads, int selection, std::function<void()> on_change)
        : wxWindow(parent, wxID_ANY, wxDefaultPosition, wxDefaultSize, wxWANTS_CHARS)
        , m_number(number), m_filament(filament), m_grams(grams), m_toolheads(toolheads), m_selection(selection)
        , m_on_change(std::move(on_change))
    {
        SetBackgroundStyle(wxBG_STYLE_PAINT);
        SetMinSize(FromDIP(wxSize(100, 126)));
        SetCursor(wxCursor(wxCURSOR_HAND));
        Bind(wxEVT_PAINT, &U1FilamentCard::on_paint, this);
        Bind(wxEVT_LEFT_UP, [this](wxMouseEvent &) { choose(); });
        Bind(wxEVT_KEY_DOWN, [this](wxKeyEvent &event) {
            if (event.GetKeyCode() == WXK_SPACE || event.GetKeyCode() == WXK_RETURN || event.GetKeyCode() == WXK_DOWN)
                choose();
            else
                event.Skip();
        });
        Bind(wxEVT_SET_FOCUS, [this](wxFocusEvent &event) { Refresh(); event.Skip(); });
        Bind(wxEVT_KILL_FOCUS, [this](wxFocusEvent &event) { Refresh(); event.Skip(); });
        update_tooltip();
    }

    bool AcceptsFocus() const override { return true; }
    int  selection() const { return m_selection; }

    // Why the chosen toolhead needs a second look, or empty.
    wxString problem() const
    {
        if (m_selection < 0 || size_t(m_selection) >= m_toolheads.size())
            return {};
        const U1PrintTask::Filament &have = m_toolheads[m_selection];
        if (!have.present)
            return wxString::Format(_L("Filament %d is mapped to toolhead %d, which is empty."), m_number, m_selection + 1);
        if (!m_filament.type.empty() && !have.type.empty() && wxString::FromUTF8(m_filament.type).Upper() != wxString::FromUTF8(have.type).Upper())
            return wxString::Format(_L("Filament %d is %s, but toolhead %d has %s loaded."), m_number, wxString::FromUTF8(m_filament.type),
                                    m_selection + 1, wxString::FromUTF8(have.type));
        return {};
    }

private:
    void choose()
    {
        SetFocus();
        wxMenu menu;
        for (size_t t = 0; t < m_toolheads.size(); ++t)
            // TRN %1$d is a toolhead number, %2$s what is loaded in it ("Snapmaker PLA Basic", "empty").
            menu.AppendRadioItem(int(wxID_HIGHEST + 1 + t), wxString::Format(_L("Toolhead %1$d (%2$s)"), int(t + 1), describe(m_toolheads[t])))
                ->Check(int(t) == m_selection);
        menu.Bind(wxEVT_MENU, [this](wxCommandEvent &event) {
            m_selection = event.GetId() - (wxID_HIGHEST + 1);
            update_tooltip();
            Refresh();
            if (m_on_change)
                m_on_change();
        });
        PopupMenu(&menu, 0, GetClientSize().y);
    }

    void update_tooltip()
    {
        if (m_selection >= 0 && size_t(m_selection) < m_toolheads.size())
            SetToolTip(wxString::Format(_L("Filament %d: %s"), m_number, describe(m_filament)) + "\n" +
                       wxString::Format(_L("Toolhead %1$d (%2$s)"), m_selection + 1, describe(m_toolheads[m_selection])));
    }

    void on_paint(wxPaintEvent &)
    {
        wxAutoBufferedPaintDC dc(this);
        const wxColour        surface = StateColor::darkModeColorFor(*wxWHITE);
        dc.SetBackground(wxBrush(GetParent()->GetBackgroundColour()));
        dc.Clear();
        wxGCDC       gc(dc);
        const wxSize size   = GetClientSize();
        const int    radius = FromDIP(8), band = FromDIP(30), edge = FromDIP(1);
        const bool   warn   = !problem().IsEmpty();
        const wxColour band_colour = filament_colour(m_filament.color);

        // The filament's color fills the rounded top; the rest is the card's surface.
        gc.SetPen(*wxTRANSPARENT_PEN);
        gc.SetBrush(wxBrush(band_colour));
        gc.DrawRoundedRectangle(edge, edge, size.x - 2 * edge, size.y - 2 * edge, radius);
        gc.SetBrush(wxBrush(surface));
        gc.DrawRectangle(edge, band, size.x - 2 * edge, size.y - band - radius);
        gc.DrawRoundedRectangle(edge, size.y - 3 * radius, size.x - 2 * edge, 3 * radius - edge, radius);
        gc.SetBrush(*wxTRANSPARENT_BRUSH);
        gc.SetPen(wxPen(warn ? warning_colour() : StateColor::darkModeColorFor(HasFocus() ? wxColour(0x00, 0x96, 0x88) : outline_colour()),
                        FromDIP(warn || HasFocus() ? 2 : 1)));
        gc.DrawRoundedRectangle(edge, edge, size.x - 2 * edge, size.y - 2 * edge, radius);

        auto centered = [&](const wxString &text, int y, const wxFont &font, const wxColour &colour) {
            gc.SetFont(font);
            gc.SetTextForeground(colour);
            const wxSize extent = gc.GetTextExtent(text);
            gc.DrawText(text, (size.x - extent.x) / 2, y - extent.y / 2);
        };
        const wxColour ink = StateColor::darkModeColorFor(wxColour("#262E30"));
        centered(m_filament.type.empty() ? wxString::Format("%d", m_number) : wxString::FromUTF8(m_filament.type), band / 2 + edge, Label::Head_14,
                 text_on(band_colour));
        if (m_grams > 0.)
            centered(wxString::Format("%.2f g", m_grams), band + FromDIP(16), Label::Body_13, ink);

        // The toolhead: its number on the color loaded there, hollow when the toolhead is empty.
        const bool chosen = m_selection >= 0 && size_t(m_selection) < m_toolheads.size();
        const bool loaded = chosen && m_toolheads[m_selection].present;
        const wxColour chip = loaded ? filament_colour(m_toolheads[m_selection].color) : surface;
        const int      d = FromDIP(32), cx = size.x / 2, cy = band + FromDIP(50);
        gc.SetPen(wxPen(StateColor::darkModeColorFor(outline_colour()), FromDIP(1), loaded ? wxPENSTYLE_SOLID : wxPENSTYLE_SHORT_DASH));
        gc.SetBrush(wxBrush(chip));
        gc.DrawEllipse(cx - d / 2, cy - d / 2, d, d);
        centered(chosen ? wxString::Format("%d", m_selection + 1) : wxString("?"), cy, Label::Head_14, loaded ? text_on(chip) : ink);

        // A chevron shows that the toolhead can be changed.
        const int w = FromDIP(5), y = cy + d / 2 + FromDIP(8);
        gc.SetPen(wxPen(StateColor::darkModeColorFor(outline_colour()), FromDIP(2)));
        gc.DrawLine(cx - w, y, cx, y + w);
        gc.DrawLine(cx, y + w, cx + w, y);
    }

    const int                                m_number;
    const U1PrintTask::Filament              m_filament;
    const double                             m_grams;
    const std::vector<U1PrintTask::Filament> m_toolheads;
    int                                      m_selection;
    std::function<void()>                    m_on_change;
};

U1SendDialog::U1SendDialog(wxWindow                                 *parent,
                           const boost::filesystem::path            &default_name,
                           const std::vector<U1PrintTask::Filament> &plate_filaments,
                           const U1PrintTask::PrinterState          &printer,
                           const wxString                           &project_name,
                           int                                       plate_number,
                           const wxString                           &estimated_time,
                           const wxBitmap                           &thumbnail,
                           const std::vector<double>                &filament_grams,
                           const wxString                           &printer_address)
    : DPIDialog(parent, wxID_ANY, _L("Print on Snapmaker U1"), wxDefaultPosition, wxDefaultSize,
                wxCAPTION | wxCLOSE_BOX)
    , m_plate_filaments(plate_filaments)
    , m_toolheads(printer.toolheads)
{
    SetBackgroundColour(*wxWHITE);
    SetFont(Label::Body_14);

    const int gap = FromDIP(16);
    auto     *top = new wxBoxSizer(wxVERTICAL);
    auto add_section = [&](const wxString &name) {
        auto *section = new Section(this, name);
        top->Add(section, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, gap);
        return section;
    };
    // A picture centered on a rounded gray tile.
    auto picture = [&](wxWindow *owner, wxImage image) -> wxWindow * {
        const wxSize box   = FromDIP(wxSize(120, 100));
        const int    inset = FromDIP(8);
        const double scale = std::min(double(box.x - 2 * inset) / image.GetWidth(), double(box.y - 2 * inset) / image.GetHeight());
        image.Rescale(std::max(1, int(image.GetWidth() * scale)), std::max(1, int(image.GetHeight() * scale)), wxIMAGE_QUALITY_HIGH);
        wxBitmap tile(box);
        {
            wxMemoryDC dc(tile);
            dc.SetBackground(wxBrush(StateColor::darkModeColorFor(*wxWHITE)));
            dc.Clear();
            wxGCDC gc(dc);
            gc.SetPen(*wxTRANSPARENT_PEN);
            gc.SetBrush(wxBrush(StateColor::darkModeColorFor(wxColour(0xF0, 0xF0, 0xF0))));
            gc.DrawRoundedRectangle(0, 0, box.x, box.y, FromDIP(14));
            gc.DrawBitmap(wxBitmap(image), (box.x - image.GetWidth()) / 2, (box.y - image.GetHeight()) / 2, true);
        }
        return new wxStaticBitmap(owner, wxID_ANY, tile);
    };

    // Model information
    Section *model = add_section(_L("Model information"));
    auto    *model_row = new wxBoxSizer(wxHORIZONTAL);
    if (thumbnail.IsOk())
        model_row->Add(picture(model, thumbnail.ConvertToImage()), 0, wxRIGHT | wxALIGN_CENTER_VERTICAL, gap);
    auto           *details           = new wxBoxSizer(wxVERTICAL);
    const wxString  displayed_project = project_name.empty() ? from_path(default_name.stem()) : project_name;
    auto *printer_filename = new Label(model, wxString::Format(_L("File name: %s"), from_path(default_name.filename())));
    details->Add(printer_filename, 0, wxBOTTOM, FromDIP(6));
    // TRN %1$s is a project name, %2$d a plate number.
    details->Add(new Label(model, wxString::Format(_L("Project: %1$s, plate %2$d"), displayed_project, plate_number)), 0, wxBOTTOM, FromDIP(6));
    details->Add(new Label(model, estimated_time.empty() ? _L("Estimated time unavailable") :
                                                           wxString::Format(_L("Estimated time: %s"), estimated_time)),
                 0, wxBOTTOM, FromDIP(6));
    double total_grams = 0.;
    for (size_t i = 0; i < plate_filaments.size() && i < filament_grams.size(); ++i)
        if (plate_filaments[i].present)
            total_grams += filament_grams[i];
    if (total_grams > 0.)
        details->Add(new Label(model, wxString::Format(_L("Estimated material: %.2f g"), total_grams)), 0);
    model_row->Add(details, 1, wxALIGN_CENTER_VERTICAL);
    model->content()->Add(model_row, 0, wxEXPAND);

    // Printer
    Section *device     = add_section(_L("Printer"));
    auto    *device_row = new wxBoxSizer(wxHORIZONTAL);
    const boost::filesystem::path cover = boost::filesystem::path(resources_dir()) / "profiles" / "Snapmaker" / "Snapmaker U1_cover.png";
    wxImage cover_image;
    if (boost::filesystem::exists(cover) && cover_image.LoadFile(from_path(cover), wxBITMAP_TYPE_PNG))
        device_row->Add(picture(device, cover_image), 0, wxRIGHT | wxALIGN_CENTER_VERTICAL, gap);
    auto *device_text = new wxBoxSizer(wxVERTICAL);
    device_text->Add(new Label(device, Label::Head_14, "Snapmaker U1"), 0, wxBOTTOM, FromDIP(6));
    if (!printer_address.empty())
        // TRN %s is the printer's network address.
        device_text->Add(new Label(device, wxString::Format(_L("LAN: %s"), printer_address)), 0, wxBOTTOM, FromDIP(6));
    auto *ready = new Label(device, Label::Body_14, _L("Online • Ready"));
    ready->SetForegroundColour(StateColor::darkModeColorFor(wxColour("#17823B")));
    device_text->Add(ready, 0);
    device_row->Add(device_text, 1, wxALIGN_CENTER_VERTICAL);
    device->content()->Add(device_row, 0, wxEXPAND);

    // Filaments
    Section *mapping = add_section(_L("Filaments"));
    mapping->content()->Add(new Label(mapping, _L("Each filament prints from the toolhead shown under it. Click a filament to change its toolhead."),
                                      LB_AUTO_WRAP, wxSize(FromDIP(520), -1)),
                            0, wxEXPAND | wxBOTTOM, FromDIP(12));
    auto *cards = new wxWrapSizer(wxHORIZONTAL);
    m_mapping_note = new Label(mapping, wxEmptyString, LB_AUTO_WRAP, wxSize(FromDIP(520), -1));
    m_mapping_note->SetForegroundColour(warning_colour());
    const std::vector<int> suggested = U1PrintTask::suggest_mapping(plate_filaments, printer.toolheads);
    for (size_t i = 0; i < plate_filaments.size(); ++i) {
        const U1PrintTask::Filament &f = plate_filaments[i];
        if (!f.present)
            continue;
        auto *card = new U1FilamentCard(mapping, int(i + 1), f, i < filament_grams.size() ? filament_grams[i] : 0., printer.toolheads,
                                        suggested[i] >= 0 ? suggested[i] : 0, [this] { update_mapping_note(); });
        cards->Add(card, 0, wxRIGHT | wxBOTTOM, FromDIP(14));
        m_rows.push_back({i, card});
    }
    mapping->content()->Add(cards, 0, wxEXPAND);
    mapping->content()->Add(m_mapping_note, 0, wxEXPAND);

    // Print preparation
    Section *preparation  = add_section(_L("Print preparation"));
    auto    *options_grid = new wxFlexGridSizer(2, FromDIP(8), FromDIP(10));
    options_grid->AddGrowableCol(1);
    auto add_check = [&](const wxString &label, const wxString &help, bool value) {
        auto *check = new ::CheckBox(preparation);
        check->SetValue(value);
        options_grid->Add(check, 0, wxALIGN_TOP);
        auto *copy = new wxBoxSizer(wxVERTICAL);
        copy->Add(new Label(preparation, Label::Head_14, label));
        copy->Add(new Label(preparation, help, LB_AUTO_WRAP, wxSize(FromDIP(480), -1)), 0, wxTOP, FromDIP(2));
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
    preparation->content()->Add(options_grid, 0, wxEXPAND);

    auto *advanced_button = new Button(this, _L("Advanced"), "", 0, 0);
    advanced_button->SetStyle(ButtonStyle::Regular, ButtonType::Choice);
    top->Add(advanced_button, 0, wxLEFT | wxRIGHT | wxTOP, gap);
    auto *advanced_panel = new wxPanel(this);
    advanced_panel->SetBackgroundColour(*wxWHITE);
    auto *advanced_sizer = new wxBoxSizer(wxVERTICAL);
    advanced_sizer->Add(new Label(advanced_panel, _L("File name on the printer")), 0, wxBOTTOM, FromDIP(6));
    m_filename = new ::TextInput(advanced_panel, from_path(default_name.filename()), wxEmptyString, wxEmptyString,
                                 wxDefaultPosition, wxSize(FromDIP(620), -1), wxTE_PROCESS_ENTER);
    m_filename->GetTextCtrl()->Bind(wxEVT_TEXT, [this, printer_filename](wxCommandEvent &event) {
        printer_filename->SetLabel(wxString::Format(_L("File name: %s"), event.GetString()));
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

    update_mapping_note();
    SetSizerAndFit(top);
    CenterOnParent();
    wxGetApp().UpdateDlgDarkUI(this);
}

void U1SendDialog::update_mapping_note()
{
    wxString note;
    for (const Row &row : m_rows)
        if (const wxString problem = row.card->problem(); !problem.IsEmpty())
            note += (note.IsEmpty() ? wxString() : "\n") + problem;
    const bool resize = note.IsEmpty() != m_mapping_note->GetLabel().IsEmpty() || note.Freq('\n') != m_mapping_note->GetLabel().Freq('\n');
    m_mapping_note->SetLabel(note);
    m_mapping_note->Show(!note.IsEmpty());
    if (resize && GetSizer() != nullptr) {
        GetSizer()->SetSizeHints(this);
        Layout();
    }
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
        const int toolhead                         = row.card->selection();
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
        const int    toolhead = row.card->selection();
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
        row.card->Refresh();
    for (::CheckBox *check : {m_bed_level, m_flow_calibrate, m_shaper_calibrate, m_time_lapse})
        check->Rescale();
    GetSizer()->SetSizeHints(this);
    Refresh();
}

} // namespace GUI
} // namespace Slic3r
