package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity
import io.github.prince_dulb.ohmynotification.data.NotificationRepository

@Composable
internal fun NotificationDetailsDialog(item: NotificationItemEntity, repository: NotificationRepository, onClose: () -> Unit) {
    val current by remember(item.itemId) { repository.item(item.itemId) }.collectAsStateWithLifecycle(item)
    val history = remember(item.itemId) { repository.pagedRevisions(item.itemId) }.collectAsLazyPagingItems()
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("更新历史", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "current") {
                        val visible = current
                        if (visible == null) Text("这条记录已不存在") else {
                        Text("当前版本 · 长按文字可复制", style = MaterialTheme.typography.labelLarge)
                        FullNotificationText(visible.title, visible.body)
                        if (visible.identityUncertain) Text("此通知出现在监听中断之后，身份存在歧义；已单独保留，没有覆盖旧条目。", style = MaterialTheme.typography.bodySmall)
                        if (visible.normalizationWarnings.contains("COPY_FAILED") || visible.normalizationWarnings.contains("TRUNCATED") || visible.normalizationWarnings.contains("SIZE_LIMIT")) {
                            Text("来源字段读取或长度有限制，保存的内容可能不完整。", style = MaterialTheme.typography.bodySmall)
                        }
                        }
                    }
                    if (history.itemCount > 0) item(key = "history-header") {
                        HorizontalDivider()
                        Text("更新前的内容", style = MaterialTheme.typography.titleMedium)
                    }
                    items(history.itemCount, key = history.itemKey { it.revisionId }) { index ->
                        history[index]?.let { revision ->
                            Text(java.time.Instant.ofEpochMilli(revision.recordedAtEpochMillis)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                                style = MaterialTheme.typography.labelMedium)
                            FullNotificationText(revision.title, revision.body)
                            HorizontalDivider()
                        }
                    }
                    if (history.loadState.hasError) item(key = "error") {
                        TextButton(onClick = history::retry) { Text("历史内容读取失败，点击重试") }
                    }
                    if (history.loadState.append is LoadState.Loading) item(key = "loading") {
                        Text("正在读取更早内容…")
                    }
                }
            }
        }
    }
}

@Composable
private fun FullNotificationText(title: String?, body: String?) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (title == null && body == null) Text("来源未提供可读取的标准文本")
        }
    }
}
