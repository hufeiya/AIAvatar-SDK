package com.neethu.companion

import com.neethu.corelib.AmbiencePreset
import com.neethu.corelib.vfx.VfxPresets

/**
 * 约会模式（酒馆 P3-3 氛围仪式感）：一键切场景 + 烛光 VFX + 暖暗灯光 +
 * 背景音乐 + 开场一句。卡绑场景优先（[CardBinding.sceneFile]），无绑定走
 * 默认卧室。关闭 = 回到约会前的场景与灯光（VFX/音乐停）。
 */
internal object DateMode {
    const val PREF_ENABLED = "date_mode_enabled"
    const val PREF_PREV_SCENE = "date_mode_prev_scene"
    const val PREF_PREV_AMBIENCE = "date_mode_prev_ambience"

    /** 默认约会场景（无卡绑定时）。 */
    const val DEFAULT_SCENE = "dream_bedroom.glb"

    /** 烛光 VFX（assets 预设：火焰）与音乐。 */
    const val VFX_ID = "fire"
    const val MUSIC_ASSET = "music/showtime-45s.mp3"

    /** 约会开场白（speak 直说，带表情动作标签）。 */
    const val DATE_LINE = "<emo:joy><act:wave>今晚的月色真好…和你在一起的每一刻都想永远留下来。"
    const val DATE_LINE_OFF = "<emo:joy>约会结束啦,今天也很开心哦。"

    /** 约会灯光：昏暗夜话（3500 lux,低照度暖感）。 */
    val AMBIENCE = AmbiencePreset.DREAMY

    fun vfxSpec() = VfxPresets.find(VFX_ID)
}
