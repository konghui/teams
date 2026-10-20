package com.wrapper.teams

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * WebView that refuses to report itself as hidden.
 *
 * When the hosting Activity goes to the background, Android dispatches a
 * window-visibility change to the WebView. Chromium reacts by throttling the
 * page's JS timers and — after a while — freezing the whole renderer (the Page
 * Lifecycle "frozen" state). That stops Teams' realtime websocket and our
 * in-page toast observer, so new-message notifications silently dry up even
 * though the process is still alive.
 *
 * By always forwarding VISIBLE we keep the renderer "foregrounded" so it never
 * throttles or freezes. The cost is extra battery/CPU while backgrounded, which
 * is the accepted trade-off for a keep-alive wrapper.
 */
class KeepAliveWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Ignore GONE/INVISIBLE from backgrounding; always look visible.
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
