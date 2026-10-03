package com.a11yland.hissi

import android.app.Application
import com.a11yland.hissi.data.AppContainer

class HissiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
