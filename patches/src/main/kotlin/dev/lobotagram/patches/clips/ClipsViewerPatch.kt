package dev.lobotagram.patches.clips

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.string
import app.revanced.patcher.firstMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.lobotagram.patches.shared.EXTENSION
import dev.lobotagram.patches.shared.EXTENSION_PACKAGE
import dev.lobotagram.patches.shared.compatibleWithInstagram
import dev.lobotagram.patches.ui.tabBarPatch

private const val REEL_CONTEXT_CLASS = EXTENSION_PACKAGE + "ReelContext;"
private const val ON_VIEWER_SOURCE = "onViewerSource"
private const val ENUM = "Ljava/lang/Enum;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Two wire names that every clips-viewer source enum has carried for years:
 * the Reels tab and Direct. Both are values in the enum's `<clinit>`, so they
 * identify the enum without naming it.
 */
private val SOURCE_ENUM_STRINGS = listOf("clips_tab", "direct")

/**
 * Packages whose classes may be injected into. Both are non-obfuscated:
 * Instagram keeps `com.instagram.clips.*` and `instagram.features.clips.*`
 * readable while renaming everything it moves into `X`.
 */
private val CLIPS_PACKAGES = listOf("Lcom/instagram/clips/", "Linstagram/features/clips/")

/** Package of the Reels autoscroll controller. Non-obfuscated. */
private const val AUTOSCROLL_PACKAGE = "Linstagram/features/clips/viewer/controller/autoscroll/"

/**
 * Upper bound on entry-point hooks. One is expected (the viewer config's
 * constructor); the cap exists so that a build which suddenly threads the
 * source enum through fifty clips methods does not get fifty injections.
 */
private const val MAX_ENTRY_POINT_HOOKS = 10

private const val LOG = "[lobotagram]"

/** A method to hook, and which of its parameters is the source enum. */
private class EntryPoint(val method: Method, val parameterIndex: Int) {
    val signature =
        "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString(",")})"

    override fun toString() = "$signature [parameter #$parameterIndex]"
}

/** Number of registers a method's parameters (plus `this`) occupy. */
private fun Method.inputRegisters(): Int {
    var count = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    parameterTypes.forEach { count += if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return count
}

/** The register a parameter lives in, given the method's total register count. */
private fun Method.registerOfParameter(index: Int, registerCount: Int): Int {
    var offset = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    parameterTypes.forEachIndexed { i, type ->
        if (i == index) return registerCount - inputRegisters() + offset
        offset += if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    throw PatchException("$this has no parameter #$index")
}

/** Every string constant a method loads. */
private fun Method.stringConstants(): Set<String> =
    instructionsOrNull?.mapNotNull { it.string }?.toSet() ?: emptySet()

/**
 * A one-argument static call, built as `invoke-static/range` rather than smali
 * text so it is valid for any register number. The clips-viewer config
 * constructor takes 223 parameters and its source enum sits in v22, far above
 * what the compact `invoke-static {vN}` form can address.
 */
private fun staticCall(
    target: String,
    name: String,
    parameter: String,
    register: Int,
) = BuilderInstruction3rc(
    Opcode.INVOKE_STATIC_RANGE,
    register,
    1,
    ImmutableMethodReference(target, name, listOf(parameter), "V"),
)

/**
 * Locks the Reels viewer to the single reel it opened on (P3 of
 * `docs/04-patch-plan.md`).
 *
 * Three parts, in decreasing order of certainty:
 *
 * - **The pager lock** needs no fingerprint at all. It runs inside
 *   `ViewerLock`, off the global-layout observer that the "Hide Reels tab"
 *   patch installs, which is why that patch is a dependency here.
 * - **Entry-point tracking** is bytecode. The clips-viewer source enum is
 *   found by its own wire names (`clips_tab`, `direct`) rather than by class
 *   name, and every clips-package method that takes it is hooked with
 *   `ReelContext.onViewerSource(Enum)`. In 435.0.0.37.76 that is exactly one
 *   method: `ClipsViewerConfig.<init>`, which every path that opens a reel
 *   builds. Nothing acts on the source in v1 — every viewer is single-reel in
 *   a lobotomized build — but the hook is here so a later "allow scrolling
 *   from the Reels tab" toggle needs no new anchor, and so the log says which
 *   entry point a locked viewer came from.
 * - **Autoscroll** is best-effort. The autoscroll controller's own package
 *   name survives minification, so its session gate can be found and made to
 *   return false. If that gate cannot be identified uniquely the patch says so
 *   and carries on: the pager lock already stops the viewer moving, and the
 *   network gate already denies the next reel.
 */
@Suppress("unused")
val clipsViewerPatch = bytecodePatch(
    name = "Lock Reels viewer",
    description = "A reel opened from a DM, a profile or a link plays, but the viewer cannot be " +
        "swiped to another reel and does not auto-advance.",
) {
    compatibleWithInstagram()
    extendWith(EXTENSION)

    // The lock rides on the global-layout observer that patch installs.
    dependsOn(tabBarPatch)

    apply {
        val classes = classDefs.toList()

        // --- (b) entry-point tracking -------------------------------------
        val sourceEnums = classes.filter { classDef ->
            classDef.superclass == ENUM &&
                !classDef.type.startsWith("LX/") &&
                classDef.methods.any { method ->
                    method.name == "<clinit>" &&
                        method.stringConstants().containsAll(SOURCE_ENUM_STRINGS)
                }
        }

        val sourceEnum = sourceEnums.singleOrNull()?.type ?: throw PatchException(
            "Expected exactly one non-obfuscated enum whose <clinit> holds " +
                SOURCE_ENUM_STRINGS.joinToString(" and ") { "\"$it\"" } +
                ", found ${sourceEnums.size}: ${sourceEnums.joinToString { it.type }}. " +
                "Every enum holding \"clips_tab\" in this APK is: " +
                classes.filter { it.superclass == ENUM }
                    .filter { cd -> cd.methods.any { "clips_tab" in it.stringConstants() } }
                    .joinToString { it.type } +
                ". Pick the clips-viewer source enum and update SOURCE_ENUM_STRINGS.",
        )

        val entryPoints = classes
            .filter { classDef -> CLIPS_PACKAGES.any { classDef.type.startsWith(it) } }
            .flatMap { classDef ->
                classDef.methods.mapNotNull { method ->
                    if (method.implementation == null) return@mapNotNull null
                    val index = method.parameterTypes.indexOfFirst { it.toString() == sourceEnum }
                    if (index < 0) null else EntryPoint(method, index)
                }
            }
            // Deterministic order, so the same APK always gets the same hooks.
            .sortedBy { it.signature }

        if (entryPoints.isEmpty()) {
            throw PatchException(
                "Found the clips-viewer source enum $sourceEnum but no method in " +
                    CLIPS_PACKAGES.joinToString(" or ") +
                    " takes it as a parameter, so there is nowhere to record the entry point. " +
                    "Widen CLIPS_PACKAGES to the package that now holds the viewer config, or " +
                    "hook the enum's own factory methods instead.",
            )
        }

        val hooks = entryPoints.take(MAX_ENTRY_POINT_HOOKS)
        if (entryPoints.size > MAX_ENTRY_POINT_HOOKS) {
            println(
                "$LOG ${entryPoints.size} clips methods take $sourceEnum; hooking the first " +
                    "$MAX_ENTRY_POINT_HOOKS in lexicographic order",
            )
        }

        println("$LOG clips-viewer source enum: $sourceEnum")

        hooks.forEach { entryPoint ->
            val method = firstMethodOrNull(entryPoint.method)
                ?: throw PatchException(
                    "Matched ${entryPoint.signature} but could not open it for editing. This is a " +
                        "patcher-side failure, not a fingerprint drift.",
                )
            val registerCount = method.implementation?.registerCount
                ?: throw PatchException("${entryPoint.signature} has no implementation to inject into.")

            val register = entryPoint.method.registerOfParameter(entryPoint.parameterIndex, registerCount)
            method.addInstructions(
                0,
                listOf(staticCall(REEL_CONTEXT_CLASS, ON_VIEWER_SOURCE, ENUM, register)),
            )

            println("$LOG hooked $entryPoint -> $REEL_CONTEXT_CLASS->$ON_VIEWER_SOURCE($ENUM)V at v$register")
        }

        // --- (c) autoscroll ------------------------------------------------
        neutralizeAutoscroll(classes)
    }
}

/**
 * Makes the Reels autoscroll gate return false, if it can be identified.
 *
 * The chain is all name-anchored: the autoscroll package
 * (`instagram.features.clips.viewer.controller.autoscroll`) survives
 * minification and holds one class, the session manager's activity-lifecycle
 * callback. That callback's constructor takes the manager itself, which is the
 * obfuscated type carrying the "autoscroll is on" flag. The gate is then the
 * one method that reads that flag, takes a `UserSession` and returns a boolean
 * — in 435.0.0.37.76 it is read right next to `MediaOption$Option.AUTO_SCROLL`
 * when the viewer's overflow menu is built.
 *
 * Deliberately not fatal. Part (a) of the patch (the pager lock) stops the
 * viewer moving whether or not this lands, and the network gate denies the
 * next reel regardless, so a drifted anchor here should not cost a build.
 */
private fun BytecodePatchContext.neutralizeAutoscroll(classes: List<ClassDef>) {
    val autoscrollClasses = classes.filter { it.type.startsWith(AUTOSCROLL_PACKAGE) }
    if (autoscrollClasses.isEmpty()) {
        println(
            "$LOG no classes under $AUTOSCROLL_PACKAGE; skipping the autoscroll gate. " +
                "The pager lock still stops the viewer advancing.",
        )
        return
    }

    // The manager type is what the lifecycle callbacks are constructed with.
    val managerTypes = autoscrollClasses.flatMap { classDef ->
        classDef.methods.filter { it.name == "<init>" }
            .flatMap { it.parameterTypes.map(CharSequence::toString) }
    }.toSet() + autoscrollClasses.map { it.type }

    val gates = classes.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.returnType == "Z" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].toString() == USER_SESSION &&
                method.implementation != null &&
                (method.instructionsOrNull ?: emptyList()).any { instruction ->
                    instruction.opcode == Opcode.IGET_BOOLEAN &&
                        instruction.fieldReference?.definingClass?.let { it in managerTypes } == true
                }
        }
    }

    val gate = gates.singleOrNull()
    if (gate == null) {
        println(
            "$LOG expected exactly one ($USER_SESSION)Z method reading the autoscroll flag on " +
                "${managerTypes.joinToString()}, found ${gates.size}: " +
                gates.joinToString { "${it.definingClass}->${it.name}" } +
                ". Skipping the autoscroll gate; the pager lock still stops the viewer advancing.",
        )
        return
    }

    val method = firstMethodOrNull(gate)
    val registerCount = method?.implementation?.registerCount
    if (method == null || registerCount == null) {
        println("$LOG could not open ${gate.definingClass}->${gate.name} for editing; autoscroll gate skipped")
        return
    }

    if (registerCount - gate.inputRegisters() < 1) {
        println(
            "$LOG ${gate.definingClass}->${gate.name} has no free local register " +
                "($registerCount registers, ${gate.inputRegisters()} of them inputs); autoscroll gate skipped",
        )
        return
    }

    method.addInstructions(
        0,
        """
            const/4 v0, 0x0
            return v0
        """,
    )

    println("$LOG autoscroll gate ${gate.definingClass}->${gate.name}($USER_SESSION)Z now returns false")
}
