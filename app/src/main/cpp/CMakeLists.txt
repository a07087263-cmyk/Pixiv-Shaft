cmake_minimum_required(VERSION 3.22.1)

project(shaft_secrets LANGUAGES CXX)

if(NOT DEFINED SHAFT_GENERATED_SECRET_DIR)
    message(FATAL_ERROR "SHAFT_GENERATED_SECRET_DIR is required")
endif()

set(SHAFT_GENERATED_SECRET_HEADER
        "${SHAFT_GENERATED_SECRET_DIR}/shaft_secrets.generated.h")
if(NOT EXISTS "${SHAFT_GENERATED_SECRET_HEADER}")
    message(FATAL_ERROR "Generated Shaft secret header is missing")
endif()

add_library(shaft_secrets SHARED shaft_hmac.cpp)

target_include_directories(shaft_secrets PRIVATE "${SHAFT_GENERATED_SECRET_DIR}")
target_compile_features(shaft_secrets PRIVATE cxx_std_20)
target_compile_options(shaft_secrets PRIVATE
        -Wall
        -Wextra
        -Werror
        -ffunction-sections
        -fdata-sections)
target_link_options(shaft_secrets PRIVATE
        -Wl,--gc-sections
        -Wl,-z,relro
        -Wl,-z,now
        # Google Play 自 2025-11 起要求 16 KB page size 支持：LOAD 段必须按 16 KB 对齐，
        # 否则上传时报 "not compatible with 16 KB devices"。
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

set_target_properties(shaft_secrets PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)

# 图片滤镜导出内核（shaft_image_filter.cpp）：与密钥库分开编译，不共享符号，也不受密钥生成任务影响。
# 无论 Debug / Release 都开 -O3：拖滑条的每一帧预览和全分辨率导出都跑它，Debug 包也得跟手。
add_library(shaft_filter SHARED shaft_image_filter.cpp)

target_compile_features(shaft_filter PRIVATE cxx_std_20)
target_compile_options(shaft_filter PRIVATE
        -O3
        -Wall
        -Wextra
        -Werror
        -ffunction-sections
        -fdata-sections)
target_link_libraries(shaft_filter PRIVATE jnigraphics)
target_link_options(shaft_filter PRIVATE
        -Wl,--gc-sections
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

set_target_properties(shaft_filter PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)


# SVT-AV1 software encoder for ugoira MP4 export. Pin both version and archive hash:
# encoder upgrades intentionally require review instead of silently changing output quality.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Production uses arm64 NEON. The optional x86_64 emulator CI must not gain a
# NASM host-tool dependency just to package this encoder, so its fallback is C-only.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(COMPILE_C_ONLY OFF CACHE BOOL "" FORCE)
else()
    set(COMPILE_C_ONLY ON CACHE BOOL "" FORCE)
endif()
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LOG_QUIET ON CACHE BOOL "" FORCE)
set(SVT_AV1_LTO OFF CACHE BOOL "" FORCE)
set(REPRODUCIBLE_BUILDS ON CACHE BOOL "" FORCE)
set(EXCLUDE_HASH ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
FetchContent_Declare(svt_av1
        URL "https://gitlab.com/AOMediaCodec/SVT-AV1/-/archive/v4.2.0/SVT-AV1-v4.2.0.tar.gz"
        URL_HASH "SHA256=c7b13c4a84bd3751aa35fcc72be13e6875467e7c2216879251a486e5b1e4e740"
        PATCH_COMMAND "${CMAKE_COMMAND}" "-DSVT_AV1_SOURCE_DIR=<SOURCE_DIR>"
                -P "${CMAKE_CURRENT_LIST_DIR}/cmake/svt-av1-preserve-llvm-tools.cmake")
FetchContent_MakeAvailable(svt_av1)

# Still-image codecs. libavif reuses the exact SVT target above for encoding and
# builds AOM only for decoding; libjxl supplies both sides of the archival path.
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AVIF_ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(AVIF_LIBYUV OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_SVT SYSTEM CACHE STRING "" FORCE)
set(AVIF_CODEC_AOM LOCAL CACHE STRING "" FORCE)
set(AVIF_CODEC_AOM_DECODE ON CACHE BOOL "" FORCE)
set(AVIF_CODEC_AOM_ENCODE OFF CACHE BOOL "" FORCE)
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    # Emulator CI must not gain a host NASM dependency merely to decode AVIF.
    set(AOM_TARGET_CPU "generic" CACHE STRING "" FORCE)
    set(ENABLE_NASM OFF CACHE BOOL "" FORCE)
endif()
FetchContent_Declare(libavif
        URL "https://github.com/AOMediaCodec/libavif/archive/refs/tags/v1.4.2.tar.gz"
        URL_HASH "SHA256=2b645287340ba5a631d268b551dc2d72bd73ac33335962dd36dcdb6d8366921d")
FetchContent_MakeAvailable(libavif)

set(JPEGXL_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_JPEGLI OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_JPEGLI_LIBJPEG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
# v0.11.2, commit 332feb17d17311c748445f7ee75c4fb55cc38530.
# Recursive submodules are recorded by that commit (Highway, Brotli, etc.).
FetchContent_Declare(libjxl
        GIT_REPOSITORY "https://github.com/libjxl/libjxl.git"
        GIT_TAG "v0.11.2"
        GIT_SHALLOW TRUE
        GIT_SUBMODULES_RECURSE TRUE
        GIT_PROGRESS TRUE)
FetchContent_MakeAvailable(libjxl)

add_library(shaft_image_codecs SHARED shaft_image_codecs.cpp)
target_compile_features(shaft_image_codecs PRIVATE cxx_std_20)
target_compile_options(shaft_image_codecs PRIVATE
        -O3
        -Wall
        -Wextra
        -ffunction-sections
        -fdata-sections)
target_include_directories(shaft_image_codecs PRIVATE
        "${libavif_SOURCE_DIR}/include"
        "${libjxl_SOURCE_DIR}/lib/include")
target_link_libraries(shaft_image_codecs PRIVATE avif SvtAv1Enc jxl jnigraphics log)
target_link_options(shaft_image_codecs PRIVATE
        -Wl,--gc-sections
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)
set_target_properties(shaft_image_codecs PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)

add_library(shaft_ugoira_av1 SHARED shaft_ugoira_av1.cpp)
target_compile_features(shaft_ugoira_av1 PRIVATE cxx_std_20)
target_compile_options(shaft_ugoira_av1 PRIVATE
        -O3
        -Wall
        -Wextra
        -Werror
        -ffunction-sections
        -fdata-sections)
target_include_directories(shaft_ugoira_av1 PRIVATE "${svt_av1_SOURCE_DIR}/Source/API")
target_link_libraries(shaft_ugoira_av1 PRIVATE SvtAv1Enc jnigraphics log)
target_link_options(shaft_ugoira_av1 PRIVATE
        -Wl,--gc-sections
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)
set_target_properties(shaft_ugoira_av1 PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)
