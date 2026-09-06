package io.github.prince_dulb.ohmynotification.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.prince_dulb.ohmynotification.data.ArchiveChange

enum class BatchAction(val label: String) {
    ARCHIVE("归档"), RESTORE("取消手动归档"), DELETE("永久删除"),
    ONLY("仅查看所选来源"), HIDE("不查看所选来源"),
    STOP("不再记录所选来源"), START("重新记录所选来源"),
}

data class BatchResult(val count: Int, val undo: ArchiveChange? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionToolbar(
    count: Int,
    archive: Boolean,
    busy: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onAction: (BatchAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column {
            TopAppBar(
                title = { Text(if (busy) "处理中…" else "已选 $count 条") },
                navigationIcon = { TextButton(onClick = onExit, enabled = !busy) { Text("退出") } },
                actions = { TextButton(onClick = onSelectAll, enabled = !busy) { Text("全选") } },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                val primary = if (archive) BatchAction.RESTORE else BatchAction.ARCHIVE
                TextButton(onClick = { onAction(primary) }, enabled = count > 0 && !busy) { Text(primary.label) }
                if (archive) {
                    TextButton(onClick = { onAction(BatchAction.DELETE) }, enabled = count > 0 && !busy) { Text("永久删除") }
                }
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { menu = true }, enabled = count > 0 && !busy) { Text("来源操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(BatchAction.ONLY, BatchAction.HIDE, BatchAction.STOP, BatchAction.START).forEach { action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = { menu = false; onAction(action) })
                        }
                    }
                }
            }
        }
    }
}
