package com.example.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RedditService {
    @GET("r/{subreddit}/search.json")
    suspend fun searchSubreddit(
        @Path("subreddit") subreddit: String = "Animewallpaper",
        @Query("q") query: String,
        @Query("restrict_sr") restrictSr: String = "on",
        @Query("sort") sort: String = "top",
        @Query("t") time: String = "all",
        @Query("limit") limit: Int = 30,
    ): Response<RedditResponse>
}
