// 对称密码学封装层：AES-256-GCM / SHA-256 / Base64 / 安全清零。
//
// 实现由内置 mbedTLS 3.6.2 提供（仅编译 aes/gcm/sha256/base64 最小子集，
// 许可见 third_party/mbedtls/LICENSE）。本项目不再自写分组密码与 GCM。
// 启动时用 NIST 已知答案向量自检，任何一项不符即拒绝加密。
#ifndef FC_CRYPTO_H
#define FC_CRYPTO_H

#include <mbedtls/sha256.h>

#include <cstdint>
#include <string>
#include <vector>

namespace fcc {

constexpr int kDekBytes = 32;
constexpr int kNonceBytes = 12;
constexpr int kTagBytes = 16;

/** AES-256-GCM 加密；输出密文与 16 字节标签。返回 false 表示参数非法或内部失败。 */
bool gcmSeal(const uint8_t key[32], const uint8_t* nonce, int nonceLen,
             const uint8_t* aad, int aadLen,
             const uint8_t* plain, int plainLen,
             std::vector<uint8_t>& cipher, uint8_t tag[kTagBytes]);

/** AES-256-GCM 解密并验签；标签不符返回 false（该段视为被改动）。 */
bool gcmOpen(const uint8_t key[32], const uint8_t* nonce, int nonceLen,
             const uint8_t* aad, int aadLen,
             const uint8_t* cipher, int cipherLen,
             const uint8_t tag[kTagBytes], std::vector<uint8_t>& plain);

/** 流式 SHA-256。 */
struct Sha256 {
    Sha256();
    ~Sha256();
    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;
    void update(const void* data, size_t len);
    void finish(uint8_t out[32]);

private:
    mbedtls_sha256_context ctx_;
};

std::string sha256Hex(const void* data, size_t len);
std::string toHex(const uint8_t* data, size_t len);
std::vector<uint8_t> fromHex(const std::string& hex);
std::string base64Encode(const uint8_t* data, size_t len);
std::vector<uint8_t> base64Decode(const std::string& s);
void secureZero(void* p, size_t n);

/** NIST 已知答案自检：返回空串表示全部通过，否则返回失败项说明。 */
std::string cryptoSelfTest();

}  // namespace fcc

#endif
