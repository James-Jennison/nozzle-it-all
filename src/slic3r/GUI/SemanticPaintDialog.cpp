#include "SemanticPaintDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_ObjectList.hpp"
#include "GUI_Utils.hpp"
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
#include "Widgets/TextInput.hpp"
#include "format.hpp"

#include <wx/bitmap.h>
#include <wx/dcmemory.h>
#include <wx/display.h>
#include <wx/image.h>
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
                        std::vector<std::string> view_names, std::vector<SP::FilamentInfo> filaments)
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

        // Everything but the bottom buttons scrolls, so the window also fits a small screen at a large text size.
        m_body = new wxScrolledWindow(this, wxID_ANY);
        m_body->SetBackgroundColour(*wxWHITE);
        m_body->SetScrollRate(0, FromDIP(12));
        auto *body = new wxBoxSizer(wxVERTICAL);

        body->Add(new Label(m_body,
                            _L("These are the exact pictures that will be sent, together with the text shown further down. Nothing is sent until "
                               "you press Send, and nothing is set up to receive it until you choose where it goes."),
                            LB_AUTO_WRAP, wxSize(width, -1)),
                  0, wxEXPAND | wxALL, edge);

        auto *images_sizer = new wxGridSizer(3, gap, gap);
        for (size_t i = 0; i < images.size(); ++i) {
            auto *box = new wxBoxSizer(wxVERTICAL);
            // A bitmap's size is in real pixels, so the scaled size is passed, not a DIP value.
            box->Add(new wxStaticBitmap(m_body, wxID_ANY, wxBitmap(to_wx(images[i], FromDIP(120)))), 0, wxALIGN_CENTER);
            box->Add(new Label(m_body, from_u8(m_view_names[i])), 0, wxALIGN_CENTER);
            images_sizer->Add(box, 0, wxALIGN_CENTER);
        }
        body->Add(images_sizer, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);

        auto *form = new wxFlexGridSizer(2, gap, edge);
        form->AddGrowableCol(1, 1);
        const auto row = [&](const wxString &label, wxWindow *field) {
            form->Add(new Label(m_body, label), 0, wxALIGN_CENTER_VERTICAL);
            form->Add(field, 1, wxEXPAND);
        };
        const auto input = [&](long style = 0) {
            return new ::TextInput(m_body, wxEmptyString, wxEmptyString, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(290), -1), style);
        };
        m_provider = new ::ComboBox(m_body, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(290), -1), 0, nullptr, wxCB_READONLY);
        m_provider->Append(_L("Choose where to send"));
        m_provider->Append(_L("A command on this computer"));
        m_provider->Append(_L("An OpenAI-compatible server"));
        m_provider->SetToolTip(_L("No service is set up in advance. A command is any program that reads the question and prints an answer, such as "
                                  "Claude Code or a local model runner. A server is any address that speaks the OpenAI chat protocol, such as a "
                                  "local Ollama or LM Studio."));
        row(_L("Send to"), m_provider);

        m_command = input();
        m_command->SetToolTip(_L("The command line to run. {dir} is replaced by the folder holding the pictures and {model} by the model name."));
        row(_L("Command"), m_command);
        m_fill_claude = new ::Button(m_body, _L("Fill in the command for Claude Code"));
        m_fill_claude->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        m_fill_claude->SetToolTip(_L("Writes the command line that runs Claude Code, if you have it installed. It uses your own Claude subscription."));
        form->AddSpacer(0);
        form->Add(m_fill_claude, 0);

        m_endpoint = input();
        m_endpoint->GetTextCtrl()->SetHint("http://localhost:11434/v1");
        row(_L("Server address"), m_endpoint);
        m_key = input(wxTE_PASSWORD);
        row(_L("API key"), m_key);
        auto *remember_row = new wxBoxSizer(wxHORIZONTAL);
        m_remember         = new ::CheckBox(m_body);
        remember_row->Add(m_remember, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, gap);
        remember_row->Add(new Label(m_body, _L("Remember the API key in the system keychain")), 0, wxALIGN_CENTER_VERTICAL);
        form->AddSpacer(0);
        form->Add(remember_row, 0);

        m_model = input();
        row(_L("Model"), m_model);
        m_hint = input();
        m_hint->GetTextCtrl()->SetHint(_L("Optional, e.g. \"make it a festive Christmas tree\""));
        row(_L("Hint"), m_hint);
        body->Add(form, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);

        m_recipient = new Label(m_body, wxEmptyString, LB_AUTO_WRAP, wxSize(width, -1));
        body->Add(m_recipient, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        body->Add(new Label(m_body, Label::Head_14, _L("The text that is sent")), 0, wxLEFT | wxRIGHT, edge);
        m_prompt = new wxTextCtrl(m_body, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(width, FromDIP(110)),
                                  wxTE_MULTILINE | wxTE_READONLY | wxTE_DONTWRAP);
        body->Add(m_prompt, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, edge);
        body->Add(new Label(m_body,
                            _L("For a command, the pictures are written to a temporary folder and their file names are added to this text."),
                            LB_AUTO_WRAP, wxSize(width, -1)),
                  0, wxEXPAND | wxALL, edge);

        m_send = new ::Button(m_body, _L("Send"));
        m_send->SetStyle(ButtonStyle::Confirm, ButtonType::Window);
        body->Add(m_send, 0, wxLEFT | wxRIGHT | wxBOTTOM, edge);
        m_status = new Label(m_body, wxEmptyString, LB_AUTO_WRAP, wxSize(width, -1));
        body->Add(m_status, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);

        // The answer: one choice per segment, shown once there is one.
        m_result_sizer = new wxFlexGridSizer(3, gap, edge);
        m_result_sizer->AddGrowableCol(2, 1);
        body->Add(m_result_sizer, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        m_tidy_row = new wxBoxSizer(wxHORIZONTAL);
        m_tidy     = new ::CheckBox(m_body);
        m_tidy->SetValue(true);
        auto *tidy_label = new Label(m_body, _L("Tidy the colour boundaries: take back single triangles that poke into another colour"),
                                     LB_AUTO_WRAP, wxSize(width - FromDIP(30), -1));
        m_tidy_row->Add(m_tidy, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, gap);
        m_tidy_row->Add(tidy_label, 1, wxALIGN_CENTER_VERTICAL);
        body->Add(m_tidy_row, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        body->Show(m_tidy_row, false);

        m_body->SetSizer(body);
        auto *sizer = new wxBoxSizer(wxVERTICAL);
        sizer->Add(m_body, 1, wxEXPAND);
        auto *buttons = new DialogButtons(this, {L("Apply as paint"), "Cancel"}, _L("Apply as paint"));
        m_apply       = buttons->GetButtonFromIndex(0);
        m_apply->Disable();
        m_apply->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_OK); });
        buttons->GetCANCEL()->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
        sizer->Add(buttons, 0, wxEXPAND);

        load_settings();
        m_provider->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent &event) {
            update_state();
            event.Skip();
        });
        for (::TextInput *field : {m_command, m_endpoint, m_model, m_hint})
            field->GetTextCtrl()->Bind(wxEVT_TEXT, [this](wxCommandEvent &event) {
                update_state();
                event.Skip();
            });
        m_fill_claude->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { m_command->GetTextCtrl()->SetValue(k_semantic_paint_claude_code_command); });
        m_send->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_send(); });

        update_state();
        fit_to_screen();
        SetSizerAndFit(sizer);
        CenterOnParent();
        wxGetApp().UpdateDlgDarkUI(this);
    }

    ~SemanticPaintDialog() override
    {
        // Every way of closing ends here: the worker is told to stop and waited for.
        m_alive->store(false);
        m_cancel = true;
        if (m_worker.joinable())
            m_worker.join();
    }

    // Segment -> filament slot (0 = keep as it is) chosen by the user.
    std::vector<int> assignments() const
    {
        std::vector<int> out(m_seg.segments.size(), 0);
        for (size_t i = 0; i < m_choices.size() && i < out.size(); ++i)
            out[i] = std::max(0, m_choices[i]->GetSelection());
        return out;
    }

    bool tidy() const { return m_tidy->GetValue(); }

protected:
    void on_dpi_changed(const wxRect &) override
    {
        for (::TextInput *field : {m_command, m_endpoint, m_key, m_model, m_hint})
            field->Rescale();
        m_provider->Rescale();
        for (::ComboBox *choice : m_choices)
            choice->Rescale();
        m_send->Rescale();
        m_fill_claude->Rescale();
        fit_to_screen();
        GetSizer()->SetSizeHints(this);
        Refresh();
    }

private:
    // The scrolled part is as tall as its content, up to what the screen has room for.
    void fit_to_screen()
    {
        const wxSize content = m_body->GetSizer()->GetMinSize();
        const int    room    = wxDisplay(wxGetApp().mainframe ? static_cast<wxWindow *>(wxGetApp().mainframe) : this).GetClientArea().GetHeight() -
                               FromDIP(190);
        m_body->SetMinSize(wxSize(content.GetWidth() + wxSystemSettings::GetMetric(wxSYS_VSCROLL_X, m_body),
                                  std::max(FromDIP(200), std::min(content.GetHeight(), room))));
        m_body->FitInside();
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

    std::string prompt() const
    {
        return SP::build_prompt(m_seg, m_filaments, m_object_name, m_view_names, into_u8(m_hint->GetTextCtrl()->GetValue()));
    }

    // What can be typed into, where the data would go, the exact text, and whether Send can be pressed, with the reason when not.
    void update_state()
    {
        const SemanticPaintSettings s       = gather();
        const bool                  command = s.provider == "command", openai = s.provider == "openai";
        m_command->Enable(command);
        m_fill_claude->Enable(command);
        m_endpoint->Enable(openai);
        m_key->Enable(openai);
        m_remember->Enable(openai);
        m_model->Enable(command || openai);

        const wxString recipient = from_u8(semantic_paint_recipient(s));
        wxString       goes_to, why;
        if (s.provider.empty())
            why = _L("Choose where to send first. Nothing is set up in advance, and nothing can be sent until you choose.");
        else if (command && recipient.empty())
            why = _L("Enter the command to run.");
        else if (openai && recipient.empty())
            why = _L("Enter the server's address.");
        else if (openai && s.model.empty())
            why = _L("Enter the name of the model to ask.");
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
        m_send->Enable(m_can_send && !m_busy);
        if (!m_busy && (!why.empty() || !m_answered))
            set_status(why);
    }

    void set_status(const wxString &text)
    {
        m_status->SetLabel(text);
        m_status->Wrap(-1);
        m_status->Wrap(m_status->GetSize().GetWidth() > 0 ? m_status->GetSize().GetWidth() : FromDIP(430));
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

        m_busy     = true;
        m_answered = false;
        m_cancel   = false;
        m_send->Disable();
        m_apply->Disable();
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
        if (!r.ok) {
            set_status(format_wxstr(_L("Failed: %1%"), from_u8(r.error)));
            return;
        }
        const auto parsed = SP::parse_assignments(r.text, m_seg.segments.size(), m_filaments.size());
        if (!parsed) {
            set_status(_L("The answer could not be understood. Try again or pick another model."));
            return;
        }
        m_answered = true;
        populate(*parsed);
        m_apply->Enable();
        set_status(_L("Review the suggestion and change anything you like, then apply it. A part left at \"Keep as it is\" keeps the paint "
                      "it has now. Nothing changes on the model until you apply."));
    }

    void populate(const std::vector<int> &assignment)
    {
        m_result_sizer->Clear(true);
        m_choices.clear();
        const int swatch = FromDIP(14);
        for (const SP::Segment &s : m_seg.segments) {
            m_result_sizer->Add(new Label(m_body, wxString::Format("#%d", s.id)), 0, wxALIGN_CENTER_VERTICAL);
            m_result_sizer->Add(new Label(m_body, wxString::Format(_L("%.1f%% of the surface"), s.area_fraction * 100.)), 0,
                                wxALIGN_CENTER_VERTICAL);
            auto *choice = new ::ComboBox(m_body, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(220), -1), 0, nullptr, wxCB_READONLY);
            choice->Append(_L("Keep as it is"));
            for (const SP::FilamentInfo &f : m_filaments) {
                // The filament's colour beside its number; the bitmap's size is in real pixels.
                wxBitmap   colour(swatch, swatch);
                wxMemoryDC dc(colour);
                dc.SetBackground(wxBrush(wxColour(from_u8(f.color_hex))));
                dc.Clear();
                dc.SelectObject(wxNullBitmap);
                choice->Append(wxString::Format("%d  %s  %s", f.index, from_u8(f.color_hex), from_u8(SP::color_name(f.color_hex))), colour);
            }
            choice->SetSelection(s.id >= 0 && s.id < int(assignment.size()) ? assignment[s.id] : 0);
            m_choices.push_back(choice);
            m_result_sizer->Add(choice, 1, wxEXPAND);
        }
        m_body->GetSizer()->Show(m_tidy_row, true);
        wxGetApp().UpdateDarkUIWin(m_body);
        m_body->Layout();
        m_body->FitInside();
        // The answer is brought into view: it is below what was on screen while waiting.
        m_body->Scroll(0, m_body->GetVirtualSize().GetHeight());
    }

    std::string                        m_object_name;
    SP::Segmentation                   m_seg;
    std::vector<std::string>           m_png;
    std::vector<std::string>           m_view_names;
    std::vector<SP::FilamentInfo>      m_filaments;
    std::shared_ptr<std::atomic<bool>> m_alive;
    std::atomic<bool>                  m_cancel{false};
    bool                               m_busy{false};
    bool                               m_can_send{false};
    bool                               m_answered{false};
    std::thread                        m_worker;

    wxScrolledWindow *m_body{nullptr};
    ::ComboBox       *m_provider{nullptr};
    ::TextInput      *m_model{nullptr}, *m_command{nullptr}, *m_endpoint{nullptr}, *m_key{nullptr}, *m_hint{nullptr};
    ::CheckBox       *m_remember{nullptr}, *m_tidy{nullptr};
    Label            *m_status{nullptr}, *m_recipient{nullptr};
    wxTextCtrl       *m_prompt{nullptr};
    ::Button         *m_send{nullptr}, *m_apply{nullptr}, *m_fill_claude{nullptr};
    wxFlexGridSizer  *m_result_sizer{nullptr};
    wxBoxSizer       *m_tidy_row{nullptr};
    std::vector<::ComboBox *> m_choices;
};

bool can_run_semantic_paint()
{
    return wxGetApp().obj_list()->get_selected_obj_idx() >= 0;
}

void run_semantic_paint()
{
    Plater *plater = wxGetApp().plater();
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

    SemanticPaintDialog dlg(plater, object->name, seg, std::move(images), std::move(names), filaments);
    if (dlg.ShowModal() != wxID_OK)
        return;

    std::vector<int> faces = SP::face_filaments(seg, dlg.assignments());
    if (dlg.tidy())
        faces = SP::clean_paint_boundaries(combined, faces);
    {
        Plater::TakeSnapshot snapshot(plater, _u8L("AI auto-paint"));
        for (size_t p = 0; p < parts.size(); ++p) {
            // The paint the part has is loaded first, so a face left at 0 keeps it, however finely it was painted.
            TriangleSelector selector(parts[p]->mesh());
            if (!parts[p]->mmu_segmentation_facets.empty())
                selector.deserialize(parts[p]->mmu_segmentation_facets.get_data(), false);
            for (size_t f = first_face[p]; f < first_face[p + 1]; ++f)
                if (faces[f] > 0 && faces[f] <= int(EnforcerBlockerType::ExtruderMax))
                    selector.set_facet(int(f - first_face[p]), EnforcerBlockerType(faces[f]));
            parts[p]->mmu_segmentation_facets.set(selector);
        }
    }
    wxGetApp().obj_list()->update_info_items(size_t(obj_idx));
    plater->changed_object(obj_idx);
}

} // namespace GUI
} // namespace Slic3r
