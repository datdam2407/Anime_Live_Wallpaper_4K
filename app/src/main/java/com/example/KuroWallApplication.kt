package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.di.AppContainer
import com.google.android.gms.ads.MobileAds

class KuroWallApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching {
            MobileAds.initialize(this) {
                container.interstitialAdManager.preload(this)
            }
        }
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
