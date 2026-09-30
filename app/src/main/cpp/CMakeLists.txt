cmake_minimum_required(VERSION 3.22.1)
project(charlie LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# Path to third_party repos (cloned by setup.sh)
# From: app/src/main/cpp/ → project root is ../../../../
# ============================================================
set(THIRD_PARTY ${CMAKE_SOURCE_DIR}/../../../../third_party)
set(LLAMA_DIR ${THIRD_PARTY}/llama.cpp)
set(WHISPER_DIR ${THIRD_PARTY}/whisper.cpp)

find_library(LOG_LIB log)

# Verify deps exist
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_DIR}\n"
        "Run ./setup.sh from the project root first!")
endif()

if(NOT EXISTS ${WHISPER_DIR}/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}\n"
        "Run ./setup.sh from the project root first!")
endif()

# ============================================================
# Build llama.cpp from source (includes ggml)
# This is the "base" that provides ggml for everything
# ============================================================
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Disable everything we don't need
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# GPU backends OFF — CPU only for Galaxy M15
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_MUSA OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_CANN OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_AARCH64 ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama_build)

# ============================================================
# Build whisper from source using llama's ggml
# We compile whisper.cpp directly, linking against llama's ggml
# ============================================================
add_library(whisper-lib STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)

target_include_directories(whisper-lib PUBLIC
    ${WHISPER_DIR}/include
)

# Use ggml headers from llama.cpp's copy
target_include_directories(whisper-lib PRIVATE
    ${WHISPER_DIR}/src
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/ggml/src
)

# Link against llama's ggml
target_link_libraries(whisper-lib PRIVATE ggml)

target_compile_definitions(whisper-lib PRIVATE
    WHISPER_VERSION="1.8.4"
)

target_compile_options(whisper-lib PRIVATE
    -O3 -DNDEBUG -fPIC
)

# ============================================================
# Build the JNI bridge library
# This single .so contains bridges for both whisper and llama
# ============================================================
add_library(charlie SHARED
    charlie-whisper.cpp
    charlie-llama.cpp
)

target_include_directories(charlie PRIVATE
    ${WHISPER_DIR}/include
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(charlie
    whisper-lib
    llama
    ${LOG_LIB}
)

target_compile_options(charlie PRIVATE
    -O3 -DNDEBUG -fPIC
)
