package com.example.repository

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ChatSessionEntity
import com.example.data.MessageEntity
import com.example.data.UserEntity
import com.example.network.NetworkMessage
import com.example.network.NetworkServiceClient
import com.example.network.NetworkUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("D_CHAT_PREFS", Context.MODE_PRIVATE)

    // --- Configurations & Preferences ---

    fun getMyUserId(): String? = sharedPrefs.getString("MY_USER_ID", null)
    fun getMyName(): String? = sharedPrefs.getString("MY_NAME", null)
    fun getMyAvatar(): String? = sharedPrefs.getString("MY_AVATAR", null)
    fun getMyStatus(): String? = sharedPrefs.getString("MY_STATUS", null)

    fun getBucketId(): String {
        return sharedPrefs.getString("BUCKET_ID", null) ?: "dchat_deny_secure_2026_bucket"
    }

    fun setBucketId(bucket: String) {
        sharedPrefs.edit().putString("BUCKET_ID", bucket.trim()).apply()
    }

    fun saveMyProfile(id: String, name: String, avatarUrl: String, status: String) {
        sharedPrefs.edit()
            .putString("MY_USER_ID", id.trim())
            .putString("MY_NAME", name.trim())
            .putString("MY_AVATAR", avatarUrl.trim())
            .putString("MY_STATUS", status.trim())
            .apply()
    }

    fun clearMyProfile() {
        sharedPrefs.edit()
            .remove("MY_USER_ID")
            .remove("MY_NAME")
            .remove("MY_AVATAR")
            .remove("MY_STATUS")
            .apply()
    }

    // --- Remote Server Uploads ---

    suspend fun uploadMedia(bytes: ByteArray, extension: String): String? = withContext(Dispatchers.IO) {
        try {
            val reqtype = "fileupload".toRequestBody("text/plain".toMediaTypeOrNull())
            val mimeType = when (extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
            val filePart = MultipartBody.Part.createFormData(
                "fileToUpload",
                "media_${System.currentTimeMillis()}.$extension",
                bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            )
            val response = NetworkServiceClient.catboxApi.uploadFile(reqtype, filePart)
            val url = response.string().trim()
            if (url.startsWith("http")) {
                url
            } else {
                Log.e("ChatRepository", "Upload failed with response: $url")
                null
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Upload exception", e)
            null
        }
    }

    suspend fun compressAndUploadImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext null
            
            // Resize if too large to save bandwidth
            val maxDimension = 1080
            val ratio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
            val (targetWidth, targetHeight) = if (originalBitmap.width > originalBitmap.height) {
                if (originalBitmap.width > maxDimension) {
                    maxDimension to (maxDimension / ratio).toInt()
                } else {
                    originalBitmap.width to originalBitmap.height
                }
            } else {
                if (originalBitmap.height > maxDimension) {
                    (maxDimension * ratio).toInt() to maxDimension
                } else {
                    originalBitmap.width to originalBitmap.height
                }
            }

            val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val bytes = outputStream.toByteArray()

            uploadMedia(bytes, "jpg")
        } catch (e: Exception) {
            Log.e("ChatRepository", "Image compression and upload failed", e)
            null
        }
    }

    suspend fun uploadVideo(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return@withContext null
            uploadMedia(bytes, "mp4")
        } catch (e: Exception) {
            Log.e("ChatRepository", "Video upload failed", e)
            null
        }
    }

    // --- Profile & User Flows ---

    suspend fun registerOrUpdateProfileOnCloud(
        id: String,
        name: String,
        avatarUrl: String,
        status: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val networkUser = NetworkUser(
                id = id,
                name = name,
                avatarUrl = avatarUrl,
                status = status,
                lastSeen = System.currentTimeMillis()
            )
            val json = NetworkServiceClient.toJsonUser(networkUser)
            NetworkServiceClient.kvdbApi.putValue(getBucketId(), "user_$id", json)
            
            // Save locally
            database.userDao().insertUser(
                UserEntity(id, name, avatarUrl, status, networkUser.lastSeen)
            )
            saveMyProfile(id, name, avatarUrl, status)
            true
        } catch (e: Exception) {
            Log.e("ChatRepository", "Cloud registration failed", e)
            false
        }
    }

    suspend fun searchUsersOnCloud(query: String): List<UserEntity> = withContext(Dispatchers.IO) {
        try {
            // 1. List all keys with prefix "user_"
            val response = NetworkServiceClient.kvdbApi.listKeys(getBucketId(), "user_")
            val keys = NetworkServiceClient.parseKeys(response.string())
            
            // 2. Fetch profiles for keys we don't have or to sync
            val myId = getMyUserId()
            for (key in keys) {
                val userId = key.removePrefix("user_")
                if (userId == myId) continue

                try {
                    val userProfileJson = NetworkServiceClient.kvdbApi.getValue(getBucketId(), key).string()
                    val netUser = NetworkServiceClient.fromJsonUser(userProfileJson)
                    if (netUser != null) {
                        database.userDao().insertUser(
                            UserEntity(
                                id = netUser.id,
                                name = netUser.name,
                                avatarUrl = netUser.avatarUrl,
                                status = netUser.status,
                                lastSeen = netUser.lastSeen
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("ChatRepository", "Failed to fetch user profile for $userId", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Cloud user search listing failed", e)
        }

        // Return from local Room database search
        database.userDao().searchUsers(query)
    }

    // --- Messaging Flows ---

    fun getLocalMessages(chatId: String): Flow<List<MessageEntity>> {
        return database.messageDao().getMessagesForChat(chatId)
    }

    fun getLocalSessions(): Flow<List<ChatSessionEntity>> {
        return database.chatSessionDao().getAllSessionsFlow()
    }

    suspend fun clearChatUnreadCount(chatId: String) {
        database.chatSessionDao().clearUnreadCount(chatId)
    }

    suspend fun sendChatMessage(
        receiverId: String,
        receiverName: String,
        receiverAvatar: String,
        text: String,
        mediaUri: Uri? = null,
        mediaType: String = "none" // "image", "video", "none"
    ): Boolean = withContext(Dispatchers.IO) {
        val myId = getMyUserId() ?: return@withContext false
        val myName = getMyName() ?: "Me"
        
        // Chat ID is sorted participant IDs: alice_deny or deny_john
        val chatId = if (myId < receiverId) "${myId}_${receiverId}" else "${receiverId}_${myId}"
        val messageId = "msg_${chatId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        var uploadedMediaUrl = ""
        if (mediaUri != null) {
            val url = if (mediaType == "image") {
                compressAndUploadImage(mediaUri)
            } else {
                uploadVideo(mediaUri)
            }
            if (url != null) {
                uploadedMediaUrl = url
            } else {
                Log.e("ChatRepository", "Media upload failed, aborting message send")
                return@withContext false
            }
        }

        try {
            val netMsg = NetworkMessage(
                id = messageId,
                senderId = myId,
                receiverId = receiverId,
                text = text,
                mediaUrl = uploadedMediaUrl,
                mediaType = mediaType,
                timestamp = System.currentTimeMillis()
            )
            val json = NetworkServiceClient.toJsonMessage(netMsg)
            NetworkServiceClient.kvdbApi.putValue(getBucketId(), messageId, json)

            // Save to local DB
            val localMsg = MessageEntity(
                id = messageId,
                chatId = chatId,
                senderId = myId,
                receiverId = receiverId,
                text = text,
                mediaUrl = uploadedMediaUrl,
                mediaType = mediaType,
                timestamp = netMsg.timestamp,
                isRead = true
            )
            database.messageDao().insertMessage(localMsg)

            // Update session
            val session = ChatSessionEntity(
                chatId = chatId,
                participantId = receiverId,
                participantName = receiverName,
                participantAvatar = receiverAvatar,
                lastMessageText = if (mediaType != "none") "[Media] $text".trim() else text,
                lastMessageTimestamp = netMsg.timestamp,
                unreadCount = 0
            )
            database.chatSessionDao().insertSession(session)
            true
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to send message on cloud", e)
            false
        }
    }

    suspend fun syncAllMessages(): Boolean = withContext(Dispatchers.IO) {
        val myId = getMyUserId() ?: return@withContext false
        try {
            // 1. List all message keys
            val response = NetworkServiceClient.kvdbApi.listKeys(getBucketId(), "msg_")
            val keys = NetworkServiceClient.parseKeys(response.string())

            // 2. Filter keys that contain our user ID
            val relevantKeys = keys.filter { key ->
                val parts = key.split("_")
                parts.any { it == myId }
            }

            var hasNew = false

            // 3. Download any keys we don't have locally
            for (key in relevantKeys) {
                val existsLocally = database.messageDao().getMessageById(key) != null
                if (!existsLocally) {
                    try {
                        val msgJson = NetworkServiceClient.kvdbApi.getValue(getBucketId(), key).string()
                        val netMsg = NetworkServiceClient.fromJsonMessage(msgJson)
                        if (netMsg != null) {
                            val chatId = if (netMsg.senderId < netMsg.receiverId) {
                                "${netMsg.senderId}_${netMsg.receiverId}"
                            } else {
                                "${netMsg.receiverId}_${netMsg.senderId}"
                            }

                            // Save message
                            database.messageDao().insertMessage(
                                MessageEntity(
                                    id = netMsg.id,
                                    chatId = chatId,
                                    senderId = netMsg.senderId,
                                    receiverId = netMsg.receiverId,
                                    text = netMsg.text,
                                    mediaUrl = netMsg.mediaUrl,
                                    mediaType = netMsg.mediaType,
                                    timestamp = netMsg.timestamp,
                                    isRead = false
                                )
                            )

                            // Fetch sender profile details to create chat session correctly
                            val otherParticipantId = if (netMsg.senderId == myId) netMsg.receiverId else netMsg.senderId
                            var otherUser = database.userDao().getUserById(otherParticipantId)
                            if (otherUser == null) {
                                // Try syncing from cloud
                                try {
                                    val profileJson = NetworkServiceClient.kvdbApi.getValue(getBucketId(), "user_$otherParticipantId").string()
                                    val netUser = NetworkServiceClient.fromJsonUser(profileJson)
                                    if (netUser != null) {
                                        otherUser = UserEntity(netUser.id, netUser.name, netUser.avatarUrl, netUser.status, netUser.lastSeen)
                                        database.userDao().insertUser(otherUser)
                                    }
                                } catch (pe: Exception) {
                                    Log.e("ChatRepository", "Could not fetch user profile for $otherParticipantId", pe)
                                }
                            }

                            val otherName = otherUser?.name ?: otherParticipantId
                            val otherAvatar = otherUser?.avatarUrl ?: ""

                            // Update session
                            val unreadInc = if (netMsg.senderId != myId) 1 else 0
                            val existingSession = database.chatSessionDao().getAllSessionsFlow() // we'll just insert/replace
                            val lastText = if (netMsg.mediaType != "none") "[Media] ${netMsg.text}".trim() else netMsg.text
                            
                            database.chatSessionDao().insertSession(
                                ChatSessionEntity(
                                    chatId = chatId,
                                    participantId = otherParticipantId,
                                    participantName = otherName,
                                    participantAvatar = otherAvatar,
                                    lastMessageText = lastText,
                                    lastMessageTimestamp = netMsg.timestamp,
                                    unreadCount = unreadInc // simplified: we will calculate dynamically or increment
                                )
                            )
                            hasNew = true
                        }
                    } catch (e: Exception) {
                        Log.e("ChatRepository", "Failed to sync message key $key", e)
                    }
                }
            }
            hasNew
        } catch (e: Exception) {
            Log.e("ChatRepository", "Global message sync failed", e)
            false
        }
    }
}
