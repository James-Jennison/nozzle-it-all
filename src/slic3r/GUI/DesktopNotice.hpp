#ifndef slic3r_GUI_DesktopNotice_hpp_
#define slic3r_GUI_DesktopNotice_hpp_

#include <wx/string.h>

namespace Slic3r::GUI {

/// Asks the desktop to show a notification, for something that happened while the app is not in front.
/// Main thread only. It does not wait for the desktop and says nothing when there is no notification service.
void desktop_notice(const wxString &title, const wxString &text);

} // namespace Slic3r::GUI

#endif // slic3r_GUI_DesktopNotice_hpp_
