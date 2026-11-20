package com.forensic.capture.state

import com.forensic.camera.data.Evidence
import com.forensic.camera.data.TreeNode

/** 台账树的一行（移植工程 A EvidenceActivity 的 rebuildRows 纯逻辑）。 */
sealed class TreeRow {
    data class NodeRow(
        val node: TreeNode?,          // null = 「未分类」桶
        val label: String,
        val depth: Int,
        val expanded: Boolean,
        val childCount: Int,
        val evCount: Int,
        val key: String
    ) : TreeRow()

    data class EvRow(val ev: Evidence, val depth: Int) : TreeRow()
}

object TreeBuilder {

    fun build(
        nodes: List<TreeNode>,
        items: List<Evidence>,
        collapsed: Set<String>,
        uncategorizedCollapsed: Boolean
    ): List<TreeRow> {
        val rows = mutableListOf<TreeRow>()

        fun evUnder(nodeId: String): List<Evidence> =
            items.filter { it.nodeId == nodeId }.sortedByDescending { it.createdAt }

        fun appendLevel(parentId: String, depth: Int) {
            for (n in nodes.filter { it.parentId == parentId }) {
                val kids = nodes.count { it.parentId == n.id }
                val evs = evUnder(n.id)
                val exp = !collapsed.contains(n.id)
                rows.add(TreeRow.NodeRow(n, n.name, depth, exp, kids, evs.size, n.id))
                if (exp) {
                    appendLevel(n.id, depth + 1)
                    evs.forEach { rows.add(TreeRow.EvRow(it, depth + 1)) }
                }
            }
        }

        appendLevel("", 0)
        // 未分类桶固定在根层末尾
        val orphans = evUnder("")
        rows.add(TreeRow.NodeRow(null, "未分类", 0, !uncategorizedCollapsed, 0, orphans.size, ""))
        if (!uncategorizedCollapsed) orphans.forEach { rows.add(TreeRow.EvRow(it, 1)) }
        return rows
    }
}
