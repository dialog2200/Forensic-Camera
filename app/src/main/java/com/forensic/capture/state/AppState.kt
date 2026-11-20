package com.forensic.capture.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.forensic.camera.anchor.AnchorManager
import com.forensic.camera.anchor.AttestKeyStore
import com.forensic.camera.anchor.Verifier
import com.forensic.camera.crypto.GmCrypto
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.AppIntegrity
import com.forensic.camera.data.DeviceMeta
import com.forensic.camera.data.Evidence
import com.forensic.camera.data.EvidenceStore
import com.forensic.camera.data.GalleryExporter
import com.forensic.camera.data.SettingsStore
import com.forensic.camera.data.TreeStore
import com.forensic.camera.zxchain.ZxChainClient
import com.forensic.capture.engine.CaptureController
import com.forensic.capture.engine.EncRecorder
import com.forensic.capture.engine.PendingCapture
import com.forensic.camera.enc.EncFormat
import com.forensic.camera.enc.EncKeyVault
import com.forensic.camera.enc.NativeEnc
import com.forensic.capture.ui.components.ToastData
import com.forensic.capture.ui.components.ToastKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.view.Surface

enum class Screen { Home, Record, Confirm, Ledger, Detail, Verify, Export, Settings }

enum class RecState { Idle, Recording, Sealing }

/** 录制模式。Video = 视频取证；Audio = 纯音频录音。 */
enum class CaptureMode { Video, Audio }

/** 导出结果（真实导出 + 导出后复核）。 */
data class ExportOutcome(
    val selected: Int,
    val exported: Int,
    val consistent: Int,
    val totalMb: Double,
    val notes: List<String>
)

/**
 * 界面状态中枢：持有真实台账、锚定队列、配置与录制引擎。
 * 所有业务直接调用 com.forensic.camera 引擎（无后端，直连至信链）。
 */
class AppState(val ctx: Context) : AnchorManager.Listener {

    private val app = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val capture = CaptureController(app)

    /** 加密录制器（fmt=E1）；为空表示当前走 CameraX/MediaRecorder 明文路径。 */
    private var encRecorder: EncRecorder? = null

    /** 加密录制时的预览 Surface，由 RecordScreen 的 SurfaceView 注入。 */
    var encPreview: Surface? = null

    var screen by mutableStateOf(Screen.Home)
        private set
    var toast by mutableStateOf<ToastData?>(null)

    /* —— 真实台账 / 目录 / 配置 —— */
    val evidences = mutableStateListOf<Evidence>()
    val nodes = mutableStateListOf<com.forensic.camera.data.TreeNode>()
    var settings by mutableStateOf(SettingsStore.load(app))
        private set

    /** 当前安装包版本（versionName(versionCode)），UI 页脚显示用。 */
    val appVersion: String = DeviceMeta.appVersion(app)

    /* —— 录制 —— */
    var mode by mutableStateOf(CaptureMode.Video)
    var recState by mutableStateOf(RecState.Idle)
    var elapsedSec by mutableIntStateOf(0)

    /** 录制结束已算好真实摘要、但尚未入台账的证据草稿；入队后转 null。 */
    var sealed by mutableStateOf<Evidence?>(null)
    var queued by mutableStateOf(false)

    /* —— 台账（树目录） —— */
    val collapsed = mutableStateListOf<String>()
    var uncategorizedCollapsed by mutableStateOf(false)
    var detailId by mutableStateOf<String?>(null)

    /* —— 校验 —— */
    var pickedId by mutableStateOf<String?>(null)
    var verifying by mutableStateOf(false)
    var verifyResult by mutableStateOf<Verifier.Result?>(null)

    /* —— 导出 —— */
    val selectedIds = mutableStateListOf<String>()
    var exportSheet by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var exportOutcome by mutableStateOf<ExportOutcome?>(null)
    var postCheck by mutableStateOf(true)

    /* —— 设置 —— */
    var keyExpanded by mutableStateOf(false)
    var testingConn by mutableStateOf(false)
    var connResult by mutableStateOf<String?>(null)

    val anchoredCount: Int get() = evidences.count { it.status == AnchorStatus.ANCHORED }
    val pendingCount: Int get() = evidences.count { it.status == AnchorStatus.PENDING }
    val failedCount: Int get() = evidences.count { it.status == AnchorStatus.FAILED }

    /** 用内存中的 settings 判断，禁止在 Compose 读取时重新读盘/做 Keystore 解包。 */
    val configured: Boolean
        get() = settings.secretId.isNotEmpty() && settings.secretKey.isNotEmpty() &&
                settings.pubKeyPem.isNotEmpty() && settings.privKeyPem.isNotEmpty()

    private var reloadJob: Job? = null
    @Volatile private var settingsRev = 0

    init {
        AnchorManager.addListener(this)
        reload()
        AnchorManager.ensureRunning(app)
    }

    override fun onAnchorChanged(evidenceId: String) { reload() }

    /** 台账/目录/设置全部在 IO 读取并预热体积缓存，主线程只接收结果。 */
    fun reload() {
        reloadJob?.cancel()
        val rev = settingsRev
        reloadJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val evs = EvidenceStore.list(app)
                EvidenceStore.prefetchBytes(app, evs)
                Triple(evs, TreeStore.list(app), SettingsStore.load(app))
            }
            evidences.clear()
            evidences.addAll(loaded.first)
            nodes.clear()
            nodes.addAll(loaded.second)
            if (rev == settingsRev) settings = loaded.third
        }
    }

    fun go(target: Screen) {
        screen = target
    }

    fun showToast(message: String, kind: ToastKind = ToastKind.Info) {
        toast = ToastData(message, kind)
    }

    fun toggleMode(next: CaptureMode) {
        if (recState == RecState.Idle) mode = next
    }

    // ---------- 录制起止（真实 CameraX / MediaRecorder） ----------

    fun startRecording(): Boolean {
        if (recState != RecState.Idle) return false
        if (settings.encRecord) return startEncryptedRecording()
        val cbDone: (PendingCapture) -> Unit = ::onCaptureFinished
        val cbFail: (String) -> Unit = { msg ->
            recState = RecState.Idle
            showToast(msg, ToastKind.Bad)
        }
        val err = if (mode == CaptureMode.Video)
            capture.startVideo(cbDone, cbFail)
        else
            capture.startAudio(cbDone, cbFail)
        if (err != null) {
            showToast(err, ToastKind.Bad)
            return false
        }
        recState = RecState.Recording
        elapsedSec = 0
        return true
    }

    /**
     * 加密取证（fmt=E1）：明文不落盘，码流直接进原生分片封装 + AES-256-GCM。
     * DEK 由 Keystore 包装后写入密文目录的 meta.json；配置了 SM2 公钥时额外做公钥托管。
     */
    private fun startEncryptedRecording(): Boolean {
        val dek = EncKeyVault.newDek()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = EncFormat.PREFIX + (if (mode == CaptureMode.Video) "VID_" else "AUD_") +
                stamp + "_" + (1000..9999).random()
        val dir = File(EvidenceStore.mediaDir(app), name)
        val rec = EncRecorder(app)
        val ok = rec.start(
            dir = dir,
            dek = dek,
            sm2PubPem = settings.pubKeyPem,
            withCamera = mode == CaptureMode.Video,
            withAudio = true,
            preview = encPreview
        )
        dek.fill(0)
        if (!ok) {
            showToast(rec.lastError.ifEmpty { "加密录制启动失败" }, ToastKind.Bad)
            dir.deleteRecursively()
            return false
        }
        encRecorder = rec
        recState = RecState.Recording
        elapsedSec = 0
        showToast("加密取证已开启：本机仅写密文", ToastKind.Ok)
        return true
    }

    fun stopRecording() {
        if (recState != RecState.Recording) return
        recState = RecState.Sealing
        val rec = encRecorder
        if (rec == null) {
            capture.stop()
            return
        }
        encRecorder = null
        scope.launch {
            val p = withContext(Dispatchers.IO) { rec.stop() }
            if (p != null) {
                onCaptureFinished(p)
            } else {
                recState = RecState.Idle
                elapsedSec = 0
                showToast(rec.lastError.ifEmpty { "加密录制收尾失败" }, ToastKind.Bad)
            }
        }
    }

    /** 当前已写入字节数：E1 统计密文目录，F0 统计明文文件。调用方应在 IO 线程执行。 */
    fun currentRecordingBytes(): Long {
        encRecorder?.let { return it.bytesWritten() }
        return capture.currentFile?.length() ?: 0L
    }

    /** 录制结束：立即在后台算真实 SHA-256 + 复合 anchorHash + 出处证明，进入确认页。 */
    private fun onCaptureFinished(p: PendingCapture) {
        scope.launch {
            val ev = withContext(Dispatchers.IO) {
                val e = buildEvidence(p)
                EvidenceStore.prefetchBytes(app, listOf(e))
                e
            }
            recState = RecState.Idle
            elapsedSec = 0
            sealed = ev
            queued = false
            screen = Screen.Confirm
            // 自动上链：摘要算完立即入台账并入队锚定，无需手动确认
            if (settings.autoAnchor) enqueueDraft()
        }
    }

    private fun buildEvidence(p: PendingCapture): Evidence {
        // E1：明文从未落盘，摘要取自原生还原后的整文件哈希；F0：原路径直接算文件哈希
        val fileSha = if (p.fmt == EncFormat.E1) p.plainSha256 else GmCrypto.sha256Hex(p.file)
        val apkSha = AppIntegrity.apkSha256(app)
        val device = DeviceMeta.describe()
        val appVer = DeviceMeta.appVersion(app)
        val canonical = Evidence.canonical(fileSha, device, p.startTs, p.durationMs, apkSha)
        val anchor = GmCrypto.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        val att = AttestKeyStore.attest(anchor)
        return Evidence(
            id = EvidenceStore.newId(),
            name = p.name, fileName = p.name, type = p.type,
            fileSha256 = fileSha, anchorHash = anchor,
            createdAt = p.startTs, durationMs = p.durationMs,
            device = device, appVersion = appVer, apkSha256 = apkSha,
            attSha256 = att.attSha256, attLevel = att.level,
            attVc = DeviceMeta.versionCode(app), attBlob = att.attBlobB64,
            status = AnchorStatus.PENDING,
            fmt = p.fmt
        )
    }

    /** 确认页「入队」：写入台账 + AnchorManager 直连上链。 */
    fun enqueueDraft() {
        val ev = sealed ?: return
        EvidenceStore.add(app, ev)
        AnchorManager.enqueue(app, ev.id)
        queued = true
        reload()
        showToast(
            if (configured) "已入台账并入队，直连至信链上链中"
            else "已入台账；未配置至信链凭据，记录将排队等待上链",
            ToastKind.Ok
        )
    }

    /** 放弃草稿：删除未入台账的媒体文件。 */
    fun discardDraft() {
        val ev = sealed ?: return
        if (evidences.none { it.id == ev.id }) {
            runCatching { EvidenceStore.delete(app, ev) }
        }
        sealed = null
        queued = false
    }

    /** 仅清除确认页草稿（已入台账时使用，不删文件）。 */
    fun clearDraft() {
        sealed = null
        queued = false
    }

    /**
     * 应用内预览加密证据：原生解密到 cacheDir/preview_plain（文件名带"播放临时件"标记），
     * 再交给系统播放器。该临时件**不计入沙盒核验**，只是查看用途。
     */
    fun previewEncrypted(ev: Evidence) {
        if (!EncFormat.isEncrypted(ev.fmt)) return
        val dir = EvidenceStore.fileFor(app, ev)
        val audio = ev.type.equals("AUDIO", ignoreCase = true)
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                val (dek, reason) = EncFormat.dekForDetailed(app, dir)
                if (dek == null) {
                    showToast("预览失败：${EncFormat.reasonText(reason)}", ToastKind.Bad)
                    return@withContext null
                }
                val out = EncFormat.PreviewCache.fileFor(app, ev.id, audio)
                val n = NativeEnc.restore(dir.absolutePath, dek, out.absolutePath)
                if (n > 0) out else {
                    showToast("预览失败：密文段解密不通过（段损坏或密钥不符）", ToastKind.Bad)
                    null
                }
            }
            if (f == null) return@launch
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", f)
            }.getOrNull()
            if (uri == null) {
                showToast("预览临时件授权失败", ToastKind.Bad)
                return@launch
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (audio) "audio/mp4" else "video/mp4")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { app.startActivity(intent) }
                .onFailure { showToast("没有可用的播放器", ToastKind.Bad) }
            showToast("已生成播放临时件（不计入核验）", ToastKind.Info)
        }
    }

    fun resetRecording() {
        if (recState == RecState.Recording) {
            val rec = encRecorder
            if (rec != null) {
                encRecorder = null
                scope.launch(Dispatchers.IO) { runCatching { rec.stop() } }
            } else {
                capture.stop()
            }
        }
        recState = RecState.Idle
        elapsedSec = 0
    }

    // ---------- 台账 / 目录 CRUD ----------

    fun toggleNode(key: String) {
        if (key.isEmpty()) {
            uncategorizedCollapsed = !uncategorizedCollapsed
        } else if (!collapsed.add(key)) {
            collapsed.remove(key)
        }
    }

    fun addNode(name: String, parentId: String) {
        if (name.isBlank()) return
        TreeStore.add(app, name.trim(), parentId)
        reload()
    }

    fun renameNode(id: String, name: String) {
        if (name.isBlank()) return
        TreeStore.rename(app, id, name.trim())
        reload()
    }

    fun deleteNode(id: String) {
        TreeStore.delete(app, id)
        reload()
    }

    fun moveEvidence(evidenceId: String, nodeId: String) {
        EvidenceStore.update(app, evidenceId) { it.nodeId = nodeId }
        reload()
    }

    fun setAlias(evidenceId: String, alias: String) {
        EvidenceStore.update(app, evidenceId) { it.alias = alias.trim() }
        reload()
    }

    fun setNotes(evidenceId: String, html: String) {
        EvidenceStore.update(app, evidenceId) { it.notesHtml = html }
        reload()
    }

    fun findEvidence(id: String?): Evidence? =
        evidences.firstOrNull { it.id == id } ?: sealed?.takeIf { it.id == id }

    // ---------- 核验 ----------

    /** 真实三方核验（沙盒 / 相册副本 / 链上），结论写回台账。 */
    fun verifyOne(ev: Evidence, then: ((Verifier.Result) -> Unit)? = null) {
        if (verifying) {
            showToast("核验进行中", ToastKind.Warn)
            return
        }
        verifying = true
        val id = ev.id
        scope.launch {
            val r = withContext(Dispatchers.IO) { Verifier.verify(app, ev) }
            EvidenceStore.update(app, id) {
                it.lastVerify = "${r.verdict} | ${r.detail}"
                it.lastVerifyAt = System.currentTimeMillis()
                it.vSandbox = r.sandbox.label
                it.vGallery = r.gallery.label
                it.vChain = r.chain.label
            }
            verifyResult = r
            verifying = false
            reload()
            then?.invoke(r)
        }
    }

    fun verifyAll() {
        if (verifying) return
        verifying = true
        val list = evidences.toList()
        scope.launch {
            for (ev in list) {
                val r = withContext(Dispatchers.IO) { Verifier.verify(app, ev) }
                EvidenceStore.update(app, ev.id) {
                    it.lastVerify = "${r.verdict} | ${r.detail}"
                    it.lastVerifyAt = System.currentTimeMillis()
                    it.vSandbox = r.sandbox.label
                    it.vGallery = r.gallery.label
                    it.vChain = r.chain.label
                }
            }
            verifying = false
            reload()
            showToast("全部核验完成（${list.size} 条）", ToastKind.Ok)
        }
    }

    // ---------- 导出到相册 ----------

    /** 单条真实导出：GalleryExporter + 记录 galleryUri/galleryName 进台账。 */
    fun exportOneToGallery(ev: Evidence, recheck: Boolean = false) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) { GalleryExporter.export(app, ev) }
            if (uri != null) {
                val albumName = GalleryExporter.displayNameFor(ev)
                EvidenceStore.update(app, ev.id) {
                    it.galleryUri = uri
                    it.galleryName = albumName
                }
                reload()
                showToast("已导出相册 DCIM/${GalleryExporter.ALBUM_DIR}/$albumName", ToastKind.Ok)
                if (recheck) verifyOne(ev)
            } else {
                showToast("导出到相册失败（源文件缺失或存储不可用）", ToastKind.Bad)
            }
        }
    }

    /** 批量导出 + 导出后真实复核。 */
    fun exportSelected() {
        if (exporting) return
        val items = evidences.filter { selectedIds.contains(it.id) }
        if (items.isEmpty()) {
            showToast("请先选择需要导出的证据", ToastKind.Warn)
            return
        }
        exporting = true
        exportOutcome = null
        val check = postCheck
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                var exported = 0
                var consistent = 0
                var totalBytes = 0L
                val notes = mutableListOf<String>()
                for (ev in items) {
                    totalBytes += EvidenceStore.bytesOf(app, ev)
                    val uri = GalleryExporter.export(app, ev)
                    if (uri == null) {
                        notes.add("${ev.alias.ifEmpty { ev.name }}：导出失败（${GalleryExporter.lastError.ifEmpty { "源文件缺失" }}）")
                        continue
                    }
                    exported++
                    EvidenceStore.update(app, ev.id) {
                        it.galleryUri = uri
                        it.galleryName = GalleryExporter.displayNameFor(ev)
                    }
                    if (check) {
                        val fresh = EvidenceStore.find(app, ev.id) ?: ev
                        val r = Verifier.verify(app, fresh)
                        EvidenceStore.update(app, ev.id) {
                            it.lastVerify = "${r.verdict} | ${r.detail}"
                            it.lastVerifyAt = System.currentTimeMillis()
                            it.vSandbox = r.sandbox.label
                            it.vGallery = r.gallery.label
                            it.vChain = r.chain.label
                        }
                        if (r.gallery == Verifier.FileState.MATCH) consistent++
                        else notes.add("${fresh.alias.ifEmpty { fresh.name }}：复核相册副本 ${r.gallery.label}")
                    } else {
                        consistent++
                    }
                }
                ExportOutcome(
                    selected = items.size,
                    exported = exported,
                    consistent = if (check) consistent else exported,
                    totalMb = totalBytes / 1048576.0,
                    notes = notes
                )
            }
            exportOutcome = res
            exporting = false
            reload()
            showToast(
                if (res.exported == res.selected && (res.notes.isEmpty() || !check))
                    "导出完成，全部一致"
                else "导出完成 ${res.exported}/${res.selected}",
                if (res.notes.isNotEmpty() && check) ToastKind.Warn else ToastKind.Ok
            )
        }
    }

    fun openExportSheet() {
        exportSheet = true
        exportOutcome = null
        if (selectedIds.isEmpty()) showToast("请先选择需要导出的证据", ToastKind.Warn)
    }

    fun toggleExportSelection(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        exportOutcome = null
    }

    // ---------- 设置 ----------

    fun saveSettings(s: SettingsStore.Settings) {
        val prev = settings
        settingsRev++
        settings = s
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SettingsStore.save(app, s) }
            }
            if (result.isFailure) {
                settingsRev++
                settings = prev
                showToast(
                    "配置保存失败：${result.exceptionOrNull()?.message ?: "Keystore 异常"}，旧配置已保留",
                    ToastKind.Bad
                )
            } else {
                AnchorManager.ensureRunning(app)
                showToast("配置已保存（仅存本机应用私有目录）", ToastKind.Ok)
            }
        }
    }

    /** 私钥 PEM 导入：成功返回 (公钥PEM, 链账户地址)，失败返回 null。 */
    fun importPrivKeyPem(privPem: String): Pair<String, String>? {
        val pub = runCatching { GmCrypto.pubPemFromPrivPem(privPem.trim()) }.getOrNull() ?: return null
        val addr = runCatching { GmCrypto.addressFromPubPem(pub) }.getOrDefault("?")
        return pub to addr
    }

    fun generateKeyPair(): Pair<String, String> = GmCrypto.generateSm2KeyPairPem()

    /** 真实测试连接：用不存在的 hash 查询，能返回业务 retCode 即鉴权签名被网关接受。 */
    fun testConnection(secretId: String, secretKey: String) {
        if (secretId.isBlank() || secretKey.isBlank()) {
            connResult = "请先填写 SecretId / SecretKey"
            return
        }
        if (testingConn) return
        testingConn = true
        connResult = "连接中…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ZxChainClient(secretId.trim(), secretKey.trim()).evQuery(hash = "0".repeat(64)) }
            }
            connResult = r.fold(
                onSuccess = { "鉴权通过 ✅ retCode=${it.retCode} retMsg=${it.retMsg}\n(retCode=16001/16002/查询为空 均属正常，说明签名校验已通过)" },
                onFailure = { "失败 ❌ ${it.message}" }
            )
            testingConn = false
        }
    }

    // ---------- 台账 JSON 导出（供第三方核验） ----------

    fun exportLedgerJson() {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val json = EvidenceStore.exportJson(app)
                val dir = File(app.filesDir, "export").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val f = File(dir, "ledger_$stamp.json")
                f.writeText(json)
                f.absolutePath
            }
            showToast("台账已导出: $path", ToastKind.Ok)
        }
    }

    /** 系统分享全量台账 JSON（通过 FileProvider，目标应用可发到电脑/网盘/邮件）。 */
    fun shareLedgerJson() {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                writeShareFile("ledger", EvidenceStore.exportJson(app))
            }
            if (f == null) {
                showToast("生成台账 JSON 失败", ToastKind.Bad)
                return@launch
            }
            shareJsonFile(f, "取证台账 JSON")
        }
    }

    /** 系统分享单条证据 JSON（zx_anchor.py verify --evidence 直接可用）。 */
    fun shareEvidenceJson(ev: Evidence) {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                writeShareFile("evidence_${ev.id}", ev.toJson().toString(2))
            }
            if (f == null) {
                showToast("生成证据 JSON 失败", ToastKind.Bad)
                return@launch
            }
            shareJsonFile(f, "证据 JSON · ${ev.alias.ifEmpty { ev.name }}")
        }
    }

    private fun writeShareFile(prefix: String, json: String): File? = runCatching {
        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        // 保留近期文件，避免删除正在被分享目标读取的旧文件
        val cutoff = System.currentTimeMillis() - 3600_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
            .format(java.util.Date())
        val f = File(dir, "${prefix}_$stamp.json")
        f.writeText(json)
        f
    }.getOrNull()

    private fun shareJsonFile(f: File, subject: String) {
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", f
            )
        }.getOrNull()
        if (uri == null) {
            showToast("分享文件授权失败", ToastKind.Bad)
            return
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(
                android.content.Intent.EXTRA_TEXT,
                "取证相机导出的 JSON（含证据元数据与链上凭证），可在电脑上用 zx_anchor.py 核验。"
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, subject).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(chooser) }
            .onFailure { showToast("没有可用的分享应用", ToastKind.Bad) }
    }

    companion object {
        fun formatDuration(sec: Int): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        }

        fun formatTime(ms: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(ms))

        fun formatClock(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(ms))
    }
}
