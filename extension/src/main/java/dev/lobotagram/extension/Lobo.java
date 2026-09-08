package dev.lobotagram.extension;

import android.util.Log;

/**
 * Logging for every lobotagram extension class.
 *
 * <p>Trace mode is a runtime switch, not a build flag:
 * <pre>adb shell setprop log.tag.Lobotagram DEBUG</pre>
 * turns {@link #trace()} on without rebuilding or reinstalling anything.
 */
public final class Lobo {
    public static final String TAG = "Lobotagram";

    private Lobo() {
    }

    /** True when trace logging is enabled for the {@code Lobotagram} tag. */
    public static boolean trace() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    /** Logs at debug level. Cheap when trace mode is off. */
    public static void d(String msg) {
        if (trace()) {
            Log.d(TAG, msg);
        }
    }

    /** Logs at info level. Always emitted; use sparingly. */
    public static void i(String msg) {
        Log.i(TAG, msg);
    }
}
