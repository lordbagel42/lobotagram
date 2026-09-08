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
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
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

/**
 * Package holding the clips-viewer *interfaces* — the viewer config and the
 * source enum itself. A constructor here is the single point every path that
 * opens a reel goes through, so it outranks every other candidate.
 */
private const val CLIPS_INTF_PACKAGE = "Lcom/instagram/clips/intf/"

/** The app-side clips package: the second-best place to record an entry point. */
private const val CLIPS_PACKAGE = "Lcom/instagram/clips/"

/** The viewer feature package, ranked last of the three named tiers. */
private const val CLIPS_VIEWER_PACKAGE = "Linstagram/features/clips/viewer/"

/** Package of the Reels autoscroll controller. Non-obfuscated. */
private const val AUTOSCROLL_PACKAGE = "Linstagram/features/clips/viewer/controller/autoscroll/"

/**
 * Types the autoscroll manager can never be: the framework, the JDK, the
 * Kotlin runtime, and the logged-in session, which every Instagram
 * constructor takes. Excluding them is what leaves the obfuscated manager.
 */
private val NON_MANAGER_TYPE_PREFIXES = listOf("Landroid/", "Ljava/", "Lkotlin/", "Lkotlinx/")

/** Whether a descriptor is a framework/JDK/Kotlin type or the user session. */
private fun String.isNonManagerType() =
    this == USER_SESSION || NON_MANAGER_TYPE_PREFIXES.any { startsWith(it) }

/**
 * Upper bound on entry-point hooks. One is expected (the viewer config's
 * constructor); the cap exists so that a build which suddenly threads the
 * source enum through fifty clips methods does not get fifty injections.
 */
private const val MAX_ENTRY_POINT_HOOKS = 10

private const val LOG = "[lobotagram]"

/** A method to hook, and which of its parameters is the source enum. */
internal class EntryPoint(val method: Method, val parameterIndex: Int) {
    /** Name and parameters, without the defining class: the sort's tie-breaker. */
    val descriptor = "${method.name}(${method.parameterTypes.joinToString(",")})${method.returnType}"

    val signature =
        "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString(",")})"

    /**
     * Which tier this candidate belongs to; lower is hooked first. The tiers
     * exist so that a future build threading the source enum through many
     * clips methods still hooks the one place every reel-opening path goes
     * through, instead of whatever happens to sort first alphabetically:
     *
     * 0. a constructor under [CLIPS_INTF_PACKAGE] — the viewer config,
     * 1. any other method under [CLIPS_PACKAGE],
     * 2. a method under [CLIPS_VIEWER_PACKAGE],
     * 3. anything else a widened [CLIPS_PACKAGES] brings in.
     */
    val tier = when {
        method.name == "<init>" && method.definingClass.startsWith(CLIPS_INTF_PACKAGE) -> 0
        method.definingClass.startsWith(CLIPS_PACKAGE) -> 1
        method.definingClass.startsWith(CLIPS_VIEWER_PACKAGE) -> 2
        else -> 3
    }

    override fun toString() = "$signature [parameter #$parameterIndex]"
}

/** Tier first, then defining class, then descriptor: stable across builds. */
private val ENTRY_POINT_ORDER =
    compareBy<EntryPoint>({ it.tier }, { it.method.definingClass }, { it.descriptor })

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

/** What [locateClipsAnchors] found, so the diagnostics patch can print it. */
internal class ClipsAnchor(
    /** Every non-obfuscated enum whose `<clinit>` holds all of [SOURCE_ENUM_STRINGS]. */
    val sourceEnums: List<String>,
    /** Every enum in the APK that mentions `clips_tab` at all, for the error message. */
    val enumsMentioningClipsTab: List<String>,
    /** The clips-viewer source enum, or null when it was not unique. */
    val sourceEnum: String?,
    /** Clips-package methods that take that enum, ranked and unbounded. */
    val entryPoints: List<EntryPoint>,
    /** Candidate autoscroll gates; exactly one is expected. */
    val autoscrollGates: List<Method>,
    /** Types the autoscroll gate's flag may be read off. */
    val autoscrollManagerTypes: Set<String>,
)

/**
 * Resolves the clips-viewer source enum, its injection sites and the autoscroll
 * gate without modifying anything. Shared with the diagnostics patch.
 */
internal fun BytecodePatchContext.locateClipsAnchors(): ClipsAnchor {
    val classes = classDefs.toList()

    val sourceEnums = classes.filter { classDef ->
        classDef.superclass == ENUM &&
            !classDef.type.startsWith("LX/") &&
            classDef.methods.any { method ->
                method.name == "<clinit>" &&
                    method.stringConstants().containsAll(SOURCE_ENUM_STRINGS)
            }
    }.map { it.type }

    val sourceEnum = sourceEnums.singleOrNull()

    val entryPoints = if (sourceEnum == null) {
        emptyList()
    } else {
        classes
            .filter { classDef -> CLIPS_PACKAGES.any { classDef.type.startsWith(it) } }
            .flatMap { classDef ->
                classDef.methods.mapNotNull { method ->
                    if (method.implementation == null) return@mapNotNull null
                    val index = method.parameterTypes.indexOfFirst { it.toString() == sourceEnum }
                    if (index < 0) null else EntryPoint(method, index)
                }
            }
            // Deterministic and meaningful order, so the cap below keeps the
            // hook that matters rather than the alphabetically luckiest one.
            .sortedWith(ENTRY_POINT_ORDER)
    }

    val autoscrollClasses = classes.filter { it.type.startsWith(AUTOSCROLL_PACKAGE) }
    // The manager type is what the lifecycle callbacks are constructed with.
    // Everything the framework, the JDK, Kotlin or the session contributes is
    // dropped first; of what is left, a type that actually declares a boolean
    // field is preferred, because the flag the gate reads is one of those.
    val autoscrollConstructorTypes = autoscrollClasses.flatMap { classDef ->
        classDef.methods.filter { it.name == "<init>" }
            .flatMap { it.parameterTypes.map(CharSequence::toString) }
    }.filterNot { it.isNonManagerType() }.toSet()
    val withBooleanField = classes
        .filter { it.type in autoscrollConstructorTypes }
        .filter { classDef -> classDef.fields.any { it.type == "Z" } }
        .map { it.type }
        .toSet()
    val autoscrollManagerTypes =
        withBooleanField.ifEmpty { autoscrollConstructorTypes } + autoscrollClasses.map { it.type }

    val autoscrollGates = if (autoscrollClasses.isEmpty()) {
        emptyList()
    } else {
        classes.flatMap { classDef ->
            classDef.methods.filter { method ->
                method.returnType == "Z" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].toString() == USER_SESSION &&
                    method.implementation != null &&
                    (method.instructionsOrNull ?: emptyList()).any { instruction ->
                        instruction.opcode == Opcode.IGET_BOOLEAN &&
                            instruction.fieldReference?.definingClass
                                ?.let { it in autoscrollManagerTypes } == true
                    }
            }
        }
    }

    return ClipsAnchor(
        sourceEnums = sourceEnums,
        enumsMentioningClipsTab = classes.filter { it.superclass == ENUM }
            .filter { cd -> cd.methods.any { "clips_tab" in it.stringConstants() } }
            .map { it.type },
        sourceEnum = sourceEnum,
        entryPoints = entryPoints,
        autoscrollGates = autoscrollGates,
        autoscrollManagerTypes = autoscrollManagerTypes,
    )
}

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
        val anchor = locateClipsAnchors()

        // --- (b) entry-point tracking -------------------------------------
        val sourceEnum = anchor.sourceEnum ?: throw PatchException(
            "Expected exactly one non-obfuscated enum whose <clinit> holds " +
                SOURCE_ENUM_STRINGS.joinToString(" and ") { "\"$it\"" } +
                ", found ${anchor.sourceEnums.size}: ${anchor.sourceEnums.joinToString()}. " +
                "Every enum holding \"clips_tab\" in this APK is: " +
                anchor.enumsMentioningClipsTab.joinToString() +
                ". Pick the clips-viewer source enum and update SOURCE_ENUM_STRINGS.",
        )

        val entryPoints = anchor.entryPoints

        if (entryPoints.isEmpty()) {
            throw PatchException(
                "Found the clips-viewer source enum $sourceEnum but no method in " +
                    CLIPS_PACKAGES.joinToString(" or ") +
                    " takes it as a parameter, so there is nowhere to record the entry point. " +
                    "Widen CLIPS_PACKAGES to the package that now holds the viewer config, or " +
                    "hook the enum's own factory methods instead.",
            )
        }

        // The cap applies to the *ranked* list, so the constructor of the
        // viewer config is never the candidate that gets dropped.
        val hooks = entryPoints.take(MAX_ENTRY_POINT_HOOKS)
        val dropped = entryPoints.size - hooks.size
        if (dropped > 0) {
            println(
                "$LOG ${entryPoints.size} clips methods take $sourceEnum; hooking the " +
                    "$MAX_ENTRY_POINT_HOOKS highest-ranked and dropping $dropped: " +
                    entryPoints.drop(MAX_ENTRY_POINT_HOOKS)
                        .joinToString { "${it.method.definingClass}->${it.descriptor}" },
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
        neutralizeAutoscroll(anchor)
    }
}

/**
 * Makes the Reels autoscroll gate return false, if it can be identified.
 *
 * The chain is all name-anchored: the autoscroll package
 * (`instagram.features.clips.viewer.controller.autoscroll`) survives
 * minification and holds one class, the session manager's activity-lifecycle
 * callback. That callback's constructor takes the manager itself, which is the
 * obfuscated type carrying the "autoscroll is on" flag — found by dropping the
 * framework, JDK, Kotlin and `UserSession` parameters and keeping the types
 * that declare a boolean field. The gate is then the
 * one method that reads that flag, takes a `UserSession` and returns a boolean
 * — in 435.0.0.37.76 it is read right next to `MediaOption$Option.AUTO_SCROLL`
 * when the viewer's overflow menu is built.
 *
 * Deliberately not fatal. Part (a) of the patch (the pager lock) stops the
 * viewer moving whether or not this lands, and the network gate denies the
 * next reel regardless, so a drifted anchor here should not cost a build.
 */
private fun BytecodePatchContext.neutralizeAutoscroll(anchor: ClipsAnchor) {
    if (anchor.autoscrollManagerTypes.isEmpty()) {
        println(
            "$LOG no classes under $AUTOSCROLL_PACKAGE; skipping the autoscroll gate. " +
                "The pager lock still stops the viewer advancing.",
        )
        return
    }

    val gate = anchor.autoscrollGates.singleOrNull()
    if (gate == null) {
        println(
            "$LOG expected exactly one ($USER_SESSION)Z method reading the autoscroll flag on " +
                "${anchor.autoscrollManagerTypes.joinToString()}, found " +
                "${anchor.autoscrollGates.size}: " +
                anchor.autoscrollGates.joinToString {
                    "${it.definingClass}->${it.name}" +
                        "(${it.parameterTypes.joinToString(",")})${it.returnType}"
                } +
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

    // A fresh implementation rather than a prepended return: the old body may
    // have try blocks, and leaving them behind a dead `return` keeps handlers
    // pointing at code that can no longer run. The register count is kept, so
    // it never drops below the parameter count, and v0 is safe to clobber
    // because nothing after the return is reachable.
    method.implementation = MutableMethodImplementation(maxOf(1, registerCount))
    method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

    println("$LOG autoscroll gate ${gate.definingClass}->${gate.name}($USER_SESSION)Z now returns false")
}
