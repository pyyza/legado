package io.legado.app.help.config

import android.os.Build
import android.util.LruCache
import io.legado.app.R
import com.airbnb.lottie.LottieCompositionFactory
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import kotlin.math.abs

/**
 * 高级标题（Lottie 章节标题）配置。
 *
 * 移植自 Archive 仓库，唯一语义差异：[TITLE_MODE_ADVANCED] 取 4 而非 3，
 * 因为 Max 的 `titleMode == 3` 已被「靠右」占用（见 ReadBookConfig.isRightTitle）。
 */
object AdvancedTitleConfig {

    /** 标题模式：高级（Lottie 标题）。Max 的合法域为 0..4。 */
    const val TITLE_MODE_ADVANCED = 4
    const val SPLIT_DELIMITER = 0
    const val SPLIT_REGEX = 1
    const val LOTTIE_BLOCK_ROLE = "advanced_title_lottie"
    const val DEFAULT_HEIGHT_FACTOR = 55

    /** 动画模式：跟随模板 [isAnimatedLottieJson] 自动检测（默认）。 */
    const val ANIMATION_MODE_AUTO = 0

    /** 动画模式：强制逐帧播放，即使模板本身是静态的。 */
    const val ANIMATION_MODE_PLAY = 1

    /** 动画模式：强制静止，只渲染第一帧。 */
    const val ANIMATION_MODE_STATIC = 2

    /** 合法区间，用于把外部传入的模式值收敛到三态之内。 */
    val ANIMATION_MODE_RANGE = ANIMATION_MODE_AUTO..ANIMATION_MODE_STATIC
    private const val BOOK_RULE_KEY = "advancedTitleRule"

    /** [isAnimatedLottieJson] 的结果缓存，键为「长度:hashCode」。 */
    private val animationFlagCache = LruCache<String, Boolean>(32)

    data class SplitRule(
        val mode: Int = SPLIT_DELIMITER,
        val delimiter: String = " ",
        val regex: String = DEFAULT_REGEX
    )

    data class Parts(
        val title: String,
        val s1: String,
        val s2: String
    )

    var globalRule: SplitRule
        get() = appCtx.getPrefString(PreferKey.advancedTitleConfig)
            ?.let { GSON.fromJsonObject<SplitRule>(it).getOrNull() }
            ?: SplitRule()
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleConfig, GSON.toJson(value))
        }

    var lottieJson: String?
        get() = appCtx.getPrefString(PreferKey.advancedTitleLottieJson)
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleLottieJson, value?.takeIf { it.isNotBlank() })
        }

    var lottiePath: String?
        get() = appCtx.getPrefString(PreferKey.advancedTitleLottiePath)
        set(value) {
            appCtx.putPrefString(PreferKey.advancedTitleLottiePath, value?.takeIf { it.isNotBlank() })
        }

    var heightFactor: Int
        get() = appCtx.getPrefInt(PreferKey.advancedTitleHeightFactor, DEFAULT_HEIGHT_FACTOR)
            .coerceIn(30, 120)
        set(value) {
            appCtx.putPrefInt(PreferKey.advancedTitleHeightFactor, value.coerceIn(30, 120))
        }

    /**
     * 当前生效条目的动画模式，取值 [ANIMATION_MODE_AUTO] / [ANIMATION_MODE_PLAY] / [ANIMATION_MODE_STATIC]。
     *
     * 翻页渲染时只会用到「当前应用」的那一条模板，所以这里只保存单值，由
     * [AdvancedTitlePackageManager.apply] 在切换条目时回写——与 globalRule / heightFactor
     * 的处理方式一致，避免在渲染路径上反复读 package.json。
     */
    var animationMode: Int
        get() = normalizeAnimationMode(
            appCtx.getPrefInt(PreferKey.advancedTitleAnimationMode, ANIMATION_MODE_AUTO)
        )
        set(value) {
            appCtx.putPrefInt(PreferKey.advancedTitleAnimationMode, normalizeAnimationMode(value))
        }

    /** 把任意来源的模式值收敛到合法区间，`null` 视为 [ANIMATION_MODE_AUTO]。 */
    fun normalizeAnimationMode(value: Int?): Int {
        return (value ?: ANIMATION_MODE_AUTO).coerceIn(ANIMATION_MODE_RANGE)
    }

    /**
     * 结合用户设置与模板内容，决定这份 JSON 是否需要逐帧播放。
     *
     * [ANIMATION_MODE_AUTO] 用 [isAnimatedLottieJson] 自动检测；
     * 另两个取值直接覆盖检测结果，用于自动检测误判时人工纠正。
     */
    fun resolveAnimated(json: String): Boolean {
        return when (animationMode) {
            ANIMATION_MODE_PLAY -> true
            ANIMATION_MODE_STATIC -> false
            else -> isAnimatedLottieJson(json)
        }
    }

    fun bookRule(book: Book?): SplitRule? {
        val value = book?.getVariable(BOOK_RULE_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return GSON.fromJsonObject<SplitRule>(value).getOrNull()
    }

    fun setBookRule(book: Book, rule: SplitRule?) {
        book.putVariable(BOOK_RULE_KEY, rule?.let { GSON.toJson(it) })
    }

    fun effectiveRule(book: Book?): SplitRule = bookRule(book) ?: globalRule

    fun split(title: String, book: Book? = null): Parts {
        val cleanTitle = title.trim()
        val rule = effectiveRule(book)
        return split(cleanTitle, rule)
    }

    fun split(title: String, rule: SplitRule): Parts {
        val cleanTitle = title.trim()
        // GSON 反序列化不走 Kotlin 默认参数（Unsafe 分配），旧版本/混淆改写字段名后
        // rule 的字段可能为 null，这里统一兜底，避免 ifEmpty 内部 length() NPE。
        // 先上转型为可空类型，确保编译器保留运行时的 null 检查。
        val delimiter: String? = rule.delimiter
        val regex: String? = rule.regex
        return when (rule.mode) {
            SPLIT_REGEX -> splitByRegex(cleanTitle, regex ?: DEFAULT_REGEX)
            else -> splitByDelimiter(cleanTitle, delimiter ?: " ")
        }
    }

    fun renderLottieJson(book: Book, title: String): String? {
        val raw = AdvancedTitlePackageManager.currentTemplate()
            ?: lottieJson?.takeIf { it.isNotBlank() }
            ?: lottiePath?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
            }
        return raw?.let { replaceVariables(it, book, title) }
    }

    fun renderValidLottieJson(book: Book, title: String): String? {
        val json = renderLottieJson(book, title)?.takeIf { it.isNotBlank() } ?: return null
        return json.takeIf { hasRenderableLayers(it) }
    }

    fun isValidLottieJson(json: String): Boolean {
        return runCatching {
            val obj = JSONObject(json)
            obj.has("layers") &&
                obj.optJSONArray("layers") != null &&
                LottieCompositionFactory.fromJsonStringSync(
                    json,
                    null
                ).value != null
        }.getOrDefault(false)
    }

    fun hasRenderableLayers(json: String): Boolean {
        return runCatching {
            val obj = JSONObject(json)
            obj.optJSONArray("layers")?.length()?.let { it > 0 } == true
        }.getOrDefault(false)
    }

    /**
     * 判断模板里是否含有真正的动画。
     *
     * 阅读器对高级标题默认用无限循环播放，但标题编辑器导出的模板允许是纯静态的
     * （所有属性都是固定值、文字只有单个文档关键帧）。静态模板每一帧画面完全相同，
     * 继续循环播放就是纯粹的持续重绘开销。
     *
     * 这是 [ANIMATION_MODE_AUTO] 下的判定依据，外部应通过 [resolveAnimated] 取值，
     * 以便用户用 [ANIMATION_MODE_PLAY] / [ANIMATION_MODE_STATIC] 覆盖检测结果。
     *
     * 解析失败一律返回 true（按动画处理），保证回退到原有行为。
     */
    fun isAnimatedLottieJson(json: String): Boolean {
        val key = "${json.length}:${json.hashCode()}"
        animationFlagCache.get(key)?.let { return it }
        val animated = runCatching { detectAnimated(JSONObject(json)) }.getOrDefault(true)
        animationFlagCache.put(key, animated)
        return animated
    }

    private fun detectAnimated(root: JSONObject): Boolean {
        if (hasAnimatedFlag(root)) return true
        // 拿不到图层列表时不下结论，交给调用方按动画处理
        val layers = root.optJSONArray("layers") ?: return true
        val compIn = root.optDouble("ip", 0.0)
        val compOut = root.optDouble("op", 0.0)
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            // 进出场时间与合成不一致 → 图层会中途出现或消失，属于动画
            if (abs(layer.optDouble("ip", compIn) - compIn) > 0.01) return true
            if (abs(layer.optDouble("op", compOut) - compOut) > 0.01) return true
            if (textIsAnimated(layer)) return true
        }
        return false
    }

    /**
     * Lottie 用 `"a": 1` 标记「该属性走关键帧」，所以整棵树递归扫一遍即可。
     *
     * 注意 `"a"` 同时也是变换里的「锚点」属性名（其值是对象而不是数字），
     * 因此只认值为数字 1 或布尔 true 的那种，避免把锚点误判成动画。
     */
    private fun hasAnimatedFlag(value: Any?): Boolean {
        return when (value) {
            is JSONObject -> {
                val names = value.keys()
                var found = false
                while (names.hasNext() && !found) {
                    val name = names.next()
                    val child = value.opt(name)
                    if (name == "a") {
                        if (child is Number && child.toInt() == 1) found = true
                        if (child is Boolean && child) found = true
                    }
                    if (!found) found = hasAnimatedFlag(child)
                }
                found
            }
            is JSONArray -> {
                var found = false
                var i = 0
                while (i < value.length() && !found) {
                    found = hasAnimatedFlag(value.opt(i))
                    i++
                }
                found
            }
            else -> false
        }
    }

    /**
     * 文字动画不走 `"a": 1` 这条线索：文字用文档关键帧数组（`t.d.k`）表达，
     * 文字动画器放在 `t.a` 数组里，这两处需要单独判断。
     */
    private fun textIsAnimated(layer: JSONObject): Boolean {
        val text = layer.optJSONObject("t") ?: return false
        val animators = text.optJSONArray("a")
        if (animators != null && animators.length() > 0) return true
        val keyFrames = text.optJSONObject("d")?.opt("k")
        return keyFrames is JSONArray && keyFrames.length() > 1
    }

    fun preview(title: String, book: Book? = null): String {
        val parts = split(title, book)
        return appCtx.getString(
            R.string.advanced_title_preview_template,
            parts.s1.ifBlank { appCtx.getString(R.string.empty) },
            parts.s2.ifBlank { appCtx.getString(R.string.empty) }
        )
    }

    private fun splitByDelimiter(title: String, delimiter: String): Parts {
        val mark = delimiter.ifEmpty { " " }
        val index = if (mark.isBlank()) {
            title.indexOfFirst { it.isWhitespace() || it == '　' }
        } else {
            title.indexOf(mark)
        }
        if (index < 0) return splitByRegex(title, DEFAULT_REGEX)
        val end = if (mark.isBlank()) {
            var next = index
            while (next < title.length && (title[next].isWhitespace() || title[next] == '　')) next++
            next
        } else {
            index + mark.length
        }
        val s1 = title.substring(0, index).trim()
        val s2 = title.substring(end.coerceAtMost(title.length)).trim()
        return if (s1.isBlank() || s2.isBlank()) {
            Parts(title, "", title)
        } else {
            Parts(title, s1, s2)
        }
    }

    private fun splitByRegex(title: String, regex: String): Parts {
        val pattern = regex.ifBlank { DEFAULT_REGEX }
        val match = runCatching { Regex(pattern).find(title) }.getOrNull()
        if (match != null) {
            val groups = match.groups
            // 命名组（(?<s1>…)/(?<s2>…)）底层走 Matcher#start(String)，API 26 起才可用；
            // 低版本直接回退到按序号取组，行为等价（无非是少了命名组的写法支持）。
            val namedS1: String?
            val namedS2: String?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val namedGroups = groups as? MatchNamedGroupCollection
                namedS1 = runCatching { namedGroups?.get("s1")?.value }.getOrNull()
                namedS2 = runCatching { namedGroups?.get("s2")?.value }.getOrNull()
            } else {
                namedS1 = null
                namedS2 = null
            }
            val s1 = (namedS1 ?: groups.getOrNull(1)?.value).orEmpty().trim()
            val s2 = (namedS2 ?: groups.getOrNull(2)?.value).orEmpty().trim()
            if (s1.isNotBlank() && s2.isNotBlank()) return Parts(title, s1, s2)
        }
        return Parts(title, "", title)
    }

    private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? {
        return if (index in 0 until size) get(index) else null
    }

    private fun replaceVariables(
        source: String,
        book: Book,
        title: String
    ): String {
        val parts = split(title, book)
        return replaceTemplateVariables(source, variables(book, parts))
    }

    internal fun replaceTemplateVariables(
        source: String,
        variables: Map<String, String>
    ): String {
        return variables.entries.fold(source) { value, entry ->
            val replacement = GSON.toJson(entry.value).let { encoded ->
                if (encoded.length >= 2 && encoded.first() == '"' && encoded.last() == '"') {
                    encoded.substring(1, encoded.lastIndex)
                } else {
                    entry.value
                }
            }
            value
                .replace("\${${entry.key}}", replacement)
                .replace("{{${entry.key}}}", replacement)
        }
    }

    private fun variables(book: Book, parts: Parts): Map<String, String> {
        return mapOf(
            "title" to parts.title,
            "s1" to parts.s1,
            "s2" to parts.s2,
            "bookName" to book.name,
            "author" to book.author
        )
    }

    const val DEFAULT_REGEX = "^\\s*(第\\S+[章节回卷部篇集])\\s+(.+?)\\s*$"

}
