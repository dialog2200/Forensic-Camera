package com.forensic.camera.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 证据目录节点（纯本地管理元数据，不参与任何哈希/上链）。parentId 空串 = 根层。 */
class TreeNode(var id: String, var name: String, var parentId: String)

object TreeStore {

    private val lock = Any()

    private fun file(ctx: Context): File =
        File(ctx.filesDir, "evidence").apply { mkdirs() }.let { File(it, "tree.json") }

    fun list(ctx: Context): MutableList<TreeNode> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TreeNode(o.getString("id"), o.getString("name"), o.optString("parentId"))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun add(ctx: Context, name: String, parentId: String): TreeNode = synchronized(lock) {
        val nodes = list(ctx)
        val n = TreeNode(UUID.randomUUID().toString(), name, parentId)
        nodes.add(n)
        persist(ctx, nodes)
        n
    }

    fun rename(ctx: Context, id: String, name: String) = synchronized(lock) {
        val nodes = list(ctx)
        nodes.firstOrNull { it.id == id }?.name = name
        persist(ctx, nodes)
    }

    /** 删除节点：子节点上移到其父层，挂载的证据退回未分类。 */
    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val nodes = list(ctx)
        val target = nodes.firstOrNull { it.id == id } ?: return
        nodes.filter { it.parentId == id }.forEach { it.parentId = target.parentId }
        nodes.remove(target)
        persist(ctx, nodes)
        EvidenceStore.list(ctx).filter { it.nodeId == id }.forEach {
            EvidenceStore.update(ctx, it.id) { e -> e.nodeId = "" }
        }
    }

    fun childrenOf(ctx: Context, parentId: String): List<TreeNode> =
        list(ctx).filter { it.parentId == parentId }

    private fun persist(ctx: Context, nodes: List<TreeNode>) {
        val arr = JSONArray()
        nodes.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId))
        }
        file(ctx).writeText(arr.toString())
    }
}
