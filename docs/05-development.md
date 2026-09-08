# Development

## Layout

```
lobotagram/
├── extension/                        # plain Java, compiled to one DEX (lobotagram.rve)
│   └── src/main/java/dev/lobotagram/extension/
│       ├── Lobo.java                 # logging, runtime trace switch
│       ├── Gate.java                 # URI decision table (throwIfBlocked)
│       └── FeedFilter.java           # feed-unit type token filter
├── patches/                          # Kotlin, the patches themselves
│   └── src/main/kotlin/dev/lobotagram/patches/
│       ├── shared/Constants.kt       # package name, extension paths, pinned versions
│       ├── network/NetworkGatePatch.kt    # P1: TigonServiceLayer.startRequest hook
│       ├── signature/SignatureBypassPatch.kt  # P4: key-hash checks return true
│       ├── feed/FeedItemFilterPatch.kt    # P5: drop the suggested-reels feed unit
│       └── DiagnosticsPatch.kt       # "Lobotagram diagnostics", off by default
├── scripts/patch.sh                  # merge splits, build the rvp, patch, sign, install
├── tools/                            # downloaded jars (git-ignored)
└── build/lobotagram.rvp              # the patch bundle
```

## Build and test

```bash
./gradlew build                       # produces build/lobotagram-<version>.rvp and build/lobotagram.rvp
./gradlew clean :patches:build        # from scratch

unzip -l build/lobotagram.rvp                        # patch classes, extensions/lobotagram.rve, classes.dex
unzip -p build/lobotagram.rvp META-INF/MANIFEST.MF   # bundle metadata

java -jar tools/revanced-cli-6.0.0-all.jar \
    list-patches -b -p build/lobotagram.rvp --packages --versions --options

# End to end. The diagnostics patch is off by default, so enable it by name.
java -Xmx6g -jar tools/revanced-cli-6.0.0-all.jar patch \
    -b -p build/lobotagram.rvp -e "Lobotagram diagnostics" \
    -o /tmp/out.apk --purge -t /tmp/patch-tmp instagram-435.0.0.37.76.apk

./scripts/patch.sh instagram-435.0.0.37.76.apk -o /tmp/out.apk \
    -- -e "Lobotagram diagnostics"
```

Patching takes a couple of minutes and needs memory: the APK is 135 MB with 20
DEX files, all of which are loaded at once. `-Xmx6g` is the working figure.

`scripts/patch.sh` signs with `./lobotagram.keystore`, generated on the first
run by `keytool` (`CN=lobotagram`, RSA 2048, 10000 days). ReVanced signs with a
BouncyCastle keystore — `KeyStore.getInstance("BKS", "BC")` — so the script
passes `-storetype BKS` and points `-providerpath` at the CLI fat jar, which
bundles the provider.

**Keep that keystore.** Android only accepts an update signed with the same
key, so losing it means uninstalling and losing app data. It is git-ignored;
back it up somewhere private. The store password is not a secret (the file is),
and ReVanced CLI wants it on the command line anyway.

## Toolchain

| Piece | Where it comes from |
|---|---|
| ReVanced CLI 6.0.0 | `:patches:fetchCli` downloads it into `tools/` and checks its sha256 |
| ReVanced Patcher v22, smali/dexlib2, Kotlin stdlib | bundled inside that CLI jar, which is the `compileOnly` compile classpath |
| D8 | `com.android.tools:r8` from Google Maven |
| android.jar | local Android SDK, via `lobotagram.androidJar`, `ANDROID_HOME` or `ANDROID_SDK_ROOT` |
| APKEditor 1.4.9 | `scripts/patch.sh` downloads it when the input is a split bundle |

The patcher artifacts (`app.revanced:patcher`) are published to GitHub
Packages, which needs an authenticated token, so the CLI release jar stands in
for them. Nothing in this build needs a secret.

Two compiler flags are load-bearing in `patches/build.gradle.kts`:
`-Xcontext-parameters`, because the patcher's matching DSL is built on context
parameters, and `-Xskip-prerelease-check`, because Patcher v22 was published
from a pre-release Kotlin 2.3 build.

## How the rvp is built without the official plugin

`app.revanced.patches` (the official Gradle plugin) is not fetchable here, so
`patches/build.gradle.kts` reproduces what it does. An `.rvp` is a jar with
four things in it, built in this order:

1. **`:extension:compileJava`** — `javac` against `android.jar` at Java 17.
   No Android Gradle Plugin: it would pull a large dependency tree for two steps.
2. **`:extension:dexExtension`** — D8 `--release --min-api 27` over the
   extension jar, giving one `classes.dex`.
3. **`:extension:syncExtension`** — renames it to
   `build/revanced/extensions/lobotagram.rve` and publishes that directory as
   the `extensionConfiguration` artifact. `:patches` consumes it as a resource
   directory, so it lands in the bundle at `extensions/lobotagram.rve`, which is
   what `extendWith(EXTENSION)` looks up.
4. **`:patches:jar`** — the patch classes plus that resource, with
   `archiveExtension = "rvp"` and the manifest attributes the official plugin
   writes (`Name`, `Description`, `Version`, `Timestamp`, `Source`, `Author`,
   `Contact`, `Website`, `License`).
5. **`:patches:dexPatches` + `:patches:buildAndroid`** — D8 compiles the *patch*
   classes too (min API 27) and the resulting `classes.dex` is merged into the
   rvp. That is what lets ReVanced Manager run the same file on a phone; the
   desktop CLI only reads the `.class` files.
6. **`:patches:copyRvp`** — a copy at the stable path `build/lobotagram.rvp`.

`./gradlew build` runs all of it.

## Trace mode

Trace logging is a runtime switch, not a build flag, so it needs no rebuild:

```bash
adb shell setprop log.tag.Lobotagram DEBUG   # on
adb logcat -s Lobotagram                     # read
adb shell setprop log.tag.Lobotagram INFO    # off
```

`Lobo.trace()` reads that property, `Lobo.d(...)` is a no-op when it is off.
The property is lost on reboot.

## Loading the rvp into ReVanced Manager

In Manager: **Settings → patch sources → "Add new patches from a URL or local
files"**, and give it either the local `lobotagram.rvp` or the URL of the rvp
attached to a GitHub release (CI attaches it on `v*` tags). The bundle contains
`classes.dex`, so Manager can load the patches on the phone.

Manager and CLI both verify bundle signatures by default and we do not sign
bundles, so the CLI needs `-b` (`--bypass-verification`). In Manager, accept the
unverified-source prompt.

## Conventions

See `CLAUDE.md`.
