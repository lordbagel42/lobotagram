# How YouTube ReVanced works

ReVanced does not ship a modified YouTube. It ships **patches**, and the user's
own device (or a desktop CLI) applies those patches to an official YouTube APK
that the user supplies. The result is repackaged, re-signed with the user's own
key, and installed. Nothing of Google's code is redistributed, which is the
whole reason the project can exist publicly.

## The three moving parts

| Component | Role |
|---|---|
| **ReVanced Patcher** (`revanced-patcher`, Kotlin library) | Opens an APK, decodes it, lets patches rewrite Dalvik bytecode and resources, emits the modified pieces for repackaging. |
| **ReVanced Patches** (`revanced-patches`, an `.rvp` bundle) | The actual modifications: "Hide ads", "SponsorBlock", "Return YouTube Dislike", and so on. Each is a small Kotlin object. |
| **ReVanced CLI / ReVanced Manager** | Front ends. They download or accept an APK, run the Patcher with a chosen set of patches, then zipalign, sign, and install. |

## Patching pipeline

1. **Decode.** The Patcher uses Apktool's library (`apktool-lib`) to decode
   resources when a resource patch needs them, and `smali`/`dexlib2` via
   `MultiDexIO` to load every `classes*.dex` into an in-memory list of class
   definitions (`BytecodePatchContext.classDefs`).
2. **Merge extensions.** A patch may declare `extendWith("foo.rve")`. An
   extension is a precompiled DEX built from ordinary Java/Kotlin. Its classes
   are merged into `classDefs` before the patch runs, so the patch can inject a
   one-line `invoke-static` into YouTube that calls into real, readable code
   instead of hand-writing long smali sequences.
3. **Fingerprint.** Method and class names in YouTube are obfuscated and change
   every release, so patches never hardcode them. A fingerprint describes a
   method by stable traits: return type, access flags, parameter types, an
   opcode pattern, string literals it references, or a custom predicate. The
   Patcher resolves each fingerprint to the one method that matches. Strings are
   indexed for fast lookup.
4. **Mutate.** With the method found, the patch edits its instruction list:
   `addInstructions(index, smali)`, `removeInstructions`, `replaceInstruction`,
   `addInstructionsWithLabels`. The typical shape is "insert an `invoke-static`
   into my extension right after the instruction that loads the value I care
   about, then `move-result` and branch or return on it."
5. **Resource patches** (optional) edit decoded XML with a DOM API or add raw
   files, e.g. to add a settings screen.
6. **Emit.** The Patcher returns modified DEX files plus resources. The CLI or
   Manager rebuilds the APK, aligns it, and signs it with the user's key.

## The patch DSL (current API)

```kotlin
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Disable ads in the app.",
) {
    compatibleWith("com.some.app"("1.0.0"))
    dependsOn(someResourcePatch)
    extendWith("disable-ads.rve")

    apply {
        val showAdsMethod = firstMethod {
            returnType == "Z" && parameterTypes == listOf("Z") &&
                implementation?.instructions?.any { it.opcode == Opcode.RETURN } == true
        }
        showAdsMethod.addInstructions(
            0,
            """
                invoke-static {}, LDisableAdsPatch;->shouldDisableAds()Z
                move-result v0
                return v0
            """
        )
    }
}
```

Declarative fingerprints look like this:

```kotlin
val BytecodePatchContext.loadAdsMethod by composingFirstMethod {
    definingClass("Lcom/some/app/ads/Loader;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("Z")
    opcodes(Opcode.RETURN)
    strings("pro")
}
```

Useful helpers: `navigate(method).to(index)` walks into a called method,
`classDefs.getOrReplaceMutable(classDef)` makes a class editable, `document()`
edits resource XML, patch `options` parametrize a patch at apply time.

Older patches (the 2023-2024 Instagram "Hide timeline ads" patch, for example)
used an annotation style: `object ShowAdFingerprint : MethodFingerprint("Z",
AccessFlags..., listOf("L","L","Z","Z"), opcodes = listOf(...))` and a
`@Patch(name=..., compatiblePackages=[CompatiblePackage("com.instagram.android",
["275.0.0.27.98"])]) object HideTimelineAdsPatch : BytecodePatch(setOf(...))`
with an `execute(context)` body. The ideas are identical.

## How YouTube specifically gets modified

- **Ads.** Fingerprints locate the methods that decide whether an ad component
  renders or that build ad request parameters. The patch inserts a call into the
  extension that returns "hide" or strips the ad from the list.
- **Litho / component filtering.** Much of YouTube's UI is server-driven
  (Litho). ReVanced hooks the component-path builder and filters by the
  identifier strings the server sends, which is why the extension needs a
  settings UI to choose what to hide.
- **SponsorBlock / Return YouTube Dislike.** The extension does network calls and
  draws overlays; patches only wire a few callbacks (player time, video id).
- **Settings.** A resource patch adds a preference screen and strings; a bytecode
  patch hooks the existing settings activity so the new screen is reachable.
- **Client spoofing and GmsCore.** Because the APK is re-signed, Google sign-in
  breaks. ReVanced patches the app to talk to microG (GmsCore) instead of Play
  Services. Instagram has no equivalent dependency, which is good news for us.

## The Morphe fork

In January 2026, several former ReVanced and ReVanced Extended contributors
launched **Morphe** (morphe.software, GitHub org `MorpheApp`). It is the same
architecture with renamed packages: `app.morphe.patcher`, `.mpp` patch bundles
instead of `.rvp`, `.mpe` extensions instead of `.rve`, a `morphe-cli` /
`morphe-desktop` jar, and a Gradle plugin `app.morphe.patches`. The Instagram
prior art we lean on most (FeurStagram) is built on Morphe. Either toolchain
works for this project; the plan uses Morphe because a working Instagram
reference project already exists on it.

## Practical constraints that carry over to Instagram

- **Re-signing.** The patched APK cannot share a signature with the Play Store
  build, so you uninstall stock Instagram or patch with a package rename. Any
  in-app signature self-check has to be neutralized (Instagram has one, see the
  plan).
- **Split APKs.** Play distributes app bundles. Patchers work on one APK, so a
  bundle (`.apkm`, `.xapk`, `.apks`) must be merged first (APKEditor) or you
  download a single-APK variant.
- **Fingerprint drift.** Patches break when the target method's shape changes.
  Anchoring on non-obfuscated names, resource ids, and string literals gives the
  longest life.

## Sources

- ReVanced Patcher docs: `docs/1_setup.md`, `2_patcher_intro.md`,
  `3_1_patch_anatomy.md`, `3_2_matching.md`, `4_structure_and_conventions.md`,
  `5_apis.md` in <https://github.com/ReVanced/revanced-patcher>
- ReVanced CLI usage: <https://github.com/ReVanced/revanced-cli/blob/main/docs/1_usage.md>
- ReVanced Patches template: <https://github.com/ReVanced/revanced-patches-template>
- ReVanced Patches (Instagram "Hide ads" history): <https://github.com/ReVanced/revanced-patches>
  (returned HTTP 451 from this environment; the 2024 Instagram patch source was
  read from a public mirror)
- Morphe: <https://morphe.software>, <https://github.com/MorpheApp>
