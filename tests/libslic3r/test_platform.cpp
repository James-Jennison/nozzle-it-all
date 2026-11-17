#include <catch2/catch_test_macros.hpp>

#include "libslic3r/Platform.hpp"

using namespace Slic3r;

TEST_CASE("The NVIDIA driver branch is read from the proprietary and the open module's version text", "[Platform]")
{
    // The first text is /proc/driver/nvidia/version as read on a machine with the proprietary module; the second has
    // the open module's wording.
    CHECK(nvidia_driver_branch("NVRM version: NVIDIA UNIX x86_64 Kernel Module  580.178.04  Tue Jul  7 12:38:21 UTC 2026\n"
                               "GCC version:  gcc version 15.2.0 (Ubuntu 15.2.0-16ubuntu1)\n") == 580);
    CHECK(nvidia_driver_branch("NVRM version: NVIDIA UNIX Open Kernel Module for x86_64  615.71.09  Release Build  (dvs-builder@U22)\n") == 615);
    CHECK(nvidia_driver_branch("NVRM version: NVIDIA UNIX x86_64 Kernel Module  610.43.02") == 610);
}

TEST_CASE("A version text that names no driver version gives branch 0", "[Platform]")
{
    CHECK(nvidia_driver_branch("") == 0);
    // Words that are not a version: one with a letter before the digits, one with nothing before the dot.
    CHECK(nvidia_driver_branch("NVRM version: NVIDIA UNIX x86_64 Kernel Module  v580.178.04\n") == 0);
    CHECK(nvidia_driver_branch("NVRM version: NVIDIA UNIX x86_64 Kernel Module  .178.04\n") == 0);
    // Tabs and several spaces between the words.
    CHECK(nvidia_driver_branch("NVRM version:\tNVIDIA UNIX x86_64 Kernel Module\t\t615.71.09   Release") == 615);
    CHECK(nvidia_driver_branch("NVRM version: NVIDIA UNIX x86_64 Kernel Module\n") == 0);
    // A version on a later line is not the module's.
    CHECK(nvidia_driver_branch("NVRM version: unknown\nGCC version:  gcc version 15.2.0\n") == 0);
}
