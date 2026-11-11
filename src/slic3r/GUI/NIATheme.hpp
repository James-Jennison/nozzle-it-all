#pragma once

#include <wx/colour.h>

namespace Slic3r::GUI::NIATheme {

// These values mirror design/tokens/nozzle.tokens.json in the existing
// Nozzle It All Desktop product. Keep the branded wxWidgets shell aligned with
// that source rather than inventing a second visual language.
inline wxColour background(bool dark) { return wxColour(dark ? "#0D1114" : "#F6F7F9"); }
inline wxColour surface(bool dark) { return wxColour(dark ? "#14191D" : "#FFFFFF"); }
inline wxColour surface_raised(bool dark) { return wxColour(dark ? "#1E2429" : "#EEF1F3"); }
inline wxColour surface_sunken(bool dark) { return wxColour(dark ? "#0A0D0F" : "#E6EAED"); }
inline wxColour line(bool dark) { return wxColour(dark ? "#2A3138" : "#D5DBDE"); }
inline wxColour line_strong(bool dark) { return wxColour(dark ? "#4A545B" : "#9AA5AA"); }
inline wxColour text(bool dark) { return wxColour(dark ? "#EEF2F4" : "#0D1114"); }
inline wxColour text_muted(bool dark) { return wxColour(dark ? "#9AA5AA" : "#4F5A61"); }
inline wxColour accent(bool dark) { return wxColour(dark ? "#A78BFA" : "#6D28D9"); }
inline wxColour on_accent(bool dark) { return wxColour(dark ? "#0D1114" : "#FFFFFF"); }
inline wxColour accent_soft(bool dark) { return wxColour(dark ? "#2B1D4A" : "#EDE9FE"); }
inline wxColour heat(bool dark) { return wxColour(dark ? "#F2754E" : "#C2410C"); }
inline wxColour focus(bool dark) { return wxColour(dark ? "#C4B5FD" : "#6D28D9"); }
inline wxColour danger(bool dark) { return wxColour(dark ? "#FB7185" : "#BE123C"); }
inline wxColour ready(bool dark) { return wxColour(dark ? "#5EEAD4" : "#0F766E"); }

} // namespace Slic3r::GUI::NIATheme
