package dev.lobotagram.patches.signature

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import dev.lobotagram.patches.shared.compatibleWithInstagram

/**
 * Instagram wraps a signing certificate's SHA-256 into a 43-character "key hash"
 * object and keeps Meta's trusted key hashes in a static allow-list. Two static
 * membership checks read that list:
 *
 *  * `(KeyHash)Z` — is this key hash trusted?
 *  * `(KeyHash, KeyHash, Z)Z` — is this key hash trusted for that app's scope?
 *
 * A re-signed APK produces a key hash that is not in the list, both checks fail,
 * and `com.facebook.secure.deeplink` silently drops the navigation — so a reel
 * shared into a DM opens the home feed instead of the reel. Forcing both checks
 * to return true restores deep links.
 *
 * Adapted from FeurStagram's `SignatureCheckBypassPatch` (GPLv3,
 * https://github.com/feurstagram).
 */
internal const val KEY_HASH_STRING = "Invalid SHA256 key hash"

/** What [locateSignatureChecks] found, so the diagnostics patch can print it. */
internal class SignatureAnchor(
    /** The key-hash type, reached from [KEY_HASH_STRING] and never named directly. */
    val keyHashType: String,
    /** Every class holding a method that contains [KEY_HASH_STRING]. */
    val stringClasses: List<String>,
    /** The unique static `(KeyHash)Z`. */
    val membershipCheck: Method,
    /** The unique static `(KeyHash, KeyHash, Z)Z`. */
    val scopeCheck: Method,
)

private fun Method.descriptor() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Finds the key-hash type by string, then the two trust checks by their shape.
 * No obfuscated name appears anywhere: the type is whatever the string leads to
 * and the methods are whatever takes that type.
 */
internal fun BytecodePatchContext.locateSignatureChecks(): SignatureAnchor {
    // The string cache is keyed by the exact literal, so match on the prefix:
    // the sentence has a tail ("- should be 256-bit.") that Meta may reword.
    val stringMethods = classDefs.methodsByString
        .filterKeys { it.contains(KEY_HASH_STRING) }
        .values
        .flatten()

    if (stringMethods.isEmpty()) {
        throw PatchException(
            "No method in the APK contains a string containing \"$KEY_HASH_STRING\", so the " +
                "key-hash type cannot be located. Search the dex files for the current wording of " +
                "Instagram's key-hash length check (grep -a on classes*.dex works) and update " +
                "KEY_HASH_STRING in patches/signature/SignatureBypassPatch.kt.",
        )
    }

    // Two classes carry that string: the key-hash type itself (its constructor
    // validates the length) and the builder that constructs one. Which is which
    // is decided by the shape of the methods that consume the type, not by name.
    val candidateTypes = stringMethods.map { it.definingClass }.distinct()

    val membershipChecks = mutableMapOf<String, MutableList<Method>>()
    val scopeChecks = mutableMapOf<String, MutableList<Method>>()

    classDefs.forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.returnType != "Z") return@forEach
            if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@forEach

            val parameters = method.parameterTypes.map { it.toString() }
            when (parameters.size) {
                1 -> if (parameters[0] in candidateTypes) {
                    membershipChecks.getOrPut(parameters[0], ::mutableListOf) += method
                }

                3 -> if (parameters[0] in candidateTypes &&
                    parameters[1] == parameters[0] &&
                    parameters[2] == "Z"
                ) {
                    scopeChecks.getOrPut(parameters[0], ::mutableListOf) += method
                }
            }
        }
    }

    val resolved = candidateTypes.filter { type ->
        membershipChecks[type]?.size == 1 && scopeChecks[type]?.size == 1
    }

    if (resolved.size != 1) {
        throw PatchException(
            buildString {
                append(
                    "Could not pin down Instagram's key-hash trust checks. Exactly one candidate " +
                        "type must have exactly one static (KeyHash)Z and exactly one static " +
                        "(KeyHash,KeyHash,Z)Z; ${resolved.size} did. Candidates reached from " +
                        "\"$KEY_HASH_STRING\":",
                )
                candidateTypes.forEach { type ->
                    append("\n  $type")
                    append("\n    (KeyHash)Z: ")
                    append(
                        membershipChecks[type]
                            ?.joinToString { it.descriptor() }
                            ?: "none",
                    )
                    append("\n    (KeyHash,KeyHash,Z)Z: ")
                    append(
                        scopeChecks[type]
                            ?.joinToString { it.descriptor() }
                            ?: "none",
                    )
                }
                append(
                    "\nPick the right pair by hand and narrow the filter in " +
                        "patches/signature/SignatureBypassPatch.kt.",
                )
            },
        )
    }

    val keyHashType = resolved.single()

    return SignatureAnchor(
        keyHashType = keyHashType,
        stringClasses = candidateTypes,
        membershipCheck = membershipChecks.getValue(keyHashType).single(),
        scopeCheck = scopeChecks.getValue(keyHashType).single(),
    )
}

/** Replaces a whole method body with `return true`, try blocks and all. */
private fun MutableMethod.returnTrue() {
    // A fresh implementation rather than removing the instructions of the old
    // one: the scope check has a try/catch, and dropping the instructions out
    // from under a try block leaves it pointing at nothing.
    implementation = MutableMethodImplementation(maxOf(1, implementation?.registerCount ?: 1))
    addInstructions(0, "const/4 v0, 0x1\nreturn v0")
}

/**
 * P4: make Instagram trust its own re-signed build, so a reel tapped in a DM
 * opens the reel.
 */
@Suppress("unused")
val signatureBypassPatch = bytecodePatch(
    name = "Bypass signature check",
    description = "Forces Instagram's signing-certificate trust checks to pass. Without this a " +
        "re-signed build refuses its own deep links, so tapping a reel shared in a DM opens the " +
        "home feed instead of the reel.",
) {
    compatibleWithInstagram()

    apply {
        val anchor = locateSignatureChecks()

        firstMethod(anchor.membershipCheck).returnTrue()
        firstMethod(anchor.scopeCheck).returnTrue()
    }
}
