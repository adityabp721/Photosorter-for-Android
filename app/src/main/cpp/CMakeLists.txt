cmake_minimum_required(VERSION 3.16)
project(vlm-ai LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    # KleidiAI disabled: caused pathologically slow inference on the S24U test device;
    # plain ggml ARM NEON/dotprod kernels are faster there.
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_OPENMP ON)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

# project_root/native/llama.cpp (this file lives in app/src/main/cpp/)
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../native/llama.cpp)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

# The NDK defaults to baseline armv8-a, which lacks the dotprod/i8mm/fp16
# instructions ggml's fast quantized-matmul kernels need (3-10x slower without
# them). Target ARMv8.2+ - fine on modern flagships like the S24 Ultra.
foreach(t ggml-base ggml-cpu ggml llama mtmd)
    target_compile_options(${t} PRIVATE -march=armv8.2-a+fp16+dotprod+i8mm)
endforeach()

add_library(vlm-ai SHARED vlm_jni.cpp)

target_compile_definitions(vlm-ai PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
    GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(vlm-ai PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(vlm-ai llama mtmd log)
