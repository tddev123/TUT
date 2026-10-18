cmake_minimum_required(VERSION 3.22.1)
project("tut_whisper" C CXX)

# Whisper is far too slow without optimisation, so always build it as Release,
# even for the debug variant of the app.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Cortex-A55/A76/A78 class cores (Galaxy A17) all support dotprod + fp16.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
# x86_64 is only for the emulator, whose virtual CPU exposes SSE but not AVX, so it stays
# at ggml's baseline there (slow, but fine for checking the UI).

add_subdirectory(whisper.cpp)

add_library(tut_whisper SHARED whisper_jni.cpp)
target_link_libraries(tut_whisper PRIVATE whisper android log)
