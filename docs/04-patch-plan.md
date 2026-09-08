# Lobotagram patch plan

Goal: an Instagram build where Reels as a *surface* does not exist, but a
single reel a friend sends in a DM still plays, and nothing lets you scroll
onward from it. Stories, posting, DMs, search, notifications and profiles keep
working.

## 1. Requirements

Must keep working:

- Direct messages, including receiving and *playing* a reel shared in a DM
- Posting to Story, viewing friends' Stories, replying to Stories
- Feed of accounts you follow (optional, see toggles), profile, search,
  notifications, creating posts

Must be removed:

- The Reels tab (icon, page, and the swipe that reaches it)
- Reels surfaced from anywhere else: Explore video grid, "suggested reels" in
  the feed, Blend, the Reels ("Friends") lane, quick-promotion nudges to Reels
- **Vertical scrolling from a DM-opened reel to the next reel**

Explicit non-goals for v1: ad blocking, telemetry blocking, theming, downloads.
FeurStagram already ships those and they can be enabled later as separate
patches if wanted.

## 2. Architecture

Same shape as ReVanced / FeurStagram: a Gradle project with a `patches/` module
(Kotlin, fingerprints and instruction injection) and an `extensions/` module
(Java, compiled to a DEX that is merged into Instagram). Built with the Morphe
patcher because a working Instagram reference project exists on it. Porting to
stock ReVanced is a package-rename exercise.

```
lobotagram/
├── build.sh                         # merge splits, build .mpp, patch, sign
├── tools/                           # APKEditor jar, morphe cli jar, recon.sh
├── patches/src/main/kotlin/dev/lobotagram/patches/
│   ├── shared/Constants.kt          # compatibleWith("com.instagram.android"("<pinned>"))
│   ├── network/NetworkGatePatch.kt  # P1: TigonServiceLayer.startRequest hook
│   ├── ui/TabBarPatch.kt            # P2: tab-bar binder hook -> hide clips_tab, swipe skip
│   ├── clips/ClipsViewerLockPatch.kt# P3: lock the reels viewer pager
│   └── signature/SignatureBypassPatch.kt # P4: key-hash checks return true
└── extensions/extension/src/main/java/dev/lobotagram/extension/
    ├── Gate.java                    # URI decision table (throwIfBlocked)
    ├── ReelContext.java             # "was this viewer opened from a DM?" state
    ├── Hiders.java                  # clips_tab GONE + HiddenTabSwipeSkipper
    ├── ViewerLock.java              # disables user input on the clips pager
    └── Prefs.java                   # SharedPreferences toggles (optional)
```

Defense in depth: the network gate is the primary control (it works even when
UI moves because Bloks renders it), the UI lock is the second control (it works
even if an endpoint is renamed), and the tab hider is cosmetic but removes the
temptation.

## 3. Patches

> The sketches below are the original plan. The implemented rules and anchors
> live in [patches/network.md](patches/network.md) and [patches/ui.md](patches/ui.md),
> which are normative where they differ (for example `/clips/connected/` is
> blocked outright, the source enum is the named `ClipsViewerSource`, and the
> UI hooks install from `BaseFragmentActivity.onAttachedToWindow`).

### P1. Network gate (primary)

Fingerprint (named, stable across every version anyone has tested):

```kotlin
internal object TigonStartRequestFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/api/tigon/TigonServiceLayer;",
    name = "startRequest",
)
```

Injection: locate the first `iget-object` whose field type is
`Ljava/net/URI;`, and insert right after it

```smali
invoke-static { vN }, Ldev/lobotagram/extension/Gate;->throwIfBlocked(Ljava/net/URI;)V
```

Throwing `IOException` from inside the request try-block is handled by
Instagram as a network failure (FeurStagram approach). InstaEclipse instead
rewrites the URI to `https://127.0.0.1/404`. Prefer throwing; it is cheaper and
does not leave a dangling connection attempt. If a particular surface shows an
ugly error toast on exceptions, switch that path to URI rewriting.

Decision table in `Gate.throwIfBlocked(URI)`:

```java
String p = uri.getPath(); String q = uri.getQuery();
if (p == null) return;

// 1. Never touch DMs or stories.
if (p.startsWith("/api/v1/direct_v2/")) return;
if (p.contains("/feed/reels_tray") || p.contains("/feed/get_latest_reel_media/")
        || p.contains("/stories/")) return;

// 2. Reels surfaces: always dead.
if (p.contains("/clips/home/")                 // Reels tab feed (+ /connected/)
 || p.contains("/clips/discover")              // discovery, Explore video
 || p.contains("/mixed_media/discover/stream/")
 || p.contains("/clips/get_blend_medias/")     // Blend
 || p.contains("/clips/ads_discover_sync_flow/")
 || p.contains("/feed/injected_reels_media")   // also matches the _www sibling
 || p.contains("/clips/trending")
 || p.contains("/clips/music/")
 || p.contains("/clips/audio/")
 || p.contains("/clips/effect/")
 || p.contains("/clips/hashtag/")
 || p.contains("/clips/location/")) throw blocked(p);

// 3. Any other clips request that is a *continuation* (the next reel).
//    The first reel opened from a DM arrives via /media/{id}/info/ or a
//    non-paginated clips call; every later one carries a cursor.
if (p.startsWith("/api/v1/clips/") && q != null
        && (q.contains("max_id=") || q.contains("next_media_ids=")
            || q.contains("page_index=") || q.contains("paging_token="))) throw blocked(p);

// 4. Optional toggles (default on): Explore, suggested accounts, quick promos.
if (Prefs.exploreBlocked() && p.contains("/discover/topical_explore")) throw blocked(p);
if (Prefs.nudgesBlocked()  && p.endsWith("/qp/batch_fetch/")) throw blocked(p);
```

Two things to confirm on the pinned version with a network trace (see §6):
the exact continuation endpoint used by the DM-opened viewer (candidates:
`/clips/home/connected/`, `/clips/connected/`, `/clips/chaining/`,
`/clips/user/`) and whether its cursor travels as a query parameter or as a
POST body field. If it is a body field, fall back to blocking every
`/api/v1/clips/` request that is *not* the single-item fetch, and let the
`ReelContext` flag (P3) allow exactly one clips request after a DM tap.

Log every blocked path with a `Lobotagram` tag so `adb logcat -s Lobotagram`
tells you which rule fired.

### P2. Tab bar: remove the Reels tab and its page

Fingerprint the tab-bar binder the FeurStagram way: constructor in `LX/`
taking one `Landroid/view/View;`, storing both a `Landroid/view/ViewGroup;` and
a `Landroid/view/View;` field. Inject after the `iput-object` of the
`ViewGroup`:

```smali
invoke-static { vN }, Ldev/lobotagram/extension/Hiders;->install(Landroid/view/ViewGroup;)V
```

`Hiders.install(root)`:

- On every global layout, resolve `clips_tab` (fallback package
  `com.instagram.android`) and set `View.GONE`.
- Install `HiddenTabSwipeSkipper` on `swipeable_tab_view_pager` (a `ViewPager2`
  reached by reflection: `getScrollState()`, `setCurrentItem(int)`), so a
  horizontal swipe that would land on the Reels page is re-aimed at the next
  visible tab. Copy FeurStagram's implementation; it is GPLv3 and this project
  should be too.
- Also hide the "Friends"/"Blend" lane in `clips_viewer_action_bar` >
  `action_bar_tab_layout` (children after the first) so the DM-opened viewer
  does not offer a lateral route into another feed.

Fallback if the binder fingerprint drifts: hook
`InstagramMainActivity.onCreate` (named class) and walk the window's content
view for `tab_bar` after first layout. Slower but name-anchored.

### P3. Clips viewer lock: the "watch one, scroll none" guarantee

This is the piece nobody has shipped. Two layers:

**3a. Entry-point tracking.** The Reels viewer is opened with a Parcelable
config (`ClipsViewerConfig` in 2024 builds; confirm the current name via
`tools/recon.sh`, it carries an enum-like "clips viewer source"). Fingerprint
its constructor or `writeToParcel` by the string constants of the source enum
(candidates: `"direct"`, `"direct_thread"`, `"clips_tab"`, `"feed_timeline"`,
`"explore"`). Inject a call that records the source in
`ReelContext.onViewerOpened(String source)`.

If the config class cannot be found reliably, a cheaper proxy: in the P1 gate,
set `ReelContext.lastDirectMediaFetchMs = now` whenever a `/media/{id}/info/`
or `/clips/item/` request happens while the top activity was reached from
`/direct_v2/`, and treat a viewer opened within 3 seconds as DM-sourced.

**3b. Pager lock.** When the clips viewer's root view (`clips_video_container`
or the `clips_viewer_action_bar` sibling) appears and `ReelContext.source` is
anything (in a lobotomized build every viewer is single-reel), find the
vertical pager (a `ViewPager2` or `RecyclerView` ancestor of the container) and
lock it:

- `ViewPager2.setUserInputEnabled(false)` via reflection, or
- for a `RecyclerView`, attach an `OnItemTouchListener` that returns `true` in
  `onInterceptTouchEvent` for vertical drags beyond the touch slop, while
  passing through taps (pause), double taps (like) and horizontal gestures
  (back).

Install it from the same global-layout observer used by `Hiders`, so it needs
no additional fingerprint. Because the network gate already denies the next
reel, this lock mostly turns an empty loading spinner into a firm stop, which
is the intended feel: the reel ends, you go back to the chat.

**3c. Autoplay-next.** Some versions auto-advance when a reel finishes. If
`recon.sh` finds the `"ig_disable_video_autoplay"` gate, fingerprint the
`(UserSession)Z`-shaped method that references it and make it return `true`
only when the clips surface is showing (check `ReelContext.viewerVisible`).
Otherwise rely on 3a/3b.

### P4. Signature check bypass (required for DM links)

Copy FeurStagram's `SignatureCheckBypassPatch` logic:

1. `Fingerprint(strings = listOf("Invalid SHA256 key hash")).classDef.type`
   gives the key-hash type `T`.
2. The unique static method `(T)Z` and the unique static method `(T, T, Z)Z`
   are rewritten to `const/4 v0, 0x1; return v0`.

Without this a re-signed Instagram refuses its own deep links, and a reel
shared into a DM opens the home feed instead of the reel. Test after patching:
tap a reel in a DM, confirm the viewer opens.

### P5 (optional). Feed hygiene

Fingerprint the feed-item deserializer by strings `clips_netego`,
`stories_netego`, `suggested_users` plus method name containing
`parseFromJson`, and rewrite `clips_netego` (the "Suggested reels" unit in the
home feed) to an invalid token so the parser drops it. This closes the last
in-feed door into Reels. Take the implementation from FeurStagram's
`FeedItemFilterPatch.kt`.

### P6 (optional). Toggles and a permanent lock

FeurStagram's long-press-Home settings page, SharedPreferences store, and
"permanent lock" (can tighten, cannot loosen without reinstall) are directly
reusable. For a personal build, hardcoding everything on in `Gate` is simpler
and removes the temptation to flip it back. Recommendation: no settings UI in
v1; add it only if the Explore toggle turns out to be wanted.

## 4. Build pipeline

Prerequisites: JDK 21, Android SDK build-tools (`apksigner`, `zipalign`), a
GitHub token with `read:packages` for the Morphe (or ReVanced) Gradle registry,
`adb`.

```bash
# one-time
git clone <this repo> && cd lobotagram
mkdir -p tools
#   drop morphe-cli-*.jar (or revanced-cli.jar) and APKEditor-*.jar into tools/
keytool -genkeypair -v -keystore lobotagram.keystore -alias lobotagram \
        -keyalg RSA -keysize 2048 -validity 10000

# every build
./build.sh instagram-<version>-arm64.apk --install
#   1. if the input is .apkm/.xapk/.apks: java -jar tools/APKEditor-*.jar m -f -i in -o merged.apk
#   2. ./gradlew :patches:build            -> patches/build/libs/patches-*.mpp
#   3. java -jar tools/morphe-cli-*.jar patch -p patches.mpp -f -r build/report.json \
#        --unsigned -o lobotagram.apk merged.apk
#   4. apksigner sign --ks lobotagram.keystore --v1..v3 lobotagram.apk
#   5. adb install -r lobotagram.apk    (uninstall stock Instagram first, or use a Clone patch)
```

With stock ReVanced instead: `java -jar revanced-cli.jar patch -bp patches.rvp
--exclusive -e "Network gate" -e "Hide Reels tab" ... input.apk`. The
`-r report.json` step matters: the CLI aborts on the first failed fingerprint,
and the report is how you see which one.

Package rename ("Clone") is worth adding as a patch so the lobotomized build can
sit next to stock Instagram during testing, then replace it.

## 5. Version pinning and maintenance

- Pin one release in `Constants.COMPATIBILITY_INSTAGRAM`, e.g.
  `"com.instagram.android"("442.0.0.46.79")`. Keep the original APK in a safe
  place; APKMirror keeps old versions but the download is manual.
- Expected fragility, lowest to highest: P1 (named class), P4 (string anchor),
  P2 (structural shape of one constructor), P5 (strings), P3a (class rename).
- On an Instagram update: rebuild, read `report.json`, fix the one fingerprint
  that failed, re-run the verification script in §6. FeurStagram's history
  suggests one small fix every few releases.
- Turn off Play Store auto-update for Instagram (it cannot update a
  differently-signed package anyway, but it will nag).

## 6. Verification checklist

Recon before writing code (`tools/recon.sh instagram.apk`): confirms every
anchor in `docs/02-instagram-apk.md` exists in the pinned version and prints
the current names for `ClipsViewerConfig`-like classes and clips endpoints.

Runtime, after installing a build with only P1 in *trace mode* (log every URI,
block nothing):

1. Open a reel from a DM, let it finish, swipe up twice. Record the exact
   paths and query strings. This tells you the continuation endpoint.
2. Open the Reels tab, scroll three reels. Record paths.
3. Open a Story, post a Story, send a DM, open Explore. Record paths, to be sure
   none of them match a block rule.

Then enable blocking and check:

- [ ] Reels tab icon gone; horizontal swipe from Search skips to Create/Direct
- [ ] Deep link to a reel (from a DM and from a browser) opens the viewer
- [ ] The DM reel plays, sound works, like/comment/share still work
- [ ] Swiping up does nothing, or shows an empty state, and never a new reel
- [ ] Reel ends and does not auto-advance
- [ ] Explore shows no video grid (if toggle on)
- [ ] Home feed has no "Suggested reels" unit (if P5 on)
- [ ] Stories tray loads, a Story posts, DMs send and receive
- [ ] `adb logcat -s Lobotagram` shows only clips/discover paths blocked

## 7. Risks

- **Terms of Service.** A modified client can get an account actioned. Use a
  persistent keystore, do not spam requests, and do not redistribute the APK.
- **Endpoint drift.** Meta renames paths. Trace mode plus the log tag makes a
  broken rule visible in minutes.
- **Bloks.** If the Reels tab itself becomes a Bloks screen, `clips_tab` may
  vanish as a resource id. P1 still holds; the tab hider would need a new
  anchor (Bloks screen ids are strings and can be fingerprinted).
- **Native networking.** If Instagram moves URI construction below
  `TigonServiceLayer` into native code, P1 breaks. Nothing suggests this is
  planned; the class has been stable for years and three projects depend on it.
- **Over-blocking.** A rule that matches `/clips/` too broadly breaks reel
  *uploads* (`/clips/...upload...`) and the reel a friend sends. Keep rules
  path-specific and keep the DM allow-list first.

## 8. Order of work

1. Pin a version, download it, run `tools/recon.sh`, fix anchors in docs.
2. Scaffold the Gradle project from the Morphe/ReVanced patches template.
3. P1 in trace mode, install, collect endpoint logs (§6 steps 1-3).
4. P4, then P1 in blocking mode. Verify DM reel opens and does not scroll.
5. P2 tab hider and swipe skipper.
6. P3b pager lock, then P3a if the network rule alone lets a second reel in.
7. P5 feed hygiene. Ship v1.
