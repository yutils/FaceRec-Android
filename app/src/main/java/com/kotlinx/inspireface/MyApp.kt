package com.kotlinx.inspireface

import android.app.Application
import com.kotlinx.inspireface.config.InspireFaceConfig

class MyApp : Application() {
    companion object {
        lateinit var instance: MyApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // SDK 生命周期跟随 Application，避免 MainActivity 销毁后子页面仍在使用导致崩溃
        InspireFaceConfig.init(this)
    }
}