package com.litetv.launcher

import android.content.ComponentName

/** One launchable app. Icons are NOT stored here; they are loaded lazily by [IconLoader]. */
class AppItem(
    val label: String,
    val component: ComponentName,
    /** True if the app ships a wide TV banner (320x180). Otherwise its square icon is used. */
    val hasBanner: Boolean
) {
    val key: String = component.flattenToShortString()
}
