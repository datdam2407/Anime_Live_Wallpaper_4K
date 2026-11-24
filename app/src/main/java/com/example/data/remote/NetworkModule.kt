package com.example.data.remote

import com.kuroanimelivewallpapers.app.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val WALLHAVEN_BASE_URL = "https://wallhaven.cc/"
    private const val REDDIT_BASE_URL = "https://www.reddit.com/"
    private val APP_USER_AGENT =
        "android:com.kuroanimelivewallpapers.app:v${BuildConfig.VERSION_NAME} (by /u/kurowall_engine)"
    const val MEDIA_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * Prefers IPv4 addresses ahead of IPv6 when resolving CDN hosts (Cloudflare R2, jsDelivr, Wallhaven).
     * Prevents hangs/timeouts on OEM ROMs (e.g., Xiaomi MIUI/HyperOS) and regional ISPs with flaky IPv6 routes.
     */
    val Ipv4PreferredDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val ipv4 = ArrayList<InetAddress>(addresses.size)
            val others = ArrayList<InetAddress>(addresses.size)
            for (addr in addresses) {
                if (addr is Inet4Address) {
                    ipv4.add(addr)
                } else {
                    others.add(addr)
                }
            }
            return if (ipv4.isEmpty()) addresses else ipv4 + others
        }
    }

    val tlsConnectionSpecs: List<ConnectionSpec> = listOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
    )

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
            .dns(Ipv4PreferredDns)
            .connectionSpecs(tlsConnectionSpecs)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(apiHeaderInterceptor)
            .addInterceptor(createLoggingInterceptor())
            .build()
    }

    /**
     * Dedicated client for binary media (Coil images, Media3/ExoPlayer streams, MP4 downloads).
     * Uses a standard mobile User-Agent, IPv4-first DNS, higher per-host concurrency, and
     * omits "Accept: application/json" so Cloudflare R2 & CDNs serve images/videos cleanly.
     */
    val mediaOkHttpClient: OkHttpClient by lazy {
        val mediaDispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
        val mediaHeaderInterceptor = Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("User-Agent", MEDIA_USER_AGENT)
            if (original.header("Accept") == null) {
                builder.header("Accept", "image/webp,image/jpeg,image/png,image/*,*/*;q=0.8")
            }
            chain.proceed(builder.build())
        }
        OkHttpClient.Builder()
            .dispatcher(mediaDispatcher)
            .dns(Ipv4PreferredDns)
            .connectionSpecs(tlsConnectionSpecs)
            .retryOnConnectionFailure(true)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
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

    fun createWallhavenService(context: android.content.Context): WallhavenService {
        return Retrofit.Builder()
            .baseUrl(WALLHAVEN_BASE_URL)
            .client(NetworkClient.createWallhavenOkHttp(context))
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
