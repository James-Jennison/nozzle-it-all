#pragma once

namespace Slic3r {
namespace GUI {

// True when exactly one object is selected.
bool can_run_semantic_paint();

// "AI auto-paint": opens the tool's panel beside the plate (GLGizmoAiPaint), which holds every step: the hint, the
// exact pictures and text before anything is sent, and the answer as a draft on the object.
void run_semantic_paint();

} // namespace GUI
} // namespace Slic3r
