package com.neethu.corelib.stage

import com.neethu.corelib.AvatarConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarConfigEqualityTest {
    @Test
    fun `two default configs are equal`() {
        val a = AvatarConfig()
        val b = AvatarConfig()
        assertTrue("AvatarConfig() != AvatarConfig() — remember(config) 会因此每轮重建", a == b)
    }
}
