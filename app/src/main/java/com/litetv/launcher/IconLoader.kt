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
class IconLoader(private val pm: PackageManager, density: Float) {

    private class Loaded(val drawable: Drawable, val isBanner: Boolean)

    private val cache = LruCache<String, Loaded>(CACHE_SIZE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // Square icons are inset inside the 16:9 tile so they do not look oversized
    // next to app banners. Banners still fill the tile completely.
    private val iconPadding = (ICON_PADDING_DP * density).toInt()

    fun load(item: AppItem, view: ImageView) {
        view.tag = item.key
        val hit = cache.get(item.key)
        if (hit != null) {
            show(view, hit)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val loaded = fetch(item) ?: return@execute
            cache.put(item.key, loaded)
            main.post {
                // The view may have been recycled for another app in the meantime.
                if (view.tag == item.key) show(view, loaded)
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

    private fun show(view: ImageView, loaded: Loaded) {
        if (loaded.isBanner) {
            // Banners are already 16:9 artwork, so let them fill the tile edge to edge.
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setPadding(0, 0, 0, 0)
        } else {
            // Square icons are inset so they do not look oversized next to banners.
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }
        view.setImageDrawable(loaded.drawable)
    }

    private companion object {
        const val CACHE_SIZE = 48
        // A tile is about 112dp tall; 42dp leaves the icon roughly half the size
        // it had at the old 22dp inset. Banners are unaffected and stay full-bleed.
        const val ICON_PADDING_DP = 42
    }
}
