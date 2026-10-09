package us.fishfarts.modhub

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ModHub's optional "restart helper". It does exactly one thing, and only when ModHub arms it right after an addon import:
 * on Minecraft's App info page in Settings it presses "Force stop" and then the confirmation button.
 * Android itself restricts it to the Settings app (android:packageNames in restart_a11y_config.xml). It does not read,
 * store or send anything, and it is idle (ignores every event) unless armed.
 */
class RestartAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: RestartAccessibilityService? = null
        @Volatile private var armedUntil = 0L
        @Volatile private var label = ""
        /** 0 = waiting for the Force stop button, 1 = pressed it (waiting for the confirmation), 2 = finished. */
        @Volatile var stage = 0
        @Volatile var note = ""

        fun arm(appLabel: String, seconds: Int = 25) {
            label = appLabel.lowercase(); stage = 0; note = ""
            armedUntil = System.currentTimeMillis() + seconds * 1000L
        }
        fun disarm() { armedUntil = 0L }
        val isFinished get() = stage == 2
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { tick() }

    /** Called on events and also polled by the import service while armed. */
    private var lastLog = 0L
    private fun dbg(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastLog > 1500) { lastLog = now; Log.i("ModHubHelper", "stage=$stage $msg") }
    }

    @Synchronized
    fun tick() {
        if (System.currentTimeMillis() > armedUntil || stage == 2) return
        val roots = settingsRoots()
        if (roots.isEmpty()) { dbg("no Settings window visible yet"); return }
        try {
            if (stage == 1) {
                // The confirmation dialog's positive button. Classic dialogs use android:id/button1; newer Settings (Compose)
                // has no ids, just a clickable box around the text "OK" (or "Force stop").
                for (root in roots) {
                    // Only the dialog's own window: it has a "Cancel" button. (The page behind it also still contains "Force stop".)
                    if (findNodes(root) { t -> t.equals("Cancel", true) }.isEmpty()) continue
                    var b = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull { it.isEnabled }
                    if (b == null) b = findNodes(root) { t -> t.equals("OK", true) || t.equals("Force stop", true) }.firstOrNull { it.isEnabled }
                    if (b != null) {
                        Log.i("ModHubHelper", "confirming")
                        var n: AccessibilityNodeInfo? = b
                        while (n != null && !n.isClickable) n = n.parent
                        (n ?: b).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        stage = 2; note = "confirmed"; disarm()
                        return
                    }
                }
                dbg("waiting for the confirmation button; windows=${roots.size}")
                return
            }
            // Only act on Minecraft's own page: its name must be on screen next to the button.
            val root = roots.firstOrNull { r -> findNodes(r) { t -> t.lowercase().contains(label) }.isNotEmpty() }
            if (root == null) { dbg("Minecraft's page not found in ${roots.size} Settings window(s)"); return }
            val btn = findNodes(root) { t -> t.equals("Force stop", ignoreCase = true) }.firstOrNull()
            if (btn == null) { dbg("no Force stop node, scrolling"); scrollPage(root); return }
            if (!btn.isEnabled) { stage = 2; note = "already stopped"; Log.i("ModHubHelper", "Force stop greyed out: app not running"); disarm(); return }   // greyed out = not running
            var n: AccessibilityNodeInfo? = btn
            while (n != null && !n.isClickable) n = n.parent
            val ok = (n ?: btn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i("ModHubHelper", "clicked Force stop (clickable ancestor=${n != null}) result=$ok")
            stage = 1; note = "pressed Force stop"
        } finally { roots.forEach { runCatching { it.recycle() } } }
    }

    /**
     * Every Settings window, found by scanning all windows rather than trusting "the active window": on some devices (and the
     * emulator) there is no input-focused window, which makes rootInActiveWindow null even while Settings is on screen, and
     * Settings can have several windows (a trampoline plus the real page). Only Settings windows can return content at all
     * (android:packageNames), so other apps' windows come back null.
     */
    private fun settingsRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.takeIf { it.packageName?.toString() == "com.android.settings" }?.let { out.add(it) }
        for (w in windows) {
            val r = w.root ?: continue
            if (r.packageName?.toString() == "com.android.settings") out.add(r) else r.recycle()
        }
        return out
    }

    /** All nodes (under [root]) whose text or content description satisfies [pred]. Own walk: findAccessibilityNodeInfosByText finds nothing in newer Compose-based Settings pages. */
    private fun findNodes(root: AccessibilityNodeInfo, pred: (String) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > 3000 || depth > 40) return
            val t = n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
            if (t.isNotEmpty() && pred(t)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    private fun scrollPage(root: AccessibilityNodeInfo) {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
            return null
        }
        find(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }
}
