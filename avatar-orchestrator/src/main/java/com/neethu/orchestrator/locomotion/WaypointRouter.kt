package com.neethu.orchestrator.locomotion

/**
 * waypoint 图路由（自由走动 P2，docs/locomotion-feasibility.md §7.2 V2）：
 * 锚点表带 links 时按**无向图** BFS 求两锚点间的最短锚点序列——多段腿绕开
 * 「直线会穿墙/穿家具」的锚点对。几十节点规模 BFS 足够（文档结论），无
 * navmesh 烘焙。
 *
 * 语义约定：
 * - links 配在任一端都连通（无向边）——手写锚点表只写一边即可；
 * - 图里不可达 / 全表无 links → 回退 `[目标]` 两点直线（V1 行为，bounds
 *   clamp 兜底）；
 * - 起点锚点解析不到（角色不在任何锚点附近）→ 路径照常从目标侧 BFS 的
 *   最短树取（调用方决定第一段怎么接）。
 *
 * 纯函数零依赖（JVM 单测直测，与 [parseMoveAnchors] 同惯例）。
 */
object WaypointRouter {

    /**
     * 求从 [from]（可为 null = 不在任何锚点上）到 [to] 的途经锚点序列
     * （**不含** from，**含** to 作为最后一项）。图退化为空时返回 `[to]`。
     */
    fun route(anchors: List<MoveAnchor>, from: MoveAnchor?, to: MoveAnchor): List<MoveAnchor> {
        val byName = anchors.associateBy { it.name }
        // 全表无 links = 无图拓扑，两点直线
        if (anchors.none { it.links.isNotEmpty() }) return listOf(to)

        // 无向邻接表（只收解析得到的名字，脏 links 静默丢弃）
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        for (a in anchors) {
            adjacency.getOrPut(a.name) { mutableSetOf() }
            for (link in a.links) {
                if (byName.containsKey(link)) {
                    adjacency.getOrPut(a.name) { mutableSetOf() }.add(link)
                    adjacency.getOrPut(link) { mutableSetOf() }.add(a.name)
                }
            }
        }

        val startName = from?.name ?: to.name
        // BFS：parent 树回溯出 start→to 最短路径
        val parent = mutableMapOf<String, String>()
        val visited = mutableSetOf(startName)
        val queue = ArrayDeque<String>()
        queue.add(startName)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == to.name) break
            for (next in adjacency[cur].orEmpty().sorted()) {
                if (next in visited) continue
                visited.add(next)
                parent[next] = cur
                queue.add(next)
            }
        }
        if (to.name !in visited) return listOf(to) // 不可达 → 直线兜底

        val path = mutableListOf<String>()
        var cur = to.name
        while (cur != startName) {
            path.add(cur)
            cur = parent[cur] ?: break
        }
        path.reverse()
        return path.mapNotNull { byName[it] }.ifEmpty { listOf(to) }
    }

    /**
     * 距 (x,z) 最近的锚点（欧氏距离，XZ 平面）。[maxDistMeters] 内没有则
     * 返回 null——「角色不在任何锚点附近」时调用方按无起点处理。
     */
    fun nearestAnchor(anchors: List<MoveAnchor>, x: Float, z: Float, maxDistMeters: Float = 1.2f): MoveAnchor? {
        var best: MoveAnchor? = null
        var bestD2 = maxDistMeters * maxDistMeters
        for (a in anchors) {
            val dx = a.x - x
            val dz = a.z - z
            val d2 = dx * dx + dz * dz
            if (d2 <= bestD2) {
                bestD2 = d2
                best = a
            }
        }
        return best
    }
}
