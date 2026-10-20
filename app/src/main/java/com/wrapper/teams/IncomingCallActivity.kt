package com.wrapper.teams

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wrapper.teams.databinding.ActivityCallBinding

/** Full-screen incoming-call UI shown over the lock screen. */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val caller = intent.getStringExtra(Constants.EXTRA_CALLER) ?: "未知来电"
        binding.callerName.text = caller

        binding.answerButton.setOnClickListener {
            CallController.answer()
            NotificationHelper.cancelCall(this)
            finish()
        }
        binding.declineButton.setOnClickListener {
            CallController.decline()
            NotificationHelper.cancelCall(this)
            finish()
        }
    }

    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}
