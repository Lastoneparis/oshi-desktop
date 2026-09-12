# =============================================================================
# libllama_jni — build recipe.   PARITY.md row 2.5.
# =============================================================================
#
# NEVER BUILT ON THE DEVELOPMENT MACHINE. This is driven by the `native-llama` job in
# .github/workflows/desktop-ci.yml, on windows-latest and ubuntu-latest, because the Mac
# this was written on cannot produce a Windows binary and inventing one would be worse
# than having none.
#
# Usage:
#   cmake -S platform/llama-jni -B build/native \
#         -DLLAMA_CPP_DIR=<a checkout of ggml-org/llama.cpp> \
#         -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/native --config Release
#
# Produces ONE file: libllama_jni.so (Linux) / llama_jni.dll (Windows) /
# libllama_jni.dylib (macOS). That is deliberate — see BUILD_SHARED_LIBS below.

cmake_minimum_required(VERSION 3.16)
project(llama_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR
        "-DLLAMA_CPP_DIR=<path to a llama.cpp checkout> is required. Nothing is fetched "
        "from here: a build that silently downloads a dependency is a build whose output "
        "nobody can reproduce or attribute.")
endif()
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_DIR=${LLAMA_CPP_DIR} is not a llama.cpp checkout.")
endif()

# ONE artifact, statically linked.
#
# The alternative — a shared libllama beside the shim — breaks on Windows in a way that is
# annoying to diagnose: the JVM loads llama_jni.dll by absolute path, and Windows then
# resolves ITS dependencies against the process directory, System32 and PATH, NOT against
# the directory the DLL came from. So a perfectly good pair of DLLs sitting side by side
# fails to load with error 126 and the JVM reports it as "can't find llama_jni" — naming
# the file that IS present. Static linking removes the whole class of problem and makes
# the thing the CI job uploads a single file a user can drop in one directory.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL          OFF CACHE BOOL "" FORCE)

# CPU only. No CUDA, no Metal, no Vulkan: an installer that assumes a GPU runtime is an
# installer that fails on most desktops, and CI has no GPU to verify one against anyway.
# Whoever wants an accelerated build can flip these and own the result.
set(GGML_CUDA   OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL  OFF CACHE BOOL "" FORCE)

# ...and no -march=native. It defaults ON in llama.cpp and it produces a binary that runs
# on the CI runner and SIGILLs on an older user CPU — the worst possible failure, because
# it is invisible until someone else's machine dies on an illegal instruction.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

find_package(JNI REQUIRED)

add_library(llama_jni SHARED llama_jni.cpp)
target_include_directories(llama_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(llama_jni PRIVATE llama ggml)

# The library NAME is not ours to choose: the shared LlamaCpp.kt says
# System.loadLibrary("llama_jni"), so the file has to map to exactly that.
set_target_properties(llama_jni PROPERTIES
    OUTPUT_NAME "llama_jni"
    PREFIX "${CMAKE_SHARED_LIBRARY_PREFIX}")

if(UNIX AND NOT APPLE)
    # Belt and braces for anyone who turns BUILD_SHARED_LIBS back on.
    set_target_properties(llama_jni PROPERTIES
        BUILD_RPATH "$ORIGIN" INSTALL_RPATH "$ORIGIN")
endif()
