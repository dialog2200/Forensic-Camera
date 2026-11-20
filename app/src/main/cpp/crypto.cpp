// 对称密码学封装层实现（mbedTLS 3.6.2 最小子集）。
#include "crypto.h"

#include <mbedtls/base64.h>
#include <mbedtls/gcm.h>
#include <mbedtls/platform_util.h>

#include <cstring>

namespace fcc {
namespace {

// 长度为 0 时也给出合法非空指针，避免个别实现把 nullptr 当成非法参数。
uint8_t g_dummy = 0;

inline uint8_t* ptrOrDummy(std::vector<uint8_t>& v) {
    return v.empty() ? &g_dummy : v.data();
}

bool gcmSetup(mbedtls_gcm_context* ctx, const uint8_t key[32]) {
    mbedtls_gcm_init(ctx);
    return mbedtls_gcm_setkey(ctx, MBEDTLS_CIPHER_ID_AES, key, 256) == 0;
}

}  // namespace

bool gcmSeal(const uint8_t key[32], const uint8_t* nonce, int nonceLen,
             const uint8_t* aad, int aadLen,
             const uint8_t* plain, int plainLen,
             std::vector<uint8_t>& cipher, uint8_t tag[kTagBytes]) {
    if (key == nullptr || nonce == nullptr || nonceLen <= 0) return false;
    if (plainLen < 0 || aadLen < 0) return false;
    if (plainLen > 0 && plain == nullptr) return false;
    if (aadLen > 0 && aad == nullptr) return false;

    mbedtls_gcm_context ctx;
    if (!gcmSetup(&ctx, key)) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    cipher.assign(static_cast<size_t>(plainLen), 0);
    int rc = mbedtls_gcm_crypt_and_tag(
        &ctx, MBEDTLS_GCM_ENCRYPT, static_cast<size_t>(plainLen),
        nonce, static_cast<size_t>(nonceLen),
        aad, static_cast<size_t>(aadLen),
        plainLen > 0 ? plain : &g_dummy,
        ptrOrDummy(cipher),
        kTagBytes, tag);
    mbedtls_gcm_free(&ctx);
    if (rc != 0) {
        secureZero(cipher.data(), cipher.size());
        cipher.clear();
        return false;
    }
    return true;
}

bool gcmOpen(const uint8_t key[32], const uint8_t* nonce, int nonceLen,
             const uint8_t* aad, int aadLen,
             const uint8_t* cipher, int cipherLen,
             const uint8_t tag[kTagBytes], std::vector<uint8_t>& plain) {
    if (key == nullptr || nonce == nullptr || nonceLen <= 0 || tag == nullptr) return false;
    if (cipherLen < 0 || aadLen < 0) return false;
    if (cipherLen > 0 && cipher == nullptr) return false;
    if (aadLen > 0 && aad == nullptr) return false;

    mbedtls_gcm_context ctx;
    if (!gcmSetup(&ctx, key)) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    plain.assign(static_cast<size_t>(cipherLen), 0);
    int rc = mbedtls_gcm_auth_decrypt(
        &ctx, static_cast<size_t>(cipherLen),
        nonce, static_cast<size_t>(nonceLen),
        aad, static_cast<size_t>(aadLen),
        tag, kTagBytes,
        cipherLen > 0 ? cipher : &g_dummy,
        ptrOrDummy(plain));
    mbedtls_gcm_free(&ctx);
    if (rc != 0) {  // 含 MBEDTLS_ERR_GCM_AUTH_FAILED：该段被改动
        secureZero(plain.data(), plain.size());
        plain.clear();
        return false;
    }
    return true;
}

Sha256::Sha256() {
    mbedtls_sha256_init(&ctx_);
    // mbedTLS 3.x 的 init 只清零，必须显式 starts 才装载初始哈希常量。
    mbedtls_sha256_starts(&ctx_, 0);
}
Sha256::~Sha256() {
    secureZero(&ctx_, sizeof(ctx_));
    mbedtls_sha256_free(&ctx_);
}
void Sha256::update(const void* data, size_t len) {
    if (data != nullptr && len > 0) {
        mbedtls_sha256_update(&ctx_, static_cast<const uint8_t*>(data), len);
    }
}
void Sha256::finish(uint8_t out[32]) { mbedtls_sha256_finish(&ctx_, out); }

std::string toHex(const uint8_t* data, size_t len) {
    static const char* kHex = "0123456789abcdef";
    std::string s;
    s.resize(len * 2);
    for (size_t i = 0; i < len; ++i) {
        s[2 * i] = kHex[data[i] >> 4];
        s[2 * i + 1] = kHex[data[i] & 0x0F];
    }
    return s;
}

std::vector<uint8_t> fromHex(const std::string& hex) {
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    std::vector<uint8_t> out;
    if (hex.size() % 2 != 0) return out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        int hi = nib(hex[i]);
        int lo = nib(hex[i + 1]);
        if (hi < 0 || lo < 0) return {};
        out.push_back(static_cast<uint8_t>((hi << 4) | lo));
    }
    return out;
}

std::string sha256Hex(const void* data, size_t len) {
    Sha256 h;
    h.update(data, len);
    uint8_t out[32];
    h.finish(out);
    return toHex(out, sizeof(out));
}

std::string base64Encode(const uint8_t* data, size_t len) {
    size_t need = 0;
    if (mbedtls_base64_encode(nullptr, 0, &need, data, len) !=
            MBEDTLS_ERR_BASE64_BUFFER_TOO_SMALL &&
        need == 0) {
        return std::string();
    }
    std::string out;
    out.resize(need);
    size_t used = 0;
    if (mbedtls_base64_encode(reinterpret_cast<unsigned char*>(&out[0]), need, &used, data, len) != 0) {
        return std::string();
    }
    out.resize(used);
    return out;
}

std::vector<uint8_t> base64Decode(const std::string& s) {
    std::vector<uint8_t> out;
    if (s.empty()) return out;
    size_t need = 0;
    if (mbedtls_base64_decode(nullptr, 0, &need,
                              reinterpret_cast<const unsigned char*>(s.data()), s.size()) !=
            MBEDTLS_ERR_BASE64_BUFFER_TOO_SMALL &&
        need == 0) {
        return out;
    }
    out.resize(need);
    size_t used = 0;
    if (mbedtls_base64_decode(out.data(), need, &used,
                              reinterpret_cast<const unsigned char*>(s.data()), s.size()) != 0) {
        out.clear();
        return out;
    }
    out.resize(used);
    return out;
}

void secureZero(void* p, size_t n) {
    if (p != nullptr && n > 0) mbedtls_platform_zeroize(p, n);
}

// ---------------------------------------------------------------------------
// NIST 已知答案自检（与实现解耦，改实现也必须过这几组向量）
// ---------------------------------------------------------------------------
namespace {

bool eqHex(const std::string& a, const char* b) {
    return a.size() == strlen(b) && memcmp(a.data(), b, a.size()) == 0;
}

}  // namespace

std::string cryptoSelfTest() {
    const uint8_t zeros32[32] = {0};
    const uint8_t iv[12] = {0};
    uint8_t tag[kTagBytes];
    std::vector<uint8_t> ct;

    // 1) AES-256-GCM，空明文：tag 必须等于规范值
    if (!gcmSeal(zeros32, iv, sizeof(iv), nullptr, 0, nullptr, 0, ct, tag)) {
        return "selftest: gcm seal(empty) failed";
    }
    if (!ct.empty()) return "selftest: gcm empty ciphertext not empty";
    if (!eqHex(toHex(tag, sizeof(tag)), "530f8afbc74536b9a963b4f1c4cb738b")) {
        return "selftest: gcm empty tag mismatch";
    }

    // 2) AES-256-GCM，16 字节零明文：密文与 tag 都必须是规范值
    const uint8_t p16[16] = {0};
    if (!gcmSeal(zeros32, iv, sizeof(iv), nullptr, 0, p16, sizeof(p16), ct, tag)) {
        return "selftest: gcm seal(16B) failed";
    }
    if (!eqHex(toHex(ct.data(), ct.size()), "cea7403d4d606b6e074ec5d3baf39d18")) {
        return "selftest: gcm 16B ciphertext mismatch";
    }
    if (!eqHex(toHex(tag, sizeof(tag)), "d0d1c8a799996bf0265b98b5d48ab919")) {
        return "selftest: gcm 16B tag mismatch";
    }

    // 3) 解密回路
    std::vector<uint8_t> back;
    if (!gcmOpen(zeros32, iv, sizeof(iv), nullptr, 0, ct.data(), static_cast<int>(ct.size()), tag, back)) {
        return "selftest: gcm open failed";
    }
    if (back.size() != sizeof(p16) || memcmp(back.data(), p16, sizeof(p16)) != 0) {
        return "selftest: gcm roundtrip mismatch";
    }

    // 4) 篡改检测：翻一位后必须解不开
    ct[0] ^= 0x01;
    if (gcmOpen(zeros32, iv, sizeof(iv), nullptr, 0, ct.data(), static_cast<int>(ct.size()), tag, back)) {
        return "selftest: tampered ciphertext accepted";
    }
    ct[0] ^= 0x01;  // 还原，避免掩盖后续问题

    // 5) SHA-256 已知答案
    if (!eqHex(sha256Hex("abc", 3),
               "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) {
        return "selftest: sha256(abc) mismatch";
    }
    if (!eqHex(sha256Hex("", 0),
               "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")) {
        return "selftest: sha256(empty) mismatch";
    }

    // 6) Base64 回路
    const uint8_t raw[5] = {0x00, 0xFF, 0x10, 0x7F, 0x80};
    std::string b64 = base64Encode(raw, sizeof(raw));
    std::vector<uint8_t> dec = base64Decode(b64);
    if (dec.size() != sizeof(raw) || memcmp(dec.data(), raw, sizeof(raw)) != 0) {
        return "selftest: base64 roundtrip mismatch";
    }

    return std::string();
}

}  // namespace fcc
