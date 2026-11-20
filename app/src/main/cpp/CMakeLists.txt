# 原生加密封装/核验库。
#
# 密码学只编译 mbedTLS 所需的最小子集（AES/GCM/SHA-256/Base64），
# 许可证见 third_party/mbedtls/LICENSE，未修改其源文件。
cmake_minimum_required(VERSION 3.22.1)
project(fcenc C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls)

set(MBEDTLS_SOURCES
        ${MBEDTLS_DIR}/library/aes.c
        ${MBEDTLS_DIR}/library/aesce.c
        ${MBEDTLS_DIR}/library/block_cipher.c
        ${MBEDTLS_DIR}/library/gcm.c
        ${MBEDTLS_DIR}/library/sha256.c
        ${MBEDTLS_DIR}/library/base64.c
        ${MBEDTLS_DIR}/library/constant_time.c
        ${MBEDTLS_DIR}/library/platform.c
        ${MBEDTLS_DIR}/library/platform_util.c
        ${MBEDTLS_DIR}/library/error.c)

add_library(fcenc SHARED
        writer.cpp
        mp4.cpp
        crypto.cpp
        jni.cpp
        ${MBEDTLS_SOURCES})

target_include_directories(fcenc PRIVATE
        ${MBEDTLS_DIR}
        ${MBEDTLS_DIR}/include)

# 极简配置：只开 AES/GCM/SHA-256/Base64/PLATFORM/ERROR
target_compile_definitions(fcenc PRIVATE MBEDTLS_CONFIG_FILE="config_fc.h")

target_compile_options(fcenc PRIVATE -O2 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)

target_link_libraries(fcenc PRIVATE log)
