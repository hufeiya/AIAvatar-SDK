package com.neethu.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.companion.AiChatPrefs
import com.neethu.companion.AiProvider
import com.neethu.companion.ConversationContextSummary
import com.neethu.companion.LorebookLibrary
import com.neethu.companion.VoicePrefs
import com.neethu.companion.resolveLlmModel

/**
 * 产品设置面板（demo SettingsScreen 的产品化子集,重写为紧凑版）：AI 服务 /
 * 语音 / 语言 / 记忆 / 关系与问候(G11+G10) / 世界书 / 上下文与记录 / 画质 /
 * 摘要。demo 追求展示所有开关,产品追求默认值即最优——没有的维度(运动调参/
 * 外置动画/天空盒/质量细档)留在 demo。
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPanel(
    aiPrefs: AiChatPrefs,
    voicePrefs: VoicePrefs,
    appLangZh: Boolean,
    onAiPrefsChange: (AiChatPrefs) -> Unit,
    onVoicePrefsChange: (VoicePrefs) -> Unit,
    onAppLangChange: (Boolean) -> Unit,
    // 关系/问候(G11+G10)
    relationshipTracking: Boolean,
    onRelationshipTrackingChange: (Boolean) -> Unit,
    greetingNotify: Boolean,
    onGreetingNotifyChange: (Boolean) -> Unit,
    onResetRelationship: () -> Unit,
    // 记忆
    memoryPanelOpen: () -> Unit,
    // 世界书
    lorebooks: List<LorebookLibrary.Entry>,
    lorebookEnabled: Boolean,
    lorebookGlobalFile: String?,
    onLorebookEnabledChange: (Boolean) -> Unit,
    onImportLorebook: () -> Unit,
    onLorebookSetGlobal: (String?) -> Unit,
    onLorebookDelete: (String) -> Unit,
    // 上下文与记录
    contexts: List<ConversationContextSummary>,
    activeContextId: String,
    historyMessageCount: Int,
    onNewContext: () -> Unit,
    onSelectContext: (String) -> Unit,
    onDeleteContext: (String) -> Unit,
    onExportHistoryJsonl: () -> Unit,
    onExportHistoryMarkdown: () -> Unit,
    onImportHistoryJsonl: () -> Unit,
    // 画质
    ambienceNames: List<Pair<String, String>>,
    selectedAmbience: String,
    onAmbienceChange: (String) -> Unit,
    // 摘要
    historySummaryEnabled: Boolean,
    onHistorySummaryChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { SectionTitle("AI 服务") }
                item {
                    val p = aiPrefs.provider
                    DropdownRow(
                        label = "服务商",
                        options = AiProvider.entries.map { it.name to it.label },
                        selected = p.name,
                        onSelect = { name ->
                            val next = AiProvider.valueOf(name)
                            onAiPrefsChange(aiPrefs.copy(provider = next, llmModel = resolveLlmModel(next, "")))
                        },
                    )
                }
                item {
                    TextFieldRow(
                        label = aiPrefs.provider.llmKeyLabel,
                        value = aiPrefs.apiKeyFor(aiPrefs.provider),
                        isPassword = true,
                        onValueChange = { onAiPrefsChange(aiPrefs.withApiKey(aiPrefs.provider, it)) },
                    )
                }
                item {
                    DropdownRow(
                        label = "模型",
                        options = (aiPrefs.provider.sdk.llmModels + aiPrefs.provider.sdk.visionLlmModels).distinct().map { it to it },
                        selected = aiPrefs.llmModel.ifBlank { aiPrefs.provider.sdk.defaultLlmModel },
                        onSelect = { onAiPrefsChange(aiPrefs.copy(llmModel = it)) },
                    )
                }
                if (aiPrefs.provider == AiProvider.CUSTOM) {
                    item {
                        TextFieldRow(
                            label = "Base URL(OpenAI 兼容)",
                            value = aiPrefs.customBaseUrl,
                            onValueChange = { onAiPrefsChange(aiPrefs.copy(customBaseUrl = it.trim())) },
                        )
                    }
                    item {
                        Text(
                            "自定义端点必须显式填模型名;TTS 不支持时自动回落 Edge 免费朗读",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { SectionTitle("语音") }
                item {
                    DropdownRow(
                        label = "朗读音色",
                        options = voiceOptions(aiPrefs),
                        selected = aiPrefs.voice,
                        onSelect = { onAiPrefsChange(aiPrefs.copy(voice = it)) },
                    )
                }
                item {
                    DropdownRow(
                        label = "语音识别",
                        options = listOf("CLOUD" to "云端识别(需服务商)", "SYSTEM" to "系统识别(免费)"),
                        selected = voicePrefs.asrEngine.name,
                        onSelect = { name ->
                            onVoicePrefsChange(voicePrefs.copy(asrEngine = com.neethu.companion.AsrEngine.valueOf(name)))
                        },
                    )
                }
                item {
                    SwitchRow("识别后直接发送", voicePrefs.autoSend) {
                        onVoicePrefsChange(voicePrefs.copy(autoSend = it))
                    }
                }
                item { SectionTitle("语言") }
                item {
                    DropdownRow(
                        label = "界面与提示词",
                        options = listOf("ZH" to "中文", "EN" to "English"),
                        selected = if (appLangZh) "ZH" else "EN",
                        onSelect = { onAppLangChange(it == "ZH") },
                    )
                }
                item { SectionTitle("记忆与关系") }
                item {
                    SwitchRow("长期记忆", aiPrefs.memory) { onAiPrefsChange(aiPrefs.copy(memory = it)) }
                }
                item {
                    SwitchRow("自动提炼记忆", aiPrefs.memoryAutoExtract) {
                        onAiPrefsChange(aiPrefs.copy(memoryAutoExtract = it))
                    }
                }
                item {
                    SwitchRow("关系进展追踪(G11)", relationshipTracking, onRelationshipTrackingChange)
                }
                item {
                    TextButton(onClick = onResetRelationship) { Text("重置关系(数据主权)") }
                }
                item {
                    SwitchRow("后台问候通知(G10)", greetingNotify, onGreetingNotifyChange)
                }
                item {
                    TextButton(onClick = memoryPanelOpen) { Text("查看记忆库…") }
                }
                item { SectionTitle("世界书") }
                item {
                    SwitchRow("世界书注入", lorebookEnabled, onLorebookEnabledChange)
                }
                item {
                    TextButton(onClick = onImportLorebook) { Text("导入世界书 JSON…") }
                }
                item {
                    Column {
                        lorebooks.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Text(
                                    text = (if (entry.fileName == lorebookGlobalFile) "★ " else "") +
                                        entry.fileName.removeSuffix(".json") +
                                        " (${entry.book.entries.size} 条)",
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    onLorebookSetGlobal(if (entry.fileName == lorebookGlobalFile) null else entry.fileName)
                                }) { Text(if (entry.fileName == lorebookGlobalFile) "取消全局" else "设为全局", fontSize = 11.sp) }
                                TextButton(onClick = { onLorebookDelete(entry.fileName) }) { Text("删除", fontSize = 11.sp) }
                            }
                        }
                        if (lorebooks.isEmpty()) {
                            Text(
                                "未导入;角色卡内嵌世界书会在导卡时自动提取",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { SectionTitle("对话与上下文") }
                item {
                    SwitchRow("长对话剧情摘要", historySummaryEnabled, onHistorySummaryChange)
                }
                item {
                    Row {
                        TextButton(onClick = onExportHistoryJsonl) { Text("导出 jsonl", fontSize = 12.sp) }
                        TextButton(onClick = onExportHistoryMarkdown) { Text("导出 md", fontSize = 12.sp) }
                        TextButton(onClick = onImportHistoryJsonl) { Text("导入 jsonl", fontSize = 12.sp) }
                    }
                }
                item {
                    Text(
                        "当前上下文 $historyMessageCount 条消息 · 共 ${contexts.size} 个上下文",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Column {
                        contexts.forEach { c ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Text(
                                    text = (if (c.id == activeContextId) "● " else "○ ") +
                                        c.id.take(8) + " · " +
                                        (c.characterId?.take(12) ?: "无卡") + " · " +
                                        c.messageCount + " 条",
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f).clickable { onSelectContext(c.id) },
                                )
                                TextButton(onClick = { onDeleteContext(c.id) }) { Text("删", fontSize = 11.sp) }
                            }
                        }
                        TextButton(onClick = onNewContext) { Text("＋ 新建上下文(重新开始)", fontSize = 12.sp) }
                    }
                }
                item { SectionTitle("画质氛围") }
                item {
                    DropdownRow(
                        label = "灯光氛围",
                        options = ambienceNames,
                        selected = selectedAmbience,
                        onSelect = onAmbienceChange,
                    )
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(text = label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (isPassword && !visible) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            trailingIcon = if (isPassword) {
                {
                    TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示", fontSize = 11.sp) }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DropdownRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor().padding(vertical = 2.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, labelText) ->
                DropdownMenuItem(
                    text = { Text(labelText, fontSize = 13.sp) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

/** 音色选项:Edge 免费目录 + 服务商自带音色。 */
private fun voiceOptions(prefs: AiChatPrefs): List<Pair<String, String>> {
    val options = mutableListOf<Pair<String, String>>()
    com.neethu.orchestrator.facade.EdgeTtsCatalog.voices.forEach { (id, label) ->
        options += id to "Edge·$label"
    }
    prefs.provider.sdk.voices.forEach { v -> options += v to "${prefs.provider.label}·$v" }
    return options.distinctBy { it.first }
}
