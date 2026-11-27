package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.monitor.MonitorService
import moe.shizuku.manager.starter.ServiceController
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.stealth.StealthController
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        if (UserHandleCompat.myUserId() > 0) return

        // The service did not survive the reboot, a stop requested before it does not count
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED == intent.action) {
            ShizukuSettings.setUserStopped(false)
            StealthController.reset()
        }
        MonitorService.sync(context)

        if (Shizuku.pingBinder()) return

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB
            && ServiceController.canAutoStart(context)) {
            // Wait for Wi-Fi and retry in a job instead of giving up after a few seconds
            BootStartJobService.schedule(context)
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    private fun rootStart(context: Context) {
        if (!Shell.getShell().isRoot) {
            //NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
            Shell.getCachedShell()?.close()
            return
        }

        Shell.cmd(Starter.internalCommand).exec()
    }
}
