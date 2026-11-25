package com.example.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface LiveCatalogApi {
    @GET("gh/datdam2407/kuro-wallpaper-data@main/live_wallpapers_v1.json")
    suspend fun getLiveCatalog(): LiveCatalogResponse

    @GET
    suspend fun getLiveCatalogByUrl(@Url url: String): LiveCatalogResponse

    @GET("https://cdn.jsdelivr.net/gh/datdam2407/kuro-wallpaper-data@main/static_wallpapers_v1.json")
    suspend fun fetchStaticCatalogCdn(): Response<StaticCatalogResponse>

    @GET("https://raw.githubusercontent.com/datdam2407/kuro-wallpaper-data/main/static_wallpapers_v1.json")
    suspend fun fetchStaticCatalogRaw(): Response<StaticCatalogResponse>

    companion object {
        const val GITHUB_RAW_FALLBACK_URL =
            "https://raw.githubusercontent.com/datdam2407/kuro-wallpaper-data/main/live_wallpapers_v1.json"
    }
}
