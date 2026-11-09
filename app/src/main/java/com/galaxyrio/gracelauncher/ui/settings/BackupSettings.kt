package com.galaxyrio.gracelauncher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.galaxyrio.gracelauncher.R
import com.galaxyrio.gracelauncher.backup.GraceBackup
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复页面。
 *
 * 交互设计取舍：
 * - 导出用 CreateDocument，由用户自己挑位置和文件名，默认名字带时间戳，
 *   不写死在应用私有目录里，方便用户拷到电脑或云盘。
 * - 恢复用 OpenDocument，只接受 zip 系扩展名。恢复前弹确认框，
 *   因为这一步会覆盖当前全部设置，不能点一下就执行。
 * - 结果用对话框显示，不用 Toast。Toast 一闪而过，用户看不全路径和条目数。
 */
@Composable
internal fun BackupSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreDone by remember { mutableStateOf(false) }

    /** 备份文件名：Grace-备份-年月日-时分.glb，一眼能看出是什么时候的。 */
    val suggestedName = remember {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        "Grace-$stamp.${GraceBackup.EXTENSION}"
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GraceBackup.MIME)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { GraceBackup.export(context, uri) }
            busy = false
            message = result.message
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestore = uri
    }

    SettingsScaffold(stringResource(R.string.backup_title), "settings_backup", onBack) { padding ->
        SettingsList(padding) {
            item { SettingsHeading(stringResource(R.string.backup_section_export)) }
            item {
                SettingsActionItem(
                    title = stringResource(R.string.backup_export),
                    summary = stringResource(R.string.backup_export_summary),
                    index = 0, count = 1, tag = "backup_export",
                    enabled = !busy,
                    leading = { LauncherIcon(LauncherSymbol.Encrypted) },
                ) { exporter.launch(suggestedName) }
            }

            item { SettingsHeading(stringResource(R.string.backup_section_restore)) }
            item {
                SettingsActionItem(
                    title = stringResource(R.string.backup_restore),
                    summary = stringResource(R.string.backup_restore_summary),
                    index = 0, count = 1, tag = "backup_restore",
                    enabled = !busy,
                    leading = { LauncherIcon(LauncherSymbol.History2) },
                ) { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
                    Text(
                        stringResource(R.string.backup_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 恢复前确认：会覆盖当前全部设置，必须让用户明确点一次
    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { GraceBackup.restore(context, uri) }
                        busy = false
                        restoreDone = result.ok
                        message = result.message
                    }
                }) { Text(stringResource(R.string.backup_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.backup_result_title)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    message = null
                    // 恢复成功后建议直接重启应用：数据库文件已换，进程里的旧状态不可信
                    if (restoreDone) {
                        restoreDone = false
                        val intent = context.packageManager
                            .getLaunchIntentForPackage(context.packageName)
                        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    }
                }) {
                    Text(stringResource(
                        if (restoreDone) R.string.backup_restart_now else R.string.close
                    ))
                }
            },
        )
    }
}