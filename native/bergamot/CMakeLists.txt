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
# __SSE__ but omits the intrinsic header on Android. Source properties are
# directory-scoped: explicitly set them in the directory that created FAISS.
# TARGET_DIRECTORY is supported by the pinned CMake 3.31.6 toolchain.
if(ANDROID AND (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86_64"))
    if(NOT TARGET faiss)
        message(FATAL_ERROR "Expected upstream FAISS target is missing; cannot apply x86_64 SSE workaround")
    endif()
    set(_faiss_vector_transform "${BERGAMOT_SOURCE_DIR}/3rd_party/marian-dev/src/3rd_party/faiss/VectorTransform.cpp")
    if(NOT EXISTS "${_faiss_vector_transform}")
        message(FATAL_ERROR "Expected FAISS source is missing: ${_faiss_vector_transform}")
    endif()
    set_source_files_properties("${_faiss_vector_transform}"
        TARGET_DIRECTORY faiss
        PROPERTIES COMPILE_OPTIONS "-include;pmmintrin.h"
    )
    get_source_file_property(_faiss_vector_transform_options "${_faiss_vector_transform}"
        TARGET_DIRECTORY faiss COMPILE_OPTIONS
    )
    list(FIND _faiss_vector_transform_options "-include" _faiss_include_flag_index)
    if(_faiss_include_flag_index LESS 0)
        message(FATAL_ERROR "pmmintrin.h compile option was not attached in FAISS source directory")
    endif()
    math(EXPR _faiss_include_header_index "${_faiss_include_flag_index} + 1")
    list(LENGTH _faiss_vector_transform_options _faiss_option_count)
    if(_faiss_include_header_index GREATER_EQUAL _faiss_option_count)
        message(FATAL_ERROR "Incomplete FAISS forced-include option")
    endif()
    list(GET _faiss_vector_transform_options ${_faiss_include_header_index} _faiss_include_header)
    if(NOT _faiss_include_header STREQUAL "pmmintrin.h")
        message(FATAL_ERROR "Unexpected FAISS forced header: ${_faiss_include_header}")
    endif()
    message(STATUS "Verified pmmintrin.h source option in FAISS target directory")
endif()

if(NOT TARGET bergamot-translator)
    message(FATAL_ERROR "Pinned Bergamot source did not define bergamot-translator")
endif()

add_library(arabify_bergamot SHARED bergamot_jni.cpp)
target_compile_features(arabify_bergamot PRIVATE cxx_std_17)
target_link_libraries(arabify_bergamot PRIVATE bergamot-translator)
target_compile_options(arabify_bergamot PRIVATE -Wall -Wextra -Werror=return-type)
