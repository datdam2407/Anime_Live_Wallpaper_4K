package com.example.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface WallhavenService {
    @GET("api/v1/search")
    suspend fun searchAnime(
        @Query("q") query: String? = null,
        @Query("categories") categories: String = "010",
        @Query("purity") purity: String = "100",
        @Query("ratios") ratios: String? = null,
        @Query("atleast") atleast: String? = null,
        @Query("sorting") sorting: String = "toplist",
        @Query("order") order: String? = null,
        @Query("topRange") topRange: String? = "1y",
        @Query("page") page: Int = 1,
    ): Response<WallhavenResponse>
}
