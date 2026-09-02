cmake_minimum_required(VERSION 3.22.1)

project(themachine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# ggml configuration, shared by both engines.
#
# The runtime-dispatch trio (BUILD_SHARED_LIBS + GGML_BACKEND_DL +
# GGML_CPU_ALL_VARIANTS) has to be set together: ALL_VARIANTS hard-errors
# without BACKEND_DL, and BACKEND_DL hard-errors with GGML_NATIVE=ON. Together
# they emit one libggml-cpu-android_armv*.so per ARM feature level — v8.0
# through v9.2 SVE+SME, including the dotprod/fp16/i8mm tiers this project
# cares about — which ggml scores and dlopen's at startup. That is what
# "ARM runtime feature dispatch" in CLAUDE.md means in practice; there is no
# separate ARM-only switch.
#
# The compile-time alternative, GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16,
# would SIGILL on any device lacking those extensions. Not worth it.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS       ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)  # mandatory when cross-compiling
set(GGML_BACKEND_DL         ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS   ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)  # NDK ships no CMake-installable OpenMP
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)  # llamafile has no Android support
set(GGML_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# Engine order is load-bearing.
#
# Both projects vendor their own ggml and both add it behind `if (NOT TARGET
# ggml)`, so whichever is added first supplies ggml to the other. whisper.cpp
# v1.9.3 vendors ggml 0.20.2; llama.cpp v0.3.0 vendors 0.22.0. llama.cpp goes
# first so the NEWER ggml wins.
#
# Verified safe rather than assumed: diffing the two vendored trees, no public
# ggml header is added or removed and only ggml.h differs (24 lines). The single
# semantic change is ggml_clamp losing its in-place behaviour to a new
# ggml_clamp_inplace — and whisper.cpp does not reference ggml_clamp anywhere.
# So whisper's sources compile unmodified against 0.22.0.
#
# If a future bump breaks this, the escape hatch is whisper's
# WHISPER_USE_SYSTEM_GGML (which WHISPER_USE_SYSTEM_LLAMA force-enables).
# ---------------------------------------------------------------------------
set(LLAMA_BUILD_IS_DEV   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)  # json_schema_to_grammar lives here
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)  # no multimodal in v1
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)  # nothing here talks to the network
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

set(WHISPER_BUILD_IS_DEV   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# The JNI bridge itself: one small .so that Kotlin loads by name.
# ---------------------------------------------------------------------------
add_library(themachine SHARED
    machine_jni.cpp
)

target_link_libraries(themachine PRIVATE
    whisper
    llama
    # Named llama-common, not common. It carries json_schema_to_grammar, which P4
    # needs to constrain the model's output to the intent schema.
    llama-common
    ggml
    android
    log
)

target_compile_options(themachine PRIVATE -Wall -Wextra -Wno-unused-parameter)

# Keep JNI_OnLoad and the Java_* entry points; everything else stays internal so
# the bridge does not re-export whisper/llama symbols into the global namespace.
target_link_options(themachine PRIVATE -Wl,--exclude-libs,ALL)

# 16 KB page alignment. NDK r28+ emits this by default, but Android 15+ devices
# refuse to load an unaligned library at all, so it is stated explicitly rather
# than inherited — a future NDK downgrade would otherwise break it silently.
target_link_options(themachine PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
