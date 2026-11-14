package com.litetv.launcher

import android.content.ComponentName

/** One launchable app. Icons are NOT stored here; they are loaded lazily by [IconLoader]. */
class AppItem(
    val label: String,
    val component: ComponentName
) {
    val key: String = component.flattenToShortString()
}
