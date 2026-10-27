package com.kotlinx.inspireface

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

abstract class BaseActivity : AppCompatActivity() {

    /** 摄像头页需要浅色状态栏图标，其余页面跟随深浅色主题自动切换 */
    protected open val lightStatusBarIcons: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = if (lightStatusBarIcons) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }

    protected fun setupToolbar(toolbar: MaterialToolbar, title: CharSequence? = null) {
        if (title != null) toolbar.title = title
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    protected fun applyInsets(
        view: View,
        top: Boolean = true,
        bottom: Boolean = true,
        ime: Boolean = false,
    ) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val imeBottom = if (ime) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            v.setPadding(
                initialLeft + bars.left,
                initialTop + if (top) bars.top else 0,
                initialRight + bars.right,
                initialBottom + (if (bottom) bars.bottom else 0) + imeBottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
