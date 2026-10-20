package com.wrapper.teams

/**
 * Bridges native UI actions (answer / decline from notification or call screen)
 * back into the Teams web page. MainActivity registers its implementation while
 * alive; actions arriving while it is dead are queued and replayed on next bind.
 */
object CallController {

    interface WebDriver {
        /** Run raw JS inside the Teams page. */
        fun runJs(js: String)
        /** Bring the WebView/activity to the foreground. */
        fun bringToFront()
    }

    @Volatile
    private var driver: WebDriver? = null
    private val pending = ArrayDeque<String>()

    fun bind(d: WebDriver) {
        driver = d
        synchronized(pending) {
            while (pending.isNotEmpty()) d.runJs(pending.removeFirst())
        }
    }

    fun unbind(d: WebDriver) {
        if (driver === d) driver = null
    }

    private fun exec(js: String) {
        val d = driver
        if (d != null) d.runJs(js) else synchronized(pending) { pending.addLast(js) }
    }

    fun answer() {
        driver?.bringToFront()
        exec("window.$JS_INJECT_ANSWER && window.$JS_INJECT_ANSWER();")
    }

    fun decline() {
        exec("window.$JS_INJECT_DECLINE && window.$JS_INJECT_DECLINE();")
    }

    private const val JS_INJECT_ANSWER = "__teamsWrapperAnswer"
    private const val JS_INJECT_DECLINE = "__teamsWrapperDecline"
}
