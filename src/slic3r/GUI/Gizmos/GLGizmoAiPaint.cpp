#include "GLGizmoAiPaint.hpp"

#include "GLGizmosManager.hpp"
#include "slic3r/GUI/Camera.hpp"
#include "slic3r/GUI/GLCanvas3D.hpp"
#include "slic3r/GUI/GLShader.hpp"
#include "slic3r/GUI/GUI.hpp"
#include "slic3r/GUI/GUI_App.hpp"
#include "slic3r/GUI/GUI_ObjectList.hpp"
#include "slic3r/GUI/I18N.hpp"
#include "slic3r/GUI/ImGuiWrapper.hpp"
#include "slic3r/GUI/MeshUtils.hpp"
#include "slic3r/GUI/Plater.hpp"
#include "slic3r/GUI/format.hpp"

#include "libslic3r/Color.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include <boost/format.hpp>
#include <imgui/imgui.h>
#include <wx/event.h>

#include <algorithm>
#include <cfloat>

namespace Slic3r {
namespace GUI {

namespace SP = Slic3r::SemanticPaint;

namespace {
constexpr auto k_flash = std::chrono::milliseconds(1200); // how long a picked part is shown in the highlight colour
const ColorRGBA k_highlight{1.f, 0.f, 0.9f, 1.f};
} // namespace

GLGizmoAiPaint::GLGizmoAiPaint(GLCanvas3D &parent, const std::string &icon_filename, unsigned int sprite_id)
    : GLGizmoBase(parent, icon_filename, sprite_id)
{}

GLGizmoAiPaint::~GLGizmoAiPaint() = default;

std::string GLGizmoAiPaint::on_get_name() const { return _u8L("AI auto-paint draft"); }

CommonGizmosDataID GLGizmoAiPaint::on_get_requirements() const { return CommonGizmosDataID(int(CommonGizmosDataID::SelectionInfo)); }

void GLGizmoAiPaint::start(Draft draft)
{
    m_draft         = std::make_unique<Draft>(std::move(draft));
    m_choice        = m_draft->suggested;
    m_choice.resize(m_draft->seg.segments.size(), 0);
    m_draft->suggested = m_choice;
    m_tidy          = true;
    m_show_original = false;
    m_selected      = -1;
    m_flash_until   = {};
    m_raycaster     = std::make_unique<MeshRaycaster>(TriangleMesh(m_draft->mesh));
    m_selectors.clear();
    m_dirty = true;
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
    std::vector<int> out = SP::face_filaments(m_draft->seg, m_choice);
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
        for (size_t f = first; f < m_draft->first_face[p + 1]; ++f) {
            if (flash && m_draft->seg.face_segment[f] == m_selected)
                selector->set_facet(int(f - first), EnforcerBlockerType(highlight_state));
            else if (!m_show_original && face_filament[f] > 0 && size_t(face_filament[f]) <= slots)
                selector->set_facet(int(f - first), EnforcerBlockerType(face_filament[f]));
        }
        selector->request_update_render_data();
        m_selectors.push_back(std::move(selector));
    }
}

void GLGizmoAiPaint::on_set_state()
{
    if (m_state == Off) {
        m_parent.toggle_model_objects_visibility(true);
        m_selectors.clear();
        m_raycaster.reset();
        m_draft.reset();
        m_pressed = false;
    } else if (m_state == On) {
        m_dirty = true;
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
    if (m_draft == nullptr)
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
    if (m_draft == nullptr)
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
    if (m_raycaster == nullptr || parts().empty())
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
    if (volumes.empty()) {
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
    if (m_draft == nullptr)
        return;
    if (parts().empty()) {
        close();
        return;
    }
    const float scale = m_parent.get_scale();
    y = std::min(y, bottom_limit - ImGui::GetFrameHeight() * 22.f);
    GizmoImguiSetNextWIndowPos(x, std::max(y, 0.f), ImGuiCond_Always, 0.0f, 0.0f);
    ImGuiWrapper::push_toolbar_style(scale);
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(6.0f, 4.0f * scale));
    GizmoImguiBegin(get_name(), ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoCollapse |
                                    ImGuiWindowFlags_NoTitleBar);

    bool       changed = false, do_apply = false, do_close = false;
    const auto &segments = m_draft->seg.segments;
    const float wrap     = m_imgui->scaled(26.f);

    m_imgui->text(_L("AI auto-paint draft"));
    ImGui::PushTextWrapPos(wrap);
    m_imgui->text(format_wxstr(_L("%1% of %2% faces get a filament. This is a draft: the model is unchanged until you apply. Turn the view to "
                                  "look at every side, underneath too."), m_assigned, m_draft->seg.face_segment.size()));
    m_imgui->text(_L("Click a part on the model to find it in the list."));
    ImGui::PopTextWrapPos();

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
        const std::string label = "#" + std::to_string(segments[i].id) + "  " + (boost::format("%.1f%%") % (segments[i].area_fraction * 100.)).str();
        if (selected)
            ImGui::TextColored(ImVec4(k_highlight.r(), k_highlight.g(), k_highlight.b(), 1.f), "%s", label.c_str());
        else
            ImGui::TextUnformatted(label.c_str());
        ImGui::SameLine(m_imgui->scaled(7.f));
        const int   slot = m_choice[i];
        const ImVec4 swatch = slot > 0 && size_t(slot) <= extruders.size() ?
            ImVec4(extruders[size_t(slot - 1)].r(), extruders[size_t(slot - 1)].g(), extruders[size_t(slot - 1)].b(), 1.f) : ImVec4(0.5f, 0.5f, 0.5f, 0.35f);
        ImGui::ColorButton("##swatch", swatch, ImGuiColorEditFlags_NoTooltip | ImGuiColorEditFlags_NoPicker | ImGuiColorEditFlags_NoDragDrop,
                           ImVec2(ImGui::GetFrameHeight(), ImGui::GetFrameHeight()));
        ImGui::SameLine();
        ImGui::SetNextItemWidth(m_imgui->scaled(14.f));
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
        do_apply = true;
    ImGuiWrapper::pop_confirm_button_style();
    ImGui::SameLine();
    ImGuiWrapper::push_cancel_button_style();
    if (m_imgui->button(_L("Discard")))
        do_close = true;
    ImGuiWrapper::pop_cancel_button_style();

    GizmoImguiEnd();
    ImGui::PopStyleVar(1);
    ImGuiWrapper::pop_toolbar_style();

    if (changed) {
        m_dirty = true;
        m_parent.set_as_dirty();
    }
    // Acted on after the panel is finished, since both end the tool.
    if (do_apply)
        apply();
    else if (do_close)
        close();
}

} // namespace GUI
} // namespace Slic3r
