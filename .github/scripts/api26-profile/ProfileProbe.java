package org.lifetracing.ciprofile;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/** Reads the Android framework profile; does not mutate the guest or run product tests. */
public final class ProfileProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            boolean actual = Class.forName("android.app.ActivityManager")
                .getDeclaredField("ENABLE_TASK_SNAPSHOTS").getBoolean(null);
            String property = (String) Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String.class).invoke(null, "persist.enable_task_snapshots");
            result.putString("snapshot_actual", Boolean.toString(actual));
            result.putString("snapshot_property", property);
            result.putInt("actual_api", android.os.Build.VERSION.SDK_INT);
            Display display = ((WindowManager) getTargetContext().getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
            DisplayMetrics real = new DisplayMetrics();
            display.getRealMetrics(real);
            DisplayMetrics app = new DisplayMetrics();
            display.getMetrics(app);
            result.putInt("display_width_px", real.widthPixels);
            result.putInt("display_height_px", real.heightPixels);
            result.putInt("display_density_dpi", real.densityDpi);
            result.putInt("app_width_px", app.widthPixels);
            result.putInt("app_height_px", app.heightPixels);
            finish(0, result);
        } catch (Throwable error) {
            result.putString("probe_error", error.toString());
            finish(1, result);
        }
    }
}
