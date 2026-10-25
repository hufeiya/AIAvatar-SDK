package com.neethu.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.companion.CardBinding
import com.neethu.companion.PresetCard
import com.neethu.orchestrator.card.CharacterCardStore

/**
 * 面板族（拷自 demo MainActivity 的 CardsPanel/ListPanel,中文文案内联）：
 * 卡库（P2-2 卡面头像 + P2-3 绑定行）、模型/场景列表。
 */

@Composable
internal fun CardsPanel(
    cards: List<CharacterCardStore.Entry>,
    activeFile: String?,
    presets: List<PresetCard>,
    presetNameFor: (PresetCard) -> String,
    activePresetAsset: String?,
    bytesFor: (String) -> ByteArray?,
    bindingFor: (String?) -> CardBinding?,
    voiceLabelFor: (String) -> String,
    onSelectPreset: (PresetCard) -> Unit,
    onClearCard: () -> Unit,
    onImport: () -> Unit,
    onActivate: (CharacterCardStore.Entry) -> Unit,
    onDelete: (CharacterCardStore.Entry) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "预置角色",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 300.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "preset_none") {
                    EmptyCardCell(selected = activeFile == null, onClick = onClearCard)
                }
                items(presets, key = { it.assetPath }) { preset ->
                    PresetCardCell(
                        preset = preset,
                        displayName = presetNameFor(preset),
                        selected = preset.assetPath == activePresetAsset,
                        onClick = { onSelectPreset(preset) },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
            ) {
                Text(text = "我的卡片", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onImport) { Text(text = "导入 PNG/JSON 卡", fontSize = 13.sp) }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(cards, key = { it.fileName }) { entry ->
                    val isActive = entry.fileName == activeFile
                    val bgColor by animateColorAsState(
                        targetValue = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        label = "cardBg"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .clickable { onActivate(entry) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rememberCardAvatar(entry.fileName, bytesFor)?.let { avatar ->
                            Image(
                                bitmap = avatar,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.card.name.ifEmpty { "未命名" },
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val meta = buildString {
                                append(
                                    entry.card.characterVersion
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { "${entry.card.spec} · $it" }
                                        ?: entry.card.spec,
                                )
                                if (entry.card.creator.isNotBlank()) append(" · 作者 ${entry.card.creator}")
                                val tags = entry.card.tags.take(3)
                                if (tags.isNotEmpty()) append(" · ${tags.joinToString("·")}")
                            }
                            Text(
                                text = meta,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isActive) {
                                bindingFor(entry.fileName)?.let { b ->
                                    val voice = b.voice?.let(voiceLabelFor)
                                    val model = b.modelFile?.substringAfterLast('/')
                                    val greeting = b.greetingIndex?.let { if (it == 0) "默认" else "#$it" }
                                    val scene = b.sceneFile?.substringAfterLast('/')
                                    val parts = listOfNotNull(
                                        voice?.let { "音色 $it" },
                                        model?.let { "模型 $it" },
                                        greeting,
                                        scene?.let { "场景 $it" },
                                    )
                                    if (parts.isNotEmpty()) {
                                        Text(
                                            text = parts.joinToString(" · "),
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (isActive) {
                            Text(
                                text = "使用中",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "删除卡片",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (cards.isEmpty()) {
                    item {
                        Text(
                            text = "还没有导入卡片——从类脑/Chub 下载 PNG 卡,或点上方「导入」",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberCardAvatar(fileName: String, bytesFor: (String) -> ByteArray?): ImageBitmap? {
    return remember(fileName) {
        runCatching {
            val bytes = bytesFor(fileName) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 && bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 96) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
internal fun rememberPresetCardBitmap(assetPath: String): ImageBitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(assetPath) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
internal fun PresetCardCell(preset: PresetCard, displayName: String, selected: Boolean, onClick: () -> Unit) {
    val bitmap = rememberPresetCardBitmap(preset.assetPath)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = displayName,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
internal fun EmptyCardCell(selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.Person, contentDescription = "不用角色卡", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text = "无角色", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun ListPanel(
    title: String,
    entries: List<String>,
    selectedItem: String?,
    displayName: (String) -> String = { it },
    onItemClick: (String) -> Unit,
    onItemLongClick: ((String) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            header?.invoke()

            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                entries.indexOf(selectedItem)
                    .takeIf { it >= 0 }
                    ?.let { listState.scrollToItem(it) }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(entries) { fileName ->
                    val isSelected = fileName == selectedItem
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        label = "itemBg"
                    )
                    val clickModifier = if (onItemLongClick != null) {
                        @OptIn(ExperimentalFoundationApi::class)
                        Modifier.combinedClickable(
                            onClick = { onItemClick(fileName) },
                            onLongClick = { onItemLongClick(fileName) },
                        )
                    } else {
                        Modifier.clickable { onItemClick(fileName) }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .then(clickModifier)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName(fileName),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
