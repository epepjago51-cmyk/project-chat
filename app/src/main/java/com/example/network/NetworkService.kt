package com.example.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Models ---

data class NetworkUser(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val status: String,
    val lastSeen: Long
)

data class NetworkMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val text: String,
    val mediaUrl: String,
    val mediaType: String, // "image", "video", "none"
    val timestamp: Long
)

// --- APIs ---

interface KvdbApi {
    @GET("{bucketId}/{key}")
    suspend fun getValue(
        @Path("bucketId") bucketId: String,
        @Path("key") key: String
    ): ResponseBody

    @POST("{bucketId}/{key}")
    suspend fun putValue(
        @Path("bucketId") bucketId: String,
        @Path("key") key: String,
        @Body body: String
    ): ResponseBody

    @GET("{bucketId}/")
    suspend fun listKeys(
        @Path("bucketId") bucketId: String,
        @Query("prefix") prefix: String? = null
    ): ResponseBody
}

interface CatboxApi {
    @Multipart
    @POST("user/api.php")
    suspend fun uploadFile(
        @Part("reqtype") reqtype: RequestBody,
        @Part fileToUpload: MultipartBody.Part
    ): ResponseBody
}

// --- Service Client ---

object NetworkServiceClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val kvdbApi: KvdbApi = Retrofit.Builder()
        .baseUrl("https://kvdb.io/")
        .client(okHttpClient)
        .build()
        .create(KvdbApi::class.java)

    val catboxApi: CatboxApi = Retrofit.Builder()
        .baseUrl("https://catbox.moe/")
        .client(okHttpClient)
        .build()
        .create(CatboxApi::class.java)

    // Helper to parse dynamic key responses from KVDB.io
    fun parseKeys(responseString: String): List<String> {
        val trimmed = responseString.trim()
        if (trimmed.isEmpty()) return emptyList()
        
        if (trimmed.startsWith("[")) {
            return try {
                val cleaned = trimmed.removePrefix("[").removeSuffix("]").trim()
                if (cleaned.isEmpty()) emptyList()
                else cleaned.split(",").map { 
                    it.trim().removeSurrounding("\"").removeSurrounding("'") 
                }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            return trimmed.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    // Helper to format/serialize models to JSON
    fun toJsonUser(user: NetworkUser): String {
        return moshi.adapter(NetworkUser::class.java).toJson(user)
    }

    fun fromJsonUser(json: String): NetworkUser? {
        return try {
            moshi.adapter(NetworkUser::class.java).fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun toJsonMessage(msg: NetworkMessage): String {
        return moshi.adapter(NetworkMessage::class.java).toJson(msg)
    }

    fun fromJsonMessage(json: String): NetworkMessage? {
        return try {
            moshi.adapter(NetworkMessage::class.java).fromJson(json)
        } catch (e: Exception) {
            null
        }
    }
}
