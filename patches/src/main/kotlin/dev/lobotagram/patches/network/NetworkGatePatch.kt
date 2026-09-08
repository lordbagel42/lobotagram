package dev.lobotagram.patches.network

import app.revanced.com.android.tools.smali.dexlib2.iface.value.MutableBooleanEncodedValue
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableField
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.custom
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.firstClassDefOrNull
import app.revanced.patcher.firstImmutableClassDefOrNull
import app.revanced.patcher.firstImmutableMethodDeclarativelyOrNull
import app.revanced.patcher.firstMethod
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.lobotagram.patches.shared.EXTENSION
import dev.lobotagram.patches.shared.EXTENSION_PACKAGE
import dev.lobotagram.patches.shared.compatibleWithInstagram

/**
 * The one class the whole plan hangs off: every Instagram HTTP request passes
 * through `TigonServiceLayer.startRequest`. Neither the class nor the method is
 * obfuscated and both have been stable for years, so they are safe to hardcode.
 * The three parameter types are obfuscated and are therefore never named — only
 * the arity is checked.
 */
internal const val TIGON_SERVICE_LAYER = "Lcom/instagram/api/tigon/TigonServiceLayer;"

internal const val START_REQUEST = "startRequest"

/** The type of the request URI that the gate inspects. */
internal const val URI_TYPE = "Ljava/net/URI;"

private const val GATE_CLASS = EXTENSION_PACKAGE + "Gate;"
private const val THROW_IF_BLOCKED = "throwIfBlocked($URI_TYPE)V"

private const val BLOCK_EXPLORE_FIELD = "blockExplore"
private const val BLOCK_NUDGES_FIELD = "blockNudges"

/** What [locateStartRequest] found, so the diagnostics patch can print it. */
internal class StartRequestAnchor(
    /** The immutable method, for handing to `firstMethod` when it is time to patch. */
    val method: Method,
    val parameterTypes: List<String>,
    val instructionCount: Int,
    val registerCount: Int,
    val parameterRegisterCount: Int,
    /** Index of the first `iget-object` of type [URI_TYPE], or null if there is none. */
    val uriLoadIndex: Int?,
    /** Destination register of that `iget-object`. */
    val uriRegister: Int?,
    /** The type of the first parameter: Instagram's request object. */
    val requestType: String,
    /** True when the method is static, i.e. its first parameter is `p0`. */
    val isStatic: Boolean,
    /** Every [URI_TYPE] field declared on [requestType]. Exactly one is expected. */
    val uriFieldNames: List<String>,
) {
    val definingClass get() = method.definingClass
    val methodName get() = method.name

    /** Smali name of the register holding the request object at method entry. */
    val requestParameter get() = if (isStatic) "p0" else "p1"
}

/** Registers taken by `this` plus the parameters; every lower register is scratch. */
private fun Method.parameterRegisterCount(): Int {
    val parameters = parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return parameters + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
}

/** The first `iget-object` whose field type is [URI_TYPE], with its index. */
private fun Iterable<Instruction>.firstUriLoadOrNull() =
    withIndex().firstOrNull { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT && instruction.fieldReference?.type == URI_TYPE
    }

/**
 * Resolves `TigonServiceLayer.startRequest` and everything the injection needs,
 * without modifying anything. Shared with the diagnostics patch.
 */
internal fun BytecodePatchContext.locateStartRequest(): StartRequestAnchor {
    val classDef = firstImmutableClassDefOrNull(TIGON_SERVICE_LAYER)
        ?: throw PatchException(
            "Could not find $TIGON_SERVICE_LAYER. Either the APK is not Instagram, or Meta moved " +
                "its network layer. Run tools/recon.sh on the APK, find the class that builds a " +
                "java.net.URI per request, and update TIGON_SERVICE_LAYER in " +
                "patches/network/NetworkGatePatch.kt.",
        )

    val method = classDef.firstImmutableMethodDeclarativelyOrNull {
        name(START_REQUEST)
        // The parameter types are obfuscated (LX/... in every release), so only
        // the arity is asserted: request, callbacks, listener.
        custom { parameterTypes.size == 3 }
    } ?: throw PatchException(
        "Found $TIGON_SERVICE_LAYER but no 3-parameter \"$START_REQUEST\" method in it. Its " +
            "methods are: " +
            classDef.methods.joinToString { "${it.name}(${it.parameterTypes.joinToString("")})" } +
            ". Pick the new request entry point and update START_REQUEST in " +
            "patches/network/NetworkGatePatch.kt.",
    )

    val instructions = method.instructionsOrNull
        ?: throw PatchException(
            "$TIGON_SERVICE_LAYER->$START_REQUEST has no implementation, so nothing can be " +
                "injected into it. The network gate needs a different anchor; look for the method " +
                "that reads the request's java.net.URI field.",
        )

    val uriLoad = instructions.firstUriLoadOrNull()
    val requestType = method.parameterTypes.first().toString()
    val uriFieldNames = firstImmutableClassDefOrNull(requestType)
        ?.fields
        ?.filter { it.type == URI_TYPE }
        ?.map { it.name }
        .orEmpty()

    return StartRequestAnchor(
        method = method,
        parameterTypes = method.parameterTypes.map { it.toString() },
        instructionCount = instructions.count(),
        registerCount = method.implementation?.registerCount ?: 0,
        parameterRegisterCount = method.parameterRegisterCount(),
        uriLoadIndex = uriLoad?.index,
        uriRegister = (uriLoad?.value as? OneRegisterInstruction)?.registerA,
        requestType = requestType,
        isStatic = AccessFlags.STATIC.isSet(method.accessFlags),
        uriFieldNames = uriFieldNames,
    )
}

/**
 * P1, the primary control: refuse every request that only exists to hand out
 * reels, while leaving DMs, Stories and posting alone.
 *
 * The hook goes right after the request URI is loaded, which in this method is
 * inside its `IOException` try block, so `Gate.throwIfBlocked` throwing is
 * handled by Instagram's own `failRequest` path and the blocked surface just
 * stays empty. The technique is FeurStagram's (`NetworkBlockPatch`, GPLv3); the
 * pagination rule that starves the *next* reel while letting the first one play
 * is InstaEclipse's (`IGNetworkInterceptor`, GPLv3).
 */
@Suppress("unused")
val networkGatePatch = bytecodePatch(
    name = "Block Reels surfaces",
    description = "Refuses the network requests behind the Reels tab, Reels discovery, Blend, " +
        "the reels injected into the feed, and the request that would fetch the next reel after " +
        "one opened from a DM. Direct messages, Stories and posting are never touched.",
) {
    compatibleWithInstagram()
    extendWith(EXTENSION)

    val blockExplore by booleanOption(
        name = BLOCK_EXPLORE_FIELD,
        default = true,
        description = "Also block Explore's topical feed (/discover/topical_explore), which is " +
            "mostly reels.",
    )

    val blockNudges by booleanOption(
        name = BLOCK_NUDGES_FIELD,
        default = true,
        description = "Also block the quick-promotion nudge fetch (/qp/batch_fetch/), which is " +
            "what asks you to try Reels.",
    )

    apply {
        val anchor = locateStartRequest()
        val method = firstMethod(anchor.method)

        injectGate(method, anchor)

        // The extension ships with both options on; only a "false" needs writing.
        setGateFlag(BLOCK_EXPLORE_FIELD, blockExplore != false)
        setGateFlag(BLOCK_NUDGES_FIELD, blockNudges != false)
    }
}

/**
 * Injects the call to `Gate.throwIfBlocked`.
 *
 * Strategy 1 (the whole point of the anchor): right after the `iget-object` that
 * loads the URI, reusing its register. That instruction sits inside the method's
 * `IOException` try block, so the throw is caught by Instagram.
 *
 * Strategy 2 (fallback, if that `iget-object` is gone): read the URI field off
 * the first parameter at method entry (`p1` on an instance method, `p0` on a
 * static one). `move-object/from16` first, so the injection works no matter how
 * high the parameter registers sit, then `iget-object` into scratch register 0,
 * which no instruction has defined yet at method entry.
 */
private fun injectGate(
    method: MutableMethod,
    anchor: StartRequestAnchor,
) {
    // Recomputed on the mutable method rather than trusting the immutable index.
    val uriLoad = method.instructions.firstUriLoadOrNull()
    if (uriLoad != null) {
        val register = (uriLoad.value as? OneRegisterInstruction)?.registerA
            ?: throw PatchException(
                "The iget-object of $URI_TYPE at index ${uriLoad.index} in " +
                    "$TIGON_SERVICE_LAYER->$START_REQUEST has no destination register, which " +
                    "cannot happen for a well-formed iget-object. Dump the method with baksmali " +
                    "and check what the instruction really is.",
            )

        method.addInstructions(
            uriLoad.index + 1,
            "invoke-static/range { v$register .. v$register }, $GATE_CLASS->$THROW_IF_BLOCKED",
        )
        return
    }

    val fieldName = anchor.uriFieldNames.singleOrNull()
        ?: throw PatchException(
            "$TIGON_SERVICE_LAYER->$START_REQUEST no longer contains an iget-object of $URI_TYPE, " +
                "and the fallback cannot be used either: the request type ${anchor.requestType} " +
                "declares ${anchor.uriFieldNames.size} fields of type $URI_TYPE " +
                "(${anchor.uriFieldNames.ifEmpty { listOf("none") }.joinToString()}), not exactly " +
                "one. Baksmali $TIGON_SERVICE_LAYER, find where the request URI is read, and " +
                "either re-point the primary strategy at it or narrow the fallback to the right " +
                "field. Run the \"Lobotagram diagnostics\" patch to see the current shape.",
        )

    if (anchor.registerCount <= anchor.parameterRegisterCount) {
        throw PatchException(
            "$TIGON_SERVICE_LAYER->$START_REQUEST has ${anchor.registerCount} registers and " +
                "${anchor.parameterRegisterCount} of them are parameters, so there is no scratch " +
                "register to load the URI into. Growing the register count would renumber the " +
                "parameter registers and corrupt the method body, so pick a different injection " +
                "point (a call site of this method, or the method that reads " +
                "${anchor.requestType}->$fieldName).",
        )
    }

    method.addInstructions(
        0,
        """
            move-object/from16 v0, ${anchor.requestParameter}
            iget-object v0, v0, ${anchor.requestType}->$fieldName:$URI_TYPE
            invoke-static/range { v0 .. v0 }, $GATE_CLASS->$THROW_IF_BLOCKED
        """.trimIndent(),
    )
}

/**
 * Writes a patch option into the extension.
 *
 * D8 compiles `public static boolean x = true` into the field's dex *initial
 * value* rather than a `sput-boolean` in `<clinit>` (verified on the extension
 * DEX this project builds), so the option is applied by editing that encoded
 * value on the mutable class def. The field is deliberately not `final` in
 * Gate.java, because a final one would let a dexer fold the constant into every
 * read and the rewrite would have no effect.
 *
 * If a future toolchain moves the initialisation into `<clinit>` instead, this
 * throws rather than silently doing nothing.
 */
private fun BytecodePatchContext.setGateFlag(
    fieldName: String,
    value: Boolean,
) {
    val gate = firstClassDefOrNull(GATE_CLASS)
        ?: throw PatchException(
            "The extension DEX was not merged: $GATE_CLASS is missing after extendWith. Rebuild " +
                "the rvp with ./gradlew build and check that extensions/lobotagram.rve is inside " +
                "it.",
        )

    val assignedInClinit = gate.methods
        .firstOrNull { it.name == "<clinit>" }
        ?.instructionsOrNull
        ?.any { it.opcode == Opcode.SPUT_BOOLEAN && it.fieldReference?.name == fieldName } == true

    if (assignedInClinit) {
        throw PatchException(
            "$GATE_CLASS->$fieldName is assigned in <clinit>, so rewriting its dex initial value " +
                "would be overwritten at class-init time. The extension's dexer changed; either " +
                "make the patch rewrite that sput-boolean instead, or switch the options over to " +
                "a Gate.configure(ZZ)V call as described in docs/patches/network.md.",
        )
    }

    val field = gate.fields.filterIsInstance<MutableField>().firstOrNull { it.name == fieldName }
        ?: throw PatchException(
            "$GATE_CLASS has no field \"$fieldName\". Its fields are: " +
                gate.fields.joinToString { "${it.name}:${it.type}" } +
                ". Rename the option or the field so they match.",
        )

    val initialValue = field.initialValue as? MutableBooleanEncodedValue
        ?: throw PatchException(
            "$GATE_CLASS->$fieldName has no boolean initial value in the extension DEX (it is " +
                "${field.initialValue}). Declare it as `public static boolean $fieldName = true;` " +
                "in Gate.java so the dexer emits an initial value the patch can rewrite.",
        )

    initialValue.value = value
}
