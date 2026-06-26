package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatSessionEntity
import com.example.data.MessageEntity
import com.example.data.UserEntity
import com.example.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    application: Application,
    private val repository: ChatRepository
) : AndroidViewModel(application) {

    // --- Authentication & Profile State ---
    private val _isRegistered = MutableStateFlow(repository.getMyUserId() != null)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _myProfile = MutableStateFlow<UserEntity?>(null)
    val myProfile: StateFlow<UserEntity?> = _myProfile.asStateFlow()

    private val _isRegistering = MutableStateFlow(false)
    val isRegistering: StateFlow<Boolean> = _isRegistering.asStateFlow()

    private val _registrationError = MutableStateFlow<String?>(null)
    val registrationError: StateFlow<String?> = _registrationError.asStateFlow()

    // --- Search Users State ---
    private val _searchResults = MutableStateFlow<List<UserEntity>>(emptyList())
    val searchResults: StateFlow<List<UserEntity>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // --- Active Chat Selection State ---
    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()

    private val _currentParticipant = MutableStateFlow<UserEntity?>(null)
    val currentParticipant: StateFlow<UserEntity?> = _currentParticipant.asStateFlow()

    // --- Global Sync State ---
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // --- Active Chat Message Stream ---
    val currentMessages: StateFlow<List<MessageEntity>> = _currentChatId
        .flatMapLatest { chatId ->
            if (chatId != null) {
                repository.getLocalMessages(chatId)
            } else {
                flowOf(emptyList())
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // --- Active Chats Session Stream ---
    val chatSessions: StateFlow<List<ChatSessionEntity>> = repository.getLocalSessions()
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Background poller job
    private var syncJob: Job? = null

    init {
        loadMyProfile()
        startPeriodicSync()
    }

    private fun loadMyProfile() {
        val myId = repository.getMyUserId()
        if (myId != null) {
            _myProfile.value = UserEntity(
                id = myId,
                name = repository.getMyName() ?: "Me",
                avatarUrl = repository.getMyAvatar() ?: "",
                status = repository.getMyStatus() ?: "Available",
                lastSeen = System.currentTimeMillis()
            )
        }
    }

    fun getBucketId(): String = repository.getBucketId()
    
    fun setBucketId(bucket: String) {
        repository.setBucketId(bucket)
        // Force refresh
        syncAll()
    }

    fun register(id: String, name: String, avatarUrl: String, status: String) {
        if (id.trim().isEmpty() || name.trim().isEmpty()) {
            _registrationError.value = "Username ID and Name cannot be empty"
            return
        }

        viewModelScope.launch {
            _isRegistering.value = true
            _registrationError.value = null
            val success = repository.registerOrUpdateProfileOnCloud(
                id = id.trim().lowercase(),
                name = name.trim(),
                avatarUrl = avatarUrl,
                status = status.trim().ifEmpty { "Hi, I'm using D-Chat!" }
            )
            if (success) {
                _isRegistered.value = true
                loadMyProfile()
            } else {
                _registrationError.value = "Failed to register profile. Please try again."
            }
            _isRegistering.value = false
        }
    }

    fun updateProfile(name: String, avatarUrl: String, status: String) {
        val myId = repository.getMyUserId() ?: return
        viewModelScope.launch {
            repository.registerOrUpdateProfileOnCloud(myId, name, avatarUrl, status)
            loadMyProfile()
        }
    }

    fun searchUsers(query: String) {
        if (query.trim().isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            val results = repository.searchUsersOnCloud(query)
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun selectChat(otherParticipantId: String, otherName: String, otherAvatar: String) {
        val myId = repository.getMyUserId() ?: return
        val chatId = if (myId < otherParticipantId) "${myId}_${otherParticipantId}" else "${otherParticipantId}_${myId}"
        
        _currentChatId.value = chatId
        _currentParticipant.value = UserEntity(
            id = otherParticipantId,
            name = otherName,
            avatarUrl = otherAvatar,
            status = "Online",
            lastSeen = System.currentTimeMillis()
        )

        viewModelScope.launch {
            repository.clearChatUnreadCount(chatId)
        }
    }

    fun clearActiveChat() {
        _currentChatId.value = null
        _currentParticipant.value = null
    }

    fun sendMessage(text: String, mediaUri: Uri? = null, mediaType: String = "none") {
        val participant = _currentParticipant.value ?: return
        viewModelScope.launch {
            val success = repository.sendChatMessage(
                receiverId = participant.id,
                receiverName = participant.name,
                receiverAvatar = participant.avatarUrl,
                text = text,
                mediaUri = mediaUri,
                mediaType = mediaType
            )
            if (!success) {
                Log.e("ChatViewModel", "Message sending failed")
            }
        }
    }

    fun syncAll() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                repository.syncAllMessages()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Sync Error", e)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                if (repository.getMyUserId() != null) {
                    try {
                        repository.syncAllMessages()
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Periodic sync error", e)
                    }
                }
                delay(3000) // Poll every 3 seconds for messages
            }
        }
    }

    fun logout() {
        repository.clearMyProfile()
        _isRegistered.value = false
        _myProfile.value = null
        _currentChatId.value = null
        _currentParticipant.value = null
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
    }
}

class ChatViewModelFactory(
    private val application: Application,
    private val repository: ChatRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
