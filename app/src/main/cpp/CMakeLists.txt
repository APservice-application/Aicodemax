# CP-146: the AI engine is compiled BY the Gradle build on ANY machine
# (local or CI) — no manual native builds, no prebuilt .so in git.
#
# Pinned engines (same versions + flags the old CI shell build used):
#   llama.cpp v0.4.1   (chat inference, in-process, no server/tools)
#   whisper.cpp v1.9.4 (voice transcription, in-process)
# + aicode_pty (terminal PTY bridge, plain C, no deps).
cmake_minimum_required(VERSION 3.22.1)
project(aicode_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ---------------------------------------------------------------- llama.cpp
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_OPENMP OFF)
set(LLAMA_CURL OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_APP OFF)
FetchContent_Declare(
    llamacpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v0.4.1.zip
)
FetchContent_MakeAvailable(llamacpp)

add_library(aicode_jni SHARED aicode_jni.cpp)
target_include_directories(
    aicode_jni PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/ggml/include
    ${llamacpp_SOURCE_DIR}/common
)
target_link_libraries(aicode_jni PRIVATE llama log android)

# -------------------------------------------------------------- whisper.cpp
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
FetchContent_Declare(
    whispercpp
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.zip
)
FetchContent_MakeAvailable(whispercpp)

add_library(aicode_whisper SHARED whisper_jni.cpp)
target_include_directories(
    aicode_whisper PRIVATE
    ${whispercpp_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}/ggml/include
)
target_link_libraries(aicode_whisper PRIVATE whisper log android)

# -------------------------------------------------------------------- pty
add_library(aicode_pty SHARED aicode_pty.c)
target_link_libraries(aicode_pty PRIVATE log)
