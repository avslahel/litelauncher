package com.litetv.launcher

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loads app banners/icons off the main thread, only for tiles that are actually shown,
 * and keeps a small cache. This keeps memory low and scrolling smooth on weak TV hardware.
 */
class IconLoader(private val pm: PackageManager) {

    private class Loaded(val drawable: Drawable, val isBanner: Boolean)

    private val cache = LruCache<String, Loaded>(CACHE_SIZE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Square icons are inset relative to the tile height so they keep the same
    // proportion whatever the tile size. Banners fill the tile completely.
    fun load(item: AppItem, view: ImageView, tileHeightPx: Int) {
        view.tag = item.key
        val hit = cache.get(item.key)
        if (hit != null) {
            show(view, hit, tileHeightPx)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val loaded = fetch(item) ?: return@execute
            cache.put(item.key, loaded)
            main.post {
                // The view may have been recycled for another app in the meantime.
                if (view.tag == item.key) show(view, loaded, tileHeightPx)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        cache.evictAll()
    }

    private fun fetch(item: AppItem): Loaded? = try {
        // Some TV apps declare the banner on the activity, others on <application>.
        // getActivityBanner only reads the activity, so fall back to the app banner.
        val banner = pm.getActivityBanner(item.component)
            ?: pm.getApplicationBanner(item.component.packageName)
        if (banner != null) {
            Loaded(banner, true)
        } else {
            Loaded(pm.getActivityIcon(item.component), false)
        }
    } catch (e: Exception) {
        null
    }

    private fun show(view: ImageView, loaded: Loaded, tileHeightPx: Int) {
        if (loaded.isBanner) {
            // Banners are 16:9 artwork, so they fill the tile edge to edge.
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setPadding(0, 0, 0, 0)
        } else {
            // Square icons take about half the tile height, leaving a margin that
            // scales with the tile so small tiles stay readable.
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (tileHeightPx * ICON_INSET_FRACTION).toInt()
            view.setPadding(pad, pad, pad, pad)
        }
        view.setImageDrawable(loaded.drawable)
    }

    private companion object {
        const val CACHE_SIZE = 48
        // Icon artwork occupies half the tile height: 0.25 inset on each side.
        const val ICON_INSET_FRACTION = 0.25f
    }
}
