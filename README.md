# lobotagram

Plan and research for a "lobotomized" Instagram: an Android build with Reels
removed as a surface, while DMs, Stories, posting and the rest keep working.
A reel a friend sends in a DM still plays, but there is no scrolling onward
from it.

This repository currently holds research and a plan, not patches.

| Document | Contents |
|---|---|
| [docs/01-how-revanced-works.md](docs/01-how-revanced-works.md) | How YouTube ReVanced patches an APK: Patcher, patches, fingerprints, extensions, CLI/Manager, and the Morphe fork |
| [docs/02-instagram-apk.md](docs/02-instagram-apk.md) | Where to get the Instagram APK, which variant, what is inside, the stable anchors and endpoints |
| [docs/03-prior-art.md](docs/03-prior-art.md) | FeurStagram, InstaEclipse, ReVanced's Instagram patch, and an apktool experiment, with the parts we reuse |
| [docs/04-patch-plan.md](docs/04-patch-plan.md) | The plan: patches P1-P6, build pipeline, verification checklist, risks, order of work |
| [tools/recon.sh](tools/recon.sh) | Script to confirm the patch anchors exist in a given Instagram APK |

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
- [ ] Download the pinned APK and run `tools/recon.sh`
- [ ] Scaffold the patches project
- [ ] Implement P1 (network gate) in trace mode and collect endpoint logs
- [ ] Implement P4, P1 blocking, P2, P3, P5

## License

Plan and any future patches: GPLv3, to stay compatible with the FeurStagram
code we intend to borrow. Do not redistribute patched Instagram APKs.
