package com.fnmusic.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.fnmusic.tv.ui.FnMusicApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TvMusicApplication).container
        setContent {
            LaunchedEffect(Unit) {
                container.playbackController.connect()
                container.sessionRepository.restore()
            }
            FnMusicApp(container)
        }
    }

    override fun onDestroy() {
        if (isFinishing) (application as TvMusicApplication).container.playbackController.disconnect()
        super.onDestroy()
    }
}
