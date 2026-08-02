package com.fnmusic.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.fnmusic.tv.ui.FnMusicApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isExiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TvMusicApplication).container
        setContent {
            FnMusicApp(container) { exitApplication(container) }
        }
    }

    private fun exitApplication(container: AppContainer) {
        if (isExiting) return
        isExiting = true
        lifecycleScope.launch {
            try {
                container.shutdownForExit()
            } finally {
                finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
