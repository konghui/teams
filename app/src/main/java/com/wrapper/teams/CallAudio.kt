package com.wrapper.teams

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Routes WebRTC call audio through Android's communication (voice-call) path
 * while a Teams call is connected. Without this, WebView WebRTC audio plays on
 * the media stream: quiet, and notifications sound comparatively huge. Entering
 * MODE_IN_COMMUNICATION + taking voice-comm audio focus makes the call audio use
 * the louder voice-call volume and ducks notifications.
 */
object CallAudio {

    @Volatile
    private var inCall = false
    private var focusRequest: AudioFocusRequest? = null

    @Synchronized
    fun setInCall(context: Context, active: Boolean) {
        if (active == inCall) return
        inCall = active
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (active) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN
                )
            }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            // Hands-free + loud by default; mirrors how most people use a wrapped
            // Teams like a phone app.
            setSpeaker(am, true)
        } else {
            setSpeaker(am, false)
            am.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    private fun setSpeaker(am: AudioManager, on: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }
}
