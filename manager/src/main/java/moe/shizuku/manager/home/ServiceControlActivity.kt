package moe.shizuku.manager.home

import android.os.Bundle
import android.util.TypedValue
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.HomeActivityBinding
import moe.shizuku.manager.management.appsViewModel
import rikka.core.ktx.unsafeLazy
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku

/**
 * Everything about starting and stopping the service: wireless debugging, a computer, root,
 * the terminal helper and the stop button. These cards used to be on the home screen.
 */
class ServiceControlActivity : AppBarActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        homeModel.reload()
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        homeModel.reload()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel, HomeAdapter.MODE_SERVICE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = HomeActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        homeModel.serviceStatus.observe(this) {
            // A failed read still carries a "not running" status, the start cards must show then
            if (it.status == Status.SUCCESS || it.status == Status.ERROR) {
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                adapter.updateData()
                if (it.status == Status.SUCCESS && status.isRunning) {
                    ShizukuSettings.setLastLaunchMode(
                        if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB
                    )
                }
            }
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addItemSpacing(top = 4f, bottom = 4f, unit = TypedValue.COMPLEX_UNIT_DIP)
        recyclerView.addEdgeSpacing(top = 4f, bottom = 4f, left = 16f, right = 16f, unit = TypedValue.COMPLEX_UNIT_DIP)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        homeModel.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
