#include "AddPrinterDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "I18N.hpp"
#include "MainFrame.hpp"
#include "MsgDialog.hpp"
#include "Plater.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/TextInput.hpp"
#include "format.hpp"
#include "libslic3r/DeviceWorkspaceState.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "slic3r/Utils/U1JobJournal.hpp"

#include <boost/algorithm/string.hpp>

#include <wx/sizer.h>
#include <wx/textctrl.h>
#include <wx/utils.h>

#include <algorithm>

namespace Slic3r::GUI {

namespace {

wxString kind_name(PrinterDiscovery::Kind kind)
{
    switch (kind) {
    case PrinterDiscovery::Kind::SnapmakerU1: return "Snapmaker U1";
    case PrinterDiscovery::Kind::AfcLanes: return _L("Klipper printer with a filament changer (AFC)");
    case PrinterDiscovery::Kind::Moonraker: break;
    }
    return _L("Klipper printer");
}

// The model a printer of this kind is offered first: what it reports decides, never its name.
bool suits(PrinterDiscovery::Kind kind, const Preset &preset)
{
    const std::string model = preset.config.has("printer_model") ? preset.config.opt_string("printer_model") : std::string();
    switch (kind) {
    case PrinterDiscovery::Kind::SnapmakerU1: return boost::icontains(model, "Snapmaker") && boost::icontains(model, "U1");
    case PrinterDiscovery::Kind::AfcLanes: return boost::icontains(model, "AFC");
    case PrinterDiscovery::Kind::Moonraker: break;
    }
    return false;
}

// The printer preset that already has this address, if any.
const Preset *preset_at(const std::string &address)
{
    const std::string key = U1Jobs::printer_key(address);
    for (const Preset &preset : wxGetApp().preset_bundle->printers)
        if (preset.is_visible && !preset.is_default && preset.config.has("print_host") && !preset.config.opt_string("print_host").empty() &&
            U1Jobs::same_printer(U1Jobs::printer_key(preset.config.opt_string("print_host")), key))
            return &preset;
    return nullptr;
}

} // namespace

AddPrinterDialog::AddPrinterDialog(wxWindow *parent)
    : DPIDialog(parent ? parent : static_cast<wxWindow *>(wxGetApp().mainframe), wxID_ANY, _L("Add printer"), wxDefaultPosition, wxDefaultSize,
                wxCAPTION | wxCLOSE_BOX)
    , m_alive(std::make_shared<std::atomic<bool>>(true))
    , m_stop(std::make_shared<std::atomic<bool>>(false))
{
    SetBackgroundColour(*wxWHITE);
    SetFont(Label::Body_14);
    const int gap   = FromDIP(8);
    const int width = FromDIP(420);

    auto *sizer = new wxBoxSizer(wxVERTICAL);
    auto *intro = new Label(this, _L("A printer added here becomes a printer preset of your own with its address. Searching and checking only read from "
                                    "the network; nothing is sent to a printer, and the printer selected for slicing stays as it is."),
                            LB_AUTO_WRAP, wxSize(width, -1));
    sizer->Add(intro, 0, wxEXPAND | wxALL, FromDIP(12));

    auto *grid = new wxFlexGridSizer(2, gap, FromDIP(12));
    grid->AddGrowableCol(1, 1);
    const auto add_row = [&](const wxString &label, wxSizer *content) {
        grid->Add(new Label(this, label), 0, wxALIGN_CENTER_VERTICAL);
        grid->Add(content, 1, wxEXPAND);
    };

    auto *search_row = new wxBoxSizer(wxHORIZONTAL);
    m_search         = new ::Button(this, _L("Search the network"));
    m_search->SetStyle(ButtonStyle::Regular, ButtonType::Window);
    m_search->SetToolTip(_L("Asks every address of this computer's own local network whether a Klipper printer answers there."));
    m_search_status = new Label(this, wxEmptyString, LB_AUTO_WRAP, wxSize(FromDIP(270), -1));
    search_row->Add(m_search, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, gap);
    search_row->Add(m_search_status, 1, wxALIGN_CENTER_VERTICAL);
    add_row(_L("On the network"), search_row);

    auto *found_row = new wxBoxSizer(wxHORIZONTAL);
    m_found_list    = new ::ComboBox(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(width, -1), 0, nullptr, wxCB_READONLY);
    m_found_list->Disable();
    found_row->Add(m_found_list, 1);
    add_row(_L("Found"), found_row);

    auto *address_row = new wxBoxSizer(wxHORIZONTAL);
    m_address         = new ::TextInput(this, wxEmptyString, wxEmptyString, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(260), -1), wxTE_PROCESS_ENTER);
    auto *check       = new ::Button(this, _L("Check"));
    check->SetStyle(ButtonStyle::Regular, ButtonType::Window);
    check->SetToolTip(_L("Asks this address what printer answers there."));
    address_row->Add(m_address, 1, wxALIGN_CENTER_VERTICAL | wxRIGHT, gap);
    address_row->Add(check, 0, wxALIGN_CENTER_VERTICAL);
    add_row(_L("Address"), address_row);

    auto *status_row = new wxBoxSizer(wxHORIZONTAL);
    m_address_status = new Label(this, wxEmptyString, LB_AUTO_WRAP, wxSize(width, -1));
    status_row->Add(m_address_status, 1);
    grid->AddSpacer(0);
    grid->Add(status_row, 1, wxEXPAND);

    auto *model_row = new wxBoxSizer(wxHORIZONTAL);
    m_model         = new ::ComboBox(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(width, -1), 0, nullptr, wxCB_READONLY);
    // The printer models chosen for this installation; a model is added to them in the printer selection.
    for (const Preset &preset : wxGetApp().preset_bundle->printers)
        if (preset.is_system && preset.is_visible && !preset.is_default)
            m_models.push_back(preset.name);
    std::sort(m_models.begin(), m_models.end());
    for (const std::string &name : m_models)
        m_model->Append(from_u8(name));
    m_model->SetToolTip(_L("The printer model the new preset is made from. Only the models chosen for this installation are listed."));
    model_row->Add(m_model, 1);
    add_row(_L("Printer model"), model_row);

    auto *name_row = new wxBoxSizer(wxHORIZONTAL);
    m_name         = new ::TextInput(this, wxEmptyString, wxEmptyString, wxEmptyString, wxDefaultPosition, wxSize(width, -1));
    name_row->Add(m_name, 1);
    add_row(_L("Name"), name_row);
    sizer->Add(grid, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, FromDIP(12));

    auto *buttons = new DialogButtons(this, {"Add", "Cancel"}, _L("Add"));
    buttons->GetButtonFromIndex(0)->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { add(); });
    buttons->GetCANCEL()->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
    sizer->Add(buttons, 0, wxEXPAND);

    m_search->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { search(); });
    check->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { check_address(); });
    m_address->Bind(wxEVT_TEXT_ENTER, [this](wxCommandEvent &) { check_address(); });
    m_found_list->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent &event) {
        const int at = m_found_list->GetSelection();
        if (at >= 0 && size_t(at) < m_found.size()) {
            m_address->GetTextCtrl()->SetValue(from_u8(m_found[size_t(at)].address));
            took(m_found[size_t(at)]);
        }
        event.Skip();
    });
    const auto rename = [this](wxCommandEvent &event) {
        // The name follows the model until the person types one of their own.
        const int at = m_model->GetSelection();
        if (at >= 0 && size_t(at) < m_models.size() && into_u8(m_name->GetTextCtrl()->GetValue()) == m_suggested_name) {
            const std::string address = into_u8(m_address->GetTextCtrl()->GetValue().Trim().Trim(false));
            m_suggested_name          = m_models[size_t(at)] + (address.empty() ? std::string() : " - " + address);
            boost::replace_all(m_suggested_name, ":", " port ");
            m_name->GetTextCtrl()->SetValue(from_u8(m_suggested_name));
        }
        event.Skip();
    };
    m_model->Bind(wxEVT_COMBOBOX, rename);

    SetSizerAndFit(sizer);
    CenterOnParent();
    wxGetApp().UpdateDlgDarkUI(this);
}

AddPrinterDialog::~AddPrinterDialog()
{
    m_alive->store(false);
    // The search is told to stop and waited for: each of its requests gives up within a second or so.
    m_stop->store(true);
    if (m_searcher.joinable())
        m_searcher.join();
}

void AddPrinterDialog::on_dpi_changed(const wxRect &)
{
    GetSizer()->SetSizeHints(this);
    Refresh();
}

void AddPrinterDialog::search()
{
    if (m_searcher.joinable())
        m_searcher.join(); // the last search has ended: the button is disabled while one runs
    const std::string own = PrinterDiscovery::own_ipv4();
    if (PrinterDiscovery::sweep_addresses(own).empty()) {
        m_search_status->SetLabel(_L("This computer is not on a local network that can be searched. Type the printer's address."));
        Layout();
        Fit();
        return;
    }
    m_search->Disable();
    m_search_status->SetLabel(_L("Searching…"));
    m_searcher = std::thread([alive = m_alive, stop = m_stop, this] {
        std::vector<PrinterDiscovery::Found> found = PrinterDiscovery::sweep(*stop, [&alive, this](size_t done, size_t total) {
            wxGetApp().CallAfter([alive, this, done, total] {
                if (alive->load())
                    // TRN %1% and %2% are numbers of network addresses.
                    m_search_status->SetLabel(format_wxstr(_L("Searching… %1% of %2% addresses asked"), done, total));
            });
        });
        wxGetApp().CallAfter([alive, this, found = std::move(found)] {
            if (!alive->load())
                return;
            m_found = found;
            m_found_list->Clear();
            for (const PrinterDiscovery::Found &one : m_found) {
                const Preset *had = preset_at(one.address);
                // TRN %1% is a printer's own name, %2% its network address, %3% what kind of printer it is.
                wxString line = format_wxstr(_L("%1% at %2% (%3%)"), one.hostname.empty() ? _L("Printer") : from_u8(one.hostname), from_u8(one.address),
                                             kind_name(one.kind));
                if (had != nullptr)
                    // TRN Follows a found printer; %1% is the name of a printer preset.
                    line += " " + format_wxstr(_L("– already added as %1%"), from_u8(had->name));
                m_found_list->Append(line);
            }
            m_found_list->Enable(!m_found.empty());
            m_search->Enable();
            // TRN %1% is a number of printers.
            m_search_status->SetLabel(m_found.empty() ? _L("No Klipper printer answered on this network.") :
                                                        format_wxstr(_L_PLURAL("%1% printer answered.", "%1% printers answered.", m_found.size()), m_found.size()));
            Layout();
        });
    });
}

void AddPrinterDialog::check_address()
{
    const std::string address = into_u8(m_address->GetTextCtrl()->GetValue().Trim().Trim(false));
    if (address.empty()) {
        m_address_status->SetLabel(_L("Type the printer's address first, such as 192.168.1.50."));
        Layout();
        return;
    }
    std::optional<PrinterDiscovery::Found> found;
    {
        wxBusyCursor            wait;
        const std::atomic<bool> never{false};
        found = PrinterDiscovery::probe(address, never);
    }
    if (!found) {
        m_address_status->SetLabel(_L("No Klipper printer answered at this address. It can still be added; its card then shows it as offline."));
        Layout();
        return;
    }
    took(*found);
}

void AddPrinterDialog::took(const PrinterDiscovery::Found &found)
{
    // TRN %1% is a printer's own name, %2% what kind of printer it is.
    m_address_status->SetLabel(format_wxstr(_L("%1% answered: %2%."), found.hostname.empty() ? _L("A printer") : from_u8(found.hostname), kind_name(found.kind)));
    // The first model that suits what the printer reports, the 0.4 nozzle before the others.
    int choice = -1;
    for (size_t i = 0; i < m_models.size(); ++i)
        if (const Preset *preset = wxGetApp().preset_bundle->printers.find_preset(m_models[i], false); preset != nullptr && suits(found.kind, *preset))
            if (choice < 0 || (boost::contains(m_models[i], "0.4") && !boost::contains(m_models[size_t(choice)], "0.4")))
                choice = int(i);
    if (choice >= 0) {
        m_model->SetSelection(choice);
        if (into_u8(m_name->GetTextCtrl()->GetValue()) == m_suggested_name) {
            m_suggested_name = m_models[size_t(choice)] + " - " + (found.hostname.empty() ? found.address : found.hostname);
            boost::replace_all(m_suggested_name, ":", " port ");
            m_name->GetTextCtrl()->SetValue(from_u8(m_suggested_name));
        }
    } else if (found.kind != PrinterDiscovery::Kind::Moonraker)
        m_address_status->SetLabel(m_address_status->GetLabel() + " " +
                                   _L("Its printer model is not among the models chosen for this installation; add the model in the printer selection first."));
    Layout();
    Fit();
}

void AddPrinterDialog::add()
{
    const wxString    title   = _L("Add printer");
    const std::string address = into_u8(m_address->GetTextCtrl()->GetValue().Trim().Trim(false));
    std::string       name    = into_u8(m_name->GetTextCtrl()->GetValue().Trim().Trim(false));
    const int         at      = m_model->GetSelection();
    PresetCollection &printers = wxGetApp().preset_bundle->printers;
    const auto refuse = [&](const wxString &why) { MessageDialog(this, why, title, wxOK | wxICON_WARNING).ShowModal(); };
    if (address.empty())
        return refuse(_L("Type the printer's address, or search the network and choose a printer that was found."));
    // The same rule the Printers side and the Monitor apply to an address: a card is only made for one they take.
    if (DeviceWorkspaceState::lan_host_from_address(address).empty())
        return refuse(_L("This is not an address on the local network. Type the printer's IP address, with a port when the printer needs one, such as "
                         "192.168.1.50 or 192.168.1.50:7125."));
    if (const Preset *had = preset_at(address))
        // TRN %1% is the name of a printer preset.
        return refuse(format_wxstr(_L("%1% already has this address, so this printer already has a card."), from_u8(had->name)));
    const Preset *base = at >= 0 && size_t(at) < m_models.size() ? printers.find_preset(m_models[size_t(at)], false) : nullptr;
    if (base == nullptr)
        return refuse(_L("Choose the printer model."));
    if (name.empty())
        return refuse(_L("Give the printer a name."));
    if (name.find_first_of("<>[]:/\\|?*\"") != std::string::npos)
        return refuse(_L("The name cannot contain any of these characters: < > [ ] : / \\ | ? * \""));
    if (printers.find_preset(name, false) != nullptr)
        // TRN %1% is the name of a printer preset.
        return refuse(format_wxstr(_L("There is already a printer preset called %1%. Give this one another name."), from_u8(name)));

    // Orca's own way of making a preset of the person's own from another (the same call its "create printer"
    // uses). It selects the preset that was selected again afterwards, which would drop changes not saved in
    // it, so the preset as it is being edited is put back.
    const Preset             edited = printers.get_edited_preset();
    std::vector<std::string> failures;
    const std::string        base_name = base->name;
    const bool made = printers.clone_presets({base}, failures, [&](Preset &preset, Preset::Type &) {
        preset.name       = name;
        preset.inherits() = base_name;
        preset.config.set_key_value("print_host", new ConfigOptionString(address));
    });
    printers.get_edited_preset() = edited;
    if (!made || printers.find_preset(name, false) == nullptr)
        return refuse(_L("The printer preset could not be made."));
    m_added = name;
    if (wxGetApp().plater() != nullptr)
        wxGetApp().plater()->sidebar().update_presets(Preset::TYPE_PRINTER);
    EndModal(wxID_OK);
}

} // namespace Slic3r::GUI
