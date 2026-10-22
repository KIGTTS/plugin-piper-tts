cmake_minimum_required(VERSION 3.18)
project(kigtts_piper_espeak LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include(ExternalProject)
set(ESPEAK_NG_SRC "${CMAKE_BINARY_DIR}/espeak-ng-build/src/espeak_ng_external")
set(ESPEAK_NG_LIB "${CMAKE_BINARY_DIR}/espeak-ng-build/src/espeak_ng_external-build/src/libespeak-ng")
ExternalProject_Add(espeak_ng_external
    PREFIX "${CMAKE_BINARY_DIR}/espeak-ng-build"
    URL "${CMAKE_CURRENT_LIST_DIR}/upstream/espeak-ng-0f65aa301e0d6bae5e172cc74197d32a6182200f.zip"
    URL_HASH SHA256=150fc955926a084a77ebe25c9e8287bde2e2a17bbf9b23a25c147b556837949f
    SOURCE_DIR "${ESPEAK_NG_SRC}"
    PATCH_COMMAND ${CMAKE_COMMAND} -DROOT=${ESPEAK_NG_SRC} -P "${CMAKE_CURRENT_LIST_DIR}/patch_espeak.cmake"
    BUILD_COMMAND ${CMAKE_COMMAND} --build . --target espeak-ng
    INSTALL_COMMAND ""
    CMAKE_ARGS
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DUSE_ASYNC=OFF
        -DUSE_MBROLA=OFF -DUSE_LIBSONIC=OFF -DUSE_LIBPCAUDIO=OFF -DUSE_KLATT=OFF
        -DUSE_SPEECHPLAYER=OFF -DBUILD_TESTING=OFF -DENABLE_TESTS=OFF -DESPEAK_NG_BUILD_TESTS=OFF
        -DEXTRA_cmn=ON -DEXTRA_ru=ON -DCMAKE_C_FLAGS=-D_FILE_OFFSET_BITS=64
        -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=c++_static -DANDROID_NDK=${ANDROID_NDK}
    BUILD_BYPRODUCTS "${ESPEAK_NG_LIB}/libespeak-ng.so"
)
add_library(espeak_jni SHARED espeak_jni.cpp piper-phonemize/phonemize.cpp piper-phonemize/shared.cpp)
add_dependencies(espeak_jni espeak_ng_external)
target_include_directories(espeak_jni PRIVATE piper-phonemize "${ESPEAK_NG_SRC}/src/include")
target_link_directories(espeak_jni PRIVATE "${ESPEAK_NG_LIB}")
target_link_libraries(espeak_jni espeak-ng log)
target_compile_options(espeak_jni PRIVATE -fexceptions)
target_link_options(espeak_jni PRIVATE -Wl,-z,max-page-size=16384)
add_custom_command(TARGET espeak_jni POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${ESPEAK_NG_LIB}/libespeak-ng.so" "$<TARGET_FILE_DIR:espeak_jni>/libespeak-ng.so")
