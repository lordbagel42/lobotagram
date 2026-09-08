package dev.lobotagram.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstImmutableClassDefOrNull
import app.revanced.patcher.firstMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
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

/**
 * Activity-level hooks, most general first. All three are framework method
 * names on named Instagram classes.
 *
 * `BaseFragmentActivity` is the base of `InstagramMainActivity`, of
 * `ModalActivity` (which hosts most non-tab fragments, the Reels viewer among
 * them) and of the URL handlers a shared reel deep-links into, so hooking it
 * once reaches every window that can show a reel. That matters: the runtime
 * side drives everything off one `ViewTreeObserver`, and a `ViewTreeObserver`
 * only ever fires for its own window — a listener installed on the tab bar
 * cannot see the Reels viewer if the viewer opened in a different activity.
 */
private val ACTIVITY_HOOKS = listOf(
    "Lcom/instagram/base/activity/BaseFragmentActivity;" to ("onAttachedToWindow" to emptyList<String>()),
    "Lcom/instagram/base/activity/IgFragmentActivity;" to ("onWindowFocusChanged" to listOf("Z")),
    "Lcom/instagram/mainactivity/InstagramMainActivity;" to ("onWindowFocusChanged" to listOf("Z")),
)

private const val LOG = "[lobotagram]"

/**
 * A structural match on the main tab-bar binder's constructor.
 *
 * @param method the matching `<init>(Landroid/view/View;)V`
 * @param storeIndex index of the `iput-object` that stores the ViewGroup
 * @param register the register holding the ViewGroup at that point
 */
internal class TabBarBinder(
    val method: Method,
    val storeIndex: Int,
    val register: Int,
) {
    override fun toString() = "${method.definingClass}-><init>($VIEW) [iput-object #$storeIndex, v$register]"
}

/** What [locateTabBarHooks] found, so the diagnostics patch can print it. */
internal class TabBarAnchor(
    /** Level 1: every `<init>(View)` with the FeurStagram shape. */
    val structural: List<TabBarBinder>,
    /** Level 2: …whose ViewGroup came out of a `View.findViewById`. */
    val viewGroupFromLookup: List<TabBarBinder>,
    /** Level 3: …on a class that also builds a ViewGroup from a `UserSession`. */
    val withSessionAccessor: List<TabBarBinder>,
    /** The most specific level that identified exactly one candidate, or null. */
    val binder: TabBarBinder?,
    /** The activity-level hook, or null if none of [ACTIVITY_HOOKS] resolved. */
    val activityHook: Method?,
) {
    val activityHookDescriptor
        get() = activityHook?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        }
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
 * Resolves both hooks without modifying anything. Shared with the diagnostics
 * patch, which prints the candidate counts so a drifted shape is visible
 * without running the real patch.
 */
internal fun BytecodePatchContext.locateTabBarHooks(): TabBarAnchor {
    // Level 1: the FeurStagram shape. Level 2 requires the ViewGroup to have
    // come out of a findViewById on the constructor's own View, which is what a
    // tab-bar binder does and what a plain view holder does not. Level 3 adds
    // the named-type discriminator.
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
    // ambiguous is not worth guessing at: the activity hook below is slower to
    // take effect but cannot be wrong.
    val binder = listOf(withSessionAccessor, viewGroupFromLookup, structural)
        .firstOrNull { it.size == 1 }
        ?.single()

    val activityHook = ACTIVITY_HOOKS.firstNotNullOfOrNull { (type, signature) ->
        val (name, parameters) = signature
        firstImmutableClassDefOrNull(type)?.methods?.firstOrNull {
            it.name == name &&
                it.parameterTypes.map(Any::toString) == parameters &&
                it.returnType == "V" &&
                it.implementation != null &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
    }

    return TabBarAnchor(
        structural = structural,
        viewGroupFromLookup = viewGroupFromLookup,
        withSessionAccessor = withSessionAccessor,
        binder = binder,
        activityHook = activityHook,
    )
}

/**
 * Removes the Reels tab, the page behind it, and the Reels viewer's lateral
 * lane (P2 of `docs/04-patch-plan.md`).
 *
 * The patch itself only installs hooks; all the work happens at runtime in
 * `Hiders`, which resolves its targets by resource *name* on every layout pass.
 * That split is deliberate: view ids move between Instagram releases far more
 * often than resource names do, and a hider that re-runs survives Instagram
 * rebuilding the tab bar.
 *
 * Two hooks, and both are installed when both resolve:
 *
 * 1. The main tab-bar binder's constructor. It takes the tab-bar root `View`,
 *    pulls the `tab_bar` `ViewGroup` out of it with `findViewById` and stashes
 *    it in a field, alongside a sibling `View` field. That shape is matched
 *    rather than the obfuscated class name, and narrowed by a named-type
 *    discriminator (the binder class also exposes a
 *    `(UserSession, ...) -> ViewGroup` method) until exactly one method is
 *    left. `Hiders.install(ViewGroup)` is injected right after the store, with
 *    the ViewGroup that was just stored. This is the hook that hides the Reels
 *    tab the instant the bar is built.
 * 2. A framework override on a named activity base class — first choice
 *    `BaseFragmentActivity.onAttachedToWindow()`. `Hiders.install(Activity)`
 *    walks that window from its decor view. This one is not only a fallback:
 *    a `ViewTreeObserver` fires for one window, and the Reels viewer opens in
 *    `ModalActivity` or a URL-handler activity as often as in the main one, so
 *    without it `ViewerLock` and the Friends-lane hider would never see the
 *    viewer at all. `Hiders` is idempotent per root view, so the two hooks
 *    overlapping in the main activity's window is harmless.
 *
 * Only if *neither* resolves does the patch fail, with the candidate count at
 * each level and every structural candidate's class.
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
        val anchor = locateTabBarHooks()

        val binder = anchor.binder
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
        } else {
            println(
                "$LOG tab-bar binder not uniquely identified " +
                    "(${anchor.withSessionAccessor.size} discriminated, " +
                    "${anchor.viewGroupFromLookup.size} lookup-fed, " +
                    "${anchor.structural.size} structural); relying on the activity hook alone",
            )
        }

        val activityHook = anchor.activityHook
        if (activityHook == null) {
            if (binder == null) {
                throw PatchException(
                    "Neither hook resolved. The tab-bar binder was ambiguous (" +
                        "${anchor.withSessionAccessor.size} discriminated, " +
                        "${anchor.viewGroupFromLookup.size} lookup-fed, " +
                        "${anchor.structural.size} structural: " +
                        anchor.structural.joinToString { it.method.definingClass } +
                        "), and none of " +
                        ACTIVITY_HOOKS.joinToString { (type, signature) ->
                            "$type->${signature.first}(${signature.second.joinToString("")})V"
                        } +
                        " exists with a body. Re-run the recon in docs/patches/ui.md: find the " +
                        "class whose <init>($VIEW) stores the tab_bar ViewGroup and add a " +
                        "discriminator that holds in the new build, or point ACTIVITY_HOOKS at a " +
                        "framework override that runs after the content view is set.",
                )
            }

            println(
                "$LOG no activity hook resolved (tried " +
                    ACTIVITY_HOOKS.joinToString { it.first } +
                    "); Hiders will only see the tab bar's own window, so the Reels viewer lock " +
                    "will not run when the viewer opens in another activity",
            )
            return@apply
        }

        val method = firstMethodOrNull(activityHook)
            ?: throw PatchException(
                "Found ${anchor.activityHookDescriptor} but could not open it for editing.",
            )

        val registerCount = method.implementation?.registerCount
            ?: throw PatchException(
                "${anchor.activityHookDescriptor} has no implementation to inject into.",
            )

        method.addInstructions(
            0,
            listOf(staticCall(HIDERS_CLASS, INSTALL, ACTIVITY, activityHook.thisRegister(registerCount))),
        )

        println("$LOG activity hook: ${anchor.activityHookDescriptor}")
        println("$LOG injected $HIDERS_CLASS->$INSTALL($ACTIVITY)V at its method start")
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
