/*
 * 本项目专用的 mbedTLS 最小配置（只开对称原语）。
 *
 * 只编译：AES-256、GCM、SHA-256、Base64、platform/error。
 * 不启用 TLS/X.509/PSA/BIGNUM 等模块，避免把整棵依赖树和体积带进安装包。
 *
 * 以 -DMBEDTLS_CONFIG_FILE='"config_fc.h"' 传给编译器。
 */
#ifndef FC_MBEDTLS_CONFIG_H
#define FC_MBEDTLS_CONFIG_H

/* 硬件加速：ARMv8 加密扩展（aarch64 上由 aesce.c 提供） */
#if defined(__aarch64__) || defined(_M_ARM64)
#define MBEDTLS_AESCE_C
#endif

#define MBEDTLS_AES_C
#define MBEDTLS_GCM_C
#define MBEDTLS_SHA256_C
#define MBEDTLS_BASE64_C
#define MBEDTLS_PLATFORM_C
#define MBEDTLS_ERROR_C

#endif /* FC_MBEDTLS_CONFIG_H */
