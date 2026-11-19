cmake_minimum_required(VERSION 3.22.1)
project(voxora_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
set(WHISPER_VERSION "1.9.5")

# Configure GGML for lightweight, self-contained Android CPU inference (zero paid/cloud libs)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

add_library(
    voxora_whisper
    SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/voxora_whisper_jni.cpp
)

target_include_directories(
    voxora_whisper
    PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(
    voxora_whisper
    PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION="${WHISPER_VERSION}"
)

# Always optimize Whisper math kernels (-O3) so real-time offline speech recognition is fast on ARM64
target_compile_options(voxora_whisper PRIVATE -O3)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(voxora_whisper PRIVATE -march=armv8-a)
endif ()

find_library(LOG_LIB log)

target_link_libraries(
    voxora_whisper
    PRIVATE
    ggml
    ${LOG_LIB}
    android
)
