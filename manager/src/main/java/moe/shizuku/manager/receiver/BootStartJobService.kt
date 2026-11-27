package moe.shizuku.manager.receiver

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.UserManager
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceController
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Starts Shizuku through wireless debugging after boot. Unlike a broadcast receiver, a job can
 * wait until Wi-Fi is connected and has enough time to retry.
 */
class BootStartJobService : JobService() {

    companion object {
        private const val JOB_ID = 1001
        private const val MAX_ATTEMPTS = 5

        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                // Both LOCKED_BOOT_COMPLETED and BOOT_COMPLETED arrive, schedule only once
                if (scheduler.getPendingJob(JOB_ID) != null) return
                ShizukuSettings.setBootStartAttempts(0)

                val builder = JobInfo.Builder(JOB_ID, ComponentName(context, BootStartJobService::class.java))
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_LINEAR)
                if (EnvironmentUtils.getAdbTcpPort() > 0) {
                    // adb on a fixed TCP port is reachable through localhost without any network
                    builder.setMinimumLatency(1000)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Wireless debugging needs Wi-Fi, no matter if it is metered or not
                    builder.setRequiredNetwork(
                        NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build()
                    )
                } else {
                    builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                }
                scheduler.schedule(builder.build())
            } catch (e: Throwable) {
                Log.w(AppConstants.TAG, "schedule boot start job", e)
            }
        }
    }

    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Shizuku.pingBinder() || ShizukuSettings.isUserStopped()) {
            return false
        }

        // The adb key may not be readable before the first unlock, such attempts are not counted
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked != false
        val attempt = ShizukuSettings.getBootStartAttempts() + (if (unlocked) 1 else 0)
        ShizukuSettings.setBootStartAttempts(attempt)

        val context = applicationContext
        worker = thread(name = "shizuku-boot-start") {
            val ok = try {
                ServiceController.start(context)
            } catch (e: Throwable) {
                Log.w(AppConstants.TAG, "start on boot", e)
                false
            }
            Log.i(AppConstants.TAG, "start on boot, attempt $attempt: $ok")
            jobFinished(params, !ok && attempt < MAX_ATTEMPTS)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Wi-Fi went away or the job ran out of time
        worker?.interrupt()
        worker = null
        return !Shizuku.pingBinder() && ShizukuSettings.getBootStartAttempts() < MAX_ATTEMPTS
    }
}
