package io.github.prince_dulb.ohmynotification.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingScreen(
    state: AppUiState,
    onAccess: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onAppSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    BackHandler(onBack = onFinish)
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("让通知有地方可找", style = MaterialTheme.typography.headlineMedium)
            Text("以下设置均可跳过，之后可在设置中重新查看。")
            TextButton(onClick = onFinish) { Text("跳过引导") }
        }
        item {
            GuideStep("1. 通知使用权", if (state.listenerAccessGranted) "已授权" else "尚未授权 · 核心必需",
                "允许系统把通知交给 OMN。跳过后将无法记录通知。", "打开通知使用权设置", onAccess)
        }
        item {
            GuideStep("2. 常驻状态通知", if (state.statusNotificationGranted && state.statusNotificationChannelEnabled) "已开启" else "尚未开启 · 推荐",
                "用于在通知栏显示当前记录状态；拒绝不会代替系统撤销通知使用权。", "设置状态通知", onNotifications)
        }
        item {
            GuideStep("3. 电池限制", if (state.batteryUnrestricted) "系统报告：已忽略电池优化" else "尚未忽略电池优化",
                "在系统列表中找到 OMN，选择不优化或不受限制。厂商额外的后台限制仍需手动确认。", "打开电池优化设置", onBattery)
        }
        item {
            GuideStep("4. 厂商后台设置", "需要手动确认", manufacturerGuidance(Build.MANUFACTURER, Build.BRAND),
                "打开应用信息", onAppSettings)
        }
        item {
            Text("后台设置可以减少中断，但无法保证进程永远存在。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("完成，进入通知记录") }
        }
    }
}

@Composable
private fun GuideStep(title: String, status: String, description: String, action: String, onClick: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(status, color = MaterialTheme.colorScheme.primary)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

internal fun manufacturerGuidance(manufacturer: String, brand: String): String {
    val identity = "$manufacturer $brand".lowercase(java.util.Locale.ROOT)
    return when {
        listOf("vivo", "iqoo").any(identity::contains) ->
            "vivo / iQOO：在系统设置或 i 管家中允许 OMN 自启动和后台高耗电；在最近任务中锁定 OMN。不同系统版本的入口名称可能不同，这些选项需你手动确认。"
        listOf("nubia", "redmagic", "zte", "红魔").any(identity::contains) ->
            "红魔：在应用或电池管理中允许 OMN 自启动、后台运行，放宽后台耗电限制；在最近任务中锁定 OMN。不同系统版本的入口名称可能不同，这些选项需你手动确认。"
        else -> "在系统的应用、电池或安全管理中找到 OMN，允许自启动和后台运行，并在最近任务中锁定。具体入口由手机厂商决定，OMN 无法自动读取这些设置。"
    }
}
