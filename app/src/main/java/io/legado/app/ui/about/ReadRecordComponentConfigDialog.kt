package io.legado.app.ui.about

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.applyModernWindowStyle
import io.legado.app.utils.applyPreferredHighRefreshRate
import io.legado.app.utils.dpToPx
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.min

object ReadRecordComponentConfigDialog {

    fun show(
        context: Context,
        initialItems: List<ReadRecordComponentItem>,
        onSaved: (List<ReadRecordComponentItem>) -> Unit
    ) {
        lateinit var dialog: AlertDialog
        val metrics = context.resources.displayMetrics
        // 列表限高：窗口高度自适应，仍要给底部「取消/确定」留空间，
        // 否则列表过高会把按钮顶出可视区域，导致用户找不到保存入口。
        val fixedListHeight = min(
            320.dpToPx(),
            (metrics.heightPixels * 0.32f).toInt()
        ).coerceAtLeast(180.dpToPx())
        val dialogWidth = (metrics.widthPixels * 0.9f).toInt()
        val composeView = ComposeView(context).apply {
            // 宽度交给窗口（MATCH_PARENT），避免「内容 90% + 窗口自身再按内容量一次」
            // 出现两套宽度，show 之后窗口尺寸跳变
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LegadoComposeTheme {
                    ReadRecordComponentConfigContent(
                        initialItems = initialItems,
                        listHeightDp = fixedListHeight / metrics.density,
                        onCancel = { dialog.dismiss() },
                        onSave = { items ->
                            val normalized = items.map { it.copy() }.toMutableList()
                            if (normalized.none { it.enabled }) {
                                normalized.firstOrNull()?.enabled = true
                            }
                            onSaved(normalized)
                            dialog.dismiss()
                        }
                    )
                }
            }
        }
        dialog = AlertDialog.Builder(context)
            .setView(composeView)
            .create()
        // 窗口尺寸 / 背景 / 刷新率申报全部在 show() 之前设好。
        // 入场动画是 alpha + scale(0.94→1)，缩放圆心按「动画开始时的窗口尺寸」计算；
        // 旧写法在 setOnShowListener 里才 setLayout，动画播放途中窗口尺寸突变，
        // 圆心失效 → 视觉上就是「框自己左右颤动一下」。
        val window = dialog.window
        window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.applyModernWindowStyle()
        // AppDialogFrame 自带圆角面板背景，清掉 AlertDialog 自身窗口背景，避免双层背景。
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 高刷申报同样提前到 show() 前：窗口挂载时一次性带上，
        // 避免入场动画途中再改窗口属性（独立窗口需自行申报，否则 ColorOS 压到 40Hz）
        if (window?.windowManager != null) {
            window.applyPreferredHighRefreshRate()
        } else {
            dialog.setOnShowListener { dialog.window?.applyPreferredHighRefreshRate() }
        }
        dialog.show()
    }
}

@Composable
private fun ReadRecordComponentConfigContent(
    initialItems: List<ReadRecordComponentItem>,
    listHeightDp: Float,
    onCancel: () -> Unit,
    onSave: (List<ReadRecordComponentItem>) -> Unit
) {
    val items = remember(initialItems) {
        mutableStateListOf<ReadRecordComponentItem>().apply {
            addAll(initialItems.map { it.copy() })
        }
    }
    val dialogStyle = rememberAppDialogStyle()
    val palette = dialogStyle.toMiuixPalette()
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        items.add(to.index, items.removeAt(from.index))
    }
    AppDialogFrame(
        title = stringResource(R.string.read_record_customize_components),
        message = stringResource(R.string.read_record_components_hint),
        scrollContent = false,
        content = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeightDp.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.type.name }
                ) { index, item ->
                    ReorderableItem(reorderState, key = item.type.name) {
                        ReadRecordComponentConfigRow(
                            item = item,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            onToggle = { checked ->
                                items[index] = item.copy(enabled = checked)
                            },
                            onMoveUp = {
                                if (index > 0) {
                                    items.move(index, index - 1)
                                }
                            },
                            onMoveDown = {
                                if (index < items.lastIndex) {
                                    items.move(index, index + 1)
                                }
                            },
                            dragHandle = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_drag_handle),
                                    contentDescription = stringResource(R.string.read_record_drag_sort),
                                    tint = palette.secondaryText,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .draggableHandle()
                                )
                            }
                        )
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(android.R.string.cancel),
                palette = palette,
                onClick = onCancel
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixActionButton(
                text = stringResource(android.R.string.ok),
                palette = palette,
                primary = true,
                onClick = { onSave(items) }
            )
        }
    )
}

@Composable
private fun ReadRecordComponentConfigRow(
    item: ReadRecordComponentItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    dragHandle: @Composable () -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!item.enabled) },
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.type.titleRes),
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = titleFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(item.type.hintRes),
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = bodyFont,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            dragHandle()
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixSwitch(
                checked = item.enabled,
                palette = palette,
                onCheckedChange = onToggle
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.move_up),
                palette = palette,
                onClick = onMoveUp,
                minWidth = 60.dp,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.move_down),
                palette = palette,
                onClick = onMoveDown,
                minWidth = 60.dp,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

private fun MutableList<ReadRecordComponentItem>.move(from: Int, to: Int) {
    if (from !in indices || to !in indices || from == to) return
    val item = removeAt(from)
    add(to, item)
}
