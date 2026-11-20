package com.forensic.camera.enc

import android.content.Context
import com.forensic.camera.data.EvidenceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 加密证据（fmt=E1）的磁盘布局与元数据。
 *
 *   filesDir/media/<fileName>/          ← fileName 以 "ENC_" 开头，本身是目录
 *       init.mp4                         ftyp+moov（明文，段链 seg0）
 *       seg_00001.enc …                  密文分片
 *       manifest.jsonl                   段链清单（逐行 fsync）
 *       summary.json                     收尾摘要
 *       meta.json                        证据清单：包装 DEK、安全等级、轨参数、SM2 托管
 *       dek.escrow.json                  仅含 SM2 封装的 DEK，便于私钥持有方离线解封
 *       open.lock                        未正常收尾标记（存在=中断）
 *
 * 明文媒体从不落盘；应用内预览产生的临时件写在 cacheDir/preview/，见 [PreviewCache]。
 */
object EncFormat {

    const val F0 = "F0"          // 旧证据：明文 mp4/m4a，原路径不变
    const val E1 = "E1"          // 录制期即加密
    const val PREFIX = "ENC_"

    fun isEncrypted(fmt: String?): Boolean = fmt == E1

    fun dirFor(ctx: Context, fileName: String): File = File(EvidenceStore.mediaDir(ctx), fileName)

    /** 目录内所有密文文件的总字节数（UI 展示体积用）。 */
    fun bytesOf(dir: File): Long =
        dir.listFiles()?.sumOf { it.length() } ?: 0L

    // ---------------- meta.json ----------------

    fun writeMeta(dir: File, meta: JSONObject) {
        runCatching { File(dir, "meta.json").writeText(meta.toString(2)) }
    }

    fun readMeta(dir: File): JSONObject? =
        runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()

    /** 从 meta.json 取 Keystore 包装的 DEK 并解开；换机/卸载后返回 null（密钥不可得）。 */
    fun dekFor(ctx: Context, dir: File): ByteArray? = dekForDetailed(ctx, dir).first

    /**
     * 取 DEK，失败带原因。顺序：Keystore 包装 → SM2 公钥托管（dek.escrow.json + 设置中的私钥）。
     * reason："" 成功 / "ESCROW" 经托管解出 / NO_META / NO_WRAPPED / NO_KEY / DECRYPT_FAIL / BAD_BLOB。
     */
    fun dekForDetailed(ctx: Context, dir: File): Pair<ByteArray?, String> {
        val meta = readMeta(dir)
        if (meta == null) {
            dekFromEscrow(ctx, dir)?.let { return it to "ESCROW" }
            return null to "NO_META"
        }
        val wrapped = meta.optString("dek")
        val (dek, reason) = if (wrapped.isEmpty()) null to "NO_WRAPPED" else EncKeyVault.unwrapDekDetailed(wrapped)
        if (dek != null) return dek to ""
        dekFromEscrow(ctx, dir)?.let { return it to "ESCROW" }
        return null to reason
    }

    /** 第二恢复路径：dek.escrow.json 用设置中用户持有的 SM2 私钥解回。 */
    private fun dekFromEscrow(ctx: Context, dir: File): ByteArray? {
        val f = File(dir, "dek.escrow.json")
        if (!f.exists()) return null
        return runCatching {
            val ct = java.util.Base64.getDecoder().decode(JSONObject(f.readText()).optString("ct"))
            val priv = com.forensic.camera.data.SettingsStore.load(ctx).privKeyPem
            if (priv.isEmpty() || ct.isEmpty()) null
            else com.forensic.camera.crypto.GmCrypto.sm2Decrypt(priv, ct)?.takeIf { it.size == 32 }
        }.getOrNull()
    }

    /** 把 dekForDetailed 的 reason 翻成给用户看的中文说明。 */
    fun reasonText(reason: String): String = when (reason) {
        "" -> "密钥可用"
        "ESCROW" -> "已经 SM2 公钥托管恢复"
        "NO_META" -> "证据清单缺失（meta.json）"
        "NO_WRAPPED" -> "录制时 DEK 包裹失败（清单中无包装体）"
        "NO_KEY" -> "Keystore 包装密钥不存在（应用被清数据/重装/换机）"
        "DECRYPT_FAIL" -> "Keystore 解密失败（包装体损坏或密钥不符）"
        "BAD_BLOB" -> "包装体格式损坏"
        else -> reason
    }

    fun levelFor(dir: File): String = readMeta(dir)?.optString("level").orEmpty().ifEmpty { "N" }

    /** 轨参数（重建 init.mp4 或第三方复算时使用）。 */
    fun tracksFromMeta(meta: JSONObject): List<NativeEnc.TrackSpec> {
        val arr = meta.optJSONArray("tracks") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            NativeEnc.TrackSpec(
                id = o.optInt("id"),
                kind = o.optString("kind"),
                width = o.optInt("w"),
                height = o.optInt("h"),
                sampleRate = o.optInt("rate"),
                channels = o.optInt("ch"),
                csdBase64 = o.optString("csd")
            )
        }
    }

    fun tracksToJson(tracks: List<NativeEnc.TrackSpec>): JSONArray {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("kind", t.kind)
                put("w", t.width)
                put("h", t.height)
                put("rate", t.sampleRate)
                put("ch", t.channels)
                put("csd", t.csdBase64)
            })
        }
        return arr
    }

    /**
     * DEK 的 SM2 公钥托管：用设置中用户持有的 SM2 公钥封装原始 DEK，
     * 使私钥持有方在 PC 上可离线解封并还原明文（应用内 Keystore 之外的第二条恢复路径）。
     */
    fun escrow(dir: File, dek: ByteArray, pubKeyPem: String): Boolean {
        if (pubKeyPem.isBlank()) return false
        val ct = runCatching { com.forensic.camera.crypto.GmCrypto.sm2Encrypt(pubKeyPem, dek) }
            .getOrNull() ?: return false
        val o = JSONObject().apply {
            put("alg", "SM2-C1C3C2")
            put("ct", java.util.Base64.getEncoder().encodeToString(ct))
            put("note", "ct 为 04||C1(x,y)||C3(SM3)||C2 原始编码；解开后即 32 字节 AES-256 DEK")
        }
        runCatching { File(dir, "dek.escrow.json").writeText(o.toString(2)) }
        return true
    }

    fun escrowJson(dir: File): String? =
        runCatching { File(dir, "dek.escrow.json").readText() }.getOrNull()

    // ---------------- 预览临时件 ----------------

    /**
     * 应用内预览用的临时解密缓存。**不计入沙盒核验**：它不是证据本体，
     * 仅为便于在播放器中查看，随时可删，且文件名带"播放临时件"标记。
     */
    object PreviewCache {
        fun dir(ctx: Context): File =
            File(ctx.cacheDir, "preview_plain").apply { mkdirs() }

        fun fileFor(ctx: Context, id: String, audio: Boolean): File =
            File(dir(ctx), "播放临时件_$id${if (audio) ".m4a" else ".mp4"}")

        fun clear(ctx: Context) {
            dir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}
