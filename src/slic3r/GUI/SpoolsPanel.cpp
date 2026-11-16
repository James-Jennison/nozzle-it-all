#include "SpoolsPanel.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_Utils.hpp"
#include "I18N.hpp"
#include "MainFrame.hpp"
#include "MsgDialog.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "U1FilamentSync.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/TextInput.hpp"
#include "format.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/SpoolInventory.hpp"
#include "libslic3r/SpoolTags.hpp"
#include "slic3r/Utils/U1JobJournal.hpp"
#include "slic3r/Utils/U1LanPrintHost.hpp"

#include <boost/algorithm/string.hpp>
#include <boost/log/trivial.hpp>

#include <wx/datetime.h>
#include <wx/dcclient.h>
#include <wx/panel.h>
#include <wx/sizer.h>
#include <wx/stattext.h>
#include <wx/textctrl.h>
#include <wx/utils.h>
#include <wx/weakref.h>

#include <algorithm>
#include <cmath>
#include <functional>
#include <optional>
#include <utility>
#include <vector>

namespace Slic3r::GUI {

namespace {

wxString when(long long ms) { return ms > 0 ? wxDateTime(static_cast<time_t>(ms / 1000)).Format("%Y-%m-%d %H:%M") : wxString(); }

wxString grams(double g) { return wxString::Format("%d g", int(std::lround(g))); }

// A printer the person has, by the preset that carries its address.
struct KnownPrinter
{
    std::string name;
    std::string key; ///< host[:port]
};

std::vector<KnownPrinter> known_printers()
{
    std::vector<KnownPrinter> printers;
    for (const Preset &preset : wxGetApp().preset_bundle->printers) {
        if (!preset.is_visible || preset.is_default || !preset.config.has("print_host") || preset.config.opt_string("print_host").empty())
            continue;
        const std::string key = U1Jobs::printer_key(preset.config.opt_string("print_host"));
        if (std::none_of(printers.begin(), printers.end(), [&key](const KnownPrinter &had) { return U1Jobs::same_printer(had.key, key); }))
            printers.push_back({preset.name, key});
    }
    return printers;
}

wxString where(const Spools::Location &location)
{
    switch (location.kind) {
    case Spools::Location::Kind::Dryer:
        // TRN %1% is a place the user named, such as "S4 left".
        return location.place.empty() ? _L("In a dryer") : format_wxstr(_L("Dryer: %1%"), from_u8(location.place));
    case Spools::Location::Kind::Printer: {
        std::string name = location.place;
        for (const KnownPrinter &printer : known_printers())
            if (U1Jobs::same_printer(printer.key, U1Jobs::printer_key(location.place)))
                name = printer.name;
        // TRN %1% is a printer's name, %2% a slot number counted from 1.
        return format_wxstr(_L("%1%, slot %2%"), from_u8(name), location.slot + 1);
    }
    case Spools::Location::Kind::Storage: break;
    }
    return location.place.empty() || location.place == "unplaced" ? _L("In storage") : from_u8(location.place);
}

// A small form: labelled fields, each a line of text or a choice, and OK / Cancel.
class SpoolForm final : public DPIDialog
{
public:
    struct Field
    {
        wxString              label;
        wxString              value;
        std::vector<wxString> choices; ///< not empty: a choice, *choice* the one first chosen
        int                   choice = 0;
    };

    SpoolForm(wxWindow *parent, const wxString &title, const wxString &intro, const std::vector<Field> &fields, const wxString &ok_label)
        : DPIDialog(parent, wxID_ANY, title, wxDefaultPosition, wxDefaultSize, wxCAPTION | wxCLOSE_BOX)
    {
        const bool dark = wxGetApp().dark_mode();
        const int  em   = wxGetApp().em_unit();
        SetBackgroundColour(NIATheme::surface(dark));
        auto *root = new wxBoxSizer(wxVERTICAL);
        if (!intro.empty()) {
            auto *text = new Label(this, intro, LB_AUTO_WRAP, wxSize(40 * em, -1));
            text->SetForegroundColour(NIATheme::text_muted(dark));
            root->Add(text, 0, wxALL | wxEXPAND, em);
        }
        auto *grid = new wxFlexGridSizer(2, em / 2, em);
        grid->AddGrowableCol(1, 1);
        for (const Field &field : fields) {
            auto *label = new Label(this, field.label);
            label->SetForegroundColour(NIATheme::text(dark));
            grid->Add(label, 0, wxALIGN_CENTER_VERTICAL);
            if (field.choices.empty()) {
                auto *input = new ::TextInput(this, field.value, "", "", wxDefaultPosition, wxSize(28 * em, -1));
                m_inputs.push_back(input);
                m_choices.push_back(nullptr);
                grid->Add(input, 1, wxEXPAND);
            } else {
                auto *choice = new ::ComboBox(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(28 * em, -1), 0, nullptr, wxCB_READONLY);
                for (const wxString &entry : field.choices)
                    choice->Append(entry);
                choice->SetSelection(field.choice);
                m_inputs.push_back(nullptr);
                m_choices.push_back(choice);
                grid->Add(choice, 1, wxEXPAND);
            }
        }
        root->Add(grid, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);
        auto *buttons = new DialogButtons(this, {"OK", "Cancel"});
        buttons->GetOK()->SetLabel(ok_label);
        buttons->GetOK()->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_OK); });
        buttons->GetCANCEL()->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
        root->Add(buttons, 0, wxEXPAND);
        SetSizerAndFit(root);
        CenterOnParent();
        wxGetApp().UpdateDlgDarkUI(this);
        if (!m_inputs.empty() && m_inputs.front() != nullptr)
            m_inputs.front()->GetTextCtrl()->SetFocus();
    }

    std::string text(size_t field) const
    {
        return field < m_inputs.size() && m_inputs[field] != nullptr ? into_u8(m_inputs[field]->GetTextCtrl()->GetValue().Trim().Trim(false)) : std::string();
    }
    int choice(size_t field) const { return field < m_choices.size() && m_choices[field] != nullptr ? m_choices[field]->GetSelection() : -1; }

protected:
    void on_dpi_changed(const wxRect &) override
    {
        GetSizer()->SetSizeHints(this);
        Refresh();
    }

private:
    std::vector<::TextInput *> m_inputs;
    std::vector<::ComboBox *>  m_choices;
};

// A number of grams as a person types it ("870", "870 g", "870,5"); nothing when it is not one.
std::optional<double> grams_typed(std::string text)
{
    boost::replace_all(text, ",", ".");
    boost::ierase_all(text, "g");
    boost::trim(text);
    try {
        size_t       used  = 0;
        const double value = std::stod(text, &used);
        if (used != text.size() || !(value >= 0.) || value > 100000.)
            return std::nullopt;
        return value;
    } catch (const std::exception &) {
        return std::nullopt;
    }
}

// Reads the inventory, lets *change* edit it and writes it back. A file that cannot be read is never written
// over: the person is told instead.
bool change_inventory(wxWindow *parent, const std::function<bool(Spools::Inventory &)> &change)
{
    bool              damaged   = false;
    Spools::Inventory inventory = Spools::Inventory::load(Spools::inventory_path(), &damaged);
    if (damaged) {
        MessageDialog(parent,
                      format_wxstr(_L("The spool list could not be read, so nothing was changed:\n%1%\n\nMove that file away to start a new list."),
                                   from_u8(Spools::inventory_path())),
                      _L("Spools"), wxOK | wxICON_WARNING).ShowModal();
        return false;
    }
    if (!change(inventory))
        return false;
    if (!inventory.save(Spools::inventory_path())) {
        MessageDialog(parent, _L("The spool list could not be saved."), _L("Spools"), wxOK | wxICON_WARNING).ShowModal();
        return false;
    }
    return true;
}

// A square of the spool's colour.
class ColourChip final : public wxPanel
{
public:
    ColourChip(wxWindow *parent, const std::string &colour) : wxPanel(parent, wxID_ANY, wxDefaultPosition, parent->FromDIP(wxSize(22, 22)))
    {
        SetMinSize(parent->FromDIP(wxSize(22, 22)));
        m_colour = wxColour(from_u8(colour));
        SetBackgroundStyle(wxBG_STYLE_PAINT);
        Bind(wxEVT_PAINT, [this](wxPaintEvent &) {
            wxPaintDC dc(this);
            const bool dark = wxGetApp().dark_mode();
            dc.SetBackground(wxBrush(NIATheme::surface(dark)));
            dc.Clear();
            dc.SetPen(wxPen(NIATheme::line(dark)));
            dc.SetBrush(m_colour.IsOk() ? wxBrush(m_colour) : *wxTRANSPARENT_BRUSH);
            dc.DrawRoundedRectangle(wxRect(GetClientSize()).Deflate(FromDIP(1)), FromDIP(4));
        });
    }

private:
    wxColour m_colour;
};

} // namespace

SpoolsPanel::SpoolsPanel(wxWindow *parent) : wxScrolledWindow(parent, wxID_ANY)
{
    SetScrollRate(0, FromDIP(12));
    auto *outer = new wxBoxSizer(wxVERTICAL);
    auto *head  = new wxBoxSizer(wxHORIZONTAL);
    m_empty     = new wxStaticText(this, wxID_ANY, wxEmptyString);
    head->Add(m_empty, 1, wxALIGN_CENTER_VERTICAL);
    const auto button = [&](const wxString &label, const wxString &help, void (SpoolsPanel::*act)()) {
        auto *made = new ::Button(this, label);
        made->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        made->SetToolTip(help);
        made->Bind(wxEVT_BUTTON, [this, act](wxCommandEvent &) {
            // Not on the stack of the click: the forms are modal and the list is rebuilt.
            wxGetApp().CallAfter([self = wxWeakRef<SpoolsPanel>(this), act] {
                if (self && !wxGetApp().is_closing())
                    (self.get()->*act)();
            });
        });
        head->Add(made, 0, wxALIGN_CENTER_VERTICAL | wxLEFT, FromDIP(8));
    };
    button(_L("Add from the printer"), _L("Reads what is loaded in the printer shown on this tab (read requests only) and offers a spool for each loaded slot that has none recorded."),
           &SpoolsPanel::add_from_printer);
    button(_L("Add spool"), _L("Adds a spool you describe yourself."), &SpoolsPanel::add_by_hand);
    outer->Add(head, 0, wxEXPAND | wxALL, FromDIP(12));
    m_rows = new wxBoxSizer(wxVERTICAL);
    outer->Add(m_rows, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, FromDIP(12));
    SetSizer(outer);
    on_sys_color_changed();
}

void SpoolsPanel::set_active(bool active)
{
    if (active)
        rebuild();
}

void SpoolsPanel::on_sys_color_changed()
{
    SetBackgroundColour(NIATheme::background(wxGetApp().dark_mode()));
    rebuild();
}

void SpoolsPanel::rebuild()
{
    const bool              dark     = wxGetApp().dark_mode();
    bool                    damaged  = false;
    const Spools::Inventory inventory = Spools::Inventory::load(Spools::inventory_path(), &damaged);
    Freeze();
    m_rows->Clear(true);
    size_t listed = 0;
    for (const Spools::Spool &spool : inventory.spools()) {
        if (spool.archived)
            continue;
        ++listed;
        auto *row = new wxPanel(this);
        row->SetBackgroundColour(NIATheme::surface(dark));
        auto *line = new wxBoxSizer(wxHORIZONTAL);
        line->Add(new ColourChip(row, spool.color), 0, wxALIGN_CENTER_VERTICAL | wxLEFT, FromDIP(10));
        auto *text = new wxBoxSizer(wxVERTICAL);
        const auto add_text = [&](const wxString &label, const wxColour &colour, bool head) {
            auto *item = new wxStaticText(row, wxID_ANY, label, wxDefaultPosition, wxDefaultSize, wxST_ELLIPSIZE_END);
            if (head)
                item->SetFont(Label::Head_14);
            item->SetForegroundColour(colour);
            text->Add(item, 0, wxEXPAND | wxTOP, head ? 0 : FromDIP(2));
        };
        // TRN %1% is the app's number for a spool, %2% the spool's name.
        add_text(format_wxstr(_L("Spool %1% · %2%"), spool.number, from_u8(spool.name.empty() ? spool.material : spool.name)), NIATheme::text(dark), true);
        wxString what = from_u8(spool.material);
        if (!spool.vendor.empty() && !boost::icontains(spool.name, spool.vendor))
            what = from_u8(spool.vendor) + (what.empty() ? wxString() : " " + what);
        what += (what.empty() ? wxString() : wxString::FromUTF8(" · ")) + where(spool.location);
        add_text(what, NIATheme::text_muted(dark), false);
        if (const Spools::Drying *session = Spools::drying_now(spool))
            // TRN %1% is a date and time.
            add_text(format_wxstr(_L("Drying since %1%"), when(session->start_ms)) + (session->place.empty() ? wxString() : " (" + from_u8(session->place) + ")"),
                     NIATheme::heat(dark), false);
        else if (!spool.drying.empty())
            add_text(format_wxstr(_L("Last dried until %1%"), when(spool.drying.back().end_ms)), NIATheme::text_muted(dark), false);
        line->Add(text, 1, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(10));

        const Spools::Remaining left     = Spools::remaining(spool);
        auto                   *left_col = new wxBoxSizer(wxVERTICAL);
        auto *amount = new wxStaticText(row, wxID_ANY, (left.measured ? wxString() : wxString::FromUTF8("≈ ")) + grams(left.grams));
        amount->SetFont(Label::Head_14);
        amount->SetForegroundColour(left.grams <= 0. ? NIATheme::danger(dark) : NIATheme::text(dark));
        // What kind of figure it is, always said: a weighing, or an estimate from the jobs sent from this app.
        auto *kind = new wxStaticText(row, wxID_ANY, left.measured ? format_wxstr(_L("weighed %1%"), when(spool.weighings.back().at_ms)) :
                                                     spool.weighings.empty() ? _L("estimated from jobs") : _L("estimated since the last weighing"));
        kind->SetForegroundColour(NIATheme::text_muted(dark));
        left_col->Add(amount, 0, wxALIGN_RIGHT);
        left_col->Add(kind, 0, wxALIGN_RIGHT | wxTOP, FromDIP(2));
        line->Add(left_col, 0, wxALIGN_CENTER_VERTICAL | wxALL, FromDIP(10));

        const int  number     = spool.number;
        const auto add_button = [&](const wxString &label, const wxString &help, void (SpoolsPanel::*act)(int)) {
            auto *made = new ::Button(row, label);
            made->SetStyle(ButtonStyle::Regular, ButtonType::Window);
            made->SetToolTip(help);
            made->Bind(wxEVT_BUTTON, [this, act, number](wxCommandEvent &) {
                wxGetApp().CallAfter([self = wxWeakRef<SpoolsPanel>(this), act, number] {
                    if (self && !wxGetApp().is_closing())
                        (self.get()->*act)(number);
                });
            });
            line->Add(made, 0, wxALIGN_CENTER_VERTICAL | wxTOP | wxBOTTOM | wxRIGHT, FromDIP(10));
        };
        add_button(_L("Weigh"), _L("Records what is left on the spool by your own weighing. It replaces the estimate."), &SpoolsPanel::weigh);
        add_button(_L("Move"), _L("Records where the spool is now: storage, a dryer, or a printer's slot."), &SpoolsPanel::move);
        add_button(Spools::drying_now(spool) != nullptr ? _L("End drying") : _L("Dry"),
                   _L("Records a drying session. It is a note of when and where; the app does not control a dryer and does not say the filament is dry."),
                   &SpoolsPanel::dry);
        add_button(_L("Archive"), _L("Takes the spool off this list (used up, given away). Its record is kept."), &SpoolsPanel::archive);
        row->SetSizer(line);
        m_rows->Add(row, 0, wxEXPAND | wxBOTTOM, FromDIP(8));
    }
    m_empty->SetForegroundColour(NIATheme::text_muted(dark));
    m_empty->SetLabel(damaged      ? _L("The spool list could not be read. Nothing is changed until that file is moved away.") :
                      listed == 0  ? _L("No spools yet. Add one yourself, or from what is loaded in the printer shown on this tab.") :
                                     _L("Your spools. What is left is an estimate from the jobs sent from this app until you weigh a spool. None of this stops a print."));
    BOOST_LOG_TRIVIAL(warning) << "Spools: " << listed << " listed";
    wxGetApp().UpdateDarkUIWin(this);
    Thaw();
    FitInside();
    Layout();
}

void SpoolsPanel::add_by_hand()
{
    SpoolForm form(wxGetApp().mainframe, _L("Add spool"), _L("Describe the spool. The app gives it a number of its own."),
                   {{_L("Name"), wxEmptyString, {}, 0},
                    {_L("Brand"), wxEmptyString, {}, 0},
                    {_L("Material"), "PLA", {}, 0},
                    {_L("Colour (#RRGGBB)"), wxEmptyString, {}, 0},
                    {_L("Filament on it (g)"), "1000", {}, 0}},
                   _L("Add"));
    if (form.ShowModal() != wxID_OK)
        return;
    const std::optional<double> initial = grams_typed(form.text(4));
    std::string                 colour  = form.text(3);
    if (!colour.empty() && colour.front() != '#')
        colour = "#" + colour;
    if (form.text(2).empty() || !initial || (!colour.empty() && !wxColour(from_u8(colour)).IsOk())) {
        MessageDialog(this, _L("A spool needs a material and an amount in grams; a colour, when given, is written like #D93B90. Nothing was added."), _L("Spools"),
                      wxOK | wxICON_WARNING).ShowModal();
        return;
    }
    Spools::Spool spool;
    spool.vendor    = form.text(1);
    spool.material  = form.text(2);
    spool.name      = form.text(0).empty() ? (spool.vendor.empty() ? spool.material : spool.vendor + " " + spool.material) : form.text(0);
    spool.color     = colour;
    spool.initial_g = *initial;
    if (change_inventory(this, [&](Spools::Inventory &inventory) { return inventory.add(spool, U1Jobs::now_ms()) > 0; }))
        rebuild();
}

void SpoolsPanel::add_from_printer()
{
    const std::string host = viewed_printer_lan_host();
    if (host.empty()) {
        MessageDialog(this, _L("The printer shown on this tab has no address, so it cannot be read."), _L("Spools"), wxOK | wxICON_WARNING).ShowModal();
        return;
    }
    // What the printer reports per slot: a Snapmaker U1's toolheads, or the slots of a filament changer.
    struct Loaded
    {
        int         slot = 0;
        std::string vendor, material, colour, tag;
    };
    std::vector<Loaded> loaded;
    wxString            error;
    {
        wxBusyCursor wait;
        if (const auto report = U1LanPrintHost::query_filament_report(host, error)) {
            const std::vector<U1ReportedFilament> slots = parse_u1_filament_report(*report);
            for (size_t i = 0; i < slots.size(); ++i)
                if (slots[i].present)
                    loaded.push_back({int(i), slots[i].vendor, slots[i].type + (slots[i].sub_type.empty() ? "" : " " + slots[i].sub_type), slots[i].color, slots[i].tag_id});
        } else {
            std::string base;
            if (const auto state = U1LanPrintHost::query_afc_printer(host, base, error)) {
                std::vector<std::string> tags;
                U1LanPrintHost::query_afc_brands(base, &tags);
                for (size_t i = 0; i < state->toolheads.size(); ++i)
                    if (state->toolheads[i].present)
                        loaded.push_back({int(i), state->toolheads[i].vendor, state->toolheads[i].type, state->toolheads[i].color,
                                          i < tags.size() ? SpoolTags::normalized(tags[i]) : std::string()});
            }
        }
    }
    if (loaded.empty()) {
        MessageDialog(this, format_wxstr(_L("The printer at %1% did not report any loaded filament.\n\n%2%"), from_u8(host), error), _L("Spools"),
                      wxOK | wxICON_WARNING).ShowModal();
        return;
    }
    const std::string key = U1Jobs::printer_key(host);
    bool              damaged = false;
    const Spools::Inventory now = Spools::Inventory::load(Spools::inventory_path(), &damaged);
    std::vector<Loaded>     to_add;
    wxString                list;
    for (const Loaded &slot : loaded) {
        const bool taken = std::any_of(now.spools().begin(), now.spools().end(), [&](const Spools::Spool &spool) {
            return !spool.archived && spool.location.kind == Spools::Location::Kind::Printer && spool.location.slot == slot.slot &&
                   U1Jobs::same_printer(U1Jobs::printer_key(spool.location.place), key);
        });
        if (taken)
            continue;
        to_add.push_back(slot);
        // TRN %1% is a slot number, %2% a brand and material such as "Snapmaker PLA".
        list += "\n" + format_wxstr(_L("Slot %1%: %2%"), slot.slot + 1, from_u8(boost::trim_copy(slot.vendor + " " + slot.material)));
    }
    if (to_add.empty()) {
        MessageDialog(this, _L("Every loaded slot of this printer already has a spool recorded."), _L("Spools"), wxOK | wxICON_INFORMATION).ShowModal();
        return;
    }
    if (MessageDialog(this,
                      _L("Add a spool for each of these slots, as the printer reports them?") + "\n" + list + "\n\n" +
                          _L("Each is recorded as holding 1000 g; weigh a spool to correct that. Nothing is sent to the printer."),
                      _L("Spools"), wxYES_NO | wxYES_DEFAULT | wxICON_QUESTION).ShowModal() != wxID_YES)
        return;
    if (change_inventory(this, [&](Spools::Inventory &inventory) {
            for (const Loaded &slot : to_add) {
                Spools::Spool spool;
                spool.vendor    = slot.vendor;
                spool.material  = slot.material;
                spool.name      = boost::trim_copy(slot.vendor + " " + slot.material);
                spool.color     = slot.colour;
                spool.initial_g = 1000.;
                // A product number names the product, not this one spool: it is not kept as the spool's tag.
                if (!slot.tag.empty() && !boost::starts_with(slot.tag, "SKU"))
                    spool.tags.push_back(slot.tag);
                spool.location = Spools::Location{Spools::Location::Kind::Printer, key, slot.slot};
                inventory.add(spool, U1Jobs::now_ms());
            }
            return true;
        }))
        rebuild();
}

void SpoolsPanel::weigh(int number)
{
    SpoolForm form(wxGetApp().mainframe, _L("Weigh spool"),
                   _L("Type how much filament is left on the spool, in grams: what the scale shows less the weight of the empty spool."),
                   {{_L("Filament left (g)"), wxEmptyString, {}, 0}}, _L("Record"));
    if (form.ShowModal() != wxID_OK)
        return;
    const std::optional<double> left = grams_typed(form.text(0));
    if (!left) {
        MessageDialog(this, _L("That is not an amount in grams. Nothing was recorded."), _L("Spools"), wxOK | wxICON_WARNING).ShowModal();
        return;
    }
    if (change_inventory(this, [&](Spools::Inventory &inventory) { return inventory.weigh(number, *left, U1Jobs::now_ms()); }))
        rebuild();
}

void SpoolsPanel::move(int number)
{
    const std::vector<KnownPrinter> printers = known_printers();
    std::vector<wxString>           places   = {_L("Storage"), _L("A dryer")};
    for (const KnownPrinter &printer : printers)
        // TRN %1% is a printer's name.
        places.push_back(format_wxstr(_L("Printer: %1%"), from_u8(printer.name)));
    SpoolForm form(wxGetApp().mainframe, _L("Move spool"),
                   _L("Say where the spool is now. For storage or a dryer, name the place in your own words. For a printer, type the slot number (1 to 4)."),
                   {{_L("Where"), wxEmptyString, places, 0}, {_L("Place, or slot number"), wxEmptyString, {}, 0}}, _L("Move"));
    if (form.ShowModal() != wxID_OK)
        return;
    const int        chosen = form.choice(0);
    Spools::Location to;
    if (chosen >= 2 && size_t(chosen - 2) < printers.size()) {
        int slot = 0;
        try {
            slot = std::stoi(form.text(1));
        } catch (const std::exception &) {
        }
        if (slot < 1 || slot > 4) {
            MessageDialog(this, _L("For a printer, type the slot number, 1 to 4. The spool was not moved."), _L("Spools"), wxOK | wxICON_WARNING).ShowModal();
            return;
        }
        to = Spools::Location{Spools::Location::Kind::Printer, printers[size_t(chosen - 2)].key, slot - 1};
    } else
        to = Spools::Location{chosen == 1 ? Spools::Location::Kind::Dryer : Spools::Location::Kind::Storage, form.text(1), -1};
    int displaced = 0;
    if (!change_inventory(this, [&](Spools::Inventory &inventory) {
            if (inventory.find(number) == nullptr)
                return false;
            displaced = inventory.move(number, to);
            return true;
        }))
        return;
    rebuild();
    if (displaced != 0)
        // TRN %1% is the app's number for a spool.
        MessageDialog(this, format_wxstr(_L("Spool %1% was recorded in that slot and is now in storage."), displaced), _L("Spools"), wxOK | wxICON_INFORMATION).ShowModal();
}

void SpoolsPanel::dry(int number)
{
    bool                    damaged = false;
    const Spools::Inventory now     = Spools::Inventory::load(Spools::inventory_path(), &damaged);
    const Spools::Spool    *spool   = now.find(number);
    if (damaged || spool == nullptr)
        return rebuild();
    const bool ending = Spools::drying_now(*spool) != nullptr;
    SpoolForm  form(wxGetApp().mainframe, ending ? _L("End drying") : _L("Start drying"),
                    ending ? _L("Ends the drying session and keeps a note with it. This records that the spool was in a dryer, not that the filament is dry.") :
                             _L("Starts a drying session for this spool. The app does not control a dryer."),
                    {{ending ? _L("Notes") : _L("Dryer or position"), wxEmptyString, {}, 0}}, ending ? _L("End") : _L("Start"));
    if (form.ShowModal() != wxID_OK)
        return;
    if (change_inventory(this, [&](Spools::Inventory &inventory) {
            return ending ? inventory.end_drying(number, form.text(0), U1Jobs::now_ms()) : inventory.start_drying(number, form.text(0), U1Jobs::now_ms());
        }))
        rebuild();
}

void SpoolsPanel::archive(int number)
{
    // TRN %1% is the app's number for a spool.
    if (MessageDialog(this, format_wxstr(_L("Take spool %1% off the list? Its record is kept."), number), _L("Spools"), wxYES_NO | wxNO_DEFAULT | wxICON_QUESTION)
            .ShowModal() != wxID_YES)
        return;
    if (change_inventory(this, [&](Spools::Inventory &inventory) {
            Spools::Spool *spool = inventory.find(number);
            if (spool == nullptr)
                return false;
            spool->archived = true;
            return true;
        }))
        rebuild();
}

} // namespace Slic3r::GUI
