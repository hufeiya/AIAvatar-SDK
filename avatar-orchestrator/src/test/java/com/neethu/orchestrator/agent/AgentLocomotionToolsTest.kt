package com.neethu.orchestrator.agent

import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
import com.neethu.orchestrator.locomotion.LocomotionDriver
import com.neethu.orchestrator.locomotion.MoveAnchor
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * move_to / stop_moving 工厂（docs/locomotion-feasibility.md §7.3）：白名单
 * 校验、Outcome → 双语文案的映射、异步起步语义（起步即确认不阻塞等到达）
 * 与 schema 形态锁。行走缝是 fakes（真实链路真机验收）。
 */
class AgentLocomotionToolsTest {

    private val texts = promptTextsOf(Lang.ZH)

    private class FakeDriver : LocomotionDriver(AvatarController()) {
        var walkedTo: String? = null
        var stopCalls = 0
        var nextOutcome: Outcome? = null

        override fun find(name: String): MoveAnchor? =
            anchors.firstOrNull { it.name == name }

        override fun walkTo(anchor: MoveAnchor): Outcome {
            walkedTo = anchor.name
            return nextOutcome ?: Outcome.STARTED
        }

        override fun stopWalking() {
            stopCalls++
        }
    }

    private fun driverWith(vararg names: String): FakeDriver = FakeDriver().apply {
        anchors = names.map { MoveAnchor(it, 1f, 2f, label = "「$it」", labelEn = it) }
    }

    @Test
    fun `started outcome confirms with localized label without blocking`() = runTest {
        val d = driverWith("sofa")
        val (_, executor) = AgentToolCatalog.moveTo(texts) { d }
        val result = executor.invoke("""{"anchor":"sofa"}""")
        assertFalse(result.isError)
        assertEquals(texts.toolMoveToStarted("「sofa」"), result.content)
        assertEquals("sofa", d.walkedTo)
    }

    @Test
    fun `already walking is a normal result model keeps talking`() = runTest {
        val d = driverWith("sofa")
        d.nextOutcome = LocomotionDriver.Outcome.ALREADY_WALKING
        val (_, executor) = AgentToolCatalog.moveTo(texts) { d }
        val result = executor.invoke("""{"anchor":"sofa"}""")
        assertFalse(result.isError)
        assertEquals(texts.toolMoveToAlreadyWalking, result.content)
    }

    @Test
    fun `unknown anchor lists the anchor table`() = runTest {
        val d = driverWith("sofa", "window")
        val (_, executor) = AgentToolCatalog.moveTo(texts) { d }
        val result = executor.invoke("""{"anchor":"couch"}""")
        assertTrue(result.isError)
        assertEquals(
            texts.toolEnumArgInvalid("move_to", "couch", listOf("sofa", "window")),
            result.content,
        )
    }

    @Test
    fun `null driver (feature unconfigured) feeds back no-anchors error`() = runTest {
        val (_, executor) = AgentToolCatalog.moveTo(texts) { null }
        val result = executor.invoke("""{"anchor":"sofa"}""")
        assertTrue(result.isError)
        assertEquals(texts.toolMoveToNoAnchors, result.content)
    }

    @Test
    fun `failure outcomes map to dedicated error texts`() = runTest {
        suspend fun contentFor(outcome: LocomotionDriver.Outcome): String {
            val d = driverWith("sofa")
            d.nextOutcome = outcome
            return AgentToolCatalog.moveTo(texts) { d }.second
                .invoke("""{"anchor":"sofa"}""").content
        }
        assertEquals(texts.toolMoveToNoModel, contentFor(LocomotionDriver.Outcome.NO_MODEL))
        assertEquals(texts.toolMoveToNoScene, contentFor(LocomotionDriver.Outcome.NO_SCENE))
        assertEquals(texts.toolMoveToNoGait, contentFor(LocomotionDriver.Outcome.NO_GAIT))
        assertEquals(texts.toolMoveToMimicActive, contentFor(LocomotionDriver.Outcome.MIMIC_ACTIVE))
        assertEquals(texts.toolMoveToFailed, contentFor(LocomotionDriver.Outcome.NO_RENDERER))
        assertEquals(texts.toolMoveToFailed, contentFor(LocomotionDriver.Outcome.LOAD_FAILED))
    }

    @Test
    fun `optional speed passes through and clamps`() = runTest {
        val d = driverWith("sofa")
        val (_, executor) = AgentToolCatalog.moveTo(texts) { d }
        executor.invoke("""{"anchor":"sofa","speed":1.8}""")
        assertEquals(1.8f, d.speed, 1e-4f)
        executor.invoke("""{"anchor":"sofa","speed":99}""")
        assertEquals(3.0f, d.speed, 1e-4f)
        // 坏值忽略走默认（不为一格参数炸一轮）
        executor.invoke("""{"anchor":"sofa","speed":"fast"}""")
        assertEquals(3.0f, d.speed, 1e-4f)
    }

    @Test
    fun `schema locks anchor enum from the anchor table`() {
        val d = driverWith("sofa", "window")
        val (spec, _) = AgentToolCatalog.moveTo(texts) { d }
        val schema = spec.parametersSchema.toString()
        assertTrue(schema.contains(""""anchor""""))
        assertTrue(schema.contains("sofa"))
        assertTrue(schema.contains("window"))
        assertTrue(schema.contains(""""speed""""))
    }

    @Test
    fun `stop_moving calls the driver and confirms`() = runTest {
        val d = driverWith("sofa")
        val (_, executor) = AgentToolCatalog.stopMoving(texts) { d }
        val result = executor.invoke("{}")
        assertFalse(result.isError)
        assertEquals(texts.toolStopMovingDone, result.content)
        assertEquals(1, d.stopCalls)
    }

    @Test
    fun `stop_moving without driver feeds back error`() = runTest {
        val (_, executor) = AgentToolCatalog.stopMoving(texts) { null }
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertEquals(texts.toolMoveToNoAnchors, result.content)
    }
}
