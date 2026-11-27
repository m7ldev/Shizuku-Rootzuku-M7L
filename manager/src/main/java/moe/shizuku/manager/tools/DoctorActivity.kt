package moe.shizuku.manager.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.home.ServiceControlActivity
import moe.shizuku.manager.stealth.StealthController
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

/**
 * Service doctor: checks everything Shizuku needs to start and to keep running, and takes
 * the user to the place where each problem can be fixed.
 */
class DoctorActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
    }

    override fun onResume() {
        super.onResume()
        if (ready) show()
    }

    private fun global(name: String): Int {
        return try {
            Settings.Global.getInt(contentResolver, name, 0)
        } catch (e: Throwable) {
            0
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }

    /**
     * @param ok true: fine, false: a problem, null: only information
     */
    private fun check(ok: Boolean?, title: Int, problem: Int, fix: (() -> Unit)? = null): Row {
        return Row(
            title = getString(title),
            summary = if (ok == true) getString(R.string.doctor_ok) else getString(problem),
            icon = when (ok) {
                true -> R.drawable.ic_check_24
                false -> R.drawable.ic_warning_24
                null -> R.drawable.ic_outline_info_24
            },
            iconBackground = when (ok) {
                true -> R.drawable.tool_icon_green
                false -> R.drawable.tool_icon_red
                null -> R.drawable.tool_icon_gray
            },
            onClick = if (ok == true) null else fix
        )
    }

    private fun show() {
        val rows = ArrayList<Row>()
        val running = Shizuku.pingBinder()
        val developerSettings = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

        rows.add(check(running, R.string.doctor_service, R.string.doctor_service_problem) {
            open(Intent(this, ServiceControlActivity::class.java))
        })

        if (running) {
            val current = try {
                Shizuku.getVersion() == Shizuku.getLatestServiceVersion()
                        && Shizuku.getServerPatchVersion() == ShizukuApiConstants.SERVER_PATCH_VERSION
            } catch (e: Throwable) {
                false
            }
            rows.add(check(current, R.string.doctor_version, R.string.doctor_version_problem) {
                open(Intent(this, ServiceControlActivity::class.java))
            })
        }

        if (StealthController.isActive()) {
            rows.add(check(false, R.string.stealth_title, R.string.stealth_active_summary) {
                open(Intent(this, StealthActivity::class.java))
            })
        }

        // A service started with root does not depend on the debugging settings
        val root = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        if (!root) {
            rows.add(
                check(
                    global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1,
                    R.string.doctor_developer_options, R.string.doctor_developer_options_problem
                ) { open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            )
            rows.add(
                check(
                    global(Settings.Global.ADB_ENABLED) == 1,
                    R.string.doctor_usb_debugging, R.string.doctor_usb_debugging_problem
                ) { open(developerSettings) }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wifi = isOnWifi()
                rows.add(check(wifi, R.string.doctor_wifi, R.string.doctor_wifi_problem) {
                    open(Intent(Settings.ACTION_WIFI_SETTINGS))
                })
                // Once Shizuku runs, wireless debugging may be off again
                rows.add(
                    check(
                        if (global("adb_wifi_enabled") == 1) true else if (running) null else false,
                        R.string.doctor_wireless_debugging,
                        if (running) R.string.doctor_wireless_debugging_off_ok else R.string.doctor_wireless_debugging_problem
                    ) { open(developerSettings) }
                )
            }
            rows.add(
                check(
                    checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
                    R.string.doctor_secure_settings, R.string.doctor_secure_settings_problem
                ) { open(Intent(this, ServiceControlActivity::class.java)) }
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                rows.add(check(null, R.string.doctor_autostart, R.string.doctor_autostart_old_android))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rows.add(
                check(
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    R.string.doctor_notifications, R.string.doctor_notifications_problem
                ) { open(appDetails) }
            )
        }

        val power = getSystemService(PowerManager::class.java)
        rows.add(
            check(
                power?.isIgnoringBatteryOptimizations(packageName) == true,
                R.string.doctor_battery, R.string.doctor_battery_problem
            ) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        )

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            // There is no public way to read this setting, so it can only be pointed out
            rows.add(check(null, R.string.doctor_auto_blocker, R.string.doctor_auto_blocker_info) {
                open(Intent(Settings.ACTION_SECURITY_SETTINGS))
            })
        }

        showRows(rows)
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Throwable) {
            false
        }
    }
}
