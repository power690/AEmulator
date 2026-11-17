/* AEmulator Sunset addition, 2026-10-07. GPL-3.0. */
package app.aemu.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.File

/** Independent of VM window visibility. Binder calls never run on the UI or boot thread. */
internal class HostBatteryBridge(private val ctx: Context, private val root: File,
    private val api: Int, defaultEnabled: Boolean, private val ready: () -> Boolean, private val generation: () -> Int,
    private val run: (String) -> Unit, private val log: (String) -> Unit) {
    @Volatile var following = defaultEnabled
        private set
    @Volatile var latest: HostBatteryState? = null
        private set
    @Volatile private var active = false
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var registered = false
    private var lastWritten: HostBatteryState? = null
    private var lastApplied: Pair<HostBatteryState, Int>? = null
    private val saved = File(root, "data/.aemu-battery")
    private fun savedState(): HostBatteryState? = runCatching {
        val p = saved.readText().trim().split(' ')
        if (p.size != 3) return null
        val v = HostBatteryState(p[0].toInt(), p[1].toInt(), p[2].toInt())
        if (v.percent in 0..100 && v.status in 1..5 && v.plugged in 0..7) v else null
    }.getOrNull()
    private fun saveState(v: HostBatteryState) {
        runCatching { saved.parentFile?.mkdirs(); saved.writeText("${v.percent} ${v.status} ${v.plugged}\n") }
            .onFailure { log("battery: ${it.message}") }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { read(intent) }
    }
    private fun read(intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        val value = HostBatteryState.from(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1), intent.getIntExtra(BatteryManager.EXTRA_STATUS, 1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
        val changed = value != latest
        latest = value
        if (changed && value != null && active && following) handler?.post { if (active && following) apply(value) }
    }
    fun start() {
        if (active) return
        val sticky = runCatching {
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrElse { log("battery: host receiver unavailable: ${it.message}"); return }
        read(sticky)
        registered = true
        val initial = if (following) latest else savedState() ?: HostBatteryState.from(100, 100, 5, 1)
        initial?.let { runCatching { it.writeSysfs(root); lastWritten = it }.onFailure { log("battery: ${it.message}") } }
        active = true
        worker = HandlerThread("host-battery").also { it.start() }
        handler = Handler(worker!!.looper)
        handler!!.post(tick)
        log("battery: host following=${following}, initial=${latest?.percent}")
    }
    private fun apply(value: HostBatteryState, attempt: Int = 0) {
        if (!active) return
        val changed = lastWritten != value
        val done = runCatching {
            if (changed) { value.writeSysfs(root); lastWritten = value }
            // Gingerbread/ICS lack the dumpsys override; retain their sysfs path.
            // Reapply after changes/restarts, not five binder calls on every poll.
            val current = generation()
            val key = value to current
            if (api >= 17 && ready() && lastApplied != key) {
                val command = value.command(api, lastApplied?.takeIf { it.second == current }?.first)
                if (command.isNotEmpty()) { run(command); lastApplied = key }
            }
        }.onFailure { log("battery: ${it.message}") }.isSuccess
        if (changed) restartHealth()
        if (!done && attempt < 2 && active && following)
            handler?.postDelayed({ if (active && following) apply(value, attempt + 1) }, 1_200)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (following) latest?.let { apply(it) }
            if (active) handler?.postDelayed(this, 15_000)
        }
    }
    fun setFollowing(enabled: Boolean) {
        following = enabled
        handler?.post {
            if (!active) return@post
            lastWritten = null
            lastApplied = null
            val value = latest ?: return@post
            if (enabled) apply(value) else runCatching { saveState(value) }
        }
    }
    fun applyManual(percent: Int, charging: Boolean) {
        following = false
        val value = HostBatteryState.from(percent, 100, if (charging) 2 else 3, if (charging) 1 else 0)!!
        handler?.post {
            if (active) {
                runCatching {
                    value.writeSysfs(root); lastWritten = value; saveState(value)
                    if (api >= 17 && ready()) run(value.command(api))
                }.onFailure { log("battery: ${it.message}") }
                restartHealth()
            }
        }
    }
    fun reset(defaultEnabled: Boolean) {
        following = defaultEnabled
        handler?.post {
            if (active && !following) {
                runCatching { HostBatteryState.from(100, 100, 5, 1)!!.writeSysfs(root) }
                runCatching { saved.delete() }
                restartHealth()
            }
            if (active && ready() && api >= 17) runCatching { run("dumpsys battery reset") }
            if (active) { lastWritten = null; lastApplied = null; handler?.removeCallbacks(tick); handler?.post(tick) }
        }
    }
    private fun restartHealth() {
        if (api < 30 || !ready()) return
        runCatching { run("setprop ctl.restart vendor.health-default") }.onFailure { log("battery: ${it.message}") }
    }
    fun stop() {
        active = false
        if (registered) { runCatching { ctx.unregisterReceiver(receiver) }; registered = false }
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        // GuestRunner's command timeout plus reader join are bounded to six seconds.
        worker?.join(7_000)
        worker = null; handler = null
    }
}
