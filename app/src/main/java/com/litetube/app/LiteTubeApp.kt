package com.litetube.app

import android.app.Application

/**
 * Application-scoped update manager (see the update hub GUIDE.md, Part 4).
 *
 * Application scope is deliberate: checks run even when the app was only
 * opened briefly, and the Available state survives the detour to the
 * "Install unknown apps" settings screen, so returning to the app can
 * install the already-downloaded APK at once (MainActivity.onResume calls
 * installPendingUpdate() for exactly that). There is no release() call:
 * process death unregisters the network callback automatically.
 *
 * updateJsonUrl MUST point at your deployed web-updater's update.json
 * (GUIDE.md Part 1). The placeholder below serves nothing until it is
 * replaced with your real Vercel domain.
 */
class LiteTubeApp : Application() {

    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        updateManager = UpdateManager(
            context = this,
            updateJsonUrl = "https://littletube.vercel.app/update.json",
            autoDownload = true
        )
        updateManager.start()
    }
}
