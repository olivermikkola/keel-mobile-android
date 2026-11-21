cmake_minimum_required(VERSION 3.22.1)
project(keel_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED KEEL_LLAMA_SRC)
    set(KEEL_LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
endif()

option(KEEL_ENABLE_VULKAN "Enable ggml Vulkan GPU backend when toolchain supports it" OFF)

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
    endif()
endif()

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(KEEL_USE_VULKAN OFF)
if(KEEL_ENABLE_VULKAN)
    if(NOT DEFINED ANDROID_ABI OR ANDROID_ABI STREQUAL "arm64-v8a")
        set(KEEL_USE_VULKAN ON)
    endif()
endif()

if(KEEL_USE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # Expose ONLY the Khronos Vulkan C++ headers (vulkan.hpp) without adding host /usr/include
    if(ANDROID AND EXISTS "/usr/include/vulkan/vulkan.hpp")
        file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc")
        if(NOT EXISTS "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/vulkan")
            file(CREATE_LINK "/usr/include/vulkan" "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/vulkan" SYMBOLIC)
        endif()
        if(EXISTS "/usr/include/vk_video" AND NOT EXISTS "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/vk_video")
            file(CREATE_LINK "/usr/include/vk_video" "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/vk_video" SYMBOLIC)
        endif()
        if(EXISTS "/usr/include/spirv" AND NOT EXISTS "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/spirv")
            file(CREATE_LINK "/usr/include/spirv" "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/spirv" SYMBOLIC)
        endif()
    endif()
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${KEEL_LLAMA_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build" EXCLUDE_FROM_ALL)

if(KEEL_USE_VULKAN AND ANDROID AND TARGET ggml-vulkan AND EXISTS "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc/vulkan")
    target_include_directories(ggml-vulkan BEFORE PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/keel-vulkan-inc")
endif()

add_library(keel_llama SHARED
    keel_llama_jni.cpp
)

target_include_directories(keel_llama PRIVATE
    "${KEEL_LLAMA_SRC}/include"
    "${KEEL_LLAMA_SRC}/ggml/include"
    "${KEEL_LLAMA_SRC}/tools/mtmd"
)

if(KEEL_USE_VULKAN)
    target_compile_definitions(keel_llama PRIVATE KEEL_HAS_VULKAN=1)
endif()

if(ANDROID)
    target_link_libraries(keel_llama PRIVATE llama mtmd android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(keel_llama PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(keel_llama PRIVATE llama mtmd)
endif()
