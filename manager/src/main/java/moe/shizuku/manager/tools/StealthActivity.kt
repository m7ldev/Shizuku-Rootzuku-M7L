package moe.shizuku.manager.tools

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.stealth.StealthController
import moe.shizuku.manager.stealth.StealthLaunchActivity
import moe.shizuku.manager.stealth.StealthService
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Hidden apps: the apps protected by stealth mode as a grid of cards. Tapping a card opens
 * the app with the developer settings turned off.
 */
class StealthActivity : ToolListActivity() {

    override val requiresService: Boolean = false
    override val searchable: Boolean = true

    private val grid = CardGridAdapter()
    private var entrancePlayed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        while (binding.list.itemDecorationCount > 0) {
            binding.list.removeItemDecorationAt(0)
        }
        binding.list.layoutManager = CardGridAdapter.layoutManager(binding.list, grid)
        binding.list.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_grid)
        binding.list.adapter = grid

        grantUsageAccess()
    }

    override fun onResume() {
        super.onResume()
        if (ready) show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menu.add(0, 1, 0, R.string.stealth_choose_apps)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, StealthPickActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onQueryChanged(query: String) {
        show()
    }

    /**
     * Noticing that the user left the protected app needs "usage access". While Shizuku is
     * running this app can give it to itself, which saves the user a trip to the settings.
     */
    private fun grantUsageAccess() {
        if (StealthController.hasUsageAccess(this) || !Shizuku.pingBinder()) return
        runAsync({
            ShizukuShell.exec(
                "appops set --user ${UserHandleCompat.myUserId()} ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow"
            )
        }) { show() }
    }

    private fun show() {
        val cards = ArrayList<Card>()
        val pm = packageManager
        val q = query.trim().lowercase(Locale.getDefault())

        // The button for adding apps always comes first
        if (q.isEmpty()) {
            cards.add(
                Card(
                    title = getString(R.string.stealth_choose_apps),
                    neon = Neon.TEAL,
                    icon = R.drawable.ic_add_24,
                    fullWidth = true,
                    onClick = { startActivity(Intent(this, StealthPickActivity::class.java)) }
                )
            )
        }

        // Notices only appear when something needs the user's attention
        if (StealthController.isActive()) {
            cards.add(
                Card(
                    title = getString(R.string.stealth_restore_now),
                    neon = Neon.GREEN,
                    icon = R.drawable.ic_power_24,
                    fullWidth = true,
                    onClick = {
                        try {
                            startService(
                                Intent(this, StealthService::class.java).setAction(StealthService.ACTION_RESTORE)
                            )
                        } catch (e: Throwable) {
                            StealthController.leave(this)
                        }
                        toast(getString(R.string.stealth_notification_restoring))
                        binding.list.postDelayed({ if (!isFinishing) show() }, 1500)
                    }
                )
            )
        }
        if (!StealthController.canChangeSettings(this)) {
            cards.add(
                Card(
                    title = getString(R.string.stealth_no_permission),
                    neon = Neon.RED,
                    icon = R.drawable.ic_warning_24,
                    fullWidth = true
                )
            )
        }
        if (!StealthController.hasUsageAccess(this)) {
            cards.add(
                Card(
                    title = getString(R.string.stealth_usage_access),
                    neon = Neon.AMBER,
                    icon = R.drawable.ic_key_24,
                    fullWidth = true,
                    onClick = {
                        try {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } catch (e: Throwable) {
                            toast(e.toString())
                        }
                    }
                )
            )
        }

        val apps = StealthController.getApps().mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                info to info.loadLabel(pm).toString()
            } catch (e: Throwable) {
                null
            }
        }.filter { (info, label) ->
            q.isEmpty() || label.lowercase(Locale.getDefault()).contains(q)
                    || info.packageName.lowercase(Locale.ROOT).contains(q)
        }.sortedBy { it.second.lowercase(Locale.getDefault()) }

        for ((info, label) in apps) {
            cards.add(
                Card(
                    title = label,
                    neon = Neon.BLUE,
                    appInfo = info,
                    onClick = { startActivity(StealthLaunchActivity.intent(this, info.packageName)) },
                    onLongClick = { showActions(info, label) }
                )
            )
        }

        grid.cards = cards
        binding.empty.visibility = View.GONE
        if (!entrancePlayed) {
            entrancePlayed = true
            binding.list.scheduleLayoutAnimation()
        }
    }

    private fun showActions(info: ApplicationInfo, label: String) {
        val labels = arrayOf<CharSequence>(
            getString(R.string.stealth_add_shortcut),
            getString(R.string.stealth_remove)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setItems(labels) { _, which ->
                if (which == 0) {
                    addShortcut(info, label)
                } else {
                    StealthController.setApps(StealthController.getApps() - info.packageName)
                    show()
                }
            }
            .show()
    }

    /**
     * Puts a shortcut on the home screen that opens the app in stealth mode directly.
     */
    private fun addShortcut(info: ApplicationInfo, label: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast(getString(R.string.stealth_shortcut_unsupported))
            return
        }
        try {
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager == null || !manager.isRequestPinShortcutSupported) {
                toast(getString(R.string.stealth_shortcut_unsupported))
                return
            }
            val size = (resources.displayMetrics.density * 72).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val drawable = info.loadIcon(packageManager)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))

            val shortcut = ShortcutInfo.Builder(this, "stealth_" + info.packageName)
                .setShortLabel(label)
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(StealthLaunchActivity.intent(this, info.packageName))
                .build()
            manager.requestPinShortcut(shortcut, null)
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }
}
