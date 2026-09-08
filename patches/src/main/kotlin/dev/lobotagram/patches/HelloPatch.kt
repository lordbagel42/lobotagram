package dev.lobotagram.patches

import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.firstImmutableClassDefOrNull
import app.revanced.patcher.firstImmutableMethodDeclarativelyOrNull
import app.revanced.patcher.name
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import dev.lobotagram.patches.shared.EXTENSION
import dev.lobotagram.patches.shared.EXTENSION_PACKAGE
import dev.lobotagram.patches.shared.compatibleWithInstagram

/**
 * The one class the whole plan hangs off: every Instagram HTTP request passes
 * through `TigonServiceLayer.startRequest`, and the name has been stable for
 * years. This is not obfuscated, so it is safe to hardcode.
 */
private const val TIGON_SERVICE_LAYER = "Lcom/instagram/api/tigon/TigonServiceLayer;"
private const val START_REQUEST = "startRequest"

/** The `java.net.URI` the network gate will eventually inspect. */
private const val URI_TYPE = "Ljava/net/URI;"

private const val HELLO_EXTENSION_CLASS = EXTENSION_PACKAGE + "Hello;"

private const val LOG = "[lobotagram]"

/**
 * Toolchain smoke test and reconnaissance for the network gate (P1).
 *
 * Changes nothing in the APK. It resolves `TigonServiceLayer.startRequest`,
 * prints the shape the network gate has to inject into, and confirms the
 * extension DEX was merged. Disabled by default; enable it with
 * `-e "Lobotagram diagnostics"`.
 */
val helloPatch = bytecodePatch(
    name = "Lobotagram diagnostics",
    description = "Prints the shape of TigonServiceLayer.startRequest and confirms the extension loaded. " +
        "Modifies nothing.",
    use = false,
) {
    compatibleWithInstagram()
    extendWith(EXTENSION)

    apply {
        val classDef = firstImmutableClassDefOrNull(TIGON_SERVICE_LAYER)
            ?: throw PatchException(
                "Could not find $TIGON_SERVICE_LAYER. Either the APK is not Instagram, or Meta moved " +
                    "its network layer. Run tools/recon.sh on the APK and update docs/02-instagram-apk.md.",
            )

        val method = classDef.firstImmutableMethodDeclarativelyOrNull { name(START_REQUEST) }
            ?: throw PatchException(
                "Found $TIGON_SERVICE_LAYER but it has no \"$START_REQUEST\" method. Its methods are: " +
                    classDef.methods.joinToString { it.name } +
                    ". Pick the new request entry point and update TIGON_SERVICE_LAYER/START_REQUEST.",
            )

        val instructions = method.instructionsOrNull
            ?: throw PatchException(
                "$TIGON_SERVICE_LAYER->$START_REQUEST has no implementation, so nothing can be injected " +
                    "into it. The network gate needs a different anchor.",
            )

        val uriIndex = instructions.withIndex().firstOrNull { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT && instruction.fieldReference?.type == URI_TYPE
        }?.index

        val extensionLoaded = firstImmutableClassDefOrNull(HELLO_EXTENSION_CLASS) != null

        println("$LOG class:            ${method.definingClass}")
        println("$LOG method:           ${method.name}")
        println("$LOG return type:      ${method.returnType}")
        println("$LOG parameter types:  ${method.parameterTypes.joinToString(", ")}")
        println("$LOG instruction count: ${instructions.count()}")
        println("$LOG first iget-object of $URI_TYPE at index: ${uriIndex ?: "not found"}")
        println("$LOG extension $HELLO_EXTENSION_CLASS merged: $extensionLoaded")

        if (!extensionLoaded) {
            throw PatchException(
                "The extension DEX was not merged: $HELLO_EXTENSION_CLASS is missing after extendWith. " +
                    "Rebuild the rvp with ./gradlew build and check that extensions/lobotagram.rve is inside it.",
            )
        }

        if (uriIndex == null) {
            throw PatchException(
                "No iget-object of $URI_TYPE in $START_REQUEST. The network gate cannot find the request " +
                    "URI to inspect. Dump the method with baksmali and pick a new injection point.",
            )
        }
    }
}
