package com.bilifolder.downloader.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bilifolder.downloader.BiliApp

/** MainViewModel 工厂：从 Application 取 AppContainer */
class MainViewModelFactory(private val activity: Activity) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java))
        return MainViewModel(activity.application as BiliApp) as T
    }
}
