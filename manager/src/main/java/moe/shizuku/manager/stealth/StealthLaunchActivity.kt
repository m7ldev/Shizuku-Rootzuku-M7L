package moe.shizuku.manager.stealth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import moe.shizuku.manager.R

/**
 * Opens an app in stealth mode: the developer settings are turned off first, the app is
 * started a moment later. Home screen shortcuts point here. It has no interface of its own.
 */
class StealthLaunchActivity : Activity() {

    companion object {
        private const val EXTRA_PACKAGE = "package"

        /** The settings need a moment to reach other apps. */
        private const val LAUNCH_DELAY = 700L

        fun intent(context: Context, packageName: String): Intent {
            return Intent(context, StealthLaunchActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = intent.getStringExtra(EXTRA_PACKAGE)
        val launch = target?.let { packageManager.getLaunchIntentForPackage(it) }
        if (target == null || launch == null) {
            Toast.makeText(this, R.string.stealth_app_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!StealthController.enter(this, target)) {
            // Better to say it than to open the app unprotected
            Toast.makeText(this, R.string.stealth_no_permission, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Toast.makeText(this, R.string.stealth_entering, Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Throwable) {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
            }
            finish()
        }, LAUNCH_DELAY)
    }
}
