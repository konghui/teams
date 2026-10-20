package com.wrapper.teams

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * Receives events from the injected JS running inside the Teams web page.
 * All methods are called on a background WebView JS thread — keep them cheap
 * and thread-safe.
 */
class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun onMessage(title: String?, body: String?, tag: String?, chatId: String?) {
        NotificationHelper.showMessage(
            context,
            title ?: "Teams",
            body ?: "",
            tag ?: System.currentTimeMillis().toString(),
            chatId ?: ""
        )
    }

    @JavascriptInterface
    fun onIncomingCall(caller: String?, callId: String?) {
        NotificationHelper.showIncomingCall(
            context,
            caller ?: "未知来电",
            callId ?: ""
        )
    }

    @JavascriptInterface
    fun onCallEnded(callId: String?) {
        NotificationHelper.cancelCall(context)
    }

    /** Connected-call state → switch Android audio routing for the call. */
    @JavascriptInterface
    fun onCallStateChanged(active: Boolean) {
        CallAudio.setInCall(context, active)
    }

    /** Simple log passthrough for debugging the injected script. */
    @JavascriptInterface
    fun log(msg: String?) {
        android.util.Log.d("TeamsInject", msg ?: "")
    }
}
