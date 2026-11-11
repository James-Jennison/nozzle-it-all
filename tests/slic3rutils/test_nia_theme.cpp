#include <catch2/catch_test_macros.hpp>

#include "slic3r/GUI/NIATheme.hpp"

TEST_CASE("NIA theme exposes shared product tokens for explicit shell styling", "[nia][theme]")
{
#if NOZZLE_BRANDING
    REQUIRE(Slic3r::GUI::NIATheme::accent(false) == wxColour("#6D28D9"));
    REQUIRE(Slic3r::GUI::NIATheme::accent(true) == wxColour("#A78BFA"));
    REQUIRE(Slic3r::GUI::NIATheme::background(false) == wxColour("#F6F7F9"));
    REQUIRE(Slic3r::GUI::NIATheme::background(true) == wxColour("#0D1114"));
    REQUIRE(Slic3r::GUI::NIATheme::accent_soft(false) == wxColour("#EDE9FE"));
    REQUIRE(Slic3r::GUI::NIATheme::accent_soft(true) == wxColour("#2B1D4A"));
#endif
}
