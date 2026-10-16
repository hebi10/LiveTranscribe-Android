package com.charles.livecaptionn.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale

data class SplitAppChoice(val packageName: String, val activityName: String, val label: String)

internal fun loadSplitAppChoices(context: Context): List<SplitAppChoice> {
    val manager = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.KOREAN)
    return manager.queryIntentActivities(query, 0).mapNotNull { result ->
        val activity = result.activityInfo ?: return@mapNotNull null
        if (activity.packageName == context.packageName || !activity.exported || !activity.enabled)
            return@mapNotNull null
        SplitAppChoice(activity.packageName, activity.name, result.loadLabel(manager).toString())
    }.distinctBy { it.packageName to it.activityName }
        .sortedWith { first, second -> collator.compare(first.label, second.label) }
}

@Composable
fun SplitAppPicker(
    apps: List<SplitAppChoice>,
    loading: Boolean,
    error: String?,
    onSelect: (SplitAppChoice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("함께 볼 앱 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("영상 앱을 옆 창으로 엽니다. 앱과 기기에 따라 직접 화면을 나눠야 할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall)
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error)
                    apps.isEmpty() -> Text("실행할 앱이 없습니다. 최근 앱에서 분할 화면을 열어 주세요.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(apps, key = { it.packageName + "/" + it.activityName }) { app ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clickable(role = Role.Button) { onSelect(app) }
                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app.label, Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
fun SplitScreenHelp(message: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("분할 화면 안내") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let { item { Text(it) } }
                item { Text("1. Android의 최근 앱 화면을 엽니다.") }
                item { Text("2. 이 앱의 아이콘이나 메뉴에서 ‘분할 화면’을 선택합니다.") }
                item { Text("3. 함께 볼 영상 앱을 선택하고, 가운데 구분선으로 공간을 조절합니다.") }
                item { Text("메뉴 이름과 배치는 기기마다 다릅니다. 앱이 분할 화면을 지원하지 않으면 팝업 방식을 선택해 주세요.",
                    style = MaterialTheme.typography.bodySmall) }
                item { Text("번역은 음성 캡처를 허용하는 앱에서 동작합니다. 함께 볼 앱 선택은 음성 입력을 해당 앱으로 제한하는 기능은 아닙니다.",
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } }
    )
}
