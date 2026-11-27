package moe.shizuku.manager.tools

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.FileManagerActivityBinding
import moe.shizuku.manager.databinding.FileRowBinding
import moe.shizuku.manager.utils.AppIconCache
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A file manager with two panes side by side that works with the permissions of the Shizuku
 * service, so that it can open folders that normal apps cannot, such as Android/data.
 *
 * Files are copied and moved from one pane to the other. Zip archives, apk files and ISO
 * images are opened like folders. Plain operations are shell commands run by the service,
 * archives are handled by [FileTool], which the service runs with app_process.
 */
class FileManagerActivity : AppBarActivity() {

    companion object {
        private const val HOME = "/sdcard"
        private const val SECOND_HOME = "/sdcard/Download"
        private const val END_OF_STAT = "::END-OF-STAT::"
        private const val TEMP = "/data/local/tmp/rootzuko_files"

        private const val MENU_NEW_FOLDER = 1
        private const val MENU_NEW_FILE = 2
        private const val MENU_EXTRACT_APK = 3
        private const val MENU_SORT = 4
        private const val MENU_HIDDEN = 5
        private const val MENU_GO_TO = 6
        private const val MENU_REFRESH = 7
        private const val MENU_SELECT_ALL = 8
        private const val MENU_SEARCH_DEEP = 9
        private const val MENU_TRASH = 10

        const val KEY_TRASH = "files_trash"
        private const val KEY_BOOKMARKS = "files_bookmarks"
        private const val KEY_HIDDEN = "files_show_hidden"
        private const val TRASH = "/sdcard/.RootzukoTrash"
        private const val TRASH_DAYS = 30

        private const val SORT_NAME = 0
        private const val SORT_SIZE = 1
        private const val SORT_DATE = 2

        private val SHORTCUTS = arrayOf(
            "/sdcard",
            "/sdcard/Download",
            "/sdcard/DCIM",
            "/sdcard/Android/data",
            "/sdcard/Android/obb",
            "/sdcard/Android/media",
            "/data/local/tmp",
            "/"
        )

        private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
        private val VIDEOS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v", "flv", "wmv", "mpg", "mpeg")
        private val AUDIO = setOf("mp3", "m4a", "ogg", "opus", "wav", "flac", "aac", "amr", "wma", "mid")
        private val TEXTS = setOf(
            "txt", "log", "json", "xml", "html", "htm", "md", "ini", "conf", "cfg", "prop",
            "sh", "csv", "yml", "yaml", "js", "css", "kt", "java", "py", "c", "h", "cpp"
        )

        /** Bundles of several apk files that are installed together. */
        private val BUNDLES = setOf("apks", "xapk", "apkm")

        /** Files with the structure of a zip archive, they are opened like folders. */
        private val ZIP_LIKE = BUNDLES + setOf("zip", "jar", "apk", "aar", "obb", "cbz", "epub", "docx", "xlsx", "pptx")
        private val TARS = setOf("tar", "gz", "tgz")
        private val ARCHIVES = ZIP_LIKE + TARS + setOf("iso", "rar", "7z", "xz", "bz2", "pkg")

        private val APP_FOLDERS = Regex("(/sdcard|/storage/emulated/\\d+)/Android/(data|obb|media)/?")
        private val PART = Regex(".*\\.\\d{3}")

        /** Types that the viewer of this app shows properly, others are looked at first. */
        private val KNOWN = IMAGES + VIDEOS + AUDIO + TEXTS + setOf("dex", "arsc")
    }

    private class Entry(
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val time: Long,
        val isParent: Boolean = false
    ) {
        val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    /**
     * Where a pane is: the folder [path], or the folder [inner] inside the archive file
     * [archive]. For an archive, [path] is the folder that holds the archive file.
     */
    private class Loc(val path: String, val archive: String? = null, val inner: String = "") {
        val display: String
            get() = if (archive == null) path else archive + "!/" + inner

        fun same(other: Loc): Boolean = path == other.path && archive == other.archive && inner == other.inner
    }

    private class ArchiveEntry(val path: String, val isFolder: Boolean, val size: Long, val time: Long)

    private class FileHolder(val binding: FileRowBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    private inner class Pane(val list: RecyclerView, val pathView: TextView, val frame: View) {
        var loc = Loc(HOME)
        var loaded = false
        var entries: List<Entry> = emptyList()
        val back = ArrayList<Loc>()
        val forward = ArrayList<Loc>()
        val selected = LinkedHashSet<String>()

        /** The listing of the archive that was read last, it is reused inside the archive. */
        var archiveOf: String? = null
        var archiveEntries: List<ArchiveEntry> = emptyList()

        val adapter = FileAdapter(this)

        init {
            list.adapter = adapter
        }
    }

    private inner class FileAdapter(val pane: Pane) : RecyclerView.Adapter<FileHolder>() {

        var items: List<Entry> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder {
            return FileHolder(FileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val entry = items[position]
            val binding = holder.binding
            binding.title.text = entry.name
            val summary = describe(entry)
            binding.summary.text = summary
            binding.summary.visibility = if (summary == null) View.GONE else View.VISIBLE
            holder.iconJob?.cancel()
            holder.iconJob = null
            val app = if (entry.isFolder && !entry.isParent && isAppFolder(pane.loc)) appOf(entry.name) else null
            if (app != null) {
                // The folders in Android/data are named after apps, show whose folder it is
                binding.icon.setBackgroundResource(0)
                binding.icon.imageTintList = null
                binding.icon.setPadding(0, 0, 0, 0)
                binding.icon.setImageDrawable(null)
                holder.iconJob = AppIconCache.loadIconBitmapAsync(this@FileManagerActivity, app, app.uid / 100000, binding.icon)
            } else {
                val padding = (5 * resources.displayMetrics.density).toInt()
                binding.icon.setPadding(padding, padding, padding, padding)
                binding.icon.setImageResource(iconOf(entry))
                binding.icon.setBackgroundResource(R.drawable.tool_icon_small)
                binding.icon.imageTintList = ColorStateList.valueOf(getColor(R.color.neon_green))
            }
            binding.root.isActivated = entry.name in pane.selected

            binding.root.setOnClickListener { onEntryClick(pane, entry) }
            binding.root.setOnLongClickListener {
                onEntryLongClick(pane, entry)
                true
            }
            // Tapping the icon marks the entry, like in other two-pane file managers
            binding.icon.setOnClickListener {
                setActive(pane)
                if (!entry.isParent) toggleSelected(pane, entry)
            }
        }
    }

    /** Apps by package name, with null for names that are not an app this app can see. */
    private val appCache = HashMap<String, ApplicationInfo?>()

    private fun isAppFolder(loc: Loc): Boolean {
        return loc.archive == null && APP_FOLDERS.matches(loc.path)
    }

    private fun appOf(name: String): ApplicationInfo? {
        if (!appCache.containsKey(name)) {
            appCache[name] = try {
                packageManager.getApplicationInfo(name, 0)
            } catch (e: Throwable) {
                null
            }
        }
        return appCache[name]
    }

    private lateinit var binding: FileManagerActivityBinding
    private lateinit var left: Pane
    private lateinit var right: Pane
    private lateinit var active: Pane
    private var ready = false
    private var refreshOnResume = false

    private var sort = SORT_NAME
    private var showHidden = ShizukuSettings.getPreferences().getBoolean(KEY_HIDDEN, true)
    private var query = ""
    private val userId = UserHandleCompat.myUserId()

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) finish()
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val loc = active.loc
            when {
                active.selected.isNotEmpty() -> clearSelection(active)
                loc.archive != null || (loc.path != "/" && loc.path != HOME) -> goUp(active)
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            toast(getString(R.string.home_status_service_not_running, getString(R.string.app_name)))
            finish()
            return
        }

        binding = FileManagerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        left = Pane(binding.listLeft, binding.pathLeft, binding.paneLeft)
        right = Pane(binding.listRight, binding.pathRight, binding.paneRight)
        active = left
        for (pane in arrayOf(left, right)) {
            // Touching a pane anywhere makes it the active one
            pane.list.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) setActive(pane)
                false
            }
            pane.pathView.setOnClickListener {
                setActive(pane)
                chooseShortcut()
            }
        }

        binding.buttonBack.setOnClickListener { goBack(active) }
        binding.buttonForward.setOnClickListener { goForward(active) }
        binding.buttonAdd.setOnClickListener { chooseNew() }
        binding.buttonSync.setOnClickListener { open(other(active), active.loc) }
        binding.buttonUp.setOnClickListener { goUp(active) }

        onBackPressedDispatcher.addCallback(this, backCallback)
        Shizuku.addBinderDeadListener(binderDeadListener)
        ready = true

        grantStorageAccess()
        purgeTrash()
        restoreInstallCheck()
        setActive(left)
        open(left, Loc(savedInstanceState?.getString("left") ?: HOME), false)
        open(right, Loc(savedInstanceState?.getString("right") ?: SECOND_HOME), false)
    }

    override fun onResume() {
        super.onResume()
        if (ready && refreshOnResume) {
            // Another screen of this app may have written files
            refreshOnResume = false
            refresh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ready) Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!ready) return
        outState.putString("left", left.loc.path)
        outState.putString("right", right.loc.path)
    }

    /**
     * With access to all files this app can hand a file of the shared storage to another app
     * directly. While Shizuku is running the app gives that access to itself.
     */
    private fun grantStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return
        thread(name = "files-storage") {
            ShizukuShell.exec("appops set --user $userId ${BuildConfig.APPLICATION_ID} MANAGE_EXTERNAL_STORAGE allow")
        }
    }

    // ---- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tool_search, menu)
        val searchView = menu.findItem(R.id.action_search)?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.apps_search)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText.orEmpty()
                if (ready) show(active)
                return true
            }
        })
        menu.add(0, MENU_NEW_FOLDER, 0, R.string.tool_files_new_folder)
        menu.add(0, MENU_NEW_FILE, 0, R.string.tool_files_new_file)
        menu.add(0, MENU_EXTRACT_APK, 0, R.string.files_extract_apk)
        menu.add(0, MENU_SELECT_ALL, 0, R.string.files_select_all)
        menu.add(0, MENU_SEARCH_DEEP, 0, R.string.files_search_deep)
        menu.add(0, MENU_TRASH, 0, R.string.files_trash)
        menu.add(0, MENU_SORT, 0, R.string.apps_sort)
        menu.add(0, MENU_HIDDEN, 0, R.string.files_settings)
        menu.add(0, MENU_GO_TO, 0, R.string.tool_files_go_to)
        menu.add(0, MENU_REFRESH, 0, R.string.activity_log_refresh)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!ready) return super.onOptionsItemSelected(item)
        when (item.itemId) {
            MENU_NEW_FOLDER -> newFolder()
            MENU_NEW_FILE -> newFile()
            MENU_EXTRACT_APK -> extractApk()
            MENU_SELECT_ALL -> {
                active.selected.clear()
                active.adapter.items.filter { !it.isParent }.forEach { active.selected.add(it.name) }
                active.adapter.notifyDataSetChanged()
                updateTitle()
            }
            MENU_SORT -> {
                val labels = arrayOf<CharSequence>(
                    getString(R.string.apps_sort_name),
                    getString(R.string.tool_files_sort_size),
                    getString(R.string.tool_files_sort_date)
                )
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.apps_sort)
                    .setSingleChoiceItems(labels, sort) { dialog, which ->
                        dialog.dismiss()
                        sort = which
                        show(left)
                        show(right)
                    }
                    .show()
            }
            MENU_HIDDEN -> showSettings()
            MENU_GO_TO -> chooseShortcut()
            MENU_SEARCH_DEEP -> searchDeep()
            MENU_TRASH -> busyThen("mkdir -p $TRASH") { open(active, Loc(TRASH)) }
            MENU_REFRESH -> refresh()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * The settings of the file manager. A change applies at once.
     */
    private fun showSettings() {
        val preferences = ShizukuSettings.getPreferences()
        val labels = arrayOf<CharSequence>(
            getString(R.string.files_settings_trash),
            getString(R.string.tool_files_show_hidden)
        )
        val checked = booleanArrayOf(trashEnabled(), showHidden)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_settings)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (which == 0) {
                    preferences.edit().putBoolean(KEY_TRASH, isChecked).apply()
                } else {
                    showHidden = isChecked
                    preferences.edit().putBoolean(KEY_HIDDEN, isChecked).apply()
                    show(left)
                    show(right)
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun bookmarks(): Set<String> {
        return ShizukuSettings.getPreferences().getStringSet(KEY_BOOKMARKS, emptySet()).orEmpty()
    }

    /**
     * The places to jump to: the bookmarks of the user, then the usual folders. The first
     * entry adds the folder of the active pane to the bookmarks, or removes it again.
     */
    private fun chooseShortcut() {
        val here = active.loc.path
        val saved = bookmarks()
        val isSaved = here in saved
        val places = saved.sorted() + SHORTCUTS.filter { it !in saved }
        val labels = ArrayList<CharSequence>()
        if (active.loc.archive == null) {
            labels.add(getString(if (isSaved) R.string.files_bookmark_remove else R.string.files_bookmark_add))
        }
        val offset = labels.size
        places.forEach { labels.add(if (it in saved) "★ $it" else it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_files_go_to)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < offset) {
                    val changed = HashSet(saved)
                    if (isSaved) changed.remove(here) else changed.add(here)
                    ShizukuSettings.getPreferences().edit().putStringSet(KEY_BOOKMARKS, changed).apply()
                } else {
                    open(active, Loc(places[which - offset]))
                }
            }
            .show()
    }

    /**
     * Looks for files by name in the folder of the active pane and everything below it.
     */
    private fun searchDeep() {
        val folder = realFolder(active) ?: return
        askText(R.string.files_search_deep, "") { text ->
            if (text.isEmpty()) return@askText
            val dialog = working()
            runAsync({
                ShizukuShell.exec(
                    "cd -- ${q(folder)} && find . -iname ${q("*$text*")} 2>/dev/null | head -n 300",
                    1024 * 1024
                )
            }) { result ->
                dialog.dismiss()
                val found = result.out.lines().filter { it.length > 2 }.map { it.removePrefix("./") }
                if (found.isEmpty()) {
                    toast(getString(R.string.files_search_none))
                    return@runAsync
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.files_search_found, found.size))
                    .setItems(found.toTypedArray<CharSequence>()) { _, which ->
                        // Opens the folder that holds the result
                        open(active, Loc(parentOf(child(folder, found[which]))))
                    }
                    .show()
            }
        }
    }

    private fun chooseNew() {
        val labels = arrayOf<CharSequence>(
            getString(R.string.tool_files_new_folder),
            getString(R.string.tool_files_new_file),
            getString(R.string.files_extract_apk)
        )
        MaterialAlertDialogBuilder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> newFolder()
                    1 -> newFile()
                    2 -> extractApk()
                }
            }
            .show()
    }

    private fun newFolder() {
        val folder = realFolder(active) ?: return
        askText(R.string.tool_files_new_folder, "") { name ->
            if (checkName(name)) busy("mkdir -- ${q(child(folder, name))}")
        }
    }

    private fun newFile() {
        val folder = realFolder(active) ?: return
        askText(R.string.tool_files_new_file, "") { name ->
            if (checkName(name)) busy("touch -- ${q(child(folder, name))}")
        }
    }

    // ---- paths

    private fun q(value: String): String = ShizukuShell.quote(value)

    private fun parentOf(p: String): String {
        val trimmed = p.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "/" else trimmed.substring(0, index)
    }

    private fun child(folder: String, name: String): String {
        return if (folder == "/") "/$name" else "$folder/$name"
    }

    private fun inner(folder: String, name: String): String {
        return if (folder.isEmpty()) name else "$folder/$name"
    }

    private fun other(pane: Pane): Pane = if (pane === left) right else left

    /**
     * The folder of [pane] for operations that write, or null with a message when the pane
     * shows the inside of an archive, which cannot be changed.
     */
    private fun realFolder(pane: Pane): String? {
        if (pane.loc.archive != null) {
            toast(getString(R.string.files_archive_read_only))
            return null
        }
        return pane.loc.path
    }

    /** The command that runs [FileTool] with the permissions of the service. */
    private fun tool(vararg args: String): String {
        return "CLASSPATH=${q(applicationInfo.sourceDir)} app_process /system/bin " +
                FileTool::class.java.name + " " + args.joinToString(" ") { q(it) }
    }

    private fun isIso(archive: String): Boolean = archive.lowercase(Locale.ROOT).endsWith(".iso")

    // ---- panes

    private fun setActive(pane: Pane) {
        val changed = active !== pane
        active = pane
        for (p in arrayOf(left, right)) {
            // Not "activated": that state would spread to every row of the pane
            p.frame.setBackgroundResource(if (p === pane) R.drawable.file_pane_active else R.drawable.file_pane_background)
            p.pathView.setTextColor(getColor(if (p === pane) R.color.neon_green else R.color.neon_text_secondary))
        }
        updateTitle()
        if (changed && query.isNotEmpty()) {
            show(left)
            show(right)
        }
    }

    private fun updateTitle() {
        val pane = active
        supportActionBar?.subtitle = if (pane.selected.isNotEmpty()) {
            getString(R.string.files_selected_count, pane.selected.size)
        } else {
            pane.loc.display
        }
    }

    private fun toggleSelected(pane: Pane, entry: Entry) {
        if (!pane.selected.remove(entry.name)) pane.selected.add(entry.name)
        pane.adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun clearSelection(pane: Pane) {
        pane.selected.clear()
        pane.adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun goUp(pane: Pane) {
        val loc = pane.loc
        when {
            loc.archive != null && loc.inner.isEmpty() -> open(pane, Loc(loc.path))
            loc.archive != null -> open(pane, Loc(loc.path, loc.archive, loc.inner.substringBeforeLast('/', "")))
            loc.path != "/" -> open(pane, Loc(parentOf(loc.path)))
        }
    }

    private fun goBack(pane: Pane) {
        if (pane.back.isEmpty()) return
        val target = pane.back.removeAt(pane.back.size - 1)
        pane.forward.add(pane.loc)
        open(pane, target, false)
    }

    private fun goForward(pane: Pane) {
        if (pane.forward.isEmpty()) return
        val target = pane.forward.removeAt(pane.forward.size - 1)
        pane.back.add(pane.loc)
        open(pane, target, false)
    }

    private fun refresh() {
        for (pane in arrayOf(left, right)) {
            // The archive may have changed, read it again
            pane.archiveOf = null
            open(pane, pane.loc, false)
        }
    }

    // ---- listing

    private fun open(pane: Pane, target: Loc, remember: Boolean = true) {
        if (target.archive != null) openArchive(pane, target, remember) else openFolder(pane, target, remember)
    }

    private fun arrived(pane: Pane, target: Loc, remember: Boolean, entries: List<Entry>) {
        if (remember && pane.loaded && !pane.loc.same(target)) {
            pane.back.add(pane.loc)
            pane.forward.clear()
        }
        pane.loaded = true
        pane.loc = target
        pane.entries = entries
        pane.selected.clear()
        pane.pathView.text = target.display
        show(pane)
        updateTitle()
    }

    private fun failed(pane: Pane, message: String) {
        toast(message)
        if (!pane.loaded) {
            pane.loaded = true
            pane.pathView.text = pane.loc.display
            show(pane)
        }
    }

    private fun openFolder(pane: Pane, target: Loc, remember: Boolean) {
        runAsync({
            // stat gives type, size and time of every entry in one go. ls is the list of
            // names that is always complete, also when stat could not handle the folder.
            ShizukuShell.exec(
                "cd -- ${q(target.path)} || exit 1; " +
                        "stat -L -c '%f|%s|%Y|%n' -- * .[!.]* ..?* 2>/dev/null; " +
                        "echo '$END_OF_STAT'; ls -1Ap",
                8 * 1024 * 1024
            )
        }) { result ->
            if (!result.out.contains(END_OF_STAT)) {
                failed(pane, result.message)
                return@runAsync
            }

            val stats = HashMap<String, Entry>()
            val names = ArrayList<String>()
            var inStat = true
            for (line in result.out.lines()) {
                if (line.isEmpty()) continue
                if (line == END_OF_STAT) {
                    inStat = false
                    continue
                }
                if (inStat) {
                    val parts = line.split('|', limit = 4)
                    if (parts.size != 4) continue
                    val mode = parts[0].toIntOrNull(16) ?: continue
                    val isFolder = (mode and 0xF000) == 0x4000
                    stats[parts[3]] = Entry(
                        parts[3], isFolder,
                        parts[1].toLongOrNull() ?: -1, (parts[2].toLongOrNull() ?: 0) * 1000
                    )
                } else {
                    names.add(line)
                }
            }

            arrived(pane, target, remember, names.map { raw ->
                val isFolder = raw.endsWith("/")
                val name = if (isFolder) raw.dropLast(1) else raw
                stats[name] ?: Entry(name, isFolder, -1, 0)
            })
        }
    }

    private fun openArchive(pane: Pane, target: Loc, remember: Boolean) {
        val archive = target.archive ?: return
        if (pane.archiveOf == archive) {
            arrived(pane, target, remember, archiveChildren(pane.archiveEntries, target.inner))
            return
        }
        toast(getString(R.string.tools_loading))
        runAsync({
            ShizukuShell.exec(tool(if (isIso(archive)) "isolist" else "ziplist", archive), 16 * 1024 * 1024)
        }) { result ->
            if (!result.isSuccess) {
                showText(getString(R.string.files_cannot_open_archive), firstLines(result.message))
                return@runAsync
            }
            val entries = ArrayList<ArchiveEntry>()
            for (line in result.out.lines()) {
                val parts = line.split('|', limit = 4)
                if (parts.size != 4 || parts[3].isEmpty()) continue
                entries.add(
                    ArchiveEntry(
                        parts[3], parts[0] == "d",
                        parts[1].toLongOrNull() ?: -1, (parts[2].toLongOrNull() ?: 0) * 1000
                    )
                )
            }
            pane.archiveOf = archive
            pane.archiveEntries = entries
            arrived(pane, target, remember, archiveChildren(entries, target.inner))
        }
    }

    /**
     * The entries directly inside the folder [folder] of an archive. Folders that have no
     * entry of their own, only files below them, are listed too.
     */
    private fun archiveChildren(all: List<ArchiveEntry>, folder: String): List<Entry> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val result = LinkedHashMap<String, Entry>()
        for (entry in all) {
            if (!entry.path.startsWith(prefix) || entry.path.length <= prefix.length) continue
            val rest = entry.path.substring(prefix.length)
            val name = rest.substringBefore('/')
            if (rest.contains('/')) {
                if (result[name]?.isFolder != true) result[name] = Entry(name, true, -1, 0)
            } else if (result[name]?.isFolder != true) {
                result[name] = Entry(name, entry.isFolder, entry.size, entry.time)
            }
        }
        return result.values.toList()
    }

    private fun iconOf(entry: Entry): Int {
        if (entry.isFolder) return R.drawable.ic_folder_24
        return when (entry.extension) {
            "apk", in BUNDLES -> R.drawable.ic_android_24
            in IMAGES -> R.drawable.ic_image_24
            in VIDEOS -> R.drawable.ic_movie_24
            in AUDIO -> R.drawable.ic_music_24
            in ARCHIVES -> R.drawable.ic_archive_24
            in TEXTS -> R.drawable.ic_article_24
            else -> R.drawable.ic_file_24
        }
    }

    private fun describe(entry: Entry): String? {
        val date = if (entry.time > 0) {
            DateUtils.formatDateTime(
                this, entry.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR
            )
        } else null
        val size = if (!entry.isFolder && entry.size >= 0) Formatter.formatShortFileSize(this, entry.size) else null
        return listOfNotNull(size, date).joinToString(" · ").ifEmpty { null }
    }

    private fun show(pane: Pane) {
        val text = if (pane === active) query.trim() else ""
        val visible = pane.entries.filter {
            (showHidden || !it.name.startsWith(".")) && (text.isEmpty() || it.name.contains(text, ignoreCase = true))
        }
        val comparator: Comparator<Entry> = when (sort) {
            SORT_SIZE -> compareByDescending { it.size }
            SORT_DATE -> compareByDescending { it.time }
            else -> compareBy { it.name.lowercase(Locale.getDefault()) }
        }
        val rows = ArrayList<Entry>()
        if ((pane.loc.archive != null || pane.loc.path != "/") && text.isEmpty()) {
            rows.add(Entry("..", true, -1, 0, isParent = true))
        }
        // Folders always come first
        rows.addAll(visible.sortedWith(compareByDescending<Entry> { it.isFolder }.then(comparator)))
        pane.adapter.items = rows
    }

    // ---- taps

    private fun onEntryClick(pane: Pane, entry: Entry) {
        setActive(pane)
        if (entry.isParent) {
            goUp(pane)
            return
        }
        if (pane.selected.isNotEmpty()) {
            toggleSelected(pane, entry)
            return
        }
        val loc = pane.loc
        if (loc.archive != null) {
            if (entry.isFolder) {
                open(pane, Loc(loc.path, loc.archive, inner(loc.inner, entry.name)))
            } else {
                viewFromArchive(pane, entry)
            }
            return
        }
        val full = child(loc.path, entry.name)
        when {
            entry.isFolder -> open(pane, Loc(full))
            entry.extension == "apk" || entry.extension in BUNDLES -> chooseForApk(pane, entry, full)
            entry.extension in ZIP_LIKE || entry.extension == "iso" -> open(pane, Loc(loc.path, full))
            entry.extension in KNOWN -> openInternal(pane, full, entry)
            else -> openUnknown(pane, full, entry)
        }
    }

    /**
     * A file with an unknown ending: when it starts like a zip archive it is opened like a
     * folder, anything else goes to the viewer, which shows text or a hex dump.
     */
    private fun openUnknown(pane: Pane, full: String, entry: Entry) {
        runAsync({ ShizukuShell.exec("od -An -tx1 -N4 -- ${q(full)}").out.replace(" ", "").trim() }) { magic ->
            if (magic == "504b0304") open(pane, Loc(pane.loc.path, full)) else openInternal(pane, full, entry)
        }
    }

    private fun onEntryLongClick(pane: Pane, entry: Entry) {
        setActive(pane)
        if (entry.isParent) return
        // The menu is for the marked entries when the pressed entry is one of them
        val targets = if (entry.name in pane.selected) {
            pane.adapter.items.filter { it.name in pane.selected }
        } else {
            listOf(entry)
        }
        if (pane.loc.archive != null) showArchiveActions(pane, targets) else showActions(pane, targets)
    }

    private fun chooseForApk(pane: Pane, entry: Entry, full: String) {
        val labels = arrayOf<CharSequence>(
            getString(R.string.tool_files_install),
            getString(R.string.files_browse_inside),
            getString(R.string.tool_files_open_with)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> install(full, entry.extension)
                    1 -> open(pane, Loc(pane.loc.path, full))
                    2 -> openExternal(full, entry, chooser = true, share = false)
                }
            }
            .show()
    }

    // ---- actions

    private class Actions(val items: ArrayList<ActionGrid.Item> = ArrayList())

    private fun Actions.add(label: Int, icon: Int, action: () -> Unit) {
        items.add(ActionGrid.Item(getString(label), icon, action))
    }

    private fun Actions.show(title: CharSequence) {
        ActionGrid.show(this@FileManagerActivity, title, items)
    }

    private fun titleOf(targets: List<Entry>): CharSequence {
        return if (targets.size == 1) targets[0].name else getString(R.string.files_selected_count, targets.size)
    }

    private fun showActions(pane: Pane, targets: List<Entry>) {
        val folder = pane.loc.path
        val single = targets.singleOrNull()
        val sources = targets.joinToString(" ") { q(child(folder, it.name)) }
        val menu = Actions()

        if (single != null && !single.isFolder) {
            val full = child(folder, single.name)
            val extension = single.extension
            menu.add(R.string.files_open, R.drawable.ic_outline_open_in_new_24) { openInternal(pane, full, single) }
            if (extension !in ZIP_LIKE && extension != "iso") {
                menu.add(R.string.files_open_as_archive, R.drawable.ic_folder_24) { open(pane, Loc(folder, full)) }
            }
            menu.add(R.string.tool_files_open_with, R.drawable.ic_apps_24) { openExternal(full, single, chooser = true, share = false) }
            if (extension == "apk" || extension in BUNDLES) {
                menu.add(R.string.tool_files_install, R.drawable.ic_android_24) { install(full, extension) }
            }
            if (extension in ZIP_LIKE || extension == "iso") {
                menu.add(R.string.files_browse_inside, R.drawable.ic_folder_24) { open(pane, Loc(folder, full)) }
            }
            if (extension in ZIP_LIKE || extension == "iso" || extension in TARS) {
                menu.add(R.string.files_extract_here, R.drawable.ic_archive_24) { extractArchive(full, single, folder) }
                menu.add(R.string.files_extract_to_other, R.drawable.ic_archive_24) {
                    realFolder(other(pane))?.let { extractArchive(full, single, it) }
                }
            }
            menu.add(R.string.tool_logcat_share, R.drawable.ic_share_24) { openExternal(full, single, chooser = true, share = true) }
        }

        menu.add(R.string.files_copy_to_other, R.drawable.ic_copy_24) { transfer(pane, sources, "cp -r") }
        menu.add(R.string.files_move_to_other, R.drawable.ic_move_24) { transfer(pane, sources, "mv") }
        if (single != null) {
            menu.add(R.string.tool_files_rename, R.drawable.ic_edit_24) {
                askText(R.string.tool_files_rename, single.name) { newName ->
                    if (checkName(newName)) busy("mv -- $sources ${q(child(folder, newName))}")
                }
            }
        }
        menu.add(R.string.files_compress, R.drawable.ic_archive_24) {
            val suggestion = (single?.name?.let { if (single.isFolder) it else it.substringBeforeLast('.', it) }
                ?: folder.trimEnd('/').substringAfterLast('/').ifEmpty { "archive" }) + ".zip"
            askText(R.string.files_compress, suggestion) { name ->
                if (checkName(name)) {
                    busy(tool("zip", child(folder, name), folder, *targets.map { it.name }.toTypedArray()))
                }
            }
        }
        if (single != null && !single.isFolder) {
            val full = child(folder, single.name)
            menu.add(R.string.files_split, R.drawable.ic_file_24) { askSplit(full) }
            if (PART.matches(single.name)) {
                menu.add(R.string.files_join, R.drawable.ic_add_24) { busy(tool("join", full)) }
            }
        }
        if (single != null && !single.isFolder) {
            menu.add(R.string.files_checksum, R.drawable.ic_shield_24) {
                details(single.name, sources, "md5sum -- $sources; sha1sum -- $sources; sha256sum -- $sources")
            }
        }
        menu.add(R.string.files_chmod, R.drawable.ic_key_24) {
            askText(R.string.files_chmod, "644") { mode ->
                if (Regex("[0-7]{3,4}").matches(mode)) busy("chmod -R $mode -- $sources") else toast(getString(R.string.tool_files_bad_name))
            }
        }
        if (inTrash(folder)) {
            menu.add(R.string.files_restore, R.drawable.ic_history_24) { restore(folder, targets) }
        }
        if (single == null) {
            menu.add(R.string.tool_logcat_share, R.drawable.ic_share_24) { shareMany(targets.filter { !it.isFolder }.map { child(folder, it.name) }) }
            menu.add(R.string.tool_files_details, R.drawable.ic_outline_info_24) {
                details(titleOf(targets).toString(), sources, "du -shc -- $sources 2>/dev/null | tail -n 1")
            }
            menu.add(R.string.files_clear_selection, R.drawable.ic_close_24) { clearSelection(pane) }
        }
        if (single != null) {
            menu.add(R.string.tool_files_details, R.drawable.ic_outline_info_24) { details(single.name, sources) }
            if (single.name !in pane.selected) {
                menu.add(R.string.files_select, R.drawable.ic_check_24) { toggleSelected(pane, single) }
            }
        }
        menu.add(R.string.tool_files_delete, R.drawable.ic_delete_24) {
            val what = if (single != null) child(folder, single.name) else titleOf(targets)
            val toTrash = trashEnabled() && onSharedStorage(folder) && !inTrash(folder)
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(if (toTrash) R.string.files_trash_confirm else R.string.tool_files_delete_confirm, what))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (toTrash) {
                        // One folder per deletion, it remembers where the files came from
                        busy(
                            "T=$TRASH/\$(date +%Y-%m-%d_%H-%M-%S); mkdir -p \"\$T\" && " +
                                    "printf '%s' ${q(folder)} > \"\$T/.origin\" && mv -- $sources \"\$T\"/"
                        )
                    } else {
                        busy("rm -rf -- $sources")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        menu.show(titleOf(targets))
    }

    private fun showArchiveActions(pane: Pane, targets: List<Entry>) {
        val loc = pane.loc
        val single = targets.singleOrNull()
        val menu = Actions()
        if (single != null && !single.isFolder) {
            menu.add(R.string.files_open, R.drawable.ic_outline_open_in_new_24) { viewFromArchive(pane, single) }
            menu.add(R.string.tool_files_open_with, R.drawable.ic_apps_24) { openFromArchive(pane, single, true) }
        }
        menu.add(R.string.files_extract_to_other, R.drawable.ic_archive_24) {
            realFolder(other(pane))?.let { extractFromArchive(loc, targets, it) }
        }
        menu.add(R.string.files_extract_next_to_archive, R.drawable.ic_archive_24) { extractFromArchive(loc, targets, loc.path) }
        if (single != null && single.name !in pane.selected) {
            menu.add(R.string.files_select, R.drawable.ic_check_24) { toggleSelected(pane, single) }
        }
        menu.show(titleOf(targets))
    }

    // ---- recycle bin

    private fun trashEnabled(): Boolean = ShizukuSettings.getPreferences().getBoolean(KEY_TRASH, false)

    private fun onSharedStorage(folder: String): Boolean {
        return folder == "/sdcard" || folder.startsWith("/sdcard/") || folder.startsWith("/storage/")
    }

    private fun inTrash(folder: String): Boolean = folder == TRASH || folder.startsWith("$TRASH/")

    /**
     * An earlier version had an option that turned off the check of apps installed over adb.
     * The option is gone, so the check is turned on again, once.
     */
    private fun restoreInstallCheck() {
        val preferences = ShizukuSettings.getPreferences()
        if (preferences.getBoolean("install_check_restored", false)) return
        thread(name = "files-verify") {
            if (ShizukuShell.exec("settings put global verifier_verify_adb_installs 1").isSuccess) {
                preferences.edit().putBoolean("install_check_restored", true).apply()
            }
        }
    }

    /** Removes what has been in the recycle bin for longer than [TRASH_DAYS] days. */
    private fun purgeTrash() {
        thread(name = "files-trash") {
            ShizukuShell.exec(
                "[ -d $TRASH ] && find $TRASH -mindepth 1 -maxdepth 1 -mtime +$TRASH_DAYS -exec rm -rf {} + 2>/dev/null"
            )
        }
    }

    /**
     * Moves deleted entries back to the folder they came from. In the recycle bin itself the
     * targets are the folders of single deletions, inside one of them they are its files.
     */
    private fun restore(folder: String, targets: List<Entry>) {
        if (folder == TRASH) {
            busy(targets.joinToString("; ") { entry ->
                val batch = q(child(TRASH, entry.name))
                "B=$batch; O=\$(cat \"\$B/.origin\") && [ -n \"\$O\" ] && mkdir -p \"\$O\" && " +
                        "for f in \"\$B\"/* \"\$B\"/.[!.]*; do [ \"\$f\" = \"\$B/.origin\" ] && continue; " +
                        "[ -e \"\$f\" ] && mv -- \"\$f\" \"\$O\"/; done; rm -f \"\$B/.origin\"; rmdir \"\$B\" 2>/dev/null"
            })
        } else {
            val batch = TRASH + "/" + folder.removePrefix("$TRASH/").substringBefore('/')
            val sources = targets.joinToString(" ") { q(child(folder, it.name)) }
            busy("O=\$(cat ${q("$batch/.origin")}) && [ -n \"\$O\" ] && mkdir -p \"\$O\" && mv -- $sources \"\$O\"/")
        }
    }

    /**
     * Copies or moves the entries to the folder that the other pane shows.
     */
    private fun transfer(pane: Pane, sources: String, command: String) {
        val target = realFolder(other(pane)) ?: return
        if (target == pane.loc.path) {
            toast(getString(R.string.files_same_folder))
            return
        }
        busy("$command -- $sources ${q(target.trimEnd('/') + "/")}")
    }

    /**
     * Unpacks a whole archive into a new folder named after it inside [into].
     */
    private fun extractArchive(full: String, entry: Entry, into: String) {
        val target = child(into, entry.name.substringBeforeLast('.', entry.name).ifEmpty { entry.name } + "_extracted")
        val make = "mkdir -p -- ${q(target)} && "
        busy(
            when {
                entry.extension == "iso" -> make + tool("isoextract", full, target)
                entry.extension in TARS -> make + "tar -xf ${q(full)} -C ${q(target)}"
                else -> make + tool("unzip", full, target)
            }
        )
    }

    private fun extractCommand(loc: Loc, entry: Entry, into: String): String {
        val archive = loc.archive.orEmpty()
        return tool(if (isIso(archive)) "isoextract" else "unzip", archive, into, inner(loc.inner, entry.name))
    }

    private fun extractFromArchive(loc: Loc, targets: List<Entry>, into: String) {
        busy(targets.joinToString(" && ") { extractCommand(loc, it, into) })
    }

    private fun askSplit(full: String) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1024")
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_split)
            .setMessage(R.string.files_split_hint)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val megabytes = input.text.toString().toLongOrNull()
                if (megabytes == null || megabytes <= 0) {
                    toast(getString(R.string.tool_files_bad_name))
                } else {
                    busy(tool("split", full, (megabytes * 1024 * 1024).toString()))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Installs an apk file, or a bundle of apk files (apks, xapk, apkm) as one app.
     */
    private fun install(full: String, extension: String) {
        if (extension == "apk") {
            busy("cat -- ${q(full)} | pm install -r -S \$(stat -c %s -- ${q(full)}) 2>&1")
            return
        }
        val temp = "$TEMP/install"
        busy(
            "rm -rf $temp; mkdir -p $temp && ${tool("unzip", full, temp)} || exit 1; " +
                    "S=\$(pm install-create -r | grep -o '[0-9][0-9]*' | head -n 1); " +
                    "[ -z \"\$S\" ] && { rm -rf $temp; echo 'cannot create install session'; exit 1; }; " +
                    "i=0; for f in $temp/*.apk $temp/*/*.apk; do [ -f \"\$f\" ] || continue; " +
                    "cat \"\$f\" | pm install-write -S \$(stat -c %s \"\$f\") \$S \$i.apk - >/dev/null 2>&1 " +
                    "|| { pm install-abandon \$S >/dev/null 2>&1; rm -rf $temp; echo \"cannot add \$f\"; exit 1; }; " +
                    "i=\$((i+1)); done; " +
                    "[ \$i -eq 0 ] && { pm install-abandon \$S >/dev/null 2>&1; rm -rf $temp; echo 'no apk files inside'; exit 1; }; " +
                    "pm install-commit \$S 2>&1; R=\$?; rm -rf $temp; exit \$R"
        )
    }

    /**
     * Opens the list of installed apps, the chosen one is saved into the folder of the active pane.
     */
    private fun extractApk() {
        val folder = realFolder(active) ?: return
        refreshOnResume = true
        startActivity(ApkExtractActivity.intent(this, folder))
    }

    // ---- opening files in other apps

    private fun mimeOf(extension: String): String {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        return when (extension) {
            in AUDIO -> "audio/*"
            in VIDEOS -> "video/*"
            in IMAGES -> "image/*"
            in TEXTS -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    private fun launch(file: File, entry: Entry, chooser: Boolean, share: Boolean) {
        try {
            val uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".files", file)
            val type = mimeOf(entry.extension)
            val intent = if (share) {
                Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (chooser || share) {
                startActivity(Intent.createChooser(intent, entry.name))
            } else {
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // No app claims this type, let the user pick from all apps that open files
                    intent.setDataAndType(uri, "*/*")
                    startActivity(Intent.createChooser(intent, entry.name))
                }
            }
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }

    /**
     * Shares several files at once. That only works for files this app can read itself.
     */
    private fun shareMany(paths: List<String>) {
        try {
            val uris = ArrayList<android.net.Uri>()
            for (path in paths) {
                val file = File(path)
                if (!file.canRead()) {
                    toast(getString(R.string.files_share_unreadable))
                    return
                }
                uris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".files", file))
            }
            if (uris.isEmpty()) {
                toast(getString(R.string.tool_apps_nothing_selected))
                return
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.tool_logcat_share)))
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }

    private fun sharedFolder(): File = File(externalCacheDir ?: cacheDir, "shared")

    /**
     * Opens a file in another app. Files that this app can read itself are handed over in
     * place. Others, such as files in Android/data, are copied to the cache of this app
     * first, because other apps cannot read the original location.
     */
    private fun openExternal(full: String, entry: Entry, chooser: Boolean, share: Boolean) {
        val original = File(full)
        val readable = try {
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) && original.canRead()
        } catch (e: Throwable) {
            false
        }
        if (readable) {
            launch(original, entry, chooser, share)
            return
        }
        val folder = sharedFolder()
        val copy = File(folder, entry.name)
        val folderQuoted = q(folder.absolutePath)
        // Old copies are removed first, only one exported file is kept
        prepare("mkdir -p $folderQuoted && rm -rf $folderQuoted/* ; cp -f -- ${q(full)} ${q(copy.absolutePath)}") {
            launch(copy, entry, chooser, share)
        }
    }

    /**
     * Opens a file in the viewer of this app. The viewer needs a file that this app can
     * read, so files it cannot read are copied to the cache first.
     */
    private fun openInternal(pane: Pane, full: String, entry: Entry) {
        val original = File(full)
        val readable = try {
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) && original.canRead()
        } catch (e: Throwable) {
            false
        }
        if (readable) {
            refreshOnResume = true
            // Pictures and media take the other files of the folder along, for swiping
            val group = when (entry.extension) {
                in ViewerActivity.IMAGES -> ViewerActivity.IMAGES
                in ViewerActivity.MEDIA -> ViewerActivity.MEDIA
                else -> null
            }
            val siblings = if (group == null || pane.loc.archive != null) emptyList() else {
                pane.adapter.items.filter { !it.isFolder && it.extension in group }.map { child(pane.loc.path, it.name) }
            }
            val index = siblings.indexOf(full)
            if (index >= 0 && siblings.size in 2..2000) {
                startActivity(ViewerActivity.intent(this, ArrayList(siblings), index))
            } else {
                startActivity(ViewerActivity.intent(this, original, entry.name, full))
            }
            return
        }
        val folder = sharedFolder()
        val copy = File(folder, entry.name)
        val folderQuoted = q(folder.absolutePath)
        prepare("mkdir -p $folderQuoted && rm -rf $folderQuoted/* ; cp -f -- ${q(full)} ${q(copy.absolutePath)}") {
            refreshOnResume = true
            startActivity(ViewerActivity.intent(this, copy, entry.name, full))
        }
    }

    private fun viewFromArchive(pane: Pane, entry: Entry) {
        val folder = sharedFolder()
        val folderQuoted = q(folder.absolutePath)
        prepare(
            "mkdir -p $folderQuoted && rm -rf $folderQuoted/* ; " + extractCommand(pane.loc, entry, folder.absolutePath)
        ) {
            // A file inside an archive cannot be saved back, an edited copy lands next to the archive
            refreshOnResume = true
            startActivity(ViewerActivity.intent(this, File(folder, entry.name), entry.name, child(pane.loc.path, entry.name)))
        }
    }

    private fun openFromArchive(pane: Pane, entry: Entry, chooser: Boolean) {
        val folder = sharedFolder()
        val folderQuoted = q(folder.absolutePath)
        prepare(
            "mkdir -p $folderQuoted && rm -rf $folderQuoted/* ; " + extractCommand(pane.loc, entry, folder.absolutePath)
        ) {
            launch(File(folder, entry.name), entry, chooser, false)
        }
    }

    // ---- dialogs and running commands

    private fun checkName(name: String): Boolean {
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") {
            toast(getString(R.string.tool_files_bad_name))
            return false
        }
        return true
    }

    private fun askText(title: Int, initial: String, action: (String) -> Unit) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(initial)
            setSelection(initial.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> action(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun details(name: String, quoted: String, command: String = "ls -ld -- $quoted; du -sh -- $quoted 2>/dev/null") {
        val dialog = working()
        runAsync({ ShizukuShell.exec(command) }) { result ->
            dialog.dismiss()
            showText(name, result.message)
        }
    }

    private fun firstLines(text: String): String = text.lines().take(12).joinToString("\n")

    private fun showText(title: String, text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun working(): AlertDialog {
        return MaterialAlertDialogBuilder(this)
            .setMessage(R.string.files_working)
            .setCancelable(false)
            .show()
    }

    /**
     * Runs a command that changes files while a "working" dialog is shown, then reads both
     * panes again.
     */
    private fun busy(command: String) {
        val dialog = working()
        runAsync({ ShizukuShell.exec(command) }) { result ->
            dialog.dismiss()
            if (result.isSuccess) {
                toast(getString(R.string.tools_done))
            } else {
                showText(getString(R.string.tool_apps_error_title), firstLines(result.message))
            }
            refresh()
        }
    }

    private fun busyThen(command: String, done: () -> Unit) = prepare(command, done)

    /**
     * Runs a command that gets a file ready, then [done] when it succeeded.
     */
    private fun prepare(command: String, done: () -> Unit) {
        val dialog = working()
        runAsync({ ShizukuShell.exec(command) }) { result ->
            dialog.dismiss()
            if (result.isSuccess) done() else showText(getString(R.string.tool_apps_error_title), firstLines(result.message))
        }
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun <T> runAsync(work: () -> T, done: (T) -> Unit) {
        thread(name = "shizuku-files") {
            val result = try {
                Result.success(work())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(done) { toast(it.toString()) }
            }
        }
    }
}
