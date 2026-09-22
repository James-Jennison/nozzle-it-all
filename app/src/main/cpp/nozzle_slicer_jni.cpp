// WO-13 Phase 0 smoke test: proves the NDK/CMake/JNI/oneTBB pipeline actually
// works end-to-end on a real device before any slicing engine code exists.
// tbbMaxConcurrency() calls a real oneTBB API (not a stub) so a wrong ABI, a
// missing libtbb.so, or a broken link step fails loudly here instead of
// silently once libslic3r itself is wired in later. Replace/extend this file
// when the slicing bridge (sliceToFile, per WO-13's plan) actually lands.
#include <jni.h>
#include <oneapi/tbb/info.h>

extern "C" JNIEXPORT jint JNICALL
Java_net_jamesjennison_klippercompanion_NativeSlicer_tbbMaxConcurrency(JNIEnv*, jobject) {
    return static_cast<jint>(oneapi::tbb::info::default_concurrency());
}
