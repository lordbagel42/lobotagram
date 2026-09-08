# CLAUDE.md

lobotagram is a ReVanced-style `.rvp` patch bundle for the Instagram Android
app. Read `docs/04-patch-plan.md` before touching patch code and
`docs/05-development.md` for how the build works.

## Commands

```bash
./gradlew build                       # -> build/lobotagram-<version>.rvp and build/lobotagram.rvp
./gradlew clean build                 # from scratch
java -jar tools/revanced-cli-6.0.0-all.jar list-patches -b -p build/lobotagram.rvp --packages --versions --options
./scripts/patch.sh <apk> -o out.apk [--install] [--trace] [-- -e "<patch name>"]
```

There are no unit tests. The test is: `list-patches` shows the patch, then
patching a real APK reports it succeeded. Patching needs `-Xmx6g` and a couple
of minutes.

## Modules

| Module | Language | Purpose |
|---|---|---|
| `patches` | Kotlin, JVM 17 | fingerprints and bytecode injection, `dev.lobotagram.patches` |
| `extension` | plain Java, 17 | code merged into Instagram, `dev.lobotagram.extension` |

`extension` is compiled with `javac` against `android.jar` and dexed with D8;
`patches` is `compileOnly` against the ReVanced CLI fat jar, which bundles
Patcher v22. Neither uses the official ReVanced Gradle plugin (GitHub Packages
needs a token).

## Conventions

- **Fingerprints must never use obfuscated names.** Anchor on non-obfuscated
  class and method names (`Lcom/instagram/api/tigon/TigonServiceLayer;`),
  string literals, resource ids, or structural shape. Anything under `LX/` is
  renamed every release; if a fingerprint needs to reach such a class, get
  there from a stable anchor at patch time.
- **Every failure path throws `PatchException` with an actionable message**:
  what was looked for, what was found instead, and what to do next. A patch
  that fails silently or with `!!` is a bug.
- **Extension code compiles against `android.jar` with no third-party
  dependencies.** No Kotlin, no AndroidX, no Gson: it is merged into someone
  else's APK. Reflection is fine where the target API is hidden.
- **Keep files disjoint per feature.** One patch per file, one extension class
  per concern, named after the feature (`network/`, `ui/`, `clips/`,
  `signature/` per the plan). Shared constants live in
  `patches/shared/Constants.kt`; the pinned Instagram versions live there and
  nowhere else.
- Patches that are diagnostic or risky ship with `use = false` so a user has to
  enable them by name.
- Log from the extension through `Lobo`, never `android.util.Log` directly, so
  `adb shell setprop log.tag.Lobotagram DEBUG` controls everything.
- Never commit jars, APKs or keystores. `.gitignore` covers `tools/*.jar`,
  `*.apk` and `*.keystore`; the Gradle wrapper jar is the one exception.
