package dev.dsh.remote.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
fun AppRoot(vm: AppViewModel) {
    // When the app returns to the foreground, re-establish streams and re-check state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onAppResumed()
    }

    // Settings are a screen *inside* MainScreen, so the navigation drawer (sidebar)
    // wraps them too instead of being replaced by it.
    MainScreen(vm)
}
