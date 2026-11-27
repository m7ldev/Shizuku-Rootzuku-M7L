package moe.shizuku.manager.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ViewerActivityBinding
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Shows a file inside the app: text that can be edited and searched, the compiled XML of apk
 * files, dex files, pictures, video and audio. Any other file is shown as a hex dump.
 *
 * The file manager hands over a file that this app can read. When that file is a copy, the
 * original path is passed too, and saving copies the text back with the Shizuku service.
 * For pictures and media it may also pass the other files of the folder, swiping left or
 * right then moves between them.
 */
class ViewerActivity : AppBarActivity() {

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_SIBLINGS = "siblings"
        private const val EXTRA_INDEX = "index"

        private const val MAX_TEXT = 512 * 1024
        private const val MAX_BINARY = 96 * 1024 * 1024
        private const val HEX_BYTES = 32 * 1024

        private const val MENU_SAVE = 1
        private const val MENU_FIND = 2
        private const val MENU_UNDO = 3
        private const val MENU_REDO = 4
        private const val MENU_FULLSCREEN = 5

        private const val KIND_NONE = 0
        private const val KIND_TEXT = 1
        private const val KIND_IMAGE = 2
        private const val KIND_MEDIA = 3

        val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
        val MEDIA = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v", "flv", "wmv", "mpg", "mpeg",
            "mp3", "m4a", "ogg", "opus", "wav", "flac", "aac", "amr", "wma", "mid"
        )

        /**
         * @param origin where the text is saved to, or null when the file cannot be changed
         */
        fun intent(context: Context, file: File, name: String, origin: String?): Intent {
            return Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_FILE, file.absolutePath)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_ORIGIN, origin)
        }

        /**
         * Opens one of several pictures or media files that this app can read directly.
         */
        fun intent(context: Context, files: ArrayList<String>, index: Int): Intent {
            return Intent(context, ViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_SIBLINGS, files)
                .putExtra(EXTRA_INDEX, index)
        }
    }

    private lateinit var binding: ViewerActivityBinding
    private lateinit var file: File
    private var origin: String? = null
    private var siblings: List<String> = emptyList()
    private var index = 0

    private var kind = KIND_NONE
    private var editable = false
    private var fullscreen = false
    private var controller: MediaController? = null

    /** Where the video was when the screen was left, to continue there. */
    private var resumePosition = 0
    private var resumePlaying = false

    private var matches: List<Int> = emptyList()
    private var currentMatch = -1
    private var matchSpan: BackgroundColorSpan? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                fullscreen -> setFullscreen(false)
                binding.findBar.visibility == View.VISIBLE -> closeFind()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        onBackPressedDispatcher.addCallback(this, backCallback)

        siblings = intent.getStringArrayListExtra(EXTRA_SIBLINGS).orEmpty()
        val path = if (siblings.isNotEmpty()) {
            index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
                .coerceIn(0, siblings.size - 1)
            siblings[index]
        } else {
            intent.getStringExtra(EXTRA_FILE)
        }
        if (path == null) {
            finish()
            return
        }
        resumePosition = savedInstanceState?.getInt("position") ?: 0
        resumePlaying = resumePosition > 0

        // Swiping sideways moves to the next or previous file, a tap shows the controls
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val c = controller
                if (kind == KIND_MEDIA && c != null) {
                    if (c.isShowing) c.hide() else c.show()
                } else if (kind == KIND_IMAGE) {
                    setFullscreen(!fullscreen)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < 80 * resources.displayMetrics.density || abs(dx) < abs(dy) * 1.5f) return false
                step(if (dx < 0) 1 else -1)
                return true
            }
        })
        val touch = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
        binding.image.setOnTouchListener(touch)
        binding.video.setOnTouchListener(touch)

        setUpFind()
        open(File(path), intent.getStringExtra(EXTRA_NAME), if (siblings.isNotEmpty()) path else intent.getStringExtra(EXTRA_ORIGIN))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
        outState.putInt("position", resumePosition)
    }

    override fun onPause() {
        super.onPause()
        if (kind == KIND_MEDIA) {
            // The video view forgets its place when the screen is left
            val position = binding.video.currentPosition
            if (position > 0) resumePosition = position
            resumePlaying = binding.video.isPlaying
            binding.video.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (kind == KIND_MEDIA && resumePosition > 0) {
            binding.video.seekTo(resumePosition)
            if (resumePlaying) binding.video.start()
        }
    }

    // ---- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, R.string.viewer_save)
            .setIcon(R.drawable.ic_save_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_FIND, 0, R.string.viewer_find)
            .setIcon(R.drawable.ic_search_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_FULLSCREEN, 0, R.string.viewer_fullscreen)
            .setIcon(R.drawable.ic_fullscreen_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_UNDO, 0, R.string.viewer_undo)
        menu.add(0, MENU_REDO, 0, R.string.viewer_redo)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_SAVE)?.isVisible = kind == KIND_TEXT && editable
        menu.findItem(MENU_UNDO)?.isVisible = kind == KIND_TEXT && editable
        menu.findItem(MENU_REDO)?.isVisible = kind == KIND_TEXT && editable
        menu.findItem(MENU_FIND)?.isVisible = kind == KIND_TEXT
        menu.findItem(MENU_FULLSCREEN)?.isVisible = kind == KIND_IMAGE || kind == KIND_MEDIA
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE -> save()
            MENU_FIND -> openFind()
            MENU_UNDO -> binding.editor.onTextContextMenuItem(android.R.id.undo)
            MENU_REDO -> binding.editor.onTextContextMenuItem(android.R.id.redo)
            MENU_FULLSCREEN -> setFullscreen(true)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun updateBack() {
        backCallback.isEnabled = fullscreen || binding.findBar.visibility == View.VISIBLE
    }

    // ---- opening

    private fun message(text: CharSequence?) {
        binding.message.text = text
        binding.message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun open(target: File, name: String?, saveTo: String?) {
        file = target
        origin = saveTo
        val title = name ?: target.name
        supportActionBar?.title = title
        supportActionBar?.subtitle = if (siblings.size > 1) "${index + 1} / ${siblings.size}" else null

        binding.textScroll.visibility = View.GONE
        binding.image.visibility = View.GONE
        binding.video.visibility = View.GONE
        message(getString(R.string.tools_loading))
        editable = false

        when (title.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            in IMAGES -> showImage()
            in MEDIA -> showMedia()
            else -> showData()
        }
    }

    /** Moves to the next or previous file of the folder. */
    private fun step(by: Int) {
        if (siblings.size < 2) return
        val next = index + by
        if (next < 0 || next >= siblings.size) return
        if (kind == KIND_MEDIA) binding.video.stopPlayback()
        index = next
        resumePosition = 0
        resumePlaying = false
        open(File(siblings[index]), null, siblings[index])
    }

    private fun setKind(value: Int) {
        kind = value
        invalidateOptionsMenu()
    }

    private fun <T> load(work: () -> T, done: (T) -> Unit) {
        val expected = file
        thread(name = "viewer") {
            val result = try {
                Result.success(work())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                // The user may have swiped on to another file in the meantime
                if (isFinishing || isDestroyed || expected !== file) return@runOnUiThread
                result.fold(done) { message(it.toString()) }
            }
        }
    }

    // ---- full screen

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val root = binding.viewerRoot
        if (on) {
            supportActionBar?.hide()
            root.setPadding(0, 0, 0, 0)
            // A video fills the screen best when the phone is held sideways
            if (kind == KIND_MEDIA) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            supportActionBar?.show()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            root.requestApplyInsets()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = WindowInsets.Type.systemBars()
            if (on) window.insetsController?.hide(bars) else window.insetsController?.show(bars)
        } else {
            @Suppress("DEPRECATION")
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        if (!on) {
            // The padding below the toolbar comes back with a fresh layout
            val bar = theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
            val size = bar.getDimensionPixelSize(0, 0)
            bar.recycle()
            root.setPadding(0, size + (root.rootWindowInsets?.systemWindowInsetTop ?: 0), 0, root.rootWindowInsets?.systemWindowInsetBottom ?: 0)
        }
        updateBack()
    }

    // ---- pictures

    private fun showImage() {
        setKind(KIND_IMAGE)
        load({
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            // Large pictures are read at a lower resolution, the screen cannot show more anyway
            var sample = 1
            while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }) { bitmap ->
            if (bitmap == null) {
                showData()
            } else {
                message(null)
                binding.image.setImageBitmap(bitmap)
                binding.image.visibility = View.VISIBLE
            }
        }
    }

    // ---- video and audio

    private fun showMedia() {
        setKind(KIND_MEDIA)
        message(null)
        val video = binding.video
        video.visibility = View.VISIBLE
        val c = controller ?: MediaController(this).also {
            controller = it
            it.setAnchorView(video)
            video.setMediaController(it)
        }
        if (siblings.size > 1) {
            c.setPrevNextListeners({ step(1) }, { step(-1) })
        }
        video.setOnPreparedListener {
            if (resumePosition > 0) video.seekTo(resumePosition)
            video.start()
            c.show()
        }
        video.setOnCompletionListener { resumePosition = 0 }
        video.setOnErrorListener { _, _, _ ->
            video.visibility = View.GONE
            message(getString(R.string.viewer_cannot_play))
            true
        }
        video.setVideoPath(file.path)
    }

    // ---- text and everything else

    private fun showData() {
        setKind(KIND_NONE)
        load({
            val length = file.length()
            val head = ByteArray(minOf(length, 8192L).toInt())
            file.inputStream().use { it.read(head) }
            val binary = BinaryFormats.isBinaryXml(head) || BinaryFormats.isDex(head)
            when {
                binary && length > MAX_BINARY -> Triple(BinaryFormats.hexDump(head, HEX_BYTES), false, true)
                BinaryFormats.isBinaryXml(head) -> Triple(BinaryFormats.decodeXml(file.readBytes()), false, false)
                BinaryFormats.isDex(head) -> Triple(BinaryFormats.describeDex(file.readBytes()), false, false)
                BinaryFormats.looksLikeText(head) -> {
                    val bytes = ByteArray(minOf(length, MAX_TEXT.toLong()).toInt())
                    file.inputStream().use { input ->
                        var read = 0
                        while (read < bytes.size) {
                            val n = input.read(bytes, read, bytes.size - read)
                            if (n < 0) break
                            read += n
                        }
                    }
                    // Only a file that is shown completely may be saved back
                    Triple(String(bytes, Charsets.UTF_8), length <= MAX_TEXT, length > MAX_TEXT)
                }
                else -> {
                    val bytes = ByteArray(minOf(length, HEX_BYTES.toLong()).toInt())
                    file.inputStream().use { it.read(bytes) }
                    Triple(BinaryFormats.hexDump(bytes, HEX_BYTES), false, length > HEX_BYTES)
                }
            }
        }) { (text, canEdit, truncated) ->
            message(null)
            binding.textScroll.visibility = View.VISIBLE
            binding.editor.setText(text)
            editable = canEdit && origin != null
            if (!editable) {
                // Still selectable for copying, but the keyboard stays away
                binding.editor.keyListener = null
                binding.editor.setTextIsSelectable(true)
            }
            supportActionBar?.subtitle = when {
                truncated -> getString(R.string.viewer_truncated)
                !editable -> getString(R.string.viewer_read_only)
                else -> null
            }
            setKind(KIND_TEXT)
        }
    }

    private fun save() {
        val target = origin ?: return
        val text = binding.editor.text.toString()
        load({
            val direct = File(target)
            val written = try {
                if (direct.canWrite()) {
                    direct.writeText(text, Charsets.UTF_8)
                    true
                } else false
            } catch (e: Throwable) {
                false
            }
            if (written) {
                null
            } else {
                // This app cannot write there, the service copies a temporary file instead
                val folder = File(externalCacheDir ?: cacheDir, "save")
                folder.mkdirs()
                val temp = File(folder, "edit.tmp")
                temp.writeText(text, Charsets.UTF_8)
                val result = ShizukuShell.exec(
                    "cat ${ShizukuShell.quote(temp.absolutePath)} > ${ShizukuShell.quote(target)}"
                )
                temp.delete()
                if (result.isSuccess) null else result.message
            }
        }) { error ->
            message(null)
            Toast.makeText(this, error ?: getString(R.string.viewer_saved_to, target), Toast.LENGTH_LONG).show()
        }
    }

    // ---- find and replace

    private fun setUpFind() {
        binding.findText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                search(0)
            }
        })
        binding.findCase.setOnCheckedChangeListener { _, _ -> search(0) }
        binding.findNext.setOnClickListener { move(1) }
        binding.findPrev.setOnClickListener { move(-1) }
        binding.findReplace.setOnClickListener { replaceCurrent() }
        binding.findReplaceAll.setOnClickListener { replaceAll() }
        binding.findClose.setOnClickListener { closeFind() }
    }

    private fun openFind() {
        binding.findBar.visibility = View.VISIBLE
        val canReplace = editable
        binding.replaceText.visibility = if (canReplace) View.VISIBLE else View.GONE
        binding.findReplace.visibility = if (canReplace) View.VISIBLE else View.GONE
        binding.findReplaceAll.visibility = if (canReplace) View.VISIBLE else View.GONE
        binding.findText.requestFocus()
        updateBack()
        search(0)
    }

    private fun closeFind() {
        binding.findBar.visibility = View.GONE
        clearMark()
        updateBack()
    }

    private fun clearMark() {
        matchSpan?.let { binding.editor.text.removeSpan(it) }
        matchSpan = null
    }

    /**
     * Finds all places of the search text and marks the first one at or after [from].
     */
    private fun search(from: Int) {
        clearMark()
        val needle = binding.findText.text.toString()
        val text = binding.editor.text.toString()
        val found = ArrayList<Int>()
        if (needle.isNotEmpty()) {
            val ignoreCase = !binding.findCase.isChecked
            var at = text.indexOf(needle, 0, ignoreCase)
            while (at >= 0 && found.size < 100000) {
                found.add(at)
                at = text.indexOf(needle, at + needle.length, ignoreCase)
            }
        }
        matches = found
        currentMatch = if (found.isEmpty()) -1 else found.indexOfFirst { it >= from }.let { if (it < 0) 0 else it }
        showMatch()
    }

    private fun showMatch() {
        clearMark()
        val needle = binding.findText.text.toString()
        if (currentMatch < 0 || needle.isEmpty()) {
            binding.findStatus.text = if (needle.isEmpty()) "" else getString(R.string.viewer_find_none)
            return
        }
        binding.findStatus.text = getString(R.string.viewer_find_count, currentMatch + 1, matches.size)
        val start = matches[currentMatch]
        val span = BackgroundColorSpan(getColor(R.color.neon_card_stroke))
        matchSpan = span
        binding.editor.text.setSpan(span, start, start + needle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Bring the line of the match into view
        binding.editor.post {
            val layout = binding.editor.layout ?: return@post
            val top = layout.getLineTop(layout.getLineForOffset(start))
            binding.textScroll.smoothScrollTo(0, (top - binding.textScroll.height / 3).coerceAtLeast(0))
        }
    }

    private fun move(by: Int) {
        if (matches.isEmpty()) return
        currentMatch = (currentMatch + by + matches.size) % matches.size
        showMatch()
    }

    private fun replaceCurrent() {
        if (!editable || currentMatch < 0) return
        val needle = binding.findText.text.toString()
        val replacement = binding.replaceText.text.toString()
        val start = matches[currentMatch]
        clearMark()
        binding.editor.text.replace(start, start + needle.length, replacement)
        search(start + replacement.length)
    }

    private fun replaceAll() {
        if (!editable || matches.isEmpty()) return
        val needle = binding.findText.text.toString()
        val replacement = binding.replaceText.text.toString()
        val count = matches.size
        clearMark()
        val text = binding.editor.text.toString().replace(needle, replacement, !binding.findCase.isChecked)
        binding.editor.setText(text)
        search(0)
        Toast.makeText(this, getString(R.string.viewer_replaced, count), Toast.LENGTH_SHORT).show()
    }
}
