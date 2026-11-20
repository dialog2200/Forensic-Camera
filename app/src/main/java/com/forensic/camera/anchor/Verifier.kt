package com.forensic.camera.anchor

import android.content.Context
import android.util.Base64
import com.forensic.camera.crypto.GmCrypto
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.AppIntegrity
import com.forensic.camera.data.Evidence
import com.forensic.camera.data.EvidenceStore
import com.forensic.camera.data.GalleryExporter
import com.forensic.camera.data.SettingsStore
import com.forensic.camera.enc.EncFormat
import com.forensic.camera.enc.EncKeyVault
import com.forensic.camera.enc.NativeEnc
import com.forensic.camera.zxchain.ZxChainClient

/**
 * v1.3.5 三方核验：对同一条证据同时核验三个来源，结论分别呈现。
 *
 *  1) 沙盒原始文件：filesDir/media/<fileName> 重算 SHA-256 ↔ 台账 fileSha256
 *  2) 相册导出副本：按台账 galleryName/galleryUri 定位 DCIM/ForensicCamera 副本，重算 SHA-256 ↔ 台账 fileSha256
 *  3) 至信链存证：用台账元数据重建复合串
 *       V1|file=<fileSha>|dev=<device>|ts=<createdAt>|dur=<durationMs>|apk=<apkSha256>
 *     取 SHA-256 后按锚定值 evQuery 查链，确认已存证且 evId 一致
 *
 * 整体结论：任一项不一致 → TAMPERED；沙盒文件缺失 → MISSING；
 * 链未配置/查询异常/未存证 → 对应状态；链与沙盒一致但相册未导出或缺失 → INCOMPLETE（不给出笼统"通过"）。
 */
object Verifier {

    enum class Verdict { INTACT, TAMPERED, INCOMPLETE, MISSING, NOT_ANCHORED, NO_CONFIG, UNKNOWN }

    /** 文件侧核验状态（沙盒 / 相册副本共用）。 */
    enum class FileState(val label: String) {
        MATCH("一致"),
        MISMATCH("不一致"),
        MISSING("缺失"),
        NOT_EXPORTED("未导出"),
        KEY_UNAVAILABLE("密钥不可得")   // 加密证据：密文结构完好，但本机 Keystore 密钥已丢失
    }

    /** 链上侧核验状态。 */
    enum class ChainState(val label: String) {
        MATCH("已存证一致"),
        MISMATCH("与链上不一致"),
        NOT_FOUND("链上未存证"),
        NO_CONFIG("未配置凭据"),
        ERROR("查询失败")
    }

    data class Result(
        val verdict: Verdict,
        val sandbox: FileState,
        val gallery: FileState,
        val chain: ChainState,
        val detail: String,
        val proof: String = ""     // evId / txId / 区块 摘要，便于台账与外链核对
    )

    fun verify(ctx: Context, ev: Evidence): Result {
        // ---------- 1) 沙盒原始文件 ----------
        val file = EvidenceStore.fileFor(ctx, ev)
        val recomputedFileSha: String?
        val sandbox: FileState
        var encNote = ""
        if (EncFormat.isEncrypted(ev.fmt)) {
            // fmt=E1：沙盒里只有密文。原路径核验 = 段链自洽 + 密文摘要命中 + 还原后明文摘要比对。
            // 段链与密文摘要不依赖密钥，密钥丢失时仍可判"结构完好但不可还原"，不误报篡改。
            val (dek, reason) = EncFormat.dekForDetailed(ctx, file)
            val ins = NativeEnc.inspect(file.absolutePath, dek, ev.fileSha256)
            recomputedFileSha = ins.plainSha.ifEmpty { null }
            val structBad = ins.badSeqs.isNotEmpty() || ins.missingSeqs.isNotEmpty()
            sandbox = when {
                !file.exists() -> FileState.MISSING
                structBad -> FileState.MISMATCH
                !ins.chainOk -> FileState.MISMATCH
                dek == null -> FileState.KEY_UNAVAILABLE
                !ins.plainSha.equals(ev.fileSha256, ignoreCase = true) -> FileState.MISMATCH
                else -> FileState.MATCH
            }
            encNote = buildString {
                append(" ｜ 密文:").append(ins.segCount).append("段、段链")
                append(if (ins.chainOk) "自洽" else "断链")
                if (ins.badSeqs.isNotEmpty()) append("、坏段").append(ins.badSeqs.take(5))
                if (ins.missingSeqs.isNotEmpty()) append("、缺段").append(ins.missingSeqs.take(5))
                if (ins.interrupted) append("、⚠上次录制未正常收尾")
                if (dek == null) {
                    append("、").append(EncFormat.reasonText(reason))
                    // #region debug-point keystore-key-lost：把 Keystore 具体异常/类型/别名状态直接显示，截图即证据
                    val d = EncKeyVault.lastDiag
                    if (d.isNotEmpty()) append("〔").append(d).append("〕")
                    // #endregion
                } else if (reason == "ESCROW") append("、经 SM2 公钥托管恢复")
            }
        } else if (!file.exists()) {
            recomputedFileSha = null
            sandbox = FileState.MISSING
        } else {
            recomputedFileSha = runCatching { GmCrypto.sha256Hex(file) }.getOrNull()
            sandbox = when {
                recomputedFileSha == null -> FileState.MISSING
                recomputedFileSha.equals(ev.fileSha256, ignoreCase = true) -> FileState.MATCH
                else -> FileState.MISMATCH
            }
        }

        // ---------- 2) 相册导出副本 ----------
        val gallery = checkGallery(ctx, ev)

        // ---------- 3) 至信链存证 ----------
        // 复算用沙盒实测哈希；沙盒文件缺失时退化为台账 fileSha256（仍可校验链上原始锚定是否被改动）
        val shaForCanonical = recomputedFileSha ?: ev.fileSha256
        val canonical = Evidence.canonical(
            shaForCanonical, ev.device, ev.createdAt, ev.durationMs, ev.apkSha256)
        val recomputedAnchor = GmCrypto.sha256Hex(canonical.toByteArray(Charsets.UTF_8))

        val s = SettingsStore.load(ctx)
        var chain: ChainState
        var proof = ""
        var chainNote = ""

        if (s.secretId.isEmpty() || s.secretKey.isEmpty()) {
            chain = ChainState.NO_CONFIG
        } else {
            val client = ZxChainClient(s.secretId, s.secretKey)
            val byRecomputed = runCatching { client.evQuery(hash = recomputedAnchor) }
            if (byRecomputed.isFailure) {
                chain = ChainState.ERROR
                chainNote = " ${byRecomputed.exceptionOrNull()?.message ?: ""}"
            } else {
                val r = byRecomputed.getOrThrow()
                if (r.retCode == 0 && r.evId.isNotEmpty()) {
                    val evIdOk = ev.evId.isEmpty() || r.evId == ev.evId
                    if (evIdOk) {
                        chain = ChainState.MATCH
                        proof = "evId=${r.evId} txId=${r.txId} 区块=${r.blockHeight}"
                        chainNote = currentApkNote(ctx, ev) + attBindNote(ev, r.extendInfo)
                    } else {
                        chain = ChainState.MISMATCH
                        proof = "命中链上其他存证 evId=${r.evId}（台账 ${ev.evId}）"
                    }
                } else {
                    // 重建 hash 未命中 → 查台账原始 anchorHash 是否仍在链上
                    val byLedger = runCatching { client.evQuery(hash = ev.anchorHash) }.getOrNull()
                    val ledgerOnChain = byLedger != null && byLedger.retCode == 0 && byLedger.evId.isNotEmpty()
                    if (ledgerOnChain) {
                        chain = ChainState.MISMATCH
                        proof = "链上原始存证 evId=${byLedger!!.evId} txId=${byLedger.txId} 区块=${byLedger.blockHeight}"
                        chainNote = "；重建复合 hash=${recomputedAnchor.take(16)}… ≠ 链上锚定 ${ev.anchorHash.take(16)}…" +
                            attBindNote(ev, byLedger.extendInfo)
                    } else if (ev.status == AnchorStatus.ANCHORED) {
                        chain = ChainState.ERROR
                        chainNote = " 台账已上链但查询未命中，稍后重试（${byLedger?.retMsg ?: "网络"}）"
                    } else {
                        chain = ChainState.NOT_FOUND
                    }
                }
            }
        }

        val verdict = when {
            sandbox == FileState.MISMATCH || gallery == FileState.MISMATCH ||
                chain == ChainState.MISMATCH -> Verdict.TAMPERED
            sandbox == FileState.MISSING -> Verdict.MISSING
            sandbox == FileState.KEY_UNAVAILABLE -> Verdict.INCOMPLETE
            chain == ChainState.NO_CONFIG -> Verdict.NO_CONFIG
            chain == ChainState.ERROR -> Verdict.UNKNOWN
            chain == ChainState.NOT_FOUND ->
                if (ev.status == AnchorStatus.ANCHORED) Verdict.UNKNOWN else Verdict.NOT_ANCHORED
            gallery != FileState.MATCH -> Verdict.INCOMPLETE
            else -> Verdict.INTACT
        }

        val detail = buildString {
            append("沙盒:").append(sandbox.label)
            append(" ｜ 相册:").append(gallery.label)
            append(" ｜ 链上:").append(chain.label)
            if (proof.isNotEmpty()) append(" (").append(proof).append(')')
            if (chainNote.isNotEmpty()) append(" ").append(chainNote)
            if (encNote.isNotEmpty()) append(encNote)
        }
        return Result(verdict, sandbox, gallery, chain, detail, proof)
    }

    /** 按台账记录的 galleryUri / galleryName 定位相册副本并重算 SHA-256。 */
    private fun checkGallery(ctx: Context, ev: Evidence): FileState {
        val hasRecord = ev.galleryUri.isNotEmpty() || ev.galleryName.isNotEmpty()
        if (!hasRecord) return FileState.NOT_EXPORTED

        val candidates = ArrayList<String>(2)
        if (ev.galleryUri.isNotEmpty()) candidates.add(ev.galleryUri)
        GalleryExporter.uriForEvidence(ctx, ev)?.let { if (it !in candidates) candidates.add(it) }

        for (uri in candidates) {
            val sha = GalleryExporter.sha256OfUri(ctx, uri) ?: continue
            return if (sha.equals(ev.fileSha256, ignoreCase = true))
                FileState.MATCH else FileState.MISMATCH
        }
        return FileState.MISSING
    }

    private fun currentApkNote(ctx: Context, ev: Evidence): String {
        val cur = AppIntegrity.apkSha256(ctx)
        return if (cur.isNotEmpty() && ev.apkSha256.isNotEmpty() && cur != ev.apkSha256)
            "|注意:当前APK(${cur.take(12)}…)≠拍摄时APK(${ev.apkSha256.take(12)}…)，App 已更新"
        else ""
    }

    /**
     * v1.3.0 出处证明绑定核验：链上 extendInfo 前缀 att=<sha256hex> 必须与
     * 本地 attBlob 重算摘要一致，证明台账里的证明链在锚定时刻就固定在了链上、未被替换。
     */
    private fun attBindNote(ev: Evidence, chainExtendInfo: String): String {
        val chainAtt = Regex("att=([0-9a-fA-F]{64})").find(chainExtendInfo)?.groupValues?.get(1)?.lowercase()
        if (chainAtt.isNullOrEmpty()) return ""   // v1.2.0 记录或无证明
        if (ev.attSha256.isEmpty()) {
            return "|⚠链上有出处证明绑定但台账证明数据缺失(attBlob 可能被删除)"
        }
        val localRecomputed = runCatching {
            GmCrypto.sha256Hex(Base64.decode(ev.attBlob, Base64.NO_WRAP)).lowercase()
        }.getOrDefault("")
        return when {
            localRecomputed != ev.attSha256.lowercase() ->
                "|🚨本地 attBlob 摘要与台账 attSha256 不一致(证明链文件被改动)"
            chainAtt != ev.attSha256.lowercase() ->
                "|🚨出处证明被替换：链上 att=${chainAtt.take(12)}… ≠ 台账 att=${ev.attSha256.take(12)}…"
            else -> "|出处证明绑定一致 lvl=${ev.attLevel}"
        }
    }
}
