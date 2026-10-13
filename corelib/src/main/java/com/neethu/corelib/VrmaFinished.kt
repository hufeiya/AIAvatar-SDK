package com.neethu.corelib

/**
 * 一次性 VRMA 播完（视觉上回到 idle）——舞台路径 [com.neethu.corelib.stage.AvatarHandle.vrmaFinished]
 * 的载荷。[avatarId] 为舞台内演员 id（"A"/"B"），归属语义与 [WalkFinished.avatarId] 一致。
 *
 * 「播完」= 引擎翻沿 `isOneShotActive()` true→false（淡出到 idle 的过渡结束时），
 * 停播（[AvatarActor.stopVrmaAnimation]）同样翻沿、同样发事件。
 */
data class VrmaFinished(val avatarId: String)
