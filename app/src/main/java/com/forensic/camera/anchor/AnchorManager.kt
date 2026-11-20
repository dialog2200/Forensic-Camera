package com.forensic.camera.anchor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.Evidence
import com.forensic.camera.data.EvidenceStore
import com.forensic.camera.data.SettingsStore
import com.forensic.camera.zxchain.ZxChainClient
import java.util.concurrent.Executors

/**
 * 离线安全的上链队列：拍摄完成即入队，后台线程逐条调用至信链 ev/save。
 * 失败保留 FAILED 状态，回到前台/手动可重试；应用重启后自动续跑未上链记录。
 */
object AnchorManager {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    interface Listener { fun onAnchorChanged(evidenceId: String) }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** 新证据入队（记录已持久化为 PENDING），唤醒后台处理。 */
    fun enqueue(ctx: Context, evidenceId: String) = ensureRunning(ctx)

    /** 处理所有未上链记录；若处理期间有新记录产生，最多再扫两轮。 */
    fun ensureRunning(ctx: Context) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                var pass = 0
                while (pass < 3) {
                    pass++
                    val pending = EvidenceStore.list(appCtx).filter { it.status != AnchorStatus.ANCHORED }
                    if (pending.isEmpty()) break
                    val s = SettingsStore.load(appCtx)
                    if (s.secretId.isEmpty() || s.secretKey.isEmpty() ||
                        s.pubKeyPem.isEmpty() || s.privKeyPem.isEmpty()) {
                        break // 未配置凭据：保留 PENDING
                    }
                    val client = ZxChainClient(s.secretId, s.secretKey)
                    val fatalCodes = setOf(140003, 140004, 140005, 16004, 16016)
                    var fatal = false
                    for (ev in pending) {
                        val file = EvidenceStore.fileFor(appCtx, ev)
                        if (!file.exists()) {
                            update(appCtx, ev.id) {
                                it.status = AnchorStatus.FAILED
                                it.anchorError = "证据文件丢失"
                            }
                            continue
                        }
                        try {
                            val r = client.evSave(ev.anchorHash, s.pubKeyPem, s.privKeyPem, ev.buildExtendInfo())
                            if (r.retCode == 0) {
                                update(appCtx, ev.id) {
                                    it.status = AnchorStatus.ANCHORED
                                    it.evId = r.evId; it.txId = r.txId
                                    it.blockHeight = r.blockHeight; it.txTime = r.txTime
                                    it.anchorError = ""
                                }
                            } else {
                                update(appCtx, ev.id) {
                                    it.status = AnchorStatus.FAILED
                                    it.anchorError = "retCode=${r.retCode} ${r.retMsg}"
                                }
                                // 鉴权失败/余额不足：重试无意义，结束本轮
                                if (r.retCode in fatalCodes) fatal = true
                            }
                        } catch (e: Exception) {
                            update(appCtx, ev.id) {
                                it.status = AnchorStatus.FAILED
                                it.anchorError = (e.message ?: "网络错误").take(200)
                            }
                        }
                        if (fatal) break
                    }
                }
            } finally {
                running = false
            }
        }
    }

    private fun update(ctx: Context, id: String, mutate: (Evidence) -> Unit) {
        EvidenceStore.update(ctx, id, mutate)
        main.post { listeners.forEach { it.onAnchorChanged(id) } }
    }
}
