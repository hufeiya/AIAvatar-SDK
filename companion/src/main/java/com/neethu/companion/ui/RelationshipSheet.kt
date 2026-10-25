package com.neethu.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.orchestrator.memory.RelationshipLevel
import com.neethu.orchestrator.memory.RelationshipSnapshot
import com.neethu.orchestrator.memory.levelLabelOf

/**
 * 关系状态详情（G11）：低调呈现——阶段词 + 一根细进度线（不出数值大字）+
 * 相识天数 + 称呼 + 纪念日；「重置关系」入口在此（§8.3 数据主权）。
 */
@Composable
internal fun RelationshipSheet(
    snapshot: RelationshipSnapshot?,
    cardName: String?,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = onReset) { Text("重置关系", color = MaterialTheme.colorScheme.error) }
        },
        title = { Text(text = "与${cardName ?: "TA"}的关系", fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snapshot == null) {
                    Text(
                        text = "还没有关系数据——聊几句（或激活一张卡）之后,关系会随对话慢慢生长。",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val level = snapshot.level
                    Text(
                        text = levelLabelOf(level),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    // 细进度线:阶段在 0..100 的位置,不标数字
                    val progress = when (level) {
                        RelationshipLevel.STRANGER -> 0.08f
                        RelationshipLevel.ACQUAINTANCE -> 0.28f
                        RelationshipLevel.CLOSE -> 0.55f
                        RelationshipLevel.INTIMATE -> 0.85f
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFF8FA3),
                    )
                    Text(text = "相识 ${snapshot.daysTogether} 天", fontSize = 13.sp)
                    snapshot.record.nickname?.let {
                        Text(text = "TA 称呼你「$it」", fontSize = 13.sp)
                    }
                    if (snapshot.isAnniversaryToday) {
                        Text(
                            text = "今天是我们相识满 ${snapshot.daysTogether / 365} 周年 🎂",
                            fontSize = 13.sp,
                            color = Color(0xFFE91E63),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "关系由你们的对话自然影响,不设数值游戏;可随时重置(数据只存在本机)。",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
