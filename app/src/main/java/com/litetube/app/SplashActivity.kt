package com.litetube.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starting screen (v1.10.0, YouTube/VidMate style): the LittleTube logo
 * on dark navy while the app starts, then hands over to MainActivity.
 * The 1.5s hold is deliberate — it is the branded start the user sees
 * every launch, the same way VidMate holds its logo. The home feed
 * starts loading only after the handover, so the splash never blocks
 * on the network. Nothing on this screen is focusable, so D-Pad users
 * can never get stuck here; the handover is automatic.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        lifecycleScope.launch {
            delay(1500)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
