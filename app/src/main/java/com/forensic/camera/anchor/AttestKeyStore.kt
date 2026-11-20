package com.forensic.camera.anchor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.forensic.camera.crypto.GmCrypto
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * T2 硬件 Keystore 出处证明（v1.3.0）。
 *
 * 每次拍摄生成一把临时 EC P-256 密钥（AndroidKeyStore，不可导出），
 * 把本次复合锚定值 anchorHash（64位hex字符串的 UTF-8 字节）写入 attestation challenge，
 * 再用该密钥对 anchorHash 做 SHA256withECDSA 签名，导出证书链 + 签名。
 *
 * attBlob 二进制布局（核验端 zx_anchor.py verify-att 按同一布局解析）：
 *   [1B 证书数 n][cert1 DER][cert2 DER]...[certn DER][签名 DER(SHA256withECDSA, X9.62 SEQUENCE)]
 * attSha256 = SHA-256(attBlob) 的 hex，写入链上 extendInfo 前缀 att=<hex>，
 * 从而把整段证明绑定进至信链存证。
 *
 * 证明等级 lvl：
 *   H = leaf 证书含 Attestation 扩展（OID 1.3.6.1.4.1.11129.2.1.17），硬件密钥、链可追到出厂根
 *   S = Keystore 生成但无 attestation 扩展（软件 keystore / 无出厂证明），仍证明密钥在安全存储内签名
 *   N = 生成或签名失败（无证明）
 *
 * V1 复合串格式不变，向后兼容 v1.2.0 台账。
 */
object AttestKeyStore {

    private const val ANDROID_NS = "AndroidKeyStore"
    private const val ATTEST_OID = "1.3.6.1.4.1.11129.2.1.17"

    const val LEVEL_HARDWARE = "H"
    const val LEVEL_SOFTWARE = "S"
    const val LEVEL_NONE = "N"

    data class Attestation(
        val attBlobB64: String = "",  // attBlob 的 base64(NO_WRAP)
        val attSha256: String = "",   // SHA-256(attBlob) hex
        val level: String = LEVEL_NONE,
        val error: String = ""
    )

    /**
     * 对 anchorHashHex 出具一次证明。在后台线程调用（Keystore 操作约几十毫秒）。
     * challenge 与签名原文均为 anchorHashHex 的 UTF-8 字节，核验端逐字节重算。
     */
    fun attest(anchorHashHex: String): Attestation {
        val alias = "att_" + UUID.randomUUID().toString().replace("-", "")
        return try {
            val ks = KeyStore.getInstance(ANDROID_NS).apply { load(null) }
            val challenge = anchorHashHex.toByteArray(Charsets.UTF_8)

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()

            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_NS)
            gen.initialize(spec)
            gen.generateKeyPair()

            val chainDer = ks.getCertificateChain(alias).map { it.encoded }
            val leaf = ks.getCertificate(alias) as X509Certificate

            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initSign(ks.getKey(alias, null) as java.security.PrivateKey, null)
                update(challenge)
            }.sign()

            val blob = ByteArrayOutputStream().apply {
                write(chainDer.size)
                chainDer.forEach { write(it) }
                write(sig)
            }.toByteArray()

            Attestation(
                attBlobB64 = Base64.encodeToString(blob, Base64.NO_WRAP),
                attSha256 = GmCrypto.sha256Hex(blob),
                level = classify(chainDer.size, leaf)
            )
        } catch (e: Exception) {
            Attestation(level = LEVEL_NONE, error = (e.message ?: e.javaClass.simpleName).take(120))
        } finally {
            runCatching {
                KeyStore.getInstance(ANDROID_NS).apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private fun classify(chainSize: Int, leaf: X509Certificate): String = try {
        if (leaf.getExtensionValue(ATTEST_OID) != null) LEVEL_HARDWARE else LEVEL_SOFTWARE
    } catch (e: Exception) {
        if (chainSize >= 1) LEVEL_SOFTWARE else LEVEL_NONE
    }

    /** 证明等级的人读名称（UI 展示用）。 */
    fun levelName(lvl: String): String = when (lvl) {
        LEVEL_HARDWARE -> "H 硬件Keystore证明"
        LEVEL_SOFTWARE -> "S 软件Keystore证明"
        else -> "N 无证明"
    }
}
