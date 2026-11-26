cmake_minimum_required(VERSION 3.22)
# NDK 在初始化工具链时读取 C++ 运行库选项。
set(ANDROID_STL c++_static CACHE STRING "NDK C++ runtime")
project(cnm LANGUAGES C CXX)

# 默认使用项目内准备的源码，桌面验证可传入其他位置。
if(NOT DEFINED CAPSTONE_SOURCE_DIR)
    get_filename_component(CAPSTONE_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../vendor/capstone" ABSOLUTE)
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM64_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(CAPSTONE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory("${CAPSTONE_SOURCE_DIR}" capstone EXCLUDE_FROM_ALL)

add_library(cnm SHARED pack_key_locator.cpp)
target_compile_features(cnm PRIVATE cxx_std_17)
target_link_libraries(cnm PRIVATE capstone_static)
set_target_properties(cnm PROPERTIES CXX_VISIBILITY_PRESET hidden)
if(ANDROID)
    target_link_options(cnm PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
endif()
