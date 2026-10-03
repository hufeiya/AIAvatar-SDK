package com.neethu.aiavatar_sdk.ui

import androidx.compose.animation.animateColorAsState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.corelib.AmbientOcclusionQuality
import com.neethu.corelib.AntiAliasingMode
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.LightingRig
import com.neethu.corelib.QualityPreset
import com.neethu.corelib.ToneMappingMode
import kotlin.math.roundToInt

/**
 * Full settings screen: a dim scrim over the 3D view plus a bottom sheet of
 * animation source options and PBR render quality options (quality presets,
 * lighting, shadows, AO, post-processing, anti-aliasing and material
 * upgrades). Changes are pushed to the SDK immediately via [onSettingsChange].
 */
@Composable
internal fun SettingsScreen(
    settings: AvatarRenderSettings,
    useExternalAnimations: Boolean,
    externalRootPath: String?,
    aiPrefs: AiChatPrefs,
    onAnimationSourceChange: (Boolean) -> Unit,
    onSettingsChange: (AvatarRenderSettings) -> Unit,
    onAiPrefsChange: (AiChatPrefs) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume taps inside the sheet so it stays open */ }
                ),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close settings")
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // ── 动画资源 ─────────────────────────────────────
                    item { SettingsSectionHeader("动画资源 (Animations)") }
                    item {
                        SettingsOptionRow(
                            title = "APK 内置动画",
                            subtitle = "打包在 assets/animations 中，开箱即用（默认）",
                            selected = !useExternalAnimations
                        ) {
                            onAnimationSourceChange(false)
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "手机外存动画",
                            subtitle = "扫描 App data 目录及其子文件夹中的 .vrma，不占 APK 体积",
                            selected = useExternalAnimations
                        ) {
                            onAnimationSourceChange(true)
                        }
                    }
                    if (useExternalAnimations) {
                        item {
                            SettingsGroupLabel(
                                "目录：${externalRootPath ?: "外部存储不可用"}\n将 .vrma 文件放入该目录即可（支持子文件夹分类）"
                            )
                        }
                    }

                    // ── AI 对话 ────────────────────────────────────────
                    item { SettingsSectionHeader("AI 对话 (AI Chat · OpenAI 兼容)") }
                    item {
                        SettingsGroupLabel(
                            if (aiPrefs.isConfigured) "已配置，保存后立即生效" else "填写以下五项后即可对话"
                        )
                    }
                    item {
                        SettingsTextFieldRow(
                            title = "API Base URL",
                            value = aiPrefs.baseUrl,
                            placeholder = "https://api.openai.com/v1"
                        ) { onAiPrefsChange(aiPrefs.copy(baseUrl = it)) }
                    }
                    item {
                        SettingsTextFieldRow(
                            title = "API Key",
                            value = aiPrefs.apiKey,
                            placeholder = "sk-...",
                            password = true
                        ) { onAiPrefsChange(aiPrefs.copy(apiKey = it)) }
                    }
                    item {
                        SettingsTextFieldRow(
                            title = "LLM 模型",
                            value = aiPrefs.llmModel,
                            placeholder = "gpt-4o-mini / deepseek-chat / ..."
                        ) { onAiPrefsChange(aiPrefs.copy(llmModel = it)) }
                    }
                    item {
                        SettingsTextFieldRow(
                            title = "TTS 模型",
                            value = aiPrefs.ttsModel,
                            placeholder = "tts-1 / playai-tts / ..."
                        ) { onAiPrefsChange(aiPrefs.copy(ttsModel = it)) }
                    }
                    item {
                        SettingsTextFieldRow(
                            title = "音色 Voice",
                            value = aiPrefs.voice,
                            placeholder = "alloy /Arabella / ..."
                        ) { onAiPrefsChange(aiPrefs.copy(voice = it)) }
                    }
                    item {
                        SettingsSwitchRow(
                            title = "AI 可控镜头",
                            subtitle = "允许模型用 <cam:…> 标签切换视角；关闭后模型不再动你的取景",
                            checked = aiPrefs.llmCamera
                        ) { onAiPrefsChange(aiPrefs.copy(llmCamera = it)) }
                    }

                    // ── 画质预设 ─────────────────────────────────────
                    item { SettingsSectionHeader("画质预设 · 一键档位") }
                    item {
                        SettingsActionRow(
                            title = "低配 Low",
                            subtitle = "单主光 · 1024 阴影 · 无AO · FXAA · 移动端稳 60fps"
                        ) {
                            onSettingsChange(
                                QualityPreset.LOW.toRenderSettings(
                                    settings.iblIntensity,
                                    settings.iblRotationDegrees,
                                    settings.showFps
                                )
                            )
                        }
                    }
                    item {
                        SettingsActionRow(
                            title = "主流 Medium",
                            subtitle = "主光+辅光 · 1024 阴影 · SSAO · ACES + 轻度泛光"
                        ) {
                            onSettingsChange(
                                QualityPreset.MEDIUM.toRenderSettings(
                                    settings.iblIntensity,
                                    settings.iblRotationDegrees,
                                    settings.showFps
                                )
                            )
                        }
                    }
                    item {
                        SettingsActionRow(
                            title = "高配 High",
                            subtitle = "三点布光 · 2048 阴影 · GTAO · ACES + 泛光"
                        ) {
                            onSettingsChange(
                                QualityPreset.HIGH.toRenderSettings(
                                    settings.iblIntensity,
                                    settings.iblRotationDegrees,
                                    settings.showFps
                                )
                            )
                        }
                    }
                    item {
                        SettingsActionRow(
                            title = "3A Ultra",
                            subtitle = "4096 阴影 + 接触阴影 · GTAO · TAA · 泛光 + 景深 + 材质增强"
                        ) {
                            onSettingsChange(
                                QualityPreset.ULTRA.toRenderSettings(
                                    settings.iblIntensity,
                                    settings.iblRotationDegrees,
                                    settings.showFps
                                )
                            )
                        }
                    }

                    // ── 光照与环境 ───────────────────────────────────
                    item { SettingsSectionHeader("光照与环境 (Lighting & IBL)") }
                    item {
                        SettingsSliderRow(
                            title = "IBL 环境光强度",
                            valueText = "${settings.iblIntensity.roundToInt()} lux",
                            value = settings.iblIntensity,
                            valueRange = 0f..50_000f
                        ) {
                            onSettingsChange(settings.copy(iblIntensity = it))
                        }
                    }
                    item {
                        SettingsSliderRow(
                            title = "IBL 环境光旋转",
                            valueText = "${settings.iblRotationDegrees.roundToInt()}°",
                            value = settings.iblRotationDegrees,
                            valueRange = 0f..360f
                        ) {
                            onSettingsChange(settings.copy(iblRotationDegrees = it))
                        }
                    }
                    item { SettingsGroupLabel("布光方案") }
                    item {
                        SettingsOptionRow(
                            title = "单主光",
                            subtitle = "仅 1 盏主平行光 + IBL，性能最优",
                            selected = settings.lightingRig == LightingRig.KEY_ONLY
                        ) {
                            onSettingsChange(settings.copy(lightingRig = LightingRig.KEY_ONLY))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "双点光",
                            subtitle = "主光 + 冷色辅光",
                            selected = settings.lightingRig == LightingRig.KEY_FILL
                        ) {
                            onSettingsChange(settings.copy(lightingRig = LightingRig.KEY_FILL))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "摄影棚三点布光",
                            subtitle = "主光 + 辅光 + 强轮廓背光，勾勒发丝与肩膀",
                            selected = settings.lightingRig == LightingRig.STUDIO
                        ) {
                            onSettingsChange(settings.copy(lightingRig = LightingRig.STUDIO))
                        }
                    }

                    // ── 阴影 ─────────────────────────────────────────
                    item { SettingsSectionHeader("阴影 (Shadows)") }
                    item { SettingsGroupLabel("阴影贴图分辨率") }
                    item {
                        SettingsOptionRow(
                            title = "1024 (Low)",
                            subtitle = "低端机适用",
                            selected = settings.shadowMapSize == 1024
                        ) {
                            onSettingsChange(settings.copy(shadowMapSize = 1024))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "2048 (Medium)",
                            subtitle = "精度与开销平衡，旗舰机适用",
                            selected = settings.shadowMapSize == 2048
                        ) {
                            onSettingsChange(settings.copy(shadowMapSize = 2048))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "4096 (Ultra)",
                            subtitle = "发丝级阴影细节，高端机适用",
                            selected = settings.shadowMapSize == 4096
                        ) {
                            onSettingsChange(settings.copy(shadowMapSize = 4096))
                        }
                    }
                    item {
                        SettingsSwitchRow(
                            title = "软阴影 (PCSS)",
                            subtitle = "基于物理的阴影半影，边缘近实远虚；Adreno 上开销极大（实测个位数帧率），仅限旗舰机尝试",
                            checked = settings.softShadows
                        ) {
                            onSettingsChange(settings.copy(softShadows = it))
                        }
                    }
                    item {
                        SettingsSwitchRow(
                            title = "接触阴影 (Contact Shadows)",
                            subtitle = "屏幕空间微阴影：睫毛、鼻翼极近距离暗部，轻微 GPU 开销",
                            checked = settings.contactShadows
                        ) {
                            onSettingsChange(settings.copy(contactShadows = it))
                        }
                    }

                    // ── 环境光遮蔽 ───────────────────────────────────
                    item { SettingsSectionHeader("环境光遮蔽 (Ambient Occlusion)") }
                    item {
                        SettingsOptionRow(
                            title = "关闭",
                            subtitle = "省电，模型易显“漂浮感”",
                            selected = settings.ambientOcclusion == AmbientOcclusionQuality.OFF
                        ) {
                            onSettingsChange(settings.copy(ambientOcclusion = AmbientOcclusionQuality.OFF))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "标准 SSAO",
                            subtitle = "屏幕空间环境光遮蔽，中等采样",
                            selected = settings.ambientOcclusion == AmbientOcclusionQuality.STANDARD
                        ) {
                            onSettingsChange(settings.copy(ambientOcclusion = AmbientOcclusionQuality.STANDARD))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "高质量 GTAO (推荐)",
                            subtitle = "Ground-Truth AO + 双边滤波，眼眶/鼻窝/褶皱暗角自然",
                            selected = settings.ambientOcclusion == AmbientOcclusionQuality.HIGH
                        ) {
                            onSettingsChange(settings.copy(ambientOcclusion = AmbientOcclusionQuality.HIGH))
                        }
                    }

                    // ── 后处理 ───────────────────────────────────────
                    item { SettingsSectionHeader("后处理 (Post-Processing)") }
                    item { SettingsGroupLabel("色调映射") }
                    item {
                        SettingsOptionRow(
                            title = "Linear",
                            subtitle = "线性，不推荐：高光极易过曝",
                            selected = settings.toneMapping == ToneMappingMode.LINEAR
                        ) {
                            onSettingsChange(settings.copy(toneMapping = ToneMappingMode.LINEAR))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "Filmic",
                            subtitle = "高对比度电影感",
                            selected = settings.toneMapping == ToneMappingMode.FILMIC
                        ) {
                            onSettingsChange(settings.copy(toneMapping = ToneMappingMode.FILMIC))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "ACES (推荐)",
                            subtitle = "Unreal/3A 标配，极佳的高光滚降",
                            selected = settings.toneMapping == ToneMappingMode.ACES
                        ) {
                            onSettingsChange(settings.copy(toneMapping = ToneMappingMode.ACES))
                        }
                    }
                    item {
                        SettingsSwitchRow(
                            title = "泛光 (Bloom)",
                            subtitle = "强光照射金属/眼球时的漫溢辉光",
                            checked = settings.bloomEnabled
                        ) {
                            onSettingsChange(settings.copy(bloomEnabled = it))
                        }
                    }
                    item {
                        SettingsSliderRow(
                            title = "泛光强度",
                            valueText = "%.2f".format(settings.bloomStrength),
                            value = settings.bloomStrength,
                            valueRange = 0f..0.5f
                        ) {
                            onSettingsChange(settings.copy(bloomStrength = it))
                        }
                    }
                    item {
                        SettingsSwitchRow(
                            title = "景深 (Depth of Field)",
                            subtitle = "特写模式虚化背景，单反微距质感；全身全景建议关闭",
                            checked = settings.depthOfFieldEnabled
                        ) {
                            onSettingsChange(settings.copy(depthOfFieldEnabled = it))
                        }
                    }

                    // ── 抗锯齿 ───────────────────────────────────────
                    item { SettingsSectionHeader("抗锯齿 (Anti-Aliasing)") }
                    item {
                        SettingsOptionRow(
                            title = "关闭",
                            subtitle = "无抗锯齿",
                            selected = settings.antiAliasing == AntiAliasingMode.NONE
                        ) {
                            onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.NONE))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "FXAA",
                            subtitle = "轻量，低端机适用，画面略糊",
                            selected = settings.antiAliasing == AntiAliasingMode.FXAA
                        ) {
                            onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.FXAA))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "MSAA 4x",
                            subtitle = "几何边缘清晰，开销较大",
                            selected = settings.antiAliasing == AntiAliasingMode.MSAA_4X
                        ) {
                            onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.MSAA_4X))
                        }
                    }
                    item {
                        SettingsOptionRow(
                            title = "TAA (3A 画质首选)",
                            subtitle = "抹平高频闪烁，带锐化；快速运动可能有轻微拖影",
                            selected = settings.antiAliasing == AntiAliasingMode.TAA
                        ) {
                            onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.TAA))
                        }
                    }

                    // ── 材质 ─────────────────────────────────────────
                    item { SettingsSectionHeader("材质增强 (Materials)") }
                    item {
                        SettingsSwitchRow(
                            title = "材质特性增强",
                            subtitle = "眼球 ClearCoat + 皮肤/头发粗糙度优化；实际效果取决于模型材质支持，关闭后自动重载模型还原",
                            checked = settings.enhanceMaterials
                        ) {
                            onSettingsChange(settings.copy(enhanceMaterials = it))
                        }
                    }

                    // ── 显示 ─────────────────────────────────────────
                    item { SettingsSectionHeader("显示 (Display)") }
                    item {
                        SettingsSwitchRow(
                            title = "显示 FPS 帧率",
                            subtitle = "在右上角实时显示渲染帧率",
                            checked = settings.showFps
                        ) {
                            onSettingsChange(settings.copy(showFps = it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
    )
}

/**
 * A selectable settings entry with title + description and a trailing radio.
 */
@Composable
private fun SettingsOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "settingsOptionBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * A one-shot preset action row (same look as [SettingsOptionRow] without a radio).
 */
@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A boolean settings entry with a trailing [Switch]. The whole row toggles.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A slider settings entry showing the current value on the trailing side.
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * A small label inside a section that groups a set of related rows.
 */
@Composable
private fun SettingsGroupLabel(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
    )
}

/**
 * A labeled single-line text input for AI provider configuration.
 * Changes stream straight through [onValueChange]; the caller decides
 * when to persist / rebuild the session.
 */
@Composable
private fun SettingsTextFieldRow(
    title: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(text = placeholder, fontSize = 13.sp) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}
