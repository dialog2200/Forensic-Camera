package com.forensic.camera.crypto

import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.digests.SM3Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithID
import org.bouncycastle.crypto.signers.SM2Signer
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 国密算法工具：SM2 签名 / SM3 摘要 / HMAC-SM3，以及证据文件 SHA-256。
 * 与腾讯至信链官方 Go SDK（tjfoc/gmsm 风格）行为对齐：
 *  - SM2 签名: e = SM3(ZA || M)，ZA 由默认 UID "1234567812345678" + 公钥计算，输出 ASN.1 DER (r,s)
 *  - API 鉴权: hex( HMAC-SM3( key = secretKey, msg = secretId&secretKey&signTime&nonce ) )
 *  - 链地址:   "ZX" + hex( SM3( 0x04||X||Y )[0:20] )
 */
object GmCrypto {

    private const val CURVE = "sm2p256v1"
    private val DEFAULT_UID = "1234567812345678".toByteArray(Charsets.UTF_8)

    init {
        // Android 系统自带一个同名 "BC" 的旧版 BouncyCastle（com.android.org.bouncycastle，
        // 不含 EC/SM2 服务），且 JCA 不允许重名 provider 并存。若不移除系统内置的，
        // 我们打包的 bcprov 永远注册不上，KeyFactory("EC","BC") 会报
        // "no such algorithm: EC for provider BC"。
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing != null && existing !is BouncyCastleProvider) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ---------- SHA-256（证据完整性摘要，用户明确要求） ----------

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    // ---------- SM3 / HMAC-SM3 ----------

    fun sm3Hex(data: ByteArray): String {
        val digest = SM3Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out.toHex()
    }

    fun hmacSm3Hex(key: String, msg: String): String {
        val hmac = HMac(SM3Digest())
        hmac.init(KeyParameter(key.toByteArray(Charsets.UTF_8)))
        val data = msg.toByteArray(Charsets.UTF_8)
        hmac.update(data, 0, data.size)
        val out = ByteArray(hmac.macSize)
        hmac.doFinal(out, 0)
        return out.toHex()
    }

    // ---------- SM2 密钥对 ----------

    /** 生成 SM2 密钥对，返回 (公钥PEM, 私钥PEM)，均为标准 PKIX / PKCS#8 PEM。 */
    fun generateSm2KeyPairPem(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(ECNamedCurveGenParameterSpec(CURVE))
        val kp = kpg.generateKeyPair()
        val pubPem = toPem("PUBLIC KEY", kp.public.encoded)
        val priPem = toPem("PRIVATE KEY", kp.private.encoded)
        return pubPem to priPem
    }

    /** 由私钥 PEM 导出公钥 PEM。 */
    fun pubPemFromPrivPem(privPem: String): String {
        val d = parsePrivateKey(privPem)
        val spec = ECNamedCurveTable.getParameterSpec(CURVE)
        val q = spec.g.multiply(d).normalize()
        val pubKp = org.bouncycastle.jce.spec.ECPublicKeySpec(q, spec)
        val kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        return toPem("PUBLIC KEY", kf.generatePublic(pubKp).encoded)
    }

    /** 链上账户地址：ZX + hex(SM3(uncompressed pubkey)[:20])。 */
    fun addressFromPubPem(pubPem: String): String {
        val kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        val pub = kf.generatePublic(X509EncodedKeySpec(fromPem(pubPem)))
        val q = (pub as org.bouncycastle.jce.interfaces.ECPublicKey).q
        val encoded = q.getEncoded(false) // 0x04 || X || Y
        val digest = SM3Digest()
        digest.update(encoded, 0, encoded.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return "ZX" + out.copyOfRange(0, 20).toHex()
    }

    /**
     * SM2 私钥签名，输入 UTF-8 字符串，输出 hex(ASN.1 DER)。
     * 至信链 ev/save 的 sign 字段 = signSm2Hex(privateKeyPem, publicKeyPem + "_" + hash + "_" + extendInfo)
     */
    fun signSm2Hex(privPem: String, message: String): String {
        val d = parsePrivateKey(privPem)
        val spec = ECNamedCurveTable.getParameterSpec(CURVE)
        val domain = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
        val signer = SM2Signer()
        signer.init(true, ParametersWithID(ECPrivateKeyParameters(d, domain), DEFAULT_UID))
        val data = message.toByteArray(Charsets.UTF_8)
        signer.update(data, 0, data.size)
        return signer.generateSignature().toHex()
    }

    /** 验签（本地自检：确认 sign 由该公钥对应私钥产生）。 */
    fun verifySm2(pubPem: String, message: String, sigHex: String): Boolean {
        return try {
            val kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
            val pub = kf.generatePublic(X509EncodedKeySpec(fromPem(pubPem)))
            val q = (pub as org.bouncycastle.jce.interfaces.ECPublicKey).q
            val spec = ECNamedCurveTable.getParameterSpec(CURVE)
            val domain = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
            val signer = SM2Signer()
            signer.init(false, ParametersWithID(ECPublicKeyParameters(q, domain), DEFAULT_UID))
            val data = message.toByteArray(Charsets.UTF_8)
            signer.update(data, 0, data.size)
            signer.verifySignature(sigHex.hexToBytes())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * SM2 公钥加密（C1C3C2，原始编码 04||x||y||C3||C2），用于 DEK 的公钥托管。
     * 私钥持有方可在 PC 上用同一曲线与本模式解封（见交付包 tools/）。
     */
    fun sm2Encrypt(pubPem: String, data: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        val pub = kf.generatePublic(X509EncodedKeySpec(fromPem(pubPem)))
        val q = (pub as org.bouncycastle.jce.interfaces.ECPublicKey).q
        val spec = ECNamedCurveTable.getParameterSpec(CURVE)
        val domain = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(true, ParametersWithRandom(ECPublicKeyParameters(q, domain), java.security.SecureRandom()))
        return engine.processBlock(data, 0, data.size)
    }

    /** SM2 公钥托管的反向操作（C1C3C2）：用私钥解回 DEK；任何失败返回 null。 */
    fun sm2Decrypt(privPem: String, ct: ByteArray): ByteArray? = runCatching {
        val d = parsePrivateKey(privPem)
        val spec = ECNamedCurveTable.getParameterSpec(CURVE)
        val domain = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(false, ECPrivateKeyParameters(d, domain))
        engine.processBlock(ct, 0, ct.size)
    }.getOrNull()

    private fun parsePrivateKey(privPem: String): BigInteger {        val kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        val key = kf.generatePrivate(PKCS8EncodedKeySpec(fromPem(privPem)))
        return (key as java.security.interfaces.ECPrivateKey).s
    }

    // ---------- PEM / hex helpers ----------

    private fun toPem(type: String, der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der)
        val sb = StringBuilder()
        sb.append("-----BEGIN ").append(type).append("-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        sb.append("-----END ").append(type).append("-----\n")
        return sb.toString()
    }

    private fun fromPem(pem: String): ByteArray {
        // 容错手机输入法粘贴：各类 Unicode 破折号/连字符变体归一化为 ASCII '-'
        val sb = StringBuilder(pem.length)
        for (ch in pem) {
            sb.append(when (ch) {
                '‐', '‑', '‒', '–', '—', '―', '─', '－' -> '-'
                else -> ch
            })
        }
        val s = sb.toString()
        // 优先取 BEGIN/END 标记之间的主体；标记残缺则去掉标记取全文
        val body = Regex("-----BEGIN[A-Z ]*-----(\\s*)([\\s\\S]*?)-----END[A-Z ]*-----")
            .find(s)?.groupValues?.get(2)
            ?: s.replace(Regex("-----BEGIN[A-Z ]*-----|-----END[A-Z ]*-----"), "")
        // 只保留 base64 字母表，剔除零宽字符、智能引号、行号等一切杂质
        val b64 = body.filter {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
        }
        return Base64.getMimeDecoder().decode(b64)
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray {
        val s = if (length % 2 == 0) this else "0$this"
        return ByteArray(s.length / 2) {
            s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
