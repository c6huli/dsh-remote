package dev.dsh.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

/**
 * 开机动画开关。
 *
 * 打开后它只是盖在主界面上的一层遮罩（900ms 后淡出），主界面本身在下面照常
 * 组合——所以动画无论如何都不会再次变成"打都打不开"的原因。
 */
private const val SHOW_BOOT_SPLASH = true

@Composable
fun AppRoot(vm: AppViewModel) {
    // When the app returns to the foreground, re-establish streams and re-check state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onAppResumed()
    }

    var showSplash by remember { mutableStateOf(SHOW_BOOT_SPLASH) }
    LaunchedEffect(Unit) {
        if (!SHOW_BOOT_SPLASH) return@LaunchedEffect
        delay(900)
        showSplash = false
    }

    Box(Modifier.fillMaxSize()) {
        // 主界面在最底层，且永不被动画挡在后面。
        //
        // 之前是 Crossfade(booting) 二选一：主界面要等动画结束才第一次组合，动画
        // 那段出问题就永远停在启动画面——正是"打都打不开"的形状。
        MainScreen(vm)

        AnimatedVisibility(
            visible = showSplash,
            exit = fadeOut(tween(240)),
        ) {
            BootSplash()
        }

        // If the previous launch died, show its stack here — without a cable this
        // is the only way "闪退" can be diagnosed.
        CrashReportDialog()
    }
}
