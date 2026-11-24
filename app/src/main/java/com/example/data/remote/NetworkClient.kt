package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kuroanimelivewallpapers.app.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Provides Disk-Cached OkHttpClients and Retrofit services with Offline Fallback:
 * - jsDelivr Live Catalog: 2-hour online cache, 7-day offline stale fallback.
 * - Wallhaven API: 6-hour online disk cache, 7-day offline stale fallback (protects 45 req/min limit).
 */
object NetworkClient {
    private const val TAG = "LIVE_DEBUG"
    private const val JSDELIVR_BASE_URL = "https://cdn.jsdelivr.net/"
    private const val DISK_CACHE_SIZE_BYTES = 30L * 1024L * 1024L // 30 MB
    private val APP_USER_AGENT =
        "android:com.kuroanimelivewallpapers.app:v${BuildConfig.VERSION_NAME} (by /u/kurowall_engine)"

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * Never throws SecurityException even if ACCESS_NETWORK_STATE is absent or ConnectivityManager
     * is unavailable; defaults to `true` so live network requests are still attempted.
     */
    private fun isNetworkAvailableSafely(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            runCatching { Log.w(TAG, "ACCESS_NETWORK_STATE missing; defaulting to online", e) }
            true
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Connectivity check failed; defaulting to online", e) }
            true
        }
    }

    private fun buildCachedOkHttp(
        context: Context,
        cacheFolderName: String,
        maxAgeHours: Int,
        includeJsonHeaders: Boolean = true,
    ): OkHttpClient {
        val appContext = context.applicationContext
        val cacheDir = File(appContext.cacheDir, cacheFolderName)
        val cache = Cache(cacheDir, DISK_CACHE_SIZE_BYTES)

        val headerInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", APP_USER_AGENT)
            if (includeJsonHeaders) {
                builder.header("Accept", "application/json")
            }
            chain.proceed(builder.build())
        }

        // Offline-resilient interceptor:
        // 1. If device reports offline, try 7-day stale cache first; if uncached (504), still try network
        //    in case ConnectivityManager lagged during cold start or VPN handoff.
        // 2. If network throws IOException, fall back to 7-day stale disk cache if present.
        val offlineInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val staleCacheControl = CacheControl.Builder()
                .onlyIfCached()
                .maxStale(7, TimeUnit.DAYS)
                .build()

            if (!isNetworkAvailableSafely(appContext)) {
                val cachedResponse = chain.proceed(
                    originalRequest.newBuilder().cacheControl(staleCacheControl).build(),
                )
                if (cachedResponse.code != 504) {
                    return@Interceptor cachedResponse
                }
                cachedResponse.close()
            }

            try {
                chain.proceed(originalRequest)
            } catch (io: IOException) {
                val fallbackResponse = runCatching {
                    chain.proceed(originalRequest.newBuilder().cacheControl(staleCacheControl).build())
                }.getOrNull()
                if (fallbackResponse != null && fallbackResponse.code != 504) {
                    fallbackResponse
                } else {
                    fallbackResponse?.close()
                    throw io
                }
            }
        }

        // Online mode: cache 2xx responses on disk for maxAgeHours; never cache HTTP 4xx/5xx errors.
        val onlineInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                return@Interceptor response
            }
            val cacheControl = CacheControl.Builder()
                .maxAge(maxAgeHours, TimeUnit.HOURS)
                .build()
            response.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Expires")
                .header("Cache-Control", cacheControl.toString())
                .build()
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .dns(NetworkModule.Ipv4PreferredDns)
            .connectionSpecs(NetworkModule.tlsConnectionSpecs)
            .retryOnConnectionFailure(true)
            .addInterceptor(headerInterceptor)
            .addInterceptor(offlineInterceptor)
            .addNetworkInterceptor(onlineInterceptor)
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Live Wallpaper catalog client backed by jsDelivr CDN (2h online disk cache, 7d offline fallback).
     */
    fun createLiveCatalogApi(context: Context): LiveCatalogApi {
        return Retrofit.Builder()
            .baseUrl(JSDELIVR_BASE_URL)
            .client(buildCachedOkHttp(context, "live_catalog_cache", maxAgeHours = 2))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(LiveCatalogApi::class.java)
    }

    /**
     * Wallhaven API OkHttpClient (6h online disk cache, 7d offline fallback) to shield against 429 limits.
     */
    fun createWallhavenOkHttp(context: Context): OkHttpClient {
        return buildCachedOkHttp(context, "wallhaven_cache", maxAgeHours = 6)
    }
}
