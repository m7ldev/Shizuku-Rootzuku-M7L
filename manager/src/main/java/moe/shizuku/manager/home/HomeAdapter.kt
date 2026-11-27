package moe.shizuku.manager.home

import android.content.Intent
import android.os.Build
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.starter.ServiceController
import moe.shizuku.manager.tools.AccountsActivity
import moe.shizuku.manager.tools.UsbStartActivity
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool
import rikka.shizuku.Shizuku

/**
 * @param mode [MODE_HOME] shows the status and the shortcut tiles, [MODE_SERVICE] shows the
 * cards for starting and stopping the service
 */
class HomeAdapter(
    private val homeModel: HomeViewModel,
    private val appsModel: AppsViewModel,
    private val mode: Int = MODE_HOME
) : IdBasedRecyclerViewAdapter(ArrayList()) {

    init {
        updateData()
        setHasStableIds(true)
    }

    companion object {

        const val MODE_HOME = 0
        const val MODE_SERVICE = 1

        private const val ID_STATUS = 0L
        private const val ID_APPS = 1L
        private const val ID_TERMINAL = 2L
        private const val ID_START_ROOT = 3L
        private const val ID_START_WADB = 4L
        private const val ID_START_ADB = 5L
        private const val ID_LEARN_MORE = 6L
        private const val ID_ADB_PERMISSION_LIMITED = 7L
        private const val ID_IN_APP_TERMINAL = 8L
        private const val ID_ACTIVITY_LOG = 9L
        private const val ID_STOP = 10L
        private const val ID_TOOLS = 11L
        private const val ID_ACCOUNTS = 12L
        private const val ID_START_USB = 13L
    }

    override fun onCreateCreatorPool(): IndexCreatorPool {
        return IndexCreatorPool()
    }

    fun updateData() {
        val status = homeModel.serviceStatus.value?.data ?: return
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0

        clear()

        if (mode == MODE_HOME) {
            addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)
            addItem(ToolsGridViewHolder.CREATOR, status to grantedCount, ID_TOOLS)
            if (running && !adbPermission) {
                addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
            }
            addItem(
                ToolViewHolder.CREATOR,
                HomeTool(
                    R.drawable.ic_heart_24, R.string.accounts_title,
                    R.string.accounts_summary, true
                ) { context ->
                    context.startActivity(Intent(context, AccountsActivity::class.java))
                },
                ID_ACCOUNTS
            )
            notifyDataSetChanged()
            return
        }

        if (running) {
            addItem(
                ToolViewHolder.CREATOR,
                HomeTool(
                    R.drawable.ic_stop_24, R.string.home_stop_title,
                    R.string.home_stop_description, true
                ) { context ->
                    MaterialAlertDialogBuilder(context)
                        .setMessage(R.string.dialog_stop_message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> ServiceController.stop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                },
                ID_STOP
            )
        }

        if (isPrimaryUser) {
            val root = EnvironmentUtils.isRooted()
            val rootRestart = running && status.uid == 0

            if (root) {
                addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0) {
                addItem(StartWirelessAdbViewHolder.CREATOR, null, ID_START_WADB)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                addItem(
                    ToolViewHolder.CREATOR,
                    HomeTool(
                        R.drawable.ic_power_24, R.string.usb_title,
                        R.string.usb_home_summary, true
                    ) { context ->
                        context.startActivity(Intent(context, UsbStartActivity::class.java))
                    },
                    ID_START_USB
                )
            }

            addItem(StartAdbViewHolder.CREATOR, null, ID_START_ADB)

            if (!root) {
                addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)
            }
        }
        if (adbPermission) {
            addItem(TerminalViewHolder.CREATOR, status, ID_TERMINAL)
        }
        addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE)
        notifyDataSetChanged()
    }
}
