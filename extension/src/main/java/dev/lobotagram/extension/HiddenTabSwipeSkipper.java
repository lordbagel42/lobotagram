package dev.lobotagram.extension;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes every tab hidden from the bottom bar unreachable by horizontal swipe.
 *
 * <p>Adapted from Feurstagram's {@code HiddenTabSwipeSkipper} (GPLv3).
 *
 * <p>Hiding the Reels tab icon (see {@link Hiders}) only removes the button:
 * the main {@code swipeable_tab_view_pager} keeps the page, so a swipe still
 * lands on the surface that was supposed to be gone. Reels would be one flick
 * of the thumb away, which defeats the point of the build.
 *
 * <p>The instant a swipe is released toward a page whose tab is gone, this
 * listener sends it on to the nearest still-visible tab in the direction of
 * travel (falling back to the other direction when there is nothing that way),
 * so swiping only ever cycles through visible tabs.
 *
 * <h3>How the re-aim is done</h3>
 * In two halves, and the destination is only ever reached through the app's own
 * navigation path:
 *
 * <ol>
 *   <li>the in-flight swipe is cancelled with {@code setCurrentItem} back to the
 *       page the pager itself reported at the start of the drag — read from
 *       {@code getCurrentItem()}, never derived from a tab index;</li>
 *   <li>the destination tab view gets a {@link View#performClick()}, which is
 *       exactly what a user tapping that tab would do.</li>
 * </ol>
 *
 * <p>A page index is never computed from a tab index, because the two need not
 * line up: the Create entry opens the camera rather than a page, so tab
 * <em>n</em> is not page <em>n</em>. Feeding a tab-bar child index to
 * {@code setCurrentItem} would land on whatever page happens to sit at that
 * offset. The cost is that the correction reads as two motions rather than one
 * continuous glide; the benefit is that it always arrives on the intended
 * surface.
 *
 * <h3>Why it is written this way</h3>
 * The pager is an {@code androidx.viewpager2.widget.ViewPager2}. Its abstract
 * page-change callback is obfuscated inside Instagram (in 435.0.0.37.76,
 * {@code registerOnPageChangeCallback} is renamed to {@code A08}) and so cannot
 * be subclassed, but the plain accessors keep their names. So this listens on
 * the framework {@link ViewTreeObserver.OnScrollChangedListener} — never
 * obfuscated — and reads the pager through {@code getScrollState()},
 * {@code getCurrentItem()} and {@code setCurrentItem(int)} by reflection, each
 * guarded and each optional: without {@code setCurrentItem} the swipe simply is
 * not cancelled, and the click still gets there.
 *
 * <p>Which page is showing is <em>not</em> read as an index: the tab bar marks
 * the live tab with {@link View#isSelected()}, and clicking another tab is how
 * the app itself changes page. Working in tab views rather than page indices
 * means nothing here depends on the pager and the tab bar agreeing on an
 * order, or on whether entries like Create even have a page.
 */
public final class HiddenTabSwipeSkipper {

    private HiddenTabSwipeSkipper() {
    }

    /** ViewPager2 scroll states. */
    private static final int STATE_IDLE = 0;
    private static final int STATE_DRAGGING = 1;

    /** How long to wait between checks that the pager has come to rest. */
    private static final long SETTLE_POLL_MS = 40;

    /** Give up waiting for rest after this many polls (~1s). */
    private static final int MAX_SETTLE_POLLS = 25;

    /**
     * Pagers already hooked, so a re-install does not stack listeners.
     *
     * <p>Touched only from the UI thread (layout and scroll callbacks), which
     * is why it needs no synchronisation.
     */
    private static final Set<View> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** Install on a window root; waits for the pager to appear, then hooks it once. */
    static void install(ViewGroup root) {
        if (root == null) {
            return;
        }
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer == null) {
            return;
        }
        observer.addOnGlobalLayoutListener(new InstallWatcher(root));
    }

    /** Global-layout listener that locates the pager, hooks it, and detaches. */
    private static final class InstallWatcher
            implements ViewTreeObserver.OnGlobalLayoutListener {
        private ViewGroup root;

        InstallWatcher(ViewGroup root) {
            this.root = root;
        }

        @Override
        public void onGlobalLayout() {
            try {
                ViewGroup r = root;
                if (r == null) {
                    return;
                }
                Context context = r.getContext();
                if (context == null) {
                    return;
                }

                int pagerId = Hiders.resolveId(context, "swipeable_tab_view_pager");
                if (pagerId == 0) {
                    Lobo.d("no swipeable_tab_view_pager in this build; swipe skipper off");
                    detach();
                    return;
                }

                View windowRoot = Hiders.windowRoot(r);
                View pager = windowRoot.findViewById(pagerId);
                if (pager == null) {
                    return; // not laid out yet; keep waiting
                }

                int tabBarId = Hiders.resolveId(context, "tab_bar");
                View tabBar = tabBarId == 0 ? null : windowRoot.findViewById(tabBarId);
                if (!(tabBar instanceof ViewGroup)) {
                    return; // keep waiting for the bar
                }

                if (attach(pager, (ViewGroup) tabBar)) {
                    detach();
                }
            } catch (Throwable t) {
                Lobo.d("HiddenTabSwipeSkipper.InstallWatcher failed: " + t);
                detach();
            }
        }

        private void detach() {
            ViewGroup r = root;
            if (r != null) {
                ViewTreeObserver observer = r.getViewTreeObserver();
                if (observer != null) {
                    observer.removeOnGlobalLayoutListener(this);
                }
                root = null;
            }
        }
    }

    /** Grab the pager's clear-named accessors and hook the scroll listener. */
    private static boolean attach(View pager, ViewGroup tabBar) {
        if (INSTALLED.contains(pager)) {
            return true;
        }
        try {
            Method getScrollState = pager.getClass().getMethod("getScrollState");
            // Both of these only cancel the in-flight swipe. The destination is
            // always reached by clicking its tab, so an unexpected pager that
            // has neither still ends up on the right surface.
            Method getCurrentItem = null;
            Method setCurrentItem = null;
            try {
                getCurrentItem = pager.getClass().getMethod("getCurrentItem");
                setCurrentItem = pager.getClass().getMethod("setCurrentItem", int.class);
            } catch (Throwable ignored) {
                getCurrentItem = null;
                setCurrentItem = null;
                Lobo.d("pager has no getCurrentItem/setCurrentItem(int); "
                        + "swipe skipper will only click tabs");
            }
            ViewTreeObserver observer = pager.getViewTreeObserver();
            if (observer == null) {
                return false;
            }
            observer.addOnScrollChangedListener(
                    new Skipper(pager, tabBar, getScrollState, getCurrentItem, setCurrentItem));
            INSTALLED.add(pager);
            Lobo.d("swipe skipper attached to " + pager.getClass().getName());
            return true;
        } catch (Throwable t) {
            // Pager API not as expected on this build: leave swipe behaviour
            // alone and stop retrying rather than spinning on every layout.
            Lobo.d("swipe skipper could not attach: " + t);
            return true;
        }
    }

    /**
     * Fires on every scroll in the pager's window. When a swipe settles on a
     * tab that is hidden, sends it on to the nearest visible one.
     */
    private static final class Skipper implements ViewTreeObserver.OnScrollChangedListener {
        private final View pager;
        private final ViewGroup tabBar;
        private final Method getScrollState;
        private final Method getCurrentItem;
        private final Method setCurrentItem;

        /** Index of the last reachable tab we settled on, to infer swipe direction. */
        private int previous = -1;
        /** Set while a jump is in flight, so it is only issued once. */
        private boolean jumping;
        /**
         * The page the pager itself reported when the current drag began, or -1
         * outside a drag. The <em>only</em> page index this class handles, and
         * it comes from {@code getCurrentItem()} rather than from any tab
         * index — cancelling a swipe means going back exactly where the pager
         * was, and nothing else here reasons in pages.
         */
        private int dragStartPage = -1;

        Skipper(View pager, ViewGroup tabBar, Method getScrollState, Method getCurrentItem,
                Method setCurrentItem) {
            this.pager = pager;
            this.tabBar = tabBar;
            this.getScrollState = getScrollState;
            this.getCurrentItem = getCurrentItem;
            this.setCurrentItem = setCurrentItem;
        }

        @Override
        public void onScrollChanged() {
            try {
                int state = scrollState();
                if (state == STATE_DRAGGING && dragStartPage < 0) {
                    // First frame of this drag: the pager still reports the
                    // page it is leaving, which is where a cancel goes back to.
                    dragStartPage = currentItem();
                }

                int current = selectedIndex();
                if (current < 0) {
                    return; // no tab marked live: nothing to reason about
                }

                if (isReachable(tabBar.getChildAt(current))) {
                    // On a tab that is really on the bar: remember it, stand down.
                    previous = current;
                    jumping = false;
                    if (state == STATE_IDLE) {
                        dragStartPage = -1;
                    }
                    return;
                }

                if (jumping) {
                    return; // already re-aimed
                }
                // While the finger is down the page still follows it; re-aiming
                // now would fight the drag. The release fires another scroll.
                if (state == STATE_DRAGGING) {
                    return;
                }

                int direction = previous <= current ? 1 : -1;
                int target = nearestReachable(current, direction);
                if (target < 0) {
                    target = nearestReachable(current, -direction);
                }
                if (target < 0) {
                    return;
                }

                jumping = true;
                Lobo.d("swipe landed on a hidden tab at " + current + "; re-aiming at " + target);
                retarget(target);
            } catch (Throwable t) {
                Lobo.d("HiddenTabSwipeSkipper.Skipper failed: " + t);
            }
        }

        /**
         * Send the swipe on to {@code target}: cancel the settle that is
         * heading for the hidden page, then reach the destination the way the
         * app itself does, by clicking its tab. A tab index is never handed to
         * {@code setCurrentItem}, because tabs and pages need not line up.
         *
         * <p>Posted rather than called inline so the pager is not re-entered
         * from inside its own scroll dispatch.
         */
        private void retarget(final int target) {
            pager.post(new Runnable() {
                @Override
                public void run() {
                    cancelSwipe();
                    clickWhenSettled(target, 0);
                }
            });
        }

        /**
         * Undo the in-flight swipe by paging back to where the drag started.
         * Best effort: with no {@code getCurrentItem}/{@code setCurrentItem}, or
         * no recorded drag, the swipe simply lands and the click corrects it.
         */
        private void cancelSwipe() {
            int page = dragStartPage;
            if (setCurrentItem == null || page < 0) {
                return;
            }
            try {
                setCurrentItem.invoke(pager, page);
            } catch (Throwable t) {
                Lobo.d("setCurrentItem failed: " + t);
            }
        }

        /**
         * Click the destination tab once the pager stops moving. Waiting
         * matters: a tab clicked mid-flight is undone when the animation lands
         * on its original destination.
         */
        private void clickWhenSettled(final int target, final int attempt) {
            pager.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!jumping) {
                        return; // stood down; see verify()
                    }
                    if (scrollState() != STATE_IDLE && attempt < MAX_SETTLE_POLLS) {
                        clickWhenSettled(target, attempt + 1);
                        return;
                    }
                    if (selectedIndex() == target) {
                        return; // got there on its own
                    }
                    View destination = tabBar.getChildAt(target);
                    if (destination == null) {
                        jumping = false;
                        return;
                    }
                    destination.performClick();
                }
            }, SETTLE_POLL_MS);
        }

        /** Index of the tab the bar marks as live, or -1. */
        private int selectedIndex() {
            for (int i = 0; i < tabBar.getChildCount(); i++) {
                View child = tabBar.getChildAt(i);
                if (child != null && child.isSelected()) {
                    return i;
                }
            }
            return -1;
        }

        /** First reachable tab from {@code from} walking in {@code direction}. */
        private int nearestReachable(int from, int direction) {
            for (int i = from + direction; i >= 0 && i < tabBar.getChildCount(); i += direction) {
                if (isReachable(tabBar.getChildAt(i))) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Whether a tab is somewhere the user may land. Hidden tabs are out,
         * and so is Create: it opens the camera rather than a page, so jumping
         * onto it would swap a hidden surface for a full-screen composer.
         */
        private boolean isReachable(View tab) {
            if (tab == null || tab.getVisibility() != View.VISIBLE) {
                return false;
            }
            return !"creation_tab".equals(entryName(tab));
        }

        private String entryName(View view) {
            int id = view.getId();
            if (id == View.NO_ID) {
                return null;
            }
            try {
                return view.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private int scrollState() {
            try {
                return (Integer) getScrollState.invoke(pager);
            } catch (Throwable ignored) {
                return STATE_IDLE;
            }
        }

        /** The pager's own current page, or -1 when it cannot be read. */
        private int currentItem() {
            if (getCurrentItem == null) {
                return -1;
            }
            try {
                return (Integer) getCurrentItem.invoke(pager);
            } catch (Throwable ignored) {
                return -1;
            }
        }
    }
}
