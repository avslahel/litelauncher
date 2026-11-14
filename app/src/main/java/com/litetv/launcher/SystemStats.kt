package com.litetv.launcher

import android.app.ActivityManager
import android.os.Environment
import android.os.StatFs

/** Cheap system readings. Both calls are a single syscall / binder call, safe to run every few seconds. */
object SystemStats {

    class Usage(val used: Long, val total: Long) {
        val percent: Int
            get() = if (total > 0) ((used * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    /** System-wide RAM. "Used" = total minus what Android reports as available (free + reclaimable cache). */
    fun ram(am: ActivityManager, info: ActivityManager.MemoryInfo): Usage {
        am.getMemoryInfo(info)
        return Usage(info.totalMem - info.availMem, info.totalMem)
    }

    /** Internal storage (the /data partition, where apps and their data live). */
    fun storage(): Usage = try {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.totalBytes
        Usage(total - fs.availableBytes, total)
    } catch (e: Exception) {
        Usage(0, 0)
    }
}
