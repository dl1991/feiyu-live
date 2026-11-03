package com.dl1991.feiyu

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.dl1991.feiyu.dafeiyu.DouyinA11yReader

/**
 * The app's own accessibility service. Its only job: while the 「抖音 App 评论」 source is on,
 * read the visible live-comment rows of the 抖音 app (config limits it to that package) and feed
 * them to [DouyinA11yReader]. It never clicks, types or reads any other app.
 */
class FyA11yService : AccessibilityService() {
    private var lastWalk = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!DouyinA11yReader.enabled || e.packageName?.toString() != DouyinA11yReader.PKG) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val now = System.currentTimeMillis()
        if (now - lastWalk < 400) return // 抖音 floods events: at most one tree walk per 400 ms
        lastWalk = now
        runCatching { DouyinA11yReader.onWindow(rootInActiveWindow) }
    }

    override fun onInterrupt() {}

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals("${ctx.packageName}/${FyA11yService::class.java.name}", true) || it.equals("${ctx.packageName}/.FyA11yService", true) }
        }
    }
}
