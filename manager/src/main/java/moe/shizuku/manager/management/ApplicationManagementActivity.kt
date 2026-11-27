package moe.shizuku.manager.management

import android.content.pm.PackageInfo
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import java.util.*

class ApplicationManagementActivity : AppBarActivity() {

    private val viewModel by appsViewModel()
    private val adapter = AppsAdapter()

    private var allPackages: List<PackageInfo> = emptyList()
    private var query: String = ""
    private val labels = HashMap<String, String>()

    private fun labelOf(pi: PackageInfo): String {
        val key = pi.packageName + ":" + (pi.applicationInfo?.uid ?: 0)
        return labels.getOrPut(key) {
            try {
                pi.applicationInfo?.loadLabel(packageManager)?.toString() ?: pi.packageName
            } catch (e: Throwable) {
                pi.packageName
            }
        }
    }

    /**
     * Shows the loaded apps filtered by the search text and in the selected order.
     */
    private fun applyFilter() {
        var list: List<PackageInfo> = allPackages

        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isNotEmpty()) {
            list = list.filter {
                it.packageName.lowercase(Locale.ROOT).contains(q)
                        || labelOf(it).lowercase(Locale.getDefault()).contains(q)
            }
        }

        when (ShizukuSettings.getAppsSortMode()) {
            ShizukuSettings.SORT_NAME -> {
                list = list.sortedBy { labelOf(it).lowercase(Locale.getDefault()) }
            }
            ShizukuSettings.SORT_GRANTED_FIRST -> {
                val granted = HashMap<PackageInfo, Boolean>()
                for (pi in list) {
                    granted[pi] = try {
                        AuthorizationManager.granted(pi.packageName, pi.applicationInfo!!.uid)
                    } catch (e: Throwable) {
                        false
                    }
                }
                list = list.sortedWith(
                    compareByDescending<PackageInfo> { granted[it] == true }
                        .thenBy { labelOf(it).lowercase(Locale.getDefault()) }
                )
            }
        }

        shownPackages = list
        adapter.updateData(list)
        if (!entrancePlayed && list.isNotEmpty()) {
            entrancePlayed = true
            findViewById<android.view.ViewGroup>(android.R.id.list)?.scheduleLayoutAnimation()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.apps, menu)

        val searchView = menu.findItem(R.id.action_search)?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.apps_search)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText.orEmpty()
                applyFilter()
                return true
            }
        })

        val checkedId = when (ShizukuSettings.getAppsSortMode()) {
            ShizukuSettings.SORT_NAME -> R.id.action_sort_name
            ShizukuSettings.SORT_GRANTED_FIRST -> R.id.action_sort_granted
            else -> R.id.action_sort_default
        }
        menu.findItem(checkedId)?.isChecked = true
        return true
    }

    /** The apps that are on screen now: all of them, or the ones matching the search. */
    private var shownPackages: List<PackageInfo> = emptyList()
    private var entrancePlayed = false

    /**
     * Gives the permission to every shown app, or takes it from all of them.
     */
    private fun bulk(grant: Boolean) {
        val targets = shownPackages
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setMessage(
                getString(
                    if (grant) R.string.apps_grant_all_confirm else R.string.apps_revoke_all_confirm,
                    targets.size
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for (pi in targets) {
                    val uid = pi.applicationInfo?.uid ?: continue
                    try {
                        if (grant) {
                            AuthorizationManager.grant(pi.packageName, uid)
                        } else {
                            AuthorizationManager.revoke(pi.packageName, uid)
                        }
                    } catch (e: Throwable) {
                        Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show()
                        break
                    }
                }
                adapter.notifyDataSetChanged()
                viewModel.load(true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_grant_all -> {
                bulk(true)
                return true
            }
            R.id.action_revoke_all -> {
                bulk(false)
                return true
            }
        }
        val mode = when (item.itemId) {
            R.id.action_sort_default -> ShizukuSettings.SORT_DEFAULT
            R.id.action_sort_name -> ShizukuSettings.SORT_NAME
            R.id.action_sort_granted -> ShizukuSettings.SORT_GRANTED_FIRST
            else -> return super.onOptionsItemSelected(item)
        }
        ShizukuSettings.setAppsSortMode(mode)
        item.isChecked = true
        applyFilter()
        return true
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.subtitle = getString(R.string.apps_restrictions_hint)

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    allPackages = it.data ?: emptyList()
                    applyFilter()
                }
                Status.ERROR -> {
                    finish()
                    val tr = it.error
                    Toast.makeText(this, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                    tr.printStackTrace()
                }
                Status.LOADING -> {

                }
            }
        }
        if (viewModel.packages.value == null) {
            viewModel.load()
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                viewModel.load(true)
            }
        })

        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }
}
