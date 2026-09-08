# lobotagram

Plan and research for a "lobotomized" Instagram: an Android build with Reels
removed as a surface, while DMs, Stories, posting and the rest keep working.
A reel a friend sends in a DM still plays, but there is no scrolling onward
from it.

This repository holds the research, the plan, and a working build that produces
an `.rvp` patch bundle. The Reels patches themselves are not written yet.

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
| [patches/](patches) | Kotlin patches (`dev.lobotagram.patches`); currently one diagnostics patch |
| [extension/](extension) | Plain-Java code merged into Instagram (`dev.lobotagram.extension`) |

## Build

```bash
./gradlew build                       # -> build/lobotagram.rvp
./scripts/patch.sh instagram-435.0.0.37.76.apk -o lobotagram.apk --install
```

JDK 21 and an Android SDK (for `android.jar`) are the only prerequisites; the
ReVanced CLI and APKEditor jars are downloaded and checksummed on demand. See
[docs/05-development.md](docs/05-development.md).

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

- [x] Research ReVanced architecture
- [x] Identify Instagram APK sources and version to pin
- [x] Study prior art (FeurStagram, InstaEclipse)
- [x] Write the patch plan
- [x] Download the pinned APK and run `tools/recon.sh`
- [x] Scaffold the patches project (Gradle build, rvp bundle, extension DEX, CI)
- [ ] Implement P1 (network gate) in trace mode and collect endpoint logs
- [ ] Implement P4, P1 blocking, P2, P3, P5

## License

Plan and any future patches: GPLv3, to stay compatible with the FeurStagram
code we intend to borrow. Do not redistribute patched Instagram APKs.
