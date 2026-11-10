cmake_minimum_required(VERSION 3.22.1)
project(arabify_bergamot_android LANGUAGES C CXX)

if(NOT DEFINED BERGAMOT_SOURCE_DIR OR NOT EXISTS "${BERGAMOT_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "BERGAMOT_SOURCE_DIR must point to the pinned Bergamot checkout")
endif()

# Keep the upstream checkout immutable during configure. build-android.sh checks out
# the superproject and every gitlink before CMake is invoked.
set(GIT_SUBMODULE OFF CACHE BOOL "Do not mutate upstream submodules during CMake" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_PYTHON OFF CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
# Android NDK does not ship system PCRE2; build the pinned ssplit dependency's
# bundled PCRE2 as a static, PIC library instead of searching host libraries.
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory("${BERGAMOT_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/bergamot-upstream" EXCLUDE_FROM_ALL)

# The pinned Marian/FAISS VectorTransform.cpp uses SSE intrinsics behind
# __SSE__ but does not include the corresponding intrinsic header on Android.
# Clang's x86_64-v2 target defines __SSE__; force-include the SSE3 header for
# this object library only. This preserves the optimized x86_64 path and does
# not affect ARM64 or other upstream translation units.
if(ANDROID AND ANDROID_ABI STREQUAL "x86_64" AND TARGET faiss)
    target_compile_options(faiss PRIVATE -include pmmintrin.h)
endif()

if(NOT TARGET bergamot-translator)
    message(FATAL_ERROR "Pinned Bergamot source did not define bergamot-translator")
endif()

add_library(arabify_bergamot SHARED bergamot_jni.cpp)
target_compile_features(arabify_bergamot PRIVATE cxx_std_17)
target_link_libraries(arabify_bergamot PRIVATE bergamot-translator)
target_compile_options(arabify_bergamot PRIVATE -Wall -Wextra -Werror=return-type)
