#include "U1SendDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_Utils.hpp"
#include "I18N.hpp"
#include "libslic3r/U1PrintTask.hpp"
#include "MsgDialog.hpp"
#include "NIATheme.hpp"
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
#include <wx/datetime.h>
#include <wx/control.h>
#include <wx/dcmemory.h>
#include <wx/display.h>
#include <wx/defs.h>
#include <wx/event.h>
#include <wx/font.h>
#include <wx/gdicmn.h>
#include <wx/image.h>
#include <wx/menu.h>
#include <wx/panel.h>
#include <wx/pen.h>
#include <wx/scrolwin.h>
#include <wx/settings.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/stattext.h>
#include <wx/string.h>
#include <wx/textctrl.h>
#include <wx/toplevel.h>
#include <wx/utils.h>
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

// The dialog's colours for the theme in use. The Nozzle It All Desktop build takes them from the NIA theme:
// that build switches off Orca's automatic recolouring, so a screen that does not set its own colours stays
// light in a dark app. The Orca build keeps Orca's colours and its recolouring.
struct Theme
{
    wxColour window;  ///< behind the sections
    wxColour surface; ///< sections and cards
    wxColour tile;    ///< behind a picture
    wxColour line;    ///< a section's outline
    wxColour outline; ///< a card's outline, the chevron
    wxColour text;
    wxColour muted;
    wxColour ready;
    wxColour danger;
    wxColour focus;
};

Theme theme()
{
    const bool dark = wxGetApp().dark_mode();
#if NOZZLE_BRANDING
    return {NIATheme::background(dark), NIATheme::surface(dark), NIATheme::surface_raised(dark), NIATheme::line(dark), NIATheme::line_strong(dark),
            NIATheme::text(dark),       NIATheme::text_muted(dark), NIATheme::ready(dark),      NIATheme::danger(dark), NIATheme::focus(dark)};
#else
    const auto orca = [](unsigned char r, unsigned char g, unsigned char b) { return StateColor::darkModeColorFor(wxColour(r, g, b)); };
    return {orca(0xFF, 0xFF, 0xFF), orca(0xFF, 0xFF, 0xFF), orca(0xF1, 0xF1, 0xF1), orca(0xDB, 0xDB, 0xDB), wxColour(0x9A, 0x9A, 0x9A),
            orca(0x26, 0x2E, 0x30), wxColour(0x6B, 0x6B, 0x6B), dark ? wxColour(0x4C, 0xC9, 0x6E) : wxColour(0x17, 0x82, 0x3B),
            orca(0xD0, 0x1B, 0x1B), wxColour(0x00, 0x96, 0x88)};
#endif
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

wxColour filament_colour(const std::string &color)
{
    const wxColour parsed(wxString::FromUTF8(color));
    return !color.empty() && parsed.IsOk() ? parsed : wxColour(0xB0, 0xB0, 0xB0);
}

// A file name the printer can take: characters its file system or Moonraker's API refuse are replaced, and it
// ends in .gcode. Empty when nothing usable is left.
wxString printer_safe_name(wxString name)
{
    static const wxString forbidden = "#*;\\/:\"<>|";
    for (wxUniCharRef c : name)
        if (forbidden.find(c) != wxString::npos || wxUniChar(c).GetValue() < 0x20 || wxUniChar(c).GetValue() == 0x7F)
            c = '_';
    name.Trim(true).Trim(false);
    // No hidden or relative names, and nothing longer than a printer's file system takes with the job's
    // identifier added to it.
    while (name.StartsWith("."))
        name.erase(0, 1);
    if (name.length() > 180)
        name = name.Left(174) + ".gcode";
    if (name.IsEmpty() || name.Lower() == "gcode")
        return {};
    if (!name.Lower().EndsWith(".gcode"))
        name += ".gcode";
    return name;
}

// Black or white, whichever reads better on *background*.
wxColour text_on(const wxColour &background)
{
    return background.GetLuminance() > 0.6 ? wxColour("#262E30") : *wxWHITE;
}

// A section of the dialog: a rounded outline with its name on top and its content below.
class Section : public wxPanel
{
public:
    Section(wxWindow *parent, const wxString &name) : wxPanel(parent)
    {
        SetBackgroundStyle(wxBG_STYLE_PAINT);
        SetBackgroundColour(theme().surface); // what the labels inside it are drawn on
        Bind(wxEVT_PAINT, &Section::on_paint, this);
        Bind(wxEVT_SIZE, [this](wxSizeEvent &event) { Refresh(); event.Skip(); });
        const int pad = FromDIP(12);
        auto     *box = new wxBoxSizer(wxVERTICAL);
        m_header      = new wxBoxSizer(wxHORIZONTAL);
        m_header->Add(new Label(this, Label::Head_14, name), 0, wxALIGN_CENTER_VERTICAL);
        box->Add(m_header, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, pad);
        m_content = new wxBoxSizer(wxVERTICAL);
        box->Add(m_content, 1, wxEXPAND | wxALL, pad);
        SetSizer(box);
    }
    wxBoxSizer *content() const { return m_content; }
    /// The row holding the section's name; more can be added to its right.
    wxBoxSizer *header() const { return m_header; }

private:
    void on_paint(wxPaintEvent &)
    {
        wxAutoBufferedPaintDC dc(this);
        const Theme           colours = theme();
        dc.SetBackground(wxBrush(colours.window));
        dc.Clear();
        wxGCDC gc(dc);
        gc.SetPen(wxPen(colours.line, FromDIP(2)));
        gc.SetBrush(wxBrush(colours.surface));
        const wxSize size = GetClientSize();
        gc.DrawRoundedRectangle(FromDIP(1), FromDIP(1), size.x - FromDIP(2), size.y - FromDIP(2), FromDIP(8));
    }
    wxBoxSizer *m_header  = nullptr;
    wxBoxSizer *m_content = nullptr;
};

} // namespace

// One sliced filament and the toolhead it will print from: the filament's color and type on top, its weight, and
// below that the chosen toolhead's number on the color loaded there. Clicking it lists the toolheads.
class U1FilamentCard : public wxWindow
{
public:
    static constexpr int CARD_WIDTH = 104, CARD_HEIGHT = 118;
    static inline const wxSize CARD_SIZE{CARD_WIDTH, CARD_HEIGHT};

    U1FilamentCard(wxWindow *parent, int number, const U1PrintTask::Filament &filament, double grams,
                   const std::vector<U1PrintTask::Filament> &toolheads, int selection, std::function<void()> on_change)
        : wxWindow(parent, wxID_ANY, wxDefaultPosition, wxDefaultSize, wxWANTS_CHARS)
        , m_number(number), m_filament(filament), m_grams(grams), m_toolheads(toolheads), m_selection(selection)
        , m_on_change(std::move(on_change))
    {
        SetBackgroundStyle(wxBG_STYLE_PAINT);
        SetMinSize(FromDIP(CARD_SIZE));
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
        // The choice comes back from the call itself, so nothing outlives the menu.
        const int chosen = GetPopupMenuSelectionFromUser(menu, 0, GetClientSize().y) - (wxID_HIGHEST + 1);
        if (chosen < 0 || size_t(chosen) >= m_toolheads.size() || chosen == m_selection)
            return;
        m_selection = chosen;
        update_tooltip();
        Refresh();
        if (m_on_change)
            m_on_change();
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
        const Theme           colours = theme();
        const wxColour        surface = colours.surface;
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
        gc.SetPen(wxPen(warn ? colours.danger : HasFocus() ? colours.focus : colours.outline,
                        FromDIP(warn || HasFocus() ? 2 : 1)));
        gc.DrawRoundedRectangle(edge, edge, size.x - 2 * edge, size.y - 2 * edge, radius);

        auto centered = [&](const wxString &text, int y, const wxFont &font, const wxColour &colour) {
            gc.SetFont(font);
            gc.SetTextForeground(colour);
            const wxSize extent = gc.GetTextExtent(text);
            gc.DrawText(text, (size.x - extent.x) / 2, y - extent.y / 2);
        };
        const wxColour ink = colours.text;
        // TRN %1$d is a filament's number, %2$s its material, e.g. "1 · PLA".
        centered(m_filament.type.empty() ? wxString::Format("%d", m_number) : wxString::Format(_L("%1$d · %2$s"), m_number, wxString::FromUTF8(m_filament.type)),
                 band / 2 + edge, Label::Head_14, text_on(band_colour));
        if (m_grams > 0.)
            centered(wxString::Format("%.2f g", m_grams), band + FromDIP(13), Label::Body_13, ink);

        // The toolhead: its number on the color loaded there, hollow when the toolhead is empty.
        const bool chosen = m_selection >= 0 && size_t(m_selection) < m_toolheads.size();
        const bool loaded = chosen && m_toolheads[m_selection].present;
        const wxColour chip = loaded ? filament_colour(m_toolheads[m_selection].color) : surface;
        const int      d = FromDIP(28), cx = size.x / 2, cy = band + FromDIP(42);
        gc.SetPen(wxPen(colours.outline, FromDIP(1), loaded ? wxPENSTYLE_SOLID : wxPENSTYLE_SHORT_DASH));
        gc.SetBrush(wxBrush(chip));
        gc.DrawEllipse(cx - d / 2, cy - d / 2, d, d);
        centered(chosen ? wxString::Format("%d", m_selection + 1) : wxString("?"), cy, Label::Head_14, loaded ? text_on(chip) : ink);

        // A chevron beside the chip shows that the toolhead can be changed.
        const int w = FromDIP(4), x = cx + d / 2 + FromDIP(12), y = cy - w / 2;
        gc.SetPen(wxPen(colours.outline, FromDIP(2)));
        gc.DrawLine(x - w, y, x, y + w);
        gc.DrawLine(x, y + w, x + w, y);

        // What is loaded there, in words, so a different material is seen without hovering.
        if (chosen) {
            const U1PrintTask::Filament &have = m_toolheads[m_selection];
            wxString what = !have.present ? _L("empty") : wxString::FromUTF8(have.type + (have.sub_type.empty() ? "" : " " + have.sub_type));
            if (what.IsEmpty())
                what = _L("unknown");
            gc.SetFont(Label::Body_12);
            what = wxControl::Ellipsize(what, gc, wxELLIPSIZE_END, size.x - FromDIP(10));
            centered(what, cy + d / 2 + FromDIP(13), Label::Body_12, warn ? colours.danger : ink);
        }
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
                           const wxString                           &printer_address,
                           std::function<ReadinessRead()>            read_readiness)
    : DPIDialog(parent, wxID_ANY, _L("Print on Snapmaker U1"), wxDefaultPosition, wxDefaultSize,
                wxCAPTION | wxCLOSE_BOX)
    , m_plate_filaments(plate_filaments)
    , m_toolheads(printer.toolheads)
    , m_read_readiness(std::move(read_readiness))
    , m_name(printer_safe_name(from_path(default_name.filename())))
{
    if (m_name.IsEmpty())
        m_name = "print.gcode";
    const Theme colours = theme();
    SetBackgroundColour(colours.window);
    SetFont(Label::Body_14);

    const int gap = FromDIP(10);
    auto     *top = new wxBoxSizer(wxVERTICAL);
    // Everything but the buttons scrolls, so they stay reachable on a screen too small for the whole dialog.
    m_scroll = new wxScrolledWindow(this, wxID_ANY, wxDefaultPosition, wxDefaultSize, wxVSCROLL);
    m_scroll->SetBackgroundColour(colours.window);
    m_scroll->SetScrollRate(0, FromDIP(12));
    auto *sections = new wxBoxSizer(wxVERTICAL);

    // A picture centered on a rounded gray tile.
    auto picture = [&](wxWindow *owner, wxImage image) -> wxWindow * {
        if (!image.IsOk() || image.GetWidth() <= 0 || image.GetHeight() <= 0)
            return nullptr;
        const wxSize box   = FromDIP(wxSize(84, 70));
        const int    inset = FromDIP(6);
        const double scale = std::min(double(box.x - 2 * inset) / image.GetWidth(), double(box.y - 2 * inset) / image.GetHeight());
        image.Rescale(std::max(1, int(image.GetWidth() * scale)), std::max(1, int(image.GetHeight() * scale)), wxIMAGE_QUALITY_HIGH);
        wxBitmap tile(box);
        {
            wxMemoryDC dc(tile);
            dc.SetBackground(wxBrush(colours.surface));
            dc.Clear();
            wxGCDC gc(dc);
            gc.SetPen(*wxTRANSPARENT_PEN);
            gc.SetBrush(wxBrush(colours.tile));
            gc.DrawRoundedRectangle(0, 0, box.x, box.y, FromDIP(10));
            gc.DrawBitmap(wxBitmap(image), (box.x - image.GetWidth()) / 2, (box.y - image.GetHeight()) / 2, true);
        }
        return new wxStaticBitmap(owner, wxID_ANY, tile);
    };
    // One line of text that is cut with an ellipsis when it is longer than *width*; the whole text is in the tooltip.
    auto line = [&](wxWindow *owner, const wxString &text, int width, long ellipsize) {
        auto *label = new Label(owner, text, ellipsize, wxSize(FromDIP(width), -1));
        label->SetToolTip(text);
        return label;
    };

    // Model information and the printer, side by side
    auto *first_row = new wxBoxSizer(wxHORIZONTAL);
    auto *model     = new Section(m_scroll, _L("Model information"));
    auto *model_row = new wxBoxSizer(wxHORIZONTAL);
    if (thumbnail.IsOk())
        if (wxWindow *tile = picture(model, thumbnail.ConvertToImage()))
            model_row->Add(tile, 0, wxRIGHT | wxALIGN_CENTER_VERTICAL, gap);
    auto           *details           = new wxBoxSizer(wxVERTICAL);
    // The project's own name; its folder would only widen the dialog, and is in the tooltip.
    const wxString  displayed_project = project_name.empty() ? from_path(default_name.stem()) : project_name.AfterLast('/').AfterLast('\\');
    // TRN %1$s is a project name, %2$d a plate number.
    details->Add(line(model, wxString::Format(_L("Project: %1$s, plate %2$d"), displayed_project, plate_number), 180, wxST_ELLIPSIZE_END), 0,
                 wxEXPAND | wxBOTTOM, FromDIP(3));
    double total_grams = 0.;
    for (size_t i = 0; i < plate_filaments.size() && i < filament_grams.size(); ++i)
        if (plate_filaments[i].present)
            total_grams += filament_grams[i];
    // Time and material share a line, which leaves the file name a line of its own across the section.
    wxString estimate;
    if (!estimated_time.empty() && total_grams > 0.)
        // TRN %1$s is a print duration such as "2h 44m 2s", %2$.2f a weight in grams.
        estimate = wxString::Format(_L("Estimated: %1$s, %2$.2f g"), estimated_time, total_grams);
    else if (!estimated_time.empty())
        estimate = wxString::Format(_L("Estimated time: %s"), estimated_time);
    else if (total_grams > 0.)
        estimate = wxString::Format(_L("Estimated material: %.2f g"), total_grams);
    else
        estimate = _L("Estimated time unavailable");
    details->Add(new Label(model, estimate), 0);
    model_row->Add(details, 1, wxALIGN_CENTER_VERTICAL);
    // The file name spans the section, so it is shortened only when the whole section is too narrow for it.
    // A click on it swaps in a text field holding the same name.
    auto *name_row = new wxBoxSizer(wxHORIZONTAL);
    name_row->Add(new Label(model, _L("File name:")), 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, FromDIP(6));
    m_name_label = new Label(model, m_name, wxST_ELLIPSIZE_MIDDLE, wxSize(FromDIP(120), -1));
    m_name_label->SetCursor(wxCursor(wxCURSOR_IBEAM));
    m_name_label->SetToolTip(m_name + "\n" + _L("Click to change the name. On the printer the job's identifier is added to it."));
    m_name_label->Bind(wxEVT_LEFT_UP, [this](wxMouseEvent &) { begin_name_edit(); });
    name_row->Add(m_name_label, 1, wxALIGN_CENTER_VERTICAL);
    m_name_edit = new ::TextInput(model, m_name, wxEmptyString, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(120), -1), wxTE_PROCESS_ENTER);
    m_name_edit->Hide();
    // Enter keeps the new name. It must not reach the dialog's default button, which is Start Print.
    m_name_edit->GetTextCtrl()->Bind(wxEVT_TEXT_ENTER, [this](wxCommandEvent &) { end_name_edit(true); });
    m_name_edit->GetTextCtrl()->Bind(wxEVT_KILL_FOCUS, [this](wxFocusEvent &event) {
        end_name_edit(true);
        event.Skip();
    });
    name_row->Add(m_name_edit, 1, wxALIGN_CENTER_VERTICAL);
    model->content()->Add(name_row, 0, wxEXPAND | wxBOTTOM, FromDIP(6));
    // Escape leaves the field with the old name instead of closing the dialog.
    Bind(wxEVT_CHAR_HOOK, [this](wxKeyEvent &event) {
        if (event.GetKeyCode() == WXK_ESCAPE && m_name_edit->IsShown())
            end_name_edit(false);
        else
            event.Skip();
    });
    model->content()->Add(model_row, 0, wxEXPAND);
    first_row->Add(model, 1, wxEXPAND | wxRIGHT, gap);

    auto *device     = new Section(m_scroll, _L("Printer"));
    auto *device_row = new wxBoxSizer(wxHORIZONTAL);
    const boost::filesystem::path cover = boost::filesystem::path(resources_dir()) / "profiles" / "Snapmaker" / "Snapmaker U1_cover.png";
    wxImage cover_image;
    if (boost::filesystem::exists(cover) && cover_image.LoadFile(from_path(cover), wxBITMAP_TYPE_PNG))
        if (wxWindow *tile = picture(device, cover_image))
            device_row->Add(tile, 0, wxRIGHT | wxALIGN_CENTER_VERTICAL, gap);
    auto *device_text = new wxBoxSizer(wxVERTICAL);
    // TRN %1$s is the printer's model, %2$s its network address.
    device_text->Add(new Label(device, printer_address.empty() ? wxString("Snapmaker U1") : wxString::Format(_L("%1$s at %2$s"), "Snapmaker U1", printer_address)),
                     0, wxBOTTOM, FromDIP(3));
    m_status = new Label(device, wxEmptyString, LB_AUTO_WRAP, wxSize(FromDIP(250), -1));
    device_text->Add(m_status, 0, wxEXPAND);
    device_row->Add(device_text, 1, wxALIGN_CENTER_VERTICAL);
    device->content()->Add(device_row, 0, wxEXPAND);
    if (m_read_readiness) {
        auto *again = new Button(device, _L("Check again"), "", 0, 0);
        again->SetStyle(ButtonStyle::Regular, ButtonType::Compact);
        again->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) {
            refresh_readiness();
            update_state();
        });
        device->header()->AddStretchSpacer(1);
        device->header()->Add(again, 0, wxALIGN_CENTER_VERTICAL);
    }
    first_row->Add(device, 1, wxEXPAND);
    sections->Add(first_row, 0, wxEXPAND | wxBOTTOM, gap);

    // Filaments
    auto *mapping_section = new Section(m_scroll, _L("Filaments"));
    auto *hint            = new Label(mapping_section, Label::Body_12, _L("Click a filament to change the toolhead it prints from."));
    hint->SetForegroundColour(colours.muted);
    mapping_section->header()->Add(hint, 0, wxALIGN_CENTER_VERTICAL | wxLEFT, gap);
    auto *cards = new wxWrapSizer(wxHORIZONTAL, wxREMOVE_LEADING_SPACES); // not the default, which stretches the last card
    m_mapping_note = new Label(mapping_section, wxEmptyString, LB_AUTO_WRAP, wxSize(FromDIP(520), -1));
    m_mapping_note->SetForegroundColour(colours.danger);
    const std::vector<int> suggested = U1PrintTask::suggest_mapping(plate_filaments, printer.toolheads);
    for (size_t i = 0; i < plate_filaments.size(); ++i) {
        const U1PrintTask::Filament &f = plate_filaments[i];
        if (!f.present)
            continue;
        auto *card = new U1FilamentCard(mapping_section, int(i + 1), f, i < filament_grams.size() ? filament_grams[i] : 0., printer.toolheads,
                                        suggested[i] >= 0 ? suggested[i] : 0, [this] { update_state(); });
        cards->Add(card, 0, wxRIGHT | wxBOTTOM, FromDIP(10));
        m_rows.push_back({i, card});
    }
    mapping_section->content()->Add(cards, 0, wxEXPAND);
    mapping_section->content()->Add(m_mapping_note, 0, wxEXPAND);
    sections->Add(mapping_section, 0, wxEXPAND | wxBOTTOM, gap);

    // Print preparation: one row; what each option does is in its tooltip
    auto *preparation = new Section(m_scroll, _L("Print preparation"));
    auto *options_row = new wxWrapSizer(wxHORIZONTAL, wxREMOVE_LEADING_SPACES);
    auto add_check = [&](const wxString &label, const wxString &help, bool value) {
        auto *check = new ::CheckBox(preparation);
        check->SetValue(value);
        check->SetToolTip(help);
        auto *text = new Label(preparation, label);
        text->SetToolTip(help);
        // Clicking the words ticks the box, as people expect of a checkbox's label.
        text->Bind(wxEVT_LEFT_UP, [check](wxMouseEvent &) { check->SetValue(!check->GetValue()); });
        auto *pair = new wxBoxSizer(wxHORIZONTAL);
        pair->Add(check, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, FromDIP(6));
        pair->Add(text, 0, wxALIGN_CENTER_VERTICAL);
        options_row->Add(pair, 0, wxRIGHT | wxBOTTOM, FromDIP(18));
        return check;
    };
    // Bed leveling starts ticked whatever the printer last used: a print should be leveled unless someone decides otherwise.
    m_bed_level = add_check(_L("Bed leveling"), _L("Measure the build plate before this print."), true);
    m_flow_calibrate = add_check(_L("Flow calibration"), _L("Calibrate only the toolheads used by this print."),
                                 printer.flow_calibrate);
    m_shaper_calibrate = add_check(_L("Input shaper calibration"), _L("Measure vibration compensation before printing."),
                                   printer.shaper_calibrate);
    m_time_lapse = add_check(_L("Timelapse"), _L("Capture a timelapse with the printer camera."),
                             printer.time_lapse_camera);
    preparation->content()->Add(options_row, 0, wxEXPAND);
    sections->Add(preparation, 0, wxEXPAND);

    m_scroll->SetSizer(sections);
    top->Add(m_scroll, 1, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, FromDIP(12));

    auto *buttons = new DialogButtons(this, {"Cancel", "Upload without starting", "Start Print"}, _L("Start Print"));
    auto *cancel = buttons->GetCANCEL();
    auto *upload = m_upload = buttons->GetButtonFromIndex(1);
    auto *print  = m_print  = buttons->GetButtonFromIndex(2);
    buttons->SetBackgroundColour(colours.window); // the row behind the buttons, which is otherwise white
    top->Add(buttons, 0, wxEXPAND);

    cancel->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
    upload->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_confirm(false); });
    print->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_confirm(true); });

    SetSizer(top);
    // Plain text takes the theme's text colour and the colour of what it sits on. The labels given a colour of
    // their own above (the hint, the note, the status) keep it.
    std::function<void(wxWindow *)> colour_text = [&](wxWindow *window) {
        for (wxWindow *child : window->GetChildren()) {
            if (dynamic_cast<wxStaticText *>(child) != nullptr) {
                child->SetBackgroundColour(window->GetBackgroundColour());
                if (child != hint && child != m_mapping_note && child != m_status)
                    child->SetForegroundColour(colours.text);
            }
            colour_text(child);
        }
    };
    colour_text(m_scroll);
    // The printer is read once the dialog is on screen, so a slow printer shows a dialog that says it is
    // checking instead of nothing at all. Until then neither button can be pressed.
    m_checking = static_cast<bool>(m_read_readiness);
    update_state(); // sizes the dialog to its content, within the screen
    if (m_checking)
        CallAfter([this] {
            Update();
            refresh_readiness();
            update_state();
        });
    CenterOnParent();
    wxGetApp().UpdateDlgDarkUI(this);
}

void U1SendDialog::fit_to_screen()
{
    // The sections take the height they need unless the screen is smaller; then they scroll.
    const wxSize    content = m_scroll->GetSizer()->GetMinSize();
    const int       index   = wxDisplay::GetFromWindow(GetParent() != nullptr ? GetParent() : this);
    const wxDisplay display(index == wxNOT_FOUND ? 0u : unsigned(index));
    const int       screen  = display.GetClientArea().GetHeight();
    const int    limit   = std::max(FromDIP(200), screen - FromDIP(160)); // leaves room for the buttons and the window frame
    const bool   scrolls = content.y > limit;
    m_scroll->SetMinSize(wxSize(content.x + (scrolls ? wxSystemSettings::GetMetric(wxSYS_VSCROLL_X, m_scroll) : 0), std::min(content.y, limit)));
    GetSizer()->SetSizeHints(this);
    Layout();
    m_scroll->FitInside();
}

std::vector<int> U1SendDialog::mapping() const
{
    std::vector<int> filament_to_toolhead(m_plate_filaments.size(), U1PrintTask::UNMAPPED);
    for (const Row &row : m_rows)
        filament_to_toolhead[row.filament] = row.card->selection();
    return filament_to_toolhead;
}

void U1SendDialog::refresh_readiness()
{
    if (!m_read_readiness)
        return;
    wxBusyCursor  wait;
    ReadinessRead read = m_read_readiness();
    m_readiness = std::move(read.check);
    // The time says when the printer last answered; a read it did not answer has none.
    m_checked_at = read.answered ? wxDateTime::Now().FormatISOTime() : wxString(); // 24-hour, so the status stays short
    m_checking   = false;
}

void U1SendDialog::update_state()
{
    wxString note;
    for (const Row &row : m_rows)
        if (const wxString problem = row.card->problem(); !problem.IsEmpty())
            note += (note.IsEmpty() ? wxString() : "\n") + problem;

    const std::vector<int> filament_to_toolhead = mapping();
    const wxString         no_upload = m_readiness ? m_readiness(filament_to_toolhead, false) : wxString();
    const wxString         no_start  = m_readiness ? m_readiness(filament_to_toolhead, true) : wxString();
    m_upload->Enable(!m_checking && no_upload.IsEmpty());
    m_print->Enable(!m_checking && no_start.IsEmpty());
    m_print->SetToolTip(no_start);
    m_upload->SetToolTip(no_upload);
    // Without a check the dialog only knows that the printer answered.
    wxString status = m_checking ? _L("Checking the printer…") : !no_start.IsEmpty() ? no_start : m_readiness ? _L("Online • Ready") :
                                                                                       _L("Online. Readiness is checked when the file is sent.");
    if (m_readiness && !m_checked_at.IsEmpty())
        // TRN %1$s is the printer's status or the reason a print cannot start, %2$s a time of day.
        status = wxString::Format(_L("%1$s (checked at %2$s)"), status, m_checked_at);
    const Theme colours = theme();
    m_status->SetForegroundColour(m_checking ? colours.muted : no_start.IsEmpty() ? colours.ready : colours.danger);

    if (m_laid_out && note == m_mapping_note->GetLabel() && status == m_status->GetLabel())
        return;
    m_mapping_note->SetLabel(note);
    m_mapping_note->Show(!note.IsEmpty());
    m_status->SetLabel(status);
    m_laid_out = true;
    fit_to_screen();
    Refresh();
}

boost::filesystem::path U1SendDialog::filename() const
{
    return boost::filesystem::path(into_u8(m_name));
}

void U1SendDialog::begin_name_edit()
{
    if (m_name_edit->IsShown())
        return;
    m_name_label->Hide();
    m_name_edit->GetTextCtrl()->ChangeValue(m_name);
    m_name_edit->Show();
    m_name_label->GetContainingSizer()->Layout();
    m_name_edit->GetTextCtrl()->SetFocus();
    // The ending is fixed, so the part worth typing over is what comes before it.
    m_name_edit->GetTextCtrl()->SetSelection(0, long(m_name.length()) - (m_name.Lower().EndsWith(".gcode") ? 6 : 0));
}

void U1SendDialog::end_name_edit(bool keep)
{
    // Hiding the field takes the focus from it, which arrives here again. Nothing is touched once the dialog
    // is being torn down.
    if (m_ending_name_edit || IsBeingDeleted() || !m_name_edit->IsShown())
        return;
    m_ending_name_edit = true;
    if (keep)
        if (const wxString typed = printer_safe_name(m_name_edit->GetTextCtrl()->GetValue()); !typed.IsEmpty())
            m_name = typed;
    m_name_label->SetLabel(m_name);
    m_name_label->SetToolTip(m_name + "\n" + _L("Click to change the name. On the printer the job's identifier is added to it."));
    m_name_edit->Hide();
    m_name_label->Show();
    m_name_label->GetContainingSizer()->Layout();
    m_ending_name_edit = false;
}

U1PrintTask::Options U1SendDialog::options() const
{
    U1PrintTask::Options options;
    options.filament_to_toolhead = mapping();
    std::set<int> used_toolheads;
    for (const Row &row : m_rows)
        if (row.card->selection() >= 0)
            used_toolheads.insert(row.card->selection());
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
    end_name_edit(true); // a name still being typed counts as typed
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

    // The printer is read once more at the moment of confirming, so the dialog never closes on a state it
    // showed minutes ago. The send still reads the printer itself afterwards; this only saves a wasted export.
    if (m_read_readiness) {
        refresh_readiness();
        update_state();
        if (const wxString refusal = m_readiness ? m_readiness(mapping(), start_print) : wxString(); !refusal.IsEmpty()) {
            MessageDialog(this, refusal, _L("Send to Snapmaker U1"), wxOK | wxICON_WARNING).ShowModal();
            return;
        }
    }

    m_start_print = start_print;
    EndModal(wxID_OK);
}

void U1SendDialog::on_dpi_changed(const wxRect & /*suggested_rect*/)
{
    m_name_edit->Rescale();
    for (const Row &row : m_rows) {
        row.card->SetMinSize(FromDIP(U1FilamentCard::CARD_SIZE));
        row.card->Refresh();
    }
    for (::CheckBox *check : {m_bed_level, m_flow_calibrate, m_shaper_calibrate, m_time_lapse})
        check->Rescale();
    fit_to_screen();
    Refresh();
}

} // namespace GUI
} // namespace Slic3r
