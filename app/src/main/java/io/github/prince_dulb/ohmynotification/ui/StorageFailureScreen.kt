package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun StorageFailureScreen(onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("暂时无法读取本地记录", style = MaterialTheme.typography.headlineSmall)
            Text("没有清空或重建数据库。为了避免在排除设置未加载时误记通知，记录功能已暂停。请检查剩余存储空间，再重试；仍失败时请反馈问题，不要清除应用数据。")
            Button(onClick = onRetry) { Text("重新读取") }
        }
    }
}
