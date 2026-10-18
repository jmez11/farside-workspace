package com.farside.workspace

import android.app.Instrumentation
import java.util.concurrent.atomic.AtomicInteger

internal class WindowLaunchVerification(private val test: Instrumentation) {
    fun run(): String {
        check(!WindowPlacementSupport.isAvailable { 207 })
        check(!WindowPlacementSupport.isAvailable { 208 })
        check(WindowPlacementSupport.isAvailable { 209 })
        check(WindowPlacementSupport.isAvailable { 210 })
        check(!WindowPlacementSupport.isAvailable { throw NoClassDefFoundError("Missing Meta OS API") })
        check(!WindowPlacementSupport.isAvailable { throw SecurityException("Version unavailable") })
        // Actual native click/drag/drop; visible+drawn callback must complete the click request.
        SessionLifecycleVerification(test).run("launch")
        var pending = -1
        test.runOnMainSync { pending = WindowLaunchMonitor.pendingCount }
        check(pending == 0) { "Presented/canceled requests still pending: $pending" }
        val failures = AtomicInteger()
        test.runOnMainSync {
            // More requests than the formerly hardcoded cap: none may be blocked by counting.
            repeat(9) {
                val id = WindowLaunchMonitor.prepare(test.targetContext) { failures.incrementAndGet() }
                WindowLaunchMonitor.watch(id)
                WindowLaunchMonitor.complete(id)
            }
            val timeout = WindowLaunchMonitor.prepare(test.targetContext) { failures.incrementAndGet() }
            WindowLaunchMonitor.watch(timeout)
        }
        Thread.sleep(10_500)
        test.runOnMainSync {
            check(failures.get() == 1) { "Expected exactly one timeout failure, got ${failures.get()}" }
            check(WindowLaunchMonitor.pendingCount == 0)
        }
        return "Real launch presentation and canceled drags complete; no fixed cap; unacknowledged request reports once, acknowledged/canceled requests stay silent."
    }
}
