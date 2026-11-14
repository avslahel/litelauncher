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
    private val iconPadding = (22 * density).toInt()

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
        val banner = if (item.hasBanner) pm.getActivityBanner(item.component) else null
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
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setPadding(0, 0, 0, 0)
        } else {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }
        view.setImageDrawable(loaded.drawable)
    }

    private companion object {
        const val CACHE_SIZE = 48
    }
}
