package com.forensic.camera.data

import android.content.Context
import com.forensic.camera.enc.EncKeyVault
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 至信链接入配置：SecretId/SecretKey + SM2 密钥对，仅存应用私有目录（不备份、不导出）。
 *
 * v3.4.0 起敏感字段（SecretKey、SM2 私钥 PEM）不再明文落盘：
 * 用 Android Keystore 包装密钥加密后存 secretKeyEnc / privKeyEnc（与录制 DEK 同一套机制，
 * StrongBox 优先、降级 TEE）。旧版本的明文字段在首次读取时自动迁移并重写。
 * 网关地址不再是可配置项，固定在 [com.forensic.camera.zxchain.ZxChainClient.BASE_URL]。
 */
object SettingsStore {

    private const val ALIAS_SECRET_KEY = "fc_cfg_secretkey_v1"
    private const val ALIAS_PRIV_PEM = "fc_cfg_privkey_v1"

    /** 最近一次读取失败的诊断（供设置页显示；不含敏感值）。 */
    @Volatile
    var lastLoadDiag: String = ""
        private set

    private fun reasonText(reason: String): String = when (reason) {
        "EMPTY" -> "包裹体为空"
        "BAD_BLOB" -> "包裹体格式损坏"
        "NO_KEY" -> "Keystore 包装密钥不存在（重装/清数据/换机）"
        "DECRYPT_FAIL" -> "Keystore 解密失败（密钥或包裹体不匹配）"
        else -> reason
    }

    data class Settings(
        val secretId: String = "",
        val secretKey: String = "",     // 仅内存持有；落盘为 secretKeyEnc
        val pubKeyPem: String = "",
        val privKeyPem: String = "",    // 仅内存持有；落盘为 privKeyEnc
        val autoAnchor: Boolean = true,
        val encRecord: Boolean = false  // 加密取证（fmt=E1），默认关闭
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "config").apply { mkdirs() }
        .let { File(it, "settings.json") }

    fun load(ctx: Context): Settings = runCatching {
        lastLoadDiag = ""
        val f = file(ctx)
        if (!f.exists()) return Settings()
        val o = JSONObject(f.readText())
        var migrated = false

        val secretKey = readSecret(o, "secretKeyEnc", "secretKey", ALIAS_SECRET_KEY) { migrated = true }
        val privKeyPem = readSecret(o, "privKeyEnc", "privKeyPem", ALIAS_PRIV_PEM) { migrated = true }

        val s = Settings(
            secretId = o.optString("secretId"),
            secretKey = secretKey,
            pubKeyPem = o.optString("pubKeyPem"),
            privKeyPem = privKeyPem,
            autoAnchor = o.optBoolean("autoAnchor", true),
            encRecord = o.optBoolean("encRecord", false)
        )
        // 迁移：把历史明文重写为包裹形式，并从 JSON 中彻底移除明文字段。
        // 包裹失败时保留原文件（含明文），并记录诊断，绝不静默丢配置。
        if (migrated) {
            runCatching { save(ctx, s) }.onFailure {
                lastLoadDiag = "旧明文配置迁移失败：${it.message ?: "Keystore 异常"}"
            }
        }
        s
    }.getOrDefault(Settings())

    private fun readSecret(
        o: JSONObject, encField: String, plainField: String, alias: String, onMigrate: () -> Unit
    ): String {
        val enc = o.optString(encField)
        if (enc.isNotEmpty()) {
            val (bytes, reason) = EncKeyVault.unwrapBlobDetailed(alias, enc)
            if (bytes != null) return bytes.toString(Charsets.UTF_8)
            if (lastLoadDiag.isEmpty()) {
                lastLoadDiag = "$encField 解包失败：${reasonText(reason)}"
            }
            return ""
        }
        val plain = o.optString(plainField)
        if (plain.isNotEmpty()) onMigrate()
        return plain
    }

    /**
     * 写入配置。敏感字段包裹失败时抛异常并保留旧文件（不覆盖旧配置）；
     * 写入采用临时文件 + fsync + rename，避免进程被杀留下半个 settings.json。
     */
    fun save(ctx: Context, s: Settings) {
        val f = file(ctx)
        val prev = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        val o = JSONObject()
        o.put("secretId", s.secretId)
        o.put("pubKeyPem", s.pubKeyPem)
        o.put("autoAnchor", s.autoAnchor)
        o.put("encRecord", s.encRecord)

        putWrapped(o, prev, "secretKeyEnc", "secretKey", s.secretKey, ALIAS_SECRET_KEY)
        putWrapped(o, prev, "privKeyEnc", "privKeyPem", s.privKeyPem, ALIAS_PRIV_PEM)

        val tmp = File(f.parentFile, "settings.json.tmp")
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                throw IllegalStateException("settings.json 写入失败")
            }
        }
    }

    /**
     * 写入包裹后的敏感字段。包裹失败时抛异常，由调用方保留旧文件并明确报错；
     * 内存值为空但旧文件有包裹体时保留旧值，避免"读取失败后点保存"把配置清空。
     */
    private fun putWrapped(
        o: JSONObject, prev: JSONObject, encField: String, plainField: String,
        value: String, alias: String
    ) {
        if (value.isEmpty()) {
            o.put(encField, prev.optString(encField))
            o.remove(plainField)
            return
        }
        val wrapped = EncKeyVault.wrapBlob(alias, value.toByteArray(Charsets.UTF_8))
            ?: throw IllegalStateException("敏感配置包裹失败（$encField）")
        o.put(encField, wrapped)
        o.remove(plainField)
    }

    fun isConfigured(ctx: Context): Boolean {
        val s = load(ctx)
        return s.secretId.isNotEmpty() && s.secretKey.isNotEmpty() &&
               s.pubKeyPem.isNotEmpty() && s.privKeyPem.isNotEmpty()
    }
}
