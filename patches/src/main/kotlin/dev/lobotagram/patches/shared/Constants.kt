package dev.lobotagram.patches.shared

import app.revanced.patcher.patch.BytecodePatchBuilder

/** The package name of the Instagram app. Never changes. */
const val INSTAGRAM_PACKAGE = "com.instagram.android"

/** The extension DEX, as it is named inside the rvp. Passed to `extendWith`. */
const val EXTENSION = "extensions/lobotagram.rve"

/** Type descriptor prefix of the extension classes, for building smali references. */
const val EXTENSION_PACKAGE = "Ldev/lobotagram/extension/"

/**
 * The Instagram releases these patches are tested against.
 *
 * One pinned list for every patch: bumping a version is a one-line change here.
 * ReVanced CLI refuses an APK outside this list unless `-f` is passed.
 */
val INSTAGRAM_VERSIONS = setOf(
    "435.0.0.37.76",
)

/** Declares compatibility with the pinned Instagram releases. */
fun BytecodePatchBuilder.compatibleWithInstagram() =
    compatibleWith(INSTAGRAM_PACKAGE(*INSTAGRAM_VERSIONS.toTypedArray()))
