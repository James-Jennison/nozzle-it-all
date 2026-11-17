#include "SemanticPaintDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_ObjectList.hpp"
#include "GUI_Utils.hpp"
#include "GLCanvas3D.hpp"
#include "Gizmos/GLGizmoAiPaint.hpp"
#include "Gizmos/GLGizmosManager.hpp"
#include "I18N.hpp"
#include "MainFrame.hpp"
#include "MsgDialog.hpp"
#include "Plater.hpp"

#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/SemanticPaint.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "slic3r/Utils/SemanticPaintClient.hpp"

#include "Widgets/Button.hpp"
#include "Widgets/CheckBox.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/StateColor.hpp"
#include "Widgets/TextInput.hpp"
#include "format.hpp"

#include <wx/bitmap.h>
#include <wx/dcmemory.h>
#include <wx/display.h>
#include <wx/image.h>
#include <wx/panel.h>
#include <wx/settings.h>
#include <wx/scrolwin.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/stattext.h>
#include <wx/textctrl.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <thread>
#include <vector>

namespace Slic3r {
namespace GUI {

namespace SP = Slic3r::SemanticPaint;

namespace {

std::vector<SP::FilamentInfo> current_filaments()
{
    std::vector<SP::FilamentInfo> out;
    const DynamicPrintConfig &cfg    = wxGetApp().preset_bundle->project_config;
    const auto *colours = cfg.option<ConfigOptionStrings>("filament_colour");
    const auto *types   = cfg.option<ConfigOptionStrings>("filament_type");
    const size_t n = colours ? colours->values.size() : 0;
    for (size_t i = 0; i < n; ++i) {
        SP::FilamentInfo f;
        f.index     = int(i) + 1;
        f.color_hex = colours->values[i];
        f.type      = types && i < types->values.size() ? types->values[i] : std::string();
        out.push_back(f);
    }
    return out;
}

wxImage to_wx(const SP::Image &img, int display)
{
    wxImage w(img.width, img.height);
    std::copy(img.rgb.begin(), img.rgb.end(), w.GetData());
    if (display != img.width)
        w.Rescale(display, display, wxIMAGE_QUALITY_HIGH);
    return w;
}

} // namespace

class SemanticPaintDialog : public DPIDialog
{
public:
    SemanticPaintDialog(wxWindow *parent, const std::string &object_name, SP::Segmentation seg, std::vector<SP::Image> images,
                        std::vector<std::string> view_names, std::vector<SP::FilamentInfo> filaments, bool has_paint)
        : DPIDialog(parent ? parent : static_cast<wxWindow *>(wxGetApp().mainframe), wxID_ANY, _L("AI auto-paint"), wxDefaultPosition, wxDefaultSize,
                    wxCAPTION | wxCLOSE_BOX | wxRESIZE_BORDER)
        , m_object_name(object_name)
        , m_seg(std::move(seg))
        , m_view_names(std::move(view_names))
        , m_filaments(std::move(filaments))
        , m_alive(std::make_shared<std::atomic<bool>>(true))
    {
        for (const SP::Image &img : images)
            m_png.push_back(SP::encode_png(img));

        SetBackgroundColour(*wxWHITE);
        SetFont(Label::Body_14);
        const int gap   = FromDIP(8);
        const int edge  = FromDIP(12);
        const int width = FromDIP(430);

        auto *sizer = new wxBoxSizer(wxVERTICAL);
        // Which step this is. The third, checking the draft, happens on the plate after this window has closed.
        m_step = new Label(this, Label::Head_14, wxEmptyString);
        sizer->Add(m_step, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, edge);

        // Everything between the heading and the bottom buttons scrolls, so the window also fits a small screen at a large text size.
        m_body = new wxScrolledWindow(this, wxID_ANY);
        m_body->SetBackgroundColour(*wxWHITE);
        m_body->SetScrollRate(0, FromDIP(12));
        auto      *body = new wxBoxSizer(wxVERTICAL);
        const auto page = [this] {
            auto *panel = new wxPanel(m_body);
            panel->SetBackgroundColour(*wxWHITE);
            return panel;
        };
        const auto text = [width](wxWindow *parent, const wxString &words) { return new Label(parent, words, LB_AUTO_WRAP, wxSize(width, -1)); };

        // Step 1: where it goes, and an optional hint. Only the fields of the chosen way of sending are shown.
        m_choose     = page();
        auto *choose = new wxBoxSizer(wxVERTICAL);
        choose->Add(text(m_choose, _L("Pictures of the model, with its surface split into numbered parts, are sent with a question to an AI "
                                      "service that you choose. No service is set up in advance, and nothing is sent in this step.")),
                    0, wxEXPAND | wxALL, edge);
        if (has_paint)
            choose->Add(text(m_choose, _L("This object already has colour painting. In the draft, a part given a filament loses the painting it "
                                          "has, and a part kept as it is keeps it.")),
                        0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, edge);

        auto *form = new wxFlexGridSizer(2, gap, edge);
        form->AddGrowableCol(1, 1);
        // shown_for: 0 always, 1 for a command, 2 for a server, 3 for both.
        const auto row = [&](const wxString &label, wxWindow *field, int shown_for, int flag = wxEXPAND) {
            auto *name = new Label(m_choose, label);
            form->Add(name, 0, wxALIGN_CENTER_VERTICAL);
            form->Add(field, 1, flag);
            m_rows.push_back({name, field, shown_for});
        };
        const auto input = [&](long style = 0) {
            return new ::TextInput(m_choose, wxEmptyString, wxEmptyString, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(290), -1), style);
        };
        m_provider = new ::ComboBox(m_choose, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(290), -1), 0, nullptr, wxCB_READONLY);
        m_provider->Append(_L("Choose where to send"));
        m_provider->Append(_L("A command on this computer"));
        m_provider->Append(_L("An OpenAI-compatible server"));
        m_provider->SetToolTip(_L("No service is set up in advance. A command is any program that reads the question and prints an answer, such as "
                                  "Claude Code or a local model runner. A server is any address that speaks the OpenAI chat protocol, such as a "
                                  "local Ollama or LM Studio."));
        row(_L("Send to"), m_provider, 0);

        m_command = input();
        m_command->SetToolTip(_L("The command line to run. {dir} is replaced by the folder holding the pictures and {model} by the model name."));
        row(_L("Command"), m_command, 1);
        m_fill_claude = new ::Button(m_choose, _L("Fill in the command for Claude Code"));
        m_fill_claude->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        m_fill_claude->SetToolTip(_L("Writes the command line that runs Claude Code, if you have it installed. It uses your own Claude subscription."));
        row(wxEmptyString, m_fill_claude, 1, 0);

        m_endpoint = input();
        m_endpoint->GetTextCtrl()->SetHint("http://localhost:11434/v1");
        row(_L("Server address"), m_endpoint, 2);
        m_key = input(wxTE_PASSWORD);
        row(_L("API key"), m_key, 2);
        auto *remember = new wxPanel(m_choose);
        remember->SetBackgroundColour(*wxWHITE);
        auto *remember_row = new wxBoxSizer(wxHORIZONTAL);
        m_remember         = new ::CheckBox(remember);
        remember_row->Add(m_remember, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, gap);
        remember_row->Add(new Label(remember, _L("Remember the API key in the system keychain")), 0, wxALIGN_CENTER_VERTICAL);
        remember->SetSizer(remember_row);
        row(wxEmptyString, remember, 2, 0);

        m_model = input();
        row(_L("Model"), m_model, 3);
        m_hint = input();
        m_hint->GetTextCtrl()->SetHint(_L("Optional, e.g. \"make it a festive Christmas tree\""));
        row(_L("Hint"), m_hint, 0);
        choose->Add(form, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        m_choose->SetSizer(choose);
        body->Add(m_choose, 0, wxEXPAND);

        // Step 2: exactly what is sent and where to, before Send can be pressed.
        m_check     = page();
        auto *check = new wxBoxSizer(wxVERTICAL);
        check->Add(text(m_check, _L("These are the exact pictures and the exact text that will be sent. Nothing is sent until you press Send.")), 0,
                   wxEXPAND | wxALL, edge);
        check->Add(text(m_check, _L("The numbered parts are found from the model's shape. They are not guaranteed to be its meaningful parts, so "
                                    "look at the pictures before sending. The answer is shown as a draft on the model itself, to check and "
                                    "correct before applying.")),
                   0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, edge);
        auto *images_sizer = new wxGridSizer(3, gap, gap);
        for (size_t i = 0; i < images.size(); ++i) {
            auto *box = new wxBoxSizer(wxVERTICAL);
            // A bitmap's size is in real pixels, so the scaled size is passed, not a DIP value.
            box->Add(new wxStaticBitmap(m_check, wxID_ANY, wxBitmap(to_wx(images[i], FromDIP(120)))), 0, wxALIGN_CENTER);
            box->Add(new Label(m_check, from_u8(m_view_names[i])), 0, wxALIGN_CENTER);
            images_sizer->Add(box, 0, wxALIGN_CENTER);
        }
        check->Add(images_sizer, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        m_recipient = text(m_check, wxEmptyString);
        check->Add(m_recipient, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        check->Add(new Label(m_check, Label::Head_14, _L("The text that is sent")), 0, wxLEFT | wxRIGHT, edge);
        m_prompt = new wxTextCtrl(m_check, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(width, FromDIP(110)),
                                  wxTE_MULTILINE | wxTE_READONLY | wxTE_DONTWRAP);
        check->Add(m_prompt, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, edge);
        check->Add(text(m_check, _L("For a command, the pictures are written to a temporary folder and their file names are added to this text.")),
                   0, wxEXPAND | wxALL, edge);
        m_check->SetSizer(check);
        body->Add(m_check, 0, wxEXPAND);

        m_body->SetSizer(body);
        sizer->Add(m_body, 1, wxEXPAND);
        // Below the scrolled part and always in view: what is going on or why the next step cannot be taken, then the buttons.
        m_status = text(this, wxEmptyString);
        sizer->Add(m_status, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, edge);
        m_buttons = new DialogButtons(this, {L("Return"), L("Show what will be sent"), L("Send"), "Cancel"});
        m_back    = m_buttons->GetButtonFromIndex(0);
        m_next    = m_buttons->GetButtonFromIndex(1);
        m_send    = m_buttons->GetButtonFromIndex(2);
        m_buttons->GetCANCEL()->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
        sizer->Add(m_buttons, 0, wxEXPAND);

        load_settings();
        m_provider->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent &event) {
            show_fields();
            update_state();
            event.Skip();
        });
        for (::TextInput *field : {m_command, m_endpoint, m_key, m_model, m_hint})
            field->GetTextCtrl()->Bind(wxEVT_TEXT, [this](wxCommandEvent &event) {
                update_state();
                event.Skip();
            });
        m_fill_claude->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { m_command->GetTextCtrl()->SetValue(k_semantic_paint_claude_code_command); });
        m_back->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) {
            if (!m_busy)
                show_step(1);
        });
        m_next->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) {
            if (m_can_send)
                show_step(2);
        });
        m_send->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_send(); });

        show_fields();
        show_step(1);
        SetSizerAndFit(sizer);
        CenterOnParent();
        wxGetApp().UpdateDlgDarkUI(this);
        // The text that is sent is there to be read: full contrast in both themes, though it cannot be edited.
        m_prompt->SetBackgroundColour(StateColor::darkModeColorFor(*wxWHITE));
        m_prompt->SetForegroundColour(StateColor::darkModeColorFor(wxColour("#262E30")));
    }

    ~SemanticPaintDialog() override
    {
        // Every way of closing ends here: the worker is told to stop and waited for.
        m_alive->store(false);
        m_cancel = true;
        if (m_worker.joinable())
            m_worker.join();
    }

    // The filament suggested for each part (0 = keep as it is), once an answer was read.
    const std::vector<int> &suggested() const { return m_suggested; }

protected:
    void on_dpi_changed(const wxRect &) override
    {
        for (::TextInput *field : {m_command, m_endpoint, m_key, m_model, m_hint})
            field->Rescale();
        m_provider->Rescale();
        m_fill_claude->Rescale();
        refit();
        Refresh();
    }

private:
    // The scrolled part is as tall as its content, up to what the screen has room for.
    void fit_to_screen()
    {
        const wxSize content = m_body->GetSizer()->GetMinSize();
        const int    room    = wxDisplay(wxGetApp().mainframe ? static_cast<wxWindow *>(wxGetApp().mainframe) : this).GetClientArea().GetHeight() -
                               FromDIP(230);
        m_body->SetMinSize(wxSize(content.GetWidth() + wxSystemSettings::GetMetric(wxSYS_VSCROLL_X, m_body),
                                  std::max(FromDIP(200), std::min(content.GetHeight(), room))));
        m_body->Layout();
        m_body->FitInside();
    }

    // The window is as tall as the step it shows needs.
    void refit()
    {
        fit_to_screen();
        if (GetSizer() != nullptr)
            GetSizer()->SetSizeHints(this);
    }

    // Only the fields of the chosen way of sending are shown.
    void show_fields()
    {
        const int at = m_provider->GetSelection(); // 1 a command, 2 a server
        for (const Row &row : m_rows) {
            const bool show = row.shown_for == 0 || (at > 0 && (row.shown_for & at) != 0);
            row.name->Show(show);
            row.field->Show(show);
        }
        refit();
    }

    // Step 1 chooses where to send, step 2 shows what is sent. The third step, the draft, is on the plate.
    void show_step(int step)
    {
        m_choose->Show(step == 1);
        m_check->Show(step == 2);
        m_step->SetLabel(step == 1 ? _L("Step 1 of 3: choose where to send") : _L("Step 2 of 3: check what is sent"));
        m_back->Show(step == 2);
        m_next->Show(step == 1);
        m_send->Show(step == 2);
        m_buttons->SetPrimaryButton(step == 1 ? m_next->GetLabel() : m_send->GetLabel());
        m_buttons->UpdateButtons();
        m_body->Scroll(0, 0);
        update_state();
        refit();
    }

    void load_settings()
    {
        AppConfig        *cfg      = wxGetApp().app_config;
        const std::string provider = cfg->get("semantic_paint", "provider");
        m_provider->SetSelection(provider == "openai" ? 2 : provider == "command" ? 1 : 0);
        m_command->GetTextCtrl()->SetValue(from_u8(cfg->get("semantic_paint", "command")));
        m_endpoint->GetTextCtrl()->SetValue(from_u8(cfg->get("semantic_paint", "endpoint")));
        m_model->GetTextCtrl()->SetValue(from_u8(cfg->get("semantic_paint", "model")));
        const std::string key = semantic_paint_load_api_key();
        m_key->GetTextCtrl()->SetValue(from_u8(key));
        m_remember->SetValue(!key.empty());
    }

    void save_settings(const SemanticPaintSettings &s)
    {
        AppConfig *cfg = wxGetApp().app_config;
        cfg->set("semantic_paint", "provider", s.provider);
        cfg->set("semantic_paint", "command", s.command);
        cfg->set("semantic_paint", "endpoint", s.endpoint);
        cfg->set("semantic_paint", "model", s.model);
        if (s.provider == "openai")
            semantic_paint_save_api_key(m_remember->GetValue() ? s.api_key : std::string());
    }

    SemanticPaintSettings gather() const
    {
        SemanticPaintSettings s;
        const int             at = m_provider->GetSelection();
        s.provider = at == 2 ? "openai" : at == 1 ? "command" : "";
        s.command  = into_u8(m_command->GetTextCtrl()->GetValue());
        s.endpoint = into_u8(m_endpoint->GetTextCtrl()->GetValue().Trim().Trim(false));
        s.api_key  = into_u8(m_key->GetTextCtrl()->GetValue());
        s.model    = into_u8(m_model->GetTextCtrl()->GetValue().Trim().Trim(false));
        return s;
    }

    // The question with the standing guidance and the user's own hint; the hint is said to come first.
    std::string prompt() const
    {
        const std::string guidance =
            "Prefer a coherent, natural palette. Segment tints are identification labels, not suggested paint colours. Do not use every "
            "available filament merely because it is available. Use accent colours only when the views clearly support them; do not invent "
            "decorative patches. A geometric segment may cross a semantic feature: when uncertain keep the dominant body colour. Keep "
            "matching features consistent across all views. The user's requested style takes precedence.";
        const std::string hint = into_u8(m_hint->GetTextCtrl()->GetValue().Trim().Trim(false));
        return SP::build_prompt(m_seg, m_filaments, m_object_name, m_view_names, hint.empty() ? guidance : guidance + "\n" + hint);
    }

    // What can be typed into, where the data would go, the exact text, and whether Send can be pressed, with the reason when not.
    void update_state()
    {
        const SemanticPaintSettings s       = gather();
        const bool                  command = s.provider == "command", openai = s.provider == "openai";
        const wxString recipient = from_u8(semantic_paint_recipient(s));
        wxString       goes_to, why;
        if (s.provider.empty())
            why = _L("Choose where to send first. Nothing is set up in advance, and nothing can be sent until you choose.");
        else if (command && recipient.empty())
            why = _L("Enter the command to run.");
        else if (openai && recipient.empty())
            why = _L("Enter the server's address.");
        else if (openai && s.model.empty())
            why = _L("Enter the name of the model to ask. It must be one that accepts pictures.");
        else if (!semantic_paint_not_ready(s).empty())
            why = _L("A server that takes an API key must be reached over HTTPS, so the key is never sent in the clear.");
        if (command && !recipient.empty())
            goes_to = s.model.empty() ?
                format_wxstr(_L("Goes to: the program \"%1%\" on this computer. Where that program sends it, and which model answers, is "
                                "decided by the program and how you have set it up."), recipient) :
                format_wxstr(_L("Goes to: the program \"%1%\" on this computer, asked for the model \"%2%\". Where that program sends it is "
                                "decided by the program and how you have set it up."), recipient, from_u8(s.model));
        else if (openai && !recipient.empty())
            goes_to = s.model.empty() ? format_wxstr(_L("Goes to: the server at %1%."), recipient) :
                                        format_wxstr(_L("Goes to: the server at %1%, model \"%2%\"."), recipient, from_u8(s.model));
        else
            goes_to = _L("Goes to: nowhere yet.");
        m_recipient->SetLabel(goes_to);
        m_recipient->Wrap(-1);
        m_recipient->Wrap(m_recipient->GetSize().GetWidth() > 0 ? m_recipient->GetSize().GetWidth() : FromDIP(430));
        m_prompt->ChangeValue(from_u8(prompt()));
        m_can_send = why.empty();
        m_next->Enable(m_can_send && !m_busy);
        m_send->Enable(m_can_send && !m_busy);
        m_back->Enable(!m_busy);
        if (!m_busy)
            set_status(why);
    }

    void set_status(const wxString &text)
    {
        m_status->SetLabel(text);
        m_status->Wrap(-1);
        m_status->Wrap(m_status->GetSize().GetWidth() > 0 ? m_status->GetSize().GetWidth() : FromDIP(430));
        if (GetSizer() != nullptr)
            Layout();
        m_body->Layout();
        m_body->FitInside();
    }

    void on_send()
    {
        const SemanticPaintSettings s = gather();
        if (m_busy || !semantic_paint_not_ready(s).empty())
            return;
        save_settings(s);
        const std::string text = prompt();

        m_busy   = true;
        m_cancel = false;
        m_send->Disable();
        m_back->Disable();
        set_status(_L("Waiting for the answer... this can take up to a few minutes."));
        if (m_worker.joinable())
            m_worker.join();
        auto alive = m_alive;
        m_worker   = std::thread([this, alive, s, text] {
            SemanticPaintReply r = semantic_paint_query(s, text, m_png, &m_cancel);
            wxGetApp().CallAfter([this, alive, r = std::move(r)] {
                if (alive->load())
                    on_reply(r);
            });
        });
    }

    void on_reply(const SemanticPaintReply &r)
    {
        m_busy = false;
        m_send->Enable(m_can_send);
        m_back->Enable();
        if (!r.ok) {
            set_status(format_wxstr(_L("Failed: %1%"), from_u8(r.error)));
            return;
        }
        const auto parsed = SP::parse_assignments(r.text, m_seg.segments.size(), m_filaments.size());
        if (!parsed) {
            set_status(_L("The answer could not be understood. Try again or pick another model."));
            return;
        }
        // The answer goes to the plate: the window closes and the draft is shown on the model itself.
        m_suggested = *parsed;
        EndModal(wxID_OK);
    }

    std::string                        m_object_name;
    SP::Segmentation                   m_seg;
    std::vector<int>                   m_suggested;
    std::vector<std::string>           m_png;
    std::vector<std::string>           m_view_names;
    std::vector<SP::FilamentInfo>      m_filaments;
    std::shared_ptr<std::atomic<bool>> m_alive;
    std::atomic<bool>                  m_cancel{false};
    bool                               m_busy{false};
    bool                               m_can_send{false};
    std::thread                        m_worker;

    struct Row
    {
        wxWindow *name, *field;
        int       shown_for;
    };
    std::vector<Row>  m_rows;
    wxScrolledWindow *m_body{nullptr};
    wxPanel          *m_choose{nullptr}, *m_check{nullptr};
    DialogButtons    *m_buttons{nullptr};
    ::ComboBox       *m_provider{nullptr};
    ::TextInput      *m_model{nullptr}, *m_command{nullptr}, *m_endpoint{nullptr}, *m_key{nullptr}, *m_hint{nullptr};
    ::CheckBox       *m_remember{nullptr};
    Label            *m_step{nullptr}, *m_status{nullptr}, *m_recipient{nullptr};
    wxTextCtrl       *m_prompt{nullptr};
    ::Button         *m_back{nullptr}, *m_next{nullptr}, *m_send{nullptr}, *m_fill_claude{nullptr};
};

bool can_run_semantic_paint()
{
    return wxGetApp().obj_list()->get_selected_obj_idx() >= 0;
}

void run_semantic_paint()
{
    Plater *plater = wxGetApp().plater();
    // Whatever tool is open is closed first, this one included (opened from its button it has no draft yet), and it is
    // opened again only with an answer to show.
    GLGizmosManager &open_tools = plater->get_view3D_canvas3D()->get_gizmos_manager();
    if (open_tools.get_current_type() != GLGizmosManager::EType::Undefined) {
        open_tools.reset_all_states();
        plater->get_view3D_canvas3D()->set_as_dirty();
    }
    const int obj_idx = wxGetApp().obj_list()->get_selected_obj_idx();
    Model &model = plater->model();
    if (obj_idx < 0 || obj_idx >= int(model.objects.size())) {
        show_info(plater, _L("Select an object first."), _L("AI auto-paint"));
        return;
    }
    ModelObject *object = model.objects[obj_idx];
    std::vector<ModelVolume *> parts;
    for (ModelVolume *v : object->volumes)
        if (v->is_model_part())
            parts.push_back(v);
    if (parts.empty())
        return;

    const std::vector<SP::FilamentInfo> filaments = current_filaments();
    if (filaments.size() < 2) {
        show_info(plater, _L("Add at least two filaments first; there is nothing to choose between with one."), _L("AI auto-paint"));
        return;
    }
    // All parts go into one mesh (volume transforms applied, instance ignored). Parts share no vertices, so a segment never
    // spans two parts, and each part owns a contiguous range of faces that is used to split the answer back up.
    indexed_triangle_set       combined;
    std::vector<size_t>        first_face;
    for (ModelVolume *v : parts) {
        TriangleMesh mesh = v->mesh();
        mesh.transform(v->get_matrix());
        const int offset = int(combined.vertices.size());
        first_face.push_back(combined.indices.size());
        combined.vertices.insert(combined.vertices.end(), mesh.its.vertices.begin(), mesh.its.vertices.end());
        for (const stl_triangle_vertex_indices &t : mesh.its.indices)
            combined.indices.emplace_back(t(0) + offset, t(1) + offset, t(2) + offset);
    }
    first_face.push_back(combined.indices.size());

    const SP::Segmentation seg = SP::segment_mesh(combined);
    std::vector<SP::Image>   images;
    std::vector<std::string> names;
    for (const SP::View &v : SP::default_views()) {
        images.push_back(SP::render_view(combined, seg, v));
        names.push_back(v.name);
    }

    const bool has_paint = std::any_of(parts.begin(), parts.end(), [](const ModelVolume *v) { return !v->mmu_segmentation_facets.empty(); });
    std::vector<int> suggested;
    {
        SemanticPaintDialog dlg(plater, object->name, seg, std::move(images), std::move(names), filaments, has_paint);
        if (dlg.ShowModal() != wxID_OK)
            return;
        suggested = dlg.suggested();
    }

    // The draft is shown on the plate by its own tool, which writes to the model only when the user applies it.
    GLGizmosManager &gizmos = plater->get_view3D_canvas3D()->get_gizmos_manager();
    auto            *tool   = dynamic_cast<GLGizmoAiPaint *>(gizmos.get_gizmo(GLGizmosManager::EType::AiPaint));
    if (tool == nullptr)
        return;
    GLGizmoAiPaint::Draft draft;
    draft.object_idx = obj_idx;
    draft.object     = object;
    draft.mesh       = std::move(combined);
    draft.first_face = std::move(first_face);
    draft.seg        = seg;
    draft.filaments  = filaments;
    draft.suggested  = std::move(suggested);
    tool->start(std::move(draft));
    gizmos.open_gizmo(GLGizmosManager::EType::AiPaint);
    plater->get_view3D_canvas3D()->set_as_dirty();
}

} // namespace GUI
} // namespace Slic3r
