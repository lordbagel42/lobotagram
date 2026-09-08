package dev.lobotagram.extension;

/**
 * Feed-item type filtering: the last in-feed door into Reels.
 *
 * <p>Instagram's home-feed response is a list of items, each wrapped as a JSON
 * object keyed by its type ({@code {"clips_netego": {...}}}). The deserialiser
 * dispatches on that key through a {@code String.hashCode()} switch. The
 * "Suggested reels" unit therefore never has a URL of its own, so the network
 * gate cannot see it — only a parse-layer hook can.
 *
 * <p>Adapted from FeurStagram's {@code Block.replaceFeedItemType} (GPLv3,
 * https://github.com/feurstagram).
 */
public final class FeedFilter {

    /**
     * The token handed back for a unit that must be dropped. It matches no case
     * in Instagram's switch, so the item falls through to the parser's own
     * "unknown type" branch, which skips the value and moves on. No throw, no
     * crash, no empty feed.
     */
    private static final String INVALID_FEED_TYPE = "lobotagram_blocked";

    /**
     * Reels feed units, matched exactly. In Instagram 435.0.0.37.76 the
     * feed-item parser knows 56 type tokens and {@code clips_netego} is the only
     * Reels one (the full list is in {@code docs/patches/network.md}).
     */
    private static final String[] REELS_FEED_UNITS = {
            "clips_netego",
    };

    /**
     * Prefix rule for units added in later releases. Inside Instagram "clips"
     * means Reels, so any feed unit whose type token starts with {@code clips_}
     * is a Reels unit by their own naming.
     */
    private static final String REELS_UNIT_PREFIX = "clips_";

    private FeedFilter() {
    }

    /**
     * The hook. Called with each feed item's JSON type token.
     *
     * @param type the token Instagram read from the response
     * @return {@code type} unchanged, or an invalid token when the unit is Reels
     */
    public static String replaceFeedItemType(String type) {
        if (type == null) {
            return null;
        }

        boolean drop = equalsAny(type, REELS_FEED_UNITS) || type.startsWith(REELS_UNIT_PREFIX);

        if (Lobo.trace()) {
            Lobo.d((drop ? "DROP feed-unit " : "KEEP feed-unit ") + type);
        }

        return drop ? INVALID_FEED_TYPE : type;
    }

    private static boolean equalsAny(String value, String[] options) {
        for (String option : options) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
