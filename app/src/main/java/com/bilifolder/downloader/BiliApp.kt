package com.bilifolder.downloader

import android.app.Application
import com.bilifolder.downloader.data.AppContainer

class BiliApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
