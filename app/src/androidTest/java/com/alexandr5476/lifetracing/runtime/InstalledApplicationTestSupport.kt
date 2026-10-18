package com.alexandr5476.lifetracing.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Read installed target metadata; the instrumentation Application may be a substitute. */
internal fun installedApplicationClassName(context: Context): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(0))
            .className
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, 0).className
    }
