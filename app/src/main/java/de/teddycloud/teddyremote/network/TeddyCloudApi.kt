package de.teddycloud.teddyremote.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

internal interface TeddyCloudApi {
    @GET("api/getBoxes")
    suspend fun getBoxes(): ResponseBody

    @GET("api/settings/get/{key}")
    suspend fun getSetting(
        @Path("key", encoded = true) key: String,
        @Query("overlay") overlay: String? = null,
    ): ResponseBody

    @POST("api/settings/set/{key}")
    suspend fun setSetting(
        @Path("key", encoded = true) key: String,
        @Query("overlay") overlay: String,
        @Body value: RequestBody,
    ): ResponseBody

    @GET("api/getTagInfo")
    suspend fun getTagInfo(
        @Query("ruid") ruid: String,
        @Query("overlay") overlay: String,
        @Query("contentVersion") contentVersion: Long? = null,
    ): ResponseBody

    @GET("api/tonieboxesJson")
    suspend fun getTonieboxCatalog(): ResponseBody

    @GET("api/tonieboxesCustomJson")
    suspend fun getCustomTonieboxCatalog(): ResponseBody

    @POST("api/box/playback")
    suspend fun playback(
        @Query("overlay") overlay: String,
        @Body command: RequestBody,
    ): ResponseBody

    @POST("api/box/volume")
    suspend fun volume(
        @Query("overlay") overlay: String,
        @Body command: RequestBody,
    ): ResponseBody

    @POST("api/box/ping")
    suspend fun ping(@Query("overlay") overlay: String): ResponseBody

    @POST("api/box/bedtime")
    suspend fun bedtime(
        @Query("overlay") overlay: String,
        @Body command: RequestBody,
    ): ResponseBody

    @POST("api/box/sleep")
    suspend fun sleep(
        @Query("overlay") overlay: String,
        @Body command: RequestBody,
    ): ResponseBody
}
