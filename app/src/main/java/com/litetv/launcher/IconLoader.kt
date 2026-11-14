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
    // next to app banners. Banners get their own inset so all artwork can be
    // scaled down by the same amount.
    private val iconPadding = (ICON_PADDING_DP * density).toInt()
    private val bannerPadding = (BANNER_PADDING_DP * density).toInt()

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
        // Artwork is always scaled down inside the tile, never cropped.
        // A banner would otherwise fill the tile completely, so it gets its own
        // inset; square icons need a larger one to shrink by the same amount.
        val pad = if (loaded.isBanner) bannerPadding else iconPadding
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.setPadding(pad, pad, pad, pad)
        view.setImageDrawable(loaded.drawable)
    }

    private companion object {
        const val CACHE_SIZE = 48
        // Tiles are about 112dp tall. 28dp inset halves a banner that used to
        // fill the tile; 42dp halves a square icon that already had a 28dp inset.
        const val BANNER_PADDING_DP = 28
        const val ICON_PADDING_DP = 42
    }
}
