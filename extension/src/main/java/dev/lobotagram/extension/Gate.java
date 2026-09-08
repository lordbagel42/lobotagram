package dev.lobotagram.extension;

import java.io.IOException;
import java.net.URI;

/**
 * The network gate: Instagram's decision table for "is this request a Reels
 * surface?".
 *
 * <p>Called from {@code TigonServiceLayer.startRequest} right after the request
 * URI is materialised, from inside the method's {@code IOException} try block,
 * so throwing here is indistinguishable from the network being unavailable and
 * the blocked surface simply stays empty. This is the approach FeurStagram's
 * {@code Block} class takes (GPLv3, https://github.com/feurstagram); the
 * "reels everywhere except DMs" pagination rule below is adapted from
 * InstaEclipse's {@code IGNetworkInterceptor} (GPLv3,
 * https://github.com/ReSo7200/InstaEclipse).
 *
 * <p>Rules are plain string tables so a maintainer can read the policy without
 * reading the code. Order matters: the allow-list is consulted first, then the
 * always-dead Reels surfaces, then the pagination rule, then the optional
 * blocks. Turn trace mode on to watch the decisions on a device:
 * <pre>
 * adb shell setprop log.tag.Lobotagram DEBUG
 * adb logcat -s Lobotagram
 * </pre>
 * Every request is then logged as {@code ALLOW <path>?<query>} or
 * {@code BLOCK <rule> <path>}, which is how a surface that moved to a new
 * endpoint gets found.
 */
public final class Gate {

    /**
     * Block Explore's topical feed. Rewritten by the "Block Reels surfaces"
     * patch from its {@code blockExplore} option; the value here is only the
     * default that ships in the extension DEX.
     *
     * <p>Not final on purpose: the patch rewrites {@code Gate.<clinit>}, and a
     * final field would let the dexer fold the constant into every read.
     */
    public static boolean blockExplore = true;

    /**
     * Block the quick-promotion nudge fetch ("try Reels", "post a Reel", ...).
     * Rewritten by the patch from its {@code blockNudges} option.
     */
    public static boolean blockNudges = true;

    /**
     * Direct messages. Matched with {@code startsWith}: everything under the
     * DM API is allowed unconditionally, including the fetch of a reel that a
     * friend shared into a thread.
     */
    private static final String[] ALLOW_DIRECT_PREFIXES = {
            "/api/v1/direct_v2/",
    };

    /**
     * Stories. Inside Instagram "reel" means Story, so {@code /feed/reels_tray}
     * is the Stories tray and must never be blocked.
     */
    private static final String[] ALLOW_STORIES = {
            "/feed/reels_tray",
            "/feed/get_latest_reel_media/",
            "/stories/",
    };

    /**
     * Anything that looks like an upload or a creation flow. Deliberately broad:
     * over-blocking here breaks posting, which is the one thing this build must
     * keep. {@code /media/configure_to_clips/} is the reel upload finaliser and
     * is listed explicitly even though {@code configure} already covers it.
     *
     * <p>Because the tokens are bare substrings, this list is consulted
     * <em>after</em> {@link #BLOCK_REELS_SURFACES} rather than before it: a
     * Reels feed that happened to carry {@code upload} or {@code configure}
     * anywhere in its path would otherwise be allowed through by accident.
     * Nothing under {@code /api/v1/clips/} in 435.0.0.37.76 contains either
     * token, so the ordering costs nothing today and closes the hole for good.
     */
    private static final String[] ALLOW_CREATION = {
            "upload",
            "configure",
            "/media/configure_to_clips/",
    };

    /**
     * Reels as a surface. Always dead, no toggle: these are the endpoints that
     * exist only to hand out an endless list of reels.
     */
    private static final String[] BLOCK_REELS_SURFACES = {
            "/clips/connected/",             // the chaining feed: "the reels after this one"
            "/clips/home/",                  // the Reels tab feed in older releases
            "/clips/discover",               // discovery / Explore video, and every /discover/* child
            "/clips/homecoming",             // "catch up" reels
            "/clips/trend",                  // /clips/trend/ and /clips/trends_media_feed/
            "/mixed_media/discover/stream/", // mixed feed with reels in it
            "/clips/get_blend_medias/",      // Blend
            "/clips/ads_discover_sync_flow/",
            "/feed/injected_reels_media",    // no trailing slash: also matches the _www sibling
            "/clips/music/",
            "/clips/audio",                  // /clips/audio/ and /clips/audio_page_chain_clips/
            "/clips/effect/",
            "/clips/keyword/",               // reels for a search keyword
            "/clips/tags/",                  // reels for a hashtag ("hashtag" was the old name)
            "/clips/locations/",             // reels for a place
    };

    /**
     * The path segment every Reels request shares. Matched with
     * {@code contains} rather than {@code startsWith("/api/v1/clips/")} so the
     * rule survives an API version bump; no other Instagram endpoint has
     * {@code /clips/} as a whole path segment (the creation finaliser is
     * {@code /media/configure_to_clips/}, with no slash before "clips").
     */
    private static final String CLIPS_PATH_SEGMENT = "/clips/";

    /**
     * Pagination cursors. A clips request that carries one of these is asking
     * for the <em>next</em> reel, which is exactly what must never arrive: the
     * first reel opened from a DM comes back from a cursorless fetch and plays,
     * and the request that would follow it starves.
     */
    private static final String[] CLIPS_PAGINATION_CURSORS = {
            "max_id=",
            "next_media_ids=",
            "page_index=",
            "paging_token=",
            "chaining_media_id=", // "give me the reels that follow this one"
    };

    /** Explore's topical feed, gated on {@link #blockExplore}. */
    private static final String[] BLOCK_EXPLORE = {
            "/discover/topical_explore",
    };

    /** Quick-promotion nudges, gated on {@link #blockNudges}. */
    private static final String[] BLOCK_NUDGES = {
            "/qp/batch_fetch/",
    };

    private Gate() {
    }

    /**
     * The hook. Throws {@link IOException} when the request is a Reels surface.
     *
     * @param uri the request URI, read straight out of Instagram's request object
     * @throws IOException when the request must not be made
     */
    public static void throwIfBlocked(URI uri) throws IOException {
        String rule;

        // Everything except the throw itself runs inside this try. The gate is
        // called on Instagram's request path: the only exception that may leave
        // this method is the IOException below, which the caller handles as a
        // network failure. Anything else - a URI whose escaped octets do not
        // decode, a Log call that objects - would be an uncaught exception
        // inside someone else's app, so it fails open instead.
        try {
            if (uri == null) {
                return;
            }

            String path = uri.getPath();
            if (path == null) {
                return;
            }

            // Raw first: getRawQuery() is the bytes as sent, getQuery() decodes
            // them (and is the one that can throw). Both are checked so a cursor
            // is caught whether or not its name arrives percent-encoded.
            String rawQuery = uri.getRawQuery();
            String query = decodedQuery(uri);
            rule = ruleFor(path, rawQuery, query);

            if (Lobo.trace()) {
                String shown = rawQuery != null ? rawQuery : query;
                if (rule == null) {
                    Lobo.d("ALLOW " + path + (shown == null ? "" : "?" + shown));
                } else {
                    Lobo.d("BLOCK " + rule + " " + path);
                }
            }
        } catch (Throwable t) {
            Lobo.d("Gate.throwIfBlocked failed open: " + t);
            return;
        }

        if (rule != null) {
            throw new IOException("lobotagram: blocked by " + rule);
        }
    }

    /** {@link URI#getQuery()} throws on malformed escapes; null is fine here. */
    private static String decodedQuery(URI uri) {
        try {
            return uri.getQuery();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The decision table. Returns the name of the rule that blocks this request,
     * or null when it is allowed. Kept separate from {@link #throwIfBlocked} so
     * the trace log can name the rule that fired.
     */
    private static String ruleFor(String path, String rawQuery, String query) {
        // 1. Never touch DMs or Stories.
        if (startsWithAny(path, ALLOW_DIRECT_PREFIXES)) {
            return null;
        }
        if (containsAny(path, ALLOW_STORIES)) {
            return null;
        }

        // 2. Reels surfaces are always dead. Ahead of the creation allow-list
        //    on purpose: see the note on ALLOW_CREATION.
        String surface = firstMatch(path, BLOCK_REELS_SURFACES);
        if (surface != null) {
            return "reels-surface " + surface;
        }

        // 3. Anything that creates content.
        if (containsAny(path, ALLOW_CREATION)) {
            return null;
        }

        // 4. Any other clips request that asks for the next reel.
        if (path.contains(CLIPS_PATH_SEGMENT)) {
            String cursor = firstMatch(rawQuery, CLIPS_PAGINATION_CURSORS);
            if (cursor == null) {
                cursor = firstMatch(query, CLIPS_PAGINATION_CURSORS);
            }
            if (cursor != null) {
                return "clips-pagination " + cursor;
            }
        }

        // 5. Optional blocks, on by default, rewritten from the patch options.
        if (blockExplore && containsAny(path, BLOCK_EXPLORE)) {
            return "explore";
        }
        if (blockNudges && containsAny(path, BLOCK_NUDGES)) {
            return "nudges";
        }

        return null;
    }

    private static String firstMatch(String haystack, String[] needles) {
        if (haystack == null) {
            return null;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return needle;
            }
        }
        return null;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        return firstMatch(haystack, needles) != null;
    }

    private static boolean startsWithAny(String haystack, String[] prefixes) {
        for (String prefix : prefixes) {
            if (haystack.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
