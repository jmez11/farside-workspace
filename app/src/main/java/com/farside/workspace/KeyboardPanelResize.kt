package com.farside.workspace

import android.util.Log
import android.view.View
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import metavrx.layout.window.compose.state.WindowState

/** Mask the compositor's old buffer until the compact buffer has been submitted. */
internal object KeyboardPanelResize {
    @Volatile internal var committedCompactions = 0
        private set
    @Volatile internal var committedHeight = 0
        private set
    suspend fun compact(state: WindowState, height: Int, resize: () -> Unit) {
        val token = (state.javaClass.methods.single { it.name.startsWith("getToken") }.invoke(state) as StateFlow<*>).value ?: return
        val manager = state.javaClass.methods.single { it.name.startsWith("getAttachedWindowManager") }.invoke(state) ?: return
        val builderClass = Class.forName("metavr.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
        fun opacity(value: Float) {
            val builder = builderClass.getConstructor().newInstance()
            builderClass.getMethod("setSurfaceAlpha", token.javaClass, Float::class.javaPrimitiveType).invoke(builder, token, value)
            val transaction = builderClass.getMethod("build").invoke(builder)
            manager.javaClass.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
            Log.d("KeyboardResize", "surface alpha=$value")
        }
        // Keep cancellation/rapid toggles from exposing a resized window with its old buffer.
        withContext(NonCancellable) {
            try {
                opacity(0.5f)
                withFrameNanos { }
                opacity(0f)
                repeat(2) { withFrameNanos { } }
                resize()
                Log.d("KeyboardResize", "compact geometry requested")
                val view = state.javaClass.methods.single { it.name.startsWith("getHostedView") }.invoke(state) as? View ?: return@withContext
                withTimeout(750) {
                    while (view.height != height) { withFrameNanos { } }
                    val submitted = CompletableDeferred<Unit>()
                    val callback = Runnable {
                        if (view.height == height) {
                            committedHeight = view.height
                            committedCompactions++
                            submitted.complete(Unit)
                        }
                    }
                    val observer = view.viewTreeObserver
                    observer.registerFrameCommitCallback(callback)
                    try { view.invalidate(); submitted.await() }
                    finally { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
                }
                repeat(2) { withFrameNanos { } }
                Log.d("KeyboardResize", "compact buffer committed")
                opacity(0.5f)
                withFrameNanos { }
            } catch (error: Exception) {
                Log.w("KeyboardResize", "Cannot synchronize compact buffer", error)
            } finally {
                try { opacity(1f) } catch (error: Exception) { Log.d("KeyboardResize", "Key surface already removed", error) }
            }
        }
    }
}
