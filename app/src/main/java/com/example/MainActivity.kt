package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.repository.ChatRepository
import com.example.ui.ChatViewModel
import com.example.ui.ChatViewModelFactory
import com.example.ui.MainApp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Room Database
        val database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "dchat_deny_database"
        ).fallbackToDestructiveMigration().build()

        // Initialize ChatRepository
        val repository = ChatRepository(applicationContext, database)

        // Initialize ChatViewModel using Factory
        val viewModel = ViewModelProvider(
            this,
            ChatViewModelFactory(application, repository)
        )[ChatViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainApp(
                    viewModel = viewModel,
                    repository = repository
                )
            }
        }
    }
}
