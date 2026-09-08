package dev.lobotagram.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstImmutableClassDefOrNull
import app.revanced.patcher.firstMethodOrNull
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.lobotagram.patches.shared.EXTENSION
import dev.lobotagram.patches.shared.EXTENSION_PACKAGE
import dev.lobotagram.patches.shared.compatibleWithInstagram

private const val HIDERS_CLASS = EXTENSION_PACKAGE + "Hiders;"
private const val INSTALL = "install"

private const val VIEW = "Landroid/view/View;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val ACTIVITY = "Landroid/app/Activity;"

/** Named, non-obfuscated, and the only thing the tab bar cannot be built without. */
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** Named class. Its own methods are obfuscated; its framework overrides are not. */
private const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"
private const val ON_WINDOW_FOCUS_CHANGED = "onWindowFocusChanged"

private const val LOG = "[lobotagram]"

/**
 * A structural match on the main tab-bar binder's constructor.
 *
 * @param method the matching `<init>(Landroid/view/View;)V`
 * @param storeIndex index of the `iput-object` that stores the ViewGroup
 * @param register the register holding the ViewGroup at that point
 */
private class TabBarBinder(
    val method: Method,
    val storeIndex: Int,
    val register: Int,
) {
    override fun toString() = "${method.definingClass}-><init>($VIEW) [iput-object #$storeIndex, v$register]"
}

/** Number of registers a method's parameters (plus `this`) occupy. */
private fun Method.inputRegisters(): Int {
    var count = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    parameterTypes.forEach { count += if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return count
}

/** The register `this` lives in, given the method's total register count. */
private fun Method.thisRegister(registerCount: Int): Int {
    require(!AccessFlags.STATIC.isSet(accessFlags)) { "$this is static and has no receiver" }
    return registerCount - inputRegisters()
}

/**
 * A one-argument static call, built as `invoke-static/range` rather than smali
 * text so it is valid for any register number: the interesting registers in
 * Instagram's larger methods sit well above v15, which the compact
 * `invoke-static {vN}` form cannot address.
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
 * Removes the Reels tab, the page behind it, and the Reels viewer's lateral
 * lane (P2 of `docs/04-patch-plan.md`).
 *
 * The patch itself only installs a hook; all the work happens at runtime in
 * `Hiders`, which resolves its targets by resource *name* on every layout pass.
 * That split is deliberate: view ids move between Instagram releases far more
 * often than resource names do, and a hider that re-runs survives Instagram
 * rebuilding the tab bar.
 *
 * Two hooks are tried, in order:
 *
 * 1. The main tab-bar binder's constructor. It takes the tab-bar root `View`,
 *    pulls the `tab_bar` `ViewGroup` out of it with `findViewById` and stashes
 *    it in a field, alongside a sibling `View` field. That shape is matched
 *    rather than the obfuscated class name, and narrowed by a named-type
 *    discriminator (the binder class also exposes a
 *    `(UserSession, ...) -> ViewGroup` method) until exactly one method is
 *    left. `Hiders.install(ViewGroup)` is injected right after the store, with
 *    the ViewGroup that was just stored.
 * 2. `InstagramMainActivity.onWindowFocusChanged(boolean)` — a framework
 *    override on a named class, so it cannot be renamed. `Hiders.install(Activity)`
 *    walks the window from the decor view instead. Slower to take effect and
 *    fires repeatedly (the runtime side is idempotent), but name-anchored.
 */
@Suppress("unused")
val tabBarPatch = bytecodePatch(
    name = "Hide Reels tab",
    description = "Removes the Reels tab from the navigation bar, keeps horizontal swipes off the " +
        "page behind it, and hides the Friends/Blend lane in the Reels viewer.",
) {
    compatibleWithInstagram()
    extendWith(EXTENSION)

    apply {
        // Level 1: the FeurStagram shape. Level 2 requires the ViewGroup to
        // have come out of a findViewById on the constructor's own View, which
        // is what a tab-bar binder does and what a plain view holder does not.
        // Level 3 adds the named-type discriminator.
        val structural = mutableListOf<TabBarBinder>()
        val viewGroupFromLookup = mutableListOf<TabBarBinder>()
        val withSessionAccessor = mutableListOf<TabBarBinder>()

        classDefs.toList().forEach { classDef ->
            val sessionViewGroupAccessor by lazy { classDef.hasSessionToViewGroupMethod() }

            classDef.methods.forEach { method ->
                if (method.name != "<init>") return@forEach
                if (method.parameterTypes.size != 1 || method.parameterTypes[0].toString() != VIEW) return@forEach

                val instructions = method.instructionsOrNull?.toList() ?: return@forEach

                var storeIndex = -1
                var register = -1
                var storeFollowsLookup = false
                var storesView = false
                var sawViewLookup = false

                instructions.forEachIndexed { index, instruction ->
                    val callee = instruction.methodReference
                    if (callee != null && callee.definingClass == VIEW &&
                        (callee.name == "findViewById" || callee.name == "requireViewById")
                    ) {
                        sawViewLookup = true
                    }

                    if (instruction.opcode != Opcode.IPUT_OBJECT) return@forEachIndexed
                    when (instruction.fieldReference?.type) {
                        VIEW_GROUP -> if (storeIndex < 0) {
                            storeIndex = index
                            register = (instruction as TwoRegisterInstruction).registerA
                            storeFollowsLookup = sawViewLookup
                        }

                        VIEW -> storesView = true
                    }
                }

                if (storeIndex < 0 || !storesView) return@forEach

                val candidate = TabBarBinder(method, storeIndex, register)
                structural += candidate
                if (!storeFollowsLookup) return@forEach
                viewGroupFromLookup += candidate
                if (sessionViewGroupAccessor) withSessionAccessor += candidate
            }
        }

        // Most specific level that identifies exactly one method wins. Anything
        // ambiguous is not worth guessing at: the named-class fallback below is
        // slower but cannot be wrong.
        val binder = listOf(withSessionAccessor, viewGroupFromLookup, structural)
            .firstOrNull { it.size == 1 }
            ?.single()

        if (binder != null) {
            val method = firstMethodOrNull(binder.method)
                ?: throw PatchException(
                    "Matched the tab-bar binder ${binder.method.definingClass} but could not open it " +
                        "for editing. This is a patcher-side failure, not a fingerprint drift.",
                )

            method.addInstructions(
                binder.storeIndex + 1,
                listOf(staticCall(HIDERS_CLASS, INSTALL, VIEW_GROUP, binder.register)),
            )

            println("$LOG tab-bar binder: $binder")
            println("$LOG injected $HIDERS_CLASS->$INSTALL($VIEW_GROUP)V after the tab_bar store")
            return@apply
        }

        // Fallback: a framework override on a named class.
        val mainActivity = firstImmutableClassDefOrNull(MAIN_ACTIVITY)
            ?: throw PatchException(
                "The tab-bar binder was ambiguous (" +
                    "${withSessionAccessor.size} discriminated, ${viewGroupFromLookup.size} lookup-fed, " +
                    "${structural.size} structural: ${structural.joinToString { it.method.definingClass }}" +
                    ") and $MAIN_ACTIVITY is missing, so there is no fallback either. Re-run the " +
                    "recon in docs/patches/ui.md: find the class whose <init>($VIEW) stores the " +
                    "tab_bar ViewGroup and add a discriminator that holds in the new build.",
            )

        val focusChanged = mainActivity.methods.firstOrNull {
            it.name == ON_WINDOW_FOCUS_CHANGED &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() == "Z" &&
                it.implementation != null
        } ?: throw PatchException(
            "The tab-bar binder was ambiguous (${structural.size} structural candidates: " +
                structural.joinToString { it.method.definingClass } +
                ") and $MAIN_ACTIVITY does not override $ON_WINDOW_FOCUS_CHANGED(Z)V. Its methods " +
                "with a single parameter are: " +
                mainActivity.methods.filter { it.parameterTypes.size == 1 }
                    .joinToString { "${it.name}(${it.parameterTypes.joinToString()})" } +
                ". Pick another framework override that runs after the content view is set and " +
                "update ON_WINDOW_FOCUS_CHANGED.",
        )

        val method = firstMethodOrNull(focusChanged)
            ?: throw PatchException(
                "Found $MAIN_ACTIVITY->$ON_WINDOW_FOCUS_CHANGED but could not open it for editing.",
            )

        val registerCount = method.implementation?.registerCount
            ?: throw PatchException(
                "$MAIN_ACTIVITY->$ON_WINDOW_FOCUS_CHANGED has no implementation to inject into.",
            )

        method.addInstructions(
            0,
            listOf(staticCall(HIDERS_CLASS, INSTALL, ACTIVITY, focusChanged.thisRegister(registerCount))),
        )

        println(
            "$LOG tab-bar binder not uniquely identified (${structural.size} structural candidates); " +
                "fell back to $MAIN_ACTIVITY->$ON_WINDOW_FOCUS_CHANGED",
        )
        println("$LOG injected $HIDERS_CLASS->$INSTALL($ACTIVITY)V at method start")
    }
}

/**
 * Whether a class exposes a `(..., UserSession, ...) -> ViewGroup` method.
 *
 * The tab-bar binder builds each tab's container from the logged-in session,
 * so it has one; view holders that merely happen to cache a ViewGroup and a
 * View do not. Both types in the signature are named, which is the point.
 */
private fun ClassDef.hasSessionToViewGroupMethod() = methods.any { method ->
    method.returnType == VIEW_GROUP && method.parameterTypes.any { it.toString() == USER_SESSION }
}
