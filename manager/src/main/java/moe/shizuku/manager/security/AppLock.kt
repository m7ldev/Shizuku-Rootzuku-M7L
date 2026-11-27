package moe.shizuku.manager.security

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

/**
 * Optional lock: the app and the permission dialog ask for the fingerprint, face or screen
 * lock of the device before they show anything.
 */
object AppLock {

    /** How long the app may stay in the background before it locks again. */
    private const val RELOCK_AFTER = 30_000L

    private var unlocked = false
    private var backgroundSince = 0L
    private var startedActivities = 0

    /** The system prompt with the screen lock as a fallback exists since Android 10. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isEnabled(activity: Activity): Boolean {
        if (!isSupported || !ShizukuSettings.isAppLockEnabled()) return false
        // Without any screen lock there is nothing to ask for, do not lock the user out
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }

    fun onActivityStarted() {
        startedActivities++
        if (backgroundSince != 0L && SystemClock.elapsedRealtime() - backgroundSince > RELOCK_AFTER) {
            unlocked = false
        }
        backgroundSince = 0L
    }

    fun onActivityStopped() {
        startedActivities--
        if (startedActivities <= 0) {
            startedActivities = 0
            backgroundSince = SystemClock.elapsedRealtime()
        }
    }

    fun needsUnlock(activity: Activity): Boolean {
        return isEnabled(activity) && !unlocked
    }

    /**
     * Shows the system prompt. [onResult] is called on the main thread with true after a
     * successful check and with false when it failed or was cancelled.
     *
     * @param remember keep the app unlocked afterwards, false for single confirmations
     */
    fun authenticate(activity: Activity, remember: Boolean, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult(true)
            return
        }

        try {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.app_lock_prompt_title, activity.getString(R.string.app_name)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }

            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (remember) unlocked = true
                        onResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        onResult(false)
                    }
                }
            )
        } catch (e: Throwable) {
            // A broken prompt must not make the app unusable
            if (remember) unlocked = true
            onResult(true)
        }
    }
}
