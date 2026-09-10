package io.legado.app.ui.welcome

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.postDelayed
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>(imageBg = false) {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    // 仅在欢迎页真正展示(显示时长>0)时才绘制欢迎背景/内容，
    // 避免快速启动路径(BaseActivity.onCreate 会调用 upBackgroundImage)产生额外开销
    private var welcomeVisible = false

    override fun initTheme() {
        setTheme(R.style.AppTheme_Welcome)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val welcomeShowTime = getPrefInt(PreferKey.welcomeShowTime, 0)
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else if (welcomeShowTime == 0) {
            // 未开启欢迎页：不绘制欢迎内容，直接进主界面，避免多余开屏停留
            hideWelcomeContent()
            startMainActivity()
        } else {
            // 欢迎页已启用：让启动页设置(背景图/文字/图标)生效
            welcomeVisible = true
            upBackgroundImage()
            upWelcomeViewVisibility()
            binding.root.postDelayed(welcomeShowTime.toLong()) { startMainActivity() }
        }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    private fun hideWelcomeContent() {
        binding.tvLegado.visibility = View.GONE
        binding.ivBook.visibility = View.GONE
        binding.tvGzh.visibility = View.GONE
    }

    /**
     * 启动页背景：自定义启动页开启且图片有效时使用所选图片，
     * 否则回落到默认预览背景(背景色+居中图标)。
     * 未启用欢迎页时不做任何绘制，保持最快启动。
     */
    override fun upBackgroundImage() {
        if (!welcomeVisible) return
        val imagePath =
            if (AppConfig.isNightTheme) AppConfig.welcomeImageDark else AppConfig.welcomeImage
        val customBitmap = if (getPrefBoolean(PreferKey.customWelcome, false) &&
            !imagePath.isNullOrBlank() && File(imagePath).isFile
        ) {
            runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
        } else {
            null
        }
        if (customBitmap != null) {
            window.decorView.background = BitmapDrawable(resources, customBitmap)
        } else {
            window.decorView.setBackgroundResource(R.drawable.bg_welcome_preview)
        }
    }

    private fun upWelcomeViewVisibility() {
        val showText =
            if (AppConfig.isNightTheme) AppConfig.welcomeShowTextDark else AppConfig.welcomeShowText
        val showIcon =
            if (AppConfig.isNightTheme) AppConfig.welcomeShowIconDark else AppConfig.welcomeShowIcon
        binding.tvLegado.visibility = if (showText) View.VISIBLE else View.GONE
        binding.ivBook.visibility = if (showIcon) View.VISIBLE else View.GONE
        binding.tvGzh.visibility = if (showText) View.VISIBLE else View.GONE
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()
