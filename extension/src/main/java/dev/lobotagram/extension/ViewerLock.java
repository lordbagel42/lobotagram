package dev.lobotagram.extension;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The "watch one, scroll none" half of the Reels lock: a reel opened from a DM,
 * a profile or a deep link plays, but the viewer cannot be swiped onward and
 * never advances by itself.
 *
 * <p>Driven from the global-layout observer {@link Hiders} installs, so it
 * needs no fingerprint of its own. Every layout pass asks: is the Reels viewer
 * in this window? The answer is a resource-name lookup of
 * {@code clips_viewer_view_pager} (and {@code clips_video_container} as a
 * second opinion), never a class name — the viewer's own classes are
 * obfuscated and renamed every release, its resource names are not.
 *
 * <h3>How the lock works</h3>
 * In Instagram 435.0.0.37.76 the vertical pager behind
 * {@code clips_viewer_view_pager} is an
 * {@code androidx.viewpager2.widget.ViewPager2} whose class name survived
 * minification and whose plain accessors — {@code setUserInputEnabled(boolean)},
 * {@code getScrollState()}, {@code getCurrentItem()},
 * {@code setCurrentItem(int)} — kept their names even though its callback
 * registration did not. So the primary lock is one reflective
 * {@code setUserInputEnabled(false)}: the pager stops handling drags itself,
 * while taps (pause), double taps (like) and the app's own programmatic paging
 * still work, which is exactly the wanted feel.
 *
 * <p>It is re-applied on every layout pass rather than once, because the app
 * re-enables user input whenever it rebinds the viewer.
 *
 * <p>If a future build's pager has no {@code setUserInputEnabled} — a plain
 * {@code RecyclerView}-based pager, say — two weaker measures take over: a
 * {@link View.OnTouchListener} on the pager that swallows vertical drags past
 * the touch slop, and a watchdog that pins {@code setCurrentItem} back to the
 * page the viewer opened on whenever the pager comes to rest somewhere else.
 * Both are best-effort: a touch listener on a ViewGroup only sees what its
 * children did not consume, and the real interception point
 * ({@code onInterceptTouchEvent}, or {@code RecyclerView.OnItemTouchListener})
 * cannot be reached without subclassing an obfuscated type. The network gate
 * denying the next reel is what makes that acceptable: the worst case is a
 * blank page rather than another reel.
 */
public final class ViewerLock {

    /** The Reels viewer's vertical pager. */
    private static final String CLIPS_VIEWER_VIEW_PAGER = "clips_viewer_view_pager";

    /** The video surface inside one reel; present even if the pager id moves. */
    private static final String CLIPS_VIDEO_CONTAINER = "clips_video_container";

    /** ViewPager2 / RecyclerView idle scroll state. */
    private static final int STATE_IDLE = 0;

    /** One lock per pager view, dropped with the view. */
    private static final Map<View, Lock> LOCKS = new WeakHashMap<View, Lock>();

    private ViewerLock() {
    }

    /**
     * Called once per layout pass with the window that was just laid out.
     *
     * @param context     a context for resolving resource names
     * @param windowRoot  the root of the window to search
     */
    static void apply(Context context, View windowRoot) {
        try {
            View pager = findByName(context, windowRoot, CLIPS_VIEWER_VIEW_PAGER);
            View container = pager != null
                    ? null
                    : findByName(context, windowRoot, CLIPS_VIDEO_CONTAINER);

            boolean visible = pager != null || container != null;
            ReelContext.onViewerVisible(visible);
            if (!visible) {
                releaseAll();
                return;
            }

            if (pager == null) {
                // The pager id is gone from this build but a reel is on screen:
                // climb to the nearest ancestor that behaves like a pager.
                pager = pagerAncestorOf(container);
                if (pager == null) {
                    return;
                }
            }

            Lock lock = LOCKS.get(pager);
            if (lock == null) {
                lock = new Lock(pager);
                LOCKS.put(pager, lock);
                lock.install();
            }
            lock.enforce();
        } catch (Throwable t) {
            Lobo.d("ViewerLock.apply failed: " + t);
        }
    }

    private static View findByName(Context context, View windowRoot, String name) {
        int id = Hiders.resolveId(context, name);
        return id == 0 ? null : windowRoot.findViewById(id);
    }

    /** Nearest ancestor that exposes a pager-shaped API. */
    private static View pagerAncestorOf(View view) {
        ViewParent parent = view.getParent();
        for (int depth = 0; depth < 8 && parent instanceof ViewGroup; depth++) {
            View candidate = (ViewGroup) parent;
            if (methodOrNull(candidate, "setUserInputEnabled", boolean.class) != null
                    || methodOrNull(candidate, "setCurrentItem", int.class) != null) {
                return candidate;
            }
            parent = candidate.getParent();
        }
        return null;
    }

    /** Forget the locks so a viewer opened later re-reads its own state. */
    private static void releaseAll() {
        if (LOCKS.isEmpty()) {
            return;
        }
        for (Lock lock : LOCKS.values()) {
            if (lock != null) {
                lock.reset();
            }
        }
    }

    /** Reflection that never throws: absent methods come back as null. */
    private static Method methodOrNull(View view, String name, Class<?>... parameterTypes) {
        try {
            return view.getClass().getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Everything applied to one pager view. */
    private static final class Lock implements ViewTreeObserver.OnScrollChangedListener {
        private final View pager;
        private final Method setUserInputEnabled;
        private final Method getScrollState;
        private final Method getCurrentItem;
        private final Method setCurrentItem;

        /** Page the viewer opened on; the watchdog pins back to it. */
        private int pinned = -1;
        private boolean loggedLock;
        private boolean loggedRepin;

        Lock(View pager) {
            this.pager = pager;
            this.setUserInputEnabled = methodOrNull(pager, "setUserInputEnabled", boolean.class);
            this.getScrollState = methodOrNull(pager, "getScrollState");
            this.getCurrentItem = methodOrNull(pager, "getCurrentItem");
            this.setCurrentItem = methodOrNull(pager, "setCurrentItem", int.class);
        }

        void install() {
            Lobo.d("locking reels pager " + pager.getClass().getName()
                    + " (userInputEnabled=" + (setUserInputEnabled != null)
                    + ", currentItem=" + (getCurrentItem != null && setCurrentItem != null)
                    + ", source=" + ReelContext.source() + ")");

            if (setUserInputEnabled != null) {
                // The pager itself will refuse drags; nothing weaker is needed,
                // and neither weaker measure is free of side effects.
                return;
            }

            Lobo.i("reels pager has no setUserInputEnabled; falling back to a touch"
                    + " swallower and a page watchdog");

            ViewTreeObserver observer = pager.getViewTreeObserver();
            if (observer != null) {
                observer.addOnScrollChangedListener(this);
            }
            installTouchSwallower();
        }

        /**
         * Re-applied every layout pass: the app turns user input back on when
         * it rebinds the viewer, so setting it once would not hold.
         */
        void enforce() {
            if (setUserInputEnabled != null) {
                try {
                    setUserInputEnabled.invoke(pager, Boolean.FALSE);
                    if (!loggedLock) {
                        loggedLock = true;
                        Lobo.d("reels pager user input disabled");
                    }
                } catch (Throwable t) {
                    Lobo.d("setUserInputEnabled failed: " + t);
                }
            }
            if (pinned < 0) {
                int current = currentItem();
                if (current >= 0) {
                    pinned = current;
                }
            }
        }

        /** Called when the viewer leaves the window. */
        void reset() {
            pinned = -1;
            loggedLock = false;
            loggedRepin = false;
        }

        @Override
        public void onScrollChanged() {
            try {
                if (setCurrentItem == null || pinned < 0) {
                    return;
                }
                if (scrollState() != STATE_IDLE) {
                    return; // still moving; the release fires another event
                }
                int current = currentItem();
                if (current < 0 || current == pinned) {
                    return;
                }
                final int target = pinned;
                if (!loggedRepin) {
                    loggedRepin = true;
                    Lobo.i("reels pager drifted to " + current + "; pinning back to " + target);
                }
                pager.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            setCurrentItem.invoke(pager, Integer.valueOf(target));
                        } catch (Throwable t) {
                            Lobo.d("setCurrentItem failed: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                Lobo.d("ViewerLock.onScrollChanged failed: " + t);
            }
        }

        /**
         * Swallows vertical drags past the touch slop while letting taps,
         * double taps and horizontal gestures through. Only installed when the
         * pager has no input switch, because it replaces whatever touch
         * listener the pager already had.
         */
        private void installTouchSwallower() {
            try {
                final int slop = ViewConfiguration.get(pager.getContext()).getScaledTouchSlop();
                pager.setOnTouchListener(new View.OnTouchListener() {
                    private float downX;
                    private float downY;
                    private boolean swallowing;

                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                downX = event.getX();
                                downY = event.getY();
                                swallowing = false;
                                return false;
                            case MotionEvent.ACTION_MOVE:
                                if (!swallowing) {
                                    float dx = Math.abs(event.getX() - downX);
                                    float dy = Math.abs(event.getY() - downY);
                                    swallowing = dy > slop && dy > dx;
                                }
                                return swallowing;
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                boolean handled = swallowing;
                                swallowing = false;
                                return handled;
                            default:
                                return swallowing;
                        }
                    }
                });
            } catch (Throwable t) {
                Lobo.d("could not install the touch swallower: " + t);
            }
        }

        private int currentItem() {
            if (getCurrentItem == null) {
                return -1;
            }
            try {
                Object value = getCurrentItem.invoke(pager);
                return value instanceof Integer ? ((Integer) value).intValue() : -1;
            } catch (Throwable ignored) {
                return -1;
            }
        }

        private int scrollState() {
            if (getScrollState == null) {
                return STATE_IDLE;
            }
            try {
                Object value = getScrollState.invoke(pager);
                return value instanceof Integer ? ((Integer) value).intValue() : STATE_IDLE;
            } catch (Throwable ignored) {
                return STATE_IDLE;
            }
        }
    }
}
