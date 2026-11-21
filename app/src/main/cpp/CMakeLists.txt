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
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${KEEL_LLAMA_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build" EXCLUDE_FROM_ALL)

add_library(keel_llama SHARED
    keel_llama_jni.cpp
)

target_include_directories(keel_llama PRIVATE
    "${KEEL_LLAMA_SRC}/include"
    "${KEEL_LLAMA_SRC}/ggml/include"
)

if(ANDROID)
    target_link_libraries(keel_llama PRIVATE llama android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(keel_llama PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(keel_llama PRIVATE llama)
endif()
