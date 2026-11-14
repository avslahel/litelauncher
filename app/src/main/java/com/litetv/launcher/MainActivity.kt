package com.litetv.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val memInfo = ActivityManager.MemoryInfo()

    private lateinit var activityManager: ActivityManager
    private lateinit var grid: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var iconLoader: IconLoader
    private lateinit var ramText: TextView
    private lateinit var ramBar: ProgressBar
    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar

    private var packages: List<String> = emptyList()
    private var wantInitialFocus = true

    /** Refreshes RAM/storage every few seconds, only while the launcher is on screen. */
    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            ui.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private val reloadApps = Runnable { loadApps() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Debounce: installs/updates can fire several broadcasts in a row.
            ui.removeCallbacks(reloadApps)
            ui.postDelayed(reloadApps, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        ramText = findViewById(R.id.ram_text)
        ramBar = findViewById(R.id.ram_bar)
        storageText = findViewById(R.id.storage_text)
        storageBar = findViewById(R.id.storage_bar)

        setupGrid()
        loadWallpaper()
        findViewById<View>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.btn_clean).setOnClickListener { cleanRam() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }

        updateStats()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        ui.post(statsTick)
    }

    override fun onPause() {
        ui.removeCallbacks(statsTick)
        super.onPause()
    }

    /** HOME pressed while the launcher is already running: jump back to the first app. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        grid.scrollToPosition(0)
        wantInitialFocus = true
        grid.post {
            val first = grid.findViewHolderForAdapterPosition(0)
            if (first != null) {
                wantInitialFocus = false
                first.itemView.requestFocus()
            }
        }
    }

    /** A launcher has nowhere to go "back" to. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = Unit

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        ui.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        iconLoader.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- grid

    private fun setupGrid() {
        val density = resources.displayMetrics.density
        val gridWidth = resources.displayMetrics.widthPixels - (2 * 48 * density).toInt()
        val span = (gridWidth / (TARGET_TILE_DP * density)).toInt().coerceIn(3, 8)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pinned = prefs.getStringSet(KEY_PINNED, null)?.toMutableSet() ?: mutableSetOf()

        iconLoader = IconLoader(packageManager)
        adapter = AppAdapter(
            cellWidthPx = gridWidth / span,
            loader = iconLoader,
            pinned = pinned,
            onLaunch = { launch(it) },
            onLongPress = { togglePin(it) },
            onPinsChanged = { prefs.edit().putStringSet(KEY_PINNED, HashSet(it)).apply() }
        )

        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, span)
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        (grid.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // Put focus on the first app as soon as it appears, so the remote works immediately.
        grid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (wantInitialFocus && grid.getChildAdapterPosition(view) == 0) {
                    wantInitialFocus = false
                    view.post { view.requestFocus() }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    private fun loadApps() {
        worker.execute {
            val apps = queryApps()
            ui.post {
                if (isFinishing || isDestroyed) return@post
                packages = apps.map { it.component.packageName }.distinct()
                adapter.setData(apps)
            }
        }
    }

    /** TV apps (leanback) first, then regular launcher apps. One entry per package. */
    private fun queryApps(): List<AppItem> {
        val pm = packageManager
        val seen = HashSet<String>()
        val result = ArrayList<AppItem>()
        for (category in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                result.add(
                    AppItem(
                        label = info.loadLabel(pm).toString(),
                        component = ComponentName(pkg, activity.name)
                    )
                )
            }
        }
        return result
    }

    // ----------------------------------------------------------- wallpaper

    /**
     * Optional wallpaper: if res/drawable-nodpi/wallpaper.jpg (or .png) exists it becomes the
     * background, darkened so text stays readable. Without it the dark gradient is kept.
     * The image is shrunk to screen size on a background thread, so it costs only a few MB of RAM.
     */
    private fun loadWallpaper() {
        val resId = resources.getIdentifier("wallpaper", "drawable", packageName)
        if (resId == 0) return
        val metrics = resources.displayMetrics
        val targetW = maxOf(metrics.widthPixels, metrics.heightPixels)
        val targetH = minOf(metrics.widthPixels, metrics.heightPixels)
        worker.execute {
            val bitmap = decodeWallpaper(resId, targetW, targetH) ?: return@execute
            ui.post {
                if (isFinishing || isDestroyed) {
                    bitmap.recycle()
                    return@post
                }
                val layers = arrayOf<Drawable>(BitmapDrawable(resources, bitmap), ColorDrawable(WALLPAPER_DIM))
                findViewById<View>(R.id.root).background = LayerDrawable(layers)
            }
        }
    }

    /** Decodes at reduced size, then center-crops to the screen's aspect ratio (no stretching). */
    private fun decodeWallpaper(resId: Int, targetW: Int, targetH: Int): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, resId, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // half the memory of ARGB_8888
            }
            val source = BitmapFactory.decodeResource(resources, resId, options) ?: return null

            val sourceRatio = source.width.toFloat() / source.height
            val targetRatio = targetW.toFloat() / targetH
            val cropW: Int
            val cropH: Int
            if (sourceRatio > targetRatio) {
                cropH = source.height
                cropW = (source.height * targetRatio).toInt()
            } else {
                cropW = source.width
                cropH = (source.width / targetRatio).toInt()
            }
            val cropped = Bitmap.createBitmap(
                source, (source.width - cropW) / 2, (source.height - cropH) / 2, cropW, cropH
            )
            if (cropped !== source) source.recycle()

            val result = if (cropW > targetW) Bitmap.createScaledBitmap(cropped, targetW, targetH, true) else cropped
            if (result !== cropped) cropped.recycle()
            return result
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    // ------------------------------------------------------------- actions

    private fun launch(item: AppItem) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(item.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.cant_open, item.label))
        }
    }

    private fun togglePin(position: Int) {
        val item = adapter.itemAt(position)
        val result = adapter.togglePin(position)
        grid.scrollToPosition(result.newPosition)
        toast(getString(if (result.pinned) R.string.pinned else R.string.unpinned, item.label))
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.cant_open_settings))
        }
    }

    /**
     * Asks Android to stop the background processes of the installed apps.
     * Android already manages memory by itself, so treat this as an occasional extra,
     * not something to press constantly: killed apps have to reload next time you open them.
     */
    private fun cleanRam() {
        val before = SystemStats.ram(activityManager, ActivityManager.MemoryInfo()).used
        val targets = packages
        worker.execute {
            for (pkg in targets) {
                try {
                    activityManager.killBackgroundProcesses(pkg)
                } catch (e: Exception) {
                    // Ignore apps we are not allowed to touch.
                }
            }
            ui.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                updateStats()
                val after = SystemStats.ram(activityManager, ActivityManager.MemoryInfo()).used
                val freedMb = ((before - after) shr 20).coerceAtLeast(0)
                toast(getString(R.string.freed, freedMb))
            }, 800)
        }
    }

    // --------------------------------------------------------------- stats

    private fun updateStats() {
        bindUsage(ramText, ramBar, SystemStats.ram(activityManager, memInfo))
        bindUsage(storageText, storageBar, SystemStats.storage())
    }

    private fun bindUsage(text: TextView, bar: ProgressBar, usage: SystemStats.Usage) {
        val label = formatUsage(usage)
        if (text.text.toString() != label) text.text = label
        if (bar.progress != usage.percent) {
            bar.progress = usage.percent
            bar.progressTintList = ColorStateList.valueOf(colorFor(usage.percent))
        }
    }

    private fun formatUsage(usage: SystemStats.Usage): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        return if (usage.total >= gb) {
            getString(R.string.usage_gb, usage.used / gb, usage.total / gb)
        } else {
            getString(R.string.usage_mb, usage.used shr 20, usage.total shr 20)
        }
    }

    private fun colorFor(percent: Int): Int = when {
        percent >= 90 -> COLOR_CRITICAL
        percent >= 75 -> COLOR_WARNING
        else -> COLOR_OK
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS = "launcher"
        const val KEY_PINNED = "pinned"
        const val STATS_INTERVAL_MS = 3000L
        const val TARGET_TILE_DP = 100

        val COLOR_OK = Color.parseColor("#3DDC97")
        val COLOR_WARNING = Color.parseColor("#FFB020")
        val COLOR_CRITICAL = Color.parseColor("#FF5252")

        /** Dark layer over the wallpaper. Alpha 0-255: higher = darker, easier to read. */
        val WALLPAPER_DIM = Color.argb(140, 0, 0, 0)
    }
}
