package com.neethu.aiavatar_sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiavatar_sdk.i18n.LocalStrings
import com.neethu.corelib.Lang
import com.neethu.orchestrator.memory.AgentMemory
import com.neethu.orchestrator.memory.MemoryStore
import com.neethu.orchestrator.memory.memoryTextsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理面板（docs/memory-feasibility.md §4.7）：总数 + 列表（内容/分类徽标/
 * 重要度/来源/时间）+ 搜索 + 单条删除 + 清空（二次确认）。数据直取
 * [MemoryStore]（与门面同一实例，会话未配置也可用）；HyperOS 禁触摸注入的
 * 老问题照惯例靠 ai_cmd memory_list/memory_forget/memory_clear 验收。
 */
@Composable
internal fun MemoryPanel(
    store: MemoryStore,
    lang: Lang,
    onDismiss: () -> Unit,
    version: Int = 0,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val texts = remember(lang) { memoryTextsOf(lang) }
    var all by remember { mutableStateOf<List<AgentMemory>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var pendingClear by remember { mutableStateOf(false) }
    var listVersion by remember { mutableStateOf(0) }

    LaunchedEffect(version, listVersion) {
        all = withContext(Dispatchers.IO) { store.all() }
    }

    val shown = if (query.isBlank()) all else {
        all.filter { it.content.contains(query.trim(), ignoreCase = true) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.memoryPanelTitle,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = s.memoryCount(all.size),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "close")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(s.memorySearchHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        s.memoryEmpty,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it.id }) { memo ->
                        MemoryRow(
                            memo = memo,
                            categoryLabel = texts.categoryLabel(memo.category),
                            dateText = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                                .format(Date(memo.updatedAt)),
                            importanceText = s.memoryImportance(memo.importance),
                            sourceText = s.memorySource(memo.source),
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.delete(memo.id) }
                                    listVersion++
                                }
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = { pendingClear = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(s.memoryClear, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text(s.memoryClearConfirmTitle) },
            text = { Text(s.memoryClearConfirmText) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.clear() }
                        listVersion++
                    }
                }) { Text(s.memoryClearConfirmYes, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text(s.memoryClearCancel) }
            },
        )
    }
}

@Composable
private fun MemoryRow(
    memo: AgentMemory,
    categoryLabel: String,
    dateText: String,
    importanceText: String,
    sourceText: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(memo.content, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    categoryLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    importanceText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    sourceText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dateText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
