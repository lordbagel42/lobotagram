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
 * listener re-aims that same swipe at the nearest still-visible tab in the
 * direction of travel (falling back to the other direction when there is
 * nothing that way), so swiping only ever cycles through visible tabs.
 * Re-aiming rather than correcting afterwards is what keeps it feeling like one
 * gesture: a ViewPager2 accepts {@code setCurrentItem} while a scroll is in
 * flight and simply retargets the animation, so the hidden page is passed over
 * in a single continuous motion instead of the pager landing on it and then
 * cutting away.
 *
 * <h3>Why it is written this way</h3>
 * The pager is an {@code androidx.viewpager2.widget.ViewPager2}. Its abstract
 * page-change callback is obfuscated inside Instagram (in 435.0.0.37.76,
 * {@code registerOnPageChangeCallback} is renamed to {@code A08}) and so cannot
 * be subclassed, but the plain accessors keep their names. So this listens on
 * the framework {@link ViewTreeObserver.OnScrollChangedListener} — never
 * obfuscated — and reads the pager through {@code getScrollState()} /
 * {@code setCurrentItem(int)} by reflection.
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

    /** Grace period before checking that re-aiming the swipe actually took. */
    private static final long VERIFY_DELAY_MS = 400;

    /** Pagers already hooked, so a re-install does not stack listeners. */
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
            // Fallback for the jump; absent on an unexpected pager, in which
            // case clicking the destination tab is the only route.
            Method setCurrentItem = null;
            try {
                setCurrentItem = pager.getClass().getMethod("setCurrentItem", int.class);
            } catch (Throwable ignored) {
                Lobo.d("pager has no setCurrentItem(int); swipe skipper will click tabs instead");
            }
            ViewTreeObserver observer = pager.getViewTreeObserver();
            if (observer == null) {
                return false;
            }
            observer.addOnScrollChangedListener(
                    new Skipper(pager, tabBar, getScrollState, setCurrentItem));
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
     * tab that is hidden, jumps to the nearest visible one.
     */
    private static final class Skipper implements ViewTreeObserver.OnScrollChangedListener {
        private final View pager;
        private final ViewGroup tabBar;
        private final Method getScrollState;
        private final Method setCurrentItem;

        /** Index of the last reachable tab we settled on, to infer swipe direction. */
        private int previous = -1;
        /** Set while a jump is in flight, so it is only issued once. */
        private boolean jumping;

        Skipper(View pager, ViewGroup tabBar, Method getScrollState, Method setCurrentItem) {
            this.pager = pager;
            this.tabBar = tabBar;
            this.getScrollState = getScrollState;
            this.setCurrentItem = setCurrentItem;
        }

        @Override
        public void onScrollChanged() {
            try {
                int current = selectedIndex();
                if (current < 0) {
                    return; // no tab marked live: nothing to reason about
                }

                if (isReachable(tabBar.getChildAt(current))) {
                    // On a tab that is really on the bar: remember it, stand down.
                    previous = current;
                    jumping = false;
                    return;
                }

                if (jumping) {
                    return; // already re-aimed
                }
                // While the finger is down the page still follows it; re-aiming
                // now would fight the drag. The release fires another scroll.
                if (scrollState() == STATE_DRAGGING) {
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
         * Extend the in-flight swipe to {@code target} instead of letting it
         * land on the hidden page. Posted rather than called inline so the
         * pager is not re-entered from inside its own scroll dispatch — one
         * frame later is still inside the settle animation, which is what makes
         * the motion continuous.
         */
        private void retarget(final int target) {
            pager.post(new Runnable() {
                @Override
                public void run() {
                    if (setCurrentItem == null) {
                        clickWhenSettled(target, 0);
                        return;
                    }
                    try {
                        setCurrentItem.invoke(pager, target);
                    } catch (Throwable t) {
                        Lobo.d("setCurrentItem failed: " + t);
                        clickWhenSettled(target, 0);
                        return;
                    }
                    verify(target);
                }
            });
        }

        /**
         * If re-aiming did not take (a build whose pager ignores it), fall back
         * to the blunt route: wait for rest, then click the destination tab.
         */
        private void verify(final int target) {
            pager.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Landed somewhere reachable in the meantime (here, or
                    // because the user swiped again): not ours to force.
                    if (!jumping || selectedIndex() == target) {
                        return;
                    }
                    clickWhenSettled(target, 0);
                }
            }, VERIFY_DELAY_MS);
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
    }
}
