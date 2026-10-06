package com.neethu.aiavatar_sdk

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiadapter.edge.EdgeTtsAdapter
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleLlmAdapter
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.rememberAvatarController
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.launch

/**
 * 最小接入样例：整个文件只用 SDK 公开 API（corelib 渲染 + orchestrator 会话 +
 * adapter 三个类），零 app 内部助手——与根目录 README.md「快速开始」代码逐行对应。
 *
 * 免 Key 可跑：显示 + 免费 Edge-TTS 说话照常工作；填入 [API_KEY] 后文本框
 * 即走 LLM 对话。
 *
 * adb 直达：`adb shell am start -n com.neethu.aiavatar_sdk/.SimpleDemoActivity`
 */
class SimpleDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SimpleDemoScreen() }
    }
}

// ── 接入配置：只填这几行 ──────────────────────────────────────────────
// 留空 API_KEY 也能跑（纯说话模式）；填入任意 OpenAI 兼容端点即开启对话。

private const val BASE_URL = "https://api.siliconflow.cn/v1"
private const val API_KEY = ""
private const val LLM_MODEL = "Qwen/Qwen3.8-27B"
private const val TTS_VOICE = "zh-CN-XiaoxiaoNeural"

/** 开场白：行内标签 <emo:joy> 会被解释成笑容（未知名静默丢弃，不会报错）。 */
private const val GREETING = "<emo:joy>你好呀！我是用几行代码接进来的虚拟人，很高兴见到你。"

@Composable
private fun SimpleDemoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ① 渲染：无参构造 + 一个 Composable，全屏铺底
    val controller = rememberAvatarController()
    val state by controller.state.collectAsState()

    // 模型加载（assets 里的 VRM；README 快速开始的第 2 步）
    LaunchedEffect(Unit) {
        controller.loadModel("vrms/10.vrm")
    }

    // ② 会话：Edge-TTS 免费无 Key；填了 API_KEY 才传 LLM adapter（null = 纯说话会话）
    val session = remember {
        AvatarSession(
            scope = scope,
            llm = if (API_KEY.isBlank()) null else OpenAiCompatibleLlmAdapter(BASE_URL, API_KEY),
            tts = EdgeTtsAdapter(),
            controller = controller,
        ).apply {
            ttsConfig = TtsConfig(model = "edge-readaloud", voice = TTS_VOICE, responseFormat = "mp3")
            if (API_KEY.isNotBlank()) {
                llmConfig = LlmConfig(baseUrl = BASE_URL, apiKey = API_KEY, model = LLM_MODEL)
            }
        }
    }

    // ③ 事件：订阅会话事件流（这里只兜底弹错误）
    LaunchedEffect(session) {
        session.events.collect { event ->
            if (event is AvatarEvent.TurnFailed) {
                Toast.makeText(context, "出错了：${event.error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ④ 模型就绪 → 启动面部驱动（口型/表情/眨眼/呼吸）+ 说一句开场白
    var greeted by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is AvatarState.Ready && !greeted) {
            greeted = true
            session.startFaceDriving()
            scope.launch { session.speak(GREETING) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AvatarView(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            config = AvatarConfig(iblPath = "default_env.ktx"),
        )

        // 模型加载状态提示（README 片段可省略这段）
        when (val s = state) {
            is AvatarState.Loading -> StatusText("模型加载中…", Modifier.align(Alignment.TopCenter))
            is AvatarState.Error -> StatusText("加载失败：${s.message}", Modifier.align(Alignment.TopCenter))
            else -> {}
        }

        // ⑤ 文本对话输入（未填 Key 时提示）
        var input by remember { mutableStateOf("") }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (API_KEY.isBlank()) "填入 API_KEY 后可对话" else "说点什么…",
                        color = Color.White,
                    )
                },
            )
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@Button
                    if (session.llmConfig == null) {
                        Toast.makeText(context, "请先在文件顶部填入 API_KEY", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    input = ""
                    session.send(text)
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { session.close() }
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(top = 48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
