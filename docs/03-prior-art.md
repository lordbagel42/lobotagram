# Prior art: who has already cut Reels out of Instagram

Four projects matter. Two of them do almost exactly what this project wants.

## FeurStagram (Morphe patches, 2025-2026)

<https://github.com/jean-voila/FeurStagram> · GPLv3

The closest reference. It is a ReVanced-style patch set for Instagram built on
the Morphe patcher, with a Java extension merged into the app. Out of the box
it blocks the home feed, Explore, Reels, ads, suggestions and telemetry while
keeping DMs, Stories, search, notifications and profiles. "Reels in DMs" is
listed as working.

How it does it:

- **Network blocking.** `NetworkBlockPatch.kt` fingerprints
  `Lcom/instagram/api/tigon/TigonServiceLayer;->startRequest`, finds the
  `iget-object` of type `Ljava/net/URI;` inside it, and inserts
  `invoke-static {vN}, Lcom/feurstagram/extension/Block;->throwIfBlocked(Ljava/net/URI;)V`
  right after. `Block.throwIfBlocked` throws `IOException("Blocked by
  Feurstagram")` for matching paths, which Instagram handles as an ordinary
  network failure, so the surface just stays empty. Reels rules: path contains
  `/clips/home/`, `/clips/discover`, or `/clips/get_blend_medias/`.
- **Tab hiding.** `Hiders.java` resolves `clips_tab` (and the others) via
  `Resources.getIdentifier` and sets `View.GONE` on every layout pass.
  `HiddenTabSwipeSkipper.java` watches the main `ViewPager2`
  (`swipeable_tab_view_pager`) through the framework `OnScrollChangedListener`
  and re-aims a swipe that would land on a hidden tab.
- **Settings entry.** `SettingsEntryPointPatch.kt` fingerprints the tab-bar
  binder constructor (class in `LX/`, takes a `View`, stores a `ViewGroup` and a
  `View` field) and injects `Settings.installHomeTabWatcher(ViewGroup)`, which
  adds a long-press on the Home tab to open a settings page. Toggles persist in
  `SharedPreferences`.
- **Signature check bypass.** `SignatureCheckBypassPatch.kt` finds the key-hash
  class through the string `"Invalid SHA256 key hash"`, then rewrites the two
  static booleans that take that type (`(KeyHash)Z` and `(KeyHash, KeyHash, Z)Z`)
  to `return true`. Without this, a re-signed build silently drops deep links
  (shared reels/posts open the home feed instead).
- **Feed item filter.** Fingerprints the feed-item deserializer by strings like
  `clips_netego` plus method name fragment `parseFromJson`, and rewrites blocked
  unit type tokens to garbage so the parser drops them without crashing.
- **Build.** `build.sh` merges `.apkm` with APKEditor if needed, runs
  `./gradlew :patches:build` to produce a `.mpp`, runs the Morphe CLI
  `patch -p bundle.mpp -f -r report.json -o out.apk input.apk`, optionally
  `--unsigned` then `apksigner` with a persistent keystore.

What it does not do for our requirement: it does not distinguish "open one reel
from a DM" from "scroll to the next one". Blocking `/clips/home/` may or may not
starve the continuation of a DM-opened viewer, depending on which endpoint that
viewer uses on the current version. That is the gap this project closes.

## InstaEclipse (LSPosed/LSPatch Xposed module, 2024-2026)

<https://github.com/ReSo7200/InstaEclipse> · uses DexKit for runtime method discovery

Runtime hooks instead of static patching, but the *targets* transfer directly.
Its Distraction-Free mode has a **"Disable Reels except in DMs"** option, which
is the exact semantic we want. From `IGNetworkInterceptor.java`:

```java
// resolve TigonServiceLayer.startRequest(a, b, c) reflectively; find the
// java.net.URI field on the first parameter's class; hook before the call.
if (FeatureFlags.disableReelsExceptDM) {
    if (uri.getPath().startsWith("/api/v1/direct_v2/")) return;   // DMs always allowed
    shouldDrop |= (uri.getPath().startsWith("/api/v1/clips/") && uri.getQuery() != null
                    && (uri.getQuery().contains("next_media_ids=")
                     || uri.getQuery().contains("max_id=")))       // pagination only
               || uri.getPath().contains("/clips/discover/")
               || uri.getPath().contains("/mixed_media/discover/stream/");
}
// full reels block (not the DM-friendly variant):
//   endsWith("/qp/batch_fetch/") || contains("api/v1/clips") || contains("clips")
//   || contains("mixed_media") || contains("mixed_media/discover/stream/")
if (shouldDrop) setObjectField(requestObj, uriField, new URI("https","127.0.0.1","/404",null));
```

So InstaEclipse's insight is: the first reel opens through a non-paginated
request, and *every* subsequent reel is fetched through a `clips/*` request that
carries `max_id=` or `next_media_ids=`. Dropping only paginated clips requests
lets one reel play and starves the scroll. It drops by rewriting the URI to
`https://127.0.0.1/404` rather than throwing.

Other useful targets from the same codebase:

- Dev options: classes containing `"is_employee"`, then `(UserSession)Z`
  methods, forced to `true` (`DevOptionsUnlockHook.java`).
- Reels overflow menu: string `"ClipsOrganicMediaItemViewMoreOptionsController"`.
- View names on the Reels surface: `clips_video_player`,
  `clips_video_container`, `clips_play_button`.
- Autoplay gate: string `"ig_disable_video_autoplay"`.
- DexKit results are cached per Instagram version code.

## Om Thorat's apktool experiment (2024)

<https://medium.com/@thoratom1104/reverse-engineering-instagram-to-fix-my-screen-time-0f3d00117138>

The crude baseline: `apktool d --no-res`, find `ClipsViewerConfig.smali`,
delete its `writeToParcel`, rebuild, zipalign, sign. Result: the app crashes
whenever a reel is opened. Two lessons: (1) "reel" is Stories and "clips" is
Reels (the first attempt broke Stories); (2) `ClipsViewerConfig` is the
parcelable that launches the Reels viewer, so it is a candidate place to read
the *entry point* (DM vs tab vs feed) if the network heuristic is not enough.

## ReVanced's own Instagram patch (2023-2026)

The official `revanced-patches` bundle has a single Instagram patch, "Hide
ads" (originally "Hide timeline ads", PR #3380). Its fingerprint style is the
template for any Instagram bytecode patch:

```kotlin
object ShowAdFingerprint : MethodFingerprint(
    "Z",
    AccessFlags.PUBLIC or AccessFlags.STATIC or AccessFlags.FINAL,
    listOf("L", "L", "Z", "Z"),
    opcodes = listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
                     Opcode.RETURN, Opcode.CONST_4, Opcode.GOTO, ...),
)
```

It has broken on many Instagram releases (issues #3242, #3449, #4723, #4891,
and 2026 reports on 425.x and 435.x), because it fingerprints an opcode
pattern inside heavily-ReDex'd code. That history is the argument for anchoring
on the network layer and resource ids instead of opcode shapes.

## IGExperiments / Instagram developer options

<https://github.com/Xposed-Modules-Repo/com.chacha.igexperiments>

Unlocks Instagram's hidden developer menu by forcing the "is employee" gates.
The dev menu exposes MobileConfig overrides. Worth knowing because some Reels
behaviour is flag-gated, but we found no documented flag that removes the
Reels tab, so this stays a fallback for research rather than a plan step.

## Takeaways for this project

1. Patch at `TigonServiceLayer.startRequest`. It is named, stable, and every
   successful project uses it.
2. Hide `clips_tab` by resource id and stop swipes to it, the FeurStagram way.
3. Implement "one reel, no scrolling" with InstaEclipse's pagination rule as
   the first line, and a UI-level swipe lock on the clips viewer as the second.
4. Ship the signature bypass, or DM-shared reel links will not open.
5. Keep Stories endpoints (`/feed/reels_tray/`) untouched.
