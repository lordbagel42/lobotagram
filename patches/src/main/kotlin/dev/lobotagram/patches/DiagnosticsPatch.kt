package dev.lobotagram.patches

import app.revanced.patcher.firstImmutableClassDefOrNull
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import dev.lobotagram.patches.clips.locateClipsAnchors
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
import dev.lobotagram.patches.ui.locateTabBarHooks

private const val LOG = "[lobotagram]"

/** Every class the extension DEX must contribute, network side and UI side. */
private val EXTENSION_CLASSES = listOf(
    "Lobo",
    "Gate",
    "FeedFilter",
    "Hiders",
    "HiddenTabSwipeSkipper",
    "ViewerLock",
    "ReelContext",
)

/** How many injection sites to print in full before summarising. */
private const val MAX_LISTED = 5

/**
 * Reconnaissance for every anchor the patches rely on, network side and UI side.
 * Changes nothing in the APK: it resolves each anchor and prints what it
 * resolved to, so on a new Instagram release one run tells you which anchor
 * drifted and what it drifted to.
 *
 * The locate functions are the *same* ones the real patches call
 * ([locateStartRequest], [locateSignatureChecks], [locateFeedItemParsers],
 * [locateTabBarHooks], [locateClipsAnchors]), so this can never report an
 * anchor the patches would resolve differently.
 *
 * Disabled by default; enable it by name:
 * `-e "Lobotagram diagnostics"`.
 */
@Suppress("unused")
val diagnosticsPatch = bytecodePatch(
    name = "Lobotagram diagnostics",
    description = "Prints what every lobotagram anchor resolved to — the Tigon request method and " +
        "its URI field, the signature-check methods, the feed-item deserialisers, the tab-bar " +
        "binder and activity hook, the clips-viewer source enum and its injection sites, the " +
        "autoscroll gate — and confirms the extension DEX was merged. Modifies nothing.",
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
                            "fallback (iget-object from the request parameter at method entry)"
                        } else {
                            "none — the patch would fail"
                        },
                )
            }

        println("$LOG === P2 tab bar ===")
        runCatching { locateTabBarHooks() }
            .onFailure { failures += "P2: ${it.message}" }
            .onSuccess { anchor ->
                println(
                    "$LOG binder candidates:      ${anchor.withSessionAccessor.size} discriminated, " +
                        "${anchor.viewGroupFromLookup.size} lookup-fed, " +
                        "${anchor.structural.size} structural",
                )
                anchor.structural.take(MAX_LISTED).forEach { println("$LOG   candidate: $it") }
                if (anchor.structural.size > MAX_LISTED) {
                    println("$LOG   ... and ${anchor.structural.size - MAX_LISTED} more")
                }
                println("$LOG binder chosen:          ${anchor.binder ?: "none — ambiguous"}")
                println("$LOG activity hook:          ${anchor.activityHookDescriptor ?: "none"}")

                // Either hook alone still hides the tab; only losing both is fatal.
                if (anchor.binder == null && anchor.activityHook == null) {
                    failures += "P2: neither the tab-bar binder nor an activity hook resolved"
                } else if (anchor.activityHook == null) {
                    failures += "P2: no activity hook resolved, so the Reels viewer lock will not " +
                        "run in windows that have no tab bar (ModalActivity, URL handlers)"
                }
            }

        println("$LOG === P3 clips viewer lock ===")
        runCatching { locateClipsAnchors() }
            .onFailure { failures += "P3: ${it.message}" }
            .onSuccess { anchor ->
                println(
                    "$LOG source enum:            " +
                        (anchor.sourceEnum ?: "not unique (${anchor.sourceEnums.size} matched: " +
                            "${anchor.sourceEnums.joinToString()})"),
                )
                println("$LOG enums holding clips_tab: ${anchor.enumsMentioningClipsTab.joinToString()}")
                println("$LOG injection sites:        ${anchor.entryPoints.size}")
                anchor.entryPoints.take(MAX_LISTED).forEach { entryPoint ->
                    println(
                        "$LOG   ${entryPoint.method.definingClass}->${entryPoint.method.name}" +
                            " (${entryPoint.method.parameterTypes.size} parameters, enum at " +
                            "#${entryPoint.parameterIndex})",
                    )
                }
                if (anchor.entryPoints.size > MAX_LISTED) {
                    println("$LOG   ... and ${anchor.entryPoints.size - MAX_LISTED} more")
                }
                println(
                    "$LOG autoscroll manager types: " +
                        anchor.autoscrollManagerTypes.ifEmpty { setOf("none") }.joinToString(),
                )
                println(
                    "$LOG autoscroll gate:        " +
                        when (anchor.autoscrollGates.size) {
                            0 -> "none — the gate would be skipped (not fatal)"
                            1 -> anchor.autoscrollGates.single()
                                .let { "${it.definingClass}->${it.name}" }

                            else -> "ambiguous (${anchor.autoscrollGates.size}: " +
                                anchor.autoscrollGates.joinToString {
                                    "${it.definingClass}->${it.name}"
                                } + ") — the gate would be skipped (not fatal)"
                        },
                )

                if (anchor.sourceEnum == null) {
                    failures += "P3: the clips-viewer source enum is not unique"
                } else if (anchor.entryPoints.isEmpty()) {
                    failures += "P3: no clips-package method takes the source enum"
                }
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
