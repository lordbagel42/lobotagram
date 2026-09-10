# What ReVanced Manager expects from a remote patch source

Findings from reading the Manager source at `github.com/ReVanced/revanced-manager`,
`main` (v2.6.1) and tag `v2.7.0-dev.11`. Everything below is quoted from that
tree; file paths are relative to `app/src/main/java/app/revanced/manager/`.

## Short version

**Manager's "Enter URL" field wants a URL to a small JSON document, not to the
`.rvp` itself.** The JSON tells Manager where the `.rvp` is. Paste this:

```
https://github.com/lordbagel42/lobotagram/releases/latest/download/patches.json
```

A PGP signature is **not** required. Manager parses `signature_download_url`
and then never uses it — there is no signature verification and no
unverified-source prompt in Manager 2.x at all. See
[No signature verification](#no-signature-verification).

## 1. The URL the "Add new patches from a URL" flow accepts

Settings → patch sources → **Add patches** offers two source types
(`ui/component/sources/ImportSourceDialog.kt`): "Enter URL" (marked
*Recommended*, description *"Patches can receive updates"*) and "Select from
storage". The URL lands in `DashboardViewModel.createRemoteSource`, then
`PatchBundleRepository.createRemote(url, autoUpdate)`, which builds a
`JsonPatchBundle`:

```kotlin
// domain/repository/PatchBundleRepository.kt
is SourceInfo.Remote -> JsonPatchBundle(
    actualName,
    uid,
    versionHash,
    releasedAt,
    null,
    file,
    source.url.toString(),
    autoUpdate,
    PatchBundleLoader
)
```

`JsonSource` is the whole contract. It `GET`s the endpoint and deserializes the
body into a `ReVancedAsset`:

```kotlin
// domain/sources/RemoteSource.kt
typealias RemotePatchBundle = RemoteSource<PatchBundle>
typealias JsonPatchBundle = JsonSource<PatchBundle>
typealias APIPatchBundle = APISource<PatchBundle>

class JsonSource<T>(...) : RemoteSource<T>(...) {
    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        runCatching {
            http.request<ReVancedAsset> {
                url(endpoint)
            }.getOrThrow()
        }.getOrElse { throw it.asRemoteSourceException() }   // v2.7.0-dev only
    }
}
```

and then downloads the `.rvp` from the `download_url` inside that JSON:

```kotlin
// domain/sources/RemoteSource.kt
private suspend fun download(info: ReVancedAsset) = withContext(Dispatchers.IO) {
    outputStream().use {
        http.streamTo(it) {
            url(info.downloadUrl)
        }
    }

    UpdateResult(info.version, info.createdAt)
}
```

So:

- **not** a direct `.rvp` URL — the body has to deserialize as JSON, and a
  `.rvp` (a zip) does not;
- **not** a GitHub repo URL, and **not** the GitHub releases API — Manager does
  no GitHub-specific handling anywhere;
- **not** required to be the ReVanced API. `SourceInfo.API` is a separate,
  sentinel-backed source type reserved for the bundled default bundle
  (`data/room/sources/Source.kt`: `Local.SENTINEL = "local"`,
  `API.SENTINEL = "api"`, anything else becomes `Remote(Url(value))`), and it
  goes through `APISource`, which calls `ReVancedAPI.getPatchesUpdate()`
  instead. A user-added URL is always a `JsonSource`.
- The URL is otherwise unvalidated in shape: any non-empty string is accepted
  by the dialog and handed to ktor's `Url(value)`. The path/extension is
  irrelevant; only the response body matters. It is trimmed before use in
  v2.7.0-dev.

## 2. The exact JSON schema

One object, five fields, from `network/dto/ReVancedAsset.kt` — verbatim:

```kotlin
package app.revanced.manager.network.dto

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReVancedAsset (
    @SerialName("download_url")
    val downloadUrl: String,
    @SerialName("created_at")
    val createdAt: LocalDateTime,
    @SerialName("signature_download_url")
    val signatureDownloadUrl: String? = null,
    val description: String,
    val version: String,
)

@Serializable
data class ReVancedAssetHistory(
    val version: String,
    @SerialName("created_at")
    val createdAt: LocalDateTime,
    val description: String,
)
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `download_url` | string | **yes** | direct URL to the `.rvp`; Manager streams it to disk as `patches.jar` |
| `created_at` | `kotlinx.datetime.LocalDateTime` | **yes** | **no timezone.** See below |
| `description` | string | **yes** | no default in the data class, so a missing `description` is a parse failure |
| `version` | string | **yes** | the update sentinel, see [§4](#4-how-manager-checks-for-updates) |
| `signature_download_url` | string or null | no | defaults to `null`, **and is never read** |

`created_at` is the one real trap. It is a `kotlinx.datetime.LocalDateTime`, so
its serializer is ISO-8601 **without** an offset or `Z`: `2026-09-10T12:34:56`
parses, `2026-09-10T12:34:56Z` and `2026-09-10T12:34:56+00:00` both throw. The
official API emits it in exactly that shape — `curl https://api.revanced.app/v5/patches`
returns

```json
{
  "version": "v6.2.1",
  "created_at": "2026-06-02T18:30:00",
  "description": "# [6.2.1](…)\n\n### Bug Fixes\n…",
  "download_url": "https://api.revanced.app/v5/patches.rvp",
  "signature_download_url": "https://api.revanced.app/v5/patches.rvp.asc"
}
```

so `scripts/release-json.sh` converts the release timestamp to UTC and drops the
zone designator. Note the official `version` carries a leading `v`; ours does
too, for symmetry.

Unknown extra keys are safe: the shared `Json` is lenient
(`di/HttpModule.kt`: `isLenient = true`, `ignoreUnknownKeys = true`).

The *name* Manager shows for the bundle does not come from this JSON — it comes
from the rvp's jar manifest:

```kotlin
// domain/repository/PatchBundleRepository.kt
override fun realNameOf(loaded: PatchBundle) = loaded.manifestAttributes?.name
// domain/sources/Source.kt
val PatchBundleSource.version get() = loaded?.manifestAttributes?.version
```

`patcher/patch/PatchBundle.kt` reads those with
`manifest?.mainAttributes?.getValue(name)` for `name`, `version`,
`description`, `source`, `author`, `contact`, `website`, `license`. Jar
attribute lookup is case-insensitive, so the capitalised attributes
`patches/build.gradle.kts` writes (`Name`, `Version`, …) are picked up as-is.
The displayed version is therefore the manifest `Version` (`0.1.0`), while the
JSON `version` (`v0.1.0`) is only the update sentinel.

## 3. No signature verification

`signature_download_url` is the only occurrence of the word "signature" in
Manager's patch-source code, and it is a dead field:

```
$ grep -rn "signatureDownloadUrl" --include=*.kt .
./app/src/main/java/app/revanced/manager/network/dto/ReVancedAsset.kt:14:    val signatureDownloadUrl: String? = null,
```

That is the *only* hit in the whole repository, on both `main` and
`v2.7.0-dev.11`. Nothing downloads the `.asc`, there is no PGP/OpenPGP or
Bouncy Castle dependency for it, and `patcher/patch/PatchBundle.kt` loads the
bundle straight through `app.revanced.patcher.patch.loadPatches` with no
verification step. (`useGpgCmd()` in `app/build.gradle.kts` and
`api/build.gradle.kts` signs Manager's *own* release APK; it has nothing to do
with bundles.)

There is also **no unverified-source prompt** in Manager 2.x. Searching the
string table for `verif`, `signat`, `unsign` and `trust` finds only:

```xml
<string name="api_url_dialog_warning">ReVanced Manager connects to the API to download patches and updates. Make sure that you trust it.</string>
<string name="no_downloaders_trusted">No downloaders have been trusted. Check your settings.</string>
<string name="remote_source_url_validation_failed">Couldn’t verify this URL. Make sure it’s correct and try again.</string>
```

The first two are unrelated (the API URL setting, and downloader plugins). The
third is about *fetching* the URL, not about cryptographic verification — see
below. The "accept the unverified-source prompt" step that older notes describe
belongs to Manager 1.x and does not exist here.

**What this means for us:** an unsigned bundle is added and used with no
warning and no extra taps. Signing is therefore optional. We still publish the
`.asc` and set `signature_download_url` when the signing secrets are present,
because the field is part of the schema the official API fills in and a future
Manager release may start checking it.

### Turning signing on

`.github/workflows/build.yml` signs only when both repository secrets exist
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `GPG_PRIVATE_KEY` | ASCII-armoured private key, i.e. the whole output of `gpg --armor --export-secret-keys <keyid>` |
| `GPG_PASSPHRASE` | that key's passphrase (set it to an empty secret if the key has none) |

With both set, the release gets `lobotagram.rvp.asc` (a detached armoured
signature over the stable-named rvp) and `patches.json` gains
`"signature_download_url": ".../releases/latest/download/lobotagram.rvp.asc"`.
With either missing, the step is skipped, the key is omitted from the JSON
entirely, and — per the above — nothing in Manager notices or complains.

## 4. How Manager checks for updates

By string equality on `version`, against the `version` it stored the last time
it downloaded:

```kotlin
// domain/sources/RemoteSource.kt
data class UpdateResult(val versionHash: String, val releasedAt: LocalDateTime)

suspend fun ActionContext.downloadLatest() = download(getLatestInfo())
suspend fun ActionContext.getUpdateInfo() =
    getLatestInfo().takeUnless { hasInstalled() && it.version == versionHash }

suspend fun ActionContext.update(): UpdateResult? = withContext(Dispatchers.IO) {
    getUpdateInfo()?.let { download(it) }
}
```

`versionHash` is persisted per source (`data/room/sources/Source.kt`:
`@ColumnInfo(name = "version") val versionHash: String?`) and is set from the
JSON's `version` on every successful download. It is compared, never ordered,
so it need not be semver — but it **must change on every release**, or Manager
will consider the bundle up to date forever. `created_at` is stored alongside
it (`releasedAt`) and only displayed.

`SourceManager.Update` runs this for every source with `autoUpdate` on (the
checkbox in the add dialog, default checked). On a metered connection it
degrades to check-only, reporting an update without downloading it:

```kotlin
val checkOnly = !force && !networkInfo.isUnmetered()
…
redownload  -> downloadLatest()
checkOnly   -> getUpdateInfo()?.let { info -> RemoteSource.UpdateResult(info.version, info.createdAt) }
else        -> update()
```

Because `download_url` is a stable `releases/latest/download/…` URL, no release
ever needs the JSON to be edited: bumping `version` in the JSON is what tells
Manager to re-download.

## 5. What a broken endpoint looks like (v2.7.0-dev)

v2.7.0-dev.11 added validation at add time. The Add button trims the URL,
fetches it, and refuses to create the source if it does not deserialize:

```kotlin
// domain/manager/SourceManager.kt
suspend fun validateRemoteUrl(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        http.request<ReVancedAsset> {
            url(url)
        }.getOrThrow()
    }.exceptionOrNull()?.toRemoteValidationMessage()
}

private fun Throwable.toRemoteValidationMessage() = when (asRemoteSourceException()) {
    // wtf is this? this data is not a bundle, at least something!
    is UnsupportedRemoteSourceException -> app.getString(R.string.remote_source_url_unsupported)

    // wtf is this? this is not a data at all and more like a webpage or something else!
    else -> app.getString(R.string.remote_source_url_validation_failed)
}
```

with

```kotlin
class UnsupportedRemoteSourceException(cause: Throwable? = null) : Exception(cause)

internal fun Throwable.asRemoteSourceException(): Throwable {
    if (this is UnsupportedRemoteSourceException) return this

    val hasSerializationFailure = generateSequence(this) { it.cause }
        .any { it is SerializationException }
    if (!hasSerializationFailure) return this

    return when (this) {
        is APIFailure -> UnsupportedRemoteSourceException(this)
        else -> UnsupportedRemoteSourceException(this)
    }
}
```

So a wrong URL is a **hard failure**, not a warning:

| What you paste | Result |
|---|---|
| the `patches.json` URL | source added |
| the `.rvp` URL directly | *"This URL is pointing to an unsupported source."* — the zip is not JSON, so a `SerializationException` surfaces as `UnsupportedRemoteSourceException` |
| valid JSON missing `description`, or `created_at` carrying a `Z` | same message, same reason |
| an HTML page, a 404 | *"Couldn’t verify this URL. Make sure it’s correct and try again."* |

On 2.6.1 there is no add-time validation; the source is created and then shows
a per-source update error instead.

## 6. What we publish

Every `v*` tag publishes, from `.github/workflows/build.yml`:

| Asset | Stable URL |
|---|---|
| `patches.json` | `https://github.com/lordbagel42/lobotagram/releases/latest/download/patches.json` |
| `lobotagram.rvp` | `https://github.com/lordbagel42/lobotagram/releases/latest/download/lobotagram.rvp` |
| `lobotagram-<version>.rvp` | versioned, for archives |
| `lobotagram.rvp.asc` | only when the GPG secrets are set |

`releases/latest/download/…` always resolves to the newest non-draft,
non-prerelease release, independent of the default branch, so the URL pasted
into Manager never has to change.

The release job also refuses to publish a mismatch: it checks the tag against
`version` in `gradle.properties` **and** against the `Version` attribute in the
built rvp's manifest, so the version Manager displays, the version in the JSON
and the tag can never drift apart.
