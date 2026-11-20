package com.forensic.camera.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.forensic.camera.enc.EncFormat
import com.forensic.camera.enc.NativeEnc
import java.io.File
import java.security.MessageDigest

/**
 * v1.3.1 导出到本地相册：把私有目录的媒体文件副本写入 DCIM/ForensicCamera。
 * v1.3.5 命名规则：以 UUID 命名——首次导出优先取该条证据的链上 evId，尚无 evId 时取其 UUID(id)；
 * 导出成功后文件名冻结进台账 galleryName，此后重复导出恒定同名，便于追踪与第三方定位。
 * 台账同时记录 galleryUri 与 galleryName，核验时按二者定位相册副本并重算 SHA-256。
 */
object GalleryExporter {

    const val ALBUM_DIR = "ForensicCamera"   // DCIM/ForensicCamera

    fun extFor(type: String): String = if (type.equals("AUDIO", ignoreCase = true)) ".m4a" else ".mp4"

    /** 稳定基名：已冻结的 galleryName > evId > UUID(id)；只保留安全字符。 */
    private fun baseNameFor(ev: Evidence): String {
        val raw = when {
            ev.galleryName.isNotEmpty() -> ev.galleryName.substringBeforeLast('.')
            ev.evId.isNotEmpty() -> ev.evId
            else -> ev.id
        }
        val safe = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return safe.ifEmpty { ev.id }
    }

    fun displayNameFor(ev: Evidence): String = baseNameFor(ev) + extFor(ev.type)

    private fun mimeFor(type: String): String =
        if (type.equals("AUDIO", ignoreCase = true)) "audio/mp4" else "video/mp4"

    /** 最近一次 export 失败的具体原因（供 UI 提示，避免笼统的"源文件缺失"）。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 导出（同名旧条目先删除，覆盖被篡改副本），返回相册条目 uri 字符串；失败返回 null。 */
    fun export(ctx: Context, ev: Evidence): String? {
        lastError = ""
        if (EncFormat.isEncrypted(ev.fmt)) return exportEncrypted(ctx, ev)
        val src = EvidenceStore.fileFor(ctx, ev)
        if (!src.exists()) {
            lastError = "源文件缺失"
            return null
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            exportScoped(ctx, ev, src) else exportLegacy(ctx, ev, src)
        if (uri == null) lastError = "相册写入失败（存储权限或 MediaStore 异常）"
        return uri
    }

    /**
     * E1：先由原生把密文还原成明文临时件（cacheDir，带"导出临时件"标记），
     * 再写入相册；临时件随后立即删除。相册副本是明文，便于移交与播放。
     */
    private fun exportEncrypted(ctx: Context, ev: Evidence): String? {
        val dir = EvidenceStore.fileFor(ctx, ev)
        if (!dir.exists()) {
            lastError = "密文目录缺失"
            return null
        }
        val (dek, reason) = EncFormat.dekForDetailed(ctx, dir)
        if (dek == null) {
            lastError = EncFormat.reasonText(reason)
            return null
        }
        val tmp = File(EncFormat.PreviewCache.dir(ctx), "导出临时件_${ev.id}${extFor(ev.type)}")
        val n = NativeEnc.restore(dir.absolutePath, dek, tmp.absolutePath)
        if (n <= 0) {
            tmp.delete()
            lastError = "密文段解密不通过（密钥不符或段损坏）"
            return null
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            exportScoped(ctx, ev, tmp) else exportLegacy(ctx, ev, tmp)
        tmp.delete()
        if (uri == null) lastError = "相册写入失败（存储权限或 MediaStore 异常）"
        return uri
    }

    /** API 29+：MediaStore 分区存储写入，无需存储权限。 */
    private fun exportScoped(ctx: Context, ev: Evidence, src: File): String? {
        val resolver = ctx.contentResolver
        // 覆盖导出：先删除同名旧条目 + 清掉台账里记录的旧 uri 指向的条目
        val name = displayNameFor(ev)
        val existUri = uriForName(ctx, name, ev.type)
        existUri?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        if (ev.galleryUri.isNotEmpty() && ev.galleryUri != existUri) {
            runCatching { resolver.delete(Uri.parse(ev.galleryUri), null, null) }
        }
        val collection = if (ev.type.equals("AUDIO", ignoreCase = true))
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(ev.type))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$ALBUM_DIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }
                true
            } == true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        return uri.toString()
    }

    /** API 26-28：直接写公共 DCIM 目录（需 WRITE_EXTERNAL_STORAGE 运行时权限）+ 媒体库扫描。 */
    private fun exportLegacy(ctx: Context, ev: Evidence, src: File): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val dst = File(dir, displayNameFor(ev))
        runCatching { src.copyTo(dst, overwrite = true) }.onFailure { return null }
        runCatching {
            MediaScannerConnection.scanFile(
                ctx, arrayOf(dst.absolutePath), arrayOf(mimeFor(ev.type)), null)
        }
        return uriForName(ctx, displayNameFor(ev), ev.type) ?: Uri.fromFile(dst).toString()
    }

    /** 按相册文件名查条目 uri（DISPLAY_NAME=?），无则 null。 */
    fun uriForName(ctx: Context, displayName: String, type: String): String? {
        val collection = if (type.equals("AUDIO", ignoreCase = true))
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(displayName), null
        )?.use { c ->
            val idx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            if (idx >= 0 && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(idx)).toString()
            }
        }
        return null
    }

    /** 按证据记录（galleryName 优先）查相册条目 uri。 */
    fun uriForEvidence(ctx: Context, ev: Evidence): String? =
        uriForName(ctx, displayNameFor(ev), ev.type)

    data class AlbumEntry(val displayName: String, val uri: String, val size: Long)

    /** 列出相册目录 DCIM/ForensicCamera 的全部条目（含台账外文件，供孤儿扫描与核验纳入）。 */
    fun listAlbum(ctx: Context): List<AlbumEntry> {
        val out = ArrayList<AlbumEntry>()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH)
        for (collection in arrayOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)) {
            ctx.contentResolver.query(collection, cols, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (iId < 0 || iName < 0) return@use
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val rel = if (iRel >= 0) c.getString(iRel) ?: "" else ""
                    val data = if (iData >= 0) c.getString(iData) ?: "" else ""
                    if (!rel.contains(ALBUM_DIR) && !data.contains("/$ALBUM_DIR/")) continue
                    out.add(AlbumEntry(
                        name,
                        ContentUris.withAppendedId(collection, c.getLong(iId)).toString(),
                        if (iSize >= 0) c.getLong(iSize) else 0L))
                }
            }
        }
        return out
    }

    /** 流式计算相册副本 SHA-256（hex）；uri 打不开返回 null。 */
    fun sha256OfUri(ctx: Context, uriStr: String): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val ins = ctx.contentResolver.openInputStream(Uri.parse(uriStr)) ?: return@runCatching null
        ins.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { b -> "%02x".format(b) }
    }.getOrNull()
}
