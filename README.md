# lobotagram

Plan and research for a "lobotomized" Instagram: an Android build with Reels
removed as a surface, while DMs, Stories, posting and the rest keep working.
A reel a friend sends in a DM still plays, but there is no scrolling onward
from it.

This repository holds the research, the plan, and a working ReVanced-style
patch bundle (`.rvp`) with six patches. Every patch applies cleanly to
Instagram 435.0.0.37.76 in the build here. **None of it has run on a phone
yet**: the on-device checklist in the plan is the next step.

| Document | Contents |
|---|---|
| [docs/01-how-revanced-works.md](docs/01-how-revanced-works.md) | How YouTube ReVanced patches an APK: Patcher, patches, fingerprints, extensions, CLI/Manager, and the Morphe fork |
| [docs/02-instagram-apk.md](docs/02-instagram-apk.md) | Where to get the Instagram APK, which variant, what is inside, the stable anchors and endpoints |
| [docs/03-prior-art.md](docs/03-prior-art.md) | FeurStagram, InstaEclipse, ReVanced's Instagram patch, and an apktool experiment, with the parts we reuse |
| [docs/04-patch-plan.md](docs/04-patch-plan.md) | The plan: patches P1-P6, build pipeline, verification checklist, risks, order of work |
| [docs/05-development.md](docs/05-development.md) | Project layout, build and test commands, how the rvp is built without the official ReVanced Gradle plugin, trace mode, loading the rvp into ReVanced Manager |
| [CLAUDE.md](CLAUDE.md) | Commands, module layout and the conventions patches have to follow |
| [tools/recon.sh](tools/recon.sh) | Script to confirm the patch anchors exist in a given Instagram APK |
| [scripts/patch.sh](scripts/patch.sh) | Merge split bundles, build the rvp, patch, sign with a persistent keystore, optionally install |
| [docs/patches/network.md](docs/patches/network.md) | The network gate, signature bypass and feed filter: anchors, injection points, rule table, how to read trace logs, what to do when a fingerprint breaks |
| [docs/patches/ui.md](docs/patches/ui.md) | The Reels tab hider, swipe skipper, viewer lock and viewer-source hook: anchors, runtime behaviour, known limitations |
| [patches/](patches) | Kotlin patches (`dev.lobotagram.patches`) |
| [extension/](extension) | Plain-Java code merged into Instagram (`dev.lobotagram.extension`) |

## Patches

| Patch | Default | What it does |
|---|---|---|
| Block Reels surfaces | on | Hooks `TigonServiceLayer.startRequest` and throws for every Reels feed endpoint (`/clips/home/`, `/clips/connected/`, `/clips/discover`, Blend, trends, audio/effect/tag chains) and every paginated `clips` request. DMs, Stories and uploads are allow-listed. Options `blockExplore`, `blockNudges`. |
| Hide Reels tab | on | Hides `clips_tab` in the bottom bar, re-aims horizontal swipes so the Reels page is never reachable, hides the Friends/Blend lane in the viewer. Installed from the tab-bar binder and from every activity window. |
| Lock Reels viewer | on | A reel opened from a DM, profile or link plays, but the vertical pager is locked and the auto-advance gate returns false. Records the viewer's entry point (`ClipsViewerSource`). |
| Hide suggested Reels in feed | on | Rewrites the `clips_netego` feed unit type so Instagram's own parser drops "Suggested reels" from the home feed. |
| Bypass signature check | on | Makes Instagram's signing-certificate trust checks pass, so deep links (a reel shared into a DM) open instead of falling back to the feed. |
| Lobotagram diagnostics | off | Modifies nothing. Prints what every fingerprint resolved to, for checking a new Instagram version. |

Fingerprints anchor only on named classes (`TigonServiceLayer`,
`ClipsViewerSource`, `ClipsViewerConfig`, `BaseFragmentActivity`), string
literals, resource names and structural shape, never on obfuscated `X/...`
names or numeric ids, so a new Instagram release should usually just need a
rebuild. When one does break, the patch fails with a message naming the
candidates it found, and the diagnostics patch shows every anchor at once.

## Use it on your phone

Two routes. Both need the Instagram APK from APKMirror (arm64-v8a, see
[docs/02-instagram-apk.md](docs/02-instagram-apk.md)) and, because the result is
re-signed, stock Instagram uninstalled first (or a package-rename patch, not
yet written).

**On the phone with ReVanced Manager.** Take `lobotagram.rvp` from a GitHub
release of this repo (CI attaches it on `v*` tags) or from `./gradlew build`.
In ReVanced Manager: Settings → patch sources → "Add new patches from a URL or
local files", point it at the rvp, accept the unverified-source prompt, then
patch Instagram with the Lobotagram patches selected.

**On a computer with the CLI.**

```bash
./gradlew build                                                  # -> build/lobotagram.rvp
./scripts/patch.sh instagram-435.0.0.37.76.apk -o lobotagram.apk --install
```

JDK 21 and an Android SDK (for `android.jar`) are the only prerequisites; the
ReVanced CLI and APKEditor jars are downloaded and checksummed on demand.
`scripts/patch.sh` creates `lobotagram.keystore` on first use. Keep it: updates
must be signed with the same key. See
[docs/05-development.md](docs/05-development.md).

**First run: trace mode.** Turn on request tracing before you judge the
result, so every allowed and blocked request is visible:

```bash
adb shell setprop log.tag.Lobotagram DEBUG
adb logcat -s Lobotagram
```

Then walk the checklist in [docs/04-patch-plan.md](docs/04-patch-plan.md)
section 6: open a reel from a DM and try to swipe on, open the Reels tab (gone),
post a Story, send a DM, open Explore.

## The approach in one paragraph

Patch the official APK with a ReVanced-style toolchain (Morphe or ReVanced
Patcher). Hook the one named, stable network class
`com.instagram.api.tigon.TigonServiceLayer.startRequest` and refuse every
request that feeds a Reels surface, plus every paginated `clips` request, so a
DM-shared reel loads once and the next one never arrives. Hide the `clips_tab`
bottom-bar icon and skip swipes to its page. Lock the reels viewer's vertical
pager as a second line of defense. Force Instagram's own signing-certificate
check to pass so deep links from DMs still open. Keep Stories and `direct_v2`
endpoints untouched.

## Status

- [x] Research ReVanced architecture, Instagram APK sources, prior art
- [x] Plan, recon of the real 435.0.0.37.76 APK
- [x] Build scaffold: Gradle project, extension DEX, rvp with `classes.dex` for ReVanced Manager, CI release workflow
- [x] Patches: network gate, signature bypass, feed filter, Reels tab hider + swipe skipper, viewer lock + source hook, diagnostics
- [x] Code review pass (gate rule audit against every `clips/*` endpoint in the APK, leak and crash-safety fixes)
- [ ] Run on a device: trace-mode endpoint capture, then the section 6 checklist
- [ ] Tag `v0.1.0` so CI publishes the rvp for ReVanced Manager
- [ ] Optional: package-rename ("Clone") patch to install next to stock Instagram

## License

Plan and any future patches: GPLv3, to stay compatible with the FeurStagram
code we intend to borrow. Do not redistribute patched Instagram APKs.
