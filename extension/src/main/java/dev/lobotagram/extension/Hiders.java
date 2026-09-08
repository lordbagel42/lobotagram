package dev.lobotagram.extension;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.HorizontalScrollView;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The runtime half of the "Hide Reels tab" patch: everything that removes Reels
 * from the view tree, plus the entry point for the Reels-viewer lock.
 *
 * <p>Adapted from Feurstagram's {@code Hiders} (GPLv3), stripped of its
 * SharedPreferences toggles: lobotagram v1 has no settings, so every hider is
 * hardcoded on.
 *
 * <p>Everything is driven from {@link ViewTreeObserver.OnGlobalLayoutListener}s:
 * one on the tab bar, one on each activity window's decor view. A
 * layout listener rather than a one-shot pass because Instagram rebuilds and
 * re-shows these views constantly: the Reels tab is re-created when the bar is
 * re-bound, and the Reels viewer's header is inflated long after the tab bar
 * exists. Targets are resolved by resource <em>name</em> through
 * {@link Resources#getIdentifier}, never by a numeric id, so an Instagram
 * update that reshuffles resource ids changes nothing here.
 */
public final class Hiders {

    /** Package to fall back to when the app has been renamed (clone builds). */
    private static final String FALLBACK_PACKAGE = "com.instagram.android";

    /** The Reels entry in the bottom navigation bar. */
    private static final String CLIPS_TAB = "clips_tab";

    /** Header of the Reels viewer; holds the "Reels"/"Friends" tab strip. */
    private static final String CLIPS_VIEWER_ACTION_BAR = "clips_viewer_action_bar";

    /** The tab strip inside that header. A generic id, hence the scoping above. */
    private static final String ACTION_BAR_TAB_LAYOUT = "action_bar_tab_layout";

    /**
     * Roots already hooked, so repeated installs do not stack listeners.
     *
     * <p>Touched only from the UI thread (layout callbacks), hence unsynchronised.
     */
    private static final Set<View> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /**
     * Resource name to id, resolved once per process.
     *
     * <p>{@link Resources#getIdentifier} is a name lookup in the resource
     * table, and every hider asks for two to five ids on <em>every layout
     * pass</em> of every window this is installed on. Ids do not change while
     * the process lives, so they are cached; a miss (0) is cached too, so a
     * build without {@code clips_tab} does not re-scan the table forever.
     * Reads and writes only happen on the UI thread (layout callbacks).
     */
    private static final Map<String, Integer> IDS = new HashMap<String, Integer>();

    private Hiders() {
    }

    /**
     * Primary wiring: called from the tab-bar binder's constructor with the
     * {@code tab_bar} ViewGroup it just pulled out of its root view.
     */
    public static void install(ViewGroup tabBar) {
        installOn(tabBar);
    }

    /**
     * Window wiring: called from a framework override on Instagram's activity
     * base class, for every activity that can host a fragment.
     *
     * <p>Not merely a fallback for {@link #install(ViewGroup)}. A
     * {@link ViewTreeObserver} only ever fires for its own window, and the
     * Reels viewer opens in {@code ModalActivity} or a URL-handler activity as
     * often as in the main one — where the tab bar, and therefore the observer
     * installed on it, does not exist. Without this entry point
     * {@link ViewerLock} and the Friends-lane hider would never see those
     * viewers. The decor view is a superset of the tab bar's window and every
     * hider searches the window anyway, so when both hooks fire in the same
     * window the second one is a no-op ({@link #INSTALLED} is keyed by root).
     */
    public static void install(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor instanceof ViewGroup) {
                installOn((ViewGroup) decor);
            }
        } catch (Throwable t) {
            Lobo.d("Hiders.install(Activity) failed: " + t);
        }
    }

    private static void installOn(ViewGroup root) {
        try {
            if (root == null) {
                return;
            }
            // add() is false when the root was hooked before. The fallback hook
            // fires on every window-focus change, so this guard is load-bearing.
            if (!INSTALLED.add(root)) {
                return;
            }
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer == null) {
                return;
            }
            observer.addOnGlobalLayoutListener(new SurfaceWatcher(root));
            HiddenTabSwipeSkipper.install(root);
            Lobo.d("hiders installed on " + root.getClass().getName());
        } catch (Throwable t) {
            Lobo.d("Hiders.installOn failed: " + t);
        }
    }

    /**
     * Resolves a resource id by name, falling back to Instagram's own package
     * so a renamed (cloned) build still finds its own ids.
     *
     * @return the id, or 0 when this build has no such resource
     */
    static int resolveId(Context context, String name) {
        try {
            Integer cached = IDS.get(name);
            if (cached != null) {
                return cached.intValue();
            }
            Resources resources = context.getResources();
            if (resources == null) {
                return 0; // not cached: a context with no resources is transient
            }
            int id = resources.getIdentifier(name, "id", context.getPackageName());
            if (id == 0) {
                id = resources.getIdentifier(name, "id", FALLBACK_PACKAGE);
            }
            IDS.put(name, Integer.valueOf(id));
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The whole window, not just the hooked subtree: the Reels viewer is a
     * sibling of the tab bar rather than a descendant, and the tab bar's
     * observer still fires for its layout passes.
     */
    static View windowRoot(View view) {
        View root = view.getRootView();
        return root != null ? root : view;
    }

    /** Sets a named view GONE if it is currently in the window. Returns true if it acted. */
    private static boolean hideByName(Context context, View searchRoot, String name) {
        int id = resolveId(context, name);
        if (id == 0) {
            return false;
        }
        View view = searchRoot.findViewById(id);
        if (view == null || view.getVisibility() == View.GONE) {
            return false;
        }
        view.setVisibility(View.GONE);
        return true;
    }

    /**
     * Hides the Reels tab, hides the Reels viewer's lateral lane, and hands the
     * window to {@link ViewerLock}, once per layout pass.
     *
     * <p>Nothing is logged per pass — that would be thousands of lines a minute
     * in trace mode. Only the first time a target is actually hidden gets a
     * line, which is what you want when checking whether the patch took.
     */
    static final class SurfaceWatcher implements ViewTreeObserver.OnGlobalLayoutListener {
        private final ViewGroup root;
        private boolean loggedClipsTab;
        private boolean loggedFriendsLane;

        SurfaceWatcher(ViewGroup root) {
            this.root = root;
        }

        @Override
        public void onGlobalLayout() {
            try {
                Context context = root.getContext();
                if (context == null) {
                    return;
                }
                View searchRoot = windowRoot(root);

                if (hideByName(context, searchRoot, CLIPS_TAB) && !loggedClipsTab) {
                    loggedClipsTab = true;
                    Lobo.d("hid " + CLIPS_TAB);
                }

                if (hideFriendsLane(context, searchRoot) && !loggedFriendsLane) {
                    loggedFriendsLane = true;
                    Lobo.d("hid the Reels viewer lane past the first tab");
                }

                ViewerLock.apply(context, searchRoot);
            } catch (Throwable t) {
                Lobo.d("Hiders.SurfaceWatcher failed: " + t);
            }
        }

        /**
         * Hides everything after the first tab in the Reels viewer's header —
         * the "Friends"/"Blend" lane, a lateral route from a DM-opened reel into
         * another endless feed.
         *
         * <p>The tabs carry no per-tab id, so they are addressed by position:
         * {@code clips_viewer_action_bar} holds {@code action_bar_tab_layout},
         * a horizontal scroller wrapping one row with a child per tab, Reels
         * first and any lane appended after it. The search is scoped to the
         * clips action bar because {@code action_bar_tab_layout} is a generic
         * id reused by other tabbed surfaces.
         */
        private static boolean hideFriendsLane(Context context, View searchRoot) {
            int barId = resolveId(context, CLIPS_VIEWER_ACTION_BAR);
            if (barId == 0) {
                return false;
            }
            View bar = searchRoot.findViewById(barId);
            if (bar == null) {
                return false; // not on the Reels surface right now
            }

            int tabsId = resolveId(context, ACTION_BAR_TAB_LAYOUT);
            if (tabsId == 0) {
                return false;
            }
            View tabs = bar.findViewById(tabsId);
            if (!(tabs instanceof ViewGroup)) {
                return false;
            }

            ViewGroup strip = (ViewGroup) tabs;
            // Step through the scroller to the row that actually holds the tabs.
            if (strip instanceof HorizontalScrollView && strip.getChildCount() == 1
                    && strip.getChildAt(0) instanceof ViewGroup) {
                strip = (ViewGroup) strip.getChildAt(0);
            }

            boolean acted = false;
            for (int i = 1; i < strip.getChildCount(); i++) {
                View child = strip.getChildAt(i);
                if (child != null && child.getVisibility() != View.GONE) {
                    child.setVisibility(View.GONE);
                    acted = true;
                }
            }
            return acted;
        }
    }
}
