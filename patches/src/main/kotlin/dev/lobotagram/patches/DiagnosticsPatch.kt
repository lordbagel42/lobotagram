package dev.lobotagram.patches

import app.revanced.patcher.firstImmutableClassDefOrNull
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import dev.lobotagram.patches.feed.CLIPS_NETEGO
import dev.lobotagram.patches.feed.FEED_UNIT_COMPANIONS
import dev.lobotagram.patches.feed.PARSE_FROM_JSON
import dev.lobotagram.patches.feed.findFeedItemHook
import dev.lobotagram.patches.feed.locateFeedItemParsers
import dev.lobotagram.patches.network.URI_TYPE
import dev.lobotagram.patches.network.locateStartRequest
import dev.lobotagram.patches.shared.EXTENSION
import dev.lobotagram.patches.shared.EXTENSION_PACKAGE
import dev.lobotagram.patches.shared.compatibleWithInstagram
import dev.lobotagram.patches.signature.KEY_HASH_STRING
import dev.lobotagram.patches.signature.locateSignatureChecks

private const val LOG = "[lobotagram]"

private val EXTENSION_CLASSES = listOf("Lobo", "Gate", "FeedFilter")

/**
 * Reconnaissance for every anchor the network-side patches rely on. Changes
 * nothing in the APK: it resolves each anchor and prints what it resolved to, so
 * on a new Instagram release one run tells you which anchor drifted and what it
 * drifted to.
 *
 * Disabled by default; enable it by name:
 * `-e "Lobotagram diagnostics"`.
 */
@Suppress("unused")
val diagnosticsPatch = bytecodePatch(
    name = "Lobotagram diagnostics",
    description = "Prints what every lobotagram anchor resolved to — the Tigon request method and " +
        "its URI field, the signature-check methods, the feed-item deserialisers — and confirms " +
        "the extension DEX was merged. Modifies nothing.",
    use = false,
) {
    compatibleWithInstagram()
    extendWith(EXTENSION)

    apply {
        val failures = mutableListOf<String>()

        println("$LOG === extension ===")
        EXTENSION_CLASSES.forEach { simpleName ->
            val type = EXTENSION_PACKAGE + simpleName + ";"
            val merged = firstImmutableClassDefOrNull(type) != null
            println("$LOG $type merged: $merged")
            if (!merged) failures += "the extension class $type is missing after extendWith"
        }

        println("$LOG === P1 network gate ===")
        runCatching { locateStartRequest() }
            .onFailure { failures += "P1: ${it.message}" }
            .onSuccess { anchor ->
                println("$LOG class:                  ${anchor.definingClass}")
                println("$LOG method:                 ${anchor.methodName}")
                println("$LOG parameter types:        ${anchor.parameterTypes.joinToString(", ")}")
                println("$LOG instruction count:      ${anchor.instructionCount}")
                println("$LOG registers:              ${anchor.registerCount} " +
                    "(${anchor.parameterRegisterCount} of them parameters)")
                println("$LOG first iget-object of $URI_TYPE at index: ${anchor.uriLoadIndex ?: "not found"}")
                println("$LOG   into register:        ${anchor.uriRegister ?: "n/a"}")
                println("$LOG request type:           ${anchor.requestType}")
                println("$LOG   $URI_TYPE fields:  ${anchor.uriFieldNames.ifEmpty { listOf("none") }.joinToString()}")
                println(
                    "$LOG strategy that would be used: " +
                        if (anchor.uriLoadIndex != null) {
                            "primary (inject after the iget-object)"
                        } else if (anchor.uriFieldNames.size == 1) {
                            "fallback (iget-object from p1 at method entry)"
                        } else {
                            "none — the patch would fail"
                        },
                )
            }

        println("$LOG === P4 signature bypass ===")
        runCatching { locateSignatureChecks() }
            .onFailure { failures += "P4: ${it.message}" }
            .onSuccess { anchor ->
                println("$LOG \"$KEY_HASH_STRING\" reached: ${anchor.stringClasses.joinToString()}")
                println("$LOG key-hash type:          ${anchor.keyHashType}")
                println(
                    "$LOG (KeyHash)Z:             ${anchor.membershipCheck.definingClass}" +
                        "->${anchor.membershipCheck.name}",
                )
                println(
                    "$LOG (KeyHash,KeyHash,Z)Z:   ${anchor.scopeCheck.definingClass}" +
                        "->${anchor.scopeCheck.name}",
                )
            }

        println("$LOG === P5 feed item filter ===")
        val parsers = locateFeedItemParsers()
        println("$LOG token \"$CLIPS_NETEGO\" + one of ${FEED_UNIT_COMPANIONS.joinToString()} " +
            "+ name containing \"$PARSE_FROM_JSON\"")
        println("$LOG deserialisers found:    ${parsers.size}")
        if (parsers.isEmpty()) failures += "P5: no feed-item deserialiser matched"
        parsers.forEach { parser ->
            val hook = findFeedItemHook(parser)
            println("$LOG   ${parser.definingClass}->${parser.name}${parser.parameterTypes.joinToString("", "(", ")")}")
            println(
                "$LOG     hook: " +
                    if (hook == null) {
                        "none — no String.hashCode() dispatch found"
                    } else {
                        "token in v${hook.keyRegister}, move-result-object at index " +
                            "${hook.keyLoadIndex}, String.hashCode() at index ${hook.hashCodeIndex}"
                    },
            )
            if (hook == null) failures += "P5: ${parser.definingClass}->${parser.name} has no hook point"
        }

        println("$LOG === summary ===")
        println("$LOG anchors that failed:    ${failures.size}")

        if (failures.isNotEmpty()) {
            throw PatchException(
                "Some lobotagram anchors no longer resolve in this APK. Fix these, then re-run " +
                    "the patches:\n" + failures.joinToString("\n") { "  - $it" },
            )
        }
    }
}
