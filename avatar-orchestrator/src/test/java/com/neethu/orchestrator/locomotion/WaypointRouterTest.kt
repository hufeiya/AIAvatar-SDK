package com.neethu.orchestrator.locomotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * waypoint 图路由（自由走动 P2 §7.2 V2）的纯函数回归：无图退化、BFS 最短、
 * 无向边、不可达兜底、脏 links、最近锚点。
 */
class WaypointRouterTest {

    private fun a(name: String, x: Float = 0f, z: Float = 0f, links: List<String> = emptyList()) =
        MoveAnchor(name, x, z, links = links)

    @Test
    fun `no links anywhere degrades to direct line`() {
        val anchors = listOf(a("bed"), a("window"))
        assertEquals(listOf("window"), WaypointRouter.route(anchors, anchors[0], anchors[1]).map { it.name })
    }

    @Test
    fun `bfs finds shortest path through the graph`() {
        // A-B, B-C, A-C：A→C 直连一跳，BFS 不许绕 B
        val anchors = listOf(
            a("a", links = listOf("b", "c")),
            a("b", links = listOf("a", "c")),
            a("c", links = listOf("a", "b")),
        )
        assertEquals(listOf("c"), WaypointRouter.route(anchors, anchors[0], anchors[2]).map { it.name })
    }

    @Test
    fun `blocked pair routes around via waypoint`() {
        // A-B 直连、B-C 直连、A-C 不可达（家具隔开）
        val anchors = listOf(
            a("a", links = listOf("b")),
            a("b", links = listOf("c")),
            a("c"),
        )
        assertEquals(listOf("b", "c"), WaypointRouter.route(anchors, anchors[0], anchors[2]).map { it.name })
    }

    @Test
    fun `edges are undirected regardless of which side declares them`() {
        // links 只写在 A 端：B→A 也必须连通
        val anchors = listOf(a("a", links = listOf("b")), a("b"), a("c"))
        assertEquals(listOf("a"), WaypointRouter.route(anchors, anchors[1], anchors[0]).map { it.name })
    }

    @Test
    fun `unreachable target falls back to direct line`() {
        val anchors = listOf(
            a("a", links = listOf("b")),
            a("b"),
            a("isolated"),
        )
        assertEquals(
            listOf("isolated"),
            WaypointRouter.route(anchors, anchors[0], anchors[2]).map { it.name },
        )
    }

    @Test
    fun `null start starts from target directly`() {
        val anchors = listOf(a("a", links = listOf("b")), a("b"))
        assertEquals(listOf("b"), WaypointRouter.route(anchors, null, anchors[1]).map { it.name })
    }

    @Test
    fun `dirty link names never crash the walk`() {
        val anchors = listOf(a("a", links = listOf("ghost", "a")), a("b"))
        assertEquals(listOf("b"), WaypointRouter.route(anchors, anchors[0], anchors[1]).map { it.name })
    }

    @Test
    fun `nearest anchor respects radius and picks the closest`() {
        val anchors = listOf(a("near", 1f, 1f), a("far", 5f, 5f))
        assertEquals("near", WaypointRouter.nearestAnchor(anchors, 0.8f, 0.8f)?.name)
        assertNull("out of radius → no anchor", WaypointRouter.nearestAnchor(anchors, 3f, 3f, maxDistMeters = 1f))
        assertNull("empty table", WaypointRouter.nearestAnchor(emptyList(), 0f, 0f))
    }
}
