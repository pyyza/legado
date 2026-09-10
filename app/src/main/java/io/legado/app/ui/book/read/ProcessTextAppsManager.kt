package io.legado.app.ui.book.read

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefStringSet

/**
 * 长按选择文本菜单中的「其他应用」模块（Android 6.0+ 的 ACTION_PROCESS_TEXT）。
 *
 * 独立成文件的目的：这个模块与上游 legado 的 TextActionMenu.kt 解耦，
 * 上游更新 TextActionMenu.kt 时，只需要保留本文件，并在菜单过滤处调用
 * [visibleApps] 即可，无需合并大段 PROCESS_TEXT 逻辑。
 */
object ProcessTextAppsManager {

    // 进程内缓存：菜单每次重建（长按、宽度变化、默认动作探测）都会调用
    // querySupportedApps；queryIntentActivities + 逐应用 loadLabel 是主线程
    // PackageManager IPC，缓存 30 秒避免重复全量扫包
    private const val SCAN_CACHE_TTL_MS = 30_000L
    private var scanCache: List<ProcessTextApp>? = null
    private var scanCacheAt = 0L

    data class ProcessTextApp(
        val packageName: String,
        val activityName: String,
        val label: String
    ) {
        val key: String get() = "$packageName/$activityName"
    }

    /** 查询系统里所有支持处理文本（ACTION_PROCESS_TEXT）的应用 */
    fun querySupportedApps(context: Context): List<ProcessTextApp> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val now = SystemClock.elapsedRealtime()
        scanCache?.takeIf { now - scanCacheAt < SCAN_CACHE_TTL_MS }?.let { return it }
        val apps = context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                ProcessTextApp(
                    packageName = activityInfo.packageName,
                    activityName = activityInfo.name,
                    label = info.loadLabel(context.packageManager).toString()
                )
            }
            .distinctBy { it.key }
        scanCache = apps
        scanCacheAt = now
        return apps
    }

    /** 应用列表可能变化（安装/卸载）时手动失效缓存 */
    fun clearScanCache() {
        scanCache = null
        scanCacheAt = 0L
    }

    /** 用户手动隐藏（不显示在菜单里）的应用 key 集合 */
    fun hiddenAppKeys(context: Context): Set<String> {
        return context.getPrefStringSet(PreferKey.contentSelectHiddenProcessTextApps, null)
            ?.toSet()
            ?: emptySet()
    }

    fun isHidden(context: Context, app: ProcessTextApp): Boolean =
        app.key in hiddenAppKeys(context)

    fun setHidden(context: Context, app: ProcessTextApp, hidden: Boolean) {
        val current = hiddenAppKeys(context).toMutableSet()
        if (hidden) current.add(app.key) else current.remove(app.key)
        context.putPrefStringSet(PreferKey.contentSelectHiddenProcessTextApps, current)
    }

    /** 过滤掉用户隐藏项后，菜单中实际显示的应用 */
    fun visibleApps(context: Context): List<ProcessTextApp> {
        val hidden = hiddenAppKeys(context)
        return querySupportedApps(context).filterNot { it.key in hidden }
    }

    fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    fun createProcessTextIntentForApp(app: ProcessTextApp): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(app.packageName, app.activityName)
    }
}
