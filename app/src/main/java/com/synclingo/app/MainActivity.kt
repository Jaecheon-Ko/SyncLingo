package com.synclingo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.synclingo.app.ui.LibraryScreen
import com.synclingo.app.ui.PlayerScreen
import com.synclingo.app.ui.SyncTheme

class MainActivity : ComponentActivity() {
    /** URL shared from the YouTube app, consumed by the library screen. */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            SyncTheme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                val id = openId
                if (id == null) {
                    LibraryScreen(
                        sharedText = sharedText.value,
                        onSharedConsumed = { sharedText.value = null },
                        onOpen = { openId = it },
                    )
                } else {
                    BackHandler { openId = null }
                    PlayerScreen(itemId = id, onBack = { openId = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
        }
    }
}
