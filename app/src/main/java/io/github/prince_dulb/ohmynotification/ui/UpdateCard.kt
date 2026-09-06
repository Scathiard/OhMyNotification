package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.prince_dulb.ohmynotification.update.AppUpdater
import io.github.prince_dulb.ohmynotification.update.UpdatePhase
import kotlinx.coroutines.launch

@Composable
internal fun UpdateCard(updater: AppUpdater, onInstall: () -> Unit, onBrowser: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { action() } finally { busy = false } }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用更新 · ${updater.currentVersion}", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { run { updater.check() } }, enabled = !busy) { Text("检查更新") }
            when (state.phase) {
                UpdatePhase.AVAILABLE -> TextButton(onClick = { run { updater.download() } }, enabled = !busy) { Text("下载更新（可能使用移动数据）") }
                UpdatePhase.DOWNLOADING -> TextButton(onClick = { run { updater.refresh() } }, enabled = !busy) { Text("刷新下载状态") }
                UpdatePhase.READY -> TextButton(onClick = onInstall, enabled = !busy) { Text("验证并安装") }
                UpdatePhase.ERROR -> TextButton(onClick = { run { updater.download() } }, enabled = !busy) { Text("重试下载") }
                else -> Unit
            }
            TextButton(onClick = onBrowser) { Text("浏览器打开 Releases") }
        }
    }
}
