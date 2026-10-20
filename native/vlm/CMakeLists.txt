cmake_minimum_required(VERSION 3.16)
project(vlm-test LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../llama.cpp)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

add_executable(vlm-test test_vlm.cpp)
target_include_directories(vlm-test PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(vlm-test PRIVATE llama mtmd)

add_executable(compare-vlm compare_vlm.cpp)
target_include_directories(compare-vlm PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(compare-vlm PRIVATE llama mtmd)
