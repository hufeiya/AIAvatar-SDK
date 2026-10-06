package com.neethu.aiavatar_sdk.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.aiavatar_sdk.AiProvider
import com.neethu.aiavatar_sdk.AsrEngine
import com.neethu.aiavatar_sdk.TtsEngine
import com.neethu.aiavatar_sdk.VoicePrefs
import com.neethu.aiavatar_sdk.i18n.LocalStrings
import com.neethu.corelib.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 新手引导（2026-10 用户需求「让用户 1 分钟内直接玩」+「用同样方式做非中文
 * 用户引导」）：AI 还没配置好时自动弹出的半屏面板（与设置页同款 bottom
 * sheet），双受众（[GuideVariant]）——国内=硅基流动（简繁中文系统），海外=
 * OpenRouter（其余全部语言）。3 张步骤截图轮播 + 第 4 页注册链接与 Key 输入；
 * 确认后一键配置 大模型/TTS/ASR。可关闭；点输入框/语音框会再弹。
 *
 * 触发判定与一键配置是纯函数（[onboardingGuideVariant]/[withOnboardingKey]/
 * [withOnboardingAsr]），单测直测；面板本体只在 MainActivity 挂载。
 */

/**
 * 引导受众。两家的免费账号新手体验不同，一键配置随之分叉：
 * - [CN]：硅基流动一把 Key 通吃（大模型/TTS/ASR 同端点），实名后领代金券；
 * - [INTL]：OpenRouter 大模型用免费路由（其 TTS 音频端点要求账户 ≥$0.5 余额，
 *   新号不可用 → TTS 落 Edge-TTS 免费；ASR 同理 → 系统内置识别）。
 */
enum class GuideVariant(
    val provider: AiProvider,
    /** 注册页链接（第 4 页按钮，跳外部浏览器）。 */
    val registerUrl: String,
) {
    CN(AiProvider.SILICONFLOW, "https://cloud.siliconflow.cn/i/iPLguD02"),
    INTL(AiProvider.OPENROUTER, "https://openrouter.ai/"),
}

/**
 * 轮播步骤截图（assets 相对路径，3 张；来源：用户提供的「国内引导」「国外
 * 引导」截图）。海外流程 = 首页 Get API Key → 登录 → 复制自动生成的 Key。
 */
fun guideStepAssets(variant: GuideVariant): List<String> = when (variant) {
    GuideVariant.CN -> listOf(
        "guide/step1_register.jpeg",
        "guide/step2_realname.jpeg",
        "guide/step3_apikey.jpeg",
    )
    GuideVariant.INTL -> listOf(
        "guide/intl_step1_getkey.jpeg",
        "guide/intl_step2_signin.jpeg",
        "guide/intl_step3_copy.jpeg",
    )
}

/** 轮播总页数 = 3 张截图 + 第 4 页（链接 + Key 输入，无图）。 */
const val GUIDE_PAGE_COUNT = 4

/**
 * 引导触发判定：AI 服务没配置好（大模型 Key 未填）时，系统中文（简繁都算，
 * 归 [Lang.ZH]）→ 国内版，其余语言 → 海外版；已配置返回 null（不打扰）。
 * 纯函数可测。
 */
fun onboardingGuideVariant(lang: Lang, aiConfigured: Boolean): GuideVariant? = when {
    aiConfigured -> null
    lang == Lang.ZH -> GuideVariant.CN
    else -> GuideVariant.INTL
}

/**
 * 「确认」时的一键配置（纯函数可测）。大模型都选**免费 + 带视觉**的落点：
 * - 国内：硅基流动视觉清单首位（Qwen3.8-27B 多模态，视频模式开箱即用）；
 * - 海外：OpenRouter 目录里名字含 "free" 的首个（用户点名规则；当前即
 *   `openrouter/free` 官方免费路由，64px 红蓝图实测真视觉）。名字含 free 的
 *   条目哪天没了就落视觉清单首位，引导不空手。
 * TTS：国内走 OpenAI 兼容端点（一把 Key 通吃）；海外 Edge-TTS（免费无 Key，
 * 避开 OpenRouter 音频端点的 ≥$0.5 余额门槛）。其余服务商旧 Key 原样保留。
 */
fun withOnboardingKey(prefs: AiChatPrefs, key: String, variant: GuideVariant): AiChatPrefs {
    val p = variant.provider
    val trimmed = key.trim()
    return prefs.copy(
        provider = p,
        apiKeySiliconflow = if (p == AiProvider.SILICONFLOW) trimmed else prefs.apiKeySiliconflow,
        apiKeyOpenrouter = if (p == AiProvider.OPENROUTER) trimmed else prefs.apiKeyOpenrouter,
        llmModel = p.llmModels.firstOrNull { it.contains("free", ignoreCase = true) }
            ?: p.visionLlmModels.first(),
        ttsSameProvider = true,
        ttsProvider = p,
        ttsModel = "",
        ttsEngine = if (variant == GuideVariant.INTL) TtsEngine.EDGE else TtsEngine.OPENAI_COMPATIBLE,
    )
}

/**
 * ASR 同步归位（纯函数可测）：国内=云端识别（硅基流动，Key 与大模型共用）；
 * 海外=系统内置识别（OpenRouter 音频端点要 ≥$0.5 余额，新号不可用）。模型
 * 都留空=按引擎/端点推断默认。
 */
fun withOnboardingAsr(prefs: VoicePrefs, variant: GuideVariant): VoicePrefs = prefs.copy(
    asrEngine = if (variant == GuideVariant.INTL) AsrEngine.SYSTEM else AsrEngine.CLOUD,
    asrModel = "",
)

/** 跳外部浏览器打开注册页；无浏览器时 false（调用方给提示）。 */
fun openRegisterPage(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.isSuccess

/**
 * 半屏引导面板：暗色遮罩 + 底部 62% 高 Surface（与 SettingsScreen 同款样式，
 * 固定高度不拖拽）。`onConfirm` 收到 trim 后的 Key；关闭/确认都由调用方收口。
 * [variant] 决定截图组/注册链接/文案（adb `open_panel guide_cn|guide_intl`
 * 可强制指定，验证另一受众的面板）。
 */
@Composable
fun OnboardingGuideSheet(
    variant: GuideVariant,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* 吃掉面板内点击，防透传到遮罩 */ }
                ),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题行 + 关闭
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.guideTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = s.guideCloseA11y)
                    }
                }
                Text(
                    text = s.guideIntro(variant.provider),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

                val steps = guideStepAssets(variant)
                val pagerState = rememberPagerState(pageCount = { GUIDE_PAGE_COUNT })
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    if (page < steps.size) {
                        GuideStepPage(variant = variant, stepAsset = steps[page], stepIndex = page)
                    } else {
                        GuideKeyPage(
                            variant = variant,
                            key = key,
                            onKeyChange = { key = it },
                            onOpenRegister = {
                                if (!openRegisterPage(context, variant.registerUrl)) {
                                    Toast.makeText(context, s.guideBrowserFail, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onConfirm = { onConfirm(key.trim()) },
                        )
                    }
                }

                // 页点指示器（第 4 页高亮 = 引导用户滑到输入页）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(GUIDE_PAGE_COUNT) { i ->
                        val active = pagerState.currentPage == i
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(width = if (active) 18.dp else 8.dp, height = 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                )
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = s.guideSwipeHint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 提示条：确认即自动配置（让用户放心随便填）
                Text(
                    text = s.guideAutoNote(variant.provider),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 轮播步骤页（1-3）：截图 + 步骤标题；国内版第 2 页（实名）叠「电脑版网页」提示。 */
@Composable
private fun GuideStepPage(variant: GuideVariant, stepAsset: String, stepIndex: Int) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, stepAsset) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(stepAsset).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.asImageBitmap()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = s.guideStepCaption(variant.provider, stepIndex),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        if (variant == GuideVariant.CN && stepIndex == 1) {
            // 需求点名：国内实名认证这步手机浏览器要切电脑版网页（海外流程
            // 截图本就是手机版网页，不需要）
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = s.guideStep2Hint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 6.dp, bottom = 4.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

/** 第 4 页（无图）：注册链接按钮 + Key 输入 + 确认。 */
@Composable
private fun GuideKeyPage(
    variant: GuideVariant,
    key: String,
    onKeyChange: (String) -> Unit,
    onOpenRegister: () -> Unit,
    onConfirm: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = s.guideLastTitle(variant.provider),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Button(
            onClick = onOpenRegister,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text(text = s.guideOpenRegister(variant.provider), fontSize = 14.sp)
        }
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChange,
            placeholder = { Text(text = s.guideKeyPlaceholder(variant.provider), fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) onConfirm() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
        Button(
            onClick = onConfirm,
            enabled = key.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text(
                text = if (key.isBlank()) s.guideKeyEmpty else s.guideConfirm,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = variant.registerUrl,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
