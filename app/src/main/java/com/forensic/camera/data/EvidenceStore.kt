package com.forensic.camera.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 本地证据索引：filesDir/evidence/index.json（追加式台账，随 APK 公证链一同交付）。 */
object EvidenceStore {

    private val lock = Any()

    /** 证据体积缓存：避免 Compose 每次重组都在主线程逐段列目录。 */
    private val sizeCache = ConcurrentHashMap<String, Long>()

    fun mediaDir(ctx: Context): File = File(ctx.filesDir, "media").apply { mkdirs() }
    private fun indexFile(ctx: Context): File =
        File(ctx.filesDir, "evidence").apply { mkdirs() }.let { File(it, "index.json") }

    fun list(ctx: Context): MutableList<Evidence> = synchronized(lock) {
        val f = indexFile(ctx)
        if (!f.exists()) return mutableListOf()
        runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Evidence.fromJson(arr.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun add(ctx: Context, ev: Evidence) = synchronized(lock) {
        val items = list(ctx)
        items.add(0, ev)
        persist(ctx, items)
        sizeCache.remove(ev.id)
    }

    fun update(ctx: Context, id: String, mutate: (Evidence) -> Unit): Evidence? =
        synchronized(lock) {
            val items = list(ctx)
            val item = items.firstOrNull { it.id == id } ?: return null
            mutate(item)
            persist(ctx, items)
            item
        }

    fun find(ctx: Context, id: String): Evidence? = list(ctx).firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    fun fileFor(ctx: Context, ev: Evidence): File = File(mediaDir(ctx), ev.fileName)

    /** 体积：F0 取文件长度，E1 取密文目录总字节数。结果缓存，避免主线程重复列目录。 */
    fun bytesOf(ctx: Context, ev: Evidence): Long =
        sizeCache[ev.id] ?: computeBytesOf(ctx, ev).also { sizeCache[ev.id] = it }

    private fun computeBytesOf(ctx: Context, ev: Evidence): Long {
        val f = fileFor(ctx, ev)
        return if (f.isDirectory) f.listFiles()?.sumOf { it.length() } ?: 0L else f.length()
    }

    /** 在 IO 线程预热体积缓存（reload 时调用），保证 UI 首帧不再做目录扫描。 */
    fun prefetchBytes(ctx: Context, items: List<Evidence>) {
        for (ev in items) {
            if (!sizeCache.containsKey(ev.id)) {
                sizeCache[ev.id] = computeBytesOf(ctx, ev)
            }
        }
    }

    fun invalidateSize(id: String) { sizeCache.remove(id) }

    /** 删除证据本体：E1 是目录，需递归删除。 */
    fun delete(ctx: Context, ev: Evidence): Boolean {
        val f = fileFor(ctx, ev)
        sizeCache.remove(ev.id)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    private fun persist(ctx: Context, items: List<Evidence>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        indexFile(ctx).writeText(arr.toString())
    }

    /** 导出完整台账 JSON（含链上凭证字段），供第三方独立核验。 */
    fun exportJson(ctx: Context): String {
        val o = JSONObject()
        o.put("exportedAt", System.currentTimeMillis())
        o.put("algorithm", "SHA-256 composite: V1|file=<fileSha256>|dev=<device>|ts=<createdAt>|dur=<durationMs>|apk=<apkSha256>")
        o.put("attestation", "v1.3.0: attBlob=[1B cert count][X509 DER chain][ECDSA-P256-SHA256 sig over anchorHash]; on-chain extendInfo prefix att=<sha256(attBlob)>;lvl=<H|S|N>;vc=<versionCode>; verify with zx_anchor.py verify-att")
        o.put("chain", "Tencent ZhixinChain (open.zxchain.qq.com)")
        val arr = JSONArray()
        list(ctx).forEach { arr.put(it.toJson()) }
        o.put("items", arr)
        return o.toString(2)
    }
}
