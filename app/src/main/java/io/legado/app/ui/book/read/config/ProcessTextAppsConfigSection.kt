package io.legado.app.ui.book.read.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.book.read.ProcessTextAppsManager
import io.legado.app.ui.widget.compose.AppDialogStyle

/**
 * 「其他应用」（ACTION_PROCESS_TEXT）配置区 —— 模块化、自包含。
 *
 * 本文件与 ProcessTextAppsManager.kt 一起构成该功能的全部实现，可整体拷贝到任何
 * legado 分支使用。唯一的接入点是调用方（ContentSelectMenuConfigDialog）里的几行，
 * 详见 MODIFICATIONS-README.md §6「上游更新时的迁移方法」。
 */
@Composable
fun ProcessTextAppsConfigSection(
    hiddenAppKeys: Set<String>,
    style: AppDialogStyle,
    onAppHiddenToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportedApps = remember {
        ProcessTextAppsManager.querySupportedApps(context)
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.content_select_other_apps),
                color = style.primaryText,
                fontSize = 15.sp
            )
            Text(
                text = stringResource(
                    R.string.content_select_other_apps_selected,
                    supportedApps.count { it.key !in hiddenAppKeys },
                    supportedApps.size
                ),
                color = style.secondaryText,
                fontSize = 12.sp
            )
        }
        Text(
            text = if (expanded) "▾" else "▸",
            color = style.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (supportedApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.content_select_other_apps_empty),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                supportedApps.forEach { app ->
                    ProcessTextAppCheckRow(
                        title = app.label,
                        checked = app.key !in hiddenAppKeys,
                        style = style,
                        onClick = { onAppHiddenToggle(app.key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessTextAppCheckRow(
    title: String,
    checked: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = style.primaryText,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = style.accent,
                uncheckedColor = style.secondaryText,
                checkmarkColor = style.surface
            )
        )
    }
}
