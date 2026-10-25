package com.neethu.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.companion.ChatLine
import com.neethu.companion.ChatRole
import com.neethu.companion.MarkdownLineParser

/**
 * 聊天 UI（拷自 demo MainActivity 的 AiChatBar 家族,产品化分叉）：
 * markdown 气泡（P2-1）+ 状态徽标 + 按住/自由说话。demo 的 i18n Strings 在
 * 产品 v1 内联中文（目标社区中文优先;架构上留了文案集中区）。
 */

/** 虚拟人回复过 markdown 解析: *动作* 斜体、**粗体**、引号着色、`代码`。 */
internal fun markdownBubble(text: String): AnnotatedString = buildAnnotatedString {
    for (span in MarkdownLineParser.parse(text)) {
        when {
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            span.code -> withStyle(SpanStyle(color = Color(0xFF9CDCFE), fontWeight = FontWeight.SemiBold)) { append(span.text) }
            span.quote -> withStyle(SpanStyle(color = Color(0xFFFFD489))) { append(span.text) }
            span.italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(span.text) }
            else -> append(span.text)
        }
    }
}

@Composable
internal fun AiChatBar(
    phase: com.neethu.orchestrator.session.ConversationPhase,
    chatLines: List<ChatLine>,
    error: String?,
    enabled: Boolean,
    voiceMode: Boolean, // 语音输入形态(替代 demo 的 InputMode 枚举:产品 v1 只有文本/语音两形态)
    recording: Boolean,
    recognizing: Boolean,
    voiceFreeTalk: Boolean,
    freeHearing: Boolean,
    onToggleFreeTalk: () -> Unit,
    voicePrefill: String?,
    onVoicePrefillConsumed: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
    /** 气泡上方的关系状态行(G11,低调);null = 隐藏。 */
    relationshipLine: String? = null,
    onRelationshipTap: () -> Unit = {},
    onTapWhenDisabled: () -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val busy = phase != com.neethu.orchestrator.session.ConversationPhase.IDLE

    LaunchedEffect(voicePrefill) {
        voicePrefill?.let {
            input = it
            onVoicePrefillConsumed()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!relationshipLine.isNullOrBlank()) {
            Text(
                text = relationshipLine,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onRelationshipTap() }) },
            )
        }
        if (chatLines.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                val scrollState = rememberScrollState()
                LaunchedEffect(chatLines) {
                    snapshotFlow { scrollState.maxValue }.collect { max ->
                        if (max > 0) scrollState.animateScrollTo(max)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    chatLines.forEach { line ->
                        val user = line.role == ChatRole.USER
                        val system = line.role == ChatRole.SYSTEM
                        Text(
                            text = if (user || system) AnnotatedString(if (user) "你：${line.text}" else line.text) else markdownBubble(line.text),
                            color = when {
                                user -> Color(0xFF9ED8FF)
                                system -> Color(0xFF9E9E9E)
                                else -> Color.White
                            },
                            fontSize = if (system) 11.sp else 13.sp,
                            lineHeight = if (system) 15.sp else 18.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                AnimatedVisibility(visible = busy) {
                    Text(
                        text = when (phase) {
                            com.neethu.orchestrator.session.ConversationPhase.THINKING -> "思考中…"
                            com.neethu.orchestrator.session.ConversationPhase.SPEAKING -> "说话中"
                            com.neethu.orchestrator.session.ConversationPhase.IDLE -> ""
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp)
                    )
                }
                if (voiceMode && input.isBlank()) {
                    VoiceTalkModeToggle(free = voiceFreeTalk, onToggle = onToggleFreeTalk, modifier = Modifier.padding(end = 2.dp))
                    if (voiceFreeTalk) {
                        FreeListenIndicator(
                            recognizing = recognizing, hearing = freeHearing, enabled = enabled,
                            onTapWhenDisabled = onTapWhenDisabled,
                            modifier = Modifier.weight(1f).height(44.dp).padding(horizontal = 4.dp),
                        )
                    } else {
                        HoldToTalk(
                            recording = recording, recognizing = recognizing, enabled = enabled,
                            onPressStart = onHoldStart, onPressEnd = onHoldEnd,
                            onTapWhenDisabled = onTapWhenDisabled,
                            modifier = Modifier.weight(1f).height(44.dp).padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    if (voiceMode) {
                        VoiceTalkModeToggle(free = voiceFreeTalk, onToggle = onToggleFreeTalk, modifier = Modifier.padding(end = 2.dp))
                        HoldToTalk(
                            recording = recording, recognizing = recognizing, enabled = enabled,
                            onPressStart = onHoldStart, onPressEnd = onHoldEnd,
                            onTapWhenDisabled = onTapWhenDisabled,
                            compact = true, modifier = Modifier.size(40.dp),
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = {
                                Text(
                                    text = if (!enabled) "先在设置里配好 AI 服务" else "说点什么…",
                                    fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            enabled = enabled,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (enabled && input.isNotBlank()) { onSend(input); input = "" }
                            }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!enabled) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures(onTap = { onTapWhenDisabled() })
                                    }
                            )
                        }
                    }
                    IconButton(
                        onClick = { onSend(input); input = "" },
                        enabled = enabled && input.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (enabled && input.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (busy) {
                    IconButton(onClick = onInterrupt) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "打断")
                    }
                }
            }
        }
    }
}

@Composable
internal fun HoldToTalk(
    recording: Boolean,
    recognizing: Boolean,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onTapWhenDisabled: () -> Unit = {},
) {
    val bgColor = when {
        recording -> MaterialTheme.colorScheme.errorContainer
        recognizing -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val label = when {
        recording -> "松开 识别"
        recognizing -> "识别中…"
        enabled -> "按住说话"
        else -> "未配置"
    }
    Box(
        modifier = modifier
            .clip(if (compact) CircleShape else RoundedCornerShape(22.dp))
            .background(bgColor)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPressStart()
                                tryAwaitRelease()
                                onPressEnd()
                            }
                        )
                    }
                } else {
                    Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTapWhenDisabled() }) }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (compact) "🎤" else label,
            color = if (recording) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (compact) 16.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun VoiceTalkModeToggle(free: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (free) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        else Color.Black.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (free) "自由" else "按住",
                color = if (free) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "说话",
                color = if (free) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
internal fun FreeListenIndicator(
    recognizing: Boolean,
    hearing: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTapWhenDisabled: () -> Unit = {},
) {
    val label = when {
        !enabled -> "未配置"
        recognizing -> "识别中…"
        hearing -> "在听…"
        else -> "自由聆听中"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (hearing) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                else Color.Black.copy(alpha = 0.35f)
            )
            .pointerInput(enabled) {
                if (!enabled) detectTapGestures(onTap = { onTapWhenDisabled() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (hearing) MaterialTheme.colorScheme.onSecondaryContainer else Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
    }
}
