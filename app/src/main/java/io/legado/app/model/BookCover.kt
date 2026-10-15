package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.help.CacheManager
import io.legado.app.help.DefaultData
import io.legado.app.help.config.CoverHtmlTemplateConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import androidx.core.graphics.drawable.toDrawable

/**
 * 书籍封面管理类
 * 
 * 负责封面的加载、显示、搜索等功能：
 * - 默认封面管理（支持日间/夜间主题）
 * - 封面图片加载（使用 Glide）
 * - 漫画图片加载
 * - 模糊封面加载
 * - 封面规则配置与自动搜索
 */
/**
 * 书籍封面管理器。
 *
 * 管理封面规则配置、默认封面绘制，以及封面图集的默认封面查找。
 * 封面图集功能允许用户为书籍指定自定义封面图片，
 * 通过 [CoverGalleryRepository] 查询匹配的图集封面。
 */
@Keep
@Suppress("ConstPropertyName")
object BookCover {

    private const val coverRuleConfigKey = "legadoCoverRuleConfig"
    private val coverGalleryRepository by lazy { CoverGalleryRepository() }

    /** 持久化封面最小间隔：30 分钟内同一本书不重复写盘 */
    private const val persistIntervalMillis = 30 * 60 * 1000L

    /** 进行中的持久化任务 key（identity），避免并发放写同一文件 */
    private val persistingKeys: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** 上次持久化完成时间（identity -> epoch millis） */
    private val lastPersistTime = ConcurrentHashMap<String, Long>()

    const val configFileName = "coverRule.json"

    var drawBookName = true
        private set

    var drawBookAuthor = true
        private set

    lateinit var defaultDrawable: Drawable
        private set

    init {
        upDefaultCover()
    }

    /** 从封面图集中获取默认封面路径，未匹配则返回 null */
    fun getGalleryDefaultCover(identity: String? = null, originalCoverPath: String? = null): String? {
        return coverGalleryRepository.getDefaultCoverPath(identity, originalCoverPath)
    }

    /** 获取书籍展示封面：优先封面图集，其次本地持久化封面，回退到原始封面 */
    fun getDisplayCover(book: Book): String? {
        val originalCover = book.getDisplayCover()
        return getGalleryDefaultCover(book.bookUrl, originalCover)
            ?: getStableCover(book.bookUrl, originalCover)
    }

    /** 获取搜索结果书籍的展示封面：优先封面图集，回退到原始封面 */
    fun getDisplayCover(searchBook: SearchBook): String? {
        val identity = buildString {
            append(searchBook.bookUrl)
            append('|')
            append(searchBook.origin)
            append('|')
            append(searchBook.name)
            append('|')
            append(searchBook.author)
        }
        return getGalleryDefaultCover(identity, searchBook.coverUrl) ?: searchBook.coverUrl
    }

    /**
     * 获取离线可用的封面路径：本地持久化封面优先，无持久化时原样返回。
     *
     * 部分书源的封面 URL 带时效签名（token/expire 随刷新轮换），签名一换
     * Glide 磁盘缓存的键就失配，离线时必然加载失败。持久化封面以 bookUrl
     * 为键，不受签名轮换影响，断网后仍可加载。
     *
     * @param identity 书籍身份（bookUrl），用于查找本地持久化封面
     * @param coverUrl 原始封面路径或 URL；非 http(s) 直接原样返回
     */
    fun getStableCover(identity: String?, coverUrl: String?): String? {
        if (coverUrl.isNullOrEmpty() || identity.isNullOrEmpty()) {
            return coverUrl
        }
        // "|" 是搜索/聚合结果的组合身份，非单本书籍，不参与持久化
        if (identity.contains("|") || !coverUrl.startsWith("http")) {
            return coverUrl
        }
        val localPath = getLocalCoverPath(identity)
        return if (File(localPath).isFile) localPath else coverUrl
    }

    /**
     * 异步持久化封面到本地（保存原始图片字节，与显示请求共用 Glide 磁盘缓存）。
     *
     * 通过 Glide downloadOnly 取原始字节（DiskCacheStrategy.DATA，通常命中磁盘缓存，
     * 不产生二次网络请求），复制到 covers 目录，文件名为 identity（bookUrl）的 md5。
     * 仅接受 http(s) 的 identity 与 coverUrl；失败静默（不影响展示）。
     *
     * @param identity 书籍身份（bookUrl）
     * @param coverUrl 已成功加载的封面 URL
     */
    fun persistCover(identity: String?, coverUrl: String?) {
        if (identity.isNullOrBlank() || coverUrl.isNullOrBlank()) return
        // 身份不限 http（本地书 bookUrl 是文件路径，同样需要持久化），封面必须是 http 网络图
        if (!coverUrl.startsWith("http")) return
        // "|" 是搜索/聚合结果的组合身份，非单本书籍，不参与持久化
        if (identity.contains("|")) return
        lastPersistTime[identity]?.let {
            if (System.currentTimeMillis() - it < persistIntervalMillis) return
        }
        if (!persistingKeys.add(identity)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val source = Glide.with(appCtx)
                    .downloadOnly()
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .load(coverUrl)
                    .submit()
                    .get(15, TimeUnit.SECONDS)
                val target = File(getLocalCoverPath(identity))
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                lastPersistTime[identity] = System.currentTimeMillis()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                persistingKeys.remove(identity)
            }
        }
    }

    /** 本地持久化封面路径（covers 目录，与 LocalBook.getCoverPath 同目录同命名规则） */
    private fun getLocalCoverPath(identity: String): String {
        return FileUtils.getPath(
            appCtx.externalFiles,
            "covers",
            "${MD5Utils.md5Encode16(identity)}.jpg"
        )
    }

    /**
     * 更新默认封面
     * 
     * 根据当前主题（日间/夜间）加载对应的默认封面图片，
     * 并更新是否在封面上绘制书名和作者的设置
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    fun upDefaultCover() {
        var path: String?
        val isNightTheme = AppConfig.isNightTheme
        if (isNightTheme) {
            drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowNameN, true)
            drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthorN, true)
            path = getGalleryDefaultCover("default-night", appCtx.getPrefString(PreferKey.defaultCoverDark))
                ?: appCtx.getPrefString(PreferKey.defaultCoverDark)
        } else {
            drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowName, true)
            drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthor, true)
            path = getGalleryDefaultCover("default-day", appCtx.getPrefString(PreferKey.defaultCover))
                ?: appCtx.getPrefString(PreferKey.defaultCover)
        }
        defaultDrawable = runCatching {
            BitmapUtils.decodeBitmap(path!!, 600, 900)!!.toDrawable(appCtx.resources)
        }.getOrDefault(appCtx.resources.getDrawable(R.drawable.image_cover_default, null))
    }

    /**
     * 加载书籍封面
     * 
     * @param context 上下文
     * @param path 封面图片路径或URL
     * @param loadOnlyWifi 是否仅在WiFi下加载
     * @param sourceOrigin 书源来源标识
     * @param onLoadFinish 加载完成回调（成功或失败都会触发）
     * @return Glide请求构建器
     */
    fun load(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        if (AppConfig.useDefaultCover) {
            return ImageLoader.load(context, defaultDrawable)
                .centerCrop()
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        var builder = ImageLoader.load(context, path)
            .apply(options)
        if (onLoadFinish != null) {
            builder = builder.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable?>,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }
            })
        }
        return builder.placeholder(defaultDrawable)
            .error(defaultDrawable)
            .centerCrop()
    }

    /**
     * 加载漫画图片
     * 
     * 与普通封面加载不同，漫画图片：
     * - 宽度适配屏幕，高度保持原始比例
     * - 启用磁盘缓存
     * - 跳过内存缓存以节省空间
     * 
     * @param context 上下文
     * @param path 图片路径或URL
     * @param loadOnlyWifi 是否仅在WiFi下加载
     * @param sourceOrigin 源来源标识
     * @param transformation 图片变换（如缩放、裁剪等）
     * @return Glide请求构建器
     */
    fun loadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        transformation: Transformation<Bitmap>? = null,
    ): RequestBuilder<Drawable> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        // 漫画页可能在 Activity 销毁后的回调中继续触发加载，使用 appCtx 避免 Glide 绑定已销毁的 Activity。
        return ImageLoader.load(appCtx, path)
            .apply(options)
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(true).let {
                if (transformation != null) {
                    it.transform(transformation)
                } else {
                    it
                }
            }
    }

    /**
     * 预加载漫画图片
     * 
     * 将漫画图片下载到磁盘缓存，不进行解码显示，
     * 用于提前加载后续页面以提升阅读体验
     * 
     * @param context 上下文
     * @param path 图片路径或URL
     * @param loadOnlyWifi 是否仅在WiFi下加载
     * @param sourceOrigin 源来源标识
     * @return Glide请求构建器，返回缓存文件
     */
    fun preloadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<File?> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        // 预加载不依赖界面生命周期，避免 Activity 销毁后 RecyclerViewPreloader 继续触发 Glide.with(activity)。
        return Glide.with(appCtx)
            .downloadOnly()
            .apply(options)
            .load(path)
    }

    /**
     * 加载模糊封面
     * 
     * 用于书籍详情页背景等场景，对封面进行高斯模糊处理，
     * 并带有1.5秒的淡入动画效果
     * 
     * @param context 上下文
     * @param path 封面图片路径或URL
     * @param loadOnlyWifi 是否仅在WiFi下加载
     * @param sourceOrigin 源来源标识
     * @return Glide请求构建器
     */
    fun loadBlur(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<Drawable> {
        val loadBlur = ImageLoader.load(context, defaultDrawable)
            .transform(BlurTransformation(25), CenterCrop())
        if (AppConfig.useDefaultCover) {
            return loadBlur
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(context, path)
            .apply(options)
            .transform(BlurTransformation(25), CenterCrop())
            .transition(DrawableTransitionOptions.withCrossFade(1500))
            .thumbnail(loadBlur)
    }

    /**
     * 获取封面规则
     * @return 封面规则，若未配置则返回默认规则
     */
    fun getCoverRule(): CoverRule {
        return getConfig() ?: DefaultData.coverRule
    }

    /**
     * 获取配置
     * @return 封面规则配置，若不存在则返回null
     */
    fun getConfig(): CoverRule? {
        return GSON.fromJsonObject<CoverRule>(CacheManager.get(coverRuleConfigKey))
            .getOrNull()
    }

    /**
     * 搜索封面
     * 
     * 根据配置的封面规则，通过书名在网络搜索并提取封面URL
     * 
     * @param book 书籍信息
     * @return 封面URL，若搜索失败或规则未启用则返回null
     */
    suspend fun searchCover(book: Book): String? {
        val config = getCoverRule()
        if (!config.enable || config.searchUrl.isBlank() || config.coverRule.isBlank()) {
            return null
        }
        val analyzeUrl = AnalyzeUrl(
            config.searchUrl,
            book.name,
            source = config,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val res = analyzeUrl.getStrResponseAwait()
        val analyzeRule = AnalyzeRule(book, config)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setContent(res.body)
        analyzeRule.setRedirectUrl(res.url)
        return analyzeRule.getString(config.coverRule, isUrl = true)
    }

    /**
     * 保存封面规则
     * @param config 封面规则配置对象
     */
    fun saveCoverRule(config: CoverRule) {
        val json = GSON.toJson(config)
        saveCoverRule(json)
    }

    /**
     * 保存封面规则
     * @param json JSON字符串
     */
    fun saveCoverRule(json: String) {
        CacheManager.put(coverRuleConfigKey, json)
    }

    /**
     * 删除封面规则
     */
    fun delCoverRule() {
        CacheManager.delete(coverRuleConfigKey)
    }

    /**
     * 渲染HTML模板
     * 
     * 将模板中的变量替换为实际值
     * 
     * @param template HTML模板
     * @param bookName 书名
     * @param author 作者
     * @return 渲染后的HTML字符串
     */
    fun renderHtmlTemplate(template: String, bookName: String, author: String): String {
        return template
            .replace("{{bookName}}", bookName)
            .replace("{{author}}", author)
    }

    /**
     * 封面规则配置类
     * 
     * 用于定义自动搜索封面的规则，实现 BaseSource 接口
     * 以复用书源的规则解析能力
     * 
     * @property enable 是否启用此规则
     * @property searchUrl 搜索URL模板，支持书名变量
     * @property coverRule 从搜索结果页面提取封面URL的规则
     * @property concurrentRate 并发限制
     * @property loginUrl 登录地址
     * @property loginUi 登录界面配置
     * @property header 请求头配置
     * @property jsLib JS库
     * @property enabledCookieJar 是否启用Cookie管理
     */
    @Keep
    data class CoverRule(
        var enable: Boolean = true,
        var searchUrl: String,
        var coverRule: String,
        override var concurrentRate: String? = null,
        override var loginUrl: String? = null,
        override var loginUi: String? = null,
        override var header: String? = null,
        override var jsLib: String? = null,
        override var enabledCookieJar: Boolean? = false,
    ) : BaseSource {

        override fun getTag(): String {
            return "CoverRule"
        }

        override fun getKey(): String {
            return searchUrl
        }
    }

}
