package com.fnmusic.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.fnmusic.tv.ui.FnMusicApp
import com.fnmusic.tv.update.UpdateEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isExiting = false
    private val appContainer: AppContainer get() = (application as TvMusicApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            FnMusicApp(container) { exitApplication(container) }
        }
        lifecycleScope.launch {
            container.updateCoordinator.effects.collectLatest { effect ->
                when (effect) {
                    is UpdateEffect.LaunchIntent -> runCatching { startActivity(effect.intent) }
                        .onFailure { container.updateCoordinator.handleIntentLaunchFailure() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.updateCoordinator.onForeground()
    }

    override fun onResume() {
        super.onResume()
        appContainer.updateCoordinator.onResumeFromSystem()
    }

    override fun onStop() {
        appContainer.updateCoordinator.onBackground(isChangingConfigurations)
        super.onStop()
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
