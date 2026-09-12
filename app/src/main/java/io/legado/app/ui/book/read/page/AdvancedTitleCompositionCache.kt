package io.legado.app.ui.book.read.page

import android.util.LruCache
import com.airbnb.lottie.LottieComposition

/**
 * 高级标题 Lottie 合成体（Composition）缓存。
 *
 * 只使用 Lottie 的公开 API 实现：合成体在页面加载完成后写入，
 * 再次回到同一页时可直接同步 `setComposition`，避免标题晚一帧出现。
 *
 * 不使用 Lottie 内部的 `LottieCompositionCache`（标记 @RestrictedApi，仅限库内调用），
 * 避免 lint 报错与对非公开 API 的依赖。
 */
internal object AdvancedTitleCompositionCache {

    /** 阅读时同时可见的标题页数量有限，保留少量即可覆盖来回翻页场景。 */
    private const val MAX_ENTRIES = 6

    private val cache = LruCache<String, LottieComposition>(MAX_ENTRIES)

    fun get(key: String): LottieComposition? = cache.get(key)

    fun put(key: String, composition: LottieComposition) {
        cache.put(key, composition)
    }

    fun clear() {
        cache.evictAll()
    }
}
