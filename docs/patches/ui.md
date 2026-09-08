# UI patches: "Hide Reels tab" and "Lock Reels viewer"

P2 and P3 of `docs/04-patch-plan.md`. Between them they remove Reels as a
*surface* from the view tree and pin the viewer to whatever single reel it
opened on. Neither touches the network; that is P1's job, and the two are meant
to be layered — the network gate denies the next reel, these two make sure
nothing on screen goes looking for it.

Verified against Instagram **435.0.0.37.76** (versionCode 384109472, arm64-v8a).

| | |
|---|---|
| Patches | `patches/.../ui/TabBarPatch.kt`, `patches/.../clips/ClipsViewerPatch.kt` |
| Runtime | `extension/.../Hiders.java`, `HiddenTabSwipeSkipper.java`, `ViewerLock.java`, `ReelContext.java` |
| Enabled | both by default; "Lock Reels viewer" `dependsOn` "Hide Reels tab" |
| Settings | none. v1 hardcodes everything on (plan §3, P6) |

`HiddenTabSwipeSkipper` and the resource-name hiding approach are adapted from
[Feurstagram](https://github.com/Feurstagram), GPLv3, credited in each file.

## Anchors

Nothing here names an obfuscated class. Every anchor is a framework type, a
named Instagram class, a string constant, a resource *name*, or a shape.

### "Hide Reels tab"

Primary — the main tab-bar binder's constructor, matched structurally and then
narrowed. In this build the levels resolve to, in order, 8 → 2 → **1**
candidate, and the winner is `LX/0ne;-><init>(Landroid/view/View;)V`:

1. `<init>` taking exactly one `Landroid/view/View;`, whose body has an
   `iput-object` of a `Landroid/view/ViewGroup;` field *and* one of a
   `Landroid/view/View;` field. (Feurstagram's shape. 8 matches here.)
2. …and the ViewGroup came out of a `View.findViewById`/`requireViewById` on
   the constructor's own parameter, not out of a factory. (2 matches.)
3. …and the defining class also exposes a method returning
   `Landroid/view/ViewGroup;` with a `Lcom/instagram/common/session/UserSession;`
   parameter — the binder builds each tab's container from the logged-in
   session; a view holder that happens to cache a ViewGroup does not. Both
   types are named, which is the whole point. (1 match.)

The most specific level with exactly one candidate wins.
`Hiders.install(Landroid/view/ViewGroup;)V` is injected immediately after the
`iput-object`, with the register the ViewGroup was just stored from. The
`const v0, #7f0b3f67` two instructions earlier is `id/tab_bar`, which is how
you confirm by eye that the right ViewGroup was captured — but the id is never
hardcoded in the patch.

Fallback — `Lcom/instagram/mainactivity/InstagramMainActivity;` ->
`onWindowFocusChanged(Z)V`. A framework override on a named class, so it cannot
be renamed. `Hiders.install(Landroid/app/Activity;)V` is injected at method
start and walks the window from the decor view instead.

Note that `InstagramMainActivity` has **no** `onCreate(Landroid/os/Bundle;)V`
of its own in this build (`docs/04-patch-plan.md` assumed it did): the cold
start runs through `BaseFragmentActivity` and obfuscated `A1r`/`A1s` overrides.
`onWindowFocusChanged` was chosen because it is a framework name, it runs after
the content view exists, and firing repeatedly is harmless — the runtime side
is idempotent.

If neither hook can be identified the patch throws a `PatchException` listing
the candidate count at each level and every structural candidate's class.

### "Lock Reels viewer"

Entry-point tracking, all name- and string-anchored:

- The clips-viewer source enum is the one class with superclass
  `Ljava/lang/Enum;`, *not* under `LX/`, whose `<clinit>` loads both the string
  `clips_tab` and the string `direct`. In this build that is exactly one class:
  `Lcom/instagram/clips/intf/ClipsViewerSource;` (421 string constants, one
  wire name plus one constant name per source).
- Injection sites are methods that take that enum and are defined under
  `Lcom/instagram/clips/` or `Linstagram/features/clips/`. In this build there
  is exactly one: `Lcom/instagram/clips/intf/ClipsViewerConfig;-><init>` — 223
  parameters, the enum at parameter #17, register **v22**. Every path that
  opens a reel builds that config, which makes it the ideal single hook.
  `ReelContext.onViewerSource(Ljava/lang/Enum;)V` goes in at index 0.
- Sites are sorted lexicographically and capped at 10, so a future build that
  threads the enum through many clips methods gets a deterministic, bounded set
  rather than dozens of injections. No site at all is a `PatchException`.

Autoscroll (best effort, never fatal):

- `Linstagram/features/clips/viewer/controller/autoscroll/` survives
  minification and holds one class,
  `ClipsSessionAutoscrollManager$lifecycleCallbacks$1`. Its constructor's
  parameter is the manager itself (`LX/3OV;` here), which carries the
  "autoscroll is on" boolean.
- The gate is then the one method returning `Z` with a single
  `Lcom/instagram/common/session/UserSession;` parameter that reads a boolean
  field off that manager: `LX/403;->A01(UserSession)Z` in this build, read
  right next to `Lcom/instagram/feed/media/mediaoption/MediaOption$Option;->AUTO_SCROLL`
  when the viewer's overflow menu is built. It is rewritten to
  `const/4 v0, 0x0; return v0`.
- If that chain does not resolve uniquely the patch prints why and carries on.
  Failing a build over an optional third layer would be the wrong trade: the
  pager lock already stops the viewer moving and the network gate already
  denies the next reel.

## Why the work is at runtime

The patches inject one call each. Everything else happens in the extension, off
a single `ViewTreeObserver.OnGlobalLayoutListener` installed on the tab bar (or
the decor view, in the fallback wiring).

That split is deliberate:

- **Resource names outlive resource ids.** Every target is resolved with
  `Resources.getIdentifier(name, "id", pkg)`, falling back to
  `com.instagram.android` so a renamed clone still finds its own ids. An
  Instagram update that reshuffles the id table changes nothing.
- **One pass would not hold.** Instagram rebuilds the tab bar, re-creates the
  Reels tab, re-enables the viewer pager's user input on rebind, and inflates
  the viewer's header long after the tab bar exists. A listener that re-runs
  every layout is the only thing that keeps up.
- **The viewer needs no fingerprint.** `ViewerLock` recognises the Reels
  surface by the presence of `clips_viewer_view_pager` /
  `clips_video_container` in the window, so P3's pager lock costs zero anchors.

### What each runtime piece does

`Hiders` (per layout pass, cheap: two `findViewById` calls)

- Sets `clips_tab` to `View.GONE`.
- Hides every child after the first inside
  `clips_viewer_action_bar` → `action_bar_tab_layout` — the "Friends"/"Blend"
  lane, i.e. a lateral route from a DM-opened reel into another endless feed.
  The tabs carry no per-tab id, so they are addressed by position, and the
  search is scoped to the clips action bar because `action_bar_tab_layout` is a
  generic id reused by other tabbed surfaces.
- Hands the window to `ViewerLock`.
- Logs a line the first time each target is actually hidden, never per pass.

`HiddenTabSwipeSkipper`

Hiding the tab icon only removes the button; `swipeable_tab_view_pager` keeps
the page, so Reels would still be one flick of the thumb away. The instant a
swipe settles on a page whose tab is `GONE`, the skipper re-aims it at the
nearest still-visible tab in the direction of travel. Which page is live is
read from the tab bar's `View.isSelected()` rather than a page index, so
nothing depends on the pager and the bar agreeing on an order. `creation_tab`
is treated as unreachable: it opens the camera, not a page.

Reflection, not subclassing: the pager is an
`androidx.viewpager2.widget.ViewPager2` whose class name survived minification
but whose `registerOnPageChangeCallback` did not (it is `A08` here), so its
page-change callback cannot be subclassed. The skipper listens on the framework
`ViewTreeObserver.OnScrollChangedListener` and reads the pager through
`getScrollState()` / `setCurrentItem(int)`, both of which kept their names.

`ViewerLock`

`clips_viewer_view_pager` is an `androidx.viewpager2.widget.ViewPager2` in this
build (confirmed: `LX/9Wz;->onViewCreated` does
`requireViewById(0x7f0b0c98)`, `check-cast` to `ViewPager2`, `setOrientation`,
and its inner `RecyclerView` is reached separately). `ViewPager2` keeps the
names `setUserInputEnabled(boolean)`, `getScrollState()`, `getCurrentItem()`
and `setCurrentItem(int)`.

So the primary lock is one reflective `setUserInputEnabled(false)`, re-applied
every layout pass because the app turns input back on when it rebinds the
viewer. Taps (pause), double taps (like) and the app's own programmatic paging
still work — which is what makes it feel like a stop rather than a freeze.

If a future build's pager has no `setUserInputEnabled`, two weaker measures
take over: a `View.OnTouchListener` on the pager swallowing vertical drags past
the touch slop, and a watchdog that pins `setCurrentItem` back to the page the
viewer opened on whenever the pager comes to rest elsewhere. Both are
best-effort — see limitations.

`ReelContext`

Records the last source enum name (lower-cased), exposes `isDirect()`
(substring `direct`, which covers `DIRECT`, `DIRECT_INBOX`, `DIRECT_THREAD`, …
without pinning constant names), and tracks `viewerVisible` with timestamps.
In v1 `ViewerLock` uses it only for logging: every viewer is single-reel in a
lobotomized build. The hook exists so a later "allow scrolling from the Reels
tab" toggle needs no new anchor.

## Known limitations

- **The fallback pager path is weak.** A `View.OnTouchListener` on a ViewGroup
  only sees what its children did not consume, and the real interception points
  (`onInterceptTouchEvent`, `RecyclerView.OnItemTouchListener`) need a subclass
  of an obfuscated type. If Instagram ever moves the clips viewer off
  `ViewPager2`, the lock degrades to "snap back after the fact" and the network
  gate becomes the load-bearing control.
- **The touch swallower replaces any existing touch listener** on the pager. It
  is only installed when there is no `setUserInputEnabled`, i.e. never on this
  build.
- **The watchdog pins to the page the viewer opened on**, not to page 0. That
  is right for a chain whose tapped reel is not first, but if a build changes
  page programmatically *after* the lock captured the index, the watchdog would
  fight it. This is why the watchdog is off whenever the input lock works.
- **Autoscroll neutralisation is a single gate.** `LX/9zU;->A0R` reads the same
  manager flag directly and is not patched; if Instagram routes auto-advance
  through that path only, the gate will not stop it. The pager lock will.
- **Bloks.** If the Reels tab becomes a Bloks screen, `clips_tab` may stop
  existing as a resource id and the hider silently does nothing (plan §7).
  `adb logcat -s Lobotagram` would stop showing "hid clips_tab".
- **No settings.** Nothing can be turned off at runtime; that is the point of
  the build.

## When it breaks

Trace mode first — it is a runtime switch, no rebuild:

```bash
adb shell setprop log.tag.Lobotagram DEBUG
adb logcat -s Lobotagram
```

A healthy cold start prints, roughly in order:

```
hiders installed on <tab bar class>
hid clips_tab
swipe skipper attached to androidx.viewpager2.widget.ViewPager2
```

and opening a reel from a DM adds:

```
reel viewer source: direct
reel viewer shown (source=direct, direct=true)
locking reels pager androidx.viewpager2.widget.ViewPager2 (userInputEnabled=true, currentItem=true, source=direct)
reels pager user input disabled
hid the Reels viewer lane past the first tab
```

Then, by symptom:

| Symptom | Where to look |
|---|---|
| Patching fails on "Hide Reels tab" | The `PatchException` lists the candidate count per level and every structural candidate. Re-run the candidate scan below and add a discriminator that holds in the new build; or delete the discriminator and lean on the `InstagramMainActivity` fallback. |
| Patching fails on "Lock Reels viewer", enum not found | The message lists every enum in the APK holding `clips_tab`. Pick the clips-viewer source enum and update `SOURCE_ENUM_STRINGS`. |
| Patching fails on "Lock Reels viewer", no injection site | The source enum moved out of `com.instagram.clips` / `instagram.features.clips`. Widen `CLIPS_PACKAGES`. |
| "autoscroll gate skipped" printed | Expected drift, not a failure. The chain starts at `AUTOSCROLL_PACKAGE`; check that package still exists and still holds the lifecycle-callback class. |
| Reels tab still visible | `hid clips_tab` missing from the log. Either the hook never ran (no `hiders installed on …`) or `clips_tab` is no longer a resource id — check `aapt2 dump resources` for it. |
| Swipe still reaches Reels | `swipe skipper attached …` missing. Check `swipeable_tab_view_pager` still exists, and that `getScrollState` is still a public name on the pager class. |
| Reel scrolls to the next one | `reels pager user input disabled` missing. Check `setUserInputEnabled(boolean)` still exists on the pager's class, and that `clips_viewer_view_pager` is still the vertical pager's id. |
| Friends lane still shown | `clips_viewer_action_bar` / `action_bar_tab_layout` nesting changed. Dump the viewer's layout and re-check which container holds the tab strip. |

### Re-running the recon

The anchors above came from throwaway dexlib2 walks over the APK. There is no
committed recon tool: the CLI fat jar already bundles dexlib2 and
multidexlib2, so a single-file Java program run straight off it is enough.

```java
// Scan.java - read the whole APK, then walk it however the question needs
DexFile dex = MultiDexIO.readDexFile(
        true, new File(args[0]), new BasicDexFileNamer(), null, null);
for (ClassDef cd : dex.getClasses()) { /* ... */ }
```

```bash
java -Xmx6g -cp tools/revanced-cli-6.0.0-all.jar Scan.java instagram-<version>.apk
```

What to check, in the order the patches need it:

1. `Lcom/instagram/mainactivity/InstagramMainActivity;` exists and still
   overrides `onWindowFocusChanged(Z)V`.
2. Exactly one non-`LX/` enum's `<clinit>` holds `clips_tab` and `direct`.
3. Exactly one method under the clips packages takes that enum.
4. `Linstagram/features/clips/viewer/controller/autoscroll/` still exists.
5. `id/clips_tab`, `id/tab_bar`, `id/swipeable_tab_view_pager`,
   `id/clips_viewer_view_pager`, `id/clips_viewer_action_bar`,
   `id/action_bar_tab_layout`, `id/clips_video_container` all still exist
   (`aapt2 dump resources <apk> | grep ' id/'`).
6. `androidx.viewpager2.widget.ViewPager2` still declares
   `setUserInputEnabled`, `getScrollState`, `getCurrentItem`, `setCurrentItem`
   under those names.

Then confirm the injection landed, in the patched APK:

```bash
unzip -o out.apk 'classes*.dex' -d dex
dexdump -d dex/classesN.dex | grep 'Hiders;.install'
dexdump -d dex/classesN.dex | grep 'ReelContext;.onViewerSource'
```
