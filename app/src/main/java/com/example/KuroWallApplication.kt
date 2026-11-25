package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.di.AppContainer

class KuroWallApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.prefetchCatalogsOnStartup()
        // MobileAds.initialize() and ad preloading are intentionally deferred until
        // GoogleMobileAdsConsentManager finishes the UMP GDPR/CCPA check in MainActivity.
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
