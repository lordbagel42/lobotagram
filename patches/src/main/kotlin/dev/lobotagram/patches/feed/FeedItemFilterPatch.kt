package dev.lobotagram.patches.feed

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.instructionsOrNull
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import dev.lobotagram.patches.shared.EXTENSION
import dev.lobotagram.patches.shared.EXTENSION_PACKAGE
import dev.lobotagram.patches.shared.compatibleWithInstagram

private const val FEED_FILTER_CLASS = EXTENSION_PACKAGE + "FeedFilter;"
private const val REPLACE_FEED_ITEM_TYPE =
    "replaceFeedItemType(Ljava/lang/String;)Ljava/lang/String;"

/**
 * The wire token for the "Suggested reels" unit. A wire protocol string, not a
 * code name, so it survives obfuscation.
 */
internal const val CLIPS_NETEGO = "clips_netego"

/**
 * Other feed-unit tokens the same parser must know. At least one has to be
 * present, so retiring a single unit type does not take the patch down: a hard
 * requirement on all of them is what broke FeurStagram when `in_feed_survey`
 * was dropped from the parser.
 */
internal val FEED_UNIT_COMPANIONS = listOf("stories_netego", "suggested_users", "bloks_netego")

/**
 * The non-obfuscated part of the parser method name. Instagram's generated
 * deserialisers are called `parseFromJson`, `unsafeParseFromJson`,
 * `parseFromJsonParser`, ...; the serialiser in the same class carries the same
 * type tokens and must not be hooked, which is what this filter is for.
 */
internal const val PARSE_FROM_JSON = "parsefromjson"

/** Where a feed-item parser's type-token dispatch can be hooked. */
internal class FeedItemHook(
    val method: Method,
    /** Index of the `invoke-virtual String.hashCode()`, the switch's discriminant. */
    val hashCodeIndex: Int,
    /** Register holding the type token. */
    val keyRegister: Int,
    /** Index of the `move-result-object` that put the token in [keyRegister]. */
    val keyLoadIndex: Int,
)

/** The register an invoke instruction passes as its first argument. */
private val Instruction.firstArgumentRegister: Int?
    get() = when (this) {
        is RegisterRangeInstruction -> if (registerCount > 0) startRegister else null
        is FiveRegisterInstruction -> registerC
        else -> null
    }

/**
 * Every feed-item deserialiser: a method that carries the feed-unit type tokens
 * and whose name marks it as a parser rather than the serialiser next to it.
 *
 * Uses the patcher's string index, so this costs a map lookup rather than a scan
 * of every method in twenty dex files.
 */
internal fun BytecodePatchContext.locateFeedItemParsers(): List<Method> {
    val withClipsNetego = classDefs.methodsByString[CLIPS_NETEGO].orEmpty()
    val companions = FEED_UNIT_COMPANIONS.map { classDefs.methodsByString[it].orEmpty() }

    return withClipsNetego.filter { method ->
        method.name.lowercase().contains(PARSE_FROM_JSON) &&
            companions.any { method in it }
    }
}

/**
 * Finds the type-token register in a parser.
 *
 * The parse loop reads each item's JSON field name, then dispatches on
 * `String.hashCode()` through a sparse switch. So: find that `hashCode()` call,
 * take the register it is invoked on, and walk back to the `move-result-object`
 * that filled it. That is the token, and rewriting it there lands before both
 * the null check and the switch.
 *
 * More precise than FeurStagram's "last move-result-object before the first
 * const-string/jumbo", which happens to find the same instruction here but only
 * because nothing else materialises an object first.
 */
internal fun findFeedItemHook(method: Method): FeedItemHook? {
    val instructions = method.instructionsOrNull?.toList() ?: return null

    val hashCode = instructions.withIndex().firstOrNull { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            instruction.methodReference?.let {
                it.definingClass == "Ljava/lang/String;" &&
                    it.name == "hashCode" &&
                    it.parameterTypes.isEmpty()
            } == true
    } ?: return null

    val keyRegister = hashCode.value.firstArgumentRegister ?: return null

    val keyLoadIndex = (hashCode.index - 1 downTo 0).firstOrNull { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (instruction as OneRegisterInstruction).registerA == keyRegister
    } ?: return null

    return FeedItemHook(method, hashCode.index, keyRegister, keyLoadIndex)
}

/**
 * P5: drop the "Suggested reels" unit from the home feed.
 *
 * Feed units are injected inline into the timeline payload, so they never have a
 * URL of their own and the network gate cannot see them. Rewriting the unit's
 * type token to something the parser does not know routes it to the parser's own
 * unknown-type branch, which skips the value. Adapted from FeurStagram's
 * `FeedItemFilterPatch` (GPLv3, https://github.com/feurstagram).
 */
@Suppress("unused")
val feedItemFilterPatch = bytecodePatch(
    name = "Hide suggested Reels in feed",
    description = "Drops the \"Suggested reels\" unit from the home feed at the JSON-parse layer, " +
        "which is the one route into Reels that has no request of its own to block.",
) {
    compatibleWithInstagram()
    extendWith(EXTENSION)

    apply {
        val parsers = locateFeedItemParsers()

        if (parsers.isEmpty()) {
            throw PatchException(
                "No feed-item deserialiser found: no method contains the string \"$CLIPS_NETEGO\" " +
                    "together with one of ${FEED_UNIT_COMPANIONS.joinToString()} and a name " +
                    "containing \"$PARSE_FROM_JSON\". Either Instagram renamed the feed-unit wire " +
                    "tokens (grep -a the dex files for \"netego\") or the generated parsers are no " +
                    "longer called *parseFromJson*. Update CLIPS_NETEGO / FEED_UNIT_COMPANIONS / " +
                    "PARSE_FROM_JSON in patches/feed/FeedItemFilterPatch.kt.",
            )
        }

        val hooks = parsers.mapNotNull(::findFeedItemHook)

        if (hooks.isEmpty()) {
            throw PatchException(
                "Found ${parsers.size} feed-item deserialiser(s) (" +
                    parsers.joinToString { "${it.definingClass}->${it.name}" } +
                    ") but none dispatches on String.hashCode(), so the type-token register cannot " +
                    "be identified. Baksmali one of them, find where the JSON field name is " +
                    "compared, and adjust findFeedItemHook in " +
                    "patches/feed/FeedItemFilterPatch.kt.",
            )
        }

        hooks.forEach { hook ->
            val method = firstMethod(hook.method)

            // Re-derived on the mutable copy rather than trusting indices across
            // the immutable/mutable boundary.
            val mutableHook = findFeedItemHook(method)
                ?: throw PatchException(
                    "The hook point in ${hook.method.definingClass}->${hook.method.name} " +
                        "disappeared between reading the method and patching it. Another patch in " +
                        "this bundle is rewriting the same method; run them one at a time to see " +
                        "which.",
                )

            val register = mutableHook.keyRegister

            method.addInstructions(
                mutableHook.keyLoadIndex + 1,
                """
                    invoke-static/range { v$register .. v$register }, $FEED_FILTER_CLASS->$REPLACE_FEED_ITEM_TYPE
                    move-result-object v$register
                """.trimIndent(),
            )
        }
    }
}
