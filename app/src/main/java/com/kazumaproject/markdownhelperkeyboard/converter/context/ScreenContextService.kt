package com.kazumaproject.markdownhelperkeyboard.converter.context

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * necookey-exp: opt-in accessibility service that reads the visible screen text ONLY when the
 * keyboard asks for it (keyboard opened). It does not react to events and keeps nothing on disk.
 */
class ScreenContextService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) instance = null
        ContextWordCache.setScreenText("")
        super.onDestroy()
    }

    private fun collect(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && sb.length < MAX_CHARS && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (!node.isPassword && node.isVisibleToUser) {
                node.text?.let { if (it.isNotEmpty()) sb.append(it).append('\n') }
                node.contentDescription?.let { if (it.length in 2..200) sb.append(it).append('\n') }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_CHARS = 3000
        private const val MAX_NODES = 400
        @Volatile private var instance: ScreenContextService? = null

        /** Reads the screen off the main thread and fills [ContextWordCache]. No-op if disabled. */
        fun refreshAsync() {
            val service = instance ?: return
            Thread {
                val text = runCatching { service.collect() }.getOrDefault("")
                ContextWordCache.setScreenText(text)
            }.apply { isDaemon = true }.start()
        }
    }
}
