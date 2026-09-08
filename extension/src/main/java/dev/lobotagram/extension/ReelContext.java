package dev.lobotagram.extension;

import java.util.Locale;

/**
 * What the Reels viewer was opened from, and whether it is on screen.
 *
 * <p>{@code onViewerSource} is called from the bytecode hook the "Lock Reels
 * viewer" patch installs on the clips-viewer config constructor: every path
 * that opens a reel (a DM, a profile, a deep link, the Reels tab) builds that
 * config and passes the source enum through it.
 *
 * <p>In v1 nothing <em>acts</em> on the source: every viewer is locked to the
 * single reel it opened on, because in a lobotomized build there is never a
 * next reel to scroll to. The state is recorded anyway so that a later toggle
 * ("allow scrolling when the viewer was not opened from a DM") needs no new
 * fingerprint, and so that {@code adb logcat -s Lobotagram} says which entry
 * point a locked viewer came from.
 */
public final class ReelContext {

    /** Lower-cased name of the last clips-viewer source enum constant seen. */
    private static volatile String source = "";

    /** When that source was recorded, in {@link System#currentTimeMillis()}. */
    private static volatile long sourceAtMs;

    /** Whether the Reels viewer is currently in the window. */
    private static volatile boolean viewerVisible;

    /** When {@link #viewerVisible} last changed. */
    private static volatile long viewerVisibleAtMs;

    private ReelContext() {
    }

    /**
     * Records the clips-viewer source. Called from patched bytecode, so it must
     * never throw: a crash here would take Instagram down with it.
     *
     * @param source the clips-viewer source enum constant, possibly null
     */
    public static void onViewerSource(Enum<?> source) {
        try {
            String name = source == null ? "" : source.name().toLowerCase(Locale.ROOT);
            if (name.equals(ReelContext.source)) {
                return;
            }
            ReelContext.source = name;
            ReelContext.sourceAtMs = System.currentTimeMillis();
            Lobo.d("reel viewer source: " + name);
        } catch (Throwable t) {
            Lobo.d("ReelContext.onViewerSource failed: " + t);
        }
    }

    /** The last recorded source, lower-cased, or "" if none was seen yet. */
    public static String source() {
        return source;
    }

    /** When the last source was recorded, or 0. */
    public static long sourceAtMs() {
        return sourceAtMs;
    }

    /**
     * Whether the last viewer was opened from Direct. The clips-viewer source
     * enum spells every DM entry point with "direct" in it (DIRECT,
     * DIRECT_INBOX, DIRECT_THREAD, ...), so a substring test covers them all
     * without pinning the exact constant names of one release.
     */
    public static boolean isDirect() {
        return source.contains("direct");
    }

    /** Called by {@link ViewerLock} when the viewer appears or disappears. */
    public static void onViewerVisible(boolean visible) {
        if (visible == viewerVisible) {
            return;
        }
        viewerVisible = visible;
        viewerVisibleAtMs = System.currentTimeMillis();
        Lobo.d("reel viewer " + (visible ? "shown" : "gone")
                + " (source=" + source + ", direct=" + isDirect() + ")");
    }

    /** Whether the Reels viewer is on screen right now. */
    public static boolean viewerVisible() {
        return viewerVisible;
    }

    /** When the viewer last appeared or disappeared, or 0. */
    public static long viewerVisibleAtMs() {
        return viewerVisibleAtMs;
    }
}
