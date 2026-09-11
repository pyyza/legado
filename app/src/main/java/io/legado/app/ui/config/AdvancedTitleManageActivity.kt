package io.legado.app.ui.config

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.RenderMode
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitleFontAssetDelegate
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.config.AdvancedTitleConfigDialog
import io.legado.app.ui.book.read.page.LottieImageBitmapCache
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.postEvent
import io.legado.app.utils.readBytes
import io.legado.app.utils.readBytesLimited
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 高级标题（Lottie 章节标题）管理页。
 *
 * 功能等价于 Archive 的 `AdvancedTitleManageActivity`：多条目管理、静态 Lottie 预览、
 * 应用 / 编辑 / 导入（文件或网络）/ 导出 / 删除。
 *
 * 与 Archive 的差异：Archive 用 Compose + miuix 组件实现列表，Max 没有这套组件库，
 * 因此这里按 Max 自身风格（`BubbleManageActivity` 的 View + RecyclerView 范式）重写，
 * 只保留功能与数据层的一致性，不引入新的 UI 依赖。
 */
class AdvancedTitleManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    AdvancedTitleConfigDialog.Host {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    private val importFromNet by lazy { getString(R.string.advanced_title_import_from_net) }
    private var loadJob: Job? = null

    private val importJson = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        if (uri.path == "/$importFromNet") {
            showNetworkImportDialog()
        } else {
            importUri(uri)
        }
    }

    private val exportJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let {
            toastOnUi(R.string.advanced_title_exported)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        loadEntries()
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.advanced_title_manage)
        tabContainer.visibility = View.GONE
        tvSummary.text = getString(R.string.advanced_title_manage_summary)
        tvSummary.setTextColor(themeSecondaryTextColor())
        recyclerView.layoutManager = LinearLayoutManager(this@AdvancedTitleManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        tvAddTheme.text = getString(R.string.import_str)
        tvAddTheme.setTextColor(themePrimaryTextColor())
        tvAddTheme.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@AdvancedTitleManageActivity, R.color.background_card_surface),
            ContextCompat.getColor(this@AdvancedTitleManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@AdvancedTitleManageActivity)
        )
        tvAddTheme.setOnClickListener { showImportPicker() }
        tvAddTheme.applyNavigationBarMargin(withInitialMargin = true)
        root.applyUiBodyTypefaceDeep(uiTypeface())
    }

    private fun loadEntries() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.rotateLoading.visibility = View.VISIBLE
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.loadEntries() }
            }.onSuccess {
                adapter.items = it
                binding.tvMsg.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                binding.tvMsg.text = getString(R.string.advanced_title_manage_summary)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            binding.rotateLoading.visibility = View.GONE
        }
    }

    private fun showImportPicker() {
        selector(getString(R.string.import_str), listOf(getString(R.string.advanced_title_import_title))) { _, _ ->
            importJson.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.advanced_title_import_title)
                allowExtensions = arrayOf("json")
                otherActions = arrayListOf(SelectItem(importFromNet, -1))
            }
        }
    }

    private fun importUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    uri.readBytes(
                        this@AdvancedTitleManageActivity,
                        AdvancedTitlePackageManager.MAX_JSON_BYTES
                    )
                }
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    private fun showNetworkImportDialog() {
        val editText = EditText(this).apply {
            hint = "https://..."
            setText("")
            isSingleLine = true
        }
        alert(titleResource = R.string.advanced_title_input_url) {
            customView { editText }
            okButton {
                editText.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::importNetwork)
            }
            cancelButton()
        }
    }

    private fun importNetwork(url: String) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { body ->
                        val declared = body.contentLength()
                        require(
                            declared <= AdvancedTitlePackageManager.MAX_JSON_BYTES || declared < 0L
                        ) {
                            getString(R.string.advanced_title_too_large)
                        }
                        body.byteStream()
                            .readBytesLimited(AdvancedTitlePackageManager.MAX_JSON_BYTES)
                    }
                }
                val name = Uri.parse(url).lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure {
                toastOnUi(
                    getString(
                        R.string.advanced_title_import_net_failed,
                        it.localizedMessage.orEmpty()
                    )
                )
            }
        }
    }

    private fun showActions(entry: AdvancedTitlePackageManager.Entry) {
        val actions = buildList {
            add(Action.APPLY)
            if (!entry.isBuiltin) {
                add(Action.EDIT)
            }
            add(Action.EXPORT)
            if (!entry.isBuiltin) {
                add(Action.DELETE)
            }
        }
        selector(entry.name, actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                Action.APPLY -> applyEntry(entry)
                Action.EDIT -> editEntry(entry)
                Action.EXPORT -> exportEntry(entry)
                Action.DELETE -> confirmDelete(entry)
            }
        }
    }

    private fun editEntry(entry: AdvancedTitlePackageManager.Entry) {
        if (entry.isBuiltin) return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.readTemplate(entry) }
            }.onSuccess { json ->
                if (supportFragmentManager.isStateSaved ||
                    supportFragmentManager.findFragmentByTag(TAG_EDIT) != null
                ) return@onSuccess
                AdvancedTitleConfigDialog.edit(
                    entryId = entry.id,
                    name = entry.name,
                    json = json,
                    splitRule = entry.config.splitRuleOrNull() ?: AdvancedTitleConfig.globalRule,
                    heightFactor = entry.config.normalizedHeightFactorOrNull()
                        ?: AdvancedTitleConfig.heightFactor
                ).show(supportFragmentManager, TAG_EDIT)
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    override fun onAdvancedTitleSaved(
        entryId: String,
        name: String,
        json: String,
        splitRule: AdvancedTitleConfig.SplitRule,
        heightFactor: Int
    ) {
        val entry = adapter.items.firstOrNull { it.id == entryId }
        if (entry == null || entry.isBuiltin) {
            toastOnUi(R.string.error)
            loadEntries()
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val updated = AdvancedTitlePackageManager.addOrUpdate(
                        name = name,
                        json = json,
                        oldEntry = entry,
                        splitRule = splitRule,
                        heightFactor = heightFactor
                    )
                    val active = AdvancedTitlePackageManager.activeId() == updated.id
                    if (active) AdvancedTitlePackageManager.apply(updated)
                    active
                }
            }.onSuccess { active ->
                if (active) notifyReader()
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    private fun exportEntry(entry: AdvancedTitlePackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.readTemplate(entry) }
            }.onSuccess { json ->
                val safeName = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "advancedTitle" }
                exportJson.launch {
                    mode = HandleFileContract.EXPORT
                    title = getString(R.string.export_str)
                    fileData = HandleFileContract.FileData(
                        "$safeName.json",
                        json.toByteArray(Charsets.UTF_8),
                        "application/json"
                    )
                }
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    private fun confirmDelete(entry: AdvancedTitlePackageManager.Entry) {
        alert(R.string.delete) {
            setMessage(getString(R.string.sure_del))
            okButton {
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { AdvancedTitlePackageManager.delete(entry) }
                    }.onSuccess {
                        notifyReader()
                        loadEntries()
                    }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
                }
            }
            cancelButton()
        }
    }

    private fun applyEntry(entry: AdvancedTitlePackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.apply(entry) }
            }.onSuccess {
                ReadBookConfig.titleMode = AdvancedTitleConfig.TITLE_MODE_ADVANCED
                adapter.refreshActiveId()
                notifyReader()
                toastOnUi(R.string.success)
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    private fun notifyReader() {
        LottieImageBitmapCache.clear()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
    }

    private fun themePrimaryTextColor(): Int =
        ContextCompat.getColor(this, R.color.primaryText)

    private fun themeSecondaryTextColor(): Int =
        ContextCompat.getColor(this, R.color.secondaryText)

    private enum class Action(val titleRes: Int) {
        APPLY(R.string.advanced_title_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.export_str),
        DELETE(R.string.delete)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        private var activeId: String = AdvancedTitlePackageManager.activeId()

        var items: List<AdvancedTitlePackageManager.Entry> = emptyList()
            set(value) {
                field = value
                activeId = AdvancedTitlePackageManager.activeId()
                notifyDataSetChanged()
            }

        /** 重新读取当前应用条目并整体刷新（供「应用」成功后立即更新按钮状态） */
        fun refreshActiveId() {
            activeId = AdvancedTitlePackageManager.activeId()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(createItemView(parent))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun onViewRecycled(holder: Holder) {
            holder.cancelPreview()
            super.onViewRecycled(holder)
        }

        private fun createItemView(parent: ViewGroup): LinearLayout {
            return LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                minimumHeight = ITEM_MIN_HEIGHT_DP.dp
                background = UiCorner.surfaceRounded(
                    this@AdvancedTitleManageActivity,
                    ContextCompat.getColor(parent.context, R.color.background_card_surface),
                    UiCorner.panelRadius(this@AdvancedTitleManageActivity)
                )
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 10.dp
                }
            }
        }

        inner class Holder(private val itemRoot: LinearLayout) : RecyclerView.ViewHolder(itemRoot) {
            private var previewJob: Job? = null
            private val preview = LottieAnimationView(itemRoot.context).apply {
                layoutParams = LinearLayout.LayoutParams(PREVIEW_WIDTH_DP.dp, PREVIEW_HEIGHT_DP.dp)
                setBackgroundColor(Color.TRANSPARENT)
                setCacheComposition(false)
                setFontAssetDelegate(AdvancedTitleFontAssetDelegate())
                repeatCount = 0
                renderMode = RenderMode.SOFTWARE
            }
            private val textBox = LinearLayout(itemRoot.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply {
                        leftMargin = 12.dp
                        rightMargin = 12.dp
                    }
            }
            private val title = TextView(itemRoot.context).apply {
                applyUiSectionTitleStyle(this@AdvancedTitleManageActivity)
                setTextColor(themePrimaryTextColor())
            }
            private val info = TextView(itemRoot.context).apply {
                applyUiLabelStyle(this@AdvancedTitleManageActivity)
                setTextColor(themeSecondaryTextColor())
            }
            private val buttonRow = LinearLayout(itemRoot.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp }
            }
            private val tvApply = createActionButton()
            private val tvEdit = createActionButton()
            private val tvMore = createActionButton()

            init {
                textBox.addView(title)
                textBox.addView(info)
                textBox.addView(buttonRow)
                buttonRow.addView(tvApply)
                buttonRow.addView(android.widget.Space(itemRoot.context).apply {
                    layoutParams = LinearLayout.LayoutParams(8.dp, 0)
                })
                buttonRow.addView(tvEdit)
                buttonRow.addView(android.widget.Space(itemRoot.context).apply {
                    layoutParams = LinearLayout.LayoutParams(8.dp, 0)
                })
                buttonRow.addView(tvMore)
                itemRoot.addView(preview)
                itemRoot.addView(textBox)
            }

            private fun createActionButton(): TextView {
                return TextView(itemRoot.context).apply {
                    gravity = android.view.Gravity.CENTER
                    minWidth = 56.dp
                    minHeight = 34.dp
                    setPadding(12.dp, 0, 12.dp, 0)
                    textSize = 13f
                    setTextColor(themePrimaryTextColor())
                    typeface = this@AdvancedTitleManageActivity.uiTypeface()
                    background = ContextCompat.getDrawable(itemRoot.context, R.drawable.bg_action_button)
                    isClickable = true
                    isFocusable = true
                }
            }

            fun bind(entry: AdvancedTitlePackageManager.Entry) {
                val active = activeId == entry.id
                title.text = entry.name
                title.setTextColor(themePrimaryTextColor())
                info.text = buildString {
                    if (active) append("${getString(R.string.applied)} · ")
                    append(
                        getString(
                            if (entry.isBuiltin) R.string.advanced_title_source_builtin
                            else R.string.advanced_title_source_local
                        )
                    )
                    append(" · ")
                    append(
                        if (entry.updatedAt > 0L) dateFormat.format(Date(entry.updatedAt))
                        else getString(R.string.advanced_title_source_builtin)
                    )
                }
                info.setTextColor(themeSecondaryTextColor())

                val previewKey = "${entry.id}#${entry.updatedAt}"
                preview.setTag(R.id.advanced_title_preview_key, previewKey)
                preview.cancelAnimation()
                preview.progress = 0f
                previewJob?.cancel()
                previewJob = lifecycleScope.launch {
                    val json = withContext(Dispatchers.IO) {
                        runCatching { AdvancedTitlePackageManager.readTemplate(entry) }.getOrNull()
                    }
                    if (preview.getTag(R.id.advanced_title_preview_key) != previewKey) return@launch
                    if (json.isNullOrBlank()) return@launch
                    runCatching {
                        preview.setAnimationFromJson(json, previewKey)
                        preview.progress = 0.5f
                        preview.pauseAnimation()
                    }
                }

                tvApply.text = getString(
                    if (active) R.string.advanced_title_applied else R.string.advanced_title_apply
                )
                tvApply.setTextColor(if (active) accentColor else themePrimaryTextColor())
                tvApply.isEnabled = !active
                tvApply.alpha = if (active) 0.6f else 1f
                tvApply.setOnClickListener { applyEntry(entry) }
                tvEdit.text = getString(R.string.edit)
                tvEdit.visibility = if (entry.isBuiltin) View.GONE else View.VISIBLE
                tvEdit.setOnClickListener { editEntry(entry) }
                tvMore.text = getString(R.string.more)
                tvMore.setOnClickListener { showActions(entry) }
                itemRoot.setOnClickListener { showActions(entry) }
            }

            fun cancelPreview() {
                previewJob?.cancel()
                previewJob = null
                preview.setTag(R.id.advanced_title_preview_key, null)
                preview.cancelAnimation()
            }
        }
    }

    private companion object {
        private const val TAG_EDIT = "advancedTitleEdit"
        private const val PREVIEW_WIDTH_DP = 104
        private const val PREVIEW_HEIGHT_DP = 68
        private const val ITEM_MIN_HEIGHT_DP = 96
    }
}
