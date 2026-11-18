#include "GLGizmoAiPaint.hpp"

#include "GLGizmosManager.hpp"
#include "slic3r/GUI/Camera.hpp"
#include "slic3r/GUI/GLCanvas3D.hpp"
#include "slic3r/GUI/GLShader.hpp"
#include "slic3r/GUI/GLTexture.hpp"
#include "slic3r/GUI/GUI.hpp"
#include "slic3r/GUI/GUI_App.hpp"
#include "slic3r/GUI/GUI_ObjectList.hpp"
#include "slic3r/GUI/I18N.hpp"
#include "slic3r/GUI/ImGuiWrapper.hpp"
#include "slic3r/GUI/MeshUtils.hpp"
#include "slic3r/GUI/Plater.hpp"
#include "slic3r/GUI/Preferences.hpp"
#include "slic3r/GUI/SemanticPaintDialog.hpp"
#include "slic3r/GUI/format.hpp"

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Color.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include <boost/format.hpp>
#include <imgui/imgui.h>
#include <imgui/imgui_stdlib.h> // std::string for text fields
#include <wx/display.h>
#include <wx/event.h>
#include <wx/glcanvas.h>
#include <wx/utils.h>

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <thread>

namespace Slic3r {
namespace GUI {

namespace SP = Slic3r::SemanticPaint;

namespace {
constexpr auto k_flash = std::chrono::milliseconds(1200); // how long a picked part is shown in the highlight colour
const ColorRGBA k_highlight{1.f, 0.f, 0.9f, 1.f};
const ImVec4    k_warning{0.95f, 0.6f, 0.1f, 1.f};
constexpr int   k_view_size = 768; // the pictures' side in pixels; the service gives a marked feature's place in these

// Standing guidance that goes with every question, ahead of the user's own hint.
const char *const k_guidance =
    "Prefer a coherent, natural palette. Segment tints are identification labels, not suggested paint colours. Do not use every "
    "available filament merely because it is available. Use accent colours only when the views clearly support them; do not invent "
    "decorative patches. A geometric segment may cross a semantic feature: when uncertain keep the dominant body colour. Keep "
    "matching features consistent across all views. The user's requested style takes precedence.";

std::vector<SP::FilamentInfo> current_filaments()
{
    std::vector<SP::FilamentInfo> out;
    const DynamicPrintConfig     &cfg     = wxGetApp().preset_bundle->project_config;
    const auto                   *colours = cfg.option<ConfigOptionStrings>("filament_colour");
    const auto                   *types   = cfg.option<ConfigOptionStrings>("filament_type");
    const size_t                  n       = colours ? colours->values.size() : 0;
    for (size_t i = 0; i < n; ++i) {
        SP::FilamentInfo f;
        f.index     = int(i) + 1;
        f.color_hex = colours->values[i];
        f.type      = types && i < types->values.size() ? types->values[i] : std::string();
        out.push_back(f);
    }
    return out;
}

// The selected object's printable parts, or none.
std::vector<ModelVolume *> selected_parts(int *object_idx = nullptr)
{
    std::vector<ModelVolume *> out;
    const int                  idx   = wxGetApp().obj_list()->get_selected_obj_idx();
    Model                     &model = wxGetApp().model();
    if (object_idx != nullptr)
        *object_idx = idx;
    if (idx < 0 || idx >= int(model.objects.size()))
        return out;
    for (ModelVolume *volume : model.objects[size_t(idx)]->volumes)
        if (volume->is_model_part())
            out.push_back(volume);
    return out;
}
} // namespace

GLGizmoAiPaint::GLGizmoAiPaint(GLCanvas3D &parent, const std::string &icon_filename, unsigned int sprite_id)
    : GLGizmoBase(parent, icon_filename, sprite_id), m_alive(std::make_shared<std::atomic<bool>>(true))
{}

GLGizmoAiPaint::~GLGizmoAiPaint()
{
    // A question still out is told to stop, and its answer finds nobody to give it to.
    m_alive->store(false);
    if (m_cancel)
        m_cancel->store(true);
}

std::string GLGizmoAiPaint::on_get_name() const { return _u8L("AI auto-paint"); }

bool GLGizmoAiPaint::on_is_activable() const { return m_draft != nullptr || can_run_semantic_paint(); }

CommonGizmosDataID GLGizmoAiPaint::on_get_requirements() const { return CommonGizmosDataID(int(CommonGizmosDataID::SelectionInfo)); }

wxString GLGizmoAiPaint::not_ready() const
{
    const SemanticPaintSettings &s       = m_settings;
    const bool                   command = s.provider == "command", openai = s.provider == "openai";
    const bool                   nowhere = semantic_paint_recipient(s).empty();
    if (selected_parts().empty())
        return _L("Select one object first.");
    if (current_filaments().size() < 2)
        return _L("Add at least two filaments first; there is nothing to choose between with one.");
    if (s.provider.empty())
        return _L("No AI service is set up, so nothing can be sent. Choose one under AI settings.");
    if (command && nowhere)
        return _L("No command is set. Enter one under AI settings.");
    if (openai && nowhere)
        return _L("No server address is set. Enter one under AI settings.");
    if (openai && s.model.empty())
        return _L("No model is set. Choose one under AI settings. It must be one that accepts pictures.");
    if (!semantic_paint_not_ready(s).empty())
        return _L("A server that takes an API key must be reached over HTTPS, so the key is never sent in the clear. Change this under AI settings.");
    return {};
}

wxString GLGizmoAiPaint::goes_to() const
{
    const SemanticPaintSettings &s         = m_settings;
    const wxString               recipient = from_u8(semantic_paint_recipient(s));
    if (s.provider == "command" && !recipient.empty())
        return s.model.empty() ?
            format_wxstr(_L("Goes to: the program \"%1%\" on this computer. Where that program sends it, and which model answers, is "
                            "decided by the program and how you have set it up."), recipient) :
            format_wxstr(_L("Goes to: the program \"%1%\" on this computer, asked for the model \"%2%\". Where that program sends it is "
                            "decided by the program and how you have set it up."), recipient, from_u8(s.model));
    if (s.provider == "openai" && !recipient.empty())
        return s.model.empty() ? format_wxstr(_L("Goes to: the server at %1%."), recipient) :
                                 format_wxstr(_L("Goes to: the server at %1%, model \"%2%\"."), recipient, from_u8(s.model));
    return _L("Goes to: nowhere yet.");
}

void GLGizmoAiPaint::prepare()
{
    if (m_busy || !not_ready().empty())
        return;
    int                              object_idx = -1;
    const std::vector<ModelVolume *> volumes    = selected_parts(&object_idx);
    if (volumes.empty())
        return;
    wxBusyCursor wait;
    auto         draft = std::make_unique<Draft>();
    draft->object_idx  = object_idx;
    draft->object      = wxGetApp().model().objects[size_t(object_idx)];
    draft->filaments   = current_filaments();
    // All parts go into one mesh (volume transforms applied, instance ignored). Parts share no vertices, so a segment never
    // spans two parts, and each part owns a contiguous range of faces that is used to split the answer back up.
    for (const ModelVolume *volume : volumes) {
        TriangleMesh mesh = volume->mesh();
        mesh.transform(volume->get_matrix());
        const int offset = int(draft->mesh.vertices.size());
        draft->first_face.push_back(draft->mesh.indices.size());
        draft->mesh.vertices.insert(draft->mesh.vertices.end(), mesh.its.vertices.begin(), mesh.its.vertices.end());
        for (const stl_triangle_vertex_indices &t : mesh.its.indices)
            draft->mesh.indices.emplace_back(t(0) + offset, t(1) + offset, t(2) + offset);
    }
    draft->first_face.push_back(draft->mesh.indices.size());
    draft->seg = SP::segment_mesh(draft->mesh);
    auto png   = std::make_shared<std::vector<std::string>>();
    for (const SP::View &view : SP::default_views()) {
        draft->labelled.emplace_back();
        draft->images.push_back(SP::render_view(draft->mesh, draft->seg, view, k_view_size, &draft->labelled.back()));
        draft->view_names.push_back(view.name);
        png->push_back(SP::encode_png(draft->images.back()));
    }
    draft->png = std::move(png);
    // The question with the standing guidance and the user's own hint; the hint is said to come first.
    const std::string hint = into_u8(from_u8(m_hint).Trim().Trim(false));
    draft->prompt = SP::build_prompt(draft->seg, draft->filaments, draft->object->name, draft->view_names,
                                     hint.empty() ? std::string(k_guidance) : std::string(k_guidance) + "\n" + hint, draft->labelled, k_view_size);
    m_textures.clear();
    m_draft = std::move(draft);
    m_status.clear();
    m_step = Step::Review;
}

void GLGizmoAiPaint::send()
{
    if (m_busy || m_draft == nullptr || m_step != Step::Review || !semantic_paint_not_ready(m_settings).empty())
        return;
    m_busy = true;
    m_status.clear();
    const unsigned asked = ++m_asked;
    auto           cancel = std::make_shared<std::atomic<bool>>(false);
    m_cancel              = cancel;
    // The thread holds nothing of this tool but what it was given; the answer comes back on the main thread and is
    // dropped when the tool is gone or the question was given up meanwhile.
    std::thread([this, alive = m_alive, cancel, asked, settings = m_settings, text = m_draft->prompt, png = m_draft->png] {
        SemanticPaintReply reply = semantic_paint_query(settings, text, *png, cancel.get());
        wxGetApp().CallAfter([this, alive, asked, reply = std::move(reply)] {
            if (alive->load() && asked == m_asked)
                on_reply(reply);
        });
    }).detach();
}

void GLGizmoAiPaint::stop_waiting()
{
    ++m_asked;
    if (m_cancel)
        m_cancel->store(true);
    m_busy = false;
}

void GLGizmoAiPaint::on_reply(const SemanticPaintReply &reply)
{
    m_busy   = false;
    m_settle = 3;
    m_parent.set_as_dirty();
    m_parent.request_extra_frame();
    if (m_draft == nullptr || m_step != Step::Review)
        return;
    if (!reply.ok) {
        m_status = format_wxstr(_L("Failed: %1%"), from_u8(reply.error));
        return;
    }
    auto parsed = SP::parse_suggestion(reply.text, m_draft->seg.segments.size(), m_draft->filaments.size(), m_draft->view_names);
    if (!parsed) {
        m_status = _L("The answer could not be understood. Try again or choose another model under AI settings.");
        return;
    }
    // A part that no picture numbers was not asked about: it takes the filament of the part around it.
    parsed->filament = SP::follow_neighbours(m_draft->mesh, m_draft->seg, std::move(parsed->filament),
                                             SP::unseen_segments(m_draft->seg.segments.size(), m_draft->labelled));
    // A feature the service marked in the pictures becomes a part of its own, after the numbered ones.
    m_draft->first_spot = m_draft->seg.segments.size();
    m_draft->face_part  = m_draft->seg.face_segment;
    m_draft->patches    = SP::apply_spots(m_draft->mesh, m_draft->seg, *parsed, SP::default_views(), k_view_size);
    m_draft->suggested = std::move(parsed->filament);
    m_draft->suggested.resize(m_draft->seg.segments.size(), 0);
    m_draft->notes = std::move(parsed->notes);
    m_draft->names   = std::move(parsed->name);
    m_draft->unsure  = std::move(parsed->unsure);
    m_draft->subject = std::move(parsed->object);
    m_choice         = m_draft->suggested;
    m_tidy           = true;
    m_show_original  = false;
    m_show_views     = false;
    m_selected       = -1;
    m_flash_until    = {};
    m_raycaster      = std::make_unique<MeshRaycaster>(TriangleMesh(m_draft->mesh));
    m_selectors.clear();
    m_dirty = true;
    m_step  = Step::Draft;
}

void GLGizmoAiPaint::open_settings()
{
    // The settings are a window of their own, opened once this frame is drawn. They can rebuild the whole interface
    // (a language change does), so this tool is touched afterwards only if it is still there.
    wxGetApp().CallAfter([this, alive = m_alive] {
        wxGetApp().open_preferences(PreferencesTab::AI, "ai_services_provider");
        if (alive->load()) {
            m_settings = semantic_paint_load_settings(*wxGetApp().app_config);
            m_settle   = 3;
            m_parent.set_as_dirty();
        }
    });
}

std::vector<ModelVolume *> GLGizmoAiPaint::parts() const
{
    std::vector<ModelVolume *> out;
    if (m_draft == nullptr)
        return out;
    const Model &model = wxGetApp().model();
    if (m_draft->object_idx < 0 || size_t(m_draft->object_idx) >= model.objects.size() || model.objects[size_t(m_draft->object_idx)] != m_draft->object)
        return out;
    for (ModelVolume *volume : model.objects[size_t(m_draft->object_idx)]->volumes)
        if (volume->is_model_part())
            out.push_back(volume);
    // The same parts with the same faces, or the draft no longer belongs to this object.
    if (out.size() + 1 != m_draft->first_face.size())
        return {};
    for (size_t p = 0; p < out.size(); ++p)
        if (out[p]->mesh().its.indices.size() != m_draft->first_face[p + 1] - m_draft->first_face[p])
            return {};
    return out;
}

std::vector<int> GLGizmoAiPaint::faces(size_t *tidied) const
{
    // The parts as they were split, without the marked features: those are painted over this as round patches.
    std::vector<int> out(m_draft->face_part.size(), 0);
    for (size_t f = 0; f < out.size(); ++f)
        if (const int part = m_draft->face_part[f]; part >= 0 && size_t(part) < m_choice.size())
            out[f] = m_choice[size_t(part)];
    size_t           changed = 0;
    if (m_tidy) {
        const std::vector<int> cleaned = SP::clean_paint_boundaries(m_draft->mesh, out);
        for (size_t f = 0; f < out.size(); ++f)
            changed += cleaned[f] != out[f];
        out = cleaned;
    }
    if (tidied != nullptr)
        *tidied = changed;
    return out;
}

void GLGizmoAiPaint::rebuild()
{
    m_dirty = false;
    m_selectors.clear();
    const std::vector<ModelVolume *> volumes = parts();
    if (volumes.empty())
        return;
    const std::vector<int> face_filament = faces(&m_tidied);
    m_assigned = size_t(std::count_if(face_filament.begin(), face_filament.end(), [](int slot) { return slot > 0; }));

    const std::vector<ColorRGBA> extruders = wxGetApp().plater()->get_extruders_colors();
    const size_t                 slots     = std::min(extruders.size(), size_t(EnforcerBlockerType::ExtruderMax));
    // A picked part is shown for a moment in a colour of its own, where there is a free state for one.
    const bool   flash           = m_selected >= 0 && std::chrono::steady_clock::now() < m_flash_until && slots < size_t(EnforcerBlockerType::ExtruderMax);
    const size_t highlight_state = slots + 1;
    m_flash_drawn                = flash;

    for (size_t p = 0; p < volumes.size(); ++p) {
        const ModelVolume *volume = volumes[p];
        size_t             own    = volume->extruder_id() > 0 ? size_t(volume->extruder_id() - 1) : 0;
        if (own >= extruders.size())
            own = 0;
        std::vector<ColorRGBA> colors;
        colors.push_back(extruders.empty() ? ColorRGBA{0.7f, 0.7f, 0.7f, 1.f} : extruders[own]);
        colors.insert(colors.end(), extruders.begin(), extruders.begin() + slots);
        if (flash)
            colors.push_back(k_highlight);

        auto selector = std::make_unique<TriangleSelectorPatch>(volume->mesh(), colors, 0.2f);
        // The paint the part has is loaded first: a face kept as it is shows it, however finely it was painted.
        if (!volume->mmu_segmentation_facets.empty())
            selector->deserialize(volume->mmu_segmentation_facets.get_data(), false, EnforcerBlockerType(slots));
        const size_t first = m_draft->first_face[p];
        const bool flash_part = flash && size_t(m_selected) < m_draft->first_spot;
        for (size_t f = first; f < m_draft->first_face[p + 1]; ++f) {
            if (flash_part && m_draft->face_part[f] == m_selected)
                selector->set_facet(int(f - first), EnforcerBlockerType(highlight_state));
            else if (!m_show_original && face_filament[f] > 0 && size_t(face_filament[f]) <= slots)
                selector->set_facet(int(f - first), EnforcerBlockerType(face_filament[f]));
        }
        // The marked features, in order: a later one goes over an earlier one. A feature the fill found edges for is
        // painted face by face: its outline is the model's own edges, and nothing beside it is touched. One with no
        // edges is painted as a ball with the round brush's own means, which split triangles along its edge, so that
        // its outline is a circle and not the triangles' zigzag.
        const Transform3d to_object = volume->get_matrix();
        Transform3d       no_move   = to_object;
        no_move.translation()       = Vec3d::Zero();
        const size_t      last      = m_draft->first_face[p + 1];
        for (const SP::SpotPatch &patch : m_draft->patches) {
            if (patch.seed_face < int(first) || patch.seed_face >= int(last) || size_t(patch.segment) >= m_choice.size())
                continue;
            const int  slot  = m_choice[size_t(patch.segment)];
            const bool shown = flash && patch.segment == m_selected;
            if (!shown && (m_show_original || slot <= 0 || size_t(slot) > slots))
                continue;
            const auto state = EnforcerBlockerType(shown ? highlight_state : size_t(slot));
            if (!patch.excluded.empty()) {
                for (const int f : patch.faces)
                    if (f >= int(first) && f < int(last))
                        selector->set_facet(f - int(first), state);
                continue;
            }
            const Vec3f center = (to_object.inverse() * patch.center.cast<double>()).cast<float>();
            selector->select_patch(patch.seed_face - int(first),
                                   std::make_unique<TriangleSelector::Sphere>(center, Vec3f(center + Vec3f::UnitZ()), patch.radius, to_object,
                                                                              TriangleSelector::ClippingPlane()),
                                   state, no_move, true);
        }
        selector->request_update_render_data();
        m_selectors.push_back(std::move(selector));
    }
}

void GLGizmoAiPaint::on_set_state()
{
    if (m_state == Off) {
        m_parent.toggle_model_objects_visibility(true);
        stop_waiting();
        m_selectors.clear();
        m_raycaster.reset();
        m_textures.clear();
        m_draft.reset();
        m_hint.clear();
        m_status.clear();
        m_step       = Step::Ask;
        m_show_views = false;
        m_pressed    = false;
    } else if (m_state == On) {
        // It opens at its first step, with the AI settings as they are now.
        m_step     = Step::Ask;
        m_settings = semantic_paint_load_settings(*wxGetApp().app_config);
        m_dirty    = true;
        m_parent.set_as_dirty();
    }
}

void GLGizmoAiPaint::data_changed(bool)
{
    // The model changed under the draft (an undo, a part removed, the object deleted): a draft that no longer fits is dropped.
    if (m_state == On && m_draft != nullptr && parts().empty())
        close();
    else
        m_dirty = true;
}

void GLGizmoAiPaint::close()
{
    // Opening the tool that is open closes it.
    GLGizmosManager &gizmos = m_parent.get_gizmos_manager();
    if (gizmos.get_current_type() == GLGizmosManager::EType::AiPaint)
        gizmos.open_gizmo(GLGizmosManager::EType::AiPaint);
}

void GLGizmoAiPaint::on_render()
{
    if (m_draft == nullptr || m_step != Step::Draft)
        return;
    const std::vector<ModelVolume *> volumes = parts();
    if (volumes.empty())
        return;
    if (m_flash_drawn && std::chrono::steady_clock::now() >= m_flash_until)
        m_dirty = true;
    if (m_dirty || m_selectors.size() != volumes.size())
        rebuild();
    if (m_selectors.size() != volumes.size())
        return;
    if (m_flash_drawn)
        m_parent.request_extra_frame(); // so the highlight is taken away when its time is up

    // The object's own drawing is replaced by the draft's while the tool is open.
    const ModelObject *object = m_draft->object;
    m_parent.toggle_model_objects_visibility(false, object);

    auto *shader = wxGetApp().get_shader("mm_gouraud");
    if (shader == nullptr)
        return;
    shader->start_using();
    const std::array<float, 4> no_clipping{0.f, 0.f, 1.f, FLT_MAX};
    const std::array<float, 2> z_range{-FLT_MAX, FLT_MAX};
    shader->set_uniform("clipping_plane", no_clipping);
    shader->set_uniform("z_range", z_range);
    glsafe(::glEnable(GL_DEPTH_TEST));
    glsafe(::glDisable(GL_CULL_FACE));

    const Camera      &camera      = wxGetApp().plater()->get_camera();
    const Transform3d &view_matrix = camera.get_view_matrix();
    for (const ModelInstance *instance : object->instances)
        for (size_t p = 0; p < volumes.size(); ++p) {
            const Transform3d trafo       = instance->get_transformation().get_matrix() * volumes[p]->get_matrix();
            const bool        left_handed = trafo.matrix().determinant() < 0.;
            if (left_handed)
                glsafe(::glFrontFace(GL_CW));
            shader->set_uniform("view_model_matrix", view_matrix * trafo);
            shader->set_uniform("projection_matrix", camera.get_projection_matrix());
            const Matrix3d view_normal_matrix = view_matrix.matrix().block(0, 0, 3, 3) * trafo.matrix().block(0, 0, 3, 3).inverse().transpose();
            shader->set_uniform("view_normal_matrix", view_normal_matrix);
            shader->set_uniform("volume_world_matrix", trafo);
            shader->set_uniform("volume_mirrored", left_handed);
            shader->set_uniform("slope.actived", false);
            shader->set_uniform("slope.volume_world_normal_matrix", static_cast<Matrix3f>(trafo.matrix().block(0, 0, 3, 3).inverse().transpose().cast<float>()));
            shader->set_uniform("slope.normal_z", 0.f);
            shader->set_uniform("slope.up_direction", Vec3f(0.f, 0.f, 1.f));
            m_selectors[p]->render(m_imgui, trafo);
            if (left_handed)
                glsafe(::glFrontFace(GL_CCW));
        }
    glsafe(::glEnable(GL_CULL_FACE));
    shader->stop_using();
}

bool GLGizmoAiPaint::on_mouse(const wxMouseEvent &mouse_event)
{
    // The view is left to turn and zoom as always. A press and release in one place is a click on a part.
    if (m_draft == nullptr || m_step != Step::Draft)
        return false;
    const Vec2d position(mouse_event.GetX(), mouse_event.GetY());
    if (mouse_event.LeftDown()) {
        m_press   = position;
        m_pressed = true;
    } else if (mouse_event.LeftUp() && m_pressed) {
        m_pressed = false;
        if ((position - m_press).norm() < 4.)
            pick(position);
    }
    return false;
}

void GLGizmoAiPaint::pick(const Vec2d &mouse_position)
{
    if (m_raycaster == nullptr || m_step != Step::Draft || parts().empty())
        return;
    const Camera &camera = wxGetApp().plater()->get_camera();
    for (const ModelInstance *instance : m_draft->object->instances) {
        Vec3f  hit, normal;
        size_t facet = 0;
        // The joined mesh already has each part's own transform, so only the instance's is applied.
        if (m_raycaster->unproject_on_mesh(mouse_position, instance->get_transformation().get_matrix(), camera, hit, normal, nullptr, &facet) &&
            facet < m_draft->seg.face_segment.size()) {
            m_selected           = m_draft->seg.face_segment[facet];
            m_scroll_to_selected = true;
            m_flash_until        = std::chrono::steady_clock::now() + k_flash;
            m_dirty              = true;
            m_parent.set_as_dirty();
            return;
        }
    }
}

void GLGizmoAiPaint::apply()
{
    const std::vector<ModelVolume *> volumes = parts();
    if (volumes.empty() || m_step != Step::Draft) {
        close();
        return;
    }
    // What is written is the draft as it stands, without the comparison or a highlight.
    m_show_original = false;
    m_flash_until   = {};
    rebuild();
    if (m_selectors.size() != volumes.size())
        return;
    const int object_idx = m_draft->object_idx;
    Plater   *plater     = wxGetApp().plater();
    {
        Plater::TakeSnapshot snapshot(plater, _u8L("AI auto-paint"));
        for (size_t p = 0; p < volumes.size(); ++p)
            volumes[p]->mmu_segmentation_facets.set(*m_selectors[p]);
    }
    close();
    wxGetApp().obj_list()->update_info_items(size_t(object_idx));
    plater->changed_object(object_idx);
}

void GLGizmoAiPaint::on_render_input_window(float x, float y, float bottom_limit)
{
    if (m_draft != nullptr && parts().empty()) {
        close();
        return;
    }
    const float scale    = m_parent.get_scale();
    // The part of the plate that is on the screen: the application's window can be larger than a small screen, and the
    // panel is kept where it can be seen and reached.
    float canvas_w = float(m_parent.get_canvas_size().get_width()), canvas_h = float(m_parent.get_canvas_size().get_height());
    if (const wxGLCanvas *canvas = m_parent.get_wxglcanvas(); canvas != nullptr) {
        const float   factor = m_parent.get_canvas_size().get_scale_factor();
        const wxRect  screen = wxDisplay(canvas).GetClientArea();
        const wxPoint origin = canvas->GetScreenPosition();
        const float   seen_w = float(screen.GetRight() + 1 - origin.x) * factor, seen_h = float(screen.GetBottom() + 1 - origin.y) * factor;
        if (seen_w > m_imgui->scaled(15.f))
            canvas_w = std::min(canvas_w, seen_w);
        if (seen_h > m_imgui->scaled(15.f))
            canvas_h = std::min(canvas_h, seen_h);
    }
    // On a small screen the panel is narrower, and it is always wholly on the plate: its button can be far to the right.
    const float wrap = std::min(m_imgui->scaled(29.f), canvas_w * 0.55f);
    y = std::max(std::min(y, bottom_limit - ImGui::GetFrameHeight() * 22.f), 0.f);
    x = std::max(std::min(x, canvas_w - std::max(last_input_window_width, wrap + 4.f * ImGui::GetStyle().WindowPadding.x)), 0.f);
    GizmoImguiSetNextWIndowPos(x, y, ImGuiCond_Always, 0.0f, 0.0f);
    // Never taller than the room under it: on a small screen the panel scrolls instead of running off the plate.
    const float room = canvas_h - y - ImGui::GetFrameHeight();
    ImGui::SetNextWindowSizeConstraints(ImVec2(0.f, 0.f), ImVec2(FLT_MAX, std::max(room, ImGui::GetFrameHeight() * 8.f)));
    ImGuiWrapper::push_toolbar_style(scale);
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(6.0f, 4.0f * scale));
    GizmoImguiBegin(get_name(), ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoCollapse |
                                    ImGuiWindowFlags_NoTitleBar);

    m_action         = Action::Nothing;
    const int number = m_step == Step::Ask ? 1 : m_step == Step::Review ? 2 : 3;
    m_imgui->text(format_wxstr(_L("AI auto-paint: step %1% of 3"), number));
    if (m_step == Step::Ask)
        render_ask(wrap);
    else if (m_step == Step::Review)
        render_review(wrap);
    else
        render_draft(wrap);

    GizmoImguiEnd();
    ImGui::PopStyleVar(1);
    ImGuiWrapper::pop_toolbar_style();

    // Acted on after the panel is finished: some of these end the tool or change what the panel holds.
    switch (m_action) {
    case Action::Prepare: prepare(); break;
    case Action::Send: send(); break;
    case Action::StopWaiting: stop_waiting(); break;
    case Action::Back:
        stop_waiting();
        m_draft.reset();
        m_textures.clear();
        m_status.clear();
        m_step = Step::Ask;
        break;
    case Action::Settings: open_settings(); break;
    case Action::Apply: apply(); break;
    case Action::Close: close(); break;
    default: break;
    }
    if (m_action != Action::Nothing)
        m_settle = 3;
    if (m_settle > 0) {
        --m_settle;
        m_parent.set_as_dirty();
        m_parent.request_extra_frame();
    }
}

void GLGizmoAiPaint::render_ask(float wrap)
{
    ImGui::PushTextWrapPos(wrap);
    m_imgui->text(_L("The model's surface is split into numbered parts by its shape. They are not guaranteed to be its meaningful parts. "
                     "You see the exact pictures and text before anything is sent, and the answer comes back as a draft on the model, to "
                     "check and correct before applying."));
    const std::vector<ModelVolume *> volumes = selected_parts();
    if (std::any_of(volumes.begin(), volumes.end(), [](const ModelVolume *v) { return !v->mmu_segmentation_facets.empty(); }))
        m_imgui->text_colored(k_warning, _L("This object already has colour painting. In the draft, a part given a filament loses the "
                                            "painting it has, and a part kept as it is keeps it."));
    m_imgui->text(goes_to());
    ImGui::PopTextWrapPos();
    if (m_imgui->button(_L("AI settings")))
        m_action = Action::Settings;

    m_imgui->text(_L("Hint (optional)"));
    ImGui::SetNextItemWidth(wrap - ImGui::GetStyle().WindowPadding.x);
    ImGui::InputTextWithHint("##hint", _u8L("e.g. a goldfish, orange body, black eyes").c_str(), &m_hint);

    const wxString why = not_ready();
    if (!why.empty()) {
        ImGui::PushTextWrapPos(wrap);
        m_imgui->text_colored(k_warning, why);
        ImGui::PopTextWrapPos();
    }
    m_imgui->disabled_begin(!why.empty());
    ImGuiWrapper::push_confirm_button_style();
    if (m_imgui->button(_L("Show what will be sent")))
        m_action = Action::Prepare;
    ImGuiWrapper::pop_confirm_button_style();
    m_imgui->disabled_end();
    ImGui::SameLine();
    ImGuiWrapper::push_cancel_button_style();
    if (m_imgui->button(_L("Close")))
        m_action = Action::Close;
    ImGuiWrapper::pop_cancel_button_style();
}

void GLGizmoAiPaint::render_views(float wrap)
{
    const std::vector<SP::Image> &images = m_draft->images;
    if (m_textures.size() != images.size()) {
        m_textures.clear();
        for (const SP::Image &image : images) {
            std::vector<unsigned char> rgba(size_t(image.width) * size_t(image.height) * 4, 255);
            for (size_t i = 0, n = size_t(image.width) * size_t(image.height); i < n; ++i)
                std::copy_n(image.rgb.begin() + i * 3, 3, rgba.begin() + i * 4);
            auto texture = std::make_unique<GLTexture>();
            // With smaller copies of itself: the pictures are drawn far smaller than they are sent.
            texture->load_from_raw_data(std::move(rgba), unsigned(image.width), unsigned(image.height), false, true);
            m_textures.push_back(std::move(texture));
        }
    }
    const float gap  = ImGui::GetStyle().ItemSpacing.x;
    const float side = std::floor((wrap - ImGui::GetStyle().WindowPadding.x - 2.f * gap) / 3.f);
    for (size_t i = 0; i < m_textures.size(); ++i) {
        if (i % 3 != 0)
            ImGui::SameLine();
        ImGui::BeginGroup();
        const auto id = ImTextureID(intptr_t(m_textures[i]->get_id()));
        ImGui::Image(id, ImVec2(side, side));
        // Pointed at, a picture is shown large enough to read its numbers.
        if (ImGui::IsItemHovered()) {
            m_settle = std::max(m_settle, 1);
            ImGui::BeginTooltip();
            ImGui::Image(id, ImVec2(side * 4.f, side * 4.f));
            ImGui::EndTooltip();
        }
        ImGui::TextUnformatted(m_draft->view_names[i].c_str());
        ImGui::EndGroup();
    }
}

void GLGizmoAiPaint::render_review(float wrap)
{
    ImGui::PushTextWrapPos(wrap);
    m_imgui->text(_L("These pictures and the text under them are what will be sent, and nothing else. Nothing is sent until you press Send. "
                     "Point at a picture to see it larger."));
    ImGui::PopTextWrapPos();
    render_views(wrap);
    ImGui::PushTextWrapPos(wrap);
    m_imgui->text(format_wxstr(_L_PLURAL("%1% numbered part.", "%1% numbered parts.", m_draft->seg.segments.size()), m_draft->seg.segments.size()));
    m_imgui->text(goes_to());
    m_imgui->text(_L("The text that is sent:"));
    ImGui::PopTextWrapPos();
    ImGui::InputTextMultiline("##prompt", &m_draft->prompt, ImVec2(wrap - ImGui::GetStyle().WindowPadding.x, ImGui::GetFrameHeight() * 5.f),
                              ImGuiInputTextFlags_ReadOnly);
    ImGui::PushTextWrapPos(wrap);
    if (m_settings.provider == "command")
        m_imgui->text(_L("The pictures are written to a temporary folder and their file names are added to this text."));
    if (m_busy)
        m_imgui->text(_L("Waiting for the answer... this can take up to a few minutes."));
    else if (!m_status.empty())
        m_imgui->text_colored(k_warning, m_status);
    ImGui::PopTextWrapPos();

    m_imgui->disabled_begin(m_busy);
    ImGuiWrapper::push_confirm_button_style();
    if (m_imgui->button(format_wxstr(_L_PLURAL("Send this %1% picture", "Send these %1% pictures", m_draft->images.size()), m_draft->images.size())))
        m_action = Action::Send;
    ImGuiWrapper::pop_confirm_button_style();
    m_imgui->disabled_end();
    ImGui::SameLine();
    ImGuiWrapper::push_cancel_button_style();
    if (m_busy) {
        if (m_imgui->button(_L("Stop waiting")))
            m_action = Action::StopWaiting;
    } else if (m_imgui->button(_L("Back")))
        m_action = Action::Back;
    ImGuiWrapper::pop_cancel_button_style();
    if (m_busy)
        m_parent.request_extra_frame(); // so the answer is shown when it comes, without the pointer having to move
}

void GLGizmoAiPaint::render_draft(float wrap)
{
    bool        changed  = false;
    const auto &segments = m_draft->seg.segments;

    ImGui::PushTextWrapPos(wrap);
    if (!m_draft->subject.empty())
        m_imgui->text(format_wxstr(_L("The service took the object to be: %1%"), from_u8(m_draft->subject)));
    // The count has a line of its own: it changes with every choice, and nothing below it may move when it does.
    m_imgui->text(format_wxstr(_L("%1% of %2% faces get a filament."), m_assigned, m_draft->seg.face_segment.size()));
    m_imgui->text(_L("This is a draft: the model is unchanged until you apply. Turn the view to look at every side, underneath too."));
    m_imgui->text(_L("Click a part on the model to find it in the list."));
    if (const size_t unsure = size_t(std::count(m_draft->unsure.begin(), m_draft->unsure.end(), 1)); unsure > 0)
        m_imgui->text(format_wxstr(_L_PLURAL("The service was unsure about %1% part. It is marked with a question mark: look at it first.",
                                             "The service was unsure about %1% parts. They are marked with a question mark: look at them first.", unsure),
                                   unsure));
    ImGui::PopTextWrapPos();

    const size_t spots = segments.size() - std::min(m_draft->first_spot, segments.size());
    ImGui::PushTextWrapPos(wrap);
    if (spots > 0)
        m_imgui->text(format_wxstr(_L_PLURAL("The service marked %1% feature in the pictures that is no part of its own (the row with a star). It is filled "
                                             "from where the service pointed, up to the model's own edges, or as a round patch where there are none: "
                                             "adjust it with the painting tools after applying.",
                                             "The service marked %1% features in the pictures that are no parts of their own (the rows with a star). Each "
                                             "is filled from where the service pointed, up to the model's own edges, or as a round patch where there are "
                                             "none: adjust them with the painting tools after applying.", spots), spots));
    if (!m_draft->notes.empty())
        m_imgui->text_colored(k_warning, format_wxstr(_L("The service's note: %1%"), from_u8(m_draft->notes)));
    ImGui::PopTextWrapPos();
    // The numbers in the list are the numbers in the pictures that were sent.
    if (m_imgui->bbl_checkbox(_L("Show the numbered pictures"), m_show_views))
        m_settle = 3;
    if (m_show_views)
        render_views(wrap);
    if (m_imgui->bbl_checkbox(_L("Compare with original"), m_show_original))
        changed = true;
    if (m_imgui->bbl_checkbox(_L("Boundary cleanup"), m_tidy))
        changed = true;
    ImGui::PushTextWrapPos(wrap);
    m_imgui->text(m_tidy ? format_wxstr(_L_PLURAL("%1% face adjusted. It takes back single triangles that poke into another colour and keeps small "
                                                  "details and sharp edges; it cannot correct a part that was found wrongly.",
                                                  "%1% faces adjusted. It takes back single triangles that poke into another colour and keeps small "
                                                  "details and sharp edges; it cannot correct a part that was found wrongly.", m_tidied), m_tidied) :
                           _L("Off."));
    ImGui::PopTextWrapPos();

    if (m_imgui->button(_L("Keep all as it is"))) {
        std::fill(m_choice.begin(), m_choice.end(), 0);
        changed = true;
    }
    ImGui::SameLine();
    if (m_imgui->button(_L("Back to the suggestion"))) {
        m_choice = m_draft->suggested;
        changed  = true;
    }

    // One row per part: its number as in the pictures, its share of the surface, and the filament it gets.
    const std::vector<ColorRGBA> extruders = wxGetApp().plater()->get_extruders_colors();
    const auto                   slot_name = [this](int slot) {
        if (slot <= 0)
            return _u8L("Keep as it is");
        for (const SP::FilamentInfo &f : m_draft->filaments)
            if (f.index == slot)
                return std::to_string(slot) + "  " + SP::color_name(f.color_hex) + (f.type.empty() ? std::string() : "  " + f.type);
        return std::to_string(slot);
    };
    ImGui::BeginChild("##parts", ImVec2(wrap, ImGui::GetFrameHeightWithSpacing() * std::min<float>(9.f, float(segments.size())) + 4.f), true);
    for (size_t i = 0; i < segments.size(); ++i) {
        ImGui::PushID(int(i));
        const bool selected = int(i) == m_selected;
        if (selected && m_scroll_to_selected) {
            ImGui::SetScrollHereY(0.5f);
            m_scroll_to_selected = false;
        }
        ImGui::AlignTextToFramePadding();
        // The part's number as in the pictures and the name the service gave it; without a name, its share of the surface.
        const std::string share  = (boost::format("%.1f%%") % (segments[i].area_fraction * 100.)).str();
        const std::string name   = i < m_draft->names.size() ? m_draft->names[i] : std::string();
        const bool        unsure = i < m_draft->unsure.size() && m_draft->unsure[i] != 0;
        // A marked feature has no number in the pictures: its row begins with a star.
        const std::string label  = i >= m_draft->first_spot ? "*  " + (name.empty() ? share : name) :
                                   "#" + std::to_string(segments[i].id) + (unsure ? " ?  " : "  ") + (name.empty() ? share : name);
        if (selected)
            ImGui::TextColored(ImVec4(k_highlight.r(), k_highlight.g(), k_highlight.b(), 1.f), "%s", label.c_str());
        else if (unsure)
            ImGui::TextColored(ImVec4(0.95f, 0.6f, 0.1f, 1.f), "%s", label.c_str());
        else
            ImGui::TextUnformatted(label.c_str());
        if (ImGui::IsItemHovered() && !name.empty())
            m_imgui->tooltip(from_u8(name + "  " + share), wrap);
        ImGui::SameLine(wrap * 0.41f); // the columns follow the panel, which is narrower on a small screen
        const int   slot = m_choice[i];
        const ImVec4 swatch = slot > 0 && size_t(slot) <= extruders.size() ?
            ImVec4(extruders[size_t(slot - 1)].r(), extruders[size_t(slot - 1)].g(), extruders[size_t(slot - 1)].b(), 1.f) : ImVec4(0.5f, 0.5f, 0.5f, 0.35f);
        ImGui::ColorButton("##swatch", swatch, ImGuiColorEditFlags_NoTooltip | ImGuiColorEditFlags_NoPicker | ImGuiColorEditFlags_NoDragDrop,
                           ImVec2(ImGui::GetFrameHeight(), ImGui::GetFrameHeight()));
        ImGui::SameLine();
        ImGui::SetNextItemWidth(wrap * 0.41f);
        if (ImGui::BeginCombo("##filament", slot_name(slot).c_str())) {
            for (int option = 0; option <= int(m_draft->filaments.size()); ++option) {
                const int value = option == 0 ? 0 : m_draft->filaments[size_t(option - 1)].index;
                if (ImGui::Selectable(slot_name(value).c_str(), value == slot)) {
                    m_choice[i] = value;
                    m_selected  = int(i);
                    changed     = true;
                }
            }
            ImGui::EndCombo();
        }
        ImGui::PopID();
    }
    ImGui::EndChild();

    ImGuiWrapper::push_confirm_button_style();
    if (m_imgui->button(_L("Apply as paint")))
        m_action = Action::Apply;
    ImGuiWrapper::pop_confirm_button_style();
    ImGui::SameLine();
    ImGuiWrapper::push_cancel_button_style();
    if (m_imgui->button(_L("Discard")))
        m_action = Action::Close;
    ImGuiWrapper::pop_cancel_button_style();

    if (changed) {
        m_dirty = true;
        m_parent.set_as_dirty();
    }
}

} // namespace GUI
} // namespace Slic3r
