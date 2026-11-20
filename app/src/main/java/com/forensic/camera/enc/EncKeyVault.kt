package com.forensic.camera.enc

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 录制期加密的密钥管理。
 *
 * 每条证据独立生成 32 字节 DEK（AES-256）；DEK 本身用 Android Keystore 中的
 * 包装密钥（AES-256-GCM）加密后随证据元数据落盘，明文 DEK 只在内存驻留。
 * 包装密钥优先 StrongBox，设备不支持时降级 TEE，并把实际等级记入 level（S/T/N）。
 *
 * 注意：Keystore 密钥不可导出、随应用卸载销毁，因此密文只能在本机还原；
 * 这是"密钥不出安全硬件"的必然代价，已在取证元数据中如实标注 sec 等级。
 */
object EncKeyVault {

    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "fc_enc_dek_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val DEK_BYTES = 32
    private const val DEK_NONCE = 12

    data class Wrapped(val b64: String, val level: String)

    private val rnd = SecureRandom()

    // #region debug-point keystore-key-lost
    /** 最近一次 Keystore 操作的具体诊断（异常类型/消息/返回类型/别名存在性），供 UI 与 logcat 取证。 */
    @Volatile
    var lastDiag: String = ""
        private set

    private fun diag(msg: String) {
        lastDiag = msg
        android.util.Log.d("FCDBG", msg)
    }
    // #endregion

    fun newDek(): ByteArray = ByteArray(DEK_BYTES).also { rnd.nextBytes(it) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }

    private fun levelFile(ctx: Context): File =
        File(ctx.filesDir, "enc").apply { mkdirs() }.let { File(it, "keylevel.txt") }

    /** 读取已记录的安全等级；无记录时按能力推断。 */
    fun recordedLevel(ctx: Context): String =
        runCatching { levelFile(ctx).readText().trim() }.getOrDefault("").ifEmpty { "N" }

    private fun createKey(alias: String, strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    /** 取（必要时创建）包装密钥，返回密钥与实际安全等级。 */
    @Synchronized
    private fun ensureKey(ctx: Context): Pair<SecretKey, String> {
        // #region debug-point keystore-key-lost
        val getTry = runCatching { keyStore().getKey(ALIAS, null) }
        val raw = getTry.getOrNull()
        val existing = raw as? SecretKey
        if (existing != null) {
            val lv = recordedLevel(ctx)
            diag("ensureKey:existing alias=$ALIAS class=${raw!!.javaClass.name}")
            return existing to (if (lv == "N") "T" else lv)
        }
        diag("ensureKey:getKey未得 raw=${raw?.javaClass?.name ?: "null"} " +
            "err=${getTry.exceptionOrNull()?.let { it.javaClass.simpleName + ":" + it.message }}")
        // #endregion
        // 首次创建：优先 StrongBox（Android 9 / API 28+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sbTry = runCatching { createKey(ALIAS, true) }
            val sb = sbTry.getOrNull()
            if (sb != null) {
                runCatching { levelFile(ctx).writeText("S") }
                diag("ensureKey:created STRONGBOX alias=$ALIAS")
                return sb to "S"
            }
            diag("ensureKey:strongbox失败 ${sbTry.exceptionOrNull()?.let { it.javaClass.simpleName + ":" + it.message }}")
        }
        val teeTry = runCatching { createKey(ALIAS, false) }
        val tee = teeTry.getOrNull()
        if (tee != null) {
            runCatching { levelFile(ctx).writeText("T") }
            diag("ensureKey:created TEE alias=$ALIAS")
            return tee to "T"
        }
        // 创建失败但 getKey 也没拿到：多为 Keystore 服务瞬时抖动、密钥其实已存在。
        // 再取一次，避免把「已有机密」误判成可新建，进而静默产出无法还原的证据。
        val retryTry = runCatching { keyStore().getKey(ALIAS, null) }
        val retry = retryTry.getOrNull() as? SecretKey
        if (retry != null) {
            val lv = recordedLevel(ctx)
            diag("ensureKey:retry-hit（创建失败但密钥存在）")
            return retry to (if (lv == "N") "T" else lv)
        }
        diag("ensureKey:全部失败 createErr=${teeTry.exceptionOrNull()?.let { it.javaClass.simpleName + ":" + it.message }}")
        throw IllegalStateException("Keystore 包装密钥不可用")
    }

    /** 取（必要时创建）指定别名的包装密钥；默认 StrongBox 优先、降级 TEE。
     *  forceTee=true 时只建 TEE（StrongBox 密钥不可用时的回退路径）。 */
    @Synchronized
    private fun ensureNamedKey(alias: String, forceTee: Boolean = false): SecretKey {
        val existing = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing
        if (!forceTee && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sb = runCatching { createKey(alias, true) }.getOrNull()
            if (sb != null) return sb
        }
        val tee = runCatching { createKey(alias, false) }.getOrNull()
        if (tee != null) return tee
        val retry = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
        if (retry != null) return retry
        throw IllegalStateException("Keystore 包装密钥不可用：$alias")
    }

    private fun wrapBlobOnce(alias: String, data: ByteArray, forceTee: Boolean): String {
        val key = ensureNamedKey(alias, forceTee)
        // AndroidKeyStore 默认要求随机 IV：加密时禁止调用方传入 IV，必须由 Cipher 生成后取回。
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv ?: throw IllegalStateException("Cipher 未生成 IV")
        if (iv.size != DEK_NONCE) throw IllegalStateException("IV 长度异常: ${iv.size}")
        val ct = c.doFinal(data)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /**
     * 通用 Keystore 包裹（用于 SecretKey、SM2 私钥 PEM 等敏感配置）。
     * 输出 base64(iv(12) || ct+tag)。
     *
     * 配置密钥优先使用 <alias>_tee（TEE）：StrongBox 在部分机型上存在"写入成功、
     * 重启后解不开"的可靠性问题；TEE 仍是硬件密钥，稳定性更高。TEE 失败才回退主别名。
     * 每次包裹后立即回环解密自检；全部失败返回 null，由 SettingsStore 拒绝覆盖旧配置。
     */
    fun wrapBlob(alias: String, data: ByteArray): String? {
        // 1) 配置优先 TEE 回退别名
        val teeAlias = alias + "_tee"
        val tee = runCatching { wrapBlobOnce(teeAlias, data, true) }.getOrNull()
        if (tee != null) {
            val chk = runCatching { unwrapBlobWithAlias(teeAlias, tee) }.getOrNull()
            if (chk != null && chk.contentEquals(data)) {
                chk.fill(0)
                return tee
            }
            chk?.fill(0)
            diag("wrapBlob:TEE 回环失败 alias=$teeAlias -> 尝试主别名")
        } else {
            diag("wrapBlob:TEE 加密失败 alias=$teeAlias -> 尝试主别名")
        }
        // 2) 主别名（StrongBox 优先 -> TEE）
        val primary = runCatching { wrapBlobOnce(alias, data, false) }.getOrNull()
        if (primary != null) {
            val chk = runCatching { unwrapBlobWithAlias(alias, primary) }.getOrNull()
            if (chk != null && chk.contentEquals(data)) {
                chk.fill(0)
                return primary
            }
            chk?.fill(0)
        }
        diag("wrapBlob:FAIL alias=$alias TEE 与主别名均不可用")
        return null
    }

    /** 用指定别名解开通用包裹；返回原因 EMPTY/BAD_BLOB/NO_KEY/DECRYPT_FAIL。 */
    private fun unwrapBlobWithAliasDetailed(alias: String, b64: String): Pair<ByteArray?, String> {
        if (b64.isEmpty()) return null to "EMPTY"
        val all = runCatching {
            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return null to "BAD_BLOB"
        if (all.size <= DEK_NONCE) return null to "BAD_BLOB"
        val key = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
            ?: return null to "NO_KEY"
        val plain = runCatching {
            val iv = all.copyOfRange(0, DEK_NONCE)
            val ct = all.copyOfRange(DEK_NONCE, all.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            c.doFinal(ct)
        }.getOrNull()
        return if (plain != null) plain to "" else null to "DECRYPT_FAIL"
    }

    private fun unwrapBlobWithAlias(alias: String, b64: String): ByteArray? =
        unwrapBlobWithAliasDetailed(alias, b64).first

    /**
     * 解开通用包裹：先试主别名，再试 `<alias>_tee` 回退；返回解包原因。
     */
    fun unwrapBlobDetailed(alias: String, b64: String): Pair<ByteArray?, String> {
        val primary = unwrapBlobWithAliasDetailed(alias, b64)
        if (primary.first != null) return primary
        val fallback = unwrapBlobWithAliasDetailed(alias + "_tee", b64)
        if (fallback.first != null) return fallback
        val reason = when {
            primary.second == "DECRYPT_FAIL" || fallback.second == "DECRYPT_FAIL" -> "DECRYPT_FAIL"
            primary.second == "BAD_BLOB" || fallback.second == "BAD_BLOB" -> "BAD_BLOB"
            primary.second == "NO_KEY" && fallback.second == "NO_KEY" -> "NO_KEY"
            else -> primary.second
        }
        diag("unwrapBlob:FAIL alias=$alias reason=$reason " +
            "(primary=${primary.second}, fallback=${fallback.second})")
        return null to reason
    }

    /** 解开通用包裹；密钥不存在（换机/卸载/清数据）返回 null。 */
    fun unwrapBlob(alias: String, b64: String): ByteArray? = unwrapBlobDetailed(alias, b64).first

    /** 用 Keystore 包装密钥加密 DEK；输出 base64(iv(12) || ct+tag)。失败重试一次。 */
    fun wrapDek(ctx: Context, dek: ByteArray): Wrapped? {
        val first = runCatching { wrapDekOnce(ctx, dek) }
        if (first.isSuccess) return first.getOrNull()
        val second = runCatching { wrapDekOnce(ctx, dek) }
        if (second.isSuccess) return second.getOrNull()
        diag("wrapDek:FAIL first=${describe(first.exceptionOrNull())} " +
            "second=${describe(second.exceptionOrNull())} " +
            "strongbox=create/encrypt 详见上方日志")
        return null
    }

    private fun describe(t: Throwable?): String = buildString {
        var cur = t
        var depth = 0
        while (cur != null && depth < 3) {
            if (isNotEmpty()) append(" <- ")
            append(cur.javaClass.simpleName).append(": ").append(cur.message ?: "")
            cur = cur.cause
            depth++
        }
    }

    private fun wrapDekOnce(ctx: Context, dek: ByteArray): Wrapped {
        val (key, level) = ensureKey(ctx)
        // AndroidKeyStore 默认 IND-CPA：加密时拒绝调用方提供 IV（会抛
        // InvalidAlgorithmParameterException: Caller-provided IV not permitted）。
        // 正确做法是不传 IV，由 Cipher 生成随机 IV，再用 getIV() 取出随密文一起保存。
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv ?: throw IllegalStateException("Cipher 未生成 IV")
        if (iv.size != DEK_NONCE) throw IllegalStateException("IV 长度异常: ${iv.size}")
        val ct = c.doFinal(dek)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Wrapped(android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP), level)
    }

    /** 解开 DEK；换机/卸载后 Keystore 密钥不存在，返回 null（不是篡改，是密钥不可得）。 */
    fun unwrapDek(b64: String): ByteArray? = unwrapDekDetailed(b64).first

    /**
     * 带原因的 DEK 解封：reason ""（成功）/ EMPTY / BAD_BLOB / NO_KEY / DECRYPT_FAIL，
     * 供 UI 区分「录制时包裹失败 / 包裹体损坏 / 密钥丢失（清数据、重装、换机）/ 解密失败」。
     */
    fun unwrapDekDetailed(b64: String): Pair<ByteArray?, String> {
        if (b64.isEmpty()) { diag("unwrap:EMPTY(清单无包裹体)"); return null to "EMPTY" }
        val all = runCatching {
            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return null to "BAD_BLOB"
        if (all.size <= DEK_NONCE) { diag("unwrap:BAD_BLOB size=${all.size}"); return null to "BAD_BLOB" }
        // #region debug-point keystore-key-lost
        val getTry = runCatching { keyStore().getKey(ALIAS, null) }
        val raw = getTry.getOrNull()
        val key = raw as? SecretKey
        if (key == null) {
            val has = runCatching { keyStore().containsAlias(ALIAS) }
            diag("unwrap:NO_KEY raw=${raw?.javaClass?.name ?: "null"} " +
                "err=${getTry.exceptionOrNull()?.let { it.javaClass.simpleName + ":" + it.message }} " +
                "containsAlias=${has.getOrNull() ?: ("err:" + has.exceptionOrNull()?.message)}")
            return null to "NO_KEY"
        }
        // #endregion
        val dek = runCatching {
            val iv = all.copyOfRange(0, DEK_NONCE)
            val ct = all.copyOfRange(DEK_NONCE, all.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            c.doFinal(ct)
        }.getOrNull()
        if (dek == null || dek.size != 32) {
            diag("unwrap:DECRYPT_FAIL keyClass=${raw.javaClass.name}")
            return null to "DECRYPT_FAIL"
        }
        diag("unwrap:OK")
        return dek to ""
    }

    fun newNonce(): ByteArray = ByteArray(DEK_NONCE).also { rnd.nextBytes(it) }
}
