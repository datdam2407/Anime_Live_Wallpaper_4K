package com.example.data.remote


import com.kuroanimelivewallpapers.app.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit


object NetworkModule {
    private const val WALLHAVEN_BASE_URL = "https://wallhaven.cc/"
    private const val REDDIT_BASE_URL = "https://www.reddit.com/"
    private val APP_USER_AGENT =
        "android:com.kuroanimelivewallpapers.app:v${BuildConfig.VERSION_NAME} (by /u/kurowall_engine)"


    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }


    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }


    /**
     * Dedicated client for REST/JSON endpoints (Wallhaven & Reddit APIs).
     */
    val apiOkHttpClient: OkHttpClient by lazy {
        val apiHeaderInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(apiHeaderInterceptor)
            .addInterceptor(createLoggingInterceptor())
            .build()
    }


    /**
     * Dedicated client for binary media (Coil images, Media3/ExoPlayer streams, MP4 downloads).
     * Omits "Accept: application/json" so CDNs serve proper image/video MIME types.
     */
    val mediaOkHttpClient: OkHttpClient by lazy {
        val mediaHeaderInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", APP_USER_AGENT)
                .build()
            chain.proceed(request)
        }
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(mediaHeaderInterceptor)
            .addInterceptor(createLoggingInterceptor())
            .build()
    }


    val wallhavenService: WallhavenService by lazy {
        Retrofit.Builder()
            .baseUrl(WALLHAVEN_BASE_URL)
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WallhavenService::class.java)
    }


    val redditService: RedditService by lazy {
        Retrofit.Builder()
            .baseUrl(REDDIT_BASE_URL)
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RedditService::class.java)
    }
}
